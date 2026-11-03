import { formatDayLabel, formatSessionTime as formatSessionTimeIntl } from "../i18n/format.js";
import { DEFAULT_LOCALE, type Locale } from "../i18n/locale.js";

/**
 * Formato de hora en la página de chat: la implementación se coloca en `src/i18n/format.ts`(Ir Internacional + lista de entradas),
 * Aquí solo se conservan funciones puras para agrupar y algunos reenvíos para evitar que la persona que llama disperse dos conjuntos de lógica de formato.
 */

/** Host Lo que se emite son milisegundos de época; los datos históricos redondeados al segundo nivel se normalizan una vez. */
function toMillis(value: number | undefined): number | undefined {
  if (value === undefined || !Number.isFinite(value) || value <= 0) return undefined;
  return value < 1_000_000_000_000 ? value * 1_000 : value;
}

/** La hora en la lista de conversaciones: proporcione la hora del mismo día, proporcione el mes y el día del mismo año y proporcione la fecha completa si abarca años nuevos. */
export function formatSessionTime(value: number | undefined, locale: Locale = DEFAULT_LOCALE): string {
  return formatSessionTimeIntl(value, locale);
}

/**
 * La clave del segmento "día" para la zona horaria local. La determinación entre días debe basarse en el día del calendario local, no se puede utilizar UTC (de lo contrario, el mensaje a las 00:30
 * se contará hasta el día anterior). Si la marca de tiempo no está disponible, se devuelve indefinido, lo que permite a la persona que llama omitir la barra separadora.
 */
export function dayKey(value: number | undefined): string | undefined {
  const ms = toMillis(value);
  if (ms === undefined) return undefined;
  const date = new Date(ms);
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

/**
 * Texto separador de flujo de mensajes: hoy/ayer con hora, antes con fecha + hora (ambos con formato internacional por idioma).
 */
export function formatDaySeparator(value: number | undefined, now: number = Date.now(), locale: Locale = DEFAULT_LOCALE): string {
  return formatDayLabel(value, now, locale);
}

/** Duración de la voz: \"12″\"Falta más de un minuto\"1′05″”. Si se desconoce la duración, se devuelve una cadena vacía y la persona que llama proporcionará una copia de localización. */
export function formatVoiceDuration(durationMs: number | undefined): string {
  if (durationMs === undefined || !Number.isFinite(durationMs) || durationMs <= 0) return "";
  const totalSeconds = Math.max(1, Math.round(durationMs / 1_000));
  if (totalSeconds < 60) return `${totalSeconds}″`;
  return `${Math.floor(totalSeconds / 60)}′${String(totalSeconds % 60).padStart(2, "0")}″`;
}

/** Tiempo de herramienta:10 Indique un decimal debajo de los segundos (0.3s / 1.2s), más de un minuto se divide en segundos. */
export function formatToolDuration(durationMs: number | undefined): string | undefined {
  if (durationMs === undefined || !Number.isFinite(durationMs) || durationMs < 0) return undefined;
  if (durationMs < 10_000) return `${(durationMs / 1_000).toFixed(1)}s`;
  const seconds = Math.round(durationMs / 1_000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m${String(seconds % 60).padStart(2, "0")}s`;
}
