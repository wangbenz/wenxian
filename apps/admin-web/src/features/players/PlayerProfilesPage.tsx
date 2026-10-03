import "./PlayerProfilesPage.css";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, List, Modal,
  Pagination, Select, Space, Statistic, Table, Tag, Typography, type TableColumnsType,
} from "antd";
import {
  BookOpenText, CaretRight, CrownSimple, Key, MagnifyingGlass, UserList,
} from "@phosphor-icons/react";
import dayjs from "dayjs";
import { useSearchParams } from "react-router-dom";
import {
  adminApi, type AgentExecution, type LifeSummary, type TurnPage, type TurnRankingItem,
  type TurnRow, type UserDetail, type UserListResult, type UserRow,
} from "../../admin-api";
import { shanghaiToday } from "../analytics/useAnalyticsDateRange";

const { Paragraph, Text, Title } = Typography;
const PAGE_SIZE = 20;
const CHARACTER_META: Record<UserRow["characterState"], { label: string; color: string }> = {
  NONE: { label: "尚未创建", color: "default" },
  CURRENT: { label: "当前有命卷", color: "success" },
  RESTARTED: { label: "仅有已删除命卷", color: "warning" },
};

const number = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";
const shortId = (value: string) => value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
const latency = (value: number) => value ? `${(value / 1000).toFixed(1)} 秒` : "—";
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;
const lifeEndReason = (value: LifeSummary["endReason"]) => value === "ENDED" ? "等待终局选择" : value === "ARCHIVED" ? "已删除" : "当前命卷";
const prettyJson = (value: string) => {
  if (!value) return "本回合未记录结构化变化。";
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
};

/**
 * 玩家档案页以账号为入口串联命卷和回合，但只展示后端已经确认的结构化状态。
 * 搜索和筛选写入 URL，刷新后仍能恢复；默认不限制注册日期，因此历史玩家始终可查询。
 */
