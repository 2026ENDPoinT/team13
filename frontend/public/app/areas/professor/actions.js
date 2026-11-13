import { el } from "../../dom.js";
import { activities } from "../../data.js";
import { saveCoursePdf, deleteCoursePdf } from "../../shared/course-pdfs.js";
import { isRemotePdfId } from "../../shared/course-pdfs-remote.js";
import { forgetMaterialList } from "../../shared/materials-remote.js";
import { backendStatus } from "../../shared/backend.js";
import { endsAfter, upsertActivity, removeActivity, activityPhase } from "../../shared/activity-library.js";
import { putActivityFile, deleteActivityFile, getActivityFile } from "../../shared/activity-files.js";
import { plainText, richPlain, sanitizeRichSource } from "../../shared/rich-text.js";
import { ApiError } from "../../shared/api.js";
import { routeFor } from "../../shared/routes.js";
import {
  createNotice, updateNotice, deleteNotice, noticeDetails, noticeKey, isVersionConflict, noticeMessage,
} from "../../shared/notices-remote.js";
import { adoptVersion } from "./notices-server.js";
import { saveAssignmentForm, toggleRelease, saveGrade } from "./assignments-server.js";
import { saveMaterialEdit, deleteMaterialEntry } from "./materials-server.js";
import { registrationPicked, saveRegistrationFromFilebox } from "./materials-server.js";
import { decideFromButton } from "./enrollments-server.js";
import { searchRoster } from "./enrollments-server.js";
import { sendProfessorMessage } from "./messages-server.js";
import { saveQuizForm, saveQuizQuestions, saveQuizGrade, moreAttempts } from "./quizzes-server.js";
import { regradeFromForm } from "./quizzes-server.js";
import { saveSurveyForm } from "./surveys-server.js";
import { gradebookCommand } from "./grades-server.js";
import { attendCommand } from "./attendance-server.js";
import { teamCommand } from "./groups-server.js";
import { createLectureEntry, saveLectureEdit, deleteLectureEntry, serverVideoCourse } from "./lectures-server.js";
import { courseById } from "../../data.js";
import { roomPublish, roomDelete, weekRoute } from "./room-server.js";
import { saveSyllabus, saveInstructorCard } from "./syllabus-server.js";
import { activityDelete } from "./delete-server.js";

