// 파일함 (`?pop=files`) on the ASTRA backend: folders, sort, search, cursor pages, storage used,
// new folder, upload (quota refusal), rename (`If-Match`), all-or-nothing move, trash / restore /
// purge / empty, download, where a file was reused, and ZIP jobs. `anon` shows only the login
// strip. Folder, sort, search and selection live in memory for the session; server text goes in
// as text nodes only.
import { el } from "../../dom.js";
import { btn, field, dialogShell as baseDialog, emptyState, loadingState, errorState, badge } from "../../ui/components.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { backendStatus, subscribe } from "../../shared/backend.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { sizeText } from "../../shared/uploads-remote.js";
import { koDateTime } from "../../shared/home-remote.js";
import {
  listings, trash, listKey, TRASH, SORTS, SCAN_STATE, foldersIn, createFolder, uploadToFilebox, renameItem,
  moveItems, trashItems, restoreItems, purgeItems, emptyTrash, downloadItem, itemReferences,
  requestZip, zipStatus, downloadZip, fileboxMessage, PURPOSE_LABEL,
} from "../../shared/filebox-remote.js";

// replaceChildren() would turn a null child into the text "null"; skip absent parts.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false));
}

const ZIP_POLL_MS = 2000;
const SCAN_POLL_MS = 3000;
const SEARCH_DELAY_MS = 400;

function initialView() {
  return { folder: null, sort: "date", order: "desc", q: "", inTrash: false, selected: new Map(), zip: null, renaming: null, moving: false };
}
let view = initialView();
subscribe(() => { view = initialView(); });

function shell(body) {
  const node = baseDialog("파일함", body, { wide: true });
  node.classList.add("home-dialog");
  return node;
}

function busy(button, on, label) {
  button.disabled = on;
  if (on) button.setAttribute("aria-busy", "true");
  else button.removeAttribute("aria-busy");
  if (label) button.textContent = label;
}

function say(line, text, alert) {
  if (!line) return;
  line.setAttribute("role", alert ? "alert" : "status");
  line.textContent = text;
}

function isoTime(value) {
  // Instants come as UTC ISO strings; koDateTime shows them on the institution wall clock.
  return koDateTime(value) || "";
}

/** Dialog for `?pop=files`, or null when the backend is off (the sample file list stays). */
export function serverFileBox() {
  const status = backendStatus();
  if (status === "off") return null;
  if (status !== "ready") {
    return shell(backendPanel({ text: "ASTRA 서버에 로그인하면 내 파일함을 서버에서 불러옵니다." }));
  }
  return shell(fileBox());
}

function currentKey() {
  return view.inTrash ? TRASH : listKey(view.folder, view.sort, view.order, view.q);
}

function load(previous = null) {
  if (view.inTrash) return trash.load(TRASH, previous);
  return listings.load(currentKey(), view.folder, view.sort, view.order, view.q, previous);
}

