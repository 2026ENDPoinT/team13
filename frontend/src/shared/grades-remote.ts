// 성적부(ADR-0023). 학생은 `GET …/grades/me`로 자기 항목을 받고, 공개하지 않은 항목에는 점수·의견·채점 일시 속성이 아예 없다.
// 합계도 서버가 공개한 점수만 더해 준다. 담당자는 `GET …/gradebook`으로 설정·항목·학생 쪽(page/size)을 받고, 항목은 `revision`,
// 점수 칸은 `version`으로 고친다. 항목 하나만 보는 점수 표(오프라인 시험)는 `GET …/gradebook/items/{itemId}`로 그 항목의 학생 쪽만
// 받는다. 점수 일괄 저장은 전부 아니면 전무이고, 거절하면 problem JSON `rows`에 순번과 이유 코드가 온다. 응답은 메모리에만 둔다.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";

type Id = number | string;

/** Server total `{ score, maxScore, itemCount, weightedScore?, weightTotal? }`. */
export interface GradeTotal {
  score: number;
  maxScore: number;
  itemCount: number;
  weightedScore?: number | null;
  weightTotal?: number | null;
}

/** One of my grade items (`GET …/grades/me`). Unreleased items have no score, feedback or gradedAt. */
export interface MyGradeItem {
  itemId: number;
  type: string;
  name: string;
  maxScore: number;
  weight?: number | null;
  released?: boolean;
  submitted?: boolean;
  assignmentId?: number | null;
  quizId?: number | null;
  closeAt?: string | null;
  score?: number | null;
  feedback?: string | null;
  gradedAt?: string | null;
}

/** `GET …/grades/me`. */
export interface MyGrades {
  activitiesVisible: boolean;
  items?: MyGradeItem[];
  total?: GradeTotal | null;
  serverTime?: string;
}

/** A gradebook item (column). */
export interface GradeItem {
  itemId: number;
  type: string;
  name: string;
  maxScore: number;
  weight?: number | null;
  released?: boolean;
  releaseAt?: string | null;
  revision: number;
  managedByAssignment?: boolean;
  managedByQuiz?: boolean;
  assignmentId?: number | null;
  quizId?: number | null;
  closeAt?: string | null;
  gradedCount?: number;
}

/** One score cell of a student. `version` is 0 when there is no score yet. */
export interface ScoreCell {
  itemId: number;
  score?: number | null;
  feedback?: string | null;
  gradedAt?: string | null;
  version: number;
}

export interface GradebookStudent {
  studentId: number;
  name: string;
  scores?: ScoreCell[];
  total?: GradeTotal | null;
}

export interface GradeSettings {
  activitiesVisible: boolean;
  revision: number;
}

/** `GET …/gradebook?page=&size=`. */
export interface Gradebook {
  settings: GradeSettings;
  items: GradeItem[];
  students: GradebookStudent[];
  totalStudents: number;
  page: number;
  size: number;
  serverTime?: string;
}

/** A student row of one item's score sheet. */
export interface ItemSheetStudent {
  studentId: number;
  name: string;
  score?: number | null;
  feedback?: string | null;
  gradedAt?: string | null;
  version: number;
}

/** `GET …/gradebook/items/{itemId}`. */
export interface ItemSheet {
  courseId: number;
  serverTime?: string;
  totalStudents: number;
  page: number;
  size: number;
  item: GradeItem;
  students: ItemSheetStudent[];
}

/** A row of a bulk score save. `score` null clears it. */
export interface ScoreRow {
  studentId: number;
  score: number | null;
  feedback?: string | null;
  version: number;
}

/** A rejected row of a bulk save. */
export interface RejectedRow {
  index: number;
  code: string;
}

export interface GradeItemInput {
  name?: string;
  maxScore?: number;
  weight?: number | null;
}

export const GRADEBOOK_PAGE = 50;
/** 학생 화면의 거르기. 서버 `type`과 같다. */
export const GRADE_TYPES: ReadonlyArray<readonly [string, string]> = [["all", "전체"], ["assignment", "과제"], ["quiz", "퀴즈"], ["manual", "기타"]];
export const TYPE_LABEL: Readonly<Record<string, string>> = { assignment: "과제", quiz: "퀴즈", manual: "직접 입력" };

const course = (courseId: Id) => `/api/courses/${encodeURIComponent(courseId)}`;
const itemPath = (courseId: Id, itemId: Id) => `${course(courseId)}/grade-items/${encodeURIComponent(itemId)}`;

export const myGradesKey = (courseId: Id, type: string | null | undefined, q: string | null | undefined): string => `${courseId}|${type || "all"}|${q || ""}`;
export const gradebookKey = (courseId: Id, page: number): string => `${courseId}:${page}`;

