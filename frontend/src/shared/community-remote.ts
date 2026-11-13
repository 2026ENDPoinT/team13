// 알림마당 자료실(`/api/community/materials`)과 1:1 문의(`/api/community/qna`) on the ASTRA backend.
// The server picks the audiences from the session's institution role and returns only the
// caller's own inquiries unless an 알림마당 운영자 asks for `all=true`; nothing here chooses a user.
// Viewing a material never marks it read: the detail view sends `read` once. Anyone logged in
// writes a 1:1 문의 (with `inquiry_attachment` files); operators answer it and create, edit and
// delete materials (with `community_material` files). Files have no course: they are uploaded
// without `courseId` and downloaded through `GET /api/files/{fileId}/content`. 운영자인지는 자료실·문의
// 목록 응답의 `canManage`로만 안다(상세의 `canManage`·`canAnswer`는 그 글에 대한 것이다).
// Lists and details stay in memory only.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";
import type { Resource } from "./remote-cache.ts";
import { hrefFor, find } from "../manifest.ts";

export const COMMUNITY_PAGE = 20;
export const AUDIENCES: readonly string[] = ["all", "common", "professor", "student"];
export const INQUIRY_STATUS: readonly string[] = ["all", "answered", "unanswered"];
/** Audiences an operator picks for a material (the stored value is the upper-case name). */
export const MATERIAL_AUDIENCES: readonly string[] = ["common", "professor", "student"];
// Limits from the backend records (CommunityService, BoardFiles); lengths are UTF-16 units.
export const MAX_TITLE = 300;
export const MAX_BODY = 20000;
export const INQUIRY_UPLOADS = 5;
export const MATERIAL_UPLOADS = 10;

const materialsPath = "/api/community/materials";
const inquiriesPath = "/api/community/qna";

/** An attached file of a material, inquiry or reply. */
export interface CommunityFile {
  fileId: string;
  name?: string | null;
  sizeBytes?: number | null;
}

/** A material or inquiry as a list row or detail view. */
export interface CommunityPost {
  postId: string;
  title?: string;
  body?: string | null;
  excerpt?: string | null;
  audience?: string;
  authorName?: string | null;
  createdAt?: string;
  publishedAt?: string | null;
  read?: boolean;
  viewCount?: number;
  version?: number;
  mine?: boolean;
  canManage?: boolean;
  canAnswer?: boolean;
  answered?: boolean;
  answeredAt?: string | null;
  answerCount?: number;
  attachments?: CommunityFile[];
  replies?: CommunityReply[];
}

/** An operator's answer to an inquiry. */
export interface CommunityReply {
  replyId: string;
  body?: string;
  authorName?: string | null;
  createdAt?: string;
  attachments?: CommunityFile[];
}

/** One server page of a list. */
export interface CommunityPageResponse {
  items?: CommunityPost[];
  nextCursor?: string | null;
  counts?: Record<string, number>;
  canManage?: boolean;
}

/** A cached list: the pages loaded so far. */
export interface CommunityList extends CommunityPageResponse {
  items: CommunityPost[];
  nextCursor: string | null;
  pages: number;
}

/** `POST …/materials/{id}/read` answer. */
export interface MaterialReadState {
  read: boolean;
  viewCount: number;
}

export interface InquiryCreate {
  title: string;
  body?: string | null;
  uploadIds?: string[];
}

export interface InquiryAnswer {
  body: string;
  uploadIds?: string[];
}

export interface MaterialCreate {
  title: string;
  body: string | null;
  audience: string;
  uploadIds: string[];
}

export interface MaterialUpdate {
  title: string;
  body: string | null;
  audience: string;
  attachments?: { keep: string[]; add: string[] };
  version: number;
}

export const materialListKey = (audience: string, q: string): string => `materials|${audience}|${q}`;
/** `all`: an operator's view of everyone's inquiries (with `authorName`). */
export const inquiryListKey = (status: string, q: string, all = false): string => `inquiries|${status}|${q}${all ? "|all" : ""}`;

const listUrl = (path: string, filter: string, name: string, q: string) => {
  const params = new URLSearchParams({ size: String(COMMUNITY_PAGE) });
  if (filter && filter !== "all") params.set(name, filter);
  if (q) params.set("q", q);
  return `${path}?${params}`;
};
const materialsUrl = (audience: string, q: string) => listUrl(materialsPath, audience, "audience", q);
const inquiriesUrl = (status: string, q: string, all = false) => `${listUrl(inquiriesPath, status, "status", q)}${all ? "&all=true" : ""}`;

// 마지막으로 받은 자료실·문의 목록의 `canManage`(알림마당 운영자인지). null은 이 세션에서 아직 목록을 읽지 않은 것이다.
let manage: boolean | null = null;
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  manage = null;
});

async function firstPage(request: Promise<CommunityPageResponse | null>): Promise<CommunityList> {
  const at = session;
  const page = await request;
  if (at === session && page && typeof page.canManage === "boolean") manage = page.canManage;
  const items = page && page.items;
  return { ...page, items: Array.isArray(items) ? items : [], nextCursor: page && page.nextCursor != null ? page.nextCursor : null, pages: 1 };
}

