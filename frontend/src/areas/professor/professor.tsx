import { Fragment, cloneElement, createElement, isValidElement, useEffect, useRef, useState, type ChangeEvent, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import { CoverImage } from "../../ui/assets.tsx";
import { Badge, Breadcrumbs, Button, EmptyState, Field, ListRow, Tabs, type BadgeTone, type ButtonKind } from "../../ui/kit.tsx";
import { Link } from "../../router.tsx";
import { navigate } from "../../router.tsx";
import { getState, save, render as repaint, toast } from "../../store.ts";
import { readForm, showErrors, stamp } from "../../forms.ts";
import { activities, people, courseById, gradeItems, weekCount, professor as teacher, files, events, notices } from "../../data.ts";
import type { Activity, ActivityType, AppState, Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { routeFor } from "../../shared/routes.ts";
import { attendStatus, statusLabel, dday, typeLabel, boardRows } from "../../shared/format.ts";
import { LECTURES } from "../../media/catalog.ts";
import { mediaFile, mediaForActivity } from "../../media.ts";
import { activityPhase, endsAfter, isClosedActivity, removeActivity, upsertActivity } from "../../shared/activity-library.ts";
import { plainText, richNodes, richPlain, sanitizeRichSource } from "../../shared/rich-text.tsx";
import { bindUploadedVideo, deleteActivityFile, getActivityFile, putActivityFile } from "../../shared/activity-files.ts";
import { listCoursePdfs, saveCoursePdf, deleteCoursePdf, type CoursePdfRecord } from "../../shared/course-pdfs.ts";
import { isRemotePdfId, getPublishedSummary, listSummaryJobs, startSummaryJob, getSummaryJob, cancelSummaryJob, retrySummaryJob, getSummaryResult, publishSummary, summaryMessage } from "../../shared/course-pdfs-remote.ts";
import type { RemotePdfRecord, ServerSummary, SummaryJob } from "../../shared/course-pdfs-remote.ts";
import { forgetMaterialList } from "../../shared/materials-remote.ts";
import { backendStatus } from "../../shared/backend.ts";
import { summaryView } from "../../shared/page-summary.tsx";
import { serverProfessorHome, serverNoticeFields } from "./notices-server.tsx";
import { serverMaterialList, registrationAttach, registrationPicked, saveRegistrationFromFilebox } from "./materials-server.tsx";
import { serverReportFields, serverSubmissionStatus, serverGrading } from "./assignments-server.tsx";
import { serverTaughtCourses, serverEnrollmentRequests, serverCourseRoster } from "./enrollments-server.tsx";
import { serverMessageFields } from "./messages-server.tsx";
import { qnaSection } from "../../shared/board-list.tsx";
import { serverExamFields, serverExamStatus, serverExamGrade, serverQuizDetail } from "./quizzes-server.tsx";
import { serverSurveyFields } from "./surveys-server.tsx";
import { serverGradebook } from "./grades-server.tsx";
import { serverAttendGeneral, serverAttendSmart, serverAttendSessions } from "./attendance-server.tsx";
import { serverTeams } from "./groups-server.tsx";
import { llmConsentSection, consentLead } from "./llm-consent-server.tsx";
import { serverVideoCourse, serverLectureManager, serverLectureCreate, serverLectureDetail } from "./lectures-server.tsx";
import { wallFormat } from "../../shared/home-remote.ts";
import { serverRoom, serverRoomView, serverActivityChooser } from "./room-server.tsx";
import { serverMaterialDetail, serverStudyRoom } from "./materials-server.tsx";
import { serverProfessorCalendar } from "./calendar-server.tsx";
import { serverCourseNotices } from "./notices-server.tsx";
import { serverSyllabusEditor } from "./syllabus-server.tsx";

declare module "../../data.ts" {
  interface Activity {
    /** Meeting link of an authored Zoom activity (professor editor). */
    meetingUrl?: string;
  }
}

/** A dialog over a professor screen (`state.draft.pfDialog`). */
interface PfDialog {
  kind: "delete-activity" | "question" | "bank" | "delete-question" | "delete-bank";
  id: string;
}
interface PfSort {
  table: string;
  key: string;
  dir: "asc" | "desc";
}
interface Makeup {
  week: string;
  when: string;
  why: string;
}
interface Team {
  name: string;
  topic: string;
  size: number;
}
interface Zoom {
  title: string;
  meeting: string;
  start: string;
  week: string;
}
interface QnaPost {
  id: string;
  board?: string;
  title: string;
  body?: string;
  author?: string;
  at?: string;
}
type Person = (typeof people)[number];

// AppState types these sample lists as unknown[]; professor.tsx is the only writer of their shape.
const teamsOf = (state: AppState) => state.teams as Team[];
const zoomsOf = (state: AppState) => state.zooms as Zoom[];
const postsOf = (state: AppState) => state.posts as QnaPost[];

const learners = (): Person[] => people.filter((p) => p.role === "student");
const TYPES: [ActivityType, string][] = [["video", "영상"], ["zoom", "Zoom"], ["material", "자료"], ["post", "글"], ["report", "과제"], ["test", "시험"], ["survey", "설문"]];
const ACTIVITY_STATUS: Record<string, [string, BadgeTone]> = {
  done: ["완료", "success"],
  ongoing: ["진행", "neutral"],
  missing: ["미제출", "danger"],
  open: ["열림", "neutral"],
  expired: ["마감", "danger"],
  submitted: ["제출", "success"],
  graded: ["채점", "success"],
  scheduled: ["예정", "neutral"],
};
const SMART: Record<string, number> = { minjun: 92, seoyeon: 88, jieun: 100, doyun: 74, haeun: 96, woojin: 63, harin: 91 };

/** Children given as separate arguments (no key warnings for fixed-position nodes). */
function kids(nodes: ReactNode[]): ReactNode {
  return createElement(Fragment, null, ...nodes);
}

function commit() {
  save();
  repaint();
}

function param(ctx: ScreenCtx, key: string): string {
  return (ctx.params && ctx.params.get(key)) || "";
}

function pf(screenId: string, extra?: Record<string, string>): string {
  return routeFor("professor", "screen", { screenId, ...(extra || {}) });
}

function kept(ctx: ScreenCtx, screenId: string, patch: Record<string, string> = {}): string {
  const extra: Record<string, string> = {};
  if (ctx.params) {
    for (const key of ["q", "view", "type", "bank"]) {
      const value = ctx.params.get(key);
      if (value) extra[key] = value;
    }
  }
  Object.assign(extra, patch);
  for (const key of Object.keys(extra)) {
    if (extra[key] == null || extra[key] === "" || extra[key] === "all") delete extra[key];
  }
  return pf(screenId, extra);
}

function withQ(ctx: ScreenCtx, screenId: string): string {
  const q = param(ctx, "q");
  return pf(screenId, q ? { q } : {});
}

function courseOf(ctx: ScreenCtx): Course {
  return courseById(ctx.state.courseId);
}

function matchesQuery(ctx: ScreenCtx, text: string): boolean {
  const q = param(ctx, "q").trim();
  return !q || String(text).includes(q);
}

function stat(label: string, value: string, extra?: ReactNode): ReactNode {
  return (
    <div className="stat pf-stat">
      <span className="tiny">{label}</span>
      <b>{value}</b>
      {extra || null}
    </div>
  );
}

function meter(pct: number): ReactNode {
  const n = Math.max(0, Math.min(100, Math.round(pct)));
  return (
    <div className="pf-meter">
      <div className="bar" aria-hidden="true"><i style={{ width: `${n}%` }} /></div>
      <span>{`${n}%`}</span>
    </div>
  );
}

/** Search box: the button or Enter puts `q` into the address. */
function pfFind() {
  const q = String(readForm().q || "").trim();
  const url = new URL(location.href);
  if (q) url.searchParams.set("q", q);
  else url.searchParams.delete("q");
  navigate(`${url.pathname}${url.search}`);
}

function finder(ctx: ScreenCtx, label: string): ReactNode {
  const q = param(ctx, "q");
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    event.preventDefault();
    pfFind();
  };
  return (
    <div className="pf-search">
      <input key={q} type="search" data-name="q" defaultValue={q} placeholder={label} aria-label={label} onKeyDown={onKeyDown} />
      <Button kind="secondary" onClick={pfFind}>검색</Button>
    </div>
  );
}

function chipRow(ctx: ScreenCtx, screenId: string, options: [string, string, string?][], key: string): ReactNode {
  const current = param(ctx, key) || "all";
  return (
    <div className="filters" role="group" aria-label="필터">
      {options.map(([value, label, tone]) => {
        const on = current === value;
        return <Link key={value} href={kept(ctx, screenId, { [key]: value })} className={`chip ${tone || "all"}${on ? " on" : ""}`}>{label}</Link>;
      })}
    </div>
  );
}

interface FrameOpts {
  course?: boolean;
  trail?: boolean;
  kicker?: string;
  sub?: string;
  actions?: ReactNode[];
}

function frame(title: string, ctx: ScreenCtx, opts: FrameOpts, ...nodes: ReactNode[]): ReactNode {
  const course = opts.course ? courseOf(ctx) : null;
  const crumbs = opts.trail === false ? null : [
    { label: "교수 홈", href: pf("pf_main") },
    ...(course ? [{ label: course.ko, href: pf("pf_room_week", { kj: course.id }) }] : []),
    { label: title },
  ];
  return (
    <section data-area="professor">
      <header className="pf-head">
        <div className="pf-title">
          {crumbs ? <Breadcrumbs items={crumbs} /> : null}
          {opts.kicker ? <p className="pf-kicker">{opts.kicker}</p> : null}
          <h1>{title}</h1>
          {opts.sub ? <p className="pf-sub">{opts.sub}</p> : null}
        </div>
        {opts.actions && opts.actions.length ? <div className="pf-actions">{kids(opts.actions)}</div> : null}
      </header>
      {kids(nodes)}
    </section>
  );
}

type SortValue = string | number | null | undefined;
interface TableRecord {
  sort: Record<string, SortValue>;
  cells: ReactNode[];
}
interface Column {
  key: string;
  label: string;
  sort?: boolean;
  lock?: boolean;
}

function compareRows(a: TableRecord, b: TableRecord, key: string, dir: string): number {
  const av = a.sort[key];
  const bv = b.sort[key];
  const empty = (value: SortValue) => value == null || value === "";
  if (empty(av) && empty(bv)) return 0;
  if (empty(av)) return 1;
  if (empty(bv)) return -1;
  const base = typeof av === "number" && typeof bv === "number"
    ? av - bv
    : String(av).localeCompare(String(bv), "ko", { numeric: true, sensitivity: "base" });
  return dir === "desc" ? -base : base;
}

function sortBy(table: string, key: string) {
  const state = getState();
  const current: Partial<PfSort> = state.draft.pfSort || {};
  const dir = current.table === table && current.key === key && current.dir === "asc" ? "desc" : "asc";
  state.draft.pfSort = { table, key, dir } satisfies PfSort;
  commit();
}

function dataTable(tableId: string, label: string, columns: Column[], records: TableRecord[], ctx: ScreenCtx, options: { layout?: "scroll" | "cards"; wide?: boolean; attend?: boolean } = {}): ReactNode {
  const sort: Partial<PfSort> = ctx.state.draft.pfSort || {};
  const active = sort.table === tableId && sort.key ? sort as PfSort : null;
  const ordered = records.map((record, index) => ({ record, index }));
  if (active) {
    ordered.sort((a, b) => {
      const cmp = compareRows(a.record, b.record, active.key, active.dir);
      return cmp || a.index - b.index;
    });
  }
  const wrapClass = `table-wrap table-${options.layout === "scroll" ? "scroll" : "cards"}${options.wide ? " pf-wide" : ""}${options.attend ? " pf-attend" : ""}`;
  return (
    <div className={wrapClass} data-table={tableId}>
      <table className="grid" aria-label={label}>
        <thead>
          <tr>
            {columns.map((col) => {
              const on = Boolean(active && active.key === col.key);
              const aria = on && active ? (active.dir === "asc" ? "ascending" : "descending") : "none";
              if (!col.sort) return <th key={col.key} scope="col" className={col.lock ? "pf-lock" : undefined}>{col.label}</th>;
              return (
                <th key={col.key} scope="col" className={col.lock ? "pf-lock" : undefined} aria-sort={aria}>
                  <button type="button" className="pf-sort" aria-label={col.label} onClick={() => sortBy(tableId, col.key)}>
                    <span>{col.label}</span>
                    {on && active ? <span aria-hidden="true">{active.dir === "asc" ? "↑" : "↓"}</span> : null}
                  </button>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {ordered.map(({ record, index }) => (
            <tr key={index}>
              {columns.map((col, i) => <td key={col.key} data-label={col.label} className={col.lock ? "pf-lock" : undefined}>{record.cells[i]}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function openDialog(kind: PfDialog["kind"], id: string) {
  getState().draft.pfDialog = { kind, id } satisfies PfDialog;
  commit();
}

function dismiss() {
  getState().draft.pfDialog = null;
  commit();
}

function pfDialog(title: string, ...body: ReactNode[]): ReactNode {
  const onBack = (event: MouseEvent<HTMLDivElement>) => {
    if (event.target === event.currentTarget) dismiss();
  };
  return (
    <div className="modal-back" onClick={onBack}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-h">
          <h2>{title}</h2>
          <button type="button" className="x" aria-label="닫기" onClick={dismiss}>×</button>
        </div>
        <div className="modal-b">{kids(body)}</div>
      </div>
    </div>
  );
}

function submissionOf(state: AppState, person: Person, activityId = "r-open"): { at: string; fileName?: string } | null {
  if (person.id === "seoyeon") return { at: "2026-11-09 09:14", fileName: "tm-note.pdf" };
  if (person.id === "minjun") return state.submissions[activityId] || null;
  return null;
}

function statusBadge(text: string, submitted: boolean): ReactNode {
  return <Badge tone={submitted ? "success" : "danger"}>{text}</Badge>;
}

function koreanWeek(n: number): string {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d: Date) => `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return `${fmt(start)} – ${fmt(end)}`;
}

function linkBtn(href: string, label: string, kind: ButtonKind = "secondary"): ReactNode {
  return <Link href={href} className={`btn btn-${kind}`}>{label}</Link>;
}

/** A screen the backend has nothing for, in a server course: no sample rows, only why and what is missing. */
function unavailable(title: string, body: string, ...actions: ReactNode[]): ReactNode {
  return (
    <div className="stack">
      <EmptyState title={title} body={body} actions={actions.length ? kids(actions) : undefined} />
    </div>
  );
}

export function renderProf(screen: Screen, ctx: ScreenCtx): ReactNode {
  const kind = screen.kind;
  if (kind === "pf-main") return <ProfessorMain ctx={ctx} />;
  if (kind === "pf-room") return room(screen, ctx);
  if (kind === "pf-form") return form(screen, ctx);
  if (kind === "pf-status") return status(ctx);
  if (kind === "pf-grade") return grade(ctx);
  if (kind === "pf-exam-status") return examStatus(ctx);
  if (kind === "pf-exam-grade") return examGrade(ctx);
  if (kind === "pf-bank") return bank(ctx);
  if (kind === "pf-attend") return attend(screen, ctx);
  if (kind === "pf-gradebook") return gradebook(ctx);
  if (kind === "pf-appeal") return appeal(ctx);
  if (kind === "pf-team") return teams(ctx);
  if (kind === "pf-monitor") return monitor(ctx);
  if (kind === "pf-ta") return ta(ctx);
  if (kind === "pf-courses") return courseList();
  if (kind === "pf-study") return studyRoom(ctx);
  if (kind === "pf-lecture") return lecturePreview(ctx);
  if (kind === "pf-submissions") return submissions(ctx);
  if (kind === "pf-quiz") return quizDetail(ctx);
  if (kind === "pf-pdf") return pdfPreview(ctx);
  if (kind === "pf-notice") return noticeBoard(ctx);
  if (kind === "pf-qna") return qnaBoard(ctx);
  if (kind === "pf-calendar") return calendarBoard(ctx);
  return <section data-area="professor"><EmptyState title="화면을 준비하지 못했습니다" body={screen.id} /></section>;
}

/** One course card on the professor home. `unread` > 0 adds the server's unread notice count. */
function courseCard(c: Course, unread?: number): ReactNode {
  const href = pf("pf_room_week", { kj: c.id });
  return (
    <article className="course-card">
      <Link className="course-card-main" href={href}>
        <div className={`cover ${c.gradient}`}><CoverImage course={c} /><b>{c.cardTitle || c.ko}</b><span>{c.code}</span></div>
        <div className="meta">
          {c.learners != null ? <div>{`${c.learners}명`}</div> : null}
          <div>{c.time}</div>
          {unread ? <div className="pf-unread">{`안 읽은 공지 ${unread}`}</div> : null}
        </div>
      </Link>
      <Link href={routeFor("professor", "study", { kj: c.id })} className="btn btn-primary course-study-entry" aria-label={`${c.cardTitle || c.ko} 학습실 입장`} resetScroll>학습실 입장</Link>
    </article>
  );
}

/** A sample course card (mock mode home and course list). */
function sampleCourseCard(c: Course): ReactNode {
  const href = pf("pf_room_week", { kj: c.id });
  return (
    <article key={c.id} className="course-card">
      <Link className="course-card-main" href={href}>
        <div className={`cover ${c.gradient}`}><CoverImage course={c} /><b>{c.cardTitle || c.ko}</b><span className="code">{c.code}</span></div>
        <div className="meta"><div>{`${c.learners}명`}</div><div>{c.time}</div></div>
      </Link>
      <Link href={routeFor("professor", "study", { kj: c.id })} className="btn btn-secondary course-study-entry" aria-label={`${c.cardTitle || c.ko} 학습실 입장`} resetScroll>학습실 입장</Link>
    </article>
  );
}

function ProfessorMain({ ctx }: { ctx: ScreenCtx }) {
  const mine = ["bi", "db", "sec"].map((id) => courseById(id));
  const openReports = activities.filter((a) => a.type === "report");
  const waiting = ctx.state.appeals.filter((a) => a.status === "대기");
  // Backend `ready`: course cards, the course count and latest notices come from GET /api/me/home.
  const remote = backendStatus() === "ready";
  const [count, setCount] = useState("");
  return (
    <section data-area="professor">
      <header className="pf-head">
        <div className="pf-title">
          <p className="pf-kicker">2026학년도 2학기</p>
          <h1>교수 홈</h1>
          <p className="pf-sub">{`${teacher.name} · ${teacher.dept}`}</p>
        </div>
        <div className="pf-actions">
          {linkBtn(pf("pf_bank"), "문제은행", "primary")}
          {linkBtn(pf("pf_contents"), "콘텐츠관리")}
          {linkBtn(routeFor("professor", "calendar"), "일정")}
          {linkBtn(pf("pf_report_grade", { kj: "bi" }), "채점")}
        </div>
      </header>
      <div className="stat-row">
        {stat("담당 과목", remote ? count : String(mine.length))}
        {stat("미채점 과제", String(openReports.length))}
        {stat("이의", String(waiting.length))}
        {stat("콘텐츠", String(ctx.state.contents.length))}
      </div>
      <h2 className="section-label">강의과목</h2>
      {remote ? serverProfessorHome(courseCard, setCount) : <div className="cards">{mine.map(sampleCourseCard)}</div>}
      <h2 className="section-label">확인할 일</h2>
      {openReports.length || waiting.length ? (
        <div>
          {openReports.map((a) => <ListRow key={`r-${a.id}`} href={pf("pf_report_grade", { kj: a.courseId || "bi" })} title={a.title} detail={`${typeLabel(a.type)} · ${a.week}주차`} meta="채점" />)}
          {waiting.map((a) => <ListRow key={`a-${a.id}`} href={pf("pf_appeal", { kj: "bi" })} title={`${a.who} · ${a.item}`} detail={a.body} meta="이의" />)}
        </div>
      ) : <EmptyState title="남은 할 일이 없습니다" body="채점과 이의신청이 생기면 여기에 모입니다." />}
      <h2 className="section-label">다가오는 일정</h2>
      <div>
        {events.filter((ev) => ev.activityId).slice(0, 6).map((ev, index) => {
          const activity = activities.find((item) => item.id === ev.activityId);
          return (
            <ListRow
              key={index}
              href={activity ? routeFor("professor", "activity", { activity, kj: activity.courseId }) : routeFor("professor", "calendar", { day: ev.date })}
              title={ev.label || ev.title}
              detail={ev.date}
              meta={activity ? typeLabel(activity.type) : "일정"}
            />
          );
        })}
      </div>
    </section>
  );
}

function pickWeek(n: number) {
  const state = getState();
  state.week = n || 1;
  commit();
}

function room(screen: Screen, ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  const mode = screen.mode || "week";
  const week = ctx.state.week || 1;
  const mine = activities.filter((a) => a.courseId === course.id);
  const due = mine.find((a) => a.due && a.status !== "done" && a.status !== "graded");
  const banner = (
    <div className="banner-course">
      <div>
        <div className="k">2026학년도 2학기</div>
        <h1>{course.ko}</h1>
        <div>{`${course.en} · ${course.code}`}</div>
      </div>
      {roleSwitch(course)}
    </div>
  );
  const modeTabs = (
    <Tabs items={[
      { label: "주별", href: pf("pf_room_week", { kj: course.id }), on: mode === "week" },
      { label: "수업활동", href: pf("pf_room_activity", { kj: course.id }), on: mode === "activity" },
    ]} />
  );
  // Backend: the server weeks and activities of a course on the server (areas/professor/room-server.tsx).
  if (serverRoom(course)) return <section data-area="professor">{banner}{modeTabs}{serverRoomView(mode, ctx, course, { stat, chipRow, finder })}</section>;
  const widgets = (
    <div className="stat-row">
      {course.learners != null ? stat("수강생", String(course.learners)) : null}
      {stat("활동", String(mine.length))}
      {stat("진도", `${course.progress}%`, <div className="bar" aria-hidden="true"><i style={{ width: `${course.progress}%` }} /></div>)}
      {stat("마감", dday(due && due.due) || "없음")}
    </div>
  );
  if (mode === "activity") {
    const type = param(ctx, "type");
    const shown = mine.filter((a) => !type || a.type === type).filter((a) => matchesQuery(ctx, `${a.title} ${typeLabel(a.type)}`));
    const chips = chipRow(ctx, "pf_room_activity", [["all", "전체", "all"], ...TYPES.map(([id, label]): [string, string, string] => [id, `${label} ${mine.filter((a) => a.type === id).length}`, id])], "type");
    return (
      <section data-area="professor">
        {banner}{modeTabs}{widgets}
        <div className="pf-toolbar">{chips}{finder(ctx, "활동 검색")}</div>
        {shown.length
          ? <div className="pf-activities">{shown.map((a) => activityCard(a, ctx))}</div>
          : <EmptyState title="해당하는 활동이 없습니다" body="다른 유형을 선택하거나 검색어를 지워 보세요." />}
        {activityDeleteDialog(ctx)}
      </section>
    );
  }
  const nums: ReactNode[] = [];
  for (let n = 1; n <= weekCount; n += 1) {
    const has = mine.some((a) => a.week === n);
    nums.push(
      <button key={n} type="button" className={`week-num${week === n ? " on" : ""}${has ? " done" : ""}`} aria-label={`${n}주차`} aria-pressed={week === n ? "true" : "false"} onClick={() => pickWeek(n)}>
        {String(n)}
      </button>,
    );
  }
  const items = mine.filter((a) => a.week === week);
  const author = ctx.state.role === "professor";
  return (
    <section data-area="professor">
      {banner}{modeTabs}{widgets}
      <div className="weekbar" role="group" aria-label="주차">{nums}</div>
      <div className="week-block">
        <div className="pf-week-head">
          <h2>{`${week}주차 · ${koreanWeek(week)}`}</h2>
          {author ? linkBtn(pf("pf_week_add", { kj: course.id, week: String(week) }), "활동 등록", "primary") : null}
        </div>
        {items.length
          ? <div className="pf-activities">{items.map((a) => activityCard(a, ctx))}</div>
          : <EmptyState title="이 주차에 등록된 활동이 없습니다" body="활동 등록에서 영상, Zoom, 자료, 글, 과제, 시험을 넣을 수 있습니다." />}
      </div>
      {activityDeleteDialog(ctx)}
    </section>
  );
}

function confirmDelete() {
  const state = getState();
  const dialog: Partial<PfDialog> = state.draft.pfDialog || {};
  if (dialog.kind === "delete-question") {
    const question = state.questions.find((item) => item.id === dialog.id);
    state.questions = state.questions.filter((item) => item.id !== dialog.id);
    if (question) {
      const bank = state.banks.find((item) => item.id === question.bankId);
      if (bank && bank.count > 0) bank.count -= 1;
    }
    state.toast = "문항을 삭제했습니다";
  } else if (dialog.kind === "delete-activity" && dialog.id) {
    const item = activities.find((row) => row.id === dialog.id);
    removeActivity(state, dialog.id);
    state.toast = item && isRemotePdfId(item.pdfId) ? "활동을 삭제했습니다. ASTRA 서버에 등록한 자료는 서버에 남아 있습니다." : "활동을 삭제했습니다";
    if (item?.fileId) deleteActivityFile(item.fileId).catch(() => {});
    if (item?.id) deleteActivityFile(item.id).catch(() => {});
    if (item?.pdfId) deleteCoursePdf(item.pdfId).catch(() => {});
  } else if (dialog.kind === "delete-bank") {
    const used = state.questions.some((item) => item.bankId === dialog.id);
    if (used) {
      state.toast = "문항이 남아 있으면 은행을 삭제할 수 없습니다";
      commit();
      return;
    }
    state.banks = state.banks.filter((item) => item.id !== dialog.id);
    state.toast = "문제은행을 삭제했습니다";
  }
  state.draft.pfDialog = null;
  commit();
}

function activityDeleteDialog(ctx: ScreenCtx): ReactNode {
  const dialog: PfDialog | null = ctx.state.draft.pfDialog;
  if (!dialog || dialog.kind !== "delete-activity") return null;
  const item = activities.find((row) => row.id === dialog.id);
  const name = item ? item.title : "이 활동";
  return pfDialog("활동 삭제",
    <p>{item && isRemotePdfId(item.pdfId)
      ? `「${name}」을 삭제합니다. 이 브라우저의 활동만 지워지고 ASTRA 서버에 등록한 자료는 남습니다. 삭제한 뒤에는 되돌릴 수 없습니다.`
      : `「${name}」을 삭제합니다. 이 브라우저에서만 지워지고, 삭제한 뒤에는 되돌릴 수 없습니다.`}</p>,
    <div className="pf-actions">
      <Button kind="secondary" onClick={dismiss}>취소</Button>
      <Button kind="danger" onClick={confirmDelete}>삭제</Button>
    </div>,
  );
}

function roleSwitch(course: Course): ReactNode {
  const href = routeFor("student", "classroom", { kj: course.id, special: course.kind === "special" });
  return <Link className="btn btn-secondary" href={href} roleSwitch="student" data-exit="role">학생 화면으로 보기</Link>;
}

function activityCard(a: Activity, ctx: ScreenCtx | null): ReactNode {
  const status = a.authored || a.attendStart || a.attendEnd ? activityPhase(a) : a.status;
  const [label, tone] = ACTIVITY_STATUS[status] || ACTIVITY_STATUS[a.status] || [status || "열림", "neutral"];
  const courseId = a.courseId || (ctx ? ctx.state.courseId : "");
  const href = routeFor("professor", "activity", { activity: a, kj: courseId });
  return (
    <div key={a.id} className="pf-activity" data-activity={a.id}>
      <Link className="pf-activity-main" href={href} data-row={a.id} data-activity={a.id}>
        <span className={`pill ${a.type}`}>{typeLabel(a.type)}</span>
        <span className="pf-activity-copy">
          <b>{a.title}</b>
          <span className="pf-meta">{`${a.week}주차${a.points ? ` · ${a.points}점` : ""}`}</span>
        </span>
        <Badge tone={tone}>{label}</Badge>
      </Link>
      {ctx && ctx.state.role === "professor" ? (
        <div className="pf-activity-actions">
          <Link href={pf("pf_week_add", { kj: courseId, edit: a.id, week: String(a.week || 1) })} className="btn btn-ghost" aria-label={`${a.title} 수정`}>수정</Link>
          <Button kind="ghost" aria-label={`${a.title} 삭제`} onClick={() => openDialog("delete-activity", a.id)}>삭제</Button>
        </div>
      ) : null}
    </div>
  );
}

function form(screen: Screen, ctx: ScreenCtx): ReactNode {
  const err = ctx.state.formError || {};
  const course = screen.course ? courseOf(ctx) : null;
  const editing = screen.form === "activity" ? activities.find((item) => item.id === param(ctx, "edit")) || null : null;
  // Backend mode: server 쪽지 to course members (areas/professor/messages-server.tsx); `anon` shows only the login strip.
  if (screen.form === "pmessage" && course && backendStatus() !== "off") {
    return frame("쪽지 발송", ctx, { course: true, kicker: course.ko, sub: "ASTRA 서버에서 이 과목 구성원을 찾아 쪽지를 보냅니다. 제목과 내용은 일반 텍스트로 보냅니다.", actions: [] }, serverMessageFields(ctx, course, { formSheet, span }));
  }
  type Entry = [string, string, ReactNode, ReactNode[]];
  const build: Record<string, () => Entry> = {
    activity: () => (course && serverRoom(course)
      ? ["활동 등록", "ASTRA 서버에 만들 수 있는 유형만 고릅니다. 고른 주차를 가지고 그 유형의 서버 등록 화면으로 갑니다.", serverActivityChooser(ctx, course), [
        linkBtn(pf("pf_room_week", { kj: course.id }), "주별로"),
      ]]
      : [editing ? "활동 수정" : "활동 등록", backendStatus() === "ready"
        ? "활동은 이 브라우저에 저장됩니다. 자료 유형의 PDF는 ASTRA 서버에 올려 강의자료로 등록합니다."
        : "영상, Zoom, 자료, 글, 과제, 시험은 이 브라우저에만 저장됩니다.", <ActivityForm key={editing ? editing.id : "new"} ctx={ctx} editing={editing} />, [
        linkBtn(pf("pf_room_week", { kj: course ? course.id : "" }), "주별로"),
      ]]),
    contents: () => ["영상 콘텐츠", serverVideoCourse(courseOf(ctx))
      ? "ASTRA 서버에 강의 영상을 올려 영상 강의로 등록하고, 변환 상태를 확인하고, 수정·삭제합니다."
      : "등록한 영상은 이 브라우저에만 남습니다.", contentFields(ctx, err), []],
    zoom: () => (serverRoom(courseOf(ctx))
      ? ["실시간 강의", "실시간 강의(Zoom)는 ASTRA 서버에 아직 없습니다.", unavailable("실시간 강의는 아직 ASTRA 서버에 없습니다", "서버에 회의 등록 기능이 생기면 여기에서 회의 주소와 시간을 등록합니다. 이 브라우저에만 남는 샘플 회의는 서버 과목에서 보이지 않습니다."), []]
      : ["실시간 강의", "회의 번호는 숫자 9자리에서 11자리입니다.", zoomFields(err, ctx), []]),
    notice: () => ["공지 작성", backendStatus() === "off"
      ? "수강생 강의실 공지에 샘플로 추가됩니다."
      : "ASTRA 서버의 강의실 공지로 등록합니다. 제목과 본문은 일반 텍스트로 저장됩니다.", noticeFields(err, ctx, course), []],
    material: () => ["자료 등록", "자료명과 파일을 함께 올립니다.", materialFields(course, ctx), []],
    report: () => (backendStatus() !== "off" && course
      ? ["과제 출제", "ASTRA 서버에 과제를 등록하고 고칩니다. 제출 구간은 서버 시각으로 정해지고, 설명은 일반 텍스트로 저장됩니다.", reportFields(err, ctx, course), [
        linkBtn(pf("pf_report_status", { kj: course.id }), "제출 현황"),
        linkBtn(pf("pf_report_grade", { kj: course.id }), "채점"),
      ]]
      : ["과제 출제", "마감은 시작 이후여야 하고 배점은 1부터 100입니다.", reportFields(err, ctx, null), [
        linkBtn(pf("pf_report_status", { kj: "bi" }), "제출 현황"),
        linkBtn(pf("pf_report_grade", { kj: "bi" }), "채점"),
      ]]),
    exam: () => (backendStatus() !== "off" && course
      ? ["시험 출제", "ASTRA 서버에 시험을 등록하고 고칩니다. 응시 구간과 응시 마감은 서버 시각으로 정해지고, 문항과 안내는 일반 텍스트로 저장됩니다.", examFields(ctx, err, course), [
        linkBtn(pf("pf_exam_status", { kj: course.id }), "응시 현황"),
        linkBtn(pf("pf_exam_grade", { kj: course.id }), "채점"),
      ]]
      : ["시험 출제", "문제은행에서 문항을 하나 이상 골랐는지 확인합니다.", examFields(ctx, err, null), [
        linkBtn(pf("pf_exam_status", { kj: "bi" }), "응시 현황"),
        linkBtn(pf("pf_bank"), "문제은행"),
      ]]),
    discuss: () => (backendStatus() !== "off" && course
      ? ["토론·설문", "설문은 ASTRA 서버에 만들고 결과를 봅니다. 토론은 아직 서버에 없어 이 브라우저에만 저장됩니다.", (
        <div className="stack">
          {serverSurveyFields(ctx, course, { formSheet, span, linkBtn })}
          {discussFields(err, true)}
        </div>
      ), []]
      : ["토론·설문", "토론 또는 설문 안내를 만듭니다.", discussFields(err, false), []]),
    pmessage: () => ["쪽지 발송", "수강생 전체 또는 한 명에게 샘플 쪽지를 남깁니다.", messageFields(err), []],
  };
  const maker = screen.form ? build[screen.form] : undefined;
  const [title, sub, body, actions]: Entry = maker ? maker() : ["작성", "", <p />, []];
  return frame(title, ctx, { course: Boolean(course), kicker: course ? course.ko : "교수", sub, actions }, body);
}

function formSheet(title: string, ...nodes: ReactNode[]): ReactNode {
  return (
    <div className="sheet">
      <h2>{title}</h2>
      <div className="pf-form">{kids(nodes)}</div>
    </div>
  );
}

/** Full-width row of a `.pf-form` grid: adds `span-2` to an element, or wraps a component in one. */
function span(node: ReactNode): ReactNode {
  if (isValidElement<{ className?: string }>(node) && typeof node.type === "string") {
    return cloneElement(node, { className: [node.props.className, "span-2"].filter(Boolean).join(" ") });
  }
  return <div className="span-2">{node}</div>;
}

function defaultClip(courseId: string): string {
  if (LECTURES[courseId]) return courseId;
  const row = Object.values(LECTURES).find((item) => item.courseId === courseId);
  return row ? row.id : "bi";
}

function validHttpUrl(value: unknown): boolean {
  try {
    const url = new URL(String(value || "").trim());
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function ActivityForm({ ctx, editing }: { ctx: ScreenCtx; editing: Activity | null }) {
  const err = ctx.state.formError || {};
  const [current, setCurrent] = useState<ActivityType>(editing?.type || "video");
  const [busy, setBusy] = useState(false);
  const videoRef = useRef<HTMLInputElement>(null);
  const materialRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const course = courseOf(ctx);
  const weekValue = editing ? String(editing.week || 1) : (param(ctx, "week") || String(ctx.state.week || 9));
  const clip = editing?.clipId || defaultClip(course.id);
  const of = (type: ActivityType, value: string | undefined) => (editing && editing.type === type ? value || "" : "");
  const panel = (type: ActivityType, ...nodes: ReactNode[]) => (
    <div className="pf-type-panel span-2" data-type-panel={type} hidden={type !== current}>{kids(nodes)}</div>
  );

  const insert = (cmd: "bold" | "list" | "link") => {
    const box = bodyRef.current;
    if (!box) return;
    const start = box.selectionStart || 0;
    const end = box.selectionEnd || 0;
    const selected = box.value.slice(start, end) || (cmd === "link" ? "안내" : cmd === "list" ? "항목" : "굵게");
    let next = selected;
    if (cmd === "bold") next = `**${selected}**`;
    else if (cmd === "list") next = selected.split("\n").map((line) => (line.startsWith("- ") ? line : `- ${line}`)).join("\n");
    else next = `[${selected}]()`;
    box.setRangeText(next, start, end, "end");
    box.focus();
  };

  const submit = async () => {
    if (busy) return;
    const state = getState();
    const data = readForm();
    const type = (text(data.type) || "video") as ActivityType;
    const title = text(data.title).trim();
    const errors: Record<string, string> = {};
    if (!title) errors.title = "제목을 입력하세요";
    else if (title.length < 2) errors.title = "제목은 두 글자 이상이어야 합니다";
    const week = Number(data.week);
    if (!Number.isInteger(week) || week < 1 || week > 14) errors.week = "주차는 1부터 14입니다";
    if (!TYPES.some(([id]) => id === type)) errors.type = "유형을 선택하세요";
    // Backend mode: a video lecture is registered by its own server sheet in the video panel.
    if (type === "video" && serverVideoCourse(courseById(state.courseId))) {
      toast("영상 강의는 위의 ASTRA 서버에 등록 버튼으로 등록합니다");
      return;
    }
    const videoFile = videoRef.current?.files?.[0] || null;
    const materialFile = materialRef.current?.files?.[0] || null;
    if (type === "video") {
      if (!data.attendStart) errors.attendStart = "출석 시작을 입력하세요";
      if (!data.attendEnd) errors.attendEnd = "출석 종료를 입력하세요";
      else if (data.attendStart && !endsAfter(text(data.attendStart), text(data.attendEnd))) errors.attendEnd = "종료는 시작 이후여야 합니다";
    } else if (type === "zoom") {
      if (!validHttpUrl(data.meetingUrl)) errors.meetingUrl = "http 또는 https 회의 링크를 입력하세요";
      if (!data.zoomStart) errors.zoomStart = "시작 시각을 입력하세요";
      if (!data.zoomEnd) errors.zoomEnd = "종료 시각을 입력하세요";
      else if (data.zoomStart && !endsAfter(text(data.zoomStart), text(data.zoomEnd))) errors.zoomEnd = "종료는 시작 이후여야 합니다";
    } else if (type === "material") {
      if (!materialFile && !(editing && editing.type === "material" && (editing.fileId || editing.pdfId))) errors.materialFile = "파일을 선택하세요";
    } else if (type === "post") {
      if (richPlain(data.body || "").length < 2) errors.body = "본문을 두 글자 이상 입력하세요";
    } else if (type === "report") {
      if (!data.reportStart) errors.reportStart = "시작을 입력하세요";
      if (!data.reportDue) errors.reportDue = "마감을 입력하세요";
      else if (data.reportStart && !endsAfter(text(data.reportStart), text(data.reportDue))) errors.reportDue = "마감은 시작 이후여야 합니다";
      const points = Number(data.points);
      if (!Number.isInteger(points) || points < 1 || points > 100) errors.points = "배점은 1부터 100입니다";
    } else if (type === "test") {
      if (!data.testStart) errors.testStart = "시작을 입력하세요";
      if (!data.testDue) errors.testDue = "종료를 입력하세요";
      else if (data.testStart && !endsAfter(text(data.testStart), text(data.testDue))) errors.testDue = "종료는 시작 이후여야 합니다";
      if (text(data.minutes).trim() !== "") {
        const minutes = Number(data.minutes);
        if (!Number.isInteger(minutes) || minutes < 0 || minutes > 300) errors.minutes = "제한 시간은 0부터 300분입니다";
      }
    } else if (type === "survey") {
      if (!data.surveyDue) errors.surveyDue = "마감을 입력하세요";
    }
    if (Object.keys(errors).length) {
      showErrors(errors);
      return;
    }

    const id = editing ? editing.id : `act-${Date.now()}`;
    const courseId = state.courseId || (editing && editing.courseId) || "bi";
    const item: Activity = { id, courseId, week, type, title, authored: true, body: "", status: "" };
    setBusy(true);
    try {
      if (type === "video") {
        item.body = plainText(data.videoBody);
        item.attendStart = text(data.attendStart);
        item.attendEnd = text(data.attendEnd);
        item.start = text(data.attendStart);
        item.due = text(data.attendEnd);
        item.clipId = text(data.clip) || "bi";
        if (videoFile) {
          await putActivityFile(id, videoFile);
          item.fileId = id;
          item.fileName = videoFile.name;
        } else if (editing && editing.type === "video") {
          item.fileId = editing.fileId || "";
          item.fileName = editing.fileName || "";
        }
      } else if (type === "zoom") {
        item.body = plainText(data.zoomBody);
        item.meetingUrl = new URL(text(data.meetingUrl).trim()).href;
        item.start = text(data.zoomStart);
        item.end = text(data.zoomEnd);
        item.when = `${text(data.zoomStart)} – ${text(data.zoomEnd)}`;
      } else if (type === "material") {
        item.body = plainText(data.materialBody);
        if (materialFile) {
          const isPdf = materialFile.type === "application/pdf" || /\.pdf$/i.test(materialFile.name || "");
          if (isPdf) {
            const pdf = await saveCoursePdf({ file: materialFile, courseId, title, weekNo: week });
            if (editing && editing.pdfId && editing.pdfId !== pdf.id) deleteCoursePdf(editing.pdfId).catch(() => {});
            if (editing && editing.fileId) deleteActivityFile(editing.fileId).catch(() => {});
            item.pdfId = pdf.id;
            item.fileName = materialFile.name;
            item.fileId = "";
          } else {
            await putActivityFile(id, materialFile);
            if (editing && editing.pdfId) deleteCoursePdf(editing.pdfId).catch(() => {});
            item.fileId = id;
            item.fileName = materialFile.name;
            item.pdfId = "";
          }
        } else if (editing && editing.type === "material") {
          item.pdfId = editing.pdfId || "";
          item.fileId = editing.fileId || "";
          item.fileName = editing.fileName || "";
        }
      } else if (type === "post") {
        item.body = sanitizeRichSource(data.body || "");
      } else if (type === "report") {
        item.body = plainText(data.reportBody);
        item.start = text(data.reportStart);
        item.due = text(data.reportDue);
        item.points = Number(data.points);
      } else if (type === "test") {
        item.body = plainText(data.testBody);
        item.start = text(data.testStart);
        item.due = text(data.testDue);
        if (text(data.minutes).trim() !== "") item.minutes = Number(data.minutes);
      } else if (type === "survey") {
        item.body = plainText(data.surveyBody);
        item.due = text(data.surveyDue);
      }
      const phase = activityPhase(item);
      if (type === "report") item.status = phase === "expired" ? "expired" : "missing";
      else if (type === "test") item.status = phase === "expired" ? "expired" : phase === "open" ? "scheduled" : "ongoing";
      else item.status = phase;
      upsertActivity(state, item);
      state.week = week;
      state.courseId = courseId;
      state.formError = {};
      state.toast = editing ? "활동을 수정했습니다" : "활동을 등록했습니다";
      save();
      navigate(`/ilos/cls/pf/submain/submain_form.acl?mode=week&kj=${encodeURIComponent(courseId)}&as=pf`);
    } catch (error) {
      setBusy(false);
      const message = (error instanceof Error && error.message) || "활동을 저장하지 못했습니다";
      const key = type === "material" ? "materialFile" : type === "video" ? "videoFile" : "title";
      showErrors({ [key]: message });
    }
  };

  return (
    <div className="sheet">
      <h2>{editing ? "활동 수정" : "활동 정보"}</h2>
      <div className="pf-form pf-activity-form" data-edit={editing ? editing.id : ""}>
        <Field label="제목" error={err.title}><input data-name="title" defaultValue={editing ? editing.title : ""} /></Field>
        <Field label="주차" error={err.week}><input data-name="week" type="number" min="1" max="14" defaultValue={weekValue} /></Field>
        <div className="pf-types span-2" role="radiogroup" aria-label="활동 유형">
          <input type="hidden" data-name="type" value={current} />
          {TYPES.map(([value, label]) => (
            <button key={value} type="button" className={`chip ${value}${value === current ? " on" : ""}`} aria-pressed={value === current ? "true" : "false"} onClick={() => setCurrent(value)}>{label}</button>
          ))}
        </div>
        {err.type ? <p className="err span-2">{err.type}</p> : null}
        {serverVideoCourse(course) ? panel("video", serverLectureCreate(course)) : panel("video",
          <Field label="설명" error={err.videoBody}><textarea data-name="videoBody" rows={4} defaultValue={of("video", editing?.body)} /></Field>,
          <Field label="샘플 영상" error={err.clip}>
            <select data-name="clip" defaultValue={clip}>
              {Object.values(LECTURES).map((row) => <option key={row.id} value={row.id}>{`${row.course} · ${row.week}`}</option>)}
            </select>
          </Field>,
          <Field label="영상 파일" error={err.videoFile}><input ref={videoRef} type="file" data-name="videoFile" accept="video/*" /></Field>,
          <Field label="출석 시작" error={err.attendStart}><input type="datetime-local" data-name="attendStart" defaultValue={of("video", editing?.attendStart)} /></Field>,
          <Field label="출석 종료" error={err.attendEnd}><input type="datetime-local" data-name="attendEnd" defaultValue={of("video", editing?.attendEnd)} /></Field>,
        )}
        {panel("zoom",
          <Field label="회의 링크" error={err.meetingUrl}><input type="url" data-name="meetingUrl" placeholder="회의 주소" defaultValue={of("zoom", editing?.meetingUrl)} /></Field>,
          <Field label="시작" error={err.zoomStart}><input type="datetime-local" data-name="zoomStart" defaultValue={of("zoom", editing?.start)} /></Field>,
          <Field label="종료" error={err.zoomEnd}><input type="datetime-local" data-name="zoomEnd" defaultValue={of("zoom", editing?.end)} /></Field>,
          <Field label="설명" error={err.zoomBody}><textarea data-name="zoomBody" rows={4} defaultValue={of("zoom", editing?.body)} /></Field>,
        )}
        {panel("material",
          <Field label="설명" error={err.materialBody}><textarea data-name="materialBody" rows={4} defaultValue={of("material", editing?.body)} /></Field>,
          <Field label="파일" error={err.materialFile}><input ref={materialRef} type="file" data-name="materialFile" /></Field>,
          <p className="pf-sub">{editing && editing.fileName ? `현재 파일 ${editing.fileName}. PDF는 학습실 뷰어에서 열리고, 다른 파일은 다운로드됩니다.` : "PDF, PPT 또는 다른 파일을 올립니다. PDF는 학습실 뷰어에서 열리고, 다른 파일은 다운로드됩니다."}</p>,
        )}
        {panel("post",
          <div className="pf-editor">
            <div className="pf-actions">
              <Button kind="ghost" onClick={() => insert("bold")}>굵게</Button>
              <Button kind="ghost" onClick={() => insert("list")}>목록</Button>
              <Button kind="ghost" onClick={() => insert("link")}>링크</Button>
            </div>
            <Field label="본문" error={err.body}><textarea ref={bodyRef} data-name="body" rows={8} defaultValue={of("post", editing?.body)} /></Field>
          </div>,
        )}
        {panel("report",
          <Field label="설명" error={err.reportBody}><textarea data-name="reportBody" rows={4} defaultValue={of("report", editing?.body)} /></Field>,
          <Field label="배점" error={err.points}><input type="number" data-name="points" min="1" max="100" defaultValue={editing && editing.points ? String(editing.points) : "20"} /></Field>,
          <Field label="시작" error={err.reportStart}><input type="datetime-local" data-name="reportStart" defaultValue={of("report", editing?.start)} /></Field>,
          <Field label="마감" error={err.reportDue}><input type="datetime-local" data-name="reportDue" defaultValue={of("report", editing?.due)} /></Field>,
        )}
        {panel("test",
          <Field label="설명" error={err.testBody}><textarea data-name="testBody" rows={4} defaultValue={of("test", editing?.body)} /></Field>,
          <Field label="시작" error={err.testStart}><input type="datetime-local" data-name="testStart" defaultValue={of("test", editing?.start)} /></Field>,
          <Field label="종료" error={err.testDue}><input type="datetime-local" data-name="testDue" defaultValue={of("test", editing?.due)} /></Field>,
          <Field label="제한 시간(분)" error={err.minutes}><input type="number" data-name="minutes" min="0" max="300" defaultValue={editing && editing.type === "test" && editing.minutes != null ? String(editing.minutes) : ""} /></Field>,
        )}
        {panel("survey",
          <Field label="설명" error={err.surveyBody}><textarea data-name="surveyBody" rows={4} defaultValue={of("survey", editing?.body)} /></Field>,
          <Field label="마감" error={err.surveyDue}><input type="datetime-local" data-name="surveyDue" defaultValue={of("survey", editing?.due)} /></Field>,
        )}
        <div className="pf-actions span-2">
          <Button kind="primary" disabled={busy} onClick={() => void submit()}>{editing ? "저장" : "등록"}</Button>
        </div>
      </div>
    </div>
  );
}

/** Sample forms (콘텐츠, Zoom, 공지, 과제, 시험, 토론, 쪽지) saved into the browser state. */
function saveProfessor(form: string) {
  const data = readForm();
  const state = getState();
  const errors: Record<string, string> = {};
  const title = text(data.title).trim();
  const body = text(data.body).trim();
  if (form === "contents") {
    if (!title) errors.title = "제목을 입력하세요";
    if (!data.file) errors.file = "파일을 선택하세요";
    if (Object.keys(errors).length) return showErrors(errors);
    state.contents.unshift({ id: `c-${Date.now()}`, title, minutes: 10, at: stamp().slice(0, 10) });
  } else if (form === "zoom") {
    if (!title) errors.title = "제목을 입력하세요";
    if (!data.start) errors.start = "시작 시각을 입력하세요";
    const meeting = text(data.meeting).replace(/\s/g, "");
    if (!/^\d{9,11}$/.test(meeting)) errors.meeting = "회의 번호는 숫자 9자리에서 11자리입니다";
    if (Object.keys(errors).length) return showErrors(errors);
    zoomsOf(state).unshift({ title, meeting, start: text(data.start), week: text(data.week) });
  } else if (form === "notice") {
    if (!title) errors.title = "제목을 입력하세요";
    if (!body) errors.body = "본문을 입력하세요";
    if (Object.keys(errors).length) return showErrors(errors);
    state.noticesExtra.unshift({ id: `n-${Date.now()}`, scope: state.courseId, title, body, date: stamp().slice(0, 10), author: "박은주", audience: "all" });
  } else if (form === "report") {
    const start = text(data.start);
    const due = text(data.due);
    if (!title) errors.title = "제목을 입력하세요";
    if (!start || !due) errors.due = "시작과 마감을 입력하세요";
    else if (new Date(due) <= new Date(start)) errors.due = "마감은 시작 이후여야 합니다";
    const points = Number(data.points);
    if (!Number.isInteger(points) || points < 1 || points > 100) errors.points = "배점은 1부터 100입니다";
    if (Object.keys(errors).length) return showErrors(errors);
    state.madeAssignments.unshift({ title, start, due, points, late: Boolean(data.late) });
    activities.push({ id: `r-${Date.now()}`, courseId: state.courseId, week: 9, type: "report", title, status: "missing", start, due, points, body: "교수 화면에서 출제한 샘플 과제입니다." });
  } else if (form === "exam") {
    if (!title) errors.title = "시험명을 입력하세요";
    const minutes = Number(data.minutes);
    if (!Number.isInteger(minutes) || minutes < 5) errors.minutes = "제한 시간은 5분 이상입니다";
    const ids = Array.isArray(data.qid) ? data.qid : [];
    if (!ids.length) errors.questions = "문항을 하나 이상 선택하세요";
    if (Object.keys(errors).length) return showErrors(errors);
    state.madeExams.unshift({ title, minutes, questions: ids });
  } else if (form === "discuss") {
    if (!title) errors.title = "제목을 입력하세요";
    if (!body) errors.body = "안내를 입력하세요";
    if (Object.keys(errors).length) return showErrors(errors);
    postsOf(state).unshift({ id: `d-${Date.now()}`, board: text(data.kind), title, body, author: "박은주", at: stamp() });
  } else if (form === "pmessage") {
    if (!title) errors.title = "제목을 입력하세요";
    if (!body) errors.body = "내용을 입력하세요";
    if (Object.keys(errors).length) return showErrors(errors);
    state.messages.unshift({ id: `msg-${Date.now()}`, folder: "sent", from: "박은주", to: data.to === "all" ? "수강생 전체" : text(data.to), title, body, at: stamp(), read: true });
  }
  state.toast = "저장했습니다";
  commit();
}

function saveButton(label: string, form: string): ReactNode {
  return <div className="pf-actions span-2"><Button kind="primary" onClick={() => saveProfessor(form)}>{label}</Button></div>;
}

function contentFields(ctx: ScreenCtx, err: Record<string, string>): ReactNode {
  // Backend mode: server video lectures (areas/professor/lectures-server.tsx).
  if (serverVideoCourse(courseOf(ctx))) return serverLectureManager(courseOf(ctx), { weekNo: ctx.state.week, open: param(ctx, "open") });
  const records = ctx.state.contents.filter((c) => matchesQuery(ctx, c.title)).map((c) => ({
    sort: { title: c.title, minutes: c.minutes, at: c.at },
    cells: [c.title, `${c.minutes}분`, c.at],
  }));
  return (
    <div className="stack">
      <div className="pf-toolbar">{finder(ctx, "콘텐츠 검색")}</div>
      {records.length
        ? dataTable("contents", "영상 콘텐츠", [
          { key: "title", label: "제목", sort: true },
          { key: "minutes", label: "길이", sort: true },
          { key: "at", label: "등록", sort: true },
        ], records, ctx)
        : <EmptyState title="해당하는 콘텐츠가 없습니다" body="아래에서 새 영상을 등록할 수 있습니다." />}
      {formSheet("새 영상",
        span(<Field label="제목" error={err.title}><input data-name="title" /></Field>),
        span(<Field label="파일" error={err.file}><input type="file" data-name="file" /></Field>),
        saveButton("콘텐츠 추가", "contents"),
      )}
    </div>
  );
}

function zoomFields(err: Record<string, string>, ctx: ScreenCtx): ReactNode {
  const records = zoomsOf(ctx.state).filter((z) => matchesQuery(ctx, z.title)).map((z) => ({
    sort: { title: z.title, meeting: z.meeting },
    cells: [z.title, z.meeting],
  }));
  return (
    <div className="stack">
      {formSheet("회의 정보",
        span(<Field label="제목" error={err.title}><input data-name="title" /></Field>),
        <Field label="주차" error={err.week}><input data-name="week" type="number" min="1" max="14" /></Field>,
        <Field label="시작" error={err.start}><input data-name="start" type="datetime-local" /></Field>,
        span(<Field label="회의 번호" error={err.meeting}><input data-name="meeting" placeholder="숫자 9–11자리" /></Field>),
        saveButton("등록", "zoom"),
      )}
      {records.length ? dataTable("zooms", "등록된 실시간 강의", [
        { key: "title", label: "제목", sort: true },
        { key: "meeting", label: "번호", sort: true },
      ], records, ctx) : null}
    </div>
  );
}

function noticeFields(err: Record<string, string>, ctx: ScreenCtx, course: Course | null): ReactNode {
  // Backend mode: server form and list (areas/professor/notices-server.tsx); `anon` shows only the login strip.
  if (backendStatus() !== "off" && course) return serverNoticeFields(ctx, course, { formSheet, span, pf, linkBtn });
  return formSheet("공지",
    span(<Field label="제목" error={err.title}><input data-name="title" /></Field>),
    span(<Field label="본문" error={err.body}><textarea data-name="body" /></Field>),
    <label className="choice pf-check span-2"><input type="checkbox" data-name="pin" /><span>상단 고정</span></label>,
    saveButton("공지 저장", "notice"),
  );
}

// Materials registered from this form: a successful create bumps it, and the new key gives an empty form (as the notice,
// assignment and survey create forms do), so a second press cannot register the same title and file again.
let materialsCreated = 0;

function materialFields(course: Course | null, ctx: ScreenCtx): ReactNode {
  const status = backendStatus();
  const sheet = <MaterialSheet key={`${course ? course.id : ""}:${materialsCreated}`} course={course} ctx={ctx} />;
  // Mock mode keeps the original single form.
  if (status === "off" || !course) return sheet;
  // Backend mode: the course's external AI consent sits above the server materials (llm-consent-server.tsx).
  return (
    <div className="stack">
      {sheet}
      {status === "ready" ? llmConsentSection(course) : null}
      {status === "ready" ? serverMaterialList(course, summaryJobPanel, { open: param(ctx, "open") }) : null}
    </div>
  );
}

function MaterialSheet({ course, ctx }: { course: Course | null; ctx: ScreenCtx }) {
  const err = ctx.state.formError || {};
  const status = backendStatus();
  const [busy, setBusy] = useState("");
  const [line, setLine] = useState("");
  const live = useRef(true);
  // The busy state only disables the button after React repaints; this blocks a second press in the same moment.
  const sending = useRef(false);
  const titleRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const guidance = status === "ready"
    ? "PDF는 ASTRA 서버에 업로드되어 이 과목의 강의자료로 등록됩니다(최대 50MB). 등록한 PDF에서 쪽별 요약을 만들면 쪽 이미지와 텍스트가 서버에 설정된 LLM 제공자로 전송됩니다. 다른 형식은 파일명만 이 브라우저에 등록됩니다."
    : "PDF는 등록 시 실제 페이지와 텍스트를 분석해 이 과목 학습실에 저장합니다. 원본과 분석 결과는 이 브라우저에만 남으며 학교 서버나 다른 기기로 전송되지 않습니다. 스캔 PDF는 OCR 없이 텍스트를 추출할 수 없습니다. 다른 형식은 파일명만 등록됩니다.";

  const submit = async () => {
    if (busy || sending.current) return;
    const file = fileRef.current?.files?.[0] || null;
    const title = (titleRef.current?.value || "").trim();
    const state = getState();
    if (registrationPicked(state.courseId)) {
      sending.current = true;
      setBusy("서버에 등록하는 중…");
      try {
        const created = await saveRegistrationFromFilebox({ title, file }, (say: string) => {
          if (live.current) setLine(say);
        });
        if (created) {
          materialsCreated += 1;
          commit();
        }
      } finally {
        sending.current = false;
        if (live.current) setBusy("");
      }
      return;
    }
    const errors: Record<string, string> = {};
    if (!title) errors.title = "자료명을 입력하세요";
    if (!file) errors.file = "파일을 선택하세요";
    if (!file || Object.keys(errors).length) {
      setLine(Object.values(errors).join(". "));
      showErrors(errors);
      return;
    }
    const courseId = state.courseId;
    const isPdf = file.type === "application/pdf" || /\.pdf$/i.test(file.name);
    const remote = isPdf && backendStatus() === "ready";
    sending.current = true;
    setBusy(remote ? "서버에 올리는 중…" : isPdf ? "PDF 분석 중…" : "등록 중…");
    setLine(remote
      ? "PDF를 ASTRA 서버에 올리고 파일 검사가 끝나면 자료로 등록합니다."
      : isPdf ? "PDF의 페이지와 텍스트를 분석하고 이 브라우저에 저장하고 있습니다." : "자료를 등록하고 있습니다.");
    try {
      const pdf: CoursePdfRecord | null = isPdf ? await saveCoursePdf({ file, courseId, title, weekNo: state.week || 1 }) : null;
      // A server registration is listed from the server; only a browser-local material is kept in the sample state.
      if (!pdf?.remote) {
        state.materialsExtra.unshift(pdf
          ? { id: pdf.id, pdfId: pdf.id, courseId, title, file: file.name }
          : { title, file: file.name });
      }
      state.toast = pdf?.remote
        ? "PDF를 ASTRA 서버에 자료로 등록했습니다. 아래 목록에서 쪽별 요약을 만들 수 있습니다."
        : pdf?.status === "no-text"
          ? "PDF를 저장했습니다. 추출할 텍스트가 없어 학습실에서 원본을 확인할 수 있습니다."
          : pdf ? "PDF를 분석해 과목 학습실에 저장했습니다" : "저장했습니다";
      // The server material list under the form reads the new material on this render.
      if (pdf?.remote) forgetMaterialList(pdf.backendCourseId);
      materialsCreated += 1;
      commit();
    } catch (error) {
      const message = (error instanceof Error && error.message) || "자료를 등록하지 못했습니다. 파일을 확인한 뒤 다시 시도하세요.";
      if (live.current) {
        setLine(message);
        showErrors({ file: message });
      } else toast(message);
    } finally {
      sending.current = false;
      if (live.current) setBusy("");
    }
  };

  return (
    <div className="sheet" aria-busy={busy ? "true" : undefined}>
      <h2>자료</h2>
      <div className="pf-form">
        {span(<Field label="자료명" error={err.title}><input ref={titleRef} data-name="title" disabled={Boolean(busy)} /></Field>)}
        {span(<Field label="파일" error={err.file}><input ref={fileRef} type="file" data-name="file" aria-describedby="material-pdf-guidance material-pdf-status" disabled={Boolean(busy)} /></Field>)}
        {status === "ready" && course ? registrationAttach(course) : null}
        <p id="material-pdf-guidance" className="pf-sub span-2">{guidance}</p>
        <p id="material-pdf-status" className="pf-sub span-2" data-material-status="" role="status" aria-live="polite">{line}</p>
        {status === "ready" && course ? <p className="pf-sub span-2">{`등록할 주차: ${ctx.state.week || 1}주차. 강의실 주별 보기나 활동 등록에서 주차를 고른 뒤 오면 그 주차에 등록합니다.`}</p> : null}
        <div className="pf-actions span-2"><Button kind="primary" disabled={Boolean(busy)} onClick={() => void submit()}>{busy || "등록"}</Button></div>
      </div>
    </div>
  );
}

function sizeLabel(bytes: number | undefined): string {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return "";
  return n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`;
}

const JOB_STATE: Record<string, string> = {
  queued: "대기 중",
  running: "생성 중",
  completed: "후보 준비됨",
  failed: "실패",
  cancelled: "취소됨",
  interrupted: "중단됨",
};
const JOB_STAGE: Record<string, string> = { rendition: "쪽 이미지 준비", pages: "쪽별 설명", story: "흐름 정리", validate: "검증" };
const POLL_MS = 4000;
const publishedAt = wallFormat("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

/** Summary job controls for one server PDF: create, live progress, cancel, retry, preview, publish. */
function summaryJobPanel(record: RemotePdfRecord): ReactNode {
  return <SummaryJobPanel record={record} />;
}

function SummaryJobPanel({ record }: { record: RemotePdfRecord }) {
  const [loaded, setLoaded] = useState(false);
  const [job, setJob] = useState<SummaryJob | null>(null);
  const [result, setResult] = useState<ServerSummary | null>(null);
  const [published, setPublished] = useState<ServerSummary | null>(null);
  // The job published from this panel. The published view has no job id, so a panel opened later recognises the
  // published job by its pack: the server publishes the job's candidate unchanged (same question, story and slides).
  const [publishedJobId, setPublishedJobId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ReactNode>(null);
  const [poll, setPoll] = useState(0);
  const live = useRef(true);

  const adopt = async (next: SummaryJob | null) => {
    const candidate = next && next.state === "completed" ? await getSummaryResult(record, next.id) : null;
    if (!live.current) return;
    setJob(next);
    setResult(candidate);
    setPoll((n) => n + 1);
  };

  useEffect(() => {
    live.current = true;
    Promise.all([listSummaryJobs(record), getPublishedSummary(record)]).then(async ([jobs, current]) => {
      if (!live.current) return;
      setPublished(current);
      const latest = (Array.isArray(jobs) ? jobs : []).slice().sort((a, b) => b.id - a.id)[0] || null;
      await adopt(latest);
    }).catch((failure: unknown) => {
      if (live.current) setError(summaryMessage(failure));
    }).finally(() => {
      if (live.current) setLoaded(true);
    });
    return () => {
      live.current = false;
    };
  }, [record]);

  useEffect(() => {
    if (!job || (job.state !== "queued" && job.state !== "running")) return;
    const timer = setTimeout(() => {
      getSummaryJob(record, job.id).then(adopt, (failure: unknown) => {
        if (!live.current) return;
        setError(summaryMessage(failure));
        setPoll((n) => n + 1);
      });
    }, POLL_MS);
    return () => clearTimeout(timer);
  }, [job, poll, record]);

  const perform = async (run: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await run();
    } catch (failure) {
      // 409 `llm_consent_required` / 503 `llm_disabled`: lead to the consent section instead of a retry.
      // The lead states the reason itself; putting `summaryMessage` in front of it said it twice.
      const lead = consentLead(failure);
      if (live.current) setError(lead || summaryMessage(failure));
    } finally {
      if (live.current) setBusy(false);
    }
  };

  const packOf = (view: ServerSummary) => JSON.stringify([view.question ?? null, view.story ?? null, view.slides ?? null]);
  const isPublished = Boolean(job && ((publishedJobId === job.id) || (published && result && packOf(published) === packOf(result))));
  const active = Boolean(job && (job.state === "queued" || job.state === "running"));

  let statusText = "요약 상태를 불러오는 중입니다.";
  if (loaded) {
    const lines: string[] = [];
    if (published) {
      const at = publishedAt.format(published.publishedAt);
      lines.push(`게시된 요약 있음${at ? ` (${at})` : ""}`);
    }
    if (!job) lines.push("아직 만든 요약이 없습니다.");
    else {
      const stage = job.state === "running" && job.stage && JOB_STAGE[job.stage] ? ` · ${JOB_STAGE[job.stage]}` : "";
      const pages = job.pagesTotal ? ` · ${job.pagesDone || 0}/${job.pagesTotal}쪽` : "";
      const reason = job.state === "failed" && (job.errorMessage || job.errorCode) ? ` · ${job.errorMessage || job.errorCode}` : "";
      lines.push(`최근 요약 작업: ${JOB_STATE[job.state] || job.state}${stage}${pages}${job.cancelRequested && job.state !== "cancelled" ? " · 취소 요청됨" : ""}${reason}`);
    }
    statusText = lines.join(" · ");
  }

  const start = () => startSummaryJob(record).then(adopt);
  const action = (label: string, kind: ButtonKind, run: () => Promise<unknown>, disabled = false) => (
    <Button key={label} kind={kind} disabled={busy || disabled} onClick={() => void perform(run)}>{label}</Button>
  );
  const buttons: ReactNode[] = [];
  if (loaded) {
    if (!job) buttons.push(action("요약 생성", "primary", start));
    else if (active) buttons.push(action("취소", "secondary", () => cancelSummaryJob(record, job.id).then(adopt), Boolean(job.cancelRequested)));
    else if (job.state === "completed" && !result) {
      // The job finished but its candidate could not be read: read it again before anything can be published.
      buttons.push(action("후보 다시 불러오기", "primary", async () => {
        const next = await getSummaryResult(record, job.id);
        if (live.current) setResult(next);
      }));
      buttons.push(action("요약 다시 생성", "secondary", start));
    } else if (job.state === "completed") {
      if (isPublished) buttons.push(<Button key="게시됨" kind="primary" disabled>게시됨</Button>);
      else {
        buttons.push(action("게시", "primary", async () => {
          const jobId = job.id;
          const view = await publishSummary(record, jobId);
          if (!live.current) return;
          setPublished(view);
          setPublishedJobId(jobId);
        }));
      }
      buttons.push(action("요약 다시 생성", "secondary", start));
    } else {
      buttons.push(action("다시 시도", "primary", () => retrySummaryJob(record, job.id).then(adopt)));
    }
  }

  return (
    <div className="pf-summary" aria-label={`${record.title} 쪽별 요약`}>
      <p className="pf-sub">요약을 만들면 이 PDF의 쪽 이미지와 텍스트가 ASTRA 서버에 설정된 LLM 제공자로 전송됩니다. 후보를 확인하고 게시해야 학생 학습실에 보입니다.</p>
      <p className="pf-sub" role="status" aria-live="polite">{statusText}</p>
      <div className="pf-summary-progress">
        {job && active ? <progress max={job.pagesTotal || 1} value={job.pagesTotal ? job.pagesDone || 0 : undefined} aria-label="요약 진행률" /> : null}
      </div>
      <div className="pf-actions">{buttons}</div>
      <p className="err" role="alert">{error}</p>
      <div className="pf-summary-preview">
        {result ? (
          <div className="stack">
            <h3>요약 후보 미리보기</h3>
            <p className="pf-sub">학생에게는 게시한 뒤에 보입니다. 검토가 필요한 쪽과 경고를 먼저 확인하세요.</p>
            {summaryView(result, { pageImageBase: null })}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function reportFields(err: Record<string, string>, ctx: ScreenCtx, course: Course | null): ReactNode {
  // Backend mode: server form and list (areas/professor/assignments-server.tsx); `anon` shows only the login strip.
  if (course && backendStatus() !== "off") return serverReportFields(ctx, course, { formSheet, span, linkBtn });
  return formSheet("과제",
    <Field label="제목" error={err.title}><input data-name="title" /></Field>,
    <Field label="배점" error={err.points}><input type="number" data-name="points" min="1" max="100" defaultValue="20" /></Field>,
    <Field label="시작" error={err.start}><input type="datetime-local" data-name="start" /></Field>,
    <Field label="마감" error={err.due}><input type="datetime-local" data-name="due" /></Field>,
    <label className="choice pf-check span-2"><input type="checkbox" data-name="late" /><span>지각 제출 허용</span></label>,
    saveButton("출제", "report"),
  );
}

function examFields(ctx: ScreenCtx, err: Record<string, string>, course: Course | null): ReactNode {
  // Backend mode: server quiz form and list (areas/professor/quizzes-server.tsx); `anon` shows only the login strip.
  if (course && backendStatus() !== "off") return serverExamFields(ctx, course, { formSheet, span, linkBtn });
  return formSheet("시험",
    <Field label="시험명" error={err.title}><input data-name="title" /></Field>,
    <Field label="제한 시간(분)" error={err.minutes}><input type="number" data-name="minutes" min="5" max="180" defaultValue="50" /></Field>,
    <div className="pf-picks span-2">
      <p className="pf-kicker">문제은행에서 가져올 문항</p>
      {ctx.state.questions.map((q) => (
        <label key={q.id} className="choice">
          <input type="checkbox" data-name="qid" value={q.id} />
          <span>{`${q.stem} · ${q.type} · ${q.points}점`}</span>
        </label>
      ))}
      <div data-name="questions" />
      {err.questions ? <p className="err">{err.questions}</p> : null}
    </div>,
    saveButton("시험 저장", "exam"),
  );
}

function discussFields(err: Record<string, string>, discussOnly: boolean): ReactNode {
  // Backend mode keeps only 토론 here; surveys are on the server (areas/professor/surveys-server.tsx).
  if (discussOnly) {
    return formSheet("토론",
      <Field label="종류"><select data-name="kind"><option value="discuss">토론</option></select></Field>,
      <Field label="제목" error={err.title}><input data-name="title" /></Field>,
      span(<Field label="안내" error={err.body}><textarea data-name="body" /></Field>),
      <p className="pf-sub span-2">토론은 이 브라우저에만 저장됩니다.</p>,
      saveButton("토론 만들기", "discuss"),
    );
  }
  return formSheet("토론·설문",
    <Field label="종류"><select data-name="kind"><option value="discuss">토론</option><option value="survey">설문</option></select></Field>,
    <Field label="제목" error={err.title}><input data-name="title" /></Field>,
    span(<Field label="안내" error={err.body}><textarea data-name="body" /></Field>),
    saveButton("만들기", "discuss"),
  );
}

function messageFields(err: Record<string, string>): ReactNode {
  return formSheet("쪽지",
    <Field label="대상">
      <select data-name="to">
        <option value="all">수강생 전체</option>
        {learners().map((p) => <option key={p.id} value={p.name}>{p.name}</option>)}
      </select>
    </Field>,
    <Field label="제목" error={err.title}><input data-name="title" /></Field>,
    span(<Field label="내용" error={err.body}><textarea data-name="body" /></Field>),
    saveButton("발송", "pmessage"),
  );
}

function peopleRows(ctx: ScreenCtx, decorate: (p: Person, index: number) => TableRecord): TableRecord[] {
  return learners().filter((p) => matchesQuery(ctx, `${p.name} ${p.no}`)).map((p, index) => decorate(p, index));
}

function status(ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverStatusFrame(ctx, course, "제출 현황", serverSubmissionStatus(ctx, course));
  const view = param(ctx, "view");
  const all = learners().map((p) => ({ person: p, sub: submissionOf(ctx.state, p) }));
  const rows = all.filter(({ person, sub }) => matchesQuery(ctx, `${person.name} ${person.no}`) && (view === "in" ? sub : view === "out" ? !sub : true));
  const records = rows.map(({ person, sub }) => ({
    sort: { no: person.no, name: person.name, status: sub ? "제출" : "미제출", at: sub ? sub.at : "" },
    cells: [
      person.no,
      person.name,
      statusBadge(sub ? "제출" : "미제출", Boolean(sub)),
      sub ? sub.at : "—",
      sub ? (sub.fileName || "첨부") : "—",
    ],
  }));
  return frame("제출 현황", ctx, {
    course: true,
    kicker: course.ko,
    sub: "텍스트마이닝 미션 2",
    actions: [
      linkBtn(pf("pf_report_create", { kj: course.id }), "과제 출제"),
      linkBtn(pf("pf_report_grade", { kj: course.id }), "채점", "primary"),
    ],
  },
  <div className="stat-row cols-3">
    {stat("수강생", String(all.length))}
    {stat("제출", String(all.filter((row) => row.sub).length))}
    {stat("미제출", String(all.filter((row) => !row.sub).length))}
  </div>,
  <div className="pf-toolbar">
    {chipRow(ctx, "pf_report_status", [["all", "전체"], ["in", "제출"], ["out", "미제출"]], "view")}
    {finder(ctx, "이름, 학번")}
  </div>,
  records.length ? dataTable("submissions", "과제 제출 현황", [
    { key: "no", label: "학번", sort: true },
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "at", label: "시각", sort: true },
    { key: "file", label: "파일" },
  ], records, ctx) : <EmptyState title="조건에 맞는 수강생이 없습니다" body="필터나 검색어를 바꿔 보세요." />,
  );
}

function saveScores(item: string) {
  const state = getState();
  const data = readForm();
  for (const [key, value] of Object.entries(data)) {
    if (!key.startsWith("score-") || value === "") continue;
    const n = Number(value);
    if (!Number.isInteger(n) || n < 0 || n > 100) return showErrors({ [key]: "점수는 0부터 100 사이의 정수입니다" });
  }
  for (const [key, value] of Object.entries(data)) {
    if (!key.startsWith("score-")) continue;
    const id = key.slice(6);
    state.scores[id] = state.scores[id] || {};
    state.scores[id][item] = value === "" ? "" : Number(value);
  }
  state.toast = "점수를 저장했습니다";
  commit();
}

function grade(ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverStatusFrame(ctx, course, "과제 채점", serverGrading(ctx, course));
  const err = ctx.state.formError || {};
  const records = peopleRows(ctx, (p) => {
    const sub = submissionOf(ctx.state, p);
    const score = ctx.state.scores[p.id] && ctx.state.scores[p.id]["r-open"];
    const shown = score == null ? "" : String(score);
    return {
      sort: { no: p.no, name: p.name, status: sub ? "제출완료" : "미제출", score: shown === "" ? null : Number(shown) },
      cells: [
        p.no,
        p.name,
        statusBadge(sub ? "제출완료" : "미제출", Boolean(sub)),
        <input key={shown} type="number" min="0" max="100" data-name={`score-${p.id}`} defaultValue={shown} aria-label={`${p.name} 점수`} />,
        sub ? (sub.fileName || "제출물") : "—",
      ],
    };
  });
  return frame("과제 채점", ctx, {
    course: true,
    kicker: course.ko,
    sub: "점수는 0부터 100 사이의 정수입니다. 이 브라우저에만 저장됩니다.",
    actions: [linkBtn(pf("pf_report_status", { kj: course.id }), "제출 현황")],
  },
  <div className="pf-toolbar">{finder(ctx, "이름, 학번")}</div>,
  err.scores ? <p className="err">{err.scores}</p> : null,
  records.length ? dataTable("grading", "과제 채점", [
    { key: "no", label: "학번", sort: true },
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "score", label: "점수", sort: true },
    { key: "file", label: "파일" },
  ], records, ctx, { wide: true }) : <EmptyState title="조건에 맞는 수강생이 없습니다" body="검색어를 지워 보세요." />,
  <div className="pf-actions"><Button kind="primary" onClick={() => saveScores("r-open")}>점수 저장</Button></div>,
  );
}

function examStatus(ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverExamFrame(ctx, course, "응시 현황", serverExamStatus(ctx, course));
  const view = param(ctx, "view");
  const roster = [
    { name: "김서연", status: "응시", at: "2026-10-22 11:40" },
    { name: "박민준", status: "응시", at: "2026-10-22 11:51" },
    { name: "이지은", status: "미응시", at: "" },
  ];
  const rows = roster.filter((row) => matchesQuery(ctx, row.name) && (view === "in" ? row.status === "응시" : view === "out" ? row.status !== "응시" : true));
  const records = rows.map((row) => ({
    sort: { name: row.name, status: row.status, at: row.at },
    cells: [row.name, statusBadge(row.status, row.status === "응시"), row.at || "—"],
  }));
  return frame("응시 현황", ctx, {
    course: true,
    kicker: course.ko,
    sub: "중간고사",
    actions: [
      linkBtn(pf("pf_exam_create", { kj: course.id }), "시험 출제"),
      linkBtn(pf("pf_exam_grade", { kj: course.id }), "채점", "primary"),
    ],
  },
  <div className="pf-toolbar">
    {chipRow(ctx, "pf_exam_status", [["all", "전체"], ["in", "응시"], ["out", "미응시"]], "view")}
    {finder(ctx, "이름 검색")}
  </div>,
  records.length ? dataTable("exam-status", "시험 응시 현황", [
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "at", label: "제출", sort: true },
  ], records, ctx) : <EmptyState title="조건에 맞는 응시 기록이 없습니다" body="필터를 전체로 돌려 보세요." />,
  );
}

function saveExamScore() {
  const state = getState();
  const n = Number(readForm().score);
  if (!Number.isInteger(n) || n < 0 || n > 5) return showErrors({ score: "0부터 5 사이의 정수입니다" });
  state.scores.minjun = state.scores.minjun || {};
  state.scores.minjun.examQ = n;
  state.toast = "문항 점수를 저장했습니다";
  commit();
}

function questionCard(q: { id: string; type: string; points: number; stem: string; choices: string[] }): ReactNode {
  return (
    <article key={q.id} className="sheet pf-q">
      <div className="pf-inline"><Badge tone="neutral">{q.type}</Badge><Badge tone="neutral">{`${q.points}점`}</Badge></div>
      <h2>{q.stem}</h2>
      {q.choices.length ? <ol>{q.choices.map((choice, index) => <li key={index}>{choice}</li>)}</ol> : null}
    </article>
  );
}

function examGrade(ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverExamFrame(ctx, course, "시험 채점", serverExamGrade(ctx, course));
  const err = ctx.state.formError || {};
  const q = ctx.state.questions.find((item) => item.stem.includes("과적합")) || ctx.state.questions[0];
  const saved = ctx.state.scores.minjun && ctx.state.scores.minjun.examQ;
  const value = saved == null ? "4" : String(saved);
  return frame("시험 채점", ctx, {
    course: true,
    kicker: course.ko,
    sub: "문항 점수는 0부터 5 사이의 정수입니다.",
    actions: [linkBtn(pf("pf_exam_status", { kj: course.id }), "응시 현황")],
  },
  q ? questionCard(q) : null,
  <div className="sheet">
    {err.score ? <p className="err">{err.score}</p> : null}
    <Field label="박민준 점수"><input key={value} type="number" min="0" max="5" data-name="score" defaultValue={value} aria-label="박민준 점수" /></Field>
    <div className="pf-actions"><Button kind="primary" onClick={saveExamScore}>문항 점수 저장</Button></div>
  </div>,
  );
}

function addBank() {
  const state = getState();
  const data = readForm();
  const title = text(data.bankTitle).trim();
  if (!title) return showErrors({ bankTitle: "이름을 입력하세요" });
  state.banks.unshift({ id: `b-${Date.now()}`, title, count: 0, updated: stamp().slice(0, 10), courseId: state.courseId });
  state.toast = "문제은행을 추가했습니다";
  commit();
}

function addQuestion() {
  const state = getState();
  const data = readForm();
  const errors: Record<string, string> = {};
  const stem = text(data.stem).trim();
  if (stem.length < 4) errors.stem = "질문을 4자 이상 입력하세요";
  const choices = text(data.choices).split("\n").map((line) => line.trim()).filter(Boolean);
  if (data.qtype === "객관식" && choices.length < 2) errors.choices = "보기를 두 줄 이상 입력하세요";
  const points = Number(data.points);
  if (!Number.isInteger(points) || points < 1 || points > 20) errors.points = "배점은 1부터 20입니다";
  if (Object.keys(errors).length) return showErrors(errors);
  const bankId = text(data.bankId);
  state.questions.push({ id: `q-${Date.now()}`, bankId, type: text(data.qtype), points, stem, choices, answer: 0 });
  const bank = state.banks.find((b) => b.id === bankId);
  if (bank) bank.count += 1;
  state.toast = "문항을 저장했습니다";
  commit();
}

function bank(ctx: ScreenCtx): ReactNode {
  // Backend mode: the server has no question bank; quiz questions are written per quiz (pf_exam_create).
  if (backendStatus() !== "off") {
    return frame("문제은행", ctx, { kicker: "교수", sub: "ASTRA 서버에는 문제은행이 아직 없습니다." },
      unavailable("문제은행은 아직 ASTRA 서버에 없습니다", "서버 시험의 문항은 시험 출제 화면에서 시험마다 직접 넣습니다. 이 브라우저에만 남는 샘플 은행과 문항은 서버에 연결된 동안 보이지 않습니다.",
        linkBtn(pf("pf_exam_create", { kj: ctx.state.courseId || "bi" }), "시험 출제로")));
  }
  const err = ctx.state.formError || {};
  const bankId = param(ctx, "bank");
  const type = param(ctx, "type");
  const bankTitleOf = (id: string) => (ctx.state.banks.find((b) => b.id === id) || { title: "" }).title;
  const questions = ctx.state.questions.filter((q) => {
    if (bankId && q.bankId !== bankId) return false;
    if (type && q.type !== type) return false;
    return matchesQuery(ctx, `${q.stem} ${q.type} ${bankTitleOf(q.bankId)}`);
  });
  const records = questions.map((q) => {
    const bankTitle = bankTitleOf(q.bankId) || "—";
    return {
      sort: { stem: q.stem, type: q.type, points: q.points, bank: bankTitle },
      cells: [
        <div className="pf-stem">
          <b>{q.stem}</b>
          {q.choices.length ? <div className="pf-meta">{q.choices.join(" · ")}</div> : null}
        </div>,
        <Badge tone="neutral">{q.type}</Badge>,
        <b className="pf-total">{String(q.points)}</b>,
        bankTitle,
        <div className="pf-row-actions">
          <Button kind="secondary" onClick={() => openDialog("question", q.id)}>수정</Button>
          <Button kind="danger" onClick={() => openDialog("delete-question", q.id)}>삭제</Button>
        </div>,
      ],
    };
  });
  const cards = ctx.state.banks.map((b) => {
    const count = ctx.state.questions.filter((q) => q.bankId === b.id).length;
    const on = bankId === b.id;
    return (
      <article key={b.id} className={`pf-bank${on ? " on" : ""}`}>
        <div className="pf-bank-top">
          <Link href={kept(ctx, "pf_bank", { bank: b.id })} className="pf-bank-name" aria-current={on ? "true" : undefined}>{b.title}</Link>
          <div className="pf-inline">
            <Button kind="secondary" onClick={() => openDialog("bank", b.id)}>수정</Button>
            <Button kind="ghost" onClick={() => openDialog("delete-bank", b.id)}>삭제</Button>
          </div>
        </div>
        <div className="pf-meta">{`편집 가능 ${count}문항 · ${b.updated}`}</div>
      </article>
    );
  });
  return frame("문제은행", ctx, {
    kicker: "교수",
    sub: "은행과 문항은 이 브라우저에만 저장됩니다.",
    actions: [linkBtn(pf("pf_exam_create", { kj: "bi" }), "시험에 넣기")],
  },
  <div className="stat-row cols-3">
    {stat("은행", String(ctx.state.banks.length))}
    {stat("문항", String(ctx.state.questions.length))}
    {stat("표시", String(questions.length))}
  </div>,
  <div className="pf-toolbar">
    {chipRow(ctx, "pf_bank", [["all", "전체", "all"], ["객관식", "객관식", "test"], ["단답", "단답", "material"]], "type")}
    {finder(ctx, "질문 검색")}
  </div>,
  <div className="pf-split">
    <div className="stack">
      {cards.length ? <div className="pf-banks">{cards}</div> : <EmptyState title="문제은행이 없습니다" body="아래에서 은행을 추가하세요." />}
      <div className="sheet">
        <h2>문제은행 추가</h2>
        <Field label="은행 이름" error={err.bankTitle}><input data-name="bankTitle" /></Field>
        <Button kind="primary" onClick={addBank}>은행 추가</Button>
      </div>
    </div>
    <div className="sheet">
      <h2>문항 추가</h2>
      {ctx.state.banks.length ? (
        <div className="pf-form">
          <Field label="은행">
            <select key={bankId} data-name="bankId" defaultValue={bankId && ctx.state.banks.some((b) => b.id === bankId) ? bankId : ctx.state.banks[0].id}>
              {ctx.state.banks.map((b) => <option key={b.id} value={b.id}>{b.title}</option>)}
            </select>
          </Field>
          <Field label="유형"><select data-name="qtype"><option value="객관식">객관식</option><option value="단답">단답</option></select></Field>
          <Field label="배점" error={err.points}><input type="number" data-name="points" min="1" max="20" defaultValue="5" /></Field>
          {span(<Field label="질문" error={err.stem}><textarea data-name="stem" /></Field>)}
          {span(<Field label="보기" error={err.choices}><textarea data-name="choices" placeholder="객관식은 한 줄에 하나씩, 두 줄 이상" /></Field>)}
          <div className="pf-actions span-2"><Button kind="primary" onClick={addQuestion}>문항 저장</Button></div>
        </div>
      ) : <p className="pf-sub">문항을 넣으려면 은행을 먼저 만드세요.</p>}
    </div>
  </div>,
  <h2 className="section-label">문항</h2>,
  records.length ? dataTable("questions", "문제은행 문항", [
    { key: "stem", label: "질문", sort: true },
    { key: "type", label: "유형", sort: true },
    { key: "points", label: "배점", sort: true },
    { key: "bank", label: "은행", sort: true },
    { key: "actions", label: "관리" },
  ], records, ctx, { wide: true }) : <EmptyState title="조건에 맞는 문항이 없습니다" body="필터를 바꾸거나 문항을 추가하세요." />,
  bankDialog(ctx),
  );
}

function saveBank(id: string) {
  const state = getState();
  const title = text(readForm().editBankTitle).trim();
  if (!title) return showErrors({ editBankTitle: "이름을 입력하세요" });
  const bank = state.banks.find((item) => item.id === id);
  if (bank) bank.title = title;
  state.draft.pfDialog = null;
  state.toast = "문제은행을 수정했습니다";
  commit();
}

function saveQuestion(id: string) {
  const state = getState();
  const data = readForm();
  const errors: Record<string, string> = {};
  const stem = text(data.editStem).trim();
  if (stem.length < 4) errors.editStem = "질문을 4자 이상 입력하세요";
  const choices = text(data.editChoices).split("\n").map((line) => line.trim()).filter(Boolean);
  if (data.editQtype === "객관식" && choices.length < 2) errors.editChoices = "보기를 두 줄 이상 입력하세요";
  const points = Number(data.editPoints);
  if (!Number.isInteger(points) || points < 1 || points > 20) errors.editPoints = "배점은 1부터 20입니다";
  if (Object.keys(errors).length) return showErrors(errors);
  const question = state.questions.find((item) => item.id === id);
  if (!question) return showErrors({ editStem: "문항을 찾지 못했습니다" });
  const prev = question.bankId;
  question.bankId = text(data.editBankId) || prev;
  question.type = text(data.editQtype);
  question.points = points;
  question.stem = stem;
  question.choices = data.editQtype === "객관식" ? choices : [];
  if (prev !== question.bankId) {
    const from = state.banks.find((item) => item.id === prev);
    const to = state.banks.find((item) => item.id === question.bankId);
    if (from && from.count > 0) from.count -= 1;
    if (to) to.count += 1;
  }
  state.draft.pfDialog = null;
  state.toast = "문항을 수정했습니다";
  commit();
}

function bankDialog(ctx: ScreenCtx): ReactNode {
  const dialog: PfDialog | null = ctx.state.draft.pfDialog;
  if (!dialog) return null;
  if (dialog.kind === "question") {
    const q = ctx.state.questions.find((item) => item.id === dialog.id);
    if (!q) return null;
    return pfDialog("문항 수정",
      <Field label="은행">
        <select data-name="editBankId" defaultValue={q.bankId}>
          {ctx.state.banks.map((b) => <option key={b.id} value={b.id}>{b.title}</option>)}
        </select>
      </Field>,
      <Field label="유형">
        <select data-name="editQtype" defaultValue={q.type}>
          <option value="객관식">객관식</option>
          <option value="단답">단답</option>
        </select>
      </Field>,
      <Field label="배점"><input type="number" data-name="editPoints" min="1" max="20" defaultValue={String(q.points)} /></Field>,
      <Field label="질문"><textarea data-name="editStem" defaultValue={q.stem} /></Field>,
      <Field label="보기"><textarea data-name="editChoices" defaultValue={q.choices.join("\n")} /></Field>,
      <div className="pf-actions">
        <Button kind="secondary" onClick={dismiss}>취소</Button>
        <Button kind="primary" onClick={() => saveQuestion(q.id)}>저장</Button>
      </div>,
    );
  }
  if (dialog.kind === "bank") {
    const b = ctx.state.banks.find((item) => item.id === dialog.id);
    if (!b) return null;
    return pfDialog("문제은행 수정",
      <Field label="은행 이름"><input data-name="editBankTitle" defaultValue={b.title} /></Field>,
      <div className="pf-actions">
        <Button kind="secondary" onClick={dismiss}>취소</Button>
        <Button kind="primary" onClick={() => saveBank(b.id)}>저장</Button>
      </div>,
    );
  }
  if (dialog.kind === "delete-question" || dialog.kind === "delete-bank") {
    const question = dialog.kind === "delete-question" ? ctx.state.questions.find((item) => item.id === dialog.id) : undefined;
    const bankItem = dialog.kind === "delete-bank" ? ctx.state.banks.find((item) => item.id === dialog.id) : undefined;
    const name = question ? question.stem : bankItem ? bankItem.title : "이 항목";
    return pfDialog(dialog.kind === "delete-question" ? "문항 삭제" : "문제은행 삭제",
      <p>{`「${name}」을 삭제합니다. 이 브라우저에서만 지워집니다.`}</p>,
      <div className="pf-actions">
        <Button kind="secondary" onClick={dismiss}>취소</Button>
        <Button kind="danger" onClick={confirmDelete}>삭제</Button>
      </div>,
    );
  }
  return null;
}

function saveMakeup() {
  const state = getState();
  const data = readForm();
  const errors: Record<string, string> = {};
  if (!data.week) errors.week = "주차를 입력하세요";
  if (!data.when) errors.when = "일시를 입력하세요";
  if (!text(data.why).trim()) errors.why = "사유를 입력하세요";
  if (Object.keys(errors).length) return showErrors(errors);
  state.draft.makeups = state.draft.makeups || [];
  state.draft.makeups.push({ week: text(data.week), when: text(data.when), why: text(data.why).trim() } satisfies Makeup);
  state.toast = "보강을 등록했습니다";
  commit();
}

function setAttendance(who: string, week: number, event: ChangeEvent<HTMLSelectElement>) {
  const state = getState();
  state.attendance[`${who}:main:${week}`] = event.target.value;
  save();
  toast("출석을 바꿨습니다");
}

function attend(screen: Screen, ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  // 백엔드 모드: 서버 출석부·스마트출석·차시 관리(areas/professor/attendance-server.tsx).
  if (backendStatus() !== "off") {
    const titles: Record<string, [string, string]> = { general: ["출석 관리", "칸마다 사유와 함께 바꾸고, 다른 곳에서 먼저 바뀐 칸은 다시 불러옵니다."], smart: ["스마트 출석", "PIN이나 15초마다 바뀌는 QR로 출석을 받습니다. 판정과 기록은 서버가 합니다."], makeup: ["휴강·보강", "차시를 만들고 고치고, 휴강한 차시의 보강을 등록합니다."] };
    const [heading, sub] = titles[screen.mode || "general"] || titles.general;
    const views: Record<string, (ctx: ScreenCtx, course: Course) => ReactNode> = { general: serverAttendGeneral, smart: serverAttendSmart, makeup: serverAttendSessions };
    return frame(heading, ctx, { course: true, kicker: course.ko, sub },
      <Tabs items={[
        { label: "일반", href: pf("pf_attend", { kj: course.id }), on: (screen.mode || "general") === "general" },
        { label: "스마트", href: pf("pf_attend_smart", { kj: course.id }), on: screen.mode === "smart" },
        { label: "휴강·보강", href: pf("pf_attend_makeup", { kj: course.id }), on: screen.mode === "makeup" },
      ]} />,
      (views[screen.mode || "general"] || serverAttendGeneral)(ctx, course));
  }
  const mode = screen.mode || "general";
  const modeTabs = (
    <Tabs items={[
      { label: "일반", href: withQ(ctx, "pf_attend"), on: mode === "general" },
      { label: "스마트", href: withQ(ctx, "pf_attend_smart"), on: mode === "smart" },
      { label: "휴강·보강", href: withQ(ctx, "pf_attend_makeup"), on: mode === "makeup" },
    ]} />
  );
  if (mode === "smart") {
    const view = param(ctx, "view");
    const rows = learners().filter((p) => matchesQuery(ctx, p.name)).map((p) => {
      const pct = SMART[p.id] ?? 80;
      return { person: p, pct, pass: pct >= 90 };
    }).filter((row) => (view === "in" ? row.pass : view === "out" ? !row.pass : true));
    const records = rows.map((row) => ({
      sort: { name: row.person.name, pct: row.pct, status: row.pass ? "출석" : "미달" },
      cells: [row.person.name, meter(row.pct), statusBadge(row.pass ? "출석" : "미달", row.pass)],
    }));
    return frame("스마트 출석", ctx, {
      course: true,
      kicker: course.ko,
      sub: "영상 진도 90% 이상이면 출석으로 인정합니다.",
    }, modeTabs,
    <div className="pf-toolbar">
      {chipRow(ctx, "pf_attend_smart", [["all", "전체"], ["in", "출석"], ["out", "미달"]], "view")}
      {finder(ctx, "이름 검색")}
    </div>,
    records.length ? dataTable("smart", "스마트 출석", [
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "pct", label: "영상 진도", sort: true },
      { key: "status", label: "인정", sort: true },
    ], records, ctx) : <EmptyState title="조건에 맞는 수강생이 없습니다" body="필터를 바꿔 보세요." />,
    );
  }
  if (mode === "makeup") {
    const err = ctx.state.formError || {};
    const makeups: Makeup[] = ctx.state.draft.makeups || [];
    const records = makeups.filter((m) => matchesQuery(ctx, `${m.week} ${m.why}`)).map((m) => ({
      sort: { week: Number(m.week), when: m.when, why: m.why },
      cells: [`${m.week}주차`, m.when, m.why],
    }));
    return frame("휴강·보강", ctx, { course: true, kicker: course.ko, sub: "보강 일정은 이 브라우저에만 남습니다." },
      modeTabs,
      formSheet("보강 등록",
        <Field label="주차" error={err.week}><input type="number" data-name="week" min="1" max="14" /></Field>,
        <Field label="보강 일시" error={err.when}><input type="datetime-local" data-name="when" /></Field>,
        span(<Field label="사유" error={err.why}><input data-name="why" /></Field>),
        <div className="pf-actions span-2"><Button kind="primary" onClick={saveMakeup}>보강 등록</Button></div>,
      ),
      records.length ? dataTable("makeups", "보강", [
        { key: "week", label: "주차", sort: true },
        { key: "when", label: "일시", sort: true },
        { key: "why", label: "사유", sort: true },
      ], records, ctx) : <EmptyState title="등록된 보강이 없습니다" body="위 양식으로 샘플 보강을 남길 수 있습니다." />,
    );
  }
  const rows = learners().filter((p) => matchesQuery(ctx, p.name));
  const columns: Column[] = [
    { key: "name", label: "이름", sort: true, lock: true },
    ...Array.from({ length: 8 }, (_, i) => ({ key: `w${i + 1}`, label: `${i + 1}주차` })),
  ];
  const records = rows.map((p) => ({
    sort: { name: p.name },
    cells: [
      p.name,
      ...Array.from({ length: 8 }, (_, i) => {
        const value = attendStatus(ctx.state, p.id, i + 1);
        return (
          <select key={value} defaultValue={value} aria-label={`${p.name} ${i + 1}주차`} className={`pf-attend-select pf-attend-${value}`} onChange={(event) => setAttendance(p.id, i + 1, event)}>
            {["present", "late", "absent", "excused", "empty"].map((st) => <option key={st} value={st}>{statusLabel(st)}</option>)}
          </select>
        );
      }),
    ],
  }));
  return frame("출석 관리", ctx, {
    course: true,
    kicker: course.ko,
    sub: "변경은 바로 이 브라우저의 출석부에 반영됩니다.",
  }, modeTabs,
  <div className="pf-toolbar">
    <div className="pf-inline"><Badge tone="success">출석</Badge><Badge tone="neutral">지각</Badge><Badge tone="danger">결석</Badge><Badge tone="neutral">유고</Badge></div>
    {finder(ctx, "이름 검색")}
  </div>,
  records.length ? dataTable("attendance", "출석부", columns, records, ctx, { layout: "scroll", wide: true, attend: true })
    : <EmptyState title="조건에 맞는 수강생이 없습니다" body="검색어를 지워 보세요." />,
  );
}

function saveGradebook() {
  const state = getState();
  const data = readForm();
  const weights: Record<string, number> = {};
  let sum = 0;
  for (const key of ["att", "rep", "mid", "fin"]) {
    const n = Number(data[`w-${key}`]);
    if (!Number.isInteger(n) || n < 0 || n > 100) return showErrors({ [`w-${key}`]: "반영 비율은 0부터 100 사이의 정수입니다" });
    weights[key] = n;
    sum += n;
  }
  if (sum !== 100) return showErrors({ weights: "반영 비율의 합이 100이어야 합니다" });
  for (const [key, value] of Object.entries(data)) {
    if (!key.startsWith("gb-") || value === "") continue;
    const n = Number(value);
    if (!Number.isInteger(n) || n < 0 || n > 100) return showErrors({ [key]: "점수는 0부터 100 사이의 정수입니다" });
  }
  state.weights = weights;
  for (const [key, value] of Object.entries(data)) {
    if (!key.startsWith("gb-")) continue;
    const [, id, item] = key.split("-");
    state.scores[id] = state.scores[id] || {};
    state.scores[id][item] = value === "" ? "" : Number(value);
  }
  state.toast = "성적부를 저장했습니다";
  commit();
}

function togglePublic() {
  const state = getState();
  state.gradePublic = !state.gradePublic;
  state.toast = state.gradePublic ? "학생에게 성적을 공개합니다" : "성적을 비공개로 바꿨습니다";
  commit();
}

function gradebook(ctx: ScreenCtx): ReactNode {
  // 백엔드 모드: 서버 성적부(areas/professor/grades-server.tsx).
  if (backendStatus() !== "off") return frame("성적 관리", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "항목·공개·점수는 ASTRA 서버에 저장됩니다. 학생에게는 공개한 점수만 보입니다." }, serverGradebook(ctx, courseOf(ctx)));
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const weights = ctx.state.weights;
  const sum = gradeItems.reduce((total, item) => total + Number(weights[item.id] || 0), 0);
  const columns: Column[] = [
    { key: "name", label: "이름", sort: true, lock: true },
    ...gradeItems.map((g) => ({ key: g.id, label: `${g.name} ${weights[g.id] || 0}%`, sort: true })),
    { key: "total", label: "합계", sort: true },
  ];
  const records = peopleRows(ctx, (p) => {
    const score = ctx.state.scores[p.id] || {};
    let total = 0;
    let pending = false;
    const cells = gradeItems.map((g) => {
      const raw = score[g.id];
      const num = raw === "" || raw == null ? null : Number(raw);
      if (num == null || Number.isNaN(num)) pending = true;
      else total += num * ((weights[g.id] || 0) / (g.id === "att" ? 10 : 30));
      const shown = raw == null ? "" : String(raw);
      return <input key={shown} type="number" min="0" max="100" data-name={`gb-${p.id}-${g.id}`} defaultValue={shown} aria-label={`${p.name} ${g.name}`} />;
    });
    return {
      sort: {
        name: p.name,
        ...Object.fromEntries(gradeItems.map((g) => {
          const raw = score[g.id];
          return [g.id, raw === "" || raw == null ? null : Number(raw)];
        })),
        total: pending ? null : Number(total.toFixed(1)),
      },
      cells: [p.name, ...cells, pending ? <Badge tone="neutral">미완</Badge> : <b className="pf-total">{total.toFixed(1)}</b>],
    };
  });
  return frame("성적 관리", ctx, {
    course: true,
    kicker: course.ko,
    sub: "반영 비율의 합이 100이어야 저장됩니다. 점수는 0부터 100 사이의 정수입니다.",
  },
  <div className="sheet">
    <div className="pf-week-head">
      <h2>반영 비율</h2>
      <Badge tone={sum === 100 ? "success" : "danger"}>{sum === 100 ? "합계 100" : `합계 ${sum}`}</Badge>
    </div>
    <div className="pf-weights">
      {gradeItems.map((g) => (
        <Field key={g.id} label={g.name}>
          <input key={String(weights[g.id] || 0)} type="number" data-name={`w-${g.id}`} defaultValue={String(weights[g.id] || 0)} min="0" max="100" aria-label={`${g.name} 반영 비율`} />
        </Field>
      ))}
    </div>
    <div data-name="weights" />
    {err.weights ? <p className="err">{err.weights}</p> : null}
  </div>,
  <div className="pf-toolbar">{finder(ctx, "이름 검색")}</div>,
  err.scores ? <p className="err">{err.scores}</p> : null,
  records.length ? dataTable("gradebook", "성적부", columns, records, ctx, { layout: "scroll", wide: true })
    : <EmptyState title="조건에 맞는 수강생이 없습니다" body="검색어를 지워 보세요." />,
  <div className="pf-actions">
    <Button kind="primary" onClick={saveGradebook}>성적 저장</Button>
    <button type="button" className={`btn ${ctx.state.gradePublic ? "btn-primary" : "btn-secondary"}`} onClick={togglePublic}>
      {ctx.state.gradePublic ? "학생 공개 중" : "학생에게 비공개"}
    </button>
  </div>,
  );
}

function saveAppeal(id: string) {
  const state = getState();
  const data = readForm();
  const key = `reply-${id}`;
  const reply = text(data[key]).trim();
  if (!reply) return showErrors({ [key]: "답변을 입력하세요" });
  const item = state.appeals.find((a) => a.id === id);
  if (item) {
    item.reply = reply;
    item.status = "답변";
  }
  state.toast = "답변을 저장했습니다";
  commit();
}

function appeal(ctx: ScreenCtx): ReactNode {
  // Backend mode: the server has no grade appeal; nothing sample is shown in a server course.
  if (serverRoom(courseOf(ctx))) {
    return frame("이의신청", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "ASTRA 서버에는 성적 이의신청이 아직 없습니다." },
      unavailable("이의신청은 아직 ASTRA 서버에 없습니다", "학생이 성적에 이의를 내고 교수가 답하는 기능이 서버에 생기면 여기에서 받습니다. 점수는 성적 관리와 과제·시험 채점에서 고칩니다.",
        linkBtn(pf("pf_gradebook", { kj: courseOf(ctx).id }), "성적 관리로")));
  }
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const view = param(ctx, "view");
  const rows = ctx.state.appeals.filter((a) => {
    if (!matchesQuery(ctx, `${a.who} ${a.item} ${a.body}`)) return false;
    if (view === "wait") return a.status === "대기";
    if (view === "done") return a.status !== "대기";
    return true;
  });
  return frame("이의신청", ctx, { course: true, kicker: course.ko, sub: "답변은 이 브라우저에만 저장됩니다." },
    <div className="pf-toolbar">
      {chipRow(ctx, "pf_appeal", [["all", "전체"], ["wait", "대기"], ["done", "답변"]], "view")}
      {finder(ctx, "이름, 문항")}
    </div>,
    rows.length ? (
      <div className="stack">
        {rows.map((a) => (
          <article key={a.id} className="sheet">
            <div className="pf-inline">
              <b>{a.who}</b>
              <Badge tone={a.status === "답변" ? "success" : "danger"}>{a.status}</Badge>
              <span className="pf-meta">{a.item}</span>
            </div>
            <p>{a.body}</p>
            {a.reply ? <p>{`답변: ${a.reply}`}</p> : (
              <div>
                <Field label="답변" error={err[a.id]}><textarea data-name={`reply-${a.id}`} /></Field>
                <Button kind="primary" onClick={() => saveAppeal(a.id)}>답변 저장</Button>
              </div>
            )}
          </article>
        ))}
      </div>
    ) : <EmptyState title="해당하는 이의신청이 없습니다" body="필터를 바꿔 보세요." />,
  );
}

function saveTeam() {
  const state = getState();
  const data = readForm();
  const errors: Record<string, string> = {};
  const name = text(data.name).trim();
  const topic = text(data.topic).trim();
  if (!name) errors.name = "팀 이름을 입력하세요";
  if (!topic) errors.topic = "주제를 입력하세요";
  const size = Number(data.size);
  if (!Number.isInteger(size) || size < 2 || size > 6) errors.size = "인원은 2부터 6입니다";
  if (Object.keys(errors).length) return showErrors(errors);
  teamsOf(state).push({ name, topic, size });
  state.toast = "팀을 만들었습니다";
  commit();
}

function teams(ctx: ScreenCtx): ReactNode {
  // 백엔드 모드: 서버 그룹과 구성원(areas/professor/groups-server.tsx).
  if (backendStatus() !== "off") return frame("팀 프로젝트", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "그룹을 만들고 수강생을 넣거나 옮깁니다. 학생은 자기 그룹과 구성원 이름만 봅니다." }, serverTeams(ctx, courseOf(ctx)));
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const rows = teamsOf(ctx.state).filter((t) => matchesQuery(ctx, `${t.name} ${t.topic}`));
  const records = rows.map((t) => ({
    sort: { name: t.name, topic: t.topic, size: t.size },
    cells: [t.name, t.topic, `${t.size}명`],
  }));
  return frame("팀 프로젝트", ctx, { course: true, kicker: course.ko, sub: "팀을 만들면 학생 그룹활동에 반영되는 샘플입니다." },
    records.length ? dataTable("teams", "팀", [
      { key: "name", label: "팀", sort: true, lock: true },
      { key: "topic", label: "주제", sort: true },
      { key: "size", label: "인원", sort: true },
    ], records, ctx) : <EmptyState title="팀이 없습니다" body="아래에서 샘플 팀을 만듭니다." />,
    formSheet("팀 만들기",
      <Field label="팀 이름" error={err.name}><input data-name="name" /></Field>,
      <Field label="주제" error={err.topic}><input data-name="topic" /></Field>,
      <Field label="인원" error={err.size}><input type="number" data-name="size" min="2" max="6" defaultValue="4" /></Field>,
      <div className="pf-actions span-2"><Button kind="primary" onClick={saveTeam}>팀 만들기</Button></div>,
    ),
  );
}

function monitor(ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  // Backend mode: pending enrollment and audit requests from the server (approve/reject with `version`).
  // The roster comes from GET /api/courses/{id}/students (areas/professor/enrollments-server.tsx); the sample learner table is not shown here.
  if (backendStatus() !== "off") {
    return frame("수강생", ctx, { course: true, kicker: course.ko, sub: "ASTRA 서버의 수강생 명단과 수강·청강 신청입니다. 승인하면 학생의 수강이 바로 확정됩니다." },
      serverCourseRoster(ctx, course),
      serverEnrollmentRequests(ctx, course));
  }
  const all = learners();
  const records = peopleRows(ctx, (p, i) => {
    const progress = 50 + all.findIndex((row) => row.id === p.id) * 6;
    const day = `2026-11-${String(10 - (i % 4)).padStart(2, "0")}`;
    return {
      sort: { name: p.name, no: p.no, at: day, progress },
      cells: [p.name, p.no, `${day} 0${i}:12`, meter(progress)],
    };
  });
  return frame("수강생", ctx, { course: true, kicker: course.ko, sub: "최근 접속과 진도는 샘플입니다." },
    <div className="pf-toolbar">{finder(ctx, "이름, 학번")}</div>,
    records.length ? dataTable("monitor", "수강생", [
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "no", label: "학번", sort: true },
      { key: "at", label: "최근 접속", sort: true },
      { key: "progress", label: "진도", sort: true },
    ], records, ctx) : <EmptyState title="조건에 맞는 수강생이 없습니다" body="검색어를 지워 보세요." />,
  );
}

function saveTa() {
  const state = getState();
  const data = readForm();
  const name = text(data.name).trim();
  if (!name) return showErrors({ name: "이름을 입력하세요" });
  state.tas.push({ name, role: text(data.role) || "조교", courseId: state.courseId });
  state.toast = "추가했습니다";
  commit();
}

function ta(ctx: ScreenCtx): ReactNode {
  // Backend mode: course settings = the server syllabus editor; TA assignment has no server API.
  if (serverRoom(courseOf(ctx))) {
    return frame("과목 설정", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "강의계획서와 내 강사 정보를 ASTRA 서버에 저장합니다. 조교·팀티칭 지정은 서버에 아직 없습니다." },
      serverSyllabusEditor(courseOf(ctx)),
      <section className="sheet" aria-label="조교 · 팀티칭">
        <h2>조교 · 팀티칭</h2>
        <EmptyState title="조교 지정은 아직 ASTRA 서버에 없습니다" body="이 과목의 조교와 팀티칭 담당자를 보고 바꾸는 기능이 서버에 생기면 여기에서 관리합니다. 이 브라우저에만 남는 샘플 조교는 서버 과목에서 보이지 않습니다." />
      </section>,
    );
  }
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const rows = ctx.state.tas.filter((t) => !t.courseId || t.courseId === course.id).filter((t) => matchesQuery(ctx, `${t.name} ${t.role}`));
  const records = rows.map((t) => ({
    sort: { name: t.name, role: t.role },
    cells: [t.name, <Badge tone={t.role === "조교" ? "neutral" : "success"}>{t.role}</Badge>],
  }));
  return frame("조교 · 팀티칭", ctx, { course: true, kicker: course.ko, sub: "이 과목의 조교와 팀티칭을 샘플로 관리합니다." },
    records.length ? dataTable("tas", "조교", [
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "role", label: "역할", sort: true },
    ], records, ctx) : <EmptyState title="등록된 조교가 없습니다" body="아래에서 추가할 수 있습니다." />,
    formSheet("추가",
      <Field label="이름" error={err.name}><input data-name="name" /></Field>,
      <Field label="역할"><select data-name="role"><option value="조교">조교</option><option value="팀티칭">팀티칭</option></select></Field>,
      <div className="pf-actions span-2"><Button kind="primary" onClick={saveTa}>추가</Button></div>,
    ),
  );
}

function VideoBody({ activity }: { activity: Activity }) {
  const media = mediaForActivity(activity);
  const closed = isClosedActivity(activity);
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    if (!closed && activity.fileId) bindUploadedVideo(ref.current, activity);
  }, [activity, closed]);
  return (
    <div className="stack">
      <video ref={ref} className="pf-lecture" poster={mediaFile(media.id, "jpg")} playsInline controls={closed ? undefined : true} aria-label={activity.title}>
        {closed ? null : <source src={mediaFile(media.id, "webm")} type="video/webm" />}
        {closed ? null : <source src={mediaFile(media.id, "mp4")} type="video/mp4" />}
      </video>
      <p className="pf-sub">{closed ? "마감된 영상은 재생할 수 없습니다." : (activity.fileId ? "등록한 영상입니다. 재생할 수 없으면 샘플 영상으로 바꿉니다." : `샘플 영상 · ${media.course} ${media.week}`)}</p>
      {activity.body ? <p>{activity.body}</p> : null}
      <p className="pf-meta">{`출석 ${activity.attendStart || activity.start || "—"} – ${activity.attendEnd || activity.due || "—"}`}</p>
    </div>
  );
}

function zoomBody(activity: Activity): ReactNode {
  return (
    <div className="stack">
      <p>
        {activity.meetingUrl
          ? <a className="pf-meeting" href={activity.meetingUrl} rel="noopener noreferrer">{activity.meetingUrl}</a>
          : <span>{activity.meeting || "회의 링크 없음"}</span>}
      </p>
      <p className="pf-meta">{`${activity.start || activity.when || "—"} – ${activity.end || "—"}`}</p>
      {activity.body ? <p>{activity.body}</p> : null}
    </div>
  );
}

function downloadActivityFile(id: string, name: string) {
  getActivityFile(id).then((blob) => {
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
  }).catch(() => {});
}

type PdfSlot =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "missing" }
  | { kind: "error" }
  | { kind: "found"; record: CoursePdfRecord };

function MaterialBody({ activity, course }: { activity: Activity; course: Course }) {
  const remoteId = isRemotePdfId(activity.pdfId);
  const offline = remoteId && backendStatus() !== "ready";
  const [slot, setSlot] = useState<PdfSlot>({ kind: activity.pdfId && !offline ? "loading" : "idle" });
  useEffect(() => {
    if (!activity.pdfId || offline) return;
    let live = true;
    listCoursePdfs(course.id).then((records) => {
      if (!live) return;
      const record = records.find((item) => item.id === activity.pdfId);
      setSlot(record ? { kind: "found", record } : { kind: "missing" });
    }).catch(() => {
      if (live) setSlot({ kind: "error" });
    });
    return () => {
      live = false;
    };
  }, [activity.pdfId, course.id, offline]);
  let pdf: ReactNode = null;
  if (offline) {
    pdf = <><p className="pf-sub">ASTRA 서버에 등록한 PDF입니다. 서버에 로그인하면 자료와 쪽별 요약을 볼 수 있습니다.</p></>;
  } else if (slot.kind === "loading") pdf = <p className="pf-sub">PDF를 불러오는 중입니다.</p>;
  else if (slot.kind === "missing") pdf = <p>{remoteId ? "ASTRA 서버에서 이 PDF를 찾지 못했습니다. 이 과목의 담당 교수 계정인지 확인하세요." : "저장된 PDF를 찾지 못했습니다."}</p>;
  else if (slot.kind === "error") pdf = <p>PDF를 불러오지 못했습니다.</p>;
  else if (slot.kind === "found") {
    const record = slot.record;
    pdf = record.remote
      ? <><p className="pf-meta">{`${record.fileName} · ${sizeLabel(record.size)} · ASTRA 서버 자료`}</p>{summaryJobPanel(record)}</>
      : (
        <>
          <p className="pf-meta">{`${record.fileName} · ${record.pageCount}쪽`}</p>
          {record.pages.slice(0, 12).map((page) => (
            <article key={page.number} className="pf-pdf-page">
              <h3>{`${page.number}쪽`}</h3>
              <p>{page.text || "이 쪽에서 추출한 텍스트가 없습니다."}</p>
            </article>
          ))}
        </>
      );
  }
  const fileId = activity.fileId;
  return (
    <div className="stack">
      {activity.body ? <p>{activity.body}</p> : null}
      {activity.fileName ? <p className="pf-meta">{activity.fileName}</p> : null}
      {fileId ? <Button kind="secondary" onClick={() => downloadActivityFile(fileId, activity.fileName || "file")}>다운로드</Button> : null}
      <div className="pf-pdf" data-pdf={activity.pdfId || ""}>{pdf}</div>
    </div>
  );
}

function postBody(activity: Activity): ReactNode {
  return <div className="pf-prose" data-post={activity.id}>{richNodes(activity.body || "")}</div>;
}

const MINE = ["bi", "db", "sec"];

function activityFrom(ctx: ScreenCtx): Activity | null {
  const id = param(ctx, "n");
  return (id && activities.find((item) => item.id === id)) || null;
}

function backRoom(course: Course): ReactNode {
  return linkBtn(pf("pf_room_week", { kj: course.id }), "강의실");
}

function courseList(): ReactNode {
  const ready = backendStatus() === "ready";
  return (
    <section data-area="professor">
      <header className="pf-head">
        <div className="pf-title">
          <Breadcrumbs items={[{ label: "교수 홈", href: pf("pf_main") }, { label: "강의과목" }]} />
          <h1>강의과목</h1>
          <p className="pf-sub">{ready ? "ASTRA 서버에서 이 계정이 담당하는 과목입니다." : "담당 과목만 모았습니다. 강의실과 학습실 모두 교수 화면입니다."}</p>
        </div>
      </header>
      {/* Backend `ready`: the courses this account teaches, from GET /api/me/courses. */}
      {ready ? serverTaughtCourses(courseCard) : <div className="cards">{MINE.map((id) => sampleCourseCard(courseById(id)))}</div>}
    </section>
  );
}

function studyRoom(ctx: ScreenCtx): ReactNode {
  // Backend mode: taught courses, the course's AI consent and server materials (areas/professor/materials-server.tsx).
  if (backendStatus() !== "off") {
    return serverStudyRoom(ctx, summaryJobPanel, {
      frame: (course: Course | null, body: ReactNode) => frame("학습실", ctx, { course: Boolean(course), kicker: course ? course.ko : "교수", sub: "담당 과목의 서버 자료와 쪽별 요약, 외부 AI 처리 동의를 봅니다.", actions: course ? [backRoom(course)] : [] }, body),
    });
  }
  const kj = param(ctx, "kj");
  const course = kj ? courseById(kj) : null;
  if (!course || !MINE.includes(course.id)) {
    return frame("학습실", ctx, { kicker: "교수", sub: "담당 과목의 영상과 자료 미리보기입니다. 학생 학습실로 넘어가지 않습니다." },
      <div className="cards">
        {MINE.map((id) => {
          const c = courseById(id);
          return (
            <Link key={c.id} className="course-card" href={routeFor("professor", "study", { kj: c.id })}>
              <div className={`cover ${c.gradient}`}><CoverImage course={c} /><b>{c.cardTitle || c.ko}</b></div>
              <div className="meta"><div>자료·영상</div></div>
            </Link>
          );
        })}
      </div>,
    );
  }
  const mine = activities.filter((a) => a.courseId === course.id && ["video", "material", "report", "test"].includes(a.type));
  return frame("학습실", ctx, {
    course: true,
    kicker: course.ko,
    sub: "항목을 열면 출석 통계, 제출 현황, 자료 미리보기로 이동합니다.",
    actions: [backRoom(course)],
  },
  mine.length ? <div className="pf-activities">{mine.map((a) => activityCard(a, ctx))}</div> : <EmptyState title="등록된 자료가 없습니다" body="자료 등록에서 PDF를 올릴 수 있습니다." />,
  activityDeleteDialog(ctx),
  );
}

function watchRate(person: Person, activity: Activity | null): number {
  if (!activity) return 0;
  if (activity.type !== "video" && activity.type !== "zoom") return activity.status === "done" || activity.status === "graded" ? 100 : 35;
  if (activity.status === "expired") return Math.max(20, (SMART[person.id] || 70) - 30);
  if (activity.status === "done") return 100;
  return SMART[person.id] != null ? SMART[person.id] : 40 + (person.name.length % 4) * 12;
}

function lecturePreview(ctx: ScreenCtx): ReactNode {
  // Backend: the server lecture, its items and per-student progress (areas/professor/lectures-server.tsx).
  const serverCourse = courseById(param(ctx, "kj") || "bi");
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || serverRoom(serverCourse))) {
    return frame("강의 미리보기", ctx, { course: true, kicker: serverCourse.ko, sub: "콘텐츠의 영상 상태와 수강생별 학습 인정 진도입니다. 진도는 서버가 적립한 시간으로만 셉니다.", actions: [backRoom(serverCourse), linkBtn(pf("pf_attend", { kj: serverCourse.id }), "출석부", "primary")] },
      serverLectureDetail(ctx, serverCourse));
  }
  const activity = activityFrom(ctx) || activities.find((item) => item.courseId === (param(ctx, "kj") || "bi") && item.type === "video") || null;
  const course = courseById((activity && activity.courseId) || param(ctx, "kj") || "bi");
  if (!activity) return frame("강의 미리보기", ctx, { course: true, kicker: course.ko }, <EmptyState title="강의를 찾지 못했습니다" body="주차 목록에서 다시 선택해 주세요." />);
  const rows = learners().map((person) => ({ person, rate: watchRate(person, activity) }));
  const avg = Math.round(rows.reduce((sum, row) => sum + row.rate, 0) / rows.length);
  const present = rows.filter((row) => row.rate >= 90).length;
  const stageTitle = activity.type === "survey" ? "응답 현황" : activity.type === "zoom" ? "실시간 강의" : "강의 미리보기";
  return frame(activity.title, ctx, {
    course: true,
    kicker: `${course.ko} · ${stageTitle}`,
    sub: activity.type === "video"
      ? "학생 출석 인정 흐름 대신 차시별 시청 통계를 봅니다. 재생 위치는 이 브라우저에만 있습니다."
      : "학생 화면의 참여 버튼 없이 현황만 확인합니다.",
    actions: [backRoom(course), linkBtn(pf("pf_attend", { kj: course.id }), "출석부", "primary")],
  },
  activity.type === "video" ? <VideoBody activity={activity} /> : activity.type === "zoom" ? zoomBody(activity) : activity.type === "post" ? postBody(activity) : null,
  <div className="pf-split">
    <div className="pf-stage" aria-label="미리보기">
      <p className="pf-kicker">{typeLabel(activity.type)}</p>
      <strong>{activity.title}</strong>
      <p>{activity.type === "zoom" ? (activity.when || activity.start || "일정 미정") : `${activity.minutes || activity.week || 0}${activity.minutes ? "분" : "주차"}`}</p>
      <p className="pf-sub">{activity.type === "post" ? "글 본문은 위에 있습니다." : (activity.body || "샘플 차시입니다. 원본 영상 파일은 이 미리보기에 넣지 않습니다.")}</p>
    </div>
    <div className="stack">
      <div className="stat-row cols-3">
        {stat("수강생", String(rows.length))}
        {stat(activity.type === "survey" ? "응답" : "평균", activity.type === "survey" ? `${present}명` : `${avg}%`)}
        {stat(activity.type === "video" ? "출석 인정" : "완료", `${present}명`)}
      </div>
      <div className="sheet">
        <h2>수강생별 현황</h2>
        {rows.map((row) => (
          <div key={row.person.id} className="pf-watch">
            <span>{row.person.name}</span>
            {meter(row.rate)}
          </div>
        ))}
      </div>
    </div>
  </div>,
  );
}

/** 응시 현황 and 시험 채점 in backend mode: server attempts and short-answer grading. */
function serverExamFrame(ctx: ScreenCtx, course: Course, title: string, body: ReactNode): ReactNode {
  return frame(title, ctx, {
    course: true,
    kicker: course.ko,
    sub: "ASTRA 서버의 응시입니다. 응시 마감과 접수 시각은 서버가 정하고, 선택 문항은 서버가 채점합니다.",
    actions: [
      linkBtn(pf("pf_exam_create", { kj: course.id }), "시험 출제"),
      linkBtn(pf(title === "시험 채점" ? "pf_exam_status" : "pf_exam_grade", { kj: course.id, n: param(ctx, "n") }), title === "시험 채점" ? "응시 현황" : "채점"),
    ],
  }, body);
}

/** 제출 현황 and 과제 채점 in backend mode: server submissions, score release and grading. */
function serverStatusFrame(ctx: ScreenCtx, course: Course, title: string, body: ReactNode): ReactNode {
  return frame(title, ctx, {
    course: true,
    kicker: course.ko,
    sub: "ASTRA 서버의 제출물입니다. 제출 시각과 지각 여부는 서버가 접수할 때 정합니다.",
    actions: [
      linkBtn(pf("pf_report_create", { kj: course.id }), "과제 출제"),
      linkBtn(pf(title === "과제 채점" ? "pf_report_status" : "pf_report_grade", { kj: course.id, n: param(ctx, "n") }), title === "과제 채점" ? "제출 현황" : "채점"),
    ],
  }, body);
}

function submissions(ctx: ScreenCtx): ReactNode {
  // A backend assignment (`n=srv-<id>`, e.g. from the student detail by role switch).
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || serverRoom(courseById(param(ctx, "kj") || "bi")))) {
    const course = courseById(param(ctx, "kj") || "bi");
    return serverStatusFrame(ctx, course, "제출 현황", serverSubmissionStatus(ctx, course));
  }
  const activity = activityFrom(ctx) || activities.find((item) => item.courseId === "bi" && item.type === "report") || null;
  const course = courseById((activity && activity.courseId) || "bi");
  const all = learners().map((person) => ({ person, sub: submissionOf(ctx.state, person, activity?.id) }));
  const records = all.map(({ person, sub }) => ({
    sort: { no: person.no, name: person.name, status: sub ? "제출" : "미제출", at: sub ? sub.at : "" },
    cells: [person.no, person.name, statusBadge(sub ? "제출" : "미제출", Boolean(sub)), sub ? sub.at : "—", sub ? (sub.fileName || "첨부") : "—"],
  }));
  return frame(activity ? activity.title : "과제", ctx, {
    course: true,
    kicker: "과제 제출",
    sub: "학생 제출 화면이 아닙니다. 제출물과 미제출을 함께 봅니다.",
    actions: [
      backRoom(course),
      linkBtn(pf("pf_report_grade", { kj: course.id }), "채점", "primary"),
    ],
  },
  <div className="stat-row cols-3">
    {stat("대상", String(all.length))}
    {stat("제출", String(all.filter((row) => row.sub).length))}
    {stat("미제출", String(all.filter((row) => !row.sub).length))}
  </div>,
  dataTable("activity-submissions", "과제 제출", [
    { key: "no", label: "학번", sort: true },
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "at", label: "시각", sort: true },
    { key: "file", label: "파일" },
  ], records, ctx),
  );
}

