import { GearIcon, HomeIcon, PersonIcon, ReaderIcon } from "@radix-ui/react-icons";

export type TabId = "adventure" | "character" | "chronicle" | "settings";
export type AppStage = "loading" | "login" | "saves" | "create" | "game";

export function TabIcon({ id }: { id: TabId }) {
  const icons = { adventure: HomeIcon, character: PersonIcon, chronicle: ReaderIcon, settings: GearIcon };
  const Icon = icons[id];
  return <Icon aria-hidden="true" />;
}
export function LoadingView() {
  return (
    <section className="entry-card loading-card" role="status">
      <span className="ink-spinner" />
      <h1>正在检索命卷</h1>
      <p>正在核对身份与命卷归属，已有经历不会因此改变。</p>
    </section>
  );
}

export function DestinyLoadingOverlay({ name }: { name?: string }) {
  const playerName = name?.trim() || "此身";
  return <div className="destiny-loading" role="status"><div className="destiny-loading-panel"><p className="destiny-loading-kicker">三 · 启其卷</p><div className="destiny-name-seal" aria-hidden="true">{playerName.slice(0, 1)}</div><strong>正在开启{playerName}的命卷</strong><small>正在写下此卷开篇。完成前，不会留下未完成的内容。</small><div className="loading-ink-line"><i /></div></div></div>;
}
