import { useEffect } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api, Instance } from "../api";
import { t } from "../i18n";
import { Icon } from "./Icon";

interface Props {
  instance: Instance;
  onChanged: (inst: Instance) => void;
  onClose: () => void;
  /** Set when the modal was opened from the Play button. */
  onPlay?: () => void;
}

const POLL_MS = 2000;

/** Mods whose authors forbid launcher downloads: the user grabs them in the browser, we pick them up. */
export default function PendingDownloadsModal({ instance, onChanged, onClose, onPlay }: Props) {
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
        <h2>{pending.length ? t("Нужно скачать вручную") : t("Готово!")}</h2>
        {pending.length > 0 ? (
          <>
            {onPlay && <div className="warning-box">{t("Без этих модов игра, скорее всего, вылетит.")}</div>}
            <p className="muted">
              {t(
                "Авторы этих модов запретили скачивание через лаунчеры. Нажмите «Скачать» и сохраните файл в папку «Загрузки», а лаунчер сам перенесёт его в сборку.",
              )}
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
                    <Icon name="download" /> {t("Скачать")}
                  </button>
                </div>
              ))}
            </div>
            <div className="row gap-sm muted small">
              <span className="spinner" /> {t("Ждём файлы в папке «Загрузки»…")}
            </div>
          </>
        ) : (
          <p className="muted">{t("Все моды перенесены в сборку.")}</p>
        )}
        <div className="modal-actions">
          {onPlay ? (
            <>
              <button className="btn" onClick={onClose}>
                {t("Отмена")}
              </button>
              <button className={`btn ${pending.length ? "danger" : "primary"}`} onClick={onPlay}>
                {pending.length ? t("Играть без них") : t("Играть")}
              </button>
            </>
          ) : (
            <button className={`btn ${pending.length ? "" : "primary"}`} onClick={onClose}>
              {pending.length ? t("Скачаю позже") : t("Закрыть")}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
