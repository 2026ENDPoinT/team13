// Lecture audio recorded or imported by the student. Backend `ready`: stored on the server in the student's own
// study room (study-room-remote.ts) and played or downloaded from its content address. Otherwise kept only in this
// browser's IndexedDB. Transcription and summary generation belong to the backend; their results stay in memory only.
// Audio is never dropped: a finished recording is kept in IndexedDB until the server answered 201 for it, and when
// even IndexedDB refuses it the audio stays in memory and is offered as a download (`unsavedAudio`).
import {
  serverStudyCourse, studyRoomOnServer, loadCourseView, isMissingEndpoint, uploadRecording, uploadName, getServerRecording,
  deleteServerRecording, contentUrl, studyUsage, studyRoomMessage, formatBytes, putPlacement, refreshCourseView,
  signedInUserId, uploadTitle, type ServerRecording, type RecordingState,
} from "./study-room-remote.ts";
import {
  placeLocally, localPlacements, localFolder, browserFolders, moveFolderToAccount, forgetMoved, ownerGroup,
  markUnmovable, unmovablePlacements, type OwnerGroup,
} from "./study-folders.ts";
import { ApiError } from "./api.ts";
import { backendSettled, backendState, connect, currentUserId, kjOfCourseId, subscribe as onBackendChange } from "./backend.ts";
import { saveBlob } from "./uploads-remote.tsx";
import { toast } from "../store.ts";
import { appendWebm, isAppendable } from "./webm-append.ts";
import { originalCleanupWarning } from "./recording-join.ts";
import type { RecordingStudyResults } from "./recording-study.ts";
import { startLiveTranscript, pauseLiveTranscript, resumeLiveTranscript, stopLiveTranscript } from "./live-transcript.ts";

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
  /** The upload key after the server reported a key conflict (a fresh key, used by the user's next try). */
  uploadKey?: string;
}

/** A recording on the server. `id` is the server id as a string; `url` plays and downloads it. */
export interface RemoteRecording extends RecordingFields, RecordingStudyResults {
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
  /** "이어서 녹음": the recording this one is joined to when it stops. `elapsedMs` and `bytes` include it. */
  appendTo: { id: string; title: string } | null;
  /** The title and start the recording gets when saved (a continuation keeps its original recording's). */
  title: string;
  startedAt: Date;
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
  startedAt: Date;
  pausedTotal: number;
  pausedAt: number;
  stoppedAt: number;
  /**
   * "이어서 녹음": the recording continued, and its audio and folder, both read at the start. The folder is the fallback
   * for `appendFolder` when this one stops: by then the original may have been deleted elsewhere, taking its placement.
   */
  append: { record: RecordingRecord; base: Promise<Blob>; folder: Promise<string | null> } | null;
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
    transcription: recording.transcription,
    summary: recording.summary,
  };
}

/**
 * Newest first. Server course: its server recordings, then every recording still only in this browser for the course
 * (screens group them by `ownerGroup`), so each can be played, downloaded, moved or deleted.
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
 * Who the server says is signed in now, compared with the account an upload or move is for. "no-owner": the
 * recording was started while nobody was signed in, so it has no account to go to on its own.
 */
type AccountCheck = "same" | "other" | "signed-out" | "unknown" | "no-owner";

/** Asks the server (`GET /api/me`, never the cached connection). A change makes the app follow it. */
async function accountNow(userId: string | null): Promise<AccountCheck> {
  if (!userId) return "no-owner";
  let now: string | null;
  try {
    now = await signedInUserId();
  } catch {
    return "unknown";
  }
  if (now === userId) return "same";
  // Another tab signed out or in as someone else on the shared cookie: let the app follow.
  void connect();
  return now ? "other" : "signed-out";
}

