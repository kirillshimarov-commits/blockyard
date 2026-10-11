import { useState } from "react";
import { api, errorText, Instance, LinkInfo, TunnelStatus } from "../api";
import { Icon } from "./Icon";

interface Props {
  instance: Instance;
  running: boolean;
  tunnel: TunnelStatus;
  onTunnelChanged: () => void;
  notify: (text: string, error?: boolean) => void;
}

export function linkLabel(link: LinkInfo): string {
  const ping = link.rttMs != null ? `${link.rttMs} мс` : "…";
  return link.relayed ? `${ping} · через сервер` : `${ping} · напрямую`;
}

/** Host side of "play with a friend": shown while the game runs. */
export default function FriendPanel({ instance, running, tunnel, onTunnelChanged, notify }: Props) {
  const [busy, setBusy] = useState(false);
  const lanPort = tunnel.lanPorts[instance.id];
  const host = tunnel.host?.instanceId === instance.id ? tunnel.host : null;

  if (!running) return null;

  async function invite() {
    setBusy(true);
    try {
      const code = await api.tunnelInvite(instance.id);
      await navigator.clipboard.writeText(code).catch(() => {});
      notify("Код-приглашение скопирован — отправьте его другу");
      onTunnelChanged();
    } catch (e) {
      notify(errorText(e), true);
    } finally {
      setBusy(false);
    }
  }

  async function stop() {
    await api.tunnelStopHost().catch(() => {});
    onTunnelChanged();
  }

  if (!lanPort) {
    return (
      <div className="friend-panel muted">
        <Icon name="users" />
        <span>
          Чтобы позвать друга: в игре нажмите <b>Esc → «Открыть для сети»</b>, и здесь появится кнопка приглашения.
        </span>
      </div>
    );
  }

  if (!host) {
    return (
      <div className="friend-panel">
        <Icon name="users" />
        <span className="grow">Мир открыт для сети. Позовите друга — ему понадобится только код.</span>
        <button className="btn primary" disabled={busy} onClick={invite}>
          {busy ? <span className="spinner" /> : <Icon name="share" />} Пригласить друга
        </button>
      </div>
    );
  }

  return (
    <div className="friend-panel column">
      <div className="row between">
        <strong>
          <Icon name="users" /> Друзья могут подключаться
        </strong>
        <button className="btn small-btn" onClick={stop}>
          Остановить
        </button>
      </div>
      <div className="invite-code">
        <code className="truncate">{host.invite}</code>
        <button
          className="btn small-btn"
          onClick={() => navigator.clipboard.writeText(host.invite).then(() => notify("Код скопирован"))}
        >
          <Icon name="copy" size={14} /> Копировать
        </button>
      </div>
      <p className="muted small">
        Друг вставляет код в Blockyard → «Подключиться к другу». Код действует, пока открыт мир.
      </p>
      {host.peers.length > 0 ? (
        <div className="peers">
          {host.peers.map((p) => (
            <span key={p.id} className="tag">
              <span className="instance-dot" data-state="running" /> Игрок {p.id.slice(0, 4)} · {linkLabel(p)}
            </span>
          ))}
        </div>
      ) : (
        <p className="muted small">Пока никто не подключился.</p>
      )}
    </div>
  );
}
