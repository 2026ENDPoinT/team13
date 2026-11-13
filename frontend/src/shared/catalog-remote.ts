// 카탈로그와 수강신청(`/api/catalog/**`, `/api/enrollments/**`, `/api/me/courses`, `/api/me/applications`,
// `/api/courses/{courseId}/enrollment-requests`, 담당자의 `/api/courses/{courseId}/students`)을 ASTRA 백엔드에서 읽고 보낸다(ADR-0026).
// 조회는 신청을 만들지 않는다. 신청·취소·승인 뒤에는 관련 캐시를 버리고, 수강이 확정되거나
// 확정이 풀리면 backend.js의 과목 목록과 홈 집계를 다시 받는다. 응답은 메모리에만 둔다.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { connect, subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";
import { reloadHome } from "./home-remote.ts";

export type Id = number | string;

export interface CatalogTerm {
  termId: number | string;
  name: string;
  current?: boolean;
}

export interface CodeName {
  code: string;
  name: string;
}

/** `GET /api/catalog/filters`: 학기·학과(정규) 또는 과목분류(`?division=`) 선택지. */
export interface CatalogFilters {
  terms?: CatalogTerm[];
  departments?: CodeName[];
  categories?: CodeName[];
}

/** 수업 시간 한 칸. 시각이 없으면 교시. */
export interface CourseSchedule {
  dayOfWeek: number;
  startTime?: string | null;
  endTime?: string | null;
  periodStart?: number | null;
  periodEnd?: number | null;
  room?: string | null;
}

/** 정규과목(목록 줄과 상세의 `course`). */
export interface CatalogCourse {
  courseId: number | string;
  code?: string | null;
  name: string;
  section?: string | null;
  type?: string;
  term?: CatalogTerm | null;
  orgSect?: string | null;
  departmentName?: string | null;
  credit?: number | null;
  gradeYear?: number | string | null;
  completionType?: string | null;
  instructors?: string[] | null;
  schedules?: CourseSchedule[] | null;
  auditAllowed?: boolean;
  merged?: boolean;
  enrolled?: number | null;
  capacity?: number | null;
}

/** 비정규과목(목록 줄과 상세의 `offering`). */
export interface CatalogOffering {
  section?: string | null;
  courseId: number | string;
  code?: string | null;
  name: string;
  division?: string | null;
  lectureType?: string | null;
  audiences?: string[] | null;
  state?: string;
  approvalMode?: string | null;
  applicationOpenAt?: string | null;
  applicationCloseAt?: string | null;
  learningStartDate?: string | null;
  learningEndDate?: string | null;
  learningPeriod?: string | null;
  roundLabel?: string | null;
  cancelAllowed?: boolean;
  cancelCloseAt?: string | null;
  instructors?: string[] | null;
  schedules?: CourseSchedule[] | null;
  enrolled?: number | null;
  capacity?: number | null;
}

/** 신청 한 건(receipt, 내 신청 목록의 줄). */
export interface EnrollmentReceipt {
  enrollmentId: number;
  kind?: string;
  status: string;
  version: number;
  cancellable?: boolean;
  appliedAt?: string | null;
  decidedAt?: string | null;
  cancelledAt?: string | null;
  reason?: string | null;
  course?: CatalogCourse | CatalogOffering | null;
}

/** 상세의 `viewer`: 지금 사용자가 신청할 수 있는지와 그 신청. */
export interface CatalogViewer {
  role?: string | null;
  eligible?: boolean;
  reasons?: string[];
  canRequestAudit?: boolean;
  application?: EnrollmentReceipt | null;
  audit?: EnrollmentReceipt | null;
}

export interface CatalogDetail {
  serverTime?: string;
  course?: CatalogCourse;
  offering?: CatalogOffering;
  viewer?: CatalogViewer | null;
}

/** 쪽 번호 목록 응답. */
export interface NumberedPage<T> {
  items?: T[];
  total: number;
  page: number;
  size?: number;
  serverTime?: string;
}

/** `GET /api/me/courses`의 줄. */
export interface MyCourseItem {
  courseId: number | string;
  code?: string | null;
  name: string;
  section?: string | null;
  type?: string;
  role?: string | null;
  term?: CatalogTerm | null;
  offering?: CatalogOffering | null;
  enrollment?: EnrollmentReceipt | null;
  instructors?: string[] | null;
  schedules?: CourseSchedule[] | null;
}

