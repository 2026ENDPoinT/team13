// Course Q&A (`/api/courses/{courseId}/qna`) and open board (`…/open-board/posts`) on the ASTRA
// backend. The server decides who sees what: a private (`secret`) question never reaches other
// students, an anonymous author's name is sent only to the author and the instructors, and
// `canEdit`/`canDelete`/`canSetSolved`/`canSetFaq` say which commands the viewer has. Viewing a
// post never marks it read; the detail view sends `read` once. Everything stays in memory.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";
import type { Resource } from "./remote-cache.ts";

export type Id = number | string;
export type BoardKind = "qna" | "open";

export interface BoardAttachment {
  fileId: number | string;
  name: string;
  sizeBytes?: number;
}

/** A Q&A question or open-board post, as a list row or a detail (`body`, `attachments`, `can*` on the detail). */
export interface BoardPost {
  postId: number;
  title: string;
  body?: string;
  excerpt?: string | null;
  authorName?: string | null;
  anonymous?: boolean;
  secret?: boolean;
  mine?: boolean;
  createdAt?: string | null;
  edited?: boolean;
  read?: boolean;
  viewCount?: number;
  likeCount?: number;
  liked?: boolean;
  answerCount?: number;
  commentCount?: number;
  solved?: boolean;
  faq?: boolean;
  attachments?: BoardAttachment[];
  canEdit?: boolean;
  canDelete?: boolean;
  canSetSolved?: boolean;
  canSetFaq?: boolean;
  version?: number;
}

export interface BoardAnswer {
  answerId: number;
  body: string;
  authorName?: string | null;
  anonymous?: boolean;
  instructor?: boolean;
  createdAt?: string | null;
  edited?: boolean;
  attachments?: BoardAttachment[];
  canEdit?: boolean;
  canDelete?: boolean;
  version: number;
}

export interface BoardComment {
  commentId: number;
  parentCommentId?: number | null;
  body: string;
  authorName?: string | null;
  mine?: boolean;
  deleted?: boolean;
  edited?: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
  canEdit?: boolean;
  canDelete?: boolean;
  version: number;
}

/** Filter of the Q&A list screen. */
export interface QnaFilter {
  status: string;
  mine: boolean;
  answered: boolean;
  q: string;
}

/** One page of `GET …/qna`. */
export interface QnaPage {
  items?: BoardPost[];
  total?: number;
  page?: number;
  size?: number;
  snapshot?: string | number | null;
  counts?: Record<string, number> | null;
}

/** A cursor list with every loaded page appended (`pages` = how many). */
export interface CursorList<T> {
  items: T[];
  nextCursor: string | null;
  total?: number;
  pages: number;
}

/** `{ keep: [fileId], add: [uploadId] }`: the final file set of an update. */
export interface AttachmentChange {
  keep: (number | string)[];
  add: (number | string)[];
}

export interface QuestionPayload {
  title: string;
  body: string;
  anonymous?: boolean;
  secret?: boolean;
  uploadIds?: (number | string)[];
}

export interface OpenPostPayload {
  title: string;
  body: string;
  uploadIds?: (number | string)[];
}

export interface PostUpdatePayload {
  title: string;
  body: string;
  anonymous?: boolean;
  secret?: boolean;
  version?: number;
  attachments?: AttachmentChange;
}

export interface AnswerPayload {
  body: string;
  anonymous: boolean;
  uploadIds?: (number | string)[];
  version?: number;
  attachments?: AttachmentChange;
}

export interface CommentPayload {
  body: string;
  parentCommentId?: number | null;
  version?: number;
}

export interface LikeState {
  liked: boolean;
  likeCount: number;
}

export interface ReadState {
  read: boolean;
  viewCount: number;
}

export interface QuestionStatus {
  solved: boolean;
  faq: boolean;
}

export const QNA_PAGE = 8;
export const OPEN_PAGE = 10;
export const ANSWER_PAGE = 20;
export const COMMENT_PAGE = 50;
export const MAX_UPLOADS = 5;
export const MAX_TITLE = 300;
export const MAX_BODY = 20000;
/** Comments are limited in legacy bytes: a character above U+007F counts 2. */
export const COMMENT_BYTES = 1800;
export const QNA_STATUS: readonly string[] = ["all", "faq", "solved", "unsolved"];

