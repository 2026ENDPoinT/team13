// External LLM processing terms and the professor's per-course consent (ADR-0032):
// `GET /api/llm/policy` (provider profile, notice version, profile digest), `…/llm-consents`
// (my consent per scope `page_summary` | `study_chat`), grant with the digest I was shown
// (409 `llm_policy_changed` when the deployment changed it since), revoke, and `…/llm-usage`.
// Summary jobs and study chat answer 409 `llm_consent_required` / 503 `llm_disabled` without it.
import { apiGet, apiSend, ApiError } from "./api.ts";
import { createResource, retryLater } from "./remote-cache.ts";

type Id = number | string;

/** `GET /api/llm/policy`: the deployment's provider profile and notice. */
export interface LlmPolicy {
  enabled: boolean;
  noticeVersion: string;
  profileDigest: string;
  providerName?: string | null;
  providerOperator?: string | null;
  processingRegion?: string | null;
  crossBorderTransfer?: boolean;
  trainingUse?: boolean;
  retentionDays?: number | null;
  acknowledgementRequired?: boolean;
}

/** One scope of `GET …/llm-consents`. */
export interface LlmConsent {
  scope: string;
  granted: boolean;
  current: boolean;
}

export interface LlmUsageCount {
  calls: number;
  inputTokens: number;
  outputTokens: number;
}

/** `GET …/llm-usage`. */
export interface LlmUsage {
  pageSummary?: LlmUsageCount | null;
  studyChatTodayTokens?: number;
  studyChatDailyTokenBudget?: number | null;
}

export const SCOPES: Record<string, string> = { page_summary: "쪽별 요약 생성", study_chat: "학생 학습 대화" };

export const policies = createResource<LlmPolicy, []>(() => apiGet<LlmPolicy>("/api/llm/policy"));
export const consents = createResource<LlmConsent[], [Id]>((courseId) => apiGet<LlmConsent[]>(`/api/courses/${encodeURIComponent(courseId)}/llm-consents`));
export const usages = createResource<LlmUsage, [Id]>((courseId) => apiGet<LlmUsage>(`/api/courses/${encodeURIComponent(courseId)}/llm-usage`));

export const POLICY = "policy";

export function loadPolicy(): Promise<void> {
  return policies.load(POLICY);
}

export function reloadConsents(courseId: Id): void {
  consents.load(String(courseId), courseId);
  usages.load(String(courseId), courseId);
}

/** Grants `scope` for the policy the professor read. `acknowledged` = cross-border/training-use box. */
export async function grantConsent(courseId: Id, scope: string, policy: Pick<LlmPolicy, "noticeVersion" | "profileDigest">, acknowledged: unknown): Promise<LlmConsent> {
  try {
    return await apiSend<LlmConsent>("PUT", `/api/courses/${encodeURIComponent(courseId)}/llm-consents/${encodeURIComponent(scope)}`, {
      noticeVersion: policy.noticeVersion,
      profileDigest: policy.profileDigest,
      acknowledged: Boolean(acknowledged),
    });
  } finally {
    reloadConsents(courseId);
  }
}

export async function revokeConsent(courseId: Id, scope: string): Promise<void> {
  try {
    await apiSend("DELETE", `/api/courses/${encodeURIComponent(courseId)}/llm-consents/${encodeURIComponent(scope)}`);
  } finally {
    reloadConsents(courseId);
  }
}

/** True for the refusals that a consent (or an operator) must resolve, not a retry. */
export function isConsentRequired(error: unknown): boolean {
  return error instanceof ApiError && error.code === "llm_consent_required";
}

export function isLlmDisabled(error: unknown): boolean {
  return error instanceof ApiError && error.code === "llm_disabled";
}

export function isPolicyChanged(error: unknown): boolean {
  return error instanceof ApiError && error.code === "llm_policy_changed";
}

/** Korean message for a failed policy/consent request. */
export function consentMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error && error.message ? error.message : "동의 요청을 처리하지 못했습니다.";
  switch (error.code) {
    case "llm_policy_changed": return "처리 조건이 바뀌었습니다. 새 조건을 다시 불러왔으니 확인한 뒤 동의하세요.";
    case "llm_acknowledgement_required": return "국외 이전·학습 이용 내용을 확인했다고 표시해야 동의할 수 있습니다.";
    case "llm_disabled": return "이 서비스에서는 외부 AI 기능을 쓰지 않습니다.";
    case "llm_consent_required": return "이 과목에서 외부 AI 처리에 동의해야 쓸 수 있습니다.";
    default: break;
  }
  switch (error.status) {
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return "이 과목의 담당 교수만 동의할 수 있습니다.";
    case 429: return retryLater(error);
    case 0: return error.message;
    default: return "동의 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
  }
}

/** "보관 기간 밝히지 않음" / "보관하지 않음" / "30일". */
export function retentionText(days: number | null | undefined): string {
  if (days == null) return "제공자가 밝히지 않음";
  return Number(days) === 0 ? "보관하지 않음" : `${days}일`;
}

export function tokensText(n: number | null | undefined): string {
  return `${Number(n || 0).toLocaleString("ko-KR")} token`;
}
