import type { ChatMessage, ChatStreamEvent, ProviderConfig } from "@muse/contracts";
import { FetchProviderTransport } from "@muse/provider";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export interface SessionRecord {
  readonly id: string;
  readonly title: string;
  readonly assistantId: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ClientSession {
  readonly token: string;
  readonly userId: string;
  readonly expiresAt: number;
}

export interface StoredProviderConfig extends ProviderConfig {
  readonly accountId: string;
  readonly hasSecret: boolean;
}

export interface AssistantRecord {
  readonly id: string;
  readonly accountId: string;
  readonly name: string;
  readonly identityPrompt: string;
  readonly relationshipPrompt: string;
  readonly stylePrompt: string;
  readonly providerId?: string;
  readonly modelId?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface MemoryFact {
  readonly id: string;
  readonly content: string;
  readonly importance: 0 | 1 | 2;
  readonly confidence: number;
  readonly hitCount: number;
  readonly pinnedAt?: string;
  readonly updatedAt: string;
}

export interface RagChunk {
  readonly id: string;
  readonly documentId: string;
  readonly ordinal: number;
  readonly text: string;
  readonly tokenEstimate: number;
}

export async function apiJson<T>(path: string, init: RequestInit = {}, session?: ClientSession): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json");
  if (session) headers.set("authorization", `Bearer ${session.token}`);
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  const body: unknown = await response.json();
  if (!response.ok) {
    const message = body && typeof body === "object" && "message" in body && typeof body.message === "string" ? body.message : `request failed (${response.status})`;
    throw new Error(message);
  }
  return body as T;
}

export async function login(email: string, password: string): Promise<ClientSession> {
  const result = await apiJson<{ session: ClientSession }>("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) });
  return result.session;
}

export async function register(email: string, password: string): Promise<void> {
  await apiJson("/api/v1/auth/register", { method: "POST", body: JSON.stringify({ email, password }) });
}

export async function listSessions(session: ClientSession): Promise<SessionRecord[]> {
  const result = await apiJson<{ sessions: SessionRecord[] }>("/api/v1/sessions", {}, session);
  return result.sessions;
}

export async function createSession(session: ClientSession, assistantId?: string): Promise<SessionRecord> {
  const result = await apiJson<{ session: SessionRecord }>("/api/v1/sessions", { method: "POST", body: JSON.stringify(assistantId ? { assistantId } : {}) }, session);
  return result.session;
}

export async function listProviders(session: ClientSession): Promise<StoredProviderConfig[]> {
  const result = await apiJson<{ providers: StoredProviderConfig[] }>("/api/v1/providers", {}, session);
  return result.providers;
}

export async function saveProvider(session: ClientSession, provider: ProviderConfig, apiKey: string): Promise<StoredProviderConfig> {
  const result = await apiJson<{ provider: StoredProviderConfig }>("/api/v1/providers", { method: "POST", headers: { "x-muse-provider-key": apiKey }, body: JSON.stringify(provider) }, session);
  return result.provider;
}

export async function listAssistants(session: ClientSession): Promise<AssistantRecord[]> {
  const result = await apiJson<{ assistants: AssistantRecord[] }>("/api/v1/assistants", {}, session);
  return result.assistants;
}

export async function createAssistant(session: ClientSession, input: Pick<AssistantRecord, "name" | "identityPrompt" | "relationshipPrompt" | "stylePrompt">): Promise<AssistantRecord> {
  const result = await apiJson<{ assistant: AssistantRecord }>("/api/v1/assistants", { method: "POST", body: JSON.stringify(input) }, session);
  return result.assistant;
}

export async function updateAssistant(session: ClientSession, assistant: AssistantRecord): Promise<AssistantRecord> {
  const result = await apiJson<{ assistant: AssistantRecord }>(`/api/v1/assistants/${encodeURIComponent(assistant.id)}`, { method: "PATCH", body: JSON.stringify({ name: assistant.name, identityPrompt: assistant.identityPrompt, relationshipPrompt: assistant.relationshipPrompt, stylePrompt: assistant.stylePrompt }) }, session);
  return result.assistant;
}

export async function searchMemory(session: ClientSession, query: string, assistantId = "default", spaceId = "default"): Promise<MemoryFact[]> {
  const params = new URLSearchParams({ q: query, assistantId, spaceId });
  const result = await apiJson<{ facts: MemoryFact[] }>(`/api/v1/memory/search?${params}`, {}, session);
  return result.facts;
}

export async function createMemorySpace(session: ClientSession, name: string, scope = "main"): Promise<{ id: string; name: string; scope: string }> {
  const result = await apiJson<{ space: { id: string; name: string; scope: string } }>("/api/v1/memory/spaces", { method: "POST", body: JSON.stringify({ name, scope }) }, session);
  return result.space;
}

export async function addMemoryFact(session: ClientSession, input: { spaceId: string; content: string; assistantId?: string; importance?: 0 | 1 | 2 }): Promise<MemoryFact> {
  const result = await apiJson<{ fact: MemoryFact }>("/api/v1/memory/facts", { method: "POST", body: JSON.stringify(input) }, session);
  return result.fact;
}

export async function searchRag(session: ClientSession, query: string): Promise<{ chunks: RagChunk[]; citations: Array<{ citationId: string; documentId: string; title: string; excerpt: string }> }> {
  const result = await apiJson<{ chunks: RagChunk[]; citations: Array<{ citationId: string; documentId: string; title: string; excerpt: string }> }>(`/api/v1/rag/search?q=${encodeURIComponent(query)}`, {}, session);
  return result;
}

export async function indexRagDocument(session: ClientSession, documentId: string, text: string): Promise<RagChunk[]> {
  const result = await apiJson<{ chunks: RagChunk[] }>("/api/v1/rag/documents", { method: "POST", body: JSON.stringify({ documentId, text }) }, session);
  return result.chunks;
}

export interface FileUploadPlan {
  readonly objectKey: string;
  readonly downloadName: string;
  readonly upload: { readonly url: string; readonly expiresAt: string };
}

export async function presignFile(session: ClientSession, input: { fileId: string; name: string; mimeType: string; sizeBytes: number }): Promise<FileUploadPlan> {
  return apiJson<FileUploadPlan>("/api/v1/files/presign", { method: "POST", body: JSON.stringify(input) }, session);
}

export async function completeFile(session: ClientSession, input: { fileId: string; name: string; mimeType: string; sizeBytes: number; objectKey: string; sha256: string }): Promise<void> {
  await apiJson("/api/v1/files/complete", { method: "POST", body: JSON.stringify(input) }, session);
}

export async function registerPushSubscription(session: ClientSession, subscription: PushSubscription): Promise<void> {
  const jsonSubscription = subscription.toJSON();
  if (!jsonSubscription.endpoint || !jsonSubscription.keys?.p256dh || !jsonSubscription.keys.auth) throw new Error("browser push subscription is incomplete");
  await apiJson("/api/v1/push/subscriptions", { method: "POST", body: JSON.stringify({ endpoint: jsonSubscription.endpoint, p256dh: jsonSubscription.keys.p256dh, auth: jsonSubscription.keys.auth, userAgent: navigator.userAgent }) }, session);
}

export async function listMessages(session: ClientSession, sessionId: string): Promise<ChatMessage[]> {
  const result = await apiJson<{ messages: ChatMessage[] }>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages`, {}, session);
  return result.messages;
}

/** Persist a message produced by the browser-side BYOK transport. */
export async function appendMessage(session: ClientSession, sessionId: string, message: ChatMessage): Promise<void> {
  await apiJson(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages`, { method: "POST", body: JSON.stringify(message) }, session);
}

export interface HostCommand {
  readonly protocolVersion?: number;
  readonly type: "hello" | "state.sync" | "session.select" | "session.new" | "session.rename" | "session.archive" | "session.delete" | "chat.send" | "chat.stop" | "chat.retry" | "chat.regenerate" | "chat.continue" | "tool.approval.resolve" | "history.page" | "history.search" | "message.copy" | "message.quote" | "message.delete" | "message.favorite" | "settings.snapshot" | "settings.update" | "assistants.list" | "assistants.detail" | "memory.list" | "memory.search" | "knowledge.list" | "knowledge.documents" | "tasks.list" | "system.status" | "providers.list" | "sessions.detail";
  readonly requestId?: string;
  readonly sessionId?: string;
  /** session.new La identificación del asistente de destino; si no se aprueba, el anfitrión utiliza el asistente predeterminado. */
  readonly assistantId?: string;
  /** knowledge.documents base de conocimientos objetivo. */
  readonly kbId?: string;
  /** chat.send Referencia: la identificación del mensaje al que se hace referencia (Host Sólo se acepta dentro de la misma sesión). */
  readonly quotedMessageId?: string;
  readonly text?: string;
  readonly generationId?: string;
  readonly title?: string;
  readonly archived?: boolean;
  readonly messageId?: string;
  /** `cursor`:state.sync cursor de evento(número);memory.list El cursor de paginación es la última identificación (cadena) de la página anterior. */
  /** message.delete El objetivo del lote (Host Elimine los elementos uno por uno e informe el recuento eliminado en el recibo). */
  readonly messageIds?: readonly string[];
  /** message.favorite estado objetivo:true = colección,false = Cancelar favoritos. */
  readonly favored?: boolean;
  /** state.sync El cursor del evento es un número;memory.list El cursor de paginación es la última identificación (cadena) de la página anterior. */
  readonly cursor?: string | number;
  /** history.page Paginación:offset ¿Cuántos elementos recientes omitir?limit Número de artículos por página. */
  readonly offset?: number;
  readonly limit?: number;
  /** history.search Palabras clave. */
  readonly query?: string;
  /** settings.update La clave de destino debe estar en la lista blanca del servidor. */
  readonly key?: string;
  /**
   * settings.update El valor objetivo, verificado por el tipo declarado por la clave.
   * booleano sigue siendo `"true"` / `"false"` Se emiten cadenas (de acuerdo con el análisis existente del Host) y los números y enumeraciones se asignan directamente a los tipos originales.
   */
  readonly value?: string | number | boolean;
}

export interface HostSettingsEntry {
  readonly id: string;
  /** Nombre del recurso de cadena para el título: stable_id (navegación/Se utiliza para afirmaciones) y no se muestra directamente. */
  readonly titleKey: string;
  readonly descKey?: string;
  /** Host Título analizado según el idioma actual de la aplicación. */
  readonly title: string;
  /** Host Descripción analizada; las entradas sin descripción están vacías. */
  readonly desc?: string;
  readonly groupKey: string;
  readonly iconKey: string;
  readonly kind: "link" | "switch" | "action";
  readonly enabled?: boolean;
  readonly toggleKey?: string;
  readonly checked?: boolean;
}

export interface HostSettingsGroup {
  /** El nombre del recurso de cadena del título del grupo. */
  readonly key: string;
  /** Host Títulos de grupo analizados según el idioma actual de la aplicación. */
  readonly groupTitle: string;
  readonly entries: readonly HostSettingsEntry[];
}

export type HostSettingValueType = "boolean" | "number" | "enum" | "text";

/** Elementos de enumeración:`label` El host lo analiza según el idioma actual y luego lo emite.Web No cree su propia tabla de copia. */
export interface HostSettingOption {
  readonly value: string;
  readonly label: string;
}

/**
 * settings.snapshot un elemento ajustable en .
 *
 * `type` Determine el control (cambiar/agregar/restar/radio expandible/fila de solo lectura),`value` el tipo de `type` Alineación:
 * boolean → boolean,number → number,enum → string(golpear `options[].value`),text → string.
 */
export interface HostSettingValue {
  readonly key: string;
  readonly type: HostSettingValueType;
  readonly value: unknown;
  /** solo type=enum: Opcional, el orden es el orden de visualización. */
  readonly options?: readonly HostSettingOption[];
}

export interface HostSettingsSnapshot {
  readonly groups: readonly HostSettingsGroup[];
  readonly toggles: readonly { readonly key: string; readonly enabled: boolean }[];
  readonly display: { readonly language: string; readonly themeMode: string; readonly fontSizeScale: string; readonly themeId: string };
  readonly writableKeys: readonly string[];
  /** Elementos ajustables de subpáginas; El antiguo anfitrión no entregará (undefined no es igual a la matriz vacía). */
  readonly values?: readonly HostSettingValue[];
}

export interface HostAssistantSummary {
  readonly id: string;
  readonly name: string;
  readonly avatarUri?: string;
  readonly isDefault?: boolean;
}

/** assistants.detail Carga: parámetros de solo lectura + resumen de caracteres + estadísticas. */
export interface HostAssistantDetail {
  readonly id: string;
  readonly name: string;
  readonly avatarUri?: string | undefined;
  readonly description?: string | undefined;
  readonly modelId?: string | undefined;
  readonly providerId?: string | undefined;
  readonly temperature?: number | undefined;
  readonly topP?: number | undefined;
  readonly maxTokens?: number | undefined;
  readonly personaSummary?: string | undefined;
  readonly systemPromptLength?: number | undefined;
  readonly toolCount?: number | undefined;
  readonly contextMessageSize?: number | undefined;
  readonly reasoningLevel?: string | undefined;
  readonly enabled?: boolean | undefined;
}

/** system.status El almacenamiento ocupado (bytes). */
export interface HostSystemStorage {
  readonly databaseBytes: number;
  readonly mediaBytes: number;
  readonly voiceBytes: number;
  readonly workspaceBytes: number;
  readonly cacheBytes: number;
  readonly totalAppBytes: number;
}

export interface HostSystemBackup {
  readonly lastBackupAt?: number | undefined;
  readonly lastBackupBytes?: number | undefined;
  readonly autoBackupEnabled: boolean;
  readonly cloudBackupEnabled: boolean;
}

/** Capacidades específicas de la plataforma:true=disponible,false=no abierto,undefined=No disponible. */
export interface HostSystemCapabilities {
  readonly accessibility?: boolean | undefined;
  readonly shell?: boolean | undefined;
  readonly root?: boolean | undefined;
  readonly shizuku?: boolean | undefined;
  readonly virtualDisplay?: boolean | undefined;
  readonly notificationListener?: boolean | undefined;
}

/** system.status carga. */
export interface HostSystemStatus {
  readonly appVersion: string;
  readonly appVersionCode: number;
  readonly uptimeMs: number;
  readonly storage: HostSystemStorage;
  readonly backup: HostSystemBackup;
  readonly capabilities: HostSystemCapabilities;
  readonly battery: { readonly level?: number | undefined; readonly charging?: boolean | undefined };
}

/** Una base de conocimientos. */
export interface HostKnowledgeBase {
  readonly id: string;
  readonly name: string;
  readonly documentCount: number;
  readonly chunkCount: number;
  readonly createdAt: number;
  readonly updatedAt: number;
  /** El backend de incrustación utilizado actualmente. */
  readonly embeddingBackend?: string | undefined;
}

/** Un documento en la base de conocimientos. */
export interface HostKnowledgeDocument {
  readonly id: string;
  readonly title: string;
  readonly chunkCount: number;
  readonly sizeBytes: number;
  readonly createdAt: number;
  /** Host El estado del índice dado (se muestra tal cual). */
  readonly status?: string | undefined;
}

/** Una tarea programada. */
export interface HostScheduledTask {
  readonly id: string;
  readonly name: string;
  readonly cron: string;
  readonly enabled: boolean;
  readonly nextRunAt?: number | undefined;
  readonly lastRunAt?: number | undefined;
  readonly lastResult?: string | undefined;
}

/** Un recuerdo. */
export interface HostMemoryItem {
  readonly id: string;
  readonly content: string;
  readonly createdAt: number;
  readonly source?: string | undefined;
  readonly pinned?: boolean | undefined;
  /** memory.search Fragmento de golpe. */
  readonly snippet?: string | undefined;
}

/** memory.list / memory.search resultados paginados. */
export interface HostMemoryPage {
  readonly items: readonly HostMemoryItem[];
  readonly hasMore: boolean;
}

export interface HostProviderSummary {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly models: readonly { readonly id: string; readonly name: string }[];
}

export interface HostSessionDetail {
  readonly id: string;
  readonly title: string;
  readonly assistantId: string;
  readonly archived: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly messageCount: number;
}

export interface HostToolCall {
  readonly callId: string;
  readonly name: string;
  readonly argumentsPreview?: string | undefined;
  readonly resultPreview?: string | undefined;
  readonly success?: boolean | undefined;
  readonly durationMs?: number | undefined;
}

export type HostMessageStatus = "sending" | "streaming" | "sent" | "failed";

export interface HostMessage {
  readonly id: string;
  readonly role: "system" | "user" | "assistant" | "tool";
  readonly content: string;
  readonly reasoning?: string;
  readonly modelId?: string;
  readonly createdAt: number;
  /**
   * Dirección de acceso directo: el nuevo host proporciona la ruta relativa al origen `/api/media/<token>`(Cookie cinturón automático),
   * Es posible que los datos antiguos aún estén `data:` URI / http URL / desnudo base64 / `file://` Camino absoluto.
   */
  readonly imageUrls?: readonly string[];
  readonly toolCallId?: string;
  readonly toolName?: string;
  readonly citationUrls?: readonly string[];
  /** La dirección del mensaje de voz enviado por el asistente también es una ruta accesible. */
  readonly voiceUrl?: string | undefined;
  /** Duración de la voz, milisegundos. */
  readonly voiceDurationMs?: number | undefined;
  /** Los detalles de la llamada a la herramienta (posiblemente varios) en este mensaje; la antigua Hostia no los entregará. */
  readonly toolCalls?: readonly HostToolCall[] | undefined;
  /** Estado de envío a nivel de mensaje; El antiguo anfitrión no realiza entregas. */
  readonly status?: HostMessageStatus | undefined;
  /** solo status=failed A veces valioso. */
  readonly errorText?: string | undefined;
  /** Si ha sido recogido; si la antigua Hostia no la entrega, se tendrá por no recogida. */
  readonly favorite?: boolean | undefined;
  /** Cite el texto de la respuesta;Host El bloque de referencia no se mostrará cuando no se emita. */
  readonly quotedContent?: string | undefined;
}

export interface HostEvent {
  readonly type: "state.snapshot" | "command.accepted" | "command.duplicate" | "error" | "history.page" | "history.search" | "message.copy" | "message.quote" | "settings.snapshot" | "assistants.list" | "assistants.detail" | "memory.list" | "memory.search" | "knowledge.list" | "knowledge.documents" | "tasks.list" | "system.status" | "providers.list" | "sessions.detail";
  readonly protocolVersion?: number;
  readonly requestId?: string;
  readonly connectionId?: string;
  readonly eventSeq?: number;
  readonly cursor?: number;
  readonly generationId?: string;
  readonly turnId?: string;
  readonly sessionId?: string;
  readonly isStreaming?: boolean;
  readonly error?: string;
  /** command.accepted El mensaje de destino devuelto en el mensaje (como chat.send/chat.retry). */
  readonly messageId?: string;
  /** message.delete Recibos de respuesta: algunos fueron eliminados. */
  readonly deletedCount?: number;
  /** message.delete Recibo: la identificación omitida (por ejemplo, la que se genera y no se permite eliminar). */
  readonly skippedMessageIds?: readonly string[];
  /** message.copy / message.quote El texto de la devolución de llamada. */
  readonly content?: string;
  /** Host Bits de capacidad declarados:media / tool_call_details / message_status Esperar. La antigua Hostia está vacía. */
  readonly capabilityFlags?: readonly string[];
  readonly sessions?: readonly { id: string; title: string; assistantId: string; updatedAt: number; archived: boolean; pinned: boolean }[];
  readonly messages?: readonly HostMessage[];
  readonly pendingApprovals?: readonly { toolCallId: string; toolName: string; argumentsPreview: string }[];
  readonly settings?: HostSettingsSnapshot;
  readonly assistants?: readonly HostAssistantSummary[];
  readonly providers?: readonly HostProviderSummary[];
  readonly selectedModelId?: string;
  readonly sessionDetail?: HostSessionDetail;
  /** assistants.detail La carga; si no existe, está vacío y lleva error=assistant_not_found. */
  readonly assistantDetail?: HostAssistantDetail;
  /** memory.list / memory.search carga (predeterminado 30, límite superior 100). */
  readonly items?: readonly HostMemoryItem[];
  readonly hasMore?: boolean;
  /** knowledge.list carga. */
  readonly bases?: readonly HostKnowledgeBase[];
  /** knowledge.documents La carga (la paginación y los elementos comparten un hasMore). */
  readonly documents?: readonly HostKnowledgeDocument[];
  /** tasks.list carga. */
  readonly tasks?: readonly HostScheduledTask[];
  /** system.status carga. */
  readonly system?: HostSystemStatus;
}

function hostBase(): string {
  return (import.meta.env.VITE_HOST_BASE ?? window.location.origin).replace(/\/$/, "");
}

export async function hostPinLogin(pin: string): Promise<void> {
  const response = await fetch(`${hostBase()}/api/auth/pin-login`, {
    method: "POST",
    credentials: "include",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ pin }),
  });
  const body: unknown = await response.json();
  if (!response.ok) {
    const message = body && typeof body === "object" && "message" in body && typeof body.message === "string" ? body.message : "Host PIN Error al iniciar sesión";
    throw new Error(message);
  }
}

