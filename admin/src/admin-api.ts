export type LoginResponse = { ok: boolean; token: string };

export type DashboardData = {
  totalUsers: number; usersWithCharacter: number; todayActive: number; onlinePlayers: number;
  usersWithAnyCharacter: number; currentCharacters: number; historicalCharacters: number; totalCharacters: number;
  usersWithHistoricalCharacters: number; deadCharacters: number; restartedCharacters: number;
  activationFunnel: Array<{ key: string; label: string; users: number; conversion: number }>;
  todayRegistrations: number; yesterdayRegistrations: number;
  dailyRegistrations: Array<{ date: string; count: number }>;
  totalTurns: number; todayTurns: number; avgLatency: number; currentModel: string;
  totalPlatformTurns: number; totalCustomTurns: number; todayPlatformTurns: number; todayCustomTurns: number;
  customApiUsers: number; todayPublicInvocations: number;
  todayAiRequests: number; todayAiFailures: number;
  todayPlatformPromptTokens: number; todayPlatformCompletionTokens: number;
  todayPlatformCacheHitTokens: number; todayPlatformCacheMissTokens: number;
  todayCustomPromptTokens: number; todayCustomCompletionTokens: number;
  totalPlatformTokens: number; totalCustomTokens: number;
  todayPlatformAverageTokensPerChapter: number; todayCustomAverageTokensPerChapter: number;
  lockedLoginPairs: number; platformKeyHealthy: boolean;
  usageTrackingStartedAt: string;
  dailyTokens: Array<{
    date: string; platformPromptTokens: number; platformCompletionTokens: number;
    customPromptTokens: number; customCompletionTokens: number;
    cacheHitTokens: number; cacheMissTokens: number;
    requests: number; failures: number; estimatedRequests: number; players: number;
    platformTurns: number; customTurns: number; models: string;
  }>;
  modelUsage: Array<{
    provider: string; model: string; source: "PUBLIC" | "USER"; requests: number;
    promptTokens: number; completionTokens: number; cacheHitTokens: number; cacheMissTokens: number;
    failures: number; avgLatency: number; firstUsedAt: string; lastUsedAt: string;
  }>;
  versionMetrics: Array<{
    version: string; requests: number; tokens: number; failures: number; avgLatency: number;
    characters: number; turns: number; rejections: number; deaths: number;
  }>;
  recentTurns: Array<{ playerName: string; realm: string; turn: number; action: string; tokens: number; latency: number; model: string; at: string }>;
};

export type V014Overview = {
  totalSaves: number; activeSaves: number; archivedSaves: number; players: number;
  totalTurns: number; promptTokens: number; completionTokens: number; avgLatency: number;
  personalCredentialUsers: number; invalidCredentialUsers: number; manualCorrections: number;
  legacyCredentialMigrationGaps: number;
  modelUsage: Array<{ provider: string; model: string; source: "PUBLIC" | "USER"; requests: number; promptTokens: number; completionTokens: number; avgLatency: number }>;
  recentTurns: Array<{ saveId: string; userId: string; turn: number; action: string; model: string; source: "PUBLIC" | "USER"; tokens: number; latency: number; at: string }>;
};

export type InviteCodeItem = {
  id: string; codeDisplay: string | null; plaintextAvailable: boolean;
  status: "ACTIVE" | "EXHAUSTED"; maxUses: number; usedCount: number;
  expiresAt: string | null; createdAt: string;
};

export type UserRow = {
  userId: string; email: string; rawEmail: string; registeredAt: string;
  lastLoginAt: string | null; saveId: string | null; playerName: string; realm: string;
  race: string; difficulty: "NORMAL" | "HARD"; characterState: "NONE" | "CURRENT" | "RESTARTED";
  archiveCount: number; turnCount: number; totalTokens: number; saveUpdated: string | null;
  credentialConfigured: boolean; credentialStatus?: string; credentialSuffix?: string;
  credentialVerifiedAt?: string | null; activeSaveCount: number;
};
export type UserListResult = [{ total: number; page: number; size: number }, { rows: UserRow[] }];
export type TurnRankingItem = {
  userId: string; email: string; saveId: string; playerName: string; realm: string;
  turn: number; lastActiveAt: string | null; saveUpdatedAt: string | null;
};

