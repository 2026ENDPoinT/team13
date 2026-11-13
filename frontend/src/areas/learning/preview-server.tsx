// 미리보기 (`preview_form.acl?n=srv-<fileId>&kj=<course>`) of a server file: the backend preview
// session (converting → page images), the inline image view for PNG/JPEG, and the published file
// summary next to it when there is one. `anon` shows only the login strip.
import { useEffect, useState, type ReactNode } from "react";
import { EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { backendStatus, courseIdFor } from "../../shared/backend.ts";
import { remoteRecord } from "../../shared/course-pdfs-remote.ts";
import { serverPreview } from "../../shared/preview-remote.tsx";
import { downloadControl } from "../../shared/uploads-remote.tsx";
import { fileOverview, studyMessage, type FileOverview } from "../../shared/study-chat-remote.ts";
import { markdown } from "../../shared/page-summary.tsx";
import type { ScreenCtx } from "../../types.ts";

/** `srv-<fileId>` → numeric file id, else null. */
function serverFileIdOf(id: string | null | undefined): string | null {
  const match = /^srv-([1-9][0-9]{0,17})$/.exec(String(id || ""));
  return match ? match[1] : null;
}

/** Screen body for a server file preview, or null when this is not one (the sample preview stays). */
export function serverPreviewScreen(ctx: ScreenCtx): ReactNode {
  const fileId = serverFileIdOf(ctx.params && ctx.params.get("n"));
  const status = backendStatus();
  if (!fileId || status === "off") return null;
  if (status !== "ready") return <section className="srv-preview-page">{backendPanel({ text: "ASTRA 서버에 로그인하면 서버에 등록된 자료를 미리 볼 수 있습니다." })}</section>;
  const kj = ctx.params.get("kj") || ctx.state.courseId;
  const courseId = courseIdFor(kj);
  if (!courseId) {
    return <section className="srv-preview-page"><EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 있는 과목에서 자료를 열어 주세요." /></section>;
  }
  const record = remoteRecord(`srv-${fileId}`);
  const name = (record && (record.fileName || record.title)) || "문서";
  return (
    <section className="srv-preview-page">
      <h1>{record ? record.title || name : "문서 미리보기"}</h1>
      <div className="srv-preview-layout">
        {serverPreview({
          courseId, fileId, label: name,
          fallback: () => <div className="btn-row">{downloadControl(courseId, { fileId, name })}</div>,
        })}
        <OverviewAside key={`${courseId}:${fileId}`} courseId={courseId} fileId={fileId} published={record ? record.summaryPublished : null} />
      </div>
    </section>
  );
}

type OverviewView = { kind: "loading" } | { kind: "none" } | { kind: "ready"; overview: FileOverview } | { kind: "failed"; error: unknown };

/**
 * "파일 요약": the published summary's question and story (`…/summary/overview`). `published` is the list's
 * `summaryPublished` for this file: false shows "no summary" without asking; null (opened by link, list not read) asks.
 */
function OverviewAside({ courseId, fileId, published }: { courseId: number | string; fileId: number | string; published: boolean | null }) {
  const [view, setView] = useState<OverviewView>(published === false ? { kind: "none" } : { kind: "loading" });
  useEffect(() => {
    if (published === false) return;
    let live = true;
    fileOverview(courseId, fileId).then((overview) => {
      if (live) setView(overview ? { kind: "ready", overview } : { kind: "none" });
    }, (error: unknown) => {
      if (live) setView({ kind: "failed", error });
    });
    return () => {
      live = false;
    };
  }, [courseId, fileId, published]);
  let body: ReactNode;
  if (view.kind === "loading") body = <LoadingState title="파일 요약을 불러오는 중입니다" />;
  else if (view.kind === "none") body = <p className="note">교수님이 게시한 요약이 없습니다.</p>;
  else if (view.kind === "failed") body = <ErrorState title="파일 요약을 불러오지 못했습니다" body={studyMessage(view.error)} />;
  else {
    body = (
      <>
        <p className="tiny">교수님이 검토해 게시한 쪽별 요약에서 만든 개요입니다. 원문과 함께 확인하세요.</p>
        {markdown(view.overview.markdown || "")}
      </>
    );
  }
  return (
    <aside className="srv-overview" aria-label="파일 요약">
      <h2>파일 요약</h2>
      <div className="srv-overview__body">{body}</div>
    </aside>
  );
}

