import { expect, test as base, type Page, type Route } from "@playwright/test";
import type { AuthResponse, StoryBootstrap, StoryGenerationSettings, StorySaveResponse, StoryState } from "../src/game-api";

// Exercise the real PlayerApp and HTTP adapters. Every API request is intercepted;
// these fixtures contain no account credentials and never reach a backend/model.
const FIRST_SAVE = "recovery-save-one";
const SECOND_SAVE = "recovery-save-two";
const OTHER_ACCOUNT_SAVE = "recovery-save-other";
const ACTION = "我停在渡口旁，先问清船家的姓名，再决定是否登船。";
const FIRST_EMAIL = "recovery-one@example.test";
const SECOND_EMAIL = "recovery-two@example.test";

type SaveWire = Omit<StorySaveResponse, "state"> & {
  saveId: string; mode: "PLAYABLE"; engineCode: string; engineVersion: number;
  stateSchemaVersion: number; turnNumber: number; capabilities: string[];
  state: Omit<StoryState, "schemaVersion" | "protagonist"> & {
    protagonist: Omit<StoryState["protagonist"], "storyTone"> & { storyPreference: string };
  };
};
type TurnSubmission = { action: string; expectedRevision: number; requestId: string; choiceId?: string };
type StatusMode = "OFFLINE" | "UNKNOWN" | "PROCESSING" | "FAILED" | "COMMITTED";

const bootstrap: Omit<StoryBootstrap, "version"> & {
  releaseVersion: string; defaultEngine: { code: string; version: number; stateSchemaVersion: number };
} = {
  releaseVersion: "恢复交互测试", maxActiveSaves: 4,
  defaultEngine: { code: "LIGHT_NARRATIVE", version: 1, stateSchemaVersion: 2 }, capabilities: [],
  creation: {
    world: {
      packageId: "XUANXU", revision: 2, name: "玄墟界", premise: "人物的选择会留下连续的经历。",
      coreRules: [], narrativeGuidance: "尊重已经确认的事实。",
      races: [{ raceId: "race:xuanxu:human", name: "人族", description: "人族", identityFacts: [], socialHooks: [] }],
      openingRegions: [{ regionId: "region:xuanxu:qingqi", name: "青岐", description: "渡口与城镇。" }],
      realmScale: { scaleId: "realm-scale:xuanxu", revision: 2, phases: ["EARLY", "MIDDLE", "LATE", "PERFECT"], ranks: [] },
    },
    playPreferences: {
      focus: [{ code: "EXPLORATION", name: "探索", description: "观察和了解周围。" }],
      combatFrequency: [{ code: "BALANCED", name: "适中", description: "适中" }],
      pace: [{ code: "BALANCED", name: "适中", description: "适中" }],
      guidance: [{ code: "BALANCED", name: "适中", description: "适中" }],
      defaultFocus: ["EXPLORATION"], defaultCombatFrequency: "BALANCED", defaultPace: "BALANCED", defaultGuidance: "BALANCED",
    },
  },
};

function makeSave(saveId: string, playerName: string): SaveWire {
  return {
    saveId, mode: "PLAYABLE", engineCode: "LIGHT_NARRATIVE", engineVersion: 1,
    stateSchemaVersion: 2, revision: 3, turnNumber: 2, capabilities: [],
    generationMode: "PLATFORM", personalProvider: null, personalModel: null,
    state: {
      saveId, turnNumber: 2, status: "ACTIVE",
      world: { code: "XUANXU", name: "玄墟界", source: "OFFICIAL", premise: "人物的选择会留下连续的经历。", cultivationStyle: "亲历之后才能成长。", openingRegion: "青岐" },
      protagonist: { name: playerName, race: "人族", gender: "UNSPECIFIED", origin: "独自远行的修士", realm: "炼气境初期", lifeGoal: "探索", storyPreference: "循序推进" },
      worldTime: { year: 1, day: 2, phase: "MORNING" }, currentLocation: "青岐渡口", condition: "安好",
      sceneTitle: `${playerName}的渡口`, narrative: "晨雾刚刚散开，船家正在岸边整理缆绳。", pendingDecision: null,
      choices: [{ id: "choice-observe", title: "观察渡口", description: "先看看周围的动静。" }],
      inventory: [], abilities: [], characters: [], memories: [],
      realm: { scaleId: "realm-scale:xuanxu", scaleRevision: 2, race: "人族", currentStageId: "realm:xuanxu:human:l1", currentStageName: "炼气境", currentRankKey: "L1", currentPhase: "EARLY", progressMarks: 0, requiredProgressMarks: 3, stages: [] },
    },
  };
}

