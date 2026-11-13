// 교수 성적 관리(pf_gradebook)의 서버 버전(ADR-0023). 설정(평가 활동 공개), 항목(직접 입력 항목의 등록·수정·공개·삭제, 과제
// 항목의 반영 비율), 학생 쪽(page/size)의 점수 표를 `GET …/gradebook` 하나로 그린다. 점수 저장은 바꾼 행만 모아 한 번에 보내고,
// 하나라도 틀리면 서버가 아무것도 저장하지 않으므로 거절된 행에 이유를 붙이고 입력값은 그대로 둔다. 오프라인 시험의 점수 표
// (`quizScoreSheet`, 시험 상세 화면)는 그 시험 항목 하나만 `GET …/gradebook/items/{itemId}`로 읽고 같은 표와 같은 저장 흐름으로
// `PUT …/quizzes/{quizId}/scores`에 보낸다. 명령은 `data-act="pf-gradebook-cmd"`(professor/actions.js)의 `data-cmd`로 온다.
import { el } from "../../dom.js";
import { btn, field, go, badge, emptyState, loadingState, errorState, pagination } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { sessionDrafts, keepDraft, bindDraft } from "../../shared/session-drafts.js";
import { koDateTime } from "../../shared/home-remote.js";
import { routeFor } from "../../shared/routes.js";
import { serverRef } from "../../shared/assignments-remote.js";
import { quizRef } from "../../shared/quizzes-remote.js";
import {
  GRADEBOOK_PAGE, TYPE_LABEL, gradebooks, gradebookKey, loadGradebook, updateGradeSettings, createGradeItem, updateGradeItem,
  releaseGradeItem, deleteGradeItem, saveScores, saveQuizScores, rejectedRows, rowCodeText, isConflict, points, totalText, gradeMessage,
  gradeItemSheets, itemSheetKey, loadItemSheet, quizGradeItems, quizItemKey,
} from "../../shared/grades-remote.js";

const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const missing = (error) => error instanceof ApiError && error.status === 404;
const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
const note = (text) => el("p", { class: "pf-sub", text });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 성적부를 서버에서 관리할 수 있습니다.";

// 입력 중인 점수와 의견, 거절된 행의 이유. 다시 그리기와 409 재조회 뒤에도 남고, 세션이 바뀌면 버린다.
const drafts = new Map(); // `${courseId}:${itemId}:${studentId}` -> { score, feedback }
const rowErrors = new Map(); // same key -> code
const notices = new Map(); // `${courseId}` -> { text, alert }
subscribe(() => {
  drafts.clear();
  rowErrors.clear();
  notices.clear();
});

// 항목 폼(이름·배점·비율)에 입력 중인 값과 그 폼의 안내(`shared/session-drafts.js`의 초안). 409 재조회로 표를 다시 그려도
// 남고, 저장에는 초안의 `revision`을 보낸다. 세션이 바뀌면 버린다.
const itemDrafts = sessionDrafts(); // itemDraftKey -> { values, version, note, conflict, open }
const itemDraftKey = (courseId, itemId) => `${courseId}:${itemId || "new"}`;

const cellKey = (courseId, itemId, studentId) => `${courseId}:${itemId}:${studentId}`;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function gate(course) {
  if (backendStatus() === "anon") return stack(backendPanel({ text: LOGIN }));
  if (!courseIdFor(course.id)) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목만 성적부를 관리할 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") return stack(backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 성적부를 관리할 수 있습니다."));
  return null;
}

const pageOf = (ctx) => Math.max(1, Math.floor(Number(ctx.params && ctx.params.get("page"))) || 1);
const itemOf = (ctx) => Number((ctx.params && ctx.params.get("item")) || "") || null;

/** 숫자 칸을 읽는다. 비우면 null, 숫자가 아니면 NaN. */
function decimal(value) {
  const text = String(value == null ? "" : value).trim();
  if (!text) return null;
  return /^\d+(\.\d{1,2})?$/.test(text) ? Number(text) : Number.NaN;
}

// ---------- 설정 ----------