export const actions = {
  /** Backend enrollment request: approve or reject with the read `version` (409 reloads the list). */
  "pf-enroll-decide"(node, api) {
    void decideFromButton(node, api);
  },
  /** Backend roster (pf_monitor): search, sort and order go into the address (roster page 1). */
  "pf-roster-search"(node, api) {
    searchRoster(node, api);
  },
  /** Backend assignment: create, or update with `revision` (409 `edit_conflict` keeps the form). */
  "pf-assignment-save"(node, api) {
    void saveAssignmentForm(node, api);
  },
  /** Backend assignment: release or withhold scores (`PUT …/assignments/{id}/release` with the read `revision`). */
  "pf-assignment-release"(node, api) {
    void toggleRelease(node, api);
  },
  "pf-grade-save"(node, api) {
    void saveGrade(node, api);
  },
  /** Backend quiz: create (with questions), or update settings with `revision`. */
  "pf-quiz-save"(node, api) {
    void saveQuizForm(node, api);
  },
  /** Backend quiz: replace all questions (409 `questions_locked` once attempts exist). */
  "pf-quiz-questions"(node, api) {
    void saveQuizQuestions(node, api);
  },
  "pf-quiz-grade"(node, api) {
    void saveQuizGrade(node, api);
  },
  /** Backend quiz attempt list: next page (`cursor`). */
  "pf-quiz-more"(node) {
    moreAttempts(node);
  },
  /** Backend course survey: create, or full update with `revision`. */
  "pf-survey-save"(node, api) {
    void saveSurveyForm(node, api);
  },
  /** Server material edit/delete (`If-Match` with the last-read `version`). */
  "pf-material-save"(node, api) {
    saveMaterialEdit(node, api);
  },
  "pf-material-delete"(node, api) {
    deleteMaterialEntry(node, api);
  },
  /** Backend 쪽지 to course members (`Idempotency-Key`, areas/professor/messages-server.js). */
  "pf-message-send"(node, api) {
    sendProfessorMessage(node, api);
  },
  /** Backend gradebook: settings, items (`revision`), bulk scores (`version` per row). */
  "pf-gradebook-cmd"(node, api) {
    void gradebookCommand(node, api);
  },
  /** Backend attendance: cells (`If-Match`), fill, settings, sessions, smart rounds. */
  "pf-attend-cmd"(node, api) {
    void attendCommand(node, api);
  },
  /** Backend groups: create, rename/delete (`If-Match`), members add/remove/move. */
  "pf-team-cmd"(node, api) {
    void teamCommand(node, api);
  },
  /** Server video lecture: create, edit (`If-Match`), delete (two presses). */
  "pf-lecture-create"(node, api) {
    createLectureEntry(node, api);
  },
  "pf-lecture-save"(node, api) {
    saveLectureEdit(node, api);
  },
  "pf-lecture-delete"(node, api) {
    deleteLectureEntry(node, api);
  },
  /** Server classroom rows: publish toggle with the read version, delete (two presses) for lectures and materials. */
  "pf-room-publish"(node, api) {
    void roomPublish(node, api);
  },
  "pf-room-delete"(node, api) {
    void roomDelete(node, api);
  },
  /** Server quiz, survey or assignment management screens: delete (two presses, `If-Match` with the detail's `revision`). */
  "pf-activity-delete"(node, api) {
    void activityDelete(node, api);
  },
  /** Backend quiz detail: correct one question's answer key and points, re-score closed attempts (`revision`). */
  "pf-quiz-regrade"(node, api) {
    void regradeFromForm(node, api);
  },
  /** 활동 등록 in a server course: carry the chosen week to that type's server form. */
  "pf-week-route"(node, api) {
    weekRoute(node, api);
  },
  /** Server syllabus and the instructor's own card (`If-Match`, first write "0"). */
  "pf-syllabus-save"(node, api) {
    void saveSyllabus(node, api);
  },
  "pf-syllabus-card-save"(node, api) {
    void saveInstructorCard(node, api);
  },
  "pf-notice-save"(node, api) {
    void saveServerNotice(node, api);
  },
  /** Two presses: the first arms the button, the second sends DELETE. */
  "pf-notice-delete"(node, api) {
    if (node.disabled) return;
    if (node.dataset.armed !== "1") {
      node.dataset.armed = "1";
      node.textContent = "한 번 더 누르면 삭제";
      return;
    }
    node.disabled = true;
    node.textContent = "삭제하는 중…";
    deleteNotice(Number(node.dataset.course), Number(node.dataset.id)).then(() => {
      api.getState().toast = "공지를 삭제했습니다";
      api.navigate(node.dataset.after);
    }, (error) => {
      if (error instanceof ApiError && error.status === 404) {
        api.getState().toast = "이미 삭제되었거나 찾을 수 없는 공지입니다. 목록을 새로 불러왔습니다.";
        api.navigate(node.dataset.after);
        return;
      }
      if (node.isConnected) {
        node.disabled = false;
        node.dataset.armed = "";
        node.textContent = "삭제";
      }
      api.getState().toast = noticeMessage(error);
      api.paintToast();
    });
  },
  "save-scores"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    for (const [key, value] of Object.entries(data)) {
      if (!key.startsWith("score-") || value === "") continue;
      const n = Number(value);
      if (!Number.isInteger(n) || n < 0 || n > 100) return api.showErrors({ [key]: "점수는 0부터 100 사이의 정수입니다" });
    }
    for (const [key, value] of Object.entries(data)) {
      if (!key.startsWith("score-")) continue;
      const id = key.slice(6);
      state.scores[id] = state.scores[id] || {};
      state.scores[id][node.dataset.item || "r-open"] = value === "" ? "" : Number(value);
    }
    state.toast = "점수를 저장했습니다";
    api.save();
    api.render();
  },
  "save-exam-score"(node, api) {
    const state = api.getState();
    const n = Number(api.readForm().score);
    if (!Number.isInteger(n) || n < 0 || n > 5) return api.showErrors({ score: "0부터 5 사이의 정수입니다" });
    state.scores.minjun = state.scores.minjun || {};
    state.scores.minjun.examQ = n;
    state.toast = "문항 점수를 저장했습니다";
    api.save();
    api.render();
  },
  "add-bank"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.bankTitle || "").trim()) return api.showErrors({ bankTitle: "이름을 입력하세요" });
    state.banks.unshift({ id: `b-${Date.now()}`, title: data.bankTitle.trim(), count: 0, updated: api.stamp().slice(0, 10), courseId: state.courseId });
    state.toast = "문제은행을 추가했습니다";
    api.save();
    api.render();
  },
  "add-question"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const errors = {};
    if (!String(data.stem || "").trim() || String(data.stem).trim().length < 4) errors.stem = "질문을 4자 이상 입력하세요";
    const choices = String(data.choices || "").split("\n").map((line) => line.trim()).filter(Boolean);
    if (data.qtype === "객관식" && choices.length < 2) errors.choices = "보기를 두 줄 이상 입력하세요";
    const points = Number(data.points);
    if (!Number.isInteger(points) || points < 1 || points > 20) errors.points = "배점은 1부터 20입니다";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.questions.push({ id: `q-${Date.now()}`, bankId: data.bankId, type: data.qtype, points, stem: data.stem.trim(), choices, answer: 0 });
    const bank = state.banks.find((b) => b.id === data.bankId);
    if (bank) bank.count += 1;
    state.toast = "문항을 저장했습니다";
    api.save();
    api.render();
  },
  "save-makeup"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const errors = {};
    if (!data.week) errors.week = "주차를 입력하세요";
    if (!data.when) errors.when = "일시를 입력하세요";
    if (!String(data.why || "").trim()) errors.why = "사유를 입력하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.draft.makeups = state.draft.makeups || [];
    state.draft.makeups.push({ week: data.week, when: data.when, why: data.why.trim() });
    state.toast = "보강을 등록했습니다";
    api.save();
    api.render();
  },
  "save-gradebook"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const weights = {};
    let sum = 0;
    for (const key of ["att", "rep", "mid", "fin"]) {
      const n = Number(data[`w-${key}`]);
      if (!Number.isInteger(n) || n < 0 || n > 100) return api.showErrors({ [`w-${key}`]: "반영 비율은 0부터 100 사이의 정수입니다" });
      weights[key] = n;
      sum += n;
    }
    if (sum !== 100) return api.showErrors({ weights: "반영 비율의 합이 100이어야 합니다" });
    for (const [key, value] of Object.entries(data)) {
      if (!key.startsWith("gb-") || value === "") continue;
      const n = Number(value);
      if (!Number.isInteger(n) || n < 0 || n > 100) return api.showErrors({ [key]: "점수는 0부터 100 사이의 정수입니다" });
    }
    state.weights = weights;
    for (const [key, value] of Object.entries(data)) {
      if (!key.startsWith("gb-")) continue;
      const [, id, item] = key.split("-");
      state.scores[id] = state.scores[id] || {};
      state.scores[id][item] = value === "" ? "" : Number(value);
    }
    state.toast = "성적부를 저장했습니다";
    api.save();
    api.render();
  },
  "toggle-public"(node, api) {
    const state = api.getState();
    state.gradePublic = !state.gradePublic;
    state.toast = state.gradePublic ? "학생에게 성적을 공개합니다" : "성적을 비공개로 바꿨습니다";
    api.save();
    api.render();
  },
  "save-appeal"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const key = `reply-${node.dataset.id}`;
    if (!String(data[key] || "").trim()) return api.showErrors({ [key]: "답변을 입력하세요" });
    const item = state.appeals.find((a) => a.id === node.dataset.id);
    if (item) { item.reply = data[key].trim(); item.status = "답변"; }
    state.toast = "답변을 저장했습니다";
    api.save();
    api.render();
  },
  "save-team"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const errors = {};
    if (!String(data.name || "").trim()) errors.name = "팀 이름을 입력하세요";
    if (!String(data.topic || "").trim()) errors.topic = "주제를 입력하세요";
    const size = Number(data.size);
    if (!Number.isInteger(size) || size < 2 || size > 6) errors.size = "인원은 2부터 6입니다";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.teams.push({ name: data.name.trim(), topic: data.topic.trim(), size });
    state.toast = "팀을 만들었습니다";
    api.save();
    api.render();
  },
  "save-ta"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.name || "").trim()) return api.showErrors({ name: "이름을 입력하세요" });
    state.tas.push({ name: data.name.trim(), role: data.role || "조교", courseId: state.courseId });
    state.toast = "추가했습니다";
    api.save();
    api.render();
  },
  "pf-save"(node, api) {
    if (node.dataset.form === "material") {
      void saveMaterial(node, api);
      return;
    }
    if (node.dataset.form === "activity") {
      void saveActivity(node, api);
      return;
    }
    saveProfessor(node.dataset.form, api.readForm(), api);
  },
  "activity-type"(node) {
    const form = node.closest(".pf-activity-form");
    if (!form) return;
    const input = form.querySelector("[data-name=type]");
    const next = node.dataset.value || "video";
    if (input) input.value = next;
    form.querySelectorAll("[data-type-panel]").forEach((panel) => {
      panel.hidden = panel.dataset.typePanel !== next;
    });
    form.querySelectorAll("[data-act=activity-type]").forEach((button) => {
      const on = button.dataset.value === next;
      button.classList.toggle("on", on);
      button.setAttribute("aria-pressed", on ? "true" : "false");
    });
  },
  "editor-insert"(node) {
    const form = node.closest(".pf-activity-form") || document;
    const box = form.querySelector("textarea[data-name=body]");
    if (!box || typeof box.setRangeText !== "function") return;
    const cmd = node.dataset.cmd;
    const start = box.selectionStart || 0;
    const end = box.selectionEnd || 0;
    const selected = box.value.slice(start, end) || (cmd === "link" ? "안내" : cmd === "list" ? "항목" : "굵게");
    let insert = selected;
    if (cmd === "bold") insert = `**${selected}**`;
    else if (cmd === "list") insert = selected.split("\n").map((line) => (line.startsWith("- ") ? line : `- ${line}`)).join("\n");
    else if (cmd === "link") insert = `[${selected}]()`;
    box.setRangeText(insert, start, end, "end");
    box.focus();
  },
  "activity-download"(node) {
    const id = node.dataset.file;
    const name = node.dataset.filename || "download";
    getActivityFile(id).then((blob) => {
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = name;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
    }).catch(() => {});
  },
  "pf-delete-activity"(node, api) {
    const state = api.getState();
    state.draft.pfDialog = { kind: "delete-activity", id: node.dataset.id };
    api.save();
    api.render();
  },
  attend(node, api) {
    const state = api.getState();
    const key = `${node.dataset.who}:main:${node.dataset.week}`;
    state.attendance[key] = node.value;
    state.toast = "출석을 바꿨습니다";
    api.save();
    api.paintToast();
  },
  "pf-find"(node, api) {
    const q = String(api.readForm().q || "").trim();
    const url = new URL(location.href);
    if (q) url.searchParams.set("q", q);
    else url.searchParams.delete("q");
    api.navigate(`${url.pathname}${url.search}`);
  },
  "pf-sort"(node, api) {
    const state = api.getState();
    const table = node.dataset.table;
    const key = node.dataset.key;
    const current = state.draft.pfSort || {};
    const dir = current.table === table && current.key === key && current.dir === "asc" ? "desc" : "asc";
    state.draft.pfSort = { table, key, dir };
    api.save();
    api.render();
  },
  "pf-edit-question"(node, api) {
    const state = api.getState();
    state.draft.pfDialog = { kind: "question", id: node.dataset.id };
    api.save();
    api.render();
  },
  "pf-edit-bank"(node, api) {
    const state = api.getState();
    state.draft.pfDialog = { kind: "bank", id: node.dataset.id };
    api.save();
    api.render();
  },
  "pf-delete-question"(node, api) {
    const state = api.getState();
    state.draft.pfDialog = { kind: "delete-question", id: node.dataset.id };
    api.save();
    api.render();
  },
  "pf-delete-bank"(node, api) {
    const state = api.getState();
    state.draft.pfDialog = { kind: "delete-bank", id: node.dataset.id };
    api.save();
    api.render();
  },
  "pf-dismiss"(node, api) {
    const state = api.getState();
    state.draft.pfDialog = null;
    api.save();
    api.render();
  },
  "pf-save-bank"(node, api) {
    const state = api.getState();
    const title = String(api.readForm().editBankTitle || "").trim();
    if (!title) return api.showErrors({ editBankTitle: "이름을 입력하세요" });
    const bank = state.banks.find((item) => item.id === node.dataset.id);
    if (bank) bank.title = title;
    state.draft.pfDialog = null;
    state.toast = "문제은행을 수정했습니다";
    api.save();
    api.render();
  },
  "pf-save-question"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const errors = {};
    if (!String(data.editStem || "").trim() || String(data.editStem).trim().length < 4) errors.editStem = "질문을 4자 이상 입력하세요";
    const choices = String(data.editChoices || "").split("\n").map((line) => line.trim()).filter(Boolean);
    if (data.editQtype === "객관식" && choices.length < 2) errors.editChoices = "보기를 두 줄 이상 입력하세요";
    const points = Number(data.editPoints);
    if (!Number.isInteger(points) || points < 1 || points > 20) errors.editPoints = "배점은 1부터 20입니다";
    if (Object.keys(errors).length) return api.showErrors(errors);
    const question = state.questions.find((item) => item.id === node.dataset.id);
    if (!question) return api.showErrors({ editStem: "문항을 찾지 못했습니다" });
    const prev = question.bankId;
    question.bankId = data.editBankId || prev;
    question.type = data.editQtype;
    question.points = points;
    question.stem = data.editStem.trim();
    question.choices = data.editQtype === "객관식" ? choices : [];
    if (prev !== question.bankId) {
      const from = state.banks.find((item) => item.id === prev);
      const to = state.banks.find((item) => item.id === question.bankId);
      if (from && from.count > 0) from.count -= 1;
      if (to) to.count += 1;
    }
    state.draft.pfDialog = null;
    state.toast = "문항을 수정했습니다";
    api.save();
    api.render();
  },
  "pf-confirm-delete"(node, api) {
    const state = api.getState();
    const dialog = state.draft.pfDialog || {};
    if (dialog.kind === "delete-question") {
      const question = state.questions.find((item) => item.id === dialog.id);
      state.questions = state.questions.filter((item) => item.id !== dialog.id);
      if (question) {
        const bank = state.banks.find((item) => item.id === question.bankId);
        if (bank && bank.count > 0) bank.count -= 1;
      }
      state.toast = "문항을 삭제했습니다";
    } else if (dialog.kind === "delete-activity") {
      const item = activities.find((row) => row.id === dialog.id);
      removeActivity(state, dialog.id);
      state.toast = item && isRemotePdfId(item.pdfId) ? "활동을 삭제했습니다. ASTRA 서버에 등록한 자료는 서버에 남아 있습니다." : "활동을 삭제했습니다";
      if (item?.fileId) deleteActivityFile(item.fileId).catch(() => {});
      if (item?.id) deleteActivityFile(item.id).catch(() => {});
      if (item?.pdfId) deleteCoursePdf(item.pdfId).catch(() => {});
    } else if (dialog.kind === "delete-bank") {
      const used = state.questions.some((item) => item.bankId === dialog.id);
      if (used) {
        state.toast = "문항이 남아 있으면 은행을 삭제할 수 없습니다";
        api.save();
        api.render();
        return;
      }
      state.banks = state.banks.filter((item) => item.id !== dialog.id);
      state.toast = "문제은행을 삭제했습니다";
    }
    state.draft.pfDialog = null;
    api.save();
    api.render();
  },
};

