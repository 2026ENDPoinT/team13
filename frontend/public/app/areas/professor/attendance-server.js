// 교수 출석 관리의 서버 버전(ADR-0030). 세 탭이 있다.
// - 일반(pf_attend): 과목 출석 설정(`If-Match`), 수강생 × 차시 출석부(쪽 번호와 정렬 허용 목록), 칸 하나를 사유와 함께 바꾸기
//   (`If-Match`는 그 칸의 version, 기록이 없으면 0), 판정 전 칸 일괄 채우기.
// - 스마트(pf_attend_smart): PIN 또는 QR 회차 열기, 진행 중인 회차의 PIN(회차가 끝날 때까지 그대로)·지금 QR token과 다음
//   교체 시각, 실시간 수. 회차 화면은 `pollAfterSec`(실시간 수를 다시 읽는 간격)마다, QR은 그보다 이른 `qrRefreshAt`에도
//   다시 읽는다. PIN 회차에는 교체가 없다(`qrRefreshAt` null). 마감과 취소.
// - 휴강·보강(pf_attend_makeup): 차시 등록(`Idempotency-Key`), 수정과 휴강(`If-Match`), 삭제, 휴강한 차시의 보강 등록.
// 버튼은 `data-act="pf-attend-cmd"`의 `data-cmd`로 professor/actions.js를 거친다. 출석부 칸은 select의 change로 바로 보낸다.
import { el } from "../../dom.js";
import { btn, field, go, badge, emptyState, loadingState, errorState, pagination } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { sessionDrafts, keepDraft, bindDraft } from "../../shared/session-drafts.js";
import { koDateTime } from "../../shared/home-remote.js";
import { routeFor } from "../../shared/routes.js";
import { qrSvg } from "../../shared/qr-code.js";
import { groupLists, groupsKey } from "../../shared/groups-remote.js";
import {
  ROSTER_PAGE, ROSTER_SORTS, rosterSort, STATUS, DECIDABLE, SOURCE, METHOD, ROUND_STATE, statusTone,
  attendanceViews, rosters, attendanceSettings, activeRounds, roundViews, attendanceKey, rosterKey, roundKey, loadRoster,
  setRecord, fillRecords, updateAttendanceSettings, createSession, updateSession, deleteSession, openRound, finishRound,
  pollDelay, serverGap, koDay, timeRange, sessionName, attendanceMessage,
} from "../../shared/attendance-remote.js";

const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const missing = (error) => error instanceof ApiError && error.status === 404;
const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
const note = (text) => el("p", { class: "pf-sub", text });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 출석을 서버에서 관리할 수 있습니다.";

// 출석부 칸을 바꿀 때 쓰는 사유. 다시 그려도 남고 세션이 바뀌면 버린다.
let reasonDraft = "";
subscribe(() => { reasonDraft = ""; });

// 설정·차시 폼에 입력 중인 값과 그 폼의 안내(`shared/session-drafts.js`의 초안). 409 재조회로 폼을 다시 그려도 남고,
// 저장에는 초안의 `version`을 보낸다. 세션이 바뀌면 버린다.
const formDrafts = sessionDrafts(); // settingsDraftKey | sessionDraftKey -> { values, version, note, conflict }
// 차시 목록(휴강 전환·삭제)의 안내. 409 재조회로 목록을 다시 그려도 남는다.
const listNotices = sessionDrafts(); // courseId -> { text, alert }
const settingsDraftKey = (courseId) => `settings:${courseId}`;
const sessionDraftKey = (courseId, sessionId, makeupOf) => `session:${courseId}:${sessionId || (makeupOf ? `makeup-${makeupOf}` : "new")}`;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function gate(course) {
  if (backendStatus() === "anon") return stack(backendPanel({ text: LOGIN }));
  if (!courseIdFor(course.id)) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 출석을 관리할 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") return stack(backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 출석을 관리할 수 있습니다."));
  return null;
}

const param = (ctx, key) => (ctx.params && ctx.params.get(key)) || "";
const positive = (value) => {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
};

/** 응답 상태를 한 줄로 보여 주는 줄. `say`로 바꾼다. */
function statusLine(name) {
  return el("p", { class: "pf-sub", role: "status", "aria-live": "polite", [`data-${name}-status`]: "" });
}

function paintLine(line, text, alert) {
  line.className = alert ? "err" : "pf-sub";
  line.setAttribute("role", alert ? "alert" : "status");
  line.textContent = text;
}

function say(name, text, alert) {
  for (const line of document.querySelectorAll(`[data-${name}-status]`)) paintLine(line, text, alert);
}

/** 새로 그린 상태 줄에 남겨 둔 안내를 다시 쓴다. */
function restoreLine(root, name, note) {
  const line = note && root.querySelector(`[data-${name}-status]`);
  if (line) paintLine(line, note.text, note.alert);
}

function allSessions(data) {
  return (data && Array.isArray(data.weeks) ? data.weeks : []).flatMap((week) => week.sessions || []);
}

/** 차시 목록(담당자의 `GET …/attendance`)을 화면에 붙인다. `paint(data)`는 응답이 있을 때만 불린다. */
function withSessions(courseId, node, paint, scope) {
  const key = attendanceKey(courseId);
  const load = () => attendanceViews.load(key, courseId);
  const draw = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) node.replaceChildren(loadingState("차시를 불러오는 중입니다"));
      else if (missing(entry.error)) node.replaceChildren(emptyState("출석을 볼 수 없습니다", "이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다."));
      else node.replaceChildren(errorState("차시를 불러오지 못했습니다", attendanceMessage(entry.error), retry(load)));
      return;
    }
    paint(data, entry.error);
  };
  draw(attendanceViews.peek(key));
  attached(node, () => {
    attendanceViews.watch(node, key, draw, load);
    if (freshVisit(scope, key) === "entry" || !attendanceViews.peek(key)) load();
  });
}

// ---------- 일반: 설정 ----------

