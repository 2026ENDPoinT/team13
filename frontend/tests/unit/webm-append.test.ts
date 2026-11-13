// @vitest-environment node
// Blob.arrayBuffer() is native in Node; the jsdom Blob is not needed here.
import { describe, expect, it } from "vitest";
import { appendWebm, isAppendable } from "../../src/shared/webm-append.ts";
import {
  ID, blobOf, buildWebm, bytesOf, readWebm, type BlockSpec, type ClusterSpec, type WebmSpec,
} from "../helpers/webm.ts";

const REJECT = /이어 붙일 수 없는 녹음 파일입니다/;

/** One cluster of frames at `times` (ms, scale 1e6), payload [tag, index]. */
const frames = (tag: number, times: number[], timecode = 0): ClusterSpec => ({
  timecode,
  blocks: times.map((rel, i): BlockSpec => ({ rel, payload: [tag, i] })),
});

const join = async (a: WebmSpec, b: WebmSpec, types: [string, string] = ["audio/webm", "audio/webm"]) =>
  readWebm(await bytesOf(await appendWebm(blobOf(buildWebm(a), types[0]), blobOf(buildWebm(b), types[1]))));

describe("appendWebm — 시간 이어 붙이기", () => {
  it("두 번째 녹음의 첫 블록은 첫 녹음의 마지막 블록 + 한 프레임 뒤에 온다", async () => {
    const out = await join({ clusters: [frames(0xaa, [0, 20, 40])] }, { clusters: [frames(0xbb, [0, 20])] });
    expect(out.blocks.map((b) => b.time)).toEqual([0, 20, 40, 60, 80]);
    expect(out.blocks.map((b) => b.payload)).toEqual([[0xaa, 0], [0xaa, 1], [0xaa, 2], [0xbb, 0], [0xbb, 1]]);
  });

  it("두 번째 녹음의 클러스터 시작 시각이 0이 아니어도 간격 없이 이어진다", async () => {
    const out = await join({ clusters: [frames(0xaa, [0, 20, 40])] }, { clusters: [frames(0xbb, [0, 20], 5000)] });
    expect(out.blocks.map((b) => b.time)).toEqual([0, 20, 40, 60, 80]);
  });

  it("프레임 길이는 첫 녹음 마지막 두 블록의 간격에서 추정한다(20ms 고정이 아님)", async () => {
    const out = await join({ clusters: [frames(0xaa, [0, 40, 80])] }, { clusters: [frames(0xbb, [0, 40])] });
    expect(out.blocks.map((b) => b.time)).toEqual([0, 40, 80, 120, 160]);
  });

  it("블록이 하나뿐이면 기본 20ms(TimecodeScale 기준)를 프레임 길이로 쓴다", async () => {
    const out = await join({ clusters: [frames(0xaa, [100])] }, { clusters: [frames(0xbb, [0])] });
    expect(out.blocks.map((b) => b.time)).toEqual([100, 120]);
  });

  it("여러 클러스터는 각 클러스터 사이의 상대 간격을 유지한다", async () => {
    const out = await join(
      { clusters: [frames(0xaa, [0, 20], 0), frames(0xaa, [0, 20], 1000)] },
      { clusters: [frames(0xbb, [0, 20], 0), frames(0xbb, [0, 20], 500)] },
    );
    expect(out.blocks.map((b) => b.time)).toEqual([0, 20, 1000, 1020, 1040, 1060, 1540, 1560]);
    expect(out.clusters).toHaveLength(4);
  });

  it("클러스터 기준 시각보다 앞선 블록(음수 상대 시각)도 올바른 절대 시각으로 계산한다", async () => {
    const b: WebmSpec = { clusters: [{ timecode: 100, blocks: [{ rel: -20, payload: [0xbb, 0] }, { rel: 0, payload: [0xbb, 1] }] }] };
    const out = await join({ clusters: [frames(0xaa, [0, 20, 40])] }, b);
    expect(out.blocks.map((x) => x.time)).toEqual([0, 20, 40, 60, 80]);
  });

  it("2^16 을 넘는 큰 타임코드(1시간)도 여러 바이트로 정확히 인코딩한다", async () => {
    const out = await join({ clusters: [frames(0xaa, [0, 20], 3_600_000)] }, { clusters: [frames(0xbb, [0, 20])] });
    expect(out.blocks.map((b) => b.time)).toEqual([3_600_000, 3_600_020, 3_600_040, 3_600_060]);
  });

  it("BlockGroup 안의 블록도 시간 계산에 포함한다", async () => {
    const a: WebmSpec = { clusters: [{ timecode: 0, blocks: [{ rel: 0, payload: [0xaa, 0] }, { rel: 20, payload: [0xaa, 1], group: true }] }] };
    const out = await join(a, { clusters: [frames(0xbb, [0])] });
    expect(out.blocks.map((b) => b.time)).toEqual([0, 20, 40]);
  });

  it("TimecodeScale 이 1ms 가 아니어도 기본 프레임 길이를 그 단위로 환산한다", async () => {
    const scale = 100000; // 0.1ms 단위: 20ms = 200
    const out = await join(
      { scale, clusters: [frames(0xaa, [0])] },
      { scale, clusters: [frames(0xbb, [0])] },
    );
    expect(out.blocks.map((b) => b.time)).toEqual([0, 200]);
    expect(out.timecodeScale).toBe(scale);
  });
});

