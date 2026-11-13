// Course notice list and detail from the ASTRA backend (status `anon` or `ready`).
// `anon` shows only the login strip: sample notices are not mixed into a connected course.
// Opening a notice sends `read` once; `confirm` comes only from the confirm button. A notice imported from the
// school's e-Class (`id=school-<n>`) is read from the school only when its dialog opens (that marks it read at the
// school); STack sends no `read`/`confirm` for it and shows its attachments and the e-Class link instead of a confirm.
import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { getState, render } from "../../store.ts";
import { Link, closeDialog } from "../../router.tsx";
import { Badge, Button, Dialog, EmptyState, ErrorState, LoadingState, Pagination, Tabs } from "../../ui/kit.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { readQuery } from "../../shared/query.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, courseIdFor, roleIn } from "../../shared/backend.ts";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { visitList } from "../../shared/board-list.tsx";
import { koDate, koDateTime } from "../../shared/home-remote.ts";
import {
  NOTICE_PAGE, listKey, noticeKey, noticeLists, noticeDetails, markNoticeRead, confirmNotice, noticeMessage, noticeFlags, isSchoolNotice,
  rememberSchoolNoticeOpened,
  type CourseNotice,
} from "../../shared/notices-remote.ts";
import { schoolFilesOf, schoolProblemOf, SchoolLink, SchoolProblemNote } from "../../shared/school-content.tsx";
import { SchoolFileRow } from "../../shared/school-files.tsx";
import type { CourseParts } from "./course.tsx";
import { RetryButton } from "./groups-server.tsx";


const missing = (error: unknown) => error instanceof ApiError && error.status === 404;

function noticeCard(notice: CourseNotice, href: string): ReactNode {
  return (
    <article key={String(notice.noticeId)} className={`crs-post${notice.read ? " is-read" : ""}`} data-notice={String(notice.noticeId)}>
      <Link className="crs-post-link" href={href}>
        <div>
          <h2>{notice.title}</h2>
          {notice.excerpt ? <p>{notice.excerpt}</p> : null}
          <div className="crs-post-meta">
            {isSchoolNotice(notice.noticeId) ? <span className="crs-kind">학교 e-Class</span> : null}
            {noticeFlags(notice).map((flag) => <span key={flag} className="crs-kind">{flag}</span>)}
            {notice.published && !notice.read ? <span className="crs-kind crs-kind-new">안 읽음</span> : null}
            <span>{notice.publishedAt ? koDate(notice.publishedAt) : "게시 전"}</span>
            {notice.viewCount == null ? null : <span>{`조회 ${notice.viewCount}`}</span>}
            {notice.readCount == null && notice.confirmCount == null ? null : <span>{`열람 ${notice.readCount ?? 0} · 확인 ${notice.confirmCount ?? 0}`}</span>}
          </div>
        </div>
      </Link>
    </article>
  );
}

/** The course notice board in backend mode. `parts` are course.tsx's head/searchBox builders. */
export function remoteNoticeBoard(screen: Screen, ctx: ScreenCtx, course: Course, parts: CourseParts): ReactNode {
  if (backendStatus() === "anon") {
    return <section className="crs" data-area="course">{parts.head("공지사항", "info", null)}</section>;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    return (
      <section className="crs" data-area="course">
        {parts.head("공지사항", "info", null)}
        <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목만 공지를 볼 수 있습니다." />
      </section>
    );
  }
  return <NoticeBoard screen={screen} ctx={ctx} kj={course.id} courseId={courseId} parts={parts} />;
}