/** 담당자가 처리할 신청 한 건. */
export interface EnrollmentRequest {
  enrollmentId: number;
  kind: string;
  status: string;
  version: number;
  studentName?: string | null;
  studentNumber?: string | null;
  appliedAt?: string | null;
  reason?: string | null;
}

/** 담당자 수강생 명단의 줄. */
export interface CourseStudent {
  userId?: number;
  name?: string | null;
  studentNumber?: string | null;
  role?: string;
  progressPercent?: number | null;
  lastAccessAt?: string | null;
}

export interface RegularFilter {
  term: string;
  org: string;
  dept: string;
  audit: boolean;
  q: string;
  page: number;
}

export interface OfferingFilter {
  open: boolean;
  aud: string;
  div: string;
  cat: string;
  types: string[];
  q: string;
  page: number;
}

export interface RosterFilter {
  q: string;
  sort: string;
  order: string;
  page: number;
}

export const CATALOG_PAGE = 20;
export const REQUEST_PAGE = 20;
export const AUDIT_REASON_MAX = 1000;
export const DECISION_REASON_MAX = 500;

const enc = encodeURIComponent;

export const catalogFilters = createResource<CatalogFilters, [termId?: Id | null]>((termId) => apiGet<CatalogFilters>(`/api/catalog/filters${termId ? `?term=${enc(termId)}` : ""}`));
/** 키는 질의 문자열 그대로다(`regularQuery`·`offeringQuery`). */
export const regularPages = createResource<NumberedPage<CatalogCourse>, [query: string]>((query) => apiGet<NumberedPage<CatalogCourse>>(`/api/catalog/courses?${query}`));
export const offeringPages = createResource<NumberedPage<CatalogOffering>, [query: string]>((query) => apiGet<NumberedPage<CatalogOffering>>(`/api/catalog/offerings?${query}`));
export const regularDetails = createResource<CatalogDetail, [courseId: Id]>((courseId) => apiGet<CatalogDetail>(`/api/catalog/courses/${enc(courseId)}`));
export const offeringDetails = createResource<CatalogDetail, [courseId: Id]>((courseId) => apiGet<CatalogDetail>(`/api/catalog/offerings/${enc(courseId)}`));
/** 내 과목(`GET /api/me/courses`). 키는 질의 문자열이다(`myCoursesQuery`). */
export const myCourses = createResource<NumberedPage<MyCourseItem>, [query: string]>((query) => apiGet<NumberedPage<MyCourseItem>>(`/api/me/courses${query ? `?${query}` : ""}`));
export const enrollmentRequests = createResource<NumberedPage<EnrollmentRequest>, [courseId: Id, page: number]>((courseId, page) => apiGet<NumberedPage<EnrollmentRequest>>(
  `/api/courses/${enc(courseId)}/enrollment-requests?page=${enc(page)}&size=${REQUEST_PAGE}`,
));
export const requestKey = (courseId: Id, page: number): string => `${courseId}|${page}`;

export const APPLICATION_PAGE = 20;
export const ROSTER_PAGE = 50;
export const APPLICATION_KINDS: readonly string[] = ["NONREGULAR", "AUDIT"];
/** 처리를 기다리는 신청 상태. 이 신청은 `/api/me/courses`에 없고 `myApplications`에만 있다. */
export const PENDING_STATUSES = new Set<string>(["PENDING", "REQUESTED", "WAITLISTED"]);

/**
 * 내 신청(`GET /api/me/applications?kind&page&size`). 키는 `applicationsKey(kind, page)`다. 응답
 * `{items[{enrollmentId, kind, status, version, cancellable, appliedAt, decidedAt, cancelledAt, reason, course}], total, page, size, serverTime}`.
 */
export const myApplications = createResource<NumberedPage<EnrollmentReceipt>, [kind: string | null | undefined, page: number]>((kind, page) => {
  const query = new URLSearchParams();
  if (kind && APPLICATION_KINDS.includes(kind)) query.set("kind", kind);
  query.set("page", String(page));
  query.set("size", String(APPLICATION_PAGE));
  return apiGet<NumberedPage<EnrollmentReceipt>>(`/api/me/applications?${query}`);
});
export const applicationsKey = (kind: string | null | undefined, page: number): string => `${kind || ""}|${page}`;

