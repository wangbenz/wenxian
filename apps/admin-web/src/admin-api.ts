export type LoginResponse = { ok: boolean; token: string };

export type AnalyticsDay = { date: string; [metric: string]: string | number };
export type AnalyticsDateRange = { from: string; to: string; days: number };
export type AnalyticsOverview = {
  today: AnalyticsDay; totalUsers: number; totalSuccessfulTurns: number;
  trend: AnalyticsDay[]; alerts: string[]; calculatedAt: string; range: AnalyticsDateRange;
};
export type GrowthAnalytics = {
  daily: AnalyticsDay[];
  funnel: { registered: number; createdSave: number; firstTurn: number };
  retention: Array<{ cohortDate: string; day: number; registered: number; activated: number; retained: number; rate: number; origin: string }>;
  definition: string; range: AnalyticsDateRange;
};
export type GameplayAnalytics = {
  daily: AnalyticsDay[];
  totals: { turns: number; createdSaves: number; corrections: number; entityRemovals: number; archivedSaves: number };
  range: AnalyticsDateRange;
};
export type AiUsageAnalytics = {
  platform: AnalyticsDay[]; player: AnalyticsDay[];
  models: Array<{ provider: string; model: string; source: "PUBLIC" | "PLATFORM" | "USER" | "PLAYER"; requests: number; promptTokens: number; completionTokens: number; cacheHitTokens: number; cacheMissTokens: number; failures: number; averageLatency: number }>;
  range: AnalyticsDateRange;
};

export type DashboardData = {
  totalUsers: number; usersWithCharacter: number; todayActive: number; onlinePlayers: number;
  usersWithAnyCharacter: number; currentCharacters: number; historicalCharacters: number; totalCharacters: number;
  usersWithHistoricalCharacters: number; endedCharacters: number; deletedCharacters: number;
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
    requests: number; failures: number; players: number;
    platformTurns: number; customTurns: number; models: string;
  }>;
  modelUsage: Array<{
    provider: string; model: string; source: "PUBLIC" | "USER"; requests: number;
    promptTokens: number; completionTokens: number; cacheHitTokens: number; cacheMissTokens: number;
    failures: number; avgLatency: number; firstUsedAt: string; lastUsedAt: string;
  }>;
  versionMetrics: Array<{
    version: string; requests: number; tokens: number; failures: number; avgLatency: number;
    characters: number; turns: number; deleted: number;
  }>;
  recentTurns: Array<{ playerName: string; realm: string; turn: number; action: string; tokens: number; latency: number; model: string; at: string }>;
};

export type InviteCodeItem = {
  id: string; codeDisplay: string | null; plaintextAvailable: boolean;
  status: "ACTIVE" | "EXHAUSTED"; maxUses: number; usedCount: number;
  expiresAt: string | null; createdAt: string;
};