function settingsSheet(courseId) {
  const key = attendanceKey(courseId);
  const load = () => attendanceSettings.load(key, courseId);
  const body = el("div", {});
  const sheet = el("section", { class: "sheet", "aria-label": "출석 설정" }, el("h2", { text: "출석 설정" }), body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(note("설정을 불러오는 중입니다."));
      else body.replaceChildren(el("p", { class: "err", role: "alert", text: attendanceMessage(entry.error) }), retry(load));
      return;
    }
    const input = (name, value, attrs) => el("input", { type: "number", "data-field": name, value: String(value), ...attrs });
    const form = el("div", { class: "pf-form", "data-attend-settings": "" },
      field("지각 기준 (회차를 연 뒤 분)", input("lateAfterMinutes", data.lateAfterMinutes, { min: "1", max: "240" })),
      field("스마트출석 기본 진행 시간 (분)", input("roundMinutes", data.roundMinutes, { min: "1", max: "240" })),
      field("지각 몇 번을 결석 1번으로 (0은 환산 안 함)", input("latesPerAbsence", data.latesPerAbsence, { min: "0", max: "20" })),
      el("div", { class: "pf-actions span-2" }, btn("설정 저장", {
        "data-act": "pf-attend-cmd",
        "data-cmd": "settings",
        "data-course": String(courseId),
        "data-version": String(data.version),
      }, "primary")),
      el("div", { class: "span-2" }, statusLine("attend-settings")),
    );
    // 입력 중이던 값과 안내는 초안에서 되살리고, 409 뒤에는 서버의 최신 값을 옆에 보인다.
    const draft = bindDraft(formDrafts, settingsDraftKey(courseId), form, data.version);
    if (draft) restoreLine(form, "attend-settings", draft.note);
    if (draft && draft.conflict) {
      form.append(el("p", { class: "pf-sub span-2", text: `서버의 최신 값 · 버전 ${data.version}: 지각 기준 ${data.lateAfterMinutes}분 · 진행 시간 ${data.roundMinutes}분 · ${data.latesPerAbsence ? `지각 ${data.latesPerAbsence}번을 결석 1번으로` : "지각 환산 안 함"}` }));
    }
    body.replaceChildren(form);
  };
  paint(attendanceSettings.peek(key));
  attached(sheet, () => {
    attendanceSettings.watch(sheet, key, paint, null);
    if (freshVisit("pf-attend-settings", key) === "entry" || !attendanceSettings.peek(key)) load();
  });
  return sheet;
}

// ---------- 일반: 출석부 ----------

function cellControl(courseId, student, session, cell) {
  const status = cell ? cell.status : "unmarked";
  const label = `${student.name || student.studentNo || student.userId} ${sessionName(session)}`;
  if (session.cancelled) return el("span", { class: "pf-meta", text: "휴강" });
  const select = el("select", {
    class: `pf-attend-select pf-attend-${statusTone(status)}`,
    "aria-label": label,
    "data-cell": `${student.userId}:${session.sessionId}`,
  },
  status === "unmarked" ? el("option", { value: "unmarked", text: STATUS.unmarked, selected: true, disabled: true }) : null,
  ...DECIDABLE.map((value) => el("option", { value, text: STATUS[value], selected: value === status ? true : null })));
  select.title = cell && cell.source ? `${SOURCE[cell.source] || cell.source}${cell.decidedAt ? ` · ${koDateTime(cell.decidedAt)}` : ""}` : "";
  select.addEventListener("change", () => void changeCell(select, courseId, student, session, cell));
  return select;
}

async function changeCell(select, courseId, student, session, cell) {
  const next = select.value;
  const before = cell ? cell.status : "unmarked";
  const reason = reasonDraft.trim();
  const label = `${student.name || student.studentNo} ${sessionName(session)}`;
  if (!reason) {
    select.value = before;
    say("roster", "바꾸는 사유를 먼저 입력하세요. 사유는 변경 이력에 남습니다.", true);
    const box = document.querySelector("[data-attend-reason]");
    if (box) box.focus();
    return;
  }
  if (select.disabled) return;
  select.disabled = true;
  select.setAttribute("aria-busy", "true");
  say("roster", `${label}: 저장하는 중입니다.`, false);
  try {
    const saved = await setRecord(courseId, session.sessionId, student.userId, { status: next, reason, version: cell ? cell.version : 0 });
    say("roster", `${label}: ${STATUS[saved.status] || saved.status}(으)로 저장했습니다.`, false);
  } catch (error) {
    select.value = before;
    say("roster", `${label}: ${attendanceMessage(error)}`, true);
    if (error instanceof ApiError && (error.status === 409 || error.status === 404 || error.status === 428)) {
      const params = new URLSearchParams(location.search);
      loadRoster(courseId, positive(params.get("page")) || 1, rosterSort(params.get("sort")));
    }
  } finally {
    if (select.isConnected) {
      select.disabled = false;
      select.removeAttribute("aria-busy");
      select.focus();
    }
  }
}

function summaryText(summary) {
  if (!summary) return "—";
  return `출석 ${summary.present} · 지각 ${summary.late} · 결석 ${summary.absent} · 유고 ${summary.excused}${summary.absenceEquivalent !== summary.absent ? ` · 환산 결석 ${summary.absenceEquivalent}` : ""}`;
}

