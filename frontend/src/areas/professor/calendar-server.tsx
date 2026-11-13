// 백엔드 연결 때의 교수 일정(`pf_calendar`): 내 캘린더(월·주, `GET /api/me/calendar`), 고른 날의 일정
// (`GET /api/me/calendar/days/{date}`), 이번 주 수업시간표(`GET /api/me/timetable`). 주소 변수는
// `view`(month 기본 | week), `date`(기준 날짜, 없으면 서버의 오늘), `day`(하루 목록), `tt`(시간표 주의 날짜)다.
// 날짜, 주 경계, 오늘은 서버 응답(`today`, `from`/`to`, `weekStart`/`weekEnd`)으로만 정한다.
// 서버 글자는 텍스트로만 넣는다.
import { useEffect, useState, type ReactNode } from "react";
import { Button, Tabs, LoadingState, ErrorState, EmptyState, Badge } from "../../ui/kit.tsx";
import { Link, GoButton } from "../../router.tsx";
import { courseById } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { backendState, backendStatus } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { routeFor } from "../../shared/routes.ts";
import { useResource, type Resource } from "../../shared/remote-cache.ts";
import { todoKind, todoLabel } from "../../shared/home-remote.ts";
import {
  calendarRanges, calendarDays, timetables, rangeKey, isoDate, addDays, daysBetween, dayOfWeek, shiftMonth,
  monthTitle, koDay, clock, eventOn, closeDay, EVENT_LABEL, calendarMessage,
  type CalendarActivity, type CalendarEvent, type CalendarRange, type TimetableSlot,
} from "../../shared/calendar-remote.ts";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];
const pf = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

interface CalState {
  view: string;
  date: string;
  day: string;
  tt: string;
}

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

/** 캐시로 그리고, 마운트 뒤 구독·조회한다. `children(data)`는 데이터가 있을 때만 부른다. */
function Bound<T, A extends unknown[]>({ resource, scope, cacheKey, args, what, children }: {
  resource: Resource<T, A>;
  scope: string;
  cacheKey: string;
  args: A;
  what: string;
  children: (data: T) => ReactNode;
}) {
  const load = () => { void resource.load(cacheKey, ...args); };
  const entry = useResource(resource, cacheKey, load);
  useEffect(() => { void resource.visit(scope, cacheKey, ...args); });
  const data = entry && entry.data;
  return (
    <div className="srv-list">
      {data ? children(data) : entry && entry.error
        ? <ErrorState title={`${what} 불러오지 못했습니다`} body={calendarMessage(entry.error)} actions={<Retry reload={load} />} />
        : <LoadingState title={`${what} 불러오는 중입니다`} />}
    </div>
  );
}

/** 현재 주소 상태에 `patch`를 덮어 쓴 `pf_calendar` 주소. 빈 값과 기본값(month)은 뺀다. */
function calHref(state: CalState, patch: Partial<CalState>) {
  const next: CalState = { ...state, ...patch };
  const extra: Record<string, string> = {};
  for (const key of ["view", "date", "day", "tt"] as const) if (next[key]) extra[key] = next[key];
  if (extra.view !== "week") delete extra.view;
  return pf("pf_calendar", extra);
}

/** 담당 과목(`INSTRUCTOR`)이고 샘플에 화면이 있는 과목의 활동만 관리 화면으로 연결한다. */
function manageHref(item: CalendarActivity): string | null {
  const { status, courses } = backendState();
  if (status !== "ready" || !Array.isArray(courses)) return null;
  const course = courses.find((entry) => entry.courseId === item.courseId);
  if (!course || course.role !== "INSTRUCTOR" || !course.key || courseById(course.key).id !== course.key) return null;
  const kj = course.key;
  const ref = `srv-${item.activityId}`;
  if (item.type === "report") return pf("pf_report_status", { kj });
  if (item.type === "test") return pf("pf_exam_status", { kj });
  if (item.type === "survey") return pf("pf_discuss", { kj });
  if (item.type === "lecture_weeks") return pf("pf_lecture", { kj, n: ref });
  if (item.type === "lecture_material") return pf("pf_pdf", { kj, material: String(item.activityId) });
  return pf("pf_room_week", { kj });
}