export type UserRow = {
  userId: string; email: string; rawEmail: string; registeredAt: string;
  lastLoginAt: string | null; saveId: string | null; playerName: string; realm: string;
  race: string; characterState: "NONE" | "CURRENT" | "RESTARTED";
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
  realm: string; turn: number; endReason: "ACTIVE" | "ENDED" | "ARCHIVED"; totalTokens: number;
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
export type ReleaseNoteAdmin = { id: string; version: string; title: string; content: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; announceAll: boolean; showOnLogin: boolean; publishedAt: string | null; createdAt: string; updatedAt: string; readCount: number };
export type AppVersion = { version: string };
export type GenerationProvider = {
  providerCode: string; displayName: string; baseUrl: string; adapterType: string;
  enabled: boolean; userKeyEnabled: boolean; keyConfigured: boolean; keySuffix: string;
  keyState: "CONFIGURED" | "MISSING" | "DECRYPT_ERROR"; configVersion: number;
  updatedAt: string; platformTaskCount: number;
  models: Array<{ modelCode: string; displayName: string; apiModelName: string;
    capabilityCodes: string[]; contextWindow: number; maximumOutputTokens: number; concurrencyLimit: number;
    platformEnabled: boolean; userSelectable: boolean; activeTaskCodes: string[];
    allPlatformTasks: boolean; configVersion: number;
    verificationStatus: "PASSED" | "FAILED" | "STALE" | "UNVERIFIED";
    verifiedCapabilityCodes: string[]; verificationLatencyMs: number | null;
    verificationErrorCode: string | null; verificationErrorSummary: string | null;
    verifiedAt: string | null;
    servicePlanReferences: Array<{ planId: string; versionNumber: number; status: "DRAFT" | "ACTIVE";
      tierCode: "LIGHT" | "BALANCED" | "DEEP"; taskCode: string | null;
      usage: "TIER_PRIMARY" | "TIER_FALLBACK" | "TASK_PRIMARY" | "TASK_FALLBACK" }>;
    updatedAt: string }>;
};
export type ModelValidationResult = {
  id: string; status: "PASSED" | "FAILED"; capabilityCodes: string; latencyMs: number | null;
  errorCode: string | null; errorSummary: string | null; checkedAt: string;
};
export type ServicePlanTier = {
  tierCode: "LIGHT" | "BALANCED" | "DEEP"; displayName: string; description: string;
  pointCost: number; enabled: boolean; primaryProviderCode: string | null; primaryModelCode: string | null;
  fallbackProviderCode: string | null; fallbackModelCode: string | null; fallbackEnabled: boolean;
};
export type ServicePlanTaskRoute = {
  tierCode: "LIGHT" | "BALANCED" | "DEEP"; taskCode: string;
  primaryProviderCode: string; primaryModelCode: string;
  fallbackProviderCode: string | null; fallbackModelCode: string | null;
  temperature: number; timeoutSeconds: number; maximumRetries: number;
  maximumOutputTokens: number | null; disableThinking: boolean; fallbackEnabled: boolean;
};
export type ServicePlan = {
  id: string; versionNumber: number; status: "DRAFT" | "ACTIVE" | "RETIRED"; revision: number;
  defaultTierCode: ServicePlanTier["tierCode"]; changeNote: string; basedOnVersionId: string | null;
  createdBy: string; createdAt: string; publishedBy: string | null; publishedAt: string | null;
  retiredAt: string | null; publishable: boolean; blockingReasons: string[]; gameRuntimeApplied: boolean;
  tiers: ServicePlanTier[]; taskRoutes: ServicePlanTaskRoute[];
  modelSnapshots: Array<{ providerCode: string; modelCode: string; providerConfigVersion: number;
    modelConfigVersion: number; validationRunId: string; capturedAt: string }>;
};
export type SaveServicePlanPayload = Pick<ServicePlan, "defaultTierCode" | "changeNote" | "tiers" | "taskRoutes"> & {
  expectedRevision: number;
};
export type GameplayConfig = { accountPublicTurnLimit: number; limitMessage: string; customApiEnabled: boolean; customApiUsers: number; invalidCredentialUsers: number; todayPublicInvocations: number; todayCustomTurns: number; maxActiveSaves: number; activeSaveCount: number; usersWithMultipleSaves: number };
export type PointRule = {
  openingGrant: number; depletedMessage: string; totalAccounts: number; initializedAccounts: number;
  awaitingInitializationAccounts: number; grantedPoints: number; availablePoints: number;
  reservedPoints: number; consumedPoints: number; compatibilityStorage: boolean;
};
export type ByokRule = {
  enabled: boolean; activeCredentialUsers: number; invalidCredentialUsers: number; todayCustomTurns: number;
};
export type ByokConversion = {
  cohortFrom: string; cohortTo: string; windowDays: number; immatureUsers: number; calculatedAt: string;
  stages: Array<{ code: string; label: string; users: number; rateFromPrevious: number; rateFromRegistered: number }>;
  trend: Array<{ date: string; registeredUsers: number; createdSaveUsers: number; firstTurnUsers: number; connectedUsers: number; byokTurnUsers: number }>;
  dataCoverage: { connectedEventsStartedAt: string | null; backfilledConnections: number; note: string };
};
export type ByokCredentialSnapshot = {
  credentialUsers: number; credentialCount: number; activeCredentials: number; invalidCredentials: number;
  selectedUsers: number; multiProviderUsers: number;
};
export type ByokProviderSummary = {
  providerCode: string; displayName: string; credentialUsers: number; credentialCount: number;
  activeCredentials: number; invalidCredentials: number; selectedUsers: number; usageUsers: number;
  successfulTurns: number; failedCalls: number; lastUsedAt: string | null;
};
export type ByokCredentialSummary = {
  snapshot: ByokCredentialSnapshot; providers: ByokProviderSummary[];
  usageFrom: string; usageTo: string; calculatedAt: string;
};
export type ByokCredentialRow = {
  userId: string; email: string; providerCode: string; providerName: string; maskedSuffix: string;
  status: string; selected: boolean; selectedModel: string | null; lastVerifiedAt: string | null;
  lastErrorCode: string | null; createdAt: string; updatedAt: string; lastUsedAt: string | null;
  periodSuccessfulTurns: number; periodFailedCalls: number;
};
export type ByokCredentialPage = {
  total: number; page: number; size: number; usageFrom: string; usageTo: string; rows: ByokCredentialRow[];
};
export type ActiveSaveRule = {
  maximumActiveSaves: number; activeSaveCount: number; usersWithMultipleSaves: number;
};
export type TurnCreditBalance = {
  granted: number; available: number; reserved: number; consumed: number; expired: number; revoked: number;
  nearestExpiry: string | null; ledgerInitialized: boolean;
};
export type TurnCreditAccount = TurnCreditBalance & {
  userId: string; email: string; status: string; lastLoginAt: string | null;
};
export type TurnCreditAccountPage = { total: number; page: number; size: number; rows: TurnCreditAccount[] };
export type TurnCreditGrant = {
  id: string; creditClass: "SPONSORED" | "PURCHASED"; grantType: string; campaignCode: string | null;
  sourceReference: string | null; granted: number; available: number; reserved: number; consumed: number;
  expired: number; revoked: number; status: "ACTIVE" | "EXHAUSTED" | "EXPIRED" | "REVOKED";
  expiresAt: string | null; reason: string; createdBy: string; createdAt: string; updatedAt: string;
};
export type TurnCreditLedger = {
  id: string; grantId: string; reservationId: string | null; eventType: string;
  availableDelta: number; reservedDelta: number; consumedDelta: number; balanceAfter: number;
  businessReference: string | null; operatorId: string; remark: string; createdAt: string;
};
export type TurnCreditDetail = {
  user: { userId: string; email: string; status: string; registeredAt: string; lastLoginAt: string | null };
  balance: TurnCreditBalance; grants: TurnCreditGrant[]; ledger: TurnCreditLedger[];
  grantsPage: { total: number; page: number; size: number; rows: TurnCreditGrant[] };
  ledgerPage: { total: number; page: number; size: number; rows: TurnCreditLedger[] };
  operation?: { grantId: string; count: number; balanceAfter: number; expiresAt: string | null; idempotent: boolean };
};
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
export type OperationErrorSummary = {
  category: string; code: string; highestStatus: number; count: number;
  affectedPlayers: number; latestAt: string; message: string;
};
export type AdminAuditLog = { id: string; operation: string; detail: string; at: string };
export type HistoryPage<T> = { total: number; page: number; size: number; rows: T[] };
export type HistoryQuery = {
  search?: string; type?: string; from?: string; to?: string; page?: number; size?: number;
};

const TOKEN_KEY = "wenxian_admin_token";
const SESSION_EXPIRED_EVENT = "wenxian:admin-session-expired";
const REQUEST_TIMEOUT_MS = 30_000;
const BASE = "/api/admin";
function token() { return window.sessionStorage.getItem(TOKEN_KEY); }
export function setToken(value: string) { window.sessionStorage.setItem(TOKEN_KEY, value); }
export function clearToken() { window.sessionStorage.removeItem(TOKEN_KEY); }
export function isAuthenticated() { return token() !== null; }

export function onAdminSessionExpired(listener: () => void) {
  window.addEventListener(SESSION_EXPIRED_EVENT, listener);
  return () => window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
}

export class AdminApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly requestId?: string,
  ) {
    super(message);
    this.name = "AdminApiError";
  }
}

