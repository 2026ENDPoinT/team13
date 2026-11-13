// Account dialogs on the ASTRA backend while the connection is not `off`: 내 정보 (profile, registrar
// sync and contact behind re-authentication), 아바타 (photo upload with an in-page square crop, colour
// allowlist and short name with `version`), 설정 (server preferences with `version`), 접속 기기·이력
// (devices, sessions, login events). `anon` shows only the login strip. Server text goes in as text nodes only.
import { el } from "../../dom.js";
import { btn, field, go, dialogShell as baseDialog, emptyState, loadingState, errorState, table, badge } from "../../ui/components.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { backendStatus, subscribe } from "../../shared/backend.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { currentRole, routeFor } from "../../shared/routes.js";
import { koDateTime } from "../../shared/home-remote.js";
import { personAvatar } from "../../shared/avatar-remote.js";
import {
  AVATAR_COLORS, isAvatarColor, profiles, preferences, sessions, devices, loginEvents,
  watchAccount, reloadAccount, loadMore, peekAccount, saveAvatar, saveContact, savePreferences,
  revokeSession, revokeOtherSessions, revokeDevice, isStepUpRequired, isVersionConflict, stepUpMethods,
  startReauth, stepUpDev, stepUpTotp, accountMessage, THEME_LABEL, LANGUAGE_LABEL, TIME_LABEL, CLIENT_LABEL,
  takeReauthFailure, syncProfile, isSyncUnavailable, syncMessage, uploadAvatarPhoto, removeAvatarPhoto,
  awaitAvatarPhoto, photoMessage, PHOTO_MAX_BYTES, PHOTO_TYPES,
} from "../../shared/account-remote.js";

// replaceChildren() would turn a null child into the text "null"; skip absent parts.
function fill(node, ...children) {
  node.replaceChildren(...children.filter((child) => child != null && child !== false));
}

export const ACCOUNT_DIALOGS = new Set(["myinfo", "avatar", "settings", "history", "history-login"]);

const TITLES = { myinfo: "내 정보", avatar: "아바타", settings: "설정", history: "접속 기기", "history-login": "접속 이력" };
const ROLE = { STUDENT: "학생", PROFESSOR: "교수", TA: "조교", STAFF: "교직원" };

// What the user typed or picked, kept across re-renders and 409 reloads; dropped on session change.
const drafts = new Map();
subscribe(() => drafts.clear());

// Commands (`step` values) whose school IdP re-authentication came back with `reauth=failed`. The
// step-up box shows the failure until the user starts another re-authentication or the dialog is reopened.
const failedSteps = new Set();
subscribe(() => failedSteps.clear());

function shell(title, body, options) {
  const node = baseDialog(title, body, options);
  node.classList.add("home-dialog");
  return node;
}

function attached(node, fn) {
  queueMicrotask(() => {
    if (node.isConnected) fn();
  });
}

function waiting(entry) {
  return !entry || (!entry.data && !entry.error);
}

function busy(button, on, label) {
  button.disabled = on;
  if (on) button.setAttribute("aria-busy", "true");
  else button.removeAttribute("aria-busy");
  if (label) button.textContent = label;
}

function retryButton(resource) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reloadAccount(resource);
  });
  return button;
}

// Status lines inside a repainted part (`data-note`) keep their last message across repaints
// (a save patches the cached profile, which rebuilds the form). Cleared when a dialog is opened.
const notes = new Map();
subscribe(() => notes.clear());

function status(text = "", alert = false, part = null) {
  const kept = part ? notes.get(part) : null;
  return el("p", {
    class: "acct-status",
    role: (kept ? kept.alert : alert) ? "alert" : "status",
    "aria-live": "polite",
    "data-note": part,
    text: kept ? kept.text : text,
  });
}

function say(line, text, alert) {
  const part = line.dataset.note;
  if (part) notes.set(part, { text, alert: Boolean(alert) });
  const live = line.isConnected || !part ? line : document.querySelector(`[data-note="${part}"]`) || line;
  live.setAttribute("role", alert ? "alert" : "status");
  live.textContent = text;
}

/** The user's avatar: the photo when `avatar.type` is `image`, else initials in the server colour. */
export function avatarBadge(profile, size = "lg") {
  const avatar = (profile && profile.avatar) || {};
  return personAvatar({
    userId: profile && profile.userId,
    name: profile && profile.name,
    color: avatar.color,
    shortName: avatar.shortName,
    className: `avatar acct-avatar is-${size}`,
    version: avatar.version,
    photo: avatar.type === "image",
  });
}

/** Dialog body for one of ACCOUNT_DIALOGS, or null when the backend is off (mock dialogs stay). */
export function serverAccountDialog(name, ctx) {
  const status = backendStatus();
  if (status === "off" || !ACCOUNT_DIALOGS.has(name)) return null;
  if (freshVisit("account-dialog", name) === "entry") {
    notes.clear();
    failedSteps.clear();
    forgetPhotoDraft();
  }
  const title = TITLES[name];
  if (status !== "ready") {
    return shell(title, el("div", { class: "acct" },
      backendPanel({ text: "ASTRA 서버에 로그인하면 내 계정 정보를 서버에서 불러옵니다." }),
      name === "history" || name === "history-login" ? historyTabs(name === "history-login") : null,
    ), name.startsWith("history") ? { wide: true } : undefined);
  }
  // A failed IdP re-authentication: remember its command, never resend it, clean the address. The
  // replaced history entry gets a new `history.state`, so the visit is marked again (not a new entry).
  const failed = takeReauthFailure();
  if (failed !== null) {
    if (failed) failedSteps.add(failed);
    freshVisit("account-dialog", name);
  }
  if (name === "myinfo") return shell(title, myInfo(ctx));
  if (name === "avatar") return shell(title, avatarForm());
  if (name === "settings") return shell(title, settingsForm());
  return shell(title, history(name === "history-login"), { wide: true });
}

