import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as ApiModule from "../../src/shared/api.ts";
import type * as BackendModule from "../../src/shared/backend.ts";

// 학습실이 서버에 있는 상태(backend ready, 과목 bi = 서버 과목 9). 서버 호출(api.ts)만 가짜로 바꾸고, 과목 view 읽기·
// 문서 레코드 변환·메시지 문장은 실제 코드를 쓴다.
const role = { value: "STUDENT" as string | null, status: "ready" as "ready" | "anon" | "off" };
vi.mock("../../src/shared/backend.ts", async (original) => ({
  ...await original<typeof BackendModule>(),
  backendStatus: () => role.status,
  roleIn: (kj: string | null | undefined) => (kj === "bi" ? role.value : null),
  courseIdFor: (kj: string | null | undefined) => (kj === "bi" ? 9 : null),
  currentUserId: () => "21101234",
}));
vi.mock("../../src/shared/api.ts", async (original) => ({
  ...await original<typeof ApiModule>(),
  apiGet: vi.fn(),
  apiSend: vi.fn(),
  apiUpload: vi.fn(),
  apiBlob: vi.fn(),
}));

import { ApiError, apiGet, apiSend, apiUpload } from "../../src/shared/api.ts";
import { courseViews, loadCourseView, type ServerDocument } from "../../src/shared/study-room-remote.ts";
import {
  DOCUMENTS_UNSUPPORTED, deleteStudyDocument, docRecordsOf, renameStudyDocument, saveStudyDocument, scanDelay, studyDocumentMessage,
  uploadsToStudyRoom,
} from "../../src/shared/study-documents.ts";
import { placeItem, placements, serverFolderOf, unplaceItem } from "../../src/shared/study-folders.ts";

const doc = (patch: Partial<ServerDocument> = {}): ServerDocument => ({
  documentId: 7, courseId: 9, title: "내 노트", fileName: "내 노트.pdf", sizeBytes: 2048, state: "scanning", folderId: null,
  createdAt: "2026-11-14T01:00:00Z", updatedAt: "2026-11-14T01:00:00Z", version: 1, ...patch,
});
const usage = { usedBytes: 1900 * 1024 * 1024, quotaBytes: 2048 * 1024 * 1024, recordingCount: 3, maxRecordings: 200, maxFileBytes: 524288000, maxFoldersPerCourse: 50, documentCount: 4, maxDocuments: 100, maxDocumentFileBytes: 50 * 1024 * 1024 };
const folder = { id: 5, courseId: 9, name: "중간고사", position: 1, createdAt: "2026-11-14T00:00:00Z", version: 1 };
let viewBody: Record<string, unknown>;
const pdf = (name = "내 노트.pdf") => new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], name, { type: "application/pdf", lastModified: 1763000000000 });

beforeEach(() => {
  role.value = "STUDENT";
  role.status = "ready";
  localStorage.clear();
  courseViews.drop(() => true);
  viewBody = { folders: [folder], recordings: [], placements: [], usage, documents: [] };
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === "/api/me/study-room/courses/9") return viewBody;
    throw new ApiError(404, { code: "not_found" });
  });
  vi.mocked(apiUpload).mockResolvedValue(doc());
  vi.mocked(apiSend).mockResolvedValue(null);
});

describe("uploadsToStudyRoom (학생 / 교수 경로 고르기)", () => {
  it("서버 로그인 상태에서 그 과목의 학생이면 내 학습실 문서로 올린다", () => {
    expect(uploadsToStudyRoom("bi")).toBe(true);
  });

  it("교수(INSTRUCTOR)는 지금처럼 강의자료로 등록한다", () => {
    role.value = "INSTRUCTOR";
    expect(uploadsToStudyRoom("bi")).toBe(false);
  });

  it("서버 연결이 없거나 서버 과목이 아니면 쓰지 않는다(브라우저 학습실)", () => {
    role.status = "off";
    expect(uploadsToStudyRoom("bi")).toBe(false);
    role.status = "ready";
    expect(uploadsToStudyRoom("sample")).toBe(false);
  });
});

