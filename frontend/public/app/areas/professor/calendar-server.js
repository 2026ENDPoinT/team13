// 백엔드 연결 때의 교수 일정(`pf_calendar`): 내 캘린더(월·주, `GET /api/me/calendar`), 고른 날의 일정
// (`GET /api/me/calendar/days/{date}`), 이번 주 수업시간표(`GET /api/me/timetable`). 주소 변수는
// `view`(month 기본 | week), `date`(기준 날짜, 없으면 서버의 오늘), `day`(하루 목록), `tt`(시간표 주의 날짜)다.
// 날짜, 주 경계, 오늘은 서버 응답(`today`, `from`/`to`, `weekStart`/`weekEnd`)으로만 정한다.
// 서버 글자는 텍스트 노드로만 넣는다.
import { el } from "../../dom.js";
import { btn, go, tabs, loadingState, errorState, emptyState, badge } from "../../ui/components.js";
import { courseById } from "../../data.js";
import { backendState, backendStatus } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { routeFor } from "../../shared/routes.js";
import { todoKind, todoLabel } from "../../shared/home-remote.js";
import {
  calendarRanges, calendarDays, timetables, rangeKey, isoDate, addDays, daysBetween, dayOfWeek, shiftMonth,
  monthTitle, koDay, clock, eventOn, closeDay, EVENT_LABEL, calendarMessage,
} from "../../shared/calendar-remote.js";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];
const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function retryButton(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** 캐시로 그리고, 붙은 뒤 구독·조회한다. `paint(data)`는 데이터가 있을 때만 부른다. */
function bind(slot, resource, scope, key, args, what, paint) {
  const draw = (entry) => {
    const data = entry && entry.data;
    if (data) return paint(data);
    slot.replaceChildren(entry && entry.error
      ? errorState(`${what} 불러오지 못했습니다`, calendarMessage(entry.error), retryButton(() => resource.load(key, ...args)))
      : loadingState(`${what} 불러오는 중입니다`));
    return undefined;
  };
  draw(resource.peek(key));
  attached(slot, () => {
    resource.watch(slot, key, draw, () => resource.load(key, ...args));
    resource.visit(scope, key, ...args);
  });
}

/** 현재 주소 상태에 `patch`를 덮어 쓴 `pf_calendar` 주소. 빈 값과 기본값(month)은 뺀다. */
function calHref(state, patch) {
  const next = { ...state, ...patch };
  const extra = {};
  for (const key of ["view", "date", "day", "tt"]) if (next[key]) extra[key] = next[key];
  if (extra.view !== "week") delete extra.view;
  return pf("pf_calendar", extra);
}

/** 담당 과목(`INSTRUCTOR`)이고 샘플에 화면이 있는 과목의 활동만 관리 화면으로 연결한다. */
function manageHref(item) {
  const { status, courses } = backendState();
  if (status !== "ready" || !Array.isArray(courses)) return null;
  const course = courses.find((entry) => entry.courseId === item.courseId);
  if (!course || course.role !== "INSTRUCTOR" || !course.key || courseById(course.key).id !== course.key) return null;
  const kj = course.key;
  const ref = `srv-${item.activityId}`;
  if (item.type === "report") return pf("pf_report_status", { kj });
  if (item.type === "test") return pf("pf_exam_status", { kj });
  if (item.type === "survey") return pf("pf_discuss", { kj });
  if (item.type === "lecture_weeks") return pf("pf_lecture", { kj, n: ref });
  if (item.type === "lecture_material") return pf("pf_pdf", { kj, material: String(item.activityId) });
  return pf("pf_room_week", { kj });
}

function closeText(value) {
  const text = String(value || "");
  return text ? `${text.slice(0, 10).replaceAll("-", ".")} ${clock(text)}` : "";
}

function eventRow(event) {
  const end = event.endDate || event.startDate;
  const range = event.startDate === end
    ? event.startDate.replaceAll("-", ".")
    : `${event.startDate.replaceAll("-", ".")} ~ ${end.replaceAll("-", ".")}`;
  return el("div", { class: "day-item" },
    el("span", { class: "rail-dot", "aria-hidden": "true" }),
    el("span", { class: "day-copy" },
      el("b", { text: event.title }),
      el("span", { class: "tiny", text: `${EVENT_LABEL[event.type] || "학사일정"} · ${range}` }),
    ),
    el("span", {}),
  );
}

function activityRow(item) {
  const href = manageHref(item);
  const kind = todoKind(item.type);
  const course = [item.courseName, [item.courseCode, item.section].filter(Boolean).join("-")].filter(Boolean).join(" ");
  return el(href ? "a" : "div", { class: `day-item${href ? " todo-link" : ""}`, href, "data-go": href, "data-todo": kind },
    el("span", { class: `rail-dot ${kind}`, "aria-hidden": "true" }),
    el("span", { class: "day-copy" },
      el("b", { text: item.published === false ? `[비공개] ${item.title}` : item.title }),
      el("span", { class: "tiny", text: `${course} · ${todoLabel(item.type)} · 마감 ${closeText(item.closeAt)}` }),
    ),
    badge(item.role === "INSTRUCTOR" ? "담당" : "수강", item.role === "INSTRUCTOR" ? "success" : "neutral"),
  );
}

function truncatedNote(data) {
  return data.truncated
    ? el("p", { class: "tiny srv-msg is-alert", role: "status", text: "활동 마감이 많아 앞의 500개만 표시했습니다. 주 보기나 하루 목록에서 나머지를 확인하세요." })
    : null;
}

// ---------- 월·주 달력 ----------

function rangeSection(state) {
  const monthView = state.view !== "week";
  const view = monthView ? "month" : "week";
  const key = rangeKey(view, state.date);
  const slot = el("div", { class: "srv-list" });
  const section = el("section", { class: "srv-catalog", "aria-label": "내 일정" },
    el("div", { class: "cal-tools" },
      tabs([
        { label: "월", href: calHref(state, { view: "", day: "" }), on: monthView },
        { label: "주", href: calHref(state, { view: "week", day: "" }), on: !monthView },
      ]),
      go(calHref(state, { date: "", day: "" }), "오늘", { class: "btn btn-ghost" }),
    ),
    slot,
  );
  bind(slot, calendarRanges, "pf:calendar", key, [view, state.date], "일정을", (data) => {
    const days = daysBetween(data.from, data.to);
    const month = monthView ? (state.date || data.today).slice(0, 7) : days[Math.min(3, days.length - 1)].slice(0, 7);
    const prev = monthView ? `${shiftMonth(month, -1)}-01` : addDays(data.from, -7);
    const next = monthView ? `${shiftMonth(month, 1)}-01` : addDays(data.from, 7);
    const events = Array.isArray(data.events) ? data.events : [];
    const activities = Array.isArray(data.activities) ? data.activities : [];
    const grid = el("div", { class: monthView ? "cal" : "cal week-cal", role: "group", "aria-label": monthView ? "월간 달력" : "주간 달력" });
    for (let i = 0; i < 7; i += 1) {
      grid.append(el("div", { class: `cal-dow${i === 0 ? " sun" : i === 6 ? " sat" : ""}`, "aria-hidden": "true", text: DOW[i] }));
    }
    for (const iso of days) {
      const dow = dayOfWeek(iso);
      const dayEvents = events.filter((event) => eventOn(event, iso));
      const dayItems = activities.filter((item) => closeDay(item) === iso);
      const count = dayEvents.length + dayItems.length;
      const cls = ["cal-day", monthView && iso.slice(0, 7) !== month ? "out" : "", iso === data.today ? "today" : ""].filter(Boolean).join(" ");
      const cell = el("button", {
        type: "button",
        class: cls,
        "data-go": calHref(state, { day: iso }),
        "aria-current": iso === data.today ? "date" : null,
        "aria-pressed": iso === state.day ? "true" : "false",
        "aria-label": `${koDay(iso)} 일정 ${count}건`,
      }, el("div", { class: `day-num${dow === 0 ? " sun" : dow === 6 ? " sat" : ""}`, text: String(Number(iso.slice(8, 10))) }));
      if (monthView) {
        if (count) cell.append(el("span", { class: "tiny", text: `${count}건` }));
      } else {
        for (const event of dayEvents) cell.append(el("span", { class: "pill", text: event.title }));
        for (const item of dayItems) cell.append(el("span", { class: `pill ${todoKind(item.type)}`, text: item.title }));
      }
      grid.append(cell);
    }
    const sorted = [...activities].sort((a, b) => String(a.closeAt).localeCompare(String(b.closeAt)) || a.activityId - b.activityId);
    slot.replaceChildren(
      el("div", { class: "cal-head" },
        go(calHref(state, { date: prev, day: "" }), "‹", { class: "btn btn-ghost", "aria-label": monthView ? "이전 달" : "이전 주" }),
        el("div", { class: "cal-title" },
          el("h2", { text: monthTitle(month) }),
          monthView ? null : el("p", { class: "cal-range", text: `${koDay(data.from)} – ${koDay(data.to)}` }),
        ),
        go(calHref(state, { date: next, day: "" }), "›", { class: "btn btn-ghost", "aria-label": monthView ? "다음 달" : "다음 주" }),
      ),
      grid,
      el("p", { class: "tiny", text: "날짜를 누르면 그날의 일정을 봅니다. 담당 과목의 활동은 관리 화면으로 열립니다." }),
      truncatedNote(data),
      el("h2", { class: "section-label", text: "학사일정" }),
      events.length
        ? el("div", {}, ...events.map(eventRow))
        : el("p", { class: "tiny", text: "이 기간에 학사일정이 없습니다." }),
      el("h2", { class: "section-label", text: "활동 마감" }),
      sorted.length
        ? el("div", {}, ...sorted.map(activityRow))
        : emptyState("이 기간에 마감되는 활동이 없습니다", "다른 달이나 주를 확인하세요."),
    );
  });
  return section;
}

// ---------- 하루 목록 ----------

function daySection(state) {
  const slot = el("div", { class: "srv-list" });
  const section = el("section", { class: "srv-catalog", "aria-label": `${koDay(state.day)} 일정` },
    el("div", { class: "cal-tools" },
      el("h2", { class: "section-label", text: koDay(state.day) }),
      go(calHref(state, { day: "" }), "닫기", { class: "btn btn-ghost", "aria-label": "하루 일정 닫기" }),
    ),
    slot,
  );
  bind(slot, calendarDays, "pf:calendar-day", state.day, [state.day], "이날 일정을", (data) => {
    const events = (Array.isArray(data.events) ? data.events : []).filter((event) => eventOn(event, state.day));
    const items = (Array.isArray(data.activities) ? data.activities : []).filter((item) => closeDay(item) === state.day);
    slot.replaceChildren(...(events.length || items.length
      ? [...events.map(eventRow), ...items.map(activityRow), truncatedNote(data)]
      : [emptyState("이 날짜에 등록된 일정이 없습니다", "다른 날짜를 눌러 보세요.")]));
  });
  return section;
}

// ---------- 수업시간표 ----------

function timeText(slot) {
  if (slot.startTime) return `${clock(slot.startTime)}–${clock(slot.endTime)}`;
  if (slot.periodStart) return `${slot.periodStart}–${slot.periodEnd || slot.periodStart}교시`;
  return "시간 미정";
}

function timetableSection(state) {
  const slot = el("div", { class: "srv-list" });
  const section = el("section", { class: "srv-catalog", "aria-label": "주간 수업시간표" },
    el("h2", { class: "section-label", text: "수업시간표" }),
    slot,
  );
  bind(slot, timetables, "pf:timetable", state.tt, [state.tt], "수업시간표를", (data) => {
    const slots = Array.isArray(data.slots) ? data.slots : [];
    const days = daysBetween(data.weekStart, data.weekEnd);
    const thisWeek = data.weekStart <= data.today && data.today <= data.weekEnd;
    const list = el("div", { class: "srv-list" });
    for (const iso of days) {
      const mine = slots.filter((item) => item.date === iso);
      // 주말은 수업이 있을 때만 보인다.
      const dow = dayOfWeek(iso);
      if (!mine.length && (dow === 0 || dow === 6)) continue;
      const card = el("section", { class: `att-card${iso === data.today ? " is-current" : ""}` }, el("h3", { text: koDay(iso) }));
      if (!mine.length) card.append(el("p", { class: "tiny", text: "수업 없음" }));
      for (const item of mine) {
        const people = Array.isArray(item.instructors) && item.instructors.length ? item.instructors.join(", ") : "";
        card.append(el("div", { class: "tt-line" },
          el("span", { class: "rail-dot", "aria-hidden": "true" }),
          el("span", {},
            el("b", { text: item.courseName }),
            el("span", { class: "tiny", text: [timeText(item), item.room || "강의실 미정", people].filter(Boolean).join(" · ") }),
          ),
        ));
      }
      list.append(card);
    }
    slot.replaceChildren(
      el("div", { class: "tt-nav" },
        go(calHref(state, { tt: addDays(data.weekStart, -7) }), "‹", { class: "btn btn-ghost", "aria-label": "이전 주 시간표" }),
        el("strong", { text: `${koDay(data.weekStart)} – ${koDay(data.weekEnd)}` }),
        go(calHref(state, { tt: addDays(data.weekStart, 7) }), "›", { class: "btn btn-ghost", "aria-label": "다음 주 시간표" }),
        thisWeek ? null : go(calHref(state, { tt: "" }), "이번 주", { class: "btn btn-ghost" }),
      ),
      el("p", { class: "tiny", text: "담당하거나 수강하는 이번 학기 과목의 수업 시간입니다. 휴강과 보강은 반영하지 않습니다." }),
      slots.length ? list : emptyState("이 주에는 수업이 없습니다", "다른 주를 확인하세요."),
    );
  });
  return section;
}

/** `pf_calendar` 백엔드 화면. `anon`이면 로그인 안내만 둔다. 노드를 바로 돌려주고 붙은 뒤 조회한다. */
export function serverProfessorCalendar(ctx) {
  if (backendStatus() === "anon") {
    return el("div", { class: "stack" }, backendPanel({ text: "ASTRA 서버에 로그인하면 담당 과목의 일정과 수업시간표를 봅니다." }));
  }
  const param = (name) => (ctx && ctx.params && ctx.params.get(name)) || "";
  const state = {
    view: param("view") === "week" ? "week" : "",
    date: isoDate(param("date")),
    day: isoDate(param("day")),
    tt: isoDate(param("tt")),
  };
  return el("div", { class: "stack" },
    backendPanel(),
    state.day ? daySection(state) : null,
    rangeSection(state),
    timetableSection(state),
  );
}
