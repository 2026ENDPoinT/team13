// Sample forms are uncontrolled inputs named with `data-name`; an action reads them all at submit.
import { getState, render } from "./store.ts";

export type FormValues = Record<string, string | boolean | string[]>;

/**
 * Values of every `[data-name]` control on the page, and clears the previous validation errors.
 * A checkbox with a `value` collects into an array of checked values; a lone checkbox is a boolean;
 * a file input gives the chosen (or dropped, `state.draft.fileName`) file name.
 */
export function readForm(): FormValues {
  const state = getState();
  state.formError = {};
  const data: FormValues = {};
  document.querySelectorAll<HTMLInputElement>("[data-name]").forEach((node) => {
    const key = node.dataset.name!;
    if (node.type === "checkbox") {
      if (node.value && node.value !== "on") {
        const list = Array.isArray(data[key]) ? data[key] : (data[key] = []);
        if (node.checked) list.push(node.value);
      } else data[key] = node.checked;
    } else if (node.type === "file") {
      data[key] = node.files?.[0]?.name || state.draft.fileName || "";
    } else data[key] = node.value;
  });
  return data;
}

/** Shows `errors` under their fields (`<Field name>`) and the first one in the toast. */
export function showErrors(errors: Record<string, string>) {
  const state = getState();
  state.formError = errors;
  state.toast = Object.values(errors)[0] || "";
  render();
}

/** "YYYY-MM-DD HH:MM" in local time, for sample records. */
export function stamp() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
