// 교수의 서버 강의실 설문(백엔드 모드, `pf_discuss`의 설문 부분): 등록, `revision`으로 전체 수정(응답한 사람이
// 있으면 문항과 응답 방식은 보내지 않는다), 완료 수가 있는 목록, 결과 집계(`?n=srv-<surveyId>`).
// 저장은 professor/actions.js(`pf-survey-save`)를 거친다. 토론은 서버 API가 없어 목업 그대로다.
import { el } from "../../dom.js";
import { btn, field, go, badge, emptyState } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { routeFor } from "../../shared/routes.js";
import {
  surveyLists, surveyDetails, surveyPath, surveyIdOf, surveyRef, createSurvey, updateSurvey, surveyMessage, isConflict,
  PHASE, QUESTION_TYPE,
} from "../../shared/surveys-remote.js";
import { resultsSection } from "../../shared/survey-view.js";
import { deleteControl } from "./delete-server.js";

const missing = (error) => error instanceof ApiError && error.status === 404;
const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });
const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 설문을 서버에 만들고 결과를 볼 수 있습니다.";

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
const toInput = (value) => (value ? String(value).slice(0, 16) : "");
const fromInput = (value) => (value ? (value.length === 16 ? `${value}:00` : value) : null);
const periodText = (item) => (item.openAt ? `${koDateTime(item.openAt)} ~ ${koDateTime(item.closeAt)}` : `~ ${koDateTime(item.closeAt)}`);

function gate(course) {
  if (backendStatus() === "anon") return stack(backendPanel({ text: LOGIN }));
  if (!courseIdFor(course.id)) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목에만 설문을 만들 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") return stack(backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 설문을 만들고 결과를 볼 수 있습니다."));
  return null;
}

// 폼 키 -> 편집 중인 문항. 세션이 바뀌면 버린다.
const drafts = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  drafts.clear();
});

const blankQuestion = () => ({ type: "single", text: "", required: true, minSelect: "", maxSelect: "", options: ["", ""] });

function fromServer(questions) {
  return (questions || []).map((q) => ({
    type: q.type,
    text: q.text || "",
    required: Boolean(q.required),
    minSelect: q.minSelect != null ? String(q.minSelect) : "",
    maxSelect: q.maxSelect != null ? String(q.maxSelect) : "",
    options: (q.options || []).map((o) => o.text),
  }));
}

function questionEditor(list, locked) {
  const root = el("div", { class: "stack", "data-survey-questions": "" });
  const paint = () => root.replaceChildren(...list.map(card), locked ? "" : el("div", { class: "pf-actions" }, add()));
  const add = () => {
    const button = btn("문항 추가", {}, "secondary");
    button.addEventListener("click", () => {
      list.push(blankQuestion());
      paint();
    });
    return button;
  };
  function card(q, index) {
    const type = el("select", { "aria-label": `${index + 1}번 문항 종류` },
      ...Object.entries(QUESTION_TYPE).map(([value, label]) => el("option", { value, text: label, selected: q.type === value ? true : null })));
    const text = el("textarea", { rows: "2", maxlength: "1000", "aria-label": `${index + 1}번 문항` });
    text.value = q.text;
    const required = el("input", { type: "checkbox" });
    required.checked = q.required;
    type.addEventListener("change", () => {
      q.type = type.value;
      if (q.type === "essay") q.options = [];
      else if (q.options.length < 2) q.options = q.type === "scale" ? ["매우 아니다", "아니다", "보통", "그렇다", "매우 그렇다"] : ["", ""];
      paint();
    });
    text.addEventListener("input", () => { q.text = text.value; });
    required.addEventListener("change", () => { q.required = required.checked; });
    const options = el("div", { class: "stack" });
    const max = q.type === "scale" ? 11 : 20;
    if (q.type !== "essay") {
      q.options.forEach((value, at) => {
        const input = el("input", { maxlength: "300", value, "aria-label": `${index + 1}번 보기 ${at + 1}` });
        input.addEventListener("input", () => { q.options[at] = input.value; });
        const drop = btn("빼기", { "aria-label": `${index + 1}번 보기 ${at + 1} 빼기` }, "ghost");
        drop.disabled = q.options.length <= 2;
        drop.addEventListener("click", () => {
          q.options.splice(at, 1);
          paint();
        });
        options.append(el("div", { class: "pf-inline" }, q.type === "scale" ? el("span", { class: "pf-meta", text: String(at + 1) }) : null, input, locked ? null : drop));
      });
      if (!locked && q.options.length < max) {
        const more = btn("보기 추가", {}, "ghost");
        more.addEventListener("click", () => {
          q.options.push("");
          paint();
        });
        options.append(more);
      }
    }
    const minSelect = el("input", { type: "number", min: "1", max: "20", value: q.minSelect, placeholder: "1", "aria-label": `${index + 1}번 최소 선택 수` });
    const maxSelect = el("input", { type: "number", min: "1", max: "20", value: q.maxSelect, placeholder: "보기 수", "aria-label": `${index + 1}번 최대 선택 수` });
    minSelect.addEventListener("input", () => { q.minSelect = minSelect.value; });
    maxSelect.addEventListener("input", () => { q.maxSelect = maxSelect.value; });
    const remove = btn("문항 빼기", { "aria-label": `${index + 1}번 문항 빼기` }, "ghost");
    remove.disabled = list.length <= 1;
    remove.addEventListener("click", () => {
      list.splice(index, 1);
      paint();
    });
    const node = el("fieldset", { class: "sheet pf-q" },
      el("legend", { text: `${index + 1}번 문항` }),
      el("div", { class: "pf-form" },
        field("종류", type),
        el("label", { class: "choice pf-check" }, required, el("span", { text: "필수 응답" })),
        el("div", { class: "span-2" }, field("문항 (일반 텍스트)", text)),
        q.type === "multiple" ? field("최소 선택", minSelect) : null,
        q.type === "multiple" ? field("최대 선택", maxSelect) : null,
        q.type === "essay" ? null : el("div", { class: "span-2" }, el("p", { class: "pf-sub", text: q.type === "scale" ? "척도는 보기 순서가 1부터의 값입니다(2~11개)." : "보기는 2개 이상 20개 이하입니다." }), options),
      ),
      locked ? null : el("div", { class: "pf-actions" }, remove),
    );
    if (locked) node.querySelectorAll("input, select, textarea, button").forEach((control) => { control.disabled = true; });
    return node;
  }
  paint();
  return root;
}

