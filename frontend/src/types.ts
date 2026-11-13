import type { AppState } from "./data.ts";

export type Role = "student" | "professor";

/** One row of a screen table (`areas/<area>/screens.ts`) or a screen resolved from the address (router.ts). */
export interface Screen {
  id: string;
  role: Role | "any";
  title: string;
  path: string;
  nav: string;
  kind: string;
  query?: Record<string, string>;
  layout?: string;
  course?: string;
  dialog?: string;
  board?: string;
  activityId?: string;
  filter?: string;
  folder?: string;
  mode?: string;
  form?: string;
  term?: string;
  open?: string | boolean;
  mobileMenu?: string;
  expand?: string | boolean;
  catalog?: string;
  gradesMode?: string;
  month?: string;
  focus?: string;
  dept?: string;
  count?: string | number;
  audience?: string;
  aside?: string;
  view?: string;
  section?: string;
  sect?: string;
  openOnly?: boolean;
  hoverNav?: string;
  extra?: boolean;
  // Backend screens resolved from the address (router.ts resolveScreen).
  serverAssignment?: number;
  /** An assignment imported from the school's e-Class (`m=report&n=school-<n>`); read only. */
  serverSchoolAssignment?: string | null;
  serverQuiz?: number | null;
  serverAttempt?: number | null;
  serverSurvey?: number | null;
  serverLecture?: number;
  serverItem?: number | null;
  serverMaterial?: number | null;
  /** A material imported from the school's e-Class (`n=school-<n>`); never a number. */
  serverSchoolMaterial?: string | null;
  /** A video imported from the school's e-Class (`m=lecture_weeks&n=school-<n>`); shown without a player. */
  serverSchoolLecture?: string | null;
  serverLink?: { type: string; activityId: number } | null;
}

/** What every screen, dialog, and panel renders from. */
export interface ScreenCtx {
  state: AppState;
  screen: Screen;
  params: URLSearchParams;
}

/** A side-menu entry (`homeNav`, `courseNav`, …). */
export interface NavItem {
  screenId: string;
  nav?: string;
  label?: string;
  labelKey?: string;
  count?: string | number;
}