function quizDetail(ctx: ScreenCtx): ReactNode {
  // Backend: the server quiz with its questions (areas/professor/quizzes-server.tsx).
  const serverCourse = courseById(param(ctx, "kj") || "bi");
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || serverRoom(serverCourse))) {
    return frame("시험 상세", ctx, { course: true, kicker: serverCourse.ko, sub: "ASTRA 서버의 시험 설정과 문항입니다. 응시와 채점은 응시 현황에서 봅니다.", actions: [backRoom(serverCourse), linkBtn(pf("pf_exam_status", { kj: serverCourse.id, n: param(ctx, "n") }), "응시 현황", "primary")] },
      serverQuizDetail(ctx, serverCourse));
  }
  const activity = activityFrom(ctx) || activities.find((item) => item.type === "test") || null;
  const course = courseById((activity && activity.courseId) || param(ctx, "kj") || "bi");
  const questions = ctx.state.questions.slice(0, 3);
  const attempts = [
    { name: "김서연", status: "응시", score: "24" },
    { name: "박민준", status: "응시", score: "21" },
    { name: "이지은", status: "미응시", score: "—" },
    { name: "이도윤", status: "미응시", score: "—" },
  ];
  return frame(activity ? activity.title : "시험", ctx, {
    course: true,
    kicker: "시험 상세",
    sub: "학생 응시 화면 대신 문항과 응시 현황을 봅니다.",
    actions: [backRoom(course), linkBtn(pf("pf_exam_status", { kj: course.id }), "응시 현황", "primary")],
  },
  <div className="stat-row cols-3">
    {stat("문항", String(questions.length))}
    {stat("응시", String(attempts.filter((row) => row.status === "응시").length))}
    {stat("배점", activity && activity.points != null ? `${activity.points}점` : "—")}
  </div>,
  activity && activity.body ? <p className="pf-sub">{activity.body}</p> : null,
  questions.map(questionCard),
  dataTable("quiz-attempts", "응시", [
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "score", label: "점수", sort: true },
  ], attempts.map((row) => ({
    sort: { name: row.name, status: row.status, score: row.score === "—" ? null : Number(row.score) },
    cells: [row.name, statusBadge(row.status, row.status === "응시"), row.score],
  })), ctx),
  );
}

