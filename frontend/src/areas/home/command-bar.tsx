import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Link, navigate } from "../../router.tsx";
import { render } from "../../store.ts";
import { Icon } from "../../ui/icon.tsx";
import { activityById, typeLabel, typeIcon, TODAY } from "../../shared/format.ts";
import { nextActions, describe } from "../../shared/assistant/resolve.ts";
import type { NextAction } from "../../shared/assistant/activity-context.ts";
import type { AssistantResult } from "../../shared/assistant/activity-context.ts";
import { searchClassrooms, classroomForCommand } from "../../shared/assistant/classrooms.ts";
import { ask, execute } from "../../shared/assistant/run.ts";
import { foldTray, focusTray, openTray, refreshTray } from "../../shared/assistant-tray.tsx";
import { planGroups, runState, startRun, stopRun } from "../../shared/assistant/plan.ts";
import type { Activity } from "../../data.ts";

const MAX_ROWS = 5;
// Survive re-renders and navigation: a request in flight, the input revision it was made for, the spoken line.
let busy = false;
let inputRevision = 0;
let pendingController: AbortController | null = null;
let sayText = "";

/** The mounted command input: its current text and how to change or focus it. */
interface CommandInput {
  value(): string;
  clear(): void;
  focus(): void;
}
let commandInput: CommandInput | null = null;

const arrowTail = /\s*[→➜>]+\s*$/;

function planReply(text: string): "accept" | "cancel" | null {
  const reply = text.toLowerCase().replace(/[\s,.!?。~]+/g, "");
  if (/^(?:(?:응|네|예|그래|좋아|좋아요))?(?:ㄱㄱ|고고|진행해|진행해줘|진행해주세요|진행하자|시작해|시작하자|이대로시작|이대로진행해|그대로진행해|그렇게해|그대로해|좋아|좋아요|응|네|예|그래|오케이|ok|okay)$/.test(reply)) return "accept";
  if (/^(?:ㄴㄴ|취소|취소해|취소해줘|아니|아니요|아니야|하지마|진행하지마|멈춰|중단)$/.test(reply)) return "cancel";
  return null;
}

function say(text: string): void {
  sayText = text;
  render();
}

function busyState(on: boolean): void {
  busy = on;
  render();
}

export function planPreview(): ReactNode {
  const run = runState();
  if (run?.status !== "ready") {
    return <section className="hero-plan" id="hero-plan-preview" aria-labelledby="hero-plan-title" hidden />;
  }
  const groups = planGroups(run);
  const sequence = groups.filter((group) => group.steps.length).map((group) => group.label).join(" → ");
  return (
    <section className="hero-plan" id="hero-plan-preview" aria-labelledby="hero-plan-title" data-run={run.id} aria-busy={busy ? "true" : "false"}>
      <span className="hero-plan-label">시작 전 요약</span>
      <h2 id="hero-plan-title">이 순서로 진행할까요?</h2>
      <p className="hero-plan-count">{`총 ${run.steps.length}개 · ${sequence} 순서예요`}</p>
      {groups.map((group) => (
        <section key={group.id} className="hero-plan-group" data-group={group.id} aria-labelledby={`hero-plan-${group.id}`}>
          <h3 id={`hero-plan-${group.id}`}>{`${group.label} ${group.steps.length}개`}</h3>
          {group.steps.length
            ? (
              <ol className="hero-plan-steps">
                {group.steps.map(({ step }) => {
                  const detail = describe(step.activityId);
                  return (
                    <li key={step.activityId}>
                      <span className="hero-plan-step">{detail.title}</span>
                      <span className="hero-plan-meta">{detail.meta}</span>
                    </li>
                  );
                })}
              </ol>
            )
            : <p className="hero-plan-meta">{`계획에 담긴 ${group.label === "영상" ? "영상이" : "과제가"} 없어요.`}</p>}
        </section>
      ))}
      <p className="hero-plan-rule">
        {run.customOrder
          ? "정한 순서대로 진행해요. Plan에서 다른 항목을 먼저 선택할 수도 있어요. 제출은 직접 해 주세요."
          : "영상을 모두 들으면 과제로 넘어가요. 과제는 Plan에서 먼저 선택할 수도 있어요. 제출은 직접 해 주세요."}
      </p>
      <button
        type="button"
        className="btn btn-primary hero-plan-accept"
        aria-label="Accept — 이 계획으로 시작"
        disabled={busy}
        onClick={() => void submitCommand({ acceptRun: run.id })}
      >
        Accept
      </button>
      <p className="hero-plan-reply" id="hero-plan-reply">
        버튼을 누르거나 <strong>ㄱㄱ</strong> · <strong>진행해</strong> 입력 후 Enter
        <span>순서·항목 추가·제외를 입력해 수정할 수 있어요. 취소는 Esc</span>
      </p>
    </section>
  );
}

