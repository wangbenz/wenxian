import type { StoryCreationCatalog, StoryWorldTime } from "../game-api";

/**
 * 玩家端只读模型：只承载稳定 Story API 已经确认、且页面确实需要展示的内容。
 * 它不是服务端权威状态，也不补造年龄、体力、货币、线索或 Agent 内部状态。
 */
export type PlayerChoice = {
  id: string;
  label: string;
  hint: string;
  tone?: "default" | "danger";
};

export type PlayerChronicleEvent = {
  id: string;
  title: string;
  detail: string;
  at: StoryWorldTime;
  kind: "decision" | "risk" | "gain" | "world";
};

export type PlayerCharacterRecord = {
  id: string;
  name: string;
  identity: string;
  state: string;
  location: string;
  relation: string;
};

export type PlayerInventoryItem = {
  id: string;
  name: string;
  category: string;
  status: string;
  quantity: number;
  quantityKnown: boolean;
  description: string;
};

export type PlayerAbilityRecord = {
  id: string;
  name: string;
  type: "TALENT" | "TECHNIQUE" | "SPELL";
  stage: string;
  source: string;
  description: string;
};

export type PlayerProfile = {
  name: string;
  race: string;
  gender: string;
  identity: string;
  origin: string;
  lifeGoal: string;
  storyPreference: string;
  realm: string;
  condition: string;
};

export type PlayerWorldProfile = {
  code: string;
  title: string;
  summary: string;
  cultivationLaw: string;
  openingRegion: string;
};

export type PlayerStoryView = {
  phase: "playing" | "dead";
  saveId: string;
  sceneTitle: string;
  currentLocation: string;
  worldTime: StoryWorldTime;
  turn: number;
  narrative: string[];
  pendingDecision: { prompt: string } | null;
  choices: PlayerChoice[];
  world: PlayerWorldProfile;
  player: PlayerProfile;
  characters: PlayerCharacterRecord[];
  events: PlayerChronicleEvent[];
  inventory: PlayerInventoryItem[];
  abilities: PlayerAbilityRecord[];
};

export type PlayerStorySnapshot = {
  story: PlayerStoryView;
  revision: number;
};

export type PlayerBootstrapView = {
  version: string;
  capabilities: string[];
  creation: StoryCreationCatalog;
};

export type PlayerSaveSummary = {
  saveId: string;
  slotNo: number;
  playerName: string;
  race: string;
  realm: string;
  currentLocation: string;
  turn: number;
  updatedAt: string;
};
