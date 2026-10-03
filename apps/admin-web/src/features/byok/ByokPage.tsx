import "./ByokPage.css";

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Modal, Select, Switch,
  Table, Tabs, Tag, Typography, type TableColumnsType,
} from "antd";
import { ArrowRight, ArrowsClockwise, ChartLineUp, Key, ShieldCheck, UserCircle } from "@phosphor-icons/react";
import dayjs from "dayjs";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  adminApi, type ByokConversion, type ByokCredentialPage, type ByokCredentialRow,
  type ByokCredentialSummary, type ByokProviderSummary, type ByokRule, type GenerationProvider,
} from "../../admin-api";
import { useAnalyticsDateRange } from "../analytics/useAnalyticsDateRange";

const { Paragraph, Text, Title } = Typography;
const number = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const percent = (value: number) => `${Number(value || 0).toFixed(1)}%`;
const fmt = (value?: string | null) => value ? dayjs(value).format("YYYY-MM-DD HH:mm") : "—";
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;
const TABS = ["conversion", "credentials", "rules"] as const;
type TabKey = (typeof TABS)[number];

/**
 * 玩家自备服务业务页统一承载转化、脱敏凭证台账和接入规则。
 * 密钥明文与密文永远不进入页面状态、URL、表格或错误提示。
 */
