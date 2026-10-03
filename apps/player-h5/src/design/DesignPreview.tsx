import { useMemo, useState } from "react";
import { ArrowLeftIcon, ChevronDownIcon, Cross2Icon, EnterIcon, Pencil1Icon } from "@radix-ui/react-icons";
import type { CharacterDraft, StoryCharacterRoute, StoryCorrectionPreview, StoryGenerationSettings, StoryWorldPackageOption } from "../game-api";
import type { PlayerBootstrapView, PlayerSaveSummary, PlayerStoryView } from "../app/player-read-model";
import { DestinyLoadingOverlay, LoadingView, TabIcon, type TabId } from "../app/AppViews";
import { AdventureView, CharacterView, ChronicleView } from "../features/gameplay/GameViews";
import { CharacterCreationView } from "../features/onboarding/CharacterOnboardingViews";
import { DeathView } from "../features/saves/LifecycleViews";
import { GenerationAccessCard, SaveSelectionView } from "../features/saves/SaveViews";
import { ReleaseAnnouncementDialog, SettingsView, StoryCorrectionDialog } from "../features/settings/SettingsViews";
import type { UiProblem } from "../shared/problem";

const pages = new Set([
  "loading", "saves", "create", "create-route", "create-review", "create-loading", "adventure", "pending", "error",
  "character", "chronicle", "death", "settings", "dialog",
]);

