// Online lectures (영상 활동) on the ASTRA backend: the course week list, the activity detail with
// its lecture items and their video state, the learner's own progress, learning sessions
// (start, heartbeat, end), the learning-auth challenge, and the instructor's registration with
// the stream upload. Credited time, progress, attendance and the credit window come from the
// server only; the browser sends a sequence number, a position hint and whether it is playing.
// Responses stay in memory; nothing is written to browser storage.
import { apiGet, apiSend, apiKeepalive, apiStreamUpload, ApiError, newRequestKey } from "./api.js";
import { schoolLoginHref, subscribe } from "./backend.js";
import { createResource, retryLater } from "./remote-cache.js";
import { koDateTime } from "./home-remote.js";

/** Activity ids (`?n=`) of backend lectures are `srv-<activityId>`. */
export function lectureIdOf(ref) {
  const match = /^srv-([1-9]\d{0,15})$/.exec(String(ref || ""));
  return match ? Number(match[1]) : null;
}

export const lectureRef = (activityId) => `srv-${activityId}`;

const PLAYER_PATH = "/ilos/cls/st/activity/player_form.acl";
const DETAIL_PATH = "/ilos/cls/st/activity/activity_form.acl";

/** Student detail of a backend lecture in sample course `kj`. */
export function studentLectureHref(kj, activityId) {
  return `${DETAIL_PATH}?${new URLSearchParams({ m: "lecture_weeks", n: lectureRef(activityId), kj: String(kj) })}`;
}

/** Player of one lecture item. */
export function lecturePlayerHref(kj, activityId, itemId) {
  return `${PLAYER_PATH}?${new URLSearchParams({ n: lectureRef(activityId), item: String(itemId), kj: String(kj) })}`;
}

const course = (courseId) => `/api/courses/${encodeURIComponent(courseId)}`;

export const courseKey = (courseId) => String(courseId);
export const detailKey = (courseId, activityId) => `${courseId}:${activityId}`;

/**
 * `GET …/weeks`: `{ serverTime, weeks: [{ weekNo, title, startsOn, endsOn, activities }] }`. Each activity has `type`,
 * `openAt`, `closeAt`, `published`, the server-clock `phase` (`not_open`·`open`·`late`·`closed`) and, for an assignment,
 * quiz or survey, its `assignmentId`·`quizId`·`surveyId`. Students do not get activities that have not opened yet.
 */
export const courseWeeks = createResource((courseId) => apiGet(`${course(courseId)}/weeks`));

/** The week array of a `courseWeeks` response (`{ serverTime, weeks }`). */
export function weeksOf(data) {
  return data && Array.isArray(data.weeks) ? data.weeks : [];
}

/** Server-clock phase of a lecture or material (`phase` on week activities and the activity detail). */
export const ACTIVITY_PHASE = { not_open: "시작 전", open: "진행 중", late: "지각 제출 기간", closed: "마감" };
/**
 * `GET …/activities/{id}`: the activity with `description`, `files` (each with `summaryPublished`), `lectureItems[{ itemId,
 * title, durationSec, requiredSec, state }]`, `phase`, `serverTime`, `version` and, for an assignment, quiz or survey, its id.
 */
export const lectureDetails = createResource((courseId, activityId) => apiGet(`${course(courseId)}/activities/${encodeURIComponent(activityId)}`));
/** `GET …/lecture-progress`: the caller's own progress per item, grouped by week. */
export const lectureProgress = createResource((courseId) => apiGet(`${course(courseId)}/lecture-progress`));

export const LECTURE_PAGE_MAX = 100;

/**
 * One page of the instructor's lecture list `GET …/lectures?cursor=&size=` (size 1–100, default 20):
 * `{ items: [{ activityId, weekNo, title, description, openAt, closeAt, published, version, items: [{ itemId, title, durationSec, requiredSec, state }] }], nextCursor }`.
 * Week, order in the week, then activity id; published or not. `nextCursor` null is the last page.
 */
export async function lecturePage(courseId, cursor, size = LECTURE_PAGE_MAX) {
  const query = new URLSearchParams({ size: String(size) });
  if (cursor) query.set("cursor", cursor);
  const page = await apiGet(`${course(courseId)}/lectures?${query}`);
  return { items: Array.isArray(page && page.items) ? page.items : [], nextCursor: (page && page.nextCursor) || null };
}

