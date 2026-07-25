import { useEffect, useMemo, useState } from "react";
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
type TabId = "adventure" | "character" | "chronicle" | "world";
type SceneId = "alley" | "bargain" | "mountain" | "home" | "shrine";

type Choice = {
  id: string;
  label: string;
  hint: string;
  tone?: "default" | "danger";
};

type ChronicleEvent = {
  id: string;
  title: string;
  detail: string;
  at: number;
  kind: "decision" | "risk" | "gain" | "world";
};

type CharacterRecord = {
  id: string;
  name: string;
  identity: string;
  state: string;
  location: string;
  relation: string;
  lastSeenAt: number;
};

type PlayerState = {
  name: string;
  realm: string;
  constitution: string;
  vitality: number;
  silver: number;
  insight: number;
  suspicion: number;
};

export type GameState = {
  sceneId: SceneId;
  minute: number;
  day: number;
  turn: number;
  narrative: string[];
  signals: string[];
  player: PlayerState;
  characters: CharacterRecord[];
  events: ChronicleEvent[];
  threads: Array<{ id: string; title: string; state: string; danger: number }>;
};

type ApiSnapshot = {
  state: GameState;
  revision: number;
  updatedAt: string;
  choices: Choice[];
  agent: { mode: string; authoritative: boolean };
};

const STORAGE_KEY = "wenxian-demo-save-v1";

const choicesByScene: Record<SceneId, Choice[]> = {
  alley: [
    { id: "ask-credit", label: "请求赊药", hint: "以三日为限，押上你的去路" },
    { id: "trade-intel", label: "交换北山情报", hint: "把你父亲留下的秘密当作筹码" },
    { id: "leave", label: "离开后巷", hint: "先回去确认妹妹的病情" },
  ],
  bargain: [
    { id: "accept-job", label: "接下试药差事", hint: "今夜去城南义庄，替许百川取一只药匣" },
    { id: "press-terms", label: "继续压价", hint: "要求他先交出半株赤心草" },
    { id: "walk-away", label: "拒绝交易", hint: "保住自己，但妹妹只剩两天" },
  ],
  mountain: [
    { id: "follow-mark", label: "追查灰色印记", hint: "它与父亲失踪那夜的车辙一致" },
    { id: "sell-map", label: "只卖地图", hint: "换药后不再过问北山" },
    { id: "hide-clue", label: "收回情报", hint: "许百川已经看出你有所隐瞒" },
  ],
  home: [
    { id: "use-medicine", label: "先用旧药压病", hint: "能拖半日，但会耗尽家中药材" },
    { id: "seek-doctor", label: "去请陈郎中", hint: "需要十二文诊金" },
    { id: "return-alley", label: "折返赌坊后巷", hint: "许百川或许还未离开" },
  ],
  shrine: [
    { id: "open-box", label: "打开无名药匣", hint: "匣内有活物般的敲击声", tone: "danger" },
    { id: "take-token", label: "只取义庄木牌", hint: "以证据换药，不碰匣中之物" },
    { id: "retreat", label: "立刻退出义庄", hint: "承认这不是凡人该碰的机缘" },
  ],
};

const initialState: GameState = {
  sceneId: "alley",
  minute: 14 * 60 + 20,
  day: 6,
  turn: 0,
  narrative: [
    "许百川将半株赤心草收回药箱。",
    "远处的叫喊越来越近，赌坊的人正在搜你。",
    "妹妹的病情只能再拖两天。",
  ],
  signals: ["商队已离镇", "赌坊警觉上升"],
  player: {
    name: "林砚",
    realm: "凡人",
    constitution: "寒骨未测",
    vitality: 68,
    silver: 23,
    insight: 7,
    suspicion: 36,
  },
  characters: [
    {
      id: "xu-baichuan",
      name: "许百川",
      identity: "行脚药商",
      state: "戒备，准备离镇",
      location: "赌坊后巷",
      relation: "可交易",
      lastSeenAt: 14 * 60 + 20,
    },
    {
      id: "lin-xiaohe",
      name: "林小禾",
      identity: "妹妹",
      state: "寒症加重，最多再拖两天",
      location: "柳溪村西屋",
      relation: "至亲",
      lastSeenAt: 13 * 60 + 40,
    },
    {
      id: "zhou-liu",
      name: "周六",
      identity: "长胜赌坊管事",
      state: "正在搜人",
      location: "柳溪村东街",
      relation: "敌视",
      lastSeenAt: 14 * 60 + 10,
    },
  ],
  events: [
    {
      id: "event-0",
      title: "赤心草被收回",
      detail: "许百川拒绝按原价卖药，妹妹的救命药再次落空。",
      at: 14 * 60 + 20,
      kind: "risk",
    },
    {
      id: "event--1",
      title: "赌坊开始搜人",
      detail: "你在赌局中看穿骰盅，长胜赌坊认定你出了千。",
      at: 13 * 60 + 55,
      kind: "world",
    },
  ],
  threads: [
    { id: "medicine", title: "取得赤心草", state: "只剩两日", danger: 72 },
    { id: "gambling", title: "摆脱赌坊追查", state: "搜捕范围扩大", danger: 61 },
    { id: "mountain", title: "父亲与北山灰印", state: "线索未证实", danger: 44 },
  ],
};

