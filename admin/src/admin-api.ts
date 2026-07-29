export type LoginResponse = { ok: boolean; token: string };

export type DashboardData = {
  totalUsers: number; usersWithCharacter: number; todayActive: number; onlinePlayers: number;
  totalTurns: number; todayTurns: number; totalTokens: number; todayTokens: number;
  failedToday: number; avgLatency: number; currentModel: string;
  dailyTokens: Array<{ date: string; promptTokens: number; completionTokens: number; turns: number; players: number }>;
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
  race: string; difficulty: "NORMAL" | "HARD"; characterState: "NONE" | "CURRENT" | "RESTARTED";
  archiveCount: number; turnCount: number; totalTokens: number; saveUpdated: string | null;
};
export type UserListResult = [{ total: number; page: number; size: number }, { rows: UserRow[] }];

export type LifeSummary = {
  status: "CURRENT" | "ARCHIVED"; saveId: string; at: string; playerName: string;
  realm: string; race: string; difficulty: "NORMAL" | "HARD"; phase: string; turn: number; sceneTitle: string;
};
export type UserDetail = {
  user: { userId: string; email: string; status: string; registeredAt: string; lastLoginAt: string | null };
  currentLives: LifeSummary[]; historicalLives: LifeSummary[];
};
export type TurnPage = { total: number; page: number; size: number; rows: Array<{
  turn: number; action: string; promptTokens: number; completionTokens: number; totalTokens: number;
  latency: number; model: string; responseId: string; stateDelta: string; status: string; at: string;
}> };
export type TurnRow = TurnPage["rows"][number];
export type AgentExecution = { turn: number; attempt: number; status: string; detail: string; model: string; responseId: string; latency: number; at: string };
export type ReleaseNoteAdmin = { id: string; version: string; title: string; content: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; announceAll: boolean; showOnLogin: boolean; publishedAt: string | null; createdAt: string; updatedAt: string; readCount: number };
export type ModelConfig = { modelCode: string; displayName: string; keyConfigured: boolean; active: boolean; updatedAt: string };

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
  dashboard: () => request<DashboardData>("/dashboard"),
  inviteCodes: (status = "") => request<InviteCodeItem[]>(`/invite-codes?status=${encodeURIComponent(status)}`),
  generateInviteCodes: (count: number, maxUses: number) => request<{ codes: string[]; count: number }>("/invite-codes", { method: "POST", body: JSON.stringify({ count, maxUses }) }),
  users: (search = "", characterState = "", difficulty = "", realm = "", page = 0, size = 20) => request<UserListResult>(`/users?search=${encodeURIComponent(search)}&characterState=${characterState}&difficulty=${difficulty}&realm=${encodeURIComponent(realm)}&page=${page}&size=${size}`),
  userDetail: (userId: string) => request<UserDetail>(`/users/${encodeURIComponent(userId)}`),
  userTurns: (userId: string, saveId: string, page = 0, size = 20, turn = "") => request<TurnPage>(`/users/${encodeURIComponent(userId)}/lives/${encodeURIComponent(saveId)}/turns?page=${page}&size=${size}${turn ? `&turn=${encodeURIComponent(turn)}` : ""}`),
  executions: (saveId: string, turn?: number) => request<AgentExecution[]>(`/agent-executions/${encodeURIComponent(saveId)}${turn === undefined ? "" : `?turn=${turn}`}`),
  releaseNotes: () => request<ReleaseNoteAdmin[]>("/release-notes"),
  saveReleaseNote: (note: Partial<ReleaseNoteAdmin>) => request<ReleaseNoteAdmin>("/release-notes", { method: "POST", body: JSON.stringify(note) }),
  modelConfigs: () => request<ModelConfig[]>("/model-configs"),
  updateModelConfig: (modelCode: string, active: boolean, apiKey = "") => request<{ ok: boolean }>(`/model-configs/${encodeURIComponent(modelCode)}`, { method: "POST", body: JSON.stringify({ active, apiKey }) }),
  operationLogs: () => request<Array<{ operation: string; detail: string; at: string }>>("/operation-logs"),
};