function settingsSheet(courseId, data) {
  const settings = data.settings || { activitiesVisible: true, revision: 0 };
  const on = Boolean(settings.activitiesVisible);
  return el("section", { class: "sheet", "aria-label": "성적 공개 설정" },
    el("h2", { text: "학생 화면" }),
    el("p", { class: "pf-sub" }, el("b", { text: on ? "평가 활동 공개 중" : "평가 활동 비공개" }), " · ", on
      ? "학생은 성적 화면에서 항목과 공개한 점수, 공개한 점수의 합계를 봅니다."
      : "학생 성적 화면에는 출석만 보이고 평가 활동 목록과 합계가 나오지 않습니다."),
    el("div", { class: "pf-actions" }, btn(on ? "평가 활동 숨기기" : "평가 활동 공개하기", {
      "data-act": "pf-gradebook-cmd",
      "data-cmd": "settings",
      "data-course": String(courseId),
      "data-visible": on ? "0" : "1",
      "data-revision": String(settings.revision),
    }, on ? "secondary" : "primary")),
  );
}

// ---------- 항목 ----------

function itemForm(courseId, item) {
  const id = item ? String(item.itemId) : "new";
  const input = (name, attrs) => el("input", { "data-field": name, ...attrs });
  const managed = Boolean(item && (item.managedByAssignment || item.managedByQuiz));
  const draftKey = itemDraftKey(courseId, item && item.itemId);
  const status = el("p", { class: "pf-sub span-2", role: "status", "aria-live": "polite", "data-item-status": "" });
  const form = el("div", { class: "pf-form pf-grade-item-form", "data-item-form": id, "data-draft": draftKey },
    field("항목 이름", input("name", { maxlength: "200", value: item ? item.name : "", disabled: managed ? true : null })),
    field("배점", input("maxScore", { type: "text", inputmode: "decimal", value: item ? points(item.maxScore) : "", disabled: managed ? true : null })),
    field("반영 비율 (%, 비우면 없음)", input("weight", { type: "text", inputmode: "decimal", value: item && item.weight != null ? points(item.weight) : "" })),
    el("div", { class: "pf-actions span-2" },
      btn(item ? "저장" : "항목 만들기", {
        "data-act": "pf-gradebook-cmd",
        "data-cmd": item ? "item-save" : "item-create",
        "data-course": String(courseId),
        "data-item": item ? id : null,
      }, "primary"),
    ),
    status,
  );
  // 입력 중이던 값과 안내는 초안에서 되살리고, 409 뒤에는 서버의 최신 값을 옆에 보인다.
  const draft = bindDraft(itemDrafts, draftKey, form, item ? item.revision : null);
  if (draft && draft.note) paintNote(status, draft.note);
  if (draft && draft.conflict && item) {
    form.append(el("p", { class: "pf-sub span-2", text: `서버의 최신 값 · 수정 번호 ${item.revision}: ${item.name} · 배점 ${points(item.maxScore)}점 · ${item.weight != null ? `반영 ${points(item.weight)}%` : "반영 비율 없음"}` }));
  }
  return form;
}

function paintNote(line, note) {
  line.className = `${note.alert ? "err" : "pf-sub"} span-2`;
  line.setAttribute("role", note.alert ? "alert" : "status");
  line.textContent = note.text;
}

