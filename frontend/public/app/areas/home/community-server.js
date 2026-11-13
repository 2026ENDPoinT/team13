// 알림마당 자료실(32) and 1:1 문의(31) from the ASTRA backend (status `anon` or `ready`). The lists
// are server data only. Anyone logged in writes a 1:1 문의 (`?pop=qna-write`) with files; 알림마당
// 운영자 answer inquiries in their detail, see everyone's inquiries (`?all=1`), and create, edit and
// delete materials (`?pop=material-write[&id=]`). Attachments have no course: they are uploaded
// without `courseId` and downloaded through `/api/files/{fileId}/content`. Commands go through
// `data-act="srv-community"`; typed text and picked files live in memory per form until the save
// succeeds or the session changes. Every piece of server text is a text node.
import { el, icon } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState, dialogShell as baseDialog } from "../../ui/components.js";
import { hrefFor, find } from "../../manifest.js";
import { readQuery } from "../../shared/query.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, subscribe } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDate, koDateTime } from "../../shared/home-remote.js";
import { downloadControl, sizeText } from "../../shared/uploads-remote.js";
import { attachPicker } from "../../shared/attach-picker.js";
import {
  AUDIENCES, INQUIRY_STATUS, MATERIAL_AUDIENCES, MAX_TITLE, MAX_BODY, INQUIRY_UPLOADS, MATERIAL_UPLOADS,
  materialListKey, inquiryListKey, materialLists, materialDetails, inquiryLists, inquiryDetails,
  moreMaterials, moreInquiries, isAppending, markMaterialRead, audienceLabel, communityMessage,
  communityManageState, checkCommunityManage,
  createInquiry, answerInquiry, createMaterial, updateMaterial, deleteMaterial, inquiryHref,
  createUncertain, INQUIRY_CREATE, MATERIAL_CREATE, answerOperation, isCode, commandMessage,
} from "../../shared/community-remote.js";

const SEARCH_DELAY = 300;
const AUDIENCE_LABEL = { all: "전체", common: "공통", professor: "교수", student: "학생" };
const AUDIENCE_COUNT = { all: "total", common: "common", professor: "professor", student: "student" };
const STATUS_LABEL = { all: "전체", answered: "답변 완료", unanswered: "답변 대기" };
const STATUS_COUNT = { all: "total", answered: "answered", unanswered: "unanswered" };
const UNSURE = "결과를 확인하지 못했습니다. 네트워크를 확인한 뒤 같은 내용으로 다시 보내세요. 이미 등록되었다면 같은 글을 돌려받습니다.";

const qnaListPath = () => hrefFor(find("31_community_qna_list"));
const materialListPath = () => hrefFor(find("32_community_material_list"));

/** Same frame as the other home dialogs (`home-dialog`). */
function dialogShell(title, body, options) {
  const node = baseDialog(title, body, options);
  node.classList.add("home-dialog");
  return node;
}

const missing = (error) => error instanceof ApiError && error.status === 404;

// ---------- memory per form (cleared when the session changes) ----------

const memos = new Map();
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  memos.clear();
});

function memoOf(key, initial = {}) {
  if (!memos.has(key)) memos.set(key, { title: "", body: "", loaded: false, ...initial });
  return memos.get(key);
}

// ---------- commands through data-act="srv-community" ----------

const commands = new WeakMap(); // button -> { run, op }
const inflight = new Set(); // operations being sent; a repainted button stays disabled

function cmd(label, kind, run, { op = null, attrs = {} } = {}) {
  const node = btn(label, { ...attrs, "data-act": "srv-community" }, kind);
  commands.set(node, { run, op });
  if (op && inflight.has(op)) {
    node.disabled = true;
    node.setAttribute("aria-busy", "true");
  }
  return node;
}

/** `srv-community` (home/actions.js). */
export function runCommunityCommand(node, api) {
  const entry = commands.get(node);
  if (!entry || node.disabled) return;
  void entry.run(node, api, entry.op);
}

