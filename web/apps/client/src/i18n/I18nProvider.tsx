import { createContext, useContext, useMemo, type ReactNode } from "react";
import { DEFAULT_LOCALE, type Locale } from "./locale.js";
import { createTranslator, type Translate } from "./translate.js";

export interface I18nValue {
  readonly locale: Locale;
  readonly t: Translate;
}

/**
 * El valor predeterminado es chino: donde no hay proveedor (el componente se representa directamente en una sola prueba), todavía se representa según zh.
 * No depende del entorno del navegador. HostChat proporciona la interfaz real utilizando el lenguaje analizado y una capa de Proveedor.
 */
const FALLBACK: I18nValue = { locale: DEFAULT_LOCALE, t: createTranslator(DEFAULT_LOCALE) };

const I18nContext = createContext<I18nValue>(FALLBACK);

export function I18nProvider({ locale, children }: { readonly locale: Locale; readonly children: ReactNode }) {
  const value = useMemo<I18nValue>(() => ({ locale, t: createTranslator(locale) }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  return useContext(I18nContext);
}
