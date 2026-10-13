import { useCallback, useEffect, useRef, useState } from "react";
import { Account, api, errorText, Settings } from "../api";
import MicrosoftLoginModal from "../components/MicrosoftLoginModal";
import { Icon } from "../components/Icon";
import { sounds } from "../sounds";
import { t } from "../i18n";
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

// Labels are Russian and translated where they are shown.
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

/** Each language is named in itself. */
const LANGUAGES: { value: Appearance["language"]; label: string }[] = [
  { value: "ru", label: "Русский" },
  { value: "en", label: "English" },
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
  translate = true,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
  translate?: boolean;
}) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button key={o.value} className={value === o.value ? "on" : ""} onClick={() => onChange(o.value)}>
          {translate ? t(o.label) : o.label}
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
  const [discordAvailable, setDiscordAvailable] = useState(false);

  useEffect(() => {
    api.listAccounts().then(setAccounts).catch(() => {});
    api.microsoftAvailable().then(setMsAvailable).catch(() => {});
    api.discordAvailable().then(setDiscordAvailable).catch(() => {});
  }, []);

  async function saveToggle(patch: Partial<Settings>) {
    const next = { ...settings, ...patch };
    try {
      await api.saveSettings(next);
      onSaved(next);
    } catch (err) {
      notify(errorText(err), true);
    }
  }

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
      notify(t("Вы вошли как {name}", { name: account.username }));
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
      const next = {
        ...draft,
        activeAccount: settings.activeAccount,
        embedGame: settings.embedGame,
        gameUi: settings.gameUi,
        discord: settings.discord,
      };
      await api.saveSettings(next);
      onSaved(next);
      notify(t("Настройки сохранены"));
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
          <h1>{t("Настройки")}</h1>
        </div>
      </header>

      <section className="card">
        <h3>
          <Icon name="globe" size={16} /> {t("Язык")} · Language
        </h3>
        <Segmented options={LANGUAGES} value={a.language} onChange={(v) => set("language", v)} translate={false} />
      </section>

      <section className="card">
        <h3>{t("Аккаунт")}</h3>
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
                <div className="muted small">{t("Microsoft · лицензия")}</div>
              </div>
              <button className="btn icon" title={t("Выйти")} onClick={() => removeAccount(acc.uuid)}>
                <Icon name="trash" />
              </button>
            </label>
          ))}
          <label className={`account-option ${!settings.activeAccount ? "on" : ""}`}>
            <input type="radio" name="account" checked={!settings.activeAccount} onChange={() => selectAccount(null)} />
            <div className="avatar">{settings.username.slice(0, 1).toUpperCase()}</div>
            <div className="grow">
              <div className="mod-title">{settings.username}</div>
              <div className="muted small">{t("Офлайн · серверы без проверки лицензии")}</div>
            </div>
          </label>
        </div>
        <button
          className="btn"
          disabled={!msAvailable}
          title={msAvailable ? undefined : t("В этой сборке лаунчера не настроен вход через Microsoft")}
          onClick={() => setLoggingIn(true)}
        >
          <Icon name="user" /> {t("Войти через Microsoft")}
        </button>
        {!msAvailable && (
          <p className="muted small">{t("Вход через Microsoft появится, когда в сборку добавят MS_CLIENT_ID.")}</p>
        )}

        <label className="field section-gap">
          <span>{t("Ник для офлайн-режима")}</span>
          <input
            value={draft.username}
            maxLength={16}
            onChange={(e) => setDraft({ ...draft, username: e.target.value })}
          />
        </label>
        {!valid && <div className="hint">{t("От 3 до 16 символов: латинские буквы, цифры и _")}</div>}

        <h3 className="section-gap">{t("Игра")}</h3>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={settings.embedGame ?? true}
            onChange={(e) => saveToggle({ embedGame: e.target.checked })}
          />
          {t("Открывать игру внутри окна лаунчера")}
        </label>
        <p className="muted small">
          {t(
            "Minecraft появляется прямо в лаунчере, без отдельного окна. Если вместо игры чёрный экран или не работает мышь — выключите.",
          )}
        </p>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={settings.gameUi ?? true}
            onChange={(e) => saveToggle({ gameUi: e.target.checked })}
          />
          {t("Меню игры в стиле Blockyard")}
        </label>
        <p className="muted small">
          {t(
            "Мод Blockyard UI делает меню Minecraft как в лаунчере. Работает на Fabric, Quilt и Forge 1.20.1 и на Fabric, Quilt и NeoForge 1.21.1, ставится в такие сборки сам при запуске. Для отдельной сборки это меняется в её настройках → «Окно и меню».",
          )}
        </p>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={settings.discord ?? true}
            disabled={!discordAvailable}
            onChange={(e) => saveToggle({ discord: e.target.checked })}
          />
          {t("Показывать игру в профиле Discord")}
        </label>
        <p className="muted small">
          {discordAvailable
            ? t("Пока идёт игра, друзья в Discord видят название сборки, версию и сколько вы уже играете.")
            : t("Появится, когда в сборку лаунчера добавят DISCORD_CLIENT_ID.")}
        </p>

        <h3 className="section-gap">{t("Новые сборки")}</h3>
        <Slider
          label={t("Оперативная память по умолчанию")}
          value={draft.memoryMb}
          min={1024}
          max={16384}
          step={512}
          suffix={` ${t("МБ")}`}
          onChange={(memoryMb) => setDraft({ ...draft, memoryMb })}
        />
        <div className="row end">
          <button className="btn primary" disabled={!valid || !dirty} onClick={save}>
            {t("Сохранить")}
          </button>
        </div>
      </section>

      {loggingIn && <MicrosoftLoginModal onClose={() => setLoggingIn(false)} onDone={onLoggedIn} />}

      <section className="card">
        <div className="row between">
          <h3>{t("Оформление")}</h3>
          <button className="btn small-btn" onClick={() => onAppearanceChange({ ...DEFAULT_APPEARANCE, language: a.language })}>
            {t("Сбросить")}
          </button>
        </div>

        <div className="field">
          <span>{t("Стиль интерфейса")}</span>
          <Segmented options={LAYOUTS} value={a.layout} onChange={(v) => set("layout", v)} />
          <span className="muted small">
            {t("«Консоль» — главная с плитками сборок и большой карточкой, как на PS5. «Классический» — боковая панель.")}
          </span>
        </div>
        {a.layout === "console" && (
          <>
            <label className="checkbox">
              <input type="checkbox" checked={a.spaceScene} onChange={(e) => set("spaceScene", e.target.checked)} />
              {t("Космический фон со звёздами и лучами света")}
            </label>
            <label className="checkbox">
              <input type="checkbox" checked={a.splash} onChange={(e) => set("splash", e.target.checked)} />
              {t("Заставка при запуске лаунчера")}
            </label>
          </>
        )}

        <div className="field">
          <span>{t("Тема")}</span>
          <Segmented options={THEMES} value={a.theme} onChange={(v) => set("theme", v)} />
        </div>

        <div className="field">
          <span>{t("Прозрачность окна")}</span>
          <Segmented options={EFFECTS} value={a.effect} onChange={(v) => set("effect", v)} />
          <span className="muted small">
            {t("Сейчас: {effect}. Mica работает только в Windows 11, в Windows 10 вместо неё включается Acrylic.", {
              effect: t(EFFECT_NAMES[activeEffect] ?? activeEffect),
            })}
          </span>
        </div>

        <Slider
          label={t("Плотность панелей")}
          value={a.opacity}
          min={0}
          max={100}
          suffix="%"
          onChange={(v) => set("opacity", v)}
        />

        <div className="field">
          <span>{t("Цвет кнопок и акцентов")}</span>
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
            <label className="swatch custom" title={t("Свой цвет")}>
              <input type="color" value={a.accent} onChange={(e) => set("accent", e.target.value)} />
            </label>
          </div>
        </div>

        <div className="field">
          <span>{t("Фон")}</span>
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
                <span>{t(g.label)}</span>
              </button>
            ))}
          </div>
        )}

        {a.background === "image" && (
          <>
            <div className="row">
              {a.backgroundImage && <img className="bg-preview" src={a.backgroundImage} alt="" />}
              <button className="btn" onClick={() => fileInput.current?.click()}>
                {a.backgroundImage ? t("Заменить картинку") : t("Выбрать картинку")}
              </button>
              <input
                ref={fileInput}
                type="file"
                accept="image/*"
                hidden
                onChange={(e) => pickImage(e.target.files?.[0])}
              />
            </div>
            <Slider
              label={t("Размытие фона")}
              value={a.backgroundBlur}
              min={0}
              max={40}
              suffix=" px"
              onChange={(v) => set("backgroundBlur", v)}
            />
          </>
        )}
        {a.background !== "window" && (
          <Slider
            label={t("Затемнение фона")}
            value={a.backgroundDim}
            min={0}
            max={80}
            suffix="%"
            onChange={(v) => set("backgroundDim", v)}
          />
        )}

        <Slider label={t("Скругление углов")} value={a.radius} min={0} max={24} suffix=" px" onChange={(v) => set("radius", v)} />
        <Slider
          label={t("Масштаб интерфейса")}
          value={a.scale}
          min={85}
          max={125}
          step={5}
          suffix="%"
          onChange={(v) => set("scale", v)}
        />

        <label className="checkbox">
          <input type="checkbox" checked={a.animations} onChange={(e) => set("animations", e.target.checked)} />
          {t("Анимации")}
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={a.sounds} onChange={(e) => set("sounds", e.target.checked)} />
          {t("Звуки интерфейса")}
        </label>
        {a.sounds && (
          <div className="row gap-sm">
            <div className="grow">
              <Slider
                label={t("Громкость звуков")}
                value={a.soundVolume}
                min={5}
                max={100}
                step={5}
                suffix="%"
                onChange={(v) => set("soundVolume", v)}
              />
            </div>
            <button className="btn icon" title={t("Послушать")} onClick={() => sounds.play()}>
              <Icon name="volume" />
            </button>
          </div>
        )}
        {a.layout === "classic" && (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={a.compactSidebar}
              onChange={(e) => set("compactSidebar", e.target.checked)}
            />
            {t("Компактная боковая панель (только иконки)")}
          </label>
        )}
      </section>
    </div>
  );
}
