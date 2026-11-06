import { useEffect } from "react";
import type { HostSettingValue } from "../api.js";
import {
  applyAppearance,
  documentAppearanceTarget,
  readFontScale,
  readThemeMode,
  type FontScale,
  type ThemeMode,
} from "./appearance.js";
import { normalizeSettingKey } from "./settingsPages.js";
import { useMediaQuery } from "./useMediaQuery.js";
import type { HostSettingsState } from "./useHostConnection.js";

export interface AppearanceState {
  readonly mode: ThemeMode;
  readonly scale: FontScale;
}

/** En valores, haga clic en \"La clave contiene estos fragmentos\" para buscar un valor (la clave puede tener el prefijo apariencia.themeMode). */
function valueOfToken(values: readonly HostSettingValue[] | undefined, tokens: readonly string[]): unknown {
  if (values === undefined) return undefined;
  for (const value of values) {
    const key = normalizeSettingKey(value.key);
    if (tokens.some((token) => key.includes(token))) return value.value;
  }
  return undefined;
}

/**
 * Leer el estado de apariencia desde la instantánea de configuración: prioridad `values[]`, si no, devuélvelo `display`(El Viejo Anfitrión sólo da lo último).
 * Función pura, fácil de afirmar "mapeo de cuatro niveles" y "volver al valor predeterminado para valores no reconocidos".
 */
export function readAppearance(settings: HostSettingsState): AppearanceState {
  const values = settings.snapshot?.values;
  const display = settings.snapshot?.display;
  const mode = readThemeMode(valueOfToken(values, ["thememode"])) ?? readThemeMode(display?.themeMode) ?? "system";
  const scale =
    readFontScale(valueOfToken(values, ["fontsizescale", "fontscale", "textscale"])) ??
    readFontScale(display?.fontSizeScale) ??
    "medium";
  return { mode, scale };
}

/**
 * Escribe la apariencia en el elemento raíz.
 *
 * Escribir "sigue el valor de configuración": escribir en la página de configuración es una actualización optimista.`values` Tan pronto como esto cambie, ejecute nuevamente →
 * El tema da en el clavo;Host Configure la capa para que vuelva a escribir el valor anterior al rechazar → Ejecútelo nuevamente aquí para restaurar automáticamente.
 * El tema del sistema también seguirá. `prefers-color-scheme` Cambió, así que me suscribí a esa consulta de medios.
 */
export function useAppearance(settings: HostSettingsState): AppearanceState {
  const { mode, scale } = readAppearance(settings);
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");

  useEffect(() => {
    if (typeof document === "undefined") return;
    applyAppearance(documentAppearanceTarget(document), mode, scale, prefersDark);
  }, [mode, scale, prefersDark]);

  return { mode, scale };
}
