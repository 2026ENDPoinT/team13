// Reusable page viewer for PDF 학습실, the video-study PDF tab, and lecture previews.
// A future 자료 activity can mount createDocumentViewer or createCoursePdfViewer.
//
// React shape (callers keep the function names):
// - `createDocumentViewer(options)` returns `<DocumentViewer …/>`. `renderPage(page)` returns the
//   content of one sheet as a ReactNode. It is rendered only while the page is near the scrollport
//   and unmounted when it scrolls far away, so per-page work (image loads, canvas drawing) lives in
//   the page's own component and is cancelled in its effect cleanup. Failures are rendered by that
//   component too (`<p className="doc-viewer__pending">…</p>`). `extras` is a ReactNode placed in
//   the viewer bar. Unmounting the viewer replaces the old `onDisconnect` callback.
// - `createCoursePdfViewer(record, options)` returns `<CoursePdfViewer …/>`, which opens the PDF.js
//   document on first use and destroys it (and its module Worker) when it unmounts.
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode, type TouchEvent } from "react";
import { Icon } from "../ui/icon.tsx";
import { getCoursePdfBlob } from "./course-pdfs.ts";
import { onSignOut } from "./backend.ts";

const STORAGE_KEY = "astra-doc-page";
const VENDOR = new URL("/app/vendor/pdfjs/", location.origin);

function readPages(): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    return parsed && typeof parsed === "object" ? { ...parsed } : {};
  } catch {
    return {};
  }
}

function storedPage(documentId: string): number {
  const value = Number(readPages()[documentId]);
  return Number.isInteger(value) && value > 0 ? value : 0;
}

function writePage(documentId: string, page: number) {
  try {
    const pages = readPages();
    pages[documentId] = page;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pages));
  } catch {
    /* private mode or full storage still keeps the in-memory page */
  }
}

// Pages of server documents (`server-file:…`, `course-pdf:srv-…`) belong to the signed-in user: forget them
// when that user goes, so the next user of the tab neither starts on them nor learns what was read.
onSignOut(() => {
  try {
    const pages = readPages();
    for (const id of Object.keys(pages)) if (id.startsWith("server-file:") || id.startsWith("course-pdf:srv-")) delete pages[id];
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pages));
  } catch {
    /* private mode: nothing was stored */
  }
});

function typingTarget(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest("input, textarea, select, [contenteditable='true']"));
}

function later(callback: () => void): () => void {
  if (typeof requestAnimationFrame !== "function") {
    callback();
    return () => {};
  }
  const frame = requestAnimationFrame(callback);
  return () => cancelAnimationFrame(frame);
}

export interface DocumentViewerOptions {
  documentId: string;
  pageCount: number;
  label?: string;
  /** Content of one sheet; rendered only while the page is near the scrollport. */
  renderPage: (page: number) => ReactNode;
  initialPage?: number;
  onPageChange?: (page: number) => void;
  /** Controls shown at the end of the viewer bar. */
  extras?: ReactNode;
  compact?: boolean;
}

/** "live": rendered by `renderPage`; "released": scrolled far away, placeholder kept at its height. */
type SheetState = "live" | "released";

const KEY_STEP: Record<string, number | "first" | "last"> = { ArrowLeft: -1, ArrowRight: 1, PageUp: -1, PageDown: 1, Home: "first", End: "last", " ": 1 };

/**
 * Scrollable document viewer. Pages outside the scrollport are left as placeholders so a long
 * PDF stays cheap.
 */
