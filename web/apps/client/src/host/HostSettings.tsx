import { useCallback, useEffect, useState } from "react";
import type { HostAssistantDetail, HostAssistantSummary, HostProviderSummary, HostSettingsEntry, HostSettingsGroup, HostSettingsSnapshot } from "../api.js";
import { CardGroup, ErrorStateBox, ListRow, ListSkeleton, Screen, SettingsIcon, Switch, TopBar } from "../components/primitives.js";
import type { HostCapabilities } from "./capabilities.js";
import { AssistantsPage } from "./AssistantsPage.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { MEMORY_FLAGS, KNOWLEDGE_FLAGS, TASK_FLAGS, SYSTEM_STATUS_FLAGS, platformAvailability, platformKeysForEntry, supportsFlag } from "./featureCapabilities.js";
import { HostPlaceholder } from "./HostPlaceholder.js";
import { MemoryPage } from "./MemoryPage.js";
import { KnowledgePage } from "./KnowledgePage.js";
import { TasksPage } from "./TasksPage.js";
import { DataPrivacyPage } from "./DataPrivacyPage.js";
import { PlatformPage } from "./PlatformPage.js";
import { pageForEntry, SETTINGS_PAGE_SPECS, type SettingsPageSpec } from "./settingsPages.js";
import { settingsPageTitle, type SettingsSearchHit } from "./settingsQuery.js";
import { SettingsSearch } from "./SettingsSearch.js";
import { SettingsValueCard, SettingsValuePage } from "./SettingsValuePage.js";
import { useDelayedFlag } from "./useDelayedFlag.js";
import type { HostKnowledgeDocumentsState, HostKnowledgeState, HostMemoryState, HostSettingsState, HostSystemState, HostTasksState } from "./useHostConnection.js";
import { valuesForPage } from "./settingsPages.js";

export interface HostSettingsProps {
  readonly settings: HostSettingsState;
  readonly capabilities: HostCapabilities;
  readonly onBack: () => void;
  readonly onRetry: () => void;
  readonly onToggle: (key: string, enabled: boolean) => void;
  /** Escritura genérica de subpáginas (boolean / number / enum). */
  readonly onWrite: (key: string, value: string | number | boolean) => void;
  /** Datos y entradas necesarios para las páginas de asistente y de personaje/páginas de memoria. */
  readonly assistants: readonly HostAssistantSummary[];
  readonly providers: readonly HostProviderSummary[];
  readonly assistantDetails: Readonly<Record<string, HostAssistantDetail>>;
  readonly assistantDetailUnsupported: boolean;
  readonly memory: HostMemoryState;
  readonly capabilityFlags: readonly string[] | undefined;
  readonly onRequestAssistantDetail: (assistantId: string) => void;
  readonly onRequestProviders: () => void;
  readonly onRequestMemory: (options?: {
    readonly query?: string | undefined;
    readonly cursor?: string | undefined;
    readonly reset?: boolean | undefined;
  }) => void;
  readonly onStartSession: (assistantId: string) => void;
  /** Base de conocimientos y tareas programadas (página de solo lectura). */
  readonly knowledge: HostKnowledgeState;
  readonly knowledgeDocuments: HostKnowledgeDocumentsState;
  readonly tasks: HostTasksState;
  readonly onRequestKnowledge: () => void;
  readonly onRequestKnowledgeDocuments: (options: {
    readonly kbId: string;
    readonly cursor?: string | undefined;
    readonly reset?: boolean | undefined;
  }) => void;
  readonly onRequestTasks: () => void;
  /** Estado del sistema (datos compartidos con la página de privacidad/página específica de la plataforma). */
  readonly system: HostSystemState;
  readonly onRequestSystemStatus: () => void;
}

/** Subpágina actualmente abierta: entrada de directorio o un valor en los resultados de búsqueda (con la fila a resaltar). */
type OpenPage =
  | { readonly kind: "entry"; readonly entry: HostSettingsEntry }
  | { readonly kind: "value"; readonly page: SettingsPageSpec; readonly title: string; readonly focusKey: string };