async function saveMaterial(node, api) {
  if (node.disabled) return;
  const scope = node.closest(".sheet");
  const status = scope.querySelector("[data-material-status]");
  const input = scope.querySelector('[data-name="file"]');
  const titleInput = scope.querySelector('[data-name="title"]');
  const file = input.files && input.files[0];
  const title = titleInput.value.trim();
  if (registrationPicked(api.getState().courseId)) return saveRegistrationFromFilebox(node, api, { title, file });
  const errors = {};
  if (!title) errors.title = "자료명을 입력하세요";
  if (!file) errors.file = "파일을 선택하세요";
  if (Object.keys(errors).length) {
    status.textContent = Object.values(errors).join(". ");
    api.showErrors(errors);
    return;
  }
  const state = api.getState();
  const courseId = state.courseId;
  const isPdf = file.type === "application/pdf" || /\.pdf$/i.test(file.name);
  const controls = [...scope.querySelectorAll("input, select, textarea, button")];
  const disabled = controls.map((control) => control.disabled);
  const label = node.textContent;
  document.querySelectorAll(".err.live").forEach((error) => error.remove());
  controls.forEach((control) => { control.disabled = true; });
  scope.setAttribute("aria-busy", "true");
  const remote = isPdf && backendStatus() === "ready";
  node.textContent = remote ? "서버에 올리는 중…" : isPdf ? "PDF 분석 중…" : "등록 중…";
  status.textContent = remote
    ? "PDF를 ASTRA 서버에 올리고 파일 검사가 끝나면 자료로 등록합니다."
    : isPdf ? "PDF의 페이지와 텍스트를 분석하고 이 브라우저에 저장하고 있습니다." : "자료를 등록하고 있습니다.";
  try {
    const pdf = isPdf ? await saveCoursePdf({ file, courseId, title, weekNo: state.week || 1 }) : null;
    // A server registration is listed from the server; only a browser-local material is kept in the sample state.
    if (!pdf?.remote) {
      state.materialsExtra.unshift(pdf
        ? { id: pdf.id, pdfId: pdf.id, courseId, title, file: file.name }
        : { title, file: file.name });
    }
    state.toast = pdf?.remote
      ? "PDF를 ASTRA 서버에 자료로 등록했습니다. 아래 목록에서 쪽별 요약을 만들 수 있습니다."
      : pdf?.status === "no-text"
        ? "PDF를 저장했습니다. 추출할 텍스트가 없어 학습실에서 원본을 확인할 수 있습니다."
        : pdf ? "PDF를 분석해 과목 학습실에 저장했습니다" : "저장했습니다";
    // The server material list under the form reads the new material on this render.
    if (pdf?.remote) forgetMaterialList(pdf.backendCourseId);
    api.save();
    if (node.isConnected) api.render();
    else api.paintToast();
  } catch (error) {
    const message = error.message || "자료를 등록하지 못했습니다. 파일을 확인한 뒤 다시 시도하세요.";
    status.textContent = message;
    if (node.isConnected) api.showErrors({ file: message });
    else {
      state.toast = message;
      api.paintToast();
    }
  } finally {
    controls.forEach((control, index) => { control.disabled = disabled[index]; });
    scope.removeAttribute("aria-busy");
    node.textContent = label;
  }
}