describe("appendWebm — 구조", () => {
  it("MediaRecorder 처럼 크기를 모르는 클러스터와 뒤따르는 Cues 도 처리한다", async () => {
    const a: WebmSpec = { extras: true, clusters: [{ ...frames(0xaa, [0, 20]), unknown: true }, { ...frames(0xaa, [0, 20], 100), unknown: true }] };
    const b: WebmSpec = { clusters: [{ ...frames(0xbb, [0, 20]), unknown: true }] };
    const out = await join(a, b);
    expect(out.blocks.map((x) => x.time)).toEqual([0, 20, 100, 120, 140, 160]);
    expect(out.blocks.map((x) => x.payload[0])).toEqual([0xaa, 0xaa, 0xaa, 0xaa, 0xbb, 0xbb]);
  });

  it("Duration·SeekHead·Cues·Tags 는 버리고 Info·Tracks·클러스터만 남긴다", async () => {
    const out = await join({ extras: true, clusters: [frames(0xaa, [0, 20])] }, { extras: true, clusters: [frames(0xbb, [0, 20])] });
    expect(out.infoIds).not.toContain(ID.duration);
    expect(out.infoIds).toContain(ID.timecodeScale);
    expect(out.segmentIds).toEqual([ID.info, ID.tracks, ID.cluster, ID.cluster]);
    expect(out.topIds).toEqual([ID.ebml, ID.segment]);
  });

  it("Tracks 는 첫 녹음의 것을 그대로 쓴다", async () => {
    const a = buildWebm({ clusters: [frames(0xaa, [0, 20])] });
    const out = await join({ clusters: [frames(0xaa, [0, 20])] }, { clusters: [frames(0xbb, [0, 20])] });
    expect(out.tracks).toEqual(readWebm(a).tracks);
  });

  it("샘플링 주파수를 float32 로 쓴 파일과 float64 로 쓴 파일도 같은 형식으로 본다", async () => {
    const out = await join({ rateBits: 32, clusters: [frames(0xaa, [0, 20])] }, { rateBits: 64, clusters: [frames(0xbb, [0, 20])] });
    expect(out.blocks).toHaveLength(4);
  });

  it("결과를 다시 이어 붙일 수 있다(이미 합친 파일에 또 붙이기)", async () => {
    const first = await appendWebm(blobOf(buildWebm({ clusters: [frames(0xaa, [0, 20])] })), blobOf(buildWebm({ clusters: [frames(0xbb, [0, 20])] })));
    const second = await appendWebm(first, blobOf(buildWebm({ clusters: [frames(0xcc, [0, 20])] })));
    expect(readWebm(await bytesOf(second)).blocks.map((b) => [b.payload[0], b.time])).toEqual([
      [0xaa, 0], [0xaa, 20], [0xbb, 40], [0xbb, 60], [0xcc, 80], [0xcc, 100],
    ]);
  });

  it.each([
    [["audio/webm;codecs=opus", "audio/webm"], "audio/webm;codecs=opus"],
    [["", "audio/webm;codecs=opus"], "audio/webm;codecs=opus"],
    [["", ""], "audio/webm"],
  ] as const)("MIME 은 첫 녹음 → 둘째 녹음 → 기본값 순: %j -> %s", async (types, expected) => {
    const blob = await appendWebm(blobOf(buildWebm({ clusters: [frames(1, [0, 20])] }), types[0]), blobOf(buildWebm({ clusters: [frames(2, [0, 20])] }), types[1]));
    expect(blob.type).toBe(expected);
  });
});

