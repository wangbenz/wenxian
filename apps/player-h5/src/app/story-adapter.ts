import type { CharacterDraft, StoryBootstrap, StorySaveResponse, StorySaveSummary } from "../game-api";
import type {
  PlayerBootstrapView,
  PlayerSaveSummary,
  PlayerStorySnapshot,
  PlayerStoryView,
} from "./player-read-model";

export const DEFAULT_DRAFT: CharacterDraft = {
  playerName: "",
  raceId: "",
  gender: "",
  raceIdentity: "",
  originPremise: "",
  playPreference: {
    focus: [],
    combatFrequency: "",
    pace: "",
    guidance: "",
    additionalNote: "",
  },
  worldPackageId: "",
  worldRevision: 0,
};

/** 新开或重开命卷时，从当前权威目录生成完整草案，避免绕过世界绑定和偏好默认值。 */
export function freshCharacterDraft(bootstrap: PlayerBootstrapView): CharacterDraft {
  const world = bootstrap.creation.world;
  const preferences = bootstrap.creation.playPreferences;
  return {
    ...DEFAULT_DRAFT,
    raceId: world.races[0]?.raceId ?? "",
    worldPackageId: world.packageId,
    worldRevision: world.revision,
    playPreference: {
      focus: [...preferences.defaultFocus],
      combatFrequency: preferences.defaultCombatFrequency,
      pace: preferences.defaultPace,
      guidance: preferences.defaultGuidance,
      additionalNote: "",
    },
  };
}

const STORY_RACE_CODES: Record<string, "HUMAN" | "DEMON" | "SPIRIT"> = { 人族: "HUMAN", 妖族: "DEMON", 灵族: "SPIRIT" };
export const STORY_RACE_NAMES: Record<string, string> = { HUMAN: "人族", DEMON: "妖族", SPIRIT: "灵族" };
export const STORY_GENDER_NAMES: Record<string, string> = { MALE: "男", FEMALE: "女", UNSPECIFIED: "不设定" };

const ABILITY_STATE_NAMES: Record<string, string> = {
  KNOWN: "已知", LEARNING: "学习中", MASTERED: "已掌握",
  TEMPORARILY_BLOCKED: "暂不可用", LOST: "已失去", UNKNOWN: "状态未明",
};

/** 新字段优先；旧服务仅兼容明确学习枚举，通用 ACTIVE 和陌生值均不推断学习程度。 */
function abilityState(ability: StorySaveResponse["state"]["abilities"][number]): string {
  const value = (ability.abilityState ?? ability.status ?? "").trim().toUpperCase();
  return Object.prototype.hasOwnProperty.call(ABILITY_STATE_NAMES, value) ? value : "UNKNOWN";
}

export function toPlayerBootstrap(source: StoryBootstrap): PlayerBootstrapView {
  return {
    version: source.version,
    capabilities: source.capabilities,
    creation: source.creation,
  };
}

export function selectedWorld(bootstrap: PlayerBootstrapView, _draft: CharacterDraft) {
  return bootstrap.creation.world;
}

export function selectedRace(bootstrap: PlayerBootstrapView, draft: CharacterDraft) {
  return selectedWorld(bootstrap, draft)?.races.find((race) => race.raceId === draft.raceId);
}

export function raceCodeFromName(raceName: string): "HUMAN" | "DEMON" | "SPIRIT" | null {
  return STORY_RACE_CODES[raceName] ?? null;
}

export function playPreferenceSummary(bootstrap: PlayerBootstrapView, draft: CharacterDraft): string {
  const options = bootstrap.creation.playPreferences;
  const focus = draft.playPreference.focus.map((code) => options.focus.find((item) => item.code === code)?.name)
    .filter(Boolean).join("、");
  const combat = options.combatFrequency.find((item) => item.code === draft.playPreference.combatFrequency)?.name;
  const pace = options.pace.find((item) => item.code === draft.playPreference.pace)?.name;
  const guidance = options.guidance.find((item) => item.code === draft.playPreference.guidance)?.name;
  const structured = `关注${focus || "探索"}；战斗${combat || "适中"}；节奏${pace || "适中"}；引导${guidance || "适中"}`;
  return draft.playPreference.additionalNote.trim()
    ? `${structured}。补充：${draft.playPreference.additionalNote.trim()}`
    : structured;
}

