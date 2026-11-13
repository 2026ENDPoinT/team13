// Reusable page viewer for PDF 학습실, the video-study PDF tab, and lecture previews.
// A future 자료 activity can mount createDocumentViewer or createCoursePdfViewer.
import { el, icon } from "../dom.js";
import { getCoursePdfBlob } from "./course-pdfs.js";

const STORAGE_KEY = "astra-doc-page";
const VENDOR = new URL("../vendor/pdfjs/", import.meta.url);

function readPages() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function storedPage(documentId) {
  const value = Number(readPages()[documentId]);
  return Number.isInteger(value) && value > 0 ? value : 0;
}

function writePage(documentId, page) {
  try {
    const pages = readPages();
    pages[documentId] = page;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pages));
  } catch {
    /* private mode or full storage still keeps the in-memory page */
  }
}

function iconButton(name, iconName, className) {
  return el("button", { type: "button", class: `icon-btn ${className}` },
    icon(iconName),
    el("span", { class: "doc-viewer__sr", text: name }));
}

function typingTarget(node) {
  return Boolean(node && node.closest && node.closest("input, textarea, select, [contenteditable='true']"));
}

// Node render smoke has no observer constructors. The browser path uses the real ones.
function watch(Ctor, callback, options) {
  if (typeof Ctor !== "function") return { observe() {}, disconnect() {} };
  try {
    return new Ctor(callback, options);
  } catch {
    return { observe() {}, disconnect() {} };
  }
}

function later(callback) {
  if (typeof requestAnimationFrame === "function") requestAnimationFrame(callback);
  else callback();
}

/**
 * Scrollable document viewer.
 * renderPage(pageNumber, host, abortSignal) fills one sheet. Pages outside
 * the scrollport are left as placeholders so a long PDF stays cheap.
 */
