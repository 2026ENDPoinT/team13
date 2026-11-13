import { afterEach, describe, expect, it } from "vitest";
import { activities, seedState, type Activity } from "../../src/data.ts";
import { syncActivities } from "../../src/store.ts";
import { activity } from "../helpers/fixtures.ts";

// syncActivities 는 모듈 전역 `activities` 를 직접 바꾸므로 테스트마다 원래대로 되돌린다.
const original = activities.map((item) => ({ ...item }));
afterEach(() => {
  activities.splice(0, activities.length, ...original.map((item) => ({ ...item })));
});

const stateWith = (patch: { authored?: Activity[]; deleted?: string[] }) => {
  const state = seedState();
  state.authoredActivities = patch.authored;
  state.deletedActivityIds = patch.deleted;
  return state;
};
const ids = () => activities.map((item) => item.id);

describe("syncActivities", () => {
  it("교수가 만든 활동을 샘플 목록에 authored 표시로 추가한다", () => {
    const state = stateWith({ authored: [activity({ id: "mine", courseId: "bi", title: "내 활동" })] });
    syncActivities(state);
    expect(activities.find((item) => item.id === "mine")).toMatchObject({ title: "내 활동", authored: true });
    expect(state.authoredActivities?.map((item) => item.id)).toEqual(["mine"]);
  });

  it("여러 번 실행해도 중복되지 않는다(멱등)", () => {
    const state = stateWith({ authored: [activity({ id: "mine", courseId: "bi", title: "내 활동" })] });
    syncActivities(state);
    const once = activities.length;
    syncActivities(state);
    syncActivities(state);
    expect(activities.length).toBe(once);
    expect(ids().filter((id) => id === "mine")).toHaveLength(1);
  });

  it("지운 활동 ID 는 샘플 활동이든 만든 활동이든 목록에서 뺀다", () => {
    const state = stateWith({
      authored: [activity({ id: "mine", courseId: "bi", title: "내 활동" })],
      deleted: ["mine", "v1"],
    });
    syncActivities(state);
    expect(ids()).not.toContain("mine");
    expect(ids()).not.toContain("v1");
    expect(state.authoredActivities).toEqual([]);
  });

  it.each([
    ["빈 제목", ""],
    ["공백만", "   "],
    ["한 글자", "가"],
    ["앞뒤 공백 포함 한 글자", " 가 "],
  ])("제목이 없는 활동(%s)은 뺀다", (_name, title) => {
    const state = stateWith({ authored: [activity({ id: "junk", courseId: "bi", title })] });
    syncActivities(state);
    expect(ids()).not.toContain("junk");
    expect(state.authoredActivities).toEqual([]);
  });

  it("두 글자 제목은 허용한다(경계)", () => {
    const state = stateWith({ authored: [activity({ id: "two", courseId: "bi", title: "ab" })] });
    syncActivities(state);
    expect(ids()).toContain("two");
  });

  it("ID 가 없는 항목은 무시한다", () => {
    const state = stateWith({ authored: [{ ...activity({ id: "x", courseId: "bi", title: "제목" }), id: "" }] });
    syncActivities(state);
    expect(state.authoredActivities).toEqual([]);
  });

  it("샘플 활동과 ID 가 같은 활동은 샘플을 대체한다(교수의 수정)", () => {
    const state = stateWith({ authored: [activity({ id: "v1", courseId: "bi", title: "수정된 강의소개" })] });
    const before = activities.length;
    syncActivities(state);
    const matches = activities.filter((item) => item.id === "v1");
    expect(matches).toHaveLength(1);
    expect(matches[0]).toMatchObject({ title: "수정된 강의소개", authored: true });
    expect(activities.length).toBe(before);
  });

  it("이전에 추가했던 활동이 state 에서 사라지면 목록에서도 사라진다", () => {
    const state = stateWith({ authored: [activity({ id: "mine", courseId: "bi", title: "내 활동" })] });
    syncActivities(state);
    expect(ids()).toContain("mine");
    state.authoredActivities = [];
    syncActivities(state);
    expect(ids()).not.toContain("mine");
  });

  it("authored/deleted 가 없는 오래된 저장 상태도 처리한다", () => {
    const state = stateWith({});
    const before = ids();
    expect(() => syncActivities(state)).not.toThrow();
    expect(ids()).toEqual(before);
    expect(state.authoredActivities).toEqual([]);
  });

  it("제목이 없는 샘플 활동(사용자가 비워 둔 것)은 목록에서 뺀다", () => {
    const state = stateWith({});
    activities.push(activity({ id: "blank-sample", courseId: "bi", title: "" }));
    syncActivities(state);
    expect(ids()).not.toContain("blank-sample");
  });
});
