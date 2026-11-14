/**
 * Establecer la afirmación de representación de la página de inicio: texto de la interfaz**solo**Leer campos de protocolo.
 *
 * `settings.snapshot` Ahora entregue directamente el localizado `title` / `desc` / `groupTitle`,
 * Web El cliente ya no guarda ninguna tabla de copia (`host/settings-copy.ts` eliminado). Este guión toma lo real.
 * `HostSettings` Los componentes se representan en HTML, con una copia falsa reconocible al instante (PROTO-…) Verificación:
 *  · El título del grupo/título de entrada/descripción provienen del campo de protocolo, no del nombre del recurso ni de la tabla local;
 *  · `titleKey` / `key` Sólo se utiliza como marca y no se filtrará a la interfaz;
 *  · kind → controlar(link/action flecha derecha,switch interruptor),iconKey icono, cargando/El estado de fracaso sigue ahí.
 *
 * Uso:npm --workspace @muse/client run test
 * Nota: Confíe en el esbuild que viene con vite para convertir los componentes en ESM temporal y luego importarlos sin agregar nuevas dependencias;
 *      Los archivos temporales se eliminan finalmente.
 */
import { build } from "esbuild";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-render-check.mjs");

const ENTRY = `
export { HostSettings } from "./src/host/HostSettings.tsx";
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

/** Texto que solo existe en los campos del protocolo: ningún nombre de tabla/recurso local puede crear estas cadenas. */
const groupTitle = "PROTO-GROUP-Modelos y Proveedores";
const linkTitle = "PROTO-ENTRY-proveedor";
const linkDesc = "PROTO-DESC-Proveedor de Modelos de Gestión";
const switchTitle = "PROTO-ENTRY-PII";
const bareTitle = "PROTO-ENTRY-proxy de red";

const snapshot = {
  groups: [
    {
      key: "settings_screen_group_models",
      groupTitle,
      entries: [
        {
          id: "provider",
          titleKey: "settings_screen_provider",
          descKey: "settings_screen_provider_desc",
          title: linkTitle,
          desc: linkDesc,
          groupKey: "settings_screen_group_models",
          iconKey: "provider",
          kind: "link",
          enabled: true,
        },
      ],
    },
    {
      key: "settings_screen_group_privacy",
      groupTitle: "PROTO-GROUP-Privacidad",
      entries: [
        {
          id: "pii_guard",
          titleKey: "settings_screen_pii_guard",
          descKey: "settings_screen_pii_guard_desc",
          title: switchTitle,
          desc: "PROTO-DESC-PII",
          groupKey: "settings_screen_group_privacy",
          iconKey: "shield",
          kind: "switch",
          toggleKey: "piiGuardEnabled",
          checked: true,
        },
        {
          id: "proxy",
          titleKey: "proxy_title",
          title: bareTitle,
          groupKey: "settings_screen_group_privacy",
          iconKey: "proxy",
          kind: "action",
        },
      ],
    },
  ],
  toggles: [{ key: "piiGuardEnabled", enabled: true }],
  display: { language: "es", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: ["piiGuardEnabled"],
};

async function render() {
  await build({
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-render-entry.tsx", loader: "tsx" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    loader: { ".css": "empty" },
    // react / react-dom Permanece externo y Node lo resuelve desde el espacio de trabajo node_modules.
    external: ["react", "react-dom", "react-dom/server"],
    outfile: TEMP,
    logLevel: "warning",
  });
  const module = await import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
  return (settings) =>
    module.renderToStaticMarkup(
      module.createElement(module.HostSettings, {
        settings,
        capabilities: { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true },
        onBack: () => undefined,
        onRetry: () => undefined,
        onToggle: () => undefined,
        onWrite: () => undefined,
      assistants: [],
      providers: [],
      assistantDetails: {},
      assistantDetailUnsupported: false,
      memory: { items: [], hasMore: false, loading: false, error: undefined, query: undefined, unsupported: false },
      capabilityFlags: [],
      onRequestAssistantDetail: () => undefined,
      onRequestProviders: () => undefined,
      onRequestMemory: () => undefined,
      onStartSession: () => undefined,
      knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
      knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      onRequestKnowledge: () => undefined,
      onRequestKnowledgeDocuments: () => undefined,
      onRequestTasks: () => undefined,
        assistants: [],
        providers: [],
        assistantDetails: {},
        assistantDetailUnsupported: false,
        memory: { items: [], hasMore: false, loading: false, error: undefined, query: undefined, unsupported: false },
        capabilityFlags: [],
        onRequestAssistantDetail: () => undefined,
        onRequestProviders: () => undefined,
        onRequestMemory: () => undefined,
        onStartSession: () => undefined,
      knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
      knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      onRequestKnowledge: () => undefined,
      onRequestKnowledgeDocuments: () => undefined,
      onRequestTasks: () => undefined,
        knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
        knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
        tasks: { items: [], loading: false, error: undefined, unsupported: false },
        onRequestKnowledge: () => undefined,
        onRequestKnowledgeDocuments: () => undefined,
        onRequestTasks: () => undefined,
      }),
    );
}

const renderSettings = await render();
try {
  const html = renderSettings({ snapshot, loading: false });

  assert(html.includes(groupTitle), "El encabezado del grupo proviene del protocolo. groupTitle");
  assert(html.includes(linkTitle) && html.includes(linkDesc), "Título de la entrada/Descripción del protocolo title/desc");
  assert(html.includes(switchTitle) && html.includes(bareTitle), "El título de cada entrada proviene del campo del protocolo.");
  assert(!html.includes("settings_screen_provider"), "Los nombres de los recursos no se filtrarán en la interfaz.");
  assert(!html.includes("settings_screen_group_models"), "Los nombres de recursos agrupados no se filtrarán a la interfaz");
  assert(!html.includes("settings_screen_pii_guard"), "El nombre del recurso de la entrada del conmutador no se filtrará a la interfaz");

  assert((html.match(/role="switch"/g) ?? []).length === 1, "kind=switch Representado como interruptor");
  assert(html.includes('aria-checked="true"'), "El valor inicial del interruptor es checked");
  assert((html.match(/muse-listrow-chevron/g) ?? []).length === 2, "link/action Renderizar flecha derecha");
  assert((html.match(/class="muse-settings-icon"/g) ?? []).length === 3, "Hay un ícono iconKey en el lado izquierdo de cada fila");
  assert(html.includes('class="muse-listrow-title">' + bareTitle), "Los elementos sin descripción solo muestran el título.");
  assert(!html.includes(">undefined<"), "La copia opcional no se renderizará cuando falte undefined");
  assert(html.includes("muse-section-label"), "Para títulos de grupo SectionLabel");

  const loading = renderSettings({ loading: true });
  // Retraso del esqueleto 300 ms: cuando se ingresa a la página por primera vez, solo se proporciona un espacio en blanco que el lector de pantalla puede leer e informar, y el esqueleto no se muestra.
  assert(
    loading.includes('role="status"') && !loading.includes("muse-skeleton"),
    "Primero dé un espacio tranquilo durante la carga (el esqueleto se retrasará 300 ms antes de ser liberado)",
  );

  const failed = renderSettings({ loading: false, error: "PROTO-ERROR-Error de lectura" });
  assert(failed.includes("PROTO-ERROR-Error de lectura") && failed.includes(">Reintentar<"), "Mostrar barra de error reintentable en caso de falla");

  if (failures > 0) throw new Error(`Error al establecer la aserción de representación (${failures} artículo)`);
  console.log("\nEstablezca las afirmaciones de representación en todas las pasadas (campo de protocolo de solo lectura del texto de la interfaz).");
} finally {
  await rm(TEMP, { force: true });
}
