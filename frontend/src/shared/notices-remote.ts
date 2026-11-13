// Course notices on the ASTRA backend (`/api/courses/{courseId}/notices`). Reading a
// notice never marks it read: the detail view sends `read` once when it is opened and
// `confirm` only from the confirm button. Lists and details stay in memory only.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";

export const NOTICE_PAGE = 8;

type Id = string | number;

/** One notice as a list row or detail. */
export interface CourseNotice {
  noticeId: number;
  courseId?: number;
  courseName?: string;
  title: string;
  body?: string;
  excerpt?: string;
  authorName?: string;
  pinned?: boolean;
  published?: boolean;
  publishedAt?: string | null;
  updatedAt?: string;
  read?: boolean;
  confirmed?: boolean;
  viewCount?: number;
  readCount?: number;
  confirmCount?: number;
  version?: number;
}

/** One page of `GET …/notices`. */
export interface NoticePage {
  items: CourseNotice[];
  page?: number;
  size?: number;
  total?: number;
}

/** `POST …/read` and `…/confirm` result. */
export interface NoticeReadState {
  noticeId: number;
  read: boolean;
  confirmed: boolean;
}

/** Create/update body; `version` only on update. */
export interface NoticePayload {
  title: string;
  body: string;
  pinned: boolean;
  published: boolean;
  publishAt: string | null;
  version?: number;
}

const base = (courseId: Id): string => `/api/courses/${encodeURIComponent(courseId)}/notices`;
const one = (courseId: Id, noticeId: Id): string => `${base(courseId)}/${encodeURIComponent(noticeId)}`;

export const listKey = (courseId: Id, q: string, page: number, size: number = NOTICE_PAGE): string => `${courseId}|${size}|${page}|${q}`;
export const noticeKey = (courseId: Id, noticeId: Id): string => `${courseId}:${noticeId}`;

export const noticeLists = createResource<NoticePage, [courseId: Id, q: string, page: number, size?: number]>((courseId, q, page, size = NOTICE_PAGE) => {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) params.set("q", q);
  return apiGet<NoticePage>(`${base(courseId)}?${params}`);
});

export const noticeDetails = createResource<CourseNotice, [courseId: Id, noticeId: Id]>((courseId, noticeId) => apiGet<CourseNotice>(one(courseId, noticeId)));

const inCourse = (courseId: Id) => (key: string): boolean => key.startsWith(`${courseId}|`) || key.startsWith(`${courseId}:`);

/** Applies a read/confirm result to every cached copy of that notice. */
function remember(courseId: Id, state: NoticeReadState): void {
  const apply = (item: CourseNotice | null | undefined): void => {
    if (item && item.noticeId === state.noticeId) {
      item.read = state.read;
      item.confirmed = state.confirmed;
    }
  };
  noticeLists.patch(inCourse(courseId), (page) => (page.items || []).forEach(apply));
  noticeDetails.patch(inCourse(courseId), apply);
}

const readSent = new Set<string>(); // noticeKey, per session: `read` is sent once per opened notice
subscribe(() => readSent.clear());

/** Marks a notice read when its detail is opened. Sends at most once per session. */
export async function markNoticeRead(courseId: Id, noticeId: Id): Promise<NoticeReadState | null> {
  const key = noticeKey(courseId, noticeId);
  if (readSent.has(key)) return null;
  readSent.add(key);
  try {
    const state = await apiSend<NoticeReadState>("POST", `${one(courseId, noticeId)}/read`);
    remember(courseId, state);
    return state;
  } catch (error) {
    readSent.delete(key);
    throw error;
  }
}

export async function confirmNotice(courseId: Id, noticeId: Id): Promise<NoticeReadState> {
  const state = await apiSend<NoticeReadState>("POST", `${one(courseId, noticeId)}/confirm`);
  remember(courseId, state);
  return state;
}

// The request may have reached the server: a retry of the same submit keeps its key.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingCreate: { fingerprint: string; key: string } | null = null;

/**
 * Creates a notice with a fresh Idempotency-Key per submit. When the previous attempt with
 * the same course and content ended without a definite answer, its key is reused so the
 * server returns the notice it may already have made instead of a duplicate.
 */
export async function createNotice(courseId: Id, payload: NoticePayload): Promise<CourseNotice> {
  const fingerprint = JSON.stringify([courseId, payload]);
  const key = pendingCreate && pendingCreate.fingerprint === fingerprint ? pendingCreate.key : newRequestKey();
  pendingCreate = { fingerprint, key };
  try {
    const notice = await apiSend<CourseNotice>("POST", base(courseId), payload, { idempotencyKey: key });
    pendingCreate = null;
    forgetCourse(courseId);
    return notice;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

/** Full update; `payload.version` is the version the form was loaded with (409 `version_conflict`). */
export async function updateNotice(courseId: Id, noticeId: Id, payload: NoticePayload): Promise<CourseNotice> {
  const notice = await apiSend<CourseNotice>("PUT", one(courseId, noticeId), payload);
  forgetCourse(courseId);
  return notice;
}

export async function deleteNotice(courseId: Id, noticeId: Id): Promise<void> {
  try {
    await apiSend("DELETE", one(courseId, noticeId));
  } finally {
    forgetCourse(courseId);
  }
}

/** Drops this course's cached lists and details after a write. */
export function forgetCourse(courseId: Id): void {
  noticeLists.drop(inCourse(courseId));
  noticeDetails.drop(inCourse(courseId));
}

export function isVersionConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === "version_conflict";
}

/** Korean message for a failed notice request. */
export function noticeMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "공지 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "공지를 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409: return error.code === "version_conflict"
      ? "다른 곳에서 이 공지를 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 글은 그대로 두었으니 확인한 뒤 다시 저장하세요."
      : error.detail || "같은 요청이 이미 처리되었습니다. 목록을 새로 불러오세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "공지 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/** "고정", "임시", "예약" labels for a notice row or detail. */
export function noticeFlags(notice: Pick<CourseNotice, "pinned" | "publishedAt" | "published">): string[] {
  const flags: string[] = [];
  if (notice.pinned) flags.push("고정");
  if (!notice.publishedAt) flags.push("임시");
  else if (!notice.published) flags.push("예약");
  return flags;
}
