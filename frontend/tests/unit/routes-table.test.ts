import { describe, expect, it } from "vitest";
import { routeFor } from "../../src/shared/routes.ts";
import { ROWS } from "../helpers/route-rows.ts";

// routeFor 의 계약: 역할 × 종류 × 입력 → 기대 주소 표는 helpers/route-rows.ts 에 있다.
// 속성 테스트(routes.test.ts)는 "어느 영역인가"만 보므로, 종류가 엉뚱한 화면으로 연결되는 오류는 이 표가 잡는다.
// 화면 경로를 일부러 바꿀 때는 표도 함께 고친다.
describe("routeFor 주소 표", () => {
  it.each(ROWS)("%s · %s · %j", (role, kind, extra, href) => {
    expect(routeFor(role, kind, extra)).toBe(href);
  });
});
