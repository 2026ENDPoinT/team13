// 쪽지 화면 조각. 학생 쪽지 창(learning/messages-server.tsx)과 교수 `pf_message`(professor/messages-server.tsx)가
// 함께 쓴다: 받은·보낸 쪽지 목록(`boxList`, 검색, "더 보기", 선택 삭제, 전체 삭제), 교수 화면에 넣는 보낸 쪽지함
// (`serverSentBox`), 받는 사람 검색(`recipientSearch`), 쪽지 첨부 선택(`messageFiles`, `filesProblem`)과 받는 사람
// 범위 안내 문구(`SCOPE_TEXT`). 목록의 검색어·읽음 필터·고른 쪽지는 메모리에만 두고 세션이 바뀌면 비운다.
//
// 선택 삭제(옛 `srv-msg-delete-picked`)는 이 파일의 `BoxList`가 직접 한다. 쪽지 창 목록은 토스트로 알리고, 지금 열린
// 쪽지를 지웠으면 그 상자 목록으로 옮긴다. 교수 화면(`embedded`)은 목록 안 알림 줄에 결과를 쓴다.
import { useEffect, useLayoutEffect, useReducer, useRef, useState, type ReactNode } from "react";
import { Button, EmptyState, ErrorState, LoadingState } from "../ui/kit.tsx";
import { Link, navigate } from "../router.tsx";
import { toast } from "../store.ts";
import { hrefFor, find } from "../manifest.ts";
import { subscribe } from "./backend.ts";
import { freshVisit, useResource } from "./remote-cache.ts";
import { koDateTime } from "./home-remote.ts";
import { attachPicker, type AttachMemo, type AttachPickerHandle } from "./attach-picker.tsx";
import { personAvatar } from "./avatar-remote.tsx";
import {
  inboxLists, sentLists, recipientPages, inboxKey, sentKey, recipientKey, loadInbox, loadMore, deleteMessages, deleteAll,
  isMissing, isUncertain, sentHref, messageIdOf, messageMessage, ROLE_LABEL, MAX_BULK, RECIPIENT_PAGE, MAX_FILES, ATTACH_PURPOSE,
} from "./messages-remote.ts";
import type { DeleteAllInput, DeleteResult, MessageItem, MessageList, MessagePerson } from "./messages-remote.ts";

/** A mailbox. */
export type BoxName = "inbox" | "sent";

const READ_FILTER = [["", "전체"], ["false", "읽지 않음"], ["true", "읽음"]] as const;
const NO_ITEMS: MessageItem[] = [];
/** Who can be messaged (ADR-0025). The search never tells "no such person" from "not allowed". */
export const SCOPE_TEXT = "쪽지는 함께 있는 과목의 사람에게만 보낼 수 있습니다. 학생은 그 과목의 담당 교수에게, 담당 교수는 그 과목의 누구에게나 보낼 수 있고, 학생끼리는 보낼 수 없습니다. 폐강한 과목은 빠집니다. 찾는 이름이 나오지 않으면 쪽지를 보낼 수 없는 사람입니다.";

const view = { read: "", q: "", sentQ: "" };
/** 상자별로 고른 쪽지 ID. learning/messages-server.tsx의 한 통 삭제(`srv-msg-delete`)도 지운 쪽지를 여기서 뺀다. */
export const picked: Record<BoxName, Set<number>> = { inbox: new Set(), sent: new Set() };
const notices: Record<BoxName, string> = { inbox: "", sent: "" }; // result line for the next painted list of a box

/** Why `count` chosen messages cannot go in one selected-delete (more than `MAX_BULK`), or "". */
export function pickedLimitText(count: number): string {
  return count > MAX_BULK
    ? `선택한 쪽지는 한 번에 ${MAX_BULK}개까지 지울 수 있습니다. 지금 ${count}개를 골랐으니 선택을 줄이거나 전체 삭제를 쓰세요.`
    : "";
}

let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  view.read = "";
  view.q = "";
  view.sentQ = "";
  picked.inbox.clear();
  picked.sent.clear();
  notices.inbox = "";
  notices.sent = "";
});

const receivedLink = (messageId: number) => hrefFor(find("02c_msg_view"), { id: `srv-${messageId}` });
const listOf = (name: BoxName) => hrefFor(find(name === "sent" ? "02d_msg_sent_tab" : "02b_msg_window"));