function readQuestions(list) {
  const errors = [];
  if (!list.length || list.length > 100) errors.push("문항은 1개 이상 100개 이하입니다");
  const questions = list.map((q, index) => {
    const n = index + 1;
    if (!q.text.trim()) errors.push(`${n}번 문항을 입력하세요`);
    if (q.text.length > 1000) errors.push(`${n}번 문항은 1,000자 이하입니다`);
    const out = { type: q.type, text: q.text, required: Boolean(q.required) };
    if (q.type === "essay") return out;
    const max = q.type === "scale" ? 11 : 20;
    if (q.options.length < 2 || q.options.length > max) errors.push(`${n}번 보기는 2개 이상 ${max}개 이하입니다`);
    if (q.options.some((o) => !o.trim() || o.length > 300)) errors.push(`${n}번 보기 문구를 모두 300자 이하로 입력하세요`);
    out.options = q.options.map((o) => o.trim());
    if (q.type === "multiple") {
      const min = q.minSelect === "" ? 1 : Number(q.minSelect);
      const most = q.maxSelect === "" ? q.options.length : Number(q.maxSelect);
      if (!Number.isInteger(min) || !Number.isInteger(most) || min < 1 || min > most || most > q.options.length) errors.push(`${n}번 선택 수는 1 이상 보기 수 이하이고 최소가 최대 이하여야 합니다`);
      out.minSelect = min;
      out.maxSelect = most;
    }
    return out;
  });
  return errors.length ? { errors } : { questions };
}

/** `pf_discuss`의 설문 부분(백엔드 모드). `?edit=<surveyId>` 수정, `?n=srv-<surveyId>` 결과. */
export function serverSurveyFields(ctx, course, parts) {
  const blocked = gate(course);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const resultsId = surveyIdOf(ctx.params && ctx.params.get("n"));
  if (resultsId) {
    return stack(backendPanel(), surveyHeader(course, courseId, resultsId),
      el("section", { class: "sheet" }, resultsSection(courseId, resultsId, { title: "결과 집계" }),
        note("익명 설문은 응답 기간이 끝나고 응답자가 5명 이상일 때만 집계를 볼 수 있습니다. 답한 사람이 5명보다 적은 문항은 가립니다. 누가 무엇을 답했는지는 어떤 화면에도 나오지 않습니다.")),
      surveyList(course, courseId));
  }
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  return stack(
    backendPanel(),
    surveyForm(ctx, course, courseId, Number.isInteger(editId) && editId > 0 ? editId : null, parts),
    surveyList(course, courseId),
  );
}

