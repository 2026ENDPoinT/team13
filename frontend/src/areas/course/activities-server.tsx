// 백엔드 모드의 학생 강의실(40·72), 학습활동 목록(50–56), 파일모음(46). 한 과목의 서버 활동을
// shared/course-activities-remote.ts가 모은 행으로만 그린다(주차 목록, 자료, 영상과 본인 진도, 과제·시험·설문과
// 본인 상태). 연결된 과목과 `anon`에서는 샘플 활동을 섞지 않는다. 진행 중·미제출 필터는 서버 상태(phase)만 본다.
// 강의실에는 그 과목의 최근 공지와 할 일(`/api/me/todo?courseId=`)도 서버에서 받아 보인다. 파일모음은 과목 파일
// 목록(`GET …/files`)이다. 서버 글은 텍스트로만 넣는다.
import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { Link } from "../../router.tsx";
import { Badge, Button, EmptyState, ErrorState, LoadingState, Pagination } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { CoverImage } from "../../ui/assets.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { ApiError } from "../../shared/api.ts";
import { freshVisit, useRefreshOnFocus, useResource, useWatch } from "../../shared/remote-cache.ts";
import { backendStatus, courseIdFor, roleIn, serverCourseOf } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { TYPE_COLOR } from "../../shared/format.ts";
import {
  activityParts, loadPart, activitySnapshot, passServerFilter, reloadFailed, serverOwnsCourse, PART_LABEL,
  type ActivityPart, type ActivityRow, type ActivitySnapshot,
} from "../../shared/course-activities-remote.ts";
import { rememberCourseFiles } from "../../shared/course-pdfs-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { noticeLists, listKey as noticeListKey, noticeMessage } from "../../shared/notices-remote.ts";
import { useCourseTodo, reloadCourseTodo, todoHref, todoLabel, todoKind, ddayAt, koDateTime, koDate, homeMessage } from "../../shared/home-remote.ts";
import { serverActivityHref } from "../../shared/activity-links.ts";
import { windowText, lectureMessage, PROGRESS_STATE, ACTIVITY_PHASE, type CourseWeek } from "../../shared/lectures-remote.ts";
import { materialMessage, courseFiles, filesKey, loadMoreFiles, type CourseFiles, type MaterialFile } from "../../shared/materials-remote.ts";
import { assignmentMessage, periodText, PHASE as REPORT_PHASE, STATUS as REPORT_STATUS } from "../../shared/assignments-remote.ts";
import { quizMessage, PHASE as QUIZ_PHASE, ATTEMPT_STATUS } from "../../shared/quizzes-remote.ts";
import { surveyMessage, PHASE as SURVEY_PHASE, STATE as SURVEY_STATE } from "../../shared/surveys-remote.ts";
import { pickWeek, roomFilter, toggleWeek, type HeadFn, type SearchBoxFn } from "./course.tsx";
import { RetryButton } from "./groups-server.tsx";

const PAGE = 8;
const STRIP_NOTICES = 3;
const STRIP_TODOS = 5;

const TYPE_LABEL: Record<string, string> = { video: "영상", material: "자료", report: "과제", test: "시험", survey: "설문" };
const ROOM_TYPES: [string, string][] = [["video", "영상"], ["material", "자료"], ["report", "과제"], ["test", "시험"], ["survey", "설문"]];

const MESSAGE: Record<string, (error: unknown) => string> = {
  weeks: lectureMessage, progress: lectureMessage, materials: materialMessage,
  assignments: assignmentMessage, quizzes: quizMessage, surveys: surveyMessage,
};

/** The fields of a row's server item (lecture, material, assignment, quiz, survey) that this screen reads. */
interface ServerItem {
  published?: boolean;
  phase?: string;
  status?: string;
  state?: string;
  examType?: string;
  submittedCount?: number;
  inProgressCount?: number;
  finishedCount?: number;
  respondentCount?: number;
  openAt?: string | null;
  closeAt?: string | null;
}

/** One lecture item of the student's own progress. */
interface ProgressItem {
  progress?: { status?: string; progressPercent?: number } | null;
}

