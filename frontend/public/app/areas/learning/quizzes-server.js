// 학생의 서버 퀴즈 상세와 응시 화면 (`?m=test&n=srv-<quizId>`, 응시는 `&attempt=<attemptId>`).
// 시작 가능 여부, 응시 구간, 응시 마감 일시는 서버가 정한다. 남은 시간은 응답의 `deadlineAt - serverTime`에서
// 도착 뒤 흐른 시간만 빼서 센다. 답은 문항마다 읽은 `revision`과 함께 바로 저장하고, 제출은 `Idempotency-Key`로
// 한 번만 접수된다. 점수·정답·해설·문항 점수는 응답에 그 속성이 있을 때만 그린다.
import { el } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState, badge } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime, ddayAt } from "../../shared/home-remote.js";
import {
  quizDetails, attemptDetails, quizKey, attemptKey, startAttempt, saveAnswer, submitAttempt, submitUncertain,
  remainingMs, clockText, quizMessage, isConflict, scoreText, studentQuizHref, PHASE, ATTEMPT_STATUS, FINISH, QUESTION_TYPE,
} from "../../shared/quizzes-remote.js";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 시험을 보고 응시할 수 있습니다.";
const TEXT_DELAY = 1200;
const MAX_TEXT = 2000;

const missing = (error) => error instanceof ApiError && error.status === 404;

// attemptKey -> 응시 중 상태. 다시 그리기와 재조회 뒤에도 고른 답과 저장 상태가 남는다. 세션이 바뀌면 버린다.
const sessions = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  sessions.clear();
});

function memoOf(key) {
  if (!sessions.has(key)) {
    sessions.set(key, { answers: new Map(), moved: false, ended: false, expired: false, finishing: false, submitting: false, receipt: null, receiptEntry: null, paint: null });
  }
  return sessions.get(key);
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** 일반 텍스트 문단. 모두 텍스트 노드다. */
function prose(text, empty) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? el("div", { class: "prose" }, el("p", { class: "tiny", text: empty })) : null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function gate(root, kj, paintOff) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: LOGIN_TEXT });
  if (status === "off") {
    if (!backendSettled()) {
      queueMicrotask(() => connectBackend().then((next) => {
        if (root.isConnected && next.status === "off") paintOff();
      }));
      return loadingState("ASTRA 서버에 연결하는 중입니다");
    }
    return emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요.");
  }
  if (!courseIdFor(kj)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 시험만 볼 수 있습니다.");
  return null;
}

function periodText(info) {
  const close = koDateTime(info.closeAt);
  return info.openAt ? `${koDateTime(info.openAt)} ~ ${close}` : `~ ${close}`;
}

function phaseBadge(phase) {
  return badge(PHASE[phase] || phase, phase === "open" ? "success" : phase === "closed" ? "danger" : "neutral");
}

const releaseText = (info) => (info.resultReleaseAt ? `${koDateTime(info.resultReleaseAt)} ~` : "비공개");

