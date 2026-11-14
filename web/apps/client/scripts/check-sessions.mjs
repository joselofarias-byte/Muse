/**
 * Confirmación de disponibilidad de sesión y asistente: ventana emergente de selección de asistente, verificación de cambio de nombre en línea, visibilidad de archivo, copia de eliminación,
 * memoria de posición de desplazamiento,focus_not_held consejos exclusivos.
 *
 * lógica pura (sessionActions / scrollMemory / hostErrors) ejecutar directamente; utilizado para listas y capas de viñetas
 * renderToStaticMarkup Representación; ruta de interacción (haga clic en cambiar nombre, haga clic en asistente) y aserciones de cable de acuerdo con el código fuente.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".sessions-check.mjs");

const ENTRY = `
export {
  assistantIdForNewSession,
  isRenameValid,
  normalizeSessionTitle,
  shouldAskAssistant,
  visibleSessions,
} from "./src/host/sessionActions.ts";
export { ES } from "./src/i18n/messages.ts";
export { ScrollMemory } from "./src/host/scrollMemory.ts";
export { describeHostError } from "./src/host/hostErrors.ts";
export { translate } from "./src/i18n/translate.ts";
export { HostAvatar } from "./src/host/HostAvatar.tsx";
export { AssistantPicker } from "./src/host/AssistantPicker.tsx";
export { SessionSidebar } from "./src/host/SessionDrawer.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "sessions-entry.tsx", loader: "tsx" },
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

const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const DRAWER = readFileSync(join(ROOT, "src", "host", "SessionDrawer.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");

const session = (id, title, overrides = {}) => ({ id, title, assistantId: "a1", updatedAt: 1_700_000_000_000, archived: false, pinned: false, ...overrides });

try {
  // ── 1. Cree una nueva sesión: seleccione primero varios asistentes, cree una sesión directamente con un solo asistente ──────────────────────
  const two = [{ id: "a1", name: "Muse" }, { id: "a2", name: "Iris" }];
  const one = [{ id: "a1", name: "Muse" }];
  assert(mod.shouldAskAssistant(two) === true, "Si hay más de un asistente, elige primero");
  assert(mod.shouldAskAssistant(one) === false, "No jugar a la selección cuando solo hay un asistente");
  assert(mod.shouldAskAssistant([]) === false, "Créelo directamente antes de que la lista de asistentes esté disponible (déjelo en manos del asistente predeterminado del Anfitrión)");
  assert(mod.assistantIdForNewSession(one) === "a1", "Llévalo contigo cuando solo tengas un asistente");
  assert(mod.assistantIdForNewSession(two) === undefined, "No decide por el usuario cuando hay varios asistentes");
  assert(/shouldAskAssistant\(assistants\)/.test(CHAT), "Al crear una nueva sesión, se realiza el juicio de \"debería seleccionar un asistente primero\".");
  assert(/assistantIdForNewSession\(assistants\)/.test(CHAT), "cinturón de camino de un solo asistente assistantId");
  assert(/type: "session\.new", assistantId/.test(CHAT), "Después de seleccionar el asistente, toma session.new assistantId");

  const pickerHtml = render(
    mod.createElement(mod.AssistantPicker, {
      assistants: [
        { id: "a1", name: "PROTO-Muse", avatarUri: "/api/media/face-1", isDefault: true },
        { id: "a2", name: "PROTO-Iris" },
      ],
      onPick: () => undefined,
      onClose: () => undefined,
    }),
  );
  assert(pickerHtml.includes('role="dialog"') && pickerHtml.includes('aria-label="Elegir asistente"'), "La selección del asistente es un cuadro de diálogo.");
  assert(pickerHtml.includes("PROTO-Muse") && pickerHtml.includes("PROTO-Iris"), "Ambas líneas de ayudantes enumeran nombres");
  assert(pickerHtml.includes(">Predeterminado<"), "El asistente predeterminado está marcado");
  assert(pickerHtml.includes('src="/api/media/face-1"'), "Renderizar imagen cuando la dirección del avatar esté disponible");
  assert((pickerHtml.match(/role="option"/g) ?? []).length === 2, "Cada asistente es un option");
  assert(pickerHtml.includes("Cancelar"), "La capa elástica tiene una entrada de cancelación.");

  const avatarFallback = render(mod.createElement(mod.HostAvatar, { name: "Muse" }));
  assert(avatarFallback.includes("host-avatar-initial") && avatarFallback.includes(">M<"), "Si no hay avatar, utilice las iniciales del nombre.");
  const avatarLocal = render(mod.createElement(mod.HostAvatar, { name: "Muse", uri: "file:///data/face.png" }));
  assert(avatarLocal.includes(">M<") && !avatarLocal.includes("<img"), "La dirección del avatar local en el teléfono celular devuelve la primera letra sin perder la imagen rota.");

  // ── 2. Cambio de nombre en línea: no se permiten nombres vacíos ─────────────────────────────────
  assert(mod.isRenameValid("") === false, "No se permite enviar nombres vacíos");
  assert(mod.isRenameValid("   ") === false, "Sólo los espacios en blanco se consideran nombres vacíos.");
  assert(mod.isRenameValid(" nuevo nombre ") === true, "Se pueden enviar títulos normales.");
  assert(mod.normalizeSessionTitle("  nuevo nombre ") === "nuevo nombre", "Elimine los espacios en blanco iniciales y finales y comprima los espacios en blanco del medio antes del envío");
  assert(DRAWER.includes("host-session-rename") && /disabled=\{!isRenameValid\(renameDraft\)\}/.test(DRAWER), "\"Guardar\" está deshabilitado cuando el nombre está vacío");
  assert(DRAWER.includes('t("sessions.renameEmpty")'), "Deje un nombre en blanco y dé una pista.");
  assert(/event\.key === "Enter"/.test(DRAWER) && /event\.key === "Escape"/.test(DRAWER), "Cambiar nombre admite Enter para enviar / Esc para cancelar");
  assert(/setRenamingId\(session\.id\)/.test(DRAWER), "⋯ \"Cambiar nombre\" en el menú cambia a entrada en línea");
  assert(!/window\.prompt\(/.test(DRAWER), "No más uso de window.prompt para cambiar el nombre");

  // ── 3. Interruptor de visibilidad de archivos ─────────────────────────────────────────
  const mixed = [session("s1", "En progreso"), session("s2", "Archivado", { archived: true })];
  assert(mod.visibleSessions(mixed, false).length === 1, "Las sesiones de archivo no aparecen en la lista de forma predeterminada");
  assert(mod.visibleSessions(mixed, true).length === 2, "Abra \"Mostrar archivos\" y enumerelos");
  // Host En el futuro, las sesiones archivadas también se incluirán en instantáneas: el filtrado debe permanecer en el nivel del panel y no se puede eliminar por adelantado en el nivel superior.
  assert(/visibleSessions\(sessions, showArchived\)/.test(DRAWER), "El filtrado de archivos se calcula presionando el interruptor en el panel de sesión");
  assert(/sessions=\{sessions\}/.test(CHAT), "El shell de chat entrega la conversación en la instantánea al panel tal como está (incluidos los archivos)");

  const sidebar = (showArchived) =>
    render(
      mod.createElement(mod.SessionSidebar, {
        sessions: mixed,
        ready: true,
        showArchived,
        onToggleArchived: () => undefined,
        currentId: "s1",
        previews: {},
        buildId: "test",
        onSelect: () => undefined,
        onNew: () => undefined,
        onRequestPreviews: () => undefined,
        onRename: () => undefined,
        onArchive: () => undefined,
        onDelete: () => undefined,
        onOpenSettings: () => undefined,
        onLogout: () => undefined,
        collapsed: false,
        onToggleSidebar: () => undefined,
      }),
    );
  const hidden = sidebar(false);
  assert(!hidden.includes("Archivado"), "Las sesiones de archivo no aparecen cuando el interruptor está apagado");
  assert(hidden.includes("Mostrar archivadas") && hidden.includes('role="switch"'), "Hay un interruptor \"Mostrar archivadas\" en la cabeza.");
  const shown = sidebar(true);
  assert(shown.includes("Archivado") && shown.includes("Archivado"), "Después de activar el interruptor, aparece la sesión de archivo y está marcada como \"Archivada\".");
  assert(/onToggleArchived/.test(DRAWER), "El estado del interruptor lo mantiene la capa superior.");

  // ── 4. Elimine la copia de confirmación (la entrada está en la tabla i18n y el shell de chat es responsable de configurar los parámetros)────────
  assert(mod.ES["sessions.deleteConfirm"].includes("{name}"), "La confirmación de eliminación de sesión es una entrada con un marcador de posición");
  assert(mod.ES["sessions.deleteConfirm"].includes("mensajes") && mod.ES["sessions.deleteConfirm"].includes("no se podrán restaurar"), "La confirmación avisa que se borran los mensajes y no se puede deshacer.");
  assert(CHAT.includes('t("sessions.deleteConfirm", { name: title.trim() || t("sessions.untitled") })'), "El shell de chat inserta el nombre de la conversación y el nombre sin nombre en la entrada");
  assert(!CHAT.includes("Esta acción es irreversible."), "Se ha borrado la copia antigua muerta.");

  // ── 5. memoria de posición de desplazamiento ───────────────────────────────────────────
  const memory = new mod.ScrollMemory();
  assert(memory.recall("s1") === undefined, "Si no se graba, no habrá ubicación.");
  memory.remember("s1", 420);
  memory.remember("s2", 0);
  assert(memory.recall("s1") === 420 && memory.recall("s2") === 0, "Mantenga notas separadas para cada conversación");
  memory.remember("s1", 900);
  assert(memory.recall("s1") === 900, "Grabar nuevamente sobrescribirá el valor anterior.");
  memory.forget("s1");
  assert(memory.recall("s1") === undefined, "No regreses al lugar después de haberlo olvidado.");
  memory.remember("bad", -5);
  assert(memory.recall("bad") === undefined, "Las ubicaciones ilegales no se registran");
  const capped = new mod.ScrollMemory();
  for (let index = 0; index < 260; index += 1) capped.remember(`s${index}`, index);
  assert(capped.size() === 200, "El número de memorias tiene un límite superior y no aumentará indefinidamente.");
  assert(/scrollMemoryRef\.current\.remember|scrollMemoryRef\.current\.recall/.test(CHAT), "La página de chat utiliza esta memoria para acceder a la ubicación.");
  assert(/pendingRestoreRef/.test(CHAT), "Después de cambiar de sesión, espere hasta que se cambie el contenido y luego colóquelo nuevamente.");

  // ── 6. focus_not_held Consejos exclusivos para ─────────────────────────────
  const zh = (key) => mod.translate("es", key);
  assert(mod.describeHostError("focus_not_held", "x", zh) === zh("error.focusNotHeld"), "focus_not_held Cambiar a consejos exclusivos");
  assert(zh("error.focusNotHeld").includes("Otro dispositivo"), `Aviso en español:${zh("error.focusNotHeld")}`);
  assert(mod.translate("en", "error.focusNotHeld").includes("Another device"), "Los consejos en inglés también tienen redacción exclusiva.");
  assert(mod.describeHostError("session_not_found", "x", zh).includes("ya no existe"), "session_not_found También tiene copia legible.");
  const notInSession = mod.describeHostError("message_not_in_current_session", "x", zh);
  assert(notInSession.includes("no está en la conversación actual"), `message_not_in_current_session:${notInSession}`);
  const deletedRace = mod.describeHostError("message_not_found", "x", zh);
  assert(deletedRace.includes("acaba de eliminar") || deletedRace.includes("ya no existe"), `message_not_found(Eliminar competencia) palabras humanas:${deletedRace}`);
  assert(mod.describeHostError("Algún error desconocido", "Ten todo en cuenta", zh) === "Algún error desconocido", "Las razones desconocidas se revelan tal como son.");
  assert(mod.describeHostError(undefined, "Ten todo en cuenta", zh) === "Ten todo en cuenta", "Utilice redacción publicitaria encubierta cuando no haya ningún motivo");
  assert((CONNECTION.match(/describeHostError\(/g) ?? []).length >= 3, "Siga las tres rutas de eventos de error y tradúzcalas");

  // ── 7. Información del asistente de barra superior ───────────────────────────────────────────
  assert(CHAT.includes("currentAssistant") && CHAT.includes("<HostAvatar name={currentAssistant.name}"), "La barra superior muestra el avatar del asistente actual.");
  assert(CHAT.includes('t("chat.pickAssistant")'), "Cuando no hay conversación, la barra superior ofrece orientación.");
  assert(CHAT.includes("${currentAssistant.name} · "), "Subtítulo de la columna superior con el nombre del asistente");

  // ── 8. estilo ───────────────────────────────────────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-avatar", "host-picker", "host-session-rename", "host-archived-toggle", "host-switch-loading", "host-topbar-head"]) {
    assert(css.includes(`.${selector}`), `definido en el estilo .${selector}`);
  }

  if (failures > 0) throw new Error(`la sesión con la afirmación del ayudante falló (${failures} artículo)`);
  console.log("\nSe aprobaron todas las afirmaciones de sesión y asistente (selección de asistente/cambiar nombre/archivar/eliminar copia/desplazar memoria/mensaje de error).");
} finally {
  await rm(TEMP, { force: true });
}
