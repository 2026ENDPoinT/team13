// Addresses ↔ screens. The address is the source of truth: `navigate()` pushes a history entry,
// `applyRoute()` syncs the state the address implies (role, course, player clip) and redraws.
import type { AnchorHTMLAttributes, ButtonHTMLAttributes, MouseEvent } from "react";
import { matchScreen, screens } from "./manifest.ts";
import { equivalentHref } from "./shared/routes.ts";
import { getState, save, render, toast } from "./store.ts";
import { activities, courseById } from "./data.ts";
import { backendStatus, followRole, logout as logoutBackend } from "./shared/backend.ts";
import { assignmentIdOf, SCHOOL_ASSIGNMENT_ID } from "./shared/assignments-remote.ts";
import { quizIdOf } from "./shared/quizzes-remote.ts";
import { surveyIdOf } from "./shared/surveys-remote.ts";
import { lectureIdOf } from "./shared/lectures-remote.ts";
import { activityLinkOf } from "./shared/activity-links.ts";
import { replaceVisit } from "./shared/remote-cache.ts";
import { withoutStalePop } from "./shared/dialog-names.ts";
import type { Role, Screen } from "./types.ts";

export const HOME_PATH = "/ilos/main/main_form.acl";

export function currentParams() {
  return new URLSearchParams(location.search);
}

/** In backend mode, switching the sample role also switches the developer-login persona. */
export function followBackendRole(role: Role) {
  followRole(role).then((error) => {
    if (error) toast((error as Error).message);
  });
}

/** `kj` when it names a known course, else the sample default. */
function courseOf(params: URLSearchParams) {
  const kj = params.get("kj");
  return kj && courseById(kj).id === kj ? kj : "bi";
}

