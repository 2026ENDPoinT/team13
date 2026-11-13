import {
  createRef, useEffect, useLayoutEffect, useState, useSyncExternalStore,
  type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent, type ReactNode,
} from "react";
import { getState, render } from "../store.ts";
import { navigate } from "../router.tsx";
import { Icon } from "../ui/icon.tsx";
import type { Screen } from "../types.ts";
import { activityById } from "./format.ts";
import { describe } from "./assistant/resolve.ts";
import { choose, requests, isDone } from "./assistant/run.ts";
import {
  advance, manualStep, pauseRun, planGroups, progressCount, reorderStep, resumeRun, runState, selectStep, stepInfo, stepStatus, stopRun,
} from "./assistant/plan.ts";
import type { AssistantRequest, PlanRun } from "./assistant/activity-context.ts";
import { chatShut, chatUsed, heroInputOut, toggleChatShut } from "./assistant/chat-state.ts";
import { currentRole } from "./routes.ts";

interface DragRow {
  index: number;
  top: number;
  center: number;
}

interface DragState {
  handle: HTMLButtonElement;
  panel: HTMLElement;
  rows: DragRow[];
  runId: PlanRun["id"];
  cursor: PlanRun["cursor"];
  status: PlanRun["status"];
  selectionVersion: PlanRun["selectionVersion"];
  from: number;
  to: number;
  pointerId: number;
  startY: number;
  y: number;
  scrollTop: number;
  panelTop: number;
  panelBottom: number;
  moved: boolean;
  frame: number;
  /** translateY per row index while the badge is dragged. */
  shifts: Map<number, number>;
}

// 하단 트레이: 오늘 계획과, 고를 게 남은 choose 요청만 보여 준다(이동·없음·계획 결과는 쌓지 않는다). 화면을 옮겨도
// 펼침/접힘이 남도록 이 모듈의 상태로 두고, 화면마다 그 상태로 다시 그린다.
let expanded = false;
let enterPending = false;
let lastScreen: Screen | null = null;
let playerKey = "";
// 계획 단계 펼침(드릴다운)도 다시 그려도 남도록 모듈 상태로 둔다. 계획이 바뀌면 비운다.
const openSteps = new Set<string>();
let openRunId = "";
let pickedStepId = "";
let drag: DragState | null = null;
/** 드래그를 끝낸 배지에 뒤따르는 click을 한 번 무시한다(활동 id). */
let suppressClick = "";
/** 순서 변경 안내(`.plan-order-status`). 트레이를 새로 그리면 비운다. */
let orderStatus = "";
/** 다음 그리기 뒤에 포커스할 요소의 `data-key`(없으면 알약). */
let pendingFocus = "";
/** 다음 그리기 뒤에 고를 요청의 첫 항목으로 포커스를 옮긴다. */
let focusChoice = false;
const trayRef = createRef<HTMLElement>();

// 재생 위치와 드래그는 트레이만 다시 그린다(화면 전체를 다시 그리지 않는다).
let trayVersion = 0;
const trayListeners = new Set<() => void>();

function paintTray(): void {
  trayVersion += 1;
  trayListeners.forEach((fn) => fn());
}

function subscribeTray(fn: () => void): () => void {
  trayListeners.add(fn);
  return () => {
    trayListeners.delete(fn);
  };
}

const trayVersionNow = (): number => trayVersion;

function movableSteps(run: PlanRun, index: number): number[] {
  if (!["ready", "running", "paused"].includes(run.status)) return [];
  const group = planGroups(run).find((item) => item.steps.some((entry) => entry.index === index));
  return (group?.steps || []).filter(({ step, index: at }) => step.status === "pending" && (run.status === "ready" || at !== run.cursor))
    .map((entry) => entry.index);
}

function moveStep(runId: PlanRun["id"], from: number, to: number): void {
  const step = runState()?.steps[from];
  if (!step || !reorderStep(runId, from, to)) return;
  pickedStepId = "";
  refreshTray();
  pendingFocus = `reorder-${step.activityId}`;
  orderStatus = `${stepInfo(step).title}, ${to + 1}번째로 이동`;
}

