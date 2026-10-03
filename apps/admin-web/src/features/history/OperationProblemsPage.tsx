import "./HistoryPage.css";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  Alert, Button, Card, Descriptions, Drawer, Empty, Input, Pagination,
  Select, Table, Tag, Typography, type TableColumnsType,
} from "antd";
import { Bug, MagnifyingGlass } from "@phosphor-icons/react";
import { useSearchParams } from "react-router-dom";
import {
  adminApi, type HistoryPage, type OperationError, type OperationErrorSummary,
} from "../../admin-api";
import { HistoryPeriodFilter } from "./HistoryPeriodFilter";
import { fmtHistoryTime, historyErrorMessage, historyNumber, historyRange, validPeriod } from "./history-utils";

const { Paragraph, Text, Title } = Typography;
const CATEGORY_LABELS: Record<string, string> = {
  INPUT: "输入与规则", RESOURCE: "资源与货币", ACCOUNT: "账号登录", CREDENTIAL: "推演密钥",
  QUOTA: "推演点", STATE: "命卷状态", SYSTEM: "服务异常", MODEL: "模型生成",
};
const ERROR_CODE_LABELS: Record<string, string> = {
  UPSTREAM_TIMEOUT: "上游服务超时",
  MODEL_INVOCATION_FAILED: "模型调用失败",
  MODEL_FALLBACK_FAILED: "备用模型调用失败",
  MODEL_ROUTE_UNAVAILABLE: "没有可用模型",
  MODEL_CREDENTIAL_DECRYPT_FAILED: "模型密钥不可用",
  PERSONAL_MODEL_UNAVAILABLE: "玩家自备模型不可用",
  PERSONAL_KEY_REJECTED: "玩家密钥验证失败",
  SERVICE_PLAN_UNAVAILABLE: "推演方案不可用",
};
const OPERATION_LABELS: Record<string, string> = {
  TURN_DIRECTOR: "回合规划",
  NARRATIVE_WRITER: "剧情生成",
  CREATE_CHARACTER: "创建角色",
};
const errorCodeLabel = (code: string) => ERROR_CODE_LABELS[code] ?? "服务处理失败";
const operationLabel = (operation: string) => OPERATION_LABELS[operation] ?? operation;
const CATEGORY_OPTIONS = [
  { value: "", label: "全部分类" },
  ...Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label })),
];

