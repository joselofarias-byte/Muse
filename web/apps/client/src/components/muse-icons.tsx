/**
 * Muse Conjunto mínimo de iconos —— SVG en línea,pathData copiar
 * `app/src/main/java/io/zer0/muse/ui/common/icons/MuseIcons.kt`(misma copia 24×24 ventana gráfica,
 * Cabeza redondeada y esquinas redondeadas). El ancho y el tamaño de la línea provienen de tokens.json:`.muse-icon` usar
 * `--muse-icons-stroke-width`(1.7) y `--muse-icons-icon`(24), si necesitas otro equipo, envíalo `size`.
 *
 * Estos iconos están relacionados con `icons.tsx`(Host Un conjunto que viene con la funda del teléfono) Coexistencia: hay una apariencia y sensación realizadas,
 * Cambiarlo hará que la interfaz existente regrese, por lo que las nuevas primitivas tomarán esta parte.
 */
import type { SVGProps } from "react";
import "./primitives.css";

type MuseIconProps = {
  /** Anule el tamaño visual predeterminado (el valor predeterminado es el token --muse-icons-icon). */
  readonly size?: number;
  readonly className?: string;
};

function iconProps(size: number | undefined, className: string | undefined): SVGProps<SVGSVGElement> {
  const props: SVGProps<SVGSVGElement> = {
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    className: className ?? "muse-icon",
    "aria-hidden": true,
    focusable: false,
  };
  if (size !== undefined) {
    props.width = size;
    props.height = size;
  }
  return props;
}

/** Retorno:arrow-left(Dos trazos, línea horizontal y esquina doblada). */
export function IconBack({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M19 12H5" />
      <path d="M11 5l-7 7 7 7" />
    </svg>
  );
}

/** Más:more-horizontal(tres puntos). */
export function IconMore({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M6 12h.01M12 12h.01M18 12h.01" />
    </svg>
  );
}

/** Enviar:send(Avión de papel macizo,MuseIcons.send Simplemente llene en lugar de trazo). */
export function IconSend({ size, className }: MuseIconProps) {
  const props = iconProps(size, className);
  return (
    <svg {...props} fill="currentColor" stroke="none">
      <path d="M20.2 3.8L3.9 10.9a.6.6 0 0 0 .07 1.13l5.9 1.9 1.9 5.9a.6.6 0 0 0 1.13.07L20.2 3.8z" />
    </svg>
  );
}

/** Adjuntos:paperclip. */
export function IconAttach({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M16.6 9.4l-6.2 6.2a2.1 2.1 0 0 0 3 3l6.2-6.2a4.1 4.1 0 0 0-5.8-5.8L6.6 13.6a6.1 6.1 0 0 0 8.6 8.6l5.6-5.5" />
    </svg>
  );
}

/** Micrófono:microphone(Cápsula + arco de stent + columna). */
export function IconMic({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M12 3.5h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z" />
      <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0" />
      <path d="M12 18v2.5" />
    </svg>
  );
}

/** Cerrar:x. */
export function IconClose({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

/** Mensaje de error:alert-circle(círculo + línea vertical + punto). */
export function IconAlertCircle({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0" />
      <path d="M12 7.5V13" />
      <path d="M12 16.5h.01" />
    </svg>
  );
}

/** Flecha derecha:chevron-right. */
export function IconChevronRight({ size, className }: MuseIconProps) {
  return (
    <svg {...iconProps(size, className)}>
      <path d="M9.5 6l6 6-6 6" />
    </svg>
  );
}
