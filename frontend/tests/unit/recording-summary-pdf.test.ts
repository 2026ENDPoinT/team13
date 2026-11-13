import { describe, expect, it } from "vitest";
import { summaryDocument } from "../../src/areas/home/recording-summary-pdf.ts";

describe("요약 PDF 문서", () => {
  it("서버 텍스트를 이스케이프하고 개요·구간·표·메모를 모두 담는다", () => {
    const html = summaryDocument("<b>강의</b>", {
      overview: "개요 & 요약",
      note: "주의 \"메모\"",
      sections: [{
        title: "해저드", at: 65,
        points: [{ term: "<img src=x onerror=alert(1)>", text: "설명", at: 70 }],
        table: { head: ["종류"], rows: [["구조적"]] },
      }],
    });
    expect(html).not.toContain("<b>강의</b>");
    expect(html).not.toContain("<img");
    expect(html).toContain("&#60;b&#62;강의&#60;/b&#62;");
    expect(html).toContain("개요 &#38; 요약");
    expect(html).toContain("<time>1:05</time>해저드");
    expect(html).toContain("<th>종류</th>");
    expect(html).toContain("<td>구조적</td>");
    expect(html).toContain("주의 &#34;메모&#34;");
  });
});
