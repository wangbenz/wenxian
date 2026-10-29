import { expect, test, type Page, type Route } from "@playwright/test";
import type { RuntimeSnapshot, SuggestionRequest } from "../src/runtime/runtime-api";

function snapshot(id: string, name = "林默"): RuntimeSnapshot {
  return { save_id: id, revision: 0, mode: "fixture", history: [], state: {
    name, goal: "问清条件", realm: "炼气初期", condition: "安好", location: "青木坊门前",
    scene_title: "青木坊的来货", narrative: ["掌柜正在核对来货，等着你的回应。"],
    time: { year: 1, day: 1, phase: "MORNING" }, choices: [], items: [], abilities: [], characters: [],
  } };
}

class AccountBackend {
  current = "";
  saves = new Map([["a", [snapshot("save-a")]], ["b", [snapshot("save-b", "顾川")]]]);
  creationRequests: string[] = [];
  creationSettingHashes: string[] = [];
  settingHash = "a".repeat(64);
  previewOffline = false;
  actionPosts = 0;
  passwordPosts = 0;
  dropCreation = false;
  expireOnAction = false;
  configFails = false;
  refuseAction = false;
  credentialWrites = 0;
  credentialConfigured = false;
  personalEnabled = false;
  unauthorizedBearer = false;
  suggestionRequests: SuggestionRequest[] = [];
  holdSuggestions = false;
  failSuggestions = false;
  dropSuggestions = false;
  private view() { return { authenticated: Boolean(this.current), user: this.current ? { userId: this.current, email: `${this.current}***@example.test` } : null, hasSave: Boolean(this.saves.get(this.current)?.length), passwordConfigured: Boolean(this.current), newUser: false, nextStep: "RESUME_GAME" }; }
  async handle(route: Route) {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(data) });
    if (path === "/api/v1/release-notes/public-latest" || path === "/api/v1/release-notes/pending") return json(null);
    if (route.request().headers().authorization) this.unauthorizedBearer = true;
    if (path.endsWith("/auth/me")) return json(this.view());
    if (path.endsWith("/auth/access-status")) return json({ restricted: false, title: "", message: "", resumeAt: "" });
    if (path.endsWith("/auth/login")) { this.current = route.request().postDataJSON().email.split("@")[0]; return json(this.view()); }
    if (path.endsWith("/auth/logout")) { this.current = ""; return route.fulfill({ status: 204 }); }
    if (path.endsWith("/auth/password")) { this.passwordPosts++; return json(this.view()); }
    if (path.endsWith("/creation")) return this.previewOffline
      ? json({ code: "LIBRARY_UNAVAILABLE", message: "开篇资料暂不可用" }, 503)
      : json({ opening_id: "qingmu-trial", setting_hash: this.settingHash, title: "青木坊的来货", narrative: ["站在青木坊门前。", "掌柜正招呼来客。"], setting_summary: "资料采用与改编的内部说明", realm: "炼气初期", location: "青木坊门前", condition: "安好", items: [], abilities: [] });
    if (!this.current) return json({ code: "UNAUTHORIZED", message: "请重新登录。" }, 401);
    if (path === "/api/runtime/v1/account/creation/suggestions" && method === "POST") {
      const body = route.request().postDataJSON() as SuggestionRequest;
      if (!this.suggestionRequests.some(row => row.request_id === body.request_id)) this.suggestionRequests.push(body);
      if (this.dropSuggestions) { this.dropSuggestions = false; return route.abort("connectionreset"); }
      return json({ request_id: body.request_id, kind: body.kind, status: "PROCESSING", result: null, error: null }, 202);
    }
    if (path.startsWith("/api/runtime/v1/account/creation/suggestions/") && method === "GET") {
      const body = this.suggestionRequests.find(row => path.endsWith("/" + row.request_id));
      if (!body) return json({ code: "SUGGESTION_NOT_FOUND", message: "未找到候选" }, 404);
      const result = body.kind === "names" ? { names: ["沈青", "陆遥", "林默", "顾宁"] }
        : { paths: [{ title: "寻处落脚", origin: "从附近山村来到青溪镇。", goal: "先向掌柜问清食宿条件" },
                   { title: "出门见闻", origin: "来自江边小镇，初次远行。", goal: "先了解镇上的药材生意" },
                   { title: "静心修行", origin: "离开乡间，打算安静修行。", goal: "先寻安静的落脚处" }] };
      return json({ request_id: body.request_id, kind: body.kind,
        status: this.failSuggestions ? "FAILED" : this.holdSuggestions ? "PROCESSING" : "COMPLETED",
        result: this.failSuggestions || this.holdSuggestions ? null : result,
        error: this.failSuggestions ? { code: "SUGGESTION_FAILED", message: "这批候选未能生成，填写内容已保留。" } : null });
    }
    const generation = () => ({ mode: this.credentialConfigured ? "PERSONAL" : "PLATFORM", platform: { turnLimit: 0, usedTurns: 0, remainingTurns: 0, available: false }, personalProviders: this.personalEnabled ? [{ code: "EXAMPLE", name: "测试服务", credentialConfigured: this.credentialConfigured, credentialStatus: this.credentialConfigured ? "SAVED" : null, maskedSuffix: null, lastVerifiedAt: null, models: [{ code: "GENERAL", name: "通用" }] }] : [], selectedPlatformTier: null, selectedPersonalProvider: this.credentialConfigured ? "EXAMPLE" : null, selectedPersonalModel: this.credentialConfigured ? "GENERAL" : null });
    if (path.endsWith("/generation/settings")) return json(generation());
    if (path.endsWith("/generation/providers/EXAMPLE/credential")) { this.credentialWrites++; this.credentialConfigured = method === "PUT"; return json(generation()); }
    if (path.endsWith("/account/config")) return json({ maximum_saves: 3 }, this.configFails ? 503 : 200);
    const own = this.saves.get(this.current)!;
    if (path.endsWith("/account/saves")) {
      if (method === "GET") return json(own.map(row => ({ id: row.save_id, name: row.state.name, realm: row.state.realm, location: row.state.location, revision: row.revision, updated_at: "2026-10-21T00:00:00Z" })));
      const body = route.request().postDataJSON(); this.creationRequests.push(body.request_id);
      this.creationSettingHashes.push(body.character.world_setting_hash);
      let value = own.find(row => row.save_id === body.request_id);
      if (!value && body.character.world_setting_hash !== this.settingHash) return json({ code: "SETTING_CHANGED", message: "开篇采用的资料已有更新，请重新读取并确认，填写资料已保留。" }, 409);
      if (!value) { value = snapshot(body.request_id, body.character.name); own.push(value); }
      return this.dropCreation ? route.abort("connectionfailed") : json({ snapshot: value }, 201);
    }
    const match = path.match(/\/account\/saves\/([^/]+)(.*)$/);
    if (!match) throw new Error(`Unexpected account endpoint ${path}`);
    const save = own.find(row => row.save_id === match[1]);
    if (!save) return json({ code: "NOT_FOUND", message: "没有找到命卷。" }, 404);
    if (!match[2]) return json(save);
    if (match[2] === "/actions") {
      this.actionPosts++;
      if (this.refuseAction) return json({ code: "TURN_LIMIT", error: { code: "TURN_LIMIT", message: "可用推演点不足，原行动已保留。" } }, 409);
      if (this.expireOnAction) { this.current = ""; return json({ code: "UNAUTHORIZED", message: "请重新登录。" }, 401); }
      save.revision++;
      save.state.scene_title = "行动已保存";
      return json({ status: "COMMITTED" }, 202);
    }
    if (match[2].startsWith("/actions/")) return json({ status: "UNKNOWN" });
    if (match[2] === "/reference") return json({ world: { title: "青溪镇", scope: "本局背景", summary: "青木坊", sections: [] }, categories: [] });
    throw new Error(`Unexpected account endpoint ${path}`);
  }
}

