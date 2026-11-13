// The `?pop=` windows that exist. A name outside this list is a removed window (old bookmarks and notification links
// such as `?pop=avatar`, `files`, `history`): the router drops it from the address instead of opening a placeholder.
// Keep it equal to the names the area `renderDialog`s compare against (tests/unit/dialog-names.test.ts checks it).

export const DIALOG_NAMES: ReadonlySet<string> = new Set([
  // areas/home/dialogs.tsx
  "welcome", "settings", "display", "profile", "myinfo", "notify", "notify-unread", "todo", "todo-more", "external",
  "related", "apply", "chat", "qna-write", "survey", "notice", "material", "inquiry", "material-write", "notice-write",
  // areas/course/dialogs.tsx
  "syllabus", "switch", "qna", "qna-reply", "board-write", "board-post",
  // areas/learning/dialogs.tsx
  "day", "submit", "recipients", "calendar",
]);

export function knownDialog(name: string): boolean {
  return DIALOG_NAMES.has(name);
}

/**
 * `search` without a `pop` that names no existing window, or null when there is nothing to drop. The result keeps the
 * other parameters in order and is "" or "?…".
 */
export function withoutStalePop(search: string): string | null {
  const params = new URLSearchParams(search);
  const pop = params.get("pop");
  if (pop === null || knownDialog(pop)) return null;
  params.delete("pop");
  const rest = params.toString();
  return rest ? `?${rest}` : "";
}
