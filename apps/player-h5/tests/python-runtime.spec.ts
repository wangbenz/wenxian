import { expect, test, type Page, type Route } from "@playwright/test";
import type { ActionStatus, PendingAction, RuntimeSnapshot } from "../src/runtime/runtime-api";

const ACTION = "先向掌柜问清核货要求，暂不答应食宿交易。";
const STORE = "wenxian:runtime:local-test:session";
function initial(): RuntimeSnapshot {
  return {
    save_id: "python-fixture-save", revision: 0, mode: "fixture", history: [],
    state: {
      name: "林默", goal: "在青溪镇落脚", realm: "炼气初期", condition: "安好", location: "青木坊门前",
      scene_title: "青木坊的来货", narrative: ["掌柜正在核对来货，等着你的回应。"],
      time: { year: 1, day: 1, phase: "MORNING" }, choices: [{ id: "ask", label: "先问清条件", hint: "暂不答应交易" }],
      items: [{ id: "clothes", name: "随身衣物", category: "随身物", status: "完好", quantity: 1, description: "随身衣物" }],
      abilities: [{ id: "breathing", name: "基础吐纳法", kind: "TECHNIQUE", stage: "初学", source: "试玩开局", description: "已掌握基础吐纳。" }],
      characters: [],
    },
  };
}
class MockRuntime {
  snapshot = initial();
  sessions = new Map<string, RuntimeSnapshot>();
  creations: { token: string; body: Record<string, string> }[] = [];
  dropCreation = false;
  summaryOffline = false;
  referenceOffline = false;
  detailOffline = false;
  emptyCategory: string | null = null;
  referenceRequests: string[] = [];
  posts: PendingAction[] = [];
  status: ActionStatus["status"] = "PROCESSING";
  commitOnPost = false;
  dropAfterCommit = false;
  queryOffline = false;
  legacyCalls: string[] = [];
  commit() {
    this.status = "COMMITTED";
    this.snapshot.revision += 1;
    this.snapshot.state.scene_title = "本次行动的模拟回应";
    this.snapshot.state.narrative = ["模拟回应已保存；你仍在青木坊门前。"];
    this.snapshot.history.push({ turn: this.snapshot.revision, action: this.posts.at(-1)?.action ?? ACTION, mode: "fixture", result: { scene_title: this.snapshot.state.scene_title, narrative: this.snapshot.state.narrative } });
  }
  async handle(route: Route) {
    const path = new URL(route.request().url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (!path.startsWith("/api/runtime/")) { this.legacyCalls.push(path); return json({ error: "unexpected legacy call" }, 500); }
    const token = route.request().headers().authorization?.replace("Bearer ", "") ?? "";
    if (path.includes("/reference")) {
      this.referenceRequests.push(route.request().url());
      if (path.endsWith("/reference")) return json({ world: { title: "青溪镇", scope: "青木坊本地试玩", summary: "这段故事从青木坊门前开始。", sections: [{ title: "经历与物品", text: "查阅不会获得物品或学会功法。" }] }, categories: [{ id: "realms", name: "修行境界" }, { id: "arts", name: "功法与术法" }, { id: "items", name: "物品" }, { id: "place-types", name: "场所" }, { id: "facility-types", name: "设施" }, { id: "recipes", name: "配方" }] });
      const match = path.match(/reference\/entries\/([^/]+)(?:\/([^/]+))?$/)!;
      if (match[2]) {
        if (this.detailOffline) return json({ error: { message: "资料离线" } }, 503);
        return json({ key: match[2], name: match[2] === "entry-0" ? "简式护身符" : `纸条${match[2].replace("entry-", "")}`, kind: "定义", example: false, description: "一次护持", revision: 2, scope: "save", sections: [{ title: "用途与用法", text: "形成贴身护光。" }, { title: "限制", text: "不能保证挡住强力兵刃。" }], sources: [{ title: "项目设定", locator: "公开资料", supports: "具体用法", url: "" }] });
      }
      if (this.referenceOffline) return json({ error: { message: "目录离线" } }, 503);
      const url = new URL(route.request().url()); const query = url.searchParams.get("q"); const offset = Number(url.searchParams.get("offset") ?? 0);
      let rows = Array.from({ length: 13 }, (_, index) => ({ key: `entry-${index}`, name: match[1] === "items" ? index === 0 ? "简式护身符" : `纸条${index}` : `境界条目${index}`, kind: "定义", example: index > 0, description: "公共资料说明" }));
      if (match[1] === this.emptyCategory) rows = [];
      if (query) rows = rows.filter(row => row.name.includes(query));
      return json({ count: rows.length, limit: 12, offset, results: rows.slice(offset, offset + 12) });
    }
    if (path.endsWith("/creation")) return json({ opening_id: "qingmu-trial", setting_hash: "a".repeat(64), title: "青木坊的来货", narrative: initial().state.narrative, realm: "炼气初期", condition: "安好", location: "青木坊门前", items: ["随身衣物"], abilities: ["基础吐纳法"] });
    if (path.endsWith("/sessions")) {
      const body = route.request().postDataJSON();
      this.creations.push({ token, body });
      if (!this.sessions.has(token)) {
        const snapshot = initial(); snapshot.save_id = `created-${this.sessions.size + 1}`;
        Object.assign(snapshot.state, body); this.sessions.set(token, snapshot);
      }
      this.snapshot = this.sessions.get(token)!;
      if (this.dropCreation) return route.abort("connectionfailed");
      return json({ snapshot: this.snapshot }, 201);
    }
    if (path.endsWith("/save/summary")) {
      if (this.summaryOffline && token === "synthetic-runtime-token") return route.abort("connectionfailed");
      const saved = this.sessions.get(token) ?? this.snapshot;
      return json({ id: saved.save_id, name: saved.state.name, realm: saved.state.realm, location: saved.state.location, revision: saved.revision, updated_at: "2026-10-11T00:00:00Z" });
    }
    if (path.endsWith("/save")) { this.snapshot = this.sessions.get(token) ?? this.snapshot; return json(this.snapshot); }
    if (path.endsWith("/actions")) {
      this.posts.push(route.request().postDataJSON());
      if (this.commitOnPost) this.commit();
      if (this.dropAfterCommit) return route.abort("connectionfailed");
      return json({ status: this.status }, 202);
    }
    if (path.includes("/actions/")) {
      if (this.queryOffline) return route.abort("connectionfailed");
      return json({ status: this.status, error: this.status === "FAILED" ? { code: "GENERATION_FAILED", message: "测试续写中断，输入已保留。" } : null });
    }
    throw new Error(`Unexpected path: ${path}`);
  }
}
async function setup(page: Page, mock: MockRuntime) {
  await page.route("**/api/**", route => mock.handle(route));
  await page.addInitScript(key => {
    if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({ token: "synthetic-runtime-token", saveId: "python-fixture-save", draft: "", pending: null }));
  }, STORE);
  mock.sessions.set("synthetic-runtime-token", mock.snapshot);
  await page.goto("/?runtime=python");
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
}
async function createRole(page: Page, name = "林默", goal = "在青溪镇落脚") {
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill(name);
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill(goal);
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
}
async function openSaves(page: Page) {
  const input = page.getByRole("textbox", { name: "自由行动" });
  if (await input.isVisible()) await input.press("Tab");
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByText("命卷", { exact: true }).click();
  await page.getByRole("button", { name: "我的命卷", exact: true }).click();
}
async function writeAction(page: Page, content = ACTION) {
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await page.getByRole("textbox", { name: "自由行动" }).fill(content);
  await page.getByRole("button", { name: "确认此行动" }).click();
}