/** 서버 퀴즈의 상세 또는 응시 화면 (`screen.serverQuiz`, `screen.serverAttempt`). `parts`는 learning.js 도우미다. */
export function remoteQuizDetail(screen, ctx, parts) {
  const kj = screen.course;
  const quizId = screen.serverQuiz;
  const main = el("div", { class: "act-main" });
  const root = el("section", { class: "bleed act-layout is-solo is-open", "data-item-open": `srv-${quizId}` }, main);
  const blocked = gate(root, kj, () => main.replaceChildren(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    main.append(el("div", { class: "act-body" }, parts.typeLine("test"), blocked));
    return root;
  }
  const courseId = courseIdFor(kj);
  if (screen.serverAttempt) {
    main.append(attemptView(courseId, kj, quizId, screen.serverAttempt, parts));
    return root;
  }
  const key = quizKey(courseId, quizId);
  const load = () => quizDetails.load(key, courseId, quizId);
  const listHref = `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main.replaceChildren(loadingState("시험을 불러오는 중입니다"));
      else if (missing(entry.error)) main.replaceChildren(emptyState("시험을 찾을 수 없습니다", "아직 공개되지 않았거나 볼 수 있는 권한이 없습니다.", go(listHref, "학습활동 목록", { class: "btn btn-secondary" })));
      else main.replaceChildren(errorState("시험을 불러오지 못했습니다", quizMessage(entry.error), retry(load)));
      return;
    }
    main.replaceChildren(detailBody(data, courseId, kj, quizId, parts, entry.error));
  };
  paint(quizDetails.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    quizDetails.watch(root, key, paint, load);
    if (freshVisit("quiz-detail", key) === "entry" || !quizDetails.peek(key)) load();
  });
  return root;
}

function detailBody(data, courseId, kj, quizId, parts, staleError) {
  const info = data.quiz || {};
  const online = info.examType !== "offline";
  const dday = data.phase === "open" ? ddayAt(info.closeAt, data.serverTime) : "";
  const body = el("div", { class: "act-body" },
    parts.typeLine("test"),
    el("h1", { text: info.title }),
    staleError ? el("p", { class: "err", role: "alert", text: quizMessage(staleError) }) : null,
    parts.metaTable([
      ["시험형태", online ? "온라인 시험" : "오프라인 시험"],
      ["응시기간", el("span", { class: "due-line" }, el("span", { text: periodText(info) }), dday ? el("span", { class: "dday", text: dday }) : null)],
      ["응시 구간", phaseBadge(data.phase)],
      ["배점", `${scoreText(info.maxScore)}점`],
      online ? ["문항", `${info.questionCount}문항`] : null,
      online ? ["제한시간", info.timeLimitMinutes ? `${info.timeLimitMinutes}분 (응시 마감을 넘지 않습니다)` : "응시 마감까지"] : null,
      online ? ["응시 횟수", info.attemptLimit ? `${data.attemptsUsed ?? 0} / ${info.attemptLimit}회` : `${data.attemptsUsed ?? 0}회 (제한 없음)`] : null,
      ["결과공개", releaseText(info)],
    ].filter(Boolean)),
    prose(info.description, "시험 안내가 없습니다."),
  );
  if (data.revision != null) {
    body.append(el("p", { class: "note", text: "담당 교수 화면입니다. 응시 현황과 채점은 교수 화면에서 봅니다." }));
    return body;
  }
  // 결과 공개 뒤에만 서버가 `score`를 보낸다.
  if (data.score != null) {
    body.append(el("section", { class: "note ok", "aria-label": "평가" },
      el("h2", { text: "평가" }),
      el("p", { text: `${scoreText(data.score)} / ${scoreText(info.maxScore)}점` }),
    ));
  }
  const attempts = Array.isArray(data.attempts) ? data.attempts : [];
  if (attempts.length) {
    body.append(el("h2", { text: "내 응시" }), el("div", { class: "stack srv-quiz-attempts" }, ...attempts.map((item) => attemptRow(item, kj, quizId))));
  }
  body.append(startArea(data, courseId, kj, quizId, attempts));
  return body;
}

function attemptRow(item, kj, quizId) {
  const live = item.status === "in_progress";
  const meta = [
    `${koDateTime(item.startedAt)} 시작`,
    item.submittedAt ? `${koDateTime(item.submittedAt)} ${FINISH[item.finishReason] || "제출"}` : `마감 ${koDateTime(item.deadlineAt)}`,
  ].join(" · ");
  return el("div", { class: "file-row" },
    el("span", {},
      el("b", { text: `${item.attemptNo}회차 · ${ATTEMPT_STATUS[item.status] || item.status}` }),
      el("span", { class: "tiny", text: meta }),
      item.totalScore != null ? el("span", { class: "tiny", text: `총점 ${scoreText(item.totalScore)}점` }) : null,
    ),
    live ? null : go(studentQuizHref(kj, quizId, { attempt: item.attemptId }), "답안 보기", { class: "btn btn-secondary", "aria-label": `${item.attemptNo}회차 답안 보기` }),
  );
}

function startArea(data, courseId, kj, quizId, attempts) {
  const info = data.quiz || {};
  const wrap = el("div", { class: "srv-quiz-start" });
  if (info.examType === "offline") {
    wrap.append(el("p", { class: "note", text: "오프라인(대면) 시험입니다. 온라인으로 응시할 수 없습니다." }));
    return wrap;
  }
  const live = attempts.find((item) => item.status === "in_progress");
  const status = el("p", { class: "tiny", role: "status", "aria-live": "polite", "data-quiz-start-status": "" });
  const attrs = { "data-act": "srv-quiz-start", "data-course": String(courseId), "data-quiz": String(quizId), "data-kj": kj };
  if (!data.canStart) {
    const why = data.phase === "not_open" ? "아직 응시 기간이 아닙니다."
      : data.phase === "closed" ? "응시 기간이 끝났습니다."
        : info.attemptLimit && data.attemptsUsed >= info.attemptLimit ? "응시 횟수를 모두 썼습니다." : "지금은 응시할 수 없습니다.";
    wrap.append(el("p", { class: "note", text: why }));
    return wrap;
  }
  if (live) {
    wrap.append(
      el("p", { class: "tiny", text: `${live.attemptNo}회차 응시가 진행 중입니다. 마감 ${koDateTime(live.deadlineAt)}. 다른 기기에서 열었던 응시도 이 화면으로 옮겨 이어서 풉니다.` }),
      el("div", { class: "btn-row" }, btn("이어서 응시", attrs, "primary")),
      status,
    );
    return wrap;
  }
  const start = btn("응시 시작", { ...attrs, disabled: true }, "primary");
  const agree = el("input", { type: "checkbox", id: `srv-quiz-agree-${quizId}` });
  agree.addEventListener("change", () => { start.disabled = !agree.checked; });
  wrap.append(
    el("ul", { class: "tiny srv-quiz-rules" },
      el("li", { text: info.timeLimitMinutes ? `시작하면 ${info.timeLimitMinutes}분 제한 시간이 바로 흐르고, 응시 마감 일시를 넘지 않습니다.` : "시작하면 응시 마감 일시까지 풀 수 있습니다." }),
      el("li", { text: "답은 고르거나 입력하는 대로 서버에 저장됩니다. 마감 시각이 되면 마지막으로 저장된 답으로 제출됩니다." }),
      el("li", { text: "다른 기기나 브라우저에서 이어서 풀면 이 화면에서는 더 저장할 수 없습니다." }),
    ),
    el("label", { class: "choice", for: agree.id }, agree, el("span", { text: "응시 안내를 확인했습니다" })),
    el("div", { class: "btn-row" }, start),
    status,
  );
  return wrap;
}

// 시작 요청이 나가 있는 퀴즈(quizKey). 응답을 기다리는 동안 다시 그린 화면의 새 버튼도 두 번째 시작을 보내지 않는다.
const starting = new Set();

/** `srv-quiz-start` (learning/actions.js): 응시 시작 또는 진행 중 응시 이어 가기. */
export async function startFromDetail(node, api) {
  if (node.disabled || node.getAttribute("aria-busy") === "true") return;
  const courseId = Number(node.dataset.course);
  const quizId = Number(node.dataset.quiz);
  const kj = node.dataset.kj;
  const scope = quizKey(courseId, quizId);
  if (starting.has(scope)) return;
  const wrap = node.closest(".srv-quiz-start, .srv-quiz-moved");
  const status = wrap && wrap.querySelector("[data-quiz-start-status]");
  const say = (text) => {
    if (status && status.isConnected) {
      status.setAttribute("role", "alert");
      status.textContent = text;
    } else {
      api.getState().toast = text;
      api.paintToast();
    }
  };
  const label = node.textContent;
  starting.add(scope);
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = "시작하는 중…";
  try {
    const { state, resumed } = await startAttempt(courseId, quizId);
    const memo = memoOf(attemptKey(courseId, quizId, state.attemptId));
    memo.moved = false;
    // 옮겨지기 전에 저장하지 못한 답은 이 화면이 이어받은 뒤 다시 보낸다.
    for (const entry of memo.answers.values()) entry.error = "";
    api.getState().toast = resumed ? "진행 중인 응시를 이어 갑니다" : `${state.attemptNo}회차 응시를 시작했습니다`;
    const href = studentQuizHref(kj, quizId, { attempt: state.attemptId });
    if (memo.paint && `${location.pathname}${location.search}` === href) {
      api.paintToast();
      attemptDetails.load(attemptKey(courseId, quizId, state.attemptId), courseId, quizId, state.attemptId);
    } else api.navigate(href);
  } catch (error) {
    say(quizMessage(error));
    if (error instanceof ApiError && (error.status === 409 || error.status === 404)) quizDetails.load(quizKey(courseId, quizId), courseId, quizId);
  } finally {
    starting.delete(scope);
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = label;
    }
  }
}

// ---------- 응시 / 결과 ----------

const controllers = new WeakMap(); // 응시 폼 노드 -> controller

function attemptView(courseId, kj, quizId, attemptId, parts) {
  const key = attemptKey(courseId, quizId, attemptId);
  const headKey = quizKey(courseId, quizId);
  const memo = memoOf(key);
  const load = () => attemptDetails.load(key, courseId, quizId, attemptId);
  const frame = el("div", { class: "act-body" });
  const detailHref = studentQuizHref(kj, quizId);
  // 접수 결과는 그것을 만든 history 항목에서만 보인다.
  if (memo.receipt && memo.receiptEntry !== history.state) memo.receipt = null;
  let controller = null;
  const paint = () => {
    const entry = attemptDetails.peek(key);
    const data = entry && entry.data;
    const head = quizDetails.peek(headKey);
    const info = head && head.data && head.data.quiz;
    if (!data) {
      controller = null;
      if (!entry || !entry.error) frame.replaceChildren(parts.typeLine("test"), loadingState("응시를 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(parts.typeLine("test"), emptyState("응시를 찾을 수 없습니다", "본인의 응시가 아니거나 이 시험의 응시가 아닙니다.", go(detailHref, "시험 정보", { class: "btn btn-secondary" })));
      else frame.replaceChildren(parts.typeLine("test"), errorState("응시를 불러오지 못했습니다", quizMessage(entry.error), retry(load)));
      return;
    }
    if (data.status === "in_progress" && !memo.ended) {
      if (!controller) {
        controller = takingForm(courseId, kj, quizId, attemptId, key, memo, parts);
        frame.replaceChildren(controller.form);
      }
      controller.update(data, info, entry.error);
      return;
    }
    controller = null;
    frame.replaceChildren(resultView(data, info, memo, detailHref, parts));
  };
  memo.paint = paint;
  paint();
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    attemptDetails.watch(frame, key, paint, load);
    quizDetails.watch(frame, headKey, paint, null);
    if (freshVisit("quiz-attempt", key) === "entry" || !attemptDetails.peek(key)) load();
    if (!quizDetails.peek(headKey)) quizDetails.load(headKey, courseId, quizId);
  });
  return frame;
}

function valueOf(question) {
  const answer = question.answer || {};
  if (question.type === "short") return answer.text == null ? "" : String(answer.text);
  return Array.isArray(answer.optionIds) ? [...answer.optionIds].sort((a, b) => a - b) : [];
}

const answered = (question, value) => (question.type === "short" ? String(value || "").trim() !== "" : value.length > 0);

function takingForm(courseId, kj, quizId, attemptId, key, memo, parts) {
  let data = null;
  let questions = [];
  const inputs = new Map(); // questionId -> { controls, note }
  const timers = new Map(); // questionId -> 단답형 저장 지연
  let timer = 0;
  let warned = false;
  let confirming = false;

  const title = el("h1", {});
  const clock = el("p", { class: "srv-quiz-clock", role: "timer", "aria-live": "off" });
  const alertLine = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const banner = el("div", {});
  const list = el("ol", { class: "srv-quiz-questions" });
  const message = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const submit = btn("제출", { "data-act": "srv-quiz-submit" }, "primary");
  const cancel = btn("취소", {}, "ghost");
  cancel.hidden = true;
  const form = el("div", { class: "srv-quiz-take" },
    parts.typeLine("test"),
    title,
    el("div", { class: "srv-quiz-bar" }, clock, alertLine),
    banner,
    list,
    message,
    el("div", { class: "submit-actions" }, cancel, submit),
  );
  const say = (text, alert) => {
    message.setAttribute("role", alert ? "alert" : "status");
    message.textContent = text || "";
  };
  const stateOf = (questionId) => {
    if (!memo.answers.has(questionId)) memo.answers.set(questionId, { value: null, revision: 0, dirty: false, inflight: null, savedAt: null, error: "" });
    return memo.answers.get(questionId);
  };
  const locked = () => memo.moved || memo.ended || memo.expired || memo.submitting;

  function noteText(question) {
    const s = stateOf(question.questionId);
    if (s.inflight) return "저장하는 중입니다.";
    if (s.error) return s.error;
    if (s.dirty) return "저장 대기 중입니다.";
    if (s.savedAt) return `${koDateTime(s.savedAt)} 저장됨`;
    return answered(question, s.value) ? "저장됨" : "아직 답하지 않았습니다.";
  }
  function paintNote(question) {
    const slot = inputs.get(question.questionId);
    if (!slot) return;
    const s = stateOf(question.questionId);
    slot.note.textContent = noteText(question);
    slot.note.classList.toggle("err", Boolean(s.error));
  }
  function paintControls() {
    for (const question of questions) {
      const slot = inputs.get(question.questionId);
      if (!slot) continue;
      const s = stateOf(question.questionId);
      for (const control of slot.controls) {
        control.disabled = locked();
        if (question.type === "short") {
          if (document.activeElement !== control && control.value !== s.value) control.value = s.value;
        } else control.checked = s.value.includes(Number(control.value));
      }
      paintNote(question);
    }
    // 제출 절차(저장 마무리부터 접수까지) 동안에는 제출 버튼을 막는다.
    submit.disabled = memo.moved || memo.ended || memo.finishing;
    cancel.disabled = submit.disabled;
  }

  async function save(question) {
    const s = stateOf(question.questionId);
    if (s.inflight || !s.dirty || locked()) return s.inflight;
    s.dirty = false;
    const value = s.value;
    const payload = question.type === "short" ? { revision: s.revision, text: value.trim() ? value : null } : { revision: s.revision, optionIds: value };
    s.inflight = saveAnswer(courseId, quizId, attemptId, question.questionId, payload).then((saved) => {
      s.revision = saved.revision;
      s.savedAt = saved.savedAt;
      s.error = "";
    }, (error) => {
      s.error = quizMessage(error);
      if (isConflict(error, "answer_conflict")) {
        // 다른 탭이 먼저 저장했다. 서버의 답과 개정 번호를 받아 쓴다.
        s.adopt = true;
        attemptDetails.load(key, courseId, quizId, attemptId);
      } else if (isConflict(error, "attempt_moved")) {
        memo.moved = true;
        s.dirty = true;
      } else if (isConflict(error, "deadline_passed") || isConflict(error, "attempt_closed")) {
        s.dirty = true;
        finish(null, true);
      } else s.dirty = true;
    }).finally(() => {
      s.inflight = null;
      if (form.isConnected) {
        paintBanner();
        paintControls();
      }
      if (s.dirty && !s.error && !locked()) save(question);
    });
    paintNote(question);
    return s.inflight;
  }

  function change(question, value) {
    if (locked()) return;
    const s = stateOf(question.questionId);
    s.value = value;
    s.dirty = true;
    s.error = "";
    paintNote(question);
    if (question.type === "short") {
      clearTimeout(timers.get(question.questionId));
      timers.set(question.questionId, setTimeout(() => { if (form.isConnected) save(question); }, TEXT_DELAY));
    } else save(question);
  }

  function questionBlock(question) {
    const s = stateOf(question.questionId);
    const note = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
    const legend = el("legend", {}, el("b", { text: `${question.number}. ` }), el("span", { class: "tiny", text: `${QUESTION_TYPE[question.type] || question.type} · ${scoreText(question.points)}점` }));
    const controls = [];
    const body = [];
    if (question.type === "short") {
      const area = el("textarea", { rows: "4", maxlength: String(MAX_TEXT), "aria-label": `${question.number}번 답` });
      area.value = s.value || "";
      area.addEventListener("input", () => change(question, area.value));
      area.addEventListener("blur", () => {
        clearTimeout(timers.get(question.questionId));
        save(question);
      });
      controls.push(area);
      body.push(area);
    } else {
      const name = `srv-quiz-q-${question.questionId}`;
      for (const option of question.options || []) {
        const input = el("input", { type: question.type === "single" ? "radio" : "checkbox", name, value: String(option.optionId) });
        input.checked = s.value.includes(option.optionId);
        input.addEventListener("change", () => {
          const picked = question.type === "single"
            ? (input.checked ? [option.optionId] : [])
            : controls.filter((box) => box.checked).map((box) => Number(box.value)).sort((a, b) => a - b);
          change(question, picked);
        });
        controls.push(input);
        body.push(el("label", { class: "choice" }, input, el("span", { text: option.text })));
      }
      if (question.type === "single") {
        const clear = btn("선택 지우기", { class: "btn btn-ghost srv-quiz-clear", "aria-label": `${question.number}번 선택 지우기` }, "ghost");
        clear.addEventListener("click", () => {
          controls.forEach((box) => { box.checked = false; });
          change(question, []);
        });
        controls.push(clear);
        body.push(clear);
      }
    }
    inputs.set(question.questionId, { controls, note });
    return el("li", {}, el("fieldset", { class: "srv-quiz-q" }, legend, prose(question.text), ...body, note));
  }

  function paintBanner() {
    if (memo.moved) {
      const status = el("p", { class: "tiny", role: "status", "aria-live": "polite", "data-quiz-start-status": "" });
      banner.replaceChildren(el("section", { class: "note srv-quiz-moved", role: "alert" },
        el("h2", { text: "다른 기기나 브라우저에서 응시 중입니다" }),
        el("p", { text: "이 화면에서는 답을 저장하거나 제출할 수 없습니다. 이 화면에서 이어서 풀면 다른 쪽 화면이 더 저장할 수 없게 됩니다. 마감 일시는 바뀌지 않습니다." }),
        el("div", { class: "btn-row" }, btn("이 화면에서 이어서 응시", { "data-act": "srv-quiz-start", "data-course": String(courseId), "data-quiz": String(quizId), "data-kj": kj }, "primary")),
        status,
      ));
    } else banner.replaceChildren();
  }

  function tick() {
    if (!form.isConnected) {
      clearInterval(timer);
      timers.forEach((id) => clearTimeout(id));
      return;
    }
    const left = remainingMs(data);
    if (Number.isNaN(left)) {
      clock.textContent = "";
      return;
    }
    clock.textContent = `남은 시간 ${clockText(left)} · 마감 ${koDateTime(data.deadlineAt)}`;
    clock.classList.toggle("is-low", left <= 60000);
    if (left <= 60000 && !warned) {
      warned = true;
      alertLine.textContent = "1분 남았습니다. 마감 시각이 되면 저장된 답으로 제출됩니다.";
    }
    if (left <= 0 && !memo.expired && !memo.ended) finish(null, true);
  }

  // 모든 답의 저장이 끝날 때까지 기다린다(대기 중인 단답형도 바로 보낸다).
  async function flushAll() {
    for (let round = 0; round < 3; round += 1) {
      for (const question of questions) {
        clearTimeout(timers.get(question.questionId));
        save(question);
      }
      const pending = questions.map((question) => stateOf(question.questionId).inflight).filter(Boolean);
      if (!pending.length) return;
      await Promise.allSettled(pending);
    }
  }

  /**
   * 제출. `auto`는 마감 시각이 되었을 때이고, 서버가 마지막으로 저장한 답으로 닫는다. 마감 뒤에는 답을 더
   * 저장하지 않고(입력이 잠긴다) 제출만 다시 보낼 수 있다. 제출 절차는 한 번에 하나다: `memo.finishing`을
   * 기다리기 전에 켜므로, 저장을 마무리하는 동안 온 두 번째 클릭이나 마감은 새 요청을 보내지 않는다. 그동안
   * 마감이 오면 `memo.expired`만 켜고, 진행 중인 절차가 저장 확인 없이 바로 제출한다.
   */
  async function finish(api, auto) {
    if (memo.moved || memo.ended) return;
    if (auto || memo.expired) {
      memo.expired = true;
      say("응시 시간이 끝났습니다. 마지막으로 저장된 답으로 제출하고 있습니다.");
    }
    if (memo.finishing) {
      paintControls();
      return;
    }
    memo.finishing = true;
    paintControls();
    try {
      if (!memo.expired) {
        await flushAll();
        if (memo.moved) return paintBanner();
        if (memo.ended) return;
        const failed = questions.filter((question) => stateOf(question.questionId).error || stateOf(question.questionId).dirty);
        if (!memo.expired && failed.length) {
          say(`저장하지 못한 답이 ${failed.length}개 있습니다(${failed.map((q) => `${q.number}번`).join(", ")}). 네트워크를 확인하고 그 문항을 다시 고른 뒤 제출하세요.`, true);
          return;
        }
      }
      await send(api);
    } finally {
      memo.finishing = false;
      // 그사이 다시 그린 응시 폼(같은 memo)도 제출 버튼을 되돌려 받는다.
      if (form.isConnected) paintControls();
      else if (memo.paint) memo.paint();
    }
  }

  async function send(api) {
    memo.submitting = true;
    paintControls();
    submit.textContent = "제출하는 중…";
    try {
      const receipt = await submitAttempt(courseId, quizId, attemptId);
      memo.receipt = receipt;
      memo.receiptEntry = history.state;
      memo.ended = true;
      const text = receipt.finishReason === "deadline" ? "마감 시각에 저장된 답으로 제출되었습니다" : "시험을 제출했습니다";
      const state = api ? api.getState() : null;
      if (state) {
        state.toast = text;
        api.paintToast();
      } else say(text);
      attemptDetails.load(key, courseId, quizId, attemptId);
    } catch (error) {
      if (isConflict(error, "attempt_moved")) {
        memo.moved = true;
        paintBanner();
      } else if (submitUncertain(courseId, quizId, attemptId)) {
        say(`${memo.expired ? "응시 시간이 끝났지만 " : ""}제출 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 다시 제출하세요. 이미 접수되었다면 같은 접수 결과를 돌려받습니다.`, true);
      } else {
        say(quizMessage(error), true);
        attemptDetails.load(key, courseId, quizId, attemptId);
      }
    } finally {
      memo.submitting = false;
      if (form.isConnected) {
        submit.textContent = "제출";
        confirming = false;
        cancel.hidden = true;
        paintControls();
        if (memo.ended && memo.paint) memo.paint();
      }
    }
  }

  cancel.addEventListener("click", () => {
    confirming = false;
    cancel.hidden = true;
    submit.textContent = "제출";
    say("");
  });

  const controller = {
    form,
    /** 두 번 눌러 제출한다. 첫 번째는 답하지 않은 문항 수를 알린다. */
    send(api) {
      if (submit.disabled) return;
      if (memo.expired) return void finish(api, false);
      if (!confirming) {
        const empty = questions.filter((question) => !answered(question, stateOf(question.questionId).value));
        confirming = true;
        cancel.hidden = false;
        submit.textContent = "제출 확인";
        say(`${empty.length ? `답하지 않은 문항이 ${empty.length}개 있습니다. ` : ""}제출하면 답을 바꿀 수 없습니다. 제출 확인을 누르면 제출합니다.`, Boolean(empty.length));
        return;
      }
      void finish(api, false);
    },
    update(next, info, staleError) {
      const first = !data;
      data = next;
      title.textContent = info ? `${info.title} · ${next.attemptNo}회차` : `${next.attemptNo}회차 응시`;
      questions = Array.isArray(next.questions) ? next.questions : [];
      for (const question of questions) {
        const s = stateOf(question.questionId);
        const server = question.answer || {};
        // 고친 뒤 아직 저장하지 않은 답은 그대로 두고, 나머지는 서버가 저장한 답과 개정 번호를 쓴다.
        if (s.value == null || s.adopt || (!s.dirty && !s.inflight)) {
          s.value = valueOf(question);
          s.revision = Number(server.revision) || 0;
          s.savedAt = server.savedAt || s.savedAt;
          if (s.adopt) s.dirty = false;
          s.adopt = false;
        }
      }
      if (first) list.replaceChildren(...questions.map(questionBlock));
      if (next.acceptingAnswers && !locked()) {
        for (const question of questions) {
          const s = stateOf(question.questionId);
          if (s.dirty && !s.inflight && !s.error) save(question);
        }
      }
      if (!next.acceptingAnswers && !memo.expired && !memo.ended) {
        finish(null, true);
      }
      if (staleError) say(quizMessage(staleError), true);
      paintBanner();
      paintControls();
      tick();
      if (first) timer = setInterval(tick, 1000);
    },
  };
  controllers.set(form, controller);
  return controller;
}

/** `srv-quiz-submit` (learning/actions.js). */
export function submitFromAttempt(node, api) {
  const form = node.closest(".srv-quiz-take");
  const controller = form && controllers.get(form);
  if (controller) controller.send(api);
}

function resultView(data, info, memo, detailHref, parts) {
  const body = el("div", {}, parts.typeLine("test"), el("h1", { text: info ? `${info.title} · ${data.attemptNo}회차` : `${data.attemptNo}회차 답안` }));
  if (memo.receipt) {
    body.append(el("section", { class: "note ok", role: "status" },
      el("h2", { text: memo.receipt.finishReason === "deadline" ? "마감 시각에 제출되었습니다" : "제출했습니다" }),
      el("p", { text: `서버 접수 시각 ${koDateTime(memo.receipt.submittedAt)}` }),
    ));
  }
  const live = data.status === "in_progress";
  body.append(parts.metaTable([
    ["상태", live ? "제출 처리 중" : ATTEMPT_STATUS[data.status] || data.status],
    ["시작", koDateTime(data.startedAt)],
    ["접수", data.submittedAt ? `${koDateTime(data.submittedAt)} · ${FINISH[data.finishReason] || ""}` : "—"],
    data.totalScore != null ? ["총점", `${scoreText(data.totalScore)}${info ? ` / ${scoreText(info.maxScore)}` : ""}점`] : null,
    info ? ["결과공개", releaseText(info)] : null,
  ].filter(Boolean)));
  if (data.status === "submitted") body.append(el("p", { class: "tiny", text: "단답형 문항을 채점하고 있습니다." }));
  const questions = Array.isArray(data.questions) ? data.questions : [];
  body.append(el("h2", { text: "내 답안" }), el("ol", { class: "srv-quiz-questions" }, ...questions.map((question) => {
    const answer = question.answer || {};
    const picked = new Set(Array.isArray(answer.optionIds) ? answer.optionIds : []);
    const head = el("p", {},
      el("b", { text: `${question.number}. ` }),
      el("span", { class: "tiny", text: `${QUESTION_TYPE[question.type] || question.type} · ${scoreText(question.points)}점` }),
      answer.correct != null ? badge(answer.correct ? "정답" : "오답", answer.correct ? "success" : "danger") : null,
      answer.awardedScore != null ? el("span", { class: "tiny", text: ` 받은 점수 ${scoreText(answer.awardedScore)}점` }) : null,
    );
    const mine = question.type === "short"
      ? el("div", { class: "srv-quiz-answer" }, el("span", { class: "tiny", text: "내 답" }), prose(answer.text, "답하지 않았습니다."))
      : el("ul", { class: "srv-quiz-options" }, ...(question.options || []).map((option) => el("li", {},
        el("span", { text: option.text }),
        picked.has(option.optionId) ? badge("내 선택", "neutral") : null,
        option.correct === true ? badge("정답 보기", "success") : null,
      )));
    return el("li", { class: "srv-quiz-q" },
      head,
      prose(question.text),
      mine,
      answer.feedback ? el("div", {}, el("span", { class: "tiny", text: "채점 의견" }), prose(answer.feedback)) : null,
      question.explanation ? el("div", {}, el("span", { class: "tiny", text: "해설" }), prose(question.explanation)) : null,
    );
  })));
  body.append(el("div", { class: "btn-row" }, go(detailHref, "시험 정보", { class: "btn btn-secondary" })));
  return body;
}
