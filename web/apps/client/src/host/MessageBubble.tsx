import { memo, useEffect, useRef } from "react";
import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent, PointerEvent as ReactPointerEvent } from "react";
import { MessageContent } from "../components/MessageContent.js";
import { CopyIcon, SparkIcon, ToolIcon } from "../components/icons.js";
import type { HostMessage } from "../api.js";
import type { HostCapabilities } from "./capabilities.js";
import { CollapsibleBlock } from "./CollapsibleBlock.js";
import { TOOL_STATUS_KEY, type ToolStatus } from "./chatStream.js";
import { actionsForMessage, type MessageActionId, type MessageActionItem } from "./messageActions.js";
import { areBubblePropsEqual } from "./messageEqual.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { MessageImages } from "./MessageImages.js";
import { resolveMessageImages, resolveVoiceUrl } from "./messageMedia.js";
import { ToolCallBlocks } from "./ToolCallBlocks.js";
import { VoiceBar } from "./VoiceBar.js";

/** Juicio de presión prolongada: si se presiona el dedo durante más de este tiempo y no hay un movimiento obvio del dedo, se considera una presión prolongada. */
const LONG_PRESS_MS = 450;
const LONG_PRESS_SLOP = 10;

interface MessageBubbleProps {
  readonly message: HostMessage;
  /** Last assistant turn still growing: render the caret. */
  readonly streaming: boolean;
  readonly showActions: boolean;
  readonly onRegenerate: () => void;
  readonly onContinue: () => void;
  /** El estado del bloque de llamada de herramienta; El antiguo anfitrión no lo tiene. toolCalls[] Úselo para inferir. */
  readonly toolStatus?: ToolStatus | undefined;
  /** Host El bit de capacidad declarado determina la imagen./Voz/Estado/Si se deben representar los elementos del menú. */
  readonly capabilities: HostCapabilities;
  /** ¿Este mensaje está actualmente marcado como favorito (Host Si no se emite, lo dará la capa superior según el estado optimista local). */
  readonly favorited?: boolean | undefined;
  /** Copie el texto de este mensaje; Es responsabilidad de la alta dirección decir si tiene éxito o no. */
  readonly onCopy?: ((text: string) => void) | undefined;
  /** Citar/favorito/no favorito/eliminar/selección múltiple; con el ID del mensaje, la capa superior puede usar devoluciones de llamada estables. */
  readonly onAction?: ((messageId: string, action: MessageActionId) => void) | undefined;
  /** Si la barra de operaciones está expandida: solo un mensaje iluminará el menú al mismo tiempo. */
  readonly actionsOpen?: boolean | undefined;
  /** Cuando hace clic o mantiene presionada la burbuja, notifica a la capa superior para cambiar este menú. */
  readonly onToggleActions?: ((messageId: string) => void) | undefined;
  /** Vuelva a intentarlo cuando el envío falle (Host comando chat.reintentar). */
  readonly onRetry?: ((messageId: string) => void) | undefined;
  /** Haga clic en la imagen para verla en pantalla completa. */
  readonly onOpenImage?: ((src: string) => void) | undefined;
  /** Modo de selección múltiple: haga clic en la burbuja para seleccionar/Cancelar en lugar de abrir el menú. */
  readonly selectionMode?: boolean | undefined;
  readonly onToggleSelect?: ((messageId: string) => void) | undefined;
}

function toolTone(status: ToolStatus): "neutral" | "accent" | "warn" {
  if (status === "awaiting") return "warn";
  if (status === "running") return "accent";
  return "neutral";
}

/**
 * Mantenga presionado o toque el gesto en la burbuja.
 *
 * Después de activar una pulsación larga, el bit disparado se activa, el clic que sigue será tragado y el menú que acaba de abrir no se cerrará nuevamente;
 * Cuando la pulsación larga tiene éxito, el menú de selección de pulsación larga nativo del navegador se bloqueará para evitar que las dos capas de menús se superpongan.
 */