type AnalyticsQuery = { days: number } | { from: string; to: string };
const analyticsQuery = (query: AnalyticsQuery) => new URLSearchParams(
  "days" in query ? { days: String(query.days) } : { from: query.from, to: query.to },
).toString();
const historyQuery = (query: Record<string, string | number | undefined>) => {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== "") parameters.set(key, String(value));
  });
  return parameters.toString();
};

/**
 * 所有管理接口统一经过这里处理超时、错误契约和会话失效。普通 5xx 只反馈给当前页面，
 * 只有服务端明确返回 401 时才清理全局会话，避免局部接口故障把管理员误踢下线。
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {};
  const timeoutController = new AbortController();
  const timeout = window.setTimeout(() => timeoutController.abort(), REQUEST_TIMEOUT_MS);
  const signal = init?.signal
    ? AbortSignal.any([init.signal, timeoutController.signal])
    : timeoutController.signal;
  if (init?.body) headers["content-type"] = "application/json";
  if (token()) headers.authorization = `Bearer ${token()}`;
  try {
    const response = await fetch(`${BASE}${path}`, { credentials: "include", ...init, headers: { ...headers, ...init?.headers }, signal });
    if (!response.ok) {
      let payload: { code?: string; message?: string; requestId?: string } = {};
      try { payload = await response.json() as typeof payload; } catch { /* 非 JSON 错误仍使用稳定兜底信息。 */ }
      if (response.status === 401 && token()) {
        clearToken();
        window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
      }
      throw new AdminApiError(
        payload.message || `请求失败（${response.status}）`,
        response.status,
        payload.code || `HTTP_${response.status}`,
        payload.requestId,
      );
    }
    if (response.status === 204 || response.headers.get("content-length") === "0") return undefined as T;
    return response.json() as Promise<T>;
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError" && timeoutController.signal.aborted) {
      throw new AdminApiError("请求超时，请稍后重试。", 0, "REQUEST_TIMEOUT");
    }
    throw reason;
  } finally {
    window.clearTimeout(timeout);
  }
}

