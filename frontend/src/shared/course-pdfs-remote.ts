// Course PDFs on the ASTRA backend. course-pdfs.js delegates here while the
// backend connection is `ready`. Records keep the IndexedDB record shape so the
// study room, document viewer and professor screens render them unchanged.
// Downloaded originals stay in memory (small LRU), never in IndexedDB.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { courseIdFor, subscribe } from "./backend.ts";
import { uploadFile, awaitScan, fileBlob } from "./uploads-remote.tsx";
import type { PageSummary } from "./page-summary.tsx";
import type { UploadView } from "./uploads-remote.tsx";
import type { Material, MaterialFile, MaterialList, CourseFileRow } from "./materials-remote.ts";

type Id = number | string;

/** A server PDF in the IndexedDB record shape of course-pdfs.ts. */
export interface RemotePdfRecord {
  id: string;
  remote: true;
  fileId: number;
  activityId: number;
  backendCourseId: Id;
  courseId: string;
  title: string;
  weekNo: number;
  published: boolean;
  description: string;
  fileName: string;
  size: number | undefined;
  uploadedAt: string | undefined;
  summaryPublished: boolean;
  pageCount: number | null;
  pages: unknown[];
  overview: unknown[];
  keywords: unknown[];
  status: "ready";
}

/** What the material fields of a record come from: a material, or a file-collection row. */
interface RecordSource {
  activityId: number;
  title: string;
  weekNo: number;
  published?: boolean;
  description?: string | null;
  createdAt?: string;
}

/** A material as `POST …/materials` returns it. */
export interface CreatedMaterial extends Material {
  createdAt?: string;
}

/** The server file a summary call addresses. */
export interface SummaryTarget {
  backendCourseId: Id;
  fileId: Id;
}

/** A published page summary or a job's candidate (`summaryView` input). */
export interface ServerSummary extends PageSummary {
  publishedAt?: string | null;
}

/** A summary job. */
export interface SummaryJob {
  id: number;
  state: string;
  stage?: string | null;
  pagesDone?: number | null;
  pagesTotal?: number | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  cancelRequested?: boolean;
}

export interface SavePdfInput {
  file: File;
  courseId: Id | null | undefined;
  title: string | null | undefined;
  weekNo: number | string | null | undefined;
}

export interface SaveUploadsInput {
  uploads: UploadView[] | null | undefined;
  courseId: Id | null | undefined;
  title: string | null | undefined;
  weekNo: number | string | null | undefined;
}

interface MaterialBody {
  weekNo: number;
  title: string;
  uploadIds: number[];
}

export const REMOTE_PREFIX = "srv-";
const BLOB_CACHE = 4;

const records = new Map<string, RemotePdfRecord>(); // record id -> record (from the latest list)
const blobs = new Map<string, Blob>(); // record id -> Blob, insertion order = LRU order

// A create that may have reached the server without an answer (no response, 502-504, or the server still working on
// the same key) keeps its key on a same-content retry, so the server returns what it may already have made.
let pendingCreate: { fingerprint: string; key: string } | null = null; // materials: { fingerprint, key }
// The clean upload of a `saveRemotePdf` whose create had an uncertain answer. Retrying the same file, title and week
// reuses it instead of uploading again, so the retry sends the same body with the same key.
let pendingPdf: { fingerprint: string; upload: UploadView } | null = null; // { fingerprint, upload }
const pendingJobs = new Map<string, string>(); // summary-job start: "<courseId>:<fileId>" -> key

// A different login may not read the same files: drop everything on session change, the keys of creates that
// ended without an answer included (they belong to the previous login). An answer that arrives after the change
// (`generation` moved on) is not kept.
let session = "";
let generation = 0;
subscribe((next) => {
  const key = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (key === session) return;
  session = key;
  generation += 1;
  records.clear();
  blobs.clear();
  pendingCreate = null;
  pendingPdf = null;
  pendingJobs.clear();
});

export function isRemotePdfId(id: unknown): boolean {
  return String(id || "").startsWith(REMOTE_PREFIX);
}

export function remoteRecord(id: unknown): RemotePdfRecord | null {
  return records.get(String(id || "")) || null;
}

