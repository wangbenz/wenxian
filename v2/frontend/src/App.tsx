import {
  ArrowRightIcon,
  BackpackIcon,
  BookmarkIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ClockIcon,
  Cross1Icon,
  EnterIcon,
  ExitIcon,
  GearIcon,
  GlobeIcon,
  HomeIcon,
  LightningBoltIcon,
  MoonIcon,
  PersonIcon,
  ReaderIcon,
  ReloadIcon,
  SewingPinFilledIcon,
  SunIcon,
} from "@radix-ui/react-icons";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api, toProblem } from "./api";
import { previewBootstrap, previewChronicle, previewSave, previewWorld } from "./preview-fixture";
import type {
  AuthResponse,
  AiSettings,
  Bootstrap,
  CreateSavePayload,
  FontMode,
  OriginProposal,
  KnownLocation,
  LegacyChapter,
  LegacyLife,
  RaceCode,
  ReleaseNotice,
  SaveAggregate,
  TabId,
  ThemeMode,
  TurnOperation,
  TurnSummary,
  UiProblem,
} from "./types";

const RACE_NAMES: Record<RaceCode, string> = { HUMAN: "人族", DEMON: "妖族", SPIRIT: "灵族" };
const RACE_MARKS: Record<RaceCode, string> = { HUMAN: "人", DEMON: "妖", SPIRIT: "灵" };
const REALM_PHASE_NAMES: Record<string, string> = {
  INITIAL: "初始", EARLY: "初期", MIDDLE: "中期", LATE: "后期", PEAK: "圆满",
};
const ABILITY_TYPE_NAMES: Record<string, string> = {
  TALENT: "天赋", TECHNIQUE: "功法", SPELL: "术法", MARTIAL: "武技", BLOODLINE: "血脉",
};
const EVENT_STAGE_NAMES: Record<string, string> = {
  SEED: "暗伏", SIGN: "征兆初显", BUILDUP: "暗流积聚", ERUPTION: "事变爆发",
  TURN: "局势转折", RESULT: "尘埃将定", AFTERMATH: "余波未息",
};
const LOCATION_TYPE_NAMES: Record<string, string> = {
  WORLD: "世界", DOMAIN: "大域", REGION: "地域", SETTLEMENT: "城镇",
  SCENE: "场景", SITE: "地点", SECT: "宗门", ROUTE: "道路",
};
const RELATIONSHIP_NAMES: Record<string, string> = {
  ACQUAINTANCE: "相识", ALLY: "同伴", RIVAL: "对手", MENTOR: "引路人",
  DEBTOR: "欠你人情", CREDITOR: "你欠其人情", ENEMY: "敌对", COMPANION: "同行者",
};
const ROOTS: Record<RaceCode, string[]> = {
  HUMAN: ["寒门遗孤", "药铺学徒", "商旅后人"],
  DEMON: ["山野走兽", "失群幼妖", "旧祠异种"],
  SPIRIT: ["矿石生灵", "草木初醒", "器物化识"],
};
const NAMES: Record<RaceCode, Record<string, string[]>> = {
  HUMAN: { 男: ["沈砚", "陆行舟", "谢临川"], 女: ["苏照月", "宁知微", "叶听澜"], 不设定: ["闻溪", "顾长明", "林问秋"] },
  DEMON: { 男: ["岑野", "乌沉", "玄离"], 女: ["绯萝", "青芜", "照霜"], 不设定: ["长风", "空青", "夜弦"] },
  SPIRIT: { 男: ["石昀", "玄砚", "长庚"], 女: ["宁照微", "青珞", "霁华"], 不设定: ["无尘", "回音", "照夜"] },
};

const previewMode = import.meta.env.DEV && new URLSearchParams(window.location.search).has("preview");

function randomId() {
  return typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `v2-${Date.now()}-${Math.random()}`;
}

function readPreference<T extends string>(key: string, fallback: T): T {
  return (localStorage.getItem(key) as T | null) ?? fallback;
}

function streamOperation(operationId: string, onUpdate: (operation: TurnOperation) => void) {
  return new Promise<TurnOperation>((resolve, reject) => {
    const stream = new EventSource(api.operationEventsUrl(operationId));
    let settled = false;
    const finish = (operation: TurnOperation) => {
      onUpdate(operation);
      if (!["SUCCEEDED", "REJECTED", "FAILED"].includes(operation.status)) return;
      settled = true;
      stream.close();
      resolve(operation);
    };
    stream.addEventListener("operation", (event) => {
      try {
        finish(JSON.parse((event as MessageEvent).data) as TurnOperation);
      } catch (invalidEvent) {
        if (!settled) {
          settled = true;
          stream.close();
          reject(invalidEvent);
        }
      }
    });
    stream.onerror = () => {
      if (settled) return;
      settled = true;
      stream.close();
      reject(new Error("推演进度连接中断"));
    };
  });
}

function chapterTitle(turn?: TurnSummary) {
  if (!turn) return "初临青岐";
  if (turn.playerAction.includes("等待")) return "静候时移";
  if (turn.playerAction.includes("前往")) return "路入新境";
  return turn.outcomeSummary?.split(/[，。；]/)[0] || `第${turn.turnNumber}回`;
}

function travelDuration(minutes: number) {
  if (minutes < 60) return `${minutes}分钟`;
  if (minutes % 60 === 0) return `${minutes / 60}小时`;
  return `${Math.floor(minutes / 60)}小时${minutes % 60}分钟`;
}

function knownLocationDescription(location: KnownLocation, currentLocationId: string) {
  if (location.id === currentLocationId) return "你此刻正在这里";
  if (location.directTravelMinutes != null) return `已有直达道路，约需${travelDuration(location.directTravelMinutes)}`;
  if (location.type === "REGION" || location.type === "DOMAIN" || location.type === "WORLD") {
    return "已知地域范围，需从具体道路逐步前往";
  }
  return location.accessible ? "已经知晓，当前没有直达路线" : "已经听闻，尚无通路";
}

function openingNarrative(save: SaveAggregate) {
  const raceLine = save.protagonist.race === "HUMAN"
    ? "城门外的尘土贴着鞋面，来往商旅没人知道你的名字。"
    : save.protagonist.race === "DEMON"
      ? "边地的风带来人烟与铁器气味，你第一次离族群如此之近。"
      : "岩层深处的震动停了片刻，你第一次确认那些回响并不全属于石头。";
  const rootDescription = save.protagonist.rootDescription.replace(/[。！？]+$/u, "");
  return `${raceLine}\n\n${save.origin.openingSituation}\n\n你是${rootDescription}。如今身在${save.characterState.locationName}，仍只是${save.characterState.realmDisplayName}之初。${save.protagonist.lifeGoal}——这是命卷记下的第一件事。尚未偿还的牵连是：${save.origin.unpaidDebt}`;
}

function periodLabel(minute: number) {
  const hour = Math.floor(minute / 60);
  if (hour < 5) return "夜深";
  if (hour < 8) return "清晨";
  if (hour < 11) return "上午";
  if (hour < 14) return "正午";
  if (hour < 18) return "午后";
  if (hour < 21) return "入夜";
  return "夜深";
}

function formatWorldTime(save: SaveAggregate) {
  const date = save.worldDate;
  return `绝天后${date.year}年 · 第${date.day}日 · ${periodLabel(date.minuteOfDay)}`;
}

function relationshipImpression(relationship: SaveAggregate["relationships"][number]) {
  const impressions: string[] = [];
  if (relationship.trustLevel >= 2) impressions.push("已有信任");
  if (relationship.trustLevel <= -2) impressions.push("彼此提防");
  if (relationship.closenessLevel >= 2) impressions.push("往来亲近");
  if (relationship.fearLevel >= 2) impressions.push("心存畏惧");
  if (relationship.interestLevel >= 1) impressions.push("仍在关注你");
  return impressions.length ? impressions.join(" · ") : "关系尚浅，仍需相处确认";
}

