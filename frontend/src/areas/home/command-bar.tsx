import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { Link, navigate } from "../../router.tsx";
import { render } from "../../store.ts";
import { currentRole, routeFor } from "../../shared/routes.ts";
import { markChatUsed, setHeroInputOut } from "../../shared/assistant/chat-state.ts";
import { isTodayAllRequest } from "../../shared/assistant/aliases.ts";
import { Icon } from "../../ui/icon.tsx";
import { activityById, typeLabel, typeIcon, TODAY } from "../../shared/format.ts";
import { nextActions, describe } from "../../shared/assistant/resolve.ts";
import type { NextAction } from "../../shared/assistant/activity-context.ts";
import type { AssistantResult } from "../../shared/assistant/activity-context.ts";
import { searchClassrooms, classroomForCommand } from "../../shared/assistant/classrooms.ts";
import { ask, execute } from "../../shared/assistant/run.ts";
import { foldTray, focusTray, openTray, refreshTray } from "../../shared/assistant-tray.tsx";
import { createRun, planGroups, runState, startRun, stopRun, todayDeadlinePlan } from "../../shared/assistant/plan.ts";
import type { Activity } from "../../data.ts";
import { backendState, backendStatus, subscribe as onBackendChange } from "../../shared/backend.ts";
import { serverCourseViews } from "../../shared/course-view.ts";
import { useHome, useTodo, loadMoreTodo, homeMessage } from "../../shared/home-remote.ts";
import { currentServerActions, serverPrompts, serverActionForCommand, TODO_COMMAND, type ServerAction } from "../../shared/assistant/server-home.ts";

const MAX_ROWS = 5;
// Survive re-renders and navigation: a request in flight, the input revision it was made for, the spoken line.
let busy = false;
let inputRevision = 0;
let pendingController: AbortController | null = null;
let sayText = "";

/** The mounted command input: its current text and how to change or focus it. */
export interface CommandInput {
  value(): string;
  clear(): void;
  focus(): void;
}
let commandInput: CommandInput | null = null;
/** 다른 화면의 챗봇 도크 입력창(홈 히어로 입력창과 동시에 보이지 않는다). */
let dockInput: CommandInput | null = null;
let serverListVisible = false;
let session = `${backendStatus()}:${backendState().me?.userId ?? ""}`;
onBackendChange((next) => {
  const owner = `${next.status}:${next.me?.userId ?? ""}`;
  if (session === owner) return;
  session = owner;
  pendingController?.abort();
  pendingController = null;
  inputRevision += 1;
  busy = false;
  sayText = "";
  serverListVisible = false;
  commandInput?.clear();
  dockInput?.clear();
});

export function bindDock(handle: CommandInput | null): void {
  dockInput = handle;
}

/** 도크가 그릴 값: 처리 중 여부와 마지막 안내 문장. */
export function dockStatus(): { busy: boolean; say: string } {
  return { busy, say: sayText };
}

/** 입력이 바뀌거나 Esc로 비울 때: 진행 중인 요청을 버리고 안내 문장을 지운다. */
export function dropPending(): void {
  pendingController?.abort();
  inputRevision += 1;
  sayText = "";
}

export function submitDock(field: CommandInput): Promise<void> {
  return submitCommand({ field });
}

/** Puts the caret in the hero's command input (no-op when it is not mounted). */
export function focusCommand(): void {
  commandInput?.focus();
}

