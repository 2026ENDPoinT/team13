import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../src/shared/api.ts";
import type * as BackendModule from "../../src/shared/backend.ts";
import type * as RemoteModule from "../../src/shared/study-room-remote.ts";
import type { StudyCourseView } from "../../src/shared/study-room-remote.ts";

// 학습실이 서버에 있는 상태(backend ready, 과목 bi = 서버 과목 9). 서버 호출만 가짜로 바꾸고 메시지 문장은 실제 것을 쓴다.
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof BackendModule>(),
  backendStatus: () => "ready",
  courseIdFor: (kj: string | null | undefined) => (kj === "bi" ? 9 : null),
  currentUserId: () => "21101234",
}));
vi.mock("../../src/shared/study-room-remote.ts", async (original) => ({
  ...await original<typeof RemoteModule>(),
  studyRoomOnServer: () => true,
  serverStudyCourse: (kj: string | null | undefined) => (kj === "bi" ? 9 : null),
  cachedViews: vi.fn(),
  peekCourseView: vi.fn(),
  loadCourseView: vi.fn(),
  refreshCourseView: vi.fn(),
  putPlacement: vi.fn(),
}));

import { cachedViews, loadCourseView, peekCourseView, putPlacement, refreshCourseView } from "../../src/shared/study-room-remote.ts";
import { FOLDER_GONE, placeItem, serverFolderOf } from "../../src/shared/study-folders.ts";

const folder = (id: number, name: string) => ({ id, courseId: 9, name, position: id, createdAt: "2026-11-14T00:00:00Z", version: 1 });
const view = (patch: Partial<StudyCourseView> = {}): StudyCourseView => ({
  courseId: 9, folders: [folder(5, "중간고사"), folder(6, "기말고사")], recordings: [], placements: [], usage: null, documents: [], ...patch,
});
const conflict = () => new ApiError(409, { code: "version_conflict" });
let current: StudyCourseView;
// 서버에서 바뀐 내용: 다시 불러오면 이 view가 된다.
let onServer: StudyCourseView;

beforeEach(() => {
  current = view();
  onServer = current;
  localStorage.clear();
  vi.mocked(cachedViews).mockImplementation(() => [current]);
  vi.mocked(peekCourseView).mockImplementation(() => ({ view: current, error: null, loading: false }));
  vi.mocked(loadCourseView).mockImplementation(async () => {
    current = onServer;
    return current;
  });
  vi.mocked(refreshCourseView).mockResolvedValue(undefined);
  vi.mocked(putPlacement).mockResolvedValue({ kind: "course_file", itemId: 345, folderId: 5, version: 1 });
});

describe("serverFolderOf", () => {
  it("서버 배치가 있으면 그 폴더 id, 없으면 과목 첫 화면(\"\")이다", () => {
    current = view({ placements: [{ kind: "course_file", itemId: 345, folderId: 6, version: 2 }] });
    expect(serverFolderOf("bi", "srv-345")).toBe("6");
    expect(serverFolderOf("bi", "srv-346")).toBe("");
  });

  it("같은 번호의 녹음 배치는 PDF 배치로 읽지 않는다", () => {
    current = view({ placements: [{ kind: "recording", itemId: 345, folderId: 6, version: 1 }] });
    expect(serverFolderOf("bi", "srv-345")).toBe("");
    expect(serverFolderOf("bi", "345")).toBe("6");
  });

  it("이 브라우저의 배치는 서버 위치로 치지 않는다", () => {
    localStorage.setItem("astra-study-folders", JSON.stringify({ folders: [], placement: { "srv-345": "local-folder" } }));
    expect(serverFolderOf("bi", "srv-345")).toBe("");
  });

  it("과목 view를 아직 받지 못했거나, 서버 과목이 아니거나, 서버가 둘 수 없는 id면 null이다", () => {
    vi.mocked(peekCourseView).mockReturnValue({ view: null, error: null, loading: true });
    expect(serverFolderOf("bi", "srv-345")).toBeNull();
    vi.mocked(peekCourseView).mockReturnValue({ view: current, error: null, loading: false });
    expect(serverFolderOf("sample", "srv-345")).toBeNull();
    expect(serverFolderOf("bi", "srv-abc")).toBeNull();
  });
});

