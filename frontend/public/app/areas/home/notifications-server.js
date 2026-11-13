// Notification dialog (`?pop=notify` 전체, `?pop=notify-unread` 안 읽음) from the ASTRA backend
// while the connection is `ready`; the login panel while it is `anon`. Viewing never marks a
// notification read: opening its link sends `read` and then navigates. Server text is only
// ever set as text nodes.
import { el } from "../../dom.js";
import { btn, emptyState, loadingState, errorState } from "../../ui/components.js";
import { currentRole, routeFor } from "../../shared/routes.js";
import { backendStatus } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { onUnread, unreadCount } from "../../shared/unread-badges.js";
import { koDateTime } from "../../shared/home-remote.js";
import {
  inboxEntry, watchInbox, reloadInbox, loadMoreInbox, readNotification, readAllNotifications,
  deleteNotification, isGone, notificationHref, notificationTag, notificationMessage,
} from "../../shared/notifications-remote.js";

/** Runs `fn` once `node` is in the document (dialogs are attached right after render). */
function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function busy(button, on, label) {
  button.disabled = on;
  if (on) button.setAttribute("aria-busy", "true");
  else button.removeAttribute("aria-busy");
  if (label) button.textContent = label;
}

function chip(href, label, count, on) {
  return el("a", { class: `chip all${on ? " on" : ""}`, href, "data-go": href, "aria-current": on ? "page" : null },
    el("span", { text: label }),
    count === undefined ? null : el("span", { class: "ntf-count", text: count == null ? "" : String(count) }),
  );
}

const statusOf = new WeakMap(); // list root -> status line, for the open action

function say(status, text, alert) {
  status.setAttribute("role", alert ? "alert" : "status");
  status.textContent = text;
}

function row(item, status, list, filter) {
  const href = notificationHref(item);
  const read = Boolean(item.read);
  const tag = notificationTag(item);
  const title = `[${tag}] ${item.message || ""}`;
  const source = item.courseName ? `${item.courseName}${item.courseCode ? `(${item.courseCode})` : ""}` : tag;
  const link = el(href ? "a" : "div", {
    class: `ntf-row${read ? " read" : ""}`,
    href,
    "data-act": href ? "ntf-open" : null,
    "data-id": String(item.notificationId),
  },
  read
    ? el("span", { class: "ntf-dot", "aria-hidden": "true" })
    : el("span", { class: "ntf-dot", role: "img", "aria-label": "새 알림" }),
  el("span", { class: "ntf-copy" },
    el("b", { text: title }),
    el("span", { class: "ntf-src", text: source }),
    el("span", { class: "ntf-when" }, el("span", { text: koDateTime(item.createdAt) })),
  ),
  );
  if (href) {
    // Plain click: app.js runs `ntf-open`, which reads and then navigates in place. A new-tab
    // click keeps the browser default and still sends `read`.
    const aside = (event) => {
      event.stopPropagation();
      readNotification(item.notificationId).catch(() => {});
    };
    link.addEventListener("click", (event) => {
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return aside(event);
      event.preventDefault();
    });
    link.addEventListener("auxclick", (event) => {
      if (event.button === 1) aside(event);
    });
  }
  const remove = btn("삭제", { class: "btn btn-ghost ntf-del", "aria-label": `알림 삭제: ${title}` }, "ghost");
  remove.addEventListener("click", () => {
    if (remove.disabled) return;
    busy(remove, true, "삭제 중…");
    const next = remove.closest("li") && remove.closest("li").nextElementSibling;
    const nextId = next ? next.getAttribute("data-id") : null;
    deleteNotification(item.notificationId).then(() => {
      say(status, "알림을 삭제했습니다.");
      refocus(list, nextId, status);
    }, (error) => {
      if (isGone(error)) {
        say(status, notificationMessage(error), true);
        refocus(list, nextId, status);
        return;
      }
      if (remove.isConnected) busy(remove, false, "삭제");
      say(status, notificationMessage(error), true);
    });
  });
  return el("li", { class: "ntf-item", "data-id": String(item.notificationId), "data-filter": filter }, link, remove);
}

// After a row is removed the list repaints; focus moves to the next row (or the status line).
function refocus(list, nextId, status) {
  queueMicrotask(() => {
    const target = nextId ? list.querySelector(`li[data-id="${CSS.escape(nextId)}"] .ntf-row, li[data-id="${CSS.escape(nextId)}"] .ntf-del`) : null;
    if (target) target.focus();
    else {
      status.setAttribute("tabindex", "-1");
      status.focus();
    }
  });
}

function retry(filter) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    busy(button, true);
    reloadInbox(filter);
  });
  return button;
}