function usePressActions(onActivate: () => void) {
  const timer = useRef<number | undefined>(undefined);
  const origin = useRef<{ readonly x: number; readonly y: number } | undefined>(undefined);
  const fired = useRef(false);
  const activate = useRef(onActivate);
  activate.current = onActivate;

  const stop = () => {
    if (timer.current !== undefined) window.clearTimeout(timer.current);
    timer.current = undefined;
    origin.current = undefined;
  };
  useEffect(() => stop, []);

  return {
    fired,
    handlers: {
      onPointerDown: (event: ReactPointerEvent<HTMLElement>) => {
        fired.current = false;
        if (event.pointerType === "mouse") return;
        stop();
        origin.current = { x: event.clientX, y: event.clientY };
        timer.current = window.setTimeout(() => {
          timer.current = undefined;
          origin.current = undefined;
          fired.current = true;
          activate.current();
        }, LONG_PRESS_MS);
      },
      onPointerMove: (event: ReactPointerEvent<HTMLElement>) => {
        const from = origin.current;
        if (timer.current === undefined || from === undefined) return;
        if (Math.abs(event.clientX - from.x) > LONG_PRESS_SLOP || Math.abs(event.clientY - from.y) > LONG_PRESS_SLOP) stop();
      },
      onPointerUp: () => {
        if (!fired.current) stop();
      },
      onPointerCancel: stop,
      onPointerLeave: stop,
      onContextMenu: (event: MouseEvent<HTMLElement>) => {
        if (fired.current) event.preventDefault();
      },
    },
  };
}

/** Menú de mensajes: una pequeña cápsula que se puede envolver horizontalmente. Después de hacer clic en él, aparecerá debajo del mensaje y no saldrá de la pantalla. */
function MessageActionMenu({
  actions,
  onPick,
}: {
  readonly actions: readonly MessageActionItem[];
  readonly onPick: (action: MessageActionItem) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="host-bubble-tools">
      {actions.map((action) => (
        <button
          key={action.id}
          className={action.danger === true ? "quiet host-tool-button is-danger" : "quiet host-tool-button"}
          type="button"
          aria-label={action.id === "select" ? t(action.labelKey) : t("message.actionAria", { action: t(action.labelKey) })}
          onClick={(event) => {
            event.stopPropagation();
            onPick(action);
          }}
        >
          {action.id === "copy" && <CopyIcon />}
          <span>{t(action.labelKey)}</span>
        </button>
      ))}
    </div>
  );
}

