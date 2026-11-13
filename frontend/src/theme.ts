import { getState, render, save } from "./store.ts";

export function systemTheme(): "light" | "dark" {
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

/** `<html data-theme data-theme-mode>` from the state; App applies it after every render. */
export function applyTheme() {
  const state = getState();
  document.documentElement.setAttribute("data-theme", state.theme || "dark");
  document.documentElement.setAttribute("data-theme-mode", state.themeMode || "system");
}

/** Follows the device setting while the theme mode is "system". */
export function watchSystemTheme() {
  window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => {
    const state = getState();
    if (state.themeMode !== "system") return;
    state.theme = systemTheme();
    save();
    render();
  });
}