/** `GET …/grades/me?type=&q=`: `{ activitiesVisible, items?, total?, serverTime }`. 거르기는 목록에만, 합계는 거르지 않는다. */
export const myGrades = createResource<MyGrades, [courseId: Id, type: string | null | undefined, q: string | null | undefined]>((courseId, type, q) => {
  const params = new URLSearchParams();
  if (type && type !== "all") params.set("type", type);
  if (q) params.set("q", q);
  const query = params.toString();
  return apiGet<MyGrades>(`${course(courseId)}/grades/me${query ? `?${query}` : ""}`);
});

/** `GET …/gradebook?page=&size=`: 설정, 항목, 학생 쪽(ID 순)과 학생별 합계. */
export const gradebooks = createResource<Gradebook, [courseId: Id, page: number]>((courseId, page) => (
  apiGet<Gradebook>(`${course(courseId)}/gradebook?page=${encodeURIComponent(page)}&size=${GRADEBOOK_PAGE}`)
));

const pages = new Map<string, [Id, number]>(); // gradebookKey -> [courseId, page]
const sheetPages = new Map<string, [Id, Id, number]>(); // itemSheetKey -> [courseId, itemId, page]
subscribe(() => {
  pages.clear();
  sheetPages.clear();
});

/** 성적부 한 쪽을 받는다. 받은 쪽을 기억해 두었다가 쓰기 뒤 다시 받는다. */
export function loadGradebook(courseId: Id, page: number): Promise<void> {
  const key = gradebookKey(courseId, page);
  pages.set(key, [courseId, page]);
  return gradebooks.load(key, courseId, page);
}

export const itemSheetKey = (courseId: Id, itemId: Id, page: number): string => `${courseId}:${itemId}:${page}`;

/**
 * `GET …/gradebook/items/{itemId}?page=&size=`: `{ courseId, serverTime, totalStudents, page, size, item, students }`. `item`은
 * 성적부 `items[]`와 같은 모양이고 `students[{ studentId, name, score, feedback, gradedAt, version }]`의 `version`은 점수가 없으면
 * 0이라 점수 일괄 저장에 그대로 보낸다. 지운 활동의 항목은 404다.
 */
export const gradeItemSheets = createResource<ItemSheet, [courseId: Id, itemId: Id, page: number]>((courseId, itemId, page) => (
  apiGet<ItemSheet>(`${course(courseId)}/gradebook/items/${encodeURIComponent(itemId)}?page=${encodeURIComponent(page)}&size=${GRADEBOOK_PAGE}`)
));

/** 항목 하나의 학생 한 쪽을 받는다. 받은 쪽을 기억해 두었다가 쓰기 뒤 다시 받는다. */
export function loadItemSheet(courseId: Id, itemId: Id, page: number): Promise<void> {
  const key = itemSheetKey(courseId, itemId, page);
  sheetPages.set(key, [courseId, itemId, page]);
  return gradeItemSheets.load(key, courseId, itemId, page);
}

export const quizItemKey = (courseId: Id, quizId: Id): string => `${courseId}:${quizId}`;

/**
 * 시험의 성적 항목 ID `{ itemId }`(성적부에 없으면 null). 시험 상세에 항목 ID가 없어 성적부의 학생 한 명짜리 쪽
 * (`GET …/gradebook?page=1&size=1`)의 `items[]`에서 `quizId`로 찾는다. 항목 ID는 바뀌지 않으므로 세션 동안 한 번만 묻는다.
 */
export const quizGradeItems = createResource<{ itemId: number | null }, [courseId: Id, quizId: Id]>(async (courseId, quizId) => {
  const data = await apiGet<Partial<Gradebook> | null>(`${course(courseId)}/gradebook?page=1&size=1`);
  const items = data && Array.isArray(data.items) ? data.items : [];
  const item = items.find((candidate) => Number(candidate.quizId) === Number(quizId));
  return { itemId: item ? item.itemId : null };
});

/**
 * 쓰기 뒤: 학생 성적 캐시는 버리고, 받아 둔 성적부 쪽과 항목 점수 표 쪽은 그 자리에서 다시 받는다(받는 동안 지금 값을 그대로
 * 보여 준다). 화면의 다른 쪽은 다음 방문 때 받는다.
 */