function MessageBubbleView({  message,
  streaming,
  showActions,
  onRegenerate,
  onContinue,
  toolStatus,
  capabilities,
  favorited = false,
  onCopy,
  onAction,
  actionsOpen = false,
  onToggleActions,
  onRetry,
  onOpenImage,
  selectionMode = false,
  onToggleSelect,
}: MessageBubbleProps) {
  const { t } = useI18n();
  const press = usePressActions(() => onToggleActions?.(message.id));

  const actions = actionsForMessage({
    role: message.role,
    hasContent: message.content.trim().length > 0,
    favorited,
    capabilities,
  });
  const menuOpen = actionsOpen && actions.length > 0;

  const pick = (action: MessageActionItem) => {
    if (action.id === "copy") onCopy?.(message.content);
    else onAction?.(message.id, action.id);
  };

  const interactive = {
    tabIndex: actions.length > 0 ? 0 : undefined,
    onClick: (event: MouseEvent<HTMLElement>) => {
      if (press.fired.current) {
        press.fired.current = false;
        return;
      }
      // Haga clic en el mensaje en modo de selección múltiple = Marque esta casilla para dejar de mostrar el menú.
      if (selectionMode) {
        onToggleSelect?.(message.id);
        return;
      }
      // Hacer clic en botones/enlaces/bloques de código/controles de voz son gestos originales y no deben usarse como "menús abiertos".
      if ((event.target as HTMLElement).closest("button,a,pre,summary,audio") !== null) return;
      // No hay prisas a la hora de seleccionar texto (hacer doble clic para seleccionar una palabra, arrastrar el clic final de un gesto).
      if (window.getSelection()?.toString().length) return;
      onToggleActions?.(message.id);
    },
    onKeyDown: (event: ReactKeyboardEvent<HTMLElement>) => {
      // Equivalente en teclado a una pulsación larga:Enter / El espacio abre y cierra el menú (el camino más allá del tacto y el mouse).
      // Solo tiene efecto cuando el mensaje en sí está enfocado: no puedo quitar los encabezados, botones y enlaces internos plegables.
      if (event.target !== event.currentTarget) return;
      if (event.key !== "Enter" && event.key !== " ") return;
      if (selectionMode) return;
      event.preventDefault();
      onToggleActions?.(message.id);
    },
    ...press.handlers,
  };

  // Host Es posible que se emitan nuevos roles en el futuro: aquellos que no reconozca se tratarán como una visualización neutral en una línea y nunca se generarán excepciones.
  const role: string = message.role;

  if (role === "tool") {
    const status = toolStatus ?? "done";
    return (
      <div className={menuOpen ? "host-tool is-open" : "host-tool"} {...interactive}>
        <CollapsibleBlock
          icon={<ToolIcon />}
          label={`${t("message.toolResult")}${message.toolName ? ` · ${message.toolName}` : ""}`}
          status={t(TOOL_STATUS_KEY[status])}
          tone={toolTone(status)}
        >
          {message.content || t("message.emptyResult")}
        </CollapsibleBlock>
        {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
      </div>
    );
  }

  if (role === "system") {
    return (
      <div className={menuOpen ? "host-system is-open" : "host-system"} {...interactive}>
        {message.content}
        {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
      </div>
    );
  }

  if (role !== "user" && role !== "assistant") {
    return (
      <div className={menuOpen ? "host-msg-unknown is-open" : "host-msg-unknown"} {...interactive}>
        <span className="host-msg-unknown-label">{t("message.unknownRole")}</span>
        {message.content.length > 0 && <span className="host-msg-unknown-text">{message.content}</span>}
        {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
      </div>
    );
  }

  const isUser = role === "user";
  // Si falta la capacidad multimedia, no se renderizará todo el bloque: sería mejor perder un bloque que tener una imagen que nunca podrá cargarse.
  const images = capabilities.media ? resolveMessageImages(message.imageUrls) : [];
  const voiceSrc = capabilities.media ? resolveVoiceUrl(message.voiceUrl) : undefined;
  const hasVoice = capabilities.media && (message.voiceUrl !== undefined || (message.voiceDurationMs ?? 0) > 0);
  // El antiguo anfitrión no tiene toolCalls[], volver al nivel de mensaje nombre de herramienta + inferencia de estado.
  const calls = capabilities.toolCallDetails ? message.toolCalls ?? [] : [];
  const status = capabilities.messageStatus ? message.status : undefined;
  const quoted = message.quotedContent !== undefined && message.quotedContent.trim().length > 0 ? message.quotedContent : undefined;
  return (
    <article
      className={`${isUser ? "host-bubble is-user" : "host-bubble is-assistant"}${menuOpen ? " is-open" : ""}${selectionMode ? " is-selecting" : ""}`}
      {...interactive}
    >
      {quoted !== undefined && (
        <blockquote className="host-quote">
          <span className="host-quote-mark" aria-hidden="true">
            {t("message.quoteMark")}
          </span>
          <span className="host-quote-text">{quoted}</span>
        </blockquote>
      )}
      <MessageContent content={message.content || (streaming ? "" : "…")} />
      <MessageImages images={images} onOpen={onOpenImage} />
      {hasVoice && <VoiceBar src={voiceSrc} durationMs={message.voiceDurationMs} />}
      {message.reasoning && (
        <CollapsibleBlock icon={<SparkIcon />} label={t("message.reasoning")}>
          {message.reasoning}
        </CollapsibleBlock>
      )}
      {calls.length > 0 ? (
        <ToolCallBlocks calls={calls} />
      ) : (
        message.toolName !== undefined && (
          <CollapsibleBlock
            icon={<ToolIcon />}
            label={`${t("message.toolCall")} · ${message.toolName}`}
            status={toolStatus === undefined ? undefined : t(TOOL_STATUS_KEY[toolStatus])}
            tone={toolStatus === undefined ? "neutral" : toolTone(toolStatus)}
          >
            {message.content || t("message.noParams")}
          </CollapsibleBlock>
        )
      )}
      {status === "sending" && (
        <p className="host-msg-status" role="status">
          <span className="host-msg-status-dot" aria-hidden="true" />
          {t("message.sending")}
        </p>
      )}
      {status === "failed" && (
        <div className="host-msg-failed" role="alert">
          <span className="host-msg-failed-text">{message.errorText !== undefined && message.errorText.length > 0 ? message.errorText : t("message.sendFailed")}</span>
          {onRetry !== undefined && (
            <button
              className="quiet host-retry"
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                onRetry?.(message.id);
              }}
            >
              {t("common.retry")}
            </button>
          )}
        </div>
      )}
      {streaming && <span className="host-caret" aria-hidden="true" />}
      {showActions && !streaming && (
        <div className="host-bubble-actions">
          <button className="quiet" type="button" onClick={onRegenerate}>
            {t("message.regenerate")}
          </button>
          <button className="quiet" type="button" onClick={onContinue}>
            {t("message.continue")}
          </button>
        </div>
      )}
      {menuOpen && <MessageActionMenu actions={actions} onPick={pick} />}
    </article>
  );
}

/**
 * El componente de mensaje se subcontrata con una capa de memo: la función de comparación solo mira los campos que realmente afectan la representación (consulte areBubblePropsEqual).
 * Para una instantánea en una sesión larga, solo se volverá a renderizar la de transmisión. Todas las devoluciones de llamada las pasa la capa superior mediante funciones estables (con messageId).
 * Por lo tanto no está incluido en el rango de comparación.——De lo contrario, la nota se destruirá por completo cada vez que se realice un nuevo cierre.
 */
export const MessageBubble = memo(MessageBubbleView, areBubblePropsEqual);
