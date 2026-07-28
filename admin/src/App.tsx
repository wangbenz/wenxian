import "./App.css";
import { useEffect, useState, type FormEvent } from "react";
import {
  adminApi,
  clearToken,
  isAuthenticated,
  setToken,
  type DashboardData,
  type InviteCodeItem,
  type UserDetail,
} from "./admin-api";

type Page = "dashboard" | "invite-codes" | "users";

function formatTime(iso: string) {
  if (!iso) return "-";
  const d = new Date(iso);
  return d.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function formatDateTime(iso: string) {
  if (!iso) return "-";
  return new Date(iso).toLocaleString("zh-CN");
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, [string, string]> = {
    ACTIVE: ["可用", "badge-green"],
    EXHAUSTED: ["已用尽", "badge-gray"],
    EXPIRED: ["已过期", "badge-red"],
    SUCCESS: ["成功", "badge-green"],
    FAILED: ["失败", "badge-red"],
  };
  const [label, cls] = map[status] ?? [status, "badge-gray"];
  return <span className={`badge ${cls}`}>{label}</span>;
}

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState("");
  const [loginBusy, setLoginBusy] = useState(false);

  const login = async (e: FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) return;
    setLoginBusy(true);
    setLoginError("");
    try {
      const res = await adminApi.login(username.trim(), password);
      setToken(res.token);
      setAuthed(true);
    } catch (err) {
      setLoginError(err instanceof Error ? err.message : "登录失败");
    } finally {
      setLoginBusy(false);
    }
  };

  const logout = () => {
    clearToken();
    setAuthed(false);
    setUsername("");
    setPassword("");
  };

  if (!authed) {
    return (
      <div className="login-shell">
        <form className="login-card" onSubmit={login}>
          <h1>问仙录 · 管理台</h1>
          <p>输入账号密码以继续</p>
          <label>
            <span>账号</span>
            <input
              autoFocus
              autoComplete="username"
              onChange={(e) => setUsername(e.currentTarget.value)}
              placeholder="管理员账号"
              value={username}
            />
          </label>
          <label>
            <span>密码</span>
            <input
              autoComplete="current-password"
              onChange={(e) => setPassword(e.currentTarget.value)}
              placeholder="密码"
              type="password"
              value={password}
            />
          </label>
          <button disabled={loginBusy || !username.trim() || !password.trim()} type="submit">
            {loginBusy ? "验证中…" : "进入管理台"}
          </button>
          {loginError && <p className="login-error">{loginError}</p>}
        </form>
      </div>
    );
  }

  return <AdminShell onLogout={logout} />;
}