function cancelDrag(): void {
  const active = drag;
  if (!active) return;
  drag = null;
  cancelAnimationFrame(active.frame);
  if (active.handle.hasPointerCapture(active.pointerId)) active.handle.releasePointerCapture(active.pointerId);
  paintTray();
}

function paintDrag(): void {
  const active = drag;
  if (!active) return;
  const run = runState();
  if (!document.contains(active.handle) || run?.id !== active.runId || run.cursor !== active.cursor
    || run.status !== active.status || run.selectionVersion !== active.selectionVersion) {
    cancelDrag();
    return;
  }
  const source = active.rows.find((row) => row.index === active.from);
  const first = active.rows[0];
  const last = active.rows[active.rows.length - 1];
  if (!source || !first || !last) {
    cancelDrag();
    return;
  }
  const offset = active.y - active.startY + active.panel.scrollTop - active.scrollTop;
  if (!active.moved && Math.abs(offset) < 4) return;
  active.moved = true;
  const dy = Math.max(first.top - source.top, Math.min(last.top - source.top, offset));
  const center = source.center + dy;
  active.to = active.rows.reduce((nearest, row) =>
    Math.abs(row.center - center) < Math.abs(nearest.center - center) ? row : nearest, source).index;
  const from = active.rows.findIndex((row) => row.index === active.from);
  const to = active.rows.findIndex((row) => row.index === active.to);
  const rows = active.rows;
  active.shifts = new Map(rows.map((row, at) => [row.index, at === from ? dy
    : from < at && at <= to ? rows[at - 1].top - row.top
    : to <= at && at < from ? rows[at + 1].top - row.top : 0]));
  paintTray();
}

function scrollDrag(): void {
  const active = drag;
  if (!active) return;
  if (!document.contains(active.handle)) {
    cancelDrag();
    return;
  }
  if (active.moved) {
    const edge = 32;
    const speed = active.y < active.panelTop + edge ? -8 : active.y > active.panelBottom - edge ? 8 : 0;
    if (speed) {
      active.panel.scrollTop += speed;
      paintDrag();
    }
  }
  if (drag === active) active.frame = requestAnimationFrame(scrollDrag);
}

function ReorderHandle({ run, index }: { run: PlanRun; index: number }): ReactNode {
  const activityId = run.steps[index].activityId;
  const slots = movableSteps(run, index);
  const movable = slots.includes(index) && slots.length > 1;
  const picked = pickedStepId === activityId;

  const onClick = (event: ReactMouseEvent<HTMLButtonElement>): void => {
    event.stopPropagation();
    if (suppressClick === activityId) {
      suppressClick = "";
      return;
    }
    const from = run.steps.findIndex((step) => step.activityId === pickedStepId);
    if (slots.includes(from) && from !== index) {
      moveStep(run.id, from, index);
      return;
    }
    pickedStepId = picked ? "" : activityId;
    refreshTray();
    if (pickedStepId) orderStatus = "이동할 위치의 배지를 클릭하세요, Escape로 취소";
  };

  const onKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      cancelDrag();
      pickedStepId = "";
      refreshTray();
    } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      const to = slots[slots.indexOf(index) + (event.key === "ArrowUp" ? -1 : 1)];
      if (to !== undefined) moveStep(run.id, index, to);
    }
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    if (!event.isPrimary || event.button !== 0 || drag) return;
    const handle = event.currentTarget;
    const list = handle.closest(".plan-steps");
    const panel = handle.closest<HTMLElement>(".tray-panel");
    if (!list || !panel) return;
    const bounds = panel.getBoundingClientRect();
    const rows = [...list.children].flatMap((node): DragRow[] => {
      const line = node.querySelector(".plan-line");
      const at = node instanceof HTMLElement ? Number(node.dataset.index) : NaN;
      if (!line || !slots.includes(at)) return [];
      const box = node.getBoundingClientRect();
      const lineBox = line.getBoundingClientRect();
      return [{ index: at, top: box.top, center: lineBox.top + lineBox.height / 2 }];
    });
    suppressClick = "";
    handle.focus({ preventScroll: true });
    handle.setPointerCapture(event.pointerId);
    const active: DragState = {
      handle, panel, rows, runId: run.id, cursor: run.cursor, status: run.status, selectionVersion: run.selectionVersion,
      from: index, to: index, pointerId: event.pointerId, startY: event.clientY, y: event.clientY,
      scrollTop: panel.scrollTop, panelTop: bounds.top, panelBottom: bounds.bottom, moved: false, frame: 0, shifts: new Map(),
    };
    drag = active;
    active.frame = requestAnimationFrame(scrollDrag);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    const active = drag;
    if (active?.handle !== event.currentTarget || event.pointerId !== active.pointerId) return;
    active.y = event.clientY;
    paintDrag();
  };

  const onPointerUp = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    const current = drag;
    if (current?.handle !== event.currentTarget || event.pointerId !== current.pointerId) return;
    paintDrag();
    const active = drag;
    if (!active) return;
    suppressClick = active.moved ? activityId : "";
    cancelDrag();
    if (active.moved) moveStep(active.runId, active.from, active.to);
  };

  const onPointerCancel = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    if (drag?.handle === event.currentTarget && event.pointerId === drag.pointerId) {
      suppressClick = activityId;
      cancelDrag();
    }
  };

  const onLostPointerCapture = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    if (drag?.handle === event.currentTarget && event.pointerId === drag.pointerId) cancelDrag();
  };

  return (
    <button
      type="button"
      className={`plan-handle${picked ? " is-picked" : ""}`}
      disabled={!movable}
      data-key={`reorder-${activityId}`}
      aria-label={`${stepInfo(run.steps[index]).title} 순서 변경`}
      aria-pressed={picked ? "true" : "false"}
      aria-describedby={movable ? "plan-order-help" : undefined}
      title={movable ? "같은 섹션 안에서 드래그 · 배지 두 개를 차례로 클릭 · 방향키로 이동" : "현재·완료 단계는 순서 고정"}
      {...(movable ? { onClick, onKeyDown, onPointerDown, onPointerMove, onPointerUp, onPointerCancel, onLostPointerCapture } : {})}
    >
      <span className="plan-node" aria-hidden="true" />
    </button>
  );
}