export function forgetGradebook(courseId: Id): Promise<void[]> {
  myGrades.drop((key) => key.startsWith(`${courseId}|`));
  const reloads: Promise<void>[] = [];
  for (const [key, [id, page]] of pages) {
    if (id !== courseId) continue;
    if (gradebooks.peek(key)) reloads.push(gradebooks.load(key, id, page));
    else pages.delete(key);
  }
  for (const [key, [id, itemId, page]] of sheetPages) {
    if (id !== courseId) continue;
    if (gradeItemSheets.peek(key)) reloads.push(gradeItemSheets.load(key, id, itemId, page));
    else sheetPages.delete(key);
  }
  return Promise.all(reloads);
}

/** 평가 활동 공개. `revision`은 읽은 설정 번호(저장한 적이 없으면 0)다. */
export async function updateGradeSettings(courseId: Id, { activitiesVisible, revision }: { activitiesVisible: unknown; revision: number | string }): Promise<GradeSettings> {
  const settings = await apiSend<GradeSettings>("PUT", `${course(courseId)}/grade-settings`, { activitiesVisible: Boolean(activitiesVisible), revision: Number(revision) });
  forgetGradebook(courseId);
  return settings;
}

// 응답 없이 끝난 같은 내용의 재시도는 같은 Idempotency-Key를 보낸다.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingCreate: { scope: string; content: string; key: string } | null = null; // { scope, content, key }
subscribe(() => { pendingCreate = null; });

