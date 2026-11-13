import { Component, useEffect, useLayoutEffect, type ReactNode } from "react";
import { useAppState, getState, render, save } from "./store.ts";
import { closeDialog, currentParams, currentScreen, followBackendRole, GoButton, HOME_PATH, navigate } from "./router.tsx";
import { routeFor } from "./shared/routes.ts";
import { observeScreen } from "./shared/assistant/plan.ts";
import { useSeenWhileOpen } from "./shared/unread-badges.tsx";
import { Shell } from "./shell/shell.tsx";
import { Button, ErrorState } from "./ui/kit.tsx";
import { Icon } from "./ui/icon.tsx";
import { asideView, dialogView, heroView, screenView } from "./areas/registry.tsx";
import { applyTheme } from "./theme.ts";
import type { Role, ScreenCtx } from "./types.ts";

const TOAST_DURATION = 3500;

/** The status line at the bottom; a message clears itself after a few seconds unless replaced. */
function Toast({ message }: { message: string }) {
  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => {
      const state = getState();
      if (state.toast !== message) return;
      state.toast = "";
      render();
    }, TOAST_DURATION);
    return () => clearTimeout(timer);
  }, [message]);
  return message ? <div className="toast" role="status">{message}</div> : null;
}

class ScreenBoundary extends Component<{ resetKey: string; children: ReactNode }, { failed: boolean; key: string }> {
  state = { failed: false, key: this.props.resetKey };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  static getDerivedStateFromProps(props: { resetKey: string }, state: { failed: boolean; key: string }) {
    return props.resetKey === state.key ? null : { failed: false, key: props.resetKey };
  }

  componentDidCatch(error: unknown) {
    console.error(error);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="chrome">
        <ErrorState title="화면을 표시하지 못했습니다" body="다시 시도해 주세요." actions={<Button kind="primary" onClick={() => navigate(HOME_PATH)}>홈으로</Button>} />
      </div>
    );
  }
}

function switchRole(role: Role) {
  const state = getState();
  state.role = role;
  state.toast = role === "professor" ? "교수 화면입니다" : "학생 화면입니다";
  save();
  navigate(routeFor(role, "home"), { allowExit: true });
  followBackendRole(role);
}

function RoleBanner({ chromeRole }: { chromeRole: Role }) {
  return (
    <div className="banner-role">
      <span>{chromeRole === "professor" ? "교수 화면입니다. 샘플 역할을 교수로 바꾸면 메뉴가 맞춰집니다." : "학생 화면입니다."}</span>
      <Button kind="primary" onClick={() => switchRole(chromeRole)}>{chromeRole === "professor" ? "교수로 전환" : "학생으로 전환"}</Button>
    </div>
  );
}

/** The message window layout (`layout: "message"`): no shell, a close button back home. */
function MessageWindow({ ctx, dialog }: { ctx: ScreenCtx; dialog: ReactNode }) {
  return (
    <div className="msg-shell">
      <div className="msg-window">
        <div className="msg-top">
          <strong>메시지</strong>
          <GoButton className="icon-btn" href={HOME_PATH} aria-label="닫기"><Icon name="close" /></GoButton>
        </div>
        <ScreenContent ctx={ctx} />
      </div>
      {dialog}
    </div>
  );
}

function ScreenContent({ ctx }: { ctx: ScreenCtx }): ReactNode {
  return screenView(ctx);
}

function DialogContent({ name, ctx }: { name: string; ctx: ScreenCtx }): ReactNode {
  return dialogView(name, ctx);
}

export function App() {
  const state = useAppState();
  const screen = currentScreen();
  const params = currentParams();
  const ctx: ScreenCtx = { state, screen, params };
  const chromeRole: Role = screen.role === "any" ? state.role : screen.role;
  const dialogName = screen.dialog || params.get("pop");
  // 계획 중인 단계와 다른 화면(다른 활동·다른 페이지)이면 계획이 스스로 일시정지한다. 트레이를 그리기 전에 해야 상태가 맞다.
  observeScreen(params.get("n") || "");
  // 머리말 할 일·쪽지·알림 목록이 열려 있으면 그 배지를 본 것으로 적는다(메시지 창은 셸 밖이라 여기서 한다).
  useSeenWhileOpen(screen, params);

  useLayoutEffect(() => {
    document.title = `STack · ${screen.title}`;
    applyTheme();
    const root = document.documentElement.classList;
    root.toggle("layout-mobile", screen.layout === "mobile");
    root.toggle("nav-open", screen.mobileMenu === "nav" || state.navOpen);
    root.toggle("aside-open", screen.mobileMenu === "aside");
  });

  // Escape closes the `?pop=` window.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && currentParams().has("pop")) closeDialog();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const key = `${location.pathname}${location.search}`;
  const dialog = dialogName ? <DialogContent name={dialogName} ctx={ctx} /> : null;
  return (
    <>
      <ScreenBoundary resetKey={key}>
        {screen.layout === "message" ? <MessageWindow ctx={ctx} dialog={dialog} /> : (
          <Shell
            ctx={ctx}
            hero={screen.kind === "home" ? heroView(ctx) : null}
            aside={() => asideView(ctx)}
            dialog={dialog}
          >
            {chromeRole !== state.role && screen.role !== "any" ? <RoleBanner chromeRole={chromeRole} /> : null}
            <ScreenContent ctx={ctx} />
          </Shell>
        )}
      </ScreenBoundary>
      <Toast message={state.toast} />
    </>
  );
}
