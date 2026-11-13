// 백엔드 연결(`ready`) 때의 주간 달력(07), 수업시간표(05), 하루 일정 창(17, `?pop=day&day=`).
// 날짜, 주 경계, 오늘은 서버 응답(`today`, `from`/`weekStart`)으로만 정한다. 주 이동은 주소의 날짜
// (`week=`, `date=`)를 바꾸는 링크다. 시간표 학기는 응답 `terms[]`에서 고르고 주소 `term=`으로 보낸다.
// 학생이 끝낸 활동(`completed`)은 글자 "완료"로도 표시한다. 서버 글자는 텍스트로만 넣는다.
import { useEffect, useRef, type ReactNode } from "react";
import { Button, Dialog, EmptyState, ErrorState, Field, LoadingState, Tabs } from "../../ui/kit.tsx";
import { GoButton, Link, navigate } from "../../router.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { subscribe } from "../../shared/backend.ts";
import { useResource, type Resource } from "../../shared/remote-cache.ts";
import { homeEntry, todoKind, todoLabel, todoHref, ddayAt, type HomeData } from "../../shared/home-remote.ts";
import {
  calendarRanges, calendarDays, timetables, timetableKey, rangeKey, isoDate, addDays, daysBetween, dayOfWeek,
  koDay, clock, eventOn, closeDay, EVENT_LABEL, calendarMessage, isDone, DONE_LABEL,
  type CalendarActivity, type CalendarRange, type Timetable, type TimetableSlot, type TimetableTerm,
} from "../../shared/calendar-remote.ts";
import type { ScreenCtx } from "../../types.ts";
import { RetryButton } from "./activity-server.tsx";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];

/** Where a calendar activity opens (the to-do link of the same activity), or null. */
export function calendarTodoHref(item: CalendarActivity, home: HomeData | null | undefined): string | null {
  return todoHref({
    ...item,
    activityId: Number(item.activityId),
    courseId: Number(item.courseId),
    courseName: item.courseName ?? undefined,
    courseCode: item.courseCode ?? undefined,
  }, home);
}

interface BoundProps<T> {
  resource: Resource<T>;
  scope: string;
  rkey: string;
  args: unknown[];
  what: string;
  /** Next to 다시 불러오기 on failure. */
  recover?: ReactNode;
  children: (data: T) => ReactNode;
}

/** 캐시로 그리고, 붙은 뒤 조회한다. `children(data)`는 데이터가 있을 때만 부른다. */
function Bound<T>({ resource, scope, rkey, args, what, recover, children }: BoundProps<T>) {
  const load = () => void resource.load(rkey, ...args);
  const entry = useResource(resource, rkey, load);
  useEffect(() => {
    void resource.visit(scope, rkey, ...args);
  });
  const data = entry && entry.data;
  if (data) return <>{children(data)}</>;
  if (entry && entry.error) {
    return <ErrorState title={`${what} 불러오지 못했습니다`} body={calendarMessage(entry.error)} actions={<><RetryButton reload={load} />{recover}</>} />;
  }
  return <LoadingState title={`${what} 불러오는 중입니다`} />;
}

// ---------- 주간 달력 ----------

function weekHref(date: string): string {
  return hrefFor(find("07_home_calendar_week"), date ? { week: date } : {});
}

function WeekGrid({ data, date }: { data: CalendarRange; date: string }) {
  const days = daysBetween(data.from, data.to);
  return (
    <>
      <div className="cal-head">
        <h1>{`${days[3].slice(0, 4)}년 ${Number(days[3].slice(5, 7))}월`}</h1>
        <Tabs items={[
          { label: "월", href: hrefFor(find("00_main_home"), { cal: "month", month: days[3].slice(0, 7) }) },
          { label: "주", on: true },
        ]} />
        <div className="cal-nav">
          <Link href={weekHref(addDays(data.from, -7))} className="btn btn-ghost" aria-label="이전 주">‹</Link>
          <Link href={weekHref("")} className="btn btn-ghost">오늘</Link>
          <Link href={weekHref(addDays(data.from, 7))} className="btn btn-ghost" aria-label="다음 주">›</Link>
        </div>
      </div>
      <p className="subhead">{`${koDay(data.from)} – ${koDay(data.to)}`}</p>
      <div className="cal week-cal">
        {days.map((iso) => {
          const day = dayOfWeek(iso);
          return <div key={`dow-${iso}`} className={`cal-dow${day === 0 ? " sun" : day === 6 ? " sat" : ""}`}>{DOW[day]}</div>;
        })}
        {days.map((iso) => {
          const day = dayOfWeek(iso);
          const href = hrefFor(find("07_home_calendar_week"), { ...(date ? { week: date } : {}), pop: "day", day: iso });
          const events = (data.events || []).filter((event) => eventOn(event, iso));
          const items = (data.activities || []).filter((item) => closeDay(item) === iso);
          const done = items.filter(isDone).length;
          return (
            <GoButton
              key={iso}
              href={href}
              className={`cal-day${iso === data.today ? " today" : ""}`}
              aria-current={iso === data.today ? "date" : undefined}
              aria-label={`${koDay(iso)} 일정 ${events.length + items.length}건${done ? `, ${DONE_LABEL} ${done}건` : ""}`}
            >
              <div className={`day-num${day === 0 ? " sun" : day === 6 ? " sat" : ""}`}>{String(Number(iso.slice(8, 10)))}</div>
              {events.map((event, index) => <span key={`e${index}`} className="pill">{event.title}</span>)}
              {items.map((item) => (
                <span key={`a${item.activityId}`} className={`pill ${todoKind(item.type)}${isDone(item) ? " is-done" : ""}`}>
                  {isDone(item) ? <span className="srv-done">{DONE_LABEL}</span> : null}
                  {`${item.courseName} ${item.title}`}
                </span>
              ))}
            </GoButton>
          );
        })}
      </div>
      {data.truncated ? <p className="tiny srv-msg is-alert" role="status">활동 마감이 많아 앞의 500개만 표시했습니다.</p> : null}
    </>
  );
}

