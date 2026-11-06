/**
 * i18n Afirmaciones: integridad de la entrada, respaldo elemento por elemento, análisis del idioma, tiempo relativo y "no hay chino codificado en el código fuente".
 *
 * El último elemento está escaneando. `src/host/*.tsx`: Si CJK sigue apareciendo después de eliminar el comentario, significa que hay redacción que no se ha eliminado. t().
 * Host La copia emitida (elementos de configuración, motivos de error) originalmente solo aparece durante el tiempo de ejecución y no se escribirá en el código fuente.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync, readdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".i18n-check.mjs");

const ENTRY = `
export { MESSAGES, ES, EN, JA, KO, RU, type MessageKey } from "./src/i18n/messages.ts";
export { formatMessage, translate, createTranslator, resolveTemplate } from "./src/i18n/translate.ts";
export { resolveLocale, matchLocale, isLocale, DEFAULT_LOCALE, LOCALES } from "./src/i18n/locale.ts";
export { formatRelativeTime, formatDayLabel, formatSessionTime, formatNumber } from "./src/i18n/format.ts";
`;

let failures = 0;
const assert = (condition, message) => {
  if (!condition) {
    failures += 1;
    console.error(`✗ ${message}`);
    return false;
  }
  console.log(`✓ ${message}`);
  return true;
};

async function load() {
  await build({
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "i18n-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    logLevel: "warning",
    outfile: TEMP,
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();

/** Elimine los comentarios de bloque y los comentarios de línea (conserve los saltos de línea para facilitar la generación de informes de números de línea). */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/[^\n]*/g, (match, prefix) => prefix + " ".repeat(match.length - prefix.length));
}

