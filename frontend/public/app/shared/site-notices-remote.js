// 알림마당 공지사항(`/api/community/notices`) on the ASTRA backend. The server picks the audiences from
// the session's institution role, sorts pinned notices first and shows scheduled notices only to
// 알림마당 운영자. Viewing never marks a notice read: the detail view and the entry popup send `read`
// once per session. 운영자인지는 목록 응답의 `canManage`로만 안다. Lists and details stay in memory only.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.js";
import { subscribe } from "./backend.js";
import { createResource, retryLater } from "./remote-cache.js";
import { patchHome } from "./home-remote.js";
import { hrefFor, find } from "../manifest.js";

export const SITE_NOTICE_PAGE = 10;
export const SITE_AUDIENCES = ["all", "common", "student", "professor"];
/** 쓰기에 고를 수 있는 대상. `all`은 목록 필터에만 있다. */
export const WRITE_AUDIENCES = ["common", "student", "professor"];
export const MAX_NOTICE_FILES = 10;

const BASE = "/api/community/notices";
const one = (noticeId) => `${BASE}/${encodeURIComponent(noticeId)}`;

export const siteListKey = (audience, q, page) => `${audience}|${page}|${q}`;

export const siteNoticeLists = createResource((audience, q, page) => {
  const params = new URLSearchParams({ page: String(page), size: String(SITE_NOTICE_PAGE) });
  if (audience && audience !== "all") params.set("audience", audience);
  if (q) params.set("q", q);
  const at = session;
  return apiGet(`${BASE}?${params}`).then((data) => {
    if (at === session && data && typeof data.canManage === "boolean") manage = data.canManage;
    return { ...data, items: Array.isArray(data && data.items) ? data.items : [] };
  });
});

export const siteNoticeDetails = createResource((noticeId) => apiGet(one(noticeId)));

// 마지막으로 받은 목록의 `canManage`(알림마당 운영자인지). null은 이 세션에서 아직 목록을 읽지 않은 것이다.
let manage = null;
const readSent = new Set(); // noticeId, per session
let session = "";
// 과목 목록 같은 다른 상태 변화가 아니라 로그인한 사람이 바뀔 때만 세션 기억을 버린다.
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  manage = null;
  readSent.clear();
  entryShown = false;
});

/** 목록 응답의 `canManage`: true / false, 이 세션에서 아직 목록을 읽지 않았으면 null. */
export function siteManageState() {
  return manage;
}

/** 운영자인지 모르면 첫 쪽 목록을 한 번 읽어 `canManage`를 얻는다. 알게 된 값(실패하면 null)으로 끝난다. */
export function checkSiteManage() {
  if (manage !== null) return Promise.resolve(manage);
  return siteNoticeLists.load(siteListKey("all", "", 1), "all", "", 1).then(() => manage);
}

/** `srv-<noticeId>` → noticeId, otherwise null. 샘플 공지 ID(`n1` 등)와 겹치지 않는다. */
export function siteNoticeIdOf(ref) {
  const match = /^srv-(\d{1,15})$/.exec(String(ref || ""));
  return match ? Number(match[1]) : null;
}

export const siteNoticeRef = (noticeId) => `srv-${noticeId}`;

/** `06_pop_notice_view` for one server notice (`?pop=notice&id=srv-<noticeId>`). */
export function siteNoticeHref(noticeId) {
  return hrefFor(find("06_pop_notice_view"), { id: siteNoticeRef(noticeId) });
}

/** Applies a read state to every cached copy: list rows, the detail and the home aggregate. */
function rememberRead(noticeId, state) {
  const apply = (item) => {
    if (item && item.noticeId === noticeId) {
      item.read = true;
      if (state && Number.isFinite(state.viewCount)) item.viewCount = state.viewCount;
    }
  };
  siteNoticeLists.patch(() => true, (page) => page.items.forEach(apply));
  siteNoticeDetails.patch((key) => key === noticeId, apply);
  patchHome((home) => {
    if (Array.isArray(home.communityNotices)) home.communityNotices.forEach(apply);
    if (Array.isArray(home.noticesForMe)) home.noticesForMe = home.noticesForMe.filter((item) => item.noticeId !== noticeId);
  });
}

/**
 * Marks a published notice read (`POST …/{id}/read` → `{noticeId, read, viewCount}`). Sends at most
 * once per session per notice.
 */
export async function markSiteNoticeRead(noticeId) {
  if (readSent.has(noticeId)) return null;
  readSent.add(noticeId);
  try {
    const state = await apiSend("POST", `${one(noticeId)}/read`);
    rememberRead(noticeId, state);
    return state;
  } catch (error) {
    readSent.delete(noticeId);
    throw error;
  }
}

