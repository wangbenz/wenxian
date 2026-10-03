import { useEffect, useRef, useState } from "react";
import { ArrowRightIcon, ChevronDownIcon, ReloadIcon } from "@radix-ui/react-icons";
import type {
  CharacterDraft,
  StoryCatalogOption,
  StoryCharacterRoute,
  StoryWorldPackageOption,
} from "../../game-api";
import type { PlayerBootstrapView } from "../../app/player-read-model";
import { STORY_GENDER_NAMES, selectedRace, selectedWorld } from "../../app/story-adapter";
import { ProblemNotice, type UiProblem } from "../../shared/problem";

const PHASE_NAMES: Record<string, string> = {
  EARLY: "初期",
  MIDDLE: "中期",
  LATE: "后期",
  PERFECT: "圆满",
};

function RealmScalePreview({ world, raceId }: { world: StoryWorldPackageOption; raceId: string }) {
  const selectedRaceOption = world.races.find((race) => race.raceId === raceId) ?? world.races[0];
  const selectedStages = world.realmScale.ranks.map((rank) => ({
    rank,
    stage: rank.raceRealms.find((stage) => stage.raceId === selectedRaceOption?.raceId),
  }));
  const startingStage = selectedStages[0]?.stage;
  if (!selectedRaceOption || !startingStage) return null;

  return <section className="realm-scale-preview" aria-labelledby="realm-scale-title">
    <div className="realm-start-card">
      <div><p>此身起点</p><h3 id="realm-scale-title">{startingStage.name}<small>初期</small></h3><span>{startingStage.description}</span></div>
      <em>由经历向前</em>
    </div>
    <p className="realm-start-rule">新角色从本族第一境初期开始，之后的变化只来自已经发生的经历。</p>
    <details className="realm-scale-details">
      <summary><span><strong>查看三族境界对照</strong><small>同一境序可跨种族比较，每境均有四个小阶段</small></span><ChevronDownIcon aria-hidden="true" /></summary>
      <ol className="realm-scale-list">
        {world.realmScale.ranks.map((rank) => <li key={rank.rankKey}>
          <b>第 {rank.order} 境</b>
          <div>{rank.raceRealms.map((stage) => {
            const race = world.races.find((option) => option.raceId === stage.raceId);
            return <span data-current-race={stage.raceId === selectedRaceOption.raceId} key={stage.stageId}><small>{race?.name ?? "异族"}</small>{stage.name}</span>;
          })}</div>
        </li>)}
      </ol>
      <p className="realm-phase-line">{world.realmScale.phases.map((phase) => PHASE_NAMES[phase] ?? phase).join(" → ")}</p>
    </details>
  </section>;
}

