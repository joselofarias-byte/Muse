/**
 * Representación de afirmaciones para los detalles de la página de chat: separación de tiempo, bloques plegables, copia, aislamiento de errores elemento por elemento,
 * Y las imágenes/voz/estado del mensaje/reintento/negociación de capacidad después de que el Host agregue el nuevo protocolo.
 *
 * Lo que se afirma son todos los "campos de protocolo". → La capa "Interfaz" contiene la ruta de degradación del host anterior (falta el campo):
 * Cuando falta enableFlags, se sigue el comportamiento anterior y la interfaz correspondiente se cierra solo cuando falta explícitamente un determinado bit.
 *
 * Uso:npm --workspace @muse/client run test
 * El mecanismo es el mismo que check-settings-render.mjs:vite Convierta el esbuild incorporado en un ESM temporal y luego impórtelo.
 * No se agregan nuevas dependencias y los archivos temporales se eliminan finalmente.
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".chat-render-check.mjs");

const ENTRY = `
export { buildStreamItems, toolStatusOf } from "./src/host/chatStream.ts";
export { formatDaySeparator, dayKey, formatToolDuration, formatVoiceDuration } from "./src/host/format.ts";
export { resolveMessageImages, resolveVoiceUrl } from "./src/host/messageMedia.ts";
export { readCapabilities } from "./src/host/capabilities.ts";
export { actionsForMessage, deleteConfirmKey, quoteExcerpt, selectionCopyText } from "./src/host/messageActions.ts";
export { MessageBubble } from "./src/host/MessageBubble.tsx";
export { MessageImages } from "./src/host/MessageImages.tsx";
export { ToolCallBlocks } from "./src/host/ToolCallBlocks.tsx";
export { VoiceBar } from "./src/host/VoiceBar.tsx";
export { ImageLightbox } from "./src/host/ImageLightbox.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "chat-render-entry.tsx", loader: "tsx" },
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
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** Se corrigió \"ahora\" para evitar que el tiempo de ejecución de la prueba dure minutos y provoque que las afirmaciones se desvíen. */
const NOW = new Date(2026, 9, 30, 14, 30, 0).getTime();

const message = (id, role, extra = {}) => ({
  id,
  role,
  content: "",
  createdAt: NOW,
  ...extra,
});

/** Anfitrión anterior: los indicadores de capacidad no se entregan. */
const LEGACY = mod.readCapabilities(undefined);
/** Nuevo anfitrión: capacidades completas. */
const FULL = mod.readCapabilities([
  "chat",
  "streaming",
  "media",
  "tool_call_details",
  "message_status",
  "settings_values",
  "message_write",
  "message_multi_select",
]);

const render = (element) => mod.renderToStaticMarkup(element);
const bubble = (overrides) =>
  mod.createElement(mod.MessageBubble, {
    streaming: false,
    showActions: false,
    onRegenerate: () => undefined,
    onContinue: () => undefined,
    capabilities: LEGACY,
    ...overrides,
  });

