import { useEffect, useRef, useState } from "react";
import type { HostAssistantDetail, HostAssistantSummary, HostProviderSummary } from "../api.js";
import { CapsuleButton, CardGroup, ListRow, ListSkeleton, Screen, Switch, TopBar } from "../components/primitives.js";
import { formatNumber } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { ASSISTANT_DETAIL_FLAGS, supportsFlag } from "./featureCapabilities.js";
import { HostAvatar } from "./HostAvatar.js";

interface AssistantsPageProps {
  readonly assistants: readonly HostAssistantSummary[];
  readonly providers: readonly HostProviderSummary[];
  readonly details: Readonly<Record<string, HostAssistantDetail>>;
  /** Host Bits de capacidad declarados (undefined = Anfitrión antiguo, pruébalo primero). */
  readonly flags: readonly string[] | undefined;
  /** La instantánea de la mitad superior de la página aún no ha llegado. */
  readonly loading: boolean;
  /** Host Respondió claramente \"Detalles no admitidos\". */
  readonly detailUnsupported: boolean;
  readonly onRequestDetail: (assistantId: string) => void;
  readonly onRequestProviders: () => void;
  readonly onStartSession: (assistantId: string) => void;
  readonly onBack: () => void;
}

/** Si el resumen del personaje supera las 6 líneas, dóblelo y expanda el botón.——Aquí sólo se registra el estado expandido. */
function PersonaSummary({ text }: { readonly text: string }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  return (
    <>
      <p className={open ? "host-persona is-open" : "host-persona"}>{text}</p>
      <button className="quiet host-persona-toggle" type="button" onClick={() => setOpen((current) => !current)}>
        {open ? t("assistants.collapse") : t("assistants.expand")}
      </button>
    </>
  );
}

function value(value: string | number | undefined, empty: string): string {
  if (value === undefined) return empty;
  if (typeof value === "number") return formatNumber(value, "en", { maximumFractionDigits: 2 });
  return value.length > 0 ? value : empty;
}

/**
 * Página de Asistentes y Personajes: Lista → Detalles.
 *
 * Además del resumen del personaje, los detalles./parámetros/Longitud de la palabra del mensaje/Número de herramienta, solo hay una acción "Iniciar sesión con este asistente";
 * El interruptor de habilitación está**solo lectura**de——No hay ningún comando escrito en el protocolo. Hacer un interruptor que no responde cuando se hace clic es peor que no activarlo en absoluto.
 */
export function AssistantsPage(props: AssistantsPageProps) {
  const { t } = useI18n();
  const assist = props.assistants;
  const providers = props.providers;
  const details = props.details;
  const { loading, detailUnsupported, onRequestDetail, onRequestProviders, onStartSession, onBack } = props;
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const requested = useRef(new Set<string>());
  const supportsDetail = supportsFlag(props.flags, ASSISTANT_DETAIL_FLAGS) && !detailUnsupported;

  // Cuando llegue la lista, obtenga los detalles de cada asistente: el nombre del modelo solo está en los detalles. Las solicitudes repetidas se deduplican según lo solicitado.
  useEffect(() => {
    if (!supportsDetail) return;
    onRequestProviders();
    for (const assistant of assist) {
      if (details[assistant.id] !== undefined) continue;
      if (requested.current.has(assistant.id)) continue;
      requested.current.add(assistant.id);
      onRequestDetail(assistant.id);
    }
  }, [supportsDetail, assist, details, onRequestDetail, onRequestProviders]);

  const modelNameOf = (detail: HostAssistantDetail | undefined): string | undefined => {
    if (detail === undefined || detail.modelId === undefined) return undefined;
    const provider = providers.find((item) => item.id === detail.providerId);
    return provider?.models.find((model) => model.id === detail.modelId)?.name ?? detail.modelId;
  };

  const selected = assist.find((assistant) => assistant.id === selectedId);

  if (selected !== undefined) {
    return (
      <AssistantDetailView
        assistant={selected}
        detail={details[selected.id]}
        modelName={modelNameOf(details[selected.id])}
        supported={supportsDetail}
        onStartSession={onStartSession}
        onBack={() => setSelectedId(undefined)}
      />
    );
  }

  return (
    <Screen fill topBar={<TopBar title={t("assistants.pageTitle")} largeTitle onBack={onBack} />}>
      {loading && assist.length === 0 ? (
        <ListSkeleton label={t("assistants.loading")} />
      ) : (
        <div className="muse-stack">
          <CardGroup>
            {assist.length === 0 ? (
              <ListRow title={t("assistants.listEmpty")} divider={false} />
            ) : (
              assist.map((assistant, index) => {
                const modelName = modelNameOf(details[assistant.id]);
                return (
                  <ListRow
                    key={assistant.id}
                    title={assistant.name}
                    {...(modelName === undefined ? {} : { subtitle: modelName })}
                    leading={<HostAvatar name={assistant.name} uri={assistant.avatarUri} />}
                    trailing={assistant.isDefault === true ? <span className="host-picker-badge">{t("assistants.default")}</span> : undefined}
                    chevron
                    divider={index !== assist.length - 1}
                    onClick={() => setSelectedId(assistant.id)}
                  />
                );
              })
            )}
          </CardGroup>
        </div>
      )}
    </Screen>
  );
}

