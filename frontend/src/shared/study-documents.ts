// The student's own PDFs in the study room (`/api/me/study-room/…/documents`). They are stored like recordings:
// owned by that one student, private, never published to the course, counted against the same study-room quota and
// scanned for malware before they open. The student may ask for a page summary of one (`…/documents/{id}/summary`):
// only then is its content sent to the AI provider. Instructors keep registering course materials
// (course-pdfs-remote.ts); only a student of the course uploads here (`uploadsToStudyRoom`).
// Records keep the PDF record shape of course-pdfs.ts so the study room cards and the document viewer show them.
import { apiBlob, apiGet, apiSend, apiUpload, ApiError, newRequestKey } from "./api.ts";
import type { ServerSummary } from "./course-pdfs-remote.ts";
import { backendStatus, courseIdFor, currentUserId, roleIn, subscribe } from "./backend.ts";
import { retryLater } from "./remote-cache.ts";
import {
  BASE, loadCourseView, peekCourseView, refreshCourseView, serverStudyCourse, isMissingEndpoint, studyRoomMessage, studyUsage,
  formatBytes, type DocumentState, type ServerDocument, type StudyCourseView,
} from "./study-room-remote.ts";
import type { PdfPage } from "./course-pdfs.ts";

type Id = number | string;

/** A study-room document in the PDF record shape (page text after `ensureCoursePdfText`). */
export interface StudyDocRecord {
  id: string;
  studyDocument: true;
  remote?: undefined;
  documentId: number;
  backendCourseId: Id;
  courseId: string;
  title: string;
  fileName: string;
  size: number;
  uploadedAt: string;
  updatedAt: string;
  state: DocumentState;
  /** The folder the document is placed in, or null in the course root. */
  folderId: string | null;
  version: number;
  /** The page summary's state; null before any request; undefined on a backend without document summaries. */
  summaryStatus?: DocSummaryStatus | null;
  pageCount: number | null;
  pages: PdfPage[];
  overview: { page: number; text: string }[];
  keywords: string[];
  status: "ready" | "no-text";
}

export const DOC_PREFIX = "doc-";
const DOC_ID = /^doc-([1-9][0-9]*)$/;

/** Said when the backend has no study-room documents (its course view has no `documents` field). */
export const DOCUMENTS_UNSUPPORTED = "이 서버는 아직 학습실에 PDF를 저장하는 기능을 지원하지 않습니다. 서버가 업데이트된 뒤 다시 올려 주세요.";

export function isStudyDocId(id: unknown): boolean {
  return DOC_ID.test(String(id || ""));
}

export function isStudyDoc(record: unknown): record is StudyDocRecord {
  return Boolean(record && typeof record === "object" && "studyDocument" in record && record.studyDocument === true);
}

/**
 * Whether "PDF·문서 업로드" in the study room of `kj` stores the PDF as the student's own study-room document:
 * signed in (backend `ready`) and a student of that course (`role` on `/api/me/courses`). Instructors, and the
 * browser-only study room, keep their current path.
 */
export function uploadsToStudyRoom(kj: string | null | undefined): boolean {
  return backendStatus() === "ready" && roleIn(kj) === "STUDENT";
}

export function toDocRecord(doc: ServerDocument, kj: string): StudyDocRecord {
  return {
    id: `${DOC_PREFIX}${doc.documentId}`,
    studyDocument: true,
    documentId: doc.documentId,
    backendCourseId: doc.courseId,
    courseId: kj,
    title: doc.title,
    fileName: doc.fileName,
    size: doc.sizeBytes,
    uploadedAt: doc.createdAt,
    updatedAt: doc.updatedAt,
    state: doc.state,
    folderId: doc.folderId == null ? null : String(doc.folderId),
    version: doc.version,
    ...(doc.summaryStatus === undefined ? {} : { summaryStatus: doc.summaryStatus }),
    pageCount: null,
    pages: [],
    overview: [],
    keywords: [],
    status: "ready",
  };
}

