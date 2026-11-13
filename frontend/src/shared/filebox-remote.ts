// Personal file box on the ASTRA backend (`/api/filebox/**`, ADR-0028) and reuse of its files as
// course uploads or course-less uploads (`POST /api/uploads/from-filebox`). Every item belongs to the signed-in user; a
// foreign id anywhere in a request is a 404 for the whole request. Listings stay in memory only.
import { apiGet, apiSend, apiUpload, apiBlob, ApiError, newRequestKey } from "./api.ts";
import { createResource, retryLater } from "./remote-cache.ts";
import { saveBlob } from "./uploads-remote.tsx";
import type { UploadView } from "./uploads-remote.tsx";

/** A file or folder in my file box. */
export interface FileboxItem {
  id: number;
  kind: string;
  name: string;
  version: number;
  state?: string;
  sizeBytes?: number | null;
  extension?: string | null;
  itemCount?: number | null;
  createdAt?: string;
  trashedAt?: string | null;
}

/** A folder on the path from the root to the listed folder. */
export interface FileboxCrumb {
  id: number;
  name: string;
}

export interface FileboxUsage {
  usedBytes: number;
  quotaBytes: number;
  itemCount: number;
  maxItems: number;
}

interface FileboxPage {
  path?: FileboxCrumb[];
  usage?: FileboxUsage | null;
  nextCursor?: string | null;
  items?: FileboxItem[];
}

/** All loaded pages of one folder listing. */
export interface FileboxListing {
  path: FileboxCrumb[];
  usage: FileboxUsage | null;
  nextCursor: string | null;
  items: FileboxItem[];
}

/** All loaded pages of the trash. */
export interface FileboxTrash {
  nextCursor: string | null;
  items: FileboxItem[];
}

/** Answer of a bulk command (move, trash, restore, purge, empty). */
export interface FileboxCount {
  count: number;
}

/** Where a file box file is attached. */
export interface FileboxReference {
  courseName?: string | null;
  purpose?: string | null;
  attachedAt?: string | null;
}

/** A ZIP job. */
export interface ZipJob {
  jobId: number | string;
  state: string;
  itemCount?: number;
  sizeBytes?: number | null;
  expiresAt?: string | null;
  failure?: string | null;
}

/** An upload made from a file box file. */
export interface FileboxUpload extends UploadView {
  itemId: number;
  expiresAt?: string | null;
}

export const FILEBOX_PAGE = 50;
export const SORTS: Readonly<Record<string, string>> = { date: "올린 날짜", name: "이름", size: "크기" };
export const SCAN_STATE: Readonly<Record<string, string>> = { ready: "", scanning: "검사 중", rejected: "검사 거부" };

/** Listing key: folder (`""` = root), sort, order, search. */
export const listKey = (parentId: number | string | null | undefined, sort: string, order: string, q: string): string => `${parentId || ""}|${sort}|${order}|${q}`;
export const TRASH = "trash";

// All loaded pages of one folder listing; "더 보기" appends the next cursor page.
export const listings = createResource<FileboxListing, [parentId: number | string | null | undefined, sort: string, order: string, q: string, previous?: FileboxListing | null]>(async (parentId, sort, order, q, previous) => {
  const params = new URLSearchParams({ sort, order, size: String(FILEBOX_PAGE) });
  if (parentId) params.set("parentId", String(parentId));
  if (q) params.set("q", q);
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet<FileboxPage | null>(`/api/filebox/items?${params}`);
  const items = page && Array.isArray(page.items) ? page.items : [];
  return {
    path: page && Array.isArray(page.path) ? page.path : [],
    usage: (page && page.usage) || null,
    nextCursor: (page && page.nextCursor) || null,
    items: previous ? [...previous.items, ...items] : items,
  };
});

export const trash = createResource<FileboxTrash, [previous?: FileboxTrash | null]>(async (previous) => {
  const params = new URLSearchParams({ size: String(FILEBOX_PAGE) });
  if (previous && previous.nextCursor) params.set("cursor", previous.nextCursor);
  const page = await apiGet<FileboxPage | null>(`/api/filebox/trash?${params}`);
  const items = page && Array.isArray(page.items) ? page.items : [];
  return { nextCursor: (page && page.nextCursor) || null, items: previous ? [...previous.items, ...items] : items };
});

/** After any change every cached listing may be stale (moves and trash touch two folders). */
export function forgetFilebox(): void {
  listings.drop(() => true);
  trash.drop(() => true);
}

/** Folders directly under `parentId` (move target picker), first 100 by name. */
export async function foldersIn(parentId: number | string | null | undefined): Promise<{ path: FileboxCrumb[]; folders: FileboxItem[] }> {
  const params = new URLSearchParams({ sort: "name", order: "asc", size: "100" });
  if (parentId) params.set("parentId", String(parentId));
  const page = await apiGet<FileboxPage | null>(`/api/filebox/items?${params}`);
  return {
    path: page && Array.isArray(page.path) ? page.path : [],
    folders: (page && Array.isArray(page.items) ? page.items : []).filter((item) => item.kind === "folder"),
  };
}