function surveyForm(ctx, course, courseId, editId, parts) {
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
  const openAt = input("openAt", { type: "datetime-local" });
  const closeAt = input("closeAt", { type: "datetime-local" });
  const published = check("published", true);
  const anonymous = check("anonymous", true);
  const resultsVisible = check("resultsVisible", false);
  const releaseAt = input("resultsReleaseAt", { type: "datetime-local" });
  const description = el("textarea", { "data-field": "description", rows: "4", maxlength: "10000" });
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-survey-status": "" });
  const save = btn(editId ? "수정 저장" : "설문 등록", { "data-act": "pf-survey-save" }, "primary");
  const slot = el("div", { class: "span-2" });
  const sheet = formSheet(editId ? "설문 수정" : "설문",
    span(field("제목", title)),
    field("주차", week),
    field("시작 (비우면 바로 시작)", openAt),
    field("마감", closeAt),
    span(choice(published, "학생에게 공개")),
    span(choice(anonymous, "익명 설문 (담당자도 누가 답했는지 모릅니다. 결과는 마감 뒤 응답자 5명 이상일 때만)")),
    span(choice(resultsVisible, "학생에게 결과 공개 (마감 뒤, 응답자 5명 이상일 때)")),
    field("결과 공개 일시 (비우면 마감 뒤 바로)", releaseAt),
    span(field("안내 (일반 텍스트)", description)),
    span(status),
    span(el("div", { "data-survey-latest": "" })),
    slot,
    span(el("div", { class: "pf-actions" }, save, editId ? linkBtn(pf("pf_discuss", { kj: course.id }), "새 설문 쓰기") : null,
      editId ? linkBtn(pf("pf_discuss", { kj: course.id, n: surveyRef(editId) }), "결과 보기") : null)),
    editId ? span(el("div", { "data-survey-delete": "" })) : null,
  );
  sheet.dataset.surveyForm = "";
  sheet.dataset.course = String(courseId);
  sheet.dataset.kj = course.id;
  sheet.dataset.edit = editId ? String(editId) : "";
  const formKey = `${courseId}:${editId || "new"}`;
  if (!editId) {
    if (!drafts.has(formKey)) drafts.set(formKey, [blankQuestion()]);
    slot.append(el("h3", { text: "문항" }), questionEditor(drafts.get(formKey), false));
    return sheet;
  }
  const path = surveyPath(courseId, editId);
  const controls = () => [...sheet.querySelectorAll("input, textarea, button, select")];
  let filled = false;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.revision == null) {
      if (filled) return;
      controls().forEach((control) => { control.disabled = true; });
      status.setAttribute("role", entry && entry.error ? "alert" : "status");
      status.textContent = !entry || !entry.error ? "설문을 불러오는 중입니다."
        : missing(entry.error) ? "설문을 찾을 수 없습니다. 이 과목의 설문이 아닙니다." : surveyMessage(entry.error);
      return;
    }
    sheet.dataset.revision = String(data.revision);
    sheet.querySelector("[data-survey-delete]").replaceChildren(deleteControl("survey", courseId, editId, { title: data.survey.title, back: pf("pf_discuss", { kj: course.id }) }));
    const info = data.survey;
    const locked = data.respondentCount > 0;
    sheet.dataset.locked = locked ? "1" : "";
    if (!filled) {
      filled = true;
      title.value = info.title;
      week.value = String(info.weekNo);
      openAt.value = toInput(info.openAt);
      closeAt.value = toInput(info.closeAt);
      published.checked = Boolean(info.published);
      anonymous.checked = Boolean(info.anonymous);
      resultsVisible.checked = Boolean(info.resultsVisible);
      releaseAt.value = toInput(info.resultsReleaseAt);
      description.value = info.description || "";
      controls().forEach((control) => { control.disabled = false; });
      drafts.set(formKey, fromServer(data.questions));
      status.setAttribute("role", "status");
    }
    // 잠긴 문항은 서버의 문항만 보여 준다.
    if (locked) drafts.set(formKey, fromServer(data.questions));
    anonymous.disabled = locked;
    // 저장 실패 안내(role=alert)는 재조회로 다시 그려도 지우지 않는다.
    if (status.getAttribute("role") !== "alert") {
      status.setAttribute("role", "status");
      status.textContent = `수정 번호 ${data.revision} · ${PHASE[data.phase] || data.phase} · 응답 ${data.respondentCount}명`;
    }
    slot.replaceChildren(
      el("h3", { text: `문항 ${data.questions.length}개` }),
      locked ? el("p", { class: "pf-sub", role: "note", text: "응답한 사람이 있어 문항과 응답 방식(익명/실명)은 바꿀 수 없습니다. 기간, 안내, 공개, 결과 공개는 바꿀 수 있습니다." }) : null,
      questionEditor(drafts.get(formKey), locked),
    );
  };
  paint(surveyDetails.peek(path));
  attached(sheet, () => {
    surveyDetails.watch(sheet, path, paint, null);
    if (freshVisit("pf-survey-edit", path) === "entry" || !surveyDetails.peek(path)) surveyDetails.load(path, path);
  });
  return sheet;
}

