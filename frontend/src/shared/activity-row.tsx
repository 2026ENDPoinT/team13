import type { ReactNode } from "react";
import { Link } from "../router.tsx";
import { TYPE_COLOR, typeLabel, dday, activityHref } from "./format.ts";
import type { Activity } from "../data.ts";
import type { ScreenCtx } from "../types.ts";

export function activityRow(activity: Activity, ctx: ScreenCtx, courseId?: string | null): ReactNode {
  const href = activityHref(activity, courseId);
  const done = activity.status === "done" || (activity.type === "report" && Boolean(ctx.state.submissions[activity.id]));
  return (
    <Link
      key={activity.id}
      className="activity"
      href={href}
      data-type={activity.type}
      data-row={activity.id}
      data-activity={activity.id}
    >
      <span className="swatch" style={{ background: TYPE_COLOR[activity.type] || "var(--surface-2)" }} />
      <span><b>{activity.title}</b><div className="tiny">{typeLabel(activity.type)}</div></span>
      {done ? <span className="check">✓</span> : <span className="tiny">{dday(activity.due)}</span>}
    </Link>
  );
}