describe("placeItem (서버 PDF)", () => {
  it("과목 첫 화면에 있던 PDF는 If-Match 0으로, 폴더에 있던 PDF는 그 배치 version으로 보낸다", async () => {
    await placeItem("srv-345", "5", { retryOnConflict: false });
    expect(putPlacement).toHaveBeenLastCalledWith("course-files", "345", "5", 0);
    current = view({ placements: [{ kind: "course_file", itemId: 345, folderId: 5, version: 3 }] });
    await placeItem("srv-345", "6", { retryOnConflict: false });
    expect(putPlacement).toHaveBeenLastCalledWith("course-files", "345", "6", 3);
    expect(refreshCourseView).toHaveBeenCalledWith(9);
  });

  it("retryOnConflict: false면 409에서 다시 불러온 뒤 다시 보내지 않고 충돌 문장을 던진다", async () => {
    onServer = view({ placements: [{ kind: "course_file", itemId: 345, folderId: 6, version: 2 }] });
    vi.mocked(putPlacement).mockRejectedValueOnce(conflict());
    await expect(placeItem("srv-345", "5", { retryOnConflict: false }))
      .rejects.toThrow("다른 탭이나 기기에서 먼저 바뀌었습니다. 다시 불러왔습니다. 확인한 뒤 다시 시도하세요.");
    expect(putPlacement).toHaveBeenCalledTimes(1);
    expect(loadCourseView).toHaveBeenCalledWith(9, true);
    // 이미 새로 불러왔으므로 한 번 더 부르지 않는다. 화면은 다른 세션이 옮긴 위치를 본다.
    expect(refreshCourseView).not.toHaveBeenCalled();
    expect(serverFolderOf("bi", "srv-345")).toBe("6");
  });

  it("기본값은 409에서 다시 불러온 version으로 한 번 더 보낸다", async () => {
    onServer = view({ placements: [{ kind: "course_file", itemId: 345, folderId: 6, version: 2 }] });
    vi.mocked(putPlacement).mockRejectedValueOnce(conflict());
    await placeItem("srv-345", "5");
    expect(putPlacement).toHaveBeenCalledTimes(2);
    expect(putPlacement).toHaveBeenLastCalledWith("course-files", "345", "5", 2);
    expect(refreshCourseView).toHaveBeenCalledWith(9);
  });

  it("404에서 다시 불러온 view에 폴더가 없으면 폴더가 지워졌다고 알린다", async () => {
    onServer = view({ folders: [folder(6, "기말고사")] });
    vi.mocked(putPlacement).mockRejectedValueOnce(new ApiError(404, { code: "not_found" }));
    await expect(placeItem("srv-345", "5", { retryOnConflict: false })).rejects.toThrow(FOLDER_GONE);
    expect(loadCourseView).toHaveBeenCalledWith(9, true);
    expect(refreshCourseView).not.toHaveBeenCalled();
  });

  it("404인데 폴더가 그대로면 자료를 찾을 수 없다(지워졌거나 수강생이 아님)는 문장이다", async () => {
    vi.mocked(putPlacement).mockRejectedValueOnce(new ApiError(404, { code: "not_found" }));
    await expect(placeItem("srv-345", "5", { retryOnConflict: false }))
      .rejects.toThrow("항목을 찾을 수 없습니다. 이미 지웠거나 이 과목의 수강생이 아닐 수 있습니다.");
  });

  it("404 뒤 다시 불러오기도 실패하면 404 문장을 그대로 쓰고 나중에 새로 불러온다", async () => {
    vi.mocked(putPlacement).mockRejectedValueOnce(new ApiError(404, { code: "not_found" }));
    vi.mocked(loadCourseView).mockRejectedValueOnce(new ApiError(0, { detail: "네트워크 오류" }));
    await expect(placeItem("srv-345", "5")).rejects.toThrow("항목을 찾을 수 없습니다.");
    expect(refreshCourseView).toHaveBeenCalledWith(9);
  });

  it("요청 한도(429)는 Retry-After를 넣은 문장이다", async () => {
    vi.mocked(putPlacement).mockRejectedValueOnce(new ApiError(429, { code: "rate_limited", retryAfter: 30 }));
    await expect(placeItem("srv-345", "5")).rejects.toThrow("요청이 많습니다. 30초 뒤 다시 시도하세요.");
  });
});
