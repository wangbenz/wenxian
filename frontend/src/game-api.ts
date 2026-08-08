export type SyncMode = "connecting" | "server" | "offline";

export type Choice = {
  id: string;
  label: string;
  hint: string;
  tone?: "default" | "danger";
  actionType?: "COMBAT" | "ESCAPE" | "INFILTRATE" | "NEGOTIATE" | "CULTIVATE" | "EXPLORE" | "INVESTIGATE" | "TRAVEL" | "USE_ITEM" | "USE_ABILITY" | "REST" | "OTHER";
  targetCharacterId?: string | null;
  targetRealm?: string | null;
  riskLevel?: "LOW" | "MEDIUM" | "HIGH" | "LETHAL";
};

export type ChronicleEvent = {
  id: string;
  title: string;
  detail: string;
  at: number;
  kind: "decision" | "risk" | "gain" | "world";
};

export type CharacterRecord = {
  id: string;
  name: string;
  identity: string;
  realm?: string;
  condition?: "STABLE" | "WOUNDED" | "SEVERE" | "DYING";
  combatProfile?: string;
  state: string;
  location: string;
  relation: string;
  lastSeenAt: number;
};

export type InventoryItem = {
  id: string;
  definitionId: string;
  name: string;
  category: string;
  quality: string;
  quantity: number;
  description: string;
  keyItem: boolean;
};

export type AbilityRecord = {
  id: string;
  definitionId: string;
  name: string;
  type: "TALENT" | "TECHNIQUE" | "SPELL";
  stage: string;
  mastery: number;
  source: string;
  description: string;
  rules: string;
  maxUses: number;
  remainingUses: number;
  resetRule: "DAILY" | "NONE";
  lastResetDay: number;
  sideEffect: string;
};

export type PlayerState = {
  name: string;
  race: string;
  gender: string;
  age: number;
  lifespan: number;
  identity: string;
  origin?: string;
  trait?: string;
  originPremise?: string;
  lifeGoal: string;
  goal: string;
  burden: string;
  realm: string;
  constitution: string;
  condition?: "STABLE" | "WOUNDED" | "SEVERE" | "DYING";
  vitality: number;
  silver: number;
  insight: number;
  suspicion: number;
};

export type WorldProfile = {
  id: string;
  title: string;
  era: string;
  summary: string;
  cultivationLaw: string;
  dangerRule: string;
  regions: string[];
  factions: string[];
  regionProfiles?: Array<{ id: string; name: string; description: string; dominantPower: string; travelRule: string }>;
  factionProfiles?: Array<{ id: string; name: string; regionId: string; purpose: string }>;
  generatedBy: string;
};

export type ProtagonistOption = {
  id: string;
  name: string;
  race: string;
  gender: string;
  lifeGoal: string;
  age: number;
  identity: string;
  origin: string;
  originPremise?: string;
  trait: string;
  goal: string;
  burden: string;
  constitution: string;
  vitality: number;
  silver: number;
  insight: number;
  suspicion: number;
};

export type QuestThread = { id: string; title: string; state: string; danger: number };

export type GameState = {
  phase: "setup" | "talent" | "playing" | "dead";
  difficulty: "NORMAL" | "HARD";
  saveId: string;
  sceneId: string;
  sceneTitle: string;
  currentLocation: string;
  currentLocationId?: string;
  worldYear: number;
  minute: number;
  day: number;
  turn: number;
  economyVersion?: number;
  wallet?: {
    copperWen: number;
    lowSpiritStones: number;
    middleSpiritStones: number;
    highSpiritStones: number;
  } | null;
  narrative: string[];
  signals: string[];
  choices: Choice[];
  world: WorldProfile;
  creationProfile?: {
    race: string;
    gender: string;
    lifeGoal: string;
    originPremise: string;
    storyPreference: string;
  } | null;
  protagonistOptions: ProtagonistOption[];
  talentOptions: AbilityRecord[];
  talentRerolled: boolean;
  player?: PlayerState | null;
  characters: CharacterRecord[];
  events: ChronicleEvent[];
  threads: QuestThread[];
  inventory: InventoryItem[];
  abilities: AbilityRecord[];
  death?: {
    title: string;
    detail: string;
    at: number;
    sceneTitle: string;
    location: string;
    turn: number;
  } | null;
};

export type AccessStatus = {
  restricted: boolean;
  title: string;
  message: string;
  resumeAt: string;
};