/** Disables `node` with `aria-busy` while `work` runs; one operation never runs twice at once. */
async function sending(node, text, op, work) {
  if (node.disabled || (op && inflight.has(op))) return undefined;
  const label = node.textContent;
  if (op) inflight.add(op);
  node.disabled = true;
  node.setAttribute("aria-busy", "true");
  if (text) node.textContent = text;
  try {
    return await work();
  } finally {
    if (op) inflight.delete(op);
    if (node.isConnected) {
      node.disabled = false;
      node.removeAttribute("aria-busy");
      node.textContent = label;
    }
    // A repaint while the command ran drew a fresh button for the same operation, disabled; free it.
    if (op) {
      for (const other of document.querySelectorAll('[data-act="srv-community"][aria-busy="true"]')) {
        const entry = commands.get(other);
        if (other !== node && entry && entry.op === op) {
          other.disabled = false;
          other.removeAttribute("aria-busy");
        }
      }
    }
  }
}

function toast(api, text) {
  api.getState().toast = text;
  api.paintToast();
}

// ---------- small views ----------

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function prose(text, empty = "본문이 없습니다.") {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? el("p", { class: "tiny", text: empty }) : null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

/** Attached files of a parent outside any course (`GET /api/files/{fileId}/content`). */
function files(list) {
  const items = Array.isArray(list) ? list.filter((file) => file && file.fileId != null) : [];
  if (!items.length) return null;
  return el("div", { class: "srv-cq-files", role: "group", "aria-label": "첨부 파일" }, ...items.map((file) => {
    const { button, note } = downloadControl(null, file);
    return el("div", { class: "file-row" }, icon("file"),
      el("span", {}, el("b", { text: file.name || "파일" }), el("span", { class: "tiny", text: sizeText(file.sizeBytes) }), note),
      button);
  }));
}

function sayLine() {
  const node = el("p", { class: "tiny", role: "status", "aria-live": "polite" });
  node.say = (text, alert) => {
    node.setAttribute("role", alert ? "alert" : "status");
    node.className = alert ? "err" : "tiny";
    node.textContent = text || "";
  };
  return node;
}

function textInput(memo, name, { label, max, multiline = false, rows = "8", placeholder = "", required = false }) {
  const input = el(multiline ? "textarea" : "input", {
    ...(multiline ? { rows } : { type: "text" }),
    maxlength: String(max),
    placeholder,
    "aria-required": required ? "true" : null,
  });
  input.value = memo[name] || "";
  input.addEventListener("input", () => { memo[name] = input.value; });
  return el("label", { class: "field" }, el("span", { class: "field-label", text: label }), input);
}

function audienceSelect(memo) {
  const select = el("select", {}, ...MATERIAL_AUDIENCES.map((value) => el("option", { value, text: AUDIENCE_LABEL[value] })));
  select.value = MATERIAL_AUDIENCES.includes(memo.audience) ? memo.audience : "common";
  memo.audience = select.value;
  select.addEventListener("change", () => { memo.audience = select.value; });
  return el("label", { class: "field" }, el("span", { class: "field-label", text: "대상" }), select);
}

/** Two presses: the first arms the button, the second runs `work`. */
function deleteButton(label, op, work) {
  return cmd("삭제", "ghost", (node, api) => {
    if (node.dataset.armed !== "1") {
      node.dataset.armed = "1";
      node.textContent = "한 번 더 누르면 삭제";
      node.setAttribute("aria-label", `${label} 삭제, 한 번 더 누르면 삭제`);
      return undefined;
    }
    return sending(node, "삭제하는 중…", op, () => work(api));
  }, { op, attrs: { "aria-label": `${label} 삭제` } });
}

/** The current address with `changes` applied (null removes a parameter). */
function here(ctx, changes) {
  const params = new URLSearchParams(ctx.params ? ctx.params.toString() : "");
  for (const [name, value] of Object.entries(changes)) {
    if (value == null || value === "") params.delete(name);
    else params.set(name, String(value));
  }
  const q = params.toString();
  return `${location.pathname}${q ? `?${q}` : ""}`;
}

function searchBox(title, ctx, scope) {
  return el("label", { class: "search board-search" },
    icon("search"),
    el("input", {
      "data-name": "surfaceQuery",
      "data-scope": scope,
      "data-focus": scope,
      value: readQuery(ctx.state, scope),
      placeholder: "검색",
      "aria-label": `${title} 제목 검색`,
    }),
  );
}

function moreButton(key, run) {
  const button = btn(isAppending(key) ? "불러오는 중…" : "더 보기", {}, "secondary");
  button.disabled = isAppending(key);
  button.addEventListener("click", () => {
    if (button.disabled) return;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    button.textContent = "불러오는 중…";
    run().catch((error) => {
      if (!button.isConnected) return;
      button.disabled = false;
      button.removeAttribute("aria-busy");
      button.textContent = "더 보기";
      button.after(el("p", { class: "err", role: "alert", text: communityMessage(error) }));
    });
  });
  return el("div", { class: "btn-row" }, button);
}

function visitList(resource, scope, key, load) {
  const fresh = freshVisit(scope, key);
  if (fresh === "entry") load();
  else if (!resource.peek(key)) {
    if (fresh === "key") setTimeout(() => { if (!resource.peek(key)) load(); }, SEARCH_DELAY);
    else load();
  }
}

// ---------- lists ----------

/** The 알림마당 자료실 or 1:1 문의 list in backend mode; `head` is the page header built by home.js. */
export function remoteCommunityBoard(screen, ctx, head) {
  const body = el("section", { class: "board" }, head);
  if (backendStatus() === "anon") {
    body.append(backendPanel({ text: screen.board === "qna" ? "ASTRA 서버에 로그인하면 내 1:1 문의를 서버에서 불러옵니다." : "ASTRA 서버에 로그인하면 자료실을 서버에서 불러옵니다." }));
    return body;
  }
  const material = screen.board === "material";
  const scope = `home:board:${screen.board}`;
  const q = readQuery(ctx.state, scope).trim().slice(0, 100);
  const params = ctx.params || new URLSearchParams();
  // `?all=1` is the operator's view of everyone's inquiries; a non-operator gets 404 and their own list.
  const all = !material && params.get("all") === "1";
  const names = material ? AUDIENCES : INQUIRY_STATUS;
  const param = material ? "audience" : "status";
  const filter = names.includes(params.get(param)) ? params.get(param) : "all";
  const resource = material ? materialLists : inquiryLists;
  const key = material ? materialListKey(filter, q) : inquiryListKey(filter, q, all);
  const load = () => (material ? materialLists.load(key, filter, q) : inquiryLists.load(key, filter, q, all));
  const listPath = hrefFor(find(screen.id));
  const query = (extra) => {
    const next = new URLSearchParams();
    if (all) next.set("all", "1");
    for (const [name, value] of Object.entries(extra)) if (value != null && value !== "all") next.set(name, value);
    const text = next.toString();
    return `${listPath}${text ? `?${text}` : ""}`;
  };
  const chipHref = (value) => query({ [param]: value });
  const chips = el("div", { class: "filters", role: "toolbar", "aria-label": material ? "자료 대상" : "답변 상태" });
  const paintChips = (counts) => chips.replaceChildren(...names.map((value) => {
    const label = (material ? AUDIENCE_LABEL : STATUS_LABEL)[value];
    const count = counts ? counts[(material ? AUDIENCE_COUNT : STATUS_COUNT)[value]] : null;
    return go(chipHref(value), count != null ? `${label} ${count}` : label, { class: `chip${filter === value ? " on all" : ""}`, "aria-current": filter === value ? "true" : null });
  }));
  // Operator tools: the inquiry scope toggle or "자료 등록", shown when the list says `canManage`.
  const tools = el("div", { class: "srv-cq-tools" });
  const paintTools = (data) => {
    if (!data || data.canManage !== true) return tools.replaceChildren();
    if (material) {
      const href = `${listPath}?${new URLSearchParams({ pop: "material-write" })}`;
      return tools.replaceChildren(go(href, "자료 등록", { class: "btn btn-primary" }));
    }
    const mineHref = `${listPath}${filter !== "all" ? `?status=${filter}` : ""}`;
    const allHref = `${listPath}?${new URLSearchParams({ all: "1", ...(filter !== "all" ? { status: filter } : {}) })}`;
    return tools.replaceChildren(el("div", { class: "filters", role: "toolbar", "aria-label": "문의 범위" },
      go(mineHref, "내 문의", { class: `chip${all ? "" : " on all"}`, "aria-current": all ? null : "true" }),
      go(allHref, "전체 문의", { class: `chip${all ? " on all" : ""}`, "aria-current": all ? "true" : null }),
    ));
  };
  const list = el("div", { "aria-live": "polite" });
  body.append(tools, chips, searchBox(material ? "자료실" : "1:1 문의", ctx, scope), list);
  const itemHref = (postId) => {
    const next = new URLSearchParams({ ...(all ? { all: "1" } : {}), ...(filter !== "all" ? { [param]: filter } : {}), pop: material ? "material" : "inquiry", id: String(postId) });
    return `${listPath}?${next}`;
  };

  const paint = (entry) => {
    const data = entry && entry.data;
    if (all && !data && entry && missing(entry.error)) {
      // Not an operator (any more): the own list, without the toggle.
      if (!body.isConnected) return;
      const own = new URLSearchParams(params.toString());
      own.delete("all");
      body.replaceWith(remoteCommunityBoard(screen, { ...ctx, params: own }, head));
      return;
    }
    paintTools(data);
    paintChips(data ? data.counts : null);
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(loadingState(material ? "자료를 불러오는 중입니다" : "문의를 불러오는 중입니다"));
      else list.replaceChildren(errorState(material ? "자료를 불러오지 못했습니다" : "문의를 불러오지 못했습니다", communityMessage(entry.error, material ? "자료" : "문의"), retry(load)));
      return;
    }
    const stale = entry.error ? el("p", { class: "err", role: "alert", text: communityMessage(entry.error, material ? "자료" : "문의") }) : "";
    if (!data.items.length) {
      list.replaceChildren(stale, q || filter !== "all"
        ? emptyState("조건에 맞는 글이 없습니다", "다른 조건을 고르거나 검색어를 지워 보세요.")
        : emptyState("조회할 자료가 없습니다", material ? "등록된 자료가 없습니다." : all ? "받은 1:1 문의가 없습니다." : "보낸 1:1 문의가 없습니다."));
      return;
    }
    list.replaceChildren(
      stale,
      el("div", { class: "board-list" }, ...data.items.map((item) => {
        const href = itemHref(item.postId);
        const meta = material
          ? [audienceLabel(item.audience), koDate(item.publishedAt), `조회 ${item.viewCount ?? 0}`, item.read ? null : "안 읽음"]
          : [all ? item.authorName : null, item.answered ? `답변 완료${item.answeredAt ? ` · ${koDate(item.answeredAt)}` : ""}` : "답변 대기", koDate(item.createdAt), item.answerCount ? `답변 ${item.answerCount}` : null];
        return el("a", { class: "board-card", href, "data-go": href },
          el("b", { text: item.title }),
          el("span", { class: "tiny", text: meta.filter(Boolean).join(" · ") }),
          material && item.excerpt ? el("p", { text: item.excerpt }) : null,
        );
      })),
      data.nextCursor != null ? moreButton(key, () => (material ? moreMaterials(filter, q) : moreInquiries(filter, q, all))) : "",
    );
  };
  paint(resource.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    resource.watch(body, key, paint, () => { const entry = resource.peek(key); if (!entry || !entry.data || entry.data.pages === 1) load(); });
    visitList(resource, material ? "community-materials" : `community-inquiries${all ? "-all" : ""}`, key, load);
  });
  return body;
}