/** Why a finished recording stayed in this browser instead of going to the server. */
const KEPT: Record<Exclude<AccountCheck, "same">, string> = {
  other: "다른 계정으로 로그인되어 있어 녹음을 서버에 올리지 않고 이 브라우저에 보관했습니다. 녹음한 계정으로 다시 로그인한 뒤 ‘내 계정으로 옮기기’로 옮길 수 있습니다.",
  "signed-out": "로그아웃되어 있어 녹음을 서버에 올리지 않고 이 브라우저에 보관했습니다. 다시 로그인한 뒤 ‘내 계정으로 옮기기’로 옮길 수 있습니다.",
  unknown: "로그인한 계정을 확인하지 못해(연결 문제) 녹음을 이 브라우저에 보관했습니다. 연결이 돌아오면 ‘내 계정으로 옮기기’로 옮길 수 있습니다.",
  "no-owner": "로그인하기 전에 시작한 녹음이라 이 브라우저에 보관했습니다. 내 녹음이 맞으면 ‘내 계정으로 옮기기’로 옮기세요.",
};

/** Why a move stopped before its end. */
const MOVE_STOPPED: Record<Exclude<AccountCheck, "same">, string> = {
  other: "로그인한 계정이 바뀌어 옮기기를 멈췄습니다. 남은 항목은 이 브라우저에 그대로 있습니다.",
  "signed-out": "로그아웃되어 옮기기를 멈췄습니다. 남은 항목은 이 브라우저에 그대로 있습니다.",
  unknown: "로그인한 계정을 확인하지 못해(연결 문제) 옮기기를 멈췄습니다. 남은 항목은 이 브라우저에 그대로 있습니다.",
  "no-owner": "로그인한 계정이 없어 옮기기를 멈췄습니다. 남은 항목은 이 브라우저에 그대로 있습니다.",
};

/** Thrown by `saveRemote` when the account check right before a POST did not answer "same". */
class AccountStop extends Error {
  outcome: Exclude<AccountCheck, "same">;
  constructor(outcome: Exclude<AccountCheck, "same">) {
    super(MOVE_STOPPED[outcome]);
    this.outcome = outcome;
  }
}

/**
 * Sends a new recording to the server (`folderId` places it in the same request). The key makes a retry safe;
 * `idempotency_in_progress` (the same key still being processed) is asked again shortly. `userId`: the account it is
 * for, checked against the server immediately before every POST (`AccountStop` otherwise).
 */
