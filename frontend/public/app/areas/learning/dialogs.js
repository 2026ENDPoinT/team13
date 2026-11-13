import { el, icon } from "../../dom.js";
import { btn, field, dialogShell, go, loadingState, errorState } from "../../ui/components.js";
import { events, people, surveyQuestions, activities, courseById } from "../../data.js";
import { dday, typeLabel, activityHref } from "../../shared/format.js";
import { hrefFor, find } from "../../manifest.js";
import { remoteSubmitDialog } from "./assignments-server.js";
import { backendStatus } from "../../shared/backend.js";
import { serverDayDialog } from "./calendar-server.js";
import { homeEntry, todoHref, todoKind, todoLabel, ddayAt } from "../../shared/home-remote.js";
import { calendarRanges, rangeKey, closeDay, calendarMessage, EVENT_LABEL } from "../../shared/calendar-remote.js";
import { usesServerMessages, serverRecipientDialog } from "./messages-server.js";
import { remoteSurveyDialog } from "./surveys-server.js";

const DOW = ["일", "월", "화", "수", "목", "금", "토"];

function pad(n) {
  return String(n).padStart(2, "0");
}

function koDay(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  if (Number.isNaN(date.getTime())) return iso;
  return `${y}.${pad(m)}.${pad(d)} (${DOW[date.getDay()]})`;
}

function named(node, error) {
  if (error) node.setAttribute("aria-invalid", "true");
  return node;
}

const UPCOMING = 6;

/**
 * Backend `ready`: 다가오는 일정 from this month's server calendar (`GET /api/me/calendar?view=month`): academic events
 * that have not ended and activity deadlines from the server's today on, nearest first.
 */
function serverUpcomingDialog() {
  const key = rangeKey("month", "");
  const body = el("div", { class: "day-sheet" });
  const draw = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (entry && entry.error) {
        const retry = btn("다시 불러오기", {}, "ghost");
        retry.addEventListener("click", () => {
          retry.disabled = true;
          calendarRanges.load(key, "month", "");
        });
        body.replaceChildren(errorState("일정을 불러오지 못했습니다", calendarMessage(entry.error), retry));
      } else body.replaceChildren(loadingState("일정을 불러오는 중입니다"));
      return;
    }
    const home = homeEntry() && homeEntry().data;
    const rows = [
      ...(data.events || []).filter((event) => (event.endDate || event.startDate) >= data.today)
        .map((event) => ({ day: event.startDate < data.today ? data.today : event.startDate, node: el("p", {},
          el("b", { text: event.title }), " · ", EVENT_LABEL[event.type] || "학사일정") })),
      ...(data.activities || []).filter((item) => closeDay(item) >= data.today)
        .map((item) => {
          const href = todoHref(item, home);
          return { day: closeDay(item), node: el(href ? "a" : "p", { class: href ? "todo-link" : null, href, "data-go": href, "data-todo": href ? todoKind(item.type) : null },
            el("b", { text: item.title }), " · ", item.courseName, " · ", ddayAt(item.closeAt, data.serverTime) || todoLabel(item.type)) };
        }),
    ].sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0)).slice(0, UPCOMING);
    body.replaceChildren(...(rows.length ? rows.map((row) => row.node) : [el("p", { class: "msg-placeholder", text: "이번 달에 남은 일정이 없습니다." })]));
  };
  draw(calendarRanges.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    calendarRanges.watch(body, key, draw, () => calendarRanges.load(key, "month", ""));
    calendarRanges.visit("learning:upcoming", key, "month", "");
  });
  return dialogShell("다가오는 일정", el("div", {}, body, go(hrefFor(find("07_home_calendar_week")), "주간 달력", { class: "btn btn-secondary" })));
}