function itemRow(courseId, kj, item, selected, page) {
  const meta = [
    TYPE_LABEL[item.type] || item.type,
    `배점 ${points(item.maxScore)}점`,
    item.weight != null ? `반영 ${points(item.weight)}%` : "반영 비율 없음",
    `채점 ${item.gradedCount}명`,
    item.released && item.releaseAt ? `공개 ${koDateTime(item.releaseAt)}` : "",
    `수정 번호 ${item.revision}`,
  ].filter(Boolean).join(" · ");
  const scoreHref = pf("pf_gradebook", { kj, item: String(item.itemId), ...(page > 1 ? { page: String(page) } : {}) });
  const actions = [go(scoreHref, selected ? "점수 표 보는 중" : "점수 표", { class: `btn ${selected ? "btn-secondary" : "btn-ghost"}`, "aria-current": selected ? "true" : null })];
  if (item.managedByAssignment) {
    actions.push(go(pf("pf_report_status", { kj, n: serverRef(item.assignmentId) }), "과제 화면에서 공개·채점", { class: "btn btn-ghost" }));
  } else if (item.managedByQuiz) {
    actions.push(go(pf("pf_quiz", { kj, n: quizRef(item.quizId) }), "시험 화면에서 점수·공개", { class: "btn btn-ghost" }));
  } else {
    actions.push(
      btn(item.released ? "점수 거두기" : "점수 공개", {
        "data-act": "pf-gradebook-cmd",
        "data-cmd": "release",
        "data-course": String(courseId),
        "data-item": String(item.itemId),
        "data-next": item.released ? "0" : "1",
        "data-revision": String(item.revision),
      }, item.released ? "secondary" : "primary"),
      btn("삭제", {
        "data-act": "pf-gradebook-cmd",
        "data-cmd": "delete",
        "data-course": String(courseId),
        "data-item": String(item.itemId),
        "data-revision": String(item.revision),
      }, "ghost"),
    );
  }
  // 고치던 항목은 다시 그려도 펼친 채로 둔다(사용자가 접으면 접은 채로).
  const draftKey = itemDraftKey(courseId, item.itemId);
  const draft = itemDrafts.get(draftKey);
  const details = el("details", { open: draft && draft.open !== false ? true : null },
    el("summary", { text: item.managedByAssignment || item.managedByQuiz ? "반영 비율 고치기" : "이름·배점·비율 고치기" }),
    item.managedByAssignment ? note("과제 항목의 이름, 배점, 점수 공개, 점수는 과제 화면에서 바꿉니다. 여기서는 반영 비율만 정합니다.") : null,
    item.managedByQuiz ? note("시험 항목의 이름과 배점은 시험 화면에서, 점수 공개는 시험의 결과 공개 일시로 정합니다. 오프라인 시험의 점수는 시험 상세 화면에서 입력합니다. 여기서는 반영 비율만 정합니다.") : null,
    itemForm(courseId, item),
  );
  details.addEventListener("toggle", () => {
    const current = itemDrafts.get(draftKey);
    if (current) current.open = details.open;
  });
  return el("article", { class: "pf-grade-item", "data-grade-item": String(item.itemId) },
    el("div", { class: "pf-inline" },
      el("b", { text: item.name }),
      badge(item.released ? "공개" : "비공개", item.released ? "success" : "neutral"),
    ),
    el("p", { class: "pf-meta", text: meta }),
    details,
    el("div", { class: "pf-actions" }, ...actions),
  );
}

function itemsSheet(courseId, kj, data, selectedId, page) {
  const items = Array.isArray(data.items) ? data.items : [];
  const weights = items.filter((item) => item.weight != null).reduce((sum, item) => sum + Number(item.weight), 0);
  return el("section", { class: "sheet", "aria-label": "성적 항목" },
    el("div", { class: "pf-week-head" },
      el("h2", { text: "성적 항목" }),
      badge(`비율 합 ${points(weights)}%`, Math.abs(weights - 100) < 1e-9 ? "success" : "neutral"),
    ),
    note("직접 입력 항목은 비공개로 만들어집니다. 점수를 공개하기 전에는 학생 응답에 점수가 들어가지 않습니다. 비율 합은 서버가 검사하지 않습니다."),
    items.length ? el("div", { class: "stack" }, ...items.map((item) => itemRow(courseId, kj, item, item.itemId === selectedId, page))) : note("아직 성적 항목이 없습니다. 과제를 내면 과제 항목이 생기고, 아래에서 직접 입력 항목을 만들 수 있습니다."),
    el("h3", { text: "직접 입력 항목 만들기" }),
    itemForm(courseId, null),
  );
}

// ---------- 점수 표 ----------

/** 학생 한 명의 그 항목 칸. 항목 점수 표는 학생 행이 곧 칸이고, `version` 0(성적 행 없음)이면 칸이 없는 것으로 본다. */
function sheetCell(student, item, sheet) {
  if (!item) return null;
  if (sheet) return Number(student.version) > 0 ? student : null;
  return (student.scores || []).find((score) => score.itemId === item.itemId) || null;
}

/**
 * 한 항목의 점수 표(또는 항목이 없으면 학생별 합계). `options.editable`이 있으면 그 값으로 입력 칸을 정하고(오프라인 시험 표),
 * 없으면 직접 입력 항목만 입력한다. `options.hrefForPage`는 쪽 링크, `options.quiz`는 시험 화면의 표인지다. `options.sheet`이면
 * `data`는 항목 하나의 점수 표 응답이다(학생마다 점수·의견·`version`이 바로 있고 합계 열은 없다).
 */
