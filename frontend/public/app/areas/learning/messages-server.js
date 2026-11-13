// 쪽지 window screens from the ASTRA backend (backend mode): received box with the read filter,
// title search, "더 보기" paging and 전체 삭제 (until the shown list's `serverTime`), received detail
// (opening it never marks it read; only the 읽음 button does), sent box with per-recipient
// read/recall state and recall (also embedded in the professor `pf_message` screen), compose with
// server draft autosave, reply and forward, and the recipient search dialog. Server messages
// use `?id=srv-<messageId>` so app.js never mistakes them for sample messages.
// Typed text, chosen recipients, picked attachments and list filters live in memory only and are
// dropped when the session changes. The compose draft keeps the chosen course and recipients
// (not the attachments); on load the server tells how many recipients it left out, never who.
// Attachments go up as `message_attachment` uploads without a course (shared/attach-picker.js)
// and details download them through `GET /api/files/{fileId}/content`.
// Commands go through `data-act` (learning/actions.js → `messageAction`). The box lists, the
// recipient search and the attachment picker are shared with `pf_message` (shared/message-box.js).
import { el, icon } from "../../dom.js";
import { btn, go, field, emptyState, loadingState, errorState, badge, dialogShell } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { backendStatus, backendSettled, backendState, connect as connectBackend, courseIdFor, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { unreadCount, onUnread } from "../../shared/unread-badges.js";
import { picked, pickedLimitText, boxList, messageFiles, filesProblem, recipientSearch, SCOPE_TEXT } from "../../shared/message-box.js";
import {
  receivedDetails, sentDetails, markRead, sendMessage, replyMessage, forwardMessage, recallMessage, deleteMessage, deleteMessages,
  loadDraft, saveDraft, isDraftStale, isNotAllowed, isMissing, isUncertain, isAttachmentNotReady, messageIdOf, sentHref, messageMessage,
  screenBytes, ROLE_LABEL, MAX_TITLE, MAX_BODY_BYTES, MAX_RECIPIENTS, MAX_DRAFT_BODY,
} from "../../shared/messages-remote.js";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 받은 쪽지와 보낸 쪽지를 보고 쪽지를 보낼 수 있습니다.";
const DRAFT_DELAY = 2000;
const ORIGIN = { REPLY: "답장", FORWARD: "전달" };

const memos = new Map(); // compose mode key -> memo
const recalls = new Map(); // messageId -> last recall result
const controllers = new WeakMap(); // compose node -> controller
let picking = null; // { mode, chosen: Map } while the recipient dialog is open
let draftFloor = 0; // highest draft revision this session used or saw

let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
  recalls.clear();
  picking = null;
  draftFloor = 0;
});

const screenHref = (id, extra) => hrefFor(find(id), extra || {});

/** True when the 쪽지 screens should be the server ones: backend on, or a `srv-` address. */
export function usesServerMessages(params) {
  if (backendStatus() !== "off") return true;
  return ["id", "reply", "forward"].some((name) => messageIdOf(params && params.get(name)) != null);
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** Plain text in paragraphs; every piece is a text node. */
function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function say(node, text, alert) {
  if (!node) return;
  node.setAttribute("role", alert ? "alert" : "status");
  node.textContent = text || "";
}

function busy(node, on, label) {
  node.disabled = on;
  if (on) node.setAttribute("aria-busy", "true");
  else node.removeAttribute("aria-busy");
  if (label) node.textContent = label;
}

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function compact(nodes) {
  return nodes.filter(Boolean);
}

/** The files of a message detail; each downloads through `GET /api/files/{fileId}/content`. */
function messageAttachments(files) {
  const list = Array.isArray(files) ? files.filter((file) => file && file.fileId != null) : [];
  if (!list.length) return null;
  return el("section", { class: "srv-msg-files", "data-msg-files": "" },
    el("h3", { class: "msg-sub", text: `첨부 파일 ${list.length}개` }),
    el("div", { class: "stack" }, ...list.map((file) => {
      const { button, note } = downloadControl(null, file);
      return el("div", { class: "file-row", "data-file": String(file.fileId) }, icon("file"),
        el("span", {}, el("b", { text: file.name || "파일" }), el("span", { class: "tiny", text: sizeText(file.sizeBytes) }), note),
        button);
    })),
    el("p", { class: "tiny", text: "회수된 쪽지나 보관함에서 지운 쪽지의 파일은 더 받을 수 없습니다." }),
  );
}

// ---------- window frame ----------

function folderTabs(folder) {
  const inboxLabel = () => {
    const count = unreadCount("messages");
    return count ? `받은쪽지 ${count}` : "받은쪽지";
  };
  const tab = (id, label, on) => go(screenHref(id), label, { class: on ? "on" : "", role: "tab", "aria-selected": on ? "true" : "false" });
  const inbox = tab("02b_msg_window", inboxLabel(), folder === "inbox");
  const node = el("div", { class: "msg-tabs", role: "tablist" },
    tab("02e_msg_compose", "쪽지쓰기", folder === "write"),
    inbox,
    tab("02d_msg_sent_tab", "보낸쪽지", folder === "sent"),
  );
  attached(node, () => {
    const stop = onUnread((kind) => {
      if (!node.isConnected) return stop();
      if (kind === "messages") inbox.textContent = inboxLabel();
    });
  });
  return node;
}

/** A node to show instead of server data (login, not connected), or null when `ready`. */
function gate(root, paintOff) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: LOGIN_TEXT });
  if (status === "off") {
    if (!backendSettled()) {
      // A `srv-` link opened directly waits for the first backend probe.
      attached(root, () => connectBackend().then((next) => {
        if (root.isConnected && next.status === "off") paintOff();
      }));
      return loadingState("ASTRA 서버에 연결하는 중입니다");
    }
    return offState();
  }
  return null;
}

