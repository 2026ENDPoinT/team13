import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent } from "react";
import { Dialog } from "../ui/kit.tsx";
import { backendStatus, subscribe } from "./backend.ts";
import { backendPanel } from "./backend-prompt.tsx";

const VISIT_KEY = "stack-entry-login-seen";

function visitSeen(): boolean {
  try {
    return sessionStorage.getItem(VISIT_KEY) === "1";
  } catch {
    return false;
  }
}

/** Anonymous visits prompt once per tab; a restored session needs no prompt. */
export function useEntryLogin() {
  const status = useSyncExternalStore(subscribe, backendStatus);
  const [seen, setSeen] = useState(visitSeen);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (status === "ready") setOpen(false);
    if (seen || status === "off") return;
    setSeen(true);
    try {
      sessionStorage.setItem(VISIT_KEY, "1");
    } catch {
      // Storage may be blocked; in-memory state still prevents repeated prompts.
    }
    if (status === "anon") setOpen(true);
  }, [seen, status]);

  return { open: open && status === "anon", dismiss: () => setOpen(false) };
}

export function EntryLoginDialog({ onClose }: { onClose: () => void }) {
  const scope = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const node = scope.current!;
    const previous = document.activeElement;
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
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, []);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
    }
    if (event.key !== "Tab") return;
    const controls = scope.current!.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), a[href]');
    const first = controls[0];
    const last = controls[controls.length - 1];
    if ((event.shiftKey && document.activeElement === first) || (!event.shiftKey && document.activeElement === last)) {
      event.preventDefault();
      (event.shiftKey ? last : first)?.focus();
    }
  };

  return (
    <div ref={scope} className="entry-login" onKeyDown={onKeyDown}>
      <Dialog title="STack 로그인" className="entry-login__modal" onClose={onClose}>
        {backendPanel({ text: "학번(ID)과 e-Class 비밀번호(PW)를 입력해 주세요." })}
      </Dialog>
    </div>
  );
}
