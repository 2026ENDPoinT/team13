// 학생의 서버 강의실 설문 상세(`?m=survey&n=srv-<surveyId>`)와 응답 창(`&pop=survey`). 응답 구간과 완료
// 여부는 서버가 정하고, 문항은 응답 기간 안이고 아직 완료하지 않았을 때만 응답에 들어온다. 결과는 서버가
// `resultsAvailable`을 줄 때만 받는다.
import { el } from "../../dom.js";
import { go, emptyState, loadingState, errorState, btn, dialogShell } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime, ddayAt } from "../../shared/home-remote.js";
import { surveyDetails, surveyPath, studentSurveyHref, surveyMessage, STATE } from "../../shared/surveys-remote.js";
import { surveyForm, resultsSection, surveyReceipt, periodText, phaseBadge, stateBadge, releaseText } from "../../shared/survey-view.js";

const LOGIN_TEXT = "ASTRA 서버에 로그인하면 이 과목의 설문에 응답할 수 있습니다.";
const missing = (error) => error instanceof ApiError && error.status === 404;

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function prose(text, empty) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? el("div", { class: "prose" }, el("p", { class: "tiny", text: empty })) : null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

function gate(root, kj, paintOff) {
  const status = backendStatus();
  if (status === "anon") return backendPanel({ text: LOGIN_TEXT });
  if (status === "off") {
    if (!backendSettled()) {
      queueMicrotask(() => connectBackend().then((next) => {
        if (root.isConnected && next.status === "off") paintOff();
      }));
      return loadingState("ASTRA 서버에 연결하는 중입니다");
    }
    return emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요.");
  }
  if (!courseIdFor(kj)) return emptyState("이 과목은 ASTRA 서버에 없습니다", "서버에 등록된 과목의 설문만 볼 수 있습니다.");
  return null;
}

/** `screen.serverSurvey`의 상세 화면. `parts`는 learning.js 도우미다. */
export function remoteSurveyDetail(screen, ctx, parts) {
  const kj = screen.course;
  const surveyId = screen.serverSurvey;
  const main = el("div", { class: "act-main" });
  const root = el("section", { class: "bleed act-layout is-solo is-open", "data-item-open": `srv-${surveyId}` }, main);
  const blocked = gate(root, kj, () => main.replaceChildren(emptyState("활동을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    main.append(el("div", { class: "act-body" }, parts.typeLine("survey"), blocked));
    return root;
  }
  const courseId = courseIdFor(kj);
  const path = surveyPath(courseId, surveyId);
  const load = () => surveyDetails.load(path, path);
  const listHref = `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj)}`;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main.replaceChildren(loadingState("설문을 불러오는 중입니다"));
      else if (missing(entry.error)) main.replaceChildren(emptyState("설문을 찾을 수 없습니다", "아직 공개되지 않았거나 볼 수 있는 권한이 없습니다.", go(listHref, "학습활동 목록", { class: "btn btn-secondary" })));
      else main.replaceChildren(errorState("설문을 불러오지 못했습니다", surveyMessage(entry.error), retry(load)));
      return;
    }
    main.replaceChildren(detailBody(data, courseId, kj, surveyId, parts, entry.error));
  };
  paint(surveyDetails.peek(path));
  queueMicrotask(() => {
    if (!root.isConnected) return;
    surveyDetails.watch(root, path, paint, load);
    if (freshVisit("survey-detail", path) === "entry" || !surveyDetails.peek(path)) load();
  });
  return root;
}

