import { screens, matchScreen, hrefFor, find } from "./manifest.js";
import { equivalentHref, routeFor } from "./shared/routes.js";
import { getState, save, resetState } from "./store.js";
import { el, icon } from "./dom.js";
import { writeQuery } from "./shared/query.js";
import { errorState, btn } from "./ui/components.js";
import { buildChrome } from "./shell/shell.js";
import { renderScreen, renderDialog, fillAside, fillHero, actions, areaOf } from "./areas/registry.js";
import { activities, courseById } from "./data.js";
import { paintPlayControl } from "./shared/player-controls.js";
import { connect as connectBackend, subscribe as onBackendChange, followRole } from "./shared/backend.js";
import { assignmentIdOf } from "./shared/assignments-remote.js";
import { quizIdOf } from "./shared/quizzes-remote.js";
import { surveyIdOf } from "./shared/surveys-remote.js";
import { lectureIdOf } from "./shared/lectures-remote.js";
import { activityLinkOf } from "./shared/activity-links.js";
import { replaceVisit, watchMark, releaseWatchers } from "./shared/remote-cache.js";

// In backend mode, switching the sample role also switches the developer-login persona.
function followBackendRole(role) {
  followRole(role).then((error) => {
    if (!error) return;
    getState().toast = error.message;
    paintToast();
  });
}

let timer = 0;
const TOAST_DURATION = 3500;
let toastTimer = 0;
let toastMessage = "";

function paramsOf() {
  return new URLSearchParams(location.search);
}

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function readForm() {
  const state = getState();
  const data = {};
  document.querySelectorAll("[data-name]").forEach((node) => {
    const key = node.dataset.name;
    if (node.type === "checkbox") {
      if (node.value && node.value !== "on") {
        if (!Array.isArray(data[key])) data[key] = [];
        if (node.checked) data[key].push(node.value);
      } else data[key] = node.checked;
    } else if (node.type === "file") {
      data[key] = (node.files && node.files[0] && node.files[0].name) || state.draft.fileName || "";
    } else data[key] = node.value;
  });
  return data;
}

function showErrors(errors) {
  document.querySelectorAll(".err.live").forEach((node) => node.remove());
  const messages = Object.values(errors);
  for (const [key, msg] of Object.entries(errors)) {
    const node = document.querySelector(`[data-name="${CSS.escape(key)}"]`);
    if (!node || !node.parentElement) continue;
    node.parentElement.append(el("span", { class: "err live", text: msg }));
  }
  getState().toast = messages[0] || "";
  paintToast();
}

function paintToast() {
  const state = getState();
  let toast = document.querySelector(".toast");
  if (!state.toast) {
    clearTimeout(toastTimer);
    toastTimer = 0;
    toastMessage = "";
    if (toast) toast.remove();
    return;
  }
  if (!toast) {
    toast = el("div", { class: "toast", role: "status" });
    document.body.append(toast);
  }
  toast.textContent = state.toast;
  if (toastTimer && toastMessage === state.toast) return;
  clearTimeout(toastTimer);
  toastMessage = state.toast;
  const message = state.toast;
  toastTimer = setTimeout(() => {
    toastTimer = 0;
    if (getState().toast !== message) return;
    getState().toast = "";
    paintToast();
  }, TOAST_DURATION);
}

function navigate(href, opts = {}) {
  const state = getState();
  let url = new URL(href, location.origin);
  if (url.origin !== location.origin) return;
  if (opts.logout) {
    state.role = "student";
    state.navOpen = false;
    save();
    history.pushState({}, "", "/ilos/lo/logout.acl");
    applyRoute();
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    return;
  }
  if (!opts.allowExit) {
    url = new URL(equivalentHref(state.role, `${url.pathname}${url.search}`), location.origin);
  }
  if (state.role === "professor" || state.role === "student") {
    url.searchParams.set("as", state.role === "professor" ? "pf" : "st");
  }
  const pageChanged = url.pathname !== location.pathname
    || url.searchParams.get("kj") !== paramsOf().get("kj");
  state.navOpen = false;
  history.pushState({}, "", `${url.pathname}${url.search}`);
  applyRoute();
  if (pageChanged) window.scrollTo({ top: 0, left: 0, behavior: "instant" });
}