/** 비정규 과목분류 선택지(`GET /api/catalog/filters?division=`)의 `categories[{code, name}]`. `name`은 늘 문자열이다(저장한 이름이 없으면 서버가 코드로 채운다). 키는 분류 코드(""는 전체)다. */
export const offeringFilters = createResource<CatalogFilters, [division?: string | null]>((division) => apiGet<CatalogFilters>(`/api/catalog/filters${division ? `?division=${enc(division)}` : ""}`));

/** 담당자의 수강생 명단(`GET /api/courses/{courseId}/students?…`). 키는 `rosterKey(courseId, rosterQuery(filter))`다. */
export const courseRosters = createResource<NumberedPage<CourseStudent>, [courseId: Id, query: string]>((courseId, query) => apiGet<NumberedPage<CourseStudent>>(`/api/courses/${enc(courseId)}/students?${query}`));
export const rosterKey = (courseId: Id, query: string): string => `${courseId}|${query}`;
export const ROSTER_SORT_LABEL: Record<string, string> = { name: "이름", studentNumber: "학번" };
export const ROSTER_ROLE_LABEL: Record<string, string> = { STUDENT: "수강생", AUDITOR: "청강생" };

const ORG = new Set(["A", "G", "F"]);
const AUDIENCE = new Set(["1", "2", "3", "4", "5", "6"]);
const DIVISION = new Set(["B", "C", "K"]);
const LECTURE = ["이클래스", "이러닝수업", "블렌디드", "플립"];
const CODE = /^[A-Za-z0-9_-]{1,20}$/;
const ID = /^[1-9]\d{0,15}$/;

function pageOf(params: URLSearchParams): number {
  const page = Number(params.get("page"));
  return Number.isInteger(page) && page >= 1 && page <= 10000 ? page : 1;
}

function queryText(params: URLSearchParams): string {
  return String(params.get("q") || "").trim().slice(0, 100);
}

/** 화면 주소(`term`, `org`, `dept`, `audit`, `q`, `page`)에서 정규과목 목록 질의. 서버가 거절할 값은 버린다. */
export function regularFilter(params: URLSearchParams): RegularFilter {
  const term = params.get("term") || "";
  const org = params.get("org") || "";
  const dept = params.get("dept") || "";
  return { term: ID.test(term) ? term : "", org: ORG.has(org) ? org : "", dept: CODE.test(dept) ? dept : "", audit: params.get("audit") === "1", q: queryText(params), page: pageOf(params) };
}

export function regularQuery(filter: RegularFilter): string {
  const query = new URLSearchParams();
  if (filter.term) query.set("term", filter.term);
  if (filter.org) query.set("orgSect", filter.org);
  if (filter.dept) query.set("department", filter.dept);
  if (filter.audit) query.set("auditable", "true");
  if (filter.q) query.set("q", filter.q);
  query.set("page", String(filter.page));
  query.set("size", String(CATALOG_PAGE));
  return query.toString();
}

/**
 * 화면 주소(`open`, `aud`, `div`, `cat`, `type`(여러 개), `q`, `page`)에서 비정규과목 목록 질의. `cat`은 모양만 보고,
 * 과목분류 선택지에 있는 값인지는 화면이 `offeringFilters` 응답으로 다시 거른다.
 */
export function offeringFilter(params: URLSearchParams): OfferingFilter {
  const audValue = params.get("aud") || "";
  const divValue = params.get("div") || "";
  const catValue = params.get("cat") || "";
  const aud = AUDIENCE.has(audValue) ? audValue : "";
  const div = DIVISION.has(divValue) ? divValue : "";
  const cat = CODE.test(catValue) ? catValue : "";
  const types = LECTURE.filter((type) => params.getAll("type").includes(type));
  return { open: params.get("open") === "1", aud, div, cat, types, q: queryText(params), page: pageOf(params) };
}

export function offeringQuery(filter: OfferingFilter): string {
  const query = new URLSearchParams();
  if (filter.open) query.set("open", "true");
  if (filter.aud) query.set("audience", filter.aud);
  if (filter.div) query.set("division", filter.div);
  if (filter.cat) query.append("category", filter.cat);
  for (const type of filter.types) query.append("lectureType", type);
  if (filter.q) query.set("q", filter.q);
  query.set("page", String(filter.page));
  query.set("size", String(CATALOG_PAGE));
  return query.toString();
}