/** `pf-survey-save`: 등록, 또는 읽은 `revision`으로 전체 수정. */
export async function saveSurveyForm(node, api) {
  const sheet = node.closest("[data-survey-form]");
  if (!sheet || node.disabled) return;
  const status = sheet.querySelector("[data-survey-status]");
  const say = (text, alert) => {
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = text;
  };
  const get = (name) => sheet.querySelector(`[data-field="${name}"]`);
  const errors = [];
  const title = get("title").value.trim();
  const weekNo = Number(get("weekNo").value);
  const openAt = fromInput(get("openAt").value);
  const closeAt = fromInput(get("closeAt").value);
  const resultsVisible = get("resultsVisible").checked;
  const releaseAt = fromInput(get("resultsReleaseAt").value);
  const description = get("description").value;
  if (!title || title.length > 200) errors.push("제목은 1자 이상 200자 이하입니다");
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
  if (!closeAt) errors.push("마감 일시를 입력하세요");
  if (openAt && closeAt && openAt >= closeAt) errors.push("시작은 마감보다 앞이어야 합니다");
  if (releaseAt && !resultsVisible) errors.push("결과 공개 일시는 학생에게 결과를 공개할 때만 정합니다");
  if (description.length > 10000) errors.push("안내는 10,000자 이하입니다");
  const courseId = Number(sheet.dataset.course);
  const editId = Number(sheet.dataset.edit) || null;
  const locked = sheet.dataset.locked === "1";
  const read = locked ? { questions: undefined } : readQuestions(drafts.get(`${courseId}:${editId || "new"}`) || []);
  if (read.errors) errors.push(...read.errors);
  if (errors.length) return say(`${errors.join(". ")}.`, true);
  const payload = {
    weekNo,
    title,
    description: description.trim() ? description : null,
    openAt,
    closeAt,
    published: get("published").checked,
    resultsVisible,
    resultsReleaseAt: releaseAt,
  };
  // 응답한 사람이 있으면 문항과 응답 방식을 보내지 않는다(서버가 그대로 둔다).
  if (!locked) {
    payload.anonymous = get("anonymous").checked;
    payload.questions = read.questions;
  }
  const controls = [...sheet.querySelectorAll("input, textarea, button, select")];
  const before = controls.map((control) => control.disabled);
  const label = node.textContent;
  controls.forEach((control) => { control.disabled = true; });
  sheet.setAttribute("aria-busy", "true");
  node.textContent = "저장하는 중…";
  say("ASTRA 서버에 저장하고 있습니다.");
  try {
    if (editId) await updateSurvey(courseId, editId, { ...payload, revision: Number(sheet.dataset.revision) });
    else await createSurvey(courseId, payload);
    drafts.delete(`${courseId}:${editId || "new"}`);
    api.getState().toast = editId ? "설문을 수정했습니다" : "설문을 서버에 등록했습니다";
    api.navigate(pf("pf_discuss", { kj: sheet.dataset.kj }));
    return;
  } catch (error) {
    say(surveyMessage(error), true);
    if (editId && (isConflict(error, "edit_conflict") || isConflict(error, "survey_has_responses"))) {
      const path = surveyPath(courseId, editId);
      await surveyDetails.load(path, path);
      const entry = surveyDetails.peek(path);
      const latest = sheet.querySelector("[data-survey-latest]");
      if (entry && entry.data && latest && sheet.isConnected) {
        const info = entry.data.survey;
        sheet.dataset.revision = String(entry.data.revision);
        latest.replaceChildren(el("details", { class: "pf-server-pdf", open: true },
          el("summary", {}, el("b", { text: "서버의 최신 내용" }), el("span", { class: "pf-meta", text: ` · 수정 번호 ${entry.data.revision} · 응답 ${entry.data.respondentCount}명` })),
          note(`${info.title} · ${info.weekNo}주 · ${periodText(info)} · ${info.anonymous ? "익명" : "실명"} · 결과 ${info.resultsVisible ? "공개" : "비공개"}`),
        ));
      }
    } else if (!sheet.isConnected) {
      api.getState().toast = surveyMessage(error);
      api.paintToast();
    }
  } finally {
    if (sheet.isConnected) {
      sheet.removeAttribute("aria-busy");
      node.textContent = label;
      controls.forEach((control, index) => { if (control.isConnected) control.disabled = before[index]; });
      if (sheet.dataset.locked === "1") {
        const box = get("anonymous");
        if (box) box.disabled = true;
      }
    }
  }
}