export function toPlayerStorySnapshot(source: StorySaveResponse): PlayerStorySnapshot {
  const state = source.state;
  const raceCode = STORY_RACE_CODES[state.protagonist.race] || state.protagonist.race;
  const story: PlayerStoryView = {
    phase: state.status === "ACTIVE" ? "playing" : "dead",
    saveId: state.saveId,
    sceneTitle: state.sceneTitle, currentLocation: state.currentLocation,
    worldTime: state.worldTime, turn: state.turnNumber,
    narrative: safeNarrativeParagraphs(state.narrative), pendingDecision: state.pendingDecision ?? null,
    choices: state.choices.map((choice) => ({ id: choice.id, label: choice.title, hint: choice.description })),
    world: { code: state.world.code, title: state.world.name, summary: state.world.premise,
      cultivationLaw: state.world.cultivationStyle, openingRegion: state.world.openingRegion },
    player: { name: state.protagonist.name, race: raceCode, gender: STORY_GENDER_NAMES[state.protagonist.gender] ?? state.protagonist.gender,
      identity: state.protagonist.origin, origin: state.protagonist.origin, lifeGoal: state.protagonist.lifeGoal,
      storyPreference: state.protagonist.storyTone, realm: state.protagonist.realm, condition: state.condition },
    characters: state.characters.filter((person) => person.tracked !== false).map((person) => ({ id: person.id, name: person.name, identity: person.identity,
      state: person.status === "ALIVE" || person.status === "ACTIVE" ? "尚在人世" : person.status === "DEAD" ? "已故" : person.status === "MISSING" ? "下落不明" : person.status === "DORMANT" ? "暂时沉寂" : "近况未明",
      location: person.lastKnownLocation, relation: person.relationship })),
    events: [...state.memories].reverse().map((memory) => ({ id: memory.id, title: memory.title, detail: memory.summary,
      at: memory.worldTime, kind: memory.importance === "KEY" ? "world" : "decision" })),
    inventory: state.inventory.filter((item) => item.inPossession !== false).map((item) => ({
      id: item.id, name: item.name, category: item.type, status: item.status === "ACTIVE" ? "持有" : item.status || "仍在行囊中",
      quantity: item.quantity, quantityKnown: item.quantityKnown !== false, description: item.description })),
    abilities: state.abilities.filter((ability) => abilityState(ability) !== "LOST").map((ability) => ({ id: ability.id,
      name: ability.name, type: ability.type === "TALENT" ? "TALENT" : ability.type === "SPELL" ? "SPELL" : "TECHNIQUE",
      stage: ABILITY_STATE_NAMES[abilityState(ability)], source: ability.source,
      description: ability.description })),
  };
  return { story, revision: source.revision };
}

export function toPlayerSaveSummary(source: StorySaveSummary): PlayerSaveSummary {
  return {
    saveId: source.id,
    slotNo: source.slotNo,
    playerName: source.playerName,
    race: STORY_RACE_NAMES[source.race] || source.race,
    realm: source.realm,
    currentLocation: source.worldName,
    turn: source.turnNumber,
    updatedAt: source.updatedAt,
  };
}

function safeNarrativeParagraphs(value: string): string[] {
  const text = (value || "").trim();
  const looksLikeRawPayload = /^[{[]/.test(text)
    && /"(?:narrative|choices|duration|rollingSummary)"\s*:/.test(text);
  if (looksLikeRawPayload) {
    return ["这一页命卷的文字排版出现异常，原始报文已被隐藏。你可以从下方选择行动继续历练。"];
  }
  const paragraphs = text.split(/\n+/).map((paragraph) => paragraph.trim()).filter(Boolean);
  return paragraphs.length > 0 ? paragraphs : ["这一页命卷只留下了模糊痕迹。"];
}
