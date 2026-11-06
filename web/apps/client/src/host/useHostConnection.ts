import { useCallback, useEffect, useRef, useState } from "react";
import {
  connectHost,
  type HostAssistantDetail,
  type HostAssistantSummary,
  type HostCommand,
  type HostConnection,
  type HostConnectionStatus,
  type HostEvent,
  type HostKnowledgeBase,
  type HostKnowledgeDocument,
  type HostMemoryItem,
  type HostMessage,
  type HostProviderSummary,
  type HostScheduledTask,
  type HostSettingsSnapshot,
  type HostSystemStatus,
} from "../api.js";
import { describeHostError } from "./hostErrors.js";
import { isUnsupportedCommandError } from "./featureCapabilities.js";
import { mergeMessages } from "./messageEqual.js";
import { DEFAULT_LOCALE } from "../i18n/locale.js";
import type { MessageKey } from "../i18n/messages.js";
import { createTranslator, type Translate, type TranslateParams } from "../i18n/translate.js";
import { applySettingValue, settingValueOf } from "./settingsState.js";

export type HostSessionSummary = NonNullable<HostEvent["sessions"]>[number];
export type HostApproval = NonNullable<HostEvent["pendingApprovals"]>[number];

export interface HostSnapshot {
  readonly sessions: readonly HostSessionSummary[];
  readonly messages: readonly HostMessage[];
  readonly sessionId: string | undefined;
  readonly isStreaming: boolean;
  readonly approvals: readonly HostApproval[];
  /** Host Bit de capacidad declarada; el antiguo Host no lo entregará y permanecerá indefinido (no una matriz vacía). */
  readonly capabilities: readonly string[] | undefined;
}

const EMPTY: HostSnapshot = { sessions: [], messages: [], sessionId: undefined, isStreaming: false, approvals: [], capabilities: undefined };

/** memory.list Número de elementos por página (Host Predeterminado 30, límite superior 100). */
const MEMORY_PAGE_SIZE = 30;
/** knowledge.documents Número de elementos por página (Host Predeterminado 20, límite superior 50). */
const KNOWLEDGE_PAGE_SIZE = 20;