export const materialLists = createResource<CommunityList, [audience: string, q: string]>((audience, q) => firstPage(apiGet<CommunityPageResponse | null>(materialsUrl(audience, q))));
// A create answers with the view itself: `seed()` caches it, and any later load asks the server.
export const materialDetails = createResource<CommunityPost, [postId: string]>((postId) => apiGet<CommunityPost>(`${materialsPath}/${encodeURIComponent(postId)}`));
export const inquiryLists = createResource<CommunityList, [status: string, q: string, all?: boolean]>((status, q, all = false) => firstPage(apiGet<CommunityPageResponse | null>(inquiriesUrl(status, q, all))));
export const inquiryDetails = createResource<CommunityPost, [postId: string]>((postId) => apiGet<CommunityPost>(`${inquiriesPath}/${encodeURIComponent(postId)}`));

/** 목록 응답의 `canManage`: true / false, 이 세션에서 아직 자료실·문의 목록을 읽지 않았으면 null. */
export function communityManageState(): boolean | null {
  return manage;
}

/** 운영자인지 모르면 자료실 첫 쪽을 한 번 읽어 `canManage`를 얻는다. 알게 된 값(실패하면 null)으로 끝난다. */
export function checkCommunityManage(): Promise<boolean | null> {
  if (manage !== null) return Promise.resolve(manage);
  return materialLists.load(materialListKey("all", ""), "all", "").then(() => manage);
}

const appending = new Set<string>();
async function appendNext(resource: Resource<CommunityList>, key: string, url: string): Promise<boolean> {
  const entry = resource.peek(key);
  const data = entry && entry.data;
  if (!data || data.nextCursor == null || appending.has(key)) return false;
  appending.add(key);
  try {
    const page = await apiGet<CommunityPageResponse>(`${url}&cursor=${encodeURIComponent(data.nextCursor)}`);
    resource.patch((k) => k === key, (current) => {
      const seen = new Set(current.items.map((item) => item.postId));
      current.items.push(...(Array.isArray(page.items) ? page.items : []).filter((item) => !seen.has(item.postId)));
      current.nextCursor = page.nextCursor != null ? page.nextCursor : null;
      if (page.counts) current.counts = page.counts;
      current.pages += 1;
    });
    return true;
  } finally {
    appending.delete(key);
  }
}

export const isAppending = (key: string): boolean => appending.has(key);
export const moreMaterials = (audience: string, q: string): Promise<boolean> => appendNext(materialLists, materialListKey(audience, q), materialsUrl(audience, q));
export const moreInquiries = (status: string, q: string, all = false): Promise<boolean> => appendNext(inquiryLists, inquiryListKey(status, q, all), inquiriesUrl(status, q, all));

const readSent = new Set<string>(); // postId, per session
subscribe(() => readSent.clear());

/** Marks a material read when its detail is opened. Sends at most once per session. */
export async function markMaterialRead(postId: string): Promise<MaterialReadState | null> {
  if (readSent.has(postId)) return null;
  readSent.add(postId);
  try {
    const state = await apiSend<MaterialReadState>("POST", `${materialsPath}/${encodeURIComponent(postId)}/read`);
    const apply = (item: CommunityPost) => {
      if (item && item.postId === postId) {
        item.read = state.read;
        item.viewCount = state.viewCount;
      }
    };
    materialDetails.patch((key) => key === postId, apply);
    materialLists.patch(() => true, (page) => (page.items || []).forEach(apply));
    return state;
  } catch (error) {
    readSent.delete(postId);
    throw error;
  }
}

// ---------- commands ----------

// The request may have reached the server: a retry of the same content keeps its key.
const uncertain = (error: unknown) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pending = new Map<string, { fingerprint: string; key: string }>(); // operation -> { fingerprint, key }
subscribe(() => pending.clear());

async function createOnce<T>(operation: string, fingerprint: unknown, send: (key: string) => Promise<T>): Promise<T> {
  const print = JSON.stringify(fingerprint);
  const last = pending.get(operation);
  const key = last && last.fingerprint === print ? last.key : newRequestKey();
  pending.set(operation, { fingerprint: print, key });
  try {
    const result = await send(key);
    pending.delete(operation);
    return result;
  } catch (error) {
    if (!uncertain(error)) pending.delete(operation);
    throw error;
  }
}

/** True when the last create of `operation` may have reached the server (a retry reuses its key). */
export function createUncertain(operation: string): boolean {
  return pending.has(operation);
}

export const INQUIRY_CREATE = "inquiry:create";
export const MATERIAL_CREATE = "material:create";
export const answerOperation = (inquiryId: string): string => `inquiry:answer:${inquiryId}`;

const forgetInquiryLists = () => inquiryLists.drop(() => true);
const forgetMaterialLists = () => materialLists.drop(() => true);