function rosterSheet(ctx, course, courseId) {
  const page = positive(param(ctx, "page")) || 1;
  const sort = rosterSort(param(ctx, "sort"));
  const key = rosterKey(courseId, page, sort);
  const load = () => loadRoster(courseId, page, sort);
  const body = el("div", { class: "stack" });
  const reason = el("input", { type: "text", maxlength: "200", value: reasonDraft, "data-attend-reason": "", placeholder: "예: 병원 진료 확인서 제출" });
  reason.addEventListener("input", () => { reasonDraft = reason.value; });
  const sortLinks = el("div", { class: "filters", role: "group", "aria-label": "정렬" }, ...ROSTER_SORTS.map(([value, label]) => go(
    pf("pf_attend", { kj: course.id, ...(value !== "studentNo" ? { sort: value } : {}) }),
    label,
    { class: `chip all${sort === value ? " on" : ""}`, "aria-current": sort === value ? "true" : null },
  )));
  const sheet = el("section", { class: "sheet", "aria-label": "출석부" },
    el("div", { class: "pf-week-head" }, el("h2", { text: "출석부" }), sortLinks),
    el("div", { class: "pf-form" }, el("div", { class: "span-2" }, field("바꾸는 사유 (칸을 바꿀 때 함께 남습니다)", reason))),
    statusLine("roster"),
    body,
  );
  let drawn = "";
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      drawn = "";
      if (!entry || !entry.error) body.replaceChildren(loadingState("출석부를 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("출석부를 볼 수 없습니다", "이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("출석부를 불러오지 못했습니다", attendanceMessage(entry.error), retry(load)));
      return;
    }
    const sig = JSON.stringify(data);
    if (sig === drawn) return;
    drawn = sig;
    const sessions = Array.isArray(data.sessions) ? data.sessions : [];
    const students = Array.isArray(data.students) ? data.students : [];
    const pages = Math.max(1, Math.ceil(Number(data.total) / (Number(data.size) || ROSTER_PAGE)));
    const pager = pagination({ page, pages, hrefForPage: (n) => pf("pf_attend", { kj: course.id, ...(sort !== "studentNo" ? { sort } : {}), ...(n > 1 ? { page: String(n) } : {}) }) });
    if (!sessions.length) {
      body.replaceChildren(emptyState("등록된 차시가 없습니다", "휴강·보강 탭에서 차시를 먼저 만드세요.", go(pf("pf_attend_makeup", { kj: course.id }), "차시 만들기", { class: "btn btn-secondary" })));
      return;
    }
    if (!students.length) {
      body.replaceChildren(page > 1 && data.total > 0
        ? emptyState("이 쪽에는 수강생이 없습니다", "", go(pf("pf_attend", { kj: course.id, ...(sort !== "studentNo" ? { sort } : {}) }), "첫 쪽으로", { class: "btn btn-secondary" }))
        : emptyState("수강생이 없습니다", "이 과목에 등록된 수강생이 없습니다."));
      return;
    }
    const headers = ["학번", "이름", ...sessions.map((session) => `${sessionName(session)}${session.cancelled ? " (휴강)" : ""}`), "요약"];
    const rows = students.map((student) => {
      const cells = new Map((student.cells || []).map((cell) => [cell.sessionId, cell]));
      return el("tr", {},
        el("td", { "data-label": "학번", text: student.studentNo || "—" }),
        el("td", { "data-label": "이름", class: "pf-lock", text: student.name || "—" }),
        ...sessions.map((session, index) => el("td", { "data-label": headers[index + 2] }, cellControl(courseId, student, session, cells.get(session.sessionId)))),
        el("td", { "data-label": "요약", text: summaryText(student.summary) }),
      );
    });
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: attendanceMessage(entry.error) }) : "",
      el("p", { class: "pf-meta", text: `수강생 ${data.total}명 · ${page}/${pages}쪽 · ${ROSTER_SORTS.find(([value]) => value === data.sort)?.[1] || "학번순"}` }),
      el("div", { class: "table-wrap table-scroll pf-wide pf-attend" },
        el("table", { class: "grid", "aria-label": "출석부" },
          el("thead", {}, el("tr", {}, ...headers.map((label) => el("th", { scope: "col", text: label })))),
          el("tbody", {}, ...rows))),
      pager || "",
      fillForm(courseId, sessions),
    );
  };
  paint(rosters.peek(key));
  attached(sheet, () => {
    rosters.watch(sheet, key, paint, load);
    if (freshVisit("pf-attend-roster", key) === "entry" || !rosters.peek(key)) load();
  });
  return sheet;
}

function fillForm(courseId, sessions) {
  const open = sessions.filter((session) => !session.cancelled);
  if (!open.length) return "";
  return el("div", { class: "pf-form pf-attend-fill", "data-attend-fill": "" },
    el("h3", { class: "span-2", text: "판정 전 칸 한 번에 채우기" }),
    field("차시", el("select", { "data-field": "session" }, ...open.map((session) => el("option", { value: String(session.sessionId), text: sessionName(session) })))),
    field("상태", el("select", { "data-field": "status" }, ...DECIDABLE.map((value) => el("option", { value, text: STATUS[value], selected: value === "absent" ? true : null })))),
    el("p", { class: "pf-sub span-2", text: "미처리인 칸만 채웁니다. 이미 판정한 칸은 그대로 두고, 위의 사유를 함께 남깁니다." }),
    el("div", { class: "pf-actions span-2" }, btn("채우기", { "data-act": "pf-attend-cmd", "data-cmd": "fill", "data-course": String(courseId) }, "secondary")),
  );
}

/** 일반 탭(`pf_attend`) 본문. */
export function serverAttendGeneral(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  return stack(backendPanel(), settingsSheet(courseId), rosterSheet(ctx, course, courseId));
}

// ---------- 스마트 ----------

const loops = new Map(); // roundKey -> { node }

/** 회차 화면을 다시 읽는다. 실시간 수는 `pollAfterSec`마다, QR은 그보다 이르면 서버가 준 교체 시각에. 진행이 끝나면 멈춘다. */
function pollRound(node, courseId, roundId) {
  const key = roundKey(courseId, roundId);
  const running = loops.get(key);
  if (running) {
    running.node = node;
    return;
  }
  const loop = { node };
  loops.set(key, loop);
  const next = (data) => {
    if (!data || data.state !== "open") {
      loops.delete(key);
      return;
    }
    // PIN 회차는 교체가 없어 `pollAfterSec`만 따른다. QR은 교체 시각이 더 이르면 그때 읽는다.
    const gap = data.method === "qr" && data.qrRefreshAt ? serverGap(data.qrRefreshAt, data.serverTime) : null;
    const wait = gap != null && gap > 0 ? Math.min(gap + 250, pollDelay(data)) : pollDelay(data);
    setTimeout(tick, Math.max(1000, wait));
  };
  const tick = async () => {
    if (!loop.node.isConnected) {
      loops.delete(key);
      return;
    }
    if (document.visibilityState === "hidden") {
      setTimeout(tick, 3000);
      return;
    }
    await roundViews.load(key, courseId, roundId);
    const entry = roundViews.peek(key);
    if (entry && entry.error && !entry.data) {
      loops.delete(key);
      return;
    }
    next(entry && entry.data);
  };
  next((roundViews.peek(key) || {}).data || { state: "open", pollAfterSec: 0 });
}

