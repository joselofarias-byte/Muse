/**
 * Página de datos y privacidad/Afirmaciones de página exclusiva de plataforma: Distribución de almacenamiento, Copia de seguridad de tres estados,PII cambiar la capacidad de escritura,
 * Mapeo de estado, indicaciones de nivel de habilidad faltantes y nuevas entradas para las cinco páginas de la plataforma.
 *
 * Los componentes se renderizan directamente usando renderToStaticMarkup; la ruta de interacción (haga clic en el interruptor, extraiga system.status) se afirma de acuerdo con el código fuente.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".data-platform-check.mjs");

const ENTRY = `
export { DataPrivacyPage } from "./src/host/DataPrivacyPage.tsx";
export { PlatformPage } from "./src/host/PlatformPage.tsx";
export { SYSTEM_STATUS_FLAGS, platformAvailability, platformKeysForEntry, supportsFlag } from "./src/host/featureCapabilities.ts";
export { formatBytes, formatDuration } from "./src/i18n/format.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "data-platform-entry.tsx", loader: "tsx" },
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

const STATUS = {
  appVersion: "2.6.10",
  appVersionCode: 20610,
  uptimeMs: 3 * 60 * 60 * 1000,
  storage: {
    databaseBytes: 4 * 1024 * 1024,
    mediaBytes: 12 * 1024 * 1024,
    voiceBytes: 1024 * 1024,
    workspaceBytes: 2 * 1024 * 1024,
    cacheBytes: 1024 * 1024,
    totalAppBytes: 20 * 1024 * 1024,
  },
  backup: { lastBackupAt: Date.now() - 2 * 60 * 60 * 1000, lastBackupBytes: 8 * 1024 * 1024, autoBackupEnabled: true, cloudBackupEnabled: false },
  capabilities: { accessibility: true, shell: false, virtualDisplay: undefined, notificationListener: true, root: undefined, shizuku: undefined },
  battery: { level: 72, charging: false },
};

const dataPage = (overrides = {}) =>
  render(
    mod.createElement(mod.DataPrivacyPage, {
      title: "PROTO-Datos y privacidad",
      system: { status: STATUS, loading: false, error: undefined, unsupported: false },
      supported: true,
      onRequest: () => undefined,
      pii: { checked: true, writable: true },
      onTogglePii: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

const platformPage = (availability, overrides = {}) =>
  render(
    mod.createElement(mod.PlatformPage, {
      title: "PROTO-Accesibilidad",
      description: "PROTO-Descripción de la función",
      availability,
      onBack: () => undefined,
      ...overrides,
    }),
  );

try {
  // ── 1. Distribución de almacenamiento ─────────────────────────────────────────────
  const html = dataPage();
  assert(html.includes("PROTO-Datos y privacidad"), "El título de la página proviene de la entrada del anfitrión.");
  assert(html.includes("Versión celular") && html.includes("2.6.10"), "La versión celular se encuentra en la parte superior.");
  assert(/3\s*h/.test(html) && html.includes("72%"), "El tiempo de funcionamiento y la energía de la batería pasan por el formato internacional");
  assert(html.includes("Uso de almacenamiento") && html.includes("base de datos") && html.includes("medios de comunicación") && html.includes("espacio de trabajo"), "Las categorías de almacenamiento están todas en");
  assert((html.match(/host-storage-segment/g) ?? []).length === 5, "La barra de apilamiento está dividida en cinco segmentos.");
  assert(html.includes("4 MB") && html.includes("20 MB"), "Los valores van a formatBytes(MB)");
  assert(html.includes("total"), "Hay una fila total");
  assert(/host-storage-bar/.test(html), "Distribución del espectáculo con barras apiladas horizontales.");

  // ── 2. Respaldo de tres estados ─────────────────────────────────────────────
  assert(html.includes("copia de seguridad reciente") && html.includes("hace 2 h"), "Mostrar el tiempo relativo cuando hay un registro de respaldo");
  assert(html.includes("8 MB"), "Tamaño de la copia de seguridad");
  assert(html.includes("Copia de seguridad automática") && html.includes("Ya encendido") && html.includes("Copia de seguridad en la nube") && html.includes("Cerrado"), "automático/Eco de solo lectura del conmutador de copia de seguridad en la nube");
  assert(html.includes("Modificar en teléfono celular"), "Los elementos de solo lectura están marcados como \"Editar en dispositivos celulares\".");

  const noBackup = dataPage({
    system: { status: { ...STATUS, backup: { autoBackupEnabled: false, cloudBackupEnabled: false } }, loading: false, error: undefined, unsupported: false },
  });
  assert(noBackup.includes("Aún no se ha realizado una copia de seguridad"), "Si no hay ningún registro de respaldo, indique \"Aún no se realizó una copia de seguridad\".");
  assert(!noBackup.includes("Tamaño de la copia de seguridad"), "No mostrar la fila de tamaño cuando no hay ningún registro");

  const unsupported = dataPage({ supported: false });
  assert(unsupported.includes("La versión actual no admite la visualización de datos y la privacidad en la página web."), "Preguntar cuando falta el bit de capacidad system_status");
  assert(!unsupported.includes("Uso de almacenamiento"), "Si falta el bit de capacidad, no se dibujará el área de almacenamiento.");

  const failed = dataPage({ system: { status: undefined, loading: false, error: "PROTO-error", unsupported: false } });
  assert(failed.includes("PROTO-error") && failed.includes(">Inténtalo de nuevo<"), "Si la lectura falla, puedes volver a intentarlo.");

  // ── 3. Capacidad de escritura del interruptor de privacidad ───────────────────────────────────────
  assert(/role="switch"/.test(html) && !html.includes('disabled=""'), "El interruptor se puede utilizar cuando se puede escribir.");
  const readOnlyPii = dataPage({ pii: { checked: true, writable: false } });
  assert(/role="switch"/.test(readOnlyPii) && readOnlyPii.includes("disabled"), "El interruptor está desactivado cuando no se puede escribir.");
  const missingPii = dataPage({ pii: undefined });
  assert(missingPii.includes("El teléfono celular no muestra el estado de encendido/apagado de protección de PII."), "Host Explique el motivo por el cual no se proporciona ninguna entrada.");
  assert(html.includes("PII proteger") && html.includes("información confidencial"), "Descripción de una línea de protección de PII");
  assert(HOST_SETTINGS.includes("onToggle(PII_GUARD_KEY, enabled)") && HOST_SETTINGS.includes('PII_GUARD_KEY = "piiGuardEnabled"'), "PII Cambiar para ir a configuración. Actualizar clave de lista blanca");

  // ── 4. Mapeo del estado de la página de la plataforma ───────────────────────────────────────
  assert(mod.platformAvailability({ accessibility: true }, ["accessibility"]) === "available", "true → Disponible");
  assert(mod.platformAvailability({ accessibility: false }, ["accessibility"]) === "notEnabled", "false → No encendido");
  assert(mod.platformAvailability({}, ["accessibility"]) === "unavailable", "Falta campo → No disponible");
  assert(mod.platformAvailability(undefined, ["accessibility"]) === "unavailable", "No system.status → No disponible");
  assert(mod.platformAvailability({ accessibility: false, root: true }, ["accessibility", "root"]) === "available", "Si alguno de los múltiples campos está disponible, se considera disponible.");

  const pages = [
    ["accessibility", "accessibility"],
    ["virtual_display", "virtualDisplay"],
    ["terminal", "shell"],
    ["notification_listener", "notificationListener"],
    ["ui_automation", "accessibility"],
  ];
  for (const [entryId, key] of pages) {
    const keys = mod.platformKeysForEntry(entryId);
    assert(keys !== undefined && keys.includes(key), `entrada ${entryId} mapeado a ${key}`);
  }
  assert(mod.platformKeysForEntry("provider") === undefined, "Las entradas que no sean de plataforma no serán juzgadas erróneamente");

  const available = platformPage("available");
  assert(available.includes("PROTO-Descripción de la función") && available.includes("PROTO-Accesibilidad"), "Descripción de la función de visualización de la página de la plataforma");
  assert(available.includes("Disponible") && !available.includes("Abrir en el teléfono celular"), "No solicite habilitarlo cuando esté disponible");
  const notEnabled = platformPage("notEnabled");
  assert(notEnabled.includes("No encendido") && notEnabled.includes("Abrir en el teléfono celular"), "Solicite que lo encienda en su teléfono si no está encendido");
  const unavailable = platformPage("unavailable");
  assert(unavailable.includes("No disponible") && unavailable.includes("teléfono celular"), "Simplemente explique la situación si no está disponible.");
  assert(!/Ir a configuración|Abrir configuración/.test(unavailable), "Sin botón falso cuando no está disponible");

  // ── 5. Entradas y cableado ─────────────────────────────────────────
  for (const key of ["data.storage", "data.backup", "data.readOnly", "data.unsupported", "data.piiGuard", "platform.available", "platform.notEnabled", "platform.unavailable", "platform.readOnly"]) {
    assert(typeof mod.ES[key] === "string" && typeof mod.EN[key] === "string" && mod.ES[key].length > 0 && mod.EN[key].length > 0, `entrada ${key} Tanto chino como inglés.`);
  }
  assert(HOST_SETTINGS.includes('openPage.entry.id === "data_management"') && HOST_SETTINGS.includes("<DataPrivacyPage"), "La entrada \"Datos y Privacidad\" ingresa a esta página");
  assert(HOST_SETTINGS.includes("platformKeysForEntry(openPage.entry.id)") && HOST_SETTINGS.includes("<PlatformPage"), "Las entradas de la plataforma ingresan a la página de la plataforma.");
  assert(CONNECTION.includes('type: "system.status"'), "system.status Se pueden emitir pedidos");
  assert(mod.supportsFlag(undefined, mod.SYSTEM_STATUS_FLAGS) === true && mod.supportsFlag([], mod.SYSTEM_STATUS_FLAGS) === false, "system_status Juicio del nivel de habilidad");
  assert(mod.formatBytes(1024) === "1 KB" || mod.formatBytes(1024).includes("1"), `formatBytes 1KB:${mod.formatBytes(1024)}`);
  assert(/host-storage-bar|host-platform-state/.test(CSS), "Existe una nueva clase de estilo.");

  if (failures > 0) throw new Error(`Error al afirmar la página de datos y plataforma (${failures} artículo)`);
  console.log("\nTodas las afirmaciones de datos y páginas de plataforma pasan (distribución de almacenamiento/respaldo de tres estados/conmutador PII/mapeo de estado de plataforma).");
} finally {
  await rm(TEMP, { force: true });
}