/** The course view's documents as records, newest first. Empty when the view is not loaded or has no `documents`. */
export function docRecordsOf(view: StudyCourseView | null | undefined, kj: string): StudyDocRecord[] {
  const documents = view && Array.isArray(view.documents) ? view.documents : [];
  return documents.map((doc) => toDocRecord(doc, kj)).sort((a, b) => String(b.uploadedAt).localeCompare(String(a.uploadedAt)));
}

/** The documents of `kj` from the cached course view (a load starts when none is cached). */
export function studyDocuments(kj: string): StudyDocRecord[] {
  const server = serverStudyCourse(kj);
  return server == null ? [] : docRecordsOf(peekCourseView(server).view, kj);
}

// ---------- messages ----------

const MB = 1024 * 1024;

function limitText(): string {
  const usage = studyUsage();
  const bytes = usage && (usage.maxDocumentFileBytes ?? null);
  return formatBytes(bytes || 50 * MB);
}

/** Korean message for a failed study-room document request. */
export function studyDocumentMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "PDF를 학습실에 저장하지 못했습니다. 잠시 후 다시 시도하세요.";
  const usage = studyUsage();
  switch (error.code) {
    case "quota_exceeded": {
      const problem = error.problem || {};
      const used = typeof problem.usedBytes === "number" ? problem.usedBytes : usage ? usage.usedBytes : null;
      const quota = typeof problem.quotaBytes === "number" ? problem.quotaBytes : usage ? usage.quotaBytes : null;
      const amount = used != null && quota != null ? `(${formatBytes(used)} / ${formatBytes(quota)} 사용 중)` : "";
      return `학습실 저장 공간${amount}이 가득 찼습니다. 쓰지 않는 녹음이나 PDF를 지운 뒤 다시 올리세요.`;
    }
    case "too_many_documents": return `학습실에는 PDF를 ${usage && usage.maxDocuments ? `${usage.maxDocuments}개` : "정해진 수"}까지 저장할 수 있습니다. 쓰지 않는 PDF를 지운 뒤 다시 올리세요.`;
    case "too_large": return `PDF 파일이 너무 큽니다. 한 파일은 ${limitText()}까지 올릴 수 있습니다.`;
    case "extension_not_allowed":
    case "invalid_name":
    case "content_mismatch":
    case "not_pdf": return "PDF 파일이 아니거나 파일 내용이 확장자와 맞지 않습니다. 원본 PDF를 선택하세요.";
    case "empty_file": return "비어 있는 파일은 올릴 수 없습니다. 내용이 있는 PDF를 선택하세요.";
    case "file_scanning": return `바이러스 검사 중인 PDF입니다. ${error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 열어 보세요.`;
    case "content_rejected": return "검사에서 악성 코드가 발견된 PDF입니다. 열 수 없습니다. 이 PDF를 지우고 원본 파일을 확인하세요.";
    case "document_deleted": return "이 PDF는 서버에서 이미 지웠습니다(다른 기기나 탭에서).";
    default: break;
  }
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다. 로그인한 뒤 다시 시도하세요.";
    case 404: return "이 과목의 학습실이나 폴더를 찾을 수 없습니다. 이미 지웠거나 이 과목의 수강생이 아닐 수 있습니다.";
    case 410: return "이 PDF는 서버에서 이미 지웠습니다(다른 기기나 탭에서).";
    case 413: return `PDF 파일이 너무 큽니다. 한 파일은 ${limitText()}까지 올릴 수 있습니다.`;
    case 415:
    case 422: return "PDF 파일이 아니거나 파일 내용이 확장자와 맞지 않습니다. 원본 PDF를 선택하세요.";
    case 429: return retryLater(error);
    default: return studyRoomMessage(error);
  }
}

/** An Error with the document sentence that keeps the server error (`cause`) for status checks. */
class DocumentError extends Error {
  constructor(error: unknown) {
    super(studyDocumentMessage(error), { cause: error });
  }
}

// ---------- upload ----------

export interface SaveStudyDocumentInput {
  file: File;
  courseId: string;
  title: string;
  /** A server folder of the course, or null for the course root. */
  folderId?: string | null;
}