function pdfPreview(ctx: ScreenCtx): ReactNode {
  // Backend: one server material, its files, preview, page summaries and AI consent (areas/professor/materials-server.tsx).
  const serverCourse = courseById(param(ctx, "kj") || "bi");
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || param(ctx, "material") || serverRoom(serverCourse))) {
    return frame("자료 미리보기", ctx, { course: true, kicker: serverCourse.ko, sub: "ASTRA 서버에 등록한 자료입니다. PDF는 쪽별 요약을 만들고 게시할 수 있습니다.", actions: [backRoom(serverCourse), linkBtn(pf("pf_material_write", { kj: serverCourse.id }), "자료 등록")] },
      serverMaterialDetail(ctx, serverCourse, summaryJobPanel));
  }
  const activity = activityFrom(ctx);
  const course = courseById((activity && activity.courseId) || param(ctx, "kj") || "bi");
  const fileId = param(ctx, "n");
  const file: { id?: string; name: string; size: string; week: number } = files.find((item) => item.id === fileId) || files.find((item) => item.courseId === course.id) || { name: activity ? `${activity.title}.pdf` : "week5-cases.pdf", size: "1.2 MB", week: activity ? activity.week : 5 };
  const siblings = files.filter((item) => item.courseId === course.id);
  return frame(file.name, ctx, {
    course: true,
    kicker: "자료 미리보기",
    sub: "학생용 문서 뷰어가 아닙니다. 올린 자료의 이름과 주차만 확인합니다.",
    actions: [backRoom(course), linkBtn(pf("pf_material_write", { kj: course.id }), "자료 등록")],
  },
  activity && (activity.pdfId || activity.fileId || activity.body) ? <MaterialBody activity={activity} course={course} /> : null,
  <div className="pf-split">
    <div className="pf-stage pf-sheet" aria-label="PDF 미리보기">
      <p className="pf-kicker">PDF</p>
      <strong>{file.name}</strong>
      <p>{`${file.size || ""}${file.week ? ` · ${file.week}주차` : ""}`}</p>
      <p className="pf-sub">페이지 1. 원문 문장은 이 샘플에 넣지 않았습니다.</p>
    </div>
    <div className="sheet">
      <h2>같은 과목 자료</h2>
      {(siblings.length ? siblings : [file]).map((item, index) => (
        <Link key={index} className="list-row" href={routeFor("professor", "pdf", { n: item.id || fileId || "f1", kj: course.id })}>
          <b>{item.name}</b>
          <span className="tiny">{item.size || ""}</span>
        </Link>
      ))}
    </div>
  </div>,
  );
}