/** 이 과목의 활동 화면을 서버가 맡는지: `anon`이거나 `ready`이고 서버에 그 과목이 있을 때. */
export function serverOwnsActivities(course: Course): boolean {
  return serverOwnsCourse(course.id);
}

const instructorIn = (kj: string) => roleIn(kj) === "INSTRUCTOR";

/**
 * Re-renders on any change of `parts`, re-reads them on focus, and loads all of them on a new
 * history entry (else only the missing ones).
 */
function useActivityParts(parts: ActivityPart[], scope: string): void {
  const subscribe = useMemo(() => (listener: () => void) => {
    const offs = parts.map((entry) => entry.resource.subscribe(listener));
    return () => offs.forEach((off) => off());
  }, [parts]);
  useSyncExternalStore(subscribe, () => parts.map((entry) => entry.resource.version()).join(","));
  useRefreshOnFocus(() => parts.forEach((entry) => void loadPart(entry)));
  useWatch(parts);
  useEffect(() => {
    const fresh = freshVisit(scope, parts.map((entry) => entry.key).join("|")) === "entry";
    for (const entry of parts) if (fresh || !entry.resource.peek(entry.key)) void loadPart(entry);
  });
}

/** 주 범위 "11월 3일 – 11월 9일"(서버 LocalDate). 없으면 빈 글. */
function weekRange(week: { startsOn?: string | null; endsOn?: string | null } | null | undefined): string {
  const part = (value: string | null | undefined) => {
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value || ""));
    return match ? `${Number(match[2])}월 ${Number(match[3])}일` : "";
  };
  const start = part(week && week.startsOn);
  const end = part(week && week.endsOn);
  return start && end ? `${start} – ${end}` : start || end;
}

function periodOf(row: ActivityRow): string {
  // The row's item is the server record of its type; this screen reads only ServerItem fields.
  const item = row.item as ServerItem;
  if (row.type === "report") return periodText(item);
  if (row.type === "video") return windowText(item);
  if (row.type === "material") return row.openAt ? `${koDateTime(row.openAt)} 공개` : "";
  if (!row.closeAt) return row.openAt ? `${koDateTime(row.openAt)} ~` : "";
  return row.openAt ? `${koDateTime(row.openAt)} ~ ${koDateTime(row.closeAt)}` : `~ ${koDateTime(row.closeAt)}`;
}

function lectureState(row: ActivityRow, item: ServerItem, instructor: boolean): string {
  // 서버 시계의 구간. 진행 중이 아니면 앞에 붙인다.
  const phase = row.phase && row.phase !== "open" ? ACTIVITY_PHASE[row.phase] || "" : "";
  if (instructor) return [item.published ? "" : "비공개", phase].filter(Boolean).join(" · ");
  if (!row.progress) return phase;
  // Progress entries are the student's own lecture items.
  const progress = row.progress as ProgressItem[];
  if (!progress.length) return [phase, "학습 기록 없음"].filter(Boolean).join(" · ");
  const met = progress.filter((entry) => entry.progress && entry.progress.status === "met").length;
  if (met === progress.length) return [phase, PROGRESS_STATE.met].filter(Boolean).join(" · ");
  const percent = Math.round(progress.reduce((sum, entry) => sum + (Number(entry.progress && entry.progress.progressPercent) || 0), 0) / progress.length);
  return [phase, `진도 ${percent}% · 인정 기준 충족 ${met}/${progress.length}`].filter(Boolean).join(" · ");
}

