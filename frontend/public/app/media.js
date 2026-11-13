import { LECTURES } from "./media/catalog.js";

export function mediaForActivity(activity) {
  const rows = Object.values(LECTURES);
  if (activity) {
    if (activity.clipId) {
      const chosen = rows.find((row) => row.id === activity.clipId);
      if (chosen) return chosen;
    }
    const pinned = rows.find((row) => row.activityIds.includes(activity.id));
    if (pinned) return pinned;
    if (LECTURES[activity.courseId]) return LECTURES[activity.courseId];
  }
  return LECTURES.bi;
}

export function mediaForCourse(courseId) {
  return LECTURES[courseId] || LECTURES.bi;
}

export function mediaFile(id, ext) {
  return `/app/media/lectures/${id}.${ext}`;
}