/** 마감이 오늘(샘플 기준일) 이내이면 임박. due는 "YYYY-MM-DDTHH:mm". */
function urgent(activity: Activity | undefined): boolean {
  if (!activity || !activity.due) return false;
  return Date.parse(activity.due) - Date.parse(`${TODAY}T00:00`) <= 24 * 3600 * 1000;
}

function ActionRow({ action }: { action: NextAction }) {
  const activity = activityById(action.id);
  const lecture = action.kind === "lecture";
  const chip = lecture ? "강의" : typeLabel(activity ? activity.type : "report");
  const cta = String(action.cta || "").replace(arrowTail, "");
  const hot = !lecture && urgent(activity);
  const progress = lecture && activity ? Math.max(0, Math.min(100, Number(activity.progress) || 0)) : null;
  return (
    <li>
      <Link
        className={`hero-action ${lecture ? "is-lecture" : "is-deadline"}`}
        href={action.href}
        aria-label={`${chip} ${action.title}, ${action.meta}. ${cta}`}
      >
        <span className={`hero-chip t-${activity ? activity.type : "report"}`} aria-hidden="true" title={chip}>
          <Icon name={typeIcon(lecture ? "video" : activity ? activity.type : "report")} />
        </span>
        <span className="hero-action-main" aria-hidden="true">
          <span className="hero-action-title">{action.title}</span>
          <span className={`hero-action-meta${hot ? " is-urgent" : ""}`}>{action.meta}</span>
          {progress == null ? null : <span className="hero-bar"><span className="hero-bar-fill" style={{ width: `${progress}%` }} /></span>}
        </span>
        <span className="hero-cta" aria-hidden="true"><span>{cta}</span><span className="hero-arrow">→</span></span>
      </Link>
    </li>
  );
}

function previewRows(text: string) {
  const query = text.trim();
  if (!query) return { rows: [], more: 0 };
  const matches = searchClassrooms(query);
  return { rows: matches.slice(0, MAX_ROWS), more: Math.max(0, matches.length - MAX_ROWS) };
}

/** 히어로의 행동 줄 + 명령 입력창. */
export function commandBar(): ReactNode {
  return <CommandBar />;
}

