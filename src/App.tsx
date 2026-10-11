import { useCallback, useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { Account, api, ContentKind, LauncherUpdate, errorText, GameState, Instance, LogEvent, ProgressEvent, Settings, StateEvent } from "./api";
import Library from "./pages/Library";
import InstanceView from "./pages/InstanceView";
import Browse from "./pages/Browse";
import SettingsPage from "./pages/Settings";
import { Icon, IconName } from "./components/Icon";
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
        await api.launch(id);
      } catch (e) {
        setStatus((s) => ({ ...s, [id]: { state: "stopped" } }));
        notify(errorText(e), true);
      }
    },
    [notify],
  );

  const nav: { key: string; view: View; label: string; icon: IconName }[] = [
    { key: "library", view: { kind: "library" }, label: "Библиотека", icon: "grid" },
    { key: "mod", view: { kind: "browse", content: "mod" }, label: "Моды", icon: "puzzle" },
    { key: "shader", view: { kind: "browse", content: "shader" }, label: "Шейдеры", icon: "sun" },
    { key: "resourcepack", view: { kind: "browse", content: "resourcepack" }, label: "Ресурспаки", icon: "image" },
    { key: "modpack", view: { kind: "browse", content: "modpack" }, label: "Готовые сборки", icon: "layers" },
    { key: "settings", view: { kind: "settings" }, label: "Настройки", icon: "gear" },
  ];
  const active = view.kind === "instance" ? "library" : view.kind === "browse" ? view.content : view.kind;

  return (
    <div className="app">
      <div className="app-bg" />
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

      <main className="content">
        {view.kind === "library" && (
          <Library
            instances={instances}
            status={status}
            onOpen={(id) => setView({ kind: "instance", id })}
            onPlay={play}
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
      </main>

      {toast && <div className={`toast ${toast.error ? "error" : ""}`}>{toast.text}</div>}
    </div>
  );
}