function validHttpUrl(value) {
  try {
    const url = new URL(String(value || "").trim());
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

async function saveActivity(node, api) {
  if (node.disabled) return;
  const state = api.getState();
  const data = api.readForm();
  const form = node.closest(".pf-activity-form");
  const editing = form && form.dataset.edit ? activities.find((item) => item.id === form.dataset.edit) : null;
  const type = data.type || "video";
  const title = String(data.title || "").trim();
  const errors = {};
  if (!title) errors.title = "제목을 입력하세요";
  else if (title.length < 2) errors.title = "제목은 두 글자 이상이어야 합니다";
  const week = Number(data.week);
  if (!Number.isInteger(week) || week < 1 || week > 14) errors.week = "주차는 1부터 14입니다";
  if (!["video", "zoom", "material", "post", "report", "test", "survey"].includes(type)) errors.type = "유형을 선택하세요";
  // Backend mode: a video lecture is registered by its own server sheet in the video panel.
  if (type === "video" && serverVideoCourse(courseById(state.courseId))) {
    state.toast = "영상 강의는 위의 ASTRA 서버에 등록 버튼으로 등록합니다";
    api.paintToast();
    return;
  }
  const videoFile = form && form.querySelector("[data-name=videoFile]") && form.querySelector("[data-name=videoFile]").files
    ? form.querySelector("[data-name=videoFile]").files[0]
    : null;
  const materialFile = form && form.querySelector("[data-name=materialFile]") && form.querySelector("[data-name=materialFile]").files
    ? form.querySelector("[data-name=materialFile]").files[0]
    : null;
  if (type === "video") {
    if (!data.attendStart) errors.attendStart = "출석 시작을 입력하세요";
    if (!data.attendEnd) errors.attendEnd = "출석 종료를 입력하세요";
    else if (data.attendStart && !endsAfter(data.attendStart, data.attendEnd)) errors.attendEnd = "종료는 시작 이후여야 합니다";
  } else if (type === "zoom") {
    if (!validHttpUrl(data.meetingUrl)) errors.meetingUrl = "http 또는 https 회의 링크를 입력하세요";
    if (!data.zoomStart) errors.zoomStart = "시작 시각을 입력하세요";
    if (!data.zoomEnd) errors.zoomEnd = "종료 시각을 입력하세요";
    else if (data.zoomStart && !endsAfter(data.zoomStart, data.zoomEnd)) errors.zoomEnd = "종료는 시작 이후여야 합니다";
  } else if (type === "material") {
    if (!materialFile && !(editing && editing.type === "material" && (editing.fileId || editing.pdfId))) errors.materialFile = "파일을 선택하세요";
  } else if (type === "post") {
    if (richPlain(data.body || "").length < 2) errors.body = "본문을 두 글자 이상 입력하세요";
  } else if (type === "report") {
    if (!data.reportStart) errors.reportStart = "시작을 입력하세요";
    if (!data.reportDue) errors.reportDue = "마감을 입력하세요";
    else if (data.reportStart && !endsAfter(data.reportStart, data.reportDue)) errors.reportDue = "마감은 시작 이후여야 합니다";
    const points = Number(data.points);
    if (!Number.isInteger(points) || points < 1 || points > 100) errors.points = "배점은 1부터 100입니다";
  } else if (type === "test") {
    if (!data.testStart) errors.testStart = "시작을 입력하세요";
    if (!data.testDue) errors.testDue = "종료를 입력하세요";
    else if (data.testStart && !endsAfter(data.testStart, data.testDue)) errors.testDue = "종료는 시작 이후여야 합니다";
    if (String(data.minutes || "").trim() !== "") {
      const minutes = Number(data.minutes);
      if (!Number.isInteger(minutes) || minutes < 0 || minutes > 300) errors.minutes = "제한 시간은 0부터 300분입니다";
    }
  } else if (type === "survey") {
    if (!data.surveyDue) errors.surveyDue = "마감을 입력하세요";
  }
  if (Object.keys(errors).length) return api.showErrors(errors);

  const id = editing ? editing.id : `act-${Date.now()}`;
  const courseId = state.courseId || (editing && editing.courseId) || "bi";
  const item = { id, courseId, week, type, title, authored: true, body: "" };
  node.disabled = true;
  try {
    if (type === "video") {
      item.body = plainText(data.videoBody);
      item.attendStart = data.attendStart;
      item.attendEnd = data.attendEnd;
      item.start = data.attendStart;
      item.due = data.attendEnd;
      item.clipId = data.clip || "bi";
      if (videoFile) {
        await putActivityFile(id, videoFile);
        item.fileId = id;
        item.fileName = videoFile.name;
      } else if (editing && editing.type === "video") {
        item.fileId = editing.fileId || "";
        item.fileName = editing.fileName || "";
      }
    } else if (type === "zoom") {
      item.body = plainText(data.zoomBody);
      item.meetingUrl = new URL(String(data.meetingUrl).trim()).href;
      item.start = data.zoomStart;
      item.end = data.zoomEnd;
      item.when = `${data.zoomStart} – ${data.zoomEnd}`;
    } else if (type === "material") {
      item.body = plainText(data.materialBody);
      if (materialFile) {
        const isPdf = materialFile.type === "application/pdf" || /\.pdf$/i.test(materialFile.name || "");
        if (isPdf) {
          const pdf = await saveCoursePdf({ file: materialFile, courseId, title, weekNo: week });
          if (editing && editing.pdfId && editing.pdfId !== pdf.id) deleteCoursePdf(editing.pdfId).catch(() => {});
          if (editing && editing.fileId) deleteActivityFile(editing.fileId).catch(() => {});
          item.pdfId = pdf.id;
          item.fileName = materialFile.name;
          item.fileId = "";
        } else {
          await putActivityFile(id, materialFile);
          if (editing && editing.pdfId) deleteCoursePdf(editing.pdfId).catch(() => {});
          item.fileId = id;
          item.fileName = materialFile.name;
          item.pdfId = "";
        }
      } else if (editing && editing.type === "material") {
        item.pdfId = editing.pdfId || "";
        item.fileId = editing.fileId || "";
        item.fileName = editing.fileName || "";
      }
    } else if (type === "post") {
      item.body = sanitizeRichSource(data.body || "");
    } else if (type === "report") {
      item.body = plainText(data.reportBody);
      item.start = data.reportStart;
      item.due = data.reportDue;
      item.points = Number(data.points);
    } else if (type === "test") {
      item.body = plainText(data.testBody);
      item.start = data.testStart;
      item.due = data.testDue;
      if (String(data.minutes || "").trim() !== "") item.minutes = Number(data.minutes);
    } else if (type === "survey") {
      item.body = plainText(data.surveyBody);
      item.due = data.surveyDue;
    }
    const phase = activityPhase(item);
    if (type === "report") item.status = phase === "expired" ? "expired" : "missing";
    else if (type === "test") item.status = phase === "expired" ? "expired" : phase === "open" ? "scheduled" : "ongoing";
    else item.status = phase;
    upsertActivity(state, item);
    state.week = week;
    state.courseId = courseId;
    state.formError = {};
    state.toast = editing ? "활동을 수정했습니다" : "활동을 등록했습니다";
    api.save();
    api.navigate(`/ilos/cls/pf/submain/submain_form.acl?mode=week&kj=${encodeURIComponent(courseId)}&as=pf`);
  } catch (error) {
    if (node.isConnected) node.disabled = false;
    const message = error.message || "활동을 저장하지 못했습니다";
    const key = type === "material" ? "materialFile" : type === "video" ? "videoFile" : "title";
    api.showErrors({ [key]: message });
  }
}

function saveProfessor(form, data, api) {
  const state = api.getState();
  const errors = {};
  if (form === "contents") {
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!data.file) errors.file = "파일을 선택하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.contents.unshift({ id: `c-${Date.now()}`, title: data.title.trim(), minutes: 10, at: api.stamp().slice(0, 10) });
  } else if (form === "zoom") {
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!data.start) errors.start = "시작 시각을 입력하세요";
    const meeting = String(data.meeting || "").replace(/\s/g, "");
    if (!/^\d{9,11}$/.test(meeting)) errors.meeting = "회의 번호는 숫자 9자리에서 11자리입니다";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.zooms.unshift({ title: data.title.trim(), meeting, start: data.start, week: data.week });
  } else if (form === "notice") {
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!String(data.body || "").trim()) errors.body = "본문을 입력하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.noticesExtra.unshift({ id: `n-${Date.now()}`, scope: state.courseId, title: data.title.trim(), body: data.body.trim(), date: api.stamp().slice(0, 10), author: "박은주", audience: "all" });
  } else if (form === "report") {
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!data.start || !data.due) errors.due = "시작과 마감을 입력하세요";
    else if (new Date(data.due) <= new Date(data.start)) errors.due = "마감은 시작 이후여야 합니다";
    const points = Number(data.points);
    if (!Number.isInteger(points) || points < 1 || points > 100) errors.points = "배점은 1부터 100입니다";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.madeAssignments.unshift({ title: data.title.trim(), start: data.start, due: data.due, points, late: Boolean(data.late) });
    activities.push({ id: `r-${Date.now()}`, courseId: state.courseId, week: 9, type: "report", title: data.title.trim(), status: "missing", start: data.start, due: data.due, points, body: "교수 화면에서 출제한 샘플 과제입니다." });
  } else if (form === "exam") {
    if (!String(data.title || "").trim()) errors.title = "시험명을 입력하세요";
    const minutes = Number(data.minutes);
    if (!Number.isInteger(minutes) || minutes < 5) errors.minutes = "제한 시간은 5분 이상입니다";
    const ids = data.qid || [];
    if (!ids.length) errors.questions = "문항을 하나 이상 선택하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.madeExams.unshift({ title: data.title.trim(), minutes, questions: ids });
  } else if (form === "discuss") {
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!String(data.body || "").trim()) errors.body = "안내를 입력하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.posts.unshift({ id: `d-${Date.now()}`, board: data.kind, title: data.title.trim(), body: data.body.trim(), author: "박은주", at: api.stamp() });
  } else if (form === "pmessage") {
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!String(data.body || "").trim()) errors.body = "내용을 입력하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.messages.unshift({ id: `msg-${Date.now()}`, folder: "sent", from: "박은주", to: data.to === "all" ? "수강생 전체" : data.to, title: data.title.trim(), body: data.body.trim(), at: api.stamp(), read: true });
  }
  state.toast = "저장했습니다";
  api.save();
  api.render();
}

