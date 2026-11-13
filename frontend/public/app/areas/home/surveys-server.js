// 알림마당 설문(백엔드 모드): 목록 `GET /api/surveys?page=&size=`(마감 오름차순)과 설문 창
// (`survey_list_form.acl?pop=survey&id=srv-<surveyId>`: 상세, 응답, 결과). 대상인 설문만 서버가 보낸다.
// 로그인 전에는 샘플 설문을 숨기고 로그인 안내만 둔다.
import { el } from "../../dom.js";
import { btn, go, emptyState, loadingState, errorState, dialogShell, pagination } from "../../ui/components.js";
import { ApiError } from "../../shared/api.js";
import { backendStatus } from "../../shared/backend.js";
import { backendPanel } from "../../shared/backend-prompt.js";
import { freshVisit } from "../../shared/remote-cache.js";
import { koDateTime, ddayAt } from "../../shared/home-remote.js";
import {
  communitySurveyPages, surveyDetails, surveyPath, surveyIdOf, surveyRef, surveyMessage, COMMUNITY_PAGE_SIZE, STATE,
} from "../../shared/surveys-remote.js";
import { surveyForm, resultsSection, surveyReceipt, periodText, phaseBadge, stateBadge, releaseText } from "../../shared/survey-view.js";

const LIST = "/ilos/main/community/survey_list_form.acl";
const LOGIN_TEXT = "ASTRA 서버에 로그인하면 대상인 학교 설문을 보고 응답할 수 있습니다.";
const missing = (error) => error instanceof ApiError && error.status === 404;

const listHref = (page) => (page > 1 ? `${LIST}?page=${page}` : LIST);
const surveyHref = (surveyId, page) => {
  const params = new URLSearchParams({ pop: "survey", id: surveyRef(surveyId) });
  if (page > 1) params.set("page", String(page));
  return `${LIST}?${params}`;
};

function retry(reload) {
  const button = btn("다시 불러오기", {}, "ghost");
  button.addEventListener("click", () => {
    button.disabled = true;
    reload();
  });
  return button;
}

function prose(text) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return el("div", { class: "prose" }, ...blocks.map((block) => {
    const p = el("p", {});
    block.split("\n").forEach((line, index) => {
      if (index) p.append(el("br", {}));
      p.append(line);
    });
    return p;
  }));
}

/** 알림마당 설문 목록(백엔드 모드). `off`이면 null이라 샘플 목록을 그린다. */
export function serverCommunitySurveys(ctx) {
  const status = backendStatus();
  if (status === "off") return null;
  const head = el("div", { class: "page-head" }, el("h1", { text: "설문" }));
  const body = el("section", { class: "board" }, head);
  if (status === "anon") {
    body.append(backendPanel({ text: LOGIN_TEXT }));
    return body;
  }
  const page = Math.max(1, Number(ctx.params && ctx.params.get("page")) || 1);
  const key = String(page);
  const load = () => communitySurveyPages.load(key, page);
  const count = el("p", { class: "board-count" });
  const list = el("div", { class: "board-list", "aria-live": "polite" });
  const pager = el("div", {});
  body.append(backendPanel({ compact: true }), count, list, pager);
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      count.textContent = "";
      pager.replaceChildren();
      if (!entry || !entry.error) list.replaceChildren(loadingState("설문을 불러오는 중입니다"));
      else list.replaceChildren(errorState("설문을 불러오지 못했습니다", surveyMessage(entry.error), retry(load)));
      return;
    }
    const items = Array.isArray(data.items) ? data.items : [];
    const pages = Math.max(1, Math.ceil((Number(data.total) || 0) / COMMUNITY_PAGE_SIZE));
    count.textContent = `전체 ${data.total ?? items.length}`;
    if (!items.length) {
      list.replaceChildren(emptyState(page > 1 ? "이 쪽에는 설문이 없습니다" : "참여할 수 있는 설문이 없습니다", page > 1 ? "첫 쪽으로 돌아가 보세요." : "대상인 설문이 열리면 이 목록에 나옵니다.", page > 1 ? go(listHref(1), "첫 쪽", { class: "btn btn-secondary" }) : null));
    } else {
      list.replaceChildren(
        entry.error ? el("p", { class: "err", role: "alert", text: surveyMessage(entry.error) }) : "",
        ...items.map((item) => {
          const href = surveyHref(item.surveyId, page);
          return el("a", { class: "board-card", href, "data-go": href, "data-survey": surveyRef(item.surveyId) },
            el("b", { text: item.title }),
            el("span", { class: "tiny", text: [STATE[item.state] || item.state, `마감 ${koDateTime(item.closeAt)}`, `${item.questionCount}문항`, item.anonymous ? "익명" : "실명"].join(" · ") }),
          );
        }),
      );
    }
    pager.replaceChildren(pages > 1 ? pagination({ page, pages, hrefForPage: listHref }) : "");
  };
  paint(communitySurveyPages.peek(key));
  queueMicrotask(() => {
    if (!body.isConnected) return;
    communitySurveyPages.watch(body, key, paint, load);
    if (freshVisit("community-surveys", key) === "entry" || !communitySurveyPages.peek(key)) load();
  });
  return body;
}