export function PlayerProfilesPage({ initialRegisteredDate = "" }: { initialRegisteredDate?: string }) {
  const [params, setParams] = useSearchParams();
  const search = params.get("q") ?? "";
  const characterState = params.get("state") ?? "";
  const registeredDate = params.get("registered") ?? initialRegisteredDate;
  const page = Math.max(1, Number(params.get("page")) || 1);
  const [draftSearch, setDraftSearch] = useState(search);
  const [draftState, setDraftState] = useState(characterState);
  const [draftDate, setDraftDate] = useState(registeredDate);
  const [data, setData] = useState<UserListResult | null>(null);
  const [ranking, setRanking] = useState<TurnRankingItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [rankingLoading, setRankingLoading] = useState(false);
  const [error, setError] = useState("");
  const [rankingError, setRankingError] = useState("");
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const initialDateApplied = useRef(false);

  useEffect(() => {
    if (initialDateApplied.current) return;
    initialDateApplied.current = true;
    if (!initialRegisteredDate || params.has("registered")) return;
    const next = new URLSearchParams(params);
    next.set("registered", initialRegisteredDate);
    setParams(next, { replace: true });
  }, [initialRegisteredDate, params, setParams]);
  useEffect(() => {
    setDraftSearch(search); setDraftState(characterState); setDraftDate(registeredDate);
  }, [characterState, registeredDate, search]);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setData(await adminApi.users(search, characterState, "", registeredDate, page - 1, PAGE_SIZE)); }
    catch (reason) { setError(errorMessage(reason, "玩家列表加载失败")); }
    finally { setLoading(false); }
  }, [characterState, page, registeredDate, search]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    let live = true; setRankingLoading(true); setRankingError("");
    adminApi.turnRanking(10).then((result) => live && setRanking(result))
      .catch((reason) => live && setRankingError(errorMessage(reason, "当前命卷回合榜加载失败")))
      .finally(() => live && setRankingLoading(false));
    return () => { live = false; };
  }, []);

  const commit = (overrides: Record<string, string | null> = {}) => {
    const next = new URLSearchParams(params);
    const values = { q: draftSearch.trim(), state: draftState, registered: draftDate, page: "", ...overrides };
    Object.entries(values).forEach(([key, value]) => value ? next.set(key, value) : next.delete(key));
    setParams(next);
  };
  const changePage = (nextPage: number) => {
    const next = new URLSearchParams(params);
    if (nextPage === 1) next.delete("page"); else next.set("page", String(nextPage));
    setParams(next);
  };

  const rows = data?.[1].rows ?? [];
  const total = data?.[0].total ?? 0;
  const columns: TableColumnsType<UserRow> = [
    { title: "玩家账号", key: "account", width: 260, render: (_, row) => <div className="player-primary-cell"><strong>{row.email}</strong><span title={row.userId}>{shortId(row.userId)}</span></div> },
    { title: "最近角色", key: "character", width: 190, render: (_, row) => <div className="player-primary-cell"><span>{row.playerName || "尚未创建角色"}</span><small>{row.race || "—"} · {row.realm || "尚未入世"}</small></div> },
    { title: "命卷", key: "saves", width: 100, render: (_, row) => <div className="player-primary-cell"><span>{row.activeSaveCount} 当前</span><small>{row.archiveCount} 已删除</small></div> },
    { title: "角色状态", dataIndex: "characterState", width: 140, render: (value: UserRow["characterState"]) => <Tag color={CHARACTER_META[value].color}>{CHARACTER_META[value].label}</Tag> },
    { title: "当前回合", dataIndex: "turnCount", width: 100, render: (value: number) => number(value) },
    { title: "推演服务", key: "credential", width: 150, render: (_, row) => row.credentialConfigured ? <Tag color={row.credentialStatus === "ACTIVE" ? "cyan" : "warning"}>自备 · •••• {row.credentialSuffix}</Tag> : <Tag>平台服务</Tag> },
    { title: "最近活动", dataIndex: "lastLoginAt", width: 180, render: fmt },
    { title: "操作", key: "action", fixed: "right", width: 78, render: (_, row) => <Button onClick={(event) => { event.stopPropagation(); setSelectedUserId(row.userId); }} size="small" type="link">查看</Button> },
  ];

  return <div className="player-profiles-page">
    <section className="player-profiles-intro">
      <div><Title level={2}>玩家档案</Title><Paragraph>查看玩家账号、命卷和回合记录，并处理登录问题。</Paragraph></div>
      <UserList aria-hidden size={42} weight="duotone" />
    </section>
    {rankingError && <Alert description={rankingError} showIcon title="回合榜暂时不可用" type="warning" />}
    <RankingCard loading={rankingLoading} onSelect={setSelectedUserId} rows={ranking} />
    <Card className="player-filter-card" variant="borderless">
      <form className="player-filter-form" onSubmit={(event: FormEvent) => { event.preventDefault(); commit(); }}>
        <label><span>玩家邮箱、角色名、用户或命卷 ID</span><Input allowClear onChange={(event) => setDraftSearch(event.currentTarget.value)} placeholder="搜索玩家" prefix={<MagnifyingGlass size={17} />} value={draftSearch} /></label>
        <label><span>角色状态</span><Select onChange={setDraftState} options={[{ value: "", label: "全部角色状态" }, { value: "NONE", label: "尚未创建角色" }, { value: "CURRENT", label: "当前有命卷" }, { value: "RESTARTED", label: "仅有已删除命卷" }]} value={draftState} /></label>
        <label><span>注册日期</span><DatePicker allowClear format="YYYY-MM-DD" onChange={(value) => setDraftDate(value?.format("YYYY-MM-DD") ?? "")} placeholder="全部历史" value={draftDate ? dayjs(draftDate) : null} /></label>
        <div className="player-filter-actions"><Button htmlType="submit" type="primary">查询</Button><Button onClick={() => { setDraftSearch(""); setDraftState(""); setDraftDate(""); commit({ q: null, state: null, registered: null }); }}>重置</Button></div>
      </form>
      <Space className="player-filter-shortcuts" wrap><Button onClick={() => { const today = shanghaiToday(); setDraftDate(today); setDraftState("NONE"); commit({ registered: today, state: "NONE" }); }} size="small">今日注册但未创角</Button><Text type="secondary">注册日期按北京时间自然日；留空即查询全部历史玩家。</Text></Space>
    </Card>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon title="玩家列表暂时无法读取" type="error" />}
    <Card className="player-result-card" variant="borderless">
      <div className="player-result-heading"><div><Text strong>玩家列表</Text><Text type="secondary">{registeredDate ? `${registeredDate} 注册 · ` : "全部注册历史 · "}共 {number(total)} 个账号</Text></div><Button loading={loading} onClick={() => void load()}>刷新</Button></div>
      <Table<UserRow> columns={columns} dataSource={rows} loading={loading} locale={{ emptyText: <Empty description="没有符合当前条件的玩家" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} onRow={(row) => ({ onClick: () => setSelectedUserId(row.userId) })} pagination={false} rowKey="userId" scroll={{ x: 1200 }} size="middle" />
      <Pagination current={page} hideOnSinglePage onChange={changePage} pageSize={PAGE_SIZE} showSizeChanger={false} showTotal={(value) => `共 ${value} 个账号`} total={total} />
    </Card>
    <PlayerDrawer onClose={() => setSelectedUserId(null)} userId={selectedUserId} />
  </div>;
}

