import { el, icon } from "../dom.js";
import { getState } from "../store.js";
import { activityById } from "./format.js";
import { describe } from "./assistant/resolve.js";
import { requests, isDone } from "./assistant/run.js";
import { planGroups, progressCount, runState, stepStatus, reorderStep } from "./assistant/plan.js";

// 하단 트레이: 오늘 계획과, 고를 게 남은 choose 요청만 보여 준다(이동·없음·계획 결과는 쌓지 않는다). draw()가 #app 전체를
// 다시 만들기 때문에 펼침/접힘은 DOM이 아니라 이 모듈의 상태로 두고, 화면을 옮겨도 그 상태로 다시 그린다.
let expanded = false;
let enterPending = false;
let lastScreen = null;
let playerKey = "";
// 계획 단계 펼침(드릴다운)은 draw()가 DOM을 새로 만들어도 남도록 모듈 상태로 둔다. 계획이 바뀌면 비운다.
const openSteps = new Set();
let openRunId = "";
let pickedStepId = "";
let drag = null;

function movableSteps(run, index) {
  if (!["ready", "running", "paused"].includes(run.status)) return [];
  const group = planGroups(run).find((item) => item.steps.some((entry) => entry.index === index));
  return (group?.steps || []).filter(({ step, index: at }) => step.status === "pending" && (run.status === "ready" || at !== run.cursor))
    .map((entry) => entry.index);
}

function announceOrder(text) {
  const live = root()?.querySelector(".plan-order-status");
  if (live) live.textContent = text;
}

function moveStep(runId, from, to) {
  const activityId = runState()?.steps[from]?.activityId;
  if (!reorderStep(runId, from, to)) return false;
  pickedStepId = "";
  refreshTray();
  root()?.querySelector(`[data-key="${CSS.escape(`reorder-${activityId}`)}"]`)?.focus({ preventScroll: true });
  announceOrder(`${describe(activityId).title}, ${to + 1}번째로 이동`);
  return true;
}

function cancelDrag() {
  const active = drag;
  if (!active) return;
  drag = null;
  cancelAnimationFrame(active.frame);
  active.list.classList.remove("is-reordering");
  active.rows.forEach(({ node }) => {
    node.style.transform = "";
    node.classList.remove("is-dragging");
  });
  if (active.handle.hasPointerCapture(active.pointerId)) active.handle.releasePointerCapture(active.pointerId);
}

function paintDrag() {
  const active = drag;
  if (!active) return;
  const run = runState();
  if (!active.handle.isConnected || run?.id !== active.runId || run.cursor !== active.cursor
    || run.status !== active.status || run.selectionVersion !== active.selectionVersion) {
    cancelDrag();
    return;
  }
  const source = active.rows.find((row) => row.index === active.from);
  const offset = active.y - active.startY + active.panel.scrollTop - active.scrollTop;
  if (!active.moved && Math.abs(offset) < 4) return;
  active.moved = true;
  active.list.classList.add("is-reordering");
  source.node.classList.add("is-dragging");
  const dy = Math.max(active.rows[0].top - source.top, Math.min(active.rows.at(-1).top - source.top, offset));
  const center = source.center + dy;
  active.to = active.rows.reduce((nearest, row) =>
    Math.abs(row.center - center) < Math.abs(nearest.center - center) ? row : nearest, source).index;
  const from = active.rows.findIndex((row) => row.index === active.from);
  const to = active.rows.findIndex((row) => row.index === active.to);
  for (const [at, row] of active.rows.entries()) {
    const shift = at === from ? dy
      : from < at && at <= to ? active.rows[at - 1].top - row.top
      : to <= at && at < from ? active.rows[at + 1].top - row.top : 0;
    row.node.style.transform = `translateY(${shift}px)`;
  }
}

