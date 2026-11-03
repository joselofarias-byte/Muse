import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { hostLogout, type HostMessage } from "../api.js";
import { ArrowDownIcon, CloseIcon, MenuIcon, SendIcon, StopIcon } from "../components/icons.js";
import { ApprovalCards } from "./ApprovalCards.js";
import { AssistantPicker } from "./AssistantPicker.js";
import { BlockBoundary } from "./BlockBoundary.js";
import { readCapabilities } from "./capabilities.js";
import { buildStreamItems, toolStatusOf } from "./chatStream.js";
import { copyTextToClipboard } from "./clipboard.js";
import { composerDisabledHint, connectionBanner } from "./connectionStatus.js";
import { HostAvatar } from "./HostAvatar.js";
import { HostSettings } from "./HostSettings.js";
import { ImageLightbox } from "./ImageLightbox.js";
import { deleteConfirmKey, quoteExcerpt, selectionCopyText, type MessageActionId } from "./messageActions.js";
import { MessageBubble } from "./MessageBubble.js";
import { MessageErrorBoundary } from "./MessageErrorBoundary.js";
import { ScrollMemory } from "./scrollMemory.js";
import { formatPerfLine, perfFlag } from "./perf.js";
import { assistantIdForNewSession, shouldAskAssistant } from "./sessionActions.js";
import { useMessageWindow } from "./useMessageWindow.js";
import { SessionDrawer, SessionSidebar } from "./SessionDrawer.js";
import { useAppearance } from "./useAppearance.js";
import { useDelayedFlag } from "./useDelayedFlag.js";
import { useHostConnection } from "./useHostConnection.js";
import { useOnline } from "./useOnline.js";
import { I18nProvider } from "../i18n/I18nProvider.js";
import { DEFAULT_LOCALE } from "../i18n/locale.js";
import { formatNumber } from "../i18n/format.js";
import { createTranslator, type Translate } from "../i18n/translate.js";
import { useHostLocale } from "./useLocale.js";

interface HostChatProps {
  readonly onLogout: () => void;
}

const STATUS_KEY = { connected: "status.connected", reconnecting: "status.reconnecting", closed: "status.closed" } as const;
const TOAST_MS = 6000;

/** Track the iOS visual viewport so the composer stays glued above the keyboard. */
function useVisualViewport(): void {
  useEffect(() => {
    const viewport = window.visualViewport;
    const root = document.documentElement;
    const apply = () => {
      if (!viewport) return;
      root.style.setProperty("--host-vvh", `${Math.round(viewport.height)}px`);
      root.style.setProperty("--host-vv-top", `${Math.round(viewport.offsetTop)}px`);
    };
    apply();
    viewport?.addEventListener("resize", apply);
    viewport?.addEventListener("scroll", apply);
    return () => {
      viewport?.removeEventListener("resize", apply);
      viewport?.removeEventListener("scroll", apply);
      root.style.removeProperty("--host-vvh");
      root.style.removeProperty("--host-vv-top");
    };
  }, []);
}

