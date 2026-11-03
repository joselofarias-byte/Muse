/**
 * Establezca aserciones para la búsqueda: filtrado local, rutas de navegación, enrutamiento directo, estado nulo, cableado de teclas de acceso directo.
 *
 * Los datos sólo se utilizan en el fixture. `settings.snapshot`(groups + entries + values),
 * Consistente con el tiempo de ejecución: todos los resultados de búsqueda provienen del filtrado local, no hay solicitudes de protocolo involucradas.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-search-check.mjs");

const ENTRY = `
export { breadcrumbText, buildSettingsSearchRows, searchSettings, settingsPageTitle } from "./src/host/settingsQuery.ts";
export { SettingsSearch } from "./src/host/SettingsSearch.tsx";
export { SettingsValuePage } from "./src/host/SettingsValuePage.tsx";
export { createElement } from "react";
export { renderToStaticMarkup } from "react-dom/server";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-search-entry.tsx", loader: "tsx" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    loader: { ".css": "empty" },
    external: ["react", "react-dom", "react-dom/server"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const render = (element) => mod.renderToStaticMarkup(element);

const entry = (id, title, extra = {}) => ({
  id,
  titleKey: `key_${id}`,
  title,
  groupKey: "group",
  iconKey: "chat",
  kind: "link",
  ...extra,
});

const SNAPSHOT = {
  groups: [
    {
      key: "settings_group_appearance",
      groupTitle: "Clasificación de apariencia",
      entries: [
        entry("appearance", "Apariencia", { desc: "Tema y tamaño de fuente" }),
        entry("miniphone", "pequeño teléfono celular", { enabled: false }),
      ],
    },
    {
      key: "settings_group_chat",
      groupTitle: "Categorías de chat",
      entries: [
        entry("chat", "charlar", { desc: "Mensajes y Generación" }),
        entry("pii", "Protección de la privacidad", { kind: "switch", toggleKey: "piiGuardEnabled", checked: true }),
        entry("weird", "Entrada desconocida", { kind: "mystery" }),
      ],
    },
  ],
  toggles: [{ key: "piiGuardEnabled", enabled: true }],
  display: { language: "es", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: [],
  values: [
    { key: "appearance.themeMode", type: "enum", value: "dark", options: [{ value: "dark", label: "PROTO-oscuro" }] },
    { key: "chat.showToolCalls", type: "boolean", value: true },
    { key: "unmapped.thing", type: "text", value: "x" },
  ],
};

const results = (query) => mod.searchSettings(SNAPSHOT, query);
const titles = (query) => results(query).map((hit) => hit.title);

try {
  // ── 1. Índice: solo coloque entradas que se puedan ingresar y valores que se puedan atribuir ────────────────────────
  const rows = mod.buildSettingsSearchRows(SNAPSHOT);
  assert(rows.length === 4, `Índice 4 filas (2 entradas accesibles + 2 valores atribuibles): Actual ${rows.length}`);
  assert(!rows.some((row) => row.title === "pequeño teléfono celular"), "enabled=false Las entradas no están indexadas");
  assert(!rows.some((row) => row.title === "Protección de la privacidad"), "La entrada del cambio no está indexada (está en la página de categorías y no tiene adónde ir)");
  assert(!rows.some((row) => row.title === "Entrada desconocida"), "Las entradas con tipo desconocido no están indexadas");
  assert(!rows.some((row) => row.title.includes("Unmapped")), "Los valores que no se pueden devolver a las subpáginas no se indexan");

  // ── 2. Filtrado: se puede acceder al nombre de la entrada/nombre de la categoría/título del valor ───────────────────
  assert(titles("Apariencia").includes("Apariencia"), "Golpeado por nombre de artículo");
  assert(titles("Categorías de chat").includes("charlar"), "Golpeado por nombre de categoría");
  assert(titles("theme").includes("Theme Mode"), "título de fila alcanzado por valor (palabra derivada de clave)");
  assert(titles("THEME").includes("Theme Mode"), "No distingue entre mayúsculas y minúsculas");
  assert(titles("  theme  ").includes("Theme Mode"), "Se ignoran los espacios en blanco en ambos extremos de las palabras clave.");
  assert(titles("Apariencia").includes("Theme Mode"), "El valor también se puede acceder a través del \"título de la subpágina a la que pertenece\"");
  assert(results("Privacidad").length === 0, "El resultado está vacío cuando solo coincide la entrada del interruptor (el interruptor no está en el índice)");
  assert(results("palabra que no existe").length === 0, "Si no hay coincidencia, se devuelve una matriz vacía");
  assert(results("").length === 0, "Las palabras clave vacías no devuelven ninguna fila (48 filas no se actualizan)");

  // ── 3. Clasificación: las visitas al título tienen prioridad sobre las visitas de solo ruta de navegación ──────────────────────
  const appearanceHits = results("Apariencia");
  assert(appearanceHits[0]?.title === "Apariencia", `Principales éxitos del título:${appearanceHits.map((hit) => hit.title).join(",")}`);
  assert(appearanceHits.length === 2, "\"Apariencia\" afecta tanto a la entrada como a la fila de valor debajo de ella.");

  // ── 4. Migas de pan: clasificación › entrada ─────────────────────────────────────
  const entryHit = results("Apariencia").find((hit) => hit.title === "Apariencia");
  assert(
    entryHit !== undefined && mod.breadcrumbText(entryHit) === "Clasificación de apariencia › Apariencia",
    `Migas de pan de entrada:${entryHit === undefined ? "" : mod.breadcrumbText(entryHit)}`,
  );
  const valueHit = results("theme").find((hit) => hit.title === "Theme Mode");
  assert(
    valueHit !== undefined && mod.breadcrumbText(valueHit) === "Apariencia › Theme Mode",
    `Rutas de navegación de la fila de valores:${valueHit === undefined ? "" : mod.breadcrumbText(valueHit)}`,
  );

  // ── 5. llegar a la meta ───────────────────────────────────────────────
  assert(
    entryHit?.target.kind === "entry" && entryHit.target.entryId === "appearance",
    "El resultado de la entrada apunta a la entrada en la subpágina correspondiente. id",
  );
  assert(
    valueHit?.target.kind === "value" &&
      valueHit.target.valueKey === "appearance.themeMode" &&
      valueHit.target.pageId === "appearance",
    "El valor resultado trae la subpágina a la que pertenece y el elemento a resaltar. key",
  );
  assert(mod.settingsPageTitle(SNAPSHOT, { id: "chat", entryIds: ["chat"], keyTokens: [] }) === "charlar", "Títulos de subpáginas tomados de las entradas del índice.");

  // ── 6. Representación: fila de resultados / vacía / mensaje ─────────────────────────────
  const searchView = (query) =>
    render(
      mod.createElement(mod.SettingsSearch, {
        snapshot: SNAPSHOT,
        query,
        onQueryChange: () => undefined,
        onPick: () => undefined,
        onClose: () => undefined,
      }),
    );
  const hitHtml = searchView("theme");
  assert(hitHtml.includes("Theme Mode") && hitHtml.includes("Apariencia › Theme Mode"), "La fila de resultados proporciona un título y rutas de navegación.");
  assert(hitHtml.includes('role="listbox"') && hitHtml.includes('role="option"'), "La lista resultante tiene semántica de cuadro de lista/opción.");
  assert(hitHtml.includes('aria-label="Salir de la búsqueda"'), "El estado de búsqueda ha cerrado la entrada.");
  const idle = searchView("");
  assert(idle.includes("Escribe para buscar") && !idle.includes('role="option"'), "Dé un mensaje cuando la palabra clave esté vacía y no enumere los resultados.");
  const empty = searchView("palabra que no existe");
  assert(empty.includes("No hay ajustes que coincidan") && empty.includes("Borrar búsqueda"), "Si no hay ninguna coincidencia, se ofrece borrar la búsqueda.");
  assert(empty.includes('role="status"'), "Los estados vacíos tienen semántica de estado");
  const withResult = searchView("Apariencia");
  assert((withResult.match(/role="option"/g) ?? []).length === 2, "El número de resultados renderizados es consistente con los resultados filtrados.");

  // ── 7. Cableado: teclas de acceso directo y resaltado ─────────────────────────────────────
  const hostSettings = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(hostSettings.includes('event.key === "/"') && hostSettings.includes("metaKey"), "Admite / y Cmd/Ctrl+K para abrir la búsqueda");
  assert(hostSettings.includes('event.key.toLowerCase() === "k"'), "Cmd/Ctrl+K La sentencia está en");
  assert(hostSettings.includes("event.preventDefault()"), "Las teclas de método abreviado evitan el comportamiento predeterminado");
  assert(hostSettings.includes('aria-label={t("settings.search")}'), "Hay una entrada de búsqueda en la parte superior de la página de categorías.");
  assert(hostSettings.includes("host-settings-search"), "La entrada de búsqueda es un contenedor de techo.");
  assert(hostSettings.includes("<SettingsSearch"), "El estado de búsqueda en pantalla completa se bloquea aquí");
  assert(hostSettings.includes("focusKey: target.valueKey"), "Cuando el resultado del valor se pasa directamente, la focusKey se pasa a la subpágina.");

  const searchSource = readFileSync(join(ROOT, "src", "host", "SettingsSearch.tsx"), "utf8");
  assert(/event\.key (===|!==) "Escape"/.test(searchSource), "Modo de búsqueda Esc cerrar");
  assert(searchSource.includes('event.key === "ArrowDown"') && searchSource.includes('event.key === "ArrowUp"'), "Teclas arriba y abajo para mover y seleccionar");
  assert(searchSource.includes('event.key !== "Enter"') && searchSource.includes("onPick(hit)"), "Enter Ingrese los elementos seleccionados");

  const valuePageHtml = render(
    mod.createElement(mod.SettingsValuePage, {
      page: { id: "chat", entryIds: ["chat"], keyTokens: [] },
      title: "charlar",
      snapshot: SNAPSHOT,
      loading: false,
      error: undefined,
      capabilities: { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true, messageWrite: true, multiSelect: true },
      focusKey: "chat.showToolCalls",
      onBack: () => undefined,
      onRetry: () => undefined,
      onWrite: () => undefined,
    }),
  );
  assert(valuePageHtml.includes("host-setting-highlight"), "Resalte la línea de destino al llegar directamente");
  assert((valuePageHtml.match(/host-setting-highlight/g) ?? []).length === 1, "Resalta solo una línea");
  assert(/host-setting-highlight[\s\S]{0,400}Show Tool Calls/.test(valuePageHtml), "Lo que se resalta es la fila a la que apunta el resultado.");

  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-settings-search", "host-search-trigger", "host-search-result", "host-search-empty", "host-setting-highlight"]) {
    assert(css.includes(`.${selector}`), `definido en el estilo .${selector}`);
  }
  assert(/\.host-settings-search\s*\{[^}]*position:\s*sticky/.test(css), "La entrada de búsqueda está montada en el techo.");

  if (failures > 0) throw new Error(`Error al establecer la aserción de búsqueda (${failures} artículo)`);
  console.log("\nConfigure las aserciones de búsqueda para que pasen todas (filtro/rutas de navegación/directo/vacío/teclas de acceso directo).");
} finally {
  await rm(TEMP, { force: true });
}