export type LifeSummary = {
  status: "CURRENT" | "ARCHIVED"; saveId: string; at: string; playerName: string;
  realm: string; race: string; difficulty: "NORMAL" | "HARD"; phase: string; turn: number; sceneTitle: string;
  endReason: "ACTIVE" | "DEAD" | "RESTARTED" | "ABANDONED" | "LEGACY"; endingTitle: string; endingDetail: string; totalTokens: number;
};
export type UserDetail = {
  user: { userId: string; email: string; status: string; registeredAt: string; lastLoginAt: string | null;
    passwordConfigured: boolean; passwordSetAt?: string | null; registrationNetwork?: string | null;
    credentialConfigured: boolean; credentialStatus?: string | null; credentialSuffix?: string | null; credentialVerifiedAt?: string | null };
  accountSummary: { totalCharacters: number; currentCharacters: number; historicalCharacters: number;
    totalTurns: number; totalTokens: number; longestLifeName: string; longestLifeTurns: number };
  currentLives: LifeSummary[]; historicalLives: LifeSummary[];
};
export type TurnPage = { total: number; page: number; size: number; rows: Array<{
  turn: number; action: string; promptTokens: number; completionTokens: number; totalTokens: number;
  latency: number; model: string; responseId: string; stateDelta: string; status: string; at: string;
}> };
export type TurnRow = TurnPage["rows"][number];
export type AgentExecution = { turn: number; attempt: number; status: string; detail: string; model: string; responseId: string; latency: number; at: string };
export type ActionAdjudication = { turn: number; stage: string; reasonCode: string; intentMode: string; actionType: string; version: string; at: string };
export type ReleaseNoteAdmin = { id: string; version: string; title: string; content: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; announceAll: boolean; showOnLogin: boolean; publishedAt: string | null; createdAt: string; updatedAt: string; readCount: number };
export type AppVersion = { version: string };
export type ModelConfig = {
  providerCode: string; modelCode: string; displayName: string; keyConfigured: boolean; active: boolean;
  keySource: "DATABASE" | "SERVER_DEFAULT" | "MISSING"; keySuffix: string;
  keyState: "CONFIGURED" | "MISSING" | "DECRYPT_ERROR";
  updatedAt: string; lastRequestStatus: string; lastUsedAt: string;
};
export type GenerationProvider = {
  providerCode: string; displayName: string; baseUrl: string; adapterType: string;
  enabled: boolean; userKeyEnabled: boolean; keyConfigured: boolean; keySuffix: string;
  keyState: "CONFIGURED" | "MISSING" | "DECRYPT_ERROR"; updatedAt: string;
  models: Array<{ modelCode: string; displayName: string; apiModelName: string;
    platformEnabled: boolean; userSelectable: boolean; activePlatform: boolean; updatedAt: string }>;
};
export type GameplayConfig = { accountPublicTurnLimit: number; limitMessage: string; customApiEnabled: boolean; customApiUsers: number; invalidCredentialUsers: number; todayPublicInvocations: number; todayCustomTurns: number; maxActiveSaves: number; activeSaveCount: number; usersWithMultipleSaves: number };
export type RegistrationConfig = { maxUsers: number; currentUsers: number; remainingSlots: number; limitMessage: string; contactText: string };
export type AccessControl = {
  mode: "OPEN" | "WHITELIST"; title: string; message: string; resumeAt: string;
  whitelist: Array<{ userId: string; email: string; note: string; createdBy: string; createdAt: string; updatedAt: string }>;
};
export type AdminAccount = {
  id: string; username: string; status: "ACTIVE" | "DISABLED"; createdAt: string;
  lastLoginAt: string | null; activeSessions: number; current: boolean;
};
export type OperationError = {
  referenceId: string; saveId: string | null; operation: string; category: string; code: string;
  httpStatus: number; message: string; diagnostic: string; email: string; playerName: string; at: string;
};

