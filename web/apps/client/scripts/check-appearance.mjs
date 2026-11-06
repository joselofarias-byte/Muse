/**
 * Afirmación de apariencia (tema/tamaño de fuente): escritura de atributos de tema de tres estados, mapeo de tamaño de fuente de cuatro niveles,theme-color actualizar,
 * La reversión de fallas de conmutación, los atributos raíz y los selectores de CSS son consistentes.
 *
 * Ejecute directamente con pura lógica;DOM La aplicación utiliza un sustituto de destino mínimo (no hace referencia a jsdom);
 * CSS Presione la estructura del código fuente para afirmar el selector y la ampliación, y verifique que las dos declaraciones oscuras no estén flotando.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".appearance-check.mjs");

const ENTRY = `
export {
  applyAppearance,
  FONT_SCALE_FACTOR,
  FONT_SCALES,
  readFontScale,
  readThemeMode,
  resolveScheme,
  THEME_MODES,
  themeColorFor,
} from "./src/host/appearance.ts";
export { readAppearance } from "./src/host/useAppearance.ts";
export { applySettingValue, settingValueOf } from "./src/host/settingsState.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "appearance-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    loader: { ".css": "empty" },
    external: ["react"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const PRIMITIVES = readFileSync(join(ROOT, "src", "components", "primitives.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const TOKEN_CSS = readFileSync(join(ROOT, "src", "design", "token-css.ts"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");

/** tomar un cierto `@media (…)` de bloques. */
function mediaBlock(source, query) {
  const marker = `@media (${query}) {`;
  const start = source.indexOf(marker);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    else if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  return "";
}

/** Obtener una regla determinada (selector + `{`) cuerpo de la declaración. */
function ruleBody(source, selector) {
  const start = source.indexOf(selector);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  const close = source.indexOf("}", open);
  if (open < 0 || close < 0) return "";
  return source.slice(open + 1, close);
}

/** Divida el cuerpo de la declaración en { "--ui-surface": "var(…)" } Tal mapeo. */
function declarations(body) {
  const map = {};
  for (const part of body.split(";")) {
    const index = part.indexOf(":");
    if (index < 0) continue;
    const name = part.slice(0, index).trim();
    const value = part.slice(index + 1).trim();
    if (name.length > 0) map[name] = value;
  }
  return map;
}

const settingsWith = (values, display = { language: "es", themeMode: "system", fontSizeScale: "1", themeId: "default" }) => ({
  snapshot: { groups: [], toggles: [], display, writableKeys: [], values },
  loading: false,
});

