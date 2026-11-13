// 백엔드 연결(`ready`) 때의 주간 달력(07), 수업시간표(05), 하루 일정 창(17, `?pop=day&day=`).
// 날짜, 주 경계, 오늘은 서버 응답(`today`, `from`/`weekStart`)으로만 정한다. 주 이동은 주소의 날짜
// (`week=`, `date=`)를 바꾸는 링크다. 시간표 학기는 응답 `terms[]`에서 고르고 주소 `term=`으로 보낸다(`srv-tt-term`).
// 학생이 끝낸 활동(`completed`)은 글자 "완료"로도 표시한다. 서버 글자는 텍스트 노드로만 넣는다.
import { el } from "../../dom.js";
import { btn, go, tabs, dialogShell, loadingState, errorState, emptyState, field } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { subscribe } from "../../shared/backend.js";
import { homeEntry, todoKind, todoLabel, todoHref, ddayAt } from "../../shared/home-remote.js";
import {
  calendarRanges, calendarDays, timetables, timetableKey, rangeKey, isoDate, addDays, daysBetween, dayOfWeek,
  koDay, clock, eventOn, closeDay, EVENT_LABEL, calendarMessage, isDone, DONE_LABEL,
} from "../../shared/calendar-remote.js";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];

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

/** 캐시로 그리고, 붙은 뒤 구독·조회한다. `paint(data)`는 데이터가 있을 때만 부른다. `recover`는 실패 때 다시 불러오기 옆 링크다. */
function bind(slot, resource, scope, key, args, what, paint, recover = null) {
  const draw = (entry) => {
    const data = entry && entry.data;
    if (data) return paint(data);
    slot.replaceChildren(entry && entry.error
      ? errorState(`${what} 불러오지 못했습니다`, calendarMessage(entry.error), retryButton(() => resource.load(key, ...args)), recover)
      : loadingState(`${what} 불러오는 중입니다`));
    return undefined;
  };
  draw(resource.peek(key));
  attached(slot, () => {
    resource.watch(slot, key, draw, () => resource.load(key, ...args));
    resource.visit(scope, key, ...args);
  });
}

// ---------- 주간 달력 ----------

function weekHref(date) {
  return hrefFor(find("07_home_calendar_week"), date ? { week: date } : {});
}

/** 07 주간 달력. `?week=yyyy-MM-dd`가 든 주, 없으면 서버의 이번 주. */
export function serverWeekCalendar(ctx) {
  const date = isoDate(ctx.params && ctx.params.get("week"));
  const key = rangeKey("week", date);
  const slot = el("section", { class: "srv-week" });
  bind(slot, calendarRanges, "learning:week", key, ["week", date], "일정을", (data) => {
    const days = daysBetween(data.from, data.to);
    const grid = el("div", { class: "cal week-cal" });
    for (const iso of days) {
      const day = dayOfWeek(iso);
      grid.append(el("div", { class: `cal-dow${day === 0 ? " sun" : day === 6 ? " sat" : ""}`, text: DOW[day] }));
    }
    for (const iso of days) {
      const day = dayOfWeek(iso);
      const href = hrefFor(find("07_home_calendar_week"), { ...(date ? { week: date } : {}), pop: "day", day: iso });
      const events = (data.events || []).filter((event) => eventOn(event, iso));
      const items = (data.activities || []).filter((item) => closeDay(item) === iso);
      const cell = el("button", {
        type: "button",
        class: `cal-day${iso === data.today ? " today" : ""}`,
        "data-go": href,
        "aria-current": iso === data.today ? "date" : null,
        "aria-label": `${koDay(iso)} 일정 ${events.length + items.length}건${items.some(isDone) ? `, ${DONE_LABEL} ${items.filter(isDone).length}건` : ""}`,
      }, el("div", { class: `day-num${day === 0 ? " sun" : day === 6 ? " sat" : ""}`, text: String(Number(iso.slice(8, 10))) }));
      for (const event of events) cell.append(el("span", { class: "pill", text: event.title }));
      for (const item of items) {
        cell.append(el("span", { class: `pill ${todoKind(item.type)}${isDone(item) ? " is-done" : ""}` },
          isDone(item) ? el("span", { class: "srv-done", text: DONE_LABEL }) : null,
          `${item.courseName} ${item.title}`));
      }
      grid.append(cell);
    }
    slot.replaceChildren(
      el("div", { class: "cal-head" },
        el("h1", { text: `${days[3].slice(0, 4)}년 ${Number(days[3].slice(5, 7))}월` }),
        tabs([
          { label: "월", href: hrefFor(find("00_main_home"), { cal: "month", month: days[3].slice(0, 7) }) },
          { label: "주", on: true },
        ]),
        el("div", { class: "cal-nav" },
          go(weekHref(addDays(data.from, -7)), "‹", { class: "btn btn-ghost", "aria-label": "이전 주" }),
          go(weekHref(""), "오늘", { class: "btn btn-ghost" }),
          go(weekHref(addDays(data.from, 7)), "›", { class: "btn btn-ghost", "aria-label": "다음 주" }),
        ),
      ),
      el("p", { class: "subhead", text: `${koDay(data.from)} – ${koDay(data.to)}` }),
      grid,
      data.truncated ? el("p", { class: "tiny srv-msg is-alert", role: "status", text: "활동 마감이 많아 앞의 500개만 표시했습니다." }) : null,
    );
  });
  return slot;
}

