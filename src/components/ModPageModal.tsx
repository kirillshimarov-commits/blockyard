import { type MouseEvent, useEffect, useMemo, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api, ContentKind, errorText, formatNumber, Instance, ModPage, Source } from "../api";
import { markdownToHtml, sanitizeHtml } from "../richtext";
import { Icon } from "./Icon";

export type InstallState = "installed" | "pending" | "installing" | "none";

interface Props {
  kind: ContentKind;
  source: Source;
  projectId: string;
  /** The instance the catalog installs into; marks which versions fit it. */
  instance?: Instance;
  stateOf: (source: Source, projectId: string, title: string) => InstallState;
  /** Missing when there is nowhere to install to. */
  onInstall?: (source: Source, projectId: string, title: string) => void;
  onShowPending?: () => void;
  onClose: () => void;
}

type Tab = "about" | "gallery" | "versions" | "dependencies";

const LINK_LABELS: Record<ModPage["links"][number]["kind"], string> = {
  website: "Сайт",
  source: "Исходный код",
  issues: "Сообщить об ошибке",
  wiki: "Вики",
  discord: "Discord",
};

const RELEASE_LABELS: Record<ModPage["versions"][number]["releaseType"], string> = {
  release: "Релиз",
  beta: "Бета",
  alpha: "Альфа",
};

const DEPENDENCY_LABELS: Record<ModPage["dependencies"][number]["kind"], string> = {
  required: "Обязательная, поставится сама",
  optional: "Необязательная",
  incompatible: "Несовместима",
  embedded: "Уже внутри мода",
};

function formatDate(iso?: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" });
}

function shortList(items: string[], max = 3): string {
  return items.length > max ? `${items.slice(0, max).join(", ")} +${items.length - max}` : items.join(", ");
}