/** 07 주간 달력. `?week=yyyy-MM-dd`가 든 주, 없으면 서버의 이번 주. */
export function serverWeekCalendar(ctx: ScreenCtx): ReactNode {
  const date = isoDate(ctx.params && ctx.params.get("week"));
  return (
    <section className="srv-week">
      <Bound resource={calendarRanges} scope="learning:week" rkey={rangeKey("week", date)} args={["week", date]} what="일정을">
        {(data) => <WeekGrid data={data} date={date} />}
      </Bound>
    </section>
  );
}

// ---------- 수업시간표 ----------

function minutes(value: string | null | undefined): number | null {
  const match = /^(\d{2}):(\d{2})/.exec(String(value || ""));
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
}

function timeText(slot: TimetableSlot): string {
  if (slot.startTime) return `${clock(slot.startTime)}–${clock(slot.endTime)}`;
  if (slot.periodStart) return `${slot.periodStart}–${slot.periodEnd || slot.periodStart}교시`;
  return "시간 미정";
}

// 응답 `terms[]`로 받은 학기(termId → 학기). 주소의 `term`은 이 목록이 있으면 그 안의 학기만 보낸다. session이 바뀌면 지운다.
const knownTerms = new Map<string, TimetableTerm>();
subscribe(() => knownTerms.clear());

function ttHref(date: string, termId: string): string {
  const query: Record<string, string> = {};
  if (termId) query.term = String(termId);
  if (date) query.date = date;
  return hrefFor(find("05_pop_lectime"), query);
}

const outside = (term: TimetableTerm | null | undefined, iso: string) => Boolean(term && ((term.startDate && iso < term.startDate) || (term.endDate && iso > term.endDate)));

function termLabel(term: TimetableTerm): string {
  return `${term.name || `${term.year} ${term.code}`}${term.current ? " (현재)" : ""}`;
}

/** 고른 학기의 첫 화면(날짜 없음)으로 간다. 목록에 없는 값은 보내지 않는다. */
export function pickTimetableTerm(value: string): void {
  navigate(ttHref("", knownTerms.has(value) ? value : ""));
}

/** 학기 고르기 줄. */
function TermPicker({ terms, termId }: { terms: TimetableTerm[]; termId: string }) {
  const select = useRef<HTMLSelectElement>(null);
  return (
    <div className="srv-filters srv-tt-terms" role="group" aria-label="학기 고르기">
      <Field label="학기">
        <select ref={select} defaultValue={terms.some((term) => String(term.termId) === termId) ? termId : undefined}>
          {terms.map((term) => <option key={String(term.termId)} value={String(term.termId)}>{termLabel(term)}</option>)}
        </select>
      </Field>
      <div className="srv-filters__go"><Button kind="secondary" onClick={() => pickTimetableTerm(select.current ? select.current.value : "")}>보기</Button></div>
    </div>
  );
}

