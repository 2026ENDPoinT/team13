// The student's own study room on the ASTRA backend (`/api/me/study-room/**`, ADR-0040): folders, item
// placement and lecture recordings. study-folders.ts and course-recordings.ts delegate here while the backend
// connection is `ready`; every path is private to the signed-in user. Course views stay in memory only.
// A backend without these endpoints (404 on the course view) leaves both stores in the browser for the session.
import { apiGet, apiSend, apiUpload, ApiError, newRequestKey } from "./api.ts";
import { backendStatus, courseIdFor, subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";

type Id = number | string;

export const BASE = "/api/me/study-room";

/** A folder as the server returns it. */
export interface ServerFolder {
  id: number;
  courseId: number;
  name: string;
  position: number;
  createdAt: string;
  version: number;
}

export type RecordingState = "scanning" | "ready" | "rejected";

/** A recording as the server returns it. */
export interface ServerRecording {
  id: number;
  courseId: number;
  title: string;
  source: "mic" | "tab" | "audio";
  mimeType: string;
  sizeBytes: number;
  durationMs: number;
  createdAt: string;
  fileName: string | null;
  state: RecordingState;
  version: number;
}

export interface ServerPlacement {
  kind: "course_file" | "recording";
  itemId: number;
  folderId: number;
  version: number;
}

export interface StudyUsage {
  usedBytes: number;
  quotaBytes: number;
  recordingCount: number;
  maxRecordings: number;
  /** null when the upload policy has no ceiling. */
  maxFileBytes: number | null;
  maxFoldersPerCourse: number;
}

export interface StudyCourseView {
  courseId: number;
  folders: ServerFolder[];
  recordings: ServerRecording[];
  placements: ServerPlacement[];
  usage: StudyUsage | null;
}

// ---------- availability ----------

// Set when the backend answered 404 for a study-room listing: an older backend without ADR-0040.
let missing = false;
let lastUsage: StudyUsage | null = null;
let session = "";
subscribe((next) => {
  const key = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (key === session) return;
  session = key;
  missing = false;
  lastUsage = null;
  pendingKeys.clear();
});

/** True while the study room lives on the server: backend `ready` and the endpoints exist. */
export const studyRoomOnServer = (): boolean => backendStatus() === "ready" && !missing;

/** The backend course id whose study room `kj` addresses on the server, or null (browser storage). */
export function serverStudyCourse(kj: string | null | undefined): Id | null {
  return studyRoomOnServer() ? courseIdFor(kj) : null;
}

/** A listing failure that means "this backend has no study room": remember it and fall back. */
export function isMissingEndpoint(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status !== 404) return false;
  missing = true;
  return true;
}

/** The latest usage the server reported (any course view, upload or listing), or null. */
export const studyUsage = (): StudyUsage | null => lastUsage;

// ---------- course views ----------

const list = <T>(value: unknown): T[] => (Array.isArray(value) ? value as T[] : []);

export const courseViews = createResource<StudyCourseView, [courseId: Id]>(async (courseId) => {
  const body = await apiGet<Partial<StudyCourseView> | null>(`${BASE}/courses/${encodeURIComponent(courseId)}`);
  const view: StudyCourseView = {
    courseId: Number(courseId),
    folders: list<ServerFolder>(body && body.folders),
    recordings: list<ServerRecording>(body && body.recordings),
    placements: list<ServerPlacement>(body && body.placements),
    usage: (body && body.usage) || null,
  };
  if (view.usage) lastUsage = view.usage;
  return view;
});

export const viewKey = (courseId: Id): string => String(courseId);

/**
 * The course view of `courseId`, loaded now. `fresh`: never reuse a load that started before the caller's last
 * write (it waits for that one, then asks again); otherwise a load already running is shared.
 * Throws the load error; a 404 marks the endpoints missing (callers then use the browser store).
 */
