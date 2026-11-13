import { useEffect, useState, type ReactNode } from "react";
import { Link } from "../../router.tsx";
import { update, toast, useAppState } from "../../store.ts";
import { Icon } from "../../ui/icon.tsx";
import { CoverImage } from "../../ui/assets.tsx";
import { Button, CourseName, EmptyState, Field } from "../../ui/kit.tsx";
import { activities, courseById, courses, events, student, weekCount, type Course, type AppState } from "../../data.ts";
import { hrefFor, find } from "../../manifest.ts";
import { activityHref, attendStatus, dday, typeLabel, TODAY } from "../../shared/format.ts";
import { backendStatus, currentUserId } from "../../shared/backend.ts";
import { useHome, memberCourses, todoHref, todoKind, todoLabel, ddayAt, homeMessage, type HomeData, type HomeCourse } from "../../shared/home-remote.ts";
import { calendarRanges, timetables, rangeKey, isoDate, isoMonth, addDays, daysBetween, dayOfWeek, shiftMonth, eventOn, closeDay, clock, calendarMessage, type CalendarRange } from "../../shared/calendar-remote.ts";
import { useResource, type Entry } from "../../shared/remote-cache.ts";
import { serverCourseViews } from "../../shared/course-view.ts";
import type { Screen, ScreenCtx } from "../../types.ts";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];
type View = "week" | "month";
const dotted = (iso: string) => iso.replaceAll("-", ".");
const monthTitle = (ym: string) => `${ym.slice(0, 4)}년 ${Number(ym.slice(5))}월`;
const weekStart = (iso: string) => addDays(iso, -dayOfWeek(iso));

function majorityMonth(days: string[]): string {
  const counts = new Map<string, number>();
  days.forEach((day) => counts.set(day.slice(0, 7), (counts.get(day.slice(0, 7)) || 0) + 1));
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0][0];
}

function monthMatrix(month: string): string[] {
  const last = addDays(`${shiftMonth(month, 1)}-01`, -1);
  return daysBetween(weekStart(`${month}-01`), addDays(last, 6 - dayOfWeek(last)));
}

function pageHref(screen: Screen, { view = "week", month = "2026-11", day = "" }: { view?: View; month?: string; day?: string } = {}): string {
  const params = new URLSearchParams();
  if (view === "month") params.set("view", "month");
  if (month && month !== "2026-11") params.set("month", month);
  if (day && day !== TODAY) params.set("day", day);
  const query = params.toString();
  return `${screen.path}${query ? `?${query}` : ""}`;
}

function serverHref(screen: Screen, query: Record<string, string>): string {
  const params = new URLSearchParams(query).toString();
  return `${screen.path}${params ? `?${params}` : ""}`;
}

function DayLink({ iso, today, inMonth = true, dots = events.filter((event) => event.date === iso).slice(0, 3).map((event) => event.type) }: {
  iso: string; today: string; inMonth?: boolean; dots?: string[];
}) {
  const dow = dayOfWeek(iso);
  return <Link className={["m-day", iso === today ? "today" : "", inMonth ? "" : "out", dow === 0 ? "sun" : "", dow === 6 ? "sat" : ""].filter(Boolean).join(" ")}
    href={`${hrefFor(find("17_pop_calendar_day_detail"))}&day=${iso}`} aria-label={`${Number(iso.slice(-2))}일`}>
    <span className="m-num">{Number(iso.slice(-2))}</span><span className="m-dots" aria-hidden="true">{dots.map((type, index) => <i className={type} key={`${type}-${index}`} />)}</span>
  </Link>;
}

