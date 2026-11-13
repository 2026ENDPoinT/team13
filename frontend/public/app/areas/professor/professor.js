import { el } from "../../dom.js";
import { coverImage } from "../../ui/assets.js";
import { btn, field, emptyState, go, mark, badge, tabs, breadcrumbs, listRow } from "../../ui/components.js";
import { activities, people, courseById, gradeItems, weekCount, professor as teacher, files, events, notices } from "../../data.js";
import { routeFor } from "../../shared/routes.js";
import { attendStatus, statusLabel, dday, typeLabel } from "../../shared/format.js";
import { LECTURES } from "../../media/catalog.js";
import { mediaFile, mediaForActivity } from "../../media.js";
import { activityPhase, isClosedActivity } from "../../shared/activity-library.js";
import { richNodes } from "../../shared/rich-text.js";
import { bindUploadedVideo } from "../../shared/activity-files.js";
import { listCoursePdfs } from "../../shared/course-pdfs.js";
import { isRemotePdfId, getPublishedSummary, listSummaryJobs, startSummaryJob, getSummaryJob, cancelSummaryJob, retrySummaryJob, getSummaryResult, publishSummary, summaryMessage } from "../../shared/course-pdfs-remote.js";
import { backendStatus } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { summaryView } from "../../shared/page-summary.js";
import { serverProfessorHome, serverNoticeFields } from "./notices-server.js";
import { serverMaterialList } from "./materials-server.js";
import { registrationAttach } from "./materials-server.js";
import { serverReportFields, serverSubmissionStatus, serverGrading } from "./assignments-server.js";
import { serverTaughtCourses, serverEnrollmentRequests } from "./enrollments-server.js";
import { serverCourseRoster } from "./enrollments-server.js";
import { serverMessageFields } from "./messages-server.js";
import { qnaSection } from "../../shared/board-list.js";
import { serverExamFields, serverExamStatus, serverExamGrade } from "./quizzes-server.js";
import { serverSurveyFields } from "./surveys-server.js";
import { serverGradebook } from "./grades-server.js";
import { serverAttendGeneral, serverAttendSmart, serverAttendSessions } from "./attendance-server.js";
import { serverTeams } from "./groups-server.js";
import { llmConsentSection, consentLead } from "./llm-consent-server.js";
import { serverVideoCourse, serverLectureManager, serverLectureCreate } from "./lectures-server.js";
import { wallFormat } from "../../shared/home-remote.js";
import { serverRoom, serverRoomView, serverActivityChooser } from "./room-server.js";
import { serverLectureDetail } from "./lectures-server.js";
import { serverQuizDetail } from "./quizzes-server.js";
import { serverMaterialDetail, serverStudyRoom } from "./materials-server.js";
import { serverProfessorCalendar } from "./calendar-server.js";
import { serverCourseNotices } from "./notices-server.js";
import { serverSyllabusEditor } from "./syllabus-server.js";

const learners = () => people.filter((p) => p.role === "student");
const TYPES = [["video", "영상"], ["zoom", "Zoom"], ["material", "자료"], ["post", "글"], ["report", "과제"], ["test", "시험"], ["survey", "설문"]];
const ACTIVITY_STATUS = {
  done: ["완료", "success"],
  ongoing: ["진행", "neutral"],
  missing: ["미제출", "danger"],
  open: ["열림", "neutral"],
  expired: ["마감", "danger"],
  submitted: ["제출", "success"],
  graded: ["채점", "success"],
  scheduled: ["예정", "neutral"],
};
const SMART = { minjun: 92, seoyeon: 88, jieun: 100, doyun: 74, haeun: 96, woojin: 63, harin: 91 };

function param(ctx, key) {
  return (ctx.params && ctx.params.get(key)) || "";
}

function pf(screenId, extra) {
  return routeFor("professor", "screen", { screenId, ...(extra || {}) });
}

function kept(ctx, screenId, patch = {}) {
  const extra = {};
  if (ctx.params) {
    for (const key of ["q", "view", "type", "bank"]) {
      const value = ctx.params.get(key);
      if (value) extra[key] = value;
    }
  }
  Object.assign(extra, patch);
  for (const key of Object.keys(extra)) {
    if (extra[key] == null || extra[key] === "" || extra[key] === "all") delete extra[key];
  }
  return pf(screenId, extra);
}

function withQ(ctx, screenId) {
  const q = param(ctx, "q");
  return pf(screenId, q ? { q } : {});
}

function courseOf(ctx) {
  return courseById(ctx.state.courseId);
}

function matchesQuery(ctx, text) {
  const q = param(ctx, "q").trim();
  return !q || String(text).includes(q);
}

function stat(label, value, extra) {
  return el("div", { class: "stat pf-stat" },
    el("span", { class: "tiny", text: label }),
    el("b", { text: value }),
    extra || null,
  );
}

function meter(pct) {
  const n = Math.max(0, Math.min(100, Math.round(pct)));
  return el("div", { class: "pf-meter" },
    el("div", { class: "bar", "aria-hidden": "true" }, el("i", { style: `width:${n}%` })),
    el("span", { text: `${n}%` }),
  );
}

function finder(ctx, label) {
  return el("div", { class: "pf-search" },
    el("input", {
      type: "search",
      "data-name": "q",
      value: param(ctx, "q"),
      placeholder: label,
      "aria-label": label,
    }),
    btn("검색", { "data-act": "pf-find" }, "secondary"),
  );
}

function chipRow(ctx, screenId, options, key) {
  const current = param(ctx, key) || "all";
  return el("div", { class: "filters", role: "group", "aria-label": "필터" }, ...options.map(([value, label, tone]) => {
    const on = current === value;
    return go(kept(ctx, screenId, { [key]: value }), label, { class: `chip ${tone || "all"}${on ? " on" : ""}` });
  }));
}

function frame(title, ctx, opts, ...nodes) {
  const course = opts.course ? courseOf(ctx) : null;
  const crumbs = opts.trail === false ? null : [
    { label: "교수 홈", href: pf("pf_main") },
    ...(course ? [{ label: course.ko, href: pf("pf_room_week", { kj: course.id }) }] : []),
    { label: title },
  ];
  return el("section", {},
    el("header", { class: "pf-head" },
      el("div", { class: "pf-title" },
        crumbs ? breadcrumbs(crumbs) : null,
        opts.kicker ? el("p", { class: "pf-kicker", text: opts.kicker }) : null,
        el("h1", { text: title }),
        opts.sub ? el("p", { class: "pf-sub", text: opts.sub }) : null,
      ),
      opts.actions && opts.actions.length ? el("div", { class: "pf-actions" }, ...opts.actions) : null,
    ),
    ...nodes,
  );
}

function compareRows(a, b, key, dir) {
  const av = a.sort[key];
  const bv = b.sort[key];
  const empty = (value) => value == null || value === "";
  if (empty(av) && empty(bv)) return 0;
  if (empty(av)) return 1;
  if (empty(bv)) return -1;
  const base = typeof av === "number" && typeof bv === "number"
    ? av - bv
    : String(av).localeCompare(String(bv), "ko", { numeric: true, sensitivity: "base" });
  return dir === "desc" ? -base : base;
}

function dataTable(tableId, label, columns, records, ctx, options = {}) {
  const sort = ctx.state.draft.pfSort || {};
  const active = sort.table === tableId ? sort : null;
  const ordered = records.map((record, index) => ({ record, index }));
  if (active) {
    ordered.sort((a, b) => {
      const cmp = compareRows(a.record, b.record, active.key, active.dir);
      return cmp || a.index - b.index;
    });
  }
  const head = el("tr", {}, ...columns.map((col) => {
    const on = active && active.key === col.key;
    const aria = on ? (active.dir === "asc" ? "ascending" : "descending") : "none";
    if (!col.sort) return el("th", { scope: "col", class: col.lock ? "pf-lock" : null, text: col.label });
    return el("th", { scope: "col", class: col.lock ? "pf-lock" : null, "aria-sort": aria },
      el("button", {
        type: "button",
        class: "pf-sort",
        "data-act": "pf-sort",
        "data-table": tableId,
        "data-key": col.key,
        "aria-label": col.label,
      }, el("span", { text: col.label }), on ? el("span", { "aria-hidden": "true", text: active.dir === "asc" ? "↑" : "↓" }) : null),
    );
  }));
  const body = ordered.map(({ record }) => el("tr", {}, ...columns.map((col, index) => el("td", {
    "data-label": col.label,
    class: col.lock ? "pf-lock" : null,
  }, record.cells[index]))));
  const wrapClass = `table-wrap table-${options.layout === "scroll" ? "scroll" : "cards"}${options.wide ? " pf-wide" : ""}${options.attend ? " pf-attend" : ""}`;
  return el("div", { class: wrapClass, "data-table": tableId },
    el("table", { class: "grid", "aria-label": label }, el("thead", {}, head), el("tbody", {}, ...body)),
  );
}

function pfDialog(title, ...body) {
  return el("div", { class: "modal-back", "data-act": "pf-dismiss" },
    el("div", {
      class: "modal",
      role: "dialog",
      "aria-modal": "true",
      "aria-label": title,
      "data-act": "pf-dialog",
    },
      el("div", { class: "modal-h" },
        el("h2", { text: title }),
        el("button", { type: "button", class: "x", "data-act": "pf-dismiss", "aria-label": "닫기", text: "×" }),
      ),
      el("div", { class: "modal-b" }, ...body),
    ),
  );
}

function submissionOf(state, person, activityId = "r-open") {
  if (person.id === "seoyeon") return { at: "2026-11-09 09:14", fileName: "tm-note.pdf" };
  if (person.id === "minjun") return state.submissions[activityId] || null;
  return null;
}

function statusBadge(text, submitted) {
  return badge(text, submitted ? "success" : "danger");
}