/** Página de detalles (exportada por separado: las afirmaciones se pueden representar directamente sin interacción). */
export function AssistantDetailView({
  assistant,
  detail,
  modelName,
  supported,
  onStartSession,
  onBack,
}: {
  readonly assistant: HostAssistantSummary;
  readonly detail: HostAssistantDetail | undefined;
  readonly modelName: string | undefined;
  readonly supported: boolean;
  readonly onStartSession: (assistantId: string) => void;
  readonly onBack: () => void;
}) {
  const { t } = useI18n();
  return (
    <Screen fill topBar={<TopBar title={assistant.name} largeTitle onBack={onBack} />}>
      <div className="muse-stack">
        <div className="host-assistant-head">
          <HostAvatar name={assistant.name} uri={assistant.avatarUri} size="large" />
          <div className="host-assistant-head-text">
            <h2>{assistant.name}</h2>
            {assistant.isDefault === true && <span className="host-picker-badge">{t("assistants.default")}</span>}
            <p>{detail?.description !== undefined && detail.description.length > 0 ? detail.description : t("assistants.noDescription")}</p>
          </div>
        </div>

        {!supported ? (
          <CardGroup>
            <ListRow title={t("assistants.unsupported")} divider={false} />
          </CardGroup>
        ) : detail === undefined ? (
          <ListSkeleton groups={2} rows={2} label={t("assistants.detailLoading")} />
        ) : (
          <>
            {detail.personaSummary !== undefined && detail.personaSummary.length > 0 && (
              <CardGroup title={t("assistants.persona")}>
                <ListRow title={<PersonaSummary text={detail.personaSummary} />} divider={false} />
              </CardGroup>
            )}
            <CardGroup title={t("assistants.params")}>
              <ListRow title={t("assistants.model")} trailing={<span className="host-setting-value">{modelName ?? t("assistants.notSet")}</span>} divider />
              <ListRow
                title={t("assistants.temperature")}
                trailing={<span className="host-setting-value">{value(detail.temperature, t("assistants.notSet"))}</span>}
                divider
              />
              <ListRow title={t("assistants.topP")} trailing={<span className="host-setting-value">{value(detail.topP, t("assistants.notSet"))}</span>} divider />
              <ListRow
                title={t("assistants.maxTokens")}
                trailing={<span className="host-setting-value">{value(detail.maxTokens, t("assistants.notSet"))}</span>}
                divider
              />
              <ListRow
                title={t("assistants.contextSize")}
                trailing={<span className="host-setting-value">{value(detail.contextMessageSize, t("assistants.notSet"))}</span>}
                divider
              />
              <ListRow
                title={t("assistants.reasoning")}
                trailing={<span className="host-setting-value">{value(detail.reasoningLevel, t("assistants.notSet"))}</span>}
                divider={false}
              />
            </CardGroup>
            <CardGroup>
              <ListRow
                title={t("assistants.prompt")}
                trailing={
                  <span className="host-setting-value">
                    {t("assistants.promptConfigured", { count: formatNumber(detail.systemPromptLength ?? 0, "en") })}
                  </span>
                }
                divider
              />
              <ListRow
                title={t("assistants.tools")}
                trailing={<span className="host-setting-value">{t("assistants.toolCount", { count: formatNumber(detail.toolCount ?? 0, "en") })}</span>}
                divider
              />
              <ListRow
                title={t("assistants.enabled")}
                subtitle={t("assistants.enabledReadOnly")}
                trailing={<Switch checked={detail.enabled === true} label={t("assistants.enabled")} disabled onChange={() => undefined} />}
                divider={false}
              />
            </CardGroup>
            <div className="host-assistant-actions">
              <CapsuleButton onClick={() => onStartSession(assistant.id)}>{t("assistants.startSession")}</CapsuleButton>
            </div>
          </>
        )}
      </div>
    </Screen>
  );
}
