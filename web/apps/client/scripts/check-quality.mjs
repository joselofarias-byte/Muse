/**
 * Afirmaciones estáticas para calidad y tolerancia a fallas: desconexión triestado, retraso de esqueleto, límites de error de bloqueo, páginas de marcador de posición, accesibilidad, contraste.
 *
 * La misma rutina que otros scripts: las funciones puras se ejecutan directamente y los componentes se representan mediante renderToStaticMarkup.
 * Cosas de cableado (límites de error, trampas de enfoque,aria-label) afirmar según la estructura del código fuente —— Sin navegador,
 * Tampoco cites jsdom. Si puedes afirmarlo, déjalo claro. Si no puede afirmarlo, déjelo en manos de la verificación manual.
 *
 * Uso:npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync, readdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".quality-check.mjs");
const TOKENS = JSON.parse(readFileSync(join(ROOT, "src", "design", "tokens.json"), "utf8"));
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const ENTRY = `
export { connectionBanner, composerDisabledHint, MANUAL_RETRY_AFTER } from "./src/host/connectionStatus.ts";
export { isSkeletonDue, SKELETON_DELAY_MS } from "./src/host/useDelayedFlag.ts";
export { trapNextIndex } from "./src/host/ImageLightbox.tsx";
export { HostPlaceholder } from "./src/host/HostPlaceholder.tsx";
export { MessageBubble } from "./src/host/MessageBubble.tsx";
export { readCapabilities } from "./src/host/capabilities.ts";
export { readLanguage } from "./src/host/useLocale.ts";
export { I18nProvider } from "./src/i18n/I18nProvider.tsx";
export { translate } from "./src/i18n/translate.ts";
export { ES, EN, MESSAGES } from "./src/i18n/messages.ts";
export { resolveLocale, LOCALES, FALLBACK_LOCALE, DEFAULT_LOCALE } from "./src/i18n/locale.ts";
export { formatRelativeTime, formatNumber, formatMonthDay, formatDayLabel } from "./src/i18n/format.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "quality-entry.tsx", loader: "tsx" },
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

// ── WCAG Contraste (calculalo tu mismo sin utilizar herramientas) ────────────────────────────────
function parseColor(value) {
  const text = String(value).trim();
  const hex = text.match(/^#([0-9a-fA-F]{6})$/);
  if (hex !== null) {
    const n = Number.parseInt(hex[1], 16);
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 };
  }
  const rgba = text.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/);
  if (rgba !== null) {
    return { r: Number(rgba[1]), g: Number(rgba[2]), b: Number(rgba[3]), a: rgba[4] === undefined ? 1 : Number(rgba[4]) };
  }
  throw new Error(`No se puede analizar el color:${text}`);
}

function over(fg, bg) {
  const alpha = fg.a ?? 1;
  return {
    r: fg.r * alpha + bg.r * (1 - alpha),
    g: fg.g * alpha + bg.g * (1 - alpha),
    b: fg.b * alpha + bg.b * (1 - alpha),
    a: 1,
  };
}

function luminance(color) {
  const channel = (value) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
}

function contrast(fgToken, bgToken) {
  const bg = parseColor(bgToken);
  const fg = over(parseColor(fgToken), bg);
  const l1 = luminance(fg);
  const l2 = luminance(bg);
  const [light, dark] = l1 >= l2 ? [l1, l2] : [l2, l1];
  return (light + 0.05) / (dark + 0.05);
}

const mod = await load();

try {
  // ── 1. Descripción de desconexión triestado y deshabilitación de entradas ───────────────────────────────────
  // La lógica pura sólo da "estado → "Clave de término + parámetro", la copia la resuelve el componente de acuerdo con el idioma actual;
  // Entonces, aquí se afirman dos cosas: si la clave asignada es correcta y si la copia zh de la clave sigue siendo la oración original.
  const zh = (key, params) => mod.translate("es", key, params);
  const offline = mod.connectionBanner({ online: false, status: "reconnecting", attempt: 2, everConnected: true });
  assert(offline.visible && offline.textKey === "connection.offline", `Cuando esté desconectado, vaya a la entrada \"La red está desconectada\":${offline.textKey}`);
  assert(zh(offline.textKey) === "La red está desconectada", `Cuando está desconectado, el texto después de la resolución es \"La red ha sido desconectada\":${zh(offline.textKey)}`);
  assert(!offline.retry, "No se permiten reintentos manuales cuando se está desconectado (no se puede conectar incluso si se vuelve a intentar)");

  const online1 = mod.connectionBanner({ online: true, status: "connected", attempt: 0, everConnected: true });
  assert(!online1.visible, "Después de conectar, la línea discontinua desaparece.");

  const first = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 0, everConnected: false });
  assert(first.visible && first.textKey === "connection.connecting", `Para la primera conexión, utilice conexión.conexión:${first.textKey}`);
  assert(zh(first.textKey) === "Conectando con el teléfono…", `Primera copia de conexión:${zh(first.textKey)}`);
  assert(!first.retry, "No se permitirán reintentos manuales para las 2 primeras reconexiones.");

  const tries2 = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 2, everConnected: true });
  assert(tries2.textKey === "connection.reconnecting", `Reconexión.reconexión:${tries2.textKey}`);
  assert(zh(tries2.textKey, tries2.params) === "Reconectando (intento 2)", `Número de veces que se vuelve a vincular la redacción publicitaria:${zh(tries2.textKey, tries2.params)}`);
  assert(!tries2.retry, "Aún no hay reintento manual por segunda vez");

  const tries3 = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 3, everConnected: true });
  assert(zh(tries3.textKey, tries3.params) === "Reconectando (intento 3)" && tries3.retry, "El reintento manual se produce después de 3 reconexiones.");

  const closed = mod.connectionBanner({ online: true, status: "closed", attempt: 3, everConnected: true });
  assert(closed.textKey === "connection.disconnected", `Desconectar conexión.desconectado:${closed.textKey}`);
  assert(zh(closed.textKey) === "Desconectado del teléfono" && closed.retry, `Desconectar copia:${zh(closed.textKey)}`);
  assert(mod.MANUAL_RETRY_AFTER === 3, "El umbral de reintento manual es 3 veces");

  const hintText = (key) => (key === undefined ? "" : zh(key));
  const hintOffline = mod.composerDisabledHint({ online: false, status: "connected" });
  assert(hintOffline === "composer.hintOffline", `Cuando está sin conexión, la descripción del cuadro de entrada va a compositor.hintOffline:${hintOffline}`);
  assert(hintText(hintOffline).includes("sin conexión"), `Cuando está desconectado, la descripción del cuadro de entrada es \"sin conexión\":${hintText(hintOffline)}`);
  const hintReconnecting = mod.composerDisabledHint({ online: true, status: "reconnecting" });
  assert(hintReconnecting === "composer.hintReconnecting", `En el cuadro de entrada de reconexión, vaya a compositor.hintReconexión:${hintReconnecting}`);
  assert(hintText(hintReconnecting).includes("Reconectando"), `La descripción del cuadro de entrada durante la reconexión:${hintText(hintReconnecting)}`);
  const hintClosed = mod.composerDisabledHint({ online: true, status: "closed" });
  assert(hintClosed === "composer.hintClosed", `Después de desconectarse, la descripción del cuadro de entrada va a compositor.hintClosed:${hintClosed}`);
  assert(hintText(hintClosed).includes("conexión"), `Después de la desconexión, la descripción del cuadro de entrada es \"conexión\":${hintText(hintClosed)}`);
  assert(mod.composerDisabledHint({ online: true, status: "connected" }) === undefined, "El cuadro de entrada está disponible cuando la conexión es normal.");

  // ── 2. retraso del esqueleto 300ms ──────────────────────────────────────────
  assert(mod.SKELETON_DELAY_MS === 300, "La constante de retardo del esqueleto es 300ms");
  assert(!mod.isSkeletonDue(false, 10_000), "El esqueleto no debería salir si no está cargando.");
  assert(!mod.isSkeletonDue(true, 120), "120ms No hay esqueleto en el interior (evite flashear)");
  assert(mod.isSkeletonDue(true, 300), "Se necesitan 300 ms para completar el esqueleto.");
  for (const file of ["HostChat.tsx", "HostSettings.tsx", "SettingsValuePage.tsx", "SessionDrawer.tsx"]) {
    const source = readFileSync(join(ROOT, "src", "host", file), "utf8");
    assert(source.includes("useDelayedFlag("), `${file} El esqueleto va con el retraso useDelayedFlag`);
  }
  assert(/\.host-loading-quiet\b/.test(CSS), "Durante el período de retraso, se proporciona un espacio de silencio (no un espacio en blanco ni un esqueleto)");

  // ── 3. Límite de error de bloque ────────────────────────────────────────────
  const boundary = readFileSync(join(ROOT, "src", "host", "BlockBoundary.tsx"), "utf8");
  assert(boundary.includes("getDerivedStateFromError") && boundary.includes("componentDidCatch"), "Los límites de los bloques implementan dos ganchos de error");
  assert(boundary.includes('t("block.retry")'), "Los límites del bloque usan la entrada block.retry");
  assert(/<Fragment key=\{this\.state\.nonce\}>/.test(boundary), "recargar = Cambiar la clave y volver a montar esta pieza.");
  assert(/role="alert"/.test(boundary), "Los errores de bloque tienen semántica de alerta");
  const chat = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  // El nombre del bloque también es una entrada: al afirmar "qué bloque cubre el límite", miramos la clave que toma y luego verificamos que la copia de la clave no haya cambiado.
  for (const [key, copy] of [["block.messages", "La lista de mensajes"], ["block.sessions", "La lista de conversaciones"], ["block.settings", "La página de ajustes"]]) {
    assert(mod.ES[key] === copy, `entrada ${key} es \"${copy}\"`);
    const wrapped = new RegExp(`<BlockBoundary name=\\{t\\("${key.replace(/\./g, "\\.")}"\\)\\}`);
    assert(wrapped.test(chat), `「${copy}\"Se envuelve una capa separada de límites de bloque (el nombre sigue a la entrada)`);
  }
  assert(chat.includes("<MessageErrorBoundary"), "El aislamiento mensaje por mensaje permanece (los límites de bloque no lo reemplazan)");

  // Rol desconocido/forma de archivo adjunto desconocido: visualización neutral en una línea, no se genera ninguna excepción
  const full = mod.readCapabilities(["media", "tool_call_details", "message_status", "message_write", "message_multi_select"]);
  const renderMessage = (overrides) =>
    mod.renderToStaticMarkup(
      mod.createElement(mod.MessageBubble, {
        message: { id: "m1", role: "assistant", content: "Texto", createdAt: Date.now(), ...overrides },
        streaming: false,
        showActions: false,
        onRegenerate: () => undefined,
        onContinue: () => undefined,
        capabilities: full,
      }),
    );
  const unknownRole = renderMessage({ role: "moderator" });
  assert(unknownRole.includes("host-msg-unknown") && unknownRole.includes("Tipo de mensaje desconocido"), "Rol desconocido degradado a una visualización neutral en una línea");
  assert(unknownRole.includes("Texto"), "La función desconocida aún muestra el texto (sin perder contenido)");
  assert(renderMessage({ imageUrls: ["weird://x"] }).includes("Este archivo adjunto no se puede abrir en la página web."), "El formulario de archivo adjunto desconocido da una pista neutral");
  assert(renderMessage({ imageUrls: ["file:///data/user/0/x.bin"] }).includes("en el teléfono"), "La ruta local de los datos antiguos proporciona las indicaciones correspondientes.");

  // ── 4. Página de marcador de posición unificada ─────────────────────────────────────────────
  const placeholder = mod.renderToStaticMarkup(
    mod.createElement(mod.HostPlaceholder, { title: "PROTO-Accesibilidad", description: "PROTO-¿Qué hace esta función?", onBack: () => undefined }),
  );
  assert(placeholder.includes("Esto se hace en el teléfono"), "Las instrucciones de la página del marcador de posición deben operarse en el teléfono.");
  assert(placeholder.includes("PROTO-¿Qué hace esta función?"), "La página de marcador de posición utiliza instrucciones emitidas por el Anfitrión para explicar la función.");
  assert(placeholder.includes(">Volver<"), "La página del marcador de posición tiene un botón de retorno.");
  assert(!/muse-capsule[^"]*is-primary/.test(placeholder), "La página del marcador de posición no contiene la operación principal (pretendiendo tener algo que hacer)");
  const placeholderNoDesc = mod.renderToStaticMarkup(
    mod.createElement(mod.HostPlaceholder, { title: "PROTO-terminales", onBack: () => undefined }),
  );
  assert(placeholderNoDesc.includes("Muse para el teléfono"), "Si no hay explicación, dé una palabra neutral, no un espacio en blanco.");
  const settingsSource = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(
    settingsSource.includes("<HostPlaceholder") && !settingsSource.includes("Esta página aún se está migrando."),
    "Las subpáginas de configuración no implementadas y las páginas específicas de la plataforma están unificadas HostPlaceholder",
  );

  // ── 5. Accesibilidad: botón icono aria-label ───────────────────────────
  const iconClasses = ["host-icon-button", "host-lightbox-close", "host-select", "host-session-more", "host-sidebar-collapse", "host-quote-draft-close", "host-sidebar-toggle"];
  // Coincidencia por límite de token de nombre de clase:host-select La selección de host todo no se puede contar como un botón de icono.
  const hasIconClass = (tag) => iconClasses.some((cls) => new RegExp(`(^|[\\s"'])${cls}([\\s"']|$)`).test(tag));
  const hostDir = join(ROOT, "src", "host");
  const offenders = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx"))) {
    const source = readFileSync(join(hostDir, file), "utf8");
    for (const match of source.matchAll(/<button\b[\s\S]{0,400}?>/g)) {
      const tag = match[0];
      if (!hasIconClass(tag)) continue;
      if (!tag.includes("aria-label")) offenders.push(`${file}: ${tag.split("\n")[0]}`);
    }
  }
  assert(offenders.length === 0, `Hay botones de iconos aria-label${offenders.length === 0 ? "" : `(Falta:${offenders.join(" / ")})`}`);
  assert(
    chat.includes('aria-label={t("chat.exitSelection")}') && chat.includes('aria-label={t("chat.cancelQuote")}'),
    "El botón de icono en la barra de referencia y selección múltiple es aria-label (ir a la entrada)",
  );
  assert(
    mod.ES["chat.exitSelection"] === "Salir de la selección" && mod.ES["chat.cancelQuote"] === "Quitar cita",
    "Salir de selección múltiple/cancelar la copia de la entrada citada aún",
  );

  // ── 6. trampa de enfoque vs. Esc ─────────────────────────────────────────
  assert(mod.trapNextIndex(3, 2, false) === 0, "Tab Devuelve el último control al primero.");
  assert(mod.trapNextIndex(3, 0, true) === 2, "Shift+Tab Da la vuelta al primer control hasta llegar al último.");
  assert(mod.trapNextIndex(3, 1, false) === 2, "Las posiciones intermedias avanzan en orden natural");
  assert(mod.trapNextIndex(0, -1, false) === -1, "No hacer nada cuando no haya controles enfocables");
  const lightbox = readFileSync(join(ROOT, "src", "host", "ImageLightbox.tsx"), "utf8");
  assert(lightbox.includes('event.key === "Escape"'), "El cuadro de diálogo admite Esc para cerrar");
  assert(/previous\?\.focus\(\)/.test(lightbox), "Devolver el foco al elemento original después de cerrar");
  assert(/closeRef\.current\?\.focus\(\)/.test(lightbox) && lightbox.includes("querySelectorAll"), "Mueva el foco al cuadro de diálogo y haga un bucle cuando se abra Tab");
  assert(/aria-modal="true"/.test(lightbox) && /role="dialog"/.test(lightbox), "La semántica del diálogo está completa.");
  assert(/\.host-scope :focus-visible/.test(CSS) && /outline: var\(--muse-icons-progress-stroke\) solid var\(--accent\)/.test(CSS), "El foco del teclado está delineado con el color principal (sin contorno: ninguno)");

  // ── 7. Contraste: el texto principal y el texto secundario son mejores en ambos temas. AA ────────────────
  assert(/--ink-muted:\s*var\(--ui-label-ink\)/.test(CSS), "El alias de texto secundario se cambia a onSurface@80% (ya no se usa el contorno gris medio)");
  for (const mode of ["light", "dark"]) {
    const scheme = TOKENS.colors.scheme[mode];
    const pairs = [
      ["Texto ink", scheme.onSurface, scheme.surface],
      ["Tinta de texto (variante de tarjeta en la parte inferior)", scheme.onSurface, scheme.surfaceVariant],
      ["Secundaria ink-soft", scheme.onSurfaceVariant, scheme.surface],
      ["Secundaria ink-muted", scheme.onSurfaceVeil80, scheme.surface],
      ["Tinta menor silenciada (base de variante de tarjeta)", scheme.onSurfaceVeil80, scheme.surfaceVariant],
    ];
    for (const [label, fg, bg] of pairs) {
      const ratio = contrast(fg, bg);
      assert(ratio >= 4.5, `${mode} · ${label} Contraste ${ratio.toFixed(2)}:1 ≥ 4.5`);
    }
  }

  // ── 8. Análisis de lenguaje/integridad de entrada/localización de tiempo y números ────────────────
  // El valor del idioma proviene del elemento que contiene el idioma en settings.snapshot.values;Host Volver al idioma de visualización cuando no se entregue.
  const settingsWith = (values) => ({
    snapshot: {
      groups: [],
      toggles: [],
      writableKeys: [],
      display: { language: "en", themeMode: "system", fontSizeScale: "1", themeId: "default" },
      ...(values === undefined ? {} : { values }),
    },
    loading: false,
  });
  const languageValue = (key, value) => [{ key, type: "enum", value }];
  assert(mod.readLanguage(settingsWith(languageValue("language", "ja"))) === "ja", "El idioma se toma del elemento de idioma de settings.snapshot.values");
  assert(mod.readLanguage(settingsWith(languageValue("display.language", "ko"))) === "ko", "También se reconoce el prefijo de la banda clave (normalizeSettingKey normalizado)");
  assert(mod.readLanguage(settingsWith(undefined)) === "en", "Host Devuelto cuando los valores no se envían display.language");
  assert(mod.readLanguage({ loading: true }) === undefined, "No hay valor de idioma cuando la configuración aún no está disponible");

  assert(mod.resolveLocale("ja", ["zh-CN"]) === "ja", "Si el idioma está especificado en la configuración, haga clic en él sin mirar el navegador.");
  assert(mod.resolveLocale("system", ["ja-JP", "en-US"]) === "ja", "system Haga coincidir uno por uno según la lista de idiomas del navegador");
  assert(mod.resolveLocale(undefined, ["en-GB"]) === "en", "Presione el idioma del navegador cuando no haya ningún valor de idioma");
  assert(mod.FALLBACK_LOCALE === "es" && mod.resolveLocale("system", []) === "es", "Idioma alternativo no reconocido zh (consistente con la versión celular)");
  assert(mod.resolveLocale("fr", ["fr-FR"]) === "es", "Host Cuando le doy un valor a un idioma que no reconozco, también es zh.");
  assert(mod.LOCALES.join(",") === "es,en,ja,ko,ru", "idiomas: es / en / ja / ko / ru");

  const zhKeys = Object.keys(mod.ES);
  const enKeys = Object.keys(mod.EN);
  assert(zhKeys.length > 0 && enKeys.length === zhKeys.length && zhKeys.every((key) => enKeys.includes(key)), `zh / en Las entradas corresponden una a una (${zhKeys.length} artículo)`);
  assert(zhKeys.every((key) => mod.ES[key].length > 0 && (mod.EN[key] ?? "").length > 0), "No hay entradas vacías");
  const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
  assert(zhKeys.every((key) => slots(mod.ES[key]) === slots(mod.EN[key] ?? "")), "zh / en Los marcadores de posición son consistentes con");
  // ja / ko / ru Todos son términos completos y cada uno utiliza el suyo propio; sólo si falta una sola entrada, volverá a en.
  for (const locale of ["ja", "ko", "ru"]) {
    const dict = mod.MESSAGES[locale] ?? {};
    assert(
      zhKeys.every((key) => typeof dict[key] === "string" && dict[key].length > 0),
      `${locale} Consistente con el conjunto de claves zh y sin entradas vacías`,
    );
    assert(zhKeys.every((key) => slots(dict[key] ?? "") === slots(mod.ES[key])), `${locale} El marcador de posición para es consistente con zh`);
    assert(mod.translate(locale, "connection.offline") === dict["connection.offline"], `${locale} Utilice sus propios términos en lugar de en`);
    assert(dict["connection.offline"] !== mod.EN["connection.offline"], `${locale} \"No hay red\" de hecho está traducido`);
  }
  assert(mod.translate("en", "connection.reconnecting", { attempt: 4 }).includes("4"), "Los parámetros también se reemplazan en en");

  // Cambie la redacción cada vez que cambie el valor del idioma: si el mismo árbol está configurado en diferentes configuraciones regionales, los resultados de la representación cambiarán en consecuencia, sin actualizarse.
  const placeholderFor = (locale) =>
    mod.renderToStaticMarkup(
      mod.createElement(mod.I18nProvider, { locale }, mod.createElement(mod.HostPlaceholder, { title: "PROTO-T", onBack: () => undefined })),
    );
  assert(placeholderFor("es").includes("Esto se hace en el teléfono"), "es muestra español");
  assert(placeholderFor("en").includes("This needs to be done on your phone"), "en renderizar ingles");
  assert(placeholderFor("ja").includes("この機能はスマートフォンでの操作が必要です"), "ja muestra japonés");
  assert(placeholderFor("ru").includes("Для этой функции нужен телефон"), "ru renderizar ruso");
  assert(!placeholderFor("ru").includes("settings.placeholderTitle"), "El nombre de la clave nunca quedará expuesto cuando falte la entrada.");
  assert(chat.includes("<I18nProvider locale={locale}>") && chat.includes("useHostLocale(settings)"), "El shell de chat aplica el lenguaje analizado a todo el árbol.");
  assert(chat.includes("t(bannerModel.textKey, bannerModel.params)"), "La barra de aviso de desconexión resuelve las entradas según el idioma actual.");

  // formatRelativeTime(date, now, locale): función pura,zh / en Pasa por cada rama.
  const now = new Date(2026, 9, 31, 12, 0, 0).getTime(); // Zona horaria local, evite la zona horaria y muévase "ayer"
  const ago = (minutes) => now - minutes * 60_000;
  const rel = (minutes, locale) => mod.formatRelativeTime(ago(minutes), now, locale);
  assert(rel(0.1, "es") === "justo ahora" && rel(0.1, "en") === "Just now", "Recién ramificado zh / en");
  assert(rel(5, "es") === "hace 5 min" && rel(5, "en") === "5 min ago", "minutos es / en");
  assert(rel(180, "es") === "hace 3 h" && rel(180, "en") === "3 h ago", "horas es / en");
  assert(rel(26 * 60, "es") === "ayer" && rel(26 * 60, "en") === "Yesterday", "ayer es / en");
  assert(rel(3 * 24 * 60, "es") === "hace 3 días" && rel(3 * 24 * 60, "en") === "3 days ago", "días es / en");
  assert(rel(30 * 24 * 60, "es") !== rel(30 * 24 * 60, "en"), "Fecha proporcionada durante más de una semana, formateada por idioma");
  assert(mod.formatRelativeTime(undefined, now, "es") === "", "Si falta la marca de tiempo, se proporciona una cadena vacía y el marcador de posición se proporciona a la persona que llama.");

  // Formato de fecha/número Internacional: el formato es diferente en zh/en al mismo tiempo y no es una cadena escrita por usted mismo.
  assert(/[A-Za-zÁÉÍÓÚáéíóú]/.test(mod.formatMonthDay(now, "es")), `es fecha Intl:${mod.formatMonthDay(now, "es")}`);
  assert(/[A-Za-z]/.test(mod.formatMonthDay(now, "en")), `en Mes y día van Internacional:${mod.formatMonthDay(now, "en")}`);
  assert(mod.formatDayLabel(now, now, "es").startsWith("hoy"), "La tira separadora de hoy \"Hoy\"");
  assert(mod.formatNumber(1234567, "en") === "1,234,567", `en Milésimas:${mod.formatNumber(1234567, "en")}`);
  assert(mod.formatNumber(1234567, "ru") !== mod.formatNumber(1234567, "en"), "Diferentes idiomas tienen diferentes símbolos de agrupación (ru No utilice espacios de nueva línea)");
  assert(chat.includes('t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })'), "El número seleccionado está en formato de número internacional.");

  // ── 9. Escaneo codificado:src/host/*.tsx No debe haber ningún carácter chino visible en el ──────
  // El escáner omite los comentarios (incluido el chino en los comentarios JSX); la lista blanca son "excepciones reconocidas" y se escribe el motivo de cada entrada.
  const WHITELIST = [
    {
      file: "SessionDrawer.tsx",
      line: '<span className="host-session-preview">{previews[session.id] ?? "　"}</span>',
      reason: "Ocupación de espacio de ancho completo cuando la vista previa de la sesión está vacía: solo para admitir la altura de la línea y no para leer la copia de la pantalla",
    },
  ];
  const CJK = /[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]/;
  /** Revíselo carácter por carácter y solo reconozca el chino en "Código y cadenas": el chino en los comentarios (incluidos los comentarios JSX) no cuenta. */
  const cjkHits = (source) => {
    const hits = [];
    const record = (index, lineNo) => {
      const start = source.lastIndexOf("\n", index) + 1;
      const end = source.indexOf("\n", index);
      hits.push({ lineNo, line: source.slice(start, end === -1 ? source.length : end).trim() });
    };
    let state = "code";
    let quote = "";
    let lineNo = 1;
    for (let index = 0; index < source.length; index += 1) {
      const ch = source[index];
      const next = source[index + 1];
      if (ch === "\n") lineNo += 1;
      if (state === "line") {
        if (ch === "\n") state = "code";
        continue;
      }
      if (state === "block") {
        if (ch === "*" && next === "/") {
          state = "code";
          index += 1;
        }
        continue;
      }
      if (state === "string") {
        if (ch === "\\") {
          index += 1;
          continue;
        }
        if (ch === quote) {
          state = "code";
          continue;
        }
        if (CJK.test(ch)) record(index, lineNo);
        continue;
      }
      if (ch === "/" && next === "/") {
        state = "line";
        index += 1;
        continue;
      }
      if (ch === "/" && next === "*") {
        state = "block";
        index += 1;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === "`") {
        state = "string";
        quote = ch;
        continue;
      }
      if (CJK.test(ch)) record(index, lineNo);
    }
    return hits;
  };
  const allowed = new Set();
  const leftovers = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx")).sort()) {
    for (const hit of cjkHits(readFileSync(join(hostDir, file), "utf8"))) {
      const match = WHITELIST.findIndex((entry) => entry.file === file && entry.line === hit.line);
      if (match >= 0) {
        allowed.add(match);
        continue;
      }
      leftovers.push(`${file}:${hit.lineNo}`);
    }
  }
  assert(leftovers.length === 0, `host/*.tsx No hay ningún chino visible que no haya sido ingresado.${leftovers.length === 0 ? "" : `(${leftovers.join(" / ")})`}`);
  assert(allowed.size === WHITELIST.length, `La lista blanca no tiene entradas caducadas (presione ${allowed.size}/${WHITELIST.length})`);
  for (const entry of WHITELIST) assert(entry.reason.length > 0, `La entrada de la lista blanca indica el motivo:${entry.file}`);

  if (failures > 0) throw new Error(`Error en la afirmación de calidad (${failures} artículo)`);
  console.log("\nSe aprobaron todas las afirmaciones de calidad (desconexión de tres estados/retraso del esqueleto/límites de bloque/páginas de marcador de posición/accesibilidad/contraste/idioma y términos).");
} finally {
  await rm(TEMP, { force: true });
}
