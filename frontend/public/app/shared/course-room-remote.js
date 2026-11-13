// 교수 강의실(주별·수업활동)의 서버 활동. `GET …/weeks`(`{ serverTime, weeks }`)의 주차와 활동 순서에 유형별 목록(자료, 영상
// 강의 목록, 과제 제출 수, 시험 응시 수, 설문 응답 수)을 붙인다. 목록은 원래 모듈의 리소스를 그대로 써서 같은 요청을 두 번
// 하지 않고, 다른 화면이 고친 캐시도 같이 본다. 영상 강의는 `GET …/lectures`를 100개씩 끝까지 읽은 목록(`lectureCatalog`)에서
// 활동 ID로 찾으므로 강의마다 상세를 읽지 않는다. 자료·과제·시험·설문 목록 항목도 `activityId`를 주므로 주차 목록의 활동과
// 활동 ID로 짝짓는다. 진행 단계는 서버 시계로 정한 `phase`다. 공개 전환과 삭제는 각 모듈의 명령을 그 버전 규칙(`If-Match` 또는
// `revision`)대로 부른다. 응답은 메모리에만 둔다.
import { ApiError } from "./api.js";
import { freshVisit } from "./remote-cache.js";
import {
  courseWeeks, lectureCatalog, lectureItemsOf, courseKey, updateLecture, deleteLecture, forgetLecture, lectureMessage, weeksOf,
} from "./lectures-remote.js";
import { materialLists, materialKey, materialsOf, materialChange, updateMaterial, deleteMaterial, forgetMaterialList, materialMessage } from "./materials-remote.js";
import { assignmentLists, assignmentDetails, listKey, assignmentKey, assignmentRequest, updateAssignment, deleteAssignment, forgetAssignment, assignmentMessage } from "./assignments-remote.js";
import { quizLists, quizDetails, quizListKey, quizKey, quizRequest, updateQuiz, deleteQuiz, forgetQuiz, quizMessage } from "./quizzes-remote.js";
import { surveyLists, surveyDetails, surveyPath, updateSurvey, deleteSurvey, forgetSurvey, surveyMessage } from "./surveys-remote.js";

/** 서버 활동 유형 → 강의실 유형 칩(샘플 화면의 `video`·`material`·`report`·`test`·`survey`)과 이름. */
export const ROOM_TYPES = {
  lecture_weeks: { chip: "video", label: "영상" },
  lecture_material: { chip: "material", label: "자료" },
  report: { chip: "report", label: "과제" },
  test: { chip: "test", label: "시험" },
  survey: { chip: "survey", label: "설문" },
};

/**
 * 삭제 API가 있는 유형. 모두 활동의 삭제 표시(soft delete)이고 학습·성적 기록은 서버에 남는다. 영상·자료는 `version`,
 * 과제·시험·설문은 상세의 `revision`을 `If-Match`로 보낸다(ADR-0011, ADR-0037).
 */
export const DELETABLE = new Set(["lecture_weeks", "lecture_material", "report", "test", "survey"]);

function parts(courseId) {
  return [
    { name: "weeks", resource: courseWeeks, key: courseKey(courseId) },
    { name: "lectures", resource: lectureCatalog, key: courseKey(courseId) },
    { name: "materials", resource: materialLists, key: materialKey(courseId) },
    { name: "assignments", resource: assignmentLists, key: listKey(courseId) },
    { name: "quizzes", resource: quizLists, key: quizListKey(courseId) },
    { name: "surveys", resource: surveyLists, key: String(courseId) },
  ];
}

const list = (value) => (Array.isArray(value) ? value : []);

function rowOf(type, weekNo, activity, item, detail) {
  const base = {
    type,
    weekNo,
    activityId: activity ? activity.activityId : item ? item.activityId : null,
    title: (item && item.title) || (activity && activity.title) || "",
    openAt: (item ? item.openAt : activity && activity.openAt) || null,
    closeAt: (item ? item.closeAt : activity && activity.closeAt) || null,
    published: item && typeof item.published === "boolean" ? item.published : Boolean(activity && activity.published),
    // 서버 시계로 정한 구간. 유형별 목록의 값이 더 새로우면 그것을 쓴다.
    phase: (item && item.phase) || (activity && activity.phase) || null,
    item: item || null,
    detail: detail || null,
    id: null,
  };
  if (type === "lecture_weeks" || type === "lecture_material") base.id = base.activityId;
  else if (type === "report") base.id = item ? item.assignmentId : activity ? activity.assignmentId ?? null : null;
  else if (type === "test") base.id = item ? item.quizId : activity ? activity.quizId ?? null : null;
  else if (type === "survey") base.id = item ? item.surveyId : activity ? activity.surveyId ?? null : null;
  return base;
}

/**
 * 지금 캐시로 만든 강의실. `{ loading, error, weeks: [{ weekNo, title, startsOn, endsOn, rows }], rows }`.
 * 주차 목록이 없으면 `weeks`는 null이다. 유형별 목록이 하나라도 실패하면 `error`에 그 오류를 둔다.
 */
