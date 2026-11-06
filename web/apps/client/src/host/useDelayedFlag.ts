import { useEffect, useState } from "react";

/** Retraso de la pantalla del esqueleto: no muestres el esqueleto cuando la carga sea rápida. Sólo después de este tiempo se considera “realmente esperando”. */
export const SKELETON_DELAY_MS = 300;

/** Juicio puro: espere el tiempo suficiente antes de que se muestre el esqueleto. */
export function isSkeletonDue(loading: boolean, waitedMs: number, delayMs: number = SKELETON_DELAY_MS): boolean {
  return loading && waitedMs >= delayMs;
}

/**
 * Cambie a "Mostrar el esqueleto solo después de que la carga exceda el retraso".
 *
 * Los datos suelen llegar en unos pocos cientos de milisegundos. Si lo subes directamente al esqueleto, parpadeará y será más deslumbrante; Aquí solo somos responsables de la visibilidad retrasada.
 * El temporizador se borra cuando llegan los datos.
 */
export function useDelayedFlag(active: boolean, delayMs: number = SKELETON_DELAY_MS): boolean {
  const [due, setDue] = useState(false);

  useEffect(() => {
    if (!active) {
      setDue(false);
      return;
    }
    const timer = window.setTimeout(() => setDue(true), delayMs);
    return () => window.clearTimeout(timer);
  }, [active, delayMs]);

  return due;
}