function scoresSheet(courseId, kj, data, item, page, options = {}) {
  const sheet = Boolean(options.sheet);
  const students = Array.isArray(data.students) ? data.students : [];
  const pages = Math.max(1, Math.ceil(Number(data.totalStudents) / (Number(data.size) || GRADEBOOK_PAGE)));
  const hrefForPage = options.hrefForPage || ((n) => pf("pf_gradebook", { kj, ...(item ? { item: String(item.itemId) } : {}), ...(n > 1 ? { page: String(n) } : {}) }));
  const pager = pagination({ page, pages, hrefForPage });
  const head = el("div", { class: "pf-week-head" },
    el("h2", { text: item ? `점수 · ${item.name}` : "학생별 합계" }),
    el("span", { class: "pf-meta", text: `수강생 ${data.totalStudents}명 · ${page}/${pages}쪽` }),
  );
  const notice = notices.get(String(courseId));
  const status = el("p", { class: notice && notice.alert ? "err" : "pf-sub", role: notice && notice.alert ? "alert" : "status", "data-scores-status": "", text: notice ? notice.text : "" });
  if (!students.length) return el("section", { class: "sheet" }, head, note("이 쪽에 수강생이 없습니다."), pager || "");
  const editable = options.editable !== undefined ? Boolean(options.editable && item) : Boolean(item && !item.managedByAssignment && !item.managedByQuiz);
  const rows = students.map((student) => {
    const cell = sheetCell(student, item, sheet);
    const total = sheet ? null : student.total ? totalText(student.total) : "—";
    const name = el("td", { class: "pf-lock", "data-label": "이름", text: student.name || `학생 ${student.studentId}` });
    if (!item) return el("tr", {}, name, el("td", { "data-label": "합계", text: total }));
    if (!editable) {
      return el("tr", {}, name,
        el("td", { "data-label": "점수", text: cell && cell.score != null ? `${points(cell.score)} / ${points(item.maxScore)}` : "-" }),
        el("td", { "data-label": "의견", text: cell && cell.feedback ? cell.feedback : "—" }),
        sheet ? null : el("td", { "data-label": "합계", text: total }));
    }
    const key = cellKey(courseId, item.itemId, student.studentId);
    const draft = drafts.get(key);
    const serverScore = cell && cell.score != null ? points(cell.score) : "";
    const serverFeedback = cell && cell.feedback ? cell.feedback : "";
    const score = el("input", {
      type: "text",
      inputmode: "decimal",
      value: draft ? draft.score : serverScore,
      "aria-label": `${student.name || student.studentId} 점수`,
      "data-score-input": String(student.studentId),
    });
    const feedback = el("input", {
      type: "text",
      maxlength: "1000",
      value: draft ? draft.feedback : serverFeedback,
      "aria-label": `${student.name || student.studentId} 의견`,
      "data-feedback-input": String(student.studentId),
    });
    const remember = () => {
      if (score.value.trim() === serverScore && feedback.value === serverFeedback) drafts.delete(key);
      else drafts.set(key, { score: score.value.trim(), feedback: feedback.value });
    };
    score.addEventListener("input", remember);
    feedback.addEventListener("input", remember);
    const code = rowErrors.get(key);
    const changedOnServer = draft && code === "version_conflict";
    const state = el("td", { "data-label": "상태" },
      code ? el("span", { class: "err", text: rowCodeText(code) }) : draft ? el("span", { class: "pf-meta", text: "저장 전" }) : el("span", { class: "pf-meta", text: cell ? `버전 ${cell.version}` : "미채점" }),
      changedOnServer ? el("span", { class: "pf-meta", text: ` 서버 값: ${serverScore || "-"}${serverFeedback ? ` · ${serverFeedback}` : ""}` }) : null,
    );
    return el("tr", { "data-row-student": String(student.studentId), class: code ? "pf-row-error" : null },
      name,
      el("td", { "data-label": `점수 (최대 ${points(item.maxScore)})` }, score),
      el("td", { "data-label": "의견" }, feedback),
      state,
      sheet ? null : el("td", { "data-label": "합계", text: total }));
  });
  const headers = !item ? ["이름", "합계"] : editable ? ["이름", `점수 (최대 ${points(item.maxScore)})`, "의견", "상태", ...(sheet ? [] : ["합계"])] : ["이름", "점수", "의견", ...(sheet ? [] : ["합계"])];
  const tableNode = el("div", { class: "table-wrap table-scroll pf-wide" },
    el("table", { class: "grid", "aria-label": item ? `${item.name} 점수` : "학생별 합계" },
      el("thead", {}, el("tr", {}, ...headers.map((label, index) => el("th", { scope: "col", class: index === 0 ? "pf-lock" : null, text: label })))),
      el("tbody", {}, ...rows)));
  return el("section", { class: "sheet", "data-scores": item ? String(item.itemId) : "", "aria-label": "점수 표" },
    head,
    !item ? note("위 항목의 \"점수 표\"를 누르면 그 항목의 점수를 입력합니다. 합계는 모든 항목의 받은 점수를 더한 서버 값입니다.") : null,
    item && !editable ? note(item.managedByQuiz ? "시험 항목의 점수는 시험 채점(온라인) 또는 시험 상세의 점수 입력(오프라인)에서 줍니다. 여기서는 읽기만 합니다." : "과제 항목의 점수는 과제 채점 화면에서 줍니다. 여기서는 읽기만 합니다.") : null,
    editable ? note("바꾼 행만 한 번에 저장합니다. 한 행이라도 틀리면 아무것도 저장하지 않고 틀린 행을 표시합니다. 점수를 비우면 점수와 의견을 지웁니다.") : null,
    options.quiz ? note("학생은 시험의 결과 공개 일시가 지난 뒤에 자기 점수만 봅니다.") : null,
    tableNode,
    editable ? el("div", { class: "pf-actions" }, btn("바꾼 점수 저장", {
      "data-act": "pf-gradebook-cmd",
      "data-cmd": "scores",
      "data-course": String(courseId),
      "data-item": String(item.itemId),
      "data-page": String(page),
      "data-sheet": sheet ? "1" : null,
    }, "primary")) : null,
    status,
    pager || "",
  );
}

