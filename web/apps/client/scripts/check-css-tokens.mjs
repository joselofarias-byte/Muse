/**
 * Validación estática de tokens de solo lectura de estilo de página.
 *
 * Reglas (alineadas con tokens.json/token-css.ts):
 *  1. cualquier desnudo hex / rgba() color → falla (el color sólo puede provenir de `--muse-*`).
 *  2. Host alcance desnudo `px` longitud → falló (ver lista blanca PX_* Constantes, anota el motivo de cada una).
 *     Host Alcance = styles.css adentro `Host mobile shell` Separe la sección después del comentario;
 *     primitives.css Toda la copia se alinea con la capa primitiva de Compose y se verifica toda la copia.
 *  3. La referencia no existe `--muse-*` variable → Fallo (token renombrado/expuesto inmediatamente después de la eliminación).
 *
 * El conjunto completo de tokens no se escribirá aquí con otro conjunto de reglas de nomenclatura: empaquetado directo `src/design/token-css.ts`(al construir
 * También generado usando el mismo código. `:root` variable) y luego tomar `tokenVariables()`, por lo que el producto de suma de comprobación no se desviará.
 *
 * Uso:npm --workspace @muse/client run test(Ejecutar junto con la afirmación de representación de la página de configuración)
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".token-css-check.mjs");
const TOKENS_FILE = join(ROOT, "src", "design", "tokens.json");

/** Verifique solo archivos con ámbito de host: a partir de este comentario delimitador. */
const HOST_REGION_MARKER = "Host mobile shell";

/** Se verifica el px de todo el documento (capa primitiva, todos los valores provienen de la especificación Compose). */
const FULLY_CHECKED_FILES = ["src/components/primitives.css"];
/** Solo el alcance del host verifica los archivos px. */
const HOST_ONLY_FILES = ["src/styles.css"];

/**
 * px Lista blanca.
 *
 * Sólo se aceptan dos tipos:0(la longitud es cero, la escala y la especificación no tienen sentido), y "App No hay ningún token correspondiente en
 * Y es un caso especial de un solo punto de "IU temporal de escritorio pura" (los motivos deben indicarse uno por uno).
 * Cualquier entrada nueva debe tener una línea de explicación aquí; de lo contrario, es hora de agregar tokens a tokens.json.
 */
const PX_WHITELIST = [
  {
    id: "zero",
    reason: "0 Longitud: sin cantidad escalable,tokens.json El archivo 0 tampoco se guarda",
    allows: (value) => value === 0,
  },
];