function offState() {
  return emptyState("쪽지를 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다. 받은 쪽지 목록에서 다른 쪽지를 선택하세요.",
    go(screenHref("02b_msg_window"), "받은 쪽지", { class: "btn btn-secondary" }));
}

/** The 쪽지 window body (`layout: "message"`) in backend mode. */
export function serverMessages(screen, ctx) {
  const folder = screen.folder || "inbox";
  const params = (ctx && ctx.params) || new URLSearchParams();
  const root = el("div", { class: "msg-app srv-msg" }, folderTabs(folder));
  const slot = el("div", { class: "msg-gate" });
  root.append(slot);
  const blocked = gate(root, () => slot.replaceChildren(offState()));
  if (blocked) {
    slot.append(blocked);
    return root;
  }
  slot.append(backendPanel({ compact: true }));
  if (folder === "write") root.append(composeView(composeMode(params), params));
  else root.append(boxPane(folder === "sent" ? "sent" : "inbox", messageIdOf(params.get("id"))));
  return root;
}

function boxPane(name, currentId) {
  const side = boxList(name, currentId);
  const main = currentId
    ? (name === "sent" ? sentDetail(currentId) : receivedDetail(currentId))
    : el("div", { class: "msg-main" }, el("p", { class: "msg-placeholder", text: "목록에서 쪽지를 선택하세요." }));
  return el("div", { class: `msg-pane${currentId ? " has-detail" : ""}` }, side, main);
}

// ---------- details ----------

function detailFrame(resource, messageId, listId, body) {
  const main = el("div", { class: "msg-main" });
  const key = String(messageId);
  const load = () => resource.load(key, messageId);
  const listHref = screenHref(listId);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (entry && entry.error && isMissing(entry.error)) {
      main.replaceChildren(emptyState("쪽지를 찾을 수 없습니다", "지워졌거나 보낸 사람이 회수한 쪽지이거나, 볼 수 있는 권한이 없습니다.",
        go(listHref, "목록으로", { class: "btn btn-secondary" })));
      return;
    }
    if (!data) {
      if (!entry || !entry.error) main.replaceChildren(loadingState("쪽지를 불러오는 중입니다"));
      else main.replaceChildren(errorState("쪽지를 불러오지 못했습니다", messageMessage(entry.error), retry(load)));
      return;
    }
    main.replaceChildren(body(data, listHref, entry.error));
  };
  paint(resource.peek(key));
  attached(main, () => {
    resource.watch(main, key, paint, load);
    if (freshVisit(`msg-detail-${listId}`, key) === "entry" || !resource.peek(key)) load();
  });
  return main;
}

function metaLine(data) {
  return el("p", { class: "msg-meta" }, ...compact([
    data.originKind && ORIGIN[data.originKind] ? badge(ORIGIN[data.originKind]) : null,
    data.course ? el("span", { class: "tiny", text: `관련 과목 ${data.course.name}` }) : null,
  ]));
}

function staleLine(error) {
  return error ? el("p", { class: "err", role: "alert", text: `최신 내용을 불러오지 못했습니다. ${messageMessage(error)}` }) : null;
}