// ---------- 내 정보 ----------

function myInfo(ctx) {
  const box = el("div", { class: "acct", "aria-live": "polite" });
  const paint = (entry) => {
    if (waiting(entry)) return fill(box, loadingState("내 정보를 불러오는 중입니다"));
    if (!entry.data) return fill(box, errorState("내 정보를 불러오지 못했습니다", accountMessage(entry.error), retryButton(profiles)));
    const p = entry.data;
    const contact = p.contact || {};
    const dept = [p.departmentName, p.grade ? `${p.grade}학년` : ""].filter(Boolean).join(" ");
    const returned = !failedSteps.has("contact") && ctx.params && ctx.params.get("step") === "contact";
    const line = status(returned && contact.editable ? "다시 인증했으면 연락처를 확인한 뒤 저장하세요." : "", false, "contact");
    line.id = "acct-contact-status";
    fill(box,
      el("div", { class: "who" },
        avatarBadge(p),
        el("b", { text: p.name || "" }),
        dept ? el("p", { text: dept }) : null,
        el("p", { class: "tiny", text: ROLE[p.role] || "" }),
        go(routeFor(currentRole(), "pop", { pop: "avatar" }), "아바타 바꾸기", { class: "btn btn-secondary" }),
      ),
      el("dl", { class: "acct-contact" },
        el("dt", { text: "전화번호" }), el("dd", { text: contact.phone || "등록된 번호 없음" }),
        el("dt", { text: "이메일" }), el("dd", { text: contact.email || "등록된 메일 없음" }),
      ),
      el("p", { class: "note", text: contact.source === "academic"
        ? "연락처는 학사 시스템에서 가져온 값입니다. 학사 시스템의 개인정보 변경에서 바꾼 뒤 동기화하세요."
        : "이름, 소속, 학년은 학사 시스템 값이라 여기서 바꾸지 않습니다." }),
      syncRow(p),
      contact.editable ? contactForm(ctx, contact, line) : null,
      line,
    );
  };
  paint(peekAccount(profiles));
  attached(box, () => watchAccount(profiles, "account-profile", box, paint));
  return box;
}

// Registrar sync in flight. The form is rebuilt when the synced profile lands, so the button
// state lives here; dropped on session change.
const syncState = { busy: false };
subscribe(() => {
  syncState.busy = false;
});

const SYNC_LABEL = "학사 정보 동기화";

/**
 * 학사 동기화 (`POST /api/me/profile/sync`). Shown only when the profile says the command can be
 * tried (`registrarSyncAvailable`: false without a registrar connection or for a break-glass
 * session). A 503 `not_configured` that still arrives (configuration changed meanwhile) is shown
 * as a plain note.
 */
function syncRow(p) {
  if (p.registrarSyncAvailable !== true) return null;
  const line = status("", false, "sync");
  const button = btn(syncState.busy ? "동기화하는 중…" : SYNC_LABEL, { "data-acct-sync": "" }, "secondary");
  if (syncState.busy) busy(button, true);
  button.addEventListener("click", async () => {
    if (syncState.busy || button.disabled) return;
    syncState.busy = true;
    busy(button, true, "동기화하는 중…");
    say(line, "");
    try {
      await syncProfile();
      say(line, "학사 시스템의 최신 정보로 바꾸었습니다.");
    } catch (error) {
      say(line, syncMessage(error), !isSyncUnavailable(error));
    } finally {
      syncState.busy = false;
      // A successful sync repainted the form: settle whichever button is on screen now.
      const live = button.isConnected ? button : document.querySelector("[data-acct-sync]");
      if (live) busy(live, false, SYNC_LABEL);
    }
  });
  const synced = koDateTime(p.lastSyncedAt);
  return el("div", { class: "acct-sync" },
    el("div", { class: "btn-row" }, button),
    el("p", { class: "tiny", text: synced ? `마지막 동기화 ${synced}` : "학사 시스템의 이름, 소속, 학년, 연락처를 다시 가져옵니다." }),
    line,
  );
}