export function roomEntry(courseId) {
  const entries = Object.fromEntries(parts(courseId).map((part) => [part.name, part.resource.peek(part.key)]));
  const loading = Object.values(entries).some((entry) => !entry || entry.loading);
  const failed = Object.entries(entries).find(([, entry]) => entry && entry.error);
  const error = failed ? { part: failed[0], error: failed[1].error } : null;
  const weeksData = entries.weeks && entries.weeks.data;
  if (!weeksData) return { loading, error, weeks: null, rows: [] };
  const byActivity = (items) => new Map(list(items).map((item) => [item.activityId, item]));
  const pools = {
    report: byActivity(entries.assignments && entries.assignments.data),
    test: byActivity(entries.quizzes && entries.quizzes.data),
    survey: byActivity(entries.surveys && entries.surveys.data),
    lecture_material: byActivity(materialsOf(entries.materials && entries.materials.data)),
  };
  const lectures = byActivity(entries.lectures && entries.lectures.data);
  const weeks = weeksOf(weeksData).map((week) => {
    const rows = list(week.activities).map((activity) => {
      const type = activity.type;
      if (type === "lecture_weeks") return rowOf(type, week.weekNo, activity, null, lectures.get(activity.activityId) || null);
      const pool = pools[type];
      if (!pool) return rowOf(type, week.weekNo, activity, null, null);
      const item = pool.get(activity.activityId) || null;
      pool.delete(activity.activityId);
      return rowOf(type, week.weekNo, activity, item, null);
    });
    return { weekNo: week.weekNo, title: week.title || null, startsOn: week.startsOn || null, endsOn: week.endsOn || null, rows };
  });
  // 주차 목록보다 늦게 받은 목록의 새 항목은 그 주차 끝에 둔다.
  for (const [type, pool] of Object.entries(pools)) {
    for (const item of pool.values()) {
      const week = weeks.find((w) => w.weekNo === item.weekNo);
      if (week) week.rows.push(rowOf(type, item.weekNo, null, item, null));
    }
  }
  return { loading, error, weeks, rows: weeks.flatMap((week) => week.rows) };
}

/** 강의실을 이루는 목록을 모두 다시 받는다. 영상 강의는 목록 쪽(`nextCursor`)을 끝까지 따라가 한 번에 받는다. */
export function reloadRoom(courseId) {
  return Promise.all(parts(courseId).map((part) => part.resource.load(part.key, courseId)));
}

/**
 * `node`가 문서에 붙은 뒤 부른다. 목록이 바뀔 때마다 `paint(roomEntry(courseId))`를 부르고, 화면에 새로 들어왔거나
 * 캐시가 없을 때만 받는다. 포커스·탭 복귀 때는 모두 다시 받는다.
 */
export function watchRoom(node, courseId, paint) {
  const fresh = freshVisit("pf-room", courseKey(courseId)) === "entry";
  const repaint = () => paint(roomEntry(courseId));
  for (const part of parts(courseId)) {
    part.resource.watch(node, part.key, repaint, part.name === "weeks" ? () => { void reloadRoom(courseId); } : null);
  }
  if (fresh) {
    void reloadRoom(courseId);
    return true;
  }
  for (const part of parts(courseId)) if (!part.resource.peek(part.key)) part.resource.load(part.key, courseId);
  return false;
}

/** 명령 뒤에 그 활동의 캐시(상세 포함)를 버린다. 실패한 명령은 각 모듈이 상세를 남겨 두므로 여기서 버린다. */
export function forgetRow(courseId, row) {
  if (row.type === "lecture_weeks") forgetLecture(courseId, row.id);
  else if (row.type === "lecture_material") forgetMaterialList(courseId);
  else if (row.type === "report" && row.id) forgetAssignment(courseId, row.id);
  else if (row.type === "test" && row.id) forgetQuiz(courseId, row.id);
  else if (row.type === "survey" && row.id) forgetSurvey(courseId, row.id);
}

// ---------- 명령 ----------

async function detailOf(resource, key, args) {
  const entry = resource.peek(key);
  if (entry && entry.data && entry.data.revision != null) return entry.data;
  await resource.load(key, ...args);
  const next = resource.peek(key);
  if (next && next.data && next.data.revision != null) return next.data;
  throw (next && next.error) || new Error("활동 정보를 불러오지 못했습니다.");
}

/**
 * 공개 여부만 바꾼 전체 수정. 영상 강의와 자료는 마지막으로 읽은 `version`을 `If-Match`로, 과제·시험·설문은 마지막으로
 * 읽은 상세의 `revision`을 본문으로 보낸다(상세가 없으면 지금 읽는다). 낡았으면 서버가 409로 거절한다.
 */
