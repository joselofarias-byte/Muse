import { useEffect, useState } from "react";
import { CloseIcon, PlusIcon } from "../components/icons.js";
import { Switch } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { formatSessionTime } from "./format.js";
import { isRenameValid, normalizeSessionTitle, visibleSessions } from "./sessionActions.js";
import { useDelayedFlag } from "./useDelayedFlag.js";
import { useMediaQuery } from "./useMediaQuery.js";
import type { HostSessionSummary } from "./useHostConnection.js";

/** Consistente con el punto de interrupción del archivo de escritorio de estilos.css (solo se usa para determinar si la columna de la izquierda debe buscar previamente las vistas previas de la sesión). */
const DESKTOP_QUERY = "(min-width: 1024px)";

/** Los datos y devoluciones de llamada del panel de sesión: el cajón y la columna izquierda del escritorio comparten la misma copia. */
interface SessionPanelData {
  readonly sessions: readonly HostSessionSummary[];
  /** La primera instantánea de la pantalla aún no ha llegado: la lista se esqueletiza primero (no se mostrará hasta después de 300 ms) y no se muestra \"aún no hay sesión\". */
  readonly ready: boolean;
  /** Las sesiones de archivo están ocultas de forma predeterminada y aparecen solo cuando este interruptor está activado. */
  readonly showArchived: boolean;
  readonly onToggleArchived: () => void;
  readonly currentId: string | undefined;
  readonly previews: Readonly<Record<string, string>>;
  readonly buildId: string;
  readonly onSelect: (sessionId: string) => void;
  readonly onNew: () => void;
  readonly onRequestPreviews: (sessionIds: readonly string[]) => void;
  readonly onRename: (sessionId: string, title: string) => void;
  readonly onArchive: (sessionId: string) => void;
  readonly onDelete: (sessionId: string) => void;
  readonly onOpenSettings: () => void;
  readonly onLogout: () => void;
  /** Solo columna izquierda: contrae la columna izquierda. No pasó en el cajón. */
  readonly onCollapse?: (() => void) | undefined;
}

export interface SessionDrawerProps extends SessionPanelData {
  readonly open: boolean;
  readonly onClose: () => void;
}

export type SessionSidebarProps = SessionPanelData & {
  /** Si la columna izquierda del archivo del escritorio está contraída (cuando esté contraída, habrá un botón de expansión adicional en la parte superior). */
  readonly collapsed: boolean;
  readonly onToggleSidebar: () => void;
};

const PREVIEW_BATCH = 40;

/**
 * Contenido del panel de sesión.
 *
 * En el teléfono celular, es un cajón deslizable (con un botón de cierre en la parte superior y una entrada de configuración en la parte inferior); la columna izquierda del archivo del escritorio es permanente.
 * El cabezal se coloca directamente en la entrada de configuración y no es necesario cerrar la llave. Ambos lados de la fila de la lista son exactamente iguales.——mismo conjunto
 * `host-session` Geometría (ListRow: altura 60, acolchado 16/12).
 *
 * El cambio de nombre es una entrada en línea (no utilice window.prompt): un nombre vacío desactiva "guardar".Enter enviar,Esc Cancelar.
 * Las sesiones de archivo no aparecen en la lista de forma predeterminada y hay un interruptor "Mostrar archivos" en el encabezado.
 */
