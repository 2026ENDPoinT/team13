// Professor screens backed by the ASTRA backend: the home course cards and latest notices
// (`GET /api/me/home`), and the course notice form with the server notice list
// (create, edit with `version`, delete).
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { Button, Badge, EmptyState, LoadingState, ErrorState, Pagination, ListRow, Tabs } from "../../ui/kit.tsx";
import { Link, navigate } from "../../router.tsx";
import { getState, render, toast } from "../../store.ts";
import { showErrors } from "../../forms.ts";
import { ApiError } from "../../shared/api.ts";
import { backendState, backendStatus, courseIdFor, roleIn } from "../../shared/backend.ts";
import { courseById, type Course } from "../../data.ts";
import type { ScreenCtx } from "../../types.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { useHome, reloadHome, memberCourses, koDate, koDateTime, homeMessage } from "../../shared/home-remote.ts";
import { routeFor } from "../../shared/routes.ts";
import {
  NOTICE_PAGE, listKey, noticeKey, noticeLists, noticeDetails, noticeMessage, noticeFlags, createNotice, updateNotice, deleteNotice,
  isVersionConflict, type CourseNotice,
} from "../../shared/notices-remote.ts";

const pf = (screenId: string, extra?: Record<string, string>) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const editHref = (kj: string, noticeId: number) => pf("pf_notice_write", { kj, edit: String(noticeId) });

function Retry({ reload }: { reload: () => void }) {
  const [pressed, setPressed] = useState(false);
  return <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); reload(); }}>다시 불러오기</Button>;
}

/** Professor home in backend mode: member courses as sample cards, then the latest notices. */
export function serverProfessorHome(card: (course: Course, unread: number) => ReactNode, onCount: (count: string) => void): ReactNode {
  return <ProfessorHome card={card} onCount={onCount} />;
}

function ProfessorHome({ card, onCount }: { card: (course: Course, unread: number) => ReactNode; onCount: (count: string) => void }) {
  const entry = useHome();
  const data = entry ? entry.data : null;
  const { matched } = memberCourses(data);
  const count = data ? String(matched.filter(({ remote }) => remote.role === "INSTRUCTOR").length) : "";
  useEffect(() => { onCount(count); }, [count, onCount]);

  let body: ReactNode;
  if (!entry || (!entry.data && !entry.error)) body = <LoadingState title="담당 과목을 불러오는 중입니다" />;
  else if (!data) body = <ErrorState title="담당 과목을 불러오지 못했습니다" body={homeMessage(entry.error)} actions={<Retry reload={reloadHome} />} />;
  else {
    const roles = new Map((Array.isArray(data.courses) ? data.courses : []).map((course) => [course.courseId, course]));
    const notices = Array.isArray(data.notices) ? data.notices : [];
    body = (
      <>
        {matched.length
          ? <div className="cards">{matched.map(({ local, remote }) => <Fragment key={remote.courseId}>{card(local, "unreadNotices" in remote ? Number(remote.unreadNotices) || 0 : 0)}</Fragment>)}</div>
          : <EmptyState title="담당 과목이 없습니다" body="ASTRA 서버에 이 계정의 과목이 없습니다." />}
        <h2 className="section-label">최근 공지</h2>
        {notices.length
          ? (
            <div>
              {notices.map((notice) => {
                const course = roles.get(notice.courseId);
                const own = course && course.role === "INSTRUCTOR" && matched.some(({ remote }) => remote.courseId === notice.courseId);
                return (
                  <ListRow
                    key={notice.noticeId}
                    href={own && course.key ? editHref(course.key, notice.noticeId) : undefined}
                    title={notice.pinned ? `[고정] ${notice.title}` : notice.title}
                    detail={`${notice.courseName} · ${koDate(notice.publishedAt)}`}
                    meta={notice.read ? "읽음" : "안 읽음"}
                  />
                );
              })}
            </div>
          )
          : <EmptyState title="게시된 공지가 없습니다" body="담당 과목에 공지를 쓰면 여기에 모입니다." />}
      </>
    );
  }
  return <div>{body}</div>;
}