function contactForm(ctx, contact, line) {
  const draft = drafts.get("contact") || { phone: contact.phone || "", email: contact.email || "" };
  drafts.set("contact", draft);
  const failed = failedSteps.has("contact");
  const returned = !failed && ctx.params && ctx.params.get("step") === "contact";
  const phone = el("input", { type: "tel", autocomplete: "tel", value: draft.phone, "aria-describedby": "acct-contact-status" });
  const email = el("input", { type: "email", autocomplete: "email", value: draft.email, "aria-describedby": "acct-contact-status" });
  phone.addEventListener("input", () => { draft.phone = phone.value; });
  email.addEventListener("input", () => { draft.email = email.value; });
  const save = btn("연락처 저장", {}, "primary");
  const stepSlot = el("div");
  const form = el("details", { class: "acct-edit", open: returned || failed || draft.open ? "" : null },
    el("summary", { text: "연락처 바꾸기" }),
    el("div", { class: "acct-form" },
      field("전화번호", phone),
      field("이메일", email),
      el("div", { class: "btn-row" }, save),
      stepSlot,
    ),
  );
  form.addEventListener("toggle", () => { draft.open = form.open; });
  let sending = false;
  const send = async () => {
    if (sending) return;
    sending = true;
    busy(save, true, "저장하는 중…");
    say(line, "");
    try {
      await saveContact({ phone: phone.value.trim(), email: email.value.trim() });
      drafts.delete("contact");
      failedSteps.delete("contact");
      say(line, "연락처를 저장했습니다.");
    } catch (error) {
      if (isStepUpRequired(error)) {
        say(line, accountMessage(error), true);
        fill(stepSlot, stepUpBox({ returnStep: "contact", retry: send, line }));
      } else {
        say(line, accountMessage(error), true);
        // The registrar owns this contact now: show the server's current state.
        if (error.code === "contact_managed_by_registrar") reloadAccount(profiles);
      }
    } finally {
      sending = false;
      if (save.isConnected) busy(save, false, "연락처 저장");
    }
  };
  save.addEventListener("click", send);
  // Back from a failed IdP re-authentication: offer it again; the save is not resent.
  if (failed) fill(stepSlot, stepUpBox({ returnStep: "contact", retry: send, line, failed: true }));
  return form;
}

/**
 * Re-authentication the backend offers for this session (`GET /api/me` `stepUpMethod`). `retry`
 * resends the command after an in-page step-up (break-glass TOTP, local dev); the school IdP is a
 * page navigation that comes back to this dialog with `step=<returnStep>` (the user sends the
 * command again from there) or, when it failed, with `reauth=failed` as well (`failed`).
 */
function stepUpBox({ returnStep, retry, line, failed = false }) {
  const [method] = stepUpMethods();
  const box = el("div", { class: "acct-stepup", role: "group", "aria-label": "본인 확인" },
    failed ? el("p", { class: "acct-status", role: "alert", text: REAUTH_FAILED }) : null,
    el("p", { class: "note", text: "보안을 위해 최근에 다시 인증한 세션에서만 할 수 있습니다." }));
  if (!method) {
    box.append(el("p", { class: "note", text: "이 세션은 여기서 다시 인증할 수 없습니다. 로그아웃한 뒤 다시 로그인하고 시도하세요." }));
    return box;
  }
  const actions = el("div", { class: "btn-row" });
  if (method === "idp") {
    const idp = btn("학교 계정으로 다시 인증", {}, "primary");
    idp.addEventListener("click", () => {
      failedSteps.delete(returnStep);
      busy(idp, true, "학교 로그인 화면으로 이동합니다…");
      const params = new URLSearchParams(location.search);
      params.set("step", returnStep);
      startReauth(`${location.pathname}?${params}`);
    });
    actions.append(idp);
  }
  if (method === "dev") {
    const dev = btn("개발용 본인 확인", {}, "secondary");
    dev.addEventListener("click", async () => {
      if (dev.disabled) return;
      failedSteps.delete(returnStep);
      busy(dev, true, "확인하는 중…");
      try {
        await stepUpDev();
        box.remove();
        await retry();
      } catch (error) {
        if (line.isConnected) say(line, accountMessage(error), true);
        if (dev.isConnected) busy(dev, false, "개발용 본인 확인");
      }
    });
    actions.append(dev);
  }
  if (method === "totp") {
    const code = el("input", { inputmode: "numeric", autocomplete: "one-time-code", maxlength: "8" });
    const confirm = btn("확인", {}, "primary");
    confirm.addEventListener("click", async () => {
      if (confirm.disabled) return;
      failedSteps.delete(returnStep);
      busy(confirm, true, "확인하는 중…");
      try {
        await stepUpTotp(code.value);
        box.remove();
        await retry();
      } catch (error) {
        if (line.isConnected) say(line, accountMessage(error), true);
        if (confirm.isConnected) busy(confirm, false, "확인");
      }
    });
    box.append(field("인증 앱 코드", code));
    actions.append(confirm);
  }
  box.append(actions);
  return box;
}

// Same first sentence as the `/?reauth=failed` toast in app.js.
const REAUTH_FAILED = "학교 계정으로 다시 인증하지 못했습니다. 요청은 보내지 않았습니다. 다시 인증한 뒤 시도하세요.";

// ---------- 아바타 ----------

