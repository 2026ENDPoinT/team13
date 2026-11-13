import type { Activity } from "../data.ts";

const DATABASE = "astra-activity-files";
const VERSION = 1;

/** One uploaded activity file in IndexedDB (`files` store, keyed by `id`). */
export interface ActivityFileRecord {
  id: string;
  blob: Blob;
  name: string;
  mime: string;
}

function storageError(error?: unknown): Error {
  if (error instanceof DOMException && error.name === "QuotaExceededError") return new Error("브라우저 저장 공간이 부족합니다. 저장 공간을 확보한 뒤 다시 등록하세요.");
  return new Error("이 브라우저에 활동 파일을 저장하거나 불러올 수 없습니다.");
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
      const db = request.result;
      if (!db.objectStoreNames.contains("files")) db.createObjectStore("files", { keyPath: "id" });
    };
    request.onerror = () => reject(storageError(request.error));
    request.onsuccess = () => resolve(request.result);
  });
}

function finish<T>(db: IDBDatabase, mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    let transaction: IDBTransaction | undefined;
    try {
      const tx = db.transaction("files", mode);
      transaction = tx;
      const result = run(tx.objectStore("files"));
      tx.oncomplete = () => {
        db.close();
        resolve(result && result.result);
      };
      tx.onabort = () => {
        db.close();
        reject(storageError(tx.error));
      };
    } catch (error) {
      if (transaction) transaction.abort();
      db.close();
      reject(storageError(error));
    }
  });
}

export async function putActivityFile(id: string | number | null | undefined, file: Blob): Promise<ActivityFileRecord> {
  const key = String(id || "");
  if (!key || !(file instanceof Blob) || !file.size) throw new Error("저장할 파일을 선택하세요.");
  const db = await openDatabase();
  const record: ActivityFileRecord = { id: key, blob: file, name: (file instanceof File && file.name) || "file", mime: file.type || "" };
  await finish(db, "readwrite", (store) => store.put(record));
  return record;
}

export async function getActivityFile(id: string | number | null | undefined): Promise<Blob | null> {
  const key = String(id || "");
  if (!key) return null;
  const db = await openDatabase();
  // The `files` store only holds ActivityFileRecord rows (putActivityFile).
  const record = (await finish(db, "readonly", (store) => store.get(key))) as ActivityFileRecord | undefined;
  return record?.blob || null;
}

export async function deleteActivityFile(id: string | number | null | undefined): Promise<void> {
  const key = String(id || "");
  if (!key) return;
  const db = await openDatabase();
  await finish(db, "readwrite", (store) => store.delete(key));
}

export function bindUploadedVideo(video: HTMLVideoElement | null | undefined, activity: Pick<Activity, "fileId"> | null | undefined): void {
  if (!activity?.fileId || !video || typeof video.addEventListener !== "function") return;
  getActivityFile(activity.fileId).then((blob) => {
    if (!blob || !video.isConnected) return;
    const url = URL.createObjectURL(blob);
    const restore = () => {
      URL.revokeObjectURL(url);
      video.removeAttribute("src");
      if (typeof video.load === "function") video.load();
    };
    video.addEventListener("error", restore, { once: true });
    video.src = url;
  }).catch(() => {});
}
