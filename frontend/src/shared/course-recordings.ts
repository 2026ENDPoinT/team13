// Lecture audio recorded or imported by the student. Backend `ready`: stored on the server in the student's own
// study room (study-room-remote.ts) and played or downloaded from its content address. Otherwise kept only in this
// browser's IndexedDB. Recordings are never transcribed or sent to a model in either mode.
// Audio is never dropped: a finished recording is kept in IndexedDB until the server answered 201 for it, and when
// even IndexedDB refuses it the audio stays in memory and is offered as a download (`unsavedAudio`).
import {
  serverStudyCourse, studyRoomOnServer, loadCourseView, isMissingEndpoint, uploadRecording, uploadName, getServerRecording,
  deleteServerRecording, contentUrl, studyUsage, studyRoomMessage, formatBytes, putPlacement, refreshCourseView,
  signedInUserId, type ServerRecording, type RecordingState,
} from "./study-room-remote.ts";
import {
  placeLocally, localPlacements, localFolder, browserFolders, moveFolderToAccount, forgetMoved, leftByOthers,
} from "./study-folders.ts";
import { ApiError } from "./api.ts";
import { backendSettled, connect, currentUserId, kjOfCourseId, subscribe as onBackendChange } from "./backend.ts";
import { saveBlob } from "./uploads-remote.tsx";
import { toast } from "../store.ts";

const DATABASE = "astra-course-recordings";
const VERSION = 1;
const STORE = "recordings";
const MIME_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus"];
// 48 kbit/s Opus keeps a 75-minute lecture near 27 MB, well under the server's 50 MB file limit.
const AUDIO_BITS_PER_SECOND = 48000;
// Share of the server file limit at which the recorder stops and saves by itself.
const STOP_SHARE = 0.97;
/** Share of the server file limit at which the recorder warns (toast and recorder screen). */
export const WARN_SHARE = 0.85;

const ACCOUNT_CHANGED = "로그아웃했거나 다른 계정으로 바뀌어 녹음을 서버에 올리지 않고 이 브라우저에 보관했습니다. 녹음한 계정으로 다시 로그인한 뒤 ‘내 계정으로 옮기기’로 옮길 수 있습니다.";
const MOVE_STOPPED = "로그인한 계정이 바뀌었거나 로그아웃되어 옮기기를 멈췄습니다. 남은 항목은 이 브라우저에 그대로 있습니다.";

export type RecordingSource = "mic" | "tab" | "audio";

interface RecordingFields {
  id: string;
  courseId: string;
  title: string;
  source: RecordingSource;
  mimeType: string;
  size: number;
  durationMs: number;
  createdAt: string;
  fileName?: string;
}

/** A recording stored in IndexedDB (`recordings` store). */
export interface LocalRecording extends RecordingFields {
  remote?: undefined;
  blob: Blob;
  /** Why the last move to the account failed (shown with the retry control); the record stays until a 201. */
  uploadError?: string;
  /** The account signed in when it was recorded or imported while `ready` (none when made signed out). */
  ownerUserId?: string;
  /** The server folder it was recorded in; it goes there when it moves (the course root if that folder is gone). */
  pendingFolderId?: string;
}

/** A recording on the server. `id` is the server id as a string; `url` plays and downloads it. */
export interface RemoteRecording extends RecordingFields {
  remote: true;
  backendCourseId: number;
  state: RecordingState;
  version: number;
  url: string;
}

export type RecordingRecord = LocalRecording | RemoteRecording;

export type RecordingEvent =
  | { type: "change" }
  /** `warning`: saved, but kept in this browser (with the reason), or stopped at the size limit. */
  | { type: "saved"; record: RecordingRecord; warning?: string }
  | { type: "error"; error: unknown };

export interface ActiveRecording {
  courseId: string;
  source: RecordingSource;
  paused: boolean;
  elapsedMs: number;
  /** Bytes recorded so far. */
  bytes: number;
  /** The server's file limit when the recording goes to the server, else null. */
  limitBytes: number | null;
  /** True when it is saved only in this browser (recorded in a browser-only folder, or nobody signed in). */
  browserOnly: boolean;
}

/** Audio that neither IndexedDB nor the server took; kept in memory for this page and offered as a download. */
export interface UnsavedAudio {
  id: string;
  courseId: string;
  title: string;
  fileName: string;
  blob: Blob;
}

interface Session {
  courseId: string;
  source: RecordingSource;
  folderId: string | null;
  /** The signed-in user when it started (null signed out); only that account may receive it. */
  ownerUserId: string | null;
  /** Whether the backend connection had settled at the start (before that, the first answer sets the owner). */
  settled: boolean;
  recorder: MediaRecorder;
  stream: MediaStream;
  chunks: Blob[];
  bytes: number;
  limitBytes: number | null;
  limitReached: boolean;
  warned: boolean;
  /** Set when the account changed during the recording: it was stopped and stays in this browser. */
  accountChanged: boolean;
  startedAt: Date;
  pausedTotal: number;
  pausedAt: number;
  stoppedAt: number;
  done?: Promise<RecordingRecord | null>;
}