function isPlayer(screen: Screen | null): boolean {
  return Boolean(screen && screen.kind === "player");
}

function screenKey(): string {
  const params = new URLSearchParams(location.search);
  params.delete("autoplay");
  return `${location.pathname}?${params}`;
}

function pendingChoice(): AssistantRequest | undefined {
  return requests().find((request) => request.kind === "choose" && !request.chosen);
}

/** 요청 항목을 골랐을 때: 버튼만 남기고 접은 뒤 그 활동으로 이동한다. */
function pickTarget(requestId: AssistantRequest["id"], activityId: string): void {
  foldTray();
  choose(requestId, activityId, { navigate });
}

function TargetButton({ request, id, index }: { request: AssistantRequest; id: string; index: number }): ReactNode {
  const d = describe(id);
  const done = isDone(id);
  const picked = request.chosen === id;
  return (
    <li className="tray-item">
      <button
        type="button"
        className={`tray-target${done ? " is-done" : ""}${picked ? " is-picked" : ""}`}
        data-key={`choice-${request.id}-${id}`}
        onClick={() => pickTarget(request.id, id)}
      >
        <span className="tray-n" aria-hidden="true">{index < 9 ? String(index + 1) : "·"}</span>
        <span className="tray-label">
          <span className="tray-title">{d.title}</span>
          {d.meta ? <span className="tray-meta">{` · ${d.meta}`}</span> : null}
        </span>
        {done ? <span className="tray-check"><Icon name="check" /><span className="sr-only">완료</span></span> : null}
      </button>
    </li>
  );
}

function RequestBlock({ request, active }: { request: AssistantRequest; active: boolean }): ReactNode {
  const targets = request.targets || [];
  return (
    <li className={`tray-req${active ? " is-active" : ""}`} data-request={request.id}>
      <p className="tray-say">{request.say || ""}</p>
      {targets.length ? (
        <ol className="tray-targets">
          {targets.map((id, i) => <TargetButton key={id} request={request} id={id} index={i} />)}
        </ol>
      ) : null}
    </li>
  );
}

const pad2 = (n: number): string => String(Math.floor(n)).padStart(2, "0");
function clock(total: number): string {
  const seconds = Math.max(0, Math.round(total));
  return `${pad2(seconds / 60)}:${pad2(seconds % 60)}`;
}