export async function hostLogout(): Promise<void> {
  await fetch(`${hostBase()}/api/auth/logout`, { method: "POST", credentials: "include" });
}

export type HostConnectionStatus = "connected" | "reconnecting" | "closed";

export class HostConnection {
  private readonly listeners = new Set<(event: HostEvent) => void>();
  private readonly closeListeners = new Set<() => void>();
  private readonly statusListeners = new Set<(status: HostConnectionStatus, attempt: number) => void>();
  private socket: WebSocket;
  private lastCursor = 0;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  private closed = false;
  private status: HostConnectionStatus = "connected";

  constructor(socket: WebSocket, private readonly url: string) {
    this.socket = socket;
    this.attachSocket(socket, false);
  }

  private attachSocket(socket: WebSocket, resyncOnOpen: boolean): void {
    this.socket = socket;
    socket.addEventListener("message", (event) => {
      if (typeof event.data !== "string") return;
      let parsed: unknown;
      try { parsed = JSON.parse(event.data); } catch { return; }
      if (parsed && typeof parsed === "object" && "type" in parsed) {
        const hostEvent = parsed as HostEvent;
        if (typeof hostEvent.cursor === "number") this.lastCursor = hostEvent.cursor;
        for (const listener of this.listeners) listener(hostEvent);
      }
    });
    socket.addEventListener("open", () => {
      if (this.closed || socket !== this.socket) return;
      this.reconnectAttempt = 0;
      this.notifyStatus("connected");
      if (resyncOnOpen) {
        this.send({ type: "state.sync", requestId: crypto.randomUUID() });
      }
    }, { once: true });
    const recover = () => {
      if (this.closed || socket !== this.socket) return;
      this.notifyStatus("reconnecting");
      this.scheduleReconnect();
    };
    socket.addEventListener("error", () => {
      if (this.closed || socket !== this.socket) return;
      socket.close();
      recover();
    }, { once: true });
    socket.addEventListener("close", () => {
      if (this.closed || socket !== this.socket) return;
      for (const listener of this.closeListeners) listener();
      recover();
    }, { once: true });
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer !== undefined) return;
    const delay = Math.min(15_000, 1_000 * (2 ** Math.min(this.reconnectAttempt, 4)));
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      if (this.closed) return;
      try {
        this.attachSocket(new WebSocket(this.url), true);
      } catch {
        this.scheduleReconnect();
      }
    }, delay);
  }

  private notifyStatus(status: HostConnectionStatus): void {
    this.status = status;
    // Al mismo tiempo, se proporciona el "número de reconexiones": la interfaz debe distinguir entre "Reconexión (enésima vez)" y "Reconexión abandonada" en consecuencia.
    for (const listener of this.statusListeners) listener(status, this.reconnectAttempt);
  }

  send(command: HostCommand): void {
    if (this.closed || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("Muse Host Reconectando");
    }
    const enriched = command.type === "state.sync" && command.cursor === undefined
      ? { ...command, protocolVersion: 1, cursor: this.lastCursor }
      : { ...command, protocolVersion: 1 };
    this.socket.send(JSON.stringify(enriched));
  }

  subscribe(listener: (event: HostEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onClose(listener: () => void): () => void {
    this.closeListeners.add(listener);
    return () => this.closeListeners.delete(listener);
  }

  onStatus(listener: (status: HostConnectionStatus, attempt: number) => void): () => void {
    this.statusListeners.add(listener);
    listener(this.status, this.reconnectAttempt);
    return () => this.statusListeners.delete(listener);
  }

  getCursor(): number { return this.lastCursor; }
  getStatus(): HostConnectionStatus { return this.status; }
  /** El número de reconexiones iniciadas (se restablece a cero después de una conexión exitosa). */
  getReconnectAttempt(): number { return this.reconnectAttempt; }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.reconnectTimer !== undefined) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.notifyStatus("closed");
    this.socket.close();
  }
}

