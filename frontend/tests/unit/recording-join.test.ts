import { describe, expect, it } from "vitest";
import { ApiError } from "../../src/shared/api.ts";
import { originalCleanupWarning } from "../../src/shared/recording-join.ts";

const detail = (error: unknown) => `상세(${error instanceof ApiError ? error.code : "?"})`;
const TITLE = "녹음 11.14 03:10";

describe("originalCleanupWarning — 이어서 녹음 뒤 원래 녹음 지우기의 결과", () => {
  it("지웠으면 안내가 없다", () => {
    expect(originalCleanupWarning(null, TITLE, detail)).toBeNull();
  });

  it("404: 다른 곳에서 이미 지워졌다고 알리고, 전체 녹음을 새로 저장했다고 말한다", () => {
    const text = originalCleanupWarning(new ApiError(404, { code: "not_found" }), TITLE, detail);
    expect(text).toContain("이미 지워졌습니다");
    expect(text).toContain(TITLE);
    expect(text).toContain("원래 있던 곳에 새로 저장했습니다");
  });

  it("409 version_conflict: 원래 녹음이 바뀌어 남겨 두었다고 말하고, '다시 시도하세요'를 붙이지 않는다", () => {
    const text = originalCleanupWarning(new ApiError(409, { code: "version_conflict" }), TITLE, detail) || "";
    expect(text).toContain("원래 녹음이 바뀌어");
    expect(text).toContain("남겨 두었습니다");
    expect(text).not.toContain("다시 시도");
    expect(text).not.toContain("상세(");
  });

  it("그 밖의 실패는 둘 다 남았다고 말하고 이유를 붙인다", () => {
    const text = originalCleanupWarning(new ApiError(503, { code: "unavailable" }), TITLE, detail);
    expect(text).toBe(`이어 붙인 녹음을 저장했지만 원래 녹음을 지우지 못해 둘 다 남아 있습니다. 앞부분만 있는 "${TITLE}" 녹음을 지우세요. 상세(unavailable)`);
  });
});
