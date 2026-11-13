// Document preview on the ASTRA backend (ADR-0033): `POST …/files/{fileId}/preview-sessions`
// answers `ready` (page count, `pageImageBase`, a cookie valid only for that `/doc/<key>/`
// path), 202 `converting` with `Retry-After`, `failed` with a code, or `unsupported`. Pages are
// `{pageImageBase}NNN.jpg`; when they start failing the cookie has expired and the same request
// renews it. PNG/JPEG files open inline through `…/image`; first-page thumbnails through
// `…/thumbnail/thumb_1`. No viewer token or user id is ever in a URL.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ErrorState, LoadingState } from "../ui/kit.tsx";
import { apiPostStatus, ApiError } from "./api.ts";
import { retryLater } from "./remote-cache.ts";
import { createDocumentViewer } from "./document-viewer.tsx";

const DEFAULT_RETRY_S = 2;
const MAX_WAIT_POLLS = 150; // about five minutes at Retry-After: 2

type Id = number | string;

const filePath = (courseId: Id, fileId: Id) => `/api/courses/${encodeURIComponent(String(courseId))}/files/${encodeURIComponent(String(fileId))}`;

export function thumbnailUrl(courseId: Id, fileId: Id): string {
  return `${filePath(courseId, fileId)}/thumbnail/thumb_1`;
}

export function imageUrl(courseId: Id, fileId: Id): string {
  return `${filePath(courseId, fileId)}/image`;
}

export function pageUrl(base: string, page: number): string {
  return `${base}${String(page).padStart(3, "0")}.jpg`;
}

export type PreviewState = "ready" | "converting" | "failed" | "unsupported";

/** One preview answer, normalized. */
export interface PreviewView {
  state: PreviewState;
  pageCount: number;
  pageImageBase: string | null;
  code: string | null;
  /** Seconds to wait before asking again while `converting`. */
  retryAfter: number;
}

/** `POST …/preview-sessions` body as the server sends it. */
interface PreviewSessionBody {
  state?: PreviewState;
  pageCount?: number;
  pageImageBase?: unknown;
  code?: string | null;
}

