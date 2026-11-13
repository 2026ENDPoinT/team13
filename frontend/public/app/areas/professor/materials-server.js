// The professor's server material list (강의자료 → 자료 등록, backend `ready`): one entry per
// material with edit (title, week, description, publish, attachment set keep/add) and delete,
// both with `If-Match` from the last read, and the summary job panel of each of its PDFs.
// Typed edits live in memory per material, so a 409/428 reload keeps them.
import { el, icon } from "../../dom.js";
import { btn, field } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { courseIdFor, roleIn, subscribe } from "../../shared/backend.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { rememberMaterials, saveRemoteMaterialFromUploads } from "../../shared/course-pdfs-remote.js";
import {
  materialLists, materialKey, materialsOf, materialChange, updateMaterial, deleteMaterial, isStaleVersion, materialMessage,
} from "../../shared/materials-remote.js";
import { forgetMaterialList } from "../../shared/materials-remote.js";
import { uploadFile, awaitScan, discardUpload, uploadFailure, sizeText } from "../../shared/uploads-remote.js";
import { fileboxAttach } from "../../shared/filebox-attach.js";
import { go, emptyState, loadingState, errorState } from "../../ui/components.js";
import { backendStatus, backendState } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { routeFor } from "../../shared/routes.js";
import { koDateTime } from "../../shared/home-remote.js";
import { downloadControl } from "../../shared/uploads-remote.js";
import { serverPreview } from "../../shared/preview-remote.js";
import { courseById } from "../../data.js";
import { llmConsentSection } from "./llm-consent-server.js";

const MAX_FILES = 10;
const UPLOAD_STATE = {
  sending: "올리는 중",
  quarantined: "검사 중",
  clean: "첨부 준비됨",
  rejected: "서버 검사를 통과하지 못했습니다. 이 파일은 붙일 수 없습니다.",
  slow: "검사가 오래 걸리고 있습니다. 잠시 후 다시 올려 보세요.",
  gone: "더 이상 쓸 수 없는 업로드입니다. 다시 올리세요.",
};

// activityId -> { open, title, weekNo, description, published, keep: Set<fileId>, adds: [...], note, alert, armed }
const edits = new Map();
const openEdits = new Set(); // 수정 칸을 펼친 채로 열 자료(activityId)
let session = "";
subscribe((next) => {
  const id = `${next.status}:${next.me ? next.me.userId : ""}`;
  if (id === session) return;
  session = id;
  edits.clear();
  registrations.clear();
  openEdits.clear();
});

// New material registration (자료 등록 form): file box picks per course key, kept across re-renders.
// course key -> { uploads: [{ name, size, state, uploadId, message }], busy }
const registrations = new Map();

const controllers = new WeakMap(); // editor node -> { save(api), remove(node, api) }

function fresh(material) {
  return {
    open: false,
    title: material.title,
    weekNo: String(material.weekNo),
    description: material.description || "",
    published: material.published !== false,
    keep: new Set((material.files || []).map((file) => file.fileId)),
    adds: [],
    note: "",
    alert: false,
    latest: null,
  };
}

/**
 * The list section. `summaryJobPanel(record)` is professor.js's per-PDF summary control.
 * `open`(activityId)은 강의실 주별 보기의 `수정`에서 온 자료로, 그 자료와 수정 칸을 펼쳐 둔다.
 */
