import * as home from "./home/home.js";
import * as course from "./course/course.js";
import * as learning from "./learning/learning.js";
import * as mobile from "./mobile/mobile.js";
import * as professor from "./professor/professor.js";
import { renderDialog as homeDialog } from "./home/dialogs.js";
import { renderDialog as courseDialog } from "./course/dialogs.js";
import { renderDialog as learningDialog } from "./learning/dialogs.js";
import { dialogShell } from "../ui/components.js";
import { el } from "../dom.js";
import { actions as homeActions } from "./home/actions.js";
import { actions as courseActions } from "./course/actions.js";
import { actions as learningActions } from "./learning/actions.js";
import { actions as professorActions } from "./professor/actions.js";
import { actions as sharedActions } from "../shared/actions.js";
import { actions as mobileActions } from "./mobile/mobile.js";

const areas = { home, course, learning, mobile, professor };

export function areaOf(screen) {
  if (!screen) return "home";
  if (screen.layout === "mobile" || screen.kind === "mobile") return "mobile";
  if (screen.role === "professor" || String(screen.kind || "").startsWith("pf-")) return "professor";
  if (["activity", "player", "preview", "attendance", "messages", "week", "timetable"].includes(screen.kind)) return "learning";
  if (["room", "activity-list", "groups", "grades", "files"].includes(screen.kind)) return "course";
  if (screen.kind === "board") return ["course", "course-qna", "open"].includes(screen.board) ? "course" : "home";
  return "home";
}

export function renderScreen(screen, ctx) {
  const area = areas[areaOf(screen)] || home;
  return area.render(screen, ctx);
}

export function renderDialog(name, ctx) {
  if (!name) return null;
  return homeDialog(name, ctx) || courseDialog(name, ctx) || learningDialog(name, ctx)
    || dialogShell(name, el("p", { text: "이 창은 샘플입니다." }));
}

export function fillAside(aside, ctx) {
  if (home.fillAside) home.fillAside(aside, ctx);
}

export function fillHero(hero, ctx) {
  if (home.fillHero) home.fillHero(hero, ctx);
}

export const actions = {
  ...sharedActions,
  ...mobileActions,
  ...homeActions,
  ...courseActions,
  ...learningActions,
  ...professorActions,
};

export { areas };
