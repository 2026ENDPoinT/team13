// A tiny WebM (EBML) writer and reader used as an independent oracle for appendWebm. The reader is deliberately
// separate from src/shared/webm-append.ts so a parsing bug there cannot hide itself.

export type Bytes = ArrayLike<number>;

export const ID = {
  ebml: 0x1a45dfa3, segment: 0x18538067, seekHead: 0x114d9b74, info: 0x1549a966, timecodeScale: 0x2ad7b1,
  duration: 0x4489, tracks: 0x1654ae6b, trackEntry: 0xae, trackNumber: 0xd7, codecId: 0x86, audio: 0xe1,
  samplingFrequency: 0xb5, channels: 0x9f, cluster: 0x1f43b675, timecode: 0xe7, simpleBlock: 0xa3,
  blockGroup: 0xa0, block: 0xa1, cues: 0x1c53bb6b, tags: 0x1254c367,
} as const;

const SEGMENT_LEVEL = new Set<number>([ID.seekHead, ID.info, ID.tracks, ID.cluster, ID.cues, ID.tags, 0x1043a770, 0x1941a469]);

const concat = (parts: Bytes[]): Uint8Array => Uint8Array.from(parts.flatMap((part) => Array.from(part)));

function idBytes(id: number): number[] {
  const out: number[] = [];
  for (let value = id; value > 0; value = Math.floor(value / 256)) out.unshift(value % 256);
  return out;
}