test("提交即显示等待，刷新恢复原请求，查看人物后继续", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock); await writeAction(page);
  await expect(page.getByLabel("已确认的行动")).toContainText(ACTION);
  await expect(page.getByText("正在等待这一回的结果")).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("已确认的行动")).toContainText(ACTION);
  expect(mock.posts).toHaveLength(1);
  mock.commit();
  await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await expect(page.getByText("林默", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: /行囊/ }).click();
  await expect(page.getByText("随身衣物", { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "故事", exact: true }).click();
  await page.reload();
  await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
  mock.commitOnPost = true;
  await writeAction(page, "接着问送货人这批药材的来路。");
  await expect.poll(() => mock.posts.length).toBe(2);
  expect(mock.posts[1].expected_revision).toBe(1);
  expect(mock.legacyCalls).toEqual([]);
});

test("提交响应丢失后查询已保存结果，不重发行动", async ({ page }) => {
  const mock = new MockRuntime(); mock.commitOnPost = true; mock.dropAfterCommit = true;
  await setup(page, mock); await writeAction(page);
  await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
  expect(mock.posts).toHaveLength(1);
  await page.reload();
  await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
  expect(mock.posts).toHaveLength(1);
});

test("失败保留输入，刷新与原样重试沿用请求号", async ({ page }) => {
  const mock = new MockRuntime(); mock.status = "FAILED";
  await setup(page, mock); await writeAction(page);
  await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue(ACTION);
  await page.reload();
  await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue(ACTION);
  mock.commitOnPost = true;
  await page.getByRole("button", { name: "重试原行动" }).click();
  await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
  expect(mock.posts[0]).toEqual(mock.posts[1]);
  expect(mock.snapshot.revision).toBe(1);
});

