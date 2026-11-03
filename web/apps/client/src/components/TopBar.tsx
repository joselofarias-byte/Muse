import type { ReactNode } from "react";
import { useI18n } from "../i18n/I18nProvider.js";
import { IconBack } from "./muse-icons.js";
import "./primitives.css";

export interface TopBarProps {
  readonly title: string;
  /** Si no se pasa, la clave de retorno no se generará (similar a la de MuseTopBar). `onBack = null` sinónimo). */
  readonly onBack?: () => void;
  /** Large Title:El título tiene su propia fila displayLarge(34sp), la columna superior solo deja retornos y operaciones. */
  readonly largeTitle?: boolean;
  readonly actions?: ReactNode;
  /** Copia de accesibilidad de la clave de devolución; no pase la entrada según el idioma actual (common.back). */
  readonly backLabel?: string;
}

/**
 * Barra superior: Atrás/Título/Acción derecha.
 *
 * Especificaciones tomadas de MuseTopBar —— margen en línea chipInnerTight(4/1), tecla de retorno 48dp objetivo táctil,
 * Título del título Se omite una sola línea grande,Large Title Utilice el relleno displayLarge + messageGap/tightGap.
 */
export function TopBar({ title, onBack, largeTitle = false, actions, backLabel }: TopBarProps) {
  const { t } = useI18n();
  const label = backLabel ?? t("common.back");
  return (
    <header className="muse-topbar">
      <div className="muse-topbar-row">
        {onBack !== undefined && (
          <button className="muse-icon-button" type="button" aria-label={label} onClick={onBack}>
            <IconBack />
          </button>
        )}
        {largeTitle ? <span className="muse-topbar-spacer" /> : <h1 className="muse-topbar-title">{title}</h1>}
        {actions !== undefined && <div className="muse-topbar-actions">{actions}</div>}
      </div>
      {largeTitle && <h1 className="muse-topbar-large-title">{title}</h1>}
    </header>
  );
}
