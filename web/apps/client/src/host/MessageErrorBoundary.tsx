import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface MessageGuardProps {
  /** Los cambios restablecen el estado de error:Host Después de reenviar la instantánea, se soluciona el mismo mensaje y es hora de intentar renderizar nuevamente. */
  readonly resetKey: string;
  readonly children: ReactNode;
}

interface MessageGuardState {
  readonly failed: boolean;
}

/** El estado de error se escribe utilizando componentes de función para obtener el idioma actual. */
function MessageFallback() {
  const { t } = useI18n();
  return (
    <div className="host-msg-error" role="status">
      <AlertIcon />
      <span>{t("message.renderFailed")}</span>
    </div>
  );
}

/**
 * Representación del aislamiento de un solo mensaje.
 *
 * Cuando el contenido de un mensaje interrumpe la ruta de representación (caracteres anormales, estructuras inesperadas), simplemente reemplace este mensaje con una línea de aviso.
 * La lista completa sigue estando disponible; de lo contrario, un mensaje incorrecto dejaría la página de chat en blanco y el usuario no podría ver ningún otro registro.
 */
export class MessageErrorBoundary extends Component<MessageGuardProps, MessageGuardState> {
  override state: MessageGuardState = { failed: false };

  static getDerivedStateFromError(): MessageGuardState {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // No silencioso: por qué este mensaje no se puede dibujar y debe dejarse en la consola.
    console.warn("message render failed", error, info.componentStack);
  }

  override componentDidUpdate(prev: MessageGuardProps): void {
    if (this.state.failed && prev.resetKey !== this.props.resetKey) this.setState({ failed: false });
  }

  override render(): ReactNode {
    if (this.state.failed) return <MessageFallback />;
    return this.props.children;
  }
}
