// Professor assignment screens backed by the ASTRA backend (backend mode): 과제 출제 (create, edit
// with `revision`, list with submission counts), 제출 현황 (submission list, score release), and
// 과제 채점 (one submission with its versions and attachments, score and feedback).
// Saving is wired through professor/actions.js (`pf-assignment-*`, `pf-grade-save`).
import { el, icon } from "../../dom.js";
import { btn, field, go, badge, emptyState, table } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, courseIdFor, roleIn } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { routeFor } from "../../shared/routes.js";
import {
  assignmentLists, assignmentDetails, submissionLists, submissionDetails, listKey, assignmentKey, submissionKey,
  assignmentIdOf, serverRef, createAssignment, updateAssignment, gradeSubmission, releaseScores,
  assignmentMessage, isConflict, PHASE, STATUS, scoreText, receiptTime, periodText,
} from "../../shared/assignments-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { deleteControl } from "./delete-server.js";

const missing = (error) => error instanceof ApiError && error.status === 404;
const pf = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

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
const failure = (error, reload) => el("div", {}, el("p", { class: "err", role: "alert", text: assignmentMessage(error) }), reload ? retry(reload) : null);

/** Paragraphs of plain text; every piece is a text node. */
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

/** Login strip, unmapped course or wrong role: a node to show instead of the server screen. */
function gate(course, loginText) {
  if (backendStatus() === "anon") return stack(backendPanel({ text: loginText }));
  if (!courseIdFor(course.id)) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목에만 과제를 내고 채점할 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") {
    return stack(backendPanel(), note("이 과목을 담당하는 교수 계정으로 로그인해야 과제를 내고 채점할 수 있습니다."));
  }
  return null;
}

const LOGIN = "ASTRA 서버에 로그인하면 이 과목의 과제를 서버에 내고 제출물을 채점할 수 있습니다.";

/** `datetime-local` value for a server LocalDateTime, and back. */
const toInput = (value) => (value ? String(value).slice(0, 16) : "");
const fromInput = (value) => (value ? (value.length === 16 ? `${value}:00` : value) : null);
/** At most two decimal places (the server's DECIMAL(6,2)). */
const hundredths = (n) => Math.abs(Math.round(n * 100) - n * 100) < 1e-6;

// ---------- 과제 출제: form + list ----------

/** `pf_report_create` in backend mode. */
export function serverReportFields(ctx, course, parts) {
  const blocked = gate(course, LOGIN);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const editId = Number((ctx.params && ctx.params.get("edit")) || "");
  return stack(
    backendPanel(),
    assignmentForm(ctx, course, courseId, Number.isInteger(editId) && editId > 0 ? editId : null, parts),
    assignmentList(course, courseId, "pf_report_create"),
  );
}

