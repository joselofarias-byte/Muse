/** 支持的语言与解析规则（纯函数，便于断言）。 */
export type Locale = "es" | "zh" | "en" | "ja" | "ko" | "ru";

export const LOCALES: readonly Locale[] = ["es", "zh", "en", "ja", "ko", "ru"];

/** Idioma de Muse cuando el navegador no coincide con un idioma disponible. */
export const FALLBACK_LOCALE: Locale = "es";

/**
 * 没套 Provider 时的基准语言（组件单测、HostChat 拿到设置之前）：zh 是词条基准，
 * 断言按它写。真实界面一律由 Provider 按解析后的语言接管，不走这个值。
 */
export const DEFAULT_LOCALE: Locale = "zh";

export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

/** "en-US" / "en_US" → "en"；不认识返回 undefined。 */
export function matchLocale(tag: string | undefined): Locale | undefined {
  if (tag === undefined) return undefined;
  const base = tag.trim().toLowerCase().split(/[-_]/)[0] ?? "";
  return isLocale(base) ? base : undefined;
}

/**
 * 解析界面语言：
 *  - 设置里给了具体语言（es/zh/en/ja/ko/ru）就用它；
 *  - `system`（或没给 / 不认识的值）按 navigator.language 列表逐个匹配；
 *  - 都匹配不到回落 es（FALLBACK_LOCALE）。
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

/** 读取浏览器语言列表（Node 环境返回空数组）。 */
export function navigatorLanguages(): readonly string[] {
  if (typeof navigator === "undefined") return [];
  const list = navigator.languages;
  if (Array.isArray(list) && list.length > 0) return list;
  return navigator.language === undefined ? [] : [navigator.language];
}
