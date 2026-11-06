import type { ReactNode } from "react";
import "./primitives.css";

export interface ScreenProps {
  readonly children?: ReactNode;
  /** Área de la barra superior (generalmente pasada <TopBar />). El área segura de la barra de estado se agregará al renderizar. */
  readonly topBar?: ReactNode;
  /** Área fija inferior (isla de entrada/operación principal). El área segura inferior se agregará al renderizar. */
  readonly footer?: ReactNode;
  /** Si el área de contenido cubre los márgenes de la pantalla (MusePaddings.screen). Verdadero predeterminado. */
  readonly padded?: boolean;
  /** En una funda de teléfono celular de posicionamiento fijo (flex columna) para completar la altura disponible, el valor predeterminado es falso. */
  readonly fill?: boolean;
  readonly className?: string;
}

/**
 * Esqueleto de página: barra superior + área de contenido de desplazamiento + área inferior, además de relleno de área segura.
 *
 * Corresponde a MusePageScaffold de Compose (contentWindowInsets = 0, la barra de estado se entrega a topBar,
 * La parte inferior se entrega al pie de página), la diferencia es que la Web usa variables de zona segura CSS env(safe-area-inset-*).
 */
export function Screen({ children, topBar, footer, padded = true, fill = false, className }: ScreenProps) {
  const classes = ["muse-screen"];
  if (fill) classes.push("is-fill");
  if (className !== undefined) classes.push(className);
  return (
    <div className={classes.join(" ")}>
      {topBar !== undefined && <div className="muse-screen-topbar">{topBar}</div>}
      <div className={padded ? "muse-screen-body is-padded" : "muse-screen-body"}>{children}</div>
      {footer !== undefined && <div className="muse-screen-footer">{footer}</div>}
    </div>
  );
}
