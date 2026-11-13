// My notifications on the ASTRA backend (`/api/me/notifications`). Listing never marks a
// notification read: `read` is an explicit command sent when the user opens its link, and
// every response that carries `unreadCount` updates the header badge (unread-badges.js).
// Lists stay in memory only and are dropped when the session changes (remote-cache.js).
import { apiGet, apiSend, ApiError } from "./api.js";
import { backendStatus, kjOfCourseId } from "./backend.js";
import { createResource, freshVisit, retryLater } from "./remote-cache.js";
import { serverActivityHref } from "./activity-links.js";
import { noteUnread, refreshUnread } from "./unread-badges.js";
import { hrefFor, find } from "../manifest.js";
import { studentAssignmentHref } from "./assignments-remote.js";
import { siteNoticeHref } from "./site-notices-remote.js";
import { inquiryHref } from "./community-remote.js";

export const NOTIFY_PAGE = 10;

const BASE = "/api/me/notifications";
const one = (notificationId) => `${BASE}/${encodeURIComponent(notificationId)}`;

/** List keys: `"all"` (전체 탭) and `"unread"` (안 읽음 탭). */
export const FILTERS = ["all", "unread"];

// All loaded pages of one tab, appended by "더 보기" through `nextCursor`.
const inbox = createResource(async (filter, previous) => {
  const params = new URLSearchParams({ size: String(NOTIFY_PAGE) });
  if (filter === "unread") params.set("unread", "true");
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet(`${BASE}?${params}`);
  const items = Array.isArray(page && page.items) ? page.items : [];
  if (page && Number.isInteger(page.unreadCount)) noteUnread("notifications", page.unreadCount);
  return {
    serverTime: page ? page.serverTime : null,
    unreadCount: page && Number.isInteger(page.unreadCount) ? page.unreadCount : null,
    // Every notification this tab can show under the same visibility rule (the tab count).
    total: page && Number.isInteger(page.total) ? page.total : null,
    nextCursor: (page && page.nextCursor) || null,
    items: previous ? [...previous.items, ...items] : items,
  };
});

const live = () => backendStatus() === "ready";
const filterOf = (filter) => (filter === "unread" ? "unread" : "all");

export function inboxEntry(filter) {
  return inbox.peek(filterOf(filter));
}

/**
 * Keeps `node` painted from one tab's list. Call after `node` is attached: the first page loads
 * once per navigation (switching tabs is a navigation) and `node` repaints when a load lands.
 */
export function watchInbox(node, filter, paint) {
  const key = filterOf(filter);
  if (!live() || !node.isConnected) return;
  inbox.watch(node, key, paint, null);
  if (freshVisit(`notify-${key}`) === "entry" || !inbox.peek(key)) inbox.load(key, key, null);
}

export function reloadInbox(filter) {
  const key = filterOf(filter);
  if (live()) inbox.load(key, key, null);
}

