import type { PlayerStoryView } from "../app/player-read-model";

export type RuntimeCreation = { opening_id: "qingmu-trial"; name: string; goal: string; gender: "未设定" | "男" | "女"; origin: string; story_preference: string; world_setting_hash?: string };
export type RuntimeSummary = { id: string; name: string; realm: string; location: string; revision: number; updated_at: string };
export type CreationPath = { title: string; origin: string; goal: string };
export type SuggestionKind = "names" | "paths";
export type SuggestionRequest = {
  request_id: string; kind: SuggestionKind; name: string; gender: "未设定" | "男" | "女";
  goal: string; origin: string; story_preference: string; exclude_names: string[]; world_setting_hash: string | null;
};
export type SuggestionStatus = {
  request_id: string; kind: SuggestionKind; status: "PROCESSING" | "COMPLETED" | "FAILED";
  result: { names?: string[]; paths?: CreationPath[] } | null; error: { code: string; message: string } | null;
};
export type ReferenceCategory = "realms" | "arts" | "items" | "place-types" | "facility-types" | "recipes";
export type ReferenceOverview = { world: { title: string; scope: string; summary: string; sections: { title: string; text: string }[] }; categories: { id: ReferenceCategory; name: string }[] };
export type ReferenceEntry = { key: string; name: string; description: string; kind: string; example: boolean };
export type ReferencePage = { count: number; offset: number; limit: number; results: ReferenceEntry[] };
export type ReferenceDetail = ReferenceEntry & { revision: number; scope: "save" | "public"; sections: { title: string; text: string }[]; sources: { title: string; locator: string; supports: string; url: string }[] };

export type RuntimeHistory = { turn: number; action: string; mode: string; result: { scene_title: string; narrative: string[] } };
export type RuntimeSnapshot = {
  save_id: string;
  revision: number;
  mode: "fixture" | "disabled" | "langchain";
  history: RuntimeHistory[];
  active_action?: PendingAction | null;
  state: {
    name: string; goal: string; realm: string; condition: string; location: string;
    gender?: string; origin?: string; story_preference?: string;
    scene_title: string; narrative: string[]; choices: { id: string; label: string; hint: string }[];
    time: PlayerStoryView["worldTime"];
    items: { id: string; name: string; category: string; status: string; quantity: number; description: string }[];
    item_uses?: Record<string, { item_id: string; name: string; status: "active" | "ended" }>;
    abilities: { id: string; name: string; kind: "TALENT" | "TECHNIQUE" | "SPELL"; stage: string; source: string; description: string }[];
    characters: PlayerStoryView["characters"];
  };
};
export type PendingAction = { request_id: string; expected_revision: number; action: string };
export type ActionStatus = {
  status: "UNKNOWN" | "PROCESSING" | "COMMITTED" | "FAILED";
  error?: { code: string; message: string } | null;
};
export class RuntimeError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

const BASE = "/api/runtime/v1";
export async function runtimeFetch<T>(path: string, token: string | null, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const result = await response.json().catch(() => null);
    throw new RuntimeError(response.status, result?.error?.code ?? "REQUEST_FAILED", result?.error?.message ?? "本次操作未能完成，请稍后重试。");
  }
  return response.json();
}

/** 账号模式只使用 HttpOnly Cookie；浏览器仅持久化命卷编号和草稿，不保存账号令牌。 */
export async function accountRequest<T>(path: string, body?: unknown, method?: "GET" | "POST" | "PUT" | "DELETE"): Promise<T> {
  const response = await fetch(path, {
    credentials: "include", method: method ?? (body === undefined ? "GET" : "POST"),
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const result = await response.json().catch(() => null);
    if (response.status === 401 && (path.startsWith(`${BASE}/account/`) || path.startsWith("/api/v1/generation/") || path.startsWith("/api/v1/account/") || path === "/api/v1/auth/password")) window.dispatchEvent(new Event("wenxian:runtime:account-expired"));
    throw new RuntimeError(response.status, result?.code ?? result?.error?.code ?? "REQUEST_FAILED", result?.message ?? result?.error?.message ?? "本次操作未能完成，请稍后重试。");
  }
  return response.status === 204 ? undefined as T : response.json();
}

export function accountRuntimeFetch<T>(path: string, saveId: string | null, body?: unknown): Promise<T> {
  if (path === "/creation") return accountRequest<T>(`${BASE}/creation`);
  if (!saveId) throw new RuntimeError(400, "SAVE_REQUIRED", "请先选择命卷。");
  const suffix = path === "/save" ? "" : path;
  return accountRequest<T>(`${BASE}/account/saves/${encodeURIComponent(saveId)}${suffix}`, body);
}

export function toRuntimeView(snapshot: RuntimeSnapshot, history = snapshot.history): PlayerStoryView {
  const s = snapshot.state;
  const uses = Object.values(s.item_uses ?? {});
  return {
    phase: "playing", saveId: snapshot.save_id, turn: snapshot.revision, sceneTitle: s.scene_title,
    currentLocation: s.location, worldTime: s.time, narrative: s.narrative, choices: s.choices, pendingDecision: null,
    world: { code: "qingxi-test", title: "青溪镇", summary: "隔离试玩的青木坊场景", cultivationLaw: "以本局确认的经历和状态为准。", openingRegion: "青溪镇" },
    player: { name: s.name, race: "HUMAN", gender: s.gender ?? "未设定", identity: "初到青溪镇的修士", origin: s.origin || "未填写", lifeGoal: s.goal, storyPreference: s.story_preference || "未填写", realm: s.realm, condition: s.condition },
    inventory: s.items.filter(i => i.quantity > 0).map(i => {
      const active = [...new Set(uses.filter(u => u.item_id === i.id && u.status === "active").map(u => u.name))];
      return { ...i, status: active.length ? `${i.status} · ${active.join("、")}生效中` : i.status, quantityKnown: true };
    }),
    abilities: s.abilities.map(a => ({ ...a, type: a.kind })), characters: s.characters,
    events: [...history].reverse().filter(h => h.turn > 0).map(h => ({ id: `turn-${h.turn}`, title: h.result.scene_title, detail: `你的行动：${h.action}\n${h.result.narrative.join("\n")}`, at: s.time, kind: "decision" })),
  };
}
