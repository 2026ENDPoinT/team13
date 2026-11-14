import { courses, activities, type Activity, type AppState, type Course } from "../../data.ts";
import { getState } from "../../store.ts";
import { TODAY, activityById, activityHref, typeLabel } from "../format.ts";

/** Data the assistant reads: the sample courses/activities, or injected ones. */
export interface AssistantCtx {
  courses: Course[];
  activities: Activity[];
  today: string;
  positions: Record<string, number>;
}

export type ResultKind = "go" | "choose" | "plan" | "revise" | "reject" | "none" | "clarify";

/** Which words of the request matched a course and an intent (local resolver). */
export interface MatchedTerms {
  course: { text: string; name: string } | null;
  intent: { text: string; label: string } | null;
}

/** A resolved request (local resolver or `/api/assistant`). */
export interface AssistantResult {
  kind: ResultKind;
  intent: string | null;
  courseId: string | null;
  targets: string[];
  say: string;
  matched?: MatchedTerms;
  /** revise/reject: the plan the answer was made for. */
  baseRunId?: string | null;
  baseRunStatus?: RunStatus;
}

export type RunStatus = "ready" | "running" | "paused" | "done" | "stopped";
export type StepStatus = "pending" | "done" | "skipped";

/**
 * What a plan step from the signed-in assistant (`POST /api/assistant` `plan.steps`) carries itself, instead of being
 * looked up in the sample `data.ts`: what it is, where it opens, and how it is shown.
 */
export interface ServerStepInfo {
  /** The backend's step id (kept for the record; the step's `activityId` is the screen id its `href` opens). */
  id: string;
  kind: string;
  title: string;
  course: string;
  due: string | null;
  href: string;
  external: boolean;
}

export interface PlanStep {
  /** Sample steps: the `data.ts` activity id. Server steps: the id of the screen `href` opens (`srv-lecture-12`). */
  activityId: string;
  mode: "auto" | "confirm";
  status: StepStatus;
  server?: ServerStepInfo;
}

/** Today's plan run (`state.assistant.run`). */
export interface PlanRun {
  id: string;
  date: string;
  steps: PlanStep[];
  cursor: number;
  status: RunStatus;
  version?: number;
  selectionVersion: number;
  customOrder?: boolean;
  /** Version 1 runs: indexes of skipped steps. */
  skipped?: number[];
  /** Server runs: the signed-in user they belong to. Sample runs have none. */
  owner?: string;
}

/** A `choose` result waiting in the 요청함. */
export interface AssistantRequest {
  id: string;
  text: string;
  at: string;
  kind: ResultKind;
  intent: string | null;
  courseId: string | null;
  targets: string[];
  chosen: string | null;
  say: string;
}

export interface ConversationTurn {
  text: string;
  result: Pick<AssistantResult, "kind" | "intent" | "courseId" | "targets" | "say">;
}

/** What the assistant keeps in `state.assistant`. */
export interface AssistantState {
  date: string;
  requests: AssistantRequest[];
  run?: PlanRun | null;
  conversation?: ConversationTurn[];
}

/** `state.assistant` with the fields the assistant stores. */
export function assistantOf(state: AppState): AssistantState {
  return state.assistant;
}

/** A saved survey answer marked done (`state.surveys[id].done`). */
export function surveyDone(state: AppState, activityId: string): boolean {
  // AppState.surveys is untyped; a saved survey answer carries `done`.
  const survey = state.surveys?.[activityId] as { done?: boolean } | undefined;
  return Boolean(survey?.done);
}

export const compact = (value: string | null | undefined): string => String(value || "").toLowerCase().replace(/\s+/g, "");
export const defaultCtx = (): AssistantCtx => ({ courses, activities, today: TODAY, positions: getState().player?.positions || {} });
export const enrolled = (course: Course): boolean => course.enrolled && !course.closed;

export function progressOf(activity: Activity, positions: Record<string, number> = {}): number {
  const seconds = positions[activity.id];
  const progress = Number.isFinite(seconds) && (activity.minutes || 0) > 0
    ? seconds / ((activity.minutes || 0) * 60) * 100 : Number(activity.progress) || 0;
  return Math.max(0, Math.min(100, progress));
}

export function ordered(items: Activity[], ctx: Pick<AssistantCtx, "positions">): Activity[] {
  return items.slice().sort((a, b) => String(a.due || "9999").localeCompare(String(b.due || "9999"))
    || progressOf(b, ctx.positions) - progressOf(a, ctx.positions));
}

export function todayLectures(ctx: AssistantCtx = defaultCtx()): string[] {
  const ids = new Set(ctx.courses.filter(enrolled).map((course) => course.id));
  return ordered(ctx.activities.filter((a) => ids.has(a.courseId) && a.type === "video" && a.status === "ongoing"
    && (!a.due || a.due.slice(0, 10) >= ctx.today)), ctx).map((a) => a.id);
}

