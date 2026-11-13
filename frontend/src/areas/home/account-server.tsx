// Account dialogs on the ASTRA backend while the connection is not `off`: 내 정보 (profile, registrar
// sync and contact behind re-authentication) and 설정 (server preferences with `version`). `anon` shows only
// the login strip. Server text goes in as text only.
import { useEffect, useReducer, useRef, useState, type ReactNode } from "react";
import { Button, Dialog, ErrorState, Field, LoadingState } from "../../ui/kit.tsx";
import { getState, render, save } from "../../store.ts";
import { systemTheme } from "../../theme.ts";
import type { ScreenCtx } from "../../types.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { backendStatus, subscribe } from "../../shared/backend.ts";
import { freshVisit, useResource, type Entry } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { personAvatar } from "../../shared/avatar-remote.tsx";
import {
  profiles, preferences, reloadAccount, saveContact, savePreferences, isStepUpRequired, isVersionConflict, stepUpMethods, startReauth, stepUpDev, stepUpTotp, accountMessage, THEME_LABEL, LANGUAGE_LABEL, TIME_LABEL, takeReauthFailure, syncProfile, isSyncUnavailable, syncMessage,
  type AccountResource, type Profile, type ProfileContact,
} from "../../shared/account-remote.ts";

export const ACCOUNT_DIALOGS = new Set(["myinfo", "settings"]);

const TITLES: Record<string, string> = { myinfo: "내 정보", settings: "설정" };
const ROLE: Record<string, string> = { STUDENT: "학생", PROFESSOR: "교수", TA: "조교", STAFF: "교직원" };

// The single cache key account-remote uses for every account resource.
const ONE = "me";

// What the user typed or picked, kept across re-renders and 409 reloads; dropped on session change.
interface ContactDraft { phone: string; email: string; open?: boolean }
interface PrefDraft {
  language: string;
  timeFormat: string;
  theme: string;
  doNotDisturb: { enabled: boolean; start: string; end: string };
  version: number;
}
let contactDraft: ContactDraft | null = null;
let prefDraft: PrefDraft | null = null;
subscribe(() => {
  contactDraft = null;
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
  }
  const title = TITLES[name] ?? "";
  if (status !== "ready") {
    return shell(title, (
      <div className="acct">
        {backendPanel({ text: "ASTRA 서버에 로그인하면 내 계정 정보를 서버에서 불러옵니다." })}
      </div>
    ));
  }
  // A failed IdP re-authentication: remember its command, never resend it, clean the address. The
  // replaced history entry gets a new `history.state`, so the visit is marked again (not a new entry).
  const failed = takeReauthFailure();
  if (failed !== null) {
    if (failed) failedSteps.add(failed);
    freshVisit("account-dialog", name);
  }
  if (name === "myinfo") return shell(title, <MyInfo ctx={ctx} />);
  return shell(title, <SettingsForm />);
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
