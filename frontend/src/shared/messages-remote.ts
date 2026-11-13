// 쪽지 on the ASTRA backend (`/api/messages/**`, ADR-0025). Opening a received message never
// marks it read: only `markRead` (the explicit 읽음 button) sends `POST inbox/{id}/read`.
// Send, reply and forward carry an Idempotency-Key; a retry of the same content (attachments
// included) after a lost response reuses its key so the server returns the receipt it may already
// have made. Attachments are uploads of purpose `message_attachment` made without a courseId;
// details list them as `attachments` read through `GET /api/files/{fileId}/content`.
// Lists, details and drafts stay in memory only.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";
import { noteUnread, refreshUnread } from "./unread-badges.tsx";
import { hrefFor, screens } from "../manifest.ts";
import type { Screen } from "../types.ts";

type Id = number | string;
/** Mailbox name: `"sent"`, anything else is the inbox. */
type BoxName = string;

export interface MessagePerson {
  userId: number;
  name: string;
  role?: string;
}

export interface MessageCourse {
  courseId: number;
  name?: string | null;
  key?: string | null;
}

/** A row of an inbox or sent list. */
export interface MessageItem {
  messageId: number;
  title: string;
  sentAt: string;
  courseId?: number | null;
  read?: boolean;
  sender?: MessagePerson | null;
  firstRecipient?: MessagePerson | null;
  recipientCount?: number;
  readCount?: number;
  recalledCount?: number;
}

/** One page (or all loaded pages) of a list. */
export interface MessageList {
  serverTime?: string;
  total?: number;
  size?: number;
  nextCursor?: string | null;
  unreadCount?: number;
  items?: MessageItem[];
}

export interface MessageAttachment {
  fileId: number;
  name: string;
  sizeBytes?: number;
}

export interface MessageRecipient extends MessagePerson {
  readAt?: string | null;
  recalled?: boolean;
}

/** Detail of a received or sent message. */
export interface MessageDetail {
  messageId: number;
  title: string;
  body: string;
  sentAt: string;
  read?: boolean;
  readAt?: string | null;
  originKind?: string | null;
  course?: MessageCourse | null;
  sender?: MessagePerson | null;
  recipients?: MessageRecipient[];
  attachments?: MessageAttachment[];
}

/** `GET recipients`. */
export interface RecipientPage {
  items: MessagePerson[];
  total: number;
  page: number;
  size: number;
}

export interface ReadState {
  messageId: number;
  read: boolean;
  wasUnread: boolean;
  unreadCount: number;
}

/** 201 of send, reply and forward. */
export interface SendReceipt {
  messageId: number;
  sentAt: string;
  recipients: MessagePerson[];
}

export interface RecallResult {
  messageId: number;
  recalledNow: number;
  recipients: (MessagePerson & { outcome: "recalled" | "read" })[];
}

export interface DeleteResult {
  deleted: number;
}

export interface MessageDraft {
  revision: number;
  title: string;
  body: string;
  course: MessageCourse | null;
  recipients: MessagePerson[];
  droppedRecipients: number;
  courseDropped: boolean;
  updatedAt: string;
}

/** `PUT draft` answer. */
export interface SavedDraft {
  revision: number;
  updatedAt: string;
}

export interface MessageInput {
  title: string;
  body: string;
  uploadIds?: readonly (number | string)[] | null;
}

export interface AddressedInput extends MessageInput {
  courseId?: number | null;
  recipientIds: readonly number[];
}

export interface DraftInput {
  title: string;
  body: string;
  courseId?: number | null;
  recipientIds?: readonly number[] | null;
}

export interface DeleteAllInput {
  read?: string | null;
  q?: string | null;
  until: string | undefined;
  exceptIds?: readonly number[] | null;
}

export const MESSAGE_PAGE = 20;
export const RECIPIENT_PAGE = 20;
export const MAX_TITLE = 300;
export const MAX_BODY_BYTES = 2000;
export const MAX_RECIPIENTS = 300;
export const MAX_DRAFT_BODY = 20000;
/** Most ids one bulk delete may carry (`messageIds` or `exceptIds`). */
export const MAX_BULK = 500;
/** Most files one message may carry (`upload_policies` of `message_attachment`). */
export const MAX_FILES = 5;
export const ATTACH_PURPOSE = "message_attachment";

