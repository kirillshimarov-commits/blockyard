import { useEffect, useMemo, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api, errorText, formatNumber, Instance, ModHit } from "../api";
import { Icon } from "../components/Icon";

interface Props {
  instances: Instance[];
  initialInstanceId?: string;
  onChanged: (inst: Instance) => void;
  onOpenInstance: (id: string) => void;
  notify: (text: string, error?: boolean) => void;
}

const SORTS = [
  { value: "relevance", label: "По релевантности" },
  { value: "downloads", label: "По загрузкам" },
  { value: "follows", label: "По подпискам" },
  { value: "newest", label: "Новые" },
  { value: "updated", label: "Обновлённые" },
];

export default function Browse({ instances, initialInstanceId, onChanged, onOpenInstance, notify }: Props) {
  const moddable = useMemo(() => instances.filter((i) => i.loader.type === "fabric"), [instances]);
  const [instanceId, setInstanceId] = useState(initialInstanceId ?? moddable[0]?.id ?? "");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState("relevance");
  const [hits, setHits] = useState<ModHit[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [installing, setInstalling] = useState<Set<string>>(new Set());

  const instance = moddable.find((i) => i.id === instanceId);
  const installed = useMemo(() => new Set(instance?.mods.map((m) => m.projectId)), [instance]);

  async function load(offset: number) {
    setLoading(true);
    setError("");
    try {
      const res = await api.searchMods(query, instance?.gameVersion ?? null, instance ? "fabric" : null, sort, offset);
      setHits((prev) => (offset === 0 ? res.hits : [...prev, ...res.hits]));
      setTotal(res.total_hits);
    } catch (e) {
      setError(`Не удалось загрузить моды: ${errorText(e)}`);
    } finally {
      setLoading(false);
    }
  }

  // Debounced search when the query or filters change.
  useEffect(() => {
    const t = window.setTimeout(() => load(0), 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, sort, instanceId]);

  async function install(hit: ModHit) {
    if (!instance) return;
    setInstalling((s) => new Set(s).add(hit.project_id));
    try {
      const res = await api.installMod(instance.id, hit.project_id);
      onChanged(res.instance);
      const extra = res.installed.length - 1;
      notify(extra > 0 ? `${hit.title} установлен (+ зависимостей: ${extra})` : `${hit.title} установлен`);
    } catch (e) {
      notify(errorText(e), true);
    } finally {
      setInstalling((s) => {
        const next = new Set(s);
        next.delete(hit.project_id);
        return next;
      });
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Моды</h1>
          <p className="muted">Каталог Modrinth</p>
        </div>
        {moddable.length > 0 ? (
          <label className="field inline">
            <span>Ставить в:</span>
            <select value={instanceId} onChange={(e) => setInstanceId(e.target.value)}>
              {moddable.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.name} ({i.gameVersion})
                </option>
              ))}
            </select>
          </label>
        ) : (
          <span className="hint">Создайте сборку с Fabric, чтобы устанавливать моды</span>
        )}
      </header>

      <div className="search-bar">
        <div className="search-input">
          <Icon name="search" />
          <input placeholder="Поиск модов…" value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
        </div>
        <select value={sort} onChange={(e) => setSort(e.target.value)}>
          {SORTS.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {instance && (
        <p className="muted small">
          Показаны моды для Minecraft {instance.gameVersion} (Fabric). Найдено: {formatNumber(total)}
        </p>
      )}
      {error && <div className="error-box">{error}</div>}

      <div className="mod-results">
        {hits.map((hit) => {
          const isInstalled = installed.has(hit.project_id);
          const isInstalling = installing.has(hit.project_id);
          return (
            <div key={hit.project_id} className="mod-card">
              {hit.icon_url ? <img src={hit.icon_url} alt="" /> : <div className="mod-icon-fallback" />}
              <div className="grow">
                <div className="row gap-sm">
                  <button className="link mod-title" onClick={() => openUrl(`https://modrinth.com/mod/${hit.slug}`)}>
                    {hit.title}
                  </button>
                  <span className="muted small">от {hit.author}</span>
                </div>
                <p className="mod-desc">{hit.description}</p>
                <div className="tags">
                  <span className="tag">
                    <Icon name="download" size={12} /> {formatNumber(hit.downloads)}
                  </span>
                  {hit.categories.slice(0, 4).map((c) => (
                    <span key={c} className="tag">
                      {c}
                    </span>
                  ))}
                </div>
              </div>
              {instance &&
                (isInstalled ? (
                  <button className="btn" onClick={() => onOpenInstance(instance.id)}>
                    <Icon name="check" /> Установлен
                  </button>
                ) : (
                  <button className="btn primary" disabled={isInstalling} onClick={() => install(hit)}>
                    {isInstalling ? <span className="spinner" /> : <Icon name="download" />}
                    {isInstalling ? "Установка…" : "Установить"}
                  </button>
                ))}
            </div>
          );
        })}
      </div>

      {loading && <div className="center muted">Загрузка…</div>}
      {!loading && hits.length < total && (
        <div className="center">
          <button className="btn" onClick={() => load(hits.length)}>
            Показать ещё
          </button>
        </div>
      )}
      {!loading && !error && hits.length === 0 && <div className="center muted">Ничего не найдено</div>}
    </div>
  );
}
