// 알림마당 자료실(32) and 1:1 문의(31) from the ASTRA backend (status `anon` or `ready`). The lists
// are server data only. Anyone logged in writes a 1:1 문의 (`?pop=qna-write`) with files; 알림마당
// 운영자 answer inquiries in their detail, see everyone's inquiries (`?all=1`), and create, edit and
// delete materials (`?pop=material-write[&id=]`). Attachments have no course: they are uploaded
// without `courseId` and downloaded through `/api/files/{fileId}/content`. Typed text and picked
// files live in memory per form until the save succeeds or the session changes. Server text is text only.
import { useEffect, useReducer, useRef, useState, type ReactNode } from "react";
import { Button, Dialog, EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Link, closeDialog, navigate } from "../../router.tsx";
import { getState, render, toast } from "../../store.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { Screen, ScreenCtx } from "../../types.ts";
import { readQuery, writeQuery } from "../../shared/query.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, subscribe } from "../../shared/backend.ts";
import { freshVisit, useResource, type Resource } from "../../shared/remote-cache.ts";
import { koDate, koDateTime } from "../../shared/home-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { attachPicker, type AttachMemo } from "../../shared/attach-picker.tsx";
import {
  AUDIENCES, INQUIRY_STATUS, MATERIAL_AUDIENCES, MAX_TITLE, MAX_BODY, INQUIRY_UPLOADS, MATERIAL_UPLOADS,
  materialListKey, inquiryListKey, materialLists, materialDetails, inquiryLists, inquiryDetails,
  moreMaterials, moreInquiries, isAppending, markMaterialRead, audienceLabel, communityMessage,
  communityManageState, checkCommunityManage,
  createInquiry, answerInquiry, createMaterial, updateMaterial, deleteMaterial, inquiryHref,
  createUncertain, INQUIRY_CREATE, MATERIAL_CREATE, answerOperation, isCode, commandMessage,
  type CommunityFile, type CommunityList, type CommunityPost,
} from "../../shared/community-remote.ts";

const SEARCH_DELAY = 300;
const AUDIENCE_LABEL: Record<string, string> = { all: "전체", common: "공통", professor: "교수", student: "학생" };
const AUDIENCE_COUNT: Record<string, string> = { all: "total", common: "common", professor: "professor", student: "student" };
const STATUS_LABEL: Record<string, string> = { all: "전체", answered: "답변 완료", unanswered: "답변 대기" };
const STATUS_COUNT: Record<string, string> = { all: "total", answered: "answered", unanswered: "unanswered" };
const UNSURE = "결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 보내세요. 이미 등록되었다면 같은 글을 돌려받습니다.";

const qnaListPath = () => hrefFor(find("31_community_qna_list"));
const materialListPath = () => hrefFor(find("32_community_material_list"));

/** Same frame as the other home dialogs (`home-dialog`). */
function dialogShell(title: string, body: ReactNode): ReactNode {
  return <Dialog title={title} wide className="home-dialog">{body}</Dialog>;
}

const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;

function useBump(): () => void {
  const [, bump] = useReducer((n: number) => n + 1, 0);
  return bump;
}

// ---------- memory per form (cleared when the session changes) ----------

interface Memo extends AttachMemo {
  title: string;
  body: string;
  loaded: boolean;
  audience?: string;
  /** File ids of the material as last read (a reload keeps files someone else added). */
  seen?: Set<number>;
}

const memos = new Map<string, Memo>();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
});

function memoOf(key: string, initial: Partial<Memo> = {}): Memo {
  let memo = memos.get(key);
  if (!memo) {
    memo = { title: "", body: "", loaded: false, ...initial };
    memos.set(key, memo);
  }
  return memo;
}

// ---------- commands ----------

const inflight = new Set<string>(); // operations being sent; a remounted button stays disabled

/** Runs one operation at a time (`op`); `busy` while this button sends, `blocked` while any copy does. */
function useSending(op: string) {
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const send = async (work: () => Promise<void>): Promise<void> => {
    if (busy || inflight.has(op)) return;
    inflight.add(op);
    setBusy(true);
    try {
      await work();
    } finally {
      inflight.delete(op);
      setBusy(false);
      // A remount while the command ran drew a fresh button for the same operation, disabled; free it.
      if (!mounted.current) render();
    }
  };
  return { busy, blocked: busy || inflight.has(op), send };
}

