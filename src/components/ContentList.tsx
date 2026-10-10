import { InstalledMod } from "../api";
import { Icon } from "./Icon";

interface Props {
  items: InstalledMod[];
  disabled: boolean;
  onRemove: (projectId: string) => void;
}

/** Installed mods, shaders or resource packs with a remove button each. */
export default function ContentList({ items, disabled, onRemove }: Props) {
  return (
    <div className="mod-list">
      {items.map((m) => (
        <div key={`${m.source}:${m.projectId}`} className="mod-row">
          {m.iconUrl ? <img src={m.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
          <div className="grow">
            <div className="mod-title">{m.title}</div>
            <div className="muted small">
              <span className={`source-badge ${m.source}`}>{m.source === "curseforge" ? "CurseForge" : "Modrinth"}</span>{" "}
              {m.fileName}
            </div>
          </div>
          <button className="btn icon" title="Удалить" disabled={disabled} onClick={() => onRemove(m.projectId)}>
            <Icon name="trash" />
          </button>
        </div>
      ))}
    </div>
  );
}