/**
 * The instructor's lectures of a course (the content list and the room's week view need every one):
 * all pages of `GET …/lectures` at size 100, following `nextCursor`. Each entry has `description`, so a
 * full update or a publish toggle sends the entry as listed without reading the detail.
 */
export const lectureCatalog = createResource(async (courseId) => {
  const all = [];
  const seen = new Set();
  let cursor = null;
  do {
    const page = await lecturePage(courseId, cursor);
    for (const lecture of page.items) {
      if (!seen.has(lecture.activityId)) {
        seen.add(lecture.activityId);
        all.push(lecture);
      }
    }
    cursor = page.nextCursor;
  } while (cursor);
  return all;
});

/** Items of a lecture from the list (`items`) or the detail (`lectureItems`). */
export function lectureItemsOf(lecture) {
  const items = lecture && (lecture.items || lecture.lectureItems);
  return Array.isArray(items) ? items : [];
}

/**
 * The latest detail of one lecture after a 409/428 (the version the editor must save against next):
 * the cached detail when it is at least `minVersion`, else read now.
 */
export async function lectureForEdit(courseId, activityId, minVersion = 0) {
  const key = detailKey(courseId, activityId);
  const cached = lectureDetails.peek(key);
  if (cached && cached.data && !cached.error && !cached.loading && cached.data.version >= minVersion) return cached.data;
  await lectureDetails.load(key, courseId, activityId);
  const entry = lectureDetails.peek(key);
  if (entry && entry.error) throw entry.error;
  if (entry && entry.data) return entry.data;
  throw new Error("강의 정보를 불러오지 못했습니다.");
}

/** The `lecture_weeks` activities of a week list, flattened with their week number. */
export function lectureRows(weeks) {
  const rows = [];
  for (const week of Array.isArray(weeks) ? weeks : []) {
    for (const activity of Array.isArray(week.activities) ? week.activities : []) {
      if (activity.type === "lecture_weeks") rows.push({ ...activity, weekNo: week.weekNo });
    }
  }
  return rows;
}

/** Progress items of one activity from a `lecture-progress` response, keyed by itemId. */
export function progressOf(progress, activityId) {
  const items = new Map();
  for (const week of Array.isArray(progress) ? progress : []) {
    for (const item of Array.isArray(week.items) ? week.items : []) {
      if (item.activityId === activityId && item.progress) items.set(item.itemId, item.progress);
    }
  }
  return items;
}

/** Forgets what a session, an edit or a delete changed, so the next visit reads it again. */
export function forgetLecture(courseId, activityId) {
  courseWeeks.drop((key) => key === courseKey(courseId));
  lectureProgress.drop((key) => key === courseKey(courseId));
  lectureCatalog.drop((key) => key === courseKey(courseId));
  if (activityId != null) lectureDetails.drop((key) => key === detailKey(courseId, activityId));
  lectureRoster.drop((key) => key.startsWith(`${courseId}:`));
}

// ---------- learning sessions ----------

const sessions = (courseId, sessionId) => `${course(courseId)}/learning-sessions/${encodeURIComponent(sessionId)}`;

/** Starts a learning session (201): `{ sessionId, heartbeatIntervalSec, leaseSec, creditable, creditUntil, resumePositionSec, progress, playback }`. */
export function startSession(courseId, itemId) {
  return apiSend("POST", `${course(courseId)}/lecture-items/${encodeURIComponent(itemId)}/learning-sessions`);
}

/** One heartbeat: `{ outcome, creditedSec, creditOpen, creditState, creditUntil, leaseRemainingSec, progress, playback }`. */
export function sendHeartbeat(courseId, sessionId, { seq, positionSec, playing }) {
  const body = { seq, playing: Boolean(playing) };
  if (Number.isInteger(positionSec)) body.positionSec = positionSec;
  return apiSend("POST", `${sessions(courseId, sessionId)}/heartbeats`, body);
}

/** Ends the session (idempotent): `{ state, endReason, creditedSec, progress }`. */
export function endSession(courseId, sessionId, { reason, positionSec }) {
  const body = { reason };
  if (Number.isInteger(positionSec)) body.positionSec = positionSec;
  return apiSend("POST", `${sessions(courseId, sessionId)}/end`, body);
}

