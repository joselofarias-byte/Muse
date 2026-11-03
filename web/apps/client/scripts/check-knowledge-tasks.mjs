/**
 * Aserciones para páginas de base de conocimientos y páginas de tareas programadas: representación de dos páginas, paginación de documentos, formato de campos de tareas, estados vacíos y bits de capacidad.
 *
 * Los componentes se renderizan directamente usando renderToStaticMarkup; Las interacciones (haga clic en la base de conocimientos, desplácese hasta la parte inferior) y el cableado de solicitudes se afirman de acuerdo con el código fuente.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".knowledge-tasks-check.mjs");

const ENTRY = `
export { KnowledgePage, KnowledgeDocumentsView } from "./src/host/KnowledgePage.tsx";
export { TasksPage } from "./src/host/TasksPage.tsx";
export { KNOWLEDGE_FLAGS, TASK_FLAGS, supportsFlag } from "./src/host/featureCapabilities.ts";
export { formatRelativeTime, formatBytes } from "./src/i18n/format.ts";
export { ES, EN } from "./src/i18n/messages.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "knowledge-tasks-entry.tsx", loader: "tsx" },
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
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const KNOWLEDGE = {
  bases: [
    { id: "kb1", name: "PROTO-base de conocimientos", documentCount: 12, chunkCount: 345, createdAt: 1, updatedAt: 2, embeddingBackend: "PROTO-bge-m3" },
    { id: "kb2", name: "PROTO-Sin biblioteca de vectores", documentCount: 0, chunkCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  loading: false,
  error: undefined,
  unsupported: false,
};

const DOCUMENTS = (items, hasMore) => ({ kbId: "kb1", items, hasMore, loading: false, error: undefined, unsupported: false });

const knowledgePage = (overrides = {}) =>
  render(
    mod.createElement(mod.KnowledgePage, {
      knowledge: KNOWLEDGE,
      documents: DOCUMENTS([], false),
      supported: true,
      onRequestBases: () => undefined,
      onRequestDocuments: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

const tasksPage = (overrides = {}) =>
  render(
    mod.createElement(mod.TasksPage, {
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      supported: true,
      onRequest: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

try {
  // ── 1. Lista de la base de conocimientos ───────────────────────────────────────────
  const listHtml = knowledgePage();
  assert(listHtml.includes("PROTO-base de conocimientos") && listHtml.includes("PROTO-Sin biblioteca de vectores"), "Listar todas las bases de conocimiento");
  assert(listHtml.includes("12 documentos · 345 trozos"), "La cantidad de documentos y fragmentos se muestra en formato localizado.");
  assert(listHtml.includes("PROTO-bge-m3"), "Mostrar backend incrustado");
  assert(listHtml.includes("No configurado"), "Marcador de posición cuando no hay un backend vectorial");
  assert((listHtml.match(/muse-listrow is-interactive/g) ?? []).length === 2, "Se puede hacer clic en cada base de conocimientos para acceder a la lista de documentos.");
  assert(listHtml.includes("Importar y gestionar la base de conocimientos en el teléfono celular."), "Escriba explícitamente de forma clara y de solo lectura: importe y administre en teléfonos celulares");

  const emptyKnowledge = knowledgePage({ knowledge: { ...KNOWLEDGE, bases: [] } });
  assert(emptyKnowledge.includes("Aún no existe una base de conocimientos"), "Dar estado vacío cuando no hay base de conocimientos");
  const unsupportedKnowledge = knowledgePage({ supported: false });
  assert(unsupportedKnowledge.includes("La versión actual no permite ver la base de conocimientos en la página web."), "Dar indicaciones cuando falten espacios de habilidad");
  const failedKnowledge = knowledgePage({ knowledge: { ...KNOWLEDGE, error: "PROTO-error" } });
  assert(failedKnowledge.includes("PROTO-error") && failedKnowledge.includes(">Inténtalo de nuevo<"), "El error de lectura muestra un error y vuelve a intentarlo");

  // ── 2. Lista de documentos y paginación. ───────────────────────────────────────
  const doc = (id, title, chunks, size, status) => ({ id, title, chunkCount: chunks, sizeBytes: size, createdAt: Date.now(), status });
  const docsView = (items, hasMore, overrides = {}) =>
    render(
      mod.createElement(mod.KnowledgeDocumentsView, {
        base: KNOWLEDGE.bases[0],
        documents: { kbId: "kb1", items, hasMore, loading: false, error: undefined, unsupported: false },
        supported: true,
        onRequestDocuments: () => undefined,
        onBack: () => undefined,
        ...overrides,
      }),
    );
  const docsWithMore = docsView([doc("d1", "PROTO-Documento 1", 12, 2048, "indexed"), doc("d2", "PROTO-Documento 2", 3, 300)], true);
  assert(docsWithMore.includes("PROTO-Documento 1") && docsWithMore.includes("PROTO-Documento 2"), "Títulos de documentos listados");
  assert(docsWithMore.includes("12 trozos"), "Número de fragmentos de documentos");
  assert(/KB|kB/.test(docsWithMore), `El tamaño del documento está en unidades internacionales:${docsWithMore.includes("2 KB") ? "2 KB" : "ver pagina"}`);
  assert(docsWithMore.includes("indexed"), "El estado del documento se muestra tal cual");
  assert(docsWithMore.includes("cargar más"), "Cuando haya una página siguiente, proporcione una entrada de carga.");
  const docsEnd = docsView([doc("d1", "PROTO-Documento 1", 12, 2048, "indexed")], false);
  assert(docsEnd.includes("no más") && !docsEnd.includes("cargar más"), "Al llegar al final muestra \"No más\"");
  const docsEmpty = docsView([], false);
  assert(docsEmpty.includes("Aún no hay documentación para esta base de conocimientos."), "El estado vacío se otorga cuando no hay ningún documento en la base de conocimientos.");
  assert(docsView([], false, { supported: false }).includes("La versión actual no permite ver la base de conocimientos en la página web."), "También se darán avisos si faltan bits de capacidad en las páginas del documento.");
  assert(mod.formatBytes(300) === "300 byte" || mod.formatBytes(300).includes("300"), `Formato de bytes:${mod.formatBytes(300)}`);
  assert(/KB|kB/.test(mod.formatBytes(2048)), `KB Formato:${mod.formatBytes(2048)}`);
  assert(/MB/.test(mod.formatBytes(5 * 1024 * 1024)), `MB Formato:${mod.formatBytes(5 * 1024 * 1024)}`);
  assert(CONNECTION.includes('type: "knowledge.documents"') && CONNECTION.includes("cursor: options.cursor"), "Banda de paginación de documentos cursor");
  assert(CONNECTION.includes("KNOWLEDGE_PAGE_SIZE = 20"), "20 artículos por página (Host valor predeterminado)");
  assert(/host-memory-more/.test(readFileSync(join(ROOT, "src", "host", "KnowledgePage.tsx"), "utf8")), "Dar botón de carga cuando haya más");

  // ── 3. tareas programadas ─────────────────────────────────────────────
  const now = Date.now();
  const task = {
    id: "t1",
    name: "PROTO-sesión informativa diaria",
    cron: "0 8 * * *",
    enabled: true,
    nextRunAt: now + 3 * 60 * 60 * 1000 + 5 * 60 * 1000,
    lastRunAt: now - 2 * 60 * 60 * 1000,
    lastResult: "PROTO-Último resultado",
  };
  const tasksHtml = tasksPage({ tasks: { items: [task], loading: false, error: undefined, unsupported: false } });
  assert(tasksHtml.includes("PROTO-sesión informativa diaria"), "Nombre de la tarea");
  assert(tasksHtml.includes("0 8 * * *"), "cron Mostrar como está");
  assert(tasksHtml.includes("Habilitado"), "Estado habilitado");
  assert(tasksHtml.includes("en 3 h"), "La próxima ejecución utilizará el tiempo relativo futuro.");
  assert(mod.formatRelativeTime(now + 3 * 60 * 60 * 1000 + 5 * 60 * 1000, now, "es") === "en 3 h", "El redondeo de horas futuras no cruza categorías (frontera estable)");
  assert(tasksHtml.includes("hace 2 h"), "El tiempo relativo de la última ejecución.");
  assert(tasksHtml.includes("PROTO-Último resultado"), "Último resultado");
  assert(tasksHtml.includes("Gestiona tareas programadas en tu teléfono celular"), "Escritura explícita y solo lectura: administrar en dispositivos celulares");

  const disabledTask = tasksPage({ tasks: { items: [{ ...task, enabled: false, nextRunAt: undefined, lastRunAt: undefined, lastResult: undefined }], loading: false, error: undefined, unsupported: false } });
  assert(disabledTask.includes("suspendido"), "Las tareas desactivadas se muestran \"En pausa\"");
  assert((disabledTask.match(/Aún no ejecutado/g) ?? []).length >= 2, "no la próxima vez/La última ejecución dio \"Aún no ejecutado\".");

  const emptyTasks = tasksPage();
  assert(emptyTasks.includes("Aún no hay tareas programadas"), "Proporcionar orientación cuando no hay tareas.");
  const unsupportedTasks = tasksPage({ supported: false });
  assert(unsupportedTasks.includes("La versión actual no admite la visualización de tareas programadas en la página web."), "Dar indicaciones cuando falten espacios de habilidad");
  const failedTasks = tasksPage({ tasks: { items: [], loading: false, error: "PROTO-error", unsupported: false } });
  assert(failedTasks.includes("PROTO-error") && failedTasks.includes(">Inténtalo de nuevo<"), "La lectura de la tarea falla y se puede volver a intentar");

  // ── 4. Tiempo relativo en el futuro (zh / en)──────────────────────────────
  assert(mod.formatRelativeTime(now - 30 * 1000, now, "es") === "justo ahora", "Los últimos 30 segundos todavía fueron solo");
  assert(mod.formatRelativeTime(now + 30 * 1000, now, "es") === "en un momento", "El próximo medio minuto es pronto");
  assert(mod.formatRelativeTime(now + 12 * 60 * 1000, now, "es") === "en 12 min", "próximos minutos");
  assert(mod.formatRelativeTime(now + 3 * 60 * 60 * 1000, now, "en") === "in 3 h", "horas en el futuro (en)");
  assert(mod.formatRelativeTime(now + 2 * 24 * 60 * 60 * 1000, now, "es") === "en 2 días", "Número de días en el futuro");

  // ── 5. Entradas y cableado ─────────────────────────────────────────
  for (const key of ["knowledge.title", "knowledge.readOnly", "knowledge.documents", "knowledge.unsupported", "tasks.title", "tasks.readOnly", "tasks.nextRun", "tasks.unsupported", "common.loadMore", "common.noMore"]) {
    assert(typeof mod.ES[key] === "string" && typeof mod.EN[key] === "string" && mod.ES[key].length > 0 && mod.EN[key].length > 0, `entrada ${key} Tanto chino como inglés.`);
  }
  assert(HOST_SETTINGS.includes('openPage.entry.id === "rag"') && HOST_SETTINGS.includes("<KnowledgePage"), "Entrada \"Base de conocimientos y búsqueda\" en la página de la base de conocimientos");
  assert(HOST_SETTINGS.includes('openPage.entry.id === "scheduled_tasks"') && HOST_SETTINGS.includes("<TasksPage"), "La entrada \"Tarea programada\" ingresa a la página de tareas");
  assert(mod.supportsFlag(undefined, mod.KNOWLEDGE_FLAGS) === true && mod.supportsFlag([], mod.KNOWLEDGE_FLAGS) === false, "Juicio de capacidad de la base de conocimientos");
  assert(mod.supportsFlag(["tasks"], mod.TASK_FLAGS) === true && mod.supportsFlag(["knowledge"], mod.TASK_FLAGS) === false, "Juicio de capacidad de tarea cronometrada");
  assert(CONNECTION.includes('type: "knowledge.list"') && CONNECTION.includes('type: "tasks.list"'), "Se pueden emitir ambos comandos de lectura.");
  assert(/host-task-cron|host-task-meta/.test(CSS), "El estilo de la tarjeta de tareas existe");

  if (failures > 0) throw new Error(`La base de conocimientos y las aserciones de tareas programadas fallaron (${failures} artículo)`);
  console.log("\nSe aprobaron todas las afirmaciones de la base de conocimientos y de las tareas programadas (representación de dos páginas/paginación/formato de tarea/estado nulo/bit de capacidad).");
} finally {
  await rm(TEMP, { force: true });
}