function NoticeBoard({ screen, ctx, kj, courseId, parts }: { screen: Screen; ctx: ScreenCtx; kj: string; courseId: number | string; parts: CourseParts }) {
  const title = "공지사항";
  const q = readQuery(ctx.state, parts.scope).trim().slice(0, 100);
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const key = listKey(courseId, q, page);
  const load = () => void noticeLists.load(key, courseId, q, page);
  const entry = useResource(noticeLists, key, load);
  // A new search word waits until typing pauses (every keystroke re-renders this board).
  useEffect(() => visitList(noticeLists, "course-notices", key, () => void noticeLists.load(key, courseId, q, page)));
  const pageHref = (n: number) => hrefFor(find(screen.id), n > 1 ? { kj, page: String(n) } : { kj });

  const data = entry && entry.data;
  let body: ReactNode;
  if (!data) {
    if (!entry || !entry.error) body = <LoadingState title="공지를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="공지를 볼 수 없습니다" body="이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다." />;
    else body = <ErrorState title="공지를 불러오지 못했습니다" body={noticeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const items = Array.isArray(data.items) ? data.items : [];
    const total = Number(data.total);
    const pages = Math.max(1, Math.ceil(total / (Number(data.size) || NOTICE_PAGE)));
    const stale = entry.error ? <p className="err" role="alert">{noticeMessage(entry.error)}</p> : null;
    if (!items.length) {
      body = (
        <>
          {stale}
          {page > 1 && total > 0
            ? <EmptyState title="이 쪽에는 공지가 없습니다" body="" actions={<Link href={pageHref(1)} className="btn btn-secondary">첫 쪽으로</Link>} />
            : q
              ? <EmptyState title="검색 결과가 없습니다" body="제목에 들어 있는 다른 단어로 찾아 보세요." />
              : <EmptyState title="게시글이 없습니다." body="등록된 공지가 없습니다." />}
        </>
      );
    } else {
      body = (
        <>
          {stale}
          <div className="crs-posts">
            {items.map((notice) => noticeCard(notice,
              hrefFor(find("44d_room_notice_view_pop"), { kj, id: String(notice.noticeId), ...(page > 1 ? { page: String(page) } : {}) })))}
          </div>
          <Pagination page={page} pages={pages} hrefForPage={pageHref} />
        </>
      );
    }
  }
  return (
    <section className="crs" data-area="course">
      {parts.head(title, "info", data ? <Badge tone="neutral">{String(data.total)}</Badge> : null)}
      <div className="crs-toolbar">
        <Tabs items={[{ label: "전체", on: true }]} />
        {parts.searchBox(`${title} 검색`, ctx, "course")}
      </div>
      <div className="crs-remote" aria-live="polite">{body}</div>
    </section>
  );
}

function meta(pairs: ([string, string] | null)[]): ReactNode {
  return (
    <dl className="crs-meta pairs">
      {pairs.filter((pair): pair is [string, string] => Boolean(pair)).flatMap(([label, value]) => [
        <dt key={`${label}-t`}>{label}</dt>,
        <dd key={`${label}-d`}>{value || "—"}</dd>,
      ])}
    </dl>
  );
}

function prose(text: string | undefined): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return <div className="crs-prose"><p className="crs-note">본문이 없습니다.</p></div>;
  // Single line breaks inside a block stay as <br>; every piece is text.
  return (
    <div className="crs-prose">
      {blocks.map((block, n) => <p key={n}>{block.split("\n").flatMap((line, index) => (index ? [<br key={index} />, line] : [line]))}</p>)}
    </div>
  );
}

function shownAt(notice: CourseNotice): string {
  if (!notice.publishedAt) return "임시 저장";
  return `${koDateTime(notice.publishedAt)}${notice.published ? "" : " 예약"}`;
}

function shell(body: ReactNode): ReactNode {
  return <Dialog title="공지사항" className="crs-modal">{body}</Dialog>;
}

/** The notice dialog (`?pop=notice&id=`) in backend mode. */
export function remoteNoticeDialog(ctx: ScreenCtx, course: Course): ReactNode {
  if (backendStatus() === "anon") return shell(<div className="crs-dialog"></div>);
  const courseId = courseIdFor(course.id);
  const raw = (ctx.params && ctx.params.get("id")) || "";
  const noticeId: number | string = isSchoolNotice(raw) ? raw : Number(raw);
  if (!courseId || (typeof noticeId === "number" && (!Number.isInteger(noticeId) || noticeId < 1))) {
    return shell(<div className="crs-dialog"><EmptyState title="공지를 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." /></div>);
  }
  return shell(<NoticeDetail kj={course.id} courseId={courseId} noticeId={noticeId} />);
}

/** "공지를 확인했습니다" (`POST …/confirm`, idempotent). */
function ConfirmButton({ courseId, noticeId }: { courseId: number | string; noticeId: number | string }) {
  const [busy, setBusy] = useState(false);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const confirm = () => {
    if (busy) return;
    setBusy(true);
    confirmNotice(Number(courseId), noticeId).then(() => {
      getState().toast = "공지를 확인했습니다";
      closeDialog();
    }, (error: unknown) => {
      if (live.current) setBusy(false);
      getState().toast = noticeMessage(error);
      render();
    });
  };
  return <Button kind="primary" disabled={busy} onClick={confirm}>{busy ? "확인하는 중…" : "공지를 확인했습니다"}</Button>;
}

