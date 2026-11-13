import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { DIALOG_NAMES, knownDialog, withoutStalePop } from "../../src/shared/dialog-names.ts";

const RENDERERS = ["src/areas/home/dialogs.tsx", "src/areas/course/dialogs.tsx", "src/areas/learning/dialogs.tsx"];

describe("DIALOG_NAMES — 지금 있는 ?pop= 창", () => {
  it("영역 renderDialog들이 비교하는 이름과 같다", () => {
    const compared = new Set<string>();
    for (const file of RENDERERS) {
      const source = readFileSync(file, "utf8");
      for (const match of source.matchAll(/name === "([a-z-]+)"/g)) compared.add(match[1]);
    }
    expect([...DIALOG_NAMES].sort()).toEqual([...compared].sort());
  });

  it.each(["avatar", "files", "history", "history-login", "sessions", "devices", "filebox", ""])("없어진 창 %j는 모른다", (name) => {
    expect(knownDialog(name)).toBe(false);
  });
});

describe("withoutStalePop — 없어진 창 이름은 주소에서 뺀다", () => {
  it("없는 이름이면 pop만 빼고 나머지 순서는 그대로", () => {
    expect(withoutStalePop("?kj=bi&pop=avatar&as=st")).toBe("?kj=bi&as=st");
  });

  it("pop만 있었으면 빈 문자열", () => {
    expect(withoutStalePop("?pop=history")).toBe("");
  });

  it("있는 창이거나 pop이 없으면 null(그대로 둔다)", () => {
    expect(withoutStalePop("?pop=myinfo")).toBeNull();
    expect(withoutStalePop("?kj=bi")).toBeNull();
    expect(withoutStalePop("")).toBeNull();
  });
});
