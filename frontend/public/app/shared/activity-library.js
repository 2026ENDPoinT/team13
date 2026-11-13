import { syncActivities } from "../store.js";

export const APP_NOW = new Date("2026-11-11T12:00:00+09:00");

export function parseStamp(value) {
  const text = String(value || "").trim();
  if (!text) return null;
  const zoned = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(text) ? `${text}:00+09:00` : text;
  const date = new Date(zoned);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function endsAfter(start, end) {
  const opened = parseStamp(start);
  const closed = parseStamp(end);
  if (!opened || !closed) return false;
  return closed.getTime() > opened.getTime();
}

export function activityPhase(activity, now = APP_NOW) {
  if (!activity) return "open";
  if (!activity.authored && !activity.attendStart && !activity.attendEnd) return activity.status || "open";
  const start = parseStamp(activity.attendStart || activity.start);
  const end = parseStamp(activity.attendEnd || activity.end || activity.due);
  if (end && end.getTime() < now.getTime()) return "expired";
  if (start && start.getTime() > now.getTime()) return "open";
  if (start || end) return "ongoing";
  return "open";
}

export function isClosedActivity(activity) {
  if (!activity) return false;
  if (activity.status === "expired") return true;
  if (activity.authored || activity.attendStart || activity.attendEnd) return activityPhase(activity) === "expired";
  return false;
}

export function upsertActivity(state, item) {
  const next = { ...item, authored: true };
  state.authoredActivities = [...(state.authoredActivities || []).filter((row) => row.id !== next.id), next];
  state.deletedActivityIds = (state.deletedActivityIds || []).filter((id) => id !== next.id);
  syncActivities(state);
  return next;
}

export function removeActivity(state, id) {
  state.authoredActivities = (state.authoredActivities || []).filter((row) => row.id !== id);
  state.deletedActivityIds = [...new Set([...(state.deletedActivityIds || []), id])];
  syncActivities(state);
}
