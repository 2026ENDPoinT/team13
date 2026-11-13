// Document preview on the ASTRA backend (ADR-0033): `POST …/files/{fileId}/preview-sessions`
// answers `ready` (page count, `pageImageBase`, a cookie valid only for that `/doc/<key>/`
// path), 202 `converting` with `Retry-After`, `failed` with a code, or `unsupported`. Pages are
// `{pageImageBase}NNN.jpg`; when they start failing the cookie has expired and the same request
// renews it. PNG/JPEG files open inline through `…/image`; first-page thumbnails through
// `…/thumbnail/thumb_1`. No viewer token or user id is ever in a URL.
import { el } from "../dom.js";
import { loadingState, errorState } from "../ui/components.js";
import { apiPostStatus, ApiError } from "./api.js";
import { retryLater } from "./remote-cache.js";
import { createDocumentViewer } from "./document-viewer.js";

// replaceChildren() would turn a null child into the text "null"; skip absent parts.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false));
}

const DEFAULT_RETRY_S = 2;
const MAX_WAIT_POLLS = 150; // about five minutes at Retry-After: 2

const filePath = (courseId, fileId) => `/api/courses/${encodeURIComponent(courseId)}/files/${encodeURIComponent(fileId)}`;

export function thumbnailUrl(courseId, fileId) {
  return `${filePath(courseId, fileId)}/thumbnail/thumb_1`;
}

export function imageUrl(courseId, fileId) {
  return `${filePath(courseId, fileId)}/image`;
}

export function pageUrl(base, page) {
  return `${base}${String(page).padStart(3, "0")}.jpg`;
}

