import type { HostMessage } from "../api.js";
import type { MessageKey } from "../i18n/messages.js";
import { DEFAULT_LOCALE, type Locale } from "../i18n/locale.js";
import { dayKey, formatDaySeparator } from "./format.js";

/**
 * Lista de representación del flujo de mensajes: inserte una separación de tiempo delante de los mensajes transfronterizos por día.
 *
 * Reglas (alineadas con el DateSeparator de la aplicación, más tres restricciones en la página web):
 *  - Las barras separadoras se insertan por día: dos mensajes adyacentes se interrumpen sólo si pertenecen a días calendario diferentes;
 *  - Los mensajes continuos dentro del mismo minuto nunca serán interrumpidos repetidamente.——El mismo minuto debe ser el mismo día, y el agrupamiento por días lo ha garantizado;
 *    Cuando el texto de la copia es el mismo que el separador anterior, ya no se insertará repetidamente y el desorden de la marca de tiempo/datos degenerados duplicados no estampará la lista en una barra separadora;
 *  - Los mensajes con marcas de tiempo faltantes o ilegales no participarán en el agrupamiento y solo se procesarán como de costumbre sin generar separadores;
 *  - Agregue un elemento más al principio de la lista: no hay una fecha de encabezado de sesión de la aplicación en la que confiar en la página web. Al abrir una sesión histórica
 *    Debes saber a primera vista de qué fecha proviene esta tanda de noticias.
 *
 * key La unicidad se maneja por separado:Host La identificación es un UUID que, en teoría, no se repite en la instantánea, pero puede colisionar después de paginar y fusionar.
 * El número de secuencia se complementa cuando se repite para evitar que React reutilice el nodo incorrecto.
 */

export type StreamItem =
  | { readonly kind: "divider"; readonly key: string; readonly label: string }
  | { readonly kind: "message"; readonly key: string; readonly message: HostMessage; readonly index: number };

export function buildStreamItems(
  messages: readonly HostMessage[],
  now: number = Date.now(),
  locale: Locale = DEFAULT_LOCALE,
): readonly StreamItem[] {
  const items: StreamItem[] = [];
  const usedKeys = new Set<string>();
  let ordinal = 0;
  let previousDay: string | undefined;
  let lastLabel: string | undefined;

  for (const message of messages) {
    const day = dayKey(message.createdAt);
    if (day !== undefined && previousDay !== undefined && day !== previousDay) {
      const label = formatDaySeparator(message.createdAt, now, locale);
      if (label.length > 0 && label !== lastLabel) {
        lastLabel = label;
        items.push({ kind: "divider", key: `divider-${day}-${ordinal}`, label });
      }
    }
    if (day !== undefined) previousDay = day;

    const base = message.id.length > 0 ? message.id : `message-${ordinal}`;
    let key = base;
    for (let index = 2; usedKeys.has(key); index += 1) key = `${base}#${index}`;
    usedKeys.add(key);
    items.push({ kind: "message", key, message, index: ordinal });
    ordinal += 1;
  }

  const first = items[0];
  if (first !== undefined && first.kind === "message") {
    const topDay = dayKey(first.message.createdAt);
    const topLabel = topDay === undefined ? "" : formatDaySeparator(first.message.createdAt, now, locale);
    if (topLabel.length > 0) items.unshift({ kind: "divider", key: `divider-top-${topDay ?? "top"}`, label: topLabel });
  }
  return items;
}

/**
 * El estado del bloque de llamada de herramienta. No hay ningún campo de estado a nivel de mensaje en el protocolo. Aquí solo utilizamos los campos existentes para inferir y no hacemos conjeturas infundadas:
 * Hay el mismo en la lista de aprobación. toolCallId → Pendiente de confirmación; toda la conversación todavía se está generando y es la última entrada en esta conversación → En ejecución;
 * De lo contrario role=tool significa que se ha completado y se ha iniciado la llamada iniciada por el asistente (el resultado aún no se ha devuelto o está doblado en el texto).
 */
export type ToolStatus = "awaiting" | "running" | "done" | "requested";

export function toolStatusOf(
  message: HostMessage,
  context: { readonly isStreaming: boolean; readonly isLast: boolean; readonly approvalToolCallIds: ReadonlySet<string> },
): ToolStatus {
  if (message.toolCallId !== undefined && context.approvalToolCallIds.has(message.toolCallId)) return "awaiting";
  if (context.isStreaming && context.isLast) return "running";
  if (message.role === "tool") return "done";
  return "requested";
}

export const TOOL_STATUS_KEY: Readonly<Record<ToolStatus, MessageKey>> = {
  awaiting: "tool.status.awaiting",
  running: "tool.status.running",
  done: "tool.status.done",
  requested: "tool.status.requested",
};
