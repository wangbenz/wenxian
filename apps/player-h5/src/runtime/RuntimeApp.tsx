import { useEffect, useRef, useState, type CSSProperties } from "react";
import { ArrowLeftIcon } from "@radix-ui/react-icons";
import { LoadingView, TabIcon, type TabId } from "../app/AppViews";
import { AdventureView, CharacterView, ChronicleView } from "../features/gameplay/GameViews";
import { ActionComposer } from "../features/gameplay/ActionComposer";
import { GenerationSettingsDialog, PasswordDialog, SettingsView, type FontSizeMode } from "../features/settings/SettingsViews";
import { SaveSelectionView } from "../features/saves/SaveViews";
import { LocalCharacterCreationView, type LocalCharacterDraft, type LocalOpeningPreview } from "../features/onboarding/CharacterOnboardingViews";
import type { PlayerSaveSummary } from "../app/player-read-model";
import { ProblemNotice, type UiProblem } from "../shared/problem";
import { accountRequest, accountRuntimeFetch, runtimeFetch, RuntimeError, toRuntimeView, type ActionStatus, type PendingAction, type RuntimeCreation, type RuntimeHistory, type RuntimeSnapshot, type RuntimeSummary } from "./runtime-api";
import { WorldReferenceView } from "./WorldReferenceView";
import type { StoryGenerationSettings } from "../game-api";
import type { RuntimeAccount } from "./AccountRuntimeApp";
import "./runtime.css";
import { useCreationSuggestions } from "./useCreationSuggestions";

type Stage = "saves" | "create" | "game";
type BoundOpeningPreview = LocalOpeningPreview & { setting_hash: string; setting_summary?: string };
type CreationRecord = { draft: LocalCharacterDraft; pending: { token: string; body: RuntimeCreation } | null };
function readCreationAt(key: string): CreationRecord {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "null");
    if (value?.draft && ["identity", "path", "review"].includes(value.draft.step)) return value;
  } catch { /* 独立创角缓存损坏不影响已有命卷。 */ }
  return { draft: { name: "", gender: "未设定", goal: "", origin: "", story_preference: "", step: "identity" }, pending: null };
}
type LocalSession = { token: string; saveId: string; draft: string; pending: PendingAction | null };
function readLocalAt(key: string): LocalSession | null {
  try {
    const data = JSON.parse(localStorage.getItem(key) ?? "null");
    return typeof data?.token === "string" && data?.saveId ? data : null;
  } catch { return null; }
}
function readSessionsAt(key: string): Record<string, LocalSession> {
  try {
    const data = JSON.parse(localStorage.getItem(key) ?? "{}");
    if (!data || typeof data !== "object" || Array.isArray(data)) return {};
    return Object.fromEntries(Object.entries(data).filter(([, value]) => value && typeof value === "object" && "token" in value && "saveId" in value)) as Record<string, LocalSession>;
  } catch { return {}; }
}
function notice(message: string, pending = false): UiProblem {
  return { category: "SYSTEM", title: pending ? "原行动结果待确认" : "这一回暂未完成", message, suggestions: [], retryable: true, turnConsumed: false, stateChanged: false, statePending: pending };
}