export type ApiSnapshot = {
  state: GameState;
  revision: number;
  updatedAt: string;
  choices: Choice[];
  agent: {
    mode: string;
    fateGenerator?: string;
    authoritative: boolean;
    deepSeekConfigured?: boolean;
    world?: string;
  };
  actionResolution?: {
    result: "REJECTED" | "CLARIFY";
    code?: string;
    title: string;
    reason: string;
    suggestions: string[];
    turnConsumed?: boolean;
  } | null;
};

export type AuthResponse = {
  authenticated: boolean;
  newUser: boolean;
  user: { userId: string; email: string } | null;
  hasSave: boolean;
  passwordConfigured: boolean;
  nextStep: "LOGIN" | "RESUME_GAME" | "CREATE_CHARACTER";
};

export type BootstrapResponse = {
  gameName: string;
  version: string;
  world: WorldProfile;
  races: Array<{
    code: "HUMAN" | "DEMON" | "SPIRIT";
    name: string;
    description: string;
    strengths: string[];
    weaknesses: string[];
    lifespan: string;
  }>;
  lifeGoals: Array<{
    code: "LONGEVITY" | "KINSHIP" | "POWER" | "TRUTH" | "FREEDOM";
    name: string;
    description: string;
  }>;
  difficulties: Array<{ code: "NORMAL" | "HARD"; name: string; description: string }>;
  realmPath: string[];
  worldOptions?: Array<{ code: string; name: string; premise: string; cultivationStyle: string; openingRegion: string; custom: boolean; available?: boolean; availabilityNote?: string }>;
};

export type CharacterDraft = {
  playerName: string;
  race: "" | "HUMAN" | "DEMON" | "SPIRIT";
  gender: "" | "MALE" | "FEMALE" | "UNSPECIFIED";
  lifeGoal: "LONGEVITY" | "KINSHIP" | "POWER" | "TRUTH" | "FREEDOM";
  difficulty: "NORMAL" | "HARD";
  originPremise: string;
  preference: string;
  worldCode?: string;
  customWorld?: string;
};

export type ReleaseNote = {
  id: string;
  version: string;
  title: string;
  content: string;
  publishedAt: string;
  unread: boolean;
};

export type AiSettings = {
  turnLimit: number;
  usedTurns: number;
  remainingTurns: number;
  customApiEnabled: boolean;
  usingCustomApi: boolean;
  credentialConfigured: boolean;
  maskedSuffix: string | null;
  credentialStatus: string | null;
  lastVerifiedAt: string | null;
};

export type SaveSummary = {
  saveId: string;
  slotNo: number;
  phase: "setup" | "talent" | "playing";
  playerName: string;
  race: string;
  realm: string;
  currentLocation: string;
  sceneTitle: string;
  turn: number;
  updatedAt: string;
};

export type SaveList = { maxActiveSaves: number; saves: SaveSummary[] };

export type StoryChoice = { id: string; title: string; description: string };
export type StoryWorld = {
  code: string; name: string; source: string; premise: string;
  cultivationStyle: string; openingRegion: string;
};
export type StoryState = {
  schemaVersion: number;
  saveId: string;
  turnNumber: number;
  status: string;
  world: StoryWorld;
  protagonist: {
    name: string; race: string; gender: string; origin: string;
    realm: string; lifeGoal: string; storyTone: string;
  };
  worldTimeMinutes: number;
  currentLocation: string;
  condition: string;
  sceneTitle: string;
  narrative: string;
  choices: StoryChoice[];
  inventory: Array<{ id: string; name: string; type: string; description: string; status: string; source: string; quantity: number; inPossession?: boolean }>;
  abilities: Array<{ id: string; name: string; type: string; description: string; status: string; source: string }>;
  characters: Array<{ id: string; name: string; tier: string; identity: string; relationship: string; status: string; lastKnownLocation: string; tracked?: boolean }>;
  memories: Array<{ id: string; title: string; summary: string; worldTimeMinutes: number; importance: string }>;
};
export type StorySaveResponse = {
  state: StoryState;
  revision: number;
  generationMode: "PLATFORM" | "PERSONAL";
  personalProvider: string | null;
  personalModel: string | null;
};
export type StorySaveSummary = {
  id: string; slotNo: number; worldName: string; playerName: string; race: string;
  realm: string; turnNumber: number; status: string; updatedAt: string;
};
export type StoryBootstrap = {
  version: string;
  maxActiveSaves: number;
  worlds: Array<{ code: string; name: string; premise: string; cultivationStyle: string; openingRegion: string; custom: boolean; available: boolean; availabilityNote: string }>;
  races: string[];
  origins: string[];
};
export type StoryGenerationSettings = {
  mode: "PLATFORM" | "PERSONAL";
  platform: { turnLimit: number; usedTurns: number; remainingTurns: number; available: boolean };
  personalProviders: Array<{
    code: string;
    name: string;
    credentialConfigured: boolean;
    credentialStatus: string | null;
    maskedSuffix: string | null;
    lastVerifiedAt: string | null;
    models: Array<{ code: string; name: string }>;
  }>;
  selectedPersonalProvider: string | null;
  selectedPersonalModel: string | null;
};
export type StoryCreateSave = {
  worldCode: string; customWorld?: string; playerName: string; race: string; gender: string;
  origin: string; opening?: string; storyTone?: string;
};
export type StoryCharacterRoute = {
  id: string; title: string; origin: string; opening: string; preference: string;
};
export type StoryCharacterRouteRequest = {
  worldCode: string; customWorld?: string; playerName: string; race: string; gender: string;
  roleSeed?: string; preferenceSeed?: string;
};

