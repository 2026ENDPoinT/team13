import { el, icon } from "../dom.js";

// One search bag for every surface. Home used to keep a single uiQuery and
// wipe it when the path changed. Course kept a separate string per board.
// Both now read and write state.queries[scope], so a catalog search cannot
// leak into a notice list, and coming back to a surface restores its own text.

export function readQuery(state, scope) {
  const bag = state && state.queries;
  if (!bag || !scope) return "";
  return String(bag[scope] || "");
}

export function writeQuery(state, scope, value) {
  if (!state || !scope) return;
  state.queries = { ...(state.queries || {}), [scope]: value == null ? "" : String(value) };
}

export function pageSearch(ctx, scope, label = "과목 검색", placeholder = "강의명을 입력하세요") {
  return el("label", { class: "search" },
    icon("search"),
    el("input", {
      "data-name": "surfaceQuery",
      "data-scope": scope,
      "data-focus": scope,
      value: readQuery(ctx.state, scope),
      placeholder,
      "aria-label": label,
    }),
  );
}
