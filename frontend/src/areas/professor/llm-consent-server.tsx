// 외부 AI 처리 동의 on the professor's material screen (backend `ready`): the deployment's provider
// terms (`GET /api/llm/policy`), my consent per scope for this course with grant (acknowledgement
// when the terms say cross-border transfer or training use) and revoke, re-consent when the terms
// changed (`current: false` or 409 `llm_policy_changed`), and the course's LLM usage. An instructor
// who may not consent (`canGrant: false`, a teaching assistant) sees the state read-only except revoke. A
// deployment with the feature off (`enabled: false`) lists only granted scopes, each with revoke. Summary
// jobs refused with 409 `llm_consent_required` / 503 `llm_disabled` point here.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, LoadingState, ErrorState } from "../../ui/kit.tsx";
import type { Course } from "../../data.ts";
import { courseIdFor, roleIn } from "../../shared/backend.ts";
import { useResource } from "../../shared/remote-cache.ts";
import {
  SCOPES, POLICY, policies, consents, usages, loadPolicy, reloadConsents, grantConsent, revokeConsent,
  isConsentRequired, isLlmDisabled, isPolicyChanged, mayGrant, DECIDER_ONLY, consentMessage, retentionText, tokensText,
  type LlmConsent, type LlmPolicy,
} from "../../shared/llm-remote.ts";

export const CONSENT_ANCHOR = "llm-consent";

