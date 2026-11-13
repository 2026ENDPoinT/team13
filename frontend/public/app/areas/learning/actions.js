import { hrefFor, find } from "../../manifest.js";
import { togglePlayerFullscreen } from "../../shared/player-controls.js";
import { submitFromDialog, draftFromDialog } from "./assignments-server.js";
import { messageAction } from "./messages-server.js";
import { startFromDetail, submitFromAttempt } from "./quizzes-server.js";
import { completeFromForm } from "../../shared/survey-view.js";
import { attendCommand } from "./attendance-server.js";
import { pickTimetableTerm } from "./calendar-server.js";
import { activityById } from "../../shared/format.js";
import { completeStep, completionToken, runState } from "../../shared/assistant/plan.js";

// Navigation replaces submit controls; a queued click on the old control is not a new submission.
const completedSubmitControls = new WeakSet();

function mailDraft(state, data) {
  state.draft.mail = {
    ...(state.draft.mail || {}),
    to: data.to || "",
    title: data.title || "",
    body: data.body || "",
    course: data.course || "",
  };
}

export const actions = {
  /** Backend assignment dialog: final submission (`Idempotency-Key`, `expectedVersion`). */
  "srv-assignment-submit"(node, api) {
    submitFromDialog(node, api);
  },
  /** Backend timetable (05): open the term chosen from the response's `terms[]`. */
  "srv-tt-term"(node, api) {
    pickTimetableTerm(node, api);
  },
  /** Backend assignment dialog: explicit draft save. */
  "srv-assignment-draft"(node) {
    draftFromDialog(node);
  },
  /** Backend 쪽지 (areas/learning/messages-server.js): send, recipient dialog, read, recall, delete. */
  "srv-msg-send"(node, api) {
    messageAction(node, api);
  },
  "srv-msg-recipients"(node, api) {
    messageAction(node, api);
  },
  "srv-msg-pick"(node, api) {
    messageAction(node, api);
  },
  "srv-msg-read"(node, api) {
    messageAction(node, api);
  },
  "srv-msg-recall"(node, api) {
    messageAction(node, api);
  },
  "srv-msg-delete"(node, api) {
    messageAction(node, api);
  },
  "srv-msg-delete-picked"(node, api) {
    messageAction(node, api);
  },
  /** Backend quiz detail: start a new attempt or resume the one in progress (moves it to this session). */
  "srv-quiz-start"(node, api) {
    void startFromDetail(node, api);
  },
  /** Backend quiz attempt: submit (`Idempotency-Key`; two presses). */
  "srv-quiz-submit"(node, api) {
    submitFromAttempt(node, api);
  },
  /** Backend survey (course or 알림마당): completion with every answer (`Idempotency-Key`). */
  "srv-survey-complete"(node, api) {
    completeFromForm(node, api);
  },
  /** Backend smart attendance: PIN or QR token check (`POST …/smart-rounds/{id}/check`). */
  "srv-attend-cmd"(node, api) {
    void attendCommand(node, api);
  },
  async fullscreen(node) {
    const page = node.closest(".player-page");
    await togglePlayerFullscreen(page);
  },
  play(node, api) {
    const video = document.querySelector(".lecture-video");
    const state = api.getState();
    if (video) {
      if (video.paused || video.ended) {
        if (video.ended) video.currentTime = 0;
        video.playbackRate = state.player.speed || 1;
        const pending = video.play();
        if (pending && typeof pending.catch === "function") pending.catch(() => {});
      } else video.pause();
      return;
    }
    state.player.playing = !state.player.playing;
    api.save();
    api.render();
  },
  speed(node, api) {
    const state = api.getState();
    state.player.speed = Number(node.dataset.speed) || 1;
    const video = document.querySelector(".lecture-video");
    if (video) {
      video.playbackRate = state.player.speed;
      document.querySelectorAll("[data-act=speed]").forEach((button) => {
        const on = Number(button.dataset.speed) === state.player.speed;
        button.classList.toggle("on", on);
        button.setAttribute("aria-pressed", on ? "true" : "false");
      });
      api.save();
      return;
    }
    api.save();
    api.render();
  },
  captions(node, api) {
    const state = api.getState();
    state.player.captions = state.player.captions === false;
    const video = document.querySelector(".lecture-video");
    const track = video && video.textTracks && video.textTracks[0];
    if (track) track.mode = state.player.captions ? "showing" : "hidden";
    node.classList.toggle("on", state.player.captions);
    node.setAttribute("aria-pressed", state.player.captions ? "true" : "false");
    api.save();
  },
  preview(node, api) {
    const state = api.getState();
    const dir = Number(node.dataset.dir) || 1;
    state.previewPage = Math.min(3, Math.max(1, (state.previewPage || 1) + dir));
    api.save();
    api.render();
  },
  "preview-zoom"(node, api) {
    const state = api.getState();
    if (node.dataset.z === "reset") state.previewZoom = 1;
    else {
      const cur = state.previewZoom || 1;
      const next = Math.round((cur + Number(node.dataset.z) * 0.1) * 10) / 10;
      state.previewZoom = Math.min(1.4, Math.max(0.8, next));
    }
    api.save();
    api.render();
  },
  "summary-tab"(node, api) {
    api.getState().summaryTab = node.dataset.tab || "summary";
    api.save();
    api.render();
  },
  "summary-sheet"(node, api) {
    const state = api.getState();
    state.summaryOpen = !state.summaryOpen;
    api.save();
    api.render();
  },
  "flip-card"(node, api) {
    const state = api.getState();
    state.summaryFlip = !state.summaryFlip;
    api.save();
    api.render();
  },
  "summary-choice"(node, api) {
    api.getState().summaryChoice = node.dataset.choice || "";
    api.save();
    api.render();
  },
  "cal-shift"(node, api) {
    const state = api.getState();
    state.calOffset = (state.calOffset || 0) + (Number(node.dataset.dir) || 0);
    api.save();
    api.render();
  },
  "tt-shift"(node, api) {
    const state = api.getState();
    state.ttOffset = (state.ttOffset || 0) + (Number(node.dataset.dir) || 0);
    api.save();
    api.render();
  },
  "attend-week"(node, api) {
    api.getState().attendanceWeek = Number(node.dataset.week) || 1;
    api.save();
    api.render();
  },
  "player-panel"(node, api) {
    const state = api.getState();
    const next = node.dataset.panel || "";
    state.playerPanel = state.playerPanel === next ? "" : next;
    api.save();
    api.render();
  },
  "player-ask"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.playerQ || "").trim()) return api.showErrors({ playerQ: "질문을 입력하세요" });
    state.draft.playerNotes = [...(state.draft.playerNotes || []), data.playerQ.trim()];
    state.playerPanel = "ask";
    state.toast = "질문을 이 브라우저에 남겼습니다";
    api.save();
    api.render();
  },
  "mail-search"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    state.draft.mailQuery = String(data.mailQuery || "").trim();
    api.save();
    api.render();
  },
  "mail-all"(node, api) {
    const state = api.getState();
    const folder = node.dataset.folder || "inbox";
    const q = String(state.draft.mailQuery || "").trim();
    const ids = state.messages
      .filter((m) => m.folder === folder && (!q || `${m.title} ${m.from} ${m.body}`.includes(q)))
      .map((m) => m.id);
    const cur = new Set(state.draft.picked || []);
    const allOn = ids.length > 0 && ids.every((id) => cur.has(id));
    state.draft.picked = allOn ? [] : ids;
    api.save();
    api.render();
  },
  "delete-mail"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const ids = new Set(data.picked || []);
    if (!ids.size) {
      state.toast = "삭제할 쪽지를 선택하세요";
      api.save();
      api.paintToast();
      return;
    }
    state.messages = state.messages.filter((m) => !ids.has(m.id));
    state.draft.picked = [];
    state.toast = "쪽지를 삭제했습니다";
    api.save();
    api.render();
  },
  "open-recipients"(node, api) {
    const state = api.getState();
    mailDraft(state, api.readForm());
    api.save();
    api.navigate(hrefFor(find("02f_msg_recipient")));
  },
  "reply-mail"(node, api) {
    const state = api.getState();
    const message = state.messages.find((m) => m.id === node.dataset.id);
    if (!message) return;
    state.draft.mail = {
      to: message.from,
      title: message.title.startsWith("Re:") ? message.title : `Re: ${message.title}`,
      body: "",
      course: message.course || "",
    };
    api.save();
    api.navigate(hrefFor(find("02e_msg_compose")));
  },
  "upload-help"(node, api) {
    const state = api.getState();
    state.toast = "첨부는 이 브라우저에만 남고 학교 서버로 올리지 않습니다. 파일을 다시 선택해 보세요.";
    api.save();
    api.paintToast();
  },
  "format-note"(node, api) {
    const state = api.getState();
    const area = document.querySelector("[data-name='body']");
    const current = area ? String(area.value || "") : String(state.draft.reportBody || "");
    const start = area && Number.isFinite(area.selectionStart) ? area.selectionStart : 0;
    const end = area && Number.isFinite(area.selectionEnd) ? area.selectionEnd : start;
    const selected = current.slice(start, end) || "텍스트";
    let wrapped;
    if (node.dataset.mark === "list") {
      wrapped = selected.split("\n").map((line) => (line.startsWith("- ") ? line : `- ${line}`)).join("\n");
    } else {
      const mark = node.dataset.mark || "**";
      wrapped = `${mark}${selected}${mark}`;
    }
    state.draft.reportBody = current.slice(0, start) + wrapped + current.slice(end);
    api.save();
    api.render();
  },
  "send-mail"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    mailDraft(state, data);
    const errors = {};
    if (!String(data.to || "").trim()) errors.to = "받는 사람을 입력하세요";
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!String(data.body || "").trim() || String(data.body).trim().length < 2) errors.body = "내용을 입력하세요";
    if (String(data.body || "").trim().length > 2000) errors.body = "내용은 2000byte 안으로 적어 주세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    state.messages.unshift({
      id: `msg-${Date.now()}`,
      folder: "sent",
      from: state.role === "professor" ? "박은주" : "박민준",
      to: data.to.trim(),
      title: data.title.trim(),
      body: data.body.trim(),
      course: data.course || "",
      at: api.stamp(),
      read: true,
    });
    state.draft.mail = { to: "", title: "", body: "", course: "" };
    state.toast = "쪽지를 보냈습니다";
    api.save();
    api.navigate(hrefFor(find("02d_msg_sent_tab")));
  },
  "pick-recipients"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const names = data.recipient || [];
    if (!names.length) return api.showErrors({ recipient: "한 명 이상 선택하세요" });
    state.draft.mail = { ...(state.draft.mail || {}), to: names.join(", ") };
    state.toast = "수신자를 넣었습니다";
    api.save();
    api.navigate(hrefFor(find("02e_msg_compose")));
  },
  "submit-report"(node, api) {
    if (completedSubmitControls.has(node)) return;
    const state = api.getState();
    const data = api.readForm();
    const body = String(data.body || state.draft.reportBody || "");
    const errors = {};
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!body.trim() || body.trim().length < 4) errors.body = "설명을 4자 이상 입력하세요";
    if (!data.file) errors.file = "첨부 파일을 선택하세요";
    if (Object.keys(errors).length) {
      state.draft.reportBody = body;
      return api.showErrors(errors);
    }
    const activity = activityById(new URLSearchParams(location.search).get("n")) || activityById("r-open");
    const token = completionToken(activity.id);
    state.submissions[activity.id] = { who: "minjun", title: data.title.trim(), body: body.trim(), fileName: data.file, at: api.stamp() };
    state.fileBox.unshift({ name: data.file, at: api.stamp().slice(0, 10) });
    state.draft.reportBody = "";
    state.draft.fileName = "";
    state.toast = "과제를 제출했습니다";
    api.save();
    completedSubmitControls.add(node);
    if (!completeStep(token, { navigate: api.navigate, event: "report-submitted" }) || runState()?.status === "done") {
      api.navigate(hrefFor(find("60_activity_report_detail_open"), { n: activity.id, kj: activity.courseId }));
    }
  },
  "save-survey"(node, api) {
    if (completedSubmitControls.has(node)) return;
    const state = api.getState();
    const data = api.readForm();
    const errors = {};
    for (const key of ["sq1", "sq2"]) if (!data[key]) errors[key] = "선택하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    const activityId = node.dataset.survey || "campus";
    const token = completionToken(activityId);
    state.surveys[activityId] = { ...data, done: true };
    state.toast = "설문을 저장했습니다";
    api.save();
    completedSubmitControls.add(node);
    if (!completeStep(token, { navigate: api.navigate, event: "survey-saved" }) || runState()?.status === "done") {
      api.closeDialog();
    }
  },
};