async function saveRemote(server: number | string, input: SaveInput, idempotencyKey: string, userId?: string | null): Promise<RemoteRecording> {
  for (let attempt = 0; ; attempt += 1) {
    if (userId !== undefined) {
      const check = await accountNow(userId);
      if (check !== "same") throw new AccountStop(check);
    }
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
  /** The account changed, the session ended, or the account could not be checked: a batch must stop; the record's reason was not touched. */
  stop?: boolean;
}

interface MoveOptions {
  /** Browser folder id -> server folder id, shared by a batch. */
  folderIds?: Map<string, string>;
  /** The account the move was started for; checked against the server right before each POST. */
  userId?: string | null;
  /** Browser folders the user chose to move (besides their own folders): a recording in one takes it along. */
  chosenFolders?: ReadonlySet<string>;
}

/** The server recording an earlier, unanswered upload of `record` made, found by title, start time and size. */
async function alreadyUploaded(server: number | string, record: LocalRecording): Promise<RemoteRecording | null> {
  const view = await loadCourseView(server, true);
  const title = uploadTitle(record.title);
  const start = Date.parse(recordedAt(record.createdAt));
  const found = view.recordings.find((item) => item.title === title && Date.parse(item.createdAt) === start && item.sizeBytes === record.blob.size);
  return found ? fromServer(found, record.courseId) : null;
}

/**
 * Moves one browser recording to the signed-in account (an explicit action, or the automatic upload right after a
 * recording). A recording in the user's own browser folder, or in a folder the user chose, takes that folder along
 * (`POST …/folders`, key `import-folder-<id>`); any other browser folder is left alone and the recording goes to the
 * course root. One with `pendingFolderId` goes into that server folder if it still exists. The upload uses the key
 * `import-rec-<localId>` (or the fresh `uploadKey` after a key conflict). The browser copy is deleted only after
 * the server answered 201 (or replayed it); on a refusal it stays with the reason in `uploadError`, written only if
 * the record still exists.
 */
export async function moveRecordingToAccount(record: LocalRecording, { folderIds = new Map(), userId = currentUserId(), chosenFolders }: MoveOptions = {}): Promise<MoveResult> {
  const keep = async (error: unknown, change: Partial<LocalRecording> = {}): Promise<MoveResult> => {
    const message = error instanceof Error && !(error instanceof ApiError) ? error.message : studyRoomMessage(error);
    const kept = await updateLocalIfPresent(record.id, { ...change, uploadError: message }).catch(() => null);
    return { record: kept || { ...record, ...change, uploadError: message }, error: message };
  };
  const stopped = (outcome: Exclude<AccountCheck, "same">): MoveResult => ({ record, error: MOVE_STOPPED[outcome], stop: true });
  const server = serverStudyCourse(record.courseId);
  if (server == null) return keep(new Error("이 과목은 ASTRA 서버에 없어 옮길 수 없습니다."));
  const placedIn = localFolder(localPlacements()[record.id] || "");
  const takeFolder = placedIn && (folderIds.has(placedIn.id) || ownerGroup(placedIn.ownerUserId) === "mine" || chosenFolders?.has(placedIn.id)) ? placedIn : null;
  let folderId: string | null = null;
  // The folder is settled first; the account is checked right before each POST (in saveRemote and here).
  try {
    if (takeFolder) {
      // The server folder must exist before the upload names it (the key's fingerprint covers folderId).
      if (!folderIds.has(takeFolder.id)) {
        const check = await accountNow(userId);
        if (check !== "same") return stopped(check);
      }
      folderId = folderIds.get(takeFolder.id) ?? await moveFolderToAccount(takeFolder);
      folderIds.set(takeFolder.id, folderId);
    } else if (record.pendingFolderId) {
      const view = await loadCourseView(server, true);
      folderId = view.folders.some((folder) => String(folder.id) === record.pendingFolderId) ? record.pendingFolderId : null;
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return stopped("signed-out");
    return keep(error);
  }
  const input: SaveInput = {
    courseId: record.courseId, source: record.source, blob: record.blob, durationMs: record.durationMs,
    startedAt: new Date(recordedAt(record.createdAt)), title: record.title, fileName: record.fileName, folderId,
  };
  let moved: RemoteRecording;
  try {
    moved = await saveRemote(server, input, record.uploadKey || `import-rec-${record.id}`, userId);
  } catch (error) {
    if (error instanceof AccountStop) return stopped(error.outcome);
    if (error instanceof ApiError && error.status === 401) return stopped("signed-out");
    if (!(error instanceof ApiError && error.code === "idempotency_conflict")) return keep(error);
    // The same key earlier carried another request (its folder was deleted since, so folderId differs): if that
    // earlier upload reached the server, it is this recording; otherwise the next try uses a new key.
    let earlier: RemoteRecording | null;
    try {
      earlier = await alreadyUploaded(server, record);
    } catch {
      // The course view did not load: nothing is known, so the key stays as it is for the next try.
      return keep(new Error("서버에 이미 올라갔는지 확인하지 못했습니다(연결 문제). 연결을 확인한 뒤 ‘다시 올리기’를 누르세요."));
    }
    if (!earlier) {
      return keep(new Error("예전에 이 녹음을 다른 폴더로 보낸 요청과 맞지 않아 올리지 못했습니다. ‘다시 올리기’를 누르면 새로 올립니다."), {
        uploadKey: `import-rec-${record.id}-${crypto.randomUUID().slice(0, 8)}`,
      });
    }
    moved = earlier;
  }
  try {
    await deleteLocalRecording(record.id);
  } catch {
    // Still in IndexedDB: a later move sends the same key and the server answers with the same recording.
  }
  forgetMoved([record.id], takeFolder && folderId ? new Map([[takeFolder.id, folderId]]) : new Map());
  return { record: moved };
}

export interface MoveScope {
  /** Only this course (`kj`); every server course of the account when absent. */
  kj?: string;
  /** Whose browser items: the user's own, those saved before login, or another account's (the user confirmed). */
  group: OwnerGroup;
  /** One browser folder the user chose to move with everything in it (any group). */
  folderId?: string;
}

/**
 * Moves browser items of the account's server courses: browser folders in their order, then recordings, then course
 * PDFs placed in those folders. Only the scope's folders move; a recording in another browser folder goes to the
 * course root and that folder is left alone. The server user is checked right before every POST and PUT; on a change,
 * an unknown answer or a 401 the run stops and leaves the rest as it is. A placement that answers 404 stays here,
 * marked as not movable with its reason. Resolves a summary sentence.
 */
export async function moveAllToAccount({ kj, group, folderId }: MoveScope): Promise<string> {
  const userId = currentUserId();
  const inCourse = (courseId: string) => (kj == null || courseId === kj) && serverStudyCourse(courseId) != null;
  const placed = localPlacements();
  const selected = browserFolders().filter((folder) => inCourse(folder.courseId) && (folderId ? folder.id === folderId : ownerGroup(folder.ownerUserId) === group));
  const chosenFolders = new Set(selected.map((folder) => folder.id));
  const folderIds = new Map<string, string>(); // local id -> server id, moved or reused by exact name
  const folderErrors: string[] = [];
  let newlyMoved = 0;
  let stop: string | null = null;
  for (const folder of selected) {
    const check = await accountNow(userId);
    if (check !== "same") { stop = MOVE_STOPPED[check]; break; }
    try {
      folderIds.set(folder.id, await moveFolderToAccount(folder));
      if (!folder.movedTo) newlyMoved += 1;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) { stop = MOVE_STOPPED["signed-out"]; break; }
      folderErrors.push(studyRoomMessage(error));
    }
  }
  const records = stop ? [] : (await listLocalRecordings())
    .filter((record) => inCourse(record.courseId) && (folderId ? placed[record.id] === folderId : ownerGroup(record.ownerUserId) === group))
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  let moved = 0;
  const errors: string[] = [];
  for (const record of records) {
    const result = await moveRecordingToAccount(record, { folderIds, userId, chosenFolders });
    if (result.stop) { stop = result.error || null; break; }
    if (result.error) errors.push(result.error);
    else moved += 1;
  }
  const handled: string[] = [];
  let unmovable = 0;
  const unmovableNow = unmovablePlacements();
  for (const [itemId, folder] of Object.entries(placed)) {
    const file = /^srv-([1-9][0-9]*)$/.exec(itemId);
    const target = folderIds.get(folder);
    if (stop || !file || !target || unmovableNow[itemId]) continue;
    const check = await accountNow(userId);
    if (check !== "same") { stop = MOVE_STOPPED[check]; break; }
    try {
      await putPlacement("course-files", file[1], target, 0);
      handled.push(itemId);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) { stop = MOVE_STOPPED["signed-out"]; break; }
      if (error instanceof ApiError && error.status === 404) {
        // Gone, or no longer readable: kept here with the reason and never sent again.
        markUnmovable(itemId, "이 강의자료는 서버에서 더 이상 열 수 없어 폴더에 옮기지 못했습니다.");
        unmovable += 1;
      } else if (error instanceof ApiError && error.status === 409) {
        // Placed already: done when the server shows it in that same folder.
        const local = localFolder(folder);
        const server = local ? serverStudyCourse(local.courseId) : null;
        const view = server != null ? await loadCourseView(server, true).catch(() => null) : null;
        if (view && view.placements.some((item) => item.kind === "course_file" && String(item.itemId) === file[1] && String(item.folderId) === target)) handled.push(itemId);
      }
    }
  }
  forgetMoved(handled, folderIds);
  for (const courseId of new Set([...records.map((record) => record.courseId), ...selected.map((folder) => folder.courseId)])) {
    const server = serverStudyCourse(courseId);
    if (server != null) void refreshCourseView(server);
  }
  const done = [newlyMoved && `폴더 ${newlyMoved}개`, moved && `녹음 ${moved}개`].filter(Boolean).join("와 ");
  const notes = [done ? `${done}를 내 계정으로 옮겼습니다.` : ""];
  // A reason is said once: a folder over the limit also stops the recordings meant for it, with the same sentence.
  const said = new Set<string>();
  const unsaid = (reasons: string[]) => [...new Set(reasons)].filter((reason) => !said.has(reason) && said.add(reason)).join(" ");
  if (folderErrors.length) notes.push(`폴더 ${folderErrors.length}개는 옮기지 못했습니다. ${unsaid(folderErrors)}`.trim());
  if (errors.length) notes.push(`녹음 ${errors.length}개는 이 브라우저에 남겨 두었습니다. ${unsaid(errors)}`.trim());
  if (unmovable) notes.push(`강의자료 ${unmovable}개는 서버에서 더 이상 열 수 없어 폴더에 옮기지 못했습니다.`);
  if (stop) notes.push(unsaid([stop]));
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

// A recording started before the first connection answer belongs to whoever that answer names. Later sign-in
// changes (an expired session, the same person signing in again, a failed connection check) never stop a recording:
// `finish` checks the account with the server before the upload and keeps the audio here when it does not match.
onBackendChange((next) => {
  const current = session;
  // A continued recording took its owner and limit from the recording it continues.
  if (!current || current.settled || !next.settled || current.append) return;
  current.settled = true;
  current.ownerUserId = next.status === "ready" && next.me ? String(next.me.userId) : null;
  // Now that the study room is known to be on the server, the server's file limit applies to this recording too.
  const server = recordsInBrowserOnly(current.courseId, current.folderId) ? null : serverStudyCourse(current.courseId);
  if (server != null && current.limitBytes == null) {
    current.limitBytes = studyUsage()?.maxFileBytes ?? null;
    loadCourseView(server).then((view) => {
      current.limitBytes = view.usage ? view.usage.maxFileBytes : null;
    }, () => {});
  }
});

/**
 * A stable `Idempotency-Key` for importing this file as this user into this course: picking the same file again
 * after a lost answer replays instead of storing it twice; another course gets its own key.
 */
async function importKey(file: File, userId: string, courseId: number | string): Promise<string> {
  const text = new TextEncoder().encode(`${userId}|${courseId}|${file.name}|${file.size}|${file.lastModified}`);
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
    const key = await importKey(file, userId, server);
    try {
      try {
        record = await saveRemote(server, input, key);
      } catch (error) {
        // 410 `recording_deleted`: this file was imported before and that recording deleted since. The user chose
        // the file again, so it is a new upload under a second key, fixed so that a lost answer is replayed too;
        // only if that one was also deleted since does it get a random key.
        if (!(error instanceof ApiError && error.status === 410)) throw error;
        try {
          record = await saveRemote(server, input, `${key}-again`);
        } catch (again) {
          if (!(again instanceof ApiError && again.status === 410)) throw again;
          record = await saveRemote(server, input, `${key}-${crypto.randomUUID()}`);
        }
      }
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

/** Bytes of the joined file so far: the continued recording and what was recorded now. */
const totalBytes = (s: Session) => s.bytes + (s.append ? s.append.record.size : 0);

/** True when a recording started now in `courseId` (inside `folderId`) is saved only in this browser. */
export function recordsInBrowserOnly(courseId: string, folderId: string | null | undefined): boolean {
  return serverStudyCourse(courseId) == null || Boolean(folderId && localFolder(folderId));
}

export function activeRecording(): ActiveRecording | null {
  if (!session) return null;
  const append = session.append;
  return {
    courseId: session.courseId, source: session.source, paused: Boolean(session.pausedAt),
    elapsedMs: elapsed(session) + (append ? append.record.durationMs : 0), bytes: totalBytes(session), limitBytes: session.limitBytes,
    browserOnly: append ? !append.record.remote : recordsInBrowserOnly(session.courseId, session.folderId),
    appendTo: append ? { id: append.record.id, title: append.record.title } : null,
    title: append ? append.record.title : recordingTitle(session.startedAt),
    startedAt: append ? new Date(recordedAt(append.record.createdAt)) : session.startedAt,
  };
}

/** The audio type MediaRecorder writes for a continuation of `record`, or null when it cannot write a joinable one. */
function appendType(record: RecordingRecord): string | null {
  if (typeof MediaRecorder === "undefined" || !isAppendable(record.mimeType)) return null;
  return [record.mimeType, "audio/webm;codecs=opus", "audio/webm"].find((type) => MediaRecorder.isTypeSupported(type)) || null;
}

/** Why "이어서 녹음" is not offered for `record` now, or "" when it is. */
export function appendBlocked(record: RecordingRecord): string {
  if (session) return "다른 녹음이 진행 중입니다. 그 녹음을 저장한 뒤 이어서 녹음할 수 있습니다.";
  if (!record.courseId) return "지금 듣지 않는 과목의 녹음이라 이어서 녹음할 수 없습니다.";
  if (record.remote && record.state !== "ready") return "바이러스 검사가 끝난 녹음만 이어서 녹음할 수 있습니다.";
  if (!isAppendable(record.mimeType)) return "이 앱에서 녹음한 파일(WebM)만 이어서 녹음할 수 있습니다. 올린 MP3·M4A 같은 파일은 새로 녹음하세요.";
  if (!appendType(record)) return "이 브라우저는 이 녹음 형식으로 이어서 녹음하지 못합니다. 데스크톱 Chrome 또는 Edge에서 열어 주세요.";
  return "";
}

function warnUnload(event: BeforeUnloadEvent): void {
  event.preventDefault();
  event.returnValue = "";
}

/**
 * Must be called from a click handler: browsers only open the mic or tab picker for a user gesture.
 * `folderId`: the open folder the recording is saved into. A browser-only folder keeps it browser-only.
 * `appendTo`: "이어서 녹음" — when it stops, the new audio is joined to the end of that recording (`appendBlocked`
 * says when that is not possible); the joined file replaces it where it is stored.
 */
export async function startRecording({ courseId, source, folderId = null, appendTo = null }: { courseId: string; source: "mic" | "tab"; folderId?: string | null; appendTo?: RecordingRecord | null }): Promise<void> {
  if (session) throw new Error("이미 녹음 중입니다. 지금 녹음을 저장한 뒤 새로 시작하세요.");
  const blocked = appendTo ? appendBlocked(appendTo) : "";
  if (blocked) throw new Error(blocked);
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
  const mimeType = appendTo ? appendType(appendTo) : MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type));
  const recorder = new MediaRecorder(new MediaStream(audio), mimeType ? { mimeType, audioBitsPerSecond: AUDIO_BITS_PER_SECOND } : { audioBitsPerSecond: AUDIO_BITS_PER_SECOND });
  const chunks: Blob[] = [];
  // A continuation goes where the recording it continues is; its server file limit covers the joined file.
  const server = appendTo ? (appendTo.remote ? appendTo.backendCourseId : null) : recordsInBrowserOnly(courseId, folderId) ? null : serverStudyCourse(courseId);
  const current: Session = {
    courseId, source, folderId: appendTo ? null : folderId, ownerUserId: currentUserId(), settled: appendTo ? true : backendSettled(), recorder, stream, chunks, bytes: 0,
    limitBytes: server != null ? studyUsage()?.maxFileBytes ?? null : null, limitReached: false, warned: false,
    startedAt: new Date(), pausedTotal: 0, pausedAt: 0, stoppedAt: 0,
    append: appendTo ? { record: appendTo, base: recordingAudio(appendTo), folder: folderOf(appendTo).catch(() => null) } : null,
  };
  // Awaited when it stops; a failure there only means the new audio is saved on its own.
  current.append?.base.catch(() => {});
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
    const bytes = totalBytes(current);
    if (!current.warned && bytes >= current.limitBytes * WARN_SHARE) {
      current.warned = true;
      toast(`녹음 파일이 서버 한도(${formatBytes(current.limitBytes)})에 가까워졌습니다. 한도에 닿으면 녹음을 멈추고 저장합니다.`);
    }
    // Stop and save before the file would pass the server's limit instead of failing after the lecture.
    if (bytes >= current.limitBytes * STOP_SHARE && recorder.state !== "inactive") {
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
  // 마이크 녹음만: 탭 소리는 브라우저 음성 인식에 넣을 수 없다. 줄의 시각은 이어서 녹음한 앞부분을 포함한 녹음 시각이다.
  if (source === "mic") startLiveTranscript(() => (session === current ? elapsed(current) + (current.append ? current.append.record.durationMs : 0) : 0));
  emit({ type: "change" });
}

/** The stored audio of a recording: the IndexedDB blob, or the server file (owner-only, the session cookie). */
async function recordingAudio(record: RecordingRecord): Promise<Blob> {
  if (!record.remote) return record.blob;
  const response = await fetch(record.url, { credentials: "same-origin" });
  if (!response.ok) throw new Error(`서버에서 원래 녹음을 받지 못했습니다(${response.status}).`);
  return response.blob();
}

/** The folder `record` is in: its browser folder or pending server folder, or its server placement. */
async function folderOf(record: RecordingRecord): Promise<string | null> {
  if (!record.remote) return localPlacements()[record.id] || record.pendingFolderId || null;
  const view = await loadCourseView(record.backendCourseId, true).catch(() => null);
  const placement = view && view.placements.find((item) => item.kind === "recording" && String(item.itemId) === record.id);
  return placement ? String(placement.folderId) : null;
}

/**
 * The folder for a continuation's audio: where the original is now, or, when it has no placement any more (deleted in
 * another tab or device during the recording), where it was when the continuation started.
 */
async function appendFolder(append: NonNullable<Session["append"]>): Promise<string | null> {
  const now = await folderOf(append.record).catch(() => null);
  return now ?? await append.folder;
}

/**
 * "이어서 녹음": joins `blob` to the end of the recording it continues and stores the joined file in its place. A
 * browser recording is rewritten in IndexedDB under the same id. A server recording is uploaded again as a new
 * recording (same title, start, folder) and the old one is deleted only after that 201; when that delete fails (both
 * stay) or finds it already deleted elsewhere, `warning` says so (recording-join.ts). Throws when nothing was replaced,
 * so the caller saves `blob` on its own.
 */
async function joinRecording(current: Session, blob: Blob, durationMs: number): Promise<{ record: RecordingRecord; warning?: string }> {
  const { record, base } = current.append!;
  const joined = await appendWebm(await base, blob);
  const total = record.durationMs + durationMs;
  if (!record.remote) {
    const updated = await updateLocalIfPresent(record.id, { blob: joined, size: joined.size, mimeType: joined.type, durationMs: total });
    if (!updated) throw new Error("이어 붙일 원래 녹음이 이 브라우저에서 지워졌습니다.");
    return { record: updated };
  }
  const input: SaveInput = {
    courseId: record.courseId, source: record.source, blob: joined, durationMs: total, startedAt: new Date(recordedAt(record.createdAt)),
    title: record.title, fileName: record.fileName, folderId: await appendFolder(current.append!),
  };
  let saved: RemoteRecording;
  try {
    // One key per continuation: a retried request after a lost answer gets the same recording back.
    saved = await saveRemote(record.backendCourseId, input, `append-${record.id}-${record.version}-${current.startedAt.getTime()}`, current.ownerUserId);
  } catch (error) {
    if (error instanceof AccountStop) throw new Error(KEPT[error.outcome]);
    throw new ApiErrorMessage(error);
  }
  let cleanup: unknown = null;
  try {
    await deleteServerRecording({ id: record.id, version: record.version, courseId: record.backendCourseId });
  } catch (error) {
    cleanup = error;
  }
  const warning = originalCleanupWarning(cleanup, record.title, studyRoomMessage);
  return warning ? { record: saved, warning } : { record: saved };
}

/**
 * Stores a finished recording: IndexedDB first, then (study room on the server, recorded by the account still signed
 * in, not in a browser-only folder) the upload, which deletes the browser copy after 201. If IndexedDB refuses it the
 * in-memory audio is uploaded with its own stable key; if that fails too it stays in memory and is offered as a file.
 * A continuation ("이어서 녹음") is joined to its recording first; if that fails it is stored on its own the same way,
 * next to that recording. Resolves the stored record, or null when only the in-memory copy is left.
 */
async function finish(current: Session): Promise<RecordingRecord | null> {
  const durationMs = elapsed(current, current.stoppedAt || Date.now());
  current.stream.getTracks().forEach((track) => track.stop());
  stopLiveTranscript();
  if (session === current) session = null;
  try {
    const blob = new Blob(current.chunks, { type: current.recorder.mimeType || current.chunks[0]?.type || "audio/webm" });
    if (!blob.size) throw new Error("녹음된 소리가 없어 저장하지 않았습니다.");
    const notes: string[] = [];
    if (current.limitReached && current.limitBytes) notes.push(`녹음 파일이 서버 한도(${formatBytes(current.limitBytes)})에 가까워져 녹음을 멈추고 저장했습니다. 이어서 녹음하려면 새로 시작하세요.`);
    let title: string | undefined;
    if (current.append) {
      try {
        const joined = await joinRecording(current, blob, durationMs);
        notes.push(...(joined.warning ? [joined.warning] : []));
        emit({ type: "saved", record: joined.record, ...(notes.length ? { warning: notes.join(" ") } : {}) });
        return joined.record;
      } catch (error) {
        const original = current.append.record;
        notes.push(`원래 녹음에 이어 붙이지 못해 새 녹음으로 따로 저장했습니다. ${error instanceof Error ? error.message : ""}`.trim());
        title = `${original.title} (이어서)`;
        current.folderId = await appendFolder(current.append);
      }
    }
    const browserFolder = current.folderId ? localFolder(current.folderId) : null;
    const server = serverStudyCourse(current.courseId);
    const owner = current.ownerUserId;
    const input: SaveInput = {
      courseId: current.courseId, source: current.source, blob, durationMs, startedAt: current.startedAt, title,
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
    // Started signed in, finished with the study room no longer on the server (signed out, or the connection lost).
    if (server == null && owner) notes.push(KEPT[backendState().status === "anon" ? "signed-out" : "unknown"]);
    const check: AccountCheck = server != null && !browserFolder ? await accountNow(owner) : "unknown";
    if (server != null && !browserFolder && check !== "same") notes.push(KEPT[check]);
    if (local && (server == null || browserFolder || check !== "same")) {
      emit({ type: "saved", record: local, ...(notes.length ? { warning: notes.join(" ") } : {}) });
      return local;
    }
    if (local) {
      const moved = await moveRecordingToAccount(local, { userId: owner });
      if (moved.error) notes.push(`서버에 저장하지 못해 이 브라우저에 보관했습니다. ${moved.error}`);
      emit({ type: "saved", record: moved.record, ...(notes.length ? { warning: notes.join(" ") } : {}) });
      return moved.record;
    }
    if (server != null && !browserFolder && check === "same") {
      // IndexedDB refused it (e.g. QuotaExceededError): send the audio from memory with a key fixed for it.
      try {
        let record: RemoteRecording;
        try {
          record = await saveRemote(server, { ...input, folderId: current.folderId }, `rec-${crypto.randomUUID()}`, owner);
        } catch (error) {
          // The folder it was recorded in is gone (404): the course root rather than only memory.
          if (!(current.folderId && error instanceof ApiError && error.status === 404)) throw error;
          record = await saveRemote(server, { ...input, folderId: null }, `rec-${crypto.randomUUID()}`, owner);
        }
        emit({ type: "saved", record, ...(notes.length ? { warning: notes.join(" ") } : {}) });
        return record;
      } catch (error) {
        storeError = error instanceof AccountStop ? new Error(KEPT[error.outcome]) : new ApiErrorMessage(error);
      }
    }
    const kept = keepInMemory(input);
    const reason = [storeError instanceof Error ? storeError.message : "", ...notes].filter(Boolean).join(" ");
    emit({ type: "error", error: new Error(`녹음을 저장할 곳이 없어 "${kept.fileName}" 파일로 내려받게 했습니다. 이 페이지를 닫기 전에 파일이 저장됐는지 확인하세요. ${reason}`.trim()) });
    return null;
  } finally {
    // Another recording may have started while this one was uploading: its page-close warning stays.
    if (!unsaved.length && !session) window.removeEventListener("beforeunload", warnUnload);
  }
}

export function pauseRecording(): void {
  if (!session || session.pausedAt || session.recorder.state !== "recording") return;
  session.recorder.pause();
  session.pausedAt = Date.now();
  emit({ type: "change" });
  pauseLiveTranscript();
}

export function resumeRecording(): void {
  if (!session || !session.pausedAt) return;
  session.recorder.resume();
  session.pausedTotal += Date.now() - session.pausedAt;
  session.pausedAt = 0;
  emit({ type: "change" });
  resumeLiveTranscript();
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
