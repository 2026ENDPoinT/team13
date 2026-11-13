// 알림마당 공지사항(30, 30b), 공지 창(06_pop_notice_view, `?pop=notice&id=srv-<noticeId>`), 공지 쓰기·수정
// 창(`?pop=notice-write[&id=srv-<noticeId>]`), 진입 공지(00_notice_for_me, `?pop=welcome`)와 홈의 알림마당 공지
// 줄을 ASTRA 서버에서 그린다(backend `anon`·`ready`). 서버 글은 모두 텍스트다. 쓰기 진입은 목록의
// `canManage`, 공지별 수정·삭제·바로 게시는 상세의 `canManage`가 true일 때만 보인다.
import { cloneElement, isValidElement, useEffect, useReducer, useRef, useState, type ReactNode } from "react";
import { Badge, Button, Dialog, EmptyState, ErrorState, Field, LoadingState, Pagination } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { HOME_PATH, Link, closeDialog, navigate } from "../../router.tsx";
import { getState, render, toast } from "../../store.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { readQuery, writeQuery } from "../../shared/query.tsx";
import { backendStatus, subscribe } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, replaceVisit, useResource } from "../../shared/remote-cache.ts";
import { useHome, koDate, koDateTime, wallText, type HomeData, type HomeSiteNotice } from "../../shared/home-remote.ts";
import { serverTime } from "../../shared/lectures-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { attachPicker, resetAttachments, type AttachMemo } from "../../shared/attach-picker.tsx";
import {
  SITE_NOTICE_PAGE, SITE_AUDIENCES, WRITE_AUDIENCES, MAX_NOTICE_FILES, siteListKey, siteNoticeLists, siteNoticeDetails,
  siteManageState, checkSiteManage, siteNoticeIdOf, siteNoticeHref, markSiteNoticeRead, claimEntryPopup, dismissEntryNotices,
  createSiteNotice, updateSiteNotice, publishSiteNoticeNow, deleteSiteNotice, isVersionConflict, isAttachmentNotReady, isMissing,
  siteAudienceLabel, siteNoticeMessage, type SiteAudience, type SiteNotice, type SiteNoticePayload,
} from "../../shared/site-notices-remote.ts";
import { RetryButton } from "./home-server.tsx";

const SEARCH_DELAY = 300;
const SCOPE = "home:board:global";
const COUNT_OF: Record<string, string> = { all: "total", common: "common", professor: "professor", student: "student" };

const listPath = () => hrefFor(find("30_community_notice_list"));
const writeHref = (noticeId: number | null) => `${listPath()}?${new URLSearchParams({ pop: "notice-write", ...(noticeId ? { id: `srv-${noticeId}` } : {}) })}`;

interface Note { text: string; alert: boolean }

interface WriteMemo {
  loaded: boolean;
  title: string;
  body: string;
  audience: string;
  pinned: boolean;
  publishAt: string;
  version: number | null;
  attach: AttachMemo;
  latest: { title: string; body?: string; version?: number } | null;
  message: string;
}

// 쓰는 중인 공지(키 "new" 또는 noticeId)와 고른 첨부. 세션이 바뀌면 버린다.
const memos = new Map<string, WriteMemo>();
// 공지 창 운영 줄의 마지막 결과(noticeId → { text, alert }). 창이 다시 그려져도 남는다.
const manageNotes = new Map<number, Note>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
  manageNotes.clear();
  entry.items = null;
  entry.live = false;
});

function useBump(): () => void {
  const [, bump] = useReducer((n: number) => n + 1, 0);
  return bump;
}

/** Same frame as the other home dialogs (`home-dialog`). */
function dialogShell(title: string, body: ReactNode, wide = true, onClose?: () => void): ReactNode {
  return <Dialog title={title} wide={wide} className="home-dialog" onClose={onClose}>{body}</Dialog>;
}

function prose(text: string | null | undefined): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return <p className="tiny">본문이 없습니다.</p>;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>
          {block.split("\n").map((line, at) => <span key={at}>{at ? <br /> : null}{line}</span>)}
        </p>
      ))}
    </div>
  );
}