export function ByokPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const range = useAnalyticsDateRange(30);
  const rawTab = searchParams.get("view") as TabKey | null;
  const activeTab: TabKey = rawTab && TABS.includes(rawTab) ? rawTab : "conversion";
  const rawWindow = Number(searchParams.get("windowDays") || 14);
  const windowDays = [7, 14, 30].includes(rawWindow) ? rawWindow : 14;

  const [rule, setRule] = useState<ByokRule | null>(null);
  const [providers, setProviders] = useState<GenerationProvider[]>([]);
  const [enabled, setEnabled] = useState(false);
  const [conversion, setConversion] = useState<ByokConversion | null>(null);
  const [summary, setSummary] = useState<ByokCredentialSummary | null>(null);
  const [credentials, setCredentials] = useState<ByokCredentialPage | null>(null);
  const [selectedCredential, setSelectedCredential] = useState<ByokCredentialRow | null>(null);
  const [ruleLoading, setRuleLoading] = useState(true);
  const [conversionLoading, setConversionLoading] = useState(true);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [credentialsLoading, setCredentialsLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const search = searchParams.get("keySearch") || "";
  const providerFilter = searchParams.get("keyProvider") || "";
  const statusFilter = searchParams.get("keyStatus") || "";
  const selectedFilter = searchParams.get("keySelected") || "";
  const page = Math.max(0, Number(searchParams.get("keyPage") || 0) || 0);
  const pageSize = Math.max(10, Math.min(50, Number(searchParams.get("keySize") || 20) || 20));

  const patchSearch = useCallback((values: Record<string, string | number | null>) => {
    const next = new URLSearchParams(searchParams);
    Object.entries(values).forEach(([key, value]) => {
      if (value === null || value === "") next.delete(key);
      else next.set(key, String(value));
    });
    setSearchParams(next);
  }, [searchParams, setSearchParams]);

  const loadRules = useCallback(async () => {
    setRuleLoading(true); setError("");
    try {
      const [nextRule, nextProviders] = await Promise.all([adminApi.byokRules(), adminApi.generationProviders()]);
      setRule(nextRule); setEnabled(nextRule.enabled); setProviders(nextProviders);
    } catch (reason) { setError(errorMessage(reason, "玩家自备服务规则加载失败")); }
    finally { setRuleLoading(false); }
  }, []);

  const loadConversion = useCallback(async () => {
    setConversionLoading(true); setError("");
    try { setConversion(await adminApi.byokConversion(range.from, range.to, windowDays)); }
    catch (reason) { setError(errorMessage(reason, "玩家自备服务转化加载失败")); }
    finally { setConversionLoading(false); }
  }, [range.from, range.to, windowDays]);

  const loadSummary = useCallback(async () => {
    setSummaryLoading(true); setError("");
    try { setSummary(await adminApi.byokCredentialSummary(range.from, range.to)); }
    catch (reason) { setError(errorMessage(reason, "玩家接入概况加载失败")); }
    finally { setSummaryLoading(false); }
  }, [range.from, range.to]);

  const loadCredentials = useCallback(async () => {
    setCredentialsLoading(true); setError("");
    try {
      setCredentials(await adminApi.byokCredentials({
        search, provider: providerFilter, status: statusFilter,
        selected: selectedFilter === "true" ? true : selectedFilter === "false" ? false : undefined,
        page, size: pageSize, usageFrom: range.from, usageTo: range.to,
      }));
    } catch (reason) { setError(errorMessage(reason, "玩家接入记录加载失败")); }
    finally { setCredentialsLoading(false); }
  }, [page, pageSize, providerFilter, range.from, range.to, search, selectedFilter, statusFilter]);

  useEffect(() => { void loadRules(); }, [loadRules]);
  useEffect(() => { if (activeTab === "conversion") void loadConversion(); }, [activeTab, loadConversion]);
  useEffect(() => {
    if (activeTab !== "credentials") return;
    void loadSummary(); void loadCredentials();
  }, [activeTab, loadCredentials, loadSummary]);

  const providerRows = useMemo(() => providers.map((provider) => ({
    ...provider,
    selectableModels: provider.models.filter((model) => model.userSelectable),
    verifiedModels: provider.models.filter((model) => model.userSelectable && model.verificationStatus === "PASSED").length,
  })), [providers]);
  const allowedProviders = providerRows.filter((provider) => provider.enabled && provider.userKeyEnabled);
  const selectableModels = allowedProviders.reduce((sum, provider) => sum + provider.selectableModels.length, 0);

  const saveRule = async () => {
    if (!rule || enabled === rule.enabled || busy) return;
    setBusy(true); setError("");
    try {
      const updated = await adminApi.updateByokRules(enabled);
      setRule(updated); setEnabled(updated.enabled); setConfirmOpen(false);
      setNotice(updated.enabled ? "玩家自备推演服务已开启。" : "玩家自备推演服务已关闭；已保存的 Key 没有删除。");
    } catch (reason) { setError(errorMessage(reason, "玩家自备服务开关保存失败")); }
    finally { setBusy(false); }
  };

  const refresh = () => {
    void loadRules();
    if (activeTab === "conversion") void loadConversion();
    if (activeTab === "credentials") { void loadSummary(); void loadCredentials(); }
  };

  return <div className="byok-page">
    {notice && <Alert closable onClose={() => setNotice("")} showIcon title={notice} type="success" />}
    {error && <Alert action={<Button onClick={refresh} size="small">重新读取</Button>} closable description={error} onClose={() => setError("")} showIcon title="玩家自备服务数据未完整加载" type="error" />}
    <section className="byok-header">
      <div><Title level={2}>玩家自备服务</Title><Paragraph>查看玩家接入 Key 后的转化、接入状态和使用情况；密钥仅显示末四位。</Paragraph></div>
      <div className="byok-header-actions"><Tag color={rule?.enabled ? "success" : "default"}>{rule?.enabled ? "服务已开启" : "服务已关闭"}</Tag><Button icon={<ArrowsClockwise size={17} />} loading={ruleLoading || conversionLoading || summaryLoading || credentialsLoading} onClick={refresh}>刷新</Button></div>
    </section>
    <Tabs activeKey={activeTab} className="byok-tabs" destroyOnHidden items={[
      { key: "conversion", label: <span><ChartLineUp size={16} />转化分析</span>, children: <ConversionTab conversion={conversion} loading={conversionLoading} range={range} windowDays={windowDays} onWindowChange={(value) => patchSearch({ windowDays: value })} /> },
      { key: "credentials", label: <span><Key size={16} />接入记录</span>, children: <CredentialsTab credentials={credentials} loading={credentialsLoading} onOpen={setSelectedCredential} onPage={(nextPage, nextSize) => patchSearch({ keyPage: nextPage, keySize: nextSize })} onSearch={(value) => patchSearch({ keySearch: value, keyPage: 0 })} onSelected={(value) => patchSearch({ keySelected: value, keyPage: 0 })} onProvider={(value) => patchSearch({ keyProvider: value, keyPage: 0 })} onStatus={(value) => patchSearch({ keyStatus: value, keyPage: 0 })} page={page} pageSize={pageSize} providerFilter={providerFilter} providers={providers} range={range} search={search} selectedFilter={selectedFilter} statusFilter={statusFilter} summary={summary} summaryLoading={summaryLoading} /> },
      { key: "rules", label: <span><ShieldCheck size={16} />接入规则</span>, children: <RulesTab allowedProviders={allowedProviders} busy={busy} enabled={enabled} loading={ruleLoading} navigate={navigate} onConfirm={() => setConfirmOpen(true)} onEnabled={setEnabled} onReset={() => rule && setEnabled(rule.enabled)} providerRows={providerRows} rule={rule} selectableModels={selectableModels} /> },
    ]} onChange={(key) => patchSearch({ view: key, keyPage: key === "credentials" ? page : null })} />
    <CredentialDrawer onClose={() => setSelectedCredential(null)} rangeLabel={`${range.from} 至 ${range.to}`} row={selectedCredential} />
    <Modal cancelText="返回修改" confirmLoading={busy} okText={enabled ? "确认开启" : "确认关闭"} onCancel={() => !busy && setConfirmOpen(false)} onOk={() => void saveRule()} open={confirmOpen} title={`${enabled ? "开启" : "关闭"}玩家自备推演服务？`}>
      <Alert description={enabled ? `开启后，当前 ${allowedProviders.length} 个启用供应商、${selectableModels} 个玩家可选模型会进入接入范围。` : `关闭后 ${number(rule?.activeCredentialUsers ?? 0)} 名已有有效 Key 的玩家不能继续使用自备服务，但 Key 不会删除。`} showIcon title={enabled ? "确认当前模型范围" : "玩家后续请求会受影响"} type={enabled ? "info" : "warning"} />
    </Modal>
  </div>;
}

