// 외부 AI 처리 동의 on the professor's material screen (backend `ready`): the deployment's provider
// terms (`GET /api/llm/policy`), my consent per scope for this course with grant (acknowledgement
// when the terms say cross-border transfer or training use) and revoke, re-consent when the terms
// changed (`current: false` or 409 `llm_policy_changed`), and the course's LLM usage. Summary
// jobs refused with 409 `llm_consent_required` / 503 `llm_disabled` point here.
import { el } from "../../dom.js";
import { btn, loadingState, errorState } from "../../ui/components.js";
import { courseIdFor, roleIn } from "../../shared/backend.js";
import {
  SCOPES, POLICY, policies, consents, usages, loadPolicy, reloadConsents, grantConsent, revokeConsent,
  isConsentRequired, isLlmDisabled, isPolicyChanged, consentMessage, retentionText, tokensText,
} from "../../shared/llm-remote.js";

// replaceChildren() would turn a null child into the text "null"; skip absent parts.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false));
}

export const CONSENT_ANCHOR = "llm-consent";

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function busy(button, on, label) {
  button.disabled = on;
  if (on) button.setAttribute("aria-busy", "true");
  else button.removeAttribute("aria-busy");
  if (label) button.textContent = label;
}

/** Under a refused summary request: what to do instead of retrying. Null for other errors. */
export function consentLead(error) {
  if (isLlmDisabled(error)) return el("span", { text: "이 서비스에서는 외부 AI 기능을 쓰지 않습니다. 관리자가 켜기 전에는 요약을 만들 수 없습니다." });
  if (!isConsentRequired(error)) return null;
  const go = el("a", { href: `#${CONSENT_ANCHOR}`, text: "외부 AI 처리 동의로 이동" });
  go.addEventListener("click", (event) => {
    event.preventDefault();
    const target = document.getElementById(CONSENT_ANCHOR);
    if (!target) return;
    target.scrollIntoView({ block: "start" });
    const focus = target.querySelector("button:not([disabled]), input");
    if (focus) focus.focus();
  });
  return el("span", {}, "이 과목의 쪽별 요약 생성에 동의해야 요약을 만들 수 있습니다. ", go);
}

function fact(label, value) {
  return [el("dt", { text: label }), el("dd", { text: value })];
}