function noticeBoard(ctx: ScreenCtx): ReactNode {
  // Backend: there is no site-wide notice list for professors; the taught courses' notices (notices-server.tsx).
  if (backendStatus() !== "off") {
    return frame("알림마당", ctx, { kicker: "교수", sub: "담당 과목의 강의실 공지를 보고 고치거나 지웁니다." }, serverCourseNotices(ctx));
  }
  const id = param(ctx, "id");
  // 알림마당은 사이트 공지만: 과목 공지는 각 강의실 공지사항에 있다.
  const rows = notices.filter((notice) => notice.scope === "global" && notice.audience !== "student");
  const open = rows.find((notice) => notice.id === id);
  return frame("알림마당", ctx, { kicker: "교수", sub: "교수에게 보이는 공지만 모았습니다." },
    open ? (
      <article className="sheet">
        <h2>{open.title}</h2>
        <p className="pf-sub">{`${open.date} · ${open.author}`}</p>
        <p>{open.body}</p>
        {linkBtn(pf("pf_notice"), "목록")}
      </article>
    ) : null,
    <div className="stack">
      {rows.map((notice) => (
        <Link key={notice.id} className="list-row" href={pf("pf_notice", { id: notice.id })}>
          <span><b>{notice.title}</b><div className="tiny">{notice.body}</div></span>
          <span className="tiny">{notice.date}</span>
        </Link>
      ))}
    </div>,
  );
}

