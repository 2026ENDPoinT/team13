// Lecture audio recorded or imported by the student. Backend `ready`: stored on the server in the student's own
// study room (study-room-remote.ts) and played or downloaded from its content address. Otherwise kept only in this
// browser's IndexedDB. Recordings are never transcribed or sent to a model in either mode.
import {
  serverStudyCourse, studyRoomOnServer, loadCourseView, isMissingEndpoint, uploadRecording, uploadName, getServerRecording,
  deleteServerRecording, contentUrl, studyUsage, studyRoomMessage, formatBytes, putPlacement, refreshCourseView,
  type ServerRecording, type RecordingState,
} from "./study-room-remote.ts";
import {
  placeLocally, localPlacements, localFolder, browserFolders, moveFolderToAccount, forgetMoved,
} from "./study-folders.ts";
import { ApiError } from "./api.ts";
import { kjOfCourseId } from "./backend.ts";

const DATABASE = "astra-course-recordings";
const VERSION = 1;
const STORE = "recordings";
const MIME_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus"];
// 48 kbit/s Opus keeps a 75-minute lecture near 27 MB, well under the server's 50 MB file limit.
const AUDIO_BITS_PER_SECOND = 48000;
// Share of the server file limit at which the recorder stops and saves by itself (the screen warns earlier).
const STOP_SHARE = 0.97;
/** Share of the server file limit at which the recorder screen starts warning. */
export const WARN_SHARE = 0.85;

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
  /** `warning`: saved, but kept in this browser because the server refused it, or stopped at the size limit. */
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
}

interface Session {
  courseId: string;
  source: RecordingSource;
  folderId: string | null;
  recorder: MediaRecorder;
  stream: MediaStream;
  chunks: Blob[];
  bytes: number;
  limitBytes: number | null;
  limitReached: boolean;
  startedAt: Date;
  pausedTotal: number;
  pausedAt: number;
  stoppedAt: number;
  done?: Promise<RecordingRecord>;
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

function putLocalRecording(record: LocalRecording): Promise<IDBValidKey> {
  return withStore("readwrite", (store) => store.put(record));
}

function deleteLocalRecording(id: string): Promise<undefined> {
  return withStore("readwrite", (store) => store.delete(id));
}

/** A server recording in the screens' record shape; `kj` is the app course it is shown under. */
function fromServer(recording: ServerRecording, kj: string): RemoteRecording {
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
 * Newest first. Server course: its server recordings, then any recording still only in this browser for the course
 * (one the server refused during the import, or a save that fell back to the browser) so it can be played,
 * downloaded or deleted.
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

/** A server id (digits) while the study room is on the server; otherwise an IndexedDB id. undefined when it is gone. */
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
}

async function saveLocal({ courseId, source, blob, durationMs, startedAt, title, fileName }: SaveInput): Promise<LocalRecording> {
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
  };
  await withStore("readwrite", (store) => store.put(record));
  return record;
}

/** A recording start the server takes: never later than now (a local clock ahead of the server's is clamped). */
function recordedAt(createdAt: string): string {
  const time = Date.parse(createdAt);
  return new Date(Number.isFinite(time) ? Math.min(time, Date.now()) : Date.now()).toISOString();
}

/** Sends a new recording to the server (`folderId` places it in the same request). The key makes a retry safe. */
async function saveRemote(server: number | string, input: SaveInput, idempotencyKey: string): Promise<RemoteRecording> {
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
}

// ---------- moving browser recordings to the account ----------

const sleep = (ms: number) => new Promise<void>((resolve) => { window.setTimeout(resolve, ms); });

export interface MoveResult {
  /** The server record after a 201, else the browser record (with its `uploadError`). */
  record: RecordingRecord;
  /** Why it stayed in this browser, or undefined when it moved. */
  error?: string;
}

/**
 * Moves one browser recording to the signed-in account: its browser folder first (when it sits in one), then
 * `POST …/recordings` with `Idempotency-Key: import-rec-<localId>`; `folderIds` (browser folder id -> server id) is
 * shared by a batch. The browser copy is deleted only after the server answered 201 (or replayed it); on any
 * failure it stays with the reason in `uploadError`.
 */