class RecoveryApi {
  email = FIRST_EMAIL;
  status: StatusMode = "OFFLINE";
  statusHttpError: number | null = null;
  turnErrorCode: string | null = null;
  commitOnPost = false;
  failNextSaveLists = 0;
  failedSaveLists = 0;
  omitCommittedSnapshot = false;
  failNextSaveDetails = 0;
  failedSaveDetails = 0;
  holdNextStatusResponse = false;
  heldStatusResponse: { route: Route; body: string } | null = null;
  posts: Array<{ saveId: string; body: TurnSubmission }> = [];
  queries: Array<{ saveId: string; requestId: string }> = [];
  statusErrors: number[] = [];
  unexpected: string[] = [];
  saves = new Map([
    [FIRST_SAVE, makeSave(FIRST_SAVE, "沈照")],
    [SECOND_SAVE, makeSave(SECOND_SAVE, "林渡")],
    [OTHER_ACCOUNT_SAVE, makeSave(OTHER_ACCOUNT_SAVE, "顾宁")],
  ]);

  commit(saveId = FIRST_SAVE) {
    const save = this.saves.get(saveId)!;
    save.revision += 1;
    save.turnNumber += 1;
    save.state.turnNumber = save.turnNumber;
    save.state.sceneTitle = "船家的回答";
    save.state.narrative = "船家放下缆绳，报上姓名；你仍留在岸边。";
    this.status = "COMMITTED";
  }

  async handle(route: Route) {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/access-status") return json({ restricted: false, title: "", message: "", resumeAt: "" });
    if (path === "/api/v1/auth/me" || path === "/api/v1/auth/login" && request.method() === "POST") {
      const auth: AuthResponse = { authenticated: true, newUser: false, user: { userId: this.email === FIRST_EMAIL ? "account-one" : "account-two", email: this.email }, hasSave: true, passwordConfigured: true, nextStep: "RESUME_GAME" };
      return json(auth);
    }
    if (path === "/api/v1/story/bootstrap") return json(bootstrap);
    if (path === "/api/v1/release-notes") return json([]);
    if (["/api/v1/release-notes/public-latest", "/api/v1/release-notes/pending"].includes(path)) return json(null);
    if (path === "/api/v1/generation/settings") {
      const generation: StoryGenerationSettings = { mode: "PLATFORM", platform: { turnLimit: 20, usedTurns: 2, remainingTurns: 18, available: true }, selectedPlatformTier: null, personalProviders: [], selectedPersonalProvider: null, selectedPersonalModel: null };
      return json(generation);
    }
    if (path === "/api/v1/story/saves" && request.method() === "GET") {
      if (this.failNextSaveLists > 0) {
        this.failNextSaveLists -= 1;
        this.failedSaveLists += 1;
        return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "命卷列表暂时不可用" }) });
      }
      const ownedIds = this.email === FIRST_EMAIL ? [FIRST_SAVE, SECOND_SAVE] : [OTHER_ACCOUNT_SAVE];
      return json(ownedIds.map((id, index) => {
        const save = this.saves.get(id)!;
        return { ...save, state: undefined, slotNo: index + 1, worldName: "玄墟界", playerName: save.state.protagonist.name, race: "HUMAN", realm: "炼气境初期", status: "ACTIVE", updatedAt: "2026-09-06T00:00:00Z" };
      }));
    }
    const statusMatch = path.match(/^\/api\/v1\/story\/saves\/([^/]+)\/turn-requests\/([^/]+)$/);
    if (statusMatch && request.method() === "GET") {
      const [, saveId, requestId] = statusMatch;
      this.queries.push({ saveId, requestId });
      if (this.statusHttpError != null) {
        this.statusErrors.push(this.statusHttpError);
        return route.fulfill({ status: this.statusHttpError, contentType: "application/json", body: JSON.stringify({ message: "暂时无法访问原行动", code: "RECOVERY_ACCESS_UNAVAILABLE" }) });
      }
      if (this.status === "OFFLINE") return route.abort("connectionrefused");
      const response = { requestId, status: this.status, terminal: ["FAILED", "COMMITTED"].includes(this.status), retryable: this.status !== "COMMITTED", retryAfterMs: 1200, message: this.status === "FAILED" ? "本次推演没有写入命卷，可以重新提交行动。" : "正在确认原行动。", updatedAt: "2026-09-06T00:00:00Z", snapshot: this.status === "COMMITTED" && !this.omitCommittedSnapshot ? this.saves.get(saveId) : null };
      if (this.holdNextStatusResponse) {
        this.holdNextStatusResponse = false;
        this.heldStatusResponse = { route, body: JSON.stringify(response) };
        return;
      }
      return json(response);
    }
    const turnMatch = path.match(/^\/api\/v1\/story\/saves\/([^/]+)\/turns$/);
    if (turnMatch && request.method() === "POST") {
      this.posts.push({ saveId: turnMatch[1], body: request.postDataJSON() as TurnSubmission });
      if (this.turnErrorCode) return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ message: "原行动仍在处理中", code: this.turnErrorCode }) });
      if (!this.commitOnPost) return route.abort("connectionrefused");
      this.commit(turnMatch[1]);
      return json(this.saves.get(turnMatch[1]));
    }
    const saveMatch = path.match(/^\/api\/v1\/story\/saves\/([^/]+)$/);
    if (saveMatch && request.method() === "GET" && this.saves.has(saveMatch[1])) {
      if (this.failNextSaveDetails > 0) {
        this.failNextSaveDetails -= 1;
        this.failedSaveDetails += 1;
        return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "命卷详情暂时不可用" }) });
      }
      return json(this.saves.get(saveMatch[1]));
    }
    this.unexpected.push(`${request.method()} ${path}`);
    return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "Unexpected offline test request" }) });
  }
}