function surveyHeader(course, courseId, surveyId) {
  const path = surveyPath(courseId, surveyId);
  const load = () => surveyDetails.load(path, path);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "설문 정보" }, body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.revision == null) {
      if (!entry || !entry.error) body.replaceChildren(note("설문을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("설문을 찾을 수 없습니다", "이 과목의 설문이 아니거나 삭제되었습니다.", go(pf("pf_discuss", { kj: course.id }), "설문 목록", { class: "btn btn-secondary" })));
      else body.replaceChildren(el("p", { class: "err", role: "alert", text: surveyMessage(entry.error) }), retry(load));
      return;
    }
    const info = data.survey;
    body.replaceChildren(
      el("h2", { text: info.title }),
      note(`${info.weekNo}주 · ${periodText(info)} · ${PHASE[data.phase] || data.phase} · ${info.anonymous ? "익명" : "실명"} · 응답 ${data.respondentCount}명 · 문항 ${data.questions.length}개`),
      el("div", { class: "pf-actions" }, go(pf("pf_discuss", { kj: course.id, edit: String(surveyId) }), "설문 수정", { class: "btn btn-ghost" })),
      deleteControl("survey", courseId, surveyId, { title: info.title, back: pf("pf_discuss", { kj: course.id }) }),
    );
  };
  paint(surveyDetails.peek(path));
  attached(section, () => {
    surveyDetails.watch(section, path, paint, load);
    if (freshVisit("pf-survey-head", path) === "entry" || !surveyDetails.peek(path)) load();
  });
  return section;
}

function surveyList(course, courseId) {
  const key = String(courseId);
  const load = () => surveyLists.load(key, courseId);
  const list = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "ASTRA 서버에 등록된 설문" }, el("h2", { text: "서버에 등록된 설문" }), list);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      list.replaceChildren(!entry || !entry.error ? note("서버에 등록된 설문을 불러오는 중입니다.") : el("div", {}, el("p", { class: "err", role: "alert", text: surveyMessage(entry.error) }), retry(load)));
      return;
    }
    const items = Array.isArray(data) ? data : [];
    if (!items.length) {
      list.replaceChildren(note("아직 서버에 등록된 설문이 없습니다."));
      return;
    }
    list.replaceChildren(...items.map((item) => {
      const edit = pf("pf_discuss", { kj: course.id, edit: String(item.surveyId) });
      const results = pf("pf_discuss", { kj: course.id, n: surveyRef(item.surveyId) });
      return el("div", { class: "pf-activity" },
        el("a", { class: "pf-activity-main", href: results, "data-go": results },
          badge(item.published ? PHASE[item.phase] || item.phase : "비공개", item.phase === "open" ? "success" : "neutral"),
          el("span", { class: "pf-activity-copy" },
            el("b", { text: item.title }),
            el("span", { class: "pf-meta", text: `${item.weekNo}주 · ${periodText(item)} · ${item.anonymous ? "익명" : "실명"} · ${item.questionCount}문항 · 응답 ${item.respondentCount ?? 0}명` }),
          ),
        ),
        el("div", { class: "pf-activity-actions" },
          go(edit, "수정", { class: "btn btn-ghost", "aria-label": `${item.title} 수정` }),
          go(results, "결과", { class: "btn btn-ghost", "aria-label": `${item.title} 결과` }),
        ),
      );
    }));
  };
  paint(surveyLists.peek(key));
  attached(section, () => {
    surveyLists.watch(section, key, paint, load);
    if (freshVisit("pf-surveys", key) === "entry" || !surveyLists.peek(key)) load();
  });
  return section;
}
