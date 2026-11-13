// My notifications on the ASTRA backend (`/api/me/notifications`). Listing never marks a
// notification read: `read` is an explicit command sent when the user opens its link, and
// every response that carries `unreadCount` updates the header badge (unread-badges.js).
// Lists stay in memory only and are dropped when the session changes (remote-cache.js).
import { useEffect } from "react";
import { apiGet, apiSend, ApiError } from "./api.ts";
import { backendStatus, kjOfCourseId } from "./backend.ts";
import { createResource, freshVisit, retryLater, useResource } from "./remote-cache.ts";
import type { Entry } from "./remote-cache.ts";
import { serverActivityHref } from "./activity-links.ts";
import { noteUnread, refreshUnread } from "./unread-badges.tsx";
import { hrefFor, find } from "../manifest.ts";
import { studentAssignmentHref } from "./assignments-remote.ts";
import { siteNoticeHref } from "./site-notices-remote.ts";
import { inquiryHref } from "./community-remote.ts";

export const NOTIFY_PAGE = 10;

type Id = string | number;

/** One notification row. `courseId` null is a site-wide or personal notification. */
export interface NotificationItem {
  notificationId: number;
  kind: string;
  targetType?: string | null;
  targetId?: Id | null;
  courseId?: number | null;
  courseName?: string | null;
  courseCode?: string | null;
  message?: string;
  createdAt?: string;
  read?: boolean;
}

/** Raw page of `GET /api/me/notifications`. */
export interface NotificationPage {
  serverTime?: string | null;
  unreadCount?: number;
  total?: number;
  nextCursor?: string | null;
  items?: NotificationItem[];
}

/** All loaded pages of one tab. */
export interface InboxData {
  serverTime: string | null;
  unreadCount: number | null;
  total: number | null;
  nextCursor: string | null;
  items: NotificationItem[];
}

/** `POST …/{id}/read` result. */
export interface NotificationReadResult {
  notification?: Pick<NotificationItem, "notificationId" | "read">;
  unreadCount?: number;
}

/** `POST …/read-all` result. */
export interface ReadAllResult {
  updated?: number;
  unreadCount?: number;
}

export type InboxFilter = "all" | "unread";

const BASE = "/api/me/notifications";
const one = (notificationId: Id): string => `${BASE}/${encodeURIComponent(notificationId)}`;

/** List keys: `"all"` (전체 탭) and `"unread"` (안 읽음 탭). */
export const FILTERS: InboxFilter[] = ["all", "unread"];

// All loaded pages of one tab, appended by "더 보기" through `nextCursor`.
const inbox = createResource<InboxData, [filter: InboxFilter, previous: InboxData | null]>(async (filter, previous) => {
  const params = new URLSearchParams({ size: String(NOTIFY_PAGE) });
  if (filter === "unread") params.set("unread", "true");
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet<NotificationPage | null>(`${BASE}?${params}`);
  const items = page && Array.isArray(page.items) ? page.items : [];
  if (page && typeof page.unreadCount === "number" && Number.isInteger(page.unreadCount)) noteUnread("notifications", page.unreadCount);
  return {
    serverTime: page ? page.serverTime ?? null : null,
    unreadCount: page && typeof page.unreadCount === "number" && Number.isInteger(page.unreadCount) ? page.unreadCount : null,
    // Every notification this tab can show under the same visibility rule (the tab count).
    total: page && typeof page.total === "number" && Number.isInteger(page.total) ? page.total : null,
    nextCursor: (page && page.nextCursor) || null,
    items: previous ? [...previous.items, ...items] : items,
  };
});

const live = (): boolean => backendStatus() === "ready";
const filterOf = (filter: string): InboxFilter => (filter === "unread" ? "unread" : "all");

export function inboxEntry(filter: string): Entry<InboxData> | null {
  return inbox.peek(filterOf(filter));
}