const story: PlayerStoryView = {
  phase: "playing",
  saveId: "preview-save",
  sceneTitle: "渡口故人",
  currentLocation: "青岚渡",
  worldTime: { year: 317, day: 42, phase: "MORNING" },
  turn: 18,
  narrative: [
    "雨势稍歇，渡口的青石仍泛着冷光。",
    "你从残碑旁醒来，袖中多了一枚刻着云纹的铜令。",
    "江面浮着薄雾，摆渡人垂手立在船头，像是已经等了很久。",
    "木桥尽头的黑衣人没有催促，只抬眼望向你。",
  ],
  pendingDecision: null,
  choices: [
    { id: "ask", label: "先问清故人的名讳", hint: "不立即登船，确认来人的身份与铜令来历。" },
    { id: "follow", label: "跟随对方进入渡口", hint: "接受眼前线索，把答案留到对岸。" },
    { id: "watch", label: "暂不回应，观察四周", hint: "留在原地，判断渡口中还有谁在关注你。" },
  ],
  world: {
    code: "xuanxu",
    title: "玄墟界",
    summary: "仙门、凡城与荒域共享一条持续推进的时间线。每一次承诺、失去与选择都会留在此世。",
    cultivationLaw: "修行来自经历、机缘与代价，境界不能凭空获得。",
    openingRegion: "青岚渡及其相连的南境水路",
  },
  player: {
    name: "沈照",
    race: "HUMAN",
    gender: "男",
    identity: "失去三年记忆的散修",
    origin: "三年前于青岚山失踪，醒来时只带着一柄断剑。",
    lifeGoal: "查清失忆与师门覆灭的真相",
    storyPreference: "偏重人物关系与探索，战斗适中",
    realm: "炼气境初期",
    condition: "旧伤未愈",
  },
  abilities: [
    { id: "a1", name: "听风诀", type: "TECHNIQUE", stage: "初窥", source: "残缺师门心法", description: "能从细微风声中分辨近处异动。" },
    { id: "a2", name: "剑骨余鸣", type: "TALENT", stage: "未明", source: "青岚山旧事", description: "接近与旧剑有关的事物时，伤处会出现异常感应。" },
    ...["青元剑法", "敛息术", "踏雾步", "照水观心", "引气归元", "断潮一式", "辨药诀", "听雨入定", "护脉真息", "旧剑感应"].map((name, index) => ({
      id: `a${index + 3}`,
      name,
      type: index % 4 === 1 ? "SPELL" as const : "TECHNIQUE" as const,
      stage: ["入门", "初窥", "熟练", "未明"][index % 4],
      source: ["青岚山残卷", "渡口旧闻", "行路所得"][index % 3],
      description: ["仍在修习中的一段法门。", "在最近的经历中逐渐显露作用。", "需要更多实践才能确认全部边界。"][index % 3],
    })),
  ],
  inventory: [
    { id: "i1", name: "云纹铜令", category: "CLUE", status: "出现新裂纹", quantity: 1, quantityKnown: true, description: "来历不明，裂纹正指向江心。" },
    { id: "i2", name: "无鞘断剑", category: "WEAPON", status: "无法出鞘", quantity: 1, quantityKnown: true, description: "从青岚山带出的唯一旧物。" },
    ...["渡口船票", "旧药囊", "青瓷小瓶", "残缺地图", "黑绳木牌", "避水符", "干粮包", "雨蓑", "火折子", "止血散", "白石碎片", "无字信笺", "铜制罗盘", "细麻绳", "山道腰牌", "薄刃短刀", "空灵石匣", "旧门钥匙", "竹叶茶包", "墨迹拓片", "渡河凭证", "松脂火种", "灰布斗篷", "青岚石印"].map((name, index) => ({
      id: `i${index + 3}`,
      name,
      category: ["CLUE", "SUPPLY", "TOOL", "MATERIAL"][index % 4],
      status: ["妥善收起", "尚待查验", "仍可使用"][index % 3],
      quantity: index % 5 === 0 ? 2 : 1,
      quantityKnown: true,
      description: ["在此前的行程中留下，来历已有记录。", "目前仍随身携带，后续可能派上用场。", "外观普通，但与最近的线索有所关联。"][index % 3],
    })),
  ],
  characters: [
    { id: "c1", name: "晏迟", identity: "黑衣引路人", state: "等待你的答复", location: "青岚渡木桥", relation: "自称受故人所托" },
    { id: "c2", name: "陆翁", identity: "摆渡人", state: "沉默观望", location: "渡船船头", relation: "态度不明" },
    ...["柳青儿", "周老", "祁越", "闻溪", "顾长川", "叶小满", "谢停云", "沈砚", "苏晚照", "宁归舟", "赵三娘", "裴照野", "林鹤眠", "秦无咎", "白芷", "江淮", "许观澜", "杜衡", "唐栖梧", "孟知微", "陆沉", "温如晦", "乔木生", "商南星", "姚渡", "程不言", "宋怀真", "陈照夜", "萧十七", "楚问津", "顾清商", "韩松声", "罗青崖", "孟春水", "齐望舒", "卫临川", "祝长安", "谢寒灯", "裴归鸿", "陶九", "贺兰生"].map((name, index) => ({
      id: `c${index + 3}`,
      name,
      identity: ["旧识", "行脚修士", "渡口商旅", "地方住民"][index % 4],
      state: ["近况未明", "仍在留意此事", "与你暂时分别", "等待下一次相见"][index % 4],
      location: ["青岚渡", "白石镇", "南境水路", "栖霞岭"][index % 4],
      relation: ["曾有一面之缘", "与你交换过消息", "对你的来历有所怀疑"][index % 3],
    })),
  ],
  events: [
    { id: "e1", title: "渡口来人", detail: "黑衣人晏迟持故人口信出现在青岚渡，邀请你登船。", at: { year: 317, day: 42, phase: "MORNING" }, kind: "world" },
    { id: "e2", title: "铜令生变", detail: "云纹铜令出现裂纹，并隐约指向江心。", at: { year: 317, day: 42, phase: "DAWN" }, kind: "gain" },
    { id: "e3", title: "拒绝交出断剑", detail: "你拒绝了白石镇巡守的盘问，决定自行保管师门断剑。", at: { year: 317, day: 39, phase: "DUSK" }, kind: "decision" },
    { id: "e4", title: "旧伤恶化", detail: "强行运转残缺心法后，胸口旧伤再次裂开。", at: { year: 317, day: 38, phase: "NIGHT" }, kind: "risk" },
  ],
};

const saves: PlayerSaveSummary[] = [
  { saveId: "preview-save", slotNo: 1, playerName: "沈照", race: "HUMAN", realm: "炼气境初期", currentLocation: "青岚渡", turn: 18, updatedAt: "2026-08-30T12:00:00Z" },
  { saveId: "preview-save-2", slotNo: 2, playerName: "祁月", race: "YAO", realm: "启灵境中期", currentLocation: "栖霞岭", turn: 7, updatedAt: "2026-08-29T08:00:00Z" },
];

