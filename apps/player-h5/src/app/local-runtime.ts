import { ApiError } from "../game-api";
import type { PlayerStorySnapshot } from "./player-read-model";

export type PendingStoryTurn = {
  saveId: string;
  requestId: string;
  action: string;
  choiceId?: string;
  expectedRevision: number;
  accountEmail: string;
  createdAt: number;
};

const CACHE_KEY = "wenxian-readonly-cache-v2";
const PENDING_STORY_TURN_KEY = "wenxian-pending-story-turn-v1";
const STORY_TURN_RECOVERY_PREFIX = "wenxian-story-turn-recovery-v1:";

/** 本地只保存待确认请求或失败草稿；成功与否始终以服务端为准。 */
export type StoryTurnRecovery = {
  pending: PendingStoryTurn;
  status: "PENDING" | "FAILED";
  message?: string;
  draft?: string;
};

export const THEME_KEY = "wenxian-theme";
export const FONT_SIZE_KEY = "wenxian-font-size";
export const STORY_TURN_RECOVERY_LIMIT_MS = 8 * 60_000;

export function cacheSnapshot(snapshot: PlayerStorySnapshot) {
  window.localStorage.setItem(CACHE_KEY, JSON.stringify(snapshot));
}

export function clearCachedSnapshot() {
  window.localStorage.removeItem(CACHE_KEY);
}

export function readCachedSnapshot(): PlayerStorySnapshot | null {
  try {
    const value = window.localStorage.getItem(CACHE_KEY);
    if (!value) return null;
    const parsed = JSON.parse(value) as Partial<PlayerStorySnapshot>;
    // 本地缓存只是可丢弃的只读快照，缺少当前读模型字段时直接忽略。
    return parsed.story && Number.isFinite(parsed.revision) ? parsed as PlayerStorySnapshot : null;
  } catch {
    return null;
  }
}

function recoveryKey(accountEmail: string, saveId: string) {
  return `${STORY_TURN_RECOVERY_PREFIX}${encodeURIComponent(accountEmail)}:${encodeURIComponent(saveId)}`;
}

export function isStoryTurnRecoveryStorageKey(key: string | null, accountEmail: string, saveId: string) {
  // 旧全局键只在进入命卷时按归属迁移，其他命卷迁移它的事件不能清空当前草稿。
  return key === recoveryKey(accountEmail, saveId);
}

/** 状态响应只能更新它所属的原请求，不能覆盖另一标签页新建的请求。 */
export function updateStoryTurnRecovery(record: StoryTurnRecovery, editDraft = false): boolean {
  const current = readStoryTurnRecovery(record.pending.accountEmail, record.pending.saveId);
  if (current?.pending.requestId !== record.pending.requestId) return false;
  // 终态不能被迟到的查询降级，也不能把玩家已经编辑的失败草稿还原成旧文字。
  if (current.status === "FAILED" && (record.status !== "FAILED" || !editDraft)) return false;
  writeStoryTurnRecovery(record);
  return true;
}

function validPending(value: Partial<PendingStoryTurn> | null | undefined): value is PendingStoryTurn {
  return Boolean(value && typeof value.saveId === "string" && value.saveId
    && typeof value.requestId === "string" && value.requestId
    && typeof value.action === "string" && value.action
    && Number.isInteger(value.expectedRevision) && Number(value.expectedRevision) >= 0
    && typeof value.accountEmail === "string" && value.accountEmail
    && Number.isFinite(value.createdAt)
    && (value.choiceId === undefined || typeof value.choiceId === "string"));
}

/** 按账号和命卷读取；只迁移属于当前范围的旧单条记录，不删除其他账号或命卷的行动。 */
export function readStoryTurnRecovery(accountEmail: string, saveId: string): StoryTurnRecovery | null {
  const matches = (pending: Partial<PendingStoryTurn> | undefined): pending is PendingStoryTurn =>
    validPending(pending) && pending.accountEmail === accountEmail && pending.saveId === saveId;
  try {
    const raw = window.localStorage.getItem(recoveryKey(accountEmail, saveId));
    if (raw) {
      const value = JSON.parse(raw) as Partial<StoryTurnRecovery> | null;
      return value && matches(value.pending) && ["PENDING", "FAILED"].includes(value.status ?? "")
        && (value.draft === undefined || typeof value.draft === "string")
        && (value.message === undefined || typeof value.message === "string") ? value as StoryTurnRecovery : null;
    }
    const legacy = JSON.parse(window.localStorage.getItem(PENDING_STORY_TURN_KEY) ?? "null") as PendingStoryTurn | null;
    if (!legacy || !matches(legacy)) return null;
    const record: StoryTurnRecovery = { pending: legacy, status: "PENDING" };
    // 迁移写失败仍返回旧记录，不能因浏览器存储异常丢失可见的原行动。
    try {
      writeStoryTurnRecovery(record);
      window.localStorage.removeItem(PENDING_STORY_TURN_KEY);
    } catch { /* 原记录仍可读取。 */ }
    return record;
  } catch {
    return null;
  }
}

/** 必须在发送前成功保存；存储失败时由调用方阻止本次发送。 */
export function writeStoryTurnRecovery(record: StoryTurnRecovery) {
  window.localStorage.setItem(recoveryKey(record.pending.accountEmail, record.pending.saveId), JSON.stringify(record));
}

/** 只清理同一个请求，旧响应不能删除另一份刚提交的记录。 */
export function clearStoryTurnRecovery(pending: PendingStoryTurn) {
  const current = readStoryTurnRecovery(pending.accountEmail, pending.saveId);
  if (current?.pending.requestId === pending.requestId) {
    window.localStorage.removeItem(recoveryKey(pending.accountEmail, pending.saveId));
    const legacy = window.localStorage.getItem(PENDING_STORY_TURN_KEY);
    if (legacy) {
      try {
        const value = JSON.parse(legacy) as PendingStoryTurn;
        if (value.accountEmail === pending.accountEmail && value.saveId === pending.saveId
            && value.requestId === pending.requestId) window.localStorage.removeItem(PENDING_STORY_TURN_KEY);
      } catch { /* 损坏的旧缓存不影响已提交回合。 */ }
    }
  }
}

export function newStoryTurnRequestId() {
  return typeof window.crypto?.randomUUID === "function"
    ? window.crypto.randomUUID()
    : `turn-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function delay(ms: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, ms));
}

export function recoverableStoryTurnFailure(error: unknown) {
  return !(error instanceof ApiError) || [500, 502, 503, 504].includes(error.status);
}

export function revealFormControl(target: HTMLElement, delayMs = 280) {
  window.setTimeout(() => {
    if (!target.isConnected || typeof target.scrollIntoView !== "function") return;
    target.scrollIntoView({ block: "center", behavior: "smooth" });
  }, delayMs);
}
