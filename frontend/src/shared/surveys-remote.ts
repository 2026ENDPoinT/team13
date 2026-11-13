// 설문 (`/api/courses/{courseId}/surveys/...`, 알림마당 `/api/surveys/...`, ADR-0022). 응답 구간은 서버
// 시각으로 정해지고, 문항은 응답 기간 안이고 아직 완료하지 않았을 때만 응답에 들어온다. 결과는 서버가
// 공개를 허락했을 때만 받고, 응답자가 5명 미만인 설문과 문항은 서버가 가린다. 응답은 메모리에만 둔다.
import { apiGet, apiSend, ApiError, newRequestKey } from "./api.ts";
import { subscribe } from "./backend.ts";
import { createResource, retryLater } from "./remote-cache.ts";

type Id = string | number;

/** Survey settings (`survey` in a detail, list rows). */
export interface SurveyInfo {
  surveyId: number;
  activityId?: number;
  weekNo?: number | null;
  title: string;
  description?: string | null;
  anonymous?: boolean;
  published?: boolean;
  openAt?: string | null;
  closeAt?: string | null;
  resultsVisible?: boolean;
  resultsReleaseAt?: string | null;
  questionCount?: number;
  phase?: string;
  state?: string;
  respondentCount?: number;
}

export interface SurveyOption {
  optionId: number;
  optionNo?: number;
  text: string;
  count?: number | null;
}

export interface SurveyQuestion {
  questionId: number;
  questionNo?: number;
  type: string;
  text: string;
  required?: boolean;
  minSelect?: number | null;
  maxSelect?: number | null;
  options?: SurveyOption[];
  answerCount?: number | null;
  scaleMean?: number | null;
  suppressed?: boolean;
}

/** Respondent or instructor detail (each role gets only its fields). */
export interface SurveyDetail {
  survey: SurveyInfo;
  phase?: string;
  serverTime?: string;
  state?: string;
  resultsAvailable?: boolean;
  revision?: number;
  respondentCount?: number;
  questions?: SurveyQuestion[];
}

export interface SurveyResults {
  disclosed: boolean;
  withheldReason?: string | null;
  respondentCount: number;
  minRespondents: number;
  anonymous?: boolean;
  survey?: SurveyInfo;
  questions: SurveyQuestion[];
}

export interface SurveyPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** One free-text answer (`SurveyViews.TextPage` items are plain strings). */
export type SurveyTextAnswer = string;

export interface SurveyAnswer {
  questionId: number;
  optionIds?: number[];
  text?: string;
}

export interface CompletionReceipt {
  surveyId: number;
  state: string;
  revision: number;
}

/** `srv-<surveyId>`의 설문 ID, 아니면 null. */
export function surveyIdOf(ref: unknown): number | null {
  const match = /^srv-([1-9]\d{0,15})$/.exec(String(ref || ""));
  return match ? Number(match[1]) : null;
}

export const surveyRef = (surveyId: Id): string => `srv-${surveyId}`;

/** 샘플 과목 `kj`의 서버 설문 학생 상세 주소. */
export function studentSurveyHref(kj: Id, surveyId: Id, extra: Record<string, string | number | null | undefined> = {}): string {
  const params = new URLSearchParams({ m: "survey", n: surveyRef(surveyId), kj: String(kj) });
  for (const [key, value] of Object.entries(extra)) if (value != null && value !== "") params.set(key, String(value));
  return `/ilos/cls/st/activity/activity_form.acl?${params}`;
}

const courseBase = (courseId: Id): string => `/api/courses/${encodeURIComponent(courseId)}/surveys`;

/** 설문 하나의 API 경로. 강의실 설문은 과목을 붙이고, 알림마당 설문은 `courseId`가 null이다. 캐시 키로도 쓴다. */
export function surveyPath(courseId: Id | null | undefined, surveyId: Id): string {
  return courseId == null
    ? `/api/surveys/${encodeURIComponent(surveyId)}`
    : `${courseBase(courseId)}/${encodeURIComponent(surveyId)}`;
}

