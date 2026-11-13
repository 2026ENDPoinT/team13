import { useEffect, useRef, useState, type ReactNode } from "react";
import { courseById, type Course } from "../../data.ts";
import { hrefFor, find } from "../../manifest.ts";
import type { ScreenCtx } from "../../types.ts";
import { Link } from "../../router.tsx";
import { LoadingState, ErrorState } from "../../ui/kit.tsx";
import { listCoursePdfs, getCoursePdfBlob, ensureCoursePdfText, type CoursePdfRecord, type PdfPage, type PdfSummary } from "../../shared/course-pdfs.ts";
import { getPublishedSummary, summaryMessage, type RemotePdfRecord, type ServerSummary } from "../../shared/course-pdfs-remote.ts";
import { backendStatus, backendSettled, connect as connectBackend } from "../../shared/backend.ts";
import { summaryView } from "../../shared/page-summary.tsx";
import { createCoursePdfViewer } from "../../shared/document-viewer.tsx";
import { getFolder, folderOf, folderState, ownerGroup, useStudyRoomStore, type StudyFolder } from "../../shared/study-folders.ts";
import { serverStudyCourse, studyRoomMessage, refreshCourseView } from "../../shared/study-room-remote.ts";
import { courseFiles, folderHref } from "./study-files.tsx";
import { createNewMenu } from "./study-new.tsx";
import { StudyDocumentContent } from "./study-document.tsx";
import { isStudyDoc, isStudyDocId, type StudyDocRecord } from "../../shared/study-documents.ts";
import { serverPreview } from "../../shared/preview-remote.tsx";
import { studySummaryHref } from "./study-server.tsx";
import { wallFormat } from "../../shared/home-remote.ts";

const dateFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric" });

/** A PDF with its page text and excerpts (local records always; server records and own documents after `ensureCoursePdfText`). */
type ParsedPdf = (CoursePdfRecord | StudyDocRecord) & PdfSummary;

/** A published summary as the server sends it: the page-image base comes with it. */
interface PublishedSummary extends ServerSummary {
  pageImageBase?: string | null;
}

/** Keeps the object URLs this screen made; revokes them a minute after it closes. */
type KeepUrl = (url: string) => void;

function UploadedDate({ value }: { value: string | undefined }) {
  const text = dateFormat.format(value);
  return text ? <time dateTime={value}>{text}</time> : <span>등록일 정보 없음</span>;
}

function PageDetails({ page }: { page: PdfPage }) {
  const hasText = Boolean(page.text.trim());
  return (
    <details className="course-study__page">
      <summary>
        <span className="course-study__page-number">{`${page.number}쪽`}</span>
        <span>{page.heading || (hasText ? "추출된 원문" : "추출된 텍스트 없음")}</span>
      </summary>
      <p className={hasText ? "course-study__page-text" : "course-study__note"}>
        {hasText ? page.text : "이 페이지에서 텍스트를 추출하지 못했습니다. 빈 페이지이거나 스캔 이미지일 수 있습니다. 내용이 있는 이미지라면 OCR(문자인식)이 필요합니다. 원본 PDF에서 확인해 주세요."}
      </p>
    </details>
  );
}