function koreanWeek(n) {
  const start = new Date(2026, 7, 31 + (n - 1) * 7);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  const fmt = (d) => `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return `${fmt(start)} – ${fmt(end)}`;
}

function linkBtn(href, label, kind = "secondary") {
  return go(href, label, { class: `btn btn-${kind}` });
}

/** A screen the backend has nothing for, in a server course: no sample rows, only why and what is missing. */
function unavailable(title, body, ...actions) {
  return el("div", { class: "stack" }, backendPanel(), emptyState(title, body, ...actions));
}

export function renderProf(screen, ctx) {
  const kind = screen.kind;
  if (kind === "pf-main") return main(ctx);
  if (kind === "pf-room") return room(screen, ctx);
  if (kind === "pf-form") return form(screen, ctx);
  if (kind === "pf-status") return status(ctx);
  if (kind === "pf-grade") return grade(ctx);
  if (kind === "pf-exam-status") return examStatus(ctx);
  if (kind === "pf-exam-grade") return examGrade(ctx);
  if (kind === "pf-bank") return bank(ctx);
  if (kind === "pf-attend") return attend(screen, ctx);
  if (kind === "pf-gradebook") return gradebook(ctx);
  if (kind === "pf-appeal") return appeal(ctx);
  if (kind === "pf-team") return teams(ctx);
  if (kind === "pf-monitor") return monitor(ctx);
  if (kind === "pf-ta") return ta(ctx);
  if (kind === "pf-courses") return courseList(ctx);
  if (kind === "pf-study") return studyRoom(ctx);
  if (kind === "pf-lecture") return lecturePreview(ctx);
  if (kind === "pf-submissions") return submissions(ctx);
  if (kind === "pf-quiz") return quizDetail(ctx);
  if (kind === "pf-pdf") return pdfPreview(ctx);
  if (kind === "pf-notice") return noticeBoard(ctx);
  if (kind === "pf-qna") return qnaBoard(ctx);
  if (kind === "pf-calendar") return calendarBoard(ctx);
  return emptyState("화면을 준비하지 못했습니다", screen.id);
}

/** One course card on the professor home. `unread` > 0 adds the server's unread notice count. */
function courseCard(c, unread) {
  const href = pf("pf_room_week", { kj: c.id });
  return el("article", { class: "course-card" },
    el("a", { class: "course-card-main", href, "data-go": href },
      el("div", { class: `cover ${c.gradient}` }, coverImage(c), el("b", { text: c.cardTitle || c.ko }), el("span", { text: c.code })),
      el("div", { class: "meta" }, el("div", { text: `${c.learners}명` }), el("div", { text: c.time }),
        unread ? el("div", { class: "pf-unread", text: `안 읽은 공지 ${unread}` }) : null),
    ),
    go(routeFor("professor", "study", { kj: c.id }), "학습실 입장", { class: "btn btn-primary course-study-entry", "aria-label": `${c.cardTitle || c.ko} 학습실 입장` }),
  );
}

function main(ctx) {
  const mine = ["bi", "db", "sec"].map(courseById);
  const openReports = activities.filter((a) => a.type === "report");
  const waiting = ctx.state.appeals.filter((a) => a.status === "대기");
  const tasks = [
    ...openReports.map((a) => listRow({
      href: pf("pf_report_grade", { kj: a.courseId || "bi" }),
      title: a.title,
      detail: `${typeLabel(a.type)} · ${a.week}주차`,
      meta: "채점",
    })),
    ...waiting.map((a) => listRow({
      href: pf("pf_appeal", { kj: "bi" }),
      title: `${a.who} · ${a.item}`,
      detail: a.body,
      meta: "이의",
    })),
  ];
  // Backend `ready`: course cards, the course count and latest notices come from GET /api/me/home.
  const remote = backendStatus() === "ready";
  const countStat = stat("담당 과목", remote ? "" : String(mine.length));
  return el("section", {},
    el("header", { class: "pf-head" },
      el("div", { class: "pf-title" },
        el("p", { class: "pf-kicker", text: "2026학년도 2학기" }),
        el("h1", { text: "교수 홈" }),
        el("p", { class: "pf-sub", text: `${teacher.name} · ${teacher.dept}` }),
      ),
      el("div", { class: "pf-actions" },
        linkBtn(pf("pf_bank"), "문제은행", "primary"),
        linkBtn(pf("pf_contents"), "콘텐츠관리"),
        linkBtn(routeFor("professor", "calendar"), "일정"),
        linkBtn(pf("pf_report_grade", { kj: "bi" }), "채점"),
      ),
    ),
    el("div", { class: "stat-row" },
      countStat,
      stat("미채점 과제", String(openReports.length)),
      stat("이의", String(waiting.length)),
      stat("콘텐츠", String(ctx.state.contents.length)),
    ),
    el("h2", { class: "section-label", text: "강의과목" }),
    backendStatus() === "anon" ? backendPanel({ text: "ASTRA 서버에 로그인하면 담당 과목과 새 공지를 서버에서 불러옵니다." }) : null,
    remote ? serverProfessorHome(courseCard, countStat.querySelector("b")) : el("div", { class: "cards" }, ...mine.map((c) => {
      const href = pf("pf_room_week", { kj: c.id });
      return el("article", { class: "course-card" },
        el("a", { class: "course-card-main", href, "data-go": href },
          el("div", { class: `cover ${c.gradient}` }, coverImage(c), el("b", { text: c.cardTitle || c.ko }), el("span", { class: "code", text: c.code })),
          el("div", { class: "meta" }, el("div", { text: `${c.learners}명` }), el("div", { text: c.time })),
        ),
        go(routeFor("professor", "study", { kj: c.id }), "학습실 입장", { class: "btn btn-secondary course-study-entry", "aria-label": `${c.cardTitle || c.ko} 학습실 입장` }),
      );
    })),
    el("h2", { class: "section-label", text: "확인할 일" }),
    tasks.length ? el("div", {}, ...tasks) : emptyState("남은 할 일이 없습니다", "채점과 이의신청이 생기면 여기에 모입니다."),
    el("h2", { class: "section-label", text: "다가오는 일정" }),
    el("div", {}, ...events.filter((ev) => ev.activityId).slice(0, 6).map((ev) => {
      const activity = activities.find((item) => item.id === ev.activityId);
      return listRow({
        href: activity ? routeFor("professor", "activity", { activity, kj: activity.courseId }) : routeFor("professor", "calendar", { day: ev.date }),
        title: ev.label || ev.title,
        detail: ev.date,
        meta: activity ? typeLabel(activity.type) : "일정",
      });
    })),
  );
}

function room(screen, ctx) {
  const course = courseOf(ctx);
  const mode = screen.mode || "week";
  const week = ctx.state.week || 1;
  const mine = activities.filter((a) => a.courseId === course.id);
  const due = mine.find((a) => a.due && a.status !== "done" && a.status !== "graded");
  const banner = el("div", { class: "banner-course" },
    el("div", {},
      el("div", { class: "k", text: "2026학년도 2학기" }),
      el("h1", { text: course.ko }),
      el("div", { text: `${course.en} · ${course.code}` }),
    ),
    roleSwitch(course),
  );
  const modeTabs = tabs([
    { label: "주별", href: pf("pf_room_week", { kj: course.id }), on: mode === "week" },
    { label: "수업활동", href: pf("pf_room_activity", { kj: course.id }), on: mode === "activity" },
  ]);
  // Backend: the server weeks and activities of a course on the server (areas/professor/room-server.js).
  if (serverRoom(course)) return el("section", {}, banner, modeTabs, serverRoomView(mode, ctx, course, { stat, chipRow, finder }));
  const widgets = el("div", { class: "stat-row" },
    stat("수강생", String(course.learners)),
    stat("활동", String(mine.length)),
    stat("진도", `${course.progress}%`, el("div", { class: "bar", "aria-hidden": "true" }, el("i", { style: `width:${course.progress}%` }))),
    stat("마감", dday(due && due.due) || "없음"),
  );
  if (mode === "activity") {
    const type = param(ctx, "type");
    const shown = mine.filter((a) => !type || a.type === type).filter((a) => matchesQuery(ctx, `${a.title} ${typeLabel(a.type)}`));
    const chips = chipRow(ctx, "pf_room_activity", [["all", "전체", "all"], ...TYPES.map(([id, label]) => [id, `${label} ${mine.filter((a) => a.type === id).length}`, id])], "type");
    const rows = shown.map((a) => activityCard(a, ctx));
    return withActivityDialog(el("section", {}, banner, modeTabs, widgets,
      el("div", { class: "pf-toolbar" }, chips, finder(ctx, "활동 검색")),
      rows.length ? el("div", { class: "pf-activities" }, ...rows) : emptyState("해당하는 활동이 없습니다", "다른 유형을 선택하거나 검색어를 지워 보세요."),
    ), ctx);
  }
  const nums = el("div", { class: "weekbar", role: "group", "aria-label": "주차" });
  for (let n = 1; n <= weekCount; n += 1) {
    const has = mine.some((a) => a.week === n);
    nums.append(el("button", {
      type: "button",
      class: `week-num${week === n ? " on" : ""}${has ? " done" : ""}`,
      "data-act": "week",
      "data-week": String(n),
      "aria-label": `${n}주차`,
      "aria-pressed": week === n ? "true" : "false",
      text: String(n),
    }));
  }
  const items = mine.filter((a) => a.week === week);
  const author = ctx.state.role === "professor";
  const block = el("div", { class: "week-block" },
    el("div", { class: "pf-week-head" },
      el("h2", { text: `${week}주차 · ${koreanWeek(week)}` }),
      author ? linkBtn(pf("pf_week_add", { kj: course.id, week: String(week) }), "활동 등록", "primary") : null,
    ),
    items.length
      ? el("div", { class: "pf-activities" }, ...items.map((a) => activityCard(a, ctx)))
      : emptyState("이 주차에 등록된 활동이 없습니다", "활동 등록에서 영상, Zoom, 자료, 글, 과제, 시험을 넣을 수 있습니다."),
  );
  return withActivityDialog(el("section", {}, banner, modeTabs, widgets, nums, block), ctx);
}

function withActivityDialog(page, ctx) {
  const dialog = activityDeleteDialog(ctx);
  if (dialog) page.append(dialog);
  return page;
}

function activityDeleteDialog(ctx) {
  const dialog = ctx.state.draft.pfDialog;
  if (!dialog || dialog.kind !== "delete-activity") return null;
  const item = activities.find((row) => row.id === dialog.id);
  const name = item ? item.title : "이 활동";
  return pfDialog("활동 삭제",
    el("p", { text: item && isRemotePdfId(item.pdfId)
      ? `「${name}」을 삭제합니다. 이 브라우저의 활동만 지워지고 ASTRA 서버에 등록한 자료는 남습니다. 삭제한 뒤에는 되돌릴 수 없습니다.`
      : `「${name}」을 삭제합니다. 이 브라우저에서만 지워지고, 삭제한 뒤에는 되돌릴 수 없습니다.` }),
    el("div", { class: "pf-actions" },
      btn("취소", { "data-act": "pf-dismiss" }, "secondary"),
      btn("삭제", { "data-act": "pf-confirm-delete" }, "danger"),
    ),
  );
}

function roleSwitch(course) {
  const href = routeFor("student", "classroom", { kj: course.id, special: course.kind === "special" });
  return el("a", {
    class: "btn btn-secondary",
    href,
    "data-go": href,
    "data-role-switch": "student",
    "data-exit": "role",
    text: "학생 화면으로 보기",
  });
}

function activityCard(a, ctx) {
  const status = a.authored || a.attendStart || a.attendEnd ? activityPhase(a) : a.status;
  const [label, tone] = ACTIVITY_STATUS[status] || ACTIVITY_STATUS[a.status] || [status || "열림", "neutral"];
  const courseId = a.courseId || (ctx && ctx.state.courseId);
  const href = routeFor("professor", "activity", { activity: a, kj: courseId });
  const open = el("a", {
    class: "pf-activity-main",
    href,
    "data-go": href,
    "data-row": a.id,
    "data-activity": a.id,
  },
  el("span", { class: `pill ${a.type}`, text: typeLabel(a.type) }),
  el("span", { class: "pf-activity-copy" },
    el("b", { text: a.title }),
    el("span", { class: "pf-meta", text: `${a.week}주차${a.points ? ` · ${a.points}점` : ""}` }),
  ),
  badge(label, tone),
  );
  const actions = ctx && ctx.state.role === "professor"
    ? el("div", { class: "pf-activity-actions" },
      go(pf("pf_week_add", { kj: courseId, edit: a.id, week: String(a.week || 1) }), "수정", { class: "btn btn-ghost", "aria-label": `${a.title} 수정` }),
      btn("삭제", { "data-act": "pf-delete-activity", "data-id": a.id, "aria-label": `${a.title} 삭제` }, "ghost"),
    )
    : null;
  return el("div", { class: "pf-activity", "data-activity": a.id }, open, actions);
}

function form(screen, ctx) {
  const err = ctx.state.formError || {};
  const course = screen.course ? courseOf(ctx) : null;
  const editing = screen.form === "activity" ? activities.find((item) => item.id === param(ctx, "edit")) : null;
  // Backend mode: server 쪽지 to course members (areas/professor/messages-server.js); `anon` shows only the login strip.
  if (screen.form === "pmessage" && course && backendStatus() !== "off") {
    return frame("쪽지 발송", ctx, { course: true, kicker: course.ko, sub: "ASTRA 서버에서 이 과목 구성원을 찾아 쪽지를 보냅니다. 제목과 내용은 일반 텍스트로 보냅니다.", actions: [] }, serverMessageFields(ctx, course, { formSheet, span }));
  }
  const map = {
    activity: serverRoom(course)
      ? ["활동 등록", "ASTRA 서버에 만들 수 있는 유형만 고릅니다. 고른 주차를 가지고 그 유형의 서버 등록 화면으로 갑니다.", serverActivityChooser(ctx, course), [
        linkBtn(pf("pf_room_week", { kj: course.id }), "주별로"),
      ]]
      : [editing ? "활동 수정" : "활동 등록", backendStatus() === "ready"
        ? "활동은 이 브라우저에 저장됩니다. 자료 유형의 PDF는 ASTRA 서버에 올려 강의자료로 등록합니다."
        : "영상, Zoom, 자료, 글, 과제, 시험은 이 브라우저에만 저장됩니다.", activityFields(ctx, err, editing), [
        linkBtn(pf("pf_room_week", { kj: course && course.id }), "주별로"),
      ]],
    contents: ["영상 콘텐츠", serverVideoCourse(courseOf(ctx))
      ? "ASTRA 서버에 강의 영상을 올려 영상 강의로 등록하고, 변환 상태를 확인하고, 수정·삭제합니다."
      : "등록한 영상은 이 브라우저에만 남습니다.", contentFields(ctx, err), []],
    zoom: serverRoom(courseOf(ctx))
      ? ["실시간 강의", "실시간 강의(Zoom)는 ASTRA 서버에 아직 없습니다.", unavailable("실시간 강의는 아직 ASTRA 서버에 없습니다", "서버에 회의 등록 기능이 생기면 여기에서 회의 주소와 시간을 등록합니다. 이 브라우저에만 남는 샘플 회의는 서버 과목에서 보이지 않습니다."), []]
      : ["실시간 강의", "회의 번호는 숫자 9자리에서 11자리입니다.", zoomFields(err, ctx), []],
    notice: ["공지 작성", backendStatus() === "off"
      ? "수강생 강의실 공지에 샘플로 추가됩니다."
      : "ASTRA 서버의 강의실 공지로 등록합니다. 제목과 본문은 일반 텍스트로 저장됩니다.", noticeFields(err, ctx, course), []],
    material: ["자료 등록", "자료명과 파일을 함께 올립니다.", materialFields(err, course, ctx), []],
    report: backendStatus() !== "off" && course
      ? ["과제 출제", "ASTRA 서버에 과제를 등록하고 고칩니다. 제출 구간은 서버 시각으로 정해지고, 설명은 일반 텍스트로 저장됩니다.", reportFields(err, ctx, course), [
        linkBtn(pf("pf_report_status", { kj: course.id }), "제출 현황"),
        linkBtn(pf("pf_report_grade", { kj: course.id }), "채점"),
      ]]
      : ["과제 출제", "마감은 시작 이후여야 하고 배점은 1부터 100입니다.", reportFields(err), [
        linkBtn(pf("pf_report_status", { kj: "bi" }), "제출 현황"),
        linkBtn(pf("pf_report_grade", { kj: "bi" }), "채점"),
      ]],
    exam: backendStatus() !== "off" && course
      ? ["시험 출제", "ASTRA 서버에 시험을 등록하고 고칩니다. 응시 구간과 응시 마감은 서버 시각으로 정해지고, 문항과 안내는 일반 텍스트로 저장됩니다.", examFields(ctx, err, course), [
        linkBtn(pf("pf_exam_status", { kj: course.id }), "응시 현황"),
        linkBtn(pf("pf_exam_grade", { kj: course.id }), "채점"),
      ]]
      : ["시험 출제", "문제은행에서 문항을 하나 이상 골랐는지 확인합니다.", examFields(ctx, err), [
        linkBtn(pf("pf_exam_status", { kj: "bi" }), "응시 현황"),
        linkBtn(pf("pf_bank"), "문제은행"),
      ]],
    discuss: backendStatus() !== "off" && course
      ? ["토론·설문", "설문은 ASTRA 서버에 만들고 결과를 봅니다. 토론은 아직 서버에 없어 이 브라우저에만 저장됩니다.", el("div", { class: "stack" },
        serverSurveyFields(ctx, course, { formSheet, span, linkBtn }),
        discussFields(err, true),
      ), []]
      : ["토론·설문", "토론 또는 설문 안내를 만듭니다.", discussFields(err), []],
    pmessage: ["쪽지 발송", "수강생 전체 또는 한 명에게 샘플 쪽지를 남깁니다.", messageFields(err), []],
  };
  const [title, sub, body, actions] = map[screen.form] || ["작성", "", el("p", { text: "" }), []];
  return frame(title, ctx, { course: Boolean(course), kicker: course ? course.ko : "교수", sub, actions }, body);
}

function formSheet(title, ...nodes) {
  return el("div", { class: "sheet" }, el("h2", { text: title }), el("div", { class: "pf-form" }, ...nodes));
}

function span(node) {
  node.classList.add("span-2");
  return node;
}

function defaultClip(courseId) {
  if (LECTURES[courseId]) return courseId;
  const row = Object.values(LECTURES).find((item) => item.courseId === courseId);
  return row ? row.id : "bi";
}

function clipOptions(selected) {
  return Object.values(LECTURES).map((row) => el("option", {
    value: row.id,
    text: `${row.course} · ${row.week}`,
    selected: row.id === selected ? true : null,
  }));
}

function typePanel(type, current, ...nodes) {
  return el("div", {
    class: "pf-type-panel span-2",
    "data-type-panel": type,
    hidden: type === current ? null : true,
  }, ...nodes);
}

function activityFields(ctx, err, editing) {
  const current = editing?.type || "video";
  const course = courseOf(ctx);
  const weekValue = editing ? String(editing.week || 1) : (param(ctx, "week") || String(ctx.state.week || 9));
  const clip = editing?.clipId || defaultClip(course.id);
  return el("div", { class: "sheet" },
    el("h2", { text: editing ? "활동 수정" : "활동 정보" }),
    el("div", { class: "pf-form pf-activity-form", "data-edit": editing ? editing.id : "" },
      field("제목", el("input", { "data-name": "title", value: editing ? editing.title : "" }), err.title),
      field("주차", el("input", { "data-name": "week", type: "number", min: "1", max: "14", value: weekValue }), err.week),
      el("div", { class: "pf-types span-2", role: "radiogroup", "aria-label": "활동 유형" },
        el("input", { type: "hidden", "data-name": "type", value: current }),
        ...TYPES.map(([value, label]) => el("button", {
          type: "button",
          class: `chip ${value}${value === current ? " on" : ""}`,
          "data-act": "activity-type",
          "data-value": value,
          "aria-pressed": value === current ? "true" : "false",
          text: label,
        })),
      ),
      err.type ? span(el("p", { class: "err", text: err.type })) : null,
      serverVideoCourse(course) ? typePanel("video", current, serverLectureCreate(course)) : typePanel("video", current,
        field("설명", el("textarea", { "data-name": "videoBody", rows: "4" }, editing && editing.type === "video" ? editing.body || "" : ""), err.videoBody),
        field("샘플 영상", el("select", { "data-name": "clip" }, ...clipOptions(clip)), err.clip),
        field("영상 파일", el("input", { type: "file", "data-name": "videoFile", accept: "video/*" }), err.videoFile),
        field("출석 시작", el("input", { type: "datetime-local", "data-name": "attendStart", value: editing && editing.type === "video" ? editing.attendStart || "" : "" }), err.attendStart),
        field("출석 종료", el("input", { type: "datetime-local", "data-name": "attendEnd", value: editing && editing.type === "video" ? editing.attendEnd || "" : "" }), err.attendEnd),
      ),
      typePanel("zoom", current,
        field("회의 링크", el("input", { type: "url", "data-name": "meetingUrl", placeholder: "회의 주소", value: editing && editing.type === "zoom" ? editing.meetingUrl || "" : "" }), err.meetingUrl),
        field("시작", el("input", { type: "datetime-local", "data-name": "zoomStart", value: editing && editing.type === "zoom" ? editing.start || "" : "" }), err.zoomStart),
        field("종료", el("input", { type: "datetime-local", "data-name": "zoomEnd", value: editing && editing.type === "zoom" ? editing.end || "" : "" }), err.zoomEnd),
        field("설명", el("textarea", { "data-name": "zoomBody", rows: "4" }, editing && editing.type === "zoom" ? editing.body || "" : ""), err.zoomBody),
      ),
      typePanel("material", current,
        field("설명", el("textarea", { "data-name": "materialBody", rows: "4" }, editing && editing.type === "material" ? editing.body || "" : ""), err.materialBody),
        field("파일", el("input", { type: "file", "data-name": "materialFile" }), err.materialFile),
        el("p", { class: "pf-sub", text: editing && editing.fileName ? `현재 파일 ${editing.fileName}. PDF는 학습실 뷰어에서 열리고, 다른 파일은 다운로드됩니다.` : "PDF, PPT 또는 다른 파일을 올립니다. PDF는 학습실 뷰어에서 열리고, 다른 파일은 다운로드됩니다." }),
      ),
      typePanel("post", current,
        el("div", { class: "pf-editor" },
          el("div", { class: "pf-actions" },
            btn("굵게", { "data-act": "editor-insert", "data-cmd": "bold" }, "ghost"),
            btn("목록", { "data-act": "editor-insert", "data-cmd": "list" }, "ghost"),
            btn("링크", { "data-act": "editor-insert", "data-cmd": "link" }, "ghost"),
          ),
          field("본문", el("textarea", { "data-name": "body", rows: "8" }, editing && editing.type === "post" ? editing.body || "" : ""), err.body),
        ),
      ),
      typePanel("report", current,
        field("설명", el("textarea", { "data-name": "reportBody", rows: "4" }, editing && editing.type === "report" ? editing.body || "" : ""), err.reportBody),
        field("배점", el("input", { type: "number", "data-name": "points", min: "1", max: "100", value: editing && editing.points ? String(editing.points) : "20" }), err.points),
        field("시작", el("input", { type: "datetime-local", "data-name": "reportStart", value: editing && editing.type === "report" ? editing.start || "" : "" }), err.reportStart),
        field("마감", el("input", { type: "datetime-local", "data-name": "reportDue", value: editing && editing.type === "report" ? editing.due || "" : "" }), err.reportDue),
      ),
      typePanel("test", current,
        field("설명", el("textarea", { "data-name": "testBody", rows: "4" }, editing && editing.type === "test" ? editing.body || "" : ""), err.testBody),
        field("시작", el("input", { type: "datetime-local", "data-name": "testStart", value: editing && editing.type === "test" ? editing.start || "" : "" }), err.testStart),
        field("종료", el("input", { type: "datetime-local", "data-name": "testDue", value: editing && editing.type === "test" ? editing.due || "" : "" }), err.testDue),
        field("제한 시간(분)", el("input", { type: "number", "data-name": "minutes", min: "0", max: "300", value: editing && editing.type === "test" && editing.minutes != null ? String(editing.minutes) : "" }), err.minutes),
      ),
      typePanel("survey", current,
        field("설명", el("textarea", { "data-name": "surveyBody", rows: "4" }, editing && editing.type === "survey" ? editing.body || "" : ""), err.surveyBody),
        field("마감", el("input", { type: "datetime-local", "data-name": "surveyDue", value: editing && editing.type === "survey" ? editing.due || "" : "" }), err.surveyDue),
      ),
      span(el("div", { class: "pf-actions" }, btn(editing ? "저장" : "등록", { "data-act": "pf-save", "data-form": "activity" }, "primary"))),
    ),
  );
}

function contentFields(ctx, err) {
  // Backend mode: server video lectures (areas/professor/lectures-server.js).
  if (serverVideoCourse(courseOf(ctx))) return serverLectureManager(courseOf(ctx), { weekNo: ctx.state.week, open: param(ctx, "open") });
  const records = ctx.state.contents.filter((c) => matchesQuery(ctx, c.title)).map((c) => ({
    sort: { title: c.title, minutes: c.minutes, at: c.at },
    cells: [c.title, `${c.minutes}분`, c.at],
  }));
  return el("div", { class: "stack" },
    el("div", { class: "pf-toolbar" }, finder(ctx, "콘텐츠 검색")),
    records.length
      ? dataTable("contents", "영상 콘텐츠", [
        { key: "title", label: "제목", sort: true },
        { key: "minutes", label: "길이", sort: true },
        { key: "at", label: "등록", sort: true },
      ], records, ctx)
      : emptyState("해당하는 콘텐츠가 없습니다", "아래에서 새 영상을 등록할 수 있습니다."),
    formSheet("새 영상",
      span(field("제목", el("input", { "data-name": "title" }), err.title)),
      span(field("파일", el("input", { type: "file", "data-name": "file" }), err.file)),
      span(el("div", { class: "pf-actions" }, btn("콘텐츠 추가", { "data-act": "pf-save", "data-form": "contents" }, "primary"))),
    ),
  );
}

function zoomFields(err, ctx) {
  const records = ctx.state.zooms.filter((z) => matchesQuery(ctx, z.title)).map((z) => ({
    sort: { title: z.title, meeting: z.meeting },
    cells: [z.title, z.meeting],
  }));
  return el("div", { class: "stack" },
    formSheet("회의 정보",
      span(field("제목", el("input", { "data-name": "title" }), err.title)),
      field("주차", el("input", { "data-name": "week", type: "number", min: "1", max: "14" }), err.week),
      field("시작", el("input", { "data-name": "start", type: "datetime-local" }), err.start),
      span(field("회의 번호", el("input", { "data-name": "meeting", placeholder: "숫자 9–11자리" }), err.meeting)),
      span(el("div", { class: "pf-actions" }, btn("등록", { "data-act": "pf-save", "data-form": "zoom" }, "primary"))),
    ),
    records.length ? dataTable("zooms", "등록된 실시간 강의", [
      { key: "title", label: "제목", sort: true },
      { key: "meeting", label: "번호", sort: true },
    ], records, ctx) : null,
  );
}

function noticeFields(err, ctx, course) {
  // Backend mode: server form and list (areas/professor/notices-server.js); `anon` shows only the login strip.
  if (backendStatus() !== "off" && course) return serverNoticeFields(ctx, course, { formSheet, span, pf, linkBtn });
  return formSheet("공지",
    span(field("제목", el("input", { "data-name": "title" }), err.title)),
    span(field("본문", el("textarea", { "data-name": "body" }), err.body)),
    span(el("label", { class: "choice pf-check" }, el("input", { type: "checkbox", "data-name": "pin" }), el("span", { text: "상단 고정" }))),
    span(el("div", { class: "pf-actions" }, btn("공지 저장", { "data-act": "pf-save", "data-form": "notice" }, "primary"))),
  );
}

function materialFields(err, course, ctx) {
  const status = backendStatus();
  const guidance = status === "ready"
    ? "PDF는 ASTRA 서버에 업로드되어 이 과목의 강의자료로 등록됩니다(최대 50MB). 등록한 PDF에서 쪽별 요약을 만들면 쪽 이미지와 텍스트가 서버에 설정된 LLM 제공자로 전송됩니다. 다른 형식은 파일명만 이 브라우저에 등록됩니다."
    : "PDF는 등록 시 실제 페이지와 텍스트를 분석해 이 과목 학습실에 저장합니다. 원본과 분석 결과는 이 브라우저에만 남으며 학교 서버나 다른 기기로 전송되지 않습니다. 스캔 PDF는 OCR 없이 텍스트를 추출할 수 없습니다. 다른 형식은 파일명만 등록됩니다.";
  const sheet = formSheet("자료",
    span(field("자료명", el("input", { "data-name": "title" }), err.title)),
    span(field("파일", el("input", { type: "file", "data-name": "file", "aria-describedby": "material-pdf-guidance material-pdf-status" }), err.file)),
    status === "ready" && course ? registrationAttach(course) : null,
    span(el("p", { id: "material-pdf-guidance", class: "pf-sub", text: guidance })),
    span(el("p", { id: "material-pdf-status", class: "pf-sub", "data-material-status": "", role: "status", "aria-live": "polite" })),
    status === "ready" && course && ctx ? span(el("p", { class: "pf-sub", text: `등록할 주차: ${ctx.state.week || 1}주차. 강의실 주별 보기나 활동 등록에서 주차를 고른 뒤 오면 그 주차에 등록합니다.` })) : null,
    span(el("div", { class: "pf-actions" }, btn("등록", { "data-act": "pf-save", "data-form": "material" }, "primary"))),
  );
  // Mock mode keeps the original single form.
  if (status === "off" || !course) return sheet;
  // Backend mode: the course's external AI consent sits above the server materials (llm-consent-server.js).
  return el("div", { class: "stack" }, backendPanel(), sheet, status === "ready" ? llmConsentSection(course) : null, status === "ready" ? serverMaterialList(course, summaryJobPanel, { open: ctx ? param(ctx, "open") : "" }) : null);
}

function sizeLabel(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return "";
  return n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`;
}

