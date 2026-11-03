import { useEffect, useRef } from "react";
import { CloseIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";

interface ImageLightboxProps {
  readonly src: string;
  readonly onClose: () => void;
}

/** Selectores para controles enfocables: la trampa de enfoque simplemente alterna entre estos. */
const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Tab El siguiente índice del bucle (función pura, fácil para afirmaciones).
 *
 * `active` -1 significa que el foco no está en el cuadro de diálogo: avanzar al 0, retroceder al último.
 */
export function trapNextIndex(count: number, active: number, shift: boolean): number {
  if (count <= 0) return -1;
  if (active < 0) return shift ? count - 1 : 0;
  if (shift) return active === 0 ? count - 1 : active - 1;
  return active === count - 1 ? 0 : active + 1;
}

/**
 * Ver la imagen en pantalla completa.
 *
 * Máscara de punto apagada,Esc Cerrar, el botón "Cerrar" se cierra; al abrir, mueva el foco al cuadro de diálogo,
 * Tab / Shift+Tab En el bucle interno (trampa de enfoque), el foco vuelve al elemento original después del cierre.
 */
export function ImageLightbox({ src, onClose }: ImageLightboxProps) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
    closeRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab") return;
      const root = dialogRef.current;
      if (root === null) return;
      const nodes = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
      const active = document.activeElement instanceof HTMLElement ? nodes.indexOf(document.activeElement) : -1;
      const next = trapNextIndex(nodes.length, active, event.shiftKey);
      if (next < 0) return;
      event.preventDefault();
      nodes[next]?.focus();
    };

    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      previous?.focus();
    };
  }, [onClose]);

  return (
    <div
      className="host-lightbox"
      role="dialog"
      aria-modal="true"
      aria-label={t("image.previewTitle")}
      ref={dialogRef}
      onClick={onClose}
    >
      <button
        className="quiet host-lightbox-close"
        type="button"
        aria-label={t("image.previewClose")}
        ref={closeRef}
        onClick={onClose}
      >
        <CloseIcon />
      </button>
      <img className="host-lightbox-image" src={src} alt={t("image.previewTitle")} onClick={(event) => event.stopPropagation()} />
    </div>
  );
}