export function myCoursesQuery({ type, termId, division, q }: { type?: string; termId?: Id | null; division?: string | null; q?: string | null } = {}): string {
  const query = new URLSearchParams();
  if (type) query.set("type", type);
  if (termId) query.set("termId", String(termId));
  if (division && DIVISION.has(division)) query.set("division", division);
  if (q) query.set("q", q);
  return query.toString();
}

const ROSTER_ORDERS = new Set(["asc", "desc"]);

/**
 * `pf_monitor` 주소(`q`, `sort`, `order`, `spage`)에서 수강생 명단 질의. `sort`는 `name`·`studentNumber`, `order`는
 * `asc`·`desc`만 남기고 나머지는 버린다(서버 기본값: 이름 오름차순).
 */
export function rosterFilter(params: URLSearchParams): RosterFilter {
  const sortValue = params.get("sort") || "";
  const orderValue = params.get("order") || "";
  const sort = Object.hasOwn(ROSTER_SORT_LABEL, sortValue) ? sortValue : "";
  const order = ROSTER_ORDERS.has(orderValue) ? orderValue : "";
  const raw = Number(params.get("spage"));
  const page = Number.isInteger(raw) && raw >= 1 && raw <= 10000 ? raw : 1;
  return { q: String(params.get("q") || "").trim().slice(0, 50), sort, order, page };
}

export function rosterQuery(filter: RosterFilter): string {
  const query = new URLSearchParams();
  if (filter.q) query.set("q", filter.q);
  if (filter.sort) query.set("sort", filter.sort);
  if (filter.order) query.set("order", filter.order);
  query.set("page", String(filter.page));
  query.set("size", String(ROSTER_PAGE));
  return query.toString();
}

/** `srv-<courseId>` → 숫자 문자열, 아니면 null. */
export function serverIdOf(value: unknown): string | null {
  const match = /^srv-([1-9]\d{0,15})$/.exec(String(value || ""));
  return match ? match[1] : null;
}

export const LECTURE_TYPES: readonly string[] = LECTURE;
export const ORG_LABEL: Record<string, string> = { A: "대학", G: "대학원", F: "미래융합대학" };
export const DIVISION_LABEL: Record<string, string> = { B: "비교과", C: "기초학력", K: "K-digital" };
/** 신청대상 코드. 표시 순서는 원본 화면과 같다. */
export const AUDIENCE_LABEL: Record<string, string> = { 3: "학생", 1: "교수", 2: "교직원", 5: "조교", 6: "대학원생", 4: "졸업생" };
export const AUDIENCE_ORDER: readonly string[] = ["3", "1", "2", "5", "6", "4"];
export const STATE_LABEL: Record<string, string> = { UPCOMING: "접수 예정", OPEN: "접수 중", CLOSED: "접수 마감" };
export const STATUS_LABEL: Record<string, string> = {
  PENDING: "승인 대기",
  WAITLISTED: "대기",
  REQUESTED: "청강 승인 대기",
  ENROLLED: "수강 확정",
  REJECTED: "반려",
  CANCELLED: "취소",
};
export const KIND_LABEL: Record<string, string> = { REGISTRAR: "학사 수강", NONREGULAR: "비정규 신청", AUDIT: "청강" };
export const REASON_LABEL: Record<string, string> = {
  not_open_yet: "아직 신청 기간이 아닙니다.",
  application_closed: "신청 기간이 지났습니다.",
  not_target: "신청대상이 아닙니다.",
  own_course: "본인이 담당하는 과목은 신청할 수 없습니다.",
  already_applied: "이미 신청했습니다.",
  seat_full: "정원이 찼습니다.",
  audit_not_allowed: "청강을 받지 않는 과목입니다.",
  already_member: "이미 이 과목의 구성원입니다.",
};

export function reasonText(code: string | null | undefined): string {
  return (code != null && REASON_LABEL[code]) || "지금은 신청할 수 없습니다.";
}

const DOW = "일월화수목금토";

function hm(value: unknown): string {
  const match = /^(\d{2}):(\d{2})/.exec(String(value || ""));
  return match ? `${match[1]}:${match[2]}` : "";
}