function countsView(counts) {
  if (!counts) return null;
  return el("div", { class: "stat-row cols-3 pf-smart-counts" },
    ...[["대상", counts.targets], ["확정", counts.confirmed], ["출석", counts.present], ["지각", counts.late], ["틀린 제출", counts.failedAttempts], ["잠긴 학생", counts.lockedStudents]]
      .map(([label, value]) => el("div", { class: "stat pf-stat" }, el("span", { class: "tiny", text: label }), el("b", { text: String(value ?? 0) }))));
}

function clock(value) {
  const match = /T(\d{2}):(\d{2}):(\d{2})/.exec(String(value || ""));
  return match ? `${match[1]}:${match[2]}:${match[3]}` : "";
}

function roundPanel(course, courseId, roundId, sessions) {
  const key = roundKey(courseId, roundId);
  const load = () => roundViews.load(key, courseId, roundId);
  const body = el("div", { class: "stack", "aria-live": "polite" });
  const sheet = el("section", { class: "sheet pf-smart-round", "data-smart-round": String(roundId), "aria-label": "스마트출석 회차" }, body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("회차를 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("회차를 찾을 수 없습니다", "이 과목의 회차가 아닙니다.", go(pf("pf_attend_smart", { kj: course.id }), "회차 목록", { class: "btn btn-secondary" })));
      else body.replaceChildren(errorState("회차를 불러오지 못했습니다", attendanceMessage(entry.error), retry(load)));
      return;
    }
    const session = sessions.find((item) => item.sessionId === data.sessionId);
    const open = data.state === "open";
    const proof = [];
    if (open && data.method === "pin" && data.pin) {
      proof.push(
        el("p", { class: "pf-smart-pin", "aria-label": `PIN ${data.pin.split("").join(" ")}`, text: data.pin }),
        el("p", { class: "pf-sub", text: "PIN은 이 회차가 끝날 때까지 바뀌지 않습니다." }),
      );
    }
    if (open && data.method === "qr" && data.qrToken) {
      const svg = qrSvg(data.qrToken, { label: "스마트출석 QR 코드" });
      proof.push(
        svg ? el("div", { class: "pf-smart-qr" }, svg) : el("p", { class: "err", text: "QR 코드를 그리지 못했습니다. 아래 문자열을 보여 주세요." }),
        el("p", { class: "pf-meta pf-smart-token", text: `문자열 ${data.qrToken}` }),
        el("p", { class: "pf-sub", text: `다음 교체 ${clock(data.qrRefreshAt)} (서버 시각). 교체되면 자동으로 새 코드를 보여 줍니다.` }),
      );
    }
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: attendanceMessage(entry.error) }) : "",
      el("div", { class: "pf-week-head" },
        el("h2", { text: `${session ? sessionName(session) : `차시 ${data.sessionId}`} · ${data.method === "qr" ? "QR" : "PIN"}` }),
        badge(ROUND_STATE[data.state] || data.state, open ? "success" : data.state === "cancelled" ? "danger" : "neutral"),
      ),
      note([
        `연 시각 ${koDateTime(data.openedAt)}`,
        `지각 기준 ${clock(data.lateAt)}`,
        `마감 ${clock(data.endsAt)}`,
        data.closedAt ? `닫은 시각 ${koDateTime(data.closedAt)}` : "",
        data.groupId ? "대상: 그룹" : "대상: 수강생 전체",
      ].filter(Boolean).join(" · ")),
      ...proof,
      countsView(data.counts),
      open ? el("div", { class: "pf-actions" },
        btn("마감", { "data-act": "pf-attend-cmd", "data-cmd": "round-close", "data-course": String(courseId), "data-round": String(roundId) }, "primary"),
        btn("취소", { "data-act": "pf-attend-cmd", "data-cmd": "round-cancel", "data-course": String(courseId), "data-round": String(roundId) }, "danger"),
      ) : el("div", { class: "pf-actions" }, go(pf("pf_attend_smart", { kj: course.id }), "새 회차 열기", { class: "btn btn-secondary" })),
      open ? note("취소하면 이 회차로 확정된 출석 기록이 지워지고 학생 화면은 \"취소한 출석\"이 됩니다.") : null,
      statusLine("round"),
    );
    if (open) pollRound(sheet, courseId, roundId);
  };
  paint(roundViews.peek(key));
  attached(sheet, () => {
    roundViews.watch(sheet, key, paint, load);
    if (freshVisit("pf-smart-round", key) === "entry" || !roundViews.peek(key)) load();
  });
  return sheet;
}

