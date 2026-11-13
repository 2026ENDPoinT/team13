// 메인 캘린더(`GET /api/me/calendar`, `GET /api/me/calendar/days/{date}`)와 수업시간표
// (`GET /api/me/timetable`). 기준 날짜를 비우면 서버가 DB 시계의 오늘로 정하고, 응답의 `today`와
// `serverTime`만 오늘로 쓴다. 브라우저 시계로 날짜를 정하지 않는다. 날짜 계산은 ISO 문자열의 달력 산술뿐이다.
import { apiGet, ApiError } from "./api.ts";
import { createResource, retryLater } from "./remote-cache.ts";

/** 학사일정 하나(`startDate`~`endDate`, 하루짜리면 `endDate` 없음). */
export interface CalendarEvent {
  eventId?: number | string;
  type: string;
  title: string;
  startDate: string;
  endDate?: string | null;
}

/** 캘린더에 실린 활동(마감일 기준). `completed`는 학생 본인의 것에만 있다. */
export interface CalendarActivity {
  activityId: number | string;
  courseId: number | string;
  courseName?: string | null;
  courseCode?: string | null;
  section?: string | null;
  type: string;
  title: string;
  closeAt?: string | null;
  completed?: boolean;
  role?: string | null;
  published?: boolean;
}

/** `GET /api/me/calendar`와 `GET /api/me/calendar/days/{date}`의 응답. */
export interface CalendarRange {
  serverTime?: string;
  today: string;
  from: string;
  to: string;
  events?: CalendarEvent[];
  activities?: CalendarActivity[];
  truncated?: boolean;
}

export interface TimetableTerm {
  termId: number | string;
  year?: number;
  code?: string;
  name: string;
  current?: boolean;
  startDate: string;
  endDate: string;
}

export interface TimetableSlot {
  date: string;
  dayOfWeek?: number;
  startTime: string;
  endTime: string;
  periodStart?: number | null;
  periodEnd?: number | null;
  courseId?: number | string;
  courseName?: string | null;
  courseCode?: string | null;
  section?: string | null;
  room?: string | null;
  instructors?: string[] | null;
}

/** `GET /api/me/timetable`: 그 날짜가 든 주(`weekStart`~`weekEnd`)의 시간표. */
export interface Timetable {
  serverTime?: string;
  today: string;
  weekStart: string;
  weekEnd: string;
  termId?: number | string | null;
  terms?: TimetableTerm[];
  slots?: TimetableSlot[];
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-\d{2}$/;

/** 유효한 "yyyy-MM-dd"면 그대로, 아니면 "". */
export function isoDate(value: unknown): string {
  const text = String(value || "");
  if (!DATE.test(text)) return "";
  const [y, m, d] = text.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d ? text : "";
}

export function isoMonth(value: unknown): string {
  const text = String(value || "");
  return MONTH.test(text) && Number(text.slice(5)) >= 1 && Number(text.slice(5)) <= 12 ? text : "";
}

export const rangeKey = (view: string, date?: string | null): string => `${view}|${date || ""}`;

/** `view`는 month|week, `date`가 ""이면 오늘. 응답 `{serverTime, today, from, to, events, activities, truncated}`. */
export const calendarRanges = createResource<CalendarRange, [view: string, date?: string | null]>((view, date) => {
  const query = new URLSearchParams({ view });
  if (date) query.set("date", date);
  return apiGet<CalendarRange>(`/api/me/calendar?${query}`);
});

export const calendarDays = createResource<CalendarRange, [date: string]>((date) => apiGet<CalendarRange>(`/api/me/calendar/days/${encodeURIComponent(date)}`));

/**
 * 그 날짜가 든 주(일~토)의 시간표. 키는 `timetableKey(date, termId)`(학기를 고르지 않으면 날짜 그대로, "" = 이번 주).
 * `termId`는 응답 `terms[]`에 있는 학기만 보낸다(그 밖은 400). 학기를 고르고 날짜를 비우면 서버가 오늘을 그 학기 안으로 옮긴다.
 */
export const timetables = createResource<Timetable, [date?: string | null, termId?: number | string | null]>((date, termId) => {
  const query = new URLSearchParams();
  if (date) query.set("date", date);
  if (termId) query.set("termId", String(termId));
  const text = query.toString();
  return apiGet<Timetable>(`/api/me/timetable${text ? `?${text}` : ""}`);
});
export const timetableKey = (date?: string | null, termId?: number | string | null): string => (termId ? `${date || ""}|t${termId}` : date || "");

/** 학생 본인이 끝낸 활동인지(캘린더 `completed === true`). 담당 과목의 활동에는 이 값이 없다. */
export const isDone = (activity: { completed?: boolean } | null | undefined): boolean => activity != null && activity.completed === true;
export const DONE_LABEL = "완료";

function utc(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function isoOf(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(iso: string, n: number): string {
  const date = utc(iso);
  date.setUTCDate(date.getUTCDate() + n);
  return isoOf(date);
}

/** `from`부터 `to`까지(포함) 날짜 목록. */
export function daysBetween(from: string, to: string): string[] {
  const days: string[] = [];
  for (let day = from; day <= to && days.length < 62; day = addDays(day, 1)) days.push(day);
  return days;
}

export function dayOfWeek(iso: string): number {
  return utc(iso).getUTCDay();
}

export function shiftMonth(ym: string, delta: number): string {
  const [y, m] = ym.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1 + delta, 1));
  return isoOf(date).slice(0, 7);
}

const DOW = "일월화수목금토";

/** "2026.11.12 (목)". */
export function koDay(iso: string): string {
  return `${iso.replaceAll("-", ".")} (${DOW[dayOfWeek(iso)]})`;
}

/** "11월 12일 (목)". */
export function koShortDay(iso: string): string {
  return `${Number(iso.slice(5, 7))}월 ${Number(iso.slice(8, 10))}일 (${DOW[dayOfWeek(iso)]})`;
}

export function monthTitle(ym: string): string {
  return `${ym.slice(0, 4)}년 ${Number(ym.slice(5, 7))}월`;
}

/** "10:00" (LocalTime 또는 LocalDateTime). */
export function clock(value: unknown): string {
  const match = /(?:^|T)(\d{2}):(\d{2})/.exec(String(value || ""));
  return match ? `${match[1]}:${match[2]}` : "";
}

export const EVENT_LABEL: Record<string, string> = { ACADEMIC: "학사일정", REGISTRATION: "수강신청", EXAM: "시험", HOLIDAY: "휴일" };

/** 서버 날짜 범위 하나에 걸친 학사일정인지. */
export function eventOn(event: Pick<CalendarEvent, "startDate" | "endDate">, day: string): boolean {
  return event.startDate <= day && day <= (event.endDate || event.startDate);
}

/** 활동 마감일 ("yyyy-MM-dd"). */
export function closeDay(activity: { closeAt?: string | null }): string {
  return String(activity.closeAt || "").slice(0, 10);
}

/** 실패한 캘린더·시간표 요청의 한국어 문구. */
export function calendarMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "ASTRA 서버에서 일정을 받지 못했습니다. 잠시 후 다시 시도하세요.";
  if (error.status === 400) return error.detail || "날짜를 확인하세요.";
  if (error.status === 401) return "ASTRA 서버 로그인이 필요합니다.";
  if (error.status === 429) return retryLater(error);
  if (error.status === 0) return error.message;
  return "ASTRA 서버에서 일정을 받지 못했습니다. 잠시 후 다시 시도하세요.";
}
