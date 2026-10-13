import { useEffect, useMemo, useState } from "react";
import { api, ContentKind, errorText, formatNumber, Instance, InstallResult, LOADER_NAMES, ModHit, Source } from "../api";
import { Icon } from "../components/Icon";
import ModPageModal, { type InstallState } from "../components/ModPageModal";
import PendingDownloadsModal from "../components/PendingDownloadsModal";
import { t } from "../i18n";

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

/** Russian texts; translated where they are shown. */
const TEXT: Record<ContentKind, { title: string; subtitle: string; placeholder: string }> = {
  mod: { title: "Моды", subtitle: "Каталоги Modrinth и CurseForge", placeholder: "Поиск модов…" },
  shader: {
    title: "Шейдеры",
    subtitle: "Красивый свет, тени и вода. Iris или Oculus установится автоматически",
    placeholder: "Поиск шейдеров…",
  },
  resourcepack: {
    title: "Ресурспаки",
    subtitle: "Текстуры, звуки и интерфейс. Работают и в ванильных сборках",
    placeholder: "Поиск ресурспаков…",
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
  // Resource packs work without a mod loader; mods and shaders need one.
  const targets = useMemo(
    () => (kind === "resourcepack" ? instances : instances.filter((i) => i.loader.type !== "vanilla")),
    [instances, kind],
  );
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
  const [opened, setOpened] = useState<ModHit | null>(null);

  const needsTarget = kind !== "modpack";
  const instance = needsTarget ? targets.find((i) => i.id === instanceId) : undefined;
  const owned =
    kind === "shader" ? instance?.shaders : kind === "resourcepack" ? instance?.resourcepacks : instance?.mods;
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
      const gameVersion = kind === "mod" || kind === "resourcepack" ? (instance?.gameVersion ?? null) : null;
      const res = await api.search(kind, source, query, gameVersion, loader, sort, offset);
      setHits((prev) => (offset === 0 ? res.hits : [...prev, ...res.hits]));
      setTotal(res.totalHits);
    } catch (e) {
      if (offset === 0) setHits([]);
      setError(t("Не удалось загрузить каталог: {error}", { error: errorText(e) }));
    } finally {
      setLoading(false);
    }
  }

  // Debounced search when the query or filters change.
  useEffect(() => {
    const timer = window.setTimeout(() => load(0), 300);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, sort, instanceId, source, kind]);

  function switchSource(next: Source) {
    setSource(next);
    if (!SORTS.find((s) => s.value === sort)?.sources.includes(next)) setSort("relevance");
  }

  function reportResult(title: string, res: InstallResult) {
    onChanged(res.instance);
    res.warnings.forEach((w) => notify(w, true));
    if (res.pending.length > 0) {
      setShowPending(true);
      return;
    }
    const count = res.installed.length;
    if (count === 0) notify(t("{title} уже есть в сборке", { title }));
    else if (count === 1) notify(t("{title} установлен", { title }));
    else notify(t("Установлено: {list}", { list: res.installed.join(", ") }));
  }

  function stateOf(src: Source, projectId: string, title: string): InstallState {
    const key = `${src}:${projectId}`;
    if (installing.has(key)) return "installing";
    if (instance && (installedIds.has(key) || installedTitles.has(normalize(title)))) return "installed";
    if (pendingIds.has(projectId)) return "pending";
    return "none";
  }

  async function install(hit: Pick<ModHit, "source" | "projectId" | "title">) {
    const key = `${hit.source}:${hit.projectId}`;
    setInstalling((s) => new Set(s).add(key));
    try {
      if (kind === "modpack") {
        const inst = await api.installModpack(hit.source, hit.projectId);
        notify(t("Устанавливаю «{name}»…", { name: hit.title }));
        onCreated(inst);
      } else if (instance) {
        const res =
          kind === "mod"
            ? await api.installMod(instance.id, hit.source, hit.projectId)
            : await api.installAsset(instance.id, hit.source, hit.projectId, kind);
        reportResult(hit.title, res);
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
          <h1>{t(text.title)}</h1>
          <p className="muted">{t(text.subtitle)}</p>
        </div>
        {needsTarget &&
          (targets.length > 0 ? (
            <label className="field inline">
              <span>{t("Ставить в:")}</span>
              <select value={instanceId} onChange={(e) => setInstanceId(e.target.value)}>
                {targets.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.name} ({i.gameVersion})
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <span className="hint">
              {kind === "resourcepack"
                ? t("Сначала создайте сборку")
                : t("Создайте сборку с загрузчиком модов (Fabric, Forge…)")}
            </span>
          ))}
      </header>

      <div className="segmented source-tabs">
        {(["modrinth", "curseforge"] as Source[]).map((s) => (
          <button
            key={s}
            className={source === s ? "on" : ""}
            disabled={s === "curseforge" && !curseforge}
            title={s === "curseforge" && !curseforge ? t("В этой сборке лаунчера нет ключа CurseForge") : undefined}
            onClick={() => switchSource(s)}
          >
            {SOURCE_LABEL[s]}
          </button>
        ))}
      </div>

      <div className="search-bar">
        <div className="search-input">
          <Icon name="search" />
          <input placeholder={t(text.placeholder)} value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
        </div>
        <select value={sort} onChange={(e) => setSort(e.target.value)}>
          {SORTS.filter((s) => s.sources.includes(source)).map((s) => (
            <option key={s.value} value={s.value}>
              {t(s.label)}
            </option>
          ))}
        </select>
      </div>

      <div className="row between">
        <p className="muted small">
          {kind === "mod" && instance
            ? `${t("Показаны моды для Minecraft {version} ({loader}).", {
                version: instance.gameVersion,
                loader: LOADER_NAMES[instance.loader.type],
              })} `
            : ""}
          {t("Найдено: {n}", { n: formatNumber(total) })}
        </p>
        {instance && instance.pending.length > 0 && (
          <button className="btn" onClick={() => setShowPending(true)}>
            {t("Ждут скачивания: {n}", { n: instance.pending.length })}
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
              <button className="mod-card-icon" title={t("Подробнее")} onClick={() => setOpened(hit)}>
                {hit.iconUrl ? <img src={hit.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
              </button>
              <div className="grow">
                <div className="row gap-sm">
                  <button className="link mod-title" title={t("Подробнее")} onClick={() => setOpened(hit)}>
                    {hit.title}
                  </button>
                  {hit.author && <span className="muted small">{t("от {author}", { author: hit.author })}</span>}
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
                    <Icon name="check" /> {t("Установлен")}
                  </button>
                ) : isPending ? (
                  <button className="btn" onClick={() => setShowPending(true)}>
                    {t("Скачать вручную")}
                  </button>
                ) : (
                  <button className="btn primary" disabled={isInstalling} onClick={() => install(hit)}>
                    {isInstalling ? <span className="spinner" /> : <Icon name="download" />}
                    {isInstalling ? t("Установка…") : t("Установить")}
                  </button>
                ))}
            </div>
          );
        })}
      </div>

      {loading && <div className="center muted">{t("Загрузка…")}</div>}
      {!loading && hits.length > 0 && hits.length < total && (
        <div className="center">
          <button className="btn" onClick={() => load(hits.length)}>
            {t("Показать ещё")}
          </button>
        </div>
      )}
      {!loading && !error && hits.length === 0 && <div className="center muted">{t("Ничего не найдено")}</div>}

      {opened && (
        <ModPageModal
          kind={kind}
          source={opened.source}
          projectId={opened.projectId}
          instance={instance}
          stateOf={stateOf}
          onInstall={kind === "modpack" || instance ? (source, projectId, title) => install({ source, projectId, title }) : undefined}
          onShowPending={() => setShowPending(true)}
          onClose={() => setOpened(null)}
        />
      )}

      {showPending && instance && (
        <PendingDownloadsModal instance={instance} onChanged={onChanged} onClose={() => setShowPending(false)} />
      )}
    </div>
  );
}
