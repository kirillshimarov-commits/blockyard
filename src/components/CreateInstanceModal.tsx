import { useEffect, useMemo, useState } from "react";
import { api, errorText, FabricLoader, Instance, McVersion } from "../api";

interface Props {
  onClose: () => void;
  onCreated: (inst: Instance) => void;
}

export default function CreateInstanceModal({ onClose, onCreated }: Props) {
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [showSnapshots, setShowSnapshots] = useState(false);
  const [gameVersion, setGameVersion] = useState("");
  const [loaderType, setLoaderType] = useState<"fabric" | "vanilla">("fabric");
  const [loaders, setLoaders] = useState<FabricLoader[]>([]);
  const [loaderVersion, setLoaderVersion] = useState("");
  const [name, setName] = useState("");
  const [nameTouched, setNameTouched] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .listVersions()
      .then((list) => {
        setVersions(list);
        const latest = list.find((v) => v.type === "release");
        if (latest) setGameVersion(latest.id);
      })
      .catch((e) => setError(`Не удалось получить список версий: ${errorText(e)}`));
  }, []);

  useEffect(() => {
    if (loaderType !== "fabric" || !gameVersion) return;
    setLoaders([]);
    setLoaderVersion("");
    api
      .listFabricLoaders(gameVersion)
      .then((list) => {
        setLoaders(list);
        setLoaderVersion((list.find((l) => l.stable) ?? list[0])?.version ?? "");
      })
      .catch(() => setLoaders([]));
  }, [gameVersion, loaderType]);

  useEffect(() => {
    if (!nameTouched && gameVersion) {
      setName(`${loaderType === "fabric" ? "Fabric" : "Vanilla"} ${gameVersion}`);
    }
  }, [gameVersion, loaderType, nameTouched]);

  const shown = useMemo(
    () => versions.filter((v) => v.type === "release" || (showSnapshots && v.type === "snapshot")),
    [versions, showSnapshots],
  );
  const fabricUnavailable = loaderType === "fabric" && gameVersion !== "" && loaders.length === 0;

  async function create() {
    setBusy(true);
    setError("");
    try {
      const loader =
        loaderType === "fabric" ? { type: "fabric" as const, version: loaderVersion } : { type: "vanilla" as const };
      onCreated(await api.createInstance(name, gameVersion, loader));
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <h2>Новая сборка</h2>

        <label className="field">
          <span>Название</span>
          <input
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setNameTouched(true);
            }}
          />
        </label>

        <div className="field">
          <span>Загрузчик модов</span>
          <div className="segmented">
            <button className={loaderType === "fabric" ? "on" : ""} onClick={() => setLoaderType("fabric")}>
              Fabric
            </button>
            <button className={loaderType === "vanilla" ? "on" : ""} onClick={() => setLoaderType("vanilla")}>
              Vanilla (без модов)
            </button>
          </div>
        </div>

        <div className="row">
          <label className="field grow">
            <span>Версия Minecraft</span>
            <select value={gameVersion} onChange={(e) => setGameVersion(e.target.value)}>
              {shown.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.id}
                  {v.type === "snapshot" ? " (снапшот)" : ""}
                </option>
              ))}
            </select>
          </label>
          {loaderType === "fabric" && (
            <label className="field grow">
              <span>Версия Fabric</span>
              <select value={loaderVersion} onChange={(e) => setLoaderVersion(e.target.value)} disabled={!loaders.length}>
                {loaders.map((l) => (
                  <option key={l.version} value={l.version}>
                    {l.version}
                    {l.stable ? "" : " (beta)"}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>

        <label className="checkbox">
          <input type="checkbox" checked={showSnapshots} onChange={(e) => setShowSnapshots(e.target.checked)} />
          Показывать снапшоты
        </label>

        {fabricUnavailable && <div className="hint">Fabric для этой версии не найден.</div>}
        {error && <div className="error-box">{error}</div>}

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            Отмена
          </button>
          <button
            className="btn primary"
            disabled={busy || !gameVersion || !name.trim() || (loaderType === "fabric" && !loaderVersion)}
            onClick={create}
          >
            Создать
          </button>
        </div>
      </div>
    </div>
  );
}