try {
  // ── 1. Tema de tres estados: escritura de atributos + prioridad ─────────────────────────────
  const makeTarget = (tokens = {}) => {
    const dataset = {};
    const meta = { content: "initial" };
    return { dataset, meta, readToken: (name) => tokens[name] ?? "" };
  };

  const system = makeTarget({ "--muse-color-scheme-light-surface": "#FAFAF8", "--muse-color-scheme-dark-surface": "#000000" });
  mod.applyAppearance(system, "system", "medium", false);
  assert(system.dataset.theme === "system", "system Escritura de patrones data-theme=system(Referido a consultas de los medios)");
  assert(system.dataset.fontScale === "medium", "El rango de tamaño de fuente se escribe como data-font-scale");

  const forcedDark = makeTarget({ "--muse-color-scheme-light-surface": "#FAFAF8", "--muse-color-scheme-dark-surface": "#000000" });
  mod.applyAppearance(forcedDark, "dark", "medium", false);
  assert(forcedDark.dataset.theme === "dark", "dark Forzar color oscuro (incluso si el sistema es de color claro)");

  const forcedLight = makeTarget();
  mod.applyAppearance(forcedLight, "light", "medium", true);
  assert(forcedLight.dataset.theme === "light", "light Forzar color claro (incluso si el sistema está oscuro)");

  assert(mod.resolveScheme("system", true) === "dark", "system + Sistema oscuro → dark");
  assert(mod.resolveScheme("system", false) === "light", "system + Color de luz del sistema → light");
  assert(mod.resolveScheme("light", true) === "light", "Forzar colores claros no se ve afectado por el sistema.");
  assert(mod.resolveScheme("dark", false) === "dark", "Forzar que el color oscuro no se vea afectado por el sistema.");

  assert(JSON.stringify(mod.THEME_MODES) === '["system","light","dark"]', "El tercer nivel del tema es system/light/dark");
  assert(mod.readThemeMode("DARK") === "dark" && mod.readThemeMode(" system ") === "system", "Los valores de tema son tolerantes a mayúsculas y minúsculas y espacios en blanco.");
  assert(mod.readThemeMode("blue") === undefined, "Los temas desconocidos se tratan como si no se hubieran dado.");

  // CSS: Dos declaraciones (consulta de medios + atributo) en color oscuro son consistentes entre sí
  const mediaDark = declarations(ruleBody(mediaBlock(PRIMITIVES, "prefers-color-scheme: dark"), ':root:not([data-theme="light"])'));
  const attrDark = declarations(ruleBody(PRIMITIVES, ':root[data-theme="dark"]'));
  assert(Object.keys(attrDark).length > 10, `El atributo bloque oscuro tiene ${Object.keys(attrDark).length} Declaración`);
  assert(
    JSON.stringify(mediaDark) === JSON.stringify(attrDark),
    "Las dos declaraciones oscuras de consultas de medios y atributos son consistentes una por una (para evitar derivas)",
  );
  assert(/var\(--muse-color-scheme-dark-surface\)/.test(attrDark["--ui-surface"] ?? ""), "Forzar que lo oscuro apunte al token de esquema oscuro");
  assert(PRIMITIVES.includes(':root:not([data-theme="light"])'), "Si fuerza el color claro cuando el sistema está oscuro, se omitirán las consultas de medios.");
  assert(CSS.includes(':root[data-theme="dark"] .host-scope { color-scheme: dark; }'), "host El alcance sigue el color oscuro forzado");
  assert(CSS.includes(':root[data-theme="light"] .host-scope { color-scheme: light; }'), "host El alcance sigue el color de luz forzado");

  // ── 2. theme-color actualizar ──────────────────────────────────────
  assert(system.meta.content === "#FAFAF8", `system(Color de luz del sistema)theme-color Tome una superficie clara:${system.meta.content}`);
  assert(makeTarget && mod.themeColorFor("dark", (name) => (name.endsWith("dark-surface") ? "#000000" : "#FAFAF8")) === "#000000", "themeColorFor Obtener token por esquema");
  const darkMeta = makeTarget({ "--muse-color-scheme-dark-surface": "#000000", "--muse-color-scheme-light-surface": "#FAFAF8" });
  mod.applyAppearance(darkMeta, "dark", "medium", false);
  assert(darkMeta.meta.content === "#000000", "Después de cambiar al color oscuro, el color del tema cambiará a color oscuro. surface");
  const noTokens = makeTarget();
  mod.applyAppearance(noTokens, "dark", "medium", false);
  assert(noTokens.meta.content === "initial", "Cuando el token no se puede leer, el meta no se cambia (no queda ninguna cadena vacía)");

  // ── 3. Mapeo de tamaño de fuente de cuatro niveles ─────────────────────────────────────────
  assert(JSON.stringify(mod.FONT_SCALES) === '["small","medium","large","xlarge"]', "La cuarta marcha es small/medium/large/xlarge");
  assert(mod.FONT_SCALE_FACTOR.small === 0.9 && mod.FONT_SCALE_FACTOR.medium === 1, "Rango pequeño 0.9, rango medio 1.0");
  assert(mod.FONT_SCALE_FACTOR.large === 1.1 && mod.FONT_SCALE_FACTOR.xlarge === 1.2, "Engranaje grande 1.1, extra grande 1.2");
  assert(mod.readFontScale("xlarge") === "xlarge" && mod.readFontScale("LARGE") === "large", "Tolerancia entre mayúsculas y minúsculas en el tamaño de fuente y el nombre de archivo");
  assert(mod.readFontScale("1") === "medium", "Número 1 normalizado medium");
  assert(mod.readFontScale(0.9) === "small" && mod.readFontScale("1.2") === "xlarge", "La ampliación digital está normalizada a la marcha más cercana.");
  assert(mod.readFontScale("huge") === undefined && mod.readFontScale(0) === undefined, "Si no reconoce el aumento, trátelo como si no estuviera dado.");

  for (const scale of ["small", "medium", "large", "xlarge"]) {
    assert(CSS.includes(`:root[data-font-scale="${scale}"]`), `CSS para ${scale} Se proporciona el selector de escala de fuente de datos.`);
  }
  const largeRule = declarations(ruleBody(CSS, ':root[data-font-scale="large"]'));
  assert(largeRule["--text-scale"] === "1.1", "large Identificador de archivo: la escala de texto está configurada en 1.1");
  assert(largeRule["--host-bubble-boost"] === "1.08", "large Por cierto, relaja las burbujas. 8%");
  const xlargeRule = declarations(ruleBody(CSS, ':root[data-font-scale="xlarge"]'));
  assert(xlargeRule["--host-bubble-boost"] === "1.16", "xlarge El engranaje se ensancha un 16% (0.7 × 1.16 ≈ 0.81 Sigue sin aparecer en pantalla)");

  // El token de composición tipográfica realmente se multiplica y el token de espaciado no se escala como un todo.
  assert(/calc\(\$\{value\}px \* var\(--text-scale, 1\)\)/.test(TOKEN_CSS), "Multiplicación de tokens de tamaño de fuente/alto de línea --text-scale");
  assert(/key !== "fontSize" && key !== "lineHeight"/.test(TOKEN_CSS), "Solo escale fontSize / lineHeight, dejando el espaciado sin cambios");
  assert(/max-width: calc\(100% \* var\(--muse-component-bubble-max-width-ratio\) \* var\(--host-bubble-boost\)\)/.test(CSS), "El ancho máximo de la burbuja aumenta con el tamaño de fuente.");
  assert(/--host-page: calc\(var\(--muse-space-screen\) \* var\(--text-scale\)\)/.test(CSS), "Los márgenes se ajustan proporcionalmente al tamaño de fuente.");
  assert(/padding: calc\(var\(--muse-component-bubble-inner-padding-y\) \* var\(--text-scale\)\)/.test(CSS), "Los márgenes internos de las burbujas se ajustan proporcionalmente al tamaño de fuente (sin bordes)");

  // ── 4. Leer la apariencia del valor establecido + revertir en caso de falla ─────────────────────────────
  const darkSnapshot = settingsWith([
    { key: "appearance.themeMode", type: "enum", value: "dark" },
    { key: "appearance.fontSizeScale", type: "enum", value: "xlarge" },
  ]);
  const read0 = mod.readAppearance(darkSnapshot);
  assert(read0.mode === "dark" && read0.scale === "xlarge", "Lea el tema y el tamaño de fuente a partir de los valores (la clave también se puede reconocer con un prefijo)");

  const legacy = settingsWith(undefined, { language: "es", themeMode: "light", fontSizeScale: "1.1", themeId: "default" });
  const readLegacy = mod.readAppearance(legacy);
  assert(readLegacy.mode === "light" && readLegacy.scale === "large", "El antiguo Host también puede leerlo cuando solo se utiliza la pantalla.");

  const empty = mod.readAppearance({ loading: false });
  assert(empty.mode === "system" && empty.scale === "medium", "Volver si no hay configuración system + medium");

  // Conmutación optimista:values Cambie tan pronto como cambie el tema; restaurar después de revertir
  const optimistic = mod.applySettingValue(darkSnapshot.snapshot, "appearance.themeMode", "light");
  assert(mod.readAppearance({ ...darkSnapshot, snapshot: optimistic }).mode === "light", "El tema se aclara inmediatamente después de una escritura optimista.");
  const rolledBack = mod.applySettingValue(optimistic, "appearance.themeMode", "dark");
  assert(mod.readAppearance({ ...darkSnapshot, snapshot: rolledBack }).mode === "dark", "El tema vuelve al color oscuro después de la reversión");
  assert(CHAT.includes("useAppearance(settings)"), "El shell de chat conecta los valores de configuración con la apariencia.");
  assert(/requestSettings\(\)/.test(CHAT), "Simplemente obtenga la configuración una vez conectado (sin esperar a abrir la página de configuración)");

  // ── 5. Los elementos autodibujados siguen el esquema (solo use tokens) ────────────────────
  const tokenDriven = [
    [".host-lightbox", "--host-scrim"],
    [".host-voice-track", "--host-veil"],
    [".host-details", "--host-veil"],
    [".host-bubble-tools", "flex"],
  ];
  for (const [selector, needle] of tokenDriven) {
    const body = ruleBody(CSS, `${selector} {`);
    assert(body.length > 0 && body.includes(needle), `${selector} Ir token (${needle})`);
  }
  assert(
    /\.muse-skeleton \{[\s\S]{0,400}?var\(--ui-surface-variant\)/.test(PRIMITIVES),
    "El esqueleto utiliza el color de fondo del token, que cambiará automáticamente a colores oscuros.",
  );

  if (failures > 0) throw new Error(`Error al afirmar la apariencia (${failures} artículo)`);
  console.log("\nSe aprobaron todas las afirmaciones de apariencia (tema de tres estados/tamaño de fuente de cuatro niveles/color del tema/retroceso/vinculación).");
} finally {
  await rm(TEMP, { force: true });
}
