// Connection to the ASTRA backend, kept in memory only (never persisted).
// status: "off"  — server.mjs has no ASTRA_API_ORIGIN, or the probe failed (mock mode)
//         "anon" — backend enabled, not logged in (401)
//         "ready" — logged in; `me` and `courses` are loaded
// methods: the login methods the server offers (`GET /api/auth/methods`): "oidc" (school
//          account), "eclassPassword", "devLogin" (local run only), "breakGlass" (hidden).
//          null when the list could not be read.
import { apiGet, apiSend, onUnauthorized, ApiError } from "./api.ts";
import { courses as sampleCourses, COURSE_ORDER } from "../data.ts";

export type BackendStatus = "off" | "anon" | "ready";
export type CourseRole = "INSTRUCTOR" | "STUDENT";

/** `GET /api/me`. */
export interface Me {
  userId: number | string;
  name?: string | null;
  role?: string | null; // "PROFESSOR" | "STUDENT" | …
  stepUpMethod?: string | null;
}

export interface CourseTerm {
  termId?: number | string | null;
  name?: string | null;
  current?: boolean | null;
}

export interface CourseSchedule {
  dayOfWeek?: number | null;
  startTime?: string | null;
  endTime?: string | null;
  periodStart?: number | null;
  periodEnd?: number | null;
  room?: string | null;
}

/** One record of `GET /api/me/courses` (also the course shape of `/api/me/home` and catalog items). */
export interface MyCourse {
  courseId: number | string;
  key?: string | null;
  role?: CourseRole | string | null;
  name?: string | null;
  code?: string | null;
  section?: string | null;
  credit?: number | string | null;
  type?: string | null; // "REGULAR" | "NONREGULAR"
  lectureType?: string | null;
  learningPeriod?: string | null;
  coverTemplate?: string | null;
  unreadNotices?: number | null;
  term?: CourseTerm | null;
  schedules?: CourseSchedule[] | null;
  instructors?: string[] | null;
  /** Learner count: an integer for courses the caller teaches, null for student courses. */
  learners?: number | null;
}

export interface BackendSnapshot {
  status: BackendStatus;
  settled: boolean;
  me: Me | null;
  courses: MyCourse[];
  methods: string[] | null;
}

type Loaded = Omit<BackendSnapshot, "settled">;

const PERSONAS = { student: "21101234", professor: "P-1042" };
const KOREAN = /[\uAC00-\uD7A3]/;
const LOGIN_FAILED = "ASTRA 서버에 로그인하지 못했습니다. 잠시 후 다시 시도하세요.";
const RETURN_MAX = 512;

const state: BackendSnapshot = { status: "off", settled: false, me: null, courses: [], methods: null };
const listeners = new Set<(next: BackendSnapshot) => void>();
let connecting: Promise<BackendSnapshot> | null = null;
// Only users signed in by this page through `loginAs` may follow the sample role switch.
// Recheck the shared cookie's current session before switching: another tab may have
// replaced the developer session with a real school session.
const devUsers = new Set<Me["userId"]>();
// Institution time zone (`timeZone` on `GET /api/auth/methods`, ADR-0039). Offset-less server
// date-times are wall-clock time in this zone. Until the server answers it is the backend default.
const DEFAULT_ZONE = "Asia/Seoul";
let zone = DEFAULT_ZONE;

function signature(): string {
  return JSON.stringify([state.status, state.settled, state.me, state.courses, state.methods, zone]);
}