function SendButton({ label, busyLabel, op, kind = "primary", disabled = false, run }: {
  label: string;
  busyLabel: string;
  op: string;
  kind?: "primary" | "ghost";
  disabled?: boolean;
  run: () => Promise<void>;
}) {
  const { busy, blocked, send } = useSending(op);
  return (
    <Button kind={kind} disabled={blocked || disabled} aria-busy={blocked || undefined} onClick={() => void send(run)}>
      {busy ? busyLabel : label}
    </Button>
  );
}

/** Two presses: the first arms the button, the second runs `work`. */
function DeleteButton({ label, op, work }: { label: string; op: string; work: () => Promise<void> }) {
  const [armed, setArmed] = useState(false);
  const { busy, blocked, send } = useSending(op);
  return (
    <Button
      kind="ghost"
      disabled={blocked}
      aria-busy={blocked || undefined}
      aria-label={armed ? `${label} 삭제, 한 번 더 누르면 삭제` : `${label} 삭제`}
      onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        void send(work);
      }}
    >
      {busy ? "삭제하는 중…" : armed ? "한 번 더 누르면 삭제" : "삭제"}
    </Button>
  );
}

// ---------- small views ----------

function Retry({ reload }: { reload: () => void }) {
  const [sent, setSent] = useState(false);
  return <Button kind="ghost" disabled={sent} onClick={() => { setSent(true); reload(); }}>다시 불러오기</Button>;
}

function prose(text: string | null | undefined, empty = "본문이 없습니다."): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? <p className="tiny">{empty}</p> : null;
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

/** Attached files of a parent outside any course (`GET /api/files/{fileId}/content`). */
function files(list: CommunityFile[] | null | undefined): ReactNode {
  const items = Array.isArray(list) ? list.filter((file) => file && file.fileId != null) : [];
  if (!items.length) return null;
  return (
    <div className="srv-cq-files" role="group" aria-label="첨부 파일">
      {items.map((file) => (
        <div key={file.fileId} className="file-row">
          <Icon name="file" />
          <span><b>{file.name || "파일"}</b><span className="tiny">{sizeText(file.sizeBytes)}</span></span>
          {downloadControl(null, { fileId: file.fileId, name: file.name || "파일" })}
        </div>
      ))}
    </div>
  );
}

interface Line { text: string; alert: boolean }

function SayLine({ line }: { line: Line | null }) {
  return (
    <p className={line && line.alert ? "err" : "tiny"} role={line && line.alert ? "alert" : "status"} aria-live="polite">
      {line ? line.text : ""}
    </p>
  );
}

function useSay(): [Line | null, (text: string, alert?: boolean) => void] {
  const [line, setLine] = useState<Line | null>(null);
  return [line, (text, alert = false) => setLine({ text, alert })];
}

function TextInput({ memo, name, label, max, multiline = false, rows = 8, placeholder = "", required = false }: {
  memo: Memo;
  name: "title" | "body";
  label: string;
  max: number;
  multiline?: boolean;
  rows?: number;
  placeholder?: string;
  required?: boolean;
}) {
  const bump = useBump();
  const common = {
    maxLength: max,
    placeholder,
    "aria-required": required ? true : undefined,
    value: memo[name] || "",
  };
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {multiline
        ? <textarea rows={rows} {...common} onChange={(event) => { memo[name] = event.currentTarget.value; bump(); }} />
        : <input type="text" {...common} onChange={(event) => { memo[name] = event.currentTarget.value; bump(); }} />}
    </label>
  );
}

function AudienceSelect({ memo }: { memo: Memo }) {
  const bump = useBump();
  if (!memo.audience || !MATERIAL_AUDIENCES.includes(memo.audience)) memo.audience = "common";
  return (
    <label className="field">
      <span className="field-label">대상</span>
      <select value={memo.audience} onChange={(event) => { memo.audience = event.currentTarget.value; bump(); }}>
        {MATERIAL_AUDIENCES.map((value) => <option key={value} value={value}>{AUDIENCE_LABEL[value]}</option>)}
      </select>
    </label>
  );
}

