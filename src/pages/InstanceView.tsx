import { useEffect, useRef, useState } from "react";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import { save } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import {
  api,
  BisectStatus,
  ContentFolder,
  ContentKind,
  errorText,
  formatPlayTime,
  Instance,
  loaderLabel,
  Source,
  TunnelStatus,
} from "../api";
import ModPageModal from "../components/ModPageModal";
import BisectPanel from "../components/BisectPanel";
import { t, tStage } from "../i18n";
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
  const [opened, setOpened] = useState<{ kind: ContentKind; source: Source; projectId: string } | null>(null);
  const [bisect, setBisect] = useState<BisectStatus | null>(null);
  const instanceId = instance?.id;

  // The crash search moves on by itself after each test launch; follow it.
  useEffect(() => {
    if (!instanceId) return;
    const load = () => api.bisectStatus(instanceId).then(setBisect).catch(() => setBisect(null));
    load();
    const unlisten = listen<string>("bisect-changed", ({ payload }) => {
      if (payload === instanceId) load();
    });
    return () => {
      unlisten.then((f) => f());
    };
  }, [instanceId]);
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

  if (!instance) return <div className="page muted">{t("Сборка не найдена")}</div>;
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
        filters: [{ name: t("Модпак Modrinth"), extensions: ["mrpack"] }],
      });
      if (!path) return;
      notify(t("Экспортирую сборку…"));
      await api.exportInstance(instance!.id, path);
      notify(t("Сборка сохранена — её можно отправить другу"));
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

  const enabledMods = instance.mods.filter((m) => !m.disabled).length;
  const canSearch = !bisect && state === "stopped" && enabledMods >= 2;

  async function startSearch() {
    try {
      setBisect(await api.bisectStart(instance!.id));
      onChanged(await api.getInstance(instance!.id));
      setTab("mods");
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
            Minecraft {instance.gameVersion} · {loaderLabel(instance.loader)} ·{" "}
            {t("{n} МБ ОЗУ", { n: instance.memoryMb })}
            {instance.modpack ? ` · ${t("модпак «{name}»", { name: instance.modpack.title })}` : ""}
            {formatPlayTime(instance.playSeconds)
              ? ` · ${t("наиграно {time}", { time: formatPlayTime(instance.playSeconds) })}`
              : ""}
          </p>
        </div>
        <button className="btn icon" title={t("Настройки сборки")} onClick={() => setShowSettings(true)}>
          <Icon name="gear" />
        </button>
        <button className="btn icon" title={t("Экспорт в .mrpack (поделиться сборкой)")} onClick={exportPack}>
          <Icon name="share" />
        </button>
        <button className="btn icon" title={t("Открыть папку")} onClick={openFolder}>
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
            <Icon name="play" /> {t("Играть")}
          </button>
        ) : (
          <button className="btn danger big" onClick={onStop}>
            <Icon name="stop" /> {state === "installing" ? t("Отмена") : t("Закрыть игру")}
          </button>
        )}
      </header>

      {state === "installing" && (
        <div className="progress-card">
          <div className="row between">
            <span>{tStage(progress?.stage ?? "Подготовка")}</span>
            <span className="muted">
              {progress && progress.total > 1 ? `${progress.current} / ${progress.total}` : ""}
            </span>
          </div>
          <div className="progress">
            <div className={`bar ${percent === null ? "indeterminate" : ""}`} style={{ width: `${percent ?? 30}%` }} />
          </div>
        </div>
      )}
      {bisect && (
        <BisectPanel
          instance={instance}
          status={bisect}
          running={state !== "stopped"}
          onStatus={setBisect}
          onChanged={onChanged}
          onPlay={onPlay}
          notify={notify}
        />
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
                <strong className="small">{t("Вероятная причина:")}</strong>
                <ul className="hints">
                  {status.hints.map((h) => (
                    <li key={h}>{h}</li>
                  ))}
                </ul>
              </>
            )}
            {instance.pending.length > 0 && (
              <div className="small">
                {t("Не скачаны моды: {list} — это частая причина вылетов.", {
                  list: instance.pending.map((p) => p.title).join(", "),
                })}
              </div>
            )}
          </div>
          <div className="crash-actions">
            {status.crashReport && (
              <button
                className="btn"
                onClick={() => openPath(status.crashReport!).catch((e) => notify(errorText(e), true))}
              >
                {t("Открыть отчёт о сбое")}
              </button>
            )}
            {canSearch && (
              <button className="btn" onClick={startSearch} title={t("Лаунчер по очереди выключит часть модов и найдёт виновника")}>
                <Icon name="bug" /> {t("Найти виновника")}
              </button>
            )}
          </div>
        </div>
      )}

      <div className="tabs">
        <button className={tab === "mods" ? "on" : ""} onClick={() => setTab("mods")}>
          {t("Моды")} {instance.mods.length > 0 && <span className="badge">{instance.mods.length}</span>}
        </button>
        <button className={tab === "shaders" ? "on" : ""} onClick={() => setTab("shaders")}>
          {t("Шейдеры")} {instance.shaders.length > 0 && <span className="badge">{instance.shaders.length}</span>}
        </button>
        <button className={tab === "resourcepacks" ? "on" : ""} onClick={() => setTab("resourcepacks")}>
          {t("Ресурспаки")}{" "}
          {instance.resourcepacks.length > 0 && <span className="badge">{instance.resourcepacks.length}</span>}
        </button>
        <button className={tab === "log" ? "on" : ""} onClick={() => setTab("log")}>
          {t("Журнал")}
        </button>
      </div>

      {tab === "mods" &&
        (!hasLoader ? (
          <div className="empty small">
            <p className="muted">{t("Это ванильная сборка. Чтобы ставить моды, создайте сборку с Fabric, Forge или NeoForge.")}</p>
          </div>
        ) : (
          <>
            {instance.pending.length > 0 && (
              <div className="warning-box row between">
                <span>{t("Ждут ручного скачивания: {list}", { list: instance.pending.map((p) => p.title).join(", ") })}</span>
                <button className="btn" onClick={() => setShowPending(true)}>
                  {t("Скачать")}
                </button>
              </div>
            )}
            <div className="row between">
              <span className="muted">
                {instance.mods.length
                  ? instance.mods.some((m) => m.disabled)
                    ? t("Установленные моды (выключено: {n})", { n: instance.mods.filter((m) => m.disabled).length })
                    : t("Установленные моды")
                  : t("Модов пока нет")}
              </span>
              <div className="row gap-sm">
                {canSearch && (
                  <button
                    className="btn icon"
                    title={t("Найти мод, из-за которого игра вылетает: лаунчер по очереди выключит часть модов")}
                    onClick={startSearch}
                  >
                    <Icon name="bug" />
                  </button>
                )}
                {instance.mods.length > 0 && (
                  <button className="btn" disabled={state !== "stopped"} onClick={() => setShowUpdates(true)}>
                    {t("Проверить обновления")}
                  </button>
                )}
                <button className="btn primary" onClick={() => onBrowse("mod")}>
                  <Icon name="plus" /> {t("Добавить моды")}
                </button>
              </div>
            </div>
            <ContentList
              items={instance.mods}
              disabled={state !== "stopped" || !!bisect}
              onRemove={(id) => removeContent("mods", id)}
              onToggle={toggleMod}
              onOpen={(m) => setOpened({ kind: "mod", source: m.source, projectId: m.projectId })}
            />
          </>
        ))}

      {tab === "shaders" &&
        (!hasLoader ? (
          <div className="empty small">
            <p className="muted">{t("Для шейдеров нужна сборка с Fabric, Forge, NeoForge или Quilt.")}</p>
          </div>
        ) : (
          <>
            <div className="row between">
              <span className="muted">
                {instance.shaders.length
                  ? t("Включить шейдер: в игре Настройки → Графика → Наборы шейдеров")
                  : t("Шейдеров пока нет")}
              </span>
              <button className="btn primary" onClick={() => onBrowse("shader")}>
                <Icon name="plus" /> {t("Добавить шейдеры")}
              </button>
            </div>
            <ContentList
              items={instance.shaders}
              disabled={state !== "stopped"}
              onRemove={(id) => removeContent("shaderpacks", id)}
              onOpen={(m) => setOpened({ kind: "shader", source: m.source, projectId: m.projectId })}
            />
          </>
        ))}

      {tab === "resourcepacks" && (
        <>
          <div className="row between">
            <span className="muted">
              {instance.resourcepacks.length
                ? t("Включить: в игре Настройки → Пакеты ресурсов")
                : t("Ресурспаков пока нет")}
            </span>
            <button className="btn primary" onClick={() => onBrowse("resourcepack")}>
              <Icon name="plus" /> {t("Добавить ресурспаки")}
            </button>
          </div>
          <ContentList
            items={instance.resourcepacks}
            disabled={state !== "stopped"}
            onRemove={(id) => removeContent("resourcepacks", id)}
            onOpen={(m) => setOpened({ kind: "resourcepack", source: m.source, projectId: m.projectId })}
          />
        </>
      )}

      {opened && (
        <ModPageModal
          kind={opened.kind}
          source={opened.source}
          projectId={opened.projectId}
          instance={instance}
          stateOf={() => "installed"}
          onClose={() => setOpened(null)}
        />
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
          {logs.length ? logs.join("\n") : t("Журнал появится после запуска игры.")}
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
              .then(() => notify(t("Исправляю сборку…")))
              .catch((e) => notify(errorText(e), true));
          }}
          notify={notify}
        />
      )}
    </div>
  );
}
