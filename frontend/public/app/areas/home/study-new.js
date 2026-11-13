import { el, icon } from "../../dom.js";
import { saveCoursePdf } from "../../shared/course-pdfs.js";
import { activeRecording, startRecording, onRecordingEvent } from "../../shared/course-recordings.js";
import { placeItem } from "../../shared/study-folders.js";
import { recordingHref, notifyStudyChanged } from "./study-files.js";
import { setRecordingError } from "./recording.js";

/**
 * The "새로 만들기" card that opens a menu: PDF·문서 업로드, 강의 녹음, and (at the course root) 새 폴더.
 * `folder` is the open user folder or null; new PDFs and recordings are placed in it.
 * `say(text, isError)` reports progress; `nameFolder()` starts the inline folder-name input.
 */
export function createNewMenu({ course, folder, say, nameFolder }) {
  const trigger = el("button", {
    type: "button", class: "study-new-card", "aria-haspopup": "menu", "aria-expanded": "false",
  }, icon("add"), el("span", { text: "새로 만들기" }));
  const item = (iconName, label, ...extra) => el("button", { type: "button", role: "menuitem", class: "study-new-item" },
    icon(iconName), el("span", { text: label }), ...extra);
  const uploadItem = item("upload", "PDF·문서 업로드");
  const recordItem = item("mic", "강의 녹음");
  const folderItem = folder ? null : item("folder", "새 폴더");
  const menu = el("div", { class: "study-new-menu", role: "menu", hidden: true },
    uploadItem, recordItem,
    folderItem ? el("div", { class: "study-new-sep", role: "separator" }) : null,
    folderItem,
  );
  const pdfInput = el("input", { type: "file", accept: "application/pdf,.pdf", hidden: true, "aria-label": "추가할 PDF 선택" });
  const wrap = el("div", { class: "study-new" }, trigger, menu, pdfInput);

  function onPointer(event) {
    if (!wrap.isConnected || !wrap.contains(event.target)) close();
  }
  function onKey(event) {
    if (event.key !== "Escape") return;
    close();
    trigger.focus();
  }
  function close() {
    menu.hidden = true;
    trigger.setAttribute("aria-expanded", "false");
    document.removeEventListener("pointerdown", onPointer);
    document.removeEventListener("keydown", onKey);
  }
  trigger.addEventListener("click", () => {
    if (!menu.hidden) return close();
    menu.hidden = false;
    trigger.setAttribute("aria-expanded", "true");
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    uploadItem.focus();
  });

  uploadItem.addEventListener("click", () => {
    close();
    pdfInput.click();
  });
  pdfInput.addEventListener("change", () => {
    const file = pdfInput.files?.[0];
    pdfInput.value = "";
    if (!file) return;
    const title = file.name.replace(/\.pdf$/i, "").trim() || file.name;
    trigger.disabled = true;
    say("PDF의 페이지와 텍스트를 분석하고 이 브라우저에 저장하고 있습니다.");
    saveCoursePdf({ file, courseId: course.id, title })
      .then((record) => {
        if (folder) placeItem(record.id, folder.id);
        say(record.status === "no-text"
          ? `"${title}"을 추가했습니다. 추출할 텍스트가 없어 원본으로만 확인할 수 있습니다.`
          : `"${title}"을 추가했습니다.`);
        notifyStudyChanged();
      })
      .catch((error) => say(error.message, true))
      .finally(() => { trigger.disabled = false; });
  });

  const openRecorder = () => {
    if (!wrap.isConnected) return;
    const link = el("a", { hidden: true, "data-go": recordingHref(course.id) });
    wrap.append(link);
    link.click();
  };
  recordItem.addEventListener("click", () => {
    close();
    say("");
    if (activeRecording()) return openRecorder();
    // The recorder screen saves the file later; place it in this folder once it exists.
    const off = onRecordingEvent((event) => {
      if (event.type === "saved") {
        if (folder && event.record.courseId === course.id) placeItem(event.record.id, folder.id);
        off();
      } else if (event.type === "error") off();
    });
    // Started inside the click: browsers open the mic prompt only for a user gesture.
    startRecording({ courseId: course.id, source: "mic" })
      .catch((error) => {
        off();
        setRecordingError(error.message);
      })
      .then(openRecorder);
  });

  folderItem?.addEventListener("click", () => {
    close();
    say("");
    nameFolder();
  });
  return wrap;
}
