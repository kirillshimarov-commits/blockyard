import { useEffect, useMemo, useRef, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import {
  api,
  errorText,
  Instance,
  InstanceSettings,
  Loader,
  LOADER_NAMES,
  LoaderVersion,
  McVersion,
} from "../api";
import { compressImage } from "../appearance";
import { t } from "../i18n";
import { Icon, IconName } from "./Icon";
import InstanceCover from "./InstanceCover";

interface Props {
  instance: Instance;
  /** All instances, to suggest existing groups. */
  instances: Instance[];
  busy: boolean;
  onClose: () => void;
  onChanged: (inst: Instance) => void;
  onDuplicated: (inst: Instance) => void;
  onDeleted: () => void;
  onRepair: () => void;
  notify: (text: string, error?: boolean) => void;
}

// Labels and hints are Russian and translated where they are shown.
type Section = "general" | "install" | "window" | "java" | "hooks";
const SECTIONS: { key: Section; label: string; icon: IconName }[] = [
  { key: "general", label: "Основные", icon: "info" },
  { key: "install", label: "Установка", icon: "wrench" },
  { key: "window", label: "Окно и меню", icon: "monitor" },
  { key: "java", label: "Java и память", icon: "coffee" },
  { key: "hooks", label: "Команды запуска", icon: "code" },
];

const CHANNELS: { value: InstanceSettings["updateChannel"]; label: string; hint: string }[] = [
  { value: "release", label: "Релиз", hint: "Только релизные версии будут считаться обновлениями." },
  { value: "beta", label: "Бета", hint: "Релизы и бета-версии модов." },
  { value: "alpha", label: "Альфа", hint: "Все версии, включая нестабильные альфы." },
];

const GAME_UI: { value: "" | "on" | "off"; label: string; hint: string }[] = [
  {
    value: "",
    label: "Авто",
    hint: "Меню Blockyard, если оно включено в настройках лаунчера и в сборке нет других модов, меняющих меню (FancyMenu, Drippy Loading Screen).",
  },
  { value: "on", label: "Blockyard", hint: "Всегда меню в стиле лаунчера (Fabric, Quilt и Forge 1.20.1, Fabric, Quilt и NeoForge 1.21.1)." },
  { value: "off", label: "Стандартное", hint: "Обычные меню Minecraft или меню из модов сборки." },
];

const LOADERS: Loader["type"][] = ["fabric", "forge", "neoforge", "quilt", "vanilla"];

export default function InstanceSettingsModal(props: Props) {
  const { instance, instances, busy, onClose, onChanged, onDuplicated, onDeleted, onRepair, notify } = props;
  const [section, setSection] = useState<Section>("general");
  const [draft, setDraft] = useState(() => ({
    name: instance.name,
    iconUrl: instance.iconUrl ?? null,
    memoryMb: instance.memoryMb,
    gameVersion: instance.gameVersion,
    loader: instance.loader,
    settings: { ...instance.settings, updateChannel: instance.settings.updateChannel || "release" },
  }));
  const [saving, setSaving] = useState(false);
  const [javaCheck, setJavaCheck] = useState("");
  const iconInput = useRef<HTMLInputElement>(null);

  // Installation section: game and loader versions.
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [loaderVersions, setLoaderVersions] = useState<LoaderVersion[]>([]);

  const set = <K extends keyof InstanceSettings>(key: K, value: InstanceSettings[K]) =>
    setDraft((d) => ({ ...d, settings: { ...d.settings, [key]: value } }));

  const groups = useMemo(
    () => [...new Set(instances.map((i) => i.settings?.group).filter((g): g is string => !!g))].sort(),
    [instances],
  );
  const dirty = JSON.stringify(draft) !== JSON.stringify({
    name: instance.name,
    iconUrl: instance.iconUrl ?? null,
    memoryMb: instance.memoryMb,
    gameVersion: instance.gameVersion,
    loader: instance.loader,
    settings: { ...instance.settings, updateChannel: instance.settings.updateChannel || "release" },
  });

  useEffect(() => {
    if (section !== "install" || versions.length) return;
    api.listVersions().then(setVersions).catch(() => {});
  }, [section, versions.length]);

  const loaderType = draft.loader.type;
  useEffect(() => {
    if (section !== "install" || loaderType === "vanilla") return;
    setLoaderVersions([]);
    api
      .listLoaderVersions(loaderType, draft.gameVersion)
      .then((list) => {
        setLoaderVersions(list);
        setDraft((d) => {
          if (d.loader.type === "vanilla" || list.some((l) => l.version === (d.loader as { version: string }).version))
            return d;
          const pick = list.find((l) => l.recommended) ?? list.find((l) => l.stable) ?? list[0];
          return { ...d, loader: { type: d.loader.type, version: pick?.version ?? "" } };
        });
      })
      .catch(() => setLoaderVersions([]));
  }, [section, loaderType, draft.gameVersion]);

  async function save() {
    setSaving(true);
    try {
      onChanged(await api.saveInstanceSettings(instance.id, draft));
      notify(t("Настройки сборки сохранены"));
      onClose();
    } catch (e) {
      notify(errorText(e), true);
      setSaving(false);
    }
  }

  async function pickIcon(file: File | undefined) {
    if (!file) return;
    try {
      setDraft((d) => ({ ...d, iconUrl: null }));
      const url = await compressImage(file, 256);
      setDraft((d) => ({ ...d, iconUrl: url }));
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function duplicate() {
    try {
      notify(t("Копирую сборку…"));
      const copy = await api.duplicateInstance(instance.id);
      notify(t("Создана «{name}»", { name: copy.name }));
      onDuplicated(copy);
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function remove() {
    if (!window.confirm(t("Удалить сборку «{name}» вместе с мирами и модами?", { name: instance.name }))) return;
    try {
      await api.deleteInstance(instance.id);
      onDeleted();
    } catch (e) {
      notify(errorText(e), true);
    }
  }

  async function browseJava() {
    const path = await open({ multiple: false, filters: [{ name: "Java", extensions: ["exe"] }] });
    if (typeof path === "string") {
      set("javaPath", path);
      checkJava(path);
    }
  }

  async function checkJava(path = draft.settings.javaPath) {
    setJavaCheck(t("Проверяю…"));
    try {
      setJavaCheck(`✓ ${await api.testJava(path)}`);
    } catch (e) {
      setJavaCheck(`✗ ${errorText(e)}`);
    }
  }

  const preview: Instance = { ...instance, name: draft.name, iconUrl: draft.iconUrl };
  const s = draft.settings;
  const channelHint = CHANNELS.find((c) => c.value === s.updateChannel)?.hint;
  const gameUiHint = GAME_UI.find((o) => o.value === (s.gameUi ?? ""))?.hint;

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal settings-modal" onMouseDown={(e) => e.stopPropagation()}>
        <header className="settings-modal-header">
          <InstanceCover instance={preview} />
          <span className="muted">{instance.name}</span>
          <span className="muted">›</span>
          <strong>{t("Настройки")}</strong>
          <button className="btn icon close" title={t("Закрыть")} onClick={onClose}>
            ✕
          </button>
        </header>

        <div className="settings-modal-body">
          <nav className="settings-nav">
            {SECTIONS.map((sec) => (
              <button key={sec.key} className={section === sec.key ? "on" : ""} onClick={() => setSection(sec.key)}>
                <Icon name={sec.icon} size={16} /> {t(sec.label)}
              </button>
            ))}
          </nav>

          <div className="settings-content">
            {section === "general" && (
              <>
                <div className="row top">
                  <label className="field grow">
                    <span>{t("Название")}</span>
                    <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                  </label>
                  <div className="icon-picker">
                    <span className="label">{t("Иконка")}</span>
                    <button className="icon-button" title={t("Выбрать картинку")} onClick={() => iconInput.current?.click()}>
                      <InstanceCover instance={preview} large />
                    </button>
                    {draft.iconUrl && (
                      <button className="link small muted" onClick={() => setDraft({ ...draft, iconUrl: null })}>
                        {t("Убрать")}
                      </button>
                    )}
                    <input
                      ref={iconInput}
                      type="file"
                      accept="image/*"
                      hidden
                      onChange={(e) => pickIcon(e.target.files?.[0])}
                    />
                  </div>
                </div>

                <h4>{t("Создание копии сборки")}</h4>
                <button className="btn" disabled={busy} onClick={duplicate}>
                  <Icon name="copy" /> {t("Создать копию")}
                </button>
                <p className="muted small">{t("Создаёт копию сборки вместе с мирами, настройками и модами.")}</p>

                <h4>{t("Группировка")}</h4>
                <input
                  list="instance-groups"
                  placeholder={t("Введите название группы")}
                  value={s.group}
                  onChange={(e) => set("group", e.target.value)}
                />
                <datalist id="instance-groups">
                  {groups.map((g) => (
                    <option key={g} value={g} />
                  ))}
                </datalist>
                <p className="muted small">{t("Сборки с одинаковой группой показываются вместе в библиотеке.")}</p>

                <h4>{t("Канал обновлений модов")}</h4>
                <div className="chips">
                  {CHANNELS.map((c) => (
                    <button
                      key={c.value}
                      className={`chip ${s.updateChannel === c.value ? "on" : ""}`}
                      onClick={() => set("updateChannel", c.value)}
                    >
                      {s.updateChannel === c.value && <Icon name="check" size={14} />} {t(c.label)}
                    </button>
                  ))}
                </div>
                <p className="muted small">{channelHint && t(channelHint)}</p>

                <h4>{t("Удаление сборки")}</h4>
                <button className="btn danger" disabled={busy} onClick={remove}>
                  <Icon name="trash" /> {t("Удалить сборку")}
                </button>
                <p className="muted small">{t("Миры, моды и настройки будут удалены без возможности восстановления.")}</p>
              </>
            )}

            {section === "install" && (
              <>
                <h4>{t("Версия игры и загрузчик")}</h4>
                <div className="segmented">
                  {LOADERS.map((l) => (
                    <button
                      key={l}
                      className={loaderType === l ? "on" : ""}
                      onClick={() =>
                        setDraft({ ...draft, loader: l === "vanilla" ? { type: "vanilla" } : { type: l, version: "" } })
                      }
                    >
                      {LOADER_NAMES[l]}
                    </button>
                  ))}
                </div>
                <div className="row top section-gap">
                  <label className="field grow">
                    <span>{t("Версия Minecraft")}</span>
                    <select value={draft.gameVersion} onChange={(e) => setDraft({ ...draft, gameVersion: e.target.value })}>
                      {!versions.some((v) => v.id === draft.gameVersion) && <option>{draft.gameVersion}</option>}
                      {versions
                        .filter((v) => v.type === "release" || v.id === draft.gameVersion)
                        .map((v) => (
                          <option key={v.id} value={v.id}>
                            {v.id}
                          </option>
                        ))}
                    </select>
                  </label>
                  {draft.loader.type !== "vanilla" && (
                    <label className="field grow">
                      <span>{t("Версия {loader}", { loader: LOADER_NAMES[draft.loader.type] })}</span>
                      <select
                        value={draft.loader.version}
                        onChange={(e) =>
                          setDraft({ ...draft, loader: { type: draft.loader.type, version: e.target.value } as Loader })
                        }
                      >
                        {!loaderVersions.length && <option>{draft.loader.version || t("Загрузка…")}</option>}
                        {loaderVersions.map((l) => (
                          <option key={l.version} value={l.version}>
                            {l.version}
                            {l.recommended ? ` (${t("рекомендуется")})` : ""}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                </div>
                {(draft.gameVersion !== instance.gameVersion || draft.loader.type !== instance.loader.type) && (
                  <div className="warning-box">
                    {t("Установленные моды могут не подойти к новой версии или загрузчику. Проверьте их после смены.")}
                  </div>
                )}

                <h4>{t("Исправление сборки")}</h4>
                <button className="btn" disabled={busy} onClick={onRepair}>
                  <Icon name="wrench" /> {t("Исправить")}
                </button>
                <p className="muted small">
                  {t(
                    "Переустанавливает загрузчик и файлы Minecraft и восстанавливает повреждённые. Помогает, если игра перестала запускаться из-за ошибок лаунчера. Миры и моды не трогаются.",
                  )}
                </p>
              </>
            )}

            {section === "window" && (
              <>
                <label className="checkbox">
                  <input type="checkbox" checked={s.fullscreen} onChange={(e) => set("fullscreen", e.target.checked)} />
                  {t("Полноэкранный режим")}
                </label>
                <h4>{t("Размер окна")}</h4>
                <div className="row">
                  <label className="field grow">
                    <span>{t("Ширина")}</span>
                    <input
                      inputMode="numeric"
                      placeholder="854"
                      disabled={s.fullscreen}
                      value={s.width ?? ""}
                      onChange={(e) => set("width", e.target.value ? Number(e.target.value.replace(/\D/g, "")) : null)}
                    />
                  </label>
                  <label className="field grow">
                    <span>{t("Высота")}</span>
                    <input
                      inputMode="numeric"
                      placeholder="480"
                      disabled={s.fullscreen}
                      value={s.height ?? ""}
                      onChange={(e) => set("height", e.target.value ? Number(e.target.value.replace(/\D/g, "")) : null)}
                    />
                  </label>
                </div>
                <p className="muted small">{t("Пусто — размер по умолчанию. Нужно указать и ширину, и высоту.")}</p>

                <h4>{t("Меню в игре")}</h4>
                <div className="chips">
                  {GAME_UI.map((o) => (
                    <button
                      key={o.value}
                      className={`chip ${(s.gameUi ?? "") === o.value ? "on" : ""}`}
                      onClick={() => set("gameUi", o.value)}
                    >
                      {(s.gameUi ?? "") === o.value && <Icon name="check" size={14} />} {t(o.label)}
                    </button>
                  ))}
                </div>
                <p className="muted small">{gameUiHint && t(gameUiHint)}</p>
              </>
            )}

            {section === "java" && (
              <>
                <h4>{t("Расположение Java")}</h4>
                <div className="java-box">
                  <Icon name="coffee" />
                  <div className="grow">
                    <input
                      placeholder={t("Автоматически: нужная версия скачается при запуске")}
                      value={s.javaPath}
                      onChange={(e) => set("javaPath", e.target.value)}
                    />
                    {javaCheck && <div className="small muted">{javaCheck}</div>}
                  </div>
                  <button className="btn" onClick={browseJava}>
                    {t("Обзор")}
                  </button>
                  {s.javaPath && (
                    <button className="btn" onClick={() => checkJava()}>
                      {t("Проверить")}
                    </button>
                  )}
                </div>

                <h4>{t("Выделенная память")}</h4>
                <div className="row">
                  <input
                    type="range"
                    className="grow"
                    min={512}
                    max={32768}
                    step={256}
                    value={draft.memoryMb}
                    onChange={(e) => setDraft({ ...draft, memoryMb: Number(e.target.value) })}
                  />
                  <input
                    className="memory-input"
                    inputMode="numeric"
                    value={draft.memoryMb}
                    onChange={(e) => setDraft({ ...draft, memoryMb: Number(e.target.value.replace(/\D/g, "")) || 512 })}
                  />
                  <span className="muted small">{t("МБ")}</span>
                </div>

                <h4>{t("Аргументы Java")}</h4>
                <input
                  placeholder={t("Например: -XX:+UseG1GC -XX:MaxGCPauseMillis=50")}
                  value={s.jvmArgs}
                  onChange={(e) => set("jvmArgs", e.target.value)}
                />

                <h4>{t("Переменные среды")}</h4>
                <textarea
                  rows={3}
                  placeholder={t("По одной на строку: ИМЯ=значение")}
                  value={s.envVars}
                  onChange={(e) => set("envVars", e.target.value)}
                />
              </>
            )}

            {section === "hooks" && (
              <>
                <p className="muted small">
                  {t("Команды выполняются в папке игры через командную строку Windows. Пустое поле — ничего не делать.")}
                </p>
                <h4>{t("Перед запуском")}</h4>
                <input
                  placeholder={t("Например: backup.bat")}
                  value={s.preLaunch}
                  onChange={(e) => set("preLaunch", e.target.value)}
                />
                <p className="muted small">{t("Если команда завершится с ошибкой, игра не запустится.")}</p>

                <h4>{t("Обёртка")}</h4>
                <input
                  placeholder={t("Программа, через которую запускается Java")}
                  value={s.wrapper}
                  onChange={(e) => set("wrapper", e.target.value)}
                />

                <h4>{t("После выхода")}</h4>
                <input
                  placeholder={t("Например: shutdown /s")}
                  value={s.postExit}
                  onChange={(e) => set("postExit", e.target.value)}
                />
              </>
            )}
          </div>
        </div>

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            {t("Отмена")}
          </button>
          <button className="btn primary" disabled={!dirty || saving || busy || !draft.name.trim()} onClick={save}>
            {t("Сохранить")}
          </button>
        </div>
      </div>
    </div>
  );
}
