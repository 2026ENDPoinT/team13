// 백엔드 연결(`ready`) 때의 홈 달력(월·주)과 오른쪽 칼럼의 오늘 수업. 날짜와 오늘은 서버 응답
// (`today`, `from`, `to`)으로만 정한다. 주소에 날짜가 없으면 서버가 DB 시계의 오늘로 고른다.
// 일정 막대는 모두 그날의 일정 창(`?pop=day&day=`)으로 간다. 서버 글자는 텍스트 노드로만 넣는다.
import { el, icon } from "../../dom.js";
import { btn, go, loadingState, errorState } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { typeIcon } from "../../shared/format.js";
import { todoKind, todoLabel } from "../../shared/home-remote.js";
import {
  calendarRanges, timetables, rangeKey, isoDate, isoMonth, addDays, daysBetween, dayOfWeek, shiftMonth,
  monthTitle, koShortDay, clock, eventOn, closeDay, EVENT_LABEL, calendarMessage, isDone, DONE_LABEL,
} from "../../shared/calendar-remote.js";

const HOME = "/ilos/main/main_form.acl";
const DOW = ["일", "월", "화", "수", "목", "금", "토"];

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function homeHref(query) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) if (value) params.set(key, value);
  const q = params.toString();
  return `${HOME}${q ? `?${q}` : ""}`;
}

function dayHref(iso) {
  return `${hrefFor(find("17_pop_calendar_day_detail"))}&day=${iso}`;
}

function iconLink(href, label, name) {
  const node = go(href, "", { class: "icon-btn", "aria-label": label });
  node.append(icon(name));
  return node;
}

/** 한 주(7일) 안의 막대: 학사일정은 걸친 날만큼 이어 붙이고, 활동은 마감일 칸 하나다. 겹치면 줄을 나눈다. */
function weekBars(week, data) {
  const segs = [];
  const first = week[0];
  const last = week[6];
  for (const event of data.events || []) {
    const end = event.endDate || event.startDate;
    if (end < first || event.startDate > last) continue;
    const start = week.indexOf(event.startDate < first ? first : event.startDate);
    const stop = week.indexOf(end > last ? last : end);
    segs.push({ start, span: stop - start + 1, event });
  }
  for (const activity of data.activities || []) {
    const at = week.indexOf(closeDay(activity));
    if (at >= 0) segs.push({ start: at, span: 1, activity });
  }
  segs.sort((a, b) => a.start - b.start || b.span - a.span);
  const lanes = [];
  for (const seg of segs) {
    const end = seg.start + seg.span;
    let lane = lanes.findIndex((used) => used.every((c) => c < seg.start || c >= end));
    if (lane < 0) lane = lanes.push([]) - 1;
    for (let c = seg.start; c < end; c += 1) lanes[lane].push(c);
    seg.lane = lane;
  }
  return { segs, lanes: lanes.length };
}

function bar(seg, week) {
  const href = dayHref(week[seg.start]);
  const style = `grid-column: ${seg.start + 1} / span ${seg.span}; grid-row: ${seg.lane + 2}`;
  if (seg.event) {
    const full = `${EVENT_LABEL[seg.event.type] || "일정"} · ${seg.event.title}`;
    return el("a", { class: "ecal-ev plain", href, "data-go": href, title: full, "aria-label": full, style },
      el("span", { class: "ecal-label", text: seg.event.title }));
  }
  const item = seg.activity;
  const kind = todoKind(item.type);
  const done = isDone(item);
  const full = `${item.courseName} · ${todoLabel(item.type)} · ${item.title} 마감 ${clock(item.closeAt)}${done ? ` · ${DONE_LABEL}` : ""}`;
  return el("a", { class: `ecal-ev ${kind}${done ? " is-done" : ""}`, href, "data-go": href, title: full, "aria-label": full, style },
    el("span", { class: "ecal-mark" }, icon(typeIcon(kind))),
    done ? el("span", { class: "srv-done", "aria-hidden": "true", text: DONE_LABEL }) : null,
    el("span", { class: "ecal-label", text: item.title }),
  );
}

function weekRow(week, data, month) {
  const { segs, lanes } = weekBars(week, data);
  const row = el("div", {
    class: "ecal-week",
    style: `grid-template-rows: var(--ecal-head) ${lanes ? `repeat(${lanes}, auto) ` : ""}minmax(var(--space-2), 1fr)`,
  });
  week.forEach((iso, i) => {
    const count = (data.events || []).filter((event) => eventOn(event, iso)).length
      + (data.activities || []).filter((item) => closeDay(item) === iso).length;
    const today = iso === data.today;
    const cls = ["ecal-day", iso.slice(0, 7) === month ? "" : "out", today ? "today" : ""].filter(Boolean).join(" ");
    row.append(el("div", { class: cls, style: `grid-column: ${i + 1}` },
      el("button", {
        type: "button",
        class: "ecal-num day-hit",
        "data-go": dayHref(iso),
        "aria-current": today ? "date" : null,
        "aria-label": `${iso} 일정 ${count}건`,
        text: String(Number(iso.slice(8, 10))),
      }),
    ));
  });
  for (const seg of segs) row.append(bar(seg, week));
  return row;
}

/**
 * 홈 달력. 주소: 주 보기(기본) `week=yyyy-MM-dd` 또는 `month=yyyy-MM`(그달 1일이 든 주),
 * 월 보기 `cal=month&month=yyyy-MM`. 날짜가 없으면 오늘이 든 주·달이다.
 */
