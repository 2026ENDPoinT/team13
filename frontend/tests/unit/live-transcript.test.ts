import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  liveTranscript, liveTranscriptSupported, onLiveTranscriptChange, pauseLiveTranscript, resumeLiveTranscript, startLiveTranscript, stopLiveTranscript,
} from "../../src/shared/live-transcript.ts";

type Handler<T> = ((event: T) => void) | null;

class FakeRecognition {
  static all: FakeRecognition[] = [];
  static failStart = false;
  lang = "";
  continuous = false;
  interimResults = false;
  onresult: Handler<{ resultIndex: number; results: { isFinal: boolean; 0: { transcript: string } }[] }> = null;
  onerror: Handler<{ error: string }> = null;
  onend: (() => void) | null = null;
  aborted = false;
  constructor() {
    FakeRecognition.all.push(this);
  }
  start() {
    if (FakeRecognition.failStart) throw new Error("already started");
  }
  stop() {}
  abort() {
    this.aborted = true;
  }
  say(results: [string, boolean][], resultIndex = 0) {
    this.onresult?.({ resultIndex, results: results.map(([transcript, isFinal]) => ({ isFinal, 0: { transcript } })) });
  }
}

const last = () => FakeRecognition.all[FakeRecognition.all.length - 1];

beforeEach(() => {
  vi.useFakeTimers();
  FakeRecognition.all = [];
  FakeRecognition.failStart = false;
  vi.stubGlobal("webkitSpeechRecognition", FakeRecognition);
});

afterEach(() => {
  stopLiveTranscript();
  vi.useRealTimers();
});

describe("live transcript", () => {
  it("listens in Korean and keeps final sentences with the recording time, the unfinished one as interim", () => {
    let ms = 0;
    startLiveTranscript(() => ms);
    const rec = last();
    expect(rec.lang).toBe("ko-KR");
    expect(rec.continuous && rec.interimResults).toBe(true);
    expect(liveTranscript().listening).toBe(true);

    ms = 3000;
    rec.say([["안녕하세요 ", true], ["오늘은", false]]);
    expect(liveTranscript().lines).toEqual([{ at: 3000, text: "안녕하세요" }]);
    expect(liveTranscript().interim).toBe("오늘은");

    ms = 9000;
    rec.say([["안녕하세요", true], ["오늘은 파이프라인", true]], 1);
    expect(liveTranscript().lines.map((line) => line.text)).toEqual(["안녕하세요", "오늘은 파이프라인"]);
    expect(liveTranscript().interim).toBe("");
  });

  it("starts a new recognizer after it ends on its own (silence), keeping the lines", () => {
    startLiveTranscript(() => 0);
    const first = last();
    first.say([["하나", true]]);
    first.onend?.();
    expect(liveTranscript().interim).toBe("");
    vi.advanceTimersByTime(400);
    expect(FakeRecognition.all).toHaveLength(2);
    expect(liveTranscript().lines).toHaveLength(1);
    // The old recognizer's late events no longer count.
    first.say([["늦게 온 말", true]]);
    expect(liveTranscript().lines).toHaveLength(1);
  });

  it("pause stops listening without restarting, resume listens again", () => {
    startLiveTranscript(() => 0);
    const first = last();
    pauseLiveTranscript();
    expect(first.aborted).toBe(true);
    first.onend?.();
    vi.advanceTimersByTime(1000);
    expect(FakeRecognition.all).toHaveLength(1);
    expect(liveTranscript().listening).toBe(false);

    resumeLiveTranscript();
    expect(FakeRecognition.all).toHaveLength(2);
    expect(liveTranscript().listening).toBe(true);
  });

  it("a denied microphone or network error stops it with a message and resume does not retry", () => {
    startLiveTranscript(() => 0);
    const rec = last();
    rec.onerror?.({ error: "no-speech" });
    expect(liveTranscript().error).toBe("");
    rec.onerror?.({ error: "not-allowed" });
    expect(liveTranscript().listening).toBe(false);
    expect(liveTranscript().error).toContain("녹음은 계속됩니다");
    rec.onend?.();
    vi.advanceTimersByTime(1000);
    resumeLiveTranscript();
    expect(FakeRecognition.all).toHaveLength(1);
  });

  it("reports a start failure instead of throwing", () => {
    FakeRecognition.failStart = true;
    startLiveTranscript(() => 0);
    expect(liveTranscript()).toMatchObject({ supported: true, listening: false });
    expect(liveTranscript().error).not.toBe("");
  });

  it("is unsupported without a recognition API, and stop clears the lines and notifies", () => {
    vi.stubGlobal("webkitSpeechRecognition", undefined);
    expect(liveTranscriptSupported()).toBe(false);
    startLiveTranscript(() => 0);
    expect(liveTranscript().supported).toBe(false);

    vi.stubGlobal("webkitSpeechRecognition", FakeRecognition);
    startLiveTranscript(() => 0);
    last().say([["남는 말", true]]);
    const seen = vi.fn();
    const off = onLiveTranscriptChange(seen);
    stopLiveTranscript();
    off();
    expect(seen).toHaveBeenCalled();
    expect(liveTranscript().lines).toEqual([]);
    expect(last().aborted).toBe(true);
  });
});
