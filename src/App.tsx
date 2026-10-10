import { useCallback, useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { api, errorText, GameState, Instance, LogEvent, ProgressEvent, Settings, StateEvent } from "./api";
import Library from "./pages/Library";
import InstanceView from "./pages/InstanceView";
import Browse from "./pages/Browse";
import SettingsPage from "./pages/Settings";
import { Icon } from "./components/Icon";

export type View =
  | { kind: "library" }
  | { kind: "instance"; id: string }
  | { kind: "browse"; instanceId?: string }
  | { kind: "settings" };

export interface GameStatus {
  state: GameState;
  progress?: ProgressEvent;
  error?: string;
}

const MAX_LOG_LINES = 3000;

export default function App() {
  const [view, setView] = useState<View>({ kind: "library" });
  const [instances, setInstances] = useState<Instance[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [status, setStatus] = useState<Record<string, GameStatus>>({});
  const [logs, setLogs] = useState<Record<string, string[]>>({});
  const [toast, setToast] = useState<{ text: string; error?: boolean } | null>(null);

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
          [payload.instanceId]: { state: payload.state, error: payload.error ?? undefined },
        }));
        if (payload.state === "installing") setLogs((l) => ({ ...l, [payload.instanceId]: [] }));
        if (payload.state === "stopped") {
          refresh();
          if (payload.error) notify(payload.error, true);
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

  const nav = [
    { kind: "library" as const, label: "Библиотека", icon: "grid" as const },
    { kind: "browse" as const, label: "Моды", icon: "search" as const },
    { kind: "settings" as const, label: "Настройки", icon: "gear" as const },
  ];
  const active = view.kind === "instance" ? "library" : view.kind;

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <img src="/logo.svg" alt="" />
          <span>Blockyard</span>
        </div>
        <nav>
          {nav.map((item) => (
            <button
              key={item.kind}
              className={`nav-item ${active === item.kind ? "active" : ""}`}
              onClick={() => setView({ kind: item.kind } as View)}
            >
              <Icon name={item.icon} />
              {item.label}
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
        <div className="account">
          <div className="avatar">{settings?.username.slice(0, 1).toUpperCase() ?? "?"}</div>
          <div>
            <div className="account-name">{settings?.username ?? "…"}</div>
            <div className="muted small">Офлайн-аккаунт</div>
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
            status={status[view.id]}
            logs={logs[view.id] ?? []}
            onPlay={() => play(view.id)}
            onStop={() => api.stop(view.id)}
            onBrowse={() => setView({ kind: "browse", instanceId: view.id })}
            onChanged={updateInstance}
            onDeleted={() => {
              refresh();
              setView({ kind: "library" });
            }}
            notify={notify}
          />
        )}
        {view.kind === "browse" && (
          <Browse
            instances={instances}
            initialInstanceId={view.instanceId}
            onChanged={updateInstance}
            onOpenInstance={(id) => setView({ kind: "instance", id })}
            notify={notify}
          />
        )}
        {view.kind === "settings" && settings && (
          <SettingsPage settings={settings} onSaved={setSettings} notify={notify} />
        )}
      </main>

      {toast && <div className={`toast ${toast.error ? "error" : ""}`}>{toast.text}</div>}
    </div>
  );
}