/** `GET /api/courses/{courseId}/surveys`: 학생은 본인 `state`, 담당자는 `respondentCount`. 키는 과목 ID. */
export const surveyLists = createResource<SurveyInfo[], [courseId: Id]>((courseId) => apiGet<SurveyInfo[]>(courseBase(courseId)));
/** 알림마당 설문 목록 한 쪽 `{ items, page, size, total }`. 키는 쪽 번호. */
export const communitySurveyPages = createResource<SurveyPage<SurveyInfo>, [page: number]>((page) => apiGet<SurveyPage<SurveyInfo>>(`/api/surveys?page=${encodeURIComponent(page)}&size=${COMMUNITY_PAGE_SIZE}`));
export const COMMUNITY_PAGE_SIZE = 20;
/** 설문 상세. 키는 `surveyPath()`. 응답자 `{ survey, phase, serverTime, state, resultsAvailable, questions? }`, 담당자 `{ survey, revision, respondentCount, questions }`. */
export const surveyDetails = createResource<SurveyDetail, [path: string]>((path) => apiGet<SurveyDetail>(path));
/** 결과 집계 `{ disclosed, withheldReason?, respondentCount, minRespondents, questions }`. 키는 `surveyPath()`. */
export const surveyResults = createResource<SurveyResults, [path: string]>((path) => apiGet<SurveyResults>(`${path}/results`));
/** 서술형 답변 한 쪽 `{ items, page, size, total }`. 키는 `textsKey()`. */
export const surveyTexts = createResource<SurveyPage<SurveyTextAnswer>, [path: string, questionId: Id, page: number]>((path, questionId, page) => (
  apiGet<SurveyPage<SurveyTextAnswer>>(`${path}/results/questions/${encodeURIComponent(questionId)}/texts?page=${encodeURIComponent(page)}&size=${TEXT_PAGE_SIZE}`)
));
export const TEXT_PAGE_SIZE = 20;
export const textsKey = (path: string, questionId: Id, page: number): string => `${path}#${questionId}#${page}`;

/** 한 설문의 캐시와 그 설문이 든 목록을 버린다. */
export function forgetSurvey(courseId: Id | null | undefined, surveyId: Id): void {
  const path = surveyPath(courseId, surveyId);
  surveyDetails.drop((key) => key === path);
  surveyResults.drop((key) => key === path);
  surveyTexts.drop((key) => key.startsWith(`${path}#`));
  if (courseId == null) communitySurveyPages.drop(() => true);
  else surveyLists.drop((key) => key === String(courseId));
}

// 요청이 서버에 닿았을 수 있는 실패.
const uncertain = (error: unknown): boolean => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
let pendingCompletion: { path: string; content: string; key: string } | null = null;
let pendingCreate: { scope: string; content: string; key: string } | null = null;
subscribe(() => {
  pendingCompletion = null;
  pendingCreate = null;
});

/** 이 설문의 마지막 완료 요청이 응답 없이 끝났는지. */
export function completionUncertain(courseId: Id | null | undefined, surveyId: Id): boolean {
  return Boolean(pendingCompletion && pendingCompletion.path === surveyPath(courseId, surveyId));
}

/**
 * 답 전체로 한 번 완료한다(`Idempotency-Key` 필수). 시도마다 새 key이고, 앞 시도가 응답 없이 끝났고 답이
 * 같으면 같은 key를 다시 보내 서버가 처음 결과를 돌려주게 한다. `answers`는 `[{ questionId, optionIds?, text? }]`이고
 * 답하지 않은 선택 문항은 넣지 않는다. receipt `{ surveyId, state, revision }`을 돌려준다.
 */
export async function completeSurvey(courseId: Id | null | undefined, surveyId: Id, answers: SurveyAnswer[]): Promise<CompletionReceipt> {
  const path = surveyPath(courseId, surveyId);
  const content = JSON.stringify(answers);
  const attempt = pendingCompletion && pendingCompletion.path === path && pendingCompletion.content === content
    ? pendingCompletion
    : { path, content, key: newRequestKey() };
  pendingCompletion = attempt;
  try {
    const receipt = await apiSend<CompletionReceipt>("POST", `${path}/completion`, { answers }, { idempotencyKey: attempt.key });
    pendingCompletion = null;
    forgetSurvey(courseId, surveyId);
    return receipt;
  } catch (error) {
    if (!uncertain(error)) pendingCompletion = null;
    throw error;
  }
}

// ---------- instructor ----------

/** 강의실 설문 등록(201). 응답 없이 끝난 같은 내용의 재시도는 같은 `Idempotency-Key`를 보낸다. */
export async function createSurvey(courseId: Id, payload: Record<string, unknown>): Promise<SurveyDetail> {
  const scope = String(courseId);
  const content = JSON.stringify(payload);
  const attempt = pendingCreate && pendingCreate.scope === scope && pendingCreate.content === content
    ? pendingCreate
    : { scope, content, key: newRequestKey() };
  pendingCreate = attempt;
  try {
    const created = await apiSend<SurveyDetail>("POST", courseBase(courseId), payload, { idempotencyKey: attempt.key });
    pendingCreate = null;
    surveyLists.drop((key) => key === scope);
    return created;
  } catch (error) {
    if (!uncertain(error)) pendingCreate = null;
    throw error;
  }
}

