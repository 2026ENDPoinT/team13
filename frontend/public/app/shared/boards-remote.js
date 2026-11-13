// Course Q&A (`/api/courses/{courseId}/qna`) and open board (`…/open-board/posts`) on the ASTRA
// backend. The server decides who sees what: a private (`secret`) question never reaches other
// students, an anonymous author's name is sent only to the author and the instructors, and
// `canEdit`/`canDelete`/`canSetSolved`/`canSetFaq` say which commands the viewer has. Viewing a
// post never marks it read; the detail view sends `read` once. Everything stays in memory.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.js";
import { subscribe } from "./backend.js";
import { createResource, retryLater } from "./remote-cache.js";

export const QNA_PAGE = 8;
export const OPEN_PAGE = 10;
export const ANSWER_PAGE = 20;
export const COMMENT_PAGE = 50;
export const MAX_UPLOADS = 5;
export const MAX_TITLE = 300;
export const MAX_BODY = 20000;
/** Comments are limited in legacy bytes: a character above U+007F counts 2. */
export const COMMENT_BYTES = 1800;
export const QNA_STATUS = ["all", "faq", "solved", "unsolved"];

const course = (courseId) => `/api/courses/${encodeURIComponent(courseId)}`;
const qnaBase = (courseId) => `${course(courseId)}/qna`;
const question = (courseId, id) => `${qnaBase(courseId)}/${encodeURIComponent(id)}`;
const openBase = (courseId) => `${course(courseId)}/open-board/posts`;
const openPost = (courseId, id) => `${openBase(courseId)}/${encodeURIComponent(id)}`;
const postPath = (kind, courseId, id) => (kind === "qna" ? question(courseId, id) : openPost(courseId, id));

// ---------- keys ----------

/** `f`: `{ status, mine, answered, q }` of the Q&A list screen. */
export const qnaFilterKey = (courseId, f) => `${courseId}|qna|${f.status}|${f.mine ? 1 : 0}|${f.answered ? 1 : 0}|${f.q}`;
export const qnaListKey = (courseId, f, page) => `${qnaFilterKey(courseId, f)}|${page}`;
export const openListKey = (courseId, q) => `${courseId}|open|${q}`;
/** Detail, answers and comments of one post; `kind` is "qna" or "open". */
export const postKey = (courseId, kind, id) => `${courseId}:${kind}:${id}`;

const inCourse = (courseId) => (key) => key.startsWith(`${courseId}|`) || key.startsWith(`${courseId}:`);
const listsOf = (courseId, kind) => (key) => key.startsWith(`${courseId}|${kind}|`);

// ---------- Q&A list: page numbers kept stable by `snapshot` ----------

// qnaFilterKey -> snapshot of the first page read with that filter. A server value: dropped when the session changes.
const snapshots = new Map();
subscribe(() => snapshots.clear());

export const qnaLists = createResource(async (courseId, f, page) => {
  const params = new URLSearchParams({ page: String(page), size: String(QNA_PAGE) });
  if (f.status && f.status !== "all") params.set("status", f.status);
  if (f.mine) params.set("mine", "true");
  if (f.answered) params.set("answered", "true");
  if (f.q) params.set("q", f.q);
  const filter = qnaFilterKey(courseId, f);
  // Page 1 starts a new reading; later pages send its snapshot so new questions do not shift them.
  if (page > 1 && snapshots.has(filter)) params.set("snapshot", String(snapshots.get(filter)));
  const data = await apiGet(`${qnaBase(courseId)}?${params}`);
  if (data && (page === 1 || !snapshots.has(filter))) snapshots.set(filter, data.snapshot);
  return data;
});

export const qnaDetails = createResource((courseId, id) => apiGet(question(courseId, id)));

// ---------- cursor lists: open board posts, answers, comments ----------

function firstPage(page) {
  return { ...page, items: Array.isArray(page && page.items) ? page.items : [], nextCursor: page && page.nextCursor != null ? page.nextCursor : null, pages: 1 };
}

const openListUrl = (courseId, q) => `${openBase(courseId)}?size=${OPEN_PAGE}${q ? `&q=${encodeURIComponent(q)}` : ""}`;
const answersUrl = (courseId, id) => `${question(courseId, id)}/answers?size=${ANSWER_PAGE}`;
const commentsUrl = (courseId, id) => `${openPost(courseId, id)}/comments?size=${COMMENT_PAGE}`;

