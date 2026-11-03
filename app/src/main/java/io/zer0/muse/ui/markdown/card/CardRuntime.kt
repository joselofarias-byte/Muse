package io.zer0.muse.ui.markdown.card

import io.zer0.common.Logger
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.markdown.CardHeightSink
import io.zer0.muse.ui.markdown.MuseCardBridge
import io.zer0.muse.ui.markdown.MuseCardTheme
import io.zer0.muse.ui.markdown.RichContentWebViewClient
import io.zer0.muse.ui.markdown.SanitizedHtmlCache
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer

/**
 * La única implementación de renderizado de tarjetas.
 *
 * ## Es el único lugar en este enlace que toca WebView
 *
 * Orden de montaje arreglado: tokens temáticos → contenido → guion. El script se inyecta en último lugar para garantizar que el DOM esté listo cuando se ejecute.
 *
 * ## altura
 *
 * - `CardSize.Auto`: altura = Altura del contenido, la tarjeta en sí no se desplaza (no hay nada que desplazar)
 * - `CardSize.Fixed`: Altura fija, desplazándose dentro de la tarjeta.
 * - `CardSize.Ratio`: Relación de aspecto, desplazamiento en la tarjeta
 *
 * Estos tres son mutuamente excluyentes. La implementación anterior permite establecer al mismo tiempo "altura fija + seguimiento de medición + desplazamiento en la tarjeta".
 * Como resultado, cualquiera de las partes puede cambiar la altura y nadie puede saber de dónde viene el valor final.
 */
@Composable
internal fun CardRuntime(
    spec: CardSpec,
    host: CardHost,
    modifier: Modifier = Modifier,
) {
    var contentHeightPx by remember { mutableIntStateOf(0) }

    val sink = remember { CardHeightSink() }
    LaunchedEffect(sink) {
        sink.onHeightChanged = { px -> if (px > 0) contentHeightPx = px }
    }
    DisposableEffect(sink) {
        onDispose { sink.onHeightChanged = null }
    }

    val bridge = remember(host.onAction, host.readData, host.cardId) {
        host.onAction?.let { action ->
            MuseCardBridge(
                onAction = action,
                readCardData = { id -> host.readData?.invoke(id) ?: spec.data },
                cardId = host.cardId,
            )
        }
    }

    // Los valores del tema siguen el tema Redactar y se regeneran tan pronto como se cambian los colores oscuros y claros, y no es necesario recargar la tarjeta.
    // Caché por coincidencia de color: cada informe de altura activará la reorganización, y este CSS solo está relacionado con la coincidencia de color.
    // Después del almacenamiento en caché, la misma combinación de colores devuelve la misma referencia.remember El juicio de igualdad clave degenera en igualdad de referencia.
    val colorScheme = MaterialTheme.colorScheme
    val themeCss = remember(colorScheme) { CardTheme.cssOf(colorScheme) }
    val componentCss = remember { CardStyles.full() }
    // WebViewClient No tiene estado, y basta con construirlo una vez: cada vez que se reorganiza, surgirá uno nuevo.
    // LifecycleAwareWebViewContainer Recordarlo como clave siempre no es válido.
    val webViewClient = remember { RichContentWebViewClient() }
    // La capacidad de inserción es "compatible con el host o no", lo cual solo se determina una vez cuando se ensambla esta página;token Los cambios en el valor de no deberían reconstruir el HTML.
    val pushEnabled = host.pushToken != null
    val html = remember(spec, bridge, themeCss, componentCss, pushEnabled) {
        assembleHtml(spec, host, themeCss, componentCss, pushEnabled).also { assembled ->
            // Cada vez que se ensambla la tarjeta HTML,WebView Solo recarga una vez. Esta línea es la única fuente autorizada sobre el "número de recargas de tarjetas".
            // Cuando la transmisión genera una tarjeta, sabrá cuántas veces el renderizado progresivo ha presionado la sobrecarga (P1 afirmación central).
            Logger.d("CardPerf", "assemble card=${host.cardId} len=${assembled.length}")
        }
    }
    val scrollable = spec.size !is CardSize.Auto

    // El objetivo de inserción solo conserva una referencia aquí (se borra al salir de la combinación) y el nombre del estado tiene un sufijo Ref para
    // No tiene el mismo nombre que el parámetro formal de onWebViewCreated —— Las dos líneas siguientes son los contratos de cableado existentes y se deben conservar los nombres formales de los parámetros.
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    // Envío de datos: notifica a la tarjeta que vuelva a leer tan pronto como cambien los datos vinculados, reemplazando el sondeo de 800 ms de cada tarjeta.
    // Si el token del primer cuadro está vacío, no se enviará;WebView Los cambios de preparación y token se activan una vez cada uno.
    LaunchedEffect(webViewRef, host.pushToken) {
        val view = webViewRef ?: return@LaunchedEffect
        if (host.pushToken == null) return@LaunchedEffect
        view.evaluateJavascript(CardScript.DATA_EVENT_JS, null)
    }

    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = null,
        javaScriptEnabled = true,
        webViewClient = webViewClient,
        scrollable = scrollable,
        onWebViewCreated = { webView ->
            // Registro incondicional de interfaz de alto nivel: no tiene nada que ver con "si se puede devolver".
            webView.addJavascriptInterface(sink, CardScript.HEIGHT_SINK_NAME)
            bridge?.let { webView.addJavascriptInterface(it, CardScript.BRIDGE_NAME) }
            webViewRef = webView
        },
        onWebViewReleased = { webViewRef = null },
        modifier = modifier
            .fillMaxWidth()
            .then(sizeModifier(spec.size, contentHeightPx)),
    )
}