/** 问题中心以近期汇总做提醒，以分页历史做事实下钻；汇总窗口不会限制明细可查询范围。 */
export function OperationProblemsPage() {
  const [params, setParams] = useSearchParams();
  const search = params.get("q") ?? "";
  const category = params.get("category") ?? "";
  const period = validPeriod(params.get("period"));
  const customFrom = params.get("from") ?? "";
  const customTo = params.get("to") ?? "";
  const page = Math.max(1, Number(params.get("page")) || 1);
  const [draftSearch, setDraftSearch] = useState(search);
  const [draftCategory, setDraftCategory] = useState(category);
  const [draftPeriod, setDraftPeriod] = useState(period);
  const [draftFrom, setDraftFrom] = useState(customFrom);
  const [draftTo, setDraftTo] = useState(customTo);
  const [data, setData] = useState<HistoryPage<OperationError> | null>(null);
  const [summary, setSummary] = useState<OperationErrorSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [error, setError] = useState("");
  const [summaryError, setSummaryError] = useState("");
  const [selected, setSelected] = useState<OperationError | null>(null);
  const range = useMemo(() => historyRange(period, customFrom, customTo), [customFrom, customTo, period]);

  useEffect(() => {
    setDraftSearch(search); setDraftCategory(category); setDraftPeriod(period);
    setDraftFrom(customFrom); setDraftTo(customTo);
  }, [category, customFrom, customTo, period, search]);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      setData(await adminApi.problemHistory({ search, type: category, from: range.from, to: range.to, page: page - 1, size: 20 }));
    } catch (reason) { setError(historyErrorMessage(reason, "问题历史加载失败")); }
    finally { setLoading(false); }
  }, [category, page, range.from, range.to, search]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    let live = true; setSummaryLoading(true); setSummaryError("");
    adminApi.operationErrorSummary(7, 12)
      .then((result) => live && setSummary(result))
      .catch((reason) => live && setSummaryError(historyErrorMessage(reason, "近期问题汇总加载失败")))
      .finally(() => live && setSummaryLoading(false));
    return () => { live = false; };
  }, []);

  const commit = (overrides: Record<string, string | null> = {}) => {
    const next = new URLSearchParams(params);
    const values = {
      q: draftSearch.trim(), category: draftCategory, period: draftPeriod,
      from: draftPeriod === "custom" ? draftFrom : "",
      to: draftPeriod === "custom" ? draftTo : "", page: "", ...overrides,
    };
    Object.entries(values).forEach(([key, value]) => value ? next.set(key, value) : next.delete(key));
    setParams(next);
  };
  const changePage = (nextPage: number) => {
    const next = new URLSearchParams(params);
    if (nextPage === 1) next.delete("page"); else next.set("page", String(nextPage));
    setParams(next);
  };

  const columns: TableColumnsType<OperationError> = [
    { title: "问题编号", dataIndex: "referenceId", width: 210, render: (value: string, row) => <div className="history-primary-cell"><Text copyable={{ text: value }} strong>{value}</Text><small>{operationLabel(row.operation)}</small></div> },
    { title: "玩家", key: "player", width: 220, render: (_, row) => <div className="history-primary-cell"><span>{row.email || row.playerName || "未关联账号"}</span><small>{row.playerName || row.saveId || "—"}</small></div> },
    { title: "分类", dataIndex: "category", width: 120, render: (value: string) => <Tag color={value === "SYSTEM" ? "error" : "blue"}>{CATEGORY_LABELS[value] ?? value}</Tag> },
    { title: "问题原因", key: "code", width: 210, render: (_, row) => <div className="history-primary-cell"><span>{errorCodeLabel(row.code)}</span><small>{row.code} · HTTP {row.httpStatus}</small></div> },
    { title: "玩家看到的提示", key: "message", width: 360, render: (_, row) => <div className="history-message-cell"><span>{row.message}</span></div> },
    { title: "发生时间", dataIndex: "at", width: 170, render: fmtHistoryTime },
    { title: "操作", key: "action", fixed: "right", width: 76, render: (_, row) => <Button onClick={(event) => { event.stopPropagation(); setSelected(row); }} size="small" type="link">详情</Button> },
  ];
  const customIncomplete = draftPeriod === "custom" && (!draftFrom || !draftTo);

  return <div className="history-page">
    <section className="history-intro">
      <div><Title level={2}>玩家问题</Title><Paragraph>查看近期高频问题，并按日期、玩家或问题编号查询完整记录。</Paragraph></div>
      <Bug aria-hidden size={42} weight="duotone" />
    </section>
    {summaryError && <Alert description={summaryError} showIcon title="近期问题汇总暂时不可用" type="warning" />}
    <Card className="history-summary-card" loading={summaryLoading} title={null} variant="borderless">
      <div className="history-summary-heading"><div><Text strong>近 7 日待处理问题</Text><Text type="secondary">只用于排序提醒，不限制下方历史查询</Text></div><Text type="secondary">{summary.length} 类</Text></div>
      {summary.length ? <div className="problem-summary-list">{summary.map((item) => <Button className="problem-summary-item" key={`${item.category}-${item.code}`} onClick={() => {
        setDraftSearch(item.code); commit({ q: item.code });
      }}>
        <Tag color={item.highestStatus >= 500 ? "error" : "warning"}>{item.highestStatus >= 500 ? "高" : "中"}</Tag>
        <span className="problem-summary-copy"><strong>{errorCodeLabel(item.code)}</strong><span>{item.code} · {historyNumber(item.affectedPlayers)} 名玩家 · {fmtHistoryTime(item.latestAt)}</span></span>
        <b className="problem-summary-count">{historyNumber(item.count)}<small>次</small></b>
      </Button>)}</div> : <Empty description="近 7 日没有记录到最终失败" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
    </Card>
    <Card className="history-filter-card" variant="borderless">
      <form className={`history-filter-form${draftPeriod === "custom" ? " has-custom-date" : ""}`} onSubmit={(event: FormEvent) => { event.preventDefault(); if (!customIncomplete) commit(); }}>
        <label className="history-filter-field"><span>问题编号、玩家、命卷或错误编码</span><Input allowClear onChange={(event) => setDraftSearch(event.currentTarget.value)} placeholder="例如 WX-20260802-ABC123" prefix={<MagnifyingGlass size={17} />} value={draftSearch} /></label>
        <label className="history-filter-field"><span>问题分类</span><Select onChange={setDraftCategory} options={CATEGORY_OPTIONS} value={draftCategory} /></label>
        <HistoryPeriodFilter from={draftFrom} onPeriodChange={setDraftPeriod} onRangeChange={(from, to) => { setDraftFrom(from); setDraftTo(to); }} period={draftPeriod} to={draftTo} />
        <div className="history-filter-actions"><Button disabled={customIncomplete} htmlType="submit" type="primary">查询</Button><Button onClick={() => { setDraftSearch(""); setDraftCategory(""); setDraftPeriod("30"); setDraftFrom(""); setDraftTo(""); commit({ q: null, category: null, period: "30", from: null, to: null }); }}>重置</Button></div>
      </form>
    </Card>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon title="问题历史暂时无法读取" type="error" />}
    <Card className="history-result-card" variant="borderless">
      <div className="history-result-heading"><div><Text strong>问题明细</Text><Text type="secondary">{range.label} · 共 {historyNumber(data?.total ?? 0)} 条</Text></div><Button loading={loading} onClick={() => void load()}>刷新</Button></div>
      <Table<OperationError> columns={columns} dataSource={data?.rows ?? []} loading={loading} locale={{ emptyText: <Empty description="所选范围内没有符合条件的问题" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} onRow={(row) => ({ onClick: () => setSelected(row) })} pagination={false} rowKey="referenceId" scroll={{ x: 1350 }} size="middle" />
      <Pagination current={page} hideOnSinglePage onChange={changePage} pageSize={20} showSizeChanger={false} showTotal={(total) => `共 ${total} 条`} total={data?.total ?? 0} />
    </Card>
    <ProblemDrawer item={selected} onClose={() => setSelected(null)} />
  </div>;
}

