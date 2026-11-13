// Study chat on one material file with a published summary (ADR-0032) and its file overview.
// A question is `POST …/study-chats/{chatId}/exchanges` with a required `Idempotency-Key`
// (a retry of the same question after a lost answer sends the same key, so the server returns
// the same exchange); the answer is written by a worker and read back by polling the exchange.
// Questions and answers stay in memory only and are dropped when the session changes.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.js";
import { subscribe } from "./backend.js";
import { retryLater } from "./remote-cache.js";

export const POLL_MS = 1500;
const POLL_LIMIT = 200;

const base = (courseId) => `/api/courses/${encodeURIComponent(courseId)}`;
const fileChats = (courseId, fileId) => `${base(courseId)}/files/${encodeURIComponent(fileId)}/study-chats`;
const chatPath = (courseId, chatId) => `${base(courseId)}/study-chats/${encodeURIComponent(chatId)}`;

/** Published file overview `{ question, story, markdown }`, or null when nothing is published. */
export async function fileOverview(courseId, fileId) {
  try {
    return await apiGet(`${base(courseId)}/files/${encodeURIComponent(fileId)}/summary/overview`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/**
 * My chats on the file, newest first, 20 per page: `{ items: [{ id, fileId, exchangeCount, createdAt, lastActivityAt }], nextBefore }`.
 * `nextBefore` goes back as `before` for the older page; null (the server leaves it out) is the last page.
 */
export async function listChats(courseId, fileId, before) {
  const page = await apiGet(`${fileChats(courseId, fileId)}${before ? `?before=${encodeURIComponent(before)}` : ""}`);
  return {
    items: Array.isArray(page && page.items) ? page.items : [],
    nextBefore: page && page.nextBefore != null ? page.nextBefore : null,
  };
}

const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pending = new Map(); // operation fingerprint -> key, for retries after a lost answer
subscribe(() => pending.clear());

async function keyed(fingerprint, send) {
  const key = pending.get(fingerprint) || newRequestKey();
  pending.set(fingerprint, key);
  try {
    const result = await send(key);
    pending.delete(fingerprint);
    return result;
  } catch (error) {
    if (!uncertain(error)) pending.delete(fingerprint);
    throw error;
  }
}

/** Opens a chat on the file. 409 `summary_not_published` / `llm_consent_required`, 503 `llm_disabled`. */
export function openChat(courseId, fileId) {
  return keyed(`open:${courseId}:${fileId}`, (key) => apiSend("POST", fileChats(courseId, fileId), undefined, { idempotencyKey: key }));
}

export function deleteChat(courseId, chatId) {
  return apiSend("DELETE", chatPath(courseId, chatId));
}

/** Newest first, 10 per page; `nextBefore` for the older page. */
export function listExchanges(courseId, chatId, before) {
  return apiGet(`${chatPath(courseId, chatId)}/exchanges${before ? `?before=${encodeURIComponent(before)}` : ""}`);
}

export function getExchange(courseId, chatId, exchangeId) {
  return apiGet(`${chatPath(courseId, chatId)}/exchanges/${encodeURIComponent(exchangeId)}`);
}

/**
 * Asks `question`. Resolves the queued exchange receipt `{ id, state }` (202). The key is reused
 * only when the previous attempt for the same chat and question ended without an answer.
 */
export function ask(courseId, chatId, question) {
  const text = String(question || "").trim();
  return keyed(`ask:${courseId}:${chatId}:${text}`, (key) => apiSend("POST", `${chatPath(courseId, chatId)}/exchanges`, { question: text }, { idempotencyKey: key }));
}

export const isWaiting = (exchange) => exchange && (exchange.state === "queued" || exchange.state === "running");

/** Polls an exchange until it is answered, failed or cancelled. `alive()` false stops early. */
export async function awaitAnswer(courseId, chatId, exchange, { onState, alive } = {}) {
  let current = exchange;
  for (let i = 0; isWaiting(current) && i < POLL_LIMIT; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
    if (alive && !alive()) return current;
    current = await getExchange(courseId, chatId, current.id);
    if (onState) onState(current);
  }
  return current;
}

const FAILED = {
  consent: "담당 교수님이 학습 대화 동의를 거두어 답하지 못했습니다.",
  disabled: "외부 AI 기능이 꺼져 있어 답하지 못했습니다.",
  forbidden: "이 자료를 볼 수 있는 권한이 없어 답하지 못했습니다.",
  unavailable: "게시된 요약이 내려가 답하지 못했습니다.",
  budget: "이 과목의 오늘 학습 대화 사용량을 모두 써서 답하지 못했습니다.",
  invalid_output: "안전하게 보여 줄 수 없는 답이라 저장하지 않았습니다. 질문을 바꿔 다시 물어보세요.",
  provider: "AI 제공자가 답하지 않았습니다. 잠시 후 다시 물어보세요.",
  interrupted: "답을 만드는 중에 멈췄습니다. 다시 물어보세요.",
};

/** Korean text for a failed or cancelled exchange. */
export function exchangeFailure(exchange) {
  return FAILED[exchange && exchange.errorCode] || "답하지 못했습니다. 잠시 후 다시 물어보세요.";
}

/** Korean message for a failed chat request. */
export function studyMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "학습 대화 요청을 처리하지 못했습니다.";
  switch (error.code) {
    case "summary_not_published": return "교수님이 요약을 게시한 자료에서만 학습 대화를 할 수 있습니다.";
    case "llm_consent_required": return "담당 교수님이 이 과목의 학습 대화에 동의하지 않아 쓸 수 없습니다.";
    case "llm_disabled": return "이 서비스에서는 외부 AI 기능을 쓰지 않습니다.";
    case "study_chat_busy": return "앞 질문의 답을 기다리고 있습니다. 답을 받은 뒤 다시 물어보세요.";
    case "study_chat_budget_exhausted": return "이 과목의 오늘 학습 대화 사용량을 모두 썼습니다. 내일 다시 물어보세요.";
    case "idempotency_conflict": return "같은 요청이 다른 내용으로 처리 중입니다. 잠시 후 다시 시도하세요.";
    default: break;
  }
  switch (error.status) {
    case 400: return error.detail || "질문을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "대화나 자료를 찾을 수 없습니다. 지웠거나 볼 수 있는 권한이 없습니다.";
    case 429: return retryLater(error);
    case 503: return "외부 AI 기능을 지금 쓸 수 없습니다.";
    case 0: return error.message;
    default: return "학습 대화 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
