// Header counts (쪽지·알림 안 읽은 수, 할 일 수) while the backend connection is `ready`. Counts
// stay in memory only. Reading polls `GET /api/me/notifications/unread-count`,
// `GET /api/messages/unread-count` and `GET /api/me/todo/count` (its `total`); a response that
// already carries the new count sets it at once through `noteUnread`, and a poll that left before
// it is dropped when it lands. Nothing is polled when the
// connection is `off`/`anon` or while the page is hidden; becoming visible again reads all counts.
import { useSyncExternalStore } from "react";
import { apiGet } from "./api.ts";
import { backendState, backendStatus, subscribe, type BackendSnapshot } from "./backend.ts";
export type UnreadKind = "messages" | "notifications" | "todo";
export type UnreadListener = (kind: UnreadKind, count: number | null) => void;

const KINDS: UnreadKind[] = ["messages", "notifications", "todo"];

// 60 s: a header badge only needs to be roughly current, and three small requests per open tab
// per minute keep the load low (the to-do read is the count endpoint, no rows). The user's own
// reads and deletes update the count at once from the command response, so a shorter interval
// would buy nothing; there is no push channel yet (architecture §11 "알림 push").
export const UNREAD_POLL_MS = 60_000;

const PATHS: Record<UnreadKind, string> = {
  messages: "/api/messages/unread-count",
  notifications: "/api/me/notifications/unread-count",
  todo: "/api/me/todo/count",
};
// The field holding the count in each response; `/api/me/todo/count` is `{serverTime, total, truncated}`.
const FIELDS: Record<UnreadKind, string> = { messages: "unreadCount", notifications: "unreadCount", todo: "total" };

const counts: Record<UnreadKind, number | null> = { messages: null, notifications: null, todo: null };
// Per-kind sequence, bumped when a read starts and when `noteUnread()` sets a count. A read whose
// sequence changed before it landed is older than the count on screen and is dropped.
const seq: Record<UnreadKind, number> = { messages: 0, notifications: 0, todo: 0 };
const listeners = new Set<UnreadListener>();
let timer: ReturnType<typeof setInterval> | null = null;
let generation = 0;
let session = "";

function emit(kind: UnreadKind): void {
  for (const listener of [...listeners]) {
    try {
      listener(kind, counts[kind]);
    } catch {
      /* one subscriber must not break the others */
    }
  }
}

function set(kind: UnreadKind, count: unknown): void {
  const next = typeof count === "number" && Number.isInteger(count) && count >= 0 ? count : null;
  if (counts[kind] === next) return;
  counts[kind] = next;
  emit(kind);
}

const visible = () => typeof document === "undefined" || document.visibilityState !== "hidden";

function readOne(kind: UnreadKind, gen: number): Promise<void> {
  const mine = ++seq[kind];
  return apiGet<Record<string, unknown> | null>(PATHS[kind]).then((body) => {
    const value = body ? body[FIELDS[kind]] : undefined;
    if (gen === generation && mine === seq[kind] && Number.isInteger(value)) set(kind, value);
  }, () => {
    /* keep the last count; the next poll tries again */
  });
}

function stop(): void {
  if (timer !== null) clearInterval(timer);
  timer = null;
}

function schedule(): void {
  stop();
  if (backendStatus() !== "ready" || !visible()) return;
  timer = setInterval(() => {
    if (backendStatus() !== "ready" || !visible()) return stop();
    void readNow();
  }, UNREAD_POLL_MS);
}

function readNow(): Promise<void> {
  const gen = generation;
  return Promise.all(KINDS.map((kind) => readOne(kind, gen))).then(() => undefined);
}

/** Sets the shown count for `"messages"` | `"notifications"` | `"todo"` now (from a response). */
export function noteUnread(kind: UnreadKind, count: number | null | undefined): void {
  if (!KINDS.includes(kind) || backendStatus() !== "ready") return;
  seq[kind] += 1;
  set(kind, count);
}

/** Re-reads every count now. No-op unless the backend connection is `ready`. */
export function refreshUnread(): Promise<void> {
  if (backendStatus() !== "ready") return Promise.resolve();
  if (timer === null && visible()) schedule();
  return readNow();
}

/** Last known count, or null when unknown (not `ready`, or not read yet). */
export function unreadCount(kind: UnreadKind): number | null {
  return KINDS.includes(kind) ? counts[kind] : null;
}

/** `fn(kind, count)` on every change. Returns an unsubscribe function. */
export function onUnread(fn: UnreadListener): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

let version = 0;
onUnread(() => {
  version += 1;
});

/**
 * The header count of `kind`: the live count while the backend is `ready` (null until read), else the
 * sample `mock`. Re-renders the caller when the count changes.
 */
export function useUnread(kind: UnreadKind, mock: number): number | null {
  useSyncExternalStore(onUnread, () => version);
  return backendStatus() === "ready" ? counts[kind] : mock;
}

/**
 * The aria-label of a header count button: "<label> 안 읽음 N건" (for `"todo"` "<label> N건") while the
 * backend is `ready`, "<label>" before the count is read, and the sample "<label> N건" otherwise.
 */
export function unreadLabel(kind: UnreadKind, label: string, count: number | null): string {
  if (backendStatus() !== "ready") return `${label} ${count}건`;
  if (count == null) return label;
  // The to-do count is what is left to do, not an unread count.
  return kind === "todo" ? `${label} ${count}건` : `${label} 안 읽음 ${count}건`;
}

function follow(next: BackendSnapshot): void {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  generation += 1;
  stop();
  for (const kind of KINDS) set(kind, null);
  if (next.status === "ready") void refreshUnread();
}

subscribe(follow);
follow(backendState());

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (!visible()) return stop();
    if (backendStatus() === "ready") {
      schedule();
      void readNow();
    }
  });
}