const test = base.extend<{ api: RecoveryApi }>({
  api: async ({ page }, use) => {
    const api = new RecoveryApi();
    await page.route("**/api/**", (route) => api.handle(route));
    await use(api);
    expect(api.unexpected).toEqual([]);
  },
});

async function openSave(page: Page, name = "沈照") {
  await page.getByRole("button", { name: `进入${name}的命卷`, exact: true }).click();
  await expect(page.getByRole("button", { name: "故事", exact: true })).toBeVisible();
}

async function composer(page: Page) {
  const toggle = page.getByRole("button", { name: "展开自定义行动，不采用推荐选项时，亲自描述下一步", exact: true });
  await expect(toggle).toBeVisible();
  if (await toggle.getAttribute("aria-expanded") === "false") await toggle.click();
  const input = page.getByRole("textbox", { name: "自由行动", exact: true });
  await expect(input).toBeVisible();
  return input;
}

async function storagePeer(page: Page) {
  await page.evaluate(() => {
    window.addEventListener("storage", (event) => {
      document.documentElement.dataset.observedStorageKey = event.key ?? "";
    });
  });
  const peer = await page.context().newPage();
  await peer.route("**/tests/recovery-storage-peer", (route) => route.fulfill({
    contentType: "text/html", body: "<!doctype html><title>Offline recovery storage peer</title>",
  }));
  await peer.goto(new URL("/tests/recovery-storage-peer", page.url()).toString());
  return peer;
}

async function submitWhileOffline(page: Page, api: RecoveryApi) {
  await page.goto("/");
  await openSave(page);
  await (await composer(page)).fill(ACTION);
  await page.getByRole("button", { name: "确认此行动", exact: true }).click();
  await expect.poll(() => api.posts.length).toBe(1);
  expect(api.posts[0].body).toMatchObject({ action: ACTION, expectedRevision: 3 });
  expect(api.posts[0].body.requestId).toBeTruthy();
  await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "继续查询", exact: true })).toBeEnabled();
}