/** Body of the notification dialog for `name` ("notify" | "notify-unread"). */
export function serverNotifications(name) {
  if (backendStatus() !== "ready") {
    return el("div", { class: "ntf" }, backendPanel({ text: "ASTRA 서버에 로그인하면 내 알림을 서버에서 불러옵니다." }));
  }
  const filter = name === "notify-unread" ? "unread" : "all";
  const unreadChip = chip(routeFor(currentRole(), "notify", { pop: "notify-unread" }), "안 읽음", unreadCount("notifications"), filter === "unread");
  const unreadNumber = unreadChip.querySelector(".ntf-count");
  // 전체 tab count: `total` of the 전체 list (same visibility rule as the list), once it is read.
  const allEntry = inboxEntry("all");
  const allChip = chip(routeFor(currentRole(), "pop", { pop: "notify" }), "전체", allEntry && allEntry.data ? allEntry.data.total : null, filter === "all");
  const allNumber = allChip.querySelector(".ntf-count");
  const readAll = btn("모두 읽음", {}, "secondary");
  const status = el("p", { class: "task-empty", role: "status", "aria-live": "polite" });
  const slot = el("div", {});
  const list = el("ul", { class: "ntf-list" });
  const more = btn("더 보기", {}, "secondary");
  const actions = el("div", { class: "btn-row ntf-more" });
  const box = el("div", { class: "ntf" },
    el("nav", { class: "filters ntf-filters", "aria-label": "알림 보기" },
      allChip,
      unreadChip,
    ),
    el("div", { class: "ntf-top" },
      el("p", { class: "note", text: "60일 지난 알림 내용은 자동 삭제됩니다." }),
      readAll,
    ),
    status, slot, actions,
  );
  statusOf.set(box, status);

  const showCount = (count) => {
    unreadNumber.textContent = count == null ? "" : String(count);
    readAll.disabled = readAll.getAttribute("aria-busy") === "true" || count === 0;
  };
  showCount(unreadCount("notifications"));

  readAll.addEventListener("click", () => {
    if (readAll.getAttribute("aria-busy") === "true") return;
    busy(readAll, true, "처리 중…");
    readAllNotifications().then((result) => {
      busy(readAll, false, "모두 읽음");
      showCount(unreadCount("notifications"));
      say(status, result && result.updated ? `알림 ${result.updated}건을 읽음으로 표시했습니다.` : "읽지 않은 알림이 없습니다.");
    }, (error) => {
      busy(readAll, false, "모두 읽음");
      showCount(unreadCount("notifications"));
      say(status, notificationMessage(error), true);
    });
  });
  more.addEventListener("click", () => {
    if (more.disabled) return;
    busy(more, true, "불러오는 중…");
    loadMoreInbox(filter);
  });

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      actions.replaceChildren();
      slot.replaceChildren(entry && entry.error
        ? errorState("알림을 불러오지 못했습니다", notificationMessage(entry.error), retry(filter))
        : loadingState("알림을 불러오는 중입니다"));
      return;
    }
    if (data.items.length) {
      list.replaceChildren(...data.items.map((item) => row(item, status, list, filter)));
      slot.replaceChildren(list);
    } else {
      slot.replaceChildren(emptyState(filter === "unread" ? "읽지 않은 알림이 없습니다" : "받은 알림이 없습니다"));
    }
    if (entry.error) say(status, notificationMessage(entry.error), true);
    actions.replaceChildren(data.nextCursor ? more : "");
    busy(more, Boolean(entry.loading), entry.loading ? "불러오는 중…" : "더 보기");
    if (data.unreadCount != null) showCount(unreadCount("notifications") ?? data.unreadCount);
    if (filter === "all" && data.total != null) allNumber.textContent = String(data.total);
  };
  paint(inboxEntry(filter));
  attached(box, () => {
    watchInbox(box, filter, paint);
    const off = onUnread((kind, count) => {
      if (!box.isConnected) return off();
      if (kind === "notifications") showCount(count);
    });
  });
  return box;
}

/** `ntf-open` (home/actions.js): marks the notification read, then opens its link. */
export function openNotification(node, api) {
  if (node.getAttribute("aria-busy") === "true") return;
  const href = node.getAttribute("href");
  const id = Number(node.dataset.id);
  if (!href || !Number.isSafeInteger(id)) return;
  const box = node.closest(".ntf");
  const status = box ? statusOf.get(box) : null;
  node.setAttribute("aria-busy", "true");
  readNotification(id).then(() => api.navigate(href), (error) => {
    node.removeAttribute("aria-busy");
    // Withdrawn or no longer visible: the row is dropped and the link is not followed. Any other
    // failure (offline, rate limit) still opens the target; it stays unread on the server.
    if (isGone(error)) {
      if (status) say(status, notificationMessage(error), true);
      return;
    }
    api.navigate(href);
  });
}