async function login(page: Page, name = "a") {
  await expect(page.getByLabel("邮箱", { exact: true })).toBeVisible();
  await page.getByLabel("邮箱", { exact: true }).fill(`${name}@example.test`);
  await page.getByLabel("密码", { exact: true }).fill("isolated-test-password");
  await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
}
async function settings(page: Page) {
  const input = page.getByRole("textbox", { name: "自由行动" });
  if (await input.isVisible()) await input.press("Tab");
  await page.getByRole("button", { name: "设置", exact: true }).click();
}
async function logout(page: Page) {
  await settings(page);
  await page.locator("summary").filter({ hasText: "账号" }).click();
  await page.getByRole("button", { name: "退出账号", exact: true }).click();
  await expect(page.getByLabel("邮箱", { exact: true })).toBeVisible();
}

test("账号登录复用原页，跨账号草稿隔离，退出后重新登录恢复自己的草稿", async ({ page }) => {
  const backend = new AccountBackend(); await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await page.getByRole("textbox", { name: "自由行动" }).fill("甲账号未提交的草稿");
  await logout(page); await login(page, "b");
  await expect(page.getByRole("button", { name: "进入林默的命卷" })).toHaveCount(0);
  await page.getByRole("button", { name: "进入顾川的命卷" }).click();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue("");
  await logout(page); await login(page);
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue("甲账号未提交的草稿");
  expect(backend.unauthorizedBearer).toBe(false);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem("wenxian:runtime:account:a:session")!).token)).toBe("");
});

