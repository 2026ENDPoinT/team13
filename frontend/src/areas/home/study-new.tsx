import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import { navigate } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { saveCoursePdf } from "../../shared/course-pdfs.ts";
import { activeRecording, startRecording } from "../../shared/course-recordings.ts";
import { backendStatus } from "../../shared/backend.ts";
import { placeItem, type StudyFolder } from "../../shared/study-folders.ts";
import { recordingHref, notifyStudyChanged, type CreatorApi } from "./study-files.tsx";
import { setRecordingError } from "./recording.tsx";

export interface NewMenuOptions extends CreatorApi {
  course: Course;
  folder: StudyFolder | null;
}

/**
 * The "새로 만들기" card that opens a menu: PDF·문서 업로드, 강의 녹음, and (at the course root) 새 폴더.
 * `folder` is the open user folder or null; new PDFs and recordings are placed in it.
 * `say(text, isError)` reports progress; `nameFolder()` starts the inline folder-name input.
 */
export function createNewMenu({ course, folder, say, nameFolder }: NewMenuOptions): ReactNode {
  return <NewMenu course={course} folder={folder} say={say} nameFolder={nameFolder} />;
}

function NewMenu({ course, folder, say, nameFolder }: NewMenuOptions) {
  const [open, setOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const uploadItem = useRef<HTMLButtonElement>(null);
  const pdfInput = useRef<HTMLInputElement>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    uploadItem.current?.focus();
    const onPointer = (event: PointerEvent) => {
      if (!wrap.current || !wrap.current.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      trigger.current?.focus();
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const onPdfChosen = () => {
    const input = pdfInput.current;
    const file = input?.files?.[0];
    if (input) input.value = "";
    if (!file) return;
    const title = file.name.replace(/\.pdf$/i, "").trim() || file.name;
    setUploading(true);
    say(backendStatus() === "ready" ? "PDF를 ASTRA 서버에 올리고 있습니다." : "PDF의 페이지와 텍스트를 분석하고 이 브라우저에 저장하고 있습니다.");
    saveCoursePdf({ file, courseId: course.id, title })
      .then(async (record) => {
        const added = record.status === "no-text"
          ? `"${title}"을 추가했습니다. 추출할 텍스트가 없어 원본으로만 확인할 수 있습니다.`
          : `"${title}"을 추가했습니다.`;
        try {
          if (folder) await placeItem(record.id, folder.id);
          say(added);
        } catch (error) {
          say(`${added} 폴더에 넣지 못해 과목 폴더에 두었습니다. ${error instanceof Error ? error.message : String(error)}`, true);
        }
        notifyStudyChanged();
      })
      .catch((error: unknown) => say(error instanceof Error ? error.message : String(error), true))
      .finally(() => setUploading(false));
  };

  const openRecorder = () => {
    if (mounted.current) navigate(recordingHref(course.id));
  };
  const onRecord = () => {
    setOpen(false);
    say("");
    if (activeRecording()) {
      openRecorder();
      return;
    }
    // Started inside the click: browsers open the mic prompt only for a user gesture. The recording is saved into this folder.
    startRecording({ courseId: course.id, source: "mic", folderId: folder ? folder.id : null })
      .catch((error: unknown) => {
        setRecordingError(error instanceof Error ? error.message : String(error));
      })
      .then(openRecorder);
  };

  return (
    <div ref={wrap} className="study-new">
      <button
        ref={trigger} type="button" className="study-new-card" aria-haspopup="menu" aria-expanded={open ? "true" : "false"} disabled={uploading}
        onClick={() => setOpen(!open)}
      >
        <Icon name="add" /><span>새로 만들기</span>
      </button>
      <div className="study-new-menu" role="menu" hidden={!open}>
        <button
          ref={uploadItem} type="button" role="menuitem" className="study-new-item"
          onClick={() => {
            setOpen(false);
            pdfInput.current?.click();
          }}
        >
          <Icon name="upload" /><span>PDF·문서 업로드</span>
        </button>
        <button type="button" role="menuitem" className="study-new-item" onClick={onRecord}>
          <Icon name="mic" /><span>강의 녹음</span>
        </button>
        {folder ? null : <div className="study-new-sep" role="separator" />}
        {folder ? null : (
          <button
            type="button" role="menuitem" className="study-new-item"
            onClick={() => {
              setOpen(false);
              say("");
              nameFolder();
            }}
          >
            <Icon name="folder" /><span>새 폴더</span>
          </button>
        )}
      </div>
      <input ref={pdfInput} type="file" accept="application/pdf,.pdf" hidden aria-label="추가할 PDF 선택" onChange={onPdfChosen} />
    </div>
  );
}