/** Under a refused summary request: what to do instead of retrying. Null for other errors. */
export function consentLead(error: unknown): ReactNode {
  if (isLlmDisabled(error)) return <span>이 서비스에서는 외부 AI 기능을 쓰지 않습니다. 관리자가 켜기 전에는 요약을 만들 수 없습니다.</span>;
  if (!isConsentRequired(error)) return null;
  return (
    <span>
      {"이 과목의 쪽별 요약 생성에 동의해야 요약을 만들 수 있습니다. "}
      <a
        href={`#${CONSENT_ANCHOR}`}
        onClick={(event) => {
          event.preventDefault();
          const target = document.getElementById(CONSENT_ANCHOR);
          if (!target) return;
          target.scrollIntoView({ block: "start" });
          target.querySelector<HTMLElement>("button:not([disabled]), input")?.focus();
        }}
      >
        외부 AI 처리 동의로 이동
      </a>
    </span>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return <><dt>{label}</dt><dd>{value}</dd></>;
}

/** The consent section for `course` (sample course object), or null when this user is not its instructor. */
export function llmConsentSection(course: Course): ReactNode {
  const courseId = courseIdFor(course.id);
  if (!courseId || roleIn(course.id) !== "INSTRUCTOR") return null;
  return <ConsentSection courseId={courseId} />;
}

interface Line {
  text: string;
  alert: boolean;
}

function ConsentSection({ courseId }: { courseId: number | string }) {
  const key = String(courseId);
  const policy = useResource(policies, POLICY, () => { void loadPolicy(); });
  const mine = useResource(consents, key, () => { void consents.load(key, courseId); });
  const usage = useResource(usages, key, () => { void usages.load(key, courseId); });
  const [line, setLine] = useState<Line>({ text: "", alert: false });
  const [acknowledged, setAcknowledged] = useState(false);
  const ack = useRef<HTMLInputElement>(null);
  useEffect(() => {
    void policies.visit("llm-policy", POLICY);
    void consents.visit(`llm-consents:${key}`, key, courseId);
    void usages.visit(`llm-usage:${key}`, key, courseId);
  });
  const say = (text: string, alert = false) => setLine({ text, alert });

  let body: ReactNode;
  if (!policy || (!policy.data && !policy.error) || !mine || (!mine.data && !mine.error)) {
    body = <LoadingState title="처리 조건과 동의를 불러오는 중입니다" />;
  } else if (!policy.data || !mine.data) {
    body = (
      <ErrorState
        title="동의 정보를 불러오지 못했습니다"
        body={consentMessage(policy.error || mine.error)}
        actions={<Button kind="ghost" onClick={() => { void loadPolicy(); reloadConsents(courseId); }}>다시 불러오기</Button>}
      />
    );
  } else {
    const p = policy.data;
    const terms = (
      <dl className="pf-llm__terms">
        <Fact label="제공자" value={[p.providerName, p.providerOperator].filter(Boolean).join(" · ") || "-"} />
        <Fact label="처리 지역" value={p.processingRegion || "-"} />
        <Fact label="보관 기간" value={retentionText(p.retentionDays)} />
        <Fact label="학습 이용" value={p.trainingUse ? "제공자가 입력을 서비스 개선에 쓸 수 있음" : "쓰지 않음"} />
        <Fact label="국외 이전" value={p.crossBorderTransfer ? "있음" : "없음"} />
        <Fact label="고지 판" value={p.noticeVersion || "-"} />
      </dl>
    );
    // A disabled deployment (switch off or no provider key) takes no new consent, but a consent given earlier stays
    // listed with only "철회" so it can be withdrawn before a provider is configured again.
    const enabled = Boolean(p.enabled);
    const rows = (Array.isArray(mine.data) ? mine.data : []).filter((item) => SCOPES[item.scope] && (enabled || item.granted));
    // A teaching assistant (`canGrant: false`) sees the state without consent buttons or the "terms changed" label;
    // an old consent of theirs can still be revoked (the backend keeps revoke for every instructor).
    const decider = mayGrant(mine.data);
    const scopeRows = rows.map((item) => (
      <ScopeRow
        key={item.scope}
        item={item}
        enabled={enabled}
        decider={decider}
        policy={p}
        courseId={courseId}
        acknowledged={acknowledged}
        say={say}
        onPolicyChanged={() => { setAcknowledged(false); void loadPolicy(); }}
        focusAck={() => ack.current?.focus()}
      />
    ));
    if (!enabled) {
      body = (
        <>
          <p className="pf-sub" role="note">이 서비스에서는 외부 AI 기능을 쓰지 않습니다. 쪽별 요약 생성과 학생 학습 대화가 모두 꺼져 있습니다. 이미 게시한 요약은 학생에게 계속 보입니다.</p>
          {terms}
          {scopeRows}
        </>
      );
    } else {
      const u = usage && usage.data;
      body = (
        <>
          {decider
            ? <p className="pf-sub">동의하면 이 과목 자료의 쪽 이미지와 텍스트(쪽별 요약), 쪽 텍스트와 게시한 해설·학생 질문(학습 대화)이 아래 제공자로 전송됩니다. 전자우편, 전화번호, 학번 형식의 숫자는 보내기 전에 가리지만 이름과 쪽 이미지 속 글자는 가리지 못합니다. 학생 이름이 든 자료는 요청 전에 확인하세요.</p>
            : <p className="pf-sub" role="note">{`${DECIDER_ONLY} 아래는 내 계정의 동의 상태입니다. 예전에 남긴 동의는 철회만 할 수 있습니다.`}</p>}
          {terms}
          {decider && p.acknowledgementRequired ? (
            <label className="choice pf-check">
              <input ref={ack} type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
              <span>국외 이전과 제공자의 학습 이용 가능성을 확인했습니다</span>
            </label>
          ) : null}
          {scopeRows}
          {u ? <p className="pf-meta">{`사용량 · 쪽별 요약 ${u.pageSummary ? u.pageSummary.calls : 0}회 호출, ${tokensText(u.pageSummary ? u.pageSummary.inputTokens + u.pageSummary.outputTokens : 0)} · 학습 대화 오늘 ${tokensText(u.studyChatTodayTokens)} / 하루 ${tokensText(u.studyChatDailyTokenBudget)}`}</p> : null}
        </>
      );
    }
  }
  return (
    <section className="pf-llm" id={CONSENT_ANCHOR} aria-label="외부 AI 처리 동의">
      <h3>외부 AI 처리 동의</h3>
      <div className="stack" aria-live="polite">{body}</div>
      <p className="pf-sub" role={line.alert ? "alert" : "status"} aria-live="polite">{line.text}</p>
    </section>
  );
}

interface ScopeRowProps {
  item: LlmConsent;
  /** `enabled` of the policy: when false only the "철회" control of a granted row is offered. */
  enabled: boolean;
  decider: boolean;
  policy: LlmPolicy;
  courseId: number | string;
  acknowledged: boolean;
  say: (text: string, alert?: boolean) => void;
  onPolicyChanged: () => void;
  focusAck: () => void;
}

function ScopeRow({ item, enabled, decider, policy, courseId, acknowledged, say, onPolicyChanged, focusAck }: ScopeRowProps) {
  const [granting, setGranting] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [armed, setArmed] = useState(false);
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);
  const state = !item.granted ? "동의하지 않음"
    : !decider ? "예전에 남긴 동의(효력 없음)"
    : !item.current ? "처리 조건이 바뀌어 다시 동의해야 합니다"
    : enabled ? "동의함" : "동의함 · 외부 AI 기능이 꺼져 있어 지금은 쓰이지 않음";

  async function grant() {
    if (granting) return;
    if (policy.acknowledgementRequired && !acknowledged) {
      say("국외 이전·학습 이용 내용을 확인했다고 표시해야 동의할 수 있습니다.", true);
      focusAck();
      return;
    }
    setGranting(true);
    say("");
    try {
      await grantConsent(courseId, item.scope, policy, acknowledged);
      say(`${SCOPES[item.scope]}에 동의했습니다.`);
    } catch (error) {
      say(consentMessage(error), true);
      // The terms changed since this screen read them: show the new terms before asking again.
      if (isPolicyChanged(error)) onPolicyChanged();
      if (live.current) setGranting(false);
    }
  }

  async function revoke() {
    if (revoking) return;
    if (!armed) {
      setArmed(true);
      return;
    }
    setRevoking(true);
    try {
      await revokeConsent(courseId, item.scope);
      say(`${SCOPES[item.scope]} 동의를 철회했습니다. 기다리던 작업과 질문은 취소되고, 게시한 요약은 그대로 남습니다.`);
    } catch (error) {
      say(consentMessage(error), true);
      if (live.current) {
        setRevoking(false);
        setArmed(false);
      }
    }
  }

  return (
    <div className="pf-llm__scope">
      <b>{SCOPES[item.scope]}</b>
      <span className="pf-meta">{state}</span>
      <div className="pf-actions">
        {enabled && decider && (!item.granted || !item.current) ? (
          <Button kind="primary" disabled={granting} aria-busy={granting ? "true" : undefined} onClick={() => { void grant(); }}>
            {granting ? "보내는 중…" : item.granted ? "다시 동의" : "동의"}
          </Button>
        ) : null}
        {item.granted ? (
          <Button kind="secondary" disabled={revoking} aria-busy={revoking ? "true" : undefined} onClick={() => { void revoke(); }}>
            {revoking ? "철회하는 중…" : armed ? "한 번 더 누르면 철회" : "철회"}
          </Button>
        ) : null}
      </div>
    </div>
  );
}
