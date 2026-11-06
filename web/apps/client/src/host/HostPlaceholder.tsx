import { CapsuleButton, CardGroup, ListRow, Screen, TopBar } from "../components/primitives.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface HostPlaceholderProps {
  /** Título de la página:Host Título del artículo localizado. */
  readonly title: string;
  /** Qué hace esta función: Priorizar utilizando la descripción de entrada emitida por el Anfitrión; si no, utilice una frase neutral del extremo local. */
  readonly description?: string | undefined;
  readonly onBack: () => void;
}

/**
 * Página de marcador de posición unificada.
 *
 * Aquí se incluyen dos tipos de páginas: la subpágina de configuración que no se ha migrado a la página web y la capacidad de operar solo en teléfonos celulares.
 * (Accesibilidad, pantalla virtual, terminal, seguimiento de notificaciones,UI Automatización……). solo di tres cosas——
 * "Esta función debe operarse en un teléfono celular", qué hace esta función y cómo regresar.
 * No pongas ningún botón falso: no pretendas poder hacer clic si no hay una entrada implementada.
 */
export function HostPlaceholder({ title, description, onBack }: HostPlaceholderProps) {
  const { t } = useI18n();
  const desc = description !== undefined && description.length > 0 ? description : t("settings.placeholderBody");
  return (
    <Screen fill topBar={<TopBar title={title} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        <CardGroup>
          <ListRow title={t("settings.placeholderTitle")} subtitle={desc} divider={false} />
        </CardGroup>
        <div className="host-placeholder-actions">
          <CapsuleButton variant="secondary" fillWidth={false} onClick={onBack}>
            {t("common.back")}
          </CapsuleButton>
        </div>
      </div>
    </Screen>
  );
}
