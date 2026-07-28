import "./App.css";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { adminApi, clearToken, isAuthenticated, setToken, type AgentExecution, type DashboardData, type InviteCodeItem, type LifeSummary, type ModelConfig, type ReleaseNoteAdmin, type TurnPage, type TurnRow, type UserDetail, type UserRow } from "./admin-api";

type Page = "dashboard" | "users" | "invite-codes" | "releases" | "models" | "logs";
const NAV: Array<[Page, string, string]> = [
  ["dashboard", "运营概览", "OVERVIEW"], ["users", "玩家档案", "PLAYERS"],
  ["invite-codes", "邀请码", "INVITES"], ["releases", "版本公告", "RELEASES"],
  ["models", "生成模型", "MODELS"], ["logs", "操作记录", "AUDIT"],
];
const fmt = (value?: string | null) => value ? new Date(value).toLocaleString("zh-CN") : "—";
const num = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const latency = (value: number) => value ? `${(value / 1000).toFixed(1)} 秒` : "—";
const difficulty = (value: string) => value === "HARD" ? "困难" : "普通";

function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null); const [error, setError] = useState(""); const [loading, setLoading] = useState(true); const [nonce, setNonce] = useState(0);
  useEffect(() => { let live = true; setLoading(true); setError(""); loader().then((value) => live && setData(value)).catch((reason) => live && setError(reason instanceof Error ? reason.message : "加载失败")).finally(() => live && setLoading(false)); return () => { live = false; }; }, [...deps, nonce]);
  return { data, error, loading, reload: () => setNonce((value) => value + 1) };
}

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated);
  if (!authed) return <Login onDone={() => setAuthed(true)} />;
  return <Shell onLogout={() => { clearToken(); setAuthed(false); }} />;
}

function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState(""); const [password, setPassword] = useState(""); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(""); try { const result = await adminApi.login(username.trim(), password); setToken(result.token); onDone(); } catch (reason) { setError(reason instanceof Error ? reason.message : "登录失败"); } finally { setBusy(false); } };
  return <main className="login-shell"><form className="login-card" onSubmit={submit}><span className="brand-mark">问</span><p className="kicker">WENXIAN OPERATIONS</p><h1>问仙录管理台</h1><p>管理试玩玩家、命卷版本与生成服务。</p><label>账号<input autoComplete="username" autoFocus value={username} onChange={(e) => setUsername(e.currentTarget.value)} /></label><label>密码<input autoComplete="current-password" type="password" value={password} onChange={(e) => setPassword(e.currentTarget.value)} /></label>{error && <p className="error">{error}</p>}<button className="primary" disabled={busy} type="submit">{busy ? "正在验证…" : "进入管理台"}</button></form></main>;
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("dashboard"); const [open, setOpen] = useState(false);
  const item = NAV.find(([id]) => id === page)!;
  return <div className="admin-shell"><aside className={open ? "sidebar open" : "sidebar"}><div className="sidebar-brand"><strong>问仙录</strong><span>试玩运营台 · V0.11</span></div><nav>{NAV.map(([id, label, english]) => <button aria-current={page === id ? "page" : undefined} key={id} onClick={() => { setPage(id); setOpen(false); }}><small>{english}</small>{label}</button>)}</nav><button className="logout" onClick={onLogout}>退出管理台</button></aside>{open && <button aria-label="关闭菜单" className="sidebar-mask" onClick={() => setOpen(false)} />}<main className="admin-main"><header><button className="menu" onClick={() => setOpen(true)}>☰</button><div><p>{item[2]}</p><h1>{item[1]}</h1></div><span>内部试玩环境</span></header><section className="admin-content">{page === "dashboard" && <Dashboard />}{page === "users" && <Users />}{page === "invite-codes" && <Invites />}{page === "releases" && <Releases />}{page === "models" && <Models />}{page === "logs" && <Logs />}</section></main></div>;
}

function State({ loading, error, children }: { loading: boolean; error: string; children: ReactNode }) { if (loading) return <div className="state">正在读取数据…</div>; if (error) return <div className="state error">{error}</div>; return <>{children}</>; }