/** "다시 불러오기": disabled once pressed (the list it sits in is replaced when the load lands). */
function RetryButton({ onRetry }: { onRetry: () => void }) {
  const [pressed, setPressed] = useState(false);
  return (
    <Button kind="ghost" disabled={pressed} onClick={() => { setPressed(true); onRetry(); }}>
      다시 불러오기
    </Button>
  );
}

interface Line {
  text: string;
  alert: boolean;
}

// ---------- attachments ----------

/**
 * The attachment picker of one compose form. `memo` is the form's own object (kept in a Map
 * cleared on session change) so picks survive re-render. Uploads have no course; the file box is
 * offered too (`message_attachment` is a course-less from-filebox purpose).
 */
export function messageFiles(memo: AttachMemo, { draft = false }: { draft?: boolean } = {}): AttachPickerHandle {
  return attachPicker({
    memo,
    courseId: null,
    purpose: ATTACH_PURPOSE,
    max: MAX_FILES,
    hint: `${MAX_FILES}개까지, 50MB 이하. 검사를 통과한 파일만 첨부됩니다.${draft ? " 첨부 파일은 임시 저장되지 않습니다." : ""}`,
  });
}

/** Why the picked files cannot go yet, or "". */
export function filesProblem(picker: AttachPickerHandle, memo: AttachMemo): string {
  if (picker.pending()) return "첨부 파일을 올리거나 검사하는 중입니다. 끝난 뒤 보내세요.";
  if ((memo.uploads || []).some((entry) => entry.state !== "clean")) return "첨부할 수 없는 파일이 있습니다. 그 파일을 빼고 보내세요.";
  return "";
}

// ---------- lists ----------

function recipientsText(item: MessageItem) {
  const first = item.firstRecipient ? item.firstRecipient.name : "받는 사람";
  const count = item.recipientCount ?? 0;
  return count > 1 ? `${first} 외 ${count - 1}명` : first;
}

export interface BoxListOptions {
  /** The professor `pf_message` sent box: no detail links, results in the list's note line. */
  embedded?: boolean;
  /**
   * 전체 삭제 took the message open beside the list (`currentId`): the detail pane should show
   * "삭제한 쪽지입니다. 목록에서 다른 쪽지를 선택하세요." instead of the message.
   */
  onCurrentDeleted?: () => void;
}

/** What one 전체 삭제 confirmation sends, fixed when it opens. */
interface DeleteAllPlan extends DeleteAllInput {
  total: number;
  exceptIds: number[];
  scope: string;
  rowIds: number[];
}

/** The box list; `rebuild` starts it over (fresh selection state) and focuses `focus` in the new one. */
function BoxList({ name, currentId, options }: { name: BoxName; currentId: number | null; options: BoxListOptions }) {
  const [generation, setGeneration] = useState(0);
  const focusAfter = useRef<string | null>(null);
  const rebuild = (focus: string | null, notice?: string) => {
    picked[name].clear();
    if (notice) notices[name] = notice;
    focusAfter.current = focus;
    setGeneration((n) => n + 1);
  };
  return <BoxListBody key={`${name}:${generation}`} name={name} currentId={currentId} options={options} rebuild={rebuild} focusAfter={focusAfter} />;
}

interface BoxListBodyProps {
  name: BoxName;
  currentId: number | null;
  options: BoxListOptions;
  rebuild: (focus: string | null, notice?: string) => void;
  focusAfter: { current: string | null };
}

