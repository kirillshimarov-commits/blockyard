import { useEffect, useMemo, useRef, useState } from "react";
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
  onStop: (id: string) => void;
  onJoinFriend: () => void;
  onBrowseModpacks: () => void;
  onCreated: (inst: Instance) => void;
  notify: (text: string, error?: boolean) => void;
}

const SELECTED_KEY = "home.selected";

function lastPlayedLabel(ts?: number | null): string {
  if (!ts) return "Ещё не запускалась";
  const days = Math.floor((Date.now() / 1000 - ts) / 86400);
  if (days <= 0) return "Играли сегодня";
  if (days === 1) return "Играли вчера";
  return `Играли ${new Date(ts * 1000).toLocaleDateString("ru-RU", { day: "numeric", month: "long" })}`;
}

/** Console-style home: a row of tiles and a large card for the selected instance. */
export default function Home(props: Props) {
  const { instances, status, onOpen, onPlay, onStop, onJoinFriend, onBrowseModpacks, onCreated, notify } = props;
  const [creating, setCreating] = useState(false);
  const [menu, setMenu] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(() => {
    try {
      return localStorage.getItem(SELECTED_KEY);
    } catch {
      return null;
    }
  });
  const row = useRef<HTMLDivElement>(null);

  // Most recently played first, then newest.
  const sorted = useMemo(
    () =>
      [...instances].sort(
        (a, b) => (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0) || b.createdAt - a.createdAt,
      ),
    [instances],
  );
  const selected = sorted.find((i) => i.id === selectedId) ?? sorted[0];
  const state = selected ? (status[selected.id]?.state ?? "stopped") : "stopped";

  function select(id: string) {
    setSelectedId(id);
    setMenu(false);
    try {
      localStorage.setItem(SELECTED_KEY, id);
    } catch {
      /* not essential */
    }
  }

  function play(inst: Instance) {
    if (inst.pending.length > 0) {
      notify(`В сборке не хватает модов (${inst.pending.length}) — скачайте их перед игрой`, true);
      onOpen(inst.id);
    } else {
      onPlay(inst.id);
    }
  }

  // Keep the selected tile in view.
  useEffect(() => {
    if (!selected) return;
    row.current
      ?.querySelector<HTMLElement>(`[data-id="${selected.id}"]`)
      ?.scrollIntoView({ behavior: "smooth", inline: "nearest", block: "nearest" });
  }, [selected?.id]);

  // Arrow keys move between tiles, Enter starts the game, like on a console.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (creating || !selected) return;
      const target = e.target as HTMLElement;
      if (target.closest("input, textarea, select, .modal")) return;
      const i = sorted.findIndex((x) => x.id === selected.id);
      if (e.key === "ArrowRight" && i < sorted.length - 1) select(sorted[i + 1].id);
      else if (e.key === "ArrowLeft" && i > 0) select(sorted[i - 1].id);
      else if (e.key === "Enter" && state === "stopped" && target.tagName !== "BUTTON") play(selected);
      else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  async function importPack() {
    setMenu(false);
    try {
      const path = await open({
        multiple: false,
        filters: [{ name: "Модпак (.mrpack, CurseForge .zip)", extensions: ["mrpack", "zip"] }],
      });
      if (typeof path !== "string") return;
      const inst = await api.importModpack(path);
      notify(`Импортирую «${inst.name}»…`);
      onCreated(inst);
      select(inst.id);
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  const createModal = creating && (
    <CreateInstanceModal
      onClose={() => setCreating(false)}
      onCreated={(inst) => {
        setCreating(false);
        onCreated(inst);
        select(inst.id);
      }}
    />
  );

  if (!selected) {
    return (
      <div className="home home-welcome">
        <h1 className="welcome-title">
          Добро пожаловать в <span className="accent-text">Blockyard</span>
        </h1>
        <p className="welcome-sub">Во что сегодня играем?</p>
        <div className="orbs">
          <button className="orb" onClick={() => setCreating(true)}>
            <span className="orb-icon">
              <Icon name="plus" size={26} />
            </span>
            <span className="orb-label">Новая сборка</span>
          </button>
          <button className="orb" onClick={onBrowseModpacks}>
            <span className="orb-icon">
              <Icon name="layers" size={26} />
            </span>
            <span className="orb-label">Готовые сборки</span>
          </button>
          <button className="orb" onClick={onJoinFriend}>
            <span className="orb-icon">
              <Icon name="users" size={26} />
            </span>
            <span className="orb-label">Сетевая игра</span>
          </button>
          <button className="orb" onClick={importPack}>
            <span className="orb-icon">
              <Icon name="upload" size={26} />
            </span>
            <span className="orb-label">Импорт</span>
          </button>
        </div>
        {createModal}
      </div>
    );
  }

  const progress = status[selected.id]?.progress;

  return (
    <div className="home" onClick={() => setMenu(false)}>
      <div className="tile-row" ref={row}>
        <button className="tile tile-action" title="Сетевая игра: зайти в мир друга" onClick={onJoinFriend}>
          <Icon name="users" size={26} />
        </button>
        {sorted.map((inst, i) => (
          <button
            key={inst.id}
            data-id={inst.id}
            className={`tile ${inst.id === selected.id ? "selected" : ""}`}
            style={{ animationDelay: `${Math.min(i, 10) * 45}ms` }}
            title={inst.name}
            onClick={() => select(inst.id)}
            onDoubleClick={() => onOpen(inst.id)}
          >
            <InstanceCover instance={inst} />
            {status[inst.id]?.state && status[inst.id].state !== "stopped" && (
              <span className="tile-state" data-state={status[inst.id].state} />
            )}
          </button>
        ))}
        <button className="tile tile-add" title="Новая сборка" onClick={() => setCreating(true)}>
          <Icon name="plus" size={24} />
        </button>
      </div>

      <div className="tile-caption" key={`cap-${selected.id}`}>
        <span className="pill-tag">{selected.loader.type === "vanilla" ? "vanilla" : selected.loader.type}</span>
        <span className="truncate">{selected.name}</span>
      </div>

      <section className="hero" key={selected.id}>
        <div className="hero-info">
          <h1 className="hero-title">{selected.name}</h1>
          <div className="hero-meta">
            Minecraft {selected.gameVersion} · {loaderLabel(selected.loader)}
          </div>
          <div className="hero-chips">
            <span className="chip">
              <span className="dot" data-state={state} />
              {state === "running" ? "Игра запущена" : state === "installing" ? "Запуск…" : lastPlayedLabel(selected.lastPlayed)}
            </span>
            <span className="chip">{selected.mods.length ? `${selected.mods.length} модов` : "Без модов"}</span>
            {selected.shaders.length > 0 && <span className="chip">Шейдеры: {selected.shaders.length}</span>}
            {selected.pending.length > 0 && <span className="chip warn">Скачать вручную: {selected.pending.length}</span>}
          </div>
          {state === "installing" && progress && (
            <div className="hero-progress">
              <div className="muted small truncate">{progress.stage}</div>
              <div className="bar">
                <div style={{ width: `${progress.total ? (progress.current / progress.total) * 100 : 0}%` }} />
              </div>
            </div>
          )}

          <div className="hero-actions">
            {state === "running" ? (
              <button className="pill-btn" onClick={() => onStop(selected.id)}>
                Остановить <Icon name="stop" size={16} />
              </button>
            ) : (
              <button className="pill-btn" disabled={state !== "stopped"} onClick={() => play(selected)}>
                {state === "installing" ? (
                  <>
                    Запуск <span className="spinner" />
                  </>
                ) : (
                  <>
                    Играть <Icon name="play" size={16} />
                  </>
                )}
              </button>
            )}
            <div className="menu-anchor">
              <button
                className="round-btn"
                title="Ещё"
                onClick={(e) => {
                  e.stopPropagation();
                  setMenu((m) => !m);
                }}
              >
                <span className="dots">•••</span>
              </button>
              {menu && (
                <div className="pop-menu" onClick={(e) => e.stopPropagation()}>
                  <button onClick={() => onOpen(selected.id)}>
                    <Icon name="puzzle" /> Моды и настройки сборки
                  </button>
                  <button onClick={onJoinFriend}>
                    <Icon name="users" /> Подключиться к другу
                  </button>
                  <button onClick={importPack}>
                    <Icon name="upload" /> Импорт модпака
                  </button>
                  <button
                    onClick={() => {
                      setMenu(false);
                      setCreating(true);
                    }}
                  >
                    <Icon name="plus" /> Новая сборка
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
        <div className="hero-art" onClick={() => onOpen(selected.id)} title="Открыть сборку">
          <InstanceCover instance={selected} large />
        </div>
      </section>

      {createModal}
    </div>
  );
}
