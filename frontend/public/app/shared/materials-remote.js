// Lecture materials on the ASTRA backend: the list (`GET …/materials` → `{ serverTime, items }`, each view with its
// `version`, server-clock `phase` and per-file `summaryPublished`), edit (`PUT`) and delete (`DELETE`), and the course file
// collection (`GET …/files?q=&cursor=&size=`). Both writes send `If-Match: "<version>"` from the last read; a newer version
// on the server answers 409 `version_conflict`, a missing header 428. A changed or deleted material drops its study-room
// records, so the next study-room render no longer lists a removed PDF.
import { apiGet, apiSend, ApiError } from "./api.js";
import { createResource, retryLater } from "./remote-cache.js";
import { forgetRemoteMaterial } from "./course-pdfs-remote.js";

const base = (courseId) => `/api/courses/${encodeURIComponent(courseId)}/materials`;
const one = (courseId, activityId) => `${base(courseId)}/${encodeURIComponent(activityId)}`;

export const materialKey = (courseId) => String(courseId);

export const materialLists = createResource((courseId) => apiGet(base(courseId)));

/** The material array of a `materialLists` response (`{ serverTime, items }`). */
export function materialsOf(data) {
  return data && Array.isArray(data.items) ? data.items : [];
}

// ---------- 파일모음 ----------

export const FILES_PAGE = 50;

export const filesKey = (courseId, q) => `${courseId}|${q}`;

/**
 * 강의실 파일모음(`GET …/files?q=&cursor=&size=`)의 받아 둔 쪽 전부. `{ serverTime, total, nextCursor, items }`이고 `items`는
 * `{ weekNo, weekTitle, startsOn, endsOn, activityId, activityType, activityTitle, file: { fileId, name, sizeBytes, extension, pdf,
 * summaryPublished } }`를 주차·활동·파일 순으로 이어 붙인다. `previous`를 주면 그 다음 쪽을 받아 붙인다. `q`는 파일 이름과 활동 제목을 찾는다.
 */
export const courseFiles = createResource(async (courseId, q, previous) => {
  const params = new URLSearchParams({ size: String(FILES_PAGE) });
  if (q) params.set("q", q);
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet(`/api/courses/${encodeURIComponent(courseId)}/files?${params}`);
  const items = Array.isArray(page && page.items) ? page.items : [];
  return {
    serverTime: page.serverTime,
    total: Number(page.total) || 0,
    nextCursor: page.nextCursor || null,
    items: previous ? [...previous.items, ...items] : items,
  };
});

/** 받아 둔 파일모음의 다음 쪽. 받는 중이거나 다음 쪽이 없으면 아무것도 하지 않는다. */
export function loadMoreFiles(courseId, q) {
  const key = filesKey(courseId, q);
  const entry = courseFiles.peek(key);
  if (!entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return courseFiles.load(key, courseId, q, entry.data);
}

function forget(courseId, activityId) {
  forgetRemoteMaterial(activityId);
  forgetMaterialList(courseId);
}

/** Drops the cached list of a course (after a material was registered elsewhere). */
export function forgetMaterialList(courseId) {
  materialLists.drop((key) => key === materialKey(courseId));
  courseFiles.drop((key) => key.startsWith(`${courseId}|`));
}

/**
 * Full update: the request is the state after the edit. `openAt` is sent back as last read
 * (an omitted value would clear it); `keep` are file ids of this material, `add` clean upload ids.
 */
export function materialChange(material, { title, weekNo, description, published, keep, add }) {
  return {
    weekNo,
    title,
    description: typeof description === "string" && description.trim() ? description : null,
    openAt: material.openAt || null,
    published: Boolean(published),
    attachments: { keep: [...keep], add: [...add] },
  };
}

export async function updateMaterial(courseId, activityId, version, change) {
  try {
    return await apiSend("PUT", one(courseId, activityId), change, { ifMatch: version });
  } finally {
    forget(courseId, activityId);
  }
}

export async function deleteMaterial(courseId, activityId, version) {
  try {
    await apiSend("DELETE", one(courseId, activityId), undefined, { ifMatch: version });
  } finally {
    forget(courseId, activityId);
  }
}

/** 409 `version_conflict` or 428: the screen must read the material again before writing. */
export function isStaleVersion(error) {
  return error instanceof ApiError && ((error.status === 409 && error.code === "version_conflict") || error.status === 428);
}

/** Korean message for a failed material edit or delete. */
export function materialMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "자료 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "자료를 찾을 수 없습니다. 이미 삭제되었거나 이 과목의 자료가 아닙니다.";
    case 409: return error.code === "version_conflict"
      ? "다른 곳에서 이 자료를 먼저 고쳤습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요."
      : error.code === "attachment_not_ready"
        ? "첨부할 수 없는 파일이 있습니다. 검사가 끝난 파일인지, 지금 이 자료에 붙어 있는 파일인지 확인하세요."
        : error.detail || "같은 요청이 이미 처리되었습니다. 목록을 새로 불러오세요.";
    case 428: return "자료 버전을 확인하지 못해 저장하지 않았습니다. 최신 내용을 불러왔으니 다시 시도하세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "자료 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
