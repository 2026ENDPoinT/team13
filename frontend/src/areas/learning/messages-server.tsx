import { useEffect, useLayoutEffect, useReducer, useRef, useState, type ReactNode } from "react";
import { Button, Dialog, EmptyState, ErrorState, Field, LoadingState, Badge } from "../../ui/kit.tsx";
import { Icon } from "../../ui/icon.tsx";
import { Link, closeDialog, navigate } from "../../router.tsx";
import { toast } from "../../store.ts";
import type { ScreenCtx } from "../../types.ts";
import { hrefFor, find } from "../../manifest.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { backendStatus, backendSettled, connect, courseIdFor, backendState, subscribe } from "../../shared/backend.ts";
import { useResource, freshVisit } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { useUnread } from "../../shared/unread-badges.tsx";
import { boxList, messageFiles, filesProblem, recipientSearch, picked, SCOPE_TEXT } from "../../shared/message-box.tsx";
import type { AttachMemo } from "../../shared/attach-picker.tsx";
import {
  receivedDetails, sentDetails, markRead, sendMessage, replyMessage, forwardMessage, recallMessage, deleteMessage,
  loadDraft, saveDraft, isDraftStale, isMissing, isUncertain, isAttachmentNotReady, messageIdOf, sentHref, messageMessage,
  screenBytes, ROLE_LABEL, MAX_TITLE, MAX_BODY_BYTES, MAX_RECIPIENTS, MAX_DRAFT_BODY,
  type MessageDetail, type MessagePerson, type MessageDraft, type RecallResult,
} from "../../shared/messages-remote.ts";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 받은 쪽지와 보낸 쪽지를 보고 쪽지를 보낼 수 있습니다.";
const DRAFT_DELAY = 2000;
type Mode = { kind: "new"; from: null; messageId: null; key: string }
  | { kind: "reply"; from: "inbox"; messageId: number; key: string }
  | { kind: "forward"; from: "inbox" | "sent"; messageId: number; key: string };
interface Memo {
  title: string | null;
  body: string | null;
  courseId: number | null | undefined;
  courseName?: string;
  recipients: Map<number, MessagePerson>;
  files: AttachMemo;
  revision: number;
  draftChecked: boolean;
  draftNote: string;
  draftAlert: boolean;
  savedTitle: string | null;
  savedBody: string | null;
  savedScope: string | null;
  drafting: boolean;
  sending: boolean;
}
const memos = new Map<string, Memo>();
const recalls = new Map<number, RecallResult>();
let picking: { mode: string; chosen: Map<number, MessagePerson> } | null = null;
let draftFloor = 0;
let session = "";
let generation = 0;
subscribe((next) => {
  const id = `${next.status}:${next.me?.userId ?? ""}`;
  if (id === session) return;
  session = id;
  generation++;
  memos.clear();
  recalls.clear();
  picking = null;
  draftFloor = 0;
});
const screenHref = (id: string, extra: Record<string, string> = {}) => hrefFor(find(id), extra);

