import { el } from "../dom.js";

export function sanitizeRichSource(source) {
  return String(source || "")
    .replace(/<\s*script\b[^>]*>[\s\S]*?<\s*\/\s*script\s*>/gi, "")
    .replace(/<\s*script\b[^>]*\/?\s*>/gi, "")
    .replace(/<\s*\/?\s*(iframe|object|embed|link|meta|style|svg)\b[^>]*>/gi, "")
    .replace(/\son\w+\s*=\s*(["']).*?\1/gi, "")
    .replace(/\son\w+\s*=\s*[^\s>]+/gi, "")
    .replace(/javascript\s*:/gi, "");
}

export function richPlain(source) {
  return sanitizeRichSource(source).replace(/\s+/g, " ").trim();
}

export function plainText(value) {
  return String(value || "").replace(/<[^>]*>/g, "").trim();
}

export function safeHref(url) {
  const value = String(url || "").trim();
  if (value.startsWith("/") && !value.startsWith("//") && !value.startsWith("/\\")) return value;
  try {
    const parsed = new URL(value);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") return parsed.href;
  } catch { /* typed text is not a link */ }
  return "";
}

function inlineNodes(line) {
  const nodes = [];
  const text = String(line || "");
  const re = /\*\*([^*\n]+)\*\*|\[([^\]\n]+)\]\(([^)\s]+)\)/g;
  let last = 0;
  let match;
  while ((match = re.exec(text))) {
    if (match.index > last) nodes.push(document.createTextNode(text.slice(last, match.index)));
    if (match[1] != null) nodes.push(el("strong", { text: match[1] }));
    else {
      const href = safeHref(match[3]);
      if (href) nodes.push(el("a", { href, rel: "noopener noreferrer", text: match[2] }));
      else nodes.push(document.createTextNode(match[2]));
    }
    last = re.lastIndex;
  }
  if (last < text.length) nodes.push(document.createTextNode(text.slice(last)));
  if (!nodes.length) nodes.push(document.createTextNode(""));
  return nodes;
}

export function richNodes(source) {
  const clean = sanitizeRichSource(source).replace(/\r\n/g, "\n");
  const frag = document.createDocumentFragment();
  const blocks = clean.split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  if (!blocks.length) {
    frag.append(el("p", { text: "" }));
    return frag;
  }
  for (const block of blocks) {
    const lines = block.split("\n");
    const list = lines.every((line) => /^\s*[-*]\s+/.test(line));
    if (list) {
      frag.append(el("ul", {}, ...lines.map((line) => el("li", {}, ...inlineNodes(line.replace(/^\s*[-*]\s+/, ""))))));
      continue;
    }
    const paragraph = el("p");
    lines.forEach((line, index) => {
      if (index) paragraph.append(el("br"));
      paragraph.append(...inlineNodes(line));
    });
    frag.append(paragraph);
  }
  return frag;
}
