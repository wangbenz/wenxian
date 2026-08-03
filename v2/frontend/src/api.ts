import type {
  AuthResponse,
  AiSettings,
  Bootstrap,
  CreateSavePayload,
  OriginProposal,
  KnownLocation,
  LegacyChapter,
  LegacyLife,
  ReleaseNotice,
  SaveAggregate,
  TurnOperation,
  TurnSummary,
  UiProblem,
} from "./types";

export class ApiProblem extends Error {
  readonly problem: UiProblem;
  readonly status: number;

  constructor(status: number, problem: UiProblem) {
    super(problem.message);
    this.status = status;
    this.problem = problem;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: "include",
    ...init,
    headers: init?.body ? { "content-type": "application/json", ...init.headers } : init?.headers,
  });
  if (!response.ok) {
    let problem: UiProblem = {
      code: `HTTP_${response.status}`,
      category: "TECHNICAL",
      message: "命卷暂时无法响应，已有状态没有改变。",
      suggestions: ["稍后重试"],
    };
    try {
      const body = (await response.json()) as Partial<UiProblem>;
      problem = {
        code: body.code ?? problem.code,
        category: body.category ?? problem.category,
        message: body.message ?? problem.message,
        suggestions: Array.isArray(body.suggestions) ? body.suggestions : problem.suggestions,
        problemReference: body.problemReference,
      };
    } catch {
      // Non-JSON responses keep the player-safe fallback above.
    }
    throw new ApiProblem(response.status, problem);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  me: () => request<AuthResponse>("/api/v2/auth/me"),
  login: (email: string, password: string) => request<AuthResponse>("/api/v2/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  }),
  register: (email: string, password: string) => request<AuthResponse>("/api/v2/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  }),
  logout: () => request<void>("/api/v2/auth/logout", { method: "POST" }),
  changePassword: (currentPassword: string, newPassword: string) => request<AuthResponse>("/api/v2/auth/password", {
    method: "PUT",
    body: JSON.stringify({ currentPassword, newPassword }),
  }),
  bootstrap: () => request<Bootstrap>("/api/v2/bootstrap"),
  saves: () => request<SaveAggregate[]>("/api/v2/saves"),
  save: (saveId: string) => request<SaveAggregate>(`/api/v2/saves/${encodeURIComponent(saveId)}`),
  createSave: (payload: CreateSavePayload) => request<SaveAggregate>("/api/v2/saves", {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  generateFates: (payload: Omit<CreateSavePayload, "starterTalentDefinitionId" | "originProposalId" | "fateCandidateId">) =>
    request<OriginProposal>("/api/v2/creation/fates", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  world: (saveId: string) => request<KnownLocation[]>(`/api/v2/saves/${encodeURIComponent(saveId)}/world`),
  chronicle: (saveId: string, limit = 60, beforeTurn?: number) => {
    const cursor = beforeTurn ? `&beforeTurn=${beforeTurn}` : "";
    return request<TurnSummary[]>(
      `/api/v2/saves/${encodeURIComponent(saveId)}/chronicle?limit=${limit}${cursor}`,
    );
  },
  submitAction: (saveId: string, payload: {
    requestId: string;
    expectedStateVersion: number;
    actionType: string;
    actionText: string;
    durationMinutes?: number;
    targetLocationId?: string;
  }) => request<TurnOperation>(`/api/v2/saves/${encodeURIComponent(saveId)}/actions`, {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  operation: (operationId: string) => request<TurnOperation>(
    `/api/v2/turn-operations/${encodeURIComponent(operationId)}`,
  ),
  operationEventsUrl: (operationId: string) =>
    `/api/v2/turn-operations/${encodeURIComponent(operationId)}/events`,
  activeOperation: () => request<{ operation: TurnOperation | null }>("/api/v2/turn-operations/active"),
  archiveSave: (saveId: string, characterName: string) => request<SaveAggregate>(
    `/api/v2/saves/${encodeURIComponent(saveId)}/archive`, {
      method: "POST",
      body: JSON.stringify({ characterName }),
    },
  ),
  endLife: (saveId: string, characterName: string, endingType: "RETIREMENT" | "LEGACY") =>
    request<SaveAggregate>(`/api/v2/saves/${encodeURIComponent(saveId)}/end`, {
      method: "POST",
      body: JSON.stringify({ characterName, endingType }),
    }),
  aiSettings: () => request<AiSettings>("/api/v2/ai/preference"),
  chooseAiPreference: (credentialSource: "PLATFORM" | "USER", modelCode: string) =>
    request<AiSettings>("/api/v2/ai/preference", {
      method: "PUT",
      body: JSON.stringify({ credentialSource, modelCode }),
    }),
  saveAiCredential: (apiKey: string, modelCode: string) =>
    request<AiSettings>("/api/v2/ai/credential", {
      method: "PUT",
      body: JSON.stringify({ apiKey, modelCode }),
    }),
  deleteAiCredential: () => request<AiSettings>("/api/v2/ai/credential", { method: "DELETE" }),
  legacyArchives: () => request<LegacyLife[]>("/api/v2/legacy-archives"),
  legacyChapters: (archiveId: string, limit = 30) => request<LegacyChapter[]>(
    `/api/v2/legacy-archives/${encodeURIComponent(archiveId)}/chapters?limit=${limit}`,
  ),
  publicNotice: () => request<{ notice: ReleaseNotice | null }>("/api/v2/notices/public"),
  pendingNotice: () => request<{ notice: ReleaseNotice | null }>("/api/v2/notices/pending"),
  markNoticeRead: (noticeId: string) => request<void>(
    `/api/v2/notices/${encodeURIComponent(noticeId)}/read`, { method: "POST" },
  ),
};

export function toProblem(error: unknown): UiProblem {
  if (error instanceof ApiProblem) return error.problem;
  return {
    code: "CLIENT_REQUEST_FAILED",
    category: "TECHNICAL",
    message: error instanceof Error ? error.message : "命卷暂时无法响应。",
    suggestions: ["检查网络后重新尝试"],
  };
}
