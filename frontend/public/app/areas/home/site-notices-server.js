// 알림마당 공지사항(30, 30b), 공지 창(06_pop_notice_view, `?pop=notice&id=srv-<noticeId>`), 공지 쓰기·수정
// 창(`?pop=notice-write[&id=srv-<noticeId>]`), 진입 공지(00_notice_for_me, `?pop=welcome`)와 홈의 알림마당 공지
// 줄을 ASTRA 서버에서 그린다(backend `anon`·`ready`). 서버 글은 모두 텍스트 노드다. 쓰기 진입은 목록의
// `canManage`, 공지별 수정·삭제·바로 게시는 상세의 `canManage`가 true일 때만 보인다.
import { el, icon } from "../../dom.js";
import { btn, go, badge, field, pagination, emptyState, loadingState, errorState, dialogShell as baseDialog } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { readQuery } from "../../shared/query.js";
import { backendStatus, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit, replaceVisit } from "../../shared/remote-cache.js";
import { homeEntry, watchHome, koDate, koDateTime, wallText } from "../../shared/home-remote.js";
import { serverTime } from "../../shared/lectures-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { attachPicker, resetAttachments } from "../../shared/attach-picker.js";
import {
  SITE_NOTICE_PAGE, SITE_AUDIENCES, WRITE_AUDIENCES, MAX_NOTICE_FILES, siteListKey, siteNoticeLists, siteNoticeDetails,
  siteManageState, checkSiteManage, siteNoticeIdOf, siteNoticeHref, markSiteNoticeRead, claimEntryPopup, dismissEntryNotices,
  createSiteNotice, updateSiteNotice, publishSiteNoticeNow, deleteSiteNotice, isVersionConflict, isAttachmentNotReady, isMissing,
  siteAudienceLabel, siteNoticeMessage,
} from "../../shared/site-notices-remote.js";

const SEARCH_DELAY = 300;
const SCOPE = "home:board:global";
const COUNT_OF = { all: "total", common: "common", professor: "professor", student: "student" };
const HOME_PATH = "/ilos/main/main_form.acl";

const listPath = () => hrefFor(find("30_community_notice_list"));
const writeHref = (noticeId) => `${listPath()}?${new URLSearchParams({ pop: "notice-write", ...(noticeId ? { id: `srv-${noticeId}` } : {}) })}`;

// 쓰는 중인 공지(키 "new" 또는 noticeId)와 고른 첨부. 세션이 바뀌면 버린다.
const memos = new Map();
// 공지 창 운영 줄의 마지막 결과(noticeId → { text, alert }). 창이 다시 그려져도 남는다.
const manageNotes = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
  manageNotes.clear();
  entry.items = null;
  entry.live = false;
});

/** Same frame as the other home dialogs (`home-dialog`). */
function dialogShell(title, body, options) {
  const node = baseDialog(title, body, options);
  node.classList.add("home-dialog");
  return node;
}

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return el("p", { class: "tiny", text: "본문이 없습니다." });
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

/** "고정", "예약" badges of a notice row or detail. */
function flags(notice) {
  return [
    notice.pinned ? badge("고정", "neutral") : null,
    notice.published === false ? badge("예약", "danger") : null,
  ].filter(Boolean);
}

function whenText(notice) {
  return notice.published === false ? `${koDateTime(notice.publishedAt)} 게시 예정` : koDate(notice.publishedAt);
}

function pageOf(params) {
  const page = Number(params.get("page"));
  return Number.isInteger(page) && page > 0 && page < 100000 ? page : 1;
}

// ---------- 목록 (30, 30b) ----------