function closeText(value: unknown) {
  const text = String(value || "");
  return text ? `${text.slice(0, 10).replaceAll("-", ".")} ${clock(text)}` : "";
}

function EventRow({ event }: { event: CalendarEvent }) {
  const end = event.endDate || event.startDate;
  const range = event.startDate === end
    ? event.startDate.replaceAll("-", ".")
    : `${event.startDate.replaceAll("-", ".")} ~ ${end.replaceAll("-", ".")}`;
  return (
    <div className="day-item">
      <span className="rail-dot" aria-hidden="true" />
      <span className="day-copy">
        <b>{event.title}</b>
        <span className="tiny">{`${EVENT_LABEL[event.type] || "학사일정"} · ${range}`}</span>
      </span>
      <span />
    </div>
  );
}

function ActivityRow({ item }: { item: CalendarActivity }) {
  const href = manageHref(item);
  const kind = todoKind(item.type);
  const course = [item.courseName, [item.courseCode, item.section].filter(Boolean).join("-")].filter(Boolean).join(" ");
  const body = (
    <>
      <span className={`rail-dot ${kind}`} aria-hidden="true" />
      <span className="day-copy">
        <b>{item.published === false ? `[비공개] ${item.title}` : item.title}</b>
        <span className="tiny">{`${course} · ${todoLabel(item.type)} · 마감 ${closeText(item.closeAt)}`}</span>
      </span>
      <Badge tone={item.role === "INSTRUCTOR" ? "success" : "neutral"}>{item.role === "INSTRUCTOR" ? "담당" : "수강"}</Badge>
    </>
  );
  return href
    ? <Link className="day-item todo-link" href={href} data-todo={kind}>{body}</Link>
    : <div className="day-item" data-todo={kind}>{body}</div>;
}

function TruncatedNote({ data }: { data: CalendarRange }) {
  return data.truncated
    ? <p className="tiny srv-msg is-alert" role="status">활동 마감이 많아 앞의 500개만 표시했습니다. 주 보기나 하루 목록에서 나머지를 확인하세요.</p>
    : null;
}

// ---------- 월·주 달력 ----------

function RangeSection({ state }: { state: CalState }) {
  const monthView = state.view !== "week";
  const view = monthView ? "month" : "week";
  return (
    <section className="srv-catalog" aria-label="내 일정">
      <div className="cal-tools">
        <Tabs items={[
          { label: "월", href: calHref(state, { view: "", day: "" }), on: monthView },
          { label: "주", href: calHref(state, { view: "week", day: "" }), on: !monthView },
        ]} />
        <Link className="btn btn-ghost" href={calHref(state, { date: "", day: "" })}>오늘</Link>
      </div>
      <Bound resource={calendarRanges} scope="pf:calendar" cacheKey={rangeKey(view, state.date)} args={[view, state.date]} what="일정을">
        {(data) => <RangeBody state={state} monthView={monthView} data={data} />}
      </Bound>
    </section>
  );
}

