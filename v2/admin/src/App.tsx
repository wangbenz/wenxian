import { useEffect, useMemo, useState } from "react";

type Tab = "overview" | "players" | "content" | "models" | "limits" | "runtime" | "release";
type Json = Record<string, any>;
const API = "/api/v2/admin";
const tabNames: Record<Tab, string> = { overview: "运营概览", players: "玩家命卷", content: "世界内容", models: "推演模型", limits: "运营规则", runtime: "问题诊断", release: "发布管理" };

async function request(path: string, token = "", options: RequestInit = {}) {
  const response = await fetch(`${API}${path}`, { ...options, headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.headers ?? {}) } });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || `请求失败（${response.status}）`);
  return body;
}

function fmt(value?: string) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"; }
function number(value: unknown) { return Number(value ?? 0).toLocaleString("zh-CN"); }
const STATUS_NAMES: Record<string, string> = { QUEUED: "排队中", INTERPRETING: "正在理解行动", RESOLVING: "正在结算", WRITING: "正在生成叙事", COMMITTING: "正在保存命卷", SUCCEEDED: "已完成", REJECTED: "未通过规则", FAILED: "未完成" };
const STAGE_NAMES: Record<string, string> = { QUEUED: "等待处理", INTERPRETING: "理解行动", RESOLVING: "结算变化", WRITING: "组织叙事", COMMITTING: "保存命卷", DONE: "已结束" };
const TASK_NAMES: Record<string, string> = { ACTION_INTERPRETATION: "行动理解", TURN_PROPOSAL: "回合推演", STORY_PLANNING: "故事推演", NARRATIVE_WRITING: "叙事生成", ORIGIN_GENERATION: "命格生成", CREATION_ORIGIN: "命格生成", SUMMARY: "前情整理" };
function statusName(value?: string) { return STATUS_NAMES[value ?? ""] ?? value ?? "未知"; }
function stageName(value?: string) { return STAGE_NAMES[value ?? ""] ?? value ?? "未知阶段"; }
function taskName(value?: string) { return TASK_NAMES[value ?? ""] ?? value ?? "未知任务"; }

export default function App() {
  const [token, setToken] = useState(() => sessionStorage.getItem("v2-admin-token") ?? "");
  const [tab, setTab] = useState<Tab>("overview");
  const [data, setData] = useState<Json | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function load(nextTab = tab) {
    setLoading(true); setError("");
    try { setData(await request(`/${nextTab === "players" ? "players?limit=50" : nextTab}`, token)); }
    catch (problem) { const message = problem instanceof Error ? problem.message : "加载失败"; setError(message); if (message.includes("登录")) { sessionStorage.removeItem("v2-admin-token"); setToken(""); } }
    finally { setLoading(false); }
  }
  useEffect(() => { if (token) load(tab); }, [token, tab]);
  if (!token) return <Login onLogin={(value) => { sessionStorage.setItem("v2-admin-token", value); setToken(value); }} />;
  return <div className="admin-shell">
    <aside><div className="admin-brand"><span>问</span><p><strong>问仙录</strong><small>V0.2 世界管理台</small></p></div><nav>{(Object.keys(tabNames) as Tab[]).map((id) => <button aria-current={tab === id ? "page" : undefined} key={id} onClick={() => { setData(null); setTab(id); }}>{tabNames[id]}</button>)}</nav><button className="logout" onClick={() => { sessionStorage.removeItem("v2-admin-token"); setToken(""); }}>退出管理台</button></aside>
    <main><header><div><small>WENXIAN CONTROL</small><h1>{tabNames[tab]}</h1></div><button onClick={() => load()}>刷新数据</button></header>{error && <div className="error-card">{error}</div>}{loading && !data ? <div className="loading">正在读取 V0.2 数据…</div> : <Page tab={tab} data={data} token={token} reload={load} />}</main>
  </div>;
}

function Login({ onLogin }: { onLogin: (token: string) => void }) {
  const [username, setUsername] = useState(""); const [password, setPassword] = useState(""); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  return <main className="login-page"><section><div className="login-seal">问</div><small>V0.2 WORLD CONTROL</small><h1>问仙录管理台</h1><p>查看新命卷、世界内容、推演成本与失败原因。</p><form onSubmit={async (event) => { event.preventDefault(); setBusy(true); setError(""); try { const result = await request("/login", "", { method: "POST", body: JSON.stringify({ username, password }) }); onLogin(result.token); } catch (problem) { setError(problem instanceof Error ? problem.message : "登录失败"); } finally { setBusy(false); } }}><label>管理账号<input autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} /></label><label>密码<input autoComplete="current-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label><button disabled={busy || !username || !password}>{busy ? "正在登录…" : "进入管理台"}</button></form>{error && <div className="error-card">{error}</div>}</section></main>;
}

