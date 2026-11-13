// Materials imported from the school's e-Class (`origin: "school"`). The student's `GET …/materials` lists them after the
// native ones with a string `activityId` (`school-<n>`) and files whose `fileId` is a string (`school-file-<n>`) until the
// backend has copied them (then a native number). `GET …/activities/school-<n>` reads the detail from the school, and the
// file content and preview-session addresses take the string id and redirect (303/307) to the native file address.
// Opening a school item changes the student's read state at the school, so nothing here runs on its own: only the
// student's open of a detail, a preview or a download contacts the school. Errors: 409 `school_signin_required` (sign in
// to e-Class again), 409 `file_scanning` with `Retry-After` (the copy waits for the virus scan), 422
// `school_file_unsupported`/`school_file_too_large` (open it on e-Class), 502 `school_unavailable`.
import type { ReactNode } from "react";
import { apiGet, ApiError } from "./api.ts";
import { createResource } from "./remote-cache.ts";
import { requestSchoolSignin } from "./entry-login.tsx";
import { Button } from "../ui/kit.tsx";
import { Icon } from "../ui/icon.tsx";
import type { MaterialList } from "./materials-remote.ts";

type Id = number | string;

/** An imported school material id (`school-<n>`). */
export const SCHOOL_MATERIAL_ID = /^school-[1-9]\d{0,17}$/;
/** An uncopied school file id (`school-file-<n>`); a copied one is a native number. */
export const SCHOOL_FILE_ID = /^school-file-[1-9]\d{0,17}$/;

/** The only origin whose addresses become links: every `schoolUrl` the backend sends is on this host. */
export const SCHOOL_ORIGIN = "https://eclass.seoultech.ac.kr";

/**
 * `url` when it may be rendered as a link to the school (a new tab), else null: `https:` on exactly
 * `eclass.seoultech.ac.kr` (default port, no user info). Other hosts, `http:`, `javascript:` and relative addresses are not links.
 */
export function schoolHref(url: unknown): string | null {
  if (typeof url !== "string" || !url) return null;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  return parsed.origin === SCHOOL_ORIGIN && !parsed.username && !parsed.password ? parsed.href : null;
}

/** A file of an imported material. */
export interface SchoolFile {
  fileId: number | string;
  name: string;
  /** Approximate, from the school's size label. */
  sizeBytes?: number | null;
  extension?: string | null;
  pdf?: boolean;
  summaryPublished?: boolean;
  origin?: "school";
  copied?: boolean;
}

/** An imported material of `GET …/materials`. */
export interface SchoolMaterial {
  origin: "school";
  activityId: string;
  weekNo: number;
  title: string;
  description?: string | null;
  openAt?: string | null;
  closeAt?: string | null;
  published?: boolean;
  phase?: string | null;
  createdAt?: string | null;
  version?: null;
  schoolUrl?: string | null;
  files?: SchoolFile[];
}

/** `GET …/activities/school-<n>`: the school's detail, read only when the student opens it. */
export interface SchoolMaterialDetail extends SchoolMaterial {
  type?: string;
  serverTime?: string;
  /** Plain text; paragraphs as newlines. */
  description?: string | null;
  author?: string | null;
  postedAt?: string | null;
}

/** The imported materials of a `materialLists` response. */
export function schoolMaterialsOf(data: MaterialList | null | undefined): SchoolMaterial[] {
  const items = data && Array.isArray(data.items) ? data.items : [];
  return items.filter((item): item is SchoolMaterial => item.origin === "school" && SCHOOL_MATERIAL_ID.test(String(item.activityId)));
}

/** The files of an imported material whose id is either a school id or a copied native number. */
export function schoolFilesOf(material: SchoolMaterial | null | undefined): SchoolFile[] {
  const files = material && Array.isArray(material.files) ? material.files : [];
  return files.filter((file) => file && (SCHOOL_FILE_ID.test(String(file.fileId)) || (typeof file.fileId === "number" && file.fileId > 0)));
}

/** The `n` of the STack preview address of a school file: `school-file-<n>` as is, or `srv-<id>` once copied. */
export function schoolFileRef(file: Pick<SchoolFile, "fileId">): string {
  return typeof file.fileId === "number" ? `srv-${file.fileId}` : String(file.fileId);
}

export const schoolDetailKey = (courseId: Id, activityId: string): string => `${courseId}|${activityId}`;

/** The school detail of one imported material. Loaded only by the material detail screen, never by a list. */
export const schoolMaterialDetails = createResource<SchoolMaterialDetail, [Id, string]>((courseId, activityId) =>
  apiGet<SchoolMaterialDetail>(`/api/courses/${encodeURIComponent(String(courseId))}/activities/${encodeURIComponent(activityId)}`));

/** What a failed school request means for the student. */
export type SchoolProblem = "signin" | "scanning" | "unsupported" | "too_large" | "unavailable";

