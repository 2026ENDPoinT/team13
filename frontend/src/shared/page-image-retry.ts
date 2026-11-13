/**
 * Recovery for server page images (`/doc/<hex>/NNN.jpg`) whose delivery cookie expired.
 *
 * The image base is content-addressed, so refetching the summary (which reissues the cookie) usually returns the same
 * address. Setting the same `src` again does not make the browser retry, so every retry remounts the `<img>`
 * (`attempt` is its React key). One automatic retry per image; after that the image shows a message and a manual
 * retry, which gets exactly one more attempt, so a permanent failure never loops. Once a base failed after a refresh,
 * other pages of it go straight to the message instead of each refetching the summary.
 */

/** Automatic retries per image before the failure message. */
export const AUTO_RETRIES = 1;
/** A refetch that has not answered by then counts as failed (the page shows the message and the retry button). */
export const REFRESH_TIMEOUT_MS = 15_000;

export type PageImagePhase = "show" | "refreshing" | "failed";

export interface PageImageState {
  base: string;
  /** Bumped on every reload; used as the `<img>` key so the browser requests the file again. */
  attempt: number;
  phase: PageImagePhase;
  autoUsed: number;
}

export type PageImageEvent =
  /** The `<img>` failed. `broken`: its base already failed after a refresh elsewhere, so no automatic retry. */
  | { type: "error"; broken?: boolean }
  | { type: "retry" }
  | { type: "refreshed"; base: string | null };

/** `broken`: the base already failed after a refresh, so the page starts on the failure message. */
export function initialPageImage(base: string, broken = false): PageImageState {
  return broken ? { base, attempt: 0, phase: "failed", autoUsed: AUTO_RETRIES } : { base, attempt: 0, phase: "show", autoUsed: 0 };
}

export function pageImageStep(state: PageImageState, event: PageImageEvent): PageImageState {
  switch (event.type) {
    case "error":
      if (state.phase !== "show") return state;
      return !event.broken && state.autoUsed < AUTO_RETRIES
        ? { ...state, phase: "refreshing", autoUsed: state.autoUsed + 1 }
        : { ...state, phase: "failed", autoUsed: Math.max(state.autoUsed, AUTO_RETRIES) };
    case "retry":
      return state.phase === "failed" ? { ...state, phase: "refreshing" } : state;
    case "refreshed":
      if (state.phase !== "refreshing") return state;
      return event.base ? { ...state, base: event.base, attempt: state.attempt + 1, phase: "show" } : { ...state, phase: "failed" };
  }
}

/** One viewer's refetch, shared by its page images, and the bases that already failed after a refresh. */
export interface ImageRefresher {
  /** Refetches the source (reissuing the cookie); resolves to the base, or null when it failed or timed out. */
  refresh(): Promise<string | null>;
  isBroken(base: string): boolean;
  /** A page of `base` still failed after a refresh. */
  markBroken(base: string): void;
  /** A page of `base` loaded: other pages may try again. */
  markLoaded(base: string): void;
}

/**
 * Shares one in-flight refetch between every image that fails at the same moment. Unlike a memoised promise, a
 * failure after the refetch settled starts a new one (the cookie can expire again). A thrown or rejected refetch, or
 * one still unanswered after `timeoutMs`, resolves to null.
 */
export function imageRefresher(load: () => Promise<string | null> | string | null, timeoutMs = REFRESH_TIMEOUT_MS): ImageRefresher {
  let inflight: Promise<string | null> | null = null;
  const broken = new Set<string>();
  return {
    refresh() {
      if (inflight) return inflight;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const limit = new Promise<null>((resolve) => {
        timer = setTimeout(() => resolve(null), timeoutMs);
      });
      // A new refetch starts only once this one settled and cleared `inflight`, so clearing it here is always ours.
      inflight = Promise.race([Promise.resolve().then(load).catch(() => null), limit]).finally(() => {
        clearTimeout(timer);
        inflight = null;
      });
      return inflight;
    },
    isBroken: (base) => broken.has(base),
    markBroken(base) {
      broken.add(base);
    },
    markLoaded(base) {
      broken.delete(base);
    },
  };
}