const BASE = "/api/messages";
const box = (name: BoxName): "sent" | "inbox" => (name === "sent" ? "sent" : "inbox");
const one = (name: BoxName, messageId: Id): string => `${BASE}/${box(name)}/${encodeURIComponent(messageId)}`;

/** Cache key of a received-box list: read filter (`""` | `"false"` | `"true"`) and title query. */
export const inboxKey = (read: string | null | undefined, q: string | null | undefined): string => `inbox|${read || ""}|${q || ""}`;
export const sentKey = (q: string | null | undefined): string => `sent|${q || ""}`;
export const recipientKey = (courseId: Id | null | undefined, q: string | null | undefined, page: number): string => `${courseId == null ? "-" : courseId}|${q || ""}|${page}`;

function listPath(name: BoxName, { read, q, cursor }: { read?: string | null; q?: string | null; cursor?: string | null } = {}): string {
  const params = new URLSearchParams({ size: String(MESSAGE_PAGE) });
  if (name === "inbox" && (read === "true" || read === "false")) params.set("read", read);
  if (q) params.set("q", q);
  if (cursor) params.set("cursor", cursor);
  return `${BASE}/${box(name)}?${params}`;
}

export const inboxLists = createResource<MessageList, [string | null | undefined, string | null | undefined]>((read, q) => apiGet<MessageList>(listPath("inbox", { read, q })));
export const sentLists = createResource<MessageList, [string | null | undefined]>((q) => apiGet<MessageList>(listPath("sent", { q })));
export const receivedDetails = createResource<MessageDetail, [Id]>((messageId) => apiGet<MessageDetail>(one("inbox", messageId)));
export const sentDetails = createResource<MessageDetail, [Id]>((messageId) => apiGet<MessageDetail>(one("sent", messageId)));
export const recipientPages = createResource<RecipientPage, [Id | null | undefined, string | null | undefined, number]>((courseId, q, page) => {
  const params = new URLSearchParams({ page: String(page), size: String(RECIPIENT_PAGE) });
  if (courseId != null) params.set("courseId", String(courseId));
  if (q) params.set("q", q);
  return apiGet<RecipientPage>(`${BASE}/recipients?${params}`);
});

const any = (): boolean => true;

function screenById(id: string): Screen {
  const screen = screens.find((item) => item.id === id);
  if (!screen) throw new Error(`unknown screen ${id}`);
  return screen;
}

/** `srv-<messageId>` → messageId, otherwise null. */
export function messageIdOf(value: unknown): number | null {
  const match = /^srv-([1-9]\d{0,15})$/.exec(String(value || ""));
  return match ? Number(match[1]) : null;
}

/** Detail of a received server message (notifications deep-link here). */
export function receivedHref(messageId: Id): string {
  return hrefFor(screenById("02c_msg_view"), { id: `srv-${messageId}` });
}

export function sentHref(messageId?: Id | null): string {
  return hrefFor(screenById("02d_msg_sent_tab"), messageId ? { id: `srv-${messageId}` } : {});
}

const moreLoads = new Map<string, Promise<MessageList | { restarted: true } | null>>(); // list key -> promise

/**
 * Reads the next page of a loaded list (`nextCursor`) and appends it to the cached entry.
 * Concurrent calls for one list share the request. Every page of one listing carries the first
 * page's `serverTime` and `total`, so the cached ones stay; 전체 삭제 sends that `serverTime` as
 * `until`. Only `unreadCount` (the whole inbox now) follows the latest page.
 * A refused cursor (400: issued before a server change, or malformed) drops the list and reads the
 * first page again; the promise then resolves to `{ restarted: true }`. Resolves to the page
 * otherwise, or null when there is nothing more or the list was replaced meanwhile.
 */