function receivedDetail(messageId) {
  return detailFrame(receivedDetails, messageId, "02b_msg_window", (data, listHref, error) => {
    const id = data.messageId;
    const sender = (data.sender && data.sender.name) || "보낸 사람";
    return el("article", { class: "msg-read", "data-msg-detail": String(id) }, ...compact([
      go(listHref, "목록", { class: "btn btn-ghost msg-back" }),
      el("h2", { text: data.title }),
      el("p", { class: "tiny", text: `${sender} · ${koDateTime(data.sentAt)}` }),
      metaLine(data),
      el("p", { class: "tiny msg-read-state", "data-read-state": data.read ? "read" : "unread", text: data.read ? `읽음 · ${koDateTime(data.readAt)}` : "읽지 않음. 읽음 표시는 아래 단추를 눌러야 남습니다." }),
      prose(data.body),
      messageAttachments(data.attachments),
      staleLine(error),
      el("p", { class: "tiny", role: "status", "aria-live": "polite", "data-msg-status": "" }),
      el("div", { class: "msg-actions" }, ...compact([
        data.read ? null : btn("읽음으로 표시", { "data-act": "srv-msg-read", "data-id": String(id) }, "secondary"),
        go(screenHref("02e_msg_compose", { reply: `srv-${id}` }), "답장", { class: "btn btn-primary" }),
        go(screenHref("02e_msg_compose", { forward: `srv-${id}`, box: "inbox" }), "전달", { class: "btn btn-secondary" }),
        btn("삭제", { "data-act": "srv-msg-delete", "data-box": "inbox", "data-id": String(id) }, "ghost"),
      ])),
    ]));
  });
}

function personState(person) {
  if (person.recalled) return "회수함";
  return person.readAt ? `읽음 · ${koDateTime(person.readAt)}` : "읽지 않음";
}

function sentDetail(messageId) {
  return detailFrame(sentDetails, messageId, "02d_msg_sent_tab", (data, listHref, error) => {
    const id = data.messageId;
    const people = Array.isArray(data.recipients) ? data.recipients : [];
    const recallable = people.some((person) => !person.readAt && !person.recalled);
    const result = recalls.get(id);
    const recall = btn("회수", { "data-act": "srv-msg-recall", "data-id": String(id) }, "secondary");
    recall.disabled = !recallable;
    return el("article", { class: "msg-read", "data-msg-detail": String(id) }, ...compact([
      go(listHref, "목록", { class: "btn btn-ghost msg-back" }),
      el("h2", { text: data.title }),
      el("p", { class: "tiny", text: `보낸 시각 ${koDateTime(data.sentAt)}` }),
      metaLine(data),
      prose(data.body),
      messageAttachments(data.attachments),
      el("h3", { class: "msg-sub", text: `받는 사람 ${people.length}명` }),
      el("ul", { class: "msg-people", "data-msg-recipients": "" }, ...people.map((person) => el("li", { "data-user": String(person.userId) },
        el("b", { text: person.name }),
        el("span", { class: "tiny", text: personState(person) }),
      ))),
      result ? el("section", { class: "note", role: "status", "data-recall-result": "" },
        el("p", { text: result.recalledNow ? `${result.recalledNow}명에게서 쪽지를 회수했습니다.` : "새로 회수한 받는 사람은 없습니다." }),
        el("ul", { class: "msg-people" }, ...(result.recipients || []).map((person) => el("li", { "data-outcome": person.outcome },
          el("b", { text: person.name }),
          el("span", { class: "tiny", text: person.outcome === "recalled" ? "회수됨" : "이미 읽어 회수하지 못했습니다" }),
        ))),
      ) : null,
      recallable ? null : el("p", { class: "tiny", text: "읽지 않은 받는 사람이 없어 더 회수할 수 없습니다." }),
      staleLine(error),
      el("p", { class: "tiny", role: "status", "aria-live": "polite", "data-msg-status": "" }),
      el("div", { class: "msg-actions" },
        recall,
        go(screenHref("02e_msg_compose", { forward: `srv-${id}`, box: "sent" }), "전달", { class: "btn btn-secondary" }),
        btn("삭제", { "data-act": "srv-msg-delete", "data-box": "sent", "data-id": String(id) }, "ghost"),
      ),
    ]));
  });
}

// ---------- compose ----------

function composeMode(params) {
  const reply = messageIdOf(params && params.get("reply"));
  if (reply) return { kind: "reply", from: "inbox", messageId: reply, key: `reply:${reply}` };
  const forward = messageIdOf(params && params.get("forward"));
  if (forward) {
    const from = params.get("box") === "sent" ? "sent" : "inbox";
    return { kind: "forward", from, messageId: forward, key: `forward:${from}:${forward}` };
  }
  return { kind: "new", from: null, messageId: null, key: "new" };
}

function memoOf(mode) {
  if (!memos.has(mode.key)) {
    memos.set(mode.key, {
      title: null, body: null, courseId: undefined, recipients: new Map(), files: {},
      revision: draftFloor, draftChecked: false, draftNote: "", draftAlert: false, savedTitle: null, savedBody: null, savedScope: null,
    });
  }
  return memos.get(mode.key);
}

function courseLabel(courseId) {
  if (courseId === null) return "과목선택 안함";
  const course = backendState().courses.find((item) => item.courseId === courseId);
  return course ? course.name || course.key : "";
}