function TimetableBody({ data, asked, termId }: { data: Timetable; asked: string; termId: string }) {
  const terms = Array.isArray(data.terms) ? data.terms : [];
  for (const term of terms) knownTerms.set(String(term.termId), term);
  const shownTerm = data.termId != null ? terms.find((term) => String(term.termId) === String(data.termId)) || null : null;
  const chosen = termId ? String(data.termId ?? termId) : "";
  const slots = Array.isArray(data.slots) ? data.slots : [];
  const days = daysBetween(data.weekStart, data.weekEnd);
  // 평일은 항상, 주말은 수업이 있을 때만 칸을 둔다.
  const shown = days.filter((iso) => { const d = dayOfWeek(iso); return (d >= 1 && d <= 5) || slots.some((s) => s.date === iso); });
  const timed = slots.filter((s) => minutes(s.startTime) != null && minutes(s.endTime) != null);
  const first = Math.min(9, ...timed.map((s) => Math.floor(minutes(s.startTime)! / 60)));
  const last = Math.max(18, ...timed.map((s) => Math.ceil(minutes(s.endTime)! / 60) - 1));
  const hours = Array.from({ length: last - first + 1 }, (_, i) => first + i);
  const untimed = slots.filter((s) => !timed.includes(s));
  const grid = (
    <div
      className="tt"
      role="grid"
      aria-label="주간 수업시간표"
      style={{ gridTemplateColumns: `64px repeat(${shown.length}, minmax(0, 1fr))`, gridTemplateRows: `36px repeat(${last - first + 1}, minmax(48px, auto))` }}
    >
      <div className="tt-h" />
      {shown.map((iso) => <div key={`h-${iso}`} className={`tt-h${iso === data.today ? " is-today" : ""}`}>{`${DOW[dayOfWeek(iso)]} ${Number(iso.slice(8, 10))}`}</div>)}
      {hours.map((hour) => [
        <div key={`t-${hour}`} className="tt-time">{`${String(hour).padStart(2, "0")}:00`}</div>,
        ...shown.map((iso) => <div key={`s-${hour}-${iso}`} className="tt-slot" />),
      ])}
      {timed.map((item, index) => {
        const col = shown.indexOf(item.date);
        if (col < 0) return null;
        const start = minutes(item.startTime)!;
        const end = Math.max(minutes(item.endTime)!, start + 1);
        const row = Math.floor(start / 60) - first + 2;
        const span = Math.max(1, Math.ceil(end / 60) - Math.floor(start / 60));
        return (
          <div key={`b-${index}`} className={`tt-block g${(Number(item.courseId) % 8) + 1}`} style={{ gridColumn: `${col + 2}`, gridRow: `${row} / span ${span}` }}>
            <b>{item.courseName}</b>
            <span>{timeText(item)}</span>
            <span>{[item.instructors && item.instructors.join(", "), item.room || "-"].filter(Boolean).join(" · ")}</span>
          </div>
        );
      })}
    </div>
  );
  return (
    <>
      <div className="tt-nav">
        {outside(shownTerm, addDays(data.weekStart, -1)) ? null : <Link href={ttHref(addDays(data.weekStart, -7), chosen)} className="btn btn-ghost" aria-label="이전 주">‹</Link>}
        <strong>{`${koDay(data.weekStart)} – ${koDay(data.weekEnd)}`}</strong>
        {outside(shownTerm, addDays(data.weekEnd, 1)) ? null : <Link href={ttHref(addDays(data.weekStart, 7), chosen)} className="btn btn-ghost" aria-label="다음 주">›</Link>}
        {data.weekStart <= data.today && data.today <= data.weekEnd ? null : <Link href={ttHref("", "")} className="btn btn-ghost">이번 주</Link>}
      </div>
      {terms.length ? <TermPicker key={String(data.termId ?? "")} terms={terms} termId={String(data.termId ?? "")} /> : null}
      {asked && !termId ? <p className="tiny srv-msg is-alert" role="status">고른 학기에 내 과목이 없어 현재 학기를 보여 줍니다.</p> : null}
      <p className="tiny">{`${shownTerm ? `${shownTerm.name || "고른 학기"}에 ` : ""}담당하거나 수강이 확정된 과목의 수업 시간입니다. 휴강과 보강은 반영하지 않습니다.`}</p>
      {slots.length ? grid : <EmptyState title="이 주에는 수업이 없습니다" body="다른 주를 보거나 수강 과목을 확인하세요." />}
      {slots.length ? (
        <div className="tt-list">
          {shown.map((iso) => {
            const mine = slots.filter((s) => s.date === iso);
            return (
              <section key={iso} className="att-card">
                <h2>{koDay(iso)}</h2>
                {mine.length ? null : <p className="tiny">수업 없음</p>}
                {mine.map((item, index) => (
                  <div key={index} className="tt-line">
                    <span className="rail-dot" />
                    <span><b>{item.courseName}</b><span className="tiny">{`${timeText(item)} · ${item.room || "-"}`}</span></span>
                  </div>
                ))}
              </section>
            );
          })}
        </div>
      ) : null}
      {untimed.length ? (
        <div className="srv-untimed">
          <h2 className="section-label">시각이 정해지지 않은 수업</h2>
          {untimed.map((item, index) => <p key={index} className="tiny">{`${DOW[item.dayOfWeek ?? 0]} · ${item.courseName} · ${timeText(item)} · ${item.room || "-"}`}</p>)}
        </div>
      ) : null}
    </>
  );
}

