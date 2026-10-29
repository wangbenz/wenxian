import { defineConfig } from "@playwright/test";

const port = Number(process.env.MOBILE_RUNTIME_TEST_PORT ?? 4190);
export default defineConfig({
  testDir: "./tests", testMatch: "python-library-world.spec.ts", retries: 0, workers: 1,
  timeout: 600_000,
  use: { baseURL: `http://127.0.0.1:${port}`, viewport: { width: 390, height: 844 }, trace: "off", screenshot: "off" },
  webServer: { command: `npm run dev:ui -- --config vite.account.config.ts --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`, reuseExistingServer: false },
});
