import { expect, test } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";

/** 真实账号链。prepare 禁止行动提交；execute 读取明确范围及有限/不设总上限计划。 */
test("原 H5 真实账号准备或显式首回验收", async ({ page }) => {
  test.skip(!process.env.REAL_ACCOUNT_MANIFEST, "必须显式提供独立账号计划");
  const plan = JSON.parse(await readFile(process.env.REAL_ACCOUNT_MANIFEST!, "utf8"));
  const execute = process.env.REAL_ACCOUNT_PHASE === "execute";
  expect(["prepare", "execute"]).toContain(process.env.REAL_ACCOUNT_PHASE);
  const credentials = JSON.parse(await readFile(plan.credentials_file, "utf8"));
  const db = new DatabaseSync(plan.database, { readOnly: true });
  const count = () => Number((db.prepare("SELECT COUNT(*) AS n FROM model_call").get() as { n: number }).n);
  expect(count()).toBe(0);
  expect(plan.model).toBe("deepseek-flash"); expect(plan.reasoning_effort).toBe("none");
  if (execute && plan.call_budget_mode === "unlimited") {
    expect(plan.authorization_state).toBe("authorized");
    expect(plan.authorized_call_limit).toBeNull();
  } else if (execute) expect(plan.authorized_call_limit).toBe(4);
  else expect(plan.authorized_call_limit).toBe(0);
  const pointCost = plan.point_cost ?? 4;
  let posts = 0;
  let submitted: { request_id: string; expected_revision: number; action: string } | null = null;
  await page.route("**/api/**", async route => {
    const req = route.request(); const path = new URL(req.url()).pathname;
    if (req.method() !== "GET") {
      if (path.endsWith("/actions")) {
        if (!execute) throw new Error("准备阶段禁止提交行动");
        const body = req.postDataJSON();
        expect(body.action).toBe(plan.action); expect(body.expected_revision).toBe(0);
        posts++; expect(posts).toBe(1); submitted = body;
      } else if (!["/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/runtime/v1/account/saves"].includes(path)) {
        throw new Error("计划范围之外的写请求");
      }
    }
    await route.continue();
  });
  const login = async () => {
    await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
    await page.getByLabel("密码", { exact: true }).fill(credentials.password);
    const response = page.waitForResponse(result => result.request().method() === "POST" && new URL(result.url()).pathname === "/api/v1/auth/login");
    await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
    expect((await response).status()).toBe(200);
  };
  const enterExisting = async (rows: { id: string; name: string }[]) => {
    expect(Array.isArray(rows)).toBe(true);
    const named = rows.filter(row => row.name === plan.character_name);
    const index = plan.save_id ? named.findIndex(row => row.id === plan.save_id) : 0;
    expect(index).toBeGreaterThanOrEqual(0);
    await page.getByRole("button", { name: `进入${plan.character_name}的命卷`, exact: true }).nth(index).click();
  };
  const saveResult = async (name: string, data: unknown) => writeFile(`${plan.evidence_dir}/${name}.json`, JSON.stringify(data, null, 2), { flag: "wx", mode: 0o600 });
  try {
    await page.goto(plan.entry_path ?? "/?runtime=python-account");
    const health = await (await page.request.get("/api/runtime/v1/health")).json(); expect(health.mode).toBe("langchain");
    if (!execute) {
      const existing = db.prepare("SELECT id FROM identity_account WHERE kind='PLAYER' AND login=?").get(credentials.email);
      if (existing) {
        await login();
        const rows = await (await page.request.get("/api/runtime/v1/account/saves")).json();
        if (rows.length) await enterExisting(rows);
      } else {
        await page.getByRole("tab", { name: "注册", exact: true }).click();
        await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
        await page.getByLabel("密码", { exact: true }).fill(credentials.password);
        await page.getByLabel("确认密码", { exact: true }).fill(credentials.password);
        const registered = page.waitForResponse(result => result.request().method() === "POST" && new URL(result.url()).pathname === "/api/v1/auth/register");
        await page.getByRole("button", { name: "注册并创建命卷", exact: true }).click();
        expect((await registered).status()).toBe(200);
      }
      const rows = await (await page.request.get("/api/runtime/v1/account/saves")).json();
      expect(Array.isArray(rows)).toBe(true);
      if (!rows.length) {
        await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
        await page.getByRole("textbox", { name: "角色姓名" }).fill(plan.character_name);
        await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
        await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("在青溪镇落脚");
        await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
        const created = page.waitForResponse(response => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/runtime/v1/account/saves");
        await page.getByRole("button", { name: "确认并开启命卷" }).click();
        expect((await created).status()).toBe(201);
      }
      await expect(page.locator(".creation-flow-view")).toHaveCount(0);
    } else {
      await login();
      await enterExisting(await (await page.request.get("/api/runtime/v1/account/saves")).json());
    }
    await expect(page.getByRole("heading", { name: "青木坊的来货", exact: true })).toBeVisible();
    const saves = await (await page.request.get("/api/runtime/v1/account/saves")).json();
    if (!plan.save_id) expect(saves).toHaveLength(1);
    const chosen = plan.save_id ? saves.find((row: { id: string }) => row.id === plan.save_id) : saves[0];
    expect(chosen).toBeTruthy(); const base = `/api/runtime/v1/account/saves/${chosen.id}`;
    const before = await (await page.request.get(base)).json();
    expect(before.revision).toBe(0); expect(before.history.every((row: { mode: string }) => row.mode === "opening")).toBe(true);
    if (!execute) {
      await page.getByRole("button", { name: "设置", exact: true }).click();
      await page.getByRole("button", { name: "大字", exact: true }).click();
      await page.reload();
      await expect(page.locator(".game-shell")).toHaveAttribute("data-font-size", "large");
      await page.getByRole("button", { name: "设置", exact: true }).click();
      await page.locator("summary").filter({ hasText: "账号" }).click();
      await page.getByRole("button", { name: "退出账号", exact: true }).click();
      await login();
      await expect(page.getByRole("heading", { name: "青木坊的来货", exact: true })).toBeVisible();
      const settings = await (await page.request.get("/api/v1/generation/settings")).json();
      const credits = await (await page.request.get("/api/v1/account/credits")).json();
      expect(settings.platform.available).toBe(true); expect(count()).toBe(0); expect(posts).toBe(0);
      const grant = plan.opening_grant ?? 4;
      expect(credits.balance).toMatchObject({ granted: grant, spent: 0, reserved: 0, available: grant });
      await saveResult("prepared", { save_id: chosen.id, revision: before.revision, calls: count(), posts, settings, credits });
    } else {
      await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
      await page.getByRole("textbox", { name: "自由行动" }).fill(plan.action);
      const accepted = page.waitForResponse(response => response.request().method() === "POST" && response.url().endsWith("/actions"));
      await page.getByRole("button", { name: "确认此行动", exact: true }).click(); expect((await accepted).status()).toBe(202);
      let status: { status: string } = { status: "UNKNOWN" };
      await expect.poll(async () => {
        status = await (await page.request.get(`${base}/actions/${submitted!.request_id}`)).json();
        return ["COMMITTED", "FAILED"].includes(status.status);
      }, { timeout: 175_000, intervals: [500, 1000, 1500] }).toBe(true);
      const after = await (await page.request.get(base)).json();
      const credits = await (await page.request.get("/api/v1/account/credits")).json();
      await saveResult("real-result", { posts, request: submitted, status, snapshot: after, calls: count(), credits });
      expect(credits.balance.reserved).toBe(0);
      expect(credits.balance.spent).toBe(status.status === "COMMITTED" ? pointCost : 0);
      expect(count()).toBeLessThanOrEqual(4); expect(status.status).toBe("COMMITTED");
      await expect(page.getByText(after.state.narrative[0], { exact: true })).toBeVisible();
      await page.reload(); await expect(page.getByText(after.state.narrative[0], { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "设置", exact: true }).click();
      await page.locator("summary").filter({ hasText: "账号" }).click();
      await page.getByRole("button", { name: "退出账号", exact: true }).click();
      await login();
      await expect(page.getByText(after.state.narrative[0], { exact: true })).toBeVisible();
      const recoveredCredits = await (await page.request.get("/api/v1/account/credits")).json();
      expect(recoveredCredits).toEqual(credits);
      expect(posts).toBe(1);
      await saveResult("recovered", { same_narrative: true, same_credits: true, posts, calls: count() });
    }
  } finally { db.close(); }
});
