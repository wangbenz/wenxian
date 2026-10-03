import {
  ApiError,
  type AuthResponse,
  type StoryCharacterRoute,
  type StoryCharacterRouteRequest,
  type StoryCreateSave,
  type StoryGenerationSettings,
  type StoryWorldPackageOption,
} from "../game-api";

type DemoSave = {
  saveId: string;
  mode: "PLAYABLE" | "ENDED";
  engineCode: string;
  engineVersion: number;
  stateSchemaVersion: number;
  revision: number;
  turnNumber: number;
  capabilities: string[];
  state: {
    saveId: string;
    turnNumber: number;
    status: "ACTIVE" | "ENDED";
    world: {
      code: string;
      name: string;
      source: string;
      premise: string;
      cultivationStyle: string;
      openingRegion: string;
    };
    protagonist: {
      name: string;
      race: string;
      gender: string;
      origin: string;
      realm: string;
      lifeGoal: string;
      storyPreference: string;
    };
    worldTime: { year: number; day: number; phase: "DAWN" | "MORNING" | "NOON" | "AFTERNOON" | "DUSK" | "NIGHT" | "LATE_NIGHT" };
    currentLocation: string;
    condition: string;
    sceneTitle: string;
    narrative: string;
    pendingDecision: { prompt: string } | null;
    choices: Array<{ id: string; title: string; description: string }>;
    inventory: Array<{ id: string; name: string; type: string; description: string; status: string; source: string; quantity: number; quantityKnown: boolean; inPossession: boolean }>;
    abilities: Array<{ id: string; name: string; type: string; description: string; status: string; source: string }>;
    characters: Array<{ id: string; name: string; tier: string; identity: string; relationship: string; status: string; lastKnownLocation: string; tracked: boolean }>;
    memories: Array<{ id: string; title: string; summary: string; worldTime: DemoSave["state"]["worldTime"]; importance: string }>;
    realm: {
      scaleId: string;
      scaleRevision: number;
      race: string;
      currentStageId: string;
      currentStageName: string;
      currentRankKey: string;
      currentPhase: "EARLY" | "MIDDLE" | "LATE" | "PERFECT";
      progressMarks: number;
      requiredProgressMarks: number;
      stages: Array<{ stageId: string; order: number; name: string; description: string; aliases: string[]; allowedNextStageIds: string[] }>;
    };
  };
  generationMode: "PLATFORM" | "PERSONAL";
  personalProvider: string | null;
  personalModel: string | null;
};

type DemoState = {
  authenticatedEmail: string | null;
  passwordConfigured: boolean;
  saves: DemoSave[];
  generation: StoryGenerationSettings;
  readReleaseIds: string[];
  pendingCorrection: { saveId: string; instruction: string; token: string } | null;
};

const STORAGE_KEY = "wenxian-local-demo-v1";

