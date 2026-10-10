import { useEffect, useMemo, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api, ContentKind, errorText, formatNumber, Instance, InstallResult, LOADER_NAMES, ModHit, Source } from "../api";
import { Icon } from "../components/Icon";
import PendingDownloadsModal from "../components/PendingDownloadsModal";

interface Props {
  kind: ContentKind;
  instances: Instance[];
  initialInstanceId?: string;
  onChanged: (inst: Instance) => void;
  onCreated: (inst: Instance) => void;
  onOpenInstance: (id: string) => void;
  notify: (text: string, error?: boolean) => void;
}

const SORTS: { value: string; label: string; sources: Source[] }[] = [
  { value: "relevance", label: "По релевантности", sources: ["modrinth", "curseforge"] },
  { value: "downloads", label: "По загрузкам", sources: ["modrinth", "curseforge"] },
  { value: "follows", label: "По подпискам", sources: ["modrinth"] },
  { value: "newest", label: "Новые", sources: ["modrinth", "curseforge"] },
  { value: "updated", label: "Обновлённые", sources: ["modrinth", "curseforge"] },
];

const SOURCE_LABEL: Record<Source, string> = { modrinth: "Modrinth", curseforge: "CurseForge" };

const TEXT: Record<ContentKind, { title: string; subtitle: string; placeholder: string }> = {
  mod: { title: "Моды", subtitle: "Каталоги Modrinth и CurseForge", placeholder: "Поиск модов…" },
  shader: {
    title: "Шейдеры",
    subtitle: "Красивый свет, тени и вода. Iris или Oculus установится автоматически",
    placeholder: "Поиск шейдеров…",
  },
  modpack: {
    title: "Готовые сборки",
    subtitle: "Модпаки в один клик: версия, загрузчик, моды и настройки ставятся сами",
    placeholder: "Поиск модпаков…",
  },
};

function normalize(title: string) {
  return title.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
}