export function serverMaterialList(course, summaryJobPanel, { open } = {}) {
  const courseId = courseIdFor(course.id);
  const instructor = roleIn(course.id) === "INSTRUCTOR";
  const list = el("div", { class: "stack", "aria-live": "polite" }, el("p", { class: "pf-sub", text: "서버에 등록된 자료를 불러오는 중입니다." }));
  const section = el("section", { class: "sheet", "aria-label": "ASTRA 서버에 등록된 자료" },
    el("h2", { text: "서버에 등록된 자료" }),
    el("p", { class: "pf-sub", text: instructor
      ? "수정과 삭제는 마지막으로 불러온 버전을 기준으로 합니다. 그 사이 다른 곳에서 고쳤으면 아무것도 바꾸지 않고 최신 내용을 불러옵니다. 삭제한 자료의 PDF는 학생 학습실에서도 바로 사라집니다."
      : "이 과목을 담당하는 교수 계정으로 로그인해야 자료를 등록·수정하고 요약을 만들 수 있습니다." }),
    list,
  );
  if (!courseId) {
    list.replaceChildren(el("p", { class: "pf-sub", text: "이 과목은 ASTRA 서버에 없습니다." }));
    return section;
  }
  const key = materialKey(courseId);
  const load = () => materialLists.load(key, courseId);
  const opened = new Set();
  let first = true;
  const openId = Number(open);
  if (Number.isInteger(openId) && openId > 0) {
    opened.add(openId);
    first = false;
    const memo = edits.get(openId);
    if (memo) memo.open = true;
    else openEdits.add(openId);
  }

  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) list.replaceChildren(el("p", { class: "pf-sub", role: "status", text: "서버에 등록된 자료를 불러오는 중입니다." }));
      else {
        const again = btn("다시 불러오기", {}, "ghost");
        again.addEventListener("click", () => {
          again.disabled = true;
          load();
        });
        list.replaceChildren(el("p", { class: "err", role: "alert", text: materialMessage(entry.error) }), again);
      }
      return;
    }
    const materials = materialsOf(data);
    const records = rememberMaterials(materials, course.id, courseId);
    if (!materials.length) {
      list.replaceChildren(el("p", { class: "pf-sub", text: "아직 서버에 등록된 자료가 없습니다." }));
      return;
    }
    if (first && !opened.size) opened.add(materials[0].activityId);
    first = false;
    list.replaceChildren(
      entry.error ? el("p", { class: "err", role: "alert", text: materialMessage(entry.error) }) : "",
      ...materials.map((material) => {
        const files = Array.isArray(material.files) ? material.files : [];
        const details = el("details", { class: "pf-server-pdf", open: opened.has(material.activityId) ? true : null },
          el("summary", {},
            el("b", { text: material.title }),
            el("span", { class: "pf-meta", text: ` · ${material.weekNo}주차 · 파일 ${files.length}개${material.published ? "" : " · 비공개"}` }),
          ),
        );
        let mounted = false;
        const mount = () => {
          if (details.open) opened.add(material.activityId);
          else opened.delete(material.activityId);
          if (mounted || !details.open) return;
          mounted = true;
          if (instructor) details.append(editor(course, courseId, material, load));
          for (const record of records.filter((item) => item.activityId === material.activityId)) {
            details.append(el("div", { class: "pf-summary" }, el("h3", { text: record.fileName }), summaryJobPanel(record)));
          }
        };
        details.addEventListener("toggle", mount);
        queueMicrotask(mount);
        return details;
      }),
    );
  };
  paint(materialLists.peek(key));
  // form() builds every form; only the attached one loads.
  queueMicrotask(() => {
    if (!section.isConnected) return;
    materialLists.watch(section, key, paint, null);
    if (freshVisit("pf-materials", key) === "entry" || !materialLists.peek(key)) load();
  });
  return section;
}

