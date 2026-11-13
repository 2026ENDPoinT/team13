// PDF.js 5.4.394 and its worker/font data are vendored for local-only use.
// While the ASTRA backend connection is `ready` (shared/backend.js) the four
// exported functions delegate to course-pdfs-remote.js; otherwise PDFs and their
// parsed pages stay in this browser's IndexedDB as before.
import { backendStatus, subscribe } from "./backend.ts";
import type { RemotePdfRecord } from "./course-pdfs-remote.ts";
import { isRemotePdfId, saveRemotePdf, listRemotePdfs, getRemotePdfBlob, deleteRemotePdf } from "./course-pdfs-remote.ts";
import { isStudyDoc, isStudyDocId, getStudyDocumentBlob, type StudyDocRecord } from "./study-documents.ts";

/** One parsed PDF page. */
export interface PdfPage {
  number: number;
  text: string;
  heading: string;
}

/** Page text, excerpts and keywords extracted in this browser. */
export interface PdfSummary {
  pageCount: number;
  pages: PdfPage[];
  overview: { page: number; text: string }[];
  keywords: string[];
  status: "ready" | "no-text";
}

/** A PDF parsed and stored in this browser's IndexedDB. */
export interface LocalPdfRecord extends PdfSummary {
  id: string;
  courseId: string;
  title: string;
  fileName: string;
  size: number;
  uploadedAt: string;
  remote?: undefined;
}

/** A course PDF: a local record, or a server record (summary after `ensureCoursePdfText`). */
export type CoursePdfRecord = LocalPdfRecord | RemotePdfRecord;

export interface SaveCoursePdfInput {
  file: File;
  courseId: string | number;
  title: string;
  weekNo?: number | string | null;
}

// The slice of PDF.js 5 (`/app/vendor/pdfjs/pdf.min.mjs`) this module uses.
interface PdfTextItem {
  str?: string;
  transform: number[];
  width: number;
  height?: number;
  hasEOL?: boolean;
}
interface PdfPageProxy {
  getTextContent(): Promise<{ items: PdfTextItem[] }>;
  cleanup(): void;
}
interface PdfDocumentProxy {
  numPages: number;
  getPage(number: number): Promise<PdfPageProxy>;
}
interface PdfLoadingTask {
  promise: Promise<PdfDocumentProxy>;
  destroy(): Promise<void>;
}
interface PdfWorker {
  destroy(): void;
}
interface PdfJs {
  PDFWorker: new (options: { port: Worker }) => PdfWorker;
  getDocument(options: Record<string, unknown> & { data: Uint8Array; worker: PdfWorker }): PdfLoadingTask;
}

const VENDOR = new URL("/app/vendor/pdfjs/", location.origin);
const DATABASE = "astra-course-pdfs";
const VERSION = 1;
const STOP_WORDS = new Set(["the", "and", "for", "with", "from", "this", "that", "are", "not", "you", "can", "will", "have", "into", "page", "of", "to", "in", "is", "it", "on", "as", "an", "or", "be", "by", "at", "수", "및", "등", "위한", "대한", "통해", "있는", "있다", "합니다", "그리고", "또는", "경우", "따라"]);

function storageError(error?: unknown): Error {
  if (error instanceof DOMException && error.name === "QuotaExceededError") return new Error("브라우저 저장 공간이 부족합니다. 저장 공간을 확보한 뒤 다시 등록하세요.");
  return new Error("이 브라우저에 PDF를 저장하거나 불러올 수 없습니다. 브라우저의 사이트 저장소 설정을 확인하세요.");
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) return reject(storageError());
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DATABASE, VERSION);
    } catch (error) {
      reject(storageError(error));
      return;
    }
    let blocked = false;
    request.onupgradeneeded = () => {
      const db = request.result;
      const records = db.createObjectStore("records", { keyPath: "id" });
      records.createIndex("courseId", "courseId");
      db.createObjectStore("files", { keyPath: "id" });
    };
    request.onblocked = () => {
      blocked = true;
      reject(new Error("다른 탭에서 PDF 저장소를 사용 중입니다. 다른 탭을 닫고 다시 시도하세요."));
    };
    request.onerror = () => reject(storageError(request.error));
    request.onsuccess = () => {
      const db = request.result;
      if (blocked) {
        db.close();
        return;
      }
      db.onversionchange = () => db.close();
      resolve(db);
    };
  });
}

