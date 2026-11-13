import type { ReactNode } from "react";
import { files, weekCount, courseById, activitiesOf } from "../../data.ts";
import type { AppState, Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { getState, save, render as repaint } from "../../store.ts";
import { Link, closeDialog } from "../../router.tsx";
import { readForm, showErrors } from "../../forms.ts";
import { Badge, Button, EmptyState, LoadingState, Pagination, Table, Tabs, Field } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { passFilter, attendStatus, activityHref, typeLabel, boardRows, ownsRow } from "../../shared/format.ts";
import type { BoardRow } from "../../shared/format.ts";
import { activityRow } from "../../shared/activity-row.tsx";
import { readQuery, writeQuery } from "../../shared/query.tsx";
import { remoteNoticeBoard } from "./notices-server.tsx";
import { backendStatus } from "../../shared/backend.ts";
import { remoteRoom, remoteActivityList, remoteFiles, serverOwnsActivities } from "./activities-server.tsx";
import { remoteQnaBoard, remoteOpenBoard } from "./boards-server.tsx";
import { remoteGrades } from "./grades-server.tsx";
import { remoteGroups } from "./groups-server.tsx";

/** The title row: `count` is a badge (string/number) or a ready node; `actions` go to the right. */
export type HeadFn = (title: string, iconName: string, count: ReactNode, ...actions: ReactNode[]) => ReactNode;
/** The search field of `scope` (without the `course:` prefix). */
export type SearchBoxFn = (label: string, ctx: ScreenCtx, scope: string) => ReactNode;

export interface CourseParts {
  head: HeadFn;
  searchBox: SearchBoxFn;
  /** The full query scope (`course:…`) of the screen. */
  scope: string;
}

interface Team {
  name: string;
  topic: string;
  size: number | string;
}

const PAGE = 8;

const ROOM_TYPES: [string, string][] = [
  ["video", "영상"],
  ["material", "자료"],
  ["report", "과제"],
  ["test", "시험"],
  ["zoom", "Zoom"],
  ["post", "글"],
  ["survey", "설문"],
];

const LIST_TYPES: [string, string, string][] = [
  ["all", "전체", "50_activity_all"],
  ["video", "영상", "51_activity_filter_video"],
  ["material", "자료", "52_activity_filter_material"],
  ["report", "과제", "53_activity_filter_report"],
  ["test", "시험", "54_activity_filter_test"],
];

const LIST_STATUS: [string, string, string][] = [
  ["ongoing", "진행중", "55_activity_filter_ongoing"],
  ["missing", "미제출", "56_activity_filter_not_submit"],
];

/** Sample board rows of `screen` without the posts removed here and with local edits applied. */
export function courseRows(screen: Screen, state: AppState): BoardRow[] {
  const draft = state.draft || {};
  const removed = new Set<string>(draft.removedPostIds || []);
  const overrides: Record<string, Partial<BoardRow>> = draft.postOverrides || {};
  return boardRows(screen, state)
    .filter((row) => !removed.has(row.id))
    .map((row) => (overrides[row.id] ? { ...row, ...overrides[row.id] } : row));
}

// ---------- actions ----------

function postErrors(data: Record<string, unknown>): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
  if (!String(data.body || "").trim() || String(data.body).trim().length < 4) errors.body = "내용을 4자 이상 입력하세요";
  return errors;
}

/** `week`: picks the current week. */
export function pickWeek(week: number) {
  const state = getState();
  state.week = Number(week) || 1;
  save();
  repaint();
}

/** `room-filter`: toggles the type or status chip of the room. */
export function roomFilter(kind: string, value: string) {
  const state = getState();
  const key = kind === "status" ? "roomStatus" : "roomType";
  state.draft[key] = state.draft[key] === value ? "" : value;
  save();
  repaint();
}

/** `toggle-week`: opens or closes one week section. */
export function toggleWeek(week: number) {
  const state = getState();
  const closed = new Set<number>(state.draft.closedWeeks || []);
  if (closed.has(week)) closed.delete(week);
  else closed.add(week);
  state.draft.closedWeeks = [...closed];
  save();
  repaint();
}

