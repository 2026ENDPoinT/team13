// 서버 첨부 폼의 "파일함에서 첨부". 내 파일함을 폴더별로 보여 주고(`listings`, 이름순, "더 보기"로 다음 쪽),
// 고른 파일만 `uploadsFromFilebox`로 그 과목·용도의 업로드로 만든다. 과목 밖 용도(`OUTSIDE_COURSE_PURPOSES`:
// 알림마당 공지·자료실, 1:1 문의, 쪽지 첨부)는 과목 없이 만든다. 받은 업로드 view는 부르는 쪽이
// 보통 업로드 결과와 같은 첨부 목록에 넣는다(`quarantined`는 `awaitScan`, 빼면 `discardUpload`).
// 거부되면 창 안에 이유를 보이고 아무것도 넘기지 않는다. 백엔드가 `ready`가 아니면 버튼이 없다.
import { el } from "../dom.js";
import { btn, badge, emptyState, loadingState, errorState } from "../ui/components.js";
import { ApiError } from "./api.js";
import { backendStatus } from "./backend.js";
import { retryLater } from "./remote-cache.js";
import { sizeText } from "./uploads-remote.js";
import { listings, listKey, SCAN_STATE, uploadsFromFilebox, fileboxMessage, OUTSIDE_COURSE_PURPOSES } from "./filebox-remote.js";

const SORT = "name";
const ORDER = "asc";
let dialogIds = 0;

const keyOf = (folder) => listKey(folder, SORT, ORDER, "");
const load = (folder, previous = null) => listings.load(keyOf(folder), folder, SORT, ORDER, "", previous);
const uncertain = (error) => error instanceof ApiError && [0, 502, 503, 504].includes(error.status);

/** from-filebox가 거부한 이유. `course`가 false면 과목 밖 용도의 요청이다. 그 밖의 실패는 `fileboxMessage`를 쓴다. */
export function attachRefusal(error, { course = true } = {}) {
  if (!(error instanceof ApiError)) return fileboxMessage(error);
  switch (error.code) {
    case "folder_not_allowed": return "폴더는 첨부할 수 없습니다. 파일만 고르세요.";
    case "not_ready": return "검사를 마치지 않았거나 검사를 통과하지 못한 파일이 있습니다. 잠시 후 다시 고르세요.";
    case "not_active": return "휴지통에 넣은 파일이 섞여 있습니다. 목록을 새로 불러왔습니다.";
    case "extension_not_allowed": return "이곳에 첨부할 수 없는 형식의 파일이 있습니다.";
    case "too_large": return "이곳에 첨부하기에는 너무 큰 파일이 있습니다.";
    case "idempotency_conflict": return "같은 요청이 다른 내용으로 처리 중입니다. 잠시 후 다시 시도하세요.";
    case "purpose_not_allowed": return "이 과목에서 고른 용도로는 파일함 파일을 쓸 수 없습니다.";
    default: break;
  }
  switch (error.status) {
    case 400: return error.detail || "요청을 처리하지 못했습니다. 다시 고르세요.";
    case 401: return "ASTRA 서버 로그인이 필요합니다.";
    case 404: return course
      ? "찾을 수 없는 파일이 있거나 이 과목에 첨부할 권한이 없습니다. 목록을 새로 불러왔습니다."
      : "찾을 수 없는 파일이 있거나 이곳에 파일을 첨부할 권한이 없습니다. 목록을 새로 불러왔습니다.";
    case 413: return "이곳에 첨부하기에는 너무 큰 파일이 있습니다.";
    case 422: return "이곳에 첨부할 수 없는 파일이 있습니다.";
    case 429: return retryLater(error);
    default: break;
  }
  if (uncertain(error)) return "첨부 결과를 확인하지 못했습니다. 고른 그대로 다시 첨부하면 한 번만 붙습니다.";
  return fileboxMessage(error);
}

/**
 * "파일함에서 첨부" 버튼과 고르기 창. `room()`은 지금 더 붙일 수 있는 수, `limit`은 안내에 쓰는 최대 수다.
 * `onAttach(uploads)`는 `[{ uploadId, itemId, state, name, sizeBytes, expiresAt }]`를 받는다. `courseId`가
 * null이면 과목 밖 용도일 때만 버튼을 만든다. `ready`가 아니거나 버튼을 만들 수 없으면 `null`.
 * 돌려주는 `{ node, setDisabled(on) }`의 `node`를 파일 입력 옆에 넣는다.
 */