function openForm(course, courseId, sessions, data) {
  const open = sessions.filter((session) => !session.cancelled);
  if (!open.length) return el("section", { class: "sheet" }, el("h2", { text: "회차 열기" }), emptyState("출석을 받을 차시가 없습니다", "휴강·보강 탭에서 차시를 먼저 만드세요.", go(pf("pf_attend_makeup", { kj: course.id }), "차시 만들기", { class: "btn btn-secondary" })));
  const groupSelect = el("select", { "data-field": "groupId" });
  const gkey = groupsKey(courseId);
  const fillGroups = (entry) => {
    const chosen = groupSelect.value;
    const groups = entry && Array.isArray(entry.data) ? entry.data : [];
    groupSelect.replaceChildren(el("option", { value: "", text: "수강생 전체" }),
      ...groups.map((group) => el("option", { value: String(group.groupId), text: `${group.name} (${(group.members || []).length}명)`, selected: String(group.groupId) === chosen ? true : null })));
  };
  fillGroups(groupLists.peek(gkey));
  attached(groupSelect, () => {
    groupLists.watch(groupSelect, gkey, fillGroups, null);
    if (!groupLists.peek(gkey)) groupLists.load(gkey, courseId);
  });
  const settings = data.settings || {};
  return el("section", { class: "sheet", "aria-label": "스마트출석 회차 열기" },
    el("h2", { text: "회차 열기" }),
    el("div", { class: "pf-form", "data-smart-open": "" },
      field("차시", el("select", { "data-field": "session" }, ...open.map((session) => el("option", { value: String(session.sessionId), text: `${sessionName(session)}${session.classDate ? ` · ${koDay(session.classDate)}` : ""}` })))),
      field("방식", el("select", { "data-field": "method" }, el("option", { value: "pin", text: "PIN (숫자)" }), el("option", { value: "qr", text: "QR (15초마다 바뀜)" }))),
      field(`진행 시간 (분, 비우면 ${settings.roundMinutes ?? "과목 설정"})`, el("input", { type: "number", min: "1", max: "240", "data-field": "minutes" })),
      field(`지각 기준 (분, 비우면 ${settings.lateAfterMinutes ?? "과목 설정"})`, el("input", { type: "number", min: "1", max: "240", "data-field": "lateAfterMinutes" })),
      el("div", { class: "span-2" }, field("대상", groupSelect)),
      el("div", { class: "pf-actions span-2" }, btn("회차 열기", { "data-act": "pf-attend-cmd", "data-cmd": "round-open", "data-course": String(courseId), "data-kj": course.id }, "primary")),
      el("div", { class: "span-2" }, statusLine("round-open")),
    ),
  );
}

function activeList(course, courseId, sessions) {
  const key = attendanceKey(courseId);
  const list = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "진행 중인 회차" }, el("h2", { text: "진행 중인 회차" }), list);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      list.replaceChildren(!entry || !entry.error ? note("진행 중인 회차를 확인하는 중입니다.") : el("p", { class: "err", role: "alert", text: attendanceMessage(entry.error) }));
      return;
    }
    const rounds = Array.isArray(data.rounds) ? data.rounds : [];
    list.replaceChildren(...(rounds.length ? rounds.map((round) => {
      const session = sessions.find((item) => item.sessionId === round.sessionId);
      const href = pf("pf_attend_smart", { kj: course.id, round: String(round.roundId) });
      return el("div", { class: "pf-activity" },
        el("a", { class: "pf-activity-main", href, "data-go": href },
          badge(round.method === "qr" ? "QR" : "PIN", "success"),
          el("span", { class: "pf-activity-copy" },
            el("b", { text: session ? sessionName(session) : `차시 ${round.sessionId}` }),
            el("span", { class: "pf-meta", text: `마감 ${clock(round.endsAt)} · 확정 ${round.counts ? round.counts.confirmed : 0}/${round.counts ? round.counts.targets : 0}` }),
          ),
        ),
      );
    }) : [note("진행 중인 회차가 없습니다.")]));
  };
  paint(activeRounds.peek(key));
  attached(section, () => {
    activeRounds.watch(section, key, paint, () => activeRounds.load(key, courseId));
    if (freshVisit("pf-smart-active", key) === "entry" || !activeRounds.peek(key)) activeRounds.load(key, courseId);
  });
  return section;
}

/** 스마트 탭(`pf_attend_smart`) 본문. `?round=`가 있으면 그 회차 화면이다. */
export function serverAttendSmart(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const roundId = positive(param(ctx, "round"));
  const slot = el("div", { class: "stack" });
  let drawn = "";
  withSessions(courseId, slot, (data, error) => {
    const sig = JSON.stringify([data.weeks, data.settings, error && error.message]);
    if (sig === drawn) return;
    drawn = sig;
    const sessions = allSessions(data);
    // 회차 화면은 다시 읽을 때마다(QR 교체, 실시간 수) 스스로 다시 그리므로, 차시 목록이 다시 와도 한 번 만든 노드를 그대로 쓴다.
    if (roundId && slot.firstElementChild && slot.firstElementChild.dataset.smartRound === String(roundId)) return;
    slot.replaceChildren(
      error ? el("p", { class: "err", role: "alert", text: attendanceMessage(error) }) : "",
      ...(roundId ? [roundPanel(course, courseId, roundId, sessions)] : [activeList(course, courseId, sessions), openForm(course, courseId, sessions, data)]),
    );
  }, "pf-attend-smart");
  return stack(backendPanel(), slot);
}

// ---------- 휴강·보강(차시) ----------

