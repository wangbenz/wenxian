export type SyncMode = "connecting" | "server" | "offline";

export type AccessStatus = {
  restricted: boolean;
  title: string;
  message: string;
  resumeAt: string;
};

export type AuthResponse = {
  authenticated: boolean;
  newUser: boolean;
  user: { userId: string; email: string } | null;
  hasSave: boolean;
  passwordConfigured: boolean;
  nextStep: "LOGIN" | "RESUME_GAME" | "CREATE_CHARACTER";
};

export type CharacterDraft = {
  playerName: string;
  raceId: string;
  gender: "" | "MALE" | "FEMALE" | "UNSPECIFIED";
  raceIdentity: string;
  originPremise: string;
  playPreference: PlayPreferenceDraft;
  worldPackageId: string;
  worldRevision: number;
};

export type PlayPreferenceDraft = {
  focus: string[];
  combatFrequency: string;
  pace: string;
  guidance: string;
  additionalNote: string;
};

export type ReleaseNote = {
  id: string;
  version: string;
  title: string;
  content: string;
  publishedAt: string;
  unread: boolean;
};

export type StoryChoice = { id: string; title: string; description: string };
export type StoryWorld = {
  code: string; name: string; source: string; premise: string;
  cultivationStyle: string; openingRegion: string;
};
export type StoryWorldTime = {
  year: number;
  day: number;
  phase: "DAWN" | "MORNING" | "NOON" | "AFTERNOON" | "DUSK" | "NIGHT" | "LATE_NIGHT";
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
  worldTime: StoryWorldTime;
  currentLocation: string;
  condition: string;
  sceneTitle: string;
  narrative: string;
  pendingDecision?: { prompt: string } | null;
  choices: StoryChoice[];
  inventory: Array<{ id: string; name: string; type: string; description: string; status: string; source: string; quantity: number; quantityKnown?: boolean; inPossession?: boolean }>;
  abilities: Array<{ id: string; name: string; type: string; description: string; status: string; source: string;
    abilityState?: "KNOWN" | "LEARNING" | "MASTERED" | "TEMPORARILY_BLOCKED" | "LOST" | "UNKNOWN" | null }>;
  characters: Array<{ id: string; name: string; tier: string; identity: string; relationship: string; status: string; lastKnownLocation: string; tracked?: boolean }>;
  memories: Array<{ id: string; title: string; summary: string; worldTime: StoryWorldTime; importance: string }>;
  realm: StoryRealmView;
};
export type StorySaveResponse = {
  state: StoryState;
  revision: number;
  generationMode: "PLATFORM" | "PERSONAL";
  personalProvider: string | null;
  personalModel: string | null;
};
export type StoryCorrectionPreview = {
  kind: "FACT" | "COGNITION" | "WORLD";
  explanation: string;
  confirmationPrompt: string;
  proposalToken: string;
};
export type StorySaveSummary = {
  id: string; slotNo: number; worldName: string; playerName: string; race: string;
  realm: string; turnNumber: number; status: string; updatedAt: string;
};
export type StoryBootstrap = {
  version: string;
  maxActiveSaves: number;
  creation: StoryCreationCatalog;
  capabilities: string[];
};
export type StoryCatalogOption = { code: string; name: string; description: string };
export type StoryRaceOption = {
  raceId: string;
  name: string;
  description: string;
  identityFacts: string[];
  socialHooks: string[];
};
export type StoryRegionOption = { regionId: string; name: string; description: string };
export type StoryWorldPackageOption = {
  packageId: string;
  revision: number;
  name: string;
  premise: string;
  coreRules: string[];
  narrativeGuidance: string;
  races: StoryRaceOption[];
  openingRegions: StoryRegionOption[];
  realmScale: StoryRealmScaleOption;
};
export type StoryCreationCatalog = {
  world: StoryWorldPackageOption;
  playPreferences: {
    focus: StoryCatalogOption[];
    combatFrequency: StoryCatalogOption[];
    pace: StoryCatalogOption[];
    guidance: StoryCatalogOption[];
    defaultFocus: string[];
    defaultCombatFrequency: string;
    defaultPace: string;
    defaultGuidance: string;
  };
};
export type StoryRealmStage = {
  stageId: string;
  order: number;
  name: string;
  description: string;
  aliases: string[];
  allowedNextStageIds: string[];
};
export type StoryRaceRealmOption = {
  raceId: string;
  stageId: string;
  name: string;
  description: string;
  capabilityBoundary: string;
  aliases: string[];
};
export type StoryRealmRankOption = {
  rankKey: string;
  order: number;
  raceRealms: StoryRaceRealmOption[];
};
export type StoryRealmScaleOption = {
  scaleId: string;
  revision: number;
  phases: Array<"EARLY" | "MIDDLE" | "LATE" | "PERFECT">;
  ranks: StoryRealmRankOption[];
};
export type StoryRealmView = {
  scaleId: string;
  scaleRevision: number;
  race: string;
  currentStageId: string;
  currentStageName: string;
  currentRankKey: string;
  currentPhase: "EARLY" | "MIDDLE" | "LATE" | "PERFECT";
  progressMarks: number;
  requiredProgressMarks: number;
  stages: StoryRealmStage[];
};
export type StoryGenerationSettings = {
  mode: "PLATFORM" | "PERSONAL";
  platform: { turnLimit: number; usedTurns: number; remainingTurns: number; available: boolean };
  platformTiers?: Array<{
    code: "LIGHT" | "BALANCED" | "DEEP";
    name: string;
    description: string;
    pointCost: number;
    selected: boolean;
  }>;
  selectedPlatformTier: "LIGHT" | "BALANCED" | "DEEP" | null;
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
  worldPackageId: string;
  worldRevision: number;
  identity: StoryCharacterIdentitySubmission;
  origin: StoryCharacterOriginSubmission;
  opening: StoryOpeningSubmission;
  playPreference: PlayPreferenceDraft;
  replaceSaveId?: string;
};
export type StoryCharacterIdentitySubmission = { name: string; genderCode: string; raceId: string };
export type StoryOriginFactSubmission = { localRef: string; type: "IDENTITY" | "PAST_EVENT" | "OBLIGATION" | "KNOWLEDGE"; statement: string };
export type StoryHookSubmission = { localRef: string; summary: string };
export type StoryStartingEntitySubmission = { localRef: string; entityType: "ITEM" | "ABILITY" | "CHARACTER"; name: string; summary: string };
export type StoryCharacterOriginSubmission = {
  summary: string;
  establishedFacts: StoryOriginFactSubmission[];
  openHooks: StoryHookSubmission[];
  startingEntities: StoryStartingEntitySubmission[];
};
export type StoryOpeningSubmission = {
  regionId: string;
  situation: string;
  scene?: { title: string; narrative: string; choices: Array<{ title: string; description: string }> };
};
export type StoryCharacterRoute = {
  id: string; title: string; origin: StoryCharacterOriginSubmission; opening: StoryOpeningSubmission;
};
export type StoryCharacterRouteRequest = {
  worldPackageId: string; worldRevision: number; identity: StoryCharacterIdentitySubmission;
  mode?: "RECOMMEND" | "FINALIZE";
  roleSeed?: string; preferenceSeed?: string;
};