function clearQuery(scope: string) {
  writeQuery(getState(), scope || "course:course", "");
  save();
  repaint();
}

function gradeFilter(value: string) {
  const state = getState();
  state.draft.gradeKind = state.draft.gradeKind === value ? "all" : value;
  save();
  repaint();
}

/** `update-post`: saves an edited sample post from the open write dialog. */
export function updatePost(id: string) {
  const state = getState();
  const data = readForm();
  const errors = postErrors(data);
  if (Object.keys(errors).length) {
    showErrors(errors);
    return;
  }
  const next = { title: String(data.title).trim(), body: String(data.body).trim() };
  // AppState.posts is untyped; saved sample posts carry BoardRow fields.
  const post = (state.posts as BoardRow[]).find((item) => item.id === id);
  if (post) Object.assign(post, next);
  else state.draft.postOverrides = { ...(state.draft.postOverrides || {}), [id]: next };
  state.toast = "수정했습니다";
  save();
  closeDialog();
}

/** `delete-post`: removes a sample post; from a dialog it closes the dialog. */
export function deletePost(id: string, fromDialog: boolean) {
  const state = getState();
  // AppState.posts is untyped; saved sample posts carry BoardRow fields.
  state.posts = (state.posts as BoardRow[]).filter((item) => item.id !== id);
  const removed = new Set<string>(state.draft.removedPostIds || []);
  removed.add(id);
  state.draft.removedPostIds = [...removed];
  if (state.draft.postOverrides) delete state.draft.postOverrides[id];
  state.toast = "삭제했습니다";
  save();
  if (fromDialog) closeDialog();
  else repaint();
}

function fileSample() {
  getState().toast = "샘플 파일은 이 브라우저 밖으로 내려받지 않습니다";
  save();
  repaint();
}

function courseReload(kind: string) {
  const state = getState();
  state.draft.loading = kind;
  save();
  repaint();
  setTimeout(() => {
    const next = getState();
    if (!next.draft || next.draft.loading !== kind) return;
    next.draft.loading = "";
    save();
    repaint();
  }, 420);
}

function openTeamAsk() {
  getState().draft.teamOpen = true;
  save();
  repaint();
}

function saveTeamAsk() {
  const state = getState();
  const data = readForm();
  const text = String(data.teamAsk || "").trim();
  if (!text || text.length < 4) {
    showErrors({ teamAsk: "요청을 4자 이상 적어 주세요" });
    return;
  }
  state.teamAsk = text;
  state.draft.teamOpen = false;
  state.toast = "구성 요청을 저장했습니다";
  save();
  repaint();
}

// ---------- views ----------

function termLabel(term: string | undefined): string {
  // 서버 과목의 학기는 서버의 학기 이름 그대로다(shared/course-view.js).
  if (term && !/^\d{4}-\d+$/.test(term)) return term;
  const [year, sem] = String(term || "2026-2").split("-");
  return `${year}학년도 ${sem}학기`;
}

// 백엔드 `ready`인데 서버에 없는 과목(서버에 없는 샘플 ID, 모르는 kj): 공지·Q&A·성적과 같은 안내만 보이고 샘플 활동·파일을 그리지 않는다.
function absentState(what: string): ReactNode {
  return <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body={`서버에 등록된 과목의 ${what}만 볼 수 있습니다.`} />;
}