/**
 * 전체 수정. `payload.revision`은 폼을 읽은 수정 번호다(409 `edit_conflict`). `anonymous`와 `questions`를
 * 빼면 서버가 그대로 둔다. 완료한 사람이 있으면 둘을 바꿀 수 없다(409 `survey_has_responses`).
 */
export async function updateSurvey(courseId: Id | null | undefined, surveyId: Id, payload: Record<string, unknown>): Promise<SurveyDetail> {
  const updated = await apiSend<SurveyDetail>("PUT", surveyPath(courseId, surveyId), payload);
  forgetSurvey(courseId, surveyId);
  return updated;
}

// ---------- text ----------

export const PHASE: Record<string, string> = { not_open: "시작 전", open: "응답 기간", closed: "마감" };
export const STATE: Record<string, string> = { not_started: "미응답", completed: "응답 완료" };
export const QUESTION_TYPE: Record<string, string> = { single: "하나 고르기", multiple: "여러 개 고르기", scale: "척도", essay: "서술형" };
export const WITHHELD: Record<string, string> = {
  not_closed: "응답 기간이 끝난 뒤에 결과를 볼 수 있습니다.",
  below_threshold: "응답한 사람이 최소 공개 인원보다 적어 결과를 가렸습니다.",
};

export function isConflict(error: unknown, code: string): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === code;
}

/** 설문 요청이 실패했을 때의 한국어 문구. */
export function surveyMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "설문 요청을 처리하지 못했습니다.";
  switch (error.status) {
    case 400: return error.detail || "답을 확인하세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "설문을 찾을 수 없거나 볼 수 있는 권한이 없습니다.";
    case 409:
      switch (error.code) {
        case "already_completed": return "이미 응답을 완료한 설문입니다. 응답은 고칠 수 없습니다.";
        case "deadline_passed": return "응답 기간이 아니어서 제출하지 못했습니다.";
        case "survey_changed": return "설문 설정이 바뀌었습니다. 설문을 다시 불러왔으니 확인한 뒤 다시 제출하세요.";
        case "idempotency_conflict": return "같은 완료 요청을 다른 답으로 보냈습니다. 설문 상태를 다시 불러왔습니다.";
        case "idempotency_in_progress": return "같은 요청을 처리하고 있습니다. 잠시 후 상태를 확인하세요.";
        case "results_withheld": return "아직 결과를 볼 수 없습니다.";
        case "edit_conflict": return "다른 곳에서 이 설문을 먼저 수정했습니다. 최신 내용을 불러왔습니다. 입력한 내용은 그대로 두었으니 확인한 뒤 다시 저장하세요.";
        case "survey_has_responses": return "응답한 사람이 있어 문항과 응답 방식은 바꿀 수 없습니다. 기간과 안내는 바꿀 수 있습니다.";
        default: return error.detail || "요청이 지금 상태와 맞지 않습니다. 화면을 새로 불러오세요.";
      }
    case 429: return retryLater(error);
    case 0: return error.message;
    case 502:
    case 503:
    case 504: return "ASTRA 서버의 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.";
    default: return "설문 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/**
 * 강의실 설문 삭제(ADR-0037, 활동의 삭제 표시). `If-Match`에 마지막으로 읽은 상세의 `revision`을 보낸다. 없으면 428,
 * 다르면 409 `edit_conflict`다. 참여와 응답은 서버에 남고 그 commit부터 설문과 결과는 누구에게도 없다. 204면 목록 캐시에서
 * 그 설문을 빼고 상세·결과 캐시를 버린다. 실패하면 그 설문의 캐시를 모두 버린다.
 */
export async function deleteSurvey(courseId: Id, surveyId: Id, revision: number | string | null | undefined): Promise<void> {
  try {
    await apiSend("DELETE", surveyPath(courseId, surveyId), undefined, { ifMatch: revision });
  } catch (error) {
    forgetSurvey(courseId, surveyId);
    throw error;
  }
  const path = surveyPath(courseId, surveyId);
  surveyLists.patch((key) => key === String(courseId), (items) => {
    if (!Array.isArray(items)) return;
    const at = items.findIndex((item) => item.surveyId === surveyId);
    if (at >= 0) items.splice(at, 1);
  });
  surveyDetails.drop((key) => key === path);
  surveyResults.drop((key) => key === path);
  surveyTexts.drop((key) => key.startsWith(`${path}#`));
}
