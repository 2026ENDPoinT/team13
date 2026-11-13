// "이어서 녹음": a recording continued later is joined to the first file without re-encoding. Both are WebM files with
// one Opus track as MediaRecorder writes them. The output keeps the first file's EBML header, Info (Duration
// removed, so players learn the length as they already do for MediaRecorder files) and Tracks, then the clusters of
// the first file, then the clusters of the second with their timecodes moved to follow the first. SeekHead, Cues and
// Tags would point at old positions and are dropped. Blocks keep their bytes: their times are relative to the cluster.

const ID = {
  ebml: 0x1a45dfa3,
  segment: 0x18538067,
  info: 0x1549a966,
  timecodeScale: 0x2ad7b1,
  duration: 0x4489,
  tracks: 0x1654ae6b,
  trackEntry: 0xae,
  trackNumber: 0xd7,
  codecId: 0x86,
  audio: 0xe1,
  samplingFrequency: 0xb5,
  channels: 0x9f,
  cluster: 0x1f43b675,
  timecode: 0xe7,
  simpleBlock: 0xa3,
  blockGroup: 0xa0,
  block: 0xa1,
} as const;

// Segment children: an unknown-size cluster ends where one of these starts.
const SEGMENT_LEVEL = new Set([0x114d9b74, ID.info, ID.tracks, ID.cluster, 0x1c53bb6b, 0x1043a770, 0x1254c367, 0x1941a469]);

const fail = (why: string): never => {
  throw new Error(`이어 붙일 수 없는 녹음 파일입니다(${why}).`);
};

interface Element {
  id: number;
  /** Where the element (its ID) starts. */
  start: number;
  /** Where its data starts. */
  data: number;
  /** Where it ends; for an unknown size, found by the caller. */
  end: number;
  unknownSize: boolean;
}

/** One EBML element header at `at`. */
function header(bytes: Uint8Array, at: number, limit: number): Element {
  if (at >= limit) fail("파일이 중간에 끝남");
  const first = bytes[at];
  const idLength = first >= 0x80 ? 1 : first >= 0x40 ? 2 : first >= 0x20 ? 3 : first >= 0x10 ? 4 : 0;
  if (!idLength || at + idLength > limit) fail("요소 ID");
  let id = 0;
  for (let i = 0; i < idLength; i++) id = id * 256 + bytes[at + i];
  const sizeAt = at + idLength;
  if (sizeAt >= limit) fail("파일이 중간에 끝남");
  const lead = bytes[sizeAt];
  let sizeLength = 1;
  while (sizeLength <= 8 && !(lead & (0x80 >> (sizeLength - 1)))) sizeLength++;
  if (sizeLength > 8 || sizeAt + sizeLength > limit) fail("요소 크기");
  let size = lead & (0xff >> sizeLength);
  let allOnes = size === 0xff >> sizeLength;
  for (let i = 1; i < sizeLength; i++) {
    const byte = bytes[sizeAt + i];
    size = size * 256 + byte;
    if (byte !== 0xff) allOnes = false;
  }
  const data = sizeAt + sizeLength;
  return { id, start: at, data, end: allOnes ? limit : data + size, unknownSize: allOnes };
}

/**
 * Children of `[from, to)`. With `stopAt`, an unknown-size child (a MediaRecorder cluster) ends where the next element
 * of `stopAt` begins; without it, an unknown-size child (the segment) runs to `to`.
 */
function children(bytes: Uint8Array, from: number, to: number, stopAt?: Set<number>): Element[] {
  const list: Element[] = [];
  let at = from;
  while (at < to) {
    const element = header(bytes, at, to);
    if (element.end > to) fail("요소가 파일보다 김");
    if (element.unknownSize && stopAt) {
      let next = element.data;
      while (next < to) {
        const child = header(bytes, next, to);
        if (stopAt.has(child.id)) break;
        if (child.unknownSize) fail("크기를 알 수 없는 하위 요소");
        if (child.end > to) fail("요소가 파일보다 김");
        next = child.end;
      }
      element.end = next;
    }
    list.push(element);
    at = element.end;
  }
  return list;
}

const uint = (bytes: Uint8Array, element: Element) => {
  let value = 0;
  for (let i = element.data; i < element.end; i++) value = value * 256 + bytes[i];
  return value;
};

function float(bytes: Uint8Array, element: Element): number {
  const view = new DataView(bytes.buffer, bytes.byteOffset + element.data, element.end - element.data);
  return element.end - element.data === 4 ? view.getFloat32(0) : element.end - element.data === 8 ? view.getFloat64(0) : NaN;
}

interface Parsed {
  bytes: Uint8Array;
  ebml: Element;
  info: Element;
  tracks: Element;
  clusters: Element[];
  scale: number;
  track: { number: number; codec: string; rate: number; channels: number };
}

