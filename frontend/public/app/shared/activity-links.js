// 서버 학습활동 ID(`learning_activities.activity_id`)로 학생 화면 주소를 만든다. 영상·자료는 활동 ID를 그대로 쓰는
// 상세로, 과제·시험·설문은 행이 함께 주는 `assignmentId`·`quizId`·`surveyId`(주차 목록, 할 일, 캘린더, 활동 상세)로
// 바로 간다. 그 ID가 없는 주소(예전 링크)는 연결 화면(`activity_form.acl?m=<type>&act=<activityId>`)이 활동 상세로
// 한 번 읽어 바꾼다. 다른 모듈을 가져오지 않아 어디서든 순환 없이 쓸 수 있다.
const ACTIVITY_PATH = "/ilos/cls/st/activity/activity_form.acl";
const ID = /^[1-9]\d{0,15}$/;

/** 연결 화면이 받는 서버 활동 유형. */
export const RESOLVED_TYPES = new Set(["report", "test", "survey"]);

/** 평가 활동 유형 → 행이 주는 그 화면의 ID 필드. */
const TARGET = { report: "assignmentId", test: "quizId", survey: "surveyId" };

/** 서버 자료 활동의 학생 상세(`m=lecture_material&n=srv-<activityId>`). */
export function materialHref(kj, activityId) {
  return `${ACTIVITY_PATH}?${new URLSearchParams({ m: "lecture_material", n: `srv-${activityId}`, kj: String(kj) })}`;
}

/** 과제·시험·설문 행(`{ assignmentId | quizId | surveyId }`)이 주는 그 화면의 ID, 없으면 null. */
export function targetIdOf(type, row) {
  const field = TARGET[type];
  const id = field && row ? row[field] : null;
  return id != null && ID.test(String(id)) ? Number(id) : null;
}

/**
 * 서버 활동 `type`(`learning_activities.activity_type`)과 활동 ID의 학생 화면 주소. `row`가 과제·시험·설문 ID를 주면 그
 * 화면(`n=srv-<id>`)으로 바로, 없으면 연결 화면으로 간다. 볼 화면이 없는 유형이나 과목이 없으면 null이다.
 */
export function serverActivityHref(kj, type, activityId, row = null) {
  if (!kj || !ID.test(String(activityId))) return null;
  if (type === "lecture_weeks") return `${ACTIVITY_PATH}?${new URLSearchParams({ m: "lecture_weeks", n: `srv-${activityId}`, kj: String(kj) })}`;
  if (type === "lecture_material") return materialHref(kj, activityId);
  if (!RESOLVED_TYPES.has(type)) return null;
  const target = targetIdOf(type, row);
  if (target != null) return `${ACTIVITY_PATH}?${new URLSearchParams({ m: type, n: `srv-${target}`, kj: String(kj) })}`;
  return `${ACTIVITY_PATH}?${new URLSearchParams({ m: type, act: String(activityId), kj: String(kj) })}`;
}

/** 연결 화면 주소의 `{ type, activityId }`, 아니면 null. */
export function activityLinkOf(params) {
  const type = params.get("m");
  const raw = params.get("act") || "";
  if (!RESOLVED_TYPES.has(type) || !ID.test(raw)) return null;
  return { type, activityId: Number(raw) };
}
