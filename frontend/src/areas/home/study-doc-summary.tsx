// The page summary of one of the student's own study-room PDFs (`/api/me/study-room/documents/{id}/summary`):
// "요약 만들기" (the content goes to the AI provider only then), queued and processing with bounded polling, failed
// with the server's sentence and a retry, and the ready summary in the same LessonViewer as course summaries
// (`summaryLessonDoc`). Shown only by a backend whose document view carries `summaryStatus`.
import { useEffect, useRef, useState } from "react";
import { Button, ErrorState, LoadingState } from "../../ui/kit.tsx";
import { LessonViewer } from "../../shared/lesson-viewer.tsx";
import { wallFormat } from "../../shared/home-remote.ts";
import { refreshCourseView } from "../../shared/study-room-remote.ts";
import {
  startDocSummary, getDocSummary, docSummaryMessage, summaryOffered, summaryPollDelay, SUMMARY_CHECKS,
  type DocSummaryAnswer, type StudyDocRecord,
} from "../../shared/study-documents.ts";
import { summaryLessonDoc } from "../../shared/summary-lesson.ts";

const madeFormat = wallFormat("ko-KR", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });

/** One line said wherever a summary can be started. */
export const SUMMARY_SENDS = "요약을 만들면 이 PDF의 내용을 AI 제공자에게 보냅니다.";

type State =
  | { kind: "idle" }
  | { kind: "busy"; text: string }
  | { kind: "waiting"; stopped: boolean }
  | { kind: "failed"; message: string }
  | { kind: "error"; message: string; retry: "start" | "check" }
  | { kind: "ready"; answer: DocSummaryAnswer };

const initial = (record: StudyDocRecord): State => {
  switch (record.summaryStatus) {
    case "queued":
    case "processing": return { kind: "waiting", stopped: false };
    case "ready":
    case "failed": return { kind: "busy", text: "요약을 불러오는 중입니다" };
    default: return { kind: "idle" };
  }
};

export function DocSummarySection({ record, pageCount }: { record: StudyDocRecord; pageCount: number | null }) {
  const [state, setState] = useState<State>(() => initial(record));
  const live = useRef(true);
  const timer = useRef(0);
  const checks = useRef(0);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
      window.clearTimeout(timer.current);
    };
  }, []);

  const settle = (answer: DocSummaryAnswer | null) => {
    if (!live.current) return;
    if (!answer) setState({ kind: "idle" });
    else if (answer.status === "ready" && answer.summary) setState({ kind: "ready", answer });
    else if (answer.status === "failed") setState({ kind: "failed", message: answer.message || "요약을 만들지 못했습니다. 다시 시도해 주세요." });
    else {
      wait();
      return;
    }
    // The cards of the study room show the summary state from the course view.
    void refreshCourseView(record.backendCourseId);
  };
  const check = () => {
    getDocSummary(record.documentId).then(settle, (error: unknown) => {
      if (live.current) setState({ kind: "error", message: docSummaryMessage(error), retry: "check" });
    });
  };
  const wait = () => {
    window.clearTimeout(timer.current);
    if (checks.current >= SUMMARY_CHECKS) {
      setState({ kind: "waiting", stopped: true });
      return;
    }
    setState({ kind: "waiting", stopped: false });
    timer.current = window.setTimeout(() => {
      checks.current += 1;
      check();
    }, summaryPollDelay() * 1000);
  };
  const start = () => {
    checks.current = 0;
    setState({ kind: "busy", text: "요약을 요청하는 중입니다" });
    startDocSummary(record.documentId).then(settle, (error: unknown) => {
      if (live.current) setState({ kind: "error", message: docSummaryMessage(error), retry: "start" });
    });
  };

  useEffect(() => {
    const first = initial(record);
    if (first.kind === "waiting") wait();
    else if (first.kind === "busy") check();
    // Read once for this document; later changes come from this section's own requests.
  }, [record.documentId]);

  if (!summaryOffered(record)) return null;
  let body;
  switch (state.kind) {
    case "idle":
      body = (
        <div className="course-study__summary-start">
          <p className="course-study__note">{SUMMARY_SENDS}</p>
          <Button kind="primary" onClick={start}>요약 만들기</Button>
        </div>
      );
      break;
    case "busy":
      body = <LoadingState title={state.text} />;
      break;
    case "waiting":
      body = state.stopped ? (
        <ErrorState
          title="요약이 아직 끝나지 않았습니다" body="요약을 만드는 데 오래 걸리고 있습니다. 잠시 후 다시 확인해 주세요."
          actions={<Button kind="ghost" onClick={() => { checks.current = 0; setState({ kind: "busy", text: "요약을 확인하는 중입니다" }); check(); }}>다시 확인</Button>}
        />
      ) : <LoadingState title="요약을 만드는 중입니다. 끝나면 여기에 나타납니다." />;
      break;
    case "failed":
      body = (
        <ErrorState
          title="요약을 만들지 못했습니다" body={state.message}
          actions={<><Button kind="secondary" onClick={start}>다시 시도</Button><span className="course-study__note">{SUMMARY_SENDS}</span></>}
        />
      );
      break;
    case "error":
      body = (
        <ErrorState
          title={state.retry === "start" ? "요약을 요청하지 못했습니다" : "요약을 불러오지 못했습니다"} body={state.message}
          actions={<Button kind="ghost" onClick={state.retry === "start" ? start : () => { setState({ kind: "busy", text: "요약을 불러오는 중입니다" }); check(); }}>다시 시도</Button>}
        />
      );
      break;
    case "ready": {
      const summary = state.answer.summary!;
      const at = madeFormat.format(summary.publishedAt);
      body = (
        <LessonViewer doc={summaryLessonDoc({
          id: record.id, title: record.title, pageCount, summary, pageImageBase: summary.pageImageBase,
          note: `${at ? `${at} 만듦 · ` : ""}내 PDF로 만든 AI 요약입니다. 원문과 함께 확인해 주세요.`,
          // The page-image cookies end: a GET of a ready summary sets fresh ones and answers the base.
          refreshImages: async () => {
            const fresh = await getDocSummary(record.documentId);
            return fresh && fresh.summary ? fresh.summary.pageImageBase ?? null : null;
          },
        })} />
      );
      break;
    }
  }
  return (
    <section className="course-study__summary" aria-label={`${record.title} AI 요약`} aria-busy={state.kind === "busy" ? "true" : undefined}>
      <h2>AI 요약</h2>
      <div className="course-study__summary-body" aria-live="polite">{body}</div>
    </section>
  );
}
