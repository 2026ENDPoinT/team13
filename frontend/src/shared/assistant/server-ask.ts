// The signed-in assistant: every command-bar input goes to `POST /api/assistant`, and the backend decides the answer
// and where it leads (`actions`, and for "do today's work" requests a `plan`). This module only sends the text with the
// current course/activity and the last few turns, checks the answer's shape, and keeps the in-app/external distinction
// the backend states.
import { backendState, courseIdFor } from "../backend.ts";
import { schoolHref } from "../school-content.tsx";
import type { ServerStepInfo } from "./activity-context.ts";
import { assistantFailure, postAssistant } from "./request.ts";

/** One place the answer leads to: an in-app path (router) or an external address (new tab). */
export interface AssistantAction {
  label: string;
  href: string;
  external: boolean;
}

/** One step of the answer's plan, as the backend sent it (checked). */
export interface AnswerStep extends ServerStepInfo {
  mode: "auto" | "confirm";
}

export interface ServerAnswer {
  ok: boolean;
  say: string;
  actions: AssistantAction[];
  /** The plan's steps when the answer is a plan (`plan.steps`), else null. */
  plan: AnswerStep[] | null;
}

interface Turn {
  text: string;
  say: string;
}

const MAX_TURNS = 4;
/** Same-day conversation of this signed-in user, in memory only. */
let history: { owner: string; turns: Turn[] } = { owner: "", turns: [] };

/** `href` when it may be followed: the school's e-Class for new-tab links, else an in-app path. */
function safeHref(href: unknown, external: unknown): string | null {
  if (typeof href !== "string") return null;
  // External links are the school's e-Class pages (imported quizzes and surveys); nothing else opens a tab.
  if (external === true) return schoolHref(href);
  // In-app paths only: one leading slash, no scheme-relative or backslash tricks.
  return external === false && href.startsWith("/") && !href.startsWith("//") && !href.includes("\\") ? href : null;
}

function action(value: unknown): AssistantAction | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const { label, href, external } = value as Record<string, unknown>;
  const safe = safeHref(href, external);
  return safe && typeof label === "string" && label.trim() ? { label, href: safe, external: external === true } : null;
}

function step(value: unknown): AnswerStep | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const { id, kind, title, course, due, href, external, mode } = value as Record<string, unknown>;
  const safe = safeHref(href, external);
  if (!safe || typeof id !== "string" || !id || typeof kind !== "string" || typeof title !== "string" || !title.trim()
    || (mode !== "auto" && mode !== "confirm") || (due != null && typeof due !== "string")) return null;
  return { id, kind, title, course: typeof course === "string" ? course : "", due: typeof due === "string" ? due : null,
    href: safe, external: external === true, mode };
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
  if (!reply.ok) return { ok: false, say: assistantFailure(reply.status, reply.retryAfter), actions: [], plan: null };
  const data = reply.data as { say?: unknown; actions?: unknown; plan?: unknown } | null;
  if (!data || typeof data.say !== "string" || !Array.isArray(data.actions)) {
    return { ok: false, say: "AI 응답을 처리하지 못했습니다", actions: [], plan: null };
  }
  const actions = data.actions.map(action).filter((row): row is AssistantAction => row !== null);
  const rawSteps = data.plan && typeof data.plan === "object" && "steps" in data.plan ? data.plan.steps : null;
  const steps = Array.isArray(rawSteps) ? rawSteps.map(step).filter((row): row is AnswerStep => row !== null) : [];
  history.turns = [...history.turns, { text: text.slice(0, 500), say: data.say.slice(0, 300) }].slice(-MAX_TURNS);
  return { ok: true, say: data.say, actions, plan: steps.length ? steps : null };
}