/** 행 오른쪽의 상태 글. 학생은 본인 상태와 서버 phase, 담당자는 서버의 수. */
function stateText(row: ActivityRow, instructor: boolean): string {
  // The row's item is the server record of its type; this screen reads only ServerItem fields.
  const item = row.item as ServerItem;
  const status = item.status || "";
  const phaseKey = item.phase || "";
  if (row.type === "video") return lectureState(row, item, instructor);
  if (row.type === "material") {
    // 시작 전 자료는 담당자에게만 온다.
    return [row.phase === "not_open" ? ACTIVITY_PHASE.not_open : "", row.files && row.files.length ? `파일 ${row.files.length}개` : ""].filter(Boolean).join(" · ");
  }
  if (row.type === "report") {
    if (!instructor) return item.phase === "open" ? REPORT_STATUS[status] || status : `${REPORT_STATUS[status] || status} · ${REPORT_PHASE[phaseKey] || phaseKey}`;
    return [`제출 ${item.submittedCount ?? 0}`, item.published ? null : "비공개", REPORT_PHASE[phaseKey] || phaseKey].filter(Boolean).join(" · ");
  }
  if (row.type === "test") {
    const phase = QUIZ_PHASE[phaseKey] || phaseKey;
    if (!instructor) {
      const own = item.examType === "offline" ? "오프라인" : ATTEMPT_STATUS[status] || status;
      return item.phase === "open" ? own : `${own} · ${phase}`;
    }
    return [`응시 중 ${item.inProgressCount ?? 0}`, `종료 ${item.finishedCount ?? 0}`, item.published ? null : "비공개", phase].filter(Boolean).join(" · ");
  }
  if (row.type === "survey") {
    const phase = SURVEY_PHASE[phaseKey] || phaseKey;
    const state = item.state || "";
    if (!instructor) return item.phase === "open" ? SURVEY_STATE[state] || state : `${SURVEY_STATE[state] || state} · ${phase}`;
    return [`응답 ${item.respondentCount ?? 0}`, item.published ? null : "비공개", phase].filter(Boolean).join(" · ");
  }
  return "이 화면에서 열 수 없는 활동";
}

/** 활동 한 줄. 볼 화면이 있으면 링크, 없으면 비활성 줄이다. */
function rowNode(row: ActivityRow, kj: string): ReactNode {
  const type = row.type === "other" ? "post" : row.type;
  const label = row.type === "other" ? todoLabel(row.kind) : TYPE_LABEL[row.type];
  const period = periodOf(row);
  const children = (
    <>
      <span className="swatch" style={{ background: TYPE_COLOR[type as keyof typeof TYPE_COLOR] || "var(--surface-2)" }} />
      <span><b>{row.title}</b><div className="tiny">{[label, `${row.weekNo}주`, period].filter(Boolean).join(" · ")}</div></span>
      <span className="tiny">{row.done ? <span className="check" aria-hidden="true">✓ </span> : null}{stateText(row, instructorIn(kj))}</span>
    </>
  );
  return row.href
    ? <Link key={row.key} className="activity" data-type={type} data-row={row.key} data-activity={row.key} href={row.href}>{children}</Link>
    : <div key={row.key} className="activity" data-type={type} data-row={row.key} data-activity={row.key} aria-disabled="true">{children}</div>;
}

/** 실패한 부분 목록 안내와 다시 받기. 받아 둔 내용이 있으면 그것은 그대로 보인다. */
function failureNotes(snapshot: ActivitySnapshot, parts: ActivityPart[]): ReactNode {
  if (!snapshot.failures.length) return null;
  return (
    <div className="err crs-srv-fail" role="alert">
      <p>일부 활동을 불러오지 못했습니다.</p>
      <ul>
        {snapshot.failures.map((failure) => {
          const say = MESSAGE[failure.name] || lectureMessage;
          return <li key={failure.name}>{`${PART_LABEL[failure.name] || failure.name}: ${failure.error instanceof ApiError && failure.error.status === 404 ? "볼 수 없습니다." : say(failure.error)}`}</li>;
        })}
      </ul>
      <RetryButton reload={() => reloadFailed(parts)} />
    </div>
  );
}

function loginPanel(text: string): ReactNode {
  return <section className="crs-week open">{backendPanel({ compact: true, text })}</section>;
}

// ---------- 강의실 ----------

