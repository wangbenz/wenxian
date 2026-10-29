import { useEffect, useRef, useState } from "react";
import type { AccessStatus, AuthResponse, ReleaseNote } from "../game-api";
import { LoginView } from "../features/auth/AuthViews";
import { LoadingView } from "../app/AppViews";
import { ReleaseAnnouncementDialog } from "../features/settings/SettingsViews";
import type { UiProblem } from "../shared/problem";
import { accountRequest, RuntimeError } from "./runtime-api";
import RuntimeApp from "./RuntimeApp";

export type RuntimeAccount = {
  userId: string; email: string;
  onLogout: () => Promise<void>;
  onPassword: (currentPassword: string, newPassword: string) => Promise<void>;
};

function problem(error: unknown): UiProblem {
  return { category: "SYSTEM", title: "暂时无法进入", message: error instanceof RuntimeError ? error.message : "连接暂时中断，请稍后重试。", suggestions: [], retryable: true, turnConsumed: false, stateChanged: false };
}

/** 独立账号入口。原登录组件与页面设计不变，账号凭据只由浏览器 Cookie 管理。 */
export default function AccountRuntimeApp() {
  const [auth, setAuth] = useState<AuthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiProblem | null>(null);
  const [access, setAccess] = useState<AccessStatus | null>(null);
  const [announcement, setAnnouncement] = useState<ReleaseNote | null>(null);
  const [pendingRelease, setPendingRelease] = useState<{ userId: string; note: ReleaseNote } | null>(null);
  const authEpoch = useRef(0);
  useEffect(() => {
    let current = true;
    const epoch = ++authEpoch.current;
    accountRequest<AuthResponse>("/api/v1/auth/me").then(value => { if (current && epoch === authEpoch.current) setAuth(value); }).catch(failure => { if (current) setError(problem(failure)); }).finally(() => { if (current) setLoading(false); });
    accountRequest<AccessStatus>("/api/v1/auth/access-status").then(value => { if (current) setAccess(value); }).catch(() => {});
    accountRequest<ReleaseNote | null>("/api/v1/release-notes/public-latest").then(value => { if (current) setAnnouncement(value); }).catch(() => {});
    const expired = () => { ++authEpoch.current; setAuth(null); setError(problem(new RuntimeError(401, "UNAUTHORIZED", "登录已失效，请重新登录；原行动与草稿已保留。"))); };
    window.addEventListener("wenxian:runtime:account-expired", expired);
    return () => { current = false; window.removeEventListener("wenxian:runtime:account-expired", expired); };
  }, []);
  useEffect(() => {
    let current = true;
    const userId = auth?.user?.userId;
    setPendingRelease(null);
    if (auth?.authenticated && userId) accountRequest<ReleaseNote | null>("/api/v1/release-notes/pending")
      .then(note => { if (current && note) setPendingRelease({ userId, note }); }).catch(() => {});
    return () => { current = false; };
  }, [auth?.authenticated, auth?.user?.userId]);
  async function authenticate(mode: "login" | "register", email: string, password: string) {
    if (busy) return;
    const epoch = ++authEpoch.current;
    setBusy(true); setError(null);
    try {
      const value = await accountRequest<AuthResponse>(`/api/v1/auth/${mode}`, { email, password });
      if (epoch === authEpoch.current) setAuth(value);
    } catch (failure) { if (epoch === authEpoch.current) setError(problem(failure)); }
    finally { setBusy(false); }
  }
  async function logout() {
    await accountRequest<void>("/api/v1/auth/logout", {});
    ++authEpoch.current; setAuth(null); setError(null);
  }
  async function password(currentPassword: string, newPassword: string) {
    const value = await accountRequest<AuthResponse>("/api/v1/auth/password", { currentPassword, newPassword });
    setAuth(value);
  }
  if (auth?.authenticated && auth.user) return <><RuntimeApp key={auth.user.userId} account={{ ...auth.user, onLogout: logout, onPassword: password }} />
    {pendingRelease?.userId === auth.user.userId && <ReleaseAnnouncementDialog note={pendingRelease.note} onClose={() => {
      const { note } = pendingRelease;
      setPendingRelease(null);
      void accountRequest<void>(`/api/v1/release-notes/${encodeURIComponent(note.id)}/read`, {})
        .catch(() => { /* 未写成的回执下次登录仍会展示，不伪造已读记录。 */ });
    }} />}</>;
  return <div className="game-shell" data-stage="login" data-theme="paper" data-entry data-font-size="standard">
    <div className="paper-background" aria-hidden="true" />
    <div className="game-scroll"><main className="screen-content" data-testid="game-content">
      {loading ? <LoadingView /> : <LoginView isBusy={busy} error={error} offline={false} announcement={announcement} accessStatus={access} onAuthenticate={(mode, email, password) => void authenticate(mode, email, password)} />}
    </main></div>
  </div>;
}
