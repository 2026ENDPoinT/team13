const DATABASE = "astra-activity-files";
const VERSION = 1;

function storageError(error) {
  if (error?.name === "QuotaExceededError") return new Error("브라우저 저장 공간이 부족합니다. 저장 공간을 확보한 뒤 다시 등록하세요.");
  return new Error("이 브라우저에 활동 파일을 저장하거나 불러올 수 없습니다.");
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(storageError());
      return;
    }
    let request;
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

function finish(db, mode, run) {
  return new Promise((resolve, reject) => {
    let transaction;
    try {
      transaction = db.transaction("files", mode);
      const result = run(transaction.objectStore("files"));
      transaction.oncomplete = () => {
        db.close();
        resolve(result && result.result);
      };
      transaction.onabort = () => {
        db.close();
        reject(storageError(transaction.error));
      };
    } catch (error) {
      if (transaction) transaction.abort();
      db.close();
      reject(storageError(error));
    }
  });
}

export async function putActivityFile(id, file) {
  const key = String(id || "");
  if (!key || !(file instanceof Blob) || !file.size) throw new Error("저장할 파일을 선택하세요.");
  const db = await openDatabase();
  const record = { id: key, blob: file, name: file.name || "file", mime: file.type || "" };
  await finish(db, "readwrite", (store) => store.put(record));
  return record;
}

export async function getActivityFile(id) {
  const key = String(id || "");
  if (!key) return null;
  const db = await openDatabase();
  const record = await finish(db, "readonly", (store) => store.get(key));
  return record?.blob || null;
}

export async function deleteActivityFile(id) {
  const key = String(id || "");
  if (!key) return;
  const db = await openDatabase();
  await finish(db, "readwrite", (store) => store.delete(key));
}

export function bindUploadedVideo(video, activity) {
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
