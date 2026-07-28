export type LoginResponse = {
  ok: boolean;
  token: string;
};

export type DashboardData = {
  totalUsers: number;
  onlineUsers: number;
  totalSaves: number;
  totalTurns: number;
  todayTurns: number;
  totalTokens: number;
  avgLatency: number;
  recentTurns: Array<{
    playerName: string;
    realm: string;
    turn: number;
    action: string;
    tokens: number;
    latency: number;
    model: string;
    at: string;
  }>;
};

export type InviteCodeItem = {
  id: string;
  codeDisplay: string | null;
  plaintextAvailable: boolean;
  status: "ACTIVE" | "EXHAUSTED";
  maxUses: number;
  usedCount: number;
  expiresAt: string | null;
  createdAt: string;
};

export type UserListResult = [
  { total: number; page: number; size: number },
  {
    rows: Array<{
      userId: string;
      email: string;
      rawEmail: string;
      registeredAt: string;
      lastLoginAt: string | null;
      saveId: string | null;
      playerName: string;
      realm: string;
      race: string;
      turnCount: number;
      totalTokens: number;
      saveUpdated: string | null;
    }>;
  }
];

export type UserDetail = {
  user: {
    userId: string;
    email: string;
    status: string;
    registeredAt: string;
    lastLoginAt: string | null;
  };
  turns: Array<{
    turn: number;
    action: string;
    promptTokens: number;
    completionTokens: number;
    latency: number;
    model: string;
    responseId: string;
    at: string;
  }>;
  executions: Array<{
    turn: number;
    attempt: number;
    status: string;
    detail: string;
    model: string;
    responseId: string;
    latency: number;
    at: string;
  }>;
};

const TOKEN_KEY = "wenxian_admin_token";
const BASE = "/api/admin";

function token(): string | null {
  return window.sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(t: string) {
  window.sessionStorage.setItem(TOKEN_KEY, t);
}

export function clearToken() {
  window.sessionStorage.removeItem(TOKEN_KEY);
}

export function isAuthenticated(): boolean {
  return token() !== null;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const tk = token();
  const headers: Record<string, string> = {};
  if (init?.body) headers["content-type"] = "application/json";
  if (tk) headers["authorization"] = `Bearer ${tk}`;

  const res = await fetch(`${BASE}${path}`, { credentials: "include", ...init, headers: { ...headers, ...init?.headers } });

  if (!res.ok) {
    let message = `请求失败（${res.status}）`;
    try {
      const body = await res.json() as { message?: string };
      message = body.message || message;
    } catch { /* not json */ }
    throw new Error(message);
  }

  if (res.status === 204 || res.headers.get("content-length") === "0") return undefined as T;
  return res.json() as Promise<T>;
}

export const adminApi = {
  login: (username: string, password: string) =>
    request<LoginResponse>("/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  changePassword: (oldPassword: string, newPassword: string) =>
    request<{ ok: boolean; message: string }>("/change-password", {
      method: "POST",
      body: JSON.stringify({ oldPassword, newPassword }),
    }),
  dashboard: () => request<DashboardData>("/dashboard"),
  inviteCodes: (status = "") =>
    request<InviteCodeItem[]>(`/invite-codes?status=${encodeURIComponent(status)}`),
  generateInviteCodes: (count: number, maxUses: number) =>
    request<{ codes: string[]; count: number }>("/invite-codes", {
      method: "POST",
      body: JSON.stringify({ count, maxUses }),
    }),
  users: (search = "", page = 0, size = 20) =>
    request<UserListResult>(
      `/users?search=${encodeURIComponent(search)}&page=${page}&size=${size}`
    ),
  userDetail: (userId: string) => request<UserDetail>(`/users/${encodeURIComponent(userId)}`),
};
