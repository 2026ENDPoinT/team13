// 출석부와 스마트출석(ADR-0030). 학생은 `GET …/attendance`로 차시마다 자기 기록과 요약을, 담당자는 상태별 수를 받는다.
// 담당자의 수강생 × 차시 출석부(`…/attendance/roster`)는 쪽 번호와 정렬(`studentNo`·`name`)로 읽고, 칸은 `If-Match`로
// 칸의 version(기록이 없으면 0)을 보내 고친다. 스마트출석은 WebSocket 없이 조회로 알린다: 학생은 `pollAfterSec`마다
// `active`·`me`를, 담당자는 회차 화면을 `pollAfterSec`(실시간 수를 다시 읽는 간격)마다, QR 회차는 그보다 이른 교체 시각
// `qrRefreshAt`에도 다시 읽는다. PIN은 회차 동안 바뀌지 않아 PIN 회차의 `qrRefreshAt`은 null이다. "출석했습니다."는
// `confirmed`이고 서버 기록이 있을 때만이다. 응답은 메모리에만 둔다.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";

export type Id = number | string;

/** 출석부 칸 하나(학생 본인의 기록 또는 담당자 출석부의 칸). 기록이 없으면 `version` 0. */
export interface AttendanceCell {
  sessionId: number;
  status: string;
  source?: string | null;
  reason?: string | null;
  decidedAt?: string | null;
  version: number;
}

export interface AttendanceSummary {
  sessions?: number;
  present?: number;
  late?: number;
  absent?: number;
  excused?: number;
  unmarked?: number;
  absenceEquivalent?: number;
}

export interface AttendanceSettings {
  lateAfterMinutes: number;
  roundMinutes: number;
  latesPerAbsence: number;
  version: number;
}

export interface AttendanceSession {
  sessionId: number;
  weekNo: number;
  sessionNo: number;
  classDate?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  method?: string;
  cancelled?: boolean;
  makeup?: boolean;
  makeupOfSessionId?: number | null;
  version?: number;
  /** 학생: 자기 기록. */
  record?: AttendanceCell | null;
  /** 담당자: 상태별 수. */
  counts?: Record<string, number>;
}

export interface AttendanceWeek {
  weekNo: number;
  sessions?: AttendanceSession[];
}

/** `GET …/attendance`. */
export interface AttendanceView {
  role?: string;
  serverTime?: string;
  settings?: AttendanceSettings | null;
  summary?: AttendanceSummary | null;
  weeks?: AttendanceWeek[];
}

export interface RosterStudent {
  userId: number;
  name?: string | null;
  studentNo?: string | null;
  summary?: AttendanceSummary | null;
  cells?: AttendanceCell[];
}

/** `GET …/attendance/roster`. */
export interface Roster {
  page: number;
  size: number;
  total: number;
  sort: string;
  sessions?: AttendanceSession[];
  students?: RosterStudent[];
}

export interface RoundCounts {
  targets?: number;
  confirmed?: number;
  present?: number;
  late?: number;
  failedAttempts?: number;
  lockedStudents?: number;
}

/** 담당자의 회차 화면(`GET …/smart-rounds/{id}`)과 `active`의 `rounds[]`. */
export interface RoundView {
  roundId: number;
  sessionId: number;
  method: string;
  state?: string;
  groupId?: number | null;
  pin?: string | null;
  qrToken?: string | null;
  qrRefreshAt?: string | null;
  openedAt?: string | null;
  lateAt?: string | null;
  endsAt?: string | null;
  closedAt?: string | null;
  serverTime?: string;
  counts?: RoundCounts | null;
  pollAfterSec?: number | null;
}

/** 학생 본인의 회차 상태(`…/me`, `check`, `active`의 `mine[]`). */
export interface MyRound {
  roundId: number;
  sessionId?: number;
  method?: string;
  state: string;
  pinLength?: number | null;
  endsAt?: string | null;
  serverTime?: string;
  failuresLeft?: number | null;
  failedAttempts?: number;
  lockedOut?: boolean;
  attendance?: { status: string } | null;
  pollAfterSec?: number | null;
}

/** `GET …/smart-rounds/active`. */
export interface ActiveRounds {
  serverTime?: string;
  rounds?: RoundView[];
  mine?: MyRound[];
  pollAfterSec?: number | null;
}

/** 차시 등록·수정 본문. */
export interface SessionBody {
  weekNo: number;
  sessionNo: number;
  classDate: string | null;
  startTime?: string | null;
  endTime?: string | null;
  method: string;
  makeupOfSessionId?: number | null;
  cancelled?: boolean;
}

