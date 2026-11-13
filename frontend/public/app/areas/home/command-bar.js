import { el, icon } from "../../dom.js";
import { activityById, typeLabel, typeIcon, TODAY } from "../../shared/format.js";
import { nextActions, describe } from "../../shared/assistant/resolve.js";
import { searchClassrooms, classroomForCommand } from "../../shared/assistant/classrooms.js";
import { ask, execute } from "../../shared/assistant/run.js";
import { foldTray, focusTray, openTray, refreshTray } from "../../shared/assistant-tray.js";
import { planGroups, runState, startRun, stopRun } from "../../shared/assistant/plan.js";

const MAX_ROWS = 5;
let busy = false;
let inputRevision = 0;
let pendingController = null;

const arrowTail = /\s*[→➜>]+\s*$/;

function planReply(text) {
  const reply = text.toLowerCase().replace(/[\s,.!?。~]+/g, "");
  if (/^(?:(?:응|네|예|그래|좋아|좋아요))?(?:ㄱㄱ|고고|진행해|진행해줘|진행해주세요|진행하자|시작해|시작하자|이대로시작|이대로진행해|그대로진행해|그렇게해|그대로해|좋아|좋아요|응|네|예|그래|오케이|ok|okay)$/.test(reply)) return "accept";
  if (/^(?:ㄴㄴ|취소|취소해|취소해줘|아니|아니요|아니야|하지마|진행하지마|멈춰|중단)$/.test(reply)) return "cancel";
  return null;
}

export function planPreview() {
  const run = runState();
  const card = el("section", { class: "hero-plan", id: "hero-plan-preview", "aria-labelledby": "hero-plan-title", hidden: true });
  if (run?.status !== "ready") return card;
  card.hidden = false;
  card.dataset.run = run.id;
  const groups = planGroups(run);
  const sequence = groups.filter((group) => group.steps.length).map((group) => group.label).join(" → ");
  const row = ({ step }) => {
    const detail = describe(step.activityId);
    return el("li", null,
      el("span", { class: "hero-plan-step", text: detail.title }),
      el("span", { class: "hero-plan-meta", text: detail.meta }),
    );
  };
  card.append(...[
    el("span", { class: "hero-plan-label", text: "시작 전 요약" }),
    el("h2", { id: "hero-plan-title", text: "이 순서로 진행할까요?" }),
    el("p", { class: "hero-plan-count", text: `총 ${run.steps.length}개 · ${sequence} 순서예요` }),
    ...groups.map((group) => el("section", { class: "hero-plan-group", "data-group": group.id, "aria-labelledby": `hero-plan-${group.id}` },
      el("h3", { id: `hero-plan-${group.id}`, text: `${group.label} ${group.steps.length}개` }),
      group.steps.length
        ? el("ol", { class: "hero-plan-steps" }, ...group.steps.map(row))
        : el("p", { class: "hero-plan-meta", text: `계획에 담긴 ${group.label === "영상" ? "영상이" : "과제가"} 없어요.` }),
    )),
    el("p", { class: "hero-plan-rule", text: run.customOrder
      ? "정한 순서대로 진행해요. Plan에서 다른 항목을 먼저 선택할 수도 있어요. 제출은 직접 해 주세요."
      : "영상을 모두 들으면 과제로 넘어가요. 과제는 Plan에서 먼저 선택할 수도 있어요. 제출은 직접 해 주세요." }),
    el("button", {
      type: "button", class: "btn btn-primary hero-plan-accept", text: "Accept",
      "data-act": "assistant-ask", "data-accept-run": run.id, "aria-label": "Accept — 이 계획으로 시작", disabled: busy,
    }),
    el("p", { class: "hero-plan-reply", id: "hero-plan-reply" },
      "버튼을 누르거나 ", el("strong", { text: "ㄱㄱ" }), " · ", el("strong", { text: "진행해" }), " 입력 후 Enter",
      el("span", { text: "순서·항목 추가·제외를 입력해 수정할 수 있어요. 취소는 Esc" }),
    ),
  ].filter(Boolean));
  return card;
}

function refreshPreview() {
  document.getElementById("hero-plan-preview")?.replaceWith(planPreview());
  const input = document.getElementById("assistant-input");
  const ready = runState()?.status === "ready";
  if (!input) return;
  input.placeholder = ready ? "계획 수정 또는 ㄱㄱ · Enter" : "과목이나 할 일 (예: 데베 강의)";
  if (ready) input.setAttribute("aria-describedby", "hero-plan-reply");
  else input.removeAttribute("aria-describedby");
}