function assignmentForm(ctx, course, courseId, editId, parts) {
  const { formSheet, span, linkBtn } = parts;
  const input = (name, attrs = {}) => el("input", { "data-field": name, ...attrs });
  const check = (name, on) => {
    const box = input(name, { type: "checkbox" });
    box.checked = on;
    return box;
  };
  const title = input("title", { maxlength: "200" });
  const week = input("weekNo", { type: "number", min: "1", max: "60", value: String(ctx.state.week || 1) });
  const maxScore = input("maxScore", { type: "number", min: "0.01", max: "1000", step: "0.01", value: "20" });
  const openAt = input("openAt", { type: "datetime-local" });
  const closeAt = input("closeAt", { type: "datetime-local" });
  const late = check("late", false);
  const lateUntil = input("lateUntil", { type: "datetime-local" });
  const allowText = check("allowText", true);
  const allowFile = check("allowFile", true);
  const published = check("published", true);
  const released = check("scoresReleased", false);
  const description = el("textarea", { "data-field": "description", rows: "6", maxlength: "10000" });
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-assignment-status": "" });
  const save = btn(editId ? "수정 저장" : "과제 등록", { "data-act": "pf-assignment-save" }, "primary");
  const choice = (box, text) => el("label", { class: "choice pf-check" }, box, el("span", { text }));
  const sheet = formSheet(editId ? "과제 수정" : "과제",
    span(field("제목", title)),
    field("주차", week),
    field("배점", maxScore),
    field("시작 (비우면 바로 시작)", openAt),
    field("마감", closeAt),
    choice(late, "마감 뒤 지각 제출 받기"),
    field("지각 제출 마감", lateUntil),
    choice(allowText, "글 입력 제출"),
    choice(allowFile, "파일 첨부 제출"),
    span(choice(published, "학생에게 공개 (끄면 학생 목록에 나오지 않습니다)")),
    span(choice(released, "점수 바로 공개")),
    span(note("점수를 공개하면 채점한 점수와 의견이 학생마다 자기 과제 화면에 보입니다. 공개하기 전에는 학생이 받는 응답에 점수가 아예 들어가지 않습니다.")),
    span(field("설명 (일반 텍스트)", description)),
    span(status),
    span(el("div", { "data-assignment-latest": "" })),
    span(el("div", { class: "pf-actions" }, save, editId ? linkBtn(pf("pf_report_create", { kj: course.id }), "새 과제 쓰기") : null)),
    editId ? span(el("div", { "data-assignment-delete": "" })) : null,
  );
  sheet.dataset.assignmentForm = "";
  sheet.dataset.course = String(courseId);
  sheet.dataset.kj = course.id;
  sheet.dataset.edit = editId ? String(editId) : "";
  if (!editId) return sheet;

  // Edit: fill the form once from the server copy; a conflict reload leaves typed text alone.
  const key = assignmentKey(courseId, editId);
  const controls = [...sheet.querySelectorAll("input, textarea, button")];
  let filled = false;
  const deleteSlot = sheet.querySelector("[data-assignment-delete]");
  const paint = (entry) => {
    const data = entry && entry.data;
    if (data && data.revision != null) {
      deleteSlot.replaceChildren(deleteControl("assignment", courseId, editId, { title: data.assignment.title, back: pf("pf_report_create", { kj: course.id }) }));
    }
    if (filled) return;
    if (!data || data.revision == null) {
      controls.forEach((control) => { control.disabled = true; });
      status.setAttribute("role", entry && entry.error ? "alert" : "status");
      status.textContent = !entry || !entry.error ? "과제를 불러오는 중입니다."
        : missing(entry.error) ? "과제를 찾을 수 없습니다. 이 과목의 과제가 아닙니다." : assignmentMessage(entry.error);
      return;
    }
    filled = true;
    const info = data.assignment;
    title.value = info.title;
    week.value = String(info.weekNo);
    maxScore.value = scoreText(info.maxScore);
    openAt.value = toInput(info.openAt);
    closeAt.value = toInput(info.closeAt);
    late.checked = info.latePolicy === "allow_until";
    lateUntil.value = toInput(info.lateUntil);
    allowText.checked = Boolean(info.allowText);
    allowFile.checked = Boolean(info.allowFile);
    published.checked = Boolean(info.published);
    released.checked = Boolean(info.scoresReleased);
    description.value = info.description || "";
    sheet.dataset.revision = String(data.revision);
    controls.forEach((control) => { control.disabled = false; });
    status.setAttribute("role", "status");
    status.textContent = `수정 번호 ${data.revision} · 제출 ${data.submittedCount}명 · 지각 ${data.lateCount}명`;
  };
  paint(assignmentDetails.peek(key));
  attached(sheet, () => {
    assignmentDetails.watch(sheet, key, paint, null);
    if (freshVisit("pf-assignment-edit", key) === "entry" || !assignmentDetails.peek(key)) assignmentDetails.load(key, courseId, editId);
  });
  return sheet;
}