function NoticeDetail({ kj, courseId, noticeId }: { kj: string; courseId: number | string; noticeId: number | string }) {
  const key = noticeKey(courseId, noticeId);
  const school = isSchoolNotice(noticeId);
  const load = () => void noticeDetails.load(key, courseId, noticeId);
  const entry = useResource(noticeDetails, key);
  const [readNote, setReadNote] = useState("");
  useEffect(() => {
    if (freshVisit("notice-detail", key) === "entry" || !noticeDetails.peek(key)) void noticeDetails.load(key, courseId, noticeId);
  });
  const notice = entry && entry.data;
  useEffect(() => {
    if (!notice) return;
    if (isSchoolNotice(noticeId)) {
      // Opening it marked it read at the school; the lists show that without asking again.
      rememberSchoolNoticeOpened(courseId, noticeId);
      return;
    }
    if (notice.read) return;
    let live = true;
    markNoticeRead(courseId, noticeId).catch((error: unknown) => {
      if (live) setReadNote(`읽음 표시를 남기지 못했습니다. ${noticeMessage(error)}`);
    });
    return () => {
      live = false;
    };
  }, [notice, courseId, noticeId]);

  let body: ReactNode;
  if (entry && entry.error && schoolProblemOf(entry.error)) {
    body = <SchoolProblemNote error={entry.error} schoolUrl={notice && notice.schoolUrl} retry={load} />;
  } else if (!notice) {
    if (!entry || !entry.error) body = <LoadingState title="공지를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="공지를 찾을 수 없습니다" body="삭제되었거나 볼 수 있는 권한이 없습니다." />;
    else body = <ErrorState title="공지를 불러오지 못했습니다" body={noticeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else if (school) {
    const files = schoolFilesOf(notice);
    body = (
      <div data-school-notice={String(noticeId)}>
        <h3 className="crs-dialog-title">{notice.title}</h3>
        <div className="crs-post-meta">{["학교 e-Class", ...noticeFlags(notice)].map((flag) => <span key={flag} className="crs-kind">{flag}</span>)}</div>
        {meta([
          notice.authorName ? ["작성자", notice.authorName] : null,
          notice.publishedAt ? ["게시일", koDateTime(notice.publishedAt)] : null,
          notice.viewCount == null ? null : ["조회", String(notice.viewCount)],
          ["첨부", files.length ? `${files.length}개` : "없음"],
        ])}
        {prose(notice.body)}
        {files.map((file) => <SchoolFileRow key={String(file.fileId)} kj={kj} courseId={courseId} file={file} schoolUrl={notice.schoolUrl} />)}
        <div className="crs-form-actions">
          <SchoolLink url={notice.schoolUrl} />
          <Button kind="secondary" onClick={closeDialog}>닫기</Button>
        </div>
      </div>
    );
  } else {
    const instructor = roleIn(kj) === "INSTRUCTOR";
    const flags = noticeFlags(notice);
    body = (
      <>
        <h3 className="crs-dialog-title">{notice.title}</h3>
        {flags.length ? <div className="crs-post-meta">{flags.map((flag) => <span key={flag} className="crs-kind">{flag}</span>)}</div> : null}
        {meta([
          ["작성자", notice.authorName || "담당 교수"],
          ["게시일", shownAt(notice)],
          ["조회", String(notice.viewCount)],
          instructor ? ["열람·확인", `열람 ${notice.readCount ?? 0} · 확인 ${notice.confirmCount ?? 0}`] : null,
        ])}
        {prose(notice.body)}
        <p className="crs-note" role="status">{readNote}</p>
        <div className="crs-form-actions">
          {notice.confirmed
            ? <Button kind="secondary" onClick={closeDialog}>확인한 공지입니다 · 닫기</Button>
            : <ConfirmButton courseId={courseId} noticeId={noticeId} />}
        </div>
      </>
    );
  }
  return <div className="crs-dialog" aria-live="polite">{body}</div>;
}
