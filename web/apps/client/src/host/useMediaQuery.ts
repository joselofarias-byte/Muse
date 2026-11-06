import { useEffect, useState } from "react";

/**
 * Suscríbase a una consulta de medios CSS.
 *
 * Solo se usa en algunos lugares donde "el diseño del equipo afecta el comportamiento"——Actualmente, solo existe la columna izquierda de la sesión: el archivo del escritorio es residente y debe recuperarse previamente.
 * Vista previa de la sesión; Este lote de solicitudes no debe enviarse cuando el cajón de archivos del teléfono/tableta esté cerrado. Las diferencias de engranaje puramente visuales se dejan en manos de CSS.
 * No lo vuelvas a hacer en JS.
 *
 * La cadena de consulta debe coincidir con el punto de interrupción en estilos.css (el script de aserción se comparará con el número 1024).
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => matchesNow(query));

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);

  return matches;
}

/** Los entornos sin matchMedia (navegadores antiguos) se tratan como no coincidentes: se pueden recuperar previamente una vez como máximo sin fallar. */
function matchesNow(query: string): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia(query).matches;
}
