// In-memory caches for server data shown while the backend connection is `ready`.
// Screens render synchronously from the cache, fetch when the user navigated to them
// (a new history entry) or changed the key (page, search), and re-render when a load
// lands (`useResource`). Nothing here is persisted; every cache is dropped when the session changes.
import { useEffect, useRef, useSyncExternalStore } from "react";
import { subscribe as onBackendChange } from "./backend.ts";

const visits = new Map<string, { entry: unknown; key: string }>();
// A history state object that `replaceVisit()` put in place of another stands for that one, so
// opening a `?pop=` window in place does not count as navigating to a new entry.
const continued = new WeakMap<object, unknown>(); // replacement state -> the state it continues (or null)

function entryOf(state: unknown) {
  return state !== null && typeof state === "object" && continued.has(state) ? continued.get(state) : state;
}

/**
 * Changes the address and state of the current history entry (`history.replaceState`) without
 * making it a new visit: `freshVisit()` answers as if the entry had not changed. For windows
 * opened or closed in place (the entry notice), which must not reload the screen under them.
 */
export function replaceVisit(url: string, state: object = {}) {
  const before = entryOf(history.state);
  history.replaceState(state, "", url);
  if (history.state !== null && typeof history.state === "object") continued.set(history.state, before);
}

/**
 * "entry" when this is a new history entry (router.tsx pushes one per navigation, popstate
 * restores another), "key" when only `key` changed on the same entry (search typing,
 * a page link to the same address), false for a plain re-render.
 */
export function freshVisit(scope: string, key = ""): "entry" | "key" | false {
  const entry = entryOf(history.state);
  const last = visits.get(scope);
  visits.set(scope, { entry, key });
  if (!last || last.entry !== entry) return "entry";
  return last.key !== key ? "key" : false;
}


export interface Entry<T> {
  data: T | null;
  error: unknown;
  loading: Promise<void> | null;
}

export interface Resource<T, A extends unknown[] = any[]> {
  peek(key: string): Entry<T> | null;
  load(key: string, ...args: A): Promise<void>;
  /** Loads `key` when the screen was navigated to, or when nothing is cached for it yet. */
  visit(scope: string, key: string, ...args: A): Promise<void>;
  /** Changes cached data in place (e.g. a notice marked read) and re-renders its readers. */
  patch(match: (key: string) => boolean, change: (data: T) => void): void;
  /** Forgets matching keys so the next visit loads them again. */
  drop(match: (key: string) => boolean): void;
  clear(): void;
  /** Calls `listener(key)` after any entry changed; returns the unsubscribe function. */
  subscribe(listener: (key: string) => void): () => void;
  /** Increments on every change; React reads it as the snapshot. */
  version(): number;
}

/**
 * A keyed cache of one kind of server response. `fetcher(...args)` returns a promise.
 * Concurrent loads of one key share a request; results from a previous session are ignored.
 */
export function createResource<T, A extends unknown[] = any[]>(fetcher: (...args: A) => Promise<T>): Resource<T, A> {
  const entries = new Map<string, Entry<T>>();
  const listeners = new Set<(key: string) => void>();
  let generation = 0;
  let changes = 0;

  function notify(key: string) {
    changes += 1;
    for (const listener of [...listeners]) listener(key);
  }

  const resource: Resource<T, A> = {
    peek(key) {
      return entries.get(key) || null;
    },
    load(key, ...args) {
      const current = entries.get(key);
      if (current && current.loading) return current.loading;
      const gen = generation;
      const entry: Entry<T> = { data: current ? current.data : null, error: null, loading: null };
      entry.loading = Promise.resolve()
        .then(() => fetcher(...args))
        .then((data) => {
          if (gen === generation) entry.data = data;
        }, (error: unknown) => {
          if (gen === generation) entry.error = error;
        })
        .finally(() => {
          if (gen !== generation) return;
          entry.loading = null;
          notify(key);
        });
      entries.set(key, entry);
      notify(key);
      return entry.loading;
    },
    visit(scope, key, ...args) {
      const fresh = freshVisit(scope, key);
      const entry = entries.get(key);
      if (fresh === "entry" || !entry) return resource.load(key, ...args);
      return entry.loading || Promise.resolve();
    },
    patch(match, change) {
      for (const [key, entry] of entries) {
        if (entry.data == null || !match(key)) continue;
        change(entry.data);
        notify(key);
      }
    },
    drop(match) {
      for (const key of [...entries.keys()]) if (match(key)) entries.delete(key);
    },
    clear() {
      generation += 1;
      entries.clear();
      notify("");
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    version() {
      return changes;
    },
  };

  let session = "";
  onBackendChange((next: { status: string; me: { userId: unknown } | null }) => {
    const id = `${next.status}:${next.me ? next.me.userId : ""}`;
    if (id === session) return;
    session = id;
    resource.clear();
  });
  return resource;
}

/**
 * The cached entry for `key` (null key: nothing), re-rendering the caller whenever any entry of
 * `resource` changes. `refresh` (optional) runs on window focus and when the tab becomes visible.
 */
export function useResource<T>(resource: Resource<T, any[]>, key: string | null, refresh?: () => void): Entry<T> | null {
  useSyncExternalStore(resource.subscribe, resource.version);
  useRefreshOnFocus(refresh);
  return key == null ? null : resource.peek(key);
}

/** Calls the latest `refresh` on window focus and when the tab becomes visible, while mounted. */
export function useRefreshOnFocus(refresh?: () => void) {
  const latest = useRef(refresh);
  latest.current = refresh;
  const enabled = Boolean(refresh);
  useEffect(() => {
    if (!enabled) return;
    const wake = () => {
      if (document.visibilityState === "visible") latest.current?.();
    };
    window.addEventListener("focus", wake);
    document.addEventListener("visibilitychange", wake);
    return () => {
      window.removeEventListener("focus", wake);
      document.removeEventListener("visibilitychange", wake);
    };
  }, [enabled]);
}

/** Korean "try again later" text for a 429, using Retry-After when the server sent it. */
export function retryLater(error: { retryAfter?: number | null } | null | undefined) {
  return `요청이 많습니다. ${error && error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
}