// One tap runs the sentence: a plan request, an activity request, and a local classroom jump.
const PROMPTS = ["오늘 할 일 전부 해줘", "데베 강의 틀어줘", "마감 임박한 과제 열어줘", "기계학습 강의실로 이동"];

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
  if (backendStatus() === "ready") {
    return serverListVisible ? <ServerTodoPreview /> : <section className="hero-plan" id="hero-plan-preview" hidden />;
  }
  const run = runState();
  if (run?.status !== "ready") {
    return <section className="hero-plan" id="hero-plan-preview" aria-labelledby="hero-plan-title" hidden />;
  }
  const groups = planGroups(run);
  return (
    <section className="hero-plan" id="hero-plan-preview" aria-labelledby="hero-plan-title" data-run={run.id} aria-busy={busy ? "true" : "false"}>
      <h2 id="hero-plan-title">이 순서로 진행할까요?</h2>
      {groups.map((group) => (
        <section key={group.id} className="hero-plan-group" data-group={group.id} aria-labelledby={`hero-plan-${group.id}`}>
          <h3 id={`hero-plan-${group.id}`}>{`${group.label} ${group.steps.length}개`}</h3>
          {group.steps.length
            ? (
              <ol className="hero-plan-steps">
                {group.steps.map(({ step }) => {
                  const detail = describe(step.activityId);
                  const type = activityById(step.activityId)?.type ?? "report";
                  return (
                    <li key={step.activityId}>
                      <span className={`hero-chip t-${type}`} role="img" aria-label={typeLabel(type)} title={typeLabel(type)}><Icon name={typeIcon(type)} /></span>
                      <span className="hero-plan-text">
                        <span className="hero-plan-step">{detail.title}</span>
                        <span className="hero-plan-meta">{detail.meta}</span>
                      </span>
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
function ServerActionRow({ action }: { action: ServerAction }) {
  return (
    <li>
      <Link className={`hero-action ${action.kind === "video" ? "is-lecture" : "is-deadline"}`} href={action.href} aria-label={`${action.label} ${action.title}, ${action.meta}. 열기`}>
        <span className={`hero-chip t-${action.kind}`} aria-hidden="true" title={action.label}><Icon name={typeIcon(action.kind)} /></span>
        <span className="hero-action-main" aria-hidden="true">
          <span className="hero-action-title">{action.title}</span>
          <span className={`hero-action-meta${action.urgent ? " is-urgent" : ""}`}>{action.meta}</span>
        </span>
        <span className="hero-cta" aria-hidden="true"><span>열기</span><span className="hero-arrow">→</span></span>
      </Link>
    </li>
  );
}

function ServerTodoPreview() {
  const entry = useTodo();
  useHome();
  const actions = currentServerActions();
  return (
    <section className="hero-plan" id="hero-plan-preview" aria-labelledby="hero-plan-title" aria-busy={entry?.loading ? "true" : "false"}>
      <h2 id="hero-plan-title">서버 할 일 목록</h2>
      {entry?.error
        ? <p className="hero-plan-meta" role="status">{homeMessage(entry.error)}</p>
        : !entry?.data
          ? <p className="hero-plan-meta" role="status">할 일을 불러오는 중이에요.</p>
          : actions.length
            ? <ul className="hero-actions" aria-label="서버 할 일">{actions.map((action) => <ServerActionRow key={action.item.activityId} action={action} />)}</ul>
            : <p className="hero-plan-meta">현재 열 수 있는 할 일이 없어요.</p>}
      {entry?.data?.nextCursor && !entry.error
        ? <button type="button" className="btn" disabled={Boolean(entry.loading)} onClick={() => void loadMoreTodo()}>할 일 더 보기</button>
        : null}
      <p className="hero-plan-rule">항목을 선택하면 해당 활동으로 이동해요. 학습과 제출은 활동 화면에서 직접 진행해 주세요.</p>
    </section>
  );
}

function previewRows(text: string) {
  const query = text.trim();
  if (!query) return { rows: [], more: 0 };
  const matches = searchClassrooms(query);
  return { rows: matches.slice(0, MAX_ROWS), more: Math.max(0, matches.length - MAX_ROWS) };
}

// 입력 중에는 움직이는 GIF 대신 정지 이미지를 보인다(동작 줄이기 설정에서는 늘 정지 이미지).
const MASCOT_STILL = "/app/assets/mascot/orange-mascot-still.png";

/** 히어로의 다음 행동 줄 + 한 번에 실행하는 추천 문장 + 맨 아래 명령 입력창. */
export function commandBar(onActivate: () => void): ReactNode {
  return <CommandBar key={`${backendStatus()}:${backendState().me?.userId ?? ""}`} onActivate={onActivate} />;
}

function CommandBar({ onActivate }: { onActivate: () => void }) {
  const server = backendStatus() === "ready";
  useHome();
  const todo = useTodo();
  const serverRows = server ? currentServerActions() : [];
  const actions = server ? [] : nextActions().slice(0, 2);
  const prompts = server ? serverPrompts(serverRows) : PROMPTS;
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
  const cmdRef = useRef<HTMLDivElement>(null);

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

  // 입력창이 스크롤로 화면 밖에 나가면 셸이 우측 하단에 챗봇 FAB·입력창을 띄운다.
  useEffect(() => {
    const node = cmdRef.current;
    if (!node || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry && setHeroInputOut(!entry.isIntersecting)) render();
    });
    observer.observe(node);
    return () => {
      observer.disconnect();
      setHeroInputOut(false);
    };
  }, []);

  const { rows, more } = open ? previewRows(value) : { rows: [], more: 0 };
  const listShown = rows.length > 0;
  const candidate = listShown ? rows[selected >= 0 ? selected : 0] : undefined;
  const command = candidate?.title;
  const ghost = Boolean(command && command !== value && value && focused && !busy && !composing && caretEnd);
  const prefix = ghost && command ? command.toLowerCase().startsWith(value.toLowerCase()) : false;
  const ready = !server && runState()?.status === "ready";

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

  const runPrompt = (text: string) => {
    pendingController?.abort();
    inputRevision += 1;
    sayText = "";
    change(text);
    setOpen(false);
    void submitCommand({});
  };

  return (
    <div className="hero-assist">
      {serverRows.length
        ? <ul className="hero-actions" aria-label="다음 행동">{serverRows.slice(0, 2).map((action) => <ServerActionRow key={action.item.activityId} action={action} />)}</ul>
        : actions.length
          ? <ul className="hero-actions" aria-label="다음 행동">{actions.map((action) => <ActionRow key={action.id} action={action} />)}</ul>
          : null}
      {ready
        ? null
        : (
          <ul className="hero-prompts" aria-label="추천 문장">
            {prompts.map((text) => (
              <li key={text}>
                <button type="button" className="hero-prompt" disabled={busy} onClick={() => runPrompt(text)}>{text}</button>
              </li>
            ))}
          </ul>
        )}
      <p className="cmd-say" id="assistant-say" aria-live="polite" aria-atomic="true">{sayText || (server && todo?.error ? homeMessage(todo.error) : server && !todo?.data ? "할 일을 불러오는 중이에요." : "")}</p>
      <div className="cmd" role="search" ref={cmdRef}>
        <div
          className={["cmd-box", listShown ? "has-list" : "", ghost ? "has-ghost" : "", busy ? "is-busy" : ""].filter(Boolean).join(" ")}
          data-completion={ghost ? command : ""}
        >
          <span className="cmd-ico" aria-hidden="true">
            {value
              ? <img src={MASCOT_STILL} alt="" width="80" height="80" />
              : (
                <picture>
                  <source media="(prefers-reduced-motion: reduce)" srcSet={MASCOT_STILL} />
                  <img src="/app/assets/mascot/orange-mascot.gif" alt="" width="80" height="80" />
                </picture>
              )}
          </span>
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
              aria-label={server ? "과목이나 활동 이동을 한 줄로 입력" : "과목 이동이나 할 일 계획을 한 줄로 입력"}
              aria-autocomplete="both"
              aria-expanded={listShown ? "true" : "false"}
              aria-controls="assistant-list"
              aria-haspopup="listbox"
              aria-description="추천 명령은 Tab으로 완성하고 Enter로 실행합니다. 위아래 화살표로 다른 추천을 선택합니다."
              aria-activedescendant={listShown && selected >= 0 ? `assistant-opt-${selected}` : undefined}
              aria-describedby={ready ? "hero-plan-reply" : undefined}
              aria-busy={busy ? "true" : "false"}
              readOnly={busy}
              placeholder={server ? "무엇을 할까요? 예: 할 일 목록 보여줘" : ready ? "계획 수정 또는 ㄱㄱ · Enter" : "무엇을 할까요? 예: 오늘 할 일 전부 해줘"}
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
              onClick={() => {
                onActivate();
                syncCaret();
              }}
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
                onActivate();
                setFocused(true);
                syncCaret();
                if (valueRef.current.trim()) {
                  setSelected(-1);
                  setOpen(true);
                }
              }}
            />
          </div>
          {ghost ? <kbd className="cmd-key" aria-hidden="true">Tab</kbd> : null}
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
    </div>
  );
}

/**
 * `assistant-ask`: 강의실 명령은 로컬 색인으로 즉시 이동하고, 그 외 입력은 AI에 전달한다.
 * `acceptRun`: the plan preview's Accept (as if "ㄱㄱ" were typed); `command`: the picked suggestion.
 */
async function submitCommand({ acceptRun, command, field: given }: { acceptRun?: string; command?: string; field?: CommandInput }): Promise<void> {
  const field = given || commandInput;
  if (!field || busy) return;
  const docked = Boolean(given);
  const text = acceptRun ? "ㄱㄱ" : field.value().trim();
  if (!text) return;
  markChatUsed();
  if (backendStatus() === "ready") {
    const requested = command || text;
    const classroom = classroomForCommand(requested);
    const action = serverActionForCommand(requested, currentServerActions());
    field.clear();
    if (classroom) {
      const course = serverCourseViews().find(({ course }) => course?.id === classroom.courseId)?.course;
      if (course) {
        sayText = "";
        navigate(routeFor(currentRole(), "classroom", { kj: course.id, special: course.kind === "special" }));
        return;
      }
    }
    if (action) {
      sayText = "";
      navigate(action.href);
      return;
    }
    if (requested.replace(/\s+/g, "") === TODO_COMMAND.replace(/\s+/g, "")) {
      serverListVisible = true;
      say("서버 할 일 목록에서 활동을 선택해 주세요. 자동 실행이나 제출은 하지 않아요.");
      if (docked) navigate(routeFor(currentRole(), "home"));
      return;
    }
    say("과목명을 입력해 강의실을 선택하거나, ‘할 일 목록 보여줘’로 활동을 찾아 주세요. 자동 실행이나 제출은 지원하지 않아요.");
    return;
  }
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
  // 목업: "오늘 할 일 전부 해줘"는 AI 없이 오늘 마감 할 일(영상 → 과제)을 Plan으로 쌓고 바로 시작한다.
  if (isTodayAllRequest(text)) {
    const targets = todayDeadlinePlan();
    field.clear();
    if (!targets.length) {
      say("오늘 처리할 일이 없어요.");
      return;
    }
    sayText = "";
    createRun(targets);
    startRun({ navigate });
    refreshTray();
    openTray();
    return;
  }
  // 현재 계획을 문맥으로 유지한다. 수정·거절·새 요청은 AI 응답이 확정된 뒤 적용한다.
  const revision = inputRevision;
  const isCurrent = () => (commandInput === field || dockInput === field) && inputRevision === revision && field.value().trim() === text;
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
      sayText = "";
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
  const current = docked ? dockInput : commandInput;
  if (current) {
    current.clear();
    if (["clarify", "plan", "revise", "reject"].includes(answer.kind)) current.focus();
    if (answer.kind === "plan") sayText = "계획을 요약했어요. Accept를 누르거나 ㄱㄱ · 진행해라고 입력하면 시작해요.";
    if (answer.kind === "revise") sayText = "계획을 수정했어요. 전체 목록을 확인한 뒤 Accept 또는 ㄱㄱ로 시작해 주세요.";
  }
  // 계획 요약과 Accept는 홈 히어로에 있다. 다른 화면에서 만든 계획은 홈에서 확인하게 한다.
  if (docked && (answer.kind === "plan" || answer.kind === "revise")) {
    navigate(routeFor("student", "home"));
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
  }
  render();
}
