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
  /** Names the abbreviation matcher may read: the course itself, plus the matching sample course for server courses. */
  names: Pick<Course, "ko" | "en">[];
}

const compact = (value: string | null | undefined): string => String(value || "").toLowerCase().replace(/\s+/g, "");
const available = ({ course }: ClassroomEntry): boolean => course.enrolled && !course.closed;
/** A server course carries only the server's name (often English, e.g. "Database"); the sample catalog knows its Korean name and aliases ("데이터베이스", "데베", "디비"). */
function sampleOf(course: Course): Course | undefined {
  const name = compact(course.ko);
  return name ? courses.find((sample) => compact(sample.ko) === name || compact(sample.en) === name) : undefined;
}
function indexCourses(availableCourses: readonly Course[], server: boolean): ClassroomEntry[] {
  const names = new Map<string, number>();
  if (server) for (const course of availableCourses) names.set(course.ko, (names.get(course.ko) || 0) + 1);
  return availableCourses.map((course) => {
    const displayName = server && (names.get(course.ko) || 0) > 1 ? `${course.ko} (${course.id})` : course.ko;
    const title = `${displayName} 강의실로 이동`;
    const known = server ? sampleOf(course) : course;
    return {
      course,
      command: compact(title),
      row: { courseId: course.id, title },
      terms: [...new Set([displayName, course.ko, course.en, course.code, known?.ko, known?.en, ...(known ? courseAliases[known.id] || [] : [])].filter(Boolean).map(compact))],
      names: known && known !== course ? [course, known] : [course],
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
    else if (entry.names.some((names) => abbreviationMatch(query, names))) ranked.push([1, entry.row]);
  }
  return ranked.sort((a, b) => a[0] - b[0]).map(([, row]) => row);
}

/** Free text is never executed as a partial or ambiguous match. */
export function classroomForCommand(text: string): ClassroomRow | null {
  const command = compact(text);
  const matches = entries().filter((entry) => available(entry) && entry.command === command);
  return matches.length === 1 ? matches[0].row : null;
}