try {
  // ── 1. Completitud de la tabla de entrada ─────────────────────────────────────────
  const zhKeys = Object.keys(mod.ES).sort();
  const enKeys = Object.keys(mod.EN).sort();
  assert(zhKeys.length > 100, `entradas chinas ${zhKeys.length} Artículo`);
  assert(JSON.stringify(zhKeys) === JSON.stringify(enKeys), "Las teclas de entrada en chino e inglés están completamente alineadas");
  const emptyZh = zhKeys.filter((key) => mod.ES[key].trim().length === 0);
  const emptyEn = enKeys.filter((key) => mod.EN[key].trim().length === 0);
  assert(emptyZh.length === 0 && emptyEn.length === 0, "No hay entradas vacías");
  const untranslated = enKeys.filter((key) => mod.EN[key] === mod.ES[key] && /[\u4e00-\u9fff]/.test(mod.EN[key]));
  assert(untranslated.length === 0, `No queda chino en las entradas en inglés.${untranslated.length === 0 ? "" : `:${untranslated.join(",")}`}`);

  // ── 2. ja / ko / ru: El conjunto de claves es exactamente el mismo que zh, sin cadena vacía, realmente traducido ──
  const DICTS = { ja: mod.JA, ko: mod.KO, ru: mod.RU };
  for (const [locale, dict] of Object.entries(DICTS)) {
    const keys = Object.keys(dict).sort();
    const missing = zhKeys.filter((key) => !(key in dict));
    const extra = keys.filter((key) => !(key in mod.ES));
    assert(
      missing.length === 0 && extra.length === 0 && keys.length === zhKeys.length,
      `${locale} El conjunto de claves es exactamente el mismo que zh (${keys.length} Artículo${missing.length === 0 ? "" : `, falta:${missing.join(",")}`}${extra.length === 0 ? "" : `, muchos:${extra.join(",")}`})`,
    );
    const empty = keys.filter((key) => dict[key].trim().length === 0);
    assert(empty.length === 0, `${locale} No hay entradas vacías${empty.length === 0 ? "" : `:${empty.join(",")}`}`);
    // Los marcadores de posición no se pueden perder ni agregar:{count} Esta categoría debe dejarse como está.
    const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
    const badSlots = zhKeys.filter((key) => slots(dict[key]) !== slots(mod.ES[key]));
    assert(badSlots.length === 0, `${locale} El marcador de posición es consistente con zh.${badSlots.length === 0 ? "" : `:${badSlots.join(",")}`}`);
  }

  // Verifique aleatoriamente algunos elementos: de hecho, se han modificado al idioma de destino, en lugar de copiarse del inglés original.
  const SPOT_CHECK = [
    "common.cancel",
    "common.retry",
    "composer.send",
    "sessions.rename",
    "settings.title",
    "memory.search",
    "approval.approve",
    "time.justNow",
    "login.submit",
    "common.loadMore",
  ];
  for (const [locale, dict] of Object.entries(DICTS)) {
    const sameAsEn = SPOT_CHECK.filter((key) => dict[key] === mod.EN[key]);
    assert(
      sameAsEn.length === 0,
      `${locale} control al azar ${SPOT_CHECK.length} Ninguno de ellos es igual al inglés.${sameAsEn.length === 0 ? "" : `:${sameAsEn.join(",")}`}`,
    );
    // Cada elemento de la verificación aleatoria utilizará directamente su propia entrada y no será rechazado por en.
    const notOwn = SPOT_CHECK.filter((key) => mod.translate(locale, key) !== dict[key]);
    assert(notOwn.length === 0, `${locale} Las entradas traducidas tienen prioridad al utilizar las suyas propias.${notOwn.length === 0 ? "" : `:${notOwn.join(",")}`}`);
  }

  // El respaldo solo ocurre cuando "falta un determinado elemento" y solo se elimina ese elemento, no se reemplaza todo el diccionario.
  const partialJa = { ...mod.JA };
  delete partialJa["common.close"];
  const partialDicts = { ...mod.MESSAGES, ja: partialJa };
  assert(
    mod.resolveTemplate("ja", "common.close", partialDicts) === mod.EN["common.close"],
    "ja Cuando falta un determinado elemento, ese elemento vuelve al inglés.",
  );
  assert(
    mod.resolveTemplate("ja", "common.cancel", partialDicts) === mod.JA["common.cancel"],
    "ja Cuando falta una de las entradas, las entradas restantes seguirán usando su propia",
  );
  assert(mod.translate("ja", "common.cancel") === mod.JA["common.cancel"], "translate Ir a la propia entrada de ja.");
  assert(mod.translate("ko", "settings.title") === mod.KO["settings.title"], "translate Ir a la propia entrada de Ko.");
  assert(mod.translate("ru", "composer.send") === mod.RU["composer.send"], "translate Ir a la propia entrada de ru.");
  for (const locale of mod.LOCALES) {
    const text = mod.translate(locale, "chat.jumpLatest");
    assert(text.length > 0 && text !== "chat.jumpLatest", `${locale} El nombre de la clave no será expuesto.`);
  }

  // ── 3. interpolación de marcador de posición ───────────────────────────────────────────
  assert(mod.formatMessage("Elemento {count}", { count: 3 }) === "Elemento 3", "Se reemplazan los marcadores de posición");
  assert(mod.formatMessage("marcador de posición sin parámetros {x}", {}) === "marcador de posición sin parámetros {x}", "Cuando faltan parámetros, el marcador de posición se deja intacto.");
  assert(mod.translate("es", "chat.selectedCount", { count: 2 }) === "2 seleccionados", "término + interpolación");
  assert(mod.translate("en", "chat.selectedCount", { count: 2 }) === "2 selected", "interpolación inglesa");

  // ── 4. análisis de lenguaje ─────────────────────────────────────────────
  assert(mod.resolveLocale("en", ["zh-CN"]) === "en", "Si se especifica en la configuración, tendrá prioridad.");
  assert(mod.resolveLocale("system", ["ja-JP", "en"]) === "ja", "system Primer elemento reconocido en la lista por idioma del navegador");
  assert(mod.resolveLocale("system", ["fr-FR"]) === "es", "No puedo igualar el respaldo zh");
  assert(mod.resolveLocale(undefined, []) === mod.DEFAULT_LOCALE, "Volver al valor predeterminado cuando no hay información de idioma");
  assert(mod.resolveLocale("system", ["en-US"]) === "en", "La etiqueta regional toma el idioma principal.");
  assert(mod.matchLocale("ko_KR") === "ko" && mod.matchLocale("xx") === undefined, "matchLocale Manejo de guiones bajos e idiomas desconocidos.");
  assert(mod.resolveLocale("nonsense", ["en-GB"]) === "en", "Los valores desconocidos se tratan como sistema.");

  // ── 5. tiempo relativo (zh / en cada sucursal)────────────────────────────
  const now = new Date(2026, 9, 30, 15, 0, 0).getTime();
  const at = (minutesAgo) => now - minutesAgo * 60_000;
  assert(mod.formatRelativeTime(at(0.2), now, "es") === "justo ahora", "zh: Menos de un minuto es \"justo ahora\"");
  assert(mod.formatRelativeTime(at(0.2), now, "en") === "Just now", "en: Menos de un minuto es Just now");
  assert(mod.formatRelativeTime(at(12), now, "es") === "hace 12 min", "es: hace minutos");
  assert(mod.formatRelativeTime(at(12), now, "en") === "12 min ago", "en: Hace minutos");
  assert(mod.formatRelativeTime(at(180), now, "es") === "hace 3 h", "es: hace horas");
  assert(mod.formatRelativeTime(at(180), now, "en") === "3 h ago", "en:hace horas");
  const yesterday = new Date(2026, 9, 29, 20, 0, 0).getTime();
  assert(mod.formatRelativeTime(yesterday, now, "es") === "ayer", "zh:ayer");
  assert(mod.formatRelativeTime(yesterday, now, "en") === "Yesterday", "en:ayer");
  assert(mod.formatRelativeTime(at(3 * 24 * 60), now, "es") === "hace 3 días", "es: hace días");
  assert(mod.formatRelativeTime(at(3 * 24 * 60), now, "en") === "3 days ago", "en: hace unos días");
  const longAgo = new Date(2026, 5, 1, 9, 0, 0).getTime();
  assert(/[A-Za-zÁÉÍÓÚáéíóú]/.test(mod.formatRelativeTime(longAgo, now, "es")), "es: fecha si pasó más de una semana");
  assert(/Jun/.test(mod.formatRelativeTime(longAgo, now, "en")), "en: Indique la fecha más de una semana (Intl abreviatura)");
  assert(mod.formatRelativeTime(undefined, now, "es") === "", "Si no hay marca de tiempo, se devuelve una cadena vacía.");

  // ── 6. Fecha/Los números van Intl ─────────────────────────────────────
  const sample = new Date(2026, 9, 28, 14, 25, 0).getTime();
  assert(mod.formatNumber(1234.5, "en", { maximumFractionDigits: 1 }) === "1,234.5", "localización digital (en agrupación)");
  assert(/[A-Za-zÁÉÍÓÚáéíóú]/.test(mod.formatDayLabel(sample, now, "es")), "fecha del separador (es)");
  assert(/Oct 28/.test(mod.formatDayLabel(sample, now, "en")), "fecha del separador (en)");
  assert(/[A-Za-zÁÉÍÓÚáéíóú]/.test(mod.formatSessionTime(sample, "es", now)), "tiempo de sesión (es)");

  // ── 7. No hay chino codificado en el código fuente. ─────────────────────────────────
  const hostDir = join(ROOT, "src", "host");
  const offenders = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx"))) {
    const source = stripComments(readFileSync(join(hostDir, file), "utf8"));
    const lines = source.split(/\r?\n/);
    lines.forEach((line, index) => {
      if (/[\u4e00-\u9fff]/.test(line)) offenders.push(`${file}:${index + 1} ${line.trim().slice(0, 60)}`);
    });
  }
  assert(offenders.length === 0, `src/host/*.tsx No hay chino codificado en${offenders.length === 0 ? "" : `:\n  ${offenders.join("\n  ")}`}`);

  if (failures > 0) throw new Error(`i18n La afirmación falló (${failures} artículo)`);
  console.log("\ni18n Se aprobaron todas las afirmaciones (alineación de entrada/retroceso elemento por elemento/interpolación/análisis de idioma/tiempo relativo/sin chino codificado).");
} finally {
  await rm(TEMP, { force: true });
}
