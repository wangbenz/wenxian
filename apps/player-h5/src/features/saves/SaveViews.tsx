import { useState } from "react";
import { ArrowLeftIcon, ChevronRightIcon, TrashIcon } from "@radix-ui/react-icons";
import type { StoryGenerationSettings } from "../../game-api";
import type { PlayerSaveSummary } from "../../app/player-read-model";

const RACE_NAMES: Record<string, string> = {
  HUMAN: "人族",
  YAO: "妖族",
  SPIRIT: "灵族",
};

function saveRaceName(race: string): string {
  if (RACE_NAMES[race]) return RACE_NAMES[race];
  return /^[A-Z0-9_]+$/.test(race) ? "身份已记录" : race;
}

const VOLUME_NAMES = ["一", "二", "三", "四"];

function volumeName(slotNo: number): string {
  return `第${VOLUME_NAMES[slotNo - 1] ?? slotNo}卷`;
}

function ExistingSaveRow({ save, slotNo, featured = false, metadataUnavailable = false, managing, isBusy, onOpen, onDelete }: { save: PlayerSaveSummary; slotNo: number; featured?: boolean; metadataUnavailable?: boolean; managing: boolean; isBusy: boolean; onOpen: (save: PlayerSaveSummary) => void; onDelete: (save: PlayerSaveSummary) => void }) {
  const identity = [saveRaceName(save.race), save.realm].filter(Boolean).join(" · ");
  const location = save.currentLocation || "尚未入世";
  if (featured) {
    return <article className="save-primary-volume" data-managing={managing} role="listitem"><header className="save-primary-heading"><span><small>{volumeName(slotNo)}</small><strong>{save.playerName}</strong></span>{!metadataUnavailable && <b>第 {save.turn} 回</b>}</header><div className="save-primary-facts"><strong>{location}</strong>{!metadataUnavailable && <span>{identity}</span>}</div>{managing ? <button aria-label={`删除${save.playerName}的命卷`} className="save-primary-delete" disabled={isBusy} onClick={() => onDelete(save)} type="button"><TrashIcon />删除此命卷</button> : <button aria-label={`继续${save.playerName}的命卷`} className="save-primary-action" disabled={isBusy} onClick={() => onOpen(save)} type="button"><strong>继续历练</strong><ChevronRightIcon /></button>}</article>;
  }
  return <article className="save-volume-row" data-managing={managing} role="listitem"><button aria-label={`进入${save.playerName}的命卷`} className="save-volume-main" disabled={isBusy} onClick={() => onOpen(save)} type="button"><span className="save-volume-copy"><small>{volumeName(slotNo)}</small><strong>{save.playerName}</strong><span className="save-volume-location">{location}</span>{!metadataUnavailable && <span className="save-volume-meta"><span>{identity}</span><b>第 {save.turn} 回</b></span>}</span><ChevronRightIcon /></button>{managing && <button aria-label={`删除${save.playerName}的命卷`} className="save-volume-delete" disabled={isBusy} onClick={() => onDelete(save)} type="button"><TrashIcon /><span>删除</span></button>}</article>;
}

