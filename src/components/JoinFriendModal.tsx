import { useEffect, useMemo, useState } from "react";
import { api, errorText, Instance, InviteInfo, loaderLabel } from "../api";

interface Props {
  instances: Instance[];
  onClose: () => void;
  onJoined: (instanceId: string) => void;
  onCreated: (inst: Instance) => void;
}

/** Guest side: paste a friend's code, pick a matching instance, start straight into their world. */
export default function JoinFriendModal({ instances, onClose, onJoined, onCreated }: Props) {
  const [code, setCode] = useState("");
  const [info, setInfo] = useState<InviteInfo | null>(null);
  const [error, setError] = useState("");
  const [instanceId, setInstanceId] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setInfo(null);
    setError("");
    if (!code.trim()) return;
    api
      .tunnelDecode(code)
      .then(setInfo)
      .catch((e) => setError(errorText(e)));
  }, [code]);

  // Instances that can join: same game version and mod loader.
  const matching = useMemo(
    () =>
      info
        ? instances.filter((i) => i.gameVersion === info.gameVersion && i.loader.type === info.loader.type)
        : [],
    [info, instances],
  );
  useEffect(() => setInstanceId(matching[0]?.id ?? ""), [matching]);

  async function join(id: string) {
    setBusy(true);
    setError("");
    try {
      await api.tunnelJoin(code, id);
      onJoined(id);
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  async function createAndJoin() {
    if (!info) return;
    setBusy(true);
    try {
      const inst = await api.createInstance(`${info.world} (у друга)`, info.gameVersion, info.loader);
      onCreated(inst);
      await join(inst.id);
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <h2>Подключиться к другу</h2>
        <label className="field">
          <span>Код-приглашение</span>
          <textarea
            rows={3}
            placeholder="Вставьте код, который прислал друг (начинается с BY1-)"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoFocus
          />
        </label>

        {info && (
          <div className="card join-info">
            <div className="mod-title">{info.world}</div>
            <div className="muted small">
              Minecraft {info.gameVersion} · {loaderLabel(info.loader)}
            </div>
            {matching.length > 0 ? (
              <label className="field section-gap">
                <span>Играть в сборке</span>
                <select value={instanceId} onChange={(e) => setInstanceId(e.target.value)}>
                  {matching.map((i) => (
                    <option key={i.id} value={i.id}>
                      {i.name}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <p className="hint">
                У вас нет сборки с такой же версией и загрузчиком. Её можно создать автоматически. Если в мире друга
                есть моды, их нужно будет поставить такие же.
              </p>
            )}
          </div>
        )}
        {error && <div className="error-box">{error}</div>}

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            Отмена
          </button>
          {info && matching.length > 0 && (
            <button className="btn primary" disabled={busy || !instanceId} onClick={() => join(instanceId)}>
              {busy ? <span className="spinner" /> : null} Подключиться и играть
            </button>
          )}
          {info && matching.length === 0 && (
            <button className="btn primary" disabled={busy} onClick={createAndJoin}>
              {busy ? <span className="spinner" /> : null} Создать сборку и подключиться
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
