// 학생의 서버 강의실 설문 상세(`?m=survey&n=srv-<surveyId>`)와 응답 창(`&pop=survey`). 응답 구간과 완료
// 여부는 서버가 정하고, 문항은 응답 기간 안이고 아직 완료하지 않았을 때만 응답에 들어온다. 결과는 서버가
// `resultsAvailable`을 줄 때만 받는다.
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { Button, Dialog, EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { Link, closeDialog, navigate } from "../../router.tsx";
import { completeStep, completionToken, screenKey } from "../../shared/assistant/plan.ts";
import { refreshTray } from "../../shared/assistant-tray.tsx";
import { ActivityShell } from "../../shared/activity-rail.tsx";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.ts";
import { useResource } from "../../shared/remote-cache.ts";
import { koDateTime, ddayAt } from "../../shared/home-remote.ts";
import { surveyDetails, surveyPath, studentSurveyHref, surveyMessage, STATE } from "../../shared/surveys-remote.ts";
import type { SurveyDetail } from "../../shared/surveys-remote.ts";
import { surveyForm, resultsSection, surveyReceipt, periodText, phaseBadge, stateBadge, releaseText } from "../../shared/survey-view.tsx";
import type { Screen, ScreenCtx } from "../../types.ts";

const missing = (error: unknown): boolean => error instanceof ApiError && error.status === 404;

/** learning.tsx 도우미. */
interface DetailParts {
  typeLine(type: string): ReactNode;
  metaTable(rows: [string, ReactNode][]): ReactNode;
}

function RetryButton({ reload }: { reload: () => void }) {
  const [busy, setBusy] = useState(false);
  return <Button kind="ghost" disabled={busy} onClick={() => { setBusy(true); reload(); }}>다시 불러오기</Button>;
}

function Prose({ text, empty }: { text: string | null | undefined; empty?: string }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return empty ? <div className="prose"><p className="tiny">{empty}</p></div> : null;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <Fragment key={at}>{at ? <br /> : null}{line}</Fragment>)}</p>
      ))}
    </div>
  );
}

type Gate = { view: ReactNode; off: boolean } | null;

/**
 * What to show instead of server data, or null when ready. A first backend probe this screen waited
 * for that stays off turns `off` on (the caller shows its own not-connected state).
 */
