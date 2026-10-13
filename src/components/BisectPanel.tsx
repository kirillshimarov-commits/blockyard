import { useState } from "react";
import { api, BisectStatus, errorText, Instance } from "../api";
import { t } from "../i18n";
import { Icon } from "./Icon";

interface Props {
  instance: Instance;
  status: BisectStatus;
  running: boolean;
  onStatus: (status: BisectStatus | null) => void;
  onChanged: (inst: Instance) => void;
  onPlay: () => void;
  notify: (text: string, error?: boolean) => void;
}

/** A running search for the mod that crashes the game: the current step or the result. */
export default function BisectPanel({ instance, status, running, onStatus, onChanged, onPlay, notify }: Props) {
  const [busy, setBusy] = useState(false);

  async function act(job: () => Promise<void>) {
    setBusy(true);
    try {
      await job();
    } catch (e) {
      notify(errorText(e), true);
    } finally {
      setBusy(false);
    }
  }

  const report = (crashed: boolean) =>
    act(async () => {
      onStatus(await api.bisectReport(instance.id, crashed));
      onChanged(await api.getInstance(instance.id));
    });

  const finish = (disableCulprit: boolean) =>
    act(async () => {
      onChanged(await api.bisectFinish(instance.id, disableCulprit));
      onStatus(null);
      notify(
        disableCulprit && status.culprit
          ? t("«{name}» выключен, остальные моды на месте", { name: status.culprit.title })
          : t("Моды вернулись как были"),
      );
    });

  if (status.culprit) {
    return (
      <div className="bisect-panel done">
        <Icon name="bug" size={22} />
        <div className="grow">
          <strong>{t("Похоже, вылет вызывает «{name}»", { name: status.culprit.title })}</strong>
          <p className="muted small">
            {t(
              "С ним игра вылетала, без него запускалась. Моды уже вернулись как были. Можно выключить этот мод или поискать его обновление.",
            )}
          </p>
        </div>
        <button className="btn primary" disabled={busy} onClick={() => finish(true)}>
          {t("Выключить его")}
        </button>
        <button className="btn" disabled={busy} onClick={() => finish(false)}>
          {t("Оставить как есть")}
        </button>
      </div>
    );
  }

  if (status.notFound) {
    return (
      <div className="bisect-panel done">
        <Icon name="bug" size={22} />
        <div className="grow">
          <strong>{t("Виновника найти не удалось")}</strong>
          <p className="muted small">
            {t(
              "Игра не вылетала ни с одной половиной модов. Скорее всего, вылет вызывают два мода вместе или он случается не каждый раз. Моды уже вернулись как были.",
            )}
          </p>
        </div>
        <button className="btn" disabled={busy} onClick={() => finish(false)}>
          {t("Понятно")}
        </button>
      </div>
    );
  }

  return (
    <div className="bisect-panel">
      <Icon name="bug" size={22} />
      <div className="grow">
        <strong>
          {t("Поиск мода, из-за которого вылет · шаг {step}, осталось около {left}", {
            step: status.step + 1,
            left: status.stepsLeft,
          })}
        </strong>
        <p className="muted small">
          {t(
            "Включено {enabled} из {total} модов, под подозрением {suspects}. Запустите игру и сделайте то же, после чего она вылетала. Вылет лаунчер заметит сам, а если всё работает, просто закройте игру.",
            { enabled: status.enabled, total: status.total, suspects: status.suspects },
          )}
        </p>
        <div className="bisect-bar">
          <div style={{ width: `${Math.max(6, (1 - status.suspects / Math.max(1, status.total)) * 100)}%` }} />
        </div>
      </div>
      <div className="bisect-actions">
        <button className="btn primary" disabled={busy || running} onClick={onPlay}>
          <Icon name="play" /> {t("Проверить")}
        </button>
        <div className="row gap-sm">
          <button
            className="btn small-btn"
            disabled={busy || running}
            onClick={() => report(true)}
            title={t("Если игра вылетела, а лаунчер не заметил")}
          >
            {t("Вылетела")}
          </button>
          <button
            className="btn small-btn"
            disabled={busy || running}
            onClick={() => report(false)}
            title={t("Если игра работала нормально")}
          >
            {t("Работает")}
          </button>
        </div>
        <button className="link small muted" disabled={busy || running} onClick={() => finish(false)}>
          {t("Отменить и вернуть моды")}
        </button>
      </div>
    </div>
  );
}