/**
 * 단계를 끝까지 봤거나 제출했는가. isDone(활동 상태·positions/minutes 기준)에 더해, 샘플 영상은 실제 길이가
 * 차시 시간보다 짧으므로 플레이어가 영상 길이 90%에 닿았을 때 남기는 player.watched도 본다.
 */
export function finished(activityId: string): boolean {
  return isDone(activityId) || Boolean(getState().player?.watched?.[activityId]);
}

function positionOf(activityId: string): number {
  const seconds = Number(getState().player?.positions?.[activityId]);
  return Number.isFinite(seconds) ? seconds : 0;
}

function totalOf(activity: { id: string; minutes?: number }): number {
  const player = getState().player;
  if (player?.id === activity.id && player.duration > 0) return player.duration;
  return activity.minutes && activity.minutes > 0 ? activity.minutes * 60 : 0;
}

function runLabel(run: PlanRun): string {
  if (run.status === "paused") return "일시정지";
  return run.steps[run.cursor]?.mode === "auto" ? "재생 중" : "확인 필요";
}

function stepText(run: PlanRun, index: number, status: string): string {
  const step = run.steps[index];
  const activity = activityById(step.activityId);
  if (status === "done") return "완료";
  if (status === "skipped") return "건너뜀";
  if (status === "waiting") return "확인 필요";
  if (status === "running") {
    const total = activity ? totalOf(activity) : 0;
    return total ? `재생 중 ${clock(positionOf(step.activityId))}/${clock(total)}` : "재생 중";
  }
  if (index === run.cursor && run.status === "paused") return "일시정지";
  if (step.server) return stepInfo(step).meta;
  if (!activity) return "";
  if (activity.minutes && activity.minutes > 0) return `${activity.minutes}분`;
  return activity.due ? `${activity.due.slice(5, 10).replace("-", "/")}까지` : "";
}

/**
 * 세부 단계: 영상은 이동·재생·끝까지 시청·다음, 과제는 이동·작성/제출·확인, 그 밖은 이동·확인.
 * 서버 단계는 이 브라우저에 재생 위치·제출 기록이 없으므로 이동만 표시하고 나머지는 단계가 끝날 때 채운다.
 */
function subSteps(run: PlanRun, index: number): { label: string; done: boolean }[] {
  const step = run.steps[index];
  const type = step.server ? step.server.kind : activityById(step.activityId)?.type;
  const labels = type === "video"
    ? ["이동", "재생", "끝까지 시청(90%)", "다음"]
    : type === "report" ? ["이동", "작성·제출", "확인"] : ["이동", "확인"];
  const status = stepStatus(index);
  if (status === "done" || status === "skipped") {
    return labels.map((label) => ({ label, done: status === "done" }));
  }
  if (index !== run.cursor) return labels.map((label) => ({ label, done: false }));
  const arrived = run.status === "running";
  if (step.server) return labels.map((label, i) => ({ label, done: i === 0 && arrived }));
  const done = finished(step.activityId);
  const player = getState().player;
  const flags = labels.length === 4
    ? [arrived, arrived && (positionOf(step.activityId) > 0 || Boolean(player?.playing && player.id === step.activityId)), done, false]
    : labels.length === 3 ? [arrived, done, false] : [arrived, false];
  return labels.map((label, i) => ({ label, done: flags[i] }));
}

function SubList({ run, index }: { run: PlanRun; index: number }): ReactNode {
  return (
    <ol className="plan-sub" id={`plan-sub-${index}`} aria-label="세부 단계">
      {subSteps(run, index).map((item) => (
        <li key={item.label} className={`plan-sub-item${item.done ? " is-done" : ""}`}>
          <span className="plan-check" aria-hidden="true">{item.done ? <Icon name="check" /> : null}</span>
          <span>{item.label}</span>
          {item.done ? <span className="sr-only">완료</span> : null}
        </li>
      ))}
    </ol>
  );
}

// 계획: 이동하는 동작은 이동하기 전에 알약으로 접고, 이동하지 않는 동작은 트레이만 새로 그린다.
function selectPlanStep(runId: PlanRun["id"], index: number): void {
  if (runId !== runState()?.id) return;
  selectStep(index, { navigate: (href: string) => {
    foldTray();
    navigate(href);
  } });
  refreshTray();
}

