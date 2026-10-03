import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  ArrowLeftIcon,
  CheckCircledIcon,
  ChevronRightIcon,
  LightningBoltIcon,
  MagnifyingGlassIcon,
} from "@radix-ui/react-icons";
import type { StoryGenerationSettings } from "../../game-api";
import type { PlayerChoice, PlayerStoryView } from "../../app/player-read-model";
import { ProblemNotice, type UiProblem } from "../../shared/problem";
import {
  formatEventTime,
  formatWorldTime,
  RACE_NAMES,
} from "../../shared/world-presentation";

export type CleanupTarget = { kind: "item" | "character"; id: string; name: string };
export type PendingPlayerAction = { kind: "CHOICE" | "TEXT"; choiceId?: string; label: string };
type CharacterRecordTab = "abilities" | "inventory" | "characters";

const CHARACTER_RECORD_PREVIEW_LIMIT = 4;
const CHARACTER_RECORD_LABELS: Record<CharacterRecordTab, { short: string; title: string; search: string }> = {
  abilities: { short: "功法", title: "天赋与功法", search: "搜索功法或天赋" },
  inventory: { short: "行囊", title: "行囊", search: "搜索行囊" },
  characters: { short: "人物", title: "已登场角色", search: "搜索登场角色" },
};

function keepSubmittedChoiceVisible(target: HTMLElement) {
  window.setTimeout(() => {
    if (!target.isConnected) return;
    target.scrollIntoView({ block: "center", behavior: "smooth" });
  }, 40);
}

function AgentProgress() {
  const phases = ["承接前情", "续写故事", "写入命卷"];
  const [phase, setPhase] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => setPhase((current) => Math.min(phases.length - 1, current + 1)), 2200);
    return () => window.clearInterval(timer);
  }, []);
  return <section aria-atomic="true" className="chapter-generating" data-phase={phase} role="status"><div className="chapter-progress-copy"><small>命卷续写中</small><strong>正在续写这一回</strong><p>{phases[phase]}</p></div><div aria-hidden="true" className="chapter-writing-line"><span /></div></section>;
}

export function AdventureView({
  game,
  choices,
  pendingAction,
  customActionActive,
  actionPanel,
  recoveryPanel,
  actionLocked = false,
  generation,
  isBusy,
  offline,
  error,
  initialChoiceId,
  onChoiceMode,
  onChoice,
}: {
  game: PlayerStoryView;
  choices: PlayerChoice[];
  pendingAction: PendingPlayerAction | null;
  customActionActive: boolean;
  actionPanel?: ReactNode;
  recoveryPanel?: ReactNode;
  actionLocked?: boolean;
  generation?: StoryGenerationSettings | null;
  isBusy: boolean;
  offline: boolean;
  error: UiProblem | null;
  initialChoiceId?: string;
  onChoiceMode: () => void;
  onChoice: (id: string) => void;
}) {
  const [selectedChoiceId, setSelectedChoiceId] = useState(initialChoiceId ?? "");
  const usageCopy = generation
    ? generation.mode === "PERSONAL"
      ? "正在使用自己的推演密钥 · 不消耗平台推演点"
      : `平台推演点剩余 ${generation.platform.remainingTurns} 点`
    : null;

  useEffect(() => {
    setSelectedChoiceId(initialChoiceId ?? "");
  }, [game.saveId, game.turn, initialChoiceId]);

  useEffect(() => {
    if (customActionActive) setSelectedChoiceId("");
  }, [customActionActive]);

  const selectedChoice = choices.find((choice) => choice.id === selectedChoiceId);
  return <div className="adventure-view">
    <header className="story-page-header"><h1 id="scene-title">{game.sceneTitle}</h1><span>本回</span></header>
    <section className="narrative" aria-label="剧情">{game.narrative.map((paragraph, index) => <p key={`${index}-${paragraph}`}>{paragraph}</p>)}</section>
    {error && !isBusy && <ProblemNotice context="story" problem={error} />}
    {!isBusy && recoveryPanel}
    {game.pendingDecision && <section className="pending-decision" aria-label="等待你的决定" role="status"><p className="eyebrow">由你决定</p><strong>{game.pendingDecision.prompt}</strong><small>命卷已经停在这里，不会替你作出这个选择。</small></section>}
    {isBusy && pendingAction && <section aria-label="已确认的行动" className="submitted-action" role="status"><span>已确认</span><p>{pendingAction.label}</p></section>}
    {isBusy && <AgentProgress />}
    {!isBusy && <section className="chapter-decision" aria-labelledby="chapter-decision-title" data-recovery={Boolean(error) || undefined}>
      <header><h2 id="chapter-decision-title">{actionLocked ? "原行动结果尚未确认" : error ? "重新选择，或原样再试" : "你准备如何回应？"}</h2></header>
      {!actionLocked && <div className="choice-list" aria-label="可选行动">
      {choices.map((choice, index) => {
        const selected = selectedChoiceId === choice.id;
        return <button aria-label={`${choice.label}。${choice.hint}`} aria-pressed={selected} className="choice-card" data-selected={selected || undefined} data-tone={choice.tone ?? "default"} disabled={offline} key={choice.id} onClick={() => { onChoiceMode(); setSelectedChoiceId(choice.id); }} type="button">
          <span className="choice-index" aria-hidden="true">{index + 1}</span><span className="choice-copy"><strong>{choice.label}</strong><small>{choice.hint}</small></span><span className="choice-selector" aria-hidden="true">{selected ? <CheckCircledIcon /> : <ChevronRightIcon />}</span>
        </button>;
      })}
      </div>}
      {actionPanel}
      {!customActionActive && !actionLocked && <div aria-live="polite" className="choice-confirmation"><button disabled={!selectedChoice || offline} onClick={(event) => { if (!selectedChoice) return; keepSubmittedChoiceVisible(event.currentTarget); onChoice(selectedChoice.id); }} type="button">{selectedChoice ? error ? "重新提交此行动" : "确认行动" : error ? "请选择一项再试" : "请选择一项行动"}</button></div>}
    </section>}
    {offline && !isBusy && <div className="agent-thinking offline-note" role="status"><LightningBoltIcon /><span>命卷暂时无法续写，已有经历仍可查阅。</span></div>}
    {usageCopy && <p className="daily-chapter-usage">{usageCopy}</p>}
  </div>;
}