function clearCommand(input) {
  input.value = "";
  paintRows(input.closest(".cmd-box"), input, document.getElementById("assistant-list"), "");
}

/** 마감이 오늘(샘플 기준일) 이내이면 임박. due는 "YYYY-MM-DDTHH:mm". */
function urgent(activity) {
  if (!activity || !activity.due) return false;
  return Date.parse(activity.due) - Date.parse(`${TODAY}T00:00`) <= 24 * 3600 * 1000;
}

function actionRow(action) {
  const activity = activityById(action.id);
  const lecture = action.kind === "lecture";
  const chip = lecture ? "강의" : typeLabel(activity ? activity.type : "report");
  const cta = String(action.cta || "").replace(arrowTail, "");
  const hot = !lecture && urgent(activity);
  const progress = lecture && activity ? Math.max(0, Math.min(100, Number(activity.progress) || 0)) : null;
  return el("li", null,
    el("a", {
      class: `hero-action ${lecture ? "is-lecture" : "is-deadline"}`,
      href: action.href,
      "data-go": action.href,
      "aria-label": `${chip} ${action.title}, ${action.meta}. ${cta}`,
    },
      el("span", { class: `hero-chip t-${activity ? activity.type : "report"}`, "aria-hidden": "true", title: chip }, icon(typeIcon(lecture ? "video" : activity ? activity.type : "report"))),
      el("span", { class: "hero-action-main", "aria-hidden": "true" },
        el("span", { class: "hero-action-title", text: action.title }),
        el("span", { class: `hero-action-meta${hot ? " is-urgent" : ""}`, text: action.meta }),
        progress == null ? null : el("span", { class: "hero-bar" }, el("span", { class: "hero-bar-fill", style: `width:${progress}%` })),
      ),
      el("span", { class: "hero-cta", "aria-hidden": "true" }, el("span", { text: cta }), el("span", { class: "hero-arrow", text: "→" })),
    ),
  );
}

function previewRows(text) {
  const query = text.trim();
  if (!query) return { rows: [], more: 0 };
  const matches = searchClassrooms(query);
  return { rows: matches.slice(0, MAX_ROWS), more: Math.max(0, matches.length - MAX_ROWS) };
}

function paintGhost(box, input, list) {
  const ghost = box.querySelector(".cmd-ghost");
  const candidate = !list.hidden && (list.querySelector("[aria-selected=true]") || list.querySelector("[role=option]"));
  const command = candidate?.dataset?.command;
  const visible = command && command !== input.value && input.value
    && document.activeElement === input && !input.readOnly && !input.dataset.composing
    && input.selectionStart === input.value.length && input.selectionEnd === input.value.length;
  box.dataset.completion = visible ? command : "";
  box.classList.toggle("has-ghost", Boolean(visible));
  ghost.hidden = !visible;
  ghost.replaceChildren();
  if (visible) {
    const prefix = command.toLowerCase().startsWith(input.value.toLowerCase());
    ghost.append(
      el("span", { class: "cmd-ghost-prefix", text: input.value }),
      document.createTextNode(prefix ? command.slice(input.value.length) : ` → ${command}`),
    );
    ghost.scrollLeft = input.scrollLeft;
  }
  box.querySelector(".cmd-key").textContent = visible ? "Tab" : "/";
}

function paintRows(box, input, list, text) {
  const { rows, more } = previewRows(text);
  list.replaceChildren(
    ...rows.map((row, i) => el("li", {
      class: "cmd-opt", role: "option", id: `assistant-opt-${i}`, "aria-selected": "false",
      "data-act": "assistant-ask", "data-course": row.courseId, "data-command": row.title,
    },
      el("span", { class: "cmd-opt-title", text: row.title }),
      el("span", { class: "cmd-opt-meta", text: "바로 이동 · Tab 완성 · ↑↓ 다른 추천" }),
    )),
  );
  if (more > 0) list.append(el("li", { class: "cmd-more", role: "presentation", text: `외 ${more}개 · 과목명을 더 입력해 주세요` }));
  const open = rows.length > 0;
  list.hidden = !open;
  input.setAttribute("aria-expanded", open ? "true" : "false");
  input.removeAttribute("aria-activedescendant");
  box.classList.toggle("has-list", open);
  paintGhost(box, input, list);
}

