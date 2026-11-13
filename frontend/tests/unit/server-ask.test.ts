import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as RequestModule from "../../src/shared/assistant/request.ts";

const control = vi.hoisted(() => ({ userId: 7, post: vi.fn() }));
vi.mock("../../src/shared/backend.ts", () => ({
  backendState: () => ({ me: { userId: control.userId } }),
  courseIdFor: (kj: string | null) => kj === "c731" ? 731 : null,
}));
vi.mock("../../src/shared/assistant/request.ts", async (importOriginal) => ({
  ...(await importOriginal<typeof RequestModule>()),
  postAssistant: control.post,
}));
// The real check (school-content.tsx) pulls in the screen modules; this keeps its rule: https on the school host only.
vi.mock("../../src/shared/school-content.tsx", () => ({
  schoolHref: (url: unknown) => typeof url === "string" && url.startsWith("https://eclass.seoultech.ac.kr/") ? url : null,
}));

import { askServer } from "../../src/shared/assistant/server-ask.ts";

const SCHOOL = "https://eclass.seoultech.ac.kr/ilos/st/course/test_view_form.acl?QZ_SEQ=7";
const answer = (body: Record<string, unknown>) => control.post.mockResolvedValueOnce({ ok: true, data: { say: "", actions: [], source: "model", ...body } });
const signal = () => new AbortController().signal;

beforeEach(() => {
  control.post.mockReset();
  control.userId += 1; // a fresh conversation per test
  history.replaceState(null, "", "/");
});

describe("askServer", () => {
  it("문장과 지금 보는 과목(백엔드 ID)·활동(n 그대로)을 보낸다", async () => {
    history.replaceState(null, "", "/ilos/cls/st/activity/activity_form.acl?m=report&n=school-12&kj=c731");
    answer({ say: "기계학습 강의실로 갈게요", actions: [{ label: "기계학습", href: "/ilos/cls/st/submain/submain_form.acl?kj=c12", external: false }], source: "rule" });
    const result = await askServer("기계학습 강의실로 가줘", signal());
    expect(control.post.mock.calls[0][0]).toEqual({
      text: "기계학습 강의실로 가줘",
      context: { courseId: 731, activityId: "school-12", history: [] },
    });
    expect(result).toEqual({ ok: true, say: "기계학습 강의실로 갈게요", actions: [{ label: "기계학습", href: "/ilos/cls/st/submain/submain_form.acl?kj=c12", external: false }] });
  });

  it("내 과목이 아닌 kj 는 보내지 않고, n 이 없으면 act 를 쓴다", async () => {
    history.replaceState(null, "", "/ilos/cls/st/activity/activity_form.acl?m=report&act=9201&kj=sample");
    answer({ say: "확인" });
    await askServer("이거 뭐야", signal());
    expect(control.post.mock.calls[0][0].context).toEqual({ activityId: "9201", history: [] });
  });

  it("학교 https 주소만 새 탭 행동으로, 앱 밖으로 나가는 내부 주소는 버린다", async () => {
    answer({ say: "세 곳", actions: [
      { label: "학교 시험", href: SCHOOL, external: true },
      { label: "다른 사이트", href: "https://evil.example/x", external: true },
      { label: "스킴 상대", href: "//evil.example/x", external: false },
      { label: "자바스크립트", href: "javascript:alert(1)", external: false },
      { label: "과제", href: "/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731", external: false },
    ] });
    const result = await askServer("이번 주에 끝내야 할 거 알려줘", signal());
    expect(result.actions).toEqual([
      { label: "학교 시험", href: SCHOOL, external: true },
      { label: "과제", href: "/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-8401&kj=c731", external: false },
    ]);
  });

  it("실패는 상태별 문장으로, 모양이 틀린 답은 처리하지 못했다고 한다", async () => {
    control.post.mockResolvedValueOnce({ ok: false, status: 429, retryAfter: 9 });
    expect(await askServer("x", signal())).toEqual({ ok: false, say: "AI 요청이 많습니다. 9초 뒤 다시 시도하세요", actions: [] });
    control.post.mockResolvedValueOnce({ ok: false, status: 503, retryAfter: null });
    expect((await askServer("x", signal())).say).toBe("AI 연결이 설정되지 않았습니다");
    control.post.mockResolvedValueOnce({ ok: true, data: { say: 3, actions: [] } });
    expect((await askServer("x", signal())).say).toBe("AI 응답을 처리하지 못했습니다");
  });

  it("같은 사용자의 최근 네 턴을 오래된 순으로 이어 보낸다", async () => {
    for (let i = 0; i < 5; i += 1) answer({ say: `답 ${i}` });
    for (let i = 0; i < 5; i += 1) await askServer(`질문 ${i}`, signal());
    expect(control.post.mock.calls[4][0].context.history).toEqual([
      { text: "질문 0", say: "답 0" }, { text: "질문 1", say: "답 1" }, { text: "질문 2", say: "답 2" }, { text: "질문 3", say: "답 3" },
    ]);
    control.userId += 1;
    answer({ say: "새 사용자" });
    await askServer("안녕", signal());
    expect(control.post.mock.calls[5][0].context.history).toEqual([]);
  });
});
