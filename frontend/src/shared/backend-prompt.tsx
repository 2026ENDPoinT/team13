// Sign-in form for anonymous backend sessions. It is drawn only inside the entry login modal (entry-login.tsx);
// pages never embed it. Account/logout controls live in the shared header menu.
// Buttons follow `GET /api/auth/methods`: school OIDC navigates to `/api/auth/login`,
// e-class password login uses one request, and developer personas require `devLogin`.
// The password is cleared after the request; break-glass login is never offered here.
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Button } from "../ui/kit.tsx";
import { backendState, backendStatus, connect, loginAs, loginWithEclass, personaFor, offers, startSchoolLogin } from "./backend.ts";

const UNKNOWN_METHODS = "로그인 방법을 확인하지 못했습니다. 다시 확인을 누르거나 잠시 후 다시 시도하세요.";

/** Stands in for a page body behind a "not signed in" gate: the login modal is the only sign-in surface, so pages draw nothing. */
export const SIGNED_OUT: ReactNode = <></>;

interface Message {
  text: string;
  alert: boolean;
}

/** `onSignedIn` runs after a sign-in in this form leaves the session `ready`. */
export function BackendLoginForm({ text, onSignedIn }: { text?: string; onSignedIn?: () => void }) {
  const { methods } = backendState();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const live = useRef(true);
  const studentIdRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
    };
  }, []);
  useEffect(() => {
    if (!busy && message?.alert) passwordRef.current?.focus();
  }, [busy, message]);
  const say = (next: string, alert = false) => setMessage({ text: next, alert });


  const eclass = offers("eclassPassword");
  const oidc = offers("oidc");
  const dev = offers("devLogin");
  const hasControls = eclass || oidc || dev;
  const fallback = hasControls ? "" : Array.isArray(methods)
    ? "이 서버에서 쓸 수 있는 로그인 방법이 없습니다. 관리자에게 문의하세요."
    : UNKNOWN_METHODS;

  const run = (attempt: () => Promise<unknown>, after?: () => void) => {
    setBusy(true);
    say("로그인하는 중입니다.");
    attempt().then(() => {
      if (live.current) say("");
      if (onSignedIn && backendStatus() === "ready") onSignedIn();
    }, (error: unknown) => {
      if (live.current) say(error instanceof Error ? error.message : String(error), true);
    }).finally(() => {
      if (live.current) setBusy(false);
      if (after) after();
    });
  };

  const submitPassword = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const studentId = studentIdRef.current?.value ?? "";
    const password = passwordRef.current?.value ?? "";
    run(() => loginWithEclass(studentId, password), () => {
      const input = passwordRef.current;
      if (!input) return;
      input.value = "";
    });
  };

  const schoolLogin = () => {
    if (busy) return;
    setBusy(true);
    if (passwordRef.current) passwordRef.current.value = "";
    say("학교 로그인 화면으로 이동합니다.");
    // Coming back through the back/forward cache must not leave the controls disabled.
    window.addEventListener("pageshow", (event) => {
      if (!event.persisted || !live.current) return;
      setBusy(false);
      say("");
    }, { once: true });
    startSchoolLogin();
  };

  const recheck = () => {
    if (busy) return;
    setBusy(true);
    say("로그인 방법을 다시 확인하는 중입니다.");
    void connect().then(() => {
      if (!live.current) return;
      setBusy(false);
      say(UNKNOWN_METHODS, true);
    });
  };

  const buttons: ReactNode[] = [];
  if (oidc) {
    buttons.push(<Button key="oidc" kind="primary" className="backend-login__action" disabled={busy} onClick={schoolLogin}>학교 계정으로 로그인</Button>);
  }
  if (!hasControls && !Array.isArray(methods)) {
    // Unknown methods are retryable; a changed answer re-renders the app through subscribe().
    buttons.push(<Button key="again" kind="secondary" className="backend-login__action" data-backend-recheck="" disabled={busy} onClick={recheck}>다시 확인</Button>);
  }

  const intro = text || (eclass
    ? "ASTRA 서버에 연결되어 있습니다. 학번과 e-class 비밀번호로 로그인하면 서버에 등록된 자료와 쪽별 요약을 볼 수 있습니다."
    : "ASTRA 서버에 연결되어 있습니다. 로그인하면 서버에 등록된 자료와 쪽별 요약을 볼 수 있습니다.");
  return (
    <div className="backend-login" aria-label="ASTRA 서버 로그인">
      <p className="backend-login__text">{intro}</p>
      {eclass ? (
        <form className="backend-login__form" aria-label="학번과 e-class 비밀번호로 로그인" onSubmit={submitPassword}>
          <label className="field backend-login__field">
            <span className="field-label">학번</span>
            <input ref={studentIdRef} name="studentId" type="text" inputMode="numeric" autoComplete="username" autoCapitalize="off" spellCheck={false} required disabled={busy} />
          </label>
          <label className="field backend-login__field">
            <span className="field-label">e-class 비밀번호</span>
            <input ref={passwordRef} name="password" type="password" autoComplete="current-password" required disabled={busy} />
          </label>
          <button type="submit" className="btn btn-primary backend-login__submit" disabled={busy}>로그인</button>
          <p className="backend-login__note">비밀번호는 학교 e-Class 확인에만 보내고 저장하지 않습니다.</p>
        </form>
      ) : null}
      {buttons.length ? <div className="backend-login__actions">{buttons}</div> : null}
      {dev ? (
        <div className={`backend-login__dev${eclass || oidc ? " is-secondary" : ""}`}>
          {eclass || oidc ? <p className="backend-login__dev-label">개발자 로그인</p> : null}
          <div className="backend-login__actions">
            <Button kind="secondary" className="backend-login__action" disabled={busy} onClick={() => run(() => loginAs(personaFor("student")))}>학생(박민준)으로 로그인</Button>
            <Button kind="secondary" className="backend-login__action" disabled={busy} onClick={() => run(() => loginAs(personaFor("professor")))}>교수(박은주)로 로그인</Button>
          </div>
        </div>
      ) : null}
      <StatusLine message={message ?? (fallback ? { text: fallback, alert: false } : null)} />
    </div>
  );
}

function StatusLine({ message }: { message: Message | null }) {
  return (
    <p className="backend-login__status" role={message && message.alert ? "alert" : "status"} aria-live="polite">
      {message ? message.text : ""}
    </p>
  );
}
