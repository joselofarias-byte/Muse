import { useEffect } from "react";
import type { HostSystemStorage } from "../api.js";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, Switch, TopBar } from "../components/primitives.js";
import { formatBytes, formatDuration, formatNumber, formatRelativeTime } from "../i18n/format.js";
import { useI18n } from "../i18n/I18nProvider.js";
import type { HostSystemState } from "./useHostConnection.js";

interface DataPrivacyPageProps {
  readonly title: string;
  readonly system: HostSystemState;
  /** Juicio de bit de capacidad/tiempo de ejecución \"no compatible\". */
  readonly supported: boolean;
  readonly onRequest: () => void;
  /** PII demonio: entrada desde settings.snapshot(toggleKey=piiGuardEnabled). */
  readonly pii: { readonly checked: boolean; readonly writable: boolean } | undefined;
  readonly onTogglePii: (enabled: boolean) => void;
  readonly onBack: () => void;
}

/** El orden segmentado y la combinación de colores de las tiras de almacenamiento (el orden es el orden de visualización). */
const STORAGE_SEGMENTS = [
  { key: "databaseBytes", labelKey: "data.database", className: "is-database" },
  { key: "mediaBytes", labelKey: "data.media", className: "is-media" },
  { key: "voiceBytes", labelKey: "data.voice", className: "is-voice" },
  { key: "workspaceBytes", labelKey: "data.workspace", className: "is-workspace" },
  { key: "cacheBytes", labelKey: "data.cache", className: "is-cache" },
] as const;

/**
 * Página de datos y privacidad.
 *
 * La ocupación del almacenamiento utiliza una barra apilada horizontal + una fila detallada; la copia de seguridad y el cambio son ecos de solo lectura (el puerto de escritura está en el teléfono celular).
 * Sólo los guardias de PII eliminan los existentes. `settings.update` La lista blanca se puede cambiar directamente en la página web.
 */
export function DataPrivacyPage({ title, system, supported, onRequest, pii, onTogglePii, onBack }: DataPrivacyPageProps) {
  const { t, locale } = useI18n();
  const status = system.status;

  useEffect(() => {
    if (supported) onRequest();
  }, [supported, onRequest]);

  const body = () => {
    if (!supported || system.unsupported) {
      return (
        <CardGroup>
          <ListRow title={t("data.unsupported")} divider={false} />
        </CardGroup>
      );
    }
    if (system.error !== undefined) return <ErrorStateBox message={system.error} onRetry={onRequest} />;
    if (status === undefined) return <ListSkeleton groups={2} rows={3} label={t("data.loading")} />;

    const storage: HostSystemStorage = status.storage;
    const total = Math.max(
      status.storage.totalAppBytes,
      STORAGE_SEGMENTS.reduce((sum, segment) => sum + (storage[segment.key] ?? 0), 0),
    );
    const backup = status.backup;

    return (
      <>
        <CardGroup title={t("data.device")}>
          <ListRow title={t("app.version")} trailing={<span className="host-setting-value">{status.appVersion}</span>} divider />
          <ListRow
            title={t("app.uptime")}
            trailing={<span className="host-setting-value">{formatDuration(status.uptimeMs, locale)}</span>}
            divider={status.battery.level === undefined}
          />
          {status.battery.level !== undefined && (
            <ListRow
              title={t("app.battery")}
              trailing={
                <span className="host-setting-value">
                  {`${formatNumber(status.battery.level, locale)}%`}
                  {status.battery.charging === true ? ` · ${t("app.charging")}` : ""}
                </span>
              }
              divider={false}
            />
          )}
        </CardGroup>

        <CardGroup title={t("data.storage")}>
          <ListRow
            title={
              <span className="host-storage-bar" role="img" aria-label={t("data.storage")}>
                {STORAGE_SEGMENTS.map((segment) => {
                  const bytes = storage[segment.key] ?? 0;
                  if (bytes <= 0 || total <= 0) return null;
                  return (
                    <span
                      key={segment.key}
                      className={`host-storage-segment ${segment.className}`}
                      style={{ flexGrow: bytes }}
                      aria-hidden="true"
                    />
                  );
                })}
              </span>
            }
            trailing={<span className="host-setting-value">{formatBytes(total, locale)}</span>}
            divider
          />
          {STORAGE_SEGMENTS.map((segment, index) => (
            <ListRow
              key={segment.key}
              title={t(segment.labelKey)}
              trailing={<span className="host-setting-value">{formatBytes(storage[segment.key] ?? 0, locale)}</span>}
              divider={index !== STORAGE_SEGMENTS.length - 1}
            />
          ))}
          <ListRow
            title={t("data.total")}
            trailing={<span className="host-setting-value">{formatBytes(total, locale)}</span>}
            divider={false}
          />
        </CardGroup>

        <CardGroup title={t("data.backup")}>
          <ListRow
            title={t("data.lastBackup")}
            trailing={
              <span className="host-setting-value">
                {backup.lastBackupAt === undefined
                  ? t("data.neverBackedUp")
                  : formatRelativeTime(backup.lastBackupAt, Date.now(), locale)}
              </span>
            }
            divider={backup.lastBackupBytes === undefined ? false : true}
          />
          {backup.lastBackupBytes !== undefined && (
            <ListRow
              title={t("data.lastBackupSize")}
              trailing={<span className="host-setting-value">{formatBytes(backup.lastBackupBytes, locale)}</span>}
              divider
            />
          )}
          <ListRow
            title={t("data.autoBackup")}
            subtitle={t("data.readOnly")}
            trailing={<span className="host-setting-value">{backup.autoBackupEnabled ? t("data.on") : t("data.off")}</span>}
            divider
          />
          <ListRow
            title={t("data.cloudBackup")}
            subtitle={t("data.readOnly")}
            trailing={<span className="host-setting-value">{backup.cloudBackupEnabled ? t("data.on") : t("data.off")}</span>}
            divider={false}
          />
        </CardGroup>

        <CardGroup title={t("data.privacy")}>
          {pii === undefined ? (
            <ListRow title={t("data.piiGuard")} subtitle={t("data.piiGuardMissing")} divider />
          ) : pii.writable ? (
            <ListRow
              title={t("data.piiGuard")}
              trailing={<Switch checked={pii.checked} label={t("data.piiGuard")} onChange={onTogglePii} />}
              divider
            />
          ) : (
            <ListRow
              title={t("data.piiGuard")}
              subtitle={t("data.readOnly")}
              trailing={<Switch checked={pii.checked} label={t("data.piiGuard")} disabled onChange={() => undefined} />}
              divider
            />
          )}
          <ListRow title={t("data.piiGuardDesc")} divider={false} />
        </CardGroup>
      </>
    );
  };

  return (
    <Screen fill topBar={<TopBar title={title} largeTitle onBack={onBack} />}>
      <div className="muse-stack">{body()}</div>
    </Screen>
  );
}