function forwardText(origin, from) {
  const who = from === "sent"
    ? (origin.recipients || []).map((person) => person.name).join(", ")
    : (origin.sender && origin.sender.name) || "";
  return [
    "",
    "",
    "----- 전달한 쪽지 -----",
    from === "sent" ? `받는 사람: ${who}` : `보낸 사람: ${who}`,
    `보낸 시각: ${koDateTime(origin.sentAt)}`,
    `제목: ${origin.title}`,
    "",
    origin.body || "",
  ].join("\n");
}

function composeView(mode, params) {
  const memo = memoOf(mode);
  const wrap = el("div", { class: "compose srv-compose", "data-compose": mode.key });
  if (memo.courseId === undefined && mode.kind !== "reply") {
    const fromAddress = courseIdFor(params.get("kj"));
    if (fromAddress) memo.courseId = fromAddress;
  }
  if (mode.kind === "new") {
    wrap.append(composeForm(mode, memo, null));
    return wrap;
  }
  const resource = mode.from === "sent" ? sentDetails : receivedDetails;
  const key = String(mode.messageId);
  const load = () => resource.load(key, mode.messageId);
  let built = false;
  const paint = (entry) => {
    if (built) return;
    const data = entry && entry.data;
    if (entry && entry.error && isMissing(entry.error)) {
      wrap.replaceChildren(emptyState("원래 쪽지를 찾을 수 없습니다", "지워졌거나 회수된 쪽지에는 답장하거나 전달할 수 없습니다.",
        go(screenHref("02e_msg_compose"), "새 쪽지 쓰기", { class: "btn btn-secondary" })));
      return;
    }
    if (!data) {
      if (!entry || !entry.error) wrap.replaceChildren(loadingState("원래 쪽지를 불러오는 중입니다"));
      else wrap.replaceChildren(errorState("원래 쪽지를 불러오지 못했습니다", messageMessage(entry.error), retry(load)));
      return;
    }
    built = true;
    wrap.replaceChildren(composeForm(mode, memo, data));
  };
  paint(resource.peek(key));
  attached(wrap, () => {
    resource.watch(wrap, key, paint, null);
    if (!resource.peek(key) || !resource.peek(key).data) load();
  });
  return wrap;
}