function BoxListBody({ name, currentId, options, rebuild, focusAfter }: BoxListBodyProps) {
  const embedded = Boolean(options.embedded);
  const inbox = name === "inbox";
  const read = inbox ? view.read : "";
  const q = inbox ? view.q : view.sentQ;
  const key = inbox ? inboxKey(read, q) : sentKey(q);
  const resource = inbox ? inboxLists : sentLists;
  const load = () => (inbox ? loadInbox(read, q) : sentLists.load(key, q));
  const chosen = picked[name];
  const entry = useResource<MessageList>(resource, key, () => { void load(); });
  const data = entry ? entry.data : null;
  const items = data && Array.isArray(data.items) ? data.items : NO_ITEMS;

  const [, bump] = useReducer((n: number) => n + 1, 0);
  const rootRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const allRef = useRef<HTMLInputElement>(null);
  const noteRef = useRef<HTMLParagraphElement>(null);
  const moreRef = useRef<HTMLButtonElement>(null);
  const removeAllRef = useRef<HTMLButtonElement>(null);
  const [note, setNote] = useState<Line>(() => {
    const text = notices[name] || "";
    notices[name] = "";
    return { text, alert: false };
  });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreError, setMoreError] = useState<unknown>(null);
  const [removing, setRemoving] = useState(false);
  const [plan, setPlan] = useState<DeleteAllPlan | null>(null);
  // 전체선택 is on: rows unchecked afterwards are left out of 전체 삭제 (`exceptIds`).
  const wide = useRef({ on: false, except: new Set<number>() });
  const shownItems = useRef<MessageItem[]>([]);
  const focusFrom = useRef(-1); // row index to focus after 더 보기 lands
  const focusMore = useRef(false);

  // Load when the screen was navigated to, or nothing is cached (after a delete dropped the box).
  useEffect(() => {
    if (freshVisit(`msg-${name}${embedded ? "-embedded" : ""}`, key) === "entry" || !resource.peek(key)) void load();
  });

  // After a rebuild: focus the control that asked for it.
  useEffect(() => {
    const focus = focusAfter.current;
    focusAfter.current = null;
    if (focus) rootRef.current?.querySelector<HTMLElement>(focus)?.focus();
  }, [focusAfter]);

  // New rows: under 전체선택 they are chosen too unless left out; ids no longer listed are forgotten.
  useLayoutEffect(() => {
    const before = new Set(shownItems.current.map((item) => item.messageId));
    shownItems.current = items;
    const listed = new Set(items.map((item) => item.messageId));
    if (wide.current.on) for (const item of items) if (!before.has(item.messageId) && !wide.current.except.has(item.messageId)) chosen.add(item.messageId);
    for (const id of [...chosen]) if (!listed.has(id)) chosen.delete(id);
    for (const id of [...wide.current.except]) if (!listed.has(id)) wide.current.except.delete(id);
    bump();
    if (focusFrom.current >= 0) {
      const from = focusFrom.current;
      focusFrom.current = -1;
      const row = from < items.length ? rootRef.current?.querySelectorAll(".msg-rows > .msg-item")[from] : null;
      const first = row ? row.querySelector<HTMLElement>("a, input") : null;
      if (first) first.focus();
      else moreRef.current?.focus();
    }
  }, [items]);

  const onCount = items.filter((item) => chosen.has(item.messageId)).length;
  useEffect(() => {
    if (allRef.current) allRef.current.indeterminate = onCount > 0 && onCount < items.length;
  });

  useEffect(() => {
    if (!focusMore.current || moreBusy) return;
    focusMore.current = false;
    moreRef.current?.focus();
  });

  const say = (text: string, alert = false) => setNote({ text, alert });

  const search = () => {
    const next = (searchRef.current?.value || "").trim();
    if (inbox) view.q = next;
    else view.sentQ = next;
    rebuild("[data-msg-search]");
  };

  const toggleAll = (on: boolean) => {
    wide.current.on = on;
    wide.current.except.clear();
    if (on) items.forEach((item) => chosen.add(item.messageId));
    else chosen.clear();
    bump();
  };

  const toggleRow = (id: number, on: boolean) => {
    if (on) {
      chosen.add(id);
      wide.current.except.delete(id);
    } else {
      chosen.delete(id);
      if (wide.current.on) wide.current.except.add(id);
    }
    bump();
  };

  const showMore = () => {
    if (moreBusy) return;
    setMoreBusy(true);
    setMoreError(null);
    focusFrom.current = items.length;
    loadMore(name, read, q).then((result) => {
      setMoreBusy(false);
      // 서버가 다음 쪽 위치를 받지 않아 첫 쪽부터 다시 읽었다: 오류 대신 알리고 초점을 알림 줄로 옮긴다.
      if (result && "restarted" in result) {
        focusFrom.current = -1;
        const now = resource.peek(key);
        if (now && now.data) {
          say("목록이 바뀌어 처음부터 다시 불러왔습니다.");
          noteRef.current?.focus();
        }
        return;
      }
      // Nothing was painted (the list changed meanwhile): keep the button usable.
      if (!result) focusFrom.current = -1;
    }, (error: unknown) => {
      focusFrom.current = -1;
      setMoreBusy(false);
      setMoreError(error);
      focusMore.current = true;
    });
  };

  // 선택 삭제: the 쪽지 window reports by toast (former `srv-msg-delete-picked`), `embedded` in the note line.
  const removePicked = async () => {
    if (removing) return;
    const ids = [...chosen];
    const tell = (text: string) => (embedded ? say(text, true) : toast(text));
    if (!ids.length) return tell("삭제할 쪽지를 선택하세요");
    // More than one request may carry: nothing is sent and the selection stays.
    const limit = pickedLimitText(ids.length);
    if (limit) return tell(limit);
    const open = embedded ? null : messageIdOf(new URLSearchParams(location.search).get("id"));
    setRemoving(true);
    let result: DeleteResult;
    try {
      result = await deleteMessages(name, ids);
    } catch (error) {
      setRemoving(false);
      // 404: a chosen message is gone; the box is read again without the selection. Any other
      // failure keeps the list and the selection.
      const gone = isMissing(error);
      const goneText = "지울 수 없는 쪽지가 섞여 있어 아무것도 지우지 않았습니다. 목록을 다시 불러왔습니다";
      if (embedded) {
        if (gone) rebuild("[data-msg-note]", goneText);
        else say(messageMessage(error), true);
        return;
      }
      if (gone) rebuild(null);
      toast(gone ? goneText : messageMessage(error));
      return;
    }
    const text = `쪽지 ${Number.isInteger(result.deleted) ? result.deleted : ids.length}개를 삭제했습니다`;
    if (embedded) {
      rebuild("[data-msg-note]", text);
      return;
    }
    rebuild(null);
    toast(text);
    if (open && ids.includes(open)) navigate(listOf(name));
  };

  const openDeleteAll = () => {
    if (!data || !data.total) return;
    setPlan({
      read,
      q,
      until: data.serverTime,
      total: Number(data.total) || 0,
      exceptIds: wide.current.on ? [...wide.current.except] : [],
      scope: q ? `‘${q}’ 검색 결과의` : inbox && read ? (read === "false" ? "읽지 않은" : "읽은") : "이 목록의",
      rowIds: items.map((item) => item.messageId),
    });
  };

  const deletedAll = (result: DeleteResult, done: DeleteAllPlan) => {
    const count = Number.isInteger(result.deleted) ? result.deleted : 0;
    const kept = new Set(done.exceptIds);
    if (!embedded && currentId && done.rowIds.includes(currentId) && !kept.has(currentId)) options.onCurrentDeleted?.();
    rebuild("[data-msg-note]", `쪽지 ${count}개를 삭제했습니다. 목록을 불러온 뒤에 온 쪽지는 남겨 두었습니다.`);
  };

  let wideText = "";
  if (wide.current.on && data) {
    const left = wide.current.except.size;
    wideText = left
      ? `전체 삭제를 누르면 선택을 해제한 ${left}건을 빼고 지웁니다.`
      : "전체 삭제를 누르면 아직 불러오지 않은 쪽지까지 이 목록의 쪽지를 모두 지웁니다.";
  }
  const scope = q ? "검색 결과" : inbox && read ? (read === "false" ? "읽지 않은 쪽지" : "읽은 쪽지") : "전체";
  const summary = !data ? "" : inbox
    ? `${scope} ${data.total}건 · 받은 쪽지 중 안 읽음 ${data.unreadCount}건`
    : `${scope} ${data.total}건`;
  const moreFailure = moreError ?? (entry ? entry.error : null);

  let rows: ReactNode;
  if (!data) {
    rows = !entry || !entry.error
      ? <LoadingState title="쪽지를 불러오는 중입니다" />
      : <ErrorState title="쪽지를 불러오지 못했습니다" body={messageMessage(entry.error)} actions={<RetryButton onRetry={() => { void load(); }} />} />;
  } else if (!items.length) {
    rows = <p className="msg-empty">{q || read ? "조건에 맞는 쪽지가 없습니다." : "쪽지가 없습니다."}</p>;
  } else {
    rows = items.map((item) => {
      const id = item.messageId;
      const who = inbox ? (item.sender && item.sender.name) || "보낸 사람" : recipientsText(item);
      // 받은 쪽지는 보낸 사람, 보낸 쪽지는 첫 받는 사람의 사진이다. 사진이 없으면 지금처럼 첫 글자를 둔다.
      const person = inbox ? item.sender : item.firstRecipient;
      const on = currentId === id;
      const copy = (
        <>
          {personAvatar({ userId: person && person.userId, name: who, className: "msg-avatar" })}
          <span className="msg-copy">
            <b>{item.title}</b>
            <span className="tiny">{`${who} · ${koDateTime(item.sentAt)}`}</span>
            {inbox ? null : <span className="tiny">{`읽음 ${item.readCount}/${item.recipientCount}${item.recalledCount ? ` · 회수 ${item.recalledCount}` : ""}`}</span>}
          </span>
          {inbox && !item.read ? <span className="unread-dot" role="img" aria-label="읽지 않음" /> : null}
        </>
      );
      return (
        <div key={id} className={`msg-item${on ? " on" : ""}`} data-message={String(id)}>
          <input type="checkbox" aria-label={`${item.title} 선택`} checked={chosen.has(id)} onChange={(event) => toggleRow(id, event.currentTarget.checked)} />
          {embedded
            ? <div className="msg-open">{copy}</div>
            : (
              <Link className={`msg-open${inbox && !item.read ? " is-unread" : ""}`} href={inbox ? receivedLink(id) : sentHref(id)} aria-current={on ? "true" : undefined}>
                {copy}
              </Link>
            )}
        </div>
      );
    });
  }

  return (
    <div ref={rootRef} className="msg-list" data-msg-box={name}>
      <div className="msg-search">
        <input
          ref={searchRef}
          type="search"
          defaultValue={q}
          maxLength={100}
          placeholder="제목으로 검색"
          aria-label={inbox ? "받은 쪽지 제목 검색" : "보낸 쪽지 제목 검색"}
          data-msg-search=""
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            search();
          }}
        />
        <Button kind="ghost" onClick={search}>검색</Button>
      </div>
      {inbox
        ? (
          <label className="msg-filter">
            <span className="tiny">보기</span>
            <select
              aria-label="읽음 상태"
              data-msg-filter=""
              defaultValue={read}
              onChange={(event) => {
                view.read = event.currentTarget.value;
                rebuild("[data-msg-filter]");
              }}
            >
              {READ_FILTER.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
        )
        : null}
      <div className="msg-tools">
        <label className="checkline">
          <input
            ref={allRef}
            type="checkbox"
            aria-label="불러온 쪽지 전체 선택"
            data-msg-all=""
            disabled={!items.length}
            checked={items.length > 0 && onCount === items.length}
            onChange={(event) => toggleAll(event.currentTarget.checked)}
          />
          <span>전체선택</span>
        </label>
        <span>
          <Button kind="ghost" data-msg-delete-picked="" disabled={removing} aria-busy={removing ? "true" : undefined} onClick={() => void removePicked()}>삭제</Button>
          <button ref={removeAllRef} type="button" className="btn btn-ghost" data-msg-delete-all="" disabled={!data || !data.total} onClick={openDeleteAll}>전체 삭제</button>
        </span>
      </div>
      <p className="tiny msg-summary" role="status" aria-live="polite">{summary}</p>
      <p className="tiny msg-summary" data-msg-wide="">{wideText}</p>
      <p ref={noteRef} className="tiny msg-summary" role={note.alert ? "alert" : "status"} tabIndex={-1} data-msg-note="">{note.text}</p>
      <div className="msg-rows">{rows}</div>
      <div className="msg-more">
        {data && moreFailure ? <p className="err" role="alert">{messageMessage(moreFailure)}</p> : null}
        {data && data.nextCursor
          ? (
            <button ref={moreRef} type="button" className="btn btn-secondary" data-msg-more="" disabled={moreBusy} aria-busy={moreBusy ? "true" : undefined} onClick={showMore}>
              {moreBusy ? "불러오는 중…" : "더 보기"}
            </button>
          )
          : null}
      </div>
      {plan
        ? (
          <DeleteAllDialog
            name={name}
            plan={plan}
            onDone={(result) => deletedAll(result, plan)}
            onDismiss={(stale) => {
              setPlan(null);
              if (stale) rebuild("[data-msg-note]", "삭제 결과를 확인하지 못해 목록을 다시 불러왔습니다.");
              else removeAllRef.current?.focus();
            }}
          />
        )
        : null}
    </div>
  );
}

