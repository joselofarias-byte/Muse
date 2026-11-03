/**
 * Host Capacidad de negociación.
 *
 * host lado `capabilityFlags` Es una declaración de "lo que implementa este Host" y la página web cambia la interfaz en consecuencia.
 * Para evitar hacer clic en un montón de interacciones que nunca fallan:
 *  - `media`:imageUrls / voiceUrl es una dirección accesible (`/api/media/<token>`);
 *  - `tool_call_details`: cinta de mensaje `toolCalls[]` detalles; detalles
 *  - `message_status`: cinta de mensaje `status` / `errorText`y reconocer `chat.retry`;
 *  - `settings_values`:`settings.snapshot` traer `values[]`, y `settings.update` Aceptar escrituras verificadas;
 *  - `message_write`: reconocer message.quote / message.favorite / message.delete;
 *  - `message_multi_select`:Selección múltiple de mensajes + escritura por lotes (eliminar/recolectar).
 *
 * Distinción clave:**No emitido** capabilityFlags(Anfitrión antiguo) no es lo mismo que "emitido pero sin éste".
 * El primero continúa funcionando de acuerdo con el comportamiento anterior (los que se pueden renderizar se renderizan y los que no están disponibles se usan como marcadores de posición), mientras que el segundo se cierra explícitamente.
 * De lo contrario, una actualización del protocolo borrará todas las imágenes y el estado del antiguo Host.
 * La excepción es la operación de escritura de mensajes: el antiguo Host no tiene estos tres comandos en absoluto, por lo que se trata como "no grabable" de forma predeterminada.
 */
export interface HostCapabilities {
  /** Si se han recibido indicadores de capacidad.false = Anfitrión antiguo, todas las capacidades se basan en comportamientos antiguos. */
  readonly known: boolean;
  readonly media: boolean;
  readonly toolCallDetails: boolean;
  readonly messageStatus: boolean;
  /** settings.snapshot traer `values[]`, y `settings.update` Acepte escrituras verificadas. */
  readonly settingsValues: boolean;
  /** Cotizar/Cobrar/Eliminar. */
  readonly messageWrite: boolean;
  /** Selección múltiple y operaciones por lotes. */
  readonly multiSelect: boolean;
}

export const LEGACY_CAPABILITIES: HostCapabilities = {
  known: false,
  media: true,
  toolCallDetails: true,
  messageStatus: true,
  // El antiguo anfitrión no tiene ninguno. values[], tampoco reconoce números/Escritura de enumeración: subpágina "Actualmente no se admiten ajustes en la página web".
  settingsValues: false,
  // De la misma manera: no hay message.delete / message.favorite, el Host antiguo solo copia.
  messageWrite: false,
  multiSelect: false,
};

export function readCapabilities(flags: readonly string[] | undefined): HostCapabilities {
  if (flags === undefined) return LEGACY_CAPABILITIES;
  const set = new Set(flags);
  return {
    known: true,
    media: set.has("media"),
    toolCallDetails: set.has("tool_call_details"),
    messageStatus: set.has("message_status"),
    settingsValues: set.has("settings_values"),
    messageWrite: set.has("message_write"),
    multiSelect: set.has("message_multi_select"),
  };
}