const worldPackage: StoryWorldPackageOption = {
  packageId: "xuanxu:1",
  revision: 1,
  name: "玄墟界",
  premise: "凡人与修行者共同生活在一条不会为玩家停下的时间线上。",
  coreRules: ["境界提升必须来自已经发生的经历", "人物只知道自己亲历或获知的事实", "承诺、伤亡与选择会持续影响后续故事"],
  narrativeGuidance: "克制、连续，重视人物关系与真实代价。",
  races: [
    { raceId: "xuanxu:human", name: "人族", description: "寿数有限，善借传承与制度延续道路。", identityFacts: ["人族身份不额外赋予修为"], socialHooks: ["宗门、家族与城镇关系紧密"] },
    { raceId: "xuanxu:yao", name: "妖族", description: "由山川草木或走兽启灵，必须亲自确定本相。", identityFacts: ["本相是不可被静默改写的身份事实"], socialHooks: ["不同地域对妖族态度各异"] },
    { raceId: "xuanxu:spirit", name: "灵族", description: "由器物、地脉或残念孕生，必须确定本源。", identityFacts: ["本源决定初始身份而非战力"], socialHooks: ["常被视作异类、祥瑞或禁忌"] },
  ],
  openingRegions: [
    { regionId: "qinglan", name: "青岚渡", description: "南境水陆交汇之地。" },
    { regionId: "qixia", name: "栖霞岭", description: "妖灵与山民共居的边岭。" },
  ],
  realmScale: {
    scaleId: "xuanxu-realms",
    revision: 1,
    phases: ["EARLY", "MIDDLE", "LATE", "PERFECT"],
    ranks: [
      {
        rankKey: "L1", order: 1, raceRealms: [
          { raceId: "xuanxu:human", stageId: "l1-human", name: "炼气境", description: "以经脉承载灵气。", capabilityBoundary: "尚不能长时间御器。", aliases: [], },
          { raceId: "xuanxu:yao", stageId: "l1-yao", name: "启灵境", description: "以本相感应天地。", capabilityBoundary: "尚不能稳定化形。", aliases: [], },
          { raceId: "xuanxu:spirit", stageId: "l1-spirit", name: "聚识境", description: "以本源凝聚灵识。", capabilityBoundary: "灵识仍依附本源。", aliases: [], },
        ],
      },
      {
        rankKey: "L2", order: 2, raceRealms: [
          { raceId: "xuanxu:human", stageId: "l2-human", name: "筑基境", description: "灵力周天初成。", capabilityBoundary: "可短时御器。", aliases: [], },
          { raceId: "xuanxu:yao", stageId: "l2-yao", name: "化形境", description: "本相与人形渐趋稳定。", capabilityBoundary: "可维持化形。", aliases: [], },
          { raceId: "xuanxu:spirit", stageId: "l2-spirit", name: "凝魄境", description: "灵识凝成稳定魂魄。", capabilityBoundary: "可暂离本源。", aliases: [], },
        ],
      },
    ],
  },
};

const generationDefaults: StoryGenerationSettings = {
  mode: "PLATFORM",
  platform: { turnLimit: 100, usedTurns: 38, remainingTurns: 62, available: true },
  platformTiers: [
    { code: "LIGHT", name: "轻量", description: "适合日常推进", pointCost: 1, selected: false },
    { code: "BALANCED", name: "均衡", description: "兼顾叙事与状态", pointCost: 2, selected: true },
    { code: "DEEP", name: "深入", description: "用于关键长回合", pointCost: 4, selected: false },
  ],
  selectedPlatformTier: "BALANCED",
  personalProviders: [
    { code: "personal", name: "自己的推演服务", credentialConfigured: false, credentialStatus: null, maskedSuffix: null, lastVerifiedAt: null,
      models: [{ code: "default", name: "默认模型" }] },
  ],
  selectedPersonalProvider: null,
  selectedPersonalModel: null,
};

const releaseNote = {
  id: "local-demo-release",
  version: "本地演示",
  title: "命卷可信状态更新",
  content: "人物、物品与世界时间现在沿统一命卷延续。\n本地演示中的操作只保存在当前浏览器会话。",
  publishedAt: "2026-09-02T00:00:00Z",
  unread: false,
};

const raceName = (raceId: string) => raceId.endsWith(":yao") ? "妖族" : raceId.endsWith(":spirit") ? "灵族" : "人族";
const raceCode = (name: string) => name === "妖族" ? "DEMON" : name === "灵族" ? "SPIRIT" : "HUMAN";
const realmName = (name: string) => name === "妖族" ? "启灵境初期" : name === "灵族" ? "聚识境初期" : "炼气境初期";

function makeRealm(race: string): DemoSave["state"]["realm"] {
  const stageName = realmName(race).replace("初期", "");
  return {
    scaleId: "xuanxu-realms",
    scaleRevision: 1,
    race,
    currentStageId: race === "妖族" ? "l1-yao" : race === "灵族" ? "l1-spirit" : "l1-human",
    currentStageName: stageName,
    currentRankKey: "L1",
    currentPhase: "EARLY",
    progressMarks: 1,
    requiredProgressMarks: 4,
    stages: [
      { stageId: "l1", order: 1, name: stageName, description: "修行道路刚刚开始。", aliases: [], allowedNextStageIds: ["l2"] },
      { stageId: "l2", order: 2, name: race === "妖族" ? "化形境" : race === "灵族" ? "凝魄境" : "筑基境", description: "下一阶段尚待亲历。", aliases: [], allowedNextStageIds: [] },
    ],
  };
}