function RangeBody({ state, monthView, data }: { state: CalState; monthView: boolean; data: CalendarRange }) {
  const days = daysBetween(data.from, data.to);
  const month = monthView ? (state.date || data.today).slice(0, 7) : days[Math.min(3, days.length - 1)].slice(0, 7);
  const prev = monthView ? `${shiftMonth(month, -1)}-01` : addDays(data.from, -7);
  const next = monthView ? `${shiftMonth(month, 1)}-01` : addDays(data.from, 7);
  const events = Array.isArray(data.events) ? data.events : [];
  const activities = Array.isArray(data.activities) ? data.activities : [];
  const sorted = [...activities].sort((a, b) => String(a.closeAt).localeCompare(String(b.closeAt)) || Number(a.activityId) - Number(b.activityId));
  return (
    <>
      <div className="cal-head">
        <Link className="btn btn-ghost" href={calHref(state, { date: prev, day: "" })} aria-label={monthView ? "이전 달" : "이전 주"}>‹</Link>
        <div className="cal-title">
          <h2>{monthTitle(month)}</h2>
          {monthView ? null : <p className="cal-range">{`${koDay(data.from)} – ${koDay(data.to)}`}</p>}
        </div>
        <Link className="btn btn-ghost" href={calHref(state, { date: next, day: "" })} aria-label={monthView ? "다음 달" : "다음 주"}>›</Link>
      </div>
      <div className={monthView ? "cal" : "cal week-cal"} role="group" aria-label={monthView ? "월간 달력" : "주간 달력"}>
        {DOW.map((name, i) => <div key={name} className={`cal-dow${i === 0 ? " sun" : i === 6 ? " sat" : ""}`} aria-hidden="true">{name}</div>)}
        {days.map((iso) => {
          const dow = dayOfWeek(iso);
          const dayEvents = events.filter((event) => eventOn(event, iso));
          const dayItems = activities.filter((item) => closeDay(item) === iso);
          const count = dayEvents.length + dayItems.length;
          const cls = ["cal-day", monthView && iso.slice(0, 7) !== month ? "out" : "", iso === data.today ? "today" : ""].filter(Boolean).join(" ");
          return (
            <GoButton
              key={iso}
              className={cls}
              href={calHref(state, { day: iso })}
              aria-current={iso === data.today ? "date" : undefined}
              aria-pressed={iso === state.day ? "true" : "false"}
              aria-label={`${koDay(iso)} 일정 ${count}건`}
            >
              <div className={`day-num${dow === 0 ? " sun" : dow === 6 ? " sat" : ""}`}>{String(Number(iso.slice(8, 10)))}</div>
              {monthView
                ? count ? <span className="tiny">{`${count}건`}</span> : null
                : (
                  <>
                    {dayEvents.map((event, index) => <span key={`e${index}`} className="pill">{event.title}</span>)}
                    {dayItems.map((item, index) => <span key={`a${index}`} className={`pill ${todoKind(item.type)}`}>{item.title}</span>)}
                  </>
                )}
            </GoButton>
          );
        })}
      </div>
      <p className="tiny">날짜를 누르면 그날의 일정을 봅니다. 담당 과목의 활동은 관리 화면으로 열립니다.</p>
      <TruncatedNote data={data} />
      <h2 className="section-label">학사일정</h2>
      {events.length
        ? <div>{events.map((event, index) => <EventRow key={index} event={event} />)}</div>
        : <p className="tiny">이 기간에 학사일정이 없습니다.</p>}
      <h2 className="section-label">활동 마감</h2>
      {sorted.length
        ? <div>{sorted.map((item) => <ActivityRow key={`${item.courseId}:${item.activityId}`} item={item} />)}</div>
        : <EmptyState title="이 기간에 마감되는 활동이 없습니다" body="다른 달이나 주를 확인하세요." />}
    </>
  );
}

// ---------- 하루 목록 ----------

function DaySection({ state }: { state: CalState }) {
  return (
    <section className="srv-catalog" aria-label={`${koDay(state.day)} 일정`}>
      <div className="cal-tools">
        <h2 className="section-label">{koDay(state.day)}</h2>
        <Link className="btn btn-ghost" href={calHref(state, { day: "" })} aria-label="하루 일정 닫기">닫기</Link>
      </div>
      <Bound resource={calendarDays} scope="pf:calendar-day" cacheKey={state.day} args={[state.day]} what="이날 일정을">
        {(data) => {
          const events = (Array.isArray(data.events) ? data.events : []).filter((event) => eventOn(event, state.day));
          const items = (Array.isArray(data.activities) ? data.activities : []).filter((item) => closeDay(item) === state.day);
          return events.length || items.length
            ? (
              <>
                {events.map((event, index) => <EventRow key={`e${index}`} event={event} />)}
                {items.map((item) => <ActivityRow key={`${item.courseId}:${item.activityId}`} item={item} />)}
                <TruncatedNote data={data} />
              </>
            )
            : <EmptyState title="이 날짜에 등록된 일정이 없습니다" body="다른 날짜를 눌러 보세요." />;
        }}
      </Bound>
    </section>
  );
}

