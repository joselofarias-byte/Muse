import { useState } from "react";
import { useI18n } from "../i18n/I18nProvider.js";
import type { MessageImage } from "./messageMedia.js";

interface MessageImagesProps {
  /** Dirección de imagen normalizada (ver messageMedia.ts). */
  readonly images: readonly MessageImage[];
  /** Haga clic en una imagen para verla en pantalla completa. */
  readonly onOpen?: ((src: string) => void) | undefined;
}

/**
 * Imágenes en burbujas.
 *
 * - Límite de altura de una sola imagen (`42vh`, consistente con el límite superior del cuerpo del bloque plegado), moverse a través de dos columnas de cuadrículas en múltiples imágenes;
 * - Haga clic para ver en pantalla completa;
 * - Cuando la carga falla o la dirección no está disponible, se proporcionará un bloque de marcador de posición y el navegador nunca podrá dibujar un icono de imagen rota.
 */
export function MessageImages({ images, onOpen }: MessageImagesProps) {
  const { t } = useI18n();
  if (images.length === 0) return null;
  const grid = images.length > 1;
  return (
    <div className={grid ? "host-bubble-images is-grid" : "host-bubble-images"}>
      {images.map((image, index) =>
        image.kind === "unavailable" ? (
          <span className="host-bubble-image-missing" key={`missing-${index}`} role="status">
            {image.reason === "local-file" ? t("image.missingLocal") : t("image.unavailable")}
          </span>
        ) : (
          <ImageTile key={`${image.src}-${index}`} src={image.src} onOpen={onOpen} />
        ),
      )}
    </div>
  );
}

function ImageTile({ src, onOpen }: { readonly src: string; readonly onOpen?: ((src: string) => void) | undefined }) {
  const { t } = useI18n();
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span className="host-bubble-image-missing" role="status">
        {t("image.loadFailed")}
      </span>
    );
  }
  return (
    <button className="quiet host-bubble-image" type="button" aria-label={t("image.view")} onClick={() => onOpen?.(src)}>
      <img src={src} alt={t("image.alt")} loading="lazy" decoding="async" onError={() => setFailed(true)} />
    </button>
  );
}
