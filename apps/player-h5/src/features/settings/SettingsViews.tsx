import { useState } from "react";
import { ArrowRightIcon, ChevronDownIcon, ReloadIcon } from "@radix-ui/react-icons";
import type { ReleaseNote, StoryCorrectionPreview, StoryGenerationSettings } from "../../game-api";
import type { CleanupTarget } from "../gameplay/GameViews";
import { ProblemNotice, type UiProblem } from "../../shared/problem";

export type ThemeMode = "system" | "paper" | "night";
export type FontSizeMode = "compact" | "standard" | "large";
export type TurnLimitNotice = { message: string; contactText: string; supportGroup: string };

export function GenerationSettingsDialog({ settings, error, isBusy, onCancel, onUsePlatform, onSave, onActivate, onDelete }: { settings: StoryGenerationSettings; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onUsePlatform: (tierCode?: "LIGHT" | "BALANCED" | "DEEP") => void; onSave: (provider: string, model: string, key: string) => void; onActivate: (provider: string, model: string) => void; onDelete: (provider: string) => void }) {
  const fallbackProvider = settings.personalProviders[0]?.code ?? "";
  const [providerCode, setProviderCode] = useState(settings.selectedPersonalProvider ?? fallbackProvider);
  const provider = settings.personalProviders.find((item) => item.code === providerCode) ?? settings.personalProviders[0];
  const fallbackModel = provider?.models[0]?.code ?? "";
  const [modelCode, setModelCode] = useState(
    settings.selectedPersonalProvider === providerCode ? settings.selectedPersonalModel ?? fallbackModel : fallbackModel,
  );
  const [apiKey, setApiKey] = useState("");
  const platformTiers = settings.platformTiers ?? [];
  const insecureRemote = window.location.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(window.location.hostname);
  const selectProvider = (code: string) => {
    setProviderCode(code);
    setModelCode(settings.personalProviders.find((item) => item.code === code)?.models[0]?.code ?? "");
    setApiKey("");
  };
  return <div className="ink-dialog-backdrop" role="presentation">
    <section aria-modal="true" className="ink-dialog credential-dialog" role="dialog">
      <div className="ink-dialog-seal">演</div>
      <p className="eyebrow">推演服务</p>
      <h2>选择推演方式</h2>
      <p>平台只展示体验档位和点数，不展示具体供应商与模型；失败请求不会消耗推演点。</p>
      {error && <ProblemNotice compact problem={error} />}
      {platformTiers.length > 0 && <div className="generation-source-list">{platformTiers.map((tier) => {
        const current = settings.mode === "PLATFORM" && tier.selected;
        return <article className="generation-source-card" data-current={current} key={tier.code}>
          <div className="generation-source-copy"><span>{current ? "当前使用" : `${tier.pointCost} 点 / 成功回合`}</span><strong>{tier.name}</strong><p>{tier.description}</p></div>
          {current ? <span className="generation-source-current">使用中</span> : <button disabled={isBusy || !settings.platform.available} onClick={() => onUsePlatform(tier.code)} type="button">选择此档</button>}
        </article>;
      })}</div>}
      <p>也可接入自己的推演服务；密钥验证成功后使用量由对应服务计费，平台不扣点。</p>
      {insecureRemote && <p className="credential-warning">当前连接未启用 HTTPS，为避免密钥在传输中暴露，暂不允许保存。</p>}
      {settings.personalProviders.length === 0 ? <p className="credential-warning">当前暂未开放可接入的推演服务。</p> : <div className="credential-field">
        <label>推演服务<span className="select-control"><select disabled={isBusy} onChange={(event) => selectProvider(event.currentTarget.value)} value={providerCode}>{settings.personalProviders.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}</select><ChevronDownIcon aria-hidden="true" /></span></label>
        <label>模型<span className="select-control"><select disabled={isBusy} onChange={(event) => setModelCode(event.currentTarget.value)} value={modelCode}>{provider?.models.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}</select><ChevronDownIcon aria-hidden="true" /></span></label>
        <label>{provider?.name ?? "推演服务"} API Key<input autoComplete="off" disabled={isBusy || insecureRemote} placeholder={provider?.credentialConfigured ? `当前尾号 ${provider.maskedSuffix ?? "****"}，输入新密钥可替换` : "粘贴自己的 API Key"} type="password" value={apiKey} onChange={(event) => setApiKey(event.currentTarget.value)} /></label>
        {apiKey && apiKey.trim().length < 20 && <small>密钥长度不足，请粘贴完整内容。</small>}
      </div>}
      <div className="ink-dialog-actions credential-actions">
        <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">返回</button>
        {provider?.credentialConfigured && <button className="credential-delete" disabled={isBusy} onClick={() => onDelete(provider.code)} type="button">移除密钥</button>}
        {provider?.credentialConfigured && !apiKey.trim() && <button className="ink-dialog-confirm" disabled={isBusy || !modelCode} onClick={() => onActivate(provider.code, modelCode)} type="button">使用此密钥</button>}
        {provider && <button className="ink-dialog-confirm" disabled={isBusy || insecureRemote || !modelCode || apiKey.trim().length < 20} onClick={() => onSave(provider.code, modelCode, apiKey.trim())} type="button">{isBusy ? "正在验证…" : provider.credentialConfigured ? "验证并替换" : "验证并启用"}</button>}
      </div>
    </section>
  </div>;
}