function makeSave(options: { id: string; slotNo: number; name: string; race: string; gender: string; turn: number; location: string; sceneTitle: string }): DemoSave {
  const time = { year: 317, day: 42, phase: "MORNING" as const };
  return {
    saveId: options.id,
    mode: "PLAYABLE",
    engineCode: "trusted-story",
    engineVersion: 1,
    stateSchemaVersion: 1,
    revision: options.turn,
    turnNumber: options.turn,
    capabilities: [],
    state: {
      saveId: options.id,
      turnNumber: options.turn,
      status: "ACTIVE",
      world: {
        code: "xuanxu", name: "玄墟界", source: "LOCAL_DEMO",
        premise: worldPackage.premise,
        cultivationStyle: "修行来自经历、机缘与代价，境界不能凭空获得。",
        openingRegion: "青岚渡及其相连的南境水路",
      },
      protagonist: {
        name: options.name, race: options.race, gender: options.gender,
        origin: options.race === "妖族" ? "栖霞岭中循月而醒的白鹿妖" : "青岚山覆灭后失去三年记忆的散修",
        realm: realmName(options.race),
        lifeGoal: options.race === "妖族" ? "查明山中灵脉枯竭的缘由" : "查清失忆与师门覆灭的真相",
        storyPreference: "偏重人物关系与探索，战斗适中",
      },
      worldTime: time,
      currentLocation: options.location,
      condition: "旧伤未愈",
      sceneTitle: options.sceneTitle,
      narrative: "雨势稍歇，渡口的青石仍泛着冷光。\n你从残碑旁醒来，袖中多了一枚刻着云纹的铜令。\n江面浮着薄雾，摆渡人垂手立在船头，像是已经等了很久。\n木桥尽头的黑衣人没有催促，只抬眼望向你。",
      pendingDecision: null,
      choices: [
        { id: "ask", title: "先问清故人的名讳", description: "不立即登船，确认来人的身份与铜令来历。" },
        { id: "follow", title: "跟随对方进入渡口", description: "接受眼前线索，把答案留到对岸。" },
        { id: "watch", title: "暂不回应，观察四周", description: "留在原地，判断渡口中还有谁在关注你。" },
      ],
      inventory: [
        { id: "cloud-token", name: "云纹铜令", type: "CLUE", description: "来历不明，裂纹正指向江心。", status: "ACTIVE", source: "青岚渡残碑", quantity: 1, quantityKnown: true, inPossession: true },
        { id: "broken-sword", name: "无鞘断剑", type: "WEAPON", description: "从青岚山带出的唯一旧物。", status: "ACTIVE", source: "青岚山旧事", quantity: 1, quantityKnown: true, inPossession: true },
      ],
      abilities: [
        { id: "listen-wind", name: "听风诀", type: "TECHNIQUE", description: "能从细微风声中分辨近处异动。", status: "初窥", source: "残缺师门心法" },
        { id: "sword-echo", name: "剑骨余鸣", type: "TALENT", description: "接近与旧剑有关的事物时，伤处会出现异常感应。", status: "未明", source: "青岚山旧事" },
      ],
      characters: [
        { id: "yan-chi", name: "晏迟", tier: "KNOWN", identity: "黑衣引路人", relationship: "自称受故人所托", status: "ACTIVE", lastKnownLocation: "青岚渡木桥", tracked: true },
        { id: "lu-weng", name: "陆翁", tier: "KNOWN", identity: "摆渡人", relationship: "态度不明", status: "ALIVE", lastKnownLocation: "渡船船头", tracked: true },
      ],
      memories: [
        { id: "old-injury", title: "旧伤恶化", summary: "强行运转残缺心法后，胸口旧伤再次裂开。", worldTime: { year: 317, day: 38, phase: "NIGHT" }, importance: "NORMAL" },
        { id: "token-change", title: "铜令生变", summary: "云纹铜令出现裂纹，并隐约指向江心。", worldTime: { year: 317, day: 42, phase: "DAWN" }, importance: "KEY" },
        { id: "visitor", title: "渡口来人", summary: "黑衣人晏迟持故人口信出现在青岚渡，邀请你登船。", worldTime: time, importance: "KEY" },
      ],
      realm: makeRealm(options.race),
    },
    generationMode: "PLATFORM",
    personalProvider: null,
    personalModel: null,
  };
}

