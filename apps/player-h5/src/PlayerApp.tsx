import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { ArrowLeftIcon, ChevronDownIcon, Cross2Icon, EnterIcon, Pencil1Icon } from "@radix-ui/react-icons";
import {
  ApiError,
  gameApi,
  type AccessStatus,
  type AuthResponse,
  type CharacterDraft,
  type ReleaseNote,
  type SyncMode,
  type StoryCharacterRoute,
  type StoryCharacterOriginSubmission,
  type StoryCorrectionPreview,
  type StoryGenerationSettings,
  type StoryTurnRequestStatus,
} from "./game-api";
import type { PlayerBootstrapView, PlayerSaveSummary, PlayerStorySnapshot } from "./app/player-read-model";
import { DestinyLoadingOverlay, LoadingView, TabIcon, type AppStage, type TabId } from "./app/AppViews";
import {
  FONT_SIZE_KEY,
  STORY_TURN_RECOVERY_LIMIT_MS,
  THEME_KEY,
  cacheSnapshot,
  clearCachedSnapshot,
  newStoryTurnRequestId,
  readCachedSnapshot,
  readStoryTurnRecovery,
  recoverableStoryTurnFailure,
  revealFormControl,
  writeStoryTurnRecovery,
  clearStoryTurnRecovery,
  updateStoryTurnRecovery,
  isStoryTurnRecoveryStorageKey,
  type PendingStoryTurn,
  type StoryTurnRecovery,
} from "./app/local-runtime";
import {
  DEFAULT_DRAFT,
  STORY_GENDER_NAMES,
  freshCharacterDraft,
  raceCodeFromName,
  playPreferenceSummary,
  selectedRace,
  selectedWorld,
  toPlayerBootstrap,
  toPlayerSaveSummary,
  toPlayerStorySnapshot,
} from "./app/story-adapter";
import { LoginView, MaintenanceDialog, type MaintenanceNotice } from "./features/auth/AuthViews";
import {
  AdventureView,
  CharacterView,
  ChronicleView,
  type CleanupTarget,
  type PendingPlayerAction,
} from "./features/gameplay/GameViews";
import {
  CharacterCreationView,
} from "./features/onboarding/CharacterOnboardingViews";
import { DeathView, ResetConfirmDialog } from "./features/saves/LifecycleViews";
import {
  AbandonSaveDialog,
  GenerationAccessCard,
  SaveSelectionView,
} from "./features/saves/SaveViews";
import {
  DeleteAccountDialog,
  GenerationSettingsDialog,
  PasswordDialog,
  RecordCleanupDialog,
  ReleaseAnnouncementDialog,
  SettingsView,
  StoryCorrectionDialog,
  TurnLimitDialog,
  type FontSizeMode,
  type ThemeMode,
  type TurnLimitNotice,
} from "./features/settings/SettingsViews";
import { problemFrom, type UiProblem } from "./shared/problem";
import { DesignPreviewApp } from "./design/DesignPreview";
import { DesignReviewHub } from "./design/DesignReviewHub";

function confirmedOrigin(draft: CharacterDraft, raceId: string,
                         route: StoryCharacterRoute): StoryCharacterOriginSubmission {
  const source = { ...route.origin, summary: draft.originPremise.trim() };
  const raceIdentity = draft.raceIdentity.trim();
  if (!raceIdentity || raceId.endsWith(":human")) return source;
  const statement = raceId.endsWith(":yao")
    ? `本相为${raceIdentity}`
    : `本源或本体为${raceIdentity}`;
  return {
    ...source,
    establishedFacts: [
      ...source.establishedFacts.filter((fact) => fact.type !== "IDENTITY"),
      { localRef: "origin:fact:identity", type: "IDENTITY", statement },
    ],
  };
}

function hasPreparedOpening(route: StoryCharacterRoute): boolean {
  const scene = route.opening.scene;
  return Boolean(scene?.title?.trim() && scene.narrative?.trim() && Array.isArray(scene.choices) && scene.choices.length
    && scene.choices.every((choice) => choice.title.trim() && choice.description.trim()));
}

