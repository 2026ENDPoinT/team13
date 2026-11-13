import type { ReactNode } from "react";
import { Link } from "../router.tsx";
import { Icon } from "../ui/icon.tsx";
import { TYPE_COLOR, typeLabel, dday, activityHref } from "./format.ts";
import type { Activity } from "../data.ts";
import type { ScreenCtx } from "../types.ts";

interface CompactActivityRowProps {
  id: string;
  type: string;
  title: string | null | undefined;
  label: string;
  href: string | null;
  done: boolean;
  status: string;
  detail?: string;
  /** `href`가 학교 e-Class 주소다: 라우터 밖의 새 탭 링크로 열고 그것을 표시와 접근 가능한 이름으로 알린다. */
  external?: boolean;
}

/** 양쪽 데이터 어댑터가 공유하는 아이콘·제목·상태의 3열 행. */
export function CompactActivityRow({ id, type, title, label, href, done, status, detail, external = false }: CompactActivityRowProps): ReactNode {
  // A row without an address stays a plain disabled row, so it carries no "leaves for e-Class" marker either.
  const leaves = external && Boolean(href);
  const children = (
    <>
      <span className="swatch" style={{ background: TYPE_COLOR[type as keyof typeof TYPE_COLOR] || "var(--surface-2)" }} />
      <span className="activity-copy">
        <b title={title || undefined}>{title}</b>
        <div className="tiny" title={detail}>{label}{leaves ? <span className="activity-ext"> · e-Class<Icon name="ext" /></span> : null}</div>
      </span>
      {done ? <span className="check" title={detail || status} aria-label={status || "완료"}>✓</span> : <span className="tiny activity-status" title={detail || status}>{status}</span>}
    </>
  );
  const props = { className: "activity", "data-type": type, "data-row": id, "data-activity": id, title: detail };
  if (href && leaves) {
    const name = [title, label, done ? status || "완료" : status].filter(Boolean).join(", ");
    return <a {...props} href={href} target="_blank" rel="noopener noreferrer" data-external="eclass" aria-label={`${name} — 학교 e-Class에서 열기(새 탭)`}>{children}</a>;
  }
  return href ? <Link {...props} href={href}>{children}</Link> : <div {...props} aria-disabled="true">{children}</div>;
}

export function activityRow(activity: Activity, ctx: ScreenCtx, courseId?: string | null): ReactNode {
  const href = activityHref(activity, courseId);
  const done = activity.status === "done" || (activity.type === "report" && Boolean(ctx.state.submissions[activity.id]));
  return (
    <CompactActivityRow
      key={activity.id}
      id={activity.id}
      type={activity.type}
      title={activity.title}
      label={typeLabel(activity.type)}
      href={href}
      done={done}
      status={done ? "완료" : dday(activity.due)}
    />
  );
}
