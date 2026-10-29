import { defineConfig } from "@playwright/test";

const port = Number(process.env.MOBILE_RUNTIME_TEST_PORT ?? 4193);
process.env.LOCAL_DEFAULT_TEST = "1";

export default defineConfig({
  testDir: "./tests", testMatch: "python-account.spec.ts", retries: 0, workers: 1,
  timeout: 20_000,
  use: { baseURL: `http://127.0.0.1:${port}`, viewport: { width: 390, height: 844 } },
  webServer: {
    command: `npm run dev:ui -- --config vite.local.config.ts --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`, reuseExistingServer: false,
  },
});
