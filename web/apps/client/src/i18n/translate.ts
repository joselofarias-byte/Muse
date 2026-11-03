import { DEFAULT_LOCALE, type Locale } from "./locale.js";
import { MESSAGES, type MessageDict, type MessageKey } from "./messages.js";

export type TranslateParams = Readonly<Record<string, string | number>>;
export type Translate = (key: MessageKey, params?: TranslateParams) => string;

/** Reemplazo de marcador de posición:`{count}` → valor;params Los marcadores de posición que no están se dejan como están (para facilitar la detección de pases perdidos). */
export function formatMessage(template: string, params?: TranslateParams): string {
  if (params === undefined) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}

/**
 * Obtenga plantillas por idioma,**artículo por artículo**Alternativa: idioma actual → en → zh → key nombre.
 *
 * Los idiomas traducidos siempre utilizan sus propios términos (ja / ko / ru Ahora son todos términos completos),
 * Sólo cuando falte uno de los elementos volverá a aparecer. en——No es que todo el diccionario esté convertido al inglés.
 * dicts Los parámetros solo dejan una salida para las afirmaciones (pase un diccionario con entradas faltantes para su verificación) y la interfaz siempre usa valores predeterminados.
 */
export function resolveTemplate(
  locale: Locale,
  key: MessageKey,
  dicts: Readonly<Record<string, MessageDict>> = MESSAGES,
): string {
  return dicts[locale]?.[key] ?? dicts["en"]?.[key] ?? dicts[DEFAULT_LOCALE]?.[key] ?? key;
}

export function translate(locale: Locale, key: MessageKey, params?: TranslateParams): string {
  return formatMessage(resolveTemplate(locale, key), params);
}

export function createTranslator(locale: Locale): Translate {
  return (key, params) => translate(locale, key, params);
}