function Page({ tab, data, token, reload }: { tab: Tab; data: any; token: string; reload: () => void }) {
  if (!data) return null;
  if (tab === "overview") return <Overview data={data} />;
  if (tab === "players") return <Players initial={data} token={token} />;
  if (tab === "content") return <Content data={data} />;
  if (tab === "models") return <Models data={data} token={token} reload={reload} />;
  if (tab === "limits") return <Limits data={data} token={token} reload={reload} />;
  if (tab === "runtime") return <Runtime data={data} token={token} reload={reload} />;
  return <Release data={data} token={token} reload={reload} />;
}

function Overview({ data }: { data: Json }) {
  const ai = data.todayAi ?? {};
  const cards = [["账号总数", data.accounts], ["15分钟内活跃", data.onlineAccounts], ["在世命卷", data.activeSaves], ["已终命卷", data.endedSaves], ["累计回合", data.turns], ["今日回合", data.todayTurns], ["运行中", data.runningOperations], ["失败/拒绝", data.failedOperations], ["今日模型调用", ai.calls]];
  return <><section className="version-strip"><p><span>引擎版本</span><strong>{data.engineVersion}</strong></p><p><span>内容版本</span><strong>{data.contentVersion}</strong></p></section><section className="metric-grid">{cards.map(([label, value]) => <article key={label}><span>{label}</span><strong>{number(value)}</strong></article>)}</section><section className="panel"><h2>今日推演消耗</h2><div className="usage-grid"><p><span>输入 Token</span><strong>{number(ai.promptTokens)}</strong></p><p><span>输出 Token</span><strong>{number(ai.completionTokens)}</strong></p><p><span>平均耗时</span><strong>{Math.round(Number(ai.averageLatencyMillis ?? 0) / 100) / 10} 秒</strong></p></div></section></>;
}

function Players({ initial, token }: { initial: Json[]; token: string }) {
  const [players, setPlayers] = useState(initial); const [query, setQuery] = useState(""); const [status, setStatus] = useState(""); const [detail, setDetail] = useState<Json | null>(null); const [error, setError] = useState("");
  async function search() { try { setError(""); setPlayers(await request(`/players?query=${encodeURIComponent(query)}&status=${status}&limit=100`, token)); } catch (problem) { setError(problem instanceof Error ? problem.message : "查询失败"); } }
  return <><section className="filters"><input placeholder="搜索完整邮箱、角色名" value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") search(); }} /><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">全部账号</option><option value="ACTIVE">有在世命卷</option><option value="ENDED">仅历史命卷</option><option value="NO_SAVE">尚未创角</option></select><button onClick={search}>查询</button></section>{error && <div className="error-card">{error}</div>}<section className="table-panel"><table><thead><tr><th>邮箱</th><th>在世/结束</th><th>回合</th><th>最近活动</th></tr></thead><tbody>{players.map((player) => <tr key={player.userId} onClick={async () => { try { setDetail(await request(`/players/${player.userId}`, token)); } catch (problem) { setError(problem instanceof Error ? problem.message : "详情加载失败"); } }}><td><strong>{player.email}</strong><small>{player.status}</small></td><td>{player.activeSaves} / {player.endedSaves}</td><td>{number(player.totalTurns)}</td><td>{fmt(player.lastSaveAt || player.lastLoginAt)}</td></tr>)}</tbody></table></section>{detail && <PlayerDetail data={detail} onClose={() => setDetail(null)} />}</>;
}