function errorName(error: unknown): string {
  return error && typeof error === "object" && "name" in error ? String(error.name) : "";
}

function storageError(error?: unknown): Error {
  if (errorName(error) === "QuotaExceededError") return new Error("브라우저 저장 공간이 부족합니다. 오래된 녹음을 지운 뒤 다시 저장하세요.");
  return new Error("이 브라우저에 녹음을 저장하거나 불러올 수 없습니다. 브라우저의 사이트 저장소 설정을 확인하세요.");
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(storageError());
      return;
    }
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DATABASE, VERSION);
    } catch (error) {
      reject(storageError(error));
      return;
    }
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE, { keyPath: "id" }).createIndex("courseId", "courseId");
    };
    request.onblocked = () => reject(new Error("다른 탭에서 녹음 저장소를 사용 중입니다. 다른 탭을 닫고 다시 시도하세요."));
    request.onerror = () => reject(storageError(request.error));
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => db.close();
      resolve(db);
    };
  });
}

async function withStore<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    try {
      const transaction = db.transaction(STORE, mode);
      const request = run(transaction.objectStore(STORE));
      transaction.oncomplete = () => {
        db.close();
        resolve(request.result);
      };
      transaction.onabort = () => {
        db.close();
        reject(storageError(transaction.error));
      };
    } catch (error) {
      db.close();
      reject(storageError(error));
    }
  });
}

// The store holds only LocalRecording rows (saveLocal).
const asRecords = (request: IDBRequest): IDBRequest<LocalRecording[]> => request as IDBRequest<LocalRecording[]>;
const newestFirst = (a: RecordingFields, b: RecordingFields) => b.createdAt.localeCompare(a.createdAt);

/** Every recording in this browser's IndexedDB, all courses. */
function listLocalRecordings(): Promise<LocalRecording[]> {
  return withStore("readonly", (store) => asRecords(store.getAll()));
}

async function localRecordingsOf(courseId: string): Promise<LocalRecording[]> {
  return withStore("readonly", (store) => asRecords(store.index("courseId").getAll(courseId)));
}

function deleteLocalRecording(id: string): Promise<undefined> {
  return withStore("readwrite", (store) => store.delete(id));
}

/**
 * Applies `change` to the IndexedDB record `id` only if it still exists, read and written in one transaction: another
 * tab may have moved it, or the user deleted it, while this one was uploading.
 */
async function updateLocalIfPresent(id: string, change: Partial<LocalRecording>): Promise<LocalRecording | null> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    let updated: LocalRecording | null = null;
    try {
      const transaction = db.transaction(STORE, "readwrite");
      const store = transaction.objectStore(STORE);
      const read = store.get(id) as IDBRequest<LocalRecording | undefined>;
      read.onsuccess = () => {
        if (!read.result) return;
        updated = { ...read.result, ...change };
        store.put(updated);
      };
      transaction.oncomplete = () => {
        db.close();
        resolve(updated);
      };
      transaction.onabort = () => {
        db.close();
        reject(storageError(transaction.error));
      };
    } catch (error) {
      db.close();
      reject(storageError(error));
    }
  });
}

/** A server recording in the screens' record shape; `kj` is the app course it is shown under ("" for a course I left). */
export function fromServer(recording: ServerRecording, kj: string): RemoteRecording {
  return {
    id: String(recording.id),
    remote: true,
    backendCourseId: recording.courseId,
    courseId: kj,
    title: recording.title,
    source: recording.source,
    mimeType: recording.mimeType,
    size: recording.sizeBytes,
    durationMs: recording.durationMs,
    createdAt: recording.createdAt,
    ...(recording.fileName ? { fileName: recording.fileName } : {}),
    state: recording.state,
    version: recording.version,
    url: contentUrl(recording.id),
  };
}

/**
 * Newest first. Server course: its server recordings, then every recording still only in this browser for the course
 * (screens set apart the ones `leftByOthers`), so each can be played, downloaded, moved or deleted.
 */
export async function listRecordings(courseId: string | null | undefined): Promise<RecordingRecord[]> {
  const kj = String(courseId || "");
  const server = serverStudyCourse(kj);
  if (server != null) {
    try {
      const view = await loadCourseView(server);
      const local = await localRecordingsOf(kj).catch((): LocalRecording[] => []);
      return [...view.recordings.map((recording) => fromServer(recording, kj)), ...local].sort(newestFirst);
    } catch (error) {
      if (!isMissingEndpoint(error)) throw error;
    }
  }
  return (await localRecordingsOf(kj)).sort(newestFirst);
}

const SERVER_ID = /^[1-9][0-9]*$/;

