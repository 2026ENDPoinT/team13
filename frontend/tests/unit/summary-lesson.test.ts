import { describe, expect, it } from "vitest";
import { summaryLessonDoc } from "../../src/shared/summary-lesson.ts";

const summary = {
  question: "q",
  story: [{ from: 1, to: 3, title: "흐름" }],
  slides: [{ page: 1, tag: "core", title: "a" }, { page: 2, tag: "support", title: "b" }],
};

describe("summaryLessonDoc — 게시본과 검토 후보를 같은 LessonViewer 문서로", () => {
  it("쪽 이미지 주소가 있으면 images 출처와 다시 받기를 붙인다", async () => {
    const refreshImages = async () => "/doc/new/";
    const doc = summaryLessonDoc({ id: "d", title: "t", pageCount: 5, summary, pageImageBase: "/doc/abc/", refreshImages, note: "n" });
    expect(doc).toMatchObject({ id: "d", title: "t", pageCount: 5, summary, overview: null, note: "n" });
    expect(doc.source).toEqual({ kind: "images", base: "/doc/abc/", aspect: null, refresh: refreshImages });
  });

  it("쪽 수를 모르면 요약이 말하는 마지막 쪽(흐름의 to 포함)을 쓴다", () => {
    expect(summaryLessonDoc({ id: "d", title: "t", pageCount: null, summary, pageImageBase: null, note: "" }).pageCount).toBe(3);
  });

  it("쪽 이미지가 없으면 출처 없이(설명만), 쪽 정보가 없는 요약은 0쪽", () => {
    const doc = summaryLessonDoc({ id: "d", title: "t", pageCount: undefined, summary: {}, pageImageBase: undefined, overview: "# 개요", note: "" });
    expect(doc.source).toBeNull();
    expect(doc.pageCount).toBe(0);
    expect(doc.overview).toBe("# 개요");
  });
});
