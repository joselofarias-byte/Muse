import type { HostSettingsSnapshot } from "../api.js";

/**
 * Configure la derivación local de instantáneas con actualizaciones optimistas.
 *
 * El propósito de formar un módulo separado es permitir una verificación separada sin WebSocket: proyección de campo (escriba una copia, tres lugares surten efecto al mismo tiempo) y
 * La reversión (escribir el valor anterior sin cambios) son todas funciones puras.Host "Restaurar el status quo" cuando se rechaza un escrito es llamar a aplicar nuevamente.
 */

/** El valor actual del interruptor: ya sea la lista marcada o la lista de alternancia en la entrada (dos proyecciones de los mismos datos). */
export function toggleValue(snapshot: HostSettingsSnapshot, key: string): boolean | undefined {
  for (const group of snapshot.groups) {
    for (const entry of group.entries) {
      if (entry.toggleKey === key && entry.checked !== undefined) return entry.checked;
    }
  }
  return snapshot.toggles.find((item) => item.key === key)?.enabled;
}

/** El valor actual de una clave: tiene prioridad `values[]`; de lo contrario, recurra a la antigua proyección booleana de dos lugares. */
export function settingValueOf(snapshot: HostSettingsSnapshot, key: string): unknown {
  const fromValues = snapshot.values?.find((item) => item.key === key);
  if (fromValues !== undefined) return fromValues.value;
  return toggleValue(snapshot, key);
}

/**
 * Actualización optimista:**al mismo tiempo**Cambiar tres proyecciones (values / toggles / entrada marcada),
 * Debido a que la antigua página de inicio solo mira los dos últimos lugares y la nueva subpágina solo mira `values`.Host No hay instantáneas de retroceso, esta es la única retroalimentación inmediata;
 * Cuando se rechaza la escritura, use la misma función para volver a escribir el valor anterior y se completa la reversión.
 */
export function applySettingValue(snapshot: HostSettingsSnapshot, key: string, value: unknown): HostSettingsSnapshot {
  const values = snapshot.values;
  const asBoolean = typeof value === "boolean" ? value : undefined;
  return {
    ...snapshot,
    groups: snapshot.groups.map((group) => ({
      ...group,
      entries: group.entries.map((entry) =>
        entry.toggleKey === key && asBoolean !== undefined ? { ...entry, checked: asBoolean } : entry,
      ),
    })),
    toggles: snapshot.toggles.map((item) =>
      item.key === key && asBoolean !== undefined ? { ...item, enabled: asBoolean } : item,
    ),
    ...(values === undefined ? {} : { values: values.map((item) => (item.key === key ? { ...item, value } : item)) }),
  };
}
