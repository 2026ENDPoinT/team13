import { useEffect, useRef, useState } from "react";
import { Icon } from "../../ui/icon.tsx";

export interface MoveOptions {
  /** Folders the item can go into (the one it is in is left out). */
  targets: { id: string; name: string }[];
  /** True when the item may also go back to the course root. */
  canLeave: boolean;
  /** `""` is the course root. Reports its own success and failure. */
  onMove: (folderId: string) => Promise<void>;
}

export interface RemoveOptions {
  /** Why this card cannot be deleted here; the item is shown disabled with this as its hint. */
  blocked?: string;
  /** Reports its own success and failure. */
  onRemove: () => Promise<void>;
}

/**
 * The vertical-dots menu at the right of a card title: "폴더로 이동" (items only) and "삭제" (a second press
 * confirms). The card itself is a stretched link, so this button sits above it and never follows the link.
 */
export function CardMenu({ title, move, remove }: { title: string; move?: MoveOptions; remove: RemoveOptions }) {
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<"main" | "move">("main");
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  const close = () => {
    setOpen(false);
    setView("main");
    setArmed(false);
  };

  useEffect(() => {
    if (!open) return;
    wrap.current?.querySelector<HTMLElement>("[role=menuitem]:not([aria-disabled=true])")?.focus();
    const onPointer = (event: PointerEvent) => {
      if (!wrap.current || !wrap.current.contains(event.target as Node)) close();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      close();
      trigger.current?.focus();
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, view]);

  const run = (task: () => Promise<void>) => {
    setBusy(true);
    task().finally(() => {
      setBusy(false);
      close();
    });
  };
  const canMove = Boolean(move && (move.targets.length || move.canLeave));

  return (
    <div ref={wrap} className="sf-menu">
      <button
        ref={trigger} type="button" className="sf-menu-btn" aria-haspopup="menu" aria-expanded={open ? "true" : "false"}
        aria-label={`${title} 메뉴`} disabled={busy} onClick={() => (open ? close() : setOpen(true))}
      >
        <Icon name="more" />
      </button>
      {open ? (
        <div className="study-new-menu" role="menu" aria-label={`${title} 메뉴`}>
          {view === "main" ? (
            <>
              {move ? (
                <button
                  type="button" role="menuitem" className="study-new-item" aria-disabled={canMove ? undefined : "true"}
                  title={canMove ? undefined : "옮길 수 있는 폴더가 없습니다."} onClick={() => { if (canMove) setView("move"); }}
                >
                  <Icon name="folder" /><span>폴더로 이동</span>
                </button>
              ) : null}
              <button
                type="button" role="menuitem" className="study-new-item is-danger" aria-disabled={remove.blocked ? "true" : undefined} title={remove.blocked}
                onClick={() => {
                  if (remove.blocked) return;
                  if (!armed) setArmed(true);
                  else run(remove.onRemove);
                }}
              >
                <Icon name="trash" /><span>{armed ? "한 번 더 누르면 삭제" : "삭제"}</span>
              </button>
            </>
          ) : move ? (
            <>
              <button type="button" role="menuitem" className="study-new-item" onClick={() => setView("main")}>
                <span>← 뒤로</span>
              </button>
              <span className="study-new-sep" role="separator" />
              {move.canLeave ? (
                <button type="button" role="menuitem" className="study-new-item" onClick={() => run(() => move.onMove(""))}>
                  <Icon name="folder" /><span>과목 폴더(맨 위)</span>
                </button>
              ) : null}
              {move.targets.map((target) => (
                <button key={target.id} type="button" role="menuitem" className="study-new-item" title={target.name} onClick={() => run(() => move.onMove(target.id))}>
                  <Icon name="folder" /><span className="sf-menu-target">{target.name}</span>
                </button>
              ))}
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
