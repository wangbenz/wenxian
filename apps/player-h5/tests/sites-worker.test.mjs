import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";
import worker from "../worker/index.js";

async function readPlayerComponents() {
  const sources = await Promise.all([
    "../src/PlayerApp.tsx",
    "../src/app/AppViews.tsx",
    "../src/app/player-read-model.ts",
    "../src/app/story-adapter.ts",
    "../src/features/auth/AuthViews.tsx",
    "../src/features/gameplay/GameViews.tsx",
    "../src/features/onboarding/CharacterOnboardingViews.tsx",
    "../src/features/saves/LifecycleViews.tsx",
    "../src/features/saves/SaveViews.tsx",
    "../src/features/settings/SettingsViews.tsx",
    "../src/shared/problem.tsx",
    "../src/design/DesignPreview.tsx",
  ].map((path) => readFile(new URL(path, import.meta.url), "utf8")));
  return sources.join("\n");
}

test("serves existing static assets without a fallback", async () => {
  const calls = [];
  const response = await worker.fetch(new Request("https://example.test/assets/app.js"), {
    ASSETS: {
      fetch: async (request) => {
        calls.push(new URL(request.url).pathname);
        return new Response("asset", { status: 200 });
      },
    },
  });

  assert.equal(response.status, 200);
  assert.deepEqual(calls, ["/assets/app.js"]);
});

test("falls back to index.html for an unknown app route", async () => {
  const calls = [];
  const response = await worker.fetch(
    new Request("https://example.test/flow/step-two?source=share", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async (request) => {
          const url = new URL(request.url);
          calls.push(url.pathname + url.search);
          return new Response(url.pathname === "/index.html" ? "app" : "missing", {
            status: url.pathname === "/index.html" ? 200 : 404,
          });
        },
      },
    },
  );

  assert.equal(response.status, 200);
  assert.deepEqual(calls, ["/flow/step-two?source=share", "/index.html"]);
});

test("does not turn missing API or write requests into the app shell", async () => {
  for (const request of [
    new Request("https://example.test/api/missing", { headers: { accept: "application/json" } }),
    new Request("https://example.test/flow", { method: "POST", headers: { accept: "text/html" } }),
  ]) {
    let calls = 0;
    const response = await worker.fetch(request, {
      ASSETS: {
        fetch: async () => {
          calls += 1;
          return new Response("missing", { status: 404 });
        },
      },
    });

    assert.equal(response.status, 404);
    assert.equal(calls, 1);
  }
});

test("emits the files required by Sites packaging", async () => {
  await access(new URL("../dist/client/index.html", import.meta.url));
  await access(new URL("../dist/server/index.js", import.meta.url));
  await access(new URL("../dist/.openai/hosting.json", import.meta.url));
});

test("keeps implementation details out of player-facing copy", async () => {
  const prototype = await readPlayerComponents();
  const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
  const forbiddenCopy = [
    "DeepSeek Agent 已连接",
    "Agent 负责叙事裁决",
    "Java 负责规则",
    "V4 PRO · 命卷推演中",
    "DeepSeek 正在推演",
    "DeepSeek 已推演",
    "启动 Java 服务",
    "恢复后端后才能继续行动",
    "Agent 只会在固定世界内调整开局",
    "由服务器沿同一时间轴保存",
    "Agent 修仙文字游戏",
  ];

  for (const copy of forbiddenCopy) {
    assert.equal(prototype.includes(copy) || html.includes(copy), false, `player copy leaked: ${copy}`);
  }
});

test("keeps the platform generation provider and model private", async () => {
  const prototype = await readPlayerComponents();

  assert.equal(prototype.includes("平台只展示体验档位和点数，不展示具体供应商与模型"), true);
  assert.equal(prototype.includes("也可接入自己的推演服务；密钥验证成功后使用量由对应服务计费，平台不扣点"), true);
  assert.equal(prototype.includes("玩家可修改平台当前模型"), false);
});

