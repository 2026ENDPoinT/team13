// 쪽지 화면 조각. 학생 쪽지 창(learning/messages-server.js)과 교수 `pf_message`(professor/messages-server.js)가
// 함께 쓴다: 받은·보낸 쪽지 목록(`boxList`, 검색, "더 보기", 선택 삭제, 전체 삭제), 교수 화면에 넣는 보낸 쪽지함
// (`serverSentBox`), 받는 사람 검색(`recipientSearch`), 쪽지 첨부 선택(`messageFiles`, `filesProblem`)과 받는 사람
// 범위 안내 문구(`SCOPE_TEXT`). 목록의 검색어·읽음 필터·고른 쪽지는 메모리에만 두고 세션이 바뀌면 비운다.
import { el } from "../dom.js";
import { btn, emptyState, loadingState, errorState } from "../ui/components.js";
import { hrefFor, find } from "../manifest.js";
import { subscribe } from "./backend.js";
import { freshVisit } from "./remote-cache.js";
import { koDateTime } from "./home-remote.js";
import { attachPicker } from "./attach-picker.js";
import { personAvatar } from "./avatar-remote.js";
import {
  inboxLists, sentLists, recipientPages, inboxKey, sentKey, recipientKey, loadInbox, loadMore, deleteMessages, deleteAll,
  isMissing, isUncertain, sentHref, messageMessage, ROLE_LABEL, MAX_BULK, RECIPIENT_PAGE, MAX_FILES, ATTACH_PURPOSE,
} from "./messages-remote.js";

const READ_FILTER = [["", "전체"], ["false", "읽지 않음"], ["true", "읽음"]];
/** Who can be messaged (ADR-0025). The search never tells "no such person" from "not allowed". */
export const SCOPE_TEXT = "쪽지는 함께 있는 과목의 사람에게만 보낼 수 있습니다. 학생은 그 과목의 담당 교수에게, 담당 교수는 그 과목의 누구에게나 보낼 수 있고, 학생끼리는 보낼 수 없습니다. 폐강한 과목은 빠집니다. 찾는 이름이 나오지 않으면 쪽지를 보낼 수 없는 사람입니다.";

const view = { read: "", q: "", sentQ: "" };
/** 상자별로 고른 쪽지 ID. learning/messages-server.js의 `messageAction`(`srv-msg-delete`·`srv-msg-delete-picked`)도 쓴다. */
export const picked = { inbox: new Set(), sent: new Set() };
const notices = { inbox: "", sent: "" }; // result line for the next painted list of a box