/**
 * A server id (digits) while the study room is on the server; otherwise an IndexedDB id. undefined when it is gone.
 * A server recording of a course I no longer take has `courseId` "".
 */
export async function getRecording(id: string | null | undefined): Promise<RecordingRecord | undefined> {
  const key = String(id || "");
  if (studyRoomOnServer() && SERVER_ID.test(key)) {
    try {
      const recording = await getServerRecording(key);
      return fromServer(recording, kjOfCourseId(recording.courseId) || "");
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return undefined;
      throw error;
    }
  }
  return withStore("readonly", (store) => store.get(key) as IDBRequest<LocalRecording | undefined>);
}

/** Deletes a recording where it is stored. Server: `If-Match` with the version read; errors carry the Korean message. */
export async function deleteRecording(record: RecordingRecord): Promise<void> {
  if (!record.remote) {
    await deleteLocalRecording(record.id);
    return;
  }
  try {
    await deleteServerRecording({ id: record.id, version: record.version, courseId: record.backendCourseId });
  } catch (error) {
    throw new ApiErrorMessage(error);
  }
}

/** An Error with the study-room sentence that keeps the server error (`cause`) for status checks. */
class ApiErrorMessage extends Error {
  constructor(error: unknown) {
    super(studyRoomMessage(error), { cause: error });
  }
}

function recordingTitle(date: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `녹음 ${p(date.getMonth() + 1)}.${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}`;
}

interface SaveInput {
  courseId: string;
  source: RecordingSource;
  blob: Blob;
  durationMs: number;
  startedAt: Date;
  title?: string;
  fileName?: string;
  folderId?: string | null;
  ownerUserId?: string | null;
  pendingFolderId?: string | null;
}

async function saveLocal({ courseId, source, blob, durationMs, startedAt, title, fileName, ownerUserId, pendingFolderId }: SaveInput): Promise<LocalRecording> {
  const record: LocalRecording = {
    id: crypto.randomUUID(),
    courseId,
    title: title || recordingTitle(startedAt),
    source,
    mimeType: blob.type || "audio/webm",
    size: blob.size,
    durationMs,
    createdAt: startedAt.toISOString(),
    blob,
    ...(fileName ? { fileName } : {}),
    ...(ownerUserId ? { ownerUserId } : {}),
    ...(pendingFolderId ? { pendingFolderId } : {}),
  };
  await withStore("readwrite", (store) => store.put(record));
  return record;
}

/** A recording start the server takes: never later than now (a local clock ahead of the server's is clamped). */
function recordedAt(createdAt: string): string {
  const time = Date.parse(createdAt);
  return new Date(Number.isFinite(time) ? Math.min(time, Date.now()) : Date.now()).toISOString();
}

const sleep = (ms: number) => new Promise<void>((resolve) => { window.setTimeout(resolve, ms); });

/**
 * Sends a new recording to the server (`folderId` places it in the same request). The key makes a retry safe;
 * `idempotency_in_progress` (the same key still being processed) is asked again shortly.
 */
async function saveRemote(server: number | string, input: SaveInput, idempotencyKey: string): Promise<RemoteRecording> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      const recording = await uploadRecording({
        courseId: server,
        blob: input.blob,
        fileName: uploadName(input.fileName, recordingExtension(input.blob.type || "audio/webm")),
        title: input.title || recordingTitle(input.startedAt),
        source: input.source,
        durationMs: input.durationMs,
        recordedAt: recordedAt(input.startedAt.toISOString()),
        folderId: input.folderId,
        idempotencyKey,
      });
      return fromServer(recording, input.courseId);
    } catch (error) {
      if (error instanceof ApiError && error.code === "idempotency_in_progress" && attempt < 4) {
        await sleep(1500);
        continue;
      }
      throw error;
    }
  }
}

/** Whether the server's signed-in user is still `userId` (asked now, not the cached connection). Resyncs on a change. */
async function stillSignedInAs(userId: string | null): Promise<boolean> {
  if (!userId) return false;
  let now: string | null;
  try {
    now = await signedInUserId();
  } catch {
    return false;
  }
  if (now === userId) return true;
  // Another tab signed out or in as someone else on the shared cookie: let the app follow.
  void connect();
  return false;
}

// ---------- audio no store took ----------

const unsaved: UnsavedAudio[] = [];
const unsavedListeners = new Set<() => void>();

/** Audio kept only in this page's memory (IndexedDB and the server both refused it). */
export const unsavedAudio = (): readonly UnsavedAudio[] => unsaved;

export function onUnsavedChange(listener: () => void): () => void {
  unsavedListeners.add(listener);
  return () => {
    unsavedListeners.delete(listener);
  };
}

/** Removes one in-memory recording after the user saved it as a file. */
export function forgetUnsaved(id: string): void {
  const index = unsaved.findIndex((item) => item.id === id);
  if (index >= 0) unsaved.splice(index, 1);
  if (!unsaved.length && !session) window.removeEventListener("beforeunload", warnUnload);
  for (const listener of [...unsavedListeners]) listener();
}