function editor(course, courseId, material, reload) {
  const id = material.activityId;
  if (!edits.has(id)) {
    edits.set(id, fresh(material));
    if (openEdits.delete(id)) edits.get(id).open = true;
  }
  const memo = edits.get(id);
  const files = Array.isArray(material.files) ? material.files : [];
  // A file the server no longer has cannot be kept.
  for (const fileId of [...memo.keep]) if (!files.some((file) => file.fileId === fileId)) memo.keep.delete(fileId);
  let busy = false;

  const status = el("p", { class: memo.alert ? "err" : "pf-sub", role: memo.alert ? "alert" : "status", "aria-live": "polite", text: memo.note });
  const say = (text, alert) => {
    memo.note = text || "";
    memo.alert = Boolean(alert);
    status.className = alert ? "err" : "pf-sub";
    status.setAttribute("role", alert ? "alert" : "status");
    status.textContent = memo.note;
  };
  const title = el("input", { maxlength: "300", value: memo.title, "aria-label": "자료명" });
  const week = el("input", { type: "number", min: "1", max: "60", value: memo.weekNo, "aria-label": "주차" });
  const description = el("textarea", { rows: "4", maxlength: "10000", "aria-label": "설명" });
  description.value = memo.description;
  const published = el("input", { type: "checkbox" });
  published.checked = memo.published;
  title.addEventListener("input", () => { memo.title = title.value; });
  week.addEventListener("input", () => { memo.weekNo = week.value; });
  description.addEventListener("input", () => { memo.description = description.value; });
  published.addEventListener("change", () => { memo.published = published.checked; });

  const keepList = el("div", { class: "stack" }, ...files.map((file) => {
    const box = el("input", { type: "checkbox" });
    box.checked = memo.keep.has(file.fileId);
    box.addEventListener("change", () => {
      if (box.checked) memo.keep.add(file.fileId);
      else memo.keep.delete(file.fileId);
    });
    return el("label", { class: "choice pf-check" }, box, el("span", { text: `${file.name} · ${sizeText(file.sizeBytes)} 유지` }));
  }));
  const addList = el("div", { class: "stack" });
  const picker = el("input", { type: "file", multiple: true, "aria-label": "추가할 파일" });

  const pending = () => memo.adds.some((entry) => entry.state === "sending" || entry.state === "quarantined");
  const paintAdds = () => {
    addList.replaceChildren(...memo.adds.map((entry) => {
      const remove = btn("빼기", { "aria-label": `${entry.name} 빼기` }, "ghost");
      remove.disabled = busy;
      remove.addEventListener("click", () => {
        memo.adds = memo.adds.filter((item) => item !== entry);
        if (entry.uploadId) discardUpload(entry.uploadId);
        paintAdds();
      });
      const state = entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state] || UPLOAD_STATE.gone;
      return el("div", { class: "pf-activity" }, el("span", { class: "pf-activity-copy" }, icon("file"),
        el("b", { text: entry.name }), el("span", { class: "pf-meta", text: ` · ${[sizeText(entry.size), state].filter(Boolean).join(" · ")}` })), remove);
    }));
  };
  const known = (state) => (state === "clean" || state === "quarantined" || state === "rejected" ? state : "gone");
  const repaint = () => { if (addList.isConnected) paintAdds(); };
  picker.addEventListener("change", (event) => {
    // Handled here: the app-wide file listener would remember the name in saved sample state.
    event.stopPropagation();
    const picked = [...(picker.files || [])];
    picker.value = "";
    for (const file of picked) {
      const entry = { name: String(file.name || "file"), size: file.size, state: "sending", uploadId: null, message: "" };
      memo.adds.push(entry);
      uploadFile(file, courseId, "lecture_material").then((view) => {
        entry.uploadId = view.uploadId;
        entry.state = known(view.state);
        repaint();
        if (entry.state !== "quarantined") return null;
        return awaitScan(view, {
          alive: () => memo.adds.includes(entry),
          onState: (next) => { entry.state = known(next.state); repaint(); },
        }).then((last) => {
          if (last && last.state === "quarantined") entry.state = "slow";
          repaint();
        });
      }).catch((error) => {
        entry.state = "failed";
        entry.message = uploadFailure(error);
        repaint();
      });
    }
    paintAdds();
  });
  // Files picked from the file box join the same add list, wait for the scan like an upload and go out
  // as `attachments.add`; kept files count against MAX_FILES too.
  const fromBox = fileboxAttach({
    courseId,
    purpose: "lecture_material",
    limit: MAX_FILES,
    room: () => MAX_FILES - memo.keep.size - memo.adds.filter((entry) => !["rejected", "failed", "gone"].includes(entry.state)).length,
    onAttach: (views) => {
      for (const view of views) {
        const entry = { name: String(view.name || "file"), size: view.sizeBytes, state: known(view.state), uploadId: view.uploadId, message: "" };
        memo.adds.push(entry);
        if (entry.state !== "quarantined") continue;
        awaitScan(view, {
          alive: () => memo.adds.includes(entry),
          onState: (next) => { entry.state = known(next.state); repaint(); },
        }).then((last) => {
          if (last && last.state === "quarantined") entry.state = "slow";
          repaint();
        }, (error) => {
          entry.state = "failed";
          entry.message = uploadFailure(error);
          repaint();
        });
      }
      paintAdds();
    },
  });
  paintAdds();

  const save = btn("수정 저장", { "data-act": "pf-material-save" }, "primary");
  const remove = btn("자료 삭제", { "data-act": "pf-material-delete", "aria-label": `${material.title} 삭제` }, "danger");
  const form = el("div", { class: "pf-form" },
    el("div", { class: "span-2" }, field("자료명", title)),
    field("주차", week),
    el("label", { class: "choice pf-check" }, published, el("span", { text: "학생에게 공개" })),
    el("div", { class: "span-2" }, field("설명", description)),
    el("div", { class: "span-2" }, el("p", { class: "pf-sub", text: "첨부 파일 (체크를 풀면 저장할 때 이 자료에서 빠지고 파일도 지워집니다)" }), keepList),
    el("div", { class: "span-2" }, field("파일 추가", picker), fromBox ? fromBox.node : null, addList),
    el("div", { class: "span-2" }, el("div", { "data-material-latest": "" }, memo.latest ? latestView(memo.latest) : null)),
    el("div", { class: "span-2" }, status),
    el("div", { class: "span-2" }, el("div", { class: "pf-actions" }, save, remove)),
  );
  const panel = el("details", { class: "pf-material-edit", open: memo.open ? true : null },
    el("summary", {}, el("b", { text: "자료 수정·삭제" }), el("span", { class: "pf-meta", text: ` · 버전 ${material.version}` })),
    form,
  );
  panel.addEventListener("toggle", () => { memo.open = panel.open; });
  const controls = () => [title, week, description, published, picker, save, remove, ...keepList.querySelectorAll("input")];
  const setBusy = (on) => {
    busy = on;
    controls().forEach((control) => { control.disabled = on; });
    if (fromBox) fromBox.setDisabled(on);
    panel.setAttribute("aria-busy", on ? "true" : "false");
    paintAdds();
  };

  // After a stale write: read the list again; the typed state stays in `edits`.
  const reloadKeeping = (message) => {
    say(message, true);
    reload();
  };

  controllers.set(panel, {
    async save(api) {
      if (busy) return;
      const text = title.value.trim();
      const weekNo = Number(week.value);
      if (!text || text.length > 300) return say("자료명은 1자 이상 300자 이하입니다.", true);
      if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > 60) return say("주차는 1부터 60 사이입니다.", true);
      if (description.value.length > 10000) return say("설명은 10,000자 이하입니다.", true);
      if (pending()) return say("추가한 파일의 검사가 끝난 뒤 저장하세요.", true);
      const add = memo.adds.filter((entry) => entry.state === "clean").map((entry) => entry.uploadId);
      if (memo.keep.size + add.length > MAX_FILES) return say(`첨부는 ${MAX_FILES}개까지입니다.`, true);
      setBusy(true);
      save.textContent = "저장하는 중…";
      say("ASTRA 서버에 저장하고 있습니다.");
      try {
        await updateMaterial(courseId, id, material.version, materialChange(material, {
          title: text, weekNo, description: description.value, published: published.checked, keep: memo.keep, add,
        }));
        edits.delete(id);
        api.getState().toast = "자료를 수정했습니다";
        api.paintToast();
        reload();
      } catch (error) {
        if (isStaleVersion(error)) {
          memo.latest = { title: "", pending: true };
          reloadKeeping(materialMessage(error));
        } else if (error instanceof ApiError && error.status === 404) reloadKeeping(materialMessage(error));
        else say(materialMessage(error), true);
      } finally {
        if (panel.isConnected) {
          setBusy(false);
          save.textContent = "수정 저장";
        }
      }
    },
    /** Two presses: the first arms the button, the second sends DELETE with the last-read version. */
    async remove(api) {
      if (busy) return;
      if (remove.dataset.armed !== "1") {
        remove.dataset.armed = "1";
        remove.textContent = "한 번 더 누르면 삭제";
        say("삭제하면 이 자료와 파일, 쪽별 요약이 학생과 교수 모두에게서 바로 사라지고 되돌릴 수 없습니다.", true);
        return;
      }
      setBusy(true);
      remove.textContent = "삭제하는 중…";
      try {
        await deleteMaterial(courseId, id, material.version);
        edits.delete(id);
        api.getState().toast = "자료를 삭제했습니다. 학생 학습실에서도 사라졌습니다.";
        api.paintToast();
        reload();
      } catch (error) {
        if (isStaleVersion(error)) reloadKeeping("다른 곳에서 이 자료를 먼저 고쳤습니다. 최신 내용을 불러왔으니 확인한 뒤 다시 삭제하세요.");
        else if (error instanceof ApiError && error.status === 404) {
          edits.delete(id);
          api.getState().toast = "이미 삭제된 자료입니다. 목록을 새로 불러왔습니다.";
          api.paintToast();
          reload();
        } else say(materialMessage(error), true);
      } finally {
        if (panel.isConnected) {
          setBusy(false);
          remove.dataset.armed = "";
          remove.textContent = "자료 삭제";
        }
      }
    },
  });
  // A conflict reload: show what the server has now, next to the kept typed text.
  if (memo.latest && memo.latest.pending) {
    memo.latest = { title: material.title, weekNo: material.weekNo, published: material.published, version: material.version, files: files.map((file) => file.name) };
  }
  form.querySelector("[data-material-latest]").replaceChildren(memo.latest ? latestView(memo.latest) : "");
  return panel;
}