function initialState(): DemoState {
  return {
    authenticatedEmail: null,
    passwordConfigured: true,
    saves: [
      makeSave({ id: "demo-shen-zhao", slotNo: 1, name: "沈照", race: "人族", gender: "MALE", turn: 18, location: "青岚渡", sceneTitle: "渡口故人" }),
      makeSave({ id: "demo-qi-yue", slotNo: 2, name: "祁月", race: "妖族", gender: "FEMALE", turn: 7, location: "栖霞岭", sceneTitle: "山雨将至" }),
    ],
    generation: structuredClone(generationDefaults),
    readReleaseIds: [],
    pendingCorrection: null,
  };
}

function loadState(): DemoState {
  const mode = new URLSearchParams(window.location.search).get("demo");
  if (mode === "reset") {
    window.sessionStorage.removeItem(STORAGE_KEY);
    const url = new URL(window.location.href);
    url.searchParams.set("demo", "1");
    window.history.replaceState(null, "", url);
  }
  try {
    const stored = window.sessionStorage.getItem(STORAGE_KEY);
    return stored ? JSON.parse(stored) as DemoState : initialState();
  } catch {
    return initialState();
  }
}

let demoState = loadState();

function persist() {
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(demoState));
}

function clone<T>(value: T): T {
  return structuredClone(value);
}

function authenticated(newUser = false): AuthResponse {
  const email = demoState.authenticatedEmail;
  return {
    authenticated: Boolean(email),
    newUser,
    user: email ? { userId: "local-demo-user", email } : null,
    hasSave: demoState.saves.length > 0,
    passwordConfigured: demoState.passwordConfigured,
    nextStep: !email ? "LOGIN" : demoState.saves.length > 0 ? "RESUME_GAME" : "CREATE_CHARACTER",
  };
}

function summary(save: DemoSave) {
  return {
    saveId: save.saveId,
    slotNo: demoState.saves.indexOf(save) + 1,
    mode: save.mode,
    engineCode: save.engineCode,
    engineVersion: save.engineVersion,
    stateSchemaVersion: save.stateSchemaVersion,
    revision: save.revision,
    turnNumber: save.turnNumber,
    capabilities: save.capabilities,
    worldName: save.state.currentLocation,
    playerName: save.state.protagonist.name,
    race: raceCode(save.state.protagonist.race),
    realm: save.state.protagonist.realm,
    status: save.state.status,
    updatedAt: new Date(2026, 8, 2, 10, save.turnNumber).toISOString(),
  };
}

function findSave(saveId: string): DemoSave {
  const save = demoState.saves.find((item) => item.saveId === saveId);
  if (!save) throw new ApiError("没有找到这卷人生", 404, "STORY_SAVE_NOT_FOUND");
  return save;
}

function parseBody<T>(init?: RequestInit): T {
  if (typeof init?.body !== "string") return {} as T;
  return JSON.parse(init.body) as T;
}