/** 4-byte EBML size (0x10 marker): enough for every fixture here. */
const sizeBytes = (n: number): number[] => [0x10 | ((n >>> 24) & 0x0f), (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff];

export const element = (id: number, ...children: Bytes[]): Uint8Array => {
  const data = concat(children);
  return concat([idBytes(id), sizeBytes(data.length), data]);
};

/** An element whose size is "unknown" (all ones), as MediaRecorder writes segments and clusters. */
export const unknownSizeElement = (id: number, ...children: Bytes[]): Uint8Array =>
  concat([idBytes(id), [0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff], ...children]);

export const uintElement = (id: number, value: number): Uint8Array => {
  const digits: number[] = [];
  for (let rest = value; rest > 0 || !digits.length; rest = Math.floor(rest / 256)) digits.unshift(rest % 256);
  return element(id, digits);
};

export const floatElement = (id: number, value: number, bits: 32 | 64): Uint8Array => {
  const buffer = new ArrayBuffer(bits / 8);
  const view = new DataView(buffer);
  if (bits === 32) view.setFloat32(0, value);
  else view.setFloat64(0, value);
  return element(id, new Uint8Array(buffer));
};

export interface BlockSpec {
  /** Time relative to the cluster timecode (signed 16-bit). */
  rel: number;
  payload: number[];
  /** Write these bytes as the whole SimpleBlock body instead (malformed-block tests). */
  raw?: number[];
  /** Write a BlockGroup that holds only a BlockDuration, no Block. */
  noBlock?: boolean;
  /** Write as BlockGroup > Block instead of SimpleBlock. */
  group?: boolean;
}

export interface ClusterSpec {
  timecode: number;
  blocks: BlockSpec[];
  /** Write with an unknown size, as MediaRecorder does. */
  unknown?: boolean;
}

export interface WebmSpec {
  clusters: ClusterSpec[];
  scale?: number;
  rate?: number;
  rateBits?: 32 | 64;
  channels?: number;
  codec?: string;
  trackNumber?: number;
  /** Number of TrackEntry elements (default 1). */
  trackCount?: number;
  /** Write a Duration in Info (default true). */
  duration?: boolean;
  /** Add SeekHead before and Cues + Tags after the clusters. */
  extras?: boolean;
}

function block(spec: BlockSpec, trackNumber: number): Uint8Array {
  if (spec.raw) return element(ID.simpleBlock, spec.raw);
  if (spec.noBlock) return element(ID.blockGroup, element(0x9b, [0x14]));
  const data = concat([[0x80 | trackNumber, (spec.rel >> 8) & 0xff, spec.rel & 0xff, 0x80], spec.payload]);
  return spec.group ? element(ID.blockGroup, element(ID.block, data)) : element(ID.simpleBlock, data);
}

export function buildWebm(spec: WebmSpec): Uint8Array {
  const trackNumber = spec.trackNumber ?? 1;
  const entry = element(
    ID.trackEntry,
    uintElement(ID.trackNumber, trackNumber),
    element(ID.codecId, new TextEncoder().encode(spec.codec ?? "A_OPUS")),
    element(ID.audio, floatElement(ID.samplingFrequency, spec.rate ?? 48000, spec.rateBits ?? 32), uintElement(ID.channels, spec.channels ?? 1)),
  );
  const info = element(
    ID.info,
    uintElement(ID.timecodeScale, spec.scale ?? 1000000),
    ...(spec.duration === false ? [] : [floatElement(ID.duration, 4000, 32)]),
  );
  const clusters = spec.clusters.map((cluster) => {
    const parts = [uintElement(ID.timecode, cluster.timecode), ...cluster.blocks.map((b) => block(b, trackNumber))];
    return cluster.unknown ? unknownSizeElement(ID.cluster, ...parts) : element(ID.cluster, ...parts);
  });
  const body = [
    ...(spec.extras ? [element(ID.seekHead, [0xec, 0x80])] : []),
    info,
    element(ID.tracks, ...Array.from({ length: spec.trackCount ?? 1 }, () => entry)),
    ...clusters,
    ...(spec.extras ? [element(ID.cues, [0xec, 0x80]), element(ID.tags, [0xec, 0x80])] : []),
  ];
  return concat([element(ID.ebml, element(0x4282, new TextEncoder().encode("webm"))), unknownSizeElement(ID.segment, ...body)]);
}

// ---------- reader ----------

interface Header { id: number; start: number; data: number; end: number; unknown: boolean }

function header(b: Uint8Array, at: number): Header {
  let length = 1;
  while (length <= 4 && !(b[at] & (0x80 >> (length - 1)))) length++;
  let id = 0;
  for (let i = 0; i < length; i++) id = id * 256 + b[at + i];
  const sizeAt = at + length;
  let sizeLength = 1;
  while (!(b[sizeAt] & (0x80 >> (sizeLength - 1)))) sizeLength++;
  let size = b[sizeAt] & (0xff >> sizeLength);
  let ones = size === 0xff >> sizeLength;
  for (let i = 1; i < sizeLength; i++) {
    size = size * 256 + b[sizeAt + i];
    if (b[sizeAt + i] !== 0xff) ones = false;
  }
  const data = sizeAt + sizeLength;
  return { id, start: at, data, end: ones ? -1 : data + size, unknown: ones };
}

function list(b: Uint8Array, from: number, to: number): Header[] {
  const out: Header[] = [];
  for (let at = from; at < to;) {
    const h = header(b, at);
    if (h.unknown && h.id === ID.segment) h.end = to;
    else if (h.unknown) {
      let next = h.data;
      while (next < to) {
        const child = header(b, next);
        if (SEGMENT_LEVEL.has(child.id)) break;
        next = child.unknown ? to : child.end;
      }
      h.end = next;
    }
    out.push(h);
    at = h.end;
  }
  return out;
}

const readUint = (b: Uint8Array, h: Header): number => {
  let v = 0;
  for (let i = h.data; i < h.end; i++) v = v * 256 + b[i];
  return v;
};

export interface ReadBlock { time: number; payload: number[] }
export interface ReadWebm {
  topIds: number[];
  /** Ids of the Segment's children in file order. */
  segmentIds: number[];
  infoIds: number[];
  timecodeScale: number;
  /** Raw bytes of the Tracks element. */
  tracks: number[];
  /** One entry per cluster: its absolute block times and payloads. */
  clusters: ReadBlock[][];
  /** Number of Timecode elements in each cluster (must be exactly 1). */
  timecodeCounts: number[];
  blocks: ReadBlock[];
}

export function readWebm(b: Uint8Array): ReadWebm {
  const top = list(b, 0, b.length);
  const segment = top.find((h) => h.id === ID.segment)!;
  const parts = list(b, segment.data, segment.end === -1 ? b.length : segment.end);
  const info = parts.find((h) => h.id === ID.info)!;
  const infoChildren = list(b, info.data, info.end);
  const scale = infoChildren.find((h) => h.id === ID.timecodeScale);
  const tracks = parts.find((h) => h.id === ID.tracks)!;
  const clusters = parts.filter((h) => h.id === ID.cluster).map((cluster) => {
    const children = list(b, cluster.data, cluster.end);
    const base = readUint(b, children.find((h) => h.id === ID.timecode)!);
    const read = (h: Header): ReadBlock => {
      // track number is a 1-byte varint in every fixture
      const rel = ((b[h.data + 1] << 24) >> 16) | b[h.data + 2];
      return { time: base + rel, payload: Array.from(b.subarray(h.data + 4, h.end)) };
    };
    return children.flatMap((h) => {
      if (h.id === ID.simpleBlock) return [read(h)];
      if (h.id === ID.blockGroup) return list(b, h.data, h.end).filter((c) => c.id === ID.block).map(read);
      return [];
    });
  });
  return {
    topIds: top.map((h) => h.id),
    timecodeCounts: parts.filter((h) => h.id === ID.cluster).map((cluster) => list(b, cluster.data, cluster.end).filter((h) => h.id === ID.timecode).length),
    segmentIds: parts.map((h) => h.id),
    infoIds: infoChildren.map((h) => h.id),
    timecodeScale: scale ? readUint(b, scale) : 1000000,
    tracks: Array.from(b.subarray(tracks.start, tracks.end)),
    clusters,
    blocks: clusters.flat(),
  };
}

export const blobOf = (bytes: Uint8Array, type = "audio/webm"): Blob => new Blob([bytes as BlobPart], { type });
export const bytesOf = async (blob: Blob): Promise<Uint8Array> => new Uint8Array(await blob.arrayBuffer());