function nextPlanStep(skip: boolean): void {
  advance({ navigate, skip });
  refreshTray();
}

function StepItem({ run, index, active }: { run: PlanRun; index: number; active: DragState | null }): ReactNode {
  const step = run.steps[index];
  const status = stepStatus(index);
  const current = index === run.cursor && run.status !== "done";
  const open = openSteps.has(step.activityId);
  const confirm = current && run.status === "running" && (step.mode === "confirm" || manualStep(step));
  // 서버 단계는 끝났는지 이 브라우저가 알 수 없으므로, 학생이 직접 "다음으로"(완료)나 "건너뛰기"를 누른다.
  const finishedStep = confirm && !step.server && finished(step.activityId);
  const selectable = ["running", "paused"].includes(run.status) && !["done", "skipped"].includes(status);
  const title = stepInfo(step).title;
  const shift = active?.shifts.get(index);
  return (
    <li
      className={`plan-step is-${status}${current ? " is-current" : ""}${active?.from === index ? " is-dragging" : ""}`}
      data-index={String(index)}
      data-status={status}
      style={shift === undefined ? undefined : { transform: `translateY(${shift}px)` }}
    >
      <div className="plan-line">
        <ReorderHandle run={run} index={index} />
        <button
          type="button"
          className="plan-row"
          data-key={`select-${step.activityId}`}
          disabled={!selectable}
          aria-current={current ? "step" : undefined}
          onClick={() => selectPlanStep(run.id, index)}
        >
          <span className="sr-only">{`${index + 1}단계`}</span>
          <span className="plan-title">{title}</span>
          <span className="plan-state">{stepText(run, index, status)}</span>
        </button>
        <button
          type="button"
          className="plan-detail"
          data-key={`expand-${step.activityId}`}
          aria-label={`${title} 세부 단계`}
          aria-expanded={open ? "true" : "false"}
          aria-controls={`plan-sub-${index}`}
          onClick={() => togglePlanStep(index)}
        >
          <span aria-hidden="true"><Icon name="caret" /></span>
        </button>
      </div>
      {confirm ? (
        <div className="plan-confirm">
          {step.server
            ? <>
              <button type="button" className="btn btn-secondary" data-key="next" onClick={() => nextPlanStep(false)}><span>다음으로</span></button>
              <button type="button" className="btn btn-ghost" data-key="skip" onClick={() => nextPlanStep(true)}><span>건너뛰기</span></button>
            </>
            : finishedStep
              ? <button type="button" className="btn btn-secondary" data-key="next" onClick={() => nextPlanStep(false)}><span>다음으로</span></button>
              : <button type="button" className="btn btn-ghost" data-key="skip" onClick={() => nextPlanStep(true)}><span>건너뛰기</span></button>}
        </div>
      ) : null}
      {open ? <SubList run={run} index={index} /> : null}
    </li>
  );
}

function PlanBar({ run }: { run: PlanRun }): ReactNode {
  return (
    <div className="plan-bar">
      {run.status === "running" ? (
        <button type="button" className="btn btn-secondary" data-key="assistant-run-pause" onClick={() => {
          pauseRun();
          refreshTray();
        }}><span>일시정지</span></button>
      ) : null}
      {run.status === "paused" ? (
        <button type="button" className="btn btn-primary" data-key="assistant-run-resume" onClick={() => {
          foldTray();
          resumeRun({ navigate });
          refreshTray();
        }}><span>재개</span></button>
      ) : null}
      <button type="button" className="btn btn-ghost" data-key="assistant-run-stop" onClick={() => {
        stopRun();
        refreshTray();
      }}><span>중단</span></button>
    </div>
  );
}

