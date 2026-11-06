import { useEffect } from "react";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, TopBar } from "../components/primitives.js";
import { formatRelativeTime } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { HostTasksState } from "./useHostConnection.js";

interface TasksPageProps {
  readonly tasks: HostTasksState;
  /** Juicio de bit de capacidad/tiempo de ejecución \"no compatible\". */
  readonly supported: boolean;
  readonly onRequest: () => void;
  readonly onBack: () => void;
}

/**
 * Página de tareas programadas (solo lectura).
 *
 * Una tarea por línea: nombre + estado habilitado,cron Mostrar como está y ejecutar la próxima vez (tiempo futuro, tiempo relativo),
 * Última ejecución y último resultado. Este lote no se inicia, se detiene ni se edita. La parte inferior de la página indica que se puede gestionar desde un teléfono celular.
 */
export function TasksPage({ tasks, supported, onRequest, onBack }: TasksPageProps) {
  const { t, locale } = useI18n();

  useEffect(() => {
    if (supported) onRequest();
  }, [supported, onRequest]);

  const now = Date.now();

  return (
    <Screen fill topBar={<TopBar title={t("tasks.title")} largeTitle onBack={onBack} />}>
      {!supported || tasks.unsupported ? (
        <div className="muse-stack">
          <CardGroup>
            <ListRow title={t("tasks.unsupported")} divider={false} />
          </CardGroup>
        </div>
      ) : tasks.error !== undefined ? (
        <ErrorStateBox message={tasks.error} onRetry={onRequest} />
      ) : tasks.loading && tasks.items.length === 0 ? (
        <ListSkeleton label={t("tasks.loading")} />
      ) : tasks.items.length === 0 ? (
        <div className="muse-stack">
          <CardGroup>
            <ListRow title={t("tasks.empty")} divider={false} />
          </CardGroup>
          <CardGroup>
            <ListRow title={t("tasks.readOnly")} divider={false} />
          </CardGroup>
        </div>
      ) : (
        <div className="muse-stack">
          {tasks.items.map((task) => (
            <article className="host-memory-item" key={task.id}>
              <div className="host-task-head">
                <span className="host-task-name">{task.name}</span>
                <span className={task.enabled ? "host-task-state is-on" : "host-task-state"}>
                  {task.enabled ? t("tasks.enabled") : t("tasks.disabled")}
                </span>
              </div>
              <code className="host-task-cron">{task.cron}</code>
              <div className="host-task-meta">
                <span className="host-task-meta-label">{t("tasks.nextRun")}</span>
                <span className="host-task-meta-value">
                  {task.nextRunAt === undefined ? t("tasks.never") : formatRelativeTime(task.nextRunAt, now, locale)}
                </span>
              </div>
              <div className="host-task-meta">
                <span className="host-task-meta-label">{t("tasks.lastRun")}</span>
                <span className="host-task-meta-value">
                  {task.lastRunAt === undefined ? t("tasks.never") : formatRelativeTime(task.lastRunAt, now, locale)}
                </span>
              </div>
              {task.lastResult !== undefined && task.lastResult.length > 0 && (
                <div className="host-task-meta">
                  <span className="host-task-meta-label">{t("tasks.lastResult")}</span>
                  <span className="host-task-meta-value">{task.lastResult}</span>
                </div>
              )}
            </article>
          ))}
          <CardGroup>
            <ListRow title={t("tasks.readOnly")} divider={false} />
          </CardGroup>
        </div>
      )}
    </Screen>
  );
}