function qnaBoard(ctx: ScreenCtx): ReactNode {
  const course = courseOf(ctx);
  // Backend mode: the server Q&A of this course; the detail and answer form open over it (`?pop=qna`).
  if (backendStatus() !== "off") {
    return frame("질의응답", ctx, { course: true, kicker: course.ko, sub: "비공개 질문과 익명 작성자 이름도 담당 교수에게는 보입니다.", actions: [backRoom(course)] },
      qnaSection(ctx, course, "pf_qna"));
  }
  const posts = boardRows({ ...ctx.screen, board: "course-qna", course: course.id }, ctx.state);
  return frame("질의응답", ctx, {
    course: true,
    kicker: course.ko,
    sub: "질문 목록은 교수 강의실에 남습니다.",
    actions: [backRoom(course)],
  },
  <div className="stack">
    {posts.map((post) => (
      <article key={post.id} className="sheet">
        <h2>{post.title}</h2>
        <p className="pf-sub">{`${post.author || "수강생"} · ${post.at || ""}`}</p>
        <p>{post.body || ""}</p>
      </article>
    ))}
  </div>,
  );
}

function calendarBoard(ctx: ScreenCtx): ReactNode {
  // Backend: the professor's calendar and week timetable from the server (areas/professor/calendar-server.tsx).
  if (backendStatus() !== "off") {
    return frame("일정", ctx, { kicker: "교수", sub: "학사일정과 담당 과목 활동의 마감, 이번 학기 수업시간표입니다. 날짜는 서버 기준입니다." }, serverProfessorCalendar(ctx));
  }
  const day = param(ctx, "day");
  const rows = events.filter((ev) => !day || ev.date === day);
  return frame("일정", ctx, {
    kicker: "교수",
    sub: day ? day : "일정의 활동은 교수용 미리보기·제출·시험 화면으로 열립니다.",
    actions: day ? [linkBtn(routeFor("professor", "calendar"), "전체 일정")] : [],
  },
  rows.length ? (
    <div className="stack">
      {rows.map((ev, index) => {
        const activity = ev.activityId ? activities.find((item) => item.id === ev.activityId) : null;
        const href = activity
          ? routeFor("professor", "activity", { activity, kj: activity.courseId })
          : routeFor("professor", "calendar", { day: ev.date });
        return (
          <Link key={index} className="list-row" href={href}>
            <span><b>{ev.label || ev.title || "일정"}</b><div className="tiny">{activity ? typeLabel(activity.type) : "안내"}</div></span>
            <span className="tiny">{ev.date}</span>
          </Link>
        );
      })}
    </div>
  ) : <EmptyState title="일정이 없습니다" body="다른 날짜를 선택해 보세요." />,
  );
}

export function render(screen: Screen, ctx: ScreenCtx): ReactNode {
  return renderProf(screen, ctx);
}