function PlayerDetail({ data, onClose }: { data: Json; onClose: () => void }) {
  const ai = data.aiPreference ?? {};
  const quota = data.sponsoredQuota ?? {};
  return <div className="drawer-backdrop" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }}><aside className="drawer"><header><div><small>玩家详情</small><h2>{data.account.email}</h2></div><button onClick={onClose}>关闭</button></header><h3>推演方式</h3><article className="save-card"><div><strong>{ai.credentialSource === "USER" ? "玩家自己的密钥" : "平台提供"}</strong><span>{ai.modelCode || "跟随平台设置"}</span></div><p>{quota.playerOwnedCredential ? "自备密钥不消耗赠送回合" : `赠送回合 ${number(quota.usedTurns)} / ${number(quota.turnLimit)}，剩余 ${number(quota.remainingTurns)}`}</p>{ai.maskedSuffix && <small>密钥末四位 {ai.maskedSuffix} · {ai.credentialStatus === "ACTIVE" ? "已验证" : "待验证"}</small>}</article><h3>V0.2 命卷</h3>{data.saves.length ? data.saves.map((save: Json) => <article className="save-card" key={save.saveId}><div><strong>{save.characterName}</strong><span>{save.status === "ACTIVE" ? "在世" : "已封存"}</span></div><p>{save.race} · {save.realm} · {save.location}</p><small>第 {save.turnNumber} 回 · {save.engineVersion} / {save.contentVersion}</small></article>) : <p className="empty">尚未创建 V0.2 角色</p>}<h3>V0.13 只读档案</h3>{data.legacyArchives?.length ? data.legacyArchives.map((life: Json) => <article className="save-card legacy" key={life.id}><div><strong>{life.characterName}</strong><span>{life.source === "V0_13_ACTIVE" ? "旧版存档" : "旧版终卷"}</span></div><p>{life.race} · {life.realm} · {life.location}</p><small>第 {life.turnNumber} 回 · 绝天后{life.worldYear}年</small></article>) : <p className="empty">没有旧版角色档案</p>}<h3>最近行动</h3>{data.recentOperations.map((operation: Json) => <article className="operation-card" key={operation.operationId}><div><strong>{operation.actionText}</strong><span data-status={operation.status}>{statusName(operation.status)}</span></div><p>{stageName(operation.stage)} · {operation.errorMessage || "已正常结算"}</p><small>{fmt(operation.createdAt)} {operation.problemReference ? `· 问题编号 ${operation.problemReference}` : ""}</small></article>)}</aside></div>;
}

function Content({ data }: { data: Json }) {
  return <><section className="metric-grid compact">{[["地点", data.locations], ["物品", data.items], ["能力", data.abilities], ["事件模板", data.events]].map(([label, value]) => <article key={label}><span>{label}</span><strong>{number(value)}</strong></article>)}</section><section className="panel"><h2>已装载内容包</h2><div className="package-list">{data.packages.map((item: Json) => <article key={item.code}><div><strong>{item.code}</strong><span>{item.status}</span></div><p>{item.type} · V{item.version}</p><small>{fmt(item.publishedAt)}</small></article>)}</div></section></>;
}

function Models({ data, token, reload }: { data: Json; token: string; reload: () => void }) {
  const activeModels = useMemo(() => data.models.filter((item: Json) => item.active), [data]); const [message, setMessage] = useState("");
  return <><section className="metric-grid compact"><article><span>平台推演凭据</span><strong>{data.platformCredentialConfigured ? "可用" : "未配置"}</strong></article><article><span>玩家密钥加密</span><strong>{data.credentialEncryptionConfigured ? "可用" : "未配置"}</strong></article></section><section className="panel"><h2>任务路由</h2><p className="section-note">修改后下一次模型调用立即生效，不需要重启服务。</p><div className="route-list">{data.routes.map((route: Json) => <article key={route.taskType}><div><strong>{taskName(route.taskType)}</strong><span>{route.providerCode}</span></div><select value={route.primaryModelCode} onChange={async (event) => { try { await request(`/models/routes/${route.taskType}`, token, { method: "PUT", body: JSON.stringify({ primaryModelCode: event.target.value, fallbackModelCode: route.fallbackModelCode }) }); setMessage(`${taskName(route.taskType)}已切换`); reload(); } catch (problem) { setMessage(problem instanceof Error ? problem.message : "切换失败"); } }}>{activeModels.map((model: Json) => <option key={model.modelCode} value={model.modelCode}>{model.displayName}</option>)}</select></article>)}</div>{message && <p className="success-note">{message}</p>}</section><section className="panel"><h2>可用模型</h2>{data.models.map((model: Json) => <div className="model-row" key={model.modelCode}><span data-on={model.active} /><p><strong>{model.displayName}</strong><small>{model.modelCode}</small></p><em>{model.active ? "已启用" : "已停用"}</em></div>)}</section></>;
}

