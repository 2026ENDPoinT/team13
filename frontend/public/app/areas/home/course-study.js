import { el } from "../../dom.js";
import { go, btn, loadingState, errorState } from "../../ui/components.js";
import { courseById } from "../../data.js";
import { hrefFor, find } from "../../manifest.js";
import { listCoursePdfs, getCoursePdfBlob, ensureCoursePdfText } from "../../shared/course-pdfs.js";
import { getPublishedSummary, summaryMessage } from "../../shared/course-pdfs-remote.js";
import { backendStatus, backendSettled, connect as connectBackend } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { summaryView } from "../../shared/page-summary.js";
import { createCoursePdfViewer } from "../../shared/document-viewer.js";
import { getFolder, folderOf } from "../../shared/study-folders.js";
import { courseFiles, folderHref } from "./study-files.js";
import { createNewMenu } from "./study-new.js";
import { serverPreview } from "../../shared/preview-remote.js";
import { studySummaryHref } from "./study-server.js";
import { wallFormat } from "../../shared/home-remote.js";

const dateFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric" });

function uploadedDate(value) {
  const text = dateFormat.format(value);
  return text
    ? el("time", { datetime: value, text })
    : el("span", { text: "등록일 정보 없음" });
}

function pageDetails(page) {
  const hasText = Boolean(page.text.trim());
  return el("details", { class: "course-study__page" },
    el("summary", {},
      el("span", { class: "course-study__page-number", text: `${page.number}쪽` }),
      el("span", { text: page.heading || (hasText ? "추출된 원문" : "추출된 텍스트 없음") }),
    ),
    el("p", {
      class: hasText ? "course-study__page-text" : "course-study__note",
      text: hasText ? page.text : "이 페이지에서 텍스트를 추출하지 못했습니다. 빈 페이지이거나 스캔 이미지일 수 있습니다. 내용이 있는 이미지라면 OCR(문자인식)이 필요합니다. 원본 PDF에서 확인해 주세요.",
    }),
  );
}

function originalControls(record, root, keepUrl) {
  const prepare = btn("원본 PDF 열기 / 다운로드", { "aria-label": `${record.title} 원본 PDF 열기 및 다운로드 준비` });
  const status = el("p", { class: "course-study__file-status", role: "status", "aria-live": "polite" });
  const actions = el("div", { class: "course-study__actions" }, prepare);
  const controls = el("div", { class: "course-study__original" }, actions, status);

  prepare.addEventListener("click", async () => {
    prepare.disabled = true;
    status.textContent = "원본 PDF를 불러오는 중입니다.";
    controls.setAttribute("aria-busy", "true");
    try {
      const blob = await getCoursePdfBlob(record.id);
      if (!root.isConnected) return;
      if (!blob) {
        status.setAttribute("role", "alert");
        status.textContent = record.remote
          ? "서버에서 원본 PDF를 찾을 수 없습니다. 자료가 비공개로 바뀌었을 수 있습니다."
          : "저장된 원본 PDF를 찾을 수 없습니다. 브라우저의 사이트 데이터가 삭제되었을 수 있습니다.";
        return;
      }
      const url = URL.createObjectURL(blob);
      keepUrl(url);
      const open = el("a", {
        class: "btn btn-secondary", href: url, target: "_blank", rel: "noopener noreferrer",
        text: "원본 열기 (새 탭)", "aria-label": `${record.title} 원본 열기 (새 탭)`,
      });
      const download = el("a", {
        class: "btn btn-secondary", href: url, download: record.fileName,
        text: "PDF 다운로드", "aria-label": `${record.title} PDF 다운로드`,
      });
      actions.replaceChildren(open, download);
      status.textContent = "원본이 준비되었습니다. 새 탭에서 열거나 다운로드할 수 있습니다.";
      open.focus();
    } catch {
      if (!root.isConnected) return;
      status.setAttribute("role", "alert");
      status.textContent = record.remote
        ? "ASTRA 서버에서 원본 PDF를 받지 못했습니다. 잠시 후 다시 시도해 주세요."
        : "이 브라우저의 저장소에서 원본 PDF를 읽지 못했습니다. 사이트 데이터 접근 설정을 확인해 주세요.";
    } finally {
      if (root.isConnected) controls.removeAttribute("aria-busy");
      if (prepare.isConnected) prepare.disabled = false;
    }
  });
  return controls;
}

