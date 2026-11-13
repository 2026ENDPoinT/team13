import { courses, type Course } from "../../data.ts";
import { courseAliases } from "./aliases.ts";
import { abbreviationMatch } from "./course-match.ts";
import { backendStatus, subscribe } from "../backend.ts";
import { serverCourseViews } from "../course-view.ts";

/** A classroom command row: the course and its "<과목> 강의실로 이동" title. */
export interface ClassroomRow {
  courseId: string;
  title: string;
}

interface ClassroomEntry {
  course: Course;
  command: string;
  row: ClassroomRow;
  terms: string[];
}

const compact = (value: string | null | undefined): string => String(value || "").toLowerCase().replace(/\s+/g, "");
const available = ({ course }: ClassroomEntry): boolean => course.enrolled && !course.closed;
function indexCourses(availableCourses: readonly Course[], server: boolean): ClassroomEntry[] {
  const names = new Map<string, number>();
  if (server) for (const course of availableCourses) names.set(course.ko, (names.get(course.ko) || 0) + 1);
  return availableCourses.map((course) => {
    const displayName = server && (names.get(course.ko) || 0) > 1 ? `${course.ko} (${course.id})` : course.ko;
    const title = `${displayName} 강의실로 이동`;
    return {
      course,
      command: compact(title),
      row: { courseId: course.id, title },
      terms: [...new Set([displayName, course.ko, course.en, course.code, ...(!server ? courseAliases[course.id] || [] : [])].filter(Boolean).map(compact))],
    };
  });
}

const sampleIndex = indexCourses(courses, false);
let serverIndex: ClassroomEntry[] | null = null;
subscribe(() => { serverIndex = null; });

function entries(): ClassroomEntry[] {
  if (backendStatus() !== "ready") return sampleIndex;
  return serverIndex ??= indexCourses(serverCourseViews().flatMap(({ course }) => course ? [course] : []), true);
}

/** Search only the current session's courses; mock aliases never apply to server courses. */
export function searchClassrooms(text: string): ClassroomRow[] {
  const query = compact(text).replace(/강의실(?:(?:로|으로)?(?:이동|가기|열기))?$/, "");
  if (!query) return [];
  const ranked: [number, ClassroomRow][] = [];
  for (const entry of entries()) {
    if (!available(entry)) continue;
    if (entry.terms.some((term) => term.includes(query))) ranked.push([0, entry.row]);
    else if (abbreviationMatch(query, entry.course)) ranked.push([1, entry.row]);
  }
  return ranked.sort((a, b) => a[0] - b[0]).map(([, row]) => row);
}

/** Free text is never executed as a partial or ambiguous match. */
export function classroomForCommand(text: string): ClassroomRow | null {
  const command = compact(text);
  const matches = entries().filter((entry) => available(entry) && entry.command === command);
  return matches.length === 1 ? matches[0].row : null;
}
