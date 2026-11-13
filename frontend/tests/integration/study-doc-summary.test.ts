import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as ApiModule from "../../src/shared/api.ts";

// 학생 본인 PDF의 AI 요약(`/api/me/study-room/documents/{id}/summary`). 서버 호출(api.ts)만 가짜로 바꾼다.
vi.mock("../../src/shared/api.ts", async (original) => ({
  ...await original<typeof ApiModule>(),
  apiGet: vi.fn(),
  apiSend: vi.fn(),
}));

import { ApiError, apiGet, apiSend } from "../../src/shared/api.ts";
import type { ServerDocument } from "../../src/shared/study-room-remote.ts";
import {
  docSummaryMessage, getDocSummary, startDocSummary, summaryOffered, summaryPollDelay, toDocRecord,
} from "../../src/shared/study-documents.ts";

const doc = (patch: Partial<ServerDocument> = {}): ServerDocument => ({
  documentId: 7, courseId: 9, title: "내 노트", fileName: "내 노트.pdf", sizeBytes: 2048, state: "ready", folderId: null,
  createdAt: "2026-11-14T01:00:00Z", updatedAt: "2026-11-14T01:00:00Z", version: 1, ...patch,
});

beforeEach(() => {
  vi.mocked(apiSend).mockResolvedValue({ status: "queued" });
  vi.mocked(apiGet).mockResolvedValue({ status: "processing" });
});

describe("요약 기능을 보일지", () => {
  it("문서 view에 summaryStatus 키가 없는 지금의 백엔드에서는 숨긴다", () => {
    const record = toDocRecord(doc(), "bi");
    expect(record.summaryStatus).toBeUndefined();
    expect(summaryOffered(record)).toBe(false);
  });

  it("summaryStatus가 null(요청 전)이거나 값이 있으면 보이고, 검사가 끝나지 않은 PDF에서는 숨긴다", () => {
    expect(summaryOffered(toDocRecord(doc({ summaryStatus: null }), "bi"))).toBe(true);
    expect(summaryOffered(toDocRecord(doc({ summaryStatus: "ready" }), "bi"))).toBe(true);
    expect(summaryOffered(toDocRecord(doc({ summaryStatus: null, state: "scanning" }), "bi"))).toBe(false);
    expect(summaryOffered(toDocRecord(doc({ summaryStatus: null, state: "rejected" }), "bi"))).toBe(false);
  });
});

describe("startDocSummary / getDocSummary", () => {
  it("POST …/documents/7/summary에 Idempotency-Key를 붙이고, 응답이 없던 요청을 다시 보내면 같은 키다", async () => {
    vi.mocked(apiSend).mockRejectedValueOnce(new ApiError(0, { detail: "연결 실패" }));
    await expect(startDocSummary(7)).rejects.toThrow();
    await expect(startDocSummary(7)).resolves.toEqual({ status: "queued" });
    const calls = vi.mocked(apiSend).mock.calls;
    expect(calls[0].slice(0, 3)).toEqual(["POST", "/api/me/study-room/documents/7/summary", undefined]);
    expect(calls[1][3]?.idempotencyKey).toBe(calls[0][3]?.idempotencyKey);
    // 답을 받은 뒤의 새 요청은 새 키다.
    await startDocSummary(7);
    expect(calls[2][3]?.idempotencyKey).not.toBe(calls[0][3]?.idempotencyKey);
  });

  it("GET은 요청 전(404 not_found)이면 null", async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(404, { code: "not_found" }));
    await expect(getDocSummary(7)).resolves.toBeNull();
    await expect(getDocSummary(7)).resolves.toEqual({ status: "processing" });
    expect(apiGet).toHaveBeenCalledWith("/api/me/study-room/documents/7/summary");
  });
});

describe("문장과 간격", () => {
  it("503 llm_disabled, 429, 하루 한도, 검사 중", () => {
    expect(docSummaryMessage(new ApiError(503, { code: "llm_disabled" }))).toBe("지금 이 서버에서는 AI 요약을 쓸 수 없습니다(AI 제공자가 설정되지 않았습니다).");
    expect(docSummaryMessage(new ApiError(429, { retryAfter: 30 }))).toBe("요약 요청이 많습니다. 30초 뒤 다시 시도하세요.");
    expect(docSummaryMessage(new ApiError(429, { code: "summary_daily_limit", retryAfter: 7200 }))).toBe("오늘 만들 수 있는 요약 수를 다 썼습니다. 2시간쯤 뒤 다시 시도하세요.");
    expect(docSummaryMessage(new ApiError(409, { code: "file_scanning", retryAfter: 10 }))).toBe("바이러스 검사가 끝난 뒤에 요약을 만들 수 있습니다. 잠시 후 다시 시도하세요.");
    expect(docSummaryMessage(new ApiError(401))).toBe("ASTRA 서버 로그인이 필요합니다. 로그인한 뒤 다시 시도하세요.");
  });

  it("대기 간격은 Retry-After(없으면 5초)를 4~10초로 묶는다", () => {
    expect(summaryPollDelay()).toBe(5);
    expect(summaryPollDelay(new ApiError(503, { retryAfter: 1 }))).toBe(4);
    expect(summaryPollDelay(new ApiError(503, { retryAfter: 60 }))).toBe(10);
  });
});