export const adminApi = {
  login: (username: string, password: string) => request<LoginResponse>("/login", { method: "POST", body: JSON.stringify({ username, password }) }),
  changePassword: (oldPassword: string, newPassword: string) => request<{ ok: boolean; message: string }>("/change-password", { method: "POST", body: JSON.stringify({ oldPassword, newPassword }) }),
  adminAccounts: () => request<AdminAccount[]>("/admin-accounts"),
  createAdminAccount: (username: string, password: string) => request<AdminAccount>("/admin-accounts", { method: "POST", body: JSON.stringify({ username, password }) }),
  updateAdminAccountStatus: (adminId: string, active: boolean) => request<{ ok: boolean }>(`/admin-accounts/${encodeURIComponent(adminId)}/status`, { method: "POST", body: JSON.stringify({ active }) }),
  resetAdminAccountPassword: (adminId: string) => request<{ temporaryPassword: string }>(`/admin-accounts/${encodeURIComponent(adminId)}/temporary-password`, { method: "POST" }),
  dashboard: () => request<DashboardData>("/dashboard"),
  analyticsOverview: (query: AnalyticsQuery = { days: 14 }) => request<AnalyticsOverview>(`/analytics/overview?${analyticsQuery(query)}`),
  analyticsGrowth: (query: AnalyticsQuery = { days: 30 }) => request<GrowthAnalytics>(`/analytics/growth?${analyticsQuery(query)}`),
  analyticsGameplay: (query: AnalyticsQuery = { days: 30 }) => request<GameplayAnalytics>(`/analytics/gameplay?${analyticsQuery(query)}`),
  analyticsAiUsage: (query: AnalyticsQuery = { days: 14 }) => request<AiUsageAnalytics>(`/analytics/ai-usage?${analyticsQuery(query)}`),
  refreshAnalytics: () => request<{ ok: boolean; message: string }>("/analytics/refresh", { method: "POST" }),
  inviteCodes: (status = "") => request<InviteCodeItem[]>(`/invite-codes?status=${encodeURIComponent(status)}`),
  generateInviteCodes: (count: number, maxUses: number) => request<{ codes: string[]; count: number }>("/invite-codes", { method: "POST", body: JSON.stringify({ count, maxUses }) }),
  users: (search = "", characterState = "", realm = "", registeredDate = "", page = 0, size = 20) => request<UserListResult>(`/users?search=${encodeURIComponent(search)}&characterState=${characterState}&realm=${encodeURIComponent(realm)}&registeredDate=${encodeURIComponent(registeredDate)}&page=${page}&size=${size}`),
  turnRanking: (limit = 10) => request<TurnRankingItem[]>(`/users/turn-ranking?limit=${limit}`),
  userDetail: (userId: string) => request<UserDetail>(`/users/${encodeURIComponent(userId)}`),
  resetPlayerPassword: (userId: string) => request<{ temporaryPassword: string }>(`/users/${encodeURIComponent(userId)}/temporary-password`, { method: "POST" }),
  unlockPlayerAuth: (userId: string) => request<{ ok: boolean; message: string }>(`/users/${encodeURIComponent(userId)}/unlock-auth`, { method: "POST" }),
  turnCreditAccounts: (search = "", page = 0, size = 20) => request<TurnCreditAccountPage>(`/inference-points?search=${encodeURIComponent(search)}&page=${page}&size=${size}`),
  turnCreditDetail: (userId: string, query: { grantPage?: number; grantSize?: number; grantStatus?: string; ledgerPage?: number; ledgerSize?: number; eventType?: string; from?: string; to?: string } = {}) => {
    const parameters = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => value !== undefined && value !== "" && parameters.set(key, String(value)));
    const suffix = parameters.size ? `?${parameters}` : "";
    return request<TurnCreditDetail>(`/inference-points/${encodeURIComponent(userId)}${suffix}`);
  },
  grantTurnCredits: (userId: string, payload: { operationId: string; grantType: string; count: number; campaignCode: string; sourceReference: string; expiresAt: string; reason: string }) => request<TurnCreditDetail>(`/inference-points/${encodeURIComponent(userId)}/grants`, { method: "POST", body: JSON.stringify(payload) }),
  revokeTurnCredits: (userId: string, grantId: string, reason: string) => request<TurnCreditDetail>(`/inference-points/${encodeURIComponent(userId)}/grants/${encodeURIComponent(grantId)}/revoke`, { method: "POST", body: JSON.stringify({ reason }) }),
  userTurns: (userId: string, saveId: string, page = 0, size = 20, turn = "") => request<TurnPage>(`/users/${encodeURIComponent(userId)}/lives/${encodeURIComponent(saveId)}/turns?page=${page}&size=${size}${turn ? `&turn=${encodeURIComponent(turn)}` : ""}`),
  executions: (saveId: string, turn?: number) => request<AgentExecution[]>(`/agent-executions/${encodeURIComponent(saveId)}${turn === undefined ? "" : `?turn=${turn}`}`),
  releaseNotes: () => request<ReleaseNoteAdmin[]>("/release-notes"),
  appVersion: () => request<AppVersion>("/app-version"),
  updateAppVersion: (version: string) => request<AppVersion>("/app-version", { method: "POST", body: JSON.stringify({ version }) }),
  saveReleaseNote: (note: Partial<ReleaseNoteAdmin>) => request<ReleaseNoteAdmin>("/release-notes", { method: "POST", body: JSON.stringify(note) }),
  clearLoginAnnouncement: () => request<{ ok: boolean; updated: number; message: string }>("/release-notes/login-announcement", { method: "DELETE" }),
  generationProviders: () => request<GenerationProvider[]>("/generation-providers"),
  updateGenerationProvider: (providerCode: string, payload: { displayName: string; baseUrl: string; enabled: boolean; userKeyEnabled: boolean; apiKey?: string }) => request<{ ok: boolean }>(`/generation-providers/${encodeURIComponent(providerCode)}`, { method: "POST", body: JSON.stringify(payload) }),
  updateGenerationModel: (providerCode: string, modelCode: string, payload: { displayName: string; apiModelName: string; capabilityCodes: string[]; contextWindow: number; maximumOutputTokens: number; concurrencyLimit: number; platformEnabled: boolean; userSelectable: boolean; applyToAllTasks?: boolean }) => request<{ ok: boolean }>(`/generation-providers/${encodeURIComponent(providerCode)}/models/${encodeURIComponent(modelCode)}`, { method: "POST", body: JSON.stringify(payload) }),
  validateGenerationModel: (providerCode: string, modelCode: string) => request<ModelValidationResult>(`/generation-providers/${encodeURIComponent(providerCode)}/models/${encodeURIComponent(modelCode)}/validation`, { method: "POST" }),
  servicePlans: () => request<ServicePlan[]>("/service-plans"),
  createServicePlanDraft: (basedOnVersionId?: string | null) => request<ServicePlan>("/service-plans/drafts", { method: "POST", body: JSON.stringify({ basedOnVersionId: basedOnVersionId || null }) }),
  saveServicePlanDraft: (planId: string, payload: SaveServicePlanPayload) => request<ServicePlan>(`/service-plans/${encodeURIComponent(planId)}`, { method: "PUT", body: JSON.stringify(payload) }),
  publishServicePlan: (planId: string, expectedRevision: number) => request<ServicePlan>(`/service-plans/${encodeURIComponent(planId)}/publish`, { method: "POST", body: JSON.stringify({ expectedRevision }) }),
  cloneServicePlan: (planId: string) => request<ServicePlan>(`/service-plans/${encodeURIComponent(planId)}/clone`, { method: "POST" }),
  gameplayConfig: () => request<GameplayConfig>("/gameplay-config"),
  updateGameplayConfig: (accountPublicTurnLimit: number, customApiEnabled: boolean, limitMessage: string, maxActiveSaves: number) => request<GameplayConfig>("/gameplay-config", { method: "POST", body: JSON.stringify({ accountPublicTurnLimit, customApiEnabled, limitMessage, maxActiveSaves }) }),
  pointRules: () => request<PointRule>("/point-rules"),
  updatePointRules: (openingGrant: number, depletedMessage: string) => request<PointRule>("/point-rules", { method: "PUT", body: JSON.stringify({ openingGrant, depletedMessage }) }),
  byokRules: () => request<ByokRule>("/byok-rules"),
  updateByokRules: (enabled: boolean) => request<ByokRule>("/byok-rules", { method: "PUT", body: JSON.stringify({ enabled }) }),
  byokConversion: (from: string, to: string, windowDays: number) => request<ByokConversion>(`/byok/conversion?cohortFrom=${encodeURIComponent(from)}&cohortTo=${encodeURIComponent(to)}&windowDays=${windowDays}`),
  byokCredentialSummary: (from: string, to: string) => request<ByokCredentialSummary>(`/byok/credentials/summary?usageFrom=${encodeURIComponent(from)}&usageTo=${encodeURIComponent(to)}`),
  byokCredentials: (query: { search?: string; provider?: string; status?: string; selected?: boolean; page?: number; size?: number; usageFrom: string; usageTo: string }) => {
    const parameters = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => value !== undefined && value !== "" && parameters.set(key, String(value)));
    return request<ByokCredentialPage>(`/byok/credentials?${parameters}`);
  },
  activeSaveRules: () => request<ActiveSaveRule>("/active-save-rules"),
  updateActiveSaveRules: (maximumActiveSaves: number) => request<ActiveSaveRule>("/active-save-rules", { method: "PUT", body: JSON.stringify({ maximumActiveSaves }) }),
  registrationConfig: () => request<RegistrationConfig>("/registration-config"),
  updateRegistrationConfig: (maxUsers: number, limitMessage: string, contactText: string) => request<RegistrationConfig>("/registration-config", { method: "POST", body: JSON.stringify({ maxUsers, limitMessage, contactText }) }),
  accessControl: () => request<AccessControl>("/access-control"),
  updateAccessControl: (payload: Pick<AccessControl, "mode" | "title" | "message" | "resumeAt">) => request<AccessControl>("/access-control", { method: "POST", body: JSON.stringify(payload) }),
  addAccessWhitelist: (email: string, note: string) => request<AccessControl>("/access-control/whitelist", { method: "POST", body: JSON.stringify({ email, note }) }),
  removeAccessWhitelist: (userId: string) => request<AccessControl>(`/access-control/whitelist/${encodeURIComponent(userId)}`, { method: "DELETE" }),
  operationLogs: () => request<Array<{ operation: string; detail: string; at: string }>>("/operation-logs"),
  operationErrors: (search = "", category = "", limit = 100) => request<OperationError[]>(`/operation-errors?search=${encodeURIComponent(search)}&category=${encodeURIComponent(category)}&limit=${limit}`),
  operationErrorSummary: (days = 7, limit = 12) => request<OperationErrorSummary[]>(`/operation-errors/summary?days=${days}&limit=${limit}`),
  problemHistory: (query: HistoryQuery = {}) => request<HistoryPage<OperationError>>(`/history/problems?${historyQuery({
    search: query.search, category: query.type, from: query.from, to: query.to,
    page: query.page ?? 0, size: query.size ?? 20,
  })}`),
  auditHistory: (query: HistoryQuery = {}) => request<HistoryPage<AdminAuditLog>>(`/history/audit-logs?${historyQuery({
    search: query.search, operation: query.type, from: query.from, to: query.to,
    page: query.page ?? 0, size: query.size ?? 20,
  })}`),
  auditOperations: () => request<string[]>("/history/audit-logs/operations"),
};