/** 알림마당 공지사항 in backend mode; `head` is the page header built by home.js. */
export function serverSiteNotices(screen, ctx, head) {
  const body = el("section", { class: "board srv-site-notices" }, head);
  if (backendStatus() === "anon") {
    body.append(backendPanel({ text: "ASTRA 서버에 로그인하면 알림마당 공지를 서버에서 불러옵니다." }));
    return body;
  }
  const params = ctx.params || new URLSearchParams();
  const wanted = screen.audience || params.get("audience");
  const audience = SITE_AUDIENCES.includes(wanted) ? wanted : "all";
  const q = readQuery(ctx.state, SCOPE).trim().slice(0, 100);
  const page = pageOf(params);
  const key = siteListKey(audience, q, page);
  const load = () => siteNoticeLists.load(key, audience, q, page);
  const base = listPath();
  const hrefWith = (extra) => {
    const query = new URLSearchParams({ ...(audience !== "all" ? { audience } : {}), ...extra });
    const text = query.toString();
    return text ? `${base}?${text}` : base;
  };

  const tools = el("div", { class: "board-tools srv-site-tools" });
  const chips = el("div", { class: "filters", role: "toolbar", "aria-label": "공지 대상" });
  const list = el("div", { "aria-live": "polite" });
  head.append(tools);
  body.append(chips, el("label", { class: "search board-search" },
    icon("search"),
    el("input", {
      "data-name": "surfaceQuery",
      "data-scope": SCOPE,
      "data-focus": SCOPE,
      value: readQuery(ctx.state, SCOPE),
      placeholder: "검색",
      "aria-label": "공지사항 제목 검색",
    }),
  ), list);

  const paintTools = (data) => tools.replaceChildren(data && data.canManage === true ? go(writeHref(null), "공지 쓰기", { class: "btn btn-primary", "data-site-notice-new": "" }) : "");
  const paintChips = (counts) => chips.replaceChildren(...SITE_AUDIENCES.map((value) => {
    const count = counts ? counts[COUNT_OF[value]] : null;
    // 볼 수 없는 대상(서버가 0으로 센 것)은 고른 경우가 아니면 숨긴다.
    if (counts && value !== "all" && value !== audience && !count) return "";
    const href = value === "all" ? base : `${base}?audience=${value}`;
    return go(href, count != null ? `${siteAudienceLabel(value)} ${count}` : siteAudienceLabel(value), {
      class: `chip${audience === value ? " on all" : ""}`,
      "aria-current": audience === value ? "true" : null,
    });
  }));

  const paint = (entry) => {
    const data = entry && entry.data;
    paintTools(data);
    paintChips(data ? data.counts : null);
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(loadingState("공지를 불러오는 중입니다"));
      else list.replaceChildren(errorState("공지를 불러오지 못했습니다", siteNoticeMessage(entry.error), retry(load)));
      return;
    }
    const stale = entry.error ? el("p", { class: "err", role: "alert", text: siteNoticeMessage(entry.error) }) : "";
    const pages = Math.max(1, Math.ceil((Number(data.total) || 0) / SITE_NOTICE_PAGE));
    if (!data.items.length) {
      list.replaceChildren(stale, page > 1
        ? emptyState("이 쪽에는 공지가 없습니다", "첫 쪽으로 돌아가 보세요.", go(hrefWith({}), "첫 쪽으로", { class: "btn btn-secondary" }))
        : q || audience !== "all"
          ? emptyState("조건에 맞는 공지가 없습니다", "다른 대상을 고르거나 검색어를 지워 보세요.")
          : emptyState("등록된 공지가 없습니다", "새 공지가 올라오면 여기에 보입니다."));
      return;
    }
    list.replaceChildren(
      stale,
      el("p", { class: "board-count", text: `${audience === "all" ? "전체" : siteAudienceLabel(audience)} ${Number(data.total) || 0}건` }),
      el("div", { class: "board-list" }, ...data.items.map((item) => {
        const href = hrefWith({ ...(page > 1 ? { page: String(page) } : {}), pop: "notice", id: `srv-${item.noticeId}` });
        return el("a", { class: `board-card${item.read ? " read" : ""}`, href, "data-go": href, "data-notice": String(item.noticeId) },
          el("span", { class: "srv-site-title" },
            ...flags(item),
            item.read ? "" : el("span", { class: "ntf-dot", role: "img", "aria-label": "읽지 않은 공지" }),
            el("b", { text: item.title }),
          ),
          el("span", { class: "tiny", text: [siteAudienceLabel(item.audience), whenText(item), `조회 ${item.viewCount ?? 0}`, item.read ? null : "안 읽음"].filter(Boolean).join(" · ") }),
          item.excerpt ? el("p", { text: item.excerpt }) : null,
        );
      })),
      pagination({ page, pages, hrefForPage: (n) => hrefWith(n > 1 ? { page: String(n) } : {}) }) || "",
    );
  };
  paint(siteNoticeLists.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    siteNoticeLists.watch(body, key, paint, load);
    const fresh = freshVisit("community-notices", key);
    if (fresh === "entry") load();
    else if (!siteNoticeLists.peek(key)) {
      if (fresh === "key") setTimeout(() => { if (body.isConnected && !siteNoticeLists.peek(key)) load(); }, SEARCH_DELAY);
      else load();
    }
  });
  return body;
}