// A command that may have reached the server without an answer keeps its key on a same-content retry.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pendingKeys = new Map<string, { fingerprint: string; key: string }>(); // operation -> { fingerprint, key }

async function keyed<T>(operation: string, fingerprint: string, send: (key: string) => Promise<T>): Promise<T> {
  const last = pendingKeys.get(operation);
  const key = last && last.fingerprint === fingerprint ? last.key : newRequestKey();
  pendingKeys.set(operation, { fingerprint, key });
  try {
    const result = await send(key);
    pendingKeys.delete(operation);
    return result;
  } catch (error) {
    if (!uncertain(error)) pendingKeys.delete(operation);
    throw error;
  }
}

export async function createFolder(parentId: number | string | null | undefined, name: string): Promise<FileboxItem> {
  const body = { parentId: parentId || null, name };
  const folder = await keyed("folder", JSON.stringify(body), (key) => apiSend<FileboxItem>("POST", "/api/filebox/folders", body, { idempotencyKey: key }));
  forgetFilebox();
  return folder;
}

/** Uploads one file into `parentId`. 409 `quota_exceeded` / `too_many_items` when the box is full. */
export async function uploadToFilebox(file: File, parentId: number | string | null | undefined): Promise<FileboxItem> {
  const form = new FormData();
  form.append("file", file, String(file.name || "file"));
  if (parentId) form.append("parentId", String(parentId));
  const fingerprint = JSON.stringify([parentId || null, file.name, file.size, file.lastModified]);
  const item = await keyed(`upload:${fingerprint}`, fingerprint, (key) => apiUpload<FileboxItem>("/api/filebox/files", form, { idempotencyKey: key }));
  forgetFilebox();
  return item;
}

/** Rename with `If-Match: "<version>"` (428 without it, 409 `version_conflict` when stale). */
export async function renameItem(item: Pick<FileboxItem, "id" | "version">, name: string): Promise<FileboxItem> {
  const updated = await apiSend<FileboxItem>("PATCH", `/api/filebox/items/${encodeURIComponent(item.id)}`, { name }, { ifMatch: item.version });
  forgetFilebox();
  return updated;
}

/** All-or-nothing move: every item with the version the screen read. `targetFolderId` null = root. */
export async function moveItems(items: Pick<FileboxItem, "id" | "version">[], targetFolderId: number | string | null | undefined): Promise<FileboxCount> {
  try {
    return await apiSend<FileboxCount>("POST", "/api/filebox/items/move", {
      targetFolderId: targetFolderId || null,
      items: items.map((item) => ({ id: item.id, version: item.version })),
    });
  } finally {
    forgetFilebox();
  }
}

async function bulk(command: string, ids: number[]): Promise<FileboxCount> {
  try {
    return await apiSend<FileboxCount>("POST", `/api/filebox/items/${command}`, { ids });
  } finally {
    forgetFilebox();
  }
}
export const trashItems = (ids: number[]): Promise<FileboxCount> => bulk("trash", ids);
export const restoreItems = (ids: number[]): Promise<FileboxCount> => bulk("restore", ids);
export const purgeItems = (ids: number[]): Promise<FileboxCount> => bulk("purge", ids);

export async function emptyTrash(): Promise<FileboxCount> {
  try {
    return await apiSend<FileboxCount>("POST", "/api/filebox/trash/empty");
  } finally {
    forgetFilebox();
  }
}

/** Downloads one of my files (always an attachment). 409 `not_ready` while the scan holds it. */
export async function downloadItem(item: Pick<FileboxItem, "id" | "name">): Promise<void> {
  const blob = await apiBlob(`/api/filebox/items/${encodeURIComponent(item.id)}/content`);
  saveBlob(blob, item.name);
}

export async function itemReferences(itemId: number | string): Promise<FileboxReference[]> {
  const body = await apiGet<{ references?: FileboxReference[] } | null>(`/api/filebox/items/${encodeURIComponent(itemId)}/references`);
  return body && Array.isArray(body.references) ? body.references : [];
}

// ---------- ZIP ----------

/**
 * Requests a ZIP of files (no folders). `Idempotency-Key` is required; a retry of the same set after
 * a lost answer reuses its key, and the server also returns an existing job for the same set.
 */
export function requestZip(itemIds: Iterable<number>): Promise<ZipJob> {
  const ids = [...itemIds].sort((a, b) => a - b);
  return keyed("zip", JSON.stringify(ids), (key) => apiSend<ZipJob>("POST", "/api/filebox/zip-jobs", { itemIds: ids }, { idempotencyKey: key }));
}

