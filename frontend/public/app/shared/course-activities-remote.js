// 강의실 한 과목의 서버 학습활동을 한데 모은다. 학생 강의실 주차(40), 학습활동 목록(50–56), 할 일·알림의 활동 연결
// 화면이 쓴다. 주차 목록(`GET …/weeks` → `{ serverTime, weeks }`)은 주차와 그 안의 순서, 영상 활동, 활동마다 서버 시계로 정한
// `phase`와 과제·시험·설문 ID를 주고, 유형별 목록은 자기 상태와 `activityId`를 준다. 자료 `…/materials`(첨부 파일), 과제
// `…/assignments`(본인 제출 상태와 phase), 시험 `…/quizzes`(본인 응시 상태와 phase), 설문 `…/surveys`(본인 응답 상태와 phase),
// 영상 진도 `…/lecture-progress`. 같은 엔드포인트는 첫 웨이브 모듈의 리소스를 그대로 써서 두 번 받지 않는다.
// 진행 중·미제출은 서버의 phase와 본인 상태로만 정한다. 브라우저 시계로 정하지 않는다.
import { ApiError } from "./api.js";
import { backendStatus, courseIdFor } from "./backend.js";
import { freshVisit } from "./remote-cache.js";
import { serverActivityHref, materialHref, targetIdOf } from "./activity-links.js";
import { courseWeeks, lectureDetails, lectureProgress, courseKey, detailKey, lectureRows, weeksOf } from "./lectures-remote.js";
import { materialLists, materialKey, materialsOf } from "./materials-remote.js";
import { assignmentLists, listKey as assignmentListKey, studentAssignmentHref, accepting } from "./assignments-remote.js";
import { quizLists, quizListKey, studentQuizHref } from "./quizzes-remote.js";
import { surveyLists, studentSurveyHref } from "./surveys-remote.js";

/**
 * 이 과목의 활동 화면을 서버가 맡는지: `anon`이거나, `ready`이고 서버에 그 과목(`kj`)이 있을 때.
 * 그때는 샘플 활동을 서버 활동처럼 보이지 않는다.
 */
export function serverOwnsCourse(kj) {
  const status = backendStatus();
  return status === "anon" || (status === "ready" && Boolean(courseIdFor(kj)));
}

/** 서버 활동 유형(`activity_type`) → 화면 유형(색, 필터 칩). */
export const VIEW_TYPE = { lecture_weeks: "video", lecture_material: "material", report: "report", test: "test", survey: "survey" };

/** 부분 목록 이름과 그 실패 문구에 쓰는 한국어 이름. */
export const PART_LABEL = {
  weeks: "주차와 영상", materials: "자료", assignments: "과제", quizzes: "시험", surveys: "설문", progress: "영상 진도",
};

function part(name, courseId) {
  switch (name) {
    case "weeks": return { name, resource: courseWeeks, key: courseKey(courseId), args: [courseId] };
    case "materials": return { name, resource: materialLists, key: materialKey(courseId), args: [courseId] };
    case "assignments": return { name, resource: assignmentLists, key: assignmentListKey(courseId), args: [courseId] };
    case "quizzes": return { name, resource: quizLists, key: quizListKey(courseId), args: [courseId] };
    case "surveys": return { name, resource: surveyLists, key: String(courseId), args: [courseId] };
    default: return { name, resource: lectureProgress, key: courseKey(courseId), args: [courseId] };
  }
}

/** 이 과목 화면이 읽는 부분 목록. 담당자에게는 본인 진도가 없다. `only`로 일부만 고른다. */
export function activityParts(courseId, { instructor = false, only = null } = {}) {
  const names = only || ["weeks", "materials", "assignments", "quizzes", "surveys", ...(instructor ? [] : ["progress"])];
  return names.map((name) => part(name, courseId));
}

export function loadPart(entry) {
  return entry.resource.load(entry.key, ...entry.args);
}

/**
 * 붙어 있는 `node`를 부분 목록마다 다시 그리게 하고, 새 화면 이동이면 전부, 아니면 없는 것만 받는다.
 * `node`가 문서에 붙은 뒤 부른다.
 */