const generation: StoryGenerationSettings = {
  mode: "PLATFORM",
  platform: { turnLimit: 100, usedTurns: 38, remainingTurns: 62, available: true },
  platformTiers: [
    { code: "LIGHT", name: "轻量", description: "适合日常推进", pointCost: 1, selected: false },
    { code: "BALANCED", name: "均衡", description: "兼顾叙事与状态", pointCost: 2, selected: true },
    { code: "DEEP", name: "深入", description: "用于关键长回合", pointCost: 4, selected: false },
  ],
  selectedPlatformTier: "BALANCED",
  personalProviders: [],
  selectedPersonalProvider: null,
  selectedPersonalModel: null,
};

const previewProblem: UiProblem = {
  category: "SYSTEM",
  title: "本次行动没有写入命卷",
  message: "连接中断，但这次行动与回合都没有被消耗。检查网络后，可以原样再试。",
  suggestions: ["也可以重新选择，或改用自定义行动"],
  retryable: true,
  turnConsumed: false,
  stateChanged: false,
};

const world: StoryWorldPackageOption = {
  packageId: "xuanxu:1",
  revision: 1,
  name: "玄墟界",
  premise: "凡人与修行者共同生活在一条不会为玩家停下的时间线上。",
  coreRules: ["境界提升必须来自已发生的经历", "人物只知道自己亲历或获知的事实", "承诺与伤亡会持续影响后续故事"],
  narrativeGuidance: "克制、连续、重视人物关系与真实代价。",
  races: [
    { raceId: "xuanxu:human", name: "人族", description: "寿数有限，善借传承与制度延续道路。", identityFacts: ["人族身份不额外赋予修为"], socialHooks: ["宗门、家族与城镇关系更紧密"] },
    { raceId: "xuanxu:yao", name: "妖族", description: "由山川草木或走兽启灵，必须亲自确定本相。", identityFacts: ["本相是不可被模型改写的身份事实"], socialHooks: ["不同地域对妖族态度各异"] },
    { raceId: "xuanxu:spirit", name: "灵族", description: "由器物、地脉或残念孕生，必须确定本源。", identityFacts: ["本源决定初始身份而非战力"], socialHooks: ["会被视作异类、祥瑞或禁忌"] },
  ],
  openingRegions: [
    { regionId: "qinglan", name: "青岚渡", description: "南境水陆交汇之地。" },
    { regionId: "qixia", name: "栖霞岭", description: "妖灵与山民共居的边岭。" },
  ],
  realmScale: {
    scaleId: "xuanxu-realms",
    revision: 1,
    phases: ["EARLY", "MIDDLE", "LATE", "PERFECT"],
    ranks: ["L1", "L2"].map((rankKey, rankIndex) => ({
      rankKey,
      order: rankIndex + 1,
      raceRealms: [
        { raceId: "xuanxu:human", stageId: `${rankKey}:human`, name: rankIndex ? "筑基境" : "炼气境", description: "以经脉承载灵气。", capabilityBoundary: "", aliases: [] },
        { raceId: "xuanxu:yao", stageId: `${rankKey}:yao`, name: rankIndex ? "化形境" : "启灵境", description: "以本相感应天地。", capabilityBoundary: "", aliases: [] },
        { raceId: "xuanxu:spirit", stageId: `${rankKey}:spirit`, name: rankIndex ? "凝魄境" : "聚识境", description: "以本源凝聚灵识。", capabilityBoundary: "", aliases: [] },
      ],
    })),
  },
};

