import { useEffect, useMemo, useRef, useState } from "react";
import type { HostSettingsSnapshot } from "../api.js";
import { CloseIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { breadcrumbText, searchSettings, type SettingsSearchHit } from "./settingsQuery.js";

interface SettingsSearchProps {
  readonly snapshot: HostSettingsSnapshot;
  readonly query: string;
  readonly onQueryChange: (next: string) => void;
  readonly onPick: (hit: SettingsSearchHit) => void;
  readonly onClose: () => void;
}

/**
 * Búsqueda de configuración de pantalla completa.
 *
 * El cuadro de entrada se enfoca automáticamente cuando se abre.Esc Cerrar (se puede cerrar incluso si el foco no está en el cuadro de entrada).
 * ↑/↓ Mover seleccionado,Enter Ingresar; Pase el mouse para seleccionar simultáneamente, y el punto y el teclado tomarán la misma ruta de selección.
 * Los resultados sólo están disponibles localmente en `settings.snapshot` Calculado, no se activa ninguna solicitud de protocolo.
 */
export function SettingsSearch({ snapshot, query, onQueryChange, onPick, onClose }: SettingsSearchProps) {
  const { t } = useI18n();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [active, setActive] = useState(0);
  const hits = useMemo(() => searchSettings(snapshot, query), [snapshot, query]);
  const trimmed = query.trim();

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Tan pronto como cambie la palabra clave, mueva la selección nuevamente al primer elemento para evitar detenerse en elementos que se han filtrado.
  useEffect(() => {
    setActive(0);
  }, [query]);

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
    <div className="host-search" role="dialog" aria-modal="true" aria-label={t("settings.searchDialog")}>
      <div className="host-search-bar">
        <button className="quiet host-icon-button" type="button" aria-label={t("settings.searchExit")} onClick={onClose}>
          <CloseIcon />
        </button>
        <input
          ref={inputRef}
          className="host-search-input"
          type="search"
          aria-label={t("settings.search")}
          placeholder={t("settings.searchPlaceholder")}
          value={query}
          autoComplete="off"
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setActive((current) => Math.min(hits.length - 1, current + 1));
              return;
            }
            if (event.key === "ArrowUp") {
              event.preventDefault();
              setActive((current) => Math.max(0, current - 1));
              return;
            }
            if (event.key !== "Enter") return;
            const hit = hits[active];
            if (hit === undefined) return;
            event.preventDefault();
            onPick(hit);
          }}
        />
      </div>

      {hits.length > 0 && (
        <div className="host-search-results" role="listbox" aria-label={t("settings.searchResults")}>
          {hits.map((hit, index) => (
            <button
              key={hit.id}
              role="option"
              aria-selected={index === active}
              className={index === active ? "quiet host-search-result is-active" : "quiet host-search-result"}
              onMouseEnter={() => setActive(index)}
              onClick={() => onPick(hit)}
            >
              <span className="host-search-title">{hit.title}</span>
              <span className="host-search-breadcrumb">{breadcrumbText(hit)}</span>
            </button>
          ))}
        </div>
      )}

      {trimmed.length > 0 && hits.length === 0 && (
        <div className="host-search-empty" role="status">
          <p>{t("settings.searchEmpty", { query: trimmed })}</p>
          <button className="quiet host-search-empty-action" type="button" onClick={() => onQueryChange("")}>
            {t("settings.searchClear")}
          </button>
        </div>
      )}

      {trimmed.length === 0 && <p className="host-search-hint">{t("settings.searchHint")}</p>}
    </div>
  );
}
