import { useEffect, useMemo, useState } from "react";
import { api, errorText, Instance, Loader, LOADER_NAMES, LoaderVersion, McVersion } from "../api";

interface Props {
  onClose: () => void;
  onCreated: (inst: Instance) => void;
}

type LoaderChoice = Loader["type"];
const LOADERS: LoaderChoice[] = ["fabric", "forge", "neoforge", "quilt", "vanilla"];

export default function CreateInstanceModal({ onClose, onCreated }: Props) {
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [showSnapshots, setShowSnapshots] = useState(false);
  const [gameVersion, setGameVersion] = useState("");
  const [loaderType, setLoaderType] = useState<LoaderChoice>("fabric");
  const [loaders, setLoaders] = useState<LoaderVersion[]>([]);
  const [loadingLoaders, setLoadingLoaders] = useState(false);
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
    setLoaders([]);
    setLoaderVersion("");
    if (loaderType === "vanilla" || !gameVersion) return;
    let cancelled = false;
    setLoadingLoaders(true);
    api
      .listLoaderVersions(loaderType, gameVersion)
      .then((list) => {
        if (cancelled) return;
        setLoaders(list);
        const pick = list.find((l) => l.recommended) ?? list.find((l) => l.stable) ?? list[0];
        setLoaderVersion(pick?.version ?? "");
      })
      .catch(() => !cancelled && setLoaders([]))
      .finally(() => !cancelled && setLoadingLoaders(false));
    return () => {
      cancelled = true;
    };
  }, [gameVersion, loaderType]);

  useEffect(() => {
    if (!nameTouched && gameVersion) setName(`${LOADER_NAMES[loaderType]} ${gameVersion}`);
  }, [gameVersion, loaderType, nameTouched]);

  const shown = useMemo(
    () => versions.filter((v) => v.type === "release" || (showSnapshots && v.type === "snapshot")),
    [versions, showSnapshots],
  );
  const needsLoader = loaderType !== "vanilla";
  const unavailable = needsLoader && gameVersion !== "" && !loadingLoaders && loaders.length === 0;

  async function create() {
    setBusy(true);
    setError("");
    try {
      const loader: Loader = needsLoader ? { type: loaderType, version: loaderVersion } : { type: "vanilla" };
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
            {LOADERS.map((l) => (
              <button key={l} className={loaderType === l ? "on" : ""} onClick={() => setLoaderType(l)}>
                {LOADER_NAMES[l]}
              </button>
            ))}
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
          {needsLoader && (
            <label className="field grow">
              <span>Версия {LOADER_NAMES[loaderType]}</span>
              <select value={loaderVersion} onChange={(e) => setLoaderVersion(e.target.value)} disabled={!loaders.length}>
                {loadingLoaders && <option>Загрузка…</option>}
                {loaders.map((l) => (
                  <option key={l.version} value={l.version}>
                    {l.version.startsWith(`${gameVersion}-`) ? l.version.slice(gameVersion.length + 1) : l.version}
                    {l.recommended ? " (рекомендуется)" : l.stable ? "" : " (beta)"}
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

        {unavailable && <div className="hint">{LOADER_NAMES[loaderType]} для этой версии не найден.</div>}
        {(loaderType === "forge" || loaderType === "neoforge") && (
          <p className="muted small">
            Первый запуск сборки с {LOADER_NAMES[loaderType]} дольше обычного: лаунчер устанавливает загрузчик.
          </p>
        )}
        {error && <div className="error-box">{error}</div>}

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            Отмена
          </button>
          <button
            className="btn primary"
            disabled={busy || !gameVersion || !name.trim() || (needsLoader && !loaderVersion)}
            onClick={create}
          >
            Создать
          </button>
        </div>
      </div>
    </div>
  );
}