/** Reads and checks the form; `{ errors }` or `{ payload }`. */
function readAssignment(sheet) {
  const get = (name) => sheet.querySelector(`[data-field="${name}"]`);
  const errors = [];
  const title = get("title").value.trim();
  const weekNo = Number(get("weekNo").value);
  const maxScore = Number(get("maxScore").value);
  const openAt = fromInput(get("openAt").value);
  const closeAt = fromInput(get("closeAt").value);
  const late = get("late").checked;
  const lateUntil = late ? fromInput(get("lateUntil").value) : null;
  const description = get("description").value;
  if (!title || title.length > 200) errors.push("제목은 1자 이상 200자 이하입니다");
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) errors.push("주차는 1부터 60 사이입니다");
  if (!Number.isFinite(maxScore) || maxScore <= 0 || maxScore > 1000 || !hundredths(maxScore)) errors.push("배점은 0보다 크고 1000 이하, 소수 둘째 자리까지입니다");
  if (!closeAt) errors.push("마감 일시를 입력하세요");
  if (openAt && closeAt && openAt >= closeAt) errors.push("시작은 마감보다 앞이어야 합니다");
  if (late && (!lateUntil || (closeAt && lateUntil <= closeAt))) errors.push("지각 제출 마감은 마감보다 뒤여야 합니다");
  if (!get("allowText").checked && !get("allowFile").checked) errors.push("글 입력과 파일 첨부 중 하나는 받아야 합니다");
  if (description.length > 10000) errors.push("설명은 10,000자 이하입니다");
  if (errors.length) return { errors };
  return {
    payload: {
      weekNo,
      title,
      description: description.trim() ? description : null,
      openAt,
      closeAt,
      published: get("published").checked,
      latePolicy: late ? "allow_until" : "none",
      lateUntil,
      allowText: get("allowText").checked,
      allowFile: get("allowFile").checked,
      maxScore,
      scoresReleased: get("scoresReleased").checked,
    },
  };
}