// ---------- 공지 창 (06_pop_notice_view) ----------

/** `?pop=notice&id=srv-<noticeId>` in backend mode: body, attachments, explicit read, operator controls. */
export function serverSiteNoticeDialog(ctx) {
  const frame = el("div", { "aria-live": "polite", class: "srv-site-notice" });
  const shell = (title = "공지") => dialogShell(title, frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: "ASTRA 서버에 로그인하면 공지를 볼 수 있습니다." }));
    return shell();
  }
  const id = siteNoticeIdOf(ctx.params && ctx.params.get("id"));
  if (!id) {
    frame.append(emptyState("공지를 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요."));
    return shell();
  }
  const load = () => siteNoticeDetails.load(id, id);
  const readNote = el("p", { class: "tiny", role: "status" });
  const sendRead = (item) => {
    if (!frame.isConnected || !item || item.read || item.published === false) return;
    markSiteNoticeRead(id).catch((error) => {
      if (readNote.isConnected) readNote.textContent = `읽음 표시를 남기지 못했습니다. ${siteNoticeMessage(error)}`;
    });
  };
  const paint = (entry) => {
    const item = entry && entry.data;
    if (!item) {
      if (!entry || !entry.error) frame.replaceChildren(loadingState("공지를 불러오는 중입니다"));
      else if (isMissing(entry.error)) frame.replaceChildren(emptyState("공지를 찾을 수 없습니다", "삭제되었거나 볼 수 있는 대상이 아닙니다."));
      else frame.replaceChildren(errorState("공지를 불러오지 못했습니다", siteNoticeMessage(entry.error), retry(load)));
      return;
    }
    const files = Array.isArray(item.attachments) ? item.attachments : [];
    frame.replaceChildren(
      el("h3", { class: "srv-site-title" }, ...flags(item), el("span", { text: item.title })),
      el("p", { class: "tiny", text: [siteAudienceLabel(item.audience), item.authorName, item.published === false ? `${koDateTime(item.publishedAt)} 게시 예정` : koDateTime(item.publishedAt), `조회 ${item.viewCount ?? 0}`].filter(Boolean).join(" · ") }),
      prose(item.body),
      files.length
        ? el("ul", { class: "stack srv-site-files", "aria-label": "첨부 파일" }, ...files.map((file) => {
          const control = downloadControl(null, file);
          return el("li", { class: "file-row" }, icon("file"),
            el("span", {}, el("b", { text: file.name || "파일" }), el("span", { class: "tiny", text: sizeText(file.sizeBytes) })),
            control.button, control.note);
        }))
        : "",
      readNote,
      item.canManage
        ? el("div", { class: "btn-row srv-site-manage" },
          go(writeHref(id), "수정", { class: "btn btn-secondary" }),
          item.published === false ? btn("바로 게시", { "data-act": "srv-site-notice", "data-op": "publish", "data-id": String(id) }, "primary") : "",
          btn("삭제", { "data-act": "srv-site-notice", "data-op": "delete", "data-id": String(id) }, "danger"),
          manageStatus(id),
        )
        : "",
    );
    sendRead(item);
  };
  paint(siteNoticeDetails.peek(id));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    siteNoticeDetails.watch(frame, id, paint, null);
    const fresh = freshVisit("community-notice", String(id)) === "entry";
    if (fresh) manageNotes.delete(id);
    if (fresh || !siteNoticeDetails.peek(id)) load();
    else sendRead(siteNoticeDetails.peek(id).data);
  });
  return shell();
}