export function createDocumentViewer({
  documentId,
  pageCount,
  label = "문서",
  renderPage,
  initialPage = 1,
  onPageChange,
  onDisconnect,
  extras = [],
  compact = false,
}) {
  const total = Math.max(0, Math.floor(Number(pageCount) || 0));
  if (!documentId || total < 1 || typeof renderPage !== "function") {
    return el("p", { class: "doc-viewer__pending", text: "표시할 페이지가 없습니다." });
  }

  const root = el("section", {
    class: `doc-viewer${compact ? " is-compact" : ""}`,
    tabindex: "0",
    "aria-label": `${label} 문서 뷰어`,
    "data-document-id": documentId,
  });
  const prev = iconButton("이전", "chevL", "doc-viewer__nav");
  prev.dataset.dir = "-1";
  const next = iconButton("다음", "chev", "doc-viewer__nav");
  next.dataset.dir = "1";
  const indicator = el("span", { class: "doc-viewer__indicator", role: "status", "aria-live": "polite" });
  const jump = el("input", {
    class: "doc-viewer__jump",
    type: "number",
    inputmode: "numeric",
    min: "1",
    max: String(total),
    step: "1",
    "aria-label": "페이지로 이동",
  });
  const mode = iconButton("한 쪽씩 보기", "layers", "doc-viewer__mode");
  mode.setAttribute("aria-pressed", "false");
  const stage = el("div", { class: "doc-viewer__stage", tabindex: "-1" });
  const slots = [];
  for (let number = 1; number <= total; number += 1) {
    const sheet = el("div", { class: "doc-viewer__sheet" },
      el("p", { class: "doc-viewer__pending", text: `${number}쪽을 불러오는 중입니다` }));
    const article = el("article", {
      class: "doc-viewer__page",
      "data-page": String(number),
      "aria-label": `${number}쪽`,
    }, sheet);
    slots.push(article);
    stage.append(article);
  }
  const bar = el("div", { class: "doc-viewer__bar viewer-bar" },
    el("span", { class: "doc-viewer__title", text: label }),
    prev,
    indicator,
    jump,
    next,
    mode,
  );
  if (extras.length) bar.append(el("div", { class: "viewer-actions doc-viewer__extras" }, ...extras));
  root.append(bar, stage);

  const aborts = new WeakMap();
  let current = 0;
  let following = false;
  let lock = false;
  let settle = true;
  let slide = false;
  let wheelAt = 0;
  let touchStart = null;

  function clamp(page) {
    return Math.min(total, Math.max(1, Math.round(Number(page) || 1)));
  }

  function align(article) {
    if (!article || slide || !article.isConnected) return;
    const delta = article.getBoundingClientRect().top - stage.getBoundingClientRect().top;
    if (Math.abs(delta) <= 1) return;
    lock = true;
    stage.scrollTop += delta;
    lock = false;
  }

  async function paint(article) {
    if (!article || article.dataset.state === "ready" || article.dataset.state === "loading") return;
    const controller = new AbortController();
    aborts.set(article, controller);
    article.dataset.state = "loading";
    const sheet = article.querySelector(".doc-viewer__sheet");
    try {
      await renderPage(Number(article.dataset.page), sheet, controller.signal);
      if (controller.signal.aborted || !article.isConnected) {
        if (article.dataset.state === "loading") delete article.dataset.state;
        return;
      }
      article.dataset.state = "ready";
    } catch {
      if (controller.signal.aborted || !article.isConnected) {
        if (article.dataset.state === "loading") delete article.dataset.state;
        return;
      }
      article.dataset.state = "error";
      sheet.replaceChildren(el("p", { class: "doc-viewer__pending", text: "이 쪽을 표시하지 못했습니다." }));
    }
  }

  function release(article) {
    const distance = Math.abs(Number(article.dataset.page) - current);
    if (distance <= 1 || slide) return;
    if (article.dataset.state !== "ready" && article.dataset.state !== "loading") return;
    aborts.get(article)?.abort();
    const height = article.offsetHeight;
    if (height > 40) article.style.minHeight = `${height}px`;
    article.querySelector(".doc-viewer__sheet")?.replaceChildren(
      el("p", { class: "doc-viewer__pending", text: `${article.dataset.page}쪽` }),
    );
    delete article.dataset.state;
  }

  function applyChrome(page) {
    const changed = page !== current;
    current = page;
    indicator.textContent = `${current} / ${total}`;
    if (document.activeElement !== jump) jump.value = String(current);
    prev.disabled = current <= 1;
    next.disabled = current >= total;
    for (const article of slots) {
      const on = Number(article.dataset.page) === current;
      article.classList.toggle("is-current", on);
      if (on) article.setAttribute("aria-current", "true");
      else if (typeof article.removeAttribute === "function") article.removeAttribute("aria-current");
    }
    if (!changed) return;
    writePage(documentId, current);
    onPageChange?.(current);
    paint(slots[current - 1]);
  }

  function show(page) {
    following = true;
    applyChrome(clamp(page));
    if (!slide) align(slots[current - 1]);
  }

  function pageAtCenter() {
    const box = stage.getBoundingClientRect();
    if (box.height < 8) return 0;
    let best = 0;
    let bestArea = 0;
    for (const article of slots) {
      const rect = article.getBoundingClientRect();
      const height = Math.min(rect.bottom, box.bottom) - Math.max(rect.top, box.top);
      const width = Math.min(rect.right, box.right) - Math.max(rect.left, box.left);
      const area = Math.max(0, height) * Math.max(0, width);
      if (area > bestArea) {
        best = Number(article.dataset.page);
        bestArea = area;
      }
    }
    return best;
  }

  function updateFromScroll() {
    if (slide || settle) return;
    const page = pageAtCenter();
    if (page) applyChrome(page);
  }

  prev.addEventListener("click", () => show(current - 1));
  next.addEventListener("click", () => show(current + 1));
  mode.addEventListener("click", () => {
    slide = !slide;
    root.classList.toggle("is-slide", slide);
    mode.setAttribute("aria-pressed", slide ? "true" : "false");
    mode.querySelector(".doc-viewer__sr").textContent = slide ? "이어 스크롤" : "한 쪽씩 보기";
    paint(slots[current - 1]);
    if (!slide) align(slots[current - 1]);
  });

  function commitJump() {
    const page = Number(jump.value);
    if (!Number.isFinite(page)) {
      jump.value = String(current);
      return;
    }
    show(page);
    jump.value = String(current);
  }
  jump.addEventListener("change", commitJump);
  jump.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    commitJump();
  });
  jump.addEventListener("wheel", (event) => event.preventDefault(), { passive: false });

  root.addEventListener("keydown", (event) => {
    if (event.altKey || event.metaKey || event.ctrlKey || typingTarget(event.target)) return;
    if (event.key === " " && event.target.closest("button, a")) return;
    const step = { ArrowLeft: -1, ArrowRight: 1, PageUp: -1, PageDown: 1, Home: "first", End: "last", " ": 1 }[event.key];
    if (step == null) return;
    event.preventDefault();
    if (step === "first") show(1);
    else if (step === "last") show(total);
    else show(current + step);
  });

  stage.addEventListener("mousedown", (event) => {
    if (event.target.closest("a, button, input, textarea, select")) return;
    root.focus({ preventScroll: true });
  });
  stage.addEventListener("scroll", () => {
    if (lock || settle) return;
    following = false;
    updateFromScroll();
  }, { passive: true });
  stage.addEventListener("wheel", (event) => {
    following = false;
    if (!slide) return;
    event.preventDefault();
    const now = Date.now();
    if (now - wheelAt < 280) return;
    if (Math.abs(event.deltaY) < 4 && Math.abs(event.deltaX) < 4) return;
    wheelAt = now;
    const forward = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX > 0 : event.deltaY > 0;
    show(current + (forward ? 1 : -1));
  }, { passive: false });
  stage.addEventListener("touchstart", (event) => {
    following = false;
    if (event.touches.length !== 1) {
      touchStart = null;
      return;
    }
    touchStart = { x: event.touches[0].clientX, y: event.touches[0].clientY };
  }, { passive: true });
  stage.addEventListener("touchend", (event) => {
    if (!touchStart) return;
    const point = event.changedTouches[0];
    const dx = point.clientX - touchStart.x;
    const dy = point.clientY - touchStart.y;
    touchStart = null;
    if (slide) {
      if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy)) return;
      show(current + (dx < 0 ? 1 : -1));
      return;
    }
    if (Math.abs(dx) < 56 || Math.abs(dx) < Math.abs(dy)) return;
    show(current + (dx < 0 ? 1 : -1));
  }, { passive: true });

  const seen = watch(globalThis.IntersectionObserver, (entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) paint(entry.target);
      else release(entry.target);
    }
    if (!lock) updateFromScroll();
  }, { root: stage, rootMargin: "800px 0px", threshold: [0, 0.25, 0.5, 0.75, 1] });
  for (const article of slots) seen.observe(article);

  const heights = new Map();
  const resized = watch(globalThis.ResizeObserver, () => {
    let shift = 0;
    for (const article of slots) {
      const nextHeight = article.offsetHeight;
      const previous = heights.has(article) ? heights.get(article) : nextHeight;
      heights.set(article, nextHeight);
      if (Number(article.dataset.page) < current) shift += nextHeight - previous;
    }
    if (shift && !slide) {
      lock = true;
      stage.scrollTop += shift;
      lock = false;
    }
    if (following) align(slots[current - 1]);
  });
  for (const article of slots) resized.observe(article);

  show(storedPage(documentId) || initialPage || 1);
  let frames = 0;
  function finishLayout() {
    later(() => {
      frames += 1;
      if (!root.isConnected) {
        if (frames < 8) finishLayout();
        else settle = false;
        return;
      }
      align(slots[current - 1]);
      const article = slots[current - 1];
      const delta = article
        ? Math.abs(article.getBoundingClientRect().top - stage.getBoundingClientRect().top)
        : 0;
      if (delta > 2 && frames < 12) {
        finishLayout();
        return;
      }
      settle = false;
    });
  }
  finishLayout();

  const watcher = watch(globalThis.MutationObserver, () => {
    if (root.isConnected) return;
    watcher.disconnect();
    seen.disconnect();
    resized.disconnect();
    for (const article of slots) aborts.get(article)?.abort();
    onDisconnect?.();
  });
  queueMicrotask(() => {
    if (!root.isConnected) return;
    watcher.observe(document.body, { childList: true, subtree: true });
  });
  return root;
}

