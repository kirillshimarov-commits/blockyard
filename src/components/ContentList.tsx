import { InstalledMod } from "../api";
import { t } from "../i18n";
import { Icon } from "./Icon";

interface Props {
  items: InstalledMod[];
  disabled: boolean;
  onRemove: (projectId: string) => void;
  /** When set, each row gets an on/off switch (mods only). */
  onToggle?: (projectId: string, enabled: boolean) => void;
  /** Opens the project's page. */
  onOpen?: (item: InstalledMod) => void;
}

/** Installed mods, shaders or resource packs with a remove button each. */
export default function ContentList({ items, disabled, onRemove, onToggle, onOpen }: Props) {
  return (
    <div className="mod-list">
      {items.map((m) => (
        <div key={`${m.source}:${m.projectId}`} className={`mod-row ${m.disabled ? "is-disabled" : ""}`}>
          {m.iconUrl ? <img src={m.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
          <div className="grow">
            {onOpen ? (
              <button className="link mod-title" title={t("Подробнее")} onClick={() => onOpen(m)}>
                {m.title}
              </button>
            ) : (
              <div className="mod-title">{m.title}</div>
            )}
            <div className="muted small">
              <span className={`source-badge ${m.source}`}>{m.source === "curseforge" ? "CurseForge" : "Modrinth"}</span>{" "}
              {m.fileName}
            </div>
          </div>
          {onToggle && (
            <label className="switch" title={m.disabled ? t("Включить мод") : t("Выключить мод")}>
              <input
                type="checkbox"
                checked={!m.disabled}
                disabled={disabled}
                onChange={(e) => onToggle(m.projectId, e.target.checked)}
              />
              <span />
            </label>
          )}
          <button className="btn icon" title={t("Удалить")} disabled={disabled} onClick={() => onRemove(m.projectId)}>
            <Icon name="trash" />
          </button>
        </div>
      ))}
    </div>
  );
}