function sessionForm(course, courseId, sessions, editing, makeupOf) {
  const v = editing || {};
  const input = (name, attrs) => el("input", { "data-field": name, ...attrs });
  const cancelledSessions = sessions.filter((session) => session.cancelled && !session.makeup);
  const makeupSelect = el("select", { "data-field": "makeupOfSessionId" },
    el("option", { value: "", text: "보강이 아님" }),
    ...cancelledSessions.map((session) => el("option", { value: String(session.sessionId), text: `${sessionName(session)} 보강`, selected: makeupOf === session.sessionId ? true : null })));
  const method = el("select", { "data-field": "method" }, ...Object.entries(METHOD).map(([value, label]) => el("option", { value, text: label, selected: (v.method || "manual") === value ? true : null })));
  const cancelled = el("input", { type: "checkbox", "data-field": "cancelled" });
  cancelled.checked = Boolean(v.cancelled);
  const base = makeupOf ? sessions.find((session) => session.sessionId === makeupOf) : null;
  const draftKey = sessionDraftKey(courseId, editing ? editing.sessionId : null, makeupOf);
  const form = el("div", { class: "pf-form", "data-session-form": editing ? String(editing.sessionId) : "new", "data-draft": draftKey },
    field("주차", input("weekNo", { type: "number", min: "1", max: "60", value: String(v.weekNo || (base && base.weekNo) || "") })),
    field("차시 번호", input("sessionNo", { type: "number", min: "1", max: "20", value: String(v.sessionNo || (base && base.sessionNo) || "") })),
    field("수업일", input("classDate", { type: "date", value: v.classDate || "" })),
    field("방식", method),
    field("시작", input("startTime", { type: "time", value: v.startTime ? String(v.startTime).slice(0, 5) : "" })),
    field("끝", input("endTime", { type: "time", value: v.endTime ? String(v.endTime).slice(0, 5) : "" })),
    editing ? el("label", { class: "choice pf-check span-2" }, cancelled, el("span", { text: "휴강" })) : el("div", { class: "span-2" }, field("보강 대상 (휴강한 차시)", makeupSelect)),
    el("div", { class: "pf-actions span-2" },
      btn(editing ? "수정 저장" : "차시 등록", {
        "data-act": "pf-attend-cmd",
        "data-cmd": "session-save",
        "data-course": String(courseId),
        "data-kj": course.id,
        "data-session": editing ? String(editing.sessionId) : null,
        "data-version": editing ? String(editing.version) : null,
      }, "primary"),
      editing || makeupOf ? go(pf("pf_attend_makeup", { kj: course.id }), "새 차시 쓰기", { class: "btn btn-ghost" }) : null,
    ),
    el("div", { class: "span-2" }, statusLine("session")),
  );
  // 입력 중이던 값과 안내는 초안에서 되살리고, 409 뒤에는 서버의 최신 내용을 옆에 보인다.
  const draft = bindDraft(formDrafts, draftKey, form, editing ? editing.version : null);
  if (draft) restoreLine(form, "session", draft.note);
  if (draft && draft.conflict && editing) {
    const when = [koDay(editing.classDate), timeRange(editing)].filter(Boolean).join(" ") || "일시 미정";
    form.append(el("p", { class: "pf-sub span-2", text: `서버의 최신 내용 · 버전 ${editing.version}: ${sessionName(editing)} · ${when} · ${METHOD[editing.method] || editing.method}${editing.cancelled ? " · 휴강" : ""}` }));
  }
  return el("section", { class: "sheet", "aria-label": editing ? "차시 수정" : "차시 등록" },
    el("h2", { text: editing ? `차시 수정 · ${sessionName(editing)}` : makeupOf ? "보강 차시 등록" : "차시 등록" }),
    form,
  );
}

function countsLine(counts) {
  const entries = Object.entries(counts || {}).filter(([, n]) => n);
  return entries.length ? entries.map(([status, n]) => `${STATUS[status] || status} ${n}`).join(" · ") : "기록 없음";
}

function sessionRow(course, courseId, session, sessions) {
  const of = session.makeupOfSessionId ? sessions.find((item) => item.sessionId === session.makeupOfSessionId) : null;
  const time = [koDay(session.classDate), timeRange(session)].filter(Boolean).join(" ");
  const edit = pf("pf_attend_makeup", { kj: course.id, edit: String(session.sessionId) });
  return el("div", { class: "pf-activity", "data-session-row": String(session.sessionId) },
    el("a", { class: "pf-activity-main", href: edit, "data-go": edit },
      badge(session.cancelled ? "휴강" : session.makeup ? "보강" : METHOD[session.method] || session.method, session.cancelled ? "danger" : session.makeup ? "success" : "neutral"),
      el("span", { class: "pf-activity-copy" },
        el("b", { text: sessionName(session) }),
        el("span", { class: "pf-meta", text: [time || "일시 미정", of ? `${sessionName(of)}의 보강` : "", countsLine(session.counts), `버전 ${session.version}`].filter(Boolean).join(" · ") }),
      ),
    ),
    el("div", { class: "pf-activity-actions" },
      go(edit, "수정", { class: "btn btn-ghost", "aria-label": `${sessionName(session)} 수정` }),
      btn(session.cancelled ? "휴강 해제" : "휴강으로", {
        "data-act": "pf-attend-cmd",
        "data-cmd": "session-cancel",
        "data-course": String(courseId),
        "data-session": String(session.sessionId),
        "data-next": session.cancelled ? "0" : "1",
        "aria-label": `${sessionName(session)} ${session.cancelled ? "휴강 해제" : "휴강으로 바꾸기"}`,
      }, "ghost"),
      session.cancelled && !session.makeup ? go(pf("pf_attend_makeup", { kj: course.id, makeup: String(session.sessionId) }), "보강 등록", { class: "btn btn-ghost" }) : null,
      btn("삭제", {
        "data-act": "pf-attend-cmd",
        "data-cmd": "session-delete",
        "data-course": String(courseId),
        "data-session": String(session.sessionId),
        "data-version": String(session.version),
        "aria-label": `${sessionName(session)} 삭제`,
      }, "ghost"),
    ),
  );
}

/** 휴강·보강 탭(`pf_attend_makeup`) 본문. `?edit=`는 수정, `?makeup=`은 그 휴강 차시의 보강 등록이다. */
export function serverAttendSessions(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const editId = positive(param(ctx, "edit"));
  const makeupOf = positive(param(ctx, "makeup"));
  const slot = el("div", { class: "stack" });
  let drawn = "";
  withSessions(courseId, slot, (data, error) => {
    // serverTime은 조회마다 바뀐다. 내용이 같으면 다시 그리지 않아 상태 줄과 입력 중인 폼이 남는다.
    const sig = JSON.stringify([data.weeks, data.settings, error && error.message]);
    if (sig === drawn) return;
    drawn = sig;
    const sessions = allSessions(data);
    const editing = editId ? sessions.find((session) => session.sessionId === editId) : null;
    const section = el("section", { class: "sheet", "aria-label": "차시 목록" },
      el("h2", { text: "차시" }),
      statusLine("sessions"),
      sessions.length ? el("div", { class: "stack" }, ...sessions.map((session) => sessionRow(course, courseId, session, sessions))) : note("아직 등록된 차시가 없습니다."),
    );
    restoreLine(section, "sessions", listNotices.get(String(courseId)));
    slot.replaceChildren(
      error ? el("p", { class: "err", role: "alert", text: attendanceMessage(error) }) : "",
      editId && !editing ? note("고친 차시를 찾지 못했습니다. 삭제되었을 수 있습니다.") : "",
      sessionForm(course, courseId, sessions, editing, editing ? null : makeupOf),
      section,
    );
  }, "pf-attend-sessions");
  return stack(backendPanel(), slot);
}