/**
 * One heartbeat that outlives the page (`fetch` keepalive), sent from `beforeunload`: the page may
 * still stay, so this only reports the credit so far. A repeated `seq` changes nothing on the server.
 */
export function heartbeatOnUnload(courseId, sessionId, { seq, positionSec, playing }) {
  const body = { seq, playing: Boolean(playing) };
  if (Number.isInteger(positionSec)) body.positionSec = positionSec;
  return apiKeepalive(`${sessions(courseId, sessionId)}/heartbeats`, body);
}

/** The end request while the page goes away (`pagehide`, `fetch` keepalive, as the backend expects). */
export function endOnUnload(courseId, sessionId, positionSec) {
  const body = { reason: "unload" };
  if (Number.isInteger(positionSec)) body.positionSec = positionSec;
  return apiKeepalive(`${sessions(courseId, sessionId)}/end`, body);
}

/**
 * A closed session answers its heartbeat with 409 and one code per stored end reason; this is the
 * text the learner reads for each. `session_superseded` never says where the learning continued.
 */
export const SESSION_CLOSED = {
  session_superseded: { text: "다른 곳에서 학습을 이어 가고 있어 이 화면의 학습을 끝냈습니다. 이 화면에서 이어 보면 다른 곳의 학습이 끝납니다." },
  session_expired: { text: "서버에 학습 신호가 한동안 닿지 않아(네트워크 끊김, 절전 모드 등) 학습이 끝났습니다. 마지막 위치에서 다시 시작할 수 있습니다." },
  session_ended: { text: "이 학습은 이미 끝났습니다. 다른 탭에서 종료했거나 페이지를 떠날 때 끝났을 수 있습니다. 다시 시작할 수 있습니다." },
  session_withdrawn: { text: "강의가 비공개로 바뀌었거나 삭제되었거나 수강 상태가 바뀌어 이 콘텐츠를 더 볼 수 없습니다." },
};

/** The end response's `endReason` (`button|unload|forced|superseded|lease_expired`) as a `SESSION_CLOSED` code. */
export function closedCodeOf(endReason) {
  if (endReason === "superseded") return "session_superseded";
  if (endReason === "lease_expired") return "session_expired";
  if (endReason === "forced") return "session_withdrawn";
  return "session_ended";
}

/** The manifest path of a `playback` object, only when it is a same-origin `/vod/<asset>/<name>.m3u8`. */
export function manifestOf(playback) {
  const url = playback && typeof playback.manifestUrl === "string" ? playback.manifestUrl : "";
  return /^\/vod\/[A-Za-z0-9_-]{1,128}\/[A-Za-z0-9._-]{1,128}\.m3u8$/.test(url) ? url : null;
}

// ---------- learning auth (SC-06-A) ----------

export function learningAuthStatus() {
  return apiGet("/api/me/learning-auth");
}

/** A new `idp` challenge: `{ challengeId, status, expiresAt, redirectPath }`. */
export function createChallenge() {
  return apiSend("POST", "/api/me/learning-auth/challenges", { channel: "idp" });
}

/** Asks the server to confirm the challenge from its own evidence (the body is never evidence). */
export function verifyChallenge(challengeId) {
  return apiSend("POST", `/api/me/learning-auth/challenges/${encodeURIComponent(challengeId)}/verify`, {});
}

/** Local runs without a school IdP: the developer re-authentication that stands in for it. */
export function developerReauth() {
  return apiSend("POST", "/api/me/step-up/dev");
}

/** `/api/auth/reauth?returnTo=…` for this page; the same return-path rule as the login. */
export function reauthHref(where = globalThis.location) {
  return `/api/auth/reauth${schoolLoginHref(where).slice("/api/auth/login".length)}`;
}

// The IdP re-authentication runs in a new tab (a plain `target=_blank` link the learner clicks),
// so this tab keeps the challenge, the session and the playback position in memory. The new
// tab comes back to `returnTo` with `auth=done`; a failed re-authentication comes back to the
// same path with `reauth=failed` added (or to `/?reauth=failed` when the path is not allowed).
// That tab tells this one over a same-origin BroadcastChannel; nothing is stored. The learner
// can also confirm by hand.
const CHANNEL = "astra-learning-auth";

