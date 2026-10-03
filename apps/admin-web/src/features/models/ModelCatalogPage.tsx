import "./ModelCatalogPage.css";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from "antd";
import { ArrowsClockwise, Gear, Plus } from "@phosphor-icons/react";
import { useSearchParams } from "react-router-dom";
import { adminApi, type GenerationProvider } from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
type Model = GenerationProvider["models"][number];
type ModelRow = { key: string; provider: GenerationProvider; model: Model };
type ModelForm = { displayName: string; apiModelName: string; capabilityCodes: string[]; contextWindow: number; maximumOutputTokens: number; concurrencyLimit: number; platformEnabled: boolean; userSelectable: boolean };
type ProviderForm = { displayName: string; baseUrl: string; apiKey?: string; enabled: boolean; userKeyEnabled: boolean };
type NewProviderForm = ProviderForm & { providerCode: string };
type NewModelForm = { providerCode: string; modelCode: string } & ModelForm;

const CAPABILITY_LABELS: Record<string, string> = {
  CHAT: "对话",
  JSON_MODE: "结构化输出",
  JSON_SCHEMA: "JSON Schema",
  TOOLS: "工具调用",
  STREAMING: "流式输出",
  THINKING_CONTROL: "思考控制",
  CACHE_USAGE: "缓存用量",
};
const capabilityLabel = (code: string) => CAPABILITY_LABELS[code] ?? code;
const CAPABILITY_OPTIONS = Object.keys(CAPABILITY_LABELS)
  .map((value) => ({ value, label: capabilityLabel(value) }));

const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";

const verificationMeta = {
  PASSED: { label: "验证通过", color: "success" },
  FAILED: { label: "验证失败", color: "error" },
  STALE: { label: "验证已过期", color: "warning" },
  UNVERIFIED: { label: "尚未验证", color: "default" },
} as const;

function modelKey(providerCode: string, modelCode: string) {
  return `${providerCode}/${modelCode}`;
}

function errorMessage(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback;
}

/**
 * 模型库页把任意数量的内部模型压平为可筛选表格，编辑细节留在 Drawer。
 * URL 只保存可分享的查询状态，不保存密钥、表单草稿或高风险确认状态。
 */
