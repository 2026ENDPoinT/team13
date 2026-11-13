// 퀴즈 출제·응시·채점 (`/api/courses/{courseId}/quizzes/...`, ADR-0021). 응시 구간, 응시 마감 일시,
// 시작 가능 여부는 서버가 DB 시계로 정한다. 학생 응답의 점수·정답·해설·문항 점수는 서버가 공개를 허락했을
// 때만 속성으로 들어오므로 화면은 있을 때만 그린다. 응답은 메모리에만 둔다.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.js";
import { subscribe } from "./backend.js";
import { createResource, retryLater } from "./remote-cache.js";
import { forgetGradebook } from "./grades-remote.js";

/** `srv-<quizId>`의 퀴즈 ID, 아니면 null. */
export function quizIdOf(ref) {
  const match = /^srv-([1-9]\d{0,15})$/.exec(String(ref || ""));
  return match ? Number(match[1]) : null;
}

export const quizRef = (quizId) => `srv-${quizId}`;

/** 샘플 과목 `kj`의 서버 퀴즈 학생 화면 주소. `attempt`를 주면 그 응시 화면이다. */
export function studentQuizHref(kj, quizId, extra = {}) {
  const params = new URLSearchParams({ m: "test", n: quizRef(quizId), kj: String(kj) });
  for (const [key, value] of Object.entries(extra)) if (value != null && value !== "") params.set(key, String(value));
  return `/ilos/cls/st/activity/activity_form.acl?${params}`;
}

const base = (courseId) => `/api/courses/${encodeURIComponent(courseId)}/quizzes`;
const one = (courseId, quizId) => `${base(courseId)}/${encodeURIComponent(quizId)}`;
const attemptPath = (courseId, quizId, attemptId) => `${one(courseId, quizId)}/attempts/${encodeURIComponent(attemptId)}`;

export const quizListKey = (courseId) => String(courseId);
export const quizKey = (courseId, quizId) => `${courseId}:${quizId}`;
export const attemptKey = (courseId, quizId, attemptId) => `${courseId}:${quizId}:${attemptId}`;

// 응답이 도착한 순간(단조 시계). 남은 시간은 `deadlineAt - serverTime`에서 도착 뒤 흐른 시간만 뺀다.
const arrivals = new WeakMap();
const stamped = (promise) => promise.then((data) => {
  if (data && typeof data === "object") arrivals.set(data, performance.now());
  return data;
});

/** `GET …/quizzes`: 학생은 공개됐고 시작한 퀴즈와 본인 `status`, 담당자는 응시 수. */
export const quizLists = createResource((courseId) => apiGet(base(courseId)));
/** `GET …/quizzes/{id}`: 학생 `{ quiz, phase, serverTime, canStart, attemptsUsed, attempts, score? }`, 담당자 `{ quiz, revision, questionsLocked, questions, … }`. */
export const quizDetails = createResource((courseId, quizId) => stamped(apiGet(one(courseId, quizId))));
/** `GET …/attempts/{attemptId}`: 학생은 응시 순서의 문항과 본인 답, 담당자는 답안과 채점 내용. */
export const attemptDetails = createResource((courseId, quizId, attemptId) => stamped(apiGet(attemptPath(courseId, quizId, attemptId))));

const PAGE_SIZE = 50;
/**
 * 담당자의 응시 목록. `nextCursor`로 이어 받은 쪽을 한 목록에 붙인다. `previous`가 없으면 첫 쪽이다.
 * 결과는 `{ items, nextCursor }`.
 */
export const attemptPages = createResource(async (courseId, quizId, previous) => {
  const query = new URLSearchParams({ size: String(PAGE_SIZE) });
  if (previous && previous.nextCursor) query.set("cursor", previous.nextCursor);
  const page = await apiGet(`${one(courseId, quizId)}/attempts?${query}`);
  const items = Array.isArray(page && page.items) ? page.items : [];
  return { items: previous ? [...previous.items, ...items] : items, nextCursor: page && page.nextCursor ? String(page.nextCursor) : null };
});