function NoticeStrip({ kj, courseId }: { kj: string; courseId: number | string }) {
  const key = noticeListKey(courseId, "", 1, STRIP_NOTICES);
  const load = () => void noticeLists.load(key, courseId, "", 1, STRIP_NOTICES);
  const entry = useResource(noticeLists, key, load);
  useEffect(() => {
    if (freshVisit("course-room-notices", key) === "entry" || !noticeLists.peek(key)) void noticeLists.load(key, courseId, "", 1, STRIP_NOTICES);
  });
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    body = !entry || !entry.error
      ? <LoadingState title="공지를 불러오는 중입니다" />
      : <ErrorState title="공지를 불러오지 못했습니다" body={noticeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const items = Array.isArray(data.items) ? data.items : [];
    body = (
      <>
        {entry.error ? <p className="err" role="alert">{noticeMessage(entry.error)}</p> : null}
        {items.length ? items.map((notice) => (
          <Link key={notice.noticeId} className="crs-strip-row" href={hrefFor(find("44d_room_notice_view_pop"), { kj, id: String(notice.noticeId) })}>
            {notice.read ? <span className="crs-strip-dot read" aria-hidden="true" /> : <span className="crs-strip-dot" role="img" aria-label="읽지 않음" />}
            <span className="crs-strip-title">{notice.pinned ? <Badge tone="neutral">고정</Badge> : null}<b>{notice.title}</b></span>
            <span className="tiny">{koDate(notice.publishedAt)}</span>
          </Link>
        )) : <p className="tiny">등록된 공지가 없습니다.</p>}
      </>
    );
  }
  return (
    <section className="crs-strip" aria-label="최근 공지">
      <div className="crs-strip-h"><h2>공지</h2><Link href={hrefFor(find("44_room_notice_list"), { kj })} className="more-link">전체 보기</Link></div>
      <div className="crs-strip-body" aria-live="polite">{body}</div>
    </section>
  );
}

/** 이 과목의 할 일(`GET /api/me/todo?courseId=`의 첫 쪽과 남은 수). 전체 목록은 할 일 팝업이다. */
function TodoStrip({ courseId }: { courseId: number | string }) {
  const entry = useCourseTodo(courseId);
  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    body = !entry || !entry.error
      ? <LoadingState title="할 일을 불러오는 중입니다" />
      : <ErrorState title="할 일을 불러오지 못했습니다" body={homeMessage(entry.error)} actions={<RetryButton reload={() => reloadCourseTodo(courseId)} />} />;
  } else {
    body = (
      <>
        {entry.error ? <p className="err" role="alert">{homeMessage(entry.error)}</p> : null}
        {data.items.length ? data.items.slice(0, STRIP_TODOS).map((item) => {
          const href = todoHref(item, null);
          const due = ddayAt(item.closeAt, data.serverTime);
          const children = (
            <>
              <span className="crs-kind">{todoLabel(item.type)}</span>
              <span className="crs-strip-title"><b>{item.title}</b></span>
              <span className="tiny">{[due, koDateTime(item.closeAt)].filter(Boolean).join(" · ")}</span>
            </>
          );
          return href
            ? <Link key={item.activityId} className="crs-strip-row" href={href} data-todo={todoKind(item.type)}>{children}</Link>
            : <div key={item.activityId} className="crs-strip-row" data-todo={todoKind(item.type)}>{children}</div>;
        }) : <p className="tiny">남은 할 일이 없습니다.</p>}
      </>
    );
  }
  return (
    <section className="crs-strip" aria-label="이 과목의 할 일">
      <div className="crs-strip-h">
        <h2>{"할 일 "}<span className="count-num">{data ? String(data.total) : ""}</span></h2>
        <Link href={hrefFor(find("09_home_todo_more"))} className="more-link">모두 보기</Link>
      </div>
      <div className="crs-strip-body" aria-live="polite">{body}</div>
    </section>
  );
}

function heroOf(course: Course, kj: string, rows: ActivityRow[]): ReactNode {
  const server = serverCourseOf(kj);
  const name = server ? server.name : course.ko;
  const meta = server
    ? [server.term && server.term.name, [server.code, server.section].filter(Boolean).join("-")].filter(Boolean).join(" · ")
    : "";
  const tags: string[] = [];
  if (server && server.type === "NONREGULAR") tags.push("비교과");
  if (rows.some((row) => row.type === "video")) tags.push("온라인");
  if (server && Array.isArray(server.schedules) && server.schedules.some((slot) => slot.room)) tags.push("대면");
  if (server && server.credit) tags.push(`${server.credit}학점`);
  const next = rows.find((row) => row.href && row.missing) || rows.find((row) => row.href && row.ongoing && !row.done);
  return (
    <article className={`crs-hero ${course.gradient || "g3"}`}>
      <CoverImage course={course} width={1110} height={188} />
      <div className="crs-hero-copy">
        <p className="crs-kicker">{server && server.term ? server.term.name : ""}</p>
        <div className="course-name">
          <h1 className="course-title">{name}</h1>
          <span className="course-prof">{server && Array.isArray(server.instructors) ? server.instructors.join(", ") : ""}</span>
        </div>
        {meta ? <p className="crs-hero-meta">{meta}</p> : null}
        <div className="crs-tags">{tags.map((tag) => <span key={tag} className="crs-tag">{tag}</span>)}</div>
      </div>
      {next && next.href ? <Link href={next.href} className="btn btn-ondark">이어서 학습하기</Link> : null}
    </article>
  );
}

