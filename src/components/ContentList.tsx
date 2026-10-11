import { InstalledMod } from "../api";
import { Icon } from "./Icon";

interface Props {
  items: InstalledMod[];
  disabled: boolean;
  onRemove: (projectId: string) => void;
  /** When set, each row gets an on/off switch (mods only). */
  onToggle?: (projectId: string, enabled: boolean) => void;
}

/** Installed mods, shaders or resource packs with a remove button each. */
export default function ContentList({ items, disabled, onRemove, onToggle }: Props) {
  return (
    <div className="mod-list">
      {items.map((m) => (
        <div key={`${m.source}:${m.projectId}`} className={`mod-row ${m.disabled ? "is-disabled" : ""}`}>
          {m.iconUrl ? <img src={m.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
          <div className="grow">
            <div className="mod-title">{m.title}</div>
            <div className="muted small">
              <span className={`source-badge ${m.source}`}>{m.source === "curseforge" ? "CurseForge" : "Modrinth"}</span>{" "}
              {m.fileName}
            </div>
          </div>
          {onToggle && (
            <label className="switch" title={m.disabled ? "Включить мод" : "Выключить мод"}>
              <input
                type="checkbox"
                checked={!m.disabled}
                disabled={disabled}
                onChange={(e) => onToggle(m.projectId, e.target.checked)}
              />
              <span />
            </label>
          )}
          <button className="btn icon" title="Удалить" disabled={disabled} onClick={() => onRemove(m.projectId)}>
            <Icon name="trash" />
          </button>
        </div>
      ))}
    </div>
  );
}