/** 다음 쪽이 있으면 받아 붙인다. */
export function loadMoreAttempts(courseId, quizId) {
  const key = quizKey(courseId, quizId);
  const entry = attemptPages.peek(key);
  if (!entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return attemptPages.load(key, courseId, quizId, entry.data);
}

/** 한 퀴즈의 캐시(목록 포함)를 모두 버린다. */
export function forgetQuiz(courseId, quizId) {
  const scope = quizKey(courseId, quizId);
  quizLists.drop((key) => key === quizListKey(courseId));
  quizDetails.drop((key) => key === scope);
  attemptPages.drop((key) => key === scope);
  attemptDetails.drop((key) => key.startsWith(`${scope}:`));
}

/** 서버 LocalDateTime(같은 벽시계 시간대) 두 값의 차이(ms). 읽지 못하면 NaN. */
function wallMs(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?/.exec(String(value || ""));
  if (!match) return NaN;
  const [, y, mo, d, h, mi, s = "0", frac = "0"] = match;
  return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s), Number(frac.slice(0, 3).padEnd(3, "0")));
}

/**
 * `data`(응시 응답)를 받은 뒤 지금까지의 남은 시간(ms). 서버의 `deadlineAt`과 `serverTime`만 쓰고,
 * 브라우저의 벽시계는 쓰지 않는다(도착 뒤 흐른 시간만 `performance.now()`로 잰다).
 */
export function remainingMs(data) {
  if (!data) return NaN;
  const left = wallMs(data.deadlineAt) - wallMs(data.serverTime);
  if (Number.isNaN(left)) return NaN;
  const at = arrivals.get(data);
  return left - (at == null ? 0 : performance.now() - at);
}

/** "12:05" 또는 "1:02:05". */
export function clockText(ms) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

// ---------- student ----------

/**
 * 응시 시작. 진행 중 응시가 있으면 서버가 그 응시를 돌려준다(200, 다른 session이면 이 session으로 옮긴다).
 * 없으면 새 회차를 만든다(201). 상태로 멱등이라 key를 보내지 않는다. `{ state, resumed }`를 돌려준다.
 * `resumed`는 응답 본문의 `created`(true면 새 회차, false면 진행 중 응시)를 뒤집은 값이다.
 */
export async function startAttempt(courseId, quizId) {
  const state = await apiSend("POST", `${one(courseId, quizId)}/attempts`, {});
  quizLists.drop((key) => key === quizListKey(courseId));
  quizDetails.load(quizKey(courseId, quizId), courseId, quizId);
  attemptDetails.drop((key) => key === attemptKey(courseId, quizId, state.attemptId));
  return { state, resumed: state.created === false };
}

/**
 * 답 하나를 저장한다. `revision`은 그 문항 답안의 마지막으로 읽은(또는 저장한) 개정 번호다.
 * 선택 문항은 `optionIds`, 단답형은 `text`만 보낸다. `{ questionId, revision, savedAt }`을 돌려준다.
 */
export function saveAnswer(courseId, quizId, attemptId, questionId, { revision, optionIds, text }) {
  const body = text !== undefined ? { revision: Number(revision) || 0, text } : { revision: Number(revision) || 0, optionIds: optionIds || [] };
  return apiSend("PUT", `${attemptPath(courseId, quizId, attemptId)}/answers/${encodeURIComponent(questionId)}`, body);
}

// 요청이 서버에 닿았을 수 있는 실패: 같은 응시의 다음 제출은 같은 key를 보낸다.
const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingSubmit = null; // { scope, key }
subscribe(() => { pendingSubmit = null; });

/** 이 응시의 마지막 제출이 응답 없이 끝났는지. */
export function submitUncertain(courseId, quizId, attemptId) {
  return Boolean(pendingSubmit && pendingSubmit.scope === attemptKey(courseId, quizId, attemptId));
}

/**
 * 제출(`Idempotency-Key` 필수). 시도마다 새 key이고, 앞 시도가 응답 없이 끝났으면 같은 key를 다시 보내
 * 서버가 처음 receipt를 돌려주게 한다. 마감 뒤에 보내면 서버가 마지막 저장 답안으로 닫는다.
 * receipt `{ attemptId, attemptNo, status, submittedAt, finishReason }`를 돌려준다(점수는 없다).
 */
export async function submitAttempt(courseId, quizId, attemptId) {
  const scope = attemptKey(courseId, quizId, attemptId);
  const attempt = pendingSubmit && pendingSubmit.scope === scope ? pendingSubmit : { scope, key: newRequestKey() };
  pendingSubmit = attempt;
  try {
    const receipt = await apiSend("POST", `${attemptPath(courseId, quizId, attemptId)}/submit`, undefined, { idempotencyKey: attempt.key });
    pendingSubmit = null;
    forgetQuiz(courseId, quizId);
    return receipt;
  } catch (error) {
    if (!uncertain(error)) pendingSubmit = null;
    throw error;
  }
}

// ---------- instructor ----------

let pendingCreate = null; // { scope, content, key }
subscribe(() => { pendingCreate = null; });