// ---------- 쓰기·수정 창 ----------

function memoFor(key) {
  if (!memos.has(key)) memos.set(key, { loaded: false, title: "", body: "", audience: "common", pinned: false, publishAt: "", version: null, attach: {}, latest: null, message: "" });
  return memos.get(key);
}

function adopt(memo, item) {
  memo.loaded = true;
  memo.title = item.title || "";
  memo.body = item.body || "";
  memo.audience = WRITE_AUDIENCES.includes(item.audience) ? item.audience : "common";
  memo.pinned = Boolean(item.pinned);
  // 이미 게시된 공지는 게시 일시를 비워 두면 원래 일시를 지킨다. 예약 공지만 그 일시를 채운다.
  memo.publishAt = item.published === false ? wallText(item.publishedAt) : "";
  memo.version = item.version;
}

function writeForm(memo, key, item) {
  const input = (name, attrs) => {
    const node = el("input", { name, ...attrs });
    node.addEventListener("input", () => { memo[name] = node.type === "checkbox" ? node.checked : node.value; });
    node.addEventListener("change", () => { memo[name] = node.type === "checkbox" ? node.checked : node.value; });
    return node;
  };
  const title = input("title", { value: memo.title, maxlength: "300", required: true });
  const text = el("textarea", { name: "body", rows: "8", maxlength: "20000" });
  text.value = memo.body;
  text.addEventListener("input", () => { memo.body = text.value; });
  const audience = el("select", { name: "audience" }, ...WRITE_AUDIENCES.map((value) => el("option", { value, text: siteAudienceLabel(value), selected: memo.audience === value })));
  audience.addEventListener("change", () => { memo.audience = audience.value; });
  const pinned = input("pinned", { type: "checkbox" });
  pinned.checked = memo.pinned;
  const publishAt = input("publishAt", { type: "datetime-local", value: memo.publishAt });
  const picker = attachPicker({
    memo: memo.attach,
    courseId: null,
    purpose: "community_notice",
    max: MAX_NOTICE_FILES,
    existing: item && Array.isArray(item.attachments) ? item.attachments : [],
    hint: `${MAX_NOTICE_FILES}개까지, 50MB 이하. 검사를 통과한 파일만 첨부됩니다.`,
  });
  memo.picker = picker;
  const status = el("p", { class: memo.message ? "err" : "tiny", role: memo.message ? "alert" : "status", "data-site-notice-status": "", text: memo.message });
  return el("div", { class: "stack srv-site-form", "data-site-notice-form": String(key) },
    field("제목", title),
    field("본문", text),
    el("div", { class: "srv-site-row" },
      field("대상", audience),
      field("게시 일시", publishAt),
    ),
    el("p", { class: "tiny", text: item && item.published !== false ? "게시 일시를 비워 두면 원래 게시 일시를 그대로 둡니다. 미래 일시를 넣으면 그때 다시 게시됩니다." : "게시 일시를 비워 두면 지금 게시합니다. 미래 일시를 넣으면 그때까지 운영자에게만 보입니다." }),
    el("label", { class: "check srv-site-check" }, pinned, el("span", { text: "고정 공지(목록 맨 위, 읽지 않은 사람에게 진입 공지로 보임)" })),
    picker.node,
    memo.latest
      ? el("details", { class: "srv-site-latest", open: true },
        el("summary", {}, el("b", { text: "서버의 최신 내용" }), el("span", { class: "tiny", text: ` · 버전 ${memo.latest.version}` })),
        el("p", { class: "tiny", text: memo.latest.title }),
        prose(memo.latest.body),
      )
      : "",
    el("div", { class: "btn-row" },
      btn(item ? "수정 저장" : "등록", { "data-act": "srv-site-notice", "data-op": "save", "data-key": String(key) }, "primary"),
      go(item ? siteNoticeHref(item.noticeId) : listPath(), "취소", { class: "btn btn-ghost" }),
    ),
    status,
  );
}