/**
 * The 전체 삭제 confirmation (native modal dialog: Esc and 취소 close it, focus returns to the
 * opener). `plan` is fixed when it opens: the shown list's filter, its first page's `serverTime`
 * as `until`, its `total` and the rows left out, so nothing the user has not seen is deleted.
 * `onDismiss(stale)` runs when it closes without deleting; `stale` when an uncertain failure
 * dropped the cached list.
 */
function DeleteAllDialog({ name, plan, onDone, onDismiss }: {
  name: BoxName;
  plan: DeleteAllPlan;
  onDone: (result: DeleteResult) => void;
  onDismiss: (stale: boolean) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState("");
  const state = useRef({ sending: false, changed: false, finished: false, result: null as DeleteResult | null });
  const count = Math.max(0, plan.total - plan.exceptIds.length);
  const inbox = name !== "sent";
  const tooMany = plan.exceptIds.length > MAX_BULK;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog || dialog.open) return;
    dialog.showModal();
    cancelRef.current?.focus();
  }, []);

  useEffect(() => {
    if (!sending && problem) confirmRef.current?.focus();
  }, [sending, problem]);

  const close = () => {
    if (!state.current.sending) dialogRef.current?.close();
  };

  const confirm = async () => {
    if (state.current.sending) return;
    state.current.sending = true;
    setSending(true);
    setProblem("");
    try {
      const result = await deleteAll(name, plan);
      state.current.finished = true;
      state.current.sending = false;
      state.current.result = result;
      dialogRef.current?.close();
    } catch (error) {
      state.current.sending = false;
      if (isUncertain(error)) state.current.changed = true;
      setProblem(isUncertain(error)
        ? "삭제 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 다시 시도하세요. 다시 눌러도 목록을 불러온 뒤에 온 쪽지는 지우지 않습니다."
        : `삭제하지 못했습니다. ${messageMessage(error)}`);
      setSending(false);
    }
  };

  return (
    <dialog
      ref={dialogRef}
      className="modal"
      aria-labelledby="srv-msg-delete-all-title"
      data-msg-delete-all-dialog=""
      style={{ padding: 0, width: "min(440px, calc(100% - 32px))" }}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onKeyDown={(event) => event.stopPropagation()}
      onClose={() => {
        const { finished, changed, result } = state.current;
        if (finished && result) onDone(result);
        else onDismiss(changed);
      }}
    >
      <div className="modal-h"><h2 id="srv-msg-delete-all-title">전체 삭제</h2></div>
      <div className="modal-b stack">
        <p>{`${inbox ? "받은" : "보낸"} 쪽지 중 ${plan.scope} 쪽지 ${count}건을 삭제합니다.`}</p>
        <p className="tiny">{plan.until ? `목록을 불러온 ${koDateTime(plan.until)} 뒤에 온 쪽지는 지우지 않습니다.` : "목록을 다시 불러온 뒤 시도하세요."}</p>
        {plan.exceptIds.length ? <p className="tiny">{`선택을 해제한 ${plan.exceptIds.length}건은 남깁니다.`}</p> : null}
        <p className="tiny">{inbox ? "받은 쪽지함에서만 지웁니다." : "보낸 쪽지함에서만 지웁니다. 받는 사람의 쪽지는 그대로입니다."}</p>
        {tooMany ? <p className="err">{`선택을 해제한 쪽지는 ${MAX_BULK}건까지 뺄 수 있습니다.`}</p> : null}
        <p className="err" role="alert">{problem}</p>
        <div className="pf-actions">
          <button ref={cancelRef} type="button" className="btn btn-ghost" disabled={sending} onClick={close}>취소</button>
          <button
            ref={confirmRef}
            type="button"
            className="btn btn-primary"
            data-msg-delete-all-confirm=""
            disabled={sending || tooMany || !plan.until || !count}
            aria-busy={sending ? "true" : undefined}
            onClick={() => void confirm()}
          >
            {sending ? "삭제하는 중…" : `${count}건 삭제`}
          </button>
        </div>
      </div>
    </dialog>
  );
}