export function loadMore(name: BoxName, read: string | null | undefined, q: string | null | undefined): Promise<MessageList | { restarted: true } | null> {
  const resource = name === "sent" ? sentLists : inboxLists;
  const key = name === "sent" ? sentKey(q) : inboxKey(read, q);
  const entry = resource.peek(key);
  const cursor = entry && entry.data && entry.data.nextCursor;
  if (!cursor) return Promise.resolve(null);
  const shared = moreLoads.get(key);
  if (shared) return shared;
  const reloadFirst = (): Promise<void> => (name === "sent" ? sentLists.load(key, q) : loadInbox(read, q));
  const loading = apiGet<MessageList>(listPath(name, { read, q, cursor })).then((page): MessageList | { restarted: true } | null => {
    resource.patch((candidate) => candidate === key, (data) => {
      // A reload of the first page meanwhile replaced the list: this page no longer follows it.
      if (data.nextCursor !== cursor) return;
      const seen = new Set((data.items || []).map((item) => item.messageId));
      data.items = [...(data.items || []), ...(page.items || []).filter((item) => !seen.has(item.messageId))];
      data.nextCursor = page.nextCursor || null;
      if (name === "inbox") data.unreadCount = page.unreadCount;
    });
    if (name === "inbox" && Number.isInteger(page.unreadCount)) noteUnread("messages", page.unreadCount);
    return page;
  }, (error) => {
    if (!(error instanceof ApiError && error.status === 400)) throw error;
    // 다음 쪽 위치를 서버가 받지 않았다: 이 목록을 버리고 첫 쪽(cursor 없음)부터 다시 읽는다.
    const current = resource.peek(key);
    if (!current || !current.data || current.data.nextCursor !== cursor) return null;
    if (!current.loading) resource.drop((candidate) => candidate === key);
    return reloadFirst().then(() => ({ restarted: true as const }));
  }).finally(() => moreLoads.delete(key));
  moreLoads.set(key, loading);
  return loading;
}

/** Loads a received-box list and tells the header badge the server's unread count. */
export function loadInbox(read: string | null | undefined, q: string | null | undefined): Promise<void> {
  const key = inboxKey(read, q);
  return inboxLists.load(key, read, q).then(() => {
    const entry = inboxLists.peek(key);
    if (entry && entry.data && Number.isInteger(entry.data.unreadCount)) noteUnread("messages", entry.data.unreadCount);
  });
}

const readSending = new Map<Id, Promise<ReadState>>(); // messageId -> promise

/** `POST inbox/{id}/read` (the explicit 읽음 command). Returns `{messageId, read, wasUnread, unreadCount}`. */
export function markRead(messageId: number): Promise<ReadState> {
  const already = readSending.get(messageId);
  if (already) return already;
  const sending = apiSend<ReadState>("POST", `${one("inbox", messageId)}/read`).then((state) => {
    inboxLists.patch(any, (page) => {
      for (const item of page.items || []) if (item.messageId === messageId) item.read = true;
      if (Number.isInteger(state.unreadCount)) page.unreadCount = state.unreadCount;
    });
    receivedDetails.patch((key) => key === String(messageId), (view) => { view.read = true; });
    // readAt is set by the server once; read it back for the detail line.
    receivedDetails.load(String(messageId), messageId);
    if (Number.isInteger(state.unreadCount)) noteUnread("messages", state.unreadCount);
    return state;
  }).finally(() => readSending.delete(messageId));
  readSending.set(messageId, sending);
  return sending;
}

// The request may have reached the server: a retry of the same content keeps its key.
export const isUncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pending = new Map<string, { fingerprint: string; key: string }>(); // command path -> { fingerprint, key }
subscribe(() => pending.clear());

async function sendOnce(path: string, payload: Record<string, unknown>): Promise<SendReceipt> {
  const fingerprint = JSON.stringify(payload);
  const last = pending.get(path);
  const key = last && last.fingerprint === fingerprint ? last.key : newRequestKey();
  pending.set(path, { fingerprint, key });
  try {
    const receipt = await apiSend<SendReceipt>("POST", path, payload, { idempotencyKey: key });
    pending.delete(path);
    sentLists.drop(any);
    return receipt;
  } catch (error) {
    if (!isUncertain(error)) pending.delete(path);
    throw error;
  }
}

