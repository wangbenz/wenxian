import { expect, test } from "@playwright/test";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";

/** 实际 H5、Python、资料与真实网关配置；准备零调用，执行受整批授权预算约束，不模拟续写。 */
test("护符真实连续使用与刷新重登保持", async ({ page }) => {
  const phase = process.env.ITEM_USE_PHASE;
  test.skip(!process.env.ITEM_USE_MANIFEST || !["prepare", "execute"].includes(phase ?? ""), "只执行显式计划");
  const manifestPath = process.env.ITEM_USE_MANIFEST!;
  const plan = JSON.parse(await readFile(manifestPath, "utf8"));
  const execute = phase === "execute";
  const startTurn = plan.resume_from_turn ?? 0;
  expect(startTurn).toBeGreaterThanOrEqual(0); expect(startTurn).toBeLessThan(4);
  if (!execute) expect(startTurn).toBe(0);
  const previousCalls = plan.previous_batch_calls ?? 0;
  const unlimited = plan.call_budget_mode === "unlimited";
  if (unlimited) {
    expect(plan.authorization.state).toBe("authorized");
    expect(plan.authorized_call_limit).toBe(execute ? null : 0);
  } else {
    const budget = plan.proposed_call_limit ?? plan.authorized_call_limit;
    expect(Number.isSafeInteger(budget)).toBe(true); expect(budget).toBeGreaterThan(0);
    if (execute) expect(budget + previousCalls).toBeLessThanOrEqual(plan.total_authorized_limit ?? budget + previousCalls);
    expect(plan.authorized_call_limit).toBe(execute ? budget : 0);
  }
  expect(plan.model).toBe("deepseek-flash"); expect(plan.reasoning_effort).toBe("none");
  expect(["deepseek-strict", "deepseek-json"]).toContain(plan.tool_protocol); expect(plan.actions).toHaveLength(4);
  expect(plan.max_turns).toBe(4); expect(plan.stop_on_first_failure).toBe(true);
  expect(createHash("sha256").update(JSON.stringify(plan.actions)).digest("hex")).toBe(plan.actions_hash);
  expect(execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim()).toBe(plan.prepared_commit);
  for (const [path, hash] of Object.entries(plan.prepared_files)) {
    expect(createHash("sha256").update(await readFile(path)).digest("hex")).toBe(hash);
  }
  const credentials = JSON.parse(await readFile(plan.credentials_file, "utf8"));
  const db = new DatabaseSync(plan.database, { readOnly: true });
  const calls = () => Number((db.prepare("SELECT COUNT(*) AS n FROM model_call").get() as { n: number }).n);
  expect(calls()).toBe(execute ? plan.starting_call_count ?? 0 : 0);
  if (previousCalls) {
    const previous = new DatabaseSync(plan.previous_database, { readOnly: true });
    try {
      expect(Number((previous.prepare("SELECT COUNT(*) AS n FROM model_call").get() as { n: number }).n)).toBe(previousCalls);
    } finally { previous.close(); }
  }
  if (plan.retry_request) {
    expect(execute).toBe(true);
    expect(plan.retry_request.expected_revision).toBe(startTurn);
    expect(plan.retry_request.action).toBe(plan.actions[startTurn]);
    const account = db.prepare("SELECT id FROM identity_account WHERE kind='PLAYER' AND login=?").get(credentials.email) as { id: string };
    expect(account).toBeTruthy();
    // 只恢复原失败 POST 的本地待确认缓存；不写后端状态或伪造模型结果，仍点真实重试按钮。
    await page.addInitScript(({ namespace, session }) => {
      if (sessionStorage.getItem("item-use-retry-restored")) return;
      localStorage.setItem(`${namespace}:session`, JSON.stringify(session));
      localStorage.setItem(`${namespace}:stage`, "game");
      sessionStorage.setItem("item-use-retry-restored", "1");
    }, { namespace: `wenxian:runtime:account:${account.id}`,
      session: { token: "", saveId: plan.save_id, draft: plan.retry_request.action, pending: plan.retry_request } });
  }
  let base = plan.save_id ? `/api/runtime/v1/account/saves/${plan.save_id}` : "";
  let step = -1, posts = 0;
  let submitted: { request_id: string; expected_revision: number; action: string } | null = null;
  const evidence = async (name: string, value: unknown) => writeFile(`${plan.evidence_dir}/${plan.evidence_prefix ?? ""}${name}.json`,
    JSON.stringify(value, null, 2), { flag: "wx", mode: 0o600 });
  await page.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (request.method() !== "GET") {
      if (path.endsWith("/actions")) {
        expect(execute).toBe(true); expect(path).toBe(`${base}/actions`);
        const body = request.postDataJSON();
        expect(step).toBeGreaterThanOrEqual(0); expect(body.action).toBe(plan.actions[step]);
        expect(body.expected_revision).toBe(step); expect(posts).toBe(step - startTurn);
        if (step === startTurn && plan.retry_request) expect(body.request_id).toBe(plan.retry_request.request_id);
        submitted = body; posts++;
      } else {
        const allowed = ["/api/v1/auth/login", "/api/v1/auth/logout",
          ...(!execute ? ["/api/v1/auth/register", "/api/runtime/v1/account/saves"] : [])];
        expect(allowed).toContain(path);
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
  const enter = async () => {
    const button = page.getByRole("button", { name: `进入${plan.character_name}的命卷`, exact: true });
    const game = page.locator('.game-shell[data-stage="game"][data-headerless="true"]');
    await expect.poll(async () => await button.isVisible() || await game.isVisible()).toBe(true);
    if (await button.isVisible()) await button.click();
    await expect(game).toBeVisible();
  };
  const relogin = async () => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.locator("summary").filter({ hasText: "账号" }).click();
    const response = page.waitForResponse(value => value.request().method() === "POST" && new URL(value.url()).pathname === "/api/v1/auth/logout");
    await page.getByRole("button", { name: "退出账号", exact: true }).click();
    expect((await response).status()).toBe(204);
    await login(); await enter();
  };
  try {
    await page.goto("/?runtime=python-account");
    expect(await (await page.request.get("/api/runtime/v1/health")).json()).toMatchObject({ mode: "langchain", profile: "account" });
    const account = db.prepare("SELECT id FROM identity_account WHERE kind='PLAYER' AND login=?").get(credentials.email);
    if (!account && !execute) {
      await page.getByRole("tab", { name: "注册", exact: true }).click();
      await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
      await page.getByLabel("密码", { exact: true }).fill(credentials.password);
      await page.getByLabel("确认密码", { exact: true }).fill(credentials.password);
      const response = page.waitForResponse(value => value.request().method() === "POST" && new URL(value.url()).pathname === "/api/v1/auth/register");
      await page.getByRole("button", { name: "注册并创建命卷", exact: true }).click();
      expect((await response).status()).toBe(200);
    } else { expect(account).toBeTruthy(); await login(); }
    let saves = await (await page.request.get("/api/runtime/v1/account/saves")).json();
    if (!execute && !saves.length) {
      await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
      await page.getByRole("textbox", { name: "角色姓名" }).fill(plan.character_name);
      await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
      await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("先了解护符，再决定是否收下使用");
      await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
      const response = page.waitForResponse(value => value.request().method() === "POST" && new URL(value.url()).pathname === "/api/runtime/v1/account/saves");
      await page.getByRole("button", { name: "确认并开启命卷" }).click();
      const created = await response; expect(created.status()).toBe(201);
      plan.save_id = (await created.json()).snapshot.save_id;
      base = `/api/runtime/v1/account/saves/${plan.save_id}`;
      saves = await (await page.request.get("/api/runtime/v1/account/saves")).json();
    } else {
      if (!execute && !plan.save_id) {
        expect(saves).toHaveLength(1); expect(saves[0].name).toBe(plan.character_name);
        plan.save_id = saves[0].id;
        base = `/api/runtime/v1/account/saves/${plan.save_id}`;
      }
      expect(saves).toHaveLength(1); expect(saves[0].id).toBe(plan.save_id);
      await enter();
    }
    expect(saves).toHaveLength(1);
    if (!execute) await writeFile(manifestPath, JSON.stringify(plan, null, 2), { mode: 0o600 });
    await expect(page.getByRole("heading", { name: plan.resume_scene_title ?? plan.initial_scene_title, exact: true })).toBeVisible();
    const initial = await (await page.request.get(base)).json();
    expect(initial.revision).toBe(startTurn);
    if (!startTurn) {
      expect(initial.state.item_uses).toEqual({});
      expect(initial.history.every((value: { mode: string }) => value.mode === "opening")).toBe(true);
    }
    const binding = db.prepare("SELECT setting_hash,setting_json FROM save_world_setting WHERE save_id=?").get(plan.save_id) as { setting_hash: string; setting_json: string };
    const setting = JSON.parse(binding.setting_json);
    expect(setting.schema_version).toBe("library-setting-2");
    expect(setting.profile.adopted_definitions).toHaveLength(7);
    expect(setting.profile.item_use_rules[0].id).toBe("simple-protection");
    if (!execute) {
      plan.setting_hash = binding.setting_hash;
      await page.getByRole("button", { name: "设置", exact: true }).click();
      await page.getByRole("button", { name: "大字", exact: true }).click();
      await page.reload();
      await expect(page.locator(".game-shell")).toHaveAttribute("data-font-size", "large");
      await relogin();
      expect((await (await page.request.get(base)).json()).state).toEqual(initial.state);
      expect(calls()).toBe(0); expect(posts).toBe(0);
      const credits = await (await page.request.get("/api/v1/account/credits")).json();
      expect(credits.balance).toMatchObject({ granted: 4, spent: 0, reserved: 0, available: 4 });
      await evidence("prepared", { save_id: plan.save_id, setting_hash: plan.setting_hash, snapshot: initial, credits, calls: calls(), posts });
      await writeFile(manifestPath, JSON.stringify(plan, null, 2), { mode: 0o600 });
      return;
    }
    expect(binding.setting_hash).toBe(plan.setting_hash);
    let latest = initial;
    let acquiredId = startTurn >= 2 ? initial.state.items.find((item: { definition?: { key: string } }) => item.definition?.key === "basic-protection-talisman").id : "";
    let useId = startTurn >= 2 ? Object.keys(initial.state.item_uses)[0] : "";
    for (step = startTurn; step < plan.actions.length; step++) {
      const accepted = page.waitForResponse(value => value.request().method() === "POST" && new URL(value.url()).pathname === `${base}/actions`);
      if (step === startTurn && plan.retry_request) {
        await page.getByRole("button", { name: "重试原行动", exact: true }).click();
      } else {
        const input = page.getByRole("textbox", { name: "自由行动" });
        if (!await input.isVisible()) await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
        await input.fill(plan.actions[step]);
        await page.getByRole("button", { name: "确认此行动", exact: true }).click();
      }
      expect((await accepted).status()).toBe(202);
      let status: { status: string } = { status: "UNKNOWN" };
      await expect.poll(async () => {
        status = await (await page.request.get(`${base}/actions/${submitted!.request_id}`)).json();
        return ["COMMITTED", "FAILED"].includes(status.status);
      }, { timeout: 175_000, intervals: [500, 1000, 1500] }).toBe(true);
      latest = await (await page.request.get(base)).json();
      const credits = await (await page.request.get("/api/v1/account/credits")).json();
      const stored = db.prepare("SELECT candidate_json,references_json,generation_audit_json FROM committed_turn WHERE save_id=? AND turn=?")
        .get(plan.save_id, step + 1) as { candidate_json: string; references_json: string; generation_audit_json: string } | undefined;
      const committed = stored ? { candidate: JSON.parse(stored.candidate_json), references: JSON.parse(stored.references_json), audit: JSON.parse(stored.generation_audit_json) } : null;
      await evidence(`real-step-${step + 1}`, { request: submitted, status, snapshot: latest, credits, committed, calls: calls(), posts });
      if (!unlimited) expect(calls()).toBeLessThanOrEqual(plan.authorized_call_limit);
      expect(status.status).toBe("COMMITTED");
      expect(latest.revision).toBe(step + 1); expect(credits.balance).toMatchObject({ spent: step + 1, reserved: 0, available: 3 - step });
      expect(latest.state.abilities).toEqual(initial.state.abilities); expect(latest.state.realm).toBe(initial.state.realm);
      expect(committed!.audit.world_setting_hash).toBe(plan.setting_hash);
      expect(committed!.audit).toMatchObject({ model: plan.model, reasoning_effort: plan.reasoning_effort,
        tool_protocol: plan.tool_protocol, item_use_protocol: "single-protection-1" });
      expect(committed!.candidate.used_definitions).toContainEqual({ category: "items", key: "basic-protection-talisman" });
      if (step === 0) {
        expect(latest.state.items).toEqual(initial.state.items); expect(latest.state.item_uses).toEqual({});
      } else {
        const talismans = latest.state.items.filter((item: { definition?: { key: string } }) => item.definition?.key === "basic-protection-talisman");
        expect(talismans).toHaveLength(1);
        const talisman = talismans[0];
        if (step === 1) {
          acquiredId = talisman.id; expect(talisman.quantity).toBe(1);
          const active = Object.values(latest.state.item_uses) as { id: string; status: string }[];
          expect(active).toHaveLength(1); expect(active[0].status).toBe("active"); useId = active[0].id;
          expect(latest.state.item_origins[acquiredId]).toMatchObject({ turn: 2, kind: "acquire" });
          await page.getByRole("button", { name: "人物", exact: true }).click();
          await page.getByRole("tab", { name: /行囊/ }).click();
          await expect(page.getByText("贴身护光生效中", { exact: false })).toBeVisible();
          await page.getByRole("button", { name: "故事", exact: true }).click();
        } else {
          expect(talisman.id).toBe(acquiredId); expect(talisman.quantity).toBe(0);
          expect(Object.keys(latest.state.item_uses)).toEqual([useId]);
          expect(latest.state.item_uses[useId]).toMatchObject({ status: "ended", end_reason: "dismissed", activated_turn: 2, ended_turn: 3 });
        }
      }
      if (plan.review_each_step) {
        // 先留存真实结果，逐回人工核对正文与授权；未确认不得自动提交下一行动。
        let review: { decision?: string; request_id?: string } = {};
        await expect.poll(async () => {
          try { review = JSON.parse(await readFile(`${plan.evidence_dir}/${plan.evidence_prefix ?? ""}review-step-${step + 1}.json`, "utf8")); }
          catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
          return review.decision;
        }, { timeout: 120_000, intervals: [1000] }).toMatch(/^(continue|stop)$/);
        expect(review.request_id).toBe(submitted!.request_id); expect(review.decision).toBe("continue");
      }
      await page.reload();
      await expect(page.getByText(latest.state.narrative[0], { exact: true })).toBeVisible();
      expect((await (await page.request.get(base)).json()).state).toEqual(latest.state);
      if (step === 1) await relogin();
    }
    await relogin();
    expect((await (await page.request.get(base)).json()).state).toEqual(latest.state);
    expect(posts).toBe(4 - startTurn);
    expect(db.prepare("SELECT setting_hash,setting_json FROM save_world_setting WHERE save_id=?").get(plan.save_id)).toEqual(binding);
    await evidence("recovered", { revision: latest.revision, same_state: true, same_binding: true, posts, calls: calls() });
  } finally { db.close(); }
});
