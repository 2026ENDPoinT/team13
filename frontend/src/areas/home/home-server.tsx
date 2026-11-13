// 홈은 목업과 같은 구성으로 그리고, 로그인 상태의 값과 이동 대상은 ASTRA 서버에서 읽는다.
// Server text is only ever rendered as text.
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, GoButton } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Button, EmptyState } from "../../ui/kit.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { typeIcon } from "../../shared/format.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import {
  homeEntry, useHome, reloadHome, useTodo, reloadTodo, loadMoreTodo,
  memberCourses, todoKind, todoLabel, todoHref, ddayAt, koDateTime, homeMessage, wallMs, wallParts,
} from "../../shared/home-remote.ts";
import type { HomeData, TodoItem, TodoList } from "../../shared/home-remote.ts";
import type { Entry } from "../../shared/remote-cache.ts";
import { courseCards, type CourseStats } from "./course-cards.tsx";
import { serverCourseViews } from "../../shared/course-view.ts";
import { offerEntryNotices } from "./site-notices-server.tsx";
import { backendState } from "../../shared/backend.ts";
import { profiles, peekAccount } from "../../shared/account-remote.ts";
import { SummaryCard } from "./summary-card.tsx";
import type { Course } from "../../data.ts";


/** "다시 불러오기": disabled after the press until the view is redrawn. */
export function RetryButton({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return (
    <Button
      kind="ghost"
      disabled={pressed}
      onClick={() => {
        setPressed(true);
        reload();
      }}
    >
      다시 불러오기
    </Button>
  );
}

/** Login strip shown on home screens while the backend is `anon`. */
export function homeLoginPanel(compact: boolean): ReactNode {
  return backendPanel({ compact, text: "ASTRA 서버에 로그인하면 수강 과목, 새 공지, 할 일을 서버에서 불러옵니다." });
}

function waiting<T>(entry: Entry<T> | null): boolean {
  return !entry || (!entry.data && !entry.error);
}

const DAY = 86400000;
const DAY_LABELS = { due: "가장 가까운 마감", soon: "사흘 안 마감", report: "제출할 과제", week: "이번 주 학습 자료" };
type DayScope = keyof typeof DAY_LABELS;

/** 조회한 서버 목록만 분류한다. 다음 쪽이 남으면 전체 개수로 표시하지 않는다. */
function dayGroups(data: TodoList) {
  const now = wallMs(data.serverTime);
  const date = wallParts(data.serverTime);
  const today = date ? Date.UTC(date.y, date.m - 1, date.d) : NaN;
  const week = date ? today - date.dow * DAY : NaN;
  const upcoming = data.items.filter((item) => wallMs(item.closeAt) >= now);
  const nearest = upcoming.reduce((at, item) => Math.min(at, wallMs(item.closeAt)), Infinity);
  return {
    due: upcoming.filter((item) => wallMs(item.closeAt) === nearest),
    soon: upcoming.filter((item) => wallMs(item.closeAt) < today + 4 * DAY),
    report: data.items.filter((item) => todoKind(item.type) === "report"),
    week: data.items.filter((item) => ["video", "material"].includes(todoKind(item.type))
      && wallMs(item.closeAt) >= week && wallMs(item.closeAt) < week + 7 * DAY),
  };
}

function dayCount(data: TodoList | null | undefined, items: TodoItem[] | undefined): string {
  if (!data || !items) return "—";
  return data.nextCursor ? (items.length ? `${items.length}+` : "—") : String(items.length);
}

export function serverHomeGreeting(): ReactNode {
  return <ServerHomeGreeting />;
}

function ServerHomeGreeting() {
  const entry = useTodo();
  const data = entry?.data;
  const me = backendState().me;
  const profile = peekAccount(profiles)?.data;
  const name = me?.name || (profile && String(profile.userId) === String(me?.userId) ? profile.name : null) || "사용자";
  const soon = data ? dayGroups(data).soon : [];
  const date = wallParts(data?.serverTime);
  return (
    <div className="hero-greet">
      {soon.length
        ? <h1 className="hero-oneline">
          {`${name}님. 마감이 얼마 남지 않은 일이 `}
          <GoButton className="hero-count" href={hrefFor(find("01_pop_todo"), { "todo-view": "soon" })} title="사흘 안 마감 목록 열기">{`${dayCount(data, soon)}개`}</GoButton>
          {" 남아있습니다."}
        </h1>
        : <h1>{data && !data.nextCursor && date ? `${name}님, 급한 할 일은 없네요!` : `${name}님, 안녕하세요.`}</h1>}
      <p>{date ? `${date.y}년 ${date.m}월 ${date.d}일 ${"일월화수목금토"[date.dow]}요일` : "서버 학습 일정을 확인하세요."}</p>
    </div>
  );
}

export function serverMyDay(): ReactNode {
  return <ServerMyDay />;
}

function ServerMyDay() {
  const entry = useTodo();
  const data = entry?.data;
  const groups = data ? dayGroups(data) : null;
  const partial = data?.nextCursor ? " · 불러온 목록 기준" : "";
  const dateKnown = Boolean(wallParts(data?.serverTime));
  const count = (kind: DayScope) => dayCount(data, kind === "report" || dateKnown ? groups?.[kind] : undefined);
  return (
    <section className="my-day" aria-label="나의 하루">
      <div className="summary-strip">
        <SummaryCard kind="due" label={DAY_LABELS.due} value={count("due")} sub={`${groups?.due[0] ? `${koDateTime(groups.due[0].closeAt)}까지` : data && dateKnown && !data.nextCursor ? "예정된 마감 없음" : "서버 마감 일정"}${partial}`} unit="건" linkText="할 일 보기" />
        <SummaryCard kind="report" label={DAY_LABELS.report} value={count("report")} sub={`${groups?.report[0]?.closeAt ? `가장 빠른 마감 ${koDateTime(groups.report[0].closeAt)}` : data && !data.nextCursor ? "남은 과제 없음" : "서버 과제 목록"}${partial}`} unit="건" linkText="과제 보기" />
        <SummaryCard kind="week" label={DAY_LABELS.week} value={count("week")} sub={`이번 주 마감${partial}`} unit="개" linkText="자료 보기" />
      </div>
      {!data ? <p className="note" role={entry?.error ? "alert" : "status"}>
        {entry?.error ? <>{homeMessage(entry.error)} <RetryButton reload={reloadTodo} /></> : "학습 현황을 불러오는 중입니다."}
      </p> : null}
    </section>
  );
}

/** 모든 서버 수강과목을 기존 홈 카드로 표시한다. 과거 학기도 누락하지 않는다. */
export function serverHomeCourses(): ReactNode {
  return <ServerHomeCourses />;
}

function ServerHomeCourses() {
  const slot = useRef<HTMLDivElement>(null);
  const entry = useHome();
  const data = entry && entry.data;
  useEffect(() => {
    if (data && slot.current) offerEntryNotices(data, slot.current);
  }, [data]);
  // `/api/me/courses` is the membership source of truth: the home aggregate may omit older school terms.
  const groups = new Map<string, { label: string; courses: Course[] }>();
  for (const { course, remote } of serverCourseViews()) {
    if (!course) continue;
    const key = course.kind === "special" ? "special" : `term:${remote.term?.termId ?? remote.term?.name ?? ""}`;
    let group = groups.get(key);
    if (!group) groups.set(key, group = { label: course.kind === "special" ? "비정규과목" : course.term || "정규과목", courses: [] });
    group.courses.push(course);
  }
  const { matched } = memberCourses(data);
  const stats = new Map<string, CourseStats>(matched.map(({ local, remote }) => [local.id, remote]));
  return (
    <div ref={slot} className="home-server">
      {groups.size
        ? [...groups].map(([key, group]) => (
          <Fragment key={key}>
            <p className="term">{group.label}</p>
            {courseCards(group.courses, "home", { stats })}
          </Fragment>
        ))
        : <EmptyState title="수강 중인 과목이 없습니다" body="ASTRA 서버에 이 계정의 과목이 없습니다." />}
      {!data && !waiting(entry)
        ? <p className="note" role="alert">{homeMessage(entry?.error)} <RetryButton reload={reloadHome} /></p>
        : null}
    </div>
  );
}


function TodoCard({ item, data, serverTime }: { item: TodoItem; data: HomeData; serverTime: unknown }) {
  const href = todoHref(item, data);
  const kind = todoKind(item.type);
  const body = (
    <>
      <div>
        <div className="todo-k">{todoLabel(item.type)}</div>
        <b>{item.title}</b>
        <div className="tiny">{[item.courseName, item.courseCode ? `(${item.courseCode})` : ""].filter(Boolean).join(" ")}</div>
        <div className="tiny">{`마감 ${koDateTime(item.closeAt)}`}</div>
      </div>
      <span className="due">{ddayAt(item.closeAt, serverTime)}</span>
    </>
  );
  return href
    ? <Link className="aside-card todo-card todo-link" href={href} data-todo={kind}>{body}</Link>
    : <div className="aside-card todo-card" data-todo={kind}>{body}</div>;
}

/** The "할일" part of the right column: count and the first items from the home aggregate. */
export function serverAsideTodo(moreHref: string): ReactNode {
  return <ServerAsideTodo moreHref={moreHref} />;
}

function ServerAsideTodo({ moreHref }: { moreHref: string }) {
  const entry = useHome();
  const data = entry && entry.data;
  const todo = data?.todo || {};
  const items = Array.isArray(todo.items) ? todo.items : [];
  return (
    <>
      <div className="aside-h"><h2>할일</h2><span className="count-num">{Number.isInteger(todo.total) && Number(todo.total) >= 0 ? String(todo.total) : ""}</span></div>
        {waiting(entry)
          ? <p className="tiny" role="status">할 일을 불러오는 중입니다.</p>
          : !data
            ? <p className="tiny" role="alert">{homeMessage(entry?.error)} <RetryButton reload={reloadHome} /></p>
            : items.length
              ? items.slice(0, 4).map((item) => <TodoCard key={item.activityId} item={item} data={data} serverTime={todo.serverTime || data.serverTime} />)
              : <p className="tiny">마감이 남은 할 일이 없습니다.</p>}
      <Link className="more-link" href={moreHref}>모두 보기</Link>
    </>
  );
}

function TaskRow({ item, serverTime }: { item: TodoItem; serverTime: unknown }) {
  const href = todoHref(item, homeEntry()?.data);
  const kind = todoKind(item.type);
  const body = (
    <>
      <div className="task-title">
        <span className={`task-kind ${kind}`} role="img" aria-label={todoLabel(item.type)} title={todoLabel(item.type)}><Icon name={typeIcon(kind)} /></span>
        <b>{item.title}</b>
      </div>
      <div className="task-course">
        <span>{item.courseName}</span>
        <span className="task-code">{`(${item.courseCode})`}</span>
      </div>
      <div className="task-when">
        <strong className="task-due">{ddayAt(item.closeAt, serverTime)}</strong>
        <span>{`마감 ${koDateTime(item.closeAt)}`}</span>
      </div>
    </>
  );
  return href
    ? <Link className="task-row todo-link" href={href} data-todo={kind}>{body}</Link>
    : <div className="task-row" data-todo={kind}>{body}</div>;
}

/** Body of the to-do dialog: every open item, nearest deadline first, 20 at a time. */
export function serverTodoList(scope = ""): ReactNode {
  return <ServerTodoList scope={scope} />;
}

function ServerTodoList({ scope }: { scope: string }) {
  const entry = useTodo();
  const data = entry && entry.data;
  let status: ReactNode;
  const scoped = Object.hasOwn(DAY_LABELS, scope) ? scope as DayScope : null;
  const items = data ? (scoped ? dayGroups(data)[scoped] : data.items) : [];
  let alert = false;
  if (!data) {
    alert = Boolean(entry && entry.error);
    status = alert ? <>{homeMessage(entry?.error)} <RetryButton reload={reloadTodo} /></> : "할 일을 불러오는 중입니다.";
  } else {
    alert = Boolean(entry.error);
    status = entry.error
      ? homeMessage(entry.error)
      : scoped ? `${DAY_LABELS[scoped]} · 불러온 목록 ${items.length}개${data.nextCursor ? " · 다음 목록이 있습니다." : ""}`
        : data.items.length ? `마감이 남은 할 일 ${data.total}개 중 ${data.items.length}개` : "마감이 남은 할 일이 없습니다.";
  }
  const loading = Boolean(entry && entry.loading);
  return (
    <div className="tasks">
      <p className="task-empty" role={alert ? "alert" : "status"} aria-live="polite">{status}</p>
      <ul className="task-list">
        {items.map((item) => <li key={item.activityId}><TaskRow item={item} serverTime={data?.serverTime} /></li>)}
      </ul>
      <div className="btn-row">
        {data && data.nextCursor
          ? <Button kind="secondary" disabled={loading} onClick={() => void loadMoreTodo()}>{loading ? "불러오는 중…" : "더 보기"}</Button>
          : null}
      </div>
    </div>
  );
}
