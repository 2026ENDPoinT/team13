import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { courseCover } from "../../src/shared/course-view.ts";
import { CoverImage } from "../../src/ui/assets.tsx";
import { course } from "../helpers/fixtures.ts";

describe("서버 과목 커버", () => {
  it.each([undefined, null, ""])("템플릿 %s인 서버 과목도 실제 로컬 커버 이미지로 렌더한다", (coverTemplate) => {
    const appearance = courseCover({ courseId: 901, coverTemplate });
    const serverCourse = course({ id: "c901", ko: "서버 과목", en: "", server: true, ...appearance });
    const host = document.createElement("div");
    host.innerHTML = renderToStaticMarkup(createElement(CoverImage, { course: serverCourse }));
    const src = host.querySelector("img.asset-cover")?.getAttribute("src");
    expect(src).toMatch(/^\/app\/assets\/covers\//);
    expect(existsSync(resolve("public", src!.slice(1)))).toBe(true);
  });

  it("템플릿이 있으면 과목 ID와 무관하게 기존 템플릿 이미지를 유지한다", () => {
    const explicit = courseCover({ courseId: 901, coverTemplate: "template-2" });
    expect(courseCover({ courseId: 902, coverTemplate: "template-2" })).toEqual(explicit);
    expect(explicit.cover).toMatch(/^\/app\/assets\/covers\//);
  });
});
