import { useEffect, useState } from "react";
import { api, ContentKind, errorText, formatNumber, Instance, ModHit, News } from "../api";
import { Icon } from "./Icon";
import { t } from "../i18n";

interface Props {
  instance: Instance;
  onOpen: () => void;
  onBrowse: (kind: ContentKind) => void;
  onChanged: (inst: Instance) => void;
  notify: (text: string, error?: boolean) => void;
}

// Kept for the whole session so switching tiles does not refetch.
const popularCache = new Map<string, ModHit[]>();
let newsCache: News[] | null = null;

function ModIcon({ url, title }: { url?: string | null; title: string }) {
  return url ? (
    <img className="shelf-icon" src={url} alt="" loading="lazy" />
  ) : (
    <span className="shelf-icon letter">{title.slice(0, 1).toUpperCase()}</span>
  );
}

/** Cards under the hero: what is in the instance, what is popular for it, and what is new in Minecraft. */
export default function HomeShelf({ instance, onOpen, onBrowse, onChanged, notify }: Props) {
  const vanilla = instance.loader.type === "vanilla";
  // Vanilla cannot load mods, so suggest resource packs instead.
  const kind: ContentKind = vanilla ? "resourcepack" : "mod";
  const loader = vanilla ? null : instance.loader.type;
  const cacheKey = `${kind}|${instance.gameVersion}|${loader}`;
  const [popular, setPopular] = useState<ModHit[] | null>(popularCache.get(cacheKey) ?? null);
  const [news, setNews] = useState<News[] | null>(newsCache);
  const [installing, setInstalling] = useState<string | null>(null);

  useEffect(() => {
    const cached = popularCache.get(cacheKey);
    setPopular(cached ?? null);
    if (cached) return;
    let alive = true;
    api
      .search(kind, "modrinth", "", instance.gameVersion, loader, "downloads", 0)
      .then((res) => {
        popularCache.set(cacheKey, res.hits);
        if (alive) setPopular(res.hits);
      })
      .catch(() => alive && setPopular([]));
    return () => {
      alive = false;
    };
  }, [cacheKey]);

  useEffect(() => {
    if (newsCache) return;
    api
      .minecraftNews()
      .then((items) => {
        newsCache = items;
        setNews(items);
      })
      .catch(() => setNews([]));
  }, []);

  const installedList = vanilla ? instance.resourcepacks : instance.mods;
  const installedIds = new Set(installedList.map((m) => m.projectId));
  const suggestions = (popular ?? []).filter((h) => !installedIds.has(h.projectId)).slice(0, 5);

  async function install(hit: ModHit) {
    setInstalling(hit.projectId);
    try {
      const res =
        kind === "mod"
          ? await api.installMod(instance.id, hit.source, hit.projectId)
          : await api.installAsset(instance.id, hit.source, hit.projectId, "resourcepack");
      onChanged(res.instance);
      if (res.pending.length) notify(t("«{title}» нужно скачать вручную — откройте сборку", { title: hit.title }), true);
      else notify(t("Установлено: {list}", { list: res.installed.join(", ") || hit.title }));
    } catch (e) {
      notify(errorText(e), true);
    } finally {
      setInstalling(null);
    }
  }

  const latest = news?.[0];
  const release = news?.find((n) => n.kind === "release");
  const featured = release ?? latest;

  return (
    <div className="shelf">
      <section className="shelf-card">
        <header>
          <span>{vanilla ? t("Ресурспаки в сборке") : t("Моды в сборке")}</span>
          <span className="muted">{installedList.length}</span>
        </header>
        {installedList.length === 0 ? (
          <div className="shelf-empty">
            <span className="muted">{vanilla ? t("Ресурспаков пока нет") : t("Модов пока нет")}</span>
            <button className="btn small-btn" onClick={() => onBrowse(kind)}>
              <Icon name="plus" size={14} /> {t("Добавить")}
            </button>
          </div>
        ) : (
          <ul className="shelf-list">
            {installedList.slice(0, 5).map((m) => (
              <li key={m.projectId} className={m.disabled ? "off" : ""}>
                <ModIcon url={m.iconUrl} title={m.title} />
                <span className="truncate">{m.title}</span>
                {m.disabled && <span className="muted small">{t("выкл.")}</span>}
              </li>
            ))}
          </ul>
        )}
        <button className="shelf-more" onClick={onOpen}>
          {installedList.length > 5 ? t("Ещё {n}", { n: installedList.length - 5 }) : t("Открыть сборку")}{" "}
          <Icon name="chevron" size={14} />
        </button>
      </section>

      <section className="shelf-card">
        <header>
          <span>{t("Популярное для {version}", { version: instance.gameVersion })}</span>
          <span className="muted">Modrinth</span>
        </header>
        {popular === null ? (
          <ul className="shelf-list">
            {[0, 1, 2, 3].map((i) => (
              <li key={i} className="skeleton" />
            ))}
          </ul>
        ) : suggestions.length === 0 ? (
          <div className="shelf-empty">
            <span className="muted">{popular.length ? t("Всё популярное уже стоит") : t("Не удалось загрузить")}</span>
          </div>
        ) : (
          <ul className="shelf-list">
            {suggestions.map((h) => (
              <li key={h.projectId}>
                <ModIcon url={h.iconUrl} title={h.title} />
                <span className="grow">
                  <span className="truncate block">{h.title}</span>
                  <span className="muted small">{t("{n} загрузок", { n: formatNumber(h.downloads) })}</span>
                </span>
                <button
                  className="mini-add"
                  title={t("Установить")}
                  disabled={installing !== null}
                  onClick={() => install(h)}
                >
                  {installing === h.projectId ? <span className="spinner" /> : <Icon name="plus" size={14} />}
                </button>
              </li>
            ))}
          </ul>
        )}
        <button className="shelf-more" onClick={() => onBrowse(kind)}>
          {t("Весь каталог")} <Icon name="chevron" size={14} />
        </button>
      </section>

      <section
        className={`shelf-card news ${featured?.image ? "with-image" : ""}`}
        style={featured?.image ? { backgroundImage: `url("${featured.image}")` } : undefined}
      >
        <header>
          <span>{t("Новое в Minecraft")}</span>
          {featured && <span className="pill-tag">{featured.kind === "release" ? t("релиз") : t("снапшот")}</span>}
        </header>
        {news === null ? (
          <div className="news-body">
            <div className="skeleton line" />
            <div className="skeleton line short" />
          </div>
        ) : !featured ? (
          <div className="shelf-empty">
            <span className="muted">{t("Новости сейчас недоступны")}</span>
          </div>
        ) : (
          <div className="news-body">
            <div className="news-title">{featured.title}</div>
            {featured.text && <div className="news-text">{featured.text}</div>}
            {latest && latest !== featured && (
              <div className="muted small">{t("Свежий снапшот: {version}", { version: latest.version })}</div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