function keepInMemory(input: SaveInput): UnsavedAudio {
  const title = input.title || recordingTitle(input.startedAt);
  const item: UnsavedAudio = {
    id: crypto.randomUUID(), courseId: input.courseId, title, blob: input.blob,
    fileName: `${title.replace(/[\\/:*?"<>|]/g, "-")}.${recordingExtension(input.blob.type || "audio/webm")}`,
  };
  unsaved.push(item);
  // Leaving the page would lose it: the browser asks first, and the file is offered right away.
  window.addEventListener("beforeunload", warnUnload);
  saveBlob(item.blob, item.fileName);
  for (const listener of [...unsavedListeners]) listener();
  return item;
}

// ---------- moving browser recordings to the account ----------

export interface MoveResult {
  /** The server record after a 201, else the browser record (with its `uploadError`). */
  record: RecordingRecord;
  /** Why it stayed in this browser, or undefined when it moved. */
  error?: string;
  /** The signed-in account changed or the session ended: a batch must stop; the record's reason was not touched. */
  stop?: boolean;
}

interface MoveOptions {
  /** Browser folder id -> server folder id, shared by a batch. */
  folderIds?: Map<string, string>;
  /** The account the move was started for; checked against the server right before the upload. */
  userId?: string | null;
}

/**
 * Moves one browser recording to the signed-in account (an explicit action, or the automatic upload right after a
 * recording). A recording in a browser folder takes that folder along (`POST …/folders`, key `import-folder-<id>`);
 * one with `pendingFolderId` goes into that server folder if it still exists. The upload uses the key
 * `import-rec-<localId>`. The browser copy is deleted only after the server answered 201 (or replayed it); on a
 * refusal it stays with the reason in `uploadError`, written only if the record still exists.
 */