// ---------- 명령 ----------

async function withBusy(node, label, work) {
  const text = node.textContent;
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = label;
  try {
    return await work();
  } finally {
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = text;
    }
  }
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

function cachedSession(courseId, sessionId) {
  const entry = attendanceViews.peek(attendanceKey(courseId));
  return allSessions(entry && entry.data).find((session) => session.sessionId === sessionId) || null;
}

const intOrNull = (value) => {
  const text = String(value == null ? "" : value).trim();
  if (!text) return null;
  const n = Number(text);
  return Number.isInteger(n) ? n : Number.NaN;
};

function readSession(form) {
  const get = (name) => form.querySelector(`[data-field="${name}"]`);
  const weekNo = intOrNull(get("weekNo").value);
  const sessionNo = intOrNull(get("sessionNo").value);
  const startTime = get("startTime").value || null;
  const endTime = get("endTime").value || null;
  const errors = [];
  if (!weekNo || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
  if (!sessionNo || sessionNo < 1 || sessionNo > 20) errors.push("차시 번호는 1부터 20 사이입니다");
  if (startTime && endTime && startTime >= endTime) errors.push("시작은 끝보다 앞이어야 합니다");
  const makeup = get("makeupOfSessionId");
  const cancelled = get("cancelled");
  return {
    errors,
    body: {
      weekNo,
      sessionNo,
      classDate: get("classDate").value || null,
      startTime,
      endTime,
      method: get("method").value,
      ...(makeup ? { makeupOfSessionId: makeup.value ? Number(makeup.value) : null } : {}),
      ...(cancelled ? { cancelled: cancelled.checked } : {}),
    },
  };
}

/** `pf-attend-cmd`: settings, fill, round-open, round-close, round-cancel(두 번 눌러 확인), session-save, session-cancel, session-delete(두 번 눌러 확인). */
export async function attendCommand(node, api) {
  if (node.disabled) return undefined;
  const courseId = Number(node.dataset.course);
  const cmd = node.dataset.cmd;
  const sessionId = Number(node.dataset.session) || null;
  const roundId = Number(node.dataset.round) || null;
  const armed = () => {
    if (node.dataset.armed === "1") {
      node.dataset.armed = "";
      return true;
    }
    node.dataset.armed = "1";
    node.dataset.label = node.textContent;
    node.textContent = "한 번 더 누르면 확정";
    return false;
  };

  if (cmd === "settings") {
    const form = node.closest("[data-attend-settings]");
    const key = attendanceKey(courseId);
    const draftKey = settingsDraftKey(courseId);
    // 누를 때의 값을 초안에 담아 409 재조회로 폼을 다시 그려도 남긴다. 저장에는 초안의 기준 버전을 보낸다.
    const draft = keepDraft(formDrafts, draftKey, form, Number(node.dataset.version));
    const tell = (text, alert) => {
      draft.note = { text, alert };
      say("attend-settings", text, alert);
    };
    const value = (name) => intOrNull(form.querySelector(`[data-field="${name}"]`).value);
    const body = { lateAfterMinutes: value("lateAfterMinutes"), roundMinutes: value("roundMinutes"), latesPerAbsence: value("latesPerAbsence") };
    if (Object.values(body).some((n) => n == null || Number.isNaN(n))) return tell("세 값을 모두 정수로 입력하세요.", true);
    return withBusy(node, "저장하는 중…", async () => {
      try {
        await updateAttendanceSettings(courseId, { ...body, version: draft.version });
        if (formDrafts.get(draftKey) === draft) formDrafts.delete(draftKey);
        // 응답으로 캐시를 고칠 때는 초안이 아직 있었으므로, 지운 뒤 서버 값으로 한 번 더 그린다.
        attendanceSettings.patch((k) => k === key, () => {});
        toast(api, "출석 설정을 저장했습니다");
      } catch (error) {
        const conflict = error instanceof ApiError && error.status === 409;
        const stale = conflict && error.code === "version_conflict";
        if (stale) draft.conflict = true;
        tell(attendanceMessage(error), true);
        if (!conflict) return;
        // 입력은 두고 버전만 새로 받는다. 다시 읽지 못하면 기준 버전을 그대로 두어 다음 저장도 충돌로 알린다.
        await attendanceSettings.load(key, courseId);
        const entry = attendanceSettings.peek(key);
        if (stale && entry && entry.data && !entry.error && formDrafts.get(draftKey) === draft) draft.version = entry.data.version;
      }
    });
  }

  if (cmd === "fill") {
    const form = node.closest("[data-attend-fill]");
    const reason = reasonDraft.trim();
    if (!reason) return say("roster", "채우는 사유를 위의 사유 칸에 먼저 입력하세요.", true);
    const target = Number(form.querySelector('[data-field="session"]').value);
    const status = form.querySelector('[data-field="status"]').value;
    return withBusy(node, "채우는 중…", async () => {
      try {
        const result = await fillRecords(courseId, target, { status, reason });
        say("roster", `판정 전이던 ${result.updated}명을 ${STATUS[status]}(으)로 채웠습니다.`, false);
      } catch (error) {
        say("roster", attendanceMessage(error), true);
      }
    });
  }

  if (cmd === "round-open") {
    const form = node.closest("[data-smart-open]");
    const get = (name) => form.querySelector(`[data-field="${name}"]`).value;
    const minutes = intOrNull(get("minutes"));
    const lateAfterMinutes = intOrNull(get("lateAfterMinutes"));
    if (Number.isNaN(minutes) || Number.isNaN(lateAfterMinutes)) return say("round-open", "진행 시간과 지각 기준은 분 단위 정수입니다.", true);
    if (minutes != null && lateAfterMinutes != null && lateAfterMinutes > minutes) return say("round-open", "지각 기준은 진행 시간보다 길 수 없습니다.", true);
    return withBusy(node, "여는 중…", async () => {
      try {
        const round = await openRound(courseId, Number(get("session")), {
          method: get("method"),
          minutes,
          lateAfterMinutes,
          groupId: get("groupId") ? Number(get("groupId")) : null,
        });
        roundViews.load(roundKey(courseId, round.roundId), courseId, round.roundId);
        toast(api, "스마트출석을 열었습니다");
        api.navigate(pf("pf_attend_smart", { kj: node.dataset.kj, round: String(round.roundId) }));
      } catch (error) {
        say("round-open", attendanceMessage(error), true);
      }
    });
  }

  if (cmd === "round-close" || cmd === "round-cancel") {
    if (cmd === "round-cancel" && !armed()) return undefined;
    return withBusy(node, cmd === "round-close" ? "마감하는 중…" : "취소하는 중…", async () => {
      try {
        await finishRound(courseId, roundId, cmd === "round-close" ? "close" : "cancel");
        toast(api, cmd === "round-close" ? "스마트출석을 마감했습니다" : "스마트출석을 취소했습니다. 이 회차로 남은 기록을 지웠습니다");
      } catch (error) {
        say("round", attendanceMessage(error), true);
        roundViews.load(roundKey(courseId, roundId), courseId, roundId);
      }
    }).finally(() => {
      if (node.isConnected && node.dataset.label) node.textContent = node.dataset.label;
    });
  }

  if (cmd === "session-save") {
    const form = node.closest("[data-session-form]");
    const draftKey = form.dataset.draft;
    // 누를 때의 값을 초안에 담아 409 재조회로 폼을 다시 그려도 남긴다. 수정은 초안의 기준 버전을 보낸다.
    const draft = keepDraft(formDrafts, draftKey, form, sessionId ? Number(node.dataset.version) : null);
    const tell = (text, alert) => {
      draft.note = { text, alert };
      say("session", text, alert);
    };
    const { errors, body } = readSession(form);
    if (errors.length) return tell(errors.join(". "), true);
    return withBusy(node, "저장하는 중…", async () => {
      try {
        if (sessionId) await updateSession(courseId, sessionId, { ...body, version: draft.version });
        else await createSession(courseId, body);
        if (formDrafts.get(draftKey) === draft) formDrafts.delete(draftKey);
        toast(api, sessionId ? "차시를 수정했습니다" : "차시를 등록했습니다");
        api.navigate(pf("pf_attend_makeup", { kj: node.dataset.kj }));
      } catch (error) {
        const status = error instanceof ApiError ? error.status : 0;
        const stale = status === 409 && Boolean(sessionId) && error.code === "version_conflict";
        if (stale) draft.conflict = true;
        // 지워진 차시의 수정은 다시 보낼 곳이 없어 초안을 버린다.
        if (status === 404 && sessionId) formDrafts.delete(draftKey);
        tell(attendanceMessage(error), true);
        if (status !== 409 && status !== 404) return;
        // 입력은 두고 버전만 새로 받는다. 다시 읽지 못하면 기준 버전을 그대로 두어 다음 저장도 충돌로 알린다.
        await attendanceViews.load(attendanceKey(courseId), courseId);
        const fresh = stale && cachedSession(courseId, sessionId);
        const entry = attendanceViews.peek(attendanceKey(courseId));
        if (fresh && entry && !entry.error && formDrafts.get(draftKey) === draft) draft.version = fresh.version;
      }
    });
  }

  // 차시 목록의 안내는 409 재조회로 목록을 다시 그려도 남고, 다음에 성공하면 지운다.
  const sayList = (text, alert) => {
    listNotices.set(String(courseId), { text, alert });
    say("sessions", text, alert);
  };

  if (cmd === "session-cancel") {
    const session = cachedSession(courseId, sessionId);
    if (!session) return sayList("차시를 찾지 못했습니다. 목록을 새로 불러오세요.", true);
    return withBusy(node, "바꾸는 중…", async () => {
      try {
        await updateSession(courseId, sessionId, { ...session, cancelled: node.dataset.next === "1" });
        listNotices.delete(String(courseId));
        toast(api, node.dataset.next === "1" ? "휴강으로 바꿨습니다" : "휴강을 해제했습니다");
      } catch (error) {
        sayList(`${sessionName(session)}: ${attendanceMessage(error)}`, true);
        if (error instanceof ApiError && (error.status === 409 || error.status === 404)) attendanceViews.load(attendanceKey(courseId), courseId);
      }
    });
  }

  if (cmd === "session-delete") {
    if (!armed()) return undefined;
    const session = cachedSession(courseId, sessionId);
    return withBusy(node, "삭제하는 중…", async () => {
      try {
        await deleteSession(courseId, sessionId, Number(node.dataset.version));
        listNotices.delete(String(courseId));
        formDrafts.delete(sessionDraftKey(courseId, sessionId));
        toast(api, "차시를 삭제했습니다");
      } catch (error) {
        sayList(`${session ? sessionName(session) : "차시"}: ${attendanceMessage(error)}`, true);
        if (error instanceof ApiError && (error.status === 409 || error.status === 404)) attendanceViews.load(attendanceKey(courseId), courseId);
      }
    }).finally(() => {
      if (node.isConnected && node.dataset.label) node.textContent = node.dataset.label;
    });
  }
  return undefined;
}
