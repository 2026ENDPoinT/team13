// Uploads on the ASTRA backend (`POST /api/uploads`, `GET /api/uploads/{id}`) and the bytes of
// an attached file (`GET /api/courses/{courseId}/files/{fileId}/content`, or for a parent
// outside any course `GET /api/files/{fileId}/content`). An upload is `quarantined` until the
// malware scan answers `clean` or `rejected`; only `clean` uploads can be attached. Downloaded
// bytes become a short-lived object URL and are never stored.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button } from "../ui/kit.tsx";
import { apiGet, apiSend, apiUpload, apiBlob, ApiError, newRequestKey } from "./api.ts";
import { retryLater } from "./remote-cache.ts";

/** Malware-scan state of an upload: only `clean` uploads can be attached. */
export type UploadState = "quarantined" | "clean" | "rejected";

/** `POST /api/uploads` and `GET /api/uploads/{id}` answer. */
export interface UploadView {
  uploadId: number;
  state: UploadState;
  name: string;
  sizeBytes: number;
}

/** A file attached to something on the server, as `downloadControl` needs it. */
export interface DownloadFile {
  fileId: number | string;
  name: string;
}

export const SCAN_POLL_MS = 1500;
const SCAN_POLL_LIMIT = 80;
const URL_LIFETIME_MS = 60000;

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Sends one file for `purpose` in backend course `courseId`; resolves with the upload view.
 * `courseId` null is an upload outside any course (`community_notice`, `community_material`,
 * `inquiry_attachment`, `message_attachment`): the multipart then has no `courseId` part.
 */
export function uploadFile(file: File, courseId: number | string | null, purpose: string): Promise<UploadView> {
  const form = new FormData();
  form.append("file", file, String(file.name || "file"));
  if (courseId != null) form.append("courseId", String(courseId));
  form.append("purpose", String(purpose));
  return apiUpload<UploadView>("/api/uploads", form, { idempotencyKey: newRequestKey() });
}

export function getUpload(uploadId: number | string): Promise<UploadView> {
  return apiGet<UploadView>(`/api/uploads/${encodeURIComponent(String(uploadId))}`);
}

/** Deletes an upload that was not attached (it would expire on its own). Never throws. */
export function discardUpload(uploadId: number | string): Promise<unknown> {
  return Promise.resolve()
    .then(() => apiSend("DELETE", `/api/uploads/${encodeURIComponent(String(uploadId))}`))
    .catch(() => null);
}

export interface AwaitScanOptions {
  /** Sees each new view. */
  onState?: (view: UploadView) => void;
  /** Polling stops early (resolving with the current view) once this returns false. */
  alive?: () => boolean;
}

/**
 * Polls while the scan holds the upload. Resolves with the last view: `clean`, `rejected`, or
 * still `quarantined` after the poll limit. `onState(view)` sees each new view; polling stops
 * early (resolving with the current view) once `alive()` returns false.
 */
export async function awaitScan(upload: UploadView, { onState, alive }: AwaitScanOptions = {}): Promise<UploadView> {
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
export function uploadFailure(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "파일을 올리지 못했습니다. 잠시 후 다시 시도하세요.";
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
export async function fileBlob(courseId: number | string | null, fileId: number | string): Promise<Blob | null> {
  const path = courseId == null
    ? `/api/files/${encodeURIComponent(String(fileId))}/content`
    : `/api/courses/${encodeURIComponent(String(courseId))}/files/${encodeURIComponent(String(fileId))}/content`;
  try {
    return await apiBlob(path);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/** Hands a Blob to the browser as a download named `name`, through a temporary object URL. */
export function saveBlob(blob: Blob, name: string | null | undefined): void {
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
 * Renders the button followed by its live status line (`span.tiny`) for progress and failures.
 */
export function downloadControl(courseId: number | string | null, file: DownloadFile): ReactNode {
  return <DownloadControl courseId={courseId} file={file} />;
}

function DownloadControl({ courseId, file }: { courseId: number | string | null; file: DownloadFile }) {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  const download = () => {
    setBusy(true);
    setNote("내려받는 중입니다.");
    fileBlob(courseId, file.fileId).then((blob) => {
      if (!blob) {
        if (live.current) setNote("파일을 찾을 수 없거나 받을 권한이 없습니다.");
        return;
      }
      saveBlob(blob, file.name);
      if (live.current) setNote("");
    }, (error: unknown) => {
      if (!live.current) return;
      setNote(error instanceof ApiError && error.status === 429 ? retryLater(error)
        : error instanceof ApiError && error.status === 0 ? error.message : "파일을 내려받지 못했습니다. 잠시 후 다시 시도하세요.");
    }).finally(() => {
      if (live.current) setBusy(false);
    });
  };
  return (
    <>
      <Button kind="secondary" aria-label={`${file.name} 내려받기`} disabled={busy} onClick={download}>내려받기</Button>
      <span className="tiny" role="status" aria-live="polite">{note}</span>
    </>
  );
}

/** "1.2MB" / "34KB" for a byte count. */
export function sizeText(bytes: number | string | null | undefined): string {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return "";
  return n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`;
}