/** Why `count` chosen messages cannot go in one selected-delete (more than `MAX_BULK`), or "". */
export function pickedLimitText(count) {
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

const screenHref = (id, extra) => hrefFor(find(id), extra || {});
const receivedLink = (messageId) => screenHref("02c_msg_view", { id: `srv-${messageId}` });

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
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

async function guarded(node, run) {
  if (node.disabled || node.getAttribute("aria-busy") === "true") return;
  busy(node, true);
  try {
    await run();
  } finally {
    if (node.isConnected) busy(node, false);
  }
}

// ---------- attachments ----------

/**
 * The attachment picker of one compose form. `memo` is the form's own object (kept in a Map
 * cleared on session change) so picks survive re-render. Uploads have no course; the file box is
 * offered too (`message_attachment` is a course-less from-filebox purpose).
 */
export function messageFiles(memo, { draft = false } = {}) {
  return attachPicker({
    memo,
    courseId: null,
    purpose: ATTACH_PURPOSE,
    max: MAX_FILES,
    hint: `${MAX_FILES}개까지, 50MB 이하. 검사를 통과한 파일만 첨부됩니다.${draft ? " 첨부 파일은 임시 저장되지 않습니다." : ""}`,
  });
}

/** Why the picked files cannot go yet, or "". */
export function filesProblem(picker, memo) {
  if (picker.pending()) return "첨부 파일을 올리거나 검사하는 중입니다. 끝난 뒤 보내세요.";
  if ((memo.uploads || []).some((entry) => entry.state !== "clean")) return "첨부할 수 없는 파일이 있습니다. 그 파일을 빼고 보내세요.";
  return "";
}

// ---------- lists ----------

function recipientsText(item) {
  const first = item.firstRecipient ? item.firstRecipient.name : "받는 사람";
  return item.recipientCount > 1 ? `${first} 외 ${item.recipientCount - 1}명` : first;
}

/**
 * One box list. `embedded` (the professor `pf_message` sent box) has no detail links and runs
 * its own selected-delete instead of the `srv-msg-delete-picked` action.
 */
export function boxList(name, currentId, options = {}) {
  const embedded = Boolean(options.embedded);
  const inbox = name === "inbox";
  const read = inbox ? view.read : "";
  const q = inbox ? view.q : view.sentQ;
  const key = inbox ? inboxKey(read, q) : sentKey(q);
  const resource = inbox ? inboxLists : sentLists;
  const load = () => (inbox ? loadInbox(read, q) : sentLists.load(key, q));
  const chosen = picked[name];
  const side = el("div", { class: "msg-list", "data-msg-box": name });
  const rebuild = (focus, notice) => {
    chosen.clear();
    if (notice) notices[name] = notice;
    const next = boxList(name, currentId, options);
    side.replaceWith(next);
    const target = next.querySelector(focus);
    if (target) target.focus();
  };

  const input = el("input", { type: "search", value: q, maxlength: "100", placeholder: "제목으로 검색", "aria-label": inbox ? "받은 쪽지 제목 검색" : "보낸 쪽지 제목 검색", "data-msg-search": "" });
  const searchButton = btn("검색", {}, "ghost");
  const search = () => {
    const next = input.value.trim();
    if (inbox) view.q = next;
    else view.sentQ = next;
    rebuild("[data-msg-search]");
  };
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    search();
  });
  searchButton.addEventListener("click", search);

  let filter = null;
  if (inbox) {
    const select = el("select", { "aria-label": "읽음 상태", "data-msg-filter": "" },
      ...READ_FILTER.map(([value, label]) => el("option", { value, text: label, selected: value === read ? true : null })));
    select.addEventListener("change", () => {
      view.read = select.value;
      rebuild("[data-msg-filter]");
    });
    filter = el("label", { class: "msg-filter" }, el("span", { class: "tiny", text: "보기" }), select);
  }

  const all = el("input", { type: "checkbox", "aria-label": "불러온 쪽지 전체 선택", "data-msg-all": "" });
  const remove = embedded
    ? btn("삭제", { "data-msg-delete-picked": "" }, "ghost")
    : btn("삭제", { "data-act": "srv-msg-delete-picked", "data-box": name }, "ghost");
  const removeAll = btn("전체 삭제", { "data-msg-delete-all": "" }, "ghost");
  const summary = el("p", { class: "tiny msg-summary", role: "status", "aria-live": "polite" });
  const wideLine = el("p", { class: "tiny msg-summary", "data-msg-wide": "" });
  const note = el("p", { class: "tiny msg-summary", role: "status", tabindex: "-1", "data-msg-note": "", text: notices[name] || "" });
  notices[name] = "";
  const rows = el("div", { class: "msg-rows" });
  const more = el("div", { class: "msg-more" });
  side.append(...compact([
    el("div", { class: "msg-search" }, input, searchButton),
    filter,
    el("div", { class: "msg-tools" }, el("label", { class: "checkline" }, all, el("span", { text: "전체선택" })), el("span", {}, remove, removeAll)),
    summary,
    wideLine,
    note,
    rows,
    more,
  ]));

  let items = [];
  let shown = null; // the list data now painted
  let focusFrom = -1; // row index to focus after 더 보기 lands
  // 전체선택 is on: rows unchecked afterwards are left out of 전체 삭제 (`exceptIds`).
  const wide = { on: false, except: new Set() };
  const paintWide = () => {
    if (!wide.on || !shown) {
      wideLine.textContent = "";
      return;
    }
    const left = wide.except.size;
    wideLine.textContent = left
      ? `전체 삭제를 누르면 선택을 해제한 ${left}건을 빼고 지웁니다.`
      : "전체 삭제를 누르면 아직 불러오지 않은 쪽지까지 이 목록의 쪽지를 모두 지웁니다.";
  };
  const syncAll = () => {
    const on = items.filter((item) => chosen.has(item.messageId)).length;
    all.checked = items.length > 0 && on === items.length;
    all.indeterminate = on > 0 && on < items.length;
    paintWide();
  };
  all.addEventListener("change", () => {
    wide.on = all.checked;
    wide.except.clear();
    if (all.checked) items.forEach((item) => chosen.add(item.messageId));
    else chosen.clear();
    paint(resource.peek(key));
  });

  const row = (item) => {
    const id = item.messageId;
    const who = inbox ? (item.sender && item.sender.name) || "보낸 사람" : recipientsText(item);
    // 받은 쪽지는 보낸 사람, 보낸 쪽지는 첫 받는 사람의 사진이다. 사진이 없으면 지금처럼 첫 글자를 둔다.
    const person = inbox ? item.sender : item.firstRecipient;
    const check = el("input", { type: "checkbox", "aria-label": `${item.title} 선택`, checked: chosen.has(id) ? true : null });
    check.addEventListener("change", () => {
      if (check.checked) {
        chosen.add(id);
        wide.except.delete(id);
      } else {
        chosen.delete(id);
        if (wide.on) wide.except.add(id);
      }
      syncAll();
    });
    const on = currentId === id;
    const copy = [
      personAvatar({ userId: person && person.userId, name: who, className: "msg-avatar" }),
      el("span", { class: "msg-copy" },
        el("b", { text: item.title }),
        el("span", { class: "tiny", text: `${who} · ${koDateTime(item.sentAt)}` }),
        inbox ? null : el("span", { class: "tiny", text: `읽음 ${item.readCount}/${item.recipientCount}${item.recalledCount ? ` · 회수 ${item.recalledCount}` : ""}` }),
      ),
      inbox && !item.read ? el("span", { class: "unread-dot", role: "img", "aria-label": "읽지 않음" }) : null,
    ];
    let open;
    if (embedded) open = el("div", { class: "msg-open" }, ...copy);
    else {
      const href = inbox ? receivedLink(id) : sentHref(id);
      open = el("a", { class: `msg-open${inbox && !item.read ? " is-unread" : ""}`, href, "data-go": href, "aria-current": on ? "true" : null }, ...copy);
    }
    return el("div", { class: `msg-item${on ? " on" : ""}`, "data-message": String(id) }, check, open);
  };

  const paintMore = (data, failure) => {
    more.replaceChildren();
    if (failure) more.append(el("p", { class: "err", role: "alert", text: messageMessage(failure) }));
    if (!data.nextCursor) return null;
    const button = btn("더 보기", { "data-msg-more": "" }, "secondary");
    button.addEventListener("click", () => {
      if (button.disabled) return;
      busy(button, true, "불러오는 중…");
      focusFrom = items.length;
      loadMore(name, read, q).then((result) => {
        // 서버가 다음 쪽 위치를 받지 않아 첫 쪽부터 다시 읽었다: 오류 대신 알리고 초점을 알림 줄로 옮긴다.
        if (result && result.restarted) {
          focusFrom = -1;
          if (!side.isConnected) return;
          const entry = resource.peek(key);
          if (entry && entry.data) {
            say(note, "목록이 바뀌어 처음부터 다시 불러왔습니다.");
            note.focus();
          }
          return;
        }
        // Nothing was painted (the list changed meanwhile): keep the button usable.
        if (!button.isConnected) return;
        focusFrom = -1;
        busy(button, false, "더 보기");
      }, (error) => {
        focusFrom = -1;
        if (!side.isConnected) return;
        const entry = resource.peek(key);
        const again = entry && entry.data ? paintMore(entry.data, error) : null;
        if (again) again.focus();
      });
    });
    more.append(button);
    return button;
  };

  function paint(entry) {
    const data = entry && entry.data;
    if (!data) {
      items = [];
      shown = null;
      all.disabled = true;
      removeAll.disabled = true;
      summary.textContent = "";
      wideLine.textContent = "";
      more.replaceChildren();
      if (!entry || !entry.error) rows.replaceChildren(loadingState("쪽지를 불러오는 중입니다"));
      else rows.replaceChildren(errorState("쪽지를 불러오지 못했습니다", messageMessage(entry.error), retry(load)));
      return;
    }
    const before = new Set(items.map((item) => item.messageId));
    shown = data;
    items = Array.isArray(data.items) ? data.items : [];
    // Under 전체선택, rows that appear later (더 보기, a reload) are chosen too unless left out.
    if (wide.on) for (const item of items) if (!before.has(item.messageId) && !wide.except.has(item.messageId)) chosen.add(item.messageId);
    for (const id of [...chosen]) if (!items.some((item) => item.messageId === id)) chosen.delete(id);
    for (const id of [...wide.except]) if (!items.some((item) => item.messageId === id)) wide.except.delete(id);
    all.disabled = !items.length;
    removeAll.disabled = !data.total;
    const scope = q ? "검색 결과" : inbox && read ? (read === "false" ? "읽지 않은 쪽지" : "읽은 쪽지") : "전체";
    summary.textContent = inbox
      ? `${scope} ${data.total}건 · 받은 쪽지 중 안 읽음 ${data.unreadCount}건`
      : `${scope} ${data.total}건`;
    rows.replaceChildren(...(items.length
      ? items.map(row)
      : [el("p", { class: "msg-empty", text: q || read ? "조건에 맞는 쪽지가 없습니다." : "쪽지가 없습니다." })]));
    syncAll();
    const button = paintMore(data, entry.error);
    if (focusFrom >= 0) {
      const from = focusFrom;
      focusFrom = -1;
      const first = from < items.length ? rows.children[from].querySelector("a, input") : null;
      if (first) first.focus();
      else if (button) button.focus();
    }
  }

  removeAll.addEventListener("click", () => {
    if (removeAll.disabled || !shown) return;
    openDeleteAll({
      name,
      opener: removeAll,
      plan: {
        read,
        q,
        until: shown.serverTime,
        total: Number(shown.total) || 0,
        exceptIds: wide.on ? [...wide.except] : [],
        scope: q ? `‘${q}’ 검색 결과의` : inbox && read ? (read === "false" ? "읽지 않은" : "읽은") : "이 목록의",
        rowIds: items.map((item) => item.messageId),
      },
      done(result, plan) {
        const count = result && Number.isInteger(result.deleted) ? result.deleted : 0;
        const kept = new Set(plan.exceptIds);
        if (!embedded && currentId && plan.rowIds.includes(currentId) && !kept.has(currentId)) clearDetail(side);
        rebuild("[data-msg-note]", `쪽지 ${count}개를 삭제했습니다. 목록을 불러온 뒤에 온 쪽지는 남겨 두었습니다.`);
      },
      stale() {
        rebuild("[data-msg-note]", "삭제 결과를 확인하지 못해 목록을 다시 불러왔습니다.");
      },
    });
  });

  if (embedded) {
    remove.addEventListener("click", () => {
      const ids = [...chosen];
      if (!ids.length) {
        say(note, "삭제할 쪽지를 선택하세요", true);
        return;
      }
      // More than one request may carry: nothing is sent and the selection stays.
      const limit = pickedLimitText(ids.length);
      if (limit) {
        say(note, limit, true);
        return;
      }
      void guarded(remove, async () => {
        try {
          const result = await deleteMessages(name, ids);
          rebuild("[data-msg-note]", `쪽지 ${result && Number.isInteger(result.deleted) ? result.deleted : ids.length}개를 삭제했습니다`);
        } catch (error) {
          // 404: a chosen message is gone; the box is read again without the selection. Any other
          // failure keeps the list and the selection.
          if (isMissing(error)) rebuild("[data-msg-note]", "지울 수 없는 쪽지가 섞여 있어 아무것도 지우지 않았습니다. 목록을 다시 불러왔습니다");
          else if (side.isConnected) say(note, messageMessage(error), true);
        }
      });
    });
  }

  paint(resource.peek(key));
  attached(side, () => {
    resource.watch(side, key, paint, load);
    if (freshVisit(`msg-${name}${embedded ? "-embedded" : ""}`, key) === "entry" || !resource.peek(key)) load();
  });
  return side;
}