/**
 * Proporciona el modificador de altura según la intención de tamaño.
 *
 * Auto Tome una posición baja antes de tomar su primera medida: es mejor ser más bajo primero y luego crecer,
 * Tampoco le des primero una caja grande y luego amontones el contenido en ella (este último saltará significativamente).
 */
@Composable
private fun sizeModifier(
    size: CardSize,
    measuredPx: Int,
): Modifier = when (size) {
    // La altura declarada es el límite superior, no el marco de hierro. Si el contenido es más corto, se reducirá en consecuencia. ——
    // Una tarjeta con tres cruces se extiende a 520 dp y el resto está completamente en blanco, lo que se lee como un error de carga.
    // Arréglelo solo si el contenido realmente excede el límite y déjelo rodar en la tarjeta.
    is CardSize.Fixed -> {
        // El valor informado proviene de getBoundingClientRect de WebView y la unidad es CSS px;
        // Y CSS px es igual a dp en WebView (devicePixelRatio es decir, densidad de Android).
        // Tratarlos como píxeles físicos y usar /density hará que cada tarjeta se reduzca según el multiplicador de densidad.
        val measured = measuredPx.dp
        if (measuredPx > 0 && measured < size.height) {
            Modifier.height(measured.coerceAtLeast(MIN_CARD_HEIGHT))
        } else {
            Modifier.height(size.height)
        }
    }
    is CardSize.Ratio -> Modifier.aspectRatio(size.ratio)
    CardSize.Auto -> if (measuredPx > 0) {
        Modifier.height(measuredPx.dp)
    } else {
        Modifier.height(80.dp)
    }
}

/** El límite inferior de contracción. No importa lo pequeño que sea, ni siquiera cabe una línea de título. */
private val MIN_CARD_HEIGHT = 88.dp

/**
 * Deletrea la página completa.
 *
 * Tokens de tema y CSP por [MuseCardTheme] Siempre uniforme, la tarjeta en sí no define el color. —— Cambiar entre colores claros y oscuros
 * No se requiere coordinación de tarjeta. Los scripts siempre se inyectan al final.
 */
private fun assembleHtml(
    spec: CardSpec,
    host: CardHost,
    themeCss: String,
    componentCss: String,
    pushEnabled: Boolean,
): String {
    val safe = SanitizedHtmlCache.get(spec.code)
    val idBoot = host.cardId
        ?.takeIf { it.isNotBlank() }
        ?.let { id ->
            "<script>window.muse=window.muse||{};window.muse.cardId=" +
                "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\";</script>"
        }
        .orEmpty()
    // Indicador de capacidad de inserción: el script de la tarjeta decide en función de esto si se suscribe al evento o regresa al sondeo de 800 ms.
    val pushBoot = if (pushEnabled) "<script>window.__museDataPush=true;</script>" else ""
    // Tres estilos de párrafo están organizados en orden de cobertura: valor predeterminado estático → Valor exacto del tema actual → Vocabulario de componentes.
    val styles = "<style>${MuseCardTheme.styleBlock}</style>" +
        "<style>$themeCss</style>" +
        "<style>$componentCss</style>"
    // Último script: el puente precede al gráfico, la inicialización del gráfico no depende del puente, pero la tarjeta puede llamar a ambos.
    // Las etiquetas de script son constantes:CardScript / CardCharts No tiene nada que ver con el tema o el contenido.
    // Antiguamente era necesario volver a montar cada conjunto (CardScript.build() La cadena debe escribirse nuevamente internamente),
    // Una tarjeta una vez, cada vez que cambia el contenido, todas son asignaciones repetidas.
    val scripts = SCRIPTS_TAG
    val padding = if (spec.size is CardSize.Auto) "0" else "8px"
    return """
        <html><head><meta charset="UTF-8">${MuseCardTheme.VIEWPORT_META}${MuseCardTheme.CSP_META}$styles</head>
        <body style="margin:0;padding:$padding;background:transparent;">$idBoot$pushBoot$safe$scripts</body></html>
    """.trimIndent()
}

/** ver [assembleHtml]: El bloque de script solo se escribe una vez en el nivel de proceso. */
private val SCRIPTS_TAG: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    "<script>${CardScript.build()}</script><script>${CardCharts.build()}</script>"
}