/**
 * 받은 응답을 `draw(data)`가 돌려준 노드들로 그리는 본문. 같은 응답이면 다시 그리지 않고(포커스 복귀 때의 재조회가 입력 중인
 * 칸을 지우지 않게), 행 오류만 바뀌면 `gradebook-redraw`로 다시 그린다. `source`가 없으면 성적부 한 쪽, 있으면
 * `{ resource, key, load, missingTitle, missingText }`(항목 점수 표)를 읽는다.
 */
function gradebookBody(courseId, page, scope, draw, source = null) {
  const resource = source ? source.resource : gradebooks;
  const key = source ? source.key : gradebookKey(courseId, page);
  const load = source ? source.load : () => loadGradebook(courseId, page);
  const body = el("div", { class: "stack", "aria-live": "polite", "data-gradebook": String(courseId) });
  let drawn = "";
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      drawn = "";
      if (!entry || !entry.error) body.replaceChildren(loadingState("성적부를 불러오는 중입니다"));
      else if (missing(entry.error)) {
        body.replaceChildren(source
          ? emptyState(source.missingTitle, source.missingText)
          : emptyState("성적부를 볼 수 없습니다", "이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다."));
      } else body.replaceChildren(errorState("성적부를 불러오지 못했습니다", gradeMessage(entry.error), retry(load)));
      return;
    }
    const sig = JSON.stringify([{ ...data, serverTime: null }, entry.error && entry.error.message]);
    if (sig === drawn) return;
    drawn = sig;
    body.replaceChildren(entry.error ? el("p", { class: "err", role: "alert", text: gradeMessage(entry.error) }) : "", ...draw(data));
  };
  paint(resource.peek(key));
  body.addEventListener("gradebook-redraw", () => {
    drawn = "";
    paint(resource.peek(key));
  });
  queueMicrotask(() => {
    if (!body.isConnected) return;
    resource.watch(body, key, paint, load);
    if (freshVisit(scope, key) === "entry" || !resource.peek(key)) load();
  });
  return body;
}

/** `pf_gradebook`(백엔드 모드)의 본문. */
export function serverGradebook(ctx, course) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const page = pageOf(ctx);
  const selectedId = itemOf(ctx);
  return stack(backendPanel(), gradebookBody(courseId, page, "pf-gradebook", (data) => {
    const items = Array.isArray(data.items) ? data.items : [];
    const item = items.find((candidate) => candidate.itemId === selectedId) || null;
    return [
      settingsSheet(courseId, data),
      itemsSheet(courseId, course.id, data, item ? item.itemId : null, page),
      selectedId && !item ? note("고른 항목이 없어졌습니다. 다른 항목을 고르세요.") : "",
      scoresSheet(courseId, course.id, data, item, page),
    ];
  }));
}

