import { el } from "../dom.js";

export function mark(area, node) {
  if (node && node.setAttribute) node.setAttribute("data-area", area);
  return node;
}

export function btn(label, attrs = {}, kind = "secondary") {
  return el("button", { type: "button", class: `btn btn-${kind}`, ...attrs, text: label });
}

export function go(href, label, attrs = {}) {
  return el("a", { href, "data-go": href, ...attrs, text: label });
}

export function courseName(course, tag = "b", title) {
  return el("div", { class: "course-name" },
    el(tag, { class: "course-title", text: title || course.cardTitle || course.ko }),
    el("span", { class: "course-prof", text: course.instructor }),
  );
}

export function field(label, control, error) {
  return el("label", { class: "field" },
    el("span", { class: "field-label", text: label }),
    control,
    error ? el("span", { class: "err", text: error }) : null,
  );
}

export function emptyState(title, body, ...actions) {
  return el("div", { class: "empty", role: "status" },
    el("strong", { text: title }),
    body ? el("p", { text: body }) : null,
    actions.length ? el("div", { class: "btn-row" }, ...actions) : null,
  );
}

export function loadingState(title = "불러오는 중") {
  return el("div", { class: "empty state-loading", role: "status", "aria-busy": "true" },
    el("span", { class: "spinner", "aria-hidden": "true" }),
    el("strong", { text: title }),
  );
}

export function errorState(title, body, ...actions) {
  return el("div", { class: "empty state-error", role: "alert" },
    el("strong", { text: title }),
    body ? el("p", { text: body }) : null,
    actions.length ? el("div", { class: "btn-row" }, ...actions) : null,
  );
}

export function badge(text, tone = "neutral") {
  return el("span", { class: `badge badge-${tone}`, text });
}

export function tabs(items) {
  return el("div", { class: "tabs", role: "tablist" }, ...items.map((item) => {
    const selected = item.on ? "true" : "false";
    if (item.href) return go(item.href, item.label, { class: item.on ? "on" : "", role: "tab", "aria-selected": selected });
    return el("span", { class: item.on ? "on" : "", role: "tab", "aria-selected": selected, text: item.label });
  }));
}

export function listRow({ href, title, meta, detail, attrs = {} }) {
  const main = el("span", {}, el("b", { text: title }), detail ? el("div", { class: "tiny", text: detail }) : null);
  const side = meta ? el("span", { class: "tiny", text: meta }) : null;
  if (href) return el("a", { class: "list-row", href, "data-go": href, ...attrs }, main, side);
  return el("div", { class: "list-row", ...attrs }, main, side);
}

export function table(headers, rows, options = {}) {
  const layout = options.layout === "scroll" ? "scroll" : "cards";
  const head = el("tr", {}, ...headers.map((h) => el("th", { scope: "col", text: String(h) })));
  const body = rows.map((cells) => el("tr", {}, ...cells.map((cell, index) => el("td", { "data-label": String(headers[index] ?? "") }, cell))));
  return el("div", { class: `table-wrap table-${layout}` }, el("table", { class: "grid" }, el("thead", {}, head), el("tbody", {}, ...body)));
}

export function breadcrumbs(items) {
  const list = el("ol", {});
  items.forEach((item, index) => {
    const last = index === items.length - 1;
    const li = el("li", {});
    if (item.href && !last) li.append(go(item.href, item.label));
    else li.append(el("span", { "aria-current": last ? "page" : null, text: item.label }));
    list.append(li);
  });
  return el("nav", { class: "crumbs", "aria-label": "현재 위치" }, list);
}

export function pagination({ page, pages, hrefForPage }) {
  if (!pages || pages <= 1) return null;
  const list = el("ol", {});
  const window = [];
  for (let n = 1; n <= pages; n += 1) {
    if (n === 1 || n === pages || Math.abs(n - page) <= 1) window.push(n);
    else if (window[window.length - 1] !== "…") window.push("…");
  }
  if (page > 1) list.append(el("li", {}, go(hrefForPage(page - 1), "이전", { class: "pager-link" })));
  for (const n of window) {
    if (n === "…") list.append(el("li", {}, el("span", { class: "pager-gap", text: "…" })));
    else {
      list.append(el("li", {}, go(hrefForPage(n), String(n), {
        class: `pager-link${n === page ? " on" : ""}`,
        "aria-current": n === page ? "page" : null,
      })));
    }
  }
  if (page < pages) list.append(el("li", {}, go(hrefForPage(page + 1), "다음", { class: "pager-link" })));
  return el("nav", { class: "pager", "aria-label": "페이지" }, list);
}

export function dialogShell(title, body, options = {}) {
  const card = el("div", {
    class: ["modal", options.wide ? "wide" : "", options.className].filter(Boolean).join(" "),
    role: "dialog",
    "aria-modal": "true",
    "aria-label": options.label || title,
  },
  el("div", { class: "modal-h" },
    el("h2", { text: title }),
    el("button", { type: "button", class: "x", "data-act": "close", "aria-label": "닫기", text: "×" }),
  ),
  el("div", { class: "modal-b" }, body),
  );
  return el("div", { class: "modal-back", "data-act": "close" }, card);
}