/** Calls `fn({ type: "done" | "failed" })` when another tab finishes the IdP step. Returns a stop function. */
export function onReauthMessage(fn) {
  if (typeof BroadcastChannel !== "function") return () => {};
  const channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = (event) => {
    const type = event && event.data && event.data.type;
    if (type === "done" || type === "failed") fn({ type });
  };
  return () => channel.close();
}

export function announceReauth(type) {
  if (typeof BroadcastChannel !== "function") return;
  const channel = new BroadcastChannel(CHANNEL);
  channel.postMessage({ type });
  channel.close();
}

/** `returnTo` for the re-authentication tab: this player page with `auth=done`. */
export function reauthReturnTo(where = globalThis.location) {
  const params = new URLSearchParams(where ? where.search : "");
  params.delete("reauth");
  params.set("auth", "done");
  return { pathname: where ? where.pathname : PLAYER_PATH, search: `?${params}` };
}

/** The address carried `reauth=failed` when this page loaded (read before app.js replaces the root address). */
export const reauthFailedAtLoad = typeof location !== "undefined" && new URLSearchParams(location.search).get("reauth") === "failed";
if (reauthFailedAtLoad) announceReauth("failed");

// ---------- instructor ----------

/** Uploads one video through `POST /api/uploads/stream`; resolves with the upload view (`state` is `clean`). */
export function uploadLectureVideo(file, courseId, { idempotencyKey, onProgress, signal } = {}) {
  const query = new URLSearchParams({ courseId: String(courseId), purpose: "lecture_video", name: String(file.name || "lecture.mp4") });
  return apiStreamUpload(`/api/uploads/stream?${query}`, file, { idempotencyKey, onProgress, signal });
}

export const VIDEO_EXTENSIONS = ["mp4", "mov", "webm", "mkv"];

// A create whose answer was lost keeps its key so the retry cannot make a second lecture.
let pendingCreate = null; // { content, key }
subscribe(() => { pendingCreate = null; });
const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status) && error.code !== "aborted";

export async function createLecture(courseId, payload) {
  const content = JSON.stringify([courseId, payload]);
  const attempt = pendingCreate && pendingCreate.content === content ? pendingCreate : { content, key: newRequestKey() };
  pendingCreate = attempt;
  try {
    const created = await apiSend("POST", `${course(courseId)}/lectures`, payload, { idempotencyKey: attempt.key });
    pendingCreate = null;
    forgetLecture(courseId, null);
    return created;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

/** Full update with `If-Match: "<version>"`; `items` is the final set `{ keep, add }`. */
export async function updateLecture(courseId, activityId, version, payload) {
  try {
    return await apiSend("PUT", `${course(courseId)}/lectures/${encodeURIComponent(activityId)}`, payload, { ifMatch: version });
  } finally {
    forgetLecture(courseId, activityId);
  }
}

export async function deleteLecture(courseId, activityId, version) {
  try {
    await apiSend("DELETE", `${course(courseId)}/lectures/${encodeURIComponent(activityId)}`, undefined, { ifMatch: version });
  } finally {
    forgetLecture(courseId, activityId);
  }
}

/** `datetime-local` value → server LocalDateTime (`2026-11-12T09:00:00`), or null. */
export function serverTime(value) {
  const text = String(value || "").trim();
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(text)) return null;
  return text.length === 16 ? `${text}:00` : text;
}

/** Server LocalDateTime → `datetime-local` value. */
export function localInput(value) {
  const text = String(value || "");
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(text) ? text.slice(0, 16) : "";
}

/** 409 `version_conflict` or 428: read the lecture again before writing. */
export function isStaleVersion(error) {
  return error instanceof ApiError && ((error.status === 409 && error.code === "version_conflict") || error.status === 428);
}

// ---------- text ----------

export const VIDEO_STATE = { processing: "변환 중", ready: "재생 가능", failed: "재생할 수 없음" };
export const PROGRESS_STATE = { met: "출석 인정", under_required: "출석 미달", unknown: "인정 시간 없음" };

/** "12분 30초" / "45초" / "1시간 5분" for seconds. */
export function durationText(total) {
  const seconds = Math.max(0, Math.floor(Number(total) || 0));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h) return `${h}시간${m ? ` ${m}분` : ""}`;
  if (m) return `${m}분${s ? ` ${s}초` : ""}`;
  return `${s}초`;
}

