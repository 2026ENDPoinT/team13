// 서버 첨부 고르기. 새 파일은 `uploadFile`(과목 밖 용도는 `courseId` null)로 올리고 검사가 끝날 때까지
// 기다린다. 이미 붙은 파일(`existing`)은 빼기·되돌리기로 남길 것만 고른다. 고른 상태는 부르는 쪽의 `memo`
// 객체에 두어 다시 그리기와 409 재조회 뒤에도 남는다. 저장 본문은 새 글이면 `uploadIds()`, 수정이면
// `attachments()`(`{ keep: [fileId], add: [uploadId] }`)다. 과목이 있거나 과목 밖 용도(공지·자료실·문의·쪽지
// 첨부)면 파일함에서도 고를 수 있다.
import { el, icon } from "../dom.js";
import { btn } from "../ui/components.js";
import { ApiError } from "./api.js";
import { uploadFile, awaitScan, getUpload, discardUpload, uploadFailure, sizeText } from "./uploads-remote.js";
import { fileboxAttach } from "./filebox-attach.js";

export const UPLOAD_STATE = {
  sending: "올리는 중",
  quarantined: "검사 중",
  clean: "첨부 준비됨",
  rejected: "서버 검사를 통과하지 못했습니다. 이 파일은 첨부할 수 없습니다.",
  slow: "검사가 오래 걸리고 있습니다. 빼고 다시 올려 보세요.",
  gone: "더 이상 쓸 수 없는 업로드입니다. 다시 올리세요.",
};

const DEAD = ["rejected", "failed", "gone"];
const scanState = (state) => (state === "clean" || state === "quarantined" || state === "rejected" ? state : "gone");
const missing = (error) => error instanceof ApiError && error.status === 404;
let localIds = 0;

/** 첨부 memo를 처음 상태로 비운다(저장이 끝난 뒤). 올린 업로드는 이미 붙었으므로 지우지 않는다. */
export function resetAttachments(memo) {
  memo.uploads = [];
  memo.keep = null;
}

/**
 * `{ memo, courseId, purpose, max, existing, hint, onChange }` → `{ node, pending(), uploadIds(),
 * keepIds(), attachments(), changed(), recheck() }`. `existing`는 `[{ fileId, name, sizeBytes }]`.
 */
