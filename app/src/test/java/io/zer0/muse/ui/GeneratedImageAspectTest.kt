package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Selección de marco de tarjeta de arte.:Sólo el URI de datos se adelanta al marco fijo,URL / Las rutas de los archivos no tienen preferencia.
 *
 * Esta rama es la clave de la apariencia de esta optimización continua.:
 *  - data-URI El mapa de bits se decodifica de forma asincrónica en el hilo IO,La proporción no se puede conocer de antemano.,Requerido para ocupar previamente el marco,De lo contrario, antes y después de la decodificación.
 *    La altura saltará una vez.(Superponer animateContentSize cuando el desplazamiento es una cadena relayout);
 *  - URL / También se desconoce la proporción de la ruta del archivo.,Pero están cargados por Coil y no tienen un valor de preferencia confiable.,bloquear en 4:3
 *    hará horizontal/Hay una gran cantidad de espacio en blanco para imágenes verticales largas.,pertenece a la degeneración —— Debe continuar caminando el intervalo de altura de 120..420 dp.
 *
 * La afirmación se escribe como "data-URI Retorno 4:3, todos los demás serán devueltos. null(No reservado)",Si un día la sentencia se escribe incorrectamente,
 * "Siempre 4:3" o "Nunca adelantarse",Se colgará directamente aquí.
 */
class GeneratedImageAspectTest {

    @Test
    fun `data uri gets fixed 4 by 3 frame`() {
        val aspect = generatedImageAspectOf("data:image/png;base64,AAAA")

        assertEquals(4f / 3f, requireNotNull(aspect), 1e-6f)
    }

    @Test
    fun `data uri frame does not depend on payload`() {
        val small = generatedImageAspectOf("data:image/png;base64,AAAA")
        val large = generatedImageAspectOf("data:image/jpeg;base64," + "x".repeat(4096))

        assertEquals(small, large)
    }

    @Test
    fun `http url does not preallocate frame`() {
        assertNull(generatedImageAspectOf("https://example.com/generated/a.png"))
    }

    @Test
    fun `absolute file path does not preallocate frame`() {
        assertNull(generatedImageAspectOf("/sdcard/Android/data/io.zer0.muse/files/a.png"))
    }

    @Test
    fun `file uri does not preallocate frame`() {
        assertNull(generatedImageAspectOf("file:///data/user/0/io.zer0.muse/files/stickers/s1.webp"))
    }

    @Test
    fun `non image data uri does not preallocate frame`() {
        assertNull(generatedImageAspectOf("data:audio/mpeg;base64,AAAA"))
    }

    @Test
    fun `blank model does not preallocate frame`() {
        assertNull(generatedImageAspectOf(""))
    }
}