function SessionPanel({
  variant,
  open,
  onClose,
  ...props
}: SessionPanelData & { readonly variant: "drawer" | "sidebar"; readonly open: boolean; readonly onClose?: (() => void) | undefined }) {
  const {
    sessions,
    ready,
    showArchived,
    onToggleArchived,
    currentId,
    previews,
    buildId,
    onSelect,
    onNew,
    onRequestPreviews,
    onRename,
    onArchive,
    onDelete,
    onOpenSettings,
    onLogout,
    onCollapse,
  } = props;
  const { t, locale } = useI18n();
  const [expandedId, setExpandedId] = useState<string | undefined>(undefined);
  const [renamingId, setRenamingId] = useState<string | undefined>(undefined);
  const [renameDraft, setRenameDraft] = useState("");
  const active = open;
  const skeletonDue = useDelayedFlag(!ready);
  const list = visibleSessions(sessions, showArchived);

  // The snapshot only carries title/time; ask the Host for the latest message of
  // each session once the panel is visible (bounded batch, best effort).
  useEffect(() => {
    if (!active) return;
    const missing = sessions
      .filter((session) => previews[session.id] === undefined)
      .slice(0, PREVIEW_BATCH)
      .map((session) => session.id);
    if (missing.length > 0) onRequestPreviews(missing);
  }, [active, sessions, previews, onRequestPreviews]);

  const cancelRename = () => {
    setRenamingId(undefined);
    setRenameDraft("");
  };

  const commitRename = () => {
    if (renamingId === undefined || !isRenameValid(renameDraft)) return;
    onRename(renamingId, normalizeSessionTitle(renameDraft));
    cancelRename();
  };

  return (
    <>
      <div className="host-drawer-head">
        <div className="host-drawer-title">
          <span>{t("sessions.title")}</span>
          {variant === "drawer" ? (
            <button className="quiet host-drawer-close" type="button" aria-label={t("sessions.close")} onClick={onClose}>
              <CloseIcon size={20} />
            </button>
          ) : (
            <span className="host-sidebar-head-actions">
              <button className="quiet host-sidebar-settings" type="button" onClick={onOpenSettings}>
                {t("common.settings")}
              </button>
              <button
                className="quiet host-sidebar-collapse"
                type="button"
                aria-label={t("sessions.collapse")}
                onClick={onCollapse}
              >
                «
              </button>
            </span>
          )}
        </div>
        <button className="host-new" type="button" onClick={onNew}>
          <PlusIcon />
          {t("chat.newSession")}
        </button>
        <div className="host-archived-toggle">
          <span className="host-archived-label">{t("sessions.showArchived")}</span>
          <Switch checked={showArchived} label={t("sessions.showArchived")} onChange={() => onToggleArchived()} />
        </div>
      </div>

      <div className="host-session-list">
        {!ready && skeletonDue && (
          <div className="host-session-skeleton" role="status" aria-label={t("sessions.loading")}>
            {[0, 1, 2].map((index) => (
              <div className="host-session-skeleton-row" key={index}>
                <span className="muse-skeleton" />
                <span className="muse-skeleton is-thin" />
              </div>
            ))}
          </div>
        )}
        {ready && list.length === 0 && (
          <p className="host-drawer-empty">
            {sessions.length === 0 ? t("sessions.empty") : t("sessions.emptyActive")}
          </p>
        )}
        {ready &&
          list.map((session) => {
            const isActive = session.id === currentId;
            const expanded = expandedId === session.id;
            if (renamingId === session.id) {
              return (
                <div className="host-session-rename" key={session.id}>
                  <input
                    className="host-session-rename-input"
                    aria-label={t("sessions.renameAria", { title: session.title })}
                    value={renameDraft}
                    autoFocus
                    onChange={(event) => setRenameDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        commitRename();
                        return;
                      }
                      if (event.key === "Escape") {
                        event.preventDefault();
                        cancelRename();
                      }
                    }}
                  />
                  <button className="quiet host-session-rename-save" type="button" disabled={!isRenameValid(renameDraft)} onClick={commitRename}>
                    {t("common.save")}
                  </button>
                  <button className="quiet" type="button" onClick={cancelRename}>
                    {t("common.cancel")}
                  </button>
                  {!isRenameValid(renameDraft) && <span className="host-session-rename-hint">{t("sessions.renameEmpty")}</span>}
                </div>
              );
            }
            return (
              <div className={isActive ? "host-session-wrap is-active" : "host-session-wrap"} key={session.id}>
                <button
                  className={isActive ? "host-session active" : "host-session"}
                  type="button"
                  aria-current={isActive ? "true" : undefined}
                  onClick={() => {
                    setExpandedId(undefined);
                    onSelect(session.id);
                  }}
                >
                  <span className="host-session-title">
                    {session.pinned && <span className="host-pin" aria-label={t("sessions.pinned")}>📌</span>}
                    {session.title || t("sessions.untitled")}
                    {session.archived && <span className="host-session-archived">{t("sessions.archived")}</span>}
                  </span>
                  <span className="host-session-meta">
                    <span className="host-session-preview">{previews[session.id] ?? "　"}</span>
                    <span className="host-session-time">{formatSessionTime(session.updatedAt, locale)}</span>
                  </span>
                </button>
                <button
                  className="quiet host-session-more"
                  type="button"
                  aria-label={t("sessions.moreAria", { title: session.title })}
                  aria-expanded={expanded}
                  onClick={() => setExpandedId(expanded ? undefined : session.id)}
                >
                  ⋯
                </button>
                {expanded && (
                  <div className="host-session-actions">
                    <button
                      className="quiet"
                      type="button"
                      onClick={() => {
                        setExpandedId(undefined);
                        setRenamingId(session.id);
                        setRenameDraft(session.title);
                      }}
                    >
                      {t("sessions.rename")}
                    </button>
                    <button className="quiet" type="button" onClick={() => onArchive(session.id)}>
                      {t("sessions.archive")}
                    </button>
                    <button className="quiet host-danger" type="button" onClick={() => onDelete(session.id)}>
                      {t("common.delete")}
                    </button>
                  </div>
                )}
              </div>
            );
          })}
      </div>

      <div className="host-drawer-foot">
        <span className="host-build">{t("sessions.version", { build: buildId })}</span>
        <div className="host-drawer-foot-actions">
          {variant === "drawer" && (
            <button className="quiet" type="button" onClick={onOpenSettings}>
              {t("common.settings")}
            </button>
          )}
          <button className="quiet" type="button" onClick={onLogout}>
            {t("sessions.logout")}
          </button>
        </div>
      </div>
    </>
  );
}

