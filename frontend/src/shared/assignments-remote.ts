// Assignments, submissions and grading on the ASTRA backend
// (`/api/courses/{courseId}/assignments/...`). The server decides the submission phase with its
// own clock, sends a student only their own submission, and leaves `grade` out of a student's
// response until scores are released. Responses stay in memory only.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";
import { koDateTime } from "./home-remote.ts";
import { forgetGradebook } from "./grades-remote.ts";
import { SCHOOL_PROBLEM_TEXT, type SchoolFile } from "./school-content.tsx";

export type Id = number | string;

/** The assignment as an instructor edits it (also inside a student's detail). */
export interface AssignmentInfo {
  assignmentId: number;
  weekNo: number;
  title: string;
  description?: string | null;
  openAt?: string | null;
  closeAt: string;
  published?: boolean;
  latePolicy?: string | null;
  lateUntil?: string | null;
  allowText?: boolean;
  allowFile?: boolean;
  maxScore: number;
  scoresReleased?: boolean;
}

/**
 * One row of `GET …/assignments`: `status` for a student, counts for the instructor. A student's list ends with the school's
 * assignments (`origin: "school"`, `assignmentId` = `activityId` = `school-<n>`, `status` from the imported done mark, `schoolUrl`).
 */
export interface AssignmentListItem {
  assignmentId: number | string;
  activityId?: number | string;
  origin?: "school";
  schoolUrl?: string | null;
  weekNo: number;
  title: string;
  openAt?: string | null;
  closeAt: string;
  phase: string;
  status?: string;
  published?: boolean;
  maxScore?: number;
  submittedCount?: number;
  lateCount?: number;
}

export interface SubmissionFile {
  fileId: number | string;
  name: string;
  size?: number;
  sizeBytes?: number;
}

export interface SubmissionDraft {
  body: string | null;
  savedAt?: string | null;
}

export interface SubmissionGrade {
  score: number;
  feedback?: string | null;
  gradedAt?: string | null;
}

/** A student's own submission inside the detail. */
export interface MySubmission {
  status: string;
  version: number;
  body?: string | null;
  files?: SubmissionFile[];
  submittedAt?: string | null;
  firstSubmittedAt?: string | null;
  draft?: SubmissionDraft | null;
  grade?: SubmissionGrade | null;
}

/** `GET …/assignments/{id}`: student fields (`phase`, `submission`) or instructor fields (`revision`, counts). */
export interface AssignmentDetail {
  assignment: AssignmentInfo;
  phase?: string;
  serverTime?: string;
  submission?: MySubmission | null;
  revision?: number;
  submittedCount?: number;
  lateCount?: number;
}

/**
 * `GET …/assignments/school-<n>`: a school assignment read with the student's own school session, read only. Attachments and the
 * student's own submitted files have the school file shape (`school-file-<n>` until copied); nothing is submitted from STack.
 */
export interface SchoolAssignmentDetail {
  origin?: "school";
  schoolUrl?: string | null;
  phase?: string;
  serverTime?: string;
  assignment: {
    assignmentId: string;
    weekNo?: number | null;
    title: string;
    description?: string | null;
    openAt?: string | null;
    closeAt?: string | null;
    files?: SchoolFile[] | null;
    schoolUrl?: string | null;
  };
  submission?: {
    status: string;
    submittedAt?: string | null;
    body?: string | null;
    files?: SchoolFile[] | null;
    /** The school's own label ("정상제출" …), shown as is. */
    statusLabel?: string | null;
  } | null;
}

export interface SubmissionListItem {
  submissionId: number;
  studentName?: string | null;
  status: string;
  late?: boolean;
  versionCount?: number;
  submittedAt?: string | null;
  score?: number | null;
}

export interface SubmissionVersion {
  version: number;
  body?: string | null;
  files?: SubmissionFile[];
  submittedAt?: string | null;
  late?: boolean;
}

/** `GET …/submissions/{id}` for the instructor. */
export interface SubmissionDetail {
  submissionId?: number;
  studentName?: string | null;
  status?: string;
  phase?: string;
  versions?: SubmissionVersion[];
  grade?: SubmissionGrade | null;
}

/** The server receipt of a final submission. */
export interface SubmissionReceipt {
  version: number;
  submittedAt: string;
  late?: boolean;
  files?: SubmissionFile[];
}

