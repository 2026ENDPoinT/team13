import type { ReactNode } from "react";
import { render } from "../store.ts";
import { Icon } from "../ui/icon.tsx";
import type { AppState } from "../data.ts";
import type { ScreenCtx } from "../types.ts";

// One search bag for every surface. Home used to keep a single uiQuery and
// wipe it when the path changed. Course kept a separate string per board.
// Both now read and write state.queries[scope], so a catalog search cannot
// leak into a notice list, and coming back to a surface restores its own text.

export function readQuery(state: Pick<AppState, "queries"> | null | undefined, scope: string | null | undefined): string {
  const bag = state && state.queries;
  if (!bag || !scope) return "";
  return String(bag[scope] || "");
}

export function writeQuery(state: Pick<AppState, "queries"> | null | undefined, scope: string | null | undefined, value: unknown): void {
  if (!state || !scope) return;
  state.queries = { ...(state.queries || {}), [scope]: value == null ? "" : String(value) };
}

/** The surface search box: typing writes `state.queries[scope]` and redraws. */
export function pageSearch(ctx: ScreenCtx, scope: string, label = "과목 검색", placeholder = "강의명을 입력하세요"): ReactNode {
  return (
    <label className="search">
      <Icon name="search" />
      <input
        value={readQuery(ctx.state, scope)}
        onChange={(event) => {
          writeQuery(ctx.state, scope, event.target.value);
          render();
        }}
        placeholder={placeholder}
        aria-label={label}
      />
    </label>
  );
}
