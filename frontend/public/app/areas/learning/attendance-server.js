// 학생 출석 화면(41, 41b)의 서버 버전. 차시마다 자기 기록과 과목 전체 요약은 `GET …/attendance`에서, 진행 중인 스마트출석은
// `GET …/smart-rounds/active`에서 받는다. 응답의 `pollAfterSec`마다 다시 묻고, 진행 목록에서 빠진 회차는 `…/me`로 마지막 상태
// (마감, 만료, 취소)를 한 번 더 읽는다. "출석했습니다."는 상태가 `confirmed`이고 서버 기록(`attendance`)이 있을 때만 쓴다.
// 제출은 `data-act="srv-attend-cmd"`(learning/actions.js)로 온다. 입력 중인 PIN·QR token은 다시 그려도 남게 메모리에만 둔다.
import { el } from "../../dom.js";
import { btn, emptyState, loadingState, errorState, field } from "../../ui/components.js";
import { courseById } from "../../data.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import {
  attendanceViews, activeRounds, myRounds, attendanceKey, roundKey, checkRound, pollDelay, serverGap, roundOutcome,
  STATUS, SOURCE, METHOD, ROUND_STATE, statusTone, koDay, timeRange, attendanceMessage,
} from "../../shared/attendance-remote.js";

const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 출석과 스마트출석을 서버에서 불러옵니다.";

// 회차별 입력값(PIN 또는 QR token)과, 이 세션에서 본 회차(진행 목록에서 빠지면 마지막 상태를 읽는다).
const typed = new Map(); // roundKey -> text
const seen = new Map(); // courseId -> Map(roundId -> { state, settled })
const notes = new Map(); // roundKey -> { text, alert }
subscribe(() => {
  typed.clear();
  seen.clear();
  notes.clear();
});

const missing = (error) => error instanceof ApiError && error.status === 404;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function courseOf(ctx) {
  const kj = ctx.params && ctx.params.get("kj");
  if (kj && courseById(kj).id === kj) return courseById(kj);
  return courseById(ctx.state.courseId || (ctx.screen && ctx.screen.course));
}

function seenOf(courseId) {
  if (!seen.has(courseId)) seen.set(courseId, new Map());
  return seen.get(courseId);
}

// ---------- 스마트출석 패널 ----------

function recordLine(attendance) {
  if (!attendance) return null;
  const parts = [STATUS[attendance.status] || attendance.status];
  if (attendance.source) parts.push(SOURCE[attendance.source] || attendance.source);
  if (attendance.decidedAt) parts.push(koDateTime(attendance.decidedAt));
  return el("p", { class: "tiny", text: `서버 기록: ${parts.join(" · ")}` });
}