/** The full create/update body (`assignmentRequest`, or the instructor form). */
export interface AssignmentPayload {
  weekNo: number;
  title: string;
  description: string | null;
  openAt: string | null;
  closeAt: string;
  published: boolean;
  latePolicy: string;
  lateUntil: string | null;
  allowText: boolean;
  allowFile: boolean;
  maxScore: number;
  scoresReleased?: boolean;
  revision?: number;
}

/** Activity ids (`?n=`) of backend assignments are `srv-<assignmentId>`; the school's are their own `school-<n>`. */
export const SERVER_PREFIX = "srv-";
/** An imported school assignment id. */
export const SCHOOL_ASSIGNMENT_ID = /^school-[1-9]\d{0,17}$/;

/** The assignment id in a `srv-<id>` reference, or null. */
export function assignmentIdOf(ref: unknown): number | null {
  const match = /^srv-([1-9]\d{0,15})$/.exec(String(ref || ""));
  return match ? Number(match[1]) : null;
}

export const serverRef = (assignmentId: Id): string => (SCHOOL_ASSIGNMENT_ID.test(String(assignmentId)) ? String(assignmentId) : `${SERVER_PREFIX}${assignmentId}`);

/** Student detail address of a backend assignment (`n=srv-<id>`) or an imported school assignment (`n=school-<n>`) in course `kj`. */
export function studentAssignmentHref(kj: Id, assignmentId: Id, extra: Record<string, unknown> = {}): string {
  const params = new URLSearchParams({ m: "report", n: serverRef(assignmentId), kj: String(kj) });
  for (const [key, value] of Object.entries(extra)) if (value != null && value !== "") params.set(key, String(value));
  return `/ilos/cls/st/activity/activity_form.acl?${params}`;
}

const base = (courseId: Id): string => `/api/courses/${encodeURIComponent(courseId)}/assignments`;
const one = (courseId: Id, assignmentId: Id): string => `${base(courseId)}/${encodeURIComponent(assignmentId)}`;

export const listKey = (courseId: Id): string => String(courseId);
export const assignmentKey = (courseId: Id, assignmentId: Id): string => `${courseId}:${assignmentId}`;
export const submissionKey = (courseId: Id, assignmentId: Id, submissionId: Id): string => `${courseId}:${assignmentId}:${submissionId}`;

/** `GET …/assignments`: a student sees opened assignments with their own `status`; the instructor, counts. */
export const assignmentLists = createResource<AssignmentListItem[], [courseId: Id]>((courseId) => apiGet<AssignmentListItem[]>(base(courseId)));
/** `GET …/assignments/{id}`: `{ assignment, phase, serverTime, submission }` or, for the instructor, `{ assignment, revision, … }`. */
export const assignmentDetails = createResource<AssignmentDetail, [courseId: Id, assignmentId: Id]>((courseId, assignmentId) => apiGet<AssignmentDetail>(one(courseId, assignmentId)));
/** The school detail of one imported assignment. Loaded only by the assignment detail screen (one read per visit), never by a list. */
export const schoolAssignmentDetails = createResource<SchoolAssignmentDetail, [courseId: Id, assignmentId: string]>((courseId, assignmentId) => apiGet<SchoolAssignmentDetail>(one(courseId, assignmentId)));
export const submissionLists = createResource<SubmissionListItem[], [courseId: Id, assignmentId: Id]>((courseId, assignmentId) => apiGet<SubmissionListItem[]>(`${one(courseId, assignmentId)}/submissions`));
export const submissionDetails = createResource<SubmissionDetail, [courseId: Id, assignmentId: Id, submissionId: Id]>((courseId, assignmentId, submissionId) => (
  apiGet<SubmissionDetail>(`${one(courseId, assignmentId)}/submissions/${encodeURIComponent(submissionId)}`)
));

/** Drops every cached copy of one assignment (and the course list) after a write. */
export function forgetAssignment(courseId: Id, assignmentId: Id): void {
  const scope = assignmentKey(courseId, assignmentId);
  assignmentLists.drop((key) => key === listKey(courseId));
  assignmentDetails.drop((key) => key === scope);
  submissionLists.drop((key) => key === scope);
  submissionDetails.drop((key) => key.startsWith(`${scope}:`));
}