function CalendarView({ view, month, days, today, prev, next, monthHref, weekHref, range, data }: {
  view: View; month: string; days: string[]; today: string; prev: string; next: string;
  monthHref: string; weekHref: string; range: string; data?: CalendarRange;
}) {
  return <>
    <div className="m-tools">
      <span className="m-tools-side" aria-hidden="true" />
      <div className="m-seg" role="tablist" aria-label="달력 보기">
        <Link href={monthHref} role="tab" aria-selected={view === "month"} className={view === "month" ? "on" : ""}>월</Link>
        <Link href={weekHref} role="tab" aria-selected={view === "week"} className={view === "week" ? "on" : ""}>주</Link>
      </div>
      <Link className="m-icon-link" href={hrefFor(find("05_pop_lectime"))} aria-label="수업시간표"><Icon name="calendar" /></Link>
    </div>
    <div className="m-month">
      <Link className="m-round" href={prev} aria-label={view === "month" ? "이전 달" : "이전 주"}><span aria-hidden="true">‹</span></Link>
      <h1>{monthTitle(month)}</h1>
      <Link className="m-round" href={next} aria-label={view === "month" ? "다음 달" : "다음 주"}><span aria-hidden="true">›</span></Link>
    </div>
    <p className="m-range">{range}</p>
    <div className={view === "week" ? "m-week" : "m-month-grid"}>
      {DOW.map((name, index) => <div className={`m-dow${index === 0 ? " sun" : index === 6 ? " sat" : ""}`} key={name}>{name}</div>)}
      {days.map((iso) => <DayLink key={iso} iso={iso} today={today} inMonth={view === "week" || iso.slice(0, 7) === month}
        dots={data ? [
          ...(data.events || []).filter((event) => eventOn(event, iso)).map(() => "material"),
          ...(data.activities || []).filter((item) => closeDay(item) === iso).map((item) => todoKind(item.type)),
        ].slice(0, 3) : undefined} />)}
    </div>
  </>;
}

function SampleCalendar({ screen, ctx }: { screen: Screen; ctx: ScreenCtx }) {
  const view: View = ctx.params.get("view") === "month" ? "month" : "week";
  const requested = ctx.params.get("month") || "";
  const requestedMonth = /^\d{4}-\d{2}$/.test(requested) ? requested : "2026-11";
  const requestedDay = ctx.params.get("day") || "";
  const anchor = /^\d{4}-\d{2}-\d{2}$/.test(requestedDay) ? requestedDay : requestedMonth === "2026-11" ? TODAY : `${requestedMonth}-01`;
  const start = weekStart(anchor);
  const week = Array.from({ length: 7 }, (_, index) => addDays(start, index));
  const month = view === "week" ? majorityMonth(week) : requestedMonth;
  const days = view === "week" ? week : monthMatrix(month);
  const inMonth = days.filter((day) => day.slice(0, 7) === month);
  const prevDays = week.map((day) => addDays(day, -7));
  const nextDays = week.map((day) => addDays(day, 7));
  const prev = view === "month" ? pageHref(screen, { view, month: shiftMonth(month, -1) }) : pageHref(screen, { month: majorityMonth(prevDays), day: prevDays[0] });
  const next = view === "month" ? pageHref(screen, { view, month: shiftMonth(month, 1) }) : pageHref(screen, { month: majorityMonth(nextDays), day: nextDays[0] });
  const rangeDays = view === "week" ? week : inMonth;
  return <div className="m-cal" data-view={view}><CalendarView view={view} month={month} days={days} today={TODAY} prev={prev} next={next}
    monthHref={pageHref(screen, { view: "month", month, day: anchor })} weekHref={pageHref(screen, { view: "week", month, day: anchor })}
    range={`${dotted(rangeDays[0])} ~ ${dotted(rangeDays[rangeDays.length - 1])}`} /></div>;
}

