import { useState } from "react";
import type { HostSettingOption, HostSettingValue, HostSettingsSnapshot } from "../api.js";
import { CheckIcon } from "../components/icons.js";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, Switch, TopBar } from "../components/primitives.js";
import type { HostCapabilities } from "./capabilities.js";
import {
  isReadOnlyKey,
  readBoolean,
  readNumber,
  readText,
  resolveValueTitle,
  stepNumber,
  valuesForPage,
  type SettingsPageSpec,
} from "./settingsPages.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { useDelayedFlag } from "./useDelayedFlag.js";

export interface SettingsValuePageProps {
  readonly page: SettingsPageSpec;
  /** Título de la página: título localizado del host de la entrada del directorio en la que se hizo clic. */
  readonly title: string;
  readonly snapshot: HostSettingsSnapshot | undefined;
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly capabilities: HostCapabilities;
  readonly onBack: () => void;
  readonly onRetry: () => void;
  readonly onWrite: (key: string, value: string | number | boolean) => void;
  /** La fila a resaltar al llegar directamente desde los resultados de la búsqueda (la clave del valor). */
  readonly focusKey?: string | undefined;
}

/** toggleKey → Entrada de directorio para obtener el título/descripción localizados del anfitrión. */
function titledEntries(snapshot: HostSettingsSnapshot): Map<string, { title: string; desc?: string | undefined }> {
  const map = new Map<string, { title: string; desc?: string | undefined }>();
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey === undefined) continue;
      map.set(entry.toggleKey, { title: entry.title, desc: entry.desc });
    }
  }
  return map;
}

/** Título de la fila: título de la tabla de contenido → Host Si se emite label → Derivación de claves (misma lógica que el índice de búsqueda). */
function rowInfo(
  value: HostSettingValue,
  entries: Map<string, { title: string; desc?: string | undefined }>,
  titles: ReadonlyMap<string, string>,
): { title: string; subtitle?: string | undefined } {
  const fromEntry = entries.get(value.key);
  const title = resolveValueTitle(value, titles);
  return fromEntry === undefined ? { title } : { title, subtitle: fromEntry.desc };
}

/**
 * Configurar subpáginas.
 *
 * OK = Host `values[]` Los clasificados en esta página (el orden es según Anfitrión, no hay reordenamiento):
 * boolean → Switch,number → −/+,enum → Selección de radio ampliable,text → Filas de solo lectura.
 * Todas las escrituras se "modifican localmente primero y luego se distribuyen". La reversión de fallas y las indicaciones de motivo se manejan de manera uniforme en useHostConnection.
 */
export function SettingsValuePage({
  page,
  title,
  snapshot,
  loading,
  error,
  capabilities,
  onBack,
  onRetry,
  onWrite,
  focusKey,
}: SettingsValuePageProps) {
  const { t } = useI18n();
  const topBar = <TopBar title={title} largeTitle onBack={onBack} />;
  const skeletonDue = useDelayedFlag(loading && snapshot === undefined);

  if (snapshot === undefined) {
    return (
      <Screen fill topBar={topBar}>
        {error !== undefined ? (
          <ErrorStateBox message={error} onRetry={onRetry} />
        ) : skeletonDue ? (
          <ListSkeleton label={t("settings.loading")} />
        ) : (
          <div className="host-loading-quiet" role="status" aria-label={t("settings.loading")} />
        )}
      </Screen>
    );
  }

  // Está claro que no existe la capacidad de settings_values, o que este Host no emite ningún valor: déjelo claro y no proporcione una lista vacía.
  if (!capabilities.settingsValues || snapshot.values === undefined) {
    return (
      <Screen fill topBar={topBar}>
        <div className="muse-stack">
          <CardGroup>
            <ListRow
              title={t("settings.unsupported")}
              subtitle={t("settings.unsupportedBody")}
              divider={false}
            />
          </CardGroup>
        </div>
      </Screen>
    );
  }

  const entries = titledEntries(snapshot);
  const rows = valuesForPage(page, snapshot.values);

  return (
    <Screen fill topBar={topBar}>
      <div className="muse-stack">
        {rows.length === 0 ? (
          <CardGroup>
            <ListRow title={t("settings.emptyPage")} subtitle={t("settings.emptyPageBody")} divider={false} />
          </CardGroup>
        ) : (
          <SettingsValueCard page={page} snapshot={snapshot} onWrite={onWrite} focusKey={focusKey} />
        )}
      </div>
    </Screen>
  );
}

/**
 * Tarjeta sintonizable de una página: un componente independiente para su reutilización en páginas mixtas, como subpáginas de configuración y "páginas de memoria".
 * Si ningún valor coincide, se devuelve nulo y la persona que llama decide cómo dibujar el estado vacío.
 */