// Shared URL construction keeps hero actions and request execution identical.
export function activityLink(activity: Activity, { autoplay = true }: { autoplay?: boolean } = {}): string {
  const href = activityHref(activity);
  return activity.type === "video" && autoplay ? `${href}${href.includes("?") ? "&" : "?"}autoplay=1` : href;
}

const pad = (n: number): string => String(Math.floor(n)).padStart(2, "0");

// A saved position is where the player resumes; activity.progress is only attendance progress.
function resumeOf(activity: Activity, positions: Record<string, number> = {}): string {
  const seconds = positions[activity.id];
  if (Number.isFinite(seconds) && seconds >= 1) return `${pad(seconds / 60)}:${pad(seconds % 60)}부터`;
  const progress = Math.round(Number(activity.progress) || 0);
  return progress > 0 ? `진도 ${progress}%` : "";
}

interface Description {
  title: string;
  meta: string;
}

function description(activity: Activity, ctx: AssistantCtx): Description {
  const course = ctx.courses.find((c) => c.id === activity.courseId);
  const meta: string[] = [];
  if (activity.minutes) meta.push(`${activity.minutes}분`);
  if (activity.due) meta.push(`${activity.due.slice(5, 10).replace("-", "/")} ${activity.due.slice(11, 16)}까지`.trim());
  const resume = activity.type === "video" ? resumeOf(activity, ctx.positions) : "";
  if (resume) meta.push(resume);
  return { title: `${course?.ko || activity.courseId} · ${activity.title}`, meta: meta.join(" · ") };
}

/** One status line for a result, shared by local and Gemini results so both read the same. */
export function sayFor(
  { kind, intent, courseId, targets }: Pick<AssistantResult, "kind" | "targets"> & Partial<Pick<AssistantResult, "intent" | "courseId">>,
  text: string,
  ctx: AssistantCtx = defaultCtx(),
): string {
  if (kind === "none" || (!targets.length && !courseId)) return `'${text}'와 맞는 항목이 없습니다`;
  const course = ctx.courses.find((c) => c.id === courseId);
  if (!targets.length) return `${course?.ko} 강의실로 이동`;
  if (kind === "choose") {
    const label = compact(text).includes("오늘") && intent === "video" ? "오늘 들을 강의"
      : `${course ? `${course.ko} ` : ""}${intent === "course" ? "활동" : typeLabel(intent || "")}`;
    return `${label} ${targets.length}개`;
  }
  const activity = ctx.activities.find((a) => a.id === targets[0]);
  if (!activity) return `'${text}'와 맞는 항목이 없습니다`;
  const owner = ctx.courses.find((c) => c.id === activity.courseId);
  if (activity.type === "video") return `${owner?.ko} ${activity.week}주차 ${activity.title} 재생`;
  if (activity.type === "report") return `${owner?.ko} ${activity.title} 제출 화면으로 이동`;
  return `${owner?.ko} ${activity.title} 열기`;
}

export function describe(activityId: string): Description {
  const ctx = defaultCtx();
  const activity = ctx.activities.find((a) => a.id === activityId);
  return activity ? description(activity, ctx) : { title: "찾을 수 없는 활동", meta: "" };
}

export interface NextAction extends Description {
  id: string;
  kind: "deadline" | "lecture";
  cta: string;
  href: string;
}

export function nextActions(ctx: AssistantCtx = defaultCtx()): NextAction[] {
  const ids = new Set(ctx.courses.filter(enrolled).map((course) => course.id));
  // Same status predicate as openTodos(), without its display-only pinned ordering.
  const deadline = ordered(ctx.activities.filter((a) => ids.has(a.courseId) && a.type !== "video"
    && ["missing", "ongoing", "scheduled"].includes(a.status) && a.due), ctx)[0];
  const lecture = ctx.activities.find((a) => a.id === todayLectures(ctx)[0]);
  const picks: { activity: Activity | undefined; kind: NextAction["kind"]; cta: string }[] = [
    { activity: deadline, kind: "deadline", cta: "바로 가기" },
    { activity: lecture, kind: "lecture", cta: "이어 듣기" },
  ];
  return picks
    .filter((pick): pick is { activity: Activity; kind: NextAction["kind"]; cta: string } => Boolean(pick.activity))
    .sort((a, b) => String(a.activity.due || "9999").localeCompare(String(b.activity.due || "9999")))
    .map(({ activity, kind, cta }) => ({
      id: activity.id, kind, ...description(activity, ctx), cta, href: activityLink(activity),
    }));
}


export function hrefFor(activityId: string, options?: { autoplay?: boolean }): string | null {
  const activity = activityById(activityId);
  return activity ? activityLink(activity, options) : null;
}

export function isDone(activityId: string): boolean {
  const activity = activityById(activityId);
  return Boolean(getState().submissions?.[activityId] || (activity && (["done", "submitted", "graded"].includes(activity.status)
    || progressOf(activity, getState().player?.positions) >= 90)));
}
