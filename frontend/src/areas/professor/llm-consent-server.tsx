// 외부 AI 처리 동의 on the professor's material screen (backend `ready`): the deployment's provider
// terms (`GET /api/llm/policy`), my consent per scope for this course with grant (acknowledgement
// when the terms say cross-border transfer or training use) and revoke, re-consent when the terms
// changed (`current: false` or 409 `llm_policy_changed`), and the course's LLM usage. Summary
// jobs refused with 409 `llm_consent_required` / 503 `llm_disabled` point here.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, LoadingState, ErrorState } from "../../ui/kit.tsx";
import type { Course } from "../../data.ts";
import { courseIdFor, roleIn } from "../../shared/backend.ts";
import { useResource } from "../../shared/remote-cache.ts";
import {
  SCOPES, POLICY, policies, consents, usages, loadPolicy, reloadConsents, grantConsent, revokeConsent,
  isConsentRequired, isLlmDisabled, isPolicyChanged, consentMessage, retentionText, tokensText,
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
    if (!p.enabled) {
      body = (
        <>
          <p className="pf-sub" role="note">이 서비스에서는 외부 AI 기능을 쓰지 않습니다. 쪽별 요약 생성과 학생 학습 대화가 모두 꺼져 있습니다. 이미 게시한 요약은 학생에게 계속 보입니다.</p>
          {terms}
        </>
      );
    } else {
      const u = usage && usage.data;
      const rows = (Array.isArray(mine.data) ? mine.data : []).filter((item) => SCOPES[item.scope]);
      body = (
        <>
          <p className="pf-sub">동의하면 이 과목 자료의 쪽 이미지와 텍스트(쪽별 요약), 쪽 텍스트와 게시한 해설·학생 질문(학습 대화)이 아래 제공자로 전송됩니다. 전자우편, 전화번호, 학번 형식의 숫자는 보내기 전에 가리지만 이름과 쪽 이미지 속 글자는 가리지 못합니다. 학생 이름이 든 자료는 요청 전에 확인하세요.</p>
          {terms}
          {p.acknowledgementRequired ? (
            <label className="choice pf-check">
              <input ref={ack} type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
              <span>국외 이전과 제공자의 학습 이용 가능성을 확인했습니다</span>
            </label>
          ) : null}
          {rows.map((item) => (
            <ScopeRow
              key={item.scope}
              item={item}
              policy={p}
              courseId={courseId}
              acknowledged={acknowledged}
              say={say}
              onPolicyChanged={() => { setAcknowledged(false); void loadPolicy(); }}
              focusAck={() => ack.current?.focus()}
            />
          ))}
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
  policy: LlmPolicy;
  courseId: number | string;
  acknowledged: boolean;
  say: (text: string, alert?: boolean) => void;
  onPolicyChanged: () => void;
  focusAck: () => void;
}

function ScopeRow({ item, policy, courseId, acknowledged, say, onPolicyChanged, focusAck }: ScopeRowProps) {
  const [granting, setGranting] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [armed, setArmed] = useState(false);
  const live = useRef(true);
  useEffect(() => () => { live.current = false; }, []);
  const state = !item.granted ? "동의하지 않음" : item.current ? "동의함" : "처리 조건이 바뀌어 다시 동의해야 합니다";

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
        {!item.granted || !item.current ? (
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