test("ability learning state survives a fresh save read and is shown in Chinese", async ({ page, api }) => {
  const save = api.saves.get(FIRST_SAVE)!;
  save.state.abilities = [{ id: "breathing", name: "调息口诀", type: "TECHNIQUE",
    description: "已有指点，仍需练习。", status: "ACTIVE", abilityState: "KNOWN", source: "已确认传授" }];
  await page.goto("/");
  await openSave(page);
  await page.getByRole("button", { name: "人物", exact: true }).click();
  const record = page.locator(".ability-row").filter({ hasText: "调息口诀" });
  await expect(record).toContainText("功法 · 已知");

  save.state.abilities[0].abilityState = "LEARNING";
  await page.reload();
  await openSave(page);
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await expect(record).toContainText("功法 · 学习中");
  await expect(record).not.toContainText("ACTIVE");
  expect(save.state.abilities[0].id).toBe("breathing");
});

test("a single ability exposes its full learning limitations and remains readable after returning and reloading", async ({ page, api }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const limitation = "快速行走时仍易失去节奏，需要停步重新调息。";
  const description = "已能在静坐时放松肩背，察觉呼吸的急促，逐步找回平稳的节奏。练习中曾因急着引气而胸口发紧，停下调整后缓解。"
    + "目前能独立完成一段缓慢吐纳，但尚不能在步伐和注意力同时变化时维持同样的顺畅。先前的练习只确认了安静环境下的体会，不能据此认定已经熟练掌握。"
    + limitation;
  api.saves.get(FIRST_SAVE)!.state.abilities = [{ id: "breathing", name: "调息口诀", type: "TECHNIQUE",
    description, status: "ACTIVE", abilityState: "LEARNING", source: "已确认传授" }];
  await page.goto("/");
  await openSave(page);
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await page.getByRole("button", { name: "查看完整记录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "天赋与功法", exact: true })).toBeVisible();

  const fullDescription = page.locator(".character-library-list .ability-row p");
  const assertReadableLimitation = async () => {
    await expect(fullDescription).toHaveText(description);
    await fullDescription.scrollIntoViewIfNeeded();
    // 检查尾句的实际排版坐标和裁剪边界；仅 toContainText 不能发现 line-clamp 隐藏内容。
    await expect.poll(() => fullDescription.evaluate((element, tail) => {
      const text = element.firstChild;
      if (!text || text.nodeType !== Node.TEXT_NODE) return false;
      const range = document.createRange();
      range.setStart(text, (text.textContent?.length ?? 0) - tail.length);
      range.setEnd(text, text.textContent?.length ?? 0);
      const tailBox = range.getBoundingClientRect();
      const paragraphBox = element.getBoundingClientRect();
      const scrollBox = element.closest(".game-scroll, .mobile-scroll")?.getBoundingClientRect();
      return tailBox.height > 0 && tailBox.bottom <= paragraphBox.bottom + 1
        && tailBox.top >= Math.max(0, scrollBox?.top ?? 0)
        && tailBox.bottom <= Math.min(window.innerHeight, scrollBox?.bottom ?? window.innerHeight)
        && element.scrollHeight <= element.clientHeight + 1;
    }, limitation)).toBe(true);
    await expect(page.locator(".ability-row")).toContainText("功法 · 学习中");
  };
  await assertReadableLimitation();
  await page.locator(".character-library-back").click();
  await expect(page.getByRole("heading", { name: "人物", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "查看完整记录", exact: true })).toBeVisible();

  await page.reload();
  await openSave(page);
  await page.getByRole("button", { name: "人物", exact: true }).click();
  await page.getByRole("button", { name: "查看完整记录", exact: true }).click();
  await assertReadableLimitation();
});

test("ability learning compatibility never guesses ACTIVE and respects trusted loss", async ({ page, api }) => {
  const baseAbility = { type: "TECHNIQUE", description: "能力记录", source: "既有记录" };
  api.saves.get(FIRST_SAVE)!.state.abilities = [
    { ...baseAbility, id: "old-mastered", name: "旧档掌握记录", status: "MASTERED" },
    { ...baseAbility, id: "old-active", name: "旧档通用状态", status: "ACTIVE" },
    { ...baseAbility, id: "unknown", name: "未明学习状态", status: "MASTERED", abilityState: "UNKNOWN" },
    { ...baseAbility, id: "blocked", name: "暂时受阻口诀", status: "LOST", abilityState: "TEMPORARILY_BLOCKED" },
    { ...baseAbility, id: "lost", name: "已经失去的口诀", status: "ACTIVE", abilityState: "LOST" },
    { ...baseAbility, id: "old-lost", name: "旧档失去口诀", status: "LOST" },
  ];
  await page.goto("/");
  await openSave(page);
  await page.getByRole("button", { name: "人物", exact: true }).click();
  const records = page.locator(".ability-row");
  await expect(records).toHaveCount(4);
  await expect(records.filter({ hasText: "旧档掌握记录" })).toContainText("已掌握");
  await expect(records.filter({ hasText: "旧档通用状态" })).toContainText("状态未明");
  await expect(records.filter({ hasText: "未明学习状态" })).toContainText("状态未明");
  await expect(records.filter({ hasText: "暂时受阻口诀" })).toContainText("暂不可用");
  await expect(records.filter({ hasText: "已经失去的口诀" })).toHaveCount(0);
  await expect(records.filter({ hasText: "旧档失去口诀" })).toHaveCount(0);
});

test("a failed connection preserves the submitted text across reload and save selection", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  await page.reload();
  await openSave(page);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).not.toBeEditable();
  await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "继续查询", exact: true })).toBeEnabled();
  await expect(page.getByRole("button", { name: "确认此行动", exact: true })).toBeDisabled();
  expect(api.posts).toHaveLength(1);
  expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
});

