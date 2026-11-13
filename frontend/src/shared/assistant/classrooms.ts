import { courses, type Course } from "../../data.ts";
import { courseAliases } from "./aliases.ts";
import { abbreviationMatch } from "./course-match.ts";

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
const index: ClassroomEntry[] = courses.map((course) => {
  const title = `${course.ko} 강의실로 이동`;
  return {
    course,
    command: compact(title),
    row: { courseId: course.id, title },
    terms: [...new Set([course.ko, course.en, course.code, ...(courseAliases[course.id] || [])].filter(Boolean).map(compact))],
  };
});

/** Only course names/aliases and classroom commands are local; activity requests stay with the AI. */
export function searchClassrooms(text: string): ClassroomRow[] {
  const query = compact(text).replace(/강의실(?:(?:로|으로)?(?:이동|가기|열기))?$/, "");
  if (!query) return [];
  const ranked: [number, ClassroomRow][] = [];
  for (const entry of index) {
    if (!available(entry)) continue;
    if (entry.terms.some((term) => term.includes(query))) ranked.push([0, entry.row]);
    else if (abbreviationMatch(query, entry.course)) ranked.push([1, entry.row]);
  }
  return ranked.sort((a, b) => a[0] - b[0]).map(([, row]) => row);
}

/** Free text is never executed as a partial or ambiguous match. */
export function classroomForCommand(text: string): ClassroomRow | null {
  const command = compact(text);
  return index.find((entry) => available(entry) && entry.command === command)?.row || null;
}