/** Backend mode: create or update a course notice from the notice screen (areas/professor/notices-server.js). */
async function saveServerNotice(node, api) {
  if (node.disabled) return;
  const sheet = node.closest("[data-notice-form]");
  if (!sheet) return;
  const field = (name) => sheet.querySelector(`[data-name="${name}"]`);
  const status = sheet.querySelector("[data-notice-status]");
  const latest = sheet.querySelector("[data-notice-latest]");
  const title = field("title").value.trim();
  const body = field("body").value;
  const errors = {};
  if (!title) errors.title = "제목을 입력하세요";
  else if (title.length > 300) errors.title = "제목은 300자 이하입니다";
  if (!body.trim()) errors.body = "본문을 입력하세요";
  else if (body.length > 20000) errors.body = "본문은 20,000자 이하입니다";
  if (Object.keys(errors).length) return api.showErrors(errors);

  const courseId = Number(sheet.dataset.course);
  const editId = Number(sheet.dataset.edit) || null;
  const draft = field("draft").checked;
  const payload = {
    title,
    body,
    pinned: field("pin").checked,
    published: !draft,
    publishAt: draft ? null : sheet.dataset.publishAt || null,
  };
  const controls = [...sheet.querySelectorAll("input, textarea, button")];
  const label = node.textContent;
  document.querySelectorAll(".err.live").forEach((error) => error.remove());
  controls.forEach((control) => { control.disabled = true; });
  sheet.setAttribute("aria-busy", "true");
  node.textContent = "저장하는 중…";
  status.setAttribute("role", "status");
  status.textContent = "ASTRA 서버에 저장하고 있습니다.";
  try {
    if (editId) await updateNotice(courseId, editId, { ...payload, version: Number(sheet.dataset.version) });
    else await createNotice(courseId, payload);
    api.getState().toast = draft ? "공지를 임시 저장했습니다" : editId ? "공지를 수정했습니다" : "공지를 서버에 등록했습니다";
    api.navigate(routeFor("professor", "screen", { screenId: "pf_notice_write", kj: sheet.dataset.kj }));
    return;
  } catch (error) {
    status.setAttribute("role", "alert");
    status.textContent = noticeMessage(error);
    if (editId && isVersionConflict(error)) await showLatest(sheet, latest, status, courseId, editId);
    else if (!sheet.isConnected) {
      api.getState().toast = noticeMessage(error);
      api.paintToast();
    }
  } finally {
    if (sheet.isConnected) {
      controls.forEach((control) => { control.disabled = false; });
      sheet.removeAttribute("aria-busy");
      node.textContent = label;
    }
  }
}

/** After a 409 version_conflict: load the current notice, keep the typed text, show the server copy. */
async function showLatest(sheet, latest, status, courseId, editId) {
  const key = noticeKey(courseId, editId);
  await noticeDetails.load(key, courseId, editId);
  const entry = noticeDetails.peek(key);
  if (!sheet.isConnected) return;
  if (!entry || !entry.data) {
    status.textContent = `다른 곳에서 이 공지를 먼저 수정했지만 최신 내용을 불러오지 못했습니다. ${noticeMessage(entry && entry.error)}`;
    return;
  }
  const notice = entry.data;
  adoptVersion(sheet, notice);
  latest.replaceChildren(el("details", { class: "pf-server-pdf", open: true },
    el("summary", {}, el("b", { text: "서버의 최신 내용" }), el("span", { class: "pf-meta", text: ` · 버전 ${notice.version}` })),
    el("p", { class: "pf-sub", text: notice.title }),
    el("p", { class: "pf-notice-body", text: notice.body || "" }),
  ));
}
