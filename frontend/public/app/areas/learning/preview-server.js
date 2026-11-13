// 미리보기 (`preview_form.acl?n=srv-<fileId>&kj=<course>`) of a server file: the backend preview
// session (converting → page images), the inline image view for PNG/JPEG, and the published file
// summary next to it when there is one. `anon` shows only the login strip.
import { el } from "../../dom.js";
import { emptyState, errorState, loadingState } from "../../ui/components.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { backendStatus, courseIdFor } from "../../shared/backend.js";
import { remoteRecord } from "../../shared/course-pdfs-remote.js";
import { serverPreview } from "../../shared/preview-remote.js";
import { downloadControl } from "../../shared/uploads-remote.js";
import { fileOverview, studyMessage } from "../../shared/study-chat-remote.js";
import { markdown } from "../../shared/page-summary.js";

// replaceChildren() would turn a null child into the text "null"; skip absent parts.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false));
}

/** `srv-<fileId>` → numeric file id, else null. */
export function serverFileIdOf(id) {
  const match = /^srv-([1-9][0-9]{0,17})$/.exec(String(id || ""));
  return match ? match[1] : null;
}

/** Screen body for a server file preview, or null when this is not one (the sample preview stays). */
export function serverPreviewScreen(ctx) {
  const fileId = serverFileIdOf(ctx.params && ctx.params.get("n"));
  const status = backendStatus();
  if (!fileId || status === "off") return null;
  if (status !== "ready") return el("section", { class: "srv-preview-page" }, backendPanel({ text: "ASTRA 서버에 로그인하면 서버에 등록된 자료를 미리 볼 수 있습니다." }));
  const kj = ctx.params.get("kj") || ctx.state.courseId;
  const courseId = courseIdFor(kj);
  if (!courseId) return el("section", { class: "srv-preview-page" }, emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 있는 과목에서 자료를 열어 주세요."));
  const record = remoteRecord(`srv-${fileId}`);
  const name = (record && (record.fileName || record.title)) || "문서";
  return el("section", { class: "srv-preview-page" },
    el("h1", { text: record ? record.title || name : "문서 미리보기" }),
    el("div", { class: "srv-preview-layout" },
      serverPreview({
        courseId, fileId, label: name,
        fallback: () => {
          const { button, note } = downloadControl(courseId, { fileId, name });
          return el("div", { class: "btn-row" }, button, note);
        },
      }),
      overviewAside(courseId, fileId, record ? record.summaryPublished : null),
    ),
  );
}

/**
 * "파일 요약": the published summary's question and story (`…/summary/overview`). `published` is the list's
 * `summaryPublished` for this file: false shows "no summary" without asking; null (opened by link, list not read) asks.
 */
export function overviewAside(courseId, fileId, published = null) {
  const body = el("div", { class: "srv-overview__body" }, loadingState("파일 요약을 불러오는 중입니다"));
  const aside = el("aside", { class: "srv-overview", "aria-label": "파일 요약" }, el("h2", { text: "파일 요약" }), body);
  if (published === false) {
    fill(body, el("p", { class: "note", text: "교수님이 게시한 요약이 없습니다." }));
    return aside;
  }
  queueMicrotask(() => {
    if (!aside.isConnected) return;
    fileOverview(courseId, fileId).then((overview) => {
      if (!aside.isConnected) return;
      if (!overview) {
        fill(body, el("p", { class: "note", text: "교수님이 게시한 요약이 없습니다." }));
        return;
      }
      fill(body,
        el("p", { class: "tiny", text: "교수님이 검토해 게시한 쪽별 요약에서 만든 개요입니다. 원문과 함께 확인하세요." }),
        markdown(overview.markdown || ""),
      );
    }, (error) => {
      if (aside.isConnected) fill(body, errorState("파일 요약을 불러오지 못했습니다", studyMessage(error)));
    });
  });
  return aside;
}