function Dashboard() {
  const query = useLoad<DashboardData>(() => adminApi.dashboard(), []);
  return <State loading={query.loading} error={query.error}>{query.data && <><div className="metric-grid">{[
    ["测试用户", num(query.data.totalUsers), "已登记邮箱"], ["已有角色", num(query.data.usersWithCharacter), "当前命卷"], ["今日活跃", num(query.data.todayActive), "今日登录"], ["今日回合", num(query.data.todayTurns), "玩家行动"], ["平均生成", latency(query.data.avgLatency), "全部回合"], ["今日用量", num(query.data.todayTokens), "输入与输出 Token"], ["今日失败", num(query.data.failedToday), "需要关注"], ["当前模型", query.data.currentModel, "新请求立即生效"],
  ].map(([label, value, note]) => <article className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>)}</div><Panel title="最近生成"><Table><thead><tr><th>玩家</th><th>回合</th><th>行动</th><th>总 Token</th><th>生成耗时</th><th>发生时间</th></tr></thead><tbody>{query.data.recentTurns.map((turn, index) => <tr key={`${turn.at}-${index}`}><td>{turn.playerName || "未命名"}<small>{turn.realm}</small></td><td>{turn.turn}</td><td className="wide">{turn.action}</td><td>{num(turn.tokens)}</td><td>{latency(turn.latency)}</td><td>{fmt(turn.at)}</td></tr>)}</tbody></Table></Panel></>}</State>;
}

function Users() {
  const [search, setSearch] = useState(""); const [state, setState] = useState(""); const [difficultyFilter, setDifficulty] = useState(""); const [page, setPage] = useState(0); const [selected, setSelected] = useState<string | null>(null);
  const query = useLoad(() => adminApi.users(search, state, difficultyFilter, "", page, 20), [search, state, difficultyFilter, page]);
  const meta = query.data?.[0]; const rows = query.data?.[1].rows ?? [];
  return <><div className="filters"><input placeholder="搜索完整邮箱、角色名、用户或存档 ID" value={search} onChange={(e) => { setSearch(e.currentTarget.value); setPage(0); }} /><select value={state} onChange={(e) => { setState(e.currentTarget.value); setPage(0); }}><option value="">全部角色状态</option><option value="NONE">尚未创建角色</option><option value="CURRENT">当前有角色</option><option value="RESTARTED">仅有前世记录</option></select><select value={difficultyFilter} onChange={(e) => { setDifficulty(e.currentTarget.value); setPage(0); }}><option value="">全部难度</option><option value="NORMAL">普通</option><option value="HARD">困难</option></select></div><State loading={query.loading} error={query.error}><Panel title={`玩家列表 · ${meta?.total ?? 0}`}><Table><thead><tr><th>邮箱</th><th>当前角色</th><th>状态</th><th>难度</th><th>回合</th><th>前世</th><th>最近登录</th></tr></thead><tbody>{rows.map((user: UserRow) => <tr className="clickable" key={user.userId} onClick={() => setSelected(user.userId)}><td>{user.email}<small>{user.userId}</small></td><td>{user.playerName || "尚未创建角色"}<small>{user.realm || "—"}</small></td><td><Badge value={user.characterState} /></td><td>{user.saveId ? difficulty(user.difficulty) : "—"}</td><td>{user.turnCount}</td><td>{user.archiveCount}</td><td>{fmt(user.lastLoginAt)}</td></tr>)}</tbody></Table><Pager page={page} size={20} total={meta?.total ?? 0} onPage={setPage} /></Panel></State>{selected && <UserDrawer userId={selected} onClose={() => setSelected(null)} />}</>;
}

function UserDrawer({ userId, onClose }: { userId: string; onClose: () => void }) {
  const query = useLoad<UserDetail>(() => adminApi.userDetail(userId), [userId]); const [life, setLife] = useState<LifeSummary | null>(null);
  return <div className="drawer-mask" onMouseDown={(e) => e.target === e.currentTarget && onClose()}><aside className="drawer"><button className="drawer-close" onClick={onClose}>×</button><State loading={query.loading} error={query.error}>{query.data && <><p className="kicker">PLAYER ARCHIVE</p><h2>{query.data.user.email}</h2><div className="identity-grid"><span>用户 ID<strong>{query.data.user.userId}</strong></span><span>注册时间<strong>{fmt(query.data.user.registeredAt)}</strong></span><span>最近登录<strong>{fmt(query.data.user.lastLoginAt)}</strong></span></div><LifeGroup title="当前命卷" lives={query.data.currentLives} empty="尚未创建当前角色" onSelect={setLife} /><LifeGroup title="前世记录" lives={query.data.historicalLives} empty="暂无重开或死亡记录" onSelect={setLife} /></>}</State></aside>{life && query.data && <TurnDrawer life={life} userId={query.data.user.userId} onClose={() => setLife(null)} />}</div>;
}

