// 서버 설문의 응답 폼과 결과 집계 화면. 강의실 설문(learning/surveys-server.js)과 알림마당 설문
// (home/surveys-server.js)이 함께 쓴다. 문항·보기·답변 문구는 모두 텍스트 노드로만 넣는다.
// 고른 답은 세션이 바뀌면 버리는 메모리 맵에만 두어 다시 그리기와 재조회 뒤에도 남는다.
import { el } from "../dom.js";
import { btn, badge, loadingState, errorState } from "../ui/components.js";
import { subscribe } from "./backend.js";
import { freshVisit } from "./remote-cache.js";
import { koDateTime } from "./home-remote.js";
import {
  surveyPath, surveyDetails, surveyResults, surveyTexts, textsKey, completeSurvey, completionUncertain,
  surveyMessage, isConflict, PHASE, STATE, QUESTION_TYPE, WITHHELD, TEXT_PAGE_SIZE,
} from "./surveys-remote.js";

const MAX_TEXT = 2000;

// surveyPath -> { answers: Map(questionId -> optionIds[] | text), receipt, receiptEntry }
const drafts = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
});

function draftOf(path) {
  if (!drafts.has(path)) drafts.set(path, { answers: new Map(), receipt: null, receiptEntry: null });
  return drafts.get(path);
}

/** 완료 결과는 그것을 만든 history 항목에서만 보여 준다. */
export function surveyReceipt(courseId, surveyId) {
  const memo = drafts.get(surveyPath(courseId, surveyId));
  return memo && memo.receipt && memo.receiptEntry === history.state ? memo.receipt : null;
}

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

export function periodText(info) {
  const close = koDateTime(info.closeAt);
  return info.openAt ? `${koDateTime(info.openAt)} ~ ${close}` : `~ ${close}`;
}

export function phaseBadge(phase) {
  return badge(PHASE[phase] || phase, phase === "open" ? "success" : phase === "closed" ? "danger" : "neutral");
}

export function stateBadge(state) {
  return badge(STATE[state] || state, state === "completed" ? "success" : "neutral");
}

/** 결과 공개 설정을 한 줄로. */
export function releaseText(info) {
  if (!info.resultsVisible) return "공개하지 않습니다";
  return info.resultsReleaseAt ? `${koDateTime(info.resultsReleaseAt)} 이후, 마감 뒤 공개` : "마감 뒤 공개";
}

function selectText(question) {
  if (question.type !== "multiple") return "";
  const max = question.maxSelect ?? (question.options || []).length;
  return question.minSelect === max ? ` · ${max}개 고르기` : ` · ${question.minSelect ?? 1}~${max}개 고르기`;
}

// ---------- 응답 폼 ----------

const forms = new WeakMap(); // 폼 노드 -> controller

/**
 * 응답자 상세(`questions`가 있을 때)의 응답 폼. 제출은 `srv-survey-complete`(learning/actions.js)가 부른다.
 * `onDone(receipt)`은 완료 뒤 화면이 할 일이다(창 닫기 등).
 */
