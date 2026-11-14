/**
 * Establezca aserciones de representación para subpáginas: paginación, mapeo de control, etiquetas de enumeración, filas de solo lectura, conexiones de escritura, reversión de fallas y avisos de bits de capacidad faltantes.
 *
 * Cubre cinco subpáginas reales (Apariencia/Chat/Voz/Memoria/General). La afirmación utiliza sólo campos en el contrato de Host:
 * `values[].{key,type,value,options[].label}` + `capabilities.settings_values`,
 * El texto de la interfaz (elementos de enumeración, valores de solo lectura) proviene de dispositivos PROTO-* cuerda——Siempre que la parte delantera esté construida en secreto por sí misma.
 * Para las tablas chinas, estas afirmaciones fallarán porque no se pueden encontrar los campos.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-pages-check.mjs");

const ENTRY = `
export { SettingsValuePage } from "./src/host/SettingsValuePage.tsx";
export {
  SETTINGS_PAGE_SPECS,
  isReadOnlyKey,
  normalizeSettingKey,
  numberStepFor,
  pageForEntry,
  pageForValueKey,
  stepNumber,
  valuesForPage,
} from "./src/host/settingsPages.ts";
export { applySettingValue, settingValueOf } from "./src/host/settingsState.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-pages-entry.tsx", loader: "tsx" },
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

const CAPS_ON = { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true };
const CAPS_OFF = { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: false };

/** una linea = una lista de fila;interactive Las filas son <button>, los no interactivos son <div>. */
const countRows = (html) => (html.match(/class="muse-listrow(?:"| )/g) ?? []).length;
const countSwitches = (html) => (html.match(/role="switch"/g) ?? []).length;

const option = (value, label) => ({ value, label });

/** Cubriendo cinco páginas de datos falsos; los nombres clave siguen la convención de nomenclatura de la aplicación.label / value usar PROTO-* Las garantías sólo pueden provenir de acuerdos. */
const VALUES = [
  {
    key: "appearance.themeMode",
    type: "enum",
    value: "dark",
    options: [option("light", "PROTO-color claro"), option("dark", "PROTO-oscuro"), option("system", "PROTO-Sigue el sistema")],
  },
  { key: "appearance.fontSizeScale", type: "number", value: 1 },
  {
    key: "chatPreferences.enterToSend",
    type: "enum",
    value: "enter",
    options: [option("enter", "PROTO-Entra para enviar"), option("button", "PROTO-botón enviar")],
  },
  { key: "chat.showToolCalls", type: "boolean", value: true },
  { key: "chat.showTimestamps", type: "boolean", value: false },
  { key: "chatPreferences.streamResponse", type: "boolean", value: true },
  {
    key: "chat.thinkingLevel",
    type: "enum",
    value: "medium",
    options: [option("low", "PROTO-bajo"), option("medium", "PROTO-en"), option("high", "PROTO-alto")],
  },
  {
    key: "tts.engine",
    type: "enum",
    value: "system",
    options: [option("system", "PROTO-sistemaTTS"), option("openai", "PROTO-OpenAI")],
  },
  { key: "tts.speechRate", type: "number", value: 1.1 },
  { key: "tts.voiceName", type: "text", value: "PROTO-Nombre del tono" },
  { key: "memoryEnabled", type: "boolean", value: true },
  { key: "memoryAutoSave", type: "boolean", value: false },
  {
    key: "language",
    type: "enum",
    value: "zh",
    options: [option("zh", "PROTO-chino"), option("en", "PROTO-English")],
  },
  { key: "modelConfigured", type: "text", value: "PROTO-configurado" },
];