/** One preview request. */
export async function openPreview(courseId: Id, fileId: Id): Promise<PreviewView> {
  const { status, retryAfter, body } = await apiPostStatus<PreviewSessionBody>(`${filePath(courseId, fileId)}/preview-sessions`);
  const view = body || {};
  return {
    state: status === 202 ? "converting" : view.state || "failed",
    pageCount: Number(view.pageCount) || 0,
    pageImageBase: typeof view.pageImageBase === "string" && view.pageImageBase.startsWith("/doc/") ? view.pageImageBase : null,
    code: view.code || null,
    retryAfter: retryAfter || DEFAULT_RETRY_S,
  };
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export interface AwaitPreviewOptions {
  onState?: (view: PreviewView) => void;
  alive?: () => boolean;
}

/**
 * Repeats the request while the server converts, after `Retry-After` seconds each time.
 * `onState(view)` sees every answer; stops early (resolving the last view) once `alive()` is false.
 */
export async function awaitPreview(courseId: Id, fileId: Id, { onState, alive }: AwaitPreviewOptions = {}): Promise<PreviewView> {
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
const FAILED: Record<string, string> = {
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
export function previewFailure(code: string | null | undefined): string {
  return (code && FAILED[code]) || FAILED.failed;
}

/** Korean message for a failed preview request. */
export function previewMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "미리보기를 열지 못했습니다.";
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "파일을 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "미리보기를 열지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

export interface ServerPreviewOptions {
  courseId: Id;
  fileId: Id;
  label?: string;
  compact?: boolean;
  /** Shown under failed/unsupported states (e.g. a download button). */
  fallback?: () => ReactNode;
}

/**
 * Preview of one server file: converting state while the server prepares pages, then a page
 * viewer of `pageImageBase` images. A failing image renews the cookie once (one shared request)
 * and retries. `unsupported` tries the inline image view (PNG/JPEG only) before giving up.
 * `fallback()` may return content shown under failed/unsupported states (e.g. a download button).
 */
export function serverPreview(options: ServerPreviewOptions): ReactNode {
  return <ServerPreview key={`${options.courseId}:${options.fileId}`} {...options} />;
}

type Phase =
  | { kind: "opening" }
  | { kind: "view"; view: PreviewView }
  | { kind: "slow" }
  | { kind: "error"; error: unknown };

function ServerPreview({ courseId, fileId, label = "문서", compact = false, fallback }: ServerPreviewOptions) {
  const [phase, setPhase] = useState<Phase>({ kind: "opening" });
  const base = useRef<string | null>(null);
  const renewing = useRef<Promise<string | null> | null>(null);

  useEffect(() => {
    let live = true;
    awaitPreview(courseId, fileId, {
      onState: (view) => {
        if (live) setPhase({ kind: "view", view });
      },
      alive: () => live,
    }).then((view) => {
      if (view.state === "converting" && live) setPhase({ kind: "slow" });
    }, (error: unknown) => {
      if (live) setPhase({ kind: "error", error });
    });
    return () => {
      live = false;
    };
  }, [courseId, fileId]);

  /** Renews the page cookie (one shared request); resolves the fresh base, or null. */
  const renew = () => {
    renewing.current ??= openPreview(courseId, fileId)
      .then((view) => {
        if (view.pageImageBase) base.current = view.pageImageBase;
        return view.state === "ready" ? base.current : null;
      })
      .catch(() => null)
      .finally(() => setTimeout(() => {
        renewing.current = null;
      }, 1000));
    return renewing.current;
  };
  const extra = fallback ? fallback() : null;

  let content: ReactNode;
  if (phase.kind === "opening") content = <LoadingState title="미리보기를 여는 중입니다" />;
  else if (phase.kind === "slow") content = <ErrorState title="미리보기 준비가 오래 걸립니다" body="잠시 후 다시 열어 주세요." actions={extra} />;
  else if (phase.kind === "error") content = <ErrorState title="미리보기를 열지 못했습니다" body={previewMessage(phase.error)} actions={extra} />;
  else {
    const { view } = phase;
    if (view.state === "converting") {
      content = (
        <div className="empty state-loading" role="status" aria-busy="true">
          <span className="spinner" aria-hidden="true" />
          <strong>미리보기를 준비하고 있습니다</strong>
          <p>처음 여는 문서는 쪽 이미지를 만드는 동안 잠시 기다려야 합니다.</p>
        </div>
      );
    } else if (view.state === "ready") {
      base.current = view.pageImageBase;
      content = base.current
        ? createDocumentViewer({
          documentId: `server-file:${courseId}:${fileId}`,
          pageCount: view.pageCount,
          label,
          compact,
          renderPage: (number: number) => <PreviewPage key={number} number={number} base={base.current ?? ""} renew={renew} />,
        })
        : <ErrorState title="쪽 이미지를 열 수 없습니다" body="이 서버에는 쪽 이미지 전달이 설정되어 있지 않습니다." actions={extra} />;
    } else if (view.state === "unsupported") {
      content = <InlineImage src={imageUrl(courseId, fileId)} label={label} extra={extra} />;
    } else {
      content = <ErrorState title="미리보기를 만들지 못했습니다" body={previewFailure(view.code)} actions={extra} />;
    }
  }
  return <div className="srv-preview" aria-live="polite">{content}</div>;
}

/** One page image; a failing image renews the cookie once and retries, then says it could not load. */
function PreviewPage({ number, base, renew }: { number: number; base: string; renew: () => Promise<string | null> }) {
  const [src, setSrc] = useState(() => pageUrl(base, number));
  const [failed, setFailed] = useState(false);
  const retried = useRef(false);
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  if (failed) return <p className="doc-viewer__pending">{`${number}쪽 이미지를 불러오지 못했습니다.`}</p>;
  const onError = () => {
    if (retried.current) {
      setFailed(true);
      return;
    }
    retried.current = true;
    void renew().then((fresh) => {
      if (!live.current) return;
      if (fresh) setSrc(pageUrl(fresh, number));
      else setFailed(true);
    });
  };
  return <img className="srv-preview__page" src={src} alt={`${number}쪽`} decoding="async" onError={onError} />;
}

/** PNG/JPEG shown inline; any other format fails to load and says the format has no preview. */
function InlineImage({ src, label, extra }: { src: string; label: string; extra: ReactNode }) {
  const [failed, setFailed] = useState(false);
  return (
    <figure className="srv-preview__figure">
      {failed
        ? <div><ErrorState title="미리보기를 지원하지 않는 형식입니다" body="HWP, 텍스트, 압축 파일 등은 미리보기가 없습니다. 원본을 내려받아 확인하세요." actions={extra} /></div>
        : <img className="srv-preview__image" src={src} alt={label} onError={() => setFailed(true)} />}
      <figcaption className="tiny">{label}</figcaption>
    </figure>
  );
}

/** First-page thumbnail for a list card; renders nothing once the server has none (404). */
export function thumbnailImage(courseId: Id, fileId: Id, className = "srv-thumb"): ReactNode {
  return <ThumbnailImage key={`${courseId}:${fileId}`} src={thumbnailUrl(courseId, fileId)} className={className} />;
}

function ThumbnailImage({ src, className }: { src: string; className: string }) {
  const [failed, setFailed] = useState(false);
  if (failed) return null;
  return <img className={className} src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />;
}