function createRoutes(draft: StoryCharacterRouteRequest): StoryCharacterRoute[] {
  const identity = draft.identity.name;
  // 仅本地演示的固定样本；真实开场由服务端候选生成接口提供。
  const routes: StoryCharacterRoute[] = draft.mode === "FINALIZE" ? [{
    id: "route-custom", title: "自定来处",
    origin: { summary: draft.roleSeed ?? "独自入世的散修", establishedFacts: [], openHooks: [], startingEntities: [] },
    opening: { regionId: "qinglan", situation: "你来到青岚渡，停在渡口边询问前路。" },
  }] : [
    { id: "route-returner", title: "断剑归客", origin: { summary: `${identity}是青岚山覆灭后失去三年记忆的散修`, establishedFacts: [], openHooks: [], startingEntities: [] }, opening: { regionId: "qinglan", situation: "在青岚渡残碑旁醒来" } },
    { id: "route-healer", title: "无籍游医", origin: { summary: `${identity}是因一纸旧案被逐出药谷的游医`, establishedFacts: [], openHooks: [], startingEntities: [] }, opening: { regionId: "qinglan", situation: "替渡口伤者诊治" } },
    { id: "route-lamplighter", title: "守灯人", origin: { summary: `${identity}替亡师守护一盏不灭命灯`, establishedFacts: [], openHooks: [], startingEntities: [] }, opening: { regionId: "qixia", situation: "追踪栖霞岭中的命灯异动" } },
  ];
  return routes.map((route) => ({ ...route, opening: { ...route.opening, scene: {
    title: "命卷初开",
    narrative: `天光穿过薄雾。${route.origin.summary}。\n\n${route.opening.situation}，船家收起缆绳，问你准备去往何处。你尚未作出选择。`,
    choices: [
      { title: "询问前路", description: "先向船家问清附近的去处。" },
      { title: "留意渡口", description: "看看周围正在发生什么。" },
    ],
  } } }));
}

function createSave(draft: StoryCreateSave): DemoSave {
  const race = raceName(draft.identity.raceId);
  const replaceIndex = draft.replaceSaveId ? demoState.saves.findIndex((save) => save.saveId === draft.replaceSaveId) : -1;
  const id = replaceIndex >= 0 ? demoState.saves[replaceIndex].saveId : `demo-${Date.now()}`;
  const save = makeSave({
    id,
    slotNo: replaceIndex >= 0 ? replaceIndex + 1 : demoState.saves.length + 1,
    name: draft.identity.name,
    race,
    gender: draft.identity.genderCode,
    turn: 1,
    location: draft.opening.regionId === "qixia" ? "栖霞岭" : "青岚渡",
    sceneTitle: "初入尘世",
  });
  save.revision = 1;
  save.state.protagonist.origin = draft.origin.summary;
  save.state.protagonist.storyPreference = draft.playPreference.additionalNote || "由玩家选择的游玩偏好";
  save.state.sceneTitle = draft.opening.scene?.title ?? "命卷初开";
  save.state.narrative = draft.opening.scene?.narrative
    ?? `天光穿过薄雾，落在${save.state.currentLocation}的旧石阶上。\n${draft.origin.summary}。\n你在此世睁开眼，往事与未定的道路同时铺陈在面前。`;
  if (draft.opening.scene) {
    save.state.choices = draft.opening.scene.choices.map((choice, index) => ({ ...choice, id: `opening-choice-${index + 1}` }));
  }
  save.state.memories = [{ id: `opening-${id}`, title: "命卷初开", summary: `${draft.identity.name}在${save.state.currentLocation}正式入世。`, worldTime: save.state.worldTime, importance: "KEY" }];
  return save;
}

