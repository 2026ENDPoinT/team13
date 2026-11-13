// Player chrome shared by the sample player (learning.tsx) and the study layout (video-study.tsx):
// the play/pause and fullscreen glyphs, the play button's fade, and fullscreen of a `.player-page`.
import { createElement, useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactElement, type RefObject } from "react";

const HOLD_MS = 320;

const PATHS = {
  play: "M8.2 5.1c-.8-.48-1.7.1-1.7 1v11.8c0 .9.9 1.48 1.7 1l9.7-5.9c.74-.45.74-1.55 0-2L8.2 5.1Z",
  pause: "M6.4 4.6h3.1c.5 0 .9.4.9.9v13c0 .5-.4.9-.9.9H6.4c-.5 0-.9-.4-.9-.9v-13c0-.5.4-.9.9-.9Zm8.1 0h3.1c.5 0 .9.4.9.9v13c0 .5-.4.9-.9.9h-3.1c-.5 0-.9-.4-.9-.9v-13c0-.5.4-.9.9-.9Z",
  enter: "M9 4H4v5M15 4h5v5M9 20H4v-5M15 20h5v-5",
  exit: "M9 9H4M9 9V4M15 9h5M15 9V4M9 15H4M9 15v5M15 15h5M15 15v5",
};

export type PlayerGlyph = keyof typeof PATHS;

/** The player's SVG glyph: filled play/pause (28px), stroked fullscreen enter/exit (18px). */
export function PlayerIcon({ name }: { name: PlayerGlyph }): ReactElement {
  const size = name === "enter" || name === "exit" ? "18" : "28";
  const path = name === "play" || name === "pause"
    ? createElement("path", { d: PATHS[name], fill: "currentColor" })
    : createElement("path", { d: PATHS[name], fill: "none", stroke: "currentColor", strokeWidth: "1.8", strokeLinecap: "round", strokeLinejoin: "round" });
  return createElement("svg", {
    viewBox: "0 0 24 24",
    width: size,
    height: size,
    "aria-hidden": "true",
    focusable: "false",
    className: name === "play" ? "glyph-play" : undefined,
  }, path);
}

export interface PlayControlFade {
  /** `is-instant`/`is-dim` classes (with a leading space) for the play button. */
  classes: string;
  /** Shows the button at once; with `fade`, dims it again after a short hold. */
  present: (fade: boolean) => void;
}

/** The play button's show-then-fade: undimmed without a transition, dimmed with one. */
export function usePlayControlFade(button: RefObject<HTMLButtonElement | null>): PlayControlFade {
  const [dim, setDim] = useState(false);
  const [instant, setInstant] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  useLayoutEffect(() => {
    if (!instant) return;
    // Commit the undimmed style without a transition before `is-instant` goes away.
    void button.current?.offsetWidth;
    setInstant(false);
  }, [instant, button]);
  useEffect(() => () => clearTimeout(timer.current), []);
  const present = useCallback((fade: boolean) => {
    clearTimeout(timer.current);
    setInstant(true);
    setDim(false);
    if (!fade) return;
    const reduced = Boolean(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
    timer.current = window.setTimeout(() => setDim(true), reduced ? 0 : HOLD_MS);
  }, []);
  return { classes: `${instant ? " is-instant" : ""}${dim ? " is-dim" : ""}`, present };
}

interface WebkitDocument {
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => void;
}

interface WebkitElement {
  webkitRequestFullscreen?: () => void;
}

interface WebkitVideo {
  webkitDisplayingFullscreen?: boolean;
  webkitEnterFullscreen?: () => void;
  webkitExitFullscreen?: () => void;
}

// Safari's prefixed fullscreen API is not in the DOM typings.
const webkitDocument: Document & WebkitDocument = document;

function fullscreenElement(): Element | null {
  return document.fullscreenElement || webkitDocument.webkitFullscreenElement || null;
}

function videoOf(root: HTMLElement | null): (HTMLVideoElement & WebkitVideo) | null {
  return root ? root.querySelector("video") : null;
}

export function isPlayerFullscreen(root: HTMLElement | null): boolean {
  if (!root) return false;
  const current = fullscreenElement();
  if (current && (current === root || root.contains(current))) return true;
  const video = videoOf(root);
  return Boolean(video && video.webkitDisplayingFullscreen);
}

async function exitPlayerFullscreen(root: HTMLElement | null): Promise<void> {
  const video = videoOf(root);
  if (fullscreenElement()) {
    if (typeof document.exitFullscreen === "function") {
      await document.exitFullscreen();
      return;
    }
    if (typeof webkitDocument.webkitExitFullscreen === "function") {
      webkitDocument.webkitExitFullscreen();
      return;
    }
  }
  if (video && video.webkitDisplayingFullscreen && typeof video.webkitExitFullscreen === "function") {
    video.webkitExitFullscreen();
  }
}

export interface FullscreenResult {
  ok: boolean;
  exited?: boolean;
  reason?: string;
}

function reasonOf(error: unknown): string {
  return error instanceof Error && error.message ? error.message : "failed";
}

/** Enters or leaves fullscreen for a `.player-page` (the element, else iOS's native video fullscreen). */
export async function togglePlayerFullscreen(root: HTMLElement | null): Promise<FullscreenResult> {
  if (!root) return { ok: false, reason: "missing" };
  const video = videoOf(root);
  try {
    if (isPlayerFullscreen(root)) {
      await exitPlayerFullscreen(root);
      return { ok: true, exited: true };
    }
    const legacyRoot: HTMLElement & WebkitElement = root;
    if (typeof root.requestFullscreen === "function") {
      try {
        await root.requestFullscreen();
        return { ok: true };
      } catch (error) {
        if (!(video && typeof video.webkitEnterFullscreen === "function")) return { ok: false, reason: reasonOf(error) };
      }
    } else if (typeof legacyRoot.webkitRequestFullscreen === "function") {
      legacyRoot.webkitRequestFullscreen();
      return { ok: true };
    }
    if (video && typeof video.webkitEnterFullscreen === "function") {
      video.webkitEnterFullscreen();
      return { ok: true };
    }
    return { ok: false, reason: "unsupported" };
  } catch (error) {
    return { ok: false, reason: reasonOf(error) };
  }
}

/** Whether the `.player-page` behind `root` is fullscreen; re-renders the caller when that changes. */
export function usePlayerFullscreen(root: RefObject<HTMLElement | null>): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    const sync = () => setOn(isPlayerFullscreen(root.current));
    const video = videoOf(root.current);
    document.addEventListener("fullscreenchange", sync);
    document.addEventListener("webkitfullscreenchange", sync);
    video?.addEventListener("webkitbeginfullscreen", sync);
    video?.addEventListener("webkitendfullscreen", sync);
    sync();
    return () => {
      document.removeEventListener("fullscreenchange", sync);
      document.removeEventListener("webkitfullscreenchange", sync);
      video?.removeEventListener("webkitbeginfullscreen", sync);
      video?.removeEventListener("webkitendfullscreen", sync);
    };
  }, [root]);
  return on;
}

let hooked = false;

/** Escape leaves a fullscreen player (once per page). */
export function ensurePlayerChrome(): void {
  if (hooked || typeof document === "undefined") return;
  hooked = true;
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const page = [...document.querySelectorAll<HTMLElement>(".player-page")].find((node) => isPlayerFullscreen(node));
    if (!page) return;
    void togglePlayerFullscreen(page);
  });
}
