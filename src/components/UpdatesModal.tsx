import { useEffect, useState } from "react";
import { api, errorText, Instance, ModUpdate } from "../api";
import { t } from "../i18n";

interface Props {
  instance: Instance;
  onChanged: (inst: Instance) => void;
  onClose: () => void;
  notify: (text: string, error?: boolean) => void;
}

export default function UpdatesModal({ instance, onChanged, onClose, notify }: Props) {
  const [updates, setUpdates] = useState<ModUpdate[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .checkModUpdates(instance.id)
      .then((list) => {
        setUpdates(list);
        setSelected(new Set(list.map((u) => u.projectId)));
      })
      .catch((e) => setError(errorText(e)));
  }, [instance.id]);

  function toggle(id: string) {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function apply() {
    setBusy(true);
    try {
      onChanged(await api.applyModUpdates(instance.id, [...selected]));
      notify(t("Обновлено модов: {n}", { n: selected.size }));
      onClose();
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <h2>{t("Обновления модов")}</h2>
        {error && <div className="error-box">{error}</div>}
        {!updates && !error && (
          <div className="row gap-sm muted">
            <span className="spinner" /> {t("Проверяю {n} модов…", { n: instance.mods.length })}
          </div>
        )}
        {updates && updates.length === 0 && <p className="muted">{t("Все моды последней версии 🎉")}</p>}
        {updates && updates.length > 0 && (
          <div className="mod-list pending-list">
            {updates.map((u) => (
              <label key={u.projectId} className="mod-row update-row">
                <input type="checkbox" checked={selected.has(u.projectId)} onChange={() => toggle(u.projectId)} />
                <div className="grow">
                  <div className="mod-title">{u.title}</div>
                  <div className="muted small truncate">
                    {u.currentFile} → {u.newFile}
                  </div>
                </div>
              </label>
            ))}
          </div>
        )}
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            {updates?.length ? t("Отмена") : t("Закрыть")}
          </button>
          {updates && updates.length > 0 && (
            <button className="btn primary" disabled={busy || selected.size === 0} onClick={apply}>
              {busy ? <span className="spinner" /> : null}
              {t("Обновить ({n})", { n: selected.size })}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