const JOB_STATE = {
  queued: "대기 중",
  running: "생성 중",
  completed: "후보 준비됨",
  failed: "실패",
  cancelled: "취소됨",
  interrupted: "중단됨",
};
const JOB_STAGE = { rendition: "쪽 이미지 준비", pages: "쪽별 설명", story: "흐름 정리", validate: "검증" };
const POLL_MS = 4000;
const publishedAt = wallFormat("ko-KR", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

/** Summary job controls for one server PDF: create, live progress, cancel, retry, preview, publish. */
function summaryJobPanel(record) {
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", text: "요약 상태를 불러오는 중입니다." });
  const progress = el("div", { class: "pf-summary-progress" });
  const controls = el("div", { class: "pf-actions" });
  const error = el("p", { class: "err", role: "alert" });
  const preview = el("div", { class: "pf-summary-preview" });
  const panel = el("div", { class: "pf-summary", "aria-label": `${record.title} 쪽별 요약` },
    el("p", { class: "pf-sub", text: "요약을 만들면 이 PDF의 쪽 이미지와 텍스트가 ASTRA 서버에 설정된 LLM 제공자로 전송됩니다. 후보를 확인하고 게시해야 학생 학습실에 보입니다." }),
    status, progress, controls, error, preview,
  );
  let job = null;
  let published = null;
  let result = null;
  let busy = false;
  let timer = 0;
  // The job published from this panel. The published view has no job id, so a panel opened later recognises the
  // published job by its pack: the server publishes the job's candidate unchanged (same question, story and slides).
  let publishedJobId = null;
  const packOf = (view) => JSON.stringify([view.question ?? null, view.story ?? null, view.slides ?? null]);
  const isPublished = () => Boolean(job && ((publishedJobId === job.id) || (published && result && packOf(published) === packOf(result))));

  const action = (label, kind, run) => {
    const button = btn(label, {}, kind);
    button.disabled = busy;
    button.addEventListener("click", () => perform(run));
    return button;
  };

  async function perform(run) {
    if (busy) return;
    busy = true;
    error.textContent = "";
    paint();
    try {
      await run();
    } catch (failure) {
      if (panel.isConnected) error.textContent = summaryMessage(failure);
      // 409 `llm_consent_required` / 503 `llm_disabled`: lead to the consent section instead of a retry.
      const lead = consentLead(failure);
      if (panel.isConnected && lead) error.append(" ", lead);
    } finally {
      busy = false;
      if (panel.isConnected) paint();
    }
  }

  async function adopt(next) {
    job = next;
    result = null;
    if (job && job.state === "completed") result = await getSummaryResult(record, job.id);
    schedule();
  }

  function schedule() {
    clearTimeout(timer);
    if (!job || (job.state !== "queued" && job.state !== "running")) return;
    timer = setTimeout(() => {
      if (!panel.isConnected) return; // left the screen: stop polling
      getSummaryJob(record, job.id).then(adopt).then(() => {
        if (panel.isConnected) paint();
      }, (failure) => {
        if (!panel.isConnected) return;
        error.textContent = summaryMessage(failure);
        // A completed job whose candidate could not be read stops polling here; repaint so the status and buttons
        // leave "생성 중 · 취소" and offer to read the candidate again.
        paint();
        schedule();
      });
    }, POLL_MS);
  }

  function paint() {
    const lines = [];
    if (published) {
      const at = publishedAt.format(published.publishedAt);
      lines.push(`게시된 요약 있음${at ? ` (${at})` : ""}`);
    }
    if (!job) lines.push("아직 만든 요약이 없습니다.");
    else {
      const stage = job.state === "running" && JOB_STAGE[job.stage] ? ` · ${JOB_STAGE[job.stage]}` : "";
      const pages = job.pagesTotal ? ` · ${job.pagesDone || 0}/${job.pagesTotal}쪽` : "";
      const reason = job.state === "failed" && (job.errorMessage || job.errorCode) ? ` · ${job.errorMessage || job.errorCode}` : "";
      lines.push(`최근 요약 작업: ${JOB_STATE[job.state] || job.state}${stage}${pages}${job.cancelRequested && job.state !== "cancelled" ? " · 취소 요청됨" : ""}${reason}`);
    }
    status.textContent = lines.join(" · ");
    progress.replaceChildren(job && (job.state === "queued" || job.state === "running")
      ? el("progress", { max: String(job.pagesTotal || 1), value: job.pagesTotal ? String(job.pagesDone || 0) : null, "aria-label": "요약 진행률" })
      : "");
    const buttons = [];
    const start = () => startSummaryJob(record).then(adopt);
    if (!job) buttons.push(action("요약 생성", "primary", start));
    else if (job.state === "queued" || job.state === "running") {
      const cancel = action("취소", "secondary", () => cancelSummaryJob(record, job.id).then(adopt));
      if (job.cancelRequested) cancel.disabled = true;
      buttons.push(cancel);
    } else if (job.state === "completed" && !result) {
      // The job finished but its candidate could not be read: read it again before anything can be published.
      buttons.push(action("후보 다시 불러오기", "primary", async () => {
        result = await getSummaryResult(record, job.id);
      }));
      buttons.push(action("요약 다시 생성", "secondary", start));
    } else if (job.state === "completed") {
      if (isPublished()) {
        const done = btn("게시됨", {}, "primary");
        done.disabled = true;
        buttons.push(done);
      } else {
        buttons.push(action("게시", "primary", async () => {
          const jobId = job.id;
          published = await publishSummary(record, jobId);
          publishedJobId = jobId;
        }));
      }
      buttons.push(action("요약 다시 생성", "secondary", start));
    } else {
      buttons.push(action("다시 시도", "primary", () => retrySummaryJob(record, job.id).then(adopt)));
    }
    controls.replaceChildren(...buttons);
    preview.replaceChildren(result
      ? el("div", { class: "stack" },
        el("h3", { text: "요약 후보 미리보기" }),
        el("p", { class: "pf-sub", text: "학생에게는 게시한 뒤에 보입니다. 검토가 필요한 쪽과 경고를 먼저 확인하세요." }),
        summaryView(result, { pageImageBase: null }))
      : "");
  }

  Promise.resolve().then(async () => {
    const [jobs, current] = await Promise.all([listSummaryJobs(record), getPublishedSummary(record)]);
    published = current;
    const latest = (Array.isArray(jobs) ? jobs : []).slice().sort((a, b) => b.id - a.id)[0] || null;
    await adopt(latest);
  }).catch((failure) => {
    if (panel.isConnected) error.textContent = summaryMessage(failure);
  }).finally(() => {
    if (panel.isConnected) paint();
  });
  return panel;
}

function reportFields(err, ctx, course) {
  // Backend mode: server form and list (areas/professor/assignments-server.js); `anon` shows only the login strip.
  if (course && backendStatus() !== "off") return serverReportFields(ctx, course, { formSheet, span, linkBtn });
  return formSheet("과제",
    field("제목", el("input", { "data-name": "title" }), err.title),
    field("배점", el("input", { type: "number", "data-name": "points", min: "1", max: "100", value: "20" }), err.points),
    field("시작", el("input", { type: "datetime-local", "data-name": "start" }), err.start),
    field("마감", el("input", { type: "datetime-local", "data-name": "due" }), err.due),
    span(el("label", { class: "choice pf-check" }, el("input", { type: "checkbox", "data-name": "late" }), el("span", { text: "지각 제출 허용" }))),
    span(el("div", { class: "pf-actions" }, btn("출제", { "data-act": "pf-save", "data-form": "report" }, "primary"))),
  );
}

function examFields(ctx, err, course) {
  // Backend mode: server quiz form and list (areas/professor/quizzes-server.js); `anon` shows only the login strip.
  if (course && backendStatus() !== "off") return serverExamFields(ctx, course, { formSheet, span, linkBtn });
  return formSheet("시험",
    field("시험명", el("input", { "data-name": "title" }), err.title),
    field("제한 시간(분)", el("input", { type: "number", "data-name": "minutes", min: "5", max: "180", value: "50" }), err.minutes),
    span(el("div", { class: "pf-picks" },
      el("p", { class: "pf-kicker", text: "문제은행에서 가져올 문항" }),
      ...ctx.state.questions.map((q) => el("label", { class: "choice" },
        el("input", { type: "checkbox", "data-name": "qid", value: q.id }),
        el("span", { text: `${q.stem} · ${q.type} · ${q.points}점` }),
      )),
      el("div", { "data-name": "questions" }),
      err.questions ? el("p", { class: "err", text: err.questions }) : null,
    )),
    span(el("div", { class: "pf-actions" }, btn("시험 저장", { "data-act": "pf-save", "data-form": "exam" }, "primary"))),
  );
}

function discussFields(err, discussOnly) {
  // Backend mode keeps only 토론 here; surveys are on the server (areas/professor/surveys-server.js).
  if (discussOnly) {
    return formSheet("토론",
      field("종류", el("select", { "data-name": "kind" }, el("option", { value: "discuss", text: "토론" }))),
      field("제목", el("input", { "data-name": "title" }), err.title),
      span(field("안내", el("textarea", { "data-name": "body" }), err.body)),
      span(el("p", { class: "pf-sub", text: "토론은 이 브라우저에만 저장됩니다." })),
      span(el("div", { class: "pf-actions" }, btn("토론 만들기", { "data-act": "pf-save", "data-form": "discuss" }, "primary"))),
    );
  }
  return formSheet("토론·설문",
    field("종류", el("select", { "data-name": "kind" }, el("option", { value: "discuss", text: "토론" }), el("option", { value: "survey", text: "설문" }))),
    field("제목", el("input", { "data-name": "title" }), err.title),
    span(field("안내", el("textarea", { "data-name": "body" }), err.body)),
    span(el("div", { class: "pf-actions" }, btn("만들기", { "data-act": "pf-save", "data-form": "discuss" }, "primary"))),
  );
}

function messageFields(err) {
  return formSheet("쪽지",
    field("대상", el("select", { "data-name": "to" }, el("option", { value: "all", text: "수강생 전체" }), ...learners().map((p) => el("option", { value: p.name, text: p.name })))),
    field("제목", el("input", { "data-name": "title" }), err.title),
    span(field("내용", el("textarea", { "data-name": "body" }), err.body)),
    span(el("div", { class: "pf-actions" }, btn("발송", { "data-act": "pf-save", "data-form": "pmessage" }, "primary"))),
  );
}

function peopleRows(ctx, decorate) {
  return learners().filter((p) => matchesQuery(ctx, `${p.name} ${p.no}`)).map((p, index) => decorate(p, index));
}

function status(ctx) {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverStatusFrame(ctx, course, "제출 현황", serverSubmissionStatus(ctx, course));
  const view = param(ctx, "view");
  const all = learners().map((p) => ({ person: p, sub: submissionOf(ctx.state, p) }));
  const rows = all.filter(({ person, sub }) => matchesQuery(ctx, `${person.name} ${person.no}`) && (view === "in" ? sub : view === "out" ? !sub : true));
  const records = rows.map(({ person, sub }) => ({
    sort: { no: person.no, name: person.name, status: sub ? "제출" : "미제출", at: sub ? sub.at : "" },
    cells: [
      person.no,
      person.name,
      statusBadge(sub ? "제출" : "미제출", Boolean(sub)),
      sub ? sub.at : "—",
      sub ? (sub.fileName || "첨부") : "—",
    ],
  }));
  return frame("제출 현황", ctx, {
    course: true,
    kicker: course.ko,
    sub: "텍스트마이닝 미션 2",
    actions: [
      linkBtn(pf("pf_report_create", { kj: course.id }), "과제 출제"),
      linkBtn(pf("pf_report_grade", { kj: course.id }), "채점", "primary"),
    ],
  },
    el("div", { class: "stat-row cols-3" },
      stat("수강생", String(all.length)),
      stat("제출", String(all.filter((row) => row.sub).length)),
      stat("미제출", String(all.filter((row) => !row.sub).length)),
    ),
    el("div", { class: "pf-toolbar" },
      chipRow(ctx, "pf_report_status", [["all", "전체"], ["in", "제출"], ["out", "미제출"]], "view"),
      finder(ctx, "이름, 학번"),
    ),
    records.length ? dataTable("submissions", "과제 제출 현황", [
      { key: "no", label: "학번", sort: true },
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "status", label: "상태", sort: true },
      { key: "at", label: "시각", sort: true },
      { key: "file", label: "파일" },
    ], records, ctx) : emptyState("조건에 맞는 수강생이 없습니다", "필터나 검색어를 바꿔 보세요."),
  );
}

