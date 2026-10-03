import "./InferencePointAccountsPage.css";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Radio,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  type TableColumnsType,
} from "antd";
import type { Dayjs } from "dayjs";
import { ArrowsClockwise, Coins, MagnifyingGlass, Plus } from "@phosphor-icons/react";
import { useSearchParams } from "react-router-dom";
import {
  adminApi,
  type TurnCreditAccount,
  type TurnCreditAccountPage,
  type TurnCreditDetail,
  type TurnCreditGrant,
  type TurnCreditLedger,
} from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
const { RangePicker } = DatePicker;

const SOURCE_META: Record<string, { label: string; color?: string }> = {
  LEGACY_OPENING: { label: "旧额度期初" },
  PLAYTEST: { label: "灰度测试", color: "blue" },
  FEEDBACK: { label: "问题反馈", color: "cyan" },
  ACTIVITY: { label: "运营活动", color: "purple" },
  ADMIN: { label: "人工补发", color: "gold" },
};

const STATUS_META: Record<string, { label: string; color: string }> = {
  ACTIVE: { label: "可用", color: "success" },
  EXHAUSTED: { label: "已用完", color: "default" },
  EXPIRED: { label: "已到期", color: "warning" },
  REVOKED: { label: "已撤销", color: "error" },
};

const EVENT_META: Record<string, { label: string; color?: string }> = {
  GRANT: { label: "发放", color: "blue" },
  RESERVE: { label: "预占", color: "gold" },
  RELEASE: { label: "释放", color: "cyan" },
  CONSUME: { label: "消费", color: "purple" },
  EXPIRE: { label: "到期", color: "orange" },
  REVOKE: { label: "撤销", color: "red" },
};

type GrantForm = {
  grantType: "PLAYTEST" | "FEEDBACK" | "ACTIVITY" | "ADMIN";
  count: number;
  validity: "PERMANENT" | "SEVEN_DAYS" | "THIRTY_DAYS" | "CUSTOM";
  customExpiresAt?: Dayjs;
  reason: string;
  campaignCode?: string;
  sourceReference?: string;
};

type PendingGrant = GrantForm & { expiresAt: string };

const number = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";
const shortId = (value: string) => value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
const signed = (value: number) => value > 0 ? `+${value}` : String(value);
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;

