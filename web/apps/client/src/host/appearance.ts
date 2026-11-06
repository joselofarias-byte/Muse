/**
 * Lógica pura y aplicación DOM de apariencia (modo de tema/escala de tamaño de fuente).
 *
 * Tema de los tres estados:
 *  - `system`:`data-theme="system"`, el estilo se entrega a `prefers-color-scheme` consultas de los medios;
 *  - `light` / `dark`:`data-theme` Solo escríbelo hasta la muerte,CSS Los selectores de atributos tienen prioridad sobre las consultas de medios.
 * Escrito en cuatro tamaños de fuente. `data-font-scale`,CSS Establecer en consecuencia `--text-scale`, todos los tokens de composición tipográfica se multiplican por este multiplicador.
 *
 * La función de la aplicación solo se basa en una estructura de destino muy pequeña, lo cual es conveniente para romper con las afirmaciones del navegador;
 * El cableado del DOM real es [documentAppearanceTarget].
 */
export type ThemeMode = "system" | "light" | "dark";
export type FontScale = "small" | "medium" | "large" | "xlarge";
export type ResolvedScheme = "light" | "dark";

export const THEME_MODES: readonly ThemeMode[] = ["system", "light", "dark"];
export const FONT_SCALES: readonly FontScale[] = ["small", "medium", "large", "xlarge"];

/** Cuatro niveles de aumento:0.9 / 1.0 / 1.1 / 1.2. Solo multiplica el tamaño de fuente y el alto de línea, sin escala general. */
export const FONT_SCALE_FACTOR: Readonly<Record<FontScale, number>> = {
  small: 0.9,
  medium: 1,
  large: 1.1,
  xlarge: 1.2,
};

export function isThemeMode(value: unknown): value is ThemeMode {
  return typeof value === "string" && (THEME_MODES as readonly string[]).includes(value);
}

export function isFontScale(value: unknown): value is FontScale {
  return typeof value === "string" && (FONT_SCALES as readonly string[]).includes(value);
}

/** Modo de lectura de tema: los valores desconocidos se tratan como \"no proporcionados\" y la persona que llama los devuelve al sistema. */
export function readThemeMode(value: unknown): ThemeMode | undefined {
  if (typeof value === "boolean") return value ? "dark" : "light";
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  return isThemeMode(normalized) ? normalized : undefined;
}

/**
 * Lectura de engranajes de tamaño de fuente: además de los cuatro nombres de engranajes, también acepta ampliación digital (Host Históricamente, display.fontSizeScale era "1"
 * Estas cadenas) están normalizadas al archivo más cercano.
 */
export function readFontScale(value: unknown): FontScale | undefined {
  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();
    if (isFontScale(normalized)) return normalized;
  }
  const numeric = typeof value === "number" ? value : typeof value === "string" ? Number(value.trim()) : Number.NaN;
  if (!Number.isFinite(numeric) || numeric <= 0) return undefined;
  let best: FontScale = "medium";
  let distance = Number.POSITIVE_INFINITY;
  for (const scale of FONT_SCALES) {
    const gap = Math.abs(FONT_SCALE_FACTOR[scale] - numeric);
    if (gap < distance) {
      distance = gap;
      best = scale;
    }
  }
  return best;
}

/** system Simplemente sigue el sistema y fuerza el resto. */
export function resolveScheme(mode: ThemeMode, prefersDark: boolean): ResolvedScheme {
  if (mode === "system") return prefersDark ? "dark" : "light";
  return mode;
}

/** theme-color Utilice el valor del token de superficie del esquema actual (conjuntos claros/oscuros). */
export function themeColorFor(scheme: ResolvedScheme, read: (token: string) => string): string {
  return read(`--muse-color-scheme-${scheme}-surface`).trim();
}

export interface AppearanceTarget {
  /** `document.documentElement.dataset`:Escribir tema-datos/escala-fuente-datos. */
  readonly dataset: Record<string, string | undefined>;
  /** `<meta name="theme-color">`: escrito como el color de superficie del esquema actual. */
  readonly meta?: { content: string } | undefined;
  /** Leer el valor actual de la variable CSS; Devuelve una cadena vacía si no se puede obtener. */
  readonly readToken?: ((token: string) => string) | undefined;
}

/**
 * Aspecto de la aplicación: primero escriba los atributos (el estilo entrará en vigor inmediatamente) y luego actualice el color del tema.
 * Se devuelve el esquema analizado y la persona que llama puede hacer otras cosas basándose en él (o simplemente hacer afirmaciones sobre el valor de retorno).
 */
export function applyAppearance(
  target: AppearanceTarget,
  mode: ThemeMode,
  scale: FontScale,
  prefersDark: boolean,
): ResolvedScheme {
  target.dataset["theme"] = mode;
  target.dataset["fontScale"] = scale;
  const scheme = resolveScheme(mode, prefersDark);
  if (target.meta !== undefined && target.readToken !== undefined) {
    const color = themeColorFor(scheme, target.readToken);
    if (color.length > 0) target.meta.content = color;
  }
  return scheme;
}

/** El objetivo: el atributo del DOM real está escrito en <html>,theme-color Escribe en meta. */
export function documentAppearanceTarget(doc: Document): AppearanceTarget {
  const meta = doc.querySelector('meta[name="theme-color"]');
  const view = doc.defaultView;
  return {
    dataset: doc.documentElement.dataset,
    meta:
      meta === null
        ? undefined
        : {
            get content(): string {
              return meta.getAttribute("content") ?? "";
            },
            set content(value: string) {
              meta.setAttribute("content", value);
            },
          },
    readToken: (token: string) =>
      view === null || typeof view.getComputedStyle !== "function"
        ? ""
        : view.getComputedStyle(doc.documentElement).getPropertyValue(token),
  };
}
