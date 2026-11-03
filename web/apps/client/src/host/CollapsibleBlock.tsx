import type { ReactNode } from "react";

/**
 * Host El bloque contraído de la página de chat: las llamadas a herramientas y los procesos de pensamiento comparten la misma apariencia (contraído por defecto, fila de título = icono + título + estado).
 *
 * Usar nativo `<details>`: El navegador mantiene el estado colapsado y el contenido expandido no se perderá debido a la reconstrucción de React cuando se vuelva a conectar la instantánea.
 */
export interface CollapsibleBlockProps {
  readonly icon: ReactNode;
  readonly label: string;
  /** La copia de estado en el lado derecho de la línea del título; si no, no se procesará. */
  readonly status?: string | undefined;
  /** running / awaiting Utilice el color principal para resaltar \"Aún en movimiento\" y utilice gris neutro para el resto. */
  readonly tone?: "neutral" | "accent" | "warn";
  readonly children: ReactNode;
}

export function CollapsibleBlock({ icon, label, status, tone = "neutral", children }: CollapsibleBlockProps) {
  return (
    <details className="host-details">
      <summary>
        <span className="host-details-icon">{icon}</span>
        <span className="host-details-label">{label}</span>
        {status !== undefined && status.length > 0 && (
          <span className="host-details-status" data-tone={tone}>
            {tone === "accent" && <span className="host-details-status-dot" aria-hidden="true" />}
            {status}
          </span>
        )}
        <span className="host-details-chevron" aria-hidden="true" />
      </summary>
      <div className="host-details-body">{children}</div>
    </details>
  );
}