function grade(ctx) {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverStatusFrame(ctx, course, "과제 채점", serverGrading(ctx, course));
  const err = ctx.state.formError || {};
  const records = peopleRows(ctx, (p) => {
    const sub = submissionOf(ctx.state, p);
    const score = (ctx.state.scores[p.id] && ctx.state.scores[p.id]["r-open"]);
    const shown = score == null ? "" : String(score);
    return {
      sort: { no: p.no, name: p.name, status: sub ? "제출완료" : "미제출", score: shown === "" ? null : Number(shown) },
      cells: [
        p.no,
        p.name,
        statusBadge(sub ? "제출완료" : "미제출", Boolean(sub)),
        el("input", { type: "number", min: "0", max: "100", "data-name": `score-${p.id}`, value: shown, "aria-label": `${p.name} 점수` }),
        sub ? (sub.fileName || "제출물") : "—",
      ],
    };
  });
  return frame("과제 채점", ctx, {
    course: true,
    kicker: course.ko,
    sub: "점수는 0부터 100 사이의 정수입니다. 이 브라우저에만 저장됩니다.",
    actions: [linkBtn(pf("pf_report_status", { kj: course.id }), "제출 현황")],
  },
    el("div", { class: "pf-toolbar" }, finder(ctx, "이름, 학번")),
    err.scores ? el("p", { class: "err", text: err.scores }) : null,
    records.length ? dataTable("grading", "과제 채점", [
      { key: "no", label: "학번", sort: true },
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "status", label: "상태", sort: true },
      { key: "score", label: "점수", sort: true },
      { key: "file", label: "파일" },
    ], records, ctx, { wide: true }) : emptyState("조건에 맞는 수강생이 없습니다", "검색어를 지워 보세요."),
    el("div", { class: "pf-actions" }, btn("점수 저장", { "data-act": "save-scores", "data-item": "r-open" }, "primary")),
  );
}