function fileBox() {
  const line = el("p", { class: "acct-status", role: "status", "aria-live": "polite" });
  const list = el("div", { class: "fb-list", "aria-live": "polite" });
  const crumbs = el("nav", { class: "fb-crumbs", "aria-label": "폴더 위치" });
  const usage = el("div", { class: "fb-usage" });
  const actions = el("div", { class: "fb-actions" });
  const zipBox = el("div", { class: "fb-zip" });
  const picker = el("div", { class: "fb-picker" });
  const toolbar = el("div", { class: "fb-toolbar" });
  const tabs = el("div", { class: "filters" });
  const box = el("div", { class: "fb" }, tabs, usage, crumbs, toolbar, actions, picker, zipBox, list, line);

  let scanTimer = 0;
  const resource = () => (view.inTrash ? trash : listings);

  // Cache watches are keyed: bind one for each listing key this dialog shows. Each paints only
  // while its key is still the one on screen, and all stop once the dialog is closed.
  const bound = new Set();
  function bind() {
    const key = currentKey();
    if (bound.has(key) || !list.isConnected) return;
    bound.add(key);
    resource().watch(list, key, (entry) => {
      if (currentKey() === key) paint(entry);
    }, () => {
      if (currentKey() === key) load();
    });
  }

  function navigateTo(patch) {
    Object.assign(view, patch, { selected: new Map(), renaming: null, moving: false });
    pickerFolder = view.folder;
    say(line, "");
    paintChrome();
    bind();
    const entry = resource().peek(currentKey());
    paint(entry);
    if (!entry) load();
  }

  // ---- tabs, toolbar (built once so typing keeps focus) ----
  function paintTabs() {
    const tab = (label, on, trashView) => {
      const node = el("button", { type: "button", class: `chip${on ? " on all" : ""}`, "aria-pressed": on ? "true" : "false", text: label });
      node.addEventListener("click", () => { if (!on) navigateTo({ inTrash: trashView }); });
      return node;
    };
    fill(tabs, tab("내 파일", !view.inTrash, false), tab("휴지통", view.inTrash, true));
  }

  const search = el("input", { type: "search", value: view.q, maxlength: "100", placeholder: "이 폴더에서 이름 찾기" });
  let searchTimer = 0;
  search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      if (!search.isConnected) return;
      const q = search.value.trim();
      if (q !== view.q) navigateTo({ q });
    }, SEARCH_DELAY_MS);
  });
  const sort = el("select", {}, ...Object.entries(SORTS).map(([key, label]) => el("option", { value: key, text: label, selected: key === view.sort ? "" : null })));
  const order = el("select", {},
    el("option", { value: "desc", text: "내림차순", selected: view.order === "desc" ? "" : null }),
    el("option", { value: "asc", text: "오름차순", selected: view.order === "asc" ? "" : null }));
  sort.addEventListener("change", () => navigateTo({ sort: sort.value, order: sort.value === "name" ? "asc" : "desc" }));
  order.addEventListener("change", () => navigateTo({ order: order.value }));

  const folderName = el("input", { maxlength: "255", placeholder: "새 폴더 이름" });
  const makeFolder = btn("폴더 만들기", {}, "secondary");
  makeFolder.addEventListener("click", async () => {
    const name = folderName.value.trim();
    if (!name) return say(line, "폴더 이름을 입력하세요.", true);
    if (makeFolder.disabled) return;
    busy(makeFolder, true, "만드는 중…");
    try {
      await createFolder(view.folder, name);
      folderName.value = "";
      say(line, "폴더를 만들었습니다.");
      load();
    } catch (error) {
      say(line, fileboxMessage(error), true);
    } finally {
      busy(makeFolder, false, "폴더 만들기");
    }
  });

  const picked = el("input", { type: "file", multiple: "" });
  const upload = btn("올리기", {}, "primary");
  upload.addEventListener("click", async () => {
    const files = [...(picked.files || [])];
    if (!files.length) return say(line, "올릴 파일을 고르세요.", true);
    if (upload.disabled) return;
    busy(upload, true, "올리는 중…");
    let done = 0;
    try {
      for (const file of files) {
        say(line, `${file.name} 올리는 중입니다.`);
        await uploadToFilebox(file, view.folder);
        done += 1;
      }
      picked.value = "";
      say(line, `${done}개를 올렸습니다.`);
    } catch (error) {
      say(line, `${done ? `${done}개를 올린 뒤 멈췄습니다. ` : ""}${fileboxMessage(error)}`, true);
    } finally {
      busy(upload, false, "올리기");
      load();
    }
  });

  function paintToolbar() {
    fill(toolbar, view.inTrash ? "" : el("div", { class: "fb-toolbar__row" },
      field("검색", search), field("정렬", sort), field("순서", order)),
    view.inTrash ? "" : el("div", { class: "fb-toolbar__row" },
      field("새 폴더", folderName), makeFolder, field("파일", picked), upload));
  }

  function paintChrome() {
    paintTabs();
    paintToolbar();
    paintZip();
  }

  // ---- selection actions ----
  function selectedItems() {
    return [...view.selected.values()];
  }

  function paintActions(entry) {
    const items = selectedItems();
    if (!items.length) {
      fill(actions, el("p", { class: "note", text: view.inTrash ? "되돌리거나 영구 삭제할 항목을 고르세요." : "항목을 고르면 옮기기, 휴지통, ZIP 받기를 할 수 있습니다." }),
        view.inTrash && entry && entry.data && entry.data.items.length ? emptyButton() : "");
      return;
    }
    const count = el("span", { class: "tiny", text: `${items.length}개 선택` });
    if (view.inTrash) {
      const restore = btn("되돌리기", {}, "secondary");
      restore.addEventListener("click", () => command(restore, "되돌리는 중…", () => restoreItems(items.map((i) => i.id)), (r) => `${r.count}개를 되돌렸습니다.`));
      const purge = btn("영구 삭제", {}, "danger");
      armed(purge, "한 번 더 누르면 영구 삭제", () => command(purge, "지우는 중…", () => purgeItems(items.map((i) => i.id)), (r) => `${r.count}개를 영구 삭제했습니다. 다른 곳에 첨부한 파일은 남습니다.`));
      fill(actions, count, restore, purge, emptyButton());
      return;
    }
    const move = btn("옮기기", { "aria-expanded": view.moving ? "true" : "false" }, "secondary");
    move.addEventListener("click", () => {
      view.moving = !view.moving;
      pickerFolder = view.folder;
      paintActions(entry);
      paintPicker();
    });
    const toTrash = btn("휴지통으로", {}, "secondary");
    toTrash.addEventListener("click", () => command(toTrash, "옮기는 중…", () => trashItems(items.map((i) => i.id)), (r) => `${r.count}개를 휴지통에 넣었습니다. 폴더 안 항목도 함께 들어갑니다.`));
    const files = items.filter((item) => item.kind === "file");
    const zip = btn("ZIP으로 받기", {}, "secondary");
    zip.disabled = files.length !== items.length;
    zip.title = zip.disabled ? "폴더는 ZIP에 넣을 수 없습니다" : "";
    zip.addEventListener("click", () => startZip(zip, files));
    fill(actions, count, move, toTrash, zip);
  }

  function emptyButton() {
    const empty = btn("휴지통 비우기", {}, "danger");
    armed(empty, "한 번 더 누르면 모두 영구 삭제", () => command(empty, "비우는 중…", () => emptyTrash(), (r) => `휴지통에서 ${r.count}개를 영구 삭제했습니다.`));
    return empty;
  }

  function armed(button, label, run) {
    button.addEventListener("click", () => {
      if (button.disabled) return;
      if (button.dataset.armed !== "1") {
        button.dataset.armed = "1";
        button.textContent = label;
        return;
      }
      run();
    });
  }

  async function command(button, label, run, success) {
    if (button.disabled) return;
    const original = button.textContent;
    busy(button, true, label);
    say(line, "");
    try {
      const result = await run();
      view.selected = new Map();
      view.moving = false;
      say(line, success(result || { count: 0 }));
    } catch (error) {
      say(line, fileboxMessage(error), true);
      if (button.isConnected) busy(button, false, original);
    } finally {
      view.moving = false;
      picker.replaceChildren();
      load();
    }
  }

  // ---- move target picker ----
  let pickerFolder = null;
  function paintPicker() {
    if (!view.moving || view.inTrash) {
      picker.replaceChildren();
      return;
    }
    const moving = new Set(selectedItems().map((item) => item.id));
    fill(picker, loadingState("폴더를 불러오는 중입니다"));
    const shown = pickerFolder;
    foldersIn(shown).then(({ path, folders }) => {
      if (!picker.isConnected || shown !== pickerFolder || !view.moving) return;
      const up = btn("위 폴더", {}, "ghost");
      up.disabled = !shown;
      up.addEventListener("click", () => {
        pickerFolder = path.length > 1 ? path[path.length - 2].id : null;
        paintPicker();
      });
      const here = btn("여기로 옮기기", {}, "primary");
      here.addEventListener("click", () => command(here, "옮기는 중…", () => moveItems(selectedItems(), shown), (r) => `${r.count}개를 옮겼습니다.`));
      const rows = folders.map((folder) => {
        const open = btn(folder.name, { "aria-label": `${folder.name} 폴더 열기` }, "ghost");
        if (moving.has(folder.id)) {
          open.disabled = true;
          open.title = "옮기는 폴더입니다";
        }
        open.addEventListener("click", () => {
          pickerFolder = folder.id;
          paintPicker();
        });
        return el("li", {}, open);
      });
      fill(picker, el("div", { class: "fb-picker__box", role: "group", "aria-label": "옮길 폴더 고르기" },
        el("p", { class: "tiny", text: `옮길 곳: ${["내 파일함", ...path.map((p) => p.name)].join(" / ")}` }),
        rows.length ? el("ul", { class: "fb-picker__list" }, ...rows) : el("p", { class: "note", text: "아래 폴더가 없습니다." }),
        el("div", { class: "btn-row" }, up, here),
      ));
    }, (error) => {
      if (picker.isConnected) fill(picker, errorState("폴더를 불러오지 못했습니다", fileboxMessage(error)));
    });
  }

  // ---- ZIP ----
  let zipTimer = 0;
  async function startZip(button, files) {
    if (button.disabled) return;
    busy(button, true, "요청하는 중…");
    say(line, "");
    try {
      view.zip = await requestZip(files.map((item) => item.id));
      paintZip();
      pollZip();
    } catch (error) {
      say(line, fileboxMessage(error), true);
    } finally {
      if (button.isConnected) busy(button, false, "ZIP으로 받기");
    }
  }

  function pollZip() {
    clearTimeout(zipTimer);
    const job = view.zip;
    if (!job || (job.state !== "requested" && job.state !== "building")) return;
    zipTimer = setTimeout(() => {
      if (!zipBox.isConnected || view.zip !== job) return;
      zipStatus(job.jobId).then((next) => {
        if (view.zip !== job) return;
        view.zip = next;
        paintZip();
        pollZip();
      }, (error) => {
        if (zipBox.isConnected) say(line, fileboxMessage(error), true);
        pollZip();
      });
    }, ZIP_POLL_MS);
  }

  function paintZip() {
    const job = view.zip;
    if (!job || view.inTrash) {
      zipBox.replaceChildren();
      return;
    }
    const text = {
      requested: "ZIP을 만들 차례를 기다리고 있습니다.",
      building: "ZIP을 만들고 있습니다.",
      ready: `ZIP이 준비되었습니다 (${sizeText(job.sizeBytes) || "크기 정보 없음"}). ${job.expiresAt ? `${isoTime(job.expiresAt)}까지` : "2시간 동안"} 받을 수 있습니다.`,
      failed: job.failure === "item_unavailable" ? "고른 파일 중 휴지통에 있거나 사라진 파일이 있어 ZIP을 만들지 못했습니다." : "ZIP을 만들지 못했습니다. 다시 요청하세요.",
      expired: "ZIP을 받을 수 있는 시간이 지났습니다. 다시 요청하세요.",
    }[job.state] || "ZIP 상태를 확인하지 못했습니다.";
    const close = btn("닫기", { "aria-label": "ZIP 안내 닫기" }, "ghost");
    close.addEventListener("click", () => {
      view.zip = null;
      paintZip();
    });
    let get = null;
    if (job.state === "ready") {
      get = btn("ZIP 내려받기", {}, "primary");
      get.addEventListener("click", async () => {
        busy(get, true, "내려받는 중…");
        try {
          await downloadZip(job);
        } catch (error) {
          say(line, fileboxMessage(error), true);
          if (error.status === 410) {
            view.zip = { ...job, state: "expired" };
            paintZip();
            return;
          }
        }
        if (get.isConnected) busy(get, false, "ZIP 내려받기");
      });
    }
    fill(zipBox, el("div", { class: "fb-zip__box", role: "status", "aria-live": "polite" },
      el("span", { text: `파일 ${job.itemCount}개 · ${text}` }),
      job.state === "requested" || job.state === "building" ? el("progress", { "aria-label": "ZIP 만드는 중" }) : null,
      get, close));
  }

  // ---- list ----
  function paintCrumbs(data) {
    if (view.inTrash) {
      crumbs.replaceChildren();
      return;
    }
    const path = (data && data.path) || [];
    const step = (label, id, last) => {
      if (last) return el("li", {}, el("span", { "aria-current": "page", text: label }));
      const link = btn(label, {}, "ghost");
      link.addEventListener("click", () => navigateTo({ folder: id, q: "" }));
      return el("li", {}, link);
    };
    fill(crumbs, el("ol", {},
      step("내 파일함", null, !path.length),
      ...path.map((entry, index) => step(entry.name, entry.id, index === path.length - 1))));
  }

  function paintUsage(data) {
    const u = data && data.usage;
    if (!u || view.inTrash) {
      fill(usage, view.inTrash ? el("p", { class: "note", text: "휴지통 파일도 영구 삭제할 때까지 용량을 차지합니다." }) : "");
      return;
    }
    fill(usage,
      el("span", { class: "tiny", text: `사용 ${sizeText(u.usedBytes) || "0KB"} / ${sizeText(u.quotaBytes)} · 항목 ${u.itemCount}/${u.maxItems}` }),
      el("progress", { max: String(u.quotaBytes || 1), value: String(Math.min(u.usedBytes, u.quotaBytes || 0)), "aria-label": "파일함 사용량" }),
    );
  }

  function toggle(item, on) {
    if (on) view.selected.set(item.id, item);
    else view.selected.delete(item.id);
  }

  function row(item, entry) {
    const check = el("input", { type: "checkbox", "aria-label": `${item.name} 고르기`, checked: view.selected.has(item.id) ? "" : null });
    check.addEventListener("change", () => {
      toggle(item, check.checked);
      view.moving = false;
      paintActions(entry);
      paintPicker();
    });
    if (view.inTrash) {
      return el("li", { class: "fb-row" }, check,
        el("span", { class: "fb-row__main" },
          el("b", { text: item.name }),
          el("span", { class: "tiny", text: [item.kind === "folder" ? `폴더 · 항목 ${item.itemCount}개` : "파일", sizeText(item.sizeBytes), item.trashedAt ? `${isoTime(item.trashedAt)} 삭제` : ""].filter(Boolean).join(" · ") })));
    }
    const main = item.kind === "folder"
      ? (() => {
        const open = btn(item.name, { class: "btn btn-ghost fb-row__open", "aria-label": `${item.name} 폴더 열기` }, "ghost");
        open.addEventListener("click", () => navigateTo({ folder: item.id, q: "" }));
        return open;
      })()
      : el("b", { text: item.name });
    const meta = [item.kind === "folder" ? "폴더" : (item.extension || "파일").toUpperCase(), sizeText(item.sizeBytes), isoTime(item.createdAt)].filter(Boolean).join(" · ");
    const scan = item.kind === "file" && SCAN_STATE[item.state] ? badge(SCAN_STATE[item.state], item.state === "rejected" ? "danger" : "neutral") : null;
    const tools = el("span", { class: "fb-row__tools" });
    const rename = btn("이름 바꾸기", { "aria-label": `${item.name} 이름 바꾸기` }, "ghost");
    rename.addEventListener("click", () => {
      view.renaming = view.renaming && view.renaming.id === item.id ? null : { id: item.id, name: item.name };
      paint(listings.peek(currentKey()));
    });
    tools.append(rename);
    const extra = el("div", { class: "fb-row__extra" });
    if (item.kind === "file") {
      const download = btn("내려받기", { "aria-label": `${item.name} 내려받기` }, "ghost");
      download.disabled = item.state !== "ready";
      download.addEventListener("click", async () => {
        busy(download, true, "내려받는 중…");
        try {
          await downloadItem(item);
        } catch (error) {
          say(line, fileboxMessage(error), true);
        } finally {
          if (download.isConnected) busy(download, false, "내려받기");
        }
      });
      const refs = btn("연결된 곳", { "aria-label": `${item.name}을 첨부한 곳`, "aria-expanded": "false" }, "ghost");
      refs.addEventListener("click", async () => {
        if (refs.getAttribute("aria-expanded") === "true") {
          refs.setAttribute("aria-expanded", "false");
          extra.replaceChildren();
          return;
        }
        refs.setAttribute("aria-expanded", "true");
        fill(extra, el("p", { class: "tiny", text: "불러오는 중입니다." }));
        try {
          const list = await itemReferences(item.id);
          if (!extra.isConnected) return;
          fill(extra, list.length
            ? el("ul", { class: "fb-refs" }, ...list.map((ref) => el("li", { text: `${ref.courseName || "과목"} · ${PURPOSE_LABEL[ref.purpose] || ref.purpose || "첨부"} · ${isoTime(ref.attachedAt)}` })))
            : el("p", { class: "tiny", text: "이 파일을 첨부한 곳이 없습니다." }));
        } catch (error) {
          if (extra.isConnected) fill(extra, el("p", { class: "tiny", role: "alert", text: fileboxMessage(error) }));
        }
      });
      tools.append(download, refs);
    }
    if (view.renaming && view.renaming.id === item.id) extra.append(renameForm(item));
    return el("li", { class: "fb-row" }, check,
      el("span", { class: "fb-row__main" }, main, el("span", { class: "tiny", text: meta }), scan),
      tools, extra);
  }

  function renameForm(item) {
    const draft = view.renaming;
    const input = el("input", { value: draft.name, maxlength: "255", "aria-label": `${item.name}의 새 이름` });
    input.addEventListener("input", () => { draft.name = input.value; });
    const save = btn("저장", {}, "primary");
    const send = async () => {
      const name = input.value.trim();
      if (!name || save.disabled) return;
      busy(save, true, "저장하는 중…");
      try {
        await renameItem(item, name);
        view.renaming = null;
        say(line, "이름을 바꿨습니다.");
      } catch (error) {
        // Stale version: the reload below brings the new version; the typed name stays in the draft.
        say(line, fileboxMessage(error), true);
        if (save.isConnected) busy(save, false, "저장");
      } finally {
        load();
      }
    };
    save.addEventListener("click", send);
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        send();
      }
    });
    queueMicrotask(() => { if (input.isConnected) input.focus(); });
    return el("div", { class: "fb-rename" }, input, save,
      el("p", { class: "tiny", text: item.kind === "file" ? "파일 확장자는 바꿀 수 없습니다." : "" }));
  }

  function paint(entry) {
    if (!entry || (!entry.data && !entry.error)) {
      fill(list, loadingState(view.inTrash ? "휴지통을 불러오는 중입니다" : "파일함을 불러오는 중입니다"));
      paintActions(null);
      return;
    }
    if (!entry.data) {
      const again = btn("다시 불러오기", {}, "ghost");
      again.addEventListener("click", () => load());
      fill(list, errorState("파일함을 불러오지 못했습니다", fileboxMessage(entry.error), again));
      // A folder that no longer exists (or is someone else's) sends the view back to the root.
      if (entry.error && entry.error.status === 404 && view.folder) {
        const root = btn("내 파일함으로", {}, "secondary");
        root.addEventListener("click", () => navigateTo({ folder: null, q: "" }));
        list.append(root);
      }
      paintActions(null);
      return;
    }
    const data = entry.data;
    // Keep the selection to items still listed, with their latest version for the move.
    const byId = new Map(data.items.map((item) => [item.id, item]));
    for (const id of [...view.selected.keys()]) {
      if (byId.has(id)) view.selected.set(id, byId.get(id));
      else view.selected.delete(id);
    }
    paintCrumbs(data);
    paintUsage(data);
    paintActions(entry);
    const more = data.nextCursor ? btn(entry.loading ? "불러오는 중…" : "더 보기", {}, "secondary") : null;
    if (more) {
      more.disabled = Boolean(entry.loading);
      more.addEventListener("click", () => {
        busy(more, true, "불러오는 중…");
        load(data);
      });
    }
    fill(list,
      data.items.length
        ? el("ul", { class: "fb-rows" }, ...data.items.map((item) => row(item, entry)))
        : view.inTrash ? emptyState("휴지통이 비어 있습니다")
          : view.q ? emptyState("찾는 이름의 항목이 없습니다", "이 폴더 바로 아래 항목의 이름에서만 찾습니다.")
            : emptyState("이 폴더에 항목이 없습니다", "파일을 올리거나 폴더를 만드세요."),
      more ? el("div", { class: "btn-row" }, more) : null,
    );
    // Files still in the malware scan: look again shortly while the dialog is open.
    clearTimeout(scanTimer);
    if (!view.inTrash && data.items.some((item) => item.state === "scanning")) {
      scanTimer = setTimeout(() => { if (list.isConnected) load(); }, SCAN_POLL_MS);
    }
  }

  paintChrome();
  paint(resource().peek(currentKey()));
  queueMicrotask(() => {
    if (!box.isConnected) return;
    bind();
    // A new history entry (the dialog was opened) reloads; a plain re-render paints from the cache.
    if (freshVisit("filebox", "files") === "entry" || !resource().peek(currentKey())) load();
    pollZip();
  });
  return box;
}
