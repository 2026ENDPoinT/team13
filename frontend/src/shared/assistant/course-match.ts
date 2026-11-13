import type { Course } from "../../data.ts";

const compact = (value: string | null | undefined): string => String(value || "").toLowerCase().replace(/\s+/g, "");
const CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

/** True when every character of `text` appears in `name` in order. */
export function subsequence(text: string, name: string): boolean {
  let index = 0;
  for (const char of name) if (char === text[index]) index += 1;
  return index === text.length;
}

const choseong = (text: string): string => [...text].map((char) => {
  const code = char.charCodeAt(0) - 0xac00;
  return code >= 0 && code < 11172 ? CHOSEONG[Math.floor(code / 588)] : char;
}).join("");

/**
 * Abbreviation match derived only from the course's own names, so every course of every user works
 * without a registered alias: syllable skips ("고웹프" → 고급웹프로그래밍), initial consonants
 * ("ㄱㅇㅍ"), and English acronyms/prefixes ("awp", "mach learn").
 */
export function abbreviationMatch(query: string, course: Pick<Course, "ko" | "en">): boolean {
  const q = compact(query);
  if (q.length < 2) return false;
  const ko = compact(course.ko);
  if (/^[ㄱ-ㅎ]+$/.test(q)) return subsequence(q, choseong(ko));
  const hangul = (q.match(/[가-힣]/g) || []).join("");
  if (hangul.length >= 2) return subsequence(hangul, ko);
  if (/^[a-z]+$/.test(q)) {
    const words = String(course.en || "").toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    return words.map((word) => word[0]).join("").includes(q) || compact(course.en).includes(q);
  }
  return false;
}