/** The notice screen (`pf_notice_write`) in backend mode. `_parts` (professor.tsx helpers) is not needed. */
export function serverNoticeFields(ctx: ScreenCtx, course: Course, _parts?: unknown): ReactNode {
  if (backendStatus() === "anon") {
    // The local sample form is hidden: a notice saved only in this browser would look like a server notice.
    return <div className="stack">{backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 공지를 서버에 쓰고 고칠 수 있습니다." })}</div>;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) return <div className="stack">{backendPanel()}<EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목에만 공지를 쓸 수 있습니다." /></div>;
  if (roleIn(course.id) !== "INSTRUCTOR") {
    return <div className="stack">{backendPanel()}<p className="pf-sub">이 과목을 담당하는 교수 계정으로 로그인해야 공지를 쓰고 고칠 수 있습니다.</p></div>;
  }
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  const edit = Number.isInteger(editId) && editId > 0 ? editId : null;
  return (
    <div className="stack">
      {backendPanel()}
      <NoticeForm key={`${courseId}:${edit || ""}`} course={course} courseId={Number(courseId)} editId={edit} />
      <NoticeList course={course} courseId={Number(courseId)} page={page} />
    </div>
  );
}

interface Status {
  text: string;
  alert: boolean;
}

function FieldError({ name }: { name: string }) {
  const error = getState().formError[name];
  return error ? <span className="err">{error}</span> : null;
}

function NoticeForm({ course, courseId, editId }: { course: Course; courseId: number; editId: number | null }) {
  const key = editId ? noticeKey(courseId, editId) : null;
  const entry = useResource(noticeDetails, key);
  // Edit: the form fills once from the server copy; later loads (a conflict reload) leave typed text alone.
  const [filled, setFilled] = useState<CourseNotice | null>(null);
  const version = useRef<{ version: number; publishAt: string }>({ version: 0, publishAt: "" });
  const [status, setStatus] = useState<Status | null>(null);
  const [saving, setSaving] = useState(false);
  const [latest, setLatest] = useState<CourseNotice | null>(null);
  const live = useRef(true);
  const sheet = useRef<HTMLDivElement>(null);
  useEffect(() => () => { live.current = false; }, []);
  useEffect(() => {
    if (!key || !editId) return;
    if (freshVisit("pf-notice-edit", key) === "entry" || !noticeDetails.peek(key)) void noticeDetails.load(key, courseId, editId);
  }, [key, courseId, editId]);
  const notice = entry && entry.data;
  useEffect(() => {
    if (!editId || filled || !notice) return;
    adoptVersion(version.current, notice);
    setFilled(notice);
    // A notice never edited has no `updatedAt`: no sentence then, rather than one without a date.
    if (notice.updatedAt) setStatus({ text: `${koDateTime(notice.updatedAt)}에 마지막으로 수정한 공지입니다.`, alert: false });
  }, [editId, filled, notice]);

  const waiting = Boolean(editId) && !filled;
  let shown: Status = status || { text: "", alert: false };
  if (waiting) {
    shown = {
      alert: Boolean(entry && entry.error),
      text: !entry || !entry.error ? "공지를 불러오는 중입니다."
        : entry.error instanceof ApiError && entry.error.status === 404 ? "공지를 찾을 수 없습니다. 삭제되었거나 이 과목의 공지가 아닙니다." : noticeMessage(entry.error),
    };
  }
  const disabled = waiting || saving;

  async function save() {
    if (saving || !sheet.current) return;
    const field = <T extends HTMLInputElement | HTMLTextAreaElement>(name: string) => sheet.current!.querySelector<T>(`[data-name="${name}"]`)!;
    const title = field<HTMLInputElement>("title").value.trim();
    const body = field<HTMLTextAreaElement>("body").value;
    const errors: Record<string, string> = {};
    if (!title) errors.title = "제목을 입력하세요";
    else if (title.length > 300) errors.title = "제목은 300자 이하입니다";
    if (!body.trim()) errors.body = "본문을 입력하세요";
    else if (body.length > 20000) errors.body = "본문은 20,000자 이하입니다";
    if (Object.keys(errors).length) {
      showErrors(errors);
      return;
    }
    getState().formError = {};
    const draft = field<HTMLInputElement>("draft").checked;
    const payload = {
      title,
      body,
      pinned: field<HTMLInputElement>("pin").checked,
      published: !draft,
      publishAt: draft ? null : version.current.publishAt || null,
    };
    setSaving(true);
    setStatus({ text: "ASTRA 서버에 저장하고 있습니다.", alert: false });
    try {
      if (editId) await updateNotice(courseId, editId, { ...payload, version: version.current.version });
      else await createNotice(courseId, payload);
      getState().toast = draft ? "공지를 임시 저장했습니다" : editId ? "공지를 수정했습니다" : "공지를 서버에 등록했습니다";
      navigate(pf("pf_notice_write", { kj: course.id }));
      return;
    } catch (error) {
      if (!live.current) {
        toast(noticeMessage(error));
        return;
      }
      setStatus({ text: noticeMessage(error), alert: true });
      if (editId && isVersionConflict(error)) await showLatest(editId, noticeMessage(error));
    } finally {
      if (live.current) setSaving(false);
    }
  }

  /** After a 409 version_conflict: load the current notice, keep the typed text, show the server copy. */
  async function showLatest(id: number, said: string) {
    const latestKey = noticeKey(courseId, id);
    await noticeDetails.load(latestKey, courseId, id);
    const fresh = noticeDetails.peek(latestKey);
    if (!live.current) return;
    if (!fresh || !fresh.data) {
      setStatus({ text: `다른 곳에서 이 공지를 먼저 수정했지만 최신 내용을 불러오지 못했습니다. ${noticeMessage(fresh && fresh.error)}`, alert: true });
      return;
    }
    adoptVersion(version.current, fresh.data);
    setStatus({ text: said, alert: true });
    setLatest(fresh.data);
  }

  return (
    <div ref={sheet} className="sheet" data-notice-form="" aria-busy={saving ? "true" : undefined}>
      <h2>{editId ? "공지 수정" : "공지"}</h2>
      <div className="pf-form" key={filled ? "filled" : "empty"}>
        <label className="field span-2">
          <span className="field-label">제목</span>
          <input data-name="title" maxLength={300} defaultValue={filled ? filled.title : ""} disabled={disabled} />
          <FieldError name="title" />
        </label>
        <label className="field span-2">
          <span className="field-label">본문</span>
          <textarea data-name="body" rows={8} maxLength={20000} defaultValue={filled ? filled.body || "" : ""} disabled={disabled} />
          <FieldError name="body" />
        </label>
        <label className="choice pf-check span-2">
          <input type="checkbox" data-name="pin" defaultChecked={Boolean(filled && filled.pinned)} disabled={disabled} />
          <span>상단 고정</span>
        </label>
        <label className="choice pf-check span-2">
          <input type="checkbox" data-name="draft" defaultChecked={Boolean(filled && !filled.publishedAt)} disabled={disabled} />
          <span>임시 저장 (학생에게 보이지 않습니다)</span>
        </label>
        <p className="pf-sub span-2" role={shown.alert ? "alert" : "status"} aria-live="polite" data-notice-status="">{shown.text}</p>
        <div className="span-2" data-notice-latest="">
          {latest ? (
            <details className="pf-server-pdf" open>
              <summary><b>서버의 최신 내용</b><span className="pf-meta">{` · 버전 ${latest.version}`}</span></summary>
              <p className="pf-sub">{latest.title}</p>
              <p className="pf-notice-body">{latest.body || ""}</p>
            </details>
          ) : null}
        </div>
        <div className="pf-actions span-2">
          <Button kind="primary" disabled={disabled} onClick={() => { void save(); }}>{saving ? "저장하는 중…" : editId ? "수정 저장" : "공지 저장"}</Button>
          {editId ? <Link className="btn btn-secondary" href={pf("pf_notice_write", { kj: course.id })}>새 공지 쓰기</Link> : null}
        </div>
      </div>
    </div>
  );
}

/** Remembers the version and schedule an edit must send back. */
function adoptVersion(target: { version: number; publishAt: string }, notice: CourseNotice) {
  target.version = Number(notice.version);
  // A scheduled notice keeps its time; a published one keeps its original time when publishAt is omitted.
  target.publishAt = notice.publishedAt && !notice.published ? notice.publishedAt : "";
}

function NoticeList({ course, courseId, page, screenId = "pf_notice_write" }: { course: Course; courseId: number; page: number; screenId?: string }) {
  const key = listKey(courseId, "", page);
  const load = () => { void noticeLists.load(key, courseId, "", page); };
  const entry = useResource(noticeLists, key, load);
  useEffect(() => {
    if (freshVisit("pf-notices", key) === "entry" || !noticeLists.peek(key)) void noticeLists.load(key, courseId, "", page);
  }, [key, courseId, page]);
  const data = entry && entry.data;
  let list: ReactNode;
  if (!data) {
    list = !entry || !entry.error
      ? <p className="pf-sub" role="status">서버에 등록된 공지를 불러오는 중입니다.</p>
      : <div><p className="err" role="alert">{noticeMessage(entry.error)}</p><Retry reload={load} /></div>;
  } else {
    const items = Array.isArray(data.items) ? data.items : [];
    if (!items.length) list = <p className="pf-sub">{page > 1 ? "이 쪽에는 공지가 없습니다." : "아직 서버에 등록된 공지가 없습니다."}</p>;
    else {
      const after = pf(screenId, page > 1 ? { kj: course.id, page: String(page) } : { kj: course.id });
      list = (
        <>
          {entry.error ? <p className="err" role="alert">{noticeMessage(entry.error)}</p> : null}
          {items.map((notice) => {
            const href = editHref(course.id, notice.noticeId);
            const flags = noticeFlags(notice);
            return (
              <div className="pf-activity" key={notice.noticeId}>
                <Link className="pf-activity-main" href={href}>
                  <Badge tone={flags.includes("임시") || flags.includes("예약") ? "neutral" : "success"}>{flags.length ? flags.join(" · ") : "게시"}</Badge>
                  <span className="pf-activity-copy">
                    <b>{notice.title}</b>
                    <span className="pf-meta">{[
                      notice.publishedAt ? koDate(notice.publishedAt) : "게시 전",
                      `조회 ${notice.viewCount}`,
                      `열람 ${notice.readCount ?? 0}`,
                      `확인 ${notice.confirmCount ?? 0}`,
                    ].join(" · ")}</span>
                  </span>
                </Link>
                <div className="pf-activity-actions">
                  <Link className="btn btn-ghost" href={href} aria-label={`${notice.title} 수정`}>수정</Link>
                  <DeleteNotice courseId={courseId} noticeId={notice.noticeId} title={notice.title} after={after} />
                </div>
              </div>
            );
          })}
          <Pagination
            page={page}
            pages={Math.max(1, Math.ceil(Number(data.total) / (Number(data.size) || NOTICE_PAGE)))}
            hrefForPage={(n) => pf(screenId, n > 1 ? { kj: course.id, page: String(n) } : { kj: course.id })}
          />
        </>
      );
    }
  }
  return (
    <section className="sheet" aria-label="ASTRA 서버에 등록된 공지">
      <h2>서버에 등록된 공지</h2>
      <p className="pf-sub">임시 저장과 예약 공지는 담당 교수에게만 보입니다. 열람·확인 수에는 작성자가 빠집니다.</p>
      <div className="stack">{list}</div>
    </section>
  );
}

/** Two presses: the first arms the button, the second sends DELETE. */
function DeleteNotice({ courseId, noticeId, title, after }: { courseId: number; noticeId: number; title: string; after: string }) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);
  function press() {
    if (busy) return;
    if (!armed) {
      setArmed(true);
      return;
    }
    setBusy(true);
    deleteNotice(courseId, noticeId).then(() => {
      getState().toast = "공지를 삭제했습니다";
      navigate(after);
    }, (error: unknown) => {
      if (error instanceof ApiError && error.status === 404) {
        getState().toast = "이미 삭제되었거나 찾을 수 없는 공지입니다. 목록을 새로 불러왔습니다.";
        navigate(after);
        return;
      }
      if (live.current) {
        setBusy(false);
        setArmed(false);
      }
      getState().toast = noticeMessage(error);
      render();
    });
  }
  return (
    <Button kind="ghost" disabled={busy} aria-label={`${title} 삭제`} onClick={press}>
      {busy ? "삭제하는 중…" : armed ? "한 번 더 누르면 삭제" : "삭제"}
    </Button>
  );
}

