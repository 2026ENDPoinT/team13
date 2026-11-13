import type { ReactNode } from "react";
import { Link } from "../router.tsx";
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
}

/** 양쪽 데이터 어댑터가 공유하는 아이콘·제목·상태의 3열 행. */
export function CompactActivityRow({ id, type, title, label, href, done, status, detail }: CompactActivityRowProps): ReactNode {
  const children = (
    <>
      <span className="swatch" style={{ background: TYPE_COLOR[type as keyof typeof TYPE_COLOR] || "var(--surface-2)" }} />
      <span className="activity-copy"><b title={title || undefined}>{title}</b><div className="tiny" title={detail}>{label}</div></span>
      {done ? <span className="check" title={detail || status} aria-label={status || "완료"}>✓</span> : <span className="tiny activity-status" title={detail || status}>{status}</span>}
    </>
  );
  const props = { className: "activity", "data-type": type, "data-row": id, "data-activity": id, title: detail };
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
