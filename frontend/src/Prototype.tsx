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
  type AuthResponse,
  type BootstrapResponse,
  type CharacterDraft,
  type Choice,
  type GameState,
  type ReleaseNote,
  type SyncMode,
} from "./game-api";

type TabId = "adventure" | "character" | "chronicle" | "world";
type AppStage = "loading" | "login" | "create" | "fate" | "talent" | "game";
type ThemeMode = "system" | "paper" | "night";

const CACHE_KEY = "wenxian-readonly-cache-v2";
const THEME_KEY = "wenxian-theme";
const DEFAULT_DRAFT: CharacterDraft = {
  playerName: "",
  race: "",
  gender: "",
  lifeGoal: "LONGEVITY",
  difficulty: "NORMAL",
  preference: "",
};

function errorMessage(error: unknown) {
  if (!(error instanceof ApiError)) return "命卷暂时无法响应，请稍后重试";
  if ([502, 503, 504].includes(error.status) || /DeepSeek|Agent|Java|API|后端|模型/i.test(error.message)) {
    return "天机暂时紊乱，本次变化没有写入命卷，请稍后重试";
  }
  return error.message;
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
  onLogin,
}: {
  isBusy: boolean;
  error: string;
  offline: boolean;
  onLogin: (email: string, inviteCode: string) => void;
}) {
  const [email, setEmail] = useState("");
  const [inviteCode, setInviteCode] = useState("");

  return (
    <section className="entry-card login-card">
      <div className="entry-seal" aria-hidden="true">问</div>
      <p className="eyebrow">ENTER XUANXU REALM</p>
      <h1>以邮箱领取命卷</h1>
      <p className="entry-lead">首次进入需填写邀请码。登记成功后，同一邮箱可直接继续自己的单机存档。</p>
      <form
        className="entry-form"
        onSubmit={(event) => {
          event.preventDefault();
          onLogin(email.trim(), inviteCode.trim());
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
          <span>邀请码 <small>老用户可留空</small></span>
          <input
            autoCapitalize="characters"
            onChange={(event) => setInviteCode(event.currentTarget.value)}
            placeholder="输入内部试玩邀请码"
            value={inviteCode}
          />
        </label>
        <button className="primary-entry-button" disabled={isBusy || !email.trim()} type="submit">
          {isBusy ? "正在验明身份…" : "进入问仙录"}<ArrowRightIcon />
        </button>
      </form>
      {(error || offline) && <p className="entry-error">{error || "命卷暂时无法开启，请稍后重试。"}</p>}
      <p className="entry-footnote">当前为小范围试玩准入，不会向该邮箱发送验证码或营销邮件。</p>
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
  onLogout,
}: {
  bootstrap: BootstrapResponse;
  draft: CharacterDraft;
  alternatives: string[];
  isBusy: boolean;
  error: string;
  onDraft: (patch: Partial<CharacterDraft>) => void;
  onGenerateName: () => void;
  onCreate: () => void;
  onLogout: () => void;
}) {
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
                maxLength={6}
                onChange={(event) => onDraft({ playerName: event.currentTarget.value })}
                placeholder="2—6个汉字"
                value={draft.playerName}
              />
            </label>
            <button disabled={isBusy || !draft.race || !draft.gender} onClick={onGenerateName} type="button"><ReloadIcon />随机雅名</button>
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

        {error && <p className="entry-error">{error}</p>}
        <button className="primary-entry-button" disabled={isBusy || !draft.gender || !draft.race || !draft.playerName.trim()} onClick={onCreate} type="button">
          {isBusy ? "天机正在推演三种命格…" : "生成三种命格"}<ArrowRightIcon />
        </button>
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
  error: string;
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
        <div className="section-heading-copy"><p className="eyebrow">CHOOSE A MORTAL LIFE</p><h2 id="fate-title">择一命格入世</h2><span>每一种出身都有独立关系、缺陷与未偿之债。</span></div>
        <div className="fate-grid" aria-busy={isBusy}>
          {game.protagonistOptions.map((option, index) => (
            <button aria-pressed={selectedId === option.id} className="fate-card" data-selected={selectedId === option.id} key={option.id} onClick={() => onSelect(option.id)} type="button">
              <span className="fate-number">命 {index + 1}</span>
              <div className="fate-name"><strong>{option.name}</strong><small>{option.age}岁 · {option.identity}</small></div>
              <p>{option.origin}</p>
              <dl><div><dt>异处</dt><dd>{option.trait}</dd></div><div><dt>所求</dt><dd>{option.goal}</dd></div><div><dt>旧债</dt><dd>{option.burden}</dd></div></dl>
            </button>
          ))}
        </div>
      </section>
      {error && <p className="entry-error">{error}</p>}
      <button className="open-scroll-button" disabled={!selected || isBusy} onClick={onStart} type="button">
        {isBusy ? "正在开启命卷…" : selected ? `以「${selected.name}」开启命卷` : "先选择一种命格"}<ArrowRightIcon />
      </button>
      <button className="text-button" disabled={isBusy} onClick={onRecreate} type="button">放弃本次命格，重新创建</button>
    </div>
  );
}

function TalentSelectionView({ game, selectedId, isBusy, error, onSelect, onEnter, onReroll, onSkip }: {
  game: GameState; selectedId: string; isBusy: boolean; error: string;
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
    {error && <p className="entry-error">{error}</p>}
    <button className="open-scroll-button" disabled={!selected || isBusy} onClick={onEnter} type="button">{isBusy ? "命卷正在落印…" : selected ? `携「${selected.name}」入世` : "先选择一种天赋"}<ArrowRightIcon /></button>
    <div className="talent-secondary-actions"><button disabled={isBusy || game.talentRerolled} onClick={onReroll} type="button"><ReloadIcon />{game.talentRerolled ? "本世已重新推演" : "免费重新推演一次"}</button><button disabled={isBusy} onClick={onSkip} type="button">不携天赋，直接入世</button></div>
  </div>;
}

function AdventureView({ game, choices, resolution, isBusy, offline, error, onChoice, onSuggestion }: { game: GameState; choices: Choice[]; resolution?: ApiSnapshot["actionResolution"]; isBusy: boolean; offline: boolean; error: string; onChoice: (id: string) => void; onSuggestion: (action: string) => void }) {
  return (
    <div className="adventure-view">
      <section className="scene-heading" aria-labelledby="scene-title"><span className="leaf-mark" aria-hidden="true">〆</span><div><p className="eyebrow">当前场景</p><h1 id="scene-title">{game.sceneTitle}</h1></div></section>
      <div className="ornament-line" aria-hidden="true"><span /></div>
      <section className="narrative" aria-label="剧情">{game.narrative.map((paragraph, index) => <p data-beat={index > 0 && index % 3 === 0 ? "new" : undefined} key={`${index}-${paragraph}`}>{paragraph}</p>)}</section>
      {game.signals.length > 0 && <section className="signal-strip" aria-label="局势变化">{game.signals.map((signal, index) => <span className={index % 2 ? "signal-danger" : "signal-safe"} key={`${index}-${signal}`}>{signal}</span>)}</section>}
      {resolution && <section className="action-resolution" data-result={resolution.result} role="status">
        <p className="eyebrow">{resolution.result === "CLARIFY" ? "行动待明" : "世界裁决"}</p>
        <h2>{resolution.title}</h2>
        <p>{resolution.reason}</p>
        <div className="resolution-suggestions" aria-label="可尝试的行动">
          {resolution.suggestions.map((suggestion) => <button disabled={isBusy || offline} key={suggestion} onClick={() => onSuggestion(suggestion)} type="button">{suggestion}<ChevronRightIcon aria-hidden="true" /></button>)}
        </div>
      </section>}
      {isBusy && <AgentProgress />}
      <section className="choice-list" aria-busy={isBusy} aria-label="可选行动">
        {choices.map((choice, index) => (
          <button className="choice-card" data-tone={choice.tone ?? "default"} disabled={isBusy || offline} key={choice.id} onClick={() => onChoice(choice.id)} type="button">
            <span className="choice-seal" aria-hidden="true">{index + 1}</span><span className="choice-copy"><strong>{choice.label}</strong><small>{choice.hint}</small></span><ChevronRightIcon aria-hidden="true" />
          </button>
        ))}
      </section>
      {offline && !isBusy && <div className="agent-thinking offline-note" role="status"><LightningBoltIcon /><span>命卷暂时无法续写，已有经历仍可查阅。</span></div>}
      {error && !isBusy && <p className="turn-error" role="alert">{error}</p>}
    </div>
  );
}

function AgentProgress() {
  const phases = ["续读此前命卷", "推演人物与因果", "书写本回章节", "校验状态与时间线"];
  const [phase, setPhase] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => setPhase((current) => Math.min(phases.length - 1, current + 1)), 2200);
    return () => window.clearInterval(timer);
  }, []);
  return <section className="chapter-generating" role="status"><span className="ink-spinner" /><div><small>命卷推演中</small><strong>{phases[phase]}</strong><p>本轮会推进一个完整小章节，完成后统一写入人物、天赋与世界时间。</p></div></section>;
}

