import { useCallback, useEffect, useRef, useState } from "react";
import { Account, api, errorText, Settings } from "../api";
import MicrosoftLoginModal from "../components/MicrosoftLoginModal";
import { Icon } from "../components/Icon";
import {
  ACCENTS,
  Appearance,
  Background,
  compressImage,
  DEFAULT_APPEARANCE,
  GRADIENTS,
  ThemeMode,
  WindowEffect,
  Layout,
} from "../appearance";

interface Props {
  settings: Settings;
  onSaved: (s: Settings) => void;
  appearance: Appearance;
  activeEffect: string;
  onAppearanceChange: (a: Appearance) => void;
  notify: (text: string, error?: boolean) => void;
}

const THEMES: { value: ThemeMode; label: string }[] = [
  { value: "system", label: "Как в системе" },
  { value: "dark", label: "Тёмная" },
  { value: "light", label: "Светлая" },
];

const EFFECTS: { value: WindowEffect; label: string }[] = [
  { value: "auto", label: "Авто" },
  { value: "mica", label: "Mica" },
  { value: "acrylic", label: "Acrylic" },
  { value: "blur", label: "Размытие" },
  { value: "none", label: "Выкл" },
];

const LAYOUTS: { value: Layout; label: string }[] = [
  { value: "console", label: "Консоль" },
  { value: "classic", label: "Классический" },
];

const BACKGROUNDS: { value: Background; label: string }[] = [
  { value: "window", label: "Прозрачный" },
  { value: "gradient", label: "Градиент" },
  { value: "image", label: "Картинка" },
];

const EFFECT_NAMES: Record<string, string> = {
  mica: "Mica",
  acrylic: "Acrylic",
  blur: "размытие",
  none: "без прозрачности",
};

function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button key={o.value} className={value === o.value ? "on" : ""} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