function LifeGroup({ title, lives, empty, onSelect }: { title: string; lives: LifeSummary[]; empty: string; onSelect: (life: LifeSummary) => void }) { return <section className="life-group"><h3>{title}<span>{lives.length}</span></h3>{lives.length === 0 ? <p className="empty">{empty}</p> : lives.map((life) => <button className="life-card" key={life.saveId} onClick={() => onSelect(life)}><div><strong>{life.playerName}</strong><span>{life.realm || "尚未入世"} · {difficulty(life.difficulty)}</span></div><div><b>{life.turn} 回</b><small>{fmt(life.at)}</small></div></button>)}</section>; }

function TurnDrawer({ userId, life, onClose }: { userId: string; life: LifeSummary; onClose: () => void }) {
  const [page, setPage] = useState(0);
  const [jump, setJump] = useState("");
  const [selected, setSelected] = useState<TurnRow | null>(null);
  const query = useLoad<TurnPage>(() => adminApi.userTurns(userId, life.saveId, page, 20, jump), [userId, life.saveId, page, jump]);
  return <aside className="drawer drawer-wide">
    <button className="drawer-close" onClick={onClose}>×</button>
    <p className="kicker">LIFE TURNS</p><h2>{life.playerName} · {life.status === "CURRENT" ? "今世" : "前世"}</h2>
    <div className="turn-toolbar"><input inputMode="numeric" placeholder="跳到回合" value={jump} onChange={(e) => { setJump(e.currentTarget.value.replace(/\D/g, "")); setPage(0); }} /><span>共 {query.data?.total ?? 0} 回</span></div>
    <State loading={query.loading} error={query.error}><Table><thead><tr><th>回合</th><th>玩家行动</th><th>输入 Token</th><th>输出 Token</th><th>总 Token</th><th>生成耗时</th><th>发生时间</th><th>状态</th></tr></thead><tbody>{query.data?.rows.map((turn) => <tr className="clickable-row" key={turn.turn} onClick={() => setSelected(turn)}><td>{turn.turn}</td><td className="wide">{turn.action}</td><td>{num(turn.promptTokens)}</td><td>{num(turn.completionTokens)}</td><td>{num(turn.totalTokens)}</td><td>{latency(turn.latency)}</td><td>{fmt(turn.at)}</td><td><Badge value={turn.status} /></td></tr>)}</tbody></Table><Pager page={page} size={20} total={query.data?.total ?? 0} onPage={setPage} /></State>
    {selected && <TurnDetailModal saveId={life.saveId} turn={selected} onClose={() => setSelected(null)} />}
  </aside>;
}

function TurnDetailModal({ saveId, turn, onClose }: { saveId: string; turn: TurnRow; onClose: () => void }) {
  const executions = useLoad<AgentExecution[]>(() => adminApi.executions(saveId, turn.turn), [saveId, turn.turn]);
  return <div className="modal-mask"><section className="modal turn-detail"><button className="drawer-close" onClick={onClose}>×</button><p className="kicker">TURN DETAIL</p><h2>第 {turn.turn} 回详情</h2>
    <div className="turn-detail-grid"><span>生成模型<strong>{turn.model || "未记录"}</strong></span><span>生成耗时<strong>{latency(turn.latency)}</strong></span><span>输入 Token<strong>{num(turn.promptTokens)}</strong></span><span>输出 Token<strong>{num(turn.completionTokens)}</strong></span></div>
    <h3>玩家行动</h3><p className="detail-copy">{turn.action}</p>
    <h3>状态变化</h3><pre className="code-block">{prettyJson(turn.stateDelta)}</pre>
    <h3>生成尝试</h3><State loading={executions.loading} error={executions.error}>{executions.data?.length ? <div className="attempt-list">{executions.data.map((item) => <article key={`${item.turn}-${item.at}-${item.attempt}`}><div><Badge value={item.status} /><strong>第 {item.attempt} 次</strong><span>{item.model || "未记录"} · {latency(item.latency)}</span></div><p>{item.detail || "无异常记录"}</p></article>)}</div> : <p className="empty">该回合没有保留额外生成记录。</p>}</State>
  </section></div>;
}