export async function loadCourseView(courseId: Id, fresh = false): Promise<StudyCourseView> {
  const key = viewKey(courseId);
  if (fresh) await reloadAfterWrite(key, courseId);
  else await courseViews.load(key, courseId);
  const entry = courseViews.peek(key);
  if (entry && entry.error) throw entry.error;
  if (!entry || !entry.data) throw new ApiError(0, { detail: "학습실 정보를 받지 못했습니다." });
  return entry.data;
}

/** A load that starts after now: a load already in flight may have read the state before a write. */
async function reloadAfterWrite(key: string, courseId: Id): Promise<void> {
  const running = courseViews.peek(key)?.loading;
  if (running) await running;
  await courseViews.load(key, courseId);
}

/** Cached view, or null; starts a load when nothing is cached for it yet. */
export function peekCourseView(courseId: Id): { view: StudyCourseView | null; error: unknown; loading: boolean } {
  const key = viewKey(courseId);
  const entry = courseViews.peek(key);
  if (!entry) {
    // Screens call this while rendering: the load (which notifies every reader) starts after the render.
    if (!queued.has(key)) {
      queued.add(key);
      queueMicrotask(() => {
        queued.delete(key);
        if (!courseViews.peek(key)) void courseViews.load(key, courseId);
      });
    }
    return { view: null, error: null, loading: true };
  }
  return { view: entry.data, error: entry.error, loading: Boolean(entry.loading) };
}
const queued = new Set<string>();

/** Every cached course view (placements are looked up across courses). */
export function cachedViews(): StudyCourseView[] {
  const views: StudyCourseView[] = [];
  for (const course of serverCourseKeys) {
    const entry = courseViews.peek(course);
    if (entry && entry.data) views.push(entry.data);
  }
  return views;
}
// The keys loaded in this session, so `cachedViews` can walk them (the resource has no key listing).
const serverCourseKeys = new Set<string>();
courseViews.subscribe((key) => {
  if (key) serverCourseKeys.add(key);
  else serverCourseKeys.clear();
});

/** Reloads the view after a write (never reusing a load that started before it); errors stay in the cache entry. */
export function refreshCourseView(courseId: Id): Promise<void> {
  return reloadAfterWrite(viewKey(courseId), courseId);
}

// ---------- writes ----------

// A command that may have reached the server without an answer keeps its key on a same-content retry.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
const pendingKeys = new Map<string, { fingerprint: string; key: string }>();

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

/** `POST …/folders`. `idempotencyKey` fixes the key (the browser-data import); otherwise a retry reuses it. */
export async function createServerFolder(courseId: Id, name: string, idempotencyKey?: string): Promise<ServerFolder> {
  const path = `${BASE}/courses/${encodeURIComponent(courseId)}/folders`;
  const folder = idempotencyKey
    ? await apiSend<ServerFolder>("POST", path, { name }, { idempotencyKey })
    : await keyed(`folder:${courseId}`, name, (key) => apiSend<ServerFolder>("POST", path, { name }, { idempotencyKey: key }));
  void refreshCourseView(courseId);
  return folder;
}

export type PlacementKind = "course-files" | "recordings";

/** `PUT …/placements/{kind}/{itemId}` with `If-Match: "<version>"` ("0" for an item in the course root). */
export function putPlacement(kind: PlacementKind, itemId: Id, folderId: Id, version: number): Promise<ServerPlacement> {
  return apiSend<ServerPlacement>("PUT", `${BASE}/placements/${kind}/${encodeURIComponent(itemId)}`, { folderId: Number(folderId) }, { ifMatch: version });
}

export interface UploadInput {
  courseId: Id;
  blob: Blob;
  fileName: string;
  title: string;
  source: "mic" | "tab" | "audio";
  durationMs: number;
  recordedAt: string;
  folderId?: Id | null;
  idempotencyKey: string;
}

const ALLOWED: Record<string, true> = { webm: true, weba: true, ogg: true, opus: true, mp3: true, m4a: true, mp4: true, wav: true, aac: true, flac: true };