/** `pf-assignment-save`: create, or update with the revision the form was loaded with. */
export async function saveAssignmentForm(node, api) {
  const sheet = node.closest("[data-assignment-form]");
  if (!sheet || node.disabled) return;
  const status = sheet.querySelector("[data-assignment-status]");
  const latest = sheet.querySelector("[data-assignment-latest]");
  const say = (text, alert) => {
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = text;
  };
  const { errors, payload } = readAssignment(sheet);
  if (errors) return say(errors.join(". "), true);
  const courseId = Number(sheet.dataset.course);
  const editId = Number(sheet.dataset.edit) || null;
  const controls = [...sheet.querySelectorAll("input, textarea, button")];
  const label = node.textContent;
  controls.forEach((control) => { control.disabled = true; });
  sheet.setAttribute("aria-busy", "true");
  node.textContent = "저장하는 중…";
  say("ASTRA 서버에 저장하고 있습니다.");
  try {
    if (editId) await updateAssignment(courseId, editId, { ...payload, revision: Number(sheet.dataset.revision) });
    else await createAssignment(courseId, payload);
    api.getState().toast = editId ? "과제를 수정했습니다" : "과제를 서버에 등록했습니다";
    api.navigate(pf("pf_report_create", { kj: sheet.dataset.kj }));
    return;
  } catch (error) {
    const message = assignmentMessage(error, { creating: !editId });
    say(message, true);
    if (editId && isConflict(error, "edit_conflict")) await showLatest(sheet, latest, status, courseId, editId);
    else if (!sheet.isConnected) {
      api.getState().toast = message;
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

/** After 409 edit_conflict: load the current assignment, adopt its revision, show it next to the typed form. */
async function showLatest(sheet, latest, status, courseId, editId) {
  const key = assignmentKey(courseId, editId);
  await assignmentDetails.load(key, courseId, editId);
  const entry = assignmentDetails.peek(key);
  if (!sheet.isConnected) return;
  if (!entry || !entry.data) {
    status.textContent = `다른 곳에서 이 과제를 먼저 수정했지만 최신 내용을 불러오지 못했습니다. ${assignmentMessage(entry && entry.error)}`;
    return;
  }
  const { assignment: info, revision } = entry.data;
  sheet.dataset.revision = String(revision);
  latest.replaceChildren(el("details", { class: "pf-server-pdf", open: true },
    el("summary", {}, el("b", { text: "서버의 최신 내용" }), el("span", { class: "pf-meta", text: ` · 수정 번호 ${revision}` })),
    note(`${info.title} · ${info.weekNo}주 · ${periodText(info)} · 배점 ${scoreText(info.maxScore)}점 · ${info.published ? "공개" : "비공개"} · 점수 ${info.scoresReleased ? "공개" : "비공개"}`),
    info.description ? prose(info.description) : null,
  ));
}

/** The course's server assignments with counts; `target` is where a row's main link goes. */
function assignmentList(course, courseId, target) {
  const key = listKey(courseId);
  const load = () => assignmentLists.load(key, courseId);
  const list = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "ASTRA 서버에 등록된 과제" },
    el("h2", { text: "서버에 등록된 과제" }),
    note("제출 수는 최종 제출한 학생 수입니다. 임시 저장은 세지 않습니다."),
    list,
  );
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      list.replaceChildren(!entry || !entry.error ? note("서버에 등록된 과제를 불러오는 중입니다.") : failure(entry.error, load));
      return;
    }
    const items = Array.isArray(data) ? data : [];
    if (!items.length) {
      list.replaceChildren(note("아직 서버에 등록된 과제가 없습니다."));
      return;
    }
    list.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: assignmentMessage(entry.error) }) : "",
      ...items.map((item) => {
        const edit = pf("pf_report_create", { kj: course.id, edit: String(item.assignmentId) });
        const submissions = pf("pf_report_status", { kj: course.id, n: serverRef(item.assignmentId) });
        const grading = pf("pf_report_grade", { kj: course.id, n: serverRef(item.assignmentId) });
        const href = target === "pf_report_grade" ? grading : target === "pf_report_status" ? submissions : edit;
        return el("div", { class: "pf-activity" },
          el("a", { class: "pf-activity-main", href, "data-go": href },
            badge(item.published ? PHASE[item.phase] || item.phase : "비공개", item.phase === "open" || item.phase === "late" ? "success" : "neutral"),
            el("span", { class: "pf-activity-copy" },
              el("b", { text: item.title }),
              el("span", { class: "pf-meta", text: `${item.weekNo}주 · ${periodText(item)} · 제출 ${item.submittedCount ?? 0} · 지각 ${item.lateCount ?? 0}` }),
            ),
          ),
          el("div", { class: "pf-activity-actions" },
            go(edit, "수정", { class: "btn btn-ghost", "aria-label": `${item.title} 수정` }),
            go(submissions, "제출 현황", { class: "btn btn-ghost", "aria-label": `${item.title} 제출 현황` }),
          ),
        );
      }),
    );
  };
  paint(assignmentLists.peek(key));
  attached(section, () => {
    assignmentLists.watch(section, key, paint, load);
    if (freshVisit(`pf-assignments-${target}`, key) === "entry" || !assignmentLists.peek(key)) load();
  });
  return section;
}

// ---------- 제출 현황 / 과제 채점 ----------

/** `pf_report_status` (and `pf_submissions?n=srv-…`) in backend mode. */
export function serverSubmissionStatus(ctx, course) {
  const blocked = gate(course, LOGIN);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const assignmentId = assignmentIdOf(ctx.params && ctx.params.get("n"));
  if (!assignmentId) return stack(backendPanel(), assignmentList(course, courseId, "pf_report_status"));
  return stack(
    backendPanel(),
    assignmentHeader(course, courseId, assignmentId, "pf_report_status"),
    submissionTable(course, courseId, assignmentId),
  );
}

/** `pf_report_grade` in backend mode: pick an assignment, then a submission. */
export function serverGrading(ctx, course) {
  const blocked = gate(course, LOGIN);
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const assignmentId = assignmentIdOf(ctx.params && ctx.params.get("n"));
  if (!assignmentId) return stack(backendPanel(), assignmentList(course, courseId, "pf_report_grade"));
  const submissionId = Number((ctx.params && ctx.params.get("sub")) || "");
  if (!Number.isInteger(submissionId) || submissionId < 1) {
    return stack(backendPanel(), assignmentHeader(course, courseId, assignmentId, "pf_report_grade"), submissionTable(course, courseId, assignmentId));
  }
  return stack(
    backendPanel(),
    assignmentHeader(course, courseId, assignmentId, "pf_report_grade"),
    submissionView(course, courseId, assignmentId, submissionId),
  );
}

