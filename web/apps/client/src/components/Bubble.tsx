import type { ReactNode } from "react";
import "./primitives.css";

export type BubbleRole = "user" | "assistant";

export interface BubbleProps {
  /** user Lado derecho (color de la marca),assistant Izquierda (gris cálido). */
  readonly role: BubbleRole;
  readonly children: ReactNode;
  /** Marca de tiempo/nombre del modelo y otros caracteres pequeños. */
  readonly meta?: ReactNode;
  /** Barra de acción inferior (regenerar/continuar). */
  readonly actions?: ReactNode;
}

/**
 * Burbuja de mensaje: usuario a la derecha, asistente a la izquierda.
 *
 * Especificaciones tomadas de MuseBubbleStyles / MuseShapes.userBubble / assistantBubble ——
 * Esquina redondeada del cuerpo principal 20 dp, esquina trasera lateral de dirección 6 dp, ancho máximo 70 % del ancho de la pantalla, acolchado bubbleInner(10/8),
 * cuerpo cuerpoGrande(1.6 el doble de la altura de la fila).
 */
export function Bubble({ role, children, meta, actions }: BubbleProps) {
  return (
    <div className={`muse-bubble is-${role}`}>
      {meta !== undefined && <div className="muse-bubble-meta">{meta}</div>}
      <div className="muse-bubble-body">{children}</div>
      {actions !== undefined && <div className="muse-bubble-actions">{actions}</div>}
    </div>
  );
}