/** The consent section for `course` (sample course object), or null when this user is not its instructor. */
export function llmConsentSection(course) {
  const courseId = courseIdFor(course.id);
  if (!courseId || roleIn(course.id) !== "INSTRUCTOR") return null;
  const key = String(courseId);
  const body = el("div", { class: "stack", "aria-live": "polite" });
  const line = el("p", { class: "pf-sub", role: "status", "aria-live": "polite" });
  const section = el("section", { class: "pf-llm", id: CONSENT_ANCHOR, "aria-label": "외부 AI 처리 동의" },
    el("h3", { text: "외부 AI 처리 동의" }), body, line);
  const acknowledged = { value: false };

  function say(text, alert) {
    line.setAttribute("role", alert ? "alert" : "status");
    line.textContent = text;
  }

  function paint() {
    const policy = policies.peek(POLICY);
    const mine = consents.peek(key);
    const usage = usages.peek(key);
    if (!policy || (!policy.data && !policy.error) || !mine || (!mine.data && !mine.error)) {
      fill(body, loadingState("처리 조건과 동의를 불러오는 중입니다"));
      return;
    }
    const failed = policy.error || mine.error;
    if (!policy.data || !mine.data) {
      const again = btn("다시 불러오기", {}, "ghost");
      again.addEventListener("click", () => {
        loadPolicy();
        reloadConsents(courseId);
      });
      fill(body, errorState("동의 정보를 불러오지 못했습니다", consentMessage(failed), again));
      return;
    }
    const p = policy.data;
    const terms = el("dl", { class: "pf-llm__terms" },
      ...fact("제공자", [p.providerName, p.providerOperator].filter(Boolean).join(" · ") || "-"),
      ...fact("처리 지역", p.processingRegion || "-"),
      ...fact("보관 기간", retentionText(p.retentionDays)),
      ...fact("학습 이용", p.trainingUse ? "제공자가 입력을 서비스 개선에 쓸 수 있음" : "쓰지 않음"),
      ...fact("국외 이전", p.crossBorderTransfer ? "있음" : "없음"),
      ...fact("고지 판", p.noticeVersion || "-"),
    );
    if (!p.enabled) {
      fill(body, el("p", { class: "pf-sub", role: "note", text: "이 서비스에서는 외부 AI 기능을 쓰지 않습니다. 쪽별 요약 생성과 학생 학습 대화가 모두 꺼져 있습니다. 이미 게시한 요약은 학생에게 계속 보입니다." }), terms);
      return;
    }
    const ack = el("input", { type: "checkbox", checked: acknowledged.value ? "" : null });
    ack.addEventListener("change", () => { acknowledged.value = ack.checked; });
    const rows = (Array.isArray(mine.data) ? mine.data : []).filter((item) => SCOPES[item.scope]).map((item) => {
      const state = !item.granted ? "동의하지 않음" : item.current ? "동의함" : "처리 조건이 바뀌어 다시 동의해야 합니다";
      const buttons = [];
      if (!item.granted || !item.current) {
        const grant = btn(item.granted ? "다시 동의" : "동의", {}, "primary");
        grant.addEventListener("click", async () => {
          if (grant.disabled) return;
          if (p.acknowledgementRequired && !acknowledged.value) {
            say("국외 이전·학습 이용 내용을 확인했다고 표시해야 동의할 수 있습니다.", true);
            ack.focus();
            return;
          }
          busy(grant, true, "보내는 중…");
          say("");
          try {
            await grantConsent(courseId, item.scope, p, acknowledged.value);
            say(`${SCOPES[item.scope]}에 동의했습니다.`);
          } catch (error) {
            say(consentMessage(error), true);
            // The terms changed since this screen read them: show the new terms before asking again.
            if (isPolicyChanged(error)) {
              acknowledged.value = false;
              loadPolicy();
            }
            if (grant.isConnected) busy(grant, false, item.granted ? "다시 동의" : "동의");
          }
        });
        buttons.push(grant);
      }
      if (item.granted) {
        const revoke = btn("철회", {}, "secondary");
        revoke.addEventListener("click", async () => {
          if (revoke.disabled) return;
          if (revoke.dataset.armed !== "1") {
            revoke.dataset.armed = "1";
            revoke.textContent = "한 번 더 누르면 철회";
            return;
          }
          busy(revoke, true, "철회하는 중…");
          try {
            await revokeConsent(courseId, item.scope);
            say(`${SCOPES[item.scope]} 동의를 철회했습니다. 기다리던 작업과 질문은 취소되고, 게시한 요약은 그대로 남습니다.`);
          } catch (error) {
            say(consentMessage(error), true);
            if (revoke.isConnected) busy(revoke, false, "철회");
          }
        });
        buttons.push(revoke);
      }
      return el("div", { class: "pf-llm__scope" },
        el("b", { text: SCOPES[item.scope] }),
        el("span", { class: "pf-meta", text: state }),
        el("div", { class: "pf-actions" }, ...buttons));
    });
    const u = usage && usage.data;
    fill(body,
      el("p", { class: "pf-sub", text: "동의하면 이 과목 자료의 쪽 이미지와 텍스트(쪽별 요약), 쪽 텍스트와 게시한 해설·학생 질문(학습 대화)이 아래 제공자로 전송됩니다. 전자우편, 전화번호, 학번 형식의 숫자는 보내기 전에 가리지만 이름과 쪽 이미지 속 글자는 가리지 못합니다. 학생 이름이 든 자료는 요청 전에 확인하세요." }),
      terms,
      p.acknowledgementRequired
        ? el("label", { class: "choice pf-check" }, ack, el("span", { text: "국외 이전과 제공자의 학습 이용 가능성을 확인했습니다" }))
        : null,
      ...rows,
      u ? el("p", { class: "pf-meta", text: `사용량 · 쪽별 요약 ${u.pageSummary ? u.pageSummary.calls : 0}회 호출, ${tokensText(u.pageSummary ? u.pageSummary.inputTokens + u.pageSummary.outputTokens : 0)} · 학습 대화 오늘 ${tokensText(u.studyChatTodayTokens)} / 하루 ${tokensText(u.studyChatDailyTokenBudget)}` }) : null,
    );
  }

  paint();
  attached(section, () => {
    for (const [resource, id] of [[policies, POLICY], [consents, key], [usages, key]]) {
      resource.watch(section, id, paint, () => {
        if (resource === policies) loadPolicy();
        else resource.load(id, courseId);
      });
    }
    policies.visit("llm-policy", POLICY);
    consents.visit(`llm-consents:${key}`, key, courseId);
    usages.visit(`llm-usage:${key}`, key, courseId);
  });
  return section;
}
