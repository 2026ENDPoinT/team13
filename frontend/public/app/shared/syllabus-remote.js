// 강의계획서(`GET /api/courses/{courseId}/syllabus`). 과목 구성원은 계획서 전체와 담당자 연락처를, 구성원이 아닌 로그인 사용자는
// 정규과목의 공개 항목만 받는다(연락처와 version 없음). 과목명·학점 같은 학사 값은 catalog 값이다. 모두 일반 텍스트다.
// 응답은 메모리에만 둔다.
import { apiGet, ApiError } from "./api.js";
import { createResource, retryLater } from "./remote-cache.js";
import { apiSend } from "./api.js";

export const syllabusKey = (courseId) => String(courseId);

export const syllabi = createResource((courseId) => apiGet(`/api/courses/${encodeURIComponent(courseId)}/syllabus`));

export const OUTCOME = { L1: "낮음", L2: "보통", L3: "높음" };

export function syllabusMessage(error) {
  if (!(error instanceof ApiError)) return "강의계획서를 불러오지 못했습니다.";
  if (error.status === 401) return "ASTRA 서버 로그인이 필요합니다.";
  if (error.status === 404) return "이 과목의 강의계획서를 볼 수 없습니다.";
  if (error.status === 429) return retryLater(error);
  if (error.status === 0) return error.message;
  return "강의계획서를 불러오지 못했습니다. 잠시 후 다시 시도하세요.";
}

// 담당 교수의 계획서 수정(`PUT …/syllabus`)과 자기 강사 정보 수정(`PUT …/syllabus/instructors/me`). 둘 다 본문 전체로 바꾸고
// (빠진 항목은 비운다) `If-Match`에 마지막으로 읽은 version을 보낸다. 저장한 적이 없으면 0이다. 성공은 200이고 계획서 수정은 바뀐
// 계획서 전체(구성원 GET과 같은 모양)를, 강사 정보 수정은 바뀐 자기 강사 정보를 돌려준다(새 version은 본문과 ETag에 있다). 받은 값은
// `syllabi` 캐시에 바로 넣으므로 다시 읽지 않는다. 한도는 SyllabusController와 같다.
export const SYLLABUS_LIMITS = {
  text: 4000, // overview, objectives, textbooks, equipment, gradingPolicy (여러 줄)
  classTimeRoom: 200,
  weeks: 16, // 행 수와 weekNo 최댓값
  weekContent: 2000, // 여러 줄
  weekNote: 500,
  department: 100,
  email: 200,
  officeHours: 200,
  phone: 50,
  office: 200,
  homepage: 300,
};

export const OUTCOME_LEVELS = ["L1", "L2", "L3"];

const syllabusPath = (courseId) => `/api/courses/${encodeURIComponent(courseId)}/syllabus`;

/** 계획서 전체 내용을 캐시에 그대로 넣는다. 같은 객체를 고치므로 지켜보는 화면이 다시 그린다. */
function replaceSyllabus(key, data) {
  syllabi.patch((k) => k === key, (current) => {
    for (const name of Object.keys(current)) delete current[name];
    Object.assign(current, data);
  });
}

/**
 * `body` = { classTimeRoom, overview, objectives, outcomeRelevance, textbooks, equipment, gradingPolicy, weeks: [{ weekNo, content, note }] }.
 * 받은 계획서 전체를 `syllabi`의 그 과목 캐시에 넣고 돌려준다.
 */
export async function updateSyllabus(courseId, version, body) {
  const saved = await apiSend("PUT", syllabusPath(courseId), body, { ifMatch: version });
  if (saved) replaceSyllabus(syllabusKey(courseId), saved);
  return saved;
}

/**
 * `body` = { department, email, officeHours, phone, office, homepage }. `version`은 자기 강사 정보의 version이다(없으면 0).
 * 받은 강사 정보로 캐시된 계획서의 `instructors`에서 같은 `userId` 항목을 바꾸고 돌려준다. 캐시에 그 항목이 없으면 그 과목 캐시를
 * 버려 다음에 다시 읽게 한다.
 */
export async function updateMyInstructorCard(courseId, version, body) {
  const saved = await apiSend("PUT", `${syllabusPath(courseId)}/instructors/me`, body, { ifMatch: version });
  const key = syllabusKey(courseId);
  if (!saved) return saved;
  let placed = false;
  syllabi.patch((k) => k === key, (current) => {
    const instructors = Array.isArray(current.instructors) ? current.instructors : [];
    const index = instructors.findIndex((person) => Number(person.userId) === Number(saved.userId));
    if (index < 0) return;
    instructors[index] = { ...saved };
    placed = true;
  });
  if (!placed) syllabi.drop((k) => k === key);
  return saved;
}

/** 강의계획서의 담당자 명단(`instructors`)에 `userId`가 있는지. 없으면 강사 정보 수정은 409 `not_listed_instructor`다. */
export function isListedInstructor(data, userId) {
  const instructors = data && Array.isArray(data.instructors) ? data.instructors : [];
  return userId != null && instructors.some((person) => Number(person.userId) === Number(userId));
}

export const NOT_LISTED_TEXT = "강의계획서의 담당자 명단에 없어 강사 정보를 쓸 수 없습니다.";

/** 409 `version_conflict` 또는 428: 다시 읽은 뒤 저장해야 한다. */
export function isStaleVersion(error) {
  return error instanceof ApiError && ((error.status === 409 && error.code === "version_conflict") || error.status === 428);
}

/** 계획서·강사 정보 저장 실패 문장. `what`은 "강의계획서" 또는 "강사 정보"다. */
export function syllabusEditMessage(error, what = "강의계획서") {
  if (!(error instanceof ApiError)) return `${what}를 저장하지 못했습니다.`;
  switch (error.status) {
    case 0: return error.message;
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 403: return error.detail || "요청이 거부되었습니다. 페이지를 새로 불러오세요.";
    case 404: return "이 과목의 강의계획서를 고칠 수 없습니다. 담당 교수만 고칠 수 있습니다.";
    case 409:
      if (error.code === "version_conflict") return `다른 곳에서 ${what}를 먼저 고쳤습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요.`;
      if (error.code === "not_listed_instructor") return NOT_LISTED_TEXT;
      return error.detail || `${what}를 저장하지 못했습니다.`;
    case 428: return `마지막으로 읽은 버전을 확인하지 못해 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요.`;
    case 429: return retryLater(error);
    default: return `${what}를 저장하지 못했습니다. 잠시 후 다시 시도하세요.`;
  }
}