function move(input, list, step) {
  const options = [...list.querySelectorAll("[role=option]")];
  if (!options.length) return;
  const current = options.findIndex((node) => node.getAttribute("aria-selected") === "true");
  const next = current < 0 ? (step > 0 ? 0 : options.length - 1) : (current + step + options.length) % options.length;
  options.forEach((node, i) => node.setAttribute("aria-selected", i === next ? "true" : "false"));
  input.setAttribute("aria-activedescendant", options[next].id);
}

/** 히어로의 행동 줄 + 명령 입력창. */
export function commandBar() {
  const actions = nextActions().slice(0, 2);
  const input = el("input", {
    type: "text", id: "assistant-input", class: "cmd-input", role: "combobox",
    "aria-label": "과목이나 할 일로 바로 가기", "aria-autocomplete": "both", "aria-expanded": "false",
    "aria-controls": "assistant-list", "aria-haspopup": "listbox",
    "aria-description": "추천 명령은 Tab으로 완성하고 Enter로 실행합니다. 위아래 화살표로 다른 추천을 선택합니다.",
    placeholder: "과목이나 할 일 (예: 데베 강의)", autocomplete: "off", autocapitalize: "off", spellcheck: "false", enterkeyhint: "go",
  });
  const list = el("ul", { class: "cmd-list", id: "assistant-list", role: "listbox", "aria-label": "해석 미리보기", hidden: true });
  const submit = el("button", { type: "button", class: "cmd-go", "data-act": "assistant-ask", "aria-label": "실행" }, el("span", { "aria-hidden": "true", text: "→" }));
  const box = el("div", { class: "cmd-box" },
    el("span", { class: "cmd-ico", "aria-hidden": "true" }, icon("search")),
    el("div", { class: "cmd-field" },
      el("div", { class: "cmd-ghost", "aria-hidden": "true", hidden: true }),
      input,
    ),
    el("kbd", { class: "cmd-key", "aria-hidden": "true", text: "/" }),
    submit,
    el("span", { class: "cmd-spin", "aria-hidden": "true" }),
  );
  if (runState()?.status === "ready") {
    input.placeholder = "계획 수정 또는 ㄱㄱ · Enter";
    input.setAttribute("aria-describedby", "hero-plan-reply");
  }

  input.addEventListener("input", () => {
    pendingController?.abort();
    inputRevision += 1;
    say("");
    paintRows(box, input, list, input.value);
  });
  input.addEventListener("compositionstart", () => {
    input.dataset.composing = "true";
    paintGhost(box, input, list);
  });
  input.addEventListener("compositionend", () => {
    delete input.dataset.composing;
    paintRows(box, input, list, input.value);
  });
  for (const event of ["click", "select", "keyup", "scroll"]) {
    input.addEventListener(event, () => paintGhost(box, input, list));
  }
  input.addEventListener("keydown", (event) => {
    if (event.isComposing || event.keyCode === 229) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (list.hidden && input.value.trim()) paintRows(box, input, list, input.value);
      if (list.hidden) return;
      event.preventDefault();
      move(input, list, event.key === "ArrowDown" ? 1 : -1);
      paintGhost(box, input, list);
    } else if (event.key === "Tab" && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey) {
      paintGhost(box, input, list);
      const completion = box.dataset.completion;
      if (!completion) return;
      event.preventDefault();
      pendingController?.abort();
      inputRevision += 1;
      input.value = completion;
      paintRows(box, input, list, input.value);
      input.setSelectionRange(input.value.length, input.value.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const active = !list.hidden && list.querySelector("[aria-selected=true]");
      (active || submit).click();
    } else if (event.key === "Escape" && (input.value || !list.hidden || runState()?.status === "ready")) {
      event.preventDefault();
      pendingController?.abort();
      inputRevision += 1;
      say("");
      if (runState()?.status === "ready") {
        stopRun();
        refreshPreview();
        refreshTray();
        say("계획을 취소했어요. 원하는 일을 다시 입력해 주세요.");
      }
      input.value = "";
      paintRows(box, input, list, "");
    }
  });
  input.addEventListener("blur", () => {
    // 옵션을 누르는 동안(mousedown)에는 포커스를 유지하므로 여기서는 그냥 닫는다.
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
    box.classList.remove("has-list");
    paintGhost(box, input, list);
  });
  input.addEventListener("focus", () => {
    if (input.value.trim()) paintRows(box, input, list, input.value);
  });
  list.addEventListener("mousedown", (event) => event.preventDefault());

  return el("div", { class: "hero-assist" },
    actions.length ? el("ul", { class: "hero-actions", "aria-label": "다음 행동" }, ...actions.map(actionRow)) : null,
    el("div", { class: "cmd", role: "search" }, box, list),
    el("p", { class: "cmd-say", id: "assistant-say", "aria-live": "polite", "aria-atomic": "true" }),
  );
}

