/**
 * Escribir texto en el portapapeles.
 *
 * Host La página es una red de área local. `http://` Contexto abierto, no seguro:Chrome / Safari bajo esta fuente
 * No expuesto en absoluto `navigator.clipboard`. Así que primero vaya con la API del Portapapeles asíncrona (localhost nativo,HTTPS implementación),
 * Si falla o falta, volverá a la ruta anterior de "ocultar área de texto + execCommand".——Seguirá funcionando dentro de llamadas sincrónicas a gestos de usuario.
 * Devolver falso significa que ninguna de las rutas tiene éxito. La persona que llama debe informar el error y no puede pretender que la copia se realizó correctamente.
 */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  if (text.length === 0) return false;
  const clipboard = typeof navigator !== "undefined" ? navigator.clipboard : undefined;
  if (clipboard?.writeText !== undefined) {
    try {
      await clipboard.writeText(text);
      return true;
    } catch {
      // Si se deniega el permiso (gesto de no usuario, página desenfocada), continúe con los pasos a continuación.
    }
  }
  if (typeof document === "undefined") return false;
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.setAttribute("aria-hidden", "true");
  area.style.position = "fixed";
  area.style.top = "0";
  area.style.left = "0";
  area.style.width = "1px";
  area.style.height = "1px";
  area.style.opacity = "0";
  const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  document.body.appendChild(area);
  let copied = false;
  try {
    area.select();
    copied = document.execCommand("copy");
  } catch {
    copied = false;
  } finally {
    area.remove();
    if (previous !== null) {
      try {
        previous.focus();
      } catch {
        // Devolver el foco al elemento original no afecta el resultado de la copia en sí.
      }
    }
  }
  return copied;
}
