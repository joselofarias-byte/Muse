/**
 * Negociación de capacidad para nuevos comandos.
 *
 * Host El nombre de los bits de habilidad aún no se ha finalizado y aquí se aceptan múltiples métodos de escritura para la misma habilidad (`assistants_detail` /
 * `assistant_detail`);**Los hosts antiguos que no hayan emitido indicadores de capacidad siempre serán devueltos. true**——Intente emitir el comando primero.
 * Si realmente no es compatible, el anfitrión responderá `error`, la página cambia al mensaje "La versión actual aún no es compatible".
 */
import type { HostSystemCapabilities } from "../api.js";

export const ASSISTANT_DETAIL_FLAGS: readonly string[] = ["assistants_detail", "assistant_detail"];
export const MEMORY_FLAGS: readonly string[] = ["memory", "memory_list"];
export const KNOWLEDGE_FLAGS: readonly string[] = ["knowledge", "knowledge_base"];
export const TASK_FLAGS: readonly string[] = ["tasks", "scheduled_tasks"];
export const SYSTEM_STATUS_FLAGS: readonly string[] = ["system_status"];
export const ASSISTANT_WRITE_FLAGS: readonly string[] = ["assistant_write", "assistants_write"];

export function supportsFlag(flags: readonly string[] | undefined, tokens: readonly string[]): boolean {
  if (flags === undefined) return true;
  return tokens.some((token) => flags.includes(token));
}

/** Host Texto de error al rechazar un comando que no implementa. Aquí hay algunas formas comunes de escribirlo. */
export function isUnsupportedCommandError(message: string | undefined): boolean {
  if (message === undefined) return false;
  return /unsupported|unknown command|not implemented|not supported/i.test(message);
}

/** Tres estados de capacidades específicas de la plataforma. */
export type PlatformAvailability = "available" | "notEnabled" | "unavailable";

/**
 * Envíe el estado de capacidad de la plataforma desde system.status.capabilities:
 * true → Disponible;false → No habilitado (el dispositivo lo admite pero no está habilitado); falta el campo → No disponible.
 * Las capacidades de múltiples campos (como la automatización de la interfaz de usuario) están disponibles tan pronto como estén disponibles.
 */
export function platformAvailability(
  capabilities: HostSystemCapabilities | undefined,
  keys: readonly (keyof HostSystemCapabilities)[],
): PlatformAvailability {
  if (capabilities === undefined) return "unavailable";
  const values = keys.map((key) => capabilities[key]);
  if (values.some((value) => value === true)) return "available";
  if (values.some((value) => value === false)) return "notEnabled";
  return "unavailable";
}

/**
 * entrada de directorio → Capacidades de plataforma dependiente.
 *
 * Host La identificación de la entrada lateral aún no se ha finalizado por completo y aquí se toleran guiones bajos, guiones y varios sinónimos;
 * Si no hay ninguna coincidencia, no ingresará a la página de la plataforma (continúe a la página de marcador de posición general).
 */
const PLATFORM_ENTRY_KEYS: Readonly<Record<string, readonly (keyof HostSystemCapabilities)[]>> = {
  accessibility: ["accessibility"],
  accessibility_service: ["accessibility"],
  virtual_display: ["virtualDisplay"],
  virtualdisplay: ["virtualDisplay"],
  terminal: ["shell"],
  shell: ["shell"],
  notification_listener: ["notificationListener"],
  notificationlistener: ["notificationListener"],
  ui_automation: ["accessibility", "shizuku", "root"],
  uiautomation: ["accessibility", "shizuku", "root"],
};

export function platformKeysForEntry(entryId: string): readonly (keyof HostSystemCapabilities)[] | undefined {
  const normalized = entryId.trim().toLowerCase().replace(/[\s-]+/g, "_");
  return PLATFORM_ENTRY_KEYS[normalized] ?? PLATFORM_ENTRY_KEYS[normalized.replace(/_/g, "")];
}
