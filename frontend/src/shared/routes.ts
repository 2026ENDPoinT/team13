import { activities, type Activity } from "../data.ts";
import type { Role } from "../types.ts";
import { hrefFor, matchScreen, find } from "../manifest.ts";
import { getState } from "../store.ts";
import { isClosedActivity } from "./activity-library.ts";

const POP: Record<string, string> = {
  myinfo: "14_pop_myinfo",
  avatar: "14b_pop_avatar_set",
  files: "16_pop_file",
  history: "15_pop_history",
  "history-login": "15b_pop_history_login_tab",
  settings: "11_pop_setup_theme",
  notify: "03_pop_notification",
  "notify-unread": "03b_pop_notification_unread",
  profile: "04_pop_mymenu",
  todo: "01_pop_todo",
  "todo-more": "09_home_todo_more",
  day: "17_pop_calendar_day_detail",
};

const ACTIVITY_SCREEN: Record<string, string> = {
  "v-expired": "57_activity_video_detail_expired",
  "v-open": "58_activity_video_detail_open",
  "z-1": "58_activity_zoom",
  "m-1": "59_activity_material_detail",
  "r-open": "60_activity_report_detail_open",
  "r-done": "61_activity_report_detail_done",
  "t-off": "62_activity_test_detail_expired",
  "t-on": "62b_activity_test_detail_open",
  "s-1": "64_activity_survey_detail",
  "nr-s": "71_nr_activity_survey_detail",
  "nr-v": "73_nr_activity_video_detail",
};

const SERVER_ASSIGNMENT = /^srv-[1-9]\d{0,15}$/;

/** What a route needs of an activity: a sample activity, or a stand-in built from `n`/`kj`. */
export type RouteActivity = Pick<Activity, "id" | "courseId" | "type"> & Partial<Activity>;

/** Route classes of an address (`classifyRoute`). */
export type RouteClass = Role | "logout" | "any" | "other";

/** Route targets `routeFor` understands. */
export type RouteKind =
  | "home" | "logout" | "survey" | "activity" | "lecture" | "assignment" | "quiz" | "pdf" | "screen"
  | "courses" | "study" | "classroom" | "notice" | "qna" | "message" | "calendar" | "weekcal" | "timetable"
  | "sitemap" | "attend" | "grades" | "switch" | "pop" | "todo" | "notify" | "profile";

/** Extra route input: known keys plus, for `screen`, any query parameter of the target screen. */
export interface RouteExtra {
  activity?: RouteActivity | null;
  n?: string;
  kj?: string;
  special?: boolean;
  screenId?: string;
  pop?: string;
  day?: string;
  qnaId?: string;
  [key: string]: string | number | boolean | RouteActivity | null | undefined;
}

type RouteQuery = Record<string, string | number | null | undefined>;

export function currentRole(): Role {
  try {
    const stateRole = getState().role === "professor" ? "professor" : "student";
    if (typeof location === "undefined" || !location.pathname) return stateRole;
    const params = new URLSearchParams(location.search || "");
    if (params.get("as") === "pf") return "professor";
    if (params.get("as") === "st") return "student";
    const kind = classifyRoute(location.pathname, params);
    if (kind === "professor" || kind === "student") return kind;
    return stateRole;
  } catch {
    return "student";
  }
}

function splitHref(href: string | null | undefined): { pathname: string; params: URLSearchParams; search: string } {
  const clean = String(href || "").split("#")[0];
  const q = clean.indexOf("?");
  return {
    pathname: q === -1 ? clean : clean.slice(0, q),
    params: new URLSearchParams(q === -1 ? "" : clean.slice(q + 1)),
    search: q === -1 ? "" : clean.slice(q),
  };
}

function joinHref(pathname: string, params: URLSearchParams): string {
  const q = params.toString();
  return pathname + (q ? `?${q}` : "");
}

