// 백엔드 연결(`ready`) 때의 홈 달력(월·주)과 오른쪽 칼럼의 오늘 수업. 날짜와 오늘은 서버 응답
// (`today`, `from`, `to`)으로만 정한다. 주소에 날짜가 없으면 서버가 DB 시계의 오늘로 고른다.
// 일정 막대는 모두 그날의 일정 창(`?pop=day&day=`)으로 간다. 서버 글자는 텍스트로만 넣는다.
import { useEffect, type ReactNode } from "react";
import { Link, GoButton } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { LoadingState, ErrorState } from "../../ui/kit.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { typeIcon } from "../../shared/format.ts";
import { todoKind, todoLabel } from "../../shared/home-remote.ts";
import { useResource } from "../../shared/remote-cache.ts";
import {
  calendarRanges, timetables, rangeKey, isoDate, isoMonth, addDays, daysBetween, dayOfWeek, shiftMonth,
  monthTitle, koShortDay, clock, eventOn, closeDay, EVENT_LABEL, calendarMessage, isDone, DONE_LABEL,
} from "../../shared/calendar-remote.ts";
import type { CalendarActivity, CalendarEvent, CalendarRange } from "../../shared/calendar-remote.ts";
import { RetryButton } from "./home-server.tsx";

const HOME = "/ilos/main/main_form.acl";
const DOW = ["일", "월", "화", "수", "목", "금", "토"];

function homeHref(query: Record<string, string>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) if (value) params.set(key, value);
  const q = params.toString();
  return `${HOME}${q ? `?${q}` : ""}`;
}

function dayHref(iso: string): string {
  return `${hrefFor(find("17_pop_calendar_day_detail"))}&day=${iso}`;
}

function IconLink({ href, label, name }: { href: string; label: string; name: string }) {
  return <Link href={href} className="icon-btn" aria-label={label}><Icon name={name} /></Link>;
}

interface Seg {
  start: number;
  span: number;
  lane: number;
  event?: CalendarEvent;
  activity?: CalendarActivity;
}

/** 한 주(7일) 안의 막대: 학사일정은 걸친 날만큼 이어 붙이고, 활동은 마감일 칸 하나다. 겹치면 줄을 나눈다. */
function weekBars(week: string[], data: CalendarRange): { segs: Seg[]; lanes: number } {
  const segs: Seg[] = [];
  const first = week[0]!;
  const last = week[6]!;
  for (const event of data.events || []) {
    const end = event.endDate || event.startDate;
    if (end < first || event.startDate > last) continue;
    const start = week.indexOf(event.startDate < first ? first : event.startDate);
    const stop = week.indexOf(end > last ? last : end);
    segs.push({ start, span: stop - start + 1, lane: 0, event });
  }
  for (const activity of data.activities || []) {
    const at = week.indexOf(closeDay(activity));
    if (at >= 0) segs.push({ start: at, span: 1, lane: 0, activity });
  }
  segs.sort((a, b) => a.start - b.start || b.span - a.span);
  const lanes: number[][] = [];
  for (const seg of segs) {
    const end = seg.start + seg.span;
    let lane = lanes.findIndex((used) => used.every((c) => c < seg.start || c >= end));
    if (lane < 0) lane = lanes.push([]) - 1;
    for (let c = seg.start; c < end; c += 1) lanes[lane]!.push(c);
    seg.lane = lane;
  }
  return { segs, lanes: lanes.length };
}