describe("appendWebm — 이어 붙일 수 없는 입력은 한국어 오류로 거부한다", () => {
  const ok = (): WebmSpec => ({ clusters: [frames(0xaa, [0, 20])] });
  const attempt = (a: Uint8Array | WebmSpec, b: Uint8Array | WebmSpec) =>
    appendWebm(blobOf(a instanceof Uint8Array ? a : buildWebm(a)), blobOf(b instanceof Uint8Array ? b : buildWebm(b)));

  it.each([
    ["샘플링 주파수가 다름", { ...ok(), rate: 44100 }],
    ["코덱이 다름", { ...ok(), codec: "A_VORBIS" }],
    ["채널 수가 다름", { ...ok(), channels: 2 }],
    ["트랙 번호가 다름", { ...ok(), trackNumber: 2 }],
    ["TimecodeScale 이 다름", { ...ok(), scale: 500000 }],
  ] as [string, WebmSpec][])("형식 불일치: %s", async (_name, other) => {
    await expect(attempt(ok(), other)).rejects.toThrow(REJECT);
    await expect(attempt(other, ok())).rejects.toThrow(REJECT); // 순서를 바꿔도 거부
  });

  it("오디오 트랙이 둘 이상이면 거부", async () => {
    await expect(attempt(ok(), { ...ok(), trackCount: 2 })).rejects.toThrow(REJECT);
  });

  it("소리 블록이 하나도 없으면 거부(첫 번째/두 번째 모두)", async () => {
    const empty: WebmSpec = { clusters: [{ timecode: 0, blocks: [] }] };
    await expect(attempt(ok(), empty)).rejects.toThrow(REJECT);
    await expect(attempt(empty, ok())).rejects.toThrow(REJECT);
  });

  it("클러스터가 없으면 거부", async () => {
    await expect(attempt(ok(), { clusters: [] })).rejects.toThrow(REJECT);
  });

  it.each([
    ["빈 파일", new Uint8Array(0)],
    ["WebM 이 아닌 바이트", Uint8Array.from([1, 2, 3, 4, 5, 6, 7, 8])],
    ["텍스트", new TextEncoder().encode("RIFF....WAVEfmt ")],
  ] as [string, Uint8Array][])("WebM 이 아님: %s", async (_name, junk) => {
    await expect(attempt(junk, buildWebm(ok()))).rejects.toThrow(REJECT);
    await expect(attempt(buildWebm(ok()), junk)).rejects.toThrow(REJECT);
  });

  it("중간에 잘린 파일(크기가 아는 마지막 클러스터가 파일보다 김)은 거부", async () => {
    const whole = buildWebm(ok());
    await expect(attempt(whole.subarray(0, whole.length - 3), buildWebm(ok()))).rejects.toThrow(REJECT);
  });
});

describe("isAppendable — MIME 동치 분할", () => {
  it.each([
    ["audio/webm", true],
    ["audio/webm;codecs=opus", true],
    ["AUDIO/WEBM", true],
    ["audio/webmx", false], // 단어 경계
    ["video/webm", false],
    ["audio/mp4", false],
    ["audio/mpeg", false],
    ["", false],
  ])("%j -> %s", (mime, expected) => {
    expect(isAppendable(mime)).toBe(expected);
  });

  it("빈 값(null/undefined 로 넘어온 값)은 false", () => {
    expect(isAppendable(undefined as unknown as string)).toBe(false);
  });
});

describe("appendWebm — 회귀: 크기를 모르는 클러스터가 잘린 파일", () => {
  // MediaRecorder 의 클러스터는 크기를 모르는 채로 기록된다. 마지막 블록이 잘린 파일을 그대로 받아들이면
  // 선언된 블록 크기가 뒤따르는 두 번째 녹음의 바이트를 삼켜 결과 파일이 깨진다.
  it("마지막 블록이 잘린 첫 녹음은 깨진 파일을 만들지 않고 거부한다", async () => {
    const whole = buildWebm({ clusters: [{ ...frames(0xaa, [0, 20, 40]), unknown: true }] });
    const second = blobOf(buildWebm({ clusters: [frames(0xbb, [0, 20])] }));
    await expect(appendWebm(blobOf(whole.subarray(0, whole.length - 1)), second)).rejects.toThrow(REJECT);
  });
});

