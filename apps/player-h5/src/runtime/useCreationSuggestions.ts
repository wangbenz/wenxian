import { useEffect, useRef, useState } from "react";
import type { LocalCharacterDraft } from "../features/onboarding/CharacterOnboardingViews";
import type { UiProblem } from "../shared/problem";
import { accountRequest, RuntimeError, type CreationPath, type SuggestionKind, type SuggestionRequest, type SuggestionStatus } from "./runtime-api";

type Suggestions = { names: string[]; paths: CreationPath[]; pending: SuggestionRequest | null; pathsForSetting?: string | null };
const empty = (): Suggestions => ({ names: [], paths: [], pending: null });
const BASE = "/api/runtime/v1/account/creation/suggestions";
const context = (draft: Omit<LocalCharacterDraft, "step">) => JSON.stringify([draft.name, draft.gender, draft.goal, draft.origin, draft.story_preference]);
function read(key: string): Suggestions {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "null");
    if (value && Array.isArray(value.names) && value.names.every((name: unknown) => typeof name === "string")
      && Array.isArray(value.paths) && value.paths.every((path: CreationPath) => path && typeof path.title === "string" && typeof path.origin === "string" && typeof path.goal === "string")
      && (!value.pending || typeof value.pending.request_id === "string")) return value;
  } catch { /* 损坏候选缓存不影响手填草稿、命卷或历史。 */ }
  return empty();
}
function notice(message: string): UiProblem {
  return { category: "SYSTEM", title: "候选暂未准备好", message, suggestions: [], retryable: true, turnConsumed: false, stateChanged: false };
}

/** 候选缓存按账号隔离；只在点选时修改草稿，恢复原编号，不用迟到响应覆盖新的输入。 */
export function useCreationSuggestions(key: string, draft: LocalCharacterDraft, settingHash: string | undefined, active: boolean, onSettingChanged?: () => void) {
  const [data, setData] = useState<Suggestions>(() => read(key));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiProblem | null>(null);
  const current = useRef(data), latest = useRef({ draft, settingHash }), alive = useRef(false), lock = useRef(false);
  latest.current = { draft, settingHash };
  function store(value: Suggestions) {
    localStorage.setItem(key, JSON.stringify(value)); current.current = value; setData(value);
  }
  async function resolve(body: SuggestionRequest, admit: boolean, retryMissing = false) {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      if (admit) await accountRequest<SuggestionStatus>(BASE, body);
      const deadline = Date.now() + 90_000;
      while (alive.current && current.current.pending?.request_id === body.request_id && Date.now() < deadline) {
        let status: SuggestionStatus;
        try { status = await accountRequest<SuggestionStatus>(`${BASE}/${encodeURIComponent(body.request_id)}`); }
        catch (failure) {
          if (retryMissing && failure instanceof RuntimeError && failure.status === 404) {
            retryMissing = false; admit = true;
            // 只有玩家明确继续且原编号尚不存在时才补发同号请求，后端幂等保护一次调用。
            await accountRequest<SuggestionStatus>(BASE, body); continue;
          }
          throw failure;
        }
        if (!alive.current || current.current.pending?.request_id !== body.request_id) return;
        if (status.status === "FAILED") {
          store({ ...current.current, pending: null }); setError(notice(status.error?.message ?? "生成未完成，填写内容已保留。")); return;
        }
        if (status.status === "COMPLETED") {
          if (context(body) !== context(latest.current.draft) || (body.kind === "paths" && latest.current.settingHash && body.world_setting_hash !== latest.current.settingHash)) {
            store({ ...current.current, pending: null }); setError(notice("资料已修改，旧候选未采用；可以按当前内容重新生成。")); return;
          }
          store({ ...current.current, pending: null,
            ...(body.kind === "names" ? { names: status.result?.names ?? [] } : { paths: status.result?.paths ?? [], pathsForSetting: body.world_setting_hash }) });
          return;
        }
        await new Promise(resolve => window.setTimeout(resolve, 700));
      }
      if (alive.current && current.current.pending) setError(notice("候选仍在准备，可以稍后继续查看；不会重新提交生成。"));
    } catch (failure) {
      if (!alive.current) return;
      if (failure instanceof RuntimeError && admit && ([400, 401, 409, 422].includes(failure.status)
        || ["MODEL_NOT_CONFIGURED", "GENERATION_CONFIG_UNAVAILABLE", "WORLD_SETTING_UNAVAILABLE"].includes(failure.code))) store({ ...current.current, pending: null });
      if (failure instanceof RuntimeError && failure.code === "SETTING_CHANGED") onSettingChanged?.();
      setError(notice(failure instanceof RuntimeError ? failure.message : "连接暂时中断，填写内容已保留，请继续查看原候选。"));
    } finally { lock.current = false; if (alive.current) setBusy(false); }
  }
  function generate(kind: SuggestionKind) {
    if (lock.current || current.current.pending) return;
    const { step: _step, ...profile } = latest.current.draft;
    const body: SuggestionRequest = { ...profile, request_id: crypto.randomUUID(), kind,
      exclude_names: kind === "names" ? [...new Set([profile.name, ...current.current.names])].filter(name => name.trim().length >= 2 && name.length <= 6).slice(-16) : [],
      world_setting_hash: kind === "paths" ? latest.current.settingHash ?? null : null };
    try { store({ ...current.current, pending: body }); void resolve(body, true); }
    catch { setError(notice("浏览器暂时无法保存候选请求，填写内容已保留。")); }
  }
  function resume(retryMissing = true) { if (current.current.pending) void resolve(current.current.pending, false, retryMissing); }
  function invalidate(identityChanged: boolean) {
    store({ ...current.current, paths: [], ...(identityChanged ? { names: [] } : {}) }); setError(null);
  }
  function reset() { store(empty()); setError(null); }
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { if (active && current.current.pending) resume(false); }, [active]);
  return { names: data.names, paths: data.pathsForSetting && data.pathsForSetting !== settingHash ? [] : data.paths, busy, kind: data.pending?.kind ?? null,
    pending: Boolean(data.pending), error, generate, resume, invalidate, reset };
}