/** One preview request. Resolves `{ state, pageCount, pageImageBase, code, retryAfter }`. */
export async function openPreview(courseId, fileId) {
  const { status, retryAfter, body } = await apiPostStatus(`${filePath(courseId, fileId)}/preview-sessions`);
  const view = body || {};
  return {
    state: status === 202 ? "converting" : view.state || "failed",
    pageCount: Number(view.pageCount) || 0,
    pageImageBase: typeof view.pageImageBase === "string" && view.pageImageBase.startsWith("/doc/") ? view.pageImageBase : null,
    code: view.code || null,
    retryAfter: retryAfter || DEFAULT_RETRY_S,
  };
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Repeats the request while the server converts, after `Retry-After` seconds each time.
 * `onState(view)` sees every answer; stops early (resolving the last view) once `alive()` is false.
 */
export async function awaitPreview(courseId, fileId, { onState, alive } = {}) {
  let view = await openPreview(courseId, fileId);
  if (onState) onState(view);
  for (let i = 0; view.state === "converting" && i < MAX_WAIT_POLLS; i += 1) {
    await wait(view.retryAfter * 1000);
    if (alive && !alive()) return view;
    view = await openPreview(courseId, fileId);
    if (onState) onState(view);
  }
  return view;
}

// The closed set of `failed` codes (ADR-0033). The backend turns unknown stored values into `failed`.
const FAILED = {
  encrypted: "암호가 걸린 문서라 미리보기를 만들 수 없습니다. 원본을 내려받아 확인하세요.",
  invalid_document: "문서 파일이 손상되었거나 문서로 읽을 수 없어 미리보기를 만들 수 없습니다. 원본을 내려받아 확인하세요.",
  empty: "쪽이 없는 문서라 미리보기를 만들 수 없습니다.",
  too_many_pages: "200쪽이 넘는 문서는 미리보기를 만들 수 없습니다. 원본을 내려받아 확인하세요.",
  too_large: "파일이 너무 커서 미리보기를 만들 수 없습니다. 원본을 내려받아 확인하세요.",
  timeout: "변환 시간이 너무 오래 걸려 미리보기를 만들지 못했습니다. 원본을 내려받아 확인하세요.",
  unavailable: "변환 서버에 문제가 있어 미리보기를 만들지 못했습니다. 문서 문제는 아니니 잠시 후 다시 열어 주세요.",
  failed: "이 문서의 미리보기를 만들지 못했습니다. 원본을 내려받아 확인하세요.",
};

/** Korean text for a `failed` preview code; an unknown code reads as `failed`. */
export function previewFailure(code) {
  return FAILED[code] || FAILED.failed;
}

/** Korean message for a failed preview request. */
export function previewMessage(error) {
  if (!(error instanceof ApiError)) return error && error.message ? error.message : "미리보기를 열지 못했습니다.";
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "파일을 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "미리보기를 열지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/**
 * Preview of one server file: converting state while the server prepares pages, then a page
 * viewer of `pageImageBase` images. A failing image renews the cookie once (one shared request)
 * and retries. `unsupported` tries the inline image view (PNG/JPEG only) before giving up.
 * `fallback()` may return nodes shown under failed/unsupported states (e.g. a download button).
 */
export function serverPreview({ courseId, fileId, label = "문서", compact = false, fallback } = {}) {
  const root = el("div", { class: "srv-preview", "aria-live": "polite" }, loadingState("미리보기를 여는 중입니다"));
  let base = null;
  let renewing = null;
  const renew = () => {
    renewing ??= openPreview(courseId, fileId)
      .then((view) => {
        if (view.pageImageBase) base = view.pageImageBase;
        return view.state === "ready" ? base : null;
      })
      .catch(() => null)
      .finally(() => setTimeout(() => { renewing = null; }, 1000));
    return renewing;
  };
  const extra = () => (fallback ? fallback() : null);

  function viewer(view) {
    base = view.pageImageBase;
    if (!base) {
      return errorState("쪽 이미지를 열 수 없습니다", "이 서버에는 쪽 이미지 전달이 설정되어 있지 않습니다.", ...[].concat(extra() || []));
    }
    return createDocumentViewer({
      documentId: `server-file:${courseId}:${fileId}`,
      pageCount: view.pageCount,
      label,
      compact,
      renderPage(number, host) {
        const img = el("img", { class: "srv-preview__page", src: pageUrl(base, number), alt: `${number}쪽`, decoding: "async" });
        let retried = false;
        img.addEventListener("error", () => {
          if (retried) {
            img.replaceWith(el("p", { class: "doc-viewer__pending", text: `${number}쪽 이미지를 불러오지 못했습니다.` }));
            return;
          }
          retried = true;
          renew().then((fresh) => {
            if (!img.isConnected) return;
            if (fresh) img.src = pageUrl(fresh, number);
            else img.replaceWith(el("p", { class: "doc-viewer__pending", text: `${number}쪽 이미지를 불러오지 못했습니다.` }));
          });
        });
        fill(host, img);
      },
    });
  }

  function inlineImage() {
    const img = el("img", { class: "srv-preview__image", src: imageUrl(courseId, fileId), alt: label });
    img.addEventListener("error", () => {
      if (!img.isConnected) return;
      img.replaceWith(el("div", {}, errorState("미리보기를 지원하지 않는 형식입니다", "HWP, 텍스트, 압축 파일 등은 미리보기가 없습니다. 원본을 내려받아 확인하세요.", ...[].concat(extra() || []))));
    });
    return el("figure", { class: "srv-preview__figure" }, img, el("figcaption", { class: "tiny", text: label }));
  }

  function paint(view) {
    if (!root.isConnected) return;
    if (view.state === "converting") {
      fill(root, el("div", { class: "empty state-loading", role: "status", "aria-busy": "true" },
        el("span", { class: "spinner", "aria-hidden": "true" }),
        el("strong", { text: "미리보기를 준비하고 있습니다" }),
        el("p", { text: "처음 여는 문서는 쪽 이미지를 만드는 동안 잠시 기다려야 합니다." })));
      return;
    }
    if (view.state === "ready") fill(root, viewer(view));
    else if (view.state === "unsupported") fill(root, inlineImage());
    else fill(root, errorState("미리보기를 만들지 못했습니다", previewFailure(view.code), ...[].concat(extra() || [])));
  }

  queueMicrotask(() => {
    if (!root.isConnected) return;
    awaitPreview(courseId, fileId, { onState: paint, alive: () => root.isConnected }).then((view) => {
      if (view.state === "converting" && root.isConnected) {
        fill(root, errorState("미리보기 준비가 오래 걸립니다", "잠시 후 다시 열어 주세요.", ...[].concat(extra() || [])));
      }
    }, (error) => {
      if (root.isConnected) fill(root, errorState("미리보기를 열지 못했습니다", previewMessage(error), ...[].concat(extra() || [])));
    });
  });
  return root;
}

/** First-page thumbnail for a list card; removes itself when the server has none (404). */
export function thumbnailImage(courseId, fileId, className = "srv-thumb") {
  const img = el("img", { class: className, src: thumbnailUrl(courseId, fileId), alt: "", loading: "lazy", decoding: "async" });
  img.addEventListener("error", () => img.remove());
  return img;
}
