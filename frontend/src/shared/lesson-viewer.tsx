// 학습실 자료 요약 화면(FMsongX2/lessonviewer `LectureView` 이식): 빨간 실 위에 요약 흐름(beat)과 쪽을 차례로 꿴다.
// 쪽 한 줄은 원본 | 너비 손잡이 | 설명이고, 보기 방식(핵심만·기본·전체)에 따라 덜 중요한 쪽은 접힌다. 위에 붙는
// 도구줄에는 지금 흐름, 보기 방식, 쪽 번호, 진행 막대가 있다. 핵심 개념·퀴즈·플래시카드는 "학습 도구" 칸에서 연다.
// 데이터는 `LessonDoc` 하나로 받는다. `summary`는 LessonViewer pack(= ASTRA 게시 요약 `ServerSummary`)과 같은
// 모양이고, `concepts`·`quiz`는 LessonViewer `GET /api/module/documents/:id/study`의 항목(상태 포함)과 같은
// 모양이다. 샘플 자료(study-files.tsx)와 서버 게시 요약(study-server.tsx)이 각자 이 모양으로 바꿔 넘긴다.
// 모델 출력은 page-summary의 markdown()/inline()으로 React 요소로만 그린다(innerHTML 없음).
// 원본의 책갈피·별표·필기·진도 저장은 저장할 서버가 없어 옮기지 않았다.
import {
  memo, useCallback, useEffect, useId, useMemo, useRef, useState,
  type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent, type ReactNode, type RefObject,
} from "react";
import { inline, markdown, PageImage, type PageSummary, type SummaryBeat, type SummarySlide } from "./page-summary.tsx";
import { sharedRefresh } from "./page-image-retry.ts";

/** LessonViewer `ModuleStudyStatus`. */
export type LessonStudyStatus = "none" | "running" | "ready" | "failed";
/** LessonViewer `ModuleStudyEntry<T>` (updatedAt 제외). */
export interface LessonStudyEntry<T> {
  status: LessonStudyStatus;
  data: T | null;
  error: string | null;
}
/** LessonViewer `ModuleStudyConcept`. */
export interface LessonConcept {
  term: string;
  meaning: string;
  page: number | null;
}
/** LessonViewer `ModuleStudyQuizItem`. `answer`는 `options`의 번호(0부터). */
export interface LessonQuizItem {
  question: string;
  options: string[];
  answer: number;
  explanation: string;
  page: number | null;
}

/** 원본 쪽: 서버 쪽 이미지(`<base>NNN.jpg`) 또는 글로 된 쪽(샘플). */
export type LessonSource =
  | { kind: "images"; base: string; aspect: number | null; refresh?: () => Promise<string | null> }
  | { kind: "text"; pages: { page: number; title: string; text: string }[] };

export interface LessonDoc {
  /** 바뀌면 보던 쪽·펼친 구간·퀴즈 답을 처음으로 돌린다. */
  id: string;
  title: string;
  pageCount: number;
  /** null: 이 화면에서 보여 줄 원본 쪽이 없다. */
  source: LessonSource | null;
  summary: PageSummary | null;
  /** 파일 전체 요약 Markdown(LessonViewer guide.overview, ASTRA `…/summary/overview`). */
  overview: string | null;
  /** 없으면(undefined) 그 출처가 이 도구를 주지 않는 것이라 탭을 숨긴다. 플래시카드는 핵심 개념으로 만든다. */
  concepts?: LessonStudyEntry<LessonConcept[]>;
  quiz?: LessonStudyEntry<LessonQuizItem[]>;
  /** 머리 아래 출처 안내. */
  note: string;
}

type Mode = "core" | "default" | "all";
const MODES: { id: Mode; label: string; key: string; title: string }[] = [
  { id: "core", label: "핵심만", key: "1", title: "핵심 쪽만 펼침 (1)" },
  { id: "default", label: "기본", key: "2", title: "핵심과 보조를 펼치고 배경·표지는 접음 (2)" },
  { id: "all", label: "전체", key: "3", title: "모든 쪽을 펼침 (3)" },
];
type Tag = "core" | "support" | "background" | "meta";
const TAG_LABEL: Record<Tag, string> = { core: "핵심", support: "보조", background: "배경", meta: "표지·목차" };
type ToolTab = "concepts" | "quiz" | "cards";
const TOOL_LABEL: Record<ToolTab, string> = { concepts: "핵심 개념", quiz: "퀴즈", cards: "플래시카드" };

const MODE_KEY = "stack-lv-mode";
const SPLIT_KEY = "stack-lv-split";
/** 원본 쪽이 차지하는 비율. 모든 줄이 같은 값을 쓴다. */
const SPLIT_DEFAULT = 0.54;
const SPLIT_MIN = 0.28;
const SPLIT_MAX = 0.76;
/** 이 너비 아래에서는 학습 도구 칸이 화면을 덮는 창이 된다(lesson-viewer.css와 같은 값). */
const DOCK_OVERLAY = "(max-width: 1319px)";

