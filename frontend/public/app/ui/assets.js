import { el } from "../dom.js";

export function coverImage(course, width = 233, height = 140) {
  if (!course || !course.cover) return null;
  return el("img", {
    class: "asset asset-cover",
    src: course.cover,
    alt: "",
    width: String(width),
    height: String(height),
  });
}

export function brandLogo() {
  return el("img", {
    class: "asset asset-logo",
    src: "/app/assets/logo/stk-brand-logo-80.png",
    srcset: "/app/assets/logo/stk-brand-logo-64.png 64w, /app/assets/logo/stk-brand-logo-80.png 80w",
    sizes: "(max-width: 767px) 32px, 40px",
    width: "40",
    height: "40",
    alt: "",
  });
}

export function fabArt() {
  return el("img", {
    class: "asset asset-mascot",
    src: "/app/assets/fab/technyon-120.png",
    srcset: "/app/assets/fab/technyon-100.png 100w, /app/assets/fab/technyon-120.png 120w",
    sizes: "(max-width: 767px) 50px, 60px",
    width: "60",
    height: "60",
    alt: "",
  });
}