async function readStored<T>(store: string, key: string, index?: string): Promise<T> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    try {
      const transaction = db.transaction(store, "readonly");
      const source = index ? transaction.objectStore(store).index(index) : transaction.objectStore(store);
      const request: IDBRequest = index ? source.getAll(key) : source.get(key);
      transaction.oncomplete = () => {
        db.close();
        // The store holds only rows this module wrote (records or { id, blob }).
        resolve(request.result as T);
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

async function storePdf(record: LocalPdfRecord, blob: Blob): Promise<void> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    let transaction: IDBTransaction | undefined;
    try {
      // A single transaction commits the parsed record and original together.
      const tx = db.transaction(["records", "files"], "readwrite");
      transaction = tx;
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onabort = () => {
        db.close();
        reject(storageError(tx.error));
      };
      tx.objectStore("records").add(record);
      tx.objectStore("files").add({ id: record.id, blob });
    } catch (error) {
      if (transaction) transaction.abort();
      db.close();
      reject(storageError(error));
    }
  });
}

function pageText(items: PdfTextItem[]): string {
  const lines: string[] = [];
  let line = "";
  let previous: PdfTextItem | undefined;
  for (const item of items) {
    if (typeof item.str !== "string") continue;
    const text = item.str;
    const changedLine = previous && Math.abs(item.transform[5] - previous.transform[5]) > Math.max(2, Math.abs(item.height || previous.height || 0) * 0.5);
    if (changedLine && line.trim()) {
      lines.push(line.trim());
      line = "";
    }
    const gap = previous ? item.transform[4] - (previous.transform[4] + previous.width) : 0;
    if (line && text && !/\s$/.test(line) && !/^\s/.test(text) && gap > Math.max(1, Math.abs(item.height || 0) * 0.15)) line += " ";
    line += text;
    if (item.hasEOL) {
      if (line.trim()) lines.push(line.trim());
      line = "";
      previous = undefined;
    } else previous = item;
  }
  if (line.trim()) lines.push(line.trim());
  return lines.join("\n");
}

function keywordsOf(pages: PdfPage[]): string[] {
  const words = new Map<string, { word: string; count: number }>();
  for (const page of pages) {
    for (const match of page.text.matchAll(/[\p{L}][\p{L}\p{N}-]{1,29}/gu)) {
      const word = match[0];
      const key = word.toLocaleLowerCase("ko-KR");
      if (STOP_WORDS.has(key)) continue;
      const entry = words.get(key);
      if (entry) entry.count += 1;
      else words.set(key, { word, count: 1 });
    }
  }
  return [...words.values()].sort((a, b) => b.count - a.count).slice(0, 12).map((entry) => entry.word);
}