function roundCard(courseId, view, sessionLabel) {
  const key = roundKey(courseId, view.roundId);
  const note = notes.get(key);
  const head = el("header", { class: "att-card-h" },
    el("h2", { text: `스마트출석 · ${view.method === "qr" ? "QR" : "PIN"}` }),
    el("p", { class: "tiny", text: [sessionLabel, view.endsAt ? `${koDateTime(view.endsAt)}까지` : ""].filter(Boolean).join(" · ") }),
  );
  const card = el("article", { class: "att-card att-smart", "data-round": String(view.roundId), "aria-live": "polite" }, head);
  const state = view.state;
  if (state === "confirmed") {
    if (view.attendance) {
      card.append(
        el("p", { class: `att-smart-result st-${statusTone(view.attendance.status)}`, role: "status", text: "출석했습니다." }),
        recordLine(view.attendance),
      );
    } else {
      // confirmed인데 기록이 없으면 확정을 단정하지 않는다(SEC-06). 다음 조회에서 다시 본다.
      card.append(el("p", { class: "att-smart-result", role: "status", text: "출석 기록을 확인하고 있습니다." }));
    }
    return card;
  }
  if (state !== "pending") {
    // 잠김(진행 중)·잠긴 채 마감·제출 없이 마감은 서버의 `failedAttempts`·`lockedOut`으로 가른다.
    const closedLocked = (state === "expired" || state === "closed") && view.lockedOut;
    card.append(el("p", { class: `att-smart-result${state === "locked" || closedLocked ? " st-absent" : ""}`, role: "status", text: ROUND_STATE[state] || state }));
    const outcome = roundOutcome(view);
    if (outcome) card.append(el("p", { class: "tiny", "data-round-outcome": state === "locked" ? "locked" : closedLocked ? "closed-locked" : (Number(view.failedAttempts) || 0) === 0 ? "never-submitted" : "closed-failed", text: outcome }));
    if (state === "cancelled") card.append(el("p", { class: "tiny", text: "담당 교수가 이 출석을 취소해 이 회차로 남은 기록이 지워졌습니다." }));
    return card;
  }
  const pin = view.method !== "qr";
  const value = typed.get(key) || "";
  const input = el("input", {
    type: "text",
    value,
    autocomplete: "off",
    "data-round-input": key,
    ...(pin
      ? { inputmode: "numeric", pattern: "[0-9]*", maxlength: String(view.pinLength || 8), placeholder: `${view.pinLength || 6}자리 숫자` }
      : { maxlength: "40", placeholder: "QR 코드의 문자열", spellcheck: "false" }),
  });
  input.addEventListener("input", () => typed.set(key, input.value.trim()));
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    const send = card.querySelector("[data-act=srv-attend-cmd]");
    if (send) send.click();
  });
  const left = view.serverTime && view.endsAt ? serverGap(view.endsAt, view.serverTime) : null;
  card.append(
    field(pin ? "담당 교수가 보여 준 PIN" : "QR 코드를 읽은 문자열", input),
    el("p", { class: "tiny", text: [
      view.failuresLeft != null ? `남은 제출 기회 ${view.failuresLeft}번` : "",
      Number(view.failedAttempts) > 0 ? `틀린 제출 ${view.failedAttempts}번` : "",
      left != null && left > 0 ? `약 ${Math.max(1, Math.ceil(left / 60000))}분 남음` : "",
      pin ? "" : "QR 코드는 15초마다 바뀝니다. 휴대폰으로 읽은 값을 바로 붙여 넣으세요.",
    ].filter(Boolean).join(" · ") }),
    el("div", { class: "btn-row" }, btn("출석하기", {
      "data-act": "srv-attend-cmd",
      "data-cmd": "check",
      "data-course": String(courseId),
      "data-round": String(view.roundId),
      "data-method": pin ? "pin" : "qr",
    }, "primary")),
    el("p", { class: note && note.alert ? "err" : "tiny", role: note && note.alert ? "alert" : "status", "data-round-note": key, text: note ? note.text : "" }),
  );
  return card;
}

function smartPanel(courseId, sessionsById) {
  const slot = el("div", { class: "att-smart-list" });
  const known = seenOf(courseId);
  // 조회마다 다시 그리면 입력 중인 칸의 포커스가 사라진다. 상태가 같은 회차는 그린 카드를 그대로 둔다.
  const drawn = new Map(); // roundId -> { sig, node }
  const cardFor = (view) => {
    const key = roundKey(courseId, view.roundId);
    const sig = JSON.stringify([view.state, view.failuresLeft, view.failedAttempts, view.lockedOut, view.attendance, notes.get(key), sessionsById.get(view.sessionId)]);
    const last = drawn.get(view.roundId);
    if (last && last.sig === sig) return last.node;
    const node = roundCard(courseId, view, sessionsById.get(view.sessionId));
    drawn.set(view.roundId, { sig, node });
    return node;
  };
  const paint = () => {
    const entry = activeRounds.peek(attendanceKey(courseId));
    const data = entry && entry.data;
    if (!data) {
      if (entry && entry.loading) return;
      slot.replaceChildren(entry && entry.error && !missing(entry.error)
        ? el("p", { class: "err", role: "alert", text: `스마트출석을 확인하지 못했습니다. ${attendanceMessage(entry.error)}` })
        : "");
      return;
    }
    const live = Array.isArray(data.mine) ? data.mine : [];
    const cards = [];
    const ids = new Set();
    for (const view of live) {
      ids.add(view.roundId);
      known.set(view.roundId, { state: view.state, settled: false });
      const mine = myRounds.peek(roundKey(courseId, view.roundId));
      // 제출 직후의 응답이 조회보다 새로울 수 있다. 같은 회차면 확정 쪽을 쓴다.
      const shown = mine && mine.data && mine.data.state === "confirmed" && view.state === "pending" ? mine.data : view;
      cards.push(cardFor(shown));
    }
    for (const [roundId] of known) {
      if (ids.has(roundId)) continue;
      const mine = myRounds.peek(roundKey(courseId, roundId));
      if (mine && mine.data) cards.push(cardFor(mine.data));
    }
    if (cards.length === slot.children.length && cards.every((node, index) => slot.children[index] === node)) return;
    slot.replaceChildren(...cards);
  };
  paint();
  return { slot, paint, known };
}

const loops = new Map(); // courseId -> { node, known, onChange }