test("keeps settings reader-facing and compact before credential management", async () => {
  const source = await readFile(new URL("../src/features/settings/SettingsViews.tsx", import.meta.url), "utf8");
  const view = source.slice(source.indexOf("export function SettingsView"));
  const chronicle = await readFile(new URL("../src/features/gameplay/GameViews.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../src/daylight.css", import.meta.url), "utf8");
  const preview = await readFile(new URL("../src/design/DesignPreview.tsx", import.meta.url), "utf8");

  for (const copy of ["阅读", "续写", "平台续写", "自己的续写", "<strong>命卷</strong>", "<strong>账号</strong>"]) {
    assert.equal(view.includes(copy), true, `missing settings copy: ${copy}`);
  }
  assert.equal(view.includes("故事记录"), false);
  assert.equal(view.includes("onCorrection"), false);
  assert.equal(chronicle.includes("修正命卷中记错的事实"), true);
  assert.equal(view.includes("当前暂未开放可接入的推演服务"), false);
  assert.equal(view.includes("API Key"), false);
  assert.match(view, /\{personalAvailable && <article className="settings-option-row/);
  assert.match(styles, /\.settings-option-row > button,[\s\S]*?min-height:\s*44px;/);
  assert.equal(preview.includes("<ChronicleView game={story} onCorrection="), true);
});

test("keeps maintenance, account and destructive actions progressively disclosed", async () => {
  const prototype = await readPlayerComponents();

  assert.equal(prototype.includes("当前暂停新账号注册，已有账号可正常登录"), true);
  assert.equal(prototype.includes(">更新期间暂停注册<"), false);
  assert.equal(prototype.includes('className="settings-fold"'), true);
  assert.equal(prototype.includes('className="settings-fold settings-danger-zone"'), true);
});

test("keeps adjudication numbers out of the narrative character UI", async () => {
  const prototype = await readPlayerComponents();
  const forbiddenFragments = [
    "fate-stats",
    'className="stat-grid"',
    "体力 {option.vitality}",
    "风险 {thread.danger}",
    "体力 ${game.player.vitality}",
  ];

  for (const fragment of forbiddenFragments) {
    assert.equal(prototype.includes(fragment), false, `numeric player UI leaked: ${fragment}`);
  }
});

test("uses the server quota instead of hardcoding twenty turns", async () => {
  const prototype = await readPlayerComponents();

  assert.equal(prototype.includes("赠送 20 个推演回合"), false);
  assert.equal(prototype.includes("获赠 20 个推演回合"), false);
  assert.equal(prototype.includes("nextGenerationSettings.platform.turnLimit"), true);
  assert.equal(prototype.includes("PUBLIC_TURN_LIMIT_REACHED"), true);
});

test("uses the server-managed active save limit", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("maxActiveSaves: number"), true);
  assert.equal(prototype.includes("source.maxActiveSaves"), true);
  assert.equal(prototype.includes("const list = { maxActiveSaves: 2"), false);
});

test("keeps story record cleanup soft and outside the turn composer", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(prototype.includes("人物记录分类"), true);
  assert.equal(prototype.includes("整理行囊"), true);
  assert.equal(prototype.includes("整理关注"), true);
  assert.equal(prototype.includes("查看全部"), true);
  assert.equal(prototype.includes("不调用推演 · 不消耗回合 · 不推进时间"), true);
  assert.equal(api.includes("storyRemoveEntity"), true);
  assert.equal(api.includes("/api/v1/story/saves/"), true);
});

test("presents the chronicle as a reader-facing recap", async () => {
  const view = await readFile(new URL("../src/features/gameplay/GameViews.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../src/daylight.css", import.meta.url), "utf8");

  for (const copy of ["前情", "发生之事", "你的选择", "有所获得", "留下的代价"]) {
    assert.equal(view.includes(copy), true, `missing chronicle copy: ${copy}`);
  }
  for (const copy of ["统一世界时", "世界事件", "玩家决策", "风险变化"]) {
    assert.equal(view.includes(copy), false, `system chronicle copy leaked: ${copy}`);
  }
  assert.equal(view.includes("visibleEvents.map((event, index)"), true);
  assert.equal(view.includes("formatWorldTime(game.worldTime)"), true);
  assert.match(styles, /\.chronicle-history-toggle\s*\{[\s\S]*?min-height:\s*44px;/);
});

test("does not invent a quantity for uncounted inventory", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("quantityKnown?: boolean"), true);
  assert.equal(prototype.includes("数量未清点"), true);
  assert.equal(prototype.includes("item.quantityKnown === false"), true);
});