function createOperationId() {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  try {
    const random = new Uint32Array(4);
    globalThis.crypto?.getRandomValues(random);
    if (random.some((value) => value !== 0)) {
      return `op-${Date.now().toString(36)}-${Array.from(random, (value) => value.toString(36)).join("")}`;
    }
  } catch {
    // HTTP 内测地址可能没有 Web Crypto；服务端唯一索引仍会阻止操作编号碰撞造成重复到账。
  }
  return `op-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

function shanghaiBoundary(day: Dayjs, end: boolean) {
  const start = Date.UTC(day.year(), day.month(), day.date(), -8);
  return new Date(end ? start + 86_400_000 - 1 : start).toISOString();
}

/**
 * 玩家中心只管理账号余额、赠送批次和不可变流水。三档价格仍归“推演服务 / 三档方案”，
 * 避免把玩家支持操作和模型定价配置混在同一页面。
 */
export function InferencePointAccountsPage() {
  const [params, setParams] = useSearchParams();
  const query = params.get("q") ?? "";
  const page = Math.max(1, Number(params.get("page")) || 1);
  const selectedUserId = params.get("account");
  const [draft, setDraft] = useState(query);
  const [data, setData] = useState<TurnCreditAccountPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => setDraft(query), [query]);

  const load = useCallback(async () => {
    if (!query) { setData(null); setError(""); return; }
    setLoading(true); setError("");
    try { setData(await adminApi.turnCreditAccounts(query, page - 1, 20)); }
    catch (reason) { setError(errorMessage(reason, "推演点账户加载失败")); }
    finally { setLoading(false); }
  }, [page, query]);

  useEffect(() => { void load(); }, [load]);

  const updateParams = (changes: Record<string, string | number | null>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => {
      if (value === null || value === "" || (key === "page" && value === 1)) next.delete(key);
      else next.set(key, String(value));
    });
    setParams(next);
  };

  const columns: TableColumnsType<TurnCreditAccount> = [
    {
      title: "玩家账号", key: "account", width: 280,
      render: (_, row) => <div className="point-account-identity"><strong>{row.email}</strong><span title={row.userId}>{shortId(row.userId)}</span><Tag color={row.status === "ACTIVE" ? "success" : "default"}>{row.status === "ACTIVE" ? "账号正常" : "账号不可用"}</Tag></div>,
    },
    { title: "可用推演点", dataIndex: "available", width: 130, render: (value: number) => <strong className="point-number">{number(value)}</strong> },
    { title: "预占中", dataIndex: "reserved", width: 100, render: (value: number) => number(value) },
    { title: "已消费", dataIndex: "consumed", width: 100, render: (value: number) => number(value) },
    { title: "累计获得", dataIndex: "granted", width: 110, responsive: ["md"], render: (value: number) => number(value) },
    { title: "最近到期", dataIndex: "nearestExpiry", width: 170, responsive: ["lg"], render: fmt },
    {
      title: "操作", key: "action", fixed: "right", width: 92,
      render: (_, row) => <Button disabled={row.status !== "ACTIVE"} onClick={(event) => { event.stopPropagation(); updateParams({ account: row.userId }); }} size="small" type="link">查看</Button>,
    },
  ];

  return <div className="point-accounts-page">
    <Alert description="仅在回合成功保存后扣点；失败、重试或切换备用模型都不会重复扣点。" showIcon title="成功后才扣点" type="info" />
    <section className="point-accounts-intro">
      <div><Title level={2}>推演点账户</Title><Paragraph>查看玩家余额、点数发放和消费明细。</Paragraph></div>
      <Coins aria-hidden size={42} weight="duotone" />
    </section>
    <Card className="point-account-search" variant="outlined">
      <form onSubmit={(event) => { event.preventDefault(); updateParams({ q: draft.trim(), page: null, account: null }); }}>
        <Input allowClear autoComplete="off" onChange={(event) => { setDraft(event.currentTarget.value); if (!event.currentTarget.value) updateParams({ q: null, page: null, account: null }); }} placeholder="输入完整邮箱或用户 ID" prefix={<MagnifyingGlass size={17} />} value={draft} />
        <Button disabled={!draft.trim()} htmlType="submit" type="primary">查找玩家</Button>
        {query && <Button onClick={() => { setDraft(""); updateParams({ q: null, page: null, account: null }); }}>清除</Button>}
        <Button aria-label="刷新账户查询" icon={<ArrowsClockwise size={17} />} loading={loading} onClick={() => void load()}>刷新</Button>
      </form>
    </Card>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon title="账户数据暂时无法读取" type="error" />}
    <Card className="point-account-results" variant="borderless">
      {!query ? <Empty description="输入玩家邮箱或用户 ID 查看点数账户" image={Empty.PRESENTED_IMAGE_SIMPLE} /> : <>
        <div className="point-card-heading"><div><Text strong>查询结果</Text><Text type="secondary">共 {number(data?.total ?? 0)} 个账号</Text></div></div>
        <Table<TurnCreditAccount>
          columns={columns}
          dataSource={data?.rows ?? []}
          loading={loading}
          locale={{ emptyText: <Empty description="没有找到对应玩家" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
          onRow={(row) => ({ onClick: () => row.status === "ACTIVE" && updateParams({ account: row.userId }) })}
          pagination={false}
          rowKey="userId"
          scroll={{ x: 900 }}
          size="middle"
        />
        <Pagination current={page} hideOnSinglePage onChange={(next) => updateParams({ page: next })} pageSize={20} showSizeChanger={false} showTotal={(total) => `共 ${total} 个账号`} total={data?.total ?? 0} />
      </>}
    </Card>
    <PointAccountDrawer onChanged={() => void load()} onClose={() => updateParams({ account: null })} userId={selectedUserId} />
  </div>;
}

function PointAccountDrawer({ userId, onClose, onChanged }: { userId: string | null; onClose: () => void; onChanged: () => void }) {
  const [detail, setDetail] = useState<TurnCreditDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [tab, setTab] = useState("grants");
  const [grantPage, setGrantPage] = useState(1);
  const [grantStatus, setGrantStatus] = useState("");
  const [ledgerPage, setLedgerPage] = useState(1);
  const [eventType, setEventType] = useState("");
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [grantOpen, setGrantOpen] = useState(false);
  const [pendingGrant, setPendingGrant] = useState<PendingGrant | null>(null);
  const [revokeGrant, setRevokeGrant] = useState<TurnCreditGrant | null>(null);
  const [revokeReason, setRevokeReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [grantForm] = Form.useForm<GrantForm>();
  const operationId = useRef("");

  const from = range?.[0] ? shanghaiBoundary(range[0], false) : "";
  const to = range?.[1] ? shanghaiBoundary(range[1], true) : "";
  const load = useCallback(async () => {
    if (!userId) { setDetail(null); return; }
    setLoading(true); setError("");
    try {
      setDetail(await adminApi.turnCreditDetail(userId, {
        grantPage: grantPage - 1, grantSize: 20, grantStatus,
        ledgerPage: ledgerPage - 1, ledgerSize: 20, eventType, from, to,
      }));
    } catch (reason) { setError(errorMessage(reason, "账户详情加载失败")); }
    finally { setLoading(false); }
  }, [eventType, from, grantPage, grantStatus, ledgerPage, to, userId]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!userId) return;
    setTab("grants"); setGrantPage(1); setLedgerPage(1); setGrantStatus(""); setEventType("");
    setRange(null); setNotice(""); setError(""); setPendingGrant(null); setRevokeGrant(null);
  }, [userId]);

  const openGrant = () => {
    grantForm.resetFields();
    grantForm.setFieldsValue({ grantType: "PLAYTEST", count: 50, validity: "PERMANENT", reason: "灰度测试推演点", campaignCode: "", sourceReference: "" });
    setGrantOpen(true); setNotice("");
  };

  const prepareGrant = async () => {
    try {
      const values = await grantForm.validateFields();
      let expiresAt = "";
      if (values.validity === "SEVEN_DAYS") expiresAt = new Date(Date.now() + 7 * 86_400_000).toISOString();
      if (values.validity === "THIRTY_DAYS") expiresAt = new Date(Date.now() + 30 * 86_400_000).toISOString();
      if (values.validity === "CUSTOM") {
        if (!values.customExpiresAt || values.customExpiresAt.valueOf() < Date.now() + 5 * 60_000) {
          grantForm.setFields([{ name: "customExpiresAt", errors: ["到期时间至少晚于当前时间 5 分钟"] }]);
          return;
        }
        expiresAt = values.customExpiresAt.toISOString();
      }
      setGrantOpen(false);
      setPendingGrant({ ...values, expiresAt });
    } catch {
      // Ant Design 已在对应字段展示校验原因。
    }
  };

  const grant = async () => {
    if (!userId || !pendingGrant || busy) return;
    if (!operationId.current) operationId.current = createOperationId();
    setBusy(true); setError("");
    try {
      const result = await adminApi.grantTurnCredits(userId, {
        operationId: operationId.current,
        grantType: pendingGrant.grantType,
        count: pendingGrant.count,
        campaignCode: pendingGrant.campaignCode?.trim() ?? "",
        sourceReference: pendingGrant.sourceReference?.trim() ?? "",
        expiresAt: pendingGrant.expiresAt,
        reason: pendingGrant.reason.trim(),
      });
      operationId.current = "";
      setPendingGrant(null);
      setNotice(`已赠送 ${number(result.operation?.count ?? pendingGrant.count)} 推演点，当前可用 ${number(result.balance.available)} 点。`);
      setGrantPage(1); setLedgerPage(1);
      await load(); onChanged();
    } catch (reason) { setError(errorMessage(reason, "赠送推演点失败")); }
    finally { setBusy(false); }
  };

  const revoke = async () => {
    if (!userId || !revokeGrant || !revokeReason.trim() || busy) return;
    setBusy(true); setError("");
    try {
      await adminApi.revokeTurnCredits(userId, revokeGrant.id, revokeReason.trim());
      setNotice(`已撤销该批次剩余 ${number(revokeGrant.available)} 推演点，历史消费不受影响。`);
      setRevokeGrant(null); setRevokeReason("");
      await load(); onChanged();
    } catch (reason) { setError(errorMessage(reason, "撤销推演点失败")); }
    finally { setBusy(false); }
  };

  const grantColumns: TableColumnsType<TurnCreditGrant> = useMemo(() => [
    {
      title: "来源", key: "source", width: 180,
      render: (_, row) => <div className="point-history-identity"><Tag color={SOURCE_META[row.grantType]?.color}>{SOURCE_META[row.grantType]?.label ?? row.grantType}</Tag><small>{row.sourceReference || row.campaignCode || shortId(row.id)}</small></div>,
    },
    { title: "发放", dataIndex: "granted", width: 80, render: number },
    { title: "可用", dataIndex: "available", width: 80, render: (value: number) => <strong>{number(value)}</strong> },
    { title: "预占", dataIndex: "reserved", width: 75, render: number },
    { title: "消费", dataIndex: "consumed", width: 75, render: number },
    { title: "状态", dataIndex: "status", width: 100, render: (value: string) => <Tag color={STATUS_META[value]?.color}>{STATUS_META[value]?.label ?? value}</Tag> },
    { title: "有效期", dataIndex: "expiresAt", width: 170, render: (value: string | null) => value ? fmt(value) : "长期有效" },
    { title: "说明", dataIndex: "reason", ellipsis: true, responsive: ["lg"] },
    {
      title: "操作", key: "action", fixed: "right", width: 92,
      render: (_, row) => row.creditClass === "SPONSORED" && row.status === "ACTIVE" && row.available > 0
        ? <Button danger disabled={row.reserved > 0 || busy} onClick={() => { setRevokeGrant(row); setRevokeReason(""); }} size="small" type="link">撤销</Button>
        : <Text type="secondary">—</Text>,
    },
  ], [busy]);

  const ledgerColumns: TableColumnsType<TurnCreditLedger> = [
    { title: "事件", dataIndex: "eventType", width: 100, render: (value: string) => <Tag color={EVENT_META[value]?.color}>{EVENT_META[value]?.label ?? value}</Tag> },
    { title: "可用变化", dataIndex: "availableDelta", width: 100, render: (value: number) => <span className={value < 0 ? "point-delta-negative" : value > 0 ? "point-delta-positive" : ""}>{signed(value)}</span> },
    { title: "预占变化", dataIndex: "reservedDelta", width: 100, render: signed },
    { title: "消费变化", dataIndex: "consumedDelta", width: 100, render: signed },
    { title: "变动后可用", dataIndex: "balanceAfter", width: 115, render: (value: number) => <strong>{number(value)}</strong> },
    { title: "说明", key: "remark", render: (_, row) => <div className="point-ledger-remark"><span>{row.remark}</span><small>{row.operatorId} · {row.businessReference || shortId(row.grantId)}</small></div> },
    { title: "时间", dataIndex: "createdAt", width: 170, render: fmt },
  ];

  const balance = detail?.balance;
  return <Drawer className="point-account-drawer" destroyOnHidden onClose={onClose} open={Boolean(userId)} size="large" title={detail ? <div className="point-drawer-title"><strong>{detail.user.email}</strong><small title={detail.user.userId}>{shortId(detail.user.userId)}</small></div> : "推演点账户"}>
    {error && <Alert closable description={error} onClose={() => setError("")} showIcon title="操作未完成" type="error" />}
    {notice && <Alert closable onClose={() => setNotice("")} showIcon title={notice} type="success" />}
    {detail && balance && <>
      <section className="point-balance-grid">
        <Card><span>当前可用</span><strong>{number(balance.available)}</strong><small>推演点</small></Card>
        <Card><span>使用中</span><strong>{number(balance.reserved)}</strong><small>回合尚未完成</small></Card>
        <Card><span>已消费</span><strong>{number(balance.consumed)}</strong><small>成功完成的回合</small></Card>
        <Card><span>累计获得</span><strong>{number(balance.granted)}</strong><small>账号下命卷共享</small></Card>
      </section>
      {!balance.ledgerInitialized && <Alert description="首次发放点数时会补建期初记录，玩家已有使用记录不会改变。" showIcon title="该账号尚未建立点数账户" type="info" />}
      <div className="point-account-actions"><div><Text strong>账户记录</Text><Text type="secondary">批次与流水均可翻查全部历史</Text></div><Button disabled={detail.user.status !== "ACTIVE"} icon={<Plus size={17} />} onClick={openGrant} type="primary">赠送推演点</Button></div>
      <Tabs activeKey={tab} items={[{ key: "grants", label: `赠送批次 ${detail.grantsPage.total}` }, { key: "ledger", label: `点数流水 ${detail.ledgerPage.total}` }]} onChange={setTab} />
      {tab === "grants" ? <section className="point-history-section">
        <div className="point-history-filters"><Select aria-label="批次状态" onChange={(value) => { setGrantStatus(value); setGrantPage(1); }} options={[{ value: "", label: "全部批次状态" }, ...Object.entries(STATUS_META).map(([value, item]) => ({ value, label: item.label }))]} value={grantStatus} /><Button icon={<ArrowsClockwise size={16} />} loading={loading} onClick={() => void load()}>刷新</Button></div>
        <Table<TurnCreditGrant> columns={grantColumns} dataSource={detail.grantsPage.rows} loading={loading} locale={{ emptyText: <Empty description="没有符合条件的赠送批次" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} pagination={false} rowKey="id" scroll={{ x: 1050 }} size="small" />
        <Pagination current={grantPage} hideOnSinglePage onChange={setGrantPage} pageSize={detail.grantsPage.size} showSizeChanger={false} showTotal={(total) => `共 ${total} 个批次`} total={detail.grantsPage.total} />
      </section> : <section className="point-history-section">
        <div className="point-history-filters point-ledger-filters"><Select aria-label="流水事件" onChange={(value) => { setEventType(value); setLedgerPage(1); }} options={[{ value: "", label: "全部事件" }, ...Object.entries(EVENT_META).map(([value, item]) => ({ value, label: item.label }))]} value={eventType} /><RangePicker allowClear format="YYYY-MM-DD" onChange={(value) => { setRange(value as [Dayjs | null, Dayjs | null] | null); setLedgerPage(1); }} placeholder={["开始日期", "结束日期"]} value={range} /><Button icon={<ArrowsClockwise size={16} />} loading={loading} onClick={() => void load()}>刷新</Button></div>
        <Table<TurnCreditLedger> columns={ledgerColumns} dataSource={detail.ledgerPage.rows} loading={loading} locale={{ emptyText: <Empty description="所选范围内没有推演点流水" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} pagination={false} rowKey="id" scroll={{ x: 980 }} size="small" />
        <Pagination current={ledgerPage} hideOnSinglePage onChange={setLedgerPage} pageSize={detail.ledgerPage.size} showSizeChanger={false} showTotal={(total) => `共 ${total} 条流水`} total={detail.ledgerPage.total} />
      </section>}
    </>}

    <Modal cancelText="取消" okText="核对赠送" onCancel={() => setGrantOpen(false)} onOk={() => void prepareGrant()} open={grantOpen} title="赠送推演点">
      <Form className="point-grant-form" form={grantForm} layout="vertical" requiredMark={false}>
        <Form.Item label="赠送原因" name="grantType" rules={[{ required: true }]}><Radio.Group buttonStyle="solid"><Radio.Button value="PLAYTEST">灰度测试</Radio.Button><Radio.Button value="FEEDBACK">问题反馈</Radio.Button><Radio.Button value="ACTIVITY">运营活动</Radio.Button><Radio.Button value="ADMIN">人工补发</Radio.Button></Radio.Group></Form.Item>
        <Form.Item label="赠送数量" name="count" rules={[{ required: true, message: "请输入赠送数量" }, { type: "number", min: 1, max: 1000, message: "单次可赠送 1 至 1000 点" }]}><InputNumber min={1} max={1000} suffix="点" /></Form.Item>
        <Space className="point-amount-presets" size={6} wrap>{[10, 20, 50, 100].map((value) => <Button key={value} onClick={() => grantForm.setFieldValue("count", value)} size="small">{value} 点</Button>)}</Space>
        <Form.Item label="有效期" name="validity" rules={[{ required: true }]}><Radio.Group><Radio value="PERMANENT">长期有效</Radio><Radio value="SEVEN_DAYS">7 天</Radio><Radio value="THIRTY_DAYS">30 天</Radio><Radio value="CUSTOM">自定义</Radio></Radio.Group></Form.Item>
        <Form.Item noStyle shouldUpdate={(previous, current) => previous.validity !== current.validity}>{({ getFieldValue }) => getFieldValue("validity") === "CUSTOM" ? <Form.Item label="到期时间（北京时间）" name="customExpiresAt" rules={[{ required: true, message: "请选择到期时间" }]}><DatePicker format="YYYY-MM-DD HH:mm" showTime /></Form.Item> : null}</Form.Item>
        <Form.Item label="操作说明" name="reason" rules={[{ required: true, whitespace: true, message: "请填写赠送原因" }, { max: 240 }]}><Input placeholder="说明为什么赠送，方便以后核对" /></Form.Item>
        <div className="point-grant-advanced"><Form.Item label="反馈或活动编号" name="sourceReference"><Input maxLength={160} placeholder="可不填" /></Form.Item><Form.Item label="内部活动批次" name="campaignCode"><Input maxLength={64} placeholder="可不填" /></Form.Item></div>
      </Form>
    </Modal>

    <Modal cancelText="返回修改" confirmLoading={busy} okText={`确认赠送 ${pendingGrant?.count ?? 0} 点`} onCancel={() => !busy && setPendingGrant(null)} onOk={() => void grant()} open={Boolean(pendingGrant)} title="最后核对">
      {pendingGrant && detail && <Descriptions bordered column={1} size="small"><Descriptions.Item label="玩家账号">{detail.user.email}</Descriptions.Item><Descriptions.Item label="赠送数量">{number(pendingGrant.count)} 推演点</Descriptions.Item><Descriptions.Item label="赠送后可用">{number(detail.balance.available + pendingGrant.count)} 推演点</Descriptions.Item><Descriptions.Item label="有效期">{pendingGrant.expiresAt ? fmt(pendingGrant.expiresAt) : "长期有效"}</Descriptions.Item><Descriptions.Item label="操作说明">{pendingGrant.reason}</Descriptions.Item></Descriptions>}
    </Modal>

    <Modal cancelText="取消" confirmLoading={busy} okButtonProps={{ danger: true, disabled: !revokeReason.trim() }} okText="确认撤销" onCancel={() => !busy && setRevokeGrant(null)} onOk={() => void revoke()} open={Boolean(revokeGrant)} title="撤销未使用推演点">
      {revokeGrant && <><Alert description={`只撤销该批次仍可用的 ${number(revokeGrant.available)} 点，已消费与历史流水不会改变。`} showIcon title="这是不可逆的余额操作" type="warning" /><Input.TextArea autoFocus maxLength={240} onChange={(event) => setRevokeReason(event.currentTarget.value)} placeholder="填写撤销原因" rows={3} value={revokeReason} /></>}
    </Modal>
  </Drawer>;
}