/** After 전체 삭제 took the message open beside the list, the detail pane shows a placeholder. */
function clearDetail(side) {
  const pane = side.closest(".msg-pane");
  const main = pane && pane.querySelector(".msg-main");
  if (!main) return;
  pane.classList.remove("has-detail");
  main.replaceWith(el("div", { class: "msg-main" }, el("p", { class: "msg-placeholder", text: "삭제한 쪽지입니다. 목록에서 다른 쪽지를 선택하세요." })));
}

/**
 * The 전체 삭제 confirmation (native modal dialog: Esc and 취소 close it, focus returns to the
 * opener). `plan` is fixed when it opens: the shown list's filter, its first page's `serverTime`
 * as `until`, its `total` and the rows left out, so nothing the user has not seen is deleted.
 */
function openDeleteAll({ name, opener, plan, done, stale }) {
  const count = Math.max(0, plan.total - plan.exceptIds.length);
  const inbox = name !== "sent";
  const problem = el("p", { class: "err", role: "alert" });
  const cancel = btn("취소", {}, "ghost");
  const confirm = btn(`${count}건 삭제`, { "data-msg-delete-all-confirm": "" }, "primary");
  const tooMany = plan.exceptIds.length > MAX_BULK;
  if (tooMany || !plan.until || !count) confirm.disabled = true;
  const lines = [
    el("p", { text: `${inbox ? "받은" : "보낸"} 쪽지 중 ${plan.scope} 쪽지 ${count}건을 삭제합니다.` }),
    el("p", { class: "tiny", text: plan.until ? `목록을 불러온 ${koDateTime(plan.until)} 뒤에 온 쪽지는 지우지 않습니다.` : "목록을 다시 불러온 뒤 시도하세요." }),
    plan.exceptIds.length ? el("p", { class: "tiny", text: `선택을 해제한 ${plan.exceptIds.length}건은 남깁니다.` }) : null,
    el("p", { class: "tiny", text: inbox ? "받은 쪽지함에서만 지웁니다." : "보낸 쪽지함에서만 지웁니다. 받는 사람의 쪽지는 그대로입니다." }),
    tooMany ? el("p", { class: "err", text: `선택을 해제한 쪽지는 ${MAX_BULK}건까지 뺄 수 있습니다.` }) : null,
  ];
  const dialog = el("dialog", { class: "modal", "aria-labelledby": "srv-msg-delete-all-title", "data-msg-delete-all-dialog": "" },
    el("div", { class: "modal-h" }, el("h2", { id: "srv-msg-delete-all-title", text: "전체 삭제" })),
    el("div", { class: "modal-b stack" }, ...compact(lines), problem, el("div", { class: "pf-actions" }, cancel, confirm)),
  );
  dialog.style.padding = "0";
  dialog.style.width = "min(440px, calc(100% - 32px))";
  let sending = false;
  let changed = false; // an uncertain failure dropped the cached list
  let finished = false;
  const close = () => {
    if (!sending) dialog.close();
  };
  cancel.addEventListener("click", close);
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    close();
  });
  dialog.addEventListener("close", () => {
    dialog.remove();
    if (finished) return;
    if (changed) stale();
    else if (opener.isConnected) opener.focus();
  });
  confirm.addEventListener("click", async () => {
    if (sending || confirm.disabled) return;
    sending = true;
    busy(confirm, true, "삭제하는 중…");
    cancel.disabled = true;
    say(problem, "");
    try {
      const result = await deleteAll(name, plan);
      finished = true;
      sending = false;
      dialog.close();
      done(result, plan);
    } catch (error) {
      sending = false;
      if (isUncertain(error)) changed = true;
      say(problem, isUncertain(error)
        ? "삭제 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 다시 시도하세요. 다시 눌러도 목록을 불러온 뒤에 온 쪽지는 지우지 않습니다."
        : `삭제하지 못했습니다. ${messageMessage(error)}`, true);
      cancel.disabled = false;
      if (confirm.isConnected) {
        busy(confirm, false, `${count}건 삭제`);
        confirm.focus();
      }
    }
  });
  (opener.closest(".msg-list") || document.body).append(dialog);
  dialog.showModal();
  cancel.focus();
}

