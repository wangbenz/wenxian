import "./HistoryPage.css";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  Alert, Button, Card, Drawer, Empty, Input, Pagination, Select,
  Table, Tag, Typography, type TableColumnsType,
} from "antd";
import { ClockCounterClockwise, MagnifyingGlass } from "@phosphor-icons/react";
import { useSearchParams } from "react-router-dom";
import { adminApi, type AdminAuditLog, type HistoryPage } from "../../admin-api";
import { HistoryPeriodFilter } from "./HistoryPeriodFilter";
import { fmtHistoryTime, historyErrorMessage, historyNumber, historyRange, validPeriod } from "./history-utils";

const { Paragraph, Text, Title } = Typography;
const OPERATION_LABELS: Record<string, string> = {
  LOGIN: "登录管理台",
  CHANGE_PASSWORD: "修改密码",
  CREATE_ADMIN: "新增管理账号",
  UPDATE_ADMIN_STATUS: "更改管理账号状态",
  RESET_ADMIN_PASSWORD: "重置管理账号密码",
  RESET_PLAYER_PASSWORD: "重置玩家密码",
  UNLOCK_PLAYER_AUTH: "解除玩家登录限制",
  UPDATE_REGISTRATION_CONFIG: "更新注册规则",
  UPDATE_ACTIVE_SAVE_RULE: "更新命卷上限",
  UPDATE_POINT_RULE: "更新点数规则",
  UPDATE_GAMEPLAY_CONFIG: "更新游戏规则",
  GRANT_TURN_CREDIT: "赠送推演点",
  REVOKE_TURN_CREDIT: "撤销推演点",
  UPDATE_GENERATION_PROVIDER: "更新供应商配置",
  UPDATE_GENERATION_MODEL: "更新模型配置",
  CREATE_SERVICE_PLAN_DRAFT: "新建三档方案草稿",
  UPDATE_SERVICE_PLAN_DRAFT: "保存三档方案草稿",
  PUBLISH_SERVICE_PLAN: "发布三档方案",
  UPDATE_BYOK_RULE: "更新玩家自备服务",
  UPDATE_APP_VERSION: "更新游戏版本",
  SAVE_RELEASE_NOTE: "保存版本公告",
  CLEAR_LOGIN_ANNOUNCEMENT: "撤下登录页公告",
  UPDATE_ACCESS_MODE: "更新访问模式",
  ADD_ACCESS_WHITELIST: "加入测试账号",
  REMOVE_ACCESS_WHITELIST: "移除测试账号",
};
const operationLabel = (operation: string) => OPERATION_LABELS[operation] ?? operation;
const readableDetail = (detail: string) => detail
  .replace("发布推演服务方案", "发布三档方案")
  .replace("；后续平台请求按新方案冻结", "，并设为当前方案")
  .replace("更新推演模型", "更新模型配置");