/** Title, phase and counts of one assignment, and the score release switch. */
function assignmentHeader(course, courseId, assignmentId, screen) {
  const key = assignmentKey(courseId, assignmentId);
  const load = () => assignmentDetails.load(key, courseId, assignmentId);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "과제 정보" }, body);
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-release-status": "" });
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data || data.revision == null) {
      if (!entry || !entry.error) body.replaceChildren(note("과제를 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("과제를 찾을 수 없습니다", "이 과목의 과제가 아니거나 삭제되었습니다.", go(pf("pf_report_create", { kj: course.id }), "과제 목록", { class: "btn btn-secondary" })));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    const info = data.assignment;
    const released = Boolean(info.scoresReleased);
    body.replaceChildren(
      el("h2", { text: info.title }),
      note(`${info.weekNo}주 · ${periodText(info)} · ${PHASE[data.phase] || data.phase} · 배점 ${scoreText(info.maxScore)}점 · 제출 ${data.submittedCount}명 · 지각 ${data.lateCount}명`),
      el("div", { class: "pf-release", "data-release": "" },
        el("p", { class: "pf-sub" }, el("b", { text: released ? "점수 공개 중" : "점수 비공개" }), " · ", released
          ? "학생은 지금 과제 화면에서 자기 점수와 의견을 볼 수 있습니다. 비공개로 바꾸면 학생 화면에서 점수와 의견이 바로 사라지고, 준 점수는 그대로 남습니다."
          : "학생에게는 점수와 의견이 보이지 않습니다. 공개하면 제출한 학생마다 자기 점수와 의견만 과제 화면에서 바로 볼 수 있습니다. 다른 학생의 점수는 보이지 않습니다."),
        el("div", { class: "pf-actions" },
          btn(released ? "점수 비공개로 바꾸기" : "점수 공개하기", {
            "data-act": "pf-assignment-release",
            "data-course": String(courseId),
            "data-id": String(assignmentId),
            "data-next": released ? "0" : "1",
          }, released ? "secondary" : "primary"),
          go(pf("pf_report_create", { kj: course.id, edit: String(assignmentId) }), "과제 수정", { class: "btn btn-ghost" }),
        ),
        status,
      ),
      deleteControl("assignment", courseId, assignmentId, { title: info.title, back: pf(screen, { kj: course.id }) }),
    );
  };
  paint(assignmentDetails.peek(key));
  attached(section, () => {
    assignmentDetails.watch(section, key, paint, load);
    if (freshVisit("pf-assignment-head", key) === "entry" || !assignmentDetails.peek(key)) load();
  });
  return section;
}

/**
 * `pf-assignment-release`: 점수 공개 명령(`PUT …/release`, `{ released, revision }`). 응답의 새 `revision`이 상세 캐시에
 * 들어가 다음 수정이 그 번호를 쓴다. 409 `edit_conflict`면 최신 상세를 다시 받고 알린다.
 */
export async function toggleRelease(node, api) {
  if (node.disabled || node.getAttribute("aria-busy") === "true") return;
  const courseId = Number(node.dataset.course);
  const assignmentId = Number(node.dataset.id);
  const next = node.dataset.next === "1";
  const key = assignmentKey(courseId, assignmentId);
  const panel = node.closest("[data-release]");
  const status = panel && panel.querySelector("[data-release-status]");
  const say = (text) => {
    const line = document.querySelector("[data-release-status]") || status;
    if (line && line.isConnected) {
      line.setAttribute("role", "alert");
      line.textContent = text;
    } else {
      api.getState().toast = text;
      api.paintToast();
    }
  };
  const current = assignmentDetails.peek(key);
  if (!current || !current.data || current.data.revision == null) return say("과제 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.");
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  const label = node.textContent;
  node.textContent = "바꾸는 중…";
  try {
    await releaseScores(courseId, assignmentId, next, current.data.revision);
    api.getState().toast = next ? "점수를 공개했습니다. 학생 과제 화면에 자기 점수와 의견이 보입니다." : "점수를 비공개로 바꿨습니다. 학생 화면에서 점수와 의견이 사라졌습니다.";
    api.paintToast();
  } catch (error) {
    if (isConflict(error, "edit_conflict")) {
      await assignmentDetails.load(key, courseId, assignmentId);
      say("다른 곳에서 이 과제를 먼저 수정했습니다. 바꾸지 않고 최신 내용을 불러왔으니 공개 상태를 확인한 뒤 다시 누르세요.");
    } else if (missing(error)) {
      await assignmentDetails.load(key, courseId, assignmentId);
      api.getState().toast = "이미 삭제되었거나 찾을 수 없는 과제입니다.";
      api.paintToast();
    } else say(assignmentMessage(error));
  } finally {
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = label;
    }
  }
}

