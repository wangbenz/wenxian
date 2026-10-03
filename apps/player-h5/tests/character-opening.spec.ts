import { expect, test as base, type Page, type Route } from "@playwright/test";
import type { StoryCharacterRoute, StoryCharacterRouteRequest, StoryCreateSave } from "../src/game-api";

// All HTTP APIs are intercepted. These browser fixtures do not contact a database or model.
const bootstrap = {
  releaseVersion: "开场交互测试", maxActiveSaves: 4,
  defaultEngine: { code: "LIGHT_NARRATIVE", version: 1, stateSchemaVersion: 2 }, capabilities: [],
  creation: {
    world: {
      packageId: "XUANXU", revision: 2, name: "玄墟界", premise: "修行由自己的选择继续。",
      coreRules: [], narrativeGuidance: "保留玩家来处。",
      races: [{ raceId: "race:xuanxu:human", name: "人族", description: "人族", identityFacts: [], socialHooks: [] }],
      openingRegions: [{ regionId: "region:xuanxu:qingqi", name: "青岐州", description: "城镇与山门。" }],
      realmScale: { scaleId: "realm-scale:xuanxu", revision: 2, phases: ["EARLY"], ranks: [] },
    },
    playPreferences: {
      focus: [{ code: "EXPLORATION", name: "探索", description: "了解周围。" }, { code: "CULTIVATION", name: "修行", description: "尝试修行。" }],
      combatFrequency: [{ code: "BALANCED", name: "适中", description: "适中" }],
      pace: [{ code: "BALANCED", name: "适中", description: "适中" }],
      guidance: [{ code: "BALANCED", name: "适中", description: "适中" }],
      defaultFocus: ["EXPLORATION"], defaultCombatFrequency: "BALANCED", defaultPace: "BALANCED", defaultGuidance: "BALANCED",
    },
  },
};

function candidate(index: number, finalized = false): StoryCharacterRoute {
  const label = finalized ? "新" : "旧";
  return {
    id: `${label}-route-${index}`, title: finalized ? "依此来历入世" : `雨棚来客${index}`,
    origin: {
      summary: finalized ? "模型整理后的摘要不得覆盖玩家原文" : `你是独自寻访师承的散修${index}`,
      establishedFacts: [{ localRef: "origin:fact:1", type: "PAST_EVENT", statement: `${label}来历中的已知经历` }],
      openHooks: [{ localRef: "origin:hook:1", summary: `${label}来历中尚未解开的事情` }],
      startingEntities: [{ localRef: "origin:entity:1", entityType: "CHARACTER", name: `${label}相识`, summary: `${label}来历里已经相识的人` }],
    },
    opening: {
      regionId: "region:xuanxu:qingqi", situation: `${label}来历对应的雨棚处境`,
      scene: { title: `${label}雨棚开篇${index}`, narrative: `${label}相识挪开一只竹凳，请你避雨。\n\n你还没有答应跟他上山。`, choices: [
        { title: "先问清去处", description: "询问山中可以学些什么。" },
        { title: "留在城里", description: "先在城中找同道交流。" },
      ] },
    },
  };
}

class CreationApi {
  requests: StoryCharacterRouteRequest[] = [];
  creates: StoryCreateSave[] = [];
  unexpected: string[] = [];
  failFinalize = false;
  holdFinalize = false;
  holdRecommend = false;
  held: Route | null = null;
  heldRoutes: StoryCharacterRoute[] = [];
  created: Record<string, unknown> | null = null;

