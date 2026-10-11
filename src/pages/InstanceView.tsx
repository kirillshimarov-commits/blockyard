import { useEffect, useRef, useState } from "react";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import { save } from "@tauri-apps/plugin-dialog";
import { api, ContentFolder, errorText, Instance, loaderLabel, TunnelStatus } from "../api";
import type { GameStatus } from "../App";
import { Icon } from "../components/Icon";
import PendingDownloadsModal from "../components/PendingDownloadsModal";
import InstanceCover from "../components/InstanceCover";
import ContentList from "../components/ContentList";
import UpdatesModal from "../components/UpdatesModal";
import InstanceSettingsModal from "../components/InstanceSettingsModal";
import FriendPanel from "../components/FriendPanel";

interface Props {
  instance?: Instance;
  instances: Instance[];
  tunnel: TunnelStatus;
  onTunnelChanged: () => void;
  status?: GameStatus;
  logs: string[];
  onPlay: () => void;
  onStop: () => void;
  onBrowse: (content: "mod" | "shader" | "resourcepack") => void;
  onChanged: (inst: Instance) => void;
  onDeleted: () => void;
  onDuplicated: (inst: Instance) => void;
  notify: (text: string, error?: boolean) => void;
}

type Tab = "mods" | "shaders" | "resourcepacks" | "log";

export default function InstanceView(props: Props) {
  const { instance, instances, tunnel, onTunnelChanged, status, logs, onPlay, onStop, onBrowse, onChanged } = props;
  const { onDeleted, onDuplicated, notify } = props;
  const [showSettings, setShowSettings] = useState(false);
  const [tab, setTab] = useState<Tab>("mods");
  const [showPending, setShowPending] = useState(false);
  const [playAfterPending, setPlayAfterPending] = useState(false);
  const [showUpdates, setShowUpdates] = useState(false);
  const logRef = useRef<HTMLPreElement>(null);
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

  async function exportPack() {
    try {
      const path = await save({
        defaultPath: `${instance!.name}.mrpack`,
        filters: [{ name: "Модпак Modrinth", extensions: ["mrpack"] }],
      });
      if (!path) return;
      notify("Экспортирую сборку…");
      await api.exportInstance(instance!.id, path);
      notify("Сборка сохранена — её можно отправить другу");
      revealItemInDir(path).catch(() => {});
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function toggleMod(projectId: string, enabled: boolean) {
    try {
      onChanged(await api.setModEnabled(instance!.id, projectId, enabled));
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
        <button className="btn icon" title="Настройки сборки" onClick={() => setShowSettings(true)}>
          <Icon name="gear" />
        </button>
        <button className="btn icon" title="Экспорт в .mrpack (поделиться сборкой)" onClick={exportPack}>
          <Icon name="share" />
        </button>
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
      <FriendPanel
        instance={instance}
        running={state === "running"}
        tunnel={tunnel}
        onTunnelChanged={onTunnelChanged}
        notify={notify}
      />
      {state === "stopped" && status?.error && (
        <div className="error-box crash-box">
          <div className="grow">
            <div>{status.error}</div>
            {status.hints && status.hints.length > 0 && (
              <>
                <strong className="small">Вероятная причина:</strong>
                <ul className="hints">
                  {status.hints.map((h) => (
                    <li key={h}>{h}</li>
                  ))}
                </ul>
              </>
            )}
            {instance.pending.length > 0 && (
              <div className="small">
                Не скачаны моды: {instance.pending.map((p) => p.title).join(", ")} — это частая причина вылетов.
              </div>
            )}
          </div>
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
              <span className="muted">
                {instance.mods.length
                  ? `Установленные моды${
                      instance.mods.some((m) => m.disabled)
                        ? ` (выключено: ${instance.mods.filter((m) => m.disabled).length})`
                        : ""
                    }`
                  : "Модов пока нет"}
              </span>
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
              onToggle={toggleMod}
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

      {showSettings && (
        <InstanceSettingsModal
          instance={instance}
          instances={instances}
          busy={state !== "stopped"}
          onClose={() => setShowSettings(false)}
          onChanged={onChanged}
          onDuplicated={(copy) => {
            setShowSettings(false);
            onDuplicated(copy);
          }}
          onDeleted={onDeleted}
          onRepair={() => {
            setShowSettings(false);
            api
              .repairInstance(instance.id)
              .then(() => notify("Исправляю сборку…"))
              .catch((e) => notify(errorText(e), true));
          }}
          notify={notify}
        />
      )}
    </div>
  );
}
