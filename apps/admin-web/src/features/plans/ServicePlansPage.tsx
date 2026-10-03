import "./ServicePlansPage.css";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Input,
  InputNumber,
  Popconfirm,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from "antd";
import { ArrowsClockwise, Copy, FloppyDisk, Plus, RocketLaunch } from "@phosphor-icons/react";
import { useSearchParams } from "react-router-dom";
import {
  adminApi,
  type GenerationProvider,
  type SaveServicePlanPayload,
  type ServicePlan,
  type ServicePlanTier,
} from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
type TierCode = ServicePlanTier["tierCode"];
type ModelOption = { key: string; providerCode: string; modelCode: string; label: string; search: string;
  verificationStatus: string; platformEnabled: boolean };

const statusMeta = {
  DRAFT: { label: "草稿", color: "processing" },
  ACTIVE: { label: "当前生效", color: "success" },
  RETIRED: { label: "历史版本", color: "default" },
} as const;

const tierMeta: Record<TierCode, { sequence: string; eyebrow: string }> = {
  LIGHT: { sequence: "01", eyebrow: "LIGHT" },
  BALANCED: { sequence: "02", eyebrow: "BALANCED" },
  DEEP: { sequence: "03", eyebrow: "DEEP" },
};

const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";

const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;

function clonePlan(plan: ServicePlan): ServicePlan {
  return {
    ...plan,
    blockingReasons: [...plan.blockingReasons],
    tiers: plan.tiers.map((tier) => ({ ...tier })),
    taskRoutes: plan.taskRoutes.map((route) => ({ ...route })),
    modelSnapshots: plan.modelSnapshots.map((snapshot) => ({ ...snapshot })),
  };
}

function editablePayload(plan: ServicePlan): Omit<SaveServicePlanPayload, "expectedRevision"> {
  return { defaultTierCode: plan.defaultTierCode, changeNote: plan.changeNote, tiers: plan.tiers, taskRoutes: plan.taskRoutes };
}

function modelKey(providerCode?: string | null, modelCode?: string | null) {
  return providerCode && modelCode ? `${providerCode}/${modelCode}` : undefined;
}

function splitModelKey(value?: string) {
  if (!value) return { providerCode: null, modelCode: null };
  const slash = value.indexOf("/");
  return { providerCode: value.slice(0, slash), modelCode: value.slice(slash + 1) };
}

/**
 * 三档方案页只编辑版本化配置，不把“管理台已发布”误写成“玩家请求已切换”。
 * 草稿修改保留服务端 revision，发布按钮只使用最近一次服务端校验结果。
 */
