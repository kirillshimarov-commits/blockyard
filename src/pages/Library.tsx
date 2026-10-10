import { useState } from "react";
import { Instance, loaderLabel } from "../api";
import type { GameStatus } from "../App";
import CreateInstanceModal from "../components/CreateInstanceModal";
import { Icon } from "../components/Icon";
import InstanceCover from "../components/InstanceCover";

interface Props {
  instances: Instance[];
  status: Record<string, GameStatus>;
  onOpen: (id: string) => void;
  onPlay: (id: string) => void;
  onCreated: (inst: Instance) => void;
  notify: (text: string, error?: boolean) => void;
}

export default function Library({ instances, status, onOpen, onPlay, onCreated }: Props) {
  const [creating, setCreating] = useState(false);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Библиотека</h1>
          <p className="muted">Ваши сборки Minecraft</p>
        </div>
        <button className="btn primary" onClick={() => setCreating(true)}>
          <Icon name="plus" /> Новая сборка
        </button>
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
        <div className="instance-grid">
          {instances.map((inst) => {
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
                    onPlay(inst.id);
                  }}
                >
                  {state === "stopped" ? <Icon name="play" /> : <span className="spinner" />}
                </button>
              </div>
            );
          })}
        </div>
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
