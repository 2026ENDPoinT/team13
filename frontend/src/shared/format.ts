import { activities, notices } from "../data.ts";
import type { Activity, ActivityType, AppState, Notice } from "../data.ts";
import type { Screen } from "../types.ts";
import { currentRole, routeFor } from "./routes.ts";

export const TYPE_COLOR: Record<ActivityType, string> = {
  video: "var(--video)",
  zoom: "var(--zoom)",
  material: "var(--material)",
  report: "var(--assign)",
  test: "var(--exam)",
  survey: "var(--survey)",
  post: "var(--accent-soft)",
};

export function dday(due: string | null | undefined): string {
  if (!due) return "";
  const days = (dayMs(due.slice(0, 10)) - dayMs(TODAY)) / 86400000;
  if (Number.isNaN(days)) return "";
  if (days < 0) return "마감";
  if (days === 0) return "D-day";
  return `D-${days}`;
}

const TYPE_LABEL: Record<string, string> = { video: "영상", zoom: "Zoom", material: "자료", report: "과제", test: "시험", survey: "설문", post: "글" };
const TYPE_ICON: Record<string, string> = { video: "play", zoom: "users", material: "file", report: "pen", test: "check", survey: "chat" };
const STATUS_LABEL: Record<string, string> = { present: "출석", late: "지각", absent: "결석", excused: "유고", empty: "—" };

export function typeLabel(type: string): string {
  return TYPE_LABEL[type] || type;
}

export function typeIcon(type: string): string {
  return TYPE_ICON[type] || "file";
}

