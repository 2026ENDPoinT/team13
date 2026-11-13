import { useLayoutEffect, useRef, useSyncExternalStore, type KeyboardEvent } from "react";
import { backendStatus, subscribe } from "./backend.ts";
import { BackendLoginForm } from "./backend-prompt.tsx";

/** The login modal is the only sign-in surface: it is open for as long as the server session is anonymous. */
export function useEntryLogin(): boolean {
  return useSyncExternalStore(subscribe, backendStatus) === "anon";
}

export function EntryLoginDialog() {
  const scope = useRef<HTMLDivElement>(null);
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
        <div className="modal entry-login__modal" role="dialog" aria-modal="true" aria-label="STack 로그인">
          <div className="modal-h"><h2>STack 로그인</h2></div>
          <div className="modal-b"><BackendLoginForm text="학번(ID)과 e-Class 비밀번호(PW)를 입력해 주세요." /></div>
        </div>
      </div>
    </div>
  );
}
