// Account dialogs on the ASTRA backend while the connection is not `off`: 내 정보 (profile, registrar
// sync and contact behind re-authentication), 아바타 (photo upload with an in-page square crop, colour
// allowlist and short name with `version`), 설정 (server preferences with `version`), 접속 기기·이력
// (devices, sessions, login events). `anon` shows only the login strip. Server text goes in as text only.
import { useEffect, useReducer, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { Badge, Button, Dialog, EmptyState, ErrorState, Field, LoadingState, Table } from "../../ui/kit.tsx";
import { Link } from "../../router.tsx";
import { getState, render, save } from "../../store.ts";
import { systemTheme } from "../../theme.ts";
import type { ScreenCtx } from "../../types.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { backendStatus, subscribe } from "../../shared/backend.ts";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { currentRole, routeFor } from "../../shared/routes.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { personAvatar } from "../../shared/avatar-remote.tsx";
import {
  AVATAR_COLORS, isAvatarColor, profiles, preferences, sessions, devices, loginEvents,
  reloadAccount, loadMore, saveAvatar, saveContact, savePreferences,
  revokeSession, revokeOtherSessions, revokeDevice, isStepUpRequired, isVersionConflict, stepUpMethods,
  startReauth, stepUpDev, stepUpTotp, accountMessage, THEME_LABEL, LANGUAGE_LABEL, TIME_LABEL, CLIENT_LABEL,
  takeReauthFailure, syncProfile, isSyncUnavailable, syncMessage, uploadAvatarPhoto, removeAvatarPhoto,
  awaitAvatarPhoto, photoMessage, PHOTO_MAX_BYTES, PHOTO_TYPES,
  type AccountResource, type AvatarPhoto, type Profile, type ProfileContact,
} from "../../shared/account-remote.ts";

export const ACCOUNT_DIALOGS = new Set(["myinfo", "avatar", "settings", "history", "history-login"]);

const TITLES: Record<string, string> = { myinfo: "내 정보", avatar: "아바타", settings: "설정", history: "접속 기기", "history-login": "접속 이력" };
const ROLE: Record<string, string> = { STUDENT: "학생", PROFESSOR: "교수", TA: "조교", STAFF: "교직원" };

// The single cache key account-remote uses for every account resource.
const ONE = "me";

// What the user typed or picked, kept across re-renders and 409 reloads; dropped on session change.
interface ContactDraft { phone: string; email: string; open?: boolean }
interface AvatarDraft { color: string; shortName: string }
interface PrefDraft {
  language: string;
  timeFormat: string;
  theme: string;
  doNotDisturb: { enabled: boolean; start: string; end: string };
  version: number;
}
let contactDraft: ContactDraft | null = null;
let avatarDraft: AvatarDraft | null = null;
let prefDraft: PrefDraft | null = null;
subscribe(() => {
  contactDraft = null;
  avatarDraft = null;
  prefDraft = null;
});

// Commands (`step` values) whose school IdP re-authentication came back with `reauth=failed`. The
// step-up box shows the failure until the user starts another re-authentication or the dialog is reopened.
const failedSteps = new Set<string>();
subscribe(() => failedSteps.clear());

function shell(title: string, body: ReactNode, wide?: boolean): ReactNode {
  return <Dialog title={title} wide={wide} className="home-dialog">{body}</Dialog>;
}

/** The cached account entry, re-rendering on changes; loads once per navigation and on focus. */
function useAccount<T>(resource: AccountResource<T>, scope: string): Entry<T> | null {
  const live = backendStatus() === "ready";
  const entry = useResource(resource, ONE, live ? () => void reloadAccount(resource) : undefined);
  useEffect(() => {
    if (backendStatus() === "ready") void resource.visit(scope, ONE, null);
  });
  return entry;
}

function useBump(): () => void {
  const [, bump] = useReducer((n: number) => n + 1, 0);
  return bump;
}

function RetryButton<T>({ resource }: { resource: AccountResource<T> }) {
  const [sent, setSent] = useState(false);
  return (
    <Button kind="ghost" disabled={sent} onClick={() => { setSent(true); void reloadAccount(resource); }}>
      다시 불러오기
    </Button>
  );
}

// Status lines of a part keep their last message across remounts (a save patches the cached
// profile). Cleared when a dialog is opened.
interface Note { text: string; alert: boolean }
const notes = new Map<string, Note>();
subscribe(() => notes.clear());

type Say = (text: string, alert?: boolean) => void;

/** The last message of a status line (`null`: nothing said yet), and the function that says one. */
function useNote(part?: string): [Note | null, Say] {
  const [note, setNote] = useState<Note | null>(() => (part ? notes.get(part) ?? null : null));
  const say: Say = (text, alert = false) => {
    const next = { text, alert };
    if (part) notes.set(part, next);
    setNote(next);
  };
  return [note, say];
}

function Status({ note, fallback = "", id }: { note: Note | null; fallback?: string; id?: string }) {
  const shown = note ?? { text: fallback, alert: false };
  return <p id={id} className="acct-status" role={shown.alert ? "alert" : "status"} aria-live="polite">{shown.text}</p>;
}

/** The user's avatar: the photo when `avatar.type` is `image`, else initials in the server colour. */
export function avatarBadge(profile: Profile | null | undefined, size = "lg"): ReactNode {
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
export function serverAccountDialog(name: string, ctx: ScreenCtx): ReactNode {
  const status = backendStatus();
  if (status === "off" || !ACCOUNT_DIALOGS.has(name)) return null;
  if (freshVisit("account-dialog", name) === "entry") {
    notes.clear();
    failedSteps.clear();
    forgetPhotoDraft();
  }
  const title = TITLES[name] ?? "";
  if (status !== "ready") {
    return shell(title, (
      <div className="acct">
        {backendPanel({ text: "ASTRA 서버에 로그인하면 내 계정 정보를 서버에서 불러옵니다." })}
        {name === "history" || name === "history-login" ? <HistoryTabs login={name === "history-login"} /> : null}
      </div>
    ), name.startsWith("history"));
  }
  // A failed IdP re-authentication: remember its command, never resend it, clean the address. The
  // replaced history entry gets a new `history.state`, so the visit is marked again (not a new entry).
  const failed = takeReauthFailure();
  if (failed !== null) {
    if (failed) failedSteps.add(failed);
    freshVisit("account-dialog", name);
  }
  if (name === "myinfo") return shell(title, <MyInfo ctx={ctx} />);
  if (name === "avatar") return shell(title, <AvatarForm />);
  if (name === "settings") return shell(title, <SettingsForm />);
  return shell(title, <History login={name === "history-login"} />, true);
}

// ---------- 내 정보 ----------

function MyInfo({ ctx }: { ctx: ScreenCtx }) {
  const entry = useAccount(profiles, "account-profile");
  const [note, say] = useNote("contact");
  if (!entry || (!entry.data && !entry.error)) {
    return <div className="acct" aria-live="polite"><LoadingState title="내 정보를 불러오는 중입니다" /></div>;
  }
  if (!entry.data) {
    return (
      <div className="acct" aria-live="polite">
        <ErrorState title="내 정보를 불러오지 못했습니다" body={accountMessage(entry.error)} actions={<RetryButton resource={profiles} />} />
      </div>
    );
  }
  const p = entry.data;
  const contact = p.contact || {};
  const dept = [p.departmentName, p.grade ? `${p.grade}학년` : ""].filter(Boolean).join(" ");
  const returned = !failedSteps.has("contact") && ctx.params && ctx.params.get("step") === "contact";
  return (
    <div className="acct" aria-live="polite">
      <div className="who">
        {avatarBadge(p)}
        <b>{p.name || ""}</b>
        {dept ? <p>{dept}</p> : null}
        <p className="tiny">{ROLE[p.role ?? ""] || ""}</p>
        <Link href={routeFor(currentRole(), "pop", { pop: "avatar" })} className="btn btn-secondary">아바타 바꾸기</Link>
      </div>
      <dl className="acct-contact">
        <dt>전화번호</dt><dd>{contact.phone || "등록된 번호 없음"}</dd>
        <dt>이메일</dt><dd>{contact.email || "등록된 메일 없음"}</dd>
      </dl>
      <p className="note">
        {contact.source === "academic"
          ? "연락처는 학사 시스템에서 가져온 값입니다. 학사 시스템의 개인정보 변경에서 바꾼 뒤 동기화하세요."
          : "이름, 소속, 학년은 학사 시스템 값이라 여기서 바꾸지 않습니다."}
      </p>
      <SyncRow p={p} />
      {contact.editable ? <ContactForm ctx={ctx} contact={contact} say={say} /> : null}
      <Status id="acct-contact-status" note={note} fallback={returned && contact.editable ? "다시 인증했으면 연락처를 확인한 뒤 저장하세요." : ""} />
    </div>
  );
}

// Registrar sync in flight. The form may remount when the synced profile lands, so the button
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
function SyncRow({ p }: { p: Profile }) {
  const [note, say] = useNote("sync");
  const bump = useBump();
  if (p.registrarSyncAvailable !== true) return null;
  const sync = async () => {
    if (syncState.busy) return;
    syncState.busy = true;
    bump();
    say("");
    try {
      await syncProfile();
      say("학사 시스템의 최신 정보로 바꾸었습니다.");
    } catch (error) {
      say(syncMessage(error), !isSyncUnavailable(error));
    } finally {
      syncState.busy = false;
      bump();
    }
  };
  const synced = koDateTime(p.lastSyncedAt);
  return (
    <div className="acct-sync">
      <div className="btn-row">
        <Button kind="secondary" disabled={syncState.busy} aria-busy={syncState.busy || undefined} onClick={() => void sync()}>
          {syncState.busy ? "동기화하는 중…" : SYNC_LABEL}
        </Button>
      </div>
      <p className="tiny">{synced ? `마지막 동기화 ${synced}` : "학사 시스템의 이름, 소속, 학년, 연락처를 다시 가져옵니다."}</p>
      <Status note={note} />
    </div>
  );
}

function ContactForm({ ctx, contact, say }: { ctx: ScreenCtx; contact: ProfileContact; say: Say }) {
  const draft = contactDraft || { phone: contact.phone || "", email: contact.email || "" };
  contactDraft = draft;
  const bump = useBump();
  const failed = failedSteps.has("contact");
  const returned = !failed && ctx.params && ctx.params.get("step") === "contact";
  const sending = useRef(false);
  const [saving, setSaving] = useState(false);
  // Back from a failed IdP re-authentication: offer it again; the save is not resent.
  const [stepUp, setStepUp] = useState<{ failed: boolean } | null>(() => (failed ? { failed: true } : null));
  const send = async (): Promise<void> => {
    if (sending.current) return;
    sending.current = true;
    setSaving(true);
    say("");
    try {
      await saveContact({ phone: draft.phone.trim(), email: draft.email.trim() });
      contactDraft = null;
      failedSteps.delete("contact");
      say("연락처를 저장했습니다.");
    } catch (error) {
      say(accountMessage(error), true);
      if (isStepUpRequired(error)) setStepUp({ failed: false });
      // The registrar owns this contact now: show the server's current state.
      else if (error instanceof Error && "code" in error && error.code === "contact_managed_by_registrar") void reloadAccount(profiles);
    } finally {
      sending.current = false;
      setSaving(false);
    }
  };
  return (
    <details
      className="acct-edit"
      open={returned || failed || draft.open || undefined}
      onToggle={(event) => { draft.open = event.currentTarget.open; }}
    >
      <summary>연락처 바꾸기</summary>
      <div className="acct-form">
        <Field label="전화번호">
          <input type="tel" autoComplete="tel" value={draft.phone} aria-describedby="acct-contact-status"
            onChange={(event) => { draft.phone = event.currentTarget.value; bump(); }} />
        </Field>
        <Field label="이메일">
          <input type="email" autoComplete="email" value={draft.email} aria-describedby="acct-contact-status"
            onChange={(event) => { draft.email = event.currentTarget.value; bump(); }} />
        </Field>
        <div className="btn-row">
          <Button kind="primary" disabled={saving} aria-busy={saving || undefined} onClick={() => void send()}>
            {saving ? "저장하는 중…" : "연락처 저장"}
          </Button>
        </div>
        <div>
          {stepUp ? <StepUpBox returnStep="contact" retry={send} say={say} failed={stepUp.failed} onDone={() => setStepUp(null)} /> : null}
        </div>
      </div>
    </details>
  );
}

// Same first sentence as the `/?reauth=failed` toast in app.js.
const REAUTH_FAILED = "학교 계정으로 다시 인증하지 못했습니다. 요청은 보내지 않았습니다. 다시 인증한 뒤 시도하세요.";

/**
 * Re-authentication the backend offers for this session (`GET /api/me` `stepUpMethod`). `retry`
 * resends the command after an in-page step-up (break-glass TOTP, local dev); the school IdP is a
 * page navigation that comes back to this dialog with `step=<returnStep>` (the user sends the
 * command again from there) or, when it failed, with `reauth=failed` as well (`failed`).
 * `onDone` removes the box after an in-page step-up.
 */
function StepUpBox({ returnStep, retry, say, failed = false, onDone }: {
  returnStep: string;
  retry: () => Promise<void>;
  say: Say;
  failed?: boolean;
  onDone: () => void;
}) {
  const [method] = stepUpMethods();
  const [busyLabel, setBusyLabel] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const confirmWith = async (step: () => Promise<unknown>) => {
    if (busyLabel) return;
    failedSteps.delete(returnStep);
    setBusyLabel("확인하는 중…");
    try {
      await step();
      onDone();
      await retry();
    } catch (error) {
      say(accountMessage(error), true);
      setBusyLabel(null);
    }
  };
  const busy = busyLabel !== null;
  return (
    <div className="acct-stepup" role="group" aria-label="본인 확인">
      {failed ? <p className="acct-status" role="alert">{REAUTH_FAILED}</p> : null}
      <p className="note">보안을 위해 최근에 다시 인증한 세션에서만 할 수 있습니다.</p>
      {!method ? <p className="note">이 세션은 여기서 다시 인증할 수 없습니다. 로그아웃한 뒤 다시 로그인하고 시도하세요.</p> : null}
      {method === "totp" ? (
        <Field label="인증 앱 코드">
          <input inputMode="numeric" autoComplete="one-time-code" maxLength={8} value={code} onChange={(event) => setCode(event.currentTarget.value)} />
        </Field>
      ) : null}
      {method ? (
        <div className="btn-row">
          {method === "idp" ? (
            <Button kind="primary" disabled={busy} aria-busy={busy || undefined} onClick={() => {
              failedSteps.delete(returnStep);
              setBusyLabel("학교 로그인 화면으로 이동합니다…");
              const params = new URLSearchParams(location.search);
              params.set("step", returnStep);
              startReauth(`${location.pathname}?${params}`);
            }}>
              {busyLabel ?? "학교 계정으로 다시 인증"}
            </Button>
          ) : null}
          {method === "dev" ? (
            <Button kind="secondary" disabled={busy} aria-busy={busy || undefined} onClick={() => void confirmWith(stepUpDev)}>
              {busyLabel ?? "개발용 본인 확인"}
            </Button>
          ) : null}
          {method === "totp" ? (
            <Button kind="primary" disabled={busy} aria-busy={busy || undefined} onClick={() => void confirmWith(() => stepUpTotp(code))}>
              {busyLabel ?? "확인"}
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

// ---------- 아바타 ----------

function AvatarForm() {
  const entry = useAccount(profiles, "account-avatar");
  const [note, say] = useNote();
  const bump = useBump();
  const [saving, setSaving] = useState(false);
  if (!entry || (!entry.data && !entry.error)) {
    return <div className="acct" aria-live="polite"><LoadingState title="아바타를 불러오는 중입니다" /></div>;
  }
  if (!entry.data) {
    return (
      <div className="acct" aria-live="polite">
        <ErrorState title="아바타를 불러오지 못했습니다" body={accountMessage(entry.error)} actions={<RetryButton resource={profiles} />} />
      </div>
    );
  }
  const p = entry.data;
  const avatar = p.avatar || {};
  const draft = avatarDraft || {
    color: isAvatarColor(avatar.color) ? avatar.color : AVATAR_COLORS[0]![0],
    shortName: avatar.shortName || "",
  };
  avatarDraft = draft;
  // Saving a colour turns a photo avatar back into a colour one and discards a photo still processing.
  const replacesPhoto = avatar.type === "image" || Boolean(avatar.photo && avatar.photo.state === "processing");
  const saveColour = async () => {
    if (saving) return;
    setSaving(true);
    say("");
    try {
      await saveAvatar({ avatarColor: draft.color, shortName: draft.shortName.trim(), version: avatar.version || 0 });
      avatarDraft = null;
      say(replacesPhoto ? "색 아바타를 저장했습니다. 사진 대신 이 아바타를 씁니다." : "아바타를 저장했습니다.");
    } catch (error) {
      say(accountMessage(error), true);
      // Stale version: nothing changed. Reload the profile and keep the choice for another try.
      if (isVersionConflict(error)) void reloadAccount(profiles);
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className="acct" aria-live="polite">
      <PhotoPart p={p} />
      <h3 className="acct-subhead">색 아바타</h3>
      <div className="who">
        <span className={`avatar acct-avatar is-xl avatar-bg-${draft.color}`} aria-hidden="true">
          {draft.shortName || String(p.name || "").slice(0, 1) || "?"}
        </span>
      </div>
      <Field label="표시 글자">
        <input value={draft.shortName} maxLength={16} autoComplete="off" aria-describedby="acct-avatar-hint"
          onChange={(event) => { draft.shortName = event.currentTarget.value; bump(); }} />
      </Field>
      <p id="acct-avatar-hint" className="note">
        {replacesPhoto
          ? "두 글자까지 씁니다. 비우면 이름의 첫 글자를 씁니다. 색 아바타를 저장하면 사진은 지워집니다."
          : "두 글자까지 씁니다. 비우면 이름의 첫 글자를 씁니다."}
      </p>
      <fieldset className="acct-fieldset">
        <legend>배경색</legend>
        <div className="acct-swatches">
          {AVATAR_COLORS.map(([key, label]) => (
            <label key={key} className="acct-swatch">
              <input type="radio" name="avatarColor" value={key} checked={draft.color === key}
                onChange={(event) => { if (!event.currentTarget.checked) return; draft.color = key; bump(); }} />
              <span className={`acct-swatch__chip avatar-bg-${key}`} aria-hidden="true" />
              <span>{label}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <div className="btn-row">
        <Link href={routeFor(currentRole(), "pop", { pop: "myinfo" })} className="btn btn-secondary">이전</Link>
        <Button kind="primary" disabled={saving} aria-busy={saving || undefined} onClick={() => void saveColour()}>
          {saving ? "저장하는 중…" : "색 아바타 저장"}
        </Button>
      </div>
      <Status note={note} />
    </div>
  );
}

// ---------- 아바타 사진 ----------

const CROP_VIEW = 240; // crop square in CSS px; the drawing buffer is twice that
const CROP_OUT = 512; // uploaded JPEG side; the server rebuilds a 256 px JPEG
const CROP_ZOOM_MAX = 4;

interface Crop { bitmap: ImageBitmap; base: number; zoom: number; x: number; y: number }

// The picked photo being cropped (an ImageBitmap in memory, never a URL) and which photo state was
// last announced. Kept across re-renders; dropped when the dialog is opened again or the session changes.
let photoDraft: Crop | null = null;
let photoShown: string | undefined;
function forgetPhotoDraft(): void {
  if (photoDraft) photoDraft.bitmap.close();
  photoDraft = null;
  photoShown = undefined;
}
subscribe(() => forgetPhotoDraft());

/** Drops the crop but keeps which photo state was announced. */
function dropCrop(): void {
  const shown = photoShown;
  forgetPhotoDraft();
  photoShown = shown;
}

function clampCrop(crop: Crop): void {
  const scale = crop.base * crop.zoom;
  crop.x = Math.min(0, Math.max(CROP_VIEW - crop.bitmap.width * scale, crop.x));
  crop.y = Math.min(0, Math.max(CROP_VIEW - crop.bitmap.height * scale, crop.y));
}

/** Zooms around the centre of the crop square. */
function zoomCrop(crop: Crop, zoom: number): void {
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

function drawCrop(canvas: HTMLCanvasElement, crop: Crop): void {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const k = canvas.width / CROP_VIEW;
  const scale = crop.base * crop.zoom * k;
  ctx.fillStyle = "#ffffff"; // a transparent PNG becomes a JPEG on white
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(crop.bitmap, crop.x * k, crop.y * k, crop.bitmap.width * scale, crop.bitmap.height * scale);
}

/** Says what changed about the uploaded photo, once per state. */
function announce(photo: AvatarPhoto | null, say: Say): void {
  const key = photo ? `${photo.state}:${photo.errorCode || ""}` : "";
  if (key === photoShown) return;
  const opened = photoShown === undefined;
  photoShown = key;
  if (!photo) return;
  if (photo.state === "processing") say("사진을 검사하고 다듬는 중입니다. 끝나면 여기에 보입니다.");
  else if (photo.state === "ready" && !opened) say("사진을 적용했습니다.");
  else if (photo.state === "failed") say(`올린 사진을 쓰지 못했습니다. ${photoMessage(photo.errorCode)}`, true);
}

const ARROWS: Record<string, [number, number]> = { ArrowLeft: [1, 0], ArrowRight: [-1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1] };

/**
 * 사진 고르기·자르기·올리기·지우기. The file is decoded with `createImageBitmap` and drawn into a
 * canvas (the page CSP has no `blob:`), cropped to a square by dragging or the arrow keys and the
 * zoom slider (or + / -), exported with `canvas.toBlob` as a 512 px JPEG and sent as multipart `file`.
 * The server answers 202 `processing`; the profile is re-read until the photo is `ready` or `failed`.
 */
function PhotoPart({ p }: { p: Profile }) {
  const [note, say] = useNote("photo");
  const bump = useBump();
  const node = useRef<HTMLElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const drag = useRef<{ x: number; y: number } | null>(null);
  const focusCanvas = useRef(false);
  const polling = useRef(false);
  const mounted = useRef(true);
  const [sending, setSending] = useState(false);
  const avatar = p.avatar || {};
  const photo = avatar.photo || null;
  const hasPhoto = avatar.type === "image";

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    announce(photo, say);
    const host = node.current;
    if (!photo || photo.state !== "processing" || polling.current || !host) return;
    polling.current = true;
    void awaitAvatarPhoto(host).then((state) => {
      polling.current = false;
      if (state === "processing" && mounted.current) say("사진 처리가 오래 걸립니다. 잠시 뒤 이 창을 다시 열어 확인하세요.");
    });
  });

  // The crop canvas is drawn imperatively from `photoDraft` after every render.
  useEffect(() => {
    const target = canvas.current;
    if (!target || !photoDraft) return;
    clampCrop(photoDraft);
    drawCrop(target, photoDraft);
    if (focusCanvas.current) {
      focusCanvas.current = false;
      target.focus();
    }
  });

  const redraw = () => {
    if (!photoDraft || !canvas.current) return;
    clampCrop(photoDraft);
    drawCrop(canvas.current, photoDraft);
  };

  const setZoom = (value: number) => {
    if (!photoDraft) return;
    zoomCrop(photoDraft, value);
    bump();
  };

  const pick = async (input: HTMLInputElement) => {
    const file = input.files && input.files[0];
    input.value = "";
    if (!file) return;
    if (!PHOTO_TYPES.has(file.type)) return say("PNG나 JPEG 파일만 올릴 수 있습니다.", true);
    if (file.size > PHOTO_MAX_BYTES) return say("5MB 이하의 사진만 올릴 수 있습니다.", true);
    let bitmap: ImageBitmap;
    try {
      bitmap = await createImageBitmap(file);
    } catch {
      return say("사진을 읽지 못했습니다. 다른 PNG나 JPEG 파일을 고르세요.", true);
    }
    dropCrop();
    const base = Math.max(CROP_VIEW / bitmap.width, CROP_VIEW / bitmap.height);
    photoDraft = { bitmap, base, zoom: 1, x: (CROP_VIEW - bitmap.width * base) / 2, y: (CROP_VIEW - bitmap.height * base) / 2 };
    say("사진을 끌거나 화살표 키로 옮기고 확대를 맞춘 뒤 올리세요.");
    focusCanvas.current = true;
    bump();
  };

  const send = async () => {
    if (sending || !photoDraft) return;
    setSending(true);
    say("");
    try {
      const out = document.createElement("canvas");
      out.width = CROP_OUT;
      out.height = CROP_OUT;
      drawCrop(out, photoDraft);
      const blob = await new Promise<Blob | null>((resolve) => out.toBlob(resolve, "image/jpeg", 0.9));
      if (!blob) throw new Error("사진을 만들지 못했습니다. 다른 사진을 고르세요.");
      if (blob.size > PHOTO_MAX_BYTES) throw new Error(photoMessage("too_large"));
      photoShown = "";
      await uploadAvatarPhoto(blob, "avatar.jpg");
      dropCrop();
      say("사진을 올렸습니다. 검사하고 다듬는 중입니다. 끝나면 여기에 보입니다.");
    } catch (error) {
      say(photoMessage(error), true);
    } finally {
      setSending(false);
    }
  };

  const remove = async () => {
    say("");
    try {
      await removeAvatarPhoto();
      photoShown = "";
      say("사진을 지웠습니다. 색 아바타를 씁니다.");
    } catch (error) {
      say(accountMessage(error), true);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLCanvasElement>) => {
    if (!photoDraft) return;
    const step = event.shiftKey ? 32 : 8;
    const move = ARROWS[event.key];
    if (move) {
      photoDraft.x += move[0] * step;
      photoDraft.y += move[1] * step;
      redraw();
    } else if (event.key === "+" || event.key === "=") setZoom(photoDraft.zoom + 0.1);
    else if (event.key === "-" || event.key === "_") setZoom(photoDraft.zoom - 0.1);
    else return;
    event.preventDefault();
  };

  const onPointerMove = (event: PointerEvent<HTMLCanvasElement>) => {
    const from = drag.current;
    if (!from || !photoDraft) return;
    const k = CROP_VIEW / (event.currentTarget.getBoundingClientRect().width || CROP_VIEW);
    photoDraft.x += (event.clientX - from.x) * k;
    photoDraft.y += (event.clientY - from.y) * k;
    drag.current = { x: event.clientX, y: event.clientY };
    redraw();
  };

  const stop = () => { drag.current = null; };

  return (
    <section ref={node} className="acct-photo" aria-labelledby="acct-photo-head">
      <h3 id="acct-photo-head" className="acct-subhead">사진</h3>
      <div className="acct-photo__current">
        {hasPhoto ? avatarBadge(p) : null}
        <p className="note">{hasPhoto ? "지금 쓰는 사진입니다." : "사진이 없어 색 아바타를 씁니다."}</p>
        {hasPhoto || avatar.photo ? (
          <div className="btn-row">
            <ConfirmButton label="사진 지우기" armedLabel="한 번 더 누르면 사진 지우기" busyLabel="지우는 중…" run={remove} />
          </div>
        ) : null}
      </div>
      <div className="acct-photo__editor">
        {!photoDraft ? (
          <>
            <Field label="사진 파일">
              <input type="file" accept="image/png,image/jpeg" aria-describedby="acct-photo-hint" onChange={(event) => void pick(event.currentTarget)} />
            </Field>
            <p id="acct-photo-hint" className="note">PNG나 JPEG, 5MB 이하. 고른 뒤 정사각형으로 잘라 올립니다.</p>
          </>
        ) : (
          <>
            <canvas
              ref={canvas}
              className="acct-crop"
              width={CROP_VIEW * 2}
              height={CROP_VIEW * 2}
              tabIndex={0}
              role="img"
              aria-label="자를 영역. 화살표 키로 옮기고 + 와 - 키로 확대하거나 줄입니다."
              onPointerDown={(event) => {
                drag.current = { x: event.clientX, y: event.clientY };
                event.currentTarget.setPointerCapture(event.pointerId);
              }}
              onPointerMove={onPointerMove}
              onPointerUp={stop}
              onPointerCancel={stop}
              onKeyDown={onKeyDown}
            />
            <Field label="확대">
              <input type="range" min="1" max={String(CROP_ZOOM_MAX)} step="0.05" value={String(photoDraft.zoom)}
                onChange={(event) => setZoom(Number(event.currentTarget.value))} />
            </Field>
            <div className="btn-row">
              <Button kind="primary" disabled={sending} aria-busy={sending || undefined} onClick={() => void send()}>
                {sending ? "올리는 중…" : "사진 올리기"}
              </Button>
              <Button kind="ghost" onClick={() => { dropCrop(); say(""); }}>취소</Button>
            </div>
          </>
        )}
      </div>
      <Status note={note} />
    </section>
  );
}

// ---------- 설정 ----------

function themeModeOf(theme: string): "dark" | "light" | "system" {
  return theme === "dark" || theme === "light" ? theme : "system";
}

function Choice({ label, options, value, onChange }: { label: string; options: Record<string, string>; value: string; onChange: (value: string) => void }) {
  return (
    <Field label={label}>
      <select value={value} onChange={(event) => onChange(event.currentTarget.value)}>
        {Object.entries(options).map(([key, text]) => <option key={key} value={key}>{text}</option>)}
      </select>
    </Field>
  );
}

function SettingsForm() {
  const entry = useAccount(preferences, "account-preferences");
  const [note, say] = useNote("preferences");
  const bump = useBump();
  const [saving, setSaving] = useState(false);
  if (!entry || (!entry.data && !entry.error)) {
    return <div className="acct" aria-live="polite"><LoadingState title="설정을 불러오는 중입니다" /></div>;
  }
  if (!entry.data) {
    return (
      <div className="acct" aria-live="polite">
        <ErrorState title="설정을 불러오지 못했습니다" body={accountMessage(entry.error)} actions={<RetryButton resource={preferences} />} />
      </div>
    );
  }
  const saved = entry.data;
  const dnd = saved.doNotDisturb || { enabled: false, start: null, end: null };
  const draft: PrefDraft = prefDraft || {
    language: saved.language, timeFormat: saved.timeFormat, theme: saved.theme,
    doNotDisturb: { enabled: Boolean(dnd.enabled), start: dnd.start || "22:00", end: dnd.end || "07:00" },
    version: saved.version,
  };
  draft.version = saved.version;
  prefDraft = draft;
  const set = (change: () => void) => { change(); bump(); };

  /**
   * Sends the dialog's values with the loaded `version`, then applies the saved theme and language
   * to this browser the same way the local settings do (App writes `data-theme` from the state, so
   * the theme boot opens the next page in the saved mode).
   */
  const submit = async () => {
    if (saving) return;
    setSaving(true);
    say("");
    const off = draft.doNotDisturb;
    try {
      const result = await savePreferences({
        language: draft.language,
        timeFormat: draft.timeFormat,
        theme: draft.theme,
        doNotDisturb: off.enabled ? { enabled: true, start: off.start, end: off.end } : { enabled: false, start: null, end: null },
        version: draft.version,
      });
      prefDraft = null;
      const state = getState();
      state.themeMode = themeModeOf(result.theme);
      state.theme = state.themeMode === "system" ? systemTheme() : state.themeMode;
      state.lang = result.language === "en" ? "en" : "ko";
      state.toast = "설정을 저장했습니다";
      save();
      render();
    } catch (error) {
      say(accountMessage(error), true);
      if (isVersionConflict(error)) void reloadAccount(preferences);
    } finally {
      setSaving(false);
    }
  };

  const local = getState().themeMode || "system";
  return (
    <div className="acct" aria-live="polite">
      <Choice label="화면 모드" options={THEME_LABEL} value={draft.theme} onChange={(value) => set(() => { draft.theme = value; })} />
      <Choice label="언어" options={LANGUAGE_LABEL} value={draft.language} onChange={(value) => set(() => { draft.language = value; })} />
      <Choice label="시간" options={TIME_LABEL} value={draft.timeFormat} onChange={(value) => set(() => { draft.timeFormat = value; })} />
      <fieldset className="acct-fieldset">
        <legend>푸시 알림 에티켓 시간</legend>
        <label className="choice">
          <input type="checkbox" checked={draft.doNotDisturb.enabled}
            onChange={(event) => { const on = event.currentTarget.checked; set(() => { draft.doNotDisturb.enabled = on; }); }} />
          <span>이 시간에는 푸시 알림을 보내지 않습니다</span>
        </label>
        <div className="acct-times" hidden={!draft.doNotDisturb.enabled}>
          <Field label="시작">
            <input type="time" value={draft.doNotDisturb.start}
              onChange={(event) => { const value = event.currentTarget.value; set(() => { draft.doNotDisturb.start = value; }); }} />
          </Field>
          <Field label="끝">
            <input type="time" value={draft.doNotDisturb.end}
              onChange={(event) => { const value = event.currentTarget.value; set(() => { draft.doNotDisturb.end = value; }); }} />
          </Field>
        </div>
        <p className="note">끝이 시작보다 이르면 다음 날까지입니다.</p>
      </fieldset>
      {local !== saved.theme
        ? <p className="note">{`이 브라우저는 지금 ${THEME_LABEL[local] || "기기 기본값"}로 보입니다. 저장하면 서버 설정에 맞춥니다.`}</p>
        : null}
      <div className="btn-row">
        <Button kind="primary" disabled={saving} aria-busy={saving || undefined} onClick={() => void submit()}>
          {saving ? "저장하는 중…" : "저장"}
        </Button>
      </div>
      <Status note={note} />
    </div>
  );
}

// ---------- 접속 기기 · 접속 이력 ----------

function HistoryTabs({ login }: { login: boolean }) {
  return (
    <div className="filters">
      <Link href={routeFor(currentRole(), "pop", { pop: "history" })} className={`chip${!login ? " on all" : ""}`} aria-current={!login ? "page" : undefined}>접속 기기</Link>
      <Link href={routeFor(currentRole(), "pop", { pop: "history-login" })} className={`chip${login ? " on all" : ""}`} aria-current={login ? "page" : undefined}>접속 이력</Link>
    </div>
  );
}

function History({ login }: { login: boolean }) {
  return (
    <div className="acct">
      <HistoryTabs login={login} />
      {login ? <LoginList /> : <div className="acct"><DeviceList /><SessionList /></div>}
    </div>
  );
}

function MoreButton({ next, loading, more }: { next: string | null; loading: boolean; more: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  if (!next) return null;
  const on = busy || loading;
  return (
    <div className="btn-row">
      <Button kind="secondary" disabled={on} aria-busy={busy || undefined} onClick={() => {
        setBusy(true);
        void more().finally(() => setBusy(false));
      }}>
        {on ? "불러오는 중…" : "더 보기"}
      </Button>
    </div>
  );
}

/**
 * A button that asks for a second press (`armedLabel`) before `run`. While `run` is pending it shows
 * `busyLabel`; afterwards the plain label, still armed.
 */
function ConfirmButton({ label, armedLabel, busyLabel, run, ariaLabel, kind = "ghost", disabled = false }: {
  label: string;
  armedLabel: string;
  busyLabel: string;
  run: () => Promise<void>;
  ariaLabel?: string;
  kind?: "ghost" | "secondary";
  disabled?: boolean;
}) {
  const [armed, setArmed] = useState(false);
  const [text, setText] = useState(label);
  const [busy, setBusy] = useState(false);
  return (
    <Button
      kind={kind}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      // The accessible name follows the visible text so the second press is announced.
      aria-label={ariaLabel ? (armed ? `${ariaLabel}: ${armedLabel}` : ariaLabel) : undefined}
      onClick={async () => {
        if (busy) return;
        if (!armed) {
          setArmed(true);
          setText(armedLabel);
          return;
        }
        setBusy(true);
        try {
          await run();
        } finally {
          setBusy(false);
          setText(label);
        }
      }}
    >
      {busy ? busyLabel : text}
    </Button>
  );
}

function DeviceList() {
  const entry = useAccount(devices, "account-devices");
  const [note, say] = useNote();
  const head = <h3>등록된 기기</h3>;
  let body: ReactNode;
  if (!entry || (!entry.data && !entry.error)) body = <LoadingState title="기기를 불러오는 중입니다" />;
  else if (!entry.data) body = <ErrorState title="기기를 불러오지 못했습니다" body={accountMessage(entry.error)} actions={<RetryButton resource={devices} />} />;
  else if (!entry.data.items.length) body = <EmptyState title="등록된 기기가 없습니다" />;
  else {
    const rows = entry.data.items.map((device) => [
      <span key="name"><b>{device.name || "이름 없는 기기"}</b>{device.current ? " " : null}{device.current ? <Badge tone="success">현재</Badge> : null}</span>,
      CLIENT_LABEL[device.clientType ?? ""] || device.clientType || "",
      koDateTime(device.lastAccessAt) || "-",
      device.certified ? "Y" : "-",
      <ConfirmButton
        key={device.deviceId}
        label="삭제"
        ariaLabel={`${device.name || "기기"} 삭제`}
        armedLabel={device.current ? "이 기기에서 로그아웃됩니다. 한 번 더 누르면 삭제" : "한 번 더 누르면 삭제"}
        busyLabel="삭제하는 중…"
        run={async () => {
          say("");
          try {
            const result = await revokeDevice(device.deviceId);
            say(result && result.currentSessionRevoked ? "이 기기를 지워 로그아웃되었습니다." : "기기를 삭제했습니다.");
          } catch (error) {
            say(accountMessage(error), true);
          }
        }}
      />,
    ]);
    body = (
      <>
        <Table headers={["접속환경", "구분", "최종접속일", "기기인증여부", "삭제"]} rows={rows} />
        <MoreButton next={entry.data.nextCursor} loading={Boolean(entry.loading)} more={() => loadMore(devices)} />
        <Status note={note} />
      </>
    );
  }
  return <section className="acct-section" aria-label="등록된 기기">{head}{body}</section>;
}

function SessionList() {
  const entry = useAccount(sessions, "account-sessions");
  const [note, say] = useNote();
  // Back from a failed IdP re-authentication: offer it again; the command is not resent.
  const [stepUp, setStepUp] = useState<{ failed: boolean } | null>(() => (failedSteps.has("revoke-others") ? { failed: true } : null));
  const head = <h3>로그인 중인 세션</h3>;
  if (!entry || (!entry.data && !entry.error)) {
    return <section className="acct-section" aria-label="로그인 중인 세션">{head}<LoadingState title="세션을 불러오는 중입니다" /></section>;
  }
  if (!entry.data) {
    return (
      <section className="acct-section" aria-label="로그인 중인 세션">
        {head}
        <ErrorState title="세션을 불러오지 못했습니다" body={accountMessage(entry.error)} actions={<RetryButton resource={sessions} />} />
      </section>
    );
  }
  const items = Array.isArray(entry.data.sessions) ? entry.data.sessions : [];
  const rows = items.map((item) => [
    <span key="agent"><b>{item.userAgent || "알 수 없는 브라우저"}</b>{item.current ? " " : null}{item.current ? <Badge tone="success">지금 세션</Badge> : null}</span>,
    item.ipAddress || "-",
    koDateTime(item.authenticatedAt) || "-",
    koDateTime(item.lastSeenAt) || "-",
    item.current ? "" : (
      <ConfirmButton
        key={item.sessionId}
        label="끝내기"
        ariaLabel={`${item.ipAddress || ""} 세션 끝내기`}
        armedLabel="한 번 더 누르면 끝내기"
        busyLabel="끝내는 중…"
        run={async () => {
          try {
            await revokeSession(item.sessionId);
            say("세션을 끝냈습니다.");
          } catch (error) {
            say(accountMessage(error), true);
          }
        }}
      />
    ),
  ]);
  const others = items.filter((item) => !item.current).length;
  const endOthers = async (): Promise<void> => {
    say("");
    try {
      const result = await revokeOtherSessions();
      setStepUp(null);
      const revoked = result && typeof result === "object" && "revokedSessions" in result ? Number(result.revokedSessions) || 0 : 0;
      say(`다른 세션 ${revoked}개를 끝냈습니다.`);
    } catch (error) {
      say(accountMessage(error), true);
      if (isStepUpRequired(error)) setStepUp({ failed: false });
    }
  };
  const returned = new URLSearchParams(location.search).get("step") === "revoke-others";
  return (
    <section className="acct-section" aria-label="로그인 중인 세션">
      {head}
      {items.length ? <Table headers={["브라우저", "IP", "로그인", "마지막 요청", ""]} rows={rows} /> : <EmptyState title="로그인 중인 세션이 없습니다" />}
      <div className="btn-row">
        <ConfirmButton kind="secondary" label="다른 세션 모두 끝내기" armedLabel="한 번 더 누르면 모두 끝내기" busyLabel="끝내는 중…" disabled={others === 0} run={endOthers} />
      </div>
      {returned && others ? <p className="note">다시 인증했으면 다른 세션 모두 끝내기를 한 번 더 누르세요.</p> : null}
      <div>
        {stepUp ? <StepUpBox returnStep="revoke-others" retry={endOthers} say={say} failed={stepUp.failed} onDone={() => setStepUp(null)} /> : null}
      </div>
      <Status note={note} />
    </section>
  );
}

function LoginList() {
  const entry = useAccount(loginEvents, "account-logins");
  const head = <p className="note acct-right">최근 7일간의 기록만 조회됩니다.</p>;
  let body: ReactNode;
  if (!entry || (!entry.data && !entry.error)) body = <LoadingState title="로그인 기록을 불러오는 중입니다" />;
  else if (!entry.data) body = <ErrorState title="로그인 기록을 불러오지 못했습니다" body={accountMessage(entry.error)} actions={<RetryButton resource={loginEvents} />} />;
  else if (!entry.data.items.length) body = <EmptyState title="최근 7일 동안의 로그인 기록이 없습니다" />;
  else {
    const rows = entry.data.items.map((item) => [
      item.ipAddress || "-",
      item.clientEnvironment || "-",
      koDateTime(item.loggedInAt) || "-",
      item.success ? "성공" : <Badge tone="danger">실패</Badge>,
    ]);
    body = (
      <>
        <Table headers={["IP", "앱", "접속시간", "결과"]} rows={rows} />
        <MoreButton next={entry.data.nextCursor} loading={Boolean(entry.loading)} more={() => loadMore(loginEvents)} />
      </>
    );
  }
  return <section className="acct-section" aria-label="로그인 기록">{head}{body}</section>;
}