const DECLARATION_WHITELIST = [
  {
    id: "drawer-desktop-max-width",
    reason:
      "Ancho máximo del cajón de escritorio 330px:App Aquí se muestra el deslizamiento lateral en pantalla completa en teléfonos celulares (sin límite superior de ancho),Web Debe estar limitado en el escritorio;MusePaddings No existe un rango de ancho correspondiente, por lo que no se creará ningún token nuevo para este propósito.",
    matches: (declaration) => /width:\s*min\(\s*84vw\s*,\s*330px\s*\)/.test(declaration),
  },
  {
    id: "toast-desktop-max-width",
    reason:
      "toast El ancho máximo en el escritorio es 520 px:App El ancho de la tostada se adapta al contenido,MusePaddings No hay equipo correspondiente; lo que realmente es efectivo en el terminal celular es 92vw",
    matches: (declaration) => /max-width:\s*min\(\s*92vw\s*,\s*520px\s*\)/.test(declaration),
  },
  {
    id: "host-breakpoint-tablet",
    reason:
      "Punto de interrupción de la tableta 600 px: los atributos personalizados no están permitidos en consultas de medios (var() No válido en la condición previa de @media) y el punto de interrupción es el umbral de cambio de diseño, no el tamaño de diseño escalable.",
    matches: (declaration) => /^@media \(min-width: 600px\) \{/.test(declaration),
  },
  {
    id: "host-breakpoint-desktop",
    reason:
      "Punto de interrupción del archivo de escritorio 1024px: igual que el anterior, las condiciones previas de la consulta de medios no se pueden citar var(), el punto de interrupción es el umbral de diseño en lugar del tamaño del diseño",
    matches: (declaration) => /^@media \(min-width: 1024px\) \{/.test(declaration),
  },
  {
    id: "host-content-max-tablet",
    reason:
      "El ancho máximo del contenido de una sola columna en un archivo de tableta es 640 px:tokens No hay ninguna configuración de \"Ancho de columna de contenido\" (MusePaddings sólo el espaciado y el tamaño del componente), y sólo en 600–1023 Entra en vigor, solo cambia un lugar.",
    matches: (declaration) => /--host-content-max:\s*640px/.test(declaration),
  },
  {
    id: "host-content-max-desktop",
    reason:
      "El ancho máximo del contenido del archivo de escritorio es 720 px:16px Aproximadamente 80 caracteres de texto./El límite superior de lectura de filas, el valor del archivo de escritorio de la misma variable",
    matches: (declaration) => /--host-content-max:\s*720px/.test(declaration),
  },
  {
    id: "host-sidebar-width",
    reason:
      "Ancho de columna izquierda del archivo de escritorio 300 px (obligatorio) 280–320, tome el valor mediano):App Es un desplazamiento lateral de pantalla completa en teléfonos celulares, sin ancho de columna vertical.tokens No crees uno nuevo para esto.",
    matches: (declaration) => /--host-sidebar-w:\s*300px/.test(declaration),
  },
];

const failures = [];

function fail(file, line, reason, content) {
  failures.push({ file, line, reason, content });
}

async function tokenNames() {
  await build({
    entryPoints: [join(ROOT, "src", "design", "token-css.ts")],
    bundle: true,
    format: "esm",
    platform: "node",
    outfile: TEMP,
    logLevel: "warning",
  });
  const module = await import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
  const tokens = JSON.parse(readFileSync(TOKENS_FILE, "utf8"));
  const names = new Set(
    module.tokenVariables(tokens).map((declaration) => declaration.trim().split(":")[0]),
  );
  if (names.size === 0) throw new Error("token-css.ts No se genera ninguna variable token");
  return names;
}

/** Reemplace los comentarios de bloque con espacios en blanco de igual longitud, conservando los números de línea y las posiciones de las columnas. */
function blankComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "));
}

function checkHexAndRgba(file, lines) {
  lines.forEach((line, index) => {
    const hex = line.match(/#[0-9a-fA-F]{3,8}\b/);
    if (hex) fail(file, index + 1, `color desnudo ${hex[0]}(En lugar de utilizar --muse-* ficha)`, line.trim());
    const func = line.match(/\b(rgba?|hsla?)\s*\(/);
    if (func) fail(file, index + 1, `función de color desnudo ${func[1]}()(En lugar de utilizar --muse-* ficha)`, line.trim());
  });
}

function checkPx(file, lines, startIndex) {
  for (let index = startIndex; index < lines.length; index += 1) {
    const line = lines[index];
    const declaration = line.trim();
    if (declaration === "" || declaration.startsWith("/*")) continue;
    if (DECLARATION_WHITELIST.some((entry) => entry.matches(declaration))) continue;
    for (const match of line.matchAll(/(?<![\w.-])(\d*\.?\d+)px\b/g)) {
      const value = Number(match[1]);
      if (PX_WHITELIST.some((entry) => entry.allows(value))) continue;
      fail(
        file,
        index + 1,
        `longitud px desnuda ${match[0]}(En lugar de utilizar --muse-* token, o especifique el motivo en PX_WHITELIST)`,
        declaration,
      );
      break;
    }
  }
}

function checkMuseVariables(file, lines, declared) {
  lines.forEach((line, index) => {
    for (const match of line.matchAll(/var\(\s*(--muse-[a-z0-9-]+)/g)) {
      if (declared.has(match[1])) continue;
      fail(file, index + 1, `Se hace referencia a un token que no existe. ${match[1]}`, line.trim());
    }
  });
}

const declared = await tokenNames();
try {
  const files = [...FULLY_CHECKED_FILES, ...HOST_ONLY_FILES];
  for (const relativePath of files) {
    const absolute = join(ROOT, relativePath);
    const rawLines = readFileSync(absolute, "utf8").split(/\r?\n/);
    // Los comentarios deben ocultarse primero antes de buscar px/color; de lo contrario, los ejemplos en los comentarios también se considerarán valores de estilo.
    const lines = blankComments(rawLines.join("\n")).split(/\r?\n/);
    const display = relative(process.cwd(), absolute).split("\\").join("/");

    checkHexAndRgba(display, lines);
    checkMuseVariables(display, lines, declared);

    if (FULLY_CHECKED_FILES.includes(relativePath)) {
      checkPx(display, lines, 0);
      continue;
    }
    // La etiqueta delimitadora de alcance en sí es un comentario y debe encontrarse en la línea original.
    const markerIndex = rawLines.findIndex((line) => line.includes(HOST_REGION_MARKER));
    if (markerIndex < 0) {
      fail(display, 1, `No se encontró la anotación delimitada del alcance del host${HOST_REGION_MARKER}", incapaz de definir el rango de inspección de píxeles desnudos`, "");
      continue;
    }
    checkPx(display, lines, markerIndex);
  }
} finally {
  await rm(TEMP, { force: true });
}

console.log(`variable simbólica ${declared.size} ;comprobar archivos ${[...FULLY_CHECKED_FILES, ...HOST_ONLY_FILES].join(", ")}`);

if (failures.length > 0) {
  for (const item of failures) {
    console.error(`✗ ${item.file}:${item.line} ${item.reason}`);
    if (item.content) console.error(`    ${item.content}`);
  }
  throw new Error(`Error en la verificación del token de estilo de página (${failures.length} (en)`);
}

console.log("Se aprobó la verificación del token de estilo de página: sin color desnudo,Host Alcance sin px desnudos, sin tokens indefinidos.");