test("结果不明时只允许恢复原行动，不覆盖为新请求", async ({ page }) => {
  const mock = new MockRuntime(); mock.queryOffline = true;
  await setup(page, mock); await writeAction(page);
  await expect(page.getByRole("button", { name: "重试原行动" })).toBeVisible();
  await expect(page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" })).toBeDisabled();
  mock.queryOffline = false; mock.commitOnPost = true;
  await page.getByRole("button", { name: "重试原行动" }).click();
  await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
  expect(mock.posts[0]).toEqual(mock.posts[1]);
});

test("本地真实 API、资料库及原 H5 连续两次保存", async ({ page }) => {
  test.skip(process.env.RUNTIME_LIVE_TEST !== "1", "需要显式启动隔离 fixture 服务，不调用真实模型");
  await page.goto("/?runtime=python");
  await createRole(page);
  await expect(page.getByRole("note")).toContainText("本地模拟联调");
  await writeAction(page);
  await expect(page.getByRole("heading", { name: "模拟联调回应" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "模拟联调回应" })).toBeVisible();
  await writeAction(page, "继续测试第二次行动，先询问送货人。");
  await expect(page.getByText(/本地联调已收到你的行动：继续测试第二次行动/)).toBeVisible();
  // 只对新的合成测试会话调用当前 API，不打印 token。
  const local = await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), STORE);
  const response = await page.request.get("/api/runtime/v1/save", { headers: { Authorization: `Bearer ${local.token}` } });
  const saved = await response.json();
  expect(saved.revision).toBe(2);
  expect(saved.state.items).toHaveLength(1);
  expect(saved.state.definition_pins["arts:basic-breathing"].revision).toBeGreaterThan(0);
  expect(saved.history[1].references[0].content_hash).toMatch(/^[a-f0-9]{64}$/);
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByRole("button", { name: "查看世界资料", exact: false }).click();
  await page.getByRole("button", { name: "物品", exact: true }).click();
  await page.getByRole("button", { name: /简式护身符/ }).click();
  await expect(page.getByRole("heading", { name: "用途与用法", exact: true })).toBeVisible();
  await expect(page.getByText("资料仅供查阅，不改变角色的持有", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "故事", exact: true }).click();
  await openSaves(page);
  await createRole(page, "顾川", "探望友人");
  await openSaves(page);
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await expect(page.getByText(/本地联调已收到你的行动：继续测试第二次行动/)).toBeVisible();
});

test("复用原设置版式，字号跨页面和刷新保留", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await expect(page.locator(".settings-view .settings-reading-section")).toBeVisible();
  await page.getByRole("button", { name: "大字", exact: true }).click();
  await expect(page.getByRole("button", { name: "大字", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "故事", exact: true }).click();
  await expect(page.locator(".game-shell")).toHaveAttribute("data-font-size", "large");
  await page.reload();
  await expect(page.locator(".game-shell")).toHaveAttribute("data-font-size", "large");
  expect(mock.legacyCalls).toEqual([]);
});

test("推荐行动确认前不提交，确认时保留补充说明及授权边界", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  await page.getByRole("button", { name: "先问清条件。暂不答应交易", exact: true }).click();
  expect(mock.posts).toHaveLength(0);
  await page.getByRole("button", { name: "确认行动", exact: true }).click();
  await expect(page.getByLabel("已确认的行动")).toContainText("先问清条件：暂不答应交易");
  await expect.poll(() => mock.posts.length).toBe(1);
  expect(mock.posts[0].action).toBe("先问清条件：暂不答应交易");
});

