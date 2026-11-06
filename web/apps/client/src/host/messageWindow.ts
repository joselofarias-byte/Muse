/**
 * Cálculo en ventana de listas de mensajes largas (función pura).
 *
 * ¿Por qué elegir "Cálculo de posición de desplazamiento" en lugar de IntersectionObserver? Lo que desea aquí es**Rango de índice determinado**
 * (¿Qué altura debe tener la parte superior/inferior?) La posición de desplazamiento puede calcular directamente la cabeza y la cola visibles;IO Sólo puedo decirte "cierto centinela es visible",
 * Hay dos elementos centinela más que colgar y devoluciones de llamada asincrónicas que manejar, lo cual es una pérdida de tiempo. El precio es alto y sólo se puede estimar.——Entonces usa la fila renderizada
 * La altura promedio se completa y la desviación se vuelve a calcular solo cuando la desviación excede el umbral.
 */
export const WINDOW_THRESHOLD = 150;
export const WINDOW_OVERSCAN = 20;
/** Valoración del resultado final sin medir ninguna altura de línea (altura común de un mensaje + espaciado). */
export const DEFAULT_ROW_HEIGHT = 72;

export interface WindowPlan {
  /** Si las ventanas están realmente habilitadas (falso si las entradas no superan el umbral, todas se representan). */
  readonly active: boolean;
  /** renderizado [start, end) este párrafo. */
  readonly start: number;
  readonly end: number;
  /** La altura de la ocupación de igual altura superior/inferior (px, utilizado para evitar que la altura del desplazamiento salte). */
  readonly topPad: number;
  readonly bottomPad: number;
}

export interface WindowInput {
  readonly itemCount: number;
  readonly scrollTop: number;
  readonly viewportHeight: number;
  /** Altura promedio de las filas renderizadas;0 Significa que aún no se ha medido. */
  readonly averageHeight: number;
  /** Cuando se pega en la parte inferior, el ancla de la ventana está al final (el chat está en la parte inferior de forma predeterminada). */
  readonly pinnedToEnd: boolean;
  readonly threshold?: number;
  readonly overscan?: number;
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(Math.max(value, min), max);
}

export function planWindow(input: WindowInput): WindowPlan {
  const threshold = input.threshold ?? WINDOW_THRESHOLD;
  const overscan = input.overscan ?? WINDOW_OVERSCAN;
  const count = Math.max(0, Math.floor(input.itemCount));
  if (count === 0) return { active: false, start: 0, end: 0, topPad: 0, bottomPad: 0 };
  if (count <= threshold) return { active: false, start: 0, end: count, topPad: 0, bottomPad: 0 };

  const rowHeight = input.averageHeight > 0 ? input.averageHeight : DEFAULT_ROW_HEIGHT;
  const viewport = input.viewportHeight > 0 ? input.viewportHeight : rowHeight * 8;
  const visible = Math.max(1, Math.ceil(viewport / rowHeight));

  let firstVisible: number;
  let lastVisible: number;
  if (input.pinnedToEnd) {
    lastVisible = count;
    firstVisible = count - visible;
  } else {
    firstVisible = Math.floor(clamp(input.scrollTop, 0, Number.MAX_SAFE_INTEGER) / rowHeight);
    lastVisible = firstVisible + visible;
  }

  const start = clamp(firstVisible - overscan, 0, count);
  const end = clamp(lastVisible + overscan, start, count);
  return {
    active: true,
    start,
    end,
    topPad: Math.round(start * rowHeight),
    bottomPad: Math.round((count - end) * rowHeight),
  };
}

/** Altura promedio de las filas renderizadas; devuelve indefinido si no hay filas disponibles. */
export function measureAverage(heights: readonly number[]): number | undefined {
  let total = 0;
  let used = 0;
  for (const height of heights) {
    if (!Number.isFinite(height) || height <= 0) continue;
    total += height;
    used += 1;
  }
  return used === 0 ? undefined : total / used;
}

/** Debe actualizarse la altura de la fila (el valor predeterminado es una zona muerta del 15 % para evitar fluctuaciones una vez medida). */
export function shouldUpdateAverage(previous: number, next: number, tolerance = 0.15): boolean {
  if (!Number.isFinite(next) || next <= 0) return false;
  if (previous <= 0) return true;
  return Math.abs(next - previous) / previous > tolerance;
}
