// Header counts (쪽지·알림 안 읽은 수, 할 일 수) while the backend connection is `ready`. Counts
// stay in memory only. Reading polls `GET /api/me/notifications/unread-count`,
// `GET /api/messages/unread-count` and `GET /api/me/todo/count` (its `total`); a response that
// already carries the new count sets it at once through `noteUnread`, and a poll that left before
// it is dropped when it lands. Nothing is polled when the
// connection is `off`/`anon` or while the page is hidden; becoming visible again reads all counts.
//
// 머리말 배지(`useBadge`)는 그 가운데 목록을 마지막으로 연 뒤에 생긴 것만 센다. 아이콘이 여는 목록이 보이는
// 동안(`useSeenWhileOpen`) 지금 것을 본 것으로 적는다. 견본(`off`/`anon`)은 본 견본 항목 id를, 서버(`ready`)는
// 사람·종류별로 그때의 서버 수를 AppState `badgeSeen`(store.ts가 이 브라우저에 저장)에 남긴다. 서버 수가 그
// 아래로 내려가면(읽거나 지움) 기준도 따라 내려가 그 뒤에 온 것이 다시 보인다. 서버의 읽음 상태는 건드리지
// 않는다: 알림·쪽지의 안 읽음 표시는 목록에서 읽거나 "모두 읽음"을 누를 때만 바뀐다.
import { useEffect, useSyncExternalStore } from "react";
import { apiGet } from "./api.ts";
import { backendState, backendStatus, subscribe, type BackendSnapshot } from "./backend.ts";
import { openTodos } from "./format.ts";
import { sampleNotifications } from "../data.ts";
import { getState, render, save, useAppState } from "../store.ts";
import type { Screen } from "../types.ts";
export type UnreadKind = "messages" | "notifications" | "todo";
export type UnreadListener = (kind: UnreadKind, count: number | null) => void;

declare module "../data.ts" {
  interface AppState {
    /** Header badge marks: per kind the sample item ids seen, and per `<userId>:<kind>` the server count when its list was last open. */
    badgeSeen?: { sample?: Partial<Record<UnreadKind, string[]>>; server?: Record<string, number> };
  }
}

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
  if (next !== null) lowerSeen(kind, next);
  emit(kind);
}

// Whose server count a seen mark belongs to.
function serverKey(kind: UnreadKind): string {
  const me = backendState().me;
  return `${me ? me.userId : ""}:${kind}`;
}

/** A server count below its seen mark (items read or removed since) lowers the mark, so later arrivals count again. */
function lowerSeen(kind: UnreadKind, count: number): void {
  const marks = getState().badgeSeen?.server;
  const key = serverKey(kind);
  const mark = marks ? marks[key] : undefined;
  if (!marks || mark === undefined || mark <= count) return;
  marks[key] = count;
  save();
}

/** Ids of the sample items behind each header count while the backend is not `ready`. */
function sampleKeys(kind: UnreadKind): string[] {
  if (kind === "todo") return openTodos().map((item) => item.id);
  if (kind === "messages") return getState().messages.filter((item) => item.folder === "inbox" && !item.read).map((item) => item.id);
  return sampleNotifications.filter((item) => !item.read).map((item) => item.title);
}

/** What arrived since the list of `kind` was last open: null while the server count is unknown. */
function unseen(kind: UnreadKind): number | null {
  const marks = getState().badgeSeen;
  if (backendStatus() === "ready") {
    const count = counts[kind];
    const mark = marks?.server?.[serverKey(kind)] ?? 0;
    return count === null ? null : Math.max(0, count - mark);
  }
  const seen = marks?.sample?.[kind] || [];
  return sampleKeys(kind).filter((key) => !seen.includes(key)).length;
}

/** Marks everything `kind` counts now as seen (a server count not read yet is marked when it lands). */
function markSeen(kind: UnreadKind): void {
  const state = getState();
  const marks = state.badgeSeen || (state.badgeSeen = {});
  if (backendStatus() === "ready") {
    const count = counts[kind];
    const server = marks.server || (marks.server = {});
    const key = serverKey(kind);
    if (count === null || server[key] === count) return;
    server[key] = count;
    save();
    emit(kind);
    return;
  }
  const keys = sampleKeys(kind);
  const sample = marks.sample || (marks.sample = {});
  const seen = sample[kind] || [];
  if (keys.every((key) => seen.includes(key))) return;
  sample[kind] = keys;
  save();
  render();
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

/** The live count of `kind` while the backend is `ready` (null until read, and otherwise). Re-renders the caller when it changes. */
export function useUnread(kind: UnreadKind): number | null {
  useSyncExternalStore(onUnread, () => version);
  return backendStatus() === "ready" ? counts[kind] : null;
}

/** The header badge of `kind` (see the top of this file). Re-renders the caller when it changes. */
export function useBadge(kind: UnreadKind): number | null {
  useSyncExternalStore(onUnread, () => version);
  useAppState();
  return unseen(kind);
}

/** The header count whose list `screen` shows (where its icon leads: 할 일·알림 창, 받은 쪽지, 교수 쪽지), or null. */
function listOn(screen: Screen, params: URLSearchParams): UnreadKind | null {
  const pop = params.get("pop") || "";
  if (pop === "todo" || pop === "todo-more") return "todo";
  if (pop.startsWith("notify")) return "notifications";
  if ((screen.kind === "messages" && (screen.folder || "inbox") === "inbox") || (screen.kind === "pf-form" && screen.form === "pmessage")) return "messages";
  return null;
}

/**
 * Marks a header count seen while `screen` shows its list: on arrival, and again whenever that count changes
 * meanwhile (the server count the list itself reads).
 */
export function useSeenWhileOpen(screen: Screen, params: URLSearchParams): void {
  const kind = listOn(screen, params);
  useEffect(() => {
    if (!kind) return;
    markSeen(kind);
    return onUnread((changed) => {
      if (changed === kind) markSeen(kind);
    });
  }, [kind]);
}

/** The aria-label of a header count button: "<label> 새 N건" while its badge shows N, else "<label>". */
export function unreadLabel(label: string, count: number | null): string {
  return count ? `${label} 새 ${count}건` : label;
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
