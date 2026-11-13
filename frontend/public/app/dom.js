export function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === "text") node.textContent = String(v);
    else if (k === "class") node.className = String(v);
    else node.setAttribute(k, v === true ? "" : String(v));
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    node.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return node;
}

const FILE_ICONS = {
  home: ["/app/assets/svg/icon-home-22.svg", 22],
  book: ["/app/assets/svg/icon-book-22.svg", 22],
  course: ["/app/assets/svg/icon-course-22.svg", 22],
  layers: ["/app/assets/svg/icon-course-22.svg", 22],
  bell: ["/app/assets/svg/icon-bell-24.svg", 24],
  mail: ["/app/assets/svg/icon-mail-24.svg", 24],
  calendar: ["/app/assets/svg/icon-calendar-24.svg", 24],
  chev: ["/app/assets/svg/icon-chevron-right-16.svg", 16],
  chevL: ["/app/assets/svg/icon-chevron-left-16.svg", 16],
  caret: ["/app/assets/svg/icon-caret-down-12.svg", 12],
  sliders: ["/app/assets/svg/icon-sliders-22.svg", 22],
  plus: ["/app/assets/svg/icon-plus-box-16.svg", 16],
  file: ["/app/assets/svg/icon-book-22.svg", 22],
  info: ["/app/assets/svg/icon-book-22.svg", 22],
};

export function icon(name) {
  const file = FILE_ICONS[name];
  if (file) {
    const wrap = document.createElement("span");
    wrap.className = `ico mask ico-${file[1]}`;
    wrap.style.setProperty("--ico", `url("${file[0]}")`);
    wrap.dataset.asset = `icon.${name}`;
    return wrap;
  }
  const paths = {
    home: '<path d="M4 11.5 12 4l8 7.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1v-8.5Z"/>',
    book: '<path d="M5 5.5A2.5 2.5 0 0 1 7.5 3H20v16H7.5A2.5 2.5 0 0 0 5 21.5V5.5Z"/><path d="M5 19.2A2.5 2.5 0 0 1 7.5 17H20"/>',
    layers: '<path d="m12 3 9 5-9 5L3 8l9-5Z"/><path d="m3 12 9 5 9-5"/><path d="m3 16 9 5 9-5"/>',
    info: '<path d="M5 6.5A2.5 2.5 0 0 1 7.5 4h9L20 7.5V19a1 1 0 0 1-1 1H7.5A2.5 2.5 0 0 1 5 17.5v-11Z"/><path d="M8 11h8M8 15h5"/>',
    chat: '<path d="M6 16.5 3.5 20V6.5A2.5 2.5 0 0 1 6 4h12a2.5 2.5 0 0 1 2.5 2.5v7A2.5 2.5 0 0 1 18 16H6Z"/>',
    check: '<path d="m5 12 5 5L20 7"/>',
    mail: '<path d="M4 7.5A1.5 1.5 0 0 1 5.5 6h13A1.5 1.5 0 0 1 20 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 16.5v-9Z"/><path d="m5 8 7 5 7-5"/>',
    bell: '<path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2H4.5L6 16Z"/><path d="M10 19a2 2 0 0 0 4 0"/>',
    chev: '<path d="m7 10 5 5 5-5"/>',
    search: '<circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/>',
    close: '<path d="m7 7 10 10M17 7 7 17"/>',
    play: '<path d="m9 7 8 5-8 5V7Z"/>',
    calendar: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4M16 3v4M4 10h16"/>',
    users: '<path d="M9 12a3 3 0 1 0-3-3 3 3 0 0 0 3 3Zm8 0a2.5 2.5 0 1 0-2.5-2.5A2.5 2.5 0 0 0 17 12ZM4 19c.4-2.4 2.4-4 5-4s4.6 1.6 5 4M14 15c2.2.2 4 1.6 4.5 4"/>',
    chart: '<path d="M4 19V5M4 19h16"/><path d="m8 15 3-4 3 2 4-6"/>',
    plus: '<path d="M12 6v12M6 12h12"/>',
    menu: '<path d="M5 7h14M5 12h14M5 17h14"/>',
    star: '<path d="m12 3 2.2 4.6L19 8.2l-3.5 3.4.8 4.9L12 14.8 7.7 16.5l.8-4.9L5 8.2l4.8-.6L12 3Z"/>',
    file: '<path d="M7 3.5h7L19 8.5V20a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1Z"/><path d="M14 3.5V9h5"/>',
    pen: '<path d="M4 20h4L19 9l-4-4L4 16v4Z"/><path d="m13.5 6.5 4 4"/>',
    mic: '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>',
    add: '<path d="M12 5v14M5 12h14"/>',
    bank: '<path d="M4 9.5 12 4l8 5.5"/><path d="M6 10v8M10 10v8M14 10v8M18 10v8M4 18h16"/>',
    ext: '<path d="M8 16 16 8M9.5 8H16v6.5"/>',
    folder: '<path d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4.6l2 2.2h8.4A1.5 1.5 0 0 1 21 8.7v9.8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5v-12Z"/>',
    upload: '<path d="M12 15V3m-4 4 4-4 4 4M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5"/>',
    notebook: '<rect x="6" y="3" width="13" height="18" rx="2"/><path d="M4 7.5h4M4 12h4M4 16.5h4"/><path d="M11 8h5M11 11.5h3.5"/>',
    "list-check": '<path d="m3 6 1.5 1.5L7 5M3 12l1.5 1.5L7 11M3 18l1.5 1.5L7 17M11 6h10M11 12h10M11 18h10"/>',
  };
  const wrap = document.createElement("span");
  wrap.className = "ico";
  wrap.innerHTML = `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true">${paths[name] || ""}</svg>`;
  return wrap;
}