/** The multipart file name: the given name when its extension is one the server takes, else `recording.<ext>`. */
export function uploadName(fileName: string | null | undefined, extension: string): string {
  const name = String(fileName || "").trim();
  const ext = name.includes(".") ? name.split(".").pop()!.toLowerCase() : "";
  return name && ALLOWED[ext] ? name : `recording.${extension}`;
}

/** Title the server accepts: no control characters, 1–200 UTF-16 units, never ending in half of a surrogate pair. */
export function uploadTitle(title: string): string {
  // eslint-disable-next-line no-control-regex
  const clean = String(title || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim() || "녹음";
  if (clean.length <= 200) return clean;
  const last = clean.charCodeAt(199);
  return clean.slice(0, last >= 0xd800 && last <= 0xdbff ? 199 : 200);
}

/** `POST …/recordings` (multipart). 201 `Recording`; the folder placement happens in the same transaction. */
export async function uploadRecording(input: UploadInput): Promise<ServerRecording> {
  const form = new FormData();
  form.append("file", input.blob, input.fileName);
  form.append("title", uploadTitle(input.title));
  form.append("source", input.source);
  form.append("durationMs", String(Math.min(86400000, Math.max(1, Math.round(input.durationMs)))));
  form.append("recordedAt", input.recordedAt);
  if (input.folderId != null && input.folderId !== "") form.append("folderId", String(input.folderId));
  const recording = await apiUpload<ServerRecording>(`${BASE}/courses/${encodeURIComponent(input.courseId)}/recordings`, form, { idempotencyKey: input.idempotencyKey });
  void refreshCourseView(input.courseId);
  return recording;
}

export function getServerRecording(id: Id): Promise<ServerRecording> {
  return apiGet<ServerRecording>(`${BASE}/recordings/${encodeURIComponent(id)}`);
}

/** Every recording of mine across courses, with the usage. */
async function allServerRecordings(): Promise<{ recordings: ServerRecording[]; usage: StudyUsage | null }> {
  const body = await apiGet<{ recordings?: ServerRecording[]; usage?: StudyUsage | null } | null>(`${BASE}/recordings`);
  const usage = (body && body.usage) || null;
  if (usage) lastUsage = usage;
  return { recordings: list<ServerRecording>(body && body.recordings), usage };
}

/** `GET …/recordings` (every course, including courses I no longer take), keyed "all". */
export const myRecordings = createResource<{ recordings: ServerRecording[]; usage: StudyUsage | null }, []>(allServerRecordings);

/**
 * The signed-in user as the server sees it now (`GET /api/me`, never the cached connection state): another tab
 * may have signed out or in as someone else on the shared cookie. null when signed out.
 */
export async function signedInUserId(): Promise<string | null> {
  try {
    const me = await apiGet<{ userId?: number | string | null } | null>("/api/me");
    return me && me.userId != null ? String(me.userId) : null;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}

/** `DELETE …/recordings/{id}` with the version the screen read. */
export async function deleteServerRecording(recording: { id: Id; version: number; courseId: Id }): Promise<void> {
  await apiSend<null>("DELETE", `${BASE}/recordings/${encodeURIComponent(recording.id)}`, undefined, { ifMatch: recording.version });
  void refreshCourseView(recording.courseId);
  // The study-room home lists recordings of courses I no longer take from this listing.
  myRecordings.drop(() => true);
}

/** Playback and download address (owner-only, ranged; the session cookie authenticates it). */
export const contentUrl = (id: Id): string => `${BASE}/recordings/${encodeURIComponent(id)}/content`;

// ---------- messages ----------

const MB = 1024 * 1024;
/** "12.3MB", "1.5GB". */
export function formatBytes(bytes: number | null | undefined): string {
  const value = Number(bytes) || 0;
  if (value >= 1024 * MB) return `${(value / (1024 * MB)).toFixed(1).replace(/\.0$/, "")}GB`;
  if (value >= MB) return `${(value / MB).toFixed(1).replace(/\.0$/, "")}MB`;
  return value > 0 ? `${Math.max(1, Math.round(value / 1024))}KB` : "0KB";
}

/** "녹음 저장 공간 12MB / 2GB" for the screens that show usage. */
export function usageText(usage: StudyUsage | null | undefined): string {
  if (!usage) return "";
  return `녹음 저장 공간 ${formatBytes(usage.usedBytes)} / ${formatBytes(usage.quotaBytes)}`;
}

/** Korean message for a failed study-room request. */
export function studyRoomMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "학습실 요청을 처리하지 못했습니다.";
  const usage = lastUsage;
  switch (error.code) {
    case "name_conflict": return "같은 이름의 폴더가 이미 있습니다.";
    case "too_many_folders": return `한 과목에는 폴더를 ${usage ? `${usage.maxFoldersPerCourse}개` : "정해진 수"}까지 만들 수 있습니다. 쓰지 않는 폴더가 있는지 확인하세요.`;
    case "quota_exceeded": return `서버의 녹음 저장 공간${usage ? `(${formatBytes(usage.quotaBytes)})` : ""}이 부족합니다. 오래된 녹음을 지운 뒤 다시 저장하세요.`;
    case "too_many_recordings": return `녹음은 ${usage ? `${usage.maxRecordings}개` : "정해진 수"}까지 저장할 수 있습니다. 오래된 녹음을 지운 뒤 다시 저장하세요.`;
    case "too_large": return `녹음 파일이 너무 큽니다. 한 파일은 ${usage && usage.maxFileBytes ? formatBytes(usage.maxFileBytes) : "50MB"}까지 저장할 수 있습니다.`;
    case "version_conflict": return "다른 탭이나 기기에서 먼저 바뀌었습니다. 다시 불러왔습니다. 확인한 뒤 다시 시도하세요.";
    case "course_mismatch": return "다른 과목의 폴더에는 넣을 수 없습니다.";
    case "not_ready": return "바이러스 검사 중인 녹음입니다. 검사가 끝나면 재생할 수 있습니다.";
    case "content_rejected": return "검사에서 악성 코드가 발견된 파일입니다. 재생하거나 저장할 수 없습니다.";
    case "extension_not_allowed":
    case "invalid_name": return "저장할 수 없는 파일 형식입니다. MP3, M4A, WAV, OGG, WEBM, AAC, FLAC 파일을 쓰세요.";
    case "content_mismatch": return "파일 내용이 확장자와 맞지 않습니다. 다른 오디오 파일을 선택하세요.";
    case "empty_file": return "비어 있는 파일은 저장할 수 없습니다. 소리가 있는 오디오 파일을 선택하세요.";
    case "idempotency_conflict": return "같은 요청이 다른 내용으로 처리 중입니다. 화면을 새로 불러오세요.";
    case "idempotency_in_progress": return "같은 요청을 아직 처리하고 있습니다. 잠시 후 다시 시도하세요.";
    case "recording_deleted": return "이 녹음은 서버에서 이미 지웠습니다(다른 기기나 탭에서). 이 브라우저의 사본은 그대로 두었습니다. 필요 없으면 직접 지우세요.";
    case "folder_deleted": return "이 폴더는 서버에서 이미 지웠습니다(다른 기기나 탭에서). 이 브라우저의 폴더와 그 안의 항목은 그대로 두었습니다.";
    default: break;
  }
  switch (error.status) {
    case 400: return error.detail || "입력한 값을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "항목을 찾을 수 없습니다. 이미 지웠거나 이 과목의 수강생이 아닐 수 있습니다.";
    case 413: return `녹음 파일이 너무 큽니다. 한 파일은 ${usage && usage.maxFileBytes ? formatBytes(usage.maxFileBytes) : "50MB"}까지 저장할 수 있습니다.`;
    case 422: return "저장할 수 없는 오디오 파일입니다. 다른 파일을 선택하세요.";
    case 428: return "자료 버전을 확인하지 못했습니다. 화면을 새로 불러오세요.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "학습실 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
