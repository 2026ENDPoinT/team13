import type { Activity } from "./data.ts";
import { LECTURES, type LectureMedia } from "./media/catalog.ts";

export function mediaForActivity(activity: Pick<Activity, "id" | "courseId" | "clipId"> | null | undefined): LectureMedia {
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

export function mediaForCourse(courseId: string): LectureMedia {
  return LECTURES[courseId] || LECTURES.bi;
}

export function mediaFile(id: string, ext: string): string {
  return `/app/media/lectures/${id}.${ext}`;
}
