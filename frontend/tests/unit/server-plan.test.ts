import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as BackendModule from "../../src/shared/backend.ts";

// 로그인한 비서의 계획(`plan.steps`)을 샘플과 같은 실행기로 돌린다. 단계는 data.ts 를 찾지 않고 스스로 제목·주소·방식을 가진다.
const control = vi.hoisted(() => ({ user: "1" as string | null }));
vi.mock("../../src/shared/backend.ts", async (importOriginal) => ({
  ...(await importOriginal<typeof BackendModule>()),
  currentUserId: () => control.user,
}));

import { getState, update } from "../../src/store.ts";
import {
  advance, completeStep, completionToken, createRun, createServerRun, manualStep, observeScreen, planGroups, runState,
  screenKey, startRun, stepInfo, type ServerPlanStep,
} from "../../src/shared/assistant/plan.ts";

const KJ = new URLSearchParams("kj=c3");
const step = (over: Partial<ServerPlanStep>): ServerPlanStep => ({
  id: "srv-12", kind: "video", title: "6주차 강의", course: "기계학습", due: null, external: false, mode: "auto",
  href: "/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&n=srv-12&kj=c3", key: screenKey("srv-lecture-12", KJ), ...over,
});
const report = step({ id: "srv-5", kind: "report", title: "과제 1", mode: "confirm", due: "2026-11-14T23:59:00",
  href: "/ilos/cls/st/activity/activity_form.acl?m=report&n=srv-5&kj=c3", key: screenKey("srv-report-5", KJ) });
const video2 = step({ id: "srv-13", title: "7주차 강의", href: "/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&n=srv-13&kj=c3",
  key: screenKey("srv-lecture-13", KJ) });
const schoolQuiz = step({ id: "school-7", kind: "test", title: "학교 퀴즈", mode: "confirm", external: true,
  href: "https://eclass.seoultech.ac.kr/x", key: "external:school-7" });
const schoolVideo = step({ id: "school-9", title: "학교 영상", mode: "confirm",
  href: "/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&n=school-9&kj=c3", key: screenKey("srv-lecture-school-9", KJ) });

beforeEach(() => {
  control.user = "1";
  update((state) => { state.assistant = { date: "2026-11-12", requests: [] }; });
});

describe("server plan steps", () => {
  it("그룹은 샘플처럼 영상·과제·기타이고 그룹 안은 서버 순서, 제목·마감·주소는 단계 자신의 것이다", () => {
    const run = createServerRun([report, step({}), schoolQuiz, video2, step({ id: "dup" })], "1");
    expect(run.owner).toBe("1");
    expect(run.steps.map((item) => item.server?.id)).toEqual(["srv-12", "srv-13", "srv-5", "school-7"]);
    expect(planGroups(run).map((group) => [group.id, group.steps.length])).toEqual([["video", 2], ["report", 1], ["other", 1]]);
    expect(stepInfo(run.steps[2])).toEqual({ type: "report", title: "기계학습 · 과제 1", meta: "11/14 23:59까지", href: report.href, external: false });
    expect([run.steps[0], run.steps[2], run.steps[3]].map(manualStep)).toEqual([false, true, true]);
  });

  it("다른 사용자·샘플 모드에서는 보이지 않고, 샘플 계획은 로그인한 사용자에게 보이지 않는다", () => {
    createServerRun([step({})], "1");
    control.user = "2";
    expect(runState()).toBeNull();
    control.user = null;
    expect(runState()).toBeNull();
    createRun(["lec-11-1"]);
    expect(runState()?.owner).toBeUndefined();
    control.user = "1";
    expect(runState()).toBeNull();
  });

  it("Accept 하면 첫 단계로 가고, 네이티브 영상은 그 화면의 영상이 끝나야 다음으로 넘어간다", () => {
    const navigate = vi.fn();
    createServerRun([step({}), video2, report], "1");
    startRun({ navigate });
    expect(navigate).toHaveBeenLastCalledWith(step({}).href);
    // 강의 상세와 플레이어는 같은 화면 id(srv-lecture-12)라 계획이 멈추지 않는다.
    observeScreen("srv-12", screenKey("srv-lecture-12", KJ));
    expect(runState()?.status).toBe("running");
    const token = completionToken(screenKey("srv-lecture-12", KJ));
    expect(completeStep(token, { navigate, event: "report-submitted" })).toBe(false);
    expect(completeStep(token, { navigate, event: "video-ended" })).toBe(true);
    expect(runState()?.steps[0].status).toBe("done");
    expect(navigate).toHaveBeenLastCalledWith(video2.href);
    // 다른 단계의 토큰과 다른 화면은 지금 단계를 끝내지 않는다.
    expect(completeStep(token, { navigate, event: "video-ended" })).toBe(false);
    observeScreen("srv-99", screenKey("srv-lecture-99", KJ));
    expect(runState()?.status).toBe("paused");
  });

  it("네이티브 과제는 제출로 끝나고, 학교·새 탭 단계는 이벤트로 끝나지 않으며 학생이 다음으로를 눌러야 넘어간다", () => {
    const navigate = vi.fn();
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    createServerRun([report, schoolVideo, schoolQuiz], "1");
    expect(runState()?.steps.map((item) => item.server?.id)).toEqual(["school-9", "srv-5", "school-7"]);
    startRun({ navigate });
    expect(navigate).toHaveBeenLastCalledWith(schoolVideo.href);
    expect(completeStep(completionToken(schoolVideo.key), { navigate, event: "video-ended" })).toBe(false);
    advance({ navigate });
    expect(navigate).toHaveBeenLastCalledWith(report.href);
    expect(completeStep(completionToken(report.key), { navigate, event: "report-submitted" })).toBe(true);
    // 새 탭 단계: 학교 주소를 새 탭으로 열고, 이 화면에 남아도 계획은 멈추지 않는다.
    expect(open).toHaveBeenCalledWith(schoolQuiz.href, "_blank", "noopener,noreferrer");
    observeScreen("", screenKey("home", new URLSearchParams()));
    expect(runState()?.status).toBe("running");
    expect(completeStep(completionToken(schoolQuiz.key), { navigate, event: "survey-saved" })).toBe(false);
    advance({ navigate });
    expect(runState()?.status).toBe("done");
    expect(getState().assistant.run?.steps.map((item) => item.status)).toEqual(["done", "done", "done"]);
  });
});
