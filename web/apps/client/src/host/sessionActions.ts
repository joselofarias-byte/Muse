/**
 * Lógica pura de las operaciones de la sesión: verificación de cambio de nombre, copia de confirmación de eliminación, visibilidad del archivo, si se debe seleccionar un asistente primero al crear una nueva sesión.
 *
 * Se extrae para poder hacer valer estas reglas sin DOM (especialmente "no se permiten nombres vacíos" y eliminar la redacción publicitaria).
 * "La conversación y todos los mensajes" deben escribirse claramente).
 */
export interface SessionLike {
  readonly id: string;
  readonly title: string;
  readonly archived: boolean;
}

/** Cambiar nombre: solo los espacios en blanco se consideran vacíos y no se permite el envío. */
export function isRenameValid(draft: string): boolean {
  return draft.trim().length > 0;
}

/** Antes de enviar, retire los espacios en blanco iniciales y finales y comprima los espacios en blanco continuos en el medio en un solo espacio. */
export function normalizeSessionTitle(draft: string): string {
  return draft.trim().replace(/\s+/g, " ");
}

/** Las sesiones de archivo están ocultas de forma predeterminada y aparecen solo cuando \"Mostrar archivos\" está activado. */
export function visibleSessions<T extends { readonly archived: boolean }>(
  sessions: readonly T[],
  showArchived: boolean,
): readonly T[] {
  return showArchived ? sessions : sessions.filter((session) => !session.archived);
}

/** Si hay más de un asistente, deberá seleccionarlo primero; Si hay uno o ningún asistente (aún no leído), puede crearlos directamente. */
export function shouldAskAssistant(assistants: readonly { readonly id: string }[]): boolean {
  return assistants.length > 1;
}

/** Tráelo cuando solo haya un ayudante; de lo contrario, deje que el anfitrión utilice el asistente predeterminado. */
export function assistantIdForNewSession(assistants: readonly { readonly id: string }[]): string | undefined {
  return assistants.length === 1 ? assistants[0]?.id : undefined;
}