function advanceTurn(save: DemoSave, action: string) {
  save.revision += 1;
  save.turnNumber += 1;
  save.state.turnNumber = save.turnNumber;
  save.state.worldTime = { ...save.state.worldTime, day: save.state.worldTime.day + 1, phase: "AFTERNOON" };
  save.state.sceneTitle = "雾中回音";
  save.state.narrative = `你没有让眼前的局势替自己作答。\n你选择了：${action.replace(/：.*$/, "")}。\n晏迟沉默片刻，将一封被雨水浸过的旧信放在石栏上。信封背面，正是与铜令相同的云纹。\n渡船轻轻撞上木桩，对岸的钟声从雾里传来。`;
  save.state.choices = [
    { id: "read-letter", title: "当面拆开旧信", description: "先确认信中内容，再决定是否登船。" },
    { id: "ask-token", title: "亮出云纹铜令", description: "观察晏迟是否认得这枚铜令。" },
    { id: "board-boat", title: "收起旧信，登上渡船", description: "暂时接受引路，把问题带到对岸。" },
  ];
  save.state.memories.push({
    id: `turn-${save.turnNumber}`,
    title: "雾中旧信",
    summary: `${save.state.protagonist.name}选择“${action.replace(/：.*$/, "")}”，晏迟交出一封带有云纹的旧信。`,
    worldTime: save.state.worldTime,
    importance: "KEY",
  });
  demoState.generation.platform.usedTurns += 2;
  demoState.generation.platform.remainingTurns = Math.max(0, demoState.generation.platform.turnLimit - demoState.generation.platform.usedTurns);
  persist();
}

async function waitForLocalResponse(path: string, method: string) {
  const duration = path.endsWith("/turns") ? 760 : path === "/api/v1/story/saves" && method === "POST" ? 950 : 120;
  await new Promise((resolve) => window.setTimeout(resolve, duration));
}

/**
 * 仅供开发环境连续体验使用。页面仍走真实 PlayerApp 与 gameApi 契约，
 * 这里替代的是 HTTP 响应，不代表真实 Java、数据库或模型已经启动。
 */
