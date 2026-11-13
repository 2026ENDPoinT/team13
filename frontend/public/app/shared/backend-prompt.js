// Small ASTRA backend login/logout strip. Renders nothing in mock mode (status `off`).
// Screens that read server data place it in their own content; the shell is untouched.
// Buttons follow `GET /api/auth/methods`: school OIDC navigates to `/api/auth/login`,
// e-class password login uses one request, and developer personas require `devLogin`.
// The password is cleared after the request; break-glass login is never offered here.
import { el } from "../dom.js";
import { btn } from "../ui/components.js";
import { backendState, connect, loginAs, loginWithEclass, logout, personaFor, offers, startSchoolLogin } from "./backend.js";

const ROLE_LABEL = { STUDENT: "학생", PROFESSOR: "교수", TA: "조교", STAFF: "교직원" };

/** null when the backend is off; otherwise a login prompt (anon) or the signed-in line (ready). */
export function backendPanel(options = {}) {
  const { status, me, methods } = backendState();
  if (status === "off") return null;
  const compact = options.compact ? " is-compact" : "";
  const message = el("p", { class: "backend-login__status", role: "status", "aria-live": "polite" });
  const busy = (controls, on) => controls.forEach((control) => { control.disabled = on; });

  if (status === "ready" && me) {
    const out = btn("로그아웃", { class: "btn btn-ghost backend-login__action" }, "ghost");
    out.addEventListener("click", () => {
      busy([out], true);
      logout().finally(() => { if (out.isConnected) busy([out], false); });
    });
    return el("div", { class: `backend-login is-ready${compact}`, "aria-label": "ASTRA 서버 연결" },
      el("p", { class: "backend-login__who" },
        el("span", { class: "backend-login__dot", "aria-hidden": "true" }),
        `ASTRA 서버 · ${me.name || "사용자"} (${ROLE_LABEL[me.role] || "사용자"})`,
      ),
      out,
      message,
    );
  }

  const eclass = offers("eclassPassword");
  const oidc = offers("oidc");
  const controls = [];
  const buttons = [];
  const run = (attempt, after) => {
    busy(controls, true);
    message.setAttribute("role", "status");
    message.textContent = "로그인하는 중입니다.";
    let failed = false;
    attempt().then(() => {
      if (message.isConnected) message.textContent = "";
    }, (error) => {
      failed = true;
      if (!message.isConnected) return;
      message.setAttribute("role", "alert");
      message.textContent = error.message;
    }).finally(() => {
      busy(controls, false);
      if (after) after(failed);
    });
  };

  let form = null;
  let passwordInput = null;
  if (eclass) {
    const studentId = el("input", { name: "studentId", type: "text", inputmode: "numeric", autocomplete: "username", autocapitalize: "off", spellcheck: "false", required: true });
    const password = el("input", { name: "password", type: "password", autocomplete: "current-password", required: true });
    passwordInput = password;
    const submit = el("button", { type: "submit", class: "btn btn-primary backend-login__submit", text: "로그인" });
    controls.push(studentId, password, submit);
    form = el("form", { class: "backend-login__form", "aria-label": "학번과 e-class 비밀번호로 로그인" },
      el("label", { class: "field backend-login__field" }, el("span", { class: "field-label", text: "학번" }), studentId),
      el("label", { class: "field backend-login__field" }, el("span", { class: "field-label", text: "e-class 비밀번호" }), password),
      submit,
      el("p", { class: "backend-login__note", text: "비밀번호는 학교 e-Class 확인에만 보내고 저장하지 않습니다." }),
    );
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      if (submit.disabled) return;
      run(() => loginWithEclass(studentId.value, password.value), (failed) => {
        password.value = "";
        if (failed && password.isConnected) password.focus();
      });
    });
  }

  if (oidc) {
    const school = btn("학교 계정으로 로그인", { class: "btn btn-primary backend-login__action" }, "primary");
    school.addEventListener("click", () => {
      if (school.disabled) return;
      busy(controls, true);
      if (passwordInput) passwordInput.value = "";
      message.setAttribute("role", "status");
      message.textContent = "학교 로그인 화면으로 이동합니다.";
      // Coming back through the back/forward cache must not leave the controls disabled.
      window.addEventListener("pageshow", (event) => {
        if (!event.persisted || !school.isConnected) return;
        busy(controls, false);
        message.textContent = "";
      }, { once: true });
      startSchoolLogin();
    });
    buttons.push(school);
    controls.push(school);
  }

  let devBlock = null;
  if (offers("devLogin")) {
    const student = btn("학생(박민준)으로 로그인", { class: "btn btn-secondary backend-login__action" }, "secondary");
    const professor = btn("교수(박은주)로 로그인", { class: "btn btn-secondary backend-login__action" }, "secondary");
    controls.push(student, professor);
    student.addEventListener("click", () => run(() => loginAs(personaFor("student"))));
    professor.addEventListener("click", () => run(() => loginAs(personaFor("professor"))));
    devBlock = el("div", { class: `backend-login__dev${eclass || oidc ? " is-secondary" : ""}` },
      eclass || oidc ? el("p", { class: "backend-login__dev-label", text: "개발자 로그인" }) : null,
      el("div", { class: "backend-login__actions" }, student, professor),
    );
  }

  if (!controls.length && Array.isArray(methods)) {
    message.textContent = "이 서버에서 쓸 수 있는 로그인 방법이 없습니다. 관리자에게 문의하세요.";
  } else if (!controls.length) {
    // Unknown methods are retryable; a changed answer re-renders the app through subscribe().
    const unknown = "로그인 방법을 확인하지 못했습니다. 다시 확인을 누르거나 잠시 후 다시 시도하세요.";
    const again = btn("다시 확인", { class: "btn btn-secondary backend-login__action", "data-backend-recheck": "" }, "secondary");
    again.addEventListener("click", () => {
      if (again.disabled) return;
      busy(controls, true);
      message.setAttribute("role", "status");
      message.textContent = "로그인 방법을 다시 확인하는 중입니다.";
      void connect().then(() => {
        if (!again.isConnected) return;
        busy(controls, false);
        message.setAttribute("role", "alert");
        message.textContent = unknown;
      });
    });
    buttons.push(again);
    controls.push(again);
    message.textContent = unknown;
  }

  const text = options.text || (eclass
    ? "ASTRA 서버에 연결되어 있습니다. 학번과 e-class 비밀번호로 로그인하면 서버에 등록된 자료와 쪽별 요약을 볼 수 있습니다."
    : "ASTRA 서버에 연결되어 있습니다. 로그인하면 서버에 등록된 자료와 쪽별 요약을 볼 수 있습니다.");
  return el("div", { class: `backend-login${compact}`, "aria-label": "ASTRA 서버 로그인" },
    el("p", { class: "backend-login__text", text }),
    form,
    buttons.length ? el("div", { class: "backend-login__actions" }, ...buttons) : null,
    devBlock,
    message,
  );
}