function ServerCalendar({ screen, ctx }: { screen: Screen; ctx: ScreenCtx }) {
  const view: View = ctx.params.get("view") === "month" ? "month" : "week";
  const month = isoMonth(ctx.params.get("month"));
  const date = view === "month" ? month ? `${month}-01` : "" : isoDate(ctx.params.get("day"));
  const key = rangeKey(view, date);
  const entry = useResource(calendarRanges, key, () => { void calendarRanges.load(key, view, date); });
  useEffect(() => { void calendarRanges.visit("mobile:calendar", key, view, date); });
  const data = entry?.data;
  if (!data) return <div className="m-cal" data-view={view}>
    <p className="m-term" role={entry?.error ? "alert" : "status"}>{entry?.error ? calendarMessage(entry.error) : "일정을 불러오는 중입니다."}</p>
    {entry?.error ? <Button kind="ghost" disabled={Boolean(entry.loading)} onClick={() => { void calendarRanges.load(key, view, date); }}>다시 불러오기</Button> : null}
  </div>;
  const days = daysBetween(data.from, data.to);
  const shown = view === "month" ? month || data.today.slice(0, 7) : (days[Math.min(3, days.length - 1)] || data.from).slice(0, 7);
  const inMonth = days.filter((day) => day.slice(0, 7) === shown);
  const prev = view === "month" ? serverHref(screen, { view, month: shiftMonth(shown, -1) }) : serverHref(screen, { day: addDays(data.from, -7) });
  const next = view === "month" ? serverHref(screen, { view, month: shiftMonth(shown, 1) }) : serverHref(screen, { day: addDays(data.from, 7) });
  const rangeDays = view === "week" || !inMonth.length ? [data.from, data.to] : inMonth;
  return <div className="m-cal" data-view={view}><CalendarView view={view} month={shown} days={days} today={data.today} prev={prev} next={next} data={data}
    monthHref={serverHref(screen, { view: "month", month: shown })}
    weekHref={view === "week" ? serverHref(screen, { day: data.from }) : serverHref(screen, shown === data.today.slice(0, 7) ? {} : { day: `${shown}-01` })}
    range={`${dotted(rangeDays[0])} ~ ${dotted(rangeDays[rangeDays.length - 1])}`} /></div>;
}

function attendRatio(state: AppState) {
  let hit = 0;
  let total = 0;
  for (let week = 1; week <= weekCount; week += 1) {
    const status = attendStatus(state, student.name, week);
    if (status === "empty") continue;
    total += 1;
    if (["present", "late", "excused"].includes(status)) hit += 1;
  }
  return { hit, total };
}

type CourseStats = HomeCourse & { currentWeek?: number; weekCount?: number; attendance?: { attended?: number; total?: number } };
const count = (value: unknown): number | null => typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : null;

function CourseCard({ course, state, remote }: { course: Course; state: AppState; remote?: CourseStats }) {
  const special = course.kind === "special";
  const room = hrefFor(find(special ? "70_nr_room_submain" : "40_room_submain"), { kj: course.id });
  const activity = special ? room : hrefFor(find("50_activity_all"), { kj: course.id });
  const attend = hrefFor(find(special ? "74_nr_room_attendance" : "41_room_attendance"), { kj: course.id });
  const week = remote ? count(remote.currentWeek) : Math.min(weekCount, Math.round(((course.progress || 0) / 100) * weekCount));
  const weeks = remote ? count(remote.weekCount) : weekCount;
  const ratio = remote ? { hit: count(remote.attendance?.attended), total: count(remote.attendance?.total) } : attendRatio(state);
  const progress = remote ? week != null && weeks ? Math.round((Math.min(week, weeks) / weeks) * 100) : null : course.progress || 0;
  return <article className="m-card">
    <Link className={`m-cover ${course.gradient}`} href={room} aria-label={`${course.cardTitle || course.ko} 강의실`}>
      {!remote ? <CoverImage course={course} width={358} height={140} /> : null}
      <CourseName course={course} as="strong" /><span className="m-code">{course.code}</span>
    </Link>
    <div className="m-card-body">
      <p className="m-meta"><Icon name="calendar" /><span>{remote ? course.time || "시간 미정" : course.time}</span></p>
      {!remote || course.credit ? <p className="m-meta"><Icon name="users" /><span>{course.credit}학점</span></p> : null}
      {week != null && weeks ? <div className="m-progress"><span>진행 중</span><span>{Math.min(week, weeks)} / {weeks} 주</span></div> : null}
      {progress != null ? <div className="m-bar" aria-hidden="true"><i style={{ width: `${progress}%` }} /></div> : null}
      <div className="m-actions"><Link className="m-action" href={activity}>참여</Link><Link className="m-action" href={attend}>{ratio.hit != null && ratio.total != null ? `출석 ${ratio.hit} / ${ratio.total}` : "출석"}</Link></div>
      <Link className="btn btn-primary course-study-entry m-study-entry" href={hrefFor(find("study_home"), { kj: course.id })} aria-label={`${course.cardTitle || course.ko} 학습실 입장`}>학습실 입장</Link>
    </div>
  </article>;
}