function idParam(ctx) {
  const id = Number(ctx.params && ctx.params.get("id"));
  return Number.isInteger(id) && id > 0 ? id : null;
}

// ---------- 자료실 detail ----------

/** `?pop=material&id=` over 자료실 in backend mode. */
export function remoteMaterialDialog(ctx) {
  const frame = el("div", { "aria-live": "polite" });
  const shell = () => dialogShell("자료실", frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: "ASTRA 서버에 로그인하면 자료를 볼 수 있습니다." }));
    return shell();
  }
  const id = idParam(ctx);
  if (!id) {
    frame.append(emptyState("자료를 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요."));
    return shell();
  }
  const load = () => materialDetails.load(id, id);
  const readNote = el("p", { class: "tiny", role: "status" });
  const sendRead = (item) => {
    if (!frame.isConnected || !item || item.read) return;
    markMaterialRead(id).catch((error) => {
      if (readNote.isConnected) readNote.textContent = `읽음 표시를 남기지 못했습니다. ${communityMessage(error)}`;
    });
  };
  const paint = (entry) => {
    const item = entry && entry.data;
    if (!item) {
      if (!entry || !entry.error) frame.replaceChildren(loadingState("자료를 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(emptyState("자료를 찾을 수 없습니다", "삭제되었거나 볼 수 있는 대상이 아닙니다."));
      else frame.replaceChildren(errorState("자료를 불러오지 못했습니다", communityMessage(entry.error), retry(load)));
      return;
    }
    const manage = item.canManage ? el("div", { class: "btn-row srv-cq-actions" },
      go(here(ctx, { pop: "material-write", id: String(id) }), "수정", { class: "btn btn-secondary" }),
      deleteButton(item.title, `material-delete:${id}`, async (api) => {
        try {
          await deleteMaterial(id);
          api.getState().toast = "자료를 삭제했습니다";
          api.navigate(materialListPath());
        } catch (error) {
          if (missing(error)) {
            api.getState().toast = "이미 삭제되었거나 찾을 수 없는 자료입니다";
            api.navigate(materialListPath());
            return;
          }
          toast(api, commandMessage(error, "자료"));
        }
      }),
    ) : null;
    frame.replaceChildren(
      el("h3", { text: item.title }),
      el("p", { class: "tiny", text: [audienceLabel(item.audience), koDateTime(item.publishedAt), `조회 ${item.viewCount ?? 0}`].filter(Boolean).join(" · ") }),
      prose(item.body),
      files(item.attachments),
      readNote,
      manage,
    );
    sendRead(item);
  };
  paint(materialDetails.peek(id));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    materialDetails.watch(frame, id, paint, null);
    if (freshVisit("community-material", String(id)) === "entry" || !materialDetails.peek(id)) load();
    else sendRead(materialDetails.peek(id).data);
  });
  return shell();
}

