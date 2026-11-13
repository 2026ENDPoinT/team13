import { backendStatus, serverCourses } from "../backend.ts";
import { homeEntry, todoEntry, todoHref, todoKind, todoLabel, koDateTime, wallMs, type HomeData, type TodoItem } from "../home-remote.ts";
import { searchClassrooms } from "./classrooms.ts";

export const TODO_COMMAND = "할 일 목록 보여줘";

export interface ServerAction {
  item: TodoItem;
  href: string;
  title: string;
  meta: string;
  command: string;
  kind: string;
  label: string;
  urgent: boolean;
}

export function serverActions(data: HomeData | null | undefined, items: readonly TodoItem[], serverTime?: string): ServerAction[] {
  if (backendStatus() !== "ready") return [];
  const memberships = new Map(serverCourses().map(({ remote }) => [Number(remote.courseId), remote]));
  const now = wallMs(serverTime || data?.serverTime || data?.todo?.serverTime);
  return items.flatMap((item): ServerAction[] => {
    const course = memberships.get(Number(item.courseId));
    const href = course ? todoHref(item, data) : null;
    if (!course || !href) return [];
    const name = course.name || course.code || "이름 없는 과목";
    const deadline = wallMs(item.closeAt);
    return [{
      item, href, title: item.title,
      meta: [name, item.closeAt ? `${koDateTime(item.closeAt)} 마감` : ""].filter(Boolean).join(" · "),
      command: `${name} ${item.title} 열어줘`,
      kind: todoKind(item.type), label: todoLabel(item.type),
      urgent: Number.isFinite(now) && Number.isFinite(deadline) && deadline >= now && deadline - now <= 24 * 3600 * 1000,
    }];
  });
}

/** The input and its visible next-action links share exactly the same URL adapter. */
export function currentServerActions(): ServerAction[] {
  const home = homeEntry();
  const todo = todoEntry();
  const data = home?.error ? null : home?.data;
  const items = todo?.data && !todo.error ? todo.data.items : [];
  return serverActions(data, items, todo?.data?.serverTime);
}

export function serverPrompts(actions: readonly ServerAction[]): string[] {
  const classroom = serverCourses().find(({ remote }) => remote.term?.current !== false);
  const name = classroom && (classroom.remote.name || [classroom.remote.code, classroom.remote.section].filter(Boolean).join("-") || "이름 없는 과목");
  const classroomCommand = name && searchClassrooms(name).find((row) => row.courseId === classroom?.kj)?.title;
  const commands = new Map<string, number>();
  for (const action of actions) commands.set(action.command, (commands.get(action.command) || 0) + 1);
  const activityCommands = actions.filter((action) => commands.get(action.command) === 1).slice(0, 2).map((action) => action.command);
  return [TODO_COMMAND, ...activityCommands, ...(classroomCommand ? [classroomCommand] : [])];
}

const compact = (text: string) => text.toLowerCase().replace(/\s+/g, "");

/** Conservative commands: open one known item, never pretend to play, submit, or execute a plan. */
export function serverActionForCommand(text: string, actions: readonly ServerAction[]): ServerAction | null {
  const query = compact(text);
  const exact = actions.filter((action) => query === compact(action.command) || query === compact(`${action.title} 열어줘`));
  if (exact.length) return exact.length === 1 ? exact[0] : null;
  if (query === "다음활동열어줘" || query === "다음할일열어줘") return actions[0] || null;
  if (query !== "마감임박한과제열어줘") return null;
  return actions.filter((action) => action.item.type === "report" && action.urgent)
    .sort((a, b) => wallMs(a.item.closeAt) - wallMs(b.item.closeAt))[0] || null;
}