function composeForm(mode, memo, origin) {
  const reply = mode.kind === "reply";
  if (origin && memo.title == null) {
    const base = String(origin.title || "");
    memo.title = reply ? (base.startsWith("Re:") ? base : `Re: ${base}`) : (base.startsWith("Fw:") ? base : `Fw: ${base}`);
    if (mode.kind === "forward" && memo.body == null) memo.body = forwardText(origin, mode.from);
    if (mode.kind === "forward" && memo.courseId === undefined && origin.course) memo.courseId = origin.course.courseId;
  }
  const status = el("p", { class: "tiny msg-compose-status", role: "status", "aria-live": "polite", "data-msg-status": "" });
  const draftNote = el("p", { class: "tiny", role: memo.draftAlert ? "alert" : "status", "aria-live": "polite", "data-draft-note": "", text: memo.draftNote });
  const titleInput = el("input", { maxlength: String(MAX_TITLE), placeholder: "제목", value: memo.title || "", "data-msg-title": "" });
  const bodyArea = el("textarea", { rows: "10", placeholder: "내용을 입력하세요.", "data-msg-body": "", text: memo.body || "" });
  const counter = el("p", { class: "tiny byte", "aria-live": "polite" });
  const sendButton = btn("보내기", { "data-act": "srv-msg-send" }, "primary");
  const chips = el("div", { class: "msg-chips", "aria-label": "고른 받는 사람" });
  const picker = messageFiles(memo.files, { draft: mode.kind === "new" });

  const paintCounter = () => {
    const bytes = screenBytes(bodyArea.value.trim());
    counter.textContent = `${bytes}/${MAX_BODY_BYTES}byte`;
    counter.classList.toggle("err", bytes > MAX_BODY_BYTES);
  };
  const paintChips = () => {
    const people = [...memo.recipients.values()];
    chips.replaceChildren(...(people.length
      ? people.map((person) => {
        const remove = btn("×", { class: "btn btn-ghost msg-chip__x", "aria-label": `${person.name} 빼기` }, "ghost");
        remove.addEventListener("click", () => {
          memo.recipients.delete(person.userId);
          paintChips();
          queue();
          const next = chips.querySelector("button");
          (next || chips.parentElement.querySelector("[data-act='srv-msg-recipients']") || titleInput).focus();
        });
        return el("span", { class: "msg-chip" },
          el("span", { text: `${person.name} · ${ROLE_LABEL[person.role] || "구성원"}` }),
          remove);
      })
      : [el("span", { class: "tiny", text: "아직 고른 받는 사람이 없습니다." })]));
  };

  let toBlock;
  let courseBlock = null;
  let select = null;
  if (reply) {
    const sender = (origin.sender && origin.sender.name) || "보낸 사람";
    toBlock = el("p", { class: "msg-to" }, el("b", { text: `받는 사람 ${sender}` }), el("span", { class: "tiny", text: " 답장은 원래 쪽지를 보낸 사람에게만 갑니다." }));
  } else {
    const courses = backendState().courses;
    select = el("select", { "data-msg-course": "" },
      el("option", { value: "", text: "과목을 고르세요", selected: memo.courseId === undefined ? true : null }),
      el("option", { value: "none", text: "과목선택 안함 (함께 듣는 모든 과목)", selected: memo.courseId === null ? true : null }),
      ...courses.map((course) => el("option", { value: String(course.courseId), text: course.name || course.key, selected: memo.courseId === course.courseId ? true : null })));
    select.addEventListener("change", () => {
      const next = select.value === "" ? undefined : select.value === "none" ? null : Number(select.value);
      if (next !== memo.courseId && memo.recipients.size) {
        memo.recipients.clear();
        paintChips();
        say(status, "과목을 바꿔 고른 받는 사람을 비웠습니다. 이 과목에서 다시 고르세요.");
      }
      memo.courseId = next;
      queue();
    });
    courseBlock = field("과목", select);
    toBlock = el("div", { class: "msg-to" },
      el("div", { class: "compose-files" },
        btn("받는 사람 찾기", { "data-act": "srv-msg-recipients" }, "secondary"),
        el("span", { class: "tiny", text: `과목에서 보낼 수 있는 사람만 고를 수 있습니다. ${MAX_RECIPIENTS}명까지.` })),
      el("p", { class: "tiny", "data-msg-scope": "", text: SCOPE_TEXT }),
      chips);
    paintChips();
  }

  // Draft autosave (new message only; the server keeps one compose draft per user).
  let drafting = false;
  let again = false;
  let timer = 0;
  const paintNote = (text, alert) => {
    memo.draftNote = text;
    memo.draftAlert = Boolean(alert);
    say(draftNote, text, alert);
  };
  const fill = (title, body) => {
    memo.title = title;
    memo.body = body;
    titleInput.value = title;
    bodyArea.value = body;
    paintCounter();
  };
  // The course and recipients the draft holds: `courseId` null is both "not chosen" and
  // "과목선택 안함", which the server does not tell apart.
  const scopeOf = (courseId, ids) => JSON.stringify([courseId == null ? null : courseId, ids]);
  const currentScope = () => scopeOf(memo.courseId, [...memo.recipients.keys()]);
  const draftScope = (draft) => scopeOf(draft.course ? draft.course.courseId : null,
    (Array.isArray(draft.recipients) ? draft.recipients : []).map((person) => person.userId));
  const adopt = (draft) => {
    draftFloor = Math.max(draftFloor, draft.revision);
    memo.revision = Math.max(memo.revision, draft.revision);
    memo.savedTitle = draft.title || "";
    memo.savedBody = draft.body || "";
    memo.savedScope = draftScope(draft);
  };
  /** Puts the draft's still-valid course and recipients into the form. */
  const restoreScope = (draft) => {
    const people = Array.isArray(draft.recipients) ? draft.recipients : [];
    if (draft.course) memo.courseId = draft.course.courseId;
    else if (people.length) memo.courseId = null;
    else if (draft.courseDropped) memo.courseId = undefined;
    memo.recipients = new Map(people.map((person) => [person.userId, { userId: person.userId, name: person.name, role: person.role }]));
    if (select) {
      const value = memo.courseId === undefined ? "" : memo.courseId === null ? "none" : String(memo.courseId);
      if (![...select.options].some((option) => option.value === value) && draft.course) {
        select.append(el("option", { value, text: draft.course.name || "과목" }));
      }
      select.value = value;
    }
    paintChips();
  };
  /** What the server left out of the draft, without names. */
  const droppedText = (draft) => compact([
    draft.courseDropped ? "임시 저장한 과목은 이제 고를 수 없어 과목 선택에서 뺐습니다. 과목을 확인하세요." : "",
    draft.droppedRecipients > 0 ? `${draft.droppedRecipients}명은 더 이상 쪽지를 받을 수 없어 받는 사람에서 뺐습니다.` : "",
  ]).join(" ");
  async function checkDraft() {
    memo.draftChecked = true;
    try {
      const draft = await loadDraft();
      if (!draft) return;
      adopt(draft);
      if (memo.title == null && memo.body == null && !memo.recipients.size) {
        fill(draft.title || "", draft.body || "");
        restoreScope(draft);
        const dropped = droppedText(draft);
        paintNote(`임시저장본을 불러왔습니다 · ${koDateTime(draft.updatedAt)}${dropped ? ` ${dropped}` : ""}`, Boolean(dropped));
      }
    } catch (error) {
      memo.draftChecked = false;
      paintNote(`임시저장본을 확인하지 못했습니다. ${messageMessage(error)}`, true);
    }
  }
  async function reloadDraft() {
    const draft = await loadDraft();
    if (!draft) {
      paintNote("임시저장본이 바뀌어 다시 저장합니다.");
      queue();
      return;
    }
    adopt(draft);
    memo.revision = draft.revision;
    fill(draft.title || "", draft.body || "");
    restoreScope(draft);
    const dropped = droppedText(draft);
    paintNote(`다른 창에서 더 새로 임시 저장한 내용이 있어 그 내용을 불러왔습니다. 확인한 뒤 이어서 쓰세요.${dropped ? ` ${dropped}` : ""}`, true);
  }
  async function autosave() {
    if (!wrapAlive()) return;
    if (drafting) {
      again = true;
      return;
    }
    const title = titleInput.value;
    const body = bodyArea.value;
    const scope = currentScope();
    if (title === (memo.savedTitle ?? "") && body === (memo.savedBody ?? "") && scope === (memo.savedScope ?? scopeOf(null, []))) return;
    // Nothing typed or picked yet and no stored draft: a course from the address is not a draft.
    if (!title && !body && !memo.recipients.size && memo.savedTitle == null) return;
    if (title.length > MAX_TITLE || body.length > MAX_DRAFT_BODY || /[\r\n]/.test(title)) {
      paintNote("이 길이의 글은 임시 저장할 수 없습니다.", true);
      return;
    }
    drafting = true;
    const revision = Math.max(memo.revision, draftFloor) + 1;
    memo.revision = revision;
    draftFloor = revision;
    paintNote("임시 저장하는 중입니다.");
    try {
      const saved = await saveDraft(revision, { title, body, courseId: memo.courseId, recipientIds: [...memo.recipients.keys()] });
      memo.revision = Math.max(memo.revision, saved.revision);
      memo.savedTitle = title;
      memo.savedBody = body;
      memo.savedScope = scope;
      paintNote(`임시 저장했습니다 · ${koDateTime(saved.updatedAt)}`);
    } catch (error) {
      if (isDraftStale(error)) {
        again = false;
        try {
          await reloadDraft();
        } catch (failure) {
          paintNote(`임시저장본을 다시 불러오지 못했습니다. ${messageMessage(failure)}`, true);
        }
      } else if (isMissing(error)) paintNote("고른 과목의 구성원이 아니어서 임시 저장하지 못했습니다. 과목을 다시 고르세요.", true);
      else paintNote(`임시 저장하지 못했습니다. ${messageMessage(error)}`, true);
    } finally {
      drafting = false;
      if (again) {
        again = false;
        queue();
      }
    }
  }
  const node = el("div", { class: "srv-compose__form" });
  const wrapAlive = () => node.isConnected;
  function queue() {
    if (mode.kind !== "new") return;
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (wrapAlive() && memo.draftChecked) void autosave();
    }, DRAFT_DELAY);
  }

  titleInput.addEventListener("input", () => {
    memo.title = titleInput.value;
    titleInput.removeAttribute("aria-invalid");
    queue();
  });
  bodyArea.addEventListener("input", () => {
    memo.body = bodyArea.value;
    bodyArea.removeAttribute("aria-invalid");
    paintCounter();
    queue();
  });
  paintCounter();

  node.append(...compact([
    mode.kind === "forward" ? el("p", { class: "note", text: "원래 쪽지를 새 쪽지로 전달합니다. 받는 사람은 새 쪽지와 같은 범위에서만 고를 수 있습니다. 원래 쪽지의 첨부 파일은 함께 전달되지 않으니, 필요하면 내려받아 다시 첨부하세요." }) : null,
    field("제목", titleInput),
    courseBlock,
    toBlock,
    field("내용", bodyArea),
    counter,
    el("div", { class: "field" }, el("span", { class: "tiny", text: "첨부 파일" }), picker.node),
    mode.kind === "new" ? draftNote : null,
    status,
    el("div", { class: "compose-send" }, sendButton),
  ]));

  let sending = false;
  const controller = {
    mode,
    memo,
    say: (text, alert) => say(status, text, alert),
    openRecipients(api) {
      if (memo.courseId === undefined) {
        say(status, "과목을 먼저 고르세요. 그 과목에서 보낼 수 있는 사람을 찾습니다.", true);
        const select = node.querySelector("[data-msg-course]");
        if (select) select.focus();
        return;
      }
      const params = new URLSearchParams(location.search);
      params.set("pop", "recipients");
      api.navigate(`${location.pathname}?${params}`);
    },
    async send(api) {
      if (sending) return;
      const title = titleInput.value.trim();
      const body = bodyArea.value;
      const problems = [];
      if (!reply && memo.courseId === undefined) problems.push(["과목을 고르세요.", node.querySelector("[data-msg-course]")]);
      if (!reply && !memo.recipients.size) problems.push(["받는 사람을 한 명 이상 고르세요.", node.querySelector("[data-act='srv-msg-recipients']")]);
      if (!reply && memo.recipients.size > MAX_RECIPIENTS) problems.push([`받는 사람은 ${MAX_RECIPIENTS}명까지입니다.`, null]);
      if (!title) problems.push(["제목을 입력하세요.", titleInput]);
      else if (title.length > MAX_TITLE) problems.push([`제목은 ${MAX_TITLE}자 이하입니다.`, titleInput]);
      if (!body.trim()) problems.push(["내용을 입력하세요.", bodyArea]);
      else if (screenBytes(body.trim()) > MAX_BODY_BYTES) problems.push([`내용은 ${MAX_BODY_BYTES}byte 이하로 적어 주세요.`, bodyArea]);
      const fileProblem = filesProblem(picker, memo.files);
      if (fileProblem) problems.push([fileProblem, null]);
      if (problems.length) {
        say(status, problems.map(([text]) => text).join(" "), true);
        for (const [, target] of problems) if (target && target.tagName !== "BUTTON") target.setAttribute("aria-invalid", "true");
        const first = problems.find(([, target]) => target);
        if (first) first[1].focus();
        return;
      }
      sending = true;
      clearTimeout(timer);
      busy(sendButton, true, "보내는 중…");
      say(status, "ASTRA 서버로 보내고 있습니다.");
      const uploadIds = picker.uploadIds();
      const payload = { title, body, courseId: memo.courseId, recipientIds: [...memo.recipients.keys()], uploadIds };
      try {
        const receipt = mode.kind === "reply"
          ? await replyMessage(mode.messageId, { title, body, uploadIds })
          : mode.kind === "forward"
            ? await forwardMessage(mode.from, mode.messageId, payload)
            : await sendMessage(payload);
        memos.delete(mode.key);
        const count = Array.isArray(receipt.recipients) ? receipt.recipients.length : 0;
        api.getState().toast = `쪽지를 보냈습니다 · 받는 사람 ${count}명`;
        api.navigate(sentHref(receipt.messageId));
      } catch (error) {
        if (isNotAllowed(error)) say(status, messageMessage(error), true);
        else if (isAttachmentNotReady(error)) {
          picker.recheck();
          say(status, messageMessage(error), true);
        } else if (isUncertain(error)) say(status, "보낸 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 보내세요. 이미 보내졌다면 같은 결과를 돌려받고 두 번 보내지 않습니다.", true);
        else say(status, messageMessage(error), true);
      } finally {
        sending = false;
        if (sendButton.isConnected) busy(sendButton, false, "보내기");
      }
    },
  };
  controllers.set(node, controller);
  if (mode.kind === "new" && !memo.draftChecked) attached(node, () => void checkDraft());
  // Recipients picked in the dialog come back through a re-render: save them too.
  else if (mode.kind === "new") attached(node, queue);
  return node;
}

