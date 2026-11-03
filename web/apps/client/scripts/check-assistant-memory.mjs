/**
 * Afirmaciones para páginas auxiliares y de memoria: lista/Representación detallada, parámetros de solo lectura, paginación de memoria y búsqueda de estados vacíos y avisos de bits de capacidad faltantes.
 *
 * El componente se renderiza directamente usando renderToStaticMarkup (la vista detallada se exporta por separado y no depende de los clics).
 * La ruta de interacción (haga clic para ir, desplácese hasta la parte inferior e ingrese para buscar) está cableada y afirmada de acuerdo con el código fuente.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".assistant-memory-check.mjs");

const ENTRY = `
export { AssistantsPage, AssistantDetailView } from "./src/host/AssistantsPage.tsx";
export { MemoryPage } from "./src/host/MemoryPage.tsx";
export { ASSISTANT_DETAIL_FLAGS, MEMORY_FLAGS, isUnsupportedCommandError, supportsFlag } from "./src/host/featureCapabilities.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "assistant-memory-entry.tsx", loader: "tsx" },
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

const HOST_SETTINGS = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
const HOST_CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const ASSISTANTS = [
  { id: "a1", name: "PROTO-Muse", isDefault: true, avatarUri: "/api/media/face-1" },
  { id: "a2", name: "PROTO-Iris" },
];
const PROVIDERS = [{ id: "p1", name: "OpenAI", enabled: true, models: [{ id: "gpt-x", name: "PROTO-GPT-X" }] }];
const DETAIL = {
  id: "a1",
  name: "PROTO-Muse",
  description: "PROTO-Introducción",
  modelId: "gpt-x",
  providerId: "p1",
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 4096,
  personaSummary: "PROTO-Resumen del personaje",
  systemPromptLength: 1234,
  toolCount: 12,
  contextMessageSize: 40,
  reasoningLevel: "medium",
  enabled: true,
};

try {
  // ── 1. negociación de competencias ─────────────────────────────────────────────
  assert(mod.supportsFlag(undefined, mod.MEMORY_FLAGS) === true, "El viejo anfitrión (incompetente) lo intentará primero.");
  assert(mod.supportsFlag([], mod.MEMORY_FLAGS) === false, "Los bits de habilidad se emiten pero no incluyen memory → No compatible");
  assert(mod.supportsFlag(["memory"], mod.MEMORY_FLAGS) === true, "Se pueden identificar las capacidades declaradas.");
  assert(mod.isUnsupportedCommandError("unsupported host command: memory.list") === true, "Incluso si se rechaza en tiempo de ejecución, se reconocerá.");
  assert(mod.isUnsupportedCommandError("focus_not_held") === false, "Los errores comerciales no se consideran \"no admitidos\"");

  // ── 2. lista de asistentes ─────────────────────────────────────────────
  const listHtml = render(
    mod.createElement(mod.AssistantsPage, {
      assistants: ASSISTANTS,
      providers: PROVIDERS,
      details: { a1: DETAIL },
      flags: ["assistants_detail", "providers"],
      loading: false,
      detailUnsupported: false,
      onRequestDetail: () => undefined,
      onRequestProviders: () => undefined,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(listHtml.includes("PROTO-Muse") && listHtml.includes("PROTO-Iris"), "Lista que muestra todos los nombres de los asistentes");
  assert(listHtml.includes(">Predeterminado<"), "El asistente predeterminado está marcado");
  assert(listHtml.includes("PROTO-GPT-X"), "Detalles + los proveedores pueden revelar el nombre del modelo");
  assert((listHtml.match(/muse-listrow is-interactive/g) ?? []).length === 2, "Se puede hacer clic en cada fila para obtener más detalles.");
  assert(listHtml.includes('src="/api/media/face-1"'), "El avatar se representa usando la dirección /api/media");

  const emptyList = render(
    mod.createElement(mod.AssistantsPage, {
      assistants: [],
      providers: [],
      details: {},
      flags: ["assistants_detail"],
      loading: false,
      detailUnsupported: false,
      onRequestDetail: () => undefined,
      onRequestProviders: () => undefined,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(emptyList.includes("Aún no hay asistente en el teléfono"), "Dejar vacío cuando no haya ayudante.");

  // ── 3. Detalles del asistente ─────────────────────────────────────────────
  const detailHtml = render(
    mod.createElement(mod.AssistantDetailView, {
      assistant: ASSISTANTS[0],
      detail: DETAIL,
      modelName: "PROTO-GPT-X",
      supported: true,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(detailHtml.includes("PROTO-Introducción"), "Introducción a la visualización de detalles");
  assert(detailHtml.includes("PROTO-Resumen del personaje") && detailHtml.includes("host-persona"), "El resumen del personaje es plegable (6 truncamiento de línea)");
  assert(detailHtml.includes("Expandir"), "El resumen del personaje da la entrada a la expansión.");
  assert(detailHtml.includes("temperatura") && detailHtml.includes("0.7"), "Pantalla de solo lectura de temperatura");
  assert(detailHtml.includes("4,096"), "Token máximo agrupado por Intl");
  assert(detailHtml.includes("configurado 1,234 palabra"), "La palabra solicitada solo muestra la cantidad de palabras, no el texto.");
  assert(!detailHtml.includes("Texto de palabra de aviso del sistema"), "El texto de la palabra solicitada no aparece en la página.");
  assert(detailHtml.includes("12 un"), "Visualización del número de herramienta");
  assert(/role="switch"/.test(detailHtml) && detailHtml.includes("disabled"), "El interruptor de habilitación es de solo lectura.");
  assert(!/<input/i.test(detailHtml), "No aparece ningún cuadro de entrada para parámetros de solo lectura");
  assert(detailHtml.includes("Iniciar una conversación con este asistente"), "La parte superior puede iniciar directamente una conversación.");

  const unsupportedDetail = render(
    mod.createElement(mod.AssistantDetailView, {
      assistant: ASSISTANTS[0],
      detail: undefined,
      modelName: undefined,
      supported: false,
      onStartSession: () => undefined,
      onBack: () => undefined,
    }),
  );
  assert(unsupportedDetail.includes("La versión actual no admite la visualización de detalles del asistente en la página web."), "Dé un mensaje en lugar de dejar un espacio en blanco cuando falte un espacio de capacidad.");

  // ── 4. pagina de memoria ───────────────────────────────────────────────
  const memoryPage = (overrides) =>
    render(
      mod.createElement(mod.MemoryPage, {
        memory: {
          items: [],
          hasMore: false,
          loading: false,
          error: undefined,
          query: undefined,
          unsupported: false,
          ...overrides,
        },
        supported: true,
        onRequest: () => undefined,
        onBack: () => undefined,
      }),
    );

  const items = [
    { id: "m1", content: "PROTO-contenido de la memoria", createdAt: Date.now(), source: "conversation", pinned: true },
    { id: "m2", content: "PROTO-Artículo 2", createdAt: Date.now() - 3 * 24 * 60 * 60 * 1000, source: "manual" },
  ];
  const listMemory = memoryPage({ items, hasMore: true });
  assert(listMemory.includes("PROTO-contenido de la memoria") && listMemory.includes("PROTO-Artículo 2"), "Listar todos los contenidos de la memoria");
  assert(listMemory.includes("fijar hacia arriba"), "etiqueta de alfiler");
  assert(listMemory.includes("conversation"), "etiqueta fuente");
  assert(listMemory.includes("justo ahora") && listMemory.includes("hace 3 días"), "El tiempo va localizado en relación con el tiempo.");
  assert(listMemory.includes("cargar más"), "Habrá más puntos de entrada para la carga.");
  const lastMemory = memoryPage({ items, hasMore: false });
  assert(lastMemory.includes("no más") && !lastMemory.includes("cargar más"), "Al llegar al final muestra \"No más\"");
  const emptyMemory = memoryPage({});
  assert(emptyMemory.includes("Aún no hay memoria"), "Proporcionar orientación cuando no hay memoria.");
  const noHit = memoryPage({ items: [], query: "PROTO-palabras clave" });
  assert(noHit.includes("PROTO-palabras clave") && noHit.includes("Borrar"), "Las palabras clave y \"Borrar\" se muestran cuando no se encuentran resultados en la búsqueda.");
  assert(memoryPage({ unsupported: true }).includes("La versión actual no admite la visualización de recuerdos en la página web."), "Preguntar si no es compatible");
  assert(memoryPage({ error: "PROTO-error" }).includes("PROTO-error"), "El error de lectura muestra un error y vuelve a intentarlo");

  // ── 5. Cableado ───────────────────────────────────────────────
  assert(HOST_SETTINGS.includes('openPage.entry.id === "assistants"') && HOST_SETTINGS.includes("<AssistantsPage"), "Ingrese el elemento \"Asistente\" en la configuración para ingresar a la página del asistente");
  assert(HOST_SETTINGS.includes('openPage.entry.id === "memory"') && HOST_SETTINGS.includes("<MemoryPage"), "La entrada \"Memoria\" ingresa a la página de memoria");
  assert(HOST_CHAT.includes("onStartSession={startSessionWith}") && HOST_CHAT.includes('type: "session.new", assistantId'), "La página de detalles puede abrir una sesión con asistenteId");
  assert(CONNECTION.includes('type: "assistants.detail", assistantId'), "Detalles del asistente solicitados por ID de asistente");
  assert(CONNECTION.includes('type: "memory.list", limit: MEMORY_PAGE_SIZE') && CONNECTION.includes("cursor: options.cursor"), "Lista de memoria con límite y paginación del cursor.");
  assert(CONNECTION.includes('type: "memory.search", query, limit: MEMORY_PAGE_SIZE'), "cinta de búsqueda de memoria query");
  assert(CONNECTION.includes("MEMORY_PAGE_SIZE = 30"), "30 artículos por página (Host valor predeterminado)");
  assert(/host-assistant-actions|host-memory-list|host-persona/.test(CSS), "Existe una nueva clase de estilo.");

  if (failures > 0) throw new Error(`El asistente con la afirmación de la memoria falló (${failures} artículo)`);
  console.log("\nTodas las afirmaciones del asistente y de la memoria pasan (lista/detalles/paginación/búsqueda/bits de capacidad).");
} finally {
  await rm(TEMP, { force: true });
}
