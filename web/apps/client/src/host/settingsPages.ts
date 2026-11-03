import type { HostSettingValue } from "../api.js";

/**
 * Establezca reglas de paginación para subpáginas.
 *
 * Host de `values[]` Es una "llave" plana → Tabla "Valor actual", no hay campo de agrupación; haga clic en la página de clasificación
 * es una entrada de directorio (`entry.id`). Entonces aquí usamos dos tablas para conectar los dos lados:
 *  - `entryIds`: Qué entrada del directorio corresponde a qué página (`appearance` / `chat` / `tts` / `memory` …);
 *  - `keyTokens`: Después de la normalización de la clave de valor (eliminar `.` `_` `-`, minúsculas) que contiene cualquier token se incluye en esta página.
 *
 * Página de inicio para ver sólo**forma de llave**, no lea la copia: el título de la fila siempre utilizará primero el título del directorio localizado del Host.
 * La derivación de claves se utiliza cuando no hay una entrada correspondiente en el directorio y la tabla de copia china nunca se mantiene en el lado web.
 * La coincidencia toma la primera página visitada en orden de matriz, por lo que hay claves ambiguas entre páginas (como `memoryAutoSave`),
 * La atribución se puede ajustar colocando primero páginas más específicas.
 */
export type SettingsPageId = "appearance" | "chat" | "voice" | "memory" | "general";

export interface SettingsPageSpec {
  readonly id: SettingsPageId;
  readonly entryIds: readonly string[];
  readonly keyTokens: readonly string[];
}

export const SETTINGS_PAGE_SPECS: readonly SettingsPageSpec[] = [
  {
    id: "appearance",
    entryIds: ["appearance"],
    keyTokens: ["thememode", "themeid", "fontsize", "fontscale", "textscale", "appearance"],
  },
  {
    id: "chat",
    entryIds: ["chat"],
    keyTokens: [
      "entertosend",
      "sendkey",
      "sendbutton",
      "showtoolcall",
      "toolcall",
      "showtimestamp",
      "timestamp",
      "stream",
      "thinking",
      "chatpreference",
    ],
  },
  {
    id: "voice",
    entryIds: ["tts", "voice", "speech"],
    keyTokens: ["tts", "voice", "speechrate", "speechpitch", "speechspeed", "pronunciation"],
  },
  {
    id: "memory",
    entryIds: ["memory"],
    keyTokens: ["memory", "autosave"],
  },
  {
    // Actualmente no existe ninguna entrada "general" independiente en el directorio;Host Se cambiará automáticamente después de agregar general.
    // `about` Esta es la entrada de abajo hacia arriba a esta página para la versión actual (si el idioma/modelo se ha configurado ya se encuentra en esta página).
    id: "general",
    entryIds: ["general", "common", "about"],
    keyTokens: ["language", "locale", "modelconfigured", "modelready", "configured", "general"],
  },
];

/** Normalización clave:`chatPreferences.enterToSend` → `chatpreferencesentertosend`. */
export function normalizeSettingKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, "");
}

export function pageForEntry(entryId: string): SettingsPageSpec | undefined {
  const id = entryId.trim().toLowerCase();
  return SETTINGS_PAGE_SPECS.find((spec) => spec.entryIds.includes(id));
}

export function pageForValueKey(key: string): SettingsPageSpec | undefined {
  const normalized = normalizeSettingKey(key);
  if (normalized.length === 0) return undefined;
  return SETTINGS_PAGE_SPECS.find((spec) => spec.keyTokens.some((token) => normalized.includes(token)));
}

/** Valores a mostrar en la página: conservados en el orden emitido por el Anfitrión, sin reordenamiento. */
export function valuesForPage(
  spec: SettingsPageSpec,
  values: readonly HostSettingValue[] | undefined,
): readonly HostSettingValue[] {
  if (values === undefined) return [];
  return values.filter((value) => pageForValueKey(value.key)?.id === spec.id);
}

/**
 * El resultado final para los títulos de las filas:Host Cuando no se emite un título para este valor, de la clave se deriva una palabra legible por humanos.
 * Solo se realiza la segmentación de casos y delimitadores, y no se realiza ninguna traducción (para evitar mantener una tabla de copia en el lado web que se desvíe con el host).
 */
export function humanizeSettingKey(key: string): string {
  const tail = key.split(".").pop() ?? key;
  const spaced = tail.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").trim();
  if (spaced.length === 0) return key;
  return spaced
    .split(/\s+/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

/**
 * Claves de solo lectura:`values[]` No hay distinción entre "si se puede escribir". Estas categorías no pueden modificarse según el acuerdo del producto.——
 * El nombre del sonido y "si el modelo ha sido configurado" son información de eco, escrita como un interruptor./En cambio, el cuadro de entrada es incorrecto.
 */
const READ_ONLY_KEY_TOKENS: readonly string[] = ["modelconfigured", "modelready", "voicename", "voiceid", "voiceprofile"];

export function isReadOnlyKey(key: string): boolean {
  const normalized = normalizeSettingKey(key);
  return READ_ONLY_KEY_TOKENS.some((token) => normalized.includes(token));
}

export function readBoolean(value: unknown): boolean {
  return value === true;
}

/**
 * El título de la fila de un valor: el título del Host alcanzado por toggleKey en el directorio → Host Si se emite label → Derivación de claves.
 * La representación de subpáginas y la indexación de búsqueda comparten esta parte para evitar que se muestre el mismo valor con nombres diferentes en dos lugares.
 */
export function resolveValueTitle(
  value: HostSettingValue,
  titlesBySettingKey: ReadonlyMap<string, string>,
): string {
  const fromEntry = titlesBySettingKey.get(value.key);
  if (fromEntry !== undefined && fromEntry.length > 0) return fromEntry;
  const described = (value as { readonly label?: unknown }).label;
  if (typeof described === "string" && described.length > 0) return described;
  return humanizeSettingKey(value.key);
}

export function readNumber(value: unknown, fallback = 0): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim().length > 0) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

export function readText(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return "";
}

/** El tamaño del paso de la recta numérica: presione 1 para números enteros, presione 0,1 para decimales (como velocidad de habla 1,0/0,9). */
export function numberStepFor(current: number): number {
  return Number.isInteger(current) ? 1 : 0.1;
}

/** Resultado del paso, hacia abajo no cruza 0 (Host Haga otra verificación de tipo y rango en el costado). */
export function stepNumber(current: number, direction: 1 | -1): number {
  const next = Number((current + numberStepFor(current) * direction).toFixed(2));
  return next < 0 ? 0 : next;
}
