import { useEffect, useRef, useState } from "react";
import { openPath } from "@tauri-apps/plugin-opener";
import { api, ContentFolder, errorText, Instance, loaderLabel } from "../api";
import type { GameStatus } from "../App";
import { Icon } from "../components/Icon";
import PendingDownloadsModal from "../components/PendingDownloadsModal";
import InstanceCover from "../components/InstanceCover";
import ContentList from "../components/ContentList";
import UpdatesModal from "../components/UpdatesModal";

interface Props {
  instance?: Instance;
  status?: GameStatus;
  logs: string[];
  onPlay: () => void;
  onStop: () => void;
  onBrowse: (content: "mod" | "shader" | "resourcepack") => void;
  onChanged: (inst: Instance) => void;
  onDeleted: () => void;
  notify: (text: string, error?: boolean) => void;
}

type Tab = "mods" | "shaders" | "resourcepacks" | "log" | "options";

export default function InstanceView(props: Props) {
  const { instance, status, logs, onPlay, onStop, onBrowse, onChanged, onDeleted, notify } = props;
  const [tab, setTab] = useState<Tab>("mods");
  const [showPending, setShowPending] = useState(false);
  const [playAfterPending, setPlayAfterPending] = useState(false);
  const [showUpdates, setShowUpdates] = useState(false);
  const logRef = useRef<HTMLPreElement>(null);
  const saveTimer = useRef<number | undefined>(undefined);
  const prevState = useRef<string | undefined>(undefined);
  const state = status?.state ?? "stopped";
  const pendingCount = instance?.pending.length ?? 0;

  useEffect(() => {
    if (state === "installing") setTab("log");
    // A modpack just finished installing but some mods need a manual download.
    if (prevState.current === "installing" && state === "stopped" && !status?.error && pendingCount > 0) {
      setTab("mods");
      setShowPending(true);
    }
    prevState.current = state;
  }, [state, status?.error, pendingCount]);

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [logs, tab]);

  if (!instance) return <div className="page muted">Сборка не найдена</div>;
  const hasLoader = instance.loader.type !== "vanilla";
  const progress = status?.progress;
  const percent = progress && progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : null;

  async function removeContent(folder: ContentFolder, projectId: string) {
    try {
      onChanged(await api.removeContent(instance!.id, folder, projectId));
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
          <button
            className="btn primary big"
            onClick={() => {
              // Launching without required mods usually ends in a crash; offer the downloads first.
              if (instance.pending.length > 0) {
                setPlayAfterPending(true);
                setShowPending(true);
              } else {
                onPlay();
              }
            }}
          >
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
        <button className={tab === "resourcepacks" ? "on" : ""} onClick={() => setTab("resourcepacks")}>
          Ресурспаки{" "}
          {instance.resourcepacks.length > 0 && <span className="badge">{instance.resourcepacks.length}</span>}
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
              <div className="row gap-sm">
                {instance.mods.length > 0 && (
                  <button className="btn" disabled={state !== "stopped"} onClick={() => setShowUpdates(true)}>
                    Проверить обновления
                  </button>
                )}
                <button className="btn primary" onClick={() => onBrowse("mod")}>
                  <Icon name="plus" /> Добавить моды
                </button>
              </div>
            </div>
            <ContentList
              items={instance.mods}
              disabled={state !== "stopped"}
              onRemove={(id) => removeContent("mods", id)}
            />
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
            <ContentList
              items={instance.shaders}
              disabled={state !== "stopped"}
              onRemove={(id) => removeContent("shaderpacks", id)}
            />
          </>
        ))}

      {tab === "resourcepacks" && (
        <>
          <div className="row between">
            <span className="muted">
              {instance.resourcepacks.length
                ? "Включить: в игре Настройки → Пакеты ресурсов"
                : "Ресурспаков пока нет"}
            </span>
            <button className="btn primary" onClick={() => onBrowse("resourcepack")}>
              <Icon name="plus" /> Добавить ресурспаки
            </button>
          </div>
          <ContentList
            items={instance.resourcepacks}
            disabled={state !== "stopped"}
            onRemove={(id) => removeContent("resourcepacks", id)}
          />
        </>
      )}

      {showUpdates && (
        <UpdatesModal instance={instance} onChanged={onChanged} onClose={() => setShowUpdates(false)} notify={notify} />
      )}

      {showPending && (
        <PendingDownloadsModal
          instance={instance}
          onChanged={onChanged}
          onClose={() => {
            setShowPending(false);
            setPlayAfterPending(false);
          }}
          onPlay={
            playAfterPending
              ? () => {
                  setShowPending(false);
                  setPlayAfterPending(false);
                  onPlay();
                }
              : undefined
          }
        />
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
