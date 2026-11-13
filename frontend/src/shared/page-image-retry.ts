/**
 * Recovery for server page images (`/doc/<hex>/NNN.jpg`) whose delivery cookie expired.
 *
 * The image base is content-addressed, so refetching the summary (which reissues the cookie) usually returns the same
 * address. Setting the same `src` again does not make the browser retry, so every retry remounts the `<img>`
 * (`attempt` is its React key). One automatic retry per image; after that the image shows a message and a manual
 * retry, which gets exactly one more attempt, so a permanent failure never loops.
 */

/** Automatic retries per image before the failure message. */
export const AUTO_RETRIES = 1;

export type PageImagePhase = "show" | "refreshing" | "failed";

export interface PageImageState {
  base: string;
  /** Bumped on every reload; used as the `<img>` key so the browser requests the file again. */
  attempt: number;
  phase: PageImagePhase;
  autoUsed: number;
}

export type PageImageEvent =
  | { type: "error" }
  | { type: "retry" }
  | { type: "refreshed"; base: string | null };

export function initialPageImage(base: string): PageImageState {
  return { base, attempt: 0, phase: "show", autoUsed: 0 };
}

export function pageImageStep(state: PageImageState, event: PageImageEvent): PageImageState {
  switch (event.type) {
    case "error":
      if (state.phase !== "show") return state;
      return state.autoUsed < AUTO_RETRIES
        ? { ...state, phase: "refreshing", autoUsed: state.autoUsed + 1 }
        : { ...state, phase: "failed" };
    case "retry":
      return state.phase === "failed" ? { ...state, phase: "refreshing" } : state;
    case "refreshed":
      if (state.phase !== "refreshing") return state;
      return event.base ? { ...state, base: event.base, attempt: state.attempt + 1, phase: "show" } : { ...state, phase: "failed" };
  }
}

/**
 * Shares one in-flight refetch between every image that fails at the same moment. Unlike a memoised promise, a
 * failure after the refetch settled starts a new one (the cookie can expire again). A thrown or rejected refetch
 * resolves to null.
 */
export function sharedRefresh(load: () => Promise<string | null> | string | null): () => Promise<string | null> {
  let inflight: Promise<string | null> | null = null;
  return () => {
    inflight ??= Promise.resolve()
      .then(load)
      .catch(() => null)
      .finally(() => {
        inflight = null;
      });
    return inflight;
  };
}