function CommandBar() {
  const actions = nextActions().slice(0, 2);
  const input = useRef<HTMLInputElement>(null);
  const ghostRef = useRef<HTMLDivElement>(null);
  const [value, setValue] = useState("");
  const valueRef = useRef(value);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(-1);
  const [composing, setComposing] = useState(false);
  const [focused, setFocused] = useState(false);
  const [caretEnd, setCaretEnd] = useState(true);
  const caretToEnd = useRef(false);

  const change = (next: string) => {
    valueRef.current = next;
    setValue(next);
    setSelected(-1);
    setOpen(true);
  };

  useEffect(() => {
    const handle: CommandInput = {
      value: () => valueRef.current,
      clear: () => change(""),
      focus: () => input.current?.focus(),
    };
    commandInput = handle;
    // `/`: 입력 요소에 포커스가 없을 때 명령 입력창으로. 대화상자가 열려 있으면 건드리지 않는다.
    const onSlash = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey || event.defaultPrevented) return;
      const target = event.target;
      if (target instanceof Element && target.closest("input, textarea, select, [contenteditable]")) return;
      if (document.querySelector("[role=dialog]")) return;
      if (!input.current) return;
      event.preventDefault();
      input.current.focus();
      input.current.select();
    };
    document.addEventListener("keydown", onSlash);
    return () => {
      document.removeEventListener("keydown", onSlash);
      if (commandInput === handle) commandInput = null;
    };
  }, []);

  const { rows, more } = open ? previewRows(value) : { rows: [], more: 0 };
  const listShown = rows.length > 0;
  const candidate = listShown ? rows[selected >= 0 ? selected : 0] : undefined;
  const command = candidate?.title;
  const ghost = Boolean(command && command !== value && value && focused && !busy && !composing && caretEnd);
  const prefix = ghost && command ? command.toLowerCase().startsWith(value.toLowerCase()) : false;
  const ready = runState()?.status === "ready";

  useLayoutEffect(() => {
    const node = input.current;
    if (caretToEnd.current && node) {
      caretToEnd.current = false;
      node.setSelectionRange(node.value.length, node.value.length);
    }
    if (ghostRef.current && node) ghostRef.current.scrollLeft = node.scrollLeft;
  });

  const syncCaret = () => {
    const node = input.current;
    if (node) setCaretEnd(node.selectionStart === node.value.length && node.selectionEnd === node.value.length);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.nativeEvent.isComposing || event.keyCode === 229) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      const count = previewRows(value).rows.length;
      if (!listShown && !value.trim()) return;
      setOpen(true);
      if (!count) return;
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      setSelected((current) => (current < 0 ? (step > 0 ? 0 : count - 1) : (current + step + count) % count));
    } else if (event.key === "Tab" && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey) {
      if (!ghost || !command) return;
      event.preventDefault();
      pendingController?.abort();
      inputRevision += 1;
      caretToEnd.current = true;
      change(command);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const active = listShown && selected >= 0 ? rows[selected] : undefined;
      void submitCommand({ command: active?.title });
    } else if (event.key === "Escape" && (value || listShown || ready)) {
      event.preventDefault();
      pendingController?.abort();
      inputRevision += 1;
      sayText = "";
      if (ready) {
        stopRun();
        refreshTray();
        sayText = "계획을 취소했어요. 원하는 일을 다시 입력해 주세요.";
      }
      change("");
    }
  };

  return (
    <div className="hero-assist">
      {actions.length
        ? <ul className="hero-actions" aria-label="다음 행동">{actions.map((action) => <ActionRow key={action.id} action={action} />)}</ul>
        : null}
      <div className="cmd" role="search">
        <div
          className={["cmd-box", listShown ? "has-list" : "", ghost ? "has-ghost" : "", busy ? "is-busy" : ""].filter(Boolean).join(" ")}
          data-completion={ghost ? command : ""}
        >
          <span className="cmd-ico" aria-hidden="true"><Icon name="search" /></span>
          <div className="cmd-field">
            <div ref={ghostRef} className="cmd-ghost" aria-hidden="true" hidden={!ghost}>
              {ghost && command
                ? <><span className="cmd-ghost-prefix">{value}</span>{prefix ? command.slice(value.length) : ` → ${command}`}</>
                : null}
            </div>
            <input
              ref={input}
              type="text"
              id="assistant-input"
              className="cmd-input"
              role="combobox"
              aria-label="과목이나 할 일로 바로 가기"
              aria-autocomplete="both"
              aria-expanded={listShown ? "true" : "false"}
              aria-controls="assistant-list"
              aria-haspopup="listbox"
              aria-description="추천 명령은 Tab으로 완성하고 Enter로 실행합니다. 위아래 화살표로 다른 추천을 선택합니다."
              aria-activedescendant={listShown && selected >= 0 ? `assistant-opt-${selected}` : undefined}
              aria-describedby={ready ? "hero-plan-reply" : undefined}
              aria-busy={busy ? "true" : "false"}
              readOnly={busy}
              placeholder={ready ? "계획 수정 또는 ㄱㄱ · Enter" : "과목이나 할 일 (예: 데베 강의)"}
              autoComplete="off"
              autoCapitalize="off"
              spellCheck="false"
              enterKeyHint="go"
              value={value}
              onChange={(event) => {
                pendingController?.abort();
                inputRevision += 1;
                sayText = "";
                change(event.target.value);
                syncCaret();
              }}
              onCompositionStart={() => setComposing(true)}
              onCompositionEnd={() => {
                setComposing(false);
                setSelected(-1);
                setOpen(true);
              }}
              onClick={syncCaret}
              onSelect={syncCaret}
              onKeyUp={syncCaret}
              onScroll={syncCaret}
              onKeyDown={onKeyDown}
              onBlur={() => {
                // 옵션을 누르는 동안(mousedown)에는 포커스를 유지하므로 여기서는 그냥 닫는다.
                setFocused(false);
                setOpen(false);
                setSelected(-1);
              }}
              onFocus={() => {
                setFocused(true);
                syncCaret();
                if (valueRef.current.trim()) {
                  setSelected(-1);
                  setOpen(true);
                }
              }}
            />
          </div>
          <kbd className="cmd-key" aria-hidden="true">{ghost ? "Tab" : "/"}</kbd>
          <button type="button" className="cmd-go" aria-label="실행" onClick={() => void submitCommand({})}>
            <span aria-hidden="true">→</span>
          </button>
          <span className="cmd-spin" aria-hidden="true" />
        </div>
        <ul
          className="cmd-list"
          id="assistant-list"
          role="listbox"
          aria-label="해석 미리보기"
          hidden={!listShown}
          onMouseDown={(event) => event.preventDefault()}
        >
          {rows.map((row, i) => (
            <li
              key={`${row.courseId}-${i}`}
              className="cmd-opt"
              role="option"
              id={`assistant-opt-${i}`}
              aria-selected={i === selected ? "true" : "false"}
              data-course={row.courseId}
              data-command={row.title}
              onClick={() => void submitCommand({ command: row.title })}
            >
              <span className="cmd-opt-title">{row.title}</span>
              <span className="cmd-opt-meta">바로 이동 · Tab 완성 · ↑↓ 다른 추천</span>
            </li>
          ))}
          {more > 0 ? <li className="cmd-more" role="presentation">{`외 ${more}개 · 과목명을 더 입력해 주세요`}</li> : null}
        </ul>
      </div>
      <p className="cmd-say" id="assistant-say" aria-live="polite" aria-atomic="true">{sayText}</p>
    </div>
  );
}

