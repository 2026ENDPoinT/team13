// Student assignment detail and submit dialog from the ASTRA backend (`?m=report&n=srv-<id>`).
// The phase, deadline and receipt time come from the server clock. A score and feedback appear
// only when the response carries `grade` (after release); nothing hints at a hidden score.
// Typed text and picked attachments live in memory per assignment until a submission succeeds.
import { el, icon } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState, badge, dialogShell } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime, ddayAt } from "../../shared/home-remote.js";
import {
  assignmentDetails, assignmentKey, saveDraft, submitAssignment, submitUncertain, assignmentMessage, isConflict,
  PHASE, STATUS, accepting, scoreText, receiptTime, studentAssignmentHref,
} from "../../shared/assignments-remote.js";
import {
  uploadFile, awaitScan, getUpload, discardUpload, uploadFailure, downloadControl, sizeText,
} from "../../shared/uploads-remote.js";
import { fileboxAttach } from "../../shared/filebox-attach.js";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 과제를 보고 제출할 수 있습니다.";
const DRAFT_DELAY = 2000;
const MAX_UPLOADS = 5;
const MAX_BODY = 50000;
const ACCEPT = ".pdf,.docx,.pptx,.xlsx,.hwp,.hwpx,.zip,.txt,.png,.jpg,.jpeg";
const UPLOAD_STATE = {
  sending: "올리는 중",
  quarantined: "검사 중",
  clean: "첨부 준비됨",
  rejected: "서버 검사를 통과하지 못했습니다. 이 파일은 제출할 수 없습니다.",
  slow: "검사가 오래 걸리고 있습니다. 잠시 후 다시 올려 보세요.",
  gone: "더 이상 쓸 수 없는 업로드입니다. 다시 올리세요.",
};

const missing = (error) => error instanceof ApiError && error.status === 404;

// assignmentKey -> { text, uploads: [{ local, name, size, state, uploadId, message }], dropped: Set<fileId>, receipt, paint }
// `dropped`는 다시 제출할 때 지금 제출본에서 빼기로 한 파일이다(나머지는 이어서 낸다).
const compose = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  compose.clear();
});
let localIds = 0;

function draftOf(key) {
  if (!compose.has(key)) compose.set(key, { text: null, uploads: [], dropped: new Set(), receipt: null, receiptEntry: null, paint: null });
  return compose.get(key);
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** Plain text in paragraphs; every piece is a text node. */
function prose(text, empty) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? el("div", { class: "prose" }, el("p", { class: "tiny", text: empty })) : null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

/** One attached file: its name as text and a download through a Blob URL. */
function fileLine(courseId, file) {
  const { button, note } = downloadControl(courseId, file);
  return el("div", { class: "file-row" }, icon("file"),
    el("span", {}, el("b", { text: file.name }), el("span", { class: "tiny", text: sizeText(file.sizeBytes) }), note),
    button);
}

function phaseBadge(phase) {
  return badge(PHASE[phase] || phase, phase === "open" ? "success" : phase === "late" ? "neutral" : "danger");
}

function forms(info) {
  return [info.allowText ? "글 입력" : null, info.allowFile ? "파일 첨부" : null].filter(Boolean).join(" / ");
}

/** Shared gate for the detail and the dialog: a node to show instead, or null when ready. */
function gate(root, kj, paintOff) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: LOGIN_TEXT });
  if (status === "off") {
    if (!backendSettled()) {
      // A server assignment link opened directly waits for the first backend probe; a change
      // of status re-renders the app, a probe that stays off is painted here.
      queueMicrotask(() => connectBackend().then((next) => {
        if (root.isConnected && next.status === "off") paintOff();
      }));
      return loadingState("ASTRA 서버에 연결하는 중입니다");
    }
    return emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요.");
  }
  if (!courseIdFor(kj)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 과제만 볼 수 있습니다.");
  return null;
}