function Bar({ seg, week }: { seg: Seg; week: string[] }) {
  const href = dayHref(week[seg.start]!);
  const style = { gridColumn: `${seg.start + 1} / span ${seg.span}`, gridRow: seg.lane + 2 };
  if (seg.event) {
    const full = `${EVENT_LABEL[seg.event.type] || "일정"} · ${seg.event.title}`;
    return (
      <Link className="ecal-ev plain" href={href} title={full} aria-label={full} style={style}>
        <span className="ecal-label">{seg.event.title}</span>
      </Link>
    );
  }
  const item = seg.activity!;
  const kind = todoKind(item.type);
  const done = isDone(item);
  const full = `${item.courseName} · ${todoLabel(item.type)} · ${item.title} 마감 ${clock(item.closeAt)}${done ? ` · ${DONE_LABEL}` : ""}`;
  return (
    <Link className={`ecal-ev ${kind}${done ? " is-done" : ""}`} href={href} title={full} aria-label={full} style={style}>
      <span className="ecal-mark"><Icon name={typeIcon(kind)} /></span>
      {done ? <span className="srv-done" aria-hidden="true">{DONE_LABEL}</span> : null}
      <span className="ecal-label">{item.title}</span>
    </Link>
  );
}

function WeekRow({ week, data, month }: { week: string[]; data: CalendarRange; month: string }) {
  const { segs, lanes } = weekBars(week, data);
  return (
    <div className="ecal-week" style={{ gridTemplateRows: `var(--ecal-head) ${lanes ? `repeat(${lanes}, auto) ` : ""}minmax(var(--space-2), 1fr)` }}>
      {week.map((iso, i) => {
        const count = (data.events || []).filter((event) => eventOn(event, iso)).length
          + (data.activities || []).filter((item) => closeDay(item) === iso).length;
        const today = iso === data.today;
        const cls = ["ecal-day", iso.slice(0, 7) === month ? "" : "out", today ? "today" : ""].filter(Boolean).join(" ");
        return (
          <div key={iso} className={cls} style={{ gridColumn: i + 1 }}>
            <GoButton
              className="ecal-num day-hit"
              href={dayHref(iso)}
              aria-current={today ? "date" : undefined}
              aria-label={`${iso} 일정 ${count}건`}
            >
              {String(Number(iso.slice(8, 10)))}
            </GoButton>
          </div>
        );
      })}
      {segs.map((seg, index) => <Bar key={index} seg={seg} week={week} />)}
    </div>
  );
}

/**
 * 홈 달력. 주소: 주 보기(기본) `week=yyyy-MM-dd` 또는 `month=yyyy-MM`(그달 1일이 든 주),
 * 월 보기 `cal=month&month=yyyy-MM`. 날짜가 없으면 오늘이 든 주·달이다.
 */
export function serverHomeCalendar(params: URLSearchParams): ReactNode {
  return <ServerHomeCalendar params={params} />;
}

function ServerHomeCalendar({ params }: { params: URLSearchParams }) {
  const monthView = params.get("cal") === "month";
  const month = isoMonth(params.get("month"));
  const date = monthView
    ? (month ? `${month}-01` : "")
    : isoDate(params.get("week")) || (month ? `${month}-01` : "");
  const view = monthView ? "month" : "week";
  const key = rangeKey(view, date);
  const reload = () => void calendarRanges.load(key, view, date);
  const entry = useResource(calendarRanges, key, reload);
  useEffect(() => {
    void calendarRanges.visit("home:calendar", key, view, date);
  });
  const data = entry && entry.data;
  return (
    <div className="home-server-cal">
      {data
        ? <CalendarView data={data} monthView={monthView} date={date} />
        : entry && entry.error
          ? <ErrorState title="일정을 불러오지 못했습니다" body={calendarMessage(entry.error)} actions={<RetryButton reload={reload} />} />
          : <LoadingState title="일정을 불러오는 중입니다" />}
    </div>
  );
}

