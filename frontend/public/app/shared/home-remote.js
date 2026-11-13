// Main screen aggregate (`GET /api/me/home`), the full to-do list (`GET /api/me/todo`) and one course's to-do
// (`GET /api/me/todo?courseId=`) while the backend connection is `ready`. Backend courses are addressed in the app
// by `kj` (`c<courseId>` or a sample-id key, shared/backend.js `kjOf`).
import { apiGet, ApiError } from "./api.js";
import { backendState, backendStatus, institutionZone, kjOf } from "./backend.js";
import { createResource, freshVisit, retryLater } from "./remote-cache.js";
import { noteUnread } from "./unread-badges.js";
import { serverActivityHref } from "./activity-links.js";
import { courseById, COURSE_ORDER } from "../data.js";

export const TODO_PAGE = 20;
const HOME = "home";
const TODO = "todo";

const home = createResource(() => apiGet("/api/me/home"));

// All loaded pages of the to-do list, appended by "더 보기".
const todo = createResource(async (previous) => {
  const params = new URLSearchParams({ size: String(TODO_PAGE) });
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet(`/api/me/todo?${params}`);
  const items = Array.isArray(page && page.items) ? page.items : [];
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

function live() {
  return backendStatus() === "ready";
}

export function homeEntry() {
  return home.peek(HOME);
}

/**
 * Keeps `node` painted from the home aggregate. Call after `node` is attached: it loads once
 * per navigation (and on focus or tab return) and repaints `node` when the response lands.
 */
export function watchHome(node, paint) {
  if (!live() || !node.isConnected) return;
  home.watch(node, HOME, paint, () => home.load(HOME));
  home.visit(HOME, HOME);
}

export function reloadHome() {
  if (live()) home.load(HOME);
}

/** Changes the cached home aggregate in place (e.g. a 알림마당 notice read) and repaints its watchers. */
export function patchHome(change) {
  home.patch((key) => key === HOME, change);
}

export function todoEntry() {
  return todo.peek(TODO);
}

/** Same as watchHome for the to-do dialog: the first page loads once per navigation. */
export function watchTodo(node, paint) {
  if (!live() || !node.isConnected) return;
  todo.watch(node, TODO, paint, null);
  if (freshVisit(TODO) === "entry" || !todo.peek(TODO)) todo.load(TODO, null);
}

export function reloadTodo() {
  if (live()) todo.load(TODO, null);
}

/** Next page after the loaded ones (`nextCursor`). */
export function loadMoreTodo() {
  const entry = todo.peek(TODO);
  if (!live() || !entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return todo.load(TODO, entry.data);
}

const rank = (kj) => (COURSE_ORDER.includes(kj) ? COURSE_ORDER.indexOf(kj) : COURSE_ORDER.length);

/**
 * The home aggregate's courses with their app course view (`local`, from `courseById(kj)`: server facts, `local.id`
 * is the `kj`), sample-keyed ones first in the sample order, then the server order. `roles` limits the membership
 * role. Every server course has a view, so `unmatched` is always empty.
 */
export function memberCourses(data, roles) {
  const list = Array.isArray(data && data.courses) ? data.courses : [];
  const matched = list
    .filter((remote) => !roles || roles.includes(remote.role))
    .map((remote) => ({ local: courseById(kjOf(remote)), remote }))
    .sort((a, b) => rank(a.local.id) - rank(b.local.id));
  return { matched, unmatched: [] };
}

/** The app course id (`?kj=`) of a backend course id the user belongs to, or null. */
export function keyOfCourse(data, courseId) {
  const list = Array.isArray(data && data.courses) ? data.courses : [];
  // The home aggregate may not be loaded on this screen; the session's course list has the same courses.
  return kjOf(list.find((course) => course.courseId === courseId) || backendState().courses.find((course) => course.courseId === courseId));
}

// learning_activities.activity_type -> [sample type for icons and colors, label]
const ACTIVITY = {
  lecture_weeks: ["video", "영상"],
  lecture_material: ["material", "자료"],
  report: ["report", "과제"],
  test: ["test", "시험"],
  survey: ["survey", "설문"],
  zoom: ["zoom", "Zoom"],
  discuss: ["post", "토론"],
  clicker: ["post", "클리커"],
};

export function todoKind(type) {
  return (ACTIVITY[type] || ["post"])[0];
}

export function todoLabel(type) {
  return (ACTIVITY[type] || [null, "활동"])[1];
}

/**
 * Where a to-do or calendar item opens, or null. Lectures and materials open their server detail by activity id;
 * assignments, quizzes and surveys open their own screen by the item's `assignmentId`·`quizId`·`surveyId`.
 */
export function todoHref(item, data) {
  const kj = keyOfCourse(data, item.courseId);
  return kj ? serverActivityHref(kj, item.type, item.activityId, item) : null;
}

// ---------- 한 과목의 할 일(강의실) ----------

export const COURSE_TODO_PAGE = 5;

/**
 * 강의실의 이 과목 할 일(`GET /api/me/todo?courseId=&size=`): 첫 쪽과 `total`. 담당 과목이면 비어 있고, 내 과목이 아니면 404다.
 * 키는 서버 과목 ID다. 전체 목록은 할 일 팝업(`todoEntry`)이 따로 읽는다.
 */
const courseTodo = createResource(async (courseId) => {
  const params = new URLSearchParams({ courseId: String(courseId), size: String(COURSE_TODO_PAGE) });
  const page = await apiGet(`/api/me/todo?${params}`);
  return {
    serverTime: page.serverTime,
    total: Number(page.total) || 0,
    nextCursor: page.nextCursor || null,
    items: Array.isArray(page && page.items) ? page.items : [],
  };
});

export function courseTodoEntry(courseId) {
  return courseTodo.peek(String(courseId));
}

export function reloadCourseTodo(courseId) {
  if (live()) courseTodo.load(String(courseId), courseId);
}

/** `node`가 붙은 뒤 부른다. 새 화면 이동이거나 받아 둔 것이 없으면 읽고, 포커스·탭 복귀 때 다시 읽는다. */
export function watchCourseTodo(node, courseId, paint) {
  if (!live() || !node.isConnected) return;
  const key = String(courseId);
  courseTodo.watch(node, key, paint, () => courseTodo.load(key, courseId));
  if (freshVisit("course-todo", key) === "entry" || !courseTodo.peek(key)) courseTodo.load(key, courseId);
}

// ---------- server date-times (ADR-0039) ----------
// An offset-less server date-time ("2026-11-12T23:59:00") is wall-clock time in the institution
// zone and is used as written. A string with `Z` or an offset is an absolute instant and is turned
// into the institution zone's wall clock. Neither path reads the browser's zone, so every text and
// every difference below is the same wherever the browser runs.

const DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$/i;
const FIXED_ZONE = /^(?:Z|UTC|GMT|(?:UTC|GMT)?([+-])(\d{2})(?::?(\d{2}))?)$/i;
const zoneParts = new Map(); // zone id -> Intl formatter

function instantWall(ms) {
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
export function wallMs(value) {
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
export function wallParts(value) {
  const ms = wallMs(value);
  if (Number.isNaN(ms)) return null;
  const t = new Date(ms);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate(), hh: t.getUTCHours(), mm: t.getUTCMinutes(), ss: t.getUTCSeconds(), dow: t.getUTCDay() };
}

/** "2026-11-12T23:59" of a server date-time on the institution wall clock (for `datetime-local`), or "". */
export function wallText(value) {
  const p = wallParts(value);
  if (!p) return "";
  const z = (n) => String(n).padStart(2, "0");
  return `${p.y}-${z(p.m)}-${z(p.d)}T${z(p.hh)}:${z(p.mm)}`;
}

/**
 * An `Intl.DateTimeFormat` for server date-times: `format(value)` shows the institution wall clock
 * with `options` (no `timeZone`), "" when `value` is not a date-time.
 */
export function wallFormat(locale, options) {
  const format = new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC" });
  return {
    format(value) {
      const ms = wallMs(value);
      return Number.isNaN(ms) ? "" : format.format(new Date(ms));
    },
  };
}

function datePart(value) {
  const p = wallParts(value);
  return p ? Date.UTC(p.y, p.m - 1, p.d) : NaN;
}

/** D-day of `closeAt` against the server clock (`serverTime`), by institution calendar day. */
export function ddayAt(closeAt, serverTime) {
  const days = Math.round((datePart(closeAt) - datePart(serverTime)) / 86400000);
  if (Number.isNaN(days)) return "";
  if (days < 0) return "마감";
  return days === 0 ? "D-day" : `D-${days}`;
}

/** "11월 12일 (목) 오후 11:59" for a server date-time on the institution wall clock. */
export function koDateTime(value) {
  const match = DATE_TIME.exec(String(value || "").trim());
  const p = match && match[4] != null ? wallParts(value) : null;
  if (!p) return "";
  return `${p.m}월 ${p.d}일 (${"일월화수목금토"[p.dow]}) ${p.hh < 12 ? "오전" : "오후"} ${p.hh % 12 || 12}:${String(p.mm).padStart(2, "0")}`;
}

/** "2026.11.10" for a server date-time (institution calendar day) or date. */
export function koDate(value) {
  const p = wallParts(value);
  const z = (n) => String(n).padStart(2, "0");
  return p ? `${p.y}.${z(p.m)}.${z(p.d)}` : "";
}

/** Korean message for a failed home or to-do request. */
export function homeMessage(error) {
  if (!(error instanceof ApiError)) return "ASTRA 서버에서 정보를 받지 못했습니다. 잠시 후 다시 시도하세요.";
  if (error.status === 401) return "ASTRA 서버 로그인이 필요합니다.";
  if (error.status === 429) return retryLater(error);
  if (error.status === 0) return error.message;
  return "ASTRA 서버에서 정보를 받지 못했습니다. 잠시 후 다시 시도하세요.";
}