/**
 * One box list. `embedded` (the professor `pf_message` sent box) has no detail links and reports
 * its selected-delete in the list's note line instead of a toast.
 */
export function boxList(name: BoxName, currentId: number | null, options: BoxListOptions = {}): ReactNode {
  return <BoxList name={name} currentId={currentId} options={options} />;
}

/**
 * The professor's 보낸 쪽지 box for `pf_message` (same list, search, 더 보기 and 전체 삭제;
 * no detail links because the detail is a student-window screen).
 */
export function serverSentBox(): ReactNode {
  return (
    <div className="srv-msg" data-msg-embedded="">
      <BoxList name="sent" currentId={null} options={{ embedded: true }} />
    </div>
  );
}

// ---------- recipient search ----------

export interface RecipientSearchOptions {
  courseId: number | null;
  /** userId → person, changed in place. */
  chosen: Map<number, MessagePerson>;
  onChange?: () => void;
}

function RecipientSearch({ courseId, chosen, onChange }: RecipientSearchOptions) {
  const [query, setQuery] = useState({ q: "", page: 1 });
  const [, bump] = useReducer((n: number) => n + 1, 0);
  const inputRef = useRef<HTMLInputElement>(null);
  const key = recipientKey(courseId, query.q, query.page);
  const entry = useResource(recipientPages, key);
  const load = () => { void recipientPages.load(key, courseId, query.q, query.page); };

  useEffect(() => {
    const now = recipientPages.peek(key);
    if (!now || (!now.data && !now.loading)) void recipientPages.load(key, courseId, query.q, query.page);
  }, [key]);

  const changed = () => {
    onChange?.();
    bump();
  };
  const choose = (person: MessagePerson) => chosen.set(person.userId, { userId: person.userId, name: person.name, role: person.role });

  const search = () => setQuery({ q: (inputRef.current?.value || "").trim(), page: 1 });

  const data = entry ? entry.data : null;
  const items = data && Array.isArray(data.items) ? data.items : [];
  let list: ReactNode;
  let pager: ReactNode = null;
  if (!data) {
    if (!entry || !entry.error) list = <LoadingState title="받는 사람을 찾는 중입니다" />;
    else if (isMissing(entry.error)) list = <EmptyState title="이 과목에서 찾을 수 없습니다" body="이 과목의 구성원이 아니거나 서버에 없는 과목입니다." />;
    else list = <ErrorState title="받는 사람을 찾지 못했습니다" body={messageMessage(entry.error)} actions={<RetryButton onRetry={load} />} />;
  } else if (!items.length) {
    list = <p className="msg-empty">{query.q ? "찾는 이름이 없습니다. 이 과목에서 쪽지를 보낼 수 있는 사람만 나옵니다." : "이 과목에서 쪽지를 보낼 수 있는 사람이 없습니다."}</p>;
  } else {
    list = items.map((person) => (
      <label key={person.userId} className="choice" data-user={String(person.userId)}>
        <input
          type="checkbox"
          value={String(person.userId)}
          checked={chosen.has(person.userId)}
          onChange={(event) => {
            if (event.currentTarget.checked) choose(person);
            else chosen.delete(person.userId);
            changed();
          }}
        />
        {personAvatar({ userId: person.userId, name: String(person.name || "?"), className: "msg-avatar" })}
        <span><b>{person.name}</b><span className="tiny">{(person.role && ROLE_LABEL[person.role]) || "구성원"}</span></span>
      </label>
    ));
    const pages = Math.max(1, Math.ceil(data.total / (data.size || RECIPIENT_PAGE)));
    pager = (
      <>
        <Button kind="ghost" onClick={() => { items.forEach(choose); changed(); }}>이 쪽 모두 선택</Button>
        <span className="tiny">{`${query.page} / ${pages}쪽 · ${data.total}명`}</span>
        <span className="msg-finder__nav">
          <Button kind="ghost" aria-label="이전 쪽" disabled={query.page <= 1} onClick={() => setQuery({ q: query.q, page: query.page - 1 })}>이전</Button>
          <Button kind="ghost" aria-label="다음 쪽" disabled={query.page >= pages} onClick={() => setQuery({ q: query.q, page: query.page + 1 })}>다음</Button>
        </span>
      </>
    );
  }

  return (
    <div className="msg-finder">
      <div className="msg-finder__bar">
        <input
          ref={inputRef}
          type="search"
          maxLength={50}
          placeholder="이름으로 찾기"
          aria-label="받는 사람 이름"
          data-recipient-q=""
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            search();
          }}
        />
        <Button kind="secondary" onClick={search}>찾기</Button>
      </div>
      <div className="msg-finder__list" aria-live="polite">{list}</div>
      <div className="msg-finder__pager">{pager}</div>
    </div>
  );
}

/**
 * Name search over `GET /api/messages/recipients` for one course (`courseId`, or null for every
 * shared course). `chosen` is a Map userId -> {userId, name, role} changed in place; the list reads
 * it on every render, so a caller that changes it re-renders its own tree.
 */
export function recipientSearch({ courseId, chosen, onChange }: RecipientSearchOptions): ReactNode {
  return <RecipientSearch courseId={courseId} chosen={chosen} onChange={onChange} />;
}