/** 管理操作记录保持只读并支持全部历史分页；筛选不会触发任何管理配置或玩家数据变更。 */
export function AuditLogsPage() {
  const [params, setParams] = useSearchParams();
  const search = params.get("q") ?? "";
  const operation = params.get("operation") ?? "";
  const period = validPeriod(params.get("period"));
  const customFrom = params.get("from") ?? "";
  const customTo = params.get("to") ?? "";
  const page = Math.max(1, Number(params.get("page")) || 1);
  const [draftSearch, setDraftSearch] = useState(search);
  const [draftOperation, setDraftOperation] = useState(operation);
  const [draftPeriod, setDraftPeriod] = useState(period);
  const [draftFrom, setDraftFrom] = useState(customFrom);
  const [draftTo, setDraftTo] = useState(customTo);
  const [data, setData] = useState<HistoryPage<AdminAuditLog> | null>(null);
  const [operations, setOperations] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<AdminAuditLog | null>(null);
  const range = useMemo(() => historyRange(period, customFrom, customTo), [customFrom, customTo, period]);

  useEffect(() => {
    setDraftSearch(search); setDraftOperation(operation); setDraftPeriod(period);
    setDraftFrom(customFrom); setDraftTo(customTo);
  }, [customFrom, customTo, operation, period, search]);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      setData(await adminApi.auditHistory({ search, type: operation, from: range.from, to: range.to, page: page - 1, size: 20 }));
    } catch (reason) { setError(historyErrorMessage(reason, "操作记录加载失败")); }
    finally { setLoading(false); }
  }, [operation, page, range.from, range.to, search]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    let live = true;
    adminApi.auditOperations().then((result) => live && setOperations(result)).catch(() => {
      // 操作类型建议列表失败不应阻断历史明细，仍可通过搜索查看全部记录。
    });
    return () => { live = false; };
  }, []);

  const commit = (overrides: Record<string, string | null> = {}) => {
    const next = new URLSearchParams(params);
    const values = {
      q: draftSearch.trim(), operation: draftOperation, period: draftPeriod,
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

  const columns: TableColumnsType<AdminAuditLog> = [
    { title: "操作类型", dataIndex: "operation", width: 240, render: (value: string) => <Tag color="blue">{operationLabel(value)}</Tag> },
    { title: "操作内容", dataIndex: "detail", width: 620, render: (value: string) => <div className="history-message-cell"><span>{readableDetail(value)}</span></div> },
    { title: "发生时间", dataIndex: "at", width: 190, render: fmtHistoryTime },
    { title: "操作", key: "action", fixed: "right", width: 76, render: (_, row) => <Button onClick={(event) => { event.stopPropagation(); setSelected(row); }} size="small" type="link">详情</Button> },
  ];
  const customIncomplete = draftPeriod === "custom" && (!draftFrom || !draftTo);

  return <div className="history-page">
    <section className="history-intro">
      <div><Title level={2}>操作记录</Title><Paragraph>查询管理账号执行过的配置变更和运营操作。</Paragraph></div>
      <ClockCounterClockwise aria-hidden size={42} weight="duotone" />
    </section>
    <Card className="history-filter-card" variant="borderless">
      <form className={`history-filter-form${draftPeriod === "custom" ? " has-custom-date" : ""}`} onSubmit={(event: FormEvent) => { event.preventDefault(); if (!customIncomplete) commit(); }}>
        <label className="history-filter-field"><span>操作类型或内容</span><Input allowClear onChange={(event) => setDraftSearch(event.currentTarget.value)} placeholder="搜索操作摘要" prefix={<MagnifyingGlass size={17} />} value={draftSearch} /></label>
        <label className="history-filter-field"><span>操作类型</span><Select onChange={setDraftOperation} options={[{ value: "", label: "全部操作" }, ...operations.map((value) => ({ value, label: operationLabel(value) }))]} showSearch value={draftOperation} /></label>
        <HistoryPeriodFilter from={draftFrom} onPeriodChange={setDraftPeriod} onRangeChange={(from, to) => { setDraftFrom(from); setDraftTo(to); }} period={draftPeriod} to={draftTo} />
        <div className="history-filter-actions"><Button disabled={customIncomplete} htmlType="submit" type="primary">查询</Button><Button onClick={() => { setDraftSearch(""); setDraftOperation(""); setDraftPeriod("30"); setDraftFrom(""); setDraftTo(""); commit({ q: null, operation: null, period: "30", from: null, to: null }); }}>重置</Button></div>
      </form>
    </Card>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon title="操作历史暂时无法读取" type="error" />}
    <Card className="history-result-card" variant="borderless">
      <div className="history-result-heading"><div><Text strong>操作历史</Text><Text type="secondary">{range.label} · 共 {historyNumber(data?.total ?? 0)} 条</Text></div><Button loading={loading} onClick={() => void load()}>刷新</Button></div>
      <Table<AdminAuditLog> columns={columns} dataSource={data?.rows ?? []} loading={loading} locale={{ emptyText: <Empty description="所选范围内没有操作记录" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} onRow={(row) => ({ onClick: () => setSelected(row) })} pagination={false} rowKey="id" scroll={{ x: 1120 }} size="middle" />
      <Pagination current={page} hideOnSinglePage onChange={changePage} pageSize={20} showSizeChanger={false} showTotal={(total) => `共 ${total} 条`} total={data?.total ?? 0} />
    </Card>
    <Drawer className="history-drawer" destroyOnHidden onClose={() => setSelected(null)} open={Boolean(selected)} size={560} title="操作详情">
      {selected && <><Text type="secondary">{fmtHistoryTime(selected.at)}</Text><Tag color="blue">{operationLabel(selected.operation)}</Tag><section className="history-diagnostic"><Text strong>操作内容</Text><pre>{readableDetail(selected.detail)}</pre></section><section className="history-diagnostic"><Text strong>技术编号</Text><pre>{selected.operation}</pre></section><Alert description="操作记录不能修改；需要调整配置时，请前往对应业务页面。" showIcon type="info" /></>}
    </Drawer>
  </div>;
}