async function openPdf(id) {
  const blob = await getCoursePdfBlob(id);
  if (!blob) return null;
  const library = await import(new URL("pdf.min.mjs", VENDOR).href);
  const data = new Uint8Array(await blob.arrayBuffer());
  const port = new Worker(new URL("pdf.worker.min.mjs", VENDOR), { type: "module" });
  const onWorkerError = (event) => event.preventDefault();
  port.addEventListener("error", onWorkerError);
  port.addEventListener("messageerror", onWorkerError);
  const worker = new library.PDFWorker({ port });
  const task = library.getDocument({
    data,
    worker,
    cMapUrl: new URL("cmaps/", VENDOR).href,
    cMapPacked: true,
    standardFontDataUrl: new URL("standard_fonts/", VENDOR).href,
    useWorkerFetch: true,
    useWasm: false,
    isEvalSupported: false,
    stopAtErrors: false,
    disableFontFace: true,
    isOffscreenCanvasSupported: false,
    isImageDecoderSupported: false,
  });
  const pdf = await task.promise;
  return {
    pdf,
    async destroy() {
      port.removeEventListener("error", onWorkerError);
      port.removeEventListener("messageerror", onWorkerError);
      await task.destroy().catch(() => {});
      worker.destroy();
      port.terminate();
    },
  };
}