/**
 * Configura una página de inicio.
 *
 * La agrupación, el orden, las entradas y los estados de cambio provienen del servidor `settings.snapshot`, la parte delantera no se reorganiza,
 * Sin filtrado ni llenado de entradas; título/Descripción/Todos los títulos de los grupos son texto analizado por el Host según el idioma actual.
 * Este fin no guarda ninguna copia (`titleKey` / `key` Solo logotipo estable).
 *
 * Hay un cuadro de búsqueda de techo en la parte superior: haga clic para abrirlo y buscar en pantalla completa, y los resultados solo se filtrarán localmente usando esta instantánea (no se enviará ninguna solicitud).
 * Haga clic en el resultado para ir directamente a la subpágina correspondiente; la fila de valor también resaltará su propia fila.
 */
export function HostSettings({
  settings,
  capabilities,
  onBack,
  onRetry,
  onToggle,
  onWrite,
  assistants,
  providers,
  assistantDetails,
  assistantDetailUnsupported,
  memory,
  capabilityFlags,
  onRequestAssistantDetail,
  onRequestProviders,
  onRequestMemory,
  onStartSession,
  knowledge,
  knowledgeDocuments,
  tasks,
  onRequestKnowledge,
  onRequestKnowledgeDocuments,
  onRequestTasks,
  system,
  onRequestSystemStatus,
}: HostSettingsProps) {
  const { t } = useI18n();
  const [openPage, setOpenPage] = useState<OpenPage | undefined>(undefined);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const snapshot = settings.snapshot;
  /** Si los datos se pueden recuperar de la página de memoria: se dará un mensaje directamente cuando falte el bit de capacidad (el antiguo Host lo intentará primero). */
  const supportsMemory = supportsFlag(capabilityFlags, MEMORY_FLAGS) && !memory.unsupported;
  // No utilice el esqueleto cuando lea rápidamente para evitar parpadeos.
  const skeletonDue = useDelayedFlag(settings.loading && snapshot === undefined);

  const closeSearch = useCallback(() => {
    setSearching(false);
    setQuery("");
  }, []);

  // Teclas de acceso directo:/ o Cmd/Ctrl+K para abrir la búsqueda. No captes el foco cuando esté en el control de entrada (/ son personajes normales).
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (snapshot === undefined) return;
      const target = event.target instanceof HTMLElement ? event.target : null;
      const typing =
        target !== null && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
      const chord = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k";
      const slash = event.key === "/" && !typing && !event.metaKey && !event.ctrlKey && !event.altKey;
      if (!chord && !slash) return;
      event.preventDefault();
      setSearching(true);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [snapshot]);

  function handlePick(hit: SettingsSearchHit) {
    closeSearch();
    if (snapshot === undefined) return;
    const target = hit.target;
    if (target.kind === "entry") {
      const entry = findEntry(snapshot, target.entryId);
      if (entry !== undefined) setOpenPage({ kind: "entry", entry });
      return;
    }
    const page = SETTINGS_PAGE_SPECS.find((spec) => spec.id === target.pageId);
    if (page === undefined) return;
    setOpenPage({ kind: "value", page, title: settingsPageTitle(snapshot, page), focusKey: target.valueKey });
  }

  if (openPage !== undefined) {
    const back = () => setOpenPage(undefined);
    // "Asistentes y funciones" y "Memoria" son páginas de funciones reales, no formularios de valores.
    if (openPage.kind === "entry" && openPage.entry.id === "assistants") {
      return (
        <AssistantsPage
          assistants={assistants}
          providers={providers}
          details={assistantDetails}
          flags={capabilityFlags}
          loading={snapshot === undefined}
          detailUnsupported={assistantDetailUnsupported}
          onRequestDetail={onRequestAssistantDetail}
          onRequestProviders={onRequestProviders}
          onStartSession={onStartSession}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "memory") {
      const memoryPage = SETTINGS_PAGE_SPECS.find((spec) => spec.id === "memory");
      const hasValues = memoryPage !== undefined && snapshot !== undefined && valuesForPage(memoryPage, snapshot.values).length > 0;
      return (
        <MemoryPage memory={memory} supported={supportsMemory} onRequest={onRequestMemory} onBack={back}>
          {hasValues && memoryPage !== undefined && snapshot !== undefined && (
            <SettingsValueCard page={memoryPage} snapshot={snapshot} onWrite={onWrite} />
          )}
        </MemoryPage>
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "rag") {
      return (
        <KnowledgePage
          knowledge={knowledge}
          documents={knowledgeDocuments}
          supported={supportsFlag(capabilityFlags, KNOWLEDGE_FLAGS) && !knowledge.unsupported}
          onRequestBases={onRequestKnowledge}
          onRequestDocuments={onRequestKnowledgeDocuments}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "scheduled_tasks") {
      return (
        <TasksPage
          tasks={tasks}
          supported={supportsFlag(capabilityFlags, TASK_FLAGS) && !tasks.unsupported}
          onRequest={onRequestTasks}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry" && openPage.entry.id === "data_management") {
      return (
        <DataPrivacyPage
          title={openPage.entry.title}
          system={system}
          supported={supportsFlag(capabilityFlags, SYSTEM_STATUS_FLAGS) && !system.unsupported}
          onRequest={onRequestSystemStatus}
          pii={piiGuardState(snapshot)}
          onTogglePii={(enabled) => onToggle(PII_GUARD_KEY, enabled)}
          onBack={back}
        />
      );
    }
    if (openPage.kind === "entry") {
      const platformKeys = platformKeysForEntry(openPage.entry.id);
      if (platformKeys !== undefined) {
        return (
          <PlatformPage
            title={openPage.entry.title}
            description={openPage.entry.desc}
            availability={platformAvailability(system.status?.capabilities, platformKeys)}
            onBack={back}
          />
        );
      }
    }
    if (openPage.kind === "value") {
      return (
        <SettingsValuePage
          page={openPage.page}
          title={openPage.title}
          snapshot={snapshot}
          loading={settings.loading}
          error={settings.error}
          capabilities={capabilities}
          focusKey={openPage.focusKey}
          onBack={back}
          onRetry={onRetry}
          onWrite={onWrite}
        />
      );
    }
    const page = pageForEntry(openPage.entry.id);
    if (page !== undefined) {
      return (
        <SettingsValuePage
          page={page}
          title={openPage.entry.title}
          snapshot={snapshot}
          loading={settings.loading}
          error={settings.error}
          capabilities={capabilities}
          onBack={back}
          onRetry={onRetry}
          onWrite={onWrite}
        />
      );
    }
    // Las páginas que no se hayan completado (incluida la capacidad de operar solo en teléfonos celulares) se incluirán en la página de marcador de posición unificada.
    return <HostPlaceholder title={openPage.entry.title} description={openPage.entry.desc} onBack={back} />;
  }

  return (
    <Screen fill topBar={<TopBar title={t("settings.title")} largeTitle onBack={onBack} />}>
      {snapshot === undefined ? (
        settings.error !== undefined ? (
          <ErrorStateBox message={settings.error} onRetry={onRetry} />
        ) : skeletonDue ? (
          <ListSkeleton label={t("settings.loading")} />
        ) : (
          // 300ms No dibujes nada al principio (el espacio en blanco es más estable que mostrar el esqueleto).
          <div className="host-loading-quiet" role="status" aria-label={t("settings.loading")} />
        )
      ) : (
        <>
          <div className="host-settings-search">
            <button className="quiet host-search-trigger" type="button" aria-label={t("settings.search")} onClick={() => setSearching(true)}>
              <span className="host-search-trigger-text">{t("settings.search")}</span>
              <kbd className="host-search-kbd">/</kbd>
            </button>
          </div>
          <div className="muse-stack">
            {snapshot.groups.map((group) => (
              <SettingsGroup
                key={group.key}
                group={group}
                writableKeys={snapshot.writableKeys}
                onOpen={(entry) => setOpenPage({ kind: "entry", entry })}
                onToggle={onToggle}
              />
            ))}
          </div>
        </>
      )}
      {searching && snapshot !== undefined && (
        <SettingsSearch snapshot={snapshot} query={query} onQueryChange={setQuery} onPick={handlePick} onClose={closeSearch} />
      )}
    </Screen>
  );
}

/** PII La tecla de encendido/apagado de la guardia (consistente con la lista blanca de configuración y actualización del Host). */
const PII_GUARD_KEY = "piiGuardEnabled";

/** Lea el demonio PII desde la instantánea de configuración: estado de las entradas, capacidad de escritura desde writableKeys. */
function piiGuardState(snapshot: HostSettingsSnapshot | undefined): { readonly checked: boolean; readonly writable: boolean } | undefined {
  if (snapshot === undefined) return undefined;
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey !== PII_GUARD_KEY) continue;
      return { checked: entry.checked === true, writable: snapshot.writableKeys.includes(PII_GUARD_KEY) };
    }
  }
  return undefined;
}