const clampPage = (page: number, count: number) => Math.min(Math.max(1, Math.round(page)), Math.max(1, count));
const clampSplit = (value: number) => Math.min(SPLIT_MAX, Math.max(SPLIT_MIN, value));
const tagOf = (slide: SummarySlide | undefined): Tag | null => (slide ? (slide.tag && slide.tag in TAG_LABEL ? slide.tag as Tag : "core") : null);
const pagesText = (pages: number[]) => (pages.length > 1 ? `p.${pages[0]}–${pages[pages.length - 1]}` : `p.${pages[0]}`);
const beatText = (beat: SummaryBeat) => pagesText(beat.from === beat.to ? [beat.from] : [beat.from, beat.to]);
const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

function readPref(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function writePref(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 저장소를 못 쓰면 이번 화면에서만 기억한다.
  }
}
const savedMode = (): Mode => {
  const value = readPref(MODE_KEY);
  return value === "core" || value === "all" ? value : "default";
};
const savedSplit = (): number => {
  const value = Number(readPref(SPLIT_KEY));
  return Number.isFinite(value) && value > 0 ? clampSplit(value) : SPLIT_DEFAULT;
};

function useMedia(query: string): boolean {
  const [match, setMatch] = useState(() => matchMedia(query).matches);
  useEffect(() => {
    const list = matchMedia(query);
    const on = () => setMatch(list.matches);
    list.addEventListener("change", on);
    return () => list.removeEventListener("change", on);
  }, [query]);
  return match;
}

const isTyping = (target: EventTarget | null) => {
  const node = target as HTMLElement | null;
  return Boolean(node && (node.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(node.tagName)));
};

/** 창 안에서만 Tab이 돌고 Esc로 닫는다(크게 보기, 덮는 학습 도구 칸). */
function useDialogKeys(ref: RefObject<HTMLElement | null>, active: boolean, onClose: () => void) {
  useEffect(() => {
    if (!active) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        onClose();
        return;
      }
      const panel = ref.current;
      if (event.key !== "Tab" || !panel) return;
      const fields = [...panel.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], [tabindex="0"]')].filter((node) => node.getClientRects().length > 0);
      const first = fields[0];
      const last = fields[fields.length - 1];
      if (!first) return event.preventDefault();
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel)) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    const html = document.documentElement;
    const before = html.style.overflow;
    html.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey, true);
      html.style.overflow = before;
    };
  }, [ref, active, onClose]);
}

/**
 * 위치 버튼의 글자와 읽어 줄 이름. 핵심 개념·퀴즈의 `page`를 받는다: 자료는 쪽 번호, 녹음 화면(recording-study)은
 * 재생 시각(초). `what`은 버튼이 가리키는 대상(용어, "해설", "뜻").
 */
export type Locate = (at: number, what: string) => { text: string; label: string };
const pageAt: Locate = (page, what) => ({ text: `p.${page}`, label: `${what} 원본 ${page}쪽 보기` });

export function LessonViewer({ doc }: { doc: LessonDoc }) {
  return <LessonBody key={doc.id} doc={doc} />;
}

// ---------- 흐름 ----------

type Item =
  | { kind: "beat"; beat: SummaryBeat; index: number }
  | { kind: "slide"; page: number }
  | { kind: "fold"; pages: number[] };

/** 쪽을 차례로 훑으며 beat를 첫 쪽 앞에 끼우고, 접힐 쪽은 이어진 구간끼리 묶는다. */
function buildItems(count: number, story: SummaryBeat[], slideAt: Map<number, SummarySlide>, mode: Mode, summarized: boolean): Item[] {
  const beatsAt = new Map<number, Item[]>();
  story.forEach((beat, index) => {
    const at = clampPage(beat.from, count);
    beatsAt.set(at, [...(beatsAt.get(at) || []), { kind: "beat", beat, index: index + 1 }]);
  });
  const items: Item[] = [];
  for (let page = 1; page <= count; page++) {
    items.push(...(beatsAt.get(page) || []));
    const tag = tagOf(slideAt.get(page));
    const open = !summarized || mode === "all" || tag === "core" || (mode === "default" && tag === "support");
    if (open) {
      items.push({ kind: "slide", page });
      continue;
    }
    const last = items[items.length - 1];
    if (last && last.kind === "fold") last.pages.push(page);
    else items.push({ kind: "fold", pages: [page] });
  }
  return items;
}

