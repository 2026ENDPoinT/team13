// 교수의 서버 시험 화면(백엔드 모드): 시험 출제(등록, `revision`으로 설정 수정, 문항 전체 교체, 응시가 생기면
// `questions_locked`), 응시 현황(응시 목록, `cursor`로 다음 쪽), 시험 채점(응시 하나의 답안과 출제 순서의 보기, 단답형 점수와
// 의견), 시험 상세(문항마다 정답·배점 정정과 재채점, 오프라인 시험은 성적부와 같은 점수 표), 삭제(delete-server.js).
// 저장은 professor/actions.js(`pf-quiz-save`, `pf-quiz-questions`, `pf-quiz-grade`, `pf-quiz-more`, `pf-quiz-regrade`)를 거친다.
// 편집 중인 문항과 정답·배점은 세션이 바뀌면 버리는 메모리 맵에만 둔다.
import { el } from "../../dom.js";
import { btn, field, go, badge, emptyState, table } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { sessionDrafts } from "../../shared/session-drafts.js";
import { koDateTime } from "../../shared/home-remote.js";
import { routeFor } from "../../shared/routes.js";
import {
  quizLists, quizDetails, attemptPages, attemptDetails, quizListKey, quizKey, attemptKey, quizIdOf, quizRef,
  createQuiz, updateQuiz, replaceQuestions, gradeAnswer, regradeQuestion, loadMoreAttempts, quizMessage, isConflict,
  PHASE, ATTEMPT_STATUS, FINISH, QUESTION_TYPE, scoreText,
} from "../../shared/quizzes-remote.js";
import { deleteControl } from "./delete-server.js";
import { quizScoreSheet } from "./grades-server.js";

const missing = (error) => error instanceof ApiError && error.status === 404;
const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 시험을 서버에 내고 응시를 채점할 수 있습니다.";

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
const note = (text) => el("p", { class: "pf-sub", text });
const failure = (error, reload) => el("div", {}, el("p", { class: "err", role: "alert", text: quizMessage(error) }), reload ? retry(reload) : null);

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return el("div", { class: "pf-prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function gate(course) {
  if (backendStatus() === "anon") return stack(backendPanel({ text: LOGIN }));
  if (!courseIdFor(course.id)) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목에만 시험을 내고 채점할 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") return stack(backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 시험을 내고 채점할 수 있습니다."));
  return null;
}

const toInput = (value) => (value ? String(value).slice(0, 16) : "");
const fromInput = (value) => (value ? (value.length === 16 ? `${value}:00` : value) : null);
const hundredths = (n) => Math.abs(Math.round(n * 100) - n * 100) < 1e-6;
const periodText = (item) => (item.openAt ? `${koDateTime(item.openAt)} ~ ${koDateTime(item.closeAt)}` : `~ ${koDateTime(item.closeAt)}`);

// ---------- 문항 편집 ----------

// 폼 키("<courseId>:new" 또는 "<courseId>:<quizId>") -> 편집 중인 문항 목록
const drafts = new Map();
// "<courseId>:<quizId>:<questionId>" -> 입력 중인 정답·배점 { points, correct: Set<optionId> }, 그 문항의 재채점 안내 { text, alert }
const keyDrafts = new Map();
const keyNotes = new Map();
const openForms = new Set(); // 펼쳐 둔 정정 폼(같은 키)
// 폼 키 -> 문항 저장 안내 { text, alert }. 저장 뒤 재조회로 문항 칸을 다시 그려도 남고, 세션이 바뀌면 버린다.
const questionNotes = sessionDrafts();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
  keyDrafts.clear();
  keyNotes.clear();
  openForms.clear();
});

const blankOption = () => ({ text: "", correct: false });
const blankQuestion = () => ({ type: "single", text: "", points: "1", explanation: "", options: [blankOption(), blankOption()] });

function fromServer(questions) {
  return (questions || []).map((q) => ({
    type: q.type,
    text: q.text || "",
    points: scoreText(q.points),
    explanation: q.explanation || "",
    options: (q.options || []).map((o) => ({ text: o.text, correct: Boolean(o.correct) })),
  }));
}

/** 문항 편집기. `list`는 메모리의 문항 배열이고 입력할 때마다 바로 바뀐다. */
function questionEditor(list, locked) {
  const root = el("div", { class: "stack", "data-quiz-questions": "" });
  const paint = () => {
    root.replaceChildren(...list.map((q, index) => questionCard(q, index)),
      locked ? "" : el("div", { class: "pf-actions" }, addButton()));
  };
  const addButton = () => {
    const add = btn("문항 추가", {}, "secondary");
    add.addEventListener("click", () => {
      list.push(blankQuestion());
      paint();
      const last = root.querySelectorAll("textarea[data-q-text]");
      if (last.length) last[last.length - 1].focus();
    });
    return add;
  };
  function questionCard(q, index) {
    const type = el("select", { "aria-label": `${index + 1}번 문항 종류` },
      ...Object.entries(QUESTION_TYPE).map(([value, label]) => el("option", { value, text: label, selected: q.type === value ? true : null })));
    const text = el("textarea", { rows: "3", maxlength: "5000", "data-q-text": "", "aria-label": `${index + 1}번 문항` });
    text.value = q.text;
    const points = el("input", { type: "number", min: "0.01", max: "1000", step: "0.01", value: q.points, "aria-label": `${index + 1}번 배점` });
    const explanation = el("textarea", { rows: "2", maxlength: "5000", "aria-label": `${index + 1}번 해설` });
    explanation.value = q.explanation;
    type.addEventListener("change", () => {
      q.type = type.value;
      if (q.type === "short") q.options = [];
      else if (q.options.length < 2) q.options = [blankOption(), blankOption()];
      if (q.type === "single") {
        let seen = false;
        q.options.forEach((o) => { if (o.correct) { o.correct = !seen; seen = true; } });
      }
      paint();
    });
    text.addEventListener("input", () => { q.text = text.value; });
    points.addEventListener("input", () => { q.points = points.value; });
    explanation.addEventListener("input", () => { q.explanation = explanation.value; });
    const options = el("div", { class: "stack" });
    if (q.type !== "short") {
      q.options.forEach((option, at) => {
        const input = el("input", { maxlength: "1000", value: option.text, "aria-label": `${index + 1}번 보기 ${at + 1}` });
        input.addEventListener("input", () => { option.text = input.value; });
        const mark = el("input", { type: q.type === "single" ? "radio" : "checkbox", name: `pf-quiz-correct-${index}` });
        mark.checked = option.correct;
        mark.addEventListener("change", () => {
          if (q.type === "single") q.options.forEach((o) => { o.correct = o === option; });
          else option.correct = mark.checked;
        });
        const drop = btn("빼기", { "aria-label": `${index + 1}번 보기 ${at + 1} 빼기` }, "ghost");
        drop.disabled = q.options.length <= 2;
        drop.addEventListener("click", () => {
          q.options.splice(at, 1);
          paint();
        });
        options.append(el("div", { class: "pf-inline" }, el("label", { class: "choice" }, mark, el("span", { text: "정답" })), input, locked ? null : drop));
      });
      if (!locked && q.options.length < 20) {
        const more = btn("보기 추가", {}, "ghost");
        more.addEventListener("click", () => {
          q.options.push(blankOption());
          paint();
        });
        options.append(more);
      }
    }
    const up = btn("위로", { "aria-label": `${index + 1}번 문항 위로` }, "ghost");
    up.disabled = index === 0;
    up.addEventListener("click", () => {
      list.splice(index - 1, 0, ...list.splice(index, 1));
      paint();
    });
    const remove = btn("문항 빼기", { "aria-label": `${index + 1}번 문항 빼기` }, "ghost");
    remove.disabled = list.length <= 1;
    remove.addEventListener("click", () => {
      list.splice(index, 1);
      paint();
    });
    const card = el("fieldset", { class: "sheet pf-q" },
      el("legend", { text: `${index + 1}번 문항` }),
      el("div", { class: "pf-form" },
        field("종류", type),
        field("배점", points),
        el("div", { class: "span-2" }, field("문항 (일반 텍스트)", text)),
        q.type === "short" ? el("p", { class: "span-2 pf-sub", text: "단답형은 학생이 제출한 뒤 시험 채점 화면에서 점수를 줍니다." }) : el("div", { class: "span-2" }, el("p", { class: "pf-sub", text: q.type === "single" ? "정답 보기를 하나 고르세요." : "정답 보기를 하나 이상 고르세요. 고른 보기 집합이 정답과 같을 때만 배점을 줍니다." }), options),
        el("div", { class: "span-2" }, field("해설 (정답을 공개할 때 학생에게 보입니다)", explanation)),
      ),
      locked ? null : el("div", { class: "pf-actions" }, up, remove),
    );
    if (locked) card.querySelectorAll("input, select, textarea, button").forEach((control) => { control.disabled = true; });
    return card;
  }
  paint();
  return root;
}

