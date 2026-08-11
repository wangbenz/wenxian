import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import {
  ArrowRightIcon,
  ChevronRightIcon,
  ClockIcon,
  Cross2Icon,
  EnterIcon,
  GearIcon,
  GlobeIcon,
  HomeIcon,
  LightningBoltIcon,
  MagnifyingGlassIcon,
  PersonIcon,
  ReaderIcon,
  ReloadIcon,
  SewingPinFilledIcon,
  TrashIcon,
} from "@radix-ui/react-icons";
import {
  ApiError,
  gameApi,
  type ApiSnapshot,
  type AiSettings,
  type AccessStatus,
  type AuthResponse,
  type BootstrapResponse,
  type CharacterDraft,
  type Choice,
  type GameState,
  type ReleaseNote,
  type SaveSummary,
  type SyncMode,
  type StoryBootstrap,
  type StoryCharacterRoute,
  type StoryCorrectionPreview,
  type StoryGenerationSettings,
  type StorySaveResponse,
} from "./game-api";

type TabId = "adventure" | "character" | "chronicle" | "world";
type AppStage = "loading" | "login" | "saves" | "create" | "fate" | "talent" | "game";
type ThemeMode = "system" | "paper" | "night";
type FontSizeMode = "compact" | "standard" | "large";
type TurnLimitNotice = { message: string; contactText: string; supportGroup: string };
type MaintenanceNotice = Pick<AccessStatus, "title" | "message" | "resumeAt">;
type CleanupTarget = { kind: "item" | "character"; id: string; name: string };
type UiProblem = {
  code?: string;
  category: string;
  title: string;
  message: string;
  suggestions: string[];
  retryable: boolean;
  turnConsumed: boolean;
  stateChanged: boolean;
  referenceId?: string;
};

const CACHE_KEY = "wenxian-readonly-cache-v2";
const THEME_KEY = "wenxian-theme";
const FONT_SIZE_KEY = "wenxian-font-size";
const DEFAULT_DRAFT: CharacterDraft = {
  playerName: "",
  race: "",
  gender: "",
  lifeGoal: "LONGEVITY",
  difficulty: "NORMAL",
  originPremise: "",
  preference: "",
  worldCode: "XUANXU",
  customWorld: "",
};

const RACE_NAMES: Record<string, string> = { HUMAN: "人族", DEMON: "妖族", SPIRIT: "灵族" };
const STORY_RACE_CODES: Record<string, CharacterDraft["race"]> = { 人族: "HUMAN", 妖族: "DEMON", 灵族: "SPIRIT" };
const STORY_RACE_NAMES: Record<string, string> = { HUMAN: "人族", DEMON: "妖族", SPIRIT: "灵族" };
const STORY_GENDER_NAMES: Record<string, string> = { MALE: "男", FEMALE: "女", UNSPECIFIED: "不设定" };

function versionAtLeast(value: string | undefined, target: number) {
  const match = value?.match(/V?(\d+)\.(\d+)/i);
  return match ? Number(match[1]) * 100 + Number(match[2]) >= target : false;
}

function navigateEdition(edition?: "v013") {
  const url = new URL(window.location.href);
  if (edition) url.searchParams.set("edition", edition);
  else url.searchParams.delete("edition");
  window.location.assign(url.toString());
}

function storyBootstrapView(source: StoryBootstrap): BootstrapResponse {
  const first = source.worlds.find((world) => !world.custom) ?? source.worlds[0];
  return {
    gameName: "问仙录", version: source.version,
    world: {
      id: first?.code ?? "XUANXU", title: first?.name ?? "问仙诸界", era: "一卷一世界",
      summary: first?.premise ?? "每一方世界，都有一种关于仙的答案。",
      cultivationLaw: first?.cultivationStyle ?? "修行自有来处与代价。", dangerRule: "世界不会围绕主角停转。",
      regions: source.worlds.filter((world) => !world.custom).map((world) => world.name), factions: [], generatedBy: "轻叙事引擎",
    },
    races: source.races.filter((race) => STORY_RACE_CODES[race]).map((race) => ({
      code: STORY_RACE_CODES[race] as "HUMAN" | "DEMON" | "SPIRIT", name: race,
      description: race === "人族" ? "寿数有限，传承与选择塑造一生。" : race === "妖族" ? "血脉与本相相伴，也受天地与族群影响。" : "由灵物、山川或执念生出灵智。",
      strengths: ["出身自由"], weaknesses: ["命数未知"], lifespan: "由世界与出身决定",
    })),
    lifeGoals: [
      { code: "LONGEVITY", name: "求长生", description: "追寻超脱寿数的道路" },
      { code: "KINSHIP", name: "护亲友", description: "不让所珍视之人被仙路夺走" },
      { code: "POWER", name: "掌命数", description: "拥有不再任人摆布的力量" },
      { code: "TRUTH", name: "问真相", description: "看清世界与成仙背后的秘密" },
      { code: "FREEDOM", name: "得自在", description: "不受宗门、血脉与天命拘束" },
    ],
    difficulties: [{ code: "NORMAL", name: "自在游历", description: "以故事与选择为主，不刻意追求残酷数值。" }, { code: "HARD", name: "险途问道", description: "代价更重，错误决定可能改变一生。" }],
    realmPath: [], worldOptions: source.worlds,
  };
}

function storySnapshotView(source: StorySaveResponse): ApiSnapshot {
  const state = source.state;
  const minutesPerYear = 360 * 1440;
  const inYear = state.worldTimeMinutes % minutesPerYear;
  const raceCode = STORY_RACE_CODES[state.protagonist.race] || state.protagonist.race;
  const mapped: GameState = {
    phase: "playing", difficulty: "NORMAL", saveId: state.saveId, sceneId: `v014-${state.turnNumber}`,
    sceneTitle: state.sceneTitle, currentLocation: state.currentLocation,
    worldYear: Math.floor(state.worldTimeMinutes / minutesPerYear) + 1,
    day: Math.floor(inYear / 1440) + 1, minute: inYear % 1440, turn: state.turnNumber,
    narrative: safeNarrativeParagraphs(state.narrative), pendingDecision: state.pendingDecision ?? null, signals: [],
    choices: state.choices.map((choice) => ({ id: choice.id, label: choice.title, hint: choice.description })),
    world: { id: state.world.code, title: state.world.name, era: "此生命卷", summary: state.world.premise,
      cultivationLaw: state.world.cultivationStyle, dangerRule: "人物、时间与得失会沿同一份命卷继续变化。",
      regions: [state.world.openingRegion], factions: [], generatedBy: "轻叙事引擎" },
    creationProfile: null, protagonistOptions: [], talentOptions: [], talentRerolled: false,
    player: { name: state.protagonist.name, race: raceCode, gender: state.protagonist.gender, age: 16, lifespan: 75,
      identity: state.protagonist.origin, origin: state.protagonist.origin, originPremise: "",
      lifeGoal: state.protagonist.lifeGoal, goal: state.protagonist.lifeGoal, burden: state.protagonist.storyTone,
      realm: state.protagonist.realm, constitution: state.condition, vitality: 75, silver: 0, insight: 0, suspicion: 0 },
    characters: state.characters.filter((person) => person.tracked !== false).map((person) => ({ id: person.id, name: person.name, identity: person.identity,
      state: person.status === "ALIVE" ? "尚在人世" : person.status === "DEAD" ? "已故" : person.status === "MISSING" ? "下落不明" : person.status || "近况未明",
      location: person.lastKnownLocation, relation: person.relationship, lastSeenAt: state.worldTimeMinutes })),
    events: [...state.memories].reverse().map((memory) => ({ id: memory.id, title: memory.title, detail: memory.summary,
      at: memory.worldTimeMinutes + minutesPerYear, kind: memory.importance === "KEY" ? "world" : "decision" })),
    threads: [], inventory: state.inventory.filter((item) => item.inPossession !== false).map((item) => ({
      id: item.id, definitionId: item.id, name: item.name, category: item.type, quality: item.status === "ACTIVE" ? "持有" : item.status || "仍在行囊中",
      quantity: item.quantity, quantityKnown: item.quantityKnown !== false,
      description: item.description, keyItem: false })),
    abilities: state.abilities.filter((ability) => ability.status !== "LOST").map((ability) => ({ id: ability.id,
      definitionId: ability.id, name: ability.name, type: ability.type === "TALENT" ? "TALENT" : ability.type === "SPELL" ? "SPELL" : "TECHNIQUE",
      stage: ability.status === "MASTERED" ? "已掌握" : ability.status, mastery: 0, source: ability.source,
      description: ability.description, rules: "", maxUses: 0, remainingUses: 0, resetRule: "NONE", lastResetDay: 0, sideEffect: "" })),
  };
  return { state: mapped, revision: source.revision, updatedAt: new Date().toISOString(), choices: mapped.choices,
    agent: { mode: source.generationMode === "PERSONAL" ? "自己的推演" : "命卷推演", authoritative: true, world: state.world.name }, actionResolution: null };
}