function RankingCard({ rows, loading, onSelect }: { rows: TurnRankingItem[]; loading: boolean; onSelect: (userId: string) => void }) {
  return <Card className="player-ranking-card" loading={loading} title={<span className="player-card-title"><CrownSimple size={20} weight="duotone" />当前命卷回合榜</span>} variant="borderless">
    {rows.length ? <div className="player-ranking-list">{rows.map((row, index) => <Button className="player-ranking-item" key={row.saveId} onClick={() => onSelect(row.userId)}>
      <span className={index < 3 ? `player-rank player-rank-${index + 1}` : "player-rank"}>{index + 1}</span>
      <span className="player-ranking-identity"><strong>{row.playerName || "尚未命名"}</strong><small>{row.email}</small><small>{row.realm || "尚未入道"}</small></span>
      <b>{number(row.turn)}<small>回</small></b>
    </Button>)}</div> : <Empty description="当前还没有已创建角色的玩家" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
    <Text className="player-ranking-note" type="secondary">按当前命卷统计；点击玩家可查看全部命卷。</Text>
  </Card>;
}

function PlayerDrawer({ userId, onClose }: { userId: string | null; onClose: () => void }) {
  const [detail, setDetail] = useState<UserDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const [selectedLife, setSelectedLife] = useState<LifeSummary | null>(null);
  const [modal, contextHolder] = Modal.useModal();

  const load = useCallback(async () => {
    if (!userId) { setDetail(null); return; }
    setLoading(true); setError("");
    try { setDetail(await adminApi.userDetail(userId)); }
    catch (reason) { setError(errorMessage(reason, "玩家详情加载失败")); }
    finally { setLoading(false); }
  }, [userId]);
  useEffect(() => { setNotice(""); setTemporaryPassword(""); setSelectedLife(null); void load(); }, [load]);

  const unlock = () => modal.confirm({
    title: "解除该玩家的登录限制？",
    content: "只清除临时登录限制，不修改密码、命卷或推演点。操作会写入审计记录。",
    okText: "确认解除", cancelText: "取消",
    onOk: async () => { if (!userId) return; const result = await adminApi.unlockPlayerAuth(userId); setNotice(result.message); },
  });
  const resetPassword = () => modal.confirm({
    title: "为该玩家生成临时密码？",
    content: "原密码会立即失效，临时密码只展示本次。请确认正在处理玩家本人提出的登录问题。",
    okText: "生成临时密码", cancelText: "取消", okButtonProps: { danger: true },
    onOk: async () => { if (!userId) return; const result = await adminApi.resetPlayerPassword(userId); setTemporaryPassword(result.temporaryPassword); setNotice("临时密码只展示本次，请立即安全交付给玩家。"); await load(); },
  });

  return <>{contextHolder}<Drawer className="player-detail-drawer" destroyOnHidden onClose={onClose} open={Boolean(userId)} size={760} title={detail?.user.email || "玩家详情"}>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon type="error" />}
    {loading && !detail ? <Card loading /> : detail && <>
      <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small" title="账号信息">
        <Descriptions.Item label="用户 ID"><Text copyable>{detail.user.userId}</Text></Descriptions.Item>
        <Descriptions.Item label="账号状态"><Tag color={detail.user.status === "ACTIVE" ? "success" : "default"}>{detail.user.status}</Tag></Descriptions.Item>
        <Descriptions.Item label="注册时间">{fmt(detail.user.registeredAt)}</Descriptions.Item>
        <Descriptions.Item label="最近活动">{fmt(detail.user.lastLoginAt)}</Descriptions.Item>
        <Descriptions.Item label="登录密码">{detail.user.passwordConfigured ? "已设置" : "未设置"}</Descriptions.Item>
        <Descriptions.Item label="注册网络">{detail.user.registrationNetwork || "未记录"}</Descriptions.Item>
        <Descriptions.Item label="自备服务" span={2}>{detail.user.credentialConfigured ? `${detail.user.credentialStatus} · •••• ${detail.user.credentialSuffix} · ${detail.user.credentialVerifiedAt ? `验证于 ${fmt(detail.user.credentialVerifiedAt)}` : "尚无验证记录"}` : "未设置"}</Descriptions.Item>
      </Descriptions>
      <section className="player-account-metrics">
        <Statistic title="累计命卷" value={detail.accountSummary.totalCharacters} />
        <Statistic title="当前命卷" value={detail.accountSummary.currentCharacters} />
        <Statistic title="累计回合" value={detail.accountSummary.totalTurns} />
        <Statistic title="章节 Token" value={detail.accountSummary.totalTokens} />
      </section>
      <Card className="player-support-card" title={<span className="player-card-title"><Key size={19} />账号协助</span>}>
        <Paragraph>只在玩家明确反馈无法登录时操作；这里不会修改命卷、模型配置或推演点。</Paragraph>
        <Space wrap><Button onClick={unlock}>解除登录限制</Button><Button danger onClick={resetPassword}>生成临时密码</Button></Space>
        {notice && <Alert description={notice} showIcon type="info" />}
        {temporaryPassword && <Alert description={<Text copyable strong>{temporaryPassword}</Text>} showIcon title="本次临时密码" type="warning" />}
      </Card>
      <LifeSection empty="尚未创建当前命卷" lives={detail.currentLives} onSelect={setSelectedLife} title="当前命卷" />
      <LifeSection empty="暂无已删除命卷" lives={detail.historicalLives} onSelect={setSelectedLife} title="已删除命卷" />
    </>}
  </Drawer>
  <TurnHistoryDrawer life={selectedLife} onClose={() => setSelectedLife(null)} userId={userId} />
  </>;
}