export function usesServerMessages(params: URLSearchParams | null | undefined): boolean {
  return backendStatus() !== "off" || ["id", "reply", "forward"].some((name) => messageIdOf(params?.get(name)) != null);
}
function modeOf(params: URLSearchParams): Mode {
  const reply = messageIdOf(params.get("reply"));
  if (reply) return { kind: "reply", from: "inbox", messageId: reply, key: `reply:${reply}` };
  const forward = messageIdOf(params.get("forward"));
  if (forward) {
    const from = params.get("box") === "sent" ? "sent" : "inbox";
    return { kind: "forward", from, messageId: forward, key: `forward:${from}:${forward}` };
  }
  return { kind: "new", from: null, messageId: null, key: "new" };
}
function memoOf(mode: Mode): Memo {
  let memo = memos.get(mode.key);
  if (!memo) {
    memo = { title: null, body: null, courseId: undefined, recipients: new Map(), files: {}, revision: draftFloor,
      draftChecked: false, draftNote: "", draftAlert: false, savedTitle: null, savedBody: null, savedScope: null,
      drafting: false, sending: false };
    memos.set(mode.key, memo);
  }
  return memo;
}
function courseLabel(courseId: number | null): string {
  if (courseId === null) return "과목선택 안함";
  const course = backendState().courses.find((item) => Number(item.courseId) === courseId);
  return course?.name || course?.key || "";
}
function Prose({ text }: { text: string }): ReactNode {
  return <div className="prose">{text.split(/\n{2,}/).map((block) => block.trim()).filter(Boolean).map((block, i) =>
    <p key={i}>{block.split("\n").map((line, j) => <span key={j}>{j > 0 && <br />}{line}</span>)}</p>)}</div>;
}
function Gate({ children }: { children: ReactNode }): ReactNode {
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    let alive = true;
    const stop = subscribe(() => { if (alive) redraw(); });
    if (backendStatus() === "off" && !backendSettled()) void connect().then(() => { if (alive) redraw(); });
    return () => { alive = false; stop(); };
  }, []);
  if (backendStatus() === "anon") return backendPanel({ text: LOGIN_TEXT });
  if (backendStatus() === "off") {
    if (!backendSettled()) return <LoadingState title="ASTRA 서버에 연결하는 중입니다" />;
    return <EmptyState title="쪽지를 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다. 받은 쪽지 목록에서 다른 쪽지를 선택하세요."
      actions={<Link className="btn btn-secondary" href={screenHref("02b_msg_window")}>받은 쪽지</Link>} />;
  }
  return <>{children}</>;
}
function Tabs({ folder }: { folder: string }): ReactNode {
  const unread = useUnread("messages");
  const tabs = [["write", "02e_msg_compose", "쪽지쓰기"], ["inbox", "02b_msg_window", unread ? `받은쪽지 ${unread}` : "받은쪽지"], ["sent", "02d_msg_sent_tab", "보낸쪽지"]];
  return <div className="msg-tabs" role="tablist">{tabs.map(([key, id, label]) =>
    <Link key={key} href={screenHref(id)} className={folder === key ? "on" : ""} role="tab" aria-selected={folder === key}>{label}</Link>)}</div>;
}
function Attachments({ files }: { files: MessageDetail["attachments"] }): ReactNode {
  const list = (files || []).filter((file) => file.fileId != null);
  if (!list.length) return null;
  return <section className="srv-msg-files"><h3 className="msg-sub">첨부 파일 {list.length}개</h3><div className="stack">{list.map((file) =>
    <div className="file-row" key={file.fileId}><Icon name="file" /><span><b>{file.name || "파일"}</b><span className="tiny">{sizeText(file.sizeBytes)}</span></span>{downloadControl(null, file)}</div>)}</div>
    <p className="tiny">회수된 쪽지나 보관함에서 지운 쪽지의 파일은 더 받을 수 없습니다.</p></section>;
}
function Detail({ id, sent }: { id: number; sent: boolean }): ReactNode {
  const resource = sent ? sentDetails : receivedDetails;
  const key = String(id);
  const load = () => { void resource.load(key, id); };
  const entry = useResource(resource, key, load);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  const working = useRef(false);
  const alive = useRef(false);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (freshVisit(`msg-detail-${sent ? "02d_msg_sent_tab" : "02b_msg_window"}`, key) === "entry" || !resource.peek(key)) void resource.load(key, id);
  }, [resource, key, id, sent]);
  const listHref = screenHref(sent ? "02d_msg_sent_tab" : "02b_msg_window");
  if (entry?.error && isMissing(entry.error)) return <EmptyState title="쪽지를 찾을 수 없습니다" body="지워졌거나 보낸 사람이 회수한 쪽지이거나, 볼 수 있는 권한이 없습니다." actions={<Link href={listHref} className="btn btn-secondary">목록으로</Link>} />;
  if (!entry?.data) return entry?.error
    ? <ErrorState title="쪽지를 불러오지 못했습니다" body={messageMessage(entry.error)} actions={<Button kind="ghost" disabled={Boolean(entry.loading)} onClick={load}>다시 불러오기</Button>} />
    : <LoadingState title="쪽지를 불러오는 중입니다" />;
  const data = entry.data;
  const people = data.recipients || [];
  const recallable = people.some((person) => !person.readAt && !person.recalled);
  const result = recalls.get(id);
  const run = async (action: "read" | "recall" | "delete") => {
    if (working.current) return;
    working.current = true;
    setBusy(true);
    setProblem("");
    const started = generation;
    try {
      if (action === "read") {
        const state = await markRead(id);
        if (alive.current && started === generation) toast(state.wasUnread ? "읽음으로 표시했습니다" : "이미 읽은 쪽지입니다");
      } else if (action === "recall") {
        const recalled = await recallMessage(id);
        if (started !== generation) return;
        recalls.set(id, recalled);
        if (alive.current) { redraw(); toast(recalled.recalledNow ? `${recalled.recalledNow}명에게서 회수했습니다` : "새로 회수한 받는 사람이 없습니다"); }
      } else {
        await deleteMessage(sent ? "sent" : "inbox", id);
        if (started !== generation) return;
        picked[sent ? "sent" : "inbox"].delete(id);
        if (alive.current) { toast("쪽지를 삭제했습니다"); navigate(listHref); }
      }
    } catch (error) {
      if (alive.current && started === generation) {
        setProblem(messageMessage(error));
        if (action !== "delete" && isMissing(error)) load();
      }
    } finally {
      working.current = false;
      if (alive.current && started === generation) setBusy(false);
    }
  };
  return <article className="msg-read"><Link href={listHref} className="btn btn-ghost msg-back">목록</Link><h2>{data.title}</h2>
    <p className="tiny">{sent ? `보낸 시각 ${koDateTime(data.sentAt)}` : `${data.sender?.name || "보낸 사람"} · ${koDateTime(data.sentAt)}`}</p>
    <p className="msg-meta">{(data.originKind === "REPLY" || data.originKind === "FORWARD") && <Badge>{data.originKind === "REPLY" ? "답장" : "전달"}</Badge>}{data.course && <span className="tiny">관련 과목 {data.course.name}</span>}</p>
    {!sent && <p className="tiny msg-read-state">{data.read ? `읽음 · ${koDateTime(data.readAt)}` : "읽지 않음. 읽음 표시는 아래 단추를 눌러야 남습니다."}</p>}
    <Prose text={data.body} /><Attachments files={data.attachments} />
    {sent && <><h3 className="msg-sub">받는 사람 {people.length}명</h3><ul className="msg-people">{people.map((person) => <li key={person.userId}><b>{person.name}</b><span className="tiny">{person.recalled ? "회수함" : person.readAt ? `읽음 · ${koDateTime(person.readAt)}` : "읽지 않음"}</span></li>)}</ul>
      {result && <section className="note" role="status"><p>{result.recalledNow ? `${result.recalledNow}명에게서 쪽지를 회수했습니다.` : "새로 회수한 받는 사람은 없습니다."}</p><ul className="msg-people">{result.recipients.map((person) => <li key={person.userId}><b>{person.name}</b><span className="tiny">{person.outcome === "recalled" ? "회수됨" : "이미 읽어 회수하지 못했습니다"}</span></li>)}</ul></section>}
      {!recallable && <p className="tiny">읽지 않은 받는 사람이 없어 더 회수할 수 없습니다.</p>}</>}
    {entry.error ? <p className="err" role="alert">최신 내용을 불러오지 못했습니다. {messageMessage(entry.error)}</p> : null}
    <p className="tiny" role="alert" aria-live="polite">{problem}</p><div className="msg-actions">
      {!sent && !data.read && <Button disabled={busy} aria-busy={busy} onClick={() => void run("read")}>읽음으로 표시</Button>}
      {!sent && <Link href={screenHref("02e_msg_compose", { reply: `srv-${id}` })} className="btn btn-primary">답장</Link>}
      {sent && <Button disabled={busy || !recallable} aria-busy={busy} onClick={() => void run("recall")}>회수</Button>}
      <Link href={screenHref("02e_msg_compose", { forward: `srv-${id}`, box: sent ? "sent" : "inbox" })} className="btn btn-secondary">전달</Link>
      <Button kind="ghost" disabled={busy} aria-busy={busy} onClick={() => void run("delete")}>삭제</Button></div></article>;
}
function forwardText(origin: MessageDetail, from: "inbox" | "sent"): string {
  const who = from === "sent" ? (origin.recipients || []).map((person) => person.name).join(", ") : origin.sender?.name || "";
  return ["", "", "----- 전달한 쪽지 -----", `${from === "sent" ? "받는 사람" : "보낸 사람"}: ${who}`, `보낸 시각: ${koDateTime(origin.sentAt)}`, `제목: ${origin.title}`, "", origin.body || ""].join("\n");
}
function Compose({ mode, params }: { mode: Mode; params: URLSearchParams }): ReactNode {
  const memo = memoOf(mode);
  if (memo.courseId === undefined && mode.kind !== "reply") {
    const courseId = courseIdFor(params.get("kj"));
    if (courseId != null) memo.courseId = Number(courseId);
  }
  if (mode.kind === "new") return <ComposeForm mode={mode} memo={memo} origin={null} />;
  return <OriginCompose mode={mode} memo={memo} />;
}
function OriginCompose({ mode, memo }: { mode: Exclude<Mode, { kind: "new" }>; memo: Memo }): ReactNode {
  const resource = mode.from === "sent" ? sentDetails : receivedDetails;
  const key = String(mode.messageId);
  const entry = useResource(resource, key);
  const [origin, setOrigin] = useState<MessageDetail | null>(() => resource.peek(key)?.data || null);
  const load = () => { void resource.load(key, mode.messageId); };
  useEffect(() => {
    const current = resource.peek(key);
    if (!current?.data && !current?.loading) void resource.load(key, mode.messageId);
  }, [resource, key, mode.messageId]);
  useEffect(() => { if (!origin && entry?.data) setOrigin(entry.data); }, [origin, entry]);
  if (!origin) {
    if (entry?.error && isMissing(entry.error)) return <EmptyState title="원래 쪽지를 찾을 수 없습니다" body="지워졌거나 회수된 쪽지에는 답장하거나 전달할 수 없습니다." actions={<Link className="btn btn-secondary" href={screenHref("02e_msg_compose")}>새 쪽지 쓰기</Link>} />;
    if (entry?.error) return <ErrorState title="원래 쪽지를 불러오지 못했습니다" body={messageMessage(entry.error)} actions={<Button kind="ghost" disabled={Boolean(entry.loading)} onClick={load}>다시 불러오기</Button>} />;
    return <LoadingState title="원래 쪽지를 불러오는 중입니다" />;
  }
  if (memo.title === null) {
    const base = origin.title || "";
    memo.title = mode.kind === "reply" ? (base.startsWith("Re:") ? base : `Re: ${base}`) : (base.startsWith("Fw:") ? base : `Fw: ${base}`);
    if (mode.kind === "forward") {
      if (memo.body === null) memo.body = forwardText(origin, mode.from);
      if (memo.courseId === undefined && origin.course) memo.courseId = origin.course.courseId;
    }
  }
  return <ComposeForm mode={mode} memo={memo} origin={origin} />;
}
const scopeOf = (courseId: number | null | undefined, ids: number[]): string => JSON.stringify([courseId ?? null, ids]);
const currentScope = (memo: Memo): string => scopeOf(memo.courseId, [...memo.recipients.keys()]);
function adoptDraft(memo: Memo, draft: MessageDraft): void {
  draftFloor = Math.max(draftFloor, draft.revision);
  memo.revision = Math.max(memo.revision, draft.revision);
  memo.savedTitle = draft.title || "";
  memo.savedBody = draft.body || "";
  memo.savedScope = scopeOf(draft.course?.courseId, (draft.recipients || []).map((person) => person.userId));
}
function restoreDraft(memo: Memo, draft: MessageDraft): string {
  memo.title = draft.title || "";
  memo.body = draft.body || "";
  if (draft.course) { memo.courseId = draft.course.courseId; memo.courseName = draft.course.name || "과목"; }
  else if (draft.recipients?.length) memo.courseId = null;
  else if (draft.courseDropped) memo.courseId = undefined;
  memo.recipients = new Map((draft.recipients || []).map((person) => [person.userId, { ...person }]));
  return [draft.courseDropped ? "임시 저장한 과목은 이제 고를 수 없어 과목 선택에서 뺐습니다. 과목을 확인하세요." : "",
    draft.droppedRecipients > 0 ? `${draft.droppedRecipients}명은 더 이상 쪽지를 받을 수 없어 받는 사람에서 뺐습니다.` : ""].filter(Boolean).join(" ");
}
function ComposeForm({ mode, memo, origin }: { mode: Mode; memo: Memo; origin: MessageDetail | null }): ReactNode {
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  const [status, setStatus] = useState({ text: "", alert: false });
  const [invalid, setInvalid] = useState<string[]>([]);
  const alive = useRef(false);
  const epoch = useRef(generation);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const titleRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const courseRef = useRef<HTMLSelectElement>(null);
  const recipientsRef = useRef<HTMLButtonElement>(null);
  const chipsRef = useRef<HTMLDivElement>(null);
  const focusChip = useRef(false);
  const picker = messageFiles(memo.files, { draft: mode.kind === "new" });
  const validSession = () => epoch.current === generation && memos.get(mode.key) === memo;
  const active = () => alive.current && validSession();
  const note = (text: string, alert = false) => {
    if (!validSession()) return;
    memo.draftNote = text;
    memo.draftAlert = alert;
    if (alive.current) redraw();
  };
  const clearTimer = () => { if (timer.current !== null) clearTimeout(timer.current); timer.current = null; };
  const queue = () => {
    if (mode.kind !== "new" || memo.sending) return;
    clearTimer();
    timer.current = setTimeout(() => { if (active() && memo.draftChecked && !memo.sending) void autosave(); }, DRAFT_DELAY);
  };
  const autosave = async () => {
    if (!active() || memo.drafting || memo.sending) return;
    const title = memo.title || "";
    const body = memo.body || "";
    const scope = currentScope(memo);
    if (title === (memo.savedTitle ?? "") && body === (memo.savedBody ?? "") && scope === (memo.savedScope ?? scopeOf(null, []))) return;
    if (!title && !body && !memo.recipients.size && memo.savedTitle === null) return;
    if (title.length > MAX_TITLE || body.length > MAX_DRAFT_BODY || /[\r\n]/.test(title)) { note("이 길이의 글은 임시 저장할 수 없습니다.", true); return; }
    memo.drafting = true;
    const revision = Math.max(memo.revision, draftFloor) + 1;
    memo.revision = revision;
    draftFloor = revision;
    note("임시 저장하는 중입니다.");
    try {
      const saved = await saveDraft(revision, { title, body, courseId: memo.courseId, recipientIds: [...memo.recipients.keys()] });
      if (!validSession()) return;
      memo.revision = Math.max(memo.revision, saved.revision);
      memo.savedTitle = title;
      memo.savedBody = body;
      memo.savedScope = scope;
      note(`임시 저장했습니다 · ${koDateTime(saved.updatedAt)}`);
    } catch (error) {
      if (!validSession() || memo.sending) return;
      if (isDraftStale(error)) {
        try {
          const draft = await loadDraft();
          if (!validSession() || memo.sending) return;
          if (draft) {
            adoptDraft(memo, draft);
            memo.revision = draft.revision;
            const dropped = restoreDraft(memo, draft);
            note(`다른 창에서 더 새로 임시 저장한 내용이 있어 그 내용을 불러왔습니다. 확인한 뒤 이어서 쓰세요.${dropped ? ` ${dropped}` : ""}`, true);
          } else { note("임시저장본이 바뀌어 다시 저장합니다."); }
        } catch (failure) { note(`임시저장본을 다시 불러오지 못했습니다. ${messageMessage(failure)}`, true); }
      } else if (isMissing(error)) note("고른 과목의 구성원이 아니어서 임시 저장하지 못했습니다. 과목을 다시 고르세요.", true);
      else note(`임시 저장하지 못했습니다. ${messageMessage(error)}`, true);
    } finally {
      memo.drafting = false;
      // Changes made while a save was pending must receive their own revision.
      if (active() && (memo.title !== title || memo.body !== body || currentScope(memo) !== scope)) queue();
    }
  };
  useEffect(() => {
    alive.current = true;
    if (mode.kind === "new" && !memo.draftChecked) {
      memo.draftChecked = true;
      void loadDraft().then((draft) => {
        if (!validSession() || memo.sending) return;
        if (draft) {
          adoptDraft(memo, draft);
          if (memo.title === null && memo.body === null && !memo.recipients.size) {
            const dropped = restoreDraft(memo, draft);
            note(`임시저장본을 불러왔습니다 · ${koDateTime(draft.updatedAt)}${dropped ? ` ${dropped}` : ""}`, Boolean(dropped));
          }
        }
        if (active()) queue();
      }).catch((error: unknown) => {
        if (!validSession()) return;
        memo.draftChecked = false;
        note(`임시저장본을 확인하지 못했습니다. ${messageMessage(error)}`, true);
      });
    } else queue();
    return () => { alive.current = false; clearTimer(); };
  }, [memo, mode.key]);
  const scope = currentScope(memo);
  useEffect(() => { queue(); }, [scope]);
  useLayoutEffect(() => {
    if (!focusChip.current) return;
    focusChip.current = false;
    (chipsRef.current?.querySelector("button") || recipientsRef.current || titleRef.current)?.focus();
  });
  const change = (field: "title" | "body", value: string) => {
    memo[field] = value;
    setInvalid((fields) => fields.filter((name) => name !== field));
    redraw();
    queue();
  };
  const openRecipients = () => {
    if (memo.courseId === undefined) { setStatus({ text: "과목을 먼저 고르세요. 그 과목에서 보낼 수 있는 사람을 찾습니다.", alert: true }); courseRef.current?.focus(); return; }
    const params = new URLSearchParams(location.search);
    params.set("pop", "recipients");
    navigate(`${location.pathname}?${params}`);
  };
  const send = async () => {
    if (memo.sending || !validSession()) return;
    const title = (memo.title || "").trim();
    const body = memo.body || "";
    const problems: { text: string; field?: string; target?: HTMLElement | null }[] = [];
    if (mode.kind !== "reply" && memo.courseId === undefined) problems.push({ text: "과목을 고르세요.", field: "course", target: courseRef.current });
    if (mode.kind !== "reply" && !memo.recipients.size) problems.push({ text: "받는 사람을 한 명 이상 고르세요.", target: recipientsRef.current });
    if (mode.kind !== "reply" && memo.recipients.size > MAX_RECIPIENTS) problems.push({ text: `받는 사람은 ${MAX_RECIPIENTS}명까지입니다.` });
    if (!title) problems.push({ text: "제목을 입력하세요.", field: "title", target: titleRef.current });
    else if (title.length > MAX_TITLE) problems.push({ text: `제목은 ${MAX_TITLE}자 이하입니다.`, field: "title", target: titleRef.current });
    if (!body.trim()) problems.push({ text: "내용을 입력하세요.", field: "body", target: bodyRef.current });
    else if (screenBytes(body.trim()) > MAX_BODY_BYTES) problems.push({ text: `내용은 ${MAX_BODY_BYTES}byte 이하로 적어 주세요.`, field: "body", target: bodyRef.current });
    const fileProblem = filesProblem(picker, memo.files);
    if (fileProblem) problems.push({ text: fileProblem });
    if (problems.length) {
      setStatus({ text: problems.map((problem) => problem.text).join(" "), alert: true });
      setInvalid(problems.flatMap((problem) => problem.field ? [problem.field] : []));
      problems.find((problem) => problem.target)?.target?.focus();
      return;
    }
    memo.sending = true;
    clearTimer();
    setStatus({ text: "ASTRA 서버로 보내고 있습니다.", alert: false });
    redraw();
    try {
      const payload = { title, body, courseId: memo.courseId, recipientIds: [...memo.recipients.keys()], uploadIds: picker.uploadIds() };
      const receipt = mode.kind === "reply" ? await replyMessage(mode.messageId, payload)
        : mode.kind === "forward" ? await forwardMessage(mode.from, mode.messageId, payload) : await sendMessage(payload);
      if (!validSession()) return;
      memos.delete(mode.key);
      if (alive.current) { toast(`쪽지를 보냈습니다 · 받는 사람 ${receipt.recipients?.length || 0}명`); navigate(sentHref(receipt.messageId)); }
    } catch (error) {
      if (!active()) return;
      if (isAttachmentNotReady(error)) picker.recheck();
      setStatus({ text: isUncertain(error) ? "보낸 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 보내세요. 이미 보내졌다면 같은 결과를 돌려받고 두 번 보내지 않습니다." : messageMessage(error), alert: true });
    } finally {
      memo.sending = false;
      if (active()) redraw();
    }
  };
  const bytes = screenBytes((memo.body || "").trim());
  const courses = backendState().courses;
  return <div className="srv-compose__form">
    {mode.kind === "forward" && <p className="note">원래 쪽지를 새 쪽지로 전달합니다. 받는 사람은 새 쪽지와 같은 범위에서만 고를 수 있습니다. 원래 쪽지의 첨부 파일은 함께 전달되지 않으니, 필요하면 내려받아 다시 첨부하세요.</p>}
    <Field label="제목"><input ref={titleRef} maxLength={MAX_TITLE} placeholder="제목" aria-invalid={invalid.includes("title") || undefined} value={memo.title || ""} onChange={(event) => change("title", event.target.value)} /></Field>
    {mode.kind === "reply" ? <p className="msg-to"><b>받는 사람 {origin?.sender?.name || "보낸 사람"}</b><span className="tiny"> 답장은 원래 쪽지를 보낸 사람에게만 갑니다.</span></p> : <>
      <Field label="과목"><select ref={courseRef} aria-invalid={invalid.includes("course") || undefined} value={memo.courseId === undefined ? "" : memo.courseId === null ? "none" : String(memo.courseId)} onChange={(event) => {
        const next = event.target.value === "" ? undefined : event.target.value === "none" ? null : Number(event.target.value);
        if (next !== memo.courseId && memo.recipients.size) { memo.recipients.clear(); setStatus({ text: "과목을 바꿔 고른 받는 사람을 비웠습니다. 이 과목에서 다시 고르세요.", alert: false }); }
        memo.courseId = next;
        setInvalid((fields) => fields.filter((name) => name !== "course")); redraw(); queue();
      }}><option value="">과목을 고르세요</option><option value="none">과목선택 안함 (함께 듣는 모든 과목)</option>{courses.map((course) => <option key={course.courseId} value={String(course.courseId)}>{course.name || course.key}</option>)}
        {memo.courseId != null && !courses.some((course) => Number(course.courseId) === memo.courseId) && <option value={memo.courseId}>{memo.courseName || "과목"}</option>}</select></Field>
      <div className="msg-to"><div className="compose-files"><Button ref={recipientsRef} onClick={openRecipients}>받는 사람 찾기</Button><span className="tiny">과목에서 보낼 수 있는 사람만 고를 수 있습니다. {MAX_RECIPIENTS}명까지.</span></div><p className="tiny">{SCOPE_TEXT}</p>
        <div className="msg-chips" aria-label="고른 받는 사람" ref={chipsRef}>{memo.recipients.size ? [...memo.recipients.values()].map((person) => <span className="msg-chip" key={person.userId}><span>{person.name} · {(person.role && ROLE_LABEL[person.role]) || "구성원"}</span><Button kind="ghost" className="msg-chip__x" aria-label={`${person.name} 빼기`} onClick={() => { memo.recipients.delete(person.userId); focusChip.current = true; redraw(); queue(); }}>×</Button></span>) : <span className="tiny">아직 고른 받는 사람이 없습니다.</span>}</div></div></>}
    <Field label="내용"><textarea ref={bodyRef} rows={10} placeholder="내용을 입력하세요." aria-invalid={invalid.includes("body") || undefined} value={memo.body || ""} onChange={(event) => change("body", event.target.value)} /></Field>
    <p className={`tiny byte${bytes > MAX_BODY_BYTES ? " err" : ""}`} aria-live="polite">{bytes}/{MAX_BODY_BYTES}byte</p>
    <div className="field"><span className="tiny">첨부 파일</span>{picker.node}</div>
    {mode.kind === "new" && <p className="tiny" role={memo.draftAlert ? "alert" : "status"} aria-live="polite">{memo.draftNote}</p>}
    <p className="tiny msg-compose-status" role={status.alert ? "alert" : "status"} aria-live="polite">{status.text}</p>
    <div className="compose-send"><Button kind="primary" disabled={memo.sending} aria-busy={memo.sending} onClick={() => void send()}>{memo.sending ? "보내는 중…" : "보내기"}</Button></div></div>;
}
export function serverMessages(screen: { folder?: string }, ctx: ScreenCtx): ReactNode {
  const folder = screen.folder || "inbox";
  const params = ctx.params || new URLSearchParams();
  const id = messageIdOf(params.get("id"));
  const mode = modeOf(params);
  return <div className="msg-app srv-msg"><Tabs folder={folder} /><Gate><div className="msg-gate">{backendPanel({ compact: true })}</div>
    {folder === "write" ? <div className="compose srv-compose"><Compose key={`${generation}:${mode.key}`} mode={mode} params={params} /></div>
      : <MessagePane folder={folder === "sent" ? "sent" : "inbox"} id={id} />}</Gate></div>;
}
/** The box list and the open message; a deleted open message leaves the placeholder until another is chosen. */
function MessagePane({ folder, id }: { folder: "inbox" | "sent"; id: number | null }): ReactNode {
  const [deleted, setDeleted] = useState<number | null>(null);
  const gone = id != null && deleted === id;
  return <div className={`msg-pane${id ? " has-detail" : ""}`}>{boxList(folder, id, { onCurrentDeleted: () => setDeleted(id) })}<div className="msg-main">
    {gone ? <p className="msg-placeholder">삭제한 쪽지입니다. 목록에서 다른 쪽지를 선택하세요.</p>
      : id ? <Detail key={`${generation}:${folder}:${id}`} id={id} sent={folder === "sent"} /> : <p className="msg-placeholder">목록에서 쪽지를 선택하세요.</p>}
  </div></div>;
}
function RecipientPicker({ mode }: { mode: Mode }): ReactNode {
  const memo = memoOf(mode);
  const [chosen] = useState(() => {
    if (!picking || picking.mode !== mode.key || freshVisit("msg-recipients", mode.key) === "entry") picking = { mode: mode.key, chosen: new Map(memo.recipients) };
    return picking.chosen;
  });
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  if (mode.kind === "reply") return <><EmptyState title="받는 사람을 고르지 않습니다" body="답장은 원래 쪽지를 보낸 사람에게만 갑니다." /><div className="submit-actions"><Button kind="primary" onClick={closeDialog}>닫기</Button></div></>;
  if (memo.courseId === undefined) return <><EmptyState title="과목을 먼저 고르세요" body="쪽지 쓰기에서 과목을 고르면 그 과목에서 보낼 수 있는 사람을 찾을 수 있습니다." /><div className="submit-actions"><Button kind="primary" onClick={closeDialog}>닫기</Button></div></>;
  return <div className="msg-recipients"><p className="tiny">과목 {courseLabel(memo.courseId)} · 이 과목에서 쪽지를 보낼 수 있는 사람만 나옵니다.</p><p className="tiny">{SCOPE_TEXT}</p>
    {recipientSearch({ courseId: memo.courseId, chosen, onChange: redraw })}<p className="tiny" role="status" aria-live="polite">고른 사람 {chosen.size}명</p>
    <div className="submit-actions"><Button kind="ghost" onClick={closeDialog}>취소</Button><Button kind="primary" onClick={() => {
      if (chosen.size > MAX_RECIPIENTS) { toast(`받는 사람은 ${MAX_RECIPIENTS}명까지입니다`); return; }
      memo.recipients = new Map(chosen); picking = null;
      toast(chosen.size ? `받는 사람 ${chosen.size}명을 넣었습니다` : "받는 사람을 비웠습니다"); closeDialog();
    }}>선택</Button></div></div>;
}
export function serverRecipientDialog(ctx: ScreenCtx): ReactNode {
  const mode = modeOf(ctx.params || new URLSearchParams());
  return <Dialog title="받는 사람" wide><Gate><RecipientPicker key={`${generation}:${mode.key}`} mode={mode} /></Gate></Dialog>;
}