function CalendarView({ data, monthView, date }: { data: CalendarRange; monthView: boolean; date: string }) {
  const days = daysBetween(data.from, data.to);
  const anchor = date || data.today;
  const month = monthView ? anchor.slice(0, 7) : (days[3] ?? anchor).slice(0, 7);
  const weekHref = homeHref({ week: monthView ? addDays(anchor, -dayOfWeek(anchor)) : data.from });
  const monthHref = homeHref({ cal: "month", month });
  const prev = monthView ? homeHref({ cal: "month", month: shiftMonth(month, -1) }) : homeHref({ week: addDays(data.from, -7) });
  const next = monthView ? homeHref({ cal: "month", month: shiftMonth(month, 1) }) : homeHref({ week: addDays(data.from, 7) });
  const weeks: string[][] = [];
  for (let i = 0; i + 7 <= days.length; i += 7) weeks.push(days.slice(i, i + 7));
  return (
    <>
      <div className="cal-tools">
        <div className="seg" role="tablist" aria-label="달력 보기">
          <Link href={monthHref} role="tab" aria-selected={monthView ? "true" : "false"} className={monthView ? "on" : ""}>월</Link>
          <Link href={weekHref} role="tab" aria-selected={monthView ? "false" : "true"} className={monthView ? "" : "on"}>주</Link>
        </div>
        <Link href={homeHref({})} className="btn btn-ghost">오늘</Link>
        <IconLink href={dayHref(data.today)} label="오늘 일정" name="calendar" />
      </div>
      <div className="cal-head">
        <IconLink href={prev} label={monthView ? "이전 달" : "이전 주"} name="chevL" />
        <div className="cal-title">
          <h1>{monthTitle(month)}</h1>
          {monthView ? null : <p className="cal-range">{`${data.from.replaceAll("-", ".")} ~ ${data.to.replaceAll("-", ".")}`}</p>}
        </div>
        <IconLink href={next} label={monthView ? "다음 달" : "다음 주"} name="chev" />
      </div>
      <div className={`${monthView ? "cal" : "home-cal"} ecal`} role="group" aria-label={monthView ? "월간 달력" : "주간 달력"}>
        <div className="ecal-dows" aria-hidden="true">
          {DOW.map((name) => <div key={name} className={`ecal-dow${name === "일" ? " sun" : name === "토" ? " sat" : ""}`}>{name}</div>)}
        </div>
        {weeks.map((week) => <WeekRow key={week[0]} week={week} data={data} month={month} />)}
      </div>
      {data.truncated
        ? <p className="tiny srv-msg is-alert" role="status">이 기간의 활동 마감이 많아 앞의 500개만 표시했습니다. 주 보기에서 나머지를 확인하세요.</p>
        : null}
    </>
  );
}

/** 오른쪽 칼럼의 오늘 수업: 이번 주 시간표에서 `date == today`인 수업. */
export function serverTodayClasses(): ReactNode {
  return <ServerTodayClasses />;
}

function ServerTodayClasses() {
  const entry = useResource(timetables, "", () => void timetables.load("", ""));
  useEffect(() => {
    void timetables.visit("home:today", "", "");
  });
  const data = entry && entry.data;
  const today = data ? (data.slots || []).filter((slot) => slot.date === data.today) : [];
  return (
    <>
      <div className="aside-h"><h2>오늘 수업</h2><span className="tiny">{data ? koShortDay(data.today) : ""}</span></div>
      <div className="home-server-today">
        {!data
          ? <p className="tiny" role={entry && entry.error ? "alert" : "status"}>{entry && entry.error ? calendarMessage(entry.error) : "오늘 수업을 불러오는 중입니다."}</p>
          : today.length
            ? today.map((slot, index) => (
              <article key={index} className="aside-card today-class">
                <b>{slot.courseName}</b>
                <div className="tiny">{`(${[slot.courseCode, slot.section].filter(Boolean).join("-")})`}</div>
                <div className="time-row"><i className="dot" /><span>{slot.startTime ? `${clock(slot.startTime)}~${clock(slot.endTime)}` : `${slot.periodStart || ""}~${slot.periodEnd || ""}교시`}</span></div>
                <div className="tiny">{slot.room || "-"}</div>
              </article>
            ))
            : <p className="tiny">오늘은 수업이 없습니다.</p>}
      </div>
    </>
  );
}