// ---------- 자료 등록·수정 (operators) ----------

/** `?pop=material-write` (new) and `?pop=material-write&id=` (edit) over 자료실 in backend mode. */
export function remoteMaterialWriteDialog(ctx) {
  const id = idParam(ctx);
  const frame = el("div", { class: "srv-cq-form" });
  const shell = () => dialogShell(id ? "자료 수정" : "자료 등록", frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: "ASTRA 서버에 로그인하면 자료를 올릴 수 있습니다." }));
    return shell();
  }
  const notAllowed = () => frame.replaceChildren(emptyState("자료는 알림마당 운영자만 올리고 고칠 수 있습니다", "목록에서 자료를 볼 수 있습니다.", go(materialListPath(), "자료실로", { class: "btn btn-secondary" })));
  const memoKey = `material-form:${id || "new"}`;
  const memo = memoOf(memoKey, { audience: "common" });
  const say = sayLine();
  let picker = null;
  let current = null; // the material being edited, as last read
  let submit = null;
  const refresh = () => {
    if (!submit || submit.getAttribute("aria-busy")) return;
    submit.disabled = Boolean((picker && picker.pending()) || (id && (!current || !current.canManage)));
  };

  const build = () => {
    picker = attachPicker({ memo, courseId: null, purpose: "community_material", max: MATERIAL_UPLOADS, existing: current ? current.attachments : [], onChange: refresh });
    submit = cmd("저장", "primary", (node, api, op) => sending(node, "저장하는 중…", op, () => save(api)), { op: `save:${memoKey}` });
    frame.replaceChildren(
      textInput(memo, "title", { label: "제목", max: MAX_TITLE, placeholder: "제목을 입력하세요", required: true }),
      audienceSelect(memo),
      textInput(memo, "body", { label: "내용", max: MAX_BODY, multiline: true, rows: "10", placeholder: "내용을 입력하세요" }),
      picker.node,
      say,
      el("div", { class: "btn-row srv-cq-actions" }, btn("취소", { "data-act": "close" }, "ghost"), submit),
    );
    refresh();
  };

  async function save(api) {
    const title = memo.title.trim();
    if (!title) return say.say("제목을 입력하세요.", true);
    if (picker.pending()) return say.say("첨부 파일 검사가 끝난 뒤 저장하세요.", true);
    const text = { title, body: memo.body.trim() ? memo.body : null, audience: memo.audience };
    try {
      let view;
      if (id) {
        if (!current) return say.say("자료를 불러온 뒤 저장하세요.", true);
        view = await updateMaterial(id, { ...text, ...(picker.changed() ? { attachments: picker.attachments() } : {}), version: current.version });
      } else {
        view = await createMaterial({ ...text, uploadIds: picker.uploadIds() });
      }
      memos.delete(memoKey);
      api.getState().toast = id ? "자료를 고쳤습니다" : "자료를 등록했습니다";
      api.navigate(`${materialListPath()}?${new URLSearchParams({ pop: "material", id: String(view.postId) })}`);
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (id && (isCode(error, 409, "version_conflict") || missing(error))) materialDetails.load(id, id);
      if (!id && missing(error)) return say.say("자료는 알림마당 운영자만 올릴 수 있습니다.", true);
      say.say(!id && createUncertain(MATERIAL_CREATE) ? `저장 ${UNSURE}` : commandMessage(error, "자료"), true);
    }
    return undefined;
  }

  if (!id) {
    // 등록 진입은 자료실·문의 목록의 `canManage`로 정한다. 아직 목록을 읽지 않았으면 자료실 첫 쪽을 한 번 읽는다.
    const paintNew = (manage) => {
      if (manage === false) notAllowed();
      else if (manage === true) { if (!submit) build(); }
      else frame.replaceChildren(errorState("권한을 확인하지 못했습니다", "잠시 후 다시 시도하세요.", retry(checkNew)));
    };
    const checkNew = () => {
      frame.replaceChildren(loadingState("권한을 확인하는 중입니다"));
      checkCommunityManage().then((manage) => { if (frame.isConnected) paintNew(manage); });
    };
    if (communityManageState() !== null) paintNew(communityManageState());
    else {
      frame.replaceChildren(loadingState("권한을 확인하는 중입니다"));
      queueMicrotask(() => { if (frame.isConnected) checkNew(); });
    }
    return shell();
  }
  const load = () => materialDetails.load(id, id);
  const paint = (entry) => {
    const item = entry && entry.data;
    if (!item) {
      if (current) {
        if (entry && entry.error) say.say(missing(entry.error) ? "삭제되었거나 고칠 수 없는 자료입니다." : communityMessage(entry.error), true);
        return;
      }
      if (!entry || !entry.error) frame.replaceChildren(loadingState("자료를 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(emptyState("자료를 찾을 수 없습니다", "삭제되었거나 고칠 수 있는 권한이 없습니다."));
      else frame.replaceChildren(errorState("자료를 불러오지 못했습니다", communityMessage(entry.error), retry(load)));
      return;
    }
    if (!item.canManage) {
      current = item;
      notAllowed();
      return;
    }
    const ids = (Array.isArray(item.attachments) ? item.attachments : []).map((file) => file.fileId);
    // A reload after 409 keeps typed text and choices; files someone else added stay attached.
    if (memo.keep instanceof Set && memo.seen instanceof Set) for (const fileId of ids) if (!memo.seen.has(fileId)) memo.keep.add(fileId);
    memo.seen = new Set(ids);
    if (!memo.loaded) Object.assign(memo, { title: item.title || "", body: item.body || "", audience: item.audience || "common", loaded: true });
    const changed = !current || current.version !== item.version;
    current = item;
    if (changed) build();
    refresh();
  };
  paint(materialDetails.peek(id));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    materialDetails.watch(frame, id, paint, null);
    if (freshVisit("community-material-edit", String(id)) === "entry" || !materialDetails.peek(id)) load();
  });
  return shell();
}

