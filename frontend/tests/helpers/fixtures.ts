import type { Activity, Course } from "../../src/data.ts";

/** A minimal enrolled course; override only what a test is about. */
export function course(overrides: Partial<Course> & Pick<Course, "id" | "ko" | "en">): Course {
  return {
    code: `code-${overrides.id}`, instructor: "-", credit: "3", time: "-", room: "-", gradient: "g1",
    term: "2026-2", kind: "regular", enrolled: true, ...overrides,
  };
}

/** A minimal activity; override only what a test is about. */
export function activity(overrides: Partial<Activity> & Pick<Activity, "id" | "courseId">): Activity {
  return { week: 1, type: "video", title: `title-${overrides.id}`, status: "ongoing", ...overrides };
}
