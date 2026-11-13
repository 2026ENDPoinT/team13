import { courses, activities, type Activity } from "../../data.ts";
import { getState, save, update } from "../../store.ts";
import { currentUserId } from "../backend.ts";
import { TODAY, activityById, myDayGroups } from "../format.ts";
import { todayLectures, hrefFor, isDone, assistantOf, surveyDone, describe } from "./activity-context.ts";
import type { AssistantCtx, PlanRun, PlanStep, ServerStepInfo } from "./activity-context.ts";

export type GroupId = "video" | "report" | "other";

export interface PlanGroup {
  id: GroupId;
  label: string;
  steps: { step: PlanStep; index: number }[];
}

/** The current step and its index in the run. */
export interface CurrentStep extends PlanStep {
  index: number;
}

/** Binds a completion to the selection it was made for. */
export interface CompletionToken {
  runId: string;
  index: number;
  activityId: string;
  selectionVersion: number;
}

export type CompletionEvent = "video-ended" | "report-submitted" | "survey-saved";
type Navigate = (href: string) => void;

/** How a step is shown and where it opens, whether it came from the sample data or from the server's plan. */
export interface StepInfo {
  type: string;
  title: string;
  meta: string;
  href: string | null;
  external: boolean;
}

const typeGroup = (type: string | undefined): GroupId => type === "video" || type === "report" ? type : "other";
const groupId = (activity: Activity | null | undefined): GroupId => typeGroup(activity?.type);
const stepGroup = (step: PlanStep): GroupId => typeGroup(step.server ? step.server.kind : activityById(step.activityId)?.type);
const groupOrder: Record<GroupId, number> = { video: 0, report: 1, other: 2 };
const terminal = (step: PlanStep): boolean => step.status === "done" || step.status === "skipped";
const compareActivities = (a: Activity | undefined, b: Activity | undefined): number => groupOrder[groupId(a)] - groupOrder[groupId(b)]
  || (a?.due ? b?.due ? a.due.localeCompare(b.due) : -1 : b?.due ? 1 : 0);
// The event that proves each native server step finished; other kinds are finished with the plan's own next control.
const SERVER_EVENT: Record<string, CompletionEvent> = { video: "video-ended", report: "report-submitted", survey: "survey-saved" };

/** "11/14 23:59까지" from a due date-time, or "". */
function dueText(due: string | null | undefined): string {
  const match = /^\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(due || ""));
  return match ? `${match[1]}/${match[2]} ${match[3]}:${match[4]}까지` : "";
}

export function stepInfo(step: PlanStep): StepInfo {
  if (step.server) {
    const { kind, title, course, due, href, external } = step.server;
    return { type: kind, title: course ? `${course} · ${title}` : title, meta: dueText(due), href, external };
  }
  const { title, meta } = describe(step.activityId);
  return { type: activityById(step.activityId)?.type ?? "report", title, meta, href: hrefFor(step.activityId), external: false };
}

/** A server step the student finishes outside a STack completion event: confirm steps, school items, new-tab steps. */
export function manualStep(step: PlanStep): boolean {
  const server = step.server;
  return Boolean(server && (step.mode === "confirm" || server.external || !SERVER_EVENT[server.kind] || /[?&]n=school-/.test(server.href)));
}

export function orderPlan(activityIds: readonly string[], activityList: Activity[] = activities): string[] {
  const byId = new Map(activityList.map((activity) => [activity.id, activity]));
  return [...activityIds].sort((a, b) => compareActivities(byId.get(a), byId.get(b)));
}