function PlanSection({ run }: { run: PlanRun }): ReactNode {
  const active = drag?.moved && drag.runId === run.id ? drag : null;
  return (
    <section className="tray-plan" aria-label="오늘 계획" data-status={run.status}>
      <PlanBar run={run} />
      <p className="plan-order-hint">{run.customOrder
        ? "정한 순서대로 진행해요. 다른 항목을 누르면 먼저 할 수 있어요."
        : "영상부터 진행해요. 과제를 누르면 먼저 할 수 있어요."}</p>
      <p className="sr-only" id="plan-order-help">같은 섹션의 배지를 위아래로 드래그하거나, 이동할 배지와 목적지 배지를 차례로 클릭하세요. 배지에서 위아래 방향키로 이동하고 Escape로 취소할 수 있습니다.</p>
      <p className="sr-only plan-order-status" role="status" aria-live="polite" aria-atomic="true">{orderStatus}</p>
      {planGroups(run).map((group) => {
        const handled = group.steps.filter(({ step }) => ["done", "skipped"].includes(step.status)).length;
        const reordering = Boolean(active && group.steps.some(({ index }) => index === active.from));
        return (
          <section key={group.id} className="plan-group" data-group={group.id} aria-labelledby={`plan-group-${group.id}`}>
            <div className="plan-group-head">
              <h3 id={`plan-group-${group.id}`}>{group.label}</h3>
              <span>{`${handled}/${group.steps.length}`}</span>
            </div>
            {group.steps.length ? (
              <ol className={`plan-steps${reordering ? " is-reordering" : ""}`}>
                {group.steps.map(({ step, index }) => <StepItem key={step.activityId} run={run} index={index} active={active} />)}
              </ol>
            ) : <p className="plan-group-empty">{`계획에 담긴 ${group.label === "영상" ? "영상이" : "과제가"} 없어요.`}</p>}
          </section>
        );
      })}
    </section>
  );
}

function typing(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest("input, textarea, select, [contenteditable]"));
}