function Invites() {
  const [status, setStatus] = useState(""); const [count, setCount] = useState(10); const [maxUses, setMaxUses] = useState(1); const [created, setCreated] = useState<string[]>([]); const query = useLoad<InviteCodeItem[]>(() => adminApi.inviteCodes(status), [status]);
  const create = async () => { const result = await adminApi.generateInviteCodes(count, maxUses); setCreated(result.codes); query.reload(); };
  return <><div className="action-bar"><select value={status} onChange={(e) => setStatus(e.currentTarget.value)}><option value="">全部状态</option><option value="ACTIVE">可用</option><option value="EXHAUSTED">已失效</option></select><label>生成数量<input type="number" min="1" max="50" value={count} onChange={(e) => setCount(Number(e.currentTarget.value))} /></label><label>每码次数<input type="number" min="1" max="200" value={maxUses} onChange={(e) => setMaxUses(Number(e.currentTarget.value))} /></label><button className="primary" onClick={create}>生成邀请码</button></div>{created.length > 0 && <Panel title="刚刚生成"><div className="code-grid">{created.map((code) => <button key={code} onClick={() => navigator.clipboard.writeText(code)}>{code}</button>)}</div></Panel>}<State loading={query.loading} error={query.error}><Panel title={`全部邀请码 · ${query.data?.length ?? 0}`}><Table><thead><tr><th>邀请码</th><th>状态</th><th>使用情况</th><th>创建时间</th></tr></thead><tbody>{query.data?.map((code) => <tr key={code.id}><td>{code.codeDisplay || "历史码（无明文）"}</td><td><Badge value={code.status} /></td><td>{code.usedCount} / {code.maxUses}</td><td>{fmt(code.createdAt)}</td></tr>)}</tbody></Table></Panel></State></>;
}

function Releases() {
  const query = useLoad<ReleaseNoteAdmin[]>(() => adminApi.releaseNotes(), []); const [editing, setEditing] = useState<Partial<ReleaseNoteAdmin> | null>(null);
  return <><div className="page-actions"><p>发布后可选择是否在所有玩家下次进入时展示一次。</p><button className="primary" onClick={() => setEditing({ version: "V0.11", title: "", content: "", status: "DRAFT", announceAll: false })}>新建版本公告</button></div><State loading={query.loading} error={query.error}><div className="release-grid">{query.data?.map((note) => <article className="release-card" key={note.id}><div><Badge value={note.status} /><span>{note.version}</span></div><h2>{note.title}</h2><p>{note.content}</p><footer><span>{note.announceAll ? `全员公告 · ${note.readCount} 人已读` : "仅版本记录"}</span><button onClick={() => setEditing(note)}>编辑</button></footer></article>)}</div></State>{editing && <ReleaseEditor note={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); query.reload(); }} />}</>;
}