test("自由行动草稿可清空，切换推荐行动与刷新仍保留", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  const toggle = page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" });
  await toggle.click();
  const input = page.getByRole("textbox", { name: "自由行动" });
  await input.fill("待清空的草稿");
  await page.getByRole("button", { name: "清空输入", exact: true }).click();
  await expect(input).toHaveValue("");
  await input.fill(ACTION);
  await page.getByRole("button", { name: "先问清条件。暂不答应交易", exact: true }).click();
  await toggle.click();
  await expect(input).toHaveValue(ACTION);
  await page.reload();
  await toggle.click();
  await expect(input).toHaveValue(ACTION);
  expect(mock.posts).toHaveLength(0);
});


test("首次先选命卷，创角草稿和步骤刷新恢复，必填字段阻止空开卷", async ({ page }) => {
  const mock = new MockRuntime(); await page.route("**/api/**", route => mock.handle(route));
  await page.goto("/?runtime=python");
  await expect(page.locator(".save-selection-view")).toBeVisible();
  expect(mock.creations).toHaveLength(0);
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await expect(page.getByRole("textbox", { name: "角色姓名" })).toBeFocused();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("沈青");
  await page.getByRole("button", { name: "女", exact: true }).click();
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await expect(page.getByRole("textbox", { name: "当前目标", exact: true })).toBeFocused();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("寻找失散的师姐");
  await page.getByRole("textbox", { name: "人物来处", exact: true }).fill("从山村出发");
  await page.getByRole("textbox", { name: "叙事偏好", exact: true }).fill("多些人物交流");
  await page.reload();
  await expect(page.getByRole("textbox", { name: "当前目标", exact: true })).toHaveValue("寻找失散的师姐");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await expect(page.getByLabel("开篇预览")).toContainText("基础吐纳法");
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await expect(page.locator(".character-record-view")).toContainText("沈青");
  await expect(page.locator(".character-record-view")).toContainText("寻找失散的师姐");
  await page.reload();
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await expect(page.locator(".character-record-view")).toContainText("从山村出发");
  expect(mock.creations).toHaveLength(1); expect(mock.posts).toHaveLength(0); expect(mock.legacyCalls).toEqual([]);
});

test("创角回应丢失，刷新后同凭据同资料找回，不重复开卷", async ({ page }) => {
  const mock = new MockRuntime(); mock.dropCreation = true;
  await page.route("**/api/**", route => mock.handle(route)); await page.goto("/?runtime=python");
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("顾川");
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("拜访友人");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await page.getByRole("button", { name: "确认并开启命卷" }).dblclick();
  await expect(page.getByRole("button", { name: "找回已确认命卷" })).toBeVisible();
  await expect(page.getByRole("button", { name: "返回修改来处与偏好" })).toBeDisabled();
  await page.reload(); mock.dropCreation = false;
  await page.getByRole("button", { name: "找回已确认命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  expect(mock.creations).toHaveLength(2); expect(mock.creations[0]).toEqual(mock.creations[1]); expect(mock.sessions.size).toBe(1);
});

test("两卷各自保留草稿，列表和人物取已保存资料，刷新后可切回", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  const toggle = page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" });
  await toggle.click(); await page.getByRole("textbox", { name: "自由行动" }).fill("林默的未提交行动");
  await openSaves(page); await createRole(page, "沈青", "寻找师姐");
  await toggle.click(); await page.getByRole("textbox", { name: "自由行动" }).fill("沈青的未提交行动");
  await openSaves(page); await page.reload();
  await expect(page.locator(".save-selection-view")).toContainText("沈青");
  await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await toggle.click(); await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue("林默的未提交行动");
  await openSaves(page); await page.getByRole("button", { name: "进入沈青的命卷" }).click();
  await toggle.click(); await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue("沈青的未提交行动");
  expect(mock.posts).toHaveLength(0);
});

test("待确认行动切到另一卷再返回，恢复原请求且不重复提交", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock); await writeAction(page);
  await openSaves(page); await createRole(page, "沈青", "寻找师姐");
  await expect(page.getByLabel("已确认的行动")).toHaveCount(0);
  await openSaves(page); await page.getByRole("button", { name: "进入林默的命卷" }).click();
  await expect(page.getByLabel("已确认的行动")).toContainText(ACTION);
  expect(mock.posts).toHaveLength(1);
  mock.commit(); await expect(page.getByRole("heading", { name: "本次行动的模拟回应" })).toBeVisible();
});

