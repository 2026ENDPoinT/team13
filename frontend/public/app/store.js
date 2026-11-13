import { seedState, activities } from "./data.js";

const KEY = "astra-state";
let state = load();
syncActivities(state);
const listeners = new Set();

function isJunkTitle(title) {
  return String(title || "").trim().length < 2;
}

/** 저장된 교수 작성 활동을 샘플 활동 목록(`data.js`의 `activities`)에 맞춘다. 지운 활동과 제목이 없는 활동은 뺀다. */
export function syncActivities(state) {
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

function resolvedTheme() {
  try {
    const attr = document.documentElement.getAttribute("data-theme");
    if (attr === "light" || attr === "dark") return attr;
  } catch { /* node smoke has no document yet */ }
  return "dark";
}

function load() {
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
    const saved = JSON.parse(raw);
    return { ...seed, ...saved, toast: "", theme: seed.theme, themeMode: seed.themeMode };
  } catch {
    return seed;
  }
}

export function getState() {
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

export function update(mutator) {
  mutator(state);
  save();
  listeners.forEach((fn) => fn());
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function resetState() {
  state = seedState();
  try {
    const theme = document.documentElement.getAttribute("data-theme");
    if (theme === "dark" || theme === "light") state.theme = theme;
  } catch { /* ignore */ }
  save();
  listeners.forEach((fn) => fn());
}