/** "고정", "예약" badges of a notice row or detail. */
function flags(notice: Pick<SiteNotice, "pinned" | "published">): ReactNode {
  return (
    <>
      {notice.pinned ? <Badge tone="neutral">고정</Badge> : null}
      {notice.published === false ? <Badge tone="danger">예약</Badge> : null}
    </>
  );
}

function whenText(notice: SiteNotice): string {
  return notice.published === false ? `${koDateTime(notice.publishedAt)} 게시 예정` : koDate(notice.publishedAt);
}

function pageOf(params: URLSearchParams): number {
  const page = Number(params.get("page"));
  return Number.isInteger(page) && page > 0 && page < 100000 ? page : 1;
}

function isSiteAudience(value: string | null | undefined): value is SiteAudience {
  return SITE_AUDIENCES.some((audience) => audience === value);
}

// ---------- 목록 (30, 30b) ----------

/** 알림마당 공지사항 in backend mode; `head` is the page header built by home (the write tool goes inside it). */
export function serverSiteNotices(screen: Screen, ctx: ScreenCtx, head: ReactNode): ReactNode {
  if (backendStatus() === "anon") {
    return (
      <section className="board srv-site-notices" data-area="home">
        {head}
        {backendPanel({ text: "ASTRA 서버에 로그인하면 알림마당 공지를 서버에서 불러옵니다." })}
      </section>
    );
  }
  return <SiteNoticeList screen={screen} ctx={ctx} head={head} />;
}

