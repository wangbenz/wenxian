import { useEffect } from "react";
import { ReaderIcon, ReloadIcon } from "@radix-ui/react-icons";
import type { PlayerStoryView } from "../../app/player-read-model";
import { formatWorldTime } from "../../shared/world-presentation";

export function DeathView({
  game,
  isBusy,
  onChronicle,
  onRebirth,
  onLogout,
}: {
  game: PlayerStoryView;
  isBusy: boolean;
  onChronicle: () => void;
  onRebirth: () => void;
  onLogout: () => void;
}) {
  const player = game.player;
  const finalEvent = game.events[0];
  return (
    <div className="death-view">
      <section className="death-card">
        <div className="death-seal" aria-hidden="true">终</div>
        <h1>此世已终</h1>
        <p className="death-name">{player.name} · {player.realm}</p>
        <div className="death-divider" aria-hidden="true"><span /></div>
        <blockquote>
          <strong>{finalEvent?.title ?? "命数已尽"}</strong>
          <p>{finalEvent?.detail ?? "这段人生已经走到尽头。"}</p>
        </blockquote>
        <dl className="death-stats">
          <div><dt>终局之地</dt><dd>{game.currentLocation}</dd></div>
          <div><dt>历经回合</dt><dd>{game.turn}</dd></div>
          <div><dt>终局时刻</dt><dd>{formatWorldTime(game.worldTime)}</dd></div>
        </dl>
        <p className="death-rule">死亡不会自动回档。转世将开启全新的命卷，本世则封存为前世记录。</p>
        <div className="death-actions">
          <button className="primary-entry-button" disabled={isBusy} onClick={onChronicle} type="button"><ReaderIcon />查看一生纪事</button>
          <button className="rebirth-button" disabled={isBusy} onClick={onRebirth} type="button"><ReloadIcon />转世重开</button>
        </div>
        <button className="text-button" disabled={isBusy} onClick={onLogout} type="button">退出当前邮箱</button>
      </section>
    </div>
  );
}
export function ResetConfirmDialog({
  game,
  intent,
  isBusy,
  onCancel,
  onConfirm,
}: {
  game: PlayerStoryView;
  intent: "RESET";
  isBusy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const isDead = game.phase === "dead";
  const finalEvent = game.events[0];
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !isBusy) onCancel();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [isBusy, onCancel]);

  return (
    <div
      className="ink-dialog-backdrop"
      onMouseDown={(event) => {
        if (event.currentTarget === event.target && !isBusy) onCancel();
      }}
      role="presentation"
    >
      <section aria-describedby="reset-dialog-description" aria-labelledby="reset-dialog-title" aria-modal="true" className="ink-dialog" role="dialog">
        <div className="ink-dialog-seal" aria-hidden="true">{isDead ? "转" : "慎"}</div>
        <p className="eyebrow">{isDead ? "封存此世" : "重开此世"}</p>
        <h2 id="reset-dialog-title">{isDead ? "封存此世" : "重开命卷"}</h2>
        <p id="reset-dialog-description">
          {isDead
            ? `${game.player.name}的这一生将完整封存为前世记录，随后开启一张没有角色与剧情的新命卷。`
            : "当前命卷会被舍弃，已有角色、物品与纪事将无法恢复。"}
        </p>
        <div className="ink-dialog-note">
          <span>{isDead ? "本世归档" : "当前存档"}</span>
          <strong>{isDead ? `${finalEvent?.title ?? "命数已尽"} · ${game.turn}回合` : "删除后不可恢复"}</strong>
        </div>
        <div className="ink-dialog-actions">
          <button autoFocus className="ink-dialog-cancel" disabled={isBusy} onClick={onCancel} type="button">暂不重开</button>
          <button className="ink-dialog-confirm destructive" disabled={isBusy} onClick={onConfirm} type="button">
            <ReloadIcon />{isBusy ? "正在处理…" : isDead ? "封存并转世" : "确认重开"}
          </button>
        </div>
      </section>
    </div>
  );
}
