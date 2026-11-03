import { useEffect } from "react";
import type { HostAssistantSummary } from "../api.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { HostAvatar } from "./HostAvatar.js";

interface AssistantPickerProps {
  readonly assistants: readonly HostAssistantSummary[];
  readonly onPick: (assistant: HostAssistantSummary) => void;
  readonly onClose: () => void;
}

/**
 * Selección de asistente antes de crear una nueva sesión.
 *
 * Aparece solo cuando hay más de un asistente (uno creado directamente, cero asistentes predeterminados entregados al Anfitrión),
 * La lista es `assistants.list` Tal cual: avatar + nombre + etiqueta predeterminada.Esc Cerca.
 * El nombre del asistente lo emite el anfitrión y se muestra tal cual.
 */
export function AssistantPicker({ assistants, onPick, onClose }: AssistantPickerProps) {
  const { t } = useI18n();

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="host-picker" role="dialog" aria-modal="true" aria-label={t("assistants.title")}>
      <div className="host-picker-sheet">
        <div className="host-picker-head">
          <h2>{t("assistants.title")}</h2>
          <button className="quiet host-picker-cancel" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
        </div>
        <div className="host-picker-list" role="listbox" aria-label={t("assistants.listAria")}>
          {assistants.map((assistant) => (
            <button
              key={assistant.id}
              className="quiet host-picker-item"
              type="button"
              role="option"
              aria-selected={assistant.isDefault === true}
              onClick={() => onPick(assistant)}
            >
              <HostAvatar name={assistant.name} uri={assistant.avatarUri} size="large" />
              <span className="host-picker-name">{assistant.name}</span>
              {assistant.isDefault === true && <span className="host-picker-badge">{t("assistants.default")}</span>}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