function LifeSection({ title, lives, empty, onSelect }: { title: string; lives: LifeSummary[]; empty: string; onSelect: (life: LifeSummary) => void }) {
  return <Card className="player-life-section" title={`${title} · ${lives.length}`}>
    {lives.length ? <List dataSource={lives} renderItem={(life) => <List.Item actions={[<Button key="turns" onClick={() => onSelect(life)} type="link">查看回合<CaretRight size={14} /></Button>]}>
      <List.Item.Meta avatar={<BookOpenText size={26} weight="duotone" />} description={`${life.realm || "尚未入世"} · ${life.turn} 回 · ${number(life.totalTokens)} Token · ${fmt(life.at)}`} title={<Space><Text strong>{life.playerName || "尚未命名"}</Text><Tag color={life.endReason === "ACTIVE" ? "success" : life.endReason === "ENDED" ? "warning" : "default"}>{lifeEndReason(life.endReason)}</Tag></Space>} />
    </List.Item>} /> : <Empty description={empty} image={Empty.PRESENTED_IMAGE_SIMPLE} />}
  </Card>;
}

function TurnHistoryDrawer({ userId, life, onClose }: { userId: string | null; life: LifeSummary | null; onClose: () => void }) {
  const [data, setData] = useState<TurnPage | null>(null);
  const [page, setPage] = useState(1);
  const [draftTurn, setDraftTurn] = useState("");
  const [turn, setTurn] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<TurnRow | null>(null);
  const load = useCallback(async () => {
    if (!userId || !life) { setData(null); return; }
    setLoading(true); setError("");
    try { setData(await adminApi.userTurns(userId, life.saveId, page - 1, PAGE_SIZE, turn)); }
    catch (reason) { setError(errorMessage(reason, "回合记录加载失败")); }
    finally { setLoading(false); }
  }, [life, page, turn, userId]);
  useEffect(() => { setPage(1); setDraftTurn(""); setTurn(""); setSelected(null); }, [life?.saveId]);
  useEffect(() => { void load(); }, [load]);

  const columns: TableColumnsType<TurnRow> = [
    { title: "回合", dataIndex: "turn", width: 80 },
    { title: "玩家行动", dataIndex: "action", width: 360, render: (value: string) => <div className="player-turn-action">{value}</div> },
    { title: "Token", dataIndex: "totalTokens", width: 100, render: number },
    { title: "耗时", dataIndex: "latency", width: 100, render: latency },
    { title: "状态", dataIndex: "status", width: 100, render: (value: string) => <Tag color={value === "SUCCESS" ? "success" : "error"}>{value === "SUCCESS" ? "成功" : value}</Tag> },
    { title: "发生时间", dataIndex: "at", width: 170, render: fmt },
    { title: "操作", key: "action", fixed: "right", width: 72, render: (_, row) => <Button onClick={() => setSelected(row)} size="small" type="link">详情</Button> },
  ];
  return <><Drawer className="player-turn-drawer" destroyOnHidden onClose={onClose} open={Boolean(life)} size={920} title={life ? `${life.playerName || "未命名"} · 回合记录` : "回合记录"}>
    <form className="player-turn-toolbar" onSubmit={(event) => { event.preventDefault(); setPage(1); setTurn(draftTurn); }}><Input allowClear inputMode="numeric" onChange={(event) => setDraftTurn(event.currentTarget.value.replace(/\D/g, ""))} placeholder="输入回合编号" value={draftTurn} /><Button htmlType="submit" type="primary">定位</Button>{turn && <Button onClick={() => { setDraftTurn(""); setTurn(""); setPage(1); }}>查看全部</Button>}<Text type="secondary">共 {number(data?.total ?? 0)} 回</Text></form>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon type="error" />}
    <Table<TurnRow> columns={columns} dataSource={data?.rows ?? []} loading={loading} locale={{ emptyText: <Empty description="没有符合条件的回合记录" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} pagination={false} rowKey="turn" scroll={{ x: 1050 }} size="small" />
    <Pagination current={page} hideOnSinglePage onChange={setPage} pageSize={PAGE_SIZE} showSizeChanger={false} total={data?.total ?? 0} />
  </Drawer><TurnDetailModal onClose={() => setSelected(null)} saveId={life?.saveId ?? ""} turn={selected} /></>;
}