/** `?pop=notice-write[&id=srv-<noticeId>]`: operators only (server `canManage`). */
export function serverSiteNoticeWriteDialog(ctx) {
  const frame = el("div", { "aria-live": "polite" });
  const ref = ctx.params && ctx.params.get("id");
  const id = ref ? siteNoticeIdOf(ref) : null;
  const shell = () => dialogShell(id ? "공지 수정" : "공지 쓰기", frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: "ASTRA 서버에 로그인하면 공지를 관리할 수 있습니다." }));
    return shell();
  }
  const denied = () => emptyState("공지를 관리할 수 없습니다", "알림마당 운영자만 공지를 쓰고 고칠 수 있습니다.", go(listPath(), "목록으로", { class: "btn btn-secondary" }));
  if (ref && !id) {
    frame.append(emptyState("공지를 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요."));
    return shell();
  }
  if (!id) {
    // 쓰기 진입은 목록의 `canManage`로 정한다. 아직 목록을 읽지 않았으면 첫 쪽을 한 번 읽는다.
    const paintNew = (manage) => {
      if (manage === true) frame.replaceChildren(writeForm(memoFor("new"), "new", null));
      else if (manage === false) frame.replaceChildren(denied());
      else frame.replaceChildren(errorState("권한을 확인하지 못했습니다", "잠시 후 다시 시도하세요.", retry(checkNew)));
    };
    const checkNew = () => {
      frame.replaceChildren(loadingState("권한을 확인하는 중입니다"));
      checkSiteManage().then((manage) => { if (frame.isConnected) paintNew(manage); });
    };
    if (siteManageState() !== null) paintNew(siteManageState());
    else {
      frame.replaceChildren(loadingState("권한을 확인하는 중입니다"));
      queueMicrotask(() => { if (frame.isConnected) checkNew(); });
    }
    return shell();
  }
  const load = () => siteNoticeDetails.load(id, id);
  const paint = (entry) => {
    const item = entry && entry.data;
    if (!item) {
      if (!entry || !entry.error) frame.replaceChildren(loadingState("공지를 불러오는 중입니다"));
      else if (isMissing(entry.error)) frame.replaceChildren(emptyState("공지를 찾을 수 없습니다", "삭제되었거나 볼 수 있는 대상이 아닙니다."));
      else frame.replaceChildren(errorState("공지를 불러오지 못했습니다", siteNoticeMessage(entry.error), retry(load)));
      return;
    }
    if (!item.canManage) {
      frame.replaceChildren(denied());
      return;
    }
    const memo = memoFor(id);
    if (!memo.loaded) adopt(memo, item);
    frame.replaceChildren(writeForm(memo, id, item));
  };
  paint(siteNoticeDetails.peek(id));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    siteNoticeDetails.watch(frame, id, paint, null);
    if (!siteNoticeDetails.peek(id)) load();
  });
  return shell();
}

function busy(node, on, label) {
  node.disabled = on;
  if (on) node.setAttribute("aria-busy", "true");
  else node.removeAttribute("aria-busy");
  if (label) node.textContent = label;
}