/**
 * New message `{title, body, courseId, recipientIds, uploadIds}`. 201 `{messageId, sentAt, recipients}`.
 * `uploadIds` are the sender's clean uploads of purpose `message_attachment` made without a courseId.
 */
export function sendMessage({ title, body, courseId, recipientIds, uploadIds }: AddressedInput): Promise<SendReceipt> {
  return sendOnce(BASE, { title, body, courseId: courseId == null ? null : courseId, recipientIds: [...recipientIds], uploadIds: [...(uploadIds || [])] });
}

/** Reply to the sender of a received message; the server picks the recipient. */
export function replyMessage(messageId: Id, { title, body, uploadIds }: MessageInput): Promise<SendReceipt> {
  return sendOnce(`${one("inbox", messageId)}/reply`, { title, body, uploadIds: [...(uploadIds || [])] });
}

/**
 * Forward a received (`"inbox"`) or sent (`"sent"`) message; same scope rule as a new message.
 * The original's files are not copied: only `uploadIds` go with the forward.
 */
export function forwardMessage(name: BoxName, messageId: Id, { title, body, courseId, recipientIds, uploadIds }: AddressedInput): Promise<SendReceipt> {
  return sendOnce(`${one(name, messageId)}/forward`, { title, body, courseId: courseId == null ? null : courseId, recipientIds: [...recipientIds], uploadIds: [...(uploadIds || [])] });
}

/**
 * `POST sent/{id}/recall`: takes back only unread copies. Returns
 * `{messageId, recalledNow, recipients: [{userId, name, outcome: "recalled" | "read"}]}`.
 */
export async function recallMessage(messageId: Id): Promise<RecallResult> {
  const result = await apiSend<RecallResult>("POST", `${one("sent", messageId)}/recall`);
  const recalled = new Set((result.recipients || []).filter((item) => item.outcome === "recalled").map((item) => item.userId));
  sentDetails.patch((key) => key === String(messageId), (view) => {
    for (const person of view.recipients || []) if (recalled.has(person.userId)) person.recalled = true;
  });
  sentLists.drop(any);
  sentDetails.load(String(messageId), messageId);
  return result;
}

function forgetBox(name: BoxName, ids: readonly Id[]): void {
  const gone = new Set(ids.map(String));
  if (name === "sent") {
    sentLists.drop(any);
    sentDetails.drop((key) => gone.has(key));
  } else {
    inboxLists.drop(any);
    receivedDetails.drop((key) => gone.has(key));
  }
}

/** Deletes one message from one box only (`DELETE inbox|sent/{id}`, 204). */
export async function deleteMessage(name: BoxName, messageId: Id): Promise<void> {
  try {
    await apiSend("DELETE", one(name, messageId));
  } finally {
    forgetBox(name, [messageId]);
    if (name !== "sent") refreshUnread();
  }
}

/**
 * Bulk delete by ids (`POST inbox|sent/delete {messageIds}`, at most `MAX_BULK`). One foreign or
 * already deleted id: 404, nothing deleted. The box is dropped when the delete ran or hit a message
 * that is gone (404). Any other failure keeps the loaded list so the screen keeps the selection;
 * after no answer (0, 502–504) a retry of the same ids either deletes them or gets that 404.
 */
export async function deleteMessages(name: BoxName, messageIds: readonly number[]): Promise<DeleteResult> {
  const forget = () => {
    forgetBox(name, messageIds);
    if (name !== "sent") refreshUnread();
  };
  try {
    const result = await apiSend<DeleteResult>("POST", `${BASE}/${box(name)}/delete`, { messageIds: [...messageIds] });
    forget();
    return result;
  } catch (error) {
    if (isMissing(error)) forget();
    else if (isUncertain(error) && name !== "sent") refreshUnread();
    throw error;
  }
}

const allDeleting = new Map<string, Promise<DeleteResult>>(); // box name -> promise