export function CharacterView({ game, isBusy, onCleanup }: { game: PlayerStoryView; isBusy: boolean; onCleanup?: (target: CleanupTarget) => void }) {
  const [organizing, setOrganizing] = useState(false);
  const [activeRecordTab, setActiveRecordTab] = useState<CharacterRecordTab>("abilities");
  const [libraryTab, setLibraryTab] = useState<CharacterRecordTab | null>(null);
  const [recordQuery, setRecordQuery] = useState("");
  const viewRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    viewRef.current?.closest<HTMLElement>(".game-scroll, .mobile-scroll")?.scrollTo({ top: 0 });
  }, [libraryTab]);
  const player = game.player;
  if (!player) return null;
  const inventory = game.inventory.filter((item) => item.category !== "CURRENCY");
  const originCopy = player.origin.trim();
  const conciseIdentity = player.identity.trim().length <= 16 ? player.identity.trim() : "";
  const profileCopy = [conciseIdentity, player.realm, player.condition].filter(Boolean).join(" · ");

  const recordCounts: Record<CharacterRecordTab, number> = {
    abilities: game.abilities.length,
    inventory: inventory.length,
    characters: game.characters.length,
  };
  const normalizedQuery = recordQuery.trim().toLocaleLowerCase();

  const selectRecordTab = (tab: CharacterRecordTab) => {
    setActiveRecordTab(tab);
    setOrganizing(false);
    setRecordQuery("");
  };

  const openLibrary = (tab: CharacterRecordTab, organize = false) => {
    setLibraryTab(tab);
    setOrganizing(organize);
    setRecordQuery("");
  };

  const closeLibrary = () => {
    setLibraryTab(null);
    setOrganizing(false);
    setRecordQuery("");
  };

  const renderRecordList = (tab: CharacterRecordTab, preview: boolean) => {
    if (tab === "abilities") {
      const records = game.abilities.filter((ability) => !normalizedQuery || [ability.name, ability.stage, ability.source, ability.description].some((value) => value.toLocaleLowerCase().includes(normalizedQuery)));
      const visible = preview ? records.slice(0, CHARACTER_RECORD_PREVIEW_LIMIT) : records;
      return <div className={`ability-list character-record-list${preview ? " character-record-preview" : ""}`}>
        {visible.map((ability) => <article className="ability-row" key={ability.id}><div><h3>{ability.name}<small>{ability.type === "TALENT" ? "天赋" : ability.type === "TECHNIQUE" ? "功法" : ability.type === "SPELL" ? "术法" : "异能"} · {ability.stage}</small></h3><p>{ability.description}</p><span>缘起：{ability.source}</span></div></article>)}
        {records.length === 0 && <p className="record-empty-copy compact">{normalizedQuery ? "没有找到相符的功法或天赋。" : "尚未掌握需要记入命卷的天赋或功法。"}</p>}
      </div>;
    }

    if (tab === "inventory") {
      const records = inventory.filter((item) => !normalizedQuery || [item.name, item.status, item.category, item.description].some((value) => value.toLocaleLowerCase().includes(normalizedQuery)));
      const visible = preview ? records.slice(0, CHARACTER_RECORD_PREVIEW_LIMIT) : records;
      return <div className={`inventory-list character-record-list${preview ? " character-record-preview" : ""}`}>
        {visible.map((item) => <article className="inventory-item" key={item.id}><div><h3>{item.name}<small>{item.status} · {item.quantityKnown === false ? "数量未清点" : `×${item.quantity}`}</small></h3><p>{item.description}</p></div>{organizing && !preview && <button disabled={isBusy} onClick={() => onCleanup?.({ kind: "item", id: item.id, name: item.name })} type="button">移出行囊</button>}</article>)}
        {records.length === 0 && <p className="record-empty-copy">{normalizedQuery ? "行囊中没有找到相符的物品。" : "行囊中暂时没有需要记录的物品。"}</p>}
      </div>;
    }

    const records = game.characters.filter((character) => !normalizedQuery || [character.name, character.identity, character.state, character.location, character.relation].some((value) => value.toLocaleLowerCase().includes(normalizedQuery)));
    const visible = preview ? records.slice(0, CHARACTER_RECORD_PREVIEW_LIMIT) : records;
    return <div className={`character-list character-record-list${preview ? " character-record-preview" : ""}`}>
      {visible.map((character) => <article className="character-row" key={character.id}><div className="mini-avatar">{character.name.slice(0, 1)}</div><div><h3>{character.name}<small>{character.identity}</small></h3><p>{character.state}</p><span>{character.location} · {character.relation}</span></div>{organizing && !preview && <button className="character-untrack-button" disabled={isBusy} onClick={() => onCleanup?.({ kind: "character", id: character.id, name: character.name })} type="button">不再关注</button>}</article>)}
      {records.length === 0 && <p className="record-empty-copy">{normalizedQuery ? "没有找到相符的登场角色。" : "当前没有需要持续关注的人物。"}</p>}
    </div>;
  };

  if (libraryTab) {
    const label = CHARACTER_RECORD_LABELS[libraryTab];
    const canOrganize = Boolean(onCleanup) && libraryTab !== "abilities" && recordCounts[libraryTab] > 0;
    return <div className="record-view character-record-view character-library-view" ref={viewRef}>
      <header className="character-library-header">
        <button className="character-library-back" onClick={closeLibrary} type="button"><ArrowLeftIcon aria-hidden="true" /><span>人物</span></button>
        <div><small>命卷记录</small><h1>{label.title}</h1></div>
        <span>{recordCounts[libraryTab]} 项</span>
      </header>
      {recordCounts[libraryTab] > 8 && <label className="character-record-search"><MagnifyingGlassIcon aria-hidden="true" /><span className="visually-hidden">{label.search}</span><input autoComplete="off" onChange={(event) => setRecordQuery(event.currentTarget.value)} placeholder={label.search} type="search" value={recordQuery} /></label>}
      {canOrganize && <div className="character-library-actions"><p>{libraryTab === "inventory" ? "只会移出当前行囊，旧事仍保留。" : "只会移出当前关注，旧事仍保留。"}</p><button aria-pressed={organizing} className="record-manage-button" disabled={isBusy} onClick={() => setOrganizing((current) => !current)} type="button">{organizing ? "完成整理" : libraryTab === "inventory" ? "整理行囊" : "整理关注"}</button></div>}
      <section aria-label={label.title} className="character-library-list">{renderRecordList(libraryTab, false)}</section>
    </div>;
  }

  const activeLabel = CHARACTER_RECORD_LABELS[activeRecordTab];
  const activeCount = recordCounts[activeRecordTab];
  const canOrganizeActive = Boolean(onCleanup) && activeRecordTab !== "abilities" && activeCount > 0;
  return <div className="record-view character-record-view" ref={viewRef}>
    <header className="character-page-header"><div><h1>人物</h1><span>第 {game.turn} 回</span></div></header>
    <section aria-labelledby="character-name" className="character-dossier"><div className="character-dossier-main"><div className="hero-name-seal" aria-hidden="true">{player.name.slice(0, 1)}</div><div className="character-dossier-identity"><h2 id="character-name">{player.name}</h2>{profileCopy && <p>{profileCopy}</p>}<small>{RACE_NAMES[player.race] ?? player.race} · {player.gender}</small></div><span className="realm-stamp">{player.realm}</span></div><dl className="character-dossier-facts">{originCopy && <div><dt>来处</dt><dd>{originCopy}</dd></div>}<div><dt>所求</dt><dd>{player.lifeGoal}</dd></div><div><dt>倾向</dt><dd>{player.storyPreference}</dd></div></dl></section>
    <section className="character-record-hub">
      <div aria-label="人物记录分类" className="character-record-tabs" role="tablist">{(Object.keys(CHARACTER_RECORD_LABELS) as CharacterRecordTab[]).map((tab) => <button aria-controls={`character-record-panel-${tab}`} aria-selected={activeRecordTab === tab} id={`character-record-tab-${tab}`} key={tab} onClick={() => selectRecordTab(tab)} role="tab" type="button"><strong>{CHARACTER_RECORD_LABELS[tab].short}</strong><span>{recordCounts[tab]}</span></button>)}</div>
      <section aria-labelledby={`character-record-tab-${activeRecordTab}`} className="character-record-panel" id={`character-record-panel-${activeRecordTab}`} role="tabpanel">
        <header className="character-record-panel-heading"><div><h2>{activeLabel.title}</h2><small>{activeCount > CHARACTER_RECORD_PREVIEW_LIMIT ? `先看前 ${CHARACTER_RECORD_PREVIEW_LIMIT} 项` : `${activeCount} 项记录`}</small></div>{canOrganizeActive && <button className="character-record-organize" disabled={isBusy} onClick={() => openLibrary(activeRecordTab, true)} type="button">{activeRecordTab === "inventory" ? "整理行囊" : "整理关注"}</button>}</header>
        {renderRecordList(activeRecordTab, true)}
        {activeCount > 0 && <button className="character-record-view-all" onClick={() => openLibrary(activeRecordTab)} type="button"><span>{activeCount > CHARACTER_RECORD_PREVIEW_LIMIT ? `查看全部 ${activeCount} 项` : "查看完整记录"}</span><ChevronRightIcon aria-hidden="true" /></button>}
      </section>
    </section>
  </div>;
}

