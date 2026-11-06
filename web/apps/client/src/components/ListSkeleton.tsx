import { CardGroup } from "./Card.js";
import { ListRow } from "./ListRow.js";
import { useI18n } from "../i18n/I18nProvider.js";
import "./primitives.css";

export interface ListSkeletonProps {
  /** Número de grupos de esqueletos, predeterminado 3. */
  readonly groups?: number;
  /** Número de filas en cada grupo, predeterminado 3. */
  readonly rows?: number;
  /** Redacción accesible; si no se aprueba, la entrada se recuperará en el idioma actual (common.loading). */
  readonly label?: string;
}

/**
 * Pantalla de esqueleto de lista (marcador de posición en solicitud para evitar espacios en blanco).
 *
 * Corresponde a MuseListSkeleton de Compose: título del grupo + fila de tarjeta, la fila se escanea
 * tira de título(55%) con instrucciones(35%). toma lo mejor listRowVertical(14) / auxGap(10),
 * Para escanear loopExtraSlow(1800ms);`prefers-reduced-motion` A continuación solo queda el color de fondo estático.
 */
export function ListSkeleton({ groups = 3, rows = 3, label }: ListSkeletonProps) {
  const { t } = useI18n();
  return (
    <div className="muse-stack" role="status" aria-label={label ?? t("common.loading")}>
      {Array.from({ length: groups }, (_, groupIndex) => (
        <CardGroup key={groupIndex} title={<span className="muse-skeleton is-label" />}>
          {Array.from({ length: rows }, (_, rowIndex) => (
            <ListRow
              key={rowIndex}
              divider={rowIndex < rows - 1}
              title={<span className="muse-skeleton" />}
              subtitle={<span className="muse-skeleton is-thin" />}
            />
          ))}
        </CardGroup>
      ))}
    </div>
  );
}
