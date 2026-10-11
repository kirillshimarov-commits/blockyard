import { useMemo, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { api, errorText, Instance, loaderLabel } from "../api";
import type { GameStatus } from "../App";
import CreateInstanceModal from "../components/CreateInstanceModal";
import { Icon } from "../components/Icon";
import InstanceCover from "../components/InstanceCover";

interface Props {
  instances: Instance[];
  status: Record<string, GameStatus>;
  onOpen: (id: string) => void;
  onPlay: (id: string) => void;
  onJoinFriend: () => void;
  onCreated: (inst: Instance) => void;
  notify: (text: string, error?: boolean) => void;
}

export default function Library({ instances, status, onOpen, onPlay, onJoinFriend, onCreated, notify }: Props) {
  const [creating, setCreating] = useState(false);

  // Named groups first (alphabetically), ungrouped instances last.
  const groups = useMemo(() => {
    const map = new Map<string, Instance[]>();
    for (const inst of instances) {
      const g = inst.settings?.group?.trim() ?? "";
      map.set(g, [...(map.get(g) ?? []), inst]);
    }
    return [...map.entries()].sort(([a], [b]) => (a === "" ? 1 : b === "" ? -1 : a.localeCompare(b)));
  }, [instances]);

  async function importPack() {
    try {
      const path = await open({
        multiple: false,
        filters: [{ name: "Модпак (.mrpack, CurseForge .zip)", extensions: ["mrpack", "zip"] }],
      });
      if (typeof path !== "string") return;
      const inst = await api.importModpack(path);
      notify(`Импортирую «${inst.name}»…`);
      onCreated(inst);
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Библиотека</h1>
          <p className="muted">Ваши сборки Minecraft</p>
        </div>
        <div className="row gap-sm">
          <button className="btn" title="Зайти в мир друга по коду-приглашению" onClick={onJoinFriend}>
            <Icon name="users" /> Подключиться к другу
          </button>
          <button className="btn" title="Открыть .mrpack или модпак CurseForge из файла" onClick={importPack}>
            <Icon name="upload" /> Импорт
          </button>
          <button className="btn primary" onClick={() => setCreating(true)}>
            <Icon name="plus" /> Новая сборка
          </button>
        </div>
      </header>

      {instances.length === 0 ? (
        <div className="empty">
          <Icon name="box" size={48} />
          <h3>Сборок пока нет</h3>
          <p className="muted">Создайте первую сборку: выберите версию игры и Fabric, чтобы ставить моды.</p>
          <button className="btn primary" onClick={() => setCreating(true)}>
            <Icon name="plus" /> Создать сборку
          </button>
        </div>
      ) : (
        groups.map(([group, list]) => (
          <section key={group} className="library-group">
            {groups.length > 1 && <h3>{group || "Без группы"}</h3>}
            <div className="instance-grid">
              {list.map((inst) => {
            const state = status[inst.id]?.state ?? "stopped";
            return (
              <div key={inst.id} className="instance-card" onClick={() => onOpen(inst.id)}>
                <InstanceCover instance={inst} />
                <div className="instance-info">
                  <div className="instance-name truncate">{inst.name}</div>
                  <div className="muted small">
                    {inst.gameVersion} · {loaderLabel(inst.loader)}
                  </div>
                  <div className="muted small">
                    {status[inst.id]?.state === "installing"
                      ? "Установка…"
                      : inst.mods.length
                        ? `${inst.mods.length} модов`
                        : "Без модов"}
                  </div>
                </div>
                <button
                  className="play-fab"
                  disabled={state !== "stopped"}
                  title="Играть"
                  onClick={(e) => {
                    e.stopPropagation();
                    if (inst.pending.length > 0) {
                      notify(`В сборке не хватает модов (${inst.pending.length}) — скачайте их перед игрой`, true);
                      onOpen(inst.id);
                    } else {
                      onPlay(inst.id);
                    }
                  }}
                >
                  {state === "stopped" ? <Icon name="play" /> : <span className="spinner" />}
                </button>
              </div>
            );
              })}
            </div>
          </section>
        ))
      )}

      {creating && (
        <CreateInstanceModal
          onClose={() => setCreating(false)}
          onCreated={(inst) => {
            setCreating(false);
            onCreated(inst);
          }}
        />
      )}
    </div>
  );
}