export function renderDialog(name, ctx) {
  const err = ctx.state.formError || {};
  // Backend `ready`: that day's academic events and deadlines from the server.
  if (name === "day" && backendStatus() === "ready") return serverDayDialog(ctx);
  if (name === "day") {
    const day = (ctx.params && ctx.params.get("day")) || "2026-11-10";
    const list = events.filter((ev) => ev.date === day);
    const body = el("div", { class: "day-sheet" });
    if (!list.length) body.append(el("p", { class: "msg-placeholder", text: "이 날짜에 등록된 일정이 없습니다." }));
    for (const ev of list) {
      const activity = ev.activityId ? activities.find((item) => item.id === ev.activityId) : null;
      const course = ev.courseId ? courseById(ev.courseId) : null;
      const when = ev.date === "2026-11-10" ? "오늘" : ev.date > "2026-11-10" ? dday(`${ev.date}T23:59`) : "지남";
      const label = ev.label || ev.title;
      const href = activity ? activityHref(activity) : course ? hrefFor(find("40_room_submain"), { kj: course.id }) : "";
      const row = el(href ? "a" : "div", {
        class: `day-item${href ? " todo-link" : ""}`,
        href: href || null,
        "data-go": href || null,
        "data-item": activity ? activity.id : null,
        "data-todo": activity ? activity.type : null,
      },
      el("span", { class: `rail-dot ${ev.type}`, "aria-hidden": "true" }),
      el("span", { class: "day-copy" },
        el("b", { text: label }),
        el("span", { class: "tiny", text: course ? `${course.ko} · ${course.code} · ${typeLabel(ev.type)}` : typeLabel(ev.type) }),
      ),
      el("span", { class: when === "오늘" || (when && when.startsWith("D-")) ? "dday" : "tiny", text: when }),
      );
      body.append(row);
    }
    return dialogShell(koDay(day), body);
  }
  if (name === "submit" && ctx.screen && ctx.screen.serverAssignment) return remoteSubmitDialog(ctx);
  if (name === "recipients" && usesServerMessages(ctx.params)) return serverRecipientDialog(ctx);
  if (name === "submit") {
    const a = activities.find((item) => item.id === ((ctx.params && ctx.params.get("n")) || "r-open")) || activities.find((item) => item.id === "r-open");
    const fileName = ctx.state.draft.fileName || "";
    const title = named(el("input", { "data-name": "title", value: a.title, placeholder: "제목" }), err.title);
    const area = named(el("textarea", { "data-name": "body", placeholder: "제출 설명을 입력하세요.", text: ctx.state.draft.reportBody || "" }), err.body);
    const file = named(el("input", { type: "file", "data-name": "file", class: "file-input" }), err.file);
    return dialogShell("과제 제출", el("div", { class: "submit-form" },
      el("p", { class: "tiny", text: a.title }),
      el("div", { class: "editor" },
        el("div", { class: "editor-bar", role: "toolbar", "aria-label": "서식" },
          btn("굵게", { "data-act": "format-note", "data-mark": "**" }, "ghost"),
          btn("기울임", { "data-act": "format-note", "data-mark": "*" }, "ghost"),
          btn("목록", { "data-act": "format-note", "data-mark": "list" }, "ghost"),
        ),
        field("설명", area, err.body),
      ),
      field("제목", title, err.title),
      el("div", { class: "drop", "data-drop": "1", text: fileName ? `선택됨 · ${fileName}` : "파일을 마우스로 끌어 놓아 업로드하세요. 파일 1개, 이 샘플은 학교 서버로 보내지 않습니다." }),
      el("div", { class: "compose-files" },
        el("label", { class: "btn btn-secondary" }, icon("file"), "파일첨부", file),
        err.file ? el("span", { class: "err", text: err.file }) : el("span", { class: "tiny", text: fileName || "아직 선택한 파일이 없습니다." }),
      ),
      el("div", { class: "submit-actions" },
        btn("취소", { "data-act": "close" }, "ghost"),
        btn("제출", { "data-act": "submit-report" }, "primary"),
      ),
    ), { wide: true });
  }
  if (name === "survey" && ctx.screen && ctx.screen.serverSurvey) return remoteSurveyDialog(ctx);
  if (name === "survey") {
    const id = (ctx.params && (ctx.params.get("n") || ctx.params.get("id"))) || "campus";
    const saved = ctx.state.surveys[id] || {};
    return dialogShell("설문", el("div", { class: "survey-form" },
      el("p", { class: "lead", text: "응답은 이 브라우저에만 저장됩니다. 모든 문항을 고른 뒤에 저장할 수 있습니다." }),
      saved.done ? el("p", { class: "note ok", text: "이미 저장한 응답입니다. 고친 뒤 다시 저장할 수 있습니다." }) : null,
      ...surveyQuestions.map((q) => {
        const select = named(el("select", { "data-name": q.id },
          el("option", { value: "", text: "선택" }),
          ...q.options.map((op) => el("option", { value: op, text: op, selected: saved[q.id] === op ? true : null })),
        ), err[q.id]);
        return field(q.prompt, select, err[q.id]);
      }),
      el("div", { class: "submit-actions" },
        btn("취소", { "data-act": "close" }, "ghost"),
        btn("응답 저장", { "data-act": "save-survey", "data-survey": id }, "primary"),
      ),
    ));
  }
  if (name === "recipients") {
    const chosen = new Set(String((ctx.state.draft.mail && ctx.state.draft.mail.to) || "").split(",").map((s) => s.trim()).filter(Boolean));
    return dialogShell("수신자", el("div", { class: "recipient-list" },
      el("p", { class: "tiny", text: "한 명 이상 고르면 쪽지 쓰기로 돌아갑니다." }),
      ...people.map((p) => el("label", { class: "choice" },
        el("input", { type: "checkbox", "data-name": "recipient", value: p.name, checked: chosen.has(p.name) ? true : null }),
        el("span", { class: "msg-avatar", text: p.name.slice(0, 1) }),
        el("span", {}, el("b", { text: p.name }), el("span", { class: "tiny", text: p.role === "professor" ? "교수" : "학생" })),
      )),
      err.recipient ? el("p", { class: "err", text: err.recipient }) : null,
      el("div", { class: "submit-actions" },
        btn("취소", { "data-act": "close" }, "ghost"),
        btn("선택", { "data-act": "pick-recipients" }, "primary"),
      ),
    ));
  }
  if (name === "todo" || name === "todo-more") return null;
  if (name === "calendar" && backendStatus() === "ready") return serverUpcomingDialog();
  if (name === "calendar") {
    const soon = events.filter((ev) => ev.date >= "2026-11-10").slice(0, 4);
    return dialogShell("다가오는 일정", el("div", { class: "day-sheet" },
      ...soon.map((ev) => el("p", {}, el("b", { text: ev.title }), " · ", courseById(ev.courseId).ko, " · ", dday(`${ev.date}T23:59`) || typeLabel(ev.type))),
      go(hrefFor(find("07_home_calendar_week")), "주간 달력", { class: "btn btn-secondary" }),
    ));
  }
  return null;
}
