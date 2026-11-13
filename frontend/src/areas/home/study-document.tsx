// The study-room view of one of the student's own PDFs (`study_home?kj=&doc=doc-<id>`): the malware-scan state
// (asked again every `Retry-After` seconds until it is ready), the rejected state, then the document card with the
// PDF read in this browser. Rename, move between folders and delete go to `/api/me/study-room/documents/**`.
// A ready document also offers its AI page summary (study-doc-summary.tsx), above the document card.
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import type { Course } from "../../data.ts";
import { navigate } from "../../router.tsx";
import { toast } from "../../store.ts";
import { Button, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.ts";
import { ensureCoursePdfText, type PdfSummary } from "../../shared/course-pdfs.ts";
import {
  getStudyDocument, renameStudyDocument, deleteStudyDocument, studyDocumentMessage, toDocRecord, scanDelay, SCAN_CHECKS,
  type StudyDocRecord,
} from "../../shared/study-documents.ts";
import { listFolders, placeItem, unplaceItem, serverFolderOf, useStudyRoomStore } from "../../shared/study-folders.ts";
import { folderHref } from "./study-files.tsx";
import { DocSummarySection } from "./study-doc-summary.tsx";

export type ParsedDoc = StudyDocRecord & PdfSummary;

type View =
  | { kind: "loading"; text: string }
  | { kind: "missing" }
  | { kind: "failed"; message: string }
  | { kind: "scanning"; record: StudyDocRecord; stopped: boolean }
  | { kind: "rejected"; record: StudyDocRecord }
  | { kind: "ready"; record: ParsedDoc }
  | { kind: "unread"; record: StudyDocRecord; message: string };

export interface StudyDocumentProps {
  course: Course;
  docId: string;
  /** The document card (viewer, page text, original) for a parsed document. */
  renderCard: (record: ParsedDoc) => ReactNode;
  /** The original open/download controls, for a document whose text could not be read. */
  renderOriginal: (record: StudyDocRecord) => ReactNode;
}

export function StudyDocumentContent({ course, docId, renderCard, renderOriginal }: StudyDocumentProps) {
  const kj = course.id;
  const documentId = docId.slice("doc-".length);
  const [view, setView] = useState<View>({ kind: "loading", text: "PDF를 불러오는 중입니다" });
  const [round, setRound] = useState(0);
  const [message, setMessage] = useState<Notice>({ text: "", isError: false });

  useEffect(() => {
    let live = true;
    let timer = 0;
    let checks = 0;
    const show = (record: StudyDocRecord) => {
      if (record.state === "rejected") {
        setView({ kind: "rejected", record });
        return;
      }
      setView({ kind: "loading", text: "PDF 원문을 읽는 중입니다" });
      ensureCoursePdfText(record).then((filled) => {
        // ensureCoursePdfText assigned the extracted pages, excerpts and keywords onto the record.
        if (live) setView({ kind: "ready", record: filled as ParsedDoc });
      }, (error: unknown) => {
        if (!live) return;
        const cause = error instanceof Error && error.cause instanceof ApiError ? error.cause : null;
        // Scanned again after the list said ready (another tab's view was older): wait like a scanning one.
        if (cause && cause.code === "file_scanning") wait({ ...record, state: "scanning" }, error);
        else setView({ kind: "unread", record, message: error instanceof Error && error.message ? error.message : "원본 PDF를 열거나 다운로드해 확인해 주세요." });
      });
    };
    const read = async () => {
      const server = courseIdFor(kj);
      const doc = await getStudyDocument(documentId);
      // Another course's document id under this course's address: shown as not found.
      if (server == null || String(doc.courseId) !== String(server)) return null;
      return toDocRecord(doc, kj);
    };
    const wait = (record: StudyDocRecord, error?: unknown) => {
      setView({ kind: "scanning", record, stopped: checks >= SCAN_CHECKS });
      if (checks >= SCAN_CHECKS) return;
      timer = window.setTimeout(() => {
        checks += 1;
        read().then((next) => {
          if (!live) return;
          if (!next) setView({ kind: "missing" });
          else if (next.state === "scanning") wait(next);
          else show(next);
        }, () => {
          if (live) wait(record);
        });
      }, scanDelay(error) * 1000);
    };
    Promise.resolve(backendSettled() ? null : connectBackend()).then(read).then((record) => {
      if (!live) return;
      if (!record) setView({ kind: "missing" });
      else if (record.state === "scanning") wait(record);
      else show(record);
    }).catch((error: unknown) => {
      if (!live) return;
      if (error instanceof ApiError && (error.status === 404 || error.status === 410)) setView({ kind: "missing" });
      else setView({ kind: "failed", message: studyDocumentMessage(error) });
    });
    return () => {
      live = false;
      window.clearTimeout(timer);
    };
  }, [kj, documentId, round]);

  const reload = () => setRound((n) => n + 1);
  const record = view.kind === "scanning" || view.kind === "rejected" || view.kind === "ready" || view.kind === "unread" ? view.record : null;
  // A rename keeps the parsed text: only the title and the version change.
  const renamed = (next: StudyDocRecord) => {
    if (view.kind === "scanning" || view.kind === "rejected" || view.kind === "ready" || view.kind === "unread") {
      Object.assign(view.record, { title: next.title, version: next.version, updatedAt: next.updatedAt });
      setView({ ...view });
    }
  };

  let body: ReactNode;
  switch (view.kind) {
    case "loading":
      body = <LoadingState title={view.text} />;
      break;
    case "missing":
      body = <ErrorState title="PDF를 찾을 수 없습니다" body="이미 지웠거나 이 계정의 학습실에 있는 PDF가 아닙니다." />;
      break;
    case "failed":
      body = <ErrorState title="PDF를 불러오지 못했습니다" body={view.message} actions={<Button kind="ghost" onClick={reload}>다시 불러오기</Button>} />;
      break;
    case "scanning":
      body = view.stopped ? (
        <ErrorState
          title="바이러스 검사가 아직 끝나지 않았습니다" body="검사가 오래 걸리고 있습니다. 잠시 후 다시 확인해 주세요."
          actions={<Button kind="ghost" onClick={reload}>다시 확인</Button>}
        />
      ) : <LoadingState title="바이러스 검사 중입니다. 검사가 끝나면 자동으로 열립니다." />;
      break;
    case "rejected":
      body = <ErrorState title="열 수 없는 PDF입니다" body="검사에서 악성 코드가 발견되어 이 PDF는 열거나 내려받을 수 없습니다. 이 PDF를 지우고 원본 파일을 확인해 주세요." />;
      break;
    case "ready":
      body = (
        <>
          <DocSummarySection record={view.record} pageCount={view.record.pageCount} />
          {renderCard(view.record)}
        </>
      );
      break;
    case "unread":
      body = (
        <>
          <ErrorState title="PDF 원문을 읽지 못했습니다" body={view.message} />
          {renderOriginal(view.record)}
          <DocSummarySection record={view.record} pageCount={null} />
        </>
      );
      break;
  }
  return (
    <div className="course-study__content" aria-busy={view.kind === "loading" ? "true" : undefined}>
      {record ? <DocumentActions record={record} onRenamed={renamed} onStale={reload} say={setMessage} /> : null}
      {/* Outside the actions: a conflict reloads the document, and its sentence must stay while that happens. */}
      <p className={`course-study__notice${message.isError ? " is-error" : ""}`} role="status" aria-live="polite" hidden={!message.text}>{message.text}</p>
      {body}
    </div>
  );
}

interface Notice {
  text: string;
  isError: boolean;
}

/** Rename, move to another folder (or the course root) and delete, with the version the screen read. */
function DocumentActions({ record, onRenamed, onStale, say: setMessage }: {
  record: StudyDocRecord; onRenamed: (next: StudyDocRecord) => void; onStale: () => void; say: (notice: Notice) => void;
}) {
  useStudyRoomStore();
  const kj = record.courseId;
  const [mode, setMode] = useState<"" | "rename" | "move">("");
  const [busy, setBusy] = useState(false);
  const [armed, setArmed] = useState(false);
  const [title, setTitle] = useState(record.title);
  const where = serverFolderOf(kj, record.id);
  const folders = listFolders(kj).filter((folder) => !folder.local);
  const [target, setTarget] = useState(where || "");
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const failed = (error: unknown) => {
    if (!mounted.current) return;
    setBusy(false);
    setArmed(false);
    setMessage({ text: error instanceof Error ? error.message : studyDocumentMessage(error), isError: true });
    // Changed elsewhere (409 version_conflict): read it again so the next try sends the new version.
    const cause = error instanceof Error ? error.cause : null;
    if (cause instanceof ApiError && cause.code === "version_conflict") onStale();
  };

  const rename = (event: FormEvent) => {
    event.preventDefault();
    if (!title.trim() || title.trim() === record.title) {
      setMode("");
      return;
    }
    setBusy(true);
    renameStudyDocument(record, title).then((next) => {
      if (!mounted.current) return;
      onRenamed(next);
      setBusy(false);
      setMode("");
      setMessage({ text: `이름을 "${next.title}"(으)로 바꿨습니다.`, isError: false });
    }, failed);
  };

  const move = (event: FormEvent) => {
    event.preventDefault();
    if (target === (where || "")) {
      setMode("");
      return;
    }
    setBusy(true);
    const name = folders.find((folder) => folder.id === target)?.name;
    (target ? placeItem(record.id, target, { retryOnConflict: false }) : unplaceItem(kj, record.id)).then(() => {
      if (!mounted.current) return;
      setBusy(false);
      setMode("");
      setMessage({ text: name ? `${name} 폴더로 옮겼습니다.` : "과목 첫 화면으로 옮겼습니다.", isError: false });
    }, failed);
  };

  const remove = () => {
    if (!armed) {
      setArmed(true);
      return;
    }
    setBusy(true);
    deleteStudyDocument(record).then(() => {
      toast(`"${record.title}"을 삭제했습니다`);
      navigate(folderHref(kj, where || undefined));
    }, failed);
  };

  return (
    <div className="course-study__doc-actions">
      <p className="course-study__note" role="note">내 학습실에만 저장된 PDF입니다. 과목의 다른 사람에게 보이지 않습니다.</p>
      <div className="btn-row">
        <Button kind="secondary" disabled={busy} aria-expanded={mode === "rename" ? "true" : "false"} onClick={() => {
          setTitle(record.title);
          setMode(mode === "rename" ? "" : "rename");
        }}>이름 바꾸기</Button>
        <Button kind="secondary" disabled={busy} aria-expanded={mode === "move" ? "true" : "false"} onClick={() => {
          setTarget(where || "");
          setMode(mode === "move" ? "" : "move");
        }}>폴더 옮기기</Button>
        <Button kind={armed ? "primary" : "ghost"} disabled={busy} onClick={remove} onBlur={() => setArmed(false)}>{armed ? "한 번 더 누르면 삭제" : "삭제"}</Button>
      </div>
      {mode === "rename" ? (
        <form className="btn-row" onSubmit={rename}>
          <input type="text" maxLength={300} aria-label="새 PDF 이름" value={title} disabled={busy} onChange={(event) => setTitle(event.target.value)} />
          <Button kind="primary" type="submit" disabled={busy || !title.trim()}>저장</Button>
          <Button kind="ghost" disabled={busy} onClick={() => setMode("")}>취소</Button>
        </form>
      ) : null}
      {mode === "move" ? (
        <form className="btn-row" onSubmit={move}>
          <select aria-label="옮길 폴더" value={target} disabled={busy} onChange={(event) => setTarget(event.target.value)}>
            <option value="">폴더 없이 (과목 첫 화면)</option>
            {folders.map((folder) => <option key={folder.id} value={folder.id}>{folder.name}</option>)}
          </select>
          <Button kind="primary" type="submit" disabled={busy || where == null || target === (where || "")}>옮기기</Button>
          <Button kind="ghost" disabled={busy} onClick={() => setMode("")}>취소</Button>
        </form>
      ) : null}
    </div>
  );
}
