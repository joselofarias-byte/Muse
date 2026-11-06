/**
 * `?perf=1` Modificador de depuración: solo imprime información de representación de instantáneas en la consola cuando este parámetro está configurado; generalmente no se imprime ni una sola línea.
 * No se introducen dependencias de seguimiento ni se realizan informes.
 */
export function perfFlag(search: string): boolean {
  const query = search.startsWith("?") ? search.slice(1) : search;
  try {
    return new URLSearchParams(query).get("perf") === "1";
  } catch {
    return false;
  }
}

export function formatPerfLine(messages: number, elapsedMs: number): string {
  const ms = Number.isFinite(elapsedMs) ? elapsedMs.toFixed(1) : "?";
  return `[muse perf] renderizado de instantáneas ${messages} mensajes / ${ms}ms`;
}