export const ROSTER_PAGE = 30;
/** 출석부 정렬 허용 목록. 주소에 다른 값이 오면 학번순으로 본다. */
export const ROSTER_SORTS: readonly (readonly [string, string])[] = [["studentNo", "학번순"], ["name", "이름순"]];
export const rosterSort = (value: unknown): string => (typeof value === "string" && ROSTER_SORTS.some(([key]) => key === value) ? value : "studentNo");

export const STATUS: Record<string, string> = { present: "출석", late: "지각", absent: "결석", excused: "유고", unmarked: "미처리" };
/** 담당자가 정할 수 있는 상태. `unmarked`는 보내지 않는다. */
export const DECIDABLE: readonly string[] = ["present", "late", "absent", "excused"];
export const SOURCE: Record<string, string> = { smart: "스마트출석", online: "온라인 학습", manual: "담당 교수" };
export const METHOD: Record<string, string> = { smart: "스마트출석", online: "온라인", manual: "일반" };
/** 학생 화면 색 클래스(`st-*`)에 맞춘 이름. */
export const statusTone = (status: string | null | undefined): string => (status === "unmarked" || !status ? "empty" : status);

const course = (courseId: Id): string => `/api/courses/${encodeURIComponent(courseId)}`;
const sessionPath = (courseId: Id, sessionId: Id): string => `${course(courseId)}/attendance-sessions/${encodeURIComponent(sessionId)}`;
const roundPath = (courseId: Id, roundId: Id): string => `${course(courseId)}/smart-rounds/${encodeURIComponent(roundId)}`;

export const attendanceKey = (courseId: Id): string => String(courseId);
export const rosterKey = (courseId: Id, page: number, sort: string): string => `${courseId}:${page}:${sort}`;
export const roundKey = (courseId: Id, roundId: Id): string => `${courseId}:${roundId}`;

/** `GET …/attendance`: `{ role, serverTime, settings, summary, weeks[{ weekNo, sessions[] }] }`. */
export const attendanceViews = createResource<AttendanceView, [courseId: Id]>((courseId) => apiGet<AttendanceView>(`${course(courseId)}/attendance`));
/** `GET …/attendance/roster?page=&size=&sort=`: `{ page, size, total, sort, sessions[], students[] }`. */
export const rosters = createResource<Roster, [courseId: Id, page: number, sort: string]>((courseId, page, sort) => (
  apiGet<Roster>(`${course(courseId)}/attendance/roster?page=${encodeURIComponent(page)}&size=${ROSTER_PAGE}&sort=${encodeURIComponent(rosterSort(sort))}`)
));
/** `GET …/attendance/settings`: `{ lateAfterMinutes, roundMinutes, latesPerAbsence, version }`. */
export const attendanceSettings = createResource<AttendanceSettings, [courseId: Id]>((courseId) => apiGet<AttendanceSettings>(`${course(courseId)}/attendance/settings`));
/** `GET …/smart-rounds/active`: 담당자는 `rounds`, 학생은 회차마다 자기 상태 `mine`. `pollAfterSec`. */
export const activeRounds = createResource<ActiveRounds, [courseId: Id]>((courseId) => apiGet<ActiveRounds>(`${course(courseId)}/smart-rounds/active`));
/** 담당자의 회차 화면 `GET …/smart-rounds/{id}`: PIN(회차 동안 그대로) 또는 지금 QR token과 교체 시각 `qrRefreshAt`, 실시간 수, `pollAfterSec`. */
export const roundViews = createResource<RoundView, [courseId: Id, roundId: Id]>((courseId, roundId) => apiGet<RoundView>(roundPath(courseId, roundId)));
/** 학생 본인의 회차 상태 `GET …/smart-rounds/{id}/me`: `state`, `failuresLeft`(pending일 때만), `failedAttempts`, `lockedOut`. */
export const myRounds = createResource<MyRound, [courseId: Id, roundId: Id]>((courseId, roundId) => apiGet<MyRound>(`${roundPath(courseId, roundId)}/me`));

const rosterPages = new Map<string, [Id, number, string]>(); // rosterKey -> [courseId, page, sort]
subscribe(() => rosterPages.clear());

/** 출석부 한 쪽을 받는다. 받은 쪽을 기억해 두었다가 쓰기 뒤 다시 받는다. */
export function loadRoster(courseId: Id, page: number, sort: unknown): Promise<void> {
  const key = rosterKey(courseId, page, rosterSort(sort));
  rosterPages.set(key, [courseId, page, rosterSort(sort)]);
  return rosters.load(key, courseId, page, rosterSort(sort));
}

