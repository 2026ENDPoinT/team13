// A server page summary (a published one, or a job's candidate under review) as the `LessonViewer` document, so the
// student study room and the professor's review screen show the same viewer from the same mapping.
import type { LessonDoc } from "./lesson-viewer.tsx";
import type { PageSummary } from "./page-summary.tsx";

export interface SummaryLessonInput {
  /** The viewer's document id: changing it resets the page, folds and quiz answers. */
  id: string;
  title: string;
  /** The file's page count when known; otherwise the last page the summary mentions. */
  pageCount: number | null | undefined;
  summary: PageSummary;
  /** "/doc/<hex>/" for the page images, or null to show the summary without them. */
  pageImageBase: string | null | undefined;
  /** Reissues the image cookie and resolves to the (usually unchanged) base, or null (page-image-retry.ts). */
  refreshImages?: () => Promise<string | null>;
  /** The file overview Markdown, when there is one. */
  overview?: string | null;
  /** The source line under the title. */
  note: string;
}

export function summaryLessonDoc({ id, title, pageCount, summary, pageImageBase, refreshImages, overview = null, note }: SummaryLessonInput): LessonDoc {
  const pages = [
    ...(Array.isArray(summary.slides) ? summary.slides : []).map((slide) => slide.page),
    ...(Array.isArray(summary.story) ? summary.story : []).map((beat) => beat.to),
  ].filter((page) => Number.isFinite(page));
  return {
    id,
    title,
    pageCount: pageCount || Math.max(0, ...pages),
    source: pageImageBase ? { kind: "images", base: pageImageBase, aspect: null, refresh: refreshImages } : null,
    summary,
    overview,
    note,
  };
}