/** 편집 중인 문항을 검사해 요청 본문으로. `{ errors }` 또는 `{ questions }`. */
function readQuestions(list) {
  const errors = [];
  if (!list.length || list.length > 200) errors.push("문항은 1개 이상 200개 이하입니다");
  let total = 0;
  const questions = list.map((q, index) => {
    const n = index + 1;
    const points = Number(q.points);
    if (!q.text.trim()) errors.push(`${n}번 문항을 입력하세요`);
    if (q.text.length > 5000 || q.explanation.length > 5000) errors.push(`${n}번 문항과 해설은 5,000자 이하입니다`);
    if (!Number.isFinite(points) || points <= 0 || points > 1000 || !hundredths(points)) errors.push(`${n}번 배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다`);
    else total += points;
    const out = { type: q.type, text: q.text, points, explanation: q.explanation.trim() ? q.explanation : null };
    if (q.type !== "short") {
      if (q.options.length < 2 || q.options.length > 20) errors.push(`${n}번 보기는 2개 이상 20개 이하입니다`);
      if (q.options.some((o) => !o.text.trim())) errors.push(`${n}번 보기 문구를 모두 입력하세요`);
      const correct = q.options.filter((o) => o.correct).length;
      if (q.type === "single" ? correct !== 1 : correct < 1) errors.push(`${n}번 정답 보기를 ${q.type === "single" ? "하나" : "하나 이상"} 고르세요`);
      out.options = q.options.map((o) => ({ text: o.text.trim(), correct: Boolean(o.correct) }));
    } else out.options = [];
    return out;
  });
  if (total > 1000) errors.push("문항 배점의 합은 1000 이하입니다");
  return errors.length ? { errors } : { questions };
}

// ---------- 시험 출제 ----------

/** `pf_exam_create` (백엔드 모드). `?edit=<quizId>`면 그 시험을 고친다. */
export function serverExamFields(ctx, course, parts) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  return stack(
    backendPanel(),
    quizForm(ctx, course, courseId, Number.isInteger(editId) && editId > 0 ? editId : null, parts),
    quizList(course, courseId, "pf_exam_create"),
  );
}