function RangeControl({ label, range, extra }: { label: string; range: ReturnType<typeof useAnalyticsDateRange>; extra?: ReactNode }) {
  return <section className="byok-range-control"><div><strong>{label}</strong><Text type="secondary">按北京时间自然日统计。</Text></div><div className="byok-range-actions"><DatePicker.RangePicker allowClear={false} format="YYYY-MM-DD" maxDate={dayjs()} onChange={(dates) => dates?.[0] && dates[1] && range.setRange(dates[0].format("YYYY-MM-DD"), dates[1].format("YYYY-MM-DD"))} value={[dayjs(range.from), dayjs(range.to)]} />{extra}</div></section>;
}

function ConversionTab({ conversion, loading, range, windowDays, onWindowChange }: { conversion: ByokConversion | null; loading: boolean; range: ReturnType<typeof useAnalyticsDateRange>; windowDays: number; onWindowChange: (value: number) => void }) {
  const stages = conversion?.stages ?? [];
  const registered = stages[0]?.users ?? 0;
  const connected = stages.find((stage) => stage.code === "KEY_CONNECTED");
  const byok = stages.find((stage) => stage.code === "BYOK_TURN");
  const columns: TableColumnsType<ByokConversion["trend"][number]> = [
    { title: "注册日期", dataIndex: "date", width: 120 }, { title: "注册", dataIndex: "registeredUsers", width: 90, render: number },
    { title: "创建命卷", dataIndex: "createdSaveUsers", width: 110, render: number }, { title: "完成首回合", dataIndex: "firstTurnUsers", width: 120, render: number },
    { title: "接入 Key", dataIndex: "connectedUsers", width: 110, render: number }, { title: "成功自备推演", dataIndex: "byokTurnUsers", width: 130, render: number },
  ];
  return <div className="byok-tab-panel">
    <RangeControl label="注册批次" range={range} extra={<Select aria-label="转化观察窗口" onChange={onWindowChange} options={[7, 14, 30].map((value) => ({ value, label: `注册后 ${value} 天` }))} value={windowDays} />} />
    <Card className="byok-conversion-card" loading={loading} variant="borderless">{conversion && <><div className="byok-conversion-heading"><div><span>已满观察期</span><strong>{number(registered)} 人</strong></div><Text type="secondary">另有 {number(conversion.immatureUsers)} 人注册未满 {conversion.windowDays} 天，暂不计算转化率。</Text></div><div aria-label="玩家自备服务转化链" className="byok-conversion-rail">{stages.map((stage, index) => <div className="byok-conversion-stage" key={stage.code}><span>{stage.label}</span><strong>{number(stage.users)}</strong><small>{index === 0 ? "样本基数" : `上一步 ${percent(stage.rateFromPrevious)}`}</small></div>)}</div><div className="byok-conversion-footer"><span>注册至 Key 接入率 <strong>{percent(connected?.rateFromRegistered ?? 0)}</strong></span><span>注册至自备推演率 <strong>{percent(byok?.rateFromRegistered ?? 0)}</strong></span></div></>}</Card>
    {conversion?.dataCoverage && <Alert description={conversion.dataCoverage.connectedEventsStartedAt ? `自 ${fmt(conversion.dataCoverage.connectedEventsStartedAt)} 起完整记录接入情况；更早数据仅包含统计启用时仍在使用的 ${number(conversion.dataCoverage.backfilledConnections)} 条 Key。` : "当前还没有完整的接入历史数据。"} showIcon title="历史数据范围" type="info" />}
    <Card className="byok-data-card" title="注册批次转化明细" variant="borderless"><Table columns={columns} dataSource={conversion?.trend ?? []} loading={loading} locale={{ emptyText: <Empty description="所选批次还没有成熟转化数据" /> }} pagination={{ defaultPageSize: 14, hideOnSinglePage: true, showSizeChanger: true, pageSizeOptions: [14, 31, 90] }} rowKey="date" scroll={{ x: 680 }} size="middle" /></Card>
  </div>;
}

