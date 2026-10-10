import { useEffect, useRef, useState } from "react";
import { openPath } from "@tauri-apps/plugin-opener";
import { api, errorText, Instance, loaderLabel } from "../api";
import type { GameStatus } from "../App";
import { Icon } from "../components/Icon";

interface Props {
  instance?: Instance;
  status?: GameStatus;
  logs: string[];
  onPlay: () => void;
  onStop: () => void;
  onBrowse: () => void;
  onChanged: (inst: Instance) => void;
  onDeleted: () => void;
  notify: (text: string, error?: boolean) => void;
}

type Tab = "mods" | "log" | "options";

export default function InstanceView(props: Props) {
  const { instance, status, logs, onPlay, onStop, onBrowse, onChanged, onDeleted, notify } = props;
  const [tab, setTab] = useState<Tab>("mods");
  const logRef = useRef<HTMLPreElement>(null);
  const saveTimer = useRef<number | undefined>(undefined);
  const state = status?.state ?? "stopped";

  useEffect(() => {
    if (state === "installing") setTab("log");
  }, [state]);

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [logs, tab]);

  if (!instance) return <div className="page muted">Сборка не найдена</div>;
  const isFabric = instance.loader.type === "fabric";
  const progress = status?.progress;
  const percent = progress && progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : null;

  async function removeMod(projectId: string) {
    try {
      onChanged(await api.removeMod(instance!.id, projectId));
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function openFolder() {
    try {
      await openPath(await api.instanceFolder(instance!.id));
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function remove() {
    if (!window.confirm(`Удалить сборку «${instance!.name}» вместе с мирами и модами?`)) return;
    try {
      await api.deleteInstance(instance!.id);
      onDeleted();
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  return (
    <div className="page">
      <header className="instance-header">
        <div className="instance-cover large">
          <span>{instance.name.slice(0, 1).toUpperCase()}</span>
        </div>
        <div className="grow">
          <h1>{instance.name}</h1>
          <p className="muted">
            Minecraft {instance.gameVersion} · {loaderLabel(instance.loader)} · {instance.memoryMb} МБ ОЗУ
          </p>
        </div>
        <button className="btn icon" title="Открыть папку" onClick={openFolder}>
          <Icon name="folder" />
        </button>
        {state === "stopped" ? (
          <button className="btn primary big" onClick={onPlay}>
            <Icon name="play" /> Играть
          </button>
        ) : (
          <button className="btn danger big" onClick={onStop}>
            <Icon name="stop" /> {state === "installing" ? "Отмена" : "Закрыть игру"}
          </button>
        )}
      </header>

      {state === "installing" && (
        <div className="progress-card">
          <div className="row between">
            <span>{progress?.stage ?? "Подготовка"}</span>
            <span className="muted">
              {progress && progress.total > 1 ? `${progress.current} / ${progress.total}` : ""}
            </span>
          </div>
          <div className="progress">
            <div className={`bar ${percent === null ? "indeterminate" : ""}`} style={{ width: `${percent ?? 30}%` }} />
          </div>
        </div>
      )}
      {state === "stopped" && status?.error && <div className="error-box">{status.error}</div>}

      <div className="tabs">
        <button className={tab === "mods" ? "on" : ""} onClick={() => setTab("mods")}>
          Моды {instance.mods.length > 0 && <span className="badge">{instance.mods.length}</span>}
        </button>
        <button className={tab === "log" ? "on" : ""} onClick={() => setTab("log")}>
          Журнал
        </button>
        <button className={tab === "options" ? "on" : ""} onClick={() => setTab("options")}>
          Настройки
        </button>
      </div>

      {tab === "mods" &&
        (!isFabric ? (
          <div className="empty small">
            <p className="muted">Это ванильная сборка. Чтобы ставить моды, создайте сборку с Fabric.</p>
          </div>
        ) : (
          <>
            <div className="row between">
              <span className="muted">{instance.mods.length ? "Установленные моды" : "Модов пока нет"}</span>
              <button className="btn primary" onClick={onBrowse}>
                <Icon name="plus" /> Добавить моды
              </button>
            </div>
            <div className="mod-list">
              {instance.mods.map((m) => (
                <div key={m.projectId} className="mod-row">
                  {m.iconUrl ? <img src={m.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
                  <div className="grow">
                    <div className="mod-title">{m.title}</div>
                    <div className="muted small">{m.fileName}</div>
                  </div>
                  <button
                    className="btn icon"
                    title="Удалить"
                    disabled={state !== "stopped"}
                    onClick={() => removeMod(m.projectId)}
                  >
                    <Icon name="trash" />
                  </button>
                </div>
              ))}
            </div>
          </>
        ))}

      {tab === "log" && (
        <pre className="log" ref={logRef}>
          {logs.length ? logs.join("\n") : "Журнал появится после запуска игры."}
        </pre>
      )}

      {tab === "options" && (
        <div className="options">
          <label className="field">
            <span>Оперативная память: {instance.memoryMb} МБ</span>
            <input
              type="range"
              min={1024}
              max={16384}
              step={512}
              value={instance.memoryMb}
              onChange={(e) => {
                const memoryMb = Number(e.target.value);
                onChanged({ ...instance, memoryMb });
                window.clearTimeout(saveTimer.current);
                saveTimer.current = window.setTimeout(
                  () =>
                    api
                      .setInstanceMemory(instance.id, memoryMb)
                      .then(onChanged)
                      .catch((err) => notify(errorText(err), true)),
                  400,
                );
              }}
            />
          </label>
          <div className="danger-zone">
            <div>
              <strong>Удалить сборку</strong>
              <p className="muted small">Миры, моды и настройки этой сборки будут удалены без возможности восстановления.</p>
            </div>
            <button className="btn danger" disabled={state !== "stopped"} onClick={remove}>
              <Icon name="trash" /> Удалить
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