export async function localDemoRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const url = new URL(path, window.location.origin);
  const pathname = url.pathname;
  await waitForLocalResponse(pathname, method);

  if (pathname === "/api/v1/auth/access-status") {
    return clone({ restricted: false, title: "", message: "", resumeAt: "" }) as T;
  }
  if (pathname === "/api/v1/auth/me") return clone(authenticated()) as T;
  if (pathname === "/api/v1/auth/login" && method === "POST") {
    const body = parseBody<{ email: string }>(init);
    demoState.authenticatedEmail = body.email;
    demoState.passwordConfigured = true;
    persist();
    return clone(authenticated(false)) as T;
  }
  if (pathname === "/api/v1/auth/register" && method === "POST") {
    const body = parseBody<{ email: string }>(init);
    demoState = { ...initialState(), authenticatedEmail: body.email, saves: [] };
    persist();
    return clone(authenticated(true)) as T;
  }
  if (pathname === "/api/v1/auth/password" && method === "POST") {
    demoState.passwordConfigured = true;
    persist();
    return clone(authenticated()) as T;
  }
  if (pathname === "/api/v1/auth/logout" && method === "POST") {
    demoState.authenticatedEmail = null;
    persist();
    return undefined as T;
  }
  if (pathname === "/api/v1/auth/account" && method === "DELETE") {
    demoState = { ...initialState(), saves: [] };
    persist();
    return undefined as T;
  }

  if (pathname === "/api/v1/release-notes/public-latest") return clone(releaseNote) as T;
  if (pathname === "/api/v1/release-notes/pending") return null as T;
  if (pathname === "/api/v1/release-notes") return clone([{ ...releaseNote, unread: false }]) as T;
  if (/^\/api\/v1\/release-notes\/[^/]+\/read$/.test(pathname) && method === "POST") {
    demoState.readReleaseIds.push(decodeURIComponent(pathname.split("/")[4]));
    persist();
    return undefined as T;
  }

  if (pathname === "/api/v1/character-names/generate" && method === "POST") {
    const body = parseBody<{ race: string; exclude: string[] }>(init);
    const candidates = body.race === "DEMON" ? ["祁月", "闻溪", "照野"] : body.race === "SPIRIT" ? ["镜微", "砚生", "长明"] : ["沈照", "顾长川", "陆沉舟"];
    const available = candidates.filter((name) => !body.exclude?.includes(name));
    return clone({ name: available[0] ?? candidates[0], alternatives: available.slice(1).concat(candidates).slice(0, 3) }) as T;
  }
  if (pathname === "/api/v1/character-names/validate" && method === "POST") {
    const body = parseBody<{ name: string }>(init);
    const normalizedName = body.name.trim();
    return clone({ valid: normalizedName.length >= 2 && normalizedName.length <= 12, normalizedName,
      message: normalizedName.length >= 2 && normalizedName.length <= 12 ? "" : "角色名需要 2—12 个字符" }) as T;
  }

  if (pathname === "/api/v1/story/bootstrap") {
    return clone({
      releaseVersion: "本地连续演示",
      maxActiveSaves: 4,
      defaultEngine: { code: "trusted-story", version: 1, stateSchemaVersion: 1 },
      creation: {
        world: worldPackage,
        playPreferences: {
          focus: [
            { code: "RELATION", name: "人物关系", description: "更关注人物选择与承诺。" },
            { code: "EXPLORE", name: "探索见闻", description: "更关注地域、线索和世界变化。" },
            { code: "CULTIVATE", name: "修行成长", description: "更关注功法、境界和代价。" },
          ],
          combatFrequency: [{ code: "MEDIUM", name: "适中", description: "战斗服务于剧情。" }],
          pace: [{ code: "STEADY", name: "从容", description: "允许人物关系自然展开。" }],
          guidance: [{ code: "LOW", name: "少引导", description: "保留更多自由尝试。" }],
          defaultFocus: ["RELATION", "EXPLORE"],
          defaultCombatFrequency: "MEDIUM",
          defaultPace: "STEADY",
          defaultGuidance: "LOW",
        },
      },
      capabilities: [],
    }) as T;
  }
  if (pathname === "/api/v1/story/character-routes" && method === "POST") {
    return clone({ routes: createRoutes(parseBody<StoryCharacterRouteRequest>(init)) }) as T;
  }

  if (pathname === "/api/v1/generation/settings") {
    if (method === "PUT") {
      const body = parseBody<{ mode: "PLATFORM" | "PERSONAL"; provider?: string; model?: string; tierCode?: "LIGHT" | "BALANCED" | "DEEP" }>(init);
      demoState.generation.mode = body.mode;
      if (body.mode === "PLATFORM") {
        const selected = body.tierCode ?? "BALANCED";
        demoState.generation.selectedPlatformTier = selected;
        demoState.generation.platformTiers = demoState.generation.platformTiers?.map((tier) => ({ ...tier, selected: tier.code === selected }));
        demoState.generation.selectedPersonalProvider = null;
        demoState.generation.selectedPersonalModel = null;
      } else {
        demoState.generation.selectedPersonalProvider = body.provider ?? "personal";
        demoState.generation.selectedPersonalModel = body.model ?? "default";
      }
      persist();
    }
    return clone(demoState.generation) as T;
  }
  const credentialMatch = pathname.match(/^\/api\/v1\/generation\/providers\/([^/]+)\/credential$/);
  if (credentialMatch) {
    const provider = decodeURIComponent(credentialMatch[1]);
    const configured = method !== "DELETE";
    demoState.generation.personalProviders = demoState.generation.personalProviders.map((item) => item.code === provider
      ? { ...item, credentialConfigured: configured, credentialStatus: configured ? "VERIFIED" : null,
        maskedSuffix: configured ? "••••demo" : null, lastVerifiedAt: configured ? new Date().toISOString() : null }
      : item);
    if (configured) {
      demoState.generation.mode = "PERSONAL";
      demoState.generation.selectedPersonalProvider = provider;
      demoState.generation.selectedPersonalModel = "default";
    } else {
      demoState.generation.mode = "PLATFORM";
      demoState.generation.selectedPersonalProvider = null;
      demoState.generation.selectedPersonalModel = null;
    }
    persist();
    return clone(demoState.generation) as T;
  }

  const turnRequestMatch = pathname.match(/^\/api\/v1\/story\/saves\/([^/]+)\/turn-requests\/([^/]+)$/);
  if (turnRequestMatch) {
    const save = findSave(decodeURIComponent(turnRequestMatch[1]));
    return clone({ requestId: decodeURIComponent(turnRequestMatch[2]), status: "COMMITTED", terminal: true, retryable: false,
      retryAfterMs: 0, message: "本地回合已完成", updatedAt: new Date().toISOString(), snapshot: save }) as T;
  }
  const turnMatch = pathname.match(/^\/api\/v1\/story\/saves\/([^/]+)\/turns$/);
  if (turnMatch && method === "POST") {
    const save = findSave(decodeURIComponent(turnMatch[1]));
    const body = parseBody<{ action: string; expectedRevision: number }>(init);
    if (body.expectedRevision !== save.revision) throw new ApiError("命卷版本已经变化，请重新查看", 409, "STATE_CONFLICT");
    advanceTurn(save, body.action);
    return clone(save) as T;
  }

  const correctionPreviewMatch = pathname.match(/^\/api\/v1\/story\/saves\/([^/]+)\/corrections\/preview$/);
  if (correctionPreviewMatch && method === "POST") {
    const saveId = decodeURIComponent(correctionPreviewMatch[1]);
    findSave(saveId);
    const body = parseBody<{ instruction: string }>(init);
    demoState.pendingCorrection = { saveId, instruction: body.instruction, token: `demo-correction-${Date.now()}` };
    persist();
    return clone({ kind: "FACT", explanation: `将按你的说明核对：${body.instruction}`, confirmationPrompt: "确认把这项修正写入当前命卷吗？", proposalToken: demoState.pendingCorrection.token }) as T;
  }
  const correctionConfirmMatch = pathname.match(/^\/api\/v1\/story\/saves\/([^/]+)\/corrections\/confirm$/);
  if (correctionConfirmMatch && method === "POST") {
    const save = findSave(decodeURIComponent(correctionConfirmMatch[1]));
    if (!demoState.pendingCorrection || demoState.pendingCorrection.saveId !== save.saveId) throw new ApiError("修正预览已经失效", 409, "CORRECTION_PREVIEW_EXPIRED");
    save.revision += 1;
    save.state.narrative = `${save.state.narrative}\n命卷补记：${demoState.pendingCorrection.instruction}`;
    demoState.pendingCorrection = null;
    persist();
    return clone(save) as T;
  }
  const entityMatch = pathname.match(/^\/api\/v1\/story\/saves\/([^/]+)\/entities\/([^/]+)$/);
  if (entityMatch && method === "DELETE") {
    const save = findSave(decodeURIComponent(entityMatch[1]));
    const entityId = decodeURIComponent(entityMatch[2]);
    save.state.inventory = save.state.inventory.filter((item) => item.id !== entityId);
    save.state.characters = save.state.characters.map((person) => person.id === entityId ? { ...person, tracked: false } : person);
    save.revision += 1;
    persist();
    return clone(save) as T;
  }

  if (pathname === "/api/v1/story/saves") {
    if (method === "POST") {
      const draft = parseBody<StoryCreateSave>(init);
      const created = createSave(draft);
      const replaceIndex = draft.replaceSaveId ? demoState.saves.findIndex((save) => save.saveId === draft.replaceSaveId) : -1;
      if (replaceIndex >= 0) demoState.saves.splice(replaceIndex, 1, created);
      else demoState.saves.push(created);
      persist();
      return clone(created) as T;
    }
    return clone(demoState.saves.map(summary)) as T;
  }

  const saveMatch = pathname.match(/^\/api\/v1\/story\/saves\/([^/]+)$/);
  if (saveMatch) {
    const saveId = decodeURIComponent(saveMatch[1]);
    if (method === "DELETE") {
      demoState.saves = demoState.saves.filter((save) => save.saveId !== saveId);
      persist();
      return undefined as T;
    }
    return clone(findSave(saveId)) as T;
  }

  throw new ApiError(`本地演示尚未覆盖此操作：${method} ${pathname}`, 501, "LOCAL_DEMO_NOT_IMPLEMENTED", {}, {
    category: "SYSTEM", title: "此操作尚未加入本地演示", suggestions: ["可继续体验其他页面"],
    retryable: false, turnConsumed: false, stateChanged: false,
  });
}