/** El estado de lectura de la lista de la base de conocimientos. */
export interface HostKnowledgeState {
  readonly bases: readonly HostKnowledgeBase[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

/** Documentos de una determinada base de conocimientos (acumulados en páginas). */
export interface HostKnowledgeDocumentsState {
  readonly kbId: string | undefined;
  readonly items: readonly HostKnowledgeDocument[];
  readonly hasMore: boolean;
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

/** El estado de lectura de la lista de tareas programadas. */
export interface HostTasksState {
  readonly items: readonly HostScheduledTask[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

/** system.status estado de lectura. */
export interface HostSystemState {
  readonly status: HostSystemStatus | undefined;
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly unsupported: boolean;
}

const EMPTY_SYSTEM: HostSystemState = { status: undefined, loading: false, error: undefined, unsupported: false };

const EMPTY_KNOWLEDGE: HostKnowledgeState = { bases: [], loading: false, error: undefined, unsupported: false };
const EMPTY_DOCUMENTS: HostKnowledgeDocumentsState = {
  kbId: undefined,
  items: [],
  hasMore: false,
  loading: false,
  error: undefined,
  unsupported: false,
};
const EMPTY_TASKS: HostTasksState = { items: [], loading: false, error: undefined, unsupported: false };

/** Datos de la página de memoria: acumulación de paginación + búsqueda o lista actual. */
export interface HostMemoryState {
  readonly items: readonly HostMemoryItem[];
  readonly hasMore: boolean;
  readonly loading: boolean;
  readonly error: string | undefined;
  /** El término de búsqueda correspondiente a la lista actual;undefined Representa una lista normal. */
  readonly query: string | undefined;
  /** Host Se respondió \"Este comando no es compatible\": la página pasó al estado de aviso. */
  readonly unsupported: boolean;
}

const EMPTY_MEMORY: HostMemoryState = {
  items: [],
  hasMore: false,
  loading: false,
  error: undefined,
  query: undefined,
  unsupported: false,
};

/** Establezca el estado de la solicitud de la página de inicio: el resultado de una instantánea de configuración + motivo de solicitud/fallo. */
export interface HostSettingsState {
  /** Existe sólo después de que la primera lectura sea exitosa; cuando falla, se conserva la copia anterior y la lista no se borra. */
  readonly snapshot?: HostSettingsSnapshot;
  readonly loading: boolean;
  /** Sólo complete cuando la lectura falle; si el cambio de escritura falla, se utilizará el brindis y no se capturará el bit de error de la página. */
  readonly error?: string;
}

const EMPTY_SETTINGS: HostSettingsState = { loading: false };

function settingsWithError(current: HostSettingsState, message: string): HostSettingsState {
  return { ...(current.snapshot === undefined ? {} : { snapshot: current.snapshot }), loading: false, error: message };
}

function settingsLoading(current: HostSettingsState): HostSettingsState {
  return { ...(current.snapshot === undefined ? {} : { snapshot: current.snapshot }), loading: true };
}

export interface UseHostConnectionOptions {
  /** Surfaced as a toast; command failures must never be silent. */
  readonly onError: (message: string) => void;
  /** Se vuelve a llamar cuando llega la instantánea (solo se usa para registrar el tiempo de rendimiento, no participa en el renderizado). */
  readonly onSnapshot?: (() => void) | undefined;
  /** Función de traducción del idioma actual: la copia de error y el prefijo de vista previa de la sesión deben seguir el idioma de la interfaz. */
  readonly translate?: Translate | undefined;
}

export interface HostConnectionApi {
  readonly status: HostConnectionStatus;
  /** El número de reconexiones iniciadas (se restablece a cero después de una conexión exitosa), la interfaz lo usa para mostrar \"Enésima vez\". */
  readonly reconnectAttempt: number;
  /** False until the first state.snapshot arrives (avoids empty-state flicker). */
  readonly ready: boolean;
  readonly snapshot: HostSnapshot;
  /** Last-message preview per session id (only the part the snapshot does not carry). */
  readonly previews: Readonly<Record<string, string>>;
  /** assistants.list El resultado: se utiliza para seleccionar asistentes para nuevas sesiones y para mostrar los asistentes actuales en la barra superior. */
  readonly assistants: readonly HostAssistantSummary[];
  /** providers.list Resultado: Traduzca modelId a un nombre de modelo legible. */
  readonly providers: readonly HostProviderSummary[];
  /** settings.snapshot Los resultados y el estado de la solicitud (para configurar la página de inicio). */
  readonly settings: HostSettingsState;
  readonly send: (command: Omit<HostCommand, "requestId">) => void;
  /**
   * Escribe con optimismo: da órdenes primero,Host Ejecutado cuando se devuelve el error. `onReject` Retrocede y dale la razón a las tostadas.
   * `onAccepted` Consíguelo `command.accepted` carga útil (como skippedMessageIds de message.delete).
   */
  readonly sendOptimistic: (
    command: Omit<HostCommand, "requestId">,
    onReject: () => void,
    onAccepted?: (event: HostEvent) => void,
  ) => void;
  readonly requestPreviews: (sessionIds: readonly string[]) => void;
  readonly requestSettings: () => void;
  readonly requestAssistants: () => void;
  readonly requestProviders: () => void;
  /** Caché de detalles del asistente (por ID) y marca \"Este host no admite detalles\". */
  readonly assistantDetails: Readonly<Record<string, HostAssistantDetail>>;
  readonly assistantDetailUnsupported: boolean;
  readonly requestAssistantDetail: (assistantId: string) => void;
  /** Lista de memoria (acumulación paginada) y solicitud de entrada. */
  readonly memory: HostMemoryState;
  readonly requestMemory: (options?: { readonly query?: string | undefined; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => void;
  /** La base de conocimientos (lista + paginación de documentos) y las tareas programadas son de solo lectura. */
  readonly knowledge: HostKnowledgeState;
  readonly knowledgeDocuments: HostKnowledgeDocumentsState;
  readonly tasks: HostTasksState;
  readonly requestKnowledge: () => void;
  readonly requestKnowledgeDocuments: (options: { readonly kbId: string; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => void;
  readonly requestTasks: () => void;
  /** estado del sistema (almacenamiento/copia de seguridad/capacidades de la plataforma). */
  readonly system: HostSystemState;
  readonly requestSystemStatus: () => void;
  /** Cambie de manera optimista la configuración de concurrencia.Actualice y desactive; retrocede automáticamente cuando es rechazado por el anfitrión. */
  readonly updateSetting: (key: string, enabled: boolean) => void;
  /** Escritura genérica de subpáginas:boolean / number / enum El texto va aquí de manera uniforme, y el motivo del fracaso se da para brindar. */
  readonly updateSettingValue: (key: string, value: string | number | boolean) => void;
  readonly retry: () => void;
}

function previewText(message: HostMessage, t: Translate): string {
  const speakerKey =
    message.role === "user"
      ? "preview.you"
      : message.role === "tool"
        ? "preview.tool"
        : message.role === "system"
          ? "preview.system"
          : "preview.assistant";
  const flat = message.content.replace(/\s+/g, " ").trim();
  if (!flat) return "";
  const clipped = flat.length > 72 ? `${flat.slice(0, 72)}…` : flat;
  return t("preview.line", { speaker: t(speakerKey), text: clipped });
}
/**
 * Owns one Host WebSocket for the whole mobile shell: snapshot state, reconnect
 * status, command dispatch and lazy session previews.
 */
export function useHostConnection(options: UseHostConnectionOptions): HostConnectionApi {
  const [status, setStatus] = useState<HostConnectionStatus>("reconnecting");
  const [reconnectAttempt, setReconnectAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<HostSnapshot>(EMPTY);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [assistants, setAssistants] = useState<readonly HostAssistantSummary[]>([]);
  const [providers, setProviders] = useState<readonly HostProviderSummary[]>([]);
  const [assistantDetails, setAssistantDetails] = useState<Record<string, HostAssistantDetail>>({});
  const [assistantDetailUnsupported, setAssistantDetailUnsupported] = useState(false);
  const [memory, setMemory] = useState<HostMemoryState>(EMPTY_MEMORY);
  const [knowledge, setKnowledge] = useState<HostKnowledgeState>(EMPTY_KNOWLEDGE);
  const [knowledgeDocuments, setKnowledgeDocuments] = useState<HostKnowledgeDocumentsState>(EMPTY_DOCUMENTS);
  const [tasks, setTasks] = useState<HostTasksState>(EMPTY_TASKS);
  const [system, setSystem] = useState<HostSystemState>(EMPTY_SYSTEM);
  const [settings, setSettings] = useState<HostSettingsState>(EMPTY_SETTINGS);
  const [ready, setReady] = useState(false);
  const [retryToken, setRetryToken] = useState(0);
  const connectionRef = useRef<HostConnection | undefined>(undefined);
  const pendingPreviews = useRef(new Map<string, string>());
  /** paginación de memoria/Solicitudes de búsqueda pendientes:requestId → ¿Se restablece o se agrega esta vez? */
  const pendingMemory = useRef(new Map<string, { readonly reset: boolean; readonly query?: string | undefined }>());
  /** Solicitud pendiente de detalles del asistente:requestId → assistantId. */
  const pendingDetails = useRef(new Map<string, string>());
  /** Base de conocimientos/documentación/solicitudes pendientes de tareas programadas:requestId → Objetivo. */
  const pendingQueries = useRef(new Map<string, { readonly kind: "knowledge" | "documents" | "tasks" | "system"; readonly reset: boolean }>());
  const pendingSettings = useRef(new Set<string>());
  const pendingWrites = useRef(new Map<string, { key: string; previous: unknown }>());
  /** Acción de reversión escrita por noticias optimistas:requestId → Deshacer función. */
  const pendingRollbacks = useRef(new Map<string, () => void>());
  /** La devolución de llamada de implementación escrita con noticias optimistas:requestId → Manejar comando.carga útil aceptada. */
  const pendingAccepts = useRef(new Map<string, (event: HostEvent) => void>());
  const optionsRef = useRef(options);
  // Traductor para el idioma actual: el texto del error sigue el idioma de la interfaz; si la persona que llama no pasa, presione zh.
  const translateRef = useRef<Translate>(options.translate ?? createTranslator(DEFAULT_LOCALE));
  translateRef.current = options.translate ?? createTranslator(DEFAULT_LOCALE);
  const tt = useCallback(
    (key: MessageKey, params?: TranslateParams) => translateRef.current(key, params),
    [],
  );
  // Para leer la "última configuración": el conmutador necesita conocer el valor anterior antes de escribir para poder revertirlo.
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  useEffect(() => {
    optionsRef.current = options;
  }, [options]);

  useEffect(() => {
    let active = true;
    let unsubscribeEvents: (() => void) | undefined;
    let unsubscribeStatus: (() => void) | undefined;
    setStatus("reconnecting");
    // Reconectar/Después de volver a intentarlo, la instantánea de configuración anterior se invalidará y los datos leídos en la página siguiente deben ser los datos de la nueva conexión.
    setSettings(EMPTY_SETTINGS);

    void connectHost()
      .then((connection) => {
        if (!active) {
          connection.close();
          return;
        }
        connectionRef.current = connection;
        unsubscribeStatus = connection.onStatus((next, tries) => {
          if (!active) return;
          setStatus(next);
          setReconnectAttempt(tries);
        });
        unsubscribeEvents = connection.subscribe((event) => {
          if (!active) return;
          if (event.type === "state.snapshot") {
            setReady(true);
            // Fusionar: los mensajes sin cambios reutilizan objetos antiguos,memo para que realmente surta efecto (consulte messageEqual.ts).
            setSnapshot((current) => ({
              sessions: event.sessions ?? [],
              messages: mergeMessages(current.messages, event.messages ?? []),
              sessionId: event.sessionId,
              isStreaming: event.isStreaming === true,
              approvals: event.pendingApprovals ?? [],
              capabilities: event.capabilityFlags,
            }));
            optionsRef.current.onSnapshot?.();
            return;
          }
          if (event.type === "history.page") {
            const requestId = event.requestId;
            const target = requestId === undefined ? undefined : pendingPreviews.current.get(requestId);
            if (requestId === undefined || target === undefined) return;
            pendingPreviews.current.delete(requestId);
            const messages = event.messages ?? [];
            const latest = messages[messages.length - 1];
            const text = latest ? previewText(latest, tt) : "";
            if (!text) return;
            setPreviews((current) => (current[target] === text ? current : { ...current, [target]: text }));
            return;
          }
          if (event.type === "settings.snapshot") {
            if (event.requestId !== undefined) pendingSettings.current.delete(event.requestId);
            const next = event.settings;
            if (next === undefined) {
              setSettings((current) => settingsWithError(current, tt("error.settingsEmpty")));
              return;
            }
            setSettings({ snapshot: next, loading: false });
            return;
          }
          if (event.type === "assistants.list") {
            setAssistants(event.assistants ?? []);
            return;
          }
          if (event.type === "providers.list") {
            setProviders(event.providers ?? []);
            return;
          }
          if (event.type === "assistants.detail") {
            const requestId = event.requestId;
            const assistantId = requestId === undefined ? undefined : pendingDetails.current.get(requestId);
            if (requestId !== undefined) pendingDetails.current.delete(requestId);
            const detail = event.assistantDetail;
            if (assistantId !== undefined && detail !== undefined) {
              setAssistantDetails((current) => ({ ...current, [assistantId]: detail }));
            }
            return;
          }
          if (event.type === "knowledge.list") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setKnowledge((current) => ({ bases: event.bases ?? [], loading: false, error: undefined, unsupported: current.unsupported }));
            return;
          }
          if (event.type === "knowledge.documents") {
            const requestId = event.requestId;
            const pending = requestId === undefined ? undefined : pendingQueries.current.get(requestId);
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setKnowledgeDocuments((current) => {
              const base = pending?.reset === true ? [] : current.items;
              const seen = new Set<string>();
              const items = [...base, ...(event.documents ?? [])].filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
              return { ...current, items, hasMore: event.hasMore === true, loading: false, error: undefined };
            });
            return;
          }
          if (event.type === "system.status") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setSystem((current) => ({ status: event.system, loading: false, error: undefined, unsupported: current.unsupported }));
            return;
          }
          if (event.type === "tasks.list") {
            const requestId = event.requestId;
            if (requestId !== undefined) pendingQueries.current.delete(requestId);
            setTasks((current) => ({ items: event.tasks ?? [], loading: false, error: undefined, unsupported: current.unsupported }));
            return;
          }
          if (event.type === "memory.list" || event.type === "memory.search") {
            const requestId = event.requestId;
            const pending = requestId === undefined ? undefined : pendingMemory.current.get(requestId);
            if (requestId !== undefined) pendingMemory.current.delete(requestId);
            const page = event.items ?? [];
            setMemory((current) => {
              const base = pending?.reset === true ? [] : current.items;
              const seen = new Set<string>();
              const items = [...base, ...page].filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
              return {
                items,
                hasMore: event.hasMore === true,
                loading: false,
                error: undefined,
                query: pending?.query ?? current.query,
                unsupported: current.unsupported,
              };
            });
            return;
          }
          if (event.type === "command.accepted") {
            // settings.update / El mensaje está escrito en el suelo. Si la escritura falla, se producirá el evento de error. Aquí sólo necesita borrar la tarea pendiente.
            const accepted = event.requestId === undefined ? undefined : pendingAccepts.current.get(event.requestId);
            if (event.requestId !== undefined) {
              pendingWrites.current.delete(event.requestId);
              pendingRollbacks.current.delete(event.requestId);
              pendingAccepts.current.delete(event.requestId);
            }
            if (accepted !== undefined) accepted(event);
            return;
          }
          if (event.type === "error") {
            const requestId = event.requestId;
            // Detalles de la memoria/Asistente: si falla, se mostrará en el acto. Si no se implementa, se cortará al mensaje "aún no es compatible" y el brindis no se reproducirá.
            const queryPending = requestId === undefined ? undefined : pendingQueries.current.get(requestId);
            if (requestId !== undefined && queryPending !== undefined) {
              pendingQueries.current.delete(requestId);
              const unsupported = isUnsupportedCommandError(event.error);
              const message = unsupported ? undefined : describeHostError(event.error, tt("error.operationFailed"), tt);
              if (queryPending.kind === "knowledge") {
                setKnowledge((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else if (queryPending.kind === "documents") {
                setKnowledgeDocuments((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else if (queryPending.kind === "tasks") {
                setTasks((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              } else {
                setSystem((current) => ({ ...current, loading: false, error: message, unsupported: current.unsupported || unsupported }));
              }
              return;
            }
            const memoryPending = requestId === undefined ? undefined : pendingMemory.current.get(requestId);
            if (requestId !== undefined && memoryPending !== undefined) {
              pendingMemory.current.delete(requestId);
              const unsupported = isUnsupportedCommandError(event.error);
              setMemory((current) => ({
                ...current,
                loading: false,
                error: unsupported ? undefined : describeHostError(event.error, tt("error.operationFailed"), tt),
                unsupported: current.unsupported || unsupported,
              }));
              return;
            }
            const detailPending = requestId === undefined ? undefined : pendingDetails.current.get(requestId);            if (requestId !== undefined && detailPending !== undefined) {
              pendingDetails.current.delete(requestId);
              if (isUnsupportedCommandError(event.error)) {
                setAssistantDetailUnsupported(true);
              } else {
                optionsRef.current.onError(describeHostError(event.error, tt("error.operationFailed"), tt));
              }
              return;
            }
            // Preview lookups are best effort: an older Host without history.page
            // must not spam the user with toasts.
            if (requestId !== undefined && pendingPreviews.current.delete(requestId)) return;
            // Establecer error de lectura: muestra la barra de error de la página + inténtalo de nuevo, ya no apilas un brindis.
            if (requestId !== undefined && pendingSettings.current.delete(requestId)) {
              setSettings((current) => settingsWithError(current, describeHostError(event.error, tt("error.settingsRead"), tt)));
              return;
            }
            // Error de escritura (todos los cambios/números/enumeraciones van aquí): retroceda al valor antes de escribir e indique el motivo dado por el anfitrión.
            const rejected = requestId === undefined ? undefined : pendingWrites.current.get(requestId);
            if (requestId !== undefined && rejected !== undefined) {
              pendingWrites.current.delete(requestId);
              setSettings((current) => {
                const currentSnapshot = current.snapshot;
                return currentSnapshot === undefined
                  ? current
                  : { ...current, snapshot: applySettingValue(currentSnapshot, rejected.key, rejected.previous) };
              });
              optionsRef.current.onError(describeHostError(event.error, tt("error.settingsSave"), tt));
              return;
            }
            // Se rechaza la escritura de mensajes (eliminación/favorito): primero retroceda localmente y luego indique el motivo dado por el anfitrión.
            const rollback = requestId === undefined ? undefined : pendingRollbacks.current.get(requestId);
            if (requestId !== undefined && rollback !== undefined) {
              pendingRollbacks.current.delete(requestId);
              pendingAccepts.current.delete(requestId);
              rollback();
              optionsRef.current.onError(describeHostError(event.error, tt("error.operationFailed"), tt));
              return;
            }
            optionsRef.current.onError(describeHostError(event.error, tt("error.commandFailed"), tt));
          }
        });
        try {
          connection.send({ type: "hello", requestId: crypto.randomUUID() });
        } catch {
          // Status listener reports the real state; nothing to do here.
        }
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setStatus("closed");
        optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.connectFailed"));
      });

    return () => {
      active = false;
      unsubscribeEvents?.();
      unsubscribeStatus?.();
      pendingPreviews.current.clear();
      pendingSettings.current.clear();
      pendingWrites.current.clear();
      pendingRollbacks.current.clear();
      pendingAccepts.current.clear();
      connectionRef.current?.close();
      connectionRef.current = undefined;
    };
  }, [retryToken]);

  const send = useCallback((command: Omit<HostCommand, "requestId">) => {
    const connection = connectionRef.current;
    if (!connection) {
      optionsRef.current.onError(tt("error.notConnected"));
      return;
    }
    try {
      connection.send({ ...command, requestId: crypto.randomUUID() });
    } catch (cause) {
      optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.sendFailed"));
    }
  }, []);

  const sendOptimistic = useCallback(
    (command: Omit<HostCommand, "requestId">, onReject: () => void, onAccepted?: (event: HostEvent) => void) => {
      const connection = connectionRef.current;
      if (!connection) {
        onReject();
        optionsRef.current.onError(tt("error.notConnected"));
        return;
      }
      const requestId = crypto.randomUUID();
      pendingRollbacks.current.set(requestId, onReject);
      if (onAccepted !== undefined) pendingAccepts.current.set(requestId, onAccepted);
      try {
        connection.send({ ...command, requestId });
      } catch (cause) {
        pendingRollbacks.current.delete(requestId);
        pendingAccepts.current.delete(requestId);
        onReject();
        optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.sendFailed"));
      }
    },
    [],
  );

  const requestPreviews = useCallback((sessionIds: readonly string[]) => {
    const connection = connectionRef.current;
    if (!connection) return;
    for (const sessionId of sessionIds) {
      const requestId = crypto.randomUUID();
      pendingPreviews.current.set(requestId, sessionId);
      try {
        connection.send({ type: "history.page", sessionId, limit: 1, offset: 0, requestId });
      } catch {
        pendingPreviews.current.delete(requestId);
      }
    }
  }, []);

  const retry = useCallback(() => setRetryToken((token) => token + 1), []);

  /** Abra la lista de asistentes una vez: se utiliza para seleccionar asistentes para nuevas sesiones e identificar al asistente actual en la barra superior. */
  const requestAssistants = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    try {
      connection.send({ type: "assistants.list", requestId: crypto.randomUUID() });
    } catch {
      // No se informará ningún error si falla la lectura: el nombre del asistente es solo para mostrar y no afecta el chat.
    }
  }, []);

  /** proveedor/Nombre del modelo: solo se usa para mostrar modelId como nombre completo. */
  const requestProviders = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    try {
      connection.send({ type: "providers.list", requestId: crypto.randomUUID() });
    } catch {
      // Igual que el anterior, únicamente para visualización.
    }
  }, []);

  /** Detalles del asistente: caché por identificación, no más solicitudes después de entradas repetidas. */
  const requestAssistantDetail = useCallback((assistantId: string) => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingDetails.current.set(requestId, assistantId);
    try {
      connection.send({ type: "assistants.detail", assistantId, requestId });
    } catch {
      pendingDetails.current.delete(requestId);
    }
  }, []);

  /**
   * Memoria: vaya a memoria.búsqueda con consulta; de lo contrario, memoria.lista;cursor Es la última identificación en la página anterior.
   * La búsqueda no está paginada (memory.search en el contrato solo acepta consulta + límite).
   */
  const requestMemory = useCallback(
    (options?: { readonly query?: string | undefined; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => {
      const connection = connectionRef.current;
      if (!connection) return;
      const query = options?.query !== undefined && options.query.length > 0 ? options.query : undefined;
      const requestId = crypto.randomUUID();
      const reset = options?.reset === true || options?.cursor === undefined;
      pendingMemory.current.set(requestId, { reset, query });
      setMemory((current) => ({
        ...current,
        loading: true,
        error: undefined,
        ...(reset ? { items: [], hasMore: false } : {}),
      }));
      try {
        if (query !== undefined) connection.send({ type: "memory.search", query, limit: MEMORY_PAGE_SIZE, requestId });
        else connection.send({ type: "memory.list", limit: MEMORY_PAGE_SIZE, ...(options?.cursor === undefined ? {} : { cursor: options.cursor }), requestId });
      } catch (cause) {
        pendingMemory.current.delete(requestId);
        setMemory((current) => ({
          ...current,
          loading: false,
          error: cause instanceof Error ? cause.message : tt("error.sendFailed"),
        }));
      }
    },
    [tt],
  );

  /** Lista de la base de conocimientos (solo lectura). */
  const requestKnowledge = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "knowledge", reset: true });
    setKnowledge((current) => ({ ...current, loading: true, error: undefined }));
    try {
      connection.send({ type: "knowledge.list", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setKnowledge((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  /** Documentación de una base de conocimientos:cursor Es la última identificación de la página anterior (el valor predeterminado es 20 por página,Host con un límite de 50). */
  const requestKnowledgeDocuments = useCallback(
    (options: { readonly kbId: string; readonly cursor?: string | undefined; readonly reset?: boolean | undefined }) => {
      const connection = connectionRef.current;
      if (!connection) return;
      const requestId = crypto.randomUUID();
      const reset = options.reset === true || options.cursor === undefined;
      pendingQueries.current.set(requestId, { kind: "documents", reset });
      setKnowledgeDocuments((current) => ({
        ...current,
        kbId: options.kbId,
        loading: true,
        error: undefined,
        ...(reset ? { items: [], hasMore: false } : {}),
      }));
      try {
        connection.send({
          type: "knowledge.documents",
          kbId: options.kbId,
          limit: KNOWLEDGE_PAGE_SIZE,
          ...(options.cursor === undefined ? {} : { cursor: options.cursor }),
          requestId,
        });
      } catch (cause) {
        pendingQueries.current.delete(requestId);
        setKnowledgeDocuments((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
      }
    },
    [tt],
  );

  /** Lista de tareas programadas (solo lectura). */
  const requestTasks = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "tasks", reset: true });
    setTasks((current) => ({ ...current, loading: true, error: undefined }));
    try {
      connection.send({ type: "tasks.list", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setTasks((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  /** Estado del sistema (almacenamiento/copia de seguridad/capacidades de plataforma), solo lectura. */
  const requestSystemStatus = useCallback(() => {
    const connection = connectionRef.current;
    if (!connection) return;
    const requestId = crypto.randomUUID();
    pendingQueries.current.set(requestId, { kind: "system", reset: true });
    setSystem((current) => ({ ...current, loading: true, error: undefined }));
    try {
      connection.send({ type: "system.status", requestId });
    } catch (cause) {
      pendingQueries.current.delete(requestId);
      setSystem((current) => ({ ...current, loading: false, error: cause instanceof Error ? cause.message : tt("error.sendFailed") }));
    }
  }, [tt]);

  const requestSettings = useCallback(() => {    const connection = connectionRef.current;
    if (!connection) {
      setSettings((current) => settingsWithError(current, tt("error.notConnected")));
      return;
    }
    const requestId = crypto.randomUUID();
    pendingSettings.current.add(requestId);
    setSettings(settingsLoading);
    try {
      connection.send({ type: "settings.snapshot", requestId });
    } catch (cause) {
      pendingSettings.current.delete(requestId);
      setSettings((current) => settingsWithError(current, cause instanceof Error ? cause.message : tt("error.settingsRead")));
    }
  }, []);

  /**
   * Configuración de escritura: cambiar localmente primero (optimista),Host Revertir el rechazo.
   *
   * booleano sigue siendo `"true"` / `"false"` Entrega de cuerdas——Host La configuración existente.update se basa en estos dos literales
   * Analizar booleano; Los números y enumeraciones se asignan directamente al tipo original y se verifican mediante el tipo declarado por el Host de acuerdo con esta clave.
   */
  const updateSettingValue = useCallback((key: string, value: string | number | boolean) => {
    const connection = connectionRef.current;
    if (!connection) {
      optionsRef.current.onError(tt("error.notConnected"));
      return;
    }
    const current = settingsRef.current.snapshot;
    if (current === undefined) return;
    const previous = settingValueOf(current, key);
    const requestId = crypto.randomUUID();
    pendingWrites.current.set(requestId, { key, previous });
    setSettings((state) => (state.snapshot === undefined ? state : { ...state, snapshot: applySettingValue(state.snapshot, key, value) }));
    try {
      connection.send({ type: "settings.update", key, value: typeof value === "boolean" ? (value ? "true" : "false") : value, requestId });
    } catch (cause) {
      pendingWrites.current.delete(requestId);
      setSettings((state) => (state.snapshot === undefined ? state : { ...state, snapshot: applySettingValue(state.snapshot, key, previous) }));
      optionsRef.current.onError(cause instanceof Error ? cause.message : tt("error.settingsSave"));
    }
  }, []);

  /** La fila de cambios en la página de inicio sigue la entrada anterior y el mismo conjunto de actualizaciones optimistas + reversión se usa internamente. */
  const updateSetting = useCallback(
    (key: string, enabled: boolean) => updateSettingValue(key, enabled),
    [updateSettingValue],
  );

  return {
    status,
    reconnectAttempt,
    ready,
    snapshot,
    previews,
    assistants,
    providers,
    assistantDetails,
    assistantDetailUnsupported,
    memory,
    knowledge,
    knowledgeDocuments,
    tasks,
    system,
    settings,
    send,
    sendOptimistic,
    requestPreviews,
    requestAssistants,
    requestProviders,
    requestAssistantDetail,
    requestMemory,
    requestKnowledge,
    requestKnowledgeDocuments,
    requestTasks,
    requestSystemStatus,
    requestSettings,
    updateSetting,
    updateSettingValue,
    retry,
  };
}
