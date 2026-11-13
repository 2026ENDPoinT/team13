// Page-by-page summary view for server PDFs (student study room and professor
// preview). Summary text is model output: it is turned into DOM nodes with el()
// and text nodes only — never innerHTML. Links are shown as "text (url)" plain
// text, $…$ math stays literal text.
import { el } from "../dom.js";

const TAGS = { core: "핵심", support: "보충", background: "배경", meta: "안내" };

// `code` | $$math$$ | $math$ | **bold** | *italic* | [text](url)
const INLINE = /(`[^`\n]+`)|(\$\$[^$]+\$\$|\$[^$\n]+\$)|(\*\*(?=\S)[\s\S]+?\*\*)|(\*(?=[^\s*])[^*\n]+?\*)|(\[[^\]\n]+\]\([^)\s]+\))/g;

function inline(text) {
  const out = [];
  let last = 0;
  const source = String(text);
  for (const match of source.matchAll(INLINE)) {
    if (match.index > last) out.push(document.createTextNode(source.slice(last, match.index)));
    const [token, code, math, bold, italic, link] = match;
    if (code) out.push(el("code", { text: code.slice(1, -1) }));
    else if (math) out.push(document.createTextNode(math));
    else if (bold) out.push(el("strong", {}, ...inline(bold.slice(2, -2))));
    else if (italic) out.push(el("em", {}, ...inline(italic.slice(1, -1))));
    else if (link) {
      const close = link.indexOf("](");
      out.push(document.createTextNode(`${link.slice(1, close)} (${link.slice(close + 2, -1)})`));
    } else out.push(document.createTextNode(token));
    last = match.index + token.length;
  }
  if (last < source.length) out.push(document.createTextNode(source.slice(last)));
  return out;
}

function lines(nodes, rows) {
  rows.forEach((row, index) => {
    if (index) nodes.push(el("br"));
    nodes.push(...inline(row));
  });
  return nodes;
}

/** Minimal markdown → DOM: paragraphs, line breaks, lists, **bold**, *italic*, `code`. */
export function markdown(text) {
  const root = el("div", { class: "md" });
  const rows = String(text ?? "").replace(/\r\n?/g, "\n").split("\n");
  let paragraph = [];
  let list = null;
  const flushParagraph = () => {
    if (paragraph.length) root.append(el("p", {}, ...lines([], paragraph)));
    paragraph = [];
  };
  const flushList = () => {
    if (list) root.append(list.node);
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
        list = { kind, node: el(kind, number && number[1] !== "1" ? { start: number[1] } : {}) };
      }
      list.node.append(el("li", {}, ...inline(bullet ? bullet[1] : number[2])));
      continue;
    }
    flushList();
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(row);
    if (heading) {
      flushParagraph();
      root.append(el("p", { class: "md-heading" }, el("strong", {}, ...inline(heading[1]))));
      continue;
    }
    paragraph.push(row.trim());
  }
  flushParagraph();
  flushList();
  return root;
}

function pageFile(base, page) {
  return `${base}${String(page).padStart(3, "0")}.jpg`;
}

function beatView(beat) {
  const range = beat.from === beat.to ? `${beat.from}쪽` : `${beat.from}–${beat.to}쪽`;
  const part = (label, value) => (value ? el("div", { class: "page-summary__part" },
    el("span", { class: "page-summary__label", text: label }), markdown(value)) : null);
  return el("li", { class: "page-summary__beat" },
    el("h4", {}, el("span", { class: "page-summary__range", text: range }), " ", ...inline(beat.title || "")),
    part("질문", beat.problem),
    part("설명", beat.answer),
    part("장단점", beat.tradeoff),
    part("더 알아보기", beat.extra),
    part("다음으로", beat.bridge),
  );
}

/**
 * summary: { question, story: Beat[], slides: Slide[], review?, warnings? }
 * options.pageImageBase: "/doc/<hex>/" or null.
 * options.refreshImages(): refetches the summary (renews image cookies) and resolves
 *   to the new base; every image retries once after it.
 */
export function summaryView(summary, options = {}) {
  const slides = Array.isArray(summary.slides) ? [...summary.slides].sort((a, b) => a.page - b.page) : [];
  const story = Array.isArray(summary.story) ? summary.story : [];
  const review = summary.review && typeof summary.review === "object" ? summary.review : null;
  const warnings = Array.isArray(summary.warnings) ? summary.warnings.filter(Boolean) : [];
  let refreshing = null;
  const refreshed = () => {
    if (!options.refreshImages) return Promise.resolve(null);
    refreshing ??= Promise.resolve().then(options.refreshImages).catch(() => null);
    return refreshing;
  };

  function pageImage(page) {
    if (!options.pageImageBase) return null;
    const img = el("img", { class: "page-summary__image", src: pageFile(options.pageImageBase, page), loading: "lazy", alt: `${page}쪽 이미지` });
    let retried = false;
    img.addEventListener("error", () => {
      if (retried) {
        img.replaceWith(el("p", { class: "page-summary__note", text: `${page}쪽 이미지를 불러오지 못했습니다.` }));
        return;
      }
      retried = true;
      refreshed().then((base) => {
        if (!img.isConnected) return;
        if (base) img.src = pageFile(base, page);
        else img.replaceWith(el("p", { class: "page-summary__note", text: `${page}쪽 이미지를 불러오지 못했습니다.` }));
      });
    });
    return img;
  }

  const reviewRows = review ? Object.entries(review).filter(([, reason]) => reason).sort((a, b) => Number(a[0]) - Number(b[0])) : [];
  return el("div", { class: "page-summary" },
    warnings.length ? el("section", { class: "page-summary__warnings", "aria-label": "확인할 점" },
      el("h4", { text: "확인할 점" }),
      el("ul", {}, ...warnings.map((text) => el("li", {}, ...inline(text)))),
    ) : null,
    reviewRows.length ? el("section", { class: "page-summary__warnings", "aria-label": "다시 확인할 쪽" },
      el("h4", { text: "다시 확인할 쪽" }),
      el("ul", {}, ...reviewRows.map(([page, reason]) => el("li", {}, el("strong", { text: `${page}쪽 ` }), String(reason)))),
    ) : null,
    summary.question ? el("section", { class: "page-summary__question", "aria-label": "핵심 질문" },
      el("span", { class: "page-summary__label", text: "핵심 질문" }),
      markdown(summary.question),
    ) : null,
    story.length ? el("section", { class: "page-summary__story", "aria-label": "흐름" },
      el("h4", { text: "흐름" }),
      el("ol", {}, ...story.map(beatView)),
    ) : null,
    slides.length ? el("section", { class: "page-summary__slides", "aria-label": "쪽별 설명" },
      ...slides.map((slide) => {
        const note = review && review[String(slide.page)];
        return el("article", { class: `page-summary__slide tag-${TAGS[slide.tag] ? slide.tag : "core"}` },
          el("header", {},
            el("span", { class: "page-summary__page", text: `${slide.page}쪽` }),
            el("span", { class: "page-summary__tag", text: TAGS[slide.tag] || "핵심" }),
            el("h4", {}, ...inline(slide.title || "")),
          ),
          pageImage(slide.page),
          markdown(slide.explain || ""),
          note ? el("p", { class: "page-summary__review", role: "note" }, el("strong", { text: "검토 필요 · " }), note) : null,
        );
      }),
    ) : null,
  );
}