  async release() {
    if (!this.held) throw new Error("No held candidate request");
    const held = this.held;
    this.held = null;
    await held.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ routes: this.heldRoutes }) });
  }

  async handle(route: Route) {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/access-status") return json({ restricted: false });
    if (path === "/api/v1/auth/me") return json({ authenticated: true, newUser: false,
      user: { userId: "opening-account", email: "opening@example.test" }, hasSave: false, passwordConfigured: true, nextStep: "CREATE_CHARACTER" });
    if (path === "/api/v1/story/bootstrap") return json(bootstrap);
    if (path === "/api/v1/release-notes") return json([]);
    if (["/api/v1/release-notes/public-latest", "/api/v1/release-notes/pending"].includes(path)) return json(null);
    if (path === "/api/v1/generation/settings") return json({ mode: "PLATFORM",
      platform: { turnLimit: 10, usedTurns: 0, remainingTurns: 10, available: true },
      selectedPlatformTier: null, personalProviders: [], selectedPersonalProvider: null, selectedPersonalModel: null });
    if (path === "/api/v1/character-names/validate") {
      return json({ valid: true, normalizedName: request.postDataJSON().name.trim(), message: "" });
    }
    if (path === "/api/v1/story/character-routes") {
      const body = request.postDataJSON() as StoryCharacterRouteRequest;
      this.requests.push(body);
      if (body.mode === "FINALIZE") {
        if (this.failFinalize) {
          this.failFinalize = false;
          return route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ message: "开篇暂时未能完成，请重试" }) });
        }
        if (this.holdFinalize) {
          this.holdFinalize = false;
          this.held = route;
          this.heldRoutes = [candidate(1, true)];
          return;
        }
        return json({ routes: [candidate(1, true)] });
      }
      const routes = [candidate(1), candidate(2), candidate(3)];
      if (this.holdRecommend) {
        this.holdRecommend = false;
        this.held = route;
        this.heldRoutes = routes;
        return;
      }
      return json({ routes });
    }
    if (path === "/api/v1/story/saves" && request.method() === "POST") {
      const body = request.postDataJSON() as StoryCreateSave;
      this.creates.push(body);
      this.created = {
        saveId: "created-opening", mode: "PLAYABLE", engineCode: "LIGHT_NARRATIVE", engineVersion: 1,
        stateSchemaVersion: 2, revision: 0, turnNumber: 0, capabilities: [],
        generationMode: "PLATFORM", personalProvider: null, personalModel: null,
        state: {
          saveId: "created-opening", turnNumber: 0, status: "ACTIVE",
          world: { code: "XUANXU", name: "玄墟界", source: "OFFICIAL", premise: "由选择继续。", cultivationStyle: "修行", openingRegion: "青岐州" },
          protagonist: { name: body.identity.name, race: "人族", gender: body.identity.genderCode, origin: body.origin.summary, realm: "炼气初期", lifeGoal: "", storyPreference: "探索" },
          worldTime: { year: 1, day: 1, phase: "MORNING" }, currentLocation: "青岐州", condition: "安好",
          sceneTitle: body.opening.scene!.title, narrative: body.opening.scene!.narrative, pendingDecision: null,
          choices: body.opening.scene!.choices.map((choice, index) => ({ ...choice, id: `choice-${index}` })),
          inventory: [], abilities: [], characters: [], memories: [],
          realm: { scaleId: "realm-scale:xuanxu", scaleRevision: 2, race: "人族", currentStageId: "realm:xuanxu:human:l1", currentStageName: "炼气", currentRankKey: "L1", currentPhase: "EARLY", progressMarks: 0, requiredProgressMarks: 3, stages: [] },
        },
      };
      return json(this.created);
    }
    if (path === "/api/v1/story/saves" && request.method() === "GET") return json([]);
    this.unexpected.push(`${request.method()} ${path}`);
    return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "Unexpected offline test request" }) });
  }
}

const test = base.extend<{ api: CreationApi }>({
  api: async ({ page }, use) => {
    const api = new CreationApi();
    await page.route("**/api/**", (route) => api.handle(route));
    await use(api);
    expect(api.unexpected).toEqual([]);
  },
});

async function begin(page: Page, recommend = true) {
  await page.goto("/");
  await page.getByRole("button", { name: "开启新命卷" }).click();
  await page.getByRole("button", { name: "男", exact: true }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("林渡");
  await page.getByRole("button", { name: /下一步 · 选择入世路线/ }).click();
  if (recommend) {
    await page.getByRole("button", { name: "推演三条角色路线", exact: true }).click();
    await expect(page.getByRole("button", { name: /1 雨棚来客1/ })).toBeVisible();
  }
}

test("untouched recommendation previews and submits the same scene without another generation", async ({ page, api }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await begin(page);
  await page.getByRole("button", { name: /1 雨棚来客1/ }).click();
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toContainText("旧相识挪开一只竹凳");
  expect(api.requests.map((r) => r.mode)).toEqual(["RECOMMEND"]);
  expect(api.creates).toHaveLength(0);
  await page.screenshot({ path: test.info().outputPath("opening-preview.png"), fullPage: true });
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByRole("heading", { name: "旧雨棚开篇1" })).toBeVisible();
  expect(api.creates).toHaveLength(1);
  expect(api.creates[0].opening.scene).toEqual(candidate(1).opening.scene);
  expect(api.creates[0].origin.startingEntities[0].name).toBe("旧相识");
});

