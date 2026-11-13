// 폼에 입력 중인 값의 메모리 맵. 다시 그리기와 409 재조회 뒤에도 남고, 로그인 세션(상태나 사용자)이 바뀌면 비운다.
// 저장소(localStorage·IndexedDB)에는 쓰지 않는다. 초안은 `{ values, version, note }`다. `values`는 폼의 `[data-field]` 칸 값,
// `version`은 처음 고치기 시작할 때 화면에 있던 서버 버전(저장에 `If-Match`로 보낸다. 409 뒤에만 새 버전으로 바꾼다),
// `note`는 그 폼의 안내 `{ text, alert }`다.
import { subscribe } from "./backend.ts";

/** A form field value: a control's `value`, or a checkbox's `checked`. */
export type FieldValue = string | boolean;
export type FieldValues = Record<string, FieldValue>;

/** A form note `{ text, alert }`. */
export interface DraftNote {
  text: string;
  alert?: boolean;
}

/** An unsaved form draft. */
export interface Draft<V = string | number | null> {
  values: FieldValues | null;
  version: V;
  note: DraftNote | null;
}

type FieldControl = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

/** 세션이 바뀌면 비우는 새 `Map`. */
export function sessionDrafts<V = string | number | null>(): Map<string, Draft<V>> {
  const map = new Map<string, Draft<V>>();
  let session = "";
  subscribe((next) => {
    const id = `${next.status}:${next.me ? next.me.userId : ""}`;
    if (id === session) return;
    session = id;
    map.clear();
  });
  return map;
}

/** `root` 안 켜진 `[data-field]` 칸의 값 `{ name: value }`. 체크박스는 `checked`다. 꺼진 칸은 사용자가 고칠 수 없어 뺀다. */
export function readFields(root: ParentNode): FieldValues {
  const values: FieldValues = {};
  for (const node of root.querySelectorAll<FieldControl>("[data-field]")) {
    if (node.disabled) continue;
    values[node.dataset.field || ""] = node instanceof HTMLInputElement && node.type === "checkbox" ? node.checked : node.value;
  }
  return values;
}

/** `values`로 `root`의 켜진 `[data-field]` 칸을 채운다. select에 그 값의 option이 없으면 그대로 둔다. */
export function fillFields(root: ParentNode, values: FieldValues | null | undefined): void {
  if (!values) return;
  for (const node of root.querySelectorAll<FieldControl>("[data-field]")) {
    const name = node.dataset.field || "";
    if (node.disabled || !Object.hasOwn(values, name)) continue;
    const value = values[name];
    if (node instanceof HTMLInputElement && node.type === "checkbox") node.checked = Boolean(value);
    else if (node instanceof HTMLSelectElement) {
      if ([...node.options].some((option) => option.value === value)) node.value = String(value);
    } else node.value = String(value);
  }
}

/** 지금 `form`의 칸 값을 `drafts`의 `key` 초안에 담는다. 초안이 없으면 `version`을 기준 버전으로 새로 만든다. */
export function keepDraft<V>(drafts: Map<string, Draft<V>>, key: string, form: ParentNode, version: V): Draft<V> {
  const draft: Draft<V> = drafts.get(key) || { values: null, version, note: null };
  draft.values = readFields(form);
  drafts.set(key, draft);
  return draft;
}

/** 새로 그린 `form`을 `key` 초안의 값으로 채우고, 칸이 바뀔 때마다 초안에 담는다. 초안(없으면 null)을 돌려준다. */
export function bindDraft<V>(drafts: Map<string, Draft<V>>, key: string, form: HTMLElement, version: V): Draft<V> | null {
  const draft = drafts.get(key) || null;
  if (draft) fillFields(form, draft.values);
  const store = () => keepDraft(drafts, key, form, version);
  form.addEventListener("input", store);
  form.addEventListener("change", store);
  return draft;
}