const bootstrap: PlayerBootstrapView = {
  version: "V0.16 design",
  capabilities: [],
  creation: {
    world,
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
};

const initialDraft: CharacterDraft = {
  playerName: "沈照",
  raceId: "xuanxu:human",
  gender: "MALE",
  raceIdentity: "",
  originPremise: "青岚山覆灭后失去三年记忆的散修",
  playPreference: { focus: ["RELATION", "EXPLORE"], combatFrequency: "MEDIUM", pace: "STEADY", guidance: "LOW", additionalNote: "" },
  worldPackageId: world.packageId,
  worldRevision: world.revision,
};

const routes: StoryCharacterRoute[] = [
  { id: "r1", title: "断剑归客", origin: { summary: "青岚山覆灭后失去三年记忆的散修", establishedFacts: [], openHooks: [], startingEntities: [] }, opening: { regionId: "qinglan", situation: "在残碑旁醒来" } },
  { id: "r2", title: "无籍游医", origin: { summary: "因一纸旧案被逐出药谷的游医", establishedFacts: [], openHooks: [], startingEntities: [] }, opening: { regionId: "qinglan", situation: "替渡口伤者诊治" } },
  { id: "r3", title: "守灯人", origin: { summary: "替亡师守护一盏不灭命灯的年轻修士", establishedFacts: [], openHooks: [], startingEntities: [] }, opening: { regionId: "qinglan", situation: "追踪命灯异动" } },
];

function PreviewHeader({ creationPage, page, onBack }: { creationPage: boolean; page: string; onBack: () => void }) {
  if (["adventure", "pending", "error", "character", "chronicle", "settings", "death"].includes(page)) return <div className="paper-background" aria-hidden="true" />;
  const showWordmark = ["loading", "saves"].includes(page);
  const contextTitle = ["adventure", "pending", "error"].includes(page)
    ? "故事"
    : page === "chronicle"
      ? "前情"
      : ["settings", "dialog"].includes(page)
        ? "设置"
        : creationPage
          ? "创建命卷"
          : "";
  return <><div className="paper-background" aria-hidden="true" /><header className="game-header"><div className="game-header-inner"><div className="app-header-leading">{creationPage && <button aria-label="返回命卷" className="header-back-button" onClick={onBack} type="button"><ArrowLeftIcon aria-hidden="true" /><span>命卷</span></button>}{showWordmark ? <span aria-label="问仙录" className="game-wordmark" role="img"><img alt="" aria-hidden="true" src="/assets/game/wenxian-wordmark-brush.png" /></span> : contextTitle ? <span className="app-context-title">{contextTitle}</span> : null}</div></div></header></>;
}

export function DesignPreviewApp({ initialPage }: { initialPage: string }) {
  const normalizedPage = initialPage === "world" ? "settings" : pages.has(initialPage) ? initialPage : "adventure";
  const [page, setPage] = useState(normalizedPage);
  const [draft, setDraft] = useState(initialDraft);
  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState("");
  const [fontSize, setFontSize] = useState<"compact" | "standard" | "large">("standard");
  const [selectedRouteId, setSelectedRouteId] = useState("r1");
  const [showCorrection, setShowCorrection] = useState(false);
  const [correctionPreview, setCorrectionPreview] = useState<StoryCorrectionPreview | null>(null);
  const adventurePage = ["adventure", "pending", "error"].includes(page);
  const settingsPage = ["settings", "dialog"].includes(page);
  const creationPage = ["create", "create-route", "create-review", "create-loading"].includes(page);
  const creationInitialStep = page === "create-route" ? "path" : ["create-review", "create-loading"].includes(page) ? "review" : "identity";
  const gamePage = adventurePage || ["character", "chronicle", "death"].includes(page) || settingsPage;
  const stage = page === "loading" ? "loading" : page === "saves" ? "saves" : creationPage ? "create" : "game";
  const game = useMemo(() => page === "death" ? { ...story, phase: "dead" as const } : story, [page]);
  const activeTab: TabId = page === "character" ? "character" : page === "chronicle" ? "chronicle" : settingsPage ? "settings" : "adventure";
  const selectTab = (tab: TabId) => setPage(tab);

  const headerless = ["adventure", "pending", "error", "character", "chronicle", "settings", "death"].includes(page);

  return <div className="game-shell" data-design-preview="true" data-entry={!gamePage} data-font-size={fontSize} data-headerless={headerless || undefined} data-stage={stage} data-tab={gamePage ? activeTab : undefined} data-theme="paper">
    <PreviewHeader creationPage={creationPage} onBack={() => setPage("saves")} page={page} />
    <div className="game-scroll" data-phase={game.phase} data-tab={gamePage ? activeTab : undefined}><main className="screen-content">
      {page === "loading" && <LoadingView />}
      {page === "saves" && <SaveSelectionView currentSaveId="preview-save" isBusy={false} maxActiveSaves={4} onCreate={() => undefined} onDelete={() => undefined} onLogout={() => undefined} onOpen={() => setPage("adventure")} saves={saves} />}
      {creationPage && <><GenerationAccessCard generation={generation} isBusy={page === "create-loading"} onOpen={() => undefined} /><CharacterCreationView alternatives={["沈照", "顾长川", "闻溪"]} bootstrap={bootstrap} draft={draft} error={null} initialStep={creationInitialStep} isBusy={page === "create-loading"} onCreate={() => setPage("create-loading")} onDraft={(patch) => setDraft((current) => ({ ...current, ...patch }))} onGenerateName={() => undefined} onGenerateRoutes={() => undefined} onLogout={() => undefined} onSelectRoute={(route) => { setSelectedRouteId(route.id); setDraft((current) => ({ ...current, originPremise: route.origin.summary })); }} routeOptions={routes} selectedRouteId={selectedRouteId} /></>}
      {adventurePage && <AdventureView actionPanel={page !== "pending" ? <section aria-labelledby="preview-custom-action" className="turn-action-zone" data-testid="turn-action-zone"><button aria-expanded={customOpen} aria-label="展开自定义行动，不采用推荐选项时，亲自描述下一步" className="turn-action-toggle" onClick={() => setCustomOpen((current) => !current)} type="button"><span className="turn-action-icon"><Pencil1Icon /></span><strong id="preview-custom-action">或，写下自己的行动</strong><ChevronDownIcon /></button>{customOpen && <form className="action-composer"><div className="action-composer-input"><textarea aria-label="自由行动" maxLength={300} onChange={(event) => setCustomText(event.currentTarget.value)} placeholder="例如：先不登船，向茶摊老板打听昨夜靠岸的黑篷船。" rows={4} value={customText} />{customText && <button aria-label="清空输入" className="clear-draft" onClick={() => setCustomText("")} type="button"><Cross2Icon /></button>}</div><div className="action-composer-footer"><span className="action-composer-meta">草稿会在切换选项后保留</span><button aria-label="确认此行动" className="act-button" disabled={!customText.trim()} type="button"><span>确认行动</span><EnterIcon /></button></div></form>}</section> : null} choices={story.choices} customActionActive={customOpen} error={page === "error" ? previewProblem : null} game={story} generation={generation} initialChoiceId={page === "error" ? "ask" : undefined} isBusy={page === "pending"} offline={false} onChoice={() => undefined} onChoiceMode={() => setCustomOpen(false)} pendingAction={page === "pending" ? { kind: "CHOICE", choiceId: "ask", label: "先问清故人的名讳" } : null} />}
      {page === "character" && <CharacterView game={story} isBusy={false} onCleanup={() => undefined} />}
      {page === "chronicle" && <ChronicleView game={story} onCorrection={() => { setCorrectionPreview(null); setShowCorrection(true); }} />}
      {settingsPage && <SettingsView email="player@example.com" fontSize={fontSize} generation={generation} isBusy={false} onDeleteAccount={() => undefined} onFontSize={setFontSize} onGenerationSettings={() => undefined} onLogout={() => undefined} onPasswordSettings={() => undefined} onPlatform={() => undefined} onReset={() => undefined} onSaves={() => setPage("saves")} passwordConfigured version="V0.16" />}
      {page === "death" && <DeathView game={game} isBusy={false} onChronicle={() => setPage("chronicle")} onLogout={() => undefined} onRebirth={() => setPage("saves")} />}
    </main></div>
    {gamePage && page !== "death" && <nav className="bottom-nav" aria-label="主要导航">{([['adventure','故事'],['character','人物'],['chronicle','前情'],['settings','设置']] as Array<[TabId,string]>).map(([id,label]) => <button aria-current={activeTab === id ? "page" : undefined} key={id} onClick={() => selectTab(id)} type="button"><TabIcon id={id} /><span>{label}</span></button>)}</nav>}
    {page === "create-loading" && <DestinyLoadingOverlay name={draft.playerName} />}
    {page === "dialog" && <ReleaseAnnouncementDialog note={{ id: "preview-note", version: "V0.16", title: "命卷可信状态更新", content: "人物、物品与世界时间现在沿统一命卷延续。\n本次更新不会改写已有经历。", publishedAt: "2026-08-30T00:00:00Z", unread: true }} onClose={() => setPage("settings")} />}
    {showCorrection && <StoryCorrectionDialog error={null} isBusy={false} onCancel={() => { setCorrectionPreview(null); setShowCorrection(false); }} onConfirm={() => { setCorrectionPreview(null); setShowCorrection(false); }} onEdit={() => setCorrectionPreview(null)} onPreview={() => setCorrectionPreview({ kind: "FACT", explanation: "将误记的人物状态改为已离世。", confirmationPrompt: "确认写入这项修正，并保留原有纪事？", proposalToken: "design-preview" })} preview={correctionPreview} />}
  </div>;
}
