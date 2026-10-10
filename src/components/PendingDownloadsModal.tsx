import { useEffect } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api, Instance } from "../api";
import { Icon } from "./Icon";

interface Props {
  instance: Instance;
  onChanged: (inst: Instance) => void;
  onClose: () => void;
}

const POLL_MS = 2000;

/** Mods whose authors forbid launcher downloads: the user grabs them in the browser, we pick them up. */
export default function PendingDownloadsModal({ instance, onChanged, onClose }: Props) {
  const pending = instance.pending;

  useEffect(() => {
    const timer = window.setInterval(async () => {
      try {
        const updated = await api.importPendingMods(instance.id);
        if (updated.pending.length !== pending.length) onChanged(updated);
      } catch {
        // Keep polling; a failed check is retried on the next tick.
      }
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [instance.id, pending.length, onChanged]);

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <h2>{pending.length ? "Нужно скачать вручную" : "Готово!"}</h2>
        {pending.length > 0 ? (
          <>
            <p className="muted">
              Авторы этих модов запретили скачивание через лаунчеры. Нажмите «Скачать» и сохраните файл в папку
              «Загрузки», а лаунчер сам перенесёт его в сборку.
            </p>
            <div className="mod-list pending-list">
              {pending.map((p) => (
                <div key={p.projectId} className="mod-row">
                  {p.iconUrl ? <img src={p.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
                  <div className="grow">
                    <div className="mod-title">{p.title}</div>
                    <div className="muted small truncate">{p.fileName}</div>
                  </div>
                  <button className="btn primary" onClick={() => openUrl(p.pageUrl)}>
                    <Icon name="download" /> Скачать
                  </button>
                </div>
              ))}
            </div>
            <div className="row gap-sm muted small">
              <span className="spinner" /> Ждём файлы в папке «Загрузки»…
            </div>
          </>
        ) : (
          <p className="muted">Все моды перенесены в сборку.</p>
        )}
        <div className="modal-actions">
          <button className={`btn ${pending.length ? "" : "primary"}`} onClick={onClose}>
            {pending.length ? "Скачаю позже" : "Закрыть"}
          </button>
        </div>
      </div>
    </div>
  );
}
