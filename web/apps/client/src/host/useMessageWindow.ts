import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from "react";
import { measureAverage, planWindow, shouldUpdateAverage, type WindowPlan } from "./messageWindow.js";

interface UseMessageWindowOptions {
  readonly scrollRef: RefObject<HTMLDivElement | null>;
  readonly itemCount: number;
  /** Cuando se pega en la parte inferior, el ancla de la ventana está al final (el chat está en la parte inferior de forma predeterminada). */
  readonly pinnedToEnd: boolean;
  /** Cuando cambia la sesión, etc., es necesario volver a medir la medición. */
  readonly resetKey: string;
}

/**
 * Ventanas de listas largas: calcule el intervalo que se representará en función de la posición de desplazamiento y utilice marcadores de posición de igual altura en ambos extremos para soportar la altura total.
 *
 * La altura de las filas solo se puede estimar: mida la altura promedio de las filas renderizadas una vez después de renderizar y solo actualice la estimación si la desviación excede el 15 % (con zona muerta,
 * Evite medir y agitar cada vez). Cuando las entradas no superan el umbral, plan.active es falso y la persona que llama se muestra en su totalidad como de costumbre.
 */
export function useMessageWindow({ scrollRef, itemCount, pinnedToEnd, resetKey }: UseMessageWindowOptions): WindowPlan {
  const [metrics, setMetrics] = useState({ top: 0, height: 0 });
  const averageRef = useRef(0);
  const [averageTick, setAverageTick] = useState(0);

  // desplazarse:rAF Para acelerar, sólo se recuerdan la posición y la altura de la ventana gráfica; Los cambios en el tamaño del contenedor también deben recalcularse.
  useEffect(() => {
    const element = scrollRef.current;
    if (element === null) return;
    let frame = 0;
    const read = () => {
      frame = 0;
      setMetrics({ top: element.scrollTop, height: element.clientHeight });
    };
    const onScroll = () => {
      if (frame !== 0) return;
      frame = window.requestAnimationFrame(read);
    };
    element.addEventListener("scroll", onScroll, { passive: true });
    read();
    const observer = typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(read);
    observer?.observe(element);
    return () => {
      element.removeEventListener("scroll", onScroll);
      if (frame !== 0) window.cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [scrollRef, resetKey]);

  const plan = useMemo(
    () =>
      planWindow({
        itemCount,
        scrollTop: metrics.top,
        viewportHeight: metrics.height,
        averageHeight: averageRef.current,
        pinnedToEnd,
      }),
    // averageTick Es una señal de que "la estimación acaba de cambiar".ref No desencadena un recálculo por sí solo.
    [itemCount, metrics, pinnedToEnd, averageTick],
  );

  // Mida la altura de la fila una vez después del renderizado; actualice la estimación y vuelva a calcular la ventana solo cuando la desviación sea lo suficientemente grande.
  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (element === null) return;
    const rows = Array.from(element.querySelectorAll<HTMLElement>("[data-host-row]"));
    const average = measureAverage(rows.map((row) => row.offsetHeight));
    if (average === undefined || !shouldUpdateAverage(averageRef.current, average)) return;
    averageRef.current = average;
    setAverageTick((tick) => tick + 1);
  }, [plan.start, plan.end, itemCount, resetKey, scrollRef]);

  return plan;
}
