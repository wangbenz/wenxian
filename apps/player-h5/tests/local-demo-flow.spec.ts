import { expect, test } from "@playwright/test";

test("local demo runs the real player flow without the Java backend", async ({ page }) => {
  await page.goto("/?demo=reset");

  await expect(page.getByRole("button", { name: "进入问仙录" })).toBeVisible();
  await page.getByRole("textbox", { name: "邮箱" }).fill("demo@wenxian.local");
  await page.getByRole("textbox", { name: "密码" }).fill("wenxian123");
  await page.getByRole("button", { name: "进入问仙录" }).click();

  await expect(page.getByRole("button", { name: "进入沈照的命卷" })).toBeVisible();
  await page.getByRole("button", { name: "进入沈照的命卷" }).click();
  await expect(page.getByRole("heading", { name: "渡口故人" })).toBeVisible();
  await expect(page.locator(".game-header")).toHaveCount(0);
  await expect(page.locator(".story-page-header")).toContainText("本回");

  await page.getByRole("button", { name: /先问清故人的名讳/ }).click();
  await page.getByRole("button", { name: "确认行动" }).click();
  await expect(page.getByText("已确认", { exact: true })).toBeVisible();
  await expect(page.getByText("正在等待这一回的结果", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "你准备如何回应？" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /跟随对方进入渡口/ })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "雾中回音" })).toBeVisible();
  await expect(page.getByText("本回合已写入统一时间线", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "人物" }).click();
  await expect(page.locator(".game-header")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "人物", exact: true })).toBeVisible();
  await expect(page.getByRole("tablist", { name: "人物记录分类" })).toBeVisible();
  await expect(page.getByRole("tab", { name: /功法 2/ })).toHaveAttribute("aria-selected", "true");
  await page.getByRole("tab", { name: /行囊 2/ }).click();
  await expect(page.getByRole("button", { name: "整理行囊" })).toBeVisible();
  await page.getByRole("tab", { name: /人物 2/ }).click();
  await expect(page.getByRole("button", { name: "整理关注" })).toBeVisible();
  await expect(page.getByText("沈照", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "前情" }).click();
  await expect(page.locator(".game-header")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "前情", exact: true })).toBeVisible();
  await expect(page.getByText("雾中旧信", { exact: true })).toBeVisible();
  await expect(page.getByText("发生之事", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("统一世界时", { exact: true })).toHaveCount(0);
  await expect(page.getByText("世界事件", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "修正命卷中记错的事实" }).click();
  await expect(page.getByRole("dialog").getByRole("heading", { name: "修正命卷事实" })).toBeVisible();
  await page.getByRole("button", { name: "暂不修正" }).click();
  await page.getByRole("button", { name: "设置" }).click();
  await expect(page.locator(".game-header")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "设置", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "阅读", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "续写", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "故事记录", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "修正命卷中记错的事实" })).toHaveCount(0);
  await expect(page.getByText("命卷", { exact: true })).toBeVisible();
  await expect(page.getByText("账号", { exact: true })).toBeVisible();
  await expect(page.getByText("API Key", { exact: false })).toHaveCount(0);
  await expect(page.locator(".quick-settings-backdrop")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "打开设置" })).toHaveCount(0);
});

test("character design preview keeps large records behind one focused category", async ({ page }) => {
  await page.goto("/?design=character");

  await expect(page.getByRole("tab", { name: /功法 12/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("button", { name: "查看全部 12 项" })).toBeVisible();
  await expect(page.getByText("云纹铜令", { exact: true })).toHaveCount(0);

  await page.getByRole("tab", { name: /行囊 26/ }).click();
  await expect(page.getByRole("heading", { name: /云纹铜令/ })).toBeVisible();
  await expect(page.getByText("晏迟", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "查看全部 26 项" }).click();
  await expect(page.getByRole("heading", { name: "行囊", exact: true })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "搜索行囊" })).toBeVisible();
  await page.getByRole("searchbox", { name: "搜索行囊" }).fill("断剑");
  await expect(page.getByRole("heading", { name: /无鞘断剑/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: /云纹铜令/ })).toHaveCount(0);
  await page.getByRole("button", { name: "人物" }).first().click();

  await page.getByRole("tab", { name: /人物 43/ }).click();
  await expect(page.getByRole("button", { name: "查看全部 43 项" })).toBeVisible();
});

test("local demo can create a new save through all three creation steps", async ({ page }) => {
  await page.goto("/?demo=reset");
  await page.getByRole("textbox", { name: "邮箱" }).fill("demo@wenxian.local");
  await page.getByRole("textbox", { name: "密码" }).fill("wenxian123");
  await page.getByRole("button", { name: "进入问仙录" }).click();
  await page.getByRole("button", { name: "开启新命卷" }).click();

  await page.getByRole("button", { name: "男", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("林渡");
  await page.getByRole("button", { name: /下一步 · 选择入世路线/ }).click();
  await page.getByRole("button", { name: "推演三条角色路线", exact: true }).click();
  await page.getByRole("button", { name: /1 断剑归客/ }).click();
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await page.getByRole("button", { name: "确认并开启命卷" }).click();

  await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "命卷初开" })).toBeVisible();
  await expect(page.getByText(/林渡是青岚山覆灭后/)).toBeVisible();
});

test("local demo can return from character creation to the save list", async ({ page }) => {
  await page.goto("/?demo=reset");
  await page.getByRole("textbox", { name: "邮箱" }).fill("demo@wenxian.local");
  await page.getByRole("textbox", { name: "密码" }).fill("wenxian123");
  await page.getByRole("button", { name: "进入问仙录" }).click();
  await page.getByRole("button", { name: "开启新命卷" }).click();

  await expect(page.getByRole("heading", { name: "定下此身" })).toBeVisible();
  await page.getByRole("button", { name: "返回命卷", exact: true }).click();

  await expect(page.getByRole("button", { name: "进入沈照的命卷" })).toBeVisible();
  await expect(page.getByRole("button", { name: "开启新命卷" })).toBeVisible();
});