async function parsePdf(file: Blob): Promise<PdfPage[]> {
  let library: PdfJs;
  try {
    // The vendored PDF.js module matches the PdfJs slice above.
    library = (await import(/* @vite-ignore */ new URL("pdf.min.mjs", VENDOR).href)) as PdfJs;
  } catch {
    throw new Error("PDF 분석기를 불러오지 못했습니다. 페이지를 새로고침한 뒤 다시 등록하세요.");
  }
  let port: Worker | undefined;
  let worker: PdfWorker | undefined;
  let loadingTask: PdfLoadingTask | undefined;
  let workerFailed = false;
  let onWorkerError: (() => void) | undefined;
  try {
    const data = new Uint8Array(await file.arrayBuffer());
    // Supply a real module worker explicitly: never fall back to main-thread parsing.
    const workerPort = new Worker(new URL("pdf.worker.min.mjs", VENDOR), { type: "module" });
    port = workerPort;
    const workerFailure = new Promise<never>((_resolve, reject) => {
      const fail = () => {
        workerFailed = true;
        reject(new Error("PDF 분석 작업을 시작하거나 완료하지 못했습니다. 브라우저의 작업자 차단 설정을 확인하세요."));
      };
      onWorkerError = fail;
      workerPort.addEventListener("error", fail);
      workerPort.addEventListener("messageerror", fail);
    });
    const pdfWorker = new library.PDFWorker({ port: workerPort });
    worker = pdfWorker;
    const task = library.getDocument({
      data,
      worker: pdfWorker,
      cMapUrl: new URL("cmaps/", VENDOR).href,
      cMapPacked: true,
      standardFontDataUrl: new URL("standard_fonts/", VENDOR).href,
      useWorkerFetch: true,
      useWasm: false,
      isEvalSupported: false,
      stopAtErrors: true,
      disableFontFace: true,
      isOffscreenCanvasSupported: false,
      isImageDecoderSupported: false,
    });
    loadingTask = task;
    return await Promise.race([
      workerFailure,
      (async () => {
        const document = await task.promise;
        const pages: PdfPage[] = [];
        for (let number = 1; number <= document.numPages; number += 1) {
          const page = await document.getPage(number);
          try {
            const content = await page.getTextContent();
            const text = pageText(content.items);
            pages.push({ number, text, heading: text.split("\n", 1)[0] || "" });
          } finally {
            page.cleanup();
          }
        }
        return pages;
      })(),
    ]);
  } catch (error) {
    const name = error instanceof Error ? error.name : "";
    if (name === "PasswordException") throw new Error("암호로 보호된 PDF는 분석할 수 없습니다. 암호 보호를 해제한 PDF를 등록하세요.");
    if (["InvalidPDFException", "FormatError", "UnknownErrorException"].includes(name)) throw new Error("PDF가 손상되었거나 읽을 수 없는 형식입니다. 원본 파일을 확인한 뒤 다시 등록하세요.");
    if (error instanceof Error && error.message.startsWith("PDF 분석")) throw error;
    throw new Error("PDF를 분석하지 못했습니다. 유효한 PDF 파일인지 확인한 뒤 다시 등록하세요.");
  } finally {
    if (port && onWorkerError) {
      port.removeEventListener("error", onWorkerError);
      port.removeEventListener("messageerror", onWorkerError);
    }
    if (loadingTask && !workerFailed) await loadingTask.destroy().catch(() => {});
    worker?.destroy();
    port?.terminate();
  }
}

