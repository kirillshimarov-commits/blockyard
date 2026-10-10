import { useEffect, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { Account, api, DeviceCode, errorText } from "../api";

interface Props {
  onClose: () => void;
  onDone: (account: Account) => void;
}

/** Device code login: the user enters a short code at microsoft.com/link. */
export default function MicrosoftLoginModal({ onClose, onDone }: Props) {
  const [code, setCode] = useState<DeviceCode | null>(null);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const c = await api.startMicrosoftLogin();
        if (cancelled) return;
        setCode(c);
        const account = await api.finishMicrosoftLogin(c);
        if (!cancelled) onDone(account);
      } catch (e) {
        if (!cancelled) setError(errorText(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [onDone]);

  async function copy() {
    if (!code) return;
    await navigator.clipboard.writeText(code.userCode).catch(() => {});
    setCopied(true);
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <h2>Вход через Microsoft</h2>
        {error ? (
          <div className="error-box">{error}</div>
        ) : !code ? (
          <div className="row gap-sm muted">
            <span className="spinner" /> Подключаюсь к Microsoft…
          </div>
        ) : (
          <>
            <p className="muted">
              1. Откройте страницу входа Microsoft.
              <br />
              2. Введите этот код и войдите в аккаунт, на котором куплен Minecraft.
            </p>
            <button className="device-code" onClick={copy} title="Скопировать">
              {code.userCode}
            </button>
            <p className="muted small center-text">{copied ? "Код скопирован" : "Нажмите на код, чтобы скопировать"}</p>
            <div className="row gap-sm muted small">
              <span className="spinner" /> Жду, пока вы войдёте…
            </div>
          </>
        )}
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            {error ? "Закрыть" : "Отмена"}
          </button>
          {code && !error && (
            <button
              className="btn primary"
              onClick={() => {
                copy();
                openUrl(code.verificationUri);
              }}
            >
              Скопировать код и открыть страницу
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