const course = (courseId: Id): string => `/api/courses/${encodeURIComponent(courseId)}`;
const qnaBase = (courseId: Id): string => `${course(courseId)}/qna`;
const question = (courseId: Id, id: Id): string => `${qnaBase(courseId)}/${encodeURIComponent(id)}`;
const openBase = (courseId: Id): string => `${course(courseId)}/open-board/posts`;
const openPost = (courseId: Id, id: Id): string => `${openBase(courseId)}/${encodeURIComponent(id)}`;
const postPath = (kind: BoardKind, courseId: Id, id: Id): string => (kind === "qna" ? question(courseId, id) : openPost(courseId, id));

// ---------- keys ----------

/** `f`: `{ status, mine, answered, q }` of the Q&A list screen. */
export const qnaFilterKey = (courseId: Id, f: QnaFilter): string => `${courseId}|qna|${f.status}|${f.mine ? 1 : 0}|${f.answered ? 1 : 0}|${f.q}`;
export const qnaListKey = (courseId: Id, f: QnaFilter, page: number): string => `${qnaFilterKey(courseId, f)}|${page}`;
export const openListKey = (courseId: Id, q: string): string => `${courseId}|open|${q}`;
/** Detail, answers and comments of one post; `kind` is "qna" or "open". */
export const postKey = (courseId: Id, kind: BoardKind, id: Id): string => `${courseId}:${kind}:${id}`;

const inCourse = (courseId: Id) => (key: string): boolean => key.startsWith(`${courseId}|`) || key.startsWith(`${courseId}:`);
const listsOf = (courseId: Id, kind: BoardKind) => (key: string): boolean => key.startsWith(`${courseId}|${kind}|`);

// ---------- Q&A list: page numbers kept stable by `snapshot` ----------

// qnaFilterKey -> snapshot of the first page read with that filter. A server value: dropped when the session changes.
const snapshots = new Map<string, string | number | null | undefined>();
subscribe(() => snapshots.clear());

export const qnaLists = createResource<QnaPage, [courseId: Id, f: QnaFilter, page: number]>(async (courseId, f, page) => {
  const params = new URLSearchParams({ page: String(page), size: String(QNA_PAGE) });
  if (f.status && f.status !== "all") params.set("status", f.status);
  if (f.mine) params.set("mine", "true");
  if (f.answered) params.set("answered", "true");
  if (f.q) params.set("q", f.q);
  const filter = qnaFilterKey(courseId, f);
  // Page 1 starts a new reading; later pages send its snapshot so new questions do not shift them.
  if (page > 1 && snapshots.has(filter)) params.set("snapshot", String(snapshots.get(filter)));
  const data = await apiGet<QnaPage>(`${qnaBase(courseId)}?${params}`);
  if (data && (page === 1 || !snapshots.has(filter))) snapshots.set(filter, data.snapshot);
  return data;
});

export const qnaDetails = createResource<BoardPost, [courseId: Id, id: Id]>((courseId, id) => apiGet<BoardPost>(question(courseId, id)));

// ---------- cursor lists: open board posts, answers, comments ----------

/** A cursor page as the server sends it. */
interface CursorPage<T> {
  items?: T[];
  nextCursor?: string | null;
  total?: number;
}

function firstPage<T>(page: CursorPage<T> | null): CursorList<T> {
  return { ...page, items: page && Array.isArray(page.items) ? page.items : [], nextCursor: page && page.nextCursor != null ? page.nextCursor : null, pages: 1 };
}

const openListUrl = (courseId: Id, q: string): string => `${openBase(courseId)}?size=${OPEN_PAGE}${q ? `&q=${encodeURIComponent(q)}` : ""}`;
const answersUrl = (courseId: Id, id: Id): string => `${question(courseId, id)}/answers?size=${ANSWER_PAGE}`;
const commentsUrl = (courseId: Id, id: Id): string => `${openPost(courseId, id)}/comments?size=${COMMENT_PAGE}`;

export const openLists = createResource<CursorList<BoardPost>, [courseId: Id, q: string]>(async (courseId, q) => firstPage(await apiGet<CursorPage<BoardPost> | null>(openListUrl(courseId, q))));
export const openDetails = createResource<BoardPost, [courseId: Id, id: Id]>((courseId, id) => apiGet<BoardPost>(openPost(courseId, id)));
export const answerLists = createResource<CursorList<BoardAnswer>, [courseId: Id, id: Id]>(async (courseId, id) => firstPage(await apiGet<CursorPage<BoardAnswer> | null>(answersUrl(courseId, id))));
export const commentLists = createResource<CursorList<BoardComment>, [courseId: Id, id: Id]>(async (courseId, id) => firstPage(await apiGet<CursorPage<BoardComment> | null>(commentsUrl(courseId, id))));

