/**
 * Afirmaciones estáticas para adaptación a pantalla ancha: puntos de interrupción de tercer nivel, exclusión mutua de la columna izquierda y el cajón, restricciones de ancho máximo de contenido, teclas de acceso directo.
 *
 * Estas son restricciones CSS/estructurales puras y no se pueden afirmar en tiempo de ejecución sin el navegador, por lo que directamente `styles.css`
 * Realice una inspección estructurada con el código fuente del componente: primero `@media (min-width: …)` El bloque se escribe y luego se escribe el bloque de aserción.
 * Qué fue escrito exactamente. Una vez que se cambian por error relaciones mutuamente excluyentes como "los archivos de escritorio ocultan el cajón" y "los archivos del teléfono celular no muestran la columna de la izquierda",
 * `npm test` Falla inmediatamente.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const CSS_RAW = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const DRAWER = readFileSync(join(ROOT, "src", "host", "SessionDrawer.tsx"), "utf8");
const LIGHTBOX = readFileSync(join(ROOT, "src", "host", "ImageLightbox.tsx"), "utf8");

/** El nombre del selector también aparecerá en los comentarios. Primero elimine los comentarios y luego busque el cuerpo de la regla para evitar tocar los comentarios. */
const CSS = CSS_RAW.replace(/\/\*[\s\S]*?\*\//g, "");

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

/** cortar algo `@media (…)` bloque (incluido todo lo que esté entre llaves). */
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

/** El cuerpo de la declaración que sigue inmediatamente a un selector (pasar al siguiente `}`). */
function ruleBody(block, selector) {
  const start = block.indexOf(selector);
  if (start < 0) return "";
  const open = block.indexOf("{", start);
  const close = block.indexOf("}", open);
  if (open < 0 || close < 0) return "";
  return block.slice(open + 1, close);
}

const tablet = mediaBlock(CSS, "min-width: 600px");
const desktop = mediaBlock(CSS, "min-width: 1024px");

try {
  // ── 1. Punto de interrupción de la tercera marcha ─────────────────────────────────────────────────
  assert(CSS.includes("@media (min-width: 600px) {"), "Hay un punto de ruptura plano (min-width: 600px)");
  assert(CSS.includes("@media (min-width: 1024px) {"), "Hay un punto de interrupción del archivo de escritorio (min-width: 1024px)");
  assert(tablet.length > 0 && desktop.length > 0, "Ambos puntos de interrupción tienen bloques (no shells vacíos)");
  assert(
    !/min-width:\s*(7\d\d|8\d\d|9\d\d)px/.test(CSS),
    "No se insertan puntos de interrupción de ancho más allá del tercer nivel (el nivel del teléfono celular es el estilo predeterminado, no se agrega ninguna consulta)",
  );

  // ── 2. La columna izquierda y el cajón son mutuamente excluyentes: los teléfonos celulares/tabletas solo tienen cajones y las computadoras de escritorio solo tienen columnas izquierdas. ──────────
  assert(/display:\s*none/.test(ruleBody(CSS, ".host-sidebar {")), "La columna izquierda del archivo del teléfono celular/tableta no se muestra de forma predeterminada");
  const sidebarDesktop = ruleBody(desktop, ".host-sidebar {");
  assert(/display:\s*flex/.test(sidebarDesktop), "La columna izquierda del archivo del escritorio es permanente (display: flex)");
  assert(/flex-direction:\s*column/.test(sidebarDesktop), "La columna de la izquierda es una columna vertical (la lista de conversaciones se desplaza sola)");
  assert(
    /flex:\s*0 0 var\(--host-sidebar-w\)/.test(sidebarDesktop) && /width:\s*var\(--host-sidebar-w\)/.test(sidebarDesktop),
    "Ancho de la columna izquierda vaya a --host-sidebar-w (280–320 engranaje)",
  );
  assert(/\.host-scrim,\s*\.host-drawer\s*\{\s*display:\s*none/.test(desktop), "No aparece ni el cajón del escritorio ni la máscara (se excluyen mutuamente con la columna de la izquierda)");
  assert(/button\.quiet\.host-menu-button\s*\{\s*display:\s*none/.test(desktop), "La barra superior de archivos del escritorio no se muestra ☰");
  // Estos dos "pieles" deben compararse. `button.host-icon-button { display: inline-grid }` Sea más específico; de lo contrario, se bloqueará el nombre de clase del mismo nivel.
  assert(/button\.host-icon-button\s*\{[^}]*display:\s*inline-grid/.test(CSS), "La clase base del botón de icono viene con pantalla (por lo que la regla oculta debe anularse con .quiet)");

  // ── 3. Ancho máximo del contenido y espacio en blanco. ─────────────────────────────────────────
  const threadTablet = ruleBody(tablet, ".host-thread");
  assert(/max-width:\s*var\(--host-content-max\)/.test(threadTablet), "Límite de ancho de la barra de mensajes de archivos de la tableta (var(--host-content-max))");
  assert(/margin-inline:\s*auto/.test(threadTablet), "La barra de mensajes de la tableta está centrada y dejada en blanco en ambos lados.");
  assert(/--host-content-max:\s*640px/.test(ruleBody(CSS, ".host-scope {")), "El ancho máximo predeterminado del contenido del archivo de la tableta es 640 (definido en .host-scope)");
  assert(/--host-content-max:\s*720px/.test(desktop), "El ancho máximo del contenido del archivo de escritorio se puede reducir a 720");
  assert(
    /\.host-composer\s*\{[^}]*padding-inline:\s*max\([^}]*var\(--host-content-max\)/.test(tablet),
    "El campo de entrada de la tableta está centrado con el ancho del contenido (no con el ancho de pantalla completa)",
  );
  assert(
    /\.host-composer\s*\{[^}]*padding-inline:\s*max\(var\(--muse-space-large-gap\)/.test(desktop),
    "El relleno izquierdo y derecho del campo de entrada del archivo del escritorio debe ser al menos 24 (largeGap)",
  );
  assert(
    /\.host-scroll\s*\{\s*padding-inline:\s*var\(--muse-space-large-gap\)/.test(desktop),
    "El relleno izquierdo y derecho del área de mensajes del escritorio debe ser al menos 24",
  );
  assert(
    /\.host-root:not\(\.is-settings\)\s*\{\s*flex-direction:\s*row/.test(desktop),
    "El shell del archivo del escritorio se ha cambiado a dos columnas a la izquierda y a la derecha (solo vista de chat, la página de configuración sigue siendo una sola columna)",
  );
  assert(
    /\.host-root\.is-settings \.muse-screen-body\s*\{[^}]*max-width:\s*var\(--host-content-max\)/.test(desktop),
    "La página de configuración está centrada dentro del ancho del escritorio y no ocupa toda la pantalla.",
  );
  assert(/--host-sidebar-w:\s*300px/.test(ruleBody(CSS, ".host-scope {")), "La variable de ancho de la columna izquierda toma 300 (280–320 mediana)");
  // La columna de la izquierda es plegable: el estado colapsado no ocupa espacio de forma predeterminada y aparece un botón de expansión en la columna superior después de colapsar.
  assert(/\.host-sidebar\.is-collapsed\s*\{\s*display:\s*none/.test(desktop), "La columna izquierda del archivo del escritorio no ocupa espacio después de contraerlo");
  assert(/\.host-root\.is-sidebar-collapsed \.host-sidebar-toggle\s*\{\s*display:\s*inline-grid/.test(desktop), "Después de contraerse, aparece un botón de expansión en la barra superior");
  assert(
    /display:\s*none/.test(ruleBody(CSS, "button.quiet.host-sidebar-toggle {")),
    "El botón expandir no se muestra de forma predeterminada (no aparece en archivos de teléfonos celulares/tabletas)",
  );

  // ── 4. Estructura: columna izquierda permanente + columna principal que cubre la columna superior/mensaje/campo de entrada ──────────────
  assert(/<SessionSidebar/.test(CHAT), "La página de chat se muestra en la columna de la izquierda.");
  assert(/<div className="host-main">/.test(CHAT), "La barra superior/área de mensaje/campo de entrada está envuelta en la columna principal");
  assert(/host-menu-button/.test(CHAT), "☰ Botones con nombres de clases que los archivos del escritorio pueden ocultar");
  assert(/className="host-root host-scope is-settings"/.test(CHAT), "Vista de configuración con etiqueta is-settings");
  assert(/export function SessionSidebar/.test(DRAWER), "La columna de la izquierda es un componente exportado de forma independiente (comparte el mismo panel con el cajón)");
  assert(/host-sidebar-settings[\s\S]{0,160}t\("common\.settings"\)/.test(DRAWER), "Hay una entrada de configuración en la parte superior de la columna izquierda.");
  assert(/variant === "drawer"[\s\S]{0,300}host-drawer-close/.test(DRAWER), "El cajón conserva el botón de cierre (archivo del celular)");
  assert(/<SessionDrawer/.test(CHAT), "El cajón todavía está colgado en archivos de dispositivos celulares/tabletas (oculto por CSS en archivos de escritorio)");
  assert(/\(min-width: 1024px\)/.test(DRAWER), "Los puntos de interrupción utilizados para la captación previa en la columna de la izquierda son consistentes con el archivo de escritorio CSS (1024)");
  assert(/host-sidebar-collapse/.test(DRAWER), "Hay un botón de cerrar en la parte superior de la columna izquierda.");
  assert(/collapsed=\{sidebarCollapsed\}/.test(CHAT) && /is-sidebar-collapsed/.test(CHAT), "El estado colapsado de la columna izquierda lo mantiene la página de chat.");

  // ── 5. Fila de sesión a lo largo de la geometría ListRow + estado seleccionado ───────────────────────────
  const sessionRow = ruleBody(CSS, "button.host-session {");
  assert(/min-height:\s*var\(--muse-component-list-row-min-height\)/.test(sessionRow), "La altura de la fila de la sesión pasa a ListRow 60");
  assert(
    /padding:\s*var\(--muse-component-list-row-padding-y\) var\(--muse-component-list-row-padding-x\)/.test(sessionRow),
    "Los márgenes en línea de la sesión van para ListRow 16/12",
  );
  assert(/\.host-session-wrap\.is-active/.test(CSS), "La sesión seleccionada está resaltada.");

  // ── 6. Teclas de acceso directo:Enter Enviar / Shift+Enter para ajustar / Esc para cerrar la pantalla completa ────────
  assert(/if \(event\.key !== "Enter"\) return;/.test(CHAT) && /if \(event\.shiftKey\) return;/.test(CHAT), "Enter enviar,Shift+Enter No interceptar (avance de línea)");
  assert(/event\.nativeEvent\.isComposing/.test(CHAT), "Sin mala pronunciación de palabras en el método de entrada");
  assert(/event\.key === "Escape"/.test(LIGHTBOX), "Esc Desactivar imagen en pantalla completa");

  if (failures > 0) throw new Error(`Error al afirmar el diseño de pantalla ancha (${failures} artículo)`);
  console.log("\nSe aprobaron todas las afirmaciones de diseño de pantalla ancha (puntos de interrupción de tres niveles/exclusión mutua de la columna izquierda y el cajón/límite de contenido/teclas de acceso directo).");
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