try {
  // ── 1. Separación horaria: insertar por día, sin interrupciones repetidas en el mismo minuto ─────────────────────────
  const sameDay = [
    message("u1", "user", { createdAt: NOW - 5 * MINUTE, content: "temprano" }),
    message("a1", "assistant", { createdAt: NOW - 4 * MINUTE, content: "hola" }),
    message("u2", "user", { createdAt: NOW - 3 * MINUTE, content: "¿Está ahí?" }),
  ];
  const items = mod.buildStreamItems(sameDay, NOW);
  const dividers = items.filter((item) => item.kind === "divider");
  assert(dividers.length === 1, "Los mensajes consecutivos dentro del mismo día están separados por un solo tiempo (el de arriba)");
  assert(dividers[0]?.label.startsWith("hoy "), `El separador de hoy es \"Hoy HH:mm\":${String(dividers[0]?.label)}`);
  assert(items.filter((item) => item.kind === "message").length === 3, "Los tres mensajes entran en la lista.");

  const crossed = [
    message("d1", "user", { createdAt: NOW - 2 * DAY }),
    message("d2", "user", { createdAt: NOW - DAY }),
    message("d3", "user", { createdAt: NOW }),
  ];
  const crossedItems = mod.buildStreamItems(crossed, NOW);
  const crossedLabels = crossedItems.filter((item) => item.kind === "divider").map((item) => item.label);
  assert(crossedLabels.length === 3, `Inserta una franja divisoria a lo largo del cielo:${crossedLabels.join(" | ")}`);
  assert(crossedLabels[1]?.startsWith("ayer "), `El estilo relativo de ayer:${String(crossedLabels[1])}`);
  assert(/^\d{1,2} oct \d{2}:\d{2}$/.test(String(crossedLabels[0])), `Anterior con fecha en estilo existente:${String(crossedLabels[0])}`);

  // Los mensajes continuos dentro del mismo minuto (separados solo por decenas de segundos) no pueden ser interrumpidos repetidamente por separadores.
  const sameMinute = [40, 20, 5].map((secondsAgo, index) =>
    message(`s${index}`, index % 2 === 0 ? "user" : "assistant", { createdAt: NOW - secondsAgo * 1_000 }),
  );
  const sameMinuteItems = mod.buildStreamItems(sameMinute, NOW);
  assert(
    sameMinuteItems.filter((item) => item.kind === "divider").length === 1,
    "Los mensajes continuos dentro del mismo minuto no son interrumpidos repetidamente por separadores: solo permanece el superior",
  );

  // Datos degradados: Cuando las marcas de tiempo se devuelven al mismo día fuera de orden, no se insertarán consecutivamente dos secciones separadas con el mismo texto.
  const shuffled = [
    message("x1", "user", { createdAt: new Date(2026, 9, 20, 10, 0, 0).getTime() }),
    message("x2", "user", { createdAt: new Date(2026, 9, 19, 10, 0, 0).getTime() }),
    message("x3", "user", { createdAt: new Date(2026, 9, 20, 10, 0, 30).getTime() }),
  ];
  const shuffledLabels = mod
    .buildStreamItems(shuffled, NOW)
    .filter((item) => item.kind === "divider")
    .map((item) => item.label);
  assert(
    shuffledLabels.every((label, index) => index === 0 || label !== shuffledLabels[index - 1]),
    `Las marcas de tiempo desordenadas no se insertarán en dos tiempos consecutivos separados por el mismo texto:${shuffledLabels.join(" | ")}`,
  );

  const broken = [message("b1", "user", { createdAt: 0 }), message("b2", "assistant", { createdAt: -1 })];
  const brokenItems = mod.buildStreamItems(broken, NOW);
  assert(brokenItems.every((item) => item.kind === "message"), "Cuando la marca de tiempo no es válida, no se genera ninguna barra separadora y el mensaje se muestra como de costumbre.");

  const dup = [message("same", "user"), message("same", "assistant")];
  const dupKeys = mod.buildStreamItems(dup, NOW).filter((item) => item.kind === "message").map((item) => item.key);
  assert(new Set(dupKeys).size === 2, `Se deduplicarán las claves con ID repetidos:${dupKeys.join(", ")}`);

  // ── 2. Llamada de herramienta: bloque contraído predeterminado, línea de título con nombre y estado de la herramienta ───────────────
  const call = message("t1", "assistant", { content: '{"path":"a.txt"}', toolName: "read_file", toolCallId: "call-1" });
  const status = mod.toolStatusOf(call, { isStreaming: true, isLast: true, approvalToolCallIds: new Set(["call-1"]) });
  assert(status === "awaiting", "toolCallId Cuando el hit está pendiente de aprobación, el estado es \"pendiente de confirmación\"");
  const running = mod.toolStatusOf(call, { isStreaming: true, isLast: true, approvalToolCallIds: new Set() });
  assert(running === "running", "Cuando se está generando y es el último ítem, el estado es \"En ejecución\"");
  const done = mod.toolStatusOf(message("t2", "tool", { toolName: "read_file", toolCallId: "call-1", content: "resultado" }), {
    isStreaming: false,
    isLast: false,
    approvalToolCallIds: new Set(),
  });
  assert(done === "done", "role=tool El estado del mensaje de resultado es \"Completado\".");

  const toolHtml = render(bubble({ message: call, toolStatus: "running" }));
  assert(toolHtml.includes("<details"), "El bloque de llamada de herramienta es un elemento de colapso de detalles.");
  assert(!toolHtml.includes("<details open"), "Los bloques de llamadas de herramientas están contraídos de forma predeterminada");
  assert(toolHtml.includes("read_file") && toolHtml.includes("En curso"), "La línea de título proporciona tanto el nombre como el estado de la herramienta.");

  // ── 3. Proceso de pensamiento: plegado del mismo estilo, compartiendo componentes con el bloque de herramientas ──────────────────────
  const thinkingHtml = render(bubble({ message: message("r1", "assistant", { content: "responder", reasoning: "piensa primero" }) }));
  assert(thinkingHtml.includes("Razonamiento") && thinkingHtml.includes("piensa primero"), "Proceso de pensamiento representado en bloques plegados.");
  assert(
    (thinkingHtml.match(/class="host-details"/g) ?? []).length === 1,
    "Los bloques de pensamiento y los bloques de herramientas comparten el mismo componente de plegado (host-details)",
  );

  // ── 4. Menú de operación: solo se proporcionan menús no vacíos con texto no vacío. La cantidad de elementos cambia según el nivel de habilidad y el personaje. ────
  const menuLabels = (html) => [...html.matchAll(/<span>([^<]+)<\/span>/g)].map((match) => match[1]);
  const legacyMenu = render(
    bubble({ message: message("c1", "user", { content: "copiame" }), onCopy: () => undefined, actionsOpen: true }),
  );
  assert(legacyMenu.includes('aria-label="Copiar este mensaje"'), "El antiguo Host (sin message_write) solo copia");
  assert(legacyMenu.includes("is-open"), "El estado expandido se escribe en la burbuja y la capa de estilo muestra la barra de operaciones en consecuencia.");
  assert((legacyMenu.match(/host-bubble-tools/g) ?? []).length === 1, "Una columna solo puede tener una barra de acción");
  assert(
    menuLabels(legacyMenu).filter((label) => ["Copiar", "Cotización", "Colección", "Eliminar", "opción múltiple"].includes(label)).join(",") === "Copiar",
    `Los elementos del menú solo se copian:${menuLabels(legacyMenu).join(",")}`,
  );
  const withoutCopy = render(bubble({ message: message("c2", "assistant", { content: "   " }), onCopy: () => undefined }));
  assert(!withoutCopy.includes("host-bubble-tools"), "La barra de acciones no se representa cuando el menú no está expandido.");
  const emptyMenu = render(bubble({ message: message("c3", "user", { content: "   " }), capabilities: FULL, actionsOpen: true }));
  assert(!emptyMenu.includes('aria-label="Copia este mensaje"'), "El texto vacío no proporciona una copia (las operaciones de escritura restantes no se ven afectadas)");

  // ── 4b. Matriz de menú: bits de habilidad × papel × Estado de la colección ────────────────────────
  const idsOf = (items) => items.map((item) => item.id).join(",");
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: LEGACY })) === "copy",
    "Anfitrión antiguo: el menú solo se copia",
  );
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: FULL })) ===
      "copy,quote,favorite,select,delete",
    "Nuevo anfitrión: copiar/referencia/favorito/selección múltiple/eliminar",
  );
  assert(
    idsOf(mod.actionsForMessage({ role: "assistant", hasContent: true, favorited: true, capabilities: FULL })) ===
      "copy,quote,unfavorite,select,delete",
    "Cuando ya está recopilado, el elemento del menú cambia a \"Cancelar favorito\".",
  );
  const writeOnly = { ...FULL, multiSelect: false };
  assert(
    !idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: writeOnly })).includes("select"),
    "La entrada de selección múltiple message_multi_select que falta no aparece",
  );
  const selectOnly = { ...FULL, messageWrite: false };
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: selectOnly })) === "copy,select",
    "Cuando falta message_write, solo queda copiar (la entrada de selección múltiple todavía está allí y las acciones por lotes están controladas por la propia barra de operaciones)",
  );
  for (const role of ["tool", "system"]) {
    assert(
      idsOf(mod.actionsForMessage({ role, hasContent: true, favorited: false, capabilities: FULL })) === "copy",
      `${role} Los mensajes solo se copian`,
    );
  }
  const favoriteMenu = render(
    bubble({
      message: message("c4", "user", { content: "Favorito" }),
      capabilities: FULL,
      favorited: true,
      actionsOpen: true,
    }),
  );
  assert(favoriteMenu.includes(">Quitar de favoritos<") && !favoriteMenu.includes(">Colección<"), "Cuando ya esté recopilado, muestre \"Quitar de favoritos\" en lugar de \"Favorito\".");
  assert(favoriteMenu.includes("Citar") && favoriteMenu.includes("Seleccionar") && favoriteMenu.includes("Eliminar"), "Los cinco elementos del menú aparecen en la barra de operaciones expandida.");
  assert(favoriteMenu.includes("is-danger"), "Eliminar elementos marcados con estilo destructivo");
  const toolMenu = render(
    bubble({
      message: message("c5", "tool", { content: "Resultados de la herramienta", toolName: "read_file" }),
      capabilities: FULL,
      actionsOpen: true,
    }),
  );
  assert(toolMenu.includes("host-tool is-open") && menuLabels(toolMenu).includes("Copiar"), "tool Una vez expandido el mensaje, solo se puede copiar.");
  assert(!toolMenu.includes("Cotización"), "tool El mensaje no está citado.");

  // ── 5. Normalización de direcciones de medios: nueva ruta relativa del host + datos antiguos en cuatro formas ────────────
  const images = mod.resolveMessageImages([
    "https://example.com/a.png",
    "data:image/png;base64,AAA",
    "A".repeat(80),
    "file:///data/user/0/io.zer0.muse/files/images/x.bin",
    "/api/media/tok-1",
  ]);
  assert(images[0]?.kind === "src" && images[0].src === "https://example.com/a.png", "http Utilice la dirección directamente");
  assert(images[1]?.kind === "src" && images[1].src.startsWith("data:"), "data URI Usar directamente");
  assert(images[2]?.kind === "src" && images[2].src.startsWith("data:image/png;base64,"), "Prefijo de datos acolchado base64 desnudo");
  assert(images[3]?.kind === "unavailable", "file:// Se determina que la ruta local no está disponible");
  assert(images[4]?.kind === "src" && images[4].src === "/api/media/tok-1", "Nuevos anfitriones /api/media/<token> Directamente de la misma fuente src");
  assert(mod.resolveVoiceUrl("/api/media/voice-1") === "/api/media/voice-1", "La dirección de voz acepta la ruta de origen /api/media");
  assert(mod.resolveVoiceUrl("file:///data/voice.m4a") === undefined, "Se considera que la referencia file:// de la voz no está disponible.");

  // ── 6. Mensaje con imagen: imagen única/Varias imágenes, hacer clic en pantalla completa, cargar el marcador de posición fallido,media Posición de habilidad ──
  const imageHtml = render(
    bubble({ message: message("i1", "assistant", { content: "mira la foto", imageUrls: ["/api/media/tok-1"] }), capabilities: FULL }),
  );
  assert(imageHtml.includes('src="/api/media/tok-1"'), "imageUrls Representar directamente rutas relativas de origen");
  assert(imageHtml.includes('aria-label="Ver en grande"') && imageHtml.includes('class="quiet host-bubble-image"'), "Se puede hacer clic en la imagen para verla en pantalla completa.");
  assert(!imageHtml.includes("is-grid"), "Una sola imagen no cubre la cuadrícula de varias imágenes");
  const multiImageHtml = render(
    bubble({
      message: message("i2", "assistant", { imageUrls: ["/api/media/a", "/api/media/b"] }),
      capabilities: FULL,
    }),
  );
  assert(multiImageHtml.includes("host-bubble-images is-grid"), "Mover dos columnas de cuadrículas en varias imágenes");

  const fileImageHtml = render(
    bubble({
      message: message("i3", "assistant", { imageUrls: ["file:///data/x.bin"] }),
      capabilities: FULL,
    }),
  );
  assert(fileImageHtml.includes("host-bubble-image-missing") && !fileImageHtml.includes("<img"), "Las imágenes que no se pueden recuperar se utilizan como marcadores de posición en lugar de imágenes rotas.");

  const noMediaHtml = render(
    bubble({
      message: message("i4", "assistant", { imageUrls: ["/api/media/tok-1"], voiceUrl: "/api/media/v", voiceDurationMs: 5_000 }),
      capabilities: mod.readCapabilities(["chat", "streaming"]),
    }),
  );
  assert(!noMediaHtml.includes("host-bubble-image"), "La imagen no se representa cuando falta el bit de capacidad multimedia");
  assert(!noMediaHtml.includes("host-voice"), "La voz no se procesa cuando falta el bit de capacidad multimedia");

  const lightboxHtml = render(mod.createElement(mod.ImageLightbox, { src: "/api/media/tok-1", onClose: () => undefined }));
  assert(lightboxHtml.includes('role="dialog"') && lightboxHtml.includes('aria-modal="true"'), "La vista de pantalla completa es una capa flotante con semántica de diálogo");
  assert(lightboxHtml.includes('src="/api/media/tok-1"') && lightboxHtml.includes('aria-label="Cerrar vista previa"'), "La capa flotante da imágenes y cierra la entrada.");

  // ── 7. Mensajes de voz: reproducibles versus solo duración ─────────────────────────────
  const voiceHtml = render(
    bubble({
      message: message("v1", "assistant", { voiceUrl: "/api/media/voice-1", voiceDurationMs: 12_000 }),
      capabilities: FULL,
    }),
  );
  assert(voiceHtml.includes("<audio") && voiceHtml.includes('src="/api/media/voice-1"'), "Renderizar cuando hay voiceUrl audio");
  assert(voiceHtml.includes('aria-label="Reproducir voz"'), "La barra de voz proporciona acceso a la reproducción.");
  assert(voiceHtml.includes("12″"), "La barra de voz muestra la duración convertida por duraciónMs");
  assert(mod.formatVoiceDuration(12_000) === "12″" && mod.formatVoiceDuration(90_000) === "1′30″", "Formato de duración: segundos/minutos segundos");

  const staticVoiceHtml = render(
    bubble({ message: message("v2", "assistant", { voiceDurationMs: 7_000 }), capabilities: FULL }),
  );
  assert(!staticVoiceHtml.includes("<audio"), "No se cuelga cuando no hay voiceUrl audio");
  assert(staticVoiceHtml.includes("host-voice is-static") && staticVoiceHtml.includes("Voz · 7″"), "Degradado a barra estática solo por duración");

  // ── 8. Estado del mensaje: envío/fallido + reintento/bit de capacidad desactivado ────────────────
  const sendingHtml = render(
    bubble({ message: message("st1", "user", { content: "¿Está ahí?", status: "sending" }), capabilities: FULL }),
  );
  assert(sendingHtml.includes("Enviando…") && sendingHtml.includes("host-msg-status"), "status=sending Mostrar envío de luz");

  const failedHtml = render(
    bubble({
      message: message("st2", "user", { content: "¿Está ahí?", status: "failed", errorText: "No hay red en el teléfono celular." }),
      capabilities: FULL,
      onRetry: () => undefined,
    }),
  );
  assert(failedHtml.includes("No hay red en el teléfono celular."), "failed mostrado en una burbuja errorText");
  assert(failedHtml.includes("host-retry") && failedHtml.includes(">Reintentar<"), "failed Dale al botón \"Reintentar\" cuando");
  assert(failedHtml.includes('role="alert"'), "Las líneas fallidas tienen semántica de alerta");

  const failedNoText = render(
    bubble({ message: message("st3", "user", { status: "failed" }), capabilities: FULL, onRetry: () => undefined }),
  );
  assert(failedNoText.includes("No se pudo enviar"), "errorText Si falta, recurra a \"No se pudo enviar\"");

  const noStatusCapability = render(
    bubble({
      message: message("st4", "user", { status: "failed", errorText: "No hay red en el teléfono celular." }),
      capabilities: mod.readCapabilities(["media"]),
      onRetry: () => undefined,
    }),
  );
  assert(
    !noStatusCapability.includes("host-msg-failed") && !noStatusCapability.includes("host-retry"),
    "El estado y el reintento no se muestran cuando falta el bit de capacidad message_status.",
  );
  const sentHtml = render(bubble({ message: message("st5", "user", { content: "Enviado", status: "sent" }), capabilities: FULL }));
  assert(!sentHtml.includes("host-msg-status"), "status=sent No hay espacio extra ocupado");

  // ── 9. Detalles de llamada de herramienta:toolCalls[] Prioridad, los campos faltantes vuelven a la antigua inferencia ───────────
  const detailedCalls = [
    { callId: "c1", name: "read_file", argumentsPreview: '{"path":"a.txt"}', resultPreview: "hello", success: true, durationMs: 1_200 },
    { callId: "c2", name: "shell_exec", success: false, durationMs: 300 },
  ];
  const callsHtml = render(
    bubble({ message: message("tc1", "assistant", { content: "Compruébalo", toolCalls: detailedCalls }), capabilities: FULL }),
  );
  assert((callsHtml.match(/class="host-details"/g) ?? []).length === 2, "Cada llamada a la herramienta representa un trozo de pliegue.");
  assert(callsHtml.includes("read_file") && callsHtml.includes("shell_exec"), "Cada título de bloque se llama con su propio nombre.");
  assert(callsHtml.includes("Correcto · 1.2s") && callsHtml.includes("Falló · 0.3s"), "La línea del título indica el éxito o el fracaso y el tiempo necesario.");
  assert(callsHtml.includes("parámetros") && callsHtml.includes("hello"), "El área ampliada muestra vistas previas de parámetros y resultados.");
  assert(!callsHtml.includes("<details open"), "Los detalles de la herramienta están contraídos de forma predeterminada.");

  const callsWithoutCapability = render(
    bubble({
      message: message("tc2", "assistant", { content: "Compruébalo", toolCalls: detailedCalls }),
      capabilities: mod.readCapabilities(["chat"]),
    }),
  );
  assert(!callsWithoutCapability.includes("read_file"), "Los bloques de detalles no se representan cuando falta tool_call_details");
  const legacyToolHtml = render(
    bubble({
      message: message("tc3", "assistant", { content: "Compruébalo", toolName: "read_file", toolCallId: "c9" }),
      capabilities: mod.readCapabilities(["chat"]),
      toolStatus: "requested",
    }),
  );
  assert(legacyToolHtml.includes("Llamada a la herramienta · read_file"), "toolCalls Recurrir al bloque de inferencia de nombre de herramienta cuando está vacío");

  // ── 10. Negociación de capacidades: el antiguo anfitrión no degenerará en una ocultación total ─────────────────────────
  assert(LEGACY.known === false && LEGACY.media === true, "Cuando no se emite enableFlags, se sigue el comportamiento anterior (no se oculta ningún medio)");
  assert(FULL.known === true && FULL.media && FULL.toolCallDetails && FULL.messageStatus, "Se pueden identificar las tres habilidades.");
  assert(mod.readCapabilities(["media"]).messageStatus === false, "Cerrado en ausencia");
  const legacyImageHtml = render(
    bubble({ message: message("lg1", "assistant", { imageUrls: ["/api/media/tok-1"] }), capabilities: LEGACY }),
  );
  assert(legacyImageHtml.includes('src="/api/media/tok-1"'), "La dirección de medios que se puede obtener todavía se representa en el host anterior.");

  // ── 10b. Cita: bloque de cotización dentro de la burbuja + franja borrador en el área de entrada ──────────────────
  const quotedHtml = render(
    bubble(
      {
        message: message("q1", "user", { content: "esta es mi respuesta", quotedContent: "PROTO-Texto original citado" }),
        capabilities: FULL,
      },
    ),
  );
  assert(quotedHtml.includes("host-quote") && quotedHtml.includes("PROTO-Texto original citado"), "quotedContent Representado como un bloque de cotizaciones dentro de una burbuja.");
  const noQuoteHtml = render(bubble({ message: message("q2", "user", { content: "sin citación" }), capabilities: FULL }));
  assert(!noQuoteHtml.includes("host-quote"), "Host No mostrar el bloque de cotización cuando el contenido cotizado no se entrega (bajar de categoría)");
  assert(mod.quoteExcerpt("  primera linea\n\nsegunda linea  ") === "primera linea segunda linea", "Los resúmenes de citas aplanan los saltos de línea y los espacios en blanco adicionales");
  assert(mod.quoteExcerpt("x".repeat(120), 20) === `${"x".repeat(20)}…`, "Los resúmenes de citas que son demasiado largos se truncan y se añaden puntos suspensivos.");

  // ── 10c. Multiselección y lotes: lógica pura + cableado ──────────────────────────────
  assert(mod.selectionCopyText([{ content: "a" }, { content: "  " }, { content: "b" }]) === "a\n\nb", "Selección múltiple para copiar solo mensajes con cuerpo de texto");
  assert(
    mod.deleteConfirmKey(1) === "message.deleteConfirmOne",
    `Para eliminar una sola entrada, utilice una sola entrada:${mod.deleteConfirmKey(1)}`,
  );
  assert(
    mod.deleteConfirmKey(3) === "message.deleteConfirmMany",
    `Utilice entradas de lote para eliminar lotes:${mod.deleteConfirmKey(3)}`,
  );

  // ── 11. Cableado y estilo: el host conecta el bit de capacidad, reintenta y pantalla completa ────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of [
    "host-day-sep",
    "host-details-status",
    "host-bubble-tools",
    "host-msg-row",
    "host-select",
    "host-selection-bar",
    "host-quote",
    "host-quote-draft",
    "host-bubble-image",
    "host-bubble-image-missing",
    "host-voice",
    "host-tool-field",
    "host-msg-status",
    "host-msg-failed",
    "host-lightbox",
    "host-msg-error",
  ]) {
    assert(css.includes(`.${selector}`), `definido en el estilo .${selector}`);
  }
  const chatSource = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  assert(
    chatSource.includes("<MessageErrorBoundary") && chatSource.includes("buildStreamItems"),
    "La lista de mensajes se empaqueta en aislamiento de errores uno por uno y la barra separadora proviene de buildStreamItems",
  );
  assert(
    chatSource.includes('className="host-day-sep"') && chatSource.includes('role="separator"'),
    "Los separadores ingresan al flujo de mensajes con semántica de separador",
  );
  assert(chatSource.includes("copyTextToClipboard"), "Herramienta de copia de portapapeles de transporte (LAN http sin API de portapapeles)");
  assert(
    chatSource.includes("readCapabilities") && chatSource.includes("capabilities={capabilities}"),
    "Los bits de capacidad se leen de la instantánea y se pasan a cada mensaje.",
  );
  assert(
    chatSource.includes('type: "chat.retry", messageId'),
    "Reintentar enviando chat.reintentar con este mensaje messageId",
  );
  assert(chatSource.includes("<ImageLightbox"), "Ver pantalla completa en esta página de chat");
  const imagesSource = readFileSync(join(ROOT, "src", "host", "MessageImages.tsx"), "utf8");
  assert(imagesSource.includes("onError"), "Si la imagen no se carga, se cortará al marcador de posición (sin dejar una imagen rota)");

  // ── 11b. Selección múltiple/operación de escritura/cableado de referencia en el chat shell ────────────────────
  assert(
    chatSource.includes("host-selection-bar") &&
      chatSource.includes('t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })'),
    "Al realizar una selección múltiple, la barra de operación fija inferior + la barra superior muestra el número de elementos seleccionados (los números se agrupan según el idioma actual)",
  );
  assert(chatSource.includes('t("chat.selectAll")') && chatSource.includes('t("chat.exitSelection")'), "La barra de operaciones de la barra superior tiene seleccionar todo y salir",
  );
  assert(
    chatSource.includes('className={selectedIds.includes(item.message.id) ? "host-select is-on" : "host-select"}') &&
      chatSource.includes('role="checkbox"'),
    "Mostrar una casilla de verificación en el lado izquierdo de cada mensaje cuando se realizan selecciones múltiples",
  );
  assert(
    chatSource.includes("deleteConfirmKey(ids.length)") && chatSource.includes("window.confirm"),
    "Reproduzca una segunda confirmación antes de eliminar (la copia incluye \"Eliminar solo registros locales, no puede restaurar\")",
  );
  assert(
    chatSource.includes('{ type: "message.delete", messageIds: [...ids] }') &&
      chatSource.includes("setRemovedIds((current) => current.filter((id) => !ids.includes(id)))"),
    "Para eliminar, vaya a message.delete,Host Volver a poner el mensaje oculto al rechazar",
  );
  assert(
    chatSource.includes("event.skippedMessageIds") && chatSource.includes('t("chat.deleteSkipped", { count: skipped.length })'),
    "Los mensajes omitidos en el recibo se restaurarán (no sólo dependiendo del error)",
  );
  assert(
    chatSource.includes('sendOptimistic({ type: "message.favorite", messageId, favored: next }') &&
      chatSource.includes("delete restored[messageId]"),
    "La colección primero se invierte localmente.Host Deshacer el valor optimista ante el rechazo",
  );
  assert(
    chatSource.includes("quoteExcerpt(message.content)") && chatSource.includes("host-quote-draft") &&
      chatSource.includes("setQuoteDraft(undefined)"),
    "La cotización de la tira borrador se puede cancelar.",
  );
  assert(
    chatSource.includes("quotedMessageId: quoted.messageId"),
    "Al enviar, la identificación del mensaje citado (nombre del campo quotedMessageId) se incluye en chat.send.",
  );
  assert(
    !/\{ messageId: quoted\.messageId \}/.test(chatSource),
    "chat.send El antiguo nombre del campo messageId ya no se utiliza",
  );
  const apiSource = readFileSync(join(ROOT, "src", "api.ts"), "utf8");
  assert(
    /readonly quotedMessageId\?: string;/.test(apiSource),
    "HostCommand declarado quotedMessageId",
  );
  assert(
    chatSource.includes('capabilities.messageWrite && (') && chatSource.includes("host-selection-danger"),
    "Eliminar en la barra de operaciones por lotes/Las colecciones también se verifican según la capacidad message_write.",
  );
  assert(
    chatSource.includes("favorited={isFavorited(item.message.id, item.message.favorite)}"),
    "El estado de la recopilación está determinado por el campo Host + cobertura optimista local.",
  );
  const bubbleSource = readFileSync(join(ROOT, "src", "host", "MessageBubble.tsx"), "utf8");
  assert(
    bubbleSource.includes("actionsForMessage(") && bubbleSource.includes("onAction?.(message.id, action.id)"),
    "Las burbujas no juzgan los elementos del menú en sí, sino que los entregan a accionesForMessage e informan la selección.",
  );

  if (failures > 0) throw new Error(`Falló la aserción de representación del chat (${failures} artículo)`);
  console.log("\nTodas las afirmaciones de representación del chat pasan (separación de tiempo/bloqueo de colapso/matriz de menú/cita/selección múltiple/imagen/discurso/estado/negociación de capacidad).");
} finally {
  await rm(TEMP, { force: true });
}