function findEntry(snapshot: HostSettingsSnapshot, entryId: string): HostSettingsEntry | undefined {
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.id === entryId) return entry;
    }
  }
  return undefined;
}

interface SettingsGroupProps {
  readonly group: HostSettingsGroup;
  readonly writableKeys: readonly string[];
  readonly onOpen: (entry: HostSettingsEntry) => void;
  readonly onToggle: (key: string, enabled: boolean) => void;
}

function SettingsGroup({ group, writableKeys, onOpen, onToggle }: SettingsGroupProps) {
  const entries = group.entries;
  return (
    <CardGroup title={group.groupTitle}>
      {entries.map((entry, index) => (
        <SettingsRow
          key={entry.id}
          entry={entry}
          last={index === entries.length - 1}
          writableKeys={writableKeys}
          onOpen={onOpen}
          onToggle={onToggle}
        />
      ))}
    </CardGroup>
  );
}

interface SettingsRowProps {
  readonly entry: HostSettingsEntry;
  readonly last: boolean;
  readonly writableKeys: readonly string[];
  readonly onOpen: (entry: HostSettingsEntry) => void;
  readonly onToggle: (key: string, enabled: boolean) => void;
}

/**
 * Elemento de configuración única.`kind` Decidir por el lado derecho:link / action → Flecha hacia la derecha e ingresar a la subpágina;
 * switch → MuseSwitch(Las claves escritas fuera de la lista blanca se desactivan directamente para evitar que el Host las rechace después de hacer clic en ellas).
 * A la izquierda está el pequeño icono redondo correspondiente a iconKey (el mismo conjunto de gráficos que el teléfono celular).
 */