export function ServicePlansPage() {
  const [params, setParams] = useSearchParams();
  const [plans, setPlans] = useState<ServicePlan[]>([]);
  const [providers, setProviders] = useState<GenerationProvider[]>([]);
  const [editing, setEditing] = useState<ServicePlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [nextPlans, nextProviders] = await Promise.all([adminApi.servicePlans(), adminApi.generationProviders()]);
      setPlans(nextPlans); setProviders(nextProviders);
    } catch (reason) { setError(errorMessage(reason, "三档方案加载失败")); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const requestedPlan = params.get("plan");
  const selected = plans.find((plan) => plan.id === requestedPlan)
    ?? plans.find((plan) => plan.status === "DRAFT")
    ?? plans.find((plan) => plan.status === "ACTIVE")
    ?? plans[0]
    ?? null;
  const active = plans.find((plan) => plan.status === "ACTIVE") ?? null;

  useEffect(() => { setEditing(selected ? clonePlan(selected) : null); }, [selected]);

  const modelOptions = useMemo<ModelOption[]>(() => providers.flatMap((provider) => provider.models.map((model) => ({
    key: modelKey(provider.providerCode, model.modelCode)!,
    providerCode: provider.providerCode,
    modelCode: model.modelCode,
    label: `${model.displayName} · ${provider.displayName} · ${model.verificationStatus === "PASSED" ? "验证通过" : model.verificationStatus === "STALE" ? "验证已过期" : model.verificationStatus === "FAILED" ? "验证失败" : "尚未验证"}${model.platformEnabled ? "" : " · 平台停用"}`,
    search: `${model.displayName} ${model.modelCode} ${provider.displayName} ${provider.providerCode}`.toLowerCase(),
    verificationStatus: model.verificationStatus,
    platformEnabled: model.platformEnabled,
  }))), [providers]);

  const dirty = Boolean(selected && editing
    && JSON.stringify(editablePayload(selected)) !== JSON.stringify(editablePayload(editing)));
  const duplicatePrimary = useMemo(() => {
    if (!editing) return false;
    const keys = editing.tiers.map((tier) => modelKey(tier.primaryProviderCode, tier.primaryModelCode)).filter(Boolean);
    return new Set(keys).size !== keys.length;
  }, [editing]);

  const choosePlan = (planId: string) => {
    const next = new URLSearchParams(params); next.set("plan", planId); setParams(next);
  };

  const replacePlan = (nextPlan: ServicePlan) => {
    setPlans((items) => items.map((item) => item.id === nextPlan.id ? nextPlan : item));
  };

  const createDraft = async () => {
    setBusy(true); setError("");
    try {
      const created = await adminApi.createServicePlanDraft(active?.id);
      setPlans((items) => [created, ...items]); choosePlan(created.id);
      setNotice(active ? `已从当前生效的 v${active.versionNumber} 创建新草稿。` : "已创建空白三档方案草稿。");
    } catch (reason) { setError(errorMessage(reason, "草稿创建失败")); }
    finally { setBusy(false); }
  };

  const cloneVersion = async (plan: ServicePlan) => {
    setBusy(true); setError("");
    try {
      const created = await adminApi.cloneServicePlan(plan.id);
      setPlans((items) => [created, ...items]); choosePlan(created.id);
      setNotice(`已从 v${plan.versionNumber} 克隆为 v${created.versionNumber} 草稿，历史版本没有被改写。`);
    } catch (reason) { setError(errorMessage(reason, "版本克隆失败")); }
    finally { setBusy(false); }
  };

  const saveDraft = async () => {
    if (!selected || !editing || selected.status !== "DRAFT") return;
    setBusy(true); setError("");
    try {
      const saved = await adminApi.saveServicePlanDraft(selected.id, {
        expectedRevision: selected.revision, ...editablePayload(editing),
      });
      replacePlan(saved); setNotice(`v${saved.versionNumber} 草稿已保存，发布状态已更新。`);
    } catch (reason) { setError(errorMessage(reason, "草稿保存失败")); }
    finally { setBusy(false); }
  };

  const publish = async () => {
    if (!selected || selected.status !== "DRAFT" || dirty) return;
    setBusy(true); setError("");
    try {
      const published = await adminApi.publishServicePlan(selected.id, selected.revision);
      await load(); choosePlan(published.id);
      setNotice(published.gameRuntimeApplied
        ? `v${published.versionNumber} 已生效；新回合将按这套档位、模型和价格执行。`
        : `v${published.versionNumber} 已发布；三档推演尚未启用，暂时不会影响玩家。`);
    } catch (reason) { setError(errorMessage(reason, "方案发布失败")); }
    finally { setBusy(false); }
  };

  const updateTier = (tierCode: TierCode, changes: Partial<ServicePlanTier>) => {
    setEditing((current) => current ? {
      ...current,
      tiers: current.tiers.map((tier) => tier.tierCode === tierCode ? { ...tier, ...changes } : tier),
    } : current);
  };

  const historyColumns: TableColumnsType<ServicePlan> = [
    { title: "版本", width: 90, render: (_, plan) => <strong>v{plan.versionNumber}</strong> },
    { title: "状态", width: 110, render: (_, plan) => <Tag color={statusMeta[plan.status].color}>{statusMeta[plan.status].label}</Tag> },
    { title: "变更说明", dataIndex: "changeNote", render: (value: string) => value || <Text type="secondary">未填写</Text> },
    { title: "发布时间", width: 160, responsive: ["md"], render: (_, plan) => fmt(plan.publishedAt) },
    { title: "操作", fixed: "right", width: 140, render: (_, plan) => <Space size={2}><Button onClick={() => choosePlan(plan.id)} size="small" type="link">查看</Button><Button disabled={busy} onClick={() => void cloneVersion(plan)} size="small" type="link">克隆</Button></Space> },
  ];

  return <div className="service-plans-page">
    {notice && <Alert closable title={notice} onClose={() => setNotice("")} showIcon type="success" />}
    {error && <Alert action={<Button onClick={() => void load()} size="small">重新读取</Button>} closable description={error} title="方案操作未完成" onClose={() => setError("")} showIcon type="error" />}
    <section className="service-plans-intro">
      <div><Title level={2}>三档推演方案</Title><Paragraph>为轻量、均衡、深度三档配置价格、主模型和备用模型。玩家只会看到档位与推演点。</Paragraph></div>
      <Space wrap><Button icon={<ArrowsClockwise size={17} />} loading={loading} onClick={() => void load()}>刷新</Button><Button icon={<Plus size={17} />} loading={busy} onClick={() => void createDraft()} type="primary">{active ? "从生效版本新建" : "新建草稿"}</Button></Space>
    </section>
    <Alert
      className="service-plans-runtime-alert"
      description={active?.gameRuntimeApplied
        ? "新回合将按当前方案计点并选择模型；重试或切换备用模型不会改变本次价格。"
        : "方案可以先发布保存，启用三档推演后才会影响玩家的新回合。"}
      title={active?.gameRuntimeApplied ? "当前方案已生效" : "三档推演尚未启用"}
      showIcon
      type={active?.gameRuntimeApplied ? "success" : "warning"}
    />
    <section className="service-plans-summary" aria-label="方案状态">
      <div><span>当前生效版本</span><strong>{active ? `v${active.versionNumber}` : "未发布"}</strong><small>{active ? fmt(active.publishedAt) : "先创建并完成草稿"}</small></div>
      <div><span>候选模型</span><strong>{modelOptions.length}</strong><small>{providers.length} 个供应商</small></div>
      <div><span>可发布草稿</span><strong>{plans.filter((plan) => plan.status === "DRAFT" && plan.publishable).length}</strong><small>符合当前发布条件</small></div>
      <div><span>当前使用方案</span><strong>{active?.gameRuntimeApplied ? `v${active.versionNumber}` : "尚未启用"}</strong><small>{active?.gameRuntimeApplied ? "新回合按此方案执行" : "启用前不影响玩家"}</small></div>
    </section>

    {!selected || !editing ? <section className="service-plans-empty"><Empty description={loading ? "正在读取方案…" : "还没有方案，先创建一个草稿"} /></section> : <>
      <section className="service-plan-workspace">
        <div className="service-plan-heading">
          <div><Space><Title level={3}>方案 v{selected.versionNumber}</Title><Tag color={statusMeta[selected.status].color}>{statusMeta[selected.status].label}</Tag>{dirty && <Tag color="warning">有未保存修改</Tag>}</Space><Paragraph>{selected.status === "DRAFT" ? `草稿修订 r${selected.revision} · 保存后会更新发布状态` : `由 ${selected.publishedBy || selected.createdBy} 发布于 ${fmt(selected.publishedAt)}`}</Paragraph></div>
          <Space wrap><Button icon={<Copy size={16} />} loading={busy} onClick={() => void cloneVersion(selected)}>克隆为新草稿</Button>{selected.status === "DRAFT" && <Button disabled={!dirty} icon={<FloppyDisk size={16} />} loading={busy} onClick={() => void saveDraft()}>保存草稿</Button>}{selected.status === "DRAFT" && <Popconfirm cancelText="取消" description={active?.gameRuntimeApplied ? "发布后会替换当前方案，新回合将按新方案计点并选择模型。" : "发布后会替换当前方案；启用三档推演前不会影响玩家。"} disabled={!selected.publishable || dirty || busy} okText="确认发布" onConfirm={() => void publish()} title="发布此三档方案？"><Button disabled={!selected.publishable || dirty || busy} icon={<RocketLaunch size={16} />} type="primary">发布方案</Button></Popconfirm>}</Space>
        </div>

        {selected.status === "DRAFT" && (selected.blockingReasons.length > 0 || duplicatePrimary || dirty) && <Alert
          className="service-plan-blockers"
          description={<ul>{dirty && <li>当前修改尚未保存；保存后会更新发布状态。</li>}{duplicatePrimary && <li>轻量、均衡、深度的主模型必须互不相同。</li>}{!dirty && selected.blockingReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>}
          title="发布前还需处理"
          showIcon
          type="warning"
        />}

        <div className="service-plan-basics">
          <label><span>版本变更说明</span><Input disabled={selected.status !== "DRAFT"} maxLength={500} onChange={(event) => setEditing({ ...editing, changeNote: event.currentTarget.value })} placeholder="例如：均衡档切换为新验证模型" value={editing.changeNote} /></label>
          <div><span>默认档位</span><Radio.Group buttonStyle="solid" disabled={selected.status !== "DRAFT"} onChange={(event) => setEditing({ ...editing, defaultTierCode: event.target.value as TierCode })} value={editing.defaultTierCode}>{editing.tiers.map((tier) => <Radio.Button key={tier.tierCode} value={tier.tierCode}>{tier.displayName}</Radio.Button>)}</Radio.Group></div>
        </div>

        <div className="service-tier-grid">
          {editing.tiers.map((tier) => <TierCard editable={selected.status === "DRAFT"} key={tier.tierCode} modelOptions={modelOptions} onChange={(changes) => updateTier(tier.tierCode, changes)} tier={tier} />)}
        </div>

        <div className="service-plan-evidence">
          <Descriptions column={{ xs: 1, md: 3 }} size="small" title="版本信息">
            <Descriptions.Item label="模型分配">{editing.taskRoutes.length > 0 ? `${editing.taskRoutes.length} 项单独配置` : "使用各档默认模型"}</Descriptions.Item>
            <Descriptions.Item label="发布时模型配置">{selected.modelSnapshots.length} 条</Descriptions.Item>
            <Descriptions.Item label="当前使用">{selected.gameRuntimeApplied ? "是" : "否"}</Descriptions.Item>
            <Descriptions.Item label="创建人">{selected.createdBy}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{fmt(selected.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="来源版本">{selected.basedOnVersionId ? plans.find((plan) => plan.id === selected.basedOnVersionId)?.versionNumber ? `v${plans.find((plan) => plan.id === selected.basedOnVersionId)?.versionNumber}` : selected.basedOnVersionId : "空白草稿"}</Descriptions.Item>
          </Descriptions>
          {selected.modelSnapshots.length > 0 && <div className="service-plan-snapshots"><Text strong>发布时模型配置</Text><Space size={[5, 5]} wrap>{selected.modelSnapshots.map((snapshot) => <Tag key={`${snapshot.providerCode}/${snapshot.modelCode}`}>{snapshot.providerCode}/{snapshot.modelCode} · 供应商 v{snapshot.providerConfigVersion} / 模型 v{snapshot.modelConfigVersion}</Tag>)}</Space></div>}
        </div>
      </section>

      <section className="service-plan-history">
        <div><Title level={4}>版本历史</Title><Paragraph>历史版本可以查看或克隆；需要恢复旧配置时，请先克隆为新草稿。</Paragraph></div>
        <Table<ServicePlan> columns={historyColumns} dataSource={plans} loading={loading} pagination={false} rowKey="id" scroll={{ x: 650 }} size="middle" />
      </section>
    </>}
  </div>;
}

function TierCard({ tier, modelOptions, editable, onChange }: {
  tier: ServicePlanTier; modelOptions: ModelOption[]; editable: boolean;
  onChange: (changes: Partial<ServicePlanTier>) => void;
}) {
  const selectedPrimary = modelOptions.find((option) => option.key === modelKey(tier.primaryProviderCode, tier.primaryModelCode));
  const selectedFallback = modelOptions.find((option) => option.key === modelKey(tier.fallbackProviderCode, tier.fallbackModelCode));
  const selectOptions = modelOptions.map((option) => ({ value: option.key, label: option.label, search: option.search }));
  const selectProps = {
    allowClear: true,
    disabled: !editable,
    filterOption: (input: string, option?: { search?: string }) => option?.search?.includes(input.toLowerCase()) ?? false,
    options: selectOptions,
    showSearch: true,
  };
  const selectModel = (value: string | undefined, fallback: boolean) => {
    const pair = splitModelKey(value);
    onChange(fallback
      ? { fallbackProviderCode: pair.providerCode, fallbackModelCode: pair.modelCode }
      : { primaryProviderCode: pair.providerCode, primaryModelCode: pair.modelCode });
  };
  return <article className={`service-tier-card service-tier-${tier.tierCode.toLowerCase()}`}>
    <header><span>{tierMeta[tier.tierCode].sequence}</span><div><small>{tierMeta[tier.tierCode].eyebrow}</small><Input disabled={!editable} maxLength={40} onChange={(event) => onChange({ displayName: event.currentTarget.value })} value={tier.displayName} variant="borderless" /></div><Switch checked={tier.enabled} disabled={!editable} onChange={(enabled) => onChange({ enabled })} /></header>
    <Input.TextArea autoSize={{ minRows: 2, maxRows: 3 }} disabled={!editable} maxLength={240} onChange={(event) => onChange({ description: event.currentTarget.value })} placeholder="玩家可见的档位说明" value={tier.description} />
    <label className="service-tier-points"><span>每次成功回合</span><InputNumber disabled={!editable} max={100000} min={0} onChange={(value) => onChange({ pointCost: Number(value ?? 0) })} precision={0} suffix="推演点" value={tier.pointCost} /></label>
    <label><span>主模型</span><Select {...selectProps} onChange={(value) => selectModel(value, false)} placeholder="从模型库选择" value={modelKey(tier.primaryProviderCode, tier.primaryModelCode)} /></label>
    {selectedPrimary && <ModelState option={selectedPrimary} />}
    <div className="service-tier-fallback-switch"><div><strong>备用模型</strong><small>主模型失败后自动使用</small></div><Switch checked={tier.fallbackEnabled} disabled={!editable} onChange={(enabled) => onChange(enabled ? { fallbackEnabled: true } : { fallbackEnabled: false, fallbackProviderCode: null, fallbackModelCode: null })} /></div>
    {tier.fallbackEnabled && <><label><span>备用模型</span><Select {...selectProps} onChange={(value) => selectModel(value, true)} placeholder="选择不同于主模型的备用模型" value={modelKey(tier.fallbackProviderCode, tier.fallbackModelCode)} /></label>{selectedFallback && <ModelState option={selectedFallback} />}</>}
  </article>;
}

function ModelState({ option }: { option: ModelOption }) {
  const valid = option.verificationStatus === "PASSED" && option.platformEnabled;
  return <div className={`service-model-state ${valid ? "is-valid" : "is-warning"}`}><span>{valid ? "可以发布" : "暂时不能发布"}</span><small>{option.verificationStatus === "PASSED" ? "当前验证有效" : option.verificationStatus === "STALE" ? "配置已变化，请重新验证" : option.verificationStatus === "FAILED" ? "最近一次验证失败" : "尚未验证"}{option.platformEnabled ? "" : " · 平台调用已停用"}</small></div>;
}