type StoryBootstrapContract = {
  releaseVersion: string;
  maxActiveSaves: number;
  defaultEngine: { code: string; version: number; stateSchemaVersion: number };
  worlds: StoryBootstrap["worlds"];
  races: string[];
  origins: string[];
};

type StoryStateContract = Omit<StoryState, "schemaVersion" | "protagonist"> & {
  protagonist: Omit<StoryState["protagonist"], "storyTone"> & { storyPreference: string };
};

type StorySaveContract = {
  saveId: string;
  mode: "PLAYABLE" | "ARCHIVE";
  engineCode: string;
  engineVersion: number;
  stateSchemaVersion: number;
  revision: number;
  turnNumber: number;
  capabilities: string[];
  state: StoryStateContract;
  generationMode: "PLATFORM" | "PERSONAL";
  personalProvider: string | null;
  personalModel: string | null;
};

type StorySaveSummaryContract = {
  saveId: string; slotNo: number; mode: "PLAYABLE" | "ARCHIVE";
  engineCode: string; engineVersion: number; stateSchemaVersion: number;
  revision: number; turnNumber: number; capabilities: string[];
  worldName: string; playerName: string; race: string; realm: string;
  status: string; updatedAt: string;
};

/** 兼容期只在 API 边界转换结构，页面不再读取产品版本决定请求路径。 */
function legacyBootstrapView(source: StoryBootstrapContract): StoryBootstrap {
  return {
    version: source.releaseVersion,
    maxActiveSaves: source.maxActiveSaves,
    worlds: source.worlds,
    races: source.races,
    origins: source.origins,
  };
}

function legacySaveView(source: StorySaveContract): StorySaveResponse {
  return {
    state: {
      ...source.state,
      schemaVersion: source.stateSchemaVersion,
      protagonist: {
        ...source.state.protagonist,
        storyTone: source.state.protagonist.storyPreference,
      },
    },
    revision: source.revision,
    generationMode: source.generationMode,
    personalProvider: source.personalProvider,
    personalModel: source.personalModel,
  };
}

function legacySaveSummary(source: StorySaveSummaryContract): StorySaveSummary {
  return {
    id: source.saveId,
    slotNo: source.slotNo,
    worldName: source.worldName,
    playerName: source.playerName,
    race: source.race,
    realm: source.realm,
    turnNumber: source.turnNumber,
    status: source.status,
    updatedAt: source.updatedAt,
  };
}

export class ApiError extends Error {
  status: number;
  code?: string;
  category?: string;
  title?: string;
  suggestions: string[];
  retryable: boolean;
  turnConsumed: boolean;
  stateChanged: boolean;
  referenceId?: string;
  details: Record<string, unknown>;

