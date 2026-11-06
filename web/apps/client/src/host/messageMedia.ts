/**
 * Normalización de direcciones de medios: distribuir el Host `imageUrls` / `voiceUrl` Conviértase en un src que el navegador realmente pueda cargar.
 *
 * Nuevo anfitrión (capabilityFlags (incluidos los medios) proporciona la ruta relativa del mismo origen. `/api/media/<token>`,Cookie cinturón automático,
 * Úselo como src directamente. Es posible que los datos antiguos aún estén en estos cuatro formatos, los cuales deben ser compatibles:
 *  - `data:` URI / `http(s):` URL / `blob:`: usado directamente;
 *  - base64 desnuda: complemento `data:image/png;base64,` prefijo (App lado `imageBase64List` Mismo método de escritura);
 *  - `file:///data/user/...` O la antigua ruta absoluta: el navegador no puede leerla, por lo que se clasifica como no disponible y se utiliza como marcador de posición.
 */
const BASE64_SAMPLE = 64;

export type MessageImage =
  | { readonly kind: "src"; readonly src: string }
  /** No se pueden obtener bytes: archivos locales en el teléfono (datos antiguos) o formulario de dirección completamente irreconocible. */
  | { readonly kind: "unavailable"; readonly ref: string; readonly reason: "local-file" | "unknown" };

export function resolveMessageImages(urls: readonly string[] | undefined): readonly MessageImage[] {
  if (urls === undefined || urls.length === 0) return [];
  return urls.map((url) => {
    const trimmed = url.trim();
    if (trimmed.length === 0) return { kind: "unavailable", ref: "", reason: "unknown" };
    const src = borrowableSource(trimmed);
    if (src !== undefined) return { kind: "src", src };
    if (isRawBase64(trimmed)) return { kind: "src", src: `data:image/png;base64,${trimmed}` };
    // La ruta local del teléfono celular (datos antiguos) y el "formulario de dirección no reconocida" se mencionan por separado para evitar engañar al lector al encontrar el archivo.
    const local = trimmed.startsWith("file://") || trimmed.startsWith("content://") || trimmed.startsWith("/");
    return { kind: "unavailable", ref: trimmed, reason: local ? "local-file" : "unknown" };
  });
}

/**
 * Dirección de voz. Devolver indefinido significa que el navegador no puede obtener esta dirección (antigua dirección del Host). `file://` referencia),
 * Por lo tanto, la persona que llama baja a una barra de duración estática en lugar de colgar una que inevitablemente informará un error. `<audio>`.
 */
export function resolveVoiceUrl(url: string | undefined): string | undefined {
  if (url === undefined) return undefined;
  const trimmed = url.trim();
  if (trimmed.length === 0) return undefined;
  return borrowableSource(trimmed);
}

/**
 * Dirección de avatar: el mismo conjunto de reglas que los medios (misma fuente `/api/` camino,http(s),data).
 * El uri del avatar también puede apuntar a un archivo local en el teléfono y el navegador no puede recuperarlo.——La persona que llama devuelve la letra inicial en consecuencia.
 */
export function resolveAvatarUrl(uri: string | undefined): string | undefined {
  if (uri === undefined) return undefined;
  const trimmed = uri.trim();
  if (trimmed.length === 0) return undefined;
  return borrowableSource(trimmed);
}

/** Varios tipos de direcciones que el navegador puede tomar por sí mismo: rutas relativas del mismo origen,http(s),data,blob. */
function borrowableSource(value: string): string | undefined {
  if (value.startsWith("data:") || value.startsWith("blob:")) return value;
  if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//")) return value;
  // Medios de origen: nuevos anfitriones /api/media/<token>. Otros que comienzan con / son rutas absolutas de archivos de datos antiguos del teléfono celular.
  // No se puede utilizar como URL (se convertirá en una imagen rota 404).
  if (value.startsWith("/api/")) return value;
  return undefined;
}

/** Solo agregue el prefijo cuando sea realmente como base64 para evitar tratar la ruta local como datos de imagen. */
function isRawBase64(value: string): boolean {
  if (value.length < BASE64_SAMPLE) return false;
  if (value.includes("/") && !/^[A-Za-z0-9+/=\s]+$/.test(value)) return false;
  return /^[A-Za-z0-9+/]+={0,2}$/.test(value.replace(/\s+/g, ""));
}