function avatarForm() {
  const box = el("div", { class: "acct", "aria-live": "polite" });
  const line = status();
  const photo = photoPart();
  let shown = "";
  const paint = (entry) => {
    if (waiting(entry)) {
      shown = "";
      return fill(box, loadingState("아바타를 불러오는 중입니다"));
    }
    if (!entry.data) {
      shown = "";
      return fill(box, errorState("아바타를 불러오지 못했습니다", accountMessage(entry.error), retryButton(profiles)));
    }
    const p = entry.data;
    const avatar = p.avatar || {};
    photo.update(p);
    // While a photo is processing the profile is re-read every few seconds: rebuild the colour
    // form only when what it shows changed, so typing and focus survive the polling.
    const signature = JSON.stringify([p.name, avatar.type, avatar.color, avatar.shortName, avatar.version, avatar.photo && avatar.photo.state]);
    if (signature === shown && box.contains(photo.node)) return;
    shown = signature;
    const draft = drafts.get("avatar") || {
      color: isAvatarColor(avatar.color) ? avatar.color : AVATAR_COLORS[0][0],
      shortName: avatar.shortName || "",
    };
    drafts.set("avatar", draft);
    const preview = el("span", { class: "avatar acct-avatar is-xl", "aria-hidden": "true" });
    const repaint = () => {
      preview.className = `avatar acct-avatar is-xl avatar-bg-${draft.color}`;
      preview.textContent = draft.shortName || String(p.name || "").slice(0, 1) || "?";
    };
    const short = el("input", { value: draft.shortName, maxlength: "16", autocomplete: "off", "aria-describedby": "acct-avatar-hint" });
    short.addEventListener("input", () => {
      draft.shortName = short.value;
      repaint();
    });
    const swatches = el("div", { class: "acct-swatches" }, ...AVATAR_COLORS.map(([key, label]) => {
      const input = el("input", { type: "radio", name: "avatarColor", value: key, checked: draft.color === key ? "" : null });
      input.addEventListener("change", () => {
        if (!input.checked) return;
        draft.color = key;
        repaint();
      });
      return el("label", { class: "acct-swatch" },
        input,
        el("span", { class: `acct-swatch__chip avatar-bg-${key}`, "aria-hidden": "true" }),
        el("span", { text: label }),
      );
    }));
    // Saving a colour turns a photo avatar back into a colour one and discards a photo still processing.
    const replacesPhoto = avatar.type === "image" || Boolean(avatar.photo && avatar.photo.state === "processing");
    const save = btn("색 아바타 저장", {}, "primary");
    save.addEventListener("click", async () => {
      if (save.disabled) return;
      busy(save, true, "저장하는 중…");
      say(line, "");
      try {
        await saveAvatar({ avatarColor: draft.color, shortName: draft.shortName.trim(), version: avatar.version || 0 });
        drafts.delete("avatar");
        say(line, replacesPhoto ? "색 아바타를 저장했습니다. 사진 대신 이 아바타를 씁니다." : "아바타를 저장했습니다.");
      } catch (error) {
        if (!line.isConnected) return;
        say(line, accountMessage(error), true);
        // Stale version: nothing changed. Reload the profile and keep the choice for another try.
        if (isVersionConflict(error)) reloadAccount(profiles);
      } finally {
        if (save.isConnected) busy(save, false, "색 아바타 저장");
      }
    });
    repaint();
    fill(box,
      photo.node,
      el("h3", { class: "acct-subhead", text: "색 아바타" }),
      el("div", { class: "who" }, preview),
      field("표시 글자", short),
      el("p", { id: "acct-avatar-hint", class: "note", text: replacesPhoto
        ? "두 글자까지 씁니다. 비우면 이름의 첫 글자를 씁니다. 색 아바타를 저장하면 사진은 지워집니다."
        : "두 글자까지 씁니다. 비우면 이름의 첫 글자를 씁니다." }),
      el("fieldset", { class: "acct-fieldset" }, el("legend", { text: "배경색" }), swatches),
      el("div", { class: "btn-row" }, go(routeFor(currentRole(), "pop", { pop: "myinfo" }), "이전", { class: "btn btn-secondary" }), save),
      line,
    );
  };
  paint(peekAccount(profiles));
  attached(box, () => watchAccount(profiles, "account-avatar", box, paint));
  return box;
}

// ---------- 아바타 사진 ----------

const CROP_VIEW = 240; // crop square in CSS px; the drawing buffer is twice that
const CROP_OUT = 512; // uploaded JPEG side; the server rebuilds a 256 px JPEG
const CROP_ZOOM_MAX = 4;

// The picked photo being cropped (an ImageBitmap in memory, never a URL) and which photo state was
// last announced. Kept across re-renders; dropped when the dialog is opened again or the session changes.
let photoDraft = null;
let photoShown;
function forgetPhotoDraft() {
  if (photoDraft && photoDraft.bitmap && photoDraft.bitmap.close) photoDraft.bitmap.close();
  photoDraft = null;
  photoShown = undefined;
}
subscribe(() => forgetPhotoDraft());

function clampCrop(crop) {
  const scale = crop.base * crop.zoom;
  crop.x = Math.min(0, Math.max(CROP_VIEW - crop.bitmap.width * scale, crop.x));
  crop.y = Math.min(0, Math.max(CROP_VIEW - crop.bitmap.height * scale, crop.y));
}

/** Zooms around the centre of the crop square. */
function zoomCrop(crop, zoom) {
  const next = Math.min(CROP_ZOOM_MAX, Math.max(1, zoom));
  const before = crop.base * crop.zoom;
  const cx = (CROP_VIEW / 2 - crop.x) / before;
  const cy = (CROP_VIEW / 2 - crop.y) / before;
  crop.zoom = next;
  const after = crop.base * next;
  crop.x = CROP_VIEW / 2 - cx * after;
  crop.y = CROP_VIEW / 2 - cy * after;
  clampCrop(crop);
}

function drawCrop(canvas, crop) {
  const ctx = canvas.getContext("2d");
  const k = canvas.width / CROP_VIEW;
  const scale = crop.base * crop.zoom * k;
  ctx.fillStyle = "#ffffff"; // a transparent PNG becomes a JPEG on white
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(crop.bitmap, crop.x * k, crop.y * k, crop.bitmap.width * scale, crop.bitmap.height * scale);
}