/** `{ title, body?, uploadIds? }` → 201 with the inquiry view (cached as its detail). */
export async function createInquiry(payload: InquiryCreate): Promise<CommunityPost> {
  const view = await createOnce(INQUIRY_CREATE, payload, (key) => apiSend<CommunityPost>("POST", inquiriesPath, payload, { idempotencyKey: key }));
  forgetInquiryLists();
  inquiryDetails.seed(view.postId, view, view.postId);
  return view;
}

/** Operator answer `{ body, uploadIds? }` → 201 with the reply; the inquiry becomes answered. */
export async function answerInquiry(inquiryId: string, payload: InquiryAnswer): Promise<CommunityReply> {
  const reply = await createOnce(answerOperation(inquiryId), [inquiryId, payload], (key) => apiSend<CommunityReply>("POST", `${inquiriesPath}/${encodeURIComponent(inquiryId)}/replies`, payload, { idempotencyKey: key }));
  inquiryDetails.patch((k) => k === inquiryId, (current) => {
    const replies = Array.isArray(current.replies) ? current.replies : [];
    if (!replies.some((item) => item.replyId === reply.replyId)) replies.push(reply);
    current.replies = replies;
    current.answered = true;
    current.answeredAt = current.answeredAt || reply.createdAt;
  });
  forgetInquiryLists();
  return reply;
}

/** Operator create `{ title, body, audience, uploadIds }` → 201 with the material view. */
export async function createMaterial(payload: MaterialCreate): Promise<CommunityPost> {
  const view = await createOnce(MATERIAL_CREATE, payload, (key) => apiSend<CommunityPost>("POST", materialsPath, payload, { idempotencyKey: key }));
  forgetMaterialLists();
  materialDetails.seed(view.postId, view, view.postId);
  return view;
}

/**
 * Operator full edit `{ title, body, audience, attachments?: { keep, add }, version }`. 409
 * `version_conflict` when someone saved first; 409 `attachment_not_ready` for a bad upload.
 */
export async function updateMaterial(postId: string, payload: MaterialUpdate): Promise<CommunityPost> {
  const view = await apiSend<CommunityPost>("PUT", `${materialsPath}/${encodeURIComponent(postId)}`, payload);
  materialDetails.patch((k) => k === postId, (current) => Object.assign(current, view));
  forgetMaterialLists();
  return view;
}

/** Operator delete (204). Cached copies are dropped whether it worked or was already gone. */
export async function deleteMaterial(postId: string): Promise<void> {
  try {
    await apiSend("DELETE", `${materialsPath}/${encodeURIComponent(postId)}`);
  } finally {
    materialDetails.drop((k) => k === postId);
    forgetMaterialLists();
  }
}

/** The 1:1 문의 detail dialog of one inquiry (e.g. for a `QUESTION_ANSWERED` notification without a course). */
export function inquiryHref(inquiryId: string | number): string {
  return hrefFor(find("31_community_qna_list"), { pop: "inquiry", id: String(inquiryId) });
}

export const isCode = (error: unknown, status: number, code?: string): boolean => error instanceof ApiError && error.status === status && (!code || error.code === code);

// 을/를 after `noun`.
function object(noun: string) {
  const last = String(noun).charCodeAt(String(noun).length - 1);
  return last >= 0xac00 && last <= 0xd7a3 && (last - 0xac00) % 28 ? `${noun}을` : `${noun}를`;
}

/** Korean message for a failed 알림마당 command (write, answer, edit, delete). */
export function commandMessage(error: unknown, noun = "자료"): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : `${object(noun)} 저장하지 못했습니다.`;
  if (error.status === 409 && error.code === "version_conflict") return `다른 곳에서 먼저 고친 ${noun}입니다. 최신 내용을 불러왔으니 확인한 뒤 다시 저장하세요.`;
  if (error.status === 409 && error.code === "attachment_not_ready") return "첨부할 수 없는 파일이 있습니다. 표시된 파일을 빼거나 다시 올린 뒤 저장하세요.";
  if (error.status === 409 && error.code === "idempotency_conflict") return "앞선 요청과 내용이 달라 처리하지 못했습니다. 다시 시도하세요.";
  if (error.status === 400 && error.code === "idempotency_key_required") return "요청을 처리하지 못했습니다. 다시 시도하세요.";
  switch (error.status) {
    case 400: return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return `${object(noun)} 찾을 수 없거나 처리할 권한이 없습니다.`;
    case 413: return error.detail || "내용이 너무 깁니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return `${object(noun)} 처리하지 못했습니다. 잠시 후 다시 시도하세요.`;
  }
}

/** "공통", "교수", "학생" for a material's `audience`. */
export function audienceLabel(audience: string): string {
  const labels: Record<string, string> = { common: "공통", professor: "교수", student: "학생" };
  return labels[audience] || "공통";
}

/** Korean message for a failed 알림마당 request. `noun` is "자료" or "문의". */
export function communityMessage(error: unknown, noun = "자료"): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : `${noun}를 불러오지 못했습니다.`;
  switch (error.status) {
    case 400: return error.detail || "검색 조건을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return `${noun}를 찾을 수 없거나 볼 수 있는 권한이 없습니다.`;
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return `${noun}를 불러오지 못했습니다. 잠시 후 다시 시도하세요.`;
  }
}