function chip(key: string, label: string, kind: "type" | "status", on: boolean): ReactNode {
  return (
    <button key={key} type="button" className={`chip ${kind === "type" ? key : ""}${on ? " on" : ""}`.trim()} aria-pressed={on ? "true" : "false"} onClick={() => roomFilter(kind, key)}>
      {label}
    </button>
  );
}

function planLink(kj: string): ReactNode {
  return (
    <Link className="crs-plan" href={hrefFor(find("40b_room_lecplan_pop"), { kj })}>
      <span className="crs-plan-label"><Icon name="file" /><span>강의계획서</span></span>
      <Icon name="chev" />
    </Link>
  );
}

/** 백엔드 모드의 강의실 본문(40·72). */
export function remoteRoom(screen: Screen, ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") {
    return (
      <section className="crs" data-area="course">
        {planLink(course.id)}
        {loginPanel("ASTRA 서버에 로그인하면 이 과목의 주차와 활동, 공지, 할 일을 서버에서 불러옵니다.")}
      </section>
    );
  }
  return <RemoteRoom screen={screen} ctx={ctx} course={course} />;
}

function RemoteRoom({ screen, ctx, course }: { screen: Screen; ctx: ScreenCtx; course: Course }) {
  const kj = course.id;
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = useMemo(() => (courseId == null ? [] : activityParts(courseId, { instructor })), [courseId, instructor]);
  useActivityParts(parts, "course-room");
  const draft = ctx.state.draft || {};
  const type: string = screen.focus === "video" ? "video" : (draft.roomType || "");
  const status: string = draft.roomStatus || "";
  const closed = new Set<number>(draft.closedWeeks || []);
  const server = serverCourseOf(kj);
  const unit = server && server.type === "NONREGULAR" ? "회차" : "주";
  const snapshot = activitySnapshot(parts, kj, { instructor });
  const { rows, weeks } = snapshot;
  const present = new Set(rows.map((row) => row.type));
  const week = ctx.state.week || 1;

  let body: ReactNode;
  if (snapshot.denied) body = <EmptyState title="이 과목의 활동을 볼 수 없습니다" body="수강 중인 과목인지 확인해 주세요." />;
  else if (snapshot.pending && !rows.length) body = <LoadingState title="주차와 활동을 불러오는 중입니다" />;
  else {
    const shown = rows.filter((row) => (!type || row.type === type) && (!status || passServerFilter(row, status)));
    const byWeek = new Map<number, ActivityRow[]>();
    for (const row of shown) {
      const n = Number(row.weekNo);
      byWeek.set(n, [...(byWeek.get(n) || []), row]);
    }
    const sections = [...byWeek.keys()].sort((a, b) => a - b).map((n) => {
      const entry = weeks.find((item: CourseWeek) => item.weekNo === n);
      const isClosed = closed.has(n);
      return (
        <section key={n} className={`crs-week${isClosed ? "" : " open"}${week === n ? " current" : ""}`}>
          <button type="button" className="crs-week-h" aria-expanded={isClosed ? "false" : "true"} onClick={() => toggleWeek(n)}>
            <span className="crs-week-label">
              <strong>{`${n}${unit}${entry && entry.title ? ` ${entry.title}` : ""}`}</strong>
              <span>{weekRange(entry)}</span>
            </span>
            <Icon name="chev" />
          </button>
          {isClosed ? null : <div className="crs-week-b">{(byWeek.get(n) || []).map((row) => rowNode(row, kj))}</div>}
        </section>
      );
    });
    body = (
      <>
        {failureNotes(snapshot, parts)}
        {sections}
        {sections.length ? null : rows.length
          ? <EmptyState title="해당하는 활동이 없습니다" body="다른 종류나 진행 상태를 선택해 보세요." />
          : snapshot.pending ? <LoadingState title="주차와 활동을 불러오는 중입니다" /> : <EmptyState title="등록된 활동이 없습니다" body="교수님이 활동을 공개하면 주차별로 표시됩니다." />}
      </>
    );
  }

  return (
    <section className="crs" aria-label="ASTRA 서버 강의실" data-area="course">
      <div className="crs-srv-hero">{heroOf(course, kj, rows)}</div>
      {planLink(kj)}
      <div className="crs-strips">
        {courseId == null ? null : <NoticeStrip kj={kj} courseId={courseId} />}
        {courseId == null ? null : <TodoStrip courseId={courseId} />}
      </div>
      <div className="crs-filters">
        <div className="crs-filter-row" role="toolbar" aria-label="활동 종류">
          {ROOM_TYPES.filter(([key]) => present.has(key) || type === key).map(([key, label]) => chip(key, label, "type", type === key))}
        </div>
        <div className="crs-filter-row" role="toolbar" aria-label="진행 상태">
          {chip("ongoing", "진행중", "status", status === "ongoing")}
          {chip("missing", "미제출", "status", status === "missing")}
        </div>
      </div>
      <div className="crs-progress" role="tablist" aria-label={`${unit}차`} hidden={!weeks.length}>
        {weeks.map((entry) => {
          const n = entry.weekNo;
          const tracked = rows.filter((row) => row.weekNo === n && ["video", "report", "test", "survey"].includes(row.type));
          const done = tracked.filter((row) => row.done).length;
          const markText = !tracked.length || !done ? "" : done === tracked.length ? "✓" : `${done}/${tracked.length}`;
          return (
            <button key={n} type="button" className={`crs-wk${week === n ? " on" : ""}`} aria-pressed={week === n ? "true" : "false"} aria-label={`${n}${unit}${entry.title ? ` ${entry.title}` : ""}`} onClick={() => pickWeek(n)}>
              <span>{String(n)}</span>
              <span className="crs-wk-mark">{markText || " "}</span>
            </button>
          );
        })}
      </div>
      <div className="crs-srv-weeks" aria-live="polite">{body}</div>
    </section>
  );
}

