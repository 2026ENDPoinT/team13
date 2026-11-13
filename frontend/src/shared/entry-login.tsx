import { useLayoutEffect, useRef, useSyncExternalStore, type KeyboardEvent } from "react";
import { backendStatus, subscribe } from "./backend.ts";
import { BackendLoginForm } from "./backend-prompt.tsx";
import { Button } from "../ui/kit.tsx";

/**
 * A sign-in asked for while the STack session is still signed in: the school's e-Class session behind it has ended
 * (409 `school_signin_required`). `done` runs after the sign-in succeeds, so the screen that asked reads its item again.
 */
let again: { done: () => void } | null = null;
const listeners = new Set<() => void>();

function onAgain(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Opens the login modal over a signed-in screen; `done` runs once the e-Class sign-in succeeded. */
export function requestSchoolSignin(done: () => void): void {
  again = { done };
  listeners.forEach((listener) => listener());
}

function closeAgain(signedIn: boolean): void {
  const pending = again;
  again = null;
  listeners.forEach((listener) => listener());
  if (signedIn && pending) pending.done();
}

const asked = () => again !== null;

/** The login modal is the only sign-in surface: it is open while the server session is anonymous, or when a screen asks to sign in to e-Class again. */
export function useEntryLogin(): boolean {
  const anon = useSyncExternalStore(subscribe, backendStatus) === "anon";
  return useSyncExternalStore(onAgain, asked) || anon;
}

export function EntryLoginDialog() {
  const scope = useRef<HTMLDivElement>(null);
  // Asked again over a signed-in screen (not anonymous): the student may also close it and stay on the page.
  const resign = useSyncExternalStore(onAgain, asked) && backendStatus() !== "anon";
  useLayoutEffect(() => {
    const node = scope.current!;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const focusFirst = () => (node.querySelector<HTMLElement>("input:not(:disabled)") || node.querySelector<HTMLElement>("button:not(:disabled)"))?.focus();
    focusFirst();
    const keepFocus = (event: FocusEvent) => {
      if (event.target instanceof Node && !node.contains(event.target)) focusFirst();
    };
    document.addEventListener("focusin", keepFocus);
    return () => {
      document.removeEventListener("focusin", keepFocus);
      document.body.style.overflow = overflow;
    };
  }, []);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape" && resign) {
      closeAgain(false);
      return;
    }
    if (event.key !== "Tab") return;
    const controls = scope.current!.querySelectorAll<HTMLElement>("button:not(:disabled), input:not(:disabled), a[href]");
    const first = controls[0];
    const last = controls[controls.length - 1];
    if ((event.shiftKey && document.activeElement === first) || (!event.shiftKey && document.activeElement === last)) {
      event.preventDefault();
      (event.shiftKey ? last : first)?.focus();
    }
  };

  return (
    <div ref={scope} className="entry-login" onKeyDown={onKeyDown}>
      <div className="modal-back" onMouseDown={(event) => { if (event.target === event.currentTarget) event.preventDefault(); }}>
        <div className="modal entry-login__modal" role="dialog" aria-modal="true" aria-label={resign ? "e-Class 다시 로그인" : "STack 로그인"}>
          <div className="modal-h"><h2>{resign ? "e-Class 다시 로그인" : "STack 로그인"}</h2></div>
          <div className="modal-b">
            <BackendLoginForm
              text={resign
                ? "학교 e-Class 연결이 끝났습니다. 학번(ID)과 e-Class 비밀번호(PW)로 다시 로그인하면 보던 자료를 다시 엽니다."
                : "학번(ID)과 e-Class 비밀번호(PW)를 입력해 주세요."}
              onSignedIn={() => closeAgain(true)}
            />
            {resign ? <div className="btn-row"><Button kind="ghost" onClick={() => closeAgain(false)}>닫기</Button></div> : null}
          </div>
        </div>
      </div>
    </div>
  );
}