/** 알림마당 설문 창(`?pop=survey&id=srv-<id>`, 백엔드 모드). 서버 설문이 아니면 null. */
export function communitySurveyDialog(ctx) {
  const surveyId = surveyIdOf(ctx.params && ctx.params.get("id"));
  if (!surveyId || backendStatus() === "off") return null;
  const frame = el("div", { class: "survey-form" });
  const shell = () => dialogShell("설문", frame, { wide: true });
  if (backendStatus() === "anon") {
    frame.append(backendPanel({ text: LOGIN_TEXT }));
    return shell();
  }
  const path = surveyPath(null, surveyId);
  const load = () => surveyDetails.load(path, path);
  let formFor = null;
  let form = null;
  const paint = (entry) => {
    const data = entry && entry.data;
    if (!data) {
      if (form && formFor) return;
      if (!entry || !entry.error) frame.replaceChildren(loadingState("설문을 불러오는 중입니다"));
      else if (missing(entry.error)) frame.replaceChildren(emptyState("설문을 찾을 수 없습니다", "대상이 아니거나 아직 시작하지 않은 설문입니다."));
      else frame.replaceChildren(errorState("설문을 불러오지 못했습니다", surveyMessage(entry.error), retry(load)));
      return;
    }
    const info = data.survey || {};
    const answering = Array.isArray(data.questions);
    const signature = answering ? JSON.stringify(data.questions.map((q) => [q.questionId, (q.options || []).map((o) => o.optionId)])) : null;
    if (answering && signature === formFor) return;
    formFor = signature;
    form = answering ? surveyForm(null, surveyId, data) : null;
    const dday = data.phase === "open" ? ddayAt(info.closeAt, data.serverTime) : "";
    const receipt = surveyReceipt(null, surveyId);
    frame.replaceChildren(
      el("h2", { text: info.title }),
      el("dl", { class: "kv" },
        el("div", {}, el("dt", { text: "기간" }), el("dd", {}, periodText(info), dday ? el("span", { class: "dday", text: ` ${dday}` }) : null)),
        el("div", {}, el("dt", { text: "상태" }), el("dd", {}, phaseBadge(data.phase), " ", stateBadge(data.state))),
        el("div", {}, el("dt", { text: "응답방식" }), el("dd", { text: info.anonymous ? "익명" : "실명" })),
        el("div", {}, el("dt", { text: "결과 공개" }), el("dd", { text: releaseText(info) })),
      ),
      prose(info.description),
      data.state === "completed"
        ? el("section", { class: "note ok", role: receipt ? "status" : null }, el("p", { text: receipt ? "응답을 제출했습니다." : "이미 응답을 완료한 설문입니다. 응답은 고칠 수 없습니다." }))
        : answering ? form : el("p", { class: "note", text: data.phase === "closed" ? "응답 기간이 끝났습니다." : "지금은 응답할 수 없습니다." }),
      data.resultsAvailable ? resultsSection(null, surveyId) : null,
      answering ? null : el("div", { class: "submit-actions" }, btn("닫기", { "data-act": "close" }, "primary")),
    );
  };
  paint(surveyDetails.peek(path));
  queueMicrotask(() => {
    if (!frame.isConnected) return;
    surveyDetails.watch(frame, path, paint, load);
    if (freshVisit("community-survey", path) === "entry" || !surveyDetails.peek(path)) load();
  });
  return shell();
}
