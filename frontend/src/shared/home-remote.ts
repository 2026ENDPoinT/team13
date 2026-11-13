// Main screen aggregate (`GET /api/me/home`), the full to-do list (`GET /api/me/todo`) and one course's to-do
// (`GET /api/me/todo?courseId=`) while the backend connection is `ready`. Backend courses are addressed in the app
// by `kj` (`c<courseId>` or a sample-id key, shared/backend.js `kjOf`).
import { apiGet, ApiError } from "./api.ts";
import { useEffect } from "react";
import { backendState, backendStatus, institutionZone, kjOf, refreshCourses } from "./backend.ts";
import { createResource, freshVisit, retryLater, useResource } from "./remote-cache.ts";
import { noteUnread } from "./unread-badges.tsx";
import { serverActivityHref, schoolAssignmentHref } from "./activity-links.ts";
import { schoolHref } from "./school-content.tsx";
import { courseById, COURSE_ORDER } from "../data.ts";
import type { Course } from "../data.ts";
import type { Entry } from "./remote-cache.ts";

type Id = number | string;

/**
 * A to-do or calendar item (`/api/me/todo`, the home aggregate's `todo`). A school item (`origin: "school"`, report/test/survey)
 * has a string `activityId` (`school-<n>`), no `assignmentId`·`quizId`·`surveyId`, and `schoolUrl`.
 */
export interface TodoItem {
  activityId: number | string;
  origin?: "school";
  schoolUrl?: string | null;
  courseId: number;
  type: string;
  title: string;
  courseName?: string;
  courseCode?: string;
  openAt?: string | null;
  closeAt?: string | null;
  assignmentId?: number | null;
  quizId?: number | null;
  surveyId?: number | null;
}

/** One page of `GET /api/me/todo`. */
export interface TodoPage {
  serverTime?: string;
  total?: number;
  nextCursor?: string | null;
  items?: TodoItem[];
}

/** All loaded pages of a to-do list. */
export interface TodoList {
  serverTime?: string;
  total: number;
  nextCursor: string | null;
  items: TodoItem[];
}

/** A course of the home aggregate (same record as `/api/me/courses`). */
export interface HomeCourse {
  courseId: number;
  key?: string | null;
  role: string;
  name?: string;
  code?: string;
  /** Published notices of this course the caller has not marked read. */
  unreadNotices?: number;
}

/** A course notice of the home aggregate. */
export interface HomeNotice {
  noticeId: number;
  courseId: number;
  title: string;
  courseName?: string;
  pinned?: boolean;
  read?: boolean;
  publishedAt?: string;
}

/** A site notice of the home aggregate (`communityNotices`, `noticesForMe`); site-notices-remote patches its fields. */
export interface HomeSiteNotice {
  noticeId: number;
  title?: string;
  pinned?: boolean;
  read?: boolean;
  publishedAt?: string;
  [field: string]: unknown;
}

/** `GET /api/me/home`. */
export interface HomeData {
  serverTime?: string;
  courses?: HomeCourse[];
  notices?: HomeNotice[];
  todo?: TodoPage;
  communityNotices?: HomeSiteNotice[];
  noticesForMe?: HomeSiteNotice[];
}

export interface MatchedCourse {
  local: Course;
  remote: HomeCourse;
}

export interface WallParts {
  y: number;
  m: number;
  d: number;
  hh: number;
  mm: number;
  ss: number;
  dow: number;
}

export const TODO_PAGE = 20;
const HOME = "home";
const TODO = "todo";

const home = createResource<HomeData, []>(() => apiGet<HomeData>("/api/me/home"));

// All loaded pages of the to-do list, appended by "더 보기".
const todo = createResource<TodoList, [TodoList | null]>(async (previous) => {
  const params = new URLSearchParams({ size: String(TODO_PAGE) });
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet<TodoPage>(`/api/me/todo?${params}`);
  const items = page && Array.isArray(page.items) ? page.items : [];
  const total = Number(page.total) || 0;
  // 헤더 할 일 배지도 같은 서버 수를 쓴다.
  noteUnread("todo", total);
  return {
    serverTime: page.serverTime,
    total,
    nextCursor: page.nextCursor || null,
    items: previous ? [...previous.items, ...items] : items,
  };
});