export async function moveRecordingToAccount(record: LocalRecording, folderIds: Map<string, string> = new Map()): Promise<MoveResult> {
  const server = serverStudyCourse(record.courseId);
  const keep = async (error: unknown): Promise<MoveResult> => {
    const message = error instanceof ApiError && (error.status === 410 || error.code === "recording_deleted")
      ? "서버에서 이미 지운 녹음입니다. 필요 없으면 이 브라우저의 사본도 지우세요."
      : studyRoomMessage(error);
    const kept: LocalRecording = { ...record, uploadError: message };
    await putLocalRecording(kept).catch(() => {});
    return { record: kept, error: message };
  };
  if (server == null) return keep(new Error("이 과목은 ASTRA 서버에 없어 옮길 수 없습니다."));
  const placedIn = localPlacements()[record.id] || "";
  // A browser folder is moved first; a server folder id is used as it is; a folder that no longer exists is ignored.
  let folderId: string | null = /^[1-9][0-9]*$/.test(placedIn) ? placedIn : null;
  const browserFolder = placedIn ? localFolder(placedIn) : null;
  if (browserFolder) {
    // The server folder must exist before the upload names it (the key's fingerprint covers folderId).
    try {
      folderId = folderIds.get(browserFolder.id) ?? await moveFolderToAccount(browserFolder);
      folderIds.set(browserFolder.id, folderId);
    } catch (error) {
      return keep(error);
    }
  }
  const input: SaveInput = {
    courseId: record.courseId, source: record.source, blob: record.blob, durationMs: record.durationMs,
    startedAt: new Date(recordedAt(record.createdAt)), title: record.title, fileName: record.fileName, folderId,
  };
  let moved: RemoteRecording | null = null;
  for (let attempt = 0; !moved; attempt += 1) {
    try {
      moved = await saveRemote(server, input, `import-rec-${record.id}`);
    } catch (error) {
      // The same key is still being processed (another tab, or an earlier try): ask again shortly.
      if (error instanceof ApiError && error.code === "idempotency_in_progress" && attempt < 4) {
        await sleep(1500);
        continue;
      }
      return keep(error);
    }
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
 * Moves everything of this browser that belongs to the server courses of the account (only `kj` when given):
 * browser folders in their order, their recordings, and course PDFs placed in them. Folders of courses the
 * server does not know, browser-only PDFs and anything that failed stay here. Resolves a summary sentence.
 */
export async function moveAllToAccount(kj?: string): Promise<string> {
  const mine = (courseId: string) => (kj == null || courseId === kj) && serverStudyCourse(courseId) != null;
  const folderIds = new Map<string, string>();
  const movedFolders: string[] = [];
  let folderErrors = 0;
  let folderError = "";
  const placed = localPlacements();
  for (const folder of browserFolders().filter((item) => mine(item.courseId))) {
    try {
      folderIds.set(folder.id, await moveFolderToAccount(folder));
      movedFolders.push(folder.id);
    } catch (error) {
      folderErrors += 1;
      folderError = studyRoomMessage(error);
    }
  }
  const records = (await listLocalRecordings()).filter((record) => mine(record.courseId)).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  let moved = 0;
  const errors: string[] = [];
  for (const record of records) {
    const result = await moveRecordingToAccount(record, folderIds);
    if (result.error) errors.push(result.error);
    else moved += 1;
  }
  const handled: string[] = [];
  for (const [itemId, folderId] of Object.entries(placed)) {
    const file = /^srv-([1-9][0-9]*)$/.exec(itemId);
    const target = folderIds.get(folderId);
    if (!file || !target) continue;
    try {
      await putPlacement("course-files", file[1], target, 0);
      handled.push(itemId);
    } catch (error) {
      // 404 (file gone or no longer readable) and 409 (already placed): nothing to move.
      if (error instanceof ApiError && (error.status === 404 || error.status === 409)) handled.push(itemId);
    }
  }
  forgetMoved(handled, movedFolders);
  for (const view of new Set(records.map((record) => record.courseId))) {
    const server = serverStudyCourse(view);
    if (server != null) void refreshCourseView(server);
  }
  const done = [movedFolders.length && `폴더 ${movedFolders.length}개`, moved && `녹음 ${moved}개`].filter(Boolean).join("와 ");
  const notes = [done ? `${done}를 내 계정으로 옮겼습니다.` : ""];
  if (folderErrors) notes.push(`폴더 ${folderErrors}개는 옮기지 못했습니다. ${folderError}`);
  if (errors.length) notes.push(`녹음 ${errors.length}개는 이 브라우저에 남겨 두었습니다. ${[...new Set(errors)].join(" ")}`);
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

function emit(event: RecordingEvent): void {
  for (const listener of listeners) listener(event);
}

/** Listen for "change" (start, pause, resume), "saved" ({ record, warning }) and "error" ({ error }) events. */
export function onRecordingEvent(listener: (event: RecordingEvent) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Import a local audio file into the study room (server while `ready`, else this browser). */
export async function importRecording({ courseId, file }: { courseId: string; file: File | null | undefined }): Promise<RecordingRecord> {
  const types: Record<string, string> = { mp3: "audio/mpeg", m4a: "audio/mp4", mp4: "audio/mp4", wav: "audio/wav", ogg: "audio/ogg", webm: "audio/webm", aac: "audio/aac", flac: "audio/flac" };
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
  const input: SaveInput = {
    courseId, source: "audio", blob, durationMs, startedAt: new Date(),
    title: file.name.replace(/\.[^.]+$/, "") || file.name,
    fileName: file.name,
  };
  let record: RecordingRecord;
  if (server != null) {
    try {
      record = await saveRemote(server, input, crypto.randomUUID());
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

export function activeRecording(): ActiveRecording | null {
  if (!session) return null;
  return {
    courseId: session.courseId, source: session.source, paused: Boolean(session.pausedAt), elapsedMs: elapsed(session),
    bytes: session.bytes, limitBytes: session.limitBytes,
  };
}

function warnUnload(event: BeforeUnloadEvent): void {
  event.preventDefault();
  event.returnValue = "";
}

/**
 * Must be called from a click handler: browsers only open the mic or tab picker for a user gesture.
 * `folderId`: the open folder the recording is saved into.
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
  const server = serverStudyCourse(courseId);
  const current: Session = {
    courseId, source, folderId, recorder, stream, chunks, bytes: 0, limitBytes: server != null ? studyUsage()?.maxFileBytes ?? null : null, limitReached: false,
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
    // Stop and save before the file would pass the server's limit instead of failing after the lecture.
    if (current.limitBytes && current.bytes >= current.limitBytes * STOP_SHARE && recorder.state !== "inactive") {
      current.limitReached = true;
      current.stoppedAt = Date.now();
      recorder.stop();
    }
  });
  const done = new Promise<RecordingRecord>((resolve, reject) => {
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

async function finish(current: Session): Promise<RecordingRecord> {
  const durationMs = elapsed(current, current.stoppedAt || Date.now());
  current.stream.getTracks().forEach((track) => track.stop());
  if (session === current) session = null;
  try {
    const blob = new Blob(current.chunks, { type: current.recorder.mimeType || current.chunks[0]?.type || "audio/webm" });
    if (!blob.size) throw new Error("녹음된 소리가 없어 저장하지 않았습니다.");
    const limitNote = current.limitReached && current.limitBytes
      ? `녹음 파일이 서버 한도(${formatBytes(current.limitBytes)})에 가까워져 녹음을 멈추고 저장했습니다. 이어서 녹음하려면 새로 시작하세요.`
      : "";
    // Always stored in this browser first: on the server it goes only after the server answered 201.
    const local = await saveLocal({ courseId: current.courseId, source: current.source, blob, durationMs, startedAt: current.startedAt });
    if (current.folderId) placeLocally(local.id, current.folderId);
    if (serverStudyCourse(current.courseId) == null) {
      emit({ type: "saved", record: local, ...(limitNote ? { warning: limitNote } : {}) });
      return local;
    }
    const moved = await moveRecordingToAccount(local);
    const warning = [limitNote, moved.error ? `서버에 저장하지 못해 이 브라우저에 보관했습니다. ${moved.error}` : ""].filter(Boolean).join(" ");
    emit({ type: "saved", record: moved.record, ...(warning ? { warning } : {}) });
    return moved.record;
  } finally {
    window.removeEventListener("beforeunload", warnUnload);
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

export function stopRecording(): Promise<RecordingRecord> {
  const current = session;
  if (!current || !current.done) return Promise.reject(new Error("진행 중인 녹음이 없습니다."));
  if (current.recorder.state !== "inactive") {
    current.stoppedAt = Date.now();
    current.recorder.stop();
  }
  return current.done;
}
