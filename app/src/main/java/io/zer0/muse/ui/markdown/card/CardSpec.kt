package io.zer0.muse.ui.markdown.card

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.markdown.CardAction

/**
 * ¿En qué se debe convertir la tarjeta? —— Datos puros, sin dependencias Compose / Android / WebView.
 *
 * La primera regla de reescritura: todo lo necesario para el renderizado se determina antes de ingresar al contenedor.
 * La implementación anterior dividía "el código fuente, el tamaño, los datos vinculantes y si es interactivo" en siete u ocho parámetros.
 * Hay capas de derivación dentro del contenedor y no está claro quién decide qué al final.
 */
internal data class CardSpec(
    /** El lenguaje del código fuente determina qué ruta de renderizado tomar. */
    val language: CardLanguage,
    /** Texto fuente. */
    val code: String,
    /** Tamaño previsto. Seguir el contenido de forma predeterminada. */
    val size: CardSize = CardSize.Auto,
    /** Datos JSON vinculados al host (show_card parámetro de datos),null Indica no. */
    val data: String? = null,
)

/** Ruta de representación de tarjetas. */
internal enum class CardLanguage { HTML, SVG, CHART, MERMAID, PLAIN }

/**
 * Tamaño previsto.
 *
 * Sólo hay tres estados, no hay un cuarto estado. —— En la implementación anterior, "altura fija + desplazamiento en la tarjeta + seguimiento de medidas" se pueden superponer arbitrariamente.
 * El resultado de la superposición es que cualquiera de los lados puede cambiar la altura. Aquí se condensa en opciones mutuamente excluyentes.
 */
internal sealed interface CardSize {
    /** Qué seguir: altura = Altura del contenido, la tarjeta en sí no se desplaza (nada que desplazar). */
    data object Auto : CardSize

    /** Altura fija: El sobrante se desplaza dentro de la tarjeta. */
    data class Fixed(val height: Dp) : CardSize

    /** Relación de aspecto: el ancho es completo, el alto es proporcional, el contenido puede exceder → Desplácese dentro de la tarjeta. */
    data class Ratio(val ratio: Float) : CardSize
}

/**
 * Analice el parámetro de altura de show_card.
 *
 * Tolerancia a fallos en lugar de informe de errores: el modelo se escribe como `520`,`520dp`,`520 px`,`16:9`,`16:9` Todos reconocidos;
 * Respuesta irreconocible Auto —— Si un tamaño está escrito torcidamente, no se debe mostrar toda la tarjeta.
 */
internal fun parseCardSize(raw: String?): CardSize {
    val text = raw?.trim()?.lowercase().orEmpty()
    if (text.isEmpty() || text == "auto") return CardSize.Auto
    return parseRatio(text) ?: parseFixed(text)
}

/** `16:9` / `16:9` / `4x3`. */
private fun parseRatio(text: String): CardSize.Ratio? {
    val parts = text.split(':', ':', 'x').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val w = parts[0].toFloatOrNull()
    val h = parts[1].toFloatOrNull()
    val usable = w != null && h != null && w > 0f && h > 0f
    return if (usable) CardSize.Ratio(w / h) else null
}

/** `520` / `520dp` / `520 px`. prensa de valor numérico dp —— Es imposible que el modelo conozca la densidad de píxeles del dispositivo de destino. */
private fun parseFixed(text: String): CardSize {
    val digits = text.removeSuffix("dp").removeSuffix("px").trim()
    val value = digits.toFloatOrNull()
    return if (value != null && value > 0f) CardSize.Fixed(value.dp) else CardSize.Auto
}

/** Huele el idioma según el código fuente (se usa cuando el código vallado no escribe el idioma). */
internal fun detectCardLanguage(code: String): CardLanguage = when {
    code.trimStart().startsWith("<svg", ignoreCase = true) -> CardLanguage.SVG
    code.trimStart().startsWith("<?xml", ignoreCase = true) -> CardLanguage.SVG
    else -> CardLanguage.HTML
}

/**
 * Todas las capacidades proporcionadas por el lado anfitrión a la tarjeta están empaquetadas en un objeto.
 *
 * Las transferencias separadas se mezclarán con el tamaño y el idioma, y ​​los parámetros aumentarán a más de una docena; y "¿se puede devolver esta tarjeta?"
 * Es un juicio general y la persona que llama no debe declararlo punto por punto.
 */
internal data class CardHost(
    /** Devolución de llamada de interacción;null Indica que no hay puente (escenarios de solo lectura, como visualización de artefactos, pasos de la tarjeta de tareas, etc.). */
    val onAction: ((CardAction) -> Unit)? = null,
    /** Leer datos vinculantes por cardId;null Indica que no se proporcionaron datos. */
    val readData: ((String) -> String?)? = null,
    /** Tarjeta de identidad. Solo generado por el host (ID de mensaje/ID de instancia de tarjeta), la herramienta no participa. */
    val cardId: String? = null,
    /**
     * Credenciales de envío de datos.null Indica que el host no puede notificar a la tarjeta a la inversa y el lado de la tarjeta vuelve al sondeo de 800 ms.
     *
     * cuando no es nulo**Identidad de instancia**Es la señal de "cambio de datos": el mismo valor reutiliza la misma instancia.
     * Cambie a una nueva instancia solo cuando cambie el valor. La señal no transporta los datos en sí. —— La tarjeta sigue funcionando después de recibir la notificación.
     * `window.muse.getData()` El valor se toma de la misma ruta que la lectura del sondeo y el comportamiento es consistente.
     */
    val pushToken: CardPushToken? = null,
)

/**
 * ver [CardHost.pushToken]. El propósito del ajuste es permitir que los "datos nulos" se utilicen como una señal válida.
 * (Nulo desnudo significa "no se admite la inserción" y "estos datos se han eliminado", lo cual es indistinguible).
 */
internal class CardPushToken(val value: String?)
