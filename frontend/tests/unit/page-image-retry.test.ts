import { describe, expect, it, vi } from "vitest";
import {
  AUTO_RETRIES, REFRESH_TIMEOUT_MS, imageRefresher, initialPageImage, pageImageStep, type PageImageEvent, type PageImageState,
} from "../../src/shared/page-image-retry.ts";

const BASE = "/doc/abc/";
const run = (events: PageImageEvent[], start: PageImageState = initialPageImage(BASE)): PageImageState =>
  events.reduce(pageImageStep, start);

describe("pageImageStep — 쪽 이미지 실패 복구", () => {
  it("처음 실패하면 출처를 다시 받는 상태가 되고, 같은 주소가 와도 attempt가 바뀌어 <img>를 다시 만든다", () => {
    const refreshing = run([{ type: "error" }]);
    expect(refreshing).toEqual({ base: BASE, attempt: 0, phase: "refreshing", autoUsed: 1 });
    const shown = pageImageStep(refreshing, { type: "refreshed", base: BASE });
    expect(shown).toEqual({ base: BASE, attempt: 1, phase: "show", autoUsed: 1 });
  });

  it("다시 받은 주소가 바뀌면 그 주소를 쓴다", () => {
    expect(run([{ type: "error" }, { type: "refreshed", base: "/doc/new/" }]).base).toBe("/doc/new/");
  });

  it(`자동 재시도는 ${AUTO_RETRIES}번뿐: 두 번째 실패는 안내(failed)로 끝나고 더 받지 않는다`, () => {
    const failed = run([{ type: "error" }, { type: "refreshed", base: BASE }, { type: "error" }]);
    expect(failed.phase).toBe("failed");
    // 실패 상태에서 다른 error가 와도 그대로다(반복 없음).
    expect(pageImageStep(failed, { type: "error" })).toBe(failed);
  });

  it("다시 받기가 실패하면(null) 바로 안내로 끝난다", () => {
    expect(run([{ type: "error" }, { type: "refreshed", base: null }]).phase).toBe("failed");
  });

  it("다시 불러오기 버튼은 한 번 더 받고 다시 그린다. 또 실패하면 자동 재시도 없이 안내로 돌아간다", () => {
    const failed = run([{ type: "error" }, { type: "refreshed", base: null }]);
    const retrying = pageImageStep(failed, { type: "retry" });
    expect(retrying.phase).toBe("refreshing");
    const shown = pageImageStep(retrying, { type: "refreshed", base: BASE });
    expect(shown).toMatchObject({ phase: "show", attempt: 1 });
    expect(pageImageStep(shown, { type: "error" }).phase).toBe("failed");
  });

  it("상태에 맞지 않는 사건은 무시한다", () => {
    const start = initialPageImage(BASE);
    expect(pageImageStep(start, { type: "retry" })).toBe(start);
    expect(pageImageStep(start, { type: "refreshed", base: "/doc/x/" })).toBe(start);
    const refreshing = run([{ type: "error" }]);
    expect(pageImageStep(refreshing, { type: "error" })).toBe(refreshing);
  });

  it("다시 받은 뒤에도 실패한 주소(broken)의 다른 쪽은 다시 받지 않고 바로 안내로 간다", () => {
    expect(initialPageImage(BASE, true)).toEqual({ base: BASE, attempt: 0, phase: "failed", autoUsed: AUTO_RETRIES });
    expect(run([{ type: "error", broken: true }])).toMatchObject({ phase: "failed", autoUsed: AUTO_RETRIES });
  });

  it("broken으로 시작한 쪽도 다시 불러오기는 한 번 받아 다시 그린다", () => {
    const shown = run([{ type: "retry" }, { type: "refreshed", base: BASE }], initialPageImage(BASE, true));
    expect(shown).toMatchObject({ phase: "show", attempt: 1 });
    expect(pageImageStep(shown, { type: "error" }).phase).toBe("failed");
  });
});

describe("imageRefresher — 동시에 실패한 쪽은 한 번만 받고, 끝난 뒤의 실패는 새로 받는다", () => {
  it("진행 중인 요청을 같이 쓴다", async () => {
    // Promise.withResolvers is outside this project's lib (es2023).
    let resolve: (value: string) => void = () => {};
    const load = vi.fn(() => new Promise<string>((done) => { resolve = done; }));
    const { refresh } = imageRefresher(load);
    const first = refresh();
    const second = refresh();
    await Promise.resolve();
    resolve(BASE);
    expect(await first).toBe(BASE);
    expect(await second).toBe(BASE);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("앞 요청이 끝난 뒤의 실패는 다시 받는다(쿠키가 또 만료될 수 있다)", async () => {
    const load = vi.fn(async () => BASE);
    const { refresh } = imageRefresher(load);
    await refresh();
    await refresh();
    expect(load).toHaveBeenCalledTimes(2);
  });

  it("던지거나 거절되면 null", async () => {
    expect(await imageRefresher(() => { throw new Error("x"); }).refresh()).toBeNull();
    expect(await imageRefresher(() => Promise.reject(new Error("x"))).refresh()).toBeNull();
  });

  it("동기 값도 받는다", async () => {
    expect(await imageRefresher(() => BASE).refresh()).toBe(BASE);
  });

  it(`답이 없으면 ${REFRESH_TIMEOUT_MS}ms 뒤 null로 끝나고, 다음 실패는 멈춘 요청을 기다리지 않고 새로 받는다`, async () => {
    vi.useFakeTimers();
    try {
      const load = vi.fn(() => new Promise<string>(() => {}));
      const { refresh } = imageRefresher(load);
      const stalled = refresh();
      await vi.advanceTimersByTimeAsync(REFRESH_TIMEOUT_MS - 1);
      expect(refresh()).toBe(stalled);
      await vi.advanceTimersByTimeAsync(1);
      expect(await stalled).toBeNull();
      const next = refresh();
      expect(next).not.toBe(stalled);
      await vi.advanceTimersByTimeAsync(0);
      expect(load).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(REFRESH_TIMEOUT_MS);
      expect(await next).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("시간 제한은 따로 줄 수 있고, 제때 온 답에는 영향이 없다", async () => {
    vi.useFakeTimers();
    try {
      const { refresh } = imageRefresher(() => new Promise<string>((done) => setTimeout(() => done(BASE), 50)), 100);
      const result = refresh();
      await vi.advanceTimersByTimeAsync(50);
      expect(await result).toBe(BASE);
    } finally {
      vi.useRealTimers();
    }
  });

  it("다시 받은 뒤에도 실패한 주소를 기억하고, 그 주소의 쪽이 불러와지면 잊는다", () => {
    const refresher = imageRefresher(() => BASE);
    expect(refresher.isBroken(BASE)).toBe(false);
    refresher.markBroken(BASE);
    expect(refresher.isBroken(BASE)).toBe(true);
    expect(refresher.isBroken("/doc/other/")).toBe(false);
    refresher.markLoaded(BASE);
    expect(refresher.isBroken(BASE)).toBe(false);
  });
});
