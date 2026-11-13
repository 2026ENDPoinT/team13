// Professor 쪽지 발송 (`pf_message`) in backend mode: pick course members through
// `GET /api/messages/recipients?courseId=` and send one message with `POST /api/messages`
// (Idempotency-Key; a retry of the same content, attachments included, after a lost response
// reuses it). Attachments are `message_attachment` uploads without a course (`messageFiles`).
// Below the form, the professor's own 보낸 쪽지 box (`GET /api/messages/sent`, every course):
// search, "더 보기" paging, selected delete and 전체 삭제 (shared/message-box.js `serverSentBox`).
// Typed text, the chosen people and picked files stay in memory per course until a send succeeds.
// Sending lives in actions.js (`pf-message-send`).
import { el } from "../../dom.js";
import { btn, field, emptyState } from "../../ui/components.js";
import { backendStatus, courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { resetAttachments } from "../../shared/attach-picker.js";
import {
  sendMessage, isNotAllowed, isUncertain, isAttachmentNotReady, messageMessage, screenBytes, ROLE_LABEL, MAX_TITLE, MAX_BODY_BYTES, MAX_RECIPIENTS,
} from "../../shared/messages-remote.js";
import { recipientSearch, serverSentBox, messageFiles, filesProblem, SCOPE_TEXT } from "../../shared/message-box.js";

const memos = new Map(); // backend courseId -> { title, body, chosen: Map, files, receipt }
const controllers = new WeakMap(); // form sheet -> controller
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
});

function memoOf(courseId) {
  if (!memos.has(courseId)) memos.set(courseId, { title: "", body: "", chosen: new Map(), files: {}, receipt: null });
  return memos.get(courseId);
}

function say(node, text, alert) {
  node.setAttribute("role", alert ? "alert" : "status");
  node.textContent = text || "";
}

