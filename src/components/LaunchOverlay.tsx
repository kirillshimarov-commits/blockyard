import { useEffect, useState } from "react";
import { Instance } from "../api";
import type { GameStatus } from "../App";
import InstanceCover from "./InstanceCover";

/** Full-screen "starting the game" screen; fades out once the game window is up. */
export default function LaunchOverlay({
  instance,
  status,
  onClose,
}: {
  instance: Instance;
  status?: GameStatus;
  onClose: () => void;
}) {
  const [leaving, setLeaving] = useState(false);
  const state = status?.state ?? "installing";
  const progress = status?.progress;

  useEffect(() => {
    if (state === "installing") return;
    // Running: let the player see that it worked. Stopped: an error toast or crash card takes over.
    setLeaving(true);
    const t = window.setTimeout(onClose, state === "running" ? 1400 : 400);
    return () => window.clearTimeout(t);
  }, [state, onClose]);

  const percent = progress?.total ? Math.round((progress.current / progress.total) * 100) : null;

  return (
    <div className={`launch-overlay ${leaving ? "leaving" : ""}`}>
      <div className="launch-art">
        <InstanceCover instance={instance} large />
      </div>
      <div className="launch-title">{instance.name}</div>
      <div className="launch-stage">
        {state === "running" ? "Приятной игры!" : (progress?.stage ?? "Подготовка к запуску…")}
      </div>
      <div className="launch-bar">
        <div
          className={percent === null ? "indeterminate" : ""}
          style={percent === null ? undefined : { width: `${percent}%` }}
        />
      </div>
      <button className="link muted small" onClick={onClose}>
        Скрыть
      </button>
    </div>
  );
}