/**
 * 사진 고르기·자르기·올리기·지우기. The file is decoded with `createImageBitmap` and drawn into a
 * canvas (the page CSP has no `blob:`), cropped to a square by dragging or the arrow keys and the
 * zoom slider (or + / -), exported with `canvas.toBlob` as a 512 px JPEG and sent as multipart `file`.
 * The server answers 202 `processing`; the profile is re-read until the photo is `ready` or `failed`.
 */
function photoPart() {
  const node = el("section", { class: "acct-photo", "aria-labelledby": "acct-photo-head" });
  const line = status("", false, "photo");
  const current = el("div", { class: "acct-photo__current" });
  const editor = el("div", { class: "acct-photo__editor" });
  let polling = false;
  let sending = false;

  const input = el("input", { type: "file", accept: "image/png,image/jpeg", "aria-describedby": "acct-photo-hint" });
  input.addEventListener("change", async () => {
    const file = input.files && input.files[0];
    input.value = "";
    if (!file) return;
    if (!PHOTO_TYPES.has(file.type)) return say(line, "PNG나 JPEG 파일만 올릴 수 있습니다.", true);
    if (file.size > PHOTO_MAX_BYTES) return say(line, "5MB 이하의 사진만 올릴 수 있습니다.", true);
    let bitmap;
    try {
      bitmap = await createImageBitmap(file);
    } catch {
      return say(line, "사진을 읽지 못했습니다. 다른 PNG나 JPEG 파일을 고르세요.", true);
    }
    const previous = photoShown;
    forgetPhotoDraft();
    photoShown = previous;
    const base = Math.max(CROP_VIEW / bitmap.width, CROP_VIEW / bitmap.height);
    photoDraft = { bitmap, base, zoom: 1, x: (CROP_VIEW - bitmap.width * base) / 2, y: (CROP_VIEW - bitmap.height * base) / 2 };
    say(line, "사진을 끌거나 화살표 키로 옮기고 확대를 맞춘 뒤 올리세요.");
    paintEditor();
    const canvas = editor.querySelector("canvas");
    if (canvas) canvas.focus();
  });

  const send = async (button) => {
    if (sending || !photoDraft) return;
    sending = true;
    busy(button, true, "올리는 중…");
    say(line, "");
    try {
      const out = document.createElement("canvas");
      out.width = CROP_OUT;
      out.height = CROP_OUT;
      drawCrop(out, photoDraft);
      const blob = await new Promise((resolve) => out.toBlob(resolve, "image/jpeg", 0.9));
      if (!blob) throw new Error("사진을 만들지 못했습니다. 다른 사진을 고르세요.");
      if (blob.size > PHOTO_MAX_BYTES) throw new Error(photoMessage("too_large"));
      photoShown = "";
      await uploadAvatarPhoto(blob, "avatar.jpg");
      const shown = photoShown;
      forgetPhotoDraft();
      photoShown = shown;
      paintEditor();
      say(line, "사진을 올렸습니다. 검사하고 다듬는 중입니다. 끝나면 여기에 보입니다.");
    } catch (error) {
      say(line, photoMessage(error), true);
    } finally {
      sending = false;
      if (button.isConnected) busy(button, false, "사진 올리기");
    }
  };

  function paintEditor() {
    if (!photoDraft) {
      fill(editor,
        field("사진 파일", input),
        el("p", { id: "acct-photo-hint", class: "note", text: "PNG나 JPEG, 5MB 이하. 고른 뒤 정사각형으로 잘라 올립니다." }),
      );
      return;
    }
    const crop = photoDraft;
    const canvas = el("canvas", {
      class: "acct-crop",
      width: String(CROP_VIEW * 2),
      height: String(CROP_VIEW * 2),
      tabindex: "0",
      role: "img",
      "aria-label": "자를 영역. 화살표 키로 옮기고 + 와 - 키로 확대하거나 줄입니다.",
    });
    const zoom = el("input", { type: "range", min: "1", max: String(CROP_ZOOM_MAX), step: "0.05", value: String(crop.zoom) });
    const redraw = () => {
      clampCrop(crop);
      drawCrop(canvas, crop);
    };
    const setZoom = (value) => {
      zoomCrop(crop, value);
      zoom.value = String(crop.zoom);
      drawCrop(canvas, crop);
    };
    zoom.addEventListener("input", () => setZoom(Number(zoom.value)));
    let drag = null;
    canvas.addEventListener("pointerdown", (event) => {
      drag = { x: event.clientX, y: event.clientY };
      canvas.setPointerCapture(event.pointerId);
    });
    canvas.addEventListener("pointermove", (event) => {
      if (!drag) return;
      const k = CROP_VIEW / (canvas.getBoundingClientRect().width || CROP_VIEW);
      crop.x += (event.clientX - drag.x) * k;
      crop.y += (event.clientY - drag.y) * k;
      drag = { x: event.clientX, y: event.clientY };
      redraw();
    });
    const stop = () => { drag = null; };
    canvas.addEventListener("pointerup", stop);
    canvas.addEventListener("pointercancel", stop);
    canvas.addEventListener("keydown", (event) => {
      const step = event.shiftKey ? 32 : 8;
      const moves = { ArrowLeft: [step, 0], ArrowRight: [-step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] };
      if (moves[event.key]) {
        crop.x += moves[event.key][0];
        crop.y += moves[event.key][1];
        redraw();
      } else if (event.key === "+" || event.key === "=") setZoom(crop.zoom + 0.1);
      else if (event.key === "-" || event.key === "_") setZoom(crop.zoom - 0.1);
      else return;
      event.preventDefault();
    });
    const upload = btn("사진 올리기", {}, "primary");
    upload.addEventListener("click", () => send(upload));
    const cancel = btn("취소", {}, "ghost");
    cancel.addEventListener("click", () => {
      const shown = photoShown;
      forgetPhotoDraft();
      photoShown = shown;
      say(line, "");
      paintEditor();
    });
    redraw();
    fill(editor,
      canvas,
      field("확대", zoom),
      el("div", { class: "btn-row" }, upload, cancel),
    );
  }

  function paintCurrent(p) {
    const avatar = p.avatar || {};
    const hasPhoto = avatar.type === "image";
    const remove = hasPhoto || avatar.photo ? btn("사진 지우기", {}, "ghost") : null;
    if (remove) {
      confirmTwice(remove, "한 번 더 누르면 사진 지우기", async () => {
        busy(remove, true, "지우는 중…");
        say(line, "");
        try {
          await removeAvatarPhoto();
          photoShown = "";
          say(line, "사진을 지웠습니다. 색 아바타를 씁니다.");
        } catch (error) {
          say(line, accountMessage(error), true);
          if (remove.isConnected) busy(remove, false, "사진 지우기");
        }
      });
    }
    fill(current,
      hasPhoto ? avatarBadge(p) : null,
      el("p", { class: "note", text: hasPhoto ? "지금 쓰는 사진입니다." : "사진이 없어 색 아바타를 씁니다." }),
      remove ? el("div", { class: "btn-row" }, remove) : null,
    );
  }

  function announce(photo) {
    const key = photo ? `${photo.state}:${photo.errorCode || ""}` : "";
    if (key === photoShown) return;
    const opened = photoShown === undefined;
    photoShown = key;
    if (!photo) return;
    if (photo.state === "processing") say(line, "사진을 검사하고 다듬는 중입니다. 끝나면 여기에 보입니다.");
    else if (photo.state === "ready" && !opened) say(line, "사진을 적용했습니다.");
    else if (photo.state === "failed") say(line, `올린 사진을 쓰지 못했습니다. ${photoMessage(photo.errorCode)}`, true);
  }

  function watchProcessing() {
    queueMicrotask(() => {
      if (polling || !node.isConnected) return;
      polling = true;
      awaitAvatarPhoto(node).then((state) => {
        polling = false;
        if (state === "processing" && node.isConnected) say(line, "사진 처리가 오래 걸립니다. 잠시 뒤 이 창을 다시 열어 확인하세요.");
      });
    });
  }

  paintEditor();
  node.append(el("h3", { id: "acct-photo-head", class: "acct-subhead", text: "사진" }), current, editor, line);
  return {
    node,
    update(p) {
      const photo = (p.avatar && p.avatar.photo) || null;
      paintCurrent(p);
      announce(photo);
      if (photo && photo.state === "processing") watchProcessing();
    },
  };
}