async function saveNotice(node, api) {
  const key = node.dataset.key === "new" ? "new" : Number(node.dataset.key);
  const memo = memos.get(key);
  const form = node.closest("[data-site-notice-form]");
  if (!memo || !form) return;
  const status = form.querySelector("[data-site-notice-status]");
  const say = (text, alert = true) => {
    memo.message = alert ? text : "";
    status.className = alert ? "err" : "tiny";
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = text;
  };
  const title = memo.title.trim();
  if (!title) return say("제목을 입력하세요.");
  if (title.length > 300) return say("제목은 300자 이하입니다.");
  if (memo.body.length > 20000) return say("본문은 20,000자 이하입니다.");
  const publishAt = memo.publishAt ? serverTime(memo.publishAt) : null;
  if (memo.publishAt && !publishAt) return say("게시 일시를 확인하세요.");
  const picker = memo.picker;
  if (picker && picker.pending()) return say("첨부 파일 검사가 끝난 뒤 저장하세요.");
  const payload = { title, body: memo.body, audience: memo.audience, pinned: Boolean(memo.pinned) };
  if (publishAt) payload.publishAt = publishAt;
  const label = node.textContent;
  busy(node, true, "저장하는 중…");
  form.setAttribute("aria-busy", "true");
  say("ASTRA 서버에 저장하고 있습니다.", false);
  try {
    let notice;
    if (key === "new") {
      notice = await createSiteNotice({ ...payload, uploadIds: picker ? picker.uploadIds() : [] });
    } else {
      if (picker && picker.changed()) payload.attachments = picker.attachments();
      notice = await updateSiteNotice(key, { ...payload, version: memo.version });
    }
    resetAttachments(memo.attach);
    memos.delete(key);
    api.getState().toast = key === "new" ? "공지를 등록했습니다" : "공지를 수정했습니다";
    api.navigate(siteNoticeHref(notice.noticeId));
  } catch (error) {
    if (!form.isConnected) {
      api.getState().toast = siteNoticeMessage(error, true);
      api.paintToast();
      return;
    }
    say(siteNoticeMessage(error, true));
    if (isAttachmentNotReady(error) && picker) picker.recheck();
    if (key !== "new" && isVersionConflict(error)) {
      // 최신 공지를 다시 읽어 version만 바꾸고 입력한 글은 그대로 둔다.
      await siteNoticeDetails.load(key, key);
      const latest = siteNoticeDetails.peek(key);
      if (latest && latest.data) {
        memo.version = latest.data.version;
        memo.latest = { title: latest.data.title, body: latest.data.body, version: latest.data.version };
      } else {
        memo.message = `${siteNoticeMessage(error, true)} 최신 내용을 불러오지 못했습니다.`;
      }
      api.render();
    }
  } finally {
    if (node.isConnected) busy(node, false, label);
    if (form.isConnected) form.removeAttribute("aria-busy");
  }
}

const armed = new Set(); // noticeId whose delete button was pressed once

async function removeNotice(node, api) {
  const id = Number(node.dataset.id);
  const status = node.parentElement && node.parentElement.querySelector("[data-site-notice-status]");
  if (!armed.has(id)) {
    armed.add(id);
    node.textContent = "한 번 더 누르면 삭제";
    if (status) status.textContent = "삭제하면 되돌릴 수 없습니다. 한 번 더 누르세요.";
    return;
  }
  armed.delete(id);
  busy(node, true, "삭제하는 중…");
  try {
    await deleteSiteNotice(id);
    api.getState().toast = "공지를 삭제했습니다";
    api.navigate(listPath());
  } catch (error) {
    if (!node.isConnected) return;
    busy(node, false, "삭제");
    if (status) {
      status.setAttribute("role", "alert");
      status.textContent = siteNoticeMessage(error, true);
    }
  }
}

/** The status line of a notice's operator row, with the last publish/delete result. */
function manageStatus(id) {
  const note = manageNotes.get(id);
  return el("span", { class: "tiny", role: note && note.alert ? "alert" : "status", "data-site-notice-status": "", text: note ? note.text : "" });
}

const publishing = new Set(); // noticeId being published now