test("editing a recommendation finalizes new material while preserving the player's exact origin", async ({ page, api }) => {
  await begin(page);
  await page.getByRole("button", { name: /1 雨棚来客1/ }).click();
  const original = "我出生在山脚，想自行修行，不愿急着拜师。".repeat(9);
  const note = "希望先与散修来往。".repeat(17);
  await page.getByRole("textbox", { name: /人物来处/ }).fill(original);
  await page.getByText("补充游玩偏好", { exact: false }).click();
  await page.getByPlaceholder("例如：希望人物关系自然发展，少一些机械受难。").fill(note);
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toContainText("新相识");
  expect(api.requests.map((r) => r.mode)).toEqual(["RECOMMEND", "FINALIZE"]);
  expect(api.requests[1].roleSeed).toBe(original);
  expect(api.requests[1].preferenceSeed).toContain(note);
  expect(api.requests[1].preferenceSeed!.length).toBeGreaterThan(160);
  expect(api.creates).toHaveLength(0);
  await expect(page.locator(".creation-summary").first()).toContainText(original);
  await expect(page.locator(".creation-summary").first()).not.toContainText("旧相识");
  await page.getByRole("button", { name: "确认并开启命卷" }).click();
  await expect(page.getByRole("heading", { name: "新雨棚开篇1" })).toBeVisible();
  expect(api.creates[0].origin.summary).toBe(original);
  expect(api.creates[0].origin.startingEntities[0].name).toBe("新相识");
  expect(api.creates[0].origin.openHooks[0].summary).toContain("新来历");
});

test("custom origin survives generation failure and requires preview before creation", async ({ page, api }) => {
  await begin(page, false);
  expect(api.requests).toHaveLength(0);
  const original = "我是山村采药人，想弄懂祖父留下的吐纳法。";
  await page.getByRole("textbox", { name: /人物来处/ }).fill(original);
  api.failFinalize = true;
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("textbox", { name: /人物来处/ })).toHaveValue(original);
  await expect(page.getByText("开篇暂时未能完成，请重试", { exact: false }).first()).toBeVisible();
  expect(api.creates).toHaveLength(0);
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toBeVisible();
  expect(api.creates).toHaveLength(0);
  expect(api.requests.map((r) => r.mode)).toEqual(["FINALIZE", "FINALIZE"]);
});

test("a delayed finalized scene cannot replace a newer origin", async ({ page, api }) => {
  await begin(page, false);
  await page.getByRole("textbox", { name: /人物来处/ }).fill("我要去山门求学。");
  api.holdFinalize = true;
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect.poll(() => api.held !== null).toBe(true);
  const newer = "我改变主意，先在城中独自修行。";
  await page.getByRole("textbox", { name: /人物来处/ }).fill(newer);
  await api.release();
  await expect(page.getByRole("button", { name: /下一步 · 核对命卷/ })).toBeEnabled();
  await expect(page.getByRole("region", { name: "开篇预览" })).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: /人物来处/ })).toHaveValue(newer);
  expect(api.creates).toHaveLength(0);
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toBeVisible();
  expect(api.requests.at(-1)?.roleSeed).toBe(newer);
});

test("changing preferences after preview invalidates the scene and preserves the origin", async ({ page, api }) => {
  await begin(page);
  await page.getByRole("button", { name: /1 雨棚来客1/ }).click();
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await page.getByRole("button", { name: "返回修改路线与偏好" }).click();
  await page.getByRole("button", { name: "修行", exact: true }).click();
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toContainText("新相识");
  expect(api.requests.at(-1)?.mode).toBe("FINALIZE");
  expect(api.requests.at(-1)?.preferenceSeed).toContain("修行");
  expect(api.creates).toHaveLength(0);
});

test("changing identity prepares a new scene without silently generating another three routes", async ({ page, api }) => {
  await begin(page);
  await page.getByRole("button", { name: /1 雨棚来客1/ }).click();
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await page.getByRole("button", { name: "返回修改路线与偏好" }).click();
  await page.getByRole("button", { name: "返回修改身份" }).click();
  await page.getByRole("textbox", { name: "角色姓名" }).fill("顾宁");
  await page.getByRole("button", { name: /下一步 · 选择入世路线/ }).click();
  expect(api.requests).toHaveLength(1);
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toContainText("新相识");
  expect(api.requests.map((r) => r.mode)).toEqual(["RECOMMEND", "FINALIZE"]);
  expect(api.requests[1].identity.name).toBe("顾宁");
  expect(api.creates).toHaveLength(0);
});

test("a delayed recommendation is discarded when the player starts writing their own origin", async ({ page, api }) => {
  await begin(page, false);
  api.holdRecommend = true;
  await page.getByRole("button", { name: "推演三条角色路线", exact: true }).click();
  await expect.poll(() => api.held !== null).toBe(true);
  const original = "我独自来城中找失散的师兄。";
  await page.getByRole("textbox", { name: /人物来处/ }).fill(original);
  await api.release();
  await expect(page.getByRole("button", { name: /下一步 · 核对命卷/ })).toBeEnabled();
  await expect(page.getByRole("button", { name: /1 雨棚来客1/ })).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: /人物来处/ })).toHaveValue(original);
  await page.getByRole("button", { name: /下一步 · 核对命卷/ }).click();
  await expect(page.getByRole("region", { name: "开篇预览" })).toBeVisible();
  expect(api.requests.at(-1)?.roleSeed).toBe(original);
  expect(api.creates).toHaveLength(0);
});