function quizForm(ctx, course, courseId, editId, parts) {
  const { formSheet, span, linkBtn } = parts;
  const input = (name, attrs = {}) => el("input", { "data-field": name, ...attrs });
  const check = (name, on) => {
    const box = input(name, { type: "checkbox" });
    box.checked = on;
    return box;
  };
  const choice = (box, text) => el("label", { class: "choice pf-check" }, box, el("span", { text }));
  const title = input("title", { maxlength: "200" });
  const week = input("weekNo", { type: "number", min: "1", max: "60", value: String(ctx.state.week || 1) });
  const examType = el("select", { "data-field": "examType" }, el("option", { value: "online", text: "온라인 시험" }), el("option", { value: "offline", text: "오프라인(대면) 시험" }));
  const openAt = input("openAt", { type: "datetime-local" });
  const closeAt = input("closeAt", { type: "datetime-local" });
  const minutes = input("timeLimitMinutes", { type: "number", min: "1", max: "1440", placeholder: "비우면 응시 마감까지" });
  const attempts = input("attemptLimit", { type: "number", min: "1", max: "100", placeholder: "비우면 제한 없음" });
  const maxScore = input("maxScore", { type: "number", min: "0.01", max: "1000", step: "0.01", value: "100" });
  const shuffle = check("shuffleQuestions", false);
  const published = check("published", true);
  const releaseAt = input("resultReleaseAt", { type: "datetime-local" });
  const answers = check("answersVisible", false);
  const description = el("textarea", { "data-field": "description", rows: "4", maxlength: "10000" });
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-quiz-status": "" });
  const save = btn(editId ? "설정 저장" : "시험 등록", { "data-act": "pf-quiz-save" }, "primary");
  const onlineOnly = [field("제한 시간(분)", minutes), field("응시 횟수", attempts), span(choice(shuffle, "문항 순서 섞기 (학생마다 고정된 순서)")), span(choice(answers, "결과 공개 뒤 응시 기간이 끝나면 정답·해설·문항별 점수도 보여 주기"))];
  const offlineOnly = [field("배점", maxScore)];
  const questionSlot = el("div", { class: "span-2", "data-quiz-question-slot": "" });
  const sheet = formSheet(editId ? "시험 수정" : "시험",
    span(field("시험명", title)),
    field("주차", week),
    field("시험형태 (등록한 뒤 바꿀 수 없습니다)", examType),
    field("시작 (비우면 바로 시작)", openAt),
    field("응시 마감", closeAt),
    ...onlineOnly,
    ...offlineOnly,
    span(choice(published, "학생에게 공개 (끄면 학생 목록에 나오지 않습니다)")),
    field("결과 공개 일시 (비우면 비공개)", releaseAt),
    span(note("결과 공개 일시가 지나기 전에는 학생이 받는 응답에 점수가 아예 들어가지 않습니다. 응시 마감은 시작할 때 min(시작 + 제한 시간, 응시 마감)으로 서버가 고정합니다.")),
    span(field("안내 (일반 텍스트)", description)),
    span(status),
    span(el("div", { "data-quiz-latest": "" })),
    span(el("div", { class: "pf-actions" }, save, editId ? linkBtn(pf("pf_exam_create", { kj: course.id }), "새 시험 쓰기") : null,
      editId ? linkBtn(pf("pf_exam_status", { kj: course.id, n: quizRef(editId) }), "응시 현황") : null)),
    editId ? span(el("div", { "data-quiz-delete": "" })) : null,
    questionSlot,
  );
  sheet.dataset.quizForm = "";
  sheet.dataset.course = String(courseId);
  sheet.dataset.kj = course.id;
  sheet.dataset.edit = editId ? String(editId) : "";
  const formKey = `${courseId}:${editId || "new"}`;
  const toggle = () => {
    const online = examType.value !== "offline";
    onlineOnly.forEach((node) => { node.hidden = !online; });
    offlineOnly.forEach((node) => { node.hidden = online; });
    questionSlot.hidden = !online;
  };
  examType.addEventListener("change", toggle);

  if (!editId) {
    if (!drafts.has(formKey)) drafts.set(formKey, [blankQuestion()]);
    questionSlot.append(el("h3", { text: "문항" }), questionEditor(drafts.get(formKey), false));
    toggle();
    return sheet;
  }

  examType.disabled = true;
  const key = quizKey(courseId, editId);
  const controls = () => [...sheet.querySelectorAll("input, textarea, button, select")].filter((node) => node !== examType);
  let filled = false;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.revision == null) {
      if (filled) return;
      controls().forEach((control) => { control.disabled = true; });
      status.setAttribute("role", entry && entry.error ? "alert" : "status");
      status.textContent = !entry || !entry.error ? "시험을 불러오는 중입니다."
        : missing(entry.error) ? "시험을 찾을 수 없습니다. 이 과목의 시험이 아닙니다." : quizMessage(entry.error);
      return;
    }
    sheet.dataset.revision = String(data.revision);
    const info = data.quiz;
    sheet.querySelector("[data-quiz-delete]").replaceChildren(deleteControl("quiz", courseId, editId, { title: info.title, back: pf("pf_exam_create", { kj: course.id }) }));
    if (!filled) {
      filled = true;
      title.value = info.title;
      week.value = String(info.weekNo);
      examType.value = info.examType;
      openAt.value = toInput(info.openAt);
      closeAt.value = toInput(info.closeAt);
      minutes.value = info.timeLimitMinutes ?? "";
      attempts.value = info.attemptLimit ?? "";
      maxScore.value = scoreText(info.maxScore);
      shuffle.checked = Boolean(info.shuffleQuestions);
      published.checked = Boolean(info.published);
      releaseAt.value = toInput(info.resultReleaseAt);
      answers.checked = Boolean(info.answersVisible);
      description.value = info.description || "";
      controls().forEach((control) => { control.disabled = false; });
      toggle();
      status.setAttribute("role", "status");
    }
    // 저장 실패 안내(role=alert)는 재조회로 다시 그려도 지우지 않는다. 다음 저장이 바꾼다.
    if (status.getAttribute("role") !== "alert") {
      status.setAttribute("role", "status");
      status.textContent = `수정 번호 ${data.revision} · ${PHASE[data.phase] || data.phase} · 응시 중 ${data.inProgressCount}명 · 종료 ${data.finishedCount}건 · 채점 대기 ${data.awaitingGradingCount}건`;
    }
    if (info.examType === "offline") return;
    // 문항 편집기: 응시가 생기면 잠근다. 서버 문항으로 처음 한 번만 채우고, 문항 저장 뒤에는 서버 값으로 다시 채운다.
    // 잠긴 시험은 바꿀 수 없으므로 언제나 서버 문항을 보인다. 응시 시작과 거절된 문항 저장은 `revision`을 올리지 않아
    // (백엔드 QuizService.replaceQuestions는 409 `questions_locked`를 던져 올린 번호를 되돌린다) 번호로는 알 수 없다.
    const locked = Boolean(data.questionsLocked);
    if (!drafts.has(formKey) || locked || sheet.dataset.questionsFrom !== String(data.revision) && !sheet.dataset.questionsTouched) {
      drafts.set(formKey, fromServer(data.questions));
    }
    sheet.dataset.questionsFrom = String(data.revision);
    const saveQuestions = btn("문항 저장", { "data-act": "pf-quiz-questions" }, "primary");
    saveQuestions.disabled = locked;
    const editor = questionEditor(drafts.get(formKey), locked);
    editor.addEventListener("input", () => { sheet.dataset.questionsTouched = "1"; });
    editor.addEventListener("click", () => { sheet.dataset.questionsTouched = "1"; });
    questionSlot.replaceChildren(
      el("h3", { text: `문항 ${data.questions.length}개 · 배점 합 ${scoreText(info.maxScore)}점` }),
      locked ? el("p", { class: "pf-sub", role: "note", "data-questions-locked": "", text: "응시가 시작된 시험이라 문항과 보기를 바꿀 수 없습니다. 설정(기간, 제한 시간, 응시 횟수, 결과 공개)은 바꿀 수 있고, 진행 중인 응시의 마감 일시는 그대로입니다." }) : note("문항 저장은 문항 전체를 이 목록으로 바꿉니다. 응시가 하나라도 생기면 더 바꿀 수 없습니다."),
      editor,
      questionStatus(questionNotes.get(formKey)),
      locked ? "" : el("div", { class: "pf-actions" }, saveQuestions),
    );
  };
  paint(quizDetails.peek(key));
  attached(sheet, () => {
    quizDetails.watch(sheet, key, paint, null);
    if (freshVisit("pf-quiz-edit", key) === "entry" || !quizDetails.peek(key)) quizDetails.load(key, courseId, editId);
  });
  return sheet;
}

/** 설정 폼을 읽고 검사한다. `{ errors }` 또는 `{ payload }`. */
function readSettings(sheet) {
  const get = (name) => sheet.querySelector(`[data-field="${name}"]`);
  const errors = [];
  const online = get("examType").value !== "offline";
  const title = get("title").value.trim();
  const weekNo = Number(get("weekNo").value);
  const openAt = fromInput(get("openAt").value);
  const closeAt = fromInput(get("closeAt").value);
  const minutesRaw = get("timeLimitMinutes").value.trim();
  const attemptsRaw = get("attemptLimit").value.trim();
  const minutes = minutesRaw ? Number(minutesRaw) : null;
  const attempts = attemptsRaw ? Number(attemptsRaw) : null;
  const maxScore = Number(get("maxScore").value);
  const description = get("description").value;
  if (!title || title.length > 200) errors.push("시험명은 1자 이상 200자 이하입니다");
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
  if (!closeAt) errors.push("응시 마감 일시를 입력하세요");
  if (openAt && closeAt && openAt >= closeAt) errors.push("시작은 응시 마감보다 앞이어야 합니다");
  if (online && minutes != null && (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440)) errors.push("제한 시간은 1분부터 1440분 사이입니다");
  if (online && attempts != null && (!Number.isInteger(attempts) || attempts < 1 || attempts > 100)) errors.push("응시 횟수는 1부터 100 사이입니다");
  if (!online && (!Number.isFinite(maxScore) || maxScore <= 0 || maxScore > 1000 || !hundredths(maxScore))) errors.push("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다");
  if (description.length > 10000) errors.push("안내는 10,000자 이하입니다");
  if (errors.length) return { errors };
  return {
    online,
    payload: {
      weekNo,
      title,
      description: description.trim() ? description : null,
      openAt,
      closeAt,
      published: get("published").checked,
      timeLimitMinutes: online ? minutes : null,
      attemptLimit: online ? attempts : null,
      shuffleQuestions: online ? get("shuffleQuestions").checked : false,
      resultReleaseAt: fromInput(get("resultReleaseAt").value),
      answersVisible: online ? get("answersVisible").checked : false,
      maxScore: online ? null : maxScore,
    },
  };
}