function documentCard(record, root, keepUrl) {
  const noText = record.status === "no-text";
  const overview = el("section", { class: "course-study__overview", "aria-label": `${record.title} 원문 개요` },
    el("h3", { text: "원문에서 뽑은 개요" }),
    el("p", { class: "course-study__note", text: "PDF의 문장을 페이지와 함께 발췌했습니다. AI가 생성한 요약이 아닙니다." }),
    record.overview.length
      ? el("ul", { class: "course-study__points" }, ...record.overview.map((point) => el("li", {},
        el("span", { class: "course-study__excerpt", text: point.text }),
        el("span", { class: "course-study__reference", text: `${point.page}쪽` }),
      )))
      : el("p", { class: "course-study__note", text: noText ? "발췌할 수 있는 텍스트가 없습니다." : "발췌된 개요가 없습니다. 페이지별 원문을 펼쳐 확인해 주세요." }),
  );
  const pageList = el("div", { class: "course-study__pages" });
  const pages = el("details", { class: "course-study__all-pages" },
    el("summary", { text: `페이지별 원문 보기 · ${record.pageCount}쪽` }), pageList,
  );
  let pagesLoaded = false;
  pages.addEventListener("toggle", () => {
    if (!pages.open || pagesLoaded) return;
    pagesLoaded = true;
    pageList.replaceChildren(...record.pages.map(pageDetails));
    if (!record.pages.length) pageList.append(el("p", { class: "course-study__note", text: "추출된 페이지 정보가 없습니다. 원본 PDF를 확인해 주세요." }));
  });

  return el("article", { class: "course-study__document", "aria-label": record.title },
    el("header", { class: "course-study__document-head" },
      el("h2", { text: record.title }),
      el("p", { class: "course-study__filename", text: record.fileName }),
      el("div", { class: "course-study__metadata" },
        el("span", { class: "course-study__format", text: "PDF" }),
        el("span", { text: `${record.pageCount}쪽` }),
        el("span", {}, "등록일 ", uploadedDate(record.uploadedAt)),
      ),
      // 요약 화면은 학생에게 보이는 요약 게시본이 있을 때(`summaryPublished`)만 연다.
      record.remote && record.summaryPublished ? go(studySummaryHref(record.courseId, record.id), "자료 요약과 테크뇽 대화", { class: "btn btn-secondary" }) : null,
      record.remote ? go(hrefFor(find("65_file_preview"), { n: record.id, kj: record.courseId }), "미리보기 화면으로 열기", { class: "btn btn-secondary" }) : null,
    ),
    noText ? el("p", { class: "course-study__ocr", role: "note", text: "텍스트를 추출하지 못한 PDF입니다. 스캔 이미지라면 OCR(문자인식)이 필요하며, 이 화면에서는 OCR을 지원하지 않습니다. 아래에서 원본을 열거나 다운로드할 수 있습니다." }) : null,
    overview,
    record.keywords.length ? el("section", { class: "course-study__keywords", "aria-label": `${record.title} 주요 단어` },
      el("h3", { text: "주요 단어" }),
      el("ul", {}, ...record.keywords.map((keyword) => el("li", { text: keyword }))),
    ) : null,
    // Server PDFs show the backend's page images (preview session, ADR-0033); local ones draw with pdf.js.
    record.remote ? serverPreview({ courseId: record.backendCourseId, fileId: record.fileId, label: record.title || record.fileName }) : createCoursePdfViewer(record),
    pages,
    originalControls(record, root, keepUrl),
  );
}