/**
 * 05 수업시간표. `?term=`(응답 `terms[]`의 학기)과 `?date=`가 든 주(일~토), 없으면 서버의 이번 주(학기를 고르면 그 학기 안의
 * 주). 고른 학기 밖의 날짜는 보내지 않는다. 시각이 없는 수업은 목록에만 둔다.
 */
export function serverTimetable(ctx: ScreenCtx): ReactNode {
  const params = ctx.params || new URLSearchParams();
  const asked = /^[1-9]\d{0,15}$/.test(params.get("term") || "") ? params.get("term") || "" : "";
  const termId = asked && (!knownTerms.size || knownTerms.has(asked)) ? asked : "";
  const askedDate = isoDate(params.get("date"));
  const date = termId && outside(knownTerms.get(termId), askedDate) ? "" : askedDate;
  const recover = termId ? <Link href={ttHref("", "")} className="btn btn-ghost">현재 학기 보기</Link> : null;
  return (
    <div className="srv-timetable">
      <Bound resource={timetables} scope="learning:timetable" rkey={timetableKey(date, termId)} args={[date, termId]} what="수업시간표를" recover={recover}>
        {(data) => <TimetableBody data={data} asked={asked} termId={termId} />}
      </Bound>
    </div>
  );
}

// ---------- 하루 일정 ----------

function DayRows({ data, day }: { data: CalendarRange; day: string }) {
  const events = (data.events || []).filter((event) => eventOn(event, day));
  const items = (data.activities || []).filter((item) => closeDay(item) === day);
  const home = homeEntry()?.data;
  if (!events.length && !items.length) return <p className="msg-placeholder">이 날짜에 등록된 일정이 없습니다.</p>;
  return (
    <>
      {events.map((event, index) => (
        <div key={`e${index}`} className="day-item">
          <span className="rail-dot" aria-hidden="true" />
          <span className="day-copy">
            <b>{event.title}</b>
            <span className="tiny">{`${EVENT_LABEL[event.type] || "학사일정"} · ${event.startDate === (event.endDate || event.startDate) ? event.startDate.replaceAll("-", ".") : `${event.startDate.replaceAll("-", ".")} ~ ${(event.endDate || "").replaceAll("-", ".")}`}`}</span>
          </span>
        </div>
      ))}
      {items.map((item) => {
        const href = calendarTodoHref(item, home);
        const kind = todoKind(item.type);
        const due = ddayAt(item.closeAt, data.serverTime);
        const done = isDone(item);
        const className = `day-item${href ? " todo-link" : ""}${done ? " is-done" : ""}`;
        const content = (
          <>
            <span className={`rail-dot ${kind}`} aria-hidden="true" />
            <span className="day-copy">
              <b>{item.published === false ? `[비공개] ${item.title}` : item.title}</b>
              <span className="tiny">{`${item.courseName} · ${[item.courseCode, item.section].filter(Boolean).join("-")} · ${todoLabel(item.type)} · 마감 ${clock(item.closeAt)}`}</span>
            </span>
            {done
              ? <span className="srv-done">{DONE_LABEL}</span>
              : <span className={due === "D-day" || due.startsWith("D-") ? "dday" : "tiny"}>{due === "마감" ? "지남" : due}</span>}
          </>
        );
        return href
          ? <Link key={`a${item.activityId}`} href={href} className={className} data-todo={kind}>{content}</Link>
          : <div key={`a${item.activityId}`} className={className} data-todo={kind}>{content}</div>;
      })}
    </>
  );
}

function ServerDayDialog({ day }: { day: string }) {
  const weekKey = rangeKey("week", "");
  // 제목은 서버의 오늘을 받은 뒤 바뀐다.
  const week = useResource(calendarRanges, day ? null : weekKey);
  const today = week?.data?.today;
  const label = day ? koDay(day) : "오늘 일정";
  return (
    <Dialog title={day ? koDay(day) : today ? koDay(today) : "오늘 일정"} label={label}>
      <div className="day-sheet">
        {day
          ? <Bound resource={calendarDays} scope="learning:day" rkey={day} args={[day]} what="일정을">{(data) => <DayRows data={data} day={day} />}</Bound>
          : <Bound resource={calendarRanges} scope="learning:day" rkey={weekKey} args={["week", ""]} what="일정을">{(data) => <DayRows data={data} day={data.today} />}</Bound>}
      </div>
    </Dialog>
  );
}

/** 17 하루 일정 창. `day=`가 없으면 서버의 오늘(이번 주 응답의 `today`)이다. */
export function serverDayDialog(ctx: ScreenCtx): ReactNode {
  return <ServerDayDialog day={isoDate(ctx.params && ctx.params.get("day"))} />;
}