function sayIn(sheet, selector) {
  const status = sheet.querySelector(selector);
  return (text, alert) => {
    if (!status) return;
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = text;
  };
}

/** 문항 저장 안내 줄. 문항 칸을 다시 그릴 때 남겨 둔 안내 `note`를 다시 쓴다. */
function questionStatus(note) {
  return el("p", { class: "pf-sub", role: note && note.alert ? "alert" : "status", "aria-live": "polite", "data-quiz-questions-status": "", text: note ? note.text : "" });
}

function busy(sheet, node, on, label) {
  sheet.querySelectorAll("input, textarea, button, select").forEach((control) => {
    if (on) {
      control.dataset.wasDisabled = control.disabled ? "1" : "";
      control.disabled = true;
    } else if (control.dataset.wasDisabled !== undefined) {
      // 요청 중에 다시 그린 컨트롤(잠긴 문항 편집기 등)은 자기 상태를 그대로 둔다.
      control.disabled = control.dataset.wasDisabled === "1";
      delete control.dataset.wasDisabled;
    }
  });
  if (on) sheet.setAttribute("aria-busy", "true");
  else sheet.removeAttribute("aria-busy");
  node.textContent = label;
}

/** `pf-quiz-save`: 등록(문항 포함) 또는 읽은 `revision`으로 설정 수정. */
export async function saveQuizForm(node, api) {
  const sheet = node.closest("[data-quiz-form]");
  if (!sheet || node.disabled) return;
  const say = sayIn(sheet, "[data-quiz-status]");
  const { errors, payload, online } = readSettings(sheet);
  if (errors) return say(errors.join(". "), true);
  const courseId = Number(sheet.dataset.course);
  const editId = Number(sheet.dataset.edit) || null;
  const formKey = `${courseId}:${editId || "new"}`;
  let body = payload;
  if (!editId) {
    body = { ...payload, examType: online ? "online" : "offline" };
    if (online) {
      const read = readQuestions(drafts.get(formKey) || []);
      if (read.errors) return say(read.errors.join(". "), true);
      body.questions = read.questions;
    }
  }
  const label = node.textContent;
  busy(sheet, node, true, "저장하는 중…");
  say("ASTRA 서버에 저장하고 있습니다.");
  try {
    if (editId) {
      const updated = await updateQuiz(courseId, editId, { ...body, revision: Number(sheet.dataset.revision) });
      api.getState().toast = "시험 설정을 저장했습니다";
      api.paintToast();
      sheet.dataset.revision = String(updated.revision);
      quizDetails.load(quizKey(courseId, editId), courseId, editId);
    } else {
      const created = await createQuiz(courseId, body);
      drafts.delete(formKey);
      api.getState().toast = "시험을 서버에 등록했습니다";
      api.navigate(pf("pf_exam_create", { kj: sheet.dataset.kj, edit: String(created.quiz.quizId) }));
      return;
    }
  } catch (error) {
    say(quizMessage(error), true);
    if (editId && isConflict(error, "edit_conflict")) await showLatest(sheet, courseId, editId);
    else if (!sheet.isConnected) {
      api.getState().toast = quizMessage(error);
      api.paintToast();
    }
  } finally {
    if (sheet.isConnected) busy(sheet, node, false, label);
  }
}

/** `pf-quiz-questions`: 문항 전체 교체(읽은 `revision`). 409 `questions_locked`면 편집기를 잠근다. */
export async function saveQuizQuestions(node, api) {
  const sheet = node.closest("[data-quiz-form]");
  if (!sheet || node.disabled) return;
  const courseId = Number(sheet.dataset.course);
  const editId = Number(sheet.dataset.edit);
  const formKey = `${courseId}:${editId}`;
  // 안내는 `questionNotes`에 두고 지금 문서의 줄에 쓴다. 재조회가 문항 칸을 다시 그려도 새 줄이 같은 안내를 보인다.
  const say = (text, alert) => {
    questionNotes.set(formKey, { text, alert: Boolean(alert) });
    const status = sheet.querySelector("[data-quiz-questions-status]");
    if (!status) return;
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = text;
  };
  const read = readQuestions(drafts.get(formKey) || []);
  if (read.errors) return say(read.errors.join(". "), true);
  const label = node.textContent;
  busy(sheet, node, true, "저장하는 중…");
  say("문항을 저장하고 있습니다.");
  try {
    await replaceQuestions(courseId, editId, sheet.dataset.revision, read.questions);
    delete sheet.dataset.questionsTouched;
    drafts.delete(formKey);
    questionNotes.delete(formKey);
    api.getState().toast = "문항을 저장했습니다";
    api.paintToast();
    await quizDetails.load(quizKey(courseId, editId), courseId, editId);
  } catch (error) {
    say(quizMessage(error), true);
    if (isConflict(error, "questions_locked")) {
      delete sheet.dataset.questionsTouched;
      await quizDetails.load(quizKey(courseId, editId), courseId, editId);
      say(quizMessage(error), true);
    } else if (isConflict(error, "edit_conflict")) {
      await showLatest(sheet, courseId, editId);
      say("다른 곳에서 이 시험을 먼저 수정했습니다. 새 수정 번호를 받았습니다. 편집한 문항은 그대로 두었으니 확인한 뒤 다시 저장하세요.", true);
    }
  } finally {
    if (sheet.isConnected) busy(sheet, node, false, label);
  }
}