test("单卷读取失败不阻止其他卷，旧凭据不被清除", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock); await openSaves(page); await createRole(page, "沈青", "寻找师姐");
  mock.summaryOffline = true; await openSaves(page);
  await expect(page.getByText("部分命卷暂时无法读取", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "继续沈青的命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  expect(await page.evaluate(() => Object.keys(JSON.parse(localStorage.getItem("wenxian:runtime:local-test:sessions")!)).length)).toBe(2);
});

test("一卷仍在读取时可进入已读取的另一卷，不显示猜测回合", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock); await openSaves(page); await createRole(page, "沈青", "寻找师姐");
  let release!: () => void;
  const delayed = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/save/summary", async route => {
    if (route.request().headers().authorization === "Bearer synthetic-runtime-token") await delayed;
    await mock.handle(route);
  });
  await openSaves(page);
  await expect(page.getByRole("button", { name: "继续沈青的命卷" })).toBeEnabled();
  await expect(page.locator(".save-volume-row").filter({ hasText: "暂未读取" })).not.toContainText("第 0 回");
  await page.getByRole("button", { name: "继续沈青的命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  release();
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await expect(page.locator(".character-record-view")).toContainText("沈青");
});

test("创角资料被明确拒绝后可修改，不把失败资料锁成已确认命卷", async ({ page }) => {
  const mock = new MockRuntime(); await page.route("**/api/**", route => mock.handle(route)); await page.goto("/?runtime=python");
  await page.getByRole("button", { name: "开启新命卷", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("顾川");
  await page.getByRole("button", { name: "下一步 · 选择来处" }).click();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("拜访友人");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await page.route("**/sessions", route => route.fulfill({ status: 422, contentType: "application/json", body: JSON.stringify({ detail: "synthetic validation rejection" }) }));
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByRole("button", { name: "返回修改来处与偏好" })).toBeEnabled();
  await page.getByRole("button", { name: "返回修改来处与偏好" }).click();
  await page.getByRole("textbox", { name: "当前目标", exact: true }).fill("先在镇上落脚");
  await page.unroute("**/sessions");
  await page.getByRole("button", { name: "下一步 · 核对命卷" }).click();
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByRole("heading", { name: "青木坊的来货" })).toBeVisible();
  expect(mock.sessions.size).toBe(1);
});


test("分类菜单、世界规则与详情刷新，不改变行囊或行动草稿", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  const before = JSON.stringify(mock.snapshot);
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await page.getByRole("textbox", { name: "自由行动" }).fill("查完资料再决定下一步");
  await page.getByRole("textbox", { name: "自由行动" }).press("Tab");
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByRole("button", { name: "查看世界资料", exact: false }).click();
  await expect(page.getByRole("heading", { name: "世界资料", exact: true })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "资料分类", exact: true }).getByRole("button")).toHaveCount(7);
  await expect(page.getByRole("searchbox")).toHaveCount(0);
  await expect(page.getByRole("combobox")).toHaveCount(0);
  expect(mock.referenceRequests.filter(url => url.includes("/entries/"))).toHaveLength(0);
  await page.getByRole("button", { name: /世界与规则/ }).click();
  await expect(page.getByRole("heading", { name: "青溪镇", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "物品", exact: true }).click();
  await page.getByRole("button", { name: /简式护身符/ }).click();
  await expect(page.getByRole("heading", { name: "简式护身符", exact: true })).toBeVisible();
  await expect(page.getByText("本局采用的资料 · 第 2 版")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "简式护身符", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "返回资料目录" }).click();
  await expect(page.getByRole("heading", { name: "物品", exact: true })).toBeFocused();
  await expect(page.getByRole("button", { name: "物品", exact: true })).toHaveAttribute("aria-current", "page");
  await page.getByRole("button", { name: "物品", exact: true }).click();
  await expect(page.getByRole("button", { name: /简式护身符/ })).toBeVisible();
  await page.getByRole("button", { name: "返回设置" }).click();
  await expect(page).not.toHaveURL(/reference=/);
  await page.getByRole("button", { name: "故事", exact: true }).click();
  await page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步" }).click();
  await expect(page.getByRole("textbox", { name: "自由行动" })).toHaveValue("查完资料再决定下一步");
  expect(mock.referenceRequests.every(url => !new URL(url).searchParams.has("q"))).toBe(true);
  expect(JSON.stringify(mock.snapshot)).toBe(before); expect(mock.posts).toHaveLength(0);
});

