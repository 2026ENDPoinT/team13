import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { render } from "../../store.ts";
import { bindDock, dockStatus, dropPending, submitDock, type CommandInput } from "./command-bar.tsx";

/** 홈 밖 화면의 챗봇 입력창. 우측 하단 마스코트 FAB 위에 놓이고, 홈 히어로 입력창과 같은 명령 처리(`submitDock`)를 쓴다. */
export function ChatDock(): ReactNode {
  const input = useRef<HTMLInputElement>(null);
  const valueRef = useRef("");
  const [value, setValue] = useState("");
  const [handle] = useState<CommandInput>(() => ({
    value: () => valueRef.current,
    clear: () => {
      valueRef.current = "";
      setValue("");
    },
    focus: () => input.current?.focus(),
  }));
  const { busy, say } = dockStatus();

  useEffect(() => {
    bindDock(handle);
    // `/`: 입력 요소에 포커스가 없을 때 챗봇 입력창으로. 대화상자가 열려 있으면 건드리지 않는다.
    const onSlash = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey || event.defaultPrevented) return;
      const target = event.target;
      if (target instanceof Element && target.closest("input, textarea, select, [contenteditable]")) return;
      if (document.querySelector("[role=dialog]") || document.getElementById("assistant-input") || !input.current) return;
      event.preventDefault();
      input.current.focus();
      input.current.select();
    };
    document.addEventListener("keydown", onSlash);
    return () => {
      document.removeEventListener("keydown", onSlash);
      bindDock(null);
    };
  }, [handle]);

  const change = (next: string) => {
    dropPending();
    valueRef.current = next;
    setValue(next);
  };

  const onSubmit = (event: { preventDefault(): void }) => {
    event.preventDefault();
    void submitDock(handle);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Escape" || event.nativeEvent.isComposing || event.keyCode === 229) return;
    if (!value && !say) return;
    event.preventDefault();
    change("");
    render();
  };

  return (
    <form className="chat-dock" role="search" aria-label="챗봇" onSubmit={onSubmit}>
      <p className="chat-say" aria-live="polite" aria-atomic="true">{say}</p>
      <div className={`chat-box${busy ? " is-busy" : ""}`}>
        <input
          ref={input}
          type="text"
          className="chat-input"
          aria-label="챗봇 입력: 과목 이동이나 할 일 요청"
          aria-busy={busy ? "true" : "false"}
          readOnly={busy}
          placeholder="무엇을 할까요? 예: 데베 강의실로 이동"
          autoComplete="off"
          autoCapitalize="off"
          spellCheck="false"
          enterKeyHint="send"
          value={value}
          onChange={(event) => change(event.target.value)}
          onKeyDown={onKeyDown}
        />
        <button type="submit" className="chat-go" aria-label="보내기" disabled={busy || !value.trim()}>
          <span aria-hidden="true">→</span>
        </button>
        <span className="chat-spin" aria-hidden="true" />
      </div>
    </form>
  );
}