function CharacterView({ game }: { game: GameState }) {
  const player = game.player;
  if (!player) return null;
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">CHARACTER RECORD</p><h1>角色</h1></div><span className="turn-badge">第 {game.turn + 1} 回</span></div>
      <section className="hero-card"><div className="hero-avatar"><PersonIcon /></div><div><h2>{player.name}</h2><p>{player.identity} · {player.realm} · {player.constitution}</p><small>{player.age}/{player.lifespan}岁 · {player.race}</small></div><span className="realm-stamp">{player.realm}</span></section>
      <section className="life-purpose-card"><p><strong>此生所求</strong>{player.goal}</p><p><strong>未偿旧债</strong>{player.burden}</p></section>
      <section className="record-section"><div className="section-label"><LightningBoltIcon /><h2>天赋与功法</h2><span>{game.abilities.length}</span></div><div className="ability-list">
        {game.abilities.map((ability) => <article className="ability-row" key={ability.id}><div><h3>{ability.name}<small>{ability.type === "TALENT" ? "天赋" : ability.type === "TECHNIQUE" ? "功法" : ability.type === "SPELL" ? "术法" : "异能"} · {ability.stage}</small></h3><p>{ability.description}</p><span>缘起：{ability.source}</span></div></article>)}
      </div></section>
      <section className="record-section"><div className="section-label"><BackpackIcon /><h2>行囊</h2><span>{game.inventory.length}</span></div><div className="inventory-list">
        {game.inventory.map((item) => <article className="inventory-item" key={item.id}><div><h3>{item.name}<small>{item.quality} · ×{item.quantity}</small></h3><p>{item.description}</p></div></article>)}
      </div></section>
      <section className="record-section"><div className="section-label"><IdCardIcon /><h2>已登场角色</h2><span>{game.characters.length}</span></div><div className="character-list">
        {game.characters.map((character) => <article className="character-row" key={character.id}><div className="mini-avatar">{character.name.slice(0, 1)}</div><div><h3>{character.name}<small>{character.identity}</small></h3><p>{character.state}</p><span>{character.location} · {character.relation}</span></div></article>)}
      </div></section>
    </div>
  );
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