export function renderCourseStudy(ctx) {
  const courseId = ctx.params.get("kj");
  const course = courseById(courseId);
  const docId = ctx.params.get("doc");
  const back = docId
    ? go(folderHref(courseId, folderOf(docId)), "폴더로", { class: "btn btn-secondary" })
    : go(hrefFor(find("21_regular_register_list")), "수강 과목으로", { class: "btn btn-secondary" });
  const root = el("section", { class: "course-study" });
  if (!courseId || course.missing || course.id !== courseId) {
    root.append(el("h1", { text: "학습실" }), errorState("과목을 찾을 수 없습니다", "수강 과목 목록에서 과목을 다시 선택해 주세요.", back));
    return root;
  }

  const folderId = ctx.params.get("f");
  const folder = folderId ? getFolder(folderId, course.id) : null;
  if (folderId && !folder) {
    root.append(el("h1", { text: `${course.cardTitle || course.ko} 학습실` }),
      errorState("폴더를 찾을 수 없습니다", "삭제되었거나 다른 브라우저에서 만든 폴더입니다.", go(folderHref(courseId), "학습실로", { class: "btn btn-secondary" })));
    return root;
  }
  const header = el("header", { class: "course-study__header" },
    el("div", { class: "course-study__heading" },
      el("h1", { text: `${course.cardTitle || course.ko} 학습실` }),
      folder ? el("p", { class: "course-study__crumb" }, go(folderHref(courseId), "전체"), ` / ${folder.name}`) : null,
    ),
    docId ? back : null,
  );
  const remoteMode = backendStatus() === "ready";
  const panel = backendPanel();
  if (!docId) {
    root.append(header, panel, courseFiles(course, folder, (api) => createNewMenu({ course, folder, ...api })));
    return root;
  }

  const content = el("div", { class: "course-study__content", "aria-busy": "true" }, loadingState("PDF 자료를 불러오는 중입니다"));
  root.append(header, panel, content);

  const urls = new Set();
  let observer;
  function keepUrl(url) {
    urls.add(url);
    if (observer) return;
    observer = new MutationObserver(() => {
      if (root.isConnected) return;
      observer.disconnect();
      // Give a just-started download or new PDF tab time to consume the URL.
      window.setTimeout(() => {
        for (const activeUrl of urls) URL.revokeObjectURL(activeUrl);
        urls.clear();
      }, 60000);
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  const done = (...nodes) => {
    content.replaceChildren(...nodes);
    content.removeAttribute("aria-busy");
  };
  // A server PDF link opened directly waits for the first backend probe.
  const ready = docId.startsWith("srv-") && !backendSettled() ? connectBackend() : null;
  // The caller attaches this synchronous return before promise callbacks run.
  Promise.resolve(ready).then(() => listCoursePdfs(courseId)).then((records) => {
    if (!root.isConnected) return;
    const record = records.find((item) => item.id === docId && item.courseId === courseId);
    if (!record) {
      done(errorState("PDF를 찾을 수 없습니다",
        docId.startsWith("srv-") ? "ASTRA 서버에 로그인했는지, 이 과목의 자료를 볼 수 있는 계정인지 확인해 주세요." : "삭제되었거나 다른 브라우저에서 등록된 자료입니다.",
        go(folderHref(courseId), "폴더로", { class: "btn btn-secondary" })));
      return;
    }
    if (!record.remote) {
      done(documentCard(record, root, keepUrl));
      return;
    }
    content.replaceChildren(loadingState("PDF 원문을 읽는 중입니다"));
    ensureCoursePdfText(record).then(() => {
      if (!root.isConnected) return;
      done(documentCard(record, root, keepUrl), summarySection(record));
    }, (error) => {
      if (!root.isConnected) return;
      done(
        errorState("PDF 원문을 읽지 못했습니다", error.message || "원본 PDF를 열거나 다운로드해 확인해 주세요."),
        originalControls(record, root, keepUrl),
        summarySection(record),
      );
    });
  }).catch(() => {
    if (!root.isConnected) return;
    done(remoteMode
      ? errorState("PDF 자료를 불러오지 못했습니다", "ASTRA 서버에서 자료 목록을 받지 못했습니다. 잠시 후 다시 시도해 주세요.")
      : errorState("PDF 자료를 불러오지 못했습니다", "이 브라우저의 저장소에 접근할 수 없습니다. 브라우저의 사이트 데이터 접근 설정을 확인해 주세요."));
  });
  return root;
}

const publishedFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

/** Published page summary of a server PDF, below the document card. Read only when the list says one is published. */
function summarySection(record) {
  const body = el("div", { class: "course-study__summary-body", "aria-busy": "true" }, loadingState("쪽별 요약을 불러오는 중입니다"));
  const section = el("section", { class: "course-study__summary", "aria-label": `${record.title} 쪽별 요약` },
    el("h2", { text: "쪽별 요약" }),
    body,
  );
  if (!record.summaryPublished) {
    body.removeAttribute("aria-busy");
    body.replaceChildren(el("p", { class: "course-study__note", text: "게시된 요약이 없습니다." }));
    return section;
  }
  Promise.resolve().then(() => getPublishedSummary(record)).then((published) => {
    if (!section.isConnected) return;
    body.removeAttribute("aria-busy");
    if (!published) {
      body.replaceChildren(el("p", { class: "course-study__note", text: "게시된 요약이 없습니다." }));
      return;
    }
    const at = publishedFormat.format(published.publishedAt);
    body.replaceChildren(
      el("p", { class: "course-study__note", text: `${at ? `${at} 게시 · ` : ""}교수님이 검토해 게시한 AI 요약입니다. 원문과 함께 확인해 주세요.` }),
      summaryView(published, {
        pageImageBase: published.pageImageBase,
        refreshImages: async () => {
          const fresh = await getPublishedSummary(record);
          return fresh ? fresh.pageImageBase : null;
        },
      }),
    );
  }).catch((error) => {
    if (!section.isConnected) return;
    body.removeAttribute("aria-busy");
    body.replaceChildren(errorState("쪽별 요약을 불러오지 못했습니다", summaryMessage(error)));
  });
  return section;
}