// ---------- 수업시간표 ----------

function minutes(value) {
  const match = /^(\d{2}):(\d{2})/.exec(String(value || ""));
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
}

function timeText(slot) {
  if (slot.startTime) return `${clock(slot.startTime)}–${clock(slot.endTime)}`;
  if (slot.periodStart) return `${slot.periodStart}–${slot.periodEnd || slot.periodStart}교시`;
  return "시간 미정";
}

// 응답 `terms[]`로 받은 학기(termId → 학기). 주소의 `term`은 이 목록이 있으면 그 안의 학기만 보낸다. session이 바뀌면 지운다.
const knownTerms = new Map();
subscribe(() => knownTerms.clear());

function ttHref(date, termId) {
  const query = {};
  if (termId) query.term = String(termId);
  if (date) query.date = date;
  return hrefFor(find("05_pop_lectime"), query);
}

const outside = (term, iso) => Boolean(term) && ((term.startDate && iso < term.startDate) || (term.endDate && iso > term.endDate));

function termLabel(term) {
  return `${term.name || `${term.year} ${term.code}`}${term.current ? " (현재)" : ""}`;
}

/** 학기 고르기 줄. `srv-tt-term`(learning/actions.js)이 고른 학기의 첫 화면(날짜 없음)으로 간다. */
function termPicker(terms, termId) {
  return el("div", { class: "srv-filters srv-tt-terms", role: "group", "aria-label": "학기 고르기" },
    field("학기", el("select", { "data-tt-term": "" },
      ...terms.map((term) => el("option", { value: String(term.termId), text: termLabel(term), selected: String(term.termId) === String(termId) ? true : null })))),
    el("div", { class: "srv-filters__go" }, btn("보기", { "data-act": "srv-tt-term" }, "secondary")),
  );
}

/** `srv-tt-term`: 고른 학기로 간다. 목록에 없는 값은 보내지 않는다. */
export function pickTimetableTerm(node, api) {
  const select = node.closest(".srv-tt-terms") && node.closest(".srv-tt-terms").querySelector("[data-tt-term]");
  const value = select ? select.value : "";
  api.navigate(ttHref("", knownTerms.has(value) ? value : ""));
}

/**
 * 05 수업시간표. `?term=`(응답 `terms[]`의 학기)과 `?date=`가 든 주(일~토), 없으면 서버의 이번 주(학기를 고르면 그 학기 안의
 * 주). 고른 학기 밖의 날짜는 보내지 않는다. 시각이 없는 수업은 목록에만 둔다.
 */