function examStatus(ctx) {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverExamFrame(ctx, course, "응시 현황", serverExamStatus(ctx, course));
  const view = param(ctx, "view");
  const roster = [
    { name: "김서연", status: "응시", at: "2026-10-22 11:40" },
    { name: "박민준", status: "응시", at: "2026-10-22 11:51" },
    { name: "이지은", status: "미응시", at: "" },
  ];
  const rows = roster.filter((row) => matchesQuery(ctx, row.name) && (view === "in" ? row.status === "응시" : view === "out" ? row.status !== "응시" : true));
  const records = rows.map((row) => ({
    sort: { name: row.name, status: row.status, at: row.at },
    cells: [row.name, statusBadge(row.status, row.status === "응시"), row.at || "—"],
  }));
  return frame("응시 현황", ctx, {
    course: true,
    kicker: course.ko,
    sub: "중간고사",
    actions: [
      linkBtn(pf("pf_exam_create", { kj: course.id }), "시험 출제"),
      linkBtn(pf("pf_exam_grade", { kj: course.id }), "채점", "primary"),
    ],
  },
    el("div", { class: "pf-toolbar" },
      chipRow(ctx, "pf_exam_status", [["all", "전체"], ["in", "응시"], ["out", "미응시"]], "view"),
      finder(ctx, "이름 검색"),
    ),
    records.length ? dataTable("exam-status", "시험 응시 현황", [
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "status", label: "상태", sort: true },
      { key: "at", label: "제출", sort: true },
    ], records, ctx) : emptyState("조건에 맞는 응시 기록이 없습니다", "필터를 전체로 돌려 보세요."),
  );
}

function examGrade(ctx) {
  const course = courseOf(ctx);
  if (backendStatus() !== "off") return serverExamFrame(ctx, course, "시험 채점", serverExamGrade(ctx, course));
  const err = ctx.state.formError || {};
  const q = ctx.state.questions.find((item) => item.stem.includes("과적합")) || ctx.state.questions[0];
  const saved = ctx.state.scores.minjun && ctx.state.scores.minjun.examQ;
  return frame("시험 채점", ctx, {
    course: true,
    kicker: course.ko,
    sub: "문항 점수는 0부터 5 사이의 정수입니다.",
    actions: [linkBtn(pf("pf_exam_status", { kj: course.id }), "응시 현황")],
  },
    q ? el("article", { class: "sheet pf-q" },
      el("div", { class: "pf-inline" }, badge(q.type, "neutral"), badge(`${q.points}점`, "neutral")),
      el("h2", { text: q.stem }),
      q.choices.length ? el("ol", {}, ...q.choices.map((choice) => el("li", { text: choice }))) : null,
    ) : null,
    el("div", { class: "sheet" },
      err.score ? el("p", { class: "err", text: err.score }) : null,
      field("박민준 점수", el("input", { type: "number", min: "0", max: "5", "data-name": "score", value: saved == null ? "4" : String(saved), "aria-label": "박민준 점수" })),
      el("div", { class: "pf-actions" }, btn("문항 점수 저장", { "data-act": "save-exam-score" }, "primary")),
    ),
  );
}

function bank(ctx) {
  // Backend mode: the server has no question bank; quiz questions are written per quiz (pf_exam_create).
  if (backendStatus() !== "off") {
    return frame("문제은행", ctx, { kicker: "교수", sub: "ASTRA 서버에는 문제은행이 아직 없습니다." },
      unavailable("문제은행은 아직 ASTRA 서버에 없습니다", "서버 시험의 문항은 시험 출제 화면에서 시험마다 직접 넣습니다. 이 브라우저에만 남는 샘플 은행과 문항은 서버에 연결된 동안 보이지 않습니다.",
        linkBtn(pf("pf_exam_create", { kj: ctx.state.courseId || "bi" }), "시험 출제로")));
  }
  const err = ctx.state.formError || {};
  const bankId = param(ctx, "bank");
  const type = param(ctx, "type");
  const questions = ctx.state.questions.filter((q) => {
    if (bankId && q.bankId !== bankId) return false;
    if (type && q.type !== type) return false;
    const bankTitle = (ctx.state.banks.find((b) => b.id === q.bankId) || {}).title || "";
    return matchesQuery(ctx, `${q.stem} ${q.type} ${bankTitle}`);
  });
  const records = questions.map((q) => {
    const bankTitle = (ctx.state.banks.find((b) => b.id === q.bankId) || {}).title || "—";
    return {
      sort: { stem: q.stem, type: q.type, points: q.points, bank: bankTitle },
      cells: [
        el("div", { class: "pf-stem" },
          el("b", { text: q.stem }),
          q.choices.length ? el("div", { class: "pf-meta", text: q.choices.join(" · ") }) : null,
        ),
        badge(q.type, "neutral"),
        el("b", { class: "pf-total", text: String(q.points) }),
        bankTitle,
        el("div", { class: "pf-row-actions" },
          btn("수정", { "data-act": "pf-edit-question", "data-id": q.id }, "secondary"),
          btn("삭제", { "data-act": "pf-delete-question", "data-id": q.id }, "danger"),
        ),
      ],
    };
  });
  const cards = ctx.state.banks.map((b) => {
    const live = ctx.state.questions.filter((q) => q.bankId === b.id).length;
    const on = bankId === b.id;
    return el("article", { class: `pf-bank${on ? " on" : ""}` },
      el("div", { class: "pf-bank-top" },
        go(kept(ctx, "pf_bank", { bank: b.id }), b.title, { class: "pf-bank-name", "aria-current": on ? "true" : null }),
        el("div", { class: "pf-inline" },
          btn("수정", { "data-act": "pf-edit-bank", "data-id": b.id }, "secondary"),
          btn("삭제", { "data-act": "pf-delete-bank", "data-id": b.id }, "ghost"),
        ),
      ),
      el("div", { class: "pf-meta", text: `편집 가능 ${live}문항 · ${b.updated}` }),
    );
  });
  return frame("문제은행", ctx, {
    kicker: "교수",
    sub: "은행과 문항은 이 브라우저에만 저장됩니다.",
    actions: [linkBtn(pf("pf_exam_create", { kj: "bi" }), "시험에 넣기")],
  },
    el("div", { class: "stat-row cols-3" },
      stat("은행", String(ctx.state.banks.length)),
      stat("문항", String(ctx.state.questions.length)),
      stat("표시", String(questions.length)),
    ),
    el("div", { class: "pf-toolbar" },
      chipRow(ctx, "pf_bank", [["all", "전체", "all"], ["객관식", "객관식", "test"], ["단답", "단답", "material"]], "type"),
      finder(ctx, "질문 검색"),
    ),
    el("div", { class: "pf-split" },
      el("div", { class: "stack" },
        cards.length ? el("div", { class: "pf-banks" }, ...cards) : emptyState("문제은행이 없습니다", "아래에서 은행을 추가하세요."),
        el("div", { class: "sheet" },
          el("h2", { text: "문제은행 추가" }),
          field("은행 이름", el("input", { "data-name": "bankTitle" }), err.bankTitle),
          btn("은행 추가", { "data-act": "add-bank" }, "primary"),
        ),
      ),
      el("div", { class: "sheet" },
        el("h2", { text: "문항 추가" }),
        ctx.state.banks.length ? el("div", { class: "pf-form" },
          field("은행", el("select", { "data-name": "bankId" }, ...ctx.state.banks.map((b) => el("option", { value: b.id, text: b.title, selected: bankId === b.id ? true : null })))),
          field("유형", el("select", { "data-name": "qtype" }, el("option", { value: "객관식", text: "객관식" }), el("option", { value: "단답", text: "단답" }))),
          field("배점", el("input", { type: "number", "data-name": "points", min: "1", max: "20", value: "5" }), err.points),
          span(field("질문", el("textarea", { "data-name": "stem" }), err.stem)),
          span(field("보기", el("textarea", { "data-name": "choices", placeholder: "객관식은 한 줄에 하나씩, 두 줄 이상" }), err.choices)),
          span(el("div", { class: "pf-actions" }, btn("문항 저장", { "data-act": "add-question" }, "primary"))),
        ) : el("p", { class: "pf-sub", text: "문항을 넣으려면 은행을 먼저 만드세요." }),
      ),
    ),
    el("h2", { class: "section-label", text: "문항" }),
    records.length ? dataTable("questions", "문제은행 문항", [
      { key: "stem", label: "질문", sort: true },
      { key: "type", label: "유형", sort: true },
      { key: "points", label: "배점", sort: true },
      { key: "bank", label: "은행", sort: true },
      { key: "actions", label: "관리" },
    ], records, ctx, { wide: true }) : emptyState("조건에 맞는 문항이 없습니다", "필터를 바꾸거나 문항을 추가하세요."),
    bankDialog(ctx),
  );
}

function bankDialog(ctx) {
  const dialog = ctx.state.draft.pfDialog;
  if (!dialog) return null;
  if (dialog.kind === "question") {
    const q = ctx.state.questions.find((item) => item.id === dialog.id);
    if (!q) return null;
    return pfDialog("문항 수정",
      field("은행", el("select", { "data-name": "editBankId" }, ...ctx.state.banks.map((b) => el("option", { value: b.id, text: b.title, selected: q.bankId === b.id ? true : null })))),
      field("유형", el("select", { "data-name": "editQtype" },
        el("option", { value: "객관식", text: "객관식", selected: q.type === "객관식" ? true : null }),
        el("option", { value: "단답", text: "단답", selected: q.type === "단답" ? true : null }),
      )),
      field("배점", el("input", { type: "number", "data-name": "editPoints", min: "1", max: "20", value: String(q.points) })),
      field("질문", el("textarea", { "data-name": "editStem" }, q.stem)),
      field("보기", el("textarea", { "data-name": "editChoices" }, q.choices.join("\n"))),
      el("div", { class: "pf-actions" },
        btn("취소", { "data-act": "pf-dismiss" }, "secondary"),
        btn("저장", { "data-act": "pf-save-question", "data-id": q.id }, "primary"),
      ),
    );
  }
  if (dialog.kind === "bank") {
    const b = ctx.state.banks.find((item) => item.id === dialog.id);
    if (!b) return null;
    return pfDialog("문제은행 수정",
      field("은행 이름", el("input", { "data-name": "editBankTitle", value: b.title })),
      el("div", { class: "pf-actions" },
        btn("취소", { "data-act": "pf-dismiss" }, "secondary"),
        btn("저장", { "data-act": "pf-save-bank", "data-id": b.id }, "primary"),
      ),
    );
  }
  if (dialog.kind === "delete-question" || dialog.kind === "delete-bank") {
    const question = dialog.kind === "delete-question" && ctx.state.questions.find((item) => item.id === dialog.id);
    const bankItem = dialog.kind === "delete-bank" && ctx.state.banks.find((item) => item.id === dialog.id);
    const name = question ? question.stem : bankItem ? bankItem.title : "이 항목";
    return pfDialog(dialog.kind === "delete-question" ? "문항 삭제" : "문제은행 삭제",
      el("p", { text: `「${name}」을 삭제합니다. 이 브라우저에서만 지워집니다.` }),
      el("div", { class: "pf-actions" },
        btn("취소", { "data-act": "pf-dismiss" }, "secondary"),
        btn("삭제", { "data-act": "pf-confirm-delete" }, "danger"),
      ),
    );
  }
  return null;
}