function recordId(): string {
  if (globalThis.crypto && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (globalThis.crypto && typeof crypto.getRandomValues === "function") crypto.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function summarize(pages: PdfPage[]): PdfSummary {
  const overview = pages.filter((page) => page.text).map((page) => ({ page: page.number, text: page.text.slice(0, 360) }));
  return {
    pageCount: pages.length,
    pages,
    overview,
    keywords: keywordsOf(pages),
    status: overview.length ? "ready" : "no-text" as const,
  };
}

/** `weekNo` is used only for the backend material (1 when absent). */
export async function saveCoursePdf({ file, courseId, title, weekNo }: SaveCoursePdfInput): Promise<CoursePdfRecord> {
  if (!(file instanceof Blob) || !file.size) throw new Error("분석할 PDF 파일을 선택하세요.");
  const course = String(courseId || "").trim();
  if (!course) throw new Error("PDF를 등록할 과목을 확인하세요.");
  const name = String(file.name || "document.pdf");
  if (!/\.pdf$/i.test(name) && file.type !== "application/pdf") throw new Error("PDF 파일만 학습실에서 분석할 수 있습니다.");
  const label = String(title || "").trim();
  if (!label) throw new Error("자료명을 입력하세요.");
  if (backendStatus() === "ready") return saveRemotePdf({ file, courseId: course, title: label, weekNo });
  const blob = file.slice(0, file.size, "application/pdf");
  const pages = await parsePdf(file);
  const record: LocalPdfRecord = {
    id: recordId(),
    courseId: course,
    title: label,
    fileName: name,
    size: file.size,
    uploadedAt: new Date().toISOString(),
    ...summarize(pages),
  };
  await storePdf(record, blob);
  return record;
}

// Page text parsed in this tab from server PDFs. Memory only; a few documents at most. Dropped when the session
// changes, like the records and originals in course-pdfs-remote.js; a parse still running then is not kept.
const remoteText = new Map<string, PdfSummary | Promise<PdfSummary>>();
const REMOTE_TEXT_CACHE = 6;
let textSession = "";
let textGeneration = 0;
subscribe((next) => {
  const key = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (key === textSession) return;
  textSession = key;
  textGeneration += 1;
  remoteText.clear();
});

function withRemoteText(record: CoursePdfRecord): CoursePdfRecord {
  const parsed = remoteText.get(record.id);
  return parsed && !(parsed instanceof Promise) ? Object.assign(record, parsed) : record;
}

export async function listCoursePdfs(courseId: string | null | undefined): Promise<CoursePdfRecord[]> {
  if (backendStatus() === "ready") return (await listRemotePdfs(courseId)).map(withRemoteText);
  const records = await readStored<LocalPdfRecord[]>("records", String(courseId || ""), "courseId");
  return records.sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt));
}

/**
 * Server records and the student's study-room documents arrive without page text. Fetch the original and extract
 * pages, excerpts and keywords in this browser (kept in memory) so the document card and viewer work as for local
 * PDFs. Local records are returned unchanged.
 */
export async function ensureCoursePdfText<R extends CoursePdfRecord | StudyDocRecord | null | undefined>(record: R): Promise<R> {
  if (!record || !(record.remote || isStudyDoc(record)) || record.pageCount != null) return record;
  const cached = remoteText.get(record.id);
  if (cached) return Object.assign(record, await cached);
  const job = (async (): Promise<PdfSummary> => {
    const blob = await getCoursePdfBlob(record.id);
    if (!blob) throw new Error("서버에서 원본 PDF를 찾지 못했습니다.");
    return summarize(await parsePdf(blob));
  })();
  const generation = textGeneration;
  remoteText.set(record.id, job);
  try {
    const parsed = await job;
    if (generation === textGeneration) {
      remoteText.set(record.id, parsed);
      for (const key of remoteText.keys()) {
        if (remoteText.size <= REMOTE_TEXT_CACHE) break;
        remoteText.delete(key);
      }
    }
    return Object.assign(record, parsed);
  } catch (error) {
    if (generation === textGeneration) remoteText.delete(record.id);
    throw error;
  }
}

export async function getCoursePdfBlob(id: string | null | undefined): Promise<Blob | null> {
  if (isRemotePdfId(id)) return getRemotePdfBlob(id);
  if (isStudyDocId(id)) return getStudyDocumentBlob(id);
  const entry = await readStored<{ id: string; blob: Blob } | undefined>("files", String(id || ""));
  return entry?.blob || null;
}

/** Server records cannot be deleted through the API yet: rejects with a message. */
export async function deleteCoursePdf(id: string | null | undefined): Promise<void> {
  if (isRemotePdfId(id)) return deleteRemotePdf();
  const key = String(id || "");
  if (!key) return;
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    let transaction: IDBTransaction | undefined;
    try {
      const tx = db.transaction(["records", "files"], "readwrite");
      transaction = tx;
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onabort = () => {
        db.close();
        reject(storageError(tx.error));
      };
      tx.objectStore("records").delete(key);
      tx.objectStore("files").delete(key);
    } catch (error) {
      if (transaction) transaction.abort();
      db.close();
      reject(storageError(error));
    }
  });
}
