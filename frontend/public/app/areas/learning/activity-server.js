// 학생 활동 상세의 백엔드 부분 중 영상·과제·시험·설문 상세 밖의 것. 서버 자료 상세
// (`?m=lecture_material&n=srv-<activityId>`, `GET …/activities/{id}`의 설명과 첨부, 내려받기, 미리보기, 학습실),
// 활동 ID만 있는 예전 연결 주소(`?m=report|test|survey&act=<activityId>`, 활동 상세가 주는 과제·시험·설문 ID로
// 그 상세로 주소를 바꾼다), 연결된 과목에서 샘플 활동 ID로 열린 상세·플레이어·미리보기의
// 찾을 수 없음 화면이다. 서버 글은 텍스트 노드로만 넣는다.
import { el } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime } from "../../shared/home-remote.js";
import { lectureDetails, detailKey, ACTIVITY_PHASE } from "../../shared/lectures-remote.js";
import { materialMessage } from "../../shared/materials-remote.js";
import { rememberMaterials } from "../../shared/course-pdfs-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { resolveActivityHref } from "../../shared/course-activities-remote.js";

const missing = (error) => error instanceof ApiError && error.status === 404;
const listHref = (kj) => hrefFor(find("50_activity_all"), { kj });
const TYPE_NAME = { report: "과제", test: "시험", survey: "설문" };

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

/** 상세·연결 화면의 공통 관문: 대신 보일 노드, 또는 진행해도 되면 null. */
function gate(root, kj, paintOff, loginText) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: loginText });
  if (status === "off") {
    if (!backendSettled()) {
      queueMicrotask(() => connectBackend().then((next) => {
        if (root.isConnected && next.status === "off") paintOff();
      }));
      return loadingState("ASTRA 서버에 연결하는 중입니다");
    }
    return emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요.");
  }
  if (!courseIdFor(kj)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 활동만 볼 수 있습니다.");
  return null;
}

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

// ---------- 자료 상세 ----------

function fileRow(kj, courseId, file) {
  const preview = hrefFor(find("65_file_preview"), { n: `srv-${file.fileId}`, kj });
  const { button, note } = downloadControl(courseId, { fileId: file.fileId, name: file.name });
  // 요약 보기는 학생에게 보이는 요약 게시본이 있을 때(`summaryPublished`)만 둔다.
  const study = file.pdf ? [
    file.summaryPublished ? go(hrefFor(find("study_summary"), { kj, doc: `srv-${file.fileId}` }), "요약 보기", { class: "btn btn-ghost" }) : null,
    go(hrefFor(find("study_home"), { kj, doc: `srv-${file.fileId}` }), "학습실에서 열기", { class: "btn btn-ghost" }),
  ].filter(Boolean) : [];
  return el("article", { class: "file-row srv-material-file", "data-file": String(file.fileId) },
    el("div", {},
      el("b", { text: file.name }),
      el("p", { class: "tiny", text: [String(file.extension || "").toUpperCase(), sizeText(file.sizeBytes)].filter(Boolean).join(" · ") }),
      note,
    ),
    el("div", { class: "btn-row" }, go(preview, "미리보기", { class: "btn btn-secondary" }), button, ...study),
  );
}

function materialBody(data, kj, courseId, parts, staleError) {
  const files = Array.isArray(data.files) ? data.files : [];
  return el("div", { class: "act-body" },
    parts.typeLine("material"),
    el("h1", { text: data.title }),
    el("p", { class: "tiny", text: [`${data.weekNo}주차`, data.published ? "" : "비공개", data.phase === "not_open" ? ACTIVITY_PHASE.not_open : ""].filter(Boolean).join(" · ") }),
    staleError ? el("p", { class: "err", role: "alert", text: materialMessage(staleError) }) : null,
    parts.metaTable([
      ["공개일", data.openAt ? koDateTime(data.openAt) : "공개 즉시"],
      ["첨부", files.length ? `${files.length}개` : "없음"],
    ]),
    prose(data.description),
    el("h2", { text: "첨부파일" }),
    ...(files.length ? files.map((file) => fileRow(kj, courseId, file)) : [el("p", { class: "tiny", text: "첨부 파일이 없습니다." })]),
    el("div", { class: "btn-row" }, go(hrefFor(find("46_room_files_list"), { kj }), "파일모음", { class: "btn btn-secondary" }), go(listHref(kj), "학습활동 목록", { class: "btn btn-ghost" })),
  );
}

