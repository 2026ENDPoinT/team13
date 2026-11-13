// The signed-in assistant: every command-bar input goes to `POST /api/assistant`, and the backend decides the answer
// and where it leads (`actions`). This module only sends the text with the current course/activity and the last few
// turns, checks the answer's shape, and keeps the in-app/external distinction the backend states.
import { backendState, courseIdFor } from "../backend.ts";
import { schoolHref } from "../school-content.tsx";
import { assistantFailure, postAssistant } from "./request.ts";

/** One place the answer leads to: an in-app path (router) or an external address (new tab). */
export interface AssistantAction {
  label: string;
  href: string;
  external: boolean;
}

export interface ServerAnswer {
  ok: boolean;
  say: string;
  actions: AssistantAction[];
}

interface Turn {
  text: string;
  say: string;
}

const MAX_TURNS = 4;
/** Same-day conversation of this signed-in user, in memory only. */
let history: { owner: string; turns: Turn[] } = { owner: "", turns: [] };

function action(value: unknown): AssistantAction | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const { label, href, external } = value as Record<string, unknown>;
  if (typeof href !== "string" || typeof label !== "string" || !label.trim()) return null;
  if (external === true) {
    // External actions are the school's e-Class pages (imported quizzes and surveys); nothing else opens a tab.
    const school = schoolHref(href);
    return school ? { label, href: school, external: true } : null;
  }
  // In-app paths only: one leading slash, no scheme-relative or backslash tricks.
  return external === false && href.startsWith("/") && !href.startsWith("//") && !href.includes("\\")
    ? { label, href, external: false } : null;
}

/** The current course (backend id from `kj`) and activity (`n`, else `act`, as-is: only a hint) from the address. */
function place(search: string): { courseId?: number; activityId?: string } {
  const params = new URLSearchParams(search);
  const courseId = Number(courseIdFor(params.get("kj")));
  const activityId = params.get("n") || params.get("act");
  return {
    ...(Number.isSafeInteger(courseId) && courseId > 0 ? { courseId } : {}),
    ...(activityId && activityId.length <= 64 ? { activityId } : {}),
  };
}

/** Ask the backend. Rejects only on network failure or cancellation. */
export async function askServer(text: string, signal: AbortSignal): Promise<ServerAnswer> {
  const owner = `${backendState().me?.userId ?? ""}:${new Date().toDateString()}`;
  if (history.owner !== owner) history = { owner, turns: [] };
  const reply = await postAssistant({
    text: text.slice(0, 500),
    context: { ...place(typeof location === "undefined" ? "" : location.search), history: history.turns },
  }, signal);
  if (!reply.ok) return { ok: false, say: assistantFailure(reply.status, reply.retryAfter), actions: [] };
  const data = reply.data as { say?: unknown; actions?: unknown } | null;
  if (!data || typeof data.say !== "string" || !Array.isArray(data.actions)) {
    return { ok: false, say: "AI 응답을 처리하지 못했습니다", actions: [] };
  }
  const actions = data.actions.map(action).filter((row): row is AssistantAction => row !== null);
  history.turns = [...history.turns, { text: text.slice(0, 500), say: data.say.slice(0, 300) }].slice(-MAX_TURNS);
  return { ok: true, say: data.say, actions };
}