export function SettingsValueCard({
  page,
  snapshot,
  onWrite,
  focusKey,
}: {
  readonly page: SettingsPageSpec;
  readonly snapshot: HostSettingsSnapshot;
  readonly onWrite: (key: string, value: string | number | boolean) => void;
  readonly focusKey?: string | undefined;
}) {
  const entries = titledEntries(snapshot);
  const titles = new Map(Array.from(entries, ([key, info]) => [key, info.title] as const));
  const rows = valuesForPage(page, snapshot.values);
  if (rows.length === 0) return null;
  return (
    <CardGroup>
      {rows.map((value, index) => {
        const row = (
          <ValueRow
            key={value.key}
            value={value}
            last={index === rows.length - 1}
            info={rowInfo(value, entries, titles)}
            onWrite={onWrite}
          />
        );
        // Acceso directo desde la búsqueda: marque la fila de destino; de lo contrario, los usuarios tendrán que buscarla ellos mismos.
        return focusKey !== undefined && value.key === focusKey ? (
          <div className="host-setting-highlight" key={value.key}>
            {row}
          </div>
        ) : (
          row
        );
      })}
    </CardGroup>
  );
}

interface ValueRowProps {
  readonly value: HostSettingValue;
  readonly last: boolean;
  readonly info: { readonly title: string; readonly subtitle?: string | undefined };
  readonly onWrite: (key: string, value: string | number | boolean) => void;
}

function ValueRow({ value, last, info, onWrite }: ValueRowProps) {
  const { t } = useI18n();
  const text = {
    title: info.title,
    ...(info.subtitle === undefined || info.subtitle === "" ? {} : { subtitle: info.subtitle }),
  };

  // Clave de solo lectura: refleja el estado y no proporciona ninguna entrada de escritura.
  if (isReadOnlyKey(value.key)) {
    if (value.type === "boolean") {
      return <ListRow {...text} divider={!last} trailing={<Switch checked={readBoolean(value.value)} disabled label={info.title} onChange={() => undefined} />} />;
    }
    return (
      <ListRow
        {...text}
        divider={!last}
        trailing={<span className="host-setting-value">{valueText(value)}</span>}
      />
    );
  }

  if (value.type === "boolean") {
    return (
      <ListRow
        {...text}
        divider={!last}
        trailing={
          <Switch
            checked={readBoolean(value.value)}
            label={info.title}
            onChange={(next) => onWrite(value.key, next)}
          />
        }
      />
    );
  }

  if (value.type === "number") {
    const current = readNumber(value.value);
    return (
      <ListRow
        {...text}
        divider={!last}
        trailing={
          <span className="host-stepper">
            <button
              className="quiet host-stepper-button"
              type="button"
              aria-label={t("settings.decrease", { title: info.title })}
              onClick={() => onWrite(value.key, stepNumber(current, -1))}
            >
              −
            </button>
            <span className="host-stepper-value">{String(current)}</span>
            <button
              className="quiet host-stepper-button"
              type="button"
              aria-label={t("settings.increase", { title: info.title })}
              onClick={() => onWrite(value.key, stepNumber(current, 1))}
            >
              +
            </button>
          </span>
        }
      />
    );
  }

  if (value.type === "enum") {
    return <EnumRow value={value} last={last} info={info} onWrite={onWrite} />;
  }

  return (
    <ListRow {...text} divider={!last} trailing={<span className="host-setting-value">{valueText(value)}</span>} />
  );
}

/** Enumeración: haga clic en la fila para expandir las opciones y marque los elementos seleccionados; todas las etiquetas utilizan la opción.label emitida por el Host. */
function EnumRow({ value, last, info, onWrite }: ValueRowProps) {
  const [open, setOpen] = useState(false);
  const options: readonly HostSettingOption[] = value.options ?? [];
  const current = readText(value.value);
  const selected = options.find((option) => option.value === current);
  const canChoose = options.length > 0;

  const text = {
    title: info.title,
    ...(info.subtitle === undefined || info.subtitle === "" ? {} : { subtitle: info.subtitle }),
  };

  return (
    <>
      <ListRow
        {...text}
        divider={canChoose || !last}
        chevron={canChoose}
        trailing={<span className="host-setting-value">{selected?.label ?? (current.length > 0 ? current : "—")}</span>}
        {...(canChoose ? { onClick: () => setOpen((previous) => !previous) } : {})}
      />
      {open &&
        options.map((option, index) => (
          <ListRow
            key={option.value}
            title={option.label}
            divider={index < options.length - 1 || !last}
            trailing={
              option.value === current ? (
                <span className="host-setting-check">
                  <CheckIcon size={18} />
                </span>
              ) : undefined
            }
            onClick={() => {
              setOpen(false);
              if (option.value !== current) onWrite(value.key, option.value);
            }}
          />
        ))}
    </>
  );
}

function valueText(value: HostSettingValue): string {
  const raw = readText(value.value);
  const option = value.options?.find((candidate) => candidate.value === raw);
  if (option !== undefined) return option.label;
  return raw.length > 0 ? raw : "—";
}