function ServerSlot({ entry, children }: { entry: Entry<HomeData> | null; children: (data: HomeData) => ReactNode }) {
  return <div className="m-server">{entry?.data ? children(entry.data) : <p className="m-term" role={entry?.error ? "alert" : "status"}>{entry?.error ? homeMessage(entry.error) : "ASTRA 서버에서 불러오는 중입니다."}</p>}</div>;
}

function CourseList({ state, ready, home }: { state: AppState; ready: boolean; home: Entry<HomeData> | null }) {
  const list = courses.filter((course) => course.kind === "regular" && course.enrolled && course.term === "2026-2");
  const views = ready ? serverCourseViews() : [];
  const current = views.find(({ remote }) => remote.term?.current) || views.find(({ remote }) => remote.term);
  const term = current?.course?.term || "";
  return <div className="m-courses"><h2 className="m-section">수강과목</h2>
    {ready ? term ? <p className="m-term">{term}</p> : null : <p className="m-term">2학기</p>}
    {ready ? <ServerSlot entry={home}>{(data) => {
      const { matched } = memberCourses(data);
      return matched.length ? <div className="m-cards">{matched.map(({ local, remote }) => <CourseCard key={local.id} course={local} state={state} remote={remote} />)}</div> : <EmptyState title="수강 중인 과목이 없습니다" body="ASTRA 서버에 이 계정의 과목이 없습니다." />;
    }}</ServerSlot> : list.length ? <div className="m-cards">{list.map((course) => <CourseCard key={course.id} course={course} state={state} />)}</div> : <EmptyState title="수강 중인 정규과목이 없습니다" body="이번 학기 과목이 등록되면 여기에 표시됩니다." />}
  </div>;
}

function ServerClasses() {
  const entry = useResource(timetables, "", () => { void timetables.load("", "", ""); });
  useEffect(() => { void timetables.visit("mobile:today", "", "", ""); });
  const data = entry?.data;
  const today = data?.slots?.filter((slot) => slot.date === data.today) || [];
  return <div className="m-classes">{!data ? <p className="m-term" role={entry?.error ? "alert" : "status"}>{entry?.error ? calendarMessage(entry.error) : "오늘 수업을 불러오는 중입니다."}</p> : today.length ? today.map((slot, index) => <div className="m-class" key={`${slot.courseId}-${index}`}><b>{slot.courseName}</b><span>{slot.startTime ? `${clock(slot.startTime)}–${clock(slot.endTime)}` : `${slot.periodStart || ""}–${slot.periodEnd || slot.periodStart || ""}교시`}</span></div>) : <p className="m-term">오늘은 수업이 없습니다.</p>}</div>;
}

function ServerTodos({ data }: { data: HomeData }) {
  const items = data.todo?.items || [];
  return <><h3>남은 활동 {Number(data.todo?.total) || 0}</h3>{items.length ? <div className="m-todos">{items.map((item, index) => {
    const href = todoHref(item, data);
    const content = <><i className={`m-todo-mark ${todoKind(item.type)}`} aria-hidden="true" /><span className="m-todo-copy"><b>{item.title}</b><span>{todoLabel(item.type)} · {item.courseName}</span></span><em>{ddayAt(item.closeAt, data.todo?.serverTime || data.serverTime)}</em></>;
    return href ? <Link className="m-todo" href={href} key={`${item.courseId}-${item.activityId}-${index}`}>{content}</Link> : <div className="m-todo" key={`${item.courseId}-${item.activityId}-${index}`}>{content}</div>;
  })}</div> : <EmptyState title="남은 활동이 없습니다" body="새 과제와 설문이 생기면 이 목록에 표시됩니다." />}</>;
}

function noteOf(draft: AppState["draft"]): string {
  const user = currentUserId();
  return user ? draft.mobileNotes?.[`u${user}`] || "" : draft.mobileNote || "";
}

