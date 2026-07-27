import { createRequire } from "node:module";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(new URL("../../frontend/package.json", import.meta.url));
const { chromium } = require("@playwright/test");

const output = fileURLToPath(new URL("./", import.meta.url));
await mkdir(output, { recursive: true });

const browser = await chromium.launch({ channel: "chrome", headless: true });
const context = await browser.newContext({
  viewport: { width: 430, height: 932 },
  deviceScaleFactor: 2,
  colorScheme: "light",
  hasTouch: true,
  isMobile: true,
  locale: "zh-CN",
});
const page = await context.newPage();
const shot = async (name) => {
  await page.waitForTimeout(350);
  await page.screenshot({ path: path.join(output, name), fullPage: false });
};

await page.goto("http://116.198.232.119:25173/?marketing=v2", { waitUntil: "networkidle" });
await page.getByRole("heading", { name: "以邮箱领取命卷" }).waitFor();
await shot("02-login.png");

await page.getByLabel("邮箱").fill("marketing.capture.20260727@example.com");
await page.getByRole("button", { name: /进入问仙录/ }).click();
await page.getByRole("heading", { name: "先定姓名，再问仙途" }).waitFor({ timeout: 20_000 });
await page.getByLabel("姓名").fill("沈砚");
await shot("03-create-character.png");

await page.getByRole("button", { name: /生成三种命格/ }).click();
await page.getByRole("heading", { name: "择一命格入世" }).waitFor({ timeout: 120_000 });
await shot("04-fates.png");

await page.locator(".fate-card").first().click();
await page.getByRole("button", { name: /开启命卷/ }).click();
await page.getByRole("heading", { name: "择一道天赋" }).waitFor({ timeout: 120_000 });
await shot("05-talents.png");

await page.locator(".talent-card").first().click();
await page.getByRole("button", { name: /携.*入世/ }).click();
await page.getByText("当前场景", { exact: true }).waitFor({ timeout: 120_000 });

await page.getByRole("button", { name: "世界", exact: true }).click();
await page.getByRole("button", { name: "宣纸", exact: true }).click();
await page.getByRole("button", { name: "历练", exact: true }).click();
await page.locator(".choice-list").scrollIntoViewIfNeeded();
await shot("06-adventure.png");

await page.getByRole("button", { name: "角色", exact: true }).click();
await shot("07-character.png");

await page.getByRole("button", { name: "纪事", exact: true }).click();
await shot("08-timeline.png");

await page.getByRole("button", { name: "世界", exact: true }).click();
await shot("09-world.png");

await browser.close();