/** 직접 입력 항목 등록(비공개로 생긴다). `weight`가 null이면 비율 없음이다. */
export async function createGradeItem(courseId: Id, { name, maxScore, weight }: GradeItemInput): Promise<GradeItem> {
  const body = { name, maxScore, weight: weight == null ? null : weight };
  const scope = String(courseId);
  const content = JSON.stringify(body);
  const attempt = pendingCreate && pendingCreate.scope === scope && pendingCreate.content === content
    ? pendingCreate
    : { scope, content, key: newRequestKey() };
  pendingCreate = attempt;
  try {
    const created = await apiSend<GradeItem>("POST", `${course(courseId)}/grade-items`, body, { idempotencyKey: attempt.key });
    pendingCreate = null;
    forgetGradebook(courseId);
    return created;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

/**
 * 항목 수정. 직접 입력 항목은 이름·배점·비율을 함께 보내고, 과제·시험 항목은 비율만 보낸다(이름이나 배점을 보내면
 * 409 `managed_by_assignment`·`managed_by_quiz`). `revision`이 다르면 409 `edit_conflict`다.
 */
export async function updateGradeItem(courseId: Id, item: Pick<GradeItem, "itemId" | "managedByAssignment" | "managedByQuiz">, { name, maxScore, weight, revision }: GradeItemInput & { revision: number | string }): Promise<GradeItem> {
  const body = item.managedByAssignment || item.managedByQuiz
    ? { weight: weight == null ? null : weight, revision: Number(revision) }
    : { name, maxScore, weight: weight == null ? null : weight, revision: Number(revision) };
  const updated = await apiSend<GradeItem>("PUT", itemPath(courseId, item.itemId), body);
  forgetGradebook(courseId);
  return updated;
}

/** 직접 입력 항목의 점수 공개(`released: true`)와 거둠. */
export async function releaseGradeItem(courseId: Id, itemId: Id, released: unknown, revision: number | string): Promise<GradeItem> {
  const updated = await apiSend<GradeItem>("PUT", `${itemPath(courseId, itemId)}/release`, { released: Boolean(released), revision: Number(revision) });
  forgetGradebook(courseId);
  return updated;
}

/** 직접 입력 항목 삭제. 점수나 의견이 남아 있으면 409 `item_has_scores`다. */
export async function deleteGradeItem(courseId: Id, itemId: Id, revision: number | string): Promise<void> {
  await apiSend("DELETE", `${itemPath(courseId, itemId)}?revision=${encodeURIComponent(revision)}`);
  forgetGradebook(courseId);
}

/**
 * 점수 일괄 저장. `rows`는 `{ studentId, score, feedback, version }`(점수 null은 비움)이고 1~200행이다. 하나라도 틀리면
 * 아무것도 저장되지 않고 `ApiError`의 `problem.rows`에 `{ index, code }`가 온다.
 */
export async function saveScores(courseId: Id, itemId: Id, rows: ScoreRow[]): Promise<unknown> {
  const sheet = await apiSend("PUT", `${itemPath(courseId, itemId)}/scores`, { rows });
  forgetGradebook(courseId);
  return sheet;
}

/**
 * 오프라인 시험의 점수 일괄 저장(`PUT …/quizzes/{quizId}/scores`). 본문 `rows`와 거절(400 `invalid_rows`·409 `version_conflict`와
 * `problem.rows`)은 `saveScores`와 같고, 행의 `version`은 성적부에서 그 시험 항목(`quizId`) 칸의 값이다. 온라인 시험은 409
 * `online_quiz`다. 저장하면 성적부 캐시를 버리고 받아 둔 쪽을 다시 받는다.
 */
export async function saveQuizScores(courseId: Id, quizId: Id, rows: ScoreRow[]): Promise<unknown> {
  const sheet = await apiSend("PUT", `${course(courseId)}/quizzes/${encodeURIComponent(quizId)}/scores`, { rows });
  forgetGradebook(courseId);
  return sheet;
}

/** 거절된 일괄 저장의 행 오류 `[{ index, code }]`. 행 오류가 없는 실패면 빈 배열이다. */
export function rejectedRows(error: unknown): RejectedRow[] {
  const rows: unknown[] = error instanceof ApiError && error.problem && Array.isArray(error.problem.rows) ? error.problem.rows : [];
  return rows.filter((row): row is RejectedRow => isRecord(row) && Number.isInteger(row.index) && typeof row.code === "string");
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null;

const ROW_CODE: Record<string, string> = {
  invalid_student: "학생을 확인하지 못했습니다.",
  not_student: "이 과목의 수강생이 아닙니다.",
  duplicate_student: "같은 학생이 두 번 들어 있습니다.",
  invalid_version: "점수 버전을 확인하지 못했습니다. 성적부를 새로 불러오세요.",
  feedback_without_score: "점수 없이 의견만 남길 수 없습니다.",
  invalid_score: "점수는 0 이상, 소수 둘째 자리까지입니다.",
  invalid_feedback: "의견은 1,000자 이하의 일반 텍스트입니다.",
  score_exceeds_max: "배점보다 큰 점수입니다.",
  version_conflict: "다른 곳에서 먼저 저장한 점수입니다. 최신 값을 확인하세요.",
};

/** 행 오류 코드의 한국어 문구. */
export function rowCodeText(code: string): string {
  return ROW_CODE[code] || "이 행을 저장하지 못했습니다.";
}

export const isConflict = (error: unknown, code?: string): boolean => error instanceof ApiError && error.status === 409 && (!code || error.code === code);

/** "20" 또는 "17.5". 서버가 소수로 보낸 점수. */
export function points(value: unknown): string {
  const n = Number(value);
  return value != null && Number.isFinite(n) ? String(Math.round(n * 100) / 100) : "";
}

/** 서버 합계 `{ score, maxScore, itemCount, weightedScore?, weightTotal? }`의 한 줄 문구. */
export function totalText(total: GradeTotal | null | undefined): string {
  if (!total) return "";
  const base = `${points(total.score)} / ${points(total.maxScore)}점 (${total.itemCount}개 항목)`;
  return total.weightedScore != null ? `${base} · 반영 ${points(total.weightedScore)} / ${points(total.weightTotal)}` : base;
}

/** 성적 요청 실패의 한국어 문구. */
export function gradeMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "성적 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400:
      if (error.code === "invalid_rows") return "올바르지 않은 행이 있어 아무것도 저장하지 않았습니다. 표시한 행을 고친 뒤 다시 저장하세요.";
      return error.detail || "입력한 내용을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "성적 항목을 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409:
      switch (error.code) {
        case "version_conflict": return "다른 곳에서 먼저 저장한 점수가 있어 아무것도 저장하지 않았습니다. 최신 성적부를 불러왔습니다. 입력한 값은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
        case "edit_conflict": return "다른 곳에서 먼저 수정했습니다. 최신 내용을 불러왔습니다. 확인한 뒤 다시 저장하세요.";
        case "managed_by_assignment": return "과제 항목의 이름, 배점, 공개, 점수는 과제 화면에서 바꿉니다.";
        case "managed_by_quiz": return "시험 항목의 이름, 배점, 공개, 점수는 시험 화면에서 바꿉니다.";
        case "online_quiz": return "온라인 시험의 점수는 응시를 채점해 정합니다. 점수 입력은 오프라인 시험에만 있습니다.";
        case "score_exceeds_max": return "이미 준 점수보다 배점을 낮출 수 없습니다.";
        case "item_has_scores": return "점수나 의견이 남아 있는 항목은 지울 수 없습니다. 점수를 모두 비운 뒤 지우세요.";
        case "too_many_items": return error.detail || "직접 입력 항목을 더 만들 수 없습니다.";
        case "write_busy": return "저장이 몰려 처리하지 못했습니다. 아무것도 저장하지 않았으니 잠시 후 다시 저장하세요.";
        default: return error.detail || "요청이 다른 변경과 겹쳤습니다. 화면을 새로 불러오세요.";
      }
    case 429: return retryLater(error);
    case 0: return error.message;
    case 502:
    case 503:
    case 504: return "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "성적 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}
