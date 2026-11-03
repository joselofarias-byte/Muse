import { Component, Fragment, type ErrorInfo, type ReactNode } from "react";
import { AlertIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface BlockBoundaryProps {
  /** ¿Qué es esta parte? (nómbrela en la copia del mensaje); la persona que llama le informará según el idioma actual. */
  readonly name: string;
  readonly children: ReactNode;
}

interface BlockBoundaryState {
  readonly failed: boolean;
  /** Incrementar significa volver a montar el subárbol: esta es la implementación de \"recargar este bloque\". */
  readonly nonce: number;
}

/** El estado de error se escribe utilizando componentes de función para obtener el idioma actual (class no se pueden utilizar ganchos). */
function BlockFallback({ name, onRetry }: { readonly name: string; readonly onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <div className="host-block-error" role="alert">
      <AlertIcon />
      <p className="host-block-error-text">{t("block.failed", { name })}</p>
      <button className="quiet host-block-retry" type="button" onClick={onRetry}>
        {t("block.retry")}
      </button>
    </div>
  );
}

/**
 * Límite de error a nivel de página: cierto bloque de renderizado explota, simplemente reemplace ese bloque con un mensaje de una línea + recarga,
 * El resto (barra lateral, área de entrada, otras tarjetas) siguen estando disponibles.
 *
 * División del trabajo con MessageErrorBoundary: ese es el aislamiento mensaje por mensaje, este es bloques más grandes
 * Aislamiento de (lista de mensajes, lista de conversaciones, página de configuración).
 */
export class BlockBoundary extends Component<BlockBoundaryProps, BlockBoundaryState> {
  override state: BlockBoundaryState = { failed: false, nonce: 0 };

  static getDerivedStateFromError(): Partial<BlockBoundaryState> {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.warn(`${this.props.name} render failed`, error, info.componentStack);
  }

  private readonly retry = () => {
    this.setState((current) => ({ failed: false, nonce: current.nonce + 1 }));
  };

  override render(): ReactNode {
    if (this.state.failed) return <BlockFallback name={this.props.name} onRetry={this.retry} />;
    return <Fragment key={this.state.nonce}>{this.props.children}</Fragment>;
  }
}