test("a terminal failure restores an editable draft across repeated reloads and a new submission gets a new ID", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.status = "FAILED";
  await page.reload();
  await openSave(page);
  const input = await composer(page);
  await expect(input).toHaveValue(ACTION);
  await expect(input).toBeEditable();
  await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toContainText("本次行动没有写入命卷");
  expect(api.posts).toHaveLength(1);
  expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
  await page.reload();
  await openSave(page);
  await expect(input).toHaveValue(ACTION);
  await expect(input).toBeEditable();
  await input.fill(`${ACTION}我先留在岸边。`);
  await expect(input).toHaveValue(`${ACTION}我先留在岸边。`);
  api.commitOnPost = true;
  await page.getByRole("button", { name: "确认此行动", exact: true }).click();
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(2);
  expect(api.posts[1].body.action).toBe(`${ACTION}我先留在岸边。`);
  expect(api.posts[1].body.requestId).not.toBe(api.posts[0].body.requestId);
});

test("UNKNOWN remains recoverable and explicit retry reuses the entire original request only once", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.status = "UNKNOWN";
  await page.reload();
  await openSave(page);
  const input = page.getByRole("textbox", { name: "自由行动", exact: true });
  const retry = page.getByRole("button", { name: "重试原行动", exact: true });
  await expect(retry).toBeEnabled();
  await expect(input).toHaveValue(ACTION);
  await expect(input).not.toBeEditable();
  // More than the former four-UNKNOWN cutoff must not discard the request.
  for (let index = 0; index < 5; index += 1) {
    const count = api.queries.length;
    await page.getByRole("button", { name: "继续查询", exact: true }).click();
    await expect.poll(() => api.queries.length).toBeGreaterThan(count);
    await expect(retry).toBeEnabled();
  }
  await page.reload();
  await openSave(page);
  await expect(input).toHaveValue(ACTION);
  await expect(retry).toBeEnabled();
  expect(api.posts).toHaveLength(1);
  const queriesBeforeRetry = api.queries.length;
  api.commitOnPost = true;
  await retry.evaluate((element) => {
    (element as HTMLButtonElement).click();
    (element as HTMLButtonElement).click();
  });
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  expect(api.queries.length).toBeGreaterThan(queriesBeforeRetry);
  expect(api.posts).toHaveLength(2);
  expect(api.posts[1]).toEqual(api.posts[0]);
  expect(api.queries.every((query) => query.saveId === FIRST_SAVE && query.requestId === api.posts[0].body.requestId)).toBe(true);
});

test("explicit retry checks status again and does not POST if the original request is processing", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.status = "UNKNOWN";
  await page.reload();
  await openSave(page);
  const retry = page.getByRole("button", { name: "重试原行动", exact: true });
  await expect(retry).toBeEnabled();
  const queriesBeforeRetry = api.queries.length;
  api.status = "PROCESSING";
  await retry.click();
  await expect.poll(() => api.queries.length).toBeGreaterThan(queriesBeforeRetry);
  await expect(retry).toHaveCount(0);
  await expect(page.getByRole("button", { name: "继续查询", exact: true })).toBeEnabled();
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  expect(api.posts).toHaveLength(1);
});

