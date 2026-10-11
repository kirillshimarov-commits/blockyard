import { useCallback, useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { Account, api, ContentKind, LauncherUpdate, TunnelStatus, errorText, GameState, Instance, LogEvent, ProgressEvent, Settings, StateEvent } from "./api";
import Library from "./pages/Library";
import Home from "./pages/Home";
import SpaceScene from "./components/SpaceScene";
import Splash from "./components/Splash";
import LaunchOverlay from "./components/LaunchOverlay";
import InstanceView from "./pages/InstanceView";
import Browse from "./pages/Browse";
import SettingsPage from "./pages/Settings";
import { Icon, IconName } from "./components/Icon";
import JoinFriendModal from "./components/JoinFriendModal";
import { linkLabel } from "./components/FriendPanel";
import {
  Appearance,
  applyAppearance,
  applyWindowStyle,
  DEFAULT_APPEARANCE,
  loadAppearance,
  saveAppearance,
} from "./appearance";

export type View =
  | { kind: "library" }
  | { kind: "instance"; id: string }
  | { kind: "browse"; content: ContentKind; instanceId?: string }
  | { kind: "settings" };

export interface GameStatus {
  state: GameState;
  progress?: ProgressEvent;
  error?: string;
  crashReport?: string;
  hints?: string[];
}

const MAX_LOG_LINES = 3000;

function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 10_000);
    return () => window.clearInterval(t);
  }, []);
  const time = now.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  const day = now.toLocaleDateString("ru-RU", { weekday: "long" });
  return (
    <span className="clock" title={now.toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" })}>
      {time}
      <span className="clock-day">, {day.charAt(0).toUpperCase() + day.slice(1)}</span>
    </span>
  );
}