/** 수업 시간 한 줄: "목 15:00–18:00 · 창학관 202" 또는 교시만 있으면 "목 6–8교시". */
export function scheduleText(schedules: readonly CourseSchedule[] | null | undefined): string {
  const list = Array.isArray(schedules) ? schedules : [];
  if (!list.length) return "시간 미정";
  return list.map((slot) => {
    const day = DOW[slot.dayOfWeek] || "";
    const time = slot.startTime ? `${hm(slot.startTime)}–${hm(slot.endTime)}` : slot.periodStart ? `${slot.periodStart}–${slot.periodEnd || slot.periodStart}교시` : "";
    return [`${day} ${time}`.trim(), slot.room].filter(Boolean).join(" · ");
  }).join(", ");
}

/** "2026.11.10" (LocalDate 또는 LocalDateTime). */
export function dotDate(value: unknown): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value || ""));
  return match ? `${match[1]}.${match[2]}.${match[3]}` : "";
}

/** "2026.11.10 18:00" (LocalDateTime). */
export function dotDateTime(value: unknown): string {
  const time = /T(\d{2}):(\d{2})/.exec(String(value || ""));
  const date = dotDate(value);
  return date && time ? `${date} ${time[1]}:${time[2]}` : date;
}

export function rangeText(from: unknown, to: unknown, format: (value: unknown) => string = dotDate): string {
  const a = format(from);
  const b = format(to);
  if (!a && !b) return "정해지지 않음";
  return `${a || "—"} ~ ${b || "—"}`;
}

export function instructorsText(list: readonly (string | null | undefined)[] | null | undefined): string {
  const names = Array.isArray(list) ? list.filter((name): name is string => Boolean(name)) : [];
  if (!names.length) return "담당자 미정";
  return names.length > 1 ? `${names[0]} 외 ${names.length - 1}명` : names[0];
}

export function seatsText(offering: { enrolled?: number | null; capacity?: number | null }): string {
  const enrolled = Number(offering.enrolled) || 0;
  return offering.capacity == null ? `${enrolled}명 / 제한 없음` : `${enrolled} / ${offering.capacity}명`;
}

export function termName(term: { name?: string | null } | null | undefined): string {
  return term && term.name ? term.name : "";
}

const inflight = new Map<string, string>(); // fingerprint -> key: 응답 없이 끝난 같은 요청의 재시도는 같은 key를 쓴다

// 요청이 서버에 닿았을 수 있는 실패: 같은 요청을 다시 보낼 때 key를 유지한다.
const uncertain = (error: unknown): boolean => error instanceof ApiError
  && ([0, 502, 503, 504].includes(error.status) || (error.status === 409 && error.code === "idempotency_in_progress"));

async function keyed<T>(fingerprint: string, send: (key: string) => Promise<T>): Promise<T> {
  const key = inflight.get(fingerprint) || newRequestKey();
  inflight.set(fingerprint, key);
  try {
    const result = await send(key);
    inflight.delete(fingerprint);
    return result;
  } catch (error) {
    if (!uncertain(error)) inflight.delete(fingerprint);
    throw error;
  }
}

subscribe(() => inflight.clear());

/** 수강 상태가 바뀐 과목의 캐시를 버린다. 강의실 권한이 생기거나 없어졌으면 과목 목록과 홈을 다시 받는다. */
export function afterEnrollmentChange(courseId: Id, { membership = false }: { membership?: boolean } = {}): void {
  const id = String(courseId);
  offeringPages.drop(() => true);
  regularPages.drop(() => true);
  offeringDetails.drop((key) => key === id);
  regularDetails.drop((key) => key === id);
  myCourses.drop(() => true);
  enrollmentRequests.drop((key) => key.startsWith(`${id}|`));
  myApplications.drop(() => true);
  courseRosters.drop((key) => key.startsWith(`${id}|`));
  if (membership) {
    void connect();
    reloadHome();
  }
}

/**
 * 비정규 과정 수강신청. `Idempotency-Key`는 시도마다 새로 만들고, 응답 없이 끝난 신청을 다시 누르면 같은 key로
 * 보내 서버가 처음 receipt(같은 `enrollmentId`)를 돌려주게 한다. 201 receipt `{enrollmentId, status, version, …}`.
 */
