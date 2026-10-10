import { useEffect, useRef, useState } from "react";
import { openPath } from "@tauri-apps/plugin-opener";
import { api, errorText, Instance, loaderLabel } from "../api";
import type { GameStatus } from "../App";
import { Icon } from "../components/Icon";
import PendingDownloadsModal from "../components/PendingDownloadsModal";
import InstanceCover from "../components/InstanceCover";

interface Props {
  instance?: Instance;
  status?: GameStatus;
  logs: string[];
  onPlay: () => void;
  onStop: () => void;
  onBrowse: (content: "mod" | "shader") => void;
  onChanged: (inst: Instance) => void;
  onDeleted: () => void;
  notify: (text: string, error?: boolean) => void;
}

type Tab = "mods" | "shaders" | "log" | "options";

export default function InstanceView(props: Props) {
  const { instance, status, logs, onPlay, onStop, onBrowse, onChanged, onDeleted, notify } = props;
  const [tab, setTab] = useState<Tab>("mods");
  const [showPending, setShowPending] = useState(false);
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
  const hasLoader = instance.loader.type !== "vanilla";
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
        <InstanceCover instance={instance} large />
        <div className="grow">
          <h1>{instance.name}</h1>
          <p className="muted">
            Minecraft {instance.gameVersion} · {loaderLabel(instance.loader)} · {instance.memoryMb} МБ ОЗУ
            {instance.modpack ? ` · модпак «${instance.modpack.title}»` : ""}
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
      {state === "stopped" && status?.error && (
        <div className="error-box crash-box">
          <span className="grow">{status.error}</span>
          {status.crashReport && (
            <button
              className="btn"
              onClick={() => openPath(status.crashReport!).catch((e) => notify(errorText(e), true))}
            >
              Открыть отчёт о сбое
            </button>
          )}
        </div>
      )}

      <div className="tabs">
        <button className={tab === "mods" ? "on" : ""} onClick={() => setTab("mods")}>
          Моды {instance.mods.length > 0 && <span className="badge">{instance.mods.length}</span>}
        </button>
        <button className={tab === "shaders" ? "on" : ""} onClick={() => setTab("shaders")}>
          Шейдеры {instance.shaders.length > 0 && <span className="badge">{instance.shaders.length}</span>}
        </button>
        <button className={tab === "log" ? "on" : ""} onClick={() => setTab("log")}>
          Журнал
        </button>
        <button className={tab === "options" ? "on" : ""} onClick={() => setTab("options")}>
          Настройки
        </button>
      </div>

      {tab === "mods" &&
        (!hasLoader ? (
          <div className="empty small">
            <p className="muted">Это ванильная сборка. Чтобы ставить моды, создайте сборку с Fabric, Forge или NeoForge.</p>
          </div>
        ) : (
          <>
            {instance.pending.length > 0 && (
              <div className="warning-box row between">
                <span>Ждут ручного скачивания: {instance.pending.map((p) => p.title).join(", ")}</span>
                <button className="btn" onClick={() => setShowPending(true)}>
                  Скачать
                </button>
              </div>
            )}
            <div className="row between">
              <span className="muted">{instance.mods.length ? "Установленные моды" : "Модов пока нет"}</span>
              <button className="btn primary" onClick={() => onBrowse("mod")}>
                <Icon name="plus" /> Добавить моды
              </button>
            </div>
            <div className="mod-list">
              {instance.mods.map((m) => (
                <div key={m.projectId} className="mod-row">
                  {m.iconUrl ? <img src={m.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
                  <div className="grow">
                    <div className="mod-title">{m.title}</div>
                    <div className="muted small">
                      <span className={`source-badge ${m.source}`}>
                        {m.source === "curseforge" ? "CurseForge" : "Modrinth"}
                      </span>{" "}
                      {m.fileName}
                    </div>
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

      {tab === "shaders" &&
        (!hasLoader ? (
          <div className="empty small">
            <p className="muted">Для шейдеров нужна сборка с Fabric, Forge, NeoForge или Quilt.</p>
          </div>
        ) : (
          <>
            <div className="row between">
              <span className="muted">
                {instance.shaders.length
                  ? "Включить шейдер: в игре Настройки → Графика → Наборы шейдеров"
                  : "Шейдеров пока нет"}
              </span>
              <button className="btn primary" onClick={() => onBrowse("shader")}>
                <Icon name="plus" /> Добавить шейдеры
              </button>
            </div>
            <div className="mod-list">
              {instance.shaders.map((m) => (
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
                    onClick={() =>
                      api
                        .removeShader(instance.id, m.projectId)
                        .then(onChanged)
                        .catch((e) => notify(errorText(e), true))
                    }
                  >
                    <Icon name="trash" />
                  </button>
                </div>
              ))}
            </div>
          </>
        ))}

      {showPending && (
        <PendingDownloadsModal instance={instance} onChanged={onChanged} onClose={() => setShowPending(false)} />
      )}

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
