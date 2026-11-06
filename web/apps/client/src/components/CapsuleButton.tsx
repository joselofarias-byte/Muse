import type { ReactNode } from "react";
import "./primitives.css";

export type CapsuleVariant = "primary" | "secondary";

export interface CapsuleButtonProps {
  readonly children: ReactNode;
  readonly onClick?: () => void;
  /** primary = El color principal de la marca es sólido;secondary = Contenedor de color primario de baja saturación. */
  readonly variant?: CapsuleVariant;
  readonly disabled?: boolean;
  /** Ya sea para llenar el ancho disponible (MuseCapsuleButton Predeterminado fillWidth = true). */
  readonly fillWidth?: boolean;
  readonly leadingIcon?: ReactNode;
  readonly type?: "button" | "submit";
}

/**
 * Botones de cápsula (primario/menor).
 *
 * Especificaciones tomadas de MuseCapsuleButton: altura mínima 48dp,24dp esquinas redondeadas(MuseShapes.huge),
 * Relleno horizontal de 20 dp / vertical de 16 dp,labelLarge Línea simple semigruesa, prensada 0.97x(120ms);
 * Contenedor deshabilitado 0.38, texto 0.75 (no se convertirá en un bloque negro bajo el tema claro).
 */
export function CapsuleButton({
  children,
  onClick,
  variant = "primary",
  disabled = false,
  fillWidth = true,
  leadingIcon,
  type = "button",
}: CapsuleButtonProps) {
  const classes = ["muse-capsule", variant === "primary" ? "is-primary" : "is-secondary"];
  if (fillWidth) classes.push("is-fill");
  return (
    <button className={classes.join(" ")} disabled={disabled} type={type} onClick={onClick}>
      {leadingIcon !== undefined && <span className="muse-capsule-icon">{leadingIcon}</span>}
      <span className="muse-capsule-label">{children}</span>
    </button>
  );
}
