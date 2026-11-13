// 유형별 관리 화면(시험 상세·수정·응시 현황, 설문 수정·결과, 과제 수정·제출 현황)의 삭제 버튼. 강의실 주별 화면
// (room-server.js)의 삭제와 같은 규칙이다: 두 번 눌러 확인하고, 마지막으로 읽은 담당자 상세의 `revision`을 `If-Match`로
// 보낸다(ADR-0037). 409 `edit_conflict`·428이면 지우지 않고 상세를 다시 받아 알리고, 404면 이미 지워진 것으로 보고
// 204와 같이 그 유형의 목록으로 돌아간다. 확인 상태와 안내는 세션이 바뀌면 버리는 메모리 맵에 두어 다시 그려도 남는다.
import { el } from "../../dom.js";
import { btn } from "../../ui/components.js";
import { subscribe } from "../../shared/backend.js";
import { isStale, isGone } from "../../shared/course-room-remote.js";
import { quizDetails, quizKey, deleteQuiz, quizMessage } from "../../shared/quizzes-remote.js";
import { surveyDetails, surveyPath, deleteSurvey, surveyMessage } from "../../shared/surveys-remote.js";
import { assignmentDetails, assignmentKey, deleteAssignment, assignmentMessage } from "../../shared/assignments-remote.js";

const TYPES = {
  quiz: {
    name: "시험",
    object: "시험을",
    what: "이 시험과 응시가 학생과 교수 모두에게서 바로 사라지고, 성적부의 항목도 빠집니다. 응시와 점수 기록은 서버에 남습니다.",
    peek: (courseId, id) => quizDetails.peek(quizKey(courseId, id)),
    load: (courseId, id) => quizDetails.load(quizKey(courseId, id), courseId, id),
    remove: deleteQuiz,
    message: quizMessage,
  },
  survey: {
    name: "설문",
    object: "설문을",
    what: "이 설문과 결과가 학생과 교수 모두에게서 바로 사라집니다. 응답 기록은 서버에 남습니다.",
    peek: (courseId, id) => surveyDetails.peek(surveyPath(courseId, id)),
    load: (courseId, id) => surveyDetails.load(surveyPath(courseId, id), surveyPath(courseId, id)),
    remove: deleteSurvey,
    message: surveyMessage,
  },
  assignment: {
    name: "과제",
    object: "과제를",
    what: "이 과제와 제출물이 학생과 교수 모두에게서 바로 사라지고, 성적부의 항목도 빠집니다. 제출 파일과 점수 기록은 서버에 남습니다.",
    peek: (courseId, id) => assignmentDetails.peek(assignmentKey(courseId, id)),
    load: (courseId, id) => assignmentDetails.load(assignmentKey(courseId, id), courseId, id),
    remove: deleteAssignment,
    message: assignmentMessage,
  },
};

// "<type>:<courseId>:<id>" -> 한 번 누른 삭제, 진행 중인 삭제, 안내 { text, alert }
const armed = new Set();
const busy = new Set();
const notes = new Map();
subscribe(() => {
  armed.clear();
  busy.clear();
  notes.clear();
});

const keyOf = (type, courseId, id) => `${type}:${courseId}:${id}`;

/**
 * 삭제 버튼과 안내 줄. `type`은 quiz·survey·assignment, `title`은 상세의 제목, `back`은 지운 뒤 돌아갈 그 유형의 목록
 * 주소다. 상세를 그릴 때마다 불러도 확인 상태와 안내가 남는다.
 */
export function deleteControl(type, courseId, id, { title, back }) {
  const key = keyOf(type, courseId, id);
  const working = busy.has(key);
  const ready = armed.has(key);
  const button = btn(working ? "삭제하는 중…" : ready ? "한 번 더 누르면 삭제" : `${TYPES[type].name} 삭제`, {
    "data-act": "pf-activity-delete",
    "data-type": type,
    "data-course": String(courseId),
    "data-id": String(id),
    "data-title": title || "",
    "data-back": back,
    "aria-label": `${title || TYPES[type].name} 삭제`,
    "aria-busy": working ? "true" : "false",
  }, ready ? "danger" : "ghost");
  button.disabled = working;
  const said = notes.get(key);
  const line = el("p", {
    class: said && said.alert ? "err" : "pf-sub",
    role: said && said.alert ? "alert" : "status",
    "aria-live": "polite",
    "data-delete-status": key,
    text: said ? said.text : "",
  });
  return el("div", { class: "pf-delete", "data-delete": key }, button, line);
}

function show(key, text, alert) {
  if (text) notes.set(key, { text, alert });
  else notes.delete(key);
  for (const line of document.querySelectorAll("[data-delete-status]")) {
    if (line.dataset.deleteStatus !== key) continue;
    line.className = alert ? "err" : "pf-sub";
    line.setAttribute("role", alert ? "alert" : "status");
    line.textContent = text || "";
  }
}

/** 상세의 `revision`. 캐시에 없으면 지금 읽는다. */
async function revisionOf(spec, courseId, id) {
  const entry = spec.peek(courseId, id);
  if (entry && entry.data && entry.data.revision != null) return entry.data.revision;
  await spec.load(courseId, id);
  const next = spec.peek(courseId, id);
  if (next && next.data && next.data.revision != null) return next.data.revision;
  throw (next && next.error) || new Error(`${spec.name} 정보를 불러오지 못했습니다.`);
}

/** `pf-activity-delete`: 첫 번째 누름은 확인을 묻고, 두 번째 누름이 `DELETE`를 보낸다. */
export async function activityDelete(node, api) {
  const type = node.dataset.type;
  const spec = TYPES[type];
  const courseId = Number(node.dataset.course);
  const id = Number(node.dataset.id);
  if (!spec || !courseId || !id) return;
  const key = keyOf(type, courseId, id);
  if (busy.has(key) || node.disabled) return;
  const title = node.dataset.title || spec.name;
  const back = node.dataset.back;
  if (!armed.has(key)) {
    armed.add(key);
    node.textContent = "한 번 더 누르면 삭제";
    node.classList.replace("btn-ghost", "btn-danger");
    show(key, `한 번 더 누르면 삭제합니다. ${spec.what} 되돌릴 수 없습니다.`, true);
    return;
  }
  busy.add(key);
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  node.textContent = "삭제하는 중…";
  const toastAndBack = (text) => {
    api.getState().toast = text;
    if (back) api.navigate(back);
    else api.paintToast();
  };
  try {
    const revision = await revisionOf(spec, courseId, id);
    await spec.remove(courseId, id, revision);
    armed.delete(key);
    show(key, "");
    toastAndBack(`「${title}」을 삭제했습니다. 학생 화면에서도 사라졌습니다.`);
  } catch (error) {
    armed.delete(key);
    // 상세를 다시 받으면 화면이 버튼을 새로 그린다. 그 전에 진행 중 표시를 풀어 새 버튼이 눌릴 수 있게 한다.
    busy.delete(key);
    if (isStale(error)) {
      show(key, `다른 곳에서 이 ${spec.object} 먼저 고쳤습니다. 지우지 않고 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.`, true);
      await spec.load(courseId, id);
    } else if (isGone(error)) {
      show(key, "");
      toastAndBack(`이미 삭제된 ${spec.name}입니다. 목록으로 돌아갑니다.`);
    } else show(key, spec.message(error), true);
  } finally {
    busy.delete(key);
    if (node.isConnected) {
      node.disabled = false;
      node.setAttribute("aria-busy", "false");
      node.textContent = `${spec.name} 삭제`;
      node.classList.replace("btn-danger", "btn-ghost");
    }
  }
}