const TOKEN_KEY = "wenxian_admin_token";
const BASE = "/api/admin";
function token() { return window.sessionStorage.getItem(TOKEN_KEY); }
export function setToken(value: string) { window.sessionStorage.setItem(TOKEN_KEY, value); }
export function clearToken() { window.sessionStorage.removeItem(TOKEN_KEY); }
export function isAuthenticated() { return token() !== null; }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {};
  if (init?.body) headers["content-type"] = "application/json";
  if (token()) headers.authorization = `Bearer ${token()}`;
  const response = await fetch(`${BASE}${path}`, { credentials: "include", ...init, headers: { ...headers, ...init?.headers } });
  if (!response.ok) {
    let message = `请求失败（${response.status}）`;
    try { message = (await response.json() as { message?: string }).message || message; } catch { /* noop */ }
    throw new Error(message);
  }
  if (response.status === 204 || response.headers.get("content-length") === "0") return undefined as T;
  return response.json() as Promise<T>;
}

export const adminApi = {
  login: (username: string, password: string) => request<LoginResponse>("/login", { method: "POST", body: JSON.stringify({ username, password }) }),
  changePassword: (oldPassword: string, newPassword: string) => request<{ ok: boolean; message: string }>("/change-password", { method: "POST", body: JSON.stringify({ oldPassword, newPassword }) }),
  adminAccounts: () => request<AdminAccount[]>("/admin-accounts"),
  createAdminAccount: (username: string, password: string) => request<AdminAccount>("/admin-accounts", { method: "POST", body: JSON.stringify({ username, password }) }),
  updateAdminAccountStatus: (adminId: string, active: boolean) => request<{ ok: boolean }>(`/admin-accounts/${encodeURIComponent(adminId)}/status`, { method: "POST", body: JSON.stringify({ active }) }),
  resetAdminAccountPassword: (adminId: string) => request<{ temporaryPassword: string }>(`/admin-accounts/${encodeURIComponent(adminId)}/temporary-password`, { method: "POST" }),
  dashboard: () => request<DashboardData>("/dashboard"),
  v014Overview: () => request<V014Overview>("/v014/overview"),
  inviteCodes: (status = "") => request<InviteCodeItem[]>(`/invite-codes?status=${encodeURIComponent(status)}`),
  generateInviteCodes: (count: number, maxUses: number) => request<{ codes: string[]; count: number }>("/invite-codes", { method: "POST", body: JSON.stringify({ count, maxUses }) }),
  users: (search = "", characterState = "", difficulty = "", realm = "", registeredDate = "", page = 0, size = 20) => request<UserListResult>(`/users?search=${encodeURIComponent(search)}&characterState=${characterState}&difficulty=${difficulty}&realm=${encodeURIComponent(realm)}&registeredDate=${encodeURIComponent(registeredDate)}&page=${page}&size=${size}`),
  turnRanking: (limit = 10) => request<TurnRankingItem[]>(`/users/turn-ranking?limit=${limit}`),
  userDetail: (userId: string) => request<UserDetail>(`/users/${encodeURIComponent(userId)}`),
  resetPlayerPassword: (userId: string) => request<{ temporaryPassword: string }>(`/users/${encodeURIComponent(userId)}/temporary-password`, { method: "POST" }),
  unlockPlayerAuth: (userId: string) => request<{ ok: boolean; message: string }>(`/users/${encodeURIComponent(userId)}/unlock-auth`, { method: "POST" }),
  userTurns: (userId: string, saveId: string, page = 0, size = 20, turn = "") => request<TurnPage>(`/users/${encodeURIComponent(userId)}/lives/${encodeURIComponent(saveId)}/turns?page=${page}&size=${size}${turn ? `&turn=${encodeURIComponent(turn)}` : ""}`),
  userAdjudications: (userId: string, saveId: string, limit = 20) => request<ActionAdjudication[]>(`/users/${encodeURIComponent(userId)}/lives/${encodeURIComponent(saveId)}/adjudications?limit=${limit}`),
  executions: (saveId: string, turn?: number) => request<AgentExecution[]>(`/agent-executions/${encodeURIComponent(saveId)}${turn === undefined ? "" : `?turn=${turn}`}`),
  releaseNotes: () => request<ReleaseNoteAdmin[]>("/release-notes"),
  appVersion: () => request<AppVersion>("/app-version"),
  updateAppVersion: (version: string) => request<AppVersion>("/app-version", { method: "POST", body: JSON.stringify({ version }) }),
  saveReleaseNote: (note: Partial<ReleaseNoteAdmin>) => request<ReleaseNoteAdmin>("/release-notes", { method: "POST", body: JSON.stringify(note) }),
  clearLoginAnnouncement: () => request<{ ok: boolean; updated: number; message: string }>("/release-notes/login-announcement", { method: "DELETE" }),
  modelConfigs: () => request<ModelConfig[]>("/model-configs"),
  updateModelConfig: (modelCode: string, active: boolean, apiKey = "") => request<{ ok: boolean }>(`/model-configs/${encodeURIComponent(modelCode)}`, { method: "POST", body: JSON.stringify({ active, apiKey }) }),
  generationProviders: () => request<GenerationProvider[]>("/generation-providers"),
  updateGenerationProvider: (providerCode: string, payload: { displayName: string; baseUrl: string; enabled: boolean; userKeyEnabled: boolean; apiKey?: string }) => request<{ ok: boolean }>(`/generation-providers/${encodeURIComponent(providerCode)}`, { method: "POST", body: JSON.stringify(payload) }),
  updateGenerationModel: (providerCode: string, modelCode: string, payload: { displayName: string; apiModelName: string; platformEnabled: boolean; userSelectable: boolean; activePlatform: boolean }) => request<{ ok: boolean }>(`/generation-providers/${encodeURIComponent(providerCode)}/models/${encodeURIComponent(modelCode)}`, { method: "POST", body: JSON.stringify(payload) }),
  gameplayConfig: () => request<GameplayConfig>("/gameplay-config"),
  updateGameplayConfig: (accountPublicTurnLimit: number, customApiEnabled: boolean, limitMessage: string, maxActiveSaves: number) => request<GameplayConfig>("/gameplay-config", { method: "POST", body: JSON.stringify({ accountPublicTurnLimit, customApiEnabled, limitMessage, maxActiveSaves }) }),
  registrationConfig: () => request<RegistrationConfig>("/registration-config"),
  updateRegistrationConfig: (maxUsers: number, limitMessage: string, contactText: string) => request<RegistrationConfig>("/registration-config", { method: "POST", body: JSON.stringify({ maxUsers, limitMessage, contactText }) }),
  accessControl: () => request<AccessControl>("/access-control"),
  updateAccessControl: (payload: Pick<AccessControl, "mode" | "title" | "message" | "resumeAt">) => request<AccessControl>("/access-control", { method: "POST", body: JSON.stringify(payload) }),
  addAccessWhitelist: (email: string, note: string) => request<AccessControl>("/access-control/whitelist", { method: "POST", body: JSON.stringify({ email, note }) }),
  removeAccessWhitelist: (userId: string) => request<AccessControl>(`/access-control/whitelist/${encodeURIComponent(userId)}`, { method: "DELETE" }),
  operationLogs: () => request<Array<{ operation: string; detail: string; at: string }>>("/operation-logs"),
  operationErrors: (search = "", category = "", limit = 100) => request<OperationError[]>(`/operation-errors?search=${encodeURIComponent(search)}&category=${encodeURIComponent(category)}&limit=${limit}`),
};
