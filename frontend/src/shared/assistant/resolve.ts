import { buildPlan } from "./plan.ts";
import { compact, defaultCtx, enrolled, ordered, sayFor, todayLectures } from "./activity-context.ts";
import { courseAliases, intentKeywords, allowsPlan } from "./aliases.ts";
import { abbreviationMatch } from "./course-match.ts";
import { typeLabel } from "../format.ts";
import type { Course } from "../../data.ts";
import type { AssistantCtx, AssistantResult, MatchedTerms } from "./activity-context.ts";
export { progressOf, todayLectures, activityLink, sayFor, describe, nextActions } from "./activity-context.ts";

export function resolve(text: string, ctx: AssistantCtx = defaultCtx()): AssistantResult {
  const input = compact(text);
  const matched: MatchedTerms = { course: null, intent: null };
  const none = (): AssistantResult => ({ kind: "none", intent: intent || null, courseId: null, targets: [], say: sayFor({ kind: "none", targets: [] }, text, ctx), matched });
  let intent: string | null = null;
  for (const [type, keywords] of Object.entries(intentKeywords)) {
    if (type === "plan" && !allowsPlan(text)) continue;
    const keyword = keywords.find((word) => input.includes(word));
    if (keyword) { intent = type; matched.intent = { text: keyword, label: typeLabel(type) }; break; }
  }
  const week = /([0-9]+)주/.exec(input);
  if (!intent && week) { intent = "video"; matched.intent = { text: week[0], label: "영상" }; }
  // Remove command tails only after intent recognition ('틀어줘' itself signals video).
  let subject = input.replace(/(듣게해줘|틀어줘|보여줘|해줘|듣게|듣자|가자|해주세요|주세요|알려줘|찾아줘|이어듣기|재생해줘)/g, "");
  for (const keywords of Object.values(intentKeywords)) for (const word of keywords) subject = subject.replaceAll(word, "");
  subject = subject.replace(/오늘|들을|듣기|보기|\d+주(?:차)?|부터|까지/g, "").replace(/(?:을|를|은|는|으로|로|에)$/g, "");
  const scored = ctx.courses.filter(enrolled).map((course: Course) => {
    const aliases = (courseAliases[course.id] || []).map((word) => compact(word));
    const alias = aliases.filter((word) => input.includes(word)).sort((a, b) => b.length - a.length)[0];
    const ko = compact(course.ko);
    const score = alias ? 400 : subject.length >= 2 && ko.startsWith(subject) ? 300
      : abbreviationMatch(subject, course) ? 200
      : subject.length >= 2 && (compact(course.en).includes(subject) || compact(course.code).includes(subject)) ? 100 : 0;
    return { course, score, text: alias || subject };
  });
  const highest = Math.max(0, ...scored.map((row) => row.score));
  const matches = scored.filter((row) => highest > 0 && row.score === highest);
  const courseId = matches.length === 1 ? matches[0].course.id : null;
  if (courseId) matched.course = { text: matches[0].text, name: matches[0].course.ko };
  if (intent === "plan" && matches.length <= 1) {
    // "데베 전부", "오늘 강의 다 해줘": a course or activity type in the same sentence narrows the plan.
    const type = Object.entries(intentKeywords).find(([key, words]) => key !== "plan" && words.some((word) => input.includes(word)))?.[0];
    const targets = buildPlan(ctx).filter((id) => {
      const activity = ctx.activities.find((a) => a.id === id);
      return Boolean(activity) && (!type || activity?.type === type) && (!courseId || activity?.courseId === courseId);
    });
    return { kind: targets.length ? "plan" : "none", intent: "plan", courseId, targets, matched,
      say: targets.length ? `오늘 계획 ${targets.length}단계` : "오늘 계획에 넣을 일이 없습니다" };
  }
  if (!matches.length && subject) return none();
  if (!intent && matches.length === 1) {
    const result: AssistantResult = { kind: "go", intent: "course", courseId, targets: [], matched, say: "" };
    return { ...result, say: sayFor(result, text, ctx) };
  }
  if (!intent && matches.length > 1) intent = "course";
  if (!intent) return none();
  const courseIds = new Set(matches.map((row) => row.course.id));
  const activeIds = new Set(ctx.courses.filter(enrolled).map((course) => course.id));
  const videos = new Set(todayLectures(ctx));
  const targets = ordered(ctx.activities.filter((a) => activeIds.has(a.courseId)
    && (!matches.length || courseIds.has(a.courseId)) && (intent === "course" || a.type === intent)
    && (a.type === "video" ? videos.has(a.id) : ["missing", "ongoing", "scheduled", "open"].includes(a.status))
    && (!week || a.week === Number(week[1]))), ctx).map((a) => a.id);
  if (!targets.length) return none();
  const result: AssistantResult = { kind: targets.length === 1 ? "go" : "choose", intent, courseId, targets, matched, say: "" };
  return { ...result, say: sayFor(result, text, ctx) };
}
