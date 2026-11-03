import type { HostConnectionStatus } from "../api.js";
import type { MessageKey } from "../i18n/messages.js";

/**
 * Lógica pura para avisos de desconexión.
 *
 * Se debe distinguir claramente la redacción publicitaria de tres estados: Reconectar (enésima vez)/ Desconectado/red desconectada (cuando el propio navegador dice desconectado)
 * No vuelva a mostrar "Reconectando", eso es engañoso). aqui solo**Clave de término + parámetro**,
 * El componente traduce una copia específica al idioma actual.
 */
export interface ConnectionBanner {
  readonly visible: boolean;
  /** entradas de redacción publicitaria;visible=false El tiempo no tiene sentido. */
  readonly textKey: MessageKey;
  readonly params?: { readonly attempt: number } | undefined;
  /** Si se debe dar una entrada de \"reintento manual\" (la falla continua alcanza el umbral o se desconecta). */
  readonly retry: boolean;
  readonly tone: "info" | "warn" | "danger";
}

/** Después de volver a conectarse tantas veces sin éxito, el usuario tiene una salida para volver a intentarlo manualmente. */
export const MANUAL_RETRY_AFTER = 3;

const HIDDEN: ConnectionBanner = { visible: false, textKey: "connection.connecting", retry: false, tone: "info" };

export function connectionBanner(input: {
  readonly online: boolean;
  readonly status: HostConnectionStatus;
  readonly attempt: number;
  readonly everConnected: boolean;
}): ConnectionBanner {
  const { online, status, attempt, everConnected } = input;
  // El propio navegador sabe que no hay red: "reconectarse" en este momento es una ilusión, digamos el motivo directamente.
  if (!online) return { visible: true, textKey: "connection.offline", retry: false, tone: "danger" };
  if (status === "connected") return HIDDEN;
  if (status === "closed") {
    return {
      visible: true,
      textKey: everConnected ? "connection.disconnected" : "connection.unreachable",
      retry: true,
      tone: "danger",
    };
  }
  if (attempt > 0) {
    return { visible: true, textKey: "connection.reconnecting", params: { attempt }, retry: attempt >= MANUAL_RETRY_AFTER, tone: "warn" };
  }
  return {
    visible: true,
    textKey: everConnected ? "connection.interrupted" : "connection.connecting",
    retry: false,
    tone: "warn",
  };
}

/**
 * Deshabilite la descripción del cuadro de entrada.undefined Indica que es posible una entrada normal.
 * Prioridad sin conexión: cuando no hay red, le indica que restaure la red, en lugar de dejar que el usuario espere una reconexión que originalmente no estaba disponible.
 */
export function composerDisabledHint(input: {
  readonly online: boolean;
  readonly status: HostConnectionStatus;
}): MessageKey | undefined {
  if (!input.online) return "composer.hintOffline";
  if (input.status === "closed") return "composer.hintClosed";
  if (input.status !== "connected") return "composer.hintReconnecting";
  return undefined;
}
