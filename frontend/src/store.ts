import { useSyncExternalStore } from "react";
import { seedState, activities, type AppState } from "./data.ts";
import { onSignOut } from "./shared/backend.ts";

const KEY = "astra-state";
let state = load();
syncActivities(state);
const listeners = new Set<() => void>();
let version = 0;

function isJunkTitle(title: unknown) {
  return String(title || "").trim().length < 2;
}

/** 저장된 교수 작성 활동을 샘플 활동 목록(`data.ts`의 `activities`)에 맞춘다. 지운 활동과 제목이 없는 활동은 뺀다. */
export function syncActivities(state: AppState) {
  const deleted = new Set(state.deletedActivityIds || []);
  const authored = [];
  for (const item of state.authoredActivities || []) {
    if (!item || !item.id || deleted.has(item.id) || isJunkTitle(item.title)) continue;
    authored.push({ ...item, authored: true });
  }
  state.authoredActivities = authored.map((item) => ({ ...item }));
  const override = new Set(authored.map((item) => item.id));
  for (let index = activities.length - 1; index >= 0; index -= 1) {
    const item = activities[index];
    if (item.authored || deleted.has(item.id) || override.has(item.id) || isJunkTitle(item.title)) activities.splice(index, 1);
  }
  const seen = new Set(activities.map((item) => item.id));
  for (const item of authored) {
    if (seen.has(item.id)) continue;
    activities.push(item);
    seen.add(item.id);
  }
}

function resolvedTheme(): AppState["theme"] {
  const attr = document.documentElement.getAttribute("data-theme");
  return attr === "light" || attr === "dark" ? attr : "dark";
}

function load(): AppState {
  const seed = seedState();
  try {
    const mode = localStorage.getItem("astra-theme");
    if (mode === "dark" || mode === "light") {
      seed.theme = mode;
      seed.themeMode = mode;
    } else {
      seed.themeMode = "system";
      seed.theme = resolvedTheme();
    }
    const raw = localStorage.getItem(KEY);
    if (!raw) return seed;
    const saved = JSON.parse(raw) as Partial<AppState>;
    return { ...seed, ...saved, toast: "", theme: seed.theme, themeMode: seed.themeMode };
  } catch {
    return seed;
  }
}

/** The one mutable app state. Mutate it, then `render()` (or `update()`), and every subscribed view redraws. */
export function getState(): AppState {
  return state;
}

export function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...state, toast: "" }));
    if (state.themeMode === "light" || state.themeMode === "dark") localStorage.setItem("astra-theme", state.themeMode);
    else localStorage.removeItem("astra-theme");
  } catch {
    /* private mode or full storage: keep the in-memory session */
  }
}

/** Redraws every view of the state (React components using `useAppState`). */
export function render() {
  version += 1;
  listeners.forEach((fn) => fn());
}

export function update(mutator: (state: AppState) => void) {
  mutator(state);
  save();
  render();
}

// Server screens save what the signed-in user typed with the app state: search words (`queries`), list filters and
// unsaved input (`draft`) and form errors. They go with that user (logout, expired session, another user). The
// sample-only parts (home command bar, sample submissions) stay.
onSignOut(() => update((current) => {
  const seed = seedState();
  current.queries = seed.queries;
  current.draft = seed.draft;
  current.formError = seed.formError;
}));

export function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

const getVersion = () => version;

/** The app state; the calling component re-renders on every `render()`/`update()`. */
export function useAppState(): AppState {
  useSyncExternalStore(subscribe, getVersion);
  return state;
}

/** Shows `message` in the toast (cleared by the toast after a few seconds). */
export function toast(message: string) {
  state.toast = message;
  render();
}

export function resetState() {
  state = seedState();
  const theme = document.documentElement.getAttribute("data-theme");
  if (theme === "dark" || theme === "light") state.theme = theme;
  save();
  render();
}
