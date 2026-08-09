import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    environment: "node",
    testTimeout: 15_000,
    // Several tests deliberately drive the bridge into failure paths; their log lines are
    // noise, not signal. Set LOG_LEVEL=debug when a test is being difficult.
    env: { LOG_LEVEL: process.env.LOG_LEVEL ?? "silent" },
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov"],
      include: ["src/**/*.ts"],
      // Generated, and the real SDK adapter is M1.
      exclude: ["src/protocol.ts", "src/runner/sdk.ts"],
    },
  },
});
