import { useState } from "react";
import { resolveAvatarUrl } from "./messageMedia.js";

interface HostAvatarProps {
  /** Nombre del asistente: Tome la primera letra si no hay ningún avatar disponible. */
  readonly name: string;
  readonly uri?: string | undefined;
  readonly size?: "small" | "large";
}

/**
 * Avatar asistente.
 *
 * El uri del avatar puede ser la ruta local del teléfono celular (el navegador no puede obtenerlo) o puede ser `/api/` camino de origen o http(s);
 * Si no se puede recuperar o la imagen no se carga, se devolverá la primera letra del nombre.——No quedan marcos vacíos ni imágenes rotas.
 * El nombre siempre viene dado por el texto al lado, que aquí está oculto a los lectores de pantalla para evitar leerlo dos veces.
 */
export function HostAvatar({ name, uri, size = "small" }: HostAvatarProps) {
  const [failed, setFailed] = useState(false);
  const src = failed ? undefined : resolveAvatarUrl(uri);
  const initial = firstChar(name);
  return (
    <span className={size === "large" ? "host-avatar is-large" : "host-avatar"} aria-hidden="true">
      {src === undefined ? (
        <span className="host-avatar-initial">{initial}</span>
      ) : (
        <img src={src} alt="" onError={() => setFailed(true)} />
      )}
    </span>
  );
}

function firstChar(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length === 0) return "?";
  return [...trimmed][0] ?? "?";
}
