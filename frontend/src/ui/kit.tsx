// Shared building blocks. Class names are the ones the stylesheets (ui/components.css, …) style.
import { useEffect, useRef, type ButtonHTMLAttributes, type ReactNode, type Ref } from "react";
import { getState } from "../store.ts";
import { Link, closeDialog } from "../router.tsx";
import type { Course } from "../data.ts";

export type ButtonKind = "primary" | "secondary" | "ghost" | "danger";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  kind?: ButtonKind;
  ref?: Ref<HTMLButtonElement>;
}

export function Button({ kind = "secondary", className, type = "button", ...rest }: ButtonProps) {
  return <button type={type} className={["btn", `btn-${kind}`, className].filter(Boolean).join(" ")} {...rest} />;
}

export function CourseName({ course, as: Tag = "b", title }: { course: Course; as?: "b" | "strong" | "h2" | "h3"; title?: string }) {
  return (
    <div className="course-name">
      <Tag className="course-title">{title || course.cardTitle || course.ko}</Tag>
      <span className="course-prof">{course.instructor}</span>
    </div>
  );
}

/**
 * A labelled control. `error` shows under it; without one, `name` shows the error that `showErrors()`
 * put in `state.formError[name]`.
 */
export function Field({ label, name, error, children }: { label: ReactNode; name?: string; error?: string; children: ReactNode }) {
  const shown = error ?? (name ? getState().formError[name] : undefined);
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {shown ? <span className="err">{shown}</span> : null}
    </label>
  );
}

function Actions({ actions }: { actions?: ReactNode }) {
  return actions ? <div className="btn-row">{actions}</div> : null;
}

export function EmptyState({ title, body, actions }: { title: string; body?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="empty" role="status">
      <strong>{title}</strong>
      {body ? <p>{body}</p> : null}
      <Actions actions={actions} />
    </div>
  );
}

export function LoadingState({ title = "불러오는 중" }: { title?: string }) {
  return (
    <div className="empty state-loading" role="status" aria-busy="true">
      <span className="spinner" aria-hidden="true" />
      <strong>{title}</strong>
    </div>
  );
}

export function ErrorState({ title, body, actions }: { title: string; body?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="empty state-error" role="alert">
      <strong>{title}</strong>
      {body ? <p>{body}</p> : null}
      <Actions actions={actions} />
    </div>
  );
}

export type BadgeTone = "neutral" | "success" | "danger";

export function Badge({ tone = "neutral", children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export interface TabItem {
  label: string;
  href?: string;
  on?: boolean;
}

export function Tabs({ items }: { items: TabItem[] }) {
  return (
    <div className="tabs" role="tablist">
      {items.map((item) => item.href
        ? <Link key={item.label} href={item.href} className={item.on ? "on" : ""} role="tab" aria-selected={item.on ? "true" : "false"}>{item.label}</Link>
        : <span key={item.label} className={item.on ? "on" : ""} role="tab" aria-selected={item.on ? "true" : "false"}>{item.label}</span>)}
    </div>
  );
}

export function ListRow({ href, title, meta, detail }: { href?: string; title: ReactNode; meta?: ReactNode; detail?: ReactNode }) {
  const body = (
    <>
      <span><b>{title}</b>{detail ? <div className="tiny">{detail}</div> : null}</span>
      {meta ? <span className="tiny">{meta}</span> : null}
    </>
  );
  return href ? <Link className="list-row" href={href}>{body}</Link> : <div className="list-row">{body}</div>;
}

/** A data table; on narrow screens `cards` stacks each row (cells keep their header as `data-label`). */
export function Table({ headers, rows, layout = "cards", label }: { headers: string[]; rows: ReactNode[][]; layout?: "cards" | "scroll"; label?: string }) {
  return (
    <div className={`table-wrap table-${layout}`}>
      <table className="grid" aria-label={label}>
        <thead><tr>{headers.map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
        <tbody>
          {rows.map((cells, row) => (
            <tr key={row}>{cells.map((cell, index) => <td key={index} data-label={headers[index] ?? ""}>{cell}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Breadcrumbs({ items }: { items: { label: string; href?: string }[] }) {
  return (
    <nav className="crumbs" aria-label="현재 위치">
      <ol>
        {items.map((item, index) => {
          const last = index === items.length - 1;
          return (
            <li key={`${index}-${item.label}`}>
              {item.href && !last ? <Link href={item.href}>{item.label}</Link> : <span aria-current={last ? "page" : undefined}>{item.label}</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export function Pagination({ page, pages, hrefForPage }: { page: number; pages: number; hrefForPage: (page: number) => string }) {
  if (!pages || pages <= 1) return null;
  const shown: (number | "…")[] = [];
  for (let n = 1; n <= pages; n += 1) {
    if (n === 1 || n === pages || Math.abs(n - page) <= 1) shown.push(n);
    else if (shown[shown.length - 1] !== "…") shown.push("…");
  }
  return (
    <nav className="pager" aria-label="페이지">
      <ol>
        {page > 1 ? <li><Link href={hrefForPage(page - 1)} className="pager-link">이전</Link></li> : null}
        {shown.map((n, index) => n === "…"
          ? <li key={`gap-${index}`}><span className="pager-gap">…</span></li>
          : <li key={n}><Link href={hrefForPage(n)} className={`pager-link${n === page ? " on" : ""}`} aria-current={n === page ? "page" : undefined}>{String(n)}</Link></li>)}
        {page < pages ? <li><Link href={hrefForPage(page + 1)} className="pager-link">다음</Link></li> : null}
      </ol>
    </nav>
  );
}

export interface DialogProps {
  title: string;
  children: ReactNode;
  wide?: boolean;
  className?: string;
  label?: string;
  /** Default: close the `?pop=` window (router `closeDialog`). */
  onClose?: () => void;
}

/** A modal window. The backdrop and × close it; Escape closes `?pop=` windows (App). Focus moves into it on open. */
export function Dialog({ title, children, wide, className, label, onClose = closeDialog }: DialogProps) {
  const card = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = card.current;
    if (!node || node.contains(document.activeElement)) return;
    node.querySelector<HTMLElement>("button, [href], input, select, textarea")?.focus();
  }, []);
  return (
    <div className="modal-back" onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div ref={card} className={["modal", wide ? "wide" : "", className].filter(Boolean).join(" ")} role="dialog" aria-modal="true" aria-label={label || title}>
        <div className="modal-h">
          <h2>{title}</h2>
          <button type="button" className="x" aria-label="닫기" onClick={onClose}>×</button>
        </div>
        <div className="modal-b">{children}</div>
      </div>
    </div>
  );
}