/** The current address with `changes` applied (null removes a parameter). */
function here(ctx: ScreenCtx, changes: Record<string, string | null>): string {
  const params = new URLSearchParams(ctx.params ? ctx.params.toString() : "");
  for (const [name, value] of Object.entries(changes)) {
    if (value == null || value === "") params.delete(name);
    else params.set(name, String(value));
  }
  const q = params.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function SearchBox({ title, ctx, scope }: { title: string; ctx: ScreenCtx; scope: string }) {
  return (
    <label className="search board-search">
      <Icon name="search" />
      <input
        value={readQuery(ctx.state, scope)}
        onChange={(event) => {
          writeQuery(ctx.state, scope, event.target.value);
          render();
        }}
        placeholder="검색"
        aria-label={`${title} 제목 검색`}
      />
    </label>
  );
}

function MoreButton({ listKey, run }: { listKey: string; run: () => Promise<unknown> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const on = busy || isAppending(listKey);
  return (
    <div className="btn-row">
      <Button kind="secondary" disabled={on} aria-busy={busy || undefined} onClick={() => {
        if (on) return;
        setBusy(true);
        setError(null);
        run().then(() => setBusy(false), (problem: unknown) => {
          setBusy(false);
          setError(communityMessage(problem));
        });
      }}>
        {on ? "불러오는 중…" : "더 보기"}
      </Button>
      {error ? <p className="err" role="alert">{error}</p> : null}
    </div>
  );
}

function visitList(resource: Resource<CommunityList>, scope: string, key: string, load: () => void): void {
  const fresh = freshVisit(scope, key);
  if (fresh === "entry") load();
  else if (!resource.peek(key)) {
    if (fresh === "key") setTimeout(() => { if (!resource.peek(key)) load(); }, SEARCH_DELAY);
    else load();
  }
}

// ---------- lists ----------

/** The 알림마당 자료실 or 1:1 문의 list in backend mode; `head` is the page header built by home. */
export function remoteCommunityBoard(screen: Screen, ctx: ScreenCtx, head: ReactNode): ReactNode {
  if (backendStatus() === "anon") {
    return (
      <section className="board" data-area="home">
        {head}
      </section>
    );
  }
  return <CommunityBoard screen={screen} ctx={ctx} head={head} />;
}

function CommunityBoard({ screen, ctx, head }: { screen: Screen; ctx: ScreenCtx; head: ReactNode }) {
  const material = screen.board === "material";
  const scope = `home:board:${screen.board}`;
  const q = readQuery(ctx.state, scope).trim().slice(0, 100);
  const params = ctx.params || new URLSearchParams();
  // `?all=1` is the operator's view of everyone's inquiries; a non-operator gets 404 and their own list.
  const all = !material && params.get("all") === "1";
  const names = material ? AUDIENCES : INQUIRY_STATUS;
  const param = material ? "audience" : "status";
  const asked = params.get(param);
  const filter = asked && names.includes(asked) ? asked : "all";
  const resource = material ? materialLists : inquiryLists;
  const key = material ? materialListKey(filter, q) : inquiryListKey(filter, q, all);
  const load = () => void (material ? materialLists.load(key, filter, q) : inquiryLists.load(key, filter, q, all));
  const entry = useResource(resource, key, () => {
    const current = resource.peek(key);
    if (!current || !current.data || current.data.pages === 1) load();
  });
  useEffect(() => {
    visitList(resource, material ? "community-materials" : `community-inquiries${all ? "-all" : ""}`, key, load);
  });
  const data = entry && entry.data;
  if (all && !data && entry && missing(entry.error)) {
    // Not an operator (any more): the own list, without the toggle.
    const own = new URLSearchParams(params.toString());
    own.delete("all");
    return <CommunityBoard screen={screen} ctx={{ ...ctx, params: own }} head={head} />;
  }
  const listPath = hrefFor(find(screen.id));
  const query = (extra: Record<string, string>) => {
    const next = new URLSearchParams();
    if (all) next.set("all", "1");
    for (const [name, value] of Object.entries(extra)) if (value !== "all") next.set(name, value);
    const text = next.toString();
    return `${listPath}${text ? `?${text}` : ""}`;
  };
  const counts = data ? data.counts : null;
  const labels = material ? AUDIENCE_LABEL : STATUS_LABEL;
  const countNames = material ? AUDIENCE_COUNT : STATUS_COUNT;
  const chips = (
    <div className="filters" role="toolbar" aria-label={material ? "자료 대상" : "답변 상태"}>
      {names.map((value) => {
        const label = labels[value] ?? value;
        const count = counts ? counts[countNames[value] ?? ""] : null;
        return (
          <Link key={value} href={query({ [param]: value })} className={`chip${filter === value ? " on all" : ""}`} aria-current={filter === value ? "true" : undefined}>
            {count != null ? `${label} ${count}` : label}
          </Link>
        );
      })}
    </div>
  );
  // Operator tools: the inquiry scope toggle or "자료 등록", shown when the list says `canManage`.
  let tools: ReactNode = null;
  if (data && data.canManage === true) {
    if (material) {
      tools = <Link href={`${listPath}?${new URLSearchParams({ pop: "material-write" })}`} className="btn btn-primary">자료 등록</Link>;
    } else {
      const mineHref = `${listPath}${filter !== "all" ? `?status=${filter}` : ""}`;
      const allHref = `${listPath}?${new URLSearchParams({ all: "1", ...(filter !== "all" ? { status: filter } : {}) })}`;
      tools = (
        <div className="filters" role="toolbar" aria-label="문의 범위">
          <Link href={mineHref} className={`chip${all ? "" : " on all"}`} aria-current={all ? undefined : "true"}>내 문의</Link>
          <Link href={allHref} className={`chip${all ? " on all" : ""}`} aria-current={all ? "true" : undefined}>전체 문의</Link>
        </div>
      );
    }
  }
  const itemHref = (postId: string) => {
    const next = new URLSearchParams({ ...(all ? { all: "1" } : {}), ...(filter !== "all" ? { [param]: filter } : {}), pop: material ? "material" : "inquiry", id: String(postId) });
    return `${listPath}?${next}`;
  };

  let list: ReactNode;
  if (!data) {
    if (!entry || !entry.error) list = <LoadingState title={material ? "자료를 불러오는 중입니다" : "문의를 불러오는 중입니다"} />;
    else list = <ErrorState title={material ? "자료를 불러오지 못했습니다" : "문의를 불러오지 못했습니다"} body={communityMessage(entry.error, material ? "자료" : "문의")} actions={<Retry reload={load} />} />;
  } else {
    const stale = entry && entry.error ? <p className="err" role="alert">{communityMessage(entry.error, material ? "자료" : "문의")}</p> : null;
    if (!data.items.length) {
      list = (
        <>
          {stale}
          {q || filter !== "all"
            ? <EmptyState title="조건에 맞는 글이 없습니다" body="다른 조건을 고르거나 검색어를 지워 보세요." />
            : <EmptyState title="조회할 자료가 없습니다" body={material ? "등록된 자료가 없습니다." : all ? "받은 1:1 문의가 없습니다." : "보낸 1:1 문의가 없습니다."} />}
        </>
      );
    } else {
      list = (
        <>
          {stale}
          <div className="board-list">
            {data.items.map((item) => {
              const meta = material
                ? [audienceLabel(item.audience ?? ""), koDate(item.publishedAt), `조회 ${item.viewCount ?? 0}`, item.read ? null : "안 읽음"]
                : [all ? item.authorName : null, item.answered ? `답변 완료${item.answeredAt ? ` · ${koDate(item.answeredAt)}` : ""}` : "답변 대기", koDate(item.createdAt), item.answerCount ? `답변 ${item.answerCount}` : null];
              return (
                <Link key={item.postId} className="board-card" href={itemHref(item.postId)}>
                  <b>{item.title}</b>
                  <span className="tiny">{meta.filter(Boolean).join(" · ")}</span>
                  {material && item.excerpt ? <p>{item.excerpt}</p> : null}
                </Link>
              );
            })}
          </div>
          {data.nextCursor != null ? <MoreButton listKey={key} run={() => (material ? moreMaterials(filter, q) : moreInquiries(filter, q, all))} /> : null}
        </>
      );
    }
  }
  return (
    <section className="board" data-area="home">
      {head}
      <div className="srv-cq-tools">{tools}</div>
      {chips}
      <SearchBox title={material ? "자료실" : "1:1 문의"} ctx={ctx} scope={scope} />
      <div aria-live="polite">{list}</div>
    </section>
  );
}

function idParam(ctx: ScreenCtx): string | null {
  const id = Number(ctx.params && ctx.params.get("id"));
  return Number.isInteger(id) && id > 0 ? String(id) : null;
}

// ---------- 자료실 detail ----------

/** `?pop=material&id=` over 자료실 in backend mode. */
export function remoteMaterialDialog(ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "anon") {
    return dialogShell("자료실", <div aria-live="polite"></div>);
  }
  const id = idParam(ctx);
  if (!id) {
    return dialogShell("자료실", <div aria-live="polite"><EmptyState title="자료를 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." /></div>);
  }
  return dialogShell("자료실", <MaterialDetail ctx={ctx} id={id} />);
}

function MaterialDetail({ ctx, id }: { ctx: ScreenCtx; id: string }) {
  const entry = useResource(materialDetails, id);
  const [readNote, setReadNote] = useState("");
  const load = () => void materialDetails.load(id, id);
  useEffect(() => {
    if (freshVisit("community-material", id) === "entry" || !materialDetails.peek(id)) load();
  }, [id]);
  const item = entry && entry.data;
  const unread = Boolean(item && !item.read);
  useEffect(() => {
    if (!unread) return;
    let live = true;
    markMaterialRead(id).catch((error: unknown) => {
      if (live) setReadNote(`읽음 표시를 남기지 못했습니다. ${communityMessage(error)}`);
    });
    return () => { live = false; };
  }, [id, unread]);
  let body: ReactNode;
  if (!item) {
    if (!entry || !entry.error) body = <LoadingState title="자료를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="자료를 찾을 수 없습니다" body="삭제되었거나 볼 수 있는 대상이 아닙니다." />;
    else body = <ErrorState title="자료를 불러오지 못했습니다" body={communityMessage(entry.error)} actions={<Retry reload={load} />} />;
  } else {
    body = (
      <>
        <h3>{item.title}</h3>
        <p className="tiny">{[audienceLabel(item.audience ?? ""), koDateTime(item.publishedAt), `조회 ${item.viewCount ?? 0}`].filter(Boolean).join(" · ")}</p>
        {prose(item.body)}
        {files(item.attachments)}
        <p className="tiny" role="status">{readNote}</p>
        {item.canManage ? (
          <div className="btn-row srv-cq-actions">
            <Link href={here(ctx, { pop: "material-write", id })} className="btn btn-secondary">수정</Link>
            <DeleteButton label={item.title ?? ""} op={`material-delete:${id}`} work={async () => {
              try {
                await deleteMaterial(id);
                getState().toast = "자료를 삭제했습니다";
                navigate(materialListPath());
              } catch (error) {
                if (missing(error)) {
                  getState().toast = "이미 삭제되었거나 찾을 수 없는 자료입니다";
                  navigate(materialListPath());
                  return;
                }
                toast(commandMessage(error, "자료"));
              }
            }} />
          </div>
        ) : null}
      </>
    );
  }
  return <div aria-live="polite">{body}</div>;
}

// ---------- 자료 등록·수정 (operators) ----------

/** `?pop=material-write` (new) and `?pop=material-write&id=` (edit) over 자료실 in backend mode. */
export function remoteMaterialWriteDialog(ctx: ScreenCtx): ReactNode {
  const id = idParam(ctx);
  const title = id ? "자료 수정" : "자료 등록";
  if (backendStatus() === "anon") {
    return dialogShell(title, <div className="srv-cq-form"></div>);
  }
  return dialogShell(title, <div className="srv-cq-form">{id ? <MaterialEdit id={id} /> : <MaterialNew />}</div>);
}

function NotAllowed() {
  return (
    <EmptyState
      title="자료는 알림마당 운영자만 올리고 고칠 수 있습니다"
      body="목록에서 자료를 볼 수 있습니다."
      actions={<Link href={materialListPath()} className="btn btn-secondary">자료실로</Link>}
    />
  );
}

function MaterialNew() {
  // 등록 진입은 자료실·문의 목록의 `canManage`로 정한다. 아직 목록을 읽지 않았으면 자료실 첫 쪽을 한 번 읽는다.
  // undefined: checking; null: the check failed.
  const [manage, setManage] = useState<boolean | null | undefined>(() => communityManageState() ?? undefined);
  useEffect(() => {
    if (manage !== undefined) return;
    let live = true;
    void checkCommunityManage().then((next) => { if (live) setManage(next); });
    return () => { live = false; };
  }, [manage]);
  if (manage === undefined) return <LoadingState title="권한을 확인하는 중입니다" />;
  if (manage === false) return <NotAllowed />;
  if (manage === null) return <ErrorState title="권한을 확인하지 못했습니다" body="잠시 후 다시 시도하세요." actions={<Retry reload={() => setManage(undefined)} />} />;
  return <MaterialForm id={null} current={null} />;
}

function MaterialEdit({ id }: { id: string }) {
  const entry = useResource(materialDetails, id);
  const current = useRef<CommunityPost | null>(null); // the material being edited, as last read
  const load = () => void materialDetails.load(id, id);
  useEffect(() => {
    if (freshVisit("community-material-edit", id) === "entry" || !materialDetails.peek(id)) load();
  }, [id]);
  const item = entry && entry.data;
  if (!item) {
    if (current.current) {
      const stale = entry && entry.error
        ? { text: missing(entry.error) ? "삭제되었거나 고칠 수 없는 자료입니다." : communityMessage(entry.error), alert: true }
        : null;
      return current.current.canManage ? <MaterialForm id={id} current={current.current} stale={stale} /> : <NotAllowed />;
    }
    if (!entry || !entry.error) return <LoadingState title="자료를 불러오는 중입니다" />;
    if (missing(entry.error)) return <EmptyState title="자료를 찾을 수 없습니다" body="삭제되었거나 고칠 수 있는 권한이 없습니다." />;
    return <ErrorState title="자료를 불러오지 못했습니다" body={communityMessage(entry.error)} actions={<Retry reload={load} />} />;
  }
  if (item !== current.current) {
    current.current = item;
    if (item.canManage) {
      const memo = memoOf(`material-form:${id}`, { audience: "common" });
      const ids = (Array.isArray(item.attachments) ? item.attachments : []).map((file) => Number(file.fileId));
      // A reload after 409 keeps typed text and choices; files someone else added stay attached.
      const { keep, seen } = memo;
      if (keep instanceof Set && seen instanceof Set) for (const fileId of ids) if (!seen.has(fileId)) keep.add(fileId);
      memo.seen = new Set(ids);
      if (!memo.loaded) Object.assign(memo, { title: item.title || "", body: item.body || "", audience: item.audience || "common", loaded: true });
    }
  }
  if (!item.canManage) return <NotAllowed />;
  // A new version rebuilds the form (and its file list) from the memo.
  return <MaterialForm key={item.version ?? 0} id={id} current={item} />;
}

function MaterialForm({ id, current, stale = null }: { id: string | null; current: CommunityPost | null; stale?: Line | null }) {
  const memoKey = `material-form:${id || "new"}`;
  const memo = memoOf(memoKey, { audience: "common" });
  const bump = useBump();
  const [line, say] = useSay();
  const existing = current && Array.isArray(current.attachments)
    ? current.attachments.map((file) => ({ fileId: Number(file.fileId), name: file.name, sizeBytes: file.sizeBytes }))
    : [];
  const picker = attachPicker({ memo, courseId: null, purpose: "community_material", max: MATERIAL_UPLOADS, existing, onChange: bump });

  const saveMaterial = async (): Promise<void> => {
    const title = memo.title.trim();
    if (!title) return say("제목을 입력하세요.", true);
    if (picker.pending()) return say("첨부 파일 검사가 끝난 뒤 저장하세요.", true);
    const text = { title, body: memo.body.trim() ? memo.body : null, audience: memo.audience || "common" };
    try {
      let view: CommunityPost;
      if (id) {
        if (!current) return say("자료를 불러온 뒤 저장하세요.", true);
        const files = picker.attachments();
        view = await updateMaterial(id, {
          ...text,
          ...(picker.changed() ? { attachments: { keep: files.keep.map(String), add: files.add.map(String) } } : {}),
          version: current.version ?? 0,
        });
      } else {
        view = await createMaterial({ ...text, uploadIds: picker.uploadIds().map(String) });
      }
      memos.delete(memoKey);
      getState().toast = id ? "자료를 고쳤습니다" : "자료를 등록했습니다";
      navigate(`${materialListPath()}?${new URLSearchParams({ pop: "material", id: String(view.postId) })}`);
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (id && (isCode(error, 409, "version_conflict") || missing(error))) void materialDetails.load(id, id);
      if (!id && missing(error)) return say("자료는 알림마당 운영자만 올릴 수 있습니다.", true);
      say(!id && createUncertain(MATERIAL_CREATE) ? `저장 ${UNSURE}` : commandMessage(error, "자료"), true);
    }
  };

  return (
    <>
      <TextInput memo={memo} name="title" label="제목" max={MAX_TITLE} placeholder="제목을 입력하세요" required />
      <AudienceSelect memo={memo} />
      <TextInput memo={memo} name="body" label="내용" max={MAX_BODY} multiline rows={10} placeholder="내용을 입력하세요" />
      {picker.node}
      <SayLine line={stale ?? line} />
      <div className="btn-row srv-cq-actions">
        <Button kind="ghost" onClick={() => closeDialog()}>취소</Button>
        <SendButton
          label="저장"
          busyLabel="저장하는 중…"
          op={`save:${memoKey}`}
          disabled={picker.pending() || Boolean(id && (!current || !current.canManage))}
          run={saveMaterial}
        />
      </div>
    </>
  );
}

// ---------- 1:1 문의 detail and answer ----------

/** `?pop=inquiry&id=` over 1:1 문의 in backend mode: the question, its replies and, for operators, the answer form. */
export function remoteInquiryDialog(ctx: ScreenCtx): ReactNode {
  if (backendStatus() === "anon") {
    return dialogShell("1:1 문의", <div aria-live="polite"></div>);
  }
  const id = idParam(ctx);
  if (!id) {
    return dialogShell("1:1 문의", <div aria-live="polite"><EmptyState title="문의를 찾을 수 없습니다" body="목록으로 돌아가 다시 선택해 주세요." /></div>);
  }
  return dialogShell("1:1 문의", <InquiryDetail id={id} />);
}

function InquiryDetail({ id }: { id: string }) {
  const entry = useResource(inquiryDetails, id);
  const load = () => void inquiryDetails.load(id, id);
  useEffect(() => {
    if (freshVisit("community-inquiry", id) === "entry" || !inquiryDetails.peek(id)) load();
  }, [id]);
  const item = entry && entry.data;
  let body: ReactNode;
  if (!item) {
    if (!entry || !entry.error) body = <LoadingState title="문의를 불러오는 중입니다" />;
    else if (missing(entry.error)) body = <EmptyState title="문의를 찾을 수 없습니다" body="본인이 쓴 문의만 볼 수 있습니다." />;
    else body = <ErrorState title="문의를 불러오지 못했습니다" body={communityMessage(entry.error, "문의")} actions={<Retry reload={load} />} />;
  } else {
    const replies = Array.isArray(item.replies) ? item.replies : [];
    body = (
      <>
        <h3>{item.title}</h3>
        <p className="tiny">{[item.mine === false && item.authorName ? item.authorName : null, koDateTime(item.createdAt), item.answered ? "답변 완료" : "답변 대기"].filter(Boolean).join(" · ")}</p>
        {prose(item.body)}
        {files(item.attachments)}
        <h3>{`답변 ${replies.length}`}</h3>
        {replies.length ? (
          <div className="stack">
            {replies.map((reply) => (
              <article key={reply.replyId} className="sheet">
                <p className="tiny">{[reply.authorName, koDateTime(reply.createdAt)].filter(Boolean).join(" · ")}</p>
                {prose(reply.body, "")}
                {files(reply.attachments)}
              </article>
            ))}
          </div>
        ) : (
          <p className="tiny">{item.canAnswer ? "아직 답변이 없습니다." : "아직 답변이 없습니다. 답변이 등록되면 여기에 표시됩니다."}</p>
        )}
        {item.canAnswer ? <AnswerForm id={id} /> : null}
      </>
    );
  }
  return <div aria-live="polite">{body}</div>;
}

function AnswerForm({ id }: { id: string }) {
  const memoKey = `inquiry-answer:${id}`;
  const memo = memoOf(memoKey);
  const bump = useBump();
  const [line, say] = useSay();
  const picker = attachPicker({ memo, courseId: null, purpose: "inquiry_attachment", max: INQUIRY_UPLOADS, onChange: bump });
  const answer = async (): Promise<void> => {
    if (!memo.body.trim()) return say("답변 내용을 입력하세요.", true);
    if (picker.pending()) return say("첨부 파일 검사가 끝난 뒤 등록하세요.", true);
    try {
      await answerInquiry(id, { body: memo.body, uploadIds: picker.uploadIds().map(String) });
      memos.delete(memoKey);
      // Repaint once more so the form comes back empty under the new reply.
      inquiryDetails.patch((key) => key === id, () => {});
      toast("답변을 등록했습니다");
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (missing(error)) void inquiryDetails.load(id, id);
      say(createUncertain(answerOperation(id)) ? `등록 ${UNSURE}` : commandMessage(error, "답변"), true);
    }
  };
  return (
    <section className="srv-cq-form" aria-label="답변 쓰기">
      <h3>답변 쓰기</h3>
      <TextInput memo={memo} name="body" label="답변" max={MAX_BODY} multiline rows={6} placeholder="답변을 입력하세요" required />
      {picker.node}
      <SayLine line={line} />
      <div className="btn-row srv-cq-actions">
        <SendButton label="답변 등록" busyLabel="등록하는 중…" op={`answer:${id}`} disabled={picker.pending()} run={answer} />
      </div>
    </section>
  );
}

// ---------- 1:1 질문하기 ----------

/** `?pop=qna-write` over 1:1 문의 in backend mode: `POST /api/community/qna` with files. */
export function remoteInquiryWriteDialog(): ReactNode {
  if (backendStatus() === "anon") {
    return dialogShell("1:1 질문하기", <div className="srv-cq-form"></div>);
  }
  return dialogShell("1:1 질문하기", <div className="srv-cq-form"><InquiryForm /></div>);
}

function InquiryForm() {
  const memoKey = "inquiry-form:new";
  const memo = memoOf(memoKey);
  const bump = useBump();
  const [line, say] = useSay();
  const picker = attachPicker({ memo, courseId: null, purpose: "inquiry_attachment", max: INQUIRY_UPLOADS, onChange: bump });
  const create = async (): Promise<void> => {
    const title = memo.title.trim();
    if (!title) return say("제목을 입력하세요.", true);
    if (picker.pending()) return say("첨부 파일 검사가 끝난 뒤 등록하세요.", true);
    try {
      const view = await createInquiry({ title, body: memo.body.trim() ? memo.body : null, uploadIds: picker.uploadIds().map(String) });
      memos.delete(memoKey);
      getState().toast = "문의를 등록했습니다";
      navigate(inquiryHref(view.postId));
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (createUncertain(INQUIRY_CREATE)) say(`등록 ${UNSURE}`, true);
      else say(missing(error) ? "이 계정으로는 1:1 문의를 보낼 수 없습니다." : commandMessage(error, "문의"), true);
    }
  };
  return (
    <>
      <p className="tiny">보낸 문의는 본인과 알림마당 운영자만 봅니다. 보낸 뒤에는 고치거나 지울 수 없습니다.</p>
      <TextInput memo={memo} name="title" label="제목" max={MAX_TITLE} placeholder="제목을 입력하세요" required />
      <TextInput memo={memo} name="body" label="내용" max={MAX_BODY} multiline rows={10} placeholder="문의 내용을 입력하세요" />
      {picker.node}
      <SayLine line={line} />
      <div className="btn-row srv-cq-actions">
        <Link href={qnaListPath()} className="btn btn-ghost">취소</Link>
        <SendButton label="등록" busyLabel="등록하는 중…" op="inquiry-create" disabled={picker.pending()} run={create} />
      </div>
    </>
  );
}