export async function connectHost(): Promise<HostConnection> {
  const url = new URL(hostBase());
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/ws";
  const target = url.toString();
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      const socket = await new Promise<WebSocket>((resolve, reject) => {
        const candidate = new WebSocket(target);
        let settled = false;
        const fail = () => {
          if (!settled) {
            settled = true;
            candidate.close();
            reject(new Error("No se puede conectar Muse Host WebSocket"));
          }
        };
        candidate.addEventListener("open", () => {
          if (!settled) {
            settled = true;
            resolve(candidate);
          }
        }, { once: true });
        candidate.addEventListener("error", fail, { once: true });
        candidate.addEventListener("close", fail, { once: true });
      });
      return new HostConnection(socket, target);
    } catch (error) {
      if (attempt === 4) throw error;
      await new Promise((resolve) => setTimeout(resolve, 500 * (attempt + 1)));
    }
  }
  throw new Error("No se puede conectar Muse Host WebSocket");
}

export interface StreamChatInput {
  readonly session: ClientSession;
  readonly sessionId: string;
  readonly messages: readonly ChatMessage[];
  readonly provider: ProviderConfig;
  readonly providerId?: string;
  readonly model: string;
  readonly apiKey: string;
  readonly mode?: "byok" | "hosted";
  readonly transport?: "direct" | "proxy";
  readonly signal?: AbortSignal;
}