describe("과목 view의 documents", () => {
  it("documents가 있으면 최신순 레코드(doc-<id>)로 바꾸고, 배치는 doc-<id>로 읽는다", async () => {
    viewBody = {
      ...viewBody,
      documents: [doc({ documentId: 7, createdAt: "2026-11-14T01:00:00Z" }), doc({ documentId: 8, state: "ready", folderId: 5, createdAt: "2026-11-14T02:00:00Z", title: "기출" })],
      placements: [{ kind: "document", itemId: 8, folderId: 5, version: 3 }, { kind: "recording", itemId: 7, folderId: 5, version: 1 }],
    };
    const view = await loadCourseView(9, true);
    const records = docRecordsOf(view, "bi");
    expect(records.map((record) => [record.id, record.state, record.folderId, record.title])).toEqual([
      ["doc-8", "ready", "5", "기출"],
      ["doc-7", "scanning", null, "내 노트"],
    ]);
    expect(records[0]).toMatchObject({ studyDocument: true, documentId: 8, backendCourseId: 9, courseId: "bi", pageCount: null, size: 2048 });
    expect(placements()).toMatchObject({ "doc-8": "5", "7": "5" });
    expect(serverFolderOf("bi", "doc-8")).toBe("5");
    expect(serverFolderOf("bi", "doc-7")).toBe("");
  });

  it("documents 필드가 없는 백엔드는 null로 둔다(빈 목록과 구별)", async () => {
    delete viewBody.documents;
    const view = await loadCourseView(9, true);
    expect(view.documents).toBeNull();
    expect(docRecordsOf(view, "bi")).toEqual([]);
  });
});