/** "11월 3일 (화) 오전 9:00 ~ 11월 12일 (목) 오후 11:59", with either end missing. */
export function windowText(item) {
  const open = item && item.openAt ? koDateTime(item.openAt) : "";
  const close = item && item.closeAt ? koDateTime(item.closeAt) : "";
  if (!open && !close) return "기간 제한 없음";
  return `${open || "공개 즉시"} ~ ${close || "제한 없음"}`;
}

/** Korean message for a failed lecture, session, upload or learning-auth request. */
export function lectureMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "강의 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 0: return error.message;
    case 400: return error.code === "verification_failed"
      ? "학습 인증을 확인하지 못했습니다. 학교 계정으로 다시 인증한 뒤 시도하세요."
      : error.code === "upload_incomplete" ? "업로드가 중간에 끊겼습니다. 다시 올리세요." : error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 403: return error.code === "learning_auth_required"
      ? "학습 인증이 필요합니다. 인증한 뒤 학습할 수 있습니다."
      : error.detail || "요청이 거부되었습니다. 페이지를 새로 불러오세요.";
    case 404: return "강의를 찾을 수 없습니다. 비공개로 바뀌었거나 삭제되었습니다.";
    case 409:
      switch (error.code) {
        case "session_superseded": return SESSION_CLOSED.session_superseded.text;
        case "session_expired": return SESSION_CLOSED.session_expired.text;
        case "session_ended": return SESSION_CLOSED.session_ended.text;
        case "session_withdrawn": return SESSION_CLOSED.session_withdrawn.text;
        case "version_conflict": return "다른 곳에서 이 강의를 먼저 고쳤습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
        case "attachment_not_ready": return "쓸 수 없는 영상이 있습니다. 업로드가 끝난 영상인지, 이 강의의 콘텐츠인지 확인하세요.";
        case "idempotency_conflict": return "같은 등록 요청을 다른 내용으로 보냈습니다. 내용을 확인한 뒤 다시 등록하세요.";
        case "challenge_used":
        case "challenge_locked":
        case "challenge_expired":
        case "challenge_cancelled": return error.detail || "인증을 다시 시작하세요.";
        default: return error.detail || "같은 요청이 이미 처리되었습니다. 화면을 새로 불러오세요.";
      }
    case 411: return "파일 크기를 알 수 없어 올리지 못했습니다.";
    case 413: return "파일이 너무 큽니다. 강의 영상은 2GB까지 올릴 수 있습니다.";
    case 422: return error.code === "content_mismatch"
      ? "파일 내용이 확장자와 맞지 않습니다. mp4, mov, webm, mkv 영상인지 확인하세요."
      : error.detail || "올릴 수 없는 파일입니다. mp4, mov, webm, mkv 영상만 올릴 수 있습니다.";
    case 428: return "강의 버전을 확인하지 못해 저장하지 않았습니다. 최신 내용을 불러왔으니 다시 시도하세요.";
    case 429: return error.code === "learning_auth_locked" ? error.detail || "인증에 여러 번 실패했습니다. 잠시 뒤 다시 시도하세요." : retryLater(error);
    case 502:
    case 503:
    case 504: return error.code === "delivery_unavailable" ? "영상 전달이 설정되지 않아 재생할 수 없습니다." : "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "강의 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

// ---------- 담당자의 수강생 진도 ----------

/** 담당자 진도표의 한 쪽 학생 수. 서버는 1~100명을 받는다. */
export const ROSTER_PAGE = 20;

export const rosterKey = (courseId, activityId, page) => `${courseId}:${activityId}:${page}`;

/**
 * `GET …/lecture-progress/students?activityId=&page=&size=`: `{ items: [열], students: [{ userId, name, studentNo, metCount,
 * items }], totalStudents, page, size }`. 학생은 수강생 명단의 사용자 ID 순으로 쪽을 나누고(`page`는 1부터), `activityId`를 주면
 * 그 영상 활동의 콘텐츠만 열로 온다. 학생 행의 `items`는 `items` 열과 같은 순서다. 키는 `rosterKey`.
 */
export const lectureRoster = createResource((courseId, activityId, page) => {
  const query = new URLSearchParams({ activityId: String(activityId), page: String(page), size: String(ROSTER_PAGE) });
  return apiGet(`${course(courseId)}/lecture-progress/students?${query}`);
});
