// 강의실 그룹(ADR-0030 7). 담당자는 과목의 모든 그룹과 구성원(사용자 ID, 학번)을 보고, 학생은 자기 그룹과 구성원 이름만 본다.
// 이름 바꾸기와 삭제는 `If-Match`(읽은 version), 만들기는 `Idempotency-Key`를 쓴다. 구성원 넣기·빼기는 멱등이고, 옮기기는 서버의
// 한 transaction이다. 응답은 메모리에만 둔다.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.js";
import { subscribe } from "./backend.js";
import { createResource, retryLater } from "./remote-cache.js";

export const GROUP_TYPE = { activity: "그룹활동", section: "출석 분반" };

const base = (courseId) => `/api/courses/${encodeURIComponent(courseId)}/groups`;
const one = (courseId, groupId) => `${base(courseId)}/${encodeURIComponent(groupId)}`;
const member = (courseId, groupId, studentId) => `${one(courseId, groupId)}/members/${encodeURIComponent(studentId)}`;

export const groupsKey = (courseId) => String(courseId);

/** `GET …/groups`: `[{ groupId, name, type, description, members[{ userId?, name, studentNo? }], version }]`. */
export const groupLists = createResource((courseId) => apiGet(base(courseId)));

/** 바뀐 그룹 하나를 목록 캐시에 바로 반영한다(응답이 그 그룹의 최신 상태다). */
function adopt(courseId, group) {
  groupLists.patch((key) => key === groupsKey(courseId), (list) => {
    const index = list.findIndex((item) => item.groupId === group.groupId);
    if (index === -1) list.push(group);
    else list[index] = group;
  });
}

const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingCreate = null;
subscribe(() => { pendingCreate = null; });

/** 그룹 만들기 `{ name, type, description }`. 응답 없이 끝난 같은 내용의 재시도는 같은 key다. */
export async function createGroup(courseId, { name, type, description }) {
  const body = { name, type: type || "activity", description: description || null };
  const scope = String(courseId);
  const content = JSON.stringify(body);
  const attempt = pendingCreate && pendingCreate.scope === scope && pendingCreate.content === content
    ? pendingCreate
    : { scope, content, key: newRequestKey() };
  pendingCreate = attempt;
  try {
    const created = await apiSend("POST", base(courseId), body, { idempotencyKey: attempt.key });
    pendingCreate = null;
    adopt(courseId, created);
    return created;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

export async function renameGroup(courseId, groupId, { name, description, version }) {
  const updated = await apiSend("PUT", one(courseId, groupId), { name, description: description || null }, { ifMatch: Number(version) });
  adopt(courseId, updated);
  return updated;
}

/** 그룹 삭제. 출석에 쓴 그룹은 409 `group_in_use`다. */
export async function deleteGroup(courseId, groupId, version) {
  await apiSend("DELETE", one(courseId, groupId), undefined, { ifMatch: Number(version) });
  groupLists.patch((key) => key === groupsKey(courseId), (list) => {
    const index = list.findIndex((item) => item.groupId === groupId);
    if (index !== -1) list.splice(index, 1);
  });
}

export async function addMember(courseId, groupId, studentId) {
  const group = await apiSend("PUT", member(courseId, groupId, studentId));
  adopt(courseId, group);
  return group;
}

export async function removeMember(courseId, groupId, studentId) {
  await apiSend("DELETE", member(courseId, groupId, studentId));
  groupLists.patch((key) => key === groupsKey(courseId), (list) => {
    const group = list.find((item) => item.groupId === groupId);
    if (group) group.members = (group.members || []).filter((item) => item.userId !== studentId);
  });
}

/** 다른 그룹으로 옮긴다. 응답은 옮겨 간 그룹이고, 떠난 그룹은 목록을 다시 받아 맞춘다. */
export async function moveMember(courseId, fromGroupId, studentId, toGroupId) {
  const group = await apiSend("POST", `${member(courseId, fromGroupId, studentId)}/move`, { toGroupId: Number(toGroupId) });
  groupLists.patch((key) => key === groupsKey(courseId), (list) => {
    const from = list.find((item) => item.groupId === fromGroupId);
    if (from) from.members = (from.members || []).filter((item) => item.userId !== studentId);
  });
  adopt(courseId, group);
  return group;
}

/** 그룹 요청 실패의 한국어 문구. */
export function groupMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "그룹 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "그룹이나 수강생을 찾을 수 없습니다. 목록을 새로 불러왔습니다.";
    case 428: return "그룹 버전을 확인하지 못했습니다. 목록을 새로 불러오세요.";
    case 409:
      switch (error.code) {
        case "version_conflict": return "다른 곳에서 이 그룹을 먼저 바꿨습니다. 최신 목록을 불러왔습니다. 확인한 뒤 다시 저장하세요.";
        case "group_exists": return "같은 이름의 그룹이 이미 있습니다.";
        case "group_in_use": return "출석에 쓴 그룹은 지울 수 없습니다.";
        case "too_many_groups":
        case "too_many_members": return error.detail || "더 넣을 수 없습니다.";
        case "idempotency_conflict": return "같은 요청을 다른 내용으로 보냈습니다. 내용을 확인한 뒤 다시 보내세요.";
        default: return error.detail || "요청이 다른 변경과 겹쳤습니다. 목록을 새로 불러오세요.";
      }
    case 429: return retryLater(error);
    case 0: return error.message;
    case 502:
    case 503:
    case 504: return "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "그룹 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

export const STUDENT_PAGE = 50;

/** 넣을 수강생 찾기의 키. 검색어는 50자까지다. */
export const studentsKey = (courseId, q, page) => `${courseId}|${page}|${q || ""}`;

/**
 * 그룹에 넣을 수강생 한 쪽 `{ items[{ userId, name, studentNo, role }], total, page, size }`(이름순). 담당자용 수강생 명단
 * (`GET …/students?q&sort=name&page&size=50`)을 읽는다. 담당자가 아니면 404다.
 */
export const courseStudents = createResource(async (courseId, q, page) => {
  const query = new URLSearchParams();
  if (q) query.set("q", q);
  query.set("sort", "name");
  query.set("page", String(page));
  query.set("size", String(STUDENT_PAGE));
  const data = await apiGet(`/api/courses/${encodeURIComponent(courseId)}/students?${query}`);
  const rows = Array.isArray(data && data.items) ? data.items : [];
  return {
    items: rows.map((row) => ({ userId: row.studentId, name: row.name, studentNo: row.studentNumber, role: row.role })),
    total: Number(data && data.total) || 0,
    page: Number(data && data.page) || page,
    size: Number(data && data.size) || STUDENT_PAGE,
  };
});