  constructor(message: string, status: number, code?: string, details: Record<string, unknown> = {}, metadata: {
    category?: string;
    title?: string;
    suggestions?: string[];
    retryable?: boolean;
    turnConsumed?: boolean;
    stateChanged?: boolean;
    referenceId?: string;
  } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
    this.category = metadata.category;
    this.title = metadata.title;
    this.suggestions = metadata.suggestions ?? [];
    this.retryable = metadata.retryable ?? false;
    this.turnConsumed = metadata.turnConsumed ?? false;
    this.stateChanged = metadata.stateChanged ?? false;
    this.referenceId = metadata.referenceId;
  }
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/$/, "") ?? "";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: "include",
    ...init,
    headers: init?.body
      ? { "content-type": "application/json", ...init.headers }
      : init?.headers,
  });

  if (!response.ok) {
    let message = `请求失败（${response.status}）`;
    let code: string | undefined;
    let details: Record<string, unknown> = {};
    let metadata: ConstructorParameters<typeof ApiError>[4] = {};
    try {
      const body = await response.json() as {
        message?: string; code?: string; category?: string; title?: string; suggestions?: string[];
        retryable?: boolean; turnConsumed?: boolean; stateChanged?: boolean; referenceId?: string;
        details?: Record<string, unknown>;
      };
      message = body.message || message;
      code = body.code;
      details = body.details ?? {};
      metadata = {
        category: body.category,
        title: body.title,
        suggestions: Array.isArray(body.suggestions) ? body.suggestions : [],
        retryable: body.retryable,
        turnConsumed: body.turnConsumed,
        stateChanged: body.stateChanged,
        referenceId: body.referenceId,
      };
    } catch {
      // Keep the HTTP fallback when the response is not JSON.
    }
    throw new ApiError(message, response.status, code, details, metadata);
  }

  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export const gameApi = {
  accessStatus: () => request<AccessStatus>("/api/v1/auth/access-status"),
  me: () => request<AuthResponse>("/api/v1/auth/me"),
  login: (email: string, password: string) => request<AuthResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  }),
  register: (email: string, password: string) => request<AuthResponse>("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  }),
  setPassword: (currentPassword: string, newPassword: string) => request<AuthResponse>("/api/v1/auth/password", {
    method: "POST",
    body: JSON.stringify({ currentPassword: currentPassword || null, newPassword }),
  }),
  logout: () => request<void>("/api/v1/auth/logout", { method: "POST" }),
  deleteAccount: (confirmation: string) => request<void>("/api/v1/auth/account", {
    method: "DELETE", body: JSON.stringify({ confirmation }),
  }),
  aiSettings: () => request<AiSettings>("/api/v1/ai/settings"),
  saveAiCredential: (apiKey: string) => request<AiSettings>("/api/v1/ai/credential", {
    method: "PUT", body: JSON.stringify({ apiKey }),
  }),
  deleteAiCredential: () => request<void>("/api/v1/ai/credential", { method: "DELETE" }),
  bootstrap: () => request<BootstrapResponse>("/api/v1/game/bootstrap"),
  releaseNotes: () => request<ReleaseNote[]>("/api/v1/release-notes"),
  latestPublicReleaseNote: () => request<ReleaseNote | null>("/api/v1/release-notes/public-latest"),
  pendingReleaseNote: () => request<ReleaseNote | null>("/api/v1/release-notes/pending"),
  markReleaseNoteRead: (releaseId: string) => request<void>(`/api/v1/release-notes/${encodeURIComponent(releaseId)}/read`, { method: "POST" }),
  currentSave: () => request<{ exists: boolean; game: ApiSnapshot | null }>("/api/v1/saves/current"),
  saves: () => request<SaveList>("/api/v1/saves"),
  getSave: (saveId: string) => request<ApiSnapshot>(`/api/v1/saves/${encodeURIComponent(saveId)}`),
  generateName: (race: Exclude<CharacterDraft["race"], "">, gender: Exclude<CharacterDraft["gender"], "">, exclude: string[]) =>
    request<{ name: string; alternatives: string[] }>("/api/v1/character-names/generate", {
      method: "POST",
      body: JSON.stringify({ race, gender, exclude }),
    }),
  validateName: (name: string) => request<{ valid: boolean; normalizedName: string; message: string }>(
    "/api/v1/character-names/validate",
    { method: "POST", body: JSON.stringify({ name }) },
  ),
  createSave: (draft: CharacterDraft) => request<ApiSnapshot>("/api/v1/saves", {
    method: "POST",
    body: JSON.stringify(draft),
  }),
  restartSave: (saveId: string, draft: CharacterDraft) => request<ApiSnapshot>(
    `/api/v1/saves/${encodeURIComponent(saveId)}/restart`,
    { method: "POST", body: JSON.stringify(draft) },
  ),
  chooseFate: (saveId: string, candidateId: string) => request<ApiSnapshot>(
    `/api/v1/saves/${encodeURIComponent(saveId)}/protagonist`,
    { method: "POST", body: JSON.stringify({ candidateId }) },
  ),
  rerollTalents: (saveId: string) => request<ApiSnapshot>(`/api/v1/saves/${encodeURIComponent(saveId)}/talents/reroll`, { method: "POST" }),
  selectTalent: (saveId: string, talentId: string) => request<ApiSnapshot>(
    `/api/v1/saves/${encodeURIComponent(saveId)}/talents/select`,
    { method: "POST", body: JSON.stringify({ talentId }) },
  ),
  skipTalent: (saveId: string) => request<ApiSnapshot>(`/api/v1/saves/${encodeURIComponent(saveId)}/talents/skip`, { method: "POST" }),
  turn: (
    saveId: string,
    stateVersion: number,
    inputType: "CHOICE" | "TEXT" | "USE_ITEM" | "USE_ABILITY" | "PRACTICE",
    payload: { choiceId?: string; targetId?: string; content?: string },
  ) => request<ApiSnapshot>(`/api/v1/saves/${encodeURIComponent(saveId)}/turns`, {
    method: "POST",
    body: JSON.stringify({
      requestId: typeof window.crypto?.randomUUID === "function"
        ? window.crypto.randomUUID()
        : `turn-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      stateVersion,
      inputType,
      ...payload,
    }),
  }),
  deleteSave: (saveId: string) => request<void>(`/api/v1/saves/${encodeURIComponent(saveId)}`, {
    method: "DELETE",
  }),
  abandonSave: (saveId: string, playerName: string) => request<void>(`/api/v1/saves/${encodeURIComponent(saveId)}/abandon`, {
    method: "POST", body: JSON.stringify({ playerName }),
  }),
  storyBootstrap: () => request<StoryBootstrapContract>("/api/v1/story/bootstrap").then(legacyBootstrapView),
  storyCharacterRoutes: (draft: StoryCharacterRouteRequest) => request<{ routes: StoryCharacterRoute[] }>("/api/v1/story/character-routes", {
    method: "POST", body: JSON.stringify(draft),
  }),
  storySaves: () => request<StorySaveSummaryContract[]>("/api/v1/story/saves").then((rows) => rows.map(legacySaveSummary)),
  storySave: (saveId: string) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}`).then(legacySaveView),
  storyCreateSave: (draft: StoryCreateSave) => request<StorySaveContract>("/api/v1/story/saves", {
    method: "POST", body: JSON.stringify({ ...draft, storyPreference: draft.storyTone, storyTone: undefined }),
  }).then(legacySaveView),
  storyTurn: (saveId: string, action: string, expectedRevision: number) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/turns`, {
    method: "POST",
    body: JSON.stringify({ action, expectedRevision,
      requestId: typeof window.crypto?.randomUUID === "function" ? window.crypto.randomUUID() : `turn-${Date.now()}` }),
  }).then(legacySaveView),
  storyCorrect: (saveId: string, instruction: string, expectedRevision: number) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/corrections`, {
    method: "POST", body: JSON.stringify({ instruction, expectedRevision }),
  }).then(legacySaveView),
  storyRemoveEntity: (saveId: string, entityId: string, revision: number) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/entities/${encodeURIComponent(entityId)}?revision=${revision}`, {
    method: "DELETE",
  }).then(legacySaveView),
  storyArchive: (saveId: string) => request<void>(`/api/v1/story/saves/${encodeURIComponent(saveId)}`, { method: "DELETE" }),
  generationSettings: () => request<StoryGenerationSettings>("/api/v1/generation/settings"),
  saveGenerationSettings: (settings: { mode: "PLATFORM" | "PERSONAL"; provider?: string; model?: string }) => request<StoryGenerationSettings>("/api/v1/generation/settings", {
    method: "PUT", body: JSON.stringify(settings),
  }),
  saveProviderCredential: (provider: string, model: string, apiKey: string) => request<StoryGenerationSettings>(`/api/v1/generation/providers/${encodeURIComponent(provider)}/credential`, {
    method: "PUT", body: JSON.stringify({ model, apiKey }),
  }),
  deleteProviderCredential: (provider: string) => request<StoryGenerationSettings>(`/api/v1/generation/providers/${encodeURIComponent(provider)}/credential`, { method: "DELETE" }),
};