function Runtime({ data, token, reload }: { data: Json; token: string; reload: () => void }) {
  const [feedback, setFeedback] = useState("");
  const [query, setQuery] = useState("");
  const [problems, setProblems] = useState<Json[]>(data.recentProblems ?? []);
  const [searching, setSearching] = useState(false);
  useEffect(() => { if (!query) setProblems(data.recentProblems ?? []); }, [data, query]);
  async function cancel(operationId: string) { try { await request(`/runtime/${operationId}/cancel`, token, { method: "POST" }); setFeedback("未提交的推演已终止，玩家命卷不受影响。"); reload(); } catch (problem) { setFeedback(problem instanceof Error ? problem.message : "终止失败"); } }
  async function search() { try { setSearching(true); setFeedback(""); const result = await request(`/runtime?query=${encodeURIComponent(query)}`, token); setProblems(result.recentProblems ?? []); } catch (problem) { setFeedback(problem instanceof Error ? problem.message : "查询失败"); } finally { setSearching(false); } }
  return <><section className="metric-grid compact">{[["排队中", data.queued], ["处理中", data.processing], ["技术失败", data.failed], ["规则未通过", data.rejected]].map(([label, value]) => <article key={label}><span>{label}</span><strong>{number(value)}</strong></article>)}</section>{feedback && <p className="success-note">{feedback}</p>}<section className="filters"><input placeholder="问题编号、邮箱、角色、存档或错误编码" value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") void search(); }} /><button disabled={searching} onClick={() => void search()}>{searching ? "查询中…" : "查询问题"}</button></section><section className="panel"><h2>{query ? "问题查询结果" : "近期未完成行动"}</h2><p className="section-note">问题编号、公开原因与玩家行动放在一起，方便直接回应反馈。</p><div className="problem-list">{problems.length ? problems.map((item: Json) => <article key={item.operationId}><div><strong>{item.errorCode || statusName(item.status)}</strong><span>{item.problemReference || "无问题编号"}</span></div><p>{item.errorMessage || "该行动未完成"}</p><blockquote>{item.actionText}</blockquote><small>{item.email || "未知账号"}{item.characterName ? ` · ${item.characterName}` : ""} · {fmt(item.createdAt)} · {stageName(item.stage)}</small>{["QUEUED", "INTERPRETING", "RESOLVING", "WRITING"].includes(item.status) && <button onClick={() => void cancel(item.operationId)}>终止本次推演</button>}</article>) : <p className="empty">没有找到符合条件的 V0.2 行动</p>}</div></section></>;
}

function Limits({ data, token, reload }: { data: Json; token: string; reload: () => void }) {
  const [turnLimit, setTurnLimit] = useState(Number(data.turnLimit ?? 20));
  const [maxActiveSaves, setMaxActiveSaves] = useState(Number(data.maxActiveSaves ?? 2));
  const [limitMessage, setLimitMessage] = useState(data.limitMessage ?? "");
  const [contactText, setContactText] = useState(data.contactText ?? "");
  const [customApiEnabled, setCustomApiEnabled] = useState(Boolean(data.customApiEnabled));
  const [feedback, setFeedback] = useState("");
  async function save() {
    try {
      setFeedback("");
      await request("/limits", token, { method: "PUT", body: JSON.stringify({ turnLimit, maxActiveSaves, limitMessage, contactText, customApiEnabled }) });
      setFeedback("运营规则已保存，下一次行动立即生效。"); reload();
    } catch (problem) { setFeedback(problem instanceof Error ? problem.message : "保存失败"); }
  }
  return <section className="panel release-panel"><h2>玩家回合与命卷规则</h2><p className="section-note">赠送回合按账号累计，旧版与 V0.2 已使用量会一起计算；裁决拒绝不扣回合。</p><label>账号获赠回合<input max={1000} min={1} onChange={(event) => setTurnLimit(Number(event.target.value))} type="number" value={turnLimit} /></label><label>同账号存活角色上限<input max={8} min={1} onChange={(event) => setMaxActiveSaves(Number(event.target.value))} type="number" value={maxActiveSaves} /><small>调低时如果已有玩家超过新上限，系统会拒绝保存，避免误伤现有命卷。</small></label><label>额度用完提示<textarea maxLength={255} onChange={(event) => setLimitMessage(event.target.value)} rows={3} value={limitMessage} /></label><label>联系与反馈方式<input maxLength={255} onChange={(event) => setContactText(event.target.value)} placeholder="例如：加入QQ群……" value={contactText} /></label><label className="switch"><input checked={customApiEnabled} onChange={(event) => setCustomApiEnabled(event.target.checked)} type="checkbox" /><span>{customApiEnabled ? "允许玩家使用自己的密钥" : "暂不开放玩家密钥"}</span></label><button className="primary-admin" onClick={() => void save()}>保存运营规则</button>{feedback && <p className="success-note">{feedback}</p>}</section>;
}