function attend(screen, ctx) {
  const course = courseOf(ctx);
  // 백엔드 모드: 서버 출석부·스마트출석·차시 관리(areas/professor/attendance-server.js).
  if (backendStatus() !== "off") {
    const titles = { general: ["출석 관리", "칸마다 사유와 함께 바꾸고, 다른 곳에서 먼저 바뀐 칸은 다시 불러옵니다."], smart: ["스마트 출석", "PIN이나 15초마다 바뀌는 QR로 출석을 받습니다. 판정과 기록은 서버가 합니다."], makeup: ["휴강·보강", "차시를 만들고 고치고, 휴강한 차시의 보강을 등록합니다."] };
    const [heading, sub] = titles[screen.mode || "general"] || titles.general;
    const views = { general: serverAttendGeneral, smart: serverAttendSmart, makeup: serverAttendSessions };
    return frame(heading, ctx, { course: true, kicker: courseOf(ctx).ko, sub }, tabs([
      { label: "일반", href: pf("pf_attend", { kj: courseOf(ctx).id }), on: (screen.mode || "general") === "general" },
      { label: "스마트", href: pf("pf_attend_smart", { kj: courseOf(ctx).id }), on: screen.mode === "smart" },
      { label: "휴강·보강", href: pf("pf_attend_makeup", { kj: courseOf(ctx).id }), on: screen.mode === "makeup" },
    ]), (views[screen.mode || "general"] || serverAttendGeneral)(ctx, courseOf(ctx)));
  }
  const mode = screen.mode || "general";
  const modeTabs = tabs([
    { label: "일반", href: withQ(ctx, "pf_attend"), on: mode === "general" },
    { label: "스마트", href: withQ(ctx, "pf_attend_smart"), on: mode === "smart" },
    { label: "휴강·보강", href: withQ(ctx, "pf_attend_makeup"), on: mode === "makeup" },
  ]);
  if (mode === "smart") {
    const view = param(ctx, "view");
    const rows = learners().filter((p) => matchesQuery(ctx, p.name)).map((p) => {
      const pct = SMART[p.id] ?? 80;
      const pass = pct >= 90;
      return { person: p, pct, pass };
    }).filter((row) => (view === "in" ? row.pass : view === "out" ? !row.pass : true));
    const records = rows.map((row) => ({
      sort: { name: row.person.name, pct: row.pct, status: row.pass ? "출석" : "미달" },
      cells: [row.person.name, meter(row.pct), statusBadge(row.pass ? "출석" : "미달", row.pass)],
    }));
    return frame("스마트 출석", ctx, {
      course: true,
      kicker: course.ko,
      sub: "영상 진도 90% 이상이면 출석으로 인정합니다.",
    }, modeTabs,
      el("div", { class: "pf-toolbar" },
        chipRow(ctx, "pf_attend_smart", [["all", "전체"], ["in", "출석"], ["out", "미달"]], "view"),
        finder(ctx, "이름 검색"),
      ),
      records.length ? dataTable("smart", "스마트 출석", [
        { key: "name", label: "이름", sort: true, lock: true },
        { key: "pct", label: "영상 진도", sort: true },
        { key: "status", label: "인정", sort: true },
      ], records, ctx) : emptyState("조건에 맞는 수강생이 없습니다", "필터를 바꿔 보세요."),
    );
  }
  if (mode === "makeup") {
    const err = ctx.state.formError || {};
    const makeups = ctx.state.draft.makeups || [];
    const records = makeups.filter((m) => matchesQuery(ctx, `${m.week} ${m.why}`)).map((m) => ({
      sort: { week: Number(m.week), when: m.when, why: m.why },
      cells: [`${m.week}주차`, m.when, m.why],
    }));
    return frame("휴강·보강", ctx, { course: true, kicker: course.ko, sub: "보강 일정은 이 브라우저에만 남습니다." },
      modeTabs,
      formSheet("보강 등록",
        field("주차", el("input", { type: "number", "data-name": "week", min: "1", max: "14" }), err.week),
        field("보강 일시", el("input", { type: "datetime-local", "data-name": "when" }), err.when),
        span(field("사유", el("input", { "data-name": "why" }), err.why)),
        span(el("div", { class: "pf-actions" }, btn("보강 등록", { "data-act": "save-makeup" }, "primary"))),
      ),
      records.length ? dataTable("makeups", "보강", [
        { key: "week", label: "주차", sort: true },
        { key: "when", label: "일시", sort: true },
        { key: "why", label: "사유", sort: true },
      ], records, ctx) : emptyState("등록된 보강이 없습니다", "위 양식으로 샘플 보강을 남길 수 있습니다."),
    );
  }
  const rows = learners().filter((p) => matchesQuery(ctx, p.name));
  const columns = [
    { key: "name", label: "이름", sort: true, lock: true },
    ...Array.from({ length: 8 }, (_, i) => ({ key: `w${i + 1}`, label: `${i + 1}주차` })),
  ];
  const records = rows.map((p) => ({
    sort: { name: p.name },
    cells: [
      p.name,
      ...Array.from({ length: 8 }, (_, i) => {
        const value = attendStatus(ctx.state, p.id, i + 1);
        return el("select", {
          "data-act": "attend",
          "data-who": p.id,
          "data-week": String(i + 1),
          "aria-label": `${p.name} ${i + 1}주차`,
          class: `pf-attend-select pf-attend-${value}`,
        }, ...["present", "late", "absent", "excused", "empty"].map((st) => el("option", {
          value: st,
          text: statusLabel(st),
          selected: value === st ? true : null,
        })));
      }),
    ],
  }));
  return frame("출석 관리", ctx, {
    course: true,
    kicker: course.ko,
    sub: "변경은 바로 이 브라우저의 출석부에 반영됩니다.",
  }, modeTabs,
    el("div", { class: "pf-toolbar" },
      el("div", { class: "pf-inline" }, badge("출석", "success"), badge("지각", "neutral"), badge("결석", "danger"), badge("유고", "neutral")),
      finder(ctx, "이름 검색"),
    ),
    records.length ? dataTable("attendance", "출석부", columns, records, ctx, { layout: "scroll", wide: true, attend: true })
      : emptyState("조건에 맞는 수강생이 없습니다", "검색어를 지워 보세요."),
  );
}

function gradebook(ctx) {
  // 백엔드 모드: 서버 성적부(areas/professor/grades-server.js).
  if (backendStatus() !== "off") return frame("성적 관리", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "항목·공개·점수는 ASTRA 서버에 저장됩니다. 학생에게는 공개한 점수만 보입니다." }, serverGradebook(ctx, courseOf(ctx)));
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const weights = ctx.state.weights;
  const sum = gradeItems.reduce((total, item) => total + Number(weights[item.id] || 0), 0);
  const columns = [
    { key: "name", label: "이름", sort: true, lock: true },
    ...gradeItems.map((g) => ({ key: g.id, label: `${g.name} ${weights[g.id] || 0}%`, sort: true })),
    { key: "total", label: "합계", sort: true },
  ];
  const records = peopleRows(ctx, (p) => {
    const score = ctx.state.scores[p.id] || {};
    let total = 0;
    let pending = false;
    const cells = gradeItems.map((g) => {
      const raw = score[g.id];
      const num = raw === "" || raw == null ? null : Number(raw);
      if (num == null || Number.isNaN(num)) pending = true;
      else total += num * ((weights[g.id] || 0) / (g.id === "att" ? 10 : 30));
      return el("input", {
        type: "number",
        min: "0",
        max: "100",
        "data-name": `gb-${p.id}-${g.id}`,
        value: raw == null ? "" : String(raw),
        "aria-label": `${p.name} ${g.name}`,
      });
    });
    return {
      sort: {
        name: p.name,
        ...Object.fromEntries(gradeItems.map((g) => {
          const raw = score[g.id];
          return [g.id, raw === "" || raw == null ? null : Number(raw)];
        })),
        total: pending ? null : Number(total.toFixed(1)),
      },
      cells: [p.name, ...cells, pending ? badge("미완", "neutral") : el("b", { class: "pf-total", text: total.toFixed(1) })],
    };
  });
  return frame("성적 관리", ctx, {
    course: true,
    kicker: course.ko,
    sub: "반영 비율의 합이 100이어야 저장됩니다. 점수는 0부터 100 사이의 정수입니다.",
  },
    el("div", { class: "sheet" },
      el("div", { class: "pf-week-head" },
        el("h2", { text: "반영 비율" }),
        badge(sum === 100 ? "합계 100" : `합계 ${sum}`, sum === 100 ? "success" : "danger"),
      ),
      el("div", { class: "pf-weights" }, ...gradeItems.map((g) => field(g.name, el("input", {
        type: "number",
        "data-name": `w-${g.id}`,
        value: String(weights[g.id] || 0),
        min: "0",
        max: "100",
        "aria-label": `${g.name} 반영 비율`,
      })))),
      el("div", { "data-name": "weights" }),
      err.weights ? el("p", { class: "err", text: err.weights }) : null,
    ),
    el("div", { class: "pf-toolbar" }, finder(ctx, "이름 검색")),
    err.scores ? el("p", { class: "err", text: err.scores }) : null,
    records.length ? dataTable("gradebook", "성적부", columns, records, ctx, { layout: "scroll", wide: true })
      : emptyState("조건에 맞는 수강생이 없습니다", "검색어를 지워 보세요."),
    el("div", { class: "pf-actions" },
      btn("성적 저장", { "data-act": "save-gradebook" }, "primary"),
      el("button", {
        type: "button",
        class: `btn ${ctx.state.gradePublic ? "btn-primary" : "btn-secondary"}`,
        "data-act": "toggle-public",
        text: ctx.state.gradePublic ? "학생 공개 중" : "학생에게 비공개",
      }),
    ),
  );
}

function appeal(ctx) {
  // Backend mode: the server has no grade appeal; nothing sample is shown in a server course.
  if (serverRoom(courseOf(ctx))) {
    return frame("이의신청", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "ASTRA 서버에는 성적 이의신청이 아직 없습니다." },
      unavailable("이의신청은 아직 ASTRA 서버에 없습니다", "학생이 성적에 이의를 내고 교수가 답하는 기능이 서버에 생기면 여기에서 받습니다. 점수는 성적 관리와 과제·시험 채점에서 고칩니다.",
        linkBtn(pf("pf_gradebook", { kj: courseOf(ctx).id }), "성적 관리로")));
  }
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const view = param(ctx, "view");
  const rows = ctx.state.appeals.filter((a) => {
    if (!matchesQuery(ctx, `${a.who} ${a.item} ${a.body}`)) return false;
    if (view === "wait") return a.status === "대기";
    if (view === "done") return a.status !== "대기";
    return true;
  });
  return frame("이의신청", ctx, { course: true, kicker: course.ko, sub: "답변은 이 브라우저에만 저장됩니다." },
    el("div", { class: "pf-toolbar" },
      chipRow(ctx, "pf_appeal", [["all", "전체"], ["wait", "대기"], ["done", "답변"]], "view"),
      finder(ctx, "이름, 문항"),
    ),
    rows.length ? el("div", { class: "stack" }, ...rows.map((a) => el("article", { class: "sheet" },
      el("div", { class: "pf-inline" },
        el("b", { text: a.who }),
        badge(a.status, a.status === "답변" ? "success" : "danger"),
        el("span", { class: "pf-meta", text: a.item }),
      ),
      el("p", { text: a.body }),
      a.reply ? el("p", { text: `답변: ${a.reply}` }) : el("div", {},
        field("답변", el("textarea", { "data-name": `reply-${a.id}` }), err[a.id]),
        btn("답변 저장", { "data-act": "save-appeal", "data-id": a.id }, "primary"),
      ),
    ))) : emptyState("해당하는 이의신청이 없습니다", "필터를 바꿔 보세요."),
  );
}

function teams(ctx) {
  // 백엔드 모드: 서버 그룹과 구성원(areas/professor/groups-server.js).
  if (backendStatus() !== "off") return frame("팀 프로젝트", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "그룹을 만들고 수강생을 넣거나 옮깁니다. 학생은 자기 그룹과 구성원 이름만 봅니다." }, serverTeams(ctx, courseOf(ctx)));
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const rows = ctx.state.teams.filter((t) => matchesQuery(ctx, `${t.name} ${t.topic}`));
  const records = rows.map((t) => ({
    sort: { name: t.name, topic: t.topic, size: t.size },
    cells: [t.name, t.topic, `${t.size}명`],
  }));
  return frame("팀 프로젝트", ctx, { course: true, kicker: course.ko, sub: "팀을 만들면 학생 그룹활동에 반영되는 샘플입니다." },
    records.length ? dataTable("teams", "팀", [
      { key: "name", label: "팀", sort: true, lock: true },
      { key: "topic", label: "주제", sort: true },
      { key: "size", label: "인원", sort: true },
    ], records, ctx) : emptyState("팀이 없습니다", "아래에서 샘플 팀을 만듭니다."),
    formSheet("팀 만들기",
      field("팀 이름", el("input", { "data-name": "name" }), err.name),
      field("주제", el("input", { "data-name": "topic" }), err.topic),
      field("인원", el("input", { type: "number", "data-name": "size", min: "2", max: "6", value: "4" }), err.size),
      span(el("div", { class: "pf-actions" }, btn("팀 만들기", { "data-act": "save-team" }, "primary"))),
    ),
  );
}

function monitor(ctx) {
  const course = courseOf(ctx);
  // Backend mode: pending enrollment and audit requests from the server (approve/reject with `version`).
  // The roster comes from GET /api/courses/{id}/students (areas/professor/enrollments-server.js); the sample learner table is not shown here.
  if (backendStatus() !== "off") {
    return frame("수강생", ctx, { course: true, kicker: course.ko, sub: "ASTRA 서버의 수강생 명단과 수강·청강 신청입니다. 승인하면 학생의 수강이 바로 확정됩니다." },
      serverCourseRoster(ctx, course),
      serverEnrollmentRequests(ctx, course));
  }
  const records = peopleRows(ctx, (p, i) => {
    const progress = 50 + learners().indexOf(p) * 6;
    return {
      sort: { name: p.name, no: p.no, at: `2026-11-${String(10 - (i % 4)).padStart(2, "0")}`, progress },
      cells: [
        p.name,
        p.no,
        `2026-11-${String(10 - (i % 4)).padStart(2, "0")} 0${i}:12`,
        meter(progress),
      ],
    };
  });
  return frame("수강생", ctx, { course: true, kicker: course.ko, sub: "최근 접속과 진도는 샘플입니다." },
    el("div", { class: "pf-toolbar" }, finder(ctx, "이름, 학번")),
    records.length ? dataTable("monitor", "수강생", [
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "no", label: "학번", sort: true },
      { key: "at", label: "최근 접속", sort: true },
      { key: "progress", label: "진도", sort: true },
    ], records, ctx) : emptyState("조건에 맞는 수강생이 없습니다", "검색어를 지워 보세요."),
  );
}

