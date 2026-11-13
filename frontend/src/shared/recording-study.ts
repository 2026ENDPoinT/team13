// Frontend contract for backend-produced recording results. No speech recognition or model calls here.
export interface TranscriptLine {
  /** Seconds from the start of the recording, in ascending order. */
  at: number;
  text: string;
}

export interface StudyPoint {
  term: string;
  text: string;
  at: number;
}

export interface StudySection {
  title: string;
  at: number;
  points: StudyPoint[];
  table?: { head: string[]; rows: string[][] };
}

export interface TranscriptionResult {
  segments: TranscriptLine[];
  /** Complete plain text produced by the backend, used verbatim for TXT downloads. */
  text: string;
}

export interface RecordingSummaryResult {
  overview: string;
  sections: StudySection[];
  note: string;
}

/** Processing and generation belong to the backend; audio scanning has its own state. */
export type RecordingJob<T> =
  | { status: "queued" | "processing" }
  | { status: "ready"; result: T }
  | { status: "failed"; message: string };

export interface RecordingStudyResults {
  /** Missing/null means the server has not provided this feature/result, not that it is processing. */
  transcription?: RecordingJob<TranscriptionResult> | null;
  summary?: RecordingJob<RecordingSummaryResult> | null;
}

export function recordingJobPending(job: RecordingStudyResults["transcription"] | RecordingStudyResults["summary"]): boolean {
  return job?.status === "queued" || job?.status === "processing";
}