/**
 * 응답의 간격마다 진행 목록을 다시 받고, 빠진 회차는 `me`를 한 번 읽는다. 과목마다 조회는 하나이고, 다시 그린 화면은 그 조회에
 * 자기 노드를 넘긴다. 화면이 문서에서 떨어지면 다음 차례에 멈춘다.
 */
function startPolling(node, courseId, known, onChange) {
  const running = loops.get(courseId);
  if (running) {
    Object.assign(running, { node, known, onChange });
    return;
  }
  const loop = { node, known, onChange };
  loops.set(courseId, loop);
  const tick = async () => {
    if (!loop.node.isConnected) {
      loops.delete(courseId);
      return;
    }
    if (typeof document !== "undefined" && document.visibilityState === "hidden") {
      setTimeout(tick, 3000);
      return;
    }
    const key = attendanceKey(courseId);
    const before = JSON.stringify(((activeRounds.peek(key) || {}).data || {}).mine || []);
    await activeRounds.load(key, courseId);
    const entry = activeRounds.peek(key);
    const live = new Set(((entry && entry.data && entry.data.mine) || []).map((view) => view.roundId));
    const settling = [...loop.known].filter(([roundId, info]) => !live.has(roundId) && !info.settled);
    await Promise.all(settling.map(async ([roundId, info]) => {
      await myRounds.load(roundKey(courseId, roundId), courseId, roundId);
      const mine = myRounds.peek(roundKey(courseId, roundId));
      if (mine && mine.data) {
        info.state = mine.data.state;
        info.settled = true;
      } else if (mine && missing(mine.error)) loop.known.delete(roundId);
    }));
    const after = JSON.stringify(((activeRounds.peek(key) || {}).data || {}).mine || []);
    // 회차가 열리거나 끝나면 출석부의 기록도 바뀌었을 수 있다.
    if (before !== after || settling.length) {
      attendanceViews.load(key, courseId);
      if (loop.node.isConnected) loop.onChange();
    }
    setTimeout(tick, pollDelay(entry && entry.data));
  };
  setTimeout(tick, 0);
}

/** `srv-attend-cmd` `check`: PIN 또는 QR token 제출. 같은 버튼을 두 번 눌러도 요청은 하나다. */
export async function attendCommand(node, api) {
  if (node.disabled || node.dataset.cmd !== "check") return;
  const courseId = Number(node.dataset.course);
  const roundId = Number(node.dataset.round);
  const key = roundKey(courseId, roundId);
  const card = node.closest("[data-round]");
  const input = card && card.querySelector("[data-round-input]");
  const value = String((input && input.value) || typed.get(key) || "").trim();
  const pin = node.dataset.method === "pin";
  const say = (text, alert) => {
    notes.set(key, { text, alert });
    const line = card && card.querySelector("[data-round-note]");
    if (line) {
      line.className = alert ? "err" : "tiny";
      line.setAttribute("role", alert ? "alert" : "status");
      line.textContent = text;
    }
  };
  if (pin ? !/^[0-9]{4,8}$/.test(value) : !/^[0-9A-Za-z._-]{1,40}$/.test(value)) {
    say(pin ? "PIN은 숫자로 입력하세요." : "QR 코드에서 읽은 문자열을 입력하세요.", true);
    if (input) input.focus();
    return;
  }
  const label = node.textContent;
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = "확인하는 중…";
  if (input) input.disabled = true;
  try {
    const state = await checkRound(courseId, roundId, pin ? { pin: value } : { qrToken: value });
    typed.delete(key);
    notes.delete(key);
    seenOf(courseId).set(roundId, { state: state.state, settled: false });
    attendanceViews.load(attendanceKey(courseId), courseId);
    activeRounds.load(attendanceKey(courseId), courseId);
    api.getState().toast = state.state === "confirmed" && state.attendance ? "출석했습니다" : ROUND_STATE[state.state] || "제출했습니다";
    api.paintToast();
  } catch (error) {
    say(attendanceMessage(error), true);
    // 잠김·만료·마감·취소·대상 아님은 상태가 바뀐 것이다. 진행 목록을 다시 받아 그 상태를 그린다.
    if (error instanceof ApiError && (error.status === 409 || error.status === 429 || error.status === 422)) {
      activeRounds.load(attendanceKey(courseId), courseId);
    }
  } finally {
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = label;
      if (input) {
        input.disabled = false;
        input.focus();
      }
    }
  }
}

// ---------- 출석부 ----------