type StoryBootstrapContract = {
  releaseVersion: string;
  maxActiveSaves: number;
  defaultEngine: { code: string; version: number; stateSchemaVersion: number };
  creation: StoryCreationCatalog;
  capabilities: string[];
};

type StoryStateContract = Omit<StoryState, "schemaVersion" | "protagonist"> & {
  protagonist: Omit<StoryState["protagonist"], "storyTone"> & { storyPreference: string };
};

type StorySaveContract = {
  saveId: string;
  mode: "PLAYABLE" | "ENDED";
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
  saveId: string; slotNo: number; mode: "PLAYABLE" | "ENDED";
  engineCode: string; engineVersion: number; stateSchemaVersion: number;
  revision: number; turnNumber: number; capabilities: string[];
  worldName: string; playerName: string; race: string; realm: string;
  status: string; updatedAt: string;
};

type StoryTurnRequestStatusContract = {
  requestId: string;
  status: "PROCESSING" | "COMMITTED" | "FAILED" | "UNKNOWN";
  terminal: boolean;
  retryable: boolean;
  retryAfterMs: number;
  message: string;
  updatedAt: string;
  snapshot: StorySaveContract | null;
};

export type StoryTurnRequestStatus = Omit<StoryTurnRequestStatusContract, "snapshot"> & {
  snapshot: StorySaveResponse | null;
};

/** API 边界只负责把传输契约转换成页面模型，页面不读取产品版本决定请求路径。 */
function storyBootstrapContractView(source: StoryBootstrapContract): StoryBootstrap {
  return {
    version: source.releaseVersion,
    maxActiveSaves: source.maxActiveSaves,
    creation: source.creation,
    capabilities: source.capabilities ?? [],
  };
}