function submissionTable(course, courseId, assignmentId) {
  const key = assignmentKey(courseId, assignmentId);
  const load = () => submissionLists.load(key, courseId, assignmentId);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "제출 목록" }, el("h2", { text: "제출 목록" }), body);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(note("제출 목록을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("제출 목록을 볼 수 없습니다", "이 과목의 과제가 아니거나 삭제되었습니다."));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    const items = Array.isArray(data) ? data : [];
    if (!items.length) {
      body.replaceChildren(note("아직 최종 제출한 학생이 없습니다."));
      return;
    }
    body.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: assignmentMessage(entry.error) }) : "",
      table(["학생", "상태", "제출 횟수", "최종 제출", "점수", ""], items.map((item) => {
        const href = pf("pf_report_grade", { kj: course.id, n: serverRef(assignmentId), sub: String(item.submissionId) });
        return [
          item.studentName || "이름 없음",
          badge(STATUS[item.status] || item.status, item.late ? "neutral" : "success"),
          `${item.versionCount}회`,
          koDateTime(item.submittedAt),
          item.score != null ? `${scoreText(item.score)}점` : "채점 전",
          go(href, "보기·채점", { class: "btn btn-secondary", "aria-label": `${item.studentName || "학생"} 제출물 보기·채점` }),
        ];
      })),
    );
  };
  paint(submissionLists.peek(key));
  attached(section, () => {
    submissionLists.watch(section, key, paint, load);
    if (freshVisit("pf-submissions", key) === "entry" || !submissionLists.peek(key)) load();
  });
  return section;
}

function fileLine(courseId, file) {
  const { button, note: line } = downloadControl(courseId, file);
  return el("div", { class: "pf-activity" },
    el("span", { class: "pf-activity-copy" }, icon("file"), el("b", { text: file.name }), el("span", { class: "pf-meta", text: ` · ${sizeText(file.sizeBytes)}` }), line),
    button);
}