export function PasswordDialog({ configured, mandatory = false, error, isBusy, onCancel, onSave }: { configured: boolean; mandatory?: boolean; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onSave: (currentPassword: string, newPassword: string) => void }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const ready = newPassword.length >= 8 && newPassword === confirmation && (!configured || currentPassword.length >= 8);
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">密</div><p className="eyebrow">账号安全</p><h2>{mandatory ? "首次登录，请设置新密码" : configured ? "修改登录密码" : "设置登录密码"}</h2><p>{mandatory ? "初始化密码仅用于本次身份确认。请立即设置自己的密码，完成后才能继续命卷。" : "密码需为 8-72 个字符。服务器只保存不可逆摘要，不会保存或展示明文。"}</p>{error && <ProblemNotice compact problem={error} />}<div className="credential-field">{configured && <label>当前密码<input autoComplete="current-password" disabled={isBusy} maxLength={72} onChange={(event) => setCurrentPassword(event.currentTarget.value)} type="password" value={currentPassword} /></label>}<label>新密码<input autoComplete="new-password" disabled={isBusy} maxLength={72} minLength={8} onChange={(event) => setNewPassword(event.currentTarget.value)} type="password" value={newPassword} /></label><label>确认新密码<input autoComplete="new-password" disabled={isBusy} maxLength={72} minLength={8} onChange={(event) => setConfirmation(event.currentTarget.value)} type="password" value={confirmation} /></label>{confirmation && confirmation !== newPassword && <small className="field-error">两次输入的密码不一致</small>}</div><div className={`ink-dialog-actions${mandatory ? " single" : ""}`}>{!mandatory && <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">返回</button>}<button className="ink-dialog-confirm" disabled={isBusy || !ready} onClick={() => onSave(currentPassword, newPassword)} type="button">{isBusy ? "正在保存…" : "保存新密码并继续"}</button></div></section></div>;
}