function storySaveContractView(source: StorySaveContract): StorySaveResponse {
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

function storySaveSummaryContractView(source: StorySaveSummaryContract): StorySaveSummary {
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
const LOCAL_DEMO = import.meta.env.DEV && new URLSearchParams(window.location.search).has("demo");

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (LOCAL_DEMO) {
    const { localDemoRequest } = await import("./design/LocalDemoBackend");
    return localDemoRequest<T>(path, init);
  }

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
  releaseNotes: () => request<ReleaseNote[]>("/api/v1/release-notes"),
  latestPublicReleaseNote: () => request<ReleaseNote | null>("/api/v1/release-notes/public-latest"),
  pendingReleaseNote: () => request<ReleaseNote | null>("/api/v1/release-notes/pending"),
  markReleaseNoteRead: (releaseId: string) => request<void>(`/api/v1/release-notes/${encodeURIComponent(releaseId)}/read`, { method: "POST" }),
  generateName: (race: "HUMAN" | "DEMON" | "SPIRIT", gender: Exclude<CharacterDraft["gender"], "">, exclude: string[]) =>
    request<{ name: string; alternatives: string[] }>("/api/v1/character-names/generate", {
      method: "POST",
      body: JSON.stringify({ race, gender, exclude }),
    }),
  validateName: (name: string) => request<{ valid: boolean; normalizedName: string; message: string }>(
    "/api/v1/character-names/validate",
    { method: "POST", body: JSON.stringify({ name }) },
  ),
  storyBootstrap: () => request<StoryBootstrapContract>("/api/v1/story/bootstrap").then(storyBootstrapContractView),
  storyCharacterRoutes: (draft: StoryCharacterRouteRequest) => request<{ routes: StoryCharacterRoute[] }>("/api/v1/story/character-routes", {
    method: "POST", body: JSON.stringify(draft),
  }),
  storySaves: () => request<StorySaveSummaryContract[]>("/api/v1/story/saves").then((rows) => rows.map(storySaveSummaryContractView)),
  storySave: (saveId: string) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}`).then(storySaveContractView),
  storyCreateSave: (draft: StoryCreateSave) => request<StorySaveContract>("/api/v1/story/saves", {
    method: "POST", body: JSON.stringify(draft),
  }).then(storySaveContractView),
  storyTurn: (saveId: string, action: string, expectedRevision: number, choiceId?: string, requestId?: string) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/turns`, {
    method: "POST",
    body: JSON.stringify({ action, expectedRevision, choiceId: choiceId || undefined,
      requestId: requestId ?? (typeof window.crypto?.randomUUID === "function" ? window.crypto.randomUUID() : `turn-${Date.now()}`) }),
  }).then(storySaveContractView),
  storyTurnRequestStatus: (saveId: string, requestId: string) => request<StoryTurnRequestStatusContract>(
    `/api/v1/story/saves/${encodeURIComponent(saveId)}/turn-requests/${encodeURIComponent(requestId)}`,
  ).then((value): StoryTurnRequestStatus => ({
    ...value,
    snapshot: value.snapshot ? storySaveContractView(value.snapshot) : null,
  })),
  storyCorrectionPreview: (saveId: string, instruction: string, expectedRevision: number) => request<StoryCorrectionPreview>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/corrections/preview`, {
    method: "POST", body: JSON.stringify({ instruction, expectedRevision }),
  }),
  storyCorrectionConfirm: (saveId: string, proposalToken: string, expectedRevision: number) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/corrections/confirm`, {
    method: "POST", body: JSON.stringify({ proposalToken, expectedRevision }),
  }).then(storySaveContractView),
  storyRemoveEntity: (saveId: string, entityId: string, revision: number) => request<StorySaveContract>(`/api/v1/story/saves/${encodeURIComponent(saveId)}/entities/${encodeURIComponent(entityId)}?revision=${revision}`, {
    method: "DELETE",
  }).then(storySaveContractView),
  storyDeleteSave: (saveId: string) => request<void>(`/api/v1/story/saves/${encodeURIComponent(saveId)}`, { method: "DELETE" }),
  generationSettings: () => request<StoryGenerationSettings>("/api/v1/generation/settings"),
  saveGenerationSettings: (settings: { mode: "PLATFORM" | "PERSONAL"; provider?: string; model?: string; tierCode?: "LIGHT" | "BALANCED" | "DEEP" }) => request<StoryGenerationSettings>("/api/v1/generation/settings", {
    method: "PUT", body: JSON.stringify(settings),
  }),
  saveProviderCredential: (provider: string, model: string, apiKey: string) => request<StoryGenerationSettings>(`/api/v1/generation/providers/${encodeURIComponent(provider)}/credential`, {
    method: "PUT", body: JSON.stringify({ model, apiKey }),
  }),
  deleteProviderCredential: (provider: string) => request<StoryGenerationSettings>(`/api/v1/generation/providers/${encodeURIComponent(provider)}/credential`, { method: "DELETE" }),
};