function useGate(kj: string | undefined): Gate {
  const status = backendStatus();
  const probing = status === "off" && !backendSettled();
  const [off, setOff] = useState(false);
  useEffect(() => {
    if (!probing) return;
    let live = true;
    void connectBackend().then((next) => {
      if (live && next.status === "off") setOff(true);
    });
    return () => {
      live = false;
    };
  }, [probing]);
  if (status === "anon") return { view: null, off: false };
  if (status === "off") {
    if (off) return { view: null, off: true };
    if (probing) return { view: <LoadingState title="ASTRA 서버에 연결하는 중입니다" />, off: false };
    return { view: <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요." />, off: false };
  }
  if (!courseIdFor(kj)) return { view: <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 설문만 볼 수 있습니다." />, off: false };
  return null;
}

/** `screen.serverSurvey`의 상세 화면. `parts`는 learning.tsx 도우미다. */
export function remoteSurveyDetail(screen: Screen, ctx: ScreenCtx, parts: DetailParts): ReactNode {
  return <SurveyDetailScreen screen={screen} parts={parts} />;
}

function SurveyDetailScreen({ screen, parts }: { screen: Screen; parts: DetailParts }) {
  const kj = screen.course;
  const surveyId = Number(screen.serverSurvey);
  const gate = useGate(kj);
  const courseId = gate ? null : courseIdFor(kj);
  const path = courseId == null ? null : surveyPath(courseId, surveyId);
  const load = () => {
    if (path) void surveyDetails.load(path, path);
  };
  const entry = useResource(surveyDetails, path, path ? load : undefined);
  useEffect(() => {
    if (path) void surveyDetails.visit("survey-detail", path, path);
  });
  const listHref = `/ilos/cls/st/activity/activity_list_form.acl?kj=${encodeURIComponent(kj || "")}`;
  let main: ReactNode;
  if (gate) {
    main = gate.off
      ? <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." />
      : <div className="act-body">{parts.typeLine("survey")}{gate.view}</div>;
  } else {
    const data = entry && entry.data;
    if (!data) {
      if (!entry || !entry.error) main = <LoadingState title="설문을 불러오는 중입니다" />;
      else if (missing(entry.error)) main = <EmptyState title="설문을 찾을 수 없습니다" body="아직 공개되지 않았거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref} className="btn btn-secondary">학습활동 목록</Link>} />;
      else main = <ErrorState title="설문을 불러오지 못했습니다" body={surveyMessage(entry.error)} actions={<RetryButton reload={load} />} />;
    } else {
      main = <DetailBody data={data} courseId={courseId} kj={kj || ""} surveyId={surveyId} parts={parts} staleError={entry ? entry.error : null} />;
    }
  }
  return (
    <ActivityShell kj={kj || ""} itemOpen={`srv-${surveyId}`}>{main}</ActivityShell>
  );
}

interface DetailBodyProps {
  data: SurveyDetail;
  courseId: number | string | null;
  kj: string;
  surveyId: number;
  parts: DetailParts;
  staleError: unknown;
}

function DetailBody({ data, courseId, kj, surveyId, parts, staleError }: DetailBodyProps) {
  const info = data.survey || {};
  const dday = data.phase === "open" ? ddayAt(info.closeAt, data.serverTime) : "";
  const head = (
    <>
      {parts.typeLine("survey")}
      <h1>{info.title}</h1>
      {staleError ? <p className="err" role="alert">{surveyMessage(staleError)}</p> : null}
      {parts.metaTable([
        ["응답 기간", <span className="due-line"><span>{periodText(info)}</span>{dday ? <span className="dday">{dday}</span> : null}</span>],
        ["응답 구간", phaseBadge(data.phase || "")],
        ["응답방식", info.anonymous ? "익명" : "실명"],
        ["문항", `${info.questionCount}문항`],
        ["결과 공개", releaseText(info)],
      ])}
      <Prose text={info.description} empty="설문 안내가 없습니다." />
    </>
  );
  if (data.revision != null) {
    return (
      <div className="act-body">
        {head}
        <p className="note">{`담당 교수 화면입니다. 응답 ${data.respondentCount ?? 0}명 · 결과는 교수 화면에서 봅니다.`}</p>
      </div>
    );
  }
  const receipt = surveyReceipt(courseId, surveyId);
  let state: ReactNode;
  if (data.state === "completed") {
    state = (
      <section className="note ok" role={receipt ? "status" : undefined}>
        <p>{stateBadge(data.state)}{receipt ? " 방금 응답을 제출했습니다." : " 이미 응답을 완료했습니다. 응답은 고칠 수 없습니다."}</p>
      </section>
    );
  } else if (Array.isArray(data.questions)) {
    state = (
      <>
        <p className="tiny">{`${STATE[data.state || ""] || data.state} · 마감 ${koDateTime(info.closeAt)}까지 응답할 수 있습니다.`}</p>
        <div className="btn-row"><Link href={studentSurveyHref(kj, surveyId, { pop: "survey" })} className="btn btn-primary">설문 참여</Link></div>
      </>
    );
  } else {
    state = <p className="note">{data.phase === "closed" ? "응답 기간이 끝났습니다." : "아직 응답 기간이 아닙니다."}</p>;
  }
  return (
    <div className="act-body">
      {head}
      <h2>제출정보</h2>
      {state}
      {data.resultsAvailable ? resultsSection(courseId, surveyId) : null}
    </div>
  );
}

/** 응답 창(`?pop=survey`, 서버 설문). 완료하면 상세로 돌아간다. */
export function remoteSurveyDialog(ctx: ScreenCtx): ReactNode {
  return <SurveyDialog screen={ctx.screen} />;
}

function SurveyDialog({ screen }: { screen: Screen }) {
  const kj = screen.course;
  const surveyId = Number(screen.serverSurvey);
  const gate = useGate(kj);
  const courseId = gate ? null : courseIdFor(kj);
  const path = courseId == null ? null : surveyPath(courseId, surveyId);
  const entry = useResource(surveyDetails, path);
  // 응답 폼을 보여 준 데이터. 데이터가 잠시 사라져도(다시 읽는 중) 입력 중인 폼을 그대로 둔다.
  const shown = useRef<SurveyDetail | null>(null);
  // 계획의 이 설문 단계: 창을 연 때의 선택에 묶고, 응답이 접수되면 그 단계를 끝낸다.
  const planToken = useRef(completionToken(screenKey(screen.id, new URLSearchParams(location.search))));
  const onDone = () => { if (completeStep(planToken.current, { navigate, event: "survey-saved" })) refreshTray(); };
  useEffect(() => {
    if (path) void surveyDetails.visit("survey-dialog", path, path);
  });
  let frame: ReactNode;
  if (gate) {
    frame = gate.off ? <EmptyState title="설문을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." /> : gate.view;
  } else {
    const data = (entry && entry.data) || shown.current;
    if (!data) {
      if (!entry || !entry.error) frame = <LoadingState title="설문을 불러오는 중입니다" />;
      else if (missing(entry.error)) frame = <EmptyState title="설문을 찾을 수 없습니다" body="아직 공개되지 않았거나 응답할 수 없는 설문입니다." />;
      else frame = <ErrorState title="설문을 불러오지 못했습니다" body={surveyMessage(entry.error)} />;
    } else if (!Array.isArray(data.questions)) {
      shown.current = null;
      frame = (
        <>
          {data.state === "completed"
            ? <section className="note ok" role="status"><p>{surveyReceipt(courseId, surveyId) ? "응답을 제출했습니다." : "이미 응답을 완료한 설문입니다."}</p></section>
            : <p className="note">{data.phase === "closed" ? "응답 기간이 끝났습니다." : "지금은 응답할 수 없습니다."}</p>}
          <div className="submit-actions"><Button kind="primary" onClick={() => closeDialog()}>닫기</Button></div>
        </>
      );
    } else {
      shown.current = data;
      const info = data.survey || {};
      // 같은 응답 폼은 다시 만들지 않는다(입력 중인 포커스를 지킨다). 문항이 바뀌었으면 새로 만든다.
      frame = (
        <Fragment key={JSON.stringify(data.questions.map((q) => [q.questionId, (q.options || []).map((o) => o.optionId)]))}>
          <p className="tiny">{`${info.title} · 마감 ${koDateTime(info.closeAt)}`}</p>
          {surveyForm(courseId, surveyId, data, { onDone })}
        </Fragment>
      );
    }
  }
  return (
    <Dialog title="설문참여" wide>
      <div className="survey-form">{frame}</div>
    </Dialog>
  );
}