export function stampRole(href: string, role: string | null | undefined): string {
  const parts = splitHref(href);
  if (role === "professor" || role === "student") parts.params.set("as", role === "professor" ? "pf" : "st");
  return joinHref(parts.pathname, parts.params);
}

export function classifyRoute(pathname: string, params: URLSearchParams | string | null | undefined): RouteClass {
  const path = pathname === "/" || pathname === "/index.html" ? "/ilos/main/main_form.acl" : pathname;
  if (path === "/ilos/lo/logout.acl") return "logout";
  if (path.startsWith("/ilos/pf/") || path.startsWith("/ilos/cls/pf/") || path.startsWith("/ilos/mp/")) return "professor";
  if (path.startsWith("/ilos/cls/st/") || path.startsWith("/ilos/m/") || path.startsWith("/ilos/message/")) return "student";
  const screen = matchScreen(path, params instanceof URLSearchParams ? params : new URLSearchParams(params || ""));
  if (screen && screen.role === "professor") return "professor";
  if (screen && screen.role === "any") return "any";
  if (path.startsWith("/ilos/main/")) return screen && screen.role === "any" ? "any" : "student";
  if (path.startsWith("/ilos/")) return "student";
  return "other";
}

function studentActivityHref(activity: RouteActivity, courseId: string): string {
  const id = courseId || activity.courseId;
  if (activity.type === "post") {
    return `/ilos/cls/st/activity/activity_form.acl?m=post&n=${encodeURIComponent(activity.id)}&kj=${encodeURIComponent(id)}`;
  }
  if (activity.type === "material" && activity.pdfId) {
    return `/ilos/main/study/study_form.acl?kj=${encodeURIComponent(id)}&doc=${encodeURIComponent(activity.pdfId)}`;
  }
  if (activity.type === "video" && !isClosedActivity(activity)) {
    return `/ilos/cls/st/activity/player_form.acl?n=${encodeURIComponent(activity.id)}&kj=${encodeURIComponent(id)}`;
  }
  const known = ACTIVITY_SCREEN[activity.id];
  if (known) return hrefFor(find(known), { kj: id, n: activity.id });
  const modes: Record<string, string> = { material: "lecture_material", report: "report", test: "test", survey: "survey", zoom: "zoom", video: "lecture_weeks", post: "post" };
  const mode = modes[activity.type] || "lecture_material";
  return `/ilos/cls/st/activity/activity_form.acl?m=${mode}&n=${encodeURIComponent(activity.id)}&kj=${encodeURIComponent(id)}`;
}

function activityOf(extra: RouteExtra): RouteActivity | null | undefined {
  if (extra.activity) return extra.activity;
  const id = extra.n || "";
  return id ? activities.find((item) => item.id === id) : null;
}

function professorActivityHref(activity: RouteActivity | null | undefined, courseId: string): string {
  const kj = courseId || (activity && activity.courseId) || "bi";
  const type = activity && activity.type;
  if (!activity) return stampRole(hrefFor(find("pf_room_week"), { kj }), "professor");
  if (type === "report") return stampRole(hrefFor(find("pf_submissions"), { kj, n: activity.id }), "professor");
  if (type === "test") return stampRole(hrefFor(find("pf_quiz"), { kj, n: activity.id }), "professor");
  if (type === "material") return stampRole(hrefFor(find("pf_pdf"), { kj, n: activity.id }), "professor");
  if (type === "video" || type === "zoom" || type === "survey" || type === "post") {
    return stampRole(hrefFor(find("pf_lecture"), { kj, n: activity.id }), "professor");
  }
  return stampRole(hrefFor(find("pf_room_activity"), { kj }), "professor");
}