type Keyed = { postId?: number; answerId?: number; commentId?: number };
const idOf = (item: Keyed): number | undefined => item.postId ?? item.answerId ?? item.commentId;
const appending = new Set<string>();

async function appendNext<T extends Keyed>(resource: Pick<Resource<CursorList<T>, never[]>, "peek" | "patch">, key: string, url: string): Promise<boolean> {
  const entry = resource.peek(key);
  const data = entry && entry.data;
  if (!data || data.nextCursor == null || appending.has(key)) return false;
  appending.add(key);
  try {
    const page = await apiGet<CursorPage<T>>(`${url}&cursor=${encodeURIComponent(data.nextCursor)}`);
    resource.patch((k) => k === key, (current) => {
      const seen = new Set(current.items.map(idOf));
      current.items.push(...(Array.isArray(page.items) ? page.items : []).filter((item) => !seen.has(idOf(item))));
      current.nextCursor = page.nextCursor != null ? page.nextCursor : null;
      if (page.total != null) current.total = page.total;
      current.pages += 1;
    });
    return true;
  } finally {
    appending.delete(key);
  }
}

export const isAppending = (key: string): boolean => appending.has(key);
export const moreOpenPosts = (courseId: Id, q: string): Promise<boolean> => appendNext(openLists, openListKey(courseId, q), openListUrl(courseId, q));
export const moreAnswers = (courseId: Id, id: Id): Promise<boolean> => appendNext(answerLists, postKey(courseId, "qna", id), answersUrl(courseId, id));
export const moreComments = (courseId: Id, id: Id): Promise<boolean> => appendNext(commentLists, postKey(courseId, "open", id), commentsUrl(courseId, id));

// ---------- cache updates ----------

const detailsOf = (kind: BoardKind): Resource<BoardPost, [courseId: Id, id: Id]> => (kind === "qna" ? qnaDetails : openDetails);
// Both list caches hold `items` of posts; only patch/drop are used here.
const listOf = (kind: BoardKind): Pick<Resource<{ items?: BoardPost[] }, never[]>, "patch" | "drop"> => (kind === "qna" ? qnaLists : openLists);

/** Applies `change(item)` to the post in its detail and every cached list page of the course. */
function patchPost(courseId: Id, kind: BoardKind, id: Id, change: (item: BoardPost) => void): void {
  detailsOf(kind).patch((key) => key === postKey(courseId, kind, id), change);
  listOf(kind).patch(listsOf(courseId, kind), (page) => (page.items || []).forEach((item) => { if (item.postId === id) change(item); }));
}

/** Forgets the course's lists of `kind` (counts or order changed) so the next visit reads them. */
export function forgetLists(courseId: Id, kind: BoardKind): void {
  listOf(kind).drop(listsOf(courseId, kind));
  if (kind === "qna") for (const key of [...snapshots.keys()]) if (key.startsWith(`${courseId}|qna|`)) snapshots.delete(key);
}

/** Forgets everything cached for one post (after a delete). */
function forgetPost(courseId: Id, kind: BoardKind, id: Id): void {
  const key = postKey(courseId, kind, id);
  detailsOf(kind).drop((k) => k === key);
  (kind === "qna" ? answerLists : commentLists).drop((k) => k === key);
  forgetLists(courseId, kind);
}

// ---------- commands ----------

// The request may have reached the server: a retry of the same content keeps its key.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pending = new Map<string, { fingerprint: string; key: string }>();
subscribe(() => pending.clear());

/**
 * Sends a create with a fresh Idempotency-Key per attempt. When the previous attempt of the same
 * operation and content ended without a definite answer, its key is reused so the server returns
 * what it may already have made instead of a duplicate.
 */
async function createOnce<T>(operation: string, fingerprint: unknown, send: (key: string) => Promise<T>): Promise<T> {
  const print = JSON.stringify(fingerprint);
  const last = pending.get(operation);
  const key = last && last.fingerprint === print ? last.key : newRequestKey();
  pending.set(operation, { fingerprint: print, key });
  try {
    const result = await send(key);
    pending.delete(operation);
    return result;
  } catch (error) {
    if (!uncertain(error)) pending.delete(operation);
    throw error;
  }
}

/** True when the last create of `operation` may have reached the server (retry reuses its key). */
export function createUncertain(operation: string): boolean {
  return pending.has(operation);
}

/** `{ title, body, anonymous, secret, uploadIds }`. 201 with the question. */
export async function createQuestion(courseId: Id, payload: QuestionPayload): Promise<BoardPost> {
  const question = await createOnce(`qna:${courseId}`, [courseId, payload], (key) => apiSend<BoardPost>("POST", qnaBase(courseId), payload, { idempotencyKey: key }));
  forgetLists(courseId, "qna");
  return question;
}

