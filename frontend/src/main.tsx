import "./app.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.tsx";
import { applyRoute, currentParams, followBackendRole, HOME_PATH } from "./router.tsx";
import { getState, render, toast } from "./store.ts";
import { watchSystemTheme } from "./theme.ts";
import { connect as connectBackend, subscribe as onBackendChange } from "./shared/backend.ts";

// The backend's school-login callback sends every failure to `/?login=failed`. Read it before the
// root redirect below drops the query; the message shows once, only when the backend is on.
const loginFailed = currentParams().get("login") === "failed";
// A failed re-authentication whose starting page the backend could not return to lands on
// `/?reauth=failed` (the root redirect below drops it). Shown once, only when the backend is on.
const reauthFailed = location.pathname === "/" && currentParams().get("reauth") === "failed";

if (location.pathname === "/" || location.pathname === "/index.html") {
  history.replaceState({}, "", HOME_PATH);
}

function dropLoginParam() {
  const params = currentParams();
  if (!params.has("login")) return;
  params.delete("login");
  const q = params.toString();
  history.replaceState(history.state, "", q ? `${location.pathname}?${q}` : location.pathname);
}

const standalone = window.matchMedia("(display-mode: standalone)");
function syncStandalone() {
  const installed = standalone.matches || Boolean((navigator as Navigator & { standalone?: boolean }).standalone);
  document.documentElement.classList.toggle("is-standalone", installed);
}
syncStandalone();
standalone.addEventListener("change", syncStandalone);
watchSystemTheme();
window.addEventListener("popstate", () => applyRoute());
if (navigator.serviceWorker && window.isSecureContext && import.meta.env.PROD) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

applyRoute();
createRoot(document.getElementById("app")!).render(<StrictMode><App /></StrictMode>);

// Backend mode (server.mjs with ASTRA_API_ORIGIN) is detected after the first paint.
// Mock mode answers {enabled:false}; the status stays "off" and nothing re-renders.
let backendKey = "";
onBackendChange((next: { status: string; me: unknown; courses: unknown; methods: unknown }) => {
  // `methods` is in the key: an `anon` page whose login-method check failed shows 다시 확인 and must
  // re-render with the login buttons once a later check answers.
  const key = JSON.stringify([next.status, next.me, next.courses, next.methods]);
  if (key === backendKey) return;
  backendKey = key;
  render();
});
// A page opened with a role the existing session does not match (e.g. `?as=st` while logged in
// as the professor) switches persona once here; later explicit logins are left as chosen.
connectBackend().then((next: { status: string }) => {
  if (loginFailed && next.status !== "off") {
    dropLoginParam();
    toast("학교 계정으로 로그인하지 못했습니다. 다시 시도하세요.");
  }
  if (reauthFailed && next.status !== "off") toast("학교 계정으로 다시 인증하지 못했습니다. 다시 시도하세요.");
  if (next.status === "ready") followBackendRole(getState().role);
});