/** A project's page inside the launcher: description, screenshots, versions and dependencies. */
export default function ModPageModal(props: Props) {
  const { kind, instance, stateOf, onInstall, onShowPending, onClose } = props;
  // Opening a dependency stacks its page on top; "back" returns to the previous one.
  const [stack, setStack] = useState([{ source: props.source, projectId: props.projectId }]);
  const current = stack[stack.length - 1];
  const [page, setPage] = useState<ModPage | null>(null);
  const [error, setError] = useState("");
  const [tab, setTab] = useState<Tab>("about");
  const [viewing, setViewing] = useState<number | null>(null);
  const [onlyFitting, setOnlyFitting] = useState(true);

  useEffect(() => {
    let alive = true;
    setPage(null);
    setError("");
    setTab("about");
    const gameVersion = kind === "mod" || kind === "resourcepack" ? (instance?.gameVersion ?? null) : null;
    const loader = kind === "mod" && instance && instance.loader.type !== "vanilla" ? instance.loader.type : null;
    api
      .modPage(current.source, current.projectId, gameVersion, loader)
      .then((p) => alive && setPage(p))
      .catch((e) => alive && setError(errorText(e)));
    return () => {
      alive = false;
    };
  }, [current.source, current.projectId, kind, instance?.gameVersion, instance?.loader.type]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (viewing !== null) setViewing(null);
        else onClose();
      } else if (viewing !== null && page && (e.key === "ArrowRight" || e.key === "ArrowLeft")) {
        const n = page.gallery.length;
        setViewing((viewing + (e.key === "ArrowRight" ? 1 : n - 1)) % n);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [viewing, page, onClose]);

  const body = useMemo(
    () => (page ? sanitizeHtml(page.bodyHtml ? page.body : markdownToHtml(page.body), "Видео") : ""),
    [page],
  );
  const hasFitting = !!page?.versions.some((v) => v.compatible);
  const versions = page ? (hasFitting && onlyFitting ? page.versions.filter((v) => v.compatible) : page.versions) : [];

  function openLink(e: MouseEvent) {
    const link = (e.target as HTMLElement).closest("a");
    if (!link) return;
    e.preventDefault();
    const href = link.getAttribute("href");
    if (href && /^https?:\/\//i.test(href)) openUrl(href).catch(() => {});
  }

  const state = page ? stateOf(page.source, page.projectId, page.title) : "none";
  const installButton =
    page &&
    onInstall &&
    (state === "installed" ? (
      <button className="btn" disabled>
        <Icon name="check" /> Установлен
      </button>
    ) : state === "pending" ? (
      <button className="btn" onClick={onShowPending}>
        Скачать вручную
      </button>
    ) : (
      <button
        className="btn primary"
        disabled={state === "installing"}
        onClick={() => onInstall(page.source, page.projectId, page.title)}
      >
        {state === "installing" ? <span className="spinner" /> : <Icon name="download" />}
        {state === "installing" ? "Установка…" : "Установить"}
      </button>
    ));

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal mod-page" onMouseDown={(e) => e.stopPropagation()}>
        <header className="mod-page-head">
          {stack.length > 1 && (
            <button className="btn icon" title="Назад" onClick={() => setStack((s) => s.slice(0, -1))}>
              <Icon name="back" />
            </button>
          )}
          {page?.iconUrl ? <img className="mod-page-icon" src={page.iconUrl} alt="" /> : <div className="mod-page-icon mod-icon-fallback" />}
          <div className="grow mod-page-title">
            <h2 className="truncate">{page?.title ?? "Загрузка…"}</h2>
            {page && (
              <div className="muted small mod-page-meta">
                {page.author && <span>от {page.author}</span>}
                <span>
                  <Icon name="download" size={12} /> {formatNumber(page.downloads)}
                </span>
                {page.updated && <span>обновлён {formatDate(page.updated)}</span>}
                {page.license && <span>{page.license}</span>}
              </div>
            )}
          </div>
          {installButton}
          {page && (
            <button className="btn icon" title="Открыть на сайте" onClick={() => openUrl(page.pageUrl).catch(() => {})}>
              <Icon name="external" />
            </button>
          )}
          <button className="btn icon" title="Закрыть" onClick={onClose}>
            <Icon name="close" />
          </button>
        </header>

        {error && <div className="error-box">Не удалось открыть страницу: {error}</div>}
        {!page && !error && (
          <div className="mod-page-loading">
            <span className="spinner" />
          </div>
        )}

        {page && (
          <>
            {page.summary && <p className="mod-page-summary">{page.summary}</p>}
            <div className="tags">
              {page.categories.slice(0, 8).map((c) => (
                <span key={c} className="tag">
                  {c}
                </span>
              ))}
              {page.links.map((l) => (
                <button key={l.url} className="tag tag-link" onClick={() => openUrl(l.url).catch(() => {})}>
                  <Icon name="external" size={11} /> {LINK_LABELS[l.kind]}
                </button>
              ))}
            </div>

            <div className="tabs">
              <button className={tab === "about" ? "on" : ""} onClick={() => setTab("about")}>
                Описание
              </button>
              {page.gallery.length > 0 && (
                <button className={tab === "gallery" ? "on" : ""} onClick={() => setTab("gallery")}>
                  Галерея <span className="badge">{page.gallery.length}</span>
                </button>
              )}
              {page.versions.length > 0 && (
                <button className={tab === "versions" ? "on" : ""} onClick={() => setTab("versions")}>
                  Версии
                </button>
              )}
              {page.dependencies.length > 0 && (
                <button className={tab === "dependencies" ? "on" : ""} onClick={() => setTab("dependencies")}>
                  Зависимости <span className="badge">{page.dependencies.length}</span>
                </button>
              )}
            </div>

            <div className="mod-page-content">
              {tab === "about" &&
                (body.trim() ? (
                  <div className="mod-body" onClick={openLink} dangerouslySetInnerHTML={{ __html: body }} />
                ) : (
                  <p className="muted">Автор не добавил описание.</p>
                ))}

              {tab === "gallery" && (
                <div className="gallery-grid">
                  {page.gallery.map((g, i) => (
                    <button key={g.url} className="gallery-item" onClick={() => setViewing(i)}>
                      <img src={g.thumbnail} alt={g.title ?? ""} loading="lazy" />
                      {g.title && <span>{g.title}</span>}
                    </button>
                  ))}
                </div>
              )}

              {tab === "versions" && (
                <>
                  {hasFitting && instance && (
                    <label className="checkbox small">
                      <input type="checkbox" checked={onlyFitting} onChange={(e) => setOnlyFitting(e.target.checked)} />
                      Только подходящие для «{instance.name}»
                    </label>
                  )}
                  {!hasFitting && instance && kind !== "shader" && (
                    <p className="hint">Среди последних версий нет подходящей для «{instance.name}».</p>
                  )}
                  <div className="version-list">
                    {versions.map((v, i) => (
                      <div key={`${v.name}-${i}`} className={`version-row ${v.compatible ? "fits" : ""}`}>
                        <div className="grow">
                          <div className="mod-title truncate">{v.name}</div>
                          <div className="muted small truncate">
                            {shortList(v.gameVersions)}
                            {v.loaders.length > 0 && ` · ${v.loaders.join(", ")}`}
                          </div>
                        </div>
                        <span className={`release-badge ${v.releaseType}`}>{RELEASE_LABELS[v.releaseType] ?? v.releaseType}</span>
                        <span className="muted small version-date">{formatDate(v.date)}</span>
                      </div>
                    ))}
                  </div>
                </>
              )}

              {tab === "dependencies" && (
                <div className="mod-list">
                  {page.dependencies.map((d) => (
                    <button
                      key={`${d.projectId}-${d.kind}`}
                      className="mod-row dependency-row"
                      onClick={() => setStack((s) => [...s, { source: page.source, projectId: d.projectId }])}
                    >
                      {d.iconUrl ? <img src={d.iconUrl} alt="" /> : <div className="mod-icon-fallback" />}
                      <div className="grow">
                        <div className="mod-title">{d.title}</div>
                        <div className={`small dependency-kind ${d.kind}`}>{DEPENDENCY_LABELS[d.kind] ?? d.kind}</div>
                      </div>
                      <Icon name="chevron" />
                    </button>
                  ))}
                </div>
              )}
            </div>
          </>
        )}
      </div>

      {viewing !== null && page && page.gallery[viewing] && (
        <div className="lightbox" onMouseDown={(e) => e.stopPropagation()} onClick={() => setViewing(null)}>
          <img src={page.gallery[viewing].url} alt="" onClick={(e) => e.stopPropagation()} />
          {page.gallery[viewing].title && <div className="lightbox-caption">{page.gallery[viewing].title}</div>}
          {page.gallery.length > 1 && (
            <>
              <button
                className="lightbox-nav prev"
                onClick={(e) => {
                  e.stopPropagation();
                  setViewing((viewing + page.gallery.length - 1) % page.gallery.length);
                }}
              >
                <Icon name="back" size={24} />
              </button>
              <button
                className="lightbox-nav next"
                onClick={(e) => {
                  e.stopPropagation();
                  setViewing((viewing + 1) % page.gallery.length);
                }}
              >
                <Icon name="chevron" size={24} />
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