/** `{ title, body }` for the open board. 201 with the post. */
export async function createOpenPost(courseId: Id, payload: OpenPostPayload): Promise<BoardPost> {
  const post = await createOnce(`open:${courseId}`, [courseId, payload], (key) => apiSend<BoardPost>("POST", openBase(courseId), payload, { idempotencyKey: key }));
  forgetLists(courseId, "open");
  return post;
}

/**
 * Full update. The server replaces title, body and (Q&A only) `anonymous`/`secret`, so the
 * payload carries all of them with the `version` the form was loaded with. `attachments`
 * (`{ keep: [fileId], add: [uploadId] }`) is the final file set; leaving it out keeps the files.
 * The version is checked first: 409 `version_conflict` (stale) or `already_answered` (a question
 * that got an answer) changes nothing; 409 `attachment_not_ready` is a file or upload that cannot
 * be attached. The response carries the new `attachments`, so removed files leave the detail at once.
 */
export async function updatePost(kind: BoardKind, courseId: Id, id: Id, payload: PostUpdatePayload): Promise<BoardPost> {
  const post = await apiSend<BoardPost>("PUT", postPath(kind, courseId, id), payload);
  detailsOf(kind).patch((key) => key === postKey(courseId, kind, id), (current) => { Object.assign(current, post); });
  forgetLists(courseId, kind);
  return post;
}

export async function deletePost(kind: BoardKind, courseId: Id, id: Id): Promise<void> {
  try {
    await apiSend("DELETE", postPath(kind, courseId, id));
  } finally {
    forgetPost(courseId, kind, id);
  }
}

/** Target state: `liked` true sends PUT, false DELETE. Repeating is harmless; the count is the server's. */
export async function setLike(kind: BoardKind, courseId: Id, id: Id, liked: boolean): Promise<LikeState> {
  const state = await apiSend<LikeState>(liked ? "PUT" : "DELETE", `${postPath(kind, courseId, id)}/like`);
  patchPost(courseId, kind, id, (item) => {
    item.liked = state.liked;
    item.likeCount = state.likeCount;
  });
  return state;
}

const readSent = new Set<string>(); // postKey, per session
subscribe(() => readSent.clear());

/** Marks a post read when its detail is opened. Sends at most once per session. */
export async function markPostRead(kind: BoardKind, courseId: Id, id: Id): Promise<ReadState | null> {
  const key = postKey(courseId, kind, id);
  if (readSent.has(key)) return null;
  readSent.add(key);
  try {
    const state = await apiSend<ReadState>("POST", `${postPath(kind, courseId, id)}/read`);
    patchPost(courseId, kind, id, (item) => {
      item.read = state.read;
      item.viewCount = state.viewCount;
    });
    return state;
  } catch (error) {
    readSent.delete(key);
    throw error;
  }
}

/** `{ solved }` (asker or instructor) or `{ faq }` (instructor). Target state, no version. */
export async function setQuestionStatus(courseId: Id, id: Id, change: { solved: boolean } | { faq: boolean }): Promise<QuestionStatus> {
  const state = await apiSend<QuestionStatus>("PUT", `${question(courseId, id)}/status`, change);
  qnaDetails.patch((key) => key === postKey(courseId, "qna", id), (item) => {
    item.solved = state.solved;
    item.faq = state.faq;
  });
  forgetLists(courseId, "qna");
  return state;
}

/** `{ body, anonymous, uploadIds }`; only students may answer anonymously. 201. */
export async function createAnswer(courseId: Id, id: Id, payload: AnswerPayload): Promise<BoardAnswer> {
  const answer = await createOnce(`answer:${courseId}:${id}`, [courseId, id, payload],
    (key) => apiSend<BoardAnswer>("POST", `${question(courseId, id)}/answers`, payload, { idempotencyKey: key }));
  refreshThread(courseId, "qna", id);
  return answer;
}

/** `{ body, anonymous, version, attachments? }` (same `attachments` rule as `updatePost`). 409 `version_conflict` when stale. */
export async function updateAnswer(courseId: Id, id: Id, answerId: Id, payload: AnswerPayload): Promise<BoardAnswer> {
  const answer = await apiSend<BoardAnswer>("PUT", `${question(courseId, id)}/answers/${encodeURIComponent(answerId)}`, payload);
  answerLists.patch((key) => key === postKey(courseId, "qna", id), (page) => {
    const index = page.items.findIndex((item) => item.answerId === answerId);
    if (index !== -1) page.items[index] = answer;
  });
  return answer;
}