// 진입 공지(`00_notice_for_me`)는 세션마다 한 번만 띄운다.
let entryShown = false;

/** True once per session: the caller then opens the entry popup. */
export function claimEntryPopup() {
  if (entryShown) return false;
  entryShown = true;
  return true;
}

/** Entry popup dismissed: `read` for each shown notice; they leave `noticesForMe` in the cached home data. */
export function dismissEntryNotices(noticeIds) {
  const ids = [...new Set(noticeIds)].filter((id) => Number.isInteger(id));
  patchHome((home) => {
    if (Array.isArray(home.noticesForMe)) home.noticesForMe = home.noticesForMe.filter((item) => !ids.includes(item.noticeId));
  });
  return Promise.allSettled(ids.map((id) => markSiteNoticeRead(id)));
}

function forget() {
  siteNoticeLists.drop(() => true);
  siteNoticeDetails.drop(() => true);
}

// The request may have reached the server: a retry of the same submit keeps its key.
const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingCreate = null; // { fingerprint, key }

/**
 * `{ title, body, audience, pinned, publishAt?, uploadIds }` → 201 notice. A fresh Idempotency-Key
 * per submit; after an attempt without a definite answer the same content reuses its key, and the
 * server answers 201 with the notice it already made.
 */
export async function createSiteNotice(payload) {
  const fingerprint = JSON.stringify(payload);
  const key = pendingCreate && pendingCreate.fingerprint === fingerprint ? pendingCreate.key : newRequestKey();
  pendingCreate = { fingerprint, key };
  try {
    const notice = await apiSend("POST", BASE, payload, { idempotencyKey: key });
    pendingCreate = null;
    forget();
    return notice;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

/** Full update with the read `version` (409 `version_conflict`); `attachments` `{ keep, add }` only when changed. */
export async function updateSiteNotice(noticeId, payload) {
  const notice = await apiSend("PUT", one(noticeId), payload);
  forget();
  return notice;
}

/**
 * 예약 공지를 지금 게시한다. 읽어 둔 상세의 제목·본문·대상·고정과 `version`으로 `publishAt` 없이 전체 수정한다
 * (첨부는 보내지 않아 그대로다). 409 `version_conflict`면 다른 곳에서 먼저 고친 것이다.
 */
export function publishSiteNoticeNow(notice) {
  return updateSiteNotice(notice.noticeId, {
    title: notice.title,
    body: notice.body,
    audience: notice.audience,
    pinned: Boolean(notice.pinned),
    version: notice.version,
  });
}

/** 204. A 404 means it is gone or the session may not manage notices. */
export async function deleteSiteNotice(noticeId) {
  try {
    await apiSend("DELETE", one(noticeId));
  } finally {
    forget();
    patchHome((home) => {
      for (const name of ["communityNotices", "noticesForMe"]) {
        if (Array.isArray(home[name])) home[name] = home[name].filter((item) => item.noticeId !== noticeId);
      }
    });
  }
}

export function isVersionConflict(error) {
  return error instanceof ApiError && error.status === 409 && error.code === "version_conflict";
}

export function isAttachmentNotReady(error) {
  return error instanceof ApiError && error.status === 409 && error.code === "attachment_not_ready";
}

export function isMissing(error) {
  return error instanceof ApiError && error.status === 404;
}

/** "공통", "학생", "교수" for a notice's `audience`. */
export function siteAudienceLabel(audience) {
  return { all: "전체", common: "공통", professor: "교수", student: "학생" }[audience] || "공통";
}

/** Korean message for a failed 알림마당 공지 request. `write` selects the wording for a command. */
export function siteNoticeMessage(error, write = false) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "공지를 불러오지 못했습니다.";
  if (error.code === "idempotency_key_required") return "요청을 처리하지 못했습니다. 다시 시도하세요.";
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return write ? "공지를 관리할 권한이 없거나 이미 삭제된 공지입니다." : "공지를 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409:
      if (error.code === "version_conflict") return "다른 곳에서 이 공지를 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 글은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
      if (error.code === "attachment_not_ready") return "첨부 파일 중 쓸 수 없는 것이 있습니다. 첨부 상태를 다시 확인했으니 빼거나 다시 올린 뒤 저장하세요.";
      return error.detail || "같은 요청이 이미 처리되었습니다. 목록을 새로 불러오세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return write ? "공지를 저장하지 못했습니다. 잠시 후 다시 시도하세요." : "공지를 불러오지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