test("a committed request is recovered by GET without a second action POST", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.commit();
  const queriesBeforeReload = api.queries.length;
  await page.reload();
  await openSave(page);
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  await expect.poll(() => api.queries.length).toBeGreaterThan(queriesBeforeReload);
  expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
  expect(api.posts).toHaveLength(1);
  await expect(await composer(page)).toHaveValue("");
});

test("a pending action older than eight minutes still queries the original request after reload", async ({ page, api }) => {
  const start = new Date("2026-09-06T01:00:00Z");
  await page.clock.setFixedTime(start);
  await submitWhileOffline(page, api);
  api.commit();
  const queriedBeforeReload = api.queries.length;
  await page.clock.setFixedTime(new Date(start.getTime() + 9 * 60_000));
  await page.reload();
  await openSave(page);
  await expect.poll(() => api.queries.length).toBeGreaterThan(queriedBeforeReload);
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(1);
});

test("a legacy pending record older than eight minutes is recovered for its matching account and save", async ({ page, api }) => {
  api.commit();
  await page.goto("/");
  await expect(page.getByRole("button", { name: "进入沈照的命卷", exact: true })).toBeVisible();
  await page.evaluate(({ saveId, action, email }) => {
    localStorage.setItem("wenxian-pending-story-turn-v1", JSON.stringify({
      saveId, requestId: "legacy-recovery-request", action, expectedRevision: 3,
      accountEmail: email, createdAt: Date.now() - 9 * 60_000,
    }));
  }, { saveId: FIRST_SAVE, action: ACTION, email: FIRST_EMAIL });
  await page.reload();
  await openSave(page);
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  await expect.poll(() => api.queries.some((query) => query.saveId === FIRST_SAVE && query.requestId === "legacy-recovery-request")).toBe(true);
  expect(api.posts).toHaveLength(0);
});

test("a pending action does not appear or run against another save", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.status = "UNKNOWN";
  const queriesBeforeReload = api.queries.length;
  await page.reload();
  await openSave(page, "林渡");
  await expect(await composer(page)).toHaveValue("");
  await expect(page.getByText(ACTION, { exact: true })).toHaveCount(0);
  expect(api.posts).toHaveLength(1);
  expect(api.queries).toHaveLength(queriesBeforeReload);
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.locator("summary").filter({ hasText: "查看全部命卷" }).click();
  await page.getByRole("button", { name: "我的命卷", exact: true }).click();
  await openSave(page);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  await expect(page.getByRole("button", { name: "重试原行动", exact: true })).toBeEnabled();
});

test("a pending action is not exposed to or queried by another account", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.email = SECOND_EMAIL;
  api.status = "UNKNOWN";
  const queryCount = api.queries.length;
  await page.reload();
  await openSave(page, "顾宁");
  await expect(await composer(page)).toHaveValue("");
  await expect(page.getByText(ACTION, { exact: true })).toHaveCount(0);
  expect(api.queries).toHaveLength(queryCount);
  expect(api.posts).toHaveLength(1);
  api.email = FIRST_EMAIL;
  await page.reload();
  await openSave(page);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  await expect(page.getByRole("button", { name: "重试原行动", exact: true })).toBeEnabled();
});

test("two immediate activations submit only one action", async ({ page, api }) => {
  api.commitOnPost = true;
  await page.goto("/");
  await openSave(page);
  await (await composer(page)).fill(ACTION);
  await page.getByRole("button", { name: "确认此行动", exact: true }).evaluate((element) => {
    // Two native activations in the same task exercise the synchronous guard,
    // before a React render can hide or disable the submit button.
    (element as HTMLButtonElement).click();
    (element as HTMLButtonElement).click();
  });
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(1);
});