function TodoSheet({ state, ready, home }: { state: AppState; ready: boolean; home: Entry<HomeData> | null }) {
  const saved = noteOf(state.draft);
  const [note, setNote] = useState(saved);
  const [error, setError] = useState("");
  const todos = activities.filter((item) => ["missing", "ongoing", "scheduled"].includes(item.status));
  const submit = () => {
    const value = note.trim();
    if (!value) { setError("내용을 입력하세요"); return; }
    const user = currentUserId();
    update((next) => {
      if (user) next.draft.mobileNotes = { ...next.draft.mobileNotes, [`u${user}`]: value };
      else next.draft.mobileNote = value;
    });
    setNote(value);
    setError("");
    toast("할 일 메모를 저장했습니다");
  };
  return <div className="m-sheet-layer">
    <Link className="m-sheet-scrim" href="/ilos/m/main_form.acl" aria-label="할 일 닫기" />
    <section className="m-sheet" aria-label="할 일">
      <div className="m-sheet-h"><h2>할 일</h2><Link className="m-text-btn" href="/ilos/m/main_form.acl">닫기</Link></div>
      {saved ? <p className="m-note-saved">{saved}</p> : null}
      <h3>오늘 수업</h3>
      {ready ? <ServerClasses /> : <div className="m-classes"><div className="m-class"><b>소프트웨어공학</b><span>10:00–13:00</span></div><div className="m-class"><b>알고리즘</b><span>14:00–16:00</span></div></div>}
      <Link className="m-block-btn" href={hrefFor(find("05_pop_lectime"))}>수업시간표</Link>
      {ready ? <ServerSlot entry={home}>{(data) => <ServerTodos data={data} />}</ServerSlot> : <>
        <h3>남은 활동 {todos.length}</h3>
        {todos.length ? <div className="m-todos">{todos.map((item) => <Link className="m-todo" href={activityHref(item, item.courseId)} key={item.id}>
          <i className={`m-todo-mark ${item.type}`} aria-hidden="true" /><span className="m-todo-copy"><b>{item.title}</b><span>{typeLabel(item.type)} · {courseById(item.courseId).ko}</span></span><em>{dday(item.due) || "진행"}</em>
        </Link>)}</div> : <EmptyState title="남은 활동이 없습니다" body="새 과제와 설문이 생기면 이 목록에 표시됩니다." />}
      </>}
      <div className="m-note" role="form" aria-label="할 일 메모">
        <Field label="메모" error={error}><input name="note" value={note} onChange={(event) => { setNote(event.target.value); setError(""); }} placeholder="이 브라우저에만 남습니다" autoComplete="off" aria-label="메모" aria-invalid={Boolean(error)} /></Field>
        <Button kind="primary" onClick={submit}>저장</Button>
      </div>
    </section>
  </div>;
}

function Tabbar({ screen }: { screen: Screen }) {
  return <nav className="m-tab" aria-label="모바일 탐색">
    <Link href="/ilos/m/main_form.acl" className={screen.mobileMenu === "closed" ? "on" : ""} aria-current={screen.mobileMenu === "closed" ? "page" : undefined}><Icon name="home" /><span>홈</span></Link>
    <Link href="/ilos/m/menu_form.acl" className={screen.mobileMenu === "nav" ? "on" : ""} aria-current={screen.mobileMenu === "nav" ? "page" : undefined}><Icon name="menu" /><span>메뉴</span></Link>
    <Link href="/ilos/m/aside_form.acl" className={screen.mobileMenu === "aside" ? "on" : ""} aria-current={screen.mobileMenu === "aside" ? "page" : undefined}><Icon name="check" /><span>할 일</span></Link>
  </nav>;
}

function Mobile({ screen, ctx }: { screen: Screen; ctx: ScreenCtx }) {
  const state = useAppState();
  const home = useHome();
  const ready = backendStatus() === "ready";
  return <section className="m-app" data-area="mobile">
    {ready ? <ServerCalendar screen={screen} ctx={ctx} /> : <SampleCalendar screen={screen} ctx={ctx} />}
    <CourseList state={state} ready={ready} home={home} />
    {screen.mobileMenu === "aside" ? <TodoSheet key={String(currentUserId() || "sample")} state={state} ready={ready} home={home} /> : null}
    <Tabbar screen={screen} />
  </section>;
}

export function render(screen: Screen, ctx: ScreenCtx): ReactNode {
  return <Mobile screen={screen} ctx={ctx} />;
}
