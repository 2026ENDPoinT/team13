import { startRecording, pauseRecording, resumeRecording, stopRecording, deleteRecording } from "../../shared/course-recordings.js";
import { setRecordingError, lockSources } from "./recording.js";
import { folderHref } from "./study-files.js";
import { choose } from "../../shared/assistant/run.js";
import { advance, pauseRun, resumeRun, runState, selectStep, stopRun } from "../../shared/assistant/plan.js";
import { foldTray, refreshTray, toggleTray, togglePlanStep } from "../../shared/assistant-tray.js";
import { submitCommand } from "./command-bar.js";

import { searchCatalog, applyFromDialog, cancelFromButton, requestAuditFromDetail, saveAuditReason } from "./catalog-server.js";
import { openNotification } from "./notifications-server.js";
import { savePreferencesAction } from "./account-server.js";
import { runCommunityCommand } from "./community-server.js";
import { siteNoticeAction } from "./site-notices-server.js";

export const actions = {
  "assistant-ask"(node, api) {
    submitCommand(node, api);
  },
  "assistant-choose"(node, api) {
    foldTray();
    choose(node.dataset.request, node.dataset.id, { navigate: api.navigate });
  },
  "assistant-tray-toggle"() {
    toggleTray();
  },
  // 계획: 이동하는 동작은 이동하기 전에 알약으로 접고, 이동하지 않는 동작은 트레이만 새로 그린다.
  "assistant-run-select"(node, api) {
    if (node.dataset.run !== runState()?.id) return;
    selectStep(Number(node.dataset.index), { navigate: (href) => {
      foldTray();
      api.navigate(href);
    } });
    refreshTray();
  },
  "assistant-run-next"(node, api) {
    advance({ navigate: api.navigate });
    refreshTray();
  },
  "assistant-run-skip"(node, api) {
    advance({ navigate: api.navigate, skip: true });
    refreshTray();
  },
  "assistant-run-pause"() {
    pauseRun();
    refreshTray();
  },
  "assistant-run-resume"(node, api) {
    foldTray();
    resumeRun({ navigate: api.navigate });
    refreshTray();
  },
  "assistant-run-stop"() {
    stopRun();
    refreshTray();
  },
  "assistant-run-expand"(node) {
    togglePlanStep(Number(node.dataset.index));
  },
  /** Backend 알림마당 1:1 문의·자료실 commands (areas/home/community-server.js). */
  "srv-community"(node, api) {
    runCommunityCommand(node, api);
  },
  /** Backend catalog: filter values go into the address (page 1). */
  "srv-catalog-search"(node, api) {
    searchCatalog(node, api);
  },
  /** Backend non-regular apply; a retry after a lost response reuses the Idempotency-Key. */
  "srv-enroll-apply"(node, api) {
    void applyFromDialog(node, api);
  },
  /** Backend cancel (two presses). Idempotent on the server. */
  "srv-enroll-cancel"(node, api) {
    void cancelFromButton(node, api);
  },
  "srv-audit-request"(node, api) {
    void requestAuditFromDetail(node, api);
  },
  /** Backend audit reason edit with the read `version` (409 `version_conflict` reloads, keeps the text). */
  "srv-audit-reason"(node, api) {
    void saveAuditReason(node, api);
  },
  "rec-start"(node, api) {
    const source = node.dataset.source;
    if (source !== "mic" && source !== "tab") return;
    lockSources(node, "녹음 연결 중…");
    // Called inside the click: browsers open the mic prompt or tab picker only for a user gesture.
    startRecording({ courseId: node.dataset.course, source })
      .catch((error) => setRecordingError(error.message))
      .then(() => api.render());
  },
  "rec-pause"(node, api) {
    pauseRecording();
    api.render();
  },
  "rec-resume"(node, api) {
    resumeRecording();
    api.render();
  },
  "rec-stop"(node) {
    node.disabled = true;
    node.textContent = "저장 중…";
    // The recorder screen follows the "saved" or "error" event to the next view.
    stopRecording().catch(() => {});
  },
  "rec-delete"(node, api) {
    if (node.dataset.armed !== "1") {
      node.dataset.armed = "1";
      node.textContent = "한 번 더 누르면 삭제";
      return;
    }
    node.disabled = true;
    deleteRecording(node.dataset.id).then(() => {
      api.getState().toast = "녹음을 삭제했습니다";
      api.navigate(folderHref(node.dataset.course));
    }, (error) => {
      node.disabled = false;
      api.showErrors({ "rec-delete": error.message });
    });
  },
  "save-avatar"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.avatar || "").trim()) return api.showErrors({ avatar: "표시 글자를 입력하세요" });
    state.draft.avatar = data.avatar.trim().slice(0, 2);
    state.toast = "표시 글자를 저장했습니다";
    api.save();
    api.render();
  },
  "apply-course"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.motive || "").trim()) return api.showErrors({ motive: "한 줄을 입력하세요" });
    state.applications[node.dataset.id] = data.motive.trim();
    state.toast = "신청을 저장했습니다";
    api.save();
    api.navigate(`/ilos/main/nr/non_regular_view_form.acl?id=${encodeURIComponent(node.dataset.id)}&enrolled=1`);
  },
  "send-chat"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    if (!String(data.chat || "").trim()) return api.showErrors({ chat: "메시지를 입력하세요" });
    const thread = state.draft.chat || [{ from: "서포터즈", text: "샘플 상담입니다. 계정 비밀번호는 받지 않습니다." }];
    thread.push({ from: "박민준", text: data.chat.trim() });
    thread.push({ from: "서포터즈", text: "확인했습니다. 이 대화는 브라우저 안에만 있습니다." });
    state.draft.chat = thread;
    state.toast = "전송했습니다";
    api.save();
    api.render();
  },
  "ntf-open"(node, api) {
    openNotification(node, api);
  },
  "srv-preferences-save"(node, api) {
    savePreferencesAction(node, api);
  },
  /** Backend 알림마당 공지 save / publish now / delete (operators only; a command in flight is not sent twice). */
  "srv-site-notice"(node, api) {
    siteNoticeAction(node, api);
  },
};