test("a 401 during recovery preserves the action through login to the same account", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.statusHttpError = 401;
  await page.getByRole("button", { name: "继续查询", exact: true }).click();
  await expect(page.getByRole("button", { name: "进入问仙录", exact: true })).toBeVisible();
  api.statusHttpError = null;
  api.status = "UNKNOWN";
  await page.getByRole("textbox", { name: "邮箱", exact: true }).fill(FIRST_EMAIL);
  await page.getByRole("textbox", { name: "密码", exact: true }).fill("offline-fixture-password");
  await page.getByRole("button", { name: "进入问仙录", exact: true }).click();
  await openSave(page);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).not.toBeEditable();
  await expect(page.getByRole("button", { name: "重试原行动", exact: true })).toBeEnabled();
  expect(api.posts).toHaveLength(1);
  expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
});

for (const status of [403, 404]) {
  test(`a status GET ${status} keeps the original action pending instead of turning it into a failed draft`, async ({ page, api }) => {
    await submitWhileOffline(page, api);
    api.statusHttpError = status;
    await page.reload();
    await openSave(page);
    await expect.poll(() => api.statusErrors).toContain(status);
    const input = page.getByRole("textbox", { name: "自由行动", exact: true });
    await expect(input).toHaveValue(ACTION);
    await expect(input).not.toBeEditable();
    await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toContainText("原行动已保留");
    await expect(page.getByRole("button", { name: "继续查询", exact: true })).toBeEnabled();
    await expect(page.getByRole("button", { name: "确认此行动", exact: true })).toBeDisabled();
    api.statusHttpError = null;
    api.status = "UNKNOWN";
    await page.getByRole("button", { name: "继续查询", exact: true }).click();
    await expect(page.getByRole("button", { name: "重试原行动", exact: true })).toBeEnabled();
    expect(api.posts).toHaveLength(1);
    expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
  });
}

test("a save-list refresh 500 after successful commit does not restore or resubmit the completed action", async ({ page, api }) => {
  api.commitOnPost = true;
  await page.goto("/");
  await openSave(page);
  await (await composer(page)).fill(ACTION);
  api.failNextSaveLists = 1;
  await page.getByRole("button", { name: "确认此行动", exact: true }).click();
  await expect.poll(() => api.failedSaveLists).toBe(1);
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  await expect(await composer(page)).toHaveValue("");
  await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toHaveCount(0);
  const queryCount = api.queries.length;
  await page.reload();
  await openSave(page);
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  await expect(await composer(page)).toHaveValue("");
  expect(api.posts).toHaveLength(1);
  expect(api.queries).toHaveLength(queryCount);
});

test("TURN_IN_PROGRESS followed by UNKNOWN remains pending and does not offer a new submission", async ({ page, api }) => {
  api.turnErrorCode = "TURN_IN_PROGRESS";
  api.status = "UNKNOWN";
  await page.goto("/");
  await openSave(page);
  await (await composer(page)).fill(ACTION);
  await page.getByRole("button", { name: "确认此行动", exact: true }).click();
  await expect(page.getByRole("button", { name: "重试原行动", exact: true })).toBeEnabled();
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).not.toBeEditable();
  await expect(page.getByRole("button", { name: "确认此行动", exact: true })).toBeDisabled();
  expect(api.posts).toHaveLength(1);
  expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
});

test("an unrelated theme storage event from another tab does not clear the current ordinary draft", async ({ page, api }) => {
  await page.goto("/");
  await openSave(page);
  const input = await composer(page);
  await input.fill(ACTION);
  const peer = await storagePeer(page);
  await peer.evaluate(() => localStorage.setItem("wenxian-theme", "dark"));
  await expect(page.locator("html")).toHaveAttribute("data-observed-storage-key", "wenxian-theme");
  await expect(input).toHaveValue(ACTION);
  await expect(input).toBeEditable();
  expect(api.posts).toHaveLength(0);
  await peer.evaluate(() => localStorage.setItem("wenxian-pending-story-turn-v1", JSON.stringify({
    accountEmail: "another@example.test", saveId: "another-save", requestId: "old-request",
    action: "别处的行动", expectedRevision: 1, createdAt: 1,
  })));
  await expect(page.locator("html")).toHaveAttribute("data-observed-storage-key", "wenxian-pending-story-turn-v1");
  await peer.evaluate(() => localStorage.removeItem("wenxian-pending-story-turn-v1"));
  await expect(input).toHaveValue(ACTION);
  await expect(input).toBeEditable();
  await peer.close();
});