function ta(ctx) {
  // Backend mode: course settings = the server syllabus editor; TA assignment has no server API.
  if (serverRoom(courseOf(ctx))) {
    return frame("과목 설정", ctx, { course: true, kicker: courseOf(ctx).ko, sub: "강의계획서와 내 강사 정보를 ASTRA 서버에 저장합니다. 조교·팀티칭 지정은 서버에 아직 없습니다." },
      serverSyllabusEditor(courseOf(ctx)),
      el("section", { class: "sheet", "aria-label": "조교 · 팀티칭" },
        el("h2", { text: "조교 · 팀티칭" }),
        emptyState("조교 지정은 아직 ASTRA 서버에 없습니다", "이 과목의 조교와 팀티칭 담당자를 보고 바꾸는 기능이 서버에 생기면 여기에서 관리합니다. 이 브라우저에만 남는 샘플 조교는 서버 과목에서 보이지 않습니다."),
      ),
    );
  }
  const course = courseOf(ctx);
  const err = ctx.state.formError || {};
  const rows = ctx.state.tas.filter((t) => !t.courseId || t.courseId === course.id).filter((t) => matchesQuery(ctx, `${t.name} ${t.role}`));
  const records = rows.map((t) => ({
    sort: { name: t.name, role: t.role },
    cells: [t.name, badge(t.role, t.role === "조교" ? "neutral" : "success")],
  }));
  return frame("조교 · 팀티칭", ctx, { course: true, kicker: course.ko, sub: "이 과목의 조교와 팀티칭을 샘플로 관리합니다." },
    records.length ? dataTable("tas", "조교", [
      { key: "name", label: "이름", sort: true, lock: true },
      { key: "role", label: "역할", sort: true },
    ], records, ctx) : emptyState("등록된 조교가 없습니다", "아래에서 추가할 수 있습니다."),
    formSheet("추가",
      field("이름", el("input", { "data-name": "name" }), err.name),
      field("역할", el("select", { "data-name": "role" }, el("option", { value: "조교", text: "조교" }), el("option", { value: "팀티칭", text: "팀티칭" }))),
      span(el("div", { class: "pf-actions" }, btn("추가", { "data-act": "save-ta" }, "primary"))),
    ),
  );
}

function videoBody(activity) {
  const media = mediaForActivity(activity);
  const closed = isClosedActivity(activity);
  const video = el("video", {
    class: "pf-lecture",
    poster: mediaFile(media.id, "jpg"),
    playsinline: true,
    controls: closed ? null : true,
    "aria-label": activity.title,
  });
  if (!closed) {
    video.append(
      el("source", { src: mediaFile(media.id, "webm"), type: "video/webm" }),
      el("source", { src: mediaFile(media.id, "mp4"), type: "video/mp4" }),
    );
    if (activity.fileId) bindUploadedVideo(video, activity);
  }
  return el("div", { class: "stack" },
    video,
    el("p", { class: "pf-sub", text: closed ? "마감된 영상은 재생할 수 없습니다." : (activity.fileId ? "등록한 영상입니다. 재생할 수 없으면 샘플 영상으로 바꿉니다." : `샘플 영상 · ${media.course} ${media.week}`) }),
    activity.body ? el("p", { text: activity.body }) : null,
    el("p", { class: "pf-meta", text: `출석 ${activity.attendStart || activity.start || "—"} – ${activity.attendEnd || activity.due || "—"}` }),
  );
}

function zoomBody(activity) {
  const link = activity.meetingUrl
    ? el("a", { class: "pf-meeting", href: activity.meetingUrl, rel: "noopener noreferrer", text: activity.meetingUrl })
    : el("span", { text: activity.meeting || "회의 링크 없음" });
  return el("div", { class: "stack" },
    el("p", {}, link),
    el("p", { class: "pf-meta", text: `${activity.start || activity.when || "—"} – ${activity.end || "—"}` }),
    activity.body ? el("p", { text: activity.body }) : null,
  );
}

function materialBody(activity, course) {
  const slot = el("div", { class: "pf-pdf", "data-pdf": activity.pdfId || "" });
  const remoteId = isRemotePdfId(activity.pdfId);
  if (remoteId && backendStatus() !== "ready") {
    slot.append(el("p", { class: "pf-sub", text: "ASTRA 서버에 등록한 PDF입니다. 서버에 로그인하면 자료와 쪽별 요약을 볼 수 있습니다." }), backendPanel());
  } else if (activity.pdfId) {
    slot.append(el("p", { class: "pf-sub", text: "PDF를 불러오는 중입니다." }));
    listCoursePdfs(course.id).then((records) => {
      if (!slot.isConnected) return;
      const record = records.find((item) => item.id === activity.pdfId);
      if (!record) {
        slot.replaceChildren(el("p", { text: remoteId ? "ASTRA 서버에서 이 PDF를 찾지 못했습니다. 이 과목의 담당 교수 계정인지 확인하세요." : "저장된 PDF를 찾지 못했습니다." }));
        return;
      }
      if (record.remote) {
        slot.replaceChildren(
          el("p", { class: "pf-meta", text: `${record.fileName} · ${sizeLabel(record.size)} · ASTRA 서버 자료` }),
          summaryJobPanel(record),
        );
        return;
      }
      slot.replaceChildren(
        el("p", { class: "pf-meta", text: `${record.fileName} · ${record.pageCount}쪽` }),
        ...record.pages.slice(0, 12).map((page) => el("article", { class: "pf-pdf-page" },
          el("h3", { text: `${page.number}쪽` }),
          el("p", { text: page.text || "이 쪽에서 추출한 텍스트가 없습니다." }),
        )),
      );
    }).catch(() => {
      if (slot.isConnected) slot.replaceChildren(el("p", { text: "PDF를 불러오지 못했습니다." }));
    });
  }
  const download = activity.fileId
    ? btn("다운로드", { "data-act": "activity-download", "data-file": activity.fileId, "data-filename": activity.fileName || "file" }, "secondary")
    : null;
  return el("div", { class: "stack" },
    activity.body ? el("p", { text: activity.body }) : null,
    activity.fileName ? el("p", { class: "pf-meta", text: activity.fileName }) : null,
    download,
    slot,
  );
}

function postBody(activity) {
  const prose = el("div", { class: "pf-prose", "data-post": activity.id });
  prose.append(richNodes(activity.body || ""));
  return prose;
}

const MINE = ["bi", "db", "sec"];

function activityFrom(ctx) {
  const id = param(ctx, "n");
  return (id && activities.find((item) => item.id === id)) || null;
}

function backRoom(course) {
  return linkBtn(pf("pf_room_week", { kj: course.id }), "강의실");
}

function courseList() {
  // Backend `ready`: the courses this account teaches, from GET /api/me/courses.
  if (backendStatus() === "ready") {
    return el("section", {},
      el("header", { class: "pf-head" },
        el("div", { class: "pf-title" },
          breadcrumbs([{ label: "교수 홈", href: pf("pf_main") }, { label: "강의과목" }]),
          el("h1", { text: "강의과목" }),
          el("p", { class: "pf-sub", text: "ASTRA 서버에서 이 계정이 담당하는 과목입니다." }),
        ),
      ),
      serverTaughtCourses(courseCard),
    );
  }
  const mine = MINE.map(courseById);
  return el("section", {},
    el("header", { class: "pf-head" },
      el("div", { class: "pf-title" },
        breadcrumbs([{ label: "교수 홈", href: pf("pf_main") }, { label: "강의과목" }]),
        el("h1", { text: "강의과목" }),
        el("p", { class: "pf-sub", text: "담당 과목만 모았습니다. 강의실과 학습실 모두 교수 화면입니다." }),
      ),
    ),
    el("div", { class: "cards" }, ...mine.map((c) => {
      const href = pf("pf_room_week", { kj: c.id });
      return el("article", { class: "course-card" },
        el("a", { class: "course-card-main", href, "data-go": href },
          el("div", { class: `cover ${c.gradient}` }, coverImage(c), el("b", { text: c.cardTitle || c.ko }), el("span", { class: "code", text: c.code })),
          el("div", { class: "meta" }, el("div", { text: `${c.learners}명` }), el("div", { text: c.time })),
        ),
        go(routeFor("professor", "study", { kj: c.id }), "학습실 입장", { class: "btn btn-secondary course-study-entry", "aria-label": `${c.cardTitle || c.ko} 학습실 입장` }),
      );
    })),
  );
}

function studyRoom(ctx) {
  // Backend mode: taught courses, the course's AI consent and server materials (areas/professor/materials-server.js).
  if (backendStatus() !== "off") {
    return serverStudyRoom(ctx, summaryJobPanel, {
      frame: (course, body) => frame("학습실", ctx, { course: Boolean(course), kicker: course ? course.ko : "교수", sub: "담당 과목의 서버 자료와 쪽별 요약, 외부 AI 처리 동의를 봅니다.", actions: course ? [backRoom(course)] : [] }, body),
    });
  }
  const kj = param(ctx, "kj");
  const course = kj ? courseById(kj) : null;
  if (!course || !MINE.includes(course.id)) {
    return frame("학습실", ctx, { kicker: "교수", sub: "담당 과목의 영상과 자료 미리보기입니다. 학생 학습실로 넘어가지 않습니다." },
      el("div", { class: "cards" }, ...MINE.map(courseById).map((c) => {
        const href = routeFor("professor", "study", { kj: c.id });
        return el("a", { class: "course-card", href, "data-go": href },
          el("div", { class: `cover ${c.gradient}` }, coverImage(c), el("b", { text: c.cardTitle || c.ko })),
          el("div", { class: "meta" }, el("div", { text: "자료·영상" })),
        );
      })),
    );
  }
  const mine = activities.filter((a) => a.courseId === course.id && ["video", "material", "report", "test"].includes(a.type));
  return frame("학습실", ctx, {
    course: true,
    kicker: course.ko,
    sub: "항목을 열면 출석 통계, 제출 현황, 자료 미리보기로 이동합니다.",
    actions: [backRoom(course)],
  },
  mine.length ? el("div", { class: "pf-activities" }, ...mine.map((a) => activityCard(a, ctx))) : emptyState("등록된 자료가 없습니다", "자료 등록에서 PDF를 올릴 수 있습니다."),
  );
}

function watchRate(person, activity) {
  if (!activity) return 0;
  if (activity.type !== "video" && activity.type !== "zoom") return activity.status === "done" || activity.status === "graded" ? 100 : 35;
  const known = { minjun: 92, seoyeon: 88, jieun: 100, doyun: 74, haeun: 96, woojin: 63, harin: 91 };
  if (activity.status === "expired") return Math.max(20, (known[person.id] || 70) - 30);
  if (activity.status === "done") return 100;
  return known[person.id] != null ? known[person.id] : 40 + (person.name.length % 4) * 12;
}

function lecturePreview(ctx) {
  // Backend: the server lecture, its items and per-student progress (areas/professor/lectures-server.js).
  const serverCourse = courseById(param(ctx, "kj") || "bi");
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || serverRoom(serverCourse))) {
    return frame("강의 미리보기", ctx, { course: true, kicker: serverCourse.ko, sub: "콘텐츠의 영상 상태와 수강생별 학습 인정 진도입니다. 진도는 서버가 적립한 시간으로만 셉니다.", actions: [backRoom(serverCourse), linkBtn(pf("pf_attend", { kj: serverCourse.id }), "출석부", "primary")] },
      serverLectureDetail(ctx, serverCourse));
  }
  const activity = activityFrom(ctx) || activities.find((item) => item.courseId === (param(ctx, "kj") || "bi") && item.type === "video");
  const course = courseById((activity && activity.courseId) || param(ctx, "kj") || "bi");
  if (!activity) return frame("강의 미리보기", ctx, { course: true, kicker: course.ko }, emptyState("강의를 찾지 못했습니다", "주차 목록에서 다시 선택해 주세요."));
  const rows = learners().map((person) => ({ person, rate: watchRate(person, activity) }));
  const avg = Math.round(rows.reduce((sum, row) => sum + row.rate, 0) / rows.length);
  const present = rows.filter((row) => row.rate >= 90).length;
  const stageTitle = activity.type === "survey" ? "응답 현황" : activity.type === "zoom" ? "실시간 강의" : "강의 미리보기";
  return frame(activity.title, ctx, {
    course: true,
    kicker: `${course.ko} · ${stageTitle}`,
    sub: activity.type === "video"
      ? "학생 출석 인정 흐름 대신 차시별 시청 통계를 봅니다. 재생 위치는 이 브라우저에만 있습니다."
      : "학생 화면의 참여 버튼 없이 현황만 확인합니다.",
    actions: [backRoom(course), linkBtn(pf("pf_attend", { kj: course.id }), "출석부", "primary")],
  },
  activity.type === "video" ? videoBody(activity) : activity.type === "zoom" ? zoomBody(activity) : activity.type === "post" ? postBody(activity) : null,
  el("div", { class: "pf-split" },
    el("div", { class: "pf-stage", "aria-label": "미리보기" },
      el("p", { class: "pf-kicker", text: typeLabel(activity.type) }),
      el("strong", { text: activity.title }),
      el("p", { text: activity.type === "zoom" ? (activity.when || activity.start || "일정 미정") : `${activity.minutes || activity.week || 0}${activity.minutes ? "분" : "주차"}` }),
      el("p", { class: "pf-sub", text: activity.type === "post" ? "글 본문은 위에 있습니다." : (activity.body || "샘플 차시입니다. 원본 영상 파일은 이 미리보기에 넣지 않습니다.") }),
    ),
    el("div", { class: "stack" },
      el("div", { class: "stat-row cols-3" },
        stat("수강생", String(rows.length)),
        stat(activity.type === "survey" ? "응답" : "평균", activity.type === "survey" ? `${present}명` : `${avg}%`),
        stat(activity.type === "video" ? "출석 인정" : "완료", `${present}명`),
      ),
      el("div", { class: "sheet" },
        el("h2", { text: "수강생별 현황" }),
        ...rows.map((row) => el("div", { class: "pf-watch" },
          el("span", { text: row.person.name }),
          meter(row.rate),
        )),
      ),
    ),
  ));
}

