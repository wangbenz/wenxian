import { expect, test } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";

/** 实际页面只读核对；禁止行动和任何创建/设置写入，不需要新增模型预算。 */
test("实际资料页显示本局固定版本，查看与刷新不改变事实", async ({ page }) => {
  test.skip(process.env.LIBRARY_WORLD_PHASE !== "inspect" || !process.env.LIBRARY_WORLD_MANIFEST, "只在显式只读检查阶段执行");
  const plan = JSON.parse(await readFile(process.env.LIBRARY_WORLD_MANIFEST!, "utf8"));
  const credentials = JSON.parse(await readFile(plan.credentials_file, "utf8"));
  expect(plan.authorized_call_limit).toBe(0);
  const db = new DatabaseSync(plan.database, { readOnly: true });
  const calls = () => Number((db.prepare("SELECT COUNT(*) AS n FROM model_call").get() as { n: number }).n);
  const row = db.prepare("SELECT setting_hash,setting_json FROM save_world_setting WHERE save_id=?").get(plan.save_id) as { setting_hash: string; setting_json: string };
  const setting = JSON.parse(row.setting_json);
  const realm = setting.definitions["realms:lianqi"];
  const practice = setting.definitions["arts:basic-breathing"];
  expect(row.setting_hash).toBe(plan.setting_hash);
  expect(calls()).toBe(0);
  let actionPosts = 0;
  await page.route("**/api/**", async route => {
    const request = route.request();
    if (request.method() !== "GET" && new URL(request.url()).pathname !== "/api/v1/auth/login") {
      if (new URL(request.url()).pathname.endsWith("/actions")) actionPosts++;
      throw new Error("只读资料检查禁止写入游戏状态或配置");
    }
    await route.continue();
  });
  try {
    await page.goto("/?runtime=python-account");
    await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
    await page.getByLabel("密码", { exact: true }).fill(credentials.password);
    const authenticated = page.waitForResponse(result => result.request().method() === "POST" && new URL(result.url()).pathname === "/api/v1/auth/login");
    await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
    expect((await authenticated).status()).toBe(200);
    const saves = await (await page.request.get("/api/runtime/v1/account/saves")).json();
    const named = saves.filter((value: { name: string }) => value.name === plan.character_name);
    const index = named.findIndex((value: { id: string }) => value.id === plan.save_id);
    expect(index).toBeGreaterThanOrEqual(0);
    await page.getByRole("button", { name: `进入${plan.character_name}的命卷`, exact: true }).nth(index).click();
    const base = `/api/runtime/v1/account/saves/${plan.save_id}`;
    const before = await (await page.request.get(base)).json();
    await expect(page.getByText(before.state.narrative[0], { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.getByRole("button", { name: "大字", exact: true }).click();
    await page.getByRole("button", { name: "查看世界资料", exact: false }).click();
    const realmSection = page.locator(".reference-section").filter({ has: page.getByRole("heading", { name: realm.definition.name, exact: true }) });
    await expect(realmSection).toContainText(realm.definition.description);
    await expect(realmSection).toContainText(realm.definition.limitations);
    await expect(page.locator(".reference-world-intro")).toContainText("本局固定资料");
    await page.getByRole("button", { name: "功法与术法", exact: true }).click();
    await page.locator(".reference-list button").filter({ hasText: practice.definition.name }).click();
    await expect(page.getByRole("heading", { name: practice.definition.name, exact: true })).toBeVisible();
    await expect(page.locator(".reference-version")).toContainText(`本局采用的资料 · 第 ${practice.revision} 版`);
    await expect(page.locator(".reference-detail")).toContainText(practice.definition.usage);
    await expect(page.locator(".reference-detail")).toContainText(practice.definition.limitations);
    await expect(page.getByText("出处与改编依据", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("searchbox")).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole("heading", { name: practice.definition.name, exact: true })).toBeVisible();
    const layout = [];
    for (const width of [320, 390, 1100]) {
      await page.setViewportSize({ width, height: 844 });
      const paragraph = page.locator(".reference-section p").first();
      await expect(paragraph).toHaveCSS("font-size", "24px");
      const fits = await paragraph.evaluate(element => element.scrollWidth <= element.clientWidth + 1);
      expect(fits).toBe(true);
      layout.push({ width, content_fits: fits });
    }
    const after = await (await page.request.get(base)).json();
    expect(after).toEqual(before);
    expect(calls()).toBe(0); expect(actionPosts).toBe(0);
    const afterBinding = db.prepare("SELECT setting_hash,setting_json FROM save_world_setting WHERE save_id=?").get(plan.save_id);
    expect(afterBinding).toEqual(row);
    await writeFile(`${plan.evidence_dir}/reference-inspection.json`, JSON.stringify({
      save_id: plan.save_id, setting_hash: row.setting_hash, same_state: true, same_binding: true,
      practice_revision: practice.revision, model_calls: calls(), action_posts: actionPosts, layout,
    }, null, 2), { flag: "wx", mode: 0o600 });
  } finally { db.close(); }
});

/** 新资料设定的真实连续回放。只有显式授权的计划才可执行，零自动重跑。 */
test("真实资料设定参与三回行动、保存、扣点与重登", async ({ page }) => {
  test.skip(process.env.LIBRARY_WORLD_PHASE !== "execute" || !process.env.LIBRARY_WORLD_MANIFEST, "必须先准备并授权新的真实批次");
  const plan = JSON.parse(await readFile(process.env.LIBRARY_WORLD_MANIFEST!, "utf8"));
  const unlimited = plan.call_budget_mode === "unlimited";
  if (unlimited) {
    expect(plan.authorization.state).toBe("authorized"); expect(plan.authorized_call_limit).toBeNull();
  } else {
    expect(Number.isSafeInteger(plan.authorized_call_limit)).toBe(true);
    expect(plan.authorized_call_limit).toBeGreaterThan(0);
  }
  expect(plan.model).toBe("deepseek-flash"); expect(plan.reasoning_effort).toBe("none");
  expect(plan.tool_protocol).toBe("deepseek-strict"); expect(plan.actions).toHaveLength(3);
  expect(plan.max_turns).toBe(3); expect(plan.stop_on_first_failure).toBe(true);
  const credentials = JSON.parse(await readFile(plan.credentials_file, "utf8"));
  const db = new DatabaseSync(plan.database, { readOnly: true });
  const calls = () => Number((db.prepare("SELECT COUNT(*) AS n FROM model_call").get() as { n: number }).n);
  expect(calls()).toBe(0);
  const binding = db.prepare("SELECT setting_hash,setting_json FROM save_world_setting WHERE save_id=?").get(plan.save_id) as { setting_hash: string; setting_json: string };
  expect(binding.setting_hash).toBe(plan.setting_hash);
  let posts = 0;
  let activeStep = -1;
  let submitted: { request_id: string; expected_revision: number; action: string } | null = null;
  const base = `/api/runtime/v1/account/saves/${plan.save_id}`;
  const saveResult = async (name: string, value: unknown) => writeFile(`${plan.evidence_dir}/${name}.json`, JSON.stringify(value, null, 2), { flag: "wx", mode: 0o600 });
  await page.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (request.method() !== "GET") {
      if (path === `${base}/actions`) {
        const body = request.postDataJSON();
        expect(activeStep).toBeGreaterThanOrEqual(0);
        expect(body.action).toBe(plan.actions[activeStep]); expect(body.expected_revision).toBe(activeStep);
        posts++; expect(posts).toBe(activeStep + 1); submitted = body;
      } else if (!["/api/v1/auth/login", "/api/v1/auth/logout"].includes(path)) {
        throw new Error("连续计划以外的写请求");
      }
    }
    await route.continue();
  });
  const login = async () => {
    await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
    await page.getByLabel("密码", { exact: true }).fill(credentials.password);
    const response = page.waitForResponse(value => value.request().method() === "POST" && new URL(value.url()).pathname === "/api/v1/auth/login");
    await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
    expect((await response).status()).toBe(200);
  };
  try {
    await page.goto("/?runtime=python-account");
    const health = await (await page.request.get("/api/runtime/v1/health")).json();
    expect(health).toMatchObject({ mode: "langchain", profile: "account" });
    await login();
    const saves = await (await page.request.get("/api/runtime/v1/account/saves")).json();
    const named = saves.filter((row: { name: string }) => row.name === plan.character_name);
    const index = named.findIndex((row: { id: string }) => row.id === plan.save_id);
    expect(index).toBeGreaterThanOrEqual(0);
    await page.getByRole("button", { name: `进入${plan.character_name}的命卷`, exact: true }).nth(index).click();
    await expect(page.locator(".creation-flow-view")).toHaveCount(0);
    const initial = await (await page.request.get(base)).json();
    expect(initial.revision).toBe(0);
    expect(initial.history.every((row: { mode: string }) => row.mode === "opening")).toBe(true);
    const overview = await (await page.request.get(`${base}/reference`)).json();
    expect(overview.world.scope).toContain("本局固定资料");
    await saveResult("real-before", { save_id: plan.save_id, binding, snapshot: initial, overview, calls: calls() });
    let latest = initial;
    for (let step = 0; step < plan.actions.length; step++) {
      activeStep = step;
      const input = page.getByRole("textbox", { name: "自由行动" });
      if (!await input.isVisible()) await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
      await input.fill(plan.actions[step]);
      const accepted = page.waitForResponse(value => value.request().method() === "POST" && new URL(value.url()).pathname === `${base}/actions`);
      await page.getByRole("button", { name: "确认此行动", exact: true }).click();
      expect((await accepted).status()).toBe(202);
      let status: { status: string } = { status: "UNKNOWN" };
      await expect.poll(async () => {
        status = await (await page.request.get(`${base}/actions/${submitted!.request_id}`)).json();
        return ["COMMITTED", "FAILED"].includes(status.status);
      }, { timeout: 175_000, intervals: [500, 1000, 1500] }).toBe(true);
      latest = await (await page.request.get(base)).json();
      const credits = await (await page.request.get("/api/v1/account/credits")).json();
      const row = db.prepare("SELECT candidate_json,references_json,generation_audit_json FROM committed_turn WHERE save_id=? AND turn=?")
        .get(plan.save_id, step + 1) as { candidate_json: string; references_json: string; generation_audit_json: string } | undefined;
      const committed = row ? { candidate: JSON.parse(row.candidate_json), references: JSON.parse(row.references_json), audit: JSON.parse(row.generation_audit_json) } : null;
      await saveResult(`real-step-${step + 1}`, { request: submitted, status, snapshot: latest, credits, committed, calls: calls(), posts });
      if (!unlimited) expect(calls()).toBeLessThanOrEqual(plan.authorized_call_limit);
      expect(status.status).toBe("COMMITTED"); // 首错停止，后续回合不执行。
      expect(latest.revision).toBe(step + 1);
      expect(latest.state.items).toEqual(initial.state.items);
      expect(latest.state.item_origins).toEqual(initial.state.item_origins);
      expect(latest.state.abilities).toEqual(initial.state.abilities);
      expect(latest.state.realm).toBe(initial.state.realm);
      expect(credits.balance).toMatchObject({ spent: step + 1, reserved: 0, available: 3 - step });
      expect(committed!.audit.world_setting_hash).toBe(plan.setting_hash);
      const required = step === 2 ? "firebolt" : "basic-breathing";
      expect(committed!.candidate.used_definitions).toContainEqual({ category: "arts", key: required });
      expect(committed!.references.some((record: { category: string; key: string }) => record.category === "arts" && record.key === required)).toBe(true);
      await expect(page.getByText(latest.state.narrative[0], { exact: true })).toBeVisible();
      await page.reload();
      await expect(page.getByText(latest.state.narrative[0], { exact: true })).toBeVisible();
    }
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.locator("summary").filter({ hasText: "账号" }).click();
    await page.getByRole("button", { name: "退出账号", exact: true }).click();
    await login();
    await expect(page.getByText(latest.state.narrative[0], { exact: true })).toBeVisible();
    const recovered = await (await page.request.get(base)).json();
    expect(recovered.state).toEqual(latest.state);
    expect(posts).toBe(3);
    await saveResult("real-recovered", { revision: recovered.revision, same_state: true, posts, calls: calls() });
  } finally { db.close(); }
});