export async function moveRecordingToAccount(record: LocalRecording, { folderIds = new Map(), userId = currentUserId() }: MoveOptions = {}): Promise<MoveResult> {
  const keep = async (error: unknown): Promise<MoveResult> => {
    const message = studyRoomMessage(error);
    const kept = await updateLocalIfPresent(record.id, { uploadError: message }).catch(() => null);
    return { record: kept || { ...record, uploadError: message }, error: message };
  };
  if (!(await stillSignedInAs(userId))) return { record, error: MOVE_STOPPED, stop: true };
  const server = serverStudyCourse(record.courseId);
  if (server == null) return keep(new Error("이 과목은 ASTRA 서버에 없어 옮길 수 없습니다."));
  const browserFolder = localFolder(localPlacements()[record.id] || "");
  let folderId: string | null = null;
  try {
    if (browserFolder) {
      // The server folder must exist before the upload names it (the key's fingerprint covers folderId).
      folderId = folderIds.get(browserFolder.id) ?? await moveFolderToAccount(browserFolder);
      folderIds.set(browserFolder.id, folderId);
    } else if (record.pendingFolderId) {
      const view = await loadCourseView(server, true);
      folderId = view.folders.some((folder) => String(folder.id) === record.pendingFolderId) ? record.pendingFolderId : null;
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return { record, error: MOVE_STOPPED, stop: true };
    return keep(error);
  }
  const input: SaveInput = {
    courseId: record.courseId, source: record.source, blob: record.blob, durationMs: record.durationMs,
    startedAt: new Date(recordedAt(record.createdAt)), title: record.title, fileName: record.fileName, folderId,
  };
  let moved: RemoteRecording;
  try {
    moved = await saveRemote(server, input, `import-rec-${record.id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return { record, error: MOVE_STOPPED, stop: true };
    return keep(error);
  }
  try {
    await deleteLocalRecording(record.id);
  } catch {
    // Still in IndexedDB: a later move sends the same key and the server answers with the same recording.
  }
  forgetMoved([record.id], browserFolder ? [browserFolder.id] : []);
  return { record: moved };
}

/**
 * Moves this browser's items of the account's server courses (only `kj` when given): `others` false takes the
 * signed-in user's own items, true the ones `leftByOthers` (the user confirmed they are theirs). Browser folders go
 * in their order, then their recordings, then course PDFs placed in them. The server user is checked before every
 * step; on a change or a 401 the run stops and leaves the rest as it is. Resolves a summary sentence.
 */
export async function moveAllToAccount(kj: string | undefined, others: boolean): Promise<string> {
  const userId = currentUserId();
  const mine = (courseId: string, owner: string | undefined) => (kj == null || courseId === kj) && serverStudyCourse(courseId) != null && leftByOthers(owner) === others;
  const folderIds = new Map<string, string>();
  const movedFolders: string[] = [];
  const folderErrors: string[] = [];
  let stopped = false;
  const placed = localPlacements();
  for (const folder of browserFolders().filter((item) => mine(item.courseId, item.ownerUserId))) {
    if (!(await stillSignedInAs(userId))) { stopped = true; break; }
    try {
      folderIds.set(folder.id, await moveFolderToAccount(folder));
      movedFolders.push(folder.id);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) { stopped = true; break; }
      folderErrors.push(studyRoomMessage(error));
    }
  }
  const records = stopped ? [] : (await listLocalRecordings()).filter((record) => mine(record.courseId, record.ownerUserId))
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  let moved = 0;
  const errors: string[] = [];
  for (const record of records) {
    const result = await moveRecordingToAccount(record, { folderIds, userId });
    if (result.stop) { stopped = true; break; }
    if (result.error) errors.push(result.error);
    else moved += 1;
  }
  const handled: string[] = [];
  for (const [itemId, folderId] of Object.entries(placed)) {
    const file = /^srv-([1-9][0-9]*)$/.exec(itemId);
    const target = folderIds.get(folderId);
    const folder = localFolder(folderId);
    if (stopped || !file || !target || !folder) continue;
    if (!(await stillSignedInAs(userId))) { stopped = true; break; }
    try {
      await putPlacement("course-files", file[1], target, 0);
      handled.push(itemId);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) { stopped = true; break; }
      // 409: placed already — the browser placement goes only when the server shows it in that same folder.
      if (error instanceof ApiError && error.status === 409) {
        const server = serverStudyCourse(folder.courseId);
        const view = server != null ? await loadCourseView(server, true).catch(() => null) : null;
        if (view && view.placements.some((item) => item.kind === "course_file" && String(item.itemId) === file[1] && String(item.folderId) === target)) handled.push(itemId);
      }
    }
  }
  forgetMoved(handled, movedFolders);
  for (const courseId of new Set([...records.map((record) => record.courseId), ...browserFolders().map((folder) => folder.courseId)])) {
    const server = serverStudyCourse(courseId);
    if (server != null) void refreshCourseView(server);
  }
  const done = [movedFolders.length && `폴더 ${movedFolders.length}개`, moved && `녹음 ${moved}개`].filter(Boolean).join("와 ");
  const notes = [done ? `${done}를 내 계정으로 옮겼습니다.` : ""];
  if (folderErrors.length) notes.push(`폴더 ${folderErrors.length}개는 옮기지 못했습니다. ${[...new Set(folderErrors)].join(" ")}`);
  if (errors.length) notes.push(`녹음 ${errors.length}개는 이 브라우저에 남겨 두었습니다. ${[...new Set(errors)].join(" ")}`);
  if (stopped) notes.push(MOVE_STOPPED);
  return notes.filter(Boolean).join(" ") || "옮길 항목이 없습니다.";
}

export function recordingExtension(mimeType: string): string {
  if (/mpeg|mp3/.test(mimeType)) return "mp3";
  if (/mp4|m4a/.test(mimeType)) return "m4a";
  if (/wav/.test(mimeType)) return "wav";
  if (/ogg/.test(mimeType)) return "ogg";
  if (/aac/.test(mimeType)) return "aac";
  if (/flac/.test(mimeType)) return "flac";
  return "webm";
}

export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const p = (n: number) => String(n).padStart(2, "0");
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return h ? `${h}:${p(m)}:${p(total % 60)}` : `${p(m)}:${p(total % 60)}`;
}

function captureError(error: unknown, source: RecordingSource): Error {
  const name = errorName(error);
  if (source === "tab") {
    if (name === "NotAllowedError" || name === "AbortError") return new Error("탭 공유를 취소했거나 권한이 거부되었습니다. 다시 시작해 녹음할 탭을 고르세요.");
    if (name === "NotSupportedError" || name === "TypeError") return new Error("이 브라우저는 탭 소리 녹음을 지원하지 않습니다. 데스크톱 Chrome 또는 Edge에서 열어 주세요.");
  } else {
    if (name === "NotAllowedError" || name === "SecurityError") return new Error("마이크 권한이 거부되었습니다. 주소창의 사이트 설정에서 마이크를 허용한 뒤 다시 시도하세요.");
    if (name === "NotFoundError" || name === "OverconstrainedError") return new Error("연결된 마이크를 찾지 못했습니다. 마이크를 연결한 뒤 다시 시도하세요.");
    if (name === "NotReadableError") return new Error("다른 앱이 마이크를 쓰고 있습니다. 그 앱을 닫고 다시 시도하세요.");
  }
  return new Error("녹음을 시작하지 못했습니다. 다시 시도해 주세요.");
}

let session: Session | null = null;
const listeners = new Set<(event: RecordingEvent) => void>();

// Whatever screen is open (a student follows the lecture in the PDF viewer), the app's toast tells what happened to
// the recording: a warning near the size limit, a stop, a save that stayed in this browser, a failure.
function emit(event: RecordingEvent): void {
  if (event.type === "saved" && event.warning) toast(event.warning);
  if (event.type === "error") toast(event.error instanceof Error ? event.error.message : String(event.error));
  for (const listener of listeners) listener(event);
}

/** Listen for "change" (start, pause, resume), "saved" ({ record, warning }) and "error" ({ error }) events. */
export function onRecordingEvent(listener: (event: RecordingEvent) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// Signing out, or another account signing in (here or in another tab, seen when the connection is checked), stops
// the recording; it is kept in this browser for the account that started it.
onBackendChange((next) => {
  const current = session;
  if (!current) return;
  const user = next.status === "ready" && next.me ? String(next.me.userId) : null;
  if (user === current.ownerUserId) return;
  if (!current.settled) {
    // Started before the first connection answer: that answer tells whose recording it is.
    current.settled = next.settled;
    current.ownerUserId = user;
    return;
  }
  current.accountChanged = true;
  if (current.recorder.state !== "inactive") {
    current.stoppedAt = Date.now();
    current.recorder.stop();
  }
});

/** A stable `Idempotency-Key` for importing this file as this user: picking the same file again after a lost answer replays. */
async function importKey(file: File, userId: string): Promise<string> {
  const text = new TextEncoder().encode(`${userId}|${file.name}|${file.size}|${file.lastModified}`);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", text));
  return `import-file-${[...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("").slice(0, 48)}`;
}

/** Import a local audio file into the study room (server while `ready`, else this browser). */
export async function importRecording({ courseId, file }: { courseId: string; file: File | null | undefined }): Promise<RecordingRecord> {
  const types: Record<string, string> = { mp3: "audio/mpeg", m4a: "audio/mp4", mp4: "audio/mp4", wav: "audio/wav", ogg: "audio/ogg", opus: "audio/ogg", webm: "audio/webm", weba: "audio/webm", aac: "audio/aac", flac: "audio/flac" };
  const extension = String(file?.name || "").split(".").pop()?.toLowerCase() || "";
  if (!file?.size) throw new Error("비어 있는 파일은 저장할 수 없습니다. 소리가 있는 오디오 파일을 선택하세요.");
  if (file.type.startsWith("video/") || (!file.type.startsWith("audio/") && !types[extension])) {
    throw new Error("오디오 파일을 선택하세요. MP3, M4A, WAV 등의 녹음 파일을 사용할 수 있습니다.");
  }
  const server = serverStudyCourse(courseId);
  const limit = server != null ? studyUsage()?.maxFileBytes : null;
  if (limit && file.size > limit) throw new Error(`녹음 파일이 너무 큽니다. 한 파일은 ${formatBytes(limit)}까지 저장할 수 있습니다.`);
  const blob = file.type.startsWith("audio/") ? file : file.slice(0, file.size, types[extension]);
  const durationMs = await new Promise<number>((resolve, reject) => {
    const audio = document.createElement("audio");
    const url = URL.createObjectURL(blob);
    const cleanup = () => {
      window.clearTimeout(timeout);
      audio.onerror = null;
      audio.onloadedmetadata = null;
      audio.ondurationchange = null;
      audio.removeAttribute("src");
      audio.load();
      URL.revokeObjectURL(url);
    };
    const fail = () => {
      cleanup();
      reject(new Error("이 브라우저에서 오디오 파일을 읽을 수 없습니다. 재생 가능한 MP3 또는 M4A 파일을 선택하세요."));
    };
    const timeout = window.setTimeout(fail, 15000);
    audio.preload = "metadata";
    audio.onerror = fail;
    audio.onloadedmetadata = () => {
      const finishDuration = () => {
        const duration = audio.duration;
        if (!Number.isFinite(duration) || duration <= 0) return;
        cleanup();
        resolve(duration * 1000);
      };
      if (Number.isFinite(audio.duration)) {
        if (audio.duration <= 0) {
          fail();
          return;
        }
        finishDuration();
      } else {
        // Browser-made WebM files often need a seek before their duration is known.
        audio.ondurationchange = finishDuration;
        audio.currentTime = Number.MAX_SAFE_INTEGER;
      }
    };
    audio.src = url;
  });
  const userId = currentUserId();
  const input: SaveInput = {
    // The file's own time, so picking the same file again sends the same request (the key covers recordedAt).
    courseId, source: "audio", blob, durationMs, startedAt: new Date(Math.min(file.lastModified || Date.now(), Date.now())),
    title: file.name.replace(/\.[^.]+$/, "") || file.name,
    fileName: file.name,
    ownerUserId: userId,
  };
  let record: RecordingRecord;
  if (server != null && userId) {
    try {
      record = await saveRemote(server, input, await importKey(file, userId));
    } catch (error) {
      // The user still has the file: report and let them choose another or free space.
      throw new ApiErrorMessage(error);
    }
  } else {
    record = await saveLocal(input);
  }
  emit({ type: "saved", record });
  return record;
}

function elapsed(s: Session, now: number = Date.now()): number {
  return now - s.startedAt.getTime() - s.pausedTotal - (s.pausedAt ? now - s.pausedAt : 0);
}

/** True when a recording started now in `courseId` (inside `folderId`) is saved only in this browser. */
export function recordsInBrowserOnly(courseId: string, folderId: string | null | undefined): boolean {
  return serverStudyCourse(courseId) == null || Boolean(folderId && localFolder(folderId));
}

export function activeRecording(): ActiveRecording | null {
  if (!session) return null;
  return {
    courseId: session.courseId, source: session.source, paused: Boolean(session.pausedAt), elapsedMs: elapsed(session),
    bytes: session.bytes, limitBytes: session.limitBytes, browserOnly: recordsInBrowserOnly(session.courseId, session.folderId),
  };
}

function warnUnload(event: BeforeUnloadEvent): void {
  event.preventDefault();
  event.returnValue = "";
}

/**
 * Must be called from a click handler: browsers only open the mic or tab picker for a user gesture.
 * `folderId`: the open folder the recording is saved into. A browser-only folder keeps it browser-only.
 */
export async function startRecording({ courseId, source, folderId = null }: { courseId: string; source: "mic" | "tab"; folderId?: string | null }): Promise<void> {
  if (session) throw new Error("이미 녹음 중입니다. 지금 녹음을 저장한 뒤 새로 시작하세요.");
  const devices = navigator.mediaDevices;
  if (!devices || typeof MediaRecorder === "undefined" || (source === "tab" ? !devices.getDisplayMedia : !devices.getUserMedia)) {
    throw captureError({ name: "NotSupportedError" }, source === "tab" ? "tab" : "mic");
  }
  // Chrome-only picker hints, not yet in lib.dom.
  const display: DisplayMediaStreamOptions & { selfBrowserSurface: string; systemAudio: string } = { video: true, audio: true, selfBrowserSurface: "exclude", systemAudio: "include" };
  let stream: MediaStream;
  try {
    stream = source === "tab"
      // Chrome only offers tab audio together with video; the video track is never recorded.
      ? await devices.getDisplayMedia(display)
      : await devices.getUserMedia({ audio: true });
  } catch (error) {
    throw captureError(error, source);
  }
  const audio = stream.getAudioTracks();
  if (!audio.length) {
    stream.getTracks().forEach((track) => track.stop());
    throw new Error("공유한 탭의 소리가 없습니다. 탭을 고르는 창에서 '탭 오디오도 공유'를 켠 뒤 다시 시작하세요.");
  }
  const mimeType = MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type));
  const recorder = new MediaRecorder(new MediaStream(audio), mimeType ? { mimeType, audioBitsPerSecond: AUDIO_BITS_PER_SECOND } : { audioBitsPerSecond: AUDIO_BITS_PER_SECOND });
  const chunks: Blob[] = [];
  const server = recordsInBrowserOnly(courseId, folderId) ? null : serverStudyCourse(courseId);
  const current: Session = {
    courseId, source, folderId, ownerUserId: currentUserId(), settled: backendSettled(), recorder, stream, chunks, bytes: 0,
    limitBytes: server != null ? studyUsage()?.maxFileBytes ?? null : null, limitReached: false, warned: false, accountChanged: false,
    startedAt: new Date(), pausedTotal: 0, pausedAt: 0, stoppedAt: 0,
  };
  // The file limit is known once this course's study room was read; read it now when it was not (the mic is already open).
  if (server != null && current.limitBytes == null) {
    loadCourseView(server).then((view) => {
      current.limitBytes = view.usage ? view.usage.maxFileBytes : null;
    }, () => {});
  }
  recorder.addEventListener("dataavailable", (event) => {
    if (!event.data.size) return;
    chunks.push(event.data);
    current.bytes += event.data.size;
    if (!current.limitBytes) return;
    if (!current.warned && current.bytes >= current.limitBytes * WARN_SHARE) {
      current.warned = true;
      toast(`녹음 파일이 서버 한도(${formatBytes(current.limitBytes)})에 가까워졌습니다. 한도에 닿으면 녹음을 멈추고 저장합니다.`);
    }
    // Stop and save before the file would pass the server's limit instead of failing after the lecture.
    if (current.bytes >= current.limitBytes * STOP_SHARE && recorder.state !== "inactive") {
      current.limitReached = true;
      current.stoppedAt = Date.now();
      recorder.stop();
    }
  });
  const done = new Promise<RecordingRecord | null>((resolve, reject) => {
    // Fires for an explicit stop and when the recorder stops itself because its tracks ended.
    recorder.addEventListener("stop", () => finish(current).then(resolve, reject), { once: true });
  });
  current.done = done;
  done.catch((error: unknown) => emit({ type: "error", error }));
  session = current;
  // Leaving the page would drop the audio, so the browser asks first until the file is stored.
  window.addEventListener("beforeunload", warnUnload);
  // Ending the share from the browser's own bar, or unplugging the mic, saves what was recorded.
  for (const track of stream.getTracks()) {
    track.addEventListener("ended", () => {
      if (recorder.state !== "inactive") recorder.stop();
    });
  }
  recorder.start(1000);
  emit({ type: "change" });
}

/**
 * Stores a finished recording: IndexedDB first, then (study room on the server, recorded by the account still signed
 * in, not in a browser-only folder) the upload, which deletes the browser copy after 201. If IndexedDB refuses it the
 * in-memory audio is uploaded with its own stable key; if that fails too it stays in memory and is offered as a file.
 * Resolves the stored record, or null when only the in-memory copy is left.
 */
async function finish(current: Session): Promise<RecordingRecord | null> {
  const durationMs = elapsed(current, current.stoppedAt || Date.now());
  current.stream.getTracks().forEach((track) => track.stop());
  if (session === current) session = null;
  try {
    const blob = new Blob(current.chunks, { type: current.recorder.mimeType || current.chunks[0]?.type || "audio/webm" });
    if (!blob.size) throw new Error("녹음된 소리가 없어 저장하지 않았습니다.");
    const notes: string[] = [];
    if (current.limitReached && current.limitBytes) notes.push(`녹음 파일이 서버 한도(${formatBytes(current.limitBytes)})에 가까워져 녹음을 멈추고 저장했습니다. 이어서 녹음하려면 새로 시작하세요.`);
    if (current.accountChanged) notes.push(ACCOUNT_CHANGED);
    const browserFolder = current.folderId ? localFolder(current.folderId) : null;
    const server = serverStudyCourse(current.courseId);
    const owner = current.ownerUserId;
    const upload = server != null && owner != null && !browserFolder && !current.accountChanged;
    const input: SaveInput = {
      courseId: current.courseId, source: current.source, blob, durationMs, startedAt: current.startedAt,
      // Any folder that is not a browser folder is a server folder (even if the session ended meanwhile): it is kept
      // on the record, never in the browser placement map.
      ownerUserId: owner, pendingFolderId: !browserFolder ? current.folderId : null,
    };
    let local: LocalRecording | null = null;
    let storeError: unknown = null;
    try {
      local = await saveLocal(input);
      // A browser folder (every folder in offline mode) keeps its recording in this browser.
      if (browserFolder) placeLocally(local.id, browserFolder.id);
    } catch (error) {
      storeError = error;
    }
    if (local && !upload) {
      emit({ type: "saved", record: local, ...(notes.length ? { warning: notes.join(" ") } : {}) });
      return local;
    }
    if (upload && !(await stillSignedInAs(owner))) {
      if (local) {
        emit({ type: "saved", record: local, warning: [...notes, ACCOUNT_CHANGED].join(" ") });
        return local;
      }
    } else if (upload && local) {
      const moved = await moveRecordingToAccount(local, { userId: owner });
      if (moved.error) notes.push(`서버에 저장하지 못해 이 브라우저에 보관했습니다. ${moved.error}`);
      emit({ type: "saved", record: moved.record, ...(notes.length ? { warning: notes.join(" ") } : {}) });
      return moved.record;
    } else if (upload) {
      // IndexedDB refused it (e.g. QuotaExceededError): send the audio from memory with a key fixed for it.
      try {
        const record = await saveRemote(server!, { ...input, folderId: current.folderId }, `rec-${crypto.randomUUID()}`);
        emit({ type: "saved", record, ...(notes.length ? { warning: notes.join(" ") } : {}) });
        return record;
      } catch (error) {
        storeError = new ApiErrorMessage(error);
      }
    }
    const kept = keepInMemory(input);
    const reason = storeError instanceof Error ? storeError.message : "";
    emit({ type: "error", error: new Error(`녹음을 저장할 곳이 없어 "${kept.fileName}" 파일로 내려받게 했습니다. 이 페이지를 닫기 전에 파일이 저장됐는지 확인하세요. ${reason}`.trim()) });
    return null;
  } finally {
    if (!unsaved.length) window.removeEventListener("beforeunload", warnUnload);
  }
}

export function pauseRecording(): void {
  if (!session || session.pausedAt || session.recorder.state !== "recording") return;
  session.recorder.pause();
  session.pausedAt = Date.now();
  emit({ type: "change" });
}

export function resumeRecording(): void {
  if (!session || !session.pausedAt) return;
  session.recorder.resume();
  session.pausedTotal += Date.now() - session.pausedAt;
  session.pausedAt = 0;
  emit({ type: "change" });
}

export function stopRecording(): Promise<RecordingRecord | null> {
  const current = session;
  if (!current || !current.done) return Promise.reject(new Error("진행 중인 녹음이 없습니다."));
  if (current.recorder.state !== "inactive") {
    current.stoppedAt = Date.now();
    current.recorder.stop();
  }
  return current.done;
}