export default function Browse(props: Props) {
  const { kind, instances, initialInstanceId, onChanged, onCreated, onOpenInstance, notify } = props;
  const targets = useMemo(() => instances.filter((i) => i.loader.type !== "vanilla"), [instances]);
  const [instanceId, setInstanceId] = useState(initialInstanceId ?? targets[0]?.id ?? "");
  const [source, setSource] = useState<Source>("modrinth");
  const [curseforge, setCurseforge] = useState(false);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState(kind === "mod" ? "relevance" : "downloads");
  const [hits, setHits] = useState<ModHit[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [installing, setInstalling] = useState<Set<string>>(new Set());
  const [showPending, setShowPending] = useState(false);

  const needsTarget = kind !== "modpack";
  const instance = needsTarget ? targets.find((i) => i.id === instanceId) : undefined;
  const owned = kind === "shader" ? instance?.shaders : instance?.mods;
  const installedIds = useMemo(() => new Set(owned?.map((m) => `${m.source}:${m.projectId}`)), [owned]);
  const installedTitles = useMemo(() => new Set(owned?.map((m) => normalize(m.title))), [owned]);
  const pendingIds = useMemo(() => new Set(instance?.pending.map((p) => p.projectId)), [instance]);
  const text = TEXT[kind];

  useEffect(() => {
    api.curseforgeAvailable().then(setCurseforge).catch(() => setCurseforge(false));
  }, []);

  async function load(offset: number) {
    setLoading(true);
    setError("");
    try {
      const loader = kind === "mod" && instance && instance.loader.type !== "vanilla" ? instance.loader.type : null;
      const gameVersion = kind === "mod" ? (instance?.gameVersion ?? null) : null;
      const res = await api.search(kind, source, query, gameVersion, loader, sort, offset);
      setHits((prev) => (offset === 0 ? res.hits : [...prev, ...res.hits]));
      setTotal(res.totalHits);
    } catch (e) {
      if (offset === 0) setHits([]);
      setError(`Не удалось загрузить каталог: ${errorText(e)}`);
    } finally {
      setLoading(false);
    }
  }

  // Debounced search when the query or filters change.
  useEffect(() => {
    const t = window.setTimeout(() => load(0), 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, sort, instanceId, source, kind]);

  function switchSource(next: Source) {
    setSource(next);
    if (!SORTS.find((s) => s.value === sort)?.sources.includes(next)) setSort("relevance");
  }

  function reportResult(hit: ModHit, res: InstallResult) {
    onChanged(res.instance);
    res.warnings.forEach((w) => notify(w, true));
    if (res.pending.length > 0) {
      setShowPending(true);
      return;
    }
    const count = res.installed.length;
    if (count === 0) notify(`${hit.title} уже есть в сборке`);
    else if (count === 1) notify(`${hit.title} установлен`);
    else notify(`Установлено: ${res.installed.join(", ")}`);
  }

  async function install(hit: ModHit) {
    const key = `${hit.source}:${hit.projectId}`;
    setInstalling((s) => new Set(s).add(key));
    try {
      if (kind === "modpack") {
        const inst = await api.installModpack(hit.source, hit.projectId);
        notify(`Устанавливаю «${hit.title}»…`);
        onCreated(inst);
      } else if (instance) {
        const res =
          kind === "shader"
            ? await api.installShader(instance.id, hit.source, hit.projectId)
            : await api.installMod(instance.id, hit.source, hit.projectId);
        reportResult(hit, res);
      }
    } catch (e) {
      notify(errorText(e), true);
    } finally {
      setInstalling((s) => {
        const next = new Set(s);
        next.delete(key);
        return next;
      });
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{text.title}</h1>
          <p className="muted">{text.subtitle}</p>
        </div>
        {needsTarget &&
          (targets.length > 0 ? (
            <label className="field inline">
              <span>Ставить в:</span>
              <select value={instanceId} onChange={(e) => setInstanceId(e.target.value)}>
                {targets.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.name} ({i.gameVersion})
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <span className="hint">Создайте сборку с загрузчиком модов (Fabric, Forge…)</span>
          ))}
      </header>

      <div className="segmented source-tabs">
        {(["modrinth", "curseforge"] as Source[]).map((s) => (
          <button
            key={s}
            className={source === s ? "on" : ""}
            disabled={s === "curseforge" && !curseforge}
            title={s === "curseforge" && !curseforge ? "В этой сборке лаунчера нет ключа CurseForge" : undefined}
            onClick={() => switchSource(s)}
          >
            {SOURCE_LABEL[s]}
          </button>
        ))}
      </div>

      <div className="search-bar">
        <div className="search-input">
          <Icon name="search" />
          <input placeholder={text.placeholder} value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
        </div>
        <select value={sort} onChange={(e) => setSort(e.target.value)}>
          {SORTS.filter((s) => s.sources.includes(source)).map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      <div className="row between">
        <p className="muted small">
          {kind === "mod" && instance
            ? `Показаны моды для Minecraft ${instance.gameVersion} (${LOADER_NAMES[instance.loader.type]}). `
            : ""}
          Найдено: {formatNumber(total)}
        </p>
        {instance && instance.pending.length > 0 && (
          <button className="btn" onClick={() => setShowPending(true)}>
            Ждут скачивания: {instance.pending.length}
          </button>
        )}
      </div>
      {error && <div className="error-box">{error}</div>}

      <div className={`mod-results ${kind === "modpack" ? "packs" : ""}`}>
        {hits.map((hit) => {
          const key = `${hit.source}:${hit.projectId}`;
          const isInstalled = installedIds.has(key) || installedTitles.has(normalize(hit.title));
          const isPending = pendingIds.has(hit.projectId);
          const isInstalling = installing.has(key);
          const canInstall = kind === "modpack" || !!instance;
          return (
            <div key={key} className="mod-card">
              {hit.iconUrl ? <img src={hit.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
              <div className="grow">
                <div className="row gap-sm">
                  <button className="link mod-title" onClick={() => openUrl(hit.pageUrl)}>
                    {hit.title}
                  </button>
                  {hit.author && <span className="muted small">от {hit.author}</span>}
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
              {canInstall &&
                (isInstalled && instance ? (
                  <button className="btn" onClick={() => onOpenInstance(instance.id)}>
                    <Icon name="check" /> Установлен
                  </button>
                ) : isPending ? (
                  <button className="btn" onClick={() => setShowPending(true)}>
                    Скачать вручную
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
      {!loading && hits.length > 0 && hits.length < total && (
        <div className="center">
          <button className="btn" onClick={() => load(hits.length)}>
            Показать ещё
          </button>
        </div>
      )}
      {!loading && !error && hits.length === 0 && <div className="center muted">Ничего не найдено</div>}

      {showPending && instance && (
        <PendingDownloadsModal instance={instance} onChanged={onChanged} onClose={() => setShowPending(false)} />
      )}
    </div>
  );
}