export function surveyForm(courseId, surveyId, data, { onDone } = {}) {
  const path = surveyPath(courseId, surveyId);
  const memo = draftOf(path);
  const questions = Array.isArray(data.questions) ? data.questions : [];
  const message = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const submit = btn("응답 제출", { "data-act": "srv-survey-complete" }, "primary");
  let busy = false;
  const blocks = questions.map((question) => questionBlock(question, memo));
  const form = el("div", { class: "srv-survey-form" },
    el("p", { class: "tiny", text: data.survey && data.survey.anonymous
      ? "익명 설문입니다. 담당자에게는 누가 무엇을 답했는지 보이지 않습니다. 제출한 응답은 고칠 수 없습니다."
      : "실명 설문입니다. 담당자는 집계만 보고, 누가 무엇을 답했는지는 보이지 않습니다. 제출한 응답은 고칠 수 없습니다." }),
    el("ol", { class: "srv-survey-questions" }, ...blocks.map((block) => block.node)),
    message,
    el("div", { class: "submit-actions" }, submit),
  );
  const say = (text, alert) => {
    message.setAttribute("role", alert ? "alert" : "status");
    message.textContent = text || "";
  };
  const controls = () => [...form.querySelectorAll("input, textarea, button")];

  async function send(api) {
    if (busy) return;
    const answers = [];
    const problems = [];
    for (const question of questions) {
      const value = memo.answers.get(question.questionId);
      if (question.type === "essay") {
        const text = typeof value === "string" ? value : "";
        if (text.length > MAX_TEXT) problems.push(`${question.questionNo}번은 ${MAX_TEXT.toLocaleString("ko-KR")}자 이하입니다`);
        if (text.trim()) answers.push({ questionId: question.questionId, text });
        else if (question.required) problems.push(`${question.questionNo}번에 답하세요`);
        continue;
      }
      const ids = Array.isArray(value) ? value : [];
      if (!ids.length) {
        if (question.required) problems.push(`${question.questionNo}번에 답하세요`);
        continue;
      }
      if (question.type === "multiple") {
        const min = question.minSelect ?? 1;
        const max = question.maxSelect ?? (question.options || []).length;
        if (ids.length < min || ids.length > max) {
          problems.push(`${question.questionNo}번은 ${min === max ? `${max}개` : `${min}~${max}개`}를 고르세요`);
          continue;
        }
      }
      answers.push({ questionId: question.questionId, optionIds: [...ids].sort((a, b) => a - b) });
    }
    if (problems.length) return say(`${problems.join(". ")}.`, true);
    busy = true;
    controls().forEach((control) => { control.disabled = true; });
    form.setAttribute("aria-busy", "true");
    submit.textContent = "제출하는 중…";
    say("ASTRA 서버에 응답을 보내고 있습니다.");
    try {
      const receipt = await completeSurvey(courseId, surveyId, answers);
      memo.answers.clear();
      memo.receipt = receipt;
      memo.receiptEntry = history.state;
      api.getState().toast = "설문 응답을 제출했습니다";
      api.paintToast();
      surveyDetails.load(path, path);
      if (onDone) onDone(receipt);
    } catch (error) {
      say(surveyMessage(error), true);
      if (isConflict(error, "already_completed") || isConflict(error, "deadline_passed") || isConflict(error, "survey_changed")
        || isConflict(error, "idempotency_conflict") || (error && error.status === 404)) {
        surveyDetails.load(path, path);
      } else if (completionUncertain(courseId, surveyId)) {
        say("제출 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 답으로 다시 제출하세요. 이미 접수되었다면 같은 결과를 돌려받습니다.", true);
      }
    } finally {
      busy = false;
      if (form.isConnected) {
        form.removeAttribute("aria-busy");
        controls().forEach((control) => { control.disabled = false; });
        submit.textContent = "응답 제출";
      }
    }
  }

  forms.set(form, { send, say });
  return form;
}

function questionBlock(question, memo) {
  const id = `srv-survey-q-${question.questionId}`;
  const head = el("legend", {},
    el("b", { text: `${question.questionNo}. ` }),
    el("span", { class: "tiny", text: `${QUESTION_TYPE[question.type] || question.type}${question.required ? " · 필수" : " · 선택"}${selectText(question)}` }),
  );
  const body = [];
  if (question.type === "essay") {
    const area = el("textarea", { rows: "4", maxlength: String(MAX_TEXT), "aria-label": `${question.questionNo}번 답` });
    const saved = memo.answers.get(question.questionId);
    area.value = typeof saved === "string" ? saved : "";
    area.addEventListener("input", () => memo.answers.set(question.questionId, area.value));
    body.push(area);
  } else {
    const multiple = question.type === "multiple";
    const saved = memo.answers.get(question.questionId);
    const picked = new Set(Array.isArray(saved) ? saved : []);
    const boxes = [];
    for (const option of question.options || []) {
      const input = el("input", { type: multiple ? "checkbox" : "radio", name: id, value: String(option.optionId) });
      input.checked = picked.has(option.optionId);
      input.addEventListener("change", () => {
        memo.answers.set(question.questionId, boxes.filter((box) => box.checked).map((box) => Number(box.value)));
      });
      boxes.push(input);
      const label = question.type === "scale" ? `${option.optionNo} · ${option.text}` : option.text;
      body.push(el("label", { class: "choice" }, input, el("span", { text: label })));
    }
  }
  return { node: el("li", {}, el("fieldset", { class: "srv-survey-q" }, head, prose(question.text), ...body)) };
}

/** `srv-survey-complete` (learning/actions.js). */
export function completeFromForm(node, api) {
  const form = node.closest(".srv-survey-form");
  const controller = form && forms.get(form);
  if (controller) void controller.send(api);
}

// ---------- 결과 ----------