export async function* streamDirectByok(input: Omit<StreamChatInput, "session" | "sessionId">): AsyncGenerator<ChatStreamEvent> {
  const transport = new FetchProviderTransport();
  yield* transport.stream({ messages: input.messages, provider: input.provider, model: input.model, mode: "byok" }, input.apiKey, input.signal ?? new AbortController().signal);
}

export async function* streamChat(input: StreamChatInput): AsyncGenerator<ChatStreamEvent> {
  const headers = new Headers({ "content-type": "application/json", authorization: `Bearer ${input.session.token}` });
  if ((input.mode ?? "byok") === "byok") headers.set("x-muse-provider-key", input.apiKey);
  const response = await fetch(`${API_BASE}/api/v1/chat/stream`, {
    method: "POST",
    headers,
    body: JSON.stringify({ sessionId: input.sessionId, messages: input.messages, provider: input.provider, providerId: input.providerId, model: input.model, mode: input.mode ?? "byok" }),
    ...(input.signal === undefined ? {} : { signal: input.signal }),
  });
  if (!response.ok || !response.body) throw new Error(`chat request failed (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      buffer += decoder.decode(part.value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) {
        const data = frame.split("\n").find((line) => line.startsWith("data:"))?.slice(5).trim();
        if (!data) continue;
        yield JSON.parse(data) as ChatStreamEvent;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