/** 409 `edit_conflict` 뒤: 최신 시험을 받아 수정 번호를 바꾸고 입력한 폼 옆에 보여 준다. */
async function showLatest(sheet, courseId, editId) {
  const key = quizKey(courseId, editId);
  await quizDetails.load(key, courseId, editId);
  const entry = quizDetails.peek(key);
  const latest = sheet.querySelector("[data-quiz-latest]");
  if (!sheet.isConnected || !latest) return;
  if (!entry || !entry.data) {
    latest.replaceChildren(note(`최신 내용을 불러오지 못했습니다. ${quizMessage(entry && entry.error)}`));
    return;
  }
  const { quiz: info, revision } = entry.data;
  sheet.dataset.revision = String(revision);
  latest.replaceChildren(el("details", { class: "pf-server-pdf", open: true },
    el("summary", {}, el("b", { text: "서버의 최신 내용" }), el("span", { class: "pf-meta", text: ` · 수정 번호 ${revision}` })),
    note(`${info.title} · ${info.weekNo}주 · ${periodText(info)} · 제한 ${info.timeLimitMinutes ? `${info.timeLimitMinutes}분` : "없음"} · 응시 ${info.attemptLimit ? `${info.attemptLimit}회` : "제한 없음"} · 결과 공개 ${info.resultReleaseAt ? koDateTime(info.resultReleaseAt) : "비공개"}`),
  ));
}

/** 이 과목의 서버 시험 목록. `target`은 행의 주 링크가 가는 화면이다. */
function quizList(course, courseId, target) {
  const key = quizListKey(courseId);
  const load = () => quizLists.load(key, courseId);
  const list = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "ASTRA 서버에 등록된 시험" }, el("h2", { text: "서버에 등록된 시험" }), list);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      list.replaceChildren(!entry || !entry.error ? note("서버에 등록된 시험을 불러오는 중입니다.") : failure(entry.error, load));
      return;
    }
    const items = Array.isArray(data) ? data : [];
    if (!items.length) {
      list.replaceChildren(note("아직 서버에 등록된 시험이 없습니다."));
      return;
    }
    list.replaceChildren(...items.map((item) => {
      const ref = quizRef(item.quizId);
      const edit = pf("pf_exam_create", { kj: course.id, edit: String(item.quizId) });
      const status = pf("pf_exam_status", { kj: course.id, n: ref });
      const grading = pf("pf_exam_grade", { kj: course.id, n: ref });
      const href = target === "pf_exam_grade" ? grading : target === "pf_exam_status" ? status : target === "pf_quiz" ? pf("pf_quiz", { kj: course.id, n: ref }) : edit;
      return el("div", { class: "pf-activity" },
        el("a", { class: "pf-activity-main", href, "data-go": href },
          badge(item.published ? PHASE[item.phase] || item.phase : "비공개", item.phase === "open" ? "success" : "neutral"),
          el("span", { class: "pf-activity-copy" },
            el("b", { text: item.title }),
            el("span", { class: "pf-meta", text: `${item.weekNo}주 · ${item.examType === "offline" ? "오프라인" : "온라인"} · ${periodText(item)} · 응시 중 ${item.inProgressCount ?? 0} · 종료 ${item.finishedCount ?? 0} · 채점 대기 ${item.awaitingGradingCount ?? 0}` }),
          ),
        ),
        el("div", { class: "pf-activity-actions" },
          go(edit, "수정", { class: "btn btn-ghost", "aria-label": `${item.title} 수정` }),
          item.examType === "offline"
            ? go(pf("pf_quiz", { kj: course.id, n: ref }), "점수 입력", { class: "btn btn-ghost", "aria-label": `${item.title} 점수 입력` })
            : go(status, "응시 현황", { class: "btn btn-ghost", "aria-label": `${item.title} 응시 현황` }),
        ),
      );
    }));
  };
  paint(quizLists.peek(key));
  attached(section, () => {
    quizLists.watch(section, key, paint, load);
    if (freshVisit(`pf-quizzes-${target}`, key) === "entry" || !quizLists.peek(key)) load();
  });
  return section;
}

// ---------- 응시 현황 / 시험 채점 ----------

/** `pf_exam_status` (백엔드 모드). `?n=srv-<quizId>`면 그 시험의 응시 목록. */
export function serverExamStatus(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const quizId = quizIdOf(ctx.params && ctx.params.get("n"));
  if (!quizId) return stack(backendPanel(), quizList(course, courseId, "pf_exam_status"));
  return stack(backendPanel(), quizHeader(course, courseId, quizId, "pf_exam_status"), attemptTable(course, courseId, quizId));
}

/**
 * `pf_exam_grade` (백엔드 모드): 시험 → 응시 → 답안 채점. 응시 하나를 볼 때는 시험 상세를 받지 않는다(답안의 `options`가
 * 출제 순서의 보기와 정답·학생 선택을 함께 준다).
 */
export function serverExamGrade(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const quizId = quizIdOf(ctx.params && ctx.params.get("n"));
  if (!quizId) return stack(backendPanel(), quizList(course, courseId, "pf_exam_grade"));
  const attemptId = Number((ctx.params && ctx.params.get("attempt")) || "");
  if (!Number.isInteger(attemptId) || attemptId < 1) return stack(backendPanel(), quizHeader(course, courseId, quizId, "pf_exam_grade"), attemptTable(course, courseId, quizId));
  return stack(backendPanel(), attemptView(course, courseId, quizId, attemptId));
}