/**
 * 출석 기록이나 차시가 바뀌는 쓰기 뒤: 받아 둔 출석부(차시 목록)와 명단 쪽을 그 자리에서 다시 받는다. 받는 동안 지금 값을 그대로
 * 보여 주므로 화면이 비지 않는다.
 */
export function forgetAttendance(courseId: Id): Promise<void[]> {
  const reloads: Promise<void>[] = [];
  const key = attendanceKey(courseId);
  if (attendanceViews.peek(key)) reloads.push(attendanceViews.load(key, courseId));
  for (const [rkey, [id, page, sort]] of rosterPages) {
    if (id !== courseId) continue;
    if (rosters.peek(rkey)) reloads.push(rosters.load(rkey, id, page, sort));
    else rosterPages.delete(rkey);
  }
  return Promise.all(reloads);
}

// ---------- 담당자: 칸, 일괄, 설정, 차시 ----------

/** 한 학생의 상태와 사유. `version`은 출석부에서 읽은 칸의 version(기록이 없으면 0)이다. */
export async function setRecord(courseId: Id, sessionId: Id, studentId: Id, { status, reason, version }: { status: string; reason?: string | null; version: unknown }): Promise<AttendanceCell> {
  const cell = await apiSend<AttendanceCell>("PUT", `${sessionPath(courseId, sessionId)}/records/${encodeURIComponent(studentId)}`, { status, reason }, { ifMatch: Number(version) });
  rosters.patch((key) => key.startsWith(`${courseId}:`), (data) => {
    for (const student of data.students || []) {
      if (student.userId !== studentId) continue;
      student.cells = (student.cells || []).map((item) => (item.sessionId === sessionId ? cell : item));
    }
  });
  const key = attendanceKey(courseId);
  if (attendanceViews.peek(key)) attendanceViews.load(key, courseId);
  return cell;
}

/** 판정 전인 수강생 모두를 한 상태로 채운다. `{ updated }`. */
export async function fillRecords(courseId: Id, sessionId: Id, { status, reason }: { status: string; reason?: string | null }): Promise<{ updated: number }> {
  const result = await apiSend<{ updated: number }>("POST", `${sessionPath(courseId, sessionId)}/records/fill`, { status, reason });
  forgetAttendance(courseId);
  return result;
}

export async function updateAttendanceSettings(courseId: Id, { lateAfterMinutes, roundMinutes, latesPerAbsence, version }: { lateAfterMinutes: number; roundMinutes: number; latesPerAbsence: number; version: unknown }): Promise<AttendanceSettings> {
  const settings = await apiSend<AttendanceSettings>("PUT", `${course(courseId)}/attendance/settings`, { lateAfterMinutes, roundMinutes, latesPerAbsence }, { ifMatch: Number(version) });
  attendanceSettings.patch((key) => key === attendanceKey(courseId), (data) => { Object.assign(data, settings); });
  forgetAttendance(courseId);
  return settings;
}

const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingSession: { scope: string; content: string; key: string } | null = null;
subscribe(() => { pendingSession = null; });

/**
 * 차시 등록 `{ weekNo, sessionNo, classDate, startTime, endTime, method, makeupOfSessionId }`. 시도마다 새 `Idempotency-Key`이고,
 * 응답 없이 끝난 같은 내용의 재시도는 같은 key라 차시가 둘 생기지 않는다.
 */
export async function createSession(courseId: Id, body: SessionBody): Promise<AttendanceSession> {
  const scope = String(courseId);
  const content = JSON.stringify(body);
  const attempt = pendingSession && pendingSession.scope === scope && pendingSession.content === content
    ? pendingSession
    : { scope, content, key: newRequestKey() };
  pendingSession = attempt;
  try {
    const created = await apiSend<AttendanceSession>("POST", `${course(courseId)}/attendance-sessions`, body, { idempotencyKey: attempt.key });
    pendingSession = null;
    forgetAttendance(courseId);
    return created;
  } catch (error) {
    if (!uncertain(error)) pendingSession = null;
    throw error;
  }
}

/** 차시 수정(휴강 포함). 본문은 수정한 뒤의 전체 상태이고 `version`은 `If-Match`로 간다. */
export async function updateSession(courseId: Id, sessionId: Id, { weekNo, sessionNo, classDate, startTime, endTime, method, cancelled, version }: SessionBody & { version: unknown }): Promise<AttendanceSession> {
  const updated = await apiSend<AttendanceSession>("PUT", sessionPath(courseId, sessionId), {
    weekNo, sessionNo, classDate, startTime, endTime, method, cancelled: Boolean(cancelled),
  }, { ifMatch: Number(version) });
  forgetAttendance(courseId);
  return updated;
}