export default function App() {
  const [view, setView] = useState<View>({ kind: "library" });
  const [instances, setInstances] = useState<Instance[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [status, setStatus] = useState<Record<string, GameStatus>>({});
  const [logs, setLogs] = useState<Record<string, string[]>>({});
  const [toast, setToast] = useState<{ text: string; error?: boolean } | null>(null);
  const [appearance, setAppearance] = useState<Appearance>(DEFAULT_APPEARANCE);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [launcherUpdate, setLauncherUpdate] = useState<LauncherUpdate | null>(null);
  const [tunnel, setTunnel] = useState<TunnelStatus>({ lanPorts: {} });
  const [joining, setJoining] = useState(false);
  const [launching, setLaunching] = useState<string | null>(null);
  /** null until the appearance is loaded, so the intro does not flash for people who turned it off. */
  const [splash, setSplash] = useState<boolean | null>(null);
  const hideSplash = useCallback(() => setSplash(false), []);
  const hideLaunch = useCallback(() => setLaunching(null), []);

  const refreshTunnel = useCallback(() => {
    api.tunnelStatus().then(setTunnel).catch(() => {});
  }, []);

  // Keep ping and connected friends fresh while a tunnel is active.
  useEffect(() => {
    refreshTunnel();
    const unlisten = listen("lan-opened", refreshTunnel);
    const timer = window.setInterval(refreshTunnel, 3000);
    return () => {
      window.clearInterval(timer);
      unlisten.then((f) => f());
    };
  }, [refreshTunnel]);
  const [updating, setUpdating] = useState(false);

  // Look for a new launcher version once at startup; failures (offline, private repo) are ignored.
  useEffect(() => {
    api.checkLauncherUpdate().then(setLauncherUpdate).catch(() => {});
  }, []);

  async function installUpdate() {
    if (!launcherUpdate) return;
    setUpdating(true);
    try {
      await api.installLauncherUpdate(launcherUpdate.url);
    } catch (e) {
      setUpdating(false);
      notify(errorText(e), true);
    }
  }
  const activeAccount = accounts.find((a) => a.uuid === settings?.activeAccount);

  // Refresh the account list whenever the active account changes (login, logout, switch).
  useEffect(() => {
    api.listAccounts().then(setAccounts).catch(() => {});
  }, [settings?.activeAccount]);
  const [activeEffect, setActiveEffect] = useState("none");
  const saveTimer = useRef<number | undefined>(undefined);

  useEffect(() => {
    loadAppearance().then(async (a) => {
      setAppearance(a);
      setSplash(a.splash && a.animations && a.layout === "console");
      setActiveEffect(await applyWindowStyle(a));
    });
  }, []);

  useEffect(() => {
    applyAppearance(appearance, activeEffect);
    if (appearance.theme !== "system") return;
    // Follow the OS when it switches between light and dark.
    const media = window.matchMedia("(prefers-color-scheme: light)");
    const onChange = () => applyAppearance(appearance, activeEffect);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [appearance, activeEffect]);

  const appearanceRef = useRef(appearance);
  appearanceRef.current = appearance;

  const changeAppearance = useCallback((next: Appearance) => {
    const prev = appearanceRef.current;
    if (prev.effect !== next.effect || prev.theme !== next.theme) {
      applyWindowStyle(next).then(setActiveEffect);
    }
    setAppearance(next);
    window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => saveAppearance(next).catch(() => {}), 400);
  }, []);

  const notify = useCallback((text: string, error = false) => {
    setToast({ text, error });
    window.setTimeout(() => setToast((t) => (t?.text === text ? null : t)), 4500);
  }, []);

  const refresh = useCallback(async () => {
    setInstances(await api.listInstances());
  }, []);

  const updateInstance = useCallback((inst: Instance) => {
    setInstances((list) => list.map((i) => (i.id === inst.id ? inst : i)));
  }, []);

  useEffect(() => {
    refresh();
    api.getSettings().then(setSettings);
    api.runningInstances().then((ids) =>
      setStatus(Object.fromEntries(ids.map((id) => [id, { state: "running" as GameState }]))),
    );

    const unlisten = [
      listen<ProgressEvent>("launch-progress", ({ payload }) =>
        setStatus((s) => ({ ...s, [payload.instanceId]: { state: "installing", progress: payload } })),
      ),
      listen<StateEvent>("game-state", ({ payload }) => {
        setStatus((s) => ({
          ...s,
          [payload.instanceId]: {
            state: payload.state,
            error: payload.error ?? undefined,
            crashReport: payload.crashReport ?? undefined,
            hints: payload.hints ?? [],
          },
        }));
        if (payload.state === "installing") setLogs((l) => ({ ...l, [payload.instanceId]: [] }));
        if (payload.state === "stopped") {
          refresh();
          if (payload.error && !payload.crashReport) notify(payload.error, true);
        }
      }),
      listen<LogEvent>("game-log", ({ payload }) =>
        setLogs((l) => {
          const lines = [...(l[payload.instanceId] ?? []), payload.line];
          return { ...l, [payload.instanceId]: lines.slice(-MAX_LOG_LINES) };
        }),
      ),
    ];
    return () => unlisten.forEach((p) => p.then((f) => f()));
  }, [refresh, notify]);

  const play = useCallback(
    async (id: string) => {
      try {
        setStatus((s) => ({ ...s, [id]: { state: "installing" } }));
        setLaunching(id);
        await api.launch(id);
      } catch (e) {
        setStatus((s) => ({ ...s, [id]: { state: "stopped" } }));
        setLaunching(null);
        notify(errorText(e), true);
      }
    },
    [notify],
  );

  const nav: { key: string; view: View; label: string; icon: IconName }[] = [
    { key: "library", view: { kind: "library" }, label: appearance.layout === "console" ? "Главная" : "Библиотека", icon: "grid" },
    { key: "mod", view: { kind: "browse", content: "mod" }, label: "Моды", icon: "puzzle" },
    { key: "shader", view: { kind: "browse", content: "shader" }, label: "Шейдеры", icon: "sun" },
    { key: "resourcepack", view: { kind: "browse", content: "resourcepack" }, label: "Ресурспаки", icon: "image" },
    { key: "modpack", view: { kind: "browse", content: "modpack" }, label: "Готовые сборки", icon: "layers" },
    { key: "settings", view: { kind: "settings" }, label: "Настройки", icon: "gear" },
  ];
  const active = view.kind === "instance" ? "library" : view.kind === "browse" ? view.content : view.kind;

  const consoleLayout = appearance.layout === "console";
  const viewKey = view.kind === "instance" ? `i-${view.id}` : view.kind === "browse" ? `b-${view.content}` : view.kind;
  const launchingInstance = launching ? instances.find((i) => i.id === launching) : undefined;

  const guestBanner = tunnel.guest && (
    <div className={`guest-banner ${tunnel.guest.connected ? "" : "lost"}`}>
      <span>
        {tunnel.guest.connected ? "В мире друга" : "Связь с другом потеряна"}
        <br />
        <small className="muted">{linkLabel(tunnel.guest.link)}</small>
      </span>
      <button
        className="btn small-btn"
        onClick={() =>
          api
            .tunnelLeave()
            .then(refreshTunnel)
            .catch(() => {})
        }
      >
        Отключиться
      </button>
    </div>
  );

  return (
    <div className={`app ${consoleLayout ? "console" : ""}`}>
      <div className="app-bg" />
      {consoleLayout && appearance.spaceScene && <SpaceScene accent={appearance.accent} animate={appearance.animations} />}
      {consoleLayout && (
        <header className="topbar">
          <nav className="topbar-tabs">
            {nav
              .filter((item) => item.key !== "settings")
              .map((item) => (
                <button
                  key={item.key}
                  className={`tab-pill ${active === item.key ? "active" : ""}`}
                  onClick={() => setView(item.view)}
                >
                  {item.label}
                </button>
              ))}
          </nav>
          <div className="topbar-right">
            {tunnel.guest && (
              <span className={`status-chip ${tunnel.guest.connected ? "" : "lost"}`} title={linkLabel(tunnel.guest.link)}>
                <span className="dot" data-state={tunnel.guest.connected ? "running" : "stopped"} />
                {tunnel.guest.connected ? "В мире друга" : "Связь потеряна"}
                <button
                  className="link small"
                  onClick={() =>
                    api
                      .tunnelLeave()
                      .then(refreshTunnel)
                      .catch(() => {})
                  }
                >
                  Выйти
                </button>
              </span>
            )}
            {launcherUpdate && (
              <button className="status-chip accent" disabled={updating} onClick={installUpdate} title="Скачать и установить">
                {updating ? <span className="spinner" /> : <Icon name="download" size={14} />}
                {updating ? "Скачиваю…" : `Обновить до ${launcherUpdate.version}`}
              </button>
            )}
            <button className="icon-btn" title="Сетевая игра: зайти в мир друга" onClick={() => setJoining(true)}>
              <Icon name="users" />
            </button>
            <button
              className={`icon-btn ${view.kind === "settings" ? "active" : ""}`}
              title="Настройки"
              onClick={() => setView({ kind: "settings" })}
            >
              <Icon name="gear" />
            </button>
            <button
              className="avatar-btn"
              title={`${activeAccount?.username ?? settings?.username ?? ""} · ${activeAccount ? "Microsoft" : "офлайн"}`}
              onClick={() => setView({ kind: "settings" })}
            >
              {activeAccount ? (
                <img src={`https://mc-heads.net/avatar/${activeAccount.uuid}/32`} alt="" />
              ) : (
                (settings?.username.slice(0, 1).toUpperCase() ?? "?")
              )}
            </button>
            <Clock />
          </div>
        </header>
      )}
      {!consoleLayout && (
      <aside className="sidebar">
        <div className="brand">
          <img src="/logo.svg" alt="" />
          <span>Blockyard</span>
        </div>
        <nav>
          {nav.map((item) => (
            <button
              key={item.key}
              className={`nav-item ${active === item.key ? "active" : ""}`}
              title={item.label}
              onClick={() => setView(item.view)}
            >
              <Icon name={item.icon} />
              <span className="nav-label">{item.label}</span>
            </button>
          ))}
        </nav>
        {instances.length > 0 && (
          <div className="sidebar-instances">
            <div className="sidebar-label">Сборки</div>
            {instances.slice(0, 8).map((inst) => (
              <button
                key={inst.id}
                className={`sidebar-instance ${view.kind === "instance" && view.id === inst.id ? "active" : ""}`}
                onClick={() => setView({ kind: "instance", id: inst.id })}
              >
                <span className="instance-dot" data-state={status[inst.id]?.state ?? "stopped"} />
                <span className="truncate">{inst.name}</span>
              </button>
            ))}
          </div>
        )}
        {guestBanner}
        {launcherUpdate && (
          <div className="update-banner" title={`Доступна версия ${launcherUpdate.version}`}>
            <span>
              Доступна версия <strong>{launcherUpdate.version}</strong>
            </span>
            <button className="btn primary" disabled={updating} onClick={installUpdate}>
              {updating ? <span className="spinner" /> : <Icon name="download" />}
              <span>{updating ? "Скачиваю…" : "Обновить"}</span>
            </button>
          </div>
        )}
        <div className="account">
          {activeAccount ? (
            <img className="avatar" src={`https://mc-heads.net/avatar/${activeAccount.uuid}/34`} alt="" />
          ) : (
            <div className="avatar">{settings?.username.slice(0, 1).toUpperCase() ?? "?"}</div>
          )}
          <div>
            <div className="account-name">{activeAccount?.username ?? settings?.username ?? "…"}</div>
            <div className="muted small">{activeAccount ? "Microsoft" : "Офлайн-аккаунт"}</div>
          </div>
        </div>
      </aside>
      )}

      <main className="content">
        <div className="view-anim" key={viewKey}>
        {view.kind === "library" && consoleLayout && (
          <Home
            instances={instances}
            status={status}
            onOpen={(id) => setView({ kind: "instance", id })}
            onPlay={play}
            onStop={(id) => api.stop(id)}
            onJoinFriend={() => setJoining(true)}
            onBrowseModpacks={() => setView({ kind: "browse", content: "modpack" })}
            onBrowse={(content, instanceId) => setView({ kind: "browse", content, instanceId })}
            onChanged={updateInstance}
            onCreated={(inst) => setInstances((list) => [inst, ...list])}
            notify={notify}
          />
        )}
        {view.kind === "library" && !consoleLayout && (
          <Library
            instances={instances}
            status={status}
            onOpen={(id) => setView({ kind: "instance", id })}
            onPlay={play}
            onJoinFriend={() => setJoining(true)}
            onCreated={(inst) => {
              setInstances((list) => [inst, ...list]);
              setView({ kind: "instance", id: inst.id });
            }}
            notify={notify}
          />
        )}
        {view.kind === "instance" && (
          <InstanceView
            key={view.id}
            instance={instances.find((i) => i.id === view.id)}
            instances={instances}
            tunnel={tunnel}
            onTunnelChanged={refreshTunnel}
            status={status[view.id]}
            logs={logs[view.id] ?? []}
            onPlay={() => play(view.id)}
            onStop={() => api.stop(view.id)}
            onBrowse={(content) => setView({ kind: "browse", content, instanceId: view.id })}
            onChanged={updateInstance}
            onDeleted={() => {
              refresh();
              setView({ kind: "library" });
            }}
            onDuplicated={(copy) => {
              setInstances((list) => [copy, ...list]);
              setView({ kind: "instance", id: copy.id });
            }}
            notify={notify}
          />
        )}
        {view.kind === "browse" && (
          <Browse
            key={view.content}
            kind={view.content}
            instances={instances}
            initialInstanceId={view.instanceId}
            onChanged={updateInstance}
            onCreated={(inst) => {
              setInstances((list) => [inst, ...list]);
              setView({ kind: "instance", id: inst.id });
            }}
            onOpenInstance={(id) => setView({ kind: "instance", id })}
            notify={notify}
          />
        )}
        {view.kind === "settings" && settings && (
          <SettingsPage
            settings={settings}
            onSaved={setSettings}
            appearance={appearance}
            activeEffect={activeEffect}
            onAppearanceChange={changeAppearance}
            notify={notify}
          />
        )}
        </div>
      </main>

      {joining && (
        <JoinFriendModal
          instances={instances}
          onClose={() => setJoining(false)}
          onCreated={(inst) => setInstances((list) => [inst, ...list])}
          onJoined={(id) => {
            setJoining(false);
            refreshTunnel();
            setView({ kind: "instance", id });
          }}
        />
      )}

      {consoleLayout && launchingInstance && (
        <LaunchOverlay instance={launchingInstance} status={status[launchingInstance.id]} onClose={hideLaunch} />
      )}
      {splash && <Splash name={activeAccount?.username ?? settings?.username ?? ""} onDone={hideSplash} />}

      {toast && <div className={`toast ${toast.error ? "error" : ""}`}>{toast.text}</div>}
    </div>
  );
}