// ---------- student ----------

/** Saves the draft text (not a submission). Blank text clears the draft. */
export async function saveDraft(courseId: Id, assignmentId: Id, text: unknown): Promise<SubmissionDraft> {
  const body = typeof text === "string" && text.trim() ? text : null;
  const draft = await apiSend<SubmissionDraft>("PUT", `${one(courseId, assignmentId)}/submission/draft`, { body });
  assignmentDetails.patch((key) => key === assignmentKey(courseId, assignmentId), (data) => {
    if (data && data.submission) data.submission.draft = draft;
  });
  return draft;
}

// The request may have reached the server: a retry of the same content keeps its key.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingSubmit: { scope: string; content: string; expectedVersion: number; key: string } | null = null;
let pendingCreate: { scope: string; content: string; key: string } | null = null;
subscribe(() => {
  pendingSubmit = null;
  pendingCreate = null;
});

/** True when the last submit of this assignment ended without a definite answer. */
export function submitUncertain(courseId: Id, assignmentId: Id): boolean {
  return Boolean(pendingSubmit && pendingSubmit.scope === assignmentKey(courseId, assignmentId));
}

/**
 * Final submission with a fresh `Idempotency-Key` per attempt. When the previous attempt with the
 * same text, attachments and kept files ended without an answer (network error, 502–504), the retry
 * sends the same key and `expectedVersion`, so the server answers with the receipt it may already
 * have made instead of a second version. `keepFileIds` are `fileId`s of the student's own current
 * version carried into the new one (uploads plus kept files: 5 at most; any other id is 409
 * `attachment_not_ready`); leave it out for a text-only assignment. Resolves with the server receipt.
 */
export async function submitAssignment(
  courseId: Id,
  assignmentId: Id,
  { expectedVersion, body, uploadIds, keepFileIds }: { expectedVersion: unknown; body: unknown; uploadIds?: readonly (number | string)[] | null; keepFileIds?: readonly (number | string)[] | null },
): Promise<SubmissionReceipt> {
  const scope = assignmentKey(courseId, assignmentId);
  const text = typeof body === "string" && body.trim() ? body : null;
  const ids = Array.isArray(uploadIds) ? uploadIds : [];
  const kept = Array.isArray(keepFileIds) ? keepFileIds : null;
  const content = JSON.stringify([text, ids, kept]);
  const attempt = pendingSubmit && pendingSubmit.scope === scope && pendingSubmit.content === content
    ? pendingSubmit
    : { scope, content, expectedVersion: Number(expectedVersion) || 0, key: newRequestKey() };
  pendingSubmit = attempt;
  const payload: { expectedVersion: number; body: string | null; uploadIds: readonly (number | string)[]; keepFileIds?: readonly (number | string)[] } = { expectedVersion: attempt.expectedVersion, body: text, uploadIds: ids };
  if (kept) payload.keepFileIds = kept;
  try {
    const receipt = await apiSend<SubmissionReceipt>("POST", `${one(courseId, assignmentId)}/submission`, payload, { idempotencyKey: attempt.key });
    pendingSubmit = null;
    forgetAssignment(courseId, assignmentId);
    return receipt;
  } catch (error) {
    if (!uncertain(error)) pendingSubmit = null;
    throw error;
  }
}

// ---------- instructor ----------

/** The full request body an update needs, from an instructor view (`assignment` + `revision`). */
export function assignmentRequest(info: AssignmentInfo, revision: unknown, changes: Partial<AssignmentPayload> = {}): AssignmentPayload {
  return {
    weekNo: info.weekNo,
    title: info.title,
    description: info.description || null,
    openAt: info.openAt || null,
    closeAt: info.closeAt,
    published: Boolean(info.published),
    latePolicy: info.latePolicy || "none",
    lateUntil: info.latePolicy === "allow_until" ? info.lateUntil ?? null : null,
    allowText: Boolean(info.allowText),
    allowFile: Boolean(info.allowFile),
    maxScore: Number(info.maxScore),
    scoresReleased: Boolean(info.scoresReleased),
    revision: Number(revision),
    ...changes,
  };
}