// ---------- 학습활동 목록 ----------

/** 백엔드 모드의 학습활동 목록 본문(50–56): 서버 활동 전체를 필터에 맞게 주차별로, 8개씩 쪽을 나눈다. */
export function remoteActivityList(screen: Screen, ctx: ScreenCtx, course: Course, filter: string): ReactNode {
  if (backendStatus() === "anon") return loginPanel("ASTRA 서버에 로그인하면 이 과목의 학습활동을 서버에서 불러옵니다.");
  return <RemoteActivityList screen={screen} ctx={ctx} kj={course.id} filter={filter} />;
}

function RemoteActivityList({ screen, ctx, kj, filter }: { screen: Screen; ctx: ScreenCtx; kj: string; filter: string }) {
  const courseId = courseIdFor(kj);
  const instructor = instructorIn(kj);
  const parts = useMemo(() => (courseId == null ? [] : activityParts(courseId, { instructor })), [courseId, instructor]);
  useActivityParts(parts, "course-activity-list");
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const pageHref = (n: number) => hrefFor(find(screen.id), n > 1 ? { kj, page: String(n) } : { kj });
  const snapshot = activitySnapshot(parts, kj, { instructor });
  const rows = snapshot.rows.filter((row) => passServerFilter(row, filter));
  let content: ReactNode;
  if (snapshot.denied) content = <EmptyState title="이 과목의 활동을 볼 수 없습니다" body="수강 중인 과목인지 확인해 주세요." />;
  else if (snapshot.pending && !rows.length) content = <LoadingState title="학습활동을 불러오는 중입니다" />;
  else {
    const pages = Math.max(1, Math.ceil(rows.length / PAGE));
    const current = Math.min(pages, page);
    const slice = rows.slice((current - 1) * PAGE, current * PAGE);
    const weeks = [...new Set(slice.map((row) => Number(row.weekNo)))].sort((a, b) => a - b);
    content = (
      <>
        {failureNotes(snapshot, parts)}
        {!slice.length
          ? snapshot.pending
            ? <LoadingState title="학습활동을 불러오는 중입니다" />
            : <EmptyState title="해당하는 활동이 없습니다" body={filter === "all" ? "교수님이 활동을 공개하면 이 목록에 표시됩니다." : "다른 필터를 선택해 보세요."} />
          : (
            <>
              {weeks.map((n) => {
                const entry = snapshot.weeks.find((item: CourseWeek) => item.weekNo === n);
                return (
                  <section key={n} className="crs-week open">
                    <h2 className="crs-rail-week">{[`${n}주`, weekRange(entry)].filter(Boolean).join("  ")}</h2>
                    {slice.filter((row) => Number(row.weekNo) === n).map((row) => rowNode(row, kj))}
                  </section>
                );
              })}
              <Pagination page={current} pages={pages} hrefForPage={pageHref} />
            </>
          )}
      </>
    );
  }
  return <div className="crs-srv-list" aria-live="polite" aria-label="ASTRA 서버 학습활동">{content}</div>;
}