export function ModelCatalogPage() {
  const [params, setParams] = useSearchParams();
  const [providers, setProviders] = useState<GenerationProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [providerDrawerOpen, setProviderDrawerOpen] = useState(false);
  const [newProviderOpen, setNewProviderOpen] = useState(false);
  const [newModelOpen, setNewModelOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setProviders(await adminApi.generationProviders()); }
    catch (reason) { setError(errorMessage(reason, "模型库加载失败")); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const rows = useMemo<ModelRow[]>(() => providers.flatMap((provider) => provider.models.map((model) => ({
    key: modelKey(provider.providerCode, model.modelCode), provider, model,
  }))), [providers]);
  const selected = rows.find((row) => row.key === selectedKey) ?? null;
  const query = params.get("q") ?? "";
  const [queryDraft, setQueryDraft] = useState(query);
  const providerFilter = params.get("provider") ?? "ALL";
  const verificationFilter = params.get("verification") ?? "ALL";
  const page = Math.max(1, Number(params.get("page")) || 1);
  const size = [10, 20, 50].includes(Number(params.get("size"))) ? Number(params.get("size")) : 10;

  useEffect(() => setQueryDraft(query), [query]);

  const updateParams = (changes: Record<string, string | number | null>, resetPage = false) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => {
      if (value === null || value === "" || value === "ALL" || (key === "page" && value === 1) || (key === "size" && value === 10)) next.delete(key);
      else next.set(key, String(value));
    });
    if (resetPage) next.delete("page");
    // 已确认的筛选和分页写入历史记录，使浏览器前进、后退与复制链接都能恢复同一视图。
    setParams(next);
  };

  const filteredRows = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return rows.filter(({ provider, model }) => {
      if (providerFilter !== "ALL" && provider.providerCode !== providerFilter) return false;
      if (verificationFilter !== "ALL" && model.verificationStatus !== verificationFilter) return false;
      if (!normalized) return true;
      return [provider.providerCode, provider.displayName, model.modelCode, model.displayName, model.apiModelName]
        .some((value) => value.toLowerCase().includes(normalized));
    });
  }, [providerFilter, query, rows, verificationFilter]);

  const columns: TableColumnsType<ModelRow> = [
    {
      title: "模型", key: "model", width: 260,
      render: (_, row) => <div className="model-catalog-identity"><strong>{row.model.displayName}</strong><span>{row.model.modelCode}</span><Tag>{row.provider.displayName}</Tag></div>,
    },
    {
      title: "验证状态", key: "verification", width: 145,
      render: (_, row) => { const meta = verificationMeta[row.model.verificationStatus]; return <div className="model-catalog-status"><Tag color={meta.color}>{meta.label}</Tag><small>{fmt(row.model.verifiedAt)}</small></div>; },
    },
    {
      title: "已登记能力", key: "capabilities", responsive: ["lg"],
      render: (_, row) => <Space size={[4, 4]} wrap>{row.model.capabilityCodes.length ? row.model.capabilityCodes.map((code) => <Tag key={code} title={code}>{capabilityLabel(code)}</Tag>) : <Text type="secondary">无</Text>}</Space>,
    },
    {
      title: "使用情况", key: "tasks", width: 170,
      render: (_, row) => row.model.activeTaskCodes.length || row.model.servicePlanReferences.length
        ? <div className="model-catalog-status"><strong>{row.model.activeTaskCodes.length} 项任务 · {row.model.servicePlanReferences.length} 处方案</strong><small>{row.model.allPlatformTasks ? "全部平台任务正在使用" : "平台任务或三档方案正在使用"}</small></div>
        : <Text type="secondary">暂未使用</Text>,
    },
    {
      title: "可用范围", key: "availability", width: 150, responsive: ["md"],
      render: (_, row) => <Space size={[4, 4]} wrap><Tag color={row.model.platformEnabled ? "blue" : "default"}>{row.model.platformEnabled ? "平台可用" : "平台停用"}</Tag><Tag color={row.model.userSelectable ? "cyan" : "default"}>{row.model.userSelectable ? "玩家自备可选" : "玩家自备不可选"}</Tag></Space>,
    },
    {
      title: "操作", key: "action", fixed: "right", width: 92,
      render: (_, row) => <Button onClick={(event) => { event.stopPropagation(); setSelectedKey(row.key); }} size="small" type="link">查看</Button>,
    },
  ];

  const refresh = async (message?: string) => {
    if (message) setNotice(message);
    await load();
  };

  return <div className="model-catalog-page">
    {notice && <Alert closable title={notice} onClose={() => setNotice("")} showIcon type="success" />}
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} title="模型库暂时无法读取" description={error} showIcon type="error" />}
    <section className="model-catalog-intro">
      <div><Title level={2}>模型库</Title><Paragraph>维护平台和玩家自备服务可用的供应商与模型。三档方案从这里选择模型。</Paragraph></div>
      <Space wrap><Button icon={<Gear size={17} />} onClick={() => setProviderDrawerOpen(true)}>供应商设置</Button><Button icon={<Plus size={17} />} onClick={() => setNewModelOpen(true)} type="primary">新增模型</Button></Space>
    </section>
    <section className="model-catalog-summary" aria-label="模型库概况">
      <div><span>模型总数</span><strong>{rows.length}</strong><small>{providers.length} 个供应商</small></div>
      <div><span>已启用模型</span><strong>{rows.filter(({ model }) => model.platformEnabled).length}</strong><small>平台可以使用</small></div>
      <div><span>验证仍有效</span><strong>{rows.filter(({ model }) => model.verificationStatus === "PASSED").length}</strong><small>可用于新方案</small></div>
      <div><span>当前使用中</span><strong>{rows.filter(({ model }) => model.activeTaskCodes.length > 0).length}</strong><small>平台任务正在使用</small></div>
    </section>
    <section className="model-catalog-table-card">
      <div className="model-catalog-filters">
        <Input.Search
          allowClear
          enterButton="搜索"
          onChange={(event) => { setQueryDraft(event.currentTarget.value); if (!event.currentTarget.value) updateParams({ q: null }, true); }}
          onSearch={(value) => updateParams({ q: value.trim() }, true)}
          placeholder="搜索模型、供应商或 API 名称"
          value={queryDraft}
        />
        <Select aria-label="供应商筛选" onChange={(value) => updateParams({ provider: value }, true)} options={[{ value: "ALL", label: "全部供应商" }, ...providers.map((provider) => ({ value: provider.providerCode, label: provider.displayName }))]} value={providerFilter} />
        <Select aria-label="验证状态筛选" onChange={(value) => updateParams({ verification: value }, true)} options={[{ value: "ALL", label: "全部验证状态" }, ...Object.entries(verificationMeta).map(([value, item]) => ({ value, label: item.label }))]} value={verificationFilter} />
        <Button aria-label="刷新模型库" icon={<ArrowsClockwise size={17} />} loading={loading} onClick={() => void load()}>刷新</Button>
      </div>
      <Table<ModelRow>
        columns={columns}
        dataSource={filteredRows}
        locale={{ emptyText: loading ? "正在读取模型目录…" : <Empty description="没有符合条件的模型" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
        loading={loading}
        onChange={(pagination) => updateParams({ page: pagination.current ?? 1, size: pagination.pageSize ?? 10 })}
        onRow={(row) => ({ onClick: () => setSelectedKey(row.key) })}
        pagination={{ current: page, pageSize: size, showSizeChanger: true, total: filteredRows.length, pageSizeOptions: [10, 20, 50], showTotal: (total) => `共 ${total} 个模型` }}
        rowClassName="model-catalog-row"
        scroll={{ x: 900 }}
        size="middle"
      />
    </section>
    <ModelDetailDrawer modelRow={selected} onClose={() => setSelectedKey(null)} onSaved={refresh} />
    <ProviderSettingsDrawer onAdd={() => setNewProviderOpen(true)} onClose={() => setProviderDrawerOpen(false)} onSaved={refresh} open={providerDrawerOpen} providers={providers} />
    <NewProviderModal onClose={() => setNewProviderOpen(false)} onSaved={refresh} open={newProviderOpen} />
    <NewModelModal onClose={() => setNewModelOpen(false)} onSaved={refresh} open={newModelOpen} providers={providers} />
  </div>;
}

function ModelDetailDrawer({ modelRow, onClose, onSaved }: { modelRow: ModelRow | null; onClose: () => void; onSaved: (message?: string) => Promise<void> }) {
  const [form] = Form.useForm<ModelForm>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const provider = modelRow?.provider;
  const model = modelRow?.model;

  useEffect(() => {
    if (!model) return;
    form.setFieldsValue({ displayName: model.displayName, apiModelName: model.apiModelName,
      capabilityCodes: model.capabilityCodes, contextWindow: model.contextWindow,
      maximumOutputTokens: model.maximumOutputTokens, concurrencyLimit: model.concurrencyLimit,
      platformEnabled: model.platformEnabled, userSelectable: model.userSelectable });
    setError("");
  }, [form, model]);

  const save = async () => {
    if (!provider || !model) return;
    setBusy(true); setError("");
    try {
      const values = await form.validateFields();
      await adminApi.updateGenerationModel(provider.providerCode, model.modelCode, { ...values, applyToAllTasks: false });
      await onSaved(`${provider.displayName} · ${values.displayName} 已保存。`);
    } catch (reason) { setError(errorMessage(reason, "模型保存失败")); }
    finally { setBusy(false); }
  };

  const applyAll = async () => {
    if (!provider || !model) return;
    setBusy(true); setError("");
    try {
      const values = await form.validateFields();
      await adminApi.updateGenerationModel(provider.providerCode, model.modelCode, { ...values, applyToAllTasks: true });
      await onSaved(`${values.displayName} 已切换为全部当前平台任务的主模型。`);
    } catch (reason) { setError(errorMessage(reason, "任务路由切换失败")); }
    finally { setBusy(false); }
  };

  const validate = async () => {
    if (!provider || !model) return;
    setBusy(true); setError("");
    try {
      const values = await form.validateFields();
      await adminApi.updateGenerationModel(provider.providerCode, model.modelCode,
        { ...values, applyToAllTasks: false });
      const result = await adminApi.validateGenerationModel(provider.providerCode, model.modelCode);
      await onSaved(result.status === "PASSED"
        ? `${values.displayName} 已通过连接和结构化输出验证。`
        : `${values.displayName} 验证未通过：${result.errorSummary ?? result.errorCode ?? "请查看最新验证记录"}`);
    } catch (reason) { setError(errorMessage(reason, "模型验证失败")); }
    finally { setBusy(false); }
  };

  const meta = model ? verificationMeta[model.verificationStatus] : verificationMeta.UNVERIFIED;
  const runtimeLocked = Boolean(model && (model.activeTaskCodes.length > 0 || model.servicePlanReferences.length > 0));
  return <Drawer className="model-catalog-drawer" destroyOnHidden onClose={onClose} open={Boolean(modelRow)} size="large" title={model ? <div className="model-drawer-title"><span>{provider?.displayName}</span><strong>{model.displayName}</strong><small>{model.modelCode}</small></div> : "模型详情"}>
    {provider && model && <>
      {error && <Alert closable title={error} onClose={() => setError("")} showIcon type="error" />}
      <Alert
        className="model-verification-alert"
        description={model.verificationStatus === "STALE" ? "供应商连接或 API 模型名已经变化，请重新验证。" : model.verificationStatus === "UNVERIFIED" ? "该模型尚未验证，验证通过前不能用于新方案。" : model.verificationStatus === "FAILED" ? model.verificationErrorSummary || "最近一次验证未通过。" : `最近验证覆盖 ${model.verifiedCapabilityCodes.join("、") || "基础连接"}，耗时 ${model.verificationLatencyMs ?? "—"} ms。`}
        title={meta.label}
        showIcon
        type={model.verificationStatus === "PASSED" ? "success" : model.verificationStatus === "FAILED" ? "error" : model.verificationStatus === "STALE" ? "warning" : "info"}
      />
      <Descriptions className="model-catalog-descriptions" column={{ xs: 1, sm: 2 }} size="small">
        <Descriptions.Item label="供应商">{provider.displayName}</Descriptions.Item>
        <Descriptions.Item label="适配协议">{provider.adapterType}</Descriptions.Item>
        <Descriptions.Item label="上下文窗口">{model.contextWindow.toLocaleString("zh-CN")} Token</Descriptions.Item>
        <Descriptions.Item label="最大输出">{model.maximumOutputTokens.toLocaleString("zh-CN")} Token</Descriptions.Item>
        <Descriptions.Item label="建议并发">{model.concurrencyLimit}</Descriptions.Item>
        <Descriptions.Item label="配置版本">供应商 v{provider.configVersion} / 模型 v{model.configVersion}</Descriptions.Item>
        <Descriptions.Item label="最近验证">{fmt(model.verifiedAt)}</Descriptions.Item>
        <Descriptions.Item label="配置更新">{fmt(model.updatedAt)}</Descriptions.Item>
      </Descriptions>
      <div className="model-capabilities"><Text strong>已登记能力</Text><Space size={[4, 4]} wrap>{model.capabilityCodes.length ? model.capabilityCodes.map((code) => <Tag key={code} title={code}>{capabilityLabel(code)}</Tag>) : <Text type="secondary">尚未登记</Text>}</Space></div>
      <Divider />
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item label="管理台名称" name="displayName" rules={[{ required: true, message: "请输入模型名称" }, { max: 80 }]}><Input /></Form.Item>
        <Form.Item extra={runtimeLocked ? "该模型正在被平台任务或三档方案使用，请先解除使用后再修改。" : "修改连接参数后，需要重新验证模型。"} label="供应商 API 模型名" name="apiModelName" rules={[{ required: true, message: "请输入 API 模型名" }, { max: 120 }]}><Input disabled={runtimeLocked} /></Form.Item>
        <Form.Item extra="这些能力需要通过实际验证后，才能用于三档方案。" label="能力标签" name="capabilityCodes"><Select disabled={runtimeLocked} mode="multiple" options={CAPABILITY_OPTIONS} placeholder="尚未登记能力" /></Form.Item>
        <div className="model-runtime-grid">
          <Form.Item label="上下文窗口（Token）" name="contextWindow" rules={[{ required: true }]}><InputNumber disabled={runtimeLocked} max={2_000_000} min={1024} step={1024} /></Form.Item>
          <Form.Item dependencies={["contextWindow"]} label="最大输出（Token）" name="maximumOutputTokens" rules={[{ required: true }, ({ getFieldValue }) => ({ validator(_, value) { return value <= getFieldValue("contextWindow") ? Promise.resolve() : Promise.reject(new Error("不能超过上下文窗口")); } })]}><InputNumber disabled={runtimeLocked} min={256} step={256} /></Form.Item>
          <Form.Item label="建议并发" name="concurrencyLimit" rules={[{ required: true }]}><InputNumber disabled={runtimeLocked} max={1000} min={1} /></Form.Item>
        </div>
        <div className="model-toggle-grid">
          <Form.Item label="允许平台任务调用" name="platformEnabled" valuePropName="checked"><Switch disabled={runtimeLocked && model.platformEnabled} /></Form.Item>
          <Form.Item label="允许玩家自备服务选择" name="userSelectable" valuePropName="checked"><Switch /></Form.Item>
        </div>
      </Form>
      {model.activeTaskCodes.length > 0 && <div className="model-route-references"><Text strong>当前任务使用</Text><Space size={[4, 4]} wrap>{model.activeTaskCodes.map((task) => <Tag color="blue" key={task}>{task}</Tag>)}</Space></div>}
      {model.servicePlanReferences.length > 0 && <div className="model-route-references"><Text strong>三档方案使用</Text><Space size={[4, 4]} wrap>{model.servicePlanReferences.map((reference) => <Tag color={reference.status === "ACTIVE" ? "green" : "gold"} key={`${reference.planId}-${reference.tierCode}-${reference.taskCode ?? "default"}-${reference.usage}`}>v{reference.versionNumber} · {reference.status === "ACTIVE" ? "生效" : "草稿"} · {reference.tierCode} · {reference.taskCode ?? "档位默认"} · {reference.usage.includes("FALLBACK") ? "备用模型" : "主模型"}</Tag>)}</Space></div>}
      <div className="model-drawer-actions">
        <Button loading={busy} onClick={() => void save()} type="primary">保存模型</Button>
        <Button disabled={!provider.keyConfigured || busy} onClick={() => void validate()}>验证模型</Button>
        <Popconfirm
          cancelText="取消"
          description={`现有 ${provider.platformTaskCount} 项平台任务将改用此模型，备用模型保持不变。`}
          okText="确认切换"
          onConfirm={() => void applyAll()}
          title="应用到全部当前平台任务？"
        >
          <Button disabled={!model.platformEnabled || model.verificationStatus !== "PASSED" || model.allPlatformTasks || busy}>{model.allPlatformTasks ? "全部任务正在使用" : model.verificationStatus !== "PASSED" ? "验证通过后才能切换" : "应用到全部当前任务"}</Button>
        </Popconfirm>
      </div>
    </>}
  </Drawer>;
}

function ProviderSettingsDrawer({ providers, open, onClose, onAdd, onSaved }: { providers: GenerationProvider[]; open: boolean; onClose: () => void; onAdd: () => void; onSaved: (message?: string) => Promise<void> }) {
  const [selectedCode, setSelectedCode] = useState<string>();
  const [form] = Form.useForm<ProviderForm>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const provider = providers.find((item) => item.providerCode === selectedCode) ?? providers[0];

  useEffect(() => {
    // Drawer 销毁内容时表单尚未挂载，只有打开后才同步供应商配置，避免操作游离的 Form 实例。
    if (!open || !provider) return;
    setSelectedCode(provider.providerCode);
    form.setFieldsValue({ displayName: provider.displayName, baseUrl: provider.baseUrl, apiKey: "", enabled: provider.enabled, userKeyEnabled: provider.userKeyEnabled });
    setError("");
  }, [form, open, provider]);

  const save = async () => {
    if (!provider) return;
    setBusy(true); setError("");
    try {
      const values = await form.validateFields();
      await adminApi.updateGenerationProvider(provider.providerCode, values);
      form.setFieldValue("apiKey", "");
      await onSaved(`${values.displayName} 的供应商配置已保存。`);
    } catch (reason) { setError(errorMessage(reason, "供应商保存失败")); }
    finally { setBusy(false); }
  };

  return <Drawer className="model-catalog-drawer" destroyOnHidden extra={<Button icon={<Plus size={16} />} onClick={onAdd}>新增</Button>} onClose={onClose} open={open} size="large" title="供应商设置">
    {!provider ? <Empty description="还没有供应商，请先新增" /> : <>
      {error && <Alert closable title={error} onClose={() => setError("")} showIcon type="error" />}
      <Select className="provider-select" onChange={setSelectedCode} options={providers.map((item) => ({ value: item.providerCode, label: `${item.displayName} · ${item.providerCode}` }))} value={provider.providerCode} />
      <Descriptions className="model-catalog-descriptions" column={1} size="small">
        <Descriptions.Item label="协议">{provider.adapterType}</Descriptions.Item>
        <Descriptions.Item label="平台密钥">{provider.keyConfigured ? `已配置 · •••• ${provider.keySuffix}` : "未配置"}</Descriptions.Item>
        <Descriptions.Item label="配置版本">v{provider.configVersion}</Descriptions.Item>
      </Descriptions>
      {provider.keyState === "DECRYPT_ERROR" && <Alert title="平台密钥无法解密，请重新保存密钥。" showIcon type="warning" />}
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item label="供应商名称" name="displayName" rules={[{ required: true }, { max: 80 }]}><Input /></Form.Item>
        <Form.Item extra="仅允许 HTTPS。修改后当前验证记录会自动过期。" label="Base URL" name="baseUrl" rules={[{ required: true }, { type: "url" }]}><Input /></Form.Item>
        <Form.Item extra="留空不会更改现有密钥；保存后无法查看完整内容。" label="更新平台密钥" name="apiKey"><Input.Password autoComplete="new-password" /></Form.Item>
        <div className="model-toggle-grid">
          <Form.Item label="启用供应商" name="enabled" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item label="允许玩家自备密钥" name="userKeyEnabled" valuePropName="checked"><Switch /></Form.Item>
        </div>
      </Form>
      <Button block loading={busy} onClick={() => void save()} type="primary">保存供应商</Button>
    </>}
  </Drawer>;
}

function NewProviderModal({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: (message?: string) => Promise<void> }) {
  const [form] = Form.useForm<NewProviderForm>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async () => {
    setBusy(true); setError("");
    try {
      const values = await form.validateFields();
      await adminApi.updateGenerationProvider(values.providerCode, values);
      form.resetFields(); onClose();
      await onSaved(`${values.displayName} 已加入供应商目录。`);
    } catch (reason) { setError(errorMessage(reason, "供应商创建失败")); }
    finally { setBusy(false); }
  };
  return <Modal cancelText="取消" forceRender okButtonProps={{ loading: busy }} okText="创建供应商" onCancel={onClose} onOk={() => void submit()} open={open} title="新增供应商">
    {error && <Alert closable title={error} onClose={() => setError("")} showIcon type="error" />}
    <Form form={form} initialValues={{ enabled: false, userKeyEnabled: true }} layout="vertical" requiredMark={false}>
      <Form.Item extra="创建后不可修改，用于数据库关联与审计。" label="供应商代号" name="providerCode" normalize={(value) => String(value ?? "").toUpperCase()} rules={[{ required: true }, { pattern: /^[A-Z0-9_]{2,32}$/, message: "使用 2-32 位大写字母、数字或下划线" }]}><Input placeholder="例如 MINIMAX" /></Form.Item>
      <Form.Item label="供应商名称" name="displayName" rules={[{ required: true }, { max: 80 }]}><Input placeholder="例如 MiniMax" /></Form.Item>
      <Form.Item label="Base URL" name="baseUrl" rules={[{ required: true }, { type: "url" }]}><Input placeholder="https://api.example.com" /></Form.Item>
      <Form.Item label="平台密钥（可稍后配置）" name="apiKey"><Input.Password autoComplete="new-password" /></Form.Item>
      <div className="model-toggle-grid"><Form.Item label="创建后启用" name="enabled" valuePropName="checked"><Switch /></Form.Item><Form.Item label="允许玩家自备密钥" name="userKeyEnabled" valuePropName="checked"><Switch /></Form.Item></div>
    </Form>
  </Modal>;
}

function NewModelModal({ providers, open, onClose, onSaved }: { providers: GenerationProvider[]; open: boolean; onClose: () => void; onSaved: (message?: string) => Promise<void> }) {
  const [form] = Form.useForm<NewModelForm>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async () => {
    setBusy(true); setError("");
    try {
      const values = await form.validateFields();
      await adminApi.updateGenerationModel(values.providerCode, values.modelCode, {
        displayName: values.displayName, apiModelName: values.apiModelName,
        capabilityCodes: values.capabilityCodes, contextWindow: values.contextWindow,
        maximumOutputTokens: values.maximumOutputTokens, concurrencyLimit: values.concurrencyLimit,
        platformEnabled: false, userSelectable: false, applyToAllTasks: false,
      });
      form.resetFields(); onClose();
      await onSaved(`${values.displayName} 已加入模型库，默认保持停用并等待验证。`);
    } catch (reason) { setError(errorMessage(reason, "模型创建失败")); }
    finally { setBusy(false); }
  };
  return <Modal cancelText="取消" forceRender okButtonProps={{ disabled: providers.length === 0, loading: busy }} okText="创建模型" onCancel={onClose} onOk={() => void submit()} open={open} title="新增模型">
    {error && <Alert closable title={error} onClose={() => setError("")} showIcon type="error" />}
    {providers.length === 0 ? <Empty description="请先创建供应商" /> : <Form form={form} initialValues={{ capabilityCodes: [], contextWindow: 64000, maximumOutputTokens: 8000, concurrencyLimit: 4 }} layout="vertical" requiredMark={false}>
      <Form.Item label="所属供应商" name="providerCode" rules={[{ required: true }]}><Select options={providers.map((provider) => ({ value: provider.providerCode, label: provider.displayName }))} /></Form.Item>
      <Form.Item extra="创建后不可修改，用于方案版本和历史回合引用。" label="模型代号" name="modelCode" rules={[{ required: true }, { pattern: /^[A-Za-z0-9._-]{2,80}$/, message: "使用 2-80 位字母、数字、点、横线或下划线" }]}><Input placeholder="例如 MiniMax-M2.1" /></Form.Item>
      <Form.Item label="管理台名称" name="displayName" rules={[{ required: true }, { max: 80 }]}><Input placeholder="例如 MiniMax M2.1" /></Form.Item>
      <Form.Item label="供应商真实 API 模型名" name="apiModelName" rules={[{ required: true }, { max: 120 }]}><Input /></Form.Item>
      <Form.Item extra="不要根据模型名称或兼容协议猜测能力。" label="已确认的能力标签" name="capabilityCodes"><Select mode="multiple" options={CAPABILITY_OPTIONS} placeholder="可稍后在模型详情中登记" /></Form.Item>
      <div className="model-runtime-grid"><Form.Item label="上下文窗口（Token）" name="contextWindow" rules={[{ required: true }]}><InputNumber max={2_000_000} min={1024} step={1024} /></Form.Item><Form.Item dependencies={["contextWindow"]} label="最大输出（Token）" name="maximumOutputTokens" rules={[{ required: true }, ({ getFieldValue }) => ({ validator(_, value) { return value <= getFieldValue("contextWindow") ? Promise.resolve() : Promise.reject(new Error("不能超过上下文窗口")); } })]}><InputNumber min={256} step={256} /></Form.Item><Form.Item label="建议并发" name="concurrencyLimit" rules={[{ required: true }]}><InputNumber max={1000} min={1} /></Form.Item></div>
      <Alert description="新模型不会自动获得验证通过状态，也不会立即进入平台路由或玩家自备列表。" title="默认安全状态" showIcon type="info" />
    </Form>}
  </Modal>;
}
