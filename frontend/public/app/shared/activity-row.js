import { el } from "../dom.js";
import { hrefFor } from "../manifest.js";
import { TYPE_COLOR, typeLabel, dday, activityHref } from "./format.js";

export function activityRow(activity, ctx, courseId) {
  const href = activityHref(activity, courseId);
  const done = activity.status === "done" || (activity.type === "report" && ctx.state.submissions[activity.id]);
  return el("a", {
    class: "activity",
    href,
    "data-go": href,
    "data-type": activity.type,
    "data-row": activity.id,
    "data-activity": activity.id,
  },
    el("span", { class: "swatch", style: `background:${TYPE_COLOR[activity.type] || "var(--surface-2)"}` }),
    el("span", {}, el("b", { text: activity.title }), el("div", { class: "tiny", text: typeLabel(activity.type) })),
    done ? el("span", { class: "check", text: "✓" }) : el("span", { class: "tiny", text: dday(activity.due) }),
  );
}

export { hrefFor };