/** 응시 현황 and 시험 채점 in backend mode: server attempts and short-answer grading. */
function serverExamFrame(ctx, course, title, body) {
  return frame(title, ctx, {
    course: true,
    kicker: course.ko,
    sub: "ASTRA 서버의 응시입니다. 응시 마감과 접수 시각은 서버가 정하고, 선택 문항은 서버가 채점합니다.",
    actions: [
      linkBtn(pf("pf_exam_create", { kj: course.id }), "시험 출제"),
      linkBtn(pf(title === "시험 채점" ? "pf_exam_status" : "pf_exam_grade", { kj: course.id, n: param(ctx, "n") }), title === "시험 채점" ? "응시 현황" : "채점"),
    ],
  }, body);
}

/** 제출 현황 and 과제 채점 in backend mode: server submissions, score release and grading. */
function serverStatusFrame(ctx, course, title, body) {
  return frame(title, ctx, {
    course: true,
    kicker: course.ko,
    sub: "ASTRA 서버의 제출물입니다. 제출 시각과 지각 여부는 서버가 접수할 때 정합니다.",
    actions: [
      linkBtn(pf("pf_report_create", { kj: course.id }), "과제 출제"),
      linkBtn(pf(title === "과제 채점" ? "pf_report_status" : "pf_report_grade", { kj: course.id, n: param(ctx, "n") }), title === "과제 채점" ? "제출 현황" : "채점"),
    ],
  }, body);
}

function submissions(ctx) {
  // A backend assignment (`n=srv-<id>`, e.g. from the student detail by role switch).
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || serverRoom(courseById(param(ctx, "kj") || "bi")))) {
    const course = courseById(param(ctx, "kj") || "bi");
    return serverStatusFrame(ctx, course, "제출 현황", serverSubmissionStatus(ctx, course));
  }
  const activity = activityFrom(ctx) || activities.find((item) => item.courseId === "bi" && item.type === "report");
  const course = courseById((activity && activity.courseId) || "bi");
  const all = learners().map((person) => ({ person, sub: submissionOf(ctx.state, person, activity?.id) }));
  const titled = activity ? activity.title : "과제";
  const records = all.map(({ person, sub }) => ({
    sort: { no: person.no, name: person.name, status: sub ? "제출" : "미제출", at: sub ? sub.at : "" },
    cells: [person.no, person.name, statusBadge(sub ? "제출" : "미제출", Boolean(sub)), sub ? sub.at : "—", sub ? (sub.fileName || "첨부") : "—"],
  }));
  return frame(titled, ctx, {
    course: true,
    kicker: "과제 제출",
    sub: "학생 제출 화면이 아닙니다. 제출물과 미제출을 함께 봅니다.",
    actions: [
      backRoom(course),
      linkBtn(pf("pf_report_grade", { kj: course.id }), "채점", "primary"),
    ],
  },
  el("div", { class: "stat-row cols-3" },
    stat("대상", String(all.length)),
    stat("제출", String(all.filter((row) => row.sub).length)),
    stat("미제출", String(all.filter((row) => !row.sub).length)),
  ),
  dataTable("activity-submissions", "과제 제출", [
    { key: "no", label: "학번", sort: true },
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "at", label: "시각", sort: true },
    { key: "file", label: "파일" },
  ], records, ctx),
  );
}

function quizDetail(ctx) {
  // Backend: the server quiz with its questions (areas/professor/quizzes-server.js).
  const serverCourse = courseById(param(ctx, "kj") || "bi");
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || serverRoom(serverCourse))) {
    return frame("시험 상세", ctx, { course: true, kicker: serverCourse.ko, sub: "ASTRA 서버의 시험 설정과 문항입니다. 응시와 채점은 응시 현황에서 봅니다.", actions: [backRoom(serverCourse), linkBtn(pf("pf_exam_status", { kj: serverCourse.id, n: param(ctx, "n") }), "응시 현황", "primary")] },
      serverQuizDetail(ctx, serverCourse));
  }
  const activity = activityFrom(ctx) || activities.find((item) => item.type === "test");
  const course = courseById((activity && activity.courseId) || param(ctx, "kj") || "bi");
  const questions = ctx.state.questions.slice(0, 3);
  const attempts = [
    { name: "김서연", status: "응시", score: "24" },
    { name: "박민준", status: "응시", score: "21" },
    { name: "이지은", status: "미응시", score: "—" },
    { name: "이도윤", status: "미응시", score: "—" },
  ];
  return frame(activity ? activity.title : "시험", ctx, {
    course: true,
    kicker: "시험 상세",
    sub: "학생 응시 화면 대신 문항과 응시 현황을 봅니다.",
    actions: [backRoom(course), linkBtn(pf("pf_exam_status", { kj: course.id }), "응시 현황", "primary")],
  },
  el("div", { class: "stat-row cols-3" },
    stat("문항", String(questions.length)),
    stat("응시", String(attempts.filter((row) => row.status === "응시").length)),
    stat("배점", activity && activity.points != null ? `${activity.points}점` : "—"),
  ),
  activity && activity.body ? el("p", { class: "pf-sub", text: activity.body }) : null,
  ...questions.map((q) => el("article", { class: "sheet pf-q" },
    el("div", { class: "pf-inline" }, badge(q.type, "neutral"), badge(`${q.points}점`, "neutral")),
    el("h2", { text: q.stem }),
    q.choices.length ? el("ol", {}, ...q.choices.map((choice) => el("li", { text: choice }))) : null,
  )),
  dataTable("quiz-attempts", "응시", [
    { key: "name", label: "이름", sort: true, lock: true },
    { key: "status", label: "상태", sort: true },
    { key: "score", label: "점수", sort: true },
  ], attempts.map((row) => ({
    sort: { name: row.name, status: row.status, score: row.score === "—" ? null : Number(row.score) },
    cells: [row.name, statusBadge(row.status, row.status === "응시"), row.score],
  })), ctx),
  );
}

function pdfPreview(ctx) {
  // Backend: one server material, its files, preview, page summaries and AI consent (areas/professor/materials-server.js).
  const serverCourse = courseById(param(ctx, "kj") || "bi");
  if (backendStatus() !== "off" && (/^srv-/.test(param(ctx, "n")) || param(ctx, "material") || serverRoom(serverCourse))) {
    return frame("자료 미리보기", ctx, { course: true, kicker: serverCourse.ko, sub: "ASTRA 서버에 등록한 자료입니다. PDF는 쪽별 요약을 만들고 게시할 수 있습니다.", actions: [backRoom(serverCourse), linkBtn(pf("pf_material_write", { kj: serverCourse.id }), "자료 등록")] },
      serverMaterialDetail(ctx, serverCourse, summaryJobPanel));
  }
  const activity = activityFrom(ctx);
  const course = courseById((activity && activity.courseId) || param(ctx, "kj") || "bi");
  const fileId = param(ctx, "n");
  const file = files.find((item) => item.id === fileId) || files.find((item) => item.courseId === course.id) || { name: activity ? `${activity.title}.pdf` : "week5-cases.pdf", size: "1.2 MB", week: activity ? activity.week : 5 };
  const siblings = files.filter((item) => item.courseId === course.id);
  return frame(file.name, ctx, {
    course: true,
    kicker: "자료 미리보기",
    sub: "학생용 문서 뷰어가 아닙니다. 올린 자료의 이름과 주차만 확인합니다.",
    actions: [backRoom(course), linkBtn(pf("pf_material_write", { kj: course.id }), "자료 등록")],
  },
  activity && (activity.pdfId || activity.fileId || activity.body) ? materialBody(activity, course) : null,
  el("div", { class: "pf-split" },
    el("div", { class: "pf-stage pf-sheet", "aria-label": "PDF 미리보기" },
      el("p", { class: "pf-kicker", text: "PDF" }),
      el("strong", { text: file.name }),
      el("p", { text: `${file.size || ""}${file.week ? ` · ${file.week}주차` : ""}` }),
      el("p", { class: "pf-sub", text: "페이지 1. 원문 문장은 이 샘플에 넣지 않았습니다." }),
    ),
    el("div", { class: "sheet" },
      el("h2", { text: "같은 과목 자료" }),
      ...(siblings.length ? siblings : [file]).map((item) => {
        const href = routeFor("professor", "pdf", { n: item.id || fileId || "f1", kj: course.id });
        return el("a", { class: "list-row", href, "data-go": href }, el("b", { text: item.name }), el("span", { class: "tiny", text: item.size || "" }));
      }),
    ),
  ),
  );
}

function noticeBoard(ctx) {
  // Backend: there is no site-wide notice list for professors; the taught courses' notices (notices-server.js).
  if (backendStatus() !== "off") {
    return frame("알림마당", ctx, { kicker: "교수", sub: "담당 과목의 강의실 공지를 보고 고치거나 지웁니다." }, serverCourseNotices(ctx));
  }
  const id = param(ctx, "id");
  const rows = notices.filter((notice) => notice.audience !== "student");
  const open = rows.find((notice) => notice.id === id);
  return frame("알림마당", ctx, { kicker: "교수", sub: "교수에게 보이는 공지만 모았습니다." },
    open ? el("article", { class: "sheet" },
      el("h2", { text: open.title }),
      el("p", { class: "pf-sub", text: `${open.date} · ${open.author}` }),
      el("p", { text: open.body }),
      linkBtn(pf("pf_notice"), "목록"),
    ) : null,
    el("div", { class: "stack" }, ...rows.map((notice) => {
      const href = pf("pf_notice", { id: notice.id });
      return el("a", { class: "list-row", href, "data-go": href },
        el("span", {}, el("b", { text: notice.title }), el("div", { class: "tiny", text: notice.body })),
        el("span", { class: "tiny", text: notice.date }),
      );
    })),
  );
}

function qnaBoard(ctx) {
  const course = courseOf(ctx);
  // Backend mode: the server Q&A of this course; the detail and answer form open over it (`?pop=qna`).
  if (backendStatus() !== "off") {
    return frame("질의응답", ctx, { course: true, kicker: course.ko, sub: "비공개 질문과 익명 작성자 이름도 담당 교수에게는 보입니다.", actions: [backRoom(course)] },
      qnaSection(ctx, course, "pf_qna"));
  }
  const seed = { id: "seed-q", title: "실습 데이터 형식", body: "CSV와 JSON 중 어떤 형식을 쓰면 되나요?", author: "김서연", at: "2026-11-03" };
  const posts = [seed, ...ctx.state.posts.filter((post) => post.board === "course-qna")];
  return frame("질의응답", ctx, {
    course: true,
    kicker: course.ko,
    sub: "질문 목록은 교수 강의실에 남습니다.",
    actions: [backRoom(course)],
  },
  posts.length ? el("div", { class: "stack" }, ...posts.map((post) => el("article", { class: "sheet" },
    el("h2", { text: post.title }),
    el("p", { class: "pf-sub", text: `${post.author || "수강생"} · ${post.at || ""}` }),
    el("p", { text: post.body || "" }),
  ))) : emptyState("질문이 없습니다", "수강생 질문이 생기면 여기에 모입니다."),
  );
}

function calendarBoard(ctx) {
  // Backend: the professor's calendar and week timetable from the server (areas/professor/calendar-server.js).
  if (backendStatus() !== "off") {
    return frame("일정", ctx, { kicker: "교수", sub: "학사일정과 담당 과목 활동의 마감, 이번 학기 수업시간표입니다. 날짜는 서버 기준입니다." }, serverProfessorCalendar(ctx));
  }
  const day = param(ctx, "day");
  const rows = events.filter((ev) => !day || ev.date === day);
  return frame("일정", ctx, {
    kicker: "교수",
    sub: day ? day : "일정의 활동은 교수용 미리보기·제출·시험 화면으로 열립니다.",
    actions: day ? [linkBtn(routeFor("professor", "calendar"), "전체 일정")] : [],
  },
  rows.length ? el("div", { class: "stack" }, ...rows.map((ev) => {
    const activity = ev.activityId ? activities.find((item) => item.id === ev.activityId) : null;
    const href = activity
      ? routeFor("professor", "activity", { activity, kj: activity.courseId })
      : routeFor("professor", "calendar", { day: ev.date });
    return el("a", { class: "list-row", href, "data-go": href },
      el("span", {}, el("b", { text: ev.label || ev.title || "일정" }), el("div", { class: "tiny", text: activity ? typeLabel(activity.type) : "안내" })),
      el("span", { class: "tiny", text: ev.date }),
    );
  })) : emptyState("일정이 없습니다", "다른 날짜를 선택해 보세요."),
  );
}

export function render(screen, ctx) {
  return mark("professor", renderProf(screen, ctx));
}

if (typeof document !== "undefined" && document.addEventListener) {
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    const target = event.target;
    if (!target || !target.closest || !target.dataset || target.dataset.name !== "q") return;
    if (!target.closest("[data-area=professor]")) return;
    event.preventDefault();
    const button = target.parentElement && target.parentElement.querySelector("[data-act=pf-find]");
    if (button) button.click();
  });
}
