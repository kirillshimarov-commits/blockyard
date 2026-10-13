import { useEffect, useState } from "react";
import { Instance } from "../api";
import type { GameStatus } from "../App";
import InstanceCover from "./InstanceCover";
import { t, tStage } from "../i18n";

/** Full-screen "starting the game" screen; fades out once the game window is up. */
export default function LaunchOverlay({
  instance,
  status,
  waitForWindow,
  embedded,
  onClose,
}: {
  instance: Instance;
  status?: GameStatus;
  /** The game will appear inside the launcher: stay until its window is there. */
  waitForWindow: boolean;
  embedded: boolean;
  onClose: () => void;
}) {
  const [leaving, setLeaving] = useState(false);
  const state = status?.state ?? "installing";
  const progress = status?.progress;

  const loadingGame = state === "running" && waitForWindow && !embedded;

  useEffect(() => {
    if (state === "installing") return;
    if (loadingGame) {
      // Never hang on this screen if the window cannot be embedded for some reason.
      const timer = window.setTimeout(onClose, 180_000);
      return () => window.clearTimeout(timer);
    }
    // Running: let the player see that it worked. Stopped: an error toast or crash card takes over.
    setLeaving(true);
    const timer = window.setTimeout(onClose, state === "running" && !embedded ? 1400 : 400);
    return () => window.clearTimeout(timer);
  }, [state, loadingGame, embedded, onClose]);

  const percent = progress?.total ? Math.round((progress.current / progress.total) * 100) : null;

  return (
    <div className={`launch-overlay ${leaving ? "leaving" : ""}`}>
      <div className="launch-art">
        <InstanceCover instance={instance} large />
      </div>
      <div className="launch-title">{instance.name}</div>
      <div className="launch-stage">
        {loadingGame
          ? t("Загружаем игру…")
          : state === "running"
            ? t("Приятной игры!")
            : tStage(progress?.stage ?? "Подготовка к запуску…")}
      </div>
      <div className="launch-bar">
        <div
          className={percent === null || loadingGame ? "indeterminate" : ""}
          style={percent === null || loadingGame ? undefined : { width: `${percent}%` }}
        />
      </div>
      <button className="link muted small" onClick={onClose}>
        {t("Скрыть")}
      </button>
    </div>
  );
}
