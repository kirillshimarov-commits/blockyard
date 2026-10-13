import { useState } from "react";
import { api, BisectStatus, errorText, Instance } from "../api";
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
      notify(disableCulprit && status.culprit ? `«${status.culprit.title}» выключен, остальные моды на месте` : "Моды вернулись как были");
    });

  if (status.culprit) {
    return (
      <div className="bisect-panel done">
        <Icon name="bug" size={22} />
        <div className="grow">
          <strong>Похоже, вылет вызывает «{status.culprit.title}»</strong>
          <p className="muted small">
            С ним игра вылетала, без него запускалась. Моды уже вернулись как были. Можно выключить этот мод или
            поискать его обновление.
          </p>
        </div>
        <button className="btn primary" disabled={busy} onClick={() => finish(true)}>
          Выключить его
        </button>
        <button className="btn" disabled={busy} onClick={() => finish(false)}>
          Оставить как есть
        </button>
      </div>
    );
  }

  if (status.notFound) {
    return (
      <div className="bisect-panel done">
        <Icon name="bug" size={22} />
        <div className="grow">
          <strong>Виновника найти не удалось</strong>
          <p className="muted small">
            Игра не вылетала ни с одной половиной модов. Скорее всего, вылет вызывают два мода вместе или он случается
            не каждый раз. Моды уже вернулись как были.
          </p>
        </div>
        <button className="btn" disabled={busy} onClick={() => finish(false)}>
          Понятно
        </button>
      </div>
    );
  }

  return (
    <div className="bisect-panel">
      <Icon name="bug" size={22} />
      <div className="grow">
        <strong>
          Поиск мода, из-за которого вылет · шаг {status.step + 1}, осталось около {status.stepsLeft}
        </strong>
        <p className="muted small">
          Включено {status.enabled} из {status.total} модов, под подозрением {status.suspects}. Запустите игру и сделайте
          то же, после чего она вылетала. Вылет лаунчер заметит сам, а если всё работает, просто закройте игру.
        </p>
        <div className="bisect-bar">
          <div style={{ width: `${Math.max(6, (1 - status.suspects / Math.max(1, status.total)) * 100)}%` }} />
        </div>
      </div>
      <div className="bisect-actions">
        <button className="btn primary" disabled={busy || running} onClick={onPlay}>
          <Icon name="play" /> Проверить
        </button>
        <div className="row gap-sm">
          <button className="btn small-btn" disabled={busy || running} onClick={() => report(true)} title="Если игра вылетела, а лаунчер не заметил">
            Вылетела
          </button>
          <button className="btn small-btn" disabled={busy || running} onClick={() => report(false)} title="Если игра работала нормально">
            Работает
          </button>
        </div>
        <button className="link small muted" disabled={busy || running} onClick={() => finish(false)}>
          Отменить и вернуть моды
        </button>
      </div>
    </div>
  );
}