/** `screen.serverMaterial` 자료 상세(백엔드 모드). `parts`는 learning.js 도우미다. */
export function remoteMaterialDetail(screen, ctx, parts) {
  const kj = screen.course;
  const activityId = screen.serverMaterial;
  const main = el("div", { class: "act-main" });
  const root = el("section", { class: "bleed act-layout is-solo is-open", "data-item-open": `srv-${activityId}` }, main);
  const blocked = gate(root, kj, () => main.replaceChildren(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")),
    "ASTRA 서버에 로그인하면 이 과목의 자료를 보고 내려받을 수 있습니다.");
  if (blocked) {
    main.append(el("div", { class: "act-body" }, parts.typeLine("material"), blocked));
    return root;
  }
  const courseId = courseIdFor(kj);
  const key = detailKey(courseId, activityId);
  const load = () => lectureDetails.load(key, courseId, activityId);
  let remembered = null;
  const paint = () => {
    const entry = lectureDetails.peek(key);
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main.replaceChildren(loadingState("자료를 불러오는 중입니다"));
      else if (missing(entry.error)) main.replaceChildren(emptyState("자료를 찾을 수 없습니다", "아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다.", go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" })));
      else main.replaceChildren(errorState("자료를 불러오지 못했습니다", materialMessage(entry.error), retry(load)));
      return;
    }
    if (data.type !== "lecture_material") {
      main.replaceChildren(emptyState("자료 활동이 아닙니다", "학습활동 목록에서 다시 선택해 보세요.", go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" })));
      return;
    }
    // 미리보기·학습실이 같은 파일 레코드를 쓴다.
    if (remembered !== data) {
      remembered = data;
      rememberMaterials([data], kj, courseId);
    }
    main.replaceChildren(materialBody(data, kj, courseId, parts, entry.error));
  };
  paint();
  queueMicrotask(() => {
    if (!root.isConnected) return;
    lectureDetails.watch(root, key, paint, load);
    if (freshVisit("material-detail", key) === "entry" || !lectureDetails.peek(key)) load();
  });
  return root;
}

// ---------- 활동 ID 연결 ----------

/**
 * `screen.serverLink` `{ type, activityId }`(활동 ID만 있는 예전 연결 주소). 과제·시험·설문 ID를 찾으면 주소를 그 상세로
 * 바꾸고(뒤로 가기가 이 화면에 머물지 않도록 history 항목을 바꾼다), 못 찾으면 찾을 수 없음이다.
 */
export function remoteActivityLink(screen, ctx, parts) {
  const kj = screen.course;
  const { type, activityId } = screen.serverLink;
  const main = el("div", { class: "act-main" });
  const root = el("section", { class: "bleed act-layout is-solo is-open" }, main);
  const body = el("div", { class: "act-body", "aria-live": "polite" }, parts.typeLine(type));
  main.append(body);
  const show = (...nodes) => body.replaceChildren(parts.typeLine(type), ...nodes);
  const blocked = gate(root, kj, () => show(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")),
    `ASTRA 서버에 로그인하면 이 ${TYPE_NAME[type]}을 열 수 있습니다.`);
  if (blocked) {
    show(blocked);
    return root;
  }
  const run = () => {
    show(loadingState(`${TYPE_NAME[type]}을 찾는 중입니다`));
    resolveActivityHref(kj, type, activityId).then((href) => {
      if (!root.isConnected) return;
      if (!href) {
        show(emptyState(`${TYPE_NAME[type]}을 찾을 수 없습니다`, "아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다.", go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" })));
        return;
      }
      history.replaceState({}, "", href);
      window.dispatchEvent(new PopStateEvent("popstate"));
    }, (error) => {
      if (!root.isConnected) return;
      show(errorState(`${TYPE_NAME[type]}을 찾지 못했습니다`, materialMessage(error), retry(run)));
    });
  };
  queueMicrotask(() => {
    if (root.isConnected) run();
  });
  return root;
}

// ---------- 샘플 ID ----------

/**
 * 백엔드 모드에서 샘플 활동·파일 ID로 열린 화면이면 대신 보일 본문: `anon`이면 로그인 안내, `ready`이고 서버에 있는 과목이면
 * "찾을 수 없음", 서버에 없는 과목이면 공지·Q&A와 같은 "이 과목은 ASTRA 서버에 없습니다". 샘플 내용을 서버 것처럼 보이지
 * 않는다. mock 모드(`off`)이거나 과목이 없으면 null(샘플 화면을 그대로 그림).
 */
export function sampleInServerCourse(kj, what = "활동") {
  const status = backendStatus();
  if (!kj || status === "off") return null;
  const body = status === "anon"
    ? backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 활동을 서버에서 불러옵니다." })
    : !courseIdFor(kj)
      ? emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 활동만 볼 수 있습니다.")
      : emptyState(`${what}을 찾을 수 없습니다`, "이 과목은 ASTRA 서버의 활동만 보입니다. 목록에서 다시 선택해 보세요.",
        go(listHref(kj), "학습활동 목록", { class: "btn btn-secondary" }));
  return el("section", { class: "bleed act-layout is-solo is-open", "data-sample-blocked": "" }, el("div", { class: "act-main" }, el("div", { class: "act-body" }, body)));
}