export function cloneInitialState(): GameState {
  return JSON.parse(JSON.stringify(initialState)) as GameState;
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

function formatWorldTime(day: number, minute: number, compact = false) {
  const normalized = minute % 1440;
  const hour = Math.floor(normalized / 60);
  const min = normalized % 60;
  if (compact) return `三月初${day} · ${String(hour).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
  return `大宁历217年 · 三月初${day} · ${periodName(normalized)}`;
}

function sceneTitle(sceneId: SceneId) {
  return {
    alley: "赌坊后巷",
    bargain: "一纸药约",
    mountain: "北山旧图",
    home: "西屋灯冷",
    shrine: "城南义庄",
  }[sceneId];
}

function TabIcon({ id }: { id: TabId }) {
  const icons = {
    adventure: HomeIcon,
    character: PersonIcon,
    chronicle: ReaderIcon,
    world: GlobeIcon,
  };
  const Icon = icons[id];
  return <Icon aria-hidden="true" />;
}

function AdventureView({
  game,
  choices,
  isThinking,
  isUnavailable,
  onChoice,
}: {
  game: GameState;
  choices: Choice[];
  isThinking: boolean;
  isUnavailable: boolean;
  onChoice: (id: string) => void;
}) {
  return (
    <div className="adventure-view">
      <section className="scene-heading" aria-labelledby="scene-title">
        <span className="leaf-mark" aria-hidden="true">〆</span>
        <div>
          <p className="eyebrow">当前场景</p>
          <h1 id="scene-title">{sceneTitle(game.sceneId)}</h1>
        </div>
      </section>

      <div className="ornament-line" aria-hidden="true"><span /></div>

      <section className="narrative" aria-label="剧情">
        {game.narrative.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}
      </section>

      <section className="signal-strip" aria-label="局势变化">
        {game.signals.map((signal, index) => (
          <span className={index % 2 ? "signal-danger" : "signal-safe"} key={signal}>{signal}</span>
        ))}
      </section>

      <section className="choice-list" aria-label="可选行动" aria-busy={isThinking}>
        {choices.map((choice, index) => (
          <button
            className="choice-card"
            data-tone={choice.tone ?? "default"}
            disabled={isThinking || isUnavailable}
            key={choice.id}
            onClick={() => onChoice(choice.id)}
            type="button"
          >
            <span className="choice-seal" aria-hidden="true">{index + 1}</span>
            <span className="choice-copy">
              <strong>{choice.label}</strong>
              <small>{choice.hint}</small>
            </span>
            <ChevronRightIcon aria-hidden="true" />
          </button>
        ))}
      </section>

      {isThinking && (
        <div className="agent-thinking" role="status">
          <LightningBoltIcon aria-hidden="true" />
          <span>天机推演中，正在校验时间与人物状态…</span>
        </div>
      )}
      {isUnavailable && !isThinking && (
        <div className="agent-thinking" role="status">
          <LightningBoltIcon aria-hidden="true" />
          <span>等待 Java 后端连接，当前缓存仅供查看。</span>
        </div>
      )}
    </div>
  );
}

function CharacterView({ game }: { game: GameState }) {
  return (
    <div className="record-view">
      <div className="page-title-row">
        <div><p className="eyebrow">CHARACTER RECORD</p><h1>人物志</h1></div>
        <span className="turn-badge">第 {game.turn + 1} 回</span>
      </div>

      <section className="hero-card">
        <div className="hero-avatar"><PersonIcon /></div>
        <div>
          <h2>{game.player.name}</h2>
          <p>{game.player.realm} · {game.player.constitution}</p>
        </div>
        <span className="realm-stamp">未入道</span>
      </section>

      <section className="stat-grid" aria-label="角色属性">
        <article><small>体力</small><strong>{game.player.vitality}</strong><i style={{ width: `${game.player.vitality}%` }} /></article>
        <article><small>悟性</small><strong>{game.player.insight}/10</strong><i style={{ width: `${game.player.insight * 10}%` }} /></article>
        <article><small>银钱</small><strong>{game.player.silver}文</strong><i style={{ width: `${Math.min(100, game.player.silver * 3)}%` }} /></article>
        <article><small>追查</small><strong>{game.player.suspicion}%</strong><i className="danger-meter" style={{ width: `${game.player.suspicion}%` }} /></article>
      </section>

      <section className="record-section">
        <div className="section-label"><IdCardIcon /><h2>已登场角色</h2><span>{game.characters.length}</span></div>
        <div className="character-list">
          {game.characters.map((character) => (
            <article className="character-row" key={character.id}>
              <div className="mini-avatar">{character.name.slice(0, 1)}</div>
              <div>
                <h3>{character.name}<small>{character.identity}</small></h3>
                <p>{character.state}</p>
                <span>{character.location} · {character.relation}</span>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="record-section">
        <div className="section-label"><BackpackIcon /><h2>行囊</h2></div>
        <div className="inventory-row">
          <span>父亲旧图</span><span>断线药囊</span><span>粗布短刀</span>
        </div>
      </section>
    </div>
  );
}

function ChronicleView({ game }: { game: GameState }) {
  return (
    <div className="record-view">
      <div className="page-title-row">
        <div><p className="eyebrow">ONE WORLD · ONE TIMELINE</p><h1>纪事</h1></div>
        <ClockIcon className="title-icon" />
      </div>

      <section className="timeline-summary">
        <span>统一世界时</span>
        <strong>{formatWorldTime(game.day, game.minute)}</strong>
        <p>所有人物状态、承诺与线索，都从同一条时间轴演化。</p>
      </section>

      <section className="timeline" aria-label="事件时间线">
        {game.events.map((event, index) => (
          <article className="timeline-event" data-kind={event.kind} key={event.id}>
            <div className="timeline-time">{index === 0 ? "现在" : formatWorldTime(game.day, event.at, true)}</div>
            <div className="timeline-dot" />
            <div className="timeline-card">
              <h2>{event.title}</h2>
              <p>{event.detail}</p>
              <small>{event.kind === "risk" ? "风险变化" : event.kind === "gain" ? "获得线索" : event.kind === "decision" ? "玩家决策" : "世界事件"}</small>
            </div>
          </article>
        ))}
      </section>
    </div>
  );
}

function WorldView({
  game,
  onReset,
  syncMode,
}: {
  game: GameState;
  onReset: () => void;
  syncMode: "connecting" | "server" | "offline";
}) {
  return (
    <div className="record-view">
      <div className="page-title-row">
        <div><p className="eyebrow">KNOWN WORLD</p><h1>世界志</h1></div>
        <GlobeIcon className="title-icon" />
      </div>

      <section className="location-card">
        <div className="location-art" />
        <div className="location-copy">
          <span><SewingPinFilledIcon /> 当前位置</span>
          <h2>{sceneTitle(game.sceneId)} · 柳溪村</h2>
          <p>凡人城镇，依山而生。北山旧矿封闭多年，城南义庄由县衙代管。</p>
        </div>
      </section>

      <section className="record-section">
        <div className="section-label"><MagnifyingGlassIcon /><h2>未解线索</h2><span>{game.threads.length}</span></div>
        <div className="thread-list">
          {game.threads.map((thread) => (
            <article className="thread-card" key={thread.id}>
              <div>
                <h3>{thread.title}</h3>
                <p>{thread.state}</p>
              </div>
              <span data-danger={thread.danger > 75 ? "high" : "normal"}>风险 {thread.danger}</span>
            </article>
          ))}
        </div>
      </section>

      <section className="agent-record-card">
        <span className="agent-dot" />
        <div>
          <h2>{syncMode === "server" ? "Java Agent 已连接" : syncMode === "connecting" ? "正在连接 Agent" : "后端未连接"}</h2>
          <p>
            {syncMode === "server" ? "裁决、时间推进与存档均由 Spring Boot 后端处理。" : "当前只能查看缓存，连接恢复后才能继续行动。"}
            已记录 {game.events.length} 个事件、{game.characters.length} 名角色、{game.turn} 次决策。
          </p>
        </div>
      </section>

      <button className="reset-button" onClick={onReset} type="button"><ReloadIcon /> 重开这一局</button>
    </div>
  );
}

export default function Prototype() {
  const [tab, setTab] = useState<TabId>("adventure");
  const [game, setGame] = useState<GameState>(() => {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      return stored ? (JSON.parse(stored) as GameState) : cloneInitialState();
    } catch {
      return cloneInitialState();
    }
  });
  const [draft, setDraft] = useState("");
  const [isThinking, setIsThinking] = useState(false);
  const [isComposerFocused, setIsComposerFocused] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [syncMode, setSyncMode] = useState<"connecting" | "server" | "offline">("connecting");
  const [currentChoices, setCurrentChoices] = useState<Choice[]>(() => choicesByScene[game.sceneId]);
  const saveId = useMemo(() => {
    const key = "wenxian-save-id-v1";
    const existing = window.localStorage.getItem(key);
    if (existing) return existing;
    const created = typeof window.crypto?.randomUUID === "function"
      ? window.crypto.randomUUID()
      : `save-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    window.localStorage.setItem(key, created);
    return created;
  }, []);
  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(game));
  }, [game]);

  useEffect(() => {
    let active = true;

    fetch(`/api/game?saveId=${encodeURIComponent(saveId)}`)
      .then(async (response) => {
        if (!response.ok) throw new Error("Agent API unavailable");
        return response.json() as Promise<ApiSnapshot>;
      })
      .then((payload) => {
        if (!active) return;
        setGame(payload.state);
        setCurrentChoices(payload.choices);
        setSyncMode("server");
      })
      .catch(() => {
        if (active) setSyncMode("offline");
      });

    return () => {
      active = false;
    };
  }, [saveId]);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 2200);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const runAgent = async (actionId: string, freeText = "") => {
    if (isThinking) return;
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    setIsComposerFocused(false);
    setIsThinking(true);

    try {
      const response = await fetch("/api/game/action", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ saveId, actionId, freeText }),
      });
      if (!response.ok) throw new Error("Agent action failed");
      const payload = await response.json() as ApiSnapshot;
      setGame(payload.state);
      setCurrentChoices(payload.choices);
      setSyncMode("server");
      setDraft("");
      setToast("服务端 Agent 已裁决并写入存档");
    } catch {
      setSyncMode("offline");
      setToast("Java 后端未连接，本次行动未执行");
    } finally {
      setIsThinking(false);
    }
  };

  const resetGame = async () => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    setIsComposerFocused(false);
    try {
      const response = await fetch("/api/game/reset", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ saveId }),
      });
      if (!response.ok) throw new Error("Reset failed");
      const payload = await response.json() as ApiSnapshot;
      setGame(payload.state);
      setCurrentChoices(payload.choices);
      setSyncMode("server");
      setToast("服务端新存档已建立");
    } catch {
      setSyncMode("offline");
      setToast("Java 后端未连接，无法重建存档");
    } finally {
      setTab("adventure");
    }
  };

  return (
    <div className="game-shell" data-composer-focused={isComposerFocused ? "true" : "false"}>
      <div className="paper-background" aria-hidden="true" />

      <header className="game-header">
        <div className="game-header-inner">
          <div className="brand-lockup">
            <strong>问仙录</strong>
            <span>浮生一卷</span>
          </div>
          <div className="world-clock">
            <span>{formatWorldTime(game.day, game.minute)}</span>
            <small><SewingPinFilledIcon /> 柳溪村 · {game.player.realm} · 体力 {game.player.vitality}</small>
          </div>
        </div>
      </header>

      <div className="game-scroll" key={`${tab}-${game.turn}`}>
        <main className="screen-content" data-testid="game-content">
          {tab === "adventure" && (
            <AdventureView
              game={game}
              choices={currentChoices}
              isThinking={isThinking}
              isUnavailable={syncMode !== "server"}
              onChoice={(id) => runAgent(id)}
            />
          )}
          {tab === "character" && <CharacterView game={game} />}
          {tab === "chronicle" && <ChronicleView game={game} />}
          {tab === "world" && <WorldView game={game} onReset={resetGame} syncMode={syncMode} />}
        </main>
      </div>

      {tab === "adventure" && (
        <form
          className="action-composer"
          onSubmit={(event) => {
            event.preventDefault();
            if (draft.trim()) runAgent("", draft);
          }}
        >
          <textarea
            aria-label="自由行动"
            onChange={(event) => setDraft(event.currentTarget.value)}
            onBlur={() => setIsComposerFocused(false)}
            onFocus={() => setIsComposerFocused(true)}
            placeholder="描述你接下来想做什么…"
            rows={1}
            value={draft}
          />
          {draft && <button aria-label="清空输入" className="clear-draft" onClick={() => setDraft("")} type="button"><Cross2Icon /></button>}
          <button className="act-button" disabled={!draft.trim() || isThinking || syncMode !== "server"} type="submit">
            <span>行动</span><EnterIcon />
          </button>
        </form>
      )}

      {!isComposerFocused && (
        <nav className="bottom-nav" aria-label="主要导航">
          {([
            ["adventure", "历练"],
            ["character", "角色"],
            ["chronicle", "纪事"],
            ["world", "世界"],
          ] as Array<[TabId, string]>).map(([id, label]) => (
            <button aria-current={tab === id ? "page" : undefined} key={id} onClick={() => setTab(id)} type="button">
              <TabIcon id={id} /><span>{label}</span>
            </button>
          ))}
        </nav>
      )}

      {toast && <div className="save-toast" role="status"><span className="agent-dot" />{toast}</div>}
    </div>
  );
}