/** 예약 공지 바로 게시. 버전이 바뀌었으면 최신 공지를 다시 읽고 확인한 뒤 다시 누르게 한다. */
async function publishNotice(node) {
  const id = Number(node.dataset.id);
  const entry = siteNoticeDetails.peek(id);
  const item = entry && entry.data;
  if (!item || publishing.has(id)) return;
  publishing.add(id);
  manageNotes.delete(id);
  const status = node.parentElement && node.parentElement.querySelector("[data-site-notice-status]");
  busy(node, true, "게시하는 중…");
  if (status) {
    status.setAttribute("role", "status");
    status.textContent = "ASTRA 서버에 게시하고 있습니다.";
  }
  try {
    await publishSiteNoticeNow(item);
    manageNotes.set(id, { text: "공지를 지금 게시했습니다.", alert: false });
    await siteNoticeDetails.load(id, id);
  } catch (error) {
    if (isVersionConflict(error)) {
      manageNotes.set(id, { text: "다른 곳에서 이 공지를 먼저 고쳤습니다. 최신 내용을 불러왔으니 확인한 뒤 다시 누르세요.", alert: true });
      await siteNoticeDetails.load(id, id);
    } else {
      manageNotes.set(id, { text: siteNoticeMessage(error, true), alert: true });
      if (isMissing(error)) await siteNoticeDetails.load(id, id);
    }
    if (node.isConnected) {
      busy(node, false, "바로 게시");
      const live = node.parentElement && node.parentElement.querySelector("[data-site-notice-status]");
      if (live) {
        live.setAttribute("role", "alert");
        live.textContent = manageNotes.get(id).text;
      }
    }
  } finally {
    publishing.delete(id);
  }
}

/** `data-act="srv-site-notice"` (`data-op` save | publish | delete). A command in flight is not sent twice. */
export function siteNoticeAction(node, api) {
  if (node.disabled || node.getAttribute("aria-busy") === "true") return;
  if (node.dataset.op === "save") void saveNotice(node, api);
  else if (node.dataset.op === "publish") void publishNotice(node);
  else if (node.dataset.op === "delete") void removeNotice(node, api);
}

// ---------- 홈: 알림마당 공지 줄과 진입 공지 ----------

/** 홈 `communityNotices`(최근 5개, 고정 먼저). 누르면 06 공지 창이 열린다. */
export function siteNoticeStrip(data) {
  const items = Array.isArray(data && data.communityNotices) ? data.communityNotices : [];
  return el("section", { class: "home-notices srv-site-strip", "aria-label": "알림마당 공지" },
    el("div", { class: "aside-h" },
      el("h2", { class: "section-label", text: "알림마당 공지" }),
      go(listPath(), "전체 보기", { class: "tiny" }),
    ),
    items.length
      ? el("ul", { class: "ntf-list" }, ...items.map((notice) => {
        const href = siteNoticeHref(notice.noticeId);
        return el("li", {},
          el("a", { class: `ntf-row${notice.read ? " read" : ""}`, href, "data-go": href },
            notice.read
              ? el("span", { class: "ntf-dot", "aria-hidden": "true" })
              : el("span", { class: "ntf-dot", role: "img", "aria-label": "읽지 않은 공지" }),
            el("span", { class: "ntf-copy" },
              el("b", { text: notice.pinned ? `[고정] ${notice.title}` : notice.title }),
              el("span", { class: "ntf-when" }, el("span", { text: koDate(notice.publishedAt) })),
            ),
          ),
        );
      }))
      : el("p", { class: "note", text: "알림마당 공지가 없습니다." }),
  );
}

/**
 * 홈 집계에 읽지 않은 고정 공지(`noticesForMe`)가 있으면 세션마다 한 번 진입 공지(`?pop=welcome`)를 연다.
 * 다른 창이 열려 있으면 열지 않는다. 사용자가 연 창이 아니라 history 항목을 만들지 않고 지금 항목의 주소만
 * 바꾼다(`replaceVisit`, `popInPlace`). 그래서 홈을 새 방문으로 보고 다시 읽지 않고, 닫기(app.js `closeDialog`)도
 * 같은 항목에서 창만 걷으며, 뒤로 가기는 홈 앞의 화면으로 간다.
 */
