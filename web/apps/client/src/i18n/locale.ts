/** Idiomas admitidos y reglas de análisis (funciones puras, fáciles para afirmaciones). */
export type Locale = "es" | "en" | "ja" | "ko" | "ru";

export const LOCALES: readonly Locale[] = ["es", "en", "ja", "ko", "ru"];

/**
 * Idioma cuando el navegador no coincide con ninguno admitido (por ejemplo fr): español.
 * No es lo mismo que la falta de una entrada: ahí translate() cae a en y después al idioma por defecto.
 */
export const FALLBACK_LOCALE: Locale = "es";

/** Idioma de referencia sin Provider (pruebas y el chat antes de leer ajustes). */
export const DEFAULT_LOCALE: Locale = "es";

export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

/** "en-US" / "en_US" → "en";Devuelve indefinido si no se reconoce. */
export function matchLocale(tag: string | undefined): Locale | undefined {
  if (tag === undefined) return undefined;
  const base = tag.trim().toLowerCase().split(/[-_]/)[0] ?? "";
  return isLocale(base) ? base : undefined;
}

/**
 * Idioma de la interfaz de análisis:
 *  - un idioma concreto (es/en/ja/ko/ru) se usa tal cual;
 *  - `system` (o un valor desconocido) recorre navigator.language;
 *  - si nada coincide, se usa FALLBACK_LOCALE (es).
 */
export function resolveLocale(value: unknown, languages: readonly string[]): Locale {
  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();
    if (isLocale(normalized)) return normalized;
  }
  for (const language of languages) {
    const matched = matchLocale(language);
    if (matched !== undefined) return matched;
  }
  return FALLBACK_LOCALE;
}

/** Lea la lista de idiomas del navegador (Node entorno devuelve una matriz vacía). */
export function navigatorLanguages(): readonly string[] {
  if (typeof navigator === "undefined") return [];
  const list = navigator.languages;
  if (Array.isArray(list) && list.length > 0) return list;
  return navigator.language === undefined ? [] : [navigator.language];
}