function parse(bytes: Uint8Array): Parsed {
  const top = children(bytes, 0, bytes.length);
  const ebml = top.find((element) => element.id === ID.ebml);
  const segment = top.find((element) => element.id === ID.segment);
  if (!ebml || !segment) fail("WebM 아님");
  const parts = children(bytes, segment!.data, segment!.end, SEGMENT_LEVEL);
  const info = parts.find((element) => element.id === ID.info);
  const tracks = parts.find((element) => element.id === ID.tracks);
  const clusters = parts.filter((element) => element.id === ID.cluster);
  if (!info || !tracks || !clusters.length) fail("트랙 또는 소리 없음");
  const scaleElement = children(bytes, info!.data, info!.end).find((element) => element.id === ID.timecodeScale);
  const entries = children(bytes, tracks!.data, tracks!.end).filter((element) => element.id === ID.trackEntry);
  if (entries.length !== 1) fail("오디오 트랙이 하나가 아님");
  const fields = children(bytes, entries[0].data, entries[0].end);
  const field = (id: number) => fields.find((element) => element.id === id);
  const audio = field(ID.audio);
  const audioFields = audio ? children(bytes, audio.data, audio.end) : [];
  const rate = audioFields.find((element) => element.id === ID.samplingFrequency);
  const channels = audioFields.find((element) => element.id === ID.channels);
  const codec = field(ID.codecId);
  const number = field(ID.trackNumber);
  return {
    bytes, ebml: ebml!, info: info!, tracks: tracks!, clusters,
    scale: scaleElement ? uint(bytes, scaleElement) : 1000000,
    track: {
      number: number ? uint(bytes, number) : 0,
      codec: codec ? new TextDecoder().decode(bytes.subarray(codec.data, codec.end)).replace(/\0+$/, "") : "",
      rate: rate ? float(bytes, rate) : 8000,
      channels: channels ? uint(bytes, channels) : 1,
    },
  };
}

/** Absolute block times of a cluster (in timecode units), in file order. */
function blockTimes(bytes: Uint8Array, cluster: Element): { timecode: Element | null; times: number[] } {
  const parts = children(bytes, cluster.data, cluster.end);
  const timecode = parts.find((element) => element.id === ID.timecode) || null;
  const base = timecode ? uint(bytes, timecode) : 0;
  const times: number[] = [];
  const blockAt = (element: Element) => {
    // Track number (EBML varint), then a signed 16-bit time relative to the cluster.
    const lead = bytes[element.data];
    let length = 1;
    while (length <= 8 && !(lead & (0x80 >> (length - 1)))) length++;
    const at = element.data + length;
    if (length > 8 || at + 2 > element.end) fail("블록");
    times.push(base + ((bytes[at] << 24) >> 16 | bytes[at + 1]));
  };
  for (const part of parts) {
    if (part.id === ID.simpleBlock) blockAt(part);
    else if (part.id === ID.blockGroup) {
      const block = children(bytes, part.data, part.end).find((element) => element.id === ID.block);
      if (block) blockAt(block);
    }
  }
  return { timecode, times };
}

function encodeId(id: number): number[] {
  const out: number[] = [];
  for (let value = id; value > 0; value = Math.floor(value / 256)) out.unshift(value % 256);
  return out;
}

/** An 8-byte EBML size. */
function encodeSize(size: number): number[] {
  const out = [0x01];
  for (let i = 6; i >= 0; i--) out.push(Math.floor(size / 256 ** i) % 256);
  return out;
}

function encodeUint(id: number, value: number): Uint8Array {
  const digits: number[] = [];
  for (let rest = Math.max(0, Math.round(value)); rest > 0 || !digits.length; rest = Math.floor(rest / 256)) digits.unshift(rest % 256);
  return new Uint8Array([...encodeId(id), 0x80 | digits.length, ...digits]);
}

const wrap = (id: number, parts: Uint8Array[]): Uint8Array[] => {
  const size = parts.reduce((sum, part) => sum + part.length, 0);
  return [new Uint8Array([...encodeId(id), ...encodeSize(size)]), ...parts];
};

/** True when `mimeType` is a file `appendWebm` may join (MediaRecorder WebM). */
export const isAppendable = (mimeType: string): boolean => /^audio\/webm\b/i.test(mimeType || "");

/** The first recording followed by the second, as one WebM file. Throws (Korean message) when they cannot be joined. */
export async function appendWebm(first: Blob, second: Blob): Promise<Blob> {
  const a = parse(new Uint8Array(await first.arrayBuffer()));
  const b = parse(new Uint8Array(await second.arrayBuffer()));
  const same = a.scale === b.scale && a.track.number === b.track.number && a.track.codec === b.track.codec
    && a.track.rate === b.track.rate && a.track.channels === b.track.channels;
  if (!same) fail("두 녹음의 소리 형식이 다름");
  const aTimes = a.clusters.flatMap((cluster) => blockTimes(a.bytes, cluster).times);
  const bClusters = b.clusters.map((cluster) => ({ cluster, ...blockTimes(b.bytes, cluster) }));
  const bStart = Math.min(...bClusters.flatMap((entry) => entry.times));
  if (!aTimes.length || !Number.isFinite(bStart)) fail("소리 블록 없음");
  // The next block of the first file would have come one frame after its last one.
  const last = Math.max(...aTimes);
  const frame = aTimes.length > 1 ? Math.max(1, last - aTimes[aTimes.length - 2]) : Math.round(20000000 / a.scale);
  const shift = last + frame - bStart;

  const out: Uint8Array[] = [a.bytes.subarray(a.ebml.start, a.ebml.end)];
  const info = children(a.bytes, a.info.data, a.info.end).filter((element) => element.id !== ID.duration);
  const body: Uint8Array[] = [
    ...wrap(ID.info, info.map((element) => a.bytes.subarray(element.start, element.end))),
    a.bytes.subarray(a.tracks.start, a.tracks.end),
    ...a.clusters.map((cluster) => a.bytes.subarray(cluster.start, cluster.end)),
  ];
  for (const { cluster, timecode } of bClusters) {
    const base = timecode ? uint(b.bytes, timecode) : 0;
    const rest = children(b.bytes, cluster.data, cluster.end).filter((element) => element.id !== ID.timecode);
    body.push(...wrap(ID.cluster, [encodeUint(ID.timecode, base + shift), ...rest.map((element) => b.bytes.subarray(element.start, element.end))]));
  }
  // The segment keeps an unknown size, as MediaRecorder writes it.
  out.push(new Uint8Array([...encodeId(ID.segment), 0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]), ...body);
  return new Blob(out as BlobPart[], { type: first.type || second.type || "audio/webm" });
}