export async function applyOffering(courseId: Id): Promise<EnrollmentReceipt | null> {
  const receipt = await keyed(`apply|${courseId}`, (key) => apiSend<EnrollmentReceipt | null>("POST", `/api/catalog/offerings/${enc(courseId)}/enrollments`, undefined, { idempotencyKey: key }));
  afterEnrollmentChange(courseId, { membership: Boolean(receipt && receipt.status === "ENROLLED") });
  return receipt;
}

/** 정규과목 청강 신청(사유 일반 텍스트). REQUESTED receipt. */
export async function requestAudit(courseId: Id, reason: string): Promise<EnrollmentReceipt> {
  const receipt = await keyed(`audit|${courseId}|${reason}`, (key) => apiSend<EnrollmentReceipt>("POST", `/api/catalog/courses/${enc(courseId)}/audit-requests`, { reason }, { idempotencyKey: key }));
  afterEnrollmentChange(courseId);
  return receipt;
}

/** REQUESTED인 청강 사유 수정. 읽은 `version`이 다르면 409 `version_conflict`. */
export async function updateAuditReason(courseId: Id, enrollmentId: Id, reason: string, version: number): Promise<EnrollmentReceipt> {
  const receipt = await apiSend<EnrollmentReceipt>("PUT", `/api/enrollments/${enc(enrollmentId)}/reason`, { reason, version });
  afterEnrollmentChange(courseId);
  return receipt;
}

/** 본인 신청 취소. 상태로 멱등이라 이미 취소했으면 같은 receipt가 온다. */
export async function cancelEnrollment(courseId: Id, enrollmentId: Id, { wasEnrolled = false }: { wasEnrolled?: boolean } = {}): Promise<EnrollmentReceipt> {
  const receipt = await apiSend<EnrollmentReceipt>("POST", `/api/enrollments/${enc(enrollmentId)}/cancel`);
  afterEnrollmentChange(courseId, { membership: wasEnrolled });
  return receipt;
}

/** 담당자의 승인(APPROVE)·반려(REJECT). 읽은 `version`을 보내고, 응답 없이 끝난 같은 처리는 같은 key로 다시 보낸다. */
export async function decideEnrollment(courseId: Id, enrollmentId: Id, decision: string, version: number, reason?: string | null): Promise<EnrollmentReceipt> {
  const body: { decision: string; version: number; reason?: string } = { decision, version };
  if (reason) body.reason = reason;
  const receipt = await keyed(`decide|${courseId}|${enrollmentId}|${decision}|${version}|${reason || ""}`,
    (key) => apiSend<EnrollmentReceipt>("POST", `/api/courses/${enc(courseId)}/enrollments/${enc(enrollmentId)}/decision`, body, { idempotencyKey: key }));
  afterEnrollmentChange(courseId);
  return receipt;
}

export function isConflict(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && error.status === 409 && (!code || error.code === code);
}

/** 실패한 카탈로그·수강신청 요청의 한국어 문구. 409 코드는 서버 `detail`(고정 문장)을 우선한다. */
export function enrollmentMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    const message = error !== null && typeof error === "object" && "message" in error ? error.message : null;
    return message ? String(message) : "요청을 처리하지 못했습니다.";
  }
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "과목이나 신청을 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409:
      switch (error.code) {
        case "version_conflict": return "다른 곳에서 먼저 바뀌었습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 시도하세요.";
        case "not_pending": return "이미 처리된 신청입니다. 목록을 새로 불러왔습니다.";
        case "not_editable": return "처리된 청강 신청은 고칠 수 없습니다. 최신 상태를 불러왔습니다.";
        case "seat_full": return error.detail || "정원이 찼습니다.";
        case "cancel_closed": return error.detail || "수강취소 기간이 지났습니다.";
        case "not_cancellable": return error.detail || "취소할 수 없는 신청입니다.";
        case "idempotency_in_progress": return "같은 요청을 처리하는 중입니다. 잠시 후 다시 누르세요.";
        case "idempotency_conflict": return "같은 요청 번호로 다른 요청이 처리되었습니다. 화면을 새로 불러온 뒤 다시 시도하세요.";
        default: return error.detail || reasonText(error.code);
      }
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "ASTRA 서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