export function watchActivities(node, parts, scope, paint) {
  if (!node.isConnected) return;
  const fresh = freshVisit(scope, parts.map((entry) => entry.key).join("|")) === "entry";
  for (const entry of parts) {
    entry.resource.watch(node, entry.key, paint, () => loadPart(entry));
    if (fresh || !entry.resource.peek(entry.key)) loadPart(entry);
  }
}

/** 실패한 부분 목록을 다시 받는다. */
export function reloadFailed(parts) {
  for (const entry of parts) {
    const got = entry.resource.peek(entry.key);
    if (!got || got.error) loadPart(entry);
  }
}

const missingError = (error) => error instanceof ApiError && error.status === 404;

function progressItems(progress, activityId) {
  const items = [];
  for (const week of Array.isArray(progress) ? progress : []) {
    for (const item of Array.isArray(week.items) ? week.items : []) if (item.activityId === activityId) items.push(item);
  }
  return items;
}

const DONE = { report: ["submitted", "late"], test: ["submitted", "graded"], survey: ["completed"] };

/**
 * 받은 부분 목록으로 만든 활동 행과 상태. 행은 `{ type, kind, key, weekNo, title, openAt, closeAt, phase, href,
 * ongoing, missing, done, item, progress, files }`이고 주차 목록의 순서(활동 ID로 짝짓는다)로 정렬한다.
 * `pending`은 아직 응답이 없는 부분, `failures`는 실패한 부분 `[{ name, error }]`, `denied`는 주차 목록이 404다.
 */
export function activitySnapshot(parts, kj, { instructor = false } = {}) {
  const data = {};
  const failures = [];
  let pending = false;
  for (const entry of parts) {
    const got = entry.resource.peek(entry.key);
    if (got && got.data) data[entry.name] = got.data;
    if (got && got.error) failures.push({ name: entry.name, error: got.error, stale: Boolean(got.data) });
    if (!got || (!got.data && !got.error)) pending = true;
  }
  const denied = failures.some((failure) => failure.name === "weeks" && !failure.stale && missingError(failure.error));
  const rows = [];
  const weeks = weeksOf(data.weeks);

  for (const material of materialsOf(data.materials)) {
    rows.push({
      type: "material", kind: "lecture_material", key: `material-${material.activityId}`, weekNo: material.weekNo, title: material.title,
      openAt: material.openAt, closeAt: null, phase: material.phase || null, href: materialHref(kj, material.activityId), activityId: material.activityId,
      ongoing: false, missing: false, done: false, item: material, files: Array.isArray(material.files) ? material.files : [],
    });
  }
  for (const activity of lectureRows(weeks)) {
    const items = progressItems(data.progress, activity.activityId);
    const ongoing = activity.phase === "open";
    const allMet = items.length > 0 && items.every((item) => item.progress && item.progress.status === "met");
    rows.push({
      type: "video", kind: "lecture_weeks", key: `lecture-${activity.activityId}`, weekNo: activity.weekNo, title: activity.title,
      openAt: activity.openAt, closeAt: activity.closeAt, phase: activity.phase || null, href: serverActivityHref(kj, "lecture_weeks", activity.activityId),
      activityId: activity.activityId, ongoing, missing: !instructor && ongoing && items.length > 0 && !allMet,
      done: !instructor && allMet, item: activity, progress: data.progress ? items : null,
    });
  }
  for (const item of Array.isArray(data.assignments) ? data.assignments : []) {
    rows.push({
      type: "report", kind: "report", key: `report-${item.assignmentId}`, weekNo: item.weekNo, title: item.title, activityId: item.activityId,
      openAt: item.openAt, closeAt: item.closeAt, phase: item.phase, href: studentAssignmentHref(kj, item.assignmentId),
      ongoing: accepting(item.phase), missing: item.status === "not_submitted" && accepting(item.phase),
      done: DONE.report.includes(item.status), item,
    });
  }
  for (const item of Array.isArray(data.quizzes) ? data.quizzes : []) {
    rows.push({
      type: "test", kind: "test", key: `test-${item.quizId}`, weekNo: item.weekNo, title: item.title, activityId: item.activityId,
      openAt: item.openAt, closeAt: item.closeAt, phase: item.phase, href: studentQuizHref(kj, item.quizId),
      ongoing: item.phase === "open",
      missing: item.phase === "open" && item.examType !== "offline" && (item.status === "not_started" || item.status === "in_progress"),
      done: DONE.test.includes(item.status), item,
    });
  }
  for (const item of Array.isArray(data.surveys) ? data.surveys : []) {
    rows.push({
      type: "survey", kind: "survey", key: `survey-${item.surveyId}`, weekNo: item.weekNo, title: item.title, activityId: item.activityId,
      openAt: item.openAt, closeAt: item.closeAt, phase: item.phase, href: studentSurveyHref(kj, item.surveyId),
      ongoing: item.phase === "open", missing: item.phase === "open" && item.state === "not_started",
      done: DONE.survey.includes(item.state), item,
    });
  }
  // 이 화면에 상세가 없는 유형(Zoom, 토론 등)은 주차 목록에서 제목만 보인다.
  for (const week of weeks) {
    for (const activity of Array.isArray(week.activities) ? week.activities : []) {
      if (VIEW_TYPE[activity.type]) continue;
      rows.push({
        type: "other", kind: activity.type, key: `other-${activity.activityId}`, weekNo: week.weekNo, title: activity.title,
        openAt: activity.openAt, closeAt: activity.closeAt, phase: activity.phase || null, href: null, activityId: activity.activityId,
        ongoing: false, missing: false, done: false, item: activity,
      });
    }
  }

  // 주차 목록의 순서를 따른다. 모든 행이 활동 ID를 주므로 그것으로 짝짓는다. 주차 목록보다 늦게 받은 목록의 새 항목은 그 주차 끝이다.
  const order = new Map();
  for (const week of weeks) {
    for (const activity of Array.isArray(week.activities) ? week.activities : []) order.set(activity.activityId, order.size);
  }
  rows.forEach((row, index) => {
    row.order = order.has(row.activityId) ? order.get(row.activityId) : order.size + index;
  });
  rows.sort((a, b) => (Number(a.weekNo) || 0) - (Number(b.weekNo) || 0) || a.order - b.order);
  return { rows, weeks, pending, failures, denied };
}