function ProblemDrawer({ item, onClose }: { item: OperationError | null; onClose: () => void }) {
  return <Drawer className="history-drawer" destroyOnHidden onClose={onClose} open={Boolean(item)} size={600} title="问题详情">
    {item && <>
      <Descriptions bordered column={1} size="small">
        <Descriptions.Item label="问题编号"><Text copyable>{item.referenceId}</Text></Descriptions.Item>
        <Descriptions.Item label="发生时间">{fmtHistoryTime(item.at)}</Descriptions.Item>
        <Descriptions.Item label="发生环节">{operationLabel(item.operation)}{operationLabel(item.operation) === item.operation ? "" : `（${item.operation}）`}</Descriptions.Item>
        <Descriptions.Item label="错误分类">{CATEGORY_LABELS[item.category] ?? item.category}</Descriptions.Item>
        <Descriptions.Item label="问题原因">{errorCodeLabel(item.code)}</Descriptions.Item>
        <Descriptions.Item label="技术编号">{item.code} · HTTP {item.httpStatus}</Descriptions.Item>
        <Descriptions.Item label="玩家">{item.email || item.playerName || "未关联"}</Descriptions.Item>
        <Descriptions.Item label="命卷 ID">{item.saveId || "—"}</Descriptions.Item>
        <Descriptions.Item label="玩家提示">{item.message}</Descriptions.Item>
      </Descriptions>
      <section className="history-diagnostic"><Text strong>技术详情</Text><pre>{item.diagnostic || "本条记录没有技术详情。"}</pre></section>
      <Alert description="诊断内容只用于管理台定位，不应直接复制给玩家，也不会作为剧情或命卷事实。" showIcon type="info" />
    </>}
  </Drawer>;
}