function SiteNoticeList({ screen, ctx, head }: { screen: Screen; ctx: ScreenCtx; head: ReactNode }) {
  const params = ctx.params || new URLSearchParams();
  const wanted = screen.audience || params.get("audience");
  const audience: SiteAudience = isSiteAudience(wanted) ? wanted : "all";
  const q = readQuery(ctx.state, SCOPE).trim().slice(0, 100);
  const page = pageOf(params);
  const key = siteListKey(audience, q, page);
  const load = () => void siteNoticeLists.load(key, audience, q, page);
  const entry = useResource(siteNoticeLists, key, load);
  useEffect(() => {
    const fresh = freshVisit("community-notices", key);
    if (fresh === "entry") load();
    else if (!siteNoticeLists.peek(key)) {
      if (fresh !== "key") return load();
      const timer = setTimeout(() => { if (!siteNoticeLists.peek(key)) load(); }, SEARCH_DELAY);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [key]);
  const base = listPath();
  const hrefWith = (extra: Record<string, string>) => {
    const query = new URLSearchParams({ ...(audience !== "all" ? { audience } : {}), ...extra });
    const text = query.toString();
    return text ? `${base}?${text}` : base;
  };
  const data = entry && entry.data;
  const counts = data ? data.counts : null;

  const tools = (
    <div key="srv-site-tools" className="board-tools srv-site-tools">
      {data && data.canManage === true ? <Link href={writeHref(null)} className="btn btn-primary" data-site-notice-new="">공지 쓰기</Link> : null}
    </div>
  );
  const withTools = isValidElement<{ children?: ReactNode }>(head)
    ? cloneElement(head, {}, head.props.children, tools)
    : <>{head}{tools}</>;

  const chips = (
    <div className="filters" role="toolbar" aria-label="공지 대상">
      {SITE_AUDIENCES.map((value) => {
        const count = counts ? counts[COUNT_OF[value] ?? ""] : null;
        // 볼 수 없는 대상(서버가 0으로 센 것)은 고른 경우가 아니면 숨긴다.
        if (counts && value !== "all" && value !== audience && !count) return null;
        const href = value === "all" ? base : `${base}?audience=${value}`;
        return (
          <Link key={value} href={href} className={`chip${audience === value ? " on all" : ""}`} aria-current={audience === value ? "true" : undefined}>
            {count != null ? `${siteAudienceLabel(value)} ${count}` : siteAudienceLabel(value)}
          </Link>
        );
      })}
    </div>
  );

  let list: ReactNode;
  if (!data) {
    if (!entry || !entry.error) list = <LoadingState title="공지를 불러오는 중입니다" />;
    else list = <ErrorState title="공지를 불러오지 못했습니다" body={siteNoticeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const stale = entry && entry.error ? <p className="err" role="alert">{siteNoticeMessage(entry.error)}</p> : null;
    const pages = Math.max(1, Math.ceil((Number(data.total) || 0) / SITE_NOTICE_PAGE));
    if (!data.items.length) {
      list = (
        <>
          {stale}
          {page > 1
            ? <EmptyState title="이 쪽에는 공지가 없습니다" body="첫 쪽으로 돌아가 보세요." actions={<Link href={hrefWith({})} className="btn btn-secondary">첫 쪽으로</Link>} />
            : q || audience !== "all"
              ? <EmptyState title="조건에 맞는 공지가 없습니다" body="다른 대상을 고르거나 검색어를 지워 보세요." />
              : <EmptyState title="등록된 공지가 없습니다" body="새 공지가 올라오면 여기에 보입니다." />}
        </>
      );
    } else {
      list = (
        <>
          {stale}
          <p className="board-count">{`${audience === "all" ? "전체" : siteAudienceLabel(audience)} ${Number(data.total) || 0}건`}</p>
          <div className="board-list">
            {data.items.map((item) => (
              <Link
                key={item.noticeId}
                className={`board-card${item.read ? " read" : ""}`}
                href={hrefWith({ ...(page > 1 ? { page: String(page) } : {}), pop: "notice", id: `srv-${item.noticeId}` })}
                data-notice={String(item.noticeId)}
              >
                <span className="srv-site-title">
                  {flags(item)}
                  {item.read ? null : <span className="ntf-dot" role="img" aria-label="읽지 않은 공지" />}
                  <b>{item.title}</b>
                </span>
                <span className="tiny">{[siteAudienceLabel(item.audience), whenText(item), `조회 ${item.viewCount ?? 0}`, item.read ? null : "안 읽음"].filter(Boolean).join(" · ")}</span>
                {item.excerpt ? <p>{item.excerpt}</p> : null}
              </Link>
            ))}
          </div>
          <Pagination page={page} pages={pages} hrefForPage={(n) => hrefWith(n > 1 ? { page: String(n) } : {})} />
        </>
      );
    }
  }
  return (
    <section className="board srv-site-notices" data-area="home">
      {withTools}
      {chips}
      <label className="search board-search">
        <Icon name="search" />
        <input
          value={readQuery(ctx.state, SCOPE)}
          onChange={(event) => {
            writeQuery(ctx.state, SCOPE, event.target.value);
            render();
          }}
          placeholder="검색"
          aria-label="공지사항 제목 검색"
        />
      </label>
      <div aria-live="polite">{list}</div>
    </section>
  );
}

// ---------- 공지 창 (06_pop_notice_view) ----------

/** `?pop=notice&id=srv-<noticeId>` in backend mode: body, attachments, explicit read, operator controls. */
export function serverSiteNoticeDialog(ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "anon") {
    return dialogShell("공지", <div aria-live="polite" className="srv-site-notice">{backendPanel({ text: "ASTRA 서버에 로그인하면 공지를 볼 수 있습니다." })}</div>);
  }
  const id = siteNoticeIdOf(ctx.params && ctx.params.get("id"));
  if (!id) {
    return dialogShell("공지", <div aria-live="polite" className="srv-site-notice"><EmptyState title="공지를 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." /></div>);
  }
  const fresh = freshVisit("community-notice", String(id)) === "entry";
  if (fresh) manageNotes.delete(id);
  return dialogShell("공지", <SiteNoticeDetail id={id} fresh={fresh} />);
}

function SiteNoticeDetail({ id, fresh }: { id: number; fresh: boolean }) {
  const entry = useResource(siteNoticeDetails, String(id));
  const [readNote, setReadNote] = useState("");
  const load = () => void siteNoticeDetails.load(String(id), id);
  useEffect(() => {
    if (fresh || !siteNoticeDetails.peek(String(id))) load();
  }, [id]);
  const item = entry && entry.data;
  const unread = Boolean(item && !item.read && item.published !== false);
  useEffect(() => {
    if (!unread) return;
    let live = true;
    markSiteNoticeRead(id).catch((error: unknown) => {
      if (live) setReadNote(`읽음 표시를 남기지 못했습니다. ${siteNoticeMessage(error)}`);
    });
    return () => { live = false; };
  }, [id, unread]);
  let body: ReactNode;
  if (!item) {
    if (!entry || !entry.error) body = <LoadingState title="공지를 불러오는 중입니다" />;
    else if (isMissing(entry.error)) body = <EmptyState title="공지를 찾을 수 없습니다" body="삭제되었거나 볼 수 있는 대상이 아닙니다." />;
    else body = <ErrorState title="공지를 불러오지 못했습니다" body={siteNoticeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const files = Array.isArray(item.attachments) ? item.attachments : [];
    body = (
      <>
        <h3 className="srv-site-title">{flags(item)}<span>{item.title}</span></h3>
        <p className="tiny">{[siteAudienceLabel(item.audience), item.authorName, item.published === false ? `${koDateTime(item.publishedAt)} 게시 예정` : koDateTime(item.publishedAt), `조회 ${item.viewCount ?? 0}`].filter(Boolean).join(" · ")}</p>
        {prose(item.body)}
        {files.length ? (
          <ul className="stack srv-site-files" aria-label="첨부 파일">
            {files.map((file, index) => (
              <li key={file.fileId ?? file.attachmentId ?? index} className="file-row">
                <Icon name="file" />
                <span><b>{file.name || "파일"}</b><span className="tiny">{sizeText(file.sizeBytes)}</span></span>
                {downloadControl(null, { fileId: file.fileId ?? file.attachmentId ?? "", name: file.name || "파일" })}
              </li>
            ))}
          </ul>
        ) : null}
        <p className="tiny" role="status">{readNote}</p>
        {item.canManage ? <ManageRow id={id} item={item} /> : null}
      </>
    );
  }
  return <div aria-live="polite" className="srv-site-notice">{body}</div>;
}

const publishing = new Set<number>(); // noticeId being published now

/** 수정·바로 게시·삭제 and the status line with the last publish/delete result. */
function ManageRow({ id, item }: { id: number; item: SiteNotice }) {
  const [status, setStatus] = useState<Note | null>(() => manageNotes.get(id) ?? null);
  const [armed, setArmed] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [publishBusy, setPublishBusy] = useState(false);

  const remove = async () => {
    if (removing) return;
    if (!armed) {
      setArmed(true);
      setStatus({ text: "삭제하면 되돌릴 수 없습니다. 한 번 더 누르세요.", alert: false });
      return;
    }
    setArmed(false);
    setRemoving(true);
    try {
      await deleteSiteNotice(id);
      getState().toast = "공지를 삭제했습니다";
      navigate(listPath());
    } catch (error) {
      setRemoving(false);
      setStatus({ text: siteNoticeMessage(error, true), alert: true });
    }
  };

  /** 예약 공지 바로 게시. 버전이 바뀌었으면 최신 공지를 다시 읽고 확인한 뒤 다시 누르게 한다. */
  const publish = async () => {
    const current = siteNoticeDetails.peek(String(id));
    const notice = current && current.data;
    if (!notice || publishing.has(id) || publishBusy) return;
    publishing.add(id);
    manageNotes.delete(id);
    setPublishBusy(true);
    setStatus({ text: "ASTRA 서버에 게시하고 있습니다.", alert: false });
    try {
      await publishSiteNoticeNow(notice);
      const done = { text: "공지를 지금 게시했습니다.", alert: false };
      manageNotes.set(id, done);
      setStatus(done);
      await siteNoticeDetails.load(String(id), id);
    } catch (error) {
      let failed: Note;
      if (isVersionConflict(error)) {
        failed = { text: "다른 곳에서 이 공지를 먼저 고쳤습니다. 최신 내용을 불러왔으니 확인한 뒤 다시 누르세요.", alert: true };
        manageNotes.set(id, failed);
        await siteNoticeDetails.load(String(id), id);
      } else {
        failed = { text: siteNoticeMessage(error, true), alert: true };
        manageNotes.set(id, failed);
        if (isMissing(error)) await siteNoticeDetails.load(String(id), id);
      }
      setStatus(failed);
    } finally {
      publishing.delete(id);
      setPublishBusy(false);
    }
  };

  return (
    <div className="btn-row srv-site-manage">
      <Link href={writeHref(id)} className="btn btn-secondary">수정</Link>
      {item.published === false ? (
        <Button kind="primary" disabled={publishBusy} aria-busy={publishBusy || undefined} onClick={() => void publish()}>
          {publishBusy ? "게시하는 중…" : "바로 게시"}
        </Button>
      ) : null}
      <Button kind="danger" disabled={removing} aria-busy={removing || undefined} onClick={() => void remove()}>
        {removing ? "삭제하는 중…" : armed ? "한 번 더 누르면 삭제" : "삭제"}
      </Button>
      <span className="tiny" role={status && status.alert ? "alert" : "status"}>{status ? status.text : ""}</span>
    </div>
  );
}

// ---------- 쓰기·수정 창 ----------

function memoFor(key: string): WriteMemo {
  let memo = memos.get(key);
  if (!memo) {
    memo = { loaded: false, title: "", body: "", audience: "common", pinned: false, publishAt: "", version: null, attach: {}, latest: null, message: "" };
    memos.set(key, memo);
  }
  return memo;
}

function adopt(memo: WriteMemo, item: SiteNotice): void {
  memo.loaded = true;
  memo.title = item.title || "";
  memo.body = item.body || "";
  memo.audience = WRITE_AUDIENCES.some((value) => value === item.audience) ? item.audience ?? "common" : "common";
  memo.pinned = Boolean(item.pinned);
  // 이미 게시된 공지는 게시 일시를 비워 두면 원래 일시를 지킨다. 예약 공지만 그 일시를 채운다.
  memo.publishAt = item.published === false ? wallText(item.publishedAt) : "";
  memo.version = item.version ?? null;
}

function WriteForm({ memoKey, item }: { memoKey: string; item: SiteNotice | null }) {
  const memo = memoFor(memoKey);
  const bump = useBump();
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<Note>(() => ({ text: memo.message, alert: Boolean(memo.message) }));
  const picker = attachPicker({
    memo: memo.attach,
    courseId: null,
    purpose: "community_notice",
    max: MAX_NOTICE_FILES,
    existing: item && Array.isArray(item.attachments)
      ? item.attachments.flatMap((file) => (file.fileId != null ? [{ fileId: file.fileId, name: file.name, sizeBytes: file.sizeBytes }] : []))
      : [],
    hint: `${MAX_NOTICE_FILES}개까지, 50MB 이하. 검사를 통과한 파일만 첨부됩니다.`,
  });

  const say = (text: string, alert = true) => {
    memo.message = alert ? text : "";
    setStatus({ text, alert });
  };

  /** `srv-site-notice` save: 등록 or 수정 저장. A save in flight is not sent twice. */
  const saveNotice = async () => {
    if (saving) return;
    const title = memo.title.trim();
    if (!title) return say("제목을 입력하세요.");
    if (title.length > 300) return say("제목은 300자 이하입니다.");
    if (memo.body.length > 20000) return say("본문은 20,000자 이하입니다.");
    const publishAt = memo.publishAt ? serverTime(memo.publishAt) : null;
    if (memo.publishAt && !publishAt) return say("게시 일시를 확인하세요.");
    if (picker.pending()) return say("첨부 파일 검사가 끝난 뒤 저장하세요.");
    const payload: SiteNoticePayload = { title, body: memo.body, audience: memo.audience, pinned: Boolean(memo.pinned) };
    if (publishAt) payload.publishAt = publishAt;
    setSaving(true);
    say("ASTRA 서버에 저장하고 있습니다.", false);
    try {
      let notice: SiteNotice;
      if (!item) {
        notice = await createSiteNotice({ ...payload, uploadIds: picker.uploadIds() });
      } else {
        if (picker.changed()) payload.attachments = picker.attachments();
        notice = await updateSiteNotice(item.noticeId, { ...payload, version: memo.version ?? undefined });
      }
      resetAttachments(memo.attach);
      memos.delete(memoKey);
      getState().toast = item ? "공지를 수정했습니다" : "공지를 등록했습니다";
      navigate(siteNoticeHref(notice.noticeId));
    } catch (error) {
      if (!mounted.current) {
        toast(siteNoticeMessage(error, true));
        return;
      }
      say(siteNoticeMessage(error, true));
      if (isAttachmentNotReady(error)) picker.recheck();
      if (item && isVersionConflict(error)) {
        // 최신 공지를 다시 읽어 version만 바꾸고 입력한 글은 그대로 둔다.
        await siteNoticeDetails.load(String(item.noticeId), item.noticeId);
        const latest = siteNoticeDetails.peek(String(item.noticeId));
        if (latest && latest.data) {
          memo.version = latest.data.version ?? null;
          memo.latest = { title: latest.data.title, body: latest.data.body, version: latest.data.version };
        } else {
          say(`${siteNoticeMessage(error, true)} 최신 내용을 불러오지 못했습니다.`);
        }
        bump();
      }
    } finally {
      setSaving(false);
    }
  };

  const set = (change: () => void) => { change(); bump(); };
  return (
    <div className="stack srv-site-form" aria-busy={saving || undefined}>
      <Field label="제목">
        <input name="title" value={memo.title} maxLength={300} required onChange={(event) => { const value = event.currentTarget.value; set(() => { memo.title = value; }); }} />
      </Field>
      <Field label="본문">
        <textarea name="body" rows={8} maxLength={20000} value={memo.body} onChange={(event) => { const value = event.currentTarget.value; set(() => { memo.body = value; }); }} />
      </Field>
      <div className="srv-site-row">
        <Field label="대상">
          <select name="audience" value={memo.audience} onChange={(event) => { const value = event.currentTarget.value; set(() => { memo.audience = value; }); }}>
            {WRITE_AUDIENCES.map((value) => <option key={value} value={value}>{siteAudienceLabel(value)}</option>)}
          </select>
        </Field>
        <Field label="게시 일시">
          <input name="publishAt" type="datetime-local" value={memo.publishAt} onChange={(event) => { const value = event.currentTarget.value; set(() => { memo.publishAt = value; }); }} />
        </Field>
      </div>
      <p className="tiny">
        {item && item.published !== false
          ? "게시 일시를 비워 두면 원래 게시 일시를 그대로 둡니다. 미래 일시를 넣으면 그때 다시 게시됩니다."
          : "게시 일시를 비워 두면 지금 게시합니다. 미래 일시를 넣으면 그때까지 운영자에게만 보입니다."}
      </p>
      <label className="check srv-site-check">
        <input name="pinned" type="checkbox" checked={memo.pinned} onChange={(event) => { const on = event.currentTarget.checked; set(() => { memo.pinned = on; }); }} />
        <span>고정 공지(목록 맨 위, 읽지 않은 사람에게 진입 공지로 보임)</span>
      </label>
      {picker.node}
      {memo.latest ? (
        <details className="srv-site-latest" open>
          <summary><b>서버의 최신 내용</b><span className="tiny">{` · 버전 ${memo.latest.version}`}</span></summary>
          <p className="tiny">{memo.latest.title}</p>
          {prose(memo.latest.body)}
        </details>
      ) : null}
      <div className="btn-row">
        <Button kind="primary" disabled={saving} aria-busy={saving || undefined} onClick={() => void saveNotice()}>
          {saving ? "저장하는 중…" : item ? "수정 저장" : "등록"}
        </Button>
        <Link href={item ? siteNoticeHref(item.noticeId) : listPath()} className="btn btn-ghost">취소</Link>
      </div>
      <p className={status.alert ? "err" : "tiny"} role={status.alert ? "alert" : "status"}>{status.text}</p>
    </div>
  );
}

function Denied() {
  return (
    <EmptyState
      title="공지를 관리할 수 없습니다"
      body="알림마당 운영자만 공지를 쓰고 고칠 수 있습니다."
      actions={<Link href={listPath()} className="btn btn-secondary">목록으로</Link>}
    />
  );
}

/** `?pop=notice-write[&id=srv-<noticeId>]`: operators only (server `canManage`). */
export function serverSiteNoticeWriteDialog(ctx: ScreenCtx): ReactNode {
  const ref = ctx.params && ctx.params.get("id");
  const id = ref ? siteNoticeIdOf(ref) : null;
  const title = id ? "공지 수정" : "공지 쓰기";
  let body: ReactNode;
  if (backendStatus() === "anon") body = backendPanel({ text: "ASTRA 서버에 로그인하면 공지를 관리할 수 있습니다." });
  else if (ref && !id) body = <EmptyState title="공지를 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." />;
  else body = id ? <EditNotice id={id} /> : <NewNotice />;
  return dialogShell(title, <div aria-live="polite">{body}</div>);
}

function NewNotice() {
  // 쓰기 진입은 목록의 `canManage`로 정한다. 아직 목록을 읽지 않았으면 첫 쪽을 한 번 읽는다.
  // undefined: checking; null: the check failed.
  const [manage, setManage] = useState<boolean | null | undefined>(() => siteManageState() ?? undefined);
  useEffect(() => {
    if (manage !== undefined) return;
    let live = true;
    void checkSiteManage().then((next) => { if (live) setManage(next); });
    return () => { live = false; };
  }, [manage]);
  if (manage === undefined) return <LoadingState title="권한을 확인하는 중입니다" />;
  if (manage === true) return <WriteForm memoKey="new" item={null} />;
  if (manage === false) return <Denied />;
  return <ErrorState title="권한을 확인하지 못했습니다" body="잠시 후 다시 시도하세요." actions={<RetryButton reload={() => setManage(undefined)} />} />;
}

function EditNotice({ id }: { id: number }) {
  const entry = useResource(siteNoticeDetails, String(id));
  const load = () => void siteNoticeDetails.load(String(id), id);
  useEffect(() => {
    if (!siteNoticeDetails.peek(String(id))) load();
  }, [id]);
  const item = entry && entry.data;
  if (!item) {
    if (!entry || !entry.error) return <LoadingState title="공지를 불러오는 중입니다" />;
    if (isMissing(entry.error)) return <EmptyState title="공지를 찾을 수 없습니다" body="삭제되었거나 볼 수 있는 대상이 아닙니다." />;
    return <ErrorState title="공지를 불러오지 못했습니다" body={siteNoticeMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  }
  if (!item.canManage) return <Denied />;
  const memo = memoFor(String(id));
  if (!memo.loaded) adopt(memo, item);
  return <WriteForm memoKey={String(id)} item={item} />;
}

// ---------- 홈: 알림마당 공지 줄과 진입 공지 ----------

function noticeTitle(notice: HomeSiteNotice): string {
  return notice.pinned ? `[고정] ${notice.title}` : notice.title ?? "";
}

/** 홈 `communityNotices`(최근 5개, 고정 먼저). 누르면 06 공지 창이 열린다. */
export function siteNoticeStrip(data: HomeData | null | undefined): ReactNode {
  const items = data && Array.isArray(data.communityNotices) ? data.communityNotices : [];
  return (
    <section className="home-notices srv-site-strip" aria-label="알림마당 공지">
      <div className="aside-h">
        <h2 className="section-label">알림마당 공지</h2>
        <Link href={listPath()} className="tiny">전체 보기</Link>
      </div>
      {items.length ? (
        <ul className="ntf-list">
          {items.map((notice) => (
            <li key={notice.noticeId}>
              <Link className={`ntf-row${notice.read ? " read" : ""}`} href={siteNoticeHref(notice.noticeId)}>
                {notice.read
                  ? <span className="ntf-dot" aria-hidden="true" />
                  : <span className="ntf-dot" role="img" aria-label="읽지 않은 공지" />}
                <span className="ntf-copy">
                  <b>{noticeTitle(notice)}</b>
                  <span className="ntf-when"><span>{koDate(notice.publishedAt)}</span></span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      ) : <p className="note">알림마당 공지가 없습니다.</p>}
    </section>
  );
}

/**
 * 홈 집계에 읽지 않은 고정 공지(`noticesForMe`)가 있으면 세션마다 한 번 진입 공지(`?pop=welcome`)를 연다.
 * 다른 창이 열려 있으면 열지 않는다. 사용자가 연 창이 아니라 history 항목을 만들지 않고 지금 항목의 주소만
 * 바꾼다(`replaceVisit`, `popInPlace`). 그래서 홈을 새 방문으로 보고 다시 읽지 않고, 닫기(router `closeDialog`)도
 * 같은 항목에서 창만 걷으며, 뒤로 가기는 홈 앞의 화면으로 간다.
 */
export function offerEntryNotices(data: HomeData | null | undefined, node: Node): void {
  const items = data && Array.isArray(data.noticesForMe) ? data.noticesForMe : [];
  if (!items.length || backendStatus() !== "ready") return;
  setTimeout(() => {
    if (!node.isConnected || (location.pathname !== HOME_PATH && location.pathname !== "/")) return;
    const params = new URLSearchParams(location.search);
    if (params.has("pop") || !claimEntryPopup()) return;
    params.set("pop", "welcome");
    const state: object = history.state && typeof history.state === "object" ? history.state : {};
    replaceVisit(`${HOME_PATH}?${params}`, { ...state, popInPlace: true });
    // The router draws the new address on popstate.
    window.dispatchEvent(new PopStateEvent("popstate"));
  }, 0);
}

// 띄운 진입 공지. 창이 다시 그려져도(토스트, 포커스) 같은 목록을 쓰고, 떠날 때 한 번만 읽음을 보낸다.
const entry: { items: HomeSiteNotice[] | null; live: boolean; listening: boolean } = { items: null, live: false, listening: false };
const onWelcome = () => new URLSearchParams(location.search).get("pop") === "welcome";

function leaveEntry(): void {
  if (!entry.live) return;
  entry.live = false;
  const ids = (entry.items || []).map((item) => item.noticeId);
  entry.items = null;
  if (ids.length) void dismissEntryNotices(ids);
}

function listenEntry(): void {
  if (entry.listening) return;
  entry.listening = true;
  // Escape는 App보다 먼저(capture) 본다. 뒤로 가기로 창을 벗어나도 떠난 것으로 친다.
  window.addEventListener("keydown", (event) => { if (event.key === "Escape" && onWelcome()) leaveEntry(); }, true);
  window.addEventListener("popstate", () => { if (!onWelcome()) leaveEntry(); });
}

/** `?pop=welcome` (00_notice_for_me) while `ready`: the unread pinned notices; leaving it sends `read` for each. */
export function serverEntryNotices(): ReactNode {
  // 닫기·바깥·확인·공지 링크: 창을 닫기 전에 읽음을 보낸다.
  return dialogShell("알림마당 공지", <EntryNotices />, false, () => {
    leaveEntry();
    closeDialog();
  });
}

function EntryNotices() {
  const frame = useRef<HTMLDivElement>(null);
  const home = useHome();
  useEffect(() => listenEntry(), []);
  if (!entry.items) {
    const data = home && home.data;
    if (!data) {
      return (
        <div ref={frame} aria-live="polite" className="srv-site-entry">
          {home && home.error
            ? <ErrorState title="공지를 불러오지 못했습니다" body={siteNoticeMessage(home.error)} />
            : <LoadingState title="공지를 불러오는 중입니다" />}
        </div>
      );
    }
    entry.items = (Array.isArray(data.noticesForMe) ? data.noticesForMe : []).slice();
    entry.live = true;
  }
  const items = entry.items;
  return (
    <div ref={frame} aria-live="polite" className="srv-site-entry">
      {items.length ? (
        <ul className="ntf-list">
          {items.map((item) => (
            <li key={item.noticeId}>
              <Link className="ntf-row" href={siteNoticeHref(item.noticeId)} onClick={() => leaveEntry()}>
                <span className="ntf-dot" role="img" aria-label="읽지 않은 공지" />
                <span className="ntf-copy">
                  <b>{noticeTitle(item)}</b>
                  <span className="ntf-when"><span>{koDate(item.publishedAt)}</span></span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      ) : <p className="note">새로 알릴 공지가 없습니다.</p>}
      {items.length ? <p className="tiny">확인을 누르면 위 공지를 읽음으로 표시하고 다시 띄우지 않습니다.</p> : null}
      <div className="btn-row">
        <Button kind="primary" onClick={() => { leaveEntry(); closeDialog(); }}>확인</Button>
      </div>
    </div>
  );
}