function useTrayListeners(): void {
  useEffect(() => {
    const onClick = (event: MouseEvent): void => {
      const target = event.target;
      if (expanded && !(target instanceof Node && trayRef.current?.contains(target))) foldTray();
    };
    const onFocusOut = (event: FocusEvent): void => {
      const tray = trayRef.current;
      if (!expanded || !tray || !(event.target instanceof Node) || !tray.contains(event.target)) return;
      if (event.relatedTarget) {
        if (!(event.relatedTarget instanceof Node && tray.contains(event.relatedTarget))) foldTray();
      } else {
        // 다시 그리며 포커스를 되돌린 뒤 실제 이탈인지 판별한다.
        queueMicrotask(() => {
          if (expanded && !trayRef.current?.contains(document.activeElement)) foldTray();
        });
      }
    };
    const onWindowBlur = (): void => {
      if (expanded) foldTray();
    };
    // Esc는 접기, 숫자키 1~9는 아직 고르지 않은 가장 최근 choose 요청의 n번째 항목.
    const onKeyDown = (event: KeyboardEvent): void => {
      if (!expanded || event.defaultPrevented) return;
      if (event.key === "Escape") {
        if (document.querySelector("[role=dialog]")) return;
        pendingFocus = "tray-toggle";
        foldTray();
        return;
      }
      if (!/^[1-9]$/.test(event.key) || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
      if (typing(event.target) || document.querySelector("[role=dialog]")) return;
      const request = pendingChoice();
      const id = request?.targets?.[Number(event.key) - 1];
      if (!request || !id || !trayRef.current) return;
      event.preventDefault();
      pickTarget(request.id, id);
    };
    document.addEventListener("click", onClick, true);
    document.addEventListener("focusout", onFocusOut);
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("blur", onWindowBlur);
    return () => {
      document.removeEventListener("click", onClick, true);
      document.removeEventListener("focusout", onFocusOut);
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("blur", onWindowBlur);
      cancelDrag();
    };
  }, []);
}

/** 그린 뒤: 들어오는 애니메이션 표시를 소비하고, 맡겨 둔 포커스를 옮긴다. */
function useTrayFocus(): void {
  useLayoutEffect(() => {
    const tray = trayRef.current;
    const key = pendingFocus;
    const choice = focusChoice;
    pendingFocus = "";
    focusChoice = false;
    if (!tray) return;
    enterPending = false;
    if (choice) {
      const first = tray.querySelector<HTMLElement>(".tray-req.is-active .tray-target");
      if (first && document.activeElement !== first) first.focus();
    }
    if (!key) return;
    const target = tray.querySelector<HTMLElement>(`[data-key="${CSS.escape(key)}"]`) || tray.querySelector<HTMLElement>(".tray-pill");
    if (target && document.activeElement !== target) target.focus({ preventScroll: true });
  });
}

/** 챗봇 입력창이 닫히는 애니메이션 길이(ms). shell.css `.chat-dock-slot[data-state="leaving"]`의 transition-duration보다 조금 길게 둔다. */
const DOCK_EXIT_MS = 160;

/**
 * `show`가 꺼져도 `exitMs` 동안은 `mounted`를 유지해 퇴장 애니메이션이 끝나기 전에 요소가 사라지지 않게 한다.
 * 퇴장 중에 다시 켜지면 타이머를 취소한다(요소는 그대로 있어 transition이 현재 값에서 되돌아간다).
 */
function usePresence(show: boolean, exitMs: number): { mounted: boolean; leaving: boolean } {
  const [held, setHeld] = useState(show);
  useEffect(() => {
    if (show) {
      setHeld(true);
      return undefined;
    }
    const timer = window.setTimeout(() => setHeld(false), exitMs);
    return () => window.clearTimeout(timer);
  }, [show, exitMs]);
  return { mounted: show || held, leaving: !show };
}

/** 셸이 화면마다 그린다. 계획·요청함이 없고 챗봇 모드도 아니면 아무것도 그리지 않는다. `dock`은 챗봇 입력창. */
export function AssistantTray({ screen, dock }: { screen: Screen; dock?: ReactNode }): ReactNode {
  useSyncExternalStore(subscribeTray, trayVersionNow);
  useTrayListeners();
  useTrayFocus();
  lastScreen = screen;
  const currentRun = runState();
  const run = currentRun && ["running", "paused"].includes(currentRun.status) && currentRun.steps.length ? currentRun : null;
  const list = requests();
  const hasPanel = Boolean(run) || list.length > 0;
  // 홈은 히어로 입력창이 화면에 보이는 동안은 숨기고, 스크롤로 벗어났을 때 FAB·입력창을 띄운다. 그 밖의 화면은 챗봇 모드일 때.
  const chat = (screen.kind === "home" ? heroInputOut() : chatUsed()) && currentRole() === "student";
  const dockShown = chat && (hasPanel || !chatShut());
  const dockPresence = usePresence(dockShown, DOCK_EXIT_MS);
  if (!hasPanel && !chat) return null;
  const newRun = run && run.id !== openRunId;
  if (newRun) {
    openRunId = run.id;
    openSteps.clear();
    pickedStepId = "";
  }
  if (isPlayer(screen)) {
    // 플레이어 이동은 기본적으로 접되, 새 계획을 시작할 때는 아래에서 전체 목록을 연다.
    const key = screenKey();
    if (key !== playerKey) {
      playerKey = key;
      expanded = false;
    }
  } else playerKey = "";
  if (newRun) {
    expanded = true;
    enterPending = true;
  }
  const active = pendingChoice();
  const open = hasPanel && expanded;
  const enter = enterPending && open;
  const mascot = Boolean(run) || chat;
  const label = run ? "오늘 계획" : list.length ? "요청함" : "챗봇";
  const pillText = run ? `Plan ${progressCount(run)}/${run.steps.length} · ${runLabel(run)}` : list.length ? "요청함" : "챗봇";
  const pillOpen = hasPanel ? open : dockShown;
  return (
    <section
      ref={trayRef}
      className={`assistant-tray${enter ? " enter" : ""}${isPlayer(screen) ? " on-player" : ""}`}
      aria-label={label}
      data-open={open ? "true" : "false"}
      data-plan={run ? run.status : undefined}
    >
      <button
        type="button"
        className={`tray-pill${mascot ? " is-mascot" : ""}`}
        data-key="tray-toggle"
        aria-expanded={pillOpen ? "true" : "false"}
        aria-controls={hasPanel ? "assistant-tray-panel" : "chat-dock"}
        aria-label={hasPanel ? `${label} ${open ? "닫기" : "열기"}, ${pillText}` : `챗봇 입력창 ${dockShown ? "닫기" : "열기"}`}
        title={pillText}
        onClick={hasPanel ? toggleTray : toggleChat}
      >
        <span className="tray-toggle-closed" hidden={open && !mascot}>
          {mascot
            ? (
              <picture>
                <source media="(prefers-reduced-motion: reduce)" srcSet="/app/assets/mascot/orange-mascot-study-blink-still.png" />
                <img className="tray-mascot" src="/app/assets/mascot/orange-mascot-study-blink.png" alt="" width="64" height="64" />
              </picture>
            )
            : <span>{pillText}</span>}
          {run || !list.length ? null : <b className="tray-count">{String(list.length)}</b>}
        </span>
        {mascot ? null : <span className="tray-toggle-open" hidden={!open} aria-hidden="true"><Icon name="close" /></span>}
      </button>
      {dockPresence.mounted
        ? (
          <div
            className="chat-dock-slot" id="chat-dock"
            data-state={dockPresence.leaving ? "leaving" : "open"}
            inert={dockPresence.leaving}
            aria-hidden={dockPresence.leaving ? "true" : undefined}
          >
            {dock}
          </div>
        )
        : null}
      <div className="tray-panel" id="assistant-tray-panel" data-key="tray-panel" tabIndex={-1} hidden={!open}>
        <div className="tray-head">
          {run ? <strong>{`Plan ${progressCount(run)}/${run.steps.length}`}</strong> : <strong>요청함</strong>}
          {run ? <span className="plan-run-state">{runLabel(run)}</span> : null}
          {active ? <span className="tray-hint">번호를 누르거나 숫자키로 선택</span> : null}
        </div>
        {run ? <PlanSection run={run} /> : null}
        {list.length ? (
          <div className="tray-requests">
            {run ? <h2 className="tray-label-row">요청함</h2> : null}
            <ul className="tray-list">
              {list.map((request) => <RequestBlock key={request.id} request={request} active={Boolean(active && active.id === request.id)} />)}
            </ul>
          </div>
        ) : null}
      </div>
    </section>
  );
}

/** 계획·요청함 패널이 없을 때 FAB은 챗봇 입력창을 접고 편다. */
function toggleChat(): void {
  toggleChatShut();
  // 펼칠 때는 입력창으로 바로 포커스(오렌지 테두리), 접을 때는 FAB에 둔다.
  pendingFocus = chatShut() ? "tray-toggle" : "chat-input";
  render();
}

/** 트레이를 다시 그린다. 트레이 안에 포커스가 있으면 같은 버튼(없으면 알약)으로 이어 둔다. */
export function refreshTray(): void {
  cancelDrag();
  orderStatus = "";
  const focused = document.activeElement;
  const keep = focused instanceof HTMLElement && trayRef.current?.contains(focused) ? focused.dataset.key || "" : "";
  if (keep) pendingFocus = keep;
  render();
}

/** 고를 choose 요청이 생긴 직후: 트레이를 펼친다. 플레이어에서는 접힌 버튼으로 둔다. */
export function openTray(): void {
  if (!isPlayer(lastScreen)) {
    expanded = true;
    enterPending = true;
  }
  refreshTray();
}

export function toggleTray(): void {
  expanded = !expanded;
  enterPending = false;
  pendingFocus = "tray-toggle";
  render();
}

/** 고를 항목이 생겼을 때 포커스를 입력창에서 첫 항목으로 옮긴다(숫자키가 입력창에 들어가지 않게). */
export function focusTray(): void {
  focusChoice = true;
  render();
}

/** 단계 줄을 눌렀을 때: 세부 단계를 펼치거나 접는다. */
export function togglePlanStep(index: number): void {
  const activityId = runState()?.steps[index]?.activityId;
  if (!activityId) return;
  if (openSteps.has(activityId)) openSteps.delete(activityId);
  else openSteps.add(activityId);
  refreshTray();
}

/**
 * 플레이어가 재생 위치를 갱신할 때마다 부른다. 현재 단계가 이 활동이면 트레이만 다시 그려
 * "재생 중 mm:ss/mm:ss"와 세부 단계를 맞춘다.
 */
export function syncPlanProgress(activityId: string): void {
  const run = runState();
  if (!run || !trayRef.current || run.status !== "running" || run.steps[run.cursor]?.activityId !== activityId) return;
  paintTray();
}

/** 요청을 고른 직후나 패널을 떠날 때: 버튼만 남기고 접는다. */
export function foldTray(): void {
  cancelDrag();
  pickedStepId = "";
  expanded = false;
  enterPending = false;
  render();
}