describe("appendWebm — 블록 해석의 경계", () => {
  const ok = (): WebmSpec => ({ clusters: [frames(0xaa, [0, 20])] });
  const withRaw = (raw: number[]): WebmSpec => ({ clusters: [{ timecode: 0, blocks: [{ rel: 0, payload: [], raw }] }] });
  const attempt = (a: WebmSpec, b: WebmSpec) => appendWebm(blobOf(buildWebm(a)), blobOf(buildWebm(b)));

  it("블록 머리(트랙 번호 + 상대 시각 2바이트)가 모자란 블록은 거부한다", async () => {
    await expect(attempt(ok(), withRaw([0x81, 0x00]))).rejects.toThrow(/블록/);
  });

  it("머리가 정확히 3바이트인 블록은 받아들인다(경계)", async () => {
    await expect(attempt(ok(), withRaw([0x81, 0x00, 0x00]))).resolves.toBeInstanceOf(Blob);
  });

  it("트랙 번호 길이를 읽을 수 없는 블록(첫 바이트 0)은 거부한다", async () => {
    await expect(attempt(ok(), withRaw([0x00, 0x00, 0x00, 0x00]))).rejects.toThrow(/블록/);
  });

  it("Block 없이 다른 요소만 든 BlockGroup 은 시간 계산에서 무시한다", async () => {
    const a: WebmSpec = { clusters: [{ timecode: 0, blocks: [{ rel: 0, payload: [0xaa, 0] }, { rel: 20, payload: [0xaa, 1] }, { rel: 0, payload: [], noBlock: true }] }] };
    const out = await join(a, { clusters: [frames(0xbb, [0, 20])] });
    expect(out.blocks.map((b) => b.time)).toEqual([0, 20, 40, 60]);
  });
});

describe("appendWebm — 출력 구조의 세부", () => {
  it("두 번째 녹음의 클러스터마다 Timecode 는 정확히 하나(원래 것을 지우고 이동한 값만)", async () => {
    const out = await join({ clusters: [frames(0xaa, [0, 20])] }, { clusters: [frames(0xbb, [0, 20], 777), frames(0xbb, [0, 20], 999)] });
    expect(out.timecodeCounts).toEqual([1, 1, 1]);
  });

  it("256바이트·64KiB 를 넘는 클러스터 크기도 정확히 인코딩한다", async () => {
    const big = (tag: number) => ({ timecode: 0, blocks: [0, 20].map((rel, i) => ({ rel, payload: Array.from({ length: 40000 }, (_, k) => (k + tag + i) & 0xff) })) });
    const out = await join({ clusters: [frames(0xaa, [0, 20])] }, { clusters: [big(1)] });
    expect(out.blocks).toHaveLength(4);
    expect(out.blocks[2].payload).toHaveLength(40000);
    expect(out.blocks[3].payload.slice(0, 3)).toEqual([2, 3, 4]);
  });

  it.each([
    ["빈 입력", async () => appendWebm(blobOf(new Uint8Array(0)), blobOf(buildWebm({ clusters: [frames(1, [0, 20])] }))), /WebM 아님/],
    ["형식 불일치", async () => appendWebm(blobOf(buildWebm({ clusters: [frames(1, [0, 20])] })), blobOf(buildWebm({ rate: 8000, clusters: [frames(1, [0, 20])] }))), /소리 형식이 다름/],
    ["트랙 둘", async () => appendWebm(blobOf(buildWebm({ clusters: [frames(1, [0, 20])] })), blobOf(buildWebm({ trackCount: 2, clusters: [frames(1, [0, 20])] }))), /오디오 트랙이 하나가 아님/],
    ["블록 없음", async () => appendWebm(blobOf(buildWebm({ clusters: [frames(1, [0, 20])] })), blobOf(buildWebm({ clusters: [{ timecode: 0, blocks: [] }] }))), /소리 블록 없음/],
  ])("오류 문구가 원인을 알려 준다: %s", async (_name, run, reason) => {
    await expect(run()).rejects.toThrow(reason);
  });

  it("MIME 은 처음부터 audio/webm 인 것만(앞에 다른 글자가 있으면 아니다)", () => {
    expect(isAppendable("x audio/webm")).toBe(false);
    expect(isAppendable("video/audio/webm")).toBe(false);
  });
});