// ---------- 설정 ----------

const PREF_DRAFT = "preferences";

function systemTheme() {
  return window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

/**
 * Area action `srv-preferences-save`: sends the dialog's values with the loaded `version`, then
 * applies the saved theme and language to this browser the same way the local settings do, so
 * the theme boot (`theme-boot.js`) opens the next page in the saved mode.
 */
export async function savePreferencesAction(node, api) {
  const draft = drafts.get(PREF_DRAFT);
  const line = document.querySelector("[data-pref-status]");
  if (!draft || node.disabled) return;
  busy(node, true, "저장하는 중…");
  if (line) say(line, "");
  const dnd = draft.doNotDisturb;
  try {
    const saved = await savePreferences({
      language: draft.language,
      timeFormat: draft.timeFormat,
      theme: draft.theme,
      doNotDisturb: dnd.enabled ? { enabled: true, start: dnd.start, end: dnd.end } : { enabled: false, start: null, end: null },
      version: draft.version,
    });
    drafts.delete(PREF_DRAFT);
    const state = api.getState();
    state.themeMode = saved.theme;
    state.theme = saved.theme === "system" ? systemTheme() : saved.theme;
    state.lang = saved.language === "en" ? "en" : "ko";
    document.documentElement.setAttribute("data-theme", state.theme);
    document.documentElement.setAttribute("data-theme-mode", state.themeMode);
    state.toast = "설정을 저장했습니다";
    api.save();
    api.render();
  } catch (error) {
    const current = document.querySelector("[data-pref-status]");
    if (current) say(current, accountMessage(error), true);
    if (isVersionConflict(error)) reloadAccount(preferences);
    if (node.isConnected) busy(node, false, "저장");
  }
}

function choice(label, options, value, onChange) {
  const select = el("select", {}, ...Object.entries(options).map(([key, text]) => el("option", { value: key, text, selected: key === value ? "" : null })));
  select.addEventListener("change", () => onChange(select.value));
  return field(label, select);
}

function settingsForm() {
  const box = el("div", { class: "acct", "aria-live": "polite" });
  const paint = (entry) => {
    if (waiting(entry)) return fill(box, loadingState("설정을 불러오는 중입니다"));
    if (!entry.data) return fill(box, errorState("설정을 불러오지 못했습니다", accountMessage(entry.error), retryButton(preferences)));
    const saved = entry.data;
    const dnd = saved.doNotDisturb || {};
    const draft = drafts.get(PREF_DRAFT) || {
      language: saved.language, timeFormat: saved.timeFormat, theme: saved.theme,
      doNotDisturb: { enabled: Boolean(dnd.enabled), start: dnd.start || "22:00", end: dnd.end || "07:00" },
    };
    draft.version = saved.version;
    drafts.set(PREF_DRAFT, draft);
    const start = el("input", { type: "time", value: draft.doNotDisturb.start });
    const end = el("input", { type: "time", value: draft.doNotDisturb.end });
    start.addEventListener("input", () => { draft.doNotDisturb.start = start.value; });
    end.addEventListener("input", () => { draft.doNotDisturb.end = end.value; });
    const toggle = el("input", { type: "checkbox", checked: draft.doNotDisturb.enabled ? "" : null });
    const times = el("div", { class: "acct-times", hidden: draft.doNotDisturb.enabled ? null : "" }, field("시작", start), field("끝", end));
    toggle.addEventListener("change", () => {
      draft.doNotDisturb.enabled = toggle.checked;
      times.hidden = !toggle.checked;
    });
    const local = document.documentElement.getAttribute("data-theme-mode") || "system";
    fill(box,
      choice("화면 모드", THEME_LABEL, draft.theme, (value) => { draft.theme = value; }),
      choice("언어", LANGUAGE_LABEL, draft.language, (value) => { draft.language = value; }),
      choice("시간", TIME_LABEL, draft.timeFormat, (value) => { draft.timeFormat = value; }),
      el("fieldset", { class: "acct-fieldset" },
        el("legend", { text: "푸시 알림 에티켓 시간" }),
        el("label", { class: "choice" }, toggle, el("span", { text: "이 시간에는 푸시 알림을 보내지 않습니다" })),
        times,
        el("p", { class: "note", text: "끝이 시작보다 이르면 다음 날까지입니다." }),
      ),
      local !== saved.theme
        ? el("p", { class: "note", text: `이 브라우저는 지금 ${THEME_LABEL[local] || "기기 기본값"}로 보입니다. 저장하면 서버 설정에 맞춥니다.` })
        : null,
      el("div", { class: "btn-row" }, btn("저장", { "data-act": "srv-preferences-save" }, "primary")),
      (() => { const line = status("", false, "preferences"); line.setAttribute("data-pref-status", ""); return line; })(),
    );
  };
  paint(peekAccount(preferences));
  attached(box, () => watchAccount(preferences, "account-preferences", box, paint));
  return box;
}

// ---------- 접속 기기 · 접속 이력 ----------

function historyTabs(login) {
  return el("div", { class: "filters" },
    go(routeFor(currentRole(), "pop", { pop: "history" }), "접속 기기", { class: `chip${!login ? " on all" : ""}`, "aria-current": !login ? "page" : null }),
    go(routeFor(currentRole(), "pop", { pop: "history-login" }), "접속 이력", { class: `chip${login ? " on all" : ""}`, "aria-current": login ? "page" : null }),
  );
}

function history(login) {
  return el("div", { class: "acct" },
    historyTabs(login),
    login ? loginList() : el("div", { class: "acct" }, deviceList(), sessionList()),
  );
}

function moreButton(resource, entry) {
  if (!entry.data.nextCursor) return null;
  const more = btn(entry.loading ? "불러오는 중…" : "더 보기", {}, "secondary");
  more.disabled = Boolean(entry.loading);
  more.addEventListener("click", () => {
    busy(more, true, "불러오는 중…");
    loadMore(resource);
  });
  return el("div", { class: "btn-row" }, more);
}

function confirmTwice(button, armedLabel, run) {
  button.addEventListener("click", () => {
    if (button.disabled) return;
    if (button.dataset.armed !== "1") {
      button.dataset.armed = "1";
      button.textContent = armedLabel;
      // The accessible name follows the visible text so the second press is announced.
      if (button.hasAttribute("aria-label")) button.setAttribute("aria-label", `${button.getAttribute("aria-label")}: ${armedLabel}`);
      return;
    }
    run();
  });
}

function deviceList() {
  const section = el("section", { class: "acct-section", "aria-label": "등록된 기기" });
  const line = status();
  const paint = (entry) => {
    const head = el("h3", { text: "등록된 기기" });
    if (waiting(entry)) return fill(section, head, loadingState("기기를 불러오는 중입니다"));
    if (!entry.data) return fill(section, head, errorState("기기를 불러오지 못했습니다", accountMessage(entry.error), retryButton(devices)));
    const items = entry.data.items;
    if (!items.length) return fill(section, head, emptyState("등록된 기기가 없습니다"));
    const rows = items.map((device) => {
      const remove = btn("삭제", { "aria-label": `${device.name || "기기"} 삭제` }, "ghost");
      confirmTwice(remove, device.current ? "이 기기에서 로그아웃됩니다. 한 번 더 누르면 삭제" : "한 번 더 누르면 삭제", async () => {
        busy(remove, true, "삭제하는 중…");
        say(line, "");
        try {
          const result = await revokeDevice(device.deviceId);
          if (line.isConnected) say(line, result && result.currentSessionRevoked ? "이 기기를 지워 로그아웃되었습니다." : "기기를 삭제했습니다.");
        } catch (error) {
          if (line.isConnected) say(line, accountMessage(error), true);
          if (remove.isConnected) busy(remove, false, "삭제");
        }
      });
      return [
        el("span", {}, el("b", { text: device.name || "이름 없는 기기" }), device.current ? " " : null, device.current ? badge("현재", "success") : null),
        CLIENT_LABEL[device.clientType] || device.clientType || "",
        koDateTime(device.lastAccessAt) || "-",
        device.certified ? "Y" : "-",
        remove,
      ];
    });
    fill(section, head, table(["접속환경", "구분", "최종접속일", "기기인증여부", "삭제"], rows), moreButton(devices, entry), line);
  };
  paint(peekAccount(devices));
  attached(section, () => watchAccount(devices, "account-devices", section, paint));
  return section;
}

function sessionList() {
  const section = el("section", { class: "acct-section", "aria-label": "로그인 중인 세션" });
  const line = status();
  const stepSlot = el("div");
  const paint = (entry) => {
    const head = el("h3", { text: "로그인 중인 세션" });
    if (waiting(entry)) return fill(section, head, loadingState("세션을 불러오는 중입니다"));
    if (!entry.data) return fill(section, head, errorState("세션을 불러오지 못했습니다", accountMessage(entry.error), retryButton(sessions)));
    const items = Array.isArray(entry.data.sessions) ? entry.data.sessions : [];
    const rows = items.map((item) => {
      let end = null;
      if (!item.current) {
        end = btn("끝내기", { "aria-label": `${item.ipAddress || ""} 세션 끝내기` }, "ghost");
        confirmTwice(end, "한 번 더 누르면 끝내기", async () => {
          busy(end, true, "끝내는 중…");
          try {
            await revokeSession(item.sessionId);
            if (line.isConnected) say(line, "세션을 끝냈습니다.");
          } catch (error) {
            if (line.isConnected) say(line, accountMessage(error), true);
            if (end.isConnected) busy(end, false, "끝내기");
          }
        });
      }
      return [
        el("span", {}, el("b", { text: item.userAgent || "알 수 없는 브라우저" }), item.current ? " " : null, item.current ? badge("지금 세션", "success") : null),
        item.ipAddress || "-",
        koDateTime(item.authenticatedAt) || "-",
        koDateTime(item.lastSeenAt) || "-",
        end || "",
      ];
    });
    const others = items.filter((item) => !item.current).length;
    const all = btn("다른 세션 모두 끝내기", {}, "secondary");
    all.disabled = others === 0;
    const endOthers = async () => {
      busy(all, true, "끝내는 중…");
      say(line, "");
      try {
        const result = await revokeOtherSessions();
        stepSlot.replaceChildren();
        if (line.isConnected) say(line, `다른 세션 ${(result && result.revokedSessions) || 0}개를 끝냈습니다.`);
      } catch (error) {
        if (!line.isConnected) return;
        say(line, accountMessage(error), true);
        if (isStepUpRequired(error)) fill(stepSlot, stepUpBox({ returnStep: "revoke-others", retry: endOthers, line }));
      } finally {
        if (all.isConnected) busy(all, false, "다른 세션 모두 끝내기");
      }
    };
    confirmTwice(all, "한 번 더 누르면 모두 끝내기", endOthers);
    const returned = new URLSearchParams(location.search).get("step") === "revoke-others";
    // Back from a failed IdP re-authentication: offer it again; the command is not resent.
    if (failedSteps.has("revoke-others") && !stepSlot.firstChild) {
      fill(stepSlot, stepUpBox({ returnStep: "revoke-others", retry: endOthers, line, failed: true }));
    }
    fill(section,
      head,
      items.length ? table(["브라우저", "IP", "로그인", "마지막 요청", ""], rows) : emptyState("로그인 중인 세션이 없습니다"),
      el("div", { class: "btn-row" }, all),
      returned && others ? el("p", { class: "note", text: "다시 인증했으면 다른 세션 모두 끝내기를 한 번 더 누르세요." }) : null,
      stepSlot,
      line,
    );
  };
  paint(peekAccount(sessions));
  attached(section, () => watchAccount(sessions, "account-sessions", section, paint));
  return section;
}

function loginList() {
  const section = el("section", { class: "acct-section", "aria-label": "로그인 기록" });
  const paint = (entry) => {
    const head = el("p", { class: "note acct-right", text: "최근 7일간의 기록만 조회됩니다." });
    if (waiting(entry)) return fill(section, head, loadingState("로그인 기록을 불러오는 중입니다"));
    if (!entry.data) return fill(section, head, errorState("로그인 기록을 불러오지 못했습니다", accountMessage(entry.error), retryButton(loginEvents)));
    const items = entry.data.items;
    if (!items.length) return fill(section, head, emptyState("최근 7일 동안의 로그인 기록이 없습니다"));
    const rows = items.map((item) => [
      item.ipAddress || "-",
      item.clientEnvironment || "-",
      koDateTime(item.loggedInAt) || "-",
      item.success ? "성공" : badge("실패", "danger"),
    ]);
    fill(section, head, table(["IP", "앱", "접속시간", "결과"], rows), moreButton(loginEvents, entry));
  };
  paint(peekAccount(loginEvents));
  attached(section, () => watchAccount(loginEvents, "account-logins", section, paint));
  return section;
}
