// One attached file as a list row: the file-type icon and name/size on the left, icon buttons on the right
// (미리보기, 내려받기, and — for PDFs that can go to the study room — the blue 학습실 upload button).
// Used by the material detail (server, school e-Class copy, sample mode).
import type { ReactNode } from "react";
import { Icon } from "../ui/icon.tsx";
import { Link } from "../router.tsx";

/** "pdf" from an explicit extension, else from the name's last dot (".pdf" and "PDF" also work). */
export function extensionOf(name: string | null | undefined, extension?: string | null): string {
  const raw = String(extension || String(name || "").split(".").pop() || "");
  return raw.replace(/^\./, "").slice(0, 4).toUpperCase();
}

/** A document with the file type on its band: red for PDF, neutral for the rest. */
export function FileTypeIcon({ extension }: { extension: string }) {
  return (
    <svg className={`attach-type${extension === "PDF" ? " is-pdf" : ""}`} viewBox="0 0 32 36" width="32" height="36" aria-hidden="true" focusable="false">
      <path className="attach-type__page" d="M7 1.5h13l7.5 7.5v24.5a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 33.5v-30A1.5 1.5 0 0 1 7 1.5Z" />
      <path className="attach-type__fold" d="M20 1.5V9h7.5" />
      <rect className="attach-type__band" x="1" y="19" width="23" height="10" rx="1.5" />
      <text className="attach-type__label" x="12.5" y="26.4" textAnchor="middle">{extension || "FILE"}</text>
    </svg>
  );
}

export interface AttachmentRowProps {
  name: string;
  /** From the file's own extension field; the name's suffix is the fallback. */
  extension?: string | null;
  /** Size (and anything else short) after the name. */
  meta?: string;
  /** `data-file` of the row. */
  fileKey?: string;
  /** Address of the STack preview; no address, no button. */
  preview?: string;
  /** The "내려받기" icon control (`downloadControl(…, "icon")`). */
  download?: ReactNode;
  /** The "학습실에 저장" icon control (`<SaveToStudy variant="icon" />`). */
  study?: ReactNode;
}

export function AttachmentRow({ name, extension, meta, fileKey, preview, download, study }: AttachmentRowProps) {
  return (
    <article className="attach-row" data-file={fileKey}>
      <FileTypeIcon extension={extensionOf(name, extension)} />
      <div className="attach-main">
        <b className="attach-name">{name}</b>
        {meta ? <span className="attach-meta">{meta}</span> : null}
      </div>
      <div className="attach-actions">
        {preview ? <Link className="icon-btn" href={preview} aria-label={`${name} 미리보기`} title="미리보기"><Icon name="search" /></Link> : null}
        {download}
        {study}
      </div>
    </article>
  );
}