/** 시험 정보와 수정·응시 목록·삭제. `screen`은 삭제한 뒤 돌아갈 목록 화면이다. */
function quizHeader(course, courseId, quizId, screen) {
  const key = quizKey(courseId, quizId);
  const load = () => quizDetails.load(key, courseId, quizId);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "시험 정보" }, body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.revision == null) {
      if (!entry || !entry.error) body.replaceChildren(note("시험을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("시험을 찾을 수 없습니다", "이 과목의 시험이 아니거나 삭제되었습니다.", go(pf("pf_exam_create", { kj: course.id }), "시험 목록", { class: "btn btn-secondary" })));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    const info = data.quiz;
    const offline = info.examType === "offline";
    body.replaceChildren(
      el("h2", { text: info.title }),
      note(`${info.weekNo}주 · ${offline ? "오프라인" : "온라인"} · ${periodText(info)} · ${PHASE[data.phase] || data.phase} · 배점 ${scoreText(info.maxScore)}점${offline ? "" : ` · 문항 ${info.questionCount}개`}`),
      offline
        ? note(`결과 공개 ${info.resultReleaseAt ? `${koDateTime(info.resultReleaseAt)} 이후` : "비공개"} · 수정 번호 ${data.revision}`)
        : note(`응시 중 ${data.inProgressCount}명 · 종료 ${data.finishedCount}건 · 채점 대기 ${data.awaitingGradingCount}건 · 결과 공개 ${info.resultReleaseAt ? `${koDateTime(info.resultReleaseAt)} 이후` : "비공개"}${data.questionsLocked ? " · 문항 잠김" : ""}`),
      el("div", { class: "pf-actions" },
        go(pf("pf_exam_create", { kj: course.id, edit: String(quizId) }), "시험 수정", { class: "btn btn-ghost" }),
        offline
          ? go(pf("pf_quiz", { kj: course.id, n: quizRef(quizId) }), "점수 입력", { class: "btn btn-ghost" })
          : go(pf("pf_exam_status", { kj: course.id, n: quizRef(quizId) }), "응시 목록", { class: "btn btn-ghost" }),
      ),
      deleteControl("quiz", courseId, quizId, { title: info.title, back: pf(screen, { kj: course.id }) }),
    );
  };
  paint(quizDetails.peek(key));
  attached(section, () => {
    quizDetails.watch(section, key, paint, load);
    if (freshVisit("pf-quiz-head", key) === "entry" || !quizDetails.peek(key)) load();
  });
  return section;
}

function attemptTable(course, courseId, quizId) {
  const key = quizKey(courseId, quizId);
  const load = () => attemptPages.load(key, courseId, quizId, null);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "응시 목록" }, el("h2", { text: "응시 목록" }), body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(note("응시 목록을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("응시 목록을 볼 수 없습니다", "이 과목의 시험이 아니거나 삭제되었습니다."));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    if (!data.items.length) {
      body.replaceChildren(note("아직 응시한 학생이 없습니다."));
      return;
    }
    const more = btn(entry.loading ? "불러오는 중…" : "더 보기", { "data-act": "pf-quiz-more", "data-course": String(courseId), "data-quiz": String(quizId) }, "secondary");
    more.disabled = Boolean(entry.loading);
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: quizMessage(entry.error) }) : "",
      table(["학생", "회차", "상태", "시작", "접수", "총점", ""], data.items.map((item) => {
        const href = pf("pf_exam_grade", { kj: course.id, n: quizRef(quizId), attempt: String(item.attemptId) });
        return [
          item.studentName || "이름 없음",
          `${item.attemptNo}회`,
          badge(item.pendingCount ? `채점 대기 ${item.pendingCount}` : ATTEMPT_STATUS[item.status] || item.status, item.status === "graded" ? "success" : "neutral"),
          koDateTime(item.startedAt),
          item.submittedAt ? `${koDateTime(item.submittedAt)} · ${FINISH[item.finishReason] || ""}` : `마감 ${koDateTime(item.deadlineAt)}`,
          item.totalScore != null ? `${scoreText(item.totalScore)}점` : "—",
          go(href, item.status === "in_progress" ? "답안 보기" : "보기·채점", { class: "btn btn-secondary", "aria-label": `${item.studentName || "학생"} ${item.attemptNo}회차 보기` }),
        ];
      })),
      el("p", { class: "pf-sub", text: `${data.items.length}건 표시${data.nextCursor ? " · 더 있습니다" : ""}` }),
      data.nextCursor ? el("div", { class: "pf-actions" }, more) : "",
    );
  };
  paint(attemptPages.peek(key));
  attached(section, () => {
    attemptPages.watch(section, key, paint, load);
    if (freshVisit("pf-quiz-attempts", key) === "entry" || !attemptPages.peek(key)) load();
  });
  return section;
}

/** `pf-quiz-more`: 응시 목록의 다음 쪽. */
export function moreAttempts(node) {
  if (node.disabled) return;
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  void loadMoreAttempts(Number(node.dataset.course), Number(node.dataset.quiz));
}

const GRADING = { auto: "자동 채점", pending: "채점 대기", manual: "직접 채점" };

