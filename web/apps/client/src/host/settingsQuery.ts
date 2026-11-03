import type { HostSettingsSnapshot } from "../api.js";
import { humanizeSettingKey, pageForValueKey, resolveValueTitle, type SettingsPageSpec } from "./settingsPages.js";

/**
 * Configurar la búsqueda: usa solo lo que ya tienes `settings.snapshot` Filtrado local, no se envían solicitudes.
 *
 * Campos involucrados en la coincidencia: nombre de entrada, nombre de categoría, descripción de entrada, título de fila de valor, título de página de valor, clave de configuración original.
 * No distingue entre mayúsculas y minúsculas y los caracteres chinos coinciden según subcadenas (sin segmentación de palabras). Las iniciales Pinyin no están hechas.——Eso requeriría un documento de varios miles de palabras.
 * caracteres chinos→Tabla Pinyin, este extremo no puede agregar nuevas dependencias, y este tipo de tabla de datos desactualizada no debe insertarse en el código de la interfaz.
 */
export type SettingsSearchTarget =
  | { readonly kind: "entry"; readonly entryId: string }
  | { readonly kind: "value"; readonly valueKey: string; readonly pageId: SettingsPageSpec["id"] };

export interface SettingsSearchHit {
  readonly id: string;
  readonly title: string;
  /** Migas de pan de dos niveles: clasificación › Artículo/Subpágina › Elementos de configuración. */
  readonly breadcrumb: readonly string[];
  readonly target: SettingsSearchTarget;
}

export interface SettingsSearchRow {
  readonly id: string;
  readonly title: string;
  readonly breadcrumb: readonly string[];
  readonly target: SettingsSearchTarget;
  /** Campos participantes en el partido (bajados). */
  readonly fields: readonly string[];
}

/** El título de la subpágina a la que pertenece el valor: si hay una entrada con el mismo nombre en el directorio, se utilizará su título y no se derivará de la identificación de la página. */
export function settingsPageTitle(snapshot: HostSettingsSnapshot, page: SettingsPageSpec): string {
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (page.entryIds.includes(entry.id)) return entry.title;
    }
  }
  return humanizeSettingKey(page.id);
}

/** toggleKey → Título del catálogo, utilizado para mantener el título de la fila de valores coherente con lo que se muestra en la subpágina. */
function titledSettingKeys(snapshot: HostSettingsSnapshot): Map<string, string> {
  const map = new Map<string, string>();
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey !== undefined) map.set(entry.toggleKey, entry.title);
    }
  }
  return map;
}

/**
 * Indexación: entradas de directorio accesibles (link / action, y no discapacitado)+ Todas las filas de valores.
 * entrada del interruptor (kind=switch) no indexado——Ya viven en la página de categorías y no tienen adónde ir si haces clic en ellos.
 */
export function buildSettingsSearchRows(snapshot: HostSettingsSnapshot): readonly SettingsSearchRow[] {
  const rows: SettingsSearchRow[] = [];
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      const navigable = (entry.kind === "link" || entry.kind === "action") && entry.enabled !== false;
      if (!navigable) continue;
      rows.push({
        id: `entry:${group.key}:${entry.id}`,
        title: entry.title,
        breadcrumb: [group.groupTitle, entry.title],
        target: { kind: "entry", entryId: entry.id },
        fields: [entry.title, group.groupTitle, entry.desc ?? ""].map((field) => field.toLowerCase()),
      });
    }
  }

  const titles = titledSettingKeys(snapshot);
  for (const value of snapshot.values ?? []) {
    const page = pageForValueKey(value.key);
    if (page === undefined) continue; // Los valores que no se pueden devolver a ninguna subpágina no tienen adónde ir y no se incluyen en los resultados.
    const pageTitle = settingsPageTitle(snapshot, page);
    const title = resolveValueTitle(value, titles);
    rows.push({
      id: `value:${value.key}`,
      title,
      breadcrumb: [pageTitle, title],
      target: { kind: "value", valueKey: value.key, pageId: page.id },
      fields: [title, pageTitle, value.key].map((field) => field.toLowerCase()),
    });
  }

  return rows;
}

/** Filtrado local: las visitas al título se clasifican antes de las rutas de navegación o de las teclas, el resto permanece en el orden de la instantánea. */
export function searchSettings(snapshot: HostSettingsSnapshot, query: string): readonly SettingsSearchHit[] {
  const needle = query.trim().toLowerCase();
  if (needle.length === 0) return [];
  const scored: { readonly row: SettingsSearchRow; readonly score: number }[] = [];
  for (const row of buildSettingsSearchRows(snapshot)) {
    if (!row.fields.some((field) => field.includes(needle))) continue;
    scored.push({ row, score: row.title.toLowerCase().includes(needle) ? 3 : 1 });
  }
  // sort Estable en ES2019+: mantenga el orden de las instantáneas, sin magia de clasificación innecesaria.
  scored.sort((left, right) => right.score - left.score);
  return scored.map(({ row }) => ({
    id: row.id,
    title: row.title,
    breadcrumb: row.breadcrumb,
    target: row.target,
  }));
}

/** Copia de ruta de navegación: clasificación › entrada. */
export function breadcrumbText(hit: SettingsSearchHit): string {
  return hit.breadcrumb.join(" › ");
}
