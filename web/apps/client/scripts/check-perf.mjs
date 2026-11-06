/**
 * Afirmación del rendimiento y animación de la lista de mensajes.
 *
 * lógica pura (memo Comparación/fusión de instantáneas/ventana/cambio de rendimiento) se ejecuta directamente;CSS afirmación lateral
 * "Usar sólo tokens de movimiento para efectos de animación" "Detener todo al reducir la dinámica"; cableado (ventana, marcador de posición,perf registro)
 * Afirmar según la estructura del código fuente.——Sin un navegador, todo lo que se puede afirmar está roto.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".perf-check.mjs");

const ENTRY = `
export { areBubblePropsEqual, areMessagesEqual, mergeMessages } from "./src/host/messageEqual.ts";
export { DEFAULT_ROW_HEIGHT, measureAverage, planWindow, shouldUpdateAverage, WINDOW_OVERSCAN, WINDOW_THRESHOLD } from "./src/host/messageWindow.ts";
export { formatPerfLine, perfFlag } from "./src/host/perf.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "perf-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    logLevel: "warning",
    outfile: TEMP,
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const BUBBLE = readFileSync(join(ROOT, "src", "host", "MessageBubble.tsx"), "utf8");

const message = (id, overrides = {}) => ({
  id,
  role: "assistant",
  content: `contenido ${id}`,
  createdAt: 1_700_000_000_000,
  ...overrides,
});

try {
  // ── 1. memo Comparar: reconocer solo los campos que afectan la representación ─────────────────────────
  const base = message("m1");
  assert(mod.areMessagesEqual(base, base) === true, "Equivalencia directa con la misma referencia");
  assert(mod.areMessagesEqual(base, message("m1")) === true, "Los nuevos objetos con el mismo contenido son iguales (memo debe omitirse)");
  assert(mod.areMessagesEqual(base, message("m1", { content: "cambiado" })) === false, "El texto ha cambiado y es necesario volver a renderizarlo.");
  assert(mod.areMessagesEqual(base, message("m1", { status: "failed" })) === false, "Los cambios de estado requieren volver a renderizarse");
  assert(mod.areMessagesEqual(base, message("m1", { reasoning: "piensa en ello" })) === false, "Es necesario volver a representar los cambios en el proceso de pensamiento.");
  assert(mod.areMessagesEqual(base, message("m1", { quotedContent: "Cita" })) === false, "Los cambios en el bloque de referencia requieren volver a renderizarse");
  assert(mod.areMessagesEqual(base, message("m2")) === false, "Las diferentes identificaciones se juzgarán de manera diferente.");
  assert(
    mod.areMessagesEqual(
      message("m3", { imageUrls: ["/api/media/a"] }),
      message("m3", { imageUrls: ["/api/media/a"] }),
    ) === true,
    "Las matrices de direcciones de medios se comparan por contenido (no por referencia)",
  );
  assert(
    mod.areMessagesEqual(
      message("m3", { imageUrls: ["/api/media/a"] }),
      message("m3", { imageUrls: ["/api/media/b"] }),
    ) === false,
    "La dirección del medio ha cambiado y es necesario volver a representarla.",
  );
  const call = (result) => ({
    toolCalls: [{ callId: "c1", name: "read_file", argumentsPreview: "{}", resultPreview: result, success: true, durationMs: 12 }],
  });
  assert(mod.areMessagesEqual(message("m4", call("ok")), message("m4", call("ok"))) === true, "Los detalles de la llamada a la herramienta son los mismos.");
  assert(mod.areMessagesEqual(message("m4", call("ok")), message("m4", call("cambiado"))) === false, "El resultado de la herramienta ha cambiado y es necesario volver a renderizarlo.");

  const caps = { known: true };
  const bubbleProps = (overrides = {}) => ({
    message: base,
    streaming: false,
    showActions: false,
    capabilities: caps,
    favorited: false,
    actionsOpen: false,
    selectionMode: false,
    ...overrides,
  });
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps()) === true, "Saltar renderizado si los accesorios de la burbuja permanecen sin cambios");
  assert(
    mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ onCopy: () => undefined, onAction: () => undefined })) === true,
    "Reemplazar la devolución de llamada con una nueva referencia no afecta el juicio (las devoluciones de llamada son estables y no se compararán)",
  );
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ streaming: true })) === false, "Los cambios de estado de transmisión requieren volver a renderizarse");
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ actionsOpen: true })) === false, "Es necesario volver a representar la apertura y el cierre del menú.");
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ favorited: true })) === false, "Los cambios de estado de la colección requieren volver a renderizarse");
  assert(/export const MessageBubble = memo\(MessageBubbleView, areBubblePropsEqual\)/.test(BUBBLE), "De hecho, el componente de mensaje está inactivo. memo");

  // ── 2. Fusión de instantáneas: los mensajes sin cambios se reemplazan con referencias antiguas ─────────────────────────
  const first = message("a");
  const second = message("b");
  const previous = [first, second];

  const sameNext = [message("a"), message("b")];
  const mergedSame = mod.mergeMessages(previous, sameNext);
  assert(mergedSame === previous, "Si toda la matriz permanece sin cambios, reutilice la matriz anterior directamente.");

  const streamingNext = [message("a"), message("b", { content: "Transmisión…" })];
  const mergedStreaming = mod.mergeMessages(previous, streamingNext);
  assert(mergedStreaming[0] === first, "Los mensajes sin cambios reutilizan objetos antiguos (memo solo válido)");
  assert(mergedStreaming[1] === streamingNext[1], "El cambiado usa un nuevo objeto.");
  assert(mergedStreaming.length === 2 && mergedStreaming !== previous, "El resultado de la combinación es una nueva matriz, no un cambio in situ.");

  const appended = mod.mergeMessages(previous, [message("a"), message("b"), message("c")]);
  assert(appended.length === 3 && appended[0] === first && appended[1] === second, "Al agregar, la referencia anterior se mantiene sin cambios.");
  assert(appended[2]?.id === "c", "Los mensajes recién agregados se agregan a la lista.");

  const replacedFirst = mod.mergeMessages(previous, [message("a", { content: "cambiado" }), message("b")]);
  assert(
    replacedFirst[0] !== first && replacedFirst !== previous,
    "Al cambiar al principio, solo se reutiliza el prefijo común (aquí está el prefijo vacío) y la copia completa se reemplaza por una nueva.——No mueva objetos entre ubicaciones",
  );

  assert(mod.mergeMessages([], previous) === previous, "La combinación de listas vacías devuelve directamente una nueva instantánea");

  // ── 3. Ventanas: cálculo de umbral y área visual ──────────────────────────────
  assert(mod.WINDOW_THRESHOLD === 150 && mod.WINDOW_OVERSCAN === 20, "Umbral 150, 20 elementos superior e inferior cada uno");
  const small = mod.planWindow({ itemCount: 150, scrollTop: 0, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(small.active === false && small.start === 0 && small.end === 150, "Renderizar en su totalidad cuando no se incluyen más de 150 elementos");

  const big = mod.planWindow({ itemCount: 400, scrollTop: 1000, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(big.active === true, "Habilitar ventanas cuando se excede el umbral");
  assert(big.start === 0 && big.end === 36, `desplazamiento 1000px / altura de línea 100 / ventana gráfica 600 → renderizado [0,36): Real [${big.start},${big.end})`);
  assert(big.topPad === 0 && big.bottomPad === (400 - 36) * 100, "Los espacios ocupados en ambos extremos se llenan hasta la altura total según la altura promedio de fila.");

  const middle = mod.planWindow({ itemCount: 400, scrollTop: 5000, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(middle.start === 30 && middle.end === 76, `ventana central [30,76): Real [${middle.start},${middle.end})`);
  assert(middle.topPad === 30 * 100, "Marcador de posición superior = start × altura de fila");

  const tail = mod.planWindow({ itemCount: 400, scrollTop: 0, viewportHeight: 600, averageHeight: 100, pinnedToEnd: true });
  assert(tail.end === 400 && tail.start === 374, `Anclar el extremo al pegarlo al fondo: real [${tail.start},${tail.end})`);
  assert(tail.bottomPad === 0, "No queda espacio en la parte inferior al fijarlo a la parte inferior.");

  const unknown = mod.planWindow({ itemCount: 400, scrollTop: 0, viewportHeight: 600, averageHeight: 0, pinnedToEnd: true });
  assert(unknown.end === 400 && unknown.start === 400 - Math.ceil(600 / mod.DEFAULT_ROW_HEIGHT) - 20, "Utilice la valoración predeterminada cuando la altura de la fila aún no se haya medido");
  const overscroll = mod.planWindow({ itemCount: 200, scrollTop: 1e9, viewportHeight: 600, averageHeight: 50, pinnedToEnd: false });
  assert(overscroll.end === 200 && overscroll.bottomPad === 0, "Al desplazarse sobre la cabeza, la ventana se sujeta al final, ocupando el espacio de 0");
  assert(mod.planWindow({ itemCount: 0, scrollTop: 0, viewportHeight: 0, averageHeight: 0, pinnedToEnd: false }).active === false, "La lista vacía no permite la creación de ventanas");

  assert(mod.measureAverage([100, 200]) === 150, "Altura promedio de fila promediada sobre filas renderizadas");
  assert(mod.measureAverage([]) === undefined && mod.measureAverage([0, -1]) === undefined, "Devuelto cuando no hay una altura de fila válida undefined");
  assert(mod.shouldUpdateAverage(0, 80) === true, "Mida la altura de la fila por primera vez y adóptela.");
  assert(mod.shouldUpdateAverage(100, 105) === false, "Desviación 5% sin actualización (zona muerta)");
  assert(mod.shouldUpdateAverage(100, 130) === true, "Actualizar solo si la desviación es del 30%");

  // ── 4. ?perf=1 Sólo tiene efecto cuando se proporcionan parámetros ────────────────────────────────
  assert(mod.perfFlag("?perf=1") === true && mod.perfFlag("perf=1") === true, "traer perf=1 recién abierto");
  assert(mod.perfFlag("") === false && mod.perfFlag("?perf=0") === false && mod.perfFlag("?debug=1") === false, "Sin registros sin parámetros");
  const line = mod.formatPerfLine(120, 12.34);
  assert(line.includes("120 mensajes") && line.includes("12.3ms"), `Formato de registro:${line}`);

  // ── 5. Cableado ───────────────────────────────────────────────
  assert(/useMessageWindow\(/.test(CHAT) && /windowPlan\.topPad/.test(CHAT) && /windowPlan\.bottomPad/.test(CHAT), "La página de chat utiliza un plan con ventanas para representar los marcadores de posición superior e inferior.");
  assert(/items\.slice\(windowPlan\.start, windowPlan\.end\)/.test(CHAT), "Solo renderiza elementos dentro de la ventana");
  assert(CHAT.includes('data-host-row'), "Marcas de medición para cada fila (la altura de la fila se rellena a partir de esto)");
  assert(/perfFlag\(/.test(CHAT) && /formatPerfLine\(/.test(CHAT) && /onSnapshot:/.test(CHAT), "?perf=1 El tiempo que lleva grabar una instantánea.");
  assert(/stickRef\.current,?\s*$/m.test(CHAT) || CHAT.includes("pinnedToEnd: stickRef.current"), "Al pegar en la parte inferior, el anclaje de la ventana queda al final.");

  // ── 6. Dinámica de animación y reducción. ─────────────────────────────────────
  assert(
    /\.host-msg-row\[data-new="true"\] \{ animation: host-row-in var\(--muse-motion-durations-content-enter\)/.test(CSS),
    "Utilice la duración de entrada de contenido para que entren nuevos mensajes (solo se proporciona el último para evitar entradas repetidas durante el desplazamiento de la ventana)",
  );
  assert(/var\(--muse-motion-easings-emphasized\)/.test(CSS), "Facilitar la obtención del token de movimiento");
  assert(/\.host-msg-status,\s*\n?\s*\.host-msg-failed \{ animation: host-status-in/.test(CSS), "Hay una transición en los cambios de estado.");
  assert(/\.host-drawer \{[\s\S]{0,200}var\(--muse-motion-durations-nav-horizontal\)/.test(CSS), "El tiempo de apertura y cierre del cajón mueve la ficha de movimiento.");
  assert(/\.host-scrim \{ transition: opacity var\(--muse-motion-durations-normal\)/.test(CSS), "La duración del desvanecimiento de la máscara toma el token de movimiento.");
  const reduced = CSS.slice(CSS.indexOf("@media (prefers-reduced-motion: reduce)"));
  for (const selector of [".host-msg-row", ".host-msg-status", ".host-msg-failed", ".host-drawer", ".host-scrim", ".host-picker"]) {
    assert(reduced.includes(selector), `Al reducir la dinámica. ${selector} también apagado`);
  }
  assert(/animation: none !important/.test(reduced) && /transition: none !important/.test(reduced), "Reducir la dinámica significa \"no moverse\" en lugar de \"acelerar\"");
  assert(/\.host-window-pad \{ flex: 0 0 auto/.test(CSS), "El bloque de marcador de posición de la ventana no participa en el escalado.");

  if (failures > 0) throw new Error(`Las afirmaciones de rendimiento y animación fallaron (${failures} artículo)`);
  console.log("\nTodas las afirmaciones de rendimiento y animación pasaron (memo / Fusionar/ventanas/cambio de rendimiento/animación).");
} finally {
  await rm(TEMP, { force: true });
}