/**
 * 오프라인 시험의 점수 표(시험 상세 화면). 시험 상세에 성적 항목 ID가 없어 먼저 `quizGradeItems`로 이 시험의 항목 ID를 찾고,
 * 그 항목 하나의 학생 쪽(`GET …/gradebook/items/{itemId}?page=`)을 성적부와 같은 표로 그린다. 저장은
 * `PUT …/quizzes/{quizId}/scores`로 보낸다. `hrefForPage(n)`은 쪽 링크다.
 */
export function quizScoreSheet(course, courseId, quizId, page, hrefForPage) {
  const wrap = el("div", { class: "stack", "aria-live": "polite" });
  const key = quizItemKey(courseId, quizId);
  const find = () => quizGradeItems.load(key, courseId, quizId);
  let shown = null; // 그린 항목 ID. 같은 항목이면 표 노드를 그대로 둔다(입력 중인 칸이 남게).
  const paint = (entry) => {
    const data = entry && entry.data;
    if (data && data.itemId != null) {
      if (shown === data.itemId) return;
      shown = data.itemId;
      const itemId = data.itemId;
      wrap.replaceChildren(gradebookBody(courseId, page, `pf-quiz-scores-${quizId}`,
        (sheet) => [scoresSheet(courseId, course.id, sheet, sheet.item, page, { editable: true, hrefForPage, quiz: true, sheet: true })],
        {
          resource: gradeItemSheets,
          key: itemSheetKey(courseId, itemId, page),
          load: () => loadItemSheet(courseId, itemId, page),
          missingTitle: "성적부에 이 시험의 항목이 없습니다",
          missingText: "시험이 삭제되었거나 이 과목의 담당 교수가 아닙니다. 시험 목록을 새로 불러오세요.",
        }));
      return;
    }
    shown = null;
    if (data) wrap.replaceChildren(emptyState("성적부에 이 시험의 항목이 없습니다", "시험이 삭제되었거나 아직 성적부에 반영되지 않았습니다. 잠시 후 다시 불러오세요."), retry(find));
    else if (!entry || !entry.error) wrap.replaceChildren(loadingState("성적부를 불러오는 중입니다"));
    else if (missing(entry.error)) wrap.replaceChildren(emptyState("성적부를 볼 수 없습니다", "이 과목의 담당 교수가 아니거나 과목을 찾을 수 없습니다."));
    else wrap.replaceChildren(errorState("성적부를 불러오지 못했습니다", gradeMessage(entry.error), retry(find)));
  };
  paint(quizGradeItems.peek(key));
  queueMicrotask(() => {
    if (!wrap.isConnected) return;
    quizGradeItems.watch(wrap, key, paint, null);
    const entry = quizGradeItems.peek(key);
    // 항목을 못 찾았거나 읽지 못했으면 다시 묻는다. 찾은 항목 ID는 세션 동안 그대로다.
    if (!entry || entry.error || !entry.data || entry.data.itemId == null) find();
  });
  return wrap;
}

// ---------- 명령 ----------