/** 학습활동 목록 필터. 진행 중·미제출은 서버 상태만 본다. */
export function passServerFilter(row, filter) {
  if (!filter || filter === "all") return true;
  if (filter === "ongoing") return row.ongoing === true;
  if (filter === "missing") return row.missing === true;
  return row.type === filter;
}

// ---------- 활동 ID 연결 ----------

/** 받아 둔 주차 목록에서 그 활동 행을 찾는다. 없으면 null(목록을 새로 읽지 않는다). */
function cachedWeekActivity(courseId, activityId) {
  const hit = courseWeeks.peek(courseKey(courseId));
  for (const week of weeksOf(hit && hit.data)) {
    for (const activity of Array.isArray(week.activities) ? week.activities : []) {
      if (activity.activityId === activityId) return activity;
    }
  }
  return null;
}

/**
 * 서버 활동 ID를 그 학생 화면 주소로 바꾼다(예전 연결 주소 `act=`). 받아 둔 주차 목록에 그 활동의 과제·시험·설문 ID가
 * 있으면 그것을, 없으면 활동 상세(`GET …/activities/{id}`, 유형과 그 ID가 있다)를 한 번 읽는다. 볼 수 없으면(404) 또는
 * 연결할 화면이 없으면 null. 네트워크 실패는 그대로 던진다.
 */
export async function resolveActivityHref(kj, type, activityId) {
  const courseId = courseIdFor(kj);
  if (!courseId) return null;
  let row = cachedWeekActivity(courseId, activityId);
  if (!row || (row.type === type && targetIdOf(type, row) == null)) {
    const key = detailKey(courseId, activityId);
    await lectureDetails.load(key, courseId, activityId);
    const got = lectureDetails.peek(key);
    if (!got || !got.data) {
      if (got && got.error && !missingError(got.error)) throw got.error;
      return null;
    }
    row = got.data;
  }
  const kind = row.type || type;
  if (kind === "lecture_weeks" || kind === "lecture_material") return serverActivityHref(kj, kind, activityId);
  return targetIdOf(kind, row) != null ? serverActivityHref(kj, kind, activityId, row) : null;
}