function ReleaseEditor({ note, onClose, onSaved }: { note: Partial<ReleaseNoteAdmin>; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState(note);
  const [busy, setBusy] = useState(false);
  const save = async (status: ReleaseNoteAdmin["status"]) => { setBusy(true); try { await adminApi.saveReleaseNote({ ...form, status }); onSaved(); } finally { setBusy(false); } };
  return <div className="modal-mask"><section className="modal"><button className="drawer-close" onClick={onClose}>×</button><p className="kicker">RELEASE NOTE</p><h2>版本公告</h2><div className="form-grid"><label>版本<input value={form.version ?? ""} onChange={(e) => setForm({ ...form, version: e.currentTarget.value })} /></label><label>当前状态<input disabled value={form.status === "PUBLISHED" ? "已发布" : form.status === "ARCHIVED" ? "已归档" : "草稿"} /></label><label className="full">标题<input value={form.title ?? ""} onChange={(e) => setForm({ ...form, title: e.currentTarget.value })} /></label><label className="full">更新内容<textarea rows={9} value={form.content ?? ""} onChange={(e) => setForm({ ...form, content: e.currentTarget.value })} /></label><label className="check full"><input type="checkbox" checked={form.announceAll ?? false} onChange={(e) => setForm({ ...form, announceAll: e.currentTarget.checked })} />发布后向全部玩家公告一次</label>{form.announceAll && form.status !== "PUBLISHED" && <p className="release-warning full">当前仍是草稿。只有点击“发布并全员公告”后，玩家端才会弹出。</p>}</div><div className="modal-actions"><button className="secondary" disabled={busy} onClick={() => void save("DRAFT")}>保存草稿</button>{note.id && <button className="danger-link" disabled={busy} onClick={() => void save("ARCHIVED")}>归档并撤下</button>}<button className="primary" disabled={busy} onClick={() => void save("PUBLISHED")}>{busy ? "正在保存…" : form.announceAll ? "发布并全员公告" : "发布版本记录"}</button></div></section></div>;
}

function Models() {
  const query = useLoad<ModelConfig[]>(() => adminApi.modelConfigs(), []); const [keys, setKeys] = useState<Record<string, string>>({}); const update = async (model: ModelConfig) => { await adminApi.updateModelConfig(model.modelCode, true, keys[model.modelCode] || ""); setKeys({ ...keys, [model.modelCode]: "" }); query.reload(); };
  return <State loading={query.loading} error={query.error}><div className="model-grid">{query.data?.map((model) => <article className={model.active ? "model-card active" : "model-card"} key={model.modelCode}><div><p className="kicker">GENERATION MODEL</p><h2>{model.displayName}</h2><Badge value={model.active ? "ACTIVE_MODEL" : "STANDBY"} /></div><p>{model.modelCode.includes("pro") ? "长篇叙事与复杂裁决优先，适合作为默认模型。" : "响应更快，适合需要降低等待时间的试玩阶段。"}</p><label>对应密钥<input type="password" placeholder={model.keyConfigured ? "已单独配置，留空则不更换" : "留空时使用服务端默认密钥"} value={keys[model.modelCode] || ""} onChange={(e) => setKeys({ ...keys, [model.modelCode]: e.currentTarget.value })} /></label><button className={model.active ? "secondary" : "primary"} onClick={() => update(model)}>{model.active ? "保存密钥" : "切换到此模型"}</button><small>切换只影响之后的新请求，进行中的章节不会被打断。</small></article>)}</div></State>;
}

function Logs() { const query = useLoad(() => adminApi.operationLogs(), []); return <State loading={query.loading} error={query.error}><Panel title="管理操作记录"><Table><thead><tr><th>操作</th><th>内容</th><th>时间</th></tr></thead><tbody>{query.data?.map((log, index) => <tr key={`${log.at}-${index}`}><td>{log.operation}</td><td className="wide">{log.detail}</td><td>{fmt(log.at)}</td></tr>)}</tbody></Table></Panel></State>; }

function Panel({ title, children }: { title: string; children: ReactNode }) { return <section className="panel"><header><h2>{title}</h2></header>{children}</section>; }
function Table({ children }: { children: ReactNode }) { return <div className="table-scroll"><table>{children}</table></div>; }
function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (page: number) => void }) { return <div className="pager"><button disabled={page <= 0} onClick={() => onPage(page - 1)}>上一页</button><span>第 {page + 1} / {Math.max(1, Math.ceil(total / size))} 页</span><button disabled={(page + 1) * size >= total} onClick={() => onPage(page + 1)}>下一页</button></div>; }
function Badge({ value }: { value: string }) { const labels: Record<string, string> = { ACTIVE: "可用", EXHAUSTED: "已失效", SUCCESS: "成功", FAILED: "失败", NONE: "尚未创建", CURRENT: "当前角色", RESTARTED: "仅有前世", DRAFT: "草稿", PUBLISHED: "已发布", ARCHIVED: "已归档", ACTIVE_MODEL: "当前使用", STANDBY: "备用" }; return <span className={`badge badge-${value.toLowerCase()}`}>{labels[value] ?? value}</span>; }
function prettyJson(value: string) { if (!value) return "本回合未记录结构化变化。"; try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
