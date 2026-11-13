// Connection to the ASTRA backend, kept in memory only (never persisted).
// status: "off"  — server.mjs has no ASTRA_API_ORIGIN, or the probe failed (mock mode)
//         "anon" — backend enabled, not logged in (401)
//         "ready" — logged in; `me` and `courses` are loaded
// methods: the login methods the server offers (`GET /api/auth/methods`): "oidc" (school
//          account), "eclassPassword", "devLogin" (local run only), "breakGlass" (hidden).
//          null when the list could not be read.
import { apiGet, apiSend, onUnauthorized, ApiError } from "./api.js";
import { courses as sampleCourses, COURSE_ORDER } from "../data.js";

const PERSONAS = { student: "21101234", professor: "P-1042" };
const KOREAN = /[\uAC00-\uD7A3]/;
const LOGIN_FAILED = "ASTRA 서버에 로그인하지 못했습니다. 잠시 후 다시 시도하세요.";
const RETURN_MAX = 512;

const state = { status: "off", settled: false, me: null, courses: [], methods: null };
const listeners = new Set();
let connecting = null;
// Only users signed in by this page through `loginAs` may follow the sample role switch.
// Recheck the shared cookie's current session before switching: another tab may have
// replaced the developer session with a real school session.
const devUsers = new Set();
// Institution time zone (`timeZone` on `GET /api/auth/methods`, ADR-0039). Offset-less server
// date-times are wall-clock time in this zone. Until the server answers it is the backend default.
const DEFAULT_ZONE = "Asia/Seoul";
let zone = DEFAULT_ZONE;

function signature() {
  return JSON.stringify([state.status, state.settled, state.me, state.courses, state.methods, zone]);
}

function update(patch) {
  const before = signature();
  Object.assign(state, patch);
  if (signature() === before) return;
  for (const listener of [...listeners]) {
    try {
      listener(snapshot());
    } catch {
      /* one subscriber must not break the others */
    }
  }
}

function snapshot() {
  return { status: state.status, settled: state.settled, me: state.me, courses: state.courses, methods: state.methods };
}

onUnauthorized(() => {
  if (state.status !== "ready") return;
  devUsers.clear();
  update({ status: "anon", me: null, courses: [] });
});

export function backendState() {
  return snapshot();
}

export function backendStatus() {
  return state.status;
}

/** True once the first connect() finished (either way). */
export function backendSettled() {
  return state.settled;
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// ---------- course identity (STAC-106) ----------
// A server course is addressed in the app (`?kj=`) as `c<courseId>`. When its `Course.key` (legacy KJKEY, nullable)
// equals a sample course id (`bi`, `algo`, …), that key addresses it too and is its preferred `kj`.

const SAMPLE_IDS = new Set(sampleCourses.map((course) => course.id));
const SERVER_KJ = /^c([1-9][0-9]{0,17})$/;

/** The `kj` of a server course record (`/api/me/courses`, `/api/me/home` courses, catalog items): its sample-id key, else `c<courseId>`. */
export function kjOf(remote) {
  if (!remote || remote.courseId == null) return null;
  return remote.key && SAMPLE_IDS.has(remote.key) ? remote.key : `c${remote.courseId}`;
}

/** The user's `/api/me/courses` record that `kj` addresses (`c<courseId>`, or a sample-id key), or null. `ready` only. */
export function serverCourseOf(kj) {
  if (state.status !== "ready" || !kj) return null;
  const text = String(kj);
  const id = SERVER_KJ.exec(text);
  if (id) return state.courses.find((item) => String(item.courseId) === id[1]) || null;
  return SAMPLE_IDS.has(text) ? state.courses.find((item) => item.key === text) || null : null;
}

/** Backend courseId for an app course id (`?kj=`), or null. */
export function courseIdFor(kj) {
  const course = serverCourseOf(kj);
  return course ? course.courseId : null;
}

/** "INSTRUCTOR" | "STUDENT" | null for an app course id. */
export function roleIn(kj) {
  const course = serverCourseOf(kj);
  return course ? course.role : null;
}

/** The `kj` of the user's server course `courseId`, or null when it is not one of the user's courses. */
export function kjOfCourseId(courseId) {
  if (state.status !== "ready" || courseId == null) return null;
  return kjOf(state.courses.find((item) => String(item.courseId) === String(courseId)));
}

const rank = (kj) => (COURSE_ORDER.includes(kj) ? COURSE_ORDER.indexOf(kj) : COURSE_ORDER.length);

/**
 * Every server course of the user as `{ kj, remote }`: sample-keyed ones first in the sample order, then the server's
 * order (current term first). `roles` (e.g. ["STUDENT"]) limits the membership role.
 */
export function serverCourses(roles) {
  if (state.status !== "ready") return [];
  return state.courses
    .filter((remote) => !roles || roles.includes(remote.role))
    .map((remote) => ({ kj: kjOf(remote), remote }))
    .sort((a, b) => rank(a.kj) - rank(b.kj));
}

/** The signed-in user's id as a string, or null when not `ready`. */
export function currentUserId() {
  return state.status === "ready" && state.me && state.me.userId != null ? String(state.me.userId) : null;
}

/** The institution time zone id ("Asia/Seoul", "UTC", "+09:00"); the backend default until read. */
export function institutionZone() {
  return zone;
}

function knownZone(value) {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text || text.length > 64) return null;
  if (/^(?:Z|UTC|GMT|(?:UTC|GMT)?[+-]\d{2}(?::?\d{2})?)$/i.test(text)) return text;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: text });
    return text;
  } catch {
    return null;
  }
}