function latestView(latest) {
  if (latest.pending) return el("p", { class: "pf-sub", text: "서버의 최신 내용을 불러오는 중입니다." });
  return el("div", { class: "pf-server-pdf" },
    el("p", { class: "pf-sub" }, el("b", { text: "서버의 최신 내용" }), ` · 버전 ${latest.version}`),
    el("p", { class: "pf-sub", text: `${latest.title} · ${latest.weekNo}주차 · ${latest.published ? "공개" : "비공개"}` }),
    el("p", { class: "pf-sub", text: latest.files.length ? `파일: ${latest.files.join(", ")}` : "파일 없음" }),
  );
}

/** `pf-material-save` (professor/actions.js). */
export function saveMaterialEdit(node, api) {
  const panel = node.closest(".pf-material-edit");
  const controller = panel && controllers.get(panel);
  if (controller) void controller.save(api);
}

/** `pf-material-delete` (professor/actions.js). */
export function deleteMaterialEntry(node, api) {
  const panel = node.closest(".pf-material-edit");
  const controller = panel && controllers.get(panel);
  if (controller) void controller.remove(api);
}

// ---------- new material from file box files (자료 등록) ----------

function registrationOf(kj) {
  if (!registrations.has(kj)) registrations.set(kj, { uploads: [], busy: false, repaint: null });
  return registrations.get(kj);
}