function WorldView({ game, version, releaseNotes, syncMode, email, theme, onTheme, onReset, onLogout }: { game: GameState; version: string; releaseNotes: ReleaseNote[]; syncMode: SyncMode; email: string; theme: ThemeMode; onTheme: (theme: ThemeMode) => void; onReset: () => void; onLogout: () => void }) {
  return (
    <div className="record-view">
      <div className="page-title-row"><div><p className="eyebrow">KNOWN WORLD</p><h1>世界</h1></div><GlobeIcon className="title-icon" /></div>
      <section className="location-card"><div className="location-art" /><div className="location-copy"><span><SewingPinFilledIcon /> 当前位置</span><h2>{game.sceneTitle} · {game.currentLocation}</h2><p>{game.world.summary}</p></div></section>
      <section className="world-law-card"><p><strong>修行法则</strong>{game.world.cultivationLaw}</p><p><strong>生存法则</strong>{game.world.dangerRule}</p><div>{game.world.factions.map((faction) => <span key={faction}>{faction}</span>)}</div></section>
      <section className="record-section"><div className="section-label"><MagnifyingGlassIcon /><h2>未解线索</h2><span>{game.threads.length}</span></div><div className="thread-list">{game.threads.map((thread) => <article className="thread-card" key={thread.id}><div><h3>{thread.title}</h3><p>{thread.state}</p></div><span data-danger={thread.danger > 75 ? "high" : "normal"}>{dangerName(thread.danger)}</span></article>)}</div></section>
      <section className="agent-record-card"><span className="agent-dot" /><div><h2>{syncMode === "server" ? "本世命卷已同步" : "命卷暂时封存"}</h2><p>{syncMode === "server" ? "人物、纪事与未解线索均已妥善保存。" : "已有经历仍可查阅，暂时无法续写。"} 当前邮箱：{email || "未知"}</p></div></section>
      <section className="release-history"><div className="section-label"><ReaderIcon /><h2>版本纪事</h2><span>{version}</span></div>{releaseNotes.length === 0 ? <p className="empty-copy">尚无公开版本纪事。</p> : releaseNotes.map((note) => <details key={note.id}><summary><span>{note.version}</span><strong>{note.title}</strong><small>{new Date(note.publishedAt).toLocaleDateString("zh-CN")}</small></summary><p>{note.content}</p></details>)}</section>
      <section className="theme-card"><div><strong>阅读外观</strong><span>夜间模式采用月下宣纸，不改变游戏进度。</span></div><div className="theme-switch">{([["system", "跟随系统"], ["paper", "宣纸"], ["night", "月夜"]] as Array<[ThemeMode, string]>).map(([id, label]) => <button aria-pressed={theme === id} data-selected={theme === id} key={id} onClick={() => onTheme(id)} type="button">{label}</button>)}</div></section>
      <div className="world-actions"><button className="reset-button" onClick={onReset} type="button"><ReloadIcon />重开此世</button><button className="reset-button" onClick={onLogout} type="button">退出邮箱</button></div>
    </div>
  );
}