export const openLists = createResource(async (courseId, q) => firstPage(await apiGet(openListUrl(courseId, q))));
export const openDetails = createResource((courseId, id) => apiGet(openPost(courseId, id)));
export const answerLists = createResource(async (courseId, id) => firstPage(await apiGet(answersUrl(courseId, id))));
export const commentLists = createResource(async (courseId, id) => firstPage(await apiGet(commentsUrl(courseId, id))));

const idOf = (item) => item.postId ?? item.answerId ?? item.commentId;
const appending = new Set();

async function appendNext(resource, key, url) {
  const entry = resource.peek(key);
  const data = entry && entry.data;
  if (!data || data.nextCursor == null || appending.has(key)) return false;
  appending.add(key);
  try {
    const page = await apiGet(`${url}&cursor=${encodeURIComponent(data.nextCursor)}`);
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

export const isAppending = (key) => appending.has(key);
export const moreOpenPosts = (courseId, q) => appendNext(openLists, openListKey(courseId, q), openListUrl(courseId, q));
export const moreAnswers = (courseId, id) => appendNext(answerLists, postKey(courseId, "qna", id), answersUrl(courseId, id));
export const moreComments = (courseId, id) => appendNext(commentLists, postKey(courseId, "open", id), commentsUrl(courseId, id));

// ---------- cache updates ----------

const detailsOf = (kind) => (kind === "qna" ? qnaDetails : openDetails);
const listOf = (kind) => (kind === "qna" ? qnaLists : openLists);

/** Applies `change(item)` to the post in its detail and every cached list page of the course. */
function patchPost(courseId, kind, id, change) {
  detailsOf(kind).patch((key) => key === postKey(courseId, kind, id), change);
  listOf(kind).patch(listsOf(courseId, kind), (page) => (page.items || []).forEach((item) => { if (item.postId === id) change(item); }));
}

/** Forgets the course's lists of `kind` (counts or order changed) so the next visit reads them. */
export function forgetLists(courseId, kind) {
  listOf(kind).drop(listsOf(courseId, kind));
  if (kind === "qna") for (const key of [...snapshots.keys()]) if (key.startsWith(`${courseId}|qna|`)) snapshots.delete(key);
}

/** Forgets everything cached for one post (after a delete). */
function forgetPost(courseId, kind, id) {
  const key = postKey(courseId, kind, id);
  detailsOf(kind).drop((k) => k === key);
  (kind === "qna" ? answerLists : commentLists).drop((k) => k === key);
  forgetLists(courseId, kind);
}

// ---------- commands ----------

// The request may have reached the server: a retry of the same content keeps its key.
const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pending = new Map(); // operation -> { fingerprint, key }
subscribe(() => pending.clear());

/**
 * Sends a create with a fresh Idempotency-Key per attempt. When the previous attempt of the same
 * operation and content ended without a definite answer, its key is reused so the server returns
 * what it may already have made instead of a duplicate.
 */
async function createOnce(operation, fingerprint, send) {
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
export function createUncertain(operation) {
  return pending.has(operation);
}

/** `{ title, body, anonymous, secret, uploadIds }`. 201 with the question. */
export async function createQuestion(courseId, payload) {
  const question = await createOnce(`qna:${courseId}`, [courseId, payload], (key) => apiSend("POST", qnaBase(courseId), payload, { idempotencyKey: key }));
  forgetLists(courseId, "qna");
  return question;
}

/** `{ title, body }` for the open board. 201 with the post. */
export async function createOpenPost(courseId, payload) {
  const post = await createOnce(`open:${courseId}`, [courseId, payload], (key) => apiSend("POST", openBase(courseId), payload, { idempotencyKey: key }));
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
export async function updatePost(kind, courseId, id, payload) {
  const post = await apiSend("PUT", postPath(kind, courseId, id), payload);
  detailsOf(kind).patch((key) => key === postKey(courseId, kind, id), (current) => Object.assign(current, post));
  forgetLists(courseId, kind);
  return post;
}

export async function deletePost(kind, courseId, id) {
  try {
    await apiSend("DELETE", postPath(kind, courseId, id));
  } finally {
    forgetPost(courseId, kind, id);
  }
}

/** Target state: `liked` true sends PUT, false DELETE. Repeating is harmless; the count is the server's. */
export async function setLike(kind, courseId, id, liked) {
  const state = await apiSend(liked ? "PUT" : "DELETE", `${postPath(kind, courseId, id)}/like`);
  patchPost(courseId, kind, id, (item) => {
    item.liked = state.liked;
    item.likeCount = state.likeCount;
  });
  return state;
}

const readSent = new Set(); // postKey, per session
subscribe(() => readSent.clear());

/** Marks a post read when its detail is opened. Sends at most once per session. */
export async function markPostRead(kind, courseId, id) {
  const key = postKey(courseId, kind, id);
  if (readSent.has(key)) return null;
  readSent.add(key);
  try {
    const state = await apiSend("POST", `${postPath(kind, courseId, id)}/read`);
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
export async function setQuestionStatus(courseId, id, change) {
  const state = await apiSend("PUT", `${question(courseId, id)}/status`, change);
  qnaDetails.patch((key) => key === postKey(courseId, "qna", id), (item) => {
    item.solved = state.solved;
    item.faq = state.faq;
  });
  forgetLists(courseId, "qna");
  return state;
}

/** `{ body, anonymous, uploadIds }`; only students may answer anonymously. 201. */
export async function createAnswer(courseId, id, payload) {
  const answer = await createOnce(`answer:${courseId}:${id}`, [courseId, id, payload],
    (key) => apiSend("POST", `${question(courseId, id)}/answers`, payload, { idempotencyKey: key }));
  refreshThread(courseId, "qna", id);
  return answer;
}

/** `{ body, anonymous, version, attachments? }` (same `attachments` rule as `updatePost`). 409 `version_conflict` when stale. */
export async function updateAnswer(courseId, id, answerId, payload) {
  const answer = await apiSend("PUT", `${question(courseId, id)}/answers/${encodeURIComponent(answerId)}`, payload);
  answerLists.patch((key) => key === postKey(courseId, "qna", id), (page) => {
    const index = page.items.findIndex((item) => item.answerId === answerId);
    if (index !== -1) page.items[index] = answer;
  });
  return answer;
}

export async function deleteAnswer(courseId, id, answerId) {
  try {
    await apiSend("DELETE", `${question(courseId, id)}/answers/${encodeURIComponent(answerId)}`);
  } finally {
    refreshThread(courseId, "qna", id);
  }
}

/** `{ body, parentCommentId }`; a reply answers a top-level comment only. 201. */
export async function createComment(courseId, id, payload) {
  const comment = await createOnce(`comment:${courseId}:${id}`, [courseId, id, payload],
    (key) => apiSend("POST", `${openPost(courseId, id)}/comments`, payload, { idempotencyKey: key }));
  refreshThread(courseId, "open", id);
  return comment;
}

export async function deleteComment(courseId, id, commentId) {
  try {
    await apiSend("DELETE", `${openPost(courseId, id)}/comments/${encodeURIComponent(commentId)}`);
  } finally {
    refreshThread(courseId, "open", id);
  }
}

/** `{ body, version }`, author only (`canEdit`). Same 1800-byte rule as create; 409 `version_conflict` when stale. */
export async function updateComment(courseId, id, commentId, payload) {
  const comment = await apiSend("PUT", `${openPost(courseId, id)}/comments/${encodeURIComponent(commentId)}`, payload);
  commentLists.patch((key) => key === postKey(courseId, "open", id), (page) => {
    const index = page.items.findIndex((item) => item.commentId === commentId);
    if (index !== -1) page.items[index] = comment;
  });
  return comment;
}

/** After an answer or comment changed: read the thread and the post (counts, `canEdit`) again. */
function refreshThread(courseId, kind, id) {
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
export function forgetCourseBoards(courseId) {
  const match = inCourse(courseId);
  for (const resource of [qnaLists, qnaDetails, openLists, openDetails, answerLists, commentLists]) resource.drop(match);
}

// ---------- errors ----------

export function isCode(error, status, code) {
  return error instanceof ApiError && error.status === status && (!code || error.code === code);
}

/** Korean message for a failed board request. `noun` is "질문", "답글", "글", "댓글". */
export function boardMessage(error, noun = "글") {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : `${noun} 요청을 처리하지 못했습니다.`;
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
export function legacyBytes(text) {
  let bytes = 0;
  for (let i = 0; i < text.length; i += 1) bytes += text.charCodeAt(i) > 127 ? 2 : 1;
  return bytes;
}
