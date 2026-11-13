import type { RecordingSummaryResult } from "../../shared/recording-study.ts";
import { clock } from "./recording-study.tsx";

const escapeHtml = (text: string) => text.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`);

const STYLE = `
@page { size: A4; margin: 18mm 16mm; }
* { box-sizing: border-box; }
body { margin: 0; color: #111; font: 11pt/1.65 "Pretendard", "Noto Sans KR", "Malgun Gothic", "Apple SD Gothic Neo", sans-serif; }
h1 { margin: 0 0 4pt; font-size: 19pt; line-height: 1.3; }
.meta { margin: 0 0 14pt; color: #666; font-size: 9.5pt; }
.overview { margin: 0 0 16pt; padding: 10pt 12pt; background: #f3f4f6; border-radius: 6pt; }
h2 { margin: 18pt 0 6pt; font-size: 13.5pt; break-after: avoid; }
h2 time, li time { color: #666; font-weight: 400; font-size: 9.5pt; margin-right: 6pt; }
ul { margin: 0; padding-left: 16pt; }
li { margin: 0 0 4pt; break-inside: avoid; }
table { width: 100%; margin: 8pt 0; border-collapse: collapse; font-size: 10pt; break-inside: avoid; }
th, td { padding: 4pt 7pt; border: 1px solid #bbb; text-align: left; vertical-align: top; }
th { background: #eee; }
.note { margin-top: 18pt; color: #555; font-size: 9.5pt; }
`;

/** 요약 PDF로 인쇄할 완결된 HTML 문서. 모든 서버 텍스트는 이스케이프해 일반 텍스트로만 넣는다. */
export function summaryDocument(title: string, summary: RecordingSummaryResult): string {
  const sections = summary.sections.map((section) => {
    const points = section.points.map((point) =>
      `<li><time>${clock(point.at)}</time><strong>${escapeHtml(point.term)}</strong> ${escapeHtml(point.text)}</li>`).join("");
    const table = section.table
      ? `<table><thead><tr>${section.table.head.map((cell) => `<th>${escapeHtml(cell)}</th>`).join("")}</tr></thead>`
        + `<tbody>${section.table.rows.map((row) => `<tr>${row.map((cell) => `<td>${escapeHtml(cell)}</td>`).join("")}</tr>`).join("")}</tbody></table>`
      : "";
    return `<section><h2><time>${clock(section.at)}</time>${escapeHtml(section.title)}</h2>${points ? `<ul>${points}</ul>` : ""}${table}</section>`;
  }).join("");
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${escapeHtml(title)} 요약</title><style>${STYLE}</style></head><body>`
    + `<h1>${escapeHtml(title)}</h1><p class="meta">요약</p>`
    + (summary.overview ? `<p class="overview">${escapeHtml(summary.overview)}</p>` : "")
    + sections
    + (summary.note ? `<p class="note">${escapeHtml(summary.note)}</p>` : "")
    + `</body></html>`;
}

/**
 * 보이지 않는 iframe에 요약을 그려 브라우저의 인쇄 창을 연다("PDF로 저장" 선택).
 * 외부 라이브러리 없이 한글 글꼴·텍스트 선택을 그대로 살리려는 선택이며, 파일은 사용자가 저장 창에서 만든다.
 */
export function printSummaryPdf(title: string, summary: RecordingSummaryResult): void {
  const frame = document.createElement("iframe");
  frame.setAttribute("aria-hidden", "true");
  frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden";
  const cleanup = () => frame.remove();
  frame.addEventListener("load", () => {
    const view = frame.contentWindow;
    if (!view) return cleanup();
    view.addEventListener("afterprint", cleanup);
    view.focus();
    view.print();
  }, { once: true });
  frame.srcdoc = summaryDocument(title, summary);
  document.body.appendChild(frame);
}