const SNAPSHOT = {
  groups: [
    {
      key: "settings_group_chat",
      groupTitle: "PROTO-grupo",
      entries: [
        {
          id: "chat",
          titleKey: "settings_screen_chat",
          title: "PROTO-charlar",
          groupKey: "settings_group_chat",
          iconKey: "chat",
          kind: "link",
        },
      ],
    },
  ],
  toggles: [],
  display: { language: "es", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: [],
  values: VALUES,
};

const pageOf = (id) => mod.SETTINGS_PAGE_SPECS.find((spec) => spec.id === id);

function renderPage(pageId, overrides = {}) {
  const page = pageOf(pageId);
  return render(
    mod.createElement(mod.SettingsValuePage, {
      page,
      title: "PROTO-Título",
      snapshot: SNAPSHOT,
      loading: false,
      error: undefined,
      capabilities: CAPS_ON,
      onBack: () => undefined,
      onRetry: () => undefined,
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
      ...overrides,
    }),
  );
}

try {
  // ── 1. Paginación: entrada de directorio → página, clave de valor → Página ────────────────────────
  assert(mod.pageForEntry("appearance")?.id === "appearance", "entrada de apariencia → página de apariencia");
  assert(mod.pageForEntry("chat")?.id === "chat", "entrada de chat → pagina de chat");
  assert(mod.pageForEntry("tts")?.id === "voice", "TTS entrada → pagina de voz");
  assert(mod.pageForEntry("memory")?.id === "memory", "entrada de memoria → pagina de memoria");
  assert(mod.pageForEntry("general")?.id === "general", "Artículos generales → pagina general");
  assert(mod.pageForEntry("provider") === undefined, "Las categorías que no se han completado todavía están en las páginas de marcador de posición");
  assert(mod.normalizeSettingKey("chatPreferences.enterToSend") === "chatpreferencesentertosend", "La normalización clave eliminará el punto y lo pondrá en minúsculas");

  for (const [key, pageId] of [
    ["appearance.themeMode", "appearance"],
    ["appearance.fontSizeScale", "appearance"],
    ["chatPreferences.enterToSend", "chat"],
    ["chat.showToolCalls", "chat"],
    ["chat.showTimestamps", "chat"],
    ["chatPreferences.streamResponse", "chat"],
    ["chat.thinkingLevel", "chat"],
    ["tts.engine", "voice"],
    ["tts.speechRate", "voice"],
    ["tts.voiceName", "voice"],
    ["memoryEnabled", "memory"],
    ["memoryAutoSave", "memory"],
    ["language", "general"],
    ["modelConfigured", "general"],
  ]) {
    assert(mod.pageForValueKey(key)?.id === pageId, `clave de valor ${key} Volver a ${pageId} página`);
  }

  // ── 2. Número de filas por página y mapeo de control ──────────────────────────────────────
  const appearance = renderPage("appearance");
  assert(countRows(appearance) === 2, `Apariencia página 2 líneas (modo tema + tamaño de fuente)`);
  assert(appearance.includes("PROTO-oscuro"), "El lado derecho de la columna de enumeración muestra la etiqueta emitida por el Host (valor actual)");
  assert(!appearance.includes(">dark<"), "La enumeración no trata el valor original como nombre para mostrar");
  assert(/aria-label="Reducir [^"]*"/.test(appearance), "La recta numérica tiene − botón (nombre accesible)");
  assert(/aria-label="Aumentar [^"]*"/.test(appearance), "La fila de números tiene un botón + (nombre accesible)");
  assert(appearance.includes(">1<"), "La fila numérica muestra el valor actual");

  const chat = renderPage("chat");
  assert(countRows(chat) === 5, "Página de chat 5 líneas (enviar clave/llamada de herramienta/marca de tiempo/transmisión/intensidad de pensamiento)");
  assert(countSwitches(chat) === 3, "Página de chat 3 líneas booleanas representadas como interruptores");
  assert(chat.includes("PROTO-Entra para enviar") && chat.includes("PROTO-en"), "Cada una de las dos enumeraciones muestra su propia Host label");

  const voice = renderPage("voice");
  assert(countRows(voice) === 3, "Página de voz 3 líneas (motor/velocidad de voz/nombre del tono)");
  assert(voice.includes("PROTO-sistemaTTS"), "TTS Visualización de la línea del motor Host label");
  assert(voice.includes(">1.1<"), "La línea de velocidad de voz muestra el número actual");
  assert(voice.includes("PROTO-Nombre del tono"), "La línea del nombre del tono muestra el valor emitido por el anfitrión.");

  const memory = renderPage("memory");
  assert(countRows(memory) === 2, "Página de memoria 2 líneas (cambiar + guardado automático)");
  assert(countSwitches(memory) === 2, "Ambas filas de la página de memoria son interruptores.");

  const general = renderPage("general");
  assert(countRows(general) === 2, "Página general 2 líneas (idioma + modelo configurado)");
  assert(general.includes("PROTO-chino"), "Visualización de la línea de idioma Host label");
  assert(general.includes("PROTO-configurado"), "Si el modelo ha configurado filas para mostrar los valores emitidos por el Host");

  // ── 3. Línea de solo lectura: si el nombre/modelo del sonido se ha configurado y no se permite escribirlo. ─────────────────
  assert(mod.isReadOnlyKey("tts.voiceName") && mod.isReadOnlyKey("modelConfigured"), "Se reconocen claves de solo lectura");
  assert(!mod.isReadOnlyKey("tts.speechRate"), "Las claves grabables no se considerarán erróneamente como de solo lectura");
  const readOnlyRowHtml = render(
    mod.createElement(mod.SettingsValuePage, {
      page: pageOf("voice"),
      title: "PROTO-Título",
      snapshot: { ...SNAPSHOT, values: [{ key: "tts.voiceName", type: "text", value: "PROTO-Nombre del tono" }] },
      loading: false,
      error: undefined,
      capabilities: CAPS_ON,
      onBack: () => undefined,
      onRetry: () => undefined,
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
    }),
  );
  assert(!readOnlyRowHtml.includes("host-stepper") && countSwitches(readOnlyRowHtml) === 0, "Las filas de solo lectura no tienen controles de escritura");
  const readOnlyBooleanHtml = renderPage("general", {
    snapshot: { ...SNAPSHOT, values: [{ key: "modelConfigured", type: "boolean", value: true }] },
  });
  assert(countSwitches(readOnlyBooleanHtml) === 1 && readOnlyBooleanHtml.includes("disabled"), "Incluso si la clave de solo lectura se emite como booleana, solo desactivará el interruptor.");

  // ── 4. El número de participaciones es:label Volver al original si falta value ───────────────────────
  const fallbackHtml = renderPage("chat", {
    snapshot: {
      ...SNAPSHOT,
      values: [
        {
          key: "chat.thinkingLevel",
          type: "enum",
          value: "experimental",
          options: [option("low", "PROTO-bajo")],
        },
      ],
    },
  });
  assert(fallbackHtml.includes(">experimental<"), "Cuando el valor actual de la enumeración no está en las opciones, el valor original se muestra directamente. value");

  // ── 5. Cableado de escritura: cada control llama a OnWrite (nivel de fuente) ────────────────
  const pageSource = readFileSync(join(ROOT, "src", "host", "SettingsValuePage.tsx"), "utf8");
  assert(/onWrite\(value\.key, next\)/.test(pageSource), "cambiar escribir ir onWrite(key, boolean)");
  assert(/onWrite\(value\.key, stepNumber\(current, -1\)\)/.test(pageSource), "numeros − escribe y vete onWrite(key, number)");
  assert(/onWrite\(value\.key, stepNumber\(current, 1\)\)/.test(pageSource), "Número + escribir onWrite(key, number)");
  assert(/onWrite\(value\.key, option\.value\)/.test(pageSource), "Opciones de enumeración anular onWrite(key, optionValue)");
  const hostSettingsSource = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(/onWrite=\{onWrite\}/.test(hostSettingsSource), "La página de inicio pasa a escribir de forma transparente a las subpáginas.");
  assert(
    /pageForEntry\(openPage\.entry\.id\)/.test(hostSettingsSource) &&
      /<SettingsValuePage/.test(hostSettingsSource) &&
      /<HostPlaceholder/.test(hostSettingsSource),
    "Haga clic en la categoría que se ha creado para ingresar a la subpágina real y las otras categorías irán a la página de marcador de posición unificada.",
  );
  const hostChatSource = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  assert(/onWrite=\{updateSettingValue\}/.test(hostChatSource), "El shell de chat conecta la escritura de useHostConnection a la página de configuración");
  const connectionSource = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
  assert(/type: "settings\.update", key, value/.test(connectionSource), "Lo que se escribe y se emite es el comando settings.update");
  assert(/typeof value === "boolean" \? \(value \? "true" : "false"\) : value/.test(connectionSource), "El booleano se emite de acuerdo con los literales verdadero/falso existentes del Host.");
  assert(
    /applySettingValue\(currentSnapshot, rejected\.key, rejected\.previous\)/.test(connectionSource),
    "Host Al devolver un error, retroceda con el valor antes de escribir.",
  );
  assert(
    /optionsRef\.current\.onError\(describeHostError\(event\.error, tt\("error\.settingsSave"\), tt\)\)/.test(connectionSource),
    "Después de retroceder, proporcione el motivo dado por el anfitrión para brindar (no se trague el motivo del error)",
  );

  // ── 6. Actualización optimista y reversión de fallas (función pura) ─────────────────────────────
  const before = mod.settingValueOf(SNAPSHOT, "chat.showToolCalls");
  assert(before === true, "Lo que se lee antes de escribir es el valor original en valores");
  const optimistic = mod.applySettingValue(SNAPSHOT, "chat.showToolCalls", false);
  assert(mod.settingValueOf(optimistic, "chat.showToolCalls") === false, "La actualización optimista cambia inmediatamente el valor en valores.");
  assert(SNAPSHOT.values.find((v) => v.key === "chat.showToolCalls").value === true, "La actualización optimista no modifica el objeto original (función pura)");
  const rolledBack = mod.applySettingValue(optimistic, "chat.showToolCalls", before);
  assert(mod.settingValueOf(rolledBack, "chat.showToolCalls") === true, "Utilice el valor antes de escribir para aplicarlo nuevamente y completar la reversión.");
  const numbers = mod.applySettingValue(SNAPSHOT, "tts.speechRate", 1.2);
  assert(mod.settingValueOf(numbers, "tts.speechRate") === 1.2, "La escritura digital también solo cambia la clave de destino.");
  const togglesProjection = mod.applySettingValue(
    {
      ...SNAPSHOT,
      toggles: [{ key: "piiGuardEnabled", enabled: true }],
    },
    "piiGuardEnabled",
    false,
  );
  assert(togglesProjection.toggles[0].enabled === false, "La escritura booleana también se cambia para alternar la proyección (la antigua página de inicio también cambia en consecuencia)");
  const legacySnapshot = { ...SNAPSHOT, values: undefined };
  assert(mod.applySettingValue(legacySnapshot, "chat.showToolCalls", false).values === undefined, "Las instantáneas antiguas sin valores no generarán campos de valores de la nada");

  // Paso digital: presione 1 para números enteros, presione 0,1 para decimales, no exceda 0
  assert(mod.numberStepFor(1) === 1 && mod.numberStepFor(1.1) === 0.1, "Los pasos enteros y decimales son diferentes");
  assert(mod.stepNumber(1, 1) === 2 && mod.stepNumber(1.1, 1) === 1.2, "+ Avance por tamaño de paso sin salir de la cola de punto flotante");
  assert(mod.stepNumber(0, -1) === 0, "− no cruzar 0");

  // ── 7. Bit de capacidad/estado cargado/estado vacío ───────────────────────────────────
  const unsupported = renderPage("appearance", { capabilities: CAPS_OFF });
  assert(unsupported.includes("Esta versión no permite cambiar estos ajustes desde la web."), "Dar un mensaje claro cuando falte el bit de capacidad settings_values");
  assert(countRows(unsupported) === 1 && countSwitches(unsupported) === 0, "No se representan filas ajustables cuando falta el bit de capacidad.");

  const oldHost = renderPage("appearance", { snapshot: { ...SNAPSHOT, values: undefined } });
  assert(oldHost.includes("Esta versión no permite cambiar estos ajustes desde la web."), "Host Se dará el mismo mensaje cuando no se emitan valores (en lugar de una lista vacía)");

  const loading = renderPage("appearance", { snapshot: undefined, loading: true });
  // Retraso del esqueleto 300 ms (useDelayedFlag): Cuando ingresa por primera vez a la subpágina, solo ocupa espacio para Anhui.
  assert(loading.includes('role="status"') && !loading.includes("muse-skeleton"), "values Dale un lugar a Quiet antes de que llegue (retraso del esqueleto 300 ms)");

  const failed = renderPage("appearance", { snapshot: undefined, loading: false, error: "PROTO-Error de lectura" });
  assert(failed.includes("PROTO-Error de lectura") && failed.includes(">Reintentar<"), "Se muestra una barra de error reintentable si falla la lectura.");

  const emptyPage = renderPage("memory", { snapshot: { ...SNAPSHOT, values: [] } });
  assert(emptyPage.includes("No hay nada para ajustar en esta página."), "Si no hay ningún valor coincidente en esta página, indique el motivo y no deje espacios en blanco.");

  // ── 8. Estilo: el nuevo nombre de clase solo acepta tokens ────────────────────────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-setting-value", "host-stepper", "host-stepper-button", "host-stepper-value", "host-setting-check"]) {
    assert(css.includes(`.${selector}`), `definido en el estilo .${selector}`);
  }

  if (failures > 0) throw new Error(`Error al establecer la aserción de subpágina (${failures} artículo)`);
  console.log("\nEstablezca aserciones de subpágina para todas las pasadas (paginación/control/solo lectura/escritura/reversión/bits de capacidad).");
} finally {
  await rm(TEMP, { force: true });
}
