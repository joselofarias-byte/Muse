import type { MessageKey } from "../i18n/messages.js";
import type { Translate } from "../i18n/translate.js";

/**
 * Host código de error → entrada.
 *
 * `session.*` El fallo más común de este tipo de comando de escritura es que otro dispositivo le quita el foco (Host volver `focus_not_held`):
 * Si arroja esta cadena de inglés directamente al usuario, él solo pensará que la función no funciona. Los códigos reconocidos se reemplazan por guías de acción,
 * Los que no se conocen se quedan como están.——Host Las razones dadas normalmente ya son legibles y no deben tragarse.
 */
export const HOST_ERROR_KEYS: Readonly<Record<string, MessageKey>> = {
  focus_not_held: "error.focusNotHeld",
  session_not_found: "error.sessionNotFound",
  message_not_found: "error.messageNotFound",
  message_not_in_current_session: "error.messageNotInSession",
  assistant_not_found: "error.assistantNotFound",
};

export function describeHostError(code: string | undefined, fallback: string, t: Translate): string {
  if (code === undefined) return fallback;
  const key = code.trim();
  if (key.length === 0) return fallback;
  const messageKey = HOST_ERROR_KEYS[key];
  return messageKey === undefined ? key : t(messageKey);
}