/** Cajón de conversación para celular/tablet: mascarilla + panel deslizable. Los archivos de escritorio están ocultos por CSS. */
export function SessionDrawer(props: SessionDrawerProps) {
  const { t } = useI18n();
  const { open, onClose, ...data } = props;
  return (
    <>
      <button
        className={open ? "host-scrim open" : "host-scrim"}
        type="button"
        aria-label={t("sessions.close")}
        tabIndex={open ? 0 : -1}
        onClick={onClose}
      />
      <aside className={open ? "host-drawer open" : "host-drawer"} aria-label={t("sessions.listAria")} aria-hidden={!open}>
        <SessionPanel variant="drawer" open={open} onClose={onClose} {...data} />
      </aside>
    </>
  );
}

/** El archivo del escritorio se encuentra permanentemente en la columna de la izquierda: el mismo panel, sin la máscara y las teclas de cierre. */
export function SessionSidebar(props: SessionSidebarProps) {
  // Obtenga previamente la vista previa de la sesión solo cuando la columna de la izquierda realmente se muestra (archivo de escritorio y no cerrado), para no enviar un lote de solicitudes cuando el cajón no está abierto en el teléfono celular.
  const { t } = useI18n();
  const desktop = useMediaQuery(DESKTOP_QUERY);
  const { collapsed, onToggleSidebar, ...data } = props;
  return (
    <aside className={collapsed ? "host-sidebar is-collapsed" : "host-sidebar"} aria-label={t("sessions.listAria")}>
      <SessionPanel variant="sidebar" open={desktop && !collapsed} onCollapse={onToggleSidebar} {...data} />
    </aside>
  );
}
