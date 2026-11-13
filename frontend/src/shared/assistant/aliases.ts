// Only synonyms that cannot be derived from a course's own name. Abbreviations such as "고웹프" or "데베"
// are matched generically by course-match.js, so new courses need no entry here.
export const courseAliases: Record<string, string[]> = {
  bi: ["디비", "DB"],
  ai: ["머신러닝"],
};

// Specific activity names take precedence over generic phrases such as '수업 설문'.
export const intentKeywords: Record<string, string[]> & { plan: string[] } = {
  plan: ["시작해", "시작하자", "시작", "오늘계획", "계획", "전부", "다해", "모두", "남은거다", "남은것다", "할일다", "다듣", "다보", "다처리", "다끝내", "순서대로", "차례로", "한꺼번에", "한번에", "할일끝내"],
  report: ["과제", "제출", "리포트"],
  test: ["시험", "퀴즈", "고사"],
  survey: ["설문"],
  material: ["자료", "pdf"],
  zoom: ["줌", "화상", "멘토링"],
  video: ["강의", "영상", "수업", "듣", "틀어", "재생", "이어"],
};

// Bulk execution needs an explicit request; an existing plan is not permission for a new request.
/** A previous turn of the conversation (only `text` and `result.kind` are read). */
export interface PlanHistoryTurn {
  text: string;
  result?: { kind?: string } | null;
}

export function allowsPlan(text: string, history: readonly PlanHistoryTurn[] = []): boolean {
  const compact = (value: string | null | undefined): string => String(value || "").toLowerCase().replace(/[\s.!?。]+/g, "");
  const explicit = (value: string): boolean => {
    const input = compact(value);
    return /^(?:오늘)?시작(?:해|하자)?$/.test(input)
      || intentKeywords.plan.some((word) => !word.startsWith("시작") && input.includes(word));
  };
  if (explicit(text)) return true;
  const acknowledgement = (value: string): boolean => /^(?:어|응|네|예|그래|좋아|좋아요|그렇게해|그대로해|그대로진행해|진행해|계속해)$/.test(compact(value));
  if (!acknowledgement(text)) return false;
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const turn = history[index];
    if (!["plan", "clarify"].includes(turn.result?.kind || "")) return false;
    if (explicit(turn.text)) return true;
    if (!acknowledgement(turn.text)) return false;
  }
  return false;
}