/**
 * "파일함에서 첨부" for the new-material form (professor.js `materialFields`). Null unless the backend is
 * `ready` and this account teaches the course. Picks stay in memory per course until registered.
 */
export function registrationAttach(course) {
  const kj = course && course.id;
  const courseId = courseIdFor(kj);
  if (!courseId || roleIn(kj) !== "INSTRUCTOR") return null;
  const memo = registrationOf(kj);
  const list = el("div", { class: "stack", "data-material-picks": "" });
  const known = (state) => (state === "clean" || state === "quarantined" || state === "rejected" ? state : "gone");
  const active = () => memo.uploads.filter((entry) => !["rejected", "failed", "gone"].includes(entry.state)).length;
  const paint = () => {
    list.replaceChildren(...memo.uploads.map((entry) => {
      const remove = btn("빼기", { "aria-label": `${entry.name} 빼기` }, "ghost");
      remove.disabled = memo.busy;
      remove.addEventListener("click", () => {
        memo.uploads = memo.uploads.filter((item) => item !== entry);
        if (entry.uploadId) discardUpload(entry.uploadId);
        paint();
      });
      const state = entry.state === "failed" ? entry.message : UPLOAD_STATE[entry.state] || UPLOAD_STATE.gone;
      return el("div", { class: "pf-activity" }, el("span", { class: "pf-activity-copy" }, icon("file"),
        el("b", { text: entry.name }), el("span", { class: "pf-meta", text: ` · ${[sizeText(entry.size), state].filter(Boolean).join(" · ")}` })), remove);
    }));
  };
  memo.repaint = () => { if (list.isConnected) paint(); };
  const fromBox = fileboxAttach({
    courseId,
    purpose: "lecture_material",
    limit: MAX_FILES,
    room: () => MAX_FILES - active(),
    onAttach: (views) => {
      for (const view of views) {
        const entry = { name: String(view.name || "file"), size: view.sizeBytes, state: known(view.state), uploadId: view.uploadId, message: "" };
        memo.uploads.push(entry);
        if (entry.state !== "quarantined") continue;
        awaitScan(view, {
          alive: () => memo.uploads.includes(entry),
          onState: (next) => { entry.state = known(next.state); if (memo.repaint) memo.repaint(); },
        }).then((last) => {
          if (last && last.state === "quarantined") entry.state = "slow";
          if (memo.repaint) memo.repaint();
        }, (error) => {
          entry.state = "failed";
          entry.message = uploadFailure(error);
          if (memo.repaint) memo.repaint();
        });
      }
      paint();
    },
  });
  if (!fromBox) return null;
  if (memo.busy) fromBox.setDisabled(true);
  paint();
  return el("div", { class: "span-2 stack" },
    el("div", { class: "pf-actions" }, fromBox.node,
      el("span", { class: "pf-sub", text: `파일함의 파일을 ${MAX_FILES}개까지 이 자료에 붙입니다. 파일함 파일을 고르면 위의 파일 칸은 비워 두세요.` })),
    list,
  );
}

