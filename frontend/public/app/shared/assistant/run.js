import { courses, activities } from "../../data.js";
import { getState, update } from "../../store.js";
import { TODAY, activityById } from "../format.js";
import { currentRole, routeFor } from "../routes.js";
import { activityLink, progressOf, sayFor, hrefFor, isDone } from "./activity-context.js";
import { buildPlan, orderPlan, createRun, runState, stopRun } from "./plan.js";

const normalize = (text) => String(text || "").toLowerCase().replace(/\s+/g, "");
const intents = new Set(["video", "report", "test", "survey", "material", "zoom", "course", "plan"]);

function isRevision(targets) {
  return targets.length > 0 && new Set(targets).size === targets.length
    && targets.every((id) => {
      const activity = activityById(id);
      return activity && activity.type !== "test";
    });
}

export async function ask(text, { signal } = {}) {
  const state = getState();
  const matched = { course: null, intent: null };
  const failure = (say) => ({ kind: "none", intent: null, courseId: null, targets: [], matched, say });
  const finish = (result) => {
    if (signal?.aborted) return failure("요청을 취소했습니다");
    update((current) => {
      if (current.assistant?.date !== TODAY) current.assistant = { date: TODAY, requests: [] };
      current.assistant.conversation = [...(current.assistant.conversation || []), {
        text: text.slice(0, 500),
        result: { kind: result.kind, intent: result.intent, courseId: result.courseId,
          targets: [...result.targets], say: result.say.slice(0, 300) },
      }].slice(-4);
    });
    return result;
  };
  const activeCourses = courses.filter((c) => c.enrolled && !c.closed);
  const activeIds = new Set(activeCourses.map((c) => c.id));
  const available = activities.filter((a) => activeIds.has(a.courseId));
  const run = runState();
  const baseRunId = ["ready", "running", "paused"].includes(run?.status) ? run.id : null;
  const baseRunStatus = run?.status;
  const controller = new AbortController();
  const cancel = () => controller.abort();
  if (signal?.aborted) cancel();
  else signal?.addEventListener("abort", cancel, { once: true });
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch("/api/assistant", {
      method: "POST", headers: { "Content-Type": "application/json" }, signal: controller.signal,
      body: JSON.stringify({ text, today: TODAY,
        courses: activeCourses.map(({ id, ko, en }) => ({ id, ko, en })),
        activities: available.map((activity) => ({
          id: activity.id, courseId: activity.courseId, type: activity.type, title: activity.title,
          due: activity.due, status: isDone(activity.id) || state.surveys?.[activity.id]?.done ? "done" : activity.status,
          progress: progressOf(activity, state.player?.positions),
        })),
        context: {
          history: state.assistant?.date === TODAY ? state.assistant.conversation || [] : [],
          todayTargets: buildPlan().filter((id) => !isDone(id) && !state.surveys?.[id]?.done),
          currentActivityId: typeof location === "undefined" ? null : new URLSearchParams(location.search).get("n"),
          run: run?.date === TODAY && run.status !== "stopped"
            ? { status: run.status, cursor: run.cursor, activityIds: run.steps.map((step) => step.activityId) } : null,
        },
      }),
    });
    if (!response.ok) return failure(response.status === 503
      ? "AI 연결이 설정되지 않았습니다"
      : response.status === 429 ? "AI 요청이 많습니다 잠시 후 다시 시도하세요" : "AI 요청을 처리하지 못했습니다");
    const data = await response.json();
    if (!data || !["go", "choose", "plan", "revise", "reject", "none", "clarify"].includes(data.kind) || typeof data.say !== "string"
      || (!["none", "clarify", "reject"].includes(data.kind) && !intents.has(data.intent)) || !Array.isArray(data.targets)) {
      return failure("AI 응답을 처리하지 못했습니다");
    }
    if (data.kind === "revise" || data.kind === "reject") {
      const current = runState();
      if (!baseRunId || current?.id !== baseRunId || current.status !== baseRunStatus
        || data.courseId !== null
        || (data.kind === "reject" ? data.intent !== null || data.targets.length !== 0
          : data.intent !== "plan" || !isRevision(data.targets)
            || data.targets.some((id) => !available.some((activity) => activity.id === id)))) {
        return failure("계획 응답을 적용하지 못했습니다. 현재 요약을 확인해 주세요.");
      }
      return finish({ kind: data.kind, intent: data.intent, courseId: null,
        targets: [...data.targets], matched, say: data.say, baseRunId, baseRunStatus });
    }
    if (data.kind === "clarify") return finish({ ...failure(data.say || "어떤 할 일을 처리할까요"), kind: "clarify" });
    if (data.kind === "none") return finish(failure(typeof data.say === "string" ? data.say : "맞는 항목이 없습니다"));
    const allowed = new Set(available.map((activity) => activity.id));
    const targets = [...new Set(data.targets.filter((id) => allowed.has(id)))];
    if (data.kind === "plan") {
      const picked = targets.filter((id) => activityById(id).type !== "test" && !isDone(id));
      const steps = orderPlan(picked.filter((id) => !state.surveys?.[id]?.done));
      const result = { kind: steps.length ? "plan" : "none", intent: "plan", courseId: null, targets: steps, matched };
      return finish({ ...result, say: steps.length ? `오늘 계획 ${steps.length}단계` : "오늘 계획에 넣을 일이 없습니다" });
    }
    const courseId = courses.find((c) => c.id === data.courseId && c.enrolled && !c.closed)?.id || null;
    const courseOnly = data.intent === "course" && courseId && !targets.length;
    const kind = courseOnly || targets.length === 1 ? "go" : targets.length > 1 ? "choose" : "none";
    if (kind === "none") return finish(failure("맞는 항목이 없습니다"));
    const result = { kind, intent: data.intent, courseId, targets, matched };
    return finish({ ...result, say: sayFor(result, text) });
  } catch {
    return failure(signal?.aborted ? "요청을 취소했습니다"
      : controller.signal.aborted ? "AI 응답 시간이 초과되었습니다" : "AI에 연결하지 못했습니다");
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", cancel);
  }
}


