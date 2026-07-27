export type SyncMode = "connecting" | "server" | "offline";

export type Choice = {
  id: string;
  label: string;
  hint: string;
  tone?: "default" | "danger";
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
  lifeGoal: string;
  goal: string;
  burden: string;
  realm: string;
  constitution: string;
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
  saveId: string;
  sceneId: string;
  sceneTitle: string;
  currentLocation: string;
  worldYear: number;
  minute: number;
  day: number;
  turn: number;
  narrative: string[];
  signals: string[];
  choices: Choice[];
  world: WorldProfile;
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
    title: string;
    reason: string;
    suggestions: string[];
  } | null;
};

export type AuthResponse = {
  authenticated: boolean;
  newUser: boolean;
  user: { userId: string; email: string } | null;
  hasSave: boolean;
  nextStep: "LOGIN" | "RESUME_GAME" | "CREATE_CHARACTER";
};

export type BootstrapResponse = {
  gameName: string;
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
  realmPath: string[];
};

export type CharacterDraft = {
  playerName: string;
  race: "" | "HUMAN" | "DEMON" | "SPIRIT";
  gender: "" | "MALE" | "FEMALE" | "UNSPECIFIED";
  lifeGoal: "LONGEVITY" | "KINSHIP" | "POWER" | "TRUTH" | "FREEDOM";
  preference: string;
};

export class ApiError extends Error {
  status: number;
  code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
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
    try {
      const body = await response.json() as { message?: string; code?: string };
      message = body.message || message;
      code = body.code;
    } catch {
      // Keep the HTTP fallback when the response is not JSON.
    }
    throw new ApiError(message, response.status, code);
  }

  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export const gameApi = {
  me: () => request<AuthResponse>("/api/v1/auth/me"),
  login: (email: string, inviteCode: string) => request<AuthResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, inviteCode: inviteCode || null }),
  }),
  logout: () => request<void>("/api/v1/auth/logout", { method: "POST" }),
  bootstrap: () => request<BootstrapResponse>("/api/v1/game/bootstrap"),
  currentSave: () => request<{ exists: boolean; game: ApiSnapshot | null }>("/api/v1/saves/current"),
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
};