/** The recipient dialog (`02f_msg_recipient`, `?pop=recipients`) in backend mode. */
export function serverRecipientDialog(ctx) {
  const params = (ctx && ctx.params) || new URLSearchParams();
  const mode = composeMode(params);
  const shell = (node) => dialogShell("받는 사람", node, { wide: true });
  const frame = el("div", { class: "msg-recipients" });
  const blocked = gate(frame, () => frame.replaceChildren(offState()));
  if (blocked) {
    frame.append(blocked);
    return shell(frame);
  }
  const memo = memoOf(mode);
  const fresh = freshVisit("msg-recipients", mode.key);
  if (mode.kind === "reply") {
    frame.append(emptyState("받는 사람을 고르지 않습니다", "답장은 원래 쪽지를 보낸 사람에게만 갑니다."),
      el("div", { class: "submit-actions" }, btn("닫기", { "data-act": "close" }, "primary")));
    return shell(frame);
  }
  if (memo.courseId === undefined) {
    frame.append(emptyState("과목을 먼저 고르세요", "쪽지 쓰기에서 과목을 고르면 그 과목에서 보낼 수 있는 사람을 찾을 수 있습니다."),
      el("div", { class: "submit-actions" }, btn("닫기", { "data-act": "close" }, "primary")));
    return shell(frame);
  }
  if (!picking || picking.mode !== mode.key || fresh === "entry") picking = { mode: mode.key, chosen: new Map(memo.recipients) };
  const count = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const paintCount = () => { count.textContent = `고른 사람 ${picking.chosen.size}명`; };
  const finder = recipientSearch({ courseId: memo.courseId, chosen: picking.chosen, onChange: paintCount });
  paintCount();
  frame.dataset.msgPick = mode.key;
  frame.append(
    el("p", { class: "tiny", text: `과목 ${courseLabel(memo.courseId)} · 이 과목에서 쪽지를 보낼 수 있는 사람만 나옵니다.` }),
    el("p", { class: "tiny", "data-msg-scope": "", text: SCOPE_TEXT }),
    finder.node,
    count,
    el("div", { class: "submit-actions" },
      btn("취소", { "data-act": "close" }, "ghost"),
      btn("선택", { "data-act": "srv-msg-pick" }, "primary")),
  );
  return shell(frame);
}