for (const replacement of ["new request", "edited failed draft"] as const) {
  test(`a delayed status response cannot overwrite another tab's ${replacement}`, async ({ page, api }) => {
    await submitWhileOffline(page, api);
    const peer = await storagePeer(page);
    api.status = "UNKNOWN";
    api.holdNextStatusResponse = true;
    await page.getByRole("button", { name: "继续查询", exact: true }).click();
    await expect.poll(() => api.heldStatusResponse !== null).toBe(true);
    const key = `wenxian-story-turn-recovery-v1:${encodeURIComponent(FIRST_EMAIL)}:${encodeURIComponent(FIRST_SAVE)}`;
    const editedAction = "我先退到岸边，等船家整理好缆绳再开口。";
    const record = {
      pending: {
        ...api.posts[0].body, saveId: FIRST_SAVE, accountEmail: FIRST_EMAIL, createdAt: Date.now(),
        requestId: replacement === "new request" ? "request-created-by-other-tab" : api.posts[0].body.requestId,
        action: replacement === "new request" ? editedAction : ACTION,
      },
      status: replacement === "new request" ? "PENDING" : "FAILED",
      message: "另一标签页已经更新了此行动。",
      ...(replacement === "edited failed draft" ? { draft: editedAction } : {}),
    };
    await peer.evaluate(({ key, record }) => localStorage.setItem(key, JSON.stringify(record)), { key, record });
    await expect(page.locator("html")).toHaveAttribute("data-observed-storage-key", key);
    const delayed = api.heldStatusResponse!;
    await delayed.route.fulfill({ status: 200, contentType: "application/json", body: delayed.body });
    const input = page.getByRole("textbox", { name: "自由行动", exact: true });
    await expect(input).toHaveValue(editedAction);
    if (replacement === "new request") {
      await expect(page.getByRole("button", { name: "继续查询", exact: true })).toBeEnabled();
      await expect(input).not.toBeEditable();
    } else {
      await expect(page.getByRole("button", { name: "确认此行动", exact: true })).toBeEnabled();
      await expect(input).toBeEditable();
    }
    expect(await page.evaluate((key) => JSON.parse(localStorage.getItem(key) ?? "null"), key)).toEqual(record);
    expect(api.posts).toHaveLength(1);
    await peer.close();
  });
}

test("COMMITTED without a snapshot keeps the request locked when reading the result fails, then recovers without POST", async ({ page, api }) => {
  await submitWhileOffline(page, api);
  api.commit();
  api.omitCommittedSnapshot = true;
  api.failNextSaveDetails = 1;
  await page.getByRole("button", { name: "继续查询", exact: true }).click();
  await expect.poll(() => api.failedSaveDetails).toBe(1);
  await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toContainText(
    "这次行动已经完成，暂时无法读取结果。原请求已保留，请继续查询。",
  );
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).toHaveValue(ACTION);
  await expect(page.getByRole("textbox", { name: "自由行动", exact: true })).not.toBeEditable();
  await expect(page.getByRole("button", { name: "确认此行动", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "重试原行动", exact: true })).toHaveCount(0);
  await expect(page.getByText(/未消耗回合|命卷没有变化|未写入|没有写入/)).toHaveCount(0);
  const key = `wenxian-story-turn-recovery-v1:${encodeURIComponent(FIRST_EMAIL)}:${encodeURIComponent(FIRST_SAVE)}`;
  expect(await page.evaluate((key) => JSON.parse(localStorage.getItem(key) ?? "null"), key)).toMatchObject({
    status: "PENDING", pending: { requestId: api.posts[0].body.requestId, action: ACTION },
  });
  const queriesBeforeRetry = api.queries.length;
  await page.getByRole("button", { name: "继续查询", exact: true }).click();
  await expect(page.getByRole("heading", { name: "船家的回答", exact: true })).toBeVisible();
  await expect(await composer(page)).toHaveValue("");
  await expect(page.getByRole("status", { name: "行动恢复", exact: true })).toHaveCount(0);
  expect(api.queries.length).toBeGreaterThan(queriesBeforeRetry);
  expect(api.queries.every((query) => query.requestId === api.posts[0].body.requestId)).toBe(true);
  expect(api.posts).toHaveLength(1);
});