export function resolveScreen(pathname: string, params: URLSearchParams): Screen | null {
  const matched = matchScreen(pathname, params);
  const itemId = params.get("n");
  const activity = itemId ? activities.find((item) => item.id === itemId) : null;
  const playerPath = "/ilos/cls/st/activity/player_form.acl";
  const activityPath = "/ilos/cls/st/activity/activity_form.acl";
  // A backend assignment (`m=report&n=srv-<assignmentId>`, backend mode) opens the server detail.
  const serverId = pathname === activityPath && params.get("m") === "report" ? assignmentIdOf(itemId) : null;
  if (serverId) {
    return {
      id: `srv-report-${serverId}`, role: "student", title: "과제", path: activityPath, nav: "activity", kind: "activity",
      serverAssignment: serverId, course: courseOf(params), layout: "course",
    };
  }
  // An assignment imported from the school's e-Class (`m=report&n=school-<n>`): read only, submitted at the school.
  const schoolAssignment = pathname === activityPath && params.get("m") === "report" && SCHOOL_ASSIGNMENT_ID.test(String(itemId || "")) ? String(itemId) : null;
  if (schoolAssignment) {
    return {
      id: `srv-report-${schoolAssignment}`, role: "student", title: "과제", path: activityPath, nav: "activity", kind: "activity",
      serverSchoolAssignment: schoolAssignment, course: courseOf(params), layout: "course",
    };
  }
  // A backend quiz (`m=test&n=srv-<quizId>`, `&attempt=<attemptId>`) or course survey (`m=survey&n=srv-<surveyId>`).
  const serverQuiz = pathname === activityPath && params.get("m") === "test" ? quizIdOf(itemId) : null;
  const serverSurvey = pathname === activityPath && params.get("m") === "survey" ? surveyIdOf(itemId) : null;
  if (serverQuiz || serverSurvey) {
    return {
      id: serverQuiz ? `srv-test-${serverQuiz}` : `srv-survey-${serverSurvey}`,
      role: "student", title: serverQuiz ? "시험" : "설문", path: activityPath, nav: "activity", kind: "activity",
      serverQuiz,
      serverAttempt: serverQuiz ? quizIdOf(`srv-${params.get("attempt") || ""}`) : null,
      serverSurvey,
      course: courseOf(params), layout: "course",
    };
  }
  // A backend lecture: detail `m=lecture_weeks&n=srv-<activityId>`, player `n=srv-<activityId>&item=<itemId>`.
  const lectureId = (pathname === activityPath && params.get("m") === "lecture_weeks") || pathname === playerPath ? lectureIdOf(itemId) : null;
  if (lectureId) {
    const item = /^[1-9]\d{0,15}$/.test(params.get("item") || "") ? Number(params.get("item")) : null;
    return {
      id: `srv-lecture-${lectureId}`, role: "student", title: pathname === playerPath ? "플레이어" : "영상",
      path: pathname, nav: "activity", kind: pathname === playerPath ? "player" : "activity",
      serverLecture: lectureId, serverItem: pathname === playerPath ? item : null,
      course: courseOf(params), layout: "course",
    };
  }
  // A video imported from the school's e-Class (`m=lecture_weeks&n=school-<n>`): the school's facts, no player.
  const schoolLecture = pathname === activityPath && params.get("m") === "lecture_weeks" && /^school-[1-9]\d{0,17}$/.test(String(itemId || "")) ? String(itemId) : null;
  if (schoolLecture) {
    return {
      id: `srv-lecture-${schoolLecture}`, role: "student", title: "영상", path: activityPath, nav: "activity", kind: "activity",
      serverSchoolLecture: schoolLecture, course: courseOf(params), layout: "course",
    };
  }
  // A backend material (`m=lecture_material&n=srv-<activityId>`) or an activity link that only knows the
  // learning activity id (`m=report|test|survey&act=<activityId>`, to-do and notifications).
  const materialId = pathname === activityPath && params.get("m") === "lecture_material" ? lectureIdOf(itemId) : null;
  // A material imported from the school's e-Class keeps its string id (`m=lecture_material&n=school-<n>`).
  const schoolMaterial = pathname === activityPath && params.get("m") === "lecture_material" && /^school-[1-9]\d{0,17}$/.test(String(itemId || "")) ? String(itemId) : null;
  const link = pathname === activityPath && !itemId ? activityLinkOf(params) : null;
  if (materialId || schoolMaterial || link) {
    return {
      id: materialId ? `srv-material-${materialId}` : schoolMaterial ? `srv-material-${schoolMaterial}` : `srv-link-${link!.type}-${link!.activityId}`,
      role: "student", title: materialId || schoolMaterial ? "자료" : "학습활동", path: activityPath, nav: "activity", kind: "activity",
      serverMaterial: materialId, serverSchoolMaterial: schoolMaterial, serverLink: link, course: courseOf(params), layout: "course",
    };
  }
  // A backend file preview (`preview_form.acl?n=srv-<fileId>`, or an uncopied school file `n=school-file-<n>`, backend mode) opens the server preview.
  const previewPath = "/ilos/cls/st/files/preview_form.acl";
  if (pathname === previewPath && !matched && /^(?:srv|school-file)-[1-9][0-9]{0,17}$/.test(String(itemId || ""))) {
    return { id: `srv-preview-${itemId}`, role: "student", title: "미리보기", path: previewPath, nav: "files", kind: "preview", course: courseOf(params), layout: "course" };
  }
  if (!activity || (pathname !== playerPath && pathname !== activityPath)) return matched;
  if (matched && matched.activityId === activity.id) return matched;
  return {
    id: `open-${activity.id}`, role: "student", title: activity.title, path: pathname, nav: "activity",
    kind: pathname === playerPath ? "player" : "activity", activityId: activity.id, course: activity.courseId, layout: "course",
  };
}

/** The screen for the current address (the student home when nothing matches). */
export function currentScreen(): Screen {
  return resolveScreen(location.pathname, currentParams()) || screens.find((s) => s.id === "00_main_home")!;
}