export function fmt(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function passFilter(activity: Activity, filter: string | null | undefined, state: AppState): boolean {
  if (!filter || filter === "all") return true;
  if (filter === "video") return activity.type === "video";
  if (filter === "material") return activity.type === "material";
  if (filter === "report") return activity.type === "report";
  if (filter === "test") return activity.type === "test";
  if (filter === "ongoing") return ["ongoing", "open", "scheduled"].includes(activity.status);
  if (filter === "missing") {
    if (activity.type === "report") return !state.submissions[activity.id];
    if (activity.type === "survey") {
      // AppState.surveys is untyped; a saved survey answer carries `done`.
      const survey = state.surveys[activity.id] as { done?: boolean } | undefined;
      return !(survey && survey.done);
    }
    return activity.status === "missing";
  }
  return true;
}

export function playerHref(activity: Pick<Activity, "id" | "courseId">, courseId?: string | null): string {
  const id = courseId || activity.courseId;
  return `/ilos/cls/st/activity/player_form.acl?n=${encodeURIComponent(activity.id)}&kj=${encodeURIComponent(id)}`;
}

export function activityHref(activity: Activity, courseId?: string | null): string {
  return routeFor(currentRole(), "activity", { activity, kj: courseId || activity.courseId });
}

export function attendStatus(state: AppState, person: string, week: number, section?: string | null): string {
  const key = `${person}:${section || "main"}:${week}`;
  const saved = state.attendance[key];
  if (saved) return String(saved);
  if (week === 3) return "late";
  if (week === 11) return "absent";
  if (week > 12) return "empty";
  return "present";
}

export function statusLabel(status: string): string {
  return STATUS_LABEL[status] || status;
}

/** A row of a sample board: a notice, a Q&A question, or a post. */
export interface BoardRow {
  id: string;
  title: string;
  body?: string;
  at?: string;
  date?: string;
  author?: string;
  reply?: string;
  courseId?: string;
  board?: string;
  scope?: string;
  audience?: string;
}

export function boardRows(screen: Screen, state: AppState): BoardRow[] {
  // AppState.posts/communityQna are untyped; saved posts and questions carry BoardRow fields.
  const posts = state.posts as BoardRow[];
  if (screen.board === "global") {
    return [...notices.filter((n) => n.scope === "global"), ...state.noticesExtra].filter((n: Notice) => !screen.audience || n.audience === screen.audience || n.audience === "all");
  }
  if (screen.board === "course") return notices.filter((n) => n.scope === (screen.course || "bi")).concat(state.noticesExtra.filter((n) => n.scope === (screen.course || "bi")));
  if (screen.board === "qna") return state.communityQna as BoardRow[];
  if (screen.board === "course-qna") {
    const seed: BoardRow[] = [{ id: "seed-q", title: "실습 데이터 형식", body: "CSV와 JSON 중 어떤 형식을 쓰면 되나요?", author: "김서연", at: "2026-11-03", reply: "CSV로 제출해 주세요.", courseId: "bi" }];
    return seed.concat(posts.filter((p) => p.board === "course-qna"));
  }
  if (screen.board === "open") return posts.filter((p) => p.board === "open");
  if (screen.board === "material") return [];
  if (screen.board === "survey") return [{ id: "campus", title: "캠퍼스 학습 환경 설문", at: "2026-11-01", body: "샘플 설문입니다." }];
  return [];
}

export function activityById(id: string | null | undefined): Activity | undefined {
  return activities.find((item) => item.id === id);
}

const PINNED_TODOS = ["lec-11-1", "lec-11-2", "rep-project", "quiz-final"];

/** Open to-dos in display order. The header badge, home hero, aside, and 할 일 dialog all count this list. */
export function openTodos(): Activity[] {
  const open = activities.filter((a) => ["missing", "ongoing", "scheduled"].includes(a.status));
  const head = PINNED_TODOS.map((id) => open.find((item) => item.id === id)).filter((item): item is Activity => Boolean(item));
  const tail = open.filter((item) => !PINNED_TODOS.includes(item.id))
    .sort((a, b) => String(a.due || "9999").localeCompare(String(b.due || "9999")));
  return [...head, ...tail];
}

/** Sample "today" and first day of the 2026 fall term; the home cards and the 할 일 dialog share them. */
export const TODAY = "2026-11-12";
const TERM_START = "2026-08-31";

const dayMs = (iso: string): number => Date.parse(`${iso}T00:00:00Z`);

function termWeek(iso: string): number {
  return Math.floor((dayMs(iso) - dayMs(TERM_START)) / (7 * 86400000)) + 1;
}

/** Last day the home hero still calls "마감이 얼마 남지 않은": today plus three days (D-0 to D-3). */
export const SOON_UNTIL = new Date(dayMs(TODAY) + 3 * 86400000).toISOString().slice(0, 10);

export interface MyDayGroup {
  label: string;
  items: Activity[];
  at?: string;
  week?: number;
}

export interface MyDayGroups {
  due: MyDayGroup & { at: string };
  soon: MyDayGroup;
  report: MyDayGroup;
  week: MyDayGroup & { week: number };
}

/**
 * The three 나의 하루 cards plus the hero's `soon` count. Each number is `items.length`, and the
 * 할 일 dialog opened with `todo-view=<key>` lists exactly these items.
 */
export function myDayGroups(state: AppState | null | undefined): MyDayGroups {
  const open = openTodos();
  const upcoming = open.filter((item) => item.due && item.due.slice(0, 10) >= TODAY);
  const nextDue = upcoming.map((item) => item.due || "").sort()[0] || "";
  const submitted = (state && state.submissions) || {};
  const week = termWeek(TODAY);
  return {
    due: { label: "가장 가까운 마감", at: nextDue, items: upcoming.filter((item) => item.due === nextDue) },
    soon: { label: "사흘 안 마감", items: upcoming.filter((item) => (item.due || "").slice(0, 10) <= SOON_UNTIL) },
    report: {
      label: "제출할 과제",
      items: open.filter((item) => item.type === "report" && !submitted[item.id])
        .sort((a, b) => (a.due || "").localeCompare(b.due || "")),
    },
    week: {
      label: "이번 주 학습 자료",
      week,
      items: open.filter((item) => item.week === week && (item.type === "video" || item.type === "material")),
    },
  };
}
