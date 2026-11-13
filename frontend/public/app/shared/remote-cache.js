// In-memory caches for server data shown while the backend connection is `ready`.
// Screens render synchronously from the cache, fetch when the user navigated to them
// (a new history entry) or changed the key (page, search), and repaint in place when a
// load lands. Nothing here is persisted; every cache is dropped when the session changes.
import { subscribe } from "./backend.js";

const visits = new Map(); // scope -> { entry, key }
// A history state object that `replaceVisit()` put in place of another stands for that one, so
// opening a `?pop=` window in place does not count as navigating to a new entry.
const continued = new WeakMap(); // replacement state -> the state it continues (or null)

function entryOf(state) {
  return state !== null && typeof state === "object" && continued.has(state) ? continued.get(state) : state;
}

/**
 * Changes the address and state of the current history entry (`history.replaceState`) without
 * making it a new visit: `freshVisit()` answers as if the entry had not changed. For windows
 * opened or closed in place (the entry notice), which must not reload the screen under them.
 */
export function replaceVisit(url, state = {}) {
  if (typeof history === "undefined") return;
  const before = entryOf(history.state);
  history.replaceState(state, "", url);
  if (history.state !== null && typeof history.state === "object") continued.set(history.state, before);
}

/**
 * "entry" when this is a new history entry (app.js pushes one per navigation, popstate
 * restores another), "key" when only `key` changed on the same entry (search typing,
 * a page link to the same address), false for a plain re-render.
 */
export function freshVisit(scope, key = "") {
  const entry = typeof history !== "undefined" ? entryOf(history.state) : null;
  const last = visits.get(scope);
  visits.set(scope, { entry, key });
  if (!last || last.entry !== entry) return "entry";
  return last.key !== key ? "key" : false;
}

// Every live `watch()` with its node, so the app can release the watchers of a replaced screen
// at once (`releaseWatchers`) instead of waiting for the next notification, focus or visibility change.
const watchers = new Set(); // { node, stop, mark }
let watchCount = 0;

/** A mark for `releaseWatchers`: watchers made after it are left alone. */
export function watchMark() {
  return watchCount;
}

/** Stops every watcher made up to `mark` whose node is no longer in the document. */
export function releaseWatchers(mark = watchCount) {
  for (const watcher of [...watchers]) if (watcher.mark <= mark && !watcher.node.isConnected) watcher.stop();
}

/**
 * A keyed cache of one kind of server response. `fetcher(...args)` returns a promise.
 * Concurrent loads of one key share a request; results from a previous session are ignored.
 */
export function createResource(fetcher) {
  const entries = new Map(); // key -> { data, error, loading }
  const listeners = new Set();
  let generation = 0;

  function notify(key) {
    for (const listener of [...listeners]) listener(key);
  }

  const resource = {
    peek(key) {
      return entries.get(key) || null;
    },
    load(key, ...args) {
      const current = entries.get(key);
      if (current && current.loading) return current.loading;
      const gen = generation;
      const entry = { data: current ? current.data : null, error: null, loading: null };
      entry.loading = Promise.resolve()
        .then(() => fetcher(...args))
        .then((data) => {
          if (gen === generation) entry.data = data;
        }, (error) => {
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
    /** Loads `key` when the screen was navigated to, or when nothing is cached for it yet. */
    visit(scope, key, ...args) {
      const fresh = freshVisit(scope, key);
      const entry = entries.get(key);
      if (fresh === "entry" || !entry) return resource.load(key, ...args);
      return entry.loading || Promise.resolve();
    },
    /** Changes cached data in place (e.g. a notice marked read) and repaints watchers. */
    patch(match, change) {
      for (const [key, entry] of entries) {
        if (entry.data == null || !match(key)) continue;
        change(entry.data);
        notify(key);
      }
    },
    /** Forgets matching keys so the next visit loads them again. */
    drop(match) {
      for (const key of [...entries.keys()]) if (match(key)) entries.delete(key);
    },
    clear() {
      generation += 1;
      entries.clear();
    },
    /**
     * Repaints `node` whenever `key` changes while it is on screen, and calls `refresh`
     * on window focus or when the tab becomes visible. Stops once `node` is detached: at the
     * next screen swap (app.js `releaseWatchers`) or the next event, whichever comes first.
     * Returns the stop function.
     */
    watch(node, key, paint, refresh) {
      const listener = (changed) => {
        if (!node.isConnected) return stop();
        if (changed === key) paint(entries.get(key) || null);
      };
      const wake = () => {
        if (!node.isConnected) return stop();
        if (refresh && document.visibilityState === "visible") refresh();
      };
      const watcher = { node, stop, mark: ++watchCount };
      listeners.add(listener);
      watchers.add(watcher);
      window.addEventListener("focus", wake);
      document.addEventListener("visibilitychange", wake);
      function stop() {
        listeners.delete(listener);
        watchers.delete(watcher);
        window.removeEventListener("focus", wake);
        document.removeEventListener("visibilitychange", wake);
      }
      return stop;
    },
  };

  let session = "";
  subscribe((next) => {
    const id = `${next.status}:${next.me ? next.me.userId : ""}`;
    if (id === session) return;
    session = id;
    resource.clear();
  });
  return resource;
}

/** Korean "try again later" text for a 429, using Retry-After when the server sent it. */
export function retryLater(error) {
  return `요청이 많습니다. ${error && error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
}