// ---------- 1:1 문의 detail and answer ----------

/** `?pop=inquiry&id=` over 1:1 문의 in backend mode: the question, its replies and, for operators, the answer form. */
export function remoteInquiryDialog(ctx) {
  const frame = el("div", { "aria-live": "polite" });
  const shell = () => dialogShell("1:1 문의", frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: "ASTRA 서버에 로그인하면 내 문의를 볼 수 있습니다." }));
    return shell();
  }
  const id = idParam(ctx);
  if (!id) {
    frame.append(emptyState("문의를 찾을 수 없습니다", "목록으로 돌아가 다시 선택해 주세요."));
    return shell();
  }
  const load = () => inquiryDetails.load(id, id);
  const paint = (entry) => {
    const item = entry && entry.data;
    if (!item) {
      if (!entry || !entry.error) frame.replaceChildren(loadingState("문의를 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(emptyState("문의를 찾을 수 없습니다", "본인이 쓴 문의만 볼 수 있습니다."));
      else frame.replaceChildren(errorState("문의를 불러오지 못했습니다", communityMessage(entry.error, "문의"), retry(load)));
      return;
    }
    const replies = Array.isArray(item.replies) ? item.replies : [];
    frame.replaceChildren(
      el("h3", { text: item.title }),
      el("p", { class: "tiny", text: [item.mine === false && item.authorName ? item.authorName : null, koDateTime(item.createdAt), item.answered ? "답변 완료" : "답변 대기"].filter(Boolean).join(" · ") }),
      prose(item.body),
      files(item.attachments),
      el("h3", { text: `답변 ${replies.length}` }),
      replies.length
        ? el("div", { class: "stack" }, ...replies.map((reply) => el("article", { class: "sheet" },
          el("p", { class: "tiny", text: [reply.authorName, koDateTime(reply.createdAt)].filter(Boolean).join(" · ") }),
          prose(reply.body, ""),
          files(reply.attachments),
        )))
        : el("p", { class: "tiny", text: item.canAnswer ? "아직 답변이 없습니다." : "아직 답변이 없습니다. 답변이 등록되면 여기에 표시됩니다." }),
      item.canAnswer ? answerForm(id) : null,
    );
  };
  paint(inquiryDetails.peek(id));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    inquiryDetails.watch(frame, id, paint, null);
    if (freshVisit("community-inquiry", String(id)) === "entry" || !inquiryDetails.peek(id)) load();
  });
  return shell();
}