export function routeFor(role: string, target: RouteKind | { kind?: RouteKind } | null | undefined, extra: RouteExtra = {}): string {
  const professor = role === "professor";
  const kind: RouteKind = typeof target === "string" ? target : (target && target.kind) || "home";
  const activity = activityOf(extra);
  const kj = extra.kj || (activity && activity.courseId) || "";

  if (kind === "logout") return "/ilos/lo/logout.acl";
  // A backend course survey (`srv-<surveyId>`): the student detail, or the professor's survey screen.
  if (kind === "survey" && SERVER_ASSIGNMENT.test(extra.n || "")) {
    return professor
      ? stampRole(hrefFor(find("pf_discuss"), { kj: kj || "bi", n: extra.n }), "professor")
      : `/ilos/cls/st/activity/activity_form.acl?m=survey&n=${encodeURIComponent(extra.n || "")}&kj=${encodeURIComponent(kj || "bi")}`;
  }
  if (kind === "activity" || kind === "lecture" || kind === "assignment" || kind === "quiz" || kind === "pdf") {
    if (!professor) {
      // A backend assignment (`srv-<id>`, backend mode only) has its own student detail.
      if (kind === "assignment" && SERVER_ASSIGNMENT.test(extra.n || "")) {
        return `/ilos/cls/st/activity/activity_form.acl?m=report&n=${encodeURIComponent(extra.n || "")}&kj=${encodeURIComponent(kj || "bi")}`;
      }
      // A backend quiz (`srv-<quizId>`) has its own student detail.
      if (kind === "quiz" && SERVER_ASSIGNMENT.test(extra.n || "")) {
        return `/ilos/cls/st/activity/activity_form.acl?m=test&n=${encodeURIComponent(extra.n || "")}&kj=${encodeURIComponent(kj || "bi")}`;
      }
      if (activity) return studentActivityHref(activity, kj || activity.courseId);
      if (kind === "pdf") return hrefFor(find("65_file_preview"), { n: extra.n || "f1", kj: kj || "bi" });
      return hrefFor(find(extra.special ? "70_nr_room_submain" : "40_room_submain"), { kj: kj || "bi" });
    }
    if (kind === "assignment") {
      const report: RouteActivity = activity && activity.type === "report" ? activity : { id: extra.n || (activity && activity.id) || "r-open", courseId: kj || "bi", type: "report" };
      return professorActivityHref(report, kj || report.courseId);
    }
    // A backend quiz (`srv-<quizId>`): its attempt list.
    if (kind === "quiz" && SERVER_ASSIGNMENT.test(extra.n || "")) return stampRole(hrefFor(find("pf_exam_status"), { kj: kj || "bi", n: extra.n }), "professor");
    if (kind === "quiz") return professorActivityHref({ ...(activity || { id: extra.n || "t-on", courseId: kj || "oss", type: "test" as const }), type: "test" }, kj);
    if (kind === "pdf") {
      return stampRole(hrefFor(find("pf_pdf"), { kj: kj || "bi", n: (activity && activity.id) || extra.n || "f1" }), "professor");
    }
    if (kind === "lecture") {
      // A backend lecture (`srv-<id>`, backend mode only) is managed on 콘텐츠관리.
      if (SERVER_ASSIGNMENT.test(extra.n || "")) return stampRole(hrefFor(find("pf_contents"), { kj: kj || "bi" }), "professor");
      const video: RouteActivity = activity && (activity.type === "video" || activity.type === "zoom" || activity.type === "survey")
        ? activity
        : { id: extra.n || (activity && activity.id) || "v-open", courseId: kj || "bi", type: "video" };
      return professorActivityHref(video, kj);
    }
    return professorActivityHref(activity, kj);
  }
  if (kind === "screen") {
    const targetScreen = find(extra.screenId || "");
    const rest: RouteQuery = {};
    for (const [key, value] of Object.entries(extra)) {
      if (key === "screenId" || key === "activity" || (value != null && typeof value === "object")) continue;
      rest[key] = typeof value === "boolean" ? String(value) : value;
    }
    if (!targetScreen) return routeFor(role, "home", extra);
    const href = hrefFor(targetScreen, rest);
    if (professor && targetScreen.role === "student") {
      const mapped = targetFor(href);
      return routeFor("professor", mapped.kind, mapped.extra);
    }
    if (!professor && targetScreen.role === "professor") {
      const mapped = targetFor(href);
      return routeFor("student", mapped.kind, mapped.extra);
    }
    return professor ? stampRole(href, "professor") : href;
  }

  const studentIds: Record<string, string> = {
    home: "00_main_home",
    courses: "21_regular_register_list",
    study: "study_home",
    classroom: extra.special ? "70_nr_room_submain" : "40_room_submain",
    notice: "30_community_notice_list",
    qna: "45_room_qna_list",
    message: "02b_msg_window",
    calendar: "17_pop_calendar_day_detail",
    weekcal: "07_home_calendar_week",
    timetable: "05_pop_lectime",
    sitemap: "sitemap",
    attend: "41_room_attendance",
    grades: "43_room_eval_result",
    switch: "40c_room_lecture_change_pop",
    pop: POP[extra.pop || ""] || "00_main_home",
  };
  const studentId = studentIds[kind] || "00_main_home";

  const professorIds: Record<string, string> = {
    home: "pf_main",
    courses: "pf_courses",
    study: "pf_study",
    classroom: "pf_room_week",
    notice: "pf_notice",
    qna: "pf_qna",
    message: "pf_message",
    calendar: "pf_calendar",
    weekcal: "pf_calendar",
    timetable: "pf_calendar",
    sitemap: "sitemap",
    attend: "pf_attend",
    grades: "pf_gradebook",
    switch: "pf_courses",
    pop: "pf_main",
    todo: "pf_main",
    notify: "pf_main",
    profile: "pf_main",
  };
  const professorId = professorIds[kind] || "pf_main";

  if (!professor && (kind === "todo" || kind === "notify" || kind === "profile")) {
    const id = kind === "todo" ? "01_pop_todo" : kind === "profile" ? "04_pop_mymenu" : (extra.pop === "notify-unread" ? "03b_pop_notification_unread" : "03_pop_notification");
    return hrefFor(find(id));
  }

  const screen = find(professor ? professorId : studentId);
  const query: RouteQuery = {};
  if (kj && (kind === "study" || kind === "classroom" || kind === "qna" || kind === "attend" || kind === "grades" || kind === "switch" || (screen && (screen.course || screen.layout === "prof" || screen.layout === "course")))) {
    query.kj = kj;
  }
  if (kind === "calendar" && extra.day) query.day = extra.day;
  if (kind === "pop") query.pop = extra.pop || "profile";
  if (professor && kind === "todo") query.pop = "todo";
  if (professor && kind === "notify") query.pop = extra.pop || "notify";
  if (professor && kind === "profile") query.pop = extra.pop || "profile";
  // A server question opened over the Q&A list (`?pop=qna&id=<questionId>`) stays open across the role switch.
  if (kind === "qna" && extra.qnaId) Object.assign(query, { pop: extra.pop === "qna-reply" ? "qna-reply" : "qna", id: extra.qnaId });
  const href = hrefFor(screen, query);
  return professor ? stampRole(href, "professor") : href;
}