export function buildPlan(ctx?: AssistantCtx): string[] {
  const context: AssistantCtx = ctx || { courses, activities, today: TODAY, positions: getState().player?.positions || {} };
  const lectures = todayLectures(context);
  // Production shares the home card's exact scope; injected dates/data use the same D-0…D-3 predicate.
  const until = new Date(Date.parse(`${context.today}T00:00:00Z`) + 3 * 86400000).toISOString().slice(0, 10);
  const soon = ctx ? context.activities.filter((item) => ["missing", "ongoing", "scheduled"].includes(item.status)
    && item.due && item.due.slice(0, 10) >= context.today && item.due.slice(0, 10) <= until)
    : myDayGroups(getState()).soon.items;
  const byId = new Map(context.activities.map((item) => [item.id, item]));
  return orderPlan([...new Set([...lectures, ...soon.map((item: Activity) => item.id)])]
    .filter((id) => byId.get(id)?.type !== "test"), context.activities);
}

/** 목업의 "오늘 할 일": 홈 히어로가 세는 사흘 안 마감 활동(시험 제외, 완료 제외). 영상 → 과제 순이라 기계학습 → 고급웹 순이 된다. */
export function todayDeadlinePlan(): string[] {
  const state = getState();
  return orderPlan(myDayGroups(state).soon.items
    .filter((item: Activity) => item.type !== "test" && !isDone(item.id) && !surveyDone(state, item.id))
    .map((item: Activity) => item.id));
}

function migrateRun(run: PlanRun): void {
  if (run.version === 2) return;
  const active = run.steps[run.cursor];
  const skipped = new Set(run.skipped || []);
  run.steps.forEach((step, index) => {
    // Legacy cursors imply a completed prefix, but cannot prove an unfinished video was watched.
    step.status = skipped.has(index) ? "skipped" : index < run.cursor
      && (activityById(step.activityId)?.type !== "video" || isDone(step.activityId)
        || getState().player?.watched?.[step.activityId]) ? "done" : "pending";
  });
  run.steps.sort((a, b) => groupOrder[groupId(activityById(a.activityId))] - groupOrder[groupId(activityById(b.activityId))]);
  const pending = run.steps.findIndex((step) => !terminal(step));
  run.cursor = run.status !== "ready" && active && !terminal(active) ? run.steps.indexOf(active)
    : pending < 0 ? run.steps.length : pending;
  if (pending < 0 && run.status !== "stopped") run.status = "done";
  else if (run.status === "done") run.status = "paused";
  run.version = 2;
  run.selectionVersion = 0;
  delete run.skipped;
  save();
}

export function runState(): PlanRun | null {
  const run = assistantOf(getState()).run;
  if (!run) return null;
  // Normalized while rendering (the tray reads it), so these are saved without another render.
  if (run.date !== TODAY) {
    assistantOf(getState()).run = null;
    save();
    return null;
  }
  // A server run belongs to the signed-in user who asked for it; a sample run only to the sample mode.
  if ((run.owner ?? null) !== currentUserId()) return null;
  migrateRun(run);
  return run.status === "stopped" ? null : run;
}

