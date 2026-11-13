import type { ReactNode } from "react";

export function sanitizeRichSource(source: unknown): string {
  return String(source || "")
    .replace(/<\s*script\b[^>]*>[\s\S]*?<\s*\/\s*script\s*>/gi, "")
    .replace(/<\s*script\b[^>]*\/?\s*>/gi, "")
    .replace(/<\s*\/?\s*(iframe|object|embed|link|meta|style|svg)\b[^>]*>/gi, "")
    .replace(/\son\w+\s*=\s*(["']).*?\1/gi, "")
    .replace(/\son\w+\s*=\s*[^\s>]+/gi, "")
    .replace(/javascript\s*:/gi, "");
}

export function richPlain(source: unknown): string {
  return sanitizeRichSource(source).replace(/\s+/g, " ").trim();
}

export function plainText(value: unknown): string {
  return String(value || "").replace(/<[^>]*>/g, "").trim();
}

export function safeHref(url: unknown): string {
  const value = String(url || "").trim();
  if (value.startsWith("/") && !value.startsWith("//") && !value.startsWith("/\\")) return value;
  try {
    const parsed = new URL(value);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") return parsed.href;
  } catch { /* typed text is not a link */ }
  return "";
}

/** `**bold**` and `[text](url)` (safe URLs only) in one line; everything else stays text. */
function inlineNodes(line: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const text = String(line || "");
  const re = /\*\*([^*\n]+)\*\*|\[([^\]\n]+)\]\(([^)\s]+)\)/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = re.exec(text))) {
    if (match.index > last) nodes.push(text.slice(last, match.index));
    if (match[1] != null) nodes.push(<strong key={match.index}>{match[1]}</strong>);
    else {
      const href = safeHref(match[3]);
      if (href) nodes.push(<a key={match.index} href={href} rel="noopener noreferrer">{match[2]}</a>);
      else nodes.push(match[2]);
    }
    last = re.lastIndex;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

/** Sanitized rich text as paragraphs (`<p>` with `<br>` per line) and `-`/`*` bullet lists. */
export function richNodes(source: unknown): ReactNode {
  const clean = sanitizeRichSource(source).replace(/\r\n/g, "\n");
  const blocks = clean.split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) return <p />;
  return blocks.map((block, blockIndex) => {
    const lines = block.split("\n");
    if (lines.every((line) => /^\s*[-*]\s+/.test(line))) {
      return (
        <ul key={blockIndex}>
          {lines.map((line, index) => <li key={index}>{inlineNodes(line.replace(/^\s*[-*]\s+/, ""))}</li>)}
        </ul>
      );
    }
    return (
      <p key={blockIndex}>
        {lines.map((line, index) => [index ? <br key={`br-${index}`} /> : null, ...inlineNodes(line)])}
      </p>
    );
  });
}