function LessonBody({ doc }: { doc: LessonDoc }) {
  const summary = doc.summary;
  const count = Math.max(0, doc.pageCount);
  const story = useMemo(() => (summary && Array.isArray(summary.story) ? summary.story.filter((beat) => Number.isFinite(beat.from)) : []), [summary]);
  const slideAt = useMemo(() => new Map((summary && Array.isArray(summary.slides) ? summary.slides : []).map((slide) => [slide.page, slide])), [summary]);
  const review = summary && summary.review && typeof summary.review === "object" ? summary.review : null;
  const summarized = story.length > 0 || slideAt.size > 0;
  const source = count ? doc.source : null;
  const tools: ToolTab[] = [...(doc.concepts ? ["concepts" as const] : []), ...(doc.quiz ? ["quiz" as const] : []), ...(doc.concepts ? ["cards" as const] : [])];

  const [mode, setMode] = useState<Mode>(savedMode);
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set());
  const [current, setCurrent] = useState(1);
  const [beatNow, setBeatNow] = useState(0);
  const [zoom, setZoom] = useState<number | null>(null);
  const [toolsOpen, setToolsOpen] = useState(false);
  const overlayDock = useMedia(DOCK_OVERLAY);
  const root = useRef<HTMLDivElement>(null);
  const bar = useRef<HTMLDivElement>(null);
  const stream = useRef<HTMLDivElement>(null);
  const dock = useRef<HTMLElement>(null);
  const toolsButton = useRef<HTMLButtonElement>(null);
  const navLock = useRef(0);
  const split = useSplit(root);
  const dockId = `${useId()}-tools`;

  const items = useMemo(() => buildItems(count, story, slideAt, mode, summarized), [count, story, slideAt, mode, summarized]);

  // 쪽 이미지가 실패하면(이미지 쿠키 만료) 출처를 다시 받아 쿠키를 새로 받고 이미지를 다시 그린다. 동시에 실패한 쪽은 한 번의 요청을 같이 쓴다.
  // 화면이 그릴 때마다 doc을 새로 만들어도 이미지 주소가 같으면 같은 함수를 쓰도록 주소에만 묶고, 다시 받는 함수는 최신 것을 읽는다.
  const imageBase = source && source.kind === "images" ? source.base : null;
  const reload = useRef<(() => Promise<string | null>) | undefined>(undefined);
  useEffect(() => {
    reload.current = source && source.kind === "images" ? source.refresh : undefined;
  });
  const refresh = useMemo(
    () => (imageBase === null ? () => Promise.resolve(null) : sharedRefresh(() => (reload.current ? reload.current() : imageBase))),
    [imageBase],
  );

  const changeMode = useCallback((next: Mode) => {
    setMode(next);
    writePref(MODE_KEY, next);
    setExpanded(new Set());
  }, []);
  const toggleFold = useCallback((pages: number[]) => setExpanded((prev) => {
    const next = new Set(prev);
    const open = pages.some((page) => prev.has(page));
    pages.forEach((page) => (open ? next.delete(page) : next.add(page)));
    return next;
  }), []);

  /** 그 쪽으로 이동한다. 접힌 구간이면 펼친 뒤 옮긴다. 스크롤이 도착하는 동안의 중간 쪽은 무시한다. */
  const scrollToPage = useCallback((target: number, smooth = true) => {
    const page = clampPage(target, count);
    const fold = items.find((item) => item.kind === "fold" && item.pages.includes(page));
    if (fold && fold.kind === "fold") setExpanded((prev) => new Set([...prev, ...fold.pages]));
    navLock.current = Date.now() + 900;
    setCurrent(page);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const row = stream.current && stream.current.querySelector<HTMLElement>(`.lv-slide[data-page="${page}"]`);
      if (row) row.scrollIntoView({ block: "start", behavior: smooth && !reducedMotion() ? "smooth" : "auto" });
    }));
  }, [count, items]);
  const jumpToBeat = useCallback((index: number) => {
    const node = stream.current && stream.current.querySelector<HTMLElement>(`.lv-beat[data-index="${index}"]`);
    if (node) node.scrollIntoView({ block: "start", behavior: reducedMotion() ? "auto" : "smooth" });
  }, []);

  // 도구줄 바로 아래 선을 지난 마지막 쪽·흐름이 지금 위치다. 맨 아래까지 내리면 마지막으로 보이는 쪽이다.
  useEffect(() => {
    let frame = 0;
    const onScroll = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const box = stream.current;
        if (!box) return;
        const line = (bar.current ? bar.current.getBoundingClientRect().bottom : 0) + 40;
        const rows = [...box.querySelectorAll<HTMLElement>(".lv-slide[data-page]")];
        let page = rows.length ? Number(rows[0].dataset.page) : 1;
        for (const row of rows) {
          if (row.getBoundingClientRect().top <= line) page = Number(row.dataset.page);
          else break;
        }
        const end = innerHeight + scrollY >= document.documentElement.scrollHeight - 4;
        if (end && rows.length) page = Number(rows[rows.length - 1].dataset.page);
        let beat = 0;
        for (const node of box.querySelectorAll<HTMLElement>(".lv-beat[data-index]")) {
          if (node.getBoundingClientRect().top <= line) beat = Number(node.dataset.index);
          else break;
        }
        setBeatNow(beat);
        if (Date.now() >= navLock.current) setCurrent(page);
      });
    };
    const observer = new ResizeObserver(onScroll);
    if (stream.current) observer.observe(stream.current);
    addEventListener("scroll", onScroll, { passive: true });
    return () => {
      observer.disconnect();
      removeEventListener("scroll", onScroll);
      cancelAnimationFrame(frame);
    };
  }, [items, expanded]);

  const closeTools = useCallback(() => {
    setToolsOpen(false);
    requestAnimationFrame(() => toolsButton.current?.focus({ preventScroll: true }));
  }, []);
  useDialogKeys(dock, toolsOpen && overlayDock, closeTools);
  useEffect(() => {
    if (toolsOpen && overlayDock) dock.current?.focus({ preventScroll: true });
  }, [toolsOpen, overlayDock]);

  // 키보드: j/k 다음·이전 쪽, 1/2/3 보기 방식. 한글 자판 상태의 자모도 받는다.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey || zoom !== null || isTyping(event.target)) return;
      if ((toolsOpen && overlayDock) || document.querySelector('[aria-modal="true"]')) return;
      const key = event.key.toLowerCase();
      const rows = [...(stream.current ? stream.current.querySelectorAll<HTMLElement>(".lv-slide[data-page]") : [])].map((row) => Number(row.dataset.page));
      if (key === "j" || key === "ㅓ") {
        const next = rows.find((page) => page > current);
        if (next) scrollToPage(next);
      } else if (key === "k" || key === "ㅏ") {
        const prev = [...rows].reverse().find((page) => page < current) ?? rows[0];
        if (prev) scrollToPage(prev);
      } else {
        const picked = summarized ? MODES.find((item) => item.key === key) : undefined;
        if (!picked) return;
        changeMode(picked.id);
      }
      event.preventDefault();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [current, zoom, toolsOpen, overlayDock, summarized, scrollToPage, changeMode]);

  // 크게 보기에서 넘겨 본 쪽으로 닫으면 화면도 그 쪽으로 옮긴다.
  const zoomStart = useRef<number | null>(null);
  const openZoom = useCallback((page: number) => {
    zoomStart.current = page;
    setZoom(page);
  }, []);
  const closeZoom = useCallback(() => {
    if (zoom !== null && zoom !== zoomStart.current) scrollToPage(zoom, false);
    setZoom(null);
  }, [zoom, scrollToPage]);

  const beat = beatNow > 0 ? story[beatNow - 1] : null;
  const warnings = summary && Array.isArray(summary.warnings) ? summary.warnings.filter((text): text is string => Boolean(text)) : [];
  const question = (summary && summary.question) || "";
  const lead = question || doc.overview || "";
  const more = question && doc.overview && doc.overview !== question ? doc.overview : "";
  const docked = toolsOpen && !overlayDock;

  const renderSlide = (page: number) => (
    <SlideRow
      key={page} page={page} slide={slideAt.get(page)} review={review ? review[String(page)] || null : null}
      source={source} summarized={summarized} split={split.handlers} refresh={refresh} onZoom={openZoom}
    />
  );

  return (
    <div ref={root} className={`lv${docked ? " with-tools" : ""}${source ? "" : " no-source"}${summarized ? "" : " no-summary"}`} style={split.style}>
      <div ref={bar} className="lv-bar">
        <span className="lv-where" title={doc.title}>{doc.title}</span>
        {story.length ? <BeatMenu story={story} now={beatNow} current={beat} onPick={jumpToBeat} /> : null}
        <span className="lv-spacer" />
        {summarized ? <Segmented label="보기 방식" value={mode} onChange={changeMode} options={MODES} /> : null}
        {count ? <span className="lv-pageno">{`${current} / ${count}`}</span> : null}
        {tools.length ? (
          <button
            ref={toolsButton} type="button" className="lv-tools-toggle" aria-expanded={toolsOpen ? "true" : "false"} aria-controls={dockId}
            onClick={() => (toolsOpen ? closeTools() : setToolsOpen(true))}
          >
            학습 도구
          </button>
        ) : null}
        <span className="lv-progress" aria-hidden="true" style={{ transform: `scaleX(${count ? current / count : 0})` }} />
      </div>

      <div className="lv-body">
        <header className="lv-intro">
          {warnings.length ? (
            <div className="lv-warn" role="note">
              <strong>확인할 점</strong>
              <ul>{warnings.map((text, index) => <li key={index}>{inline(text)}</li>)}</ul>
            </div>
          ) : null}
          {lead ? <div className="lv-question">{markdown(lead)}</div> : null}
          {more ? <div className="lv-overview">{markdown(more)}</div> : null}
          {story.length ? (
            <ol className="lv-storymap" aria-label="요약 흐름">
              {story.map((item, index) => (
                <li key={index}>
                  <button type="button" onClick={() => jumpToBeat(index + 1)}>
                    <span className="t">{inline(item.title || `${index + 1}번째 구간`)}</span>
                    <span className="r">{beatText(item)}</span>
                  </button>
                </li>
              ))}
            </ol>
          ) : null}
          {summarized ? null : <p className="lv-waiting">아직 이 자료의 요약이 없습니다. 원본 쪽만 볼 수 있습니다.</p>}
          <p className="lv-note">{doc.note}</p>
        </header>

        {count ? (
          <div ref={stream} className="lv-stream">
            <span className="lv-thread" aria-hidden="true" />
            {items.map((item) => {
              if (item.kind === "beat") return <BeatBlock key={`b-${item.index}`} beat={item.beat} index={item.index} />;
              if (item.kind === "slide") return renderSlide(item.page);
              const open = item.pages.some((page) => expanded.has(page));
              const titles = item.pages.map((page) => (slideAt.get(page) || {}).title || "").filter(Boolean);
              return (
                <div key={`f-${item.pages[0]}`} className={`lv-fold${open ? " is-open" : ""}`}>
                  <button type="button" className="lv-fold-bar" aria-expanded={open ? "true" : "false"} onClick={() => toggleFold(item.pages)}>
                    <span className="lv-fold-count">{open ? "다시 접기" : `접은 쪽 ${item.pages.length}장`}</span>
                    <span className="lv-fold-range">{pagesText(item.pages)}</span>
                    {open ? null : <span className="lv-fold-titles">{titles.join(", ")}</span>}
                  </button>
                  {open ? <div className="lv-fold-body">{item.pages.map(renderSlide)}</div> : null}
                </div>
              );
            })}
          </div>
        ) : <p className="lv-state">이 화면에서 볼 수 있는 원본 쪽이 없습니다.</p>}
      </div>

      {tools.length ? (
        <>
          {toolsOpen && overlayDock ? <div className="lv-dock-backdrop" aria-hidden="true" onClick={closeTools} /> : null}
          <aside
            ref={dock} id={dockId} className="lv-dock" tabIndex={-1} hidden={!toolsOpen}
            role={overlayDock ? "dialog" : "complementary"} aria-modal={toolsOpen && overlayDock ? "true" : undefined} aria-label="학습 도구"
          >
            <div className="lv-dock-head">
              <h2>학습 도구</h2>
              <button type="button" className="lv-text-btn" onClick={closeTools}>닫기</button>
            </div>
            <StudyTabs doc={doc} tools={tools} onJump={(page) => { if (overlayDock) closeTools(); scrollToPage(page, false); }} />
          </aside>
        </>
      ) : null}

      {zoom !== null && source ? <Lightbox source={source} title={doc.title} slideAt={slideAt} count={count} page={zoom} onPage={setZoom} onClose={closeZoom} refresh={refresh} /> : null}
    </div>
  );
}

