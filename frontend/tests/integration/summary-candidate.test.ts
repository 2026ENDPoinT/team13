import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as ApiModule from "../../src/shared/api.ts";

// 교수자 요약 패널이 작업과 후보를 함께 읽는 단계(withCandidate). 서버 호출(api.ts)만 가짜로 바꾼다.
vi.mock("../../src/shared/api.ts", async (original) => ({
  ...await original<typeof ApiModule>(),
  apiGet: vi.fn(),
}));

import { ApiError, apiGet } from "../../src/shared/api.ts";
import { withCandidate } from "../../src/shared/course-pdfs-remote.ts";

const record = { backendCourseId: 3, fileId: 8 };

beforeEach(() => {
  vi.mocked(apiGet).mockReset();
});

describe("withCandidate", () => {
  it("끝나지 않은 작업과 작업 없음은 후보를 읽지 않는다", async () => {
    await expect(withCandidate(record, { id: 1, state: "running" })).resolves.toEqual({ job: { id: 1, state: "running" }, result: null, failure: null });
    await expect(withCandidate(record, null)).resolves.toEqual({ job: null, result: null, failure: null });
    expect(apiGet).not.toHaveBeenCalled();
  });

  it("끝난 작업은 후보를 함께 돌려준다", async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({ question: "q", story: [], slides: [] });
    const read = await withCandidate(record, { id: 2, state: "completed" });
    expect(read.result).toEqual({ question: "q", story: [], slides: [] });
    expect(apiGet).toHaveBeenCalledWith("/api/courses/3/summary-jobs/2/result");
  });

  it("후보를 읽지 못해도 끝난 작업은 그대로 돌려준다(패널이 실행 중에 멈춰 있지 않게)", async () => {
    const failure = new ApiError(502, { detail: "bad gateway" });
    vi.mocked(apiGet).mockRejectedValueOnce(failure);
    await expect(withCandidate(record, { id: 2, state: "completed" })).resolves.toEqual({ job: { id: 2, state: "completed" }, result: null, failure });
  });
});
