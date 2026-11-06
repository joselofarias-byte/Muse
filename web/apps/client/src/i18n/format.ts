import { DEFAULT_LOCALE, type Locale } from "./locale.js";
import { translate } from "./translate.js";

/**
 * Localización de hora/número: utilice Intl de manera uniforme, no escriba el mes y la semana usted mismo.
 * Procese únicamente la parte donde "el extremo local puede determinar el formato";Host La copia emitida seguirá mostrándose tal como está.
 */
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function toMillis(value: number | Date | undefined): number | undefined {
  if (value === undefined) return undefined;
  const ms = value instanceof Date ? value.getTime() : value;
  if (!Number.isFinite(ms) || ms <= 0) return undefined;
  return ms < 1_000_000_000_000 ? ms * 1_000 : ms;
}

function sameDay(a: number, b: number): boolean {
  const left = new Date(a);
  const right = new Date(b);
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  );
}

/** tiempo:puntos (24 Reloj horario, utilice Intl para seguir la convención de idioma). */
export function formatClock(value: number | Date | undefined, locale: Locale = DEFAULT_LOCALE): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  return new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(ms));
}

/** Mes y día (mismo año); para año nuevo, indique año, mes y día. */
export function formatMonthDay(value: number | Date | undefined, locale: Locale = DEFAULT_LOCALE, now = Date.now()): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  const date = new Date(ms);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return new Intl.DateTimeFormat(locale, {
    ...(sameYear ? {} : { year: "numeric" as const }),
    month: "short",
    day: "numeric",
  }).format(date);
}

/** La hora en la lista de conversaciones: proporcione la hora del mismo día, proporcione el mes y el día del mismo año y proporcione la fecha completa si abarca años nuevos. */
export function formatSessionTime(value: number | undefined, locale: Locale = DEFAULT_LOCALE, now = Date.now()): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  if (sameDay(ms, now)) return formatClock(ms, locale);
  return formatMonthDay(ms, locale, now);
}

/**
 * Tiempo relativo: Justo ahora / Hace N minutos / Hace N horas / Ayer / Hace N días / Antes indique la fecha.
 * función pura,now y la configuración regional los proporciona la persona que llama y la prueba puede solucionarlos.
 */
export function formatRelativeTime(
  value: number | Date | undefined,
  now: number = Date.now(),
  locale: Locale = DEFAULT_LOCALE,
): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  const diff = now - ms;
  // Hora futura (por ejemplo, próxima ejecución): diga "N "después" en lugar de "justo ahora".
  if (diff < 0) {
    const ahead = -diff;
    if (ahead < MINUTE) return translate(locale, "time.inMoments");
    if (ahead < HOUR) return translate(locale, "time.inMinutes", { count: Math.floor(ahead / MINUTE) });
    if (ahead < DAY) return translate(locale, "time.inHours", { count: Math.floor(ahead / HOUR) });
    if (ahead < 7 * DAY) return translate(locale, "time.inDays", { count: Math.floor(ahead / DAY) });
    return formatMonthDay(ms, locale, now);
  }
  if (diff < MINUTE) return translate(locale, "time.justNow");
  if (diff < HOUR) return translate(locale, "time.minutesAgo", { count: Math.floor(diff / MINUTE) });
  if (diff < DAY && sameDay(ms, now)) return translate(locale, "time.hoursAgo", { count: Math.floor(diff / HOUR) });

  const yesterday = new Date(now);
  yesterday.setHours(0, 0, 0, 0);
  yesterday.setDate(yesterday.getDate() - 1);
  if (sameDay(ms, yesterday.getTime())) return translate(locale, "time.yesterday");
  if (diff < 7 * DAY) return translate(locale, "time.daysAgo", { count: Math.floor(diff / DAY) });
  return formatMonthDay(ms, locale, now);
}

/** La barra separadora de tiempo de la lista de mensajes: hoy/ayer con hora, antes con fecha + hora. */
export function formatDayLabel(value: number | undefined, now: number = Date.now(), locale: Locale = DEFAULT_LOCALE): string {
  const ms = toMillis(value);
  if (ms === undefined) return "";
  const clock = formatClock(ms, locale);
  if (sameDay(ms, now)) return `${translate(locale, "time.today")} ${clock}`;
  const yesterday = new Date(now);
  yesterday.setHours(0, 0, 0, 0);
  yesterday.setDate(yesterday.getDate() - 1);
  if (sameDay(ms, yesterday.getTime())) return `${translate(locale, "time.yesterday")} ${clock}`;
  return `${formatMonthDay(ms, locale, now)} ${clock}`;
}

/** Duración (tiempo de ejecución, etc.): minutos/horas/días en unidades internacionales. */
export function formatDuration(ms: number | undefined, locale: Locale = DEFAULT_LOCALE): string {
  if (ms === undefined || !Number.isFinite(ms) || ms < 0) return "";
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return new Intl.NumberFormat(locale, { style: "unit", unit: "minute", maximumFractionDigits: 0 }).format(minutes);
  const hours = ms / 3_600_000;
  if (hours < 24) return new Intl.NumberFormat(locale, { style: "unit", unit: "hour", maximumFractionDigits: 1 }).format(hours);
  return new Intl.NumberFormat(locale, { style: "unit", unit: "day", maximumFractionDigits: 1 }).format(hours / 24);
}

/** Tamaño de archivo: proporcione bytes para menos de 1 KB, KB/MB para el resto (Intl formato de unidad). */
export function formatBytes(bytes: number | undefined, locale: Locale = DEFAULT_LOCALE): string {
  if (bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return new Intl.NumberFormat(locale, { style: "unit", unit: "byte", maximumFractionDigits: 0 }).format(bytes);
  const kilobytes = bytes / 1024;
  if (kilobytes < 1024) return new Intl.NumberFormat(locale, { style: "unit", unit: "kilobyte", maximumFractionDigits: 1 }).format(kilobytes);
  return new Intl.NumberFormat(locale, { style: "unit", unit: "megabyte", maximumFractionDigits: 1 }).format(kilobytes / 1024);
}

/** Números: agrupados por idioma (p. ej. 1,234 / 1 234). */
export function formatNumber(value: number, locale: Locale = DEFAULT_LOCALE, options?: Intl.NumberFormatOptions): string {
  if (!Number.isFinite(value)) return "";
  return new Intl.NumberFormat(locale, options).format(value);
}
