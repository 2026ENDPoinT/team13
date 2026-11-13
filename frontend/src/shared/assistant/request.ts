// `POST /api/assistant`: the one call both the sample home (`run.ts` `ask`) and the signed-in home (`server-ask.ts`) make.
// With a backend (`ASTRA_API_ORIGIN`), server.mjs forwards it to the backend, which checks the session, origin and CSRF
// token, so it goes through `apiSend` (X-XSRF-TOKEN). Without one, server.mjs answers it with its local relay, which has
// no CSRF endpoint, so it is a plain fetch.
import { ApiError, apiSend } from "../api.ts";
import { backendStatus } from "../backend.ts";
import type { ResultKind } from "./activity-context.ts";

/** The JSON body `/api/assistant` answers (checked by `isAssistantResponse`). */
export interface AssistantResponse {
  kind: ResultKind;
  intent: string | null;
  courseId: string | null;
  targets: string[];
  say: string;
}

export type AssistantReply = { ok: true; data: unknown } | { ok: false; status: number; retryAfter: number | null };

const INTENTS = new Set<string | null>(["video", "report", "test", "survey", "material", "zoom", "course", "plan"]);
const KINDS = ["go", "choose", "plan", "revise", "reject", "none", "clarify"];

/** The answer's shape: a known kind, a known intent unless the kind carries none, string targets, a `say` text. */
export function isAssistantResponse(data: unknown): data is AssistantResponse {
  if (!data || typeof data !== "object" || Array.isArray(data)) return false;
  const body = data as Record<string, unknown>;
  return typeof body.kind === "string" && KINDS.includes(body.kind) && typeof body.say === "string"
    && (["none", "clarify", "reject"].includes(body.kind) || INTENTS.has(body.intent as string | null))
    && Array.isArray(body.targets) && body.targets.every((id) => typeof id === "string")
    && (body.courseId == null || typeof body.courseId === "string");
}

/**
 * The sentence for a failed assistant call. The backend answers 401 `unauthorized`, 429 `rate_limited` (with
 * `Retry-After`), 502 `assistant_upstream_error`/`assistant_invalid_response`, 503 `llm_disabled` and 504
 * `assistant_timeout`; the local relay uses the same statuses.
 */
export function assistantFailure(status: number, retryAfter: number | null = null): string {
  switch (status) {
    case 401: return "AI 도우미를 쓰려면 먼저 로그인하세요";
    case 429: return retryAfter && retryAfter > 0
      ? `AI 요청이 많습니다. ${Math.ceil(retryAfter)}초 뒤 다시 시도하세요`
      : "AI 요청이 많습니다. 잠시 후 다시 시도하세요";
    case 502:
    case 504: return "AI가 답하지 못했습니다. 잠시 후 다시 시도하세요";
    case 503: return "AI 연결이 설정되지 않았습니다";
    default: return "AI 요청을 처리하지 못했습니다";
  }
}

/** Sends the request body; network failures and cancellation reject (the caller tells them apart by its signals). */
export async function postAssistant(body: unknown, signal: AbortSignal): Promise<AssistantReply> {
  if (backendStatus() !== "off") {
    try {
      return { ok: true, data: await apiSend("POST", "/api/assistant", body, { signal }) };
    } catch (error) {
      if (error instanceof ApiError && error.status > 0) {
        return { ok: false, status: error.status, retryAfter: error.retryAfter && error.retryAfter > 0 ? error.retryAfter : null };
      }
      throw error;
    }
  }
  const response = await fetch("/api/assistant", {
    method: "POST", headers: { "Content-Type": "application/json" }, signal, body: JSON.stringify(body),
  });
  if (!response.ok) {
    const retry = Number(response.headers.get("retry-after"));
    return { ok: false, status: response.status, retryAfter: Number.isFinite(retry) && retry > 0 ? retry : null };
  }
  return { ok: true, data: await response.json() };
}