export function CharacterCreationView({
  bootstrap,
  draft,
  alternatives,
  isBusy,
  error,
  routeOptions,
  selectedRouteId,
  preparedRoute,
  onDraft,
  onGenerateName,
  onGenerateRoutes,
  onSelectRoute,
  onPrepareOpening,
  onCreate,
  onLogout,
  initialStep = "identity",
}: {
  bootstrap: PlayerBootstrapView;
  draft: CharacterDraft;
  alternatives: string[];
  isBusy: boolean;
  error: UiProblem | null;
  routeOptions: StoryCharacterRoute[];
  selectedRouteId: string;
  preparedRoute?: StoryCharacterRoute | null;
  onDraft: (patch: Partial<CharacterDraft>) => void;
  onGenerateName: () => void;
  onGenerateRoutes: () => void;
  onSelectRoute: (route: StoryCharacterRoute) => void;
  onPrepareOpening?: () => Promise<boolean>;
  onCreate: () => void;
  onLogout: () => void;
  initialStep?: "identity" | "path" | "review";
}) {
  const [step, setStep] = useState<"identity" | "path" | "review">(initialStep);
  const creationViewRef = useRef<HTMLDivElement>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  const raceIdentityInputRef = useRef<HTMLInputElement>(null);
  const world = selectedWorld(bootstrap, draft);
  const race = selectedRace(bootstrap, draft);
  const preferences = bootstrap.creation.playPreferences;
  const needsRaceIdentity = Boolean(race && !race.raceId.endsWith(":human"));
  const raceIdentityLabel = race?.raceId.endsWith(":yao") ? "你的本相" : "你的本源或本体";
  const selectedRoute = routeOptions.find((route) => route.id === selectedRouteId);
  const openingRoute = preparedRoute ?? (onPrepareOpening ? undefined : selectedRoute);
  const startingStage = world?.realmScale.ranks[0]?.raceRealms.find((stage) => stage.raceId === draft.raceId);
  const preferenceNames = [
    ...draft.playPreference.focus.map((code) => preferences.focus.find((option) => option.code === code)?.name),
    preferences.combatFrequency.find((option) => option.code === draft.playPreference.combatFrequency)?.name,
    preferences.pace.find((option) => option.code === draft.playPreference.pace)?.name,
    preferences.guidance.find((option) => option.code === draft.playPreference.guidance)?.name,
  ].filter(Boolean).join(" · ");
  const stepNumber = step === "identity" ? 1 : step === "path" ? 2 : 3;
  const stepLabels = ["定其身", "择其途", "启其卷"];
  const stepMarks = ["一", "二", "三"];
  const moveTo = (nextStep: "identity" | "path" | "review") => {
    setStep(nextStep);
    window.requestAnimationFrame(() => creationViewRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }));
  };
  const continueFromIdentity = () => {
    if (!draft.playerName.trim()) {
      nameInputRef.current?.focus();
      nameInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
      return;
    }
    if (needsRaceIdentity && !draft.raceIdentity.trim()) {
      raceIdentityInputRef.current?.focus();
      raceIdentityInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
      return;
    }
    moveTo("path");
  };
  const continueFromPath = async () => {
    if (!draft.originPremise.trim()) {
      return;
    }
    if (onPrepareOpening && !await onPrepareOpening()) return;
    moveTo("review");
  };

  useEffect(() => setStep(initialStep), [initialStep]);

  return (
    <div className="creation-view creation-flow-view" data-creation-step={step} ref={creationViewRef}>
      <section className="creation-flow-card">
        <header className="creation-flow-heading">
          <div><h1>{step === "identity" ? "定下此身" : step === "path" ? "选择来处" : "开启此卷"}</h1></div>
          <p>{step === "identity" ? "姓名、族属与身份由你确定。" : step === "path" ? "选择来处，再决定故事更看重什么。" : "核对开篇；确认后，这些内容会写入命卷。"}</p>
          <ol className="creation-progress" aria-label={`创建命卷，第${stepNumber}步，共3步`}>
            {stepLabels.map((label, index) => <li aria-current={stepNumber === index + 1 ? "step" : undefined} data-complete={stepNumber > index + 1} key={label}><span /><b>{stepMarks[index]}</b><small>{label}</small></li>)}
          </ol>
        </header>

        {step === "identity" && <div className="creation-stage creation-identity-stage">
          <section className="creation-world-strip" aria-label="当前世界">
            <div><small>此卷所在</small><strong>{world?.name ?? "玄墟界"}</strong></div>
            <em title={world?.coreRules.join("；")}>世界已定</em>
          </section>

          <fieldset className="creation-section identity-race-section">
            <legend><span>选择种族</span><small>影响身份事实与他人判断</small></legend>
            <div className="race-grid creation-race-grid">
              {world?.races.map((option) => <button aria-label={`${option.name}。${option.raceId.endsWith(":human") ? "传承与城镇" : option.raceId.endsWith(":yao") ? "需确定本相" : "需确定本源"}。${option.socialHooks[0]}`} aria-pressed={draft.raceId === option.raceId} data-selected={draft.raceId === option.raceId} key={option.raceId} onClick={() => onDraft({ raceId: option.raceId })} type="button"><strong>{option.name}</strong><span>{option.raceId.endsWith(":human") ? "传承与城镇" : option.raceId.endsWith(":yao") ? "需确定本相" : "需确定本源"}</span></button>)}
            </div>
            {race && <p className="creation-race-note">{race.socialHooks[0]}</p>}
            {needsRaceIdentity && <label className="race-identity-field">
              <span>{raceIdentityLabel}<small>必填，由你亲自确定</small></span>
              <input ref={raceIdentityInputRef} maxLength={40} onChange={(event) => onDraft({ raceIdentity: event.currentTarget.value })} placeholder={race?.raceId.endsWith(":yao") ? "例如：月纹灵狐" : "例如：山门古剑孕生的器灵"} value={draft.raceIdentity} />
              <small>会成为身份事实，但不能直接赋予高阶修为。</small>
            </label>}
          </fieldset>

          <fieldset className="creation-section">
            <legend><span>性别</span></legend>
            <div className="segmented-control">
              {([["MALE", "男"], ["FEMALE", "女"], ["UNSPECIFIED", "不设定"]] as const).map(([code, label]) => <button aria-pressed={draft.gender === code} data-selected={draft.gender === code} key={code} onClick={() => onDraft({ gender: code })} type="button">{label}</button>)}
            </div>
          </fieldset>

          <fieldset className="creation-section creation-name-section">
            <legend><span>姓名</span><small>可自定义，也可随机生成</small></legend>
            <div className="name-field-row">
              <input ref={nameInputRef} aria-label="角色姓名" maxLength={6} onChange={(event) => onDraft({ playerName: event.currentTarget.value })} placeholder="2-6个汉字" value={draft.playerName} />
              <button disabled={isBusy || !draft.gender || !draft.raceId} onClick={onGenerateName} type="button"><ReloadIcon />随机雅名</button>
            </div>
            {alternatives.length > 0 && <div className="name-alternatives" aria-label="备选姓名"><small>备选</small>{alternatives.map((name) => <button key={name} onClick={() => onDraft({ playerName: name })} type="button">{name}</button>)}</div>}
          </fieldset>

          {world && draft.raceId && <RealmScalePreview raceId={draft.raceId} world={world} />}
          {error && <ProblemNotice compact problem={error} />}
          <button className="creation-primary-button" disabled={isBusy} onClick={continueFromIdentity} type="button">下一步 · 选择入世路线<ArrowRightIcon /></button>
        </div>}

        {step === "path" && <div className="creation-stage creation-path-stage">
          <section className="creation-route-heading"><div><strong>三条开篇</strong><small>选择一条作为人物来处</small></div><button disabled={isBusy} onClick={onGenerateRoutes} type="button"><ReloadIcon />换一批</button></section>
          {routeOptions.length === 0 ? <section className="creation-route-empty"><p>结合身份与游玩偏好，推演三种不同的入世方式。</p><button disabled={isBusy} onClick={onGenerateRoutes} type="button"><ReloadIcon />{isBusy ? "正在推演…" : "推演三条角色路线"}</button></section> : <div className="character-route-grid creation-route-grid">{routeOptions.map((route, index) => <button aria-pressed={selectedRouteId === route.id} data-selected={selectedRouteId === route.id} key={route.id} onClick={() => onSelectRoute(route)} type="button"><span>{index + 1}</span><div><strong>{route.title}</strong><p>{route.origin.summary}</p><small>{route.opening.situation}</small></div><ArrowRightIcon /></button>)}</div>}

          <label className="preference-field origin-premise-field creation-origin-field">
            <span>人物来处 <small>修改后会重新准备开篇</small></span>
            <textarea maxLength={240} onChange={(event) => onDraft({ originPremise: event.currentTarget.value })} placeholder="写下不可随意改写的出身事实，但不要预设高阶修为或无条件力量。" rows={3} value={draft.originPremise} />
          </label>

          <fieldset className="creation-section play-preference-step">
            <legend><span>故事更看重</span><small>可多选</small></legend>
            <PreferenceOptionGroup legend="" multiple options={preferences.focus} selected={draft.playPreference.focus} onChange={(focus) => onDraft({ playPreference: { ...draft.playPreference, focus } })} />
          </fieldset>

          <div className="creation-preference-summary">
            <PreferenceOptionGroup legend="战斗" options={preferences.combatFrequency} selected={[draft.playPreference.combatFrequency]} onChange={([combatFrequency]) => onDraft({ playPreference: { ...draft.playPreference, combatFrequency } })} />
            <PreferenceOptionGroup legend="节奏" options={preferences.pace} selected={[draft.playPreference.pace]} onChange={([pace]) => onDraft({ playPreference: { ...draft.playPreference, pace } })} />
            <PreferenceOptionGroup legend="引导" options={preferences.guidance} selected={[draft.playPreference.guidance]} onChange={([guidance]) => onDraft({ playPreference: { ...draft.playPreference, guidance } })} />
          </div>

          <details className="creation-note-disclosure">
            <summary>补充游玩偏好 <small>{draft.playPreference.additionalNote.trim() ? "已填写" : "可留空"}</small></summary>
            <label className="preference-field preference-note-field"><textarea maxLength={160} onChange={(event) => onDraft({ playPreference: { ...draft.playPreference, additionalNote: event.currentTarget.value } })} placeholder="例如：希望人物关系自然发展，少一些机械受难。" rows={3} value={draft.playPreference.additionalNote} /></label>
          </details>

          {error && <ProblemNotice compact problem={error} />}
          <button className="creation-primary-button" disabled={isBusy || !draft.originPremise.trim()} onClick={() => void continueFromPath()} type="button">{isBusy ? "正在准备开篇…" : "下一步 · 核对命卷"}<ArrowRightIcon /></button>
          <button className="creation-back-button" disabled={isBusy} onClick={() => moveTo("identity")} type="button">返回修改身份</button>
        </div>}

        {step === "review" && <div className="creation-stage creation-review-stage">
          <section className="creation-summary" aria-labelledby="creation-summary-title">
            <div className="creation-summary-heading"><span className="creation-name-seal" aria-hidden="true">{draft.playerName.trim().slice(0, 1) || "命"}</span><div><h2 id="creation-summary-title">{draft.playerName || "尚未命名"}</h2><p>{race?.name} · {STORY_GENDER_NAMES[draft.gender] ?? "尚未设定"} · {startingStage?.name ?? "本族第一境"}初期</p><small>{world?.name}</small></div></div>
            <dl>
              {needsRaceIdentity && <div><dt>{raceIdentityLabel}</dt><dd>{draft.raceIdentity.trim() || "尚未填写"}</dd></div>}
              <div><dt>来处</dt><dd>{draft.originPremise.trim()}</dd></div>
              <div><dt>处境</dt><dd>{openingRoute?.opening.situation ?? "请返回来处，准备完整开篇后再确认。"}</dd></div>
              {(openingRoute?.origin.openHooks.length ?? 0) > 0 && <div><dt>仍待追寻</dt><dd>{openingRoute?.origin.openHooks.map((hook) => hook.summary).join("；")}</dd></div>}
              {(openingRoute?.origin.startingEntities.length ?? 0) > 0 && <div><dt>随身与相识</dt><dd>{openingRoute?.origin.startingEntities.map((entity) => `${entity.name}：${entity.summary}`).join("；")}</dd></div>}
              <div><dt>故事偏好</dt><dd>{preferenceNames || "采用默认偏好"}{draft.playPreference.additionalNote.trim() ? `；${draft.playPreference.additionalNote.trim()}` : ""}</dd></div>
            </dl>
            {(openingRoute?.origin.establishedFacts.length ?? 0) > 0 && <details>
              <summary>查看已确认的来历</summary>
              <ul>{openingRoute?.origin.establishedFacts.map((fact) => <li key={fact.localRef}>{fact.statement}</li>)}</ul>
            </details>}
          </section>
          {openingRoute?.opening.scene && <section className="creation-summary" aria-label="开篇预览">
            <h3>{openingRoute.opening.scene.title}</h3>
            <p style={{ whiteSpace: "pre-wrap" }}>{openingRoute.opening.scene.narrative}</p>
            <p>入卷后，你可以这样行动，也可以写自己的想法：</p>
            <ol>{openingRoute.opening.scene.choices.map((choice, index) => <li key={index}><strong>{choice.title}</strong>：{choice.description}</li>)}</ol>
          </section>}
          <section className="creation-trust-note"><div><strong>偏好以后仍可调整</strong><small>姓名、身份、来处与已发生的故事不会被静默改写</small></div></section>
          {error && <ProblemNotice compact problem={error} />}
          <button className="creation-primary-button" disabled={isBusy || Boolean(onPrepareOpening && !openingRoute?.opening.scene)} onClick={onCreate} type="button">{isBusy ? "命卷正在落笔…" : "确认并开启命卷"}<ArrowRightIcon /></button>
          <button className="creation-back-button" disabled={isBusy} onClick={() => moveTo("path")} type="button">返回修改路线与偏好</button>
        </div>}

        <footer className="creation-account-actions">
          <button disabled={isBusy} onClick={onLogout} type="button">退出当前邮箱</button>
        </footer>
      </section>
    </div>
  );
}

function PreferenceOptionGroup({ legend, options, selected, multiple = false, onChange }: {
  legend: string;
  options: StoryCatalogOption[];
  selected: string[];
  multiple?: boolean;
  onChange: (codes: string[]) => void;
}) {
  const choose = (code: string) => {
    if (!multiple) return onChange([code]);
    if (selected.includes(code)) {
      if (selected.length > 1) onChange(selected.filter((item) => item !== code));
      return;
    }
    onChange([...selected, code]);
  };
  if (!multiple && options.length === 1) {
    const option = options[0];
    return <section aria-label={`${legend}：${option.name}`} className="preference-fixed-value"><span>{legend}</span><strong>{option.name}</strong><small>{option.description}</small></section>;
  }
  return <fieldset className="preference-option-group">
    <legend>{legend}{multiple && <small>可多选</small>}</legend>
    <div>{options.map((option) => <button aria-pressed={selected.includes(option.code)} data-selected={selected.includes(option.code)} key={option.code} onClick={() => choose(option.code)} title={option.description} type="button">{option.name}</button>)}</div>
    <p>{options.filter((option) => selected.includes(option.code)).map((option) => option.description).join("；")}</p>
  </fieldset>;
}