export function serverTimetable(ctx) {
  const params = ctx.params || new URLSearchParams();
  const asked = /^[1-9]\d{0,15}$/.test(params.get("term") || "") ? params.get("term") : "";
  const termId = asked && (!knownTerms.size || knownTerms.has(asked)) ? asked : "";
  const askedDate = isoDate(params.get("date"));
  const date = termId && outside(knownTerms.get(termId), askedDate) ? "" : askedDate;
  const key = timetableKey(date, termId);
  const slot = el("div", { class: "srv-timetable" });
  const recover = termId ? go(ttHref("", ""), "현재 학기 보기", { class: "btn btn-ghost" }) : null;
  bind(slot, timetables, "learning:timetable", key, [date, termId], "수업시간표를", (data) => {
    const terms = Array.isArray(data.terms) ? data.terms : [];
    for (const term of terms) knownTerms.set(String(term.termId), term);
    const shownTerm = data.termId != null ? terms.find((term) => String(term.termId) === String(data.termId)) || null : null;
    const chosen = termId ? String(data.termId ?? termId) : "";
    const slots = Array.isArray(data.slots) ? data.slots : [];
    const days = daysBetween(data.weekStart, data.weekEnd);
    // 평일은 항상, 주말은 수업이 있을 때만 칸을 둔다.
    const shown = days.filter((iso) => { const d = dayOfWeek(iso); return (d >= 1 && d <= 5) || slots.some((s) => s.date === iso); });
    const timed = slots.filter((s) => minutes(s.startTime) != null && minutes(s.endTime) != null);
    const first = Math.min(9, ...timed.map((s) => Math.floor(minutes(s.startTime) / 60)));
    const last = Math.max(18, ...timed.map((s) => Math.ceil(minutes(s.endTime) / 60) - 1));
    const grid = el("div", {
      class: "tt",
      role: "grid",
      "aria-label": "주간 수업시간표",
      style: `grid-template-columns: 64px repeat(${shown.length}, minmax(0, 1fr)); grid-template-rows: 36px repeat(${last - first + 1}, minmax(48px, auto))`,
    });
    grid.append(el("div", { class: "tt-h", text: "" }));
    for (const iso of shown) grid.append(el("div", { class: `tt-h${iso === data.today ? " is-today" : ""}`, text: `${DOW[dayOfWeek(iso)]} ${Number(iso.slice(8, 10))}` }));
    for (let hour = first; hour <= last; hour += 1) {
      grid.append(el("div", { class: "tt-time", text: `${String(hour).padStart(2, "0")}:00` }));
      for (let i = 0; i < shown.length; i += 1) grid.append(el("div", { class: "tt-slot" }));
    }
    for (const item of timed) {
      const col = shown.indexOf(item.date);
      if (col < 0) continue;
      const start = minutes(item.startTime);
      const end = Math.max(minutes(item.endTime), start + 1);
      const row = Math.floor(start / 60) - first + 2;
      const span = Math.max(1, Math.ceil(end / 60) - Math.floor(start / 60));
      grid.append(el("div", {
        class: `tt-block g${(Number(item.courseId) % 8) + 1}`,
        style: `grid-column:${col + 2};grid-row:${row} / span ${span}`,
      },
      el("b", { text: item.courseName }),
      el("span", { text: timeText(item) }),
      el("span", { text: [item.instructors && item.instructors.join(", "), item.room || "-"].filter(Boolean).join(" · ") }),
      ));
    }
    const list = el("div", { class: "tt-list" });
    for (const iso of shown) {
      const mine = slots.filter((s) => s.date === iso);
      const card = el("section", { class: "att-card" }, el("h2", { text: koDay(iso) }));
      if (!mine.length) card.append(el("p", { class: "tiny", text: "수업 없음" }));
      for (const item of mine) {
        card.append(el("div", { class: "tt-line" },
          el("span", { class: "rail-dot" }),
          el("span", {}, el("b", { text: item.courseName }), el("span", { class: "tiny", text: `${timeText(item)} · ${item.room || "-"}` })),
        ));
      }
      list.append(card);
    }
    const untimed = slots.filter((s) => !timed.includes(s));
    slot.replaceChildren(
      el("div", { class: "tt-nav" },
        outside(shownTerm, addDays(data.weekStart, -1)) ? null : go(ttHref(addDays(data.weekStart, -7), chosen), "‹", { class: "btn btn-ghost", "aria-label": "이전 주" }),
        el("strong", { text: `${koDay(data.weekStart)} – ${koDay(data.weekEnd)}` }),
        outside(shownTerm, addDays(data.weekEnd, 1)) ? null : go(ttHref(addDays(data.weekStart, 7), chosen), "›", { class: "btn btn-ghost", "aria-label": "다음 주" }),
        data.weekStart <= data.today && data.today <= data.weekEnd ? null : go(ttHref("", ""), "이번 주", { class: "btn btn-ghost" }),
      ),
      terms.length ? termPicker(terms, data.termId ?? "") : null,
      asked && !termId ? el("p", { class: "tiny srv-msg is-alert", role: "status", text: "고른 학기에 내 과목이 없어 현재 학기를 보여 줍니다." }) : null,
      el("p", { class: "tiny", text: `${shownTerm ? `${shownTerm.name || "고른 학기"}에 ` : ""}담당하거나 수강이 확정된 과목의 수업 시간입니다. 휴강과 보강은 반영하지 않습니다.` }),
      slots.length ? grid : emptyState("이 주에는 수업이 없습니다", "다른 주를 보거나 수강 과목을 확인하세요."),
      slots.length ? list : null,
      untimed.length
        ? el("div", { class: "srv-untimed" }, el("h2", { class: "section-label", text: "시각이 정해지지 않은 수업" }),
          ...untimed.map((item) => el("p", { class: "tiny", text: `${DOW[item.dayOfWeek]} · ${item.courseName} · ${timeText(item)} · ${item.room || "-"}` })))
        : null,
    );
  }, recover);
  return slot;
}

