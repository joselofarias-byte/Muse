import { CapsuleButton } from "./CapsuleButton.js";
import { IconAlertCircle } from "./muse-icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import "./primitives.css";

export interface ErrorStateBoxProps {
  readonly message: string;
  /** Renderice \"Reintentar\" después de pasarlo. */
  readonly onRetry?: () => void;
  /** \"Cerrar\" se representa sólo después de pasarlo. */
  readonly onDismiss?: () => void;
}

/**
 * Bloque de estado de error (reintentable).
 *
 * Correspondiente a MuseErrorStateBox de Compose: fondo de color de error circular (8%)+ error @60% icono,
 * Centra el títuloCopia mediana y una fila de botones de cápsula debajo. El tamaño va iconEmpty(64) + screen*2 el anillo exterior.
 */
export function ErrorStateBox({ message, onRetry, onDismiss }: ErrorStateBoxProps) {
  const { t } = useI18n();
  const hasActions = onRetry !== undefined || onDismiss !== undefined;
  return (
    <div className="muse-error-box" role="alert">
      <span className="muse-error-box-icon">
        <IconAlertCircle className="muse-icon" />
      </span>
      <p className="muse-error-box-message">{message}</p>
      {hasActions && (
        <div className="muse-error-box-actions">
          {onRetry !== undefined && (
            <CapsuleButton variant="secondary" fillWidth={false} onClick={onRetry}>
              {t("common.retry")}
            </CapsuleButton>
          )}
          {onDismiss !== undefined && (
            <CapsuleButton variant="secondary" fillWidth={false} onClick={onDismiss}>
              {t("common.close")}
            </CapsuleButton>
          )}
        </div>
      )}
    </div>
  );
}
