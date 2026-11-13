import type { ReactNode } from "react";
import * as home from "./home/home.tsx";
import * as course from "./course/course.tsx";
import * as learning from "./learning/learning.tsx";
import * as mobile from "./mobile/mobile.tsx";
import * as professor from "./professor/professor.tsx";
import { renderDialog as homeDialog } from "./home/dialogs.tsx";
import { renderDialog as courseDialog } from "./course/dialogs.tsx";
import { renderDialog as learningDialog } from "./learning/dialogs.tsx";
import { Dialog } from "../ui/kit.tsx";
import type { Screen, ScreenCtx } from "../types.ts";

export type AreaName = "home" | "course" | "learning" | "mobile" | "professor";

const areas = { home, course, learning, mobile, professor };

export function areaOf(screen: Screen | null): AreaName {
  if (!screen) return "home";
  if (screen.layout === "mobile" || screen.kind === "mobile") return "mobile";
  if (screen.role === "professor" || String(screen.kind || "").startsWith("pf-")) return "professor";
  if (["activity", "player", "preview", "attendance", "messages", "week", "timetable"].includes(screen.kind)) return "learning";
  if (["room", "activity-list", "groups", "grades", "files"].includes(screen.kind)) return "course";
  if (screen.kind === "board") return ["course", "course-qna", "open"].includes(screen.board || "") ? "course" : "home";
  return "home";
}

export function screenView(ctx: ScreenCtx): ReactNode {
  return areas[areaOf(ctx.screen)].render(ctx.screen, ctx);
}

/** The `?pop=` window `name`, or a placeholder window for a name no area knows. */
export function dialogView(name: string, ctx: ScreenCtx): ReactNode {
  return homeDialog(name, ctx) ?? courseDialog(name, ctx) ?? learningDialog(name, ctx)
    ?? <Dialog title={name}><p>이 창은 샘플입니다.</p></Dialog>;
}

export const asideView = home.homeAside;
export const heroView = home.homeHero;
