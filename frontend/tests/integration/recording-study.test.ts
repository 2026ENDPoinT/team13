import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ScreenCtx } from "../../src/types.ts";
import type { ServerRecording } from "../../src/shared/study-room-remote.ts";
import type * as BackendModule from "../../src/shared/backend.ts";
import type * as RecordingsModule from "../../src/shared/course-recordings.ts";
import type * as UploadsModule from "../../src/shared/uploads-remote.tsx";

vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof BackendModule>(),
  backendStatus: () => "ready",
  backendSettled: () => true,
  backendState: () => ({ status: "ready", settled: true, me: { userId: 1 }, courses: [], methods: [] }),
}));
vi.mock("../../src/shared/course-recordings.ts", async (original) => ({
  ...await original<typeof RecordingsModule>(),
  getRecording: vi.fn(),
}));
vi.mock("../../src/areas/home/study-server.tsx", () => ({ serverStudyLecture: () => null }));
vi.mock("../../src/areas/home/study-files.tsx", () => ({ folderHref: () => "/study", recordingHref: () => "/recording" }));
vi.mock("../../src/shared/uploads-remote.tsx", async (original) => ({
  ...await original<typeof UploadsModule>(),
  saveBlob: vi.fn(),
}));

import { getRecording, fromServer } from "../../src/shared/course-recordings.ts";
import { saveBlob } from "../../src/shared/uploads-remote.tsx";
import { renderRecording } from "../../src/areas/home/recording.tsx";
import { getState } from "../../src/store.ts";
import { find } from "../../src/manifest.ts";

// React's test-only global is intentionally outside the standard DOM declarations.
const reactTestEnvironment = globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean };
reactTestEnvironment.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
const read = vi.mocked(getRecording);
const saved = vi.mocked(saveBlob);
const base: ServerRecording = {
  id: 71, courseId: 9, title: "서버 강의", source: "mic", mimeType: "audio/webm", sizeBytes: 1024,
  durationMs: 60000, createdAt: "2026-11-14T01:00:00Z", fileName: "lecture.webm", state: "ready", version: 1,
};
const transcript = { segments: [{ at: 12, text: "서버에서 변환한 실제 전사" }], text: "서버 전사 원문\n줄바꿈 유지" };
const complete = (patch: Partial<ServerRecording> = {}) => fromServer({ ...base, ...patch }, "");
const mount = async () => {
  const ctx: ScreenCtx = { state: getState(), screen: find("study_lecture"), params: new URLSearchParams("rec=71") };
  await act(async () => root.render(renderRecording(ctx)));
};
const tick = async () => { await act(async () => { await vi.advanceTimersByTimeAsync(4000); }); };
const menu = () => act(() => host.querySelector<HTMLButtonElement>('[aria-label="다운로드"]')!.click());

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  read.mockReset();
  saved.mockClear();
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});

describe("백엔드가 생성한 녹음 전사·요약", () => {
  it("대기→처리→완료를 갱신하면서 재생 위치를 유지하고, 완료 후 조회를 멈춘다", async () => {
    const done = complete({ transcription: { status: "ready", result: transcript }, summary: { status: "failed", message: "요약 처리 실패" } });
    read.mockResolvedValue(done)
      .mockResolvedValueOnce(complete({ transcription: { status: "queued" } }))
      .mockResolvedValueOnce(complete({ transcription: { status: "processing" } }));
    await mount();
    expect(host.textContent).toContain("전사 대기 중");
    const audio = host.querySelector("audio")!;
    await act(async () => { audio.currentTime = 17; audio.dispatchEvent(new Event("timeupdate")); });
    await tick();
    expect(host.textContent).toContain("전사 생성 중");
    await tick();
    expect(host.querySelector("audio")).toBe(audio);
    expect(audio.currentTime).toBe(17);
    expect(host.querySelector('[aria-label="전사"]')?.textContent).toContain(transcript.segments[0].text);
    expect(host.textContent).toContain("요약 처리 실패");
    act(() => host.querySelector<HTMLButtonElement>(".rs-line")!.click());
    expect(audio.currentTime).toBe(12);
    const calls = read.mock.calls.length;
    await tick();
    expect(read).toHaveBeenCalledTimes(calls);
  });

  it("서버 전사 원문만 TXT로 저장하며 요약 처리는 전사 다운로드를 막지 않는다", async () => {
    read.mockResolvedValue(complete({ transcription: { status: "ready", result: transcript }, summary: { status: "processing" } }));
    await mount();
    expect(host.textContent).toContain("요약 생성 중");
    menu();
    expect(host.querySelector('a[download="lecture.webm"]')?.textContent).toContain("원본 음성");
    const download = [...host.querySelectorAll<HTMLButtonElement>('button[role="menuitem"]')].find((node) => node.textContent === "전사문 TXT")!;
    act(() => download.click());
    const [blob, name] = saved.mock.calls[0];
    expect(name).toBe("서버 강의.txt");
    expect(blob.type).toBe("text/plain;charset=utf-8");
    vi.useRealTimers();
    const reader = new FileReader();
    reader.readAsText(blob);
    await vi.waitFor(() => expect(reader.result).toBe(transcript.text));
  });

  it("필드가 없는 서버에서는 샘플을 섞거나 처리 중으로 표시하지 않고 TXT를 비활성화한다", async () => {
    read.mockResolvedValue(complete());
    await mount();
    expect(host.textContent).toContain("아직 전사 결과가 없습니다");
    expect(host.textContent).not.toContain("파이프라인");
    menu();
    expect([...host.querySelectorAll('[aria-disabled="true"]')].map((node) => node.textContent)).toEqual(["전사문 TXT", "요약 PDF"]);
    const calls = read.mock.calls.length;
    await tick();
    expect(read).toHaveBeenCalledTimes(calls);
    expect(saved).not.toHaveBeenCalled();
  });

  it("검사 중·거부된 파일에서는 서버 응답에 결과가 있어도 노출하거나 내려받지 않는다", async () => {
    const results = { transcription: { status: "ready" as const, result: transcript } };
    read.mockResolvedValueOnce(complete({ ...results, state: "scanning" }))
      .mockResolvedValue(complete({ ...results, state: "rejected" }));
    await mount();
    expect(host.querySelector("audio")).toBeNull();
    expect(host.textContent).not.toContain(transcript.segments[0].text);
    await tick();
    expect(host.textContent).toContain("악성 코드");
    expect(host.textContent).not.toContain(transcript.segments[0].text);
    menu();
    expect(host.querySelectorAll('[aria-disabled="true"]')).toHaveLength(3);
    const calls = read.mock.calls.length;
    await tick();
    expect(read).toHaveBeenCalledTimes(calls);
  });

  it("갱신 실패 때 플레이어를 유지하고 재조회 성공 뒤 서버 결과를 표시한다", async () => {
    read.mockResolvedValueOnce(complete({ transcription: { status: "processing" } }))
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValue(complete({ transcription: { status: "ready", result: transcript } }));
    await mount();
    const audio = host.querySelector("audio");
    await tick();
    expect(host.querySelector("audio")).toBe(audio);
    const retry = [...host.querySelectorAll("button")].find((node) => node.textContent === "상태 다시 확인")!;
    await tick();
    expect(read).toHaveBeenCalledTimes(2);
    await act(async () => retry.click());
    expect(host.querySelector('[aria-label="전사"]')?.textContent).toContain(transcript.segments[0].text);
  });
});
