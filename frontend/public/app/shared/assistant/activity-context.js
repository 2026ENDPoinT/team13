import { courses, activities } from "../../data.js";
import { getState } from "../../store.js";
import { TODAY, activityById, activityHref, typeLabel } from "../format.js";

export const compact = (value) => String(value || "").toLowerCase().replace(/\s+/g, "");
export const defaultCtx = () => ({ courses, activities, today: TODAY, positions: getState().player?.positions || {} });
export const enrolled = (course) => course.enrolled && !course.closed;

export function progressOf(activity, positions = {}) {
  const seconds = positions[activity.id];
  const progress = Number.isFinite(seconds) && activity.minutes > 0
    ? seconds / (activity.minutes * 60) * 100 : Number(activity.progress) || 0;
  return Math.max(0, Math.min(100, progress));
}

export function ordered(items, ctx) {
  return items.slice().sort((a, b) => String(a.due || "9999").localeCompare(String(b.due || "9999"))
    || progressOf(b, ctx.positions) - progressOf(a, ctx.positions));
}

export function todayLectures(ctx = defaultCtx()) {
  const ids = new Set(ctx.courses.filter(enrolled).map((course) => course.id));
  return ordered(ctx.activities.filter((a) => ids.has(a.courseId) && a.type === "video" && a.status === "ongoing"
    && (!a.due || a.due.slice(0, 10) >= ctx.today)), ctx).map((a) => a.id);
}

// Shared URL construction keeps hero actions and request execution identical.
export function activityLink(activity, { autoplay = true } = {}) {
  const href = activityHref(activity);
  return activity.type === "video" && autoplay ? `${href}${href.includes("?") ? "&" : "?"}autoplay=1` : href;
}

const pad = (n) => String(Math.floor(n)).padStart(2, "0");

// A saved position is where the player resumes; activity.progress is only attendance progress.
function resumeOf(activity, positions = {}) {
  const seconds = positions[activity.id];
  if (Number.isFinite(seconds) && seconds >= 1) return `${pad(seconds / 60)}:${pad(seconds % 60)}부터`;
  const progress = Math.round(Number(activity.progress) || 0);
  return progress > 0 ? `진도 ${progress}%` : "";
}

function description(activity, ctx) {
  const course = ctx.courses.find((c) => c.id === activity.courseId);
  const meta = [];
  if (activity.minutes) meta.push(`${activity.minutes}분`);
  if (activity.due) meta.push(`${activity.due.slice(5, 10).replace("-", "/")} ${activity.due.slice(11, 16)}까지`.trim());
  const resume = activity.type === "video" ? resumeOf(activity, ctx.positions) : "";
  if (resume) meta.push(resume);
  return { title: `${course?.ko || activity.courseId} · ${activity.title}`, meta: meta.join(" · ") };
}

/** One status line for a result, shared by local and Gemini results so both read the same. */
export function sayFor({ kind, intent, courseId, targets }, text, ctx = defaultCtx()) {
  if (kind === "none" || (!targets.length && !courseId)) return `'${text}'와 맞는 항목이 없습니다`;
  const course = ctx.courses.find((c) => c.id === courseId);
  if (!targets.length) return `${course.ko} 강의실로 이동`;
  if (kind === "choose") {
    const label = compact(text).includes("오늘") && intent === "video" ? "오늘 들을 강의"
      : `${course ? `${course.ko} ` : ""}${intent === "course" ? "활동" : typeLabel(intent)}`;
    return `${label} ${targets.length}개`;
  }
  const activity = ctx.activities.find((a) => a.id === targets[0]);
  const owner = ctx.courses.find((c) => c.id === activity.courseId);
  if (activity.type === "video") return `${owner.ko} ${activity.week}주차 ${activity.title} 재생`;
  if (activity.type === "report") return `${owner.ko} ${activity.title} 제출 화면으로 이동`;
  return `${owner.ko} ${activity.title} 열기`;
}

export function describe(activityId) {
  const ctx = defaultCtx();
  const activity = ctx.activities.find((a) => a.id === activityId);
  return activity ? description(activity, ctx) : { title: "찾을 수 없는 활동", meta: "" };
}

export function nextActions(ctx = defaultCtx()) {
  const ids = new Set(ctx.courses.filter(enrolled).map((course) => course.id));
  // Same status predicate as openTodos(), without its display-only pinned ordering.
  const deadline = ordered(ctx.activities.filter((a) => ids.has(a.courseId) && a.type !== "video"
    && ["missing", "ongoing", "scheduled"].includes(a.status) && a.due), ctx)[0];
  const lecture = ctx.activities.find((a) => a.id === todayLectures(ctx)[0]);
  return [[deadline, "deadline", "바로 가기"], [lecture, "lecture", "이어 듣기"]]
    .filter(([activity]) => activity)
    .sort(([a], [b]) => String(a.due || "9999").localeCompare(String(b.due || "9999")))
    .map(([activity, kind, cta]) => ({
      id: activity.id, kind, ...description(activity, ctx), cta, href: activityLink(activity),
    }));
}


export function hrefFor(activityId, options) {
  const activity = activityById(activityId);
  return activity ? activityLink(activity, options) : null;
}

export function isDone(activityId) {
  const activity = activityById(activityId);
  return Boolean(getState().submissions?.[activityId] || (activity && (["done", "submitted", "graded"].includes(activity.status)
    || progressOf(activity, getState().player?.positions) >= 90)));
}