export default function PlayerApp() {
  const [stage, setStage] = useState<AppStage>("loading");
  const [tab, setTab] = useState<TabId>("adventure");
  const [auth, setAuth] = useState<AuthResponse | null>(null);
  const [bootstrap, setBootstrap] = useState<PlayerBootstrapView | null>(null);
  const [snapshot, setSnapshot] = useState<PlayerStorySnapshot | null>(null);
  const [saves, setSaves] = useState<PlayerSaveSummary[]>([]);
  const [maxActiveSaves, setMaxActiveSaves] = useState(4);
  const storyMaxActiveSavesRef = useRef(4);
  const [abandonTarget, setAbandonTarget] = useState<PlayerSaveSummary | null>(null);
  const [draft, setDraft] = useState("");
  const [characterDraft, setCharacterDraft] = useState<CharacterDraft>(DEFAULT_DRAFT);
  const [nameAlternatives, setNameAlternatives] = useState<string[]>([]);
  const [generatedName, setGeneratedName] = useState("");
  const [characterRoutes, setCharacterRoutes] = useState<StoryCharacterRoute[]>([]);
  const [selectedCharacterRouteId, setSelectedCharacterRouteId] = useState("");
  const [preparedCharacterRoute, setPreparedCharacterRoute] = useState<{ route: StoryCharacterRoute; inputKey: string } | null>(null);
  const characterInputVersion = useRef(0);
  const preparedOpening = preparedCharacterRoute?.inputKey === JSON.stringify(characterDraft)
    ? preparedCharacterRoute.route : null;
  const [theme, setTheme] = useState<ThemeMode>(() => (window.localStorage.getItem(THEME_KEY) as ThemeMode | null) ?? "system");
  const [fontSize, setFontSize] = useState<FontSizeMode>(() => (window.localStorage.getItem(FONT_SIZE_KEY) as FontSizeMode | null) ?? "standard");
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const [isBusy, setIsBusy] = useState(false);
  const [isCreatingCharacter, setIsCreatingCharacter] = useState(false);
  const busyRef = useRef(false);
  const [isComposerFocused, setIsComposerFocused] = useState(false);
  const [customActionOpen, setCustomActionOpen] = useState(false);
  const [syncMode, setSyncMode] = useState<SyncMode>("connecting");
  const [error, setError] = useState<UiProblem | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [resetIntent, setResetIntent] = useState<"RESET" | null>(null);
  const [restartTargetId, setRestartTargetId] = useState<string | null>(null);
  const [pendingRelease, setPendingRelease] = useState<ReleaseNote | null>(null);
  const [loginAnnouncement, setLoginAnnouncement] = useState<ReleaseNote | null>(null);
  const [accessStatus, setAccessStatus] = useState<AccessStatus | null>(null);
  const [releaseNotes, setReleaseNotes] = useState<ReleaseNote[]>([]);
  const [showGenerationSettings, setShowGenerationSettings] = useState(false);
  const [showPasswordSettings, setShowPasswordSettings] = useState(false);
  const [showDeleteAccount, setShowDeleteAccount] = useState(false);
  const [turnLimitNotice, setTurnLimitNotice] = useState<TurnLimitNotice | null>(null);
  const [maintenanceNotice, setMaintenanceNotice] = useState<MaintenanceNotice | null>(null);
  const [generationSettings, setGenerationSettings] = useState<StoryGenerationSettings | null>(null);
  const [cleanupTarget, setCleanupTarget] = useState<CleanupTarget | null>(null);
  const [showCorrection, setShowCorrection] = useState(false);
  const [correctionPreview, setCorrectionPreview] = useState<StoryCorrectionPreview | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingPlayerAction | null>(null);
  const [turnRecovery, setTurnRecovery] = useState<StoryTurnRecovery | null>(null);
  const [recoveryStatus, setRecoveryStatus] = useState<StoryTurnRequestStatus["status"] | null>(null);
  const recoveryScope = useRef("");
  const recoveryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [isCheckingRecovery, setIsCheckingRecovery] = useState(false);

  const designPreviewPage = import.meta.env.DEV ? new URLSearchParams(window.location.search).get("design") : null;
  const designPreview = Boolean(designPreviewPage);
  const game = snapshot?.story ?? null;
  const choices = game?.choices ?? [];
  const email = auth?.user?.email ?? "";
  recoveryScope.current = stage === "game" && auth?.authenticated && game ? email + "\n" + game.saveId : "";
  const currentRecovery = turnRecovery?.pending.accountEmail === email && turnRecovery.pending.saveId === game?.saveId
    ? turnRecovery : null;
  const actionLocked = currentRecovery?.status === "PENDING";
  const showGameChrome = stage === "saves" || stage === "game";
  const actualTheme = "paper";
  const storeSnapshot = (next: PlayerStorySnapshot) => {
    setSnapshot(next);
    cacheSnapshot(next);
  };

  const loadBootstrap = async () => {
    const source = await gameApi.storyBootstrap();
    storyMaxActiveSavesRef.current = source.maxActiveSaves;
    setMaxActiveSaves(source.maxActiveSaves);
    const next = toPlayerBootstrap(source);
    setBootstrap(next);
    setCharacterDraft((current) => {
      const world = selectedWorld(next, current);
      const race = world?.races.find((option) => option.raceId === current.raceId) ?? world?.races[0];
      const preferences = next.creation.playPreferences;
      const focusCodes = new Set(preferences.focus.map((option) => option.code));
      const selectedFocus = current.playPreference.focus.filter((code) => focusCodes.has(code));
      const validOrDefault = (value: string, options: Array<{ code: string }>, fallback: string) =>
        options.some((option) => option.code === value) ? value : fallback;
      return world && race ? { ...current, worldPackageId: world.packageId, worldRevision: world.revision,
        raceId: race.raceId,
        playPreference: {
          ...current.playPreference,
          focus: selectedFocus.length ? selectedFocus : preferences.defaultFocus,
          combatFrequency: validOrDefault(current.playPreference.combatFrequency,
            preferences.combatFrequency, preferences.defaultCombatFrequency),
          pace: validOrDefault(current.playPreference.pace, preferences.pace, preferences.defaultPace),
          guidance: validOrDefault(current.playPreference.guidance, preferences.guidance,
            preferences.defaultGuidance),
        } } : current;
    });
    return next;
  };

  const stageFor = (_next: PlayerStorySnapshot): AppStage => "game";

  const refreshSaves = async () => {
    const rows = await gameApi.storySaves();
    const list = { maxActiveSaves: storyMaxActiveSavesRef.current,
      saves: rows.filter((save) => save.status === "ACTIVE").map(toPlayerSaveSummary) };
    setSaves(list.saves);
    setMaxActiveSaves(list.maxActiveSaves);
    return list;
  };

  const loadSave = async (saveId: string) => toPlayerStorySnapshot(await gameApi.storySave(saveId));

  const enterAuthenticatedFlow = async (nextAuth: AuthResponse) => {
    setAuth(nextAuth);
    setSyncMode("server");
    await loadBootstrap();
    const [notes, pending, nextGenerationSettings] = await Promise.all([
      gameApi.releaseNotes(), gameApi.pendingReleaseNote(), gameApi.generationSettings(),
    ]);
    setReleaseNotes(notes);
    setPendingRelease(pending);
    setGenerationSettings(nextGenerationSettings);
    if (!nextAuth.passwordConfigured) setShowPasswordSettings(true);
    await refreshSaves();
    // 登录后统一进入命卷列表，让单命卷玩家也能看见槽位、切换或新增角色。
    setSnapshot(null);
    setStage("saves");
    return nextGenerationSettings;
  };

  useEffect(() => {
    let active = true;
    if (designPreview) {
      setSyncMode("server");
      setStage("login");
      return () => { active = false; };
    }
    (async () => {
      try {
        const nextAccessStatus = await gameApi.accessStatus();
        if (active) setAccessStatus(nextAccessStatus);
        void gameApi.latestPublicReleaseNote()
          .then((note) => { if (active) setLoginAnnouncement(note); })
          .catch(() => undefined);
        const session = await gameApi.me();
        if (!active) return;
        if (!session.authenticated) {
          await loadBootstrap();
          if (active) { setAuth(session); setSyncMode("server"); setStage("login"); }
          return;
        }
        await enterAuthenticatedFlow(session);
      } catch (caught) {
        if (!active) return;
        if (caught instanceof ApiError && caught.code === "GAME_MAINTENANCE") {
          setMaintenanceNotice({
            title: typeof caught.details.title === "string" ? caught.details.title : "命卷暂时封存",
            message: caught.message,
            resumeAt: typeof caught.details.resumeAt === "string" ? caught.details.resumeAt : "",
          });
          setSyncMode("server");
          setStage("login");
          return;
        }
        const cached = readCachedSnapshot();
        setSyncMode("offline");
        if (cached) {
          setSnapshot(cached);
          setStage("game");
        } else {
          setStage("login");
        }
      }
    })();
    return () => { active = false; };
  }, [designPreview]);

  useEffect(() => {
    if (stage !== "login") return;
    let active = true;
    let loading = false;
    const refreshLoginAnnouncement = async () => {
      if (loading || document.visibilityState === "hidden") return;
      loading = true;
      try {
        const note = await gameApi.latestPublicReleaseNote();
        if (active) setLoginAnnouncement(note);
      } catch { /* 登录页公告失败不阻断账号登录 */ }
      finally { loading = false; }
    };
    const onVisibility = () => { if (document.visibilityState === "visible") void refreshLoginAnnouncement(); };
    void refreshLoginAnnouncement();
    window.addEventListener("focus", refreshLoginAnnouncement);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      active = false;
      window.removeEventListener("focus", refreshLoginAnnouncement);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [stage]);

  useEffect(() => {
    if (!auth?.authenticated) return;
    let active = true;
    let checking = false;
    const checkReleases = async () => {
      if (checking || document.visibilityState === "hidden") return;
      checking = true;
      try {
        const [notes, pending] = await Promise.all([gameApi.releaseNotes(), gameApi.pendingReleaseNote()]);
        if (!active) return;
        setReleaseNotes(notes);
        if (pending) setPendingRelease((current) => current?.id === pending.id ? current : pending);
      } catch (caught) {
        if (caught instanceof ApiError && caught.status === 401 && active) {
          setAuth(null);
          setStage("login");
          setSyncMode("server");
        }
        // 其他公告检查失败不应中断单机命卷。
      } finally {
        checking = false;
      }
    };
    const onVisible = () => { if (document.visibilityState === "visible") void checkReleases(); };
    const timer = window.setInterval(() => void checkReleases(), 5 * 60_000);
    window.addEventListener("focus", checkReleases);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener("focus", checkReleases);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [auth?.authenticated]);

  useEffect(() => {
    window.localStorage.setItem(THEME_KEY, theme);
  }, [theme]);

  useEffect(() => {
    window.localStorage.setItem(FONT_SIZE_KEY, fontSize);
  }, [fontSize]);

  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => {
      const layoutHeight = Math.max(window.innerHeight, document.documentElement.clientHeight);
      setKeyboardOffset(Math.max(0, layoutHeight - viewport.height - viewport.offsetTop));
    };
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    update();
    return () => { viewport.removeEventListener("resize", update); viewport.removeEventListener("scroll", update); };
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 2400);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const runBusy = async (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setIsBusy(true);
    setError(null);
    try {
      await task();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401
          && caught.code !== "LOGIN_CREDENTIALS_INVALID") {
        setAuth(null);
        setStage("login");
        setSyncMode("server");
        setError({ category: "ACCOUNT", title: "登录状态已经失效",
          message: "请重新进入命卷。刚才填写的内容不会产生任何消耗。",
          suggestions: ["重新登录"], retryable: false, turnConsumed: false, stateChanged: false });
        return;
      }
      if (caught instanceof ApiError && caught.code === "PUBLIC_TURN_LIMIT_REACHED") {
        setTurnLimitNotice({
          message: caught.message,
          contactText: typeof caught.details.contactText === "string" ? caught.details.contactText : "",
          supportGroup: typeof caught.details.supportGroup === "string" ? caught.details.supportGroup : "",
        });
        gameApi.generationSettings().then(setGenerationSettings).catch(() => undefined);
        return;
      }
      if (caught instanceof ApiError && caught.code === "GAME_MAINTENANCE") {
        setMaintenanceNotice({
          title: typeof caught.details.title === "string" ? caught.details.title : "命卷暂时封存",
          message: caught.message,
          resumeAt: typeof caught.details.resumeAt === "string" ? caught.details.resumeAt : "",
        });
        setSyncMode("server");
        return;
      }
      if (caught instanceof ApiError && ["STATE_CONFLICT", "CORRECTION_REVISION_CONFLICT", "CORRECTION_PREVIEW_EXPIRED"].includes(caught.code ?? "") && snapshot?.story.saveId) {
        try {
          const refreshed = await loadSave(snapshot.story.saveId);
          storeSnapshot(refreshed);
          if (showCorrection) setCorrectionPreview(null);
        } catch {
          // 保留原始冲突提示；刷新失败时不覆盖真正的原因。
        }
      }
      setError(problemFrom(caught));
    } finally {
      busyRef.current = false;
      setIsBusy(false);
    }
  };

  const ownsRecovery = (pending: PendingStoryTurn) =>
    recoveryScope.current === pending.accountEmail + "\n" + pending.saveId
    && readStoryTurnRecovery(pending.accountEmail, pending.saveId)?.pending.requestId === pending.requestId;

  const showRecovery = (record: StoryTurnRecovery) => {
    // 请求已经留存；后续提示写失败不能把网络未知结果误报为失败。
    try { if (!updateStoryTurnRecovery(record)) return; } catch { /* 原请求仍保留。 */ }
    if (!ownsRecovery(record.pending)) return;
    setTurnRecovery(record);
    setDraft(record.draft ?? record.pending.action);
    setCustomActionOpen(true);
  };

  const restoreFailedAction = (pending: PendingStoryTurn, message: string) => {
    showRecovery({ pending, status: "FAILED", draft: pending.action, message });
    setRecoveryStatus("FAILED");
  };

  const finishStoryTurn = async (next: PlayerStorySnapshot, pending: PendingStoryTurn) => {
    // 成功快照是权威结果；列表/缓存刷新失败不能重新把已提交回合当成失败。
    if (ownsRecovery(pending)) {
      setSnapshot(next);
      try { cacheSnapshot(next); } catch { /* 缓存可重建。 */ }
      setDraft("");
      setTurnRecovery(null);
      setRecoveryStatus(null);
      setTab("adventure");
      setStage("game");
      setSyncMode("server");
      setToast("本回合已写入统一时间线");
    }
    try { clearStoryTurnRecovery(pending); } catch { /* 下次仍按原请求查询成功结果。 */ }
    await refreshSaves().catch(() => undefined);
    gameApi.generationSettings().then(setGenerationSettings).catch(() => undefined);
  };

  /**
   * 恢复只查询原请求。UNKNOWN 不是失败；只有玩家明确重试且重新查询仍为 UNKNOWN 才重发原参数。
   * 每次进入命卷/继续查询都有新的轮询预算，原创建时间仅作记录，不使旧请求永远无法查询。
   */
  const recoverPendingStoryTurn = async (pending: PendingStoryTurn,
      options: { retry?: boolean; rejected?: ApiError; deadline?: number } = {}) => {
    const deadline = options.deadline ?? Date.now() + STORY_TURN_RECOVERY_LIMIT_MS;
    setIsCheckingRecovery(true);
    try {
      setRecoveryStatus(null);
      while (ownsRecovery(pending)
          && readStoryTurnRecovery(pending.accountEmail, pending.saveId)?.status === "PENDING") {
        let status: StoryTurnRequestStatus;
        try {
          status = await gameApi.storyTurnRequestStatus(pending.saveId, pending.requestId);
        } catch (caught) {
          if (!ownsRecovery(pending)) return;
          if (caught instanceof ApiError && caught.status === 401) throw caught;
          const inaccessible = caught instanceof ApiError && [403, 404].includes(caught.status);
          showRecovery({ pending, status: "PENDING", message: inaccessible
            ? "暂时无法访问这份命卷，原行动已保留。请确认账号与命卷是否仍可访问。"
            : "暂时无法确认这次行动的结果。原行动已保留，恢复连接后可以继续查询。" });
          return;
        }
        if (!ownsRecovery(pending)
            || readStoryTurnRecovery(pending.accountEmail, pending.saveId)?.status !== "PENDING") return;
        setRecoveryStatus(status.status);
        if (status.status === "COMMITTED") {
          let committed: PlayerStorySnapshot;
          try {
            committed = toPlayerStorySnapshot(status.snapshot ?? await gameApi.storySave(pending.saveId));
          } catch (caught) {
            if (caught instanceof ApiError && caught.status === 401) throw caught;
            showRecovery({ pending, status: "PENDING", message:
              "这次行动已经完成，暂时无法读取结果。原请求已保留，请继续查询。" });
            return;
          }
          await finishStoryTurn(committed, pending);
          return;
        }
        if (status.status === "FAILED" && status.terminal) {
          restoreFailedAction(pending, "本次行动没有写入命卷。原文字已恢复，可以修改后重新提交。");
          return;
        }
        if (status.status === "UNKNOWN") {
          // 明确的同步拒绝还须对账：旧请求若已提交/仍处理，不得走失败草稿分支。
          if (options.rejected) {
            const next = await loadSave(pending.saveId);
            if (!ownsRecovery(pending)) return;
            storeSnapshot(next);
            restoreFailedAction(pending, problemFrom(options.rejected).message + " 原文字已恢复，请检查后重新提交。");
            // 保留已有额度、维护、密钥等专属反馈；草稿已经保存，不再丢失。
            throw options.rejected;
          }
          if (options.retry && status.retryable) {
            setIsCheckingRecovery(false);
            await sendPendingStoryTurn(pending);
            return;
          }
          showRecovery({ pending, status: "PENDING", message:
            "暂时没有查到这次行动的结果，尚不能确认是否送达。你可以继续查询，或重试同一条原行动。" });
          return;
        }
        showRecovery({ pending, status: "PENDING", message: "这次行动仍在处理中，原文字已保留，正在查询结果。" });
        if (Date.now() >= deadline) {
          showRecovery({ pending, status: "PENDING", message:
            "这次行动的结果仍未确认。已暂停持续查询，你可以稍后继续查询，原行动不会自动重发。" });
          return;
        }
        // 查询间隔不锁住页面；同一命卷仍禁止另起行动，读取与恢复入口保持可见。
        recoveryTimer.current = setTimeout(() => {
          if (ownsRecovery(pending)) void continueStoryTurnRecovery(false, deadline);
        }, Math.max(1200, Math.min(status.retryAfterMs || 2500, 5000)));
        return;
      }
    } finally { setIsCheckingRecovery(false); }
  };

  const sendPendingStoryTurn = async (pending: PendingStoryTurn) => {
    try {
      const response = await gameApi.storyTurn(pending.saveId, pending.action, pending.expectedRevision,
        pending.choiceId, pending.requestId);
      await finishStoryTurn(toPlayerStorySnapshot(response), pending);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) throw caught;
      const rejected = caught instanceof ApiError && !recoverableStoryTurnFailure(caught)
        && caught.code !== "TURN_IN_PROGRESS" ? caught : undefined;
      await recoverPendingStoryTurn(pending, { rejected });
    }
  };

  const continueStoryTurnRecovery = (retry = false, deadline?: number) => {
    if (!email || !game || busyRef.current || syncMode !== "server") return;
    if (recoveryTimer.current) clearTimeout(recoveryTimer.current);
    const record = readStoryTurnRecovery(email, game.saveId);
    if (record?.status !== "PENDING") return;
    setPendingAction({ kind: record.pending.choiceId ? "CHOICE" : "TEXT", label: record.pending.action });
    return runBusy(async () => {
      try { await recoverPendingStoryTurn(record.pending, { retry, deadline }); }
      finally { setPendingAction(null); }
    });
  };

  useEffect(() => {
    if (!email || stage !== "game" || !game?.saveId) {
      setTurnRecovery(null);
      setRecoveryStatus(null);
      setDraft("");
      return;
    }
    const restore = () => {
      const record = readStoryTurnRecovery(email, game.saveId);
      setTurnRecovery(record);
      setRecoveryStatus(null);
      setDraft(record ? record.draft ?? record.pending.action : "");
      setCustomActionOpen(Boolean(record));
      return record;
    };
    const record = restore();
    // 只在玩家进入对应命卷后恢复，不在列表后台清记录或跳到另一份命卷。
    if (record?.status === "PENDING" && !busyRef.current) void continueStoryTurnRecovery();
    const onStorage = (event: StorageEvent) => {
      if (isStoryTurnRecoveryStorageKey(event.key, email, game.saveId)) restore();
    };
    window.addEventListener("storage", onStorage);
    return () => {
      window.removeEventListener("storage", onStorage);
      if (recoveryTimer.current) clearTimeout(recoveryTimer.current);
    };
    // 恢复提示/查询结果重绘不能触发新一轮轮询。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [email, game?.saveId, stage]);

  const updateActionDraft = (value: string) => {
    if (actionLocked) return;
    setDraft(value);
    if (currentRecovery?.status === "FAILED") {
      const updated = { ...currentRecovery, draft: value };
      try { if (!updateStoryTurnRecovery(updated, true)) return; } catch { /* 当前页面仍保留正在编辑的文字。 */ }
      setTurnRecovery(updated);
    }
  };

  const authenticate = (mode: "login" | "register", loginEmail: string, password: string) => runBusy(async () => {
    const nextAuth = mode === "register"
      ? await gameApi.register(loginEmail, password)
      : await gameApi.login(loginEmail, password);
    const nextGenerationSettings = await enterAuthenticatedFlow(nextAuth);
    setToast(nextAuth.newUser
      ? `新账号已登记，获赠 ${nextGenerationSettings.platform.turnLimit} 点平台推演点`
      : "欢迎回来，命卷已寻回");
  });

  const generateName = () => runBusy(async () => {
    const inputVersion = characterInputVersion.current;
    if (!bootstrap || !characterDraft.raceId || !characterDraft.gender) {
      throw new ApiError("请先选择性别与种族", 400);
    }
    const race = selectedRace(bootstrap, characterDraft);
    if (!race) throw new ApiError("所选种族不属于当前世界，请重新选择", 400);
    const raceCode = raceCodeFromName(race.name);
    if (!raceCode) throw new ApiError("当前种族暂不支持随机姓名，请直接填写角色名", 400);
    const generated = await gameApi.generateName(raceCode, characterDraft.gender,
      [characterDraft.playerName, ...nameAlternatives].filter(Boolean));
    if (inputVersion !== characterInputVersion.current) return;
    characterInputVersion.current += 1;
    setCharacterDraft((current) => ({ ...current, playerName: generated.name }));
    setGeneratedName(generated.name);
    setNameAlternatives(generated.alternatives);
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setPreparedCharacterRoute(null);
  });

  const updateCharacterDraft = (patch: Partial<CharacterDraft>) => {
    // 草稿修改立即使候选及在途响应失效，不能将旧实体或旧场景套到新来历上。
    characterInputVersion.current += 1;
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setPreparedCharacterRoute(null);
    const identityChanged = patch.raceId !== undefined || patch.gender !== undefined
      || patch.worldPackageId !== undefined || patch.worldRevision !== undefined;
    if (identityChanged) {
      setNameAlternatives([]);
      setCharacterDraft((current) => ({
        ...current,
        ...patch,
        raceIdentity: patch.raceId !== undefined && patch.raceId !== current.raceId ? "" : current.raceIdentity,
        playerName: current.playerName === generatedName ? "" : current.playerName,
      }));
      setGeneratedName("");
      return;
    }
    if (patch.playerName !== undefined) {
      setGeneratedName(nameAlternatives.includes(patch.playerName) ? patch.playerName : "");
    }
    setCharacterDraft((current) => ({ ...current, ...patch }));
  };

  const requestCharacterRoutes = async (mode: "RECOMMEND" | "FINALIZE", inputVersion: number) => {
    if (!bootstrap || !characterDraft.gender || !characterDraft.raceId) {
      throw new ApiError("请先选择性别与种族，再推演角色路线", 400);
    }
    if (!characterDraft.playerName.trim()) {
      throw new ApiError("请先输入角色名，或点击“随机雅名”生成一个名字", 400);
    }
    const validation = await gameApi.validateName(characterDraft.playerName);
    if (inputVersion !== characterInputVersion.current) return null;
    if (!validation.valid) throw new ApiError(validation.message, 400);
    const world = selectedWorld(bootstrap, characterDraft);
    const race = selectedRace(bootstrap, characterDraft);
    if (!world || !race) throw new ApiError("世界或种族目录已经变化，请重新选择", 400);
    if (!race.raceId.endsWith(":human") && !characterDraft.raceIdentity.trim()) {
      throw new ApiError(race.raceId.endsWith(":yao")
        ? "请先填写妖族角色的具体本相"
        : "请先填写灵族角色的本源或本体", 400);
    }
    const roleSeed = [characterDraft.raceIdentity.trim(), characterDraft.originPremise.trim()]
      .filter(Boolean).join("；");
    const preferenceSeed = playPreferenceSummary(bootstrap, characterDraft);
    if (roleSeed.length > 400 || preferenceSeed.length > 400) {
      throw new ApiError("来历或偏好过长，请精简后再准备开篇", 400);
    }
    const response = await gameApi.storyCharacterRoutes({
      mode,
      worldPackageId: world.packageId,
      worldRevision: world.revision,
      identity: {
        name: validation.normalizedName,
        raceId: race.raceId,
        genderCode: characterDraft.gender,
      },
      roleSeed: roleSeed || undefined,
      preferenceSeed,
    });
    if (inputVersion !== characterInputVersion.current) return null;
    if (response.routes.length !== (mode === "RECOMMEND" ? 3 : 1)
      || response.routes.some((route) => !hasPreparedOpening(route))) {
      throw new ApiError("开篇尚未准备完整，请重试；你的来历已保留", 502);
    }
    return { routes: response.routes, draft: { ...characterDraft, playerName: validation.normalizedName } };
  };

  const generateCharacterRoutes = () => runBusy(async () => {
    const inputVersion = characterInputVersion.current;
    try {
      const result = await requestCharacterRoutes("RECOMMEND", inputVersion);
      if (!result) return;
      setCharacterDraft(result.draft);
      setCharacterRoutes(result.routes);
      setSelectedCharacterRouteId("");
      setPreparedCharacterRoute(null);
      setToast("三条角色路线已经落定，可以选择或继续自定义");
    } catch (failure) {
      if (inputVersion === characterInputVersion.current) throw failure;
    }
  });

  const selectCharacterRoute = (route: StoryCharacterRoute) => {
    if (!characterRoutes.includes(route)) return;
    characterInputVersion.current += 1;
    setSelectedCharacterRouteId(route.id);
    const nextDraft = { ...characterDraft, originPremise: route.origin.summary };
    setCharacterDraft(nextDraft);
    setPreparedCharacterRoute(hasPreparedOpening(route) ? { route, inputKey: JSON.stringify(nextDraft) } : null);
  };

  const prepareCharacterOpening = async (): Promise<boolean> => {
    if (preparedOpening && hasPreparedOpening(preparedOpening)) return true;
    const inputVersion = characterInputVersion.current;
    let prepared = false;
    await runBusy(async () => {
      if (!characterDraft.originPremise.trim()) throw new ApiError("请先填写人物来处", 400);
      try {
        const result = await requestCharacterRoutes("FINALIZE", inputVersion);
        if (!result) return;
        // 模型整理材料，但不替换玩家亲自填写的来历；确认后才会创建命卷。
        const route = { ...result.routes[0], origin: { ...result.routes[0].origin, summary: result.draft.originPremise.trim() } };
        setCharacterDraft(result.draft);
        setPreparedCharacterRoute({ route, inputKey: JSON.stringify(result.draft) });
        setSelectedCharacterRouteId("");
        prepared = true;
      } catch (failure) {
        if (inputVersion === characterInputVersion.current) throw failure;
      }
    });
    return prepared && inputVersion === characterInputVersion.current;
  };

  const createCharacter = async () => {
    setIsCreatingCharacter(true);
    try {
      await runBusy(async () => {
        const inputVersion = characterInputVersion.current;
        if (!bootstrap || !characterDraft.gender || !characterDraft.raceId) {
          throw new ApiError("请先选择性别与种族，再推演命格", 400);
        }
        if (!characterDraft.playerName.trim()) {
          throw new ApiError("请先输入角色名，或点击“随机雅名”生成一个名字", 400);
        }
        if (!characterDraft.originPremise.trim()) {
          throw new ApiError("请选择一条角色路线，或填写自己的角色设定", 400);
        }
        const validation = await gameApi.validateName(characterDraft.playerName);
        if (inputVersion !== characterInputVersion.current) return;
        if (!validation.valid) throw new ApiError(validation.message, 400);
        const request = { ...characterDraft, playerName: validation.normalizedName };
        const world = selectedWorld(bootstrap, request);
        const race = selectedRace(bootstrap, request);
        if (!world || !race) throw new ApiError("世界或种族目录已经变化，请重新选择", 400);
        if (!race.raceId.endsWith(":human") && !request.raceIdentity.trim()) {
          throw new ApiError(race.raceId.endsWith(":yao")
            ? "请先填写妖族角色的具体本相"
            : "请先填写灵族角色的本源或本体", 400);
        }
        if (!preparedOpening || !hasPreparedOpening(preparedOpening)) {
          throw new ApiError("来历或偏好已变化，请先重新准备并查看开篇", 409);
        }
        const origin = confirmedOrigin(request, race.raceId, preparedOpening);
        const opening = preparedOpening.opening;
        const created = toPlayerStorySnapshot(await gameApi.storyCreateSave({
          worldPackageId: world.packageId,
          worldRevision: world.revision,
          identity: { name: request.playerName, raceId: race.raceId, genderCode: request.gender },
          origin,
          opening,
          playPreference: request.playPreference,
          replaceSaveId: restartTargetId ?? undefined,
        }));
        setCharacterDraft(request);
        setRestartTargetId(null);
        storeSnapshot(created);
        await refreshSaves();
        setCharacterRoutes([]);
        setSelectedCharacterRouteId("");
        setPreparedCharacterRoute(null);
        setTab("adventure");
        setStage("game");
        setToast(`此生命卷已经开启，你从${created.story.player?.realm ?? "此世起点"}入世`);
      });
    } finally {
      setIsCreatingCharacter(false);
    }
  };

  const takeTurn = (inputType: "CHOICE" | "TEXT", payload: { choiceId?: string; content?: string }) => {
    if (!game || !snapshot || !email || syncMode !== "server" || busyRef.current) return;
    // 提交前重读，阻止另一标签页或刚恢复的未知请求被新 ID 覆盖。
    const existing = readStoryTurnRecovery(email, game.saveId);
    if (existing?.status === "PENDING") {
      showRecovery(existing);
      return;
    }
    const selectedChoice = inputType === "CHOICE" ? choices.find((choice) => choice.id === payload.choiceId) : undefined;
    const action = inputType === "CHOICE"
      ? selectedChoice ? `${selectedChoice.label}：${selectedChoice.hint}` : "观察当前局势"
      : (payload.content ?? "观察当前局势");
    setPendingAction({ kind: inputType, choiceId: payload.choiceId, label: inputType === "CHOICE" ? selectedChoice?.label ?? action : action });
    return runBusy(async () => {
      try {
        if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
        setIsComposerFocused(false);
        const pending: PendingStoryTurn = {
          saveId: game.saveId, requestId: newStoryTurnRequestId(), action,
          choiceId: inputType === "CHOICE" ? payload.choiceId : undefined,
          expectedRevision: snapshot.revision, accountEmail: email, createdAt: Date.now(),
        };
        const record: StoryTurnRecovery = { pending, status: "PENDING" };
        // 无法持久保存恢复信息时不发送，避免刷新后找不到这次行动。
        writeStoryTurnRecovery(record);
        setTurnRecovery(record);
        setRecoveryStatus(null);
        await sendPendingStoryTurn(pending);
      } finally {
        setPendingAction(null);
      }
    });
  };

  const confirmCleanup = () => runBusy(async () => {
    if (!game || !snapshot || !cleanupTarget) return;
    const target = cleanupTarget;
    const response = await gameApi.storyRemoveEntity(game.saveId, target.id, snapshot.revision);
    storeSnapshot(toPlayerStorySnapshot(response));
    setCleanupTarget(null);
    setToast(target.kind === "item" ? "物品已移出当前行囊，旧事仍保留" : "人物已移出当前关注，旧事仍保留");
  });

  const previewCorrection = (instruction: string) => runBusy(async () => {
    if (!game || !snapshot) return;
    const preview = await gameApi.storyCorrectionPreview(game.saveId, instruction, snapshot.revision);
    setCorrectionPreview(preview);
  });

  const confirmCorrection = () => runBusy(async () => {
    if (!game || !snapshot || !correctionPreview) return;
    const response = await gameApi.storyCorrectionConfirm(game.saveId, correctionPreview.proposalToken, snapshot.revision);
    storeSnapshot(toPlayerStorySnapshot(response));
    await refreshSaves();
    setCorrectionPreview(null);
    setShowCorrection(false);
    setToast("命卷事实已修正，原有纪事仍然保留");
  });

  const openCorrection = () => {
    setCorrectionPreview(null);
    setError(null);
    setShowCorrection(true);
  };

  const closeCorrection = () => {
    if (isBusy) return;
    setCorrectionPreview(null);
    setShowCorrection(false);
    setError(null);
  };

  const prepareRestart = () => {
    if (!game) return;
    characterInputVersion.current += 1;
    setPreparedCharacterRoute(null);
    setRestartTargetId(game.saveId);
    setSnapshot(null);
    setCharacterDraft(bootstrap ? freshCharacterDraft(bootstrap) : DEFAULT_DRAFT);
    setNameAlternatives([]);
    setGeneratedName("");
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setStage("create");
  };

  const cancelRestart = () => runBusy(async () => {
    if (!restartTargetId) return;
    const current = await loadSave(restartTargetId);
    setRestartTargetId(null);
    storeSnapshot(current);
    setTab("adventure");
    setStage(stageFor(current));
  });

  const openSave = (save: PlayerSaveSummary) => runBusy(async () => {
    const next = await loadSave(save.saveId);
    setRestartTargetId(null);
    storeSnapshot(next);
    await refreshSaves();
    setTab("adventure");
    setStage(stageFor(next));
  });

  const createAdditionalSave = () => {
    if (saves.length >= maxActiveSaves) return;
    characterInputVersion.current += 1;
    setPreparedCharacterRoute(null);
    setRestartTargetId(null);
    setSnapshot(null);
    setCharacterDraft(bootstrap ? freshCharacterDraft(bootstrap) : DEFAULT_DRAFT);
    setNameAlternatives([]);
    setGeneratedName("");
    setCharacterRoutes([]);
    setSelectedCharacterRouteId("");
    setStage("create");
  };

  const abandonSave = () => runBusy(async () => {
    if (!abandonTarget) return;
    const targetId = abandonTarget.saveId;
    await gameApi.storyDeleteSave(targetId);
    setAbandonTarget(null);
    if (snapshot?.story.saveId === targetId) {
      setSnapshot(null);
      clearCachedSnapshot();
    }
    await refreshSaves();
    setTab("adventure");
    setStage("saves");
    setToast("命卷已删除，空出的命卷位可以重新使用");
  });

  const resetGame = () => {
    setResetIntent("RESET");
  };

  const leaveDeath = () => runBusy(async () => {
    setSnapshot(null);
    clearCachedSnapshot();
    const list = await refreshSaves();
    setStage(list.saves.length > 0 ? "saves" : "create");
    setTab("adventure");
  });

  const logout = () => runBusy(async () => {
    characterInputVersion.current += 1;
    setPreparedCharacterRoute(null);
    await gameApi.logout();
    clearCachedSnapshot();
    // 待确认行动按账号隔离保留，重新登录原账号后才能继续恢复。
    setTurnRecovery(null);
    setRecoveryStatus(null);
    setDraft("");
    setAuth(null);
    setSnapshot(null);
    setSaves([]);
    setGenerationSettings(null);
    setRestartTargetId(null);
    setTurnLimitNotice(null);
    setStage("login");
    setSyncMode("server");
  });

  const saveProviderCredential = (provider: string, model: string, apiKey: string) => runBusy(async () => {
    setGenerationSettings(await gameApi.saveProviderCredential(provider, model, apiKey));
    setShowGenerationSettings(false);
    setToast("自己的推演密钥已验证并启用");
  });

  const activateProviderCredential = (provider: string, model: string) => runBusy(async () => {
    setGenerationSettings(await gameApi.saveGenerationSettings({ mode: "PERSONAL", provider, model }));
    setShowGenerationSettings(false);
    setToast("已切换为自己的推演密钥");
  });

  const usePlatformGeneration = (tierCode?: "LIGHT" | "BALANCED" | "DEEP") => runBusy(async () => {
    setGenerationSettings(await gameApi.saveGenerationSettings({ mode: "PLATFORM", tierCode }));
    setShowGenerationSettings(false);
    setToast("已切换为平台推演");
  });

  const deleteProviderCredential = (provider: string) => runBusy(async () => {
    setGenerationSettings(await gameApi.deleteProviderCredential(provider));
    setShowGenerationSettings(false);
    setToast("密钥已移除，现使用平台推演");
  });

  const savePassword = (currentPassword: string, newPassword: string) => runBusy(async () => {
    const nextAuth = await gameApi.setPassword(currentPassword, newPassword);
    setAuth(nextAuth);
    setShowPasswordSettings(false);
    setToast("登录密码已妥善保存");
  });

  const deleteAccount = () => runBusy(async () => {
    await gameApi.deleteAccount("确认注销");
    clearCachedSnapshot();
    setShowDeleteAccount(false);
    setAuth(null);
    setSnapshot(null);
    setGenerationSettings(null);
    setTurnLimitNotice(null);
    setStage("login");
    setSyncMode("server");
    setToast("账号与全部命卷已注销");
  });

  const copySupportGroup = async () => {
    if (!turnLimitNotice?.supportGroup) return;
    try {
      await navigator.clipboard.writeText(turnLimitNotice.supportGroup);
      setToast(`QQ群号 ${turnLimitNotice.supportGroup} 已复制`);
    } catch {
      setToast(`请手动记录QQ群号：${turnLimitNotice.supportGroup}`);
    }
  };

  const openTurnLimitSettings = () => {
    setTurnLimitNotice(null);
    setError(null);
    setTab("settings");
    if ((generationSettings?.personalProviders.length ?? 0) > 0) setShowGenerationSettings(true);
  };

  const creationBackLabel = restartTargetId ? "返回原来的命卷" : saves.length > 0 ? "返回命卷" : null;
  const headerTitle = stage === "create"
    ? "创建命卷"
    : stage === "game"
      ? game?.phase === "dead" && tab !== "chronicle"
        ? "此世已结"
        : tab === "adventure"
          ? "故事"
          : tab === "character"
            ? "人物"
            : tab === "chronicle"
              ? "前情"
              : "设置"
      : "";
  const headerlessGame = stage === "game" && (game?.phase === "dead" || game?.phase === "playing" && ["adventure", "character", "chronicle", "settings"].includes(tab));
  const leaveCreation = () => {
    if (restartTargetId) {
      void cancelRestart();
      return;
    }
    setError(null);
    setStage("saves");
  };

  if (["review", "all"].includes(designPreviewPage ?? "")) {
    return <DesignReviewHub />;
  }

  if (designPreviewPage && designPreviewPage !== "login") {
    return <DesignPreviewApp initialPage={designPreviewPage} />;
  }

  return (
    <div className="game-shell" data-composer-focused={isComposerFocused} data-design-preview={designPreview || undefined} data-entry={!showGameChrome} data-font-size={fontSize} data-headerless={headerlessGame || undefined} data-keyboard-open={keyboardOffset > 80} data-stage={stage} data-tab={stage === "game" ? tab : undefined} data-theme={actualTheme} onFocusCapture={(event) => { const target = event.target; if (target instanceof HTMLElement && target.matches("input, textarea, select") && !target.closest(".action-composer")) revealFormControl(target); }} style={{ "--keyboard-offset": `${keyboardOffset}px` } as CSSProperties}>
      <div className="paper-background" aria-hidden="true" />
      {!headerlessGame && <header className="game-header"><div className="game-header-inner"><div className="app-header-leading">{stage === "create" && creationBackLabel && <button aria-label={creationBackLabel} className="header-back-button" disabled={isBusy} onClick={leaveCreation} type="button"><ArrowLeftIcon aria-hidden="true" /><span>{restartTargetId ? "原命卷" : "命卷"}</span></button>}{["loading", "saves"].includes(stage) ? <span aria-label="问仙录" className="game-wordmark" role="img"><img alt="" aria-hidden="true" src="/assets/game/wenxian-wordmark-brush.png" /></span> : headerTitle ? <span className="app-context-title">{headerTitle}</span> : null}</div></div></header>}
      <div className="game-scroll" data-phase={game?.phase ?? stage} data-tab={stage === "game" ? tab : undefined} key={`${stage}-${tab}-${game?.turn ?? 0}`}><main className="screen-content" data-testid="game-content">
        {generationSettings && stage === "create" && <GenerationAccessCard generation={generationSettings} isBusy={isBusy} onOpen={() => setShowGenerationSettings(true)} />}
        {stage === "loading" && <LoadingView />}
        {stage === "login" && <LoginView accessStatus={designPreview ? null : accessStatus} announcement={designPreview ? null : loginAnnouncement} error={designPreview ? null : error} isBusy={designPreview ? false : isBusy} offline={designPreview ? false : syncMode === "offline"} onAuthenticate={designPreview ? () => setToast("设计预览：登录交互已触发") : authenticate} />}
        {stage === "saves" && <SaveSelectionView currentSaveId={game?.saveId} isBusy={isBusy} maxActiveSaves={maxActiveSaves} onBack={game ? () => setStage("game") : undefined} onCreate={createAdditionalSave} onDelete={setAbandonTarget} onLogout={() => void logout()} onOpen={(save) => void openSave(save)} saves={saves} />}
        {stage === "create" && bootstrap && <CharacterCreationView alternatives={nameAlternatives} bootstrap={bootstrap} draft={characterDraft} error={error} isBusy={isBusy} onCreate={createCharacter} onDraft={updateCharacterDraft} onGenerateName={generateName} onGenerateRoutes={generateCharacterRoutes} onLogout={() => void logout()} onPrepareOpening={prepareCharacterOpening} onSelectRoute={selectCharacterRoute} preparedRoute={preparedOpening} routeOptions={characterRoutes} selectedRouteId={selectedCharacterRouteId} />}
        {stage === "game" && game?.phase === "playing" && tab === "adventure" && <AdventureView actionLocked={actionLocked} recoveryPanel={currentRecovery ? <section aria-label="行动恢复" className="problem-notice story-problem-notice" data-category="SYSTEM" role="status">
          <h3>{actionLocked ? "行动结果待确认" : "原行动已恢复"}</h3>
          <p>{isCheckingRecovery ? "正在查询原行动结果…" : currentRecovery.message ?? "原行动已保留，正在确认这次行动的结果。"}</p>
          {actionLocked && <div className="turn-recovery-actions"><button disabled={isBusy || syncMode !== "server"} onClick={() => void continueStoryTurnRecovery()} type="button">继续查询</button>{recoveryStatus === "UNKNOWN" && <button disabled={isBusy || syncMode !== "server"} onClick={() => void continueStoryTurnRecovery(true)} type="button">重试原行动</button>}</div>}
        </section> : null} actionPanel={!isBusy || isCheckingRecovery ? <section aria-labelledby="turn-action-title" className="turn-action-zone" data-testid="turn-action-zone">
          <button disabled={actionLocked} aria-expanded={customActionOpen} aria-label="展开自定义行动，不采用推荐选项时，亲自描述下一步" className="turn-action-toggle" onClick={() => setCustomActionOpen((current) => !current)} type="button"><span className="turn-action-icon"><Pencil1Icon /></span><strong id="turn-action-title">或，写下自己的行动</strong><ChevronDownIcon aria-hidden="true" /></button>
          {customActionOpen && <form className="action-composer" data-has-draft={Boolean(draft.trim())} onSubmit={(event) => { event.preventDefault(); if (actionLocked || !draft.trim()) return; const textarea = event.currentTarget.querySelector("textarea"); textarea?.blur(); setIsComposerFocused(false); void takeTurn("TEXT", { content: draft.trim() }); }}><div className="action-composer-input"><textarea aria-label="自由行动" readOnly={actionLocked} maxLength={300} onBlur={() => window.setTimeout(() => setIsComposerFocused(false), 120)} onChange={(event) => updateActionDraft(event.currentTarget.value)} onFocus={(event) => { const target = event.currentTarget; setIsComposerFocused(true); revealFormControl(target, 160); }} placeholder="例如：先不登船，向茶摊老板打听昨夜靠岸的黑篷船。" rows={4} value={draft} />{draft && !actionLocked && <button aria-label="清空输入" className="clear-draft" onClick={() => updateActionDraft("")} type="button"><Cross2Icon /></button>}</div><div className="action-composer-footer"><span className="action-composer-meta">{actionLocked ? "确认原行动结果后才能提交新行动" : "草稿会在切换选项后保留"}</span><button aria-label="确认此行动" className="act-button" disabled={actionLocked || !draft.trim() || syncMode !== "server"} type="submit"><span>确认行动</span><EnterIcon /></button></div></form>}
        </section> : null} choices={choices} customActionActive={customActionOpen} error={error} game={game} generation={generationSettings} isBusy={isBusy && !isCheckingRecovery} offline={syncMode !== "server"} onChoice={(choiceId) => void takeTurn("CHOICE", { choiceId })} onChoiceMode={() => setCustomActionOpen(false)} pendingAction={pendingAction} />}
        {stage === "game" && game?.phase === "playing" && tab === "character" && <CharacterView game={game} isBusy={isBusy} onCleanup={setCleanupTarget} />}
        {stage === "game" && game?.phase === "playing" && tab === "chronicle" && <ChronicleView game={game} isBusy={isBusy} onCorrection={openCorrection} />}
        {stage === "game" && game?.phase === "playing" && tab === "settings" && <SettingsView email={email} fontSize={fontSize} generation={generationSettings} isBusy={isBusy} onDeleteAccount={() => setShowDeleteAccount(true)} onFontSize={setFontSize} onGenerationSettings={() => setShowGenerationSettings(true)} onLogout={() => void logout()} onPasswordSettings={() => setShowPasswordSettings(true)} onPlatform={() => void usePlatformGeneration()} onReset={resetGame} onSaves={() => setStage("saves")} passwordConfigured={auth?.passwordConfigured ?? false} version={bootstrap?.version ?? "版本同步中"} />}
        {stage === "game" && game?.phase === "dead" && tab !== "chronicle" && <DeathView game={game} isBusy={isBusy} onChronicle={() => setTab("chronicle")} onLogout={() => void logout()} onRebirth={() => void leaveDeath()} />}
        {stage === "game" && game?.phase === "dead" && tab === "chronicle" && <div className="death-chronicle"><ChronicleView game={game} /><button className="reset-button" onClick={() => setTab("adventure")} type="button">返回终局结算</button></div>}
      </main></div>
      {stage === "game" && game?.phase === "playing" && !isComposerFocused && <nav className="bottom-nav" aria-label="主要导航">{([["adventure", "故事"], ["character", "人物"], ["chronicle", "前情"], ["settings", "设置"]] as Array<[TabId, string]>).map(([id, label]) => <button aria-current={tab === id ? "page" : undefined} key={id} onClick={() => { setError(null); setTab(id); }} type="button"><TabIcon id={id} /><span>{label}</span></button>)}</nav>}
      {resetIntent && game && <ResetConfirmDialog game={game} intent={resetIntent} isBusy={isBusy} onCancel={() => setResetIntent(null)} onConfirm={() => { setResetIntent(null); prepareRestart(); }} />}
      {abandonTarget && <AbandonSaveDialog isBusy={isBusy} onCancel={() => setAbandonTarget(null)} onConfirm={() => void abandonSave()} save={abandonTarget} />}
      {pendingRelease && <ReleaseAnnouncementDialog note={pendingRelease} onClose={() => { const id = pendingRelease.id; setPendingRelease(null); setReleaseNotes((notes) => notes.map((note) => note.id === id ? { ...note, unread: false } : note)); void gameApi.markReleaseNoteRead(id); }} />}
      {turnLimitNotice && <TurnLimitDialog notice={turnLimitNotice} onClose={() => setTurnLimitNotice(null)} onCopyGroup={() => void copySupportGroup()} onOpenSettings={openTurnLimitSettings} />}
      {maintenanceNotice && <MaintenanceDialog notice={maintenanceNotice} onClose={() => setMaintenanceNotice(null)} />}
      {cleanupTarget && <RecordCleanupDialog isBusy={isBusy} onCancel={() => setCleanupTarget(null)} onConfirm={() => void confirmCleanup()} target={cleanupTarget} />}
      {showGenerationSettings && generationSettings && <GenerationSettingsDialog error={error} isBusy={isBusy} onActivate={(provider, model) => void activateProviderCredential(provider, model)} onCancel={() => setShowGenerationSettings(false)} onDelete={(provider) => void deleteProviderCredential(provider)} onSave={(provider, model, key) => void saveProviderCredential(provider, model, key)} onUsePlatform={(tierCode) => void usePlatformGeneration(tierCode)} settings={generationSettings} />}
      {showPasswordSettings && <PasswordDialog configured={auth?.passwordConfigured ?? false} mandatory={auth?.authenticated === true && !auth.passwordConfigured} error={error} isBusy={isBusy} onCancel={() => setShowPasswordSettings(false)} onSave={(current, next) => void savePassword(current, next)} />}
      {showDeleteAccount && <DeleteAccountDialog isBusy={isBusy} onCancel={() => setShowDeleteAccount(false)} onConfirm={() => void deleteAccount()} />}
      {showCorrection && <StoryCorrectionDialog error={error} isBusy={isBusy} onCancel={closeCorrection} onConfirm={() => void confirmCorrection()} onEdit={() => { setCorrectionPreview(null); setError(null); }} onPreview={(instruction) => void previewCorrection(instruction)} preview={correctionPreview} />}
      {isCreatingCharacter && stage === "create" && <DestinyLoadingOverlay name={characterDraft.playerName} />}
      {toast && <div className="save-toast" role="status"><span className="agent-dot" />{toast}</div>}
    </div>
  );
}