/** Today's open choices. Only `choose` results wait in the 요청함; go/none/plan never pile up there. */
export function requests() {
  const assistant = getState().assistant;
  if (assistant?.date !== TODAY || (assistant.requests || []).some((item) => item.kind !== "choose")) {
    update((state) => {
      const kept = state.assistant?.date === TODAY ? state.assistant.requests.filter((item) => item.kind === "choose") : [];
      state.assistant = { ...(state.assistant?.date === TODAY ? state.assistant : {}), date: TODAY, requests: kept };
    });
  }
  return getState().assistant.requests;
}

export function execute(result, { text, navigate }) {
  requests();
  if (result.kind === "revise" || result.kind === "reject") {
    const run = runState();
    if (!["ready", "running", "paused"].includes(run?.status) || run.id !== result.baseRunId
      || run.status !== result.baseRunStatus) return null;
    if (result.kind === "reject") stopRun();
    else if (isRevision(result.targets)) {
      const finished = run.steps.filter((step) => ["done", "skipped"].includes(step.status));
      const statuses = new Map(finished.map((step) => [step.activityId, step.status]));
      const targets = new Set(result.targets);
      const next = createRun([...result.targets,
        ...finished.filter((step) => !targets.has(step.activityId)).map((step) => step.activityId)], { preserveOrder: true });
      update(() => {
        for (const step of next.steps) step.status = statuses.get(step.activityId) || "pending";
        const pending = next.steps.findIndex((step) => step.status === "pending");
        next.cursor = pending < 0 ? next.steps.length : pending;
        if (pending < 0) next.status = "done";
      });
    }
    return null;
  }
  if (result.kind === "plan") createRun(result.targets);
  else if (["go", "choose"].includes(result.kind) && runState()?.status === "ready") stopRun();
  let request = null;
  if (result.kind === "choose") update((state) => {
    const previous = state.assistant.requests.find((item) => normalize(item.text) === normalize(text));
    const now = new Date();
    const id = previous?.id || `rq-${now.getTime()}-${Math.random().toString(36).slice(2, 8)}`;
    request = { id, text, at: now.toISOString(), kind: result.kind, intent: result.intent,
      courseId: result.courseId, targets: [...result.targets], chosen: null, say: result.say };
    state.assistant.requests = [request, ...state.assistant.requests.filter((item) => item.id !== id)].slice(0, 5);
  });
  if (result.kind === "go") {
    const course = courses.find((c) => c.id === result.courseId);
    const href = result.intent === "course" && !result.targets.length && course
      ? routeFor(currentRole(), "classroom", { kj: course.id, special: course.kind === "special" })
      : hrefFor(result.targets[0]);
    if (href) navigate(href);
  }
  return request;
}

export function choose(requestId, activityId, { navigate }) {
  const request = requests().find((item) => item.id === requestId);
  const href = hrefFor(activityId);
  if (!request?.targets.includes(activityId) || !href) return;
  update((state) => { state.assistant.requests.find((item) => item.id === requestId).chosen = activityId; });
  navigate(href);
}


export function nextQueued(activityId) {
  const request = requests().find((item) => item.targets.includes(activityId));
  if (!request) return null;
  return request.targets.slice(request.targets.indexOf(activityId) + 1).find((id) => activityById(id) && !isDone(id)) || null;
}

export { hrefFor, isDone } from "./activity-context.js";
