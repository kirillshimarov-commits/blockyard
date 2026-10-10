import { useState } from "react";
import { api, errorText, Settings } from "../api";

interface Props {
  settings: Settings;
  onSaved: (s: Settings) => void;
  notify: (text: string, error?: boolean) => void;
}

export default function SettingsPage({ settings, onSaved, notify }: Props) {
  const [draft, setDraft] = useState(settings);
  const valid = /^[A-Za-z0-9_]{3,16}$/.test(draft.username);

  async function save() {
    try {
      await api.saveSettings(draft);
      onSaved(draft);
      notify("Настройки сохранены");
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  return (
    <div className="page narrow">
      <header className="page-header">
        <div>
          <h1>Настройки</h1>
        </div>
      </header>

      <section className="card">
        <h3>Аккаунт</h3>
        <label className="field">
          <span>Ник в игре</span>
          <input
            value={draft.username}
            maxLength={16}
            onChange={(e) => setDraft({ ...draft, username: e.target.value })}
          />
        </label>
        {!valid && <div className="hint">От 3 до 16 символов: латинские буквы, цифры и _</div>}
        <p className="muted small">
          Офлайн-режим: можно играть в одиночной игре и на серверах без проверки лицензии.
        </p>
      </section>

      <section className="card">
        <h3>Новые сборки</h3>
        <label className="field">
          <span>Оперативная память по умолчанию: {draft.memoryMb} МБ</span>
          <input
            type="range"
            min={1024}
            max={16384}
            step={512}
            value={draft.memoryMb}
            onChange={(e) => setDraft({ ...draft, memoryMb: Number(e.target.value) })}
          />
        </label>
      </section>

      <div className="row end">
        <button className="btn primary" disabled={!valid} onClick={save}>
          Сохранить
        </button>
      </div>
    </div>
  );
}