function answerForm(id) {
  const memoKey = `inquiry-answer:${id}`;
  const memo = memoOf(memoKey);
  const say = sayLine();
  let submit = null;
  const picker = attachPicker({ memo, courseId: null, purpose: "inquiry_attachment", max: INQUIRY_UPLOADS, onChange: () => { if (submit && !submit.getAttribute("aria-busy")) submit.disabled = picker.pending(); } });
  submit = cmd("답변 등록", "primary", (node, api, op) => sending(node, "등록하는 중…", op, async () => {
    if (!memo.body.trim()) return say.say("답변 내용을 입력하세요.", true);
    if (picker.pending()) return say.say("첨부 파일 검사가 끝난 뒤 등록하세요.", true);
    try {
      await answerInquiry(id, { body: memo.body, uploadIds: picker.uploadIds() });
      memos.delete(memoKey);
      // Repaint once more so the form comes back empty under the new reply.
      inquiryDetails.patch((key) => key === id, () => {});
      toast(api, "답변을 등록했습니다");
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (missing(error)) inquiryDetails.load(id, id);
      say.say(createUncertain(answerOperation(id)) ? `등록 ${UNSURE}` : commandMessage(error, "답변"), true);
    }
    return undefined;
  }), { op: `answer:${id}` });
  submit.disabled = submit.disabled || picker.pending();
  return el("section", { class: "srv-cq-form", "aria-label": "답변 쓰기" },
    el("h3", { text: "답변 쓰기" }),
    textInput(memo, "body", { label: "답변", max: MAX_BODY, multiline: true, rows: "6", placeholder: "답변을 입력하세요", required: true }),
    picker.node,
    say,
    el("div", { class: "btn-row srv-cq-actions" }, submit),
  );
}