// ---------- commands (learning/actions.js) ----------

async function guarded(node, run) {
  if (node.disabled || node.getAttribute("aria-busy") === "true") return;
  busy(node, true);
  try {
    await run();
  } finally {
    if (node.isConnected) busy(node, false);
  }
}

function statusNear(node) {
  const scope = node.closest("[data-msg-detail]");
  return scope ? scope.querySelector("[data-msg-status]") : null;
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

const boxOf = (node) => (node.dataset.box === "sent" ? "sent" : "inbox");
const listOf = (name) => screenHref(name === "sent" ? "02d_msg_sent_tab" : "02b_msg_window");

/** Every `srv-msg-*` action of the 쪽지 window. */
export function messageAction(node, api) {
  const act = node.dataset.act;
  const compose = node.closest(".srv-compose__form");
  const controller = compose && controllers.get(compose);
  if (act === "srv-msg-send") {
    if (controller) void controller.send(api);
    return;
  }
  if (act === "srv-msg-recipients") {
    if (controller) controller.openRecipients(api);
    return;
  }
  if (act === "srv-msg-pick") {
    const mode = composeMode(new URLSearchParams(location.search));
    if (!picking || picking.mode !== mode.key) return;
    const memo = memoOf(mode);
    if (picking.chosen.size > MAX_RECIPIENTS) {
      toast(api, `받는 사람은 ${MAX_RECIPIENTS}명까지입니다`);
      return;
    }
    memo.recipients = new Map(picking.chosen);
    picking = null;
    toast(api, memo.recipients.size ? `받는 사람 ${memo.recipients.size}명을 넣었습니다` : "받는 사람을 비웠습니다");
    api.closeDialog();
    return;
  }
  const id = Number(node.dataset.id);
  if (act === "srv-msg-read") {
    void guarded(node, async () => {
      try {
        const state = await markRead(id);
        toast(api, state.wasUnread ? "읽음으로 표시했습니다" : "이미 읽은 쪽지입니다");
      } catch (error) {
        say(statusNear(node), messageMessage(error), true);
        if (isMissing(error)) receivedDetails.load(String(id), id);
      }
    });
    return;
  }
  if (act === "srv-msg-recall") {
    void guarded(node, async () => {
      try {
        const result = await recallMessage(id);
        recalls.set(id, result);
        sentDetails.patch((key) => key === String(id), () => {});
        toast(api, result.recalledNow ? `${result.recalledNow}명에게서 회수했습니다` : "새로 회수한 받는 사람이 없습니다");
      } catch (error) {
        say(statusNear(node), messageMessage(error), true);
        if (isMissing(error)) sentDetails.load(String(id), id);
      }
    });
    return;
  }
  if (act === "srv-msg-delete") {
    const name = boxOf(node);
    void guarded(node, async () => {
      try {
        await deleteMessage(name, id);
        picked[name].delete(id);
        toast(api, "쪽지를 삭제했습니다");
        api.navigate(listOf(name));
      } catch (error) {
        say(statusNear(node), messageMessage(error), true);
      }
    });
    return;
  }
  if (act === "srv-msg-delete-picked") {
    const name = boxOf(node);
    const ids = [...picked[name]];
    if (!ids.length) {
      toast(api, "삭제할 쪽지를 선택하세요");
      return;
    }
    // More than one request may carry: nothing is sent and the selection stays.
    const limit = pickedLimitText(ids.length);
    if (limit) {
      toast(api, limit);
      return;
    }
    void guarded(node, async () => {
      const current = messageIdOf(new URLSearchParams(location.search).get("id"));
      try {
        const result = await deleteMessages(name, ids);
        picked[name].clear();
        toast(api, `쪽지 ${result && Number.isInteger(result.deleted) ? result.deleted : ids.length}개를 삭제했습니다`);
        if (current && ids.includes(current)) api.navigate(listOf(name));
        else api.render();
      } catch (error) {
        // 404: a chosen message is gone and the box is read again, so the selection goes with it.
        // Any other failure changed nothing that is known: the list and the selection stay.
        const gone = isMissing(error);
        if (gone) picked[name].clear();
        toast(api, gone
          ? "지울 수 없는 쪽지가 섞여 있어 아무것도 지우지 않았습니다. 목록을 다시 불러왔습니다"
          : messageMessage(error));
        if (gone) api.render();
      }
    });
  }
}