type CredentialTabProps = { summary: ByokCredentialSummary | null; summaryLoading: boolean; credentials: ByokCredentialPage | null; loading: boolean; range: ReturnType<typeof useAnalyticsDateRange>; providers: GenerationProvider[]; search: string; providerFilter: string; statusFilter: string; selectedFilter: string; page: number; pageSize: number; onSearch: (value: string) => void; onProvider: (value: string) => void; onStatus: (value: string) => void; onSelected: (value: string) => void; onPage: (page: number, size: number) => void; onOpen: (row: ByokCredentialRow) => void };

function CredentialsTab(props: CredentialTabProps) {
  const snapshot = props.summary?.snapshot;
  const providerColumns: TableColumnsType<ByokProviderSummary> = [
    { title: "供应商", key: "provider", width: 180, render: (_, row) => <div className="byok-provider-name"><strong>{row.displayName}</strong><small>{row.providerCode}</small></div> },
    { title: "接入玩家", dataIndex: "credentialUsers", width: 105, render: number }, { title: "接入记录", dataIndex: "credentialCount", width: 95, render: number },
    { title: "有效 / 异常", width: 125, render: (_, row) => <><span className="byok-good">{number(row.activeCredentials)}</span> / <span className={row.invalidCredentials ? "byok-bad" : ""}>{number(row.invalidCredentials)}</span></> },
    { title: "当前选用", dataIndex: "selectedUsers", width: 105, render: number }, { title: "使用玩家", dataIndex: "usageUsers", width: 105, render: number },
    { title: "成功回合", dataIndex: "successfulTurns", width: 105, render: number }, { title: "失败调用", dataIndex: "failedCalls", width: 100, render: (value: number) => <span className={value ? "byok-bad" : ""}>{number(value)}</span> },
    { title: "最后使用", dataIndex: "lastUsedAt", width: 150, responsive: ["lg"], render: fmt },
  ];
  const credentialColumns: TableColumnsType<ByokCredentialRow> = [
    { title: "玩家", key: "player", width: 230, render: (_, row) => <div className="byok-player-cell"><strong>{row.email}</strong><small>{row.userId}</small></div> },
    { title: "供应商", key: "provider", width: 160, render: (_, row) => <div className="byok-provider-name"><strong>{row.providerName}</strong><small>{row.providerCode}</small></div> },
    { title: "Key 状态", key: "status", width: 150, render: (_, row) => <div className="byok-status-cell"><Tag color={row.status === "ACTIVE" ? "success" : "warning"}>{row.status === "ACTIVE" ? "有效" : "异常"}</Tag><small>•••• {row.maskedSuffix}</small></div> },
    { title: "当前选用", dataIndex: "selected", width: 105, render: (value: boolean) => value ? <Tag color="blue">使用中</Tag> : <Text type="secondary">否</Text> },
    { title: "最后使用", dataIndex: "lastUsedAt", width: 150, responsive: ["md"], render: fmt },
    { title: "成功回合 / 失败", width: 145, render: (_, row) => <span>{number(row.periodSuccessfulTurns)} / <span className={row.periodFailedCalls ? "byok-bad" : ""}>{number(row.periodFailedCalls)}</span></span> },
    { title: "操作", fixed: "right", width: 78, render: (_, row) => <Button onClick={() => props.onOpen(row)} size="small" type="link">查看</Button> },
  ];
  return <div className="byok-tab-panel"><RangeControl label="使用日期" range={props.range} /><section aria-label="当前接入概况" className="byok-snapshot-strip">{[["接入玩家", snapshot?.credentialUsers], ["已接入 Key", snapshot?.credentialCount], ["有效 Key", snapshot?.activeCredentials], ["异常 Key", snapshot?.invalidCredentials], ["当前选用", snapshot?.selectedUsers], ["多供应商玩家", snapshot?.multiProviderUsers]].map(([label, value]) => <div key={String(label)}><span>{label}</span><strong>{number(Number(value || 0))}</strong></div>)}</section><Card className="byok-data-card" loading={props.summaryLoading} title="供应商接入分布" variant="borderless"><Table columns={providerColumns} dataSource={props.summary?.providers ?? []} locale={{ emptyText: <Empty description="还没有玩家接入 Key" /> }} pagination={false} rowKey="providerCode" scroll={{ x: 980 }} size="middle" /></Card><Card className="byok-data-card byok-ledger-card" title="玩家接入记录" variant="borderless"><div className="byok-ledger-filters"><Input.Search allowClear defaultValue={props.search} onSearch={props.onSearch} placeholder="邮箱或用户 ID" /><Select allowClear onChange={(value) => props.onProvider(value || "")} options={props.providers.map((provider) => ({ value: provider.providerCode, label: provider.displayName }))} placeholder="全部供应商" value={props.providerFilter || undefined} /><Select allowClear onChange={(value) => props.onStatus(value || "")} options={[{ value: "ACTIVE", label: "有效" }, { value: "INVALID", label: "异常" }]} placeholder="全部状态" value={props.statusFilter || undefined} /><Select allowClear onChange={(value) => props.onSelected(value || "")} options={[{ value: "true", label: "当前选用" }, { value: "false", label: "未选用" }]} placeholder="选用状态" value={props.selectedFilter || undefined} /></div><Table columns={credentialColumns} dataSource={props.credentials?.rows ?? []} loading={props.loading} locale={{ emptyText: <Empty description="没有符合条件的接入记录" /> }} pagination={{ current: props.page + 1, pageSize: props.pageSize, total: props.credentials?.total ?? 0, showSizeChanger: true, pageSizeOptions: [10, 20, 50], showTotal: (total) => `共 ${number(total)} 条记录`, onChange: (next, size) => props.onPage(next - 1, size) }} rowKey={(row) => `${row.userId}:${row.providerCode}`} scroll={{ x: 930 }} size="middle" /></Card></div>;
}