// ---------- 파일모음 ----------

function extensionOf(file: Partial<MaterialFile>): string {
  const ext = String(file.extension || String(file.name || "").split(".").pop() || "").toLowerCase();
  return ext ? ext.toUpperCase() : "파일";
}

/** 검색어를 고친 뒤 서버에 묻기까지 기다리는 시간(ms). 그 사이 다시 고치면 앞 요청은 보내지 않는다. */
const SEARCH_DELAY = 300;

interface FilesParts {
  head: HeadFn;
  searchBox: SearchBoxFn;
  query: string;
}

/**
 * 백엔드 모드의 파일모음(46): `GET …/files?q=&cursor=&size=`. 학습활동에 첨부된 파일을 주차·활동 순으로 서버가 나눈 쪽으로
 * 받고, 검색(`q`, 파일 이름·활동 제목)도 서버가 한다. "더 보기"는 `nextCursor`로 다음 쪽을 붙인다. 내려받기는 첨부 원본
 * Blob, 미리보기는 서버 미리보기 화면이다.
 */
export function remoteFiles(ctx: ScreenCtx, course: Course, { head, searchBox, query }: FilesParts): ReactNode {
  if (backendStatus() === "anon") {
    return (
      <section className="crs" data-area="course">
        {head("파일모음", "file", null)}
        {loginPanel("ASTRA 서버에 로그인하면 이 과목의 파일을 서버에서 불러옵니다.")}
      </section>
    );
  }
  return <RemoteFiles ctx={ctx} kj={course.id} head={head} searchBox={searchBox} query={query} />;
}

function MoreFiles({ courseId, q, data, busy }: { courseId: number | string; q: string; data: CourseFiles; busy: boolean }) {
  const [pressed, setPressed] = useState(false);
  const disabled = busy || pressed;
  return (
    <div className="btn-row">
      <Button
        kind="secondary"
        disabled={disabled}
        aria-busy={disabled ? "true" : undefined}
        onClick={() => {
          if (disabled) return;
          setPressed(true);
          void loadMoreFiles(courseId, q);
        }}
      >
        {`더 보기 (${data.items.length}/${data.total})`}
      </Button>
    </div>
  );
}