function OriginalControls({ record, keepUrl }: { record: CoursePdfRecord | StudyDocRecord; keepUrl: KeepUrl }) {
  const onServer = Boolean(record.remote) || isStudyDoc(record);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState({ text: "", alert: false });
  const [url, setUrl] = useState("");
  const open = useRef<HTMLAnchorElement>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (url) open.current?.focus();
  }, [url]);

  const prepare = async () => {
    setBusy(true);
    setStatus({ text: "원본 PDF를 불러오는 중입니다.", alert: false });
    try {
      const blob = await getCoursePdfBlob(record.id);
      if (!mounted.current) return;
      if (!blob) {
        setStatus({
          text: onServer
            ? "서버에서 원본 PDF를 찾을 수 없습니다. 자료가 비공개로 바뀌었을 수 있습니다."
            : "저장된 원본 PDF를 찾을 수 없습니다. 브라우저의 사이트 데이터가 삭제되었을 수 있습니다.",
          alert: true,
        });
        return;
      }
      const next = URL.createObjectURL(blob);
      keepUrl(next);
      setUrl(next);
      setStatus({ text: "원본이 준비되었습니다. 새 탭에서 열거나 다운로드할 수 있습니다.", alert: false });
    } catch (error) {
      if (!mounted.current) return;
      setStatus({
        text: isStudyDoc(record) && error instanceof Error && error.message
          ? error.message
          : onServer
            ? "ASTRA 서버에서 원본 PDF를 받지 못했습니다. 잠시 후 다시 시도해 주세요."
            : "이 브라우저의 저장소에서 원본 PDF를 읽지 못했습니다. 사이트 데이터 접근 설정을 확인해 주세요.",
        alert: true,
      });
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  return (
    <div className="course-study__original" aria-busy={busy ? "true" : undefined}>
      <div className="course-study__actions">
        {url ? (
          <>
            <a ref={open} className="btn btn-secondary" href={url} target="_blank" rel="noopener noreferrer" aria-label={`${record.title} 원본 열기 (새 탭)`}>원본 열기 (새 탭)</a>
            <a className="btn btn-secondary" href={url} download={record.fileName} aria-label={`${record.title} PDF 다운로드`}>PDF 다운로드</a>
          </>
        ) : (
          <button type="button" className="btn btn-secondary" disabled={busy} aria-label={`${record.title} 원본 PDF 열기 및 다운로드 준비`} onClick={() => void prepare()}>
            원본 PDF 열기 / 다운로드
          </button>
        )}
      </div>
      <p className="course-study__file-status" role={status.alert ? "alert" : "status"} aria-live="polite">{status.text}</p>
    </div>
  );
}

function AllPages({ record }: { record: ParsedPdf }) {
  const [loaded, setLoaded] = useState(false);
  const pages: PdfPage[] = record.pages;
  return (
    <details className="course-study__all-pages" onToggle={(event) => { if (event.currentTarget.open) setLoaded(true); }}>
      <summary>{`페이지별 원문 보기 · ${record.pageCount}쪽`}</summary>
      <div className="course-study__pages">
        {loaded ? pages.map((page) => <PageDetails key={page.number} page={page} />) : null}
        {loaded && !pages.length ? <p className="course-study__note">추출된 페이지 정보가 없습니다. 원본 PDF를 확인해 주세요.</p> : null}
      </div>
    </details>
  );
}

function DocumentCard({ record, keepUrl }: { record: ParsedPdf; keepUrl: KeepUrl }) {
  const noText = record.status === "no-text";
  const overview: { page: number; text: string }[] = record.overview;
  const keywords: string[] = record.keywords;
  return (
    <article className="course-study__document" aria-label={record.title}>
      <header className="course-study__document-head">
        <h2>{record.title}</h2>
        <p className="course-study__filename">{record.fileName}</p>
        <div className="course-study__metadata">
          <span className="course-study__format">PDF</span>
          <span>{`${record.pageCount}쪽`}</span>
          <span>등록일 <UploadedDate value={record.uploadedAt} /></span>
        </div>
        {/* 요약 화면은 학생에게 보이는 요약 게시본이 있을 때(`summaryPublished`)만 연다. */}
        {record.remote && record.summaryPublished ? <Link href={studySummaryHref(record.courseId, record.id)} className="btn btn-secondary">자료 요약</Link> : null}
        {record.remote ? <Link href={hrefFor(find("65_file_preview"), { n: record.id, kj: record.courseId })} className="btn btn-secondary">미리보기 화면으로 열기</Link> : null}
      </header>
      {noText ? <p className="course-study__ocr" role="note">텍스트를 추출하지 못한 PDF입니다. 스캔 이미지라면 OCR(문자인식)이 필요하며, 이 화면에서는 OCR을 지원하지 않습니다. 아래에서 원본을 열거나 다운로드할 수 있습니다.</p> : null}
      <section className="course-study__overview" aria-label={`${record.title} 원문 개요`}>
        <h3>원문에서 뽑은 개요</h3>
        <p className="course-study__note">PDF의 문장을 페이지와 함께 발췌했습니다. AI가 생성한 요약이 아닙니다.</p>
        {overview.length ? (
          <ul className="course-study__points">
            {overview.map((point, index) => (
              <li key={index}>
                <span className="course-study__excerpt">{point.text}</span>
                <span className="course-study__reference">{`${point.page}쪽`}</span>
              </li>
            ))}
          </ul>
        ) : <p className="course-study__note">{noText ? "발췌할 수 있는 텍스트가 없습니다." : "발췌된 개요가 없습니다. 페이지별 원문을 펼쳐 확인해 주세요."}</p>}
      </section>
      {keywords.length ? (
        <section className="course-study__keywords" aria-label={`${record.title} 주요 단어`}>
          <h3>주요 단어</h3>
          <ul>{keywords.map((keyword, index) => <li key={index}>{keyword}</li>)}</ul>
        </section>
      ) : null}
      {/* Server PDFs show the backend's page images (preview session, ADR-0033); local ones draw with pdf.js. */}
      {record.remote
        ? serverPreview({ courseId: record.backendCourseId, fileId: record.fileId, label: record.title || record.fileName })
        : createCoursePdfViewer(record)}
      <AllPages record={record} />
      <OriginalControls record={record} keepUrl={keepUrl} />
    </article>
  );
}

export function renderCourseStudy(ctx: ScreenCtx): ReactNode {
  const params = ctx.params;
  return <CourseStudy key={`${params.get("kj")}|${params.get("f")}|${params.get("doc")}`} ctx={ctx} />;
}

function CourseStudy({ ctx }: { ctx: ScreenCtx }) {
  useStudyRoomStore();
  // A folder made in another tab or device is not in the cached view: it is read again once before "not found".
  const [rechecked, setRechecked] = useState(false);
  const courseId = ctx.params.get("kj") || "";
  const course = courseById(courseId);
  const docId = ctx.params.get("doc");
  const back = docId
    ? <Link href={folderHref(courseId, folderOf(docId))} className="btn btn-secondary">폴더로</Link>
    : <Link href={hrefFor(find("21_regular_register_list"))} className="btn btn-secondary">수강 과목으로</Link>;
  if (!courseId || course.missing || course.id !== courseId) {
    return (
      <section className="course-study" data-area="home">
        <h1>학습실</h1>
        <ErrorState title="과목을 찾을 수 없습니다" body="수강 과목 목록에서 과목을 다시 선택해 주세요." actions={back} />
      </section>
    );
  }
  const folderId = ctx.params.get("f");
  // Server folders are known once the backend answered and the course's study room was read.
  const folders = folderId || docId ? folderState(course.id) : { state: "ready" as const, error: null };
  if (folderId && (!backendSettled() || folders.state !== "ready")) {
    return (
      <section className="course-study" data-area="home">
        <h1>{`${course.cardTitle || course.ko} 학습실`}</h1>
        {folders.state === "failed"
          ? <ErrorState title="폴더를 불러오지 못했습니다" body={studyRoomMessage(folders.error)} actions={<Link href={folderHref(courseId)} className="btn btn-secondary">학습실로</Link>} />
          : <LoadingState title="폴더를 불러오는 중입니다" />}
      </section>
    );
  }
  const folder = folderId ? getFolder(folderId, course.id) : null;
  const server = serverStudyCourse(course.id);
  if (folderId && !folder && server != null && !rechecked) {
    return (
      <section className="course-study" data-area="home">
        <h1>{`${course.cardTitle || course.ko} 학습실`}</h1>
        <FolderRecheck courseId={server} onDone={() => setRechecked(true)} />
      </section>
    );
  }
  if (folderId && !folder) {
    return (
      <section className="course-study" data-area="home">
        <h1>{`${course.cardTitle || course.ko} 학습실`}</h1>
        <ErrorState
          title="폴더를 찾을 수 없습니다" body={server != null ? "삭제되었거나 이 과목의 폴더가 아닙니다." : "삭제되었거나 다른 브라우저에서 만든 폴더입니다."}
          actions={<Link href={folderHref(courseId)} className="btn btn-secondary">학습실로</Link>}
        />
      </section>
    );
  }
  return (
    <section className="course-study" data-area="home">
      <header className="course-study__header">
        <div className="course-study__heading">
          <h1>{`${course.cardTitle || course.ko} 학습실`}</h1>
          {folder ? <p className="course-study__crumb"><Link href={folderHref(courseId)}>전체</Link>{` / ${folder.name}${!folder.local || server == null ? "" : ownerGroup(folder.ownerUserId) === "before-login" ? " (로그인 전에 이 브라우저에 저장)" : ownerGroup(folder.ownerUserId) === "other" ? " (다른 계정으로 저장)" : " (이 브라우저에만 저장)"}`}</p> : null}
        </div>
        {docId ? back : null}
      </header>
      {!docId
        ? <FolderFiles course={course} folder={folder} />
        : isStudyDocId(docId) ? <StudyDocument course={course} docId={docId} /> : <DocumentContent course={course} docId={docId} />}
    </section>
  );
}

/** Loading state while the course's study room is read again (a fresh load, not one started before). */
function FolderRecheck({ courseId, onDone }: { courseId: number | string; onDone: () => void }) {
  const done = useRef(onDone);
  done.current = onDone;
  useEffect(() => {
    void refreshCourseView(courseId).finally(() => done.current());
  }, [courseId]);
  return <LoadingState title="폴더를 불러오는 중입니다" />;
}

function FolderFiles({ course, folder }: { course: Course; folder: StudyFolder | null }) {
  return courseFiles(course, folder, (api) => createNewMenu({ course, folder, ...api }));
}

type Content =
  | { kind: "loading"; text: string }
  | { kind: "missing" }
  | { kind: "failed"; remote: boolean }
  | { kind: "doc"; record: ParsedPdf }
  | { kind: "unread"; record: RemotePdfRecord; message: string }
  | { kind: "remote"; record: RemotePdfRecord & PdfSummary };

/** Keeps the object URLs a screen made; revokes them a minute after it closes. */
function useKeptUrls(): KeepUrl {
  const urls = useRef(new Set<string>());
  useEffect(() => {
    const kept = urls.current;
    return () => {
      // Give a just-started download or new PDF tab time to consume the URL.
      window.setTimeout(() => {
        for (const activeUrl of kept) URL.revokeObjectURL(activeUrl);
        kept.clear();
      }, 60000);
    };
  }, []);
  return (url: string) => {
    urls.current.add(url);
  };
}

/** One of the student's own study-room PDFs: its own AI summary (study-doc-summary.tsx), no course summary or preview session. */
function StudyDocument({ course, docId }: { course: Course; docId: string }) {
  const keepUrl = useKeptUrls();
  return (
    <StudyDocumentContent
      course={course} docId={docId}
      renderCard={(record) => <DocumentCard record={record} keepUrl={keepUrl} />}
      renderOriginal={(record) => <OriginalControls record={record} keepUrl={keepUrl} />}
    />
  );
}

function DocumentContent({ course, docId }: { course: Course; docId: string }) {
  const courseId = course.id;
  const [content, setContent] = useState<Content>({ kind: "loading", text: "PDF 자료를 불러오는 중입니다" });
  const keepUrl = useKeptUrls();

  useEffect(() => {
    let live = true;
    const remoteMode = backendStatus() === "ready";
    // A server PDF link opened directly waits for the first backend probe.
    const ready = docId.startsWith("srv-") && !backendSettled() ? connectBackend() : null;
    Promise.resolve(ready).then(() => listCoursePdfs(courseId)).then((records) => {
      if (!live) return;
      const record = records.find((item) => item.id === docId && item.courseId === courseId);
      if (!record) {
        setContent({ kind: "missing" });
        return;
      }
      if (!record.remote) {
        setContent({ kind: "doc", record });
        return;
      }
      setContent({ kind: "loading", text: "PDF 원문을 읽는 중입니다" });
      ensureCoursePdfText(record).then((filled) => {
        // ensureCoursePdfText assigned the extracted pages, excerpts and keywords onto the record.
        const parsed = filled as RemotePdfRecord & PdfSummary;
        if (live) setContent({ kind: "remote", record: parsed });
      }, (error: unknown) => {
        const message = error instanceof Error ? error.message : "";
        if (live) setContent({ kind: "unread", record, message: message || "원본 PDF를 열거나 다운로드해 확인해 주세요." });
      });
    }).catch(() => {
      if (live) setContent({ kind: "failed", remote: remoteMode });
    });
    return () => {
      live = false;
    };
  }, [courseId, docId]);

  let body: ReactNode;
  switch (content.kind) {
    case "loading":
      body = <LoadingState title={content.text} />;
      break;
    case "missing":
      body = (
        <ErrorState
          title="PDF를 찾을 수 없습니다"
          body={docId.startsWith("srv-") ? "ASTRA 서버에 로그인했는지, 이 과목의 자료를 볼 수 있는 계정인지 확인해 주세요." : "삭제되었거나 다른 브라우저에서 등록된 자료입니다."}
          actions={<Link href={folderHref(courseId)} className="btn btn-secondary">폴더로</Link>}
        />
      );
      break;
    case "failed":
      body = content.remote
        ? <ErrorState title="PDF 자료를 불러오지 못했습니다" body="ASTRA 서버에서 자료 목록을 받지 못했습니다. 잠시 후 다시 시도해 주세요." />
        : <ErrorState title="PDF 자료를 불러오지 못했습니다" body="이 브라우저의 저장소에 접근할 수 없습니다. 브라우저의 사이트 데이터 접근 설정을 확인해 주세요." />;
      break;
    case "doc":
      body = <DocumentCard record={content.record} keepUrl={keepUrl} />;
      break;
    case "remote":
      body = <><DocumentCard record={content.record} keepUrl={keepUrl} /><SummarySection record={content.record} /></>;
      break;
    case "unread":
      body = (
        <>
          <ErrorState title="PDF 원문을 읽지 못했습니다" body={content.message} />
          <OriginalControls record={content.record} keepUrl={keepUrl} />
          <SummarySection record={content.record} />
        </>
      );
      break;
  }
  return <div className="course-study__content" aria-busy={content.kind === "loading" ? "true" : undefined}>{body}</div>;
}

const publishedFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

type SummaryState = { kind: "loading" } | { kind: "none" } | { kind: "failed"; message: string } | { kind: "ready"; published: PublishedSummary };

/** Published page summary of a server PDF, below the document card. Read only when the list says one is published. */
function SummarySection({ record }: { record: RemotePdfRecord }) {
  const [summary, setSummary] = useState<SummaryState>(record.summaryPublished ? { kind: "loading" } : { kind: "none" });
  useEffect(() => {
    if (!record.summaryPublished) return;
    let live = true;
    getPublishedSummary(record).then((published) => {
      if (live) setSummary(published ? { kind: "ready", published } : { kind: "none" });
    }).catch((error: unknown) => {
      if (live) setSummary({ kind: "failed", message: summaryMessage(error) });
    });
    return () => {
      live = false;
    };
  }, [record]);

  let body: ReactNode;
  if (summary.kind === "loading") body = <LoadingState title="쪽별 요약을 불러오는 중입니다" />;
  else if (summary.kind === "none") body = <p className="course-study__note">게시된 요약이 없습니다.</p>;
  else if (summary.kind === "failed") body = <ErrorState title="쪽별 요약을 불러오지 못했습니다" body={summary.message} />;
  else {
    const published = summary.published;
    const at = publishedFormat.format(published.publishedAt);
    body = (
      <>
        <p className="course-study__note">{`${at ? `${at} 게시 · ` : ""}교수님이 검토해 게시한 AI 요약입니다. 원문과 함께 확인해 주세요.`}</p>
        {summaryView(published, {
          pageImageBase: published.pageImageBase,
          refreshImages: async () => {
            const fresh: PublishedSummary | null = await getPublishedSummary(record);
            return fresh ? fresh.pageImageBase ?? null : null;
          },
        })}
      </>
    );
  }
  return (
    <section className="course-study__summary" aria-label={`${record.title} 쪽별 요약`}>
      <h2>쪽별 요약</h2>
      <div className="course-study__summary-body" aria-busy={summary.kind === "loading" ? "true" : undefined}>{body}</div>
    </section>
  );
}