function safeNarrativeParagraphs(value: string): string[] {
  const text = (value || "").trim();
  const looksLikeRawPayload = /^[{[]/.test(text)
    && /"(?:narrative|choices|elapsedMinutes|rollingSummary)"\s*:/.test(text);
  if (looksLikeRawPayload) {
    return ["这一页命卷的文字排版出现异常，原始报文已被隐藏。你可以从下方选择行动继续历练。"];
  }
  const paragraphs = text.split(/\n+/).map((paragraph) => paragraph.trim()).filter(Boolean);
  return paragraphs.length > 0 ? paragraphs : ["这一页命卷只留下了模糊痕迹。"];
}

function problemFrom(error: unknown): UiProblem {
  if (!(error instanceof ApiError)) return {
    category: "SYSTEM", title: "命卷暂时无法响应",
    message: "本次操作没有完成，请稍后重试。", suggestions: ["稍后重新尝试"],
    retryable: true, turnConsumed: false, stateChanged: false,
  };
  const technical = /DeepSeek|Agent|Java|API|后端|模型/i.test(error.message);
  const system = [500, 502, 503, 504].includes(error.status) || error.category === "SYSTEM";
  return {
    code: error.code,
    category: error.category ?? (system ? "SYSTEM" : "INPUT"),
    title: error.title ?? (system ? "本次推演未能完成" : "本次操作未能完成"),
    message: technical ? "本次操作没有完成，命卷没有发生变化。" : error.message,
    suggestions: error.suggestions.length ? error.suggestions : system ? ["稍后重新尝试"] : [],
    retryable: error.retryable || [502, 503, 504].includes(error.status),
    turnConsumed: error.turnConsumed,
    stateChanged: error.stateChanged,
    referenceId: error.referenceId,
  };
}

function ProblemNotice({ problem, compact = false }: { problem: UiProblem; compact?: boolean }) {
  return <section className={compact ? "problem-notice compact" : "problem-notice"} data-category={problem.category} role="alert">
    <p className="eyebrow">{problem.category === "SYSTEM" ? "命卷推演中断" : "本次操作未完成"}</p>
    <h3>{problem.title}</h3>
    <p>{problem.message}</p>
    {!compact && <p className="problem-state">{problem.turnConsumed ? "本轮已计入回合" : "未消耗回合"} · {problem.stateChanged ? "部分变化已经记录" : "存档没有变化"}</p>}
    {problem.suggestions.length > 0 && <ul>{problem.suggestions.map((suggestion) => <li key={suggestion}>{suggestion}</li>)}</ul>}
    {problem.referenceId && <small>问题编号：<strong>{problem.referenceId}</strong></small>}
  </section>;
}

function cacheSnapshot(snapshot: ApiSnapshot) {
  window.localStorage.setItem(CACHE_KEY, JSON.stringify(snapshot));
}

function readCachedSnapshot(): ApiSnapshot | null {
  try {
    const value = window.localStorage.getItem(CACHE_KEY);
    return value ? JSON.parse(value) as ApiSnapshot : null;
  } catch {
    return null;
  }
}

function revealFormControl(target: HTMLElement, delay = 280) {
  window.setTimeout(() => {
    if (!target.isConnected || typeof target.scrollIntoView !== "function") return;
    target.scrollIntoView({ block: "center", behavior: "smooth" });
  }, delay);
}

function periodName(minute: number) {
  if (minute < 360) return "夜深";
  if (minute < 540) return "清晨";
  if (minute < 720) return "上午";
  if (minute < 840) return "正午";
  if (minute < 1020) return "午后";
  if (minute < 1140) return "黄昏";
  return "夜里";
}

function formatWorldTime(worldYear: number, day: number, minute: number) {
  return `世界历${worldYear}年 · 第${day}日 · ${periodName(minute)}`;
}

function formatEventTime(at: number) {
  const minutesPerYear = 360 * 1440;
  const year = Math.floor(at / minutesPerYear);
  const inYear = at % minutesPerYear;
  const day = Math.floor(inYear / 1440) + 1;
  const minute = inYear % 1440;
  return `${year}年·${day}日 ${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

function conditionName(vitality: number) {
  if (vitality <= 0) return "命数已尽";
  if (vitality <= 25) return "气息虚弱";
  if (vitality <= 45) return "疲惫难支";
  if (vitality <= 70) return "气息尚稳";
  return "精神尚佳";
}

function dangerName(danger: number) {
  if (danger <= 30) return "风平浪静";
  if (danger <= 55) return "暗流渐起";
  if (danger <= 75) return "危机逼近";
  return "凶险万分";
}

function TabIcon({ id }: { id: TabId }) {
  const icons = { adventure: HomeIcon, character: PersonIcon, chronicle: ReaderIcon, world: GlobeIcon };
  const Icon = icons[id];
  return <Icon aria-hidden="true" />;
}

function LoadingView() {
  return (
    <section className="entry-card loading-card" role="status">
      <span className="ink-spinner" />
      <p className="eyebrow">READING THE JADE SLIP</p>
      <h1>正在检索命卷</h1>
      <p>正在核对身份与命卷归属，已有经历不会因此改变。</p>
    </section>
  );
}

function DestinyLoadingOverlay({ stage }: { stage: AppStage }) {
  const copy = stage === "create"
    ? ["天机正在落笔", "正依据世界、种族与身份推演角色路线"]
    : stage === "fate" ? ["命格已被选中", "正在从血脉、出身与所求中寻找先天灵光"]
      : ["天赋正在落印", "世界时间将从你入世的这一刻开始流动"];
  return <div className="destiny-loading" role="status"><div className="destiny-orbit"><span /><i />问</div><p className="eyebrow">FATE IS UNFOLDING</p><strong>{copy[0]}</strong><small>{copy[1]}</small><div className="loading-ink-line"><i /></div></div>;
}

function LoginView({
  isBusy,
  error,
  offline,
  announcement,
  accessStatus,
  onAuthenticate,
}: {
  isBusy: boolean;
  error: UiProblem | null;
  offline: boolean;
  announcement: ReleaseNote | null;
  accessStatus: AccessStatus | null;
  onAuthenticate: (mode: "login" | "register", email: string, password: string) => void;
}) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [accessNoticeOpen, setAccessNoticeOpen] = useState(Boolean(accessStatus?.restricted));
  const mismatch = mode === "register" && confirmation.length > 0 && confirmation !== password;

  useEffect(() => {
    setAccessNoticeOpen(Boolean(accessStatus?.restricted));
    if (accessStatus?.restricted) setMode("login");
  }, [accessStatus?.restricted, accessStatus?.title, accessStatus?.message, accessStatus?.resumeAt]);

  return (
    <section className="entry-card login-card">
      <div className="entry-seal" aria-hidden="true">问</div>
      <p className="eyebrow">ENTER XUANXU REALM</p>
      <h1>{mode === "login" ? "续写你的命卷" : "领取一份新命卷"}</h1>
      <p className="entry-lead">{mode === "login" ? "使用邮箱与密码继续自己的单机存档。老用户首次登录时，初始密码为完整邮箱地址。" : "新账号将获赠平台推演回合，请填写长期可用的真实邮箱。"}</p>
      {announcement && <details className="entry-announcement entry-announcement-fold">
        <summary>
          <span>{announcement.version}</span>
          <strong>{announcement.title}</strong>
          <small className="fold-open">展开</small>
          <small className="fold-close">收起</small>
        </summary>
        <p>{announcement.content}</p>
      </details>}
      <div className="login-mode-switch" role="tablist" aria-label="登录方式">
        <button aria-selected={mode === "login"} onClick={() => { setMode("login"); setConfirmation(""); }} role="tab" type="button">登录</button>
        <button aria-selected={mode === "register"} disabled={Boolean(accessStatus?.restricted)} onClick={() => setMode("register")} role="tab" type="button">{accessStatus?.restricted ? "更新期间暂停注册" : "注册新账号"}</button>
      </div>
      <form
        className="entry-form"
        onSubmit={(event) => {
          event.preventDefault();
          onAuthenticate(mode, email.trim(), password);
        }}
      >
        <label>
          <span>邮箱</span>
          <input
            autoComplete="email"
            inputMode="email"
            onChange={(event) => setEmail(event.currentTarget.value)}
            placeholder="you@example.com"
            required
            type="email"
            value={email}
          />
        </label>
        <label>
          <span>密码</span>
          <input
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            minLength={mode === "register" ? 8 : 1}
            maxLength={mode === "register" ? 72 : 255}
            onChange={(event) => setPassword(event.currentTarget.value)}
            placeholder={mode === "login" ? "输入登录密码" : "至少 8 个字符"}
            required
            type="password"
            value={password}
          />
        </label>
        {mode === "register" && <label>
          <span>确认密码</span>
          <input autoComplete="new-password" minLength={8} maxLength={72} onChange={(event) => setConfirmation(event.currentTarget.value)} placeholder="再次输入密码" required type="password" value={confirmation} />
          {mismatch && <small className="field-error">两次输入的密码不一致</small>}
        </label>}
        <button className="primary-entry-button" disabled={isBusy || !email.trim() || (mode === "register" ? password.length < 8 || confirmation !== password : !password)} type="submit">
          {isBusy ? "正在验明身份…" : mode === "login" ? "进入问仙录" : "注册并领取命卷"}<ArrowRightIcon />
        </button>
      </form>
      {error && <ProblemNotice compact problem={error} />}
      {!error && offline && <p className="entry-error">命卷暂时无法开启，请稍后重试。</p>}
      <p className="entry-footnote">当前暂不发送验证码，请务必使用自己的真实邮箱。验证码功能上线后，无法接收邮件的账号可能无法找回。</p>
      {accessStatus?.restricted && accessNoticeOpen && <MaintenanceDialog closeLabel="测试账号登录" notice={accessStatus} onClose={() => setAccessNoticeOpen(false)} />}
    </section>
  );
}

function CharacterCreationView({
  bootstrap,
  draft,
  alternatives,
  isBusy,
  error,
  routeOptions,
  selectedRouteId,
  onDraft,
  onGenerateName,
  onGenerateRoutes,
  onSelectRoute,
  onCreate,
  onCancelRestart,
  onLogout,
  onLegacyArchive,
}: {
  bootstrap: BootstrapResponse;
  draft: CharacterDraft;
  alternatives: string[];
  isBusy: boolean;
  error: UiProblem | null;
  routeOptions: StoryCharacterRoute[];
  selectedRouteId: string;
  onDraft: (patch: Partial<CharacterDraft>) => void;
  onGenerateName: () => void;
  onGenerateRoutes: () => void;
  onSelectRoute: (route: StoryCharacterRoute) => void;
  onCreate: () => void;
  onCancelRestart?: () => void;
  onLogout: () => void;
  onLegacyArchive?: () => void;
}) {
  const nameInputRef = useRef<HTMLInputElement>(null);
  const isStoryMode = Boolean(bootstrap.worldOptions);

  const requestFates = () => {
    if (!draft.playerName.trim()) {
      nameInputRef.current?.focus();
      nameInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
    }
    if (isStoryMode && !draft.originPremise.trim()) onGenerateRoutes();
    else onCreate();
  };

  return (
    <div className="creation-view">
      <section className="world-intro-card compact-world-card">
        <p className="eyebrow">ONE NAME · MANY WORLDS</p>
        <div className="world-title-row">
          <div><h1>{bootstrap.world.title}</h1><span>{bootstrap.world.era}</span></div>
          <i data-mode="fixed">世界待择</i>
        </div>
        <p className="world-summary">{bootstrap.world.summary}</p>
        <div className="world-pill-row">{bootstrap.world.regions.map((region) => <span key={region}>{region}</span>)}</div>
      </section>

      <section className="creation-card">
        <div className="section-heading-copy">
          <p className="eyebrow">CHOOSE WHO YOU ARE</p>
          <h2>先择身份，再取姓名</h2>
          <span>性别与种族会影响系统生成的姓名风格，自定义姓名不受此限制。</span>
        </div>

        {bootstrap.worldOptions && <fieldset className="choice-fieldset identity-step">
          <legend><span>始</span> 世界</legend>
          <div className="race-grid world-choice-grid">{bootstrap.worldOptions.map((world) => <button aria-disabled={world.available === false} aria-pressed={draft.worldCode === world.code} className={world.available === false ? "world-option-locked" : undefined} data-selected={draft.worldCode === world.code} disabled={world.available === false} key={world.code} onClick={() => onDraft({ worldCode: world.code })} type="button"><strong>{world.name}{world.available === false && <em>后续开放</em>}</strong><span>{world.openingRegion}</span><p>{world.premise}</p><small>{world.available === false ? world.availabilityNote : world.cultivationStyle}</small></button>)}</div>
          {draft.worldCode === "CUSTOM" && <label className="preference-field custom-world-field"><span>自定世界设定 <small>至少二十个字</small></span><textarea maxLength={1200} onChange={(event) => onDraft({ customWorld: event.currentTarget.value })} placeholder="描述这个世界如何修行、各族如何相处，以及仙意味着什么……" rows={4} value={draft.customWorld ?? ""} /></label>}
        </fieldset>}

        <fieldset className="choice-fieldset identity-step">
          <legend><span>一</span> 性别</legend>
          <div className="segmented-control">
            {([["MALE", "男"], ["FEMALE", "女"], ["UNSPECIFIED", "不设定"]] as const).map(([code, label]) => (
              <button aria-pressed={draft.gender === code} data-selected={draft.gender === code} key={code} onClick={() => onDraft({ gender: code })} type="button">{label}</button>
            ))}
          </div>
        </fieldset>

        <fieldset className="choice-fieldset identity-step">
          <legend><span>二</span> 种族</legend>
          <div className="race-grid">
            {bootstrap.races.map((race) => (
              <button
                aria-pressed={draft.race === race.code}
                data-selected={draft.race === race.code}
                key={race.code}
                onClick={() => onDraft({ race: race.code })}
                type="button"
              >
                <strong>{race.name}</strong><span>{race.lifespan}</span><p>{race.description}</p>
                <small>长：{race.strengths.join("、")} · 短：{race.weaknesses.join("、")}</small>
              </button>
            ))}
          </div>
        </fieldset>

        <fieldset className="choice-fieldset identity-step">
          <legend><span>三</span> 姓名</legend>
          <div className="name-field-row">
            <label>
              <span>自定义或随机生成</span>
              <input
                ref={nameInputRef}
                maxLength={6}
                onChange={(event) => onDraft({ playerName: event.currentTarget.value })}
                placeholder="2—6个汉字"
                value={draft.playerName}
              />
            </label>
            <button disabled={isBusy} onClick={onGenerateName} type="button">
              <ReloadIcon />{isBusy ? "推演中…" : "随机雅名"}
            </button>
          </div>
          {(!draft.gender || !draft.race) && <p className="name-generation-hint">先选择性别与种族，命卷才能推演合适的名字。</p>}
          {alternatives.length > 0 && (
            <div className="name-alternatives" aria-label="备选姓名">
              {alternatives.map((name) => <button key={name} onClick={() => onDraft({ playerName: name })} type="button">{name}</button>)}
            </div>
          )}
        </fieldset>

        {!isStoryMode && <><div className="two-column-fields single-column-fields">
          <fieldset className="choice-fieldset identity-step">
            <legend><span>四</span> 此生所求</legend>
            <div className="goal-grid">
              {bootstrap.lifeGoals.map((goal) => (
                <button aria-pressed={draft.lifeGoal === goal.code} data-selected={draft.lifeGoal === goal.code} key={goal.code} onClick={() => onDraft({ lifeGoal: goal.code })} type="button">
                  <strong>{goal.name}</strong><small>{goal.description}</small>
                </button>
              ))}
            </div>
          </fieldset>
        </div>

        <fieldset className="choice-fieldset identity-step">
          <legend><span>五</span> 本世难度</legend>
          <div className="difficulty-grid">
            {bootstrap.difficulties.map((difficulty) => (
              <button aria-pressed={draft.difficulty === difficulty.code} data-selected={draft.difficulty === difficulty.code} key={difficulty.code} onClick={() => onDraft({ difficulty: difficulty.code })} type="button">
                <strong>{difficulty.name}</strong><small>{difficulty.description}</small>
              </button>
            ))}
          </div>
          <p className="difficulty-note">难度在本世开启后不会中途改变；重开命卷时可以重新选择。</p>
        </fieldset></>}

        {isStoryMode && <fieldset className="choice-fieldset identity-step character-route-step">
          <legend><span>四</span> 角色路线</legend>
          <p className="route-intro">系统会结合所选世界、种族、性别与姓名，推演三种不同的入世方式。路线只是开端，不会限制之后的自由行动。</p>
          <button className="route-generate-button" disabled={isBusy || !draft.race || !draft.gender || !draft.playerName.trim()} onClick={onGenerateRoutes} type="button"><ReloadIcon />{routeOptions.length ? "重新推演三条路线" : "推演三条角色路线"}</button>
          {routeOptions.length > 0 && <div className="character-route-grid">{routeOptions.map((route, index) => <button aria-pressed={selectedRouteId === route.id} data-selected={selectedRouteId === route.id} key={route.id} onClick={() => onSelectRoute(route)} type="button"><span>路线 {index + 1}</span><strong>{route.title}</strong><p>{route.origin}</p><small><b>入世</b>{route.opening}</small><small><b>倾向</b>{route.preference}</small></button>)}</div>}
        </fieldset>}

        <label className="preference-field origin-premise-field">
          <span>{isStoryMode ? "角色设定" : "角色根源"} <small>{isStoryMode ? "选择路线后仍可修改" : "必须遵守 · 可留空"}</small></span>
          <textarea
            maxLength={160}
            onChange={(event) => onDraft({ originPremise: event.currentTarget.value })}
            placeholder={draft.race === "SPIRIT" ? "例如：一块矿石在灵脉中孕育百年后生出灵智。" : draft.race === "DEMON" ? "例如：山中白狐幼崽，刚刚第一次化形。" : "例如：边城药农之子，自幼能看见常人忽略的灵光。"}
            rows={3}
            value={draft.originPremise}
          />
          <small className="field-explanation">这是角色不可随意改写的出身事实，但不能借此直接获得高阶修为或无条件力量。</small>
        </label>

        <label className="preference-field">
          <span>{isStoryMode ? "游玩偏好" : "命格偏好"} <small>可留空</small></span>
          <textarea
            maxLength={160}
            onChange={(event) => onDraft({ preference: event.currentTarget.value })}
            placeholder={isStoryMode ? "例如：喜欢探索奇遇与人物关系，希望少一些机械受难。" : "例如：低起点、宗门线、重谋略；命格只会在既定世界中展开。"}
            rows={3}
            value={draft.preference}
          />
        </label>

        {error && <ProblemNotice compact problem={error} />}
        <button className="primary-entry-button" disabled={isBusy} onClick={requestFates} type="button">
          {isBusy ? "天机正在落笔…" : isStoryMode ? draft.originPremise.trim() ? "开启此生命卷" : "先推演角色路线" : "生成三种命格"}<ArrowRightIcon />
        </button>
        {onCancelRestart && <button className="text-button" disabled={isBusy} onClick={onCancelRestart} type="button">返回原来的命卷</button>}
        <button className="text-button" disabled={isBusy} onClick={onLogout} type="button">退出当前邮箱</button>
        {onLegacyArchive && <button className="legacy-archive-link" disabled={isBusy} onClick={onLegacyArchive} type="button"><ReaderIcon />查看 V0.13 旧版命卷档案</button>}
      </section>
    </div>
  );
}

function FateSelectionView({
  game,
  selectedId,
  isBusy,
  error,
  onSelect,
  onStart,
  onRecreate,
}: {
  game: GameState;
  selectedId: string;
  isBusy: boolean;
  error: UiProblem | null;
  onSelect: (id: string) => void;
  onStart: () => void;
  onRecreate: () => void;
}) {
  const selected = game.protagonistOptions.find((option) => option.id === selectedId);
  return (
    <div className="onboarding-view">
      <section className="world-intro-card compact-world-card">
        <p className="eyebrow">THREE FATES · ONE TRUE TIMELINE</p>
        <div className="world-title-row"><div><h1>{game.world.title}</h1><span>{game.world.era}</span></div><i data-mode="divined">命数已定</i></div>
        <p className="world-summary">姓名与种族已定，选择其中一段出身后，命卷才会正式开始流动。</p>
      </section>
      <section className="fate-selection" aria-labelledby="fate-title">
        <div className="section-heading-copy"><p className="eyebrow">CHOOSE ONE LIFE</p><h2 id="fate-title">择一命格入世</h2><span>种族与根源已经确定，每一种命格拥有不同的身份、关系与未偿之债。</span></div>
        <div className="fate-grid" aria-busy={isBusy}>
          {game.protagonistOptions.map((option, index) => (
            <button aria-pressed={selectedId === option.id} className="fate-card" data-selected={selectedId === option.id} key={option.id} onClick={() => onSelect(option.id)} type="button">
              <span className="fate-number">命 {index + 1}</span>
              <div className="fate-name"><strong>{option.name}</strong><small>{RACE_NAMES[option.race] ?? option.race} · {option.age}岁 · {option.identity}</small></div>
              {option.originPremise && <span className="origin-anchor">本源 · {option.originPremise}</span>}
              <p>{option.origin}</p>
              <dl><div><dt>异处</dt><dd>{option.trait}</dd></div><div><dt>所求</dt><dd>{option.goal}</dd></div><div><dt>旧债</dt><dd>{option.burden}</dd></div></dl>
            </button>
          ))}
        </div>
      </section>
      {error && <ProblemNotice compact problem={error} />}
      <button className="open-scroll-button" disabled={!selected || isBusy} onClick={onStart} type="button">
        {isBusy ? "正在开启命卷…" : selected ? `以「${selected.name}」开启命卷` : "先选择一种命格"}<ArrowRightIcon />
      </button>
      <button className="text-button" disabled={isBusy} onClick={onRecreate} type="button">放弃本次命格，重新创建</button>
    </div>
  );
}

function TalentSelectionView({ game, selectedId, isBusy, error, onSelect, onEnter, onReroll, onSkip }: {
  game: GameState; selectedId: string; isBusy: boolean; error: UiProblem | null;
  onSelect: (id: string) => void; onEnter: () => void; onReroll: () => void; onSkip: () => void;
}) {
  const selected = game.talentOptions.find((talent) => talent.id === selectedId);
  return <div className="onboarding-view talent-view">
    <section className="world-intro-card compact-world-card talent-intro">
      <p className="eyebrow">AWAKENING · OPTIONAL GIFT</p><h1>灵光初醒</h1>
      <p className="world-summary">命格与血脉在入世前显出一缕异象。你可携带一种天赋，也可以一身轻松地走入人间。</p>
    </section>
    <section className="fate-selection"><div className="section-heading-copy"><p className="eyebrow">CHOOSE ONE OR NONE</p><h2>择一道天赋</h2><span>它只是命途的一缕偏向，得失要在入世之后才会显明。</span></div>
      <div className="talent-grid">{game.talentOptions.map((talent, index) => <button aria-pressed={selectedId === talent.id} className="talent-card" data-selected={selectedId === talent.id} key={talent.id} onClick={() => onSelect(talent.id)} type="button">
        <span className="talent-sigil">{["观", "听", "悟"][index] ?? "灵"}</span><div><h3>{talent.name}</h3><p>{talent.description}</p><small>此中玄妙，尚待你亲自印证。</small></div>
      </button>)}</div>
    </section>
    {error && <ProblemNotice compact problem={error} />}
    <button className="open-scroll-button" disabled={!selected || isBusy} onClick={onEnter} type="button">{isBusy ? "命卷正在落印…" : selected ? `携「${selected.name}」入世` : "先选择一种天赋"}<ArrowRightIcon /></button>
    <div className="talent-secondary-actions"><button disabled={isBusy || game.talentRerolled} onClick={onReroll} type="button"><ReloadIcon />{game.talentRerolled ? "本世已重新推演" : "免费重新推演一次"}</button><button disabled={isBusy} onClick={onSkip} type="button">不携天赋，直接入世</button></div>
  </div>;
}

function AdventureView({ game, choices, resolution, aiSettings, isBusy, offline, readOnly = false, error, onChoice, onSuggestion, onResolutionDismiss }: { game: GameState; choices: Choice[]; resolution?: ApiSnapshot["actionResolution"]; aiSettings: AiSettings | null; isBusy: boolean; offline: boolean; readOnly?: boolean; error: UiProblem | null; onChoice: (id: string) => void; onSuggestion: (action: string) => void; onResolutionDismiss: () => void }) {
  return (
    <div className="adventure-view">
      <section className="scene-heading" aria-labelledby="scene-title"><span className="leaf-mark" aria-hidden="true">〆</span><div><p className="eyebrow">当前场景</p><h1 id="scene-title">{game.sceneTitle}</h1><small className="scene-time"><ClockIcon />{formatWorldTime(game.worldYear, game.day, game.minute)}</small></div></section>
      <div className="ornament-line" aria-hidden="true"><span /></div>
      {readOnly && <section className="legacy-readonly-notice" role="status"><strong>旧版命卷 · 只读档案</strong><p>这段经历已经封存，可继续查阅剧情、角色、纪事与世界记录，但不能再行动、重开或修改。</p><button onClick={() => navigateEdition()} type="button">返回当前命卷</button></section>}
      <section className="narrative" aria-label="剧情">{game.narrative.map((paragraph, index) => <p data-beat={index > 0 && index % 3 === 0 ? "new" : undefined} key={`${index}-${paragraph}`}>{paragraph}</p>)}</section>
      {game.pendingDecision && <section className="pending-decision" aria-label="等待你的决定" role="status"><p className="eyebrow">由你决定</p><strong>{game.pendingDecision.prompt}</strong><small>命卷已经停在这里，不会替你作出这个选择。</small></section>}
      {game.signals.length > 0 && <section className="signal-strip" aria-label="局势变化">{game.signals.map((signal, index) => <span className={index % 2 ? "signal-danger" : "signal-safe"} key={`${index}-${signal}`}>{signal}</span>)}</section>}
      {resolution && <section className="action-resolution" data-result={resolution.result} role="status">
        <p className="eyebrow">本轮未发生 · {resolution.result === "CLARIFY" ? "行动待明" : "世界裁决"}</p>
        <h2>{resolution.title}</h2>
        <p>{resolution.reason}</p>
        <div className="resolution-suggestions" aria-label="可改写的行动">
          {resolution.suggestions.map((suggestion) => <button disabled={isBusy || offline || readOnly} key={suggestion} onClick={() => onSuggestion(suggestion)} type="button"><span><small>可改为</small>{suggestion}</span><ChevronRightIcon aria-hidden="true" /></button>)}
        </div>
        <button className="resolution-dismiss" disabled={isBusy} onClick={onResolutionDismiss} type="button">返回原计划</button>
      </section>}
      {isBusy && <AgentProgress />}
      {!resolution && <section className="choice-list" aria-busy={isBusy} aria-label="可选行动">
        {choices.map((choice, index) => (
          <button className="choice-card" data-tone={choice.tone ?? "default"} disabled={isBusy || offline || readOnly} key={choice.id} onClick={() => onChoice(choice.id)} type="button">
            <span className="choice-seal" aria-hidden="true">{index + 1}</span><span className="choice-copy"><strong>{choice.label}</strong><small>{choice.hint}</small></span><ChevronRightIcon aria-hidden="true" />
          </button>
        ))}
      </section>}
      {offline && !readOnly && !isBusy && <div className="agent-thinking offline-note" role="status"><LightningBoltIcon /><span>命卷暂时无法续写，已有经历仍可查阅。</span></div>}
      {error && !isBusy && <ProblemNotice problem={error} />}
      {aiSettings && !readOnly && <p className="daily-chapter-usage">{aiSettings.usingCustomApi ? "正在使用自己的推演密钥 · 不消耗赠送回合" : `当前账号回合 ${aiSettings.usedTurns}/${aiSettings.turnLimit}`}</p>}
    </div>
  );
}

function AgentProgress() {
  const phases = ["续读此前命卷", "推演人物与因果", "书写本回故事", "校验状态与时间线"];
  const [phase, setPhase] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => setPhase((current) => Math.min(phases.length - 1, current + 1)), 2200);
    return () => window.clearInterval(timer);
  }, []);
  return <section className="chapter-generating" role="status"><span className="ink-spinner" /><div><small>命卷推演中</small><strong>{phases[phase]}</strong><p>本轮会推进一段完整故事，完成后统一写入人物、天赋与世界时间。</p></div></section>;
}

function CharacterView({ game, isBusy, onCleanup }: { game: GameState; isBusy: boolean; onCleanup?: (target: CleanupTarget) => void }) {
  const [organizing, setOrganizing] = useState(false);
  const player = game.player;
  if (!player) return null;
  const inventory = game.inventory.filter((item) => item.category !== "CURRENCY");
  const copperWen = game.wallet?.copperWen ?? player.silver ?? 0;
  const mortalMoney = formatMortalMoney(copperWen);
  const spiritMoney = formatSpiritMoney(game.wallet);
  const hasStructuredWallet = game.wallet !== undefined || game.economyVersion !== undefined;
  const isLightStorySave = game.world.generatedBy === "轻叙事引擎";
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">CHARACTER RECORD</p><h1>角色</h1></div><div className="record-title-actions"><span className="turn-badge">第 {game.turn} 回</span>{isLightStorySave && onCleanup && <button className="record-manage-button" disabled={isBusy} onClick={() => setOrganizing((current) => !current)} type="button">{organizing ? "完成整理" : "整理记录"}</button>}</div></div>
      <section className="hero-card"><div className="hero-avatar hero-name-seal" aria-hidden="true">{player.name.slice(0, 1)}</div><div><h2>{player.name}</h2><p>{player.identity} · {player.realm} · {player.constitution}</p><small>{isLightStorySave ? `${RACE_NAMES[player.race] ?? player.race} · ${player.gender}` : `${player.age}/${player.lifespan}岁 · ${RACE_NAMES[player.race] ?? player.race}`}</small></div><span className="realm-stamp">{player.realm}</span></section>
      <section className="life-purpose-card">{player.origin && <p><strong>此身来处</strong>{player.origin}</p>}{player.originPremise && <p><strong>本源设定</strong>{player.originPremise}</p>}<p><strong>此生所求</strong>{player.goal}</p><p><strong>{isLightStorySave ? "游玩偏好" : "未偿旧债"}</strong>{player.burden}</p></section>
      <section className="record-section"><div className="section-label section-label-plain"><p className="eyebrow">CULTIVATION</p><h2>天赋与功法</h2><span>{game.abilities.length}</span></div><div className="ability-list">
        {game.abilities.map((ability) => <article className="ability-row" key={ability.id}><div><h3>{ability.name}<small>{ability.type === "TALENT" ? "天赋" : ability.type === "TECHNIQUE" ? "功法" : ability.type === "SPELL" ? "术法" : "异能"} · {ability.stage}</small></h3><p>{ability.description}</p><span>缘起：{ability.source}</span></div></article>)}
      </div></section>
      {hasStructuredWallet && <section className="record-section"><div className="section-label section-label-plain"><p className="eyebrow">POSSESSIONS</p><h2>随身财物</h2></div><div className="wallet-card">
        <p><span>凡俗钱财</span><strong>{mortalMoney}</strong></p>
        <p><span>修行资粮</span><strong>{spiritMoney}</strong></p>
        <small>凡俗钱财与灵石分别记账，只有剧情中真实发生的交易才会增减。</small>
      </div></section>}
      <section className="record-section"><div className="section-label section-label-plain"><p className="eyebrow">INVENTORY</p><h2>行囊</h2><span>{inventory.length}</span></div><div className="inventory-list">
        {inventory.map((item) => <article className="inventory-item" key={item.id}><div><h3>{item.name}<small>{item.quality} · {item.quantityKnown === false ? "数量未清点" : `×${item.quantity}`}</small></h3><p>{item.description}</p></div>{organizing && <button disabled={isBusy} onClick={() => onCleanup?.({ kind: "item", id: item.id, name: item.name })} type="button">移出行囊</button>}</article>)}
        {inventory.length === 0 && <p className="record-empty-copy">行囊中暂时没有需要记录的物品。</p>}
      </div></section>
      <section className="record-section"><div className="section-label section-label-plain"><p className="eyebrow">RELATIONSHIPS</p><h2>已登场角色</h2><span>{game.characters.length}</span></div><div className="character-list">
        {game.characters.map((character) => <article className="character-row" key={character.id}><div className="mini-avatar">{character.name.slice(0, 1)}</div><div><h3>{character.name}<small>{character.identity}</small></h3><p>{character.state}</p><span>{character.location} · {character.relation}</span></div>{organizing && <button className="character-untrack-button" disabled={isBusy} onClick={() => onCleanup?.({ kind: "character", id: character.id, name: character.name })} type="button">不再关注</button>}</article>)}
        {game.characters.length === 0 && <p className="record-empty-copy">当前没有需要持续关注的人物。</p>}
      </div></section>
    </div>
  );
}

function formatMortalMoney(value: number) {
  const total = Math.max(0, Math.floor(value || 0));
  const gold = Math.floor(total / 10000);
  const silver = Math.floor((total % 10000) / 1000);
  const copper = total % 1000;
  const parts = [gold > 0 ? `${gold}两金` : "", silver > 0 ? `${silver}两银` : "", copper > 0 || total === 0 ? `${copper}文` : ""];
  return parts.filter(Boolean).join(" · ");
}

function formatSpiritMoney(wallet: GameState["wallet"]) {
  if (!wallet) return "尚无灵石";
  const parts = [
    wallet.highSpiritStones > 0 ? `上品 ${wallet.highSpiritStones}` : "",
    wallet.middleSpiritStones > 0 ? `中品 ${wallet.middleSpiritStones}` : "",
    wallet.lowSpiritStones > 0 ? `下品 ${wallet.lowSpiritStones}` : "",
  ];
  return parts.filter(Boolean).join(" · ") || "尚无灵石";
}

function ChronicleView({ game }: { game: GameState }) {
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">ONE WORLD · ONE TIMELINE</p><h1>纪事</h1></div><ClockIcon className="title-icon" /></div>
      <section className="timeline-summary"><span>统一世界时</span><strong>{formatWorldTime(game.worldYear, game.day, game.minute)}</strong><p>所有人物状态、承诺与线索都沿着同一条世界时间线延续。</p></section>
      <section className="timeline" aria-label="事件时间线">{game.events.map((event, index) => <article className="timeline-event" data-kind={event.kind} key={event.id}><div className="timeline-time">{index === 0 ? "现在" : formatEventTime(event.at)}</div><div className="timeline-dot" /><div className="timeline-card"><h2>{event.title}</h2><p>{event.detail}</p><small>{event.kind === "risk" ? "风险变化" : event.kind === "gain" ? "获得线索" : event.kind === "decision" ? "玩家决策" : "世界事件"}</small></div></article>)}</section>
    </div>
  );
}

function DeathView({
  game,
  isBusy,
  onChronicle,
  onRebirth,
  onLogout,
  readOnly = false,
}: {
  game: GameState;
  isBusy: boolean;
  onChronicle: () => void;
  onRebirth: () => void;
  onLogout: () => void;
  readOnly?: boolean;
}) {
  const player = game.player;
  const finalEvent = game.death ?? game.events[0];
  return (
    <div className="death-view">
      <section className="death-card">
        <div className="death-seal" aria-hidden="true">终</div>
        <p className="eyebrow">THIS LIFE HAS ENDED</p>
        <h1>此世已终</h1>
        <p className="death-name">{player?.name ?? "无名之人"} · {player?.realm ?? "凡俗"} · 享年 {player?.age ?? 0} 岁</p>
        <div className="death-divider" aria-hidden="true"><span /></div>
        <blockquote>
          <strong>{finalEvent?.title ?? "命数已尽"}</strong>
          <p>{finalEvent?.detail ?? "这段人生已经走到尽头。"}</p>
        </blockquote>
        <dl className="death-stats">
          <div><dt>终局之地</dt><dd>{game.death?.location ?? game.currentLocation}</dd></div>
          <div><dt>历经回合</dt><dd>{game.death?.turn ?? game.turn}</dd></div>
          <div><dt>终局时刻</dt><dd>{formatWorldTime(game.worldYear, game.day, game.minute)}</dd></div>
        </dl>
        <p className="death-rule">死亡不会自动回档。转世将开启全新的命卷，本世则封存为前世记录。</p>
        <div className="death-actions">
          <button className="primary-entry-button" disabled={isBusy} onClick={onChronicle} type="button"><ReaderIcon />查看一生纪事</button>
          {!readOnly && <button className="rebirth-button" disabled={isBusy} onClick={onRebirth} type="button"><ReloadIcon />转世重开</button>}
        </div>
        <button className="text-button" disabled={isBusy} onClick={onLogout} type="button">退出当前邮箱</button>
      </section>
    </div>
  );
}

function ResetConfirmDialog({
  game,
  intent,
  isBusy,
  onCancel,
  onConfirm,
}: {
  game: GameState;
  intent: "RESET" | "FATES";
  isBusy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const isDead = game.phase === "dead";
  const isFateReset = intent === "FATES";
  const finalEvent = game.death ?? game.events[0];
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !isBusy) onCancel();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [isBusy, onCancel]);

  return (
    <div
      className="ink-dialog-backdrop"
      onMouseDown={(event) => {
        if (event.currentTarget === event.target && !isBusy) onCancel();
      }}
      role="presentation"
    >
      <section aria-describedby="reset-dialog-description" aria-labelledby="reset-dialog-title" aria-modal="true" className="ink-dialog" role="dialog">
        <div className="ink-dialog-seal" aria-hidden="true">{isDead ? "转" : isFateReset ? "择" : "慎"}</div>
        <p className="eyebrow">{isDead ? "ARCHIVE THIS LIFE" : isFateReset ? "ABANDON THESE FATES" : "REWRITE THIS LIFE"}</p>
        <h2 id="reset-dialog-title">{isDead ? "封存此世" : isFateReset ? "舍弃三份命格" : "重开命卷"}</h2>
        <p id="reset-dialog-description">
          {isDead
            ? `${game.player?.name ?? "这名修士"}的这一生将完整封存为前世记录，随后开启一张没有角色与剧情的新命卷。`
            : isFateReset ? "当前生成的三份命格将被舍弃，你会返回姓名与种族设定页重新推演。" : "当前命卷会被舍弃，已有角色、物品与纪事将无法恢复。"}
        </p>
        <div className="ink-dialog-note">
          <span>{isDead ? "本世归档" : isFateReset ? "候选命格" : "当前存档"}</span>
          <strong>{isDead ? `${finalEvent?.title ?? "命数已尽"} · ${game.turn}回合` : isFateReset ? `${game.protagonistOptions.length}份命格将被舍弃` : "删除后不可恢复"}</strong>
        </div>
        <div className="ink-dialog-actions">
          <button autoFocus className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不重开</button>
          <button className="ink-dialog-confirm" disabled={isBusy} onClick={onConfirm} type="button">
            <ReloadIcon />{isBusy ? "正在处理…" : isDead ? "封存并转世" : isFateReset ? "舍弃并重选" : "确认重开"}
          </button>
        </div>
      </section>
    </div>
  );
}

function saveUpdatedLabel(value: string) {
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return "最近游玩时间未知";
  return `上次游玩 ${time.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" })}`;
}

function SaveCards({ saves, currentSaveId, maxActiveSaves, isBusy, onOpen, onCreate, onDelete }: { saves: SaveSummary[]; currentSaveId?: string; maxActiveSaves: number; isBusy: boolean; onOpen: (save: SaveSummary) => void; onCreate: () => void; onDelete: (save: SaveSummary) => void }) {
  // 兼容迁移前可能重复或超出范围的旧 slotNo：优先保留有效槽位，冲突项依次补到空位。
  // 新后端会通过 active_slot_no 保证唯一；这里仅避免灰度期间命卷卡片被 Map 静默覆盖。
  const bySlot = new Map<number, SaveSummary>();
  const pending: SaveSummary[] = [];
  for (const save of saves) {
    if (save.slotNo >= 1 && save.slotNo <= maxActiveSaves && !bySlot.has(save.slotNo)) bySlot.set(save.slotNo, save);
    else pending.push(save);
  }
  for (const save of pending) {
    const empty = Array.from({ length: maxActiveSaves }, (_, index) => index + 1).find((slot) => !bySlot.has(slot));
    if (empty !== undefined) bySlot.set(empty, save);
  }
  return <div className="save-card-list" role="list">{Array.from({ length: maxActiveSaves }, (_, index) => {
    const slotNo = index + 1;
    const save = bySlot.get(slotNo);
    if (!save) return <button aria-label={`在第${slotNo}命卷位开启新角色`} className="new-save-card" disabled={isBusy} key={`empty-${slotNo}`} onClick={onCreate} type="button"><span className="save-slot-index">{String(slotNo).padStart(2, "0")}</span><span className="new-save-mark">＋</span><strong>空命卷</strong><small>在此开启一段新的修仙经历</small></button>;
    const current = save.saveId === currentSaveId;
    return <article className="save-slot-card" data-current={current} key={save.saveId} role="listitem"><button aria-label={`进入${save.playerName}的命卷`} className="save-slot-main" disabled={isBusy} onClick={() => onOpen(save)} type="button"><span className="save-slot-index">{String(slotNo).padStart(2, "0")}</span><span className="save-slot-state">{current ? "当前命卷" : save.race || "此生命卷"}</span><strong>{save.playerName}</strong><p>{save.realm} · {save.currentLocation || "尚未入世"}</p><small>第 {save.turn} 回 · {saveUpdatedLabel(save.updatedAt)}</small><span className="save-open-label">进入此世 <ChevronRightIcon /></span></button><button aria-label={`删除${save.playerName}的命卷`} className="save-slot-delete" disabled={isBusy} onClick={() => onDelete(save)} type="button"><TrashIcon />删除</button></article>;
  })}</div>;
}

function SaveSelectionView({ saves, maxActiveSaves, isBusy, onOpen, onCreate, onDelete, onLogout }: { saves: SaveSummary[]; maxActiveSaves: number; isBusy: boolean; onOpen: (save: SaveSummary) => void; onCreate: () => void; onDelete: (save: SaveSummary) => void; onLogout: () => void }) {
  return <div className="record-view save-selection-view"><div className="save-selection-heading"><div><p className="eyebrow">MY DESTINY SCROLLS</p><h1>我的命卷</h1><p>选择一段人生继续历练，或在空命卷中开启新的角色。</p></div><div className="save-capacity"><strong>{saves.length}</strong><span>/ {maxActiveSaves}</span><small>已启用</small></div></div><SaveCards isBusy={isBusy} maxActiveSaves={maxActiveSaves} onCreate={onCreate} onDelete={onDelete} onOpen={onOpen} saves={saves} /><p className="save-delete-note">删除只会移除所选命卷，不影响账号、其他角色与已使用的回合。</p><button className="reset-button save-logout" onClick={onLogout} type="button">退出当前账号</button></div>;
}

function LegacyArchiveUnavailableView({ onLogout }: { onLogout: () => void }) {
  return <div className="record-view legacy-archive-empty"><div className="page-title-row"><div><p className="eyebrow">ARCHIVED LEGACY SCROLL</p><h1>旧版命卷已封存</h1></div><ReaderIcon className="title-icon" /></div><section className="legacy-readonly-notice"><strong>这份旧命卷尚未正式入世</strong><p>V0.13 的未完成创角、命格或天赋选择不再继续生成。已有正式角色仍可在旧版档案中查阅。</p></section><button className="primary-entry-button" onClick={() => navigateEdition()} type="button">返回当前版本开启新命卷<ArrowRightIcon /></button><button className="text-button" onClick={onLogout} type="button">退出当前邮箱</button></div>;
}

function GenerationAccessCard({ settings, generation, isBusy, onOpen }: { settings: AiSettings; generation: StoryGenerationSettings | null; isBusy: boolean; onOpen: () => void }) {
  const personal = generation?.mode === "PERSONAL";
  const provider = generation?.personalProviders.find((item) => item.code === generation.selectedPersonalProvider);
  const personalReady = personal && provider?.credentialConfigured && provider.credentialStatus === "ACTIVE";
  const title = personal
    ? personalReady ? "正在使用自己的推演密钥" : "自己的推演密钥需要重新验证"
    : `账号赠送回合 ${settings.usedTurns}/${settings.turnLimit}`;
  const detail = personal
    ? personalReady
      ? `${provider?.name ?? "自选服务"}密钥尾号 ${provider?.maskedSuffix ?? "已隐藏"}，只用于你的命卷。`
      : "本次行动不会自动改用平台回合，请先重新保存密钥或主动切换平台推演。"
    : settings.remainingTurns > 0
      ? `还可使用 ${settings.remainingTurns} 回；平台推演的具体技术配置不会对玩家展示。`
      : "赠送回合已用完，接入自己的密钥后仍可继续游玩。";
  return <section className="generation-access-card"><div><span>剧情生成</span><strong>{title}</strong><p>{detail}</p></div><button disabled={isBusy || !settings.customApiEnabled} onClick={onOpen} type="button">{personalReady ? "管理" : personal ? "重新验证" : "接入密钥"}</button></section>;
}

function AbandonSaveDialog({ save, isBusy, onCancel, onConfirm }: { save: SaveSummary; isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const [confirmation, setConfirmation] = useState("");
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-labelledby="delete-save-title" aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">删</div><p className="eyebrow">DELETE THIS DESTINY</p><h2 id="delete-save-title">删除「{save.playerName}」的命卷</h2><p>删除后，这名角色不会再出现在你的命卷列表中，相关经历也不能自行恢复。账号、其他命卷与已使用的回合不受影响。</p><label className="credential-field">请输入角色名“{save.playerName}”确认<input autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.currentTarget.value)} /></label><div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">保留命卷</button><button className="ink-dialog-confirm destructive" disabled={isBusy || confirmation !== save.playerName} onClick={onConfirm} type="button">确认删除</button></div></section></div>;
}

function WorldView({ game, saves, maxActiveSaves, version, releaseNotes, isBusy, readOnly = false, onOpenSave, onCreateSave, onAbandonSave }: { game: GameState; saves: SaveSummary[]; maxActiveSaves: number; version: string; releaseNotes: ReleaseNote[]; isBusy: boolean; readOnly?: boolean; onOpenSave: (save: SaveSummary) => void; onCreateSave: () => void; onAbandonSave: (save: SaveSummary) => void }) {
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">KNOWN WORLD</p><h1>世界</h1></div><GlobeIcon className="title-icon" /></div>
      <section className="location-card"><div className="location-art" /><div className="location-copy"><span><SewingPinFilledIcon /> 当前位置</span><h2>{game.sceneTitle} · {game.currentLocation}</h2><p>{game.world.summary}</p></div></section>
      <section className="world-law-card"><p><strong>修行法则</strong>{game.world.cultivationLaw}</p><p><strong>生存法则</strong>{game.world.dangerRule}</p><div>{game.world.factions.map((faction) => <span key={faction}>{faction}</span>)}</div></section>
      <section className="record-section"><div className="section-label"><ReaderIcon /><h2>我的命卷</h2><span>{saves.length}/{maxActiveSaves}</span></div><SaveCards currentSaveId={game.saveId} isBusy={isBusy} maxActiveSaves={maxActiveSaves} onCreate={onCreateSave} onDelete={onAbandonSave} onOpen={onOpenSave} saves={saves} /></section>
      <section className="record-section"><div className="section-label"><MagnifyingGlassIcon /><h2>未解线索</h2><span>{game.threads.length}</span></div><div className="thread-list">{game.threads.map((thread) => <article className="thread-card" key={thread.id}><div><h3>{thread.title}</h3><p>{thread.state}</p></div><span data-danger={thread.danger > 75 ? "high" : "normal"}>{dangerName(thread.danger)}</span></article>)}</div></section>
      <section className="release-history"><div className="section-label"><ReaderIcon /><h2>版本纪事</h2><span>{version}</span></div>{releaseNotes.length === 0 ? <p className="empty-copy">尚无公开版本纪事。</p> : releaseNotes.map((note) => <details key={note.id}><summary><span>{note.version}</span><strong>{note.title}</strong><small>{new Date(note.publishedAt).toLocaleDateString("zh-CN")}</small></summary><p>{note.content}</p></details>)}</section>
    </div>
  );
}

function LegacyAiCredentialDialog({ settings, error, isBusy, onCancel, onSave, onDelete }: { settings: AiSettings; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onSave: (key: string) => void; onDelete: () => void }) {
  const [apiKey, setApiKey] = useState("");
  const insecureRemote = window.location.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(window.location.hostname);
  const keyTooShort = apiKey.trim().length > 0 && apiKey.trim().length < 20;
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">钥</div><p className="eyebrow">PRIVATE GENERATION KEY</p><h2>自己的推演密钥</h2><p>密钥会在服务器加密保存，只用于续写你的游戏故事；页面不会再次展示完整内容。</p>{insecureRemote && <p className="credential-warning">当前连接未启用 HTTPS，为避免密钥在传输中暴露，暂不允许保存。请改用安全地址。</p>}{error && <ProblemNotice compact problem={error} />}<label className="credential-field">DeepSeek API Key<input autoComplete="off" disabled={isBusy || insecureRemote} placeholder={settings.credentialConfigured ? `当前尾号 ${settings.maskedSuffix ?? "****"}，输入新密钥可替换` : "sk-…"} type="password" value={apiKey} onChange={(event) => setApiKey(event.currentTarget.value)} />{keyTooShort && <small>密钥长度不足，请粘贴完整的 API Key。</small>}</label><div className="ink-dialog-actions credential-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">返回</button>{settings.credentialConfigured && <button className="credential-delete" disabled={isBusy} onClick={onDelete} type="button">移除密钥</button>}<button className="ink-dialog-confirm" disabled={isBusy || insecureRemote || apiKey.trim().length < 20} onClick={() => onSave(apiKey.trim())} type="button">{isBusy ? "正在验证…" : "验证并保存"}</button></div></section></div>;
}

function GenerationSettingsDialog({ settings, error, isBusy, onCancel, onUsePlatform, onSave, onActivate, onDelete }: { settings: StoryGenerationSettings; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onUsePlatform: () => void; onSave: (provider: string, model: string, key: string) => void; onActivate: (provider: string, model: string) => void; onDelete: (provider: string) => void }) {
  const fallbackProvider = settings.personalProviders[0]?.code ?? "";
  const [providerCode, setProviderCode] = useState(settings.selectedPersonalProvider ?? fallbackProvider);
  const provider = settings.personalProviders.find((item) => item.code === providerCode) ?? settings.personalProviders[0];
  const fallbackModel = provider?.models[0]?.code ?? "";
  const [modelCode, setModelCode] = useState(
    settings.selectedPersonalProvider === providerCode ? settings.selectedPersonalModel ?? fallbackModel : fallbackModel,
  );
  const [apiKey, setApiKey] = useState("");
  const insecureRemote = window.location.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(window.location.hostname);
  const selectProvider = (code: string) => {
    setProviderCode(code);
    setModelCode(settings.personalProviders.find((item) => item.code === code)?.models[0]?.code ?? "");
    setApiKey("");
  };
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">钥</div><p className="eyebrow">PERSONAL GENERATION SERVICE</p><h2>自己的推演服务</h2><p>平台推演由游戏统一维护，不展示具体供应商与模型。只有使用自己的密钥时，才需要在这里选择服务与模型。</p>{insecureRemote && <p className="credential-warning">当前连接未启用 HTTPS，为避免密钥在传输中暴露，暂不允许保存。</p>}{error && <ProblemNotice compact problem={error} />}{settings.personalProviders.length === 0 ? <p className="credential-warning">当前暂未开放可接入的推演服务。</p> : <div className="credential-field"><label>推演服务<select disabled={isBusy} onChange={(event) => selectProvider(event.currentTarget.value)} value={providerCode}>{settings.personalProviders.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label><label>模型<select disabled={isBusy} onChange={(event) => setModelCode(event.currentTarget.value)} value={modelCode}>{provider?.models.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label><label>{provider?.name ?? "推演服务"} API Key<input autoComplete="off" disabled={isBusy || insecureRemote} placeholder={provider?.credentialConfigured ? `当前尾号 ${provider.maskedSuffix ?? "****"}，输入新密钥可替换` : "粘贴自己的 API Key"} type="password" value={apiKey} onChange={(event) => setApiKey(event.currentTarget.value)} /></label>{apiKey && apiKey.trim().length < 20 && <small>密钥长度不足，请粘贴完整内容。</small>}</div>}<div className="ink-dialog-actions credential-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">返回</button>{settings.mode === "PERSONAL" && <button className="credential-delete" disabled={isBusy} onClick={onUsePlatform} type="button">改用平台推演</button>}{provider?.credentialConfigured && <button className="credential-delete" disabled={isBusy} onClick={() => onDelete(provider.code)} type="button">移除密钥</button>}{provider?.credentialConfigured && !apiKey.trim() && <button className="ink-dialog-confirm" disabled={isBusy || !modelCode} onClick={() => onActivate(provider.code, modelCode)} type="button">使用此密钥</button>}<button className="ink-dialog-confirm" disabled={isBusy || insecureRemote || !provider || !modelCode || apiKey.trim().length < 20} onClick={() => onSave(provider.code, modelCode, apiKey.trim())} type="button">{isBusy ? "正在验证…" : provider?.credentialConfigured ? "验证并替换" : "验证并启用"}</button></div></section></div>;
}

function PasswordDialog({ configured, mandatory = false, error, isBusy, onCancel, onSave }: { configured: boolean; mandatory?: boolean; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onSave: (currentPassword: string, newPassword: string) => void }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const ready = newPassword.length >= 8 && newPassword === confirmation && (!configured || currentPassword.length >= 8);
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">密</div><p className="eyebrow">ACCOUNT SECURITY</p><h2>{mandatory ? "首次登录，请设置新密码" : configured ? "修改登录密码" : "设置登录密码"}</h2><p>{mandatory ? "初始化密码仅用于本次身份确认。请立即设置自己的密码，完成后才能继续命卷。" : "密码需为 8-72 个字符。服务器只保存不可逆摘要，不会保存或展示明文。"}</p>{error && <ProblemNotice compact problem={error} />}<div className="credential-field">{configured && <label>当前密码<input autoComplete="current-password" disabled={isBusy} maxLength={72} onChange={(event) => setCurrentPassword(event.currentTarget.value)} type="password" value={currentPassword} /></label>}<label>新密码<input autoComplete="new-password" disabled={isBusy} maxLength={72} minLength={8} onChange={(event) => setNewPassword(event.currentTarget.value)} type="password" value={newPassword} /></label><label>确认新密码<input autoComplete="new-password" disabled={isBusy} maxLength={72} minLength={8} onChange={(event) => setConfirmation(event.currentTarget.value)} type="password" value={confirmation} /></label>{confirmation && confirmation !== newPassword && <small className="field-error">两次输入的密码不一致</small>}</div><div className={`ink-dialog-actions${mandatory ? " single" : ""}`}>{!mandatory && <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">返回</button>}<button className="ink-dialog-confirm" disabled={isBusy || !ready} onClick={() => onSave(currentPassword, newPassword)} type="button">{isBusy ? "正在保存…" : "保存新密码并继续"}</button></div></section></div>;
}

function DeleteAccountDialog({ isBusy, onCancel, onConfirm }: { isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const [confirmation, setConfirmation] = useState("");
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">止</div><p className="eyebrow">DELETE ACCOUNT</p><h2>注销这个账号</h2><p>注销后，邮箱身份、今生命卷、前世记录和自定义密钥都会永久删除，无法恢复。</p><label className="credential-field">请输入“确认注销”<input autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.currentTarget.value)} /></label><div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不注销</button><button className="ink-dialog-confirm destructive" disabled={isBusy || confirmation !== "确认注销"} onClick={onConfirm} type="button">永久注销</button></div></section></div>;
}

function ReleaseAnnouncementDialog({ note, onClose }: { note: ReleaseNote; onClose: () => void }) {
  return <div className="ink-dialog-backdrop release-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog release-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">新</div><p className="eyebrow">NEW CHAPTER · {note.version}</p><h2>{note.title}</h2>
    <div className="release-dialog-content">{note.content.split(/\n+/).filter(Boolean).map((line, index) => <p key={`${index}-${line}`}>{line}</p>)}</div>
    <div className="ink-dialog-actions single"><button autoFocus className="ink-dialog-confirm" onClick={onClose} type="button">知道了，继续此世<ArrowRightIcon /></button></div>
  </section></div>;
}

function TurnLimitDialog({ notice, onClose, onCopyGroup, onOpenSettings }: { notice: TurnLimitNotice; onClose: () => void; onCopyGroup: () => void; onOpenSettings: () => void }) {
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-describedby="turn-limit-description" aria-labelledby="turn-limit-title" aria-modal="true" className="ink-dialog quota-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">续</div><p className="eyebrow">CONTINUE THIS CHAPTER</p><h2 id="turn-limit-title">赠送回合已用完</h2>
    <p id="turn-limit-description">{notice.message}</p>
    {notice.contactText && <div className="ink-dialog-note"><span>试玩交流</span><strong>{notice.contactText}</strong></div>}
    <div className="ink-dialog-actions quota-actions">
      <button className="ink-dialog-confirm" onClick={onOpenSettings} type="button">接入自己的推演密钥<ArrowRightIcon /></button>
      {notice.supportGroup && <button className="ink-dialog-cancel" onClick={onCopyGroup} type="button">复制QQ群号</button>}
      <button className="ink-dialog-cancel" onClick={onClose} type="button">暂时留在此页</button>
    </div>
  </section></div>;
}

function MaintenanceDialog({ notice, onClose, closeLabel = "知道了" }: { notice: MaintenanceNotice; onClose: () => void; closeLabel?: string }) {
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog quota-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">候</div><p className="eyebrow">WORLD IS UPDATING</p>
    <h2>{notice.title}</h2><p>{notice.message}</p>
    {notice.resumeAt && <div className="ink-dialog-note"><span>预计恢复</span><strong>{new Date(notice.resumeAt).toLocaleString("zh-CN")}</strong></div>}
    <div className="ink-dialog-actions single"><button autoFocus className="ink-dialog-confirm" onClick={onClose} type="button">{closeLabel}</button></div>
  </section></div>;
}

function RecordCleanupDialog({ target, isBusy, onCancel, onConfirm }: { target: CleanupTarget; isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const item = target.kind === "item";
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">理</div><p className="eyebrow">TIDY CURRENT RECORDS</p>
    <h2>{item ? `移出「${target.name}」` : `不再关注「${target.name}」`}</h2>
    <p>{item ? "这件物品会从当前行囊和后续故事上下文中移除。" : "这个人物会从当前关系和后续故事上下文中移除。"}已经发生过的剧情与纪事仍会保留。</p>
    <div className="ink-dialog-note"><span>不会发生</span><strong>不调用推演 · 不消耗回合 · 不推进时间</strong></div>
    <div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不整理</button><button className="ink-dialog-confirm" disabled={isBusy} onClick={onConfirm} type="button">确认{item ? "移出" : "不再关注"}</button></div>
  </section></div>;
}

function StoryCorrectionDialog({ preview, error, isBusy, onCancel, onPreview, onConfirm, onEdit }: { preview: StoryCorrectionPreview | null; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onPreview: (instruction: string) => void; onConfirm: () => void; onEdit: () => void }) {
  const [instruction, setInstruction] = useState("");
  const kindLabel = preview?.kind === "WORLD" ? "世界设定" : preview?.kind === "COGNITION" ? "人物认知" : "客观事实";
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog correction-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">正</div><p className="eyebrow">CORRECT THE RECORD</p>
    <h2>{preview ? "确认这次命卷修正" : "修正命卷事实"}</h2>
    {preview ? <>
      <p>系统只会写入下面展示的变化。确认前，你仍可以返回修改或直接取消。</p>
      <div className="correction-preview"><span>{kindLabel}</span><strong>{preview.explanation}</strong><p>{preview.confirmationPrompt}</p></div>
      <div className="ink-dialog-note"><span>确认后</span><strong>不消耗回合 · 不推进时间 · 保留原有纪事</strong></div>
    </> : <>
      <p>这里用于纠正已经记录错误的事实，不是发起新的行动。系统会先生成一份明确预览，只有再次确认后才会写入命卷。</p>
      <label className="credential-field correction-field">需要纠正的内容<textarea autoFocus disabled={isBusy} maxLength={500} onChange={(event) => setInstruction(event.currentTarget.value)} placeholder="例如：林霜三年前已经离世，不应再次作为活人出现。" rows={5} value={instruction} /><small>{instruction.trim().length}/500</small></label>
    </>}
    {error && <ProblemNotice compact problem={error} />}
    {preview ? <div className="ink-dialog-actions correction-actions">
      <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">取消修正</button>
      <button className="ink-dialog-cancel" disabled={isBusy} onClick={onEdit} type="button">返回修改</button>
      <button className="ink-dialog-confirm" disabled={isBusy} onClick={onConfirm} type="button">{isBusy ? "正在写入…" : "确认写入命卷"}</button>
    </div> : <div className="ink-dialog-actions">
      <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不修正</button>
      <button className="ink-dialog-confirm" disabled={isBusy || instruction.trim().length < 2} onClick={() => onPreview(instruction.trim())} type="button">{isBusy ? "正在核对…" : "生成修正预览"}</button>
    </div>}
  </section></div>;
}

function QuickSettingsPanel({ theme, fontSize, generation, aiSettings, email, passwordConfigured, isBusy, legacyReadOnly = false, showLegacyArchive = false, onClose, onTheme, onFontSize, onPlatform, onAiSettings, onCorrection, onPasswordSettings, onSaves, onReset, onLogout, onDeleteAccount }: { theme: ThemeMode; fontSize: FontSizeMode; generation: StoryGenerationSettings | null; aiSettings: AiSettings | null; email: string; passwordConfigured: boolean; isBusy: boolean; legacyReadOnly?: boolean; showLegacyArchive?: boolean; onClose: () => void; onTheme: (value: ThemeMode) => void; onFontSize: (value: FontSizeMode) => void; onPlatform: () => void; onAiSettings: () => void; onCorrection?: () => void; onPasswordSettings: () => void; onSaves: () => void; onReset: () => void; onLogout: () => void; onDeleteAccount: () => void }) {
  const personalProvider = generation?.personalProviders.find((item) => item.code === generation.selectedPersonalProvider);
  const personalModel = personalProvider?.models.find((item) => item.code === generation?.selectedPersonalModel);
  const personalReady = generation?.mode === "PERSONAL" && personalProvider?.credentialConfigured && personalProvider.credentialStatus === "ACTIVE";
  return <div className="quick-settings-backdrop" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }}><aside aria-modal="true" className="quick-settings-panel" role="dialog"><header><div><p className="eyebrow">READING & ACCOUNT</p><h2>设置</h2></div><button aria-label="关闭设置" onClick={onClose} type="button"><Cross2Icon /></button></header>
    <section><h3>阅读外观</h3><p>只改变当前设备的阅读效果，不会影响命卷。</p><div className="theme-switch">{([['system','跟随系统'],['paper','宣纸'],['night','月夜']] as Array<[ThemeMode,string]>).map(([id,label]) => <button aria-pressed={theme === id} data-selected={theme === id} key={id} onClick={() => onTheme(id)} type="button">{label}</button>)}</div><div className="font-size-setting"><div><strong>字体大小</strong><span>调整剧情、选项与档案正文。</span></div><div className="theme-switch">{([['compact','小字'],['standard','标准'],['large','大字']] as Array<[FontSizeMode,string]>).map(([id,label]) => <button aria-pressed={fontSize === id} data-selected={fontSize === id} key={id} onClick={() => onFontSize(id)} type="button">{label}</button>)}</div></div></section>
    <section><h3>剧情生成</h3>{generation ? <><p>{generation.mode === "PERSONAL" ? personalReady ? `正在使用自己的${personalProvider ? ` ${personalProvider.name}` : ""}密钥${personalModel ? ` · ${personalModel.name}` : ""}。` : "自己的推演密钥当前不可用；行动会暂停，不会自动消耗平台回合。" : `正在使用平台推演，还可行动 ${generation.platform.remainingTurns} 回。平台使用的供应商和模型由游戏统一维护。`}</p><div className="theme-switch"><button aria-pressed={generation.mode === "PLATFORM"} data-selected={generation.mode === "PLATFORM"} disabled={isBusy} onClick={onPlatform} type="button">平台推演</button><button aria-pressed={generation.mode === "PERSONAL"} data-selected={generation.mode === "PERSONAL"} disabled={isBusy || generation.personalProviders.length === 0} onClick={onAiSettings} type="button">自己的密钥</button></div></> : <><p>{!aiSettings ? "生成设置暂未同步。" : aiSettings.usingCustomApi ? `正在使用自己的密钥，尾号 ${aiSettings.maskedSuffix ?? "已隐藏"}。` : `正在使用平台推演，还可行动 ${aiSettings.remainingTurns} 回。`}</p><button className="secondary-setting-button" disabled={!aiSettings?.customApiEnabled} onClick={onAiSettings} type="button">{aiSettings?.credentialConfigured ? "管理自己的密钥" : "接入自己的密钥"}</button></>}</section>
    {onCorrection && <section><h3>命卷事实</h3><p>若人物生死、持有物、地点或世界设定被记错，可以先预览再确认修正。修正不会代替角色行动。</p><button className="secondary-setting-button correction-entry-button" disabled={isBusy} onClick={onCorrection} type="button">修正当前命卷</button></section>}
    <section><h3>账号与命卷</h3><p>{email || "当前账号"}<br />{passwordConfigured ? "登录密码已设置。" : "请为旧账号设置登录密码。"}</p><div className="settings-action-grid"><button onClick={onPasswordSettings} type="button">{passwordConfigured ? "修改密码" : "设置密码"}</button><button onClick={onSaves} type="button">{legacyReadOnly ? "旧版命卷" : "我的命卷"}</button>{showLegacyArchive && <button className="settings-wide-action" onClick={() => navigateEdition("v013")} type="button"><ReaderIcon />V0.13 旧版命卷档案</button>}{legacyReadOnly && <button className="settings-wide-action" onClick={() => navigateEdition()} type="button"><ArrowRightIcon />返回当前命卷</button>}</div></section>
    <section className="settings-danger-zone"><h3>命卷管理</h3><p>{legacyReadOnly ? "旧版命卷只读封存；退出账号不会删除档案。" : "重开只影响当前角色；退出账号不会删除存档。"}</p><div className="settings-action-grid">{!legacyReadOnly && <button onClick={onReset} type="button"><ReloadIcon />重开此世</button>}<button onClick={onLogout} type="button">退出账号</button></div>{!legacyReadOnly && <button className="delete-account-button" onClick={onDeleteAccount} type="button">注销账号与全部命卷</button>}</section>
  </aside></div>;
}

function LegacyPrototype({ v014 = false }: { v014?: boolean }) {
  const [stage, setStage] = useState<AppStage>("loading");
  const [tab, setTab] = useState<TabId>("adventure");
  const [auth, setAuth] = useState<AuthResponse | null>(null);
  const [bootstrap, setBootstrap] = useState<BootstrapResponse | null>(null);
  const [snapshot, setSnapshot] = useState<ApiSnapshot | null>(null);
  const [saves, setSaves] = useState<SaveSummary[]>([]);
  const [maxActiveSaves, setMaxActiveSaves] = useState(4);
  const storyMaxActiveSavesRef = useRef(4);
  const [abandonTarget, setAbandonTarget] = useState<SaveSummary | null>(null);
  const [draft, setDraft] = useState("");
  const [characterDraft, setCharacterDraft] = useState<CharacterDraft>(DEFAULT_DRAFT);
  const [nameAlternatives, setNameAlternatives] = useState<string[]>([]);
  const [generatedName, setGeneratedName] = useState("");
  const [characterRoutes, setCharacterRoutes] = useState<StoryCharacterRoute[]>([]);
  const [selectedCharacterRouteId, setSelectedCharacterRouteId] = useState("");
  const [selectedFate, setSelectedFate] = useState("");
  const [selectedTalent, setSelectedTalent] = useState("");
  const [theme, setTheme] = useState<ThemeMode>(() => (window.localStorage.getItem(THEME_KEY) as ThemeMode | null) ?? "system");
  const [fontSize, setFontSize] = useState<FontSizeMode>(() => (window.localStorage.getItem(FONT_SIZE_KEY) as FontSizeMode | null) ?? "standard");
  const [systemDark, setSystemDark] = useState(() => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false);
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const [isBusy, setIsBusy] = useState(false);
  const busyRef = useRef(false);
  const [isComposerFocused, setIsComposerFocused] = useState(false);
  const [syncMode, setSyncMode] = useState<SyncMode>("connecting");
  const [error, setError] = useState<UiProblem | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [resetIntent, setResetIntent] = useState<"RESET" | "FATES" | null>(null);
  const [restartTargetId, setRestartTargetId] = useState<string | null>(null);
  const [pendingRelease, setPendingRelease] = useState<ReleaseNote | null>(null);
  const [loginAnnouncement, setLoginAnnouncement] = useState<ReleaseNote | null>(null);
  const [accessStatus, setAccessStatus] = useState<AccessStatus | null>(null);
  const [releaseNotes, setReleaseNotes] = useState<ReleaseNote[]>([]);
  const [aiSettings, setAiSettings] = useState<AiSettings | null>(null);
  const [showAiSettings, setShowAiSettings] = useState(false);
  const [showPasswordSettings, setShowPasswordSettings] = useState(false);
  const [showDeleteAccount, setShowDeleteAccount] = useState(false);
  const [turnLimitNotice, setTurnLimitNotice] = useState<TurnLimitNotice | null>(null);
  const [maintenanceNotice, setMaintenanceNotice] = useState<MaintenanceNotice | null>(null);
  const [showQuickSettings, setShowQuickSettings] = useState(false);
  const [generationSettings, setGenerationSettings] = useState<StoryGenerationSettings | null>(null);
  const [cleanupTarget, setCleanupTarget] = useState<CleanupTarget | null>(null);
  const [showCorrection, setShowCorrection] = useState(false);
  const [correctionPreview, setCorrectionPreview] = useState<StoryCorrectionPreview | null>(null);

  const game = snapshot?.state ?? null;
  const choices = snapshot?.choices ?? game?.choices ?? [];
  const email = auth?.user?.email ?? "";
  const showGameChrome = stage === "saves" || stage === "fate" || stage === "talent" || stage === "game";
  const actualTheme = theme === "system" ? (systemDark ? "night" : "paper") : theme;
  const legacyReadOnly = !v014 && versionAtLeast(bootstrap?.version, 14);

  const storeSnapshot = (next: ApiSnapshot) => {
    setSnapshot(next);
    cacheSnapshot(next);
  };

  const loadBootstrap = async () => {
    if (v014) {
      const source = await gameApi.storyBootstrap();
      storyMaxActiveSavesRef.current = source.maxActiveSaves;
      setMaxActiveSaves(source.maxActiveSaves);
      const next = storyBootstrapView(source);
      setBootstrap(next);
      return next;
    }
    const next = await gameApi.bootstrap();
    setBootstrap(next);
    return next;
  };

  const stageFor = (next: ApiSnapshot) => next.state.phase === "setup" ? "fate" : next.state.phase === "talent" ? "talent" : "game";

  const refreshSaves = async () => {
    if (v014) {
      const rows = await gameApi.storySaves();
      const list = { maxActiveSaves: storyMaxActiveSavesRef.current, saves: rows.filter((save) => save.status === "ACTIVE").map((save) => ({
        saveId: save.id, slotNo: save.slotNo, phase: "playing" as const, playerName: save.playerName,
        race: STORY_RACE_CODES[save.race] || save.race, realm: save.realm, currentLocation: save.worldName,
        sceneTitle: save.worldName, turn: save.turnNumber, updatedAt: save.updatedAt,
      })) };
      setSaves(list.saves); setMaxActiveSaves(list.maxActiveSaves); return list;
    }
    const list = await gameApi.saves();
    setSaves(list.saves);
    setMaxActiveSaves(list.maxActiveSaves);
    return list;
  };

  const loadSave = async (saveId: string) => v014 ? storySnapshotView(await gameApi.storySave(saveId)) : gameApi.getSave(saveId);

  const enterAuthenticatedFlow = async (nextAuth: AuthResponse) => {
    setAuth(nextAuth);
    setSyncMode("server");
    await loadBootstrap();
    const [notes, pending, nextAiSettings] = await Promise.all([gameApi.releaseNotes(), gameApi.pendingReleaseNote(), gameApi.aiSettings()]);
    setReleaseNotes(notes);
    setPendingRelease(pending);
    setAiSettings(nextAiSettings);
    if (v014) setGenerationSettings(await gameApi.generationSettings());
    if (!nextAuth.passwordConfigured) setShowPasswordSettings(true);
    await refreshSaves();
    // 登录后统一进入命卷列表，让单命卷玩家也能看见槽位、切换或新增角色。
    setSnapshot(null);
    setStage("saves");
    return nextAiSettings;
  };

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const nextAccessStatus = await gameApi.accessStatus();
        if (active) setAccessStatus(nextAccessStatus);
        void gameApi.latestPublicReleaseNote()
          .then((note) => { if (active) setLoginAnnouncement(note); })
          .catch(() => undefined);
        const session = await gameApi.me();
        if (!active) return;
        if (!session.authenticated) {
          await loadBootstrap();
          if (active) { setAuth(session); setSyncMode("server"); setStage("login"); }
          return;
        }
        await enterAuthenticatedFlow(session);
      } catch (caught) {
        if (!active) return;
        if (caught instanceof ApiError && caught.code === "GAME_MAINTENANCE") {
          setMaintenanceNotice({
            title: typeof caught.details.title === "string" ? caught.details.title : "命卷暂时封存",
            message: caught.message,
            resumeAt: typeof caught.details.resumeAt === "string" ? caught.details.resumeAt : "",
          });
          setSyncMode("server");
          setStage("login");
          return;
        }
        const cached = readCachedSnapshot();
        setSyncMode("offline");
        if (cached) {
          setSnapshot(cached);
          setStage(cached.state.phase === "setup" ? "fate" : cached.state.phase === "talent" ? "talent" : "game");
        } else {
          setStage("login");
        }
      }
    })();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (stage !== "login") return;
    let active = true;
    let loading = false;
    const refreshLoginAnnouncement = async () => {
      if (loading || document.visibilityState === "hidden") return;
      loading = true;
      try {
        const note = await gameApi.latestPublicReleaseNote();
        if (active) setLoginAnnouncement(note);
      } catch { /* 登录页公告失败不阻断账号登录 */ }
      finally { loading = false; }
    };
    const onVisibility = () => { if (document.visibilityState === "visible") void refreshLoginAnnouncement(); };
    void refreshLoginAnnouncement();
    window.addEventListener("focus", refreshLoginAnnouncement);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      active = false;
      window.removeEventListener("focus", refreshLoginAnnouncement);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [stage]);

  useEffect(() => {
    if (!auth?.authenticated) return;
    let active = true;
    let checking = false;
    const checkReleases = async () => {
      if (checking || document.visibilityState === "hidden") return;
      checking = true;
      try {
        const [notes, pending] = await Promise.all([gameApi.releaseNotes(), gameApi.pendingReleaseNote()]);
        if (!active) return;
        setReleaseNotes(notes);
        if (pending) setPendingRelease((current) => current?.id === pending.id ? current : pending);
      } catch (caught) {
        if (caught instanceof ApiError && caught.status === 401 && active) {
          setAuth(null);
          setStage("login");
          setSyncMode("server");
        }
        // 其他公告检查失败不应中断单机命卷。
      } finally {
        checking = false;
      }
    };
    const onVisible = () => { if (document.visibilityState === "visible") void checkReleases(); };
    const timer = window.setInterval(() => void checkReleases(), 5 * 60_000);
    window.addEventListener("focus", checkReleases);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener("focus", checkReleases);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [auth?.authenticated]);

  useEffect(() => {
    window.localStorage.setItem(THEME_KEY, theme);
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setSystemDark(media.matches);
    media.addEventListener?.("change", update);
    return () => media.removeEventListener?.("change", update);
  }, [theme]);

  useEffect(() => {
    window.localStorage.setItem(FONT_SIZE_KEY, fontSize);
  }, [fontSize]);

  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => {
      const layoutHeight = Math.max(window.innerHeight, document.documentElement.clientHeight);
      setKeyboardOffset(Math.max(0, layoutHeight - viewport.height - viewport.offsetTop));
    };
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    update();
    return () => { viewport.removeEventListener("resize", update); viewport.removeEventListener("scroll", update); };
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 2400);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const runBusy = async (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setIsBusy(true);
    setError(null);
    try {
      await task();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401
          && caught.code !== "LOGIN_CREDENTIALS_INVALID") {
        setAuth(null);
        setStage("login");
        setSyncMode("server");
        setError({ category: "ACCOUNT", title: "登录状态已经失效",
          message: "请重新进入命卷。刚才填写的内容不会产生任何消耗。",
          suggestions: ["重新登录"], retryable: false, turnConsumed: false, stateChanged: false });
        return;
      }
      if (caught instanceof ApiError && caught.code === "PUBLIC_TURN_LIMIT_REACHED") {
        setTurnLimitNotice({
          message: caught.message,
          contactText: typeof caught.details.contactText === "string" ? caught.details.contactText : "",
          supportGroup: typeof caught.details.supportGroup === "string" ? caught.details.supportGroup : "",
        });
        gameApi.aiSettings().then(setAiSettings).catch(() => undefined);
        if (v014) gameApi.generationSettings().then(setGenerationSettings).catch(() => undefined);
        return;
      }
      if (caught instanceof ApiError && caught.code === "GAME_MAINTENANCE") {
        setMaintenanceNotice({
          title: typeof caught.details.title === "string" ? caught.details.title : "命卷暂时封存",
          message: caught.message,
          resumeAt: typeof caught.details.resumeAt === "string" ? caught.details.resumeAt : "",
        });
        setSyncMode("server");
        return;
      }
      if (caught instanceof ApiError && ["STATE_CONFLICT", "CORRECTION_REVISION_CONFLICT", "CORRECTION_PREVIEW_EXPIRED"].includes(caught.code ?? "") && snapshot?.state.saveId) {
        try {
          const refreshed = await loadSave(snapshot.state.saveId);
          storeSnapshot(refreshed);
          if (showCorrection) setCorrectionPreview(null);
        } catch {
          // 保留原始冲突提示；刷新失败时不覆盖真正的原因。
        }
      }
      setError(problemFrom(caught));
    } finally {
      busyRef.current = false;
      setIsBusy(false);
    }
  };

  const authenticate = (mode: "login" | "register", loginEmail: string, password: string) => runBusy(async () => {
    const nextAuth = mode === "register"
      ? await gameApi.register(loginEmail, password)
      : await gameApi.login(loginEmail, password);
    const nextAiSettings = await enterAuthenticatedFlow(nextAuth);
    setToast(nextAuth.newUser ? `新账号已登记，获赠 ${nextAiSettings.turnLimit} 个推演回合` : "旧命卷已寻回");
  });

  const generateName = () => runBusy(async () => {
    if (!characterDraft.race || !characterDraft.gender) {
      throw new ApiError("请先选择性别与种族", 400);
    }
    const generated = await gameApi.generateName(characterDraft.race, characterDraft.gender, [characterDraft.playerName, ...nameAlternatives].filter(Boolean));
    setCharacterDraft((current) => ({ ...current, playerName: generated.name }));
    setGeneratedName(generated.name);
    setNameAlternatives(generated.alternatives);
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
  });

  const updateCharacterDraft = (patch: Partial<CharacterDraft>) => {
    const identityChanged = patch.race !== undefined || patch.gender !== undefined
      || patch.worldCode !== undefined || patch.customWorld !== undefined;
    if (identityChanged) {
      setNameAlternatives([]);
      setCharacterRoutes([]);
      setSelectedCharacterRouteId("");
      setCharacterDraft((current) => ({
        ...current,
        ...patch,
        playerName: current.playerName === generatedName ? "" : current.playerName,
      }));
      setGeneratedName("");
      return;
    }
    if (patch.playerName !== undefined) {
      setCharacterRoutes([]);
      setSelectedCharacterRouteId("");
      setGeneratedName(nameAlternatives.includes(patch.playerName) ? patch.playerName : "");
    }
    setCharacterDraft((current) => ({ ...current, ...patch }));
  };

  const generateCharacterRoutes = () => runBusy(async () => {
    if (!characterDraft.gender || !characterDraft.race) {
      throw new ApiError("请先选择性别与种族，再推演角色路线", 400);
    }
    if (!characterDraft.playerName.trim()) {
      throw new ApiError("请先输入角色名，或点击“随机雅名”生成一个名字", 400);
    }
    const validation = await gameApi.validateName(characterDraft.playerName);
    if (!validation.valid) throw new ApiError(validation.message, 400);
    const response = await gameApi.storyCharacterRoutes({
      worldCode: characterDraft.worldCode || "XUANXU",
      customWorld: characterDraft.customWorld,
      playerName: validation.normalizedName,
      race: STORY_RACE_NAMES[characterDraft.race] ?? characterDraft.race,
      gender: STORY_GENDER_NAMES[characterDraft.gender] ?? characterDraft.gender,
      roleSeed: characterDraft.originPremise.trim() || undefined,
      preferenceSeed: characterDraft.preference.trim() || undefined,
    });
    setCharacterDraft((current) => ({ ...current, playerName: validation.normalizedName }));
    setCharacterRoutes(response.routes);
    setSelectedCharacterRouteId("");
    setToast("三条角色路线已经落定，可以选择或继续自定义");
  });

  const selectCharacterRoute = (route: StoryCharacterRoute) => {
    setSelectedCharacterRouteId(route.id);
    setCharacterDraft((current) => ({
      ...current,
      originPremise: route.origin,
      preference: route.preference,
    }));
  };

  const createCharacter = () => runBusy(async () => {
    if (!characterDraft.gender || !characterDraft.race) {
      throw new ApiError("请先选择性别与种族，再推演命格", 400);
    }
    if (!characterDraft.playerName.trim()) {
      throw new ApiError("请先输入角色名，或点击“随机雅名”生成一个名字", 400);
    }
    if (v014 && !characterDraft.originPremise.trim()) {
      throw new ApiError("请选择一条角色路线，或填写自己的角色设定", 400);
    }
    const validation = await gameApi.validateName(characterDraft.playerName);
    if (!validation.valid) throw new ApiError(validation.message, 400);
    const request = { ...characterDraft, playerName: validation.normalizedName };
    let created: ApiSnapshot;
    if (v014) {
      if (restartTargetId) await gameApi.storyDeleteSave(restartTargetId);
      const selectedRoute = characterRoutes.find((route) => route.id === selectedCharacterRouteId);
      created = storySnapshotView(await gameApi.storyCreateSave({
        worldCode: request.worldCode || "XUANXU", customWorld: request.customWorld,
        playerName: request.playerName, race: STORY_RACE_NAMES[request.race] ?? request.race,
        gender: STORY_GENDER_NAMES[request.gender] ?? request.gender,
        origin: request.originPremise.trim(), opening: selectedRoute?.opening,
        storyTone: request.preference.trim() || "自由探索，不预设唯一道路",
      }));
    } else {
      created = restartTargetId ? await gameApi.restartSave(restartTargetId, request) : await gameApi.createSave(request);
    }
    setRestartTargetId(null);
    storeSnapshot(created);
    await refreshSaves();
    setSelectedFate("");
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setStage(v014 ? "game" : "fate");
    if (v014) setTab("adventure");
    setToast(v014 ? "此生命卷已经开启" : "三份命格已经落定");
  });

  const chooseFate = () => runBusy(async () => {
    if (!game || !selectedFate) return;
    const started = await gameApi.chooseFate(game.saveId, selectedFate);
    storeSnapshot(started);
    await refreshSaves();
    setSelectedTalent("");
    setStage("talent");
    setToast("命格已定，先天灵光正在显现");
  });

  const rerollTalents = () => runBusy(async () => {
    if (!game) return;
    const next = await gameApi.rerollTalents(game.saveId);
    storeSnapshot(next);
    setSelectedTalent("");
  });

  const enterWithTalent = () => runBusy(async () => {
    if (!game || !selectedTalent) return;
    const next = await gameApi.selectTalent(game.saveId, selectedTalent);
    storeSnapshot(next); await refreshSaves(); setStage("game"); setTab("adventure");
    setToast("天赋已入命，世界时间开始流动");
  });

  const enterWithoutTalent = () => runBusy(async () => {
    if (!game) return;
    const next = await gameApi.skipTalent(game.saveId);
    storeSnapshot(next); await refreshSaves(); setStage("game"); setTab("adventure");
    setToast("一身清净，正式入世");
  });

  const takeTurn = (inputType: "CHOICE" | "TEXT", payload: { choiceId?: string; content?: string }) => runBusy(async () => {
    if (!game || !snapshot) return;
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    setIsComposerFocused(false);
    const next = v014
      ? storySnapshotView(await gameApi.storyTurn(game.saveId, inputType === "CHOICE"
        ? (() => { const selected = choices.find((choice) => choice.id === payload.choiceId); return selected ? `${selected.label}：${selected.hint}` : "观察当前局势"; })()
        : (payload.content ?? "观察当前局势"), snapshot.revision))
      : await gameApi.turn(game.saveId, snapshot.revision, inputType, payload);
    storeSnapshot(next);
    await refreshSaves();
    gameApi.aiSettings().then(setAiSettings).catch(() => undefined);
    if (v014) gameApi.generationSettings().then(setGenerationSettings).catch(() => undefined);
    setDraft("");
    setTab("adventure");
    setSyncMode("server");
    if (!next.actionResolution) setToast("本回合已写入统一时间线");
  });

  const confirmCleanup = () => runBusy(async () => {
    if (!v014 || !game || !snapshot || !cleanupTarget) return;
    const target = cleanupTarget;
    const response = await gameApi.storyRemoveEntity(game.saveId, target.id, snapshot.revision);
    storeSnapshot(storySnapshotView(response));
    setCleanupTarget(null);
    setToast(target.kind === "item" ? "物品已移出当前行囊，旧事仍保留" : "人物已移出当前关注，旧事仍保留");
  });

  const previewCorrection = (instruction: string) => runBusy(async () => {
    if (!v014 || !game || !snapshot) return;
    const preview = await gameApi.storyCorrectionPreview(game.saveId, instruction, snapshot.revision);
    setCorrectionPreview(preview);
  });

  const confirmCorrection = () => runBusy(async () => {
    if (!v014 || !game || !snapshot || !correctionPreview) return;
    const response = await gameApi.storyCorrectionConfirm(game.saveId, correctionPreview.proposalToken, snapshot.revision);
    storeSnapshot(storySnapshotView(response));
    await refreshSaves();
    setCorrectionPreview(null);
    setShowCorrection(false);
    setToast("命卷事实已修正，原有纪事仍然保留");
  });

  const closeCorrection = () => {
    if (isBusy) return;
    setCorrectionPreview(null);
    setShowCorrection(false);
    setError(null);
  };

  const dismissResolution = () => {
    setSnapshot((current) => current ? {
      ...current,
      choices: current.state.choices,
      actionResolution: null,
    } : current);
  };

  const prepareRestart = () => {
    if (!game) return;
    setRestartTargetId(game.saveId);
    setSnapshot(null);
    setCharacterDraft(DEFAULT_DRAFT);
    setNameAlternatives([]);
    setGeneratedName("");
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setStage("create");
  };

  const cancelRestart = () => runBusy(async () => {
    if (!restartTargetId) return;
    const current = await loadSave(restartTargetId);
    setRestartTargetId(null);
    storeSnapshot(current);
    setTab("adventure");
    setStage(stageFor(current));
  });

  const openSave = (save: SaveSummary) => runBusy(async () => {
    const next = await loadSave(save.saveId);
    setRestartTargetId(null);
    storeSnapshot(next);
    await refreshSaves();
    setTab("adventure");
    setStage(stageFor(next));
  });

  const createAdditionalSave = () => {
    if (saves.length >= maxActiveSaves) return;
    setRestartTargetId(null);
    setSnapshot(null);
    setCharacterDraft(DEFAULT_DRAFT);
    setNameAlternatives([]);
    setGeneratedName("");
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setStage("create");
  };

  const abandonSave = () => runBusy(async () => {
    if (!abandonTarget) return;
    const targetId = abandonTarget.saveId;
    if (v014) await gameApi.storyDeleteSave(targetId); else await gameApi.abandonSave(targetId, abandonTarget.playerName);
    setAbandonTarget(null);
    if (snapshot?.state.saveId === targetId) {
      setSnapshot(null);
      window.localStorage.removeItem(CACHE_KEY);
    }
    await refreshSaves();
    setTab("adventure");
    setStage("saves");
    setToast("命卷已删除，空出的命卷位可以重新使用");
  });

  const resetGame = () => {
    setResetIntent("RESET");
  };

  const leaveDeath = () => runBusy(async () => {
    setSnapshot(null);
    window.localStorage.removeItem(CACHE_KEY);
    const list = await refreshSaves();
    setStage(list.saves.length > 0 ? "saves" : "create");
    setTab("adventure");
  });

  const logout = () => runBusy(async () => {
    await gameApi.logout();
    window.localStorage.removeItem(CACHE_KEY);
    setAuth(null);
    setSnapshot(null);
    setSaves([]);
    setAiSettings(null);
    setGenerationSettings(null);
    setRestartTargetId(null);
    setTurnLimitNotice(null);
    setStage("login");
    setSyncMode("server");
  });

  const saveAiCredential = (apiKey: string) => runBusy(async () => {
    const next = await gameApi.saveAiCredential(apiKey);
    setAiSettings(next);
    setShowAiSettings(false);
    setToast("自己的推演密钥已启用");
  });

  const saveProviderCredential = (provider: string, model: string, apiKey: string) => runBusy(async () => {
    setGenerationSettings(await gameApi.saveProviderCredential(provider, model, apiKey));
    setShowAiSettings(false);
    setToast("自己的推演密钥已验证并启用");
  });

  const activateProviderCredential = (provider: string, model: string) => runBusy(async () => {
    setGenerationSettings(await gameApi.saveGenerationSettings({ mode: "PERSONAL", provider, model }));
    setShowAiSettings(false);
    setToast("已切换为自己的推演密钥");
  });

  const usePlatformGeneration = () => runBusy(async () => {
    setGenerationSettings(await gameApi.saveGenerationSettings({ mode: "PLATFORM" }));
    setShowAiSettings(false);
    setToast("已切换为平台推演");
  });

  const deleteProviderCredential = (provider: string) => runBusy(async () => {
    setGenerationSettings(await gameApi.deleteProviderCredential(provider));
    setShowAiSettings(false);
    setToast("密钥已移除，现使用平台推演");
  });

  const deleteAiCredential = () => runBusy(async () => {
    await gameApi.deleteAiCredential();
    setAiSettings(await gameApi.aiSettings());
    setShowAiSettings(false);
    setToast("已恢复使用账号赠送回合");
  });

  const savePassword = (currentPassword: string, newPassword: string) => runBusy(async () => {
    const nextAuth = await gameApi.setPassword(currentPassword, newPassword);
    setAuth(nextAuth);
    setShowPasswordSettings(false);
    setToast("登录密码已妥善保存");
  });

  const deleteAccount = () => runBusy(async () => {
    await gameApi.deleteAccount("确认注销");
    window.localStorage.removeItem(CACHE_KEY);
    setShowDeleteAccount(false);
    setAuth(null);
    setSnapshot(null);
    setAiSettings(null);
    setGenerationSettings(null);
    setTurnLimitNotice(null);
    setStage("login");
    setSyncMode("server");
    setToast("账号与全部命卷已注销");
  });

  const copySupportGroup = async () => {
    if (!turnLimitNotice?.supportGroup) return;
    try {
      await navigator.clipboard.writeText(turnLimitNotice.supportGroup);
      setToast(`QQ群号 ${turnLimitNotice.supportGroup} 已复制`);
    } catch {
      setToast(`请手动记录QQ群号：${turnLimitNotice.supportGroup}`);
    }
  };

  const openTurnLimitSettings = () => {
    setTurnLimitNotice(null);
    setError(null);
    if (aiSettings?.customApiEnabled) setShowAiSettings(true);
  };

  return (
    <div className="game-shell" data-composer-focused={isComposerFocused} data-entry={!showGameChrome} data-font-size={fontSize} data-keyboard-open={keyboardOffset > 80} data-theme={actualTheme} onFocusCapture={(event) => { const target = event.target; if (target instanceof HTMLElement && target.matches("input, textarea, select") && !target.closest(".action-composer")) revealFormControl(target); }} style={{ "--keyboard-offset": `${keyboardOffset}px` } as CSSProperties}>
      <div className="paper-background" aria-hidden="true" />
      <header className="game-header"><div className="game-header-inner"><div className="brand-lockup"><strong>问仙录</strong><span>浮生一卷 · {bootstrap?.version ?? "版本同步中"}</span></div>{auth?.authenticated && !["loading", "login"].includes(stage) && <button aria-label="打开设置" className="header-settings-button" onClick={() => setShowQuickSettings(true)} title="设置" type="button"><GearIcon /></button>}</div></header>
      <div className="game-scroll" data-phase={game?.phase ?? stage} key={`${stage}-${tab}-${game?.turn ?? 0}`}><main className="screen-content" data-testid="game-content">
        {aiSettings && !legacyReadOnly && (["create", "fate", "talent"] as AppStage[]).includes(stage) && <GenerationAccessCard generation={v014 ? generationSettings : null} isBusy={isBusy} onOpen={() => setShowAiSettings(true)} settings={aiSettings} />}
        {stage === "loading" && <LoadingView />}
        {stage === "login" && <LoginView accessStatus={accessStatus} announcement={loginAnnouncement} error={error} isBusy={isBusy} offline={syncMode === "offline"} onAuthenticate={authenticate} />}
        {stage === "saves" && <SaveSelectionView isBusy={isBusy} maxActiveSaves={maxActiveSaves} onCreate={createAdditionalSave} onDelete={setAbandonTarget} onLogout={() => void logout()} onOpen={(save) => void openSave(save)} saves={saves} />}
        {stage === "create" && bootstrap && (legacyReadOnly ? <LegacyArchiveUnavailableView onLogout={() => void logout()} /> : <CharacterCreationView alternatives={nameAlternatives} bootstrap={bootstrap} draft={characterDraft} error={error} isBusy={isBusy} onCancelRestart={restartTargetId ? () => void cancelRestart() : undefined} onCreate={createCharacter} onDraft={updateCharacterDraft} onGenerateName={generateName} onGenerateRoutes={generateCharacterRoutes} onLogout={() => void logout()} onSelectRoute={selectCharacterRoute} routeOptions={characterRoutes} selectedRouteId={selectedCharacterRouteId} />)}
        {stage === "fate" && game && (legacyReadOnly ? <LegacyArchiveUnavailableView onLogout={() => void logout()} /> : <FateSelectionView error={error} game={game} isBusy={isBusy} onRecreate={() => setResetIntent("FATES")} onSelect={setSelectedFate} onStart={chooseFate} selectedId={selectedFate} />)}
        {stage === "talent" && game && (legacyReadOnly ? <LegacyArchiveUnavailableView onLogout={() => void logout()} /> : <TalentSelectionView error={error} game={game} isBusy={isBusy} onEnter={enterWithTalent} onReroll={rerollTalents} onSelect={setSelectedTalent} onSkip={enterWithoutTalent} selectedId={selectedTalent} />)}
        {stage === "game" && game?.phase === "playing" && tab === "adventure" && <AdventureView aiSettings={aiSettings} choices={choices} error={error} game={game} isBusy={isBusy} offline={syncMode !== "server"} onChoice={(choiceId) => void takeTurn("CHOICE", { choiceId })} onResolutionDismiss={dismissResolution} onSuggestion={(content) => void takeTurn("TEXT", { content })} readOnly={legacyReadOnly} resolution={snapshot?.actionResolution} />}
        {stage === "game" && game?.phase === "playing" && tab === "character" && <CharacterView game={game} isBusy={isBusy} onCleanup={v014 ? setCleanupTarget : undefined} />}
        {stage === "game" && game?.phase === "playing" && tab === "chronicle" && <ChronicleView game={game} />}
        {stage === "game" && game?.phase === "playing" && tab === "world" && <WorldView game={game} isBusy={isBusy} maxActiveSaves={maxActiveSaves} onAbandonSave={setAbandonTarget} onCreateSave={createAdditionalSave} onOpenSave={(save) => void openSave(save)} readOnly={legacyReadOnly} releaseNotes={releaseNotes} saves={saves} version={bootstrap?.version ?? "版本同步中"} />}
        {stage === "game" && game?.phase === "dead" && tab !== "chronicle" && <DeathView game={game} isBusy={isBusy} onChronicle={() => setTab("chronicle")} onLogout={() => void logout()} onRebirth={() => void leaveDeath()} readOnly={legacyReadOnly} />}
        {stage === "game" && game?.phase === "dead" && tab === "chronicle" && <div className="death-chronicle"><ChronicleView game={game} /><button className="reset-button" onClick={() => setTab("adventure")} type="button">返回终局结算</button></div>}
      </main></div>
      {stage === "game" && game?.phase === "playing" && tab === "adventure" && !legacyReadOnly && <form className="action-composer" onSubmit={(event) => { event.preventDefault(); if (draft.trim()) void takeTurn("TEXT", { content: draft.trim() }); }}><textarea aria-label="自由行动" maxLength={300} onBlur={() => window.setTimeout(() => setIsComposerFocused(false), 120)} onChange={(event) => setDraft(event.currentTarget.value)} onFocus={(event) => { const target = event.currentTarget; setIsComposerFocused(true); revealFormControl(target, 160); }} placeholder="输入行动、物品或天赋…" rows={1} value={draft} />{draft && <button aria-label="清空输入" className="clear-draft" onClick={() => setDraft("")} type="button"><Cross2Icon /></button>}<button className="act-button" disabled={!draft.trim() || isBusy || syncMode !== "server"} type="submit"><span>行动</span><EnterIcon /></button></form>}
      {stage === "game" && game?.phase === "playing" && !isComposerFocused && <nav className="bottom-nav" aria-label="主要导航">{([["adventure", "历练"], ["character", "角色"], ["chronicle", "纪事"], ["world", "世界"]] as Array<[TabId, string]>).map(([id, label]) => <button aria-current={tab === id ? "page" : undefined} key={id} onClick={() => { setError(null); setTab(id); }} type="button"><TabIcon id={id} /><span>{label}</span></button>)}</nav>}
      {resetIntent && game && <ResetConfirmDialog game={game} intent={resetIntent} isBusy={isBusy} onCancel={() => setResetIntent(null)} onConfirm={() => { setResetIntent(null); prepareRestart(); }} />}
      {abandonTarget && <AbandonSaveDialog isBusy={isBusy} onCancel={() => setAbandonTarget(null)} onConfirm={() => void abandonSave()} save={abandonTarget} />}
      {pendingRelease && <ReleaseAnnouncementDialog note={pendingRelease} onClose={() => { const id = pendingRelease.id; setPendingRelease(null); setReleaseNotes((notes) => notes.map((note) => note.id === id ? { ...note, unread: false } : note)); void gameApi.markReleaseNoteRead(id); }} />}
      {turnLimitNotice && <TurnLimitDialog notice={turnLimitNotice} onClose={() => setTurnLimitNotice(null)} onCopyGroup={() => void copySupportGroup()} onOpenSettings={openTurnLimitSettings} />}
      {maintenanceNotice && <MaintenanceDialog notice={maintenanceNotice} onClose={() => setMaintenanceNotice(null)} />}
      {cleanupTarget && <RecordCleanupDialog isBusy={isBusy} onCancel={() => setCleanupTarget(null)} onConfirm={() => void confirmCleanup()} target={cleanupTarget} />}
      {showAiSettings && aiSettings && (v014 && generationSettings
        ? <GenerationSettingsDialog error={error} isBusy={isBusy} onActivate={(provider, model) => void activateProviderCredential(provider, model)} onCancel={() => setShowAiSettings(false)} onDelete={(provider) => void deleteProviderCredential(provider)} onSave={(provider, model, key) => void saveProviderCredential(provider, model, key)} onUsePlatform={() => void usePlatformGeneration()} settings={generationSettings} />
        : <LegacyAiCredentialDialog error={error} isBusy={isBusy} onCancel={() => setShowAiSettings(false)} onDelete={() => void deleteAiCredential()} onSave={(key) => void saveAiCredential(key)} settings={aiSettings} />)}
      {showPasswordSettings && <PasswordDialog configured={auth?.passwordConfigured ?? false} mandatory={auth?.authenticated === true && !auth.passwordConfigured} error={error} isBusy={isBusy} onCancel={() => setShowPasswordSettings(false)} onSave={(current, next) => void savePassword(current, next)} />}
      {showDeleteAccount && <DeleteAccountDialog isBusy={isBusy} onCancel={() => setShowDeleteAccount(false)} onConfirm={() => void deleteAccount()} />}
      {showCorrection && <StoryCorrectionDialog error={error} isBusy={isBusy} onCancel={closeCorrection} onConfirm={() => void confirmCorrection()} onEdit={() => { setCorrectionPreview(null); setError(null); }} onPreview={(instruction) => void previewCorrection(instruction)} preview={correctionPreview} />}
      {showQuickSettings && <QuickSettingsPanel aiSettings={aiSettings} email={email} fontSize={fontSize} generation={v014 ? generationSettings : null} isBusy={isBusy} legacyReadOnly={legacyReadOnly} onAiSettings={() => { setShowQuickSettings(false); setShowAiSettings(true); }} onClose={() => setShowQuickSettings(false)} onCorrection={v014 && game?.phase === "playing" && !legacyReadOnly ? () => { setShowQuickSettings(false); setCorrectionPreview(null); setError(null); setShowCorrection(true); } : undefined} onDeleteAccount={() => { setShowQuickSettings(false); setShowDeleteAccount(true); }} onFontSize={setFontSize} onLogout={() => { setShowQuickSettings(false); void logout(); }} onPasswordSettings={() => { setShowQuickSettings(false); setShowPasswordSettings(true); }} onPlatform={() => void usePlatformGeneration()} onReset={() => { setShowQuickSettings(false); resetGame(); }} onSaves={() => { setShowQuickSettings(false); setStage("saves"); }} onTheme={setTheme} passwordConfigured={auth?.passwordConfigured ?? false} theme={theme} />}
      {isBusy && (stage === "create" || stage === "fate" || stage === "talent") && <DestinyLoadingOverlay stage={stage} />}
      {toast && <div className="save-toast" role="status"><span className="agent-dot" />{toast}</div>}
    </div>
  );
}

export default function Prototype() {
  return <LegacyPrototype v014 />;
}