function update(patch: Partial<BackendSnapshot>): void {
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

function snapshot(): BackendSnapshot {
  return { status: state.status, settled: state.settled, me: state.me, courses: state.courses, methods: state.methods };
}

onUnauthorized((): void => {
  if (state.status !== "ready") return;
  devUsers.clear();
  update({ status: "anon", me: null, courses: [] });
});

export function backendState(): BackendSnapshot {
  return snapshot();
}

export function backendStatus(): BackendStatus {
  return state.status;
}

/** True once the first connect() finished (either way). */
export function backendSettled(): boolean {
  return state.settled;
}

export function subscribe(fn: (next: BackendSnapshot) => void): () => boolean {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// ---------- course identity (STAC-106) ----------
// A server course is addressed in the app (`?kj=`) as `c<courseId>`. When its `Course.key` (legacy KJKEY, nullable)
// equals a sample course id (`bi`, `algo`, …), that key addresses it too and is its preferred `kj`.

const SAMPLE_IDS = new Set<string>(sampleCourses.map((course) => course.id));
const SERVER_KJ = /^c([1-9][0-9]{0,17})$/;

/** The `kj` of a server course record (`/api/me/courses`, `/api/me/home` courses, catalog items): its sample-id key, else `c<courseId>`. */
export function kjOf(remote: { courseId?: number | string | null; key?: string | null } | null | undefined): string | null {
  if (!remote || remote.courseId == null) return null;
  return remote.key && SAMPLE_IDS.has(remote.key) ? remote.key : `c${remote.courseId}`;
}

/** The user's `/api/me/courses` record that `kj` addresses (`c<courseId>`, or a sample-id key), or null. `ready` only. */
export function serverCourseOf(kj: string | null | undefined): MyCourse | null {
  if (state.status !== "ready" || !kj) return null;
  const text = String(kj);
  const id = SERVER_KJ.exec(text);
  if (id) return state.courses.find((item) => String(item.courseId) === id[1]) || null;
  return SAMPLE_IDS.has(text) ? state.courses.find((item) => item.key === text) || null : null;
}

/** Backend courseId for an app course id (`?kj=`), or null. */
export function courseIdFor(kj: string | null | undefined): MyCourse["courseId"] | null {
  const course = serverCourseOf(kj);
  return course ? course.courseId : null;
}

/** "INSTRUCTOR" | "STUDENT" | null for an app course id. */
export function roleIn(kj: string | null | undefined): MyCourse["role"] {
  const course = serverCourseOf(kj);
  return course ? course.role ?? null : null;
}

/** The `kj` of the user's server course `courseId`, or null when it is not one of the user's courses. */
export function kjOfCourseId(courseId: number | string | null | undefined): string | null {
  if (state.status !== "ready" || courseId == null) return null;
  return kjOf(state.courses.find((item) => String(item.courseId) === String(courseId)));
}

const rank = (kj: string | null): number => (kj != null && COURSE_ORDER.includes(kj) ? COURSE_ORDER.indexOf(kj) : COURSE_ORDER.length);

/**
 * Every server course of the user as `{ kj, remote }`: sample-keyed ones first in the sample order, then the server's
 * order (current term first). `roles` (e.g. ["STUDENT"]) limits the membership role.
 */
export interface ServerCourse {
  kj: string | null;
  remote: MyCourse;
}

export function serverCourses(roles?: readonly string[] | null): ServerCourse[] {
  if (state.status !== "ready") return [];
  return state.courses
    .filter((remote) => !roles || roles.includes(remote.role ?? ""))
    .map((remote) => ({ kj: kjOf(remote), remote }))
    .sort((a, b) => rank(a.kj) - rank(b.kj));
}

/** The signed-in user's id as a string, or null when not `ready`. */
export function currentUserId(): string | null {
  return state.status === "ready" && state.me && state.me.userId != null ? String(state.me.userId) : null;
}

// ---------- data kept for one user beyond memory ----------
// Server screens keep some of the signed-in user's input in localStorage (search words, filters, document
// pages). `OWNER_KEY` records whose it is, so a different user (or nobody) found on the first settled
// `anon`/`ready` of a page load clears it, even when the previous user closed the tab without logging out.
const OWNER_KEY = "astra-data-owner";
const signOutHandlers = new Set<() => void>();
// The user whose data this tab holds; undefined until this tab first settled on `anon` or `ready`.
let tabOwner: string | null | undefined;

function storedOwner(): string | null {
  try {
    return localStorage.getItem(OWNER_KEY);
  } catch {
    return null;
  }
}

function storeOwner(id: string | null): void {
  try {
    if (id == null) localStorage.removeItem(OWNER_KEY);
    else localStorage.setItem(OWNER_KEY, id);
  } catch {
    /* private mode: nothing of the user was stored either */
  }
}

function signOut(): void {
  for (const fn of [...signOutHandlers]) {
    try {
      fn();
    } catch {
      /* one handler must not keep the others from clearing */
    }
  }
}

function settleOwner(id: string | null): void {
  const stored = storedOwner();
  if ((stored != null && stored !== id) || (tabOwner != null && tabOwner !== id)) signOut();
  tabOwner = id;
  storeOwner(id);
}

subscribe((next) => {
  if (next.status === "off") return;
  settleOwner(next.status === "ready" && next.me && next.me.userId != null ? String(next.me.userId) : null);
});

// Another tab logged out or signed in as someone else: a tab that held a user's data drops its copy at once;
// a signed-out tab holds none and only follows. Either way, re-read the shared session.
if (typeof window !== "undefined") {
  window.addEventListener("storage", (event) => {
    if (event.key !== OWNER_KEY || tabOwner === undefined || event.newValue === tabOwner) return;
    const held = tabOwner;
    tabOwner = event.newValue;
    if (held != null) signOut();
    void connect();
  });
}

/**
 * Registers `fn` to clear one kind of data kept for the signed-in user beyond memory. It runs when that
 * user goes: logout, an expired session, another user signing in (also across reloads and tabs). A reload
 * of the same user, a failed probe (`off`) and sample mode never run it.
 */
export function onSignOut(fn: () => void): void {
  signOutHandlers.add(fn);
}

/**
 * False while this tab holds a user's data that is no longer the stored owner's (another tab logged out or
 * signed in as someone else and this tab has not caught up): it must not write that data back. A tab that
 * holds no user's data (sample mode, not settled yet, signed out) may always save.
 */
export function mayStoreUserData(): boolean {
  return tabOwner == null || tabOwner === storedOwner();
}

/** The institution time zone id ("Asia/Seoul", "UTC", "+09:00"); the backend default until read. */
export function institutionZone(): string {
  return zone;
}

function knownZone(value: unknown): string | null {
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

async function probe(): Promise<boolean> {
  if (typeof fetch !== "function") return false;
  try {
    const response = await fetch("/api/backend", { credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } });
    if (!response.ok) return false;
    const body: unknown = await response.json();
    return Boolean(body && typeof body === "object" && "enabled" in body && body.enabled === true);
  } catch {
    return false;
  }
}

// Which login buttons to show. Read without a session; a failure leaves it unknown (null).
interface AuthMethods {
  methods?: unknown;
  timeZone?: unknown;
}

async function methods(): Promise<string[] | null> {
  try {
    const body = await apiGet<AuthMethods | null>("/api/auth/methods");
    zone = knownZone(body && body.timeZone) || DEFAULT_ZONE;
    return body && Array.isArray(body.methods) ? body.methods.filter((item): item is string => typeof item === "string") : null;
  } catch {
    return null;
  }
}

async function load(): Promise<Loaded> {
  if (!(await probe())) return { status: "off", me: null, courses: [], methods: null };
  const offered = methods();
  try {
    const me = await apiGet<Me>("/api/me");
    const courses = await apiGet<unknown>("/api/me/courses");
    return { status: "ready", me, courses: Array.isArray(courses) ? courses as MyCourse[] : [] /* server list of course records */, methods: await offered };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return { status: "anon", me: null, courses: [], methods: await offered };
    return { status: "off", me: null, courses: [], methods: null };
  }
}

/** True when the server lists `method` ("oidc", "eclassPassword", "devLogin") in `GET /api/auth/methods`. */
export function offers(method: string): boolean {
  return Array.isArray(state.methods) && state.methods.includes(method);
}

/**
 * `/api/auth/login?returnTo=…` for the page now on screen. `returnTo` is only this page's own
 * path and query (same origin by construction); anything the backend would refuse becomes `/`.
 */
export function schoolLoginHref(where: Pick<Location, "pathname" | "search"> | null | undefined = globalThis.location): string {
  const path = where ? `${where.pathname || ""}${where.search || ""}` : "";
  const safe = path.startsWith("/") && !path.startsWith("//") && path.length <= RETURN_MAX && /^[\x21-\x7e]+$/.test(path) && !path.includes("\\");
  return `/api/auth/login?returnTo=${encodeURIComponent(safe ? path : "/")}`;
}

/** School account login: a top-level navigation (the backend redirects to the school IdP). */
export function startSchoolLogin(): void {
  devUsers.clear();
  location.assign(schoolLoginHref());
}

/** Probe and (re)load the session and login methods. Never throws; concurrent calls share one load. */
export function connect(): Promise<BackendSnapshot> {
  if (connecting) return connecting;
  connecting = load()
    .catch((): Loaded => ({ status: "off", me: null, courses: [], methods: null }))
    .then((next) => {
      update({ ...next, settled: true });
      return snapshot();
    })
    .finally(() => { connecting = null; });
  return connecting;
}

function retryMessage(error: ApiError): string {
  return `로그인 요청이 많습니다. ${error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
}

/** Developer login (only when the server offers "devLogin"), then reload. Throws an Error with a user message. */
export async function loginAs(externalRef: string | number): Promise<BackendSnapshot> {
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
export async function loginWithEclass(studentId: string | number | null | undefined, password: string): Promise<BackendSnapshot> {
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

export const LOGOUT_FAILED = "로그아웃하지 못했습니다. 아직 로그인되어 있습니다. 잠시 뒤 다시 시도하세요.";

/**
 * Ends the server session: 204, or 401 (the session was already gone), counts as ended; then reload as
 * `anon`. Any other outcome (no connection, 5xx, 403) leaves the session and every local copy as they
 * are and throws an Error with `LOGOUT_FAILED`.
 */
export async function logout(): Promise<BackendSnapshot> {
  try {
    await apiSend("POST", "/api/auth/logout");
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401)) throw new Error(LOGOUT_FAILED);
  }
  devUsers.clear();
  update({ status: "anon", me: null, courses: [] });
  return connect();
}

/** The sample persona for a frontend role ("student" | "professor"). */
export function personaFor(role: string): string {
  return role === "professor" ? PERSONAS.professor : PERSONAS.student;
}

/**
 * Keep the backend session in step with the sample role switch, but only for a developer
 * user signed in by this page. Always reload first: another tab may have replaced the
 * shared session cookie with a school account, even if login methods have since changed.
 * Existing sessions of unknown origin are never switched. Errors are returned, not thrown.
 */
export async function followRole(role: string): Promise<Error | null> {
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
    return error instanceof Error ? error : new Error(String(error));
  }
}
