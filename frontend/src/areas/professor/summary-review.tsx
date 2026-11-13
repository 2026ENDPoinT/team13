// The professor's review of a summary job's candidate before publishing: the same LessonViewer the students see after
// publishing (summary-lesson.ts builds both documents). The candidate (`…/summary-jobs/{id}/result`) carries no page
// image base, so the page images come from the file's preview session (`…/preview-sessions`), which also renews the
// image cookie when the images start failing (page-image-retry.ts).
import { useEffect, useState } from "react";
import { LessonViewer } from "../../shared/lesson-viewer.tsx";
import { summaryLessonDoc } from "../../shared/summary-lesson.ts";
import { awaitPreview, openPreview, previewFailure, previewMessage, type PreviewView } from "../../shared/preview-remote.tsx";
import type { RemotePdfRecord, ServerSummary } from "../../shared/course-pdfs-remote.ts";

type Images = { state: "loading" } | { state: "ready"; view: PreviewView } | { state: "none"; text: string };

export function SummaryReview({ record, jobId, result }: { record: RemotePdfRecord; jobId: number; result: ServerSummary }) {
  const [images, setImages] = useState<Images>({ state: "loading" });
  useEffect(() => {
    let live = true;
    setImages({ state: "loading" });
    awaitPreview(record.backendCourseId, record.fileId, { alive: () => live }).then((view) => {
      if (!live) return;
      if (view.state === "ready" && view.pageImageBase) setImages({ state: "ready", view });
      else setImages({ state: "none", text: view.state === "unsupported" ? "이 파일은 쪽 이미지를 만들 수 없습니다." : previewFailure(view.code) });
    }, (error: unknown) => {
      if (live) setImages({ state: "none", text: previewMessage(error) });
    });
    return () => {
      live = false;
    };
  }, [record.backendCourseId, record.fileId]);

  const view = images.state === "ready" ? images.view : null;
  const doc = summaryLessonDoc({
    id: `review-${record.backendCourseId}-${record.fileId}-${jobId}`,
    title: record.title || record.fileName,
    pageCount: (view && view.pageCount) || record.pageCount,
    summary: result,
    pageImageBase: view ? view.pageImageBase : null,
    // 쪽 이미지 쿠키가 끝나면 미리보기 세션을 다시 열어 쿠키를 새로 받는다.
    refreshImages: async () => {
      const next = await openPreview(record.backendCourseId, record.fileId);
      return next.state === "ready" ? next.pageImageBase : null;
    },
    note: "게시 전 요약 후보입니다. 게시하면 학생 학습실에 이 모습으로 보입니다. 검토가 필요한 쪽과 경고를 먼저 확인하세요.",
  });
  return (
    <div className="stack pf-summary-review">
      {images.state === "loading" ? <p className="pf-sub" role="status">쪽 이미지를 준비하는 중입니다. 준비되면 설명 옆에 원본 쪽이 보입니다.</p> : null}
      {images.state === "none" ? <p className="pf-sub" role="status">{`쪽 이미지 없이 요약만 보입니다. ${images.text}`}</p> : null}
      <LessonViewer doc={doc} />
    </div>
  );
}
