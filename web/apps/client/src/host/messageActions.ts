import type { MessageKey } from "../i18n/messages.js";

/**
 * Lógica pura del menú de operación de mensajes y operación por lotes.
 *
 * Los elementos del menú están determinados por solo tres cosas: personaje, espacio de habilidad y estado actual de la colección. El propósito de extraer funciones puras es escapar de DOM
 * Afirmar "Falta message_write, solo copiando"tool / system Reglas como "Sólo copiar";
 * Los elementos del menú solo dan**entrada key**, la copia es traducida por el propio componente según el idioma actual (este módulo no toca el idioma).
 */
export type MessageActionId = "copy" | "quote" | "favorite" | "unfavorite" | "delete" | "select";

export interface MessageActionItem {
  readonly id: MessageActionId;
  readonly labelKey: MessageKey;
  /** La eliminación es una operación destructiva y está marcada por separado en el estilo. */
  readonly danger?: boolean;
}

export interface MessageActionInput {
  readonly role: "system" | "user" | "assistant" | "tool";
  readonly hasContent: boolean;
  readonly favorited: boolean;
  readonly capabilities: { readonly messageWrite: boolean; readonly multiSelect: boolean };
}

/**
 * Elementos del menú (el orden es el orden de representación):
 *  - Copia: Si tienes el texto, por favor entrégalo;
 *  - Cita/Favorito (o no favorito)/ Eliminar: se requiere escribir_mensaje;
 *  - Selección múltiple: se requiere message_multi_select;
 *  - role=tool / system: sólo para copiar——Estas dos categorías no son "mensajes de conversación" y eliminarlas o citarlas no tendrá ningún significado.
 */
export function actionsForMessage(input: MessageActionInput): readonly MessageActionItem[] {
  const items: MessageActionItem[] = [];
  if (input.hasContent) items.push({ id: "copy", labelKey: "common.copy" });
  if (input.role === "tool" || input.role === "system") return items;
  if (input.capabilities.messageWrite) {
    items.push({ id: "quote", labelKey: "common.quote" });
    items.push(input.favorited ? { id: "unfavorite", labelKey: "common.unfavorite" } : { id: "favorite", labelKey: "common.favorite" });
  }
  if (input.capabilities.multiSelect) items.push({ id: "select", labelKey: "common.select" });
  if (input.capabilities.messageWrite) items.push({ id: "delete", labelKey: "common.delete", danger: true });
  return items;
}

/** Resumen del contenido citado: aplanar y ajustar, truncar en una línea y la barra sobre el área de entrada no se podrá elevar. */
export function quoteExcerpt(text: string, max = 80): string {
  const flat = text.replace(/\s+/g, " ").trim();
  if (flat.length <= max) return flat;
  return `${flat.slice(0, max)}…`;
}

/** Copia de selección múltiple: solo se deletrean los mensajes con texto, separados por líneas en blanco. */
export function selectionCopyText(messages: readonly { readonly content: string }[]): string {
  return messages
    .map((message) => message.content.trim())
    .filter((content) => content.length > 0)
    .join("\n\n");
}

/** Qué entrada utilizar para la confirmación de eliminación: entrada única/lote. La persona que llama utiliza la copia t() Llevar. */
export function deleteConfirmKey(count: number): MessageKey {
  return count <= 1 ? "message.deleteConfirmOne" : "message.deleteConfirmMany";
}