/** True when the new-material form of course `kj` holds file box picks (actions.js `saveMaterial`). */
export function registrationPicked(kj) {
  const memo = registrations.get(kj);
  return Boolean(memo && memo.uploads.length);
}

/**
 * `pf-save` (data-form="material") with file box picks: one server material with every clean pick
 * (`saveRemoteMaterialFromUploads`). The form's status line and errors work like an uploaded PDF.
 */
export async function saveRegistrationFromFilebox(node, api, { title, file }) {
  const state = api.getState();
  const kj = state.courseId;
  const memo = registrationOf(kj);
  if (memo.busy || node.disabled) return;
  const scope = node.closest(".sheet");
  const status = scope.querySelector("[data-material-status]");
  const fail = (errors) => {
    status.textContent = Object.values(errors).join(". ");
    api.showErrors(errors);
  };
  if (!title) return fail({ title: "자료명을 입력하세요" });
  if (file) return fail({ file: "직접 고른 파일과 파일함 파일은 따로 등록하세요. 한쪽을 비운 뒤 다시 등록하세요." });
  if (memo.uploads.some((entry) => entry.state === "sending" || entry.state === "quarantined")) return fail({ file: "파일함 파일의 검사가 끝난 뒤 등록하세요." });
  const ready = memo.uploads.filter((entry) => entry.state === "clean");
  if (!ready.length) return fail({ file: "등록할 수 있는 파일함 파일이 없습니다. 빼고 다시 고르세요." });
  if (ready.length > MAX_FILES) return fail({ file: `파일은 ${MAX_FILES}개까지 등록할 수 있습니다.` });
  const controls = [...scope.querySelectorAll("input, select, textarea, button")];
  const disabled = controls.map((control) => control.disabled);
  const label = node.textContent;
  document.querySelectorAll(".err.live").forEach((error) => error.remove());
  memo.busy = true;
  controls.forEach((control) => { control.disabled = true; });
  scope.setAttribute("aria-busy", "true");
  node.setAttribute("aria-busy", "true");
  node.textContent = "서버에 등록하는 중…";
  status.textContent = "파일함 파일로 이 과목의 자료를 등록하고 있습니다.";
  try {
    const { records } = await saveRemoteMaterialFromUploads({
      uploads: ready.map((entry) => ({ uploadId: entry.uploadId, state: entry.state })),
      courseId: kj,
      title,
      weekNo: state.week || 1,
    });
    memo.uploads = [];
    state.toast = records.length
      ? "파일함 파일로 자료를 등록했습니다. 아래 목록에서 쪽별 요약을 만들 수 있습니다."
      : "파일함 파일로 자료를 등록했습니다";
    forgetMaterialList(courseIdFor(kj));
    api.save();
    if (node.isConnected) api.render();
    else api.paintToast();
  } catch (error) {
    const message = error.message || "자료를 등록하지 못했습니다. 잠시 후 다시 시도하세요.";
    status.textContent = message;
    if (node.isConnected) api.showErrors({ file: message });
    else {
      state.toast = message;
      api.paintToast();
    }
  } finally {
    memo.busy = false;
    controls.forEach((control, index) => { if (control.isConnected) control.disabled = disabled[index]; });
    scope.removeAttribute("aria-busy");
    node.removeAttribute("aria-busy");
    node.textContent = label;
    if (memo.repaint) memo.repaint();
  }
}