async function probe() {
  if (typeof fetch !== "function") return false;
  try {
    const response = await fetch("/api/backend", { credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } });
    if (!response.ok) return false;
    const body = await response.json();
    return Boolean(body && body.enabled === true);
  } catch {
    return false;
  }
}

// Which login buttons to show. Read without a session; a failure leaves it unknown (null).
async function methods() {
  try {
    const body = await apiGet("/api/auth/methods");
    zone = knownZone(body && body.timeZone) || DEFAULT_ZONE;
    return body && Array.isArray(body.methods) ? body.methods.filter((item) => typeof item === "string") : null;
  } catch {
    return null;
  }
}

async function load() {
  if (!(await probe())) return { status: "off", me: null, courses: [], methods: null };
  const offered = methods();
  try {
    const me = await apiGet("/api/me");
    const courses = await apiGet("/api/me/courses");
    return { status: "ready", me, courses: Array.isArray(courses) ? courses : [], methods: await offered };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return { status: "anon", me: null, courses: [], methods: await offered };
    return { status: "off", me: null, courses: [], methods: null };
  }
}

/** True when the server lists `method` ("oidc", "eclassPassword", "devLogin") in `GET /api/auth/methods`. */
export function offers(method) {
  return Array.isArray(state.methods) && state.methods.includes(method);
}

/**
 * `/api/auth/login?returnTo=…` for the page now on screen. `returnTo` is only this page's own
 * path and query (same origin by construction); anything the backend would refuse becomes `/`.
 */
export function schoolLoginHref(where = globalThis.location) {
  const path = where ? `${where.pathname || ""}${where.search || ""}` : "";
  const safe = path.startsWith("/") && !path.startsWith("//") && path.length <= RETURN_MAX && /^[\x21-\x7e]+$/.test(path) && !path.includes("\\");
  return `/api/auth/login?returnTo=${encodeURIComponent(safe ? path : "/")}`;
}

/** School account login: a top-level navigation (the backend redirects to the school IdP). */
export function startSchoolLogin() {
  devUsers.clear();
  location.assign(schoolLoginHref());
}

/** Probe and (re)load the session and login methods. Never throws; concurrent calls share one load. */
export function connect() {
  if (connecting) return connecting;
  connecting = load()
    .catch(() => ({ status: "off", me: null, courses: [], methods: null }))
    .then((next) => {
      update({ ...next, settled: true });
      return snapshot();
    })
    .finally(() => { connecting = null; });
  return connecting;
}

function retryMessage(error) {
  return `로그인 요청이 많습니다. ${error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
}

/** Developer login (only when the server offers "devLogin"), then reload. Throws an Error with a user message. */
export async function loginAs(externalRef) {
  if (!offers("devLogin")) throw new Error("이 서버에는 개발자 로그인이 없습니다.");
  try {
    await apiSend("POST", "/api/auth/dev-login", { externalRef: String(externalRef) });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) throw new Error("이 서버에는 개발자 로그인이 없습니다.");
    if (error instanceof ApiError && error.status === 429) throw new Error(retryMessage(error));
    throw new Error(LOGIN_FAILED);
  }
  const next = await connect();
  if (next.status === "ready" && next.me) devUsers.add(next.me.userId);
  return next;
}

/**
 * 학번 + e-class 비밀번호 login, then reload. The password goes only into this one
 * request body and is kept nowhere. Throws an Error with a user message.
 */
export async function loginWithEclass(studentId, password) {
  const id = String(studentId ?? "").trim();
  if (!id || !password) throw new Error("학번과 비밀번호를 입력하세요.");
  try {
    await apiSend("POST", "/api/auth/eclass-login", { studentId: id, password: String(password) });
  } catch (error) {
    if (!(error instanceof ApiError)) throw new Error(LOGIN_FAILED);
    if (error.status === 401 && error.code === "invalid_credentials") throw new Error("학번 또는 e-class 비밀번호가 맞지 않습니다.");
    if (error.status === 429) throw new Error(retryMessage(error));
    if (error.status === 502) throw new Error("학교 e-Class 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도하세요.");
    if (error.status === 400) throw new Error("학번과 비밀번호를 입력하세요.");
    throw new Error(KOREAN.test(error.detail || "") ? error.detail : LOGIN_FAILED);
  }
  devUsers.clear();
  return connect();
}

export async function logout() {
  try {
    await apiSend("POST", "/api/auth/logout");
  } catch {
    /* the session is dropped locally either way */
  }
  devUsers.clear();
  update({ status: "anon", me: null, courses: [] });
  return connect();
}

/** The sample persona for a frontend role ("student" | "professor"). */
export function personaFor(role) {
  return role === "professor" ? PERSONAS.professor : PERSONAS.student;
}

/**
 * Keep the backend session in step with the sample role switch, but only for a developer
 * user signed in by this page. Always reload first: another tab may have replaced the
 * shared session cookie with a school account, even if login methods have since changed.
 * Existing sessions of unknown origin are never switched. Errors are returned, not thrown.
 */
export async function followRole(role) {
  if (!offers("devLogin") || state.status !== "ready" || !state.me) return null;
  const current = await connect();
  if (!offers("devLogin") || current.status !== "ready" || !current.me || !devUsers.has(current.me.userId)) return null;
  const professor = role === "professor";
  const isProfessor = state.me.role === "PROFESSOR";
  if (professor === isProfessor) return null;
  try {
    await loginAs(personaFor(role));
    return null;
  } catch (error) {
    return error;
  }
}