/** Syncs the state the address implies, then redraws. Runs on every navigation and on popstate. */
export function applyRoute() {
  const state = getState();
  // A `?pop=` naming a removed window (old links such as `?pop=avatar`) opens nothing and leaves the address.
  const search = withoutStalePop(location.search);
  if (search !== null) history.replaceState(history.state, "", `${location.pathname}${search}${location.hash}`);
  const params = currentParams();
  const screen = resolveScreen(location.pathname, params);
  const previousRole = state.role;
  if (params.get("as") === "pf") state.role = "professor";
  else if (params.get("as") === "st") state.role = "student";
  else if (screen && screen.role === "professor") state.role = "professor";
  else if (screen && screen.role === "student") state.role = "student";
  // A role change by address (?as=, screen role) also switches the backend persona; no-op unless `ready`.
  if (state.role !== previousRole) followBackendRole(state.role);
  if (screen && screen.role === "student" && state.role === "professor" && params.get("as") === "pf") {
    history.replaceState({}, "", equivalentHref("professor", `${location.pathname}${location.search}`));
    applyRoute();
    return;
  }
  if (screen && screen.role === "professor" && state.role === "student" && params.get("as") === "st") {
    history.replaceState({}, "", equivalentHref("student", `${location.pathname}${location.search}`));
    applyRoute();
    return;
  }
  if (screen && (screen.role === "professor" || screen.role === "student") && screen.role === state.role) {
    const want = state.role === "professor" ? "pf" : "st";
    if (params.get("as") !== want) {
      params.set("as", want);
      history.replaceState({}, "", `${location.pathname}?${params.toString()}`);
    }
  }
  const theme = params.get("theme");
  if (theme === "dark" || theme === "light") {
    state.theme = theme;
    state.themeMode = theme;
  }
  if (screen && screen.kind === "player" && screen.activityId && state.player.id !== screen.activityId) {
    const clip = activities.find((item) => item.id === screen.activityId);
    state.player.positions = state.player.positions || {};
    state.player.id = screen.activityId;
    state.player.seconds = Number(state.player.positions[screen.activityId]) || 0;
    state.player.duration = ((clip && clip.minutes) || 30) * 60;
    state.player.playing = false;
  }
  if (screen && screen.course) state.courseId = screen.course;
  const kj = params.get("kj");
  if (kj && courseById(kj)) state.courseId = kj;
  // `id=` is a course only when it names a sample or server course (backend mode answers any other id with a "not on the server" placeholder).
  const id = params.get("id");
  if (id && courseById(id).id === id && !courseById(id).missing) state.courseId = id;
  const message = state.messages.find((m) => m.id === id);
  if (message) message.read = true;
  save();
  render();
}

export interface NavigateOptions {
  /** Keep the address as given instead of mapping it to the current role's equivalent. */
  allowExit?: boolean;
  logout?: boolean;
  /** With `logout`: the server confirmed the end of the session (the logged-out page may say so). */
  serverLogout?: boolean;
}

/** True on the logged-out page entry pushed right after a logout the server confirmed. */
export function serverLogoutConfirmed(): boolean {
  const entry: unknown = history.state;
  return Boolean(entry && typeof entry === "object" && "serverLogout" in entry && entry.serverLogout === true);
}

export function navigate(href: string, opts: NavigateOptions = {}) {
  const state = getState();
  let url = new URL(href, location.origin);
  if (url.origin !== location.origin) return;
  if (opts.logout) {
    state.role = "student";
    state.navOpen = false;
    save();
    history.pushState(opts.serverLogout ? { serverLogout: true } : {}, "", "/ilos/lo/logout.acl");
    applyRoute();
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    return;
  }
  if (!opts.allowExit) {
    url = new URL(equivalentHref(state.role, `${url.pathname}${url.search}`), location.origin);
  }
  if (state.role === "professor" || state.role === "student") {
    url.searchParams.set("as", state.role === "professor" ? "pf" : "st");
  }
  const pageChanged = url.pathname !== location.pathname
    || url.searchParams.get("kj") !== currentParams().get("kj");
  state.navOpen = false;
  history.pushState({}, "", `${url.pathname}${url.search}`);
  applyRoute();
  if (pageChanged) window.scrollTo({ top: 0, left: 0, behavior: "instant" });
}