// ---------- 하루 일정 ----------

function dayRows(data, day) {
  const events = (data.events || []).filter((event) => eventOn(event, day));
  const items = (data.activities || []).filter((item) => closeDay(item) === day);
  const home = homeEntry() && homeEntry().data;
  const rows = [];
  for (const event of events) {
    rows.push(el("div", { class: "day-item" },
      el("span", { class: "rail-dot", "aria-hidden": "true" }),
      el("span", { class: "day-copy" },
        el("b", { text: event.title }),
        el("span", { class: "tiny", text: `${EVENT_LABEL[event.type] || "학사일정"} · ${event.startDate === (event.endDate || event.startDate) ? event.startDate.replaceAll("-", ".") : `${event.startDate.replaceAll("-", ".")} ~ ${event.endDate.replaceAll("-", ".")}`}` }),
      ),
    ));
  }
  for (const item of items) {
    const href = todoHref(item, home);
    const kind = todoKind(item.type);
    const due = ddayAt(item.closeAt, data.serverTime);
    const done = isDone(item);
    rows.push(el(href ? "a" : "div", { class: `day-item${href ? " todo-link" : ""}${done ? " is-done" : ""}`, href, "data-go": href, "data-todo": kind },
      el("span", { class: `rail-dot ${kind}`, "aria-hidden": "true" }),
      el("span", { class: "day-copy" },
        el("b", { text: item.published === false ? `[비공개] ${item.title}` : item.title }),
        el("span", { class: "tiny", text: `${item.courseName} · ${[item.courseCode, item.section].filter(Boolean).join("-")} · ${todoLabel(item.type)} · 마감 ${clock(item.closeAt)}` }),
      ),
      done
        ? el("span", { class: "srv-done", text: DONE_LABEL })
        : el("span", { class: due === "D-day" || due.startsWith("D-") ? "dday" : "tiny", text: due === "마감" ? "지남" : due }),
    ));
  }
  return rows;
}

/** 17 하루 일정 창. `day=`가 없으면 서버의 오늘(이번 주 응답의 `today`)이다. */
export function serverDayDialog(ctx) {
  const day = isoDate(ctx.params && ctx.params.get("day"));
  const body = el("div", { class: "day-sheet" });
  const title = el("span", { text: day ? koDay(day) : "오늘 일정" });
  const paint = (data, on) => {
    title.textContent = koDay(on);
    const rows = dayRows(data, on);
    body.replaceChildren(...(rows.length ? rows : [el("p", { class: "msg-placeholder", text: "이 날짜에 등록된 일정이 없습니다." })]));
  };
  if (day) bind(body, calendarDays, "learning:day", day, [day], "일정을", (data) => paint(data, day));
  else bind(body, calendarRanges, "learning:day", rangeKey("week", ""), ["week", ""], "일정을", (data) => paint(data, data.today));
  const shell = dialogShell(day ? koDay(day) : "오늘 일정", body);
  // 제목은 서버의 오늘을 받은 뒤 바뀐다.
  const heading = shell.querySelector(".modal-h h2");
  if (heading) heading.replaceChildren(title);
  return shell;
}