/**
 * 전체 삭제 (`POST inbox|sent/delete {all: true, read?, q?, until, exceptIds}`), 200 `{deleted}`.
 * `until` is the `serverTime` of the list on screen, `read`/`q` its filter (`read` only for
 * the inbox): messages that arrived after it stay. `exceptIds` are loaded rows left out.
 * One request per box at a time; the lists and details of that box are dropped afterwards.
 */
export function deleteAll(name: BoxName, { read, q, until, exceptIds }: DeleteAllInput): Promise<DeleteResult> {
  const target = box(name);
  const running = allDeleting.get(target);
  if (running) return running;
  const payload: { all: true; until: string | undefined; exceptIds: number[]; read?: boolean; q?: string } = { all: true, until, exceptIds: [...(exceptIds || [])] };
  if (target === "inbox" && (read === "true" || read === "false")) payload.read = read === "true";
  if (q) payload.q = q;
  const forget = () => {
    if (target === "sent") {
      sentLists.drop(any);
      sentDetails.drop(any);
    } else {
      inboxLists.drop(any);
      receivedDetails.drop(any);
      refreshUnread();
    }
  };
  const sending = apiSend<DeleteResult>("POST", `${BASE}/${target}/delete`, payload).then((result) => {
    forget();
    return result;
  }, (error) => {
    // The request may have run: the list on screen can be out of date.
    if (isUncertain(error)) forget();
    throw error;
  }).finally(() => allDeleting.delete(target));
  allDeleting.set(target, sending);
  return sending;
}

/**
 * The compose draft `{revision, title, body, course, recipients: [{userId, name, role}],
 * droppedRecipients, courseDropped, updatedAt}`, or null when there is none (204). The server
 * rechecks the stored course and recipients on every read: people no longer reachable are left
 * out and only counted, a course the user left is dropped.
 */
export function loadDraft(): Promise<MessageDraft | null> {
  return apiGet<MessageDraft | null>(`${BASE}/draft`);
}

/**
 * `PUT draft {revision, title, body, courseId, recipientIds}`; `revision` must exceed the stored
 * one (409 `draft_stale`). A course the user is not in is 404.
 */
export function saveDraft(revision: number, { title, body, courseId, recipientIds }: DraftInput): Promise<SavedDraft> {
  return apiSend<SavedDraft>("PUT", `${BASE}/draft`, { revision, title, body, courseId: courseId == null ? null : courseId, recipientIds: [...(recipientIds || [])] });
}

export function isDraftStale(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === "draft_stale";
}

export function isNotAllowed(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && error.code === "recipient_not_allowed";
}

/** 409 `attachment_not_ready`: an upload is not clean, not the sender's or not `message_attachment`. */
export function isAttachmentNotReady(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === "attachment_not_ready";
}

export function isMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** Bytes as the server counts them: 2 per character above U+007F, 1 otherwise. */
export function screenBytes(text: unknown): number {
  let length = 0;
  for (const char of String(text || "")) length += char.length === 1 && char.charCodeAt(0) <= 127 ? 1 : char.length * 2;
  return length;
}

export const ROLE_LABEL: Record<string, string> = { INSTRUCTOR: "교수", STUDENT: "학생" };

/** Korean message for a failed message request. */
export function messageMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "쪽지 요청을 처리하지 못했습니다.";
  if (isNotAllowed(error)) return `${error.detail || "쪽지를 보낼 수 없는 받는사람이 있습니다."} 아무에게도 보내지 않았습니다. 받는 사람을 다시 고르세요.`;
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "쪽지를 찾을 수 없습니다. 지워졌거나 보낸 사람이 회수한 쪽지일 수 있습니다.";
    case 409: return isDraftStale(error)
      ? error.detail || "더 새로운 임시저장본이 있습니다."
      : isAttachmentNotReady(error)
        ? "보낼 수 없는 첨부 파일이 있어 아무에게도 보내지 않았습니다. 표시된 파일을 빼고 다시 보내세요."
        : error.code === "idempotency_conflict"
          ? "같은 요청이 다른 내용으로 처리된 적이 있습니다. 내용을 확인한 뒤 다시 보내세요."
          : error.detail || "요청이 다른 변경과 겹쳤습니다. 다시 불러온 뒤 시도하세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "쪽지 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