export function fileboxAttach({ courseId, purpose, limit, room, onAttach }) {
  if (backendStatus() !== "ready") return null;
  const course = courseId != null;
  if (!course && !OUTSIDE_COURSE_PURPOSES.has(purpose)) return null;
  const id = `fba-${(dialogIds += 1)}`;
  const open = btn("파일함에서 첨부", { "aria-haspopup": "dialog" }, "secondary");
  let folder = null;
  let busy = false;
  const selected = new Map(); // itemId -> item (폴더를 옮겨도 고른 것은 남는다)

  const title = el("h2", { id: `${id}-title`, text: "파일함에서 첨부" });
  const close = btn("닫기", {}, "ghost");
  const hint = el("p", { id: `${id}-hint`, class: "tiny" });
  const crumbs = el("nav", { class: "fba-crumbs", "aria-label": "파일함 위치" });
  const list = el("div", { class: "fba-list", tabindex: "-1", "aria-label": "파일함 항목" });
  const message = el("p", { class: "srv-msg", role: "status", "aria-live": "polite" });
  const count = el("span", { class: "tiny", role: "status", "aria-live": "polite" });
  const cancel = btn("취소", {}, "ghost");
  const confirm = btn("첨부", {}, "primary");
  const dialog = el("dialog", { class: "fba-dialog", "aria-labelledby": title.id, "aria-describedby": hint.id },
    el("div", { class: "fba-body" },
      el("div", { class: "fba-head" }, title, close),
      hint,
      crumbs,
      list,
      message,
      el("div", { class: "fba-foot" }, count, el("span", { class: "fba-actions" }, cancel, confirm)),
    ),
  );
  const node = el("span", { class: "fba" }, open, dialog);

  const say = (text, alert) => {
    message.className = alert ? "srv-msg is-alert" : "srv-msg";
    message.setAttribute("role", alert ? "alert" : "status");
    message.textContent = text || "";
  };
  const left = () => Math.max(0, Number(room()) || 0);
  const paintCount = () => {
    const free = left();
    count.textContent = selected.size ? `${selected.size}개 고름 · ${free}개까지 첨부할 수 있습니다` : `${free}개까지 첨부할 수 있습니다`;
    confirm.disabled = busy || !selected.size;
  };

  const crumb = (label, target, current) => (current
    ? el("li", {}, el("span", { "aria-current": "location", text: label }))
    : el("li", {}, (() => {
      const go = btn(label, { class: "btn btn-ghost fba-crumb" }, "ghost");
      go.addEventListener("click", () => show(target));
      return go;
    })()));
  const paintCrumbs = (path) => {
    const trail = Array.isArray(path) ? path : [];
    crumbs.replaceChildren(el("ol", {},
      crumb("파일함", null, !folder),
      ...trail.map((entry, index) => crumb(entry.name, entry.id, index === trail.length - 1 && folder != null)),
    ));
  };

  const row = (item) => {
    if (item.kind === "folder") {
      const enter = btn(item.name, { class: "btn btn-ghost fba-folder", "aria-label": `${item.name} 폴더 열기` }, "ghost");
      enter.addEventListener("click", () => show(item.id));
      return el("li", { class: "fba-row" }, enter, el("span", { class: "tiny", text: "폴더" }));
    }
    const ready = item.state === "ready";
    const box = el("input", { type: "checkbox", "aria-describedby": `${id}-i${item.id}` });
    box.checked = selected.has(item.id);
    box.disabled = busy || !ready;
    box.addEventListener("change", () => {
      if (box.checked) {
        if (selected.size >= left()) {
          box.checked = false;
          say(limit ? `파일은 ${limit}개까지 첨부할 수 있습니다. 더 고르려면 고른 파일을 빼세요.` : "더 첨부할 수 없습니다.", true);
          return;
        }
        selected.set(item.id, item);
      } else selected.delete(item.id);
      say("");
      paintCount();
    });
    const meta = [(item.extension || "파일").toUpperCase(), sizeText(item.sizeBytes)].filter(Boolean).join(" · ");
    const scan = SCAN_STATE[item.state] ? badge(SCAN_STATE[item.state], item.state === "rejected" ? "danger" : "neutral") : null;
    return el("li", { class: "fba-row" },
      el("label", { class: "choice fba-file" }, box,
        el("span", { class: "fba-name" }, el("b", { text: item.name }), el("span", { id: `${id}-i${item.id}`, class: "tiny", text: meta }))),
      scan);
  };

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(loadingState("파일함을 불러오는 중입니다"));
      else {
        const again = btn("다시 불러오기", {}, "ghost");
        again.addEventListener("click", () => {
          again.disabled = true;
          refresh();
        });
        list.replaceChildren(errorState("파일함을 불러오지 못했습니다", fileboxMessage(entry.error), again));
      }
      return;
    }
    paintCrumbs(data.path);
    if (entry.error) say(fileboxMessage(entry.error), true);
    const more = data.nextCursor ? btn(entry.loading ? "불러오는 중…" : "더 보기", {}, "secondary") : null;
    if (more) {
      more.disabled = Boolean(entry.loading);
      more.addEventListener("click", () => {
        more.disabled = true;
        more.setAttribute("aria-busy", "true");
        more.textContent = "불러오는 중…";
        refresh(data);
      });
    }
    const hadFocus = list.contains(document.activeElement);
    list.replaceChildren(
      data.items.length
        ? el("ul", { class: "fba-rows" }, ...data.items.map(row))
        : emptyState("이 폴더에 항목이 없습니다", "파일함 화면에서 파일을 올린 뒤 다시 고르세요."),
      more ? el("div", { class: "btn-row" }, more) : null,
    );
    if (hadFocus || (dialog.open && !dialog.contains(document.activeElement))) list.focus();
    paintCount();
  };

  // 지금 폴더를 읽고, 그 사이 다른 폴더로 옮기지 않았으면 다시 그린다. `previous`가 있으면 다음 쪽을 붙인다.
  function refresh(previous = null) {
    const key = keyOf(folder);
    const loading = load(folder, previous);
    paint(listings.peek(key));
    loading.then(() => {
      if (keyOf(folder) === key && dialog.open) paint(listings.peek(key));
    });
  }

  function show(target) {
    folder = target || null;
    refresh();
    list.focus();
  }

  const finish = () => {
    if (dialog.open) dialog.close();
  };
  open.addEventListener("click", () => {
    if (open.disabled || dialog.open) return;
    selected.clear();
    say("");
    hint.textContent = limit
      ? `검사를 통과한 파일만 고를 수 있습니다. 이 글에는 ${limit}개까지 첨부합니다.`
      : "검사를 통과한 파일만 고를 수 있습니다.";
    dialog.showModal();
    show(folder);
  });
  close.addEventListener("click", finish);
  cancel.addEventListener("click", finish);
  // 앱 전체 click·keydown 처리(`data-act`, Escape로 바깥 창 닫기)가 이 창의 입력을 받지 않게 한다.
  for (const type of ["click", "change", "input"]) dialog.addEventListener(type, (event) => event.stopPropagation());
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) finish();
  });
  dialog.addEventListener("keydown", (event) => {
    event.stopPropagation();
    if (event.key !== "Escape") return;
    event.preventDefault();
    finish();
  });
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    finish();
  });
  dialog.addEventListener("close", () => {
    if (open.isConnected) open.focus();
  });

  confirm.addEventListener("click", async () => {
    if (busy) return;
    const ids = [...selected.keys()].sort((a, b) => a - b);
    if (!ids.length) return say("첨부할 파일을 고르세요.", true);
    if (ids.length > left()) {
      return say(limit ? `파일은 ${limit}개까지 첨부할 수 있습니다. 지금은 ${left()}개까지 고를 수 있습니다.` : "더 첨부할 수 없습니다.", true);
    }
    busy = true;
    confirm.disabled = true;
    confirm.setAttribute("aria-busy", "true");
    confirm.textContent = "첨부하는 중…";
    for (const box of list.querySelectorAll("input[type=checkbox]")) box.disabled = true;
    say(course ? "파일함의 파일을 이 과목에 첨부하고 있습니다." : "파일함의 파일을 첨부하고 있습니다.");
    let stale = false;
    try {
      const uploads = await uploadsFromFilebox(ids, course ? courseId : null, purpose);
      selected.clear();
      say("");
      onAttach(uploads);
      finish();
    } catch (error) {
      say(attachRefusal(error, { course }), true);
      // 목록과 서버가 어긋났다. 고른 것을 비우고 지금 폴더를 다시 읽는다. 응답을 못 받은 경우는 고른 그대로 둔다.
      stale = error instanceof ApiError && (error.status === 404 || ["not_active", "not_ready", "folder_not_allowed"].includes(error.code));
      if (stale) selected.clear();
    } finally {
      busy = false;
      confirm.removeAttribute("aria-busy");
      confirm.textContent = "첨부";
      if (dialog.open) {
        if (stale) refresh();
        else paint(listings.peek(keyOf(folder)));
      }
      paintCount();
    }
  });

  return {
    node,
    setDisabled(on) {
      open.disabled = Boolean(on);
      if (on) finish();
    },
  };
}