/**
 * The professor's 보낸 쪽지 box for `pf_message` (same list, search, 더 보기 and 전체 삭제;
 * no detail links because the detail is a student-window screen). `refresh()` reloads it.
 */
export function serverSentBox() {
  const holder = el("div", { class: "srv-msg", "data-msg-embedded": "" });
  const paint = () => holder.replaceChildren(boxList("sent", null, { embedded: true }));
  paint();
  return { node: holder, refresh: paint };
}

// ---------- recipient search ----------

/**
 * Name search over `GET /api/messages/recipients` for one course (`courseId`, or null for every
 * shared course). `chosen` is a Map userId -> {userId, name, role} changed in place.
 */
export function recipientSearch({ courseId, chosen, onChange }) {
  const state = { q: "", page: 1 };
  let key = null;
  const node = el("div", { class: "msg-finder" });
  const input = el("input", { type: "search", maxlength: "50", placeholder: "이름으로 찾기", "aria-label": "받는 사람 이름", "data-recipient-q": "" });
  const findButton = btn("찾기", {}, "secondary");
  const list = el("div", { class: "msg-finder__list", "aria-live": "polite" });
  const pager = el("div", { class: "msg-finder__pager" });
  node.append(el("div", { class: "msg-finder__bar" }, input, findButton), list, pager);
  const changed = () => { if (onChange) onChange(); };

  const paint = (entry) => {
    const data = entry && entry.data;
    pager.replaceChildren();
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(loadingState("받는 사람을 찾는 중입니다"));
      else if (isMissing(entry.error)) list.replaceChildren(emptyState("이 과목에서 찾을 수 없습니다", "이 과목의 구성원이 아니거나 서버에 없는 과목입니다."));
      else list.replaceChildren(errorState("받는 사람을 찾지 못했습니다", messageMessage(entry.error), retry(show)));
      return;
    }
    const items = Array.isArray(data.items) ? data.items : [];
    if (!items.length) {
      list.replaceChildren(el("p", { class: "msg-empty", text: state.q ? "찾는 이름이 없습니다. 이 과목에서 쪽지를 보낼 수 있는 사람만 나옵니다." : "이 과목에서 쪽지를 보낼 수 있는 사람이 없습니다." }));
      return;
    }
    list.replaceChildren(...items.map((person) => {
      const box = el("input", { type: "checkbox", value: String(person.userId), checked: chosen.has(person.userId) ? true : null });
      box.addEventListener("change", () => {
        if (box.checked) chosen.set(person.userId, { userId: person.userId, name: person.name, role: person.role });
        else chosen.delete(person.userId);
        changed();
      });
      return el("label", { class: "choice", "data-user": String(person.userId) }, box,
        personAvatar({ userId: person.userId, name: String(person.name || "?"), className: "msg-avatar" }),
        el("span", {}, el("b", { text: person.name }), el("span", { class: "tiny", text: ROLE_LABEL[person.role] || "구성원" })));
    }));
    const pages = Math.max(1, Math.ceil(data.total / (data.size || RECIPIENT_PAGE)));
    const pickAll = btn("이 쪽 모두 선택", {}, "ghost");
    pickAll.addEventListener("click", () => {
      for (const person of items) chosen.set(person.userId, { userId: person.userId, name: person.name, role: person.role });
      changed();
      paint(recipientPages.peek(key));
    });
    const prev = btn("이전", { "aria-label": "이전 쪽" }, "ghost");
    const next = btn("다음", { "aria-label": "다음 쪽" }, "ghost");
    prev.disabled = state.page <= 1;
    next.disabled = state.page >= pages;
    prev.addEventListener("click", () => { state.page -= 1; show(); });
    next.addEventListener("click", () => { state.page += 1; show(); });
    pager.append(pickAll, el("span", { class: "tiny", text: `${state.page} / ${pages}쪽 · ${data.total}명` }), el("span", { class: "msg-finder__nav" }, prev, next));
  };

  function show() {
    const current = recipientKey(courseId, state.q, state.page);
    key = current;
    recipientPages.watch(node, current, (entry) => { if (key === current) paint(entry); }, null);
    paint(recipientPages.peek(current));
    const entry = recipientPages.peek(current);
    if (!entry || (!entry.data && !entry.loading)) recipientPages.load(current, courseId, state.q, state.page);
  }
  const search = () => {
    state.q = input.value.trim();
    state.page = 1;
    show();
  };
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    search();
  });
  findButton.addEventListener("click", search);
  list.replaceChildren(loadingState("받는 사람을 찾는 중입니다"));
  attached(node, show);
  return { node, refresh: () => { if (key) paint(recipientPages.peek(key)); } };
}