export function DeleteAccountDialog({ isBusy, onCancel, onConfirm }: { isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const [confirmation, setConfirmation] = useState("");
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog credential-dialog" role="dialog"><div className="ink-dialog-seal">止</div><p className="eyebrow">注销账号</p><h2>注销这个账号</h2><p>注销后，邮箱身份、今生命卷、前世记录和自定义密钥都会永久删除，无法恢复。</p><label className="credential-field">请输入“确认注销”<input autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.currentTarget.value)} /></label><div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不注销</button><button className="ink-dialog-confirm destructive" disabled={isBusy || confirmation !== "确认注销"} onClick={onConfirm} type="button">永久注销</button></div></section></div>;
}

export function ReleaseAnnouncementDialog({ note, onClose }: { note: ReleaseNote; onClose: () => void }) {
  return <div className="ink-dialog-backdrop release-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog release-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">新</div><p className="eyebrow">版本更新 · {note.version}</p><h2>{note.title}</h2>
    <div className="release-dialog-content">{note.content.split(/\n+/).filter(Boolean).map((line, index) => <p key={`${index}-${line}`}>{line}</p>)}</div>
    <div className="ink-dialog-actions single"><button autoFocus className="ink-dialog-confirm" onClick={onClose} type="button">知道了，继续此世<ArrowRightIcon /></button></div>
  </section></div>;
}

export function TurnLimitDialog({ notice, onClose, onCopyGroup, onOpenSettings }: { notice: TurnLimitNotice; onClose: () => void; onCopyGroup: () => void; onOpenSettings: () => void }) {
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-describedby="turn-limit-description" aria-labelledby="turn-limit-title" aria-modal="true" className="ink-dialog quota-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">续</div><p className="eyebrow">继续此章</p><h2 id="turn-limit-title">推演点不足</h2>
    <p id="turn-limit-description">{notice.message}</p>
    {notice.contactText && <div className="ink-dialog-note"><span>试玩交流</span><strong>{notice.contactText}</strong></div>}
    <div className="ink-dialog-actions quota-actions">
      <button className="ink-dialog-confirm" onClick={onOpenSettings} type="button">接入自己的推演密钥<ArrowRightIcon /></button>
      {notice.supportGroup && <button className="ink-dialog-cancel" onClick={onCopyGroup} type="button">复制QQ群号</button>}
      <button className="ink-dialog-cancel" onClick={onClose} type="button">暂时留在此页</button>
    </div>
  </section></div>;
}

export function RecordCleanupDialog({ target, isBusy, onCancel, onConfirm }: { target: CleanupTarget; isBusy: boolean; onCancel: () => void; onConfirm: () => void }) {
  const item = target.kind === "item";
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">理</div><p className="eyebrow">整理当前记录</p>
    <h2>{item ? `移出「${target.name}」` : `不再关注「${target.name}」`}</h2>
    <p>{item ? "这件物品会从当前行囊和后续故事上下文中移除。" : "这个人物会从当前关系和后续故事上下文中移除。"}已经发生过的剧情与纪事仍会保留。</p>
    <div className="ink-dialog-note"><span>不会发生</span><strong>不调用推演 · 不消耗回合 · 不推进时间</strong></div>
    <div className="ink-dialog-actions"><button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不整理</button><button className="ink-dialog-confirm" disabled={isBusy} onClick={onConfirm} type="button">确认{item ? "移出" : "不再关注"}</button></div>
  </section></div>;
}

export function StoryCorrectionDialog({ preview, error, isBusy, onCancel, onPreview, onConfirm, onEdit }: { preview: StoryCorrectionPreview | null; error: UiProblem | null; isBusy: boolean; onCancel: () => void; onPreview: (instruction: string) => void; onConfirm: () => void; onEdit: () => void }) {
  const [instruction, setInstruction] = useState("");
  const kindLabel = preview?.kind === "WORLD" ? "世界设定" : preview?.kind === "COGNITION" ? "人物认知" : "客观事实";
  return <div className="ink-dialog-backdrop" role="presentation"><section aria-modal="true" className="ink-dialog correction-dialog" role="dialog">
    <div className="ink-dialog-seal" aria-hidden="true">正</div><p className="eyebrow">修正命卷事实</p>
    <h2>{preview ? "确认这次命卷修正" : "修正命卷事实"}</h2>
    {preview ? <>
      <p>系统只会写入下面展示的变化。确认前，你仍可以返回修改或直接取消。</p>
      <div className="correction-preview"><span>{kindLabel}</span><strong>{preview.explanation}</strong><p>{preview.confirmationPrompt}</p></div>
      <div className="ink-dialog-note"><span>确认后</span><strong>不消耗回合 · 不推进时间 · 保留原有纪事</strong></div>
    </> : <>
      <p>这里用于纠正已经记录错误的事实，不是发起新的行动。系统会先生成一份明确预览，只有再次确认后才会写入命卷。</p>
      <label className="credential-field correction-field">需要纠正的内容<textarea autoFocus disabled={isBusy} maxLength={500} onChange={(event) => setInstruction(event.currentTarget.value)} placeholder="例如：林霜三年前已经离世，不应再次作为活人出现。" rows={5} value={instruction} /><small>{instruction.trim().length}/500</small></label>
    </>}
    {error && <ProblemNotice compact problem={error} />}
    {preview ? <div className="ink-dialog-actions correction-actions">
      <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">取消修正</button>
      <button className="ink-dialog-cancel" disabled={isBusy} onClick={onEdit} type="button">返回修改</button>
      <button className="ink-dialog-confirm" disabled={isBusy} onClick={onConfirm} type="button">{isBusy ? "正在写入…" : "确认写入命卷"}</button>
    </div> : <div className="ink-dialog-actions">
      <button className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不修正</button>
      <button className="ink-dialog-confirm" disabled={isBusy || instruction.trim().length < 2} onClick={() => onPreview(instruction.trim())} type="button">{isBusy ? "正在核对…" : "生成修正预览"}</button>
    </div>}
  </section></div>;
}

export function SettingsView({ fontSize, generation, email, passwordConfigured, version, isBusy, onFontSize, onPlatform, onGenerationSettings, onPasswordSettings, onSaves, onReset, onLogout, onDeleteAccount }: { fontSize: FontSizeMode; generation: StoryGenerationSettings | null; email: string; passwordConfigured: boolean; version: string; isBusy: boolean; onFontSize: (value: FontSizeMode) => void; onPlatform: () => void; onGenerationSettings: () => void; onPasswordSettings: () => void; onSaves: () => void; onReset: () => void; onLogout: () => void; onDeleteAccount: () => void }) {
  const personalProvider = generation?.personalProviders.find((item) => item.code === generation.selectedPersonalProvider)
    ?? generation?.personalProviders.find((item) => item.credentialConfigured)
    ?? generation?.personalProviders[0];
  const personalModel = personalProvider?.models.find((item) => item.code === generation?.selectedPersonalModel);
  const platformSelected = generation?.mode !== "PERSONAL";
  const personalSelected = generation?.mode === "PERSONAL";
  const personalConfigured = Boolean(personalProvider?.credentialConfigured);
  const personalReady = personalConfigured && personalProvider?.credentialStatus === "ACTIVE";
  const personalAvailable = (generation?.personalProviders.length ?? 0) > 0;
  const platformRemaining = generation?.platform.remainingTurns ?? 0;
  const selectedTier = generation?.platformTiers?.find((tier) => tier.selected);
  const platformDetail = platformRemaining > 0
    ? `全部命卷共享 · 剩余 ${platformRemaining} 点${selectedTier ? ` · 本档 ${selectedTier.pointCost} 点/次` : ""}`
    : "平台点数已用完，可接入自己的续写服务";
  const personalDetail = personalReady
    ? `${personalProvider?.name ?? "自己的服务"}${personalModel ? ` · ${personalModel.name}` : ""}`
    : personalConfigured
      ? "已保存，需要重新验证后使用"
      : "尚未接入";
  const personalAction = personalConfigured ? "管理" : "接入";
  return <div className="record-view settings-view">
    <header className="settings-heading"><h1>设置</h1></header>
    <section className="settings-section settings-reading-section"><h2>阅读</h2><div className="settings-preference-row"><div className="settings-row-copy"><strong>字号</strong><small>故事、选项与人物资料同步调整</small></div><div aria-label="字号" className="theme-switch font-size-switch">{([['compact','小字'],['standard','标准'],['large','大字']] as Array<[FontSizeMode,string]>).map(([id,label]) => <button aria-pressed={fontSize === id} data-selected={fontSize === id} data-size-option={id} key={id} onClick={() => onFontSize(id)} type="button">{label}</button>)}</div></div></section>
    <section className="settings-section generation-settings-section"><h2>续写</h2><div className="settings-option-list">
      <article className="settings-option-row settings-generation-row" data-current={platformSelected}><div className="settings-row-copy"><span className="settings-row-state">{platformSelected ? "正在使用" : "平台提供"}</span><strong>平台续写 · {selectedTier?.name ?? "默认档"}</strong><small>{platformDetail}</small></div><button disabled={isBusy} onClick={platformSelected ? onGenerationSettings : onPlatform} type="button">{platformSelected ? "管理" : "使用"}</button></article>
      {personalAvailable && <article className="settings-option-row settings-generation-row" data-current={personalSelected} data-problem={personalSelected && !personalReady}><div className="settings-row-copy"><span className="settings-row-state">{personalSelected ? personalReady ? "正在使用" : "需要验证" : personalReady ? "已接入" : personalConfigured ? "需要验证" : "可选"}</span><strong>自己的续写</strong><small>{personalDetail}</small></div><button disabled={isBusy} onClick={onGenerationSettings} type="button">{personalAction}</button></article>}
    </div></section>
    <details className="settings-fold"><summary><span><strong>命卷</strong><small>查看全部命卷 · 重开当前故事</small></span></summary><div className="settings-fold-content"><p>切换命卷不会改变已经写入的故事；重开只影响当前角色。</p><div className="settings-action-grid"><button disabled={isBusy} onClick={onSaves} type="button">我的命卷</button><button disabled={isBusy} onClick={onReset} type="button"><ReloadIcon aria-hidden="true" />重开此世</button></div></div></details>
    <details className="settings-fold settings-danger-zone"><summary><span><strong>账号</strong><small>{email || "当前账号"}</small></span></summary><div className="settings-fold-content"><p>{passwordConfigured ? "登录密码已设置。" : "请为旧账号设置登录密码。"}</p><div className="settings-action-grid"><button disabled={isBusy} onClick={onPasswordSettings} type="button">{passwordConfigured ? "修改密码" : "设置密码"}</button><button disabled={isBusy} onClick={onLogout} type="button">退出账号</button></div><button className="delete-account-button" disabled={isBusy} onClick={onDeleteAccount} type="button">注销账号与全部命卷</button></div></details>
    <footer className="settings-version"><span>问仙录</span><strong>{version}</strong></footer>
  </div>;
}