function TurnDetailModal({ saveId, turn, onClose }: { saveId: string; turn: TurnRow | null; onClose: () => void }) {
  const [executions, setExecutions] = useState<AgentExecution[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!turn || !saveId) { setExecutions([]); return; }
    let live = true; setLoading(true); setError("");
    adminApi.executions(saveId, turn.turn).then((result) => live && setExecutions(result))
      .catch((reason) => live && setError(errorMessage(reason, "生成尝试加载失败")))
      .finally(() => live && setLoading(false));
    return () => { live = false; };
  }, [saveId, turn]);

  return <Modal footer={null} onCancel={onClose} open={Boolean(turn)} title={turn ? `第 ${turn.turn} 回详情` : "回合详情"} width={800}>
    {turn && <div className="player-turn-detail">
      <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small">
        <Descriptions.Item label="生成模型">{turn.model || "未记录"}</Descriptions.Item><Descriptions.Item label="生成耗时">{latency(turn.latency)}</Descriptions.Item>
        <Descriptions.Item label="输入 Token">{number(turn.promptTokens)}</Descriptions.Item><Descriptions.Item label="输出 Token">{number(turn.completionTokens)}</Descriptions.Item>
        <Descriptions.Item label="玩家行动" span={2}>{turn.action}</Descriptions.Item>
      </Descriptions>
      <section><Text strong>状态变更</Text><pre>{prettyJson(turn.stateDelta)}</pre></section>
      <section><Text strong>生成尝试</Text>{error && <Alert description={error} showIcon type="error" />}{loading ? <Card loading /> : executions.length ? <List dataSource={executions} renderItem={(item) => <List.Item><List.Item.Meta description={item.detail || "无异常记录"} title={<Space><Tag color={item.status === "SUCCESS" ? "success" : "error"}>{item.status}</Tag><Text>第 {item.attempt} 次 · {item.model || "未记录"} · {latency(item.latency)}</Text></Space>} /></List.Item>} /> : <Empty description="该回合没有额外生成记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />}</section>
    </div>}
  </Modal>;
}