export function schoolProblemOf(error: unknown): SchoolProblem | null {
  if (!(error instanceof ApiError)) return null;
  if (error.status === 409 && error.code === "school_signin_required") return "signin";
  if (error.status === 409 && error.code === "file_scanning") return "scanning";
  if (error.status === 422 && error.code === "school_file_unsupported") return "unsupported";
  if (error.status === 422 && error.code === "school_file_too_large") return "too_large";
  if (error.status === 502 && error.code === "school_unavailable") return "unavailable";
  return null;
}

export const SCHOOL_PROBLEM_TEXT: Record<SchoolProblem, string> = {
  signin: "학교 e-Class 연결이 끝났습니다. 학번과 e-Class 비밀번호로 다시 로그인하면 이어서 열 수 있습니다.",
  scanning: "학교에서 받은 파일을 검사하고 있습니다. 잠시 후 다시 시도하세요.",
  unsupported: "이 파일은 STack에서 열 수 없는 형식입니다. 학교 e-Class에서 열어 주세요.",
  too_large: "이 파일은 너무 커서 STack으로 가져올 수 없습니다. 학교 e-Class에서 열어 주세요.",
  unavailable: "학교 e-Class 서버에서 답이 없습니다. 잠시 후 다시 시도하세요.",
};

/** Default and bounds of the wait between two tries while the copy is scanned (seconds). */
const SCAN_WAIT_DEFAULT_S = 5;
const SCAN_WAIT_MAX_S = 30;
/** Tries while scanning, after the first one; with the default `Retry-After: 5` this is one minute. */
export const SCAN_TRIES = 12;

/** Milliseconds to wait after a `file_scanning` answer: its `Retry-After`, within 1–30 s. */
export function scanWaitMs(error: unknown): number {
  const seconds = error instanceof ApiError && error.retryAfter != null ? error.retryAfter : SCAN_WAIT_DEFAULT_S;
  return Math.min(SCAN_WAIT_MAX_S, Math.max(1, seconds)) * 1000;
}

export interface ScanOptions {
  /** False once the screen is gone: stop waiting and throw the last error. */
  alive?: () => boolean;
  /** Called before each wait. */
  onWait?: () => void;
  wait?: (ms: number) => Promise<void>;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Runs `task`, and again after each 409 `file_scanning` answer, waiting its `Retry-After`; at most `SCAN_TRIES` more
 * times. Any other outcome (and the last scanning answer) is returned or thrown as is.
 */
export async function whileScanning<T>(task: () => Promise<T>, { alive, onWait, wait = sleep }: ScanOptions = {}): Promise<T> {
  for (let tries = 0; ; tries += 1) {
    try {
      return await task();
    } catch (error) {
      if (schoolProblemOf(error) !== "scanning" || tries >= SCAN_TRIES || (alive && !alive())) throw error;
      if (onWait) onWait();
      await wait(scanWaitMs(error));
      if (alive && !alive()) throw error;
    }
  }
}

/** "e-Class에서 열기": the school address as a plain new-tab link, or nothing when the address is not the school's. */
export function SchoolLink({ url, kind = "secondary", label = "e-Class에서 열기" }: { url: unknown; kind?: "primary" | "secondary" | "ghost"; label?: string }): ReactNode {
  const href = schoolHref(url);
  if (!href) return null;
  return (
    <a className={`btn btn-${kind} school-link`} href={href} target="_blank" rel="noopener noreferrer" aria-label={`${label} — 학교 e-Class(새 탭)`}>
      {label}<Icon name="ext" />
    </a>
  );
}

/** "다시 로그인": opens the login modal over this screen; `done` reads the item again after the sign-in. */
export function SchoolSigninButton({ done }: { done: () => void }): ReactNode {
  return <Button kind="primary" onClick={() => requestSchoolSignin(done)}>학번·e-Class 비밀번호로 다시 로그인</Button>;
}

/**
 * The state for a failed school request: sign in again, open it on e-Class (422), or try again (502, a scan that took
 * too long). `retry` reads the same item again; `null` when `error` is not a school problem.
 */
export function SchoolProblemNote({ error, schoolUrl, retry, compact = false }: { error: unknown; schoolUrl?: unknown; retry: () => void; compact?: boolean }): ReactNode {
  const problem = schoolProblemOf(error);
  if (!problem) return null;
  let actions: ReactNode;
  if (problem === "signin") actions = <SchoolSigninButton done={retry} />;
  else if (problem === "unsupported" || problem === "too_large") actions = <SchoolLink url={schoolUrl} kind="primary" />;
  else actions = <><Button kind="secondary" onClick={retry}>다시 시도</Button><SchoolLink url={schoolUrl} kind="ghost" /></>;
  return (
    <div className={`school-problem${compact ? " is-compact" : ""}`} role="alert" data-school-problem={problem}>
      <p>{SCHOOL_PROBLEM_TEXT[problem]}</p>
      <div className="btn-row">{actions}</div>
    </div>
  );
}