function sessionRow(session) {
  const record = session.record || { status: "unmarked" };
  const cancelled = session.cancelled;
  const time = [koDay(session.classDate), timeRange(session)].filter(Boolean).join(" ");
  return el("div", { class: `att-session${cancelled ? " is-cancelled" : ""}` },
    el("span", { class: "att-rail", "aria-hidden": "true" }, el("i")),
    el("span", {},
      el("b", { text: `${session.sessionNo}차시${session.makeup ? " 보강" : ""}` }),
      el("span", { class: "tiny", text: [cancelled ? "휴강" : METHOD[session.method] || session.method, time].filter(Boolean).join(" · ") }),
    ),
    cancelled
      ? el("span", { class: "att-status st-empty", text: "휴강" })
      : el("span", { class: `att-status st-${statusTone(record.status)}`, title: record.source ? SOURCE[record.source] || record.source : "", text: STATUS[record.status] || record.status }),
  );
}

/** 학생 출석 화면(백엔드 모드). `typeLine`은 learning.js의 제목 줄이다. */
export function remoteAttendance(screen, ctx, head) {
  const course = courseOf(ctx);
  const root = el("section", { class: "bleed attend" }, head, el("p", { class: "subhead", text: `${course.ko} · ${course.code}` }));
  if (backendStatus() === "anon") {
    root.append(backendPanel({ text: LOGIN }));
    return root;
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) {
    root.append(emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 출석을 볼 수 있습니다."));
    return root;
  }
  const key = attendanceKey(courseId);
  const load = () => attendanceViews.load(key, courseId);
  const body = el("div", { class: "att-body", "aria-live": "polite" });
  const sessionsById = new Map();
  const smart = smartPanel(courseId, sessionsById);
  root.append(smart.slot, body);

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("출석을 불러오는 중입니다"));
      else if (missing(entry.error)) body.replaceChildren(emptyState("출석을 볼 수 없습니다", "이 과목의 구성원이 아니거나 과목을 찾을 수 없습니다."));
      else body.replaceChildren(errorState("출석을 불러오지 못했습니다", attendanceMessage(entry.error), retry(load)));
      return;
    }
    const weeks = Array.isArray(data.weeks) ? data.weeks : [];
    sessionsById.clear();
    for (const week of weeks) for (const session of week.sessions || []) sessionsById.set(session.sessionId, `${week.weekNo}주 ${session.sessionNo}차시`);
    smart.paint();
    const s = data.summary;
    const stats = s ? el("div", { class: "att-stats" },
      el("span", { text: `전체 ${s.sessions}` }),
      el("span", { text: `출석 ${s.present}` }),
      el("span", { text: `지각 ${s.late}` }),
      el("span", { text: `결석 ${s.absent}` }),
      el("span", { text: `유고 ${s.excused}` }),
      el("span", { text: `미처리 ${s.unmarked}` }),
      data.settings && data.settings.latesPerAbsence > 0 ? el("span", { text: `결석 환산 ${s.absenceEquivalent} (지각 ${data.settings.latesPerAbsence}번 = 결석 1번)` }) : null,
    ) : null;
    if (!weeks.length) {
      body.replaceChildren(stats || "", emptyState("등록된 차시가 없습니다", "담당 교수가 출석 차시를 만들면 여기에 주차별로 표시됩니다."));
      return;
    }
    const numbers = weeks.map((week) => week.weekNo);
    const selected = numbers.includes(ctx.state.attendanceWeek) ? ctx.state.attendanceWeek : null;
    const bar = el("div", { class: "attend-weeks", role: "group", "aria-label": "주차" }, ...numbers.map((n) => el("button", {
      type: "button",
      class: `week-num${n === selected ? " on" : ""}`,
      "data-act": "attend-week",
      "data-week": String(n),
      "aria-pressed": n === selected ? "true" : "false",
      text: String(n),
    })));
    const cards = weeks.map((week) => el("article", { class: `att-card${week.weekNo === selected ? " is-current" : ""}`, id: `att-${week.weekNo}` },
      el("header", { class: "att-card-h" }, el("h2", { text: `${week.weekNo}주` })),
      ...(week.sessions || []).map(sessionRow),
    ));
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: attendanceMessage(entry.error) }) : "",
      stats || "",
      bar,
      ...cards,
    );
  };
  paint(attendanceViews.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    attendanceViews.watch(root, key, paint, load);
    activeRounds.watch(root, key, () => smart.paint(), null);
    if (freshVisit("student-attendance", key) === "entry" || !attendanceViews.peek(key)) load();
    // 다음 조회는 화면이 붙어 있을 때만 보낸다. 다른 화면으로 가면 그 tick에서 멈춘다.
    startPolling(root, courseId, smart.known, () => smart.paint());
  });
  return root;
}