describe("saveStudyDocument", () => {
  it("POST …/courses/9/documents에 file·title·folderId·key를 보내고, 헤더 키와 form key가 같다", async () => {
    const record = await saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트", folderId: "5" });
    expect(record).toMatchObject({ id: "doc-7", state: "scanning" });
    const [path, form, options] = vi.mocked(apiUpload).mock.calls[0];
    expect(path).toBe("/api/me/study-room/courses/9/documents");
    expect(form.get("title")).toBe("내 노트");
    expect(form.get("folderId")).toBe("5");
    expect((form.get("file") as File).name).toBe("내 노트.pdf");
    expect(options?.idempotencyKey).toMatch(/^doc-file-[0-9a-f]{48}$/);
    expect(form.get("key")).toBe(options?.idempotencyKey);
  });

  it("응답을 잃은 뒤 같은 파일을 다시 고르면 같은 키로 보내 서버가 재생한다", async () => {
    vi.mocked(apiUpload).mockRejectedValueOnce(new ApiError(0, { detail: "ASTRA 서버에 연결하지 못했습니다." }));
    await expect(saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" })).rejects.toThrow("ASTRA 서버에 연결하지 못했습니다.");
    await saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" });
    const keys = vi.mocked(apiUpload).mock.calls.map((call) => call[2]?.idempotencyKey);
    expect(keys[0]).toBe(keys[1]);
    expect(vi.mocked(apiUpload).mock.calls[0][1].get("folderId")).toBeNull();
  });

  it("다른 폴더에 같은 파일을 올리면 다른 키다", async () => {
    await saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" });
    await saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트", folderId: "5" });
    const keys = vi.mocked(apiUpload).mock.calls.map((call) => call[2]?.idempotencyKey);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it("같은 키의 문서를 지운 뒤(410) 다시 올리면 두 번째 고정 키로 보낸다", async () => {
    vi.mocked(apiUpload).mockRejectedValueOnce(new ApiError(410, { code: "document_deleted" }));
    await saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" });
    const keys = vi.mocked(apiUpload).mock.calls.map((call) => call[2]?.idempotencyKey);
    expect(keys[1]).toBe(`${keys[0]}-again`);
  });

  it("documents 필드가 없는 지금의 백엔드에서는 아무것도 보내지 않고 미지원 문장으로 실패한다", async () => {
    delete viewBody.documents;
    await expect(saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" })).rejects.toThrow(DOCUMENTS_UNSUPPORTED);
    expect(apiUpload).not.toHaveBeenCalled();
  });

  it("PDF가 아니거나 한도보다 크면 보내지 않는다", async () => {
    await expect(saveStudyDocument({ file: new File(["x"], "a.png", { type: "image/png" }), courseId: "bi", title: "a" })).rejects.toThrow("PDF 파일만");
    viewBody = { ...viewBody, usage: { ...usage, maxDocumentFileBytes: 2 } };
    await expect(saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" })).rejects.toThrow("PDF 파일이 너무 큽니다. 한 파일은 1KB까지");
    expect(apiUpload).not.toHaveBeenCalled();
  });

  it("서버 오류는 한국어 문장으로 바꾼다(저장 공간 가득)", async () => {
    vi.mocked(apiUpload).mockRejectedValueOnce(new ApiError(409, { code: "quota_exceeded" }));
    await expect(saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" }))
      .rejects.toThrow("학습실 저장 공간(1.9GB / 2GB 사용 중)이 가득 찼습니다. 쓰지 않는 녹음이나 PDF를 지운 뒤 다시 올리세요.");
  });
});

describe("studyDocumentMessage", () => {
  beforeEach(async () => {
    await loadCourseView(9, true);
  });

  it("저장 공간: 응답 본문의 사용량을 먼저, 없으면 과목 view의 사용량을 쓴다", () => {
    expect(studyDocumentMessage(new ApiError(409, { code: "quota_exceeded", problem: { usedBytes: 100 * 1024 * 1024, quotaBytes: 200 * 1024 * 1024 } })))
      .toBe("학습실 저장 공간(100MB / 200MB 사용 중)이 가득 찼습니다. 쓰지 않는 녹음이나 PDF를 지운 뒤 다시 올리세요.");
    expect(studyDocumentMessage(new ApiError(409, { code: "quota_exceeded" }))).toContain("(1.9GB / 2GB 사용 중)");
  });

  it("너무 큼, PDF 아님, 요청 많음, 로그인 필요, 개수 한도, 검사 중·거부", () => {
    expect(studyDocumentMessage(new ApiError(413))).toBe("PDF 파일이 너무 큽니다. 한 파일은 50MB까지 올릴 수 있습니다.");
    expect(studyDocumentMessage(new ApiError(415))).toBe("PDF 파일이 아니거나 파일 내용이 확장자와 맞지 않습니다. 원본 PDF를 선택하세요.");
    expect(studyDocumentMessage(new ApiError(422, { code: "content_mismatch" }))).toBe("PDF 파일이 아니거나 파일 내용이 확장자와 맞지 않습니다. 원본 PDF를 선택하세요.");
    expect(studyDocumentMessage(new ApiError(429, { retryAfter: 30 }))).toBe("요청이 많습니다. 30초 뒤 다시 시도하세요.");
    expect(studyDocumentMessage(new ApiError(401))).toBe("ASTRA 서버 로그인이 필요합니다. 로그인한 뒤 다시 시도하세요.");
    expect(studyDocumentMessage(new ApiError(409, { code: "too_many_documents" }))).toBe("학습실에는 PDF를 100개까지 저장할 수 있습니다. 쓰지 않는 PDF를 지운 뒤 다시 올리세요.");
    expect(studyDocumentMessage(new ApiError(409, { code: "file_scanning", retryAfter: 10 }))).toBe("바이러스 검사 중인 PDF입니다. 10초 뒤 다시 열어 보세요.");
    expect(studyDocumentMessage(new ApiError(422, { code: "content_rejected" }))).toContain("악성 코드가 발견된 PDF입니다");
    expect(studyDocumentMessage(new ApiError(409, { code: "version_conflict" }))).toBe("다른 탭이나 기기에서 먼저 바뀌었습니다. 다시 불러왔습니다. 확인한 뒤 다시 시도하세요.");
  });

  it("검사 중 다시 묻는 간격은 Retry-After(없으면 10초), 2~120초", () => {
    expect(scanDelay(new ApiError(409, { code: "file_scanning", retryAfter: 4 }))).toBe(4);
    expect(scanDelay()).toBe(10);
    expect(scanDelay(new ApiError(409, { retryAfter: 1 }))).toBe(2);
    expect(scanDelay(new ApiError(409, { retryAfter: 900 }))).toBe(120);
  });
});

describe("이름 바꾸기·삭제·폴더 옮기기", () => {
  const record = () => docRecordsOf({ courseId: 9, folders: [], recordings: [], placements: [], usage: null, documents: [doc({ state: "ready", version: 4 })] }, "bi")[0];

  it("PATCH는 title과 읽은 version을 보낸다", async () => {
    vi.mocked(apiSend).mockResolvedValueOnce(doc({ state: "ready", title: "새 이름", version: 5 }));
    const next = await renameStudyDocument(record(), " 새 이름 ");
    expect(apiSend).toHaveBeenCalledWith("PATCH", "/api/me/study-room/documents/7", { title: "새 이름", version: 4 });
    expect(next).toMatchObject({ title: "새 이름", version: 5 });
  });

  it("DELETE는 ?version=n이고, 409 version_conflict면 과목 view를 다시 읽고 충돌 문장을 낸다", async () => {
    await deleteStudyDocument(record());
    expect(apiSend).toHaveBeenCalledWith("DELETE", "/api/me/study-room/documents/7?version=4");
    vi.mocked(apiGet).mockClear();
    vi.mocked(apiSend).mockRejectedValueOnce(new ApiError(409, { code: "version_conflict" }));
    const failure = deleteStudyDocument(record());
    await expect(failure).rejects.toThrow("다른 탭이나 기기에서 먼저 바뀌었습니다.");
    await expect(failure.catch((error: Error) => (error.cause as ApiError).code)).resolves.toBe("version_conflict");
    expect(apiGet).toHaveBeenCalledWith("/api/me/study-room/courses/9");
  });

  it("폴더로 옮기기는 placements/documents, 과목 첫 화면으로는 그 배치를 DELETE", async () => {
    viewBody = { ...viewBody, placements: [{ kind: "document", itemId: 7, folderId: 5, version: 2 }] };
    await loadCourseView(9, true);
    vi.mocked(apiSend).mockResolvedValueOnce({ kind: "document", itemId: 7, folderId: 5, version: 3 });
    await placeItem("doc-7", "5", { retryOnConflict: false });
    expect(apiSend).toHaveBeenCalledWith("PUT", "/api/me/study-room/placements/documents/7", { folderId: 5 }, { ifMatch: 2 });
    await unplaceItem("bi", "doc-7");
    expect(apiSend).toHaveBeenCalledWith("DELETE", "/api/me/study-room/placements/documents/7", undefined, { ifMatch: 2 });
  });
});

// 마지막에 둔다: 404를 본 뒤로 이 세션은 학습실이 서버에 없다고 기억한다(세션이 바뀔 때만 지운다).
describe("학습실이 없는 백엔드", () => {
  it("과목 view가 404면 미지원 문장이고, 그 뒤로도 같다", async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError(404, { code: "not_found" }));
    await expect(saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" })).rejects.toThrow(DOCUMENTS_UNSUPPORTED);
    await expect(saveStudyDocument({ file: pdf(), courseId: "bi", title: "내 노트" })).rejects.toThrow(DOCUMENTS_UNSUPPORTED);
    expect(apiUpload).not.toHaveBeenCalled();
  });
});
