import type { HostMessage, HostToolCall } from "../api.js";

/**
 * Determinación de "si es necesario volver a renderizar" los mensajes y fusionar instantáneas.
 *
 * Host Toda la matriz de mensajes se vuelve a enviar en cada cuadro, pero la gran mayoría de los mensajes permanecen sin cambios. Aquí se hacen dos cosas:
 *  1. `areMessagesEqual` Mire sólo los campos que realmente afectan la representación (id / Texto/Estado/Llamada de herramienta/Cotización/Dirección de medios…),
 *     React.memo Úselo como función de comparación;
 *  2. `mergeMessages` Reemplazar mensajes sin cambios cuando llegue la instantánea**Cambiar la referencia del objeto anterior**, de lo contrario, la nota se verá afectada por el
 *     Todos los objetos nuevos no son válidos. Las dos funciones comparten el mismo conjunto de juicios de campo y no habrá ningún tipo de "fusión dice que no hay cambios".memo Dijo que ha cambiado”.
 */

function sameStrings(left: readonly string[] | undefined, right: readonly string[] | undefined): boolean {
  if (left === right) return true;
  const a = left ?? [];
  const b = right ?? [];
  if (a.length !== b.length) return false;
  return a.every((value, index) => value === b[index]);
}

function sameToolCalls(left: readonly HostToolCall[] | undefined, right: readonly HostToolCall[] | undefined): boolean {
  if (left === right) return true;
  const a = left ?? [];
  const b = right ?? [];
  if (a.length !== b.length) return false;
  return a.every((call, index) => {
    const other = b[index];
    if (other === undefined) return false;
    return (
      call.callId === other.callId &&
      call.name === other.name &&
      call.argumentsPreview === other.argumentsPreview &&
      call.resultPreview === other.resultPreview &&
      call.success === other.success &&
      call.durationMs === other.durationMs
    );
  });
}

/** Si los dos mensajes se representan exactamente igual (la misma referencia determinará directamente la igualdad). */
export function areMessagesEqual(prev: HostMessage, next: HostMessage): boolean {
  if (prev === next) return true;
  return (
    prev.id === next.id &&
    prev.role === next.role &&
    prev.content === next.content &&
    prev.reasoning === next.reasoning &&
    prev.status === next.status &&
    prev.errorText === next.errorText &&
    prev.quotedContent === next.quotedContent &&
    prev.favorite === next.favorite &&
    prev.toolName === next.toolName &&
    prev.toolCallId === next.toolCallId &&
    prev.modelId === next.modelId &&
    prev.createdAt === next.createdAt &&
    prev.voiceUrl === next.voiceUrl &&
    prev.voiceDurationMs === next.voiceDurationMs &&
    sameStrings(prev.imageUrls, next.imageUrls) &&
    sameStrings(prev.citationUrls, next.citationUrls) &&
    sameToolCalls(prev.toolCalls, next.toolCalls)
  );
}

/**
 * Combinación de instantáneas: compare elementos uno por uno desde el principio, reutilice objetos antiguos directamente para mensajes sin cambios y use objetos nuevos para mensajes modificados (generalmente el último mensaje de la secuencia).
 *
 * solo en**Mismo prefijo**multiplexación——Siempre que cambie el orden o el número del mensaje, reemplace el mensaje completo con una nueva matriz para evitar referencias que no coincidan con otros mensajes.
 */
export function mergeMessages(
  previous: readonly HostMessage[],
  next: readonly HostMessage[],
): readonly HostMessage[] {
  if (previous.length === 0 || next.length === 0) return next;
  let index = 0;
  while (index < previous.length && index < next.length) {
    const older = previous[index];
    const newer = next[index];
    if (older === undefined || newer === undefined || !areMessagesEqual(older, newer)) break;
    index += 1;
  }
  if (index === 0) return next;
  if (index === next.length && previous.length === next.length) return previous;
  const merged = next.slice();
  for (let position = 0; position < index; position += 1) {
    const older = previous[position];
    if (older !== undefined) merged[position] = older;
  }
  return merged;
}

/** MessageBubble Comparación de notas: si los campos de datos son iguales, se omitirá la nueva representación (se garantiza que las devoluciones de llamada serán estables en la capa superior). */
export interface BubbleCompareProps {
  readonly message: HostMessage;
  readonly streaming: boolean;
  readonly showActions: boolean;
  readonly toolStatus?: string | undefined;
  readonly capabilities: unknown;
  readonly favorited?: boolean | undefined;
  readonly actionsOpen?: boolean | undefined;
  readonly selectionMode?: boolean | undefined;
}

export function areBubblePropsEqual(prev: BubbleCompareProps, next: BubbleCompareProps): boolean {
  return (
    prev.streaming === next.streaming &&
    prev.showActions === next.showActions &&
    prev.toolStatus === next.toolStatus &&
    prev.capabilities === next.capabilities &&
    prev.favorited === next.favorited &&
    prev.actionsOpen === next.actionsOpen &&
    prev.selectionMode === next.selectionMode &&
    areMessagesEqual(prev.message, next.message)
  );
}