function RemoteFiles({ ctx, kj, head, searchBox, query }: { ctx: ScreenCtx; kj: string; head: HeadFn; searchBox: SearchBoxFn; query: string }) {
  const courseId = courseIdFor(kj);
  const q = String(query || "").trim().slice(0, 100);
  const key = courseId == null ? null : filesKey(courseId, q);
  const load = () => {
    if (key != null && courseId != null) void courseFiles.load(key, courseId, q, null);
  };
  // 포커스 복귀 때 첫 쪽만 다시 받으면 "더 보기"로 붙인 쪽이 사라지므로, 새로 읽는 것은 화면 이동과 검색어 변경 때다.
  const entry = useResource(courseFiles, key);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (key == null || courseId == null) return;
    const fresh = freshVisit("course-files", key);
    if (fresh !== "entry" && courseFiles.peek(key)) return;
    const run = () => void courseFiles.load(key, courseId, q, null);
    // 검색어를 고치는 동안은 잠시 기다렸다가 묻는다. 그 사이 화면이 다시 그려지면(다음 글자) 이 요청은 보내지 않는다.
    if (fresh === "key") setTimeout(() => { if (live.current) run(); }, SEARCH_DELAY);
    else run();
  });
  const data = entry && entry.data;
  // 미리보기 화면의 제목과 요약 게시 여부가 이 목록의 레코드를 쓴다.
  const remembered = useRef<CourseFiles | null>(null);
  useEffect(() => {
    if (data && courseId != null && remembered.current !== data) {
      remembered.current = data;
      rememberCourseFiles(data.items, kj, courseId);
    }
  });

  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="파일을 불러오는 중입니다" />;
    else if (entry.error instanceof ApiError && entry.error.status === 404) body = <EmptyState title="이 과목의 파일을 볼 수 없습니다" body="수강 중인 과목인지 확인해 주세요." />;
    else body = <ErrorState title="파일을 불러오지 못했습니다" body={materialMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const stale = entry.error ? <p className="err" role="alert">{materialMessage(entry.error)}</p> : null;
    if (!data.items.length) {
      body = (
        <>
          {stale}
          {q
            ? <EmptyState title="검색 결과가 없습니다" body="파일 이름이나 활동 제목의 일부만 입력해 보세요." />
            : <EmptyState title="파일이 없습니다" body="학습활동에 파일이 첨부되면 주차별로 모입니다." />}
        </>
      );
    } else {
      const blocks: { weekNo: number; rows: CourseFiles["items"] }[] = [];
      for (const row of data.items) {
        const last = blocks[blocks.length - 1];
        if (!last || last.weekNo !== row.weekNo) blocks.push({ weekNo: row.weekNo, rows: [row] });
        else last.rows.push(row);
      }
      body = (
        <>
          {stale}
          {blocks.map((block, index) => {
            const first = block.rows[0];
            return (
              <section key={`${block.weekNo}-${index}`} className="crs-week open">
                <h2 className="crs-rail-week">{[`${block.weekNo}주`, first.weekTitle || "", weekRange(first)].filter(Boolean).join("  ")}</h2>
                {block.rows.map((row) => {
                  const file: Partial<MaterialFile> = row.file || {};
                  const preview = hrefFor(find("65_file_preview"), { n: `srv-${file.fileId}`, kj });
                  const detail = serverActivityHref(kj, row.activityType, row.activityId);
                  return (
                    <article key={`${row.activityId}-${file.fileId}`} className="crs-file" data-file={String(file.fileId)}>
                      <span className="crs-kind">{extensionOf(file)}</span>
                      <div className="crs-file-main">
                        <h3>{file.name}</h3>
                        <p className="tiny">
                          {sizeText(file.sizeBytes)}
                          {" · "}
                          {detail ? <Link href={detail} className="crs-file-activity">{row.activityTitle}</Link> : <span>{row.activityTitle}</span>}
                        </p>
                      </div>
                      <div className="crs-file-actions">
                        <Link className="icon-btn" href={preview} aria-label={`${file.name} 미리보기`}><Icon name="search" /></Link>
                        {file.fileId == null ? null : downloadControl(courseId, { fileId: file.fileId, name: file.name || "" })}
                      </div>
                    </article>
                  );
                })}
              </section>
            );
          })}
          {data.nextCursor && courseId != null ? <MoreFiles key={data.items.length} courseId={courseId} q={q} data={data} busy={Boolean(entry.loading)} /> : null}
        </>
      );
    }
  }
  return (
    <section className="crs" data-area="course">
      {head("파일모음", "file", data ? <Badge tone="neutral">{String(data.total)}</Badge> : null)}
      <div className="crs-toolbar">
        <p className="crs-note">이 과목 학습활동에 첨부된 파일을 주차별로 모았습니다.</p>
        {searchBox("파일 검색", ctx, "files")}
      </div>
      <div className="crs-files" aria-live="polite">{body}</div>
    </section>
  );
}