export function DocumentViewer({
  documentId,
  pageCount,
  label = "문서",
  renderPage,
  initialPage = 1,
  onPageChange,
  extras,
  compact = false,
}: DocumentViewerOptions) {
  const total = Math.max(0, Math.floor(Number(pageCount) || 0));
  const clamp = (page: number) => Math.min(total, Math.max(1, Math.round(Number(page) || 1)));

  const [current, setCurrent] = useState(() => clamp(storedPage(documentId) || initialPage || 1));
  const [slide, setSlide] = useState(false);
  const [sheets, setSheets] = useState<ReadonlyMap<number, SheetState>>(() => new Map());

  const rootRef = useRef<HTMLElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const jumpRef = useRef<HTMLInputElement>(null);
  const articles = useRef<(HTMLElement | null)[]>([]);
  const minHeights = useRef(new Map<number, number>());
  const latest = useRef({ onPageChange, documentId });
  latest.current = { onPageChange, documentId };
  // Scroll bookkeeping read by observers and native listeners (no re-render needed).
  const ctl = useRef({
    current: 0,
    sheets: new Map<number, SheetState>(),
    following: false,
    lock: false,
    settle: true,
    slide: false,
    wheelAt: 0,
    touchStart: null as { x: number; y: number } | null,
  });

  const setSheet = (page: number, state: SheetState) => {
    const c = ctl.current;
    if (c.sheets.get(page) === state) return;
    const next = new Map(c.sheets);
    next.set(page, state);
    c.sheets = next;
    setSheets(next);
  };

  const paint = (page: number) => {
    if (page < 1 || page > total) return;
    if (ctl.current.sheets.get(page) === "live") return;
    setSheet(page, "live");
  };

  const release = (page: number) => {
    const c = ctl.current;
    if (Math.abs(page - c.current) <= 1 || c.slide) return;
    if (c.sheets.get(page) !== "live") return;
    const article = articles.current[page - 1];
    const height = article ? article.offsetHeight : 0;
    if (height > 40) minHeights.current.set(page, height);
    setSheet(page, "released");
  };

  const align = (page: number) => {
    const c = ctl.current;
    const article = articles.current[page - 1];
    const stage = stageRef.current;
    if (!article || !stage || c.slide) return;
    const delta = article.getBoundingClientRect().top - stage.getBoundingClientRect().top;
    if (Math.abs(delta) <= 1) return;
    c.lock = true;
    stage.scrollTop += delta;
    c.lock = false;
  };

  const applyChrome = (page: number) => {
    const c = ctl.current;
    const changed = page !== c.current;
    c.current = page;
    setCurrent(page);
    const jump = jumpRef.current;
    if (jump && document.activeElement !== jump) jump.value = String(page);
    if (!changed) return;
    writePage(latest.current.documentId, page);
    latest.current.onPageChange?.(page);
    paint(page);
  };

  const show = (page: number) => {
    ctl.current.following = true;
    applyChrome(clamp(page));
    if (!ctl.current.slide) align(ctl.current.current);
  };

  const pageAtCenter = () => {
    const stage = stageRef.current;
    if (!stage) return 0;
    const box = stage.getBoundingClientRect();
    if (box.height < 8) return 0;
    let best = 0;
    let bestArea = 0;
    articles.current.forEach((article, index) => {
      if (!article) return;
      const rect = article.getBoundingClientRect();
      const height = Math.min(rect.bottom, box.bottom) - Math.max(rect.top, box.top);
      const width = Math.min(rect.right, box.right) - Math.max(rect.left, box.left);
      const area = Math.max(0, height) * Math.max(0, width);
      if (area > bestArea) {
        best = index + 1;
        bestArea = area;
      }
    });
    return best;
  };

  const updateFromScroll = () => {
    const c = ctl.current;
    if (c.slide || c.settle) return;
    const page = pageAtCenter();
    if (page) applyChrome(page);
  };

  // The handlers above read refs only; the effects keep the latest closures here.
  const live = useRef({ show, paint, release, align, updateFromScroll });
  live.current = { show, paint, release, align, updateFromScroll };

  // Mount: first page, observers, native listeners that must call preventDefault, layout settling.
  useLayoutEffect(() => {
    const c = ctl.current;
    const stage = stageRef.current;
    const jump = jumpRef.current;
    c.current = 0;
    c.settle = true;
    live.current.show(storedPage(documentId) || initialPage || 1);

    const seen = typeof IntersectionObserver === "function"
      ? new IntersectionObserver((entries) => {
        for (const entry of entries) {
          const page = Number((entry.target as HTMLElement).dataset.page); // observed targets are the page articles
          if (entry.isIntersecting) live.current.paint(page);
          else live.current.release(page);
        }
        if (!c.lock) live.current.updateFromScroll();
      }, { root: stage, rootMargin: "800px 0px", threshold: [0, 0.25, 0.5, 0.75, 1] })
      : null;

    const heights = new Map<Element, number>();
    const resized = typeof ResizeObserver === "function"
      ? new ResizeObserver(() => {
        let shift = 0;
        articles.current.forEach((article, index) => {
          if (!article) return;
          const nextHeight = article.offsetHeight;
          const previous = heights.get(article) ?? nextHeight;
          heights.set(article, nextHeight);
          if (index + 1 < c.current) shift += nextHeight - previous;
        });
        if (shift && !c.slide && stage) {
          c.lock = true;
          stage.scrollTop += shift;
          c.lock = false;
        }
        if (c.following) live.current.align(c.current);
      })
      : null;
    for (const article of articles.current) {
      if (!article) continue;
      seen?.observe(article);
      resized?.observe(article);
    }

    const onStageWheel = (event: WheelEvent) => {
      c.following = false;
      if (!c.slide) return;
      event.preventDefault();
      const now = Date.now();
      if (now - c.wheelAt < 280) return;
      if (Math.abs(event.deltaY) < 4 && Math.abs(event.deltaX) < 4) return;
      c.wheelAt = now;
      const forward = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX > 0 : event.deltaY > 0;
      live.current.show(c.current + (forward ? 1 : -1));
    };
    const onJumpWheel = (event: WheelEvent) => event.preventDefault();
    const onJumpChange = () => commitJump();
    stage?.addEventListener("wheel", onStageWheel, { passive: false });
    jump?.addEventListener("wheel", onJumpWheel, { passive: false });
    // The native `change` (spinner, blur), not React's per-keystroke onChange.
    jump?.addEventListener("change", onJumpChange);

    let frames = 0;
    let cancelFrame = () => {};
    const finishLayout = () => {
      cancelFrame = later(() => {
        frames += 1;
        live.current.align(c.current);
        const article = articles.current[c.current - 1];
        const delta = article && stage
          ? Math.abs(article.getBoundingClientRect().top - stage.getBoundingClientRect().top)
          : 0;
        if (delta > 2 && frames < 12) {
          finishLayout();
          return;
        }
        c.settle = false;
      });
    };
    finishLayout();

    return () => {
      cancelFrame();
      seen?.disconnect();
      resized?.disconnect();
      stage?.removeEventListener("wheel", onStageWheel);
      jump?.removeEventListener("wheel", onJumpWheel);
      jump?.removeEventListener("change", onJumpChange);
    };
    // One viewer per document: the caller keys it by `documentId` and page count.
    // Runs once per mount; the latest handlers come through `live`.
  }, []);

  // Back to continuous scrolling: the other pages are laid out again, then the current one is aligned.
  const firstSlide = useRef(true);
  useLayoutEffect(() => {
    if (firstSlide.current) {
      firstSlide.current = false;
      return;
    }
    if (!slide) live.current.align(ctl.current.current);
  }, [slide]);

  function commitJump() {
    const jump = jumpRef.current;
    if (!jump) return;
    const page = Number(jump.value);
    if (!Number.isFinite(page)) {
      jump.value = String(ctl.current.current);
      return;
    }
    live.current.show(page);
    jump.value = String(ctl.current.current);
  }

  const toggleMode = () => {
    const next = !ctl.current.slide;
    ctl.current.slide = next;
    setSlide(next);
    paint(ctl.current.current);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.altKey || event.metaKey || event.ctrlKey || typingTarget(event.target)) return;
    if (event.key === " " && event.target instanceof Element && event.target.closest("button, a")) return;
    const step = KEY_STEP[event.key];
    if (step == null) return;
    event.preventDefault();
    if (step === "first") show(1);
    else if (step === "last") show(total);
    else show(ctl.current.current + step);
  };

  const onMouseDown = (event: MouseEvent<HTMLDivElement>) => {
    if (event.target instanceof Element && event.target.closest("a, button, input, textarea, select")) return;
    rootRef.current?.focus({ preventScroll: true });
  };

  const onScroll = () => {
    const c = ctl.current;
    if (c.lock || c.settle) return;
    c.following = false;
    updateFromScroll();
  };

  const onTouchStart = (event: TouchEvent<HTMLDivElement>) => {
    const c = ctl.current;
    c.following = false;
    const touch = event.touches[0];
    c.touchStart = event.touches.length === 1 && touch ? { x: touch.clientX, y: touch.clientY } : null;
  };

  const onTouchEnd = (event: TouchEvent<HTMLDivElement>) => {
    const c = ctl.current;
    const start = c.touchStart;
    const point = event.changedTouches[0];
    if (!start || !point) return;
    const dx = point.clientX - start.x;
    const dy = point.clientY - start.y;
    c.touchStart = null;
    const reach = c.slide ? 48 : 56;
    if (Math.abs(dx) < reach || Math.abs(dx) < Math.abs(dy)) return;
    show(c.current + (dx < 0 ? 1 : -1));
  };

  const hasExtras = Array.isArray(extras) ? extras.length > 0 : extras != null && extras !== false;
  const pages: number[] = [];
  for (let number = 1; number <= total; number += 1) pages.push(number);

  return (
    <section
      ref={rootRef}
      className={`doc-viewer${compact ? " is-compact" : ""}${slide ? " is-slide" : ""}`}
      tabIndex={0}
      aria-label={`${label} 문서 뷰어`}
      data-document-id={documentId}
      onKeyDown={onKeyDown}
    >
      <div className="doc-viewer__bar viewer-bar">
        <span className="doc-viewer__title">{label}</span>
        <button type="button" className="icon-btn doc-viewer__nav" data-dir="-1" disabled={current <= 1} onClick={() => show(ctl.current.current - 1)}>
          <Icon name="chevL" />
          <span className="doc-viewer__sr">이전</span>
        </button>
        <span className="doc-viewer__indicator" role="status" aria-live="polite">{`${current} / ${total}`}</span>
        <input
          ref={jumpRef}
          className="doc-viewer__jump"
          type="number"
          inputMode="numeric"
          min="1"
          max={String(total)}
          step="1"
          aria-label="페이지로 이동"
          defaultValue={String(current)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            commitJump();
          }}
        />
        <button type="button" className="icon-btn doc-viewer__nav" data-dir="1" disabled={current >= total} onClick={() => show(ctl.current.current + 1)}>
          <Icon name="chev" />
          <span className="doc-viewer__sr">다음</span>
        </button>
        <button type="button" className="icon-btn doc-viewer__mode" aria-pressed={slide ? "true" : "false"} onClick={toggleMode}>
          <Icon name="layers" />
          <span className="doc-viewer__sr">{slide ? "이어 스크롤" : "한 쪽씩 보기"}</span>
        </button>
        {hasExtras ? <div className="viewer-actions doc-viewer__extras">{extras}</div> : null}
      </div>
      <div ref={stageRef} className="doc-viewer__stage" tabIndex={-1} onMouseDown={onMouseDown} onScroll={onScroll} onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
        {pages.map((number) => {
          const state = sheets.get(number);
          const minHeight = minHeights.current.get(number);
          const on = number === current;
          return (
            <article
              key={number}
              ref={(node) => { articles.current[number - 1] = node; }}
              className={`doc-viewer__page${on ? " is-current" : ""}`}
              data-page={String(number)}
              aria-label={`${number}쪽`}
              aria-current={on ? "true" : undefined}
              style={minHeight ? { minHeight: `${minHeight}px` } : undefined}
            >
              <div className="doc-viewer__sheet">
                {state === "live"
                  ? renderPage(number)
                  : <p className="doc-viewer__pending">{state === "released" ? `${number}쪽` : `${number}쪽을 불러오는 중입니다`}</p>}
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

/** The viewer for one document, or a short note when there is nothing to page through. */
export function createDocumentViewer(options: DocumentViewerOptions): ReactNode {
  const total = Math.max(0, Math.floor(Number(options.pageCount) || 0));
  if (!options.documentId || total < 1) {
    return <p className="doc-viewer__pending">표시할 페이지가 없습니다.</p>;
  }
  return <DocumentViewer key={`${options.documentId}:${total}`} {...options} />;
}

// ---------- PDF.js ----------

// The parts of the vendored PDF.js module this file uses.
interface PdfViewport { width: number; height: number }
interface PdfRenderTask { promise: Promise<void>; cancel(): void }
interface PdfPageProxy {
  getViewport(options: { scale: number }): PdfViewport;
  render(options: { canvasContext: CanvasRenderingContext2D; canvas: HTMLCanvasElement; viewport: PdfViewport }): PdfRenderTask;
  cleanup(): void;
}
interface PdfDocumentProxy { getPage(number: number): Promise<PdfPageProxy> }
interface PdfLoadingTask { promise: Promise<PdfDocumentProxy>; destroy(): Promise<void> }
interface PdfWorkerHandle { destroy(): void }
interface PdfLibrary {
  PDFWorker: new (options: { port: Worker }) => PdfWorkerHandle;
  getDocument(options: Record<string, unknown>): PdfLoadingTask;
}

interface OpenedPdf {
  pdf: PdfDocumentProxy;
  destroy(): Promise<void>;
}

async function openPdf(id: string): Promise<OpenedPdf | null> {
  const blob = await getCoursePdfBlob(id);
  if (!blob) return null;
  // Runtime URL of the vendored PDF.js in `public/` (not bundled), so a static import cannot name it.
  const library: PdfLibrary = await import(/* @vite-ignore */ new URL("pdf.min.mjs", VENDOR).href);
  const data = new Uint8Array(await blob.arrayBuffer());
  const port = new Worker(new URL("pdf.worker.min.mjs", VENDOR), { type: "module" });
  const onWorkerError = (event: Event) => event.preventDefault();
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

/** One PDF document opened on first use and destroyed by `close()`. */
interface PdfSession {
  open(): Promise<OpenedPdf | null>;
  close(): void;
}

function pdfSession(id: string): PdfSession {
  let opened: Promise<OpenedPdf | null> | null = null;
  return {
    open() {
      opened ??= openPdf(id).catch((error: unknown) => {
        opened = null;
        throw error;
      });
      return opened;
    },
    close() {
      const closing = opened;
      opened = null;
      closing?.then((doc) => doc?.destroy()).catch(() => {});
    },
  };
}

/** One PDF page: the canvas when PDF.js can draw it, the extracted text either way. */
function PdfPage({ number, text, session }: { number: number; text: string; session: PdfSession }) {
  const slotRef = useRef<HTMLDivElement>(null);
  const [drawn, setDrawn] = useState<"pending" | "drawn" | "failed">("pending");

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    const slot = slotRef.current;
    const draw = async () => {
      if (!slot) return;
      const doc = await session.open();
      if (signal.aborted || !doc) return;
      const pdfPage = await doc.pdf.getPage(number);
      if (signal.aborted) {
        pdfPage.cleanup();
        return;
      }
      const width = Math.max(1, slot.clientWidth || slot.parentElement?.clientWidth || 320);
      const base = pdfPage.getViewport({ scale: 1 });
      const viewport = pdfPage.getViewport({ scale: width / base.width });
      const canvas = document.createElement("canvas");
      const ratio = Math.min(2, window.devicePixelRatio || 1);
      canvas.width = Math.floor(viewport.width * ratio);
      canvas.height = Math.floor(viewport.height * ratio);
      const context = canvas.getContext("2d");
      if (!context) throw new Error("canvas 2d context unavailable");
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      // The slot has no React children: PDF.js owns its canvas.
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
      if (!signal.aborted) setDrawn("drawn");
    };
    draw().catch((error: unknown) => {
      const name = typeof error === "object" && error !== null && "name" in error ? error.name : "";
      if (signal.aborted || name === "RenderingCancelledException") return;
      setDrawn("failed");
    });
    return () => {
      controller.abort();
      slot?.replaceChildren();
    };
  }, [number, session]);

  return (
    <>
      {drawn === "failed" ? null : <div ref={slotRef} className="doc-viewer__canvas" aria-hidden="true" />}
      <p className={`doc-viewer__text${drawn === "drawn" ? " doc-viewer__sr" : ""}`}>{text}</p>
    </>
  );
}

/** The fields of a stored course PDF record the viewer reads. */
export interface CoursePdfSource {
  id: string;
  pageCount?: number | null;
  pages?: readonly { number: number; text: string }[] | null;
  title?: string | null;
  fileName?: string | null;
}

export interface CoursePdfViewerOptions {
  label?: string;
  compact?: boolean;
}

/** Page viewer for one stored course PDF. Text stays available when the canvas cannot be drawn. */
export function CoursePdfViewer({ record, label, compact = false }: { record: CoursePdfSource } & CoursePdfViewerOptions) {
  const [session] = useState(() => pdfSession(record.id));
  useEffect(() => () => session.close(), [session]);
  const pages = Array.isArray(record.pages) ? record.pages : [];
  return createDocumentViewer({
    documentId: `course-pdf:${record.id}`,
    pageCount: record.pageCount || pages.length,
    label: label || record.title || record.fileName || "PDF",
    compact,
    renderPage(number) {
      const source = pages.find((item) => item.number === number);
      const text = source && source.text.trim()
        ? source.text
        : "이 페이지에서 텍스트를 추출하지 못했습니다. 원본 PDF에서 확인해 주세요.";
      return <PdfPage number={number} text={text} session={session} />;
    },
  });
}

export function createCoursePdfViewer(record: CoursePdfSource, options: CoursePdfViewerOptions = {}): ReactNode {
  return <CoursePdfViewer key={record.id} record={record} label={options.label} compact={Boolean(options.compact)} />;
}