test("账号开卷响应丢失后刷新，保持同一开卷编号与资料", async ({ page }) => {
  const backend = new AccountBackend(); backend.dropCreation = true;
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("沈青");
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByText("自己填写", { exact: true }).click();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("寻找师姐");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect.poll(() => backend.creationRequests.length).toBe(1);
  backend.dropCreation = false; backend.previewOffline = true; await page.reload();
  await page.getByRole("button", { name: "找回已确认命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  expect(backend.creationRequests).toHaveLength(2);
  expect(backend.creationRequests[0]).toBe(backend.creationRequests[1]);
  expect(backend.saves.get("a")).toHaveLength(2);
  expect(backend.creationSettingHashes).toEqual(["a".repeat(64), "a".repeat(64)]);
});

test("开卷设定变化后保留资料，重新确认新版，不创建半份命卷", async ({ page }) => {
  const backend = new AccountBackend();
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("沈青");
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByText("自己填写", { exact: true }).click();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("寻找师姐");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  backend.settingHash = "b".repeat(64);
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByText("开篇采用的资料已有更新，请重新读取并确认，填写资料已保留。", { exact: true })).toBeVisible();
  expect(backend.saves.get("a")).toHaveLength(1);
  await expect(page.getByRole("region", { name: "沈青", exact: true })).toContainText("沈青");
  await expect(page.getByRole("region", { name: "沈青", exact: true })).toContainText("寻找师姐");
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  expect(backend.creationSettingHashes).toEqual(["a".repeat(64), "b".repeat(64)]);
  expect(backend.actionPosts).toBe(0);
});

test("账号过期不丢行动，重新登录先查询原请求，不自动重发", async ({ page }) => {
  const backend = new AccountBackend(); backend.expireOnAction = true;
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await page.getByRole("textbox", { name: "自由行动" }).fill("原行动必须保留");
  await page.getByRole("button", { name: "确认此行动" }).click();
  await login(page);
  await expect(page.getByText("原行动：原行动必须保留", { exact: true })).toBeVisible();
  expect(backend.actionPosts).toBe(1);
});

test("命卷配置读取失败仍可打开已有卷，原密码和字号设置继续可用", async ({ page }) => {
  const backend = new AccountBackend(); backend.configFails = true;
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await expect(page.getByRole("button", { name: "开启新命卷", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await settings(page); await page.getByRole("button", { name: "大字", exact: true }).click();
  await page.locator("summary").filter({ hasText: "账号" }).click();
  await page.getByRole("button", { name: "修改密码", exact: true }).click();
  await page.getByLabel("当前密码", { exact: true }).fill("isolated-test-password");
  await page.getByLabel("新密码", { exact: true }).fill("isolated-new-password");
  await page.getByLabel("确认新密码", { exact: true }).fill("isolated-new-password");
  await page.getByRole("button", { name: "保存新密码并继续" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(backend.passwordPosts).toBe(1);
  await page.reload();
  await expect(page.locator(".game-shell")).toHaveAttribute("data-font-size", "large");
});

test("实际玩家后端登录、恢复命卷、保存模拟行动与重新登录", async ({ page }) => {
  test.skip(process.env.ACCOUNT_LIVE_TEST !== "1", "只在显式配置的独立账号 fixture 服务执行");
  const { readFile } = await import("node:fs/promises");
  const credentials = JSON.parse(await readFile(process.env.ACCOUNT_LIVE_CREDENTIALS!, "utf8"));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account");
  await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
  await page.getByLabel("密码", { exact: true }).fill(credentials.password);
  await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  const action = `账号联调 ${Date.now()}：确认保存后恢复同一命卷。`;
  await page.getByRole("textbox", { name: "自由行动" }).fill(action);
  await page.getByRole("button", { name: "确认此行动" }).click();
  await expect(page.getByText(`本地联调已收到你的行动：${action}`, { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "模拟联调回应" })).toBeVisible();
  const interrupted = `账号联调 ${Date.now()}：处理中刷新后找回同一行动。`;
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await page.getByRole("textbox", { name: "自由行动" }).fill(interrupted);
  const accepted = page.waitForResponse(response => response.request().method() === "POST" && response.url().endsWith("/actions") && response.status() === 202);
  await page.getByRole("button", { name: "确认此行动" }).click();
  await accepted;
  await page.reload();
  await expect(page.getByText(`本地联调已收到你的行动：${interrupted}`, { exact: true })).toBeVisible();
  await logout(page);
  await page.getByLabel("邮箱", { exact: true }).fill(credentials.email);
  await page.getByLabel("密码", { exact: true }).fill(credentials.password);
  await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "模拟联调回应" })).toBeVisible();
});

test("玩家密钥设置明确延后验证，保存和移除都不触发剧情请求或浏览器持久化密钥", async ({ page }) => {
  const backend = new AccountBackend(); backend.personalEnabled = true;
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await settings(page); await page.getByRole("button", { name: "续写设置", exact: true }).click();
  await expect(page.getByText("保存密钥不会发起请求", { exact: false })).toBeVisible();
  const synthetic = "synthetic-browser-test-key-value";
  await page.getByLabel("测试服务 API Key", { exact: true }).fill(synthetic);
  await page.getByRole("button", { name: "保存并启用", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(backend.credentialWrites).toBe(1); expect(backend.actionPosts).toBe(0);
  expect(await page.evaluate(value => Object.values(localStorage).some(row => row.includes(value)), synthetic)).toBe(false);
  await page.getByRole("button", { name: "续写设置", exact: true }).click();
  await page.getByRole("button", { name: "移除密钥", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(backend.credentialWrites).toBe(2); expect(backend.actionPosts).toBe(0);
});

test("账号公告复用原弹窗，关闭写入回执后刷新不再提示", async ({ page }) => {
  const backend = new AccountBackend();
  const note = { id: "local-note", version: "local", title: "账号联调公告", content: "仅用于测试。", publishedAt: "2020-01-01T00:00:00Z", unread: true };
  let read = false;
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/release-notes/public-latest") return route.fulfill({ json: note });
    if (path === "/api/v1/release-notes/pending") return route.fulfill({ json: read ? null : note });
    if (path === "/api/v1/release-notes/local-note/read") { read = true; return route.fulfill({ status: 204 }); }
    return backend.handle(route);
  });
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "知道了，继续此世" }).click();
  await expect.poll(() => read).toBe(true);
  await page.reload();
  await expect(page.getByRole("button", { name: "进入林默的命卷" })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("明确因点数不足拒绝后保留可编辑草稿，不把未受理请求锁成结果不明", async ({ page }) => {
  const backend = new AccountBackend(); backend.refuseAction = true;
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account"); await login(page);
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await page.getByRole("textbox", { name: "自由行动" }).fill("暂不提交其他安排");
  await page.getByRole("button", { name: "确认此行动" }).click();
  await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue("暂不提交其他安排");
  await expect(page.getByRole("textbox", { name: "自由行动" })).toBeEditable();
  await expect(page.getByText("可用推演点不足，原行动已保留。", { exact: true })).toBeVisible();
  expect(backend.actionPosts).toBe(1);
});

// 以下仅检验候选交互与恢复边界；实际 DeepSeek 结果另在独立服务验证。
async function openCreation(page: Page, backend: AccountBackend) {
  backend.saves.set("a", []);
  await page.route("**/api/**", route => backend.handle(route));
  await page.goto(process.env.LOCAL_DEFAULT_TEST === "1" ? "/" : "/?runtime=python-account");
  await login(page);
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
}

test("创角姓名先提供候选，选用才填入；来历候选也要点选，不提前创建命卷", async ({ page }) => {
  const backend = new AccountBackend(); await openCreation(page, backend);
  await page.getByRole("button", { name: "生成姓名", exact: true }).click();
  const names = page.locator('[aria-label="备选姓名"]');
  await expect(names.getByRole("button")).toHaveCount(4);
  await expect(page.getByRole("textbox", { name: "角色姓名" })).toHaveValue("");
  await names.getByRole("button", { name: "沈青", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "角色姓名" })).toHaveValue("沈青");
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByRole("button", { name: "生成来历与目标", exact: true }).click();
  const paths = page.getByRole("tablist", { name: "来历候选" });
  const leaves = paths.getByRole("tab");
  const panel = page.getByRole("tabpanel");
  await expect(leaves).toHaveCount(3);
  await expect(page.getByRole("textbox", { name: "当前目标", exact: true })).toBeHidden();
  expect(backend.creationRequests).toHaveLength(0);
  await leaves.first().click();
  await leaves.first().press("End");
  await expect(leaves.nth(2)).toBeFocused();
  await leaves.nth(2).press("Home");
  await leaves.first().press("ArrowRight");
  await expect(leaves.nth(1)).toBeFocused();
  await expect(leaves.nth(1)).toHaveAttribute("aria-selected", "true");
  await expect(panel).toContainText("来自江边小镇，初次远行。");
  expect(await panel.locator("p, dd").evaluateAll(nodes => nodes.every(node => node.scrollWidth <= node.clientWidth && node.scrollHeight <= node.clientHeight + 1))).toBe(true);
  await expect(page.getByRole("button", { name: "下一步 · 核对命卷" })).toBeDisabled();
  await page.getByRole("button", { name: "选用此来历", exact: true }).click();
  await expect(leaves.nth(1)).toHaveAttribute("data-chosen", "true");
  await page.getByText("修改来历与目标", { exact: true }).click();
  await expect(page.getByRole("textbox", { name: "当前目标", exact: true })).toHaveValue("先了解镇上的药材生意");
  await expect(page.getByRole("textbox", { name: "人物来处", exact: true })).toHaveValue("来自江边小镇，初次远行。");
  await page.getByRole("button", { name: "人物交流", exact: true }).click();
  await expect(leaves).toHaveCount(3);
  await expect(leaves.nth(1)).toHaveAttribute("data-chosen", "true");
  await page.reload();
  await expect(leaves.nth(1)).toHaveAttribute("data-chosen", "true");
  await expect(leaves.nth(1)).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("button", { name: "人物交流", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.getByText("修改来历与目标", { exact: true }).click();
  await expect(page.getByRole("textbox", { name: "当前目标", exact: true })).toHaveValue("先了解镇上的药材生意");
  await page.getByText("补充偏好", { exact: true }).click();
  await expect(page.getByRole("textbox", { name: "叙事偏好", exact: true })).toHaveValue("多一些人物交流，让关系自然发展。");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await expect(page.getByRole("heading", { name: "故事开篇" })).toBeFocused();
  const preview = page.getByLabel("开篇预览");
  await expect(preview).toContainText("站在青木坊门前。");
  await expect(page.getByText("掌柜正招呼来客。", { exact: true })).toBeHidden();
  await preview.locator("summary").click();
  await expect(page.getByText("掌柜正招呼来客。", { exact: true })).toBeVisible();
  await expect(page.getByText("资料采用与改编的内部说明", { exact: true })).toHaveCount(0);
  expect(backend.suggestionRequests).toHaveLength(2); expect(backend.creationRequests).toHaveLength(0);
});

test("生成时仍可手填，迟到候选不覆盖修改后的姓名", async ({ page }) => {
  const backend = new AccountBackend(); backend.holdSuggestions = true; await openCreation(page, backend);
  await page.getByRole("button", { name: "生成姓名", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("姓名候选");
  await page.getByRole("textbox", { name: "角色姓名" }).fill("亲写姓名");
  backend.holdSuggestions = false;
  await expect(page.getByText("资料已修改，旧候选未采用；可以按当前内容重新生成。", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "角色姓名" })).toHaveValue("亲写姓名");
  await expect(page.locator('[aria-label="备选姓名"]')).toHaveCount(0);
  expect(backend.suggestionRequests).toHaveLength(1);
});

test("候选请求响应丢失后刷新只查原编号，不重复生成", async ({ page }) => {
  const backend = new AccountBackend(); backend.dropSuggestions = true; await openCreation(page, backend);
  await page.getByRole("button", { name: "生成姓名", exact: true }).click();
  await expect(page.getByRole("button", { name: "继续生成候选", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.locator('[aria-label="备选姓名"]').getByRole("button")).toHaveCount(4);
  expect(backend.suggestionRequests).toHaveLength(1);
});

test("候选失败保留原输入，允许手填继续；明确重试才生成新一批", async ({ page }) => {
  const backend = new AccountBackend(); backend.failSuggestions = true; await openCreation(page, backend);
  await page.getByRole("textbox", { name: "角色姓名" }).fill("自填姓名");
  await page.getByRole("button", { name: "生成姓名", exact: true }).click();
  await expect(page.getByText("这批候选未能生成，填写内容已保留。", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "角色姓名" })).toHaveValue("自填姓名");
  expect(backend.suggestionRequests).toHaveLength(1);
  backend.failSuggestions = false;
  await page.getByRole("button", { name: "生成姓名", exact: true }).click();
  await expect(page.locator('[aria-label="备选姓名"]').getByRole("button")).toHaveCount(4);
  expect(backend.suggestionRequests).toHaveLength(2);
  expect(backend.suggestionRequests[0].request_id).not.toBe(backend.suggestionRequests[1].request_id);
});