// ---------- 수업시간표 ----------

function timeText(slot: TimetableSlot) {
  if (slot.startTime) return `${clock(slot.startTime)}–${clock(slot.endTime)}`;
  if (slot.periodStart) return `${slot.periodStart}–${slot.periodEnd || slot.periodStart}교시`;
  return "시간 미정";
}

function TimetableSection({ state }: { state: CalState }) {
  return (
    <section className="srv-catalog" aria-label="주간 수업시간표">
      <h2 className="section-label">수업시간표</h2>
      <Bound resource={timetables} scope="pf:timetable" cacheKey={state.tt} args={[state.tt]} what="수업시간표를">
        {(data) => {
          const slots = Array.isArray(data.slots) ? data.slots : [];
          const thisWeek = data.weekStart <= data.today && data.today <= data.weekEnd;
          // 주말은 수업이 있을 때만 보인다.
          const days = daysBetween(data.weekStart, data.weekEnd).flatMap((iso) => {
            const mine = slots.filter((item) => item.date === iso);
            const dow = dayOfWeek(iso);
            return !mine.length && (dow === 0 || dow === 6) ? [] : [{ iso, mine }];
          });
          return (
            <>
              <div className="tt-nav">
                <Link className="btn btn-ghost" href={calHref(state, { tt: addDays(data.weekStart, -7) })} aria-label="이전 주 시간표">‹</Link>
                <strong>{`${koDay(data.weekStart)} – ${koDay(data.weekEnd)}`}</strong>
                <Link className="btn btn-ghost" href={calHref(state, { tt: addDays(data.weekStart, 7) })} aria-label="다음 주 시간표">›</Link>
                {thisWeek ? null : <Link className="btn btn-ghost" href={calHref(state, { tt: "" })}>이번 주</Link>}
              </div>
              <p className="tiny">담당하거나 수강하는 이번 학기 과목의 수업 시간입니다. 휴강과 보강은 반영하지 않습니다.</p>
              {slots.length
                ? (
                  <div className="srv-list">
                    {days.map(({ iso, mine }) => (
                      <section key={iso} className={`att-card${iso === data.today ? " is-current" : ""}`}>
                        <h3>{koDay(iso)}</h3>
                        {!mine.length ? <p className="tiny">수업 없음</p> : null}
                        {mine.map((item, index) => {
                          const people = Array.isArray(item.instructors) && item.instructors.length ? item.instructors.join(", ") : "";
                          return (
                            <div className="tt-line" key={index}>
                              <span className="rail-dot" aria-hidden="true" />
                              <span>
                                <b>{item.courseName}</b>
                                <span className="tiny">{[timeText(item), item.room || "강의실 미정", people].filter(Boolean).join(" · ")}</span>
                              </span>
                            </div>
                          );
                        })}
                      </section>
                    ))}
                  </div>
                )
                : <EmptyState title="이 주에는 수업이 없습니다" body="다른 주를 확인하세요." />}
            </>
          );
        }}
      </Bound>
    </section>
  );
}

/** `pf_calendar` 백엔드 화면. `anon`이면 로그인 안내만 둔다. */
export function serverProfessorCalendar(ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "anon") {
    return <div className="stack">{backendPanel({ text: "ASTRA 서버에 로그인하면 담당 과목의 일정과 수업시간표를 봅니다." })}</div>;
  }
  const param = (name: string) => (ctx && ctx.params && ctx.params.get(name)) || "";
  const state: CalState = {
    view: param("view") === "week" ? "week" : "",
    date: isoDate(param("date")),
    day: isoDate(param("day")),
    tt: isoDate(param("tt")),
  };
  return (
    <div className="stack">
      {backendPanel()}
      {state.day ? <DaySection state={state} /> : null}
      <RangeSection state={state} />
      <TimetableSection state={state} />
    </div>
  );
}