// ---------- 자료 미리보기 (`pf_pdf`)와 학습실 (`pf_study`) ----------

const pfHref = (screenId, extra) => routeFor("professor", "screen", { screenId, ...(extra || {}) });

function plainText(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  return el("div", { class: "pf-prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function materialGate(course, text) {
  if (backendStatus() === "anon") return el("div", { class: "stack" }, backendPanel({ text }));
  if (!courseIdFor(course.id)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 자료만 볼 수 있습니다.");
  if (roleIn(course.id) !== "INSTRUCTOR") return el("div", { class: "stack" }, backendPanel(), el("p", { class: "pf-sub", text: "이 과목을 담당하는 교수 계정으로 로그인해야 자료와 요약을 관리할 수 있습니다." }));
  return null;
}

/**
 * `pf_pdf`(서버 과목): `?material=<activityId>`(강의실에서 온 자료) 또는 `?n=srv-<fileId>`(그 파일이 든 자료)의 상세.
 * 파일마다 내려받기, 첫 PDF의 서버 미리보기, PDF별 쪽별 요약 작업과 게시 상태, 이 과목의 외부 AI 처리 동의를 보인다.
 */
export function serverMaterialDetail(ctx, course, summaryJobPanel) {
  const blocked = materialGate(course, "ASTRA 서버에 로그인하면 이 과목의 자료와 쪽별 요약을 볼 수 있습니다.");
  if (blocked) return blocked;
  const courseId = courseIdFor(course.id);
  const activityId = Number(ctx.params && ctx.params.get("material"));
  const fileRef = /^srv-([1-9]\d{0,15})$/.exec(String((ctx.params && ctx.params.get("n")) || ""));
  const fileId = fileRef ? Number(fileRef[1]) : null;
  if (!(Number.isInteger(activityId) && activityId > 0) && !fileId) {
    return el("div", { class: "stack" }, el("p", { class: "pf-sub", text: "아래 목록이나 강의실 주별 보기에서 자료를 고르세요." }), serverMaterialList(course, summaryJobPanel));
  }
  const key = materialKey(courseId);
  const load = () => materialLists.load(key, courseId);
  const section = el("section", { class: "sheet", "aria-label": "자료 상세" });
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) section.replaceChildren(loadingState("자료를 불러오는 중입니다"));
      else if (entry.error instanceof ApiError && entry.error.status === 404) section.replaceChildren(emptyState("자료를 볼 수 없습니다", "이 과목을 담당하는 교수만 볼 수 있습니다."));
      else {
        const again = btn("다시 불러오기", {}, "ghost");
        again.addEventListener("click", () => { again.disabled = true; load(); });
        section.replaceChildren(errorState("자료를 불러오지 못했습니다", materialMessage(entry.error), again));
      }
      return;
    }
    const materials = materialsOf(data);
    const records = rememberMaterials(materials, course.id, courseId);
    const material = materials.find((item) => (activityId > 0 && item.activityId === activityId)
      || (fileId && (item.files || []).some((file) => file.fileId === fileId)));
    if (!material) {
      section.replaceChildren(emptyState("자료를 찾을 수 없습니다", "이미 삭제되었거나 이 과목의 자료가 아닙니다.", go(pfHref("pf_material_write", { kj: course.id }), "자료 목록", { class: "btn btn-secondary" })));
      return;
    }
    const files = Array.isArray(material.files) ? material.files : [];
    const firstPdf = files.find((file) => file.pdf) || null;
    section.replaceChildren(
      el("h2", { text: material.title }),
      el("p", { class: "pf-sub", text: [`${material.weekNo}주차`, material.published ? "공개" : "비공개", material.openAt ? `${koDateTime(material.openAt)}부터` : "바로 공개", `파일 ${files.length}개`, `버전 ${material.version}`].join(" · ") }),
      material.description ? plainText(material.description) : null,
      el("div", { class: "pf-actions" },
        go(pfHref("pf_material_write", { kj: course.id, open: String(material.activityId) }), "수정·삭제", { class: "btn btn-secondary" }),
        go(pfHref("pf_room_week", { kj: course.id }), "강의실", { class: "btn btn-ghost" }),
      ),
      files.length
        ? el("ul", { class: "pf-lecture-items" }, ...files.map((file) => el("li", {},
          el("b", { text: file.name }),
          el("span", { class: "pf-meta", text: ` · ${sizeText(file.sizeBytes)}${file.pdf ? " · PDF" : ""}` }), " ",
          downloadControl(courseId, file),
        )))
        : el("p", { class: "pf-sub", text: "첨부한 파일이 없습니다." }),
      firstPdf ? el("div", { class: "stack" }, el("h3", { text: `미리보기 · ${firstPdf.name}` }), serverPreview({ courseId, fileId: firstPdf.fileId, label: firstPdf.name, compact: true })) : null,
      ...records.filter((record) => record.activityId === material.activityId)
        .map((record) => el("div", { class: "pf-summary" }, el("h3", { text: `쪽별 요약 · ${record.fileName}` }), summaryJobPanel(record))),
      llmConsentSection(course),
    );
  };
  paint(materialLists.peek(key));
  queueMicrotask(() => {
    if (!section.isConnected) return;
    materialLists.watch(section, key, paint, load);
    if (freshVisit("pf-material-detail", key) === "entry" || !materialLists.peek(key)) load();
  });
  return section;
}