function wait(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

export function App() {
  const [stage, setStage] = useState<"boot" | "login" | "password" | "create" | "game">(previewMode ? "game" : "boot");
  const [initialPassword, setInitialPassword] = useState("");
  const [bootstrap, setBootstrap] = useState<Bootstrap | null>(previewMode ? previewBootstrap : null);
  const [auth, setAuth] = useState<AuthResponse | null>(previewMode ? {
    authenticated: true,
    newUser: false,
    user: { userId: "preview-user", email: "界面预览" },
    passwordConfigured: true,
    nextStep: "RESUME_GAME",
  } : null);
  const [saves, setSaves] = useState<SaveAggregate[]>(previewMode ? [previewSave] : []);
  const [current, setCurrent] = useState<SaveAggregate | null>(previewMode ? previewSave : null);
  const [world, setWorld] = useState<KnownLocation[]>(previewMode ? previewWorld : []);
  const [chronicle, setChronicle] = useState<TurnSummary[]>(previewMode ? previewChronicle : []);
  const [chronicleHasMore, setChronicleHasMore] = useState(false);
  const [chronicleLoading, setChronicleLoading] = useState(false);
  const [tab, setTab] = useState<TabId>("adventure");
  const [problem, setProblem] = useState<UiProblem | null>(null);
  const [busy, setBusy] = useState(false);
  const [operation, setOperation] = useState<TurnOperation | null>(null);
  const [saveShelfOpen, setSaveShelfOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [legacyArchives, setLegacyArchives] = useState<LegacyLife[]>([]);
  const [legacyDetail, setLegacyDetail] = useState<{ life: LegacyLife; chapters: LegacyChapter[] } | null>(null);
  const [archiveTarget, setArchiveTarget] = useState<SaveAggregate | null>(null);
  const [endingTarget, setEndingTarget] = useState<SaveAggregate | null>(null);
  const [publicNotice, setPublicNotice] = useState<ReleaseNotice | null>(null);
  const [pendingNotice, setPendingNotice] = useState<ReleaseNotice | null>(null);
  const [composerFocused, setComposerFocused] = useState(false);
  const [draft, setDraft] = useState("");
  const [theme, setTheme] = useState<ThemeMode>(() => readPreference("wenxian-v2-theme", "system"));
  const [fontMode, setFontMode] = useState<FontMode>(() => readPreference("wenxian-v2-font", "standard"));

  useEffect(() => {
    if (previewMode) return;
    void boot();
  }, []);

  useEffect(() => {
    localStorage.setItem("wenxian-v2-theme", theme);
    localStorage.setItem("wenxian-v2-font", fontMode);
  }, [theme, fontMode]);

  useEffect(() => {
    if (!current) return;
    const key = `wenxian-v2-draft:${current.save.id}`;
    setDraft(sessionStorage.getItem(key) ?? "");
  }, [current?.save.id]);

  async function boot() {
    setProblem(null);
    try {
      const [nextBootstrap, nextAuth, notice] = await Promise.all([
        api.bootstrap(), api.me(), api.publicNotice().catch(() => ({ notice: null })),
      ]);
      setBootstrap(nextBootstrap);
      setAuth(nextAuth);
      setPublicNotice(notice.notice);
      if (!nextAuth.authenticated) {
        setStage("login");
        return;
      }
      if (!nextAuth.passwordConfigured) {
        setStage("password");
        return;
      }
      await loadSaves();
      void loadPendingNotice();
    } catch (error) {
      // Initial connectivity issues should not make the untouched login screen
      // look like a failed player action. A real login/register request will
      // still surface the detailed problem through authenticate().
      setProblem(null);
      setStage("login");
    }
  }

  async function loadPendingNotice() {
    if (previewMode) return;
    try {
      const response = await api.pendingNotice();
      setPendingNotice(response.notice);
    } catch {
      // A notice must never block entering an existing save.
    }
  }

  async function loadSaves(preferredId?: string) {
    const nextSaves = await api.saves();
    setSaves(nextSaves);
    const activeSaves = nextSaves.filter((entry) => entry.save.status === "ACTIVE");
    if (nextSaves.length === 0) {
      setCurrent(null);
      setStage("create");
      return;
    }
    const lastId = preferredId ?? localStorage.getItem("wenxian-v2-current-save");
    const next = activeSaves.find((entry) => entry.save.id === lastId)
      ?? activeSaves[0]
      ?? nextSaves.find((entry) => entry.save.id === lastId)
      ?? nextSaves[0];
    await openSave(next);
    await recoverActiveOperation(nextSaves);
  }

  async function openSave(save: SaveAggregate) {
    setProblem(null);
    setBusy(true);
    try {
      const [nextSave, nextWorld, nextChronicle] = await Promise.all([
        previewMode ? Promise.resolve(save) : api.save(save.save.id),
        previewMode ? Promise.resolve(previewWorld) : api.world(save.save.id),
        previewMode ? Promise.resolve(previewChronicle) : api.chronicle(save.save.id),
      ]);
      setCurrent(nextSave);
      setWorld(nextWorld);
      setChronicle(nextChronicle);
      setChronicleHasMore(!previewMode && nextChronicle.length === 60 && nextChronicle.at(-1)!.turnNumber > 1);
      localStorage.setItem("wenxian-v2-current-save", nextSave.save.id);
      setSaveShelfOpen(false);
      setTab("adventure");
      setStage("game");
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function refreshCurrent() {
    if (!current || previewMode) return;
    await refreshSave(current.save.id);
  }

  async function refreshSave(saveId: string) {
    if (previewMode) return;
    const [nextSave, nextWorld, nextChronicle] = await Promise.all([
      api.save(saveId),
      api.world(saveId),
      api.chronicle(saveId),
    ]);
    setCurrent(nextSave);
    setWorld(nextWorld);
    setChronicle(nextChronicle);
    setChronicleHasMore(nextChronicle.length === 60 && nextChronicle.at(-1)!.turnNumber > 1);
    setSaves((entries) => entries.map((entry) => entry.save.id === saveId ? nextSave : entry));
    localStorage.setItem("wenxian-v2-current-save", saveId);
  }

  async function followOperation(initial: TurnOperation): Promise<boolean> {
    let nextOperation = initial;
    setOperation(nextOperation);
    sessionStorage.setItem("wenxian-v2-active-operation", nextOperation.id);
    if (!["SUCCEEDED", "REJECTED", "FAILED"].includes(nextOperation.status)) {
      try {
        nextOperation = await streamOperation(nextOperation.id, setOperation);
      } catch {
        while (!["SUCCEEDED", "REJECTED", "FAILED"].includes(nextOperation.status)) {
          await wait(900);
          nextOperation = await api.operation(nextOperation.id);
          setOperation(nextOperation);
        }
      }
    }
    sessionStorage.removeItem("wenxian-v2-active-operation");
    if (nextOperation.status === "SUCCEEDED") {
      await refreshSave(nextOperation.saveId);
      return true;
    }
    setProblem({
      code: nextOperation.errorCode ?? "TURN_NOT_COMPLETED",
      category: nextOperation.status === "REJECTED" ? "BUSINESS" : "TECHNICAL",
      message: nextOperation.errorMessage ?? "本次行动没有完成，命卷状态没有改变。",
      suggestions: nextOperation.errorSuggestions?.length
        ? nextOperation.errorSuggestions
        : nextOperation.status === "REJECTED" ? ["根据当前事实重新描述行动"] : ["稍后重试本次行动"],
      problemReference: nextOperation.problemReference,
    });
    return false;
  }

  async function recoverActiveOperation(knownSaves: SaveAggregate[]) {
    if (previewMode) return;
    try {
      const recovered = (await api.activeOperation()).operation;
      if (!recovered) {
        sessionStorage.removeItem("wenxian-v2-active-operation");
        return;
      }
      const ownerSave = knownSaves.find((entry) => entry.save.id === recovered.saveId);
      if (ownerSave) await openSave(ownerSave);
      setBusy(true);
      await followOperation(recovered);
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setOperation(null);
      setBusy(false);
    }
  }

  async function authenticate(mode: "login" | "register", email: string, password: string) {
    setBusy(true);
    setProblem(null);
    try {
      const nextAuth = mode === "login" ? await api.login(email, password) : await api.register(email, password);
      setAuth(nextAuth);
      if (!nextAuth.passwordConfigured) {
        setInitialPassword(password);
        setStage("password");
        return;
      }
      if (!bootstrap) setBootstrap(await api.bootstrap());
      await loadSaves();
      void loadPendingNotice();
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function changePassword(nextPassword: string) {
    setBusy(true);
    setProblem(null);
    try {
      const nextAuth = await api.changePassword(initialPassword, nextPassword);
      setAuth(nextAuth);
      setInitialPassword("");
      if (!bootstrap) setBootstrap(await api.bootstrap());
      await loadSaves();
      void loadPendingNotice();
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function createSave(payload: CreateSavePayload) {
    setBusy(true);
    setProblem(null);
    try {
      const created = previewMode ? previewSave : await api.createSave(payload);
      setSaves((entries) => [...entries, created]);
      await openSave(created);
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function submitAction(action: { actionType: "WAIT" | "TRAVEL" | "FREE"; actionText: string; durationMinutes?: number; targetLocationId?: string }): Promise<boolean> {
    if (!current || operation) return false;
    if (current.save.status !== "ACTIVE") {
      setProblem({
        code: "SAVE_ALREADY_ENDED",
        category: "BUSINESS",
        message: "这份命卷已经走到终点，不能再继续行动。",
        suggestions: ["从命卷架开启另一份命卷", "在纪事中回看此生经历"],
      });
      return false;
    }
    setProblem(null);
    setBusy(true);
    try {
      if (previewMode) {
        const simulated: TurnOperation = {
          id: randomId(), requestId: randomId(), saveId: current.save.id,
          expectedStateVersion: current.save.stateVersion, status: "RESOLVING", stage: "RESOLVING",
          actionType: action.actionType, actionText: action.actionText,
        };
        setOperation(simulated);
        await wait(900);
        setOperation({ ...simulated, status: "COMMITTING", stage: "COMMITTING" });
        await wait(700);
        setOperation(null);
        return true;
      }
      const nextOperation = await api.submitAction(current.save.id, {
        requestId: randomId(),
        expectedStateVersion: current.save.stateVersion,
        ...action,
      });
      return await followOperation(nextOperation);
    } catch (error) {
      setProblem(toProblem(error));
      return false;
    } finally {
      setOperation(null);
      setBusy(false);
    }
  }

  function saveDraft(next: string) {
    setDraft(next);
    if (current) sessionStorage.setItem(`wenxian-v2-draft:${current.save.id}`, next);
  }

  async function submitFreeAction() {
    if (!draft.trim()) return;
    const actionText = draft.trim();
    const completed = await submitAction({ actionType: "FREE", actionText });
    if (completed) {
      saveDraft("");
      setComposerFocused(false);
    }
  }

  async function loadOlderChronicle() {
    if (!current || chronicleLoading || !chronicleHasMore || previewMode) return;
    const oldestTurn = chronicle.at(-1)?.turnNumber;
    if (!oldestTurn) return;
    setChronicleLoading(true);
    try {
      const older = await api.chronicle(current.save.id, 60, oldestTurn);
      setChronicle((entries) => [...entries, ...older.filter((candidate) =>
        !entries.some((entry) => entry.id === candidate.id))]);
      setChronicleHasMore(older.length === 60 && older.at(-1)!.turnNumber > 1);
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setChronicleLoading(false);
    }
  }

  async function logout() {
    if (!previewMode) await api.logout();
    setSettingsOpen(false);
    setCurrent(null);
    setSaves([]);
    setAuth(null);
    setPendingNotice(null);
    setStage("login");
  }

  async function openSaveShelf() {
    setSaveShelfOpen(true);
    if (previewMode) return;
    try {
      setLegacyArchives(await api.legacyArchives());
    } catch (error) {
      setProblem(toProblem(error));
    }
  }

  async function openLegacyArchive(life: LegacyLife) {
    setBusy(true);
    try {
      const chapters = previewMode ? [] : await api.legacyChapters(life.id);
      setSaveShelfOpen(false);
      setLegacyDetail({ life, chapters });
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function archiveSave(characterName: string) {
    if (!archiveTarget || previewMode) return;
    setBusy(true);
    setProblem(null);
    try {
      await api.archiveSave(archiveTarget.save.id, characterName);
      setArchiveTarget(null);
      setSaveShelfOpen(false);
      localStorage.removeItem("wenxian-v2-current-save");
      await loadSaves();
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function endLife(characterName: string, endingType: "RETIREMENT" | "LEGACY") {
    if (!endingTarget || previewMode) return;
    setBusy(true);
    setProblem(null);
    try {
      const ended = await api.endLife(endingTarget.save.id, characterName, endingType);
      setCurrent(ended);
      setSaves((entries) => entries.map((entry) => entry.save.id === ended.save.id ? ended : entry));
      setEndingTarget(null);
      setSaveShelfOpen(false);
      setTab("adventure");
    } catch (error) {
      setProblem(toProblem(error));
    } finally {
      setBusy(false);
    }
  }

  async function dismissNotice(notice: ReleaseNotice) {
    setPendingNotice(null);
    if (!previewMode) {
      try { await api.markNoticeRead(notice.id); } catch { /* It may appear again after a failed receipt write. */ }
    }
  }

  const resolvedTheme = theme === "system"
    ? window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "night" : "paper"
    : theme;
  const freeMode = composerFocused;

  return (
    <main className="app-shell" data-font={fontMode} data-theme={resolvedTheme}>
      <div className="wash-layer" aria-hidden="true" />
      {previewMode && <div className="preview-ribbon">界面预览 · 不写入正式命卷</div>}
      {stage === "boot" && <BootView problem={problem} />}
      {stage === "login" && <LoginView busy={busy} notice={publicNotice} onAuthenticate={authenticate} problem={problem?.code === "GAME_UPDATING" ? null : problem} />}
      {stage === "password" && <PasswordView busy={busy} onChange={changePassword} problem={problem} />}
      {stage === "create" && bootstrap && (
        <CreationView bootstrap={bootstrap} busy={busy} onCreate={createSave} problem={problem} />
      )}
      {stage === "game" && current && (
        <>
          <GameHeader
            onOpenSaves={openSaveShelf}
            onOpenSettings={() => setSettingsOpen(true)}
            save={current}
          />
          {operation && <GlobalOperation operation={operation} saveName={current.protagonist.name} />}
          <section className="page-viewport">
            {tab === "adventure" && (
              <AdventureView
                chronicle={chronicle}
                current={current}
                draft={draft}
                freeMode={freeMode}
                onBlur={() => window.setTimeout(() => setComposerFocused(false), 220)}
                onChangeDraft={saveDraft}
                onExitInput={() => setComposerFocused(false)}
                onFocus={() => setComposerFocused(true)}
                onSubmitFree={submitFreeAction}
                onSubmitQuick={submitAction}
                onOpenSaves={openSaveShelf}
                operation={operation}
                problem={problem}
                world={world}
              />
            )}
            {tab === "character" && <CharacterView save={current} />}
            {tab === "chronicle" && <ChronicleView entries={chronicle} hasMore={chronicleHasMore} loading={chronicleLoading} onLoadMore={loadOlderChronicle} save={current} />}
            {tab === "world" && <WorldView chronicle={chronicle} locations={world} save={current} />}
          </section>
          <BottomNav current={tab} hidden={freeMode} onChange={setTab} />
          {saveShelfOpen && (
            <SaveShelf
              busy={busy}
              currentId={current.save.id}
              onClose={() => setSaveShelfOpen(false)}
              onCreate={() => { setSaveShelfOpen(false); setStage("create"); }}
              onOpen={openSave}
              onOpenLegacy={openLegacyArchive}
              onArchive={setArchiveTarget}
              onEnd={setEndingTarget}
              legacy={legacyArchives}
              maxActiveSaves={bootstrap?.maxActiveSaves ?? 2}
              saves={saves}
            />
          )}
          {settingsOpen && (
            <SettingsSheet
              auth={auth}
              bootstrap={bootstrap}
              fontMode={fontMode}
              onClose={() => setSettingsOpen(false)}
              onFont={setFontMode}
              onLogout={logout}
              onTheme={setTheme}
              theme={theme}
            />
          )}
          {legacyDetail && <LegacyArchiveSheet detail={legacyDetail} onClose={() => setLegacyDetail(null)} />}
          {archiveTarget && <ArchiveSaveModal busy={busy} onClose={() => setArchiveTarget(null)} onConfirm={archiveSave} save={archiveTarget} />}
          {endingTarget && <EndLifeModal busy={busy} onClose={() => setEndingTarget(null)} onConfirm={endLife} save={endingTarget} />}
        </>
      )}
      {pendingNotice && <AnnouncementModal notice={pendingNotice} onClose={() => void dismissNotice(pendingNotice)} />}
      {stage === "login" && problem?.code === "GAME_UPDATING" && <MaintenanceModal onClose={() => setProblem(null)} problem={problem} />}
    </main>
  );
}

function PasswordView({ busy, problem, onChange }: { busy: boolean; problem: UiProblem | null; onChange: (password: string) => void }) {
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  return <div className="entry-layout"><section className="entry-card login-card"><p className="eyebrow">账号保护</p><h1>先设置新密码</h1><p>这是旧账号第一次使用新版登录。设置新密码后才会进入命卷，已有角色和记录不会改变。</p><form onSubmit={(event) => { event.preventDefault(); if (password === confirmation) onChange(password); }}><label><span>新密码</span><input autoComplete="new-password" minLength={8} onChange={(event) => setPassword(event.target.value)} placeholder="八至七十二个字符" required type="password" value={password} /></label><label><span>再次输入</span><input autoComplete="new-password" minLength={8} onChange={(event) => setConfirmation(event.target.value)} placeholder="再次输入新密码" required type="password" value={confirmation} />{confirmation && confirmation !== password && <small className="field-error">两次密码不一致</small>}</label><button className="primary-button" disabled={busy || password.length < 8 || password !== confirmation} type="submit">{busy ? "正在保存…" : "设置密码并继续"}<ArrowRightIcon /></button></form>{problem && <ProblemCard compact problem={problem} />}</section></div>;
}

function BootView({ problem }: { problem: UiProblem | null }) {
  return <div className="entry-layout"><section className="entry-card boot-card"><span className="ink-loader" /><p className="eyebrow">READING THE JADE SLIP</p><h1>正在检索命卷</h1><p>核对账号、内容版本与命卷状态，已有经历不会因此改变。</p>{problem && <ProblemCard problem={problem} />}</section></div>;
}

function LoginView({ busy, notice, problem, onAuthenticate }: { busy: boolean; notice: ReleaseNotice | null; problem: UiProblem | null; onAuthenticate: (mode: "login" | "register", email: string, password: string) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  function submit(event: FormEvent) {
    event.preventDefault();
    if (mode === "register" && password !== confirmation) return;
    onAuthenticate(mode, email.trim(), password);
  }
  return <div className="entry-layout">
    <section className="brand-intro"><div className="brand-seal">问</div><p>绝天之变后三千年</p><h1>问仙录</h1><blockquote>你的每次行动，都会成为这个世界的一部分。</blockquote><div className="entry-features" aria-label="游戏特点"><span>自由行动</span><span>世界演化</span><span>单机存档</span></div><aside className="entry-world-hook"><small>玄墟界 · 青岐州</small><strong>人、妖、灵三族皆可入世</strong><p>从不同身份醒来，在持续变化的边州中修行、生存与抉择。</p></aside></section>
    <section className="entry-card login-card">
      <p className="eyebrow">{mode === "login" ? "账号登录" : "创建账号"}</p>
      <h2>{mode === "login" ? "返回玄墟界" : "开始新游戏"}</h2>
      <p>{mode === "login" ? "登录后继续当前角色、世界时间与未完成的行动。" : "注册后选择种族、姓名与此生所求，建立独立存档。"}</p>
      <div className="segmented" role="tablist"><button aria-selected={mode === "login"} onClick={() => setMode("login")} type="button">登录</button><button aria-selected={mode === "register"} onClick={() => setMode("register")} type="button">注册</button></div>
      <form onSubmit={submit}>
        <label><span>邮箱</span><input autoComplete="email" inputMode="email" onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" required type="email" value={email} /></label>
        <label><span>密码</span><input autoComplete={mode === "login" ? "current-password" : "new-password"} minLength={mode === "register" ? 8 : 1} onChange={(event) => setPassword(event.target.value)} placeholder={mode === "login" ? "输入登录密码" : "至少 8 个字符"} required type="password" value={password} /></label>
        {mode === "register" && <label><span>确认密码</span><input autoComplete="new-password" minLength={8} onChange={(event) => setConfirmation(event.target.value)} placeholder="再次输入密码" required type="password" value={confirmation} />{confirmation && confirmation !== password && <small className="field-error">两次密码不一致</small>}</label>}
        <button className="primary-button" disabled={busy || !email || !password || (mode === "register" && (password.length < 8 || confirmation !== password))} type="submit">{busy ? "正在验明身份…" : mode === "login" ? "继续游戏" : "创建账号"}<ArrowRightIcon /></button>
      </form>
      {problem && <ProblemCard compact problem={problem} />}
      {notice && <details className="login-notice"><summary><span>V{notice.version}</span><strong>{notice.title}</strong><ChevronRightIcon /></summary><p>{notice.content}</p></details>}
      <small className="entry-note">V0.2 使用全新的世界与存档；旧版角色仍会保留在只读档案中。</small>
    </section>
  </div>;
}

function CreationView({ bootstrap, busy, problem, onCreate }: { bootstrap: Bootstrap; busy: boolean; problem: UiProblem | null; onCreate: (payload: CreateSavePayload) => void }) {
  const [step, setStep] = useState(0);
  const [draft, setDraft] = useState<CreateSavePayload>({ name: "", genderExpression: "", race: "HUMAN", rootType: "", rootDescription: "", lifeGoal: "", difficulty: "NORMAL" });
  const [nameIndex, setNameIndex] = useState(0);
  const [originProposal, setOriginProposal] = useState<OriginProposal | null>(null);
  const [originBusy, setOriginBusy] = useState(false);
  const [originProblem, setOriginProblem] = useState<UiProblem | null>(null);
  const canContinue = step === 0 ? Boolean(draft.race && draft.genderExpression)
    : step === 1 ? draft.name.trim().length >= 2
      : step === 2 ? Boolean(draft.rootType && draft.rootDescription.trim().length >= 2 && draft.lifeGoal.trim().length >= 2)
        : Boolean(draft.originProposalId && draft.fateCandidateId);
  const rootOptions = ROOTS[draft.race];
  const starterTalents = bootstrap.starterTalents.filter((talent) => talent.raceScope === draft.race || talent.raceScope === "ANY").slice(0, 3);

  function generateName() {
    const pool = NAMES[draft.race][draft.genderExpression] ?? NAMES[draft.race]["不设定"];
    const nextIndex = (nameIndex + 1) % pool.length;
    setNameIndex(nextIndex);
    setDraft((value) => ({ ...value, name: pool[nextIndex] }));
  }

  async function revealFates() {
    if (!canContinue || originBusy) return;
    setOriginBusy(true);
    setOriginProblem(null);
    try {
      const proposal = await api.generateFates({
        name: draft.name,
        genderExpression: draft.genderExpression,
        race: draft.race,
        rootType: draft.rootType,
        rootDescription: draft.rootDescription,
        lifeGoal: draft.lifeGoal,
        difficulty: draft.difficulty,
      });
      setOriginProposal(proposal);
      setDraft((value) => ({ ...value, originProposalId: proposal.proposalId, fateCandidateId: undefined }));
      setStep(3);
    } catch (error) {
      setOriginProblem(toProblem(error));
    } finally {
      setOriginBusy(false);
    }
  }

  const selectedFate = originProposal?.candidates.find((candidate) => candidate.id === draft.fateCandidateId);

  return <div className="creation-layout">
    {originBusy && <div className="creation-thinking" role="status"><span className="ink-loader" /><p className="eyebrow">READING THREE PATHS</p><h2>正在推演命格</h2><span>校对种族、本体、旧债与开场处境……</span></div>}
    <header className="creation-header"><div><p className="eyebrow">FIXED WORLD · SINGLE PLAYER</p><h1>玄墟界</h1><span>绝天之变后三千年 · 青岐州</span></div><div className="step-counter">{step + 1}<small>/4</small></div></header>
    <div className="creation-progress"><i style={{ width: `${(step + 1) * 25}%` }} /></div>
    <section className="creation-panel">
      {step === 0 && <><p className="eyebrow">WHO AWAKENS</p><h2>此身为何物</h2><p className="panel-lead">种族决定你的起点、寿命尺度与修行称谓，不决定一生的结局。</p><div className="race-options">{bootstrap.races.map((race) => <button aria-pressed={draft.race === race} key={race} onClick={() => setDraft((value) => ({ ...value, race, rootType: "" }))} type="button"><span>{RACE_MARKS[race]}</span><strong>{RACE_NAMES[race]}</strong><small>{race === "HUMAN" ? "从亲缘、生业与秩序中求道" : race === "DEMON" ? "从族群、领地与化形中求存" : "从本体、记忆与依附中醒来"}</small></button>)}</div><fieldset><legend>性别表达</legend><div className="segmented">{["男", "女", "不设定"].map((gender) => <button aria-selected={draft.genderExpression === gender} key={gender} onClick={() => setDraft((value) => ({ ...value, genderExpression: gender }))} type="button">{gender}</button>)}</div></fieldset></>}
      {step === 1 && <><p className="eyebrow">A NAME IN THE RECORD</p><h2>如何称呼</h2><p className="panel-lead">随机雅名会同时参考种族与性别；你也可以写下自己的名字。</p><label className="large-field"><span>姓名</span><input autoFocus maxLength={20} onChange={(event) => setDraft((value) => ({ ...value, name: event.target.value }))} placeholder="写下姓名" value={draft.name} /></label><button className="secondary-button" onClick={generateName} type="button"><ReloadIcon />随机雅名</button></>}
      {step === 2 && <><p className="eyebrow">ROOT AND DESIRE</p><h2>从何处来，为何而活</h2><p className="panel-lead">这些内容会成为命卷事实，但不会凭空赠予高阶力量。</p><fieldset><legend>根源类型</legend><div className="root-options">{rootOptions.map((root) => <button aria-selected={draft.rootType === root} key={root} onClick={() => setDraft((value) => ({ ...value, rootType: root }))} type="button">{root}</button>)}</div></fieldset><label><span>根源描述</span><textarea maxLength={300} onChange={(event) => setDraft((value) => ({ ...value, rootDescription: event.target.value }))} placeholder={draft.race === "SPIRIT" ? "例如：照骨矿深处一块听了百年镐声的矿石……" : "用一两句话写下这具身体从哪里来……"} rows={4} value={draft.rootDescription} /></label><label><span>此生所求</span><textarea maxLength={160} onChange={(event) => setDraft((value) => ({ ...value, lifeGoal: event.target.value }))} placeholder="你最想守住、得到或弄清什么？" rows={3} value={draft.lifeGoal} /></label><fieldset><legend>命途难度</legend><div className="difficulty-options"><button aria-selected={draft.difficulty === "NORMAL"} onClick={() => setDraft((value) => ({ ...value, difficulty: "NORMAL" }))} type="button"><strong>寻常</strong><small>适合休闲阅读，危险仍有代价</small></button><button aria-selected={draft.difficulty === "HARD"} onClick={() => setDraft((value) => ({ ...value, difficulty: "HARD" }))} type="button"><strong>艰难</strong><small>资源更紧，失误后果更重</small></button></div></fieldset></>}
      {step === 3 && <><p className="eyebrow">THE FIRST INK</p><h2>命数初显</h2><p className="panel-lead">三条命途都遵循你写下的种族与根源，只改变你在青岐州醒来时的处境。</p><div className="fate-options">{originProposal?.candidates.map((fate) => <button aria-pressed={draft.fateCandidateId === fate.id} key={fate.id} onClick={() => setDraft((value) => ({ ...value, fateCandidateId: fate.id }))} type="button"><span>{RACE_MARKS[draft.race]}</span><p><small>{fate.publicIdentity}</small><strong>{fate.title}</strong><em>{fate.openingSituation}</em><b>未偿：{fate.unpaidDebt}</b></p></button>)}</div>{selectedFate && <><div className="creation-divider"><span>择一先天灵光，也可不带天赋</span></div><div className="origin-talents">{starterTalents.map((talent) => <button aria-pressed={draft.starterTalentDefinitionId === talent.id} key={talent.id} onClick={() => setDraft((value) => ({ ...value, starterTalentDefinitionId: talent.id }))} type="button"><LightningBoltIcon /><p><strong>{talent.name}</strong><span>{talent.description}</span></p></button>)}<button aria-pressed={!draft.starterTalentDefinitionId} onClick={() => setDraft((value) => ({ ...value, starterTalentDefinitionId: undefined }))} type="button"><span className="empty-talent">无</span><p><strong>不携带天赋</strong><span>以普通起点入世，后续仍可在经历中获得传承或觉醒。</span></p></button></div><dl className="creation-summary"><div><dt>姓名</dt><dd>{draft.name}</dd></div><div><dt>命格</dt><dd>{selectedFate.title}</dd></div><div><dt>根源</dt><dd>{draft.rootType}</dd></div><div><dt>所求</dt><dd>{draft.lifeGoal}</dd></div><div><dt>难度</dt><dd>{draft.difficulty === "NORMAL" ? "寻常" : "艰难"}</dd></div></dl></>}</>}
      {(originProblem ?? problem) && <ProblemCard problem={(originProblem ?? problem)!} />}
      <footer className="creation-actions">{step > 0 ? <button className="ghost-button" disabled={busy || originBusy} onClick={() => setStep((value) => value - 1)} type="button"><ChevronLeftIcon />上一步</button> : <span />}{step < 3 ? <button className="primary-button" disabled={!canContinue || originBusy} onClick={() => step === 2 ? void revealFates() : setStep((value) => value + 1)} type="button">{step === 2 ? "推演命格" : "下一步"}<ChevronRightIcon /></button> : <button className="primary-button" disabled={busy || !canContinue} onClick={() => onCreate(draft)} type="button">{busy ? "命卷正在落印…" : "进入青岐州"}<ArrowRightIcon /></button>}</footer>
    </section>
  </div>;
}

function GameHeader({ save, onOpenSaves, onOpenSettings }: { save: SaveAggregate; onOpenSaves: () => void; onOpenSettings: () => void }) {
  return <header className="game-header"><button className="brand-button" onClick={onOpenSaves} type="button"><span>问仙录</span><small>{save.protagonist.name} · {RACE_NAMES[save.protagonist.race]}</small></button><div className="header-context"><strong>{formatWorldTime(save)}</strong><small><SewingPinFilledIcon />{save.characterState.locationName}</small></div><button aria-label="卷外设置" className="icon-button" onClick={onOpenSettings} type="button"><GearIcon /></button></header>;
}

function GlobalOperation({ operation, saveName }: { operation: TurnOperation; saveName: string }) {
  const copy: Record<string, string> = { QUEUED: "行动已入卷", INTERPRETING: "正在理解此举", RESOLVING: "正在结算人物与世界", WRITING: "正在呈现本回", COMMITTING: "正在保存命卷" };
  return <div className="global-operation" role="status"><span className="ink-loader small" /><p><strong>{saveName}的命卷正在推演</strong><small>{copy[operation.stage] ?? copy[operation.status] ?? "正在整理本回"}</small></p></div>;
}

function AdventureView({ current, chronicle, world, problem, operation, draft, freeMode, onFocus, onBlur, onChangeDraft, onExitInput, onSubmitFree, onSubmitQuick, onOpenSaves }: { current: SaveAggregate; chronicle: TurnSummary[]; world: KnownLocation[]; problem: UiProblem | null; operation: TurnOperation | null; draft: string; freeMode: boolean; onFocus: () => void; onBlur: () => void; onChangeDraft: (value: string) => void; onExitInput: () => void; onSubmitFree: () => void; onSubmitQuick: (action: { actionType: "WAIT" | "TRAVEL" | "FREE"; actionText: string; durationMinutes?: number; targetLocationId?: string }) => void; onOpenSaves: () => void }) {
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const latest = chronicle[0];
  const narrative = latest?.narrativeText ?? openingNarrative(current);
  const reachable = world.filter((location) => location.accessible && location.directTravelMinutes != null
    && location.id !== current.characterState.locationId).slice(0, 2);
  const defaultSuggestions = [
    { id: "wait", title: "在此静观片刻", detail: "让一个时辰过去，观察周围是否发生变化。", action: { actionType: "WAIT" as const, actionText: "在此静观一个时辰", durationMinutes: 60 } },
    ...reachable.map((location) => ({ id: location.id, title: `前往${location.name}`, detail: `沿已知道路移动，约需${travelDuration(location.directTravelMinutes!)}。`, action: { actionType: "TRAVEL" as const, actionText: `前往${location.name}`, targetLocationId: location.id } })),
  ];
  const generatedSuggestions = latest?.choices?.slice(0, 4).map((choice, index) => ({
    id: `${latest.id}-choice-${index}`,
    title: choice.title,
    detail: choice.actionText,
    action: { actionType: "FREE" as const, actionText: choice.actionText },
  })) ?? [];
  const suggestions = generatedSuggestions.length ? generatedSuggestions : defaultSuggestions;
  const visibleChanges = latest?.changes?.filter((change) =>
    change.domainType !== "TIME" && change.changeType !== "NARRATIVE_FACT_CONFIRMED"
  ) ?? [];
  const ended = current.save.status !== "ACTIVE";
  return <div className={`adventure-page ${freeMode ? "is-composing" : ""}`}>
    <article className="chapter-card">
      <header><p className="eyebrow">{latest ? `当前场景 · 第 ${latest.turnNumber} 回` : "当前场景 · 初入此世"}</p><h1>{chapterTitle(latest)}</h1><span>{current.characterState.locationName}</span></header>
      <div className="chapter-rule"><i /></div>
      <div className="chapter-prose">{narrative.split("\n").filter(Boolean).map((paragraph, index) => <p key={index}>{paragraph}</p>)}</div>
    </article>
    {latest && <section className="turn-change"><p className="eyebrow">本回变化</p><strong>{latest.outcomeSummary}</strong>{visibleChanges.length > 0 && <ul>{visibleChanges.slice(0, 5).map((change, index) => <li key={change.changeType + index}>{change.reason}</li>)}</ul>}<span>世界时间前进 {Math.max(0, latest.worldMinuteAfter - latest.worldMinuteBefore)} 分钟 · 命卷已保存</span></section>}
    {problem && <ProblemCard problem={problem} />}
    {ended && <section className="ending-card"><p className="eyebrow">THE END OF THIS LIFE</p><h2>{current.ending?.title ?? "此生命卷已合"}</h2><p>{current.ending?.summary ?? current.characterState.bodyCondition}</p><span>共历 {current.save.turnNumber} 回 · 所有经历仍可在纪事中回看</span><button className="secondary-button" onClick={onOpenSaves} type="button">查看其他命卷<ChevronRightIcon /></button></section>}
    {operation && <section className="operation-panel"><span className="ink-loader" /><p><small>命卷推演中</small><strong>{operation.stage === "COMMITTING" ? "正在保存命卷" : "正在结算人物与世界"}</strong><span>你可以查看角色、纪事和世界，完成后这里会自动更新。</span></p></section>}
    {!ended && !operation && <section className={`suggestions ${freeMode ? "suggestions-collapsed" : ""}`} aria-hidden={freeMode} inert={freeMode || undefined}>
      <div className="section-heading"><p><span>接下来</span><strong>建议行动</strong></p><small>也可以自由描述</small></div>
      <div className="suggestion-list">{suggestions.map((suggestion, index) => <button key={suggestion.id} onClick={() => onSubmitQuick(suggestion.action)} type="button"><span>{index + 1}</span><p><strong>{suggestion.title}</strong><small>{suggestion.detail}</small></p><ChevronRightIcon /></button>)}</div>
    </section>}
    {!ended && <div className="composer-spacer" />}
    {!ended && <form className="action-composer" onSubmit={(event) => { event.preventDefault(); onSubmitFree(); }}>
      {freeMode && <div className="compose-mode"><span>自定义行动</span><button onClick={() => { inputRef.current?.blur(); onExitInput(); }} type="button">查看其他选项</button></div>}
      <div className="composer-row"><textarea aria-label="输入你的行动" disabled={Boolean(operation)} onBlur={onBlur} onChange={(event) => onChangeDraft(event.target.value)} onFocus={onFocus} placeholder="输入你的行动……" ref={inputRef} rows={1} value={draft} /><button aria-label="确认行动" disabled={Boolean(operation) || !draft.trim()} type="submit"><span>行动</span><ArrowRightIcon /></button></div>
      <small>自由输入不会直接改变事实；结果由当前人物、地点和世界规则共同决定。</small>
    </form>}
  </div>;
}

function CharacterView({ save }: { save: SaveAggregate }) {
  const [section, setSection] = useState<"self" | "cultivation" | "inventory" | "people">("self");
  const sourceName = (sourceType: string, sourceId: string | null) => sourceType === "ORIGIN" ? "本体根源"
    : save.knownCharacters.find((entry) => entry.id === sourceId)?.name
      ?? save.inventory.find((entry) => entry.instanceId === sourceId)?.name ?? "已确认的经历";
  return <div className="record-page">
    <PageTitle eyebrow="CHARACTER RECORD" icon={<PersonIcon />} title="角色" />
    <section className="hero-card"><div className="hero-mark">{RACE_MARKS[save.protagonist.race]}</div><div><h2>{save.protagonist.name}</h2><p>{save.characterState.publicIdentity}</p><small>{RACE_NAMES[save.protagonist.race]} · {save.protagonist.genderExpression} · {save.characterState.locationName}</small></div><span>{save.characterState.realmDisplayName}</span></section>
    <div className="local-tabs">{([['self','此身'],['cultivation','修行'],['inventory','行囊'],['people','人物']] as const).map(([id, label]) => <button aria-selected={section === id} key={id} onClick={() => setSection(id)} type="button">{label}</button>)}</div>
    {section === "self" && <div className="record-stack"><InfoCard label="此生命格" title={save.origin.fateTitle} text={save.origin.openingSituation} /><InfoCard label="此身根源" title={save.protagonist.rootType} text={save.protagonist.rootDescription} /><InfoCard label="未偿旧债" title={save.origin.unpaidDebt} text={`当前身份：${save.characterState.publicIdentity}`} /><InfoCard label="此生所求" title={save.protagonist.lifeGoal} text={`当前：${save.characterState.currentGoal}`} /><InfoCard label="身体状态" title={save.characterState.bodyCondition} text="身体变化只显示确认事实，不换算成百分比。" /></div>}
    {section === "cultivation" && <div className="record-stack"><InfoCard label={`${RACE_NAMES[save.protagonist.race]}修行`} title={`${save.characterState.realmDisplayName} · ${REALM_PHASE_NAMES[save.characterState.realmPhase] ?? "阶段未明"}`} text={save.cultivation.insightSummary ? `当前所得：${save.cultivation.insightSummary}` : "境界由修炼项目、资源、时间和突破事件共同推进。"} />{save.abilities.length ? save.abilities.map((ability) => <InfoCard key={ability.characterAbilityId} label={ABILITY_TYPE_NAMES[ability.type] ?? "能力"} title={ability.name} text={`${ability.masteryStage} · ${ability.stability} · 来源：${sourceName(ability.sourceType, ability.sourceReferenceId)}`} />) : <EmptyCard title="尚未掌握天赋或功法" text="真正获得后会连同来源与掌握状态一并记录。" />}</div>}
    {section === "inventory" && <div className="record-stack"><section className="wallet-grid">{save.wallet.filter((entry) => Number(entry.balance) > 0).map((entry) => <div key={entry.currencyCode}><span>{entry.currencyName}</span><strong>{Number(entry.balance).toLocaleString("zh-CN")}</strong></div>)}{save.wallet.every((entry) => Number(entry.balance) === 0) && <p>身无余财</p>}</section>{save.inventory.length ? save.inventory.map((item) => <InfoCard key={item.instanceId} label={`${item.quantity} 件 · ${item.condition}`} title={item.name} text={item.provenance} />) : <EmptyCard title="行囊空空" text="物品需要在故事中真实获得。" />}</div>}
    {section === "people" && <div className="record-stack">{save.knownCharacters.length ? save.knownCharacters.map((character) => {
      const relationship = save.relationships.find((entry) => entry.targetCharacterId === character.id);
      const daysAgo = Math.max(0, Math.floor((save.worldMinute - (relationship?.lastChangedWorldMinute ?? save.worldMinute)) / 1440));
      return <article className="person-card" key={character.id}><div><span>{RACE_MARKS[character.race]}</span><p><small>{relationship ? RELATIONSHIP_NAMES[relationship.relationshipType] ?? "有所牵连" : "已登场"}</small><strong>{character.name}</strong><em>{character.publicIdentity}</em></p><b>{character.status === "ALIVE" ? character.realmDisplayName : "已故"}</b></div><p>{relationship ? relationshipImpression(relationship) : "尚未形成明确关系"}</p><footer><span>{character.locationName} · {character.currentGoal}</span><small>{daysAgo === 0 ? "近期有过交集" : `${daysAgo}日前最后变化`}</small></footer></article>;
    }) : <EmptyCard title="尚未形成稳定的人间锚" text="重要人物登场后，会记录关系性质、最后确认时间与已知去向。" />}</div>}
  </div>;
}

function ChronicleView({ save, entries, hasMore, loading, onLoadMore }: { save: SaveAggregate; entries: TurnSummary[]; hasMore: boolean; loading: boolean; onLoadMore: () => void }) {
  const [filter, setFilter] = useState("全部");
  const [selected, setSelected] = useState<TurnSummary | null>(null);
  const visibleEntries = entries.filter((entry) => {
    if (filter === "全部") return true;
    const domains = entry.changes.map((change) => `${change.domainType}:${change.changeType}`).join(" ");
    if (filter === "人物") return /CHARACTER|RELATIONSHIP/.test(domains);
    if (filter === "修行") return /CULTIVATION|ABILITY|REALM/.test(domains);
    if (filter === "世界") return /WORLD|EVENT|LOCATION|TIME/.test(domains);
    return entry.choices.length > 0 || /FAILED|PARTIAL|DISCOVERED/.test(entry.outcomeCode);
  });
  return <div className="record-page"><PageTitle eyebrow="ONE WORLD · ONE TIMELINE" icon={<ClockIcon />} title="纪事" /><section className="timeline-summary"><span>统一世界时</span><strong>{formatWorldTime(save)}</strong><p>所有人物、地点和承诺沿同一条命卷时间变化。</p></section><div className="filter-row">{["全部", "抉择", "人物", "修行", "世界"].map((item) => <button aria-selected={filter === item} key={item} onClick={() => setFilter(item)} type="button">{item}</button>)}</div>{visibleEntries.length ? <section className="timeline">{visibleEntries.map((entry, index) => <article key={entry.id}><div className="timeline-rail"><span>{index === 0 && filter === "全部" ? "现在" : `第${entry.turnNumber}回`}</span><i /></div><div className="timeline-entry"><small>玩家行动 · {entry.playerAction}</small><h2>{entry.outcomeSummary}</h2><p>{entry.narrativeText.split("\n").find(Boolean)}</p><button onClick={() => setSelected(entry)} type="button">回看本回<ChevronRightIcon /></button></div></article>)}</section> : <EmptyCard title={entries.length ? "没有符合条件的纪事" : "命卷尚无纪事"} text={entries.length ? "切换其他分类查看已经发生的经历。" : "第一回完成后，重要事实会出现在这里。"} />}{hasMore && <button className="chronicle-more" disabled={loading} onClick={onLoadMore} type="button">{loading ? "正在展开旧卷…" : "展开更早纪事"}</button>}{selected && <TurnDetailSheet entry={selected} onClose={() => setSelected(null)} />}</div>;
}

function TurnDetailSheet({ entry, onClose }: { entry: TurnSummary; onClose: () => void }) {
  return <ModalShell label={`第${entry.turnNumber}回`} onClose={onClose}><div className="sheet-heading"><div><p className="eyebrow">TURN {entry.turnNumber}</p><h2>{entry.outcomeSummary}</h2></div><button aria-label="关闭本回" onClick={onClose} type="button"><Cross1Icon /></button></div><section className="turn-detail"><small>你的行动</small><blockquote>{entry.playerAction}</blockquote><div>{entry.narrativeText.split("\n").filter(Boolean).map((paragraph, index) => <p key={index}>{paragraph}</p>)}</div><footer><span>世界时间前进 {Math.max(0, entry.worldMinuteAfter - entry.worldMinuteBefore)} 分钟</span>{entry.changes.filter((change) => change.domainType !== "TIME").map((change, index) => <em key={`${change.changeType}-${index}`}>{change.reason}</em>)}</footer></section></ModalShell>;
}

function WorldView({ save, locations, chronicle }: { save: SaveAggregate; locations: KnownLocation[]; chronicle: TurnSummary[] }) {
  const activeEvents = save.worldEvents.filter((event) => event.stage !== "AFTERMATH").slice(0, 4);
  const knownFacts = Array.from(new Map(chronicle.flatMap((turn) => turn.changes)
    .filter((change) => change.changeType === "NARRATIVE_FACT_CONFIRMED" && change.after)
    .map((change) => [change.after!, change.after!] as const)).values()).slice(0, 6);
  return <div className="record-page"><PageTitle eyebrow="KNOWN WORLD" icon={<GlobeIcon />} title="世界" /><section className="location-hero"><div><SewingPinFilledIcon /><span>当前位置</span></div><h2>{save.characterState.locationName}</h2><p>青岐州 · 东陆与北荒交界的边州。矿路、商镇与小宗门在这里争夺有限的灵脉。</p></section>{activeEvents.length > 0 && <section className="world-event-list"><div className="section-heading"><p><span>不会等待主角</span><strong>正在发展的地域大事</strong></p><small>{activeEvents.length}件</small></div>{activeEvents.map((event) => <article className="world-event-card" key={event.id}><p className="eyebrow">{EVENT_STAGE_NAMES[event.stage] ?? "局势未明"}</p><h2>{event.title}</h2><p>{event.publicSummary}</p><span>{event.locationName ?? "青岐州"}</span></article>)}</section>}<section className="map-scroll"><div className="section-heading"><p><span>地域卷轴</span><strong>已知地点</strong></p><small>{locations.length}处</small></div>{locations.map((location) => <article data-current={location.id === save.characterState.locationId} key={location.id}><i /><div><small>{LOCATION_TYPE_NAMES[location.type] ?? "地点"}</small><h3>{location.name}</h3><p>{knownLocationDescription(location, save.characterState.locationId)}</p></div><span>{location.localState === "STABLE" ? "如常" : "有异"}</span></article>)}</section>{knownFacts.length > 0 && <section className="world-clues"><div className="section-heading"><p><span>亲历所得</span><strong>已确认线索</strong></p><small>{knownFacts.length}条</small></div><ul>{knownFacts.map((fact) => <li key={fact}>{fact}</li>)}</ul></section>}<section className="known-grid"><InfoCard label="势力" title="青铜门" text="掌握附近部分灵脉的小宗门，真实意图尚不清楚。" /><InfoCard label="传闻" title="天门久闭" text="三千年前最后一批飞升者在天门前失踪，此后再无人能证明仙界存在。" /></section></div>;
}

function BottomNav({ current, hidden, onChange }: { current: TabId; hidden: boolean; onChange: (tab: TabId) => void }) {
  const items: Array<[TabId, string, typeof HomeIcon]> = [["adventure", "历练", HomeIcon], ["character", "角色", PersonIcon], ["chronicle", "纪事", ReaderIcon], ["world", "世界", GlobeIcon]];
  return <nav className="bottom-nav" data-hidden={hidden}>{items.map(([id, label, Icon]) => <button aria-current={current === id ? "page" : undefined} key={id} onClick={() => onChange(id)} type="button"><Icon /><span>{label}</span></button>)}</nav>;
}

function SaveShelf({ saves, legacy, currentId, maxActiveSaves, busy, onClose, onOpen, onOpenLegacy, onArchive, onEnd, onCreate }: { saves: SaveAggregate[]; legacy: LegacyLife[]; currentId: string; maxActiveSaves: number; busy: boolean; onClose: () => void; onOpen: (save: SaveAggregate) => void; onOpenLegacy: (life: LegacyLife) => void; onArchive: (save: SaveAggregate) => void; onEnd: (save: SaveAggregate) => void; onCreate: () => void }) {
  const activeCount = saves.filter((entry) => entry.save.status === "ACTIVE").length;
  return <ModalShell label="命卷架" onClose={onClose}>
    <div className="sheet-heading"><div><p className="eyebrow">YOUR DESTINY SCROLLS</p><h2>我的命卷</h2></div><button aria-label="关闭命卷架" onClick={onClose} type="button"><Cross1Icon /></button></div>
    <p className="sheet-lead">每份 V0.2 命卷拥有独立人物、时间、行囊和行动草稿。当前可保留 {maxActiveSaves} 名存活角色。</p>
    <div className="save-list">{saves.map((save) => <article className="save-row" data-current={save.save.id === currentId} data-ended={save.save.status !== "ACTIVE"} key={save.save.id}>
      <button className="save-open" disabled={busy} onClick={() => onOpen(save)} type="button"><span>{RACE_MARKS[save.protagonist.race]}</span><p><small>命卷 {save.save.slotNo}{save.save.id === currentId ? " · 当前" : ""}</small><strong>{save.protagonist.name}</strong><em>{save.save.status === "ACTIVE" ? `${save.characterState.realmDisplayName} · ${save.characterState.locationName}` : `${endingLabel(save)} · ${save.characterState.locationName}`}</em></p><div>第 {save.save.turnNumber} 回<ChevronRightIcon /></div></button>
      {save.save.status === "ACTIVE" && <div className="save-row-actions"><button disabled={busy} onClick={() => onEnd(save)} type="button">收束此生</button><button disabled={busy} onClick={() => onArchive(save)} type="button">封存</button></div>}
    </article>)}
      {activeCount < maxActiveSaves ? <button className="new-save" onClick={onCreate} type="button"><span>＋</span><p><strong>开启新的命卷</strong><em>创建另一名初始角色</em></p></button> : <p className="save-limit-note">存活角色已达上限；如需新建，请先封存一份命卷。</p>}
    </div>
    <div className="archive-heading"><BookmarkIcon /><p><strong>V0.13 旧命卷</strong><span>只读保留，不会写入新版世界</span></p></div>
    <div className="legacy-save-list">{legacy.length ? legacy.map((life) => <button disabled={busy} key={life.id} onClick={() => onOpenLegacy(life)} type="button"><p><small>{life.source === "V0_13_ACTIVE" ? "旧版存档" : "旧版终卷"}</small><strong>{life.characterName}</strong><span>{life.realm} · {life.location}</span></p><div>第 {life.turnNumber} 回<ChevronRightIcon /></div></button>) : <p>当前账号没有可读取的 V0.13 命卷。</p>}</div>
  </ModalShell>;
}

function ArchiveSaveModal({ save, busy, onClose, onConfirm }: { save: SaveAggregate; busy: boolean; onClose: () => void; onConfirm: (name: string) => void }) {
  const [name, setName] = useState("");
  const matched = name.trim() === save.protagonist.name;
  return <ModalShell label="封存命卷" onClose={onClose}><div className="sheet-heading"><div><p className="eyebrow">ARCHIVE THIS LIFE</p><h2>封存 {save.protagonist.name}</h2></div><button aria-label="取消封存" onClick={onClose} type="button"><Cross1Icon /></button></div><section className="archive-confirm"><p>封存后，这名角色不能再行动，但人物、纪事、行囊和关系都会保留供回看。此操作不会删除数据。</p><label><span>输入角色名确认</span><input autoFocus disabled={busy} onChange={(event) => setName(event.target.value)} placeholder={save.protagonist.name} value={name} /></label><div><button className="secondary-button" disabled={busy} onClick={onClose} type="button">暂不封存</button><button className="danger-button" disabled={busy || !matched} onClick={() => onConfirm(name.trim())} type="button">确认封存</button></div></section></ModalShell>;
}

function EndLifeModal({ save, busy, onClose, onConfirm }: { save: SaveAggregate; busy: boolean; onClose: () => void; onConfirm: (name: string, type: "RETIREMENT" | "LEGACY") => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState<"RETIREMENT" | "LEGACY">("RETIREMENT");
  const matched = name.trim() === save.protagonist.name;
  const retirementReady = save.save.turnNumber >= 5;
  const legacyReady = save.save.turnNumber >= 20;
  const selectedReady = type === "RETIREMENT" ? retirementReady : legacyReady;
  return <ModalShell label="收束此生" onClose={onClose}><div className="sheet-heading"><div><p className="eyebrow">COMPLETE THIS LIFE</p><h2>为 {save.protagonist.name} 写下终卷</h2></div><button aria-label="取消收束" onClick={onClose} type="button"><Cross1Icon /></button></div><section className="archive-confirm"><p>这是角色主动作出的人生结局，不是删除存档。完成后不能继续行动，所有经历仍可回看。</p><div className="ending-options"><button aria-selected={type === "RETIREMENT"} disabled={!retirementReady} onClick={() => setType("RETIREMENT")} type="button"><strong>归隐</strong><small>{retirementReady ? "退出纷争，为此生保留平静结局" : `还需经历 ${5 - save.save.turnNumber} 回`}</small></button><button aria-selected={type === "LEGACY"} disabled={!legacyReady} onClick={() => setType("LEGACY")} type="button"><strong>留下传承</strong><small>{legacyReady ? "将此生所得交给后来者" : `还需经历 ${20 - save.save.turnNumber} 回`}</small></button></div><label><span>输入角色名确认</span><input autoFocus disabled={busy || !selectedReady} onChange={(event) => setName(event.target.value)} placeholder={save.protagonist.name} value={name} /></label><div><button className="secondary-button" disabled={busy} onClick={onClose} type="button">继续此生</button><button className="danger-button" disabled={busy || !selectedReady || !matched} onClick={() => onConfirm(name.trim(), type)} type="button">写下终卷</button></div></section></ModalShell>;
}

function endingLabel(save: SaveAggregate) {
  if (save.save.endReason === "RETIREMENT") return "已归隐";
  if (save.save.endReason === "LEGACY") return "已留传";
  if (save.save.endReason === "DEATH") return "已故";
  return "已封存";
}

function SettingsSheet({ auth, bootstrap, theme, fontMode, onClose, onTheme, onFont, onLogout }: { auth: AuthResponse | null; bootstrap: Bootstrap | null; theme: ThemeMode; fontMode: FontMode; onClose: () => void; onTheme: (mode: ThemeMode) => void; onFont: (mode: FontMode) => void; onLogout: () => void }) {
  const [aiSettings, setAiSettings] = useState<AiSettings | null>(null);
  const [aiProblem, setAiProblem] = useState<UiProblem | null>(null);
  const [aiBusy, setAiBusy] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [selectedModel, setSelectedModel] = useState("");

  useEffect(() => {
    if (previewMode) return;
    setAiBusy(true);
    api.aiSettings().then((settings) => {
      setAiSettings(settings);
      setSelectedModel(settings.modelCode);
    }).catch((error) => setAiProblem(toProblem(error))).finally(() => setAiBusy(false));
  }, []);

  async function updateAi(work: () => Promise<AiSettings>) {
    setAiBusy(true);
    setAiProblem(null);
    try {
      const settings = await work();
      setAiSettings(settings);
      setSelectedModel(settings.modelCode);
      setApiKey("");
    } catch (error) {
      setAiProblem(toProblem(error));
    } finally {
      setAiBusy(false);
    }
  }

  const model = selectedModel || aiSettings?.modelCode || "deepseek-v4-flash";
  return <ModalShell label="卷外设置" onClose={onClose}>
    <div className="sheet-heading"><div><p className="eyebrow">OUTSIDE THE SCROLL</p><h2>卷外设置</h2></div><button aria-label="关闭设置" onClick={onClose} type="button"><Cross1Icon /></button></div>
    <section className="setting-section"><h3>阅读外观</h3><div className="setting-options">{([['system','跟随系统'],['paper','宣纸'],['night','月夜']] as const).map(([id, label]) => <button aria-selected={theme === id} key={id} onClick={() => onTheme(id)} type="button">{id === "night" ? <MoonIcon /> : <SunIcon />}{label}</button>)}</div><div className="setting-options font-options">{([['compact','小字'],['standard','标准'],['large','大字']] as const).map(([id, label]) => <button aria-selected={fontMode === id} key={id} onClick={() => onFont(id)} type="button">{label}</button>)}</div></section>
    <section className="setting-section"><h3>推演方式</h3>
      {aiBusy && !aiSettings ? <article className="setting-card"><span className="ink-loader small" /><p><strong>正在读取推演设置</strong><span>不会改变当前命卷</span></p></article> : <>
        {aiSettings && <article className="quota-card"><p><span>账号获赠回合</span><strong>{aiSettings.remainingTurns} / {aiSettings.turnLimit}</strong></p><small>{aiSettings.credentialSource === "USER" ? "当前使用自己的密钥，不消耗获赠回合。" : `已完成 ${aiSettings.usedTurns} 回；裁决不通过不会扣除。`}</small></article>}
        <div className="setting-options ai-source-options"><button aria-selected={aiSettings?.credentialSource !== "USER"} disabled={aiBusy} onClick={() => void updateAi(() => api.chooseAiPreference("PLATFORM", model))} type="button">游戏提供</button><button aria-selected={aiSettings?.credentialSource === "USER"} disabled={aiBusy || !aiSettings?.credentialConfigured} onClick={() => void updateAi(() => api.chooseAiPreference("USER", model))} type="button">自己的密钥</button></div>
        <label className="setting-field"><span>模型</span><select disabled={aiBusy} value={model} onChange={(event) => { const next = event.target.value; setSelectedModel(next); if (aiSettings) void updateAi(() => api.chooseAiPreference(aiSettings.credentialSource, next)); }}>{aiSettings?.models.map((option) => <option key={option.modelCode} value={option.modelCode}>{option.displayName}</option>)}</select></label>
        {aiSettings?.credentialConfigured ? <article className="setting-card credential-card"><LightningBoltIcon /><p><strong>自己的密钥 · ••••••••{aiSettings.maskedSuffix}</strong><span>{aiSettings.credentialSource === "USER" ? "当前行动使用自己的额度" : "已保存，当前仍使用游戏提供的额度"}</span></p><button disabled={aiBusy} onClick={() => void updateAi(api.deleteAiCredential)} type="button">删除</button></article> : <div className="credential-form"><label className="setting-field"><span>自己的推演密钥</span><input autoCapitalize="none" autoComplete="off" disabled={!aiSettings?.userCredentialEnabled || aiBusy} onChange={(event) => setApiKey(event.target.value)} placeholder="粘贴后只显示末四位" spellCheck={false} type="password" value={apiKey} /></label><button className="secondary-button" disabled={!apiKey.trim() || aiBusy || !aiSettings?.userCredentialEnabled} onClick={() => void updateAi(() => api.saveAiCredential(apiKey, model))} type="button">{aiBusy ? "正在验证…" : "验证并启用"}</button></div>}
        <p className="setting-hint">自己的密钥经服务端验证后加密保存，浏览器与管理台都不会再显示完整内容。世界规则与存档裁决不会因此改变。</p>
      </>}
      {aiProblem && <ProblemCard compact problem={aiProblem} />}
    </section>
    <section className="setting-section"><h3>账号与版本</h3><article className="setting-card"><PersonIcon /><p><strong>{auth?.user?.email ?? "当前账号"}</strong><span>账号信息与现有登录身份一致</span></p></article><article className="setting-card"><ReaderIcon /><p><strong>V{bootstrap?.engineVersion ?? "0.2.0"}</strong><span>青岐州内容 {bootstrap?.contentVersion ?? "qingqi-0.2.0"}</span></p></article></section>
    <button className="logout-button" onClick={onLogout} type="button"><ExitIcon />退出当前账号</button>
  </ModalShell>;
}

function LegacyArchiveSheet({ detail, onClose }: { detail: { life: LegacyLife; chapters: LegacyChapter[] }; onClose: () => void }) {
  const { life, chapters } = detail;
  return <ModalShell label={`${life.characterName}的旧命卷`} onClose={onClose}><div className="sheet-heading"><div><p className="eyebrow">V0.13 · READ ONLY</p><h2>{life.characterName}</h2></div><button aria-label="关闭旧命卷" onClick={onClose} type="button"><Cross1Icon /></button></div><section className="legacy-summary"><span>{life.race} · {life.realm}</span><strong>{life.location}</strong><p>绝天后{life.worldYear}年 · 第{life.day}日 · 共 {life.turnNumber} 回</p><small>这份命卷只供回看，不会并入 V0.2 的人物、物品或世界时间。</small></section><div className="legacy-chapters">{chapters.length ? chapters.map((chapter) => <article key={`${life.id}-${chapter.turnNumber}`}><small>第 {chapter.turnNumber} 回 · {chapter.playerAction}</small><p>{chapter.narrative || "本回正文未能读取。"}</p></article>) : <EmptyCard title="旧卷没有留下可回看的章节" text="角色摘要仍会长期保留。" />}</div></ModalShell>;
}

function AnnouncementModal({ notice, onClose }: { notice: ReleaseNotice; onClose: () => void }) {
  return <ModalShell label="版本公告" onClose={onClose}><div className="sheet-heading"><div><p className="eyebrow">VERSION NOTICE · V{notice.version}</p><h2>{notice.title}</h2></div><button aria-label="关闭公告" onClick={onClose} type="button"><Cross1Icon /></button></div><div className="announcement-content">{notice.content.split("\n").filter(Boolean).map((paragraph, index) => <p key={index}>{paragraph}</p>)}</div><button className="primary-button" onClick={onClose} type="button">我知道了<ArrowRightIcon /></button></ModalShell>;
}

function MaintenanceModal({ problem, onClose }: { problem: UiProblem; onClose: () => void }) {
  return <ModalShell label="游戏更新提示" onClose={onClose}><div className="maintenance-modal"><span className="brand-seal">问</span><p className="eyebrow">SERVICE NOTICE</p><h2>游戏正在更新</h2><p>{problem.message}</p>{problem.suggestions.map((suggestion) => <strong key={suggestion}>{suggestion}</strong>)}<button className="primary-button" onClick={onClose} type="button">我知道了</button></div></ModalShell>;
}

function ModalShell({ label, onClose, children }: { label: string; onClose: () => void; children: React.ReactNode }) {
  return <div aria-label={label} aria-modal="true" className="sheet-backdrop" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }} role="dialog"><section className="side-sheet">{children}</section></div>;
}

function PageTitle({ eyebrow, title, icon }: { eyebrow: string; title: string; icon: React.ReactNode }) {
  return <div className="page-title"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1></div><span>{icon}</span></div>;
}

function InfoCard({ label, title, text }: { label: string; title: string; text: string }) {
  return <article className="info-card"><small>{label}</small><h3>{title}</h3><p>{text}</p></article>;
}

function EmptyCard({ title, text }: { title: string; text: string }) {
  return <article className="empty-card"><BookmarkIcon /><div><h3>{title}</h3><p>{text}</p></div></article>;
}

function ProblemCard({ problem, compact = false }: { problem: UiProblem; compact?: boolean }) {
  return <section className={`problem-card ${compact ? "compact" : ""}`} data-category={problem.category}><p className="eyebrow">{problem.category === "BUSINESS" ? "此举未能成立" : problem.category === "DEVELOPMENT" ? "开发阶段说明" : "命卷暂未完成"}</p><h2>{problem.message}</h2>{problem.suggestions.length > 0 && <ul>{problem.suggestions.map((suggestion) => <li key={suggestion}>{suggestion}</li>)}</ul>}{problem.problemReference && <small>问题编号：{problem.problemReference}</small>}</section>;
}