function AdminShell({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("dashboard");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [showPwdModal, setShowPwdModal] = useState(false);

  const navItems: Array<[Page, string, string]> = [
    ["dashboard", "仪表盘", "DASHBOARD"],
    ["invite-codes", "内测码", "INVITE CODES"],
    ["users", "玩家", "USERS"],
  ];

  const closeSidebar = () => setSidebarOpen(false);

  return (
    <div className="admin-shell">
      <aside className={`sidebar${sidebarOpen ? " open" : ""}`}>
        <div className="sidebar-brand">
          <strong>问仙录</strong>
          <span>管理台</span>
        </div>
        <nav className="sidebar-nav">
          {navItems.map(([id, label, sub]) => (
            <button
              aria-current={page === id ? "page" : undefined}
              key={id}
              onClick={() => { setPage(id); closeSidebar(); }}
            >
              <span>{sub}</span>
              {label}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <button className="sidebar-action" onClick={() => setShowPwdModal(true)}>修改密码</button>
          <button className="sidebar-logout" onClick={onLogout}>退出</button>
        </div>
      </aside>
      {sidebarOpen && <div className="sidebar-overlay" onClick={closeSidebar} />}
      <div className="admin-main">
        <header className="admin-header">
          <button
            aria-label="展开菜单"
            className="menu-toggle"
            onClick={() => setSidebarOpen((v) => !v)}
          >
            <span /><span /><span />
          </button>
          <h1>{navItems.find(([id]) => id === page)?.[1]}</h1>
        </header>
        <main className="admin-content">
          {page === "dashboard" && <DashboardPage />}
          {page === "invite-codes" && <InviteCodesPage />}
          {page === "users" && <UsersPage />}
        </main>
      </div>
      {showPwdModal && <ChangePasswordModal onClose={() => setShowPwdModal(false)} />}
    </div>
  );
}

function ChangePasswordModal({ onClose }: { onClose: () => void }) {
  const [oldPwd, setOldPwd] = useState("");
  const [newPwd, setNewPwd] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!oldPwd || !newPwd) return;
    setBusy(true);
    setError("");
    try {
      await adminApi.changePassword(oldPwd, newPwd);
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "修改失败");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal">
        {done ? (
          <>
            <h2>密码已修改</h2>
            <p className="gen-warning">所有会话已失效，下次请用新密码登录。</p>
            <div className="modal-actions">
              <button className="btn-primary" onClick={onClose}>关闭</button>
            </div>
          </>
        ) : (
          <form onSubmit={submit}>
            <h2>修改密码</h2>
            <label>
              <span>当前密码</span>
              <input autoFocus onChange={(e) => setOldPwd(e.currentTarget.value)} type="password" value={oldPwd} />
            </label>
            <label>
              <span>新密码（至少 8 位）</span>
              <input onChange={(e) => setNewPwd(e.currentTarget.value)} type="password" value={newPwd} />
            </label>
            {error && <p className="gen-error">{error}</p>}
            <div className="modal-actions">
              <button className="btn-primary" disabled={busy || !oldPwd || !newPwd} type="submit">
                {busy ? "处理中…" : "确认修改"}
              </button>
              <button className="btn-secondary" disabled={busy} onClick={onClose} type="button">取消</button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

function useFetch<T>(fetcher: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const reload = () => {
    setLoading(true);
    setError("");
    fetcher()
      .then(setData)
      .catch((e) => setError(e.message || "加载失败"))
      .finally(() => setLoading(false));
  };

  useEffect(() => { reload(); }, deps);

  return { data, error, loading, reload };
}

function DashboardPage() {
  const { data, loading, error } = useFetch(() => adminApi.dashboard(), []);

  if (loading) return <div className="loading">加载中…</div>;
  if (error) return <div className="error-msg">{error}</div>;
  if (!data) return null;

  return (
    <div className="dashboard">
      <div className="stat-grid">
        <StatCard label="总用户数" value={data.totalUsers} />
        <StatCard label="在线人数" value={data.onlineUsers} sub="有效会话" />
        <StatCard label="总存档" value={data.totalSaves} />
        <StatCard label="总回合数" value={data.totalTurns} />
        <StatCard label="今日回合" value={data.todayTurns} />
        <StatCard label="平均延迟" value={`${data.avgLatency}ms`} />
      </div>
      <section className="card">
        <div className="card-header">
          <h2>最近回合活动</h2>
          <span className="card-meta">{data.totalTokens.toLocaleString()} Tokens 累计消耗</span>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>玩家</th>
                <th>境界</th>
                <th>回合</th>
                <th>行动</th>
                <th>Tokens</th>
                <th>延迟</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {data.recentTurns.map((t, i) => (
                <tr key={i}>
                  <td className="td-name">{t.playerName}</td>
                  <td>{t.realm || "-"}</td>
                  <td>{t.turn}</td>
                  <td className="td-action" title={t.action}>{t.action}</td>
                  <td>{t.tokens}</td>
                  <td>{t.latency}ms</td>
                  <td className="td-time">{formatTime(t.at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function StatCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div className="stat-card">
      <span className="stat-label">{label}</span>
      <strong className="stat-value">{value}</strong>
      {sub && <small className="stat-sub">{sub}</small>}
    </div>
  );
}

function InviteCodesPage() {
  const [statusFilter, setStatusFilter] = useState("");
  const { data, loading, error, reload } = useFetch(() => adminApi.inviteCodes(statusFilter), [statusFilter]);
  const [showGenerate, setShowGenerate] = useState(false);
  const [genCount, setGenCount] = useState(5);
  const [genMax, setGenMax] = useState(20);
  const [genBusy, setGenBusy] = useState(false);
  const [genResult, setGenResult] = useState<string[] | null>(null);
  const [genError, setGenError] = useState("");

  const handleGenerate = async (e: FormEvent) => {
    e.preventDefault();
    setGenBusy(true);
    setGenError("");
    try {
      const res = await adminApi.generateInviteCodes(genCount, genMax);
      setGenResult(res.codes);
      reload();
    } catch (err) {
      setGenError(err instanceof Error ? err.message : "生成失败");
    } finally {
      setGenBusy(false);
    }
  };

  const closeModal = () => {
    setShowGenerate(false);
    setGenResult(null);
    setGenError("");
  };

  return (
    <div className="page">
      <div className="page-toolbar">
        <select className="search-input" onChange={(e) => setStatusFilter(e.target.value)} style={{width: "auto"}} value={statusFilter}>
          <option value="">全部状态</option>
          <option value="ACTIVE">可用</option>
          <option value="EXHAUSTED">已用尽</option>
        </select>
        <button className="btn-primary" onClick={() => setShowGenerate(true)}>+ 批量生成</button>
      </div>
      {loading && <div className="loading">加载中…</div>}
      {error && <div className="error-msg">{error}</div>}
      {data && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>内测码</th>
                  <th>状态</th>
                  <th>已用 / 上限</th>
                  <th>过期时间</th>
                  <th>创建时间</th>
                </tr>
              </thead>
              <tbody>
                {data.map((c) => (
                  <tr key={c.id}>
                    <td className="td-mono">
                      {c.plaintextAvailable && c.codeDisplay
                        ? c.codeDisplay
                        : <span className="code-unavailable" title="旧邀请码仅保存哈希，无法恢复原始明文">明文不可恢复</span>}
                    </td>
                    <td><StatusBadge status={c.status} /></td>
                    <td>{c.usedCount} / {c.maxUses}</td>
                    <td>{c.expiresAt ? formatDateTime(c.expiresAt) : "永不过期"}</td>
                    <td className="td-time">{formatDateTime(c.createdAt)}</td>
                  </tr>
                ))}
                {data.length === 0 && (
                  <tr><td colSpan={5} className="td-empty">暂无内测码</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {showGenerate && (
        <div className="modal-backdrop" onClick={(e) => { if (e.target === e.currentTarget) closeModal(); }}>
          <div className="modal">
            <h2>批量生成内测码</h2>
            {genResult ? (
              <div>
                <p className="gen-warning">以下内测码仅显示一次，请立即复制保存：</p>
                <textarea className="gen-output" readOnly rows={Math.min(genResult.length, 12)} value={genResult.join("\n")} />
                <div className="modal-actions">
                  <button className="btn-primary" onClick={() => { navigator.clipboard.writeText(genResult.join("\n")); }}>
                    复制全部
                  </button>
                  <button className="btn-secondary" onClick={closeModal}>关闭</button>
                </div>
              </div>
            ) : (
              <form onSubmit={handleGenerate}>
                <label>
                  <span>数量</span>
                  <input max={50} min={1} onChange={(e) => setGenCount(Number(e.target.value))} type="number" value={genCount} />
                </label>
                <label>
                  <span>每个码可用次数</span>
                  <input max={200} min={1} onChange={(e) => setGenMax(Number(e.target.value))} type="number" value={genMax} />
                </label>
                {genError && <p className="gen-error">{genError}</p>}
                <div className="modal-actions">
                  <button className="btn-primary" disabled={genBusy} type="submit">
                    {genBusy ? "生成中…" : `生成 ${genCount} 个内测码`}
                  </button>
                  <button className="btn-secondary" disabled={genBusy} onClick={closeModal} type="button">取消</button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function UsersPage() {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const { data, loading, error, reload } = useFetch(
    () => adminApi.users(search, page, 20),
    [search, page]
  );
  const [detailId, setDetailId] = useState<string | null>(null);

  const [meta, rowsObj] = data ?? [{ total: 0, page: 0, size: 20 }, { rows: [] }];
  const rows = rowsObj?.rows ?? [];
  const totalPages = Math.max(1, Math.ceil(meta.total / meta.size));

  return (
    <div className="page">
      <div className="page-toolbar">
        <input
          className="search-input"
          onChange={(e) => { setSearch(e.target.value); setPage(0); }}
          placeholder="搜索邮箱…"
          type="text"
          value={search}
        />
        <span className="page-meta">共 {meta.total} 人</span>
      </div>
      {loading && <div className="loading">加载中…</div>}
      {error && <div className="error-msg">{error}</div>}
      {data && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>邮箱</th>
                  <th>角色名</th>
                  <th>种族</th>
                  <th>境界</th>
                  <th>回合</th>
                  <th>Tokens</th>
                  <th>最近活跃</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((u) => (
                  <tr key={u.userId}>
                    <td className="td-email" title={u.rawEmail}>{u.email}</td>
                    <td className="td-name">{u.playerName || "-"}</td>
                    <td>{u.race || "-"}</td>
                    <td>{u.realm || "-"}</td>
                    <td>{u.turnCount}</td>
                    <td>{u.totalTokens.toLocaleString()}</td>
                    <td className="td-time">{u.lastLoginAt ? formatTime(u.lastLoginAt) : "-"}</td>
                    <td>
                      <button
                        className="btn-link"
                        onClick={() => setDetailId(detailId === u.userId ? null : u.userId)}
                      >
                        {detailId === u.userId ? "收起" : "详情"}
                      </button>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr><td colSpan={8} className="td-empty">暂无玩家数据</td></tr>
                )}
              </tbody>
            </table>
          </div>
          {totalPages > 1 && (
            <div className="pagination">
              <button disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>上一页</button>
              <span>{page + 1} / {totalPages}</span>
              <button disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>下一页</button>
            </div>
          )}
        </div>
      )}
      {detailId && <UserDetailPanel userId={detailId} onClose={() => setDetailId(null)} />}
    </div>
  );
}

function UserDetailPanel({ userId, onClose }: { userId: string; onClose: () => void }) {
  const { data, loading, error } = useFetch(() => adminApi.userDetail(userId), [userId]);

  return (
    <div className="drawer-backdrop" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <aside className="drawer">
        <div className="drawer-header">
          <h2>玩家详情</h2>
          <button className="btn-link" onClick={onClose}>关闭</button>
        </div>
        {loading && <div className="loading">加载中…</div>}
        {error && <div className="error-msg">{error}</div>}
        {data && (
          <div className="drawer-body">
            <dl className="detail-grid">
              <div><dt>邮箱</dt><dd>{data.user.email}</dd></div>
              <div><dt>状态</dt><dd><StatusBadge status={data.user.status} /></dd></div>
              <div><dt>注册时间</dt><dd>{formatDateTime(data.user.registeredAt)}</dd></div>
              <div><dt>最近登录</dt><dd>{data.user.lastLoginAt ? formatDateTime(data.user.lastLoginAt) : "-"}</dd></div>
            </dl>
            <section>
              <h3>回合历史 ({data.turns.length})</h3>
              <div className="table-wrap">
                <table className="table-sm">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>行动</th>
                      <th>Prompt</th>
                      <th>Comp.</th>
                      <th>延迟</th>
                      <th>时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.turns.map((t) => (
                      <tr key={t.turn}>
                        <td>{t.turn}</td>
                        <td className="td-action" title={t.action}>{t.action}</td>
                        <td>{t.promptTokens}</td>
                        <td>{t.completionTokens}</td>
                        <td>{t.latency}ms</td>
                        <td className="td-time">{formatTime(t.at)}</td>
                      </tr>
                    ))}
                    {data.turns.length === 0 && <tr><td colSpan={6} className="td-empty">无回合记录</td></tr>}
                  </tbody>
                </table>
              </div>
            </section>
            <section>
              <h3>Agent 执行日志 ({data.executions.length})</h3>
              <div className="table-wrap">
                <table className="table-sm">
                  <thead>
                    <tr>
                      <th>回合</th>
                      <th>尝试</th>
                      <th>状态</th>
                      <th>备注</th>
                      <th>延迟</th>
                      <th>时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.executions.map((e, i) => (
                      <tr key={i}>
                        <td>{e.turn}</td>
                        <td>{e.attempt}</td>
                        <td><StatusBadge status={e.status} /></td>
                        <td className="td-action" title={e.detail}>{e.detail}</td>
                        <td>{e.latency}ms</td>
                        <td className="td-time">{formatTime(e.at)}</td>
                      </tr>
                    ))}
                    {data.executions.length === 0 && <tr><td colSpan={6} className="td-empty">无执行记录</td></tr>}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        )}
      </aside>
    </div>
  );
}
