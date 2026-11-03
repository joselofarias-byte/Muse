import { CardGroup, ListRow, Screen, TopBar } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { PlatformAvailability } from "./featureCapabilities.js";

interface PlatformPageProps {
  /** Host Título del artículo localizado. */
  readonly title: string;
  /** Qué hace esta función: Priorizar la descripción de entrada emitida por el Anfitrión. */
  readonly description?: string | undefined;
  readonly availability: PlatformAvailability;
  readonly onBack: () => void;
}

/**
 * Páginas específicas de la plataforma (Accesibilidad/Pantalla virtual/Terminal/Escucha de notificaciones/Automatización de UI).
 *
 * Muestra solo tres cosas: qué hace esta característica, disponibilidad actual (escriba desde system.status.capabilities),
 * y "Sólo se puede abrir en teléfonos celulares". Si no está disponible, simplemente explique la situación.——No le des al botón "Ir a Configuración",
 * Debido a que esa es la configuración del teléfono celular, no se puede hacer clic en la página web.
 */
export function PlatformPage({ title, description, availability, onBack }: PlatformPageProps) {
  const { t } = useI18n();
  const label =
    availability === "available"
      ? t("platform.available")
      : availability === "notEnabled"
        ? t("platform.notEnabled")
        : t("platform.unavailable");
  const desc = description !== undefined && description.length > 0 ? description : t("platform.readOnly");

  return (
    <Screen fill topBar={<TopBar title={title} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        <CardGroup>
          <ListRow title={desc} divider={false} />
        </CardGroup>
        <CardGroup title={t("platform.status")}>
          <ListRow
            title={label}
            subtitle={availability === "available" ? undefined : t("platform.enableOnPhone")}
            trailing={<span className={`host-platform-state is-${availability}`} aria-hidden="true" />}
            divider
          />
          <ListRow title={t("platform.readOnly")} divider={false} />
        </CardGroup>
      </div>
    </Screen>
  );
}
