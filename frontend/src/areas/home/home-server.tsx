// Home pieces read from the ASTRA backend while the connection is `ready`: course cards with
// unread notice counts, the latest notices, the to-do column and the full to-do dialog.
// Server text is only ever rendered as text.
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { Link } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Button, EmptyState, LoadingState, ErrorState } from "../../ui/kit.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { typeIcon } from "../../shared/format.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import {
  homeEntry, useHome, reloadHome, useTodo, reloadTodo, loadMoreTodo,
  memberCourses, keyOfCourse, todoKind, todoLabel, todoHref, ddayAt, koDateTime, koDate, homeMessage,
} from "../../shared/home-remote.ts";
import type { HomeData, TodoItem } from "../../shared/home-remote.ts";
import type { Entry } from "../../shared/remote-cache.ts";
import { courseCards, type CourseStats } from "./course-cards.tsx";
import { serverCourseViews } from "../../shared/course-view.ts";
import { siteNoticeStrip, offerEntryNotices } from "./site-notices-server.tsx";
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

/** Course cards of every server course the user belongs to (server facts only) and the latest notices. */
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
  const unread = new Map([...stats].map(([id, remote]) => [id, Number(remote.unreadNotices) || 0]));
  return (
    <div ref={slot} className="home-server">
      {groups.size
        ? [...groups].map(([key, group]) => (
          <Fragment key={key}>
            <p className="term">{group.label}</p>
            {courseCards(group.courses, "home", { unread, stats })}
          </Fragment>
        ))
        : <EmptyState title="수강 중인 과목이 없습니다" body="ASTRA 서버에 이 계정의 과목이 없습니다." />}
      {data
        ? <>{latestNotices(data)}{siteNoticeStrip(data)}</>
        : waiting(entry)
          ? <LoadingState title="공지와 학습 현황을 불러오는 중입니다" />
          : <ErrorState title="학습 현황을 불러오지 못했습니다" body={homeMessage(entry?.error)} actions={<RetryButton reload={reloadHome} />} />}
    </div>
  );
}

function latestNotices(data: HomeData): ReactNode {
  const items = Array.isArray(data.notices) ? data.notices : [];
  return (
    <section className="home-notices" aria-label="최근 공지">
      <h2 className="section-label">최근 공지</h2>
      {items.length
        ? (
          <ul className="ntf-list">
            {items.map((notice) => {
              const kj = keyOfCourse(data, notice.courseId);
              const href = kj ? hrefFor(find("44d_room_notice_view_pop"), { kj, id: String(notice.noticeId) }) : null;
              const className = `ntf-row${notice.read ? " read" : ""}`;
              const body = (
                <>
                  {notice.read
                    ? <span className="ntf-dot" aria-hidden="true" />
                    : <span className="ntf-dot" role="img" aria-label="읽지 않은 공지" />}
                  <span className="ntf-copy">
                    <b>{notice.pinned ? `[고정] ${notice.title}` : notice.title}</b>
                    <span className="ntf-src">{notice.courseName}</span>
                    <span className="ntf-when"><span>{koDate(notice.publishedAt)}</span></span>
                  </span>
                </>
              );
              return (
                <li key={notice.noticeId}>
                  {href ? <Link className={className} href={href}>{body}</Link> : <div className={className}>{body}</div>}
                </li>
              );
            })}
          </ul>
        )
        : <p className="note">게시된 공지가 없습니다.</p>}
    </section>
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
        <div className="tiny">{`${item.courseName} (${item.courseCode})`}</div>
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
      <div className="aside-h"><h2>할일</h2><span className="count-num">{data ? String(Number(todo.total) || 0) : ""}</span></div>
      <div className="home-server-todo">
        {waiting(entry)
          ? <p className="tiny" role="status">할 일을 불러오는 중입니다.</p>
          : !data
            ? <p className="tiny" role="alert">{homeMessage(entry?.error)}</p>
            : items.length
              ? items.map((item) => <TodoCard key={item.activityId} item={item} data={data} serverTime={todo.serverTime || data.serverTime} />)
              : <p className="tiny">마감이 남은 할 일이 없습니다.</p>}
      </div>
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
export function serverTodoList(): ReactNode {
  return <ServerTodoList />;
}

function ServerTodoList() {
  const entry = useTodo();
  const data = entry && entry.data;
  let status: ReactNode;
  let alert = false;
  if (!data) {
    alert = Boolean(entry && entry.error);
    status = alert ? <>{homeMessage(entry?.error)} <RetryButton reload={reloadTodo} /></> : "할 일을 불러오는 중입니다.";
  } else {
    alert = Boolean(entry.error);
    status = entry.error
      ? homeMessage(entry.error)
      : data.items.length ? `마감이 남은 할 일 ${data.total}개 중 ${data.items.length}개` : "마감이 남은 할 일이 없습니다.";
  }
  const loading = Boolean(entry && entry.loading);
  return (
    <div className="tasks">
      <p className="task-empty" role={alert ? "alert" : "status"} aria-live="polite">{status}</p>
      <ul className="task-list">
        {data ? data.items.map((item) => <li key={item.activityId}><TaskRow item={item} serverTime={data.serverTime} /></li>) : null}
      </ul>
      <div className="btn-row">
        {data && data.nextCursor
          ? <Button kind="secondary" disabled={loading} onClick={() => void loadMoreTodo()}>{loading ? "불러오는 중…" : "더 보기"}</Button>
          : null}
      </div>
    </div>
  );
}
