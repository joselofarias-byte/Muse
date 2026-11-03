import type { ReactNode } from "react";
import { IconChevronRight } from "./muse-icons.js";
import "./primitives.css";

export interface ListRowProps {
  readonly title: ReactNode;
  readonly subtitle?: ReactNode;
  /** Contenido a la izquierda (normalmente iconos). */
  readonly leading?: ReactNode;
  /** Contenido a la derecha (Switch / valor literal/elemento personalizado). */
  readonly trailing?: ReactNode;
  /** Se agrega una flecha hacia la derecha de Muse a la derecha (se puede superponer con una flecha final). */
  readonly chevron?: boolean;
  readonly onClick?: () => void;
  /** Línea separadora inferior, valor predeterminado verdadero; pase falso para la última línea del grupo. */
  readonly divider?: boolean;
}

/**
 * Establecer fila de elementos: título + subtítulo + flecha derecha/Interruptor + Divisor.
 *
 * Especificaciones tomadas de MuseListItem / MuseDivider —— Altura mínima 60dp, horizontal 16dp / vertical 12dp,
 * Título títuloMedio, cuerpo del subtítuloMedio, separador 0,5dp y sangría izquierda 56dp.
 */
export function ListRow({
  title,
  subtitle,
  leading,
  trailing,
  chevron = false,
  onClick,
  divider = true,
}: ListRowProps) {
  const content = (
    <>
      {leading !== undefined && <span className="muse-listrow-leading">{leading}</span>}
      <span className="muse-listrow-text">
        <span className="muse-listrow-title">{title}</span>
        {subtitle !== undefined && <span className="muse-listrow-subtitle">{subtitle}</span>}
      </span>
      {(trailing !== undefined || chevron) && (
        <span className="muse-listrow-trailing">
          {trailing}
          {chevron && <IconChevronRight className="muse-icon muse-listrow-chevron" size={18} />}
        </span>
      )}
      {divider && <span aria-hidden="true" className="muse-listrow-divider" />}
    </>
  );

  if (onClick === undefined) {
    return <div className="muse-listrow">{content}</div>;
  }
  return (
    <button className="muse-listrow is-interactive" type="button" onClick={onClick}>
      {content}
    </button>
  );
}