// ---------- 1:1 질문하기 ----------

/** `?pop=qna-write` over 1:1 문의 in backend mode: `POST /api/community/qna` with files. */
export function remoteInquiryWriteDialog() {
  const frame = el("div", { class: "srv-cq-form" });
  const shell = () => dialogShell("1:1 질문하기", frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: "ASTRA 서버에 로그인하면 1:1 문의를 보낼 수 있습니다." }));
    return shell();
  }
  const memoKey = "inquiry-form:new";
  const memo = memoOf(memoKey);
  const say = sayLine();
  let submit = null;
  const picker = attachPicker({ memo, courseId: null, purpose: "inquiry_attachment", max: INQUIRY_UPLOADS, onChange: () => { if (submit && !submit.getAttribute("aria-busy")) submit.disabled = picker.pending(); } });
  submit = cmd("등록", "primary", (node, api, op) => sending(node, "등록하는 중…", op, async () => {
    const title = memo.title.trim();
    if (!title) return say.say("제목을 입력하세요.", true);
    if (picker.pending()) return say.say("첨부 파일 검사가 끝난 뒤 등록하세요.", true);
    try {
      const view = await createInquiry({ title, body: memo.body.trim() ? memo.body : null, uploadIds: picker.uploadIds() });
      memos.delete(memoKey);
      api.getState().toast = "문의를 등록했습니다";
      api.navigate(inquiryHref(view.postId));
    } catch (error) {
      if (isCode(error, 409, "attachment_not_ready")) picker.recheck();
      if (createUncertain(INQUIRY_CREATE)) say.say(`등록 ${UNSURE}`, true);
      else say.say(missing(error) ? "이 계정으로는 1:1 문의를 보낼 수 없습니다." : commandMessage(error, "문의"), true);
    }
    return undefined;
  }), { op: "inquiry-create" });
  submit.disabled = submit.disabled || picker.pending();
  frame.append(
    el("p", { class: "tiny", text: "보낸 문의는 본인과 알림마당 운영자만 봅니다. 보낸 뒤에는 고치거나 지울 수 없습니다." }),
    textInput(memo, "title", { label: "제목", max: MAX_TITLE, placeholder: "제목을 입력하세요", required: true }),
    textInput(memo, "body", { label: "내용", max: MAX_BODY, multiline: true, rows: "10", placeholder: "문의 내용을 입력하세요" }),
    picker.node,
    say,
    el("div", { class: "btn-row srv-cq-actions" }, go(qnaListPath(), "취소", { class: "btn btn-ghost" }), submit),
  );
  return shell();
}
