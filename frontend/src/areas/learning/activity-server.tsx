// 학생 활동 상세의 백엔드 부분 중 영상·과제·시험·설문 상세 밖의 것. 서버 자료 상세
// (`?m=lecture_material&n=srv-<activityId>`, `GET …/activities/{id}`의 설명과 첨부, 내려받기, 미리보기, 학습실),
// 활동 ID만 있는 예전 연결 주소(`?m=report|test|survey&act=<activityId>`, 활동 상세가 주는 과제·시험·설문 ID로
// 그 상세로 주소를 바꾼다), 연결된 과목에서 샘플 활동 ID로 열린 상세·플레이어·미리보기의
// 찾을 수 없음 화면이다. 서버 글은 텍스트로만 넣는다.
import { Fragment, useEffect, useState, type ReactNode } from "react";
import { Button, EmptyState, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { Link } from "../../router.tsx";
import { hrefFor, find } from "../../manifest.ts";
import { ApiError } from "../../shared/api.ts";
import { backendStatus, backendSettled, connect as connectBackend, courseIdFor } from "../../shared/backend.ts";
import { backendPanel } from "../../shared/backend-prompt.tsx";
import { freshVisit, useResource } from "../../shared/remote-cache.ts";
import { koDateTime } from "../../shared/home-remote.ts";
import { lectureDetails, detailKey, ACTIVITY_PHASE, type ActivityDetail, type ActivityFile } from "../../shared/lectures-remote.ts";
import { materialMessage } from "../../shared/materials-remote.ts";
import { rememberMaterials } from "../../shared/course-pdfs-remote.ts";
import { downloadControl, sizeText } from "../../shared/uploads-remote.tsx";
import { resolveActivityHref } from "../../shared/course-activities-remote.ts";
import type { ScreenCtx, Screen } from "../../types.ts";
import type { ActivityParts } from "./learning.tsx";

const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
const listHref = (kj: string) => hrefFor(find("50_activity_all"), { kj });
const TYPE_NAME: Record<string, string> = { report: "과제", test: "시험", survey: "설문" };

/** "다시 불러오기": disabled once pressed (the error state is replaced while the load runs). */
export function RetryButton({ reload }: { reload: () => void }) {
  const [busy, setBusy] = useState(false);
  return (
    <Button kind="ghost" disabled={busy} onClick={() => {
      setBusy(true);
      reload();
    }}>다시 불러오기</Button>
  );
}

/**
 * 상세·연결 화면의 공통 관문: 대신 보일 내용, 또는 진행해도 되면 null. 연결을 아직 확인하지 못했으면 연결을 시도하고,
 * 끝내 서버가 꺼져 있으면 `offLate`가 참이 된다.
 */
function useGate(kj: string | undefined, loginText: string): { blocked: ReactNode; offLate: boolean } {
  const [offLate, setOffLate] = useState(false);
  const status = backendStatus();
  const waiting = status === "off" && !backendSettled();
  useEffect(() => {
    if (!waiting) return;
    let live = true;
    void connectBackend().then((next) => {
      if (live && next.status === "off") setOffLate(true);
    });
    return () => {
      live = false;
    };
  }, [waiting]);
  if (status === "anon") return { blocked: backendPanel({ text: loginText }), offLate };
  if (status === "off") {
    return {
      blocked: waiting
        ? <LoadingState title="ASTRA 서버에 연결하는 중입니다" />
        : <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다. 목록에서 다른 활동을 선택해 보세요." />,
      offLate,
    };
  }
  if (!courseIdFor(kj)) return { blocked: <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 활동만 볼 수 있습니다." />, offLate };
  return { blocked: null, offLate };
}

function Prose({ text }: { text: string | null | undefined }) {
  const blocks = String(text || "").split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return null;
  return (
    <div className="prose">
      {blocks.map((block, index) => (
        <p key={index}>{block.split("\n").map((line, at) => <Fragment key={at}>{at ? <br /> : null}{line}</Fragment>)}</p>
      ))}
    </div>
  );
}

// ---------- 자료 상세 ----------

function FileRow({ kj, courseId, file }: { kj: string; courseId: number | string; file: ActivityFile }) {
  const preview = hrefFor(find("65_file_preview"), { n: `srv-${file.fileId}`, kj });
  return (
    <article className="file-row srv-material-file" data-file={String(file.fileId)}>
      <div>
        <b>{file.name}</b>
        <p className="tiny">{[String(file.extension || "").toUpperCase(), sizeText(file.sizeBytes)].filter(Boolean).join(" · ")}</p>
      </div>
      <div className="btn-row">
        <Link href={preview} className="btn btn-secondary">미리보기</Link>
        {downloadControl(courseId, { fileId: file.fileId, name: file.name })}
        {/* 요약 보기는 학생에게 보이는 요약 게시본이 있을 때(`summaryPublished`)만 둔다. */}
        {file.pdf && file.summaryPublished ? <Link href={hrefFor(find("study_summary"), { kj, doc: `srv-${file.fileId}` })} className="btn btn-ghost">요약 보기</Link> : null}
        {file.pdf ? <Link href={hrefFor(find("study_home"), { kj, doc: `srv-${file.fileId}` })} className="btn btn-ghost">학습실에서 열기</Link> : null}
      </div>
    </article>
  );
}

function MaterialBody({ data, kj, courseId, parts, staleError }: { data: ActivityDetail; kj: string; courseId: number | string; parts: ActivityParts; staleError: unknown }) {
  const files = Array.isArray(data.files) ? data.files : [];
  return (
    <div className="act-body">
      {parts.typeLine("material")}
      <h1>{data.title}</h1>
      <p className="tiny">{[`${data.weekNo}주차`, data.published ? "" : "비공개", data.phase === "not_open" ? ACTIVITY_PHASE.not_open : ""].filter(Boolean).join(" · ")}</p>
      {staleError ? <p className="err" role="alert">{materialMessage(staleError)}</p> : null}
      {parts.metaTable([
        ["공개일", data.openAt ? koDateTime(data.openAt) : "공개 즉시"],
        ["첨부", files.length ? `${files.length}개` : "없음"],
      ])}
      <Prose text={data.description} />
      <h2>첨부파일</h2>
      {files.length ? files.map((file) => <FileRow key={file.fileId} kj={kj} courseId={courseId} file={file} />) : <p className="tiny">첨부 파일이 없습니다.</p>}
      <div className="btn-row">
        <Link href={hrefFor(find("46_room_files_list"), { kj })} className="btn btn-secondary">파일모음</Link>
        <Link href={listHref(kj)} className="btn btn-ghost">학습활동 목록</Link>
      </div>
    </div>
  );
}

function MaterialLoaded({ kj, courseId, activityId, parts }: { kj: string; courseId: number | string; activityId: number; parts: ActivityParts }) {
  const key = detailKey(courseId, activityId);
  const load = () => lectureDetails.load(key, courseId, activityId);
  const entry = useResource(lectureDetails, key, load);
  useEffect(() => {
    if (freshVisit("material-detail", key) === "entry" || !lectureDetails.peek(key)) void lectureDetails.load(key, courseId, activityId);
  });
  const data = entry && entry.data;
  // 미리보기·학습실이 같은 파일 레코드를 쓴다.
  useEffect(() => {
    if (data && data.type === "lecture_material") rememberMaterials([data], kj, courseId);
  }, [data, kj, courseId]);
  if (!data) {
    if (!entry || !entry.error) return <LoadingState title="자료를 불러오는 중입니다" />;
    if (missing(entry.error)) {
      return <EmptyState title="자료를 찾을 수 없습니다" body="아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />;
    }
    return <ErrorState title="자료를 불러오지 못했습니다" body={materialMessage(entry.error)} actions={<RetryButton reload={() => void load()} />} />;
  }
  if (data.type !== "lecture_material") {
    return <EmptyState title="자료 활동이 아닙니다" body="학습활동 목록에서 다시 선택해 보세요." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />;
  }
  return <MaterialBody data={data} kj={kj} courseId={courseId} parts={parts} staleError={entry.error} />;
}

function MaterialDetail({ screen, parts }: { screen: Screen; parts: ActivityParts }) {
  const kj = screen.course || "";
  const activityId = screen.serverMaterial ?? 0;
  const { blocked, offLate } = useGate(kj, "ASTRA 서버에 로그인하면 이 과목의 자료를 보고 내려받을 수 있습니다.");
  const courseId = courseIdFor(kj);
  let main: ReactNode;
  if (offLate) main = <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." />;
  else if (blocked || courseId == null) main = <div className="act-body">{parts.typeLine("material")}{blocked}</div>;
  else main = <MaterialLoaded kj={kj} courseId={courseId} activityId={activityId} parts={parts} />;
  return (
    <section className="bleed act-layout is-open" data-item-open={`srv-${activityId}`}>
      <div className="act-main">{main}</div>
    </section>
  );
}

/** `screen.serverMaterial` 자료 상세(백엔드 모드). `parts`는 learning.tsx 도우미다. */
export function remoteMaterialDetail(screen: Screen, ctx: ScreenCtx, parts: ActivityParts): ReactNode {
  return <MaterialDetail key={`${screen.course}:${screen.serverMaterial}`} screen={screen} parts={parts} />;
}

// ---------- 활동 ID 연결 ----------

type LinkView = { kind: "finding" } | { kind: "missing" } | { kind: "failed"; error: unknown };

function ActivityLink({ screen, parts }: { screen: Screen; parts: ActivityParts }) {
  const kj = screen.course || "";
  const type = screen.serverLink?.type ?? "";
  const activityId = screen.serverLink?.activityId ?? 0;
  const { blocked, offLate } = useGate(kj, `ASTRA 서버에 로그인하면 이 ${TYPE_NAME[type]}을 열 수 있습니다.`);
  const [view, setView] = useState<LinkView>({ kind: "finding" });
  const [attempt, setAttempt] = useState(0);
  const proceed = !blocked;
  useEffect(() => {
    if (!proceed) return;
    let live = true;
    setView({ kind: "finding" });
    resolveActivityHref(kj, type, activityId).then((href) => {
      if (!live) return;
      if (!href) {
        setView({ kind: "missing" });
        return;
      }
      history.replaceState({}, "", href);
      window.dispatchEvent(new PopStateEvent("popstate"));
    }, (error: unknown) => {
      if (live) setView({ kind: "failed", error });
    });
    return () => {
      live = false;
    };
  }, [proceed, kj, type, activityId, attempt]);
  let shown: ReactNode;
  if (offLate) shown = <EmptyState title="활동을 찾을 수 없습니다" body="ASTRA 서버에 연결되어 있지 않습니다." />;
  else if (blocked) shown = blocked;
  else if (view.kind === "finding") shown = <LoadingState title={`${TYPE_NAME[type]}을 찾는 중입니다`} />;
  else if (view.kind === "missing") {
    shown = <EmptyState title={`${TYPE_NAME[type]}을 찾을 수 없습니다`} body="아직 공개되지 않았거나 삭제되었거나 볼 수 있는 권한이 없습니다." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />;
  } else {
    shown = <ErrorState title={`${TYPE_NAME[type]}을 찾지 못했습니다`} body={materialMessage(view.error)} actions={<RetryButton reload={() => setAttempt((n) => n + 1)} />} />;
  }
  return (
    <section className="bleed act-layout is-open">
      <div className="act-main">
        <div className="act-body" aria-live="polite">{parts.typeLine(type)}{shown}</div>
      </div>
    </section>
  );
}

/**
 * `screen.serverLink` `{ type, activityId }`(활동 ID만 있는 예전 연결 주소). 과제·시험·설문 ID를 찾으면 주소를 그 상세로
 * 바꾸고(뒤로 가기가 이 화면에 머물지 않도록 history 항목을 바꾼다), 못 찾으면 찾을 수 없음이다.
 */
export function remoteActivityLink(screen: Screen, ctx: ScreenCtx, parts: ActivityParts): ReactNode {
  return <ActivityLink key={`${screen.course}:${screen.serverLink?.type}:${screen.serverLink?.activityId}`} screen={screen} parts={parts} />;
}

// ---------- 샘플 ID ----------

/**
 * 백엔드 모드에서 샘플 활동·파일 ID로 열린 화면이면 대신 보일 본문: `anon`이면 로그인 안내, `ready`이고 서버에 있는 과목이면
 * "찾을 수 없음", 서버에 없는 과목이면 공지·Q&A와 같은 "이 과목은 ASTRA 서버에 없습니다". 샘플 내용을 서버 것처럼 보이지
 * 않는다. mock 모드(`off`)이거나 과목이 없으면 null(샘플 화면을 그대로 그림).
 */
export function sampleInServerCourse(kj: string | null | undefined, what = "활동"): ReactNode {
  const status = backendStatus();
  if (!kj || status === "off") return null;
  const body = status === "anon"
    ? backendPanel({ text: "ASTRA 서버에 로그인하면 이 과목의 활동을 서버에서 불러옵니다." })
    : !courseIdFor(kj)
      ? <EmptyState title="이 과목은 ASTRA 서버에 없습니다" body="서버에 등록된 과목의 활동만 볼 수 있습니다." />
      : <EmptyState title={`${what}을 찾을 수 없습니다`} body="이 과목은 ASTRA 서버의 활동만 보입니다. 목록에서 다시 선택해 보세요." actions={<Link href={listHref(kj)} className="btn btn-secondary">학습활동 목록</Link>} />;
  return (
    <section className="bleed act-layout is-open" data-sample-blocked="">
      <div className="act-main"><div className="act-body">{body}</div></div>
    </section>
  );
}