function scrollDrag() {
  if (!drag) return;
  const active = drag;
  if (!active.handle.isConnected) { cancelDrag(); return; }
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

function reorderHandle(run, index) {
  const activityId = run.steps[index].activityId;
  const slots = movableSteps(run, index);
  const movable = slots.includes(index) && slots.length > 1;
  const handle = el("button", {
    type: "button", class: `plan-handle${pickedStepId === activityId ? " is-picked" : ""}`,
    disabled: !movable, "data-key": `reorder-${activityId}`, "data-activity": activityId,
    "aria-label": `${describe(activityId).title} 순서 변경`, "aria-pressed": pickedStepId === activityId ? "true" : "false",
    "aria-describedby": movable ? "plan-order-help" : null,
    title: movable ? "같은 섹션 안에서 드래그 · 배지 두 개를 차례로 클릭 · 방향키로 이동" : "현재·완료 단계는 순서 고정",
  }, el("span", { class: "plan-node", "aria-hidden": "true" }));
  if (!movable) return handle;
  let suppressClick = false;
  handle.addEventListener("click", (event) => {
    event.stopPropagation();
    if (suppressClick) { suppressClick = false; return; }
    const from = run.steps.findIndex((step) => step.activityId === pickedStepId);
    if (slots.includes(from) && from !== index) { moveStep(run.id, from, index); return; }
    pickedStepId = pickedStepId === activityId ? "" : activityId;
    refreshTray();
    if (pickedStepId) announceOrder("이동할 위치의 배지를 클릭하세요, Escape로 취소");
  });
  handle.addEventListener("keydown", (event) => {
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
  });
  handle.addEventListener("pointerdown", (event) => {
    if (!event.isPrimary || event.button !== 0 || drag) return;
    const list = handle.closest(".plan-steps");
    const panel = handle.closest(".tray-panel");
    const bounds = panel.getBoundingClientRect();
    const rows = [...list.children].filter((node) => slots.includes(Number(node.dataset.index))).map((node) => {
      const box = node.getBoundingClientRect();
      const line = node.querySelector(".plan-line").getBoundingClientRect();
      return { node, index: Number(node.dataset.index), top: box.top, center: line.top + line.height / 2 };
    });
    suppressClick = false;
    handle.focus({ preventScroll: true });
    handle.setPointerCapture(event.pointerId);
    drag = { handle, list, panel, rows, runId: run.id, cursor: run.cursor, status: run.status,
      from: index, to: index, pointerId: event.pointerId, selectionVersion: run.selectionVersion, startY: event.clientY, y: event.clientY,
      scrollTop: panel.scrollTop, panelTop: bounds.top, panelBottom: bounds.bottom, moved: false, frame: 0 };
    drag.frame = requestAnimationFrame(scrollDrag);
  });
  handle.addEventListener("pointermove", (event) => {
    if (drag?.handle !== handle || event.pointerId !== drag.pointerId) return;
    drag.y = event.clientY;
    paintDrag();
  });
  handle.addEventListener("pointerup", (event) => {
    if (drag?.handle !== handle || event.pointerId !== drag.pointerId) return;
    paintDrag();
    if (!drag) return;
    const active = drag;
    suppressClick = active.moved;
    cancelDrag();
    if (active.moved) moveStep(active.runId, active.from, active.to);
  });
  handle.addEventListener("pointercancel", (event) => {
    if (drag?.handle === handle && event.pointerId === drag.pointerId) { suppressClick = true; cancelDrag(); }
  });
  handle.addEventListener("lostpointercapture", (event) => {
    if (drag?.handle === handle && event.pointerId === drag.pointerId) cancelDrag();
  });
  return handle;
}

function isPlayer(screen) {
  return Boolean(screen && screen.kind === "player");
}

function screenKey() {
  const params = new URLSearchParams(location.search);
  params.delete("autoplay");
  return `${location.pathname}?${params}`;
}

function pendingChoice() {
  return requests().find((request) => request.kind === "choose" && !request.chosen);
}

function root() {
  return document.querySelector(".assistant-tray");
}

/** 열림/닫힘만 바꾼다. 내용은 건드리지 않아 포커스와 스크롤이 유지된다. */
function sync() {
  const tray = root();
  if (!tray) return;
  tray.dataset.open = expanded ? "true" : "false";
  tray.querySelector(".tray-panel").hidden = !expanded;
  const pill = tray.querySelector(".tray-pill");
  pill.querySelector(".tray-toggle-closed").hidden = expanded;
  pill.querySelector(".tray-toggle-open").hidden = !expanded;
  pill.setAttribute("aria-expanded", expanded ? "true" : "false");
  pill.setAttribute("aria-label", `${tray.getAttribute("aria-label")} ${expanded ? "닫기" : "열기"}, ${pill.dataset.summary}`);
}

function targetButton(request, id, index) {
  const d = describe(id);
  const done = isDone(id);
  const picked = request.chosen === id;
  return el("li", { class: "tray-item" },
    el("button", {
      type: "button",
      class: `tray-target${done ? " is-done" : ""}${picked ? " is-picked" : ""}`,
      "data-act": "assistant-choose",
      "data-request": request.id,
      "data-id": id,
      "data-key": `choice-${request.id}-${id}`,
      "data-n": index < 9 ? String(index + 1) : null,
    },
      el("span", { class: "tray-n", "aria-hidden": "true", text: index < 9 ? String(index + 1) : "·" }),
      el("span", { class: "tray-label" },
        el("span", { class: "tray-title", text: d.title }),
        d.meta ? el("span", { class: "tray-meta", text: ` · ${d.meta}` }) : null,
      ),
      done ? el("span", { class: "tray-check" }, icon("check"), el("span", { class: "sr-only", text: "완료" })) : null,
    ),
  );
}

function requestBlock(request, active) {
  const targets = request.targets || [];
  return el("li", { class: `tray-req${active ? " is-active" : ""}`, "data-request": request.id },
    el("p", { class: "tray-say", text: request.say || "" }),
    targets.length ? el("ol", { class: "tray-targets" }, ...targets.map((id, i) => targetButton(request, id, i))) : null,
  );
}

const pad2 = (n) => String(Math.floor(n)).padStart(2, "0");
const clock = (total) => {
  const seconds = Math.max(0, Math.round(total));
  return `${pad2(seconds / 60)}:${pad2(seconds % 60)}`;
};

/**
 * 단계를 끝까지 봤거나 제출했는가. isDone(활동 상태·positions/minutes 기준)에 더해, 샘플 영상은 실제 길이가
 * 차시 시간보다 짧으므로 플레이어가 영상 길이 90%에 닿았을 때 남기는 player.watched도 본다.
 */
export function finished(activityId) {
  return isDone(activityId) || Boolean(getState().player?.watched?.[activityId]);
}

function positionOf(activityId) {
  const player = getState().player || {};
  const seconds = Number(player.positions && player.positions[activityId]);
  return Number.isFinite(seconds) ? seconds : 0;
}

function totalOf(activity) {
  const player = getState().player || {};
  if (player.id === activity.id && player.duration > 0) return player.duration;
  return activity.minutes > 0 ? activity.minutes * 60 : 0;
}

function runLabel(run) {
  if (run.status === "paused") return "일시정지";
  return run.steps[run.cursor]?.mode === "auto" ? "재생 중" : "확인 필요";
}

function stepText(run, index, status) {
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
  if (!activity) return "";
  if (activity.minutes > 0) return `${activity.minutes}분`;
  return activity.due ? `${activity.due.slice(5, 10).replace("-", "/")}까지` : "";
}

/** 세부 단계: 영상은 이동·재생·끝까지 시청·다음, 과제는 이동·작성/제출·확인, 그 밖은 이동·확인. */
function subSteps(run, index) {
  const step = run.steps[index];
  const activity = activityById(step.activityId);
  const labels = activity && activity.type === "video"
    ? ["이동", "재생", "끝까지 시청(90%)", "다음"]
    : activity && activity.type === "report" ? ["이동", "작성·제출", "확인"] : ["이동", "확인"];
  const status = stepStatus(index);
  if (status === "done" || status === "skipped") {
    return labels.map((label) => ({ label, done: status === "done" }));
  }
  if (index !== run.cursor) return labels.map((label) => ({ label, done: false }));
  const arrived = run.status === "running";
  const done = finished(step.activityId);
  const flags = labels.length === 4
    ? [arrived, arrived && (positionOf(step.activityId) > 0 || Boolean(getState().player?.playing && getState().player.id === step.activityId)), done, false]
    : labels.length === 3 ? [arrived, done, false] : [arrived, false];
  return labels.map((label, i) => ({ label, done: flags[i] }));
}

function subList(run, index) {
  return el("ol", { class: "plan-sub", id: `plan-sub-${index}`, "aria-label": "세부 단계" },
    ...subSteps(run, index).map((item) => el("li", { class: `plan-sub-item${item.done ? " is-done" : ""}` },
      el("span", { class: "plan-check", "aria-hidden": "true" }, item.done ? icon("check") : null),
      el("span", { text: item.label }),
      item.done ? el("span", { class: "sr-only", text: "완료" }) : null,
    )),
  );
}

function stepItem(run, index) {
  const step = run.steps[index];
  const status = stepStatus(index);
  const current = index === run.cursor && run.status !== "done";
  const open = openSteps.has(step.activityId);
  const confirm = current && run.status === "running" && step.mode === "confirm";
  const finishedStep = confirm && finished(step.activityId);
  const selectable = ["running", "paused"].includes(run.status) && !["done", "skipped"].includes(status);
  const title = describe(step.activityId).title;
  return el("li", { class: `plan-step is-${status}${current ? " is-current" : ""}`, "data-index": String(index), "data-status": status },
    el("div", { class: "plan-line" },
      reorderHandle(run, index),
      el("button", {
        type: "button", class: "plan-row", "data-act": "assistant-run-select", "data-index": String(index), "data-run": run.id, "data-key": `select-${step.activityId}`,
        disabled: selectable ? null : true, "aria-current": current ? "step" : null,
      },
        el("span", { class: "sr-only", text: `${index + 1}단계` }),
        el("span", { class: "plan-title", text: title }),
        el("span", { class: "plan-state", text: stepText(run, index, status) }),
      ),
      el("button", {
        type: "button", class: "plan-detail", "data-act": "assistant-run-expand", "data-index": String(index), "data-key": `expand-${step.activityId}`,
        "aria-label": `${title} 세부 단계`, "aria-expanded": open ? "true" : "false", "aria-controls": `plan-sub-${index}`,
      }, el("span", { "aria-hidden": "true" }, icon("caret"))),
    ),
    confirm ? el("div", { class: "plan-confirm" },
      finishedStep
        ? el("button", { type: "button", class: "btn btn-secondary", "data-act": "assistant-run-next", "data-key": "next" }, el("span", { text: "다음으로" }))
        : el("button", { type: "button", class: "btn btn-ghost", "data-act": "assistant-run-skip", "data-key": "skip" }, el("span", { text: "건너뛰기" })),
    ) : null,
    open ? subList(run, index) : null,
  );
}

function planBar(run) {
  const button = (act, label, kind) => el("button", { type: "button", class: `btn btn-${kind}`, "data-act": act, "data-key": act }, el("span", { text: label }));
  return el("div", { class: "plan-bar" },
    run.status === "running" ? button("assistant-run-pause", "일시정지", "secondary") : null,
    run.status === "paused" ? button("assistant-run-resume", "재개", "primary") : null,
    button("assistant-run-stop", "중단", "ghost"),
  );
}

function planSection(run) {
  return el("section", { class: "tray-plan", "aria-label": "오늘 계획", "data-status": run.status },
    planBar(run),
    el("p", { class: "plan-order-hint", text: run.customOrder
      ? "정한 순서대로 진행해요. 다른 항목을 누르면 먼저 할 수 있어요."
      : "영상부터 진행해요. 과제를 누르면 먼저 할 수 있어요." }),
    el("p", { class: "sr-only", id: "plan-order-help", text: "같은 섹션의 배지를 위아래로 드래그하거나, 이동할 배지와 목적지 배지를 차례로 클릭하세요. 배지에서 위아래 방향키로 이동하고 Escape로 취소할 수 있습니다." }),
    el("p", { class: "sr-only plan-order-status", role: "status", "aria-live": "polite", "aria-atomic": "true" }),
    ...planGroups(run).map((group) => {
      const handled = group.steps.filter(({ step }) => ["done", "skipped"].includes(step.status)).length;
      return el("section", { class: "plan-group", "data-group": group.id, "aria-labelledby": `plan-group-${group.id}` },
        el("div", { class: "plan-group-head" },
          el("h3", { id: `plan-group-${group.id}`, text: group.label }),
          el("span", { text: `${handled}/${group.steps.length}` }),
        ),
        group.steps.length
          ? el("ol", { class: "plan-steps" }, ...group.steps.map(({ index }) => stepItem(run, index)))
          : el("p", { class: "plan-group-empty", text: `계획에 담긴 ${group.label === "영상" ? "영상이" : "과제가"} 없어요.` }),
      );
    }),
  );
}

/** 현재 단계의 진행 표시를 바꿀 때 다시 그릴지 가르는 값. 이 값이 달라지면 트레이를 새로 만든다. */
function progressSig(run) {
  const step = run && run.steps[run.cursor];
  return step ? `${run.status}|${finished(step.activityId)}|${positionOf(step.activityId) > 0}` : "";
}

/** 셸이 화면마다 호출한다. 요청도 계획도 없으면 아무것도 그리지 않는다. */
export function buildTray(screen) {
  lastScreen = screen;
  const currentRun = runState();
  const run = ["running", "paused"].includes(currentRun?.status) && currentRun.steps.length ? currentRun : null;
  const list = requests();
  if (!list.length && !run) return null;
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
  const enter = enterPending && expanded;
  enterPending = false;
  const pillText = run ? `Plan ${progressCount(run)}/${run.steps.length} · ${runLabel(run)}` : "요청함";
  return el("section", {
    class: `assistant-tray${enter ? " enter" : ""}${isPlayer(screen) ? " on-player" : ""}`,
    "aria-label": run ? "오늘 계획" : "요청함",
    "data-open": expanded ? "true" : "false",
    "data-plan": run ? run.status : null,
    "data-sig": run ? progressSig(run) : null,
  },
    el("button", {
      type: "button", class: `tray-pill${run ? " is-plan" : ""}`, "data-act": "assistant-tray-toggle",
      "data-key": "tray-toggle", "data-summary": pillText,
      "aria-expanded": expanded ? "true" : "false", "aria-controls": "assistant-tray-panel",
      "aria-label": `${run ? "오늘 계획" : "요청함"} ${expanded ? "닫기" : "열기"}, ${pillText}`,
      title: pillText,
    },
      el("span", { class: "tray-toggle-closed", hidden: expanded ? true : null },
        run ? icon("list-check") : el("span", { text: pillText }),
        run ? null : el("b", { class: "tray-count", text: String(list.length) })),
      el("span", { class: "tray-toggle-open", hidden: expanded ? null : true, "aria-hidden": "true" }, icon("close")),
    ),
    el("div", { class: "tray-panel", id: "assistant-tray-panel", "data-key": "tray-panel", tabindex: "-1", hidden: expanded ? null : true },
      el("div", { class: "tray-head" },
        run
          ? el("strong", { text: `Plan ${progressCount(run)}/${run.steps.length}` })
          : el("strong", { text: "요청함" }),
        run ? el("span", { class: "plan-run-state", text: runLabel(run) }) : null,
        active ? el("span", { class: "tray-hint", text: "번호를 누르거나 숫자키로 선택" }) : null,
      ),
      run ? planSection(run) : null,
      list.length ? el("div", { class: "tray-requests" },
        run ? el("h2", { class: "tray-label-row", text: "요청함" }) : null,
        el("ul", { class: "tray-list" }, ...list.map((request) => requestBlock(request, Boolean(active && active.id === request.id)))),
      ) : null,
    ),
  );
}

/** 화면 전체를 다시 그리지 않고 트레이만 새로 만든다(히어로 입력과 안내 문장을 지키기 위해). 포커스한 버튼과 스크롤은 이어 둔다. */
export function refreshTray() {
  cancelDrag();
  const prev = root();
  const keep = prev && prev.contains(document.activeElement) ? document.activeElement.dataset.key || "" : "";
  const scroll = prev && prev.querySelector(".tray-panel") ? prev.querySelector(".tray-panel").scrollTop : 0;
  const next = buildTray(lastScreen);
  if (!next) {
    if (prev) prev.remove();
    return;
  }
  if (prev) prev.replaceWith(next);
  else (document.querySelector(".chrome") || document.body).append(next);
  const panel = next.querySelector(".tray-panel");
  if (panel && scroll) panel.scrollTop = scroll;
  if (keep) {
    const target = next.querySelector(`[data-key="${CSS.escape(keep)}"]`) || next.querySelector(".tray-pill");
    target?.focus({ preventScroll: true });
  }
}

/** 고를 choose 요청이 생긴 직후: 트레이를 펼친다. 플레이어에서는 접힌 버튼으로 둔다. */
export function openTray() {
  if (!isPlayer(lastScreen)) {
    expanded = true;
    enterPending = true;
  }
  refreshTray();
}

export function toggleTray() {
  expanded = !expanded;
  enterPending = false;
  sync();
  const tray = root();
  if (!tray) return;
  tray.querySelector(".tray-pill")?.focus({ preventScroll: true });
}

/** 고를 항목이 생겼을 때 포커스를 입력창에서 첫 항목으로 옮긴다(숫자키가 입력창에 들어가지 않게). */
export function focusTray() {
  const tray = root();
  const first = tray && tray.querySelector(".tray-req.is-active .tray-target");
  if (first) first.focus();
}


/** 단계 줄을 눌렀을 때: 세부 단계를 펼치거나 접는다. */
export function togglePlanStep(index) {
  const activityId = runState()?.steps[index]?.activityId;
  if (!activityId) return;
  if (openSteps.has(activityId)) openSteps.delete(activityId);
  else openSteps.add(activityId);
  refreshTray();
}

/**
 * 플레이어가 재생 위치를 갱신할 때마다 부른다. 현재 단계의 "재생 중 mm:ss/mm:ss"만 제자리에서 바꾸고,
 * 끝까지 시청 여부나 재생 시작 같은 세부 단계 변화가 생기면 그때만 트레이를 새로 그린다.
 */
export function syncPlanProgress(activityId) {
  const run = runState();
  const tray = root();
  if (!run || !tray || run.status !== "running" || run.steps[run.cursor]?.activityId !== activityId) return;
  if (tray.dataset.sig !== progressSig(run)) {
    refreshTray();
    return;
  }
  const label = tray.querySelector(`.plan-step[data-index="${run.cursor}"] .plan-state`);
  const text = stepText(run, run.cursor, stepStatus(run.cursor));
  if (label && label.textContent !== text) label.textContent = text;
}

/** 요청을 고른 직후나 패널을 떠날 때: 버튼만 남기고 접는다. */
export function foldTray() {
  cancelDrag();
  pickedStepId = "";
  expanded = false;
  enterPending = false;
  sync();
}

function typing(target) {
  return Boolean(target && target.closest && target.closest("input, textarea, select, [contenteditable]"));
}

if (typeof document !== "undefined") {
  document.addEventListener("click", (event) => {
    if (expanded && !root()?.contains(event.target)) foldTray();
  }, true);
  document.addEventListener("focusout", (event) => {
    const tray = root();
    if (!expanded || !tray?.contains(event.target)) return;
    if (event.relatedTarget) {
      if (!tray.contains(event.relatedTarget)) foldTray();
    } else {
      // DOM 교체 시 refreshTray가 포커스를 복원한 뒤 실제 이탈인지 판별한다.
      queueMicrotask(() => {
        if (expanded && !root()?.contains(document.activeElement)) foldTray();
      });
    }
  });
  window.addEventListener("blur", () => {
    if (expanded) foldTray();
  });
}

// Esc는 접기, 숫자키 1~9는 아직 고르지 않은 가장 최근 choose 요청의 n번째 항목.
if (typeof document !== "undefined") document.addEventListener("keydown", (event) => {
  if (!expanded || event.defaultPrevented) return;
  if (event.key === "Escape") {
    if (document.querySelector("[role=dialog]")) return;
    foldTray();
    const pill = root() && root().querySelector(".tray-pill");
    if (pill) pill.focus();
    return;
  }
  if (!/^[1-9]$/.test(event.key) || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
  if (typing(event.target) || document.querySelector("[role=dialog]")) return;
  const request = pendingChoice();
  const tray = root();
  if (!request || !tray) return;
  const button = tray.querySelector(`.tray-target[data-request="${CSS.escape(request.id)}"][data-n="${event.key}"]`);
  if (!button) return;
  event.preventDefault();
  button.click();
});
