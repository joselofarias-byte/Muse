package io.zer0.muse.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-6: Determinante de fallas de producción [ToolResultJudge.isSuccess] La prueba matricial completa.
 *
 * Objeto guardián: subagente/tarjeta de tarea/tareas programadas/Las pruebas determinan que la misma implementación se comparte en todas partes.,Debilitado en cualquier lugar
 * (Sólo reconoce 1-2 prefijos)hará que "el modelo piense que la herramienta tiene éxito",La deriva de "la recepción pero sin resultados".
 */
class ToolResultJudgeTest {

    @Test
    fun `plain success results are judged success`() {
        assertTrue(ToolResultJudge.isSuccess("Cálculo completado: 1 + 1 = 2"))
        assertTrue(ToolResultJudge.isSuccess("saved"))
        assertTrue(ToolResultJudge.isSuccess("ha sido escrito /tmp/a.txt"))
        assertTrue(ToolResultJudge.isSuccess("{\"ok\": true}"))
    }

    @Test
    fun `error keyword prefix is a failure`() {
        assertFalse(ToolResultJudge.isSuccess("error: open_url Red inalcanzable"))
    }

    @Test
    fun `bracket failure markers are failures`() {
        assertFalse(ToolResultJudge.isSuccess("[tiempo de espera] La herramienta browser_search no responde durante 30 segundos"))
        assertFalse(ToolResultJudge.isSuccess("[interrumpir] Ejecución de herramienta de ejecución de código cancelada"))
        assertFalse(ToolResultJudge.isSuccess("[error] verify falló"))
        assertFalse(ToolResultJudge.isSuccess("[falló] verify falló"))
        assertFalse(ToolResultJudge.isSuccess("[Cancelar] El usuario cancela"))
        assertFalse(ToolResultJudge.isSuccess("[La salida de la herramienta está truncada] El contenido es demasiado largo."))
    }

    @Test
    fun `all chinese error prefixes are failures`() {
        val failures = listOf(
            "La herramienta no existe: foo",
            "Excepción de ejecución de herramienta: boom",
            "Falló el análisis de parámetros: bad json",
            "skill excepción de ejecución: oops",
            "Anulación de ruta: /etc/passwd",
            "Parámetro faltante: url",
            "Archivo demasiado grande: 100MB",
            "El archivo no existe: /x",
            "desconocido skill: y",
            "URL debe ser https",
            "No se puede leer el archivo",
            "Sesión no encontrada abc",
            "sub-asistente no pudo ejecutarse",
            "error: Error al generar la imagen",
            "falló: verify",
            "tiempo de espera: Las solicitudes exceden 30s",
            "Cancelar: El usuario cancela",
            "No configurado API Key",
            "esta vacio: La lista de resultados está vacía",
            "No disponible: Servicio no disponible",
        )
        failures.forEach { assertFalse("should fail: $it", ToolResultJudge.isSuccess(it)) }
        // Brechas conocidas(No bloquear): El determinante sólo hace coincidencias de "prefijos",Texto de error de postfix como
        // Actualmente, "Error en la generación de imágenes" se considera exitoso cuando todo el párrafo no contiene un prefijo de error. —— Ver notas sobre
        // ToolResultJudge.kt BRACKET_FAILURE_MARKERS cerca,Déjelo para un apriete especial.
    }

    @Test
    fun `structured error json is a failure`() {
        assertFalse(ToolResultJudge.isSuccess("""{"error": "error de red", "code": 500}"""))
        assertFalse(ToolResultJudge.isSuccess("""{"data": null, "error": "UNAUTHORIZED"}"""))
    }

    @Test
    fun `non-error structured json is a success`() {
        assertTrue(ToolResultJudge.isSuccess("""{"result": "2", "ok": true}"""))
    }

    @Test
    fun `result containing error word in the middle is still a success`() {
        assertTrue(ToolResultJudge.isSuccess("Consulta completada,No se encontraron registros relacionados con errores"))
    }

    @Test
    fun `long document text starting with a weak failure word is a success`() {
        // v2.6.2(auditoría de usuario): read_file/web_fetch regresó**texto normal**si“error/falló/incapaz”
        // comienzo,No se puede juzgar erróneamente como una falla (de lo contrario, la tarjeta de tareas mostrará una falla + reintentar el reinicio de inyección).
        val docAboutErrors = "guía de manejo de errores\n\nEste artículo presenta tres patrones comunes de manejo de errores:" +
            "Reintento, reversión y disyuntor. En la ingeniería real, estos modos……".repeat(10)
        assertTrue(ToolResultJudge.isSuccess(docAboutErrors))

        val novelOpening = "El fracaso es la madre del éxito. Esta es una verdad que todo el mundo sabe pero pocos entienden realmente. La historia comienza con aquel verano de hace veinte años.……".repeat(6)
        assertTrue(ToolResultJudge.isSuccess(novelOpening))

        val cannotDoc = "Pasos para solucionar problemas por no iniciar el servicio: primero lea los registros, luego verifique la ocupación del puerto y finalmente confirme el archivo de configuración……".repeat(8)
        assertTrue(ToolResultJudge.isSuccess(cannotDoc))
    }

    @Test
    fun `short bare failure label is still a failure`() {
        // Las palabras de falla débiles aún deberían fallar como etiquetas cortas (la herramienta de hecho devolverá cadenas cortas directamente).
        assertFalse(ToolResultJudge.isSuccess("falló(Coordenadas fuera de límites)"))
        assertFalse(ToolResultJudge.isSuccess("falló(Nombre de paquete no válido)"))
        assertFalse(ToolResultJudge.isSuccess("No se puede abrir el flujo de salida"))
        assertFalse(ToolResultJudge.isSuccess("No compatible: botones de pantalla virtual home"))
    }
}