/**
 * 과제 등록(201). 저장을 누를 때마다 새 `Idempotency-Key`이고, 응답 없이 끝난(네트워크 오류, 502–504) 같은 내용의
 * 재시도는 같은 key를 보내 서버가 이미 만든 과제를 돌려준다. 처음 요청이 아직 처리 중이면(409 `idempotency_in_progress`)
 * key를 그대로 두어 다음 저장도 같은 과제를 가리킨다. 같은 key에 다른 내용은 409 `idempotency_conflict`다.
 */
export async function createAssignment(courseId: Id, payload: AssignmentPayload): Promise<unknown> {
  const scope = String(courseId);
  const content = JSON.stringify(payload);
  const attempt = pendingCreate && pendingCreate.scope === scope && pendingCreate.content === content
    ? pendingCreate
    : { scope, content, key: newRequestKey() };
  pendingCreate = attempt;
  try {
    const created = await apiSend("POST", base(courseId), payload, { idempotencyKey: attempt.key });
    pendingCreate = null;
    assignmentLists.drop((key) => key === listKey(courseId));
    return created;
  } catch (error) {
    if (!uncertain(error) && !isConflict(error, "idempotency_in_progress")) pendingCreate = null;
    throw error;
  }
}

/** Full update; `payload.revision` is the revision the form was loaded with (409 `edit_conflict`). */
export async function updateAssignment(courseId: Id, assignmentId: Id, payload: AssignmentPayload): Promise<unknown> {
  const updated = await apiSend("PUT", one(courseId, assignmentId), payload);
  forgetAssignment(courseId, assignmentId);
  return updated;
}

export async function gradeSubmission(courseId: Id, assignmentId: Id, submissionId: Id, { score, feedback }: { score: number; feedback: string | null }): Promise<SubmissionGrade> {
  const grade = await apiSend<SubmissionGrade>("PUT", `${one(courseId, assignmentId)}/submissions/${encodeURIComponent(submissionId)}/grade`, { score, feedback });
  submissionDetails.patch((key) => key === submissionKey(courseId, assignmentId, submissionId), (data) => { data.grade = grade; });
  submissionLists.drop((key) => key === assignmentKey(courseId, assignmentId));
  return grade;
}

// ---------- text ----------

export const PHASE: Record<string, string> = { not_open: "시작 전", open: "제출 기간", late: "지각 제출 기간", closed: "마감" };
export const STATUS: Record<string, string> = { not_submitted: "미제출", submitted: "제출함", late: "지각 제출" };

export const accepting = (phase: unknown): boolean => phase === "open" || phase === "late";

export function isConflict(error: unknown, code: string): error is ApiError {
  return error instanceof ApiError && error.status === 409 && error.code === code;
}

/** "20" or "17.5" for a score the server sent as a decimal number. */
export function scoreText(value: unknown): string {
  const n = Number(value);
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : "";
}

/** "11월 12일 (목) 오후 11:59:07" for a server receipt time (seconds matter near a deadline). */
export function receiptTime(value: unknown): string {
  const base = koDateTime(value);
  const match = /T\d{2}:\d{2}:(\d{2})/.exec(String(value || ""));
  return base && match ? `${base}:${match[1]}` : base;
}

/** "11월 3일 (화) 오전 9:00 ~ 11월 12일 (목) 오후 11:59" (start omitted when the server has none). */
export function periodText(item: { openAt?: string | null; closeAt?: string | null }): string {
  const close = koDateTime(item.closeAt);
  return item.openAt ? `${koDateTime(item.openAt)} ~ ${close}` : `~ ${close}`;
}

/**
 * Korean message for a failed assignment, submission or grading request. `creating` picks the
 * 과제 등록 wording for the two `Idempotency-Key` refusals (the default wording is the student's 제출).
 */