export function serverHomeCalendar(params) {
  const monthView = params.get("cal") === "month";
  const month = isoMonth(params.get("month"));
  const date = monthView
    ? (month ? `${month}-01` : "")
    : isoDate(params.get("week")) || (month ? `${month}-01` : "");
  const view = monthView ? "month" : "week";
  const key = rangeKey(view, date);
  const slot = el("div", { class: "home-server-cal" });
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      slot.replaceChildren(entry && entry.error
        ? errorState("일정을 불러오지 못했습니다", calendarMessage(entry.error), retryButton(() => calendarRanges.load(key, view, date)))
        : loadingState("일정을 불러오는 중입니다"));
      return;
    }
    slot.replaceChildren(...calendarView(data, monthView, date));
  };
  paint(calendarRanges.peek(key));
  attached(slot, () => {
    calendarRanges.watch(slot, key, paint, () => calendarRanges.load(key, view, date));
    calendarRanges.visit("home:calendar", key, view, date);
  });
  return slot;
}

function retryButton(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function calendarView(data, monthView, date) {
  const days = daysBetween(data.from, data.to);
  const anchor = date || data.today;
  const month = monthView ? anchor.slice(0, 7) : days[3].slice(0, 7);
  const weekHref = homeHref({ week: monthView ? addDays(anchor, -dayOfWeek(anchor)) : data.from });
  const monthHref = homeHref({ cal: "month", month });
  const prev = monthView ? homeHref({ cal: "month", month: shiftMonth(month, -1) }) : homeHref({ week: addDays(data.from, -7) });
  const next = monthView ? homeHref({ cal: "month", month: shiftMonth(month, 1) }) : homeHref({ week: addDays(data.from, 7) });
  const grid = el("div", { class: `${monthView ? "cal" : "home-cal"} ecal`, role: "group", "aria-label": monthView ? "월간 달력" : "주간 달력" },
    el("div", { class: "ecal-dows", "aria-hidden": "true" },
      ...DOW.map((name) => el("div", { class: `ecal-dow${name === "일" ? " sun" : name === "토" ? " sat" : ""}`, text: name })),
    ),
  );
  for (let i = 0; i + 7 <= days.length; i += 7) grid.append(weekRow(days.slice(i, i + 7), data, month));
  return [
    el("div", { class: "cal-tools" },
      el("div", { class: "seg", role: "tablist", "aria-label": "달력 보기" },
        go(monthHref, "월", { role: "tab", "aria-selected": monthView ? "true" : "false", class: monthView ? "on" : "" }),
        go(weekHref, "주", { role: "tab", "aria-selected": monthView ? "false" : "true", class: monthView ? "" : "on" }),
      ),
      go(homeHref({}), "오늘", { class: "btn btn-ghost" }),
      iconLink(dayHref(data.today), "오늘 일정", "calendar"),
    ),
    el("div", { class: "cal-head" },
      iconLink(prev, monthView ? "이전 달" : "이전 주", "chevL"),
      el("div", { class: "cal-title" },
        el("h1", { text: monthTitle(month) }),
        monthView ? null : el("p", { class: "cal-range", text: `${data.from.replaceAll("-", ".")} ~ ${data.to.replaceAll("-", ".")}` }),
      ),
      iconLink(next, monthView ? "다음 달" : "다음 주", "chev"),
    ),
    grid,
    data.truncated
      ? el("p", { class: "tiny srv-msg is-alert", role: "status", text: "이 기간의 활동 마감이 많아 앞의 500개만 표시했습니다. 주 보기에서 나머지를 확인하세요." })
      : null,
  ];
}

/** 오른쪽 칼럼의 오늘 수업: 이번 주 시간표에서 `date == today`인 수업. */
export function serverTodayClasses() {
  const head = el("span", { class: "tiny", text: "" });
  const list = el("div", { class: "home-server-today" });
  const nodes = [el("div", { class: "aside-h" }, el("h2", { text: "오늘 수업" }), head), list];
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      head.textContent = "";
      list.replaceChildren(el("p", { class: "tiny", role: entry && entry.error ? "alert" : "status", text: entry && entry.error ? calendarMessage(entry.error) : "오늘 수업을 불러오는 중입니다." }));
      return;
    }
    head.textContent = koShortDay(data.today);
    const today = (data.slots || []).filter((slot) => slot.date === data.today);
    list.replaceChildren(...(today.length
      ? today.map((slot) => el("article", { class: "aside-card today-class" },
        el("b", { text: slot.courseName }),
        el("div", { class: "tiny", text: `(${[slot.courseCode, slot.section].filter(Boolean).join("-")})` }),
        el("div", { class: "time-row" }, el("i", { class: "dot" }), el("span", { text: slot.startTime ? `${clock(slot.startTime)}~${clock(slot.endTime)}` : `${slot.periodStart || ""}~${slot.periodEnd || ""}교시` })),
        el("div", { class: "tiny", text: slot.room || "-" }),
      ))
      : [el("p", { class: "tiny", text: "오늘은 수업이 없습니다." })]));
  };
  paint(timetables.peek(""));
  attached(list, () => {
    timetables.watch(list, "", paint, () => timetables.load("", ""));
    timetables.visit("home:today", "", "");
  });
  return nodes;
}
