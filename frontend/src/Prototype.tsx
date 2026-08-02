import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import {
  ArrowRightIcon,
  BackpackIcon,
  ChevronRightIcon,
  ClockIcon,
  Cross2Icon,
  EnterIcon,
  GlobeIcon,
  HomeIcon,
  IdCardIcon,
  LightningBoltIcon,
  MagnifyingGlassIcon,
  PersonIcon,
  ReaderIcon,
  ReloadIcon,
  SewingPinFilledIcon,
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
} from "./game-api";

type TabId = "adventure" | "character" | "chronicle" | "world";
type AppStage = "loading" | "login" | "saves" | "create" | "fate" | "talent" | "game";
type ThemeMode = "system" | "paper" | "night";
type FontSizeMode = "compact" | "standard" | "large";
type TurnLimitNotice = { message: string; contactText: string; supportGroup: string };
type MaintenanceNotice = Pick<AccessStatus, "title" | "message" | "resumeAt">;
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
};

const RACE_NAMES: Record<string, string> = { HUMAN: "人族", DEMON: "妖族", SPIRIT: "灵族" };

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
  return `绝天后${worldYear}年 · 第${day}日 · ${periodName(minute)}`;
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
    ? ["天机正在落笔", "为同一名字推演三种不同的凡俗人生"]
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
  onDraft,
  onGenerateName,
  onCreate,
  onCancelRestart,
  onLogout,
}: {
  bootstrap: BootstrapResponse;
  draft: CharacterDraft;
  alternatives: string[];
  isBusy: boolean;
  error: UiProblem | null;
  onDraft: (patch: Partial<CharacterDraft>) => void;
  onGenerateName: () => void;
  onCreate: () => void;
  onCancelRestart?: () => void;
  onLogout: () => void;
}) {
  const nameInputRef = useRef<HTMLInputElement>(null);

  const requestFates = () => {
    if (!draft.playerName.trim()) {
      nameInputRef.current?.focus();
      nameInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
    }
    onCreate();
  };

  return (
    <div className="creation-view">
      <section className="world-intro-card compact-world-card">
        <p className="eyebrow">FIXED WORLD · SINGLE PLAYER</p>
        <div className="world-title-row">
          <div><h1>{bootstrap.world.title}</h1><span>{bootstrap.world.era}</span></div>
          <i data-mode="fixed">世界已定</i>
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

        <div className="two-column-fields single-column-fields">
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
        </fieldset>

        <label className="preference-field origin-premise-field">
          <span>角色根源 <small>必须遵守 · 可留空</small></span>
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
          <span>命格偏好 <small>可留空</small></span>
          <textarea
            maxLength={160}
            onChange={(event) => onDraft({ preference: event.currentTarget.value })}
            placeholder="例如：低起点、宗门线、重谋略；命格只会在既定世界中展开。"
            rows={3}
            value={draft.preference}
          />
        </label>

        {error && <ProblemNotice compact problem={error} />}
        <button className="primary-entry-button" disabled={isBusy} onClick={requestFates} type="button">
          {isBusy ? "天机正在推演三种命格…" : "生成三种命格"}<ArrowRightIcon />
        </button>
        {onCancelRestart && <button className="text-button" disabled={isBusy} onClick={onCancelRestart} type="button">返回原来的命卷</button>}
        <button className="text-button" disabled={isBusy} onClick={onLogout} type="button">退出当前邮箱</button>
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

function AdventureView({ game, choices, resolution, aiSettings, isBusy, offline, error, onChoice, onSuggestion, onResolutionDismiss }: { game: GameState; choices: Choice[]; resolution?: ApiSnapshot["actionResolution"]; aiSettings: AiSettings | null; isBusy: boolean; offline: boolean; error: UiProblem | null; onChoice: (id: string) => void; onSuggestion: (action: string) => void; onResolutionDismiss: () => void }) {
  return (
    <div className="adventure-view">
      <section className="scene-heading" aria-labelledby="scene-title"><span className="leaf-mark" aria-hidden="true">〆</span><div><p className="eyebrow">当前场景</p><h1 id="scene-title">{game.sceneTitle}</h1></div></section>
      <div className="ornament-line" aria-hidden="true"><span /></div>
      <section className="narrative" aria-label="剧情">{game.narrative.map((paragraph, index) => <p data-beat={index > 0 && index % 3 === 0 ? "new" : undefined} key={`${index}-${paragraph}`}>{paragraph}</p>)}</section>
      {game.signals.length > 0 && <section className="signal-strip" aria-label="局势变化">{game.signals.map((signal, index) => <span className={index % 2 ? "signal-danger" : "signal-safe"} key={`${index}-${signal}`}>{signal}</span>)}</section>}
      {resolution && <section className="action-resolution" data-result={resolution.result} role="status">
        <p className="eyebrow">本轮未发生 · {resolution.result === "CLARIFY" ? "行动待明" : "世界裁决"}</p>
        <h2>{resolution.title}</h2>
        <p>{resolution.reason}</p>
        <div className="resolution-suggestions" aria-label="可改写的行动">
          {resolution.suggestions.map((suggestion) => <button disabled={isBusy || offline} key={suggestion} onClick={() => onSuggestion(suggestion)} type="button"><span><small>可改为</small>{suggestion}</span><ChevronRightIcon aria-hidden="true" /></button>)}
        </div>
        <button className="resolution-dismiss" disabled={isBusy} onClick={onResolutionDismiss} type="button">返回原计划</button>
      </section>}
      {isBusy && <AgentProgress />}
      {!resolution && <section className="choice-list" aria-busy={isBusy} aria-label="可选行动">
        {choices.map((choice, index) => (
          <button className="choice-card" data-tone={choice.tone ?? "default"} disabled={isBusy || offline} key={choice.id} onClick={() => onChoice(choice.id)} type="button">
            <span className="choice-seal" aria-hidden="true">{index + 1}</span><span className="choice-copy"><strong>{choice.label}</strong><small>{choice.hint}</small></span><ChevronRightIcon aria-hidden="true" />
          </button>
        ))}
      </section>}
      {offline && !isBusy && <div className="agent-thinking offline-note" role="status"><LightningBoltIcon /><span>命卷暂时无法续写，已有经历仍可查阅。</span></div>}
      {error && !isBusy && <ProblemNotice problem={error} />}
      {aiSettings && <p className="daily-chapter-usage">{aiSettings.usingCustomApi ? "正在使用自己的推演密钥 · 不消耗赠送回合" : `当前账号回合 ${aiSettings.usedTurns}/${aiSettings.turnLimit}`}</p>}
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

function CharacterView({ game }: { game: GameState }) {
  const player = game.player;
  if (!player) return null;
  const inventory = game.inventory.filter((item) => item.category !== "CURRENCY");
  const copperWen = game.wallet?.copperWen ?? player.silver ?? 0;
  const mortalMoney = formatMortalMoney(copperWen);
  const spiritMoney = formatSpiritMoney(game.wallet);
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">CHARACTER RECORD</p><h1>角色</h1></div><span className="turn-badge">第 {game.turn + 1} 回</span></div>
      <section className="hero-card"><div className="hero-avatar"><PersonIcon /></div><div><h2>{player.name}</h2><p>{player.identity} · {player.realm} · {player.constitution}</p><small>{player.age}/{player.lifespan}岁 · {RACE_NAMES[player.race] ?? player.race}</small></div><span className="realm-stamp">{player.realm}</span></section>
      <section className="life-purpose-card">{player.origin && <p><strong>此身来处</strong>{player.origin}</p>}{player.originPremise && <p><strong>本源设定</strong>{player.originPremise}</p>}<p><strong>此生所求</strong>{player.goal}</p><p><strong>未偿旧债</strong>{player.burden}</p></section>
      <section className="record-section"><div className="section-label"><LightningBoltIcon /><h2>天赋与功法</h2><span>{game.abilities.length}</span></div><div className="ability-list">
        {game.abilities.map((ability) => <article className="ability-row" key={ability.id}><div><h3>{ability.name}<small>{ability.type === "TALENT" ? "天赋" : ability.type === "TECHNIQUE" ? "功法" : ability.type === "SPELL" ? "术法" : "异能"} · {ability.stage}</small></h3><p>{ability.description}</p><span>缘起：{ability.source}</span></div></article>)}
      </div></section>
      <section className="record-section"><div className="section-label"><span className="section-glyph">泉</span><h2>随身财物</h2></div><div className="wallet-card">
        <p><span>凡俗钱财</span><strong>{mortalMoney}</strong></p>
        <p><span>修行资粮</span><strong>{spiritMoney}</strong></p>
        <small>凡俗钱财与灵石分别记账，只有剧情中真实发生的交易才会增减。</small>
      </div></section>
      <section className="record-section"><div className="section-label"><BackpackIcon /><h2>行囊</h2><span>{inventory.length}</span></div><div className="inventory-list">
        {inventory.map((item) => <article className="inventory-item" key={item.id}><div><h3>{item.name}<small>{item.quality} · ×{item.quantity}</small></h3><p>{item.description}</p></div></article>)}
      </div></section>
      <section className="record-section"><div className="section-label"><IdCardIcon /><h2>已登场角色</h2><span>{game.characters.length}</span></div><div className="character-list">
        {game.characters.map((character) => <article className="character-row" key={character.id}><div className="mini-avatar">{character.name.slice(0, 1)}</div><div><h3>{character.name}<small>{character.identity}</small></h3><p>{character.state}</p><span>{character.location} · {character.relation}</span></div></article>)}
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
}: {
  game: GameState;
  isBusy: boolean;
  onChronicle: () => void;
  onRebirth: () => void;
  onLogout: () => void;
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
          <button className="rebirth-button" disabled={isBusy} onClick={onRebirth} type="button"><ReloadIcon />转世重开</button>
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

function SaveCards({ saves, currentSaveId, maxActiveSaves, isBusy, onOpen, onCreate, onAbandon }: { saves: SaveSummary[]; currentSaveId?: string; maxActiveSaves: number; isBusy: boolean; onOpen: (save: SaveSummary) => void; onCreate: () => void; onAbandon: (save: SaveSummary) => void }) {
  return <div className="save-card-list">{saves.map((save) => <article className="save-slot-card" data-current={save.saveId === currentSaveId} key={save.saveId}><button className="save-slot-main" disabled={isBusy} onClick={() => onOpen(save)} type="button"><span>命卷 {save.slotNo}{save.saveId === currentSaveId ? " · 当前" : ""}</span><strong>{save.playerName}</strong><p>{save.realm} · {save.currentLocation || "尚未入世"}</p><small>第 {save.turn} 回 · {new Date(save.updatedAt).toLocaleDateString("zh-CN")}</small></button><button className="save-slot-abandon" disabled={isBusy} onClick={() => onAbandon(save)} type="button">封存</button></article>)}{saves.length < maxActiveSaves && <button className="new-save-card" disabled={isBusy} onClick={onCreate} type="button"><span>＋</span><strong>开启新的命卷</strong><small>与现有角色分别经历、共享账号回合</small></button>}</div>;
}

function SaveSelectionView({ saves, maxActiveSaves, isBusy, onOpen, onCreate, onAbandon, onLogout }: { saves: SaveSummary[]; maxActiveSaves: number; isBusy: boolean; onOpen: (save: SaveSummary) => void; onCreate: () => void; onAbandon: (save: SaveSummary) => void; onLogout: () => void }) {
  return <div className="record-view save-selection-view"><div className="page-title-row"><div><p className="eyebrow">YOUR DESTINY SCROLLS</p><h1>选择命卷</h1></div><ReaderIcon className="title-icon" /></div><p className="save-selection-copy">同一账号可以保留 {maxActiveSaves} 名仍在世的角色。每份命卷拥有独立的经历、人物关系与天机上下文。</p><SaveCards isBusy={isBusy} maxActiveSaves={maxActiveSaves} onAbandon={onAbandon} onCreate={onCreate} onOpen={onOpen} saves={saves} /><button className="reset-button save-logout" onClick={onLogout} type="button">退出邮箱</button></div>;
}

function GenerationAccessCard({ settings, isBusy, onOpen }: { settings: AiSettings; isBusy: boolean; onOpen: () => void }) {
  const title = settings.usingCustomApi
    ? "自己的推演密钥已启用"
    : `账号赠送回合 ${settings.usedTurns}/${settings.turnLimit}`;
  const detail = settings.usingCustomApi
    ? `当前密钥尾号 ${settings.maskedSuffix ?? "已隐藏"}，命格、天赋和正文都会使用自己的密钥。`
    : settings.remainingTurns > 0
      ? `还可使用 ${settings.remainingTurns} 回；也可以提前接入自己的密钥。`
      : "赠送回合已用完，接入自己的密钥后仍可重开和续写命卷。";
  return <section className="generation-access-card"><div><span>命卷推演</span><strong>{title}</strong><p>{detail}</p></div><button disabled={isBusy || !settings.customApiEnabled} onClick={onOpen} type="button">{settings.credentialConfigured ? "管理密钥" : "接入密钥"}</button></section>;
}

function AbandonSaveDialog({ save, isBusy, onCancel, onConfirm }: { save: SaveSummary; isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const [confirmation, setConfirmation] = useState("");
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">藏</div><p className="eyebrow">ARCHIVE THIS DESTINY</p><h2>封存「{save.playerName}」的命卷</h2><p>封存后这名角色将不再出现在游戏中，但经历会作为前世记录保留，管理员仍可协助核查。此操作不会返还已经使用的账号回合。</p><label className="credential-field">请输入角色名“{save.playerName}”<input autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.currentTarget.value)} /></label><div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">继续此世</button><button className="ink-dialog-confirm destructive" disabled={isBusy || confirmation !== save.playerName} onClick={onConfirm} type="button">确认封存</button></div></section></div>;
}

function WorldView({ game, saves, maxActiveSaves, version, releaseNotes, syncMode, email, passwordConfigured, aiSettings, theme, fontSize, isBusy, onOpenSave, onCreateSave, onAbandonSave, onTheme, onFontSize, onAiSettings, onPasswordSettings, onReset, onLogout, onDeleteAccount }: { game: GameState; saves: SaveSummary[]; maxActiveSaves: number; version: string; releaseNotes: ReleaseNote[]; syncMode: SyncMode; email: string; passwordConfigured: boolean; aiSettings: AiSettings | null; theme: ThemeMode; fontSize: FontSizeMode; isBusy: boolean; onOpenSave: (save: SaveSummary) => void; onCreateSave: () => void; onAbandonSave: (save: SaveSummary) => void; onTheme: (theme: ThemeMode) => void; onFontSize: (fontSize: FontSizeMode) => void; onAiSettings: () => void; onPasswordSettings: () => void; onReset: () => void; onLogout: () => void; onDeleteAccount: () => void }) {
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">KNOWN WORLD</p><h1>世界</h1></div><GlobeIcon className="title-icon" /></div>
      <section className="location-card"><div className="location-art" /><div className="location-copy"><span><SewingPinFilledIcon /> 当前位置</span><h2>{game.sceneTitle} · {game.currentLocation}</h2><p>{game.world.summary}</p></div></section>
      <section className="world-law-card"><p><strong>修行法则</strong>{game.world.cultivationLaw}</p><p><strong>生存法则</strong>{game.world.dangerRule}</p><div>{game.world.factions.map((faction) => <span key={faction}>{faction}</span>)}</div></section>
      <section className="record-section"><div className="section-label"><ReaderIcon /><h2>我的命卷</h2><span>{saves.length}/{maxActiveSaves}</span></div><SaveCards currentSaveId={game.saveId} isBusy={isBusy} maxActiveSaves={maxActiveSaves} onAbandon={onAbandonSave} onCreate={onCreateSave} onOpen={onOpenSave} saves={saves} /></section>
      <section className="record-section"><div className="section-label"><MagnifyingGlassIcon /><h2>未解线索</h2><span>{game.threads.length}</span></div><div className="thread-list">{game.threads.map((thread) => <article className="thread-card" key={thread.id}><div><h3>{thread.title}</h3><p>{thread.state}</p></div><span data-danger={thread.danger > 75 ? "high" : "normal"}>{dangerName(thread.danger)}</span></article>)}</div></section>
      <section className="agent-record-card"><span className="agent-dot" /><div><h2>{syncMode === "server" ? "本世命卷已同步" : "命卷暂时封存"}</h2><p>{syncMode === "server" ? "人物、纪事与未解线索均已妥善保存。" : "已有经历仍可查阅，暂时无法续写。"} 当前邮箱：{email || "未知"}</p></div></section>
      <section className="ai-settings-card"><div><p className="eyebrow">账号安全</p><h2>{passwordConfigured ? "登录密码已设置" : "请为旧账号设置密码"}</h2><p>{passwordConfigured ? "密码只用于登录，不会在页面或管理台中明文展示。" : "设置后可在其他设备使用邮箱和密码找回当前命卷。"}</p></div><button className="secondary-setting-button" onClick={onPasswordSettings} type="button">{passwordConfigured ? "修改密码" : "设置密码"}</button></section>
      <section className="ai-settings-card"><div><p className="eyebrow">命卷续写</p><h2>{!aiSettings ? "续写设置暂未同步" : aiSettings.usingCustomApi ? "正在使用自己的推演密钥" : `当前账号回合 ${aiSettings.usedTurns}/${aiSettings.turnLimit}`}</h2><p>{!aiSettings ? "请确认网络连接后刷新页面，已有命卷不会受到影响。" : aiSettings.usingCustomApi ? `密钥尾号 · ${aiSettings.maskedSuffix ?? "已隐藏"}，推演不消耗赠送回合。` : `当前账号还可推演 ${aiSettings.remainingTurns} 回。自己的密钥只用于你的剧情续写，并会加密保存。`}</p></div><button className="secondary-setting-button" disabled={!aiSettings || !aiSettings.customApiEnabled} onClick={onAiSettings} type="button">{aiSettings?.credentialConfigured ? "管理密钥" : "接入自己的密钥"}</button></section>
      <section className="release-history"><div className="section-label"><ReaderIcon /><h2>版本纪事</h2><span>{version}</span></div>{releaseNotes.length === 0 ? <p className="empty-copy">尚无公开版本纪事。</p> : releaseNotes.map((note) => <details key={note.id}><summary><span>{note.version}</span><strong>{note.title}</strong><small>{new Date(note.publishedAt).toLocaleDateString("zh-CN")}</small></summary><p>{note.content}</p></details>)}</section>
      <section className="theme-card">
        <div><strong>阅读外观</strong><span>夜间模式采用月下宣纸，不改变游戏进度。</span></div>
        <div className="theme-switch">{([["system", "跟随系统"], ["paper", "宣纸"], ["night", "月夜"]] as Array<[ThemeMode, string]>).map(([id, label]) => <button aria-pressed={theme === id} data-selected={theme === id} key={id} onClick={() => onTheme(id)} type="button">{label}</button>)}</div>
        <div className="font-size-setting"><div><strong>字体大小</strong><span>调整剧情、选项与资料正文的阅读字号。</span></div><div className="theme-switch">{([["compact", "小字"], ["standard", "标准"], ["large", "大字"]] as Array<[FontSizeMode, string]>).map(([id, label]) => <button aria-pressed={fontSize === id} data-selected={fontSize === id} key={id} onClick={() => onFontSize(id)} type="button">{label}</button>)}</div></div>
      </section>
      <div className="world-actions"><button className="reset-button" onClick={onReset} type="button"><ReloadIcon />重开此世</button><button className="reset-button" onClick={onLogout} type="button">退出邮箱</button></div>
      <button className="delete-account-button" onClick={onDeleteAccount} type="button">注销账号与全部命卷</button>
    </div>
  );
}

function AiCredentialDialog({ settings, error, isBusy, onCancel, onSave, onDelete }: { settings: AiSettings; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onSave: (key: string) => void; onDelete: () => void }) {
  const [apiKey, setApiKey] = useState("");
  const insecureRemote = window.location.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(window.location.hostname);
  const keyTooShort = apiKey.trim().length > 0 && apiKey.trim().length < 20;
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">钥</div><p className="eyebrow">PRIVATE GENERATION KEY</p><h2>自己的推演密钥</h2><p>密钥会在服务器加密保存，只用于续写你的游戏故事；页面不会再次展示完整内容。</p>{insecureRemote && <p className="credential-warning">当前连接未启用 HTTPS，为避免密钥在传输中暴露，暂不允许保存。请改用安全地址。</p>}{error && <ProblemNotice compact problem={error} />}<label className="credential-field">DeepSeek API Key<input autoComplete="off" disabled={isBusy || insecureRemote} placeholder={settings.credentialConfigured ? `当前尾号 ${settings.maskedSuffix ?? "****"}，输入新密钥可替换` : "sk-…"} type="password" value={apiKey} onChange={(event) => setApiKey(event.currentTarget.value)} />{keyTooShort && <small>密钥长度不足，请粘贴完整的 API Key。</small>}</label><div className="ink-dialog-actions credential-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">返回</button>{settings.credentialConfigured && <button className="credential-delete" disabled={isBusy} onClick={onDelete} type="button">移除密钥</button>}<button className="ink-dialog-confirm" disabled={isBusy || insecureRemote || apiKey.trim().length < 20} onClick={() => onSave(apiKey.trim())} type="button">{isBusy ? "正在验证…" : "验证并保存"}</button></div></section></div>;
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

export default function Prototype() {
  const [stage, setStage] = useState<AppStage>("loading");
  const [tab, setTab] = useState<TabId>("adventure");
  const [auth, setAuth] = useState<AuthResponse | null>(null);
  const [bootstrap, setBootstrap] = useState<BootstrapResponse | null>(null);
  const [snapshot, setSnapshot] = useState<ApiSnapshot | null>(null);
  const [saves, setSaves] = useState<SaveSummary[]>([]);
  const [maxActiveSaves, setMaxActiveSaves] = useState(2);
  const [abandonTarget, setAbandonTarget] = useState<SaveSummary | null>(null);
  const [draft, setDraft] = useState("");
  const [characterDraft, setCharacterDraft] = useState<CharacterDraft>(DEFAULT_DRAFT);
  const [nameAlternatives, setNameAlternatives] = useState<string[]>([]);
  const [generatedName, setGeneratedName] = useState("");
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

  const game = snapshot?.state ?? null;
  const choices = snapshot?.choices ?? game?.choices ?? [];
  const email = auth?.user?.email ?? "";
  const showGameChrome = stage === "saves" || stage === "fate" || stage === "talent" || stage === "game";
  const actualTheme = theme === "system" ? (systemDark ? "night" : "paper") : theme;

  const storeSnapshot = (next: ApiSnapshot) => {
    setSnapshot(next);
    cacheSnapshot(next);
  };

  const loadBootstrap = async () => {
    const next = await gameApi.bootstrap();
    setBootstrap(next);
    return next;
  };

  const stageFor = (next: ApiSnapshot) => next.state.phase === "setup" ? "fate" : next.state.phase === "talent" ? "talent" : "game";

  const refreshSaves = async () => {
    const list = await gameApi.saves();
    setSaves(list.saves);
    setMaxActiveSaves(list.maxActiveSaves);
    return list;
  };

  const enterAuthenticatedFlow = async (nextAuth: AuthResponse) => {
    setAuth(nextAuth);
    setSyncMode("server");
    await loadBootstrap();
    const [notes, pending, nextAiSettings] = await Promise.all([gameApi.releaseNotes(), gameApi.pendingReleaseNote(), gameApi.aiSettings()]);
    setReleaseNotes(notes);
    setPendingRelease(pending);
    setAiSettings(nextAiSettings);
    if (!nextAuth.passwordConfigured) setShowPasswordSettings(true);
    const saveList = await refreshSaves();
    if (saveList.saves.length > 1) {
      setSnapshot(null);
      setStage("saves");
      return nextAiSettings;
    }
    if (saveList.saves.length === 1) {
      const current = await gameApi.getSave(saveList.saves[0].saveId);
      storeSnapshot(current);
      setTab("adventure");
      setStage(stageFor(current));
      return nextAiSettings;
    }
    setSnapshot(null);
    setCharacterDraft(DEFAULT_DRAFT);
    setNameAlternatives([]);
    setGeneratedName("");
    setStage("create");
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
      if (caught instanceof ApiError && caught.code === "STATE_CONFLICT" && snapshot?.state.saveId) {
        try {
          const refreshed = await gameApi.getSave(snapshot.state.saveId);
          storeSnapshot(refreshed);
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
  });

  const updateCharacterDraft = (patch: Partial<CharacterDraft>) => {
    const identityChanged = patch.race !== undefined || patch.gender !== undefined;
    if (identityChanged) {
      setNameAlternatives([]);
      setCharacterDraft((current) => ({
        ...current,
        ...patch,
        playerName: current.playerName === generatedName ? "" : current.playerName,
      }));
      setGeneratedName("");
      return;
    }
    if (patch.playerName !== undefined) {
      setGeneratedName(nameAlternatives.includes(patch.playerName) ? patch.playerName : "");
    }
    setCharacterDraft((current) => ({ ...current, ...patch }));
  };

  const createCharacter = () => runBusy(async () => {
    if (!characterDraft.gender || !characterDraft.race) {
      throw new ApiError("请先选择性别与种族，再推演命格", 400);
    }
    if (!characterDraft.playerName.trim()) {
      throw new ApiError("请先输入角色名，或点击“随机雅名”生成一个名字", 400);
    }
    const validation = await gameApi.validateName(characterDraft.playerName);
    if (!validation.valid) throw new ApiError(validation.message, 400);
    const request = { ...characterDraft, playerName: validation.normalizedName };
    const created = restartTargetId
      ? await gameApi.restartSave(restartTargetId, request)
      : await gameApi.createSave(request);
    setRestartTargetId(null);
    storeSnapshot(created);
    await refreshSaves();
    setSelectedFate("");
    setStage("fate");
    setToast("三份命格已经落定");
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
    const next = await gameApi.turn(game.saveId, snapshot.revision, inputType, payload);
    storeSnapshot(next);
    await refreshSaves();
    gameApi.aiSettings().then(setAiSettings).catch(() => undefined);
    setDraft("");
    setTab("adventure");
    setSyncMode("server");
    if (!next.actionResolution) setToast("本回合已写入统一时间线");
  });

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
    setStage("create");
  };

  const cancelRestart = () => runBusy(async () => {
    if (!restartTargetId) return;
    const current = await gameApi.getSave(restartTargetId);
    setRestartTargetId(null);
    storeSnapshot(current);
    setTab("adventure");
    setStage(stageFor(current));
  });

  const openSave = (save: SaveSummary) => runBusy(async () => {
    const next = await gameApi.getSave(save.saveId);
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
    setStage("create");
  };

  const abandonSave = () => runBusy(async () => {
    if (!abandonTarget) return;
    const targetId = abandonTarget.saveId;
    await gameApi.abandonSave(targetId, abandonTarget.playerName);
    setAbandonTarget(null);
    if (snapshot?.state.saveId === targetId) {
      setSnapshot(null);
      window.localStorage.removeItem(CACHE_KEY);
    }
    const list = await refreshSaves();
    if (list.saves.length === 0) setStage("create");
    else if (list.saves.length === 1) {
      const next = await gameApi.getSave(list.saves[0].saveId);
      storeSnapshot(next);
      setTab("adventure");
      setStage(stageFor(next));
    }
    else setStage("saves");
    setToast("命卷已封存为前世记录");
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

  const headerText = useMemo(() => {
    if (game?.phase === "dead") return `${formatWorldTime(game.worldYear, game.day, game.minute)} · 此世已终`;
    if (game?.phase === "playing") return formatWorldTime(game.worldYear, game.day, game.minute);
    return game?.world.era || bootstrap?.world.era || "玄墟界 · 命卷未启";
  }, [bootstrap, game]);

  return (
    <div className="game-shell" data-composer-focused={isComposerFocused} data-entry={!showGameChrome} data-font-size={fontSize} data-keyboard-open={keyboardOffset > 80} data-theme={actualTheme} onFocusCapture={(event) => { const target = event.target; if (target instanceof HTMLElement && target.matches("input, textarea, select") && !target.closest(".action-composer")) revealFormControl(target); }} style={{ "--keyboard-offset": `${keyboardOffset}px` } as CSSProperties}>
      <div className="paper-background" aria-hidden="true" />
      <header className="game-header"><div className="game-header-inner"><div className="brand-lockup"><strong>问仙录</strong><span>浮生一卷 · {bootstrap?.version ?? "版本同步中"}</span></div><div className="world-clock"><span>{headerText}</span><small><SewingPinFilledIcon />{game?.phase === "playing" && game.player ? `${game.currentLocation} · ${game.player.realm} · ${conditionName(game.player.vitality)}` : game?.phase === "dead" && game.player ? `${game.currentLocation} · ${game.player.realm} · 已故` : syncMode === "offline" ? "命卷暂封" : "单机世界 · 命卷待启"}</small></div></div></header>
      <div className="game-scroll" data-phase={game?.phase ?? stage} key={`${stage}-${tab}-${game?.turn ?? 0}`}><main className="screen-content" data-testid="game-content">
        {aiSettings && (["saves", "create", "fate", "talent"] as AppStage[]).includes(stage) && <GenerationAccessCard isBusy={isBusy} onOpen={() => setShowAiSettings(true)} settings={aiSettings} />}
        {stage === "loading" && <LoadingView />}
        {stage === "login" && <LoginView accessStatus={accessStatus} announcement={loginAnnouncement} error={error} isBusy={isBusy} offline={syncMode === "offline"} onAuthenticate={authenticate} />}
        {stage === "saves" && <SaveSelectionView isBusy={isBusy} maxActiveSaves={maxActiveSaves} onAbandon={setAbandonTarget} onCreate={createAdditionalSave} onLogout={() => void logout()} onOpen={(save) => void openSave(save)} saves={saves} />}
        {stage === "create" && bootstrap && <CharacterCreationView alternatives={nameAlternatives} bootstrap={bootstrap} draft={characterDraft} error={error} isBusy={isBusy} onCancelRestart={restartTargetId ? () => void cancelRestart() : undefined} onCreate={createCharacter} onDraft={updateCharacterDraft} onGenerateName={generateName} onLogout={() => void logout()} />}
        {stage === "fate" && game && <FateSelectionView error={error} game={game} isBusy={isBusy} onRecreate={() => setResetIntent("FATES")} onSelect={setSelectedFate} onStart={chooseFate} selectedId={selectedFate} />}
        {stage === "talent" && game && <TalentSelectionView error={error} game={game} isBusy={isBusy} onEnter={enterWithTalent} onReroll={rerollTalents} onSelect={setSelectedTalent} onSkip={enterWithoutTalent} selectedId={selectedTalent} />}
        {stage === "game" && game?.phase === "playing" && tab === "adventure" && <AdventureView aiSettings={aiSettings} choices={choices} error={error} game={game} isBusy={isBusy} offline={syncMode !== "server"} onChoice={(choiceId) => void takeTurn("CHOICE", { choiceId })} onResolutionDismiss={dismissResolution} onSuggestion={(content) => void takeTurn("TEXT", { content })} resolution={snapshot?.actionResolution} />}
        {stage === "game" && game?.phase === "playing" && tab === "character" && <CharacterView game={game} />}
        {stage === "game" && game?.phase === "playing" && tab === "chronicle" && <ChronicleView game={game} />}
        {stage === "game" && game?.phase === "playing" && tab === "world" && <WorldView aiSettings={aiSettings} email={email} fontSize={fontSize} game={game} isBusy={isBusy} maxActiveSaves={maxActiveSaves} onAbandonSave={setAbandonTarget} onAiSettings={() => setShowAiSettings(true)} onCreateSave={createAdditionalSave} onDeleteAccount={() => setShowDeleteAccount(true)} onFontSize={setFontSize} onLogout={() => void logout()} onOpenSave={(save) => void openSave(save)} onPasswordSettings={() => setShowPasswordSettings(true)} onReset={resetGame} onTheme={setTheme} passwordConfigured={auth?.passwordConfigured ?? false} releaseNotes={releaseNotes} saves={saves} syncMode={syncMode} theme={theme} version={bootstrap?.version ?? "版本同步中"} />}
        {stage === "game" && game?.phase === "dead" && tab !== "chronicle" && <DeathView game={game} isBusy={isBusy} onChronicle={() => setTab("chronicle")} onLogout={() => void logout()} onRebirth={() => void leaveDeath()} />}
        {stage === "game" && game?.phase === "dead" && tab === "chronicle" && <div className="death-chronicle"><ChronicleView game={game} /><button className="reset-button" onClick={() => setTab("adventure")} type="button">返回终局结算</button></div>}
      </main></div>
      {stage === "game" && game?.phase === "playing" && tab === "adventure" && <form className="action-composer" onSubmit={(event) => { event.preventDefault(); if (draft.trim()) void takeTurn("TEXT", { content: draft.trim() }); }}><textarea aria-label="自由行动" maxLength={300} onBlur={() => window.setTimeout(() => setIsComposerFocused(false), 120)} onChange={(event) => setDraft(event.currentTarget.value)} onFocus={(event) => { const target = event.currentTarget; setIsComposerFocused(true); revealFormControl(target, 160); }} placeholder="输入行动、物品或天赋…" rows={1} value={draft} />{draft && <button aria-label="清空输入" className="clear-draft" onClick={() => setDraft("")} type="button"><Cross2Icon /></button>}<button className="act-button" disabled={!draft.trim() || isBusy || syncMode !== "server"} type="submit"><span>行动</span><EnterIcon /></button></form>}
      {stage === "game" && game?.phase === "playing" && !isComposerFocused && <nav className="bottom-nav" aria-label="主要导航">{([["adventure", "历练"], ["character", "角色"], ["chronicle", "纪事"], ["world", "世界"]] as Array<[TabId, string]>).map(([id, label]) => <button aria-current={tab === id ? "page" : undefined} key={id} onClick={() => { setError(null); setTab(id); }} type="button"><TabIcon id={id} /><span>{label}</span></button>)}</nav>}
      {resetIntent && game && <ResetConfirmDialog game={game} intent={resetIntent} isBusy={isBusy} onCancel={() => setResetIntent(null)} onConfirm={() => { setResetIntent(null); prepareRestart(); }} />}
      {abandonTarget && <AbandonSaveDialog isBusy={isBusy} onCancel={() => setAbandonTarget(null)} onConfirm={() => void abandonSave()} save={abandonTarget} />}
      {pendingRelease && <ReleaseAnnouncementDialog note={pendingRelease} onClose={() => { const id = pendingRelease.id; setPendingRelease(null); setReleaseNotes((notes) => notes.map((note) => note.id === id ? { ...note, unread: false } : note)); void gameApi.markReleaseNoteRead(id); }} />}
      {turnLimitNotice && <TurnLimitDialog notice={turnLimitNotice} onClose={() => setTurnLimitNotice(null)} onCopyGroup={() => void copySupportGroup()} onOpenSettings={openTurnLimitSettings} />}
      {maintenanceNotice && <MaintenanceDialog notice={maintenanceNotice} onClose={() => setMaintenanceNotice(null)} />}
      {showAiSettings && aiSettings && <AiCredentialDialog error={error} isBusy={isBusy} onCancel={() => setShowAiSettings(false)} onDelete={() => void deleteAiCredential()} onSave={(key) => void saveAiCredential(key)} settings={aiSettings} />}
      {showPasswordSettings && <PasswordDialog configured={auth?.passwordConfigured ?? false} mandatory={auth?.authenticated === true && !auth.passwordConfigured} error={error} isBusy={isBusy} onCancel={() => setShowPasswordSettings(false)} onSave={(current, next) => void savePassword(current, next)} />}
      {showDeleteAccount && <DeleteAccountDialog isBusy={isBusy} onCancel={() => setShowDeleteAccount(false)} onConfirm={() => void deleteAccount()} />}
      {isBusy && (stage === "create" || stage === "fate" || stage === "talent") && <DestinyLoadingOverlay stage={stage} />}
      {toast && <div className="save-toast" role="status"><span className="agent-dot" />{toast}</div>}
    </div>
  );
}