export function attachPicker({ memo, courseId = null, purpose, max = 5, existing = [], hint = "", onChange = () => {} }) {
  if (!Array.isArray(memo.uploads)) memo.uploads = [];
  const current = Array.isArray(existing) ? existing.filter((file) => file && file.fileId != null) : [];
  // After `resetAttachments` the same picker starts again from `existing`.
  const kept = () => {
    if (!Array.isArray(memo.uploads)) memo.uploads = [];
    if (!(memo.keep instanceof Set)) memo.keep = new Set(current.map((file) => file.fileId));
    return memo.keep;
  };
  kept();
  const list = el("div", { class: "stack srv-attach-list" });
  const input = el("input", { type: "file", class: "file-input", multiple: true, "aria-label": "첨부 파일 고르기" });
  const note = el("span", { class: "tiny", role: "status", "aria-live": "polite" });
  const live = () => memo.uploads.filter((entry) => !DEAD.includes(entry.state)).length;
  const room = () => Math.max(0, max - kept().size - live());
  const repaint = () => { if (memo.repaint) memo.repaint(); };

  const paint = () => {
    const keptRows = current.map((file) => {
      const on = kept().has(file.fileId);
      const toggle = btn(on ? "빼기" : "되돌리기", { "aria-label": `${file.name} ${on ? "빼기" : "되돌리기"}` }, "ghost");
      toggle.addEventListener("click", () => {
        if (on) kept().delete(file.fileId);
        else if (room() > 0) kept().add(file.fileId);
        else note.textContent = `파일은 ${max}개까지 첨부할 수 있습니다.`;
        repaint();
      });
      return el("div", { class: `file-row${on ? "" : " is-removed"}` }, icon("file"),
        el("span", {}, el("b", { text: file.name || "파일" }), el("span", { class: "tiny", text: [sizeText(file.sizeBytes), on ? "붙어 있음" : "저장하면 빠짐"].filter(Boolean).join(" · ") })),
        toggle);
    });
    const added = memo.uploads.map((entry) => {
      const remove = btn("빼기", { "aria-label": `${entry.name} 빼기` }, "ghost");
      remove.addEventListener("click", () => {
        memo.uploads = memo.uploads.filter((item) => item !== entry);
        // 붙이지 않은 업로드는 스스로 만료되지만 먼저 지워 둔다.
        if (entry.uploadId) discardUpload(entry.uploadId);
        repaint();
      });
      const state = entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state] || UPLOAD_STATE.gone;
      return el("div", { class: "file-row" }, icon("file"),
        el("span", {}, el("b", { text: entry.name }), el("span", { class: "tiny", text: [sizeText(entry.size), state].filter(Boolean).join(" · ") })),
        remove);
    });
    list.replaceChildren(...keptRows, ...added);
    if (fromBox) fromBox.setDisabled(room() <= 0);
    onChange();
  };
  memo.repaint = paint;

  const track = (entry, view) => {
    entry.uploadId = view.uploadId;
    entry.state = scanState(view.state);
    if (entry.state !== "quarantined") return;
    awaitScan(view, {
      alive: () => memo.uploads.includes(entry),
      onState: (next) => { entry.state = scanState(next.state); repaint(); },
    }).then((last) => {
      if (last && last.state === "quarantined") entry.state = "slow";
      repaint();
    }, (error) => {
      entry.state = "failed";
      entry.message = uploadFailure(error);
      repaint();
    });
  };

  input.addEventListener("change", (event) => {
    // 앱 전체 파일 listener가 이름을 샘플 상태에 남기지 않게 여기서 멈춘다.
    event.stopPropagation();
    const picked = [...(input.files || [])];
    input.value = "";
    const free = room();
    note.textContent = picked.length > free ? `파일은 ${max}개까지 첨부할 수 있습니다.` : "";
    for (const file of picked.slice(0, free)) {
      const entry = { local: (localIds += 1), name: String(file.name || "file"), size: file.size, state: "sending", uploadId: null, message: "" };
      memo.uploads.push(entry);
      uploadFile(file, courseId, purpose).then((view) => {
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

  // 파일함 다시 쓰기(`/api/uploads/from-filebox`): 과목 업로드와 과목 밖 용도(`OUTSIDE_COURSE_PURPOSES`)만 된다.
  // 과목이 없는 다른 용도면 `fileboxAttach`가 null이라 버튼이 없다.
  const fromBox = fileboxAttach({
    courseId,
    purpose,
    limit: max,
    room,
    onAttach: (views) => {
      for (const view of views) {
        const entry = { local: (localIds += 1), name: String(view.name || "file"), size: view.sizeBytes, state: "sending", uploadId: null, message: "" };
        memo.uploads.push(entry);
        track(entry, view);
      }
      note.textContent = "";
      repaint();
    },
  });

  const node = el("div", { class: "srv-attach" },
    el("div", { class: "compose-files" },
      el("label", { class: "btn btn-secondary" }, icon("file"), "파일 첨부", input),
      fromBox ? fromBox.node : null,
      el("span", { class: "tiny", text: hint || `${max}개까지, 50MB 이하. 검사를 통과한 파일만 첨부됩니다.` }),
      note,
    ),
    list,
  );
  paint();

  return {
    node,
    pending: () => memo.uploads.some((entry) => entry.state === "sending" || entry.state === "quarantined"),
    uploadIds: () => memo.uploads.filter((entry) => entry.state === "clean").map((entry) => entry.uploadId),
    keepIds: () => current.filter((file) => kept().has(file.fileId)).map((file) => file.fileId),
    attachments() {
      return { keep: this.keepIds(), add: this.uploadIds() };
    },
    /** 남길 파일이 처음과 다르거나 새 파일이 있으면 true. */
    changed() {
      return this.uploadIds().length > 0 || current.some((file) => !kept().has(file.fileId));
    },
    /** 409 `attachment_not_ready` 뒤: 업로드마다 상태를 다시 읽는다. */
    recheck() {
      for (const entry of memo.uploads) {
        if (!entry.uploadId || entry.state !== "clean") continue;
        getUpload(entry.uploadId).then((view) => { track(entry, view); repaint(); }, (error) => {
          entry.state = missing(error) ? "gone" : "failed";
          entry.message = uploadFailure(error);
          repaint();
        });
      }
    },
  };
}