/** `pf_study`(서버): 담당 과목 고르기, 고른 과목의 외부 AI 처리 동의와 서버 자료(PDF별 쪽별 요약 작업). */
export function serverStudyRoom(ctx, summaryJobPanel, { frame }) {
  if (backendStatus() === "anon") return frame(null, el("div", { class: "stack" }, backendPanel({ text: "ASTRA 서버에 로그인하면 담당 과목의 자료와 쪽별 요약을 관리할 수 있습니다." })));
  const taught = (backendState().courses || [])
    .filter((item) => item.role === "INSTRUCTOR" && item.key && courseById(item.key).id === item.key)
    .map((item) => courseById(item.key));
  const kj = (ctx.params && ctx.params.get("kj")) || "";
  const course = taught.find((item) => item.id === kj) || null;
  const picker = taught.length
    ? el("div", { class: "filters", role: "group", "aria-label": "담당 과목" }, ...taught.map((item) => go(pfHref("pf_study", { kj: item.id }), item.ko, {
      class: `chip all${course && course.id === item.id ? " on" : ""}`,
      "aria-current": course && course.id === item.id ? "page" : null,
    })))
    : emptyState("담당 과목이 없습니다", "ASTRA 서버에 이 계정이 담당하는 과목이 없습니다.");
  if (!course) return frame(null, el("div", { class: "stack" }, backendPanel(), picker, taught.length ? el("p", { class: "pf-sub", text: "과목을 고르면 그 과목의 서버 자료와 쪽별 요약, 외부 AI 처리 동의를 봅니다." }) : null));
  return frame(course, el("div", { class: "stack" },
    backendPanel(),
    picker,
    llmConsentSection(course),
    serverMaterialList(course, summaryJobPanel),
  ));
}