// ---------- 도구줄 ----------

function Segmented<T extends string>({ options, value, onChange, label }: { options: { id: T; label: string; title?: string }[]; value: T; onChange: (value: T) => void; label: string }) {
  return (
    <div className="lv-seg" role="radiogroup" aria-label={label}>
      {options.map((option) => (
        <button key={option.id} type="button" role="radio" aria-checked={value === option.id ? "true" : "false"} title={option.title} onClick={() => onChange(option.id)}>
          {option.label}
        </button>
      ))}
    </div>
  );
}

/** 지금 읽는 흐름. 누르면 흐름 목차가 열린다. */
function BeatMenu({ story, now, current, onPick }: { story: SummaryBeat[]; now: number; current: SummaryBeat | null; onPick: (index: number) => void }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = `${useId()}-beats`;
  useEffect(() => {
    if (!open) return;
    const onDown = (event: PointerEvent) => {
      if (box.current && !box.current.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setOpen(false);
      trigger.current?.focus();
    };
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  return (
    <div ref={box} className="lv-pop">
      <button ref={trigger} type="button" className="lv-beat-now" aria-expanded={open ? "true" : "false"} aria-controls={panelId} title="요약 흐름 목차" onClick={() => setOpen(!open)}>
        {current ? <span className="n">{now}</span> : null}
        <span className="t">{current ? inline(current.title || `${now}번째 구간`) : "요약 흐름"}</span>
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 6l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
      {open ? (
        <div className="lv-pop-panel" id={panelId}>
          <ol className="lv-beat-menu">
            {story.map((beat, index) => (
              <li key={index}>
                <button type="button" aria-current={index + 1 === now ? "step" : undefined} onClick={() => { setOpen(false); onPick(index + 1); }}>
                  <span className="n">{index + 1}</span>
                  <span className="t">{inline(beat.title || `${index + 1}번째 구간`)}</span>
                  <span className="r">{beatText(beat)}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </div>
  );
}

// ---------- 흐름 한 단계와 쪽 한 줄 ----------

/** 요약 흐름 한 단계: 문제 → 해결 → trade-off → (보충) → 이어서. */
function BeatBlock({ beat, index }: { beat: SummaryBeat; index: number }) {
  const parts: [string, string | undefined, string][] = [
    ["문제", beat.problem, ""], ["해결", beat.answer, ""], ["trade-off", beat.tradeoff, ""], ["보충", beat.extra, "extra"],
  ];
  return (
    <section className="lv-beat" data-index={index} aria-label={`${index}번째 흐름`}>
      <span className="lv-node" aria-hidden="true">{index}</span>
      <header>
        <h3>{inline(beat.title || `${index}번째 구간`)}</h3>
        <span className="lv-range">{beatText(beat)}</span>
      </header>
      <dl>
        {parts.filter(([, value]) => value).map(([label, value, kind]) => (
          <div key={label} className={kind || undefined}>
            <dt>{label}</dt>
            <dd>{markdown(value)}</dd>
          </div>
        ))}
      </dl>
      {beat.bridge ? <p className="lv-bridge"><span className="lv-bridge-label">이어서</span><span>{inline(beat.bridge)}</span></p> : null}
    </section>
  );
}

interface SlideRowProps {
  page: number;
  slide: SummarySlide | undefined;
  review: string | null;
  source: LessonSource | null;
  summarized: boolean;
  split: SplitHandlers;
  refresh: () => Promise<string | null>;
  onZoom: (page: number) => void;
}

const SlideRow = memo(function SlideRow({ page, slide, review, source, summarized, split, refresh, onZoom }: SlideRowProps) {
  const tag = tagOf(slide);
  return (
    <article className={`lv-slide tag-${tag || "none"}`} data-page={page} aria-label={`${page}쪽`}>
      <div className="lv-gutter"><span className="lv-pno">{`p.${page}`}</span></div>
      {source ? (
        <div className="lv-shot">
          <PageView source={source} page={page} refresh={refresh} zoom={{ label: `${page}쪽 크게 보기`, open: () => onZoom(page) }} />
        </div>
      ) : null}
      {source && summarized ? <Splitter split={split} /> : null}
      {summarized ? (
        <div className="lv-explain">
          {slide && tag ? (
            <>
              <h3>
                <span>{inline(slide.title || `${page}쪽`)}</span>
                <span className={`lv-tagname t-${tag}`}>{TAG_LABEL[tag]}</span>
              </h3>
              {review ? (
                <details className="lv-review">
                  <summary>확인 필요</summary>
                  <p>{review}</p>
                </details>
              ) : null}
              {markdown(slide.explain || "")}
            </>
          ) : <p className="lv-waiting">이 쪽에는 설명이 없습니다.</p>}
        </div>
      ) : null}
    </article>
  );
});

interface Zoom {
  label: string;
  open: () => void;
}

function ZoomButton({ zoom, children }: { zoom: Zoom | undefined; children: ReactNode }) {
  if (!zoom) return children;
  return <button type="button" className="lv-zoom" aria-label={zoom.label} onClick={zoom.open}>{children}</button>;
}

/**
 * 원본 한 쪽: 서버 쪽 이미지 또는 글로 된 쪽(샘플). `zoom`이 있으면 쪽을 크게 보기 버튼으로 감싼다.
 * 이미지를 끝내 못 불러오면 크게 보기 대신 안내와 다시 불러오기 버튼을 보인다(버튼 안에 버튼을 두지 않는다).
 */
function PageView({ source, page, refresh, zoom }: { source: LessonSource; page: number; refresh: () => Promise<string | null>; zoom?: Zoom }) {
  if (source.kind === "images") {
    const style = source.aspect ? { aspectRatio: String(source.aspect) } : undefined;
    return (
      <PageImage
        key={source.base} base={source.base} page={page} refresh={refresh} alt={`${page}쪽 원본`}
        errorClass="lv-sheet lv-page-error page-image-failed"
        wrap={(image) => <ZoomButton zoom={zoom}><span className="lv-sheet" style={style}>{image}</span></ZoomButton>}
      />
    );
  }
  const item = source.pages.find((row) => row.page === page);
  return (
    <ZoomButton zoom={zoom}>
      <span className="lv-sheet lv-paper">
        <span className="lv-paper-title">{item ? item.title : ""}</span>
        <span className="lv-paper-text">{item ? item.text : ""}</span>
        <span className="lv-paper-no" aria-hidden="true">{page}</span>
      </span>
    </ZoomButton>
  );
}

// ---------- 원본·설명 너비 ----------

interface SplitHandlers {
  value: number;
  start: (event: ReactPointerEvent<HTMLElement>) => void;
  key: (event: ReactKeyboardEvent<HTMLElement>) => void;
  reset: () => void;
}

function useSplit(host: RefObject<HTMLDivElement | null>) {
  const [split, setSplitState] = useState(savedSplit);
  const setSplit = useCallback((value: number) => {
    const next = clampSplit(value);
    setSplitState(next);
    writePref(SPLIT_KEY, next.toFixed(4));
  }, []);

  // 끄는 동안에는 CSS 변수만 바꿔 렌더 없이 따라오게 하고, 놓을 때 저장한다. 잡고 있는 줄이 제자리에 있도록 스크롤을 보정한다.
  const start = useCallback((event: ReactPointerEvent<HTMLElement>) => {
    const handle = event.currentTarget;
    const row = handle.closest<HTMLElement>(".lv-slide");
    const box = host.current;
    if (!row || !box || event.button !== 0) return;
    event.preventDefault();
    const rect = row.getBoundingClientRect();
    const anchor = rect.top;
    let ratio = split;
    handle.setPointerCapture(event.pointerId);
    document.documentElement.classList.add("lv-resizing");
    const move = (next: PointerEvent) => {
      ratio = clampSplit((next.clientX - rect.left) / rect.width);
      box.style.setProperty("--lv-split", `${(ratio * 100).toFixed(2)}%`);
      const drift = row.getBoundingClientRect().top - anchor;
      if (Math.abs(drift) >= 1) scrollBy(0, drift);
    };
    const end = () => {
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", end);
      handle.removeEventListener("pointercancel", end);
      document.documentElement.classList.remove("lv-resizing");
      setSplit(ratio);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", end);
    handle.addEventListener("pointercancel", end);
  }, [host, split, setSplit]);

  const key = useCallback((event: ReactKeyboardEvent<HTMLElement>) => {
    const step = event.shiftKey ? 0.06 : 0.02;
    if (event.key === "ArrowLeft") setSplit(split - step);
    else if (event.key === "ArrowRight") setSplit(split + step);
    else if (event.key === "Enter" || event.key === "Home") setSplit(SPLIT_DEFAULT);
    else return;
    event.preventDefault();
    event.stopPropagation();
  }, [split, setSplit]);

  const reset = useCallback(() => setSplit(SPLIT_DEFAULT), [setSplit]);
  const handlers = useMemo<SplitHandlers>(() => ({ value: split, start, key, reset }), [split, start, key, reset]);
  const style = { "--lv-split": `${(split * 100).toFixed(2)}%` } as CSSProperties;
  return { handlers, style };
}

function Splitter({ split }: { split: SplitHandlers }) {
  return (
    <div
      className="lv-splitter" role="separator" aria-orientation="vertical" aria-label="원본과 설명 너비"
      aria-valuemin={SPLIT_MIN * 100} aria-valuemax={SPLIT_MAX * 100} aria-valuenow={Math.round(split.value * 100)} tabIndex={0}
      title="끌어서 너비 조절, 더블클릭하면 처음 비율" onPointerDown={split.start} onKeyDown={split.key} onDoubleClick={split.reset}
    >
      <span className="lv-grip" />
    </div>
  );
}

// ---------- 크게 보기 ----------

interface LightboxProps {
  source: LessonSource;
  title: string;
  slideAt: Map<number, SummarySlide>;
  count: number;
  page: number;
  onPage: (page: number) => void;
  onClose: () => void;
  refresh: () => Promise<string | null>;
}

function Lightbox({ source, title, slideAt, count, page, onPage, onClose, refresh }: LightboxProps) {
  const box = useRef<HTMLDivElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  useDialogKeys(box, true, onClose);
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    close.current?.focus();
    return () => before?.focus({ preventScroll: true });
  }, []);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "ArrowLeft" && page > 1) onPage(page - 1);
      else if (event.key === "ArrowRight" && page < count) onPage(page + 1);
      else return;
      event.preventDefault();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [page, count, onPage]);
  const slide = slideAt.get(page);
  return (
    <div ref={box} className="lv-lightbox" role="dialog" aria-modal="true" aria-label={`${title} ${page}쪽 크게 보기`} onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="lv-lb-stage"><PageView key={page} source={source} page={page} refresh={refresh} /></div>
      <div className="lv-lb-bar">
        <button type="button" aria-label="이전 쪽" disabled={page <= 1} onClick={() => onPage(page - 1)}>‹</button>
        <span className="lv-lb-label">{`p.${page}`}{slide && slide.title ? <>{"  "}{inline(slide.title)}</> : null}</span>
        <span className="lv-lb-count">{`${page} / ${count}`}</span>
        <button type="button" aria-label="다음 쪽" disabled={page >= count} onClick={() => onPage(page + 1)}>›</button>
        <button ref={close} type="button" aria-label="크게 보기 닫기" onClick={onClose}>✕</button>
      </div>
    </div>
  );
}

// ---------- 학습 도구 ----------

function StudyTabs({ doc, tools, onJump }: { doc: LessonDoc; tools: ToolTab[]; onJump: (page: number) => void }) {
  const [picked, setTab] = useState<ToolTab>(tools[0]);
  const tab = tools.includes(picked) ? picked : tools[0];
  const list = useRef<HTMLDivElement>(null);
  const panelId = `${useId()}-panel`;

  const onTabKey = (event: ReactKeyboardEvent) => {
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, Home: -Infinity, End: Infinity };
    if (!(event.key in moves)) return;
    event.preventDefault();
    const at = tools.indexOf(tab);
    const step = moves[event.key];
    const next = step === -Infinity ? 0 : step === Infinity ? tools.length - 1 : (at + step + tools.length) % tools.length;
    setTab(tools[next]);
    requestAnimationFrame(() => list.current?.querySelector<HTMLButtonElement>(`[data-tab="${tools[next]}"]`)?.focus());
  };

  return (
    <>
      <div ref={list} className="lv-tabs" role="tablist" aria-label="학습 도구" onKeyDown={onTabKey}>
        {tools.map((key) => (
          <button
            key={key} type="button" role="tab" data-tab={key} id={`${panelId}-${key}`} aria-selected={key === tab ? "true" : "false"}
            aria-controls={panelId} tabIndex={key === tab ? 0 : -1} onClick={() => setTab(key)}
          >
            {TOOL_LABEL[key]}
          </button>
        ))}
      </div>
      <div className="lv-panel" id={panelId} role="tabpanel" aria-labelledby={`${panelId}-${tab}`} tabIndex={0}>
        {tab === "concepts" && doc.concepts ? <StudyEntry entry={doc.concepts} render={(concepts) => <ConceptList concepts={concepts} onJump={onJump} />} /> : null}
        {tab === "quiz" && doc.quiz ? <StudyEntry entry={doc.quiz} render={(quiz) => <Quiz quiz={quiz} onJump={onJump} />} /> : null}
        {tab === "cards" && doc.concepts ? <StudyEntry entry={doc.concepts} render={(concepts) => <Flashcards concepts={concepts} />} /> : null}
      </div>
    </>
  );
}

export function Jump({ to, text, label, current, onJump }: { to: number; text: string; label: string; current?: boolean; onJump: (page: number) => void }) {
  return <button type="button" className="lv-jump" aria-current={current ? "true" : undefined} aria-label={label} onClick={() => onJump(to)}>{text}</button>;
}

/** 도구 하나의 상태별 자리. 준비된 것만 그린다. */
function StudyEntry<T>({ entry, render }: { entry: LessonStudyEntry<T>; render: (data: T) => ReactNode }) {
  if (entry.status === "ready" && entry.data) return <>{render(entry.data)}</>;
  if (entry.status === "failed") return <p className="lv-state is-failed" role="alert"><strong>만들지 못했습니다</strong>{entry.error || "잠시 후 다시 열어 주세요."}</p>;
  if (entry.status === "running") return <p className="lv-state" role="status"><strong>만드는 중입니다</strong>만들어지면 여기에 보입니다.</p>;
  return <p className="lv-state"><strong>아직 만들지 않았습니다</strong>교수님이 요약을 게시하면 함께 만들어집니다.</p>;
}

function ConceptList({ concepts, onJump, locate = pageAt }: { concepts: LessonConcept[]; onJump: (page: number) => void; locate?: Locate }) {
  return (
    <dl className="lv-concepts">
      {concepts.map((concept, index) => (
        <div key={index}>
          <dt>{inline(concept.term)}</dt>
          <dd>
            {inline(concept.meaning)}
            {concept.page != null ? <Jump to={concept.page} {...locate(concept.page, concept.term)} onJump={onJump} /> : null}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function Quiz({ quiz, onJump, locate = pageAt }: { quiz: LessonQuizItem[]; onJump: (page: number) => void; locate?: Locate }) {
  // 문제 번호 → 고른 보기. 한 번 고르면 잠근다.
  const [answers, setAnswers] = useState<Record<number, number>>({});
  const solved = Object.keys(answers).length;
  const right = quiz.filter((item, index) => answers[index] === item.answer).length;
  return (
    <>
      <div className="lv-quiz-head">
        <span>{`${quiz.length}문제 중 ${solved}문제 풀었습니다${solved ? ` · 맞힌 문제 ${right}` : ""}`}</span>
        {solved ? <button type="button" className="lv-text-btn" onClick={() => setAnswers({})}>다시 풀기</button> : null}
      </div>
      <ol className="lv-quiz">
        {quiz.map((item, qi) => {
          const chosen = answers[qi];
          const done = chosen !== undefined;
          return (
            <li key={qi}>
              <p className="lv-quiz-q">{`${qi + 1}. `}{inline(item.question)}</p>
              <div className="lv-quiz-options">
                {item.options.map((option, oi) => {
                  const mark = done && oi === item.answer ? "is-correct" : done && oi === chosen ? "is-wrong" : "";
                  return (
                    <button key={oi} type="button" className={mark} aria-disabled={done ? "true" : undefined} aria-pressed={done && oi === chosen ? "true" : undefined}
                      onClick={() => { if (!done) setAnswers((prev) => ({ ...prev, [qi]: oi })); }}>
                      {inline(option)}
                    </button>
                  );
                })}
              </div>
              {done ? (
                <p className={`lv-quiz-explain ${chosen === item.answer ? "is-correct" : "is-wrong"}`} role="status">
                  <strong>{chosen === item.answer ? "정답" : "오답"}</strong>
                  {inline(item.explanation)}
                  {item.page != null ? <Jump to={item.page} {...locate(item.page, "해설")} onJump={onJump} /> : null}
                </p>
              ) : null}
            </li>
          );
        })}
      </ol>
    </>
  );
}

function Flashcards({ concepts, locate = pageAt }: { concepts: LessonConcept[]; locate?: Locate }) {
  const [card, setCard] = useState(0);
  const [flipped, setFlipped] = useState(false);
  if (!concepts.length) return <p className="lv-state">핵심 개념이 없어 카드를 만들 수 없습니다.</p>;
  const item = concepts[Math.min(card, concepts.length - 1)];
  const move = (next: number) => {
    setCard(next);
    setFlipped(false);
  };
  return (
    <>
      <div className={`lv-card${flipped ? " is-back" : ""}`} aria-live="polite">
        <span>{`${card + 1} / ${concepts.length}`}</span>
        <strong>{inline(flipped ? item.meaning : item.term)}</strong>
        <small>{flipped ? (item.page != null ? `뜻 · ${locate(item.page, "뜻").text}` : "뜻") : "용어"}</small>
      </div>
      <div className="lv-card-actions">
        <button type="button" className="btn btn-secondary" disabled={card === 0} onClick={() => move(card - 1)}>이전</button>
        <button type="button" className="btn btn-primary" onClick={() => setFlipped(!flipped)}>{flipped ? "용어 보기" : "뜻 보기"}</button>
        <button type="button" className="btn btn-secondary" disabled={card >= concepts.length - 1} onClick={() => move(card + 1)}>다음</button>
      </div>
    </>
  );
}
