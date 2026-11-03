import { useMemo } from "react";
import { normalizeSettingKey } from "./settingsPages.js";
import { navigatorLanguages, resolveLocale, type Locale } from "../i18n/locale.js";
import type { HostSettingsState } from "./useHostConnection.js";

/**
 * Idioma de la interfaz: desde `settings.snapshot.values` La clave contiene `language` Ese artículo (Host Problema
 * system/zh/en/ja/ko/ru); si no, regresa `display.language`, ya no presione el idioma del navegador.
 * `system` Las reglas de análisis están en i18n/locale.ts (función pura, afirmable).
 */
export function readLanguage(settings: HostSettingsState): unknown {
  const values = settings.snapshot?.values;
  if (values !== undefined) {
    for (const value of values) {
      if (normalizeSettingKey(value.key).includes("language")) return value.value;
    }
  }
  return settings.snapshot?.display.language;
}

export function useHostLocale(settings: HostSettingsState): Locale {
  const raw = readLanguage(settings);
  const languages = useMemo(() => navigatorLanguages(), []);
  return resolveLocale(raw, languages);
}