function Release({ data, token, reload }: { data: Json; token: string; reload: () => void }) {
  const [restricted, setRestricted] = useState(Boolean(data.restricted));
  const [title, setTitle] = useState(data.title ?? "命卷暂时封存");
  const [message, setMessage] = useState(data.message ?? "游戏正在更新中，请稍后再回来。");
  const [resumeAt, setResumeAt] = useState(data.resumeAt ?? "");
  const [email, setEmail] = useState("");
  const [note, setNote] = useState("");
  const [feedback, setFeedback] = useState("");
  const [announcement, setAnnouncement] = useState({ version: "0.2.0", title: "", content: "", status: "DRAFT", announceAll: false, showOnLogin: false });
  async function run(work: () => Promise<unknown>, success: string) { try { setFeedback(""); await work(); setFeedback(success); reload(); } catch (problem) { setFeedback(problem instanceof Error ? problem.message : "操作失败"); } }
  return <><section className="panel release-panel"><div className="release-heading"><div><h2>灰度访问</h2><p className="section-note">开启后只有白名单账号能进入游戏，其他玩家会看到下面的更新提示。</p></div><label className="switch"><input checked={restricted} onChange={(event) => setRestricted(event.target.checked)} type="checkbox" /><span>{restricted ? "已限制" : "开放中"}</span></label></div><label>提示标题<input maxLength={80} value={title} onChange={(event) => setTitle(event.target.value)} /></label><label>提示内容<textarea maxLength={500} rows={4} value={message} onChange={(event) => setMessage(event.target.value)} /></label><label>预计恢复时间<input placeholder="例如：今晚 22:00 或约 30 分钟" value={resumeAt} onChange={(event) => setResumeAt(event.target.value)} /></label><button className="primary-admin" onClick={() => void run(() => request("/release", token, { method: "PUT", body: JSON.stringify({ restricted, title, message, resumeAt }) }), "灰度设置已保存")}>保存灰度设置</button>{feedback && <p className="success-note">{feedback}</p>}</section><section className="panel"><h2>测试账号白名单</h2><div className="inline-form"><input placeholder="完整玩家邮箱" value={email} onChange={(event) => setEmail(event.target.value)} /><input placeholder="备注（可选）" value={note} onChange={(event) => setNote(event.target.value)} /><button onClick={() => void run(() => request("/release/whitelist", token, { method: "POST", body: JSON.stringify({ email, note }) }), "已加入白名单")}>加入</button></div><div className="whitelist-list">{data.whitelist.length ? data.whitelist.map((entry: Json) => <article key={entry.userId}><p><strong>{entry.email}</strong><small>{entry.note || "无备注"}</small></p><button onClick={() => void run(() => request(`/release/whitelist/${entry.userId}`, token, { method: "DELETE" }), "已移出白名单")}>移除</button></article>) : <p className="empty">还没有测试账号。开启限制前请先加入至少一个可登录账号。</p>}</div></section><section className="panel release-panel"><h2>版本公告</h2><p className="section-note">“登录页展示”控制折叠公告；“进入后弹出”只对尚未阅读的玩家生效。</p><label>版本<input maxLength={32} onChange={(event) => setAnnouncement({ ...announcement, version: event.target.value })} value={announcement.version} /></label><label>标题<input maxLength={120} onChange={(event) => setAnnouncement({ ...announcement, title: event.target.value })} value={announcement.title} /></label><label>正文<textarea onChange={(event) => setAnnouncement({ ...announcement, content: event.target.value })} rows={7} value={announcement.content} /></label><div className="announcement-switches"><label className="switch"><input checked={announcement.showOnLogin} onChange={(event) => setAnnouncement({ ...announcement, showOnLogin: event.target.checked })} type="checkbox" /><span>登录页折叠展示</span></label><label className="switch"><input checked={announcement.announceAll} onChange={(event) => setAnnouncement({ ...announcement, announceAll: event.target.checked })} type="checkbox" /><span>玩家进入后弹出</span></label></div><div className="announcement-actions"><button onClick={() => void run(() => request("/release/announcements", token, { method: "POST", body: JSON.stringify({ ...announcement, status: "DRAFT" }) }), "公告草稿已保存")}>保存草稿</button><button className="primary-admin" onClick={() => void run(() => request("/release/announcements", token, { method: "POST", body: JSON.stringify({ ...announcement, status: "PUBLISHED" }) }), "公告已发布")}>立即发布</button></div><div className="announcement-list">{data.announcements?.map((item: Json) => <article key={item.id}><p><strong>V{item.version} · {item.title}</strong><small>{item.status === "PUBLISHED" ? "已发布" : "草稿"} · {item.showOnLogin ? "登录页展示" : "登录页隐藏"} · {item.announceAll ? "进入后弹出" : "不弹出"}</small></p><button onClick={() => setAnnouncement({ id: item.id, version: item.version, title: item.title, content: item.content, status: item.status, announceAll: item.announceAll, showOnLogin: item.showOnLogin } as any)}>编辑</button></article>)}</div></section></>;
}