function attemptView(course, courseId, quizId, attemptId) {
  const key = attemptKey(courseId, quizId, attemptId);
  const load = () => attemptDetails.load(key, courseId, quizId, attemptId);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "응시 답안" }, body);
  const typed = new Map(); // questionId -> { score, feedback } 입력 중인 값
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.answers == null) {
      if (!entry || !entry.error) body.replaceChildren(note("답안을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("응시를 찾을 수 없습니다", "이 시험의 응시가 아닙니다."));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    const closed = data.status !== "in_progress";
    body.replaceChildren(
      el("h2", { text: `${data.studentName || "학생"} · ${data.attemptNo}회차 · ${ATTEMPT_STATUS[data.status] || data.status}` }),
      note(`시작 ${koDateTime(data.startedAt)} · ${data.submittedAt ? `접수 ${koDateTime(data.submittedAt)} (${FINISH[data.finishReason] || ""})` : `마감 ${koDateTime(data.deadlineAt)}`} · 총점 ${data.totalScore != null ? `${scoreText(data.totalScore)}점` : "확정 전"}`),
      closed ? "" : note("아직 응시 중입니다. 제출하거나 마감된 뒤에 채점할 수 있습니다."),
      ...data.answers.map((answer) => answerCard(course, courseId, quizId, attemptId, answer, closed, typed)),
      el("div", { class: "pf-actions" },
        go(pf("pf_exam_grade", { kj: course.id, n: quizRef(quizId) }), "응시 목록", { class: "btn btn-ghost" }),
        go(pf("pf_quiz", { kj: course.id, n: quizRef(quizId) }), "시험 상세·재채점", { class: "btn btn-ghost" }),
      ),
    );
  };
  paint(attemptDetails.peek(key));
  attached(section, () => {
    attemptDetails.watch(section, key, paint, load);
    if (freshVisit("pf-quiz-attempt", key) === "entry" || !attemptDetails.peek(key)) load();
  });
  return section;
}

/** 답안 하나. 선택 문항은 서버가 준 `options`(출제 순서, 정답 여부, 학생 선택)를 그대로 그린다. */
function answerCard(course, courseId, quizId, attemptId, answer, closed, typed) {
  const head = el("div", { class: "pf-inline" },
    badge(QUESTION_TYPE[answer.type] || answer.type, "neutral"),
    badge(`${scoreText(answer.points)}점`, "neutral"),
    answer.gradingStatus ? badge(GRADING[answer.gradingStatus] || answer.gradingStatus, answer.gradingStatus === "pending" ? "danger" : "success") : null,
    answer.awardedScore != null ? badge(`받은 점수 ${scoreText(answer.awardedScore)}`, "neutral") : null,
  );
  const card = el("article", { class: "sheet pf-q" }, head, el("h3", { text: `${answer.number}번` }), prose(answer.text));
  const fix = go(pf("pf_quiz", { kj: course.id, n: quizRef(quizId), q: String(answer.questionId) }), "정답·배점 고치기", { class: "btn btn-ghost", "aria-label": `${answer.number}번 정답·배점 고치기` });
  if (answer.type !== "short") {
    const options = Array.isArray(answer.options) ? answer.options : [];
    card.append(
      options.length
        ? el("ul", { class: "pf-grading-options" }, ...options.map((o) => el("li", { "data-option": String(o.optionId) },
          el("span", { text: `${o.number}. ${o.text}` }),
          o.selected ? badge("학생 선택", "neutral") : null,
          o.correct ? badge("정답", "success") : null,
        )))
        : null,
      note(options.some((o) => o.selected) ? `${answer.correct == null ? "채점 전" : answer.correct ? "정답" : "오답"}` : "답하지 않았습니다."),
      el("div", { class: "pf-actions" }, fix),
    );
    return card;
  }
  card.append(el("div", {}, el("span", { class: "pf-meta", text: "학생 답" }), answer.answerText ? prose(answer.answerText) : note("답하지 않았습니다.")));
  if (!closed) return card;
  const memo = typed.get(answer.questionId) || { score: answer.awardedScore != null ? scoreText(answer.awardedScore) : "", feedback: answer.feedback || "" };
  typed.set(answer.questionId, memo);
  const score = el("input", { type: "number", min: "0", max: scoreText(answer.points), step: "0.01", "data-field": "score", value: memo.score, "aria-label": `${answer.number}번 점수 (0~${scoreText(answer.points)})` });
  const feedback = el("textarea", { rows: "3", maxlength: "1000", "data-field": "feedback", "aria-label": `${answer.number}번 의견` });
  feedback.value = memo.feedback;
  score.addEventListener("input", () => { memo.score = score.value; });
  feedback.addEventListener("input", () => { memo.feedback = feedback.value; });
  card.append(el("div", {
    class: "pf-form",
    "data-quiz-grade": "",
    "data-course": String(courseId),
    "data-quiz": String(quizId),
    "data-attempt": String(attemptId),
    "data-question": String(answer.questionId),
    "data-max": scoreText(answer.points),
  },
  field(`점수 (0~${scoreText(answer.points)})`, score),
  el("div", { class: "span-2" }, field("의견 (일반 텍스트, 1,000자까지)", feedback)),
  el("p", { class: "span-2 pf-sub", role: "status", "aria-live": "polite", "data-grade-status": "", text: answer.gradedAt ? `${koDateTime(answer.gradedAt)}에 채점했습니다.` : "" }),
  el("div", { class: "span-2 pf-actions" }, btn("점수 저장", { "data-act": "pf-quiz-grade" }, "primary"), fix),
  ));
  return card;
}

/** `pf-quiz-grade`: 문항 배점 안의 점수와 일반 텍스트 의견. 응답의 응시 상세로 화면을 바꾼다. */
export async function saveQuizGrade(node, api) {
  const form = node.closest("[data-quiz-grade]");
  if (!form || node.disabled) return;
  const status = form.querySelector("[data-grade-status]");
  const say = (text, alert) => {
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = text;
  };
  const raw = form.querySelector('[data-field="score"]').value.trim();
  const feedback = form.querySelector('[data-field="feedback"]').value;
  const max = Number(form.dataset.max);
  const score = Number(raw);
  if (raw === "" || !Number.isFinite(score) || score < 0 || !(score <= max) || !hundredths(score)) {
    return say(`점수는 0부터 ${scoreText(max)}까지, 소수 둘째 자리까지 입력하세요.`, true);
  }
  if (feedback.length > 1000) return say("의견은 1,000자 이하입니다.", true);
  const controls = [...form.querySelectorAll("input, textarea, button")];
  controls.forEach((control) => { control.disabled = true; });
  node.textContent = "저장하는 중…";
  say("점수를 저장하고 있습니다.");
  try {
    const detail = await gradeAnswer(Number(form.dataset.course), Number(form.dataset.quiz), Number(form.dataset.attempt), Number(form.dataset.question), {
      score,
      feedback: feedback.trim() ? feedback : null,
    });
    api.getState().toast = detail.status === "graded" ? `점수를 저장했습니다. 총점 ${scoreText(detail.totalScore)}점으로 확정했습니다` : "점수를 저장했습니다";
    api.paintToast();
  } catch (error) {
    say(quizMessage(error), true);
  } finally {
    if (form.isConnected) {
      controls.forEach((control) => { control.disabled = false; });
      node.textContent = "점수 저장";
    }
  }
}

// ---------- 시험 상세 (`pf_quiz`) ----------

/**
 * `pf_quiz`(백엔드 모드). `?n=srv-<quizId>`면 그 시험의 정보와 문항(정답 표시, 문항마다 정답·배점 정정과 재채점), 없으면
 * 시험 목록. `?q=<questionId>`면 그 문항의 정정 폼을 펼친다. 오프라인 시험은 문항 대신 점수 표(`?page=`)를 보인다.
 */
export function serverQuizDetail(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const quizId = quizIdOf(ctx.params && ctx.params.get("n"));
  if (!quizId) return stack(backendPanel(), quizList(course, courseId, "pf_quiz"));
  const focus = Number((ctx.params && ctx.params.get("q")) || "") || null;
  const page = Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
  return stack(backendPanel(), quizHeader(course, courseId, quizId, "pf_quiz"), questionSheet(course, courseId, quizId, focus, page));
}

function questionSheet(course, courseId, quizId, focus, page) {
  const key = quizKey(courseId, quizId);
  const load = () => quizDetails.load(key, courseId, quizId);
  const body = el("div", { class: "stack" });
  const heading = el("h2", { text: "문항" });
  const section = el("section", { class: "sheet", "aria-label": "시험 문항" }, heading, body);
  let scores = null; // 오프라인 시험의 점수 표. 상세가 다시 와도 같은 노드를 쓴다(입력 중인 칸이 남게).
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.revision == null) {
      if (!entry || !entry.error) body.replaceChildren(note("문항을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(note("이 시험의 문항을 볼 수 없습니다."));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    const questions = Array.isArray(data.questions) ? data.questions : [];
    if (data.quiz.examType === "offline") {
      heading.textContent = "점수 입력";
      section.setAttribute("aria-label", "오프라인 시험 점수");
      scores = scores || quizScoreSheet(course, courseId, quizId, page, (n) => pf("pf_quiz", { kj: course.id, n: quizRef(quizId), ...(n > 1 ? { page: String(n) } : {}) }));
      body.replaceChildren(note(`오프라인 시험이라 서버에 문항이 없습니다. 학생별 점수(배점 ${scoreText(data.quiz.maxScore)}점)와 의견을 아래 표에 입력합니다.`), scores);
      return;
    }
    if (!questions.length) {
      body.replaceChildren(emptyState("문항이 없습니다", "시험 수정에서 문항을 넣으세요.", go(pf("pf_exam_create", { kj: course.id, edit: String(quizId) }), "시험 수정", { class: "btn btn-secondary" })));
      return;
    }
    body.replaceChildren(
      data.questionsLocked ? note("응시가 있어 지문과 보기는 잠겨 있습니다. 정답과 배점은 문항마다 고칠 수 있고, 고치면 제출을 마친 응시를 다시 채점합니다.") : "",
      ...questions.map((q) => el("article", { class: "sheet pf-q", id: `pf-q-${q.questionId}` },
        el("div", { class: "pf-inline" }, badge(`${q.number}번`, "neutral"), badge(QUESTION_TYPE[q.type] || q.type, "neutral"), badge(`${scoreText(q.points)}점`, "neutral")),
        prose(q.text),
        Array.isArray(q.options) && q.options.length
          ? el("ol", {}, ...q.options.map((option) => el("li", {}, option.text, option.correct ? " " : null, option.correct ? badge("정답", "success") : null)))
          : note("단답형 문항은 응시한 뒤 시험 채점에서 점수를 줍니다."),
        q.explanation ? el("div", {}, el("p", { class: "pf-kicker", text: "해설" }), prose(q.explanation)) : null,
        answerKeyForm(courseId, quizId, q, focus === q.questionId),
      )),
    );
  };
  paint(quizDetails.peek(key));
  attached(section, () => {
    quizDetails.watch(section, key, paint, load);
    if (!quizDetails.peek(key)) load();
    if (focus) {
      const target = document.getElementById(`pf-q-${focus}`);
      if (target) target.scrollIntoView({ block: "start" });
    }
  });
  return section;
}

// ---------- 정답·배점 정정(재채점) ----------

const keyOf = (courseId, quizId, questionId) => `${courseId}:${quizId}:${questionId}`;

/** 문항 하나의 정답·배점 정정 폼. 입력 중인 값과 안내는 다시 그려도 남고, 저장에 성공하면 서버 값으로 돌아간다. */
function answerKeyForm(courseId, quizId, q, open) {
  const key = keyOf(courseId, quizId, q.questionId);
  const options = Array.isArray(q.options) ? q.options : [];
  const memo = keyDrafts.get(key) || { points: scoreText(q.points), correct: new Set(options.filter((o) => o.correct).map((o) => o.optionId)) };
  const touch = () => keyDrafts.set(key, memo);
  const points = el("input", { type: "number", min: "0.01", max: "1000", step: "0.01", value: memo.points, "data-field": "points", "aria-label": `${q.number}번 배점` });
  points.addEventListener("input", () => {
    memo.points = points.value;
    touch();
  });
  const choice = q.type !== "short";
  const list = el("div", { class: "stack" }, ...options.map((o, at) => {
    const mark = el("input", { type: q.type === "single" ? "radio" : "checkbox", name: `pf-key-${quizId}-${q.questionId}`, "data-option": String(o.optionId) });
    mark.checked = memo.correct.has(o.optionId);
    mark.addEventListener("change", () => {
      if (q.type === "single") memo.correct = new Set([o.optionId]);
      else if (mark.checked) memo.correct.add(o.optionId);
      else memo.correct.delete(o.optionId);
      touch();
    });
    return el("label", { class: "choice" }, mark, el("span", { text: `${at + 1}. ${o.text}` }));
  }));
  const said = keyNotes.get(key);
  const status = el("p", {
    class: said && said.alert ? "err span-2" : "pf-sub span-2",
    role: said && said.alert ? "alert" : "status",
    "aria-live": "polite",
    "data-key-status": key,
    text: said ? said.text : "",
  });
  const box = el("details", { class: "pf-regrade", open: open || said || openForms.has(key) ? true : null },
    el("summary", { text: "정답·배점 고치기 (재채점)" }),
    el("div", {
      class: "pf-form",
      "data-quiz-key": "",
      "data-course": String(courseId),
      "data-quiz": String(quizId),
      "data-question": String(q.questionId),
      "data-type": q.type,
      "data-number": String(q.number),
    },
    el("p", { class: "span-2 pf-sub", text: "지문과 보기는 바꾸지 않습니다. 저장하면 제출을 마친 응시를 모두 새 정답과 배점으로 다시 채점하고 총점과 성적부를 고칩니다. 진행 중인 응시는 제출할 때 새 정답으로 채점합니다." }),
    field("배점", points),
    choice
      ? el("fieldset", { class: "span-2" }, el("legend", { text: q.type === "single" ? "정답 보기 (하나)" : "정답 보기 (하나 이상)" }), list)
      : el("p", { class: "span-2 pf-sub", text: "단답형은 배점만 바꿉니다. 이미 준 점수보다 낮출 수 없습니다." }),
    status,
    el("div", { class: "span-2 pf-actions" }, btn("정답·배점 저장하고 다시 채점", { "data-act": "pf-quiz-regrade" }, "primary")),
    ));
  box.addEventListener("toggle", () => {
    if (box.open) openForms.add(key);
    else openForms.delete(key);
  });
  return box;
}

function sayKey(key, text, alert) {
  if (text) keyNotes.set(key, { text, alert });
  else keyNotes.delete(key);
  for (const line of document.querySelectorAll("[data-key-status]")) {
    if (line.dataset.keyStatus !== key) continue;
    line.className = alert ? "err span-2" : "pf-sub span-2";
    line.setAttribute("role", alert ? "alert" : "status");
    line.textContent = text || "";
  }
}

/**
 * `pf-quiz-regrade`: 읽은 `revision`으로 문항 하나의 정답과 배점을 고친다(`PUT …/answer-key`). 응답의 `quiz`가 화면을 바꾸고
 * `rescoredAttempts`를 알린다. 409 `edit_conflict`면 최신 상세를 받고 입력은 그대로 둔다.
 */
export async function regradeFromForm(node, api) {
  const form = node.closest("[data-quiz-key]");
  if (!form || node.disabled || node.getAttribute("aria-busy") === "true") return;
  const courseId = Number(form.dataset.course);
  const quizId = Number(form.dataset.quiz);
  const questionId = Number(form.dataset.question);
  const type = form.dataset.type;
  const key = keyOf(courseId, quizId, questionId);
  const entry = quizDetails.peek(quizKey(courseId, quizId));
  const revision = entry && entry.data ? entry.data.revision : null;
  if (revision == null) return sayKey(key, "시험 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.", true);
  const points = Number(form.querySelector('[data-field="points"]').value);
  if (!Number.isFinite(points) || points <= 0 || points > 1000 || !hundredths(points)) return sayKey(key, "배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다.", true);
  let correctOptionIds;
  if (type !== "short") {
    correctOptionIds = [...form.querySelectorAll("[data-option]")].filter((box) => box.checked).map((box) => Number(box.dataset.option));
    if (type === "single" ? correctOptionIds.length !== 1 : correctOptionIds.length < 1) {
      return sayKey(key, type === "single" ? "정답 보기를 하나 고르세요." : "정답 보기를 하나 이상 고르세요.", true);
    }
  }
  const controls = [...form.querySelectorAll("input, button")];
  controls.forEach((control) => { control.disabled = true; });
  node.setAttribute("aria-busy", "true");
  node.textContent = "다시 채점하는 중…";
  sayKey(key, "정답과 배점을 저장하고 응시를 다시 채점하고 있습니다.", false);
  try {
    const result = await regradeQuestion(courseId, quizId, questionId, { revision, points, correctOptionIds });
    keyDrafts.delete(key);
    const count = Number(result && result.rescoredAttempts) || 0;
    const text = count ? `정답과 배점을 저장했습니다. 응시 ${count}건을 다시 채점했습니다.` : "정답과 배점을 저장했습니다. 다시 채점할 제출된 응시는 없었습니다.";
    sayKey(key, text, false);
    api.getState().toast = text;
    api.paintToast();
  } catch (error) {
    if (isConflict(error, "edit_conflict")) {
      sayKey(key, "다른 곳에서 이 시험을 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 정답과 배점은 그대로 두었으니 확인한 뒤 다시 저장하세요.", true);
      await quizDetails.load(quizKey(courseId, quizId), courseId, quizId);
    } else sayKey(key, quizMessage(error), true);
  } finally {
    if (node.isConnected) {
      controls.forEach((control) => { control.disabled = false; });
      node.removeAttribute("aria-busy");
      node.textContent = "정답·배점 저장하고 다시 채점";
    }
  }
}
