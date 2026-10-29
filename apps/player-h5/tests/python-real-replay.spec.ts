import { expect, test } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";

/** 显式受控真实验收：无模拟响应，只允许发送冻结首回一次，不因失败自动重跑。 */
test("真实首回从原 H5 恢复原请求，生成成功后刷新读取同一结果", async ({ page, request }) => {
  test.skip(!process.env.REAL_REPLAY_MANIFEST, "仅在明确模型、冻结输入和同批预算后显式执行");
  const plan = JSON.parse(await readFile(process.env.REAL_REPLAY_MANIFEST!, "utf8"));
  const credentials = JSON.parse(await readFile(plan.session_file, "utf8"));
  const body = JSON.parse(await readFile(plan.request_file, "utf8")).request;
  expect(body.request_id).toBe("continuity-20261020-01"); expect(body.expected_revision).toBe(0);
  expect(plan.model).toBe("deepseek-flash"); expect(plan.reasoning_effort).toBe("none");
  const unlimited = plan.call_budget_mode === "unlimited";
  if (unlimited) {
    expect(plan.authorization.state).toBe("authorized"); expect(plan.total_limit).toBeNull();
  } else {
    expect(Number.isSafeInteger(plan.total_limit)).toBe(true); expect(plan.total_limit).toBeGreaterThan(0);
  }
  const db = new DatabaseSync(plan.database, { readOnly: true });
  const count = () => Number((db.prepare("SELECT COUNT(*) AS n FROM model_call").get() as { n: number }).n);
  expect(count()).toBe(plan.calls_before);
  if (!unlimited) expect(count()).toBeLessThan(plan.total_limit);
  const headers = { Authorization: `Bearer ${credentials.token}` };
  const base = plan.api_url + "/api/runtime/v1";
  const health = await request.get(base + "/health"); expect((await health.json()).mode).toBe("langchain");
  const before = await (await request.get(base + "/save", { headers })).json();
  expect(before.revision).toBe(0); expect(before.history.every((row: { mode: string }) => row.mode === "opening")).toBe(true);
  const frozen = db.prepare("SELECT action,expected_revision FROM action_request WHERE request_id=?").get(body.request_id) as { action: string; expected_revision: number };
  expect(frozen.action).toBe(body.action); expect(frozen.expected_revision).toBe(body.expected_revision);
  let posts = 0;
  await page.route("**/api/**", async route => {
    if (route.request().method() !== "GET") {
      if (!route.request().url().endsWith("/api/runtime/v1/actions")) return route.abort();
      expect(route.request().postDataJSON()).toEqual(body);
      posts++; expect(posts).toBe(1);
    }
    await route.continue(); // 始终连接真实后端；这里仅限制写入范围，不伪造任何响应。
  });
  await page.addInitScript(({ token, saveId, pending }) => {
    if (localStorage.getItem("wenxian:real-check-initialized")) return;
    localStorage.setItem("wenxian:runtime:local-test:session", JSON.stringify({ token, saveId, draft: pending.action, pending }));
    localStorage.setItem("wenxian:runtime:local-test:stage", "game");
    localStorage.setItem("wenxian:real-check-initialized", "yes");
  }, { token: credentials.token, saveId: credentials.save_id, pending: body });
  try {
    await page.goto("/?runtime=python");
    const accepted = page.waitForResponse(response => response.request().method() === "POST"
      && response.url().endsWith("/api/runtime/v1/actions"));
    await page.getByRole("button", { name: "重试原行动", exact: true }).click();
    expect((await accepted).status()).toBe(202);
    let status: { status: string; error?: unknown } = { status: "UNKNOWN" };
    await expect.poll(async () => {
      status = await (await request.get(base + "/actions/" + body.request_id, { headers })).json();
      return ["COMMITTED", "FAILED"].includes(status.status);
    }, { timeout: 175_000, intervals: [500, 1000, 1500] }).toBe(true);
    const after = await (await request.get(base + "/save", { headers })).json();
    await writeFile(plan.output, JSON.stringify({ posts, status, revision: after.revision, calls_before: plan.calls_before,
      calls_after: count(), snapshot: after }, null, 2), { flag: "wx", mode: 0o600 });
    if (!unlimited) expect(count()).toBeLessThanOrEqual(plan.total_limit);
    expect(status.status).toBe("COMMITTED");
    await expect(page.getByText(after.state.narrative[0], { exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByText(after.state.narrative[0], { exact: true })).toBeVisible();
    expect(posts).toBe(1);
  } finally { db.close(); }
});
