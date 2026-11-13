// 알림마당 설문(백엔드 모드): 목록 `GET /api/surveys?page=&size=`(마감 오름차순)과 설문 창
// (`survey_list_form.acl?pop=survey&id=srv-<surveyId>`: 상세, 응답, 결과). 대상인 설문만 서버가 보낸다.
// 로그인 전에는 샘플 설문을 숨기고 로그인 안내만 둔다.
import { useEffect, type ReactNode } from "react";
import { Link, closeDialog } from "../../router.tsx";
import { Button, Dialog, EmptyState, LoadingState, ErrorState, Pagination } from "../../ui/kit.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { koDateTime, ddayAt } from "../../shared/home-remote.ts";
import {
  communitySurveyPages, surveyDetails, surveyPath, surveyIdOf, surveyRef, surveyMessage, COMMUNITY_PAGE_SIZE, STATE,
} from "../../shared/surveys-remote.ts";
import { surveyForm, resultsSection, surveyReceipt, periodText, phaseBadge, stateBadge, releaseText } from "../../shared/survey-view.tsx";
import type { ScreenCtx } from "../../types.ts";
import { RetryButton } from "./home-server.tsx";

const LIST = "/ilos/main/community/survey_list_form.acl";
const LOGIN_TEXT = "ASTRA 서버에 로그인하면 대상인 학교 설문을 보고 응답할 수 있습니다.";

const listHref = (page: number): string => (page > 1 ? `${LIST}?page=${page}` : LIST);

function surveyHref(surveyId: number, page: number): string {
  const params = new URLSearchParams({ pop: "survey", id: surveyRef(surveyId) });
  if (page > 1) params.set("page", String(page));
  return `${LIST}?${params}`;
}

function prose(text: unknown): ReactNode {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => [at ? <br key="br" /> : null, line])}</p>
      ))}
    </div>
  );
}

/** 알림마당 설문 목록(백엔드 모드). `off`이면 null이라 샘플 목록을 그린다. */
export function serverCommunitySurveys(ctx: ScreenCtx): ReactNode {
  const status = backendStatus();
  if (status === "off") return null;
  if (status === "anon") {
    return (
      <section className="board" data-area="home">
        <div className="page-head"><h1>설문</h1></div>
        {backendPanel({ text: LOGIN_TEXT })}
      </section>
    );
  }
  return <CommunitySurveys page={Math.max(1, Number(ctx.params && ctx.params.get("page")) || 1)} />;
}