export function zipStatus(jobId: number | string): Promise<ZipJob> {
  return apiGet<ZipJob>(`/api/filebox/zip-jobs/${encodeURIComponent(jobId)}`);
}

/** Saves a ready ZIP. 409 `not_ready` while building, 410 once the two-hour window has passed. */
export async function downloadZip(job: Pick<ZipJob, "jobId">): Promise<void> {
  const blob = await apiBlob(`/api/filebox/zip-jobs/${encodeURIComponent(job.jobId)}/download`);
  saveBlob(blob, `filebox-${job.jobId}.zip`);
}

// ---------- reuse as an upload ----------

/** 과목 없이 파일함 파일을 다시 쓸 수 있는 용도(ADR-0035). 그 밖의 용도는 과목 업로드라 `courseId`가 있어야 한다. */
export const OUTSIDE_COURSE_PURPOSES: ReadonlySet<string> = new Set(["community_notice", "community_material", "inquiry_attachment", "message_attachment"]);

/**
 * Creates `clean` uploads that point at the same content as file box files, for attaching through
 * a parent save exactly like `uploadFile` results (`uploadIds`, `attachments.add`). `courseId` is
 * null for an `OUTSIDE_COURSE_PURPOSES` purpose and is then left out of the body.
 * Resolves `[{ uploadId, itemId, state, name, sizeBytes, expiresAt }]`.
 */
export async function uploadsFromFilebox(itemIds: Iterable<number>, courseId: number | string | null | undefined, purpose: string): Promise<FileboxUpload[]> {
  const body = { itemIds: [...itemIds], ...(courseId == null ? {} : { courseId: Number(courseId) }), purpose: String(purpose) };
  const receipt = await keyed("reuse", JSON.stringify(body), (key) => apiSend<{ uploads?: FileboxUpload[] } | null>("POST", "/api/uploads/from-filebox", body, { idempotencyKey: key }));
  return receipt && Array.isArray(receipt.uploads) ? receipt.uploads : [];
}

// ---------- messages ----------

/** Korean message for a failed file box request. */
export function fileboxMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "파일함 요청을 처리하지 못했습니다.";
  switch (error.code) {
    case "quota_exceeded": return "파일함 용량이 부족합니다. 휴지통을 비우거나 파일을 지운 뒤 다시 올리세요.";
    case "too_many_items": return "파일함 항목 수 한도를 넘습니다. 필요 없는 항목을 영구 삭제하세요.";
    case "cycle": return "폴더를 자기 자신이나 그 아래 폴더로 옮길 수 없습니다. 아무것도 옮기지 않았습니다.";
    case "version_conflict": return "다른 곳에서 먼저 바뀌었습니다. 목록을 다시 불러왔습니다. 확인한 뒤 다시 시도하세요.";
    case "name_conflict": return "같은 이름의 항목이 이미 있습니다. 다른 이름을 쓰세요.";
    case "too_deep": return error.detail || "폴더를 더 깊이 만들 수 없습니다.";
    case "not_ready": return "검사를 마치지 않았거나 거부된 파일입니다. 잠시 후 다시 시도하세요.";
    case "not_in_trash": return "휴지통에 넣은 항목만 고를 수 있습니다. 목록을 새로 불러오세요.";
    case "not_active": return "휴지통에 있는 항목이 섞여 있습니다. 목록을 새로 불러오세요.";
    case "folder_not_allowed": return "폴더는 ZIP으로 받거나 첨부할 수 없습니다. 파일만 고르세요.";
    case "zip_too_large": return "ZIP에 넣을 파일의 크기 합이 500MB를 넘습니다.";
    case "too_many_jobs": return "진행 중인 ZIP 작업이 끝난 뒤 다시 요청하세요.";
    case "expired": return "ZIP을 내려받을 수 있는 시간(2시간)이 지났습니다. 다시 요청하세요.";
    case "extension_not_allowed": return "올릴 수 없는 파일 형식입니다.";
    case "idempotency_conflict": return "같은 요청이 다른 내용으로 처리 중입니다. 목록을 새로 불러오세요.";
    default: break;
  }
  switch (error.status) {
    case 400: return error.detail || "입력한 값을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "항목을 찾을 수 없습니다. 이미 옮겼거나 지운 항목일 수 있습니다.";
    case 410: return "ZIP을 내려받을 수 있는 시간이 지났습니다. 다시 요청하세요.";
    case 413: return "파일이 너무 큽니다.";
    case 422: return "파일 내용이 확장자와 맞지 않거나 올릴 수 없는 파일입니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "파일함 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

export const PURPOSE_LABEL: Readonly<Record<string, string>> = {
  lecture_material: "강의 자료",
  assignment_submission: "과제 제출",
  notice_attachment: "공지 첨부",
};