export function useInbox(filter: string): Entry<InboxData> | null {
  const key = filterOf(filter);
  const entry = useResource(inbox, key);
  useEffect(() => {
    if (live() && (freshVisit(`notify-${key}`) === "entry" || !inbox.peek(key))) void inbox.load(key, key, null);
  });
  return entry;
}

export function reloadInbox(filter: string): void {
  const key = filterOf(filter);
  if (live()) void inbox.load(key, key, null);
}

/** Next page after the loaded ones (`nextCursor`). */
export function loadMoreInbox(filter: string): Promise<void> {
  const key = filterOf(filter);
  const entry = inbox.peek(key);
  if (!live() || !entry || !entry.data || !entry.data.nextCursor || entry.loading) return Promise.resolve();
  return inbox.load(key, key, entry.data);
}

function patchAll(change: (data: InboxData) => void): void {
  inbox.patch(() => true, (data) => change(data));
}

function remember(view: Pick<NotificationItem, "notificationId" | "read">): void {
  patchAll((data) => {
    for (const item of data.items) if (item.notificationId === view.notificationId) item.read = Boolean(view.read);
  });
}

function forget(notificationId: Id): void {
  patchAll((data) => {
    const before = data.items.length;
    data.items = data.items.filter((item) => item.notificationId !== notificationId);
    if (data.total != null && data.items.length < before) data.total = Math.max(0, data.total - 1);
  });
}

const reading = new Map<Id, Promise<NotificationReadResult | null>>(); // notificationId -> promise: one `read` per notification at a time

/** `POST …/{id}/read`. Marks every cached copy read and sets the badge from `unreadCount`. */
export function readNotification(notificationId: Id): Promise<NotificationReadResult | null> {
  const inFlight = reading.get(notificationId);
  if (inFlight) return inFlight;
  const sent = apiSend<NotificationReadResult | null>("POST", `${one(notificationId)}/read`)
    .then((result) => {
      if (result && result.notification) remember(result.notification);
      if (result && typeof result.unreadCount === "number" && Number.isInteger(result.unreadCount)) {
        const count = result.unreadCount;
        noteUnread("notifications", count);
        patchAll((data) => { data.unreadCount = count; });
      }
      return result;
    }, (error: unknown) => {
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
export async function readAllNotifications(): Promise<ReadAllResult | null> {
  const result = await apiSend<ReadAllResult | null>("POST", `${BASE}/read-all`);
  const count = result && typeof result.unreadCount === "number" && Number.isInteger(result.unreadCount) ? result.unreadCount : 0;
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
export async function deleteNotification(notificationId: Id): Promise<void> {
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

export function isGone(error: unknown): boolean {
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
export function notificationHref(item: NotificationItem | null | undefined): string | null {
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

const LABEL: Record<string, string> = {
  COURSE_NOTICE: "공지사항",
  SITE_NOTICE: "전체공지사항",
  LECTURE_MATERIAL: "강의자료",
  ASSIGNMENT_OPENED: "과제",
  ASSIGNMENT_GRADED: "과제",
  QUESTION_ANSWERED: "질의응답",
  MESSAGE_RECEIVED: "쪽지",
};

/** "[공지사항]"-style label of a notification kind. */
export function notificationLabel(kind: string | null | undefined): string {
  return (kind && LABEL[kind]) || "알림";
}

/**
 * The bracket label of one notification: a notice without a course (`courseId` null) is a
 * site-wide notice, "전체공지사항"; an answered question without a course is a 1:1 문의.
 */
export function notificationTag(item: NotificationItem | null | undefined): string {
  if (item && item.courseId == null) {
    if (item.kind === "SITE_NOTICE" || item.kind === "COURSE_NOTICE") return LABEL.SITE_NOTICE;
    if (item.kind === "QUESTION_ANSWERED") return "1:1 문의";
  }
  return notificationLabel(item && item.kind);
}

/** Korean message for a failed notification request. */
export function notificationMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "알림 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "요청 값을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "알림을 찾을 수 없습니다. 이미 지웠거나 더 볼 수 없는 알림입니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "알림 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
