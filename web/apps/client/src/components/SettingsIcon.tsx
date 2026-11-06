import { SETTINGS_ICON_PATHS } from "./settings-icon-paths.js";
import "./primitives.css";

export interface SettingsIconProps {
  /** establecer entrada `iconKey`(con `MuseIcons` adentro `val` mismo nombre). */
  readonly iconKey: string;
}

/** La línea inferior de la iconKey desconocida: un círculo hueco para garantizar que la altura de la fila y el ancho de la columna izquierda permanezcan sin cambios. */
const FALLBACK_PATHS: readonly string[] = ["M5.5 12a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0"];

/**
 * El ícono en el lado izquierdo del elemento de configuración: píldora inferior redonda + ícono de línea.
 *
 * Gráficos de `settings-icon-paths.ts`(Copiar `MuseIcons` rutaDatos),
 * Todos los tamaños y colores están tokenizados (`--muse-icons-icon-large` fondo redondo / `--muse-icons-icon-small` gráficos /
 * `--ui-surface-variant` abajo / `--ui-on-surface-variant` línea), no se escribe ningún valor numérico en la página.
 */
export function SettingsIcon({ iconKey }: SettingsIconProps) {
  const paths = SETTINGS_ICON_PATHS[iconKey] ?? FALLBACK_PATHS;
  return (
    <span className="muse-settings-icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" className="muse-icon" aria-hidden focusable={false}>
        {paths.map((d) => (
          <path key={d} d={d} />
        ))}
      </svg>
    </span>
  );
}