function SettingsRow({ entry, last, writableKeys, onOpen, onToggle }: SettingsRowProps) {
  const title = entry.title;
  const desc = entry.desc;
  const text = {
    title,
    ...(desc === undefined || desc === "" ? {} : { subtitle: desc }),
    leading: <SettingsIcon iconKey={entry.iconKey} />,
    divider: !last,
  };

  if (entry.kind === "switch") {
    const toggleKey = entry.toggleKey;
    const writable = toggleKey !== undefined && writableKeys.includes(toggleKey);
    return (
      <ListRow
        {...text}
        trailing={
          <Switch
            checked={entry.checked === true}
            label={title}
            disabled={!writable}
            onChange={(next) => {
              if (writable && toggleKey !== undefined) onToggle(toggleKey, next);
            }}
          />
        }
      />
    );
  }

  // Tipo desconocido: degradado a una línea de visualización neutral (sin flechas dibujadas, sin clics, sin errores).
  if (entry.kind !== "link" && entry.kind !== "action") {
    return <ListRow {...text} />;
  }

  // Host Oferta enabled = false La entrada (como el pequeño teléfono celular cuando la función experimental está apagada) solo se muestra y no ingresa a la subpágina.
  if (entry.enabled === false) {
    return <ListRow {...text} />;
  }

  return <ListRow {...text} chevron onClick={() => onOpen(entry)} />;
}