function say(text) {
  const node = document.getElementById("assistant-say");
  if (node) node.textContent = text;
}

function busyState(on) {
  busy = on;
  const preview = document.getElementById("hero-plan-preview");
  preview?.setAttribute("aria-busy", String(on));
  const accept = preview?.querySelector(".hero-plan-accept");
  if (accept) accept.disabled = on;
  const input = document.getElementById("assistant-input");
  if (!input) return;
  input.readOnly = on;
  input.setAttribute("aria-busy", on ? "true" : "false");
  const box = input.closest(".cmd-box");
  if (box) box.classList.toggle("is-busy", on);
}

/** 강의실 명령은 로컬 색인으로 즉시 이동하고, 그 외 입력은 AI에 전달한다. */
export async function submitCommand(node, api) {
  const input = document.getElementById("assistant-input");
  if (!input || busy) return;
  const acceptRun = node.dataset.acceptRun;
  const text = acceptRun ? "ㄱㄱ" : input.value.trim();
  if (!text) return;
  const reply = planReply(text);
  const run = runState();
  const preview = document.getElementById("hero-plan-preview");
  if (acceptRun && (acceptRun !== run?.id || run.status !== "ready"
    || preview?.dataset.run !== acceptRun || preview.hidden)) return;
  if (reply && run?.status === "ready" && preview?.dataset.run === run.id && !preview.hidden) {
    clearCommand(input);
    if (reply === "cancel") {
      stopRun();
      refreshPreview();
      refreshTray();
      say("계획을 취소했어요. 원하는 일을 다시 입력해 주세요.");
    } else {
      startRun({ navigate: api.navigate });
      refreshTray();
    }
    return;
  }
  if (reply === "accept" && !run) {
    clearCommand(input);
    say("먼저 원하는 계획을 입력해 주세요. 요약을 확인한 뒤 시작할 수 있어요.");
    return;
  }
  // 현재 계획을 문맥으로 유지한다. 수정·거절·새 요청은 AI 응답이 확정된 뒤 적용한다.
  const revision = inputRevision;
  const isCurrent = () => document.getElementById("assistant-input") === input
    && inputRevision === revision && input.value.trim() === text;
  const classroom = classroomForCommand(node.dataset.command || text);
  busyState(true);
  say("");
  let result;
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
    busyState(false);
    say("요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요");
    return;
  }
  let leaving = false;
  const request = execute(result, {
    text,
    navigate: (href, opts) => {
      leaving = true;
      say(result.say);
      if (isCurrent()) api.navigate(href, opts);
      busyState(false);
    },
  });
  if (request) openTray();
  if (leaving) return;
  busyState(false);
  say(result.say);
  if (result.kind === "choose") focusTray();
  refreshPreview();
  refreshTray();
  const field = document.getElementById("assistant-input");
  if (field) {
    clearCommand(field);
    if (["clarify", "plan", "revise", "reject"].includes(result.kind)) field.focus();
    if (result.kind === "plan") say("계획을 요약했어요. Accept를 누르거나 ㄱㄱ · 진행해라고 입력하면 시작해요.");
    if (result.kind === "revise") say("계획을 수정했어요. 전체 목록을 확인한 뒤 Accept 또는 ㄱㄱ로 시작해 주세요.");
  }
}

// `/`: 입력 요소에 포커스가 없을 때 명령 입력창으로. 대화상자가 열려 있으면 건드리지 않는다.
if (typeof document !== "undefined") document.addEventListener("keydown", (event) => {
  if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey || event.defaultPrevented) return;
  const target = event.target;
  if (target && target.closest && target.closest("input, textarea, select, [contenteditable]")) return;
  if (document.querySelector("[role=dialog]")) return;
  const input = document.getElementById("assistant-input");
  if (!input) return;
  event.preventDefault();
  input.focus();
  input.select();
});