/** 차시 삭제. 기록·회차·보강·연결 활동이 있으면 409 `session_in_use`다. */
export async function deleteSession(courseId: Id, sessionId: Id, version: unknown): Promise<void> {
  await apiSend("DELETE", sessionPath(courseId, sessionId), undefined, { ifMatch: Number(version) });
  forgetAttendance(courseId);
}

// ---------- 스마트출석 ----------

/** 회차 열기 `{ method: "pin" | "qr", minutes?, lateAfterMinutes?, groupId? }`. 비운 값은 과목 설정을 쓴다. */
export async function openRound(
  courseId: Id,
  sessionId: Id,
  { method, minutes, lateAfterMinutes, groupId }: { method: string; minutes?: number | null; lateAfterMinutes?: number | null; groupId?: number | null },
): Promise<RoundView> {
  const body: { method: string; minutes?: number; lateAfterMinutes?: number; groupId?: number } = { method };
  if (minutes != null) body.minutes = minutes;
  if (lateAfterMinutes != null) body.lateAfterMinutes = lateAfterMinutes;
  if (groupId != null) body.groupId = groupId;
  const round = await apiSend<RoundView>("POST", `${sessionPath(courseId, sessionId)}/smart-rounds`, body);
  activeRounds.load(attendanceKey(courseId), courseId);
  return round;
}

/** 마감(멱등)과 취소(그 회차가 만든 기록을 지운다). 응답은 바뀐 회차 화면이다. */
export async function finishRound(courseId: Id, roundId: Id, how: string): Promise<RoundView> {
  const round = await apiSend<RoundView>("POST", `${roundPath(courseId, roundId)}/${how === "cancel" ? "cancel" : "close"}`);
  if (activeRounds.peek(attendanceKey(courseId))) activeRounds.load(attendanceKey(courseId), courseId);
  roundViews.patch((key) => key === roundKey(courseId, roundId), (data) => { Object.assign(data, round); });
  forgetAttendance(courseId);
  return round;
}

/**
 * 학생 제출 `{ pin }` 또는 `{ qrToken }`. 확정이면 200과 `confirmed` 상태다. 틀림 422(`bad_pin`·`bad_qr`), 잠김 429
 * `attempts_locked`, 만료·마감·취소·대상 아님 409. 다시 보내도 기록은 하나라 `Idempotency-Key`가 없다.
 */
export async function checkRound(courseId: Id, roundId: Id, proof: { pin?: string | number | null; qrToken?: string | null }): Promise<MyRound> {
  const body = proof.pin != null ? { pin: String(proof.pin) } : { qrToken: String(proof.qrToken) };
  const state = await apiSend<MyRound>("POST", `${roundPath(courseId, roundId)}/check`, body);
  if (myRounds.peek(roundKey(courseId, roundId))) myRounds.patch((key) => key === roundKey(courseId, roundId), (data) => { Object.assign(data, state); });
  else myRounds.load(roundKey(courseId, roundId), courseId, roundId);
  return state;
}

/** 다음 조회 간격(밀리초): 응답의 `pollAfterSec`. 서버가 주지 않으면 3초, 1초보다 짧게 묻지 않는다. */
export function pollDelay(data: { pollAfterSec?: number | null } | null | undefined): number {
  const seconds = Number(data && data.pollAfterSec);
  return Math.max(1, Number.isFinite(seconds) && seconds > 0 ? seconds : 3) * 1000;
}

/**
 * 학생 회차 상태의 설명 한 줄(자기 시도만 센다). 진행 중 잠김(`locked`), 잠긴 채 끝남(`lockedOut`), 제출하지 않고 끝남
 * (`failedAttempts` 0), 틀린 값만 내고 끝남을 가른다. 설명할 것이 없으면 빈 문자열.
 */
export function roundOutcome(view: Pick<MyRound, "state" | "failedAttempts" | "lockedOut"> | null | undefined): string {
  if (!view) return "";
  const failed = Number(view.failedAttempts) || 0;
  if (view.state === "locked") return `틀린 값을 ${failed}번 내 제출 기회를 모두 썼습니다. 이번 출석에는 더 제출할 수 없으니 담당 교수에게 확인을 요청하세요.`;
  if (view.state !== "expired" && view.state !== "closed") return "";
  const ended = view.state === "expired" ? "출석 시간이 끝났습니다" : "담당 교수가 출석을 마감했습니다";
  if (view.lockedOut) return `틀린 횟수 한도에 닿아 제출이 잠긴 채로 ${ended}. 출석으로 기록되지 않았으니 담당 교수에게 확인을 요청하세요.`;
  if (failed === 0) return `제출하지 않은 채로 ${ended}.`;
  return `틀린 값을 ${failed}번 냈고 맞는 값을 내지 못한 채로 ${ended}.`;
}