test("分类目录分页往返和刷新、空分类及读取失败可恢复", async ({ page }) => {
  const mock = new MockRuntime(); mock.emptyCategory = "facility-types"; await setup(page, mock);
  await page.getByRole("button", { name: "设置", exact: true }).click(); await page.getByRole("button", { name: "查看世界资料", exact: false }).click();
  await page.getByRole("button", { name: "物品", exact: true }).click();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("button", { name: /纸条12/ })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: /纸条12/ })).toBeVisible();
  await page.getByRole("button", { name: /纸条12/ }).click();
  await expect(page.getByRole("heading", { name: "纸条12", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "返回资料目录" }).click();
  await expect(page.getByRole("navigation", { name: "资料翻页" })).toContainText("第 2 / 2 页");
  await page.getByRole("button", { name: "设施", exact: true }).click();
  await expect(page.getByText("此类资料尚未收录", { exact: true })).toBeVisible();
  mock.referenceOffline = true; await page.getByRole("button", { name: "功法与术法", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("命卷和故事不受影响");
  mock.referenceOffline = false; await page.getByRole("button", { name: "重新读取", exact: true }).click();
  await expect(page.getByRole("button", { name: /境界条目0/ })).toBeVisible();
  await page.getByRole("button", { name: "物品", exact: true }).click();
  mock.detailOffline = true; await page.getByRole("button", { name: /简式护身符/ }).click();
  await expect(page.getByRole("alert")).toContainText("仍可返回目录或设置");
  mock.detailOffline = false; await page.getByRole("button", { name: "重新读取", exact: true }).click();
  await expect(page.getByRole("heading", { name: "简式护身符", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "返回资料目录" }).click(); await page.getByRole("button", { name: "返回设置" }).click();
  await expect(page.getByRole("heading", { name: "设置", exact: true })).toBeVisible(); expect(mock.posts).toHaveLength(0);
});

test("切换资料分类后旧请求晚到不会覆盖当前目录", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  let release!: () => void; const delayed = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/reference/entries/realms?**", async route => { await delayed; await mock.handle(route); });
  await page.getByRole("button", { name: "设置", exact: true }).click(); await page.getByRole("button", { name: "查看世界资料", exact: false }).click();
  await page.getByRole("button", { name: "修行境界", exact: true }).click(); await page.getByRole("button", { name: "物品", exact: true }).click();
  await expect(page.getByRole("button", { name: /简式护身符/ })).toBeVisible(); release();
  await expect(page.getByRole("button", { name: "物品", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("button", { name: /境界条目/ })).toHaveCount(0);
});

test("旧搜索链接回到完整分类，大字号菜单在窄屏和桌面均可查阅", async ({ page }) => {
  const mock = new MockRuntime(); await setup(page, mock);
  await page.goto("/?runtime=python&reference=items&refQuery=护身&refOffset=12");
  await expect(page.getByRole("button", { name: /纸条12/ })).toBeVisible();
  await expect(page).not.toHaveURL(/refQuery/);
  await page.getByRole("button", { name: "返回设置" }).click();
  await page.getByRole("button", { name: "大字", exact: true }).click();
  await page.getByRole("button", { name: "查看世界资料", exact: false }).click();
  for (const width of [320, 390, 1100]) {
    await page.setViewportSize({ width, height: 844 });
    const categories = page.getByRole("navigation", { name: "资料分类", exact: true }).getByRole("button");
    await expect(categories).toHaveCount(7);
    const bounds = await categories.evaluateAll(elements => elements.map(element => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, right: rect.right, width: rect.width, height: rect.height, clipped: element.scrollWidth > element.clientWidth + 1 };
    }));
    expect(bounds.every(rect => rect.left >= 0 && rect.right <= width && rect.width >= 44 && rect.height >= 44 && !rect.clipped)).toBe(true);
    await page.getByRole("button", { name: "物品", exact: true }).click();
    await page.getByRole("button", { name: /简式护身符/ }).click();
    const body = page.locator(".reference-section p").first();
    await expect(body).toHaveCSS("font-size", "24px");
    expect(await body.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    await page.getByRole("button", { name: "物品", exact: true }).click();
    await expect(page.getByRole("button", { name: /简式护身符/ })).toBeVisible();
  }
  expect(mock.posts).toHaveLength(0);
  expect(mock.referenceRequests.every(url => !new URL(url).searchParams.has("q"))).toBe(true);
});