/** One student's submission: grade form, then every version (newest first) with its files. */
function submissionView(course, courseId, assignmentId, submissionId) {
  const key = submissionKey(courseId, assignmentId, submissionId);
  const headKey = assignmentKey(courseId, assignmentId);
  const load = () => submissionDetails.load(key, courseId, assignmentId, submissionId);
  const body = el("div", { class: "stack" });
  const section = el("section", { class: "sheet", "aria-label": "제출물" }, body);
  const score = el("input", { type: "number", min: "0", step: "0.01", "data-field": "score", "aria-label": "점수" });
  const feedback = el("textarea", { rows: "4", maxlength: "1000", "data-field": "feedback", "aria-label": "의견" });
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-grade-status": "" });
  const save = btn("점수 저장", { "data-act": "pf-grade-save" }, "primary");
  const visibility = el("p", { class: "pf-sub" });
  const form = el("div", { class: "pf-form", "data-grade-form": "", "data-course": String(courseId), "data-assignment": String(assignmentId), "data-submission": String(submissionId) },
    field("점수", score),
    el("div", { class: "span-2" }, field("의견 (일반 텍스트, 1,000자까지)", feedback)),
    el("div", { class: "span-2" }, visibility),
    el("div", { class: "span-2" }, status),
    el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, save, go(pf("pf_report_grade", { kj: course.id, n: serverRef(assignmentId) }), "제출 목록", { class: "btn btn-ghost" }))),
  );
  let filled = false;
  const paint = () => {
    const entry = submissionDetails.peek(key);
    const data = entry && entry.data;
    const head = assignmentDetails.peek(headKey);
    const info = head && head.data && head.data.assignment;
    if (info) {
      score.max = scoreText(info.maxScore);
      form.dataset.max = scoreText(info.maxScore);
      score.setAttribute("aria-label", `점수 (0~${scoreText(info.maxScore)})`);
      visibility.textContent = info.scoresReleased
        ? "점수를 공개한 과제입니다. 저장하면 이 학생의 과제 화면에 점수와 의견이 바로 보입니다."
        : "점수를 공개하기 전에는 학생에게 점수와 의견이 보이지 않습니다.";
    }
    save.disabled = !info || !data;
    if (!data) {
      if (!entry || !entry.error) body.replaceChildren(note("제출물을 불러오는 중입니다."));
      else if (missing(entry.error)) body.replaceChildren(emptyState("제출물을 찾을 수 없습니다", "이 과제의 제출물이 아니거나 아직 최종 제출하지 않았습니다."));
      else body.replaceChildren(failure(entry.error, load));
      return;
    }
    if (!filled) {
      filled = true;
      if (data.grade) {
        score.value = scoreText(data.grade.score);
        feedback.value = data.grade.feedback || "";
      }
    }
    if (data.grade && !status.textContent) status.textContent = `${koDateTime(data.grade.gradedAt)}에 채점했습니다.`;
    const versions = Array.isArray(data.versions) ? data.versions : [];
    body.replaceChildren(
      el("h2", { text: `${data.studentName || "학생"} · ${STATUS[data.status] || data.status}` }),
      form,
      el("h3", { text: `제출 버전 ${versions.length}개` }),
      ...versions.map((version, index) => {
        const files = Array.isArray(version.files) ? version.files : [];
        return el("details", { class: "pf-server-pdf", open: index === 0 ? true : null },
          el("summary", {},
            el("b", { text: `${version.version}번째 제출` }),
            el("span", { class: "pf-meta", text: ` · ${receiptTime(version.submittedAt)}${version.late ? " · 지각" : ""} · 파일 ${files.length}개` }),
          ),
          version.body ? prose(version.body) : note("글 없이 파일만 제출했습니다."),
          ...files.map((file) => fileLine(courseId, file)),
        );
      }),
    );
  };
  paint();
  attached(section, () => {
    submissionDetails.watch(section, key, paint, load);
    assignmentDetails.watch(section, headKey, paint, null);
    if (freshVisit("pf-submission", key) === "entry" || !submissionDetails.peek(key)) load();
  });
  return section;
}

/** `pf-grade-save`: score within the assignment's bounds, plain-text feedback. */
export async function saveGrade(node, api) {
  const form = node.closest("[data-grade-form]");
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
  const courseId = Number(form.dataset.course);
  const assignmentId = Number(form.dataset.assignment);
  const submissionId = Number(form.dataset.submission);
  const controls = [...form.querySelectorAll("input, textarea, button")];
  controls.forEach((control) => { control.disabled = true; });
  node.textContent = "저장하는 중…";
  say("점수를 저장하고 있습니다.");
  try {
    const grade = await gradeSubmission(courseId, assignmentId, submissionId, { score, feedback: feedback.trim() ? feedback : null });
    say(`${koDateTime(grade.gradedAt)}에 저장했습니다.`);
    api.getState().toast = "점수를 저장했습니다";
    api.paintToast();
  } catch (error) {
    say(assignmentMessage(error), true);
  } finally {
    if (form.isConnected) {
      controls.forEach((control) => { control.disabled = false; });
      node.textContent = "점수 저장";
    }
  }
}