/** `later - earlier`(밀리초). 둘 다 서버 LocalDateTime이라 브라우저 시계와 관계없다. 읽지 못하면 null. */
export function serverGap(later: string | null | undefined, earlier: string | null | undefined): number | null {
  const a = Date.parse(`${String(later || "")}Z`);
  const b = Date.parse(`${String(earlier || "")}Z`);
  return Number.isFinite(a) && Number.isFinite(b) ? a - b : null;
}

export const ROUND_STATE: Record<string, string> = {
  confirmed: "출석이 확정되었습니다",
  pending: "제출 전",
  locked: "제출이 잠겼습니다",
  expired: "출석 시간이 지났습니다",
  closed: "마감한 출석입니다",
  cancelled: "취소한 출석입니다",
  not_target: "출석 대상이 아닙니다",
  open: "진행 중",
};

/** "2026.09.17 (목)" for a server LocalDate. */
export function koDay(value: unknown): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value || ""));
  if (!match) return "";
  const dow = "일월화수목금토"[new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))).getUTCDay()];
  return `${match[1]}.${match[2]}.${match[3]} (${dow})`;
}

/** "14:00 ~ 17:00" (시각이 없으면 빈 문자열). */
export function timeRange(session: { startTime?: string | null; endTime?: string | null }): string {
  const t = (value: string | null | undefined): string => (value ? String(value).slice(0, 5) : "");
  if (!session.startTime && !session.endTime) return "";
  return `${t(session.startTime)} ~ ${t(session.endTime)}`.trim();
}

/** 차시 한 줄 이름: "3주 1차시", 보강이면 "3주 1차시 보강". */
export function sessionName(session: { weekNo: number; sessionNo: number; makeup?: boolean }): string {
  return `${session.weekNo}주 ${session.sessionNo}차시${session.makeup ? " 보강" : ""}`;
}

/** 출석·스마트출석 요청 실패의 한국어 문구. */
export function attendanceMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    const message = error !== null && typeof error === "object" && "message" in error ? error.message : null;
    return message ? String(message) : "출석 요청을 처리하지 못했습니다.";
  }
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "출석 정보를 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 422:
      if (error.code === "bad_pin") return "PIN이 맞지 않습니다. 화면의 숫자를 다시 확인하세요.";
      if (error.code === "bad_qr") return "QR 코드가 맞지 않거나 이미 바뀌었습니다. 화면의 QR 코드를 다시 읽어 주세요.";
      return error.detail || "제출한 값이 맞지 않습니다.";
    case 428: return "출석부 버전을 확인하지 못했습니다. 출석부를 새로 불러오세요.";
    case 409:
      switch (error.code) {
        case "version_conflict": return "그 사이 다른 곳에서 먼저 바뀌었습니다. 최신 내용을 불러왔습니다. 고른 값은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
        case "round_expired": return "출석 시간이 지났습니다.";
        case "round_closed": return "마감한 출석입니다.";
        case "round_cancelled": return "취소한 출석입니다.";
        case "not_target": return "이 출석의 대상이 아닙니다.";
        case "round_open": return "이 차시에 진행 중인 스마트출석이 있습니다. 먼저 마감하거나 취소하세요.";
        case "session_cancelled": return "휴강한 차시에는 출석을 정하거나 받지 않습니다.";
        case "session_not_cancelled": return "휴강한 차시만 보강할 수 있습니다.";
        case "session_in_use": return "출석 기록, 스마트출석, 보강이나 연결된 활동이 있는 차시는 지울 수 없습니다.";
        case "session_exists": return "같은 주차에 같은 차시가 이미 있습니다.";
        case "idempotency_conflict": return "같은 요청을 다른 내용으로 보냈습니다. 내용을 확인한 뒤 다시 보내세요.";
        case "idempotency_in_progress": return "같은 요청을 처리하고 있습니다. 잠시 후 목록을 확인하세요.";
        default: return error.detail || "요청이 다른 변경과 겹쳤습니다. 화면을 새로 불러오세요.";
      }
    case 429:
      if (error.code === "attempts_locked") return "틀린 횟수가 한도를 넘어 이번 출석에는 더 제출할 수 없습니다. 담당 교수에게 확인을 요청하세요.";
      return retryLater(error);
    case 0: return error.message;
    case 502:
    case 503:
    case 504: return "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "출석 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