function live(): boolean {
  return backendStatus() === "ready";
}

export function homeEntry(): Entry<HomeData> | null {
  return home.peek(HOME);
}

export function useHome(): Entry<HomeData> | null {
  const entry = useResource(home, HOME, reloadHome);
  useEffect(() => {
    if (live()) void home.visit(HOME, HOME);
  });
  return entry;
}

export function useTodo(): Entry<TodoList> | null {
  const entry = useResource(todo, TODO);
  useEffect(() => {
    if (live() && (freshVisit(TODO) === "entry" || !todo.peek(TODO))) void todo.load(TODO, null);
  });
  return entry;
}

export function useCourseTodo(courseId: Id): Entry<TodoList> | null {
  const key = String(courseId);
  const entry = useResource(courseTodo, key, () => reloadCourseTodo(courseId));
  useEffect(() => {
    if (live() && (freshVisit("course-todo", key) === "entry" || !courseTodo.peek(key))) void courseTodo.load(key, courseId);
  });
  return entry;
}


export function reloadHome(): void {
  if (live()) home.load(HOME);
}

/** Changes the cached home aggregate in place (e.g. a 알림마당 notice read) and repaints its watchers. */
export function patchHome(change: (data: HomeData) => void): void {
  home.patch((key) => key === HOME, change);
}

/** Copies each course's `unreadNotices` from fresh `/api/me/courses` records onto the home aggregate's same courses. */
export function applyCourseCounts(data: HomeData, courses: readonly { courseId: number | string; unreadNotices?: number | null }[]): void {
  if (!Array.isArray(data.courses)) return;
  const fresh = new Map(courses.map((course) => [String(course.courseId), course.unreadNotices]));
  for (const course of data.courses) {
    const count = fresh.get(String(course.courseId));
    if (typeof count === "number") course.unreadNotices = count;
  }
}

/**
 * After a course notice was marked read: marks it read in the cached home aggregate, then re-reads the course records
 * (`refreshCourses`) so the unread counts of the left menu, the course cards and the home aggregate follow without a
 * reload. A failed re-read leaves the old counts (the next page load corrects them).
 */
export async function followNoticeRead(noticeId: number): Promise<void> {
  patchHome((data) => {
    for (const notice of Array.isArray(data.notices) ? data.notices : []) if (notice.noticeId === noticeId) notice.read = true;
  });
  const courses = await refreshCourses().catch(() => null);
  if (courses) patchHome((data) => applyCourseCounts(data, courses));
}

export function todoEntry(): Entry<TodoList> | null {
  return todo.peek(TODO);
}


export function reloadTodo(): void {
  if (live()) todo.load(TODO, null);
}

