// Lecture audio recorded or imported in this browser.
// Audio is kept only in this browser's IndexedDB. Nothing is uploaded or transcribed.
const DATABASE = "astra-course-recordings";
const VERSION = 1;
const STORE = "recordings";
const MIME_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus"];

export type RecordingSource = "mic" | "tab" | "audio";

/** A recording stored in IndexedDB (`recordings` store). */
export interface RecordingRecord {
  id: string;
  courseId: string;
  title: string;
  source: RecordingSource;
  mimeType: string;
  size: number;
  durationMs: number;
  createdAt: string;
  blob: Blob;
  fileName?: string;
}

export type RecordingEvent =
  | { type: "change" }
  | { type: "saved"; record: RecordingRecord }
  | { type: "error"; error: unknown };

export interface ActiveRecording {
  courseId: string;
  source: RecordingSource;
  paused: boolean;
  elapsedMs: number;
}

interface Session {
  courseId: string;
  source: RecordingSource;
  recorder: MediaRecorder;
  stream: MediaStream;
  chunks: Blob[];
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

// The store holds only RecordingRecord rows (saveRecording).
const asRecords = (request: IDBRequest): IDBRequest<RecordingRecord[]> => request as IDBRequest<RecordingRecord[]>;

export async function listRecordings(courseId: string | null | undefined): Promise<RecordingRecord[]> {
  const records = await withStore("readonly", (store) => asRecords(store.index("courseId").getAll(String(courseId || ""))));
  return records.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function getRecording(id: string | null | undefined): Promise<RecordingRecord | undefined> {
  // The store holds only RecordingRecord rows (saveRecording).
  return withStore("readonly", (store) => store.get(String(id || "")) as IDBRequest<RecordingRecord | undefined>);
}

export function deleteRecording(id: string | null | undefined): Promise<undefined> {
  return withStore("readwrite", (store) => store.delete(String(id || "")));
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
}

async function saveRecording({ courseId, source, blob, durationMs, startedAt, title, fileName }: SaveInput): Promise<RecordingRecord> {
  const record: RecordingRecord = {
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

/** Listen for "change" (start, pause, resume), "saved" ({ record }) and "error" ({ error }) events. */
export function onRecordingEvent(listener: (event: RecordingEvent) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Import a local audio file through the same browser-only storage used by recordings. */
export async function importRecording({ courseId, file }: { courseId: string; file: File | null | undefined }): Promise<RecordingRecord> {
  const types: Record<string, string> = { mp3: "audio/mpeg", m4a: "audio/mp4", mp4: "audio/mp4", wav: "audio/wav", ogg: "audio/ogg", webm: "audio/webm", aac: "audio/aac", flac: "audio/flac" };
  const extension = String(file?.name || "").split(".").pop()?.toLowerCase() || "";
  if (!file?.size) throw new Error("비어 있는 파일은 저장할 수 없습니다. 소리가 있는 오디오 파일을 선택하세요.");
  if (file.type.startsWith("video/") || (!file.type.startsWith("audio/") && !types[extension])) {
    throw new Error("오디오 파일을 선택하세요. MP3, M4A, WAV 등의 녹음 파일을 사용할 수 있습니다.");
  }
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
  const record = await saveRecording({
    courseId, source: "audio", blob, durationMs, startedAt: new Date(),
    title: file.name.replace(/\.[^.]+$/, "") || file.name,
    fileName: file.name,
  });
  emit({ type: "saved", record });
  return record;
}

function elapsed(s: Session, now: number = Date.now()): number {
  return now - s.startedAt.getTime() - s.pausedTotal - (s.pausedAt ? now - s.pausedAt : 0);
}

export function activeRecording(): ActiveRecording | null {
  if (!session) return null;
  return { courseId: session.courseId, source: session.source, paused: Boolean(session.pausedAt), elapsedMs: elapsed(session) };
}

function warnUnload(event: BeforeUnloadEvent): void {
  event.preventDefault();
  event.returnValue = "";
}

/** Must be called from a click handler: browsers only open the mic or tab picker for a user gesture. */
export async function startRecording({ courseId, source }: { courseId: string; source: "mic" | "tab" }): Promise<void> {
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
  const recorder = new MediaRecorder(new MediaStream(audio), mimeType ? { mimeType } : undefined);
  const chunks: Blob[] = [];
  recorder.addEventListener("dataavailable", (event) => {
    if (event.data.size) chunks.push(event.data);
  });
  const current: Session = { courseId, source, recorder, stream, chunks, startedAt: new Date(), pausedTotal: 0, pausedAt: 0, stoppedAt: 0 };
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
    const record = await saveRecording({ courseId: current.courseId, source: current.source, blob, durationMs, startedAt: current.startedAt });
    emit({ type: "saved", record });
    return record;
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
