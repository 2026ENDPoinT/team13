// Uploads on the ASTRA backend (`POST /api/uploads`, `GET /api/uploads/{id}`) and the bytes of
// an attached file (`GET /api/courses/{courseId}/files/{fileId}/content`, or for a parent
// outside any course `GET /api/files/{fileId}/content`). An upload is `quarantined` until the
// malware scan answers `clean` or `rejected`; only `clean` uploads can be attached. Downloaded
// bytes become a short-lived object URL and are never stored.
import { el } from "../dom.js";
import { btn } from "../ui/components.js";
import { apiGet, apiSend, apiUpload, apiBlob, ApiError, newRequestKey } from "./api.js";
import { retryLater } from "./remote-cache.js";

export const SCAN_POLL_MS = 1500;
const SCAN_POLL_LIMIT = 80;
const URL_LIFETIME_MS = 60000;

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Sends one file for `purpose` in backend course `courseId`; resolves with the upload view.
 * `courseId` null is an upload outside any course (`community_notice`, `community_material`,
 * `inquiry_attachment`, `message_attachment`): the multipart then has no `courseId` part.
 */
export function uploadFile(file, courseId, purpose) {
  const form = new FormData();
  form.append("file", file, String(file.name || "file"));
  if (courseId != null) form.append("courseId", String(courseId));
  form.append("purpose", String(purpose));
  return apiUpload("/api/uploads", form, { idempotencyKey: newRequestKey() });
}

export function getUpload(uploadId) {
  return apiGet(`/api/uploads/${encodeURIComponent(uploadId)}`);
}

/** Deletes an upload that was not attached (it would expire on its own). Never throws. */
export function discardUpload(uploadId) {
  return Promise.resolve()
    .then(() => apiSend("DELETE", `/api/uploads/${encodeURIComponent(uploadId)}`))
    .catch(() => null);
}

/**
 * Polls while the scan holds the upload. Resolves with the last view: `clean`, `rejected`, or
 * still `quarantined` after the poll limit. `onState(view)` sees each new view; polling stops
 * early (resolving with the current view) once `alive()` returns false.
 */
export async function awaitScan(upload, { onState, alive } = {}) {
  let current = upload;
  for (let i = 0; current && current.state === "quarantined" && i < SCAN_POLL_LIMIT; i += 1) {
    await wait(SCAN_POLL_MS);
    if (alive && !alive()) return current;
    current = await getUpload(current.uploadId);
    if (onState) onState(current);
  }
  return current;
}

/** Korean message for a failed upload request (not for a `rejected` scan result). */
export function uploadFailure(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "파일을 올리지 못했습니다. 잠시 후 다시 시도하세요.";
  switch (error.status) {
    case 400: return error.detail || "파일을 올리지 못했습니다. 파일을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "파일을 올릴 권한이 없습니다.";
    case 413: return "파일이 너무 큽니다. 50MB 이하의 파일을 올리세요.";
    case 422: return error.code === "extension_not_allowed"
      ? "올릴 수 없는 파일 형식입니다."
      : error.code === "empty_file" ? "빈 파일은 올릴 수 없습니다." : "파일 내용이 확장자와 맞지 않거나 올릴 수 없는 파일입니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "파일을 올리지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/**
 * Bytes of a file attached to something in `courseId` (null: a parent outside any course), or
 * null when it is gone or not permitted (404).
 */
export async function fileBlob(courseId, fileId) {
  const path = courseId == null
    ? `/api/files/${encodeURIComponent(fileId)}/content`
    : `/api/courses/${encodeURIComponent(courseId)}/files/${encodeURIComponent(fileId)}/content`;
  try {
    return await apiBlob(path);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/** Hands a Blob to the browser as a download named `name`, through a temporary object URL. */
export function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = String(name || "file");
  link.rel = "noopener";
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
  // Give the download time to consume the URL.
  setTimeout(() => URL.revokeObjectURL(url), URL_LIFETIME_MS);
}

/**
 * "내려받기" for one attached file: the bytes come through `fileBlob` and leave as a Blob URL.
 * Returns `{ button, note }`; `note` is a live status line for progress and failures.
 */
export function downloadControl(courseId, file) {
  const note = el("span", { class: "tiny", role: "status", "aria-live": "polite" });
  const button = btn("내려받기", { "aria-label": `${file.name} 내려받기` }, "secondary");
  button.addEventListener("click", () => {
    button.disabled = true;
    note.textContent = "내려받는 중입니다.";
    fileBlob(courseId, file.fileId).then((blob) => {
      if (!blob) {
        if (note.isConnected) note.textContent = "파일을 찾을 수 없거나 받을 권한이 없습니다.";
        return;
      }
      saveBlob(blob, file.name);
      if (note.isConnected) note.textContent = "";
    }, (error) => {
      if (!note.isConnected) return;
      note.textContent = error instanceof ApiError && error.status === 429 ? retryLater(error)
        : error instanceof ApiError && error.status === 0 ? error.message : "파일을 내려받지 못했습니다. 잠시 후 다시 시도하세요.";
    }).finally(() => {
      if (button.isConnected) button.disabled = false;
    });
  });
  return { button, note };
}

/** "1.2MB" / "34KB" for a byte count. */
export function sizeText(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return "";
  return n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`;
}
