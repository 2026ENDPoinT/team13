import { courses, activities } from "../../data.js";
import { getState, update } from "../../store.js";
import { TODAY, activityById, myDayGroups } from "../format.js";
import { todayLectures, hrefFor, isDone } from "./activity-context.js";

const groupId = (activity) => ["video", "report"].includes(activity?.type) ? activity.type : "other";
const groupOrder = { video: 0, report: 1, other: 2 };
const terminal = (step) => step.status === "done" || step.status === "skipped";
const compareActivities = (a, b) => groupOrder[groupId(a)] - groupOrder[groupId(b)]
  || (a?.due ? b?.due ? a.due.localeCompare(b.due) : -1 : b?.due ? 1 : 0);

export function orderPlan(activityIds, activityList = activities) {
  const byId = new Map(activityList.map((activity) => [activity.id, activity]));
  return [...activityIds].sort((a, b) => compareActivities(byId.get(a), byId.get(b)));
}

export function buildPlan(ctx) {
  const context = ctx || { courses, activities, today: TODAY, positions: getState().player?.positions || {} };
  const lectures = todayLectures(context);
  // Production shares the home card's exact scope; injected dates/data use the same D-0…D-3 predicate.
  const until = new Date(Date.parse(`${context.today}T00:00:00Z`) + 3 * 86400000).toISOString().slice(0, 10);
  const soon = ctx ? context.activities.filter((item) => ["missing", "ongoing", "scheduled"].includes(item.status)
    && item.due && item.due.slice(0, 10) >= context.today && item.due.slice(0, 10) <= until)
    : myDayGroups(getState()).soon.items;
  const byId = new Map(context.activities.map((item) => [item.id, item]));
  return orderPlan([...new Set([...lectures, ...soon.map((item) => item.id)])]
    .filter((id) => byId.get(id)?.type !== "test"), context.activities);
}

function migrateRun(run) {
  if (run.version === 2) return;
  update(() => {
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
  });
}

export function runState() {
  const run = getState().assistant?.run;
  if (!run) return null;
  if (run.date !== TODAY) {
    update((state) => { state.assistant.run = null; });
    return null;
  }
  migrateRun(run);
  return run.status === "stopped" ? null : run;
}

export function createRun(activityIds, { preserveOrder = false } = {}) {
  let ordered;
  if (preserveOrder) {
    const groups = new Map();
    for (const id of activityIds) {
      const type = groupId(activityById(id));
      if (!groups.has(type)) groups.set(type, []);
      groups.get(type).push(id);
    }
    ordered = [...groups.values()].flat();
  } else ordered = orderPlan(activityIds);
  const steps = ordered.map((activityId) => ({ activityId,
    mode: activityById(activityId)?.type === "video" ? "auto" : "confirm", status: "pending" }));
  const run = { id: `run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, date: TODAY,
    steps, cursor: 0, status: "ready", version: 2, selectionVersion: 0 };
  if (preserveOrder) run.customOrder = true;
  update((state) => {
    if (state.assistant?.date !== TODAY) state.assistant = { date: TODAY, requests: [] };
    state.assistant.run = run;
  });
  return run;
}

export function planGroups(run) {
  const groups = [{ id: "video", label: "영상", steps: [] }, { id: "report", label: "과제", steps: [] },
    { id: "other", label: "기타", steps: [] }];
  run?.steps.forEach((step, index) => {
    groups[groupOrder[groupId(activityById(step.activityId))]].steps.push({ step, index });
  });
  if (run?.customOrder) groups.sort((a, b) => (a.steps[0]?.index ?? Infinity) - (b.steps[0]?.index ?? Infinity));
  return groups.filter((group) => group.id !== "other" || group.steps.length);
}

export function progressCount(run) {
  return run?.steps.filter(terminal).length || 0;
}

export function currentStep() {
  const run = runState();
  if (!run || !["ready", "running", "paused"].includes(run.status)) return null;
  const step = run.steps[run.cursor];
  return step ? { index: run.cursor, ...step } : null;
}

export function stepHref(activityId) {
  return hrefFor(activityId);
}

function navigateCurrent(navigate) {
  const step = currentStep();
  const href = step && stepHref(step.activityId);
  if (href) navigate(href);
}

export function startRun({ navigate }) {
  const run = runState();
  if (run?.status !== "ready") return;
  update(() => {
    const pending = run.steps.findIndex((step) => !terminal(step));
    run.cursor = pending < 0 ? run.steps.length : pending;
    run.status = pending < 0 ? "done" : "running";
  });
  navigateCurrent(navigate);
}

export function selectStep(index, { navigate }) {
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

export function reorderStep(runId, from, to) {
  const run = runState();
  if (!run || run.id !== runId || !["ready", "running", "paused"].includes(run.status)
    || !Number.isInteger(from) || !Number.isInteger(to) || from === to
    || !run.steps[from] || !run.steps[to]) return false;
  const group = groupId(activityById(run.steps[from].activityId));
  if (groupId(activityById(run.steps[to].activityId)) !== group) return false;
  const slots = [];
  run.steps.forEach((step, index) => {
    if (!terminal(step) && (run.status === "ready" || index !== run.cursor)
      && groupId(activityById(step.activityId)) === group) slots.push(index);
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

export function advance({ navigate, skip = false }) {
  const run = runState();
  if (run?.status !== "running" || !run.steps[run.cursor]) return;
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
export function completionToken(activityId) {
  const run = runState();
  if (run?.status !== "running" || run.steps[run.cursor]?.activityId !== activityId) return null;
  return { runId: run.id, index: run.cursor, activityId, selectionVersion: run.selectionVersion };
}

export function completeStep(token, { navigate, event }) {
  const run = runState();
  if (!token || run?.status !== "running" || run.id !== token.runId || run.cursor !== token.index
    || run.selectionVersion !== token.selectionVersion
    || run.steps[run.cursor]?.activityId !== token.activityId) return false;
  const activity = activityById(token.activityId);
  const state = getState();
  const completed = event === "video-ended" && activity?.type === "video"
    ? isDone(activity.id) || Boolean(state.player?.watched?.[activity.id])
    : event === "report-submitted" && activity?.type === "report"
      ? Boolean(state.submissions?.[activity.id])
      : event === "survey-saved" && activity?.type === "survey" && Boolean(state.surveys?.[activity.id]?.done);
  if (!completed) return false;
  advance({ navigate });
  return true;
}

export function pauseRun() {
  const run = runState();
  if (run?.status === "running") update(() => { run.status = "paused"; });
}

export function resumeRun({ navigate }) {
  const run = runState();
  if (run?.status !== "paused") return;
  update(() => { run.status = "running"; });
  navigateCurrent(navigate);
}

export function stopRun() {
  const run = runState();
  if (run) update(() => { run.status = "stopped"; });
}

export function stepStatus(index) {
  const run = runState();
  if (!run || !run.steps[index]) return "pending";
  const step = run.steps[index];
  if (terminal(step)) return step.status;
  if (index === run.cursor && run.status === "running") return step.mode === "auto" ? "running" : "waiting";
  return "pending";
}

export function observeScreen(activityId) {
  const run = runState();
  if (run?.status === "running" && run.steps[run.cursor]?.activityId !== activityId) pauseRun();
}
