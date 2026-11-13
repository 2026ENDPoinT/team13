const HOLD_MS = 320;
const fadeTimers = new WeakMap();

const PATHS = {
  play: "M8.2 5.1c-.8-.48-1.7.1-1.7 1v11.8c0 .9.9 1.48 1.7 1l9.7-5.9c.74-.45.74-1.55 0-2L8.2 5.1Z",
  pause: "M6.4 4.6h3.1c.5 0 .9.4.9.9v13c0 .5-.4.9-.9.9H6.4c-.5 0-.9-.4-.9-.9v-13c0-.5.4-.9.9-.9Zm8.1 0h3.1c.5 0 .9.4.9.9v13c0 .5-.4.9-.9.9h-3.1c-.5 0-.9-.4-.9-.9v-13c0-.5.4-.9.9-.9Z",
  enter: "M9 4H4v5M15 4h5v5M9 20H4v-5M15 20h5v-5",
  exit: "M9 9H4M9 9V4M15 9h5M15 9V4M9 15H4M9 15v5M15 15h5M15 15v5",
};

function svgNode(tag) {
  if (typeof document.createElementNS === "function") return document.createElementNS("http://www.w3.org/2000/svg", tag);
  return document.createElement(tag);
}

export function svgIcon(name) {
  const svg = svgNode("svg");
  const size = name === "enter" || name === "exit" ? "18" : "28";
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", size);
  svg.setAttribute("height", size);
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  if (name === "play") svg.setAttribute("class", "glyph-play");
  const path = svgNode("path");
  path.setAttribute("d", PATHS[name] || PATHS.play);
  if (name === "play" || name === "pause") {
    path.setAttribute("fill", "currentColor");
  } else {
    path.setAttribute("fill", "none");
    path.setAttribute("stroke", "currentColor");
    path.setAttribute("stroke-width", "1.8");
    path.setAttribute("stroke-linecap", "round");
    path.setAttribute("stroke-linejoin", "round");
  }
  svg.append(path);
  return svg;
}

function prefersReducedMotion() {
  return Boolean(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
}

export function presentPlayControl(button, { fade }) {
  if (!button) return;
  const pending = fadeTimers.get(button);
  if (pending) clearTimeout(pending);
  fadeTimers.delete(button);
  button.classList.add("is-instant");
  button.classList.remove("is-dim");
  void button.offsetWidth;
  button.classList.remove("is-instant");
  if (!fade) return;
  fadeTimers.set(button, setTimeout(() => {
    fadeTimers.delete(button);
    button.classList.add("is-dim");
  }, prefersReducedMotion() ? 0 : HOLD_MS));
}

export function paintPlayControl(button, playing, { reveal = false } = {}) {
  if (!button) return;
  const glyph = playing ? "pause" : "play";
  button.setAttribute("aria-label", playing ? "일시정지" : "재생");
  button.setAttribute("aria-pressed", playing ? "true" : "false");
  if (button.dataset.glyph !== glyph) {
    button.dataset.glyph = glyph;
    button.replaceChildren(svgIcon(glyph));
  }
  if (reveal) presentPlayControl(button, { fade: playing });
}

function fullscreenElement() {
  return document.fullscreenElement || document.webkitFullscreenElement || null;
}

export function isPlayerFullscreen(root) {
  if (!root) return false;
  const current = fullscreenElement();
  if (current && (current === root || root.contains(current))) return true;
  const video = root.querySelector("video");
  return Boolean(video && video.webkitDisplayingFullscreen);
}

async function exitPlayerFullscreen(root) {
  const video = root ? root.querySelector("video") : null;
  if (fullscreenElement()) {
    if (typeof document.exitFullscreen === "function") {
      const pending = document.exitFullscreen();
      if (pending && typeof pending.then === "function") await pending;
      return;
    }
    if (typeof document.webkitExitFullscreen === "function") {
      document.webkitExitFullscreen();
      return;
    }
  }
  if (video && video.webkitDisplayingFullscreen && typeof video.webkitExitFullscreen === "function") {
    video.webkitExitFullscreen();
  }
}

export function syncFullscreenButton(root) {
  if (!root) return;
  const button = root.querySelector("[data-act=fullscreen]");
  if (!button) return;
  const on = isPlayerFullscreen(root);
  const glyph = on ? "exit" : "enter";
  button.setAttribute("aria-label", on ? "전체화면 종료" : "전체화면");
  button.setAttribute("aria-pressed", on ? "true" : "false");
  if (button.dataset.glyph !== glyph) {
    button.dataset.glyph = glyph;
    button.replaceChildren(svgIcon(glyph));
  }
}

function syncAllFullscreenButtons() {
  document.querySelectorAll(".player-page").forEach((root) => syncFullscreenButton(root));
}

export async function togglePlayerFullscreen(root) {
  if (!root) return { ok: false, reason: "missing" };
  const video = root.querySelector("video");
  try {
    if (isPlayerFullscreen(root)) {
      await exitPlayerFullscreen(root);
      syncFullscreenButton(root);
      return { ok: true, exited: true };
    }
    if (typeof root.requestFullscreen === "function") {
      try {
        const pending = root.requestFullscreen();
        if (pending && typeof pending.then === "function") await pending;
        syncFullscreenButton(root);
        return { ok: true };
      } catch (error) {
        if (!(video && typeof video.webkitEnterFullscreen === "function")) {
          return { ok: false, reason: error && error.message ? error.message : "failed" };
        }
      }
    } else if (typeof root.webkitRequestFullscreen === "function") {
      root.webkitRequestFullscreen();
      syncFullscreenButton(root);
      return { ok: true };
    }
    if (video && typeof video.webkitEnterFullscreen === "function") {
      video.webkitEnterFullscreen();
      syncFullscreenButton(root);
      return { ok: true };
    }
    return { ok: false, reason: "unsupported" };
  } catch (error) {
    return { ok: false, reason: error && error.message ? error.message : "failed" };
  }
}

let hooked = false;

export function ensurePlayerChrome() {
  if (hooked || typeof document === "undefined") return;
  hooked = true;
  document.addEventListener("fullscreenchange", syncAllFullscreenButtons);
  document.addEventListener("webkitfullscreenchange", syncAllFullscreenButtons);
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const page = [...document.querySelectorAll(".player-page")].find((node) => isPlayerFullscreen(node));
    if (!page) return;
    togglePlayerFullscreen(page);
  });
}