/** The detail screen of `screen.serverAssignment` (backend mode). `parts` are learning.js helpers. */
export function remoteAssignmentDetail(screen, ctx, parts) {
  const kj = screen.course;
  const assignmentId = screen.serverAssignment;
  const main = el("div", { class: "act-main" });
  const root = el("section", { class: "bleed act-layout is-solo is-open", "data-item-open": `srv-${assignmentId}` }, main);
  const listHref = `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;
  const blocked = gate(root, kj, () => main.replaceChildren(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    main.append(el("div", { class: "act-body" }, parts.typeLine("report"), blocked));
    return root;
  }
  const courseId = courseIdFor(kj);
  const key = assignmentKey(courseId, assignmentId);
  const load = () => assignmentDetails.load(key, courseId, assignmentId);

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main.replaceChildren(loadingState("과제를 불러오는 중입니다"));
      else if (missing(entry.error)) main.replaceChildren(emptyState("과제를 찾을 수 없습니다", "아직 공개되지 않았거나 볼 수 있는 권한이 없습니다.", go(listHref, "학습활동 목록", { class: "btn btn-secondary" })));
      else main.replaceChildren(errorState("과제를 불러오지 못했습니다", assignmentMessage(entry.error), retry(load)));
      return;
    }
    main.replaceChildren(detailBody(data, courseId, kj, assignmentId, parts, entry.error));
  };
  paint(assignmentDetails.peek(key));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    assignmentDetails.watch(root, key, paint, load);
    if (freshVisit("assignment-detail", key) === "entry" || !assignmentDetails.peek(key)) load();
  });
  return root;
}

function detailBody(data, courseId, kj, assignmentId, parts, staleError) {
  const info = data.assignment || {};
  const phase = data.phase;
  const dday = ddayAt(info.closeAt, data.serverTime);
  const due = el("span", { class: "due-line" }, el("span", { text: koDateTime(info.closeAt) }), dday ? el("span", { class: "dday", text: dday }) : null);
  const body = el("div", { class: "act-body" },
    parts.typeLine("report"),
    el("h1", { text: info.title }),
    staleError ? el("p", { class: "err", role: "alert", text: assignmentMessage(staleError) }) : null,
    parts.metaTable([
      ["제출 구간", phaseBadge(phase)],
      ["시작", info.openAt ? koDateTime(info.openAt) : "바로 시작"],
      ["마감", due],
      ["지각 제출", info.latePolicy === "allow_until" && info.lateUntil ? `${koDateTime(info.lateUntil)}까지 받습니다` : "받지 않습니다"],
      ["제출 형태", forms(info)],
      ["배점", `${scoreText(info.maxScore)}점`],
    ]),
    prose(info.description, "과제 설명이 없습니다."),
  );
  const mine = data.submission;
  if (!mine) {
    // The instructor's view of the same assignment: counts, not a submission.
    body.append(
      el("p", { class: "note", text: `담당 교수 화면입니다. 제출 ${data.submittedCount ?? 0}명 · 지각 ${data.lateCount ?? 0}명` }),
    );
    return body;
  }
  body.append(el("h2", { text: "내 제출" }));
  const submitted = mine.status === "submitted" || mine.status === "late";
  body.append(parts.metaTable([
    ["상태", badge(STATUS[mine.status] || mine.status, submitted ? "success" : "danger")],
    submitted ? ["최종 제출", `${koDateTime(mine.submittedAt)} · ${mine.version}번째 제출`] : null,
    submitted && mine.firstSubmittedAt && mine.version > 1 ? ["처음 제출", koDateTime(mine.firstSubmittedAt)] : null,
    mine.draft && mine.draft.savedAt ? ["임시 저장", `${koDateTime(mine.draft.savedAt)} (제출로 치지 않습니다)`] : null,
  ].filter(Boolean)));
  if (submitted) {
    const text = prose(mine.body);
    if (text) body.append(text);
    const files = Array.isArray(mine.files) ? mine.files : [];
    if (files.length) body.append(...files.map((file) => fileLine(courseId, file)));
  }
  // Released scores only: the server sends `grade` after release and omits it before.
  if (mine.grade && mine.grade.score != null) {
    body.append(el("section", { class: "note ok", "aria-label": "점수" },
      el("h2", { text: `점수 ${scoreText(mine.grade.score)} / ${scoreText(info.maxScore)}점` }),
      prose(mine.grade.feedback),
      mine.grade.gradedAt ? el("p", { class: "tiny", text: `${koDateTime(mine.grade.gradedAt)} 채점` }) : null,
    ));
  }
  if (accepting(phase)) {
    if (phase === "late") body.append(el("p", { class: "note", text: "지각 제출 기간입니다. 지금 제출하면 지각으로 기록됩니다." }));
    body.append(el("div", { class: "btn-row" }, go(studentAssignmentHref(kj, assignmentId, { pop: "submit" }), submitted ? "다시 제출" : "제출", { class: "btn btn-primary" })));
  } else if (phase === "closed") {
    body.append(el("p", { class: "note", text: "제출 기간이 끝났습니다." }));
  } else {
    body.append(el("p", { class: "note", text: "아직 제출 기간이 아닙니다." }));
  }
  return body;
}

// ---------- submit dialog ----------

const controllers = new WeakMap(); // dialog form -> controller

/** The submit dialog (`?pop=submit`) for a backend assignment. */
export function remoteSubmitDialog(ctx) {
  const screen = ctx.screen;
  const kj = screen.course;
  const assignmentId = screen.serverAssignment;
  const shell = (node) => dialogShell("과제 제출", node, { wide: true });
  const frame = el("div", { class: "submit-form" });
  const blocked = gate(frame, kj, () => frame.replaceChildren(emptyState("과제를 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    frame.append(blocked);
    return shell(frame);
  }
  const courseId = courseIdFor(kj);
  const key = assignmentKey(courseId, assignmentId);
  const memo = draftOf(key);
  let controller = null;

  // A receipt belongs to the history entry it was made on; reopening the dialog starts a new form.
  if (memo.receipt && memo.receiptEntry !== history.state) memo.receipt = null;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (memo.receipt) {
      frame.replaceChildren(receiptView(memo.receipt, courseId));
      controller = null;
      return;
    }
    if (!data) {
      if (controller) {
        if (entry && entry.error) controller.say(assignmentMessage(entry.error), true);
        return;
      }
      if (!entry || !entry.error) frame.replaceChildren(loadingState("과제를 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(emptyState("과제를 찾을 수 없습니다", "아직 공개되지 않았거나 제출할 수 없는 과제입니다."));
      else frame.replaceChildren(errorState("과제를 불러오지 못했습니다", assignmentMessage(entry.error), retry(() => assignmentDetails.load(key, courseId, assignmentId))));
      return;
    }
    if (!data.submission) {
      frame.replaceChildren(emptyState("담당 교수는 제출할 수 없습니다", "학생 계정으로 로그인하면 제출할 수 있습니다."));
      return;
    }
    if (!controller) {
      controller = submitForm(courseId, assignmentId, key, memo);
      frame.replaceChildren(controller.form);
    }
    controller.update(data);
  };
  memo.paint = () => paint(assignmentDetails.peek(key));
  paint(assignmentDetails.peek(key));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    assignmentDetails.watch(frame, key, paint, null);
    // Opening the dialog is a new history entry: read the latest version before the next submit.
    if (freshVisit("assignment-submit", key) === "entry" || !assignmentDetails.peek(key)) assignmentDetails.load(key, courseId, assignmentId);
  });
  return shell(frame);
}

function receiptView(receipt, courseId) {
  const files = Array.isArray(receipt.files) ? receipt.files : [];
  return el("div", {},
    el("section", { class: "note ok", role: "status" },
      el("h2", { text: "제출했습니다" }),
      el("p", { text: `서버 접수 시각 ${receiptTime(receipt.submittedAt)}` }),
      el("p", { text: `${receipt.version}번째 제출${receipt.late ? " · 지각 제출로 기록되었습니다" : ""}` }),
    ),
    ...files.map((file) => fileLine(courseId, file)),
    el("div", { class: "submit-actions" }, btn("닫기", { "data-act": "close" }, "primary")),
  );
}

function submitForm(courseId, assignmentId, key, memo) {
  let data = null;
  let busy = false;
  let drafting = false;
  let draftAgain = false;
  let draftTimer = 0;
  let savedText = null;

  const intro = el("div", {});
  const message = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  const draftNote = el("span", { class: "tiny", role: "status", "aria-live": "polite" });
  const area = el("textarea", { rows: "10", maxlength: String(MAX_BODY), placeholder: "제출할 글을 입력하세요.", "aria-label": "제출 글" });
  const draftButton = btn("임시 저장", { "data-act": "srv-assignment-draft" }, "ghost");
  const textBlock = el("div", { class: "editor" },
    el("label", { class: "field" }, el("span", { class: "field-label", text: "글" }), area),
    el("div", { class: "compose-files" }, draftButton, draftNote),
  );
  const picker = el("input", { type: "file", class: "file-input", multiple: true, accept: ACCEPT, "aria-label": "첨부 파일 고르기" });
  const keptBlock = el("div", { class: "stack" });
  const fileList = el("div", { class: "stack" });
  const activeUploads = () => memo.uploads.filter((entry) => entry.state !== "rejected" && entry.state !== "failed" && entry.state !== "gone").length;
  // 지금 제출본(현재 버전)의 파일. 다시 제출할 때 고른 것만 새 버전에 이어진다(`keepFileIds`).
  const currentFiles = () => {
    const mine = data && data.submission;
    const submitted = mine && (mine.status === "submitted" || mine.status === "late");
    return submitted && data.assignment.allowFile && Array.isArray(mine.files) ? mine.files : [];
  };
  const keptIds = () => currentFiles().filter((file) => !memo.dropped.has(file.fileId)).map((file) => file.fileId);
  const room = () => MAX_UPLOADS - activeUploads() - keptIds().length;
  // Files picked from the file box join the same list and go through the same scan wait and removal.
  const fromBox = fileboxAttach({
    courseId,
    purpose: "assignment_submission",
    limit: MAX_UPLOADS,
    room,
    onAttach: (views) => {
      for (const view of views) {
        const entry = { local: (localIds += 1), name: String(view.name || "file"), size: view.sizeBytes, state: "sending", uploadId: null, message: "" };
        memo.uploads.push(entry);
        track(entry, view);
      }
      repaint();
    },
  });
  const fileBlock = el("div", {},
    el("div", { class: "compose-files" },
      el("label", { class: "btn btn-secondary" }, icon("file"), "파일 첨부", picker),
      fromBox ? fromBox.node : null,
      el("span", { class: "tiny", text: `이어서 내는 파일과 새로 올리는 파일을 합쳐 ${MAX_UPLOADS}개까지, 50MB 이하. 검사를 통과한 파일만 제출됩니다.` }),
    ),
    keptBlock,
    fileList,
  );
  const submit = btn("제출", { "data-act": "srv-assignment-submit" }, "primary");
  const form = el("div", { class: "srv-submit" },
    intro,
    textBlock,
    fileBlock,
    message,
    el("div", { class: "submit-actions" }, btn("취소", { "data-act": "close" }, "ghost"), submit),
  );

  const say = (text, alert) => {
    message.setAttribute("role", alert ? "alert" : "status");
    message.textContent = text || "";
  };
  const repaint = () => { if (memo.paint) memo.paint(); };

  const paintFiles = () => {
    const current = currentFiles();
    keptBlock.replaceChildren(...(current.length ? [
      el("p", { class: "tiny", text: "지금 제출본의 파일입니다. 체크를 풀면 다시 제출할 때 빠지고, 체크한 파일은 새 버전에 그대로 이어집니다." }),
      ...current.map((file) => {
        const keep = el("input", { type: "checkbox", "data-keep-file": String(file.fileId) });
        keep.checked = !memo.dropped.has(file.fileId);
        keep.disabled = busy;
        const state = el("span", { class: "tiny" });
        const label = () => { state.textContent = [sizeText(file.sizeBytes), keep.checked ? "이어서 제출" : "이번 제출에서 뺌"].filter(Boolean).join(" · "); };
        label();
        keep.addEventListener("change", () => {
          if (keep.checked) memo.dropped.delete(file.fileId);
          else memo.dropped.add(file.fileId);
          label();
          if (room() < 0) say(`파일은 이어서 내는 것과 새로 올리는 것을 합쳐 ${MAX_UPLOADS}개까지 제출할 수 있습니다.`, true);
          refresh();
        });
        return el("div", { class: "file-row" }, icon("file"),
          el("span", {}, el("b", { text: file.name }), state),
          el("label", { class: "choice" }, keep, el("span", { text: "이어서 제출" })));
      }),
    ] : []));
    fileList.replaceChildren(...memo.uploads.map((entry) => {
      const remove = btn("빼기", { "aria-label": `${entry.name} 빼기` }, "ghost");
      remove.disabled = busy;
      remove.addEventListener("click", () => {
        memo.uploads = memo.uploads.filter((item) => item !== entry);
        // An unattached upload expires on its own; deleting it early is a courtesy.
        if (entry.uploadId) discardUpload(entry.uploadId);
        paintFiles();
        refresh();
      });
      const state = entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state] || UPLOAD_STATE.gone;
      return el("div", { class: "file-row" }, icon("file"),
        el("span", {}, el("b", { text: entry.name }), el("span", { class: "tiny", text: [sizeText(entry.size), state].filter(Boolean).join(" · ") })),
        remove);
    }));
  };

  const pendingUploads = () => memo.uploads.some((entry) => entry.state === "sending" || entry.state === "quarantined");
  const refresh = () => {
    const open = data && accepting(data.phase);
    submit.disabled = busy || !open || pendingUploads();
    draftButton.disabled = busy || drafting || !open;
    picker.disabled = busy || !open;
    if (fromBox) fromBox.setDisabled(busy || !open);
    area.readOnly = busy;
  };

  const track = (entry, view) => {
    entry.uploadId = view.uploadId;
    entry.state = view.state === "clean" || view.state === "quarantined" || view.state === "rejected" ? view.state : "gone";
    if (entry.state !== "quarantined") return;
    awaitScan(view, {
      alive: () => memo.uploads.includes(entry),
      onState: (next) => {
        entry.state = next.state === "clean" || next.state === "quarantined" || next.state === "rejected" ? next.state : "gone";
        repaint();
      },
    }).then((last) => {
      if (last && last.state === "quarantined") entry.state = "slow";
      repaint();
    }, (error) => {
      entry.state = "failed";
      entry.message = uploadFailure(error);
      repaint();
    });
  };

  picker.addEventListener("change", (event) => {
    // Handled here: the app-wide file listener would remember the name in saved sample state.
    event.stopPropagation();
    const picked = [...(picker.files || [])];
    picker.value = "";
    const left = room();
    if (picked.length > left) say(`파일은 이어서 내는 것과 새로 올리는 것을 합쳐 ${MAX_UPLOADS}개까지 제출할 수 있습니다.`, true);
    for (const file of picked.slice(0, Math.max(0, left))) {
      const entry = { local: (localIds += 1), name: String(file.name || "file"), size: file.size, state: "sending", uploadId: null, message: "" };
      memo.uploads.push(entry);
      uploadFile(file, courseId, "assignment_submission").then((view) => {
        track(entry, view);
        repaint();
      }, (error) => {
        entry.state = "failed";
        entry.message = uploadFailure(error);
        repaint();
      });
    }
    repaint();
  });

  const queueDraft = () => {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => {
      if (form.isConnected && area.value !== savedText) void saveText(false);
    }, DRAFT_DELAY);
  };

  async function saveText(explicit) {
    if (!data || !data.assignment.allowText || !accepting(data.phase)) return;
    if (drafting) {
      draftAgain = true;
      return;
    }
    clearTimeout(draftTimer);
    drafting = true;
    refresh();
    const text = area.value;
    draftNote.textContent = "임시 저장하는 중입니다.";
    try {
      const draft = await saveDraft(courseId, assignmentId, text);
      savedText = text;
      if (draftNote.isConnected) draftNote.textContent = draft && draft.savedAt ? `임시 저장했습니다 · ${koDateTime(draft.savedAt)}` : "임시 저장한 글을 지웠습니다.";
    } catch (error) {
      if (draftNote.isConnected) draftNote.textContent = `임시 저장하지 못했습니다. ${assignmentMessage(error)}`;
      if (!explicit) draftAgain = false;
    } finally {
      drafting = false;
      if (form.isConnected) refresh();
      if (draftAgain && form.isConnected) {
        draftAgain = false;
        queueDraft();
      }
    }
  }

  area.addEventListener("input", () => {
    memo.text = area.value;
    queueDraft();
  });

  async function send(api) {
    if (busy || !data) return;
    if (!accepting(data.phase)) return say("제출 기간이 아닙니다.", true);
    if (pendingUploads()) return say("첨부 파일 검사가 끝난 뒤 제출하세요.", true);
    const allowText = data.assignment.allowText;
    const text = allowText ? area.value : "";
    if (text.length > MAX_BODY) return say(`글은 ${MAX_BODY.toLocaleString("ko-KR")}자 이하입니다.`, true);
    const uploadIds = memo.uploads.filter((entry) => entry.state === "clean").map((entry) => entry.uploadId);
    // 다시 제출이면 지금 제출본에서 고른 파일을 `keepFileIds`로 함께 보낸다. 글만 받는 과제에는 보내지 않는다.
    const resubmit = data.submission.status === "submitted" || data.submission.status === "late";
    const keepFileIds = data.assignment.allowFile && resubmit ? keptIds() : undefined;
    const kept = keepFileIds ? keepFileIds.length : 0;
    if (!text.trim() && !uploadIds.length && !kept) return say(allowText ? "글을 쓰거나 파일을 첨부하세요." : "제출할 파일을 첨부하세요.", true);
    if (uploadIds.length + kept > MAX_UPLOADS) return say(`파일은 이어서 내는 것과 새로 올리는 것을 합쳐 ${MAX_UPLOADS}개까지 제출할 수 있습니다. 몇 개를 빼고 다시 제출하세요.`, true);
    clearTimeout(draftTimer);
    busy = true;
    refresh();
    paintFiles();
    submit.textContent = "제출하는 중…";
    say("ASTRA 서버에 제출하고 있습니다.");
    try {
      const receipt = await submitAssignment(courseId, assignmentId, {
        expectedVersion: data.submission.version,
        body: allowText ? text : null,
        uploadIds,
        keepFileIds,
      });
      memo.text = null;
      memo.uploads = [];
      memo.dropped.clear();
      memo.receipt = receipt;
      memo.receiptEntry = history.state;
      api.getState().toast = "과제를 제출했습니다";
      api.paintToast();
      repaint();
      assignmentDetails.load(key, courseId, assignmentId);
    } catch (error) {
      if (isConflict(error, "version_conflict") || isConflict(error, "deadline_passed") || missing(error)) {
        say(assignmentMessage(error), true);
        assignmentDetails.load(key, courseId, assignmentId);
      } else if (isConflict(error, "attachment_not_ready")) {
        // 검사가 덜 끝난 업로드이거나 이어받을 수 없는 파일(지금 제출본에 없는 파일)이다. 아무것도 저장되지 않았으니
        // 업로드 상태와 최신 제출본을 다시 읽는다. 입력한 글과 고른 파일은 그대로 둔다.
        say(assignmentMessage(error), true);
        recheck();
        assignmentDetails.load(key, courseId, assignmentId);
      } else if (submitUncertain(courseId, assignmentId)) {
        say("제출 결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 제출하세요. 이미 접수되었다면 같은 접수 결과를 돌려받습니다.", true);
      } else say(assignmentMessage(error), true);
    } finally {
      busy = false;
      if (form.isConnected) {
        submit.textContent = "제출";
        refresh();
        paintFiles();
      }
    }
  }

  // After 409 attachment_not_ready: read each upload's state again.
  function recheck() {
    for (const entry of memo.uploads) {
      if (!entry.uploadId || entry.state !== "clean") continue;
      getUpload(entry.uploadId).then((view) => {
        track(entry, view);
        repaint();
      }, (error) => {
        entry.state = missing(error) ? "gone" : "failed";
        entry.message = uploadFailure(error);
        repaint();
      });
    }
  }

  const controller = {
    form,
    say,
    send,
    saveText,
    update(next) {
      const first = !data;
      data = next;
      const info = next.assignment;
      const mine = next.submission;
      const submitted = mine.status === "submitted" || mine.status === "late";
      const dday = ddayAt(info.closeAt, next.serverTime);
      intro.replaceChildren(...[
        el("p", { class: "tiny", text: info.title }),
        el("p", { class: "due-line" }, phaseBadge(next.phase), el("span", { text: `마감 ${koDateTime(info.closeAt)}` }), dday ? el("span", { class: "dday", text: dday }) : null),
        next.phase === "late" ? el("p", { class: "note", text: "지각 제출 기간입니다. 지금 제출하면 지각으로 기록됩니다." }) : null,
        !accepting(next.phase) ? el("p", { class: "note", text: "제출 기간이 아니어서 제출할 수 없습니다. 입력한 글은 이 창을 닫기 전까지 남아 있습니다." }) : null,
        submitted ? el("p", { class: "tiny", text: `${koDateTime(mine.submittedAt)}에 ${mine.version}번째로 제출했습니다. 다시 제출하면 새 버전이 됩니다.${info.allowFile ? " 지금 제출본의 파일은 체크한 것만 이어지고, 새로 올린 파일이 더해집니다." : ""}` }) : null,
      ].filter(Boolean));
      if (first) {
        // The submission form of an assignment does not change while the dialog is open.
        if (!info.allowText) textBlock.remove();
        if (!info.allowFile) fileBlock.remove();
        // Typed text survives re-renders; otherwise start from the draft, then the current version.
        const start = memo.text != null ? memo.text : mine.draft && mine.draft.body != null ? mine.draft.body : submitted && mine.body ? mine.body : "";
        area.value = start;
        savedText = mine.draft && mine.draft.body != null ? mine.draft.body : null;
        if (mine.draft && mine.draft.savedAt) draftNote.textContent = `임시 저장 · ${koDateTime(mine.draft.savedAt)}`;
      }
      paintFiles();
      refresh();
    },
  };
  controllers.set(form, controller);
  return controller;
}

/** `srv-assignment-submit` (learning/actions.js). */
export function submitFromDialog(node, api) {
  const form = node.closest(".srv-submit");
  const controller = form && controllers.get(form);
  if (controller) void controller.send(api);
}

/** `srv-assignment-draft` (learning/actions.js): explicit draft save. */
export function draftFromDialog(node) {
  const form = node.closest(".srv-submit");
  const controller = form && controllers.get(form);
  if (controller) void controller.saveText(true);
}