/**
 * `assistant-ask`: 강의실 명령은 로컬 색인으로 즉시 이동하고, 그 외 입력은 AI에 전달한다.
 * `acceptRun`: the plan preview's Accept (as if "ㄱㄱ" were typed); `command`: the picked suggestion.
 */
async function submitCommand({ acceptRun, command }: { acceptRun?: string; command?: string }): Promise<void> {
  const field = commandInput;
  if (!field || busy) return;
  const text = acceptRun ? "ㄱㄱ" : field.value().trim();
  if (!text) return;
  const reply = planReply(text);
  const run = runState();
  if (acceptRun && (acceptRun !== run?.id || run.status !== "ready")) return;
  if (reply && run?.status === "ready") {
    field.clear();
    if (reply === "cancel") {
      stopRun();
      refreshTray();
      say("계획을 취소했어요. 원하는 일을 다시 입력해 주세요.");
    } else {
      startRun({ navigate });
      refreshTray();
    }
    return;
  }
  if (reply === "accept" && !run) {
    field.clear();
    say("먼저 원하는 계획을 입력해 주세요. 요약을 확인한 뒤 시작할 수 있어요.");
    return;
  }
  // 현재 계획을 문맥으로 유지한다. 수정·거절·새 요청은 AI 응답이 확정된 뒤 적용한다.
  const revision = inputRevision;
  const isCurrent = () => commandInput === field && inputRevision === revision && field.value().trim() === text;
  const classroom = classroomForCommand(command || text);
  busy = true;
  say("");
  let result: AssistantResult | null;
  if (classroom) {
    result = { kind: "go", intent: "course", courseId: classroom.courseId, targets: [], say: classroom.title };
    foldTray();
  } else {
    const controller = new AbortController();
    pendingController = controller;
    try {
      result = await ask(text, { signal: controller.signal });
    } catch {
      result = null;
    } finally {
      if (pendingController === controller) pendingController = null;
    }
  }
  if (!isCurrent() || (run && runState()?.id !== run.id)) {
    busyState(false);
    return;
  }
  if (!result) {
    busy = false;
    say("요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요");
    return;
  }
  const answer = result;
  let leaving = false;
  const request = execute(answer, {
    text,
    navigate: (href) => {
      leaving = true;
      sayText = answer.say;
      busy = false;
      if (isCurrent()) navigate(href);
      else render();
    },
  });
  if (request) openTray();
  if (leaving) return;
  busy = false;
  sayText = answer.say;
  if (answer.kind === "choose") focusTray();
  refreshTray();
  const current = commandInput;
  if (current) {
    current.clear();
    if (["clarify", "plan", "revise", "reject"].includes(answer.kind)) current.focus();
    if (answer.kind === "plan") sayText = "계획을 요약했어요. Accept를 누르거나 ㄱㄱ · 진행해라고 입력하면 시작해요.";
    if (answer.kind === "revise") sayText = "계획을 수정했어요. 전체 목록을 확인한 뒤 Accept 또는 ㄱㄱ로 시작해 주세요.";
  }
  render();
}