export function HostChat({ onLogout }: HostChatProps) {
  const [toast, setToast] = useState<string | undefined>(undefined);
  const toastTimer = useRef<number | undefined>(undefined);
  const pushToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimer.current !== undefined) window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(undefined), TOAST_MS);
  }, []);

  // El traductor primero crea un reenvío estable en el idioma predeterminado (hook La opción debe estar disponible antes de obtener la configuración),
  // Una vez analizado el lenguaje real, solo se cambia la implementación y la referencia de la función de reenvío permanece sin cambios.
  const translatorRef = useRef(createTranslator(DEFAULT_LOCALE));
  const translate: Translate = useCallback((key, params) => translatorRef.current(key, params), []);
  const { status, reconnectAttempt, ready, snapshot, previews, assistants, providers, assistantDetails, assistantDetailUnsupported, memory, knowledge, knowledgeDocuments, tasks, system, settings, send, sendOptimistic, requestPreviews, requestAssistants, requestProviders, requestAssistantDetail, requestMemory, requestKnowledge, requestKnowledgeDocuments, requestTasks, requestSystemStatus, requestSettings, updateSetting, updateSettingValue, retry } = useHostConnection({
    onError: pushToast,
    // perf Sincronización: En el momento en que aterriza la instantánea (solo en ?perf=1 utilizado cuando).
    onSnapshot: () => {
      snapshotAtRef.current = performance.now();
    },
    translate,
  });
  const { sessions, messages, sessionId, isStreaming, approvals } = snapshot;
  const [everConnected, setEverConnected] = useState(false);
  /** El estado de la red del propio navegador: \"Reconectando\" no se muestra cuando está desconectado. */
  const online = useOnline();
  /** Idioma de la interfaz: idioma en la configuración (system Presione el idioma del navegador); una vez que cambia el valor, todo el árbol cambia de idioma inmediatamente. */
  const locale = useHostLocale(settings);
  const t = useMemo(() => createTranslator(locale), [locale]);
  translatorRef.current = t;
  /** El primer esqueleto de pantalla tiene un retraso de 300 ms para evitar que pase rápidamente. */
  const chatSkeletonDue = useDelayedFlag(!ready);

  const [draft, setDraft] = useState("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [showJump, setShowJump] = useState(false);
  /** Configurar la página de inicio es una capa de páginas en este shell:"chat" esta charlando,"settings" es configurar la página de inicio. */
  const [view, setView] = useState<"chat" | "settings">("chat");
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const stickRef = useRef(true);
  /** Sólo un mensaje ilumina la entrada de copia al mismo tiempo; lo que se almacena es la clave del elemento de renderizado. */
  const [activeKey, setActiveKey] = useState<string | undefined>(undefined);
  /** La dirección de la imagen que se está viendo en pantalla completa. */
  const [previewImage, setPreviewImage] = useState<string | undefined>(undefined);
  /** Si la columna izquierda del archivo de escritorio está contraída (los archivos de dispositivos celulares/tabletas no la leen). */
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  /** Modo de selección múltiple: la parte superior se reemplaza por una barra de operaciones y aparece una casilla de verificación en el lado izquierdo de cada mensaje. */
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<readonly string[]>([]);
  /** Eliminación optimista: primero ocúltelo localmente,Host Si te niegas, devuélvelo. */
  const [removedIds, setRemovedIds] = useState<readonly string[]>([]);
  /** Colección optimista:Host Cuando el campo favorito no se envía, confíe en él para traducir la copia del menú a \"Cancelar favorito\". */
  const [favoriteOverrides, setFavoriteOverrides] = useState<Readonly<Record<string, boolean>>>({});
  /** Borrador de cotización: el que está encima del área de entrada. */
  const [quoteDraft, setQuoteDraft] = useState<{ readonly messageId: string; readonly excerpt: string } | undefined>(undefined);
  /** Las sesiones de archivo están ocultas de forma predeterminada y el cajón/columna izquierda comparte un interruptor. */
  const [showArchived, setShowArchived] = useState(false);
  /** Al crear una nueva sesión, selecciona un asistente: cuando haya más de un asistente, este jugará primero. */
  const [pickerOpen, setPickerOpen] = useState(false);
  /** Sesión que se cambia a: Aviso de carga ligera durante el cambio. */
  const [switchingTo, setSwitchingTo] = useState<string | undefined>(undefined);
  /** La última posición de desplazamiento por sesión (solo memoria). */
  const scrollMemoryRef = useRef(new ScrollMemory());
  /** ?perf=1 El interruptor de depuración y la \"hora de llegada de la instantánea\" generalmente no están activados. */
  const perfOn = useRef(typeof window === "undefined" ? false : perfFlag(window.location.search));
  const snapshotAtRef = useRef(0);
  /** Los últimos datos que leerá la devolución de llamada estable (memo Después de la renderización, el cierre se detendrá en la renderización anterior y no podrá capturar el estado directamente). */
  const messagesRef = useRef<readonly HostMessage[]>([]);
  const sessionIdRef = useRef<string | undefined>(undefined);
  const sessionsRef = useRef(sessions);
  const favoriteOverridesRef = useRef<Readonly<Record<string, boolean>>>({});
  /** Después de cambiar de sesión, espere hasta que se cambie el contenido y luego colóquelo nuevamente. */
  const pendingRestoreRef = useRef<{ readonly sessionId: string; readonly top: number } | undefined>(undefined);

  useVisualViewport();
  useEffect(() => () => {
    if (toastTimer.current !== undefined) window.clearTimeout(toastTimer.current);
  }, []);
  useEffect(() => {
    if (status === "connected") setEverConnected(true);
  }, [status]);
  // La lista de asistentes se recupera cada vez que te conectas: debes seleccionar un asistente al crear una nueva sesión, y el nombre y el avatar deben mostrarse en la parte superior.
  useEffect(() => {
    if (status === "connected") requestAssistants();
  }, [status, requestAssistants]);
  // La apariencia (tema/tamaño de fuente) también depende de la instantánea de configuración: simplemente levántela una vez, sin esperar a que el usuario abra la página de configuración.
  useEffect(() => {
    if (status === "connected") requestSettings();
  }, [status, requestSettings]);
  // Escriba tema de datos / escala de fuente de datos en el elemento raíz; reescribir cuando el valor cambie (incluida la reversión).
  useAppearance(settings);
  // Se completa el cambio de sesión (el ID de sesión en la instantánea se ha actualizado): cierre el mensaje de cambio.
  useEffect(() => {
    if (switchingTo !== undefined && switchingTo === sessionId) setSwitchingTo(undefined);
  }, [sessionId, switchingTo]);
  // El sessionId no cambiará cuando el cambio falla (por ejemplo, otro dispositivo quita el foco): dé un tiempo de espera y no permita que el mensaje se cuelgue.
  useEffect(() => {
    if (switchingTo === undefined) return;
    const timer = window.setTimeout(() => setSwitchingTo(undefined), 4000);
    return () => window.clearTimeout(timer);
  }, [switchingTo]);
  // Para configurar la página de inicio, solo necesita tomar la instantánea de configuración una vez cuando la abra; se tomará nuevamente cuando se vuelva a conectar.
  useEffect(() => {
    if (view !== "settings" || status !== "connected") return;
    requestSettings();
  }, [view, status, requestSettings]);
  useEffect(() => {
    document.body.classList.add("host-locked");
    return () => document.body.classList.remove("host-locked");
  }, []);

  const currentSession = sessions.find((session) => session.id === sessionId);
  /** Asistente para la sesión actual: Dingqi muestra el nombre y el avatar; Da una guía cuando no hay sesión. */
  const currentAssistant =
    currentSession === undefined ? undefined : assistants.find((assistant) => assistant.id === currentSession.assistantId);

  /** Los mensajes que se hayan eliminado localmente de forma optimista ya no entrarán en la lista y el resto del orden será exactamente como lo indique el anfitrión. */
  const visibleMessages = useMemo(
    () => (removedIds.length === 0 ? messages : messages.filter((message) => !removedIds.includes(message.id))),
    [messages, removedIds],
  );
  /** Lista de flujo de mensajes: Insertar separación horaria por día. La barra separadora participa en la representación de la lista pero no en el juicio \"inferior\". */
  const items = useMemo(() => buildStreamItems(visibleMessages, Date.now(), locale), [visibleMessages, locale]);
  /** Ventanas de lista larga: solo renderiza el área visible cuando se excede el umbral ± 20, ocupando alturas iguales en ambos extremos. */
  const windowPlan = useMessageWindow({
    scrollRef,
    itemCount: items.length,
    pinnedToEnd: stickRef.current,
    resetKey: sessionId ?? "",
  });
  /** Una colección de toolCallIds pendiente de aprobación, utilizada para marcar bloques de herramientas como \"pendientes de confirmación\". */
  const approvalIds = useMemo(() => new Set(approvals.map((approval) => approval.toolCallId)), [approvals]);
  /** Host Bit de capacidad declarada: los medios faltantes no representan imágenes/Voz, falta message_status no muestra el estado y vuelve a intentarlo. */
  const capabilities = useMemo(() => readCapabilities(snapshot.capabilities), [snapshot.capabilities]);

  function scrollToBottom(smooth = false) {
    const element = scrollRef.current;
    if (!element) return;
    stickRef.current = true;
    setShowJump(false);
    element.scrollTo({ top: element.scrollHeight, behavior: smooth ? "smooth" : "auto" });
  }

  // Cambiar sesión: elimine la última posición de desplazamiento de este artículo; si no está grabado, pégalo en la parte inferior.
  // handleScroll registra continuamente la posición anterior y no se volverá a registrar aquí.——Después de que la instantánea cambia el contenido, es posible que scrollTop haya sido
  // El nuevo contenido está muy truncado y se recordará el valor incorrecto al leerlo.
  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (sessionId === undefined) return;
    const saved = scrollMemoryRef.current.recall(sessionId);
    if (saved === undefined) {
      stickRef.current = true;
      setShowJump(false);
      return;
    }
    // En este momento, es posible que el contenido de la lista aún sea el contenido de la sesión anterior y la altura no coincida: suéltelo una vez primero y luego suéltelo nuevamente después de cambiar el contenido.
    pendingRestoreRef.current = { sessionId, top: saved };
    stickRef.current = false;
    setShowJump(saved > 0);
    if (element !== null) element.scrollTop = saved;
  }, [sessionId]);

  // Después de que el contenido cambie: se colocará la posición a restaurar; de lo contrario, se mantendrá el "seguir en la parte inferior" original.
  // Confíe en elementos en lugar de mensajes: los intervalos de tiempo recién insertados también cambiarán la altura de la lista, también hacia la parte inferior.
  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (element === null) return;
    const pending = pendingRestoreRef.current;
    if (pending !== undefined && pending.sessionId === sessionId) {
      element.scrollTop = pending.top;
      pendingRestoreRef.current = undefined;
      return;
    }
    if (!stickRef.current) return;
    element.scrollTop = element.scrollHeight;
  }, [items, sessionId]);

  // ?perf=1: Poner "instantánea recibida" → La hora y el número de envíos de renderizado se informan a la consola (sin sobrecarga cuando no se proporcionan parámetros).
  useLayoutEffect(() => {
    if (!perfOn.current || snapshotAtRef.current === 0) return;
    const elapsed = performance.now() - snapshotAtRef.current;
    snapshotAtRef.current = 0;
    console.info(formatPerfLine(messages.length, elapsed));
  }, [messages]);

  // Keyboard opening/closing changes the scroll container height.
  useEffect(() => {
    const viewport = window.visualViewport;
    const onResize = () => {
      if (stickRef.current) scrollToBottom();
    };
    viewport?.addEventListener("resize", onResize);
    return () => viewport?.removeEventListener("resize", onResize);
  }, []);

  useEffect(() => {
    const element = inputRef.current;
    if (!element) return;
    element.style.height = "auto";
    element.style.height = `${Math.min(element.scrollHeight, 132)}px`;
  }, [draft]);

  function handleScroll() {
    const element = scrollRef.current;
    if (!element) return;
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight;
    const atBottom = distance < 80;
    stickRef.current = atBottom;
    setShowJump((current) => (current === !atBottom ? current : !atBottom));
    // Cada sesión recuerda su posición y no comienza de nuevo cuando vuelve a cambiar.
    if (sessionId !== undefined) scrollMemoryRef.current.remember(sessionId, element.scrollTop);
  }

  function submit() {
    const text = draft.trim();
    if (!text || isStreaming || status !== "connected") return;
    setDraft("");
    setShowJump(false);
    const quoted = quoteDraft;
    setQuoteDraft(undefined);
    stickRef.current = true;
    send({
      type: "chat.send",
      ...(sessionId === undefined ? {} : { sessionId }),
      text,
      // Las referencias se envían con chat.send con el id del mensaje al que se hace referencia:Host El nombre dado a este campo es quotedMessageId.
      ...(quoted === undefined ? {} : { quotedMessageId: quoted.messageId }),
    });
    window.requestAnimationFrame(() => scrollToBottom(true));
  }

  const writeCommand = send;

  function selectSession(nextId: string) {
    setDrawerOpen(false);
    setSelectionMode(false);
    setSelectedIds([]);
    if (nextId === sessionId) return;
    // Dé un ligero recordatorio durante el cambio (el contenido antiguo todavía está allí, no hay pantalla blanca) y la instantánea desaparecerá automáticamente después de que se ponga al día.
    setSwitchingTo(nextId);
    writeCommand({ type: "session.select", sessionId: nextId });
  }

  function newSession() {
    setDrawerOpen(false);
    stickRef.current = true;
    // Si hay más de un asistente, dejar que el usuario elija primero; si uno se usa directamente, el otro se entrega al asistente predeterminado del anfitrión.
    if (shouldAskAssistant(assistants)) {
      setPickerOpen(true);
      return;
    }
    const assistantId = assistantIdForNewSession(assistants);
    writeCommand({ type: "session.new", ...(assistantId === undefined ? {} : { assistantId }) });
  }

  function createSessionWith(assistantId: string) {
    setPickerOpen(false);
    setDrawerOpen(false);
    stickRef.current = true;
    writeCommand({ type: "session.new", assistantId });
  }

  /** Cambie el nombre del título proporcionado directamente mediante la entrada en línea (los nombres vacíos se bloquean en el panel de sesión). */
  function renameSession(targetId: string, title: string) {
    const trimmed = title.trim();
    if (trimmed.length === 0) return;
    writeCommand({ type: "session.rename", sessionId: targetId, title: trimmed });
  }

  function archiveSession(targetId: string) {
    writeCommand({ type: "session.archive", sessionId: targetId, archived: true });
  }

  function deleteSession(targetId: string) {
    const title = sessions.find((session) => session.id === targetId)?.title ?? "";
    // La copia debe indicar claramente "conversación + todos los mensajes", no sólo "irrevocable".
    if (window.confirm(t("sessions.deleteConfirm", { name: title.trim() || t("sessions.untitled") }))) writeCommand({ type: "session.delete", sessionId: targetId });
  }

  function resolveApproval(toolCallId: string, decision: "approved" | "denied") {
    writeCommand({ type: "tool.approval.resolve", generationId: toolCallId, text: decision });
  }

  function stop() {
    writeCommand({ type: "chat.stop", ...(sessionId === undefined ? {} : { sessionId }) });
  }

  function logout() {
    setDrawerOpen(false);
    void hostLogout().catch(() => undefined);
    onLogout();
  }

  const canCompose = status === "connected" && online;
  const lastMessageIndex = visibleMessages.length - 1;
  const lastAssistantIndex = (() => {
    for (let index = visibleMessages.length - 1; index >= 0; index -= 1) {
      if (visibleMessages[index]?.role === "assistant") return index;
    }
    return -1;
  })();
  const isFavorited = (messageId: string, fallback: boolean | undefined): boolean => favoriteOverrides[messageId] ?? fallback === true;

  // Coloque los datos más recientes en referencia para cada representación: las siguientes devoluciones de llamada son referencias fijas que utilizan useCallback y dependen de referencia para obtener nuevos valores.
  messagesRef.current = visibleMessages;
  sessionIdRef.current = sessionId;
  sessionsRef.current = sessions;
  favoriteOverridesRef.current = favoriteOverrides;

  /** Copiar texto: cuando la página web no está en el contexto seguro, la API del Portapapeles no existe, por lo que tenemos que ver si podemos recurrir a execCommand. */
  const copyMessage = useCallback(
    async (text: string) => {
      const copied = await copyTextToClipboard(text);
      pushToast(copied ? t("chat.copied") : t("chat.copyFailed"));
    },
    [pushToast],
  );

  /** Cita: el resumen se escribe primero localmente (no de ida y vuelta) y el quotedMessageId se incluye con chat.send al enviar. */
  const quoteMessageById = useCallback((messageId: string) => {
    const message = messagesRef.current.find((item) => item.id === messageId);
    if (message === undefined) return;
    setActiveKey(undefined);
    setQuoteDraft({ messageId, excerpt: quoteExcerpt(message.content) });
    inputRef.current?.focus();
  }, []);

  /** Cobro/cancelación de cobro: primero verifique el estado local,Host Cuando sea rechazado, retírese y explique por qué. */
  const toggleFavoriteById = useCallback(
    (messageId: string) => {
      const message = messagesRef.current.find((item) => item.id === messageId);
      if (message === undefined) return;
      const next = !(favoriteOverridesRef.current[messageId] ?? message.favorite === true);
      setActiveKey(undefined);
      setFavoriteOverrides((current) => ({ ...current, [messageId]: next }));
      sendOptimistic({ type: "message.favorite", messageId, favored: next }, () => {
        setFavoriteOverrides((current) => {
          const restored = { ...current };
          delete restored[messageId];
          return restored;
        });
      });
    },
    [sendOptimistic],
  );

  /** Eliminar (selección única o múltiple): Confirme dos veces antes de eliminarlo de la lista con optimismo; si falla, vuelva a colocarlo. */
  const deleteMessages = useCallback(
    (ids: readonly string[]) => {
      if (ids.length === 0) return;
      if (!window.confirm(t(deleteConfirmKey(ids.length), { count: ids.length }))) return;
      setActiveKey(undefined);
      setRemovedIds((current) => [...current, ...ids.filter((id) => !current.includes(id))]);
      setSelectedIds([]);
      setSelectionMode(false);
      sendOptimistic(
        { type: "message.delete", messageIds: [...ids] },
        () => {
          // Host Rechazo: Devolver el mensaje que ha ocultado el optimismo.
          setRemovedIds((current) => current.filter((id) => !ids.includes(id)));
        },
        (event) => {
          // Para artículos que se omitieron por nombre en el recibo (como el que se está generando): restaure la visualización e indique el motivo.
          const skipped = event.skippedMessageIds ?? [];
          if (skipped.length === 0) return;
          setRemovedIds((current) => current.filter((id) => !skipped.includes(id)));
          pushToast(t("chat.deleteSkipped", { count: skipped.length }));
        },
      );
    },
    [sendOptimistic, pushToast],
  );

  /** Distribución del menú: solo tome el ID del mensaje, la devolución de llamada en sí mantiene una referencia estable (memo es eficaz). */
  const handleMessageAction = useCallback(
    (messageId: string, action: MessageActionId) => {
      switch (action) {
        case "quote":
          quoteMessageById(messageId);
          return;
        case "favorite":
        case "unfavorite":
          toggleFavoriteById(messageId);
          return;
        case "delete":
          deleteMessages([messageId]);
          return;
        case "select":
          setActiveKey(undefined);
          setSelectionMode(true);
          setSelectedIds([messageId]);
          return;
        default:
          return;
      }
    },
    [quoteMessageById, toggleFavoriteById, deleteMessages],
  );

  const toggleActions = useCallback((messageId: string) => {
    setActiveKey((current) => (current === messageId ? undefined : messageId));
  }, []);

  const toggleSelected = useCallback((messageId: string) => {
    setSelectedIds((current) =>
      current.includes(messageId) ? current.filter((id) => id !== messageId) : [...current, messageId],
    );
  }, []);

  const retryMessage = useCallback(
    (messageId: string) => {
      send({ type: "chat.retry", messageId });
    },
    [send],
  );

  const regenerate = useCallback(() => {
    const id = sessionIdRef.current;
    send({ type: "chat.regenerate", ...(id === undefined ? {} : { sessionId: id }) });
  }, [send]);

  const continueGeneration = useCallback(() => {
    const id = sessionIdRef.current;
    send({ type: "chat.continue", ...(id === undefined ? {} : { sessionId: id }) });
  }, [send]);

  const openImage = useCallback((src: string) => setPreviewImage(src), []);

  /** Inicie una conversación directamente desde los detalles del asistente: regrese a la página de chat y traiga al asistente con usted. */
  const startSessionWith = useCallback(
    (assistantId: string) => {
      setView("chat");
      stickRef.current = true;
      send({ type: "session.new", assistantId });
    },
    [send],
  );

  function exitSelection() {
    setSelectionMode(false);
    setSelectedIds([]);
  }

  function selectAllMessages() {
    setSelectedIds(visibleMessages.map((message) => message.id));
  }

  function copySelected() {
    const picked = visibleMessages.filter((message) => selectedIds.includes(message.id));
    void copyMessage(selectionCopyText(picked));
  }

  function favoriteSelected() {
    const picked = visibleMessages.filter((message) => selectedIds.includes(message.id));
    for (const message of picked) {
      if (!isFavorited(message.id, message.favorite)) toggleFavoriteById(message.id);
    }
    exitSelection();
  }

  // Al cambiar de conversación, no debe llevar consigo la barra de operación del mensaje anterior, el estado de selección múltiple ni la imagen de pantalla completa.
  useEffect(() => {
    setActiveKey(undefined);
    setPreviewImage(undefined);
    setSelectionMode(false);
    setSelectedIds([]);
    setQuoteDraft(undefined);
  }, [sessionId]);

  // Esc: Primero cierre el menú de mensajes, luego salga de la selección múltiple.
  useEffect(() => {
    if (activeKey === undefined && !selectionMode) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setActiveKey(undefined);
      if (selectionMode) {
        setSelectionMode(false);
        setSelectedIds([]);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activeKey, selectionMode]);

  const bannerModel = connectionBanner({ online, status, attempt: reconnectAttempt, everConnected });
  const composerHintKey = composerDisabledHint({ online, status });
  const composerHint = composerHintKey === undefined ? undefined : t(composerHintKey);
  const banner = bannerModel.visible && (
    <div className={`host-banner is-${bannerModel.tone}`} role="status" aria-live="polite">
      <span>{t(bannerModel.textKey, bannerModel.params)}</span>
      {bannerModel.retry && (
        <button className="quiet host-banner-action" type="button" onClick={retry}>
          {t("connection.manualRetry")}
        </button>
      )}
    </div>
  );

  const toastNode = toast !== undefined && (
    <div className="host-toast" role="alert" onClick={() => setToast(undefined)}>
      {toast}
    </div>
  );

  // Al configurar la página de inicio, también necesita ver un mensaje de conexión: cuando se desconecta, el usuario no puede enfrentarse a una página de configuración que siempre girará en círculo.
  if (view === "settings") {
    return (
      <I18nProvider locale={locale}>
      <div className="host-root host-scope is-settings">
        {banner}
        <BlockBoundary name={t("block.settings")}>
          <HostSettings
            settings={settings}
            capabilities={capabilities}
            assistants={assistants}
            providers={providers}
            assistantDetails={assistantDetails}
            assistantDetailUnsupported={assistantDetailUnsupported}
            memory={memory}
            knowledge={knowledge}
            knowledgeDocuments={knowledgeDocuments}
            tasks={tasks}
            system={system}
            capabilityFlags={snapshot.capabilities}
            onRequestAssistantDetail={requestAssistantDetail}
            onRequestProviders={requestProviders}
            onRequestMemory={requestMemory}
            onRequestKnowledge={requestKnowledge}
            onRequestKnowledgeDocuments={requestKnowledgeDocuments}
            onRequestTasks={requestTasks}
            onRequestSystemStatus={requestSystemStatus}
            onStartSession={startSessionWith}
            onBack={() => setView("chat")}
            onRetry={requestSettings}
            onToggle={updateSetting}
            onWrite={updateSettingValue}
          />
        </BlockBoundary>
        {toastNode}
      </div>
      </I18nProvider>
    );
  }

  function openSettings() {
    setDrawerOpen(false);
    setView("settings");
  }

  return (
    <I18nProvider locale={locale}>
    <div className={sidebarCollapsed ? "host-root host-scope is-sidebar-collapsed" : "host-root host-scope"}>
      {/* Archivo de escritorio (≥1024) está ubicado permanentemente en la columna de la izquierda y se puede contraer; Los teléfonos celulares/tabletas todavía usan cajones, y CSS los dos se excluyen mutuamente. */}
      <BlockBoundary name={t("block.sessions")}>
        <SessionSidebar
          sessions={sessions}
          ready={ready}
          showArchived={showArchived}
          onToggleArchived={() => setShowArchived((current) => !current)}
          currentId={sessionId}
          previews={previews}
          buildId={__MUSE_BUILD_ID__}
          onSelect={selectSession}
          onNew={newSession}
          onRequestPreviews={requestPreviews}
          onRename={renameSession}
          onArchive={archiveSession}
          onDelete={deleteSession}
          onOpenSettings={openSettings}
          onLogout={logout}
          collapsed={sidebarCollapsed}
          onToggleSidebar={() => setSidebarCollapsed((current) => !current)}
        />
      </BlockBoundary>

      <div className="host-main">
        {selectionMode ? (
          <header className="host-topbar host-selection-topbar">
            <button className="quiet host-icon-button" type="button" aria-label={t("chat.exitSelection")} onClick={exitSelection}>
              <CloseIcon />
            </button>
            <div className="host-topbar-title">
              <h1>{t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })}</h1>
            </div>
            <button
              className="quiet host-selection-all"
              type="button"
              onClick={selectAllMessages}
              disabled={visibleMessages.length === 0}
            >
              {t("chat.selectAll")}
            </button>
          </header>
        ) : (
        <header className="host-topbar">
          {/* El archivo del escritorio contrae la columna izquierda y luego la expande; el archivo del celular/tableta está oculto por CSS.☰ Todavía la entrada del cajón. */}
          <button
            className="quiet host-icon-button host-sidebar-toggle"
            type="button"
            aria-label={t("chat.menuExpand")}
            onClick={() => setSidebarCollapsed(false)}
          >
            <MenuIcon />
          </button>
          <button
            className="quiet host-icon-button host-menu-button"
            type="button"
            aria-label={t("chat.menu")}
            onClick={() => setDrawerOpen(true)}
          >
            <MenuIcon />
          </button>
          <div className="host-topbar-title">
            <span className="host-topbar-head">
              {currentAssistant !== undefined && (
                <HostAvatar name={currentAssistant.name} uri={currentAssistant.avatarUri} />
              )}
              <h1>{currentSession?.title ?? t("chat.pickAssistant")}</h1>
            </span>
            <p>
              {currentAssistant !== undefined ? `${currentAssistant.name} · ` : ""}
              {online ? t(STATUS_KEY[status]) : t("status.offline")}
              {isStreaming ? ` · ${t("status.generating")}` : ""}
            </p>
          </div>
          <span
            className={`host-status-dot ${status}`}
            role="status"
            aria-label={t("connection.stateAria", { status: t(STATUS_KEY[status]) })}
            title={t(STATUS_KEY[status])}
          />
        </header>
        )}

        {banner}

        {switchingTo !== undefined && switchingTo !== sessionId && (
          <div className="host-switch-loading" role="status" aria-live="polite">
            {t("chat.switching")}
          </div>
        )}

        <div className="host-scroll" ref={scrollRef} onScroll={handleScroll}>
          <BlockBoundary name={t("block.messages")}>
          <div className="host-thread" role="log" aria-live="polite" aria-label={t("chat.threadAria")}>
            {!ready && chatSkeletonDue && (
              <div className="host-chat-skeleton" role="status" aria-label={t("chat.loadingThread")}>
                {[0, 1, 2, 3].map((index) => (
                  <div className={index % 2 === 1 ? "host-chat-skeleton-row is-user" : "host-chat-skeleton-row"} key={index}>
                    <span className="muse-skeleton" />
                    <span className="muse-skeleton is-thin" />
                  </div>
                ))}
              </div>
            )}
            {ready && sessions.length === 0 && (
              <div className="host-empty">
                <h2>{t("chat.emptyNoSessionsTitle")}</h2>
                <p>{t("chat.emptyNoSessionsBody")}</p>
                <button type="button" onClick={newSession}>
                  {t("chat.newSession")}
                </button>
              </div>
            )}
            {ready && sessions.length > 0 && messages.length === 0 && (
              <div className="host-empty">
                <h2>{t("chat.emptyNoMessagesTitle")}</h2>
                <p>{t("chat.emptyNoMessagesBody")}</p>
              </div>
            )}
            {windowPlan.topPad > 0 && <div className="host-window-pad" style={{ height: windowPlan.topPad }} aria-hidden="true" />}
            {items.slice(windowPlan.start, windowPlan.end).map((item) =>
              item.kind === "divider" ? (
                <div className="host-day-sep" key={item.key} role="separator" data-host-row>
                  <span className="host-day-sep-line" aria-hidden="true" />
                  <span className="host-day-sep-label">{item.label}</span>
                  <span className="host-day-sep-line" aria-hidden="true" />
                </div>
              ) : (
                <div
                  key={item.key}
                  data-host-row
                  data-new={item.index === lastMessageIndex ? "true" : undefined}
                  className={`host-msg-row${item.message.role === "user" ? " is-user" : ""}${selectionMode ? " is-selecting" : ""}`}
                >
                  {selectionMode && (
                    <button
                      className={selectedIds.includes(item.message.id) ? "host-select is-on" : "host-select"}
                      type="button"
                      role="checkbox"
                      aria-checked={selectedIds.includes(item.message.id)}
                      aria-label={selectedIds.includes(item.message.id) ? t("chat.deselectMessage") : t("chat.selectMessage")}
                      onClick={() => toggleSelected(item.message.id)}
                    >
                      <span className="host-select-mark" aria-hidden="true" />
                    </button>
                  )}
                  <MessageErrorBoundary resetKey={`${item.key}:${item.message.content.length}:${item.message.reasoning?.length ?? 0}`}>
                  <MessageBubble
                    message={item.message}
                    streaming={isStreaming && item.index === lastMessageIndex && item.message.role === "assistant"}
                    showActions={item.index === lastMessageIndex && item.index === lastAssistantIndex && !isStreaming}
                    capabilities={capabilities}
                    favorited={isFavorited(item.message.id, item.message.favorite)}
                    toolStatus={
                      item.message.toolName === undefined && item.message.role !== "tool"
                        ? undefined
                        : toolStatusOf(item.message, {
                            isStreaming,
                            isLast: item.index === lastMessageIndex,
                            approvalToolCallIds: approvalIds,
                          })
                    }
                    actionsOpen={activeKey === item.message.id}
                    onToggleActions={toggleActions}
                    selectionMode={selectionMode}
                    onToggleSelect={toggleSelected}
                    onAction={handleMessageAction}
                    onRetry={retryMessage}
                    onOpenImage={openImage}
                    onCopy={copyMessage}
                    onRegenerate={regenerate}
                    onContinue={continueGeneration}
                  />
                  </MessageErrorBoundary>
                </div>
              ),
            )}
            {windowPlan.bottomPad > 0 && <div className="host-window-pad" style={{ height: windowPlan.bottomPad }} aria-hidden="true" />}
          </div>
          </BlockBoundary>
          {showJump && (
            <div className="host-jump-row">
              <button className="host-jump" type="button" onClick={() => scrollToBottom(true)}>
                <ArrowDownIcon />
                {t("chat.jumpLatest")}
              </button>
            </div>
          )}
        </div>

        {selectionMode ? (
          <footer className="host-selection-bar" role="toolbar" aria-label={t("chat.selectionTopAria")}>
            {capabilities.messageWrite && (
              <button className="quiet host-selection-danger" type="button" disabled={selectedIds.length === 0} onClick={() => deleteMessages(selectedIds)}>
                {t("common.delete")}
              </button>
            )}
            {capabilities.messageWrite && (
              <button className="quiet" type="button" disabled={selectedIds.length === 0} onClick={favoriteSelected}>
                {t("common.favorite")}
              </button>
            )}
            <button className="quiet" type="button" disabled={selectedIds.length === 0} onClick={copySelected}>
              {t("common.copy")}
            </button>
          </footer>
        ) : (
        <footer className="host-composer">
          <ApprovalCards approvals={approvals} onResolve={resolveApproval} />
          {quoteDraft !== undefined && (
            <div className="host-quote-draft">
              <span className="host-quote-draft-mark" aria-hidden="true">
                {t("message.quoteMark")}
              </span>
              <span className="host-quote-draft-text">{quoteDraft.excerpt}</span>
              <button
                className="quiet host-quote-draft-close"
                type="button"
                aria-label={t("chat.cancelQuote")}
                onClick={() => setQuoteDraft(undefined)}
              >
                <CloseIcon size={18} />
              </button>
            </div>
          )}
          {composerHint !== undefined && (
            <p className="host-composer-hint" id="host-composer-hint">
              {composerHint}
            </p>
          )}
          <div className="host-input-shell">
            <textarea
              ref={inputRef}
              className="host-input"
              aria-label={t("composer.ariaMessage")}
              placeholder={composerHint ?? (canCompose ? t("composer.placeholder") : t("composer.placeholderConnecting"))}
              rows={1}
              value={draft}
              enterKeyHint="send"
              autoComplete="off"
              disabled={!canCompose}
              aria-describedby={composerHint === undefined ? undefined : "host-composer-hint"}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key !== "Enter") return;
                if (event.nativeEvent.isComposing) return;
                if (event.shiftKey) return;
                event.preventDefault();
                submit();
              }}
            />
            {isStreaming ? (
              <button className="host-send is-stop" type="button" aria-label={t("composer.stop")} onClick={stop}>
                <StopIcon />
              </button>
            ) : (
              <button
                className="host-send"
                type="button"
                aria-label={t("composer.send")}
                disabled={!canCompose || !draft.trim()}
                onClick={submit}
              >
                <SendIcon />
              </button>
            )}
          </div>
        </footer>
        )}
      </div>

      <BlockBoundary name={t("block.sessions")}>
        <SessionDrawer
          open={drawerOpen}
          sessions={sessions}
          ready={ready}
          showArchived={showArchived}
          onToggleArchived={() => setShowArchived((current) => !current)}
          currentId={sessionId}
          previews={previews}
          buildId={__MUSE_BUILD_ID__}
          onClose={() => setDrawerOpen(false)}
          onSelect={selectSession}
          onNew={newSession}
          onRequestPreviews={requestPreviews}
          onRename={renameSession}
          onArchive={archiveSession}
          onDelete={deleteSession}
          onOpenSettings={openSettings}
          onLogout={logout}
        />
      </BlockBoundary>

      {toastNode}

      {pickerOpen && (
        <AssistantPicker
          assistants={assistants}
          onPick={(assistant) => createSessionWith(assistant.id)}
          onClose={() => setPickerOpen(false)}
        />
      )}

      {previewImage !== undefined && <ImageLightbox src={previewImage} onClose={() => setPreviewImage(undefined)} />}
    </div>
    </I18nProvider>
  );
}