function detailBody(data, courseId, kj, surveyId, parts, staleError) {
  const info = data.survey || {};
  const dday = data.phase === "open" ? ddayAt(info.closeAt, data.serverTime) : "";
  const body = el("div", { class: "act-body" },
    parts.typeLine("survey"),
    el("h1", { text: info.title }),
    staleError ? el("p", { class: "err", role: "alert", text: surveyMessage(staleError) }) : null,
    parts.metaTable([
      ["응답 기간", el("span", { class: "due-line" }, el("span", { text: periodText(info) }), dday ? el("span", { class: "dday", text: dday }) : null)],
      ["응답 구간", phaseBadge(data.phase)],
      ["응답방식", info.anonymous ? "익명" : "실명"],
      ["문항", `${info.questionCount}문항`],
      ["결과 공개", releaseText(info)],
    ]),
    prose(info.description, "설문 안내가 없습니다."),
  );
  if (data.revision != null) {
    body.append(el("p", { class: "note", text: `담당 교수 화면입니다. 응답 ${data.respondentCount ?? 0}명 · 결과는 교수 화면에서 봅니다.` }));
    return body;
  }
  body.append(el("h2", { text: "제출정보" }));
  const receipt = surveyReceipt(courseId, surveyId);
  if (data.state === "completed") {
    body.append(el("section", { class: "note ok", role: receipt ? "status" : null },
      el("p", {}, stateBadge(data.state), receipt ? " 방금 응답을 제출했습니다." : " 이미 응답을 완료했습니다. 응답은 고칠 수 없습니다."),
    ));
  } else if (Array.isArray(data.questions)) {
    body.append(
      el("p", { class: "tiny", text: `${STATE[data.state] || data.state} · 마감 ${koDateTime(info.closeAt)}까지 응답할 수 있습니다.` }),
      el("div", { class: "btn-row" }, go(studentSurveyHref(kj, surveyId, { pop: "survey" }), "설문 참여", { class: "btn btn-primary" })),
    );
  } else {
    body.append(el("p", { class: "note", text: data.phase === "closed" ? "응답 기간이 끝났습니다." : "아직 응답 기간이 아닙니다." }));
  }
  if (data.resultsAvailable) body.append(resultsSection(courseId, surveyId));
  return body;
}

/** 응답 창(`?pop=survey`, 서버 설문). 완료하면 상세로 돌아간다. */
export function remoteSurveyDialog(ctx) {
  const screen = ctx.screen;
  const kj = screen.course;
  const surveyId = screen.serverSurvey;
  const frame = el("div", { class: "survey-form" });
  const shell = () => dialogShell("설문참여", frame, { wide: true });
  const blocked = gate(frame, kj, () => frame.replaceChildren(emptyState("설문을 찾을 수 없습니다", "ASTRA 서버에 연결되어 있지 않습니다.")));
  if (blocked) {
    frame.append(blocked);
    return shell();
  }
  const courseId = courseIdFor(kj);
  const path = surveyPath(courseId, surveyId);
  let shownFor = null;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (shownFor) return;
      if (!entry || !entry.error) frame.replaceChildren(loadingState("설문을 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(emptyState("설문을 찾을 수 없습니다", "아직 공개되지 않았거나 응답할 수 없는 설문입니다."));
      else frame.replaceChildren(errorState("설문을 불러오지 못했습니다", surveyMessage(entry.error)));
      return;
    }
    const info = data.survey || {};
    if (!Array.isArray(data.questions)) {
      shownFor = null;
      const done = data.state === "completed";
      frame.replaceChildren(
        done ? el("section", { class: "note ok", role: "status" }, el("p", { text: surveyReceipt(courseId, surveyId) ? "응답을 제출했습니다." : "이미 응답을 완료한 설문입니다." }))
          : el("p", { class: "note", text: data.phase === "closed" ? "응답 기간이 끝났습니다." : "지금은 응답할 수 없습니다." }),
        el("div", { class: "submit-actions" }, btn("닫기", { "data-act": "close" }, "primary")),
      );
      return;
    }
    // 같은 응답 폼은 다시 만들지 않는다(입력 중인 포커스를 지킨다). 문항이 바뀌었으면 새로 만든다.
    const signature = JSON.stringify(data.questions.map((q) => [q.questionId, (q.options || []).map((o) => o.optionId)]));
    if (shownFor === signature) return;
    shownFor = signature;
    frame.replaceChildren(
      el("p", { class: "tiny", text: `${info.title} · 마감 ${koDateTime(info.closeAt)}` }),
      surveyForm(courseId, surveyId, data),
    );
  };
  paint(surveyDetails.peek(path));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    surveyDetails.watch(frame, path, paint, null);
    if (freshVisit("survey-dialog", path) === "entry" || !surveyDetails.peek(path)) surveyDetails.load(path, path);
  });
  return shell();
}