/** Next page after the loaded ones (`nextCursor`). */
export function loadMoreTodo(): Promise<void> {
  const entry = todo.peek(TODO);
  if (!live() || !entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return todo.load(TODO, entry.data);
}

const rank = (kj: string): number => (COURSE_ORDER.includes(kj) ? COURSE_ORDER.indexOf(kj) : COURSE_ORDER.length);

/**
 * The home aggregate's courses with their app course view (`local`, from `courseById(kj)`: server facts, `local.id`
 * is the `kj`), sample-keyed ones first in the sample order, then the server order. `roles` limits the membership
 * role. Every server course has a view, so `unmatched` is always empty.
 */
export function memberCourses(data: HomeData | null | undefined, roles?: readonly string[] | null): { matched: MatchedCourse[]; unmatched: MatchedCourse[] } {
  const list = data && Array.isArray(data.courses) ? data.courses : [];
  const matched = list
    .filter((remote) => !roles || roles.includes(remote.role))
    .map((remote) => ({ local: courseById(kjOf(remote)), remote }))
    .sort((a, b) => rank(a.local.id) - rank(b.local.id));
  return { matched, unmatched: [] };
}

/** The app course id (`?kj=`) of a backend course id the user belongs to, or null. */
export function keyOfCourse(data: HomeData | null | undefined, courseId: number): string | null {
  const list = data && Array.isArray(data.courses) ? data.courses : [];
  // The home aggregate may not be loaded on this screen; the session's course list has the same courses.
  return kjOf(list.find((course) => course.courseId === courseId) || backendState().courses.find((course) => course.courseId === courseId));
}

// learning_activities.activity_type -> [sample type for icons and colors, label]
const ACTIVITY: Record<string, [string, string]> = {
  lecture_weeks: ["video", "영상"],
  lecture_material: ["material", "자료"],
  report: ["report", "과제"],
  test: ["test", "시험"],
  survey: ["survey", "설문"],
  zoom: ["zoom", "Zoom"],
  discuss: ["post", "토론"],
  clicker: ["post", "클리커"],
};

export function todoKind(type: string): string {
  return (ACTIVITY[type] || ["post"])[0];
}

export function todoLabel(type: string): string {
  return (ACTIVITY[type] || [null, "활동"])[1];
}

/**
 * Where a to-do or calendar item opens in STack, or null. Lectures and materials open their server detail by activity id;
 * assignments, quizzes and surveys open their own screen by the item's `assignmentId`·`quizId`·`surveyId`. A school assignment
 * opens STack's read-only detail (`n=school-<n>`); a school test or survey has no STack screen (see `todoSchoolHref`).
 */
export function todoHref(item: TodoItem, data: HomeData | null | undefined): string | null {
  const kj = keyOfCourse(data, item.courseId);
  if (!kj) return null;
  if (item.origin === "school") return item.type === "report" ? schoolAssignmentHref(kj, item.activityId) : null;
  return serverActivityHref(kj, item.type, item.activityId, item);
}

/** The school's own address of a school to-do that STack does not open (a test, a survey): a new-tab link, or null. */
export function todoSchoolHref(item: TodoItem): string | null {
  return item.origin === "school" && item.type !== "report" ? schoolHref(item.schoolUrl) : null;
}

// ---------- 한 과목의 할 일(강의실) ----------

export const COURSE_TODO_PAGE = 5;

/**
 * 강의실의 이 과목 할 일(`GET /api/me/todo?courseId=&size=`): 첫 쪽과 `total`. 담당 과목이면 비어 있고, 내 과목이 아니면 404다.
 * 키는 서버 과목 ID다. 전체 목록은 할 일 팝업(`todoEntry`)이 따로 읽는다.
 */
const courseTodo = createResource<TodoList, [Id]>(async (courseId) => {
  const params = new URLSearchParams({ courseId: String(courseId), size: String(COURSE_TODO_PAGE) });
  const page = await apiGet<TodoPage>(`/api/me/todo?${params}`);
  return {
    serverTime: page.serverTime,
    total: Number(page.total) || 0,
    nextCursor: page.nextCursor || null,
    items: page && Array.isArray(page.items) ? page.items : [],
  };
});

export function courseTodoEntry(courseId: Id): Entry<TodoList> | null {
  return courseTodo.peek(String(courseId));
}

export function reloadCourseTodo(courseId: Id): void {
  if (live()) courseTodo.load(String(courseId), courseId);
}


// ---------- server date-times (ADR-0039) ----------
// An offset-less server date-time ("2026-11-12T23:59:00") is wall-clock time in the institution
// zone and is used as written. A string with `Z` or an offset is an absolute instant and is turned
// into the institution zone's wall clock. Neither path reads the browser's zone, so every text and
// every difference below is the same wherever the browser runs.

const DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$/i;
const FIXED_ZONE = /^(?:Z|UTC|GMT|(?:UTC|GMT)?([+-])(\d{2})(?::?(\d{2}))?)$/i;
const zoneParts = new Map<string, Intl.DateTimeFormat>(); // zone id -> Intl formatter

function instantWall(ms: number): number {
  const zone = institutionZone();
  const fixed = FIXED_ZONE.exec(zone);
  if (fixed) {
    const offset = fixed[1] ? (fixed[1] === "-" ? -1 : 1) * (Number(fixed[2]) * 60 + Number(fixed[3] || 0)) * 60000 : 0;
    return ms + offset;
  }
  let format = zoneParts.get(zone);
  if (!format) {
    format = new Intl.DateTimeFormat("en-US", {
      timeZone: zone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
    });
    zoneParts.set(zone, format);
  }
  const part = Object.fromEntries(format.formatToParts(new Date(ms)).map((item) => [item.type, item.value]));
  return Date.UTC(Number(part.year), Number(part.month) - 1, Number(part.day), Number(part.hour) % 24, Number(part.minute), Number(part.second), ((ms % 1000) + 1000) % 1000);
}

/**
 * Server date-time (or date) → milliseconds of its institution wall clock read as if it were UTC,
 * or NaN. Only differences of two such values and `wallFormat` give meaning to the number.
 */
export function wallMs(value: unknown): number {
  const match = DATE_TIME.exec(String(value || "").trim());
  if (!match) return NaN;
  const [, y, m, d, hh, mm, ss, frac, offset] = match;
  const wall = Date.UTC(Number(y), Number(m) - 1, Number(d), Number(hh || 0), Number(mm || 0), Number(ss || 0), Number((frac || "0").slice(0, 3).padEnd(3, "0")));
  if (!offset) return wall;
  const sign = offset[0] === "-" ? -1 : 1;
  const digits = offset.replace(/[^\d]/g, "");
  const shift = offset.toUpperCase() === "Z" ? 0 : sign * (Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2, 4) || 0)) * 60000;
  return instantWall(wall - shift);
}