export function ChronicleView({ game, isBusy = false, onCorrection }: { game: PlayerStoryView; isBusy?: boolean; onCorrection?: () => void }) {
  const [showAllEvents, setShowAllEvents] = useState(false);
  const recentEventLimit = 24;
  const visibleEvents = showAllEvents ? game.events : game.events.slice(0, recentEventLimit);
  const eventKindCopy = {
    decision: "你的选择",
    gain: "有所获得",
    risk: "留下的代价",
    world: "发生之事",
  } as const;
  return <div className="record-view chronicle-view">
    <header className="chronicle-heading"><div><h1>前情</h1><p>{formatWorldTime(game.worldTime)}</p></div>{onCorrection && <button aria-label="修正命卷中记错的事实" className="chronicle-correction-button" disabled={isBusy} onClick={onCorrection} type="button"><span>修正记录</span><ChevronRightIcon aria-hidden="true" /></button>}</header>
    {visibleEvents.length > 0 ? <section className="chronicle-timeline" aria-label="前情记录">{visibleEvents.map((event, index) => <article className="chronicle-event" data-kind={event.kind} data-latest={index === 0 ? "true" : undefined} key={event.id}>
      <span className="chronicle-event-marker" aria-hidden="true" />
      <div className="chronicle-event-copy">
        <div className="chronicle-event-meta"><span>{eventKindCopy[event.kind]}</span><time>{index === 0 ? "此刻" : formatEventTime(event.at, game.worldTime.year)}</time></div>
        <h2>{event.title}</h2>
        <p>{event.detail}</p>
      </div>
    </article>)}</section> : <p className="chronicle-empty">故事刚刚开始，尚无需要回顾的前情。</p>}
    {game.events.length > recentEventLimit && <button aria-expanded={showAllEvents} className="chronicle-history-toggle" onClick={() => setShowAllEvents((current) => !current)} type="button">{showAllEvents ? "收起更早记录" : `查看更早的 ${game.events.length - recentEventLimit} 条记录`}</button>}
  </div>;
}