/**
 * `pf_notice`(알림마당) 백엔드 화면. 교수용 기관 공지 목록 API가 없어 담당 과목의 공지를 고르고
 * 수정·삭제한다. 주소 `kj`가 담당 과목이 아니면 첫 담당 과목, `page`는 그 과목 공지 목록의 쪽이다.
 */
export function serverCourseNotices(ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "anon") {
    return <div className="stack">{backendPanel({ text: "ASTRA 서버에 로그인하면 담당 과목의 공지를 보고 고칠 수 있습니다." })}</div>;
  }
  const param = (name: string) => (ctx && ctx.params && ctx.params.get(name)) || "";
  const { courses } = backendState();
  const taught = (Array.isArray(courses) ? courses : [])
    .filter((remote) => remote.role === "INSTRUCTOR" && remote.key && courseById(remote.key).id === remote.key);
  if (!taught.length) {
    return <div className="stack">{backendPanel()}<EmptyState title="담당 과목이 없습니다" body="ASTRA 서버에 이 계정이 담당하는 과목이 없어 공지를 보여 줄 수 없습니다." /></div>;
  }
  const chosen = taught.find((remote) => remote.key === param("kj")) || taught[0];
  const course = courseById(chosen.key!);
  const page = Math.max(1, Math.floor(Number(param("page"))) || 1);
  const write = pf("pf_notice_write", { kj: course.id });
  return (
    <div className="stack">
      {backendPanel()}
      <p className="pf-sub">ASTRA 서버에는 교수용 기관 공지가 아직 없어 담당 과목의 공지를 모아 보여 줍니다.</p>
      <nav aria-label="담당 과목">
        <Tabs items={taught.map((remote) => ({
          label: remote.name || courseById(remote.key!).ko || remote.key!,
          href: pf("pf_notice", { kj: remote.key! }),
          on: remote.key === chosen.key,
        }))} />
      </nav>
      <div className="pf-actions"><Link className="btn btn-primary" href={write}>새 공지 쓰기</Link></div>
      <NoticeList course={course} courseId={Number(chosen.courseId)} page={page} screenId="pf_notice" />
    </div>
  );
}