function RulesTab({ allowedProviders, busy, enabled, loading, navigate, onConfirm, onEnabled, onReset, providerRows, rule, selectableModels }: { allowedProviders: Array<GenerationProvider & { selectableModels: GenerationProvider["models"]; verifiedModels: number }>; busy: boolean; enabled: boolean; loading: boolean; navigate: ReturnType<typeof useNavigate>; onConfirm: () => void; onEnabled: (value: boolean) => void; onReset: () => void; providerRows: Array<GenerationProvider & { selectableModels: GenerationProvider["models"]; verifiedModels: number }>; rule: ByokRule | null; selectableModels: number }) {
  const columns: TableColumnsType<(typeof providerRows)[number]> = [
    { title: "供应商", key: "provider", render: (_, row) => <div className="byok-provider-name"><strong>{row.displayName}</strong><small>{row.providerCode}</small></div> }, { title: "平台状态", width: 120, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{row.enabled ? "已启用" : "已停用"}</Tag> }, { title: "支持玩家自备 Key", width: 160, render: (_, row) => <Tag color={row.userKeyEnabled ? "blue" : "default"}>{row.userKeyEnabled ? "支持" : "不支持"}</Tag> }, { title: "可选模型", width: 110, render: (_, row) => row.selectableModels.length }, { title: "验证通过", width: 110, render: (_, row) => `${row.verifiedModels} / ${row.selectableModels.length}` }, { title: "操作", fixed: "right", width: 90, render: () => <Button onClick={() => navigate("/inference/models")} size="small" type="link">去配置</Button> },
  ];
  return <div className="byok-tab-panel"><section className="byok-rule-grid"><Card className="byok-switch-card" loading={loading} title="平台总开关" variant="borderless">{rule && <><div className="byok-switch-row"><div><strong>允许玩家自备推演服务</strong><Paragraph>{enabled ? "玩家可以接入已允许的供应商和模型。" : "已保存的 Key 仍会保留，但不能继续使用。"}</Paragraph></div><Switch checked={enabled} checkedChildren="开启" onChange={onEnabled} unCheckedChildren="关闭" /></div><Alert description={enabled ? "成功使用自备 Key 的回合不消耗平台推演点。" : "关闭后玩家不能继续使用自备 Key，已保存的 Key 不会删除。"} showIcon type={enabled ? "info" : "warning"} /><div className="byok-actions"><Button disabled={enabled === rule.enabled || busy} onClick={onReset}>撤销修改</Button><Button disabled={enabled === rule.enabled || busy} onClick={onConfirm} type="primary">保存总开关</Button></div></>}</Card><Card className="byok-security-card" title="密钥安全" variant="borderless"><div className="byok-security-seal"><ShieldCheck size={30} weight="duotone" /><div><strong>管理台无法查看完整密钥</strong><span>仅显示供应商、状态和末四位</span></div></div><Descriptions column={1} size="small"><Descriptions.Item label="保存">玩家 Key 会加密保存，管理台无法查看完整内容</Descriptions.Item><Descriptions.Item label="验证">玩家提交后会立即验证；失败时只显示错误原因</Descriptions.Item><Descriptions.Item label="可选范围">只有已启用且允许玩家选择的模型可以接入</Descriptions.Item><Descriptions.Item label="点数">成功使用自备 Key 的回合不消耗平台推演点</Descriptions.Item></Descriptions></Card></section><Card className="byok-data-card" extra={<Button icon={<ArrowRight size={16} />} onClick={() => navigate("/inference/models")} type="link">管理模型库</Button>} title="允许接入的供应商与模型" variant="borderless"><Alert description={`当前支持 ${allowedProviders.length} 个供应商、${selectableModels} 个模型；接入范围在模型库中配置。`} showIcon title="当前接入范围" type="info" /><Table columns={columns} dataSource={providerRows} locale={{ emptyText: <Empty description="还没有配置生成供应商" /> }} pagination={false} rowKey="providerCode" scroll={{ x: 780 }} size="middle" /></Card></div>;
}