/** 原 H5 的局部适配入口；连接 Python 试玩或账号命卷；草稿按账号隔离，不读取 Java 存档缓存。 */
export default function RuntimeApp({ account }: { account?: RuntimeAccount }) {
  const namespace = account ? `wenxian:runtime:account:${account.userId}` : "wenxian:runtime:local-test";
  const STORAGE = `${namespace}:session`, SAVED_SESSIONS = `${namespace}:sessions`;
  const READING_SIZE = `${namespace}:font-size`, STAGE = `${namespace}:stage`, CREATION = `${namespace}:creation`;
  const readCreation = () => readCreationAt(CREATION);
  const readLocal = () => readLocalAt(STORAGE);
  const readSessions = () => readSessionsAt(SAVED_SESSIONS);
  function availableSessions() {
    const saved = readSessions(); const current = readLocal();
    if (current) saved[current.saveId] = current;
    return saved;
  }
  function requestRuntime<T>(path: string, target: LocalSession | null, body?: unknown): Promise<T> {
    return account ? accountRuntimeFetch<T>(path, target?.saveId ?? null, body) : runtimeFetch<T>(path, target?.token ?? null, body);
  }
  const [maximumSaves, setMaximumSaves] = useState(0);
  const [generation, setGeneration] = useState<StoryGenerationSettings | null>(null);
  const [generationOpen, setGenerationOpen] = useState(false);
  const [generationBusy, setGenerationBusy] = useState(false);
  const [generationError, setGenerationError] = useState<UiProblem | null>(null);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [accountBusy, setAccountBusy] = useState(false);
  const [accountError, setAccountError] = useState<UiProblem | null>(null);
  const [stage, setStage] = useState<Stage>(() => {
    const value = localStorage.getItem(STAGE);
    return value === "create" || value === "saves" || value === "game" ? value : readLocal() ? "game" : "saves";
  });
  const [saves, setSaves] = useState<PlayerSaveSummary[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [unavailableSaves, setUnavailableSaves] = useState<string[]>([]);
  const [listError, setListError] = useState<UiProblem | null>(null);
  const [creation, setCreation] = useState<CreationRecord>(readCreation);
  const [preview, setPreview] = useState<BoundOpeningPreview | null>(null);
  const suggestions = useCreationSuggestions(`${CREATION}:suggestions`, creation.draft, preview?.setting_hash, stage === "create" && Boolean(account), () => void loadPreview(false));
  const [creating, setCreating] = useState(false);
  const [creationError, setCreationError] = useState<UiProblem | null>(null);
  const [snapshot, setSnapshot] = useState<RuntimeSnapshot | null>(null);
  const [history, setHistory] = useState<RuntimeHistory[]>([]);
  const [referenceOpen, setReferenceOpen] = useState(() => new URL(window.location.href).searchParams.has("reference"));
  const [tab, setTab] = useState<TabId>(() => new URL(window.location.href).searchParams.has("reference") ? "settings" : "adventure");
  const [draft, setDraft] = useState(() => readLocal()?.draft ?? "");
  const [openComposer, setOpenComposer] = useState(false);
  const [focused, setFocused] = useState(false);
  const [fontSize, setFontSize] = useState<FontSizeMode>(() => {
    const value = localStorage.getItem(READING_SIZE);
    return value === "compact" || value === "large" ? value : "standard";
  });
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [status, setStatus] = useState<ActionStatus["status"] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiProblem | null>(null);
  const [bootFailed, setBootFailed] = useState(false);
  const session = useRef<LocalSession | null>(readLocal());
  const alive = useRef(false);
  const querying = useRef(false);
  const submitLock = useRef(false);
  const bootLock = useRef(false);
  const createLock = useRef(false);
  const listEpoch = useRef(0);
  const pendingRef = useRef<PendingAction | null>(null);

  function persist() {
    if (session.current) {
      localStorage.setItem(STORAGE, JSON.stringify(session.current));
      const saved = readSessions(); saved[session.current.saveId] = session.current;
      localStorage.setItem(SAVED_SESSIONS, JSON.stringify(saved));
    }
  }
  function closeReference() {
    setReferenceOpen(false);
    const url = new URL(window.location.href);
    for (const key of ["reference", "refQuery", "refOffset", "refEntry"]) url.searchParams.delete(key);
    window.history.replaceState(null, "", url);
  }
  function moveTo(next: Stage) { if (next !== "game") closeReference(); localStorage.setItem(STAGE, next); setStage(next); }
  function updateCreation(patch: Partial<LocalCharacterDraft>, keepSuggestions = false) {
    if (creation.pending) return;
    const next = { ...creation, draft: { ...creation.draft, ...patch } };
    try {
      localStorage.setItem(CREATION, JSON.stringify(next)); setCreation(next);
      // 调整故事偏好时保留可选来历；尚未返回的候选仍由完整草稿校验阻止过期结果。
      if (!keepSuggestions && Object.keys(patch).some(key => key !== "step" && key !== "story_preference")) suggestions.invalidate(patch.gender !== undefined);
    }
    catch { setCreationError(notice("浏览器暂时无法保存创角草稿，请检查本机存储后重试。")); }
  }
  async function loadPreview(clearError = true) {
    setPreview(null);
    try {
      const result = await requestRuntime<BoundOpeningPreview>("/creation", null);
      if (!/^[a-f0-9]{64}$/.test(result.setting_hash ?? "")) throw new Error("设定尚未确认");
      if (alive.current) {
        setPreview(result);
        if (clearError) setCreationError(null);
      }
    }
    catch { if (alive.current) setCreationError(notice("暂时无法读取试玩开篇，填写内容已经保留。")); }
  }
  async function loadSaves() {
    const epoch = ++listEpoch.current;
    setListLoading(true); setListError(null);
    if (account) {
      const [rows, config] = await Promise.allSettled([
        accountRequest<RuntimeSummary[]>("/api/runtime/v1/account/saves"),
        accountRequest<{ maximum_saves: number }>("/api/runtime/v1/account/config"),
      ]);
      if (!alive.current || epoch !== listEpoch.current) return;
      if (rows.status === "fulfilled") {
        const stored = availableSessions();
        for (const row of rows.value) stored[row.id] ??= { token: "", saveId: row.id, draft: "", pending: null };
        localStorage.setItem(SAVED_SESSIONS, JSON.stringify(stored));
        setSaves(rows.value.map((row, index) => ({ saveId: row.id, slotNo: index + 1, playerName: row.name, race: "HUMAN", realm: row.realm, currentLocation: row.location, turn: row.revision, updatedAt: row.updated_at })));
        setUnavailableSaves([]);
      } else setListError(notice(rows.reason instanceof RuntimeError ? rows.reason.message : "命卷列表暂时无法读取，草稿已保留。"));
      if (config.status === "fulfilled") setMaximumSaves(config.value.maximum_saves);
        else { setMaximumSaves(0); setListError(notice("命卷规则暂时无法读取，已有命卷仍可进入。")); }
      setListLoading(false); return;
    }
    const entries = Object.values(availableSessions());
    setUnavailableSaves(entries.map(saved => saved.saveId));
    setSaves(entries.map((saved, index) => ({ saveId: saved.saveId, slotNo: index + 1, playerName: `第${index + 1}卷（暂未读取）`, race: "", realm: "", currentLocation: "正在读取命卷", turn: 0, updatedAt: "" })));
    const results = await Promise.allSettled(entries.map(async (saved, index) => {
      try {
        const value = await runtimeFetch<RuntimeSummary>("/save/summary", saved.token);
        if (!alive.current || epoch !== listEpoch.current) return;
        setSaves(current => current.map(row => row.saveId === saved.saveId ? { saveId: saved.saveId, slotNo: index + 1, playerName: value.name, race: "HUMAN", realm: value.realm, currentLocation: value.location, turn: value.revision, updatedAt: value.updated_at } : row));
        setUnavailableSaves(current => current.filter(id => id !== saved.saveId));
      } catch (failure) {
        if (alive.current && epoch === listEpoch.current) setSaves(current => current.map(row => row.saveId === saved.saveId ? { ...row, currentLocation: "暂时无法读取" } : row));
        throw failure;
      }
    }));
    if (!alive.current || epoch !== listEpoch.current) return;
    if (results.some(result => result.status === "rejected")) setListError(notice("部分命卷暂时无法读取，记录和草稿已保留；其他命卷仍可进入。"));
    setListLoading(false);
  }
  function showSaves() { if (submitLock.current || bootLock.current || createLock.current) return; persist(); setFocused(false); moveTo("saves"); void loadSaves(); }
  function showCreation() { moveTo("create"); void loadPreview(); }
  async function createCharacter() {
    if (createLock.current || (!preview && !creation.pending)) return;
    createLock.current = true; setCreating(true); setCreationError(null);
    try {
      const { step: _step, ...profile } = creation.draft;
      const request = creation.pending ?? { token: crypto.randomUUID().replaceAll("-", "") + crypto.randomUUID().replaceAll("-", ""), body: { ...profile, opening_id: preview!.opening_id, world_setting_hash: preview!.setting_hash } };
      const remembered = { ...creation, pending: request };
      // 先持久化稳定凭据与资料，再发送；丢失响应、刷新或重试不会新开另一卷。
      localStorage.setItem(CREATION, JSON.stringify(remembered)); setCreation(remembered);
      const result = account
        ? await accountRequest<{ snapshot: RuntimeSnapshot }>("/api/runtime/v1/account/saves", { request_id: request.token, character: request.body })
        : await runtimeFetch<{ snapshot: RuntimeSnapshot }>("/sessions", request.token, request.body);
      if (!alive.current) return;
      persist(); session.current = { token: account ? "" : request.token, saveId: result.snapshot.save_id, draft: "", pending: result.snapshot.active_action ?? null }; persist();
      pendingRef.current = null; setPending(null); setStatus(null); setBusy(false); setError(null); setDraft(""); setOpenComposer(false); setTab("adventure");
      acceptSnapshot(result.snapshot); moveTo("game");
      localStorage.removeItem(CREATION); setCreation(readCreation());
      suggestions.reset();
      if (session.current.pending) { rememberPending(session.current.pending); setStatus("PROCESSING"); await checkOriginal(session.current.pending); }
    } catch (failure) {
      if (alive.current) {
        // 明确的输入/凭据拒绝未落卷，允许修正；网络错误仍保留原凭据与确认资料。
        const settingChanged = failure instanceof RuntimeError && ["SETTING_CHANGED", "SETTING_CONFIRMATION_REQUIRED"].includes(failure.code);
        if (failure instanceof RuntimeError && ([400, 401, 422].includes(failure.status) || settingChanged)) {
          const editable = { draft: creation.draft, pending: null };
          localStorage.setItem(CREATION, JSON.stringify(editable)); setCreation(editable);
        }
        setCreationError(notice(failure instanceof RuntimeError ? failure.message : "暂时无法确认开卷结果。填写内容已保留，请找回已确认命卷。"));
        if (settingChanged) await loadPreview(false);
      }
    }
    finally { createLock.current = false; setCreating(false); }
  }
  function updateDraft(value: string) {
    setDraft(value);
    if (session.current) { session.current.draft = value; persist(); }
  }
  function acceptSnapshot(value: RuntimeSnapshot) {
    setSnapshot(value); setHistory(value.history);
  }
  function rememberPending(value: PendingAction | null) {
    pendingRef.current = value; setPending(value);
    if (session.current) { session.current.pending = value; persist(); }
  }
  async function checkOriginal(action: PendingAction) {
    if (querying.current || !session.current) return;
    const target = session.current;
    const stillCurrent = () => alive.current && session.current?.saveId === target.saveId && pendingRef.current?.request_id === action.request_id;
    querying.current = true;
    try {
      const result = await requestRuntime<ActionStatus>(`/actions/${action.request_id}`, target);
      if (!stillCurrent()) return;
      setStatus(result.status);
      if (result.status === "COMMITTED") {
        // 读取最新投影，避免另一个标签已继续时回退到旧请求快照。
        const latest = await requestRuntime<RuntimeSnapshot>("/save", target);
        if (!stillCurrent()) return;
        acceptSnapshot(latest); updateDraft(""); rememberPending(null); setBusy(false); setStatus(null); setError(null);
      } else if (result.status === "FAILED") {
        setBusy(false); setError(notice(result.error?.message ?? "原行动没有完成，输入已保留，可原样重试。"));
        updateDraft(action.action); setOpenComposer(true);
      } else if (result.status === "UNKNOWN") {
        setBusy(false); setError(notice("暂未找到原请求记录，可继续查询，或使用同一编号重试原行动。", true));
      } else { setBusy(true); setError(null); }
    } catch {
      if (stillCurrent()) { setBusy(false); setStatus("UNKNOWN"); setError(notice("暂时无法确认结果。原行动已保留，请先查询或重试原行动。", true)); }
    } finally { querying.current = false; }
  }
  async function boot() {
    if (bootLock.current || !session.current) return;
    const target = session.current;
    const stillCurrent = () => alive.current && session.current?.saveId === target.saveId;
    bootLock.current = true; setBootFailed(false); setError(null);
    try {
      const value = await requestRuntime<RuntimeSnapshot>("/save", target);
      if (!stillCurrent()) return;
      acceptSnapshot(value);
      if (value.active_action) { session.current!.pending = value.active_action; persist(); }
      if (session.current!.pending) {
        rememberPending(session.current.pending); setStatus("PROCESSING"); setBusy(true);
        await checkOriginal(session.current.pending);
      }
    } catch (failure) {
      if (stillCurrent()) { setBootFailed(true); setError(notice(failure instanceof RuntimeError ? failure.message : "本地试玩暂时无法连接，已有草稿已保留。")); }
    } finally { bootLock.current = false; }
  }
  function enterSession(next: LocalSession) {
    closeReference();
    if (bootLock.current || submitLock.current) return;
    persist();
    session.current = next;
    pendingRef.current = null; setPending(null); setStatus(null); setBusy(false);
    setSnapshot(null); setHistory([]); setDraft(next.draft ?? ""); setOpenComposer(false); setTab("adventure");
    persist(); moveTo("game"); void boot();
  }
  useEffect(() => {
    alive.current = true;
    if (stage === "game" && session.current) void boot();
    else if (stage === "create") void loadPreview();
    else { moveTo("saves"); void loadSaves(); }
    return () => { alive.current = false; };
  }, []);
  useEffect(() => {
    localStorage.setItem(READING_SIZE, fontSize);
  }, [fontSize]);
  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => setKeyboardOffset(Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop));
    viewport.addEventListener("resize", update); viewport.addEventListener("scroll", update);
    return () => { viewport.removeEventListener("resize", update); viewport.removeEventListener("scroll", update); };
  }, []);
  useEffect(() => {
    if (status !== "PROCESSING" || !pending) return;
    const timer = window.setInterval(() => void checkOriginal(pending), 900);
    return () => window.clearInterval(timer);
  }, [status, pending]);

  async function submit(action: string, original?: PendingAction) {
    if (!snapshot || !session.current || submitLock.current || (!original && pendingRef.current && status !== "FAILED")) return;
    submitLock.current = true;
    const old = pendingRef.current;
    const request = original ?? (old?.action === action && old.expected_revision === snapshot.revision ? old : { request_id: crypto.randomUUID(), expected_revision: snapshot.revision, action });
    rememberPending(request); updateDraft(action); setBusy(true); setStatus("PROCESSING"); setError(null); setFocused(false);
    try {
      await requestRuntime<ActionStatus>("/actions", session.current, request);
      await checkOriginal(request);
    } catch (failure) {
      // 响应丢失不表示生成失败；先查原请求，不能换请求号重发。
      await checkOriginal(request);
      if (failure instanceof RuntimeError && ["REVISION_CONFLICT", "ACTION_IN_PROGRESS", "REQUEST_CONFLICT", "TURN_LIMIT", "MODEL_NOT_CONFIGURED", "MODEL_UNAVAILABLE", "GENERATION_CONFIG_UNAVAILABLE", "CREDENTIAL_REQUIRED", "CREDENTIAL_CHANGED"].includes(failure.code)) {
        const current = await requestRuntime<RuntimeSnapshot>("/save", session.current).catch(() => null);
        if (current) acceptSnapshot(current);
        if (current?.active_action) {
          rememberPending(current.active_action); setStatus("PROCESSING"); setBusy(true);
          await checkOriginal(current.active_action);
          return;
        }
        // 本请求被明确拒绝，才允许编辑新行动；别人的处理中请求仍由服务端保护。
        if (pendingRef.current?.request_id === request.request_id) { rememberPending(null); setStatus(null); setBusy(false); setError(notice(failure.message)); setOpenComposer(true); }
      }
    } finally { submitLock.current = false; }
  }
  async function earlierHistory() {
    if (!session.current || !history.length) return;
    const target = session.current;
    try {
      const older = await requestRuntime<RuntimeHistory[]>(`/history?before_turn=${history[0].turn}&limit=20`, target);
      if (!alive.current || session.current?.saveId !== target.saveId) return;
      setHistory(current => [...older.filter(h => !current.some(c => c.turn === h.turn)), ...current]);
    } catch { if (session.current?.saveId === target.saveId) setError(notice("更早的经历暂时无法读取，当前命卷没有变化。")); }
  }

  async function loadGeneration() {
    if (!account) return;
    try {
      const value = await accountRequest<StoryGenerationSettings>("/api/v1/generation/settings");
      if (alive.current) { setGeneration(value); setGenerationError(null); }
    } catch (failure) { if (alive.current) setGenerationError(notice(failure instanceof RuntimeError ? failure.message : "续写设置暂时无法读取。")); }
  }
  useEffect(() => { if (account && tab === "settings" && !referenceOpen) void loadGeneration(); }, [account?.userId, tab, referenceOpen]);
  async function changeGeneration(path: string, method: "PUT" | "DELETE", body?: unknown) {
    if (!account || generationBusy) return;
    setGenerationBusy(true); setGenerationError(null);
    try {
      const value = await accountRequest<StoryGenerationSettings>(`/api/v1/generation/${path}`, body, method);
      if (alive.current) { setGeneration(value); setGenerationOpen(false); }
    } catch (failure) { if (alive.current) setGenerationError(notice(failure instanceof RuntimeError ? failure.message : "续写设置未能保存，请重试。")); }
    finally { if (alive.current) setGenerationBusy(false); }
  }
  function openGeneration() { setGenerationOpen(true); void loadGeneration(); }

  async function logout() {
    if (!account || accountBusy) return;
    setAccountBusy(true); setAccountError(null); persist();
    try { await account.onLogout(); }
    catch (failure) { if (alive.current) setAccountError(notice(failure instanceof RuntimeError ? failure.message : "退出未能确认，请重试。")); }
    finally { if (alive.current) setAccountBusy(false); }
  }
  async function savePassword(currentPassword: string, newPassword: string) {
    if (!account || accountBusy) return;
    setAccountBusy(true); setAccountError(null);
    try { await account.onPassword(currentPassword, newPassword); if (alive.current) setPasswordOpen(false); }
    catch (failure) { if (alive.current) setAccountError(notice(failure instanceof RuntimeError ? failure.message : "密码修改未能确认，请重试。")); }
    finally { if (alive.current) setAccountBusy(false); }
  }

  const locked = Boolean(pending && status !== "FAILED");
  const game = stage === "game" && snapshot ? toRuntimeView(snapshot, history) : null;
  return <div className="game-shell" data-stage={stage} data-creator={stage === "create" ? "manuscript" : undefined} data-tab={tab} data-theme="paper" data-entry={!game} data-font-size={fontSize} data-headerless={Boolean(game)} data-keyboard-open={keyboardOffset > 80} data-composer-focused={focused} style={{ "--keyboard-offset": `${keyboardOffset}px` } as CSSProperties}>
    <div className="paper-background" aria-hidden="true" />
    {!game && <header className="game-header"><div className="game-header-inner"><div className="app-header-leading">{stage === "create" ? <><button aria-label="返回命卷列表" className="header-back-button" disabled={creating} onClick={showSaves} type="button"><ArrowLeftIcon aria-hidden="true" /><span>命卷</span></button><span className="app-context-title">新命卷</span></> : <span aria-label="问仙录" className="game-wordmark" role="img"><img alt="" aria-hidden="true" src="/assets/game/wenxian-wordmark-brush.png" /></span>}</div></div></header>}
    <div className="game-scroll" data-tab={tab} data-phase={game?.phase ?? "loading"} key={`${tab}-${referenceOpen ? "reference" : game?.turn ?? 0}`}><main className="screen-content" data-testid="game-content">
      {snapshot && snapshot.mode !== "langchain" && <p className="runtime-mode-note" role="note">{snapshot.mode === "fixture" ? "本地模拟联调 · 回应仅用于验证保存与恢复" : "本地试玩 · 尚未启用真实续写"}</p>}
      {stage === "saves" && <><SaveSelectionView saves={saves} currentSaveId={session.current?.saveId} maxActiveSaves={account ? Math.max(maximumSaves, saves.length) : Math.max(4, saves.length + 1)} isBusy={false} unavailableSaveIds={unavailableSaves} onBack={session.current ? () => enterSession(session.current!) : undefined} onOpen={save => { const saved = availableSessions()[save.saveId]; if (saved) enterSession(saved); }} onCreate={showCreation} onDelete={() => {}} onLogout={() => void logout()} localTrial accountTrial={Boolean(account)} />{listLoading && <LoadingView />}{listError && <><ProblemNotice problem={listError} /><button className="creation-back-button" onClick={() => void loadSaves()} type="button">重新读取命卷</button></>}</>}
      {stage === "create" && <LocalCharacterCreationView draft={creation.draft} preview={preview} isBusy={creating} confirmed={Boolean(creation.pending)} error={creationError} onDraft={updateCreation} onCreate={() => void createCharacter()} onRetry={() => void loadPreview()} suggestions={account ? suggestions : undefined} onChoosePath={path => updateCreation({ origin: path.origin, goal: path.goal }, true)} />}
      {stage === "game" && !game && !bootFailed && <LoadingView />}
      {stage === "game" && !game && bootFailed && <section className="entry-card"><ProblemNotice problem={error!} /><button onClick={() => void boot()} type="button">重新连接</button><button onClick={showSaves} type="button">返回命卷列表</button></section>}
      {game && tab === "adventure" && <AdventureView game={game} choices={game.choices} pendingAction={pending ? { kind: "TEXT", label: pending.action } : null} customActionActive={openComposer} isBusy={busy} offline={false} error={error} actionLocked={locked}
        onChoiceMode={() => setOpenComposer(false)} onChoice={id => { const choice = game.choices.find(c => c.id === id); if (choice) void submit(`${choice.label}：${choice.hint}`); }}
        recoveryPanel={pending && !busy ? <section className="problem-notice story-problem-notice" aria-label="行动恢复"><p>原行动：{pending.action}</p><div className="turn-recovery-actions"><button onClick={() => void checkOriginal(pending)} type="button">继续查询</button>{status !== "PROCESSING" && <button onClick={() => void submit(pending.action, pending)} type="button">重试原行动</button>}</div></section> : null}
        actionPanel={<ActionComposer open={openComposer} draft={draft} locked={locked} maxLength={2000} placeholder="写下你准备做的事，也可以修改建议行动。" onToggle={() => setOpenComposer(current => !current)} onDraft={updateDraft} onFocusChange={setFocused} onSubmit={action => void submit(action)} />} />}
      {game && tab === "character" && <CharacterView game={game} isBusy={busy} />}
      {game && tab === "chronicle" && <><ChronicleView game={game} />{history[0]?.turn > 0 && <button className="chronicle-history-toggle" onClick={() => void earlierHistory()} type="button">读取更早经历</button>}{error && <ProblemNotice problem={error} />}</>}
      {game && tab === "settings" && referenceOpen && session.current && <WorldReferenceView key={session.current.saveId} token={session.current.token} accountSaveId={account ? session.current.saveId : undefined} onBack={closeReference} />}
      {game && tab === "settings" && !referenceOpen && <SettingsView onReference={() => setReferenceOpen(true)} fontSize={fontSize} onFontSize={setFontSize} generation={generation} email={account?.email ?? ""} passwordConfigured={Boolean(account)} version="本地试玩" isBusy={busy} onPlatform={() => void changeGeneration("settings", "PUT", { mode: "PLATFORM" })} onGenerationSettings={openGeneration} onPasswordSettings={() => { setAccountError(null); setPasswordOpen(true); }} onSaves={showSaves} onReset={() => {}} onLogout={() => void logout()} onDeleteAccount={() => {}} localTrial={{ accountEnabled: Boolean(account), generationEnabled: Boolean(account && snapshot?.mode === "langchain"), settingsControl: account ? <button onClick={openGeneration} type="button">续写设置</button> : undefined, modeLabel: snapshot?.mode === "langchain" ? "真实续写" : snapshot?.mode === "fixture" ? "模拟回应 · 仅验证保存与恢复" : "尚未启用续写", saveControls: <button onClick={showSaves} type="button">我的命卷</button> }} />}
      {generationError && (!generationOpen || !generation) && tab === "settings" && <ProblemNotice problem={generationError} />}
      {accountError && !passwordOpen && <ProblemNotice problem={accountError} />}
    </main></div>
    {generationOpen && generation && account && <GenerationSettingsDialog settings={generation} error={generationError} isBusy={generationBusy} verificationDeferred onCancel={() => setGenerationOpen(false)} onUsePlatform={tierCode => void changeGeneration("settings", "PUT", { mode: "PLATFORM", tierCode })} onSave={(provider, model, apiKey) => void changeGeneration(`providers/${encodeURIComponent(provider)}/credential`, "PUT", { model, apiKey })} onActivate={(provider, model) => void changeGeneration("settings", "PUT", { mode: "PERSONAL", provider, model })} onDelete={provider => void changeGeneration(`providers/${encodeURIComponent(provider)}/credential`, "DELETE")} />}
    {passwordOpen && account && <PasswordDialog configured error={accountError} isBusy={accountBusy} onCancel={() => setPasswordOpen(false)} onSave={(current, next) => void savePassword(current, next)} />}
    {game && !focused && <nav className="bottom-nav" aria-label="主要导航">{([["adventure", "故事"], ["character", "人物"], ["chronicle", "前情"], ["settings", "设置"]] as [TabId, string][]).map(([id, label]) => <button key={id} type="button" aria-current={tab === id ? "page" : undefined} onClick={() => { closeReference(); setTab(id); }}><TabIcon id={id} /><span>{label}</span></button>)}</nav>}
  </div>;
}