/**
 * 퀴즈 등록(201). 제출마다 새 `Idempotency-Key`이고, 응답 없이 끝난 같은 내용의 재시도는 같은 key를 보내
 * 서버가 이미 만든 퀴즈를 돌려준다.
 */
export async function createQuiz(courseId, payload) {
  const scope = String(courseId);
  const content = JSON.stringify(payload);
  const attempt = pendingCreate && pendingCreate.scope === scope && pendingCreate.content === content
    ? pendingCreate
    : { scope, content, key: newRequestKey() };
  pendingCreate = attempt;
  try {
    const created = await apiSend("POST", base(courseId), payload, { idempotencyKey: attempt.key });
    pendingCreate = null;
    quizLists.drop((key) => key === quizListKey(courseId));
    return created;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

/** 설정 수정. `payload.revision`은 폼을 읽은 수정 번호다(409 `edit_conflict`). 응답은 담당자 상세다. */
export async function updateQuiz(courseId, quizId, payload) {
  const updated = await apiSend("PUT", one(courseId, quizId), payload);
  forgetQuiz(courseId, quizId);
  return updated;
}

/** 문항 전체 교체. 응시가 있으면 409 `questions_locked`, 수정 번호가 다르면 409 `edit_conflict`. */
export async function replaceQuestions(courseId, quizId, revision, questions) {
  const updated = await apiSend("PUT", `${one(courseId, quizId)}/questions`, { revision: Number(revision), questions });
  forgetQuiz(courseId, quizId);
  return updated;
}

/** 담당자 상세(`InstructorQuiz`)의 설정을 수정 요청 본문으로. `changes`가 덮어쓴다. */
export function quizRequest(info, revision, changes = {}) {
  const online = info.examType !== "offline";
  return {
    weekNo: info.weekNo,
    title: info.title,
    description: info.description || null,
    openAt: info.openAt || null,
    closeAt: info.closeAt,
    published: Boolean(info.published),
    timeLimitMinutes: online ? info.timeLimitMinutes ?? null : null,
    attemptLimit: online ? info.attemptLimit ?? null : null,
    shuffleQuestions: online ? Boolean(info.shuffleQuestions) : false,
    resultReleaseAt: info.resultReleaseAt || null,
    answersVisible: online ? Boolean(info.answersVisible) : false,
    maxScore: online ? null : Number(info.maxScore),
    revision: Number(revision),
    ...changes,
  };
}

/** 단답형 채점. 점수는 0~문항 배점, 의견은 일반 텍스트 1,000자. 응답은 담당자의 응시 상세다. */
export async function gradeAnswer(courseId, quizId, attemptId, questionId, { score, feedback }) {
  const detail = await apiSend("PUT", `${attemptPath(courseId, quizId, attemptId)}/answers/${encodeURIComponent(questionId)}/grade`, { score, feedback });
  attemptDetails.patch((key) => key === attemptKey(courseId, quizId, attemptId), (data) => Object.assign(data, detail));
  attemptPages.drop((key) => key === quizKey(courseId, quizId));
  quizLists.drop((key) => key === quizListKey(courseId));
  // 채점 대기 수가 바뀐 시험 상세는 버린다. 채점 화면은 상세를 보지 않고, 다음에 여는 화면이 받는다.
  quizDetails.drop((key) => key === quizKey(courseId, quizId));
  return detail;
}

// ---------- text ----------

export const PHASE = { not_open: "시작 전", open: "응시 기간", closed: "마감" };
export const ATTEMPT_STATUS = { not_started: "미응시", in_progress: "응시 중", submitted: "채점 대기", graded: "채점 완료" };
export const FINISH = { submitted: "직접 제출", deadline: "마감 시각에 닫힘" };
export const QUESTION_TYPE = { single: "단일 선택", multiple: "복수 선택", short: "단답형" };

export function isConflict(error, code) {
  return error instanceof ApiError && error.status === 409 && error.code === code;
}

/** "20" 또는 "17.5". */
export function scoreText(value) {
  const n = Number(value);
  return value == null || !Number.isFinite(n) ? "" : String(Math.round(n * 100) / 100);
}

/** 퀴즈·응시·채점 요청이 실패했을 때의 한국어 문구. */
export function quizMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "시험 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "시험을 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409:
      switch (error.code) {
        case "offline_quiz": return "오프라인 시험은 온라인으로 응시할 수 없습니다.";
        case "deadline_passed": return "응시 시간이 끝났습니다. 마지막으로 저장된 답으로 처리됩니다.";
        case "attempt_limit_reached": return "응시 횟수를 모두 썼습니다.";
        case "attempt_conflict": return "응시를 시작하지 못했습니다. 잠시 후 다시 시도하세요.";
        case "attempt_moved": return "다른 기기나 브라우저에서 이어서 응시하고 있습니다. 이 화면에서는 저장하거나 제출할 수 없습니다.";
        case "attempt_closed": return "이미 제출한 응시입니다.";
        case "answer_conflict": return "다른 탭이나 기기에서 이 문항을 먼저 저장했습니다. 서버에 저장된 답을 불러왔으니 확인한 뒤 다시 고르세요.";
        case "attempt_not_submitted": return "아직 제출하지 않은 응시는 채점할 수 없습니다.";
        case "edit_conflict": return "다른 곳에서 이 시험을 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
        case "questions_locked": return "응시가 시작된 시험이라 문항을 바꿀 수 없습니다. 설정은 바꿀 수 있습니다.";
        case "score_exceeds_max": return "이미 준 점수보다 배점을 낮출 수 없습니다.";
        case "awarded_exceeds_points": return "이미 준 점수보다 배점을 낮출 수 없습니다. 그 문항의 점수를 먼저 고친 뒤 다시 저장하세요.";
        case "online_quiz": return "온라인 시험의 점수는 응시를 채점해 정합니다. 점수 입력은 오프라인 시험에만 있습니다.";
        case "idempotency_conflict": return "같은 요청을 다른 내용으로 보냈습니다. 내용을 확인한 뒤 다시 시도하세요.";
        case "idempotency_in_progress": return "같은 요청을 처리하고 있습니다. 잠시 후 상태를 확인하세요.";
        default: return error.detail || "요청이 지금 상태와 맞지 않습니다. 화면을 새로 불러오세요.";
      }
    case 429: return retryLater(error);
    case 0: return error.message;
    case 502:
    case 503:
    case 504: return "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "시험 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/**
 * 퀴즈 삭제(ADR-0037, 활동의 삭제 표시). `If-Match`에 마지막으로 읽은 상세의 `revision`을 보낸다. 없으면 428, 다르면
 * 409 `edit_conflict`다. 응시와 성적은 서버에 남고 그 commit부터 누구에게도 보이지 않는다. 204면 목록 캐시에서 그 퀴즈를
 * 빼고 상세·응시 캐시와 성적부를 버린다. 실패하면 그 퀴즈의 캐시를 모두 버린다.
 */
export async function deleteQuiz(courseId, quizId, revision) {
  try {
    await apiSend("DELETE", one(courseId, quizId), undefined, { ifMatch: revision });
  } catch (error) {
    forgetQuiz(courseId, quizId);
    throw error;
  }
  const scope = quizKey(courseId, quizId);
  quizLists.patch((key) => key === quizListKey(courseId), (items) => {
    if (!Array.isArray(items)) return;
    const at = items.findIndex((item) => item.quizId === quizId);
    if (at >= 0) items.splice(at, 1);
  });
  quizDetails.drop((key) => key === scope);
  attemptPages.drop((key) => key === scope);
  attemptDetails.drop((key) => key.startsWith(`${scope}:`));
  forgetGradebook(courseId);
}

/**
 * 정답과 배점 정정(재채점, ADR-0037). 선택 문항은 `{ revision, points, correctOptionIds }`, 단답형은 `correctOptionIds`를
 * 빼고 `{ revision, points }`만 보낸다. 응답 `{ quiz, rescoredAttempts }`의 `quiz`(담당자 상세)로 상세 캐시를 바꾸고,
 * 점수가 바뀌었을 응시·목록·성적부 캐시는 버린다. 409 `edit_conflict`, 409 `awarded_exceeds_points`, 400.
 */
export async function regradeQuestion(courseId, quizId, questionId, { revision, points, correctOptionIds }) {
  const body = correctOptionIds === undefined
    ? { revision: Number(revision), points }
    : { revision: Number(revision), points, correctOptionIds };
  const result = await apiSend("PUT", `${one(courseId, quizId)}/questions/${encodeURIComponent(questionId)}/answer-key`, body);
  const scope = quizKey(courseId, quizId);
  if (result && result.quiz) {
    quizDetails.patch((key) => key === scope, (data) => {
      for (const name of Object.keys(data)) delete data[name];
      Object.assign(data, result.quiz);
    });
  }
  quizLists.drop((key) => key === quizListKey(courseId));
  attemptPages.drop((key) => key === scope);
  attemptDetails.drop((key) => key.startsWith(`${scope}:`));
  forgetGradebook(courseId);
  return result;
}
