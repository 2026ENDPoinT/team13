// Page-by-page summary view for server PDFs (student study room and professor
// preview). Summary text is model output: it is turned into React elements and
// text only — never innerHTML. Links are shown as "text (url)" plain text,
// $…$ math stays literal text.
import { useEffect, useRef, useState, type ReactNode } from "react";

const TAGS: Record<string, string> = { core: "핵심", support: "보충", background: "배경", meta: "안내" };

// `code` | $$math$$ | $math$ | **bold** | *italic* | [text](url)
const INLINE = /(`[^`\n]+`)|(\$\$[^$]+\$\$|\$[^$\n]+\$)|(\*\*(?=\S)[\s\S]+?\*\*)|(\*(?=[^\s*])[^*\n]+?\*)|(\[[^\]\n]+\]\([^)\s]+\))/g;

/** Inline markdown (`code`, **bold**, *italic*, [text](url) as plain text) → React nodes. */
export function inline(text: unknown): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  const source = String(text);
  for (const match of source.matchAll(INLINE)) {
    const at = match.index ?? 0;
    if (at > last) out.push(source.slice(last, at));
    const [token, code, math, bold, italic, link] = match;
    if (code) out.push(<code key={at}>{code.slice(1, -1)}</code>);
    else if (math) out.push(math);
    else if (bold) out.push(<strong key={at}>{inline(bold.slice(2, -2))}</strong>);
    else if (italic) out.push(<em key={at}>{inline(italic.slice(1, -1))}</em>);
    else if (link) {
      const close = link.indexOf("](");
      out.push(`${link.slice(1, close)} (${link.slice(close + 2, -1)})`);
    } else out.push(token);
    last = at + token.length;
  }
  if (last < source.length) out.push(source.slice(last));
  return out;
}

function lines(rows: string[]): ReactNode[] {
  return rows.map((row, index) => [index ? <br key="br" /> : null, ...inline(row)]);
}

type Block =
  | { kind: "p"; rows: string[] }
  | { kind: "heading"; text: string }
  | { kind: "ul" | "ol"; start: string | null; items: string[] };

/** Minimal markdown → React: paragraphs, line breaks, lists, **bold**, *italic*, `code`. */
export function markdown(text: unknown): ReactNode {
  const rows = String(text ?? "").replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let list: Extract<Block, { kind: "ul" | "ol" }> | null = null;
  const flushParagraph = () => {
    if (paragraph.length) blocks.push({ kind: "p", rows: paragraph });
    paragraph = [];
  };
  const flushList = () => {
    if (list) blocks.push(list);
    list = null;
  };
  for (const raw of rows) {
    const row = raw.trimEnd();
    const bullet = /^\s*[-*+]\s+(.*)$/.exec(row);
    const number = bullet ? null : /^\s*(\d{1,3})[.)]\s+(.*)$/.exec(row);
    if (!row.trim()) {
      flushParagraph();
      flushList();
      continue;
    }
    if (bullet || number) {
      flushParagraph();
      const kind = bullet ? "ul" : "ol";
      if (!list || list.kind !== kind) {
        flushList();
        list = { kind, start: number && number[1] !== "1" ? number[1] : null, items: [] };
      }
      list.items.push(bullet ? bullet[1] : number ? number[2] : "");
      continue;
    }
    flushList();
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(row);
    if (heading) {
      flushParagraph();
      blocks.push({ kind: "heading", text: heading[1] });
      continue;
    }
    paragraph.push(row.trim());
  }
  flushParagraph();
  flushList();
  return (
    <div className="md">
      {blocks.map((block, index) => {
        if (block.kind === "p") return <p key={index}>{lines(block.rows)}</p>;
        if (block.kind === "heading") return <p key={index} className="md-heading"><strong>{inline(block.text)}</strong></p>;
        const items = block.items.map((item, at) => <li key={at}>{inline(item)}</li>);
        return block.kind === "ul"
          ? <ul key={index}>{items}</ul>
          : <ol key={index} start={block.start ? Number(block.start) : undefined}>{items}</ol>;
      })}
    </div>
  );
}

/** Page image `NNN.jpg` under a server image base ("/doc/<hex>/"). */
export function pageFile(base: string, page: number): string {
  return `${base}${String(page).padStart(3, "0")}.jpg`;
}

/** One step of the summary story (`story[]`). */
export interface SummaryBeat {
  from: number;
  to: number;
  title?: string;
  problem?: string;
  answer?: string;
  tradeoff?: string;
  extra?: string;
  bridge?: string;
}

/** One page of the summary (`slides[]`). */
export interface SummarySlide {
  page: number;
  tag?: string;
  title?: string;
  explain?: string;
}

export interface PageSummary {
  question?: string;
  story?: SummaryBeat[];
  slides?: SummarySlide[];
  /** page → reason it needs review. */
  review?: Record<string, string | null> | null;
  warnings?: (string | null)[];
}

export interface SummaryViewOptions {
  /** "/doc/<hex>/" or null. */
  pageImageBase?: string | null;
  /** Refetches the summary (renews image cookies) and resolves to the new base. */
  refreshImages?: () => Promise<string | null> | string | null;
}

function SummaryPart({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <div className="page-summary__part">
      <span className="page-summary__label">{label}</span>
      {markdown(value)}
    </div>
  );
}

function BeatView({ beat }: { beat: SummaryBeat }) {
  const range = beat.from === beat.to ? `${beat.from}쪽` : `${beat.from}–${beat.to}쪽`;
  return (
    <li className="page-summary__beat">
      <h4><span className="page-summary__range">{range}</span>{" "}{inline(beat.title || "")}</h4>
      <SummaryPart label="질문" value={beat.problem} />
      <SummaryPart label="설명" value={beat.answer} />
      <SummaryPart label="장단점" value={beat.tradeoff} />
      <SummaryPart label="더 알아보기" value={beat.extra} />
      <SummaryPart label="다음으로" value={beat.bridge} />
    </li>
  );
}

/**
 * summary: { question, story: Beat[], slides: Slide[], review?, warnings? }
 * options.pageImageBase: "/doc/<hex>/" or null.
 * options.refreshImages(): refetches the summary (renews image cookies) and resolves
 *   to the new base; every image retries once after it.
 */
export function summaryView(summary: PageSummary, options: SummaryViewOptions = {}): ReactNode {
  return <SummaryView summary={summary} options={options} />;
}

function SummaryView({ summary, options }: { summary: PageSummary; options: SummaryViewOptions }) {
  const refreshing = useRef<Promise<string | null> | null>(null);
  const slides = Array.isArray(summary.slides) ? [...summary.slides].sort((a, b) => a.page - b.page) : [];
  const story = Array.isArray(summary.story) ? summary.story : [];
  const review = summary.review && typeof summary.review === "object" ? summary.review : null;
  const warnings = Array.isArray(summary.warnings) ? summary.warnings.filter((text): text is string => Boolean(text)) : [];
  const { refreshImages } = options;
  /** One shared summary refetch for every failing image. */
  const refreshed = (): Promise<string | null> => {
    if (!refreshImages) return Promise.resolve(null);
    refreshing.current ??= Promise.resolve().then(refreshImages).catch(() => null);
    return refreshing.current;
  };
  const reviewRows = review
    ? Object.entries(review).filter(([, reason]) => reason).sort((a, b) => Number(a[0]) - Number(b[0]))
    : [];
  return (
    <div className="page-summary">
      {warnings.length ? (
        <section className="page-summary__warnings" aria-label="확인할 점">
          <h4>확인할 점</h4>
          <ul>{warnings.map((text, index) => <li key={index}>{inline(text)}</li>)}</ul>
        </section>
      ) : null}
      {reviewRows.length ? (
        <section className="page-summary__warnings" aria-label="다시 확인할 쪽">
          <h4>다시 확인할 쪽</h4>
          <ul>{reviewRows.map(([page, reason]) => <li key={page}><strong>{`${page}쪽 `}</strong>{String(reason)}</li>)}</ul>
        </section>
      ) : null}
      {summary.question ? (
        <section className="page-summary__question" aria-label="핵심 질문">
          <span className="page-summary__label">핵심 질문</span>
          {markdown(summary.question)}
        </section>
      ) : null}
      {story.length ? (
        <section className="page-summary__story" aria-label="흐름">
          <h4>흐름</h4>
          <ol>{story.map((beat, index) => <BeatView key={index} beat={beat} />)}</ol>
        </section>
      ) : null}
      {slides.length ? (
        <section className="page-summary__slides" aria-label="쪽별 설명">
          {slides.map((slide, index) => {
            const note = review && review[String(slide.page)];
            const tag = slide.tag && TAGS[slide.tag] ? slide.tag : "core";
            return (
              <article key={`${slide.page}-${index}`} className={`page-summary__slide tag-${tag}`}>
                <header>
                  <span className="page-summary__page">{`${slide.page}쪽`}</span>
                  <span className="page-summary__tag">{TAGS[tag]}</span>
                  <h4>{inline(slide.title || "")}</h4>
                </header>
                {options.pageImageBase ? <PageImage page={slide.page} base={options.pageImageBase} refreshed={refreshed} /> : null}
                {markdown(slide.explain || "")}
                {note ? <p className="page-summary__review" role="note"><strong>검토 필요 · </strong>{note}</p> : null}
              </article>
            );
          })}
        </section>
      ) : null}
    </div>
  );
}

/** A page image that retries once after the summary refetch, then says it could not load. */
function PageImage({ page, base, refreshed }: { page: number; base: string; refreshed: () => Promise<string | null> }) {
  const [src, setSrc] = useState(() => pageFile(base, page));
  const [failed, setFailed] = useState(false);
  const retried = useRef(false);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  if (failed) return <p className="page-summary__note">{`${page}쪽 이미지를 불러오지 못했습니다.`}</p>;
  const onError = () => {
    if (retried.current) {
      setFailed(true);
      return;
    }
    retried.current = true;
    void refreshed().then((fresh) => {
      if (!live.current) return;
      if (fresh) setSrc(pageFile(fresh, page));
      else setFailed(true);
    });
  };
  return <img className="page-summary__image" src={src} loading="lazy" alt={`${page}쪽 이미지`} onError={onError} />;
}