export function offerEntryNotices(data, node) {
  const items = Array.isArray(data && data.noticesForMe) ? data.noticesForMe : [];
  if (!items.length || backendStatus() !== "ready") return;
  setTimeout(() => {
    if (!node.isConnected || location.pathname !== HOME_PATH && location.pathname !== "/") return;
    const params = new URLSearchParams(location.search);
    if (params.has("pop") || !claimEntryPopup()) return;
    params.set("pop", "welcome");
    const state = history.state && typeof history.state === "object" ? history.state : {};
    replaceVisit(`${HOME_PATH}?${params}`, { ...state, popInPlace: true });
    // app.js draws the new address on popstate.
    window.dispatchEvent(new PopStateEvent("popstate"));
  }, 0);
}

// 띄운 진입 공지. 창이 다시 그려져도(토스트, 포커스) 같은 목록을 쓰고, 떠날 때 한 번만 읽음을 보낸다.
const entry = { items: null, live: false, listening: false };
const onWelcome = () => new URLSearchParams(location.search).get("pop") === "welcome";

function leaveEntry() {
  if (!entry.live) return;
  entry.live = false;
  const ids = (entry.items || []).map((item) => item.noticeId);
  entry.items = null;
  if (ids.length) void dismissEntryNotices(ids);
}

function listenEntry() {
  if (entry.listening) return;
  entry.listening = true;
  // Escape는 app.js보다 먼저(capture) 본다. 뒤로 가기로 창을 벗어나도 떠난 것으로 친다.
  window.addEventListener("keydown", (event) => { if (event.key === "Escape" && onWelcome()) leaveEntry(); }, true);
  window.addEventListener("popstate", () => { if (!onWelcome()) leaveEntry(); });
}

/** `?pop=welcome` (00_notice_for_me) while `ready`: the unread pinned notices; leaving it sends `read` for each. */
export function serverEntryNotices() {
  const frame = el("div", { "aria-live": "polite", class: "srv-site-entry" });
  const paint = (home) => {
    const data = home && home.data;
    if (!entry.items) {
      if (!data) {
        frame.replaceChildren(home && home.error ? errorState("공지를 불러오지 못했습니다", siteNoticeMessage(home.error)) : loadingState("공지를 불러오는 중입니다"));
        return;
      }
      entry.items = (Array.isArray(data.noticesForMe) ? data.noticesForMe : []).slice();
      entry.live = true;
    }
    const items = entry.items;
    frame.replaceChildren(
      items.length
        ? el("ul", { class: "ntf-list" }, ...items.map((item) => {
          const href = siteNoticeHref(item.noticeId);
          return el("li", {},
            el("a", { class: "ntf-row", href, "data-go": href },
              el("span", { class: "ntf-dot", role: "img", "aria-label": "읽지 않은 공지" }),
              el("span", { class: "ntf-copy" },
                el("b", { text: item.pinned ? `[고정] ${item.title}` : item.title }),
                el("span", { class: "ntf-when" }, el("span", { text: koDate(item.publishedAt) })),
              ),
            ),
          );
        }))
        : el("p", { class: "note", text: "새로 알릴 공지가 없습니다." }),
      items.length ? el("p", { class: "tiny", text: "확인을 누르면 위 공지를 읽음으로 표시하고 다시 띄우지 않습니다." }) : "",
      el("div", { class: "btn-row" }, btn("확인", { "data-act": "close" }, "primary")),
    );
  };
  paint(homeEntry());
  const shell = dialogShell("알림마당 공지", frame);
  // 닫기·바깥·확인·공지 링크: app.js가 창을 닫기 전에(요소 listener가 먼저) 읽음을 보낸다.
  shell.addEventListener("click", (event) => {
    const hit = event.target.closest("[data-act=\"close\"], [data-go]");
    if (!hit || (hit === shell && event.target !== shell)) return;
    leaveEntry();
  });
  listenEntry();
  queueMicrotask(() => {
    if (frame.isConnected && !entry.items) watchHome(frame, (home) => { if (!entry.items) paint(home); });
  });
  return shell;
}