/** Title the server accepts: no control characters, at most 300 UTF-16 units, never ending in half of a surrogate pair. */
export function documentTitle(title: string, fileName: string): string {
  // eslint-disable-next-line no-control-regex
  const clean = String(title || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim() || String(fileName || "").replace(/\.pdf$/i, "").trim() || "PDF";
  if (clean.length <= 300) return clean;
  const last = clean.charCodeAt(299);
  return clean.slice(0, last >= 0xd800 && last <= 0xdbff ? 299 : 300);
}

/**
 * A stable key for uploading this file with this title into this folder as this user: choosing the same file again
 * after a lost answer replays the first upload instead of storing it twice.
 */
export async function documentKey(file: File, userId: string, courseId: Id, folderId: string | null, title: string): Promise<string> {
  const text = new TextEncoder().encode(`${userId}|${courseId}|${folderId || ""}|${file.name}|${file.size}|${file.lastModified}|${title}`);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", text));
  return `doc-file-${[...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("").slice(0, 48)}`;
}

async function postDocument(courseId: Id, file: File, title: string, folderId: string | null, key: string): Promise<ServerDocument> {
  for (let attempt = 0; ; attempt += 1) {
    const form = new FormData();
    form.append("file", file, file.name || "document.pdf");
    form.append("title", title);
    if (folderId) form.append("folderId", folderId);
    form.append("key", key);
    try {
      return await apiUpload<ServerDocument>(`${BASE}/courses/${encodeURIComponent(courseId)}/documents`, form, { idempotencyKey: key });
    } catch (error) {
      // The first request with this key is still being processed: ask again shortly with the same key.
      if (error instanceof ApiError && error.code === "idempotency_in_progress" && attempt < 3) {
        await new Promise<void>((resolve) => { setTimeout(resolve, 1500); });
        continue;
      }
      throw error;
    }
  }
}

/**
 * Stores a student's PDF in the study room of `courseId` (`POST …/courses/{id}/documents`), in `folderId` when
 * given. Rejects with a Korean message; on a backend whose course view has no `documents`, with
 * `DOCUMENTS_UNSUPPORTED` before anything is sent.
 */
export async function saveStudyDocument({ file, courseId, title, folderId = null }: SaveStudyDocumentInput): Promise<StudyDocRecord> {
  if (!(file instanceof Blob) || !file.size) throw new Error("비어 있는 파일은 올릴 수 없습니다. 내용이 있는 PDF를 선택하세요.");
  const name = String(file.name || "");
  if (!/\.pdf$/i.test(name) && file.type !== "application/pdf") throw new Error("PDF 파일만 학습실에 올릴 수 있습니다.");
  const server = serverStudyCourse(courseId);
  if (server == null) {
    // Signed in, course on the server, but the study-room endpoints answered 404 earlier: an older backend.
    if (backendStatus() === "ready" && courseIdFor(courseId) != null) throw new Error(DOCUMENTS_UNSUPPORTED);
    throw new Error("이 과목은 ASTRA 서버에 없어 학습실에 PDF를 올릴 수 없습니다.");
  }
  let view: StudyCourseView;
  try {
    view = await loadCourseView(server);
  } catch (error) {
    if (isMissingEndpoint(error)) throw new Error(DOCUMENTS_UNSUPPORTED);
    throw new DocumentError(error);
  }
  if (view.documents === null) throw new Error(DOCUMENTS_UNSUPPORTED);
  const limit = view.usage && view.usage.maxDocumentFileBytes;
  if (limit && file.size > limit) throw new Error(`PDF 파일이 너무 큽니다. 한 파일은 ${formatBytes(limit)}까지 올릴 수 있습니다.`);
  const label = documentTitle(title, name);
  const userId = currentUserId();
  if (!userId) throw new Error("ASTRA 서버 로그인이 필요합니다. 로그인한 뒤 다시 시도하세요.");
  const folder = folderId ? String(folderId) : null;
  const key = await documentKey(file, userId, server, folder, label);
  let doc: ServerDocument;
  try {
    try {
      doc = await postDocument(server, file, label, folder, key);
    } catch (error) {
      // 410: this file was uploaded under this key before and that document deleted since. The user chose it again,
      // so it is a new upload under a second fixed key (a lost answer replays too), then a random one.
      if (!(error instanceof ApiError && error.status === 410)) throw error;
      try {
        doc = await postDocument(server, file, label, folder, `${key}-again`);
      } catch (again) {
        if (!(again instanceof ApiError && again.status === 410)) throw again;
        doc = await postDocument(server, file, label, folder, `${key}-${crypto.randomUUID()}`);
      }
    }
  } catch (error) {
    throw new DocumentError(error);
  } finally {
    void refreshCourseView(server);
  }
  return toDocRecord(doc, courseId);
}

// ---------- one document ----------

const path = (documentId: Id) => `${BASE}/documents/${encodeURIComponent(documentId)}`;

/** `GET …/documents/{id}`. */
export function getStudyDocument(documentId: Id): Promise<ServerDocument> {
  return apiGet<ServerDocument>(path(documentId));
}

/** After a 409 `version_conflict` the view is read again so the next try sends the new version. */
async function afterWrite(record: StudyDocRecord, error?: unknown): Promise<void> {
  if (error === undefined) {
    void refreshCourseView(record.backendCourseId);
    return;
  }
  if (error instanceof ApiError && (error.code === "version_conflict" || error.status === 404 || error.status === 410)) {
    await loadCourseView(record.backendCourseId, true).catch(() => undefined);
  }
}

/** `PATCH …/documents/{id}` with the title and the version the screen read. */
export async function renameStudyDocument(record: StudyDocRecord, title: string): Promise<StudyDocRecord> {
  const label = String(title || "").trim();
  if (!label) throw new Error("PDF 이름을 입력하세요.");
  try {
    const doc = await apiSend<ServerDocument>("PATCH", path(record.documentId), { title: documentTitle(label, record.fileName), version: record.version });
    await afterWrite(record);
    return toDocRecord(doc, record.courseId);
  } catch (error) {
    await afterWrite(record, error);
    throw new DocumentError(error);
  }
}

/** `DELETE …/documents/{id}?version=n`. */
export async function deleteStudyDocument(record: StudyDocRecord): Promise<void> {
  try {
    await apiSend<null>("DELETE", `${path(record.documentId)}?version=${encodeURIComponent(record.version)}`);
    blobs.delete(record.id);
    await afterWrite(record);
  } catch (error) {
    await afterWrite(record, error);
    throw new DocumentError(error);
  }
}

// ---------- page summary ----------

/** Where a document's page summary stands. */
export type DocSummaryStatus = "queued" | "processing" | "ready" | "failed";

/** `POST`/`GET …/documents/{id}/summary`: `message` only when failed, `summary` only when ready. */
export interface DocSummaryAnswer {
  status: DocSummaryStatus;
  message?: string | null;
  summary?: (ServerSummary & { pageImageBase?: string | null; fileId?: number }) | null;
}

/** True when the backend offers summaries of the student's documents (its document view carries `summaryStatus`). */
export function summaryOffered(record: StudyDocRecord): boolean {
  return record.summaryStatus !== undefined && record.state === "ready";
}

const pendingSummary = new Map<string, string>(); // document id -> key of a start that ended without an answer

/**
 * `POST …/documents/{id}/summary`: starts a job (202), or answers the one queued, processing or ready. A start that
 * ended without an answer keeps its `Idempotency-Key` for the retry.
 */
export async function startDocSummary(documentId: Id): Promise<DocSummaryAnswer> {
  const scope = String(documentId);
  const key = pendingSummary.get(scope) || newRequestKey();
  pendingSummary.set(scope, key);
  try {
    const answer = await apiSend<DocSummaryAnswer>("POST", `${path(documentId)}/summary`, undefined, { idempotencyKey: key });
    pendingSummary.delete(scope);
    return answer;
  } catch (error) {
    if (!(error instanceof ApiError && [0, 502, 503, 504].includes(error.status) && error.code !== "llm_disabled")) pendingSummary.delete(scope);
    throw error;
  }
}

/** `GET …/documents/{id}/summary`; null before any request (404 `not_found`). Also renews the page-image cookies. */
export async function getDocSummary(documentId: Id): Promise<DocSummaryAnswer | null> {
  try {
    return await apiGet<DocSummaryAnswer>(`${path(documentId)}/summary`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404 && error.code === "not_found") return null;
    throw error;
  }
}

/** Seconds between checks of a queued or processing summary: the server's `Retry-After` (5), kept within 4–10. */
export function summaryPollDelay(error?: unknown): number {
  const cause = error instanceof Error && error.cause instanceof ApiError ? error.cause : error;
  const seconds = cause instanceof ApiError && cause.retryAfter ? cause.retryAfter : 5;
  return Math.min(10, Math.max(4, seconds));
}

/** How many times a screen checks a queued or processing summary before it stops and offers a button. */
export const SUMMARY_CHECKS = 90;

/** Korean message for a failed summary request. */
export function docSummaryMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "요약 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  switch (error.code) {
    case "llm_disabled": return "지금 이 서버에서는 AI 요약을 쓸 수 없습니다(AI 제공자가 설정되지 않았습니다).";
    case "summary_daily_limit": return `오늘 만들 수 있는 요약 수를 다 썼습니다. ${error.retryAfter ? `${Math.ceil(error.retryAfter / 3600)}시간쯤 뒤` : "내일"} 다시 시도하세요.`;
    case "file_scanning": return "바이러스 검사가 끝난 뒤에 요약을 만들 수 있습니다. 잠시 후 다시 시도하세요.";
    case "content_rejected": return "검사에서 악성 코드가 발견된 PDF라 요약을 만들 수 없습니다.";
    case "idempotency_in_progress": return "같은 요약 요청을 처리하고 있습니다. 잠시 후 다시 확인하세요.";
    default: break;
  }
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다. 로그인한 뒤 다시 시도하세요.";
    case 404: return "이 PDF를 찾을 수 없습니다. 이미 지웠거나 이 계정의 PDF가 아닙니다.";
    case 429: return `요약 요청이 많습니다. ${error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
    case 503: return "AI 요약을 지금 쓸 수 없습니다. 잠시 후 다시 시도하세요.";
    default: return studyDocumentMessage(error);
  }
}

// ---------- content ----------

const BLOB_CACHE = 4;
const blobs = new Map<string, Blob>(); // record id -> PDF, insertion order = LRU order
let session = "";
let generation = 0;
subscribe((next) => {
  const key = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (key === session) return;
  session = key;
  generation += 1;
  blobs.clear();
  pendingSummary.clear();
});

/**
 * The original PDF of `doc-<id>` (`GET …/documents/{id}/content`), only for a `ready` document. A scanning one
 * rejects with the server's 409 `file_scanning` (with `Retry-After`), a rejected one with 422 `content_rejected`.
 */
export async function getStudyDocumentBlob(id: unknown): Promise<Blob | null> {
  const key = String(id || "");
  const match = DOC_ID.exec(key);
  if (!match) return null;
  const hit = blobs.get(key);
  if (hit) {
    blobs.delete(key);
    blobs.set(key, hit);
    return hit;
  }
  const before = generation;
  let raw: Blob;
  try {
    raw = await apiBlob(`${path(match[1])}/content`);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 410)) return null;
    throw new DocumentError(error);
  }
  if (before !== generation) return null;
  const blob = raw.type === "application/pdf" ? raw : raw.slice(0, raw.size, "application/pdf");
  blobs.set(key, blob);
  for (const oldest of blobs.keys()) {
    if (blobs.size <= BLOB_CACHE) break;
    blobs.delete(oldest);
  }
  return blob;
}

/** Seconds to wait before asking again about a scanning document: the server's `Retry-After`, else 10, at least 2. */
export function scanDelay(error?: unknown): number {
  const cause = error instanceof Error && error.cause instanceof ApiError ? error.cause : error;
  const seconds = cause instanceof ApiError && cause.retryAfter ? cause.retryAfter : 10;
  return Math.min(120, Math.max(2, seconds));
}

/** How many times a screen asks again about a scanning document before it stops and offers a button. */
export const SCAN_CHECKS = 30;