function weekRange(n: number): string {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d: Date) => `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return `${fmt(start)} – ${fmt(end)}`;
}

function excerpt(text: string, n = 96): string {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  if (value.length <= n) return value;
  return `${value.slice(0, n)}…`;
}

function courseScope(scope: string): string {
  return `course:${scope}`;
}

function queryOf(ctx: ScreenCtx, scope: string): string {
  return readQuery(ctx.state, courseScope(scope)).trim().toLowerCase();
}

function matches(q: string, ...parts: unknown[]): boolean {
  if (!q) return true;
  return parts.some((part) => String(part || "").toLowerCase().includes(q));
}

const searchBox: SearchBoxFn = (label, ctx, scope) => {
  const key = courseScope(scope);
  const value = readQuery(ctx.state, key);
  return (
    <div className="crs-search">
      <Icon name="search" />
      <input
        type="text"
        value={value}
        onChange={(event) => {
          writeQuery(getState(), key, event.target.value);
          repaint();
        }}
        placeholder="검색"
        aria-label={label}
        autoComplete="off"
      />
      {value ? <button type="button" className="crs-search-clear" aria-label="검색어 지우기" onClick={() => clearQuery(key)}>×</button> : null}
    </div>
  );
};

const head: HeadFn = (title, iconName, count, ...actions) => {
  const shown = actions.filter((action) => action != null && action !== false);
  return (
    <div className="crs-head">
      <div className="crs-head-title">
        <span className="crs-mark" aria-hidden="true"><Icon name={iconName} /></span>
        <h1>{title}</h1>
        {typeof count === "string" || typeof count === "number" ? <Badge tone="neutral">{String(count)}</Badge> : count}
      </div>
      {shown.length ? <div className="crs-head-actions">{shown}</div> : null}
    </div>
  );
};

function quiet(title: string, body: string, kind: string, action?: ReactNode): ReactNode {
  return (
    <div className="empty crs-empty" role="status">
      <div className="crs-empty-art" aria-hidden="true"><Icon name="layers" /></div>
      <strong>{title}</strong>
      {body ? <p>{body}</p> : null}
      <div className="btn-row">
        {action}
        <Button kind="ghost" onClick={() => courseReload(kind)}>다시 불러오기</Button>
      </div>
    </div>
  );
}

function weekMark(list: { week: number; status: string }[], week: number): string {
  const items = list.filter((item) => item.week === week);
  if (!items.length) return "";
  const done = items.filter((item) => item.status === "done" || item.status === "submitted" || item.status === "graded").length;
  if (!done) return "";
  if (done === items.length) return "✓";
  return `${done}/${items.length}`;
}

function roomCourse(screen: Screen, ctx: ScreenCtx): Course {
  const queryId = ctx.params && ctx.params.get("kj");
  if (queryId && courseById(queryId).id === queryId) return courseById(queryId);
  if (ctx.state.courseId && courseById(ctx.state.courseId).id === ctx.state.courseId) return courseById(ctx.state.courseId);
  return courseById(screen.course);
}

function renderRoom(screen: Screen, ctx: ScreenCtx): ReactNode {
  const course = roomCourse(screen, ctx);
  // 백엔드 모드: 연결된 과목은 서버 주차·활동·공지·할 일만 그린다(areas/course/activities-server.js).
  if (backendStatus() !== "off") {
    return serverOwnsActivities(course) ? remoteRoom(screen, ctx, course) : <section className="crs" data-area="course">{absentState("강의실")}</section>;
  }
  const all = activitiesOf(course.id);
  const draft = ctx.state.draft || {};
  const type: string = screen.focus === "video" ? "video" : (draft.roomType || "");
  const status: string = draft.roomStatus || "";
  let shown = all;
  if (type) shown = shown.filter((item) => item.type === type);
  if (status) shown = shown.filter((item) => passFilter(item, status, ctx.state));
  const week = ctx.state.week || 1;
  const unit = course.kind === "special" ? "회차" : "주";
  const next = all.find((item) => item.status === "ongoing") || all[0];
  const tags: string[] = [];
  if (course.kind === "special") tags.push("비교과", "온라인");
  else {
    if (all.some((item) => item.type === "video" || item.type === "zoom")) tags.push("온라인");
    if (course.room && course.room !== "e-learning") tags.push("대면");
  }
  if (course.credit) tags.push(`${course.credit}학점`);
  const presentTypes = new Set<string>(all.map((item) => item.type));
  const planHref = hrefFor(find("40b_room_lecplan_pop"), { kj: course.id });
  const closed = new Set<number>(draft.closedWeeks || []);
  const weeks = [...new Set(shown.map((item) => item.week))].sort((a, b) => a - b);
  return (
    <section className="crs" data-area="course">
      <article className="crs-hero">
        <div className="crs-hero-copy">
          <p className="crs-kicker">{termLabel(course.term)}</p>
          <div className="course-name">
            <h1 className="course-title">{course.ko}</h1>
            <span className="course-prof">{course.instructor}</span>
          </div>
          <p className="crs-hero-meta">{[course.en, course.time, course.code].filter(Boolean).join(" · ")}</p>
          <div className="crs-tags">{tags.map((tag) => <span key={tag} className="crs-tag">{tag}</span>)}</div>
        </div>
        {next ? <Link href={activityHref(next, course.id)} className="btn btn-primary">이어서 학습하기</Link> : null}
      </article>
      <Link className="crs-plan" href={planHref}>
        <span className="crs-plan-label"><Icon name="file" /><span>강의계획서</span></span>
        <Icon name="chev" />
      </Link>
      <div className="crs-filters">
        <div className="crs-filter-row" role="toolbar" aria-label="활동 종류">
          {ROOM_TYPES.filter(([key]) => presentTypes.has(key)).map(([key, label]) => (
            <button key={key} type="button" className={`chip ${key}${type === key ? " on" : ""}`} aria-pressed={type === key ? "true" : "false"} onClick={() => roomFilter("type", key)}>{label}</button>
          ))}
        </div>
        <div className="crs-filter-row" role="toolbar" aria-label="진행 상태">
          {[["ongoing", "진행중"], ["missing", "미제출"]].map(([key, label]) => (
            <button key={key} type="button" className={`chip${status === key ? " on" : ""}`} aria-pressed={status === key ? "true" : "false"} onClick={() => roomFilter("status", key)}>{label}</button>
          ))}
        </div>
      </div>
      <div className="crs-progress" role="tablist" aria-label="주차">
        {Array.from({ length: weekCount }, (_, index) => {
          const n = index + 1;
          const markText = weekMark(all, n);
          return (
            <button key={n} type="button" className={`crs-wk${week === n ? " on" : ""}`} aria-pressed={week === n ? "true" : "false"} aria-label={`${n}${unit}`} onClick={() => pickWeek(n)}>
              <span>{String(n)}</span>
              <span className="crs-wk-mark">{markText || " "}</span>
            </button>
          );
        })}
      </div>
      {!weeks.length ? quiet("해당하는 활동이 없습니다", "다른 종류나 진행 상태를 선택해 보세요.", "room") : weeks.map((n) => {
        const items = shown.filter((item) => item.week === n);
        const isClosed = closed.has(n);
        return (
          <section key={n} className={`crs-week${isClosed ? "" : " open"}${week === n ? " current" : ""}`}>
            <button type="button" className="crs-week-h" aria-expanded={isClosed ? "false" : "true"} onClick={() => toggleWeek(n)}>
              <span className="crs-week-label">
                <strong>{`${n}${unit}`}</strong>
                <span>{weekRange(n)}</span>
              </span>
              <Icon name="chev" />
            </button>
            {isClosed ? null : <div className="crs-week-b">{items.map((item) => <Row key={item.id}>{activityRow(item, ctx, course.id)}</Row>)}</div>}
          </section>
        );
      })}
    </section>
  );
}

/** A keyed wrapper-free slot for a ReactNode in a list. */
function Row({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

function renderActivityList(screen: Screen, ctx: ScreenCtx): ReactNode {
  // 샘플·백엔드 모드 모두 주소의 과목(kj)이다. 화면 표의 기본 과목(bi)을 쓰면 비정규과목의 필터 칩이 다른 과목으로 간다.
  const course = roomCourse(screen, ctx);
  // 백엔드 모드: 연결된 과목은 서버 활동만 필터에 맞춰 그린다(areas/course/activities-server.js).
  const server = backendStatus() !== "off" && serverOwnsActivities(course);
  const absent = backendStatus() !== "off" && !server;
  const filter = screen.filter || "all";
  const rows = server || absent ? [] : activitiesOf(course.id).filter((item) => passFilter(item, filter, ctx.state));
  const pages = Math.max(1, Math.ceil(rows.length / PAGE));
  const page = Math.min(pages, Math.max(1, Number(ctx.params && ctx.params.get("page")) || 1));
  const slice = rows.slice((page - 1) * PAGE, page * PAGE);
  const weeks = [...new Set(slice.map((item) => item.week))].sort((a, b) => a - b);
  let content: ReactNode;
  if (server) content = remoteActivityList(screen, ctx, course, filter);
  else if (absent) content = absentState("활동");
  else if (!slice.length) content = quiet("해당하는 활동이 없습니다", "다른 필터를 선택해 보세요.", "activity-list");
  else {
    content = weeks.map((n) => (
      <section key={n} className="crs-week open">
        <h2 className="crs-rail-week">{`${n}주  ${weekRange(n)}`}</h2>
        {slice.filter((item) => item.week === n).map((item) => <Row key={item.id}>{activityRow(item, ctx, course.id)}</Row>)}
      </section>
    ));
  }
  return (
    <section className="crs crs-split" data-area="course">
      <div className="crs-rail">
        <div className="crs-filters">
          <div className="crs-filter-row" role="toolbar" aria-label="활동 종류">
            {LIST_TYPES.map(([key, label, id]) => <Link key={key} href={hrefFor(find(id), { kj: course.id })} className={`chip ${key}${filter === key ? " on" : ""}`}>{label}</Link>)}
          </div>
          <div className="crs-filter-row" role="toolbar" aria-label="진행 상태">
            {LIST_STATUS.map(([key, label, id]) => <Link key={key} href={hrefFor(find(id), { kj: course.id })} className={`chip${filter === key ? " on" : ""}`}>{label}</Link>)}
          </div>
        </div>
        {content}
        {!server && !absent ? (
          <Pagination
            page={page}
            pages={pages}
            hrefForPage={(n) => hrefFor(find(screen.id), n > 1 ? { kj: course.id, page: String(n) } : { kj: course.id })}
          />
        ) : null}
      </div>
      <div className="crs-stage"><p>활동을 선택하세요.</p></div>
    </section>
  );
}

function renderGroups(ctx: ScreenCtx): ReactNode {
  // AppState.teams is untyped; sample teams carry these fields.
  const teams = (ctx.state.teams || []) as Team[];
  return (
    <section className="crs" data-area="course">
      {head("그룹활동", "users", teams.length || null)}
      {teams.length
        ? teams.map((team, index) => (
          <article key={index} className="crs-team">
            <h2>{team.name}</h2>
            <p>{team.topic}</p>
            <span className="tiny">{`인원 ${team.size}`}</span>
          </article>
        ))
        : quiet(
          "생성된 그룹이 없습니다.",
          "팀 프로젝트 등 그룹 활동이 열리면 이 목록에 표시됩니다.",
          "groups",
          ctx.state.draft.teamOpen ? null : <Button kind="primary" onClick={openTeamAsk}>구성 요청</Button>,
        )}
      {ctx.state.teamAsk ? (
        <div className="sheet crs-ask">
          <h2>구성 요청</h2>
          <p>{ctx.state.teamAsk}</p>
        </div>
      ) : ctx.state.draft.teamOpen ? (
        <div className="sheet crs-ask">
          <Field label="요청 내용" name="teamAsk"><textarea data-name="teamAsk" placeholder="희망 주제와 인원" /></Field>
          <Button kind="primary" onClick={saveTeamAsk}>요청 저장</Button>
        </div>
      ) : null}
    </section>
  );
}

function attendanceCounts(state: AppState) {
  const counts = { present: 0, late: 0, absent: 0, total: weekCount };
  for (let week = 1; week <= weekCount; week += 1) {
    const status = attendStatus(state, "minjun", week, "main");
    if (status === "late") counts.late += 1;
    else if (status === "absent") counts.absent += 1;
    else if (status === "present" || status === "excused") counts.present += 1;
  }
  return counts;
}

interface EvalItem {
  key: string;
  type: string;
  title: string;
  meta: string;
  score: string;
  done: boolean;
  filter: string;
}

function renderGrades(screen: Screen, ctx: ScreenCtx): ReactNode {
  // 백엔드 모드: 공개 여부는 서버가 항목마다 정하므로 세 경로가 같은 서버 화면이다(areas/course/grades-server.js).
  if (backendStatus() !== "off") return remoteGrades(screen, ctx, roomCourse(screen, ctx), { head, searchBox, scope: courseScope("grades") });
  const mode = screen.gradesMode || (ctx.state.gradePublic ? "public" : "partial");
  const kind: string = (ctx.state.draft && ctx.state.draft.gradeKind) || "all";
  const q = queryOf(ctx, "grades");
  const top = (
    <>
      {head("성적", "chart", null)}
      <Tabs items={[
        { label: "집계", href: hrefFor(find("43_room_eval_result")), on: mode === "partial" },
        { label: "공개", href: hrefFor(find("43b_room_eval_public")), on: mode === "public" },
        { label: "비공개", href: hrefFor(find("43c_room_eval_private")), on: mode === "private" },
      ]} />
    </>
  );
  if (mode === "private") {
    return (
      <section className="crs crs-grades" data-area="course">
        {top}
        {quiet("성적이 비공개입니다", "담당 교수가 공개로 전환하기 전에는 점수를 보여 주지 않습니다.", "grades")}
      </section>
    );
  }
  const counts = attendanceCounts(ctx.state);
  const mine = ctx.state.scores.minjun || {};
  const course = courseById(screen.course || ctx.state.courseId);
  const items: EvalItem[] = [];
  const attendanceScore = mode === "public" ? `${mine.att ?? "—"} / 10` : "공개 예정";
  items.push({ key: "att", type: "출석", title: "출석", meta: `${weekCount}주`, score: attendanceScore, done: mode === "public" && mine.att != null, filter: "att" });
  for (const activity of activitiesOf(course.id)) {
    if (activity.type !== "report" && activity.type !== "test") continue;
    let score = "—";
    let done = false;
    if (activity.type === "report") {
      if (ctx.state.submissions[activity.id]) {
        score = mode === "public" ? String(mine.rep || "채점 중") : "제출";
        done = true;
      } else score = "미제출";
    } else if (activity.score != null) {
      score = mode === "public" ? `${activity.score} / ${activity.points}` : String(activity.score);
      done = activity.status === "graded";
    }
    items.push({
      key: activity.id,
      type: typeLabel(activity.type),
      title: activity.title,
      meta: `${activity.week}주 · ${activity.due ? activity.due.slice(0, 10) : "일정 없음"}`,
      score,
      done,
      filter: activity.type,
    });
  }
  const visible = items.filter((item) => (kind === "all" || item.filter === kind) && matches(q, item.title, item.type));
  return (
    <section className="crs crs-grades" data-area="course">
      {top}
      <section className="crs-block">
        <h2>출석</h2>
        <p className="crs-note">출석 기준은 담당 교수의 설정을 따릅니다.</p>
        <Table headers={["전체", "출석", "지각", "결석"]} rows={[[String(counts.total), String(counts.present), String(counts.late), String(counts.absent)]]} />
      </section>
      <section className="crs-block">
        <div className="crs-toolbar">
          <h2>평가 항목</h2>
          {searchBox("평가 항목 검색", ctx, "grades")}
        </div>
        <div className="crs-filter-row" role="toolbar" aria-label="평가 종류">
          {[["all", "전체"], ["report", "과제"], ["test", "시험"]].map(([key, label]) => (
            <button key={key} type="button" className={`chip${kind === key ? " on" : ""}`} aria-pressed={kind === key ? "true" : "false"} onClick={() => gradeFilter(key)}>{label}</button>
          ))}
        </div>
        {visible.length ? (
          <div className="crs-eval">
            {visible.map((item) => (
              <article key={item.key} className="crs-eval-row">
                <span className="crs-kind">{item.type}</span>
                <div>
                  <h3>{item.title}</h3>
                  <p className="tiny">{item.meta}</p>
                </div>
                <span className="crs-eval-score">{item.score}</span>
                <span className={`crs-eval-check${item.done ? " done" : ""}`}>{item.done ? "✓" : ""}</span>
              </article>
            ))}
          </div>
        ) : quiet("해당하는 항목이 없습니다", "다른 종류를 선택하거나 검색어를 지워 보세요.", "grades")}
      </section>
    </section>
  );
}

function postCard(row: BoardRow, href: string, expand: boolean, editHref: string): ReactNode {
  const body = (
    <div>
      <h2>{row.title}</h2>
      <p>{expand ? (row.body || "") : excerpt(row.body || row.reply || "")}</p>
      <div className="crs-post-meta">
        {row.author ? <span>{row.author}</span> : null}
        <span>{row.date || row.at || ""}</span>
        {row.reply ? <span className="crs-kind">답변</span> : null}
      </div>
    </div>
  );
  return (
    <article key={row.id} className="crs-post">
      {href ? <Link className="crs-post-link" href={href}>{body}</Link> : body}
      {editHref ? (
        <div className="crs-post-actions">
          <Link href={editHref} className="btn btn-secondary">수정</Link>
          <Button kind="ghost" onClick={() => deletePost(row.id, false)}>삭제</Button>
        </div>
      ) : null}
    </article>
  );
}

function renderBoard(screen: Screen, ctx: ScreenCtx): ReactNode {
  if (screen.board === "course" && backendStatus() !== "off") {
    return remoteNoticeBoard(screen, ctx, roomCourse(screen, ctx), { head, searchBox, scope: courseScope("course") });
  }
  // Backend mode: Q&A and open board come from the server (areas/course/boards-server.js).
  if (screen.board === "course-qna" && backendStatus() !== "off") return remoteQnaBoard(screen, ctx, roomCourse(screen, ctx), { head, searchBox, scope: courseScope("course-qna") });
  if (screen.board === "open" && backendStatus() !== "off") return remoteOpenBoard(screen, ctx, roomCourse(screen, ctx), { head, searchBox, scope: courseScope("open") });
  const board = screen.board || "";
  const titles: Record<string, string> = { course: "공지사항", "course-qna": "질의응답", open: "열린게시판" };
  const icons: Record<string, string> = { course: "info", "course-qna": "chat", open: "layers" };
  const title = titles[board] || screen.title;
  const kj = roomCourse(screen, ctx).id;
  const all = courseRows({ ...screen, course: kj }, ctx.state);
  const q = queryOf(ctx, board);
  const rows = all.filter((row) => matches(q, row.title, row.body, row.author));
  const canWrite = board === "course-qna" || board === "open";
  const writeId = board === "course-qna" ? "45b_room_qna_insert_pop" : "47b_room_open_board_insert_pop";
  let content: ReactNode;
  if (!all.length) {
    const copy = board === "open"
      ? ["조회된 자료가 없습니다.", "첫 글을 남기면 이 목록에 표시됩니다."]
      : ["게시글이 없습니다.", canWrite ? "첫 글을 남기면 이 목록에 표시됩니다." : "등록된 자료가 없습니다."];
    content = quiet(copy[0], copy[1], "board");
  } else if (!rows.length) {
    content = quiet("검색 결과가 없습니다", "다른 단어로 찾아 보세요.", "board");
  } else {
    content = (
      <div className="crs-posts">
        {rows.map((row) => {
          let href = "";
          let editHref = "";
          // 수정·삭제는 작성자 본인의 글에만 붙인다.
          const own = ownsRow(row, ctx.state);
          if (board === "course") href = hrefFor(find("44d_room_notice_view_pop"), { id: row.id, kj });
          else if (board === "course-qna") {
            href = hrefFor(find("45d_room_qna_view_pop"), { id: row.id, kj });
            if (own) editHref = hrefFor(find("45b_room_qna_insert_pop"), { id: row.id, kj });
          } else if (board === "open" && own) editHref = hrefFor(find("47b_room_open_board_insert_pop"), { id: row.id, kj });
          return postCard(row, href, Boolean(screen.expand), editHref);
        })}
      </div>
    );
  }
  return (
    <section className="crs" data-area="course">
      {head(title, icons[board] || "info", all.length, canWrite ? <Link href={hrefFor(find(writeId), { kj })} className="btn btn-primary">{board === "open" ? "글쓰기" : "질문 작성"}</Link> : null)}
      <div className="crs-toolbar">
        <Tabs items={[{ label: `전체 ${all.length}`, on: true }]} />
        {searchBox(`${title} 검색`, ctx, board)}
      </div>
      {content}
    </section>
  );
}

function fileKind(name: string): string {
  const ext = (String(name).split(".").pop() || "").toLowerCase();
  if (ext === "pdf") return "자료";
  if (ext === "ppt" || ext === "pptx") return "영상";
  return "파일";
}

function renderFiles(screen: Screen, ctx: ScreenCtx): ReactNode {
  const linked = roomCourse(screen, ctx);
  // 백엔드 모드: 연결된 과목의 파일은 서버 자료의 첨부다(areas/course/activities-server.js).
  if (backendStatus() !== "off") {
    return serverOwnsActivities(linked)
      ? remoteFiles(ctx, linked, { head, searchBox, query: queryOf(ctx, "files") })
      : <section className="crs" data-area="course">{head("파일모음", "file", null)}{absentState("파일")}</section>;
  }
  const courseId = ctx.state.courseId;
  const course = courseById(courseId);
  const q = queryOf(ctx, "files");
  const all = files.filter((file) => file.courseId === courseId);
  const rows = all.filter((file) => matches(q, file.name, fileKind(file.name)));
  let content: ReactNode;
  if (!all.length) content = quiet("파일이 없습니다", "강의 자료가 올라오면 주차별로 모입니다.", "files");
  else if (!rows.length) content = quiet("검색 결과가 없습니다", "파일 이름의 일부만 입력해 보세요.", "files");
  else {
    const weeks = [...new Set(rows.map((file) => file.week))].sort((a, b) => a - b);
    content = weeks.map((week) => (
      <section key={week} className="crs-week open">
        <h2 className="crs-rail-week">{`${week}주  ${weekRange(week)}`}</h2>
        {rows.filter((item) => item.week === week).map((file) => {
          const href = hrefFor(find("65_file_preview"), { n: file.id, kj: course.id });
          return (
            <article key={file.id} className="crs-file">
              <span className="crs-kind">{fileKind(file.name)}</span>
              <div className="crs-file-main">
                <h3>{file.name}</h3>
                <p className="tiny">{`${file.size}${file.at ? ` · ${file.at}` : ""}`}</p>
              </div>
              <div className="crs-file-actions">
                <Link className="icon-btn" href={href} aria-label={`${file.name} 미리보기`}><Icon name="search" /></Link>
                <button type="button" className="icon-btn" data-file={file.name} aria-label={`${file.name} 내려받기`} onClick={fileSample}><Icon name="file" /></button>
              </div>
            </article>
          );
        })}
      </section>
    ));
  }
  return (
    <section className="crs" data-area="course">
      {head("파일모음", "file", all.length)}
      <div className="crs-toolbar">
        <p className="crs-note">주차별로 모은 강의 파일입니다.</p>
        {searchBox("파일 검색", ctx, "files")}
      </div>
      {content}
    </section>
  );
}

export function render(screen: Screen, ctx: ScreenCtx): ReactNode {
  if (ctx.state.draft && ctx.state.draft.loading === screen.kind) {
    return <section className="crs" data-area="course"><LoadingState title="불러오는 중" /></section>;
  }
  // 백엔드 모드: 서버 그룹(areas/course/groups-server.js).
  if (screen.kind === "groups" && backendStatus() !== "off") return remoteGroups(roomCourse(screen, ctx), head);
  switch (screen.kind) {
    case "room": return renderRoom(screen, ctx);
    case "activity-list": return renderActivityList(screen, ctx);
    case "groups": return renderGroups(ctx);
    case "grades": return renderGrades(screen, ctx);
    case "board": return renderBoard(screen, ctx);
    case "files": return renderFiles(screen, ctx);
    default: return <EmptyState title="이 화면은 강의실 영역이 아닙니다" body={screen.id} />;
  }
}