function Slider({
  label,
  value,
  min,
  max,
  step = 1,
  suffix = "",
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  suffix?: string;
  onChange: (v: number) => void;
}) {
  return (
    <label className="field">
      <span className="row between">
        {label}
        <span className="value">
          {value}
          {suffix}
        </span>
      </span>
      <input type="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}

export default function SettingsPage(props: Props) {
  const { settings, onSaved, appearance: a, activeEffect, onAppearanceChange, notify } = props;
  const [draft, setDraft] = useState(settings);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [msAvailable, setMsAvailable] = useState(false);
  const [loggingIn, setLoggingIn] = useState(false);

  useEffect(() => {
    api.listAccounts().then(setAccounts).catch(() => {});
    api.microsoftAvailable().then(setMsAvailable).catch(() => {});
  }, []);

  async function selectAccount(activeAccount: string | null) {
    const next = { ...settings, activeAccount };
    try {
      await api.saveSettings(next);
      onSaved(next);
      setDraft((d) => ({ ...d, activeAccount }));
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function removeAccount(uuid: string) {
    try {
      const next = await api.removeAccount(uuid);
      setAccounts(await api.listAccounts());
      onSaved(next);
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  const onLoggedIn = useCallback(
    async (account: Account) => {
      setLoggingIn(false);
      setAccounts(await api.listAccounts());
      onSaved({ ...settings, activeAccount: account.uuid });
      notify(`Вы вошли как ${account.username}`);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [settings],
  );
  const fileInput = useRef<HTMLInputElement>(null);
  const valid = /^[A-Za-z0-9_]{3,16}$/.test(draft.username);
  const dirty = draft.username !== settings.username || draft.memoryMb !== settings.memoryMb;

  const set = <K extends keyof Appearance>(key: K, value: Appearance[K]) => onAppearanceChange({ ...a, [key]: value });

  async function save() {
    try {
      const next = { ...draft, activeAccount: settings.activeAccount, embedGame: settings.embedGame };
      await api.saveSettings(next);
      onSaved(next);
      notify("Настройки сохранены");
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function pickImage(file: File | undefined) {
    if (!file) return;
    try {
      const dataUrl = await compressImage(file);
      onAppearanceChange({ ...a, background: "image", backgroundImage: dataUrl });
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
        <div className="accounts">
          {accounts.map((acc) => (
            <label key={acc.uuid} className={`account-option ${settings.activeAccount === acc.uuid ? "on" : ""}`}>
              <input
                type="radio"
                name="account"
                checked={settings.activeAccount === acc.uuid}
                onChange={() => selectAccount(acc.uuid)}
              />
              <img src={`https://mc-heads.net/avatar/${acc.uuid}/32`} alt="" />
              <div className="grow">
                <div className="mod-title">{acc.username}</div>
                <div className="muted small">Microsoft · лицензия</div>
              </div>
              <button className="btn icon" title="Выйти" onClick={() => removeAccount(acc.uuid)}>
                <Icon name="trash" />
              </button>
            </label>
          ))}
          <label className={`account-option ${!settings.activeAccount ? "on" : ""}`}>
            <input type="radio" name="account" checked={!settings.activeAccount} onChange={() => selectAccount(null)} />
            <div className="avatar">{settings.username.slice(0, 1).toUpperCase()}</div>
            <div className="grow">
              <div className="mod-title">{settings.username}</div>
              <div className="muted small">Офлайн · серверы без проверки лицензии</div>
            </div>
          </label>
        </div>
        <button
          className="btn"
          disabled={!msAvailable}
          title={msAvailable ? undefined : "В этой сборке лаунчера не настроен вход через Microsoft"}
          onClick={() => setLoggingIn(true)}
        >
          <Icon name="user" /> Войти через Microsoft
        </button>
        {!msAvailable && (
          <p className="muted small">Вход через Microsoft появится, когда в сборку добавят MS_CLIENT_ID.</p>
        )}

        <label className="field section-gap">
          <span>Ник для офлайн-режима</span>
          <input
            value={draft.username}
            maxLength={16}
            onChange={(e) => setDraft({ ...draft, username: e.target.value })}
          />
        </label>
        {!valid && <div className="hint">От 3 до 16 символов: латинские буквы, цифры и _</div>}

        <h3 className="section-gap">Игра</h3>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={settings.embedGame ?? true}
            onChange={async (e) => {
              const next = { ...settings, embedGame: e.target.checked };
              try {
                await api.saveSettings(next);
                onSaved(next);
              } catch (err) {
                notify(errorText(err), true);
              }
            }}
          />
          Открывать игру внутри окна лаунчера
        </label>
        <p className="muted small">
          Minecraft появляется прямо в лаунчере, без отдельного окна. Если вместо игры чёрный экран или не работает
          мышь — выключите.
        </p>

        <h3 className="section-gap">Новые сборки</h3>
        <Slider
          label="Оперативная память по умолчанию"
          value={draft.memoryMb}
          min={1024}
          max={16384}
          step={512}
          suffix=" МБ"
          onChange={(memoryMb) => setDraft({ ...draft, memoryMb })}
        />
        <div className="row end">
          <button className="btn primary" disabled={!valid || !dirty} onClick={save}>
            Сохранить
          </button>
        </div>
      </section>

      {loggingIn && <MicrosoftLoginModal onClose={() => setLoggingIn(false)} onDone={onLoggedIn} />}

      <section className="card">
        <div className="row between">
          <h3>Оформление</h3>
          <button className="btn small-btn" onClick={() => onAppearanceChange(DEFAULT_APPEARANCE)}>
            Сбросить
          </button>
        </div>

        <div className="field">
          <span>Стиль интерфейса</span>
          <Segmented options={LAYOUTS} value={a.layout} onChange={(v) => set("layout", v)} />
          <span className="muted small">
            «Консоль» — главная с плитками сборок и большой карточкой, как на PS5. «Классический» — боковая панель.
          </span>
        </div>
        {a.layout === "console" && (
          <>
            <label className="checkbox">
              <input type="checkbox" checked={a.spaceScene} onChange={(e) => set("spaceScene", e.target.checked)} />
              Космический фон со звёздами и лучами света
            </label>
            <label className="checkbox">
              <input type="checkbox" checked={a.splash} onChange={(e) => set("splash", e.target.checked)} />
              Заставка при запуске лаунчера
            </label>
          </>
        )}

        <div className="field">
          <span>Тема</span>
          <Segmented options={THEMES} value={a.theme} onChange={(v) => set("theme", v)} />
        </div>

        <div className="field">
          <span>Прозрачность окна</span>
          <Segmented options={EFFECTS} value={a.effect} onChange={(v) => set("effect", v)} />
          <span className="muted small">
            Сейчас: {EFFECT_NAMES[activeEffect] ?? activeEffect}. Mica работает только в Windows 11, в Windows 10
            вместо неё включается Acrylic.
          </span>
        </div>

        <Slider
          label="Плотность панелей"
          value={a.opacity}
          min={0}
          max={100}
          suffix="%"
          onChange={(v) => set("opacity", v)}
        />

        <div className="field">
          <span>Цвет кнопок и акцентов</span>
          <div className="swatches">
            {ACCENTS.map((c) => (
              <button
                key={c}
                className={`swatch ${a.accent.toLowerCase() === c ? "on" : ""}`}
                style={{ background: c }}
                title={c}
                onClick={() => set("accent", c)}
              />
            ))}
            <label className="swatch custom" title="Свой цвет">
              <input type="color" value={a.accent} onChange={(e) => set("accent", e.target.value)} />
            </label>
          </div>
        </div>

        <div className="field">
          <span>Фон</span>
          <Segmented options={BACKGROUNDS} value={a.background} onChange={(v) => set("background", v)} />
        </div>

        {a.background === "gradient" && (
          <div className="gradients">
            {Object.entries(GRADIENTS).map(([key, g]) => (
              <button
                key={key}
                className={`gradient-tile ${a.gradient === key ? "on" : ""}`}
                style={{ background: g.css }}
                onClick={() => set("gradient", key)}
              >
                <span>{g.label}</span>
              </button>
            ))}
          </div>
        )}

        {a.background === "image" && (
          <>
            <div className="row">
              {a.backgroundImage && <img className="bg-preview" src={a.backgroundImage} alt="" />}
              <button className="btn" onClick={() => fileInput.current?.click()}>
                {a.backgroundImage ? "Заменить картинку" : "Выбрать картинку"}
              </button>
              <input
                ref={fileInput}
                type="file"
                accept="image/*"
                hidden
                onChange={(e) => pickImage(e.target.files?.[0])}
              />
            </div>
            <Slider label="Размытие фона" value={a.backgroundBlur} min={0} max={40} suffix=" px" onChange={(v) => set("backgroundBlur", v)} />
          </>
        )}
        {a.background !== "window" && (
          <Slider label="Затемнение фона" value={a.backgroundDim} min={0} max={80} suffix="%" onChange={(v) => set("backgroundDim", v)} />
        )}

        <Slider label="Скругление углов" value={a.radius} min={0} max={24} suffix=" px" onChange={(v) => set("radius", v)} />
        <Slider label="Масштаб интерфейса" value={a.scale} min={85} max={125} step={5} suffix="%" onChange={(v) => set("scale", v)} />

        <label className="checkbox">
          <input type="checkbox" checked={a.animations} onChange={(e) => set("animations", e.target.checked)} />
          Анимации
        </label>
        {a.layout === "classic" && (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={a.compactSidebar}
              onChange={(e) => set("compactSidebar", e.target.checked)}
            />
            Компактная боковая панель (только иконки)
          </label>
        )}
      </section>
    </div>
  );
}