test("keeps failed actions in the shared problem channel instead of a legacy choice branch", async () => {
  const prototype = await readPlayerComponents();
  const view = await readFile(new URL("../src/features/gameplay/GameViews.tsx", import.meta.url), "utf8");
  const problem = await readFile(new URL("../src/shared/problem.tsx", import.meta.url), "utf8");
  const design = await readFile(new URL("../src/design/DesignPreview.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../src/prototype.css", import.meta.url), "utf8");

  assert.equal(prototype.includes('<ProblemNotice context="story" problem={error}'), true);
  assert.equal(prototype.includes("actionResolution"), false);
  assert.equal(prototype.includes("本轮未发生"), false);
  assert.equal(view.indexOf('<ProblemNotice context="story" problem={error}') < view.indexOf('className="chapter-decision"'), true);
  for (const copy of ["续写未完成", "命卷没有变化", "重新选择，或原样再试", "重新提交此行动"]) {
    assert.equal(`${view}\n${problem}`.includes(copy), true, `missing recovery copy: ${copy}`);
  }
  assert.equal(design.includes('initialChoiceId={page === "error" ? "ask" : undefined}'), true);
  assert.match(styles, /\.story-problem-notice::before\s*\{[\s\S]*?linear-gradient/);
  assert.match(styles, /\.chapter-decision\[data-recovery="true"\]\s*\{[\s\S]*?margin-top:\s*32px;/);
});

test("uses the wordmark only at entry stages and keeps the scene title in the story field", async () => {
  const app = await readFile(new URL("../src/PlayerApp.tsx", import.meta.url), "utf8");
  const design = await readFile(new URL("../src/design/DesignPreview.tsx", import.meta.url), "utf8");
  const view = await readFile(new URL("../src/features/gameplay/GameViews.tsx", import.meta.url), "utf8");

  assert.equal(app.includes('["loading", "saves"].includes(stage)'), true);
  assert.equal(app.includes('const headerlessGame = stage === "game" && (game?.phase === "dead"'), true);
  assert.equal(app.includes('["adventure", "character", "chronicle", "settings"].includes(tab)'), true);
  assert.equal(design.includes('const showWordmark = ["loading", "saves"].includes(page)'), true);
  assert.equal(design.includes('["adventure", "pending", "error", "character", "chronicle", "settings", "death"].includes(page)'), true);
  for (const contextTitle of ['"故事"', '"前情"', '"设置"', '"创建命卷"']) {
    assert.equal(design.includes(contextTitle), true, `missing preview context title: ${contextTitle}`);
  }
  assert.equal(view.includes('<header className="story-page-header"><h1 id="scene-title">{game.sceneTitle}</h1><span>本回</span></header>'), true);
  assert.equal(design.includes("浮生一卷 · 日间设计预览"), false);
});

test("reduces a pending turn to the confirmed action and reader-facing writing progress", async () => {
  const view = await readFile(new URL("../src/features/gameplay/GameViews.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../src/daylight.css", import.meta.url), "utf8");

  for (const copy of ["已确认", "命卷续写中", "正在续写这一回", "承接前情", "续写故事", "写入命卷"]) {
    assert.equal(view.includes(copy), true, `missing pending copy: ${copy}`);
  }
  for (const copy of ["推演人物与因果", "校验状态与时间线", "统一写入人物、天赋与世界时间"]) {
    assert.equal(view.includes(copy), false, `internal pending copy leaked: ${copy}`);
  }
  assert.match(view, /\{isBusy && pendingAction && <section aria-label="已确认的行动"/);
  assert.match(view, /\{!isBusy && <section className="chapter-decision"/);
  assert.match(styles, /\.chapter-writing-line\s*\{[\s\S]*?height:\s*2px;/);
  assert.match(styles, /\.chapter-generating\[data-phase="2"\] \.chapter-writing-line span \{ width: 100%; \}/);
});

test("reveals free action at the end of the adventure flow instead of floating over the story", async () => {
  const prototype = await readPlayerComponents();
  const styles = await Promise.all(["../src/prototype.css", "../src/daylight.css"]
    .map((path) => readFile(new URL(path, import.meta.url), "utf8")))
    .then((sources) => sources.join("\n"));

  assert.equal(prototype.includes('data-testid="turn-action-zone"'), true);
  assert.equal(prototype.indexOf('data-testid="turn-action-zone"') > prototype.indexOf("<AdventureView"), true);
  assert.equal(prototype.includes("不采用推荐选项时，亲自描述下一步"), true);
  assert.equal(prototype.includes('aria-expanded={customActionOpen}'), true);
  assert.equal(prototype.includes('className="action-composer-footer"'), true);
  assert.equal(prototype.includes("确认此行动"), true);
  assert.equal(prototype.includes("rows={4}"), true);
  assert.match(styles, /\.action-composer\s*\{[\s\S]*?position:\s*relative;/);
  assert.match(styles, /\.action-composer textarea\s*\{[\s\S]*?min-height:\s*88px;/);
  assert.match(styles, /@media \(max-width: 420px\)[\s\S]*?\.act-button \{ width: 100%; \}/);
  assert.equal(styles.includes('position: absolute;\n  z-index: 12;\n  right: auto;\n  bottom: calc(78px + var(--bottom-safe));'), false);
});

test("uses shared responsive controls for dialogs and generation settings", async () => {
  const prototype = await readPlayerComponents();
  const styles = await Promise.all(["../src/prototype.css", "../src/daylight.css"]
    .map((path) => readFile(new URL(path, import.meta.url), "utf8")))
    .then((sources) => sources.join("\n"));

  assert.equal(prototype.includes("ChevronDownIcon"), true);
  assert.equal(prototype.includes('className="select-control"'), true);
  assert.match(styles, /\.select-control select \{[^}]*appearance:\s*none;/);
  assert.match(styles, /\.ink-dialog\s*\{[\s\S]*?max-height:\s*calc\(100dvh/);
  assert.match(styles, /\.game-shell:not\(\[data-stage="login"\]\) \.choice-list \{[\s\S]*?grid-template-columns:\s*minmax\(0, 1fr\);/);
  assert.equal(styles.includes(".choice-list { grid-template-columns: repeat(2"), false);
});

test("keeps this redesign daylight-only and exposes every critical review state", async () => {
  const prototype = await readPlayerComponents();

  assert.equal(prototype.includes('const actualTheme = "paper"'), true);
  assert.equal(prototype.includes("故事、选项与人物资料同步调整"), true);
  for (const page of [
    "loading", "saves", "create", "create-loading", "adventure", "pending", "error",
    "character", "chronicle", "death", "settings", "dialog",
  ]) {
    assert.equal(prototype.includes(`"${page}"`), true, `missing design review state: ${page}`);
  }
  assert.equal(prototype.includes("正在开启"), true);
  assert.equal(prototype.includes("本次行动没有写入命卷"), true);
  assert.equal(prototype.includes("版本更新 ·"), true);
});

test("separates authoritative origin from soft story preferences", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(prototype.includes("角色设定"), true);
  assert.equal(prototype.includes("游玩偏好"), true);
  assert.equal(prototype.includes("不可随意改写的出身事实"), true);
  assert.equal(prototype.includes("const origin = confirmedOrigin(request, race.raceId, preparedOpening)"), true);
  assert.equal(prototype.includes("playPreference: request.playPreference"), true);
  assert.equal(prototype.includes("preference: route.preference"), false);
  assert.equal(api.includes("originPremise: string"), true);
  assert.equal(api.includes("playPreference: PlayPreferenceDraft"), true);
});

test("uses the singular authoritative world package and its race catalog", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(prototype.includes("推演三条角色路线"), true);
  assert.equal(prototype.includes("选择来处，再决定故事更看重什么"), true);
  assert.equal(prototype.includes("此卷所在"), true);
  assert.equal(prototype.includes("影响身份事实与他人判断"), true);
  assert.equal(prototype.includes("bootstrap.creation.world"), true);
  assert.equal(prototype.includes("bootstrap.creation.worlds"), false);
  assert.equal(api.includes("worldRevision: number"), true);
  assert.equal(api.includes("raceId: string"), true);
  assert.equal(api.includes("creation: StoryCreationCatalog"), true);
  assert.equal(prototype.includes('worldPackageId: "XUANXU"'), false);
  assert.equal(prototype.includes('raceId: "race:xuanxu:human"'), false);
  assert.equal(prototype.includes("preferences.defaultFocus"), true);
  assert.equal(prototype.includes("freshCharacterDraft(bootstrap)"), true);
  assert.equal(prototype.includes('draft.worldCode === "CUSTOM"'), false);
  assert.equal(prototype.includes("isStoryMode"), false);
  assert.equal(api.includes('"/api/v1/story/character-routes"'), true);
});

test("keeps character creation portrait-first on phones", async () => {
  const styles = await readFile(new URL("../src/prototype.css", import.meta.url), "utf8");
  const phoneStart = styles.indexOf("@media (max-width: 640px)");
  const phoneRules = styles.slice(phoneStart, styles.indexOf("@media (max-width: 420px)", phoneStart));

  assert.match(phoneRules, /\.race-grid\s*\{\s*grid-template-columns:\s*1fr;\s*\}/);
  assert.doesNotMatch(phoneRules, /grid-auto-flow:\s*column/);
  assert.doesNotMatch(phoneRules, /overflow-x:\s*auto/);
  assert.doesNotMatch(phoneRules, /scroll-snap-type:\s*inline/);
  assert.equal(styles.includes("orientation: landscape"), false);
});

test("routes current gameplay through version-neutral story and generation APIs", async () => {
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("/api/v1/v014"), false);
  assert.equal(api.includes('"/api/v1/game/bootstrap"'), false);
  assert.equal(api.includes('"/api/v1/saves"'), false);
  assert.equal(api.includes('"/api/v1/ai/settings"'), false);
  assert.equal(api.includes('"/api/v1/story/bootstrap"'), true);
  assert.equal(api.includes('"/api/v1/generation/settings"'), true);
  assert.equal(api.includes("expectedRevision"), true);
});

test("uses a focused player read model without fabricated legacy state", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("export type GameState"), false);
  assert.equal(api.includes("export type ApiSnapshot"), false);
  assert.equal(api.includes("export type BootstrapResponse"), false);
  assert.equal(prototype.includes("PlayerStorySnapshot"), true);
  assert.equal(prototype.includes("age: 16"), false);
  assert.equal(prototype.includes("vitality: 75"), false);
  assert.equal(prototype.includes("actionResolution"), false);
  assert.equal(prototype.includes("threads: []"), false);
});

test("shows the shared realm scale and creates every character at the fixed starting realm", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("realmScale: StoryRealmScaleOption"), true);
  assert.equal(api.includes("realm: StoryRealmView"), true);
  assert.equal(api.includes('"/api/v1/story/cultivation-systems/recommendations"'), false);
  assert.equal(api.includes("cultivationSystem:"), false);
  assert.equal(api.includes("startingStageId:"), false);
  assert.equal(prototype.includes("此身起点"), true);
  assert.equal(prototype.includes("之后的变化只来自已经发生的经历"), true);
  assert.equal(prototype.includes("查看三族境界对照"), true);
  assert.equal(prototype.includes("开启此卷"), true);
  assert.equal(prototype.includes("核对开篇；确认后，这些内容会写入命卷"), true);
  assert.equal(prototype.includes("CultivationConfirmationView"), false);
});

test("presents character creation as a three-part life record instead of a system form", async () => {
  const view = await readFile(new URL("../src/features/onboarding/CharacterOnboardingViews.tsx", import.meta.url), "utf8");
  const appView = await readFile(new URL("../src/app/AppViews.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../src/prototype.css", import.meta.url), "utf8");

  for (const copy of ["定其身", "择其途", "启其卷", "三条开篇", "人物来处", "故事更看重", "偏好以后仍可调整"]) {
    assert.equal(view.includes(copy), true, `missing creation copy: ${copy}`);
  }
  for (const internalCopy of ["修订 {world", "L1 ·", "没有预设物品、能力或人物", "只有服务端完整创建成功后"]) {
    assert.equal(view.includes(internalCopy), false, `internal creation copy leaked: ${internalCopy}`);
  }
  for (const internalCopy of ["绑定世界修订", "半成品命卷"]) {
    assert.equal(appView.includes(internalCopy), false, `internal loading copy leaked: ${internalCopy}`);
  }
  assert.equal(appView.includes("正在写下此卷开篇"), true);
  assert.match(styles, /\.game-shell\[data-stage="create"\] \.creation-flow-card\s*\{[\s\S]*?background:\s*transparent;[\s\S]*?border:\s*0;/);
  assert.match(styles, /\.creation-name-seal\s*\{[\s\S]*?height:\s*58px;/);
  assert.match(styles, /\.preference-fixed-value\s*\{[\s\S]*?display:\s*grid;/);
});

test("requires player-authored yao or spirit identity facts", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("raceIdentity: string"), true);
  assert.equal(prototype.includes("你的本相"), true);
  assert.equal(prototype.includes("你的本源或本体"), true);
  assert.equal(prototype.includes('type: "IDENTITY"'), true);
  assert.equal(prototype.includes('localRef: "origin:fact:identity"'), true);
  assert.equal(prototype.includes("由你亲自确定"), true);
});

test("routes restart through the stable story API and exposes the account key before entering the world", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("restartSave:"), false);
  assert.equal(api.includes("storyCreateSave:"), true);
  assert.equal(api.includes("storyDeleteSave:"), true);
  assert.equal(prototype.includes("await gameApi.deleteSave(game.saveId)"), false);
  assert.equal(prototype.includes("GenerationAccessCard"), true);
  assert.equal(prototype.includes('stage === "create"'), true);
  assert.equal(prototype.includes("接入自己的推演密钥"), true);
  assert.equal(prototype.includes("返回原来的命卷"), true);
});

test("shows four reusable save slots without a product-version runtime", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(prototype.includes("Array.from({ length: maxActiveSaves }"), true);
  assert.equal(prototype.includes("确认删除"), true);
  assert.equal(prototype.includes("命卷已删除，空出的命卷位可以重新使用"), true);
  assert.equal(prototype.includes("LegacyPrototype"), false);
  assert.equal(prototype.includes("navigateEdition"), false);
  assert.equal(prototype.includes("V0.13 旧版命卷档案"), false);
  assert.equal(api.includes("storyDeleteSave"), true);
  assert.equal(api.includes("storyArchive"), false);
  assert.equal(prototype.includes("冲突项补到空位"), true);
});

test("requires a preview and explicit confirmation before correcting story facts", async () => {
  const prototype = await readPlayerComponents();
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");

  assert.equal(api.includes("storyCorrectionPreview"), true);
  assert.equal(api.includes("/corrections/preview"), true);
  assert.equal(api.includes("storyCorrectionConfirm"), true);
  assert.equal(api.includes("/corrections/confirm"), true);
  assert.equal(api.includes("storyCorrect:"), false);
  assert.equal(prototype.includes("生成修正预览"), true);
  assert.equal(prototype.includes("确认写入命卷"), true);
  assert.equal(prototype.includes("不消耗回合 · 不推进时间 · 保留原有纪事"), true);
  assert.equal(prototype.includes("gameApi.storyCorrect("), false);
});

test("shows a persisted major decision instead of letting prose decide silently", async () => {
  const api = await readFile(new URL("../src/game-api.ts", import.meta.url), "utf8");
  const prototype = await readPlayerComponents();
  assert.equal(api.includes("pendingDecision?: { prompt: string } | null"), true);
  assert.equal(prototype.includes("命卷已经停在这里，不会替你作出这个选择"), true);
});
