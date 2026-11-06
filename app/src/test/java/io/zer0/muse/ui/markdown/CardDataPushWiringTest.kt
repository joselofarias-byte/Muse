package io.zer0.muse.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato de cableado para push de datos de tarjeta.
 *
 * Verificación de comportamiento en `ci/script/card_script_contract_test.js`(Node Realmente ejecuta ese JS en el sandbox).
 * Lo que está bloqueado aquí es**Kotlin ¿Has conectado los cables del lateral?**: Inyecte etiqueta de capacidad, llame a push después de obtener WebView,
 * Y la degradación "Volver al sondeo cuando no se admite la inserción" no se puede eliminar.
 *
 * Por qué vale la pena bloquearlo: el fallo de cualquiera de estos tres no provocará que falle la compilación. ——
 *   - Marca de habilidad faltante → Las tarjetas se sondean para siempre (volviendo a un cronómetro por tarjeta);
 *   - menos evaluateJavascript → La tarjeta está suscrita pero nunca recibe actualizaciones;
 *   - Eliminar rama de votación → Los hosts sin CardDataStore (visualización de artefactos, etc.) pierden la capacidad de actualizarse.
 */
class CardDataPushWiringTest {

    private fun readAppSource(relative: String): String =
        File("src/main/java/io/zer0/muse/$relative").readText()

    @Test
    fun `El script de la tarjeta selecciona push o poll por etiqueta de capacidad`() {
        val source = readAppSource("ui/markdown/card/CardScript.kt")
        val subscribeAt = source.indexOf("window.muse.data.onChange = function(cb){")
        require(subscribeAt >= 0) { "onCambiar definición no encontrada" }
        val body = source.substring(subscribeAt, (subscribeAt + 2_000).coerceAtMost(source.length))

        val pushAt = body.indexOf("if (window.__museDataPush)")
        val intervalAt = body.indexOf("setInterval(")
        assertTrue("Se debe conservar el criterio sobre las capacidades de empuje", pushAt >= 0)
        assertTrue("Se deben preservar las rebajas de las encuestas", intervalAt >= 0)
        assertTrue(
            "El sondeo debe programarse después de presionar la rama: iniciar el cronómetro primero significa sondear para siempre",
            pushAt < intervalAt,
        )
        assertTrue("La entrada push debe ser invocable por el host", source.contains("window.__museDataEvent = function()"))
    }

    @Test
    fun `Después de que el host obtenga WebView, presione pushToken para enviarlo`() {
        val source = readAppSource("ui/markdown/card/CardRuntime.kt")
        assertTrue(
            "Se debe llamar al script push",
            source.contains("evaluateJavascript(CardScript.DATA_EVENT_JS"),
        )
        assertTrue(
            "Push debe usar pushToken como clave (de lo contrario, no se activará incluso si los datos cambian)",
            source.contains("LaunchedEffect(webViewRef, host.pushToken)"),
        )
        assertTrue(
            "Cuando no se admite la inserción, se debe salir temprano para evitar ejecuciones repetidas sin sentido. JS",
            source.contains("if (host.pushToken == null) return@LaunchedEffect"),
        )
        assertTrue(
            "Para salir de la combinación, borre la referencia de WebView",
            source.contains("onWebViewReleased = { webViewRef = null }"),
        )
    }

    @Test
    fun `El indicador de capacidad de inserción solo se inyecta cuando se admite la inserción`() {
        val source = readAppSource("ui/markdown/card/CardRuntime.kt")
        assertTrue(
            "Se debe inyectar la etiqueta __museDataPush",
            source.contains("window.__museDataPush=true"),
        )
        assertTrue(
            "Los marcadores deben controlarse mediante pushEnabled y no se pueden inyectar incondicionalmente",
            source.contains("val pushBoot = if (pushEnabled)"),
        )
        assertTrue(
            "HTML debe traer pushBoot",
            source.contains("\$idBoot\$pushBoot\$safe\$scripts"),
        )
    }

    @Test
    fun `Punto de llamada con tarjeta proporcionado pushToken`() {
        val source = readAppSource("ui/markdown/RichContentCard.kt")
        assertTrue(
            "SvgCard/HtmlCard Debes entregar pushToken a CardRuntime",
            Regex("pushToken = rememberCardPushToken\\(store, cardId\\)")
                .findAll(source)
                .count() >= 2,
        )
        assertTrue(
            "La suscripción solo debe centrarse en la clave de esta tarjeta; de lo contrario, cualquier cambio en los datos de la tarjeta activará todas las tarjetas.",
            source.contains("store.data.map { it[cardId] }.distinctUntilChanged()"),
        )
    }
}
