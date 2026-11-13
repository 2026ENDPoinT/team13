// App course objects for server courses (STAC-106). Screens read a course through `courseById(kj)` (data.js); in backend
// mode this module answers it: a server `kj` (shared/backend.js `serverCourseOf`) gets a view built only from the
// server's facts (`GET /api/me/courses`), never from the sample course with the same key, and an unknown non-sample
// `kj` gets a "not on the server" placeholder (`missing: true`) so screens show their gate, not a sample course.
// Sample ids remain available in mock mode (`off`) and before sign-in (`anon`), but unknown routes always get a
// placeholder. Once signed in (`ready`), sample ids absent from the user's courses are placeholders too. Views
// are rebuilt when the session changes. Imported by shell/shell.js before the first render.
import { backendStatus, serverCourseOf, serverCourses, subscribe } from "./backend.js";
import { courses, resolveCoursesWith } from "../data.js";
import { instructorsText, termName } from "./catalog-remote.js";

const DOW = "일월화수목금토";
const SAMPLE_IDS = new Set(courses.map((course) => course.id));
const views = new Map(); // kj -> view
subscribe(() => views.clear());

/** Cover class `g1`…`g8` of a server course, fixed by its id (tokens.css `--gN-*`). */
export function courseGradient(courseId) {
  const id = Number(courseId);
  return `g${Number.isFinite(id) ? (Math.abs(Math.trunc(id)) % 8) + 1 : 1}`;
}

// Local covers only: keep imported templates without loading school-hosted images.
const COVERS = [
  { gradient: "g1", cover: "/app/assets/covers/cover-pale-discs-716x430.png" },
  { gradient: "g2", cover: "/app/assets/covers/cover-purple-orbs-467x280.png" },
  { gradient: "g3", cover: "/app/assets/covers/cover-magenta-sphere-716x430.png" },
  { gradient: "g5", cover: "/app/assets/covers/cover-navy-waves-467x280.png" },
  { gradient: "g6", cover: "/app/assets/covers/cover-purple-wing-467x280.png" },
  { gradient: "g4", cover: "/app/assets/covers/cover-pale-discs-467x280.png" },
  { gradient: "g7", cover: "/app/assets/covers/cover-magenta-sphere-467x280.png" },
];

export function courseCover(remote) {
  const template = String(remote.coverTemplate || "");
  if (!template) return { gradient: courseGradient(remote.courseId) };
  const number = /(\d+)$/.exec(template);
  let index = number ? Number(number[1]) : 0;
  if (!number) for (const ch of template) index = (index * 31 + ch.charCodeAt(0)) >>> 0;
  return COVERS[index % COVERS.length];
}

const hm = (value) => {
  const match = /^(\d{2}):(\d{2})/.exec(String(value || ""));
  return match ? `${match[1]}:${match[2]}` : "";
};

/** "목 15:00–18:00, 금 6–8교시", or "" without schedules. Rooms are in `room`. */
function timeText(schedules) {
  return (Array.isArray(schedules) ? schedules : []).map((slot) => {
    const day = DOW[slot.dayOfWeek] || "";
    const time = slot.startTime ? `${hm(slot.startTime)}–${hm(slot.endTime)}` : slot.periodStart ? `${slot.periodStart}–${slot.periodEnd || slot.periodStart}교시` : "";
    return `${day} ${time}`.trim();
  }).filter(Boolean).join(", ");
}

function roomText(schedules) {
  return [...new Set((Array.isArray(schedules) ? schedules : []).map((slot) => slot.room).filter(Boolean))].join(", ");
}

function viewOf(remote, kj) {
  const name = remote.name || [remote.code, remote.section].filter(Boolean).join("-") || "이름 없는 과목";
  return {
    id: kj,
    courseId: remote.courseId,
    server: true,
    role: remote.role,
    code: [remote.code, remote.section].filter(Boolean).join("-"),
    ko: name,
    en: "",
    cardTitle: name,
    instructor: instructorsText(remote.instructors),
    credit: remote.credit != null && Number.isFinite(Number(remote.credit)) ? String(Number(remote.credit)) : "",
    time: timeText(remote.schedules),
    clock: "",
    room: roomText(remote.schedules),
    ...courseCover(remote),
    coverTemplate: remote.coverTemplate || "",
    learningPeriod: remote.learningPeriod || "",
    lectureType: remote.lectureType || "",
    schedules: remote.schedules || [],
    instructors: remote.instructors || [],
    term: termName(remote.term),
    kind: remote.type === "NONREGULAR" ? "special" : "regular",
    enrolled: true,
    closed: Boolean(remote.term) && remote.term.current === false,
  };
}

function placeholder(kj) {
  return {
    id: kj,
    missing: true,
    code: "",
    ko: "서버에 없는 과목",
    en: "",
    cardTitle: "서버에 없는 과목",
    instructor: "",
    credit: "",
    time: "",
    clock: "",
    room: "",
    gradient: "g8",
    term: "",
    kind: "regular",
    enrolled: false,
  };
}

function resolve(id) {
  if (!id) return undefined;
  const kj = String(id);
  if (views.has(kj)) return views.get(kj);
  const remote = serverCourseOf(kj);
  if (!remote && SAMPLE_IDS.has(kj) && backendStatus() !== "ready") return undefined;
  const view = remote ? viewOf(remote, kj) : placeholder(kj);
  views.set(kj, view);
  return view;
}

resolveCoursesWith(resolve);

/** Every server course of the user as `{ course, remote }` (course = its app view, `course.id` = kj), in the app order. */
export function serverCourseViews(roles) {
  return serverCourses(roles).map(({ kj, remote }) => ({ course: resolve(kj), remote }));
}