export function assignmentMessage(error: unknown, options?: { creating?: boolean }): string {
  const creating = Boolean(options && options.creating);
  if (!(error instanceof ApiError)) {
    const message = error !== null && typeof error === "object" && "message" in error ? error.message : null;
    return message ? String(message) : "과제 요청을 처리하지 못했습니다.";
  }
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "과제를 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409:
      switch (error.code) {
        case "version_conflict": return "다른 탭이나 기기에서 먼저 제출했습니다. 최신 제출 상태를 불러왔습니다. 입력한 글과 첨부는 그대로 두었으니 확인한 뒤 다시 제출하세요.";
        case "deadline_passed": return "제출 기간이 아니어서 처리하지 못했습니다. 입력한 글은 그대로 두었습니다.";
        case "attachment_not_ready": return "제출할 수 없는 첨부가 있어 아무것도 제출하지 않았습니다. 검사가 끝나지 않은 파일이 있거나, 이어서 내려던 파일이 지금 제출본에 없습니다. 최신 제출 상태를 불러왔으니 첨부를 확인한 뒤 다시 제출하세요.";
        case "idempotency_conflict": return creating
          ? "같은 등록 요청을 다른 내용으로 보냈습니다. 내용을 확인한 뒤 다시 저장하세요."
          : "같은 제출 요청을 다른 내용으로 보냈습니다. 내용을 확인한 뒤 다시 제출하세요.";
        case "idempotency_in_progress": return creating
          ? "앞서 보낸 과제 등록을 처리하고 있습니다. 잠시 후 과제 목록을 확인하세요."
          : "같은 제출을 처리하고 있습니다. 잠시 후 제출 상태를 확인하세요.";
        case "edit_conflict": return "다른 곳에서 이 과제를 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
        case "score_exceeds_max": return "이미 준 점수보다 배점을 낮출 수 없습니다.";
        case "school_submit_at_school": return SCHOOL_PROBLEM_TEXT.at_school;
        default: return error.detail || "같은 요청이 이미 처리되었습니다. 화면을 새로 불러오세요.";
      }
    case 429: return retryLater(error);
    case 0: return error.message;
    case 502:
    case 503:
    case 504: return "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "과제 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/**
 * 과제 삭제(ADR-0037, 활동의 삭제 표시). `If-Match`에 마지막으로 읽은 상세의 `revision`을 보낸다. 없으면 428, 다르면
 * 409 `edit_conflict`다. 제출물과 점수는 서버에 남고 그 commit부터 누구에게도 보이지 않는다. 204면 목록 캐시에서 그 과제를
 * 빼고 상세·제출 캐시와 성적부를 버린다. 실패하면 그 과제의 캐시를 모두 버린다.
 */
export async function deleteAssignment(courseId: Id, assignmentId: Id, revision: number | string | null | undefined): Promise<void> {
  try {
    await apiSend("DELETE", one(courseId, assignmentId), undefined, { ifMatch: revision });
  } catch (error) {
    forgetAssignment(courseId, assignmentId);
    throw error;
  }
  const scope = assignmentKey(courseId, assignmentId);
  assignmentLists.patch((key) => key === listKey(courseId), (items) => {
    if (!Array.isArray(items)) return;
    const at = items.findIndex((item) => item.assignmentId === assignmentId);
    if (at >= 0) items.splice(at, 1);
  });
  assignmentDetails.drop((key) => key === scope);
  submissionLists.drop((key) => key === scope);
  submissionDetails.drop((key) => key.startsWith(`${scope}:`));
  forgetGradebook(courseId);
}

/**
 * 점수 공개(`released: true`)와 거두기. 과제 전체를 다시 보내지 않고 `{ released, revision }`만 보낸다. 읽은 `revision`이
 * 다르면 409 `edit_conflict`다. 응답은 담당자 상세(새 `revision` 포함)이고, 그것으로 상세 캐시를 바꿔 다음 수정이 새 번호를
 * 쓰게 한다. 목록·제출·성적부 캐시는 버린다.
 */
export async function releaseScores(courseId: Id, assignmentId: Id, released: unknown, revision: unknown): Promise<AssignmentDetail | null> {
  const updated = await apiSend<AssignmentDetail | null>("PUT", `${one(courseId, assignmentId)}/release`, { released: Boolean(released), revision: Number(revision) });
  const scope = assignmentKey(courseId, assignmentId);
  if (updated && updated.revision != null) {
    assignmentDetails.patch((key) => key === scope, (data) => {
      for (const name of Object.keys(data)) Reflect.deleteProperty(data, name);
      Object.assign(data, updated);
    });
  }
  assignmentLists.drop((key) => key === listKey(courseId));
  submissionLists.drop((key) => key === scope);
  submissionDetails.drop((key) => key.startsWith(`${scope}:`));
  forgetGradebook(courseId);
  return updated;
}
