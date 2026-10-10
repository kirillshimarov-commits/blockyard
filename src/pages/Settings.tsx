import { useRef, useState } from "react";
import { api, errorText, Settings } from "../api";
import {
  ACCENTS,
  Appearance,
  Background,
  compressImage,
  DEFAULT_APPEARANCE,
  GRADIENTS,
  ThemeMode,
  WindowEffect,
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
  const fileInput = useRef<HTMLInputElement>(null);
  const valid = /^[A-Za-z0-9_]{3,16}$/.test(draft.username);
  const dirty = draft.username !== settings.username || draft.memoryMb !== settings.memoryMb;

  const set = <K extends keyof Appearance>(key: K, value: Appearance[K]) => onAppearanceChange({ ...a, [key]: value });

  async function save() {
    try {
      await api.saveSettings(draft);
      onSaved(draft);
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
        <label className="field">
          <span>Ник в игре</span>
          <input
            value={draft.username}
            maxLength={16}
            onChange={(e) => setDraft({ ...draft, username: e.target.value })}
          />
        </label>
        {!valid && <div className="hint">От 3 до 16 символов: латинские буквы, цифры и _</div>}
        <p className="muted small">Офлайн-режим: одиночная игра и серверы без проверки лицензии.</p>

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

      <section className="card">
        <div className="row between">
          <h3>Оформление</h3>
          <button className="btn small-btn" onClick={() => onAppearanceChange(DEFAULT_APPEARANCE)}>
            Сбросить
          </button>
        </div>

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
        <label className="checkbox">
          <input
            type="checkbox"
            checked={a.compactSidebar}
            onChange={(e) => set("compactSidebar", e.target.checked)}
          />
          Компактная боковая панель (только иконки)
        </label>
      </section>
    </div>
  );
}