export function SaveCards({ saves, currentSaveId, maxActiveSaves, isBusy, managing = false, unavailableSaveIds = [], onOpen, onCreate, onDelete }: { saves: PlayerSaveSummary[]; currentSaveId?: string; maxActiveSaves: number; isBusy: boolean; managing?: boolean; unavailableSaveIds?: string[]; onOpen: (save: PlayerSaveSummary) => void; onCreate: () => void; onDelete: (save: PlayerSaveSummary) => void }) {
  // 隔离异常槽位：正常命卷按权威 slotNo 展示，冲突项补到空位，避免一份坏数据覆盖其他命卷。
  const bySlot = new Map<number, PlayerSaveSummary>();
  const pending: PlayerSaveSummary[] = [];
  for (const save of saves) {
    if (save.slotNo >= 1 && save.slotNo <= maxActiveSaves && !bySlot.has(save.slotNo)) bySlot.set(save.slotNo, save);
    else pending.push(save);
  }
  for (const save of pending) {
    const empty = Array.from({ length: maxActiveSaves }, (_, index) => index + 1).find((slot) => !bySlot.has(slot));
    if (empty !== undefined) bySlot.set(empty, save);
  }
  const slots = Array.from({ length: maxActiveSaves }, (_, index) => ({ slotNo: index + 1, save: bySlot.get(index + 1) }));
  const currentSlot = slots.find(({ save }) => save?.saveId === currentSaveId);
  const otherSlots = slots.filter(({ slotNo, save }) => Boolean(save) && slotNo !== currentSlot?.slotNo) as Array<{ slotNo: number; save: PlayerSaveSummary }>;
  const hasCapacity = saves.length < maxActiveSaves;
  return <div aria-label="命卷" className="save-card-groups">{currentSlot?.save && <div aria-label="当前命卷" className="save-current-list" role="list"><ExistingSaveRow featured metadataUnavailable={unavailableSaveIds.includes(currentSlot.save.saveId)} isBusy={isBusy} managing={managing} onDelete={onDelete} onOpen={onOpen} save={currentSlot.save} slotNo={currentSlot.slotNo} /></div>}<div aria-label={currentSlot ? "其他命卷" : "全部命卷"} className="save-card-list" role="list">{otherSlots.map(({ slotNo, save }) => <ExistingSaveRow metadataUnavailable={unavailableSaveIds.includes(save.saveId)} isBusy={isBusy} key={save.saveId} managing={managing} onDelete={onDelete} onOpen={onOpen} save={save} slotNo={slotNo} />)}{hasCapacity && <article className="new-save-slot" role="listitem"><button aria-label="开启新命卷" className="new-save-card" disabled={isBusy} onClick={onCreate} type="button"><strong>开启新命卷</strong><ChevronRightIcon /></button></article>}</div></div>;
}
export function SaveSelectionView({ saves, currentSaveId, maxActiveSaves, isBusy, onBack, onOpen, onCreate, onDelete, onLogout, localTrial = false, accountTrial = false, unavailableSaveIds = [] }: { saves: PlayerSaveSummary[]; currentSaveId?: string; maxActiveSaves: number; isBusy: boolean; onBack?: () => void; onOpen: (save: PlayerSaveSummary) => void; onCreate: () => void; onDelete: (save: PlayerSaveSummary) => void; onLogout: () => void; localTrial?: boolean; accountTrial?: boolean; unavailableSaveIds?: string[] }) {
  const [managing, setManaging] = useState(false);
  return <div aria-label="命卷" className="record-view save-selection-view"><h1 className="visually-hidden">命卷</h1>{localTrial && !accountTrial && <p className="runtime-mode-note">本机试玩命卷 · 切换命卷会保留各自的故事和草稿。</p>}{onBack && <button className="save-back-button" disabled={isBusy} onClick={onBack} type="button"><ArrowLeftIcon />返回当前命卷</button>}<SaveCards unavailableSaveIds={unavailableSaveIds} currentSaveId={currentSaveId} isBusy={isBusy} managing={managing} maxActiveSaves={maxActiveSaves} onCreate={onCreate} onDelete={onDelete} onOpen={onOpen} saves={saves} />{(!localTrial || accountTrial) && <div className="save-account-actions">{!localTrial && saves.length > 0 && <button aria-pressed={managing} className="save-manage-toggle" disabled={isBusy} onClick={() => setManaging((value) => !value)} type="button">{managing ? "完成管理" : "管理命卷"}</button>}<button className="save-logout" onClick={onLogout} type="button">退出账号</button></div>}</div>;
}

export function GenerationAccessCard({ generation, isBusy, onOpen }: { generation: StoryGenerationSettings; isBusy: boolean; onOpen: () => void }) {
  const personal = generation.mode === "PERSONAL";
  const provider = generation.personalProviders.find((item) => item.code === generation.selectedPersonalProvider);
  const personalReady = personal && provider?.credentialConfigured && provider.credentialStatus === "ACTIVE";
  const platformRemaining = generation.platform.remainingTurns;
  const selectedTier = generation.platformTiers?.find((tier) => tier.selected);
  const title = personal
    ? personalReady ? "正在使用自己的推演密钥" : "自己的推演密钥需要重新验证"
    : `平台推演 · ${selectedTier?.name ?? "默认档"} · ${platformRemaining} 点`;
  const detail = personal
    ? personalReady
      ? `${provider?.name ?? "自选服务"}密钥尾号 ${provider?.maskedSuffix ?? "已隐藏"}，只用于你的命卷。`
      : "本次行动不会自动改用平台推演点，请先重新保存密钥或主动切换平台推演。"
    : platformRemaining > 0
      ? `${selectedTier ? `本档每个成功回合消耗 ${selectedTier.pointCost} 点；` : ""}四份命卷共享同一账号余额。`
      : "推演点不足时，可换用更轻档位或接入自己的密钥继续游玩。";
  return <section className="generation-access-card"><div><span>剧情生成</span><strong>{title}</strong><p>{detail}</p></div><button disabled={isBusy} onClick={onOpen} type="button">{personalReady ? "管理" : personal ? "重新验证" : "选择档位"}</button></section>;
}

export function AbandonSaveDialog({ save, isBusy, onCancel, onConfirm }: { save: PlayerSaveSummary; isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const [confirmation, setConfirmation] = useState("");
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-labelledby="delete-save-title" aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">删</div><p className="eyebrow">删除命卷</p><h2 id="delete-save-title">删除「{save.playerName}」的命卷</h2><p>删除后，这名角色不会再出现在你的命卷列表中，相关经历也不能自行恢复。账号、其他命卷与已使用的回合不受影响。</p><label className="credential-field">请输入角色名“{save.playerName}”确认<input autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.currentTarget.value)} /></label><div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">保留命卷</button><button className="ink-dialog-confirm destructive" disabled={isBusy || confirmation !== save.playerName} onClick={onConfirm} type="button">确认删除</button></div></section></div>;
}