/** Korean form message for an API failure while registering a material. */
export function uploadMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "자료를 서버에 등록하지 못했습니다. 잠시 후 다시 시도하세요.";
  if (error.code === "idempotency_in_progress") return "같은 등록을 처리하고 있습니다. 잠시 후 자료 목록을 확인하세요.";
  if (error.code === "idempotency_conflict") return "앞서 보낸 등록과 내용이 달라 처리하지 않았습니다. 다시 등록하세요.";
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다. 로그인한 뒤 다시 등록하세요.";
    case 404: return "이 과목에 자료를 등록할 권한이 없습니다.";
    case 409: return "업로드한 파일을 아직 사용할 수 없습니다. 잠시 후 다시 등록하세요.";
    case 413: return "파일이 너무 큽니다. 50MB 이하의 PDF를 등록하세요.";
    case 422: return "PDF 형식이 아니거나 파일 내용이 확장자와 맞지 않습니다. 원본 PDF를 확인한 뒤 다시 등록하세요.";
    case 429: return `요청이 많습니다. ${error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
    case 0: return error.message;
    default: return "자료를 서버에 등록하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

function toRecord(material: RecordSource, file: MaterialFile, kj: string, backendCourseId: Id): RemotePdfRecord {
  return {
    id: `${REMOTE_PREFIX}${file.fileId}`,
    remote: true,
    fileId: file.fileId,
    activityId: material.activityId,
    backendCourseId,
    courseId: kj,
    title: material.title,
    weekNo: material.weekNo,
    published: material.published !== false,
    description: typeof material.description === "string" ? material.description : "",
    fileName: file.name,
    size: file.sizeBytes,
    uploadedAt: material.createdAt,
    // 학생에게 보이는 요약 게시본이 있는지(서버 `summaryPublished`). 요약 화면은 true일 때만 요약을 읽는다.
    summaryPublished: file.summaryPublished === true,
    pageCount: null,
    pages: [],
    overview: [],
    keywords: [],
    status: "ready",
  };
}

function remember(record: RemotePdfRecord): RemotePdfRecord {
  records.set(record.id, record);
  return record;
}

async function waitClean(upload: UploadView): Promise<UploadView> {
  const current = await awaitScan(upload);
  if (!current || current.state === "quarantined") throw new Error("파일 검사가 끝나지 않았습니다. 잠시 후 다시 등록하세요.");
  if (current.state !== "clean") throw new Error("업로드한 파일이 서버 검사를 통과하지 못했습니다. 원본 PDF를 확인한 뒤 다시 등록하세요.");
  return current;
}

const uncertainCreate = (error: unknown): boolean => error instanceof ApiError
  && ([0, 502, 503, 504].includes(error.status) || (error.status === 409 && error.code === "idempotency_in_progress"));
const UNCERTAIN_CREATE = "등록 결과를 확인하지 못했습니다. 같은 내용으로 다시 등록하면 한 번만 등록됩니다.";

/** `POST …/materials` with an `Idempotency-Key` that a same-body retry after an uncertain answer reuses. */
async function createMaterial(backendCourseId: Id, body: MaterialBody): Promise<CreatedMaterial | null> {
  const fingerprint = JSON.stringify([backendCourseId, body]);
  const key = pendingCreate && pendingCreate.fingerprint === fingerprint ? pendingCreate.key : newRequestKey();
  pendingCreate = { fingerprint, key };
  try {
    const material = await apiSend<CreatedMaterial | null>("POST", `/api/courses/${encodeURIComponent(backendCourseId)}/materials`, body, { idempotencyKey: key });
    pendingCreate = null;
    return material;
  } catch (error) {
    if (!uncertainCreate(error)) pendingCreate = null;
    throw error;
  }
}

export async function saveRemotePdf({ file, courseId, title, weekNo }: SavePdfInput): Promise<RemotePdfRecord> {
  const kj = String(courseId || "").trim();
  const backendCourseId = courseIdFor(kj);
  if (!backendCourseId) throw new Error("이 과목은 ASTRA 서버에 없어 자료를 등록할 수 없습니다.");
  const label = String(title || "").trim().slice(0, 300);
  const week = Number(weekNo);
  const fingerprint = JSON.stringify([backendCourseId, file && file.name, file && file.size, file && file.lastModified, label, week]);
  try {
    const upload = pendingPdf && pendingPdf.fingerprint === fingerprint
      ? pendingPdf.upload
      : await waitClean(await uploadFile(file, backendCourseId, "lecture_material"));
    let material;
    try {
      material = await createMaterial(backendCourseId, {
        weekNo: Number.isInteger(week) && week >= 1 && week <= 60 ? week : 1,
        title: label,
        uploadIds: [upload.uploadId],
      });
      pendingPdf = null;
    } catch (error) {
      pendingPdf = uncertainCreate(error) ? { fingerprint, upload } : null;
      throw error;
    }
    const files = material && Array.isArray(material.files) ? material.files : [];
    const pdf = files.find((item) => item.pdf) || files[0];
    if (!pdf || !material) throw new Error("서버가 등록한 파일 정보를 돌려주지 않았습니다. 학습실에서 자료를 확인하세요.");
    return remember(toRecord(material, pdf, kj, backendCourseId));
  } catch (error) {
    if (pendingPdf && uncertainCreate(error)) throw new Error(UNCERTAIN_CREATE);
    throw new Error(uploadMessage(error));
  }
}

/**
 * Registers one material from uploads the form already holds (file box picks, `uploadsFromFilebox`
 * views). Waits for each scan like `saveRemotePdf`, then `POST …/materials` with all `uploadIds` and an
 * `Idempotency-Key`. Resolves `{ material, records }`; `records` are the study-room records of its PDFs.
 */
export async function saveRemoteMaterialFromUploads({ uploads, courseId, title, weekNo }: SaveUploadsInput): Promise<{ material: CreatedMaterial | null; records: RemotePdfRecord[] }> {
  const kj = String(courseId || "").trim();
  const backendCourseId = courseIdFor(kj);
  if (!backendCourseId) throw new Error("이 과목은 ASTRA 서버에 없어 자료를 등록할 수 없습니다.");
  const label = String(title || "").trim().slice(0, 300);
  const week = Number(weekNo);
  const body: MaterialBody = {
    weekNo: Number.isInteger(week) && week >= 1 && week <= 60 ? week : 1,
    title: label,
    uploadIds: [],
  };
  try {
    const clean = await Promise.all((Array.isArray(uploads) ? uploads : []).map((upload) => waitClean(upload)));
    body.uploadIds = clean.map((upload) => upload.uploadId);
    const material = await createMaterial(backendCourseId, body);
    const files = material && Array.isArray(material.files) ? material.files : [];
    const records = !material ? [] : files.filter((file) => file && file.pdf).map((file) => remember(toRecord(material, file, kj, backendCourseId)));
    return { material, records };
  } catch (error) {
    if (pendingCreate && uncertainCreate(error)) throw new Error(UNCERTAIN_CREATE);
    throw new Error(uploadMessage(error));
  }
}

/** Study-room records for the PDFs of server materials (the `items` of `GET …/materials`, or one activity detail). */
export function rememberMaterials(materials: (RecordSource & { files?: MaterialFile[] | null })[] | null | undefined, kj: string, backendCourseId: Id): RemotePdfRecord[] {
  const out: RemotePdfRecord[] = [];
  for (const material of Array.isArray(materials) ? materials : []) {
    for (const file of Array.isArray(material.files) ? material.files : []) {
      if (file && file.pdf) out.push(remember(toRecord(material, file, kj, backendCourseId)));
    }
  }
  return out.sort((a, b) => String(b.uploadedAt).localeCompare(String(a.uploadedAt)));
}

/**
 * 강의실 파일모음 행(`GET …/files`의 `items`)의 PDF를 레코드로 기억한다. 미리보기 화면의 제목과 요약 게시 여부가 이 레코드를
 * 쓴다. 같은 파일을 자료 목록이 이미 기억했으면 그 레코드(설명·등록 시각이 있다)를 그대로 둔다.
 */
export function rememberCourseFiles(rows: CourseFileRow[] | null | undefined, kj: string, backendCourseId: Id): void {
  for (const row of Array.isArray(rows) ? rows : []) {
    const file = row && row.file;
    if (!file || !file.pdf) continue;
    const known = records.get(`${REMOTE_PREFIX}${file.fileId}`);
    if (known) known.summaryPublished = file.summaryPublished === true;
    else remember(toRecord({ activityId: row.activityId, title: row.activityTitle, weekNo: row.weekNo }, file, kj, backendCourseId));
  }
}

export async function listRemotePdfs(courseId: Id | null | undefined): Promise<RemotePdfRecord[]> {
  const kj = String(courseId || "");
  const backendCourseId = courseIdFor(kj);
  if (!backendCourseId) return [];
  const before = generation;
  const list = await apiGet<MaterialList | null>(`/api/courses/${encodeURIComponent(backendCourseId)}/materials`);
  if (before !== generation) return [];
  // Materials imported from the school's e-Class have no study-room records: their files have no summaries, and an
  // uncopied file has a string id the study room cannot address.
  const native = (list && Array.isArray(list.items) ? list.items : []).filter((item): item is Material => item.origin !== "school");
  return rememberMaterials(native, kj, backendCourseId);
}

/** Forgets the records and cached originals of a material that was changed or deleted. */
export function forgetRemoteMaterial(activityId: number): void {
  for (const [id, record] of [...records]) {
    if (record.activityId !== activityId) continue;
    records.delete(id);
    blobs.delete(id);
  }
}

export async function getRemotePdfBlob(id: unknown): Promise<Blob | null> {
  const key = String(id || "");
  if (blobs.has(key)) {
    const hit = blobs.get(key);
    if (hit) {
      blobs.delete(key);
      blobs.set(key, hit);
      return hit;
    }
  }
  const record = records.get(key);
  if (!record) return null;
  const before = generation;
  const raw = await fileBlob(record.backendCourseId, record.fileId);
  if (!raw || before !== generation) return null;
  // The server sends attachment bytes with its detected type; the viewer needs a PDF Blob.
  const blob = raw.slice(0, raw.size, "application/pdf");
  blobs.set(key, blob);
  for (const oldest of blobs.keys()) {
    if (blobs.size <= BLOB_CACHE) break;
    blobs.delete(oldest);
  }
  return blob;
}

/** Server PDFs are deleted with their material (materials-remote.js), not one by one. */
export async function deleteRemotePdf(): Promise<never> {
  throw new Error("서버에 등록한 자료는 강의자료 화면의 서버 자료 목록에서 삭제합니다.");
}

// ---------- page summaries ----------

function base(record: SummaryTarget): string {
  return `/api/courses/${encodeURIComponent(record.backendCourseId)}`;
}

/** Published summary, or null when nothing is published (404). */
export async function getPublishedSummary(record: SummaryTarget): Promise<ServerSummary | null> {
  try {
    return await apiGet<ServerSummary>(`${base(record)}/files/${encodeURIComponent(record.fileId)}/summary`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

export function listSummaryJobs(record: SummaryTarget): Promise<SummaryJob[]> {
  return apiGet<SummaryJob[]>(`${base(record)}/files/${encodeURIComponent(record.fileId)}/summary-jobs`);
}

/**
 * Starts a summary job with an `Idempotency-Key`. When the previous start for the same file ended without a definite
 * answer, the retry sends the same key and the server returns the job it may already have made.
 */
export async function startSummaryJob(record: SummaryTarget): Promise<SummaryJob> {
  const scope = `${record.backendCourseId}:${record.fileId}`;
  const key = pendingJobs.get(scope) || newRequestKey();
  pendingJobs.set(scope, key);
  try {
    const job = await apiSend<SummaryJob>("POST", `${base(record)}/files/${encodeURIComponent(record.fileId)}/summary-jobs`, undefined, { idempotencyKey: key });
    pendingJobs.delete(scope);
    return job;
  } catch (error) {
    if (!uncertainCreate(error)) pendingJobs.delete(scope);
    throw error;
  }
}

export function getSummaryJob(record: SummaryTarget, jobId: Id): Promise<SummaryJob> {
  return apiGet<SummaryJob>(`${base(record)}/summary-jobs/${encodeURIComponent(jobId)}`);
}

export function cancelSummaryJob(record: SummaryTarget, jobId: Id): Promise<SummaryJob> {
  return apiSend<SummaryJob>("POST", `${base(record)}/summary-jobs/${encodeURIComponent(jobId)}/cancel`);
}

export function retrySummaryJob(record: SummaryTarget, jobId: Id): Promise<SummaryJob> {
  return apiSend<SummaryJob>("POST", `${base(record)}/summary-jobs/${encodeURIComponent(jobId)}/retry`);
}

export function getSummaryResult(record: SummaryTarget, jobId: Id): Promise<ServerSummary> {
  return apiGet<ServerSummary>(`${base(record)}/summary-jobs/${encodeURIComponent(jobId)}/result`);
}

export function publishSummary(record: SummaryTarget, jobId: Id): Promise<ServerSummary> {
  return apiSend<ServerSummary>("POST", `${base(record)}/summary-jobs/${encodeURIComponent(jobId)}/publish`);
}

/** Korean message for a failed summary request. */
export function summaryMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "요약 요청을 처리하지 못했습니다.";
  if (error.code === "llm_consent_required") return "이 과목의 외부 AI 처리(쪽별 요약 생성)에 동의하지 않아 요약을 만들 수 없습니다.";
  if (error.code === "llm_disabled") return "이 서비스에서는 외부 AI 기능을 쓰지 않습니다.";
  // 403 from a backend that lets only the course's professor (or manager) start and retry summary jobs (ADR-0032).
  if (error.code === "llm_decider_required") return "쪽별 요약 생성과 다시 시도는 이 과목의 담당 교수나 비정규 담당자만 할 수 있습니다. 조교 계정으로는 요약을 만들 수 없습니다.";
  if (error.code === "idempotency_in_progress") return "같은 요약 요청을 처리하고 있습니다. 잠시 후 진행 상황을 새로 불러오세요.";
  if (error.code === "idempotency_conflict") return "앞서 보낸 요청과 내용이 달라 처리하지 않았습니다. 다시 시도하세요.";
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "이 자료의 요약을 다룰 권한이 없거나 자료를 찾을 수 없습니다.";
    case 409: return "이미 이 자료의 요약을 만들고 있습니다. 진행 상황을 새로 불러오세요.";
    case 422: return "PDF 자료만 쪽별 요약을 만들 수 있습니다.";
    case 429: return `요청이 많습니다. ${error.retryAfter ? `${error.retryAfter}초 뒤` : "잠시 후"} 다시 시도하세요.`;
    case 0: return error.message;
    default: return "요약 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
