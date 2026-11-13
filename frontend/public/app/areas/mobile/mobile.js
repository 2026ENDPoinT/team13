import { el, icon } from "../../dom.js";
import { coverImage } from "../../ui/assets.js";
import { btn, emptyState, field, mark, courseName } from "../../ui/components.js";
import { activities, courseById, courses, events, student, weekCount } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import { activityHref, attendStatus, dday, typeLabel, TODAY } from "../../shared/format.js";
import { backendStatus, currentUserId } from "../../shared/backend.js";
import { homeEntry, watchHome, memberCourses, todoHref, todoKind, todoLabel, ddayAt, homeMessage } from "../../shared/home-remote.js";
import {
  calendarRanges, timetables, rangeKey, isoDate, isoMonth, addDays as serverAddDays, daysBetween, dayOfWeek,
  shiftMonth as serverShiftMonth, eventOn, closeDay, clock, calendarMessage,
} from "../../shared/calendar-remote.js";
import { serverCourseViews } from "../../shared/course-view.js";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];

function pad(n) {
  return String(n).padStart(2, "0");
}

function parseIso(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

function isoOf(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function addDays(iso, n) {
  const date = parseIso(iso);
  date.setDate(date.getDate() + n);
  return isoOf(date);
}

function weekStart(iso) {
  const date = parseIso(iso);
  date.setDate(date.getDate() - date.getDay());
  return isoOf(date);
}

function shiftMonth(ym, delta) {
  const [y, m] = ym.split("-").map(Number);
  const date = new Date(y, m - 1 + delta, 1);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}`;
}

function dotted(iso) {
  return iso.replaceAll("-", ".");
}

function monthTitle(ym) {
  const [y, m] = ym.split("-");
  return `${y}년 ${Number(m)}월`;
}

function majorityMonth(days) {
  const counts = new Map();
  for (const iso of days) {
    const key = iso.slice(0, 7);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0][0];
}

function monthMatrix(ym) {
  const [y, m] = ym.split("-").map(Number);
  const first = new Date(y, m - 1, 1);
  const start = new Date(first);
  start.setDate(1 - first.getDay());
  const cells = [];
  for (let i = 0; i < 42; i += 1) {
    const date = new Date(start);
    date.setDate(start.getDate() + i);
    cells.push({
      iso: isoOf(date),
      day: date.getDate(),
      inMonth: date.getMonth() === m - 1,
      dow: date.getDay(),
    });
    if (date.getMonth() !== m - 1 && date > first && date.getDay() === 6) break;
  }
  return cells;
}

function pageHref(screen, { view = "week", month = "2026-11", day = "" } = {}) {
  const params = new URLSearchParams();
  if (view === "month") params.set("view", "month");
  if (month && month !== "2026-11") params.set("month", month);
  if (day && day !== TODAY) params.set("day", day);
  const q = params.toString();
  return `${screen.path}${q ? `?${q}` : ""}`;
}

function readCalendar(params) {
  const view = params.get("view") === "month" ? "month" : "week";
  const requested = params.get("month") || "";
  const month = /^\d{4}-\d{2}$/.test(requested) ? requested : "2026-11";
  const requestedDay = params.get("day") || "";
  const anchor = /^\d{4}-\d{2}-\d{2}$/.test(requestedDay) ? requestedDay : (month === "2026-11" ? TODAY : `${month}-01`);
  const start = weekStart(anchor);
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i));
  return { view, month: view === "week" ? majorityMonth(days) : month, anchor, start, days };
}

function roundLink(href, label, text) {
  return el("a", {
    class: "m-round",
    href,
    "data-go": href,
    "aria-label": label,
  }, el("span", { "aria-hidden": "true", text }));
}

function dayLink(iso, day, dow, { today = false, inMonth = true, dots = events.filter((ev) => ev.date === iso).slice(0, 3).map((ev) => ev.type) } = {}) {
  const href = `${hrefFor(find("17_pop_calendar_day_detail"))}&day=${iso}`;
  const cls = [
    "m-day",
    today ? "today" : "",
    inMonth ? "" : "out",
    dow === 0 ? "sun" : "",
    dow === 6 ? "sat" : "",
  ].filter(Boolean).join(" ");
  return el("a", { class: cls, href, "data-go": href, "aria-label": `${day}일` },
    el("span", { class: "m-num", text: String(day) }),
    el("span", { class: "m-dots", "aria-hidden": "true" }, ...dots.map((type) => el("i", { class: type }))),
  );
}

/** `node`가 붙은 뒤 `resource`의 `key`를 구독하고 이번 화면 이동에 한 번 읽는다. */
function bindRemote(node, resource, scope, key, args, draw) {
  draw(resource.peek(key));
  queueMicrotask(() => {
    if (!node.isConnected) return;
    resource.watch(node, key, draw, () => resource.load(key, ...args));
    resource.visit(scope, key, ...args);
  });
}

function serverHref(screen, query) {
  const q = new URLSearchParams(query).toString();
  return `${screen.path}${q ? `?${q}` : ""}`;
}

/** Backend `ready`: the same week/month calendar from `GET /api/me/calendar`; today and the week come from the server. */
function serverCalendar(screen, ctx) {
  const params = ctx.params || new URLSearchParams();
  const view = params.get("view") === "month" ? "month" : "week";
  const month = isoMonth(params.get("month"));
  const date = view === "month" ? (month ? `${month}-01` : "") : isoDate(params.get("day"));
  const key = rangeKey(view, date);
  const slot = el("div", { class: "m-cal", "data-view": view });
  bindRemote(slot, calendarRanges, "mobile:calendar", key, [view, date], (entry) => {
    const data = entry && entry.data;
    if (data) {
      slot.replaceChildren(...serverCalendarView(screen, view, month, data));
      return;
    }
    const failed = Boolean(entry && entry.error);
    const retry = failed ? btn("다시 불러오기", {}, "ghost") : null;
    if (retry) retry.addEventListener("click", () => {
      retry.disabled = true;
      calendarRanges.load(key, view, date);
    });
    slot.replaceChildren(el("p", { class: "m-term", role: failed ? "alert" : "status", text: failed ? calendarMessage(entry.error) : "일정을 불러오는 중입니다." }), retry);
  });
  return slot;
}

function serverCalendarView(screen, view, month, data) {
  const days = daysBetween(data.from, data.to);
  const shown = view === "month" ? month || data.today.slice(0, 7) : days[Math.min(3, days.length - 1)].slice(0, 7);
  const prev = view === "month" ? serverHref(screen, { view, month: serverShiftMonth(shown, -1) }) : serverHref(screen, { day: serverAddDays(data.from, -7) });
  const next = view === "month" ? serverHref(screen, { view, month: serverShiftMonth(shown, 1) }) : serverHref(screen, { day: serverAddDays(data.from, 7) });
  const monthHref = serverHref(screen, { view: "month", month: shown });
  const weekHref = view === "week" ? serverHref(screen, { day: data.from }) : serverHref(screen, shown === data.today.slice(0, 7) ? {} : { day: `${shown}-01` });
  const timetable = hrefFor(find("05_pop_lectime"));
  const inMonth = days.filter((iso) => iso.slice(0, 7) === shown);
  const grid = el("div", { class: view === "week" ? "m-week" : "m-month-grid" });
  DOW.forEach((name, index) => {
    const sun = index === 0 ? " sun" : index === 6 ? " sat" : "";
    grid.append(el("div", { class: `m-dow${sun}`, text: name }));
  });
  for (const iso of days) {
    const dots = [
      ...(data.events || []).filter((event) => eventOn(event, iso)).map(() => "material"),
      ...(data.activities || []).filter((item) => closeDay(item) === iso).map((item) => todoKind(item.type)),
    ].slice(0, 3);
    grid.append(dayLink(iso, Number(iso.slice(-2)), dayOfWeek(iso), { today: iso === data.today, inMonth: view === "week" || iso.slice(0, 7) === shown, dots }));
  }
  return [
    el("div", { class: "m-tools" },
      el("span", { class: "m-tools-side", "aria-hidden": "true" }),
      el("div", { class: "m-seg", role: "tablist", "aria-label": "달력 보기" },
        el("a", { href: monthHref, "data-go": monthHref, role: "tab", "aria-selected": view === "month" ? "true" : "false", class: view === "month" ? "on" : "", text: "월" }),
        el("a", { href: weekHref, "data-go": weekHref, role: "tab", "aria-selected": view === "week" ? "true" : "false", class: view === "week" ? "on" : "", text: "주" }),
      ),
      el("a", { class: "m-icon-link", href: timetable, "data-go": timetable, "aria-label": "수업시간표" }, icon("calendar")),
    ),
    el("div", { class: "m-month" },
      roundLink(prev, view === "month" ? "이전 달" : "이전 주", "‹"),
      el("h1", { text: monthTitle(shown) }),
      roundLink(next, view === "month" ? "다음 달" : "다음 주", "›"),
    ),
    el("p", { class: "m-range", text: view === "week" || !inMonth.length ? `${dotted(data.from)} ~ ${dotted(data.to)}` : `${dotted(inMonth[0])} ~ ${dotted(inMonth[inMonth.length - 1])}` }),
    grid,
  ];
}

function calendar(screen, ctx) {
  const { view, month, anchor, start, days } = readCalendar(ctx.params);
  const end = days[6];
  const prevDays = Array.from({ length: 7 }, (_, i) => addDays(start, i - 7));
  const nextDays = Array.from({ length: 7 }, (_, i) => addDays(start, i + 7));
  const prev = view === "month"
    ? pageHref(screen, { view, month: shiftMonth(month, -1) })
    : pageHref(screen, { view: "week", month: majorityMonth(prevDays), day: prevDays[0] });
  const next = view === "month"
    ? pageHref(screen, { view, month: shiftMonth(month, 1) })
    : pageHref(screen, { view: "week", month: majorityMonth(nextDays), day: nextDays[0] });
  const timetable = hrefFor(find("05_pop_lectime"));
  const tools = el("div", { class: "m-tools" },
    el("span", { class: "m-tools-side", "aria-hidden": "true" }),
    el("div", { class: "m-seg", role: "tablist", "aria-label": "달력 보기" },
      el("a", {
        href: pageHref(screen, { view: "month", month, day: anchor }),
        "data-go": pageHref(screen, { view: "month", month, day: anchor }),
        role: "tab",
        "aria-selected": view === "month" ? "true" : "false",
        class: view === "month" ? "on" : "",
        text: "월",
      }),
      el("a", {
        href: pageHref(screen, { view: "week", month, day: anchor }),
        "data-go": pageHref(screen, { view: "week", month, day: anchor }),
        role: "tab",
        "aria-selected": view === "week" ? "true" : "false",
        class: view === "week" ? "on" : "",
        text: "주",
      }),
    ),
    el("a", { class: "m-icon-link", href: timetable, "data-go": timetable, "aria-label": "수업시간표" }, icon("calendar")),
  );
  const head = el("div", { class: "m-month" },
    roundLink(prev, view === "month" ? "이전 달" : "이전 주", "‹"),
    el("h1", { text: monthTitle(month) }),
    roundLink(next, view === "month" ? "다음 달" : "다음 주", "›"),
  );
  const monthCells = monthMatrix(month).filter((cell) => cell.inMonth);
  const rangeText = view === "week"
    ? `${dotted(start)} ~ ${dotted(end)}`
    : `${dotted(monthCells[0].iso)} ~ ${dotted(monthCells[monthCells.length - 1].iso)}`;
  const range = el("p", { class: "m-range", text: rangeText });
  const grid = el("div", { class: view === "week" ? "m-week" : "m-month-grid" });
  DOW.forEach((name, index) => {
    const sun = index === 0 ? " sun" : index === 6 ? " sat" : "";
    grid.append(el("div", { class: `m-dow${sun}`, text: name }));
  });
  if (view === "week") {
    days.forEach((iso, index) => grid.append(dayLink(iso, Number(iso.slice(-2)), index, { today: iso === TODAY })));
  } else {
    for (const cell of monthMatrix(month)) {
      grid.append(dayLink(cell.iso, cell.day, cell.dow, { today: cell.iso === TODAY, inMonth: cell.inMonth }));
    }
  }
  return el("div", { class: "m-cal", "data-view": view }, tools, head, range, grid);
}

function attendRatio(state) {
  let hit = 0;
  let total = 0;
  for (let week = 1; week <= weekCount; week += 1) {
    const status = attendStatus(state, student.name, week);
    if (status === "empty") continue;
    total += 1;
    if (status === "present" || status === "late" || status === "excused") hit += 1;
  }
  return { hit, total };
}

function courseCard(course, state) {
  const room = course.kind === "special"
    ? hrefFor(find("70_nr_room_submain"), { kj: course.id })
    : hrefFor(find("40_room_submain"), { kj: course.id });
  const activity = course.kind === "special"
    ? room
    : hrefFor(find("50_activity_all"), { kj: course.id });
  const attend = hrefFor(find(course.kind === "special" ? "74_nr_room_attendance" : "41_room_attendance"), { kj: course.id });
  const done = Math.min(weekCount, Math.round((course.progress / 100) * weekCount));
  const ratio = attendRatio(state);
  return el("article", { class: "m-card" },
    el("a", { class: `m-cover ${course.gradient}`, href: room, "data-go": room, "aria-label": `${course.cardTitle || course.ko} 강의실` },
      coverImage(course, 358, 140),
      courseName(course, "strong"),
      el("span", { class: "m-code", text: course.code }),
    ),
    el("div", { class: "m-card-body" },
      el("p", { class: "m-meta" }, icon("calendar"), el("span", { text: course.time })),
      el("p", { class: "m-meta" }, icon("users"), el("span", { text: `${course.credit}학점` })),
      el("div", { class: "m-progress" },
        el("span", { text: "진행 중" }),
        el("span", { text: `${done} / ${weekCount} 주` }),
      ),
      el("div", { class: "m-bar", "aria-hidden": "true" }, el("i", { style: `width:${course.progress}%` })),
      el("div", { class: "m-actions" },
        el("a", { class: "m-action", href: activity, "data-go": activity, text: "참여" }),
        el("a", { class: "m-action", href: attend, "data-go": attend, text: `출석 ${ratio.hit} / ${ratio.total}` }),
      ),
      el("a", {
        class: "btn btn-primary course-study-entry m-study-entry",
        href: hrefFor(find("study_home"), { kj: course.id }),
        "data-go": hrefFor(find("study_home"), { kj: course.id }),
        "aria-label": `${course.cardTitle || course.ko} 학습실 입장`,
        text: "학습실 입장",
      }),
    ),
  );
}

const count = (value) => (Number.isInteger(value) && value >= 0 ? value : null);

/**
 * Backend `ready`: a server course with server facts only. 진행 주 and 출석 come from its `GET /api/me/home` course
 * record (`currentWeek`, `weekCount`, `attendance{attended,total}`) and are left out while the server does not send them.
 */
function serverCourseCard(course, stats) {
  const special = course.kind === "special";
  const room = hrefFor(find(special ? "70_nr_room_submain" : "40_room_submain"), { kj: course.id });
  const activity = special ? room : hrefFor(find("50_activity_all"), { kj: course.id });
  const attend = hrefFor(find(special ? "74_nr_room_attendance" : "41_room_attendance"), { kj: course.id });
  const week = count(stats && stats.currentWeek);
  const weeks = count(stats && stats.weekCount);
  const attendance = stats && stats.attendance;
  const attended = count(attendance && attendance.attended);
  const total = count(attendance && attendance.total);
  const study = hrefFor(find("study_home"), { kj: course.id });
  return el("article", { class: "m-card" },
    el("a", { class: `m-cover ${course.gradient}`, href: room, "data-go": room, "aria-label": `${course.cardTitle || course.ko} 강의실` },
      courseName(course, "strong"),
      el("span", { class: "m-code", text: course.code }),
    ),
    el("div", { class: "m-card-body" },
      el("p", { class: "m-meta" }, icon("calendar"), el("span", { text: course.time || "시간 미정" })),
      course.credit ? el("p", { class: "m-meta" }, icon("users"), el("span", { text: `${course.credit}학점` })) : null,
      week != null && weeks ? el("div", { class: "m-progress" }, el("span", { text: "진행 중" }), el("span", { text: `${Math.min(week, weeks)} / ${weeks} 주` })) : null,
      week != null && weeks ? el("div", { class: "m-bar", "aria-hidden": "true" }, el("i", { style: `width:${Math.round((Math.min(week, weeks) / weeks) * 100)}%` })) : null,
      el("div", { class: "m-actions" },
        el("a", { class: "m-action", href: activity, "data-go": activity, text: "참여" }),
        el("a", { class: "m-action", href: attend, "data-go": attend, text: attended != null && total != null ? `출석 ${attended} / ${total}` : "출석" }),
      ),
      el("a", {
        class: "btn btn-primary course-study-entry m-study-entry",
        href: study,
        "data-go": study,
        "aria-label": `${course.cardTitle || course.ko} 학습실 입장`,
        text: "학습실 입장",
      }),
    ),
  );
}

function serverCourseCards(data) {
  const { matched } = memberCourses(data);
  return [matched.length
    ? el("div", { class: "m-cards" }, ...matched.map(({ local, remote }) => serverCourseCard(local, remote)))
    : emptyState("수강 중인 과목이 없습니다", "ASTRA 서버에 이 계정의 과목이 없습니다.")];
}

function courseCards(list, ctx) {
  return list.length
    ? el("div", { class: "m-cards" }, ...list.map((course) => courseCard(course, ctx.state)))
    : emptyState("수강 중인 정규과목이 없습니다", "이번 학기 과목이 등록되면 여기에 표시됩니다.");
}

/** Backend `ready`: a layout-neutral slot repainted from the home aggregate (shared/home-remote.js). */
function serverSlot(paint) {
  const slot = el("div", { class: "m-server" });
  const draw = (entry) => {
    if (!entry || (!entry.data && !entry.error)) slot.replaceChildren(el("p", { class: "m-term", role: "status", text: "ASTRA 서버에서 불러오는 중입니다." }));
    else if (!entry.data) slot.replaceChildren(el("p", { class: "m-term", role: "alert", text: homeMessage(entry.error) }));
    else slot.replaceChildren(...paint(entry.data));
  };
  draw(homeEntry());
  queueMicrotask(() => {
    if (slot.isConnected) watchHome(slot, draw);
  });
  return slot;
}

/** The term of the user's server courses: the current one, else the first listed, or "". */
function serverTermName() {
  const views = serverCourseViews();
  const current = views.find(({ remote }) => remote.term && remote.term.current) || views.find(({ remote }) => remote.term);
  return current ? current.course.term : "";
}

function courseList(ctx) {
  const list = courses.filter((course) => course.kind === "regular" && course.enrolled && course.term === "2026-2");
  const ready = backendStatus() === "ready";
  return el("div", { class: "m-courses" },
    el("h2", { class: "m-section", text: "수강과목" }),
    ready ? (serverTermName() ? el("p", { class: "m-term", text: serverTermName() }) : null) : el("p", { class: "m-term", text: "2학기" }),
    ready ? serverSlot(serverCourseCards) : courseCards(list, ctx),
  );
}

function serverTodos(data) {
  const todo = data.todo || {};
  const items = Array.isArray(todo.items) ? todo.items : [];
  return [
    el("h3", { text: `남은 활동 ${Number(todo.total) || 0}` }),
    items.length
      ? el("div", { class: "m-todos" }, ...items.map((item) => {
        const href = todoHref(item, data);
        return el(href ? "a" : "div", { class: "m-todo", href, "data-go": href },
          el("i", { class: `m-todo-mark ${todoKind(item.type)}`, "aria-hidden": "true" }),
          el("span", { class: "m-todo-copy" },
            el("b", { text: item.title }),
            el("span", { text: `${todoLabel(item.type)} · ${item.courseName}` }),
          ),
          el("em", { text: ddayAt(item.closeAt, todo.serverTime || data.serverTime) }),
        );
      }))
      : emptyState("남은 활동이 없습니다", "새 과제와 설문이 생기면 이 목록에 표시됩니다."),
  ];
}

/** Backend `ready`: today's classes from this week's timetable (`GET /api/me/timetable`, `date == today`). */
function serverClasses() {
  const list = el("div", { class: "m-classes" });
  bindRemote(list, timetables, "mobile:today", "", ["", ""], (entry) => {
    const data = entry && entry.data;
    if (!data) {
      const failed = Boolean(entry && entry.error);
      list.replaceChildren(el("p", { class: "m-term", role: failed ? "alert" : "status", text: failed ? calendarMessage(entry.error) : "오늘 수업을 불러오는 중입니다." }));
      return;
    }
    const today = (Array.isArray(data.slots) ? data.slots : []).filter((slot) => slot.date === data.today);
    list.replaceChildren(...(today.length
      ? today.map((slot) => el("div", { class: "m-class" },
        el("b", { text: slot.courseName }),
        el("span", { text: slot.startTime ? `${clock(slot.startTime)}–${clock(slot.endTime)}` : `${slot.periodStart || ""}–${slot.periodEnd || slot.periodStart || ""}교시` }),
      ))
      : [el("p", { class: "m-term", text: "오늘은 수업이 없습니다." })]));
  });
  return list;
}

// The memo stays in this browser. Signed in to the server, each account keeps its own (`draft.mobileNotes[u<userId>]`),
// so another account on the same browser never sees it; otherwise it is the sample persona's `draft.mobileNote`.
function noteOf(draft) {
  const user = currentUserId();
  if (!user) return draft.mobileNote || "";
  return (draft.mobileNotes && draft.mobileNotes[`u${user}`]) || "";
}

function todoSheet(ctx) {
  const home = "/ilos/m/main_form.acl";
  const timetable = hrefFor(find("05_pop_lectime"));
  const todos = activities.filter((item) => ["missing", "ongoing", "scheduled"].includes(item.status));
  const saved = noteOf(ctx.state.draft);
  const sheet = el("section", { class: "m-sheet", "aria-label": "할 일" },
    el("div", { class: "m-sheet-h" },
      el("h2", { text: "할 일" }),
      el("a", { class: "m-text-btn", href: home, "data-go": home, text: "닫기" }),
    ),
    saved ? el("p", { class: "m-note-saved", text: saved }) : null,
    el("h3", { text: "오늘 수업" }),
    backendStatus() === "ready" ? serverClasses() : el("div", { class: "m-classes" },
      el("div", { class: "m-class" }, el("b", { text: "소프트웨어공학" }), el("span", { text: "10:00–13:00" })),
      el("div", { class: "m-class" }, el("b", { text: "알고리즘" }), el("span", { text: "14:00–16:00" })),
    ),
    el("a", { class: "m-block-btn", href: timetable, "data-go": timetable, text: "수업시간표" }),
    ...(backendStatus() === "ready" ? [serverSlot(serverTodos)] : [
      el("h3", { text: `남은 활동 ${todos.length}` }),
      todos.length
        ? el("div", { class: "m-todos" }, ...todos.map((item) => {
          const course = courseById(item.courseId);
          const href = activityHref(item, item.courseId);
          return el("a", { class: "m-todo", href, "data-go": href },
            el("i", { class: `m-todo-mark ${item.type}`, "aria-hidden": "true" }),
            el("span", { class: "m-todo-copy" },
              el("b", { text: item.title }),
              el("span", { text: `${typeLabel(item.type)} · ${course.ko}` }),
            ),
            el("em", { text: dday(item.due) || "진행" }),
          );
        }))
        : emptyState("남은 활동이 없습니다", "새 과제와 설문이 생기면 이 목록에 표시됩니다."),
    ]),
    el("div", { class: "m-note", role: "form", "aria-label": "할 일 메모" },
      field("메모", el("input", {
        "data-name": "note",
        value: saved,
        placeholder: "이 브라우저에만 남습니다",
        autocomplete: "off",
        "aria-label": "메모",
      })),
      btn("저장", { "data-act": "mobile-note" }, "primary"),
    ),
  );
  return el("div", { class: "m-sheet-layer" },
    el("a", { class: "m-sheet-scrim", href: home, "data-go": home, "aria-label": "할 일 닫기" }),
    sheet,
  );
}

function tab(href, label, iconName, on) {
  return el("a", {
    href,
    "data-go": href,
    class: on ? "on" : "",
    "aria-current": on ? "page" : null,
  }, icon(iconName), el("span", { text: label }));
}

function tabbar(screen) {
  const mode = screen.mobileMenu;
  return el("nav", { class: "m-tab", "aria-label": "모바일 탐색" },
    tab("/ilos/m/main_form.acl", "홈", "home", mode === "closed"),
    tab("/ilos/m/menu_form.acl", "메뉴", "menu", mode === "nav"),
    tab("/ilos/m/aside_form.acl", "할 일", "check", mode === "aside"),
  );
}

export const actions = {
  "mobile-note"(node, api) {
    const data = api.readForm();
    const note = String(data.note || "").trim();
    if (!note) return api.showErrors({ note: "내용을 입력하세요" });
    const state = api.getState();
    const user = currentUserId();
    if (user) state.draft.mobileNotes = { ...(state.draft.mobileNotes || {}), [`u${user}`]: note };
    else state.draft.mobileNote = note;
    state.toast = "할 일 메모를 저장했습니다";
    api.save();
    api.render();
  },
};

export function render(screen, ctx) {
  return mark("mobile", el("section", { class: "m-app" },
    backendStatus() === "ready" ? serverCalendar(screen, ctx) : calendar(screen, ctx),
    courseList(ctx),
    screen.mobileMenu === "aside" ? todoSheet(ctx) : null,
    tabbar(screen),
  ));
}