function say(courseId, text, alert) {
  notices.set(String(courseId), { text, alert });
  const line = document.querySelector("[data-scores-status]");
  if (line) {
    line.className = alert ? "err" : "pf-sub";
    line.setAttribute("role", alert ? "alert" : "status");
    line.textContent = text;
  }
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

function readItemForm(form, managed) {
  const get = (name) => form.querySelector(`[data-field="${name}"]`);
  const name = get("name").value.trim();
  const maxScore = decimal(get("maxScore").value);
  const weight = decimal(get("weight").value);
  const errors = [];
  if (!managed) {
    if (!name || name.length > 200) errors.push("이름은 1자 이상 200자 이하입니다");
    if (maxScore == null || Number.isNaN(maxScore) || maxScore <= 0 || maxScore > 1000) errors.push("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다");
  }
  if (Number.isNaN(weight) || (weight != null && weight > 100)) errors.push("반영 비율은 0부터 100까지, 소수 둘째 자리까지입니다");
  return { errors, name, maxScore, weight };
}

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

function gradebookData(courseId, page) {
  const entry = gradebooks.peek(gradebookKey(courseId, page));
  return entry && entry.data;
}

function itemSheetData(courseId, itemId, page) {
  const entry = gradeItemSheets.peek(itemSheetKey(courseId, itemId, page));
  return entry && entry.data;
}

/** 점수 저장: 바꾼 행만 모아 보내고, 거절이면 행마다 이유를 붙인다. `sheet`이면 항목 점수 표의 응답에서 읽는다. */
async function sendScores(node, api, courseId, itemId, page, sheet) {
  const data = sheet ? itemSheetData(courseId, itemId, page) : gradebookData(courseId, page);
  const item = !data ? null : sheet ? (data.item && data.item.itemId === itemId ? data.item : null) : (data.items || []).find((candidate) => candidate.itemId === itemId);
  if (!item) return say(courseId, "항목을 찾지 못했습니다. 성적부를 새로 불러오세요.", true);
  const reload = () => (sheet ? loadItemSheet(courseId, itemId, page) : loadGradebook(courseId, page));
  const rows = [];
  const keys = [];
  let local = 0;
  for (const student of data.students || []) {
    const key = cellKey(courseId, itemId, student.studentId);
    const draft = drafts.get(key);
    if (!draft) continue;
    const cell = sheetCell(student, item, sheet);
    const score = decimal(draft.score);
    const feedback = draft.feedback.trim() ? draft.feedback : null;
    rowErrors.delete(key);
    if (Number.isNaN(score) || (score != null && score > Number(item.maxScore))) {
      rowErrors.set(key, Number.isNaN(score) ? "invalid_score" : "score_exceeds_max");
      local += 1;
    } else if (score == null && feedback) {
      rowErrors.set(key, "feedback_without_score");
      local += 1;
    }
    rows.push({ studentId: student.studentId, score, feedback: score == null ? null : feedback, version: cell ? cell.version : 0 });
    keys.push(key);
  }
  if (!rows.length) return say(courseId, "바꾼 점수가 없습니다.", false);
  if (local) {
    say(courseId, `틀린 행 ${local}개를 고친 뒤 저장하세요. 아직 아무것도 보내지 않았습니다.`, true);
    redraw(courseId);
    return;
  }
  await withBusy(node, "저장하는 중…", async () => {
    try {
      // 오프라인 시험 항목은 시험의 점수 명령으로, 직접 입력 항목은 항목의 점수 명령으로 보낸다. 본문과 거절 모양은 같다.
      const sheet = item.managedByQuiz ? await saveQuizScores(courseId, item.quizId, rows) : await saveScores(courseId, itemId, rows);
      for (const key of keys) {
        drafts.delete(key);
        rowErrors.delete(key);
      }
      say(courseId, `${(sheet.rows || []).length}명의 점수를 저장했습니다.`, false);
      toast(api, "점수를 저장했습니다");
    } catch (error) {
      const rejected = rejectedRows(error);
      for (const row of rejected) if (keys[row.index]) rowErrors.set(keys[row.index], row.code);
      say(courseId, `${gradeMessage(error)}${rejected.length ? ` (틀린 행 ${rejected.length}개)` : ""}`, true);
      if (isConflict(error, "version_conflict") || missing(error)) await reload();
      redraw(courseId);
    }
  });
}

/** 행 오류만 바뀌었을 때도 표를 다시 그린다(paint는 같은 응답이면 건너뛴다). */
function redraw(courseId) {
  const node = document.querySelector(`[data-gradebook="${courseId}"]`);
  if (node) node.dispatchEvent(new CustomEvent("gradebook-redraw"));
}

/** `pf-gradebook-cmd`: settings, item-create, item-save, release, delete(두 번 눌러 확인), scores. */
export async function gradebookCommand(node, api) {
  if (node.disabled) return;
  const courseId = Number(node.dataset.course);
  const itemId = Number(node.dataset.item) || null;
  const cmd = node.dataset.cmd;
  const page = Number(node.dataset.page) || Number(new URLSearchParams(location.search).get("page")) || 1;
  if (cmd === "scores") return sendScores(node, api, courseId, itemId, page, node.dataset.sheet === "1");
  if (cmd === "settings") {
    return withBusy(node, "바꾸는 중…", async () => {
      try {
        await updateGradeSettings(courseId, { activitiesVisible: node.dataset.visible === "1", revision: Number(node.dataset.revision) });
        toast(api, node.dataset.visible === "1" ? "학생에게 평가 활동을 공개했습니다" : "학생 화면에서 평가 활동을 숨겼습니다");
      } catch (error) {
        toast(api, gradeMessage(error));
        if (isConflict(error)) loadGradebook(courseId, page);
      }
    });
  }
  if (cmd === "item-create" || cmd === "item-save") {
    const form = node.closest("[data-item-form]");
    const data = gradebookData(courseId, page);
    const item = itemId && data ? (data.items || []).find((candidate) => candidate.itemId === itemId) : null;
    if (cmd === "item-save" && !item) return toast(api, "항목을 찾지 못했습니다. 성적부를 새로 불러오세요.");
    const draftKey = form.dataset.draft;
    // 누를 때의 값을 초안에 담아 409 재조회로 표를 다시 그려도 남긴다. 수정은 초안의 기준 `revision`을 보낸다.
    const draft = keepDraft(itemDrafts, draftKey, form, item ? item.revision : null);
    const tell = (text, alert) => {
      draft.note = { text, alert };
      const line = form.querySelector("[data-item-status]");
      if (line) paintNote(line, draft.note);
    };
    const managed = Boolean(item && (item.managedByAssignment || item.managedByQuiz));
    const read = readItemForm(form, managed);
    if (read.errors.length) return tell(read.errors.join(". "), true);
    return withBusy(node, "저장하는 중…", async () => {
      try {
        // 활동에 딸린 항목(과제·시험)은 반영 비율만 보낸다(`updateGradeItem`이 고른다).
        if (item) await updateGradeItem(courseId, item, { name: read.name, maxScore: read.maxScore, weight: read.weight, revision: draft.version });
        else await createGradeItem(courseId, { name: read.name, maxScore: read.maxScore, weight: read.weight });
        if (itemDrafts.get(draftKey) === draft) itemDrafts.delete(draftKey);
        toast(api, item ? "항목을 수정했습니다" : "항목을 만들었습니다. 점수를 공개하기 전에는 학생에게 점수가 보이지 않습니다");
      } catch (error) {
        const stale = Boolean(item) && isConflict(error, "edit_conflict");
        if (stale) draft.conflict = true;
        // 지워진 항목의 수정은 다시 보낼 곳이 없어 초안을 버린다.
        if (item && missing(error)) itemDrafts.delete(draftKey);
        tell(gradeMessage(error), true);
        if (!stale && !missing(error)) return;
        // 입력은 두고 `revision`만 새로 받는다. 다시 읽지 못하면 기준 번호를 그대로 두어 다음 저장도 충돌로 알린다.
        await loadGradebook(courseId, page);
        const entry = gradebooks.peek(gradebookKey(courseId, page));
        const fresh = stale && entry && !entry.error && (gradebookData(courseId, page)?.items || []).find((candidate) => candidate.itemId === itemId);
        if (fresh && itemDrafts.get(draftKey) === draft) draft.version = fresh.revision;
      }
    });
  }
  if (cmd === "release") {
    return withBusy(node, "바꾸는 중…", async () => {
      try {
        await releaseGradeItem(courseId, itemId, node.dataset.next === "1", Number(node.dataset.revision));
        toast(api, node.dataset.next === "1" ? "점수를 학생에게 공개했습니다" : "점수 공개를 거뒀습니다");
      } catch (error) {
        toast(api, gradeMessage(error));
        if (isConflict(error) || missing(error)) loadGradebook(courseId, page);
      }
    });
  }
  if (cmd === "delete") {
    if (node.dataset.armed !== "1") {
      node.dataset.armed = "1";
      node.textContent = "한 번 더 누르면 삭제";
      return;
    }
    node.dataset.armed = "";
    return withBusy(node, "삭제하는 중…", async () => {
      try {
        await deleteGradeItem(courseId, itemId, Number(node.dataset.revision));
        itemDrafts.delete(itemDraftKey(courseId, itemId));
        toast(api, "항목을 삭제했습니다");
      } catch (error) {
        toast(api, gradeMessage(error));
        if (isConflict(error) || missing(error)) loadGradebook(courseId, page);
      }
    }).finally(() => {
      if (node.isConnected) node.textContent = "삭제";
    });
  }
  return undefined;
}