/** 결과 집계 화면. 마운트되면 `GET …/results`를 받는다. 서버가 가린 설문·문항은 이유만 보인다. */
export function resultsSection(courseId, surveyId, { title = "결과" } = {}) {
  const path = surveyPath(courseId, surveyId);
  const load = () => surveyResults.load(path, path);
  const body = el("div", { class: "stack", "aria-live": "polite" });
  const section = el("section", { class: "srv-survey-results", "aria-label": `설문 ${title}` }, el("h2", { text: title }), body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(loadingState("결과를 불러오는 중입니다"));
      else if (entry.error.status === 404) body.replaceChildren(el("p", { class: "tiny", text: "아직 결과를 볼 수 없습니다." }));
      else body.replaceChildren(errorState("결과를 불러오지 못했습니다", surveyMessage(entry.error), retry(load)));
      return;
    }
    const count = el("p", { class: "tiny", text: `응답 ${data.respondentCount}명 · ${data.anonymous ? "익명" : "실명"} 설문` });
    if (!data.disclosed) {
      body.replaceChildren(count, el("p", { class: "note", text: `${WITHHELD[data.withheldReason] || "아직 결과를 볼 수 없습니다."}${data.withheldReason === "below_threshold" ? ` (최소 ${data.minRespondents}명)` : ""}` }));
      return;
    }
    const questions = Array.isArray(data.questions) ? data.questions : [];
    body.replaceChildren(count, el("ol", { class: "srv-survey-questions" }, ...questions.map((question) => questionResult(courseId, surveyId, question, data.minRespondents))));
  };
  paint(surveyResults.peek(path));
  queueMicrotask(() => {
    if (!section.isConnected) return;
    surveyResults.watch(section, path, paint, load);
    if (freshVisit(`survey-results-${path}`, path) === "entry" || !surveyResults.peek(path)) load();
  });
  return section;
}

function questionResult(courseId, surveyId, question, min) {
  const head = el("p", {}, el("b", { text: `${question.questionNo}. ` }), el("span", { class: "tiny", text: `${QUESTION_TYPE[question.type] || question.type} · 답 ${question.answerCount}명` }));
  const item = el("li", { class: "srv-survey-q" }, head, prose(question.text));
  if (question.suppressed) {
    item.append(el("p", { class: "tiny", text: `답한 사람이 ${min}명보다 적어 이 문항의 집계를 가렸습니다.` }));
    return item;
  }
  if (question.type === "essay") {
    item.append(textAnswers(courseId, surveyId, question));
    return item;
  }
  const total = Math.max(1, question.answerCount);
  const options = Array.isArray(question.options) ? question.options : [];
  item.append(el("ul", { class: "srv-survey-bars" }, ...options.map((option) => {
    const pct = Math.round((option.count / total) * 100);
    const label = question.type === "scale" ? `${option.optionNo} · ${option.text}` : option.text;
    return el("li", {},
      el("span", { class: "srv-survey-bar-label", text: label }),
      el("meter", { min: "0", max: String(total), value: String(option.count), "aria-label": `${label} ${option.count}명` }),
      el("span", { class: "tiny", text: `${option.count}명 (${pct}%)` }),
    );
  })));
  if (question.scaleMean != null) item.append(el("p", { class: "tiny", text: `평균 ${question.scaleMean}` }));
  return item;
}

/** 서술형 답변. 누르면 첫 쪽을 받고, `더 보기`로 다음 쪽을 붙인다. 문구 순서다(입력 순서가 아니다). */
function textAnswers(courseId, surveyId, question) {
  const path = surveyPath(courseId, surveyId);
  const list = el("ul", { class: "srv-survey-texts" });
  const status = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const more = btn(question.answerCount ? "답변 보기" : "답변 없음", {}, "secondary");
  more.disabled = !question.answerCount;
  let page = 0;
  let shown = 0;
  more.addEventListener("click", async () => {
    if (more.disabled) return;
    const next = page + 1;
    const key = textsKey(path, question.questionId, next);
    more.disabled = true;
    more.setAttribute("aria-busy", "true");
    status.textContent = "답변을 불러오는 중입니다.";
    await surveyTexts.load(key, path, question.questionId, next);
    const entry = surveyTexts.peek(key);
    more.removeAttribute("aria-busy");
    if (!entry || !entry.data) {
      status.textContent = surveyMessage(entry && entry.error);
      more.disabled = false;
      return;
    }
    page = next;
    const items = Array.isArray(entry.data.items) ? entry.data.items : [];
    shown += items.length;
    list.append(...items.map((text) => el("li", {}, prose(text) || el("span", { text }))));
    status.textContent = `${entry.data.total}개 중 ${shown}개 (문구 순서)`;
    const left = shown < entry.data.total && items.length === TEXT_PAGE_SIZE;
    more.textContent = "더 보기";
    more.disabled = !left;
    more.hidden = !left;
  });
  return el("div", {}, list, status, more);
}
