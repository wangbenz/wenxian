import { defineConfig } from "@playwright/test";
import original from "./playwright.config";

const port = Number(process.env.MOBILE_RUNTIME_TEST_PORT ?? 4190);
export default defineConfig({
  ...original,
  testMatch: "**/python-account.spec.ts",
  use: { ...original.use, baseURL: `http://127.0.0.1:${port}` },
  webServer: { command: `npm run dev:ui -- --config vite.account.config.ts --port ${port}`,
    url: `http://127.0.0.1:${port}`, reuseExistingServer: process.env.ACCOUNT_LIVE_TEST === "1" },
});