function targetFor(href: string): { kind: RouteKind; extra: RouteExtra } {
  const url = splitHref(href);
  const params = url.params;
  const screen = matchScreen(url.pathname, params);
  const n = params.get("n") || (screen && screen.activityId) || "";
  const activity = n ? activities.find((item) => item.id === n) : null;
  const kj = params.get("kj") || params.get("id") || (activity && activity.courseId) || (screen && screen.course) || "";
  const extra: RouteExtra = { n, kj, activity, day: params.get("day") || "", pop: params.get("pop") || "", special: url.pathname.includes("/nr/") };
  if ((extra.pop === "qna" || extra.pop === "qna-reply") && /^[1-9]\d{0,15}$/.test(params.get("id") || "")) extra.qnaId = params.get("id") || "";
  if (SERVER_ASSIGNMENT.test(n) && params.get("m") === "report" && url.pathname.includes("activity_form")) return { kind: "assignment", extra };
  if (SERVER_ASSIGNMENT.test(n) && params.get("m") === "test" && url.pathname.includes("activity_form")) return { kind: "quiz", extra };
  if (SERVER_ASSIGNMENT.test(n) && params.get("m") === "survey" && url.pathname.includes("activity_form")) return { kind: "survey", extra };
  if (SERVER_ASSIGNMENT.test(n) && screen && (screen.kind === "pf-exam-status" || screen.kind === "pf-exam-grade" || (screen.kind === "pf-form" && screen.form === "exam"))) return { kind: "quiz", extra };
  if (SERVER_ASSIGNMENT.test(n) && screen && screen.kind === "pf-form" && screen.form === "discuss") return { kind: "survey", extra };
  if (url.pathname.includes("player_form") || (activity && (activity.type === "video" || activity.type === "zoom" || activity.type === "survey" || activity.type === "post")) || (screen && (screen.kind === "player" || screen.kind === "pf-lecture"))) {
    return { kind: "lecture", extra };
  }
  if ((activity && activity.type === "report") || (screen && screen.kind === "pf-submissions")) return { kind: "assignment", extra };
  if ((activity && activity.type === "test") || (screen && screen.kind === "pf-quiz")) return { kind: "quiz", extra };
  if ((activity && activity.type === "material") || (screen && (screen.kind === "preview" || screen.kind === "pf-pdf")) || url.pathname.includes("preview_form")) {
    return { kind: "pdf", extra };
  }
  if (screen && (screen.kind === "study" || screen.kind === "lecture" || screen.kind === "pf-study")) return { kind: "study", extra };
  if (screen && (screen.kind === "room" || screen.kind === "pf-room" || screen.kind === "activity-list")) return { kind: "classroom", extra };
  if (screen && (screen.kind === "course-list" || screen.kind === "catalog" || screen.kind === "detail" || screen.kind === "pf-courses")) return { kind: "courses", extra };
  if (screen && (screen.kind === "pf-notice" || (screen.kind === "board" && screen.board === "global"))) return { kind: "notice", extra };
  if (screen && (screen.kind === "pf-qna" || screen.board === "qna" || screen.board === "course-qna")) return { kind: "qna", extra };
  if (screen && (screen.kind === "week" || screen.kind === "timetable" || screen.kind === "pf-calendar" || params.get("pop") === "day")) return { kind: "calendar", extra };
  if (params.get("pop") === "todo" || params.get("pop") === "todo-more") return { kind: "todo", extra };
  if (String(params.get("pop") || "").startsWith("notify")) return { kind: "notify", extra: { ...extra, pop: params.get("pop") || "" } };
  if (params.get("pop") === "profile" || params.get("pop") === "myinfo" || params.get("pop") === "settings") return { kind: "profile", extra: { ...extra, pop: params.get("pop") || "" } };
  if (screen && (screen.kind === "messages" || (screen.kind === "pf-form" && screen.form === "pmessage"))) return { kind: "message", extra };
  if (screen && (screen.kind === "attendance" || screen.kind === "pf-attend")) return { kind: "attend", extra };
  if (screen && (screen.kind === "grades" || screen.kind === "pf-gradebook")) return { kind: "grades", extra };
  return { kind: "home", extra };
}

export function equivalentHref(role: string, href: string): string {
  const url = splitHref(href);
  const kind = classifyRoute(url.pathname, url.params);
  if (kind === "logout" || kind === "other" || kind === "any") return url.pathname + url.search;
  if (kind === role) return role === "professor" ? stampRole(url.pathname + url.search, "professor") : url.pathname + url.search;
  const mapped = targetFor(url.pathname + url.search);
  return routeFor(role, mapped.kind, mapped.extra);
}