/** The `pf_message` body in backend mode. `parts` are professor.js helpers. */
export function serverMessageFields(ctx, course, parts) {
  const stack = (...nodes) => el("div", { class: "stack" }, ...nodes);
  if (backendStatus() === "anon") {
    // The sample form is hidden: a message kept only in this browser would look sent.
    return stack(backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 수강생에게 쪽지를 보낼 수 있습니다." }));
  }
  const courseId = courseIdFor(course.id);
  if (!courseId) return stack(backendPanel(), emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 구성원에게만 쪽지를 보낼 수 있습니다."));
  if (roleIn(course.id) !== "INSTRUCTOR") {
    return stack(backendPanel(), el("p", { class: "pf-sub", text: "이 과목을 담당하는 교수 계정으로 로그인해야 과목 구성원에게 쪽지를 보낼 수 있습니다." }));
  }
  const box = serverSentBox();
  const { formSheet, span } = parts;
  const sent = formSheet("보낸 쪽지", span(el("p", { class: "pf-sub", text: "모든 과목에서 보낸 쪽지입니다. 여기서 지우면 보낸 쪽지함에서만 사라지고 받는 사람의 쪽지는 그대로입니다." })), span(box.node));
  sent.dataset.pfSentBox = "";
  return stack(backendPanel(), messageForm(courseId, parts, box.refresh), sent);
}

function messageForm(courseId, parts, onSent) {
  const { formSheet, span } = parts;
  const memo = memoOf(courseId);
  const title = el("input", { maxlength: String(MAX_TITLE), value: memo.title, "data-pf-msg-title": "" });
  const body = el("textarea", { rows: "8", text: memo.body, "data-pf-msg-body": "" });
  const counter = el("p", { class: "pf-sub", "aria-live": "polite" });
  const chips = el("div", { class: "msg-chips", "aria-label": "고른 받는 사람" });
  const status = el("p", { class: "pf-sub", role: "status", "aria-live": "polite", "data-pf-msg-status": "" });
  const receipt = el("div", {});
  const send = btn("발송", { "data-act": "pf-message-send" }, "primary");
  let finder = null;
  let picker = messageFiles(memo.files);
  const files = el("div", { "data-pf-msg-files": "" }, picker.node);

  const paintCounter = () => {
    const bytes = screenBytes(body.value.trim());
    counter.textContent = `${bytes}/${MAX_BODY_BYTES}byte`;
    counter.classList.toggle("err", bytes > MAX_BODY_BYTES);
  };
  const paintChips = () => {
    const people = [...memo.chosen.values()];
    chips.replaceChildren(...(people.length
      ? people.map((person) => {
        const remove = btn("×", { class: "btn btn-ghost msg-chip__x", "aria-label": `${person.name} 빼기` }, "ghost");
        remove.addEventListener("click", () => {
          memo.chosen.delete(person.userId);
          paintChips();
          if (finder) finder.refresh();
          title.focus();
        });
        return el("span", { class: "msg-chip" }, el("span", { text: `${person.name} · ${ROLE_LABEL[person.role] || "구성원"}` }), remove);
      })
      : [el("span", { class: "pf-sub", text: "아래에서 받는 사람을 고르세요." })]));
  };
  const paintReceipt = () => {
    const last = memo.receipt;
    receipt.replaceChildren(...(last ? [el("section", { class: "note ok", role: "status", "data-pf-msg-receipt": "" },
      el("p", { text: `${last.recipients.length}명에게 쪽지를 보냈습니다.` }),
      el("p", { class: "pf-sub", text: last.recipients.map((person) => person.name).join(", ") }),
    )] : []));
  };

  title.addEventListener("input", () => { memo.title = title.value; });
  body.addEventListener("input", () => {
    memo.body = body.value;
    paintCounter();
  });
  finder = recipientSearch({ courseId, chosen: memo.chosen, onChange: paintChips });
  paintCounter();
  paintChips();
  paintReceipt();

  const sheet = formSheet("쪽지",
    span(field("제목", title)),
    span(field("내용", body)),
    span(counter),
    span(el("div", {}, el("p", { class: "pf-kicker", text: `받는 사람 (${MAX_RECIPIENTS}명까지)` }), chips)),
    span(el("p", { class: "pf-sub", "data-msg-scope": "", text: SCOPE_TEXT })),
    span(finder.node),
    span(el("div", {}, el("p", { class: "pf-kicker", text: "첨부 파일" }), files)),
    span(status),
    span(receipt),
    span(el("div", { class: "pf-actions" }, send)),
  );
  sheet.dataset.messageForm = "";

  let sending = false;
  controllers.set(sheet, {
    async send(api) {
      if (sending) return;
      const text = title.value.trim();
      const problems = [];
      if (!memo.chosen.size) problems.push("받는 사람을 한 명 이상 고르세요.");
      else if (memo.chosen.size > MAX_RECIPIENTS) problems.push(`받는 사람은 ${MAX_RECIPIENTS}명까지입니다.`);
      if (!text) problems.push("제목을 입력하세요.");
      if (!body.value.trim()) problems.push("내용을 입력하세요.");
      else if (screenBytes(body.value.trim()) > MAX_BODY_BYTES) problems.push(`내용은 ${MAX_BODY_BYTES}byte 이하로 적어 주세요.`);
      const fileProblem = filesProblem(picker, memo.files);
      if (fileProblem) problems.push(fileProblem);
      if (problems.length) {
        say(status, problems.join(" "), true);
        return;
      }
      sending = true;
      send.disabled = true;
      send.setAttribute("aria-busy", "true");
      send.textContent = "보내는 중…";
      say(status, "ASTRA 서버로 보내고 있습니다.");
      try {
        const sent = await sendMessage({ title: text, body: body.value, courseId, recipientIds: [...memo.chosen.keys()], uploadIds: picker.uploadIds() });
        memo.title = "";
        memo.body = "";
        memo.chosen.clear();
        resetAttachments(memo.files);
        picker = messageFiles(memo.files);
        files.replaceChildren(picker.node);
        memo.receipt = { recipients: Array.isArray(sent.recipients) ? sent.recipients : [] };
        title.value = "";
        body.value = "";
        paintCounter();
        paintChips();
        finder.refresh();
        paintReceipt();
        if (onSent) onSent();
        say(status, "");
        api.getState().toast = "쪽지를 보냈습니다";
        api.paintToast();
      } catch (error) {
        if (isNotAllowed(error)) say(status, messageMessage(error), true);
        else if (isAttachmentNotReady(error)) {
          picker.recheck();
          say(status, messageMessage(error), true);
        } else if (isUncertain(error)) say(status, "보낸 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 보내세요. 이미 보내졌다면 두 번 보내지 않습니다.", true);
        else say(status, messageMessage(error), true);
      } finally {
        sending = false;
        if (send.isConnected) {
          send.disabled = false;
          send.removeAttribute("aria-busy");
          send.textContent = "발송";
        }
      }
    },
  });
  return sheet;
}

/** `pf-message-send` (professor/actions.js). */
export function sendProfessorMessage(node, api) {
  const sheet = node.closest("[data-message-form]");
  const controller = sheet && controllers.get(sheet);
  if (controller) void controller.send(api);
}