function CredentialDrawer({ row, rangeLabel, onClose }: { row: ByokCredentialRow | null; rangeLabel: string; onClose: () => void }) {
  return <Drawer onClose={onClose} open={Boolean(row)} size="large" title="玩家 Key 详情">{row && <div className="byok-drawer-content"><Alert description="这里只显示隐藏大部分内容后的 Key 信息。需要修复时，由玩家重新提交。" showIcon title="完整密钥不可查看" type="info" /><div className="byok-drawer-player"><UserCircle size={34} weight="duotone" /><div><strong>{row.email}</strong><small>{row.userId}</small></div></div><Descriptions bordered column={{ xs: 1, sm: 2 }} size="small"><Descriptions.Item label="供应商">{row.providerName} · {row.providerCode}</Descriptions.Item><Descriptions.Item label="密钥末四位">•••• {row.maskedSuffix}</Descriptions.Item><Descriptions.Item label="Key 状态"><Tag color={row.status === "ACTIVE" ? "success" : "warning"}>{row.status === "ACTIVE" ? "有效" : "异常"}</Tag></Descriptions.Item><Descriptions.Item label="当前选用">{row.selected ? `是 · ${row.selectedModel || "模型未记录"}` : "否"}</Descriptions.Item><Descriptions.Item label="最后验证">{fmt(row.lastVerifiedAt)}</Descriptions.Item><Descriptions.Item label="最后使用">{fmt(row.lastUsedAt)}</Descriptions.Item><Descriptions.Item label="创建时间">{fmt(row.createdAt)}</Descriptions.Item><Descriptions.Item label="更新时间">{fmt(row.updatedAt)}</Descriptions.Item><Descriptions.Item label="最近错误编号" span={2}>{row.lastErrorCode || "无"}</Descriptions.Item><Descriptions.Item label={`${rangeLabel} 成功回合`}>{number(row.periodSuccessfulTurns)}</Descriptions.Item><Descriptions.Item label={`${rangeLabel} 失败调用`}>{number(row.periodFailedCalls)}</Descriptions.Item></Descriptions></div>}</Drawer>;
}