/** Page viewer for one stored course PDF. Text stays available when the canvas cannot be drawn. */
export function createCoursePdfViewer(record, options = {}) {
  let opened = null;
  function session() {
    opened ??= openPdf(record.id).catch((error) => {
      opened = null;
      throw error;
    });
    return opened;
  }
  const pages = Array.isArray(record.pages) ? record.pages : [];
  return createDocumentViewer({
    documentId: `course-pdf:${record.id}`,
    pageCount: record.pageCount || pages.length,
    label: options.label || record.title || record.fileName || "PDF",
    compact: Boolean(options.compact),
    onDisconnect() {
      opened?.then((doc) => doc?.destroy()).catch(() => {});
    },
    async renderPage(number, host, signal) {
      const source = pages.find((item) => item.number === number);
      const text = source && source.text.trim()
        ? source.text
        : "이 페이지에서 텍스트를 추출하지 못했습니다. 원본 PDF에서 확인해 주세요.";
      const slot = el("div", { class: "doc-viewer__canvas", "aria-hidden": "true" });
      const copy = el("p", { class: "doc-viewer__text", text });
      host.replaceChildren(slot, copy);
      try {
        const doc = await session();
        if (signal.aborted || !doc || !host.isConnected) return;
        const pdfPage = await doc.pdf.getPage(number);
        if (signal.aborted || !host.isConnected) {
          pdfPage.cleanup();
          return;
        }
        const width = Math.max(1, slot.clientWidth || host.clientWidth || 320);
        const base = pdfPage.getViewport({ scale: 1 });
        const viewport = pdfPage.getViewport({ scale: width / base.width });
        const canvas = document.createElement("canvas");
        const ratio = Math.min(2, window.devicePixelRatio || 1);
        canvas.width = Math.floor(viewport.width * ratio);
        canvas.height = Math.floor(viewport.height * ratio);
        const context = canvas.getContext("2d");
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        slot.replaceChildren(canvas);
        const task = pdfPage.render({ canvasContext: context, canvas, viewport });
        const cancel = () => task.cancel();
        if (signal.aborted) cancel();
        else signal.addEventListener("abort", cancel, { once: true });
        try {
          await task.promise;
        } finally {
          signal.removeEventListener("abort", cancel);
          pdfPage.cleanup();
        }
        if (!signal.aborted && slot.querySelector("canvas")) copy.classList.add("doc-viewer__sr");
      } catch (error) {
        if (signal.aborted || error?.name === "RenderingCancelledException") return;
        slot.remove();
      }
    },
  });
}