function storeRun(steps: PlanStep[], extra: Partial<PlanRun> = {}): PlanRun {
  const run: PlanRun = { id: `run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, date: TODAY,
    steps, cursor: 0, status: "ready", version: 2, selectionVersion: 0, ...extra };
  update((state) => {
    if (state.assistant?.date !== TODAY) state.assistant = { date: TODAY, requests: [] };
    assistantOf(state).run = run;
  });
  return run;
}

export function createRun(activityIds: readonly string[], { preserveOrder = false }: { preserveOrder?: boolean } = {}): PlanRun {
  let ordered: string[];
  if (preserveOrder) {
    const groups = new Map<GroupId, string[]>();
    for (const id of activityIds) {
      const type = groupId(activityById(id));
      if (!groups.has(type)) groups.set(type, []);
      groups.get(type)?.push(id);
    }
    ordered = [...groups.values()].flat();
  } else ordered = orderPlan(activityIds);
  const steps: PlanStep[] = ordered.map((activityId) => ({ activityId,
    mode: activityById(activityId)?.type === "video" ? "auto" : "confirm", status: "pending" }));
  return storeRun(steps, preserveOrder ? { customOrder: true } : {});
}

/** A step of the server's plan, with `key`: the id of the screen its `href` opens (`resolveScreen(...).id`). */
export interface ServerPlanStep extends ServerStepInfo {
  mode: "auto" | "confirm";
  key: string;
}

/**
 * The signed-in assistant's plan as today's run for `owner`. The server's order is kept inside each group (영상, 과제,
 * 기타, as for sample plans); a key seen twice keeps its first step, since a screen can only stand for one step.
 */
export function createServerRun(planSteps: readonly ServerPlanStep[], owner: string): PlanRun {
  const seen = new Set<string>();
  const steps: PlanStep[] = planSteps.filter((step) => !seen.has(step.key) && Boolean(seen.add(step.key)))
    .map(({ key, mode, ...server }) => ({ activityId: key, mode, status: "pending" as const, server }));
  steps.sort((a, b) => groupOrder[stepGroup(a)] - groupOrder[stepGroup(b)]);
  return storeRun(steps, { owner });
}

export function planGroups(run: PlanRun | null | undefined): PlanGroup[] {
  const groups: PlanGroup[] = [{ id: "video", label: "영상", steps: [] }, { id: "report", label: "과제", steps: [] },
    { id: "other", label: "기타", steps: [] }];
  run?.steps.forEach((step, index) => {
    groups[groupOrder[stepGroup(step)]].steps.push({ step, index });
  });
  if (run?.customOrder) groups.sort((a, b) => (a.steps[0]?.index ?? Infinity) - (b.steps[0]?.index ?? Infinity));
  return groups.filter((group) => group.id !== "other" || group.steps.length);
}

export function progressCount(run: PlanRun | null | undefined): number {
  return run?.steps.filter(terminal).length || 0;
}

export function currentStep(): CurrentStep | null {
  const run = runState();
  if (!run || !["ready", "running", "paused"].includes(run.status)) return null;
  const step = run.steps[run.cursor];
  return step ? { index: run.cursor, ...step } : null;
}

// In-app steps go through the router; new-tab steps (the school's e-Class) open beside the plan, which stays here.
function navigateCurrent(navigate: Navigate): void {
  const step = currentStep();
  const info = step && stepInfo(step);
  if (!info?.href) return;
  if (info.external) window.open(info.href, "_blank", "noopener,noreferrer");
  else navigate(info.href);
}

export function startRun({ navigate }: { navigate: Navigate }): void {
  const run = runState();
  if (!run || run.status !== "ready") return;
  update(() => {
    const pending = run.steps.findIndex((step) => !terminal(step));
    run.cursor = pending < 0 ? run.steps.length : pending;
    run.status = pending < 0 ? "done" : "running";
  });
  navigateCurrent(navigate);
}

export function selectStep(index: number, { navigate }: { navigate: Navigate }): boolean {
  const run = runState();
  if (!run || !["running", "paused"].includes(run.status) || !Number.isInteger(index)
    || !run.steps[index] || terminal(run.steps[index])) return false;
  if (index === run.cursor && run.status === "running") return true;
  update(() => {
    if (index !== run.cursor) run.selectionVersion += 1;
    run.cursor = index;
    run.status = "running";
  });
  navigateCurrent(navigate);
  return true;
}

export function reorderStep(runId: string, from: number, to: number): boolean {
  const run = runState();
  if (!run || run.id !== runId || !["ready", "running", "paused"].includes(run.status)
    || !Number.isInteger(from) || !Number.isInteger(to) || from === to
    || !run.steps[from] || !run.steps[to]) return false;
  const group = stepGroup(run.steps[from]);
  if (stepGroup(run.steps[to]) !== group) return false;
  const slots: number[] = [];
  run.steps.forEach((step, index) => {
    if (!terminal(step) && (run.status === "ready" || index !== run.cursor)
      && stepGroup(step) === group) slots.push(index);
  });
  const source = slots.indexOf(from);
  const target = slots.indexOf(to);
  if (source < 0 || target < 0) return false;
  update(() => {
    const pending = slots.map((index) => run.steps[index]);
    pending.splice(target, 0, pending.splice(source, 1)[0]);
    slots.forEach((index, position) => { run.steps[index] = pending[position]; });
  });
  return true;
}

export function advance({ navigate, skip = false }: { navigate: Navigate; skip?: boolean }): void {
  const run = runState();
  if (!run || run.status !== "running" || !run.steps[run.cursor]) return;
  update(() => {
    run.steps[run.cursor].status = skip ? "skipped" : "done";
    const pending = run.steps.findIndex((step) => !terminal(step));
    run.cursor = pending < 0 ? run.steps.length : pending;
    run.selectionVersion += 1;
    if (pending < 0) run.status = "done";
  });
  navigateCurrent(navigate);
}

// Bind completion to the current selection, including switches away and back to the same activity.
export function completionToken(activityId: string): CompletionToken | null {
  const run = runState();
  if (!run || run.status !== "running" || run.steps[run.cursor]?.activityId !== activityId) return null;
  return { runId: run.id, index: run.cursor, activityId, selectionVersion: run.selectionVersion };
}

export function completeStep(token: CompletionToken | null | undefined, { navigate, event }: { navigate: Navigate; event: CompletionEvent }): boolean {
  const run = runState();
  if (!token || !run || run.status !== "running" || run.id !== token.runId || run.cursor !== token.index
    || run.selectionVersion !== token.selectionVersion
    || run.steps[run.cursor]?.activityId !== token.activityId) return false;
  const step = run.steps[run.cursor];
  const server = step.server;
  const activity = server ? undefined : activityById(token.activityId);
  const state = getState();
  // A native server step is finished by its own STack event (the backend has the record); school and new-tab steps never are.
  const completed = server
    ? SERVER_EVENT[server.kind] === event && !server.external && !/[?&]n=school-/.test(server.href)
    : event === "video-ended" && activity?.type === "video"
      ? isDone(activity.id) || Boolean(state.player?.watched?.[activity.id])
      : event === "report-submitted" && activity?.type === "report"
        ? Boolean(state.submissions?.[activity.id])
        : event === "survey-saved" && activity?.type === "survey" && surveyDone(state, activity.id);
  if (!completed) return false;
  advance({ navigate });
  return true;
}

export function pauseRun(): void {
  const run = runState();
  if (run && run.status === "running") update(() => { run.status = "paused"; });
}

export function resumeRun({ navigate }: { navigate: Navigate }): void {
  const run = runState();
  if (!run || run.status !== "paused") return;
  update(() => { run.status = "running"; });
  navigateCurrent(navigate);
}

export function stopRun(): void {
  const run = runState();
  if (run) update(() => { run.status = "stopped"; });
}

export function stepStatus(index: number): "pending" | "done" | "skipped" | "running" | "waiting" {
  const run = runState();
  if (!run || !run.steps[index]) return "pending";
  const step = run.steps[index];
  if (terminal(step)) return step.status;
  if (index === run.cursor && run.status === "running") return step.mode === "auto" ? "running" : "waiting";
  return "pending";
}

/** The key a server step is matched on: the screen id its `href` opens (a lecture's detail and player share one) and the course. */
export function screenKey(screenId: string, params: URLSearchParams): string {
  return `${screenId}@${params.get("kj") || ""}`;
}

/**
 * Pauses a running plan when the screen is not its current step. Sample steps compare the activity id (`n`); server
 * steps the `screenKey`. A new-tab step never leaves this screen.
 */
export function observeScreen(activityId: string, key: string): void {
  const run = runState();
  const step = run?.steps[run.cursor];
  if (run?.status !== "running" || step?.server?.external) return;
  if (step?.activityId !== (step?.server ? key : activityId)) pauseRun();
}