/** Next page after the loaded ones (`nextCursor`). */
export function loadMoreInbox(filter) {
  const key = filterOf(filter);
  const entry = inbox.peek(key);
  if (!live() || !entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return inbox.load(key, key, entry.data);
}

function patchAll(change) {
  inbox.patch(() => true, (data) => change(data));
}

function remember(view) {
  patchAll((data) => {
    for (const item of data.items) if (item.notificationId === view.notificationId) item.read = Boolean(view.read);
  });
}

function forget(notificationId) {
  patchAll((data) => {
    const before = data.items.length;
    data.items = data.items.filter((item) => item.notificationId !== notificationId);
    if (data.total != null && data.items.length < before) data.total = Math.max(0, data.total - 1);
  });
}

const reading = new Map(); // notificationId -> promise: one `read` per notification at a time

/** `POST …/{id}/read`. Marks every cached copy read and sets the badge from `unreadCount`. */
export function readNotification(notificationId) {
  if (reading.has(notificationId)) return reading.get(notificationId);
  const sent = apiSend("POST", `${one(notificationId)}/read`)
    .then((result) => {
      if (result && result.notification) remember(result.notification);
      if (result && Number.isInteger(result.unreadCount)) {
        noteUnread("notifications", result.unreadCount);
        patchAll((data) => { data.unreadCount = result.unreadCount; });
      }
      return result;
    }, (error) => {
      // Withdrawn, expired or no longer a member: the row cannot be opened any more.
      if (isGone(error)) {
        forget(notificationId);
        refreshUnread();
      }
      throw error;
    })
    .finally(() => reading.delete(notificationId));
  reading.set(notificationId, sent);
  return sent;
}

/** `POST …/read-all`. Every loaded row becomes read; the 안 읽음 tab is then empty. */
export async function readAllNotifications() {
  const result = await apiSend("POST", `${BASE}/read-all`);
  const count = result && Number.isInteger(result.unreadCount) ? result.unreadCount : 0;
  noteUnread("notifications", count);
  inbox.patch((key) => key === "all", (data) => {
    for (const item of data.items) item.read = true;
    data.unreadCount = count;
  });
  inbox.patch((key) => key === "unread", (data) => {
    data.items = [];
    data.nextCursor = null;
    data.total = 0;
    data.unreadCount = count;
  });
  return result;
}

/** `DELETE …/{id}` (204). A 404 means it is already gone: the row is dropped either way. */
export async function deleteNotification(notificationId) {
  try {
    await apiSend("DELETE", one(notificationId));
  } catch (error) {
    if (!isGone(error)) throw error;
    forget(notificationId);
    refreshUnread();
    throw error;
  }
  forget(notificationId);
  refreshUnread();
}

export function isGone(error) {
  return error instanceof ApiError && error.status === 404;
}

/**
 * Where a notification opens, or null when no backend-reading screen shows its target.
 * Course notice and answered question open their course dialogs, a received message opens
 * the message view (`srv-<messageId>`), a lecture material opens its server material detail,
 * an assignment (`targetType` `ASSIGNMENT`, the assignment id) opens the assignment detail.
 * Without a course (`courseId` null) a site notice opens the 알림마당 notice and an answered
 * question is a 1:1 문의.
 */
export function notificationHref(item) {
  if (!item || item.targetId == null) return null;
  const id = String(item.targetId);
  if (item.kind === "MESSAGE_RECEIVED" && item.targetType === "MESSAGE") return hrefFor(find("02c_msg_view"), { id: `srv-${id}` });
  if (item.courseId == null) {
    if (item.kind === "SITE_NOTICE" && item.targetType === "ARTICLE") return siteNoticeHref(item.targetId);
    if (item.kind === "QUESTION_ANSWERED" && item.targetType === "ARTICLE") return inquiryHref(item.targetId);
    return null;
  }
  const kj = kjOfCourseId(item.courseId);
  if (!kj) return null;
  if (item.kind === "COURSE_NOTICE" && item.targetType === "ARTICLE") return hrefFor(find("44d_room_notice_view_pop"), { kj, id });
  if (item.kind === "QUESTION_ANSWERED" && item.targetType === "ARTICLE") return hrefFor(find("45d_room_qna_view_pop"), { kj, id });
  if (item.kind === "LECTURE_MATERIAL" && item.targetType === "ACTIVITY") return serverActivityHref(kj, "lecture_material", id);
  if ((item.kind === "ASSIGNMENT_OPENED" || item.kind === "ASSIGNMENT_GRADED") && item.targetType === "ASSIGNMENT") return studentAssignmentHref(kj, item.targetId);
  return null;
}

const LABEL = {
  COURSE_NOTICE: "공지사항",
  SITE_NOTICE: "전체공지사항",
  LECTURE_MATERIAL: "강의자료",
  ASSIGNMENT_OPENED: "과제",
  ASSIGNMENT_GRADED: "과제",
  QUESTION_ANSWERED: "질의응답",
  MESSAGE_RECEIVED: "쪽지",
};

/** "[공지사항]"-style label of a notification kind. */
export function notificationLabel(kind) {
  return LABEL[kind] || "알림";
}

/**
 * The bracket label of one notification: a notice without a course (`courseId` null) is a
 * site-wide notice, "전체공지사항"; an answered question without a course is a 1:1 문의.
 */
export function notificationTag(item) {
  if (item && item.courseId == null) {
    if (item.kind === "SITE_NOTICE" || item.kind === "COURSE_NOTICE") return LABEL.SITE_NOTICE;
    if (item.kind === "QUESTION_ANSWERED") return "1:1 문의";
  }
  return notificationLabel(item && item.kind);
}

/** Korean message for a failed notification request. */
export function notificationMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "알림 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "요청 값을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "알림을 찾을 수 없습니다. 이미 지웠거나 더 볼 수 없는 알림입니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "알림 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