function closeDialog() {
  const params = paramsOf();
  if (!params.has("pop")) {
    navigate("/ilos/main/main_form.acl");
    return;
  }
  params.delete("pop");
  const path = location.pathname === "/" ? "/ilos/main/main_form.acl" : location.pathname;
  const q = params.toString();
  // A window opened in place (`popInPlace` on the entry, e.g. the entry notice) closes in place:
  // no new history entry, and the screen under it is not treated as a new visit.
  const entry = history.state;
  if (entry && typeof entry === "object" && entry.popInPlace) {
    const { popInPlace, ...rest } = entry;
    replaceVisit(q ? `${path}?${q}` : path, rest);
    applyRoute();
    return;
  }
  navigate(q ? `${path}?${q}` : path);
}

function systemTheme() {
  return window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

function paintTheme(next, mode, origin, animate) {
  const state = getState();
  const root = document.documentElement;
  if (origin) {
    root.style.setProperty("--reveal-x", `${origin.x}px`);
    root.style.setProperty("--reveal-y", `${origin.y}px`);
  }
  const apply = () => {
    state.theme = next;
    state.themeMode = mode;
    root.setAttribute("data-theme", next);
    root.setAttribute("data-theme-mode", mode);
    save();
    render();
  };
  const reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (animate && document.startViewTransition && !reduce) document.startViewTransition(apply);
  else apply();
}

function resolveScreen(pathname, params) {
  const matched = matchScreen(pathname, params);
  const itemId = params.get("n");
  const activity = itemId ? activities.find((item) => item.id === itemId) : null;
  const playerPath = "/ilos/cls/st/activity/player_form.acl";
  const activityPath = "/ilos/cls/st/activity/activity_form.acl";
  // A backend assignment (`m=report&n=srv-<assignmentId>`, backend mode) opens the server detail.
  const serverId = pathname === activityPath && params.get("m") === "report" ? assignmentIdOf(itemId) : null;
  if (serverId) {
    const kj = params.get("kj");
    return {
      id: `srv-report-${serverId}`,
      role: "student",
      title: "과제",
      path: activityPath,
      nav: "activity",
      kind: "activity",
      serverAssignment: serverId,
      course: kj && courseById(kj).id === kj ? kj : "bi",
      layout: "course",
    };
  }
  // A backend quiz (`m=test&n=srv-<quizId>`, `&attempt=<attemptId>`) or course survey (`m=survey&n=srv-<surveyId>`).
  const serverQuiz = pathname === activityPath && params.get("m") === "test" ? quizIdOf(itemId) : null;
  const serverSurvey = pathname === activityPath && params.get("m") === "survey" ? surveyIdOf(itemId) : null;
  if (serverQuiz || serverSurvey) {
    const kj = params.get("kj");
    const attempt = serverQuiz ? quizIdOf(`srv-${params.get("attempt") || ""}`) : null;
    return {
      id: serverQuiz ? `srv-test-${serverQuiz}` : `srv-survey-${serverSurvey}`,
      role: "student",
      title: serverQuiz ? "시험" : "설문",
      path: activityPath,
      nav: "activity",
      kind: "activity",
      serverQuiz,
      serverAttempt: attempt,
      serverSurvey,
      course: kj && courseById(kj).id === kj ? kj : "bi",
      layout: "course",
    };
  }
  // A backend lecture: detail `m=lecture_weeks&n=srv-<activityId>`, player `n=srv-<activityId>&item=<itemId>`.
  const lectureId = (pathname === activityPath && params.get("m") === "lecture_weeks") || pathname === playerPath ? lectureIdOf(itemId) : null;
  if (lectureId) {
    const kj = params.get("kj");
    const item = /^[1-9]\d{0,15}$/.test(params.get("item") || "") ? Number(params.get("item")) : null;
    return {
      id: `srv-lecture-${lectureId}`,
      role: "student",
      title: pathname === playerPath ? "플레이어" : "영상",
      path: pathname,
      nav: "activity",
      kind: pathname === playerPath ? "player" : "activity",
      serverLecture: lectureId,
      serverItem: pathname === playerPath ? item : null,
      course: kj && courseById(kj).id === kj ? kj : "bi",
      layout: "course",
    };
  }
  // A backend material (`m=lecture_material&n=srv-<activityId>`) or an activity link that only knows the
  // learning activity id (`m=report|test|survey&act=<activityId>`, to-do and notifications).
  const materialId = pathname === activityPath && params.get("m") === "lecture_material" ? lectureIdOf(itemId) : null;
  const link = pathname === activityPath && !itemId ? activityLinkOf(params) : null;
  if (materialId || link) {
    const kj = params.get("kj");
    return {
      id: materialId ? `srv-material-${materialId}` : `srv-link-${link.type}-${link.activityId}`,
      role: "student",
      title: materialId ? "자료" : "학습활동",
      path: activityPath,
      nav: "activity",
      kind: "activity",
      serverMaterial: materialId,
      serverLink: link,
      course: kj && courseById(kj).id === kj ? kj : "bi",
      layout: "course",
    };
  }
  // A backend file preview (`preview_form.acl?n=srv-<fileId>`, backend mode) opens the server preview (learning/preview-server.js).
  const previewPath = "/ilos/cls/st/files/preview_form.acl";
  if (pathname === previewPath && !matched && /^srv-[1-9][0-9]{0,17}$/.test(String(itemId || ""))) {
    const kj = params.get("kj");
    return { id: `srv-preview-${itemId}`, role: "student", title: "미리보기", path: previewPath, nav: "files", kind: "preview", course: kj && courseById(kj).id === kj ? kj : "bi", layout: "course" };
  }
  if (!activity || (pathname !== playerPath && pathname !== activityPath)) return matched;
  if (matched && matched.activityId === activity.id) return matched;
  return {
    id: `open-${activity.id}`,
    role: "student",
    title: activity.title,
    path: pathname,
    nav: "activity",
    kind: pathname === playerPath ? "player" : "activity",
    activityId: activity.id,
    course: activity.courseId,
    layout: "course",
  };
}

// Builds the screen and swaps it in, then releases the server-data watchers of the screen it
// replaced, so their listeners do not hold the detached DOM until the next notification.
function draw() {
  const mark = watchMark();
  drawScreen();
  releaseWatchers(mark);
}

function drawScreen() {
  const state = getState();
  const params = paramsOf();
  const screen = resolveScreen(location.pathname, params) || screens.find((s) => s.id === "00_main_home");
  document.title = `STack · ${screen.title}`;
  const ctx = { state, screen, params };
  const chromeRole = screen.role === "any" ? state.role : screen.role;
  if (screen.layout === "message") {
    const root = el("div", { class: "msg-shell" });
    const win = el("div", { class: "msg-window" });
    win.append(
      el("div", { class: "msg-top" },
        el("strong", { text: "메시지" }),
        el("button", { type: "button", class: "icon-btn", "data-go": "/ilos/main/main_form.acl", "aria-label": "닫기" }, icon("close")),
      ),
      renderScreen(screen, ctx),
    );
    root.append(win);
    const dialog = screen.dialog || params.get("pop");
    if (dialog) root.append(renderDialog(dialog, ctx));
    document.getElementById("app").replaceChildren(root);
    paintToast();
    focusDialog();
    return;
  }
  let page;
  let slot;
  let aside;
  let hero;
  let dialogs;
  try {
    ({ page, slot, aside, hero, dialogs } = buildChrome(screen, ctx));
    if (chromeRole !== state.role && screen.role !== "any") {
      slot.append(el("div", { class: "banner-role" },
        el("span", { text: chromeRole === "professor" ? "교수 화면입니다. 샘플 역할을 교수로 바꾸면 메뉴가 맞춰집니다." : "학생 화면입니다." }),
        el("button", { type: "button", class: "btn btn-primary", "data-act": "role", "data-role": chromeRole, text: chromeRole === "professor" ? "교수로 전환" : "학생으로 전환" }),
      ));
    }
    slot.append(renderScreen(screen, ctx));
    if (aside && aside.parentNode) fillAside(aside, ctx);
    if (hero) fillHero(hero, ctx);
    const dialog = screen.dialog || params.get("pop");
    if (dialog) dialogs.append(renderDialog(dialog, ctx));
  } catch (error) {
    page = el("div", { class: "chrome" }, errorState("화면을 표시하지 못했습니다", "다시 시도해 주세요.", btn("홈으로", { "data-go": "/ilos/main/main_form.acl" }, "primary")));
    console.error(error);
  }
  document.getElementById("app").replaceChildren(page);
  paintToast();
  focusDialog();
}

function focusDialog() {
  const dialog = document.querySelector("[role=dialog]");
  if (!dialog || dialog.contains(document.activeElement)) return;
  const focusable = dialog.querySelector("button, [href], input, select, textarea");
  if (focusable && focusable.focus) focusable.focus();
}

function render() {
  if (timer) {
    clearInterval(timer);
    timer = 0;
  }
  const typingScope = document.activeElement && document.activeElement.dataset && document.activeElement.dataset.name === "surfaceQuery"
    ? document.activeElement.dataset.scope || ""
    : "";
  const keepFullscreenPlayer = document.fullscreenElement?.matches(".player-page")
    && resolveScreen(location.pathname, paramsOf())?.kind === "player";
  if (keepFullscreenPlayer) {
    const player = getState().player;
    const play = document.querySelector('.player-page [data-act="play"]');
    if (play) {
      const playing = Boolean(player.playing);
      const label = playing ? "일시정지" : "재생";
      paintPlayControl(play, playing, { reveal: play.getAttribute("aria-label") !== label });
    }
    for (const button of document.querySelectorAll('.player-page [data-act="speed"]')) {
      const selected = Number(button.dataset.speed) === player.speed;
      button.classList.toggle("on", selected);
      button.setAttribute("aria-pressed", String(selected));
    }
  } else draw();
  if (typingScope) {
    const focus = document.querySelector(`[data-focus="${CSS.escape(typingScope)}"]`);
    if (focus) {
      focus.focus();
      const len = focus.value.length;
      if (focus.setSelectionRange) focus.setSelectionRange(len, len);
    }
  }
  const state = getState();
  const screen = resolveScreen(location.pathname, paramsOf());
  if (screen && screen.kind === "player" && state.player.playing && !document.querySelector(".lecture-video")) {
    timer = setInterval(() => {
      const player = getState().player;
      player.seconds = Math.min(player.duration, player.seconds + player.speed);
      if (player.seconds >= player.duration) player.playing = false;
      save();
      const slider = document.querySelector('.player-seek [data-act="seek"]');
      if (slider) {
        slider.value = String(player.seconds);
        const seconds = Math.floor(player.seconds);
        slider.previousElementSibling.textContent = `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
      }
      const pill = document.querySelector(".player-page .attend-pill");
      if (pill) {
        const done = player.seconds / player.duration >= 0.9;
        pill.classList.toggle("ok", done);
        pill.textContent = done ? "출석 완료" : "출석 진행";
      }
      if (!player.playing) {
        clearInterval(timer);
        timer = 0;
        const play = document.querySelector('.player-page [data-act="play"]');
        if (play) paintPlayControl(play, false, { reveal: true });
      }
    }, 1000);
  }
}

export function applyRoute() {
  const state = getState();
  const params = paramsOf();
  const screen = resolveScreen(location.pathname, params);
  const previousRole = state.role;
  if (params.get("as") === "pf") state.role = "professor";
  else if (params.get("as") === "st") state.role = "student";
  else if (screen && screen.role === "professor") state.role = "professor";
  else if (screen && screen.role === "student") state.role = "student";
  // A role change by address (?as=, screen role) also switches the backend persona; no-op unless `ready`.
  if (state.role !== previousRole) followBackendRole(state.role);
  if (screen && screen.role === "student" && state.role === "professor" && params.get("as") === "pf") {
    history.replaceState({}, "", equivalentHref("professor", `${location.pathname}${location.search}`));
    applyRoute();
    return;
  }
  if (screen && screen.role === "professor" && state.role === "student" && params.get("as") === "st") {
    history.replaceState({}, "", equivalentHref("student", `${location.pathname}${location.search}`));
    applyRoute();
    return;
  }
  if (screen && (screen.role === "professor" || screen.role === "student") && screen.role === state.role) {
    const want = state.role === "professor" ? "pf" : "st";
    if (params.get("as") !== want) {
      params.set("as", want);
      history.replaceState({}, "", `${location.pathname}?${params.toString()}`);
    }
  }
  if (params.get("theme") === "dark" || params.get("theme") === "light") {
    state.theme = params.get("theme");
    state.themeMode = params.get("theme");
  }
  if (screen && screen.kind === "player" && screen.activityId && state.player.id !== screen.activityId) {
    const clip = activities.find((item) => item.id === screen.activityId);
    state.player.positions = state.player.positions || {};
    state.player.id = screen.activityId;
    state.player.seconds = Number(state.player.positions[screen.activityId]) || 0;
    state.player.duration = ((clip && clip.minutes) || 30) * 60;
    state.player.playing = false;
  }
  if (screen && screen.course) state.courseId = screen.course;
  if (params.get("kj") && courseById(params.get("kj"))) state.courseId = params.get("kj");
  // `id=` is a course only when it names a sample or server course (backend mode answers any other id with a "not on the server" placeholder).
  if (params.get("id") && courseById(params.get("id")).id === params.get("id") && !courseById(params.get("id")).missing) state.courseId = params.get("id");
  const message = state.messages.find((m) => m.id === params.get("id"));
  if (message) message.read = true;
  document.documentElement.setAttribute("data-theme", state.theme || "dark");
  document.documentElement.setAttribute("data-theme-mode", state.themeMode || "system");
  document.documentElement.classList.toggle("layout-mobile", Boolean(screen && screen.layout === "mobile"));
  document.documentElement.classList.toggle("nav-open", Boolean(screen && screen.mobileMenu === "nav") || state.navOpen);
  document.documentElement.classList.toggle("aside-open", Boolean(screen && screen.mobileMenu === "aside"));
  save();
  render();
}

const api = { readForm, showErrors, navigate, closeDialog, render, stamp, getState, save, paintToast, hrefFor, find, areaOf };

function run(act, node) {
  const state = getState();
  state.formError = {};
  if (act === "nav") {
    state.navOpen = !state.navOpen;
    document.documentElement.classList.toggle("nav-open", state.navOpen);
    document.querySelector(".body")?.classList.toggle("nav-open", state.navOpen);
    const toggle = document.querySelector(".menu-toggle");
    if (toggle) toggle.setAttribute("aria-expanded", state.navOpen ? "true" : "false");
    return;
  }
  if (act === "lang") {
    state.lang = state.lang === "ko" ? "en" : "ko";
    save();
    render();
    return;
  }
  if (act === "theme") {
    const choice = node.dataset.theme === "light" || node.dataset.theme === "dark" || node.dataset.theme === "system" ? node.dataset.theme : "system";
    const box = node.getBoundingClientRect();
    const next = choice === "system" ? systemTheme() : choice;
    paintTheme(next, choice, { x: box.left + box.width / 2, y: box.top + box.height / 2 }, true);
    return;
  }
  if (act === "role") {
    state.role = node.dataset.role === "professor" ? "professor" : "student";
    state.toast = state.role === "professor" ? "교수 화면입니다" : "학생 화면입니다";
    save();
    navigate(routeFor(state.role, "home"), { allowExit: true });
    followBackendRole(state.role);
    return;
  }
  if (act === "reset") {
    resetState();
    navigate("/ilos/main/main_form.acl");
    return;
  }
  if (act === "external" || act === "related") {
    const params = paramsOf();
    params.set("pop", act === "related" ? "related" : "external");
    const path = location.pathname === "/" ? "/ilos/main/main_form.acl" : location.pathname;
    navigate(`${path}?${params.toString()}`);
    return;
  }
  if (act === "technyon") {
    state.technyonOpen = !state.technyonOpen;
    save();
    render();
    return;
  }
  if (act === "technyon-note") {
    state.toast = `${node.dataset.note || "프로그램"} 안내는 이 브라우저에만 있습니다`;
    save();
    paintToast();
    return;
  }
  if (act === "tutor-ask") {
    const data = readForm();
    if (!String(data.tutor || "").trim()) return showErrors({ tutor: "질문을 입력하세요" });
    const thread = state.draft.tutor || [{ from: "테크뇽", text: "자료에서 궁금한 점을 적어 주세요. 답은 샘플이고 학교 서버로 보내지 않습니다." }];
    thread.push({ from: "나", text: data.tutor.trim() });
    thread.push({ from: "테크뇽", text: "샘플 답변입니다. 문서 원문을 학교 서버로 보내지 않았고, 이 대화는 브라우저 안에만 있습니다." });
    state.draft.tutor = thread;
    state.toast = "테크뇽에 남겼습니다";
    save();
    render();
    return;
  }
  if (act === "seek") {
    const seconds = Number(node.value) || 0;
    const video = document.querySelector(".lecture-video");
    state.player.positions = state.player.positions || {};
    if (video && Number.isFinite(video.duration) && video.duration > 0) {
      const next = Math.min(Math.max(0, seconds), Math.max(0, video.duration - 0.05));
      video.currentTime = next;
      state.player.seconds = next;
      if (state.player.id) state.player.positions[state.player.id] = next;
      const label = document.querySelector(".player-seek .tiny");
      if (label) {
        const shown = Math.max(0, Math.round(next));
        label.textContent = `${String(Math.floor(shown / 60)).padStart(2, "0")}:${String(shown % 60).padStart(2, "0")}`;
      }
      save();
      return;
    }
    state.player.seconds = seconds;
    if (state.player.id) state.player.positions[state.player.id] = seconds;
    save();
    render();
    return;
  }
  const handler = actions[act];
  if (handler) handler(node, api);
}

document.addEventListener("click", (event) => {
  const go = event.target.closest("[data-go]");
  if (go) {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return;
    event.preventDefault();
    const resetScroll = Boolean(go.closest(".sidenav, .course-study-entry"));
    if (go.getAttribute("data-exit") === "logout") {
      navigate("/ilos/lo/logout.acl", { logout: true });
      return;
    }
    const switchRole = go.getAttribute("data-role-switch");
    if (switchRole === "student" || switchRole === "professor") {
      const state = getState();
      state.role = switchRole;
      state.toast = switchRole === "professor" ? "교수 화면입니다" : "학생 화면입니다";
      save();
      navigate(go.getAttribute("data-go"), { allowExit: true });
      followBackendRole(switchRole);
      if (resetScroll) window.scrollTo({ top: 0, left: 0, behavior: "instant" });
      return;
    }
    navigate(go.getAttribute("data-go"));
    if (resetScroll) window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    return;
  }
  const node = event.target.closest("[data-act]");
  if (!node) return;
  if (node.dataset.act === "close") {
    if (node.classList.contains("modal-back") && event.target !== node) return;
    closeDialog();
    return;
  }
  run(node.dataset.act, node);
});

document.addEventListener("change", (event) => {
  const node = event.target;
  if (node.dataset.act === "attend") return run("attend", node);
  if (node.dataset.act === "seek") return run("seek", node);
  if (node.type === "file") getState().draft.fileName = node.files && node.files[0] ? node.files[0].name : "";
});

document.addEventListener("input", (event) => {
  const node = event.target;
  if (node.dataset.name === "surfaceQuery" && node.dataset.scope) {
    writeQuery(getState(), node.dataset.scope, node.value);
    render();
  }
});

document.addEventListener("dragover", (event) => {
  if (event.target.closest("[data-drop]")) event.preventDefault();
});
document.addEventListener("drop", (event) => {
  const zone = event.target.closest("[data-drop]");
  if (!zone) return;
  event.preventDefault();
  const file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
  if (!file) return;
  getState().draft.fileName = file.name;
  zone.textContent = file.name;
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && paramsOf().has("pop")) closeDialog();
  if (event.key !== " " && event.key !== "Spacebar") return;
  const row = event.target && event.target.closest ? event.target.closest("[data-row]") : null;
  if (!row || event.target !== row) return;
  if (event.metaKey || event.ctrlKey || event.altKey) return;
  event.preventDefault();
  row.click();
});

window.addEventListener("popstate", () => applyRoute());
if (window.matchMedia) {
  window.matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => {
    const state = getState();
    if (state.themeMode !== "system") return;
    state.theme = systemTheme();
    document.documentElement.setAttribute("data-theme", state.theme);
    save();
    render();
  });
}

// The backend's school-login callback sends every failure to `/?login=failed`. Read it before the
// root redirect below drops the query; the message shows once, only when the backend is on.
const loginFailed = paramsOf().get("login") === "failed";
// A failed re-authentication whose starting page the backend could not return to lands on
// `/?reauth=failed` (the root redirect below drops it). Shown once, only when the backend is on.
const reauthFailed = location.pathname === "/" && paramsOf().get("reauth") === "failed";

if (location.pathname === "/" || location.pathname === "/index.html") {
  history.replaceState({}, "", "/ilos/main/main_form.acl");
}

function dropLoginParam() {
  const params = paramsOf();
  if (!params.has("login")) return;
  params.delete("login");
  const q = params.toString();
  history.replaceState(history.state, "", q ? `${location.pathname}?${q}` : location.pathname);
}

function syncStandalone() {
  const query = window.matchMedia ? window.matchMedia("(display-mode: standalone)") : null;
  const nav = window.navigator;
  const installed = Boolean(query && query.matches) || Boolean(nav && nav.standalone);
  document.documentElement.classList.toggle("is-standalone", installed);
}

syncStandalone();
if (window.matchMedia) {
  const query = window.matchMedia("(display-mode: standalone)");
  if (query && query.addEventListener) query.addEventListener("change", syncStandalone);
}
if (typeof navigator !== "undefined" && navigator.serviceWorker && window.isSecureContext) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}
applyRoute();
// Backend mode (server.mjs with ASTRA_API_ORIGIN) is detected after the first paint.
// Mock mode answers {enabled:false}; the status stays "off" and nothing re-renders.
let backendKey = "";
onBackendChange((next) => {
  // `methods` is in the key: an `anon` page whose login-method check failed shows 다시 확인 and must
  // re-render with the login buttons once a later check answers.
  const key = JSON.stringify([next.status, next.me, next.courses, next.methods]);
  if (key === backendKey) return;
  backendKey = key;
  render();
});
// A page opened with a role the existing session does not match (e.g. `?as=st` while logged in
// as the professor) switches persona once here; later explicit logins are left as chosen.
connectBackend().then((next) => {
  if (loginFailed && next.status !== "off") {
    dropLoginParam();
    getState().toast = "학교 계정으로 로그인하지 못했습니다. 다시 시도하세요.";
    paintToast();
  }
  if (reauthFailed && next.status !== "off") {
    getState().toast = "학교 계정으로 다시 인증하지 못했습니다. 다시 시도하세요.";
    paintToast();
  }
  if (next.status === "ready") followBackendRole(getState().role);
});
