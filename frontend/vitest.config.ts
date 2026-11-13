import { defineConfig } from "vitest/config";

// jsdom: store.ts reads localStorage and document at import time; session-drafts walks real form controls.
export default defineConfig({
  test: {
    environment: "jsdom",
    include: ["tests/**/*.test.ts"],
    restoreMocks: true,
    unstubGlobals: true,
    coverage: {
      provider: "v8",
      reporter: [["text", { skipFull: false }], "html", "json-summary"],
      reportsDirectory: "coverage",
      // 현재 수치(문 98 / 분기 94 / 함수 98 / 줄 99)보다 조금 낮게 잡아 회귀만 막는다. 올리려면 먼저 테스트를 추가한다.
      thresholds: { statements: 95, branches: 90, functions: 95, lines: 95 },
      // Only the modules this suite is designed to cover; UI components (tsx) are out of scope (docs/TEST_STRATEGY.md §6).
      include: [
        "src/shared/format.ts",
        "src/shared/routes.ts",
        "src/shared/remote-cache.ts",
        "src/shared/webm-append.ts",
        "src/shared/session-drafts.ts",
        "src/shared/activity-links.ts",
        "src/shared/page-image-retry.ts",
        "src/shared/dialog-names.ts",
        "src/shared/recording-join.ts",
        "src/shared/summary-lesson.ts",
        "src/shared/live-transcript.ts",
        "src/shared/assistant/course-match.ts",
        "src/shared/assistant/aliases.ts",
        "src/shared/assistant/resolve.ts",
        "src/store.ts",
      ],
    },
  },
});
