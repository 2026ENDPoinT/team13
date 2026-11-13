import { afterEach, describe, expect, it, vi } from "vitest";

const backend = vi.hoisted(() => ({ status: "off" as "off" | "anon" | "ready" }));
vi.mock("../../src/shared/backend.ts", () => ({ backendStatus: () => backend.status }));

import { assistantFailure, isAssistantResponse, postAssistant } from "../../src/shared/assistant/request.ts";

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });

afterEach(() => {
  backend.status = "off";
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("assistantFailure", () => {
  it("백엔드 상태 코드를 한국어 안내로 바꾼다", () => {
    expect(assistantFailure(401)).toBe("AI 도우미를 쓰려면 먼저 로그인하세요");
    expect(assistantFailure(429, 12)).toBe("AI 요청이 많습니다. 12초 뒤 다시 시도하세요");
    expect(assistantFailure(429, null)).toBe("AI 요청이 많습니다. 잠시 후 다시 시도하세요");
    expect(assistantFailure(502)).toBe("AI가 답하지 못했습니다. 잠시 후 다시 시도하세요");
    expect(assistantFailure(504)).toBe("AI가 답하지 못했습니다. 잠시 후 다시 시도하세요");
    expect(assistantFailure(503)).toBe("AI 연결이 설정되지 않았습니다");
    expect(assistantFailure(400)).toBe("AI 요청을 처리하지 못했습니다");
  });
});

describe("isAssistantResponse", () => {
  it("알려진 kind·intent 와 문자열 targets 만 받는다", () => {
    expect(isAssistantResponse({ kind: "go", intent: "report", courseId: "1", targets: ["school-9"], say: "" })).toBe(true);
    expect(isAssistantResponse({ kind: "none", intent: "x", courseId: null, targets: [], say: "없음" })).toBe(true);
    expect(isAssistantResponse({ kind: "go", intent: "dance", courseId: null, targets: [], say: "" })).toBe(false);
    expect(isAssistantResponse({ kind: "go", intent: "report", courseId: null, targets: [3], say: "" })).toBe(false);
    expect(isAssistantResponse({ kind: "go", intent: "report", courseId: 3, targets: [], say: "" })).toBe(false);
    expect(isAssistantResponse(null)).toBe(false);
  });
});

describe("postAssistant", () => {
  it("백엔드가 없으면 로컬 중계에 그대로 보내고 Retry-After 를 읽는다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(429, { error: "rate_limited" }, { "Retry-After": "30" }));
    vi.stubGlobal("fetch", fetchMock);
    const reply = await postAssistant({ text: "x" }, new AbortController().signal);
    expect(reply).toEqual({ ok: false, status: 429, retryAfter: 30 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/assistant");
    expect(init.headers).toEqual({ "Content-Type": "application/json" });
  });

  it("백엔드가 있으면 CSRF 토큰을 붙여 보내고 답을 돌려준다", async () => {
    backend.status = "ready";
    document.cookie = "XSRF-TOKEN=tok";
    const answer = { kind: "go", intent: "report", courseId: "1", targets: ["5"], say: "과제" };
    const fetchMock = vi.fn().mockResolvedValue(json(200, answer));
    vi.stubGlobal("fetch", fetchMock);
    const reply = await postAssistant({ text: "x" }, new AbortController().signal);
    expect(reply).toEqual({ ok: true, data: answer });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/assistant");
    expect(init.headers["X-XSRF-TOKEN"]).toBe("tok");
    expect(init.credentials).toBe("same-origin");
  });

  it("백엔드의 401·504 문제 응답을 상태로 돌려준다", async () => {
    backend.status = "anon";
    document.cookie = "XSRF-TOKEN=tok";
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: 401, code: "unauthorized" }), { status: 401, headers: { "Content-Type": "application/problem+json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: 504, code: "assistant_timeout" }), { status: 504, headers: { "Content-Type": "application/problem+json" } })));
    expect(await postAssistant({}, new AbortController().signal)).toEqual({ ok: false, status: 401, retryAfter: null });
    expect(await postAssistant({}, new AbortController().signal)).toEqual({ ok: false, status: 504, retryAfter: null });
  });

  it("연결 실패는 그대로 던진다(호출한 쪽이 시간 초과와 구분한다)", async () => {
    backend.status = "ready";
    document.cookie = "XSRF-TOKEN=tok";
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await expect(postAssistant({}, new AbortController().signal)).rejects.toMatchObject({ status: 0 });
  });
});