function CommunitySurveys({ page }: { page: number }) {
  const key = String(page);
  const load = () => void communitySurveyPages.load(key, page);
  const entry = useResource(communitySurveyPages, key, load);
  useEffect(() => {
    if (freshVisit("community-surveys", key) === "entry" || !communitySurveyPages.peek(key)) load();
  });
  const data = entry && entry.data;
  let count = "";
  let list: ReactNode;
  let pager: ReactNode = null;
  if (!data) {
    list = !entry || !entry.error
      ? <LoadingState title="설문을 불러오는 중입니다" />
      : <ErrorState title="설문을 불러오지 못했습니다" body={surveyMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  } else {
    const items = Array.isArray(data.items) ? data.items : [];
    const pages = Math.max(1, Math.ceil((Number(data.total) || 0) / COMMUNITY_PAGE_SIZE));
    count = `전체 ${data.total ?? items.length}`;
    list = items.length
      ? (
        <>
          {entry.error ? <p className="err" role="alert">{surveyMessage(entry.error)}</p> : null}
          {items.map((item) => (
            <Link key={item.surveyId} className="board-card" href={surveyHref(item.surveyId, page)} data-survey={surveyRef(item.surveyId)}>
              <b>{item.title}</b>
              <span className="tiny">{[(item.state && STATE[item.state]) || item.state, `마감 ${koDateTime(item.closeAt)}`, `${item.questionCount}문항`, item.anonymous ? "익명" : "실명"].join(" · ")}</span>
            </Link>
          ))}
        </>
      )
      : (
        <EmptyState
          title={page > 1 ? "이 쪽에는 설문이 없습니다" : "참여할 수 있는 설문이 없습니다"}
          body={page > 1 ? "첫 쪽으로 돌아가 보세요." : "대상인 설문이 열리면 이 목록에 나옵니다."}
          actions={page > 1 ? <Link href={listHref(1)} className="btn btn-secondary">첫 쪽</Link> : null}
        />
      );
    pager = pages > 1 ? <Pagination page={page} pages={pages} hrefForPage={listHref} /> : null;
  }
  return (
    <section className="board" data-area="home">
      <div className="page-head"><h1>설문</h1></div>
      {backendPanel({ compact: true })}
      <p className="board-count">{count}</p>
      <div className="board-list" aria-live="polite">{list}</div>
      <div>{pager}</div>
    </section>
  );
}

/** 알림마당 설문 창(`?pop=survey&id=srv-<id>`, 백엔드 모드). 서버 설문이 아니면 null. */
export function communitySurveyDialog(ctx: ScreenCtx): ReactNode {
  const surveyId = surveyIdOf(ctx.params && ctx.params.get("id"));
  if (!surveyId || backendStatus() === "off") return null;
  return (
    <Dialog title="설문" wide>
      <div className="survey-form">
        {backendStatus() === "anon" ? backendPanel({ text: LOGIN_TEXT }) : <CommunitySurvey surveyId={surveyId} />}
      </div>
    </Dialog>
  );
}

function CommunitySurvey({ surveyId }: { surveyId: number }) {
  const path = surveyPath(null, surveyId);
  const load = () => void surveyDetails.load(path, path);
  const entry = useResource(surveyDetails, path, load);
  useEffect(() => {
    if (freshVisit("community-survey", path) === "entry" || !surveyDetails.peek(path)) load();
  });
  const data = entry && entry.data;
  if (!data) {
    if (!entry || !entry.error) return <LoadingState title="설문을 불러오는 중입니다" />;
    if (entry.error instanceof ApiError && entry.error.status === 404) return <EmptyState title="설문을 찾을 수 없습니다" body="대상이 아니거나 아직 시작하지 않은 설문입니다." />;
    return <ErrorState title="설문을 불러오지 못했습니다" body={surveyMessage(entry.error)} actions={<RetryButton reload={load} />} />;
  }
  const info = data.survey;
  const answering = Array.isArray(data.questions);
  const dday = data.phase === "open" ? ddayAt(info.closeAt, data.serverTime) : "";
  const receipt = surveyReceipt(null, surveyId);
  return (
    <>
      <h2>{info.title}</h2>
      <dl className="kv">
        <div><dt>기간</dt><dd>{periodText(info)}{dday ? <span className="dday">{` ${dday}`}</span> : null}</dd></div>
        <div><dt>상태</dt><dd>{phaseBadge(data.phase ?? "")}{" "}{stateBadge(data.state ?? "")}</dd></div>
        <div><dt>응답방식</dt><dd>{info.anonymous ? "익명" : "실명"}</dd></div>
        <div><dt>결과 공개</dt><dd>{releaseText(info)}</dd></div>
      </dl>
      {prose(info.description)}
      {data.state === "completed"
        ? <section className="note ok" role={receipt ? "status" : undefined}><p>{receipt ? "응답을 제출했습니다." : "이미 응답을 완료한 설문입니다. 응답은 고칠 수 없습니다."}</p></section>
        : answering ? surveyForm(null, surveyId, data) : <p className="note">{data.phase === "closed" ? "응답 기간이 끝났습니다." : "지금은 응답할 수 없습니다."}</p>}
      {data.resultsAvailable ? resultsSection(null, surveyId) : null}
      {answering ? null : <div className="submit-actions"><Button kind="primary" onClick={() => closeDialog()}>닫기</Button></div>}
    </>
  );
}