/** `{ y, m, d, hh, mm, ss, dow }` of a server date-time on the institution wall clock, or null. */
export function wallParts(value: unknown): WallParts | null {
  const ms = wallMs(value);
  if (Number.isNaN(ms)) return null;
  const t = new Date(ms);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate(), hh: t.getUTCHours(), mm: t.getUTCMinutes(), ss: t.getUTCSeconds(), dow: t.getUTCDay() };
}

/** "2026-11-12T23:59" of a server date-time on the institution wall clock (for `datetime-local`), or "". */
export function wallText(value: unknown): string {
  const p = wallParts(value);
  if (!p) return "";
  const z = (n: number) => String(n).padStart(2, "0");
  return `${p.y}-${z(p.m)}-${z(p.d)}T${z(p.hh)}:${z(p.mm)}`;
}

/**
 * An `Intl.DateTimeFormat` for server date-times: `format(value)` shows the institution wall clock
 * with `options` (no `timeZone`), "" when `value` is not a date-time.
 */
export function wallFormat(locale: string | string[] | undefined, options: Intl.DateTimeFormatOptions): { format(value: unknown): string } {
  const format = new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC" });
  return {
    format(value: unknown): string {
      const ms = wallMs(value);
      return Number.isNaN(ms) ? "" : format.format(new Date(ms));
    },
  };
}

function datePart(value: unknown): number {
  const p = wallParts(value);
  return p ? Date.UTC(p.y, p.m - 1, p.d) : NaN;
}

/** D-day of `closeAt` against the server clock (`serverTime`), by institution calendar day. */
export function ddayAt(closeAt: unknown, serverTime: unknown): string {
  const days = Math.round((datePart(closeAt) - datePart(serverTime)) / 86400000);
  if (Number.isNaN(days)) return "";
  if (days < 0) return "마감";
  return days === 0 ? "D-day" : `D-${days}`;
}

/** "11월 12일 (목) 오후 11:59" for a server date-time on the institution wall clock. */
export function koDateTime(value: unknown): string {
  const match = DATE_TIME.exec(String(value || "").trim());
  const p = match && match[4] != null ? wallParts(value) : null;
  if (!p) return "";
  return `${p.m}월 ${p.d}일 (${"일월화수목금토"[p.dow]}) ${p.hh < 12 ? "오전" : "오후"} ${p.hh % 12 || 12}:${String(p.mm).padStart(2, "0")}`;
}

/** "2026.11.10" for a server date-time (institution calendar day) or date. */
export function koDate(value: unknown): string {
  const p = wallParts(value);
  const z = (n: number) => String(n).padStart(2, "0");
  return p ? `${p.y}.${z(p.m)}.${z(p.d)}` : "";
}

/** Korean message for a failed home or to-do request. */
export function homeMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "ASTRA 서버에서 정보를 받지 못했습니다. 잠시 후 다시 시도하세요.";
  if (error.status === 401) return "ASTRA 서버 로그인이 필요합니다.";
  if (error.status === 429) return retryLater(error);
  if (error.status === 0) return error.message;
  return "ASTRA 서버에서 정보를 받지 못했습니다. 잠시 후 다시 시도하세요.";
}