export async function deleteAnswer(courseId: Id, id: Id, answerId: Id): Promise<void> {
  try {
    await apiSend("DELETE", `${question(courseId, id)}/answers/${encodeURIComponent(answerId)}`);
  } finally {
    refreshThread(courseId, "qna", id);
  }
}

/** `{ body, parentCommentId }`; a reply answers a top-level comment only. 201. */
export async function createComment(courseId: Id, id: Id, payload: CommentPayload): Promise<BoardComment> {
  const comment = await createOnce(`comment:${courseId}:${id}`, [courseId, id, payload],
    (key) => apiSend<BoardComment>("POST", `${openPost(courseId, id)}/comments`, payload, { idempotencyKey: key }));
  refreshThread(courseId, "open", id);
  return comment;
}

export async function deleteComment(courseId: Id, id: Id, commentId: Id): Promise<void> {
  try {
    await apiSend("DELETE", `${openPost(courseId, id)}/comments/${encodeURIComponent(commentId)}`);
  } finally {
    refreshThread(courseId, "open", id);
  }
}

/** `{ body, version }`, author only (`canEdit`). Same 1800-byte rule as create; 409 `version_conflict` when stale. */
export async function updateComment(courseId: Id, id: Id, commentId: Id, payload: CommentPayload): Promise<BoardComment> {
  const comment = await apiSend<BoardComment>("PUT", `${openPost(courseId, id)}/comments/${encodeURIComponent(commentId)}`, payload);
  commentLists.patch((key) => key === postKey(courseId, "open", id), (page) => {
    const index = page.items.findIndex((item) => item.commentId === commentId);
    if (index !== -1) page.items[index] = comment;
  });
  return comment;
}

/** After an answer or comment changed: read the thread and the post (counts, `canEdit`) again. */
function refreshThread(courseId: Id, kind: BoardKind, id: Id): void {
  const key = postKey(courseId, kind, id);
  if (kind === "qna") {
    answerLists.load(key, courseId, id).catch(() => {});
    qnaDetails.load(key, courseId, id).catch(() => {});
  } else {
    commentLists.load(key, courseId, id).catch(() => {});
    openDetails.load(key, courseId, id).catch(() => {});
  }
  forgetLists(courseId, kind);
}

/** Drops everything of one course (e.g. after 404 on a post that was in a list). */
export function forgetCourseBoards(courseId: Id): void {
  const match = inCourse(courseId);
  const resources: Pick<Resource<unknown, never[]>, "drop">[] = [qnaLists, qnaDetails, openLists, openDetails, answerLists, commentLists];
  for (const resource of resources) resource.drop(match);
}

// ---------- errors ----------

export function isCode(error: unknown, status: number, code?: string): error is ApiError {
  return error instanceof ApiError && error.status === status && (!code || error.code === code);
}

/** Korean message for a failed board request. `noun` is "질문", "답글", "글", "댓글". */
export function boardMessage(error: unknown, noun = "글"): string {
  if (!(error instanceof ApiError)) {
    const message = error !== null && typeof error === "object" && "message" in error ? error.message : null;
    return message ? String(message) : `${noun} 요청을 처리하지 못했습니다.`;
  }
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return `${noun}을 찾을 수 없거나 볼 수 있는 권한이 없습니다.`;
    case 409:
      if (error.code === "version_conflict") return `다른 곳에서 이 ${noun}을 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 글은 그대로 두었으니 확인한 뒤 다시 저장하세요.`;
      if (error.code === "already_answered") return "답글이 달린 질문은 고치거나 지울 수 없습니다.";
      if (error.code === "attachment_not_ready") return "첨부할 수 없는 파일이 있습니다. 빠진 파일이나 검사가 끝나지 않은 파일을 확인한 뒤 다시 저장하세요.";
      if (error.code === "idempotency_in_progress") return "같은 요청을 처리하는 중입니다. 잠시 후 다시 시도하세요.";
      if (error.code === "idempotency_conflict") return "앞서 보낸 요청과 내용이 달라 처리하지 않았습니다. 다시 저장하세요.";
      return error.detail || "요청이 다른 변경과 겹쳤습니다. 새로 불러온 뒤 다시 시도하세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return `${noun} 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.`;
  }
}

/** Legacy byte length the server uses for comments (above U+007F counts 2). */
export function legacyBytes(text: string): number {
  let bytes = 0;
  for (let i = 0; i < text.length; i += 1) bytes += text.charCodeAt(i) > 127 ? 2 : 1;
  return bytes;
}