/** Closes the `?pop=` window over the current screen (or goes home when there is none). */
export function closeDialog() {
  const params = currentParams();
  if (!params.has("pop")) {
    navigate(HOME_PATH);
    return;
  }
  params.delete("pop");
  const path = location.pathname === "/" ? HOME_PATH : location.pathname;
  const q = params.toString();
  // A window opened in place (`popInPlace` on the entry, e.g. the entry notice) closes in place:
  // no new history entry, and the screen under it is not treated as a new visit.
  const entry: unknown = history.state;
  if (entry && typeof entry === "object" && "popInPlace" in entry && entry.popInPlace) {
    const { popInPlace: _, ...rest } = entry;
    replaceVisit(q ? `${path}?${q}` : path, rest);
    applyRoute();
    return;
  }
  navigate(q ? `${path}?${q}` : path);
}

export interface FollowOptions {
  /** Switch the sample role first (and the backend persona). */
  roleSwitch?: Role;
  logout?: boolean;
  /** Scroll to the top even when only the query changed (side menu, study entry). */
  resetScroll?: boolean;
}

// A server logout in flight: further 로그아웃 clicks are ignored (one POST, one logged-out history entry).
let loggingOut = false;

/** What an in-app link does when clicked: navigate, switching role or logging out when asked. */
export function followLink(href: string, opts: FollowOptions = {}) {
  if (opts.logout) {
    // Signed in to the server: end that session first (as the backend strip's 로그아웃 does). A confirmed end
    // empties every per-user cache and saved input (`onSignOut`), then the logged-out page shows; a failed one
    // keeps the user where they are, still signed in, and says so.
    if (loggingOut) return;
    if (backendStatus() === "ready") {
      loggingOut = true;
      logoutBackend().then(
        () => navigate("/ilos/lo/logout.acl", { logout: true, serverLogout: true }),
        (error: unknown) => toast(error instanceof Error ? error.message : String(error)),
      ).finally(() => { loggingOut = false; });
    } else navigate("/ilos/lo/logout.acl", { logout: true });
    return;
  }
  if (opts.roleSwitch) {
    const state = getState();
    state.role = opts.roleSwitch;
    state.toast = opts.roleSwitch === "professor" ? "교수 화면입니다" : "학생 화면입니다";
    save();
    navigate(href, { allowExit: true });
    followBackendRole(opts.roleSwitch);
  } else navigate(href);
  if (opts.resetScroll) window.scrollTo({ top: 0, left: 0, behavior: "instant" });
}

/** True for clicks the browser should handle itself (new tab, download, context menu). */
export function isModifiedClick(event: Pick<MouseEvent, "metaKey" | "ctrlKey" | "shiftKey" | "altKey" | "button">) {
  return event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0;
}

export interface LinkProps extends AnchorHTMLAttributes<HTMLAnchorElement>, FollowOptions {
  href: string;
}

/** An in-app link: a real `<a href>` that navigates without a page load on a plain click. */
export function Link({ href, roleSwitch, logout, resetScroll, onClick, ...rest }: LinkProps) {
  return (
    <a
      href={href}
      {...rest}
      onClick={(event) => {
        onClick?.(event);
        if (event.defaultPrevented || isModifiedClick(event)) return;
        event.preventDefault();
        followLink(href, { roleSwitch, logout, resetScroll });
      }}
    />
  );
}

export interface GoButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, FollowOptions {
  href: string;
}

/** A button that navigates (header icons, course switcher): button styling and semantics, link behavior. */
export function GoButton({ href, roleSwitch, logout, resetScroll, onClick, type = "button", ...rest }: GoButtonProps) {
  return (
    <button
      type={type}
      {...rest}
      onClick={(event) => {
        onClick?.(event);
        if (!event.defaultPrevented) followLink(href, { roleSwitch, logout, resetScroll });
      }}
    />
  );
}