function ReleaseAnnouncementDialog({ note, onClose }: { note: ReleaseNote; onClose: () => void }) {
  return <div className="ink-dialog-backdrop release-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog release-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">新</div><p className="eyebrow">NEW CHAPTER · {note.version}</p><h2>{note.title}</h2>
    <div className="release-dialog-content">{note.content.split(/\n+/).filter(Boolean).map((line, index) => <p key={`${index}-${line}`}>{line}</p>)}</div>
    <div className="ink-dialog-actions single"><button autoFocus className="ink-dialog-confirm" onClick={onClose} type="button">知道了，继续此世<ArrowRightIcon /></button></div>
  </section></div>;
}

export default function Prototype() {
  const [stage, setStage] = useState<AppStage>("loading");
  const [tab, setTab] = useState<TabId>("adventure");
  const [auth, setAuth] = useState<AuthResponse | null>(null);
  const [bootstrap, setBootstrap] = useState<BootstrapResponse | null>(null);
  const [snapshot, setSnapshot] = useState<ApiSnapshot | null>(null);
  const [draft, setDraft] = useState("");
  const [characterDraft, setCharacterDraft] = useState<CharacterDraft>(DEFAULT_DRAFT);
  const [nameAlternatives, setNameAlternatives] = useState<string[]>([]);
  const [generatedName, setGeneratedName] = useState("");
  const [selectedFate, setSelectedFate] = useState("");
  const [selectedTalent, setSelectedTalent] = useState("");
  const [theme, setTheme] = useState<ThemeMode>(() => (window.localStorage.getItem(THEME_KEY) as ThemeMode | null) ?? "system");
  const [systemDark, setSystemDark] = useState(() => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false);
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const [isBusy, setIsBusy] = useState(false);
  const busyRef = useRef(false);
  const [isComposerFocused, setIsComposerFocused] = useState(false);
  const [syncMode, setSyncMode] = useState<SyncMode>("connecting");
  const [error, setError] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [resetIntent, setResetIntent] = useState<"RESET" | "FATES" | null>(null);
  const [pendingRelease, setPendingRelease] = useState<ReleaseNote | null>(null);
  const [releaseNotes, setReleaseNotes] = useState<ReleaseNote[]>([]);

  const game = snapshot?.state ?? null;
  const choices = snapshot?.choices ?? game?.choices ?? [];
  const email = auth?.user?.email ?? "";
  const showGameChrome = stage === "fate" || stage === "talent" || stage === "game";
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

  const enterAuthenticatedFlow = async (nextAuth: AuthResponse) => {
    setAuth(nextAuth);
    setSyncMode("server");
    await loadBootstrap();
    const [notes, pending] = await Promise.all([gameApi.releaseNotes(), gameApi.pendingReleaseNote()]);
    setReleaseNotes(notes);
    setPendingRelease(pending);
    if (nextAuth.hasSave) {
      const current = await gameApi.currentSave();
      if (current.exists && current.game) {
        storeSnapshot(current.game);
        setTab("adventure");
        setStage(current.game.state.phase === "setup" ? "fate" : current.game.state.phase === "talent" ? "talent" : "game");
        return;
      }
    }
    setSnapshot(null);
    setCharacterDraft(DEFAULT_DRAFT);
    setNameAlternatives([]);
    setGeneratedName("");
    setStage("create");
  };

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const session = await gameApi.me();
        if (!active) return;
        if (!session.authenticated) {
          await loadBootstrap();
          if (active) { setAuth(session); setSyncMode("server"); setStage("login"); }
          return;
        }
        await enterAuthenticatedFlow(session);
      } catch {
        if (!active) return;
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
    window.localStorage.setItem(THEME_KEY, theme);
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setSystemDark(media.matches);
    media.addEventListener?.("change", update);
    return () => media.removeEventListener?.("change", update);
  }, [theme]);

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
    setError("");
    try {
      await task();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      busyRef.current = false;
      setIsBusy(false);
    }
  };

  const login = (loginEmail: string, inviteCode: string) => runBusy(async () => {
    const nextAuth = await gameApi.login(loginEmail, inviteCode);
    await enterAuthenticatedFlow(nextAuth);
    setToast(nextAuth.newUser ? "命卷身份已登记" : "旧命卷已寻回");
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
    const validation = await gameApi.validateName(characterDraft.playerName);
    if (!validation.valid) throw new ApiError(validation.message, 400);
    const created = await gameApi.createSave({ ...characterDraft, playerName: validation.normalizedName });
    storeSnapshot(created);
    setSelectedFate("");
    setStage("fate");
    setToast("三份命格已经落定");
  });

  const chooseFate = () => runBusy(async () => {
    if (!game || !selectedFate) return;
    const started = await gameApi.chooseFate(game.saveId, selectedFate);
    storeSnapshot(started);
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
    storeSnapshot(next); setStage("game"); setTab("adventure");
    setToast("天赋已入命，世界时间开始流动");
  });

  const enterWithoutTalent = () => runBusy(async () => {
    if (!game) return;
    const next = await gameApi.skipTalent(game.saveId);
    storeSnapshot(next); setStage("game"); setTab("adventure");
    setToast("一身清净，正式入世");
  });

  const takeTurn = (inputType: "CHOICE" | "TEXT", payload: { choiceId?: string; content?: string }) => runBusy(async () => {
    if (!game || !snapshot) return;
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    setIsComposerFocused(false);
    const next = await gameApi.turn(game.saveId, snapshot.revision, inputType, payload);
    storeSnapshot(next);
    setDraft("");
    setTab("adventure");
    setSyncMode("server");
    if (!next.actionResolution) setToast("本回合已写入统一时间线");
  });

  const recreate = () => runBusy(async () => {
    if (!game) return;
    await gameApi.deleteSave(game.saveId);
    setSnapshot(null);
    window.localStorage.removeItem(CACHE_KEY);
    await loadBootstrap();
    setCharacterDraft(DEFAULT_DRAFT);
    setNameAlternatives([]);
    setStage("create");
  });

  const resetGame = () => {
    setResetIntent("RESET");
  };

  const logout = () => runBusy(async () => {
    await gameApi.logout();
    window.localStorage.removeItem(CACHE_KEY);
    setAuth(null);
    setSnapshot(null);
    setStage("login");
    setSyncMode("server");
  });

  const headerText = useMemo(() => {
    if (game?.phase === "dead") return `${formatWorldTime(game.worldYear, game.day, game.minute)} · 此世已终`;
    if (game?.phase === "playing") return formatWorldTime(game.worldYear, game.day, game.minute);
    return game?.world.era || bootstrap?.world.era || "玄墟界 · 命卷未启";
  }, [bootstrap, game]);

  return (
    <div className="game-shell" data-composer-focused={isComposerFocused} data-entry={!showGameChrome} data-keyboard-open={keyboardOffset > 80} data-theme={actualTheme} onFocusCapture={(event) => { const target = event.target; if (target instanceof HTMLElement && target.matches("input, textarea, select") && !target.closest(".action-composer")) revealFormControl(target); }} style={{ "--keyboard-offset": `${keyboardOffset}px` } as CSSProperties}>
      <div className="paper-background" aria-hidden="true" />
      <header className="game-header"><div className="game-header-inner"><div className="brand-lockup"><strong>问仙录</strong><span>浮生一卷 · {bootstrap?.version ?? "V0.11"}</span></div><div className="world-clock"><span>{headerText}</span><small><SewingPinFilledIcon />{game?.phase === "playing" && game.player ? `${game.currentLocation} · ${game.player.realm} · ${conditionName(game.player.vitality)}` : game?.phase === "dead" && game.player ? `${game.currentLocation} · ${game.player.realm} · 已故` : syncMode === "offline" ? "命卷暂封" : "单机世界 · 命卷待启"}</small></div></div></header>
      <div className="game-scroll" data-phase={game?.phase ?? stage} key={`${stage}-${tab}-${game?.turn ?? 0}`}><main className="screen-content" data-testid="game-content">
        {stage === "loading" && <LoadingView />}
        {stage === "login" && <LoginView error={error} isBusy={isBusy} offline={syncMode === "offline"} onLogin={login} />}
        {stage === "create" && bootstrap && <CharacterCreationView alternatives={nameAlternatives} bootstrap={bootstrap} draft={characterDraft} error={error} isBusy={isBusy} onCreate={createCharacter} onDraft={updateCharacterDraft} onGenerateName={generateName} onLogout={() => void logout()} />}
        {stage === "fate" && game && <FateSelectionView error={error} game={game} isBusy={isBusy} onRecreate={() => setResetIntent("FATES")} onSelect={setSelectedFate} onStart={chooseFate} selectedId={selectedFate} />}
        {stage === "talent" && game && <TalentSelectionView error={error} game={game} isBusy={isBusy} onEnter={enterWithTalent} onReroll={rerollTalents} onSelect={setSelectedTalent} onSkip={enterWithoutTalent} selectedId={selectedTalent} />}
        {stage === "game" && game?.phase === "playing" && tab === "adventure" && <AdventureView choices={choices} error={error} game={game} isBusy={isBusy} offline={syncMode !== "server"} onChoice={(choiceId) => void takeTurn("CHOICE", { choiceId })} onSuggestion={(content) => void takeTurn("TEXT", { content })} resolution={snapshot?.actionResolution} />}
        {stage === "game" && game?.phase === "playing" && tab === "character" && <CharacterView game={game} />}
        {stage === "game" && game?.phase === "playing" && tab === "chronicle" && <ChronicleView game={game} />}
        {stage === "game" && game?.phase === "playing" && tab === "world" && <WorldView email={email} game={game} onLogout={() => void logout()} onReset={resetGame} onTheme={setTheme} releaseNotes={releaseNotes} syncMode={syncMode} theme={theme} version={bootstrap?.version ?? "V0.11"} />}
        {stage === "game" && game?.phase === "dead" && tab !== "chronicle" && <DeathView game={game} isBusy={isBusy} onChronicle={() => setTab("chronicle")} onLogout={() => void logout()} onRebirth={resetGame} />}
        {stage === "game" && game?.phase === "dead" && tab === "chronicle" && <div className="death-chronicle"><ChronicleView game={game} /><button className="reset-button" onClick={() => setTab("adventure")} type="button">返回终局结算</button></div>}
      </main></div>
      {stage === "game" && game?.phase === "playing" && tab === "adventure" && <form className="action-composer" onSubmit={(event) => { event.preventDefault(); if (draft.trim()) void takeTurn("TEXT", { content: draft.trim() }); }}><textarea aria-label="自由行动" maxLength={300} onBlur={() => window.setTimeout(() => setIsComposerFocused(false), 120)} onChange={(event) => setDraft(event.currentTarget.value)} onFocus={(event) => { const target = event.currentTarget; setIsComposerFocused(true); revealFormControl(target, 160); }} placeholder="可输入：使用物品、施展天赋或描述行动…" rows={1} value={draft} />{draft && <button aria-label="清空输入" className="clear-draft" onClick={() => setDraft("")} type="button"><Cross2Icon /></button>}<button className="act-button" disabled={!draft.trim() || isBusy || syncMode !== "server"} type="submit"><span>行动</span><EnterIcon /></button></form>}
      {stage === "game" && game?.phase === "playing" && !isComposerFocused && <nav className="bottom-nav" aria-label="主要导航">{([["adventure", "历练"], ["character", "角色"], ["chronicle", "纪事"], ["world", "世界"]] as Array<[TabId, string]>).map(([id, label]) => <button aria-current={tab === id ? "page" : undefined} key={id} onClick={() => { setError(""); setTab(id); }} type="button"><TabIcon id={id} /><span>{label}</span></button>)}</nav>}
      {resetIntent && game && <ResetConfirmDialog game={game} intent={resetIntent} isBusy={isBusy} onCancel={() => setResetIntent(null)} onConfirm={() => { setResetIntent(null); void recreate(); }} />}
      {pendingRelease && <ReleaseAnnouncementDialog note={pendingRelease} onClose={() => { const id = pendingRelease.id; setPendingRelease(null); setReleaseNotes((notes) => notes.map((note) => note.id === id ? { ...note, unread: false } : note)); void gameApi.markReleaseNoteRead(id); }} />}
      {isBusy && (stage === "create" || stage === "fate" || stage === "talent") && <DestinyLoadingOverlay stage={stage} />}
      {toast && <div className="save-toast" role="status"><span className="agent-dot" />{toast}</div>}
    </div>
  );
}