export async function setPublished(courseId, row, published) {
  if (row.type === "lecture_weeks") {
    if (!row.detail) throw new Error("강의 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.");
    // 강의 목록 항목에 설명까지 있으므로 읽은 그대로 보낸다(전체 수정에서 빠진 설명은 지워진다).
    const lecture = row.detail;
    await updateLecture(courseId, row.id, lecture.version, {
      weekNo: lecture.weekNo,
      title: lecture.title,
      description: lecture.description || null,
      openAt: lecture.openAt || null,
      closeAt: lecture.closeAt || null,
      published,
      items: { keep: lectureItemsOf(lecture).map((item) => ({ itemId: item.itemId, title: item.title, requiredSec: item.requiredSec ?? null })), add: [] },
    });
    return;
  }
  if (row.type === "lecture_material") {
    const material = row.item;
    if (!material) throw new Error("자료 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.");
    await updateMaterial(courseId, row.id, material.version, materialChange(material, {
      title: material.title,
      weekNo: material.weekNo,
      description: material.description,
      published,
      keep: list(material.files).map((file) => file.fileId),
      add: [],
    }));
    return;
  }
  if (row.id == null) throw new Error("이 활동의 서버 정보를 찾지 못했습니다. 목록을 새로 불러온 뒤 다시 누르세요.");
  if (row.type === "report") {
    const data = await detailOf(assignmentDetails, assignmentKey(courseId, row.id), [courseId, row.id]);
    await updateAssignment(courseId, row.id, assignmentRequest(data.assignment, data.revision, { published }));
    return;
  }
  if (row.type === "test") {
    const data = await detailOf(quizDetails, quizKey(courseId, row.id), [courseId, row.id]);
    await updateQuiz(courseId, row.id, quizRequest(data.quiz, data.revision, { published }));
    return;
  }
  if (row.type === "survey") {
    const path = surveyPath(courseId, row.id);
    const data = await detailOf(surveyDetails, path, [path]);
    const info = data.survey;
    // `anonymous`와 `questions`를 빼면 서버가 그대로 둔다.
    await updateSurvey(courseId, row.id, {
      weekNo: info.weekNo,
      title: info.title,
      description: info.description || null,
      openAt: info.openAt || null,
      closeAt: info.closeAt,
      published,
      resultsVisible: Boolean(info.resultsVisible),
      resultsReleaseAt: info.resultsReleaseAt || null,
      revision: Number(data.revision),
    });
    return;
  }
  throw new Error("이 유형은 강의실에서 공개 여부를 바꿀 수 없습니다.");
}

/** 활동 삭제(삭제 표시만 남는다). 마지막으로 읽은 `version` 또는 상세의 `revision`을 `If-Match`로 보낸다. */
export async function removeActivity(courseId, row) {
  if (row.type === "lecture_weeks") {
    if (!row.detail) throw new Error("강의 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.");
    await deleteLecture(courseId, row.id, row.detail.version);
    return;
  }
  if (row.type === "lecture_material") {
    if (!row.item) throw new Error("자료 정보를 아직 불러오지 못했습니다. 잠시 후 다시 누르세요.");
    await deleteMaterial(courseId, row.id, row.item.version);
    return;
  }
  if (row.id == null) throw new Error("이 활동의 서버 정보를 찾지 못했습니다. 목록을 새로 불러온 뒤 다시 누르세요.");
  if (row.type === "report") {
    const data = await detailOf(assignmentDetails, assignmentKey(courseId, row.id), [courseId, row.id]);
    await deleteAssignment(courseId, row.id, data.revision);
    return;
  }
  if (row.type === "test") {
    const data = await detailOf(quizDetails, quizKey(courseId, row.id), [courseId, row.id]);
    await deleteQuiz(courseId, row.id, data.revision);
    return;
  }
  if (row.type === "survey") {
    const path = surveyPath(courseId, row.id);
    const data = await detailOf(surveyDetails, path, [path]);
    await deleteSurvey(courseId, row.id, data.revision);
    return;
  }
  throw new Error("이 유형은 서버에 삭제 기능이 없습니다.");
}

/** 고치기 전에 읽은 판이 낡았다: 409 `version_conflict`(영상·자료)·`edit_conflict`(과제·시험·설문), 428. */
export function isStale(error) {
  return error instanceof ApiError && ((error.status === 409 && (error.code === "version_conflict" || error.code === "edit_conflict")) || error.status === 428);
}

export function isGone(error) {
  return error instanceof ApiError && error.status === 404;
}

/** 유형별 모듈의 한국어 문구. */
export function roomMessage(type, error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "요청을 처리하지 못했습니다.";
  if (type === "lecture_weeks") return lectureMessage(error);
  if (type === "lecture_material") return materialMessage(error);
  if (type === "report") return assignmentMessage(error);
  if (type === "test") return quizMessage(error);
  if (type === "survey") return surveyMessage(error);
  if (error.status === 404) return "이 과목의 강의실을 볼 수 없습니다. 담당 교수 계정인지 확인하세요.";
  return lectureMessage(error);
}
