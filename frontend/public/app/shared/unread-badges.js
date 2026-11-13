// Header counts (쪽지·알림 안 읽은 수, 할 일 수) while the backend connection is `ready`. Counts
// stay in memory only. Reading polls `GET /api/me/notifications/unread-count`,
// `GET /api/messages/unread-count` and `GET /api/me/todo/count` (its `total`); a response that
// already carries the new count sets it at once through `noteUnread`, and a poll that left before
// it is dropped when it lands. Nothing is polled when the
// connection is `off`/`anon` or while the page is hidden; becoming visible again reads all counts.
import { el } from "../dom.js";
import { apiGet } from "./api.js";
import { backendState, backendStatus, subscribe } from "./backend.js";

// 60 s: a header badge only needs to be roughly current, and three small requests per open tab
// per minute keep the load low (the to-do read is the count endpoint, no rows). The user's own
// reads and deletes update the count at once from the command response, so a shorter interval
// would buy nothing; there is no push channel yet (architecture §11 "알림 push").
export const UNREAD_POLL_MS = 60_000;

const PATHS = {
  messages: "/api/messages/unread-count",
  notifications: "/api/me/notifications/unread-count",
  todo: "/api/me/todo/count",
};
// The field holding the count in each response; `/api/me/todo/count` is `{serverTime, total, truncated}`.
const FIELDS = { messages: "unreadCount", notifications: "unreadCount", todo: "total" };

const counts = { messages: null, notifications: null, todo: null };
// Per-kind sequence, bumped when a read starts and when `noteUnread()` sets a count. A read whose
// sequence changed before it landed is older than the count on screen and is dropped.
const seq = { messages: 0, notifications: 0, todo: 0 };
const listeners = new Set();
let timer = null;
let generation = 0;
let session = "";

function emit(kind) {
  for (const listener of [...listeners]) {
    try {
      listener(kind, counts[kind]);
    } catch {
      /* one subscriber must not break the others */
    }
  }
}

function set(kind, count) {
  const next = Number.isInteger(count) && count >= 0 ? count : null;
  if (counts[kind] === next) return;
  counts[kind] = next;
  emit(kind);
}

const visible = () => typeof document === "undefined" || document.visibilityState !== "hidden";

function readOne(kind, gen) {
  const mine = ++seq[kind];
  return apiGet(PATHS[kind]).then((body) => {
    if (gen === generation && mine === seq[kind] && body && Number.isInteger(body[FIELDS[kind]])) set(kind, body[FIELDS[kind]]);
  }, () => {
    /* keep the last count; the next poll tries again */
  });
}

function stop() {
  clearInterval(timer);
  timer = null;
}

function schedule() {
  stop();
  if (backendStatus() !== "ready" || !visible()) return;
  timer = setInterval(() => {
    if (backendStatus() !== "ready" || !visible()) return stop();
    readNow();
  }, UNREAD_POLL_MS);
}

function readNow() {
  const gen = generation;
  return Promise.all(Object.keys(PATHS).map((kind) => readOne(kind, gen))).then(() => undefined);
}

/** Sets the shown count for `"messages"` | `"notifications"` | `"todo"` now (from a response). */
export function noteUnread(kind, count) {
  if (!(kind in counts) || backendStatus() !== "ready") return;
  seq[kind] += 1;
  set(kind, count);
}

/** Re-reads every count now. No-op unless the backend connection is `ready`. */
export function refreshUnread() {
  if (backendStatus() !== "ready") return Promise.resolve();
  if (timer === null && visible()) schedule();
  return readNow();
}

/** Last known count, or null when unknown (not `ready`, or not read yet). */
export function unreadCount(kind) {
  return kind in counts ? counts[kind] : null;
}

/** `fn(kind, count)` on every change. Returns an unsubscribe function. */
export function onUnread(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// Header buttons showing a count. The shell rebuilds the header on every render, so a bound
// button is forgotten once it has left the document; the newest button is painted in place.
const bound = new Set(); // { button, kind, label, mock }

function paintBadge(entry) {
  const { button, kind, label, mock } = entry;
  const ready = backendStatus() === "ready";
  const count = ready ? counts[kind] : mock;
  const shown = Number(count) || 0;
  // The to-do count is what is left to do, not an unread count.
  const live = kind === "todo" ? `${label} ${shown}건` : `${label} 안 읽음 ${shown}건`;
  button.setAttribute("aria-label", ready ? (count == null ? label : live) : `${label} ${mock}건`);
  const old = button.querySelector(".badge-count");
  if (shown <= 0) {
    if (old) old.remove();
    return;
  }
  const text = shown > 99 ? "99" : String(shown);
  if (old) old.textContent = text;
  else button.append(el("span", { class: "badge-count", text }));
}

function prune() {
  for (const entry of [...bound]) if (!entry.button.isConnected) bound.delete(entry);
}

/**
 * Shows the live count of `kind` on a header `button` while the backend is `ready` (aria-label
 * "<label> 안 읽음 N건", for `"todo"` "<label> N건"), and the sample count `mock` ("<label> N건")
 * otherwise. The badge node is updated in place when the count changes, without re-rendering
 * the app. Returns `button`.
 */
export function bindUnread(button, kind, label, mock) {
  const entry = { button, kind, label, mock };
  paintBadge(entry);
  bound.add(entry);
  // By the next microtask the new header is attached and the previous one detached.
  queueMicrotask(prune);
  return button;
}

onUnread((kind) => {
  prune();
  for (const entry of bound) if (entry.kind === kind) paintBadge(entry);
});

function follow(next) {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  generation += 1;
  stop();
  for (const kind of Object.keys(counts)) set(kind, null);
  if (next.status === "ready") refreshUnread();
}

subscribe(follow);
follow(backendState());

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (!visible()) return stop();
    if (backendStatus() === "ready") {
      schedule();
      readNow();
    }
  });
}
