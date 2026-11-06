package io.zer0.muse.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.markdown.card.CardHost
import io.zer0.muse.ui.markdown.card.CardLanguage
import io.zer0.muse.ui.markdown.card.CardPushToken
import io.zer0.muse.ui.markdown.card.CardRuntime
import io.zer0.muse.ui.markdown.card.CardSpec
import io.zer0.muse.ui.markdown.card.parseCardSize
import org.koin.compose.koinInject
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream

/**
 * v0.48: Tarjeta de contenido multimedia enriquecido — Renderizar SVG/HTML/gráficos.
 *
 * LLM Utilice un lenguaje de bloque de código especial para identificar factores desencadenantes en las respuestas.:
 * - ```svg ... ``` → SVG tarjeta(WebView renderizado)
 * - ```html ... ``` → HTML tarjeta(WebView renderizado)
 * - ```chart {...} ``` → tarjetas gráficas(Este problema se simplifica a la visualización JSON.,Disponible más tarde Vico)
 * - ```mermaid ... ``` → Mermaid tarjetas gráficas(v1.57: WebView + mermaid.js Diagrama de flujo de renderizado/Diagrama de tiempos, etc.;v1.97 Cambiar a la carga de activos locales)
 *
 * Instrucciones de seguridad: v1.97 A partir de Chart.js/mermaid.js/KaTeX se cargan desde activos/vendedor/locales(base URL para
 * file:///android_asset/),Ya no depende de ningún control remoto CDN,Sin riesgo de secuestro o SRI.RichContentWebViewClient
 * Intercepta toda la navegación de nivel superior.H1 Reparación: Realice una limpieza básica regular en la salida HTML/SVG de LLM,Evite amenazas como metaactualización/iframe/atributos de eventos.
 *
 * Phase 2 completo:
 *  - Cabecera agregada"Copiar código fuente"acción(Todos los idiomas disponibles),Escribir en el portapapeles + brindar comentarios;
 *  - La vista previa en pantalla completa ampliada a chart/mermaid:html/svg todavía pasa [onHtmlPreview] entregar HtmlPreviewScreen;
 *    chart/mermaid Depender del script de activos locales(En aproximadamente: URL base en blanco de HtmlPreviewScreen
 *    No se puede cargar),Utilice el cuadro de diálogo WebView de pantalla completa dentro de la tarjeta para representar la misma copia. HTML,Asegúrese de que la ruta esté disponible.
 *
 * @param onHtmlPreview HTML/SVG Devolución de llamada de vista previa de pantalla completa,El parámetro es el código fuente HTML completo.
 *         (SVG Primero se empaquetará en HTML completo y luego se volverá a llamar.). Sólo se activa en lenguaje svg/html,Se ignoran otros idiomas.
 * @param showPreviewButton Ya sea para mostrar la esquina superior derecha"Vista previa en pantalla completa"botón. Cuando la persona que llama no tiene capacidades de navegación(Como por ejemplo ArtifactViewer
 *         En la ventana emergente)Se puede configurar en false,Evite botones no válidos.
 */
// v2.6.0: Después de agregar el parámetro inlineCardData,La desviación de la firma hace que las entradas de referencia existentes no coincidan.
// Estas son estructuras inherentes de las funciones @Composable a nivel de pantalla.(función larga/múltiples parámetros/condiciones complejas/Nombre),Exención por punto.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun RichContentCard(
    language: String,
    content: String,
    modifier: Modifier = Modifier,
    onHtmlPreview: (String) -> Unit = {},
    showPreviewButton: Boolean = true,
    /**
     * v1.0.92: Devolución de llamada de puente de tarjeta(Escenas de chat proporcionadas).
     *
     * v2.6.8: null Simplemente significa "la tarjeta no tiene canal de retorno",**Ya no significa "tarjetas sin guiones".** ——
     * JS Desacopladas del puente, las tarjetas sin puente ejecutan sus propios scripts e interactúan localmente como de costumbre (consulte los comentarios de HtmlCard/SvgCard).
     */
    onCardAction: ((CardAction) -> Unit)? = null,
    /**
     * v2.6.6: Modo de renderizado progresivo——Se establece como verdadero cuando el código fuente se transmite.
     * Internamente, el contenido que se pasa al WebView secundario se limita (se actualiza solo una vez que aumenta cada número fijo de caracteres).
     * Evite recargar WebView para cada token.
     */
    progressive: Boolean = false,
    /**
     * v2.6.0: Datos de tarjeta en línea(show_card el parámetro de datos).
     *
     * Cuando no está vacío, el script de la tarjeta `window.muse.getData(cardId)` Devolver estos datos incondicionalmente
     * (Ya no depende de la búsqueda de cardId de CardDataStore —— show_card El cardId se genera solo al ejecutar,
     * No disponible en el lado del renderizado).null Mantenga el comportamiento original (verifique el almacenamiento por cardId).
     */
    inlineCardData: String? = null,
    /**
     * v2.6.7: Identidad de la tarjeta (id del mensaje en el escenario de chat).
     *
     * Inyectado en el guión de la tarjeta `window.muse.cardId`, para que la tarjeta pueda leer sus propios datos vinculantes,
     * La fuente también se puede traer al regresar para resolver el punto de interrupción de "la tarjeta termina en la tarjeta".
     * null Cuando el cardId del lado de la tarjeta es nulo (escenario de renderizado de solo lectura/sin chat).
     */
    cardId: String? = null,
    /**
     * v2.6.11: tamaño de tarjeta declarado por el modelo (show_card parámetro de altura).
     *
     * Una de las razones por las que se cortaron las cartas fue que "la altura sólo se puede medir". Cuando se genera el modelo, sabemos qué altura tiene la tarjeta.
     * Deje que las afirmaciones tengan prioridad sobre las mediciones: haga lo que dice cuando lo dice y mida cuando no lo haga.
     */
    heightSpec: String? = null,
) {
    val context = LocalContext.current
    // Limitación progresiva del renderizado —— El código fuente continúa creciendo durante la transmisión y cada vez que se envía contenido nuevo a WebView, se recarga la página completa.
    // (Volver a analizar HTML + volver a ejecutar el script de la tarjeta + reinicialización del gráfico). Aquí sólo avanzamos la instantánea en la marcha de duplicación:
    // Las recargas de página completa para una tarjeta de gráficos de 8 KB se redujeron de 68 a 7, consulte [ProgressiveCard].
    // Cuando la posición del engranaje permanece sin cambios, se devuelve el prefijo equivalente y se pueden acceder tanto al recuerdo descendente como al "contenido sin cambios y no recarga" de WebView.
    // Después de que finaliza la transmisión (progressive=false) utilizan directamente el contenido más reciente y completo.
    val effectiveContent = if (!progressive) content else ProgressiveCard.snapshot(content)
    // Phase 2: chart/mermaid Se realiza una vista previa en pantalla completa dentro de la tarjeta.(Los scripts de activos locales se pueden cargar normalmente)
    var showFullscreenPreview by remember { mutableStateOf(false) }
    // v2.x unificación: Las acciones en línea convergen en el menú "Más"(Evite que las líneas estrechas de texto de la pantalla sean comprimidas por varios botones)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    val supportsPreview = richContentSupportsPreview(language)
    val isLocalPreview = language.lowercase().trim() in RICH_LOCAL_PREVIEW_LANGUAGES
    val typeLabel = when (language.lowercase().trim()) {
        "svg" -> stringResource(R.string.markdown_svg_label)
        "html" -> stringResource(R.string.markdown_html_label)
        "chart" -> stringResource(R.string.markdown_chart_label)
        "mermaid" -> stringResource(R.string.markdown_mermaid_label)
        else -> language.uppercase()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // fila de encabezado:Etiqueta de idioma a la izquierda + acción a la derecha(Copiar código fuente/vista previa en pantalla completa)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Las etiquetas de idioma (no texto) están limitadas a 1 línea —— Los dos botones con íconos de 48 dp a la derecha tienen un tamaño de fuente ampliado.
                    // La columna de la etiqueta se comprimirá en una fila vertical (formulario de accidente en línea); el texto del usuario se representará por separado a continuación y no se verá afectado.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Phase 2: Copiar código fuente — Todos los idiomas disponibles,ya no se limita a HTML/SVG
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = { copyRichContentSource(context, content) },
                    contentDescription = stringResource(R.string.html_preview_copy_source_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
                if (onCardAction != null || (supportsPreview && showPreviewButton)) {
                    MuseTactileButton(
                        icon = MuseIcons.moreVertical,
                        onClick = { menuExpanded = true },
                        contentDescription = moreCd,
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            when (language.lowercase().trim()) {
                "svg" -> SvgCard(effectiveContent, onCardAction, inlineCardData, cardId, heightSpec)
                "html" -> HtmlCard(effectiveContent, onCardAction, inlineCardData, cardId, heightSpec)
                "chart" -> ChartCard(effectiveContent)
                "mermaid" -> MermaidBlock(effectiveContent)
                else -> Text(effectiveContent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onCardAction != null) {
                        // v2.6.13: Favoritos en la cima —— la tarjeta es“algo que vale la pena conservar”,
                        // Guardar como archivo es el método de exportación y ocupa el segundo lugar.
                        MuseActionSheetRow(
                            icon = MuseIcons.star,
                            text = stringResource(R.string.card_favorite),
                            onClick = {
                                menuExpanded = false
                                onCardAction.invoke(CardAction.Favorite(language, content, typeLabel, cardId))
                            },
                        )
                        MuseActionSheetRow(
                            icon = MuseIcons.bookmark,
                            text = stringResource(R.string.card_save_cd),
                            onClick = {
                                menuExpanded = false
                                onCardAction.invoke(CardAction.Save(language, content))
                            },
                        )
                    }
                    if (supportsPreview && showPreviewButton) {
                        MuseActionSheetRow(
                            icon = MuseIcons.eye,
                            text = stringResource(R.string.html_preview_button_cd),
                            onClick = {
                                menuExpanded = false
                                if (isLocalPreview) {
                                    // chart/mermaid:Pantalla completa en tarjeta WebView,Cargar script de activos locales
                                    showFullscreenPreview = true
                                } else {
                                    // SVG Envuélvalo en HTML completo y luego vuelva a llamar,Asegúrese de que HtmlPreviewScreen se represente directamente
                                    val fullHtml = if (language.lowercase().trim() == "svg") {
                                        "<html><body style=\"margin:0;padding:8px;\">$content</body></html>"
                                    } else {
                                        content
                                    }
                                    onHtmlPreview(fullHtml)
                                }
                            },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }

    // Phase 2: chart/mermaid Cuadro de diálogo de vista previa en pantalla completa(Igual que la representación de tarjetas HTML)
    if (showFullscreenPreview && isLocalPreview) {
        // P3-2: Prefijo de error inyectado desde el recurso(JS No más chino codificado en)
        val renderFailedPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)
        val previewHtml = remember(content, language) { buildLocalPreviewHtml(language, content, renderFailedPrefix) }
        RichContentFullscreenPreview(
            title = typeLabel,
            html = previewHtml,
            onDismiss = { showFullscreenPreview = false },
        )
    }
}

/**
 * Lenguaje de contenido enriquecido con soporte de vista previa en pantalla completa(html/svg Tomar vista previa de navegación externa;chart/mermaid Ingrese al cuadro de diálogo de pantalla completa dentro de la tarjeta).
 * Phase 2: Del original"solo html/svg"Extendido a gráfico/sirena.
 */
internal fun richContentSupportsPreview(language: String): Boolean = language.lowercase().trim() in RICH_PREVIEW_LANGUAGES

/** Vista previa en pantalla completa de la colección de idiomas. */
private val RICH_PREVIEW_LANGUAGES = setOf("html", "svg", "chart", "mermaid")

/**
 * Un lenguaje que se basa en la representación de secuencias de comandos de activos locales y requiere una vista previa de WebView en pantalla completa en la tarjeta.
 * HtmlPreviewScreen Dejar sobre: estar en blanco baseUrl,No se puede cargar el script del proveedor relativo.
 */
internal val RICH_LOCAL_PREVIEW_LANGUAGES = setOf("chart", "mermaid")

/** Generar vista previa a pantalla completa en la tarjeta HTML(Utilice el mismo constructor que el renderizado de tarjetas.). */
private fun buildLocalPreviewHtml(language: String, content: String, errorPrefix: String): String = when (language.lowercase().trim()) {
    "chart" -> buildChartHtml(content, errorPrefix)
    "mermaid" -> buildMermaidHtml(content, errorPrefix)
    else -> content
}

/** Copie el código fuente de contenido enriquecido al portapapeles,Y comentarios brindados. */
private fun copyRichContentSource(context: Context, source: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("muse_rich_content", source))
    MuseToast.show(context.getString(R.string.markdown_source_copied))
}

/**
 * Phase 2: Cuadro de diálogo de vista previa en pantalla completa dentro de la tarjeta — Título superior + botón de cerrar,El cuerpo principal es un WebView que llena la pantalla.
 *
 * Usa lo mismo que la tarjeta. [LifecycleAwareWebViewContainer] + `file:///android_asset/` baseUrl,
 * Por lo tanto chart(Chart.js)/mermaid(mermaid.js)El script del proveedor local se puede cargar normalmente.
 */
@Composable
private fun RichContentFullscreenPreview(title: String, html: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Cubre toda la pantalla(Con barra de estado): De lo contrario, el color de fondo de la vista previa en pantalla completa/El enmascaramiento comienza debajo de la barra de estado,
            // Habrá una banda brillante descubierta en la parte superior.
            decorFitsSystemWindows = false,
        ),
    ) {
        // v1.0.92: window Cubre toda la pantalla(Con barra de estado),con BrowserCapsule / CommandPalette /
        // FullScreenMediaViewer alineación de procesamiento existente;De lo contrario, el color de fondo de la vista previa comienza desde el borde inferior de la barra de estado.
        MuseDialogWindowEffect(forceFullScreen = true)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = MusePaddings.screen, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: El título del cuadro de diálogo de vista previa en pantalla completa (no el texto) está limitado a 1 línea —— El botón de cerrar a la derecha aprieta la barra de título.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                }
                LifecycleAwareWebViewContainer(
                    htmlContent = html,
                    baseUrl = "file:///android_asset/",
                    javaScriptEnabled = true,
                    webViewClient = RichContentWebViewClient(),
                    // v2.6.7: La vista previa en pantalla completa permite el desplazamiento interno —— Las tarjetas pueden extenderse mucho más allá de una pantalla.
                    scrollable = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * v1.131: HTML/SVG Reglas de limpieza — Constantes de nivel de archivo,evitar cada vez [sanitizeHtml] Cada llamada crea 9 nuevas expresiones regulares.
 * La implementación original se reconstruía cada vez que LLM generaba contenido enriquecido.,La presión del GC se nota bajo una entrada SVG/HTML larga.
 */
private val IFRAME_BLOCK_REGEX = Regex("""(?is)<iframe\b[^>]*>.*?</iframe>""")
private val FORM_BLOCK_REGEX = Regex("""(?is)<form\b[^>]*>.*?</form>""")
private val OBJECT_BLOCK_REGEX = Regex("""(?is)<object\b[^>]*>.*?</object>""")
private val EMBED_BLOCK_REGEX = Regex("""(?is)<embed\b[^>]*>.*?</embed>""")
private val IFRAME_SELF_CLOSED_REGEX = Regex("""(?is)<iframe\b[^>]*/?>""")
private val EMBED_SELF_CLOSED_REGEX = Regex("""(?is)<embed\b[^>]*/?>""")
private val META_HTTP_EQUIV_REGEX = Regex("""(?is)<meta\b[^>]*http-equiv[^>]*>""")

// v2.6.7: EVENT_ATTR_REGEX Ya por [rewriteInlineHandlers] reemplazar —— Los atributos del evento se cambian a
// Reescrito en addEventListener y ya no se elimina directamente.
private val JS_PROTO_REGEX = Regex("""(?i)(href|src)\s*=\s*("javascript:[^"]*"|'javascript:[^']*')""")

/**
 * H1 Reparación: Limpieza básica de salida HTML/SVG por LLM,Elimine etiquetas y atributos de alto riesgo.
 *
 * v2.6.7 Reparación interactiva: `onXxx` Los atributos de eventos ya no se eliminan directamente —— Eliminarlo equivale a eliminar la interacción de la tarjeta.
 * Se pierden todas las capacidades (el clic escrito en el modelo es el único portador de interacción de clic), y esto es exactamente
 * “¿Se pueden ver o hacer clic en las tarjetas?”causa directa.
 *
 * Cambiar a: primero `onXxx="handler"` Recójalos, elimínelos de las propiedades y vuelva a escribirlos en equivalentes.
 * `<script>` enlace de evento (`el.addEventListener`). Porque CSP ha permitido
 * `script-src 'unsafe-inline'`(Representación local, sin red externa), el comportamiento permanece sin cambios después de reescribir,
 * Pero ya no depende de la ejecución de atributos en línea y solo hay una ruta de código.
 *
 * Limitaciones conocidas(Limitaciones inherentes a la limpieza regular):
 *  - No se pueden cubrir todos los vectores XSS(Como etiquetas con formato incorrecto,Unicode Omitir, comillas anidadas dentro de valores de atributos, etc.);
 *  - pero puede bloquear amenazas comunes:<meta http-equiv="refresh">,<iframe>,<form>,<object>/<embed>,
 *    javascript: Pseudo acuerdo.
 *  - La tarjeta en sí se ejecuta en una red que prohíbe todas las redes externas (connect-src 'none')+ En una zona de pruebas con interceptación de navegación completa,
 *    Por lo tanto, la superficie de riesgo de permitir scripts interactivos en línea se limita a“propia de la tarjeta DOM”Adentro.
 */
internal fun sanitizeHtml(input: String): String {
    var s = input
    // Quitar iframe / form / object / embed(Contiene contenido,a través de líneas)
    s = IFRAME_BLOCK_REGEX.replace(s, "")
    s = FORM_BLOCK_REGEX.replace(s, "")
    s = OBJECT_BLOCK_REGEX.replace(s, "")
    s = EMBED_BLOCK_REGEX.replace(s, "")
    // Quitar el cierre automático iframe/embed(Sin etiqueta de cierre)
    s = IFRAME_SELF_CLOSED_REGEX.replace(s, "")
    s = EMBED_SELF_CLOSED_REGEX.replace(s, "")
    // Quitar meta http-equiv(refresh saltar)
    s = META_HTTP_EQUIV_REGEX.replace(s, "")
    // v2.6.7: Las propiedades del evento se reescriben como enlace addEventListener (conservando la interacción)
    s = rewriteInlineHandlers(s)
    // Eliminar javascript: pseudoprotocolo(href/src="javascript:...")
    s = JS_PROTO_REGEX.replace(s, """$1="#" """)
    return s
}

/**
 * [sanitizeHtml] LRU a nivel de proceso.
 *
 * La limpieza consta de 7 párrafos de expresiones regulares + una reescritura de etiqueta completa. Es una función pura y solo está relacionada con la entrada. Y este camino será
 * Repetidamente: el mensaje sale de la pantalla y luego retrocede (LazyColumn Destruye y reconstruye la combinación), cambio de color claro y oscuro.
 * (themeCss cambiar → Reensamblar HTML) será correcto**El mismo código fuente de la tarjeta.**Lavar de nuevo.
 *
 * La clave utiliza el contenido original en lugar del hash: el resultado de la limpieza regular no tiene en cuenta el riesgo de colisión y el límite superior de entradas es muy pequeño
 * ([MAX_ENTRIES]), mantener el texto original en la memoria es un coste controlable. Contenido extra largo ([MAX_CONTENT_LENGTH]
 * Arriba) no ingrese al caché para evitar que tarjetas grandes llenen el caché.
 */
internal object SanitizedHtmlCache {

    private const val MAX_ENTRIES = 12
    private const val MAX_CONTENT_LENGTH = 64_000

    private val entries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_ENTRIES
    }

    /** Obtenga el resultado de la limpieza; el mismo contenido llega por segunda vez y llega directamente al objeto original. */
    fun get(code: String): String {
        if (code.length > MAX_CONTENT_LENGTH) return sanitizeHtml(code)
        return synchronized(entries) {
            entries.getOrPut(code) { sanitizeHtml(code) }
        }
    }

    /** Solo para pruebas: número actual de entradas. */
    internal fun size(): Int = synchronized(entries) { entries.size }

    /** Sólo para pruebas: claro. */
    internal fun clear() = synchronized(entries) { entries.clear() }
}

/** partido `onXxx="code"` / `onXxx='code'` / `onXxx=code`. */
private val INLINE_HANDLER_REGEX =
    Regex("""\s+(on[a-z]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/**
 * v2.6.7: Vuelva a escribir el atributo de evento en línea como `<script>` addEventListener en.
 *
 * Método: marcar cada elemento con un evento `data-muse-h="mN"` marca,
 * El nombre y el código del evento se reciben en la tabla y, finalmente, se crea un script para vincularlos.
 * No solo conserva la interacción escrita por el modelo, sino que tampoco depende de la ejecución de atributos en línea.
 */
private fun rewriteInlineHandlers(html: String): String {
    val handlers = mutableListOf<Triple<String, String, String>>() // (marca, nombre del evento, código)
    var seq = 0

    val out = TAG_REGEX.replace(html) { tagMatch ->
        val tag = tagMatch.value
        // Eventos dentro de la etiqueta actual: recójalos primero y luego decida si desea marcarlos.
        val local = mutableListOf<Triple<String, String, String>>()
        val stripped = INLINE_HANDLER_REGEX.replace(tag) { m ->
            val event = m.groupValues[1].lowercase()
            val code = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
            if (code.isBlank()) {
                ""
            } else {
                val marker = "m${seq++}"
                local += Triple(marker, event, code)
                ""
            }
        }
        if (local.isEmpty()) return@replace tag

        handlers += local
        val markerAttr = " data-muse-h=\"${local.joinToString(" ") { it.first }}\""
        stripped.replaceFirst(Regex("""^(<[a-zA-Z][\w-]*)"""), "$1" + markerAttr)
    }

    if (handlers.isEmpty()) return out

    val bindings = handlers.joinToString("\n") { (marker, event, code) ->
        "document.querySelectorAll('[data-muse-h~=\"$marker\"]').forEach(function(el){" +
            "el.addEventListener('$event', function(ev){try{$code}catch(e){console.warn(e);}});});"
    }
    return out + "\n<script>\n" + bindings + "\n</script>"
}

/** Coincide aproximadamente con una etiqueta completa (que no abarca > ), utilizado para reescribir atributos dentro de etiquetas. */
private val TAG_REGEX = Regex("""<[a-zA-Z][^>]*>""")

/**
 * Intercepción de navegación unificada WebView de contenido enriquecido — Rechazarlos a todos.
 *
 * v1.0.10: Se corrigió la vulnerabilidad de la lógica de la lista blanca. Versión antigua lanzada cdn.jsdelivr.net,pero Chart.js/mermaid.js/KaTeX
 * Cambiado para cargar desde activos locales/proveedor/(ver buildHtml),Ya no es necesario incluir ningún nombre de dominio remoto en la lista blanca.
 * La antigua lista blanca se ha convertido en un agujero de seguridad:LLM Si el resultado apunta a una página maliciosa de jsdelivr, se permitirá.
 *
 * estrategia actual:La navegación de nivel superior siempre está bloqueada(return true),Evitar la salida de LLM <a>/meta refresh
 * Saltar a cualquier página externa o pseudoprotocolo. recursos locales(file:///android_asset/)Cargar como subrecurso,
 * No afectado por deberíaOverrideUrlLoading.
 */
internal class RichContentWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return true
    }

    /**
     * v2.6.6 Refuerzo de seguridad: Intercepte todas las solicitudes de la red externa.
     *
     * Todas las dependencias de la tarjeta.(Chart.js / mermaid.js / KaTeX)Todo de local `file:///android_asset/`
     * Cargar,Los subrecursos no se ven afectados por shouldOverrideUrlLoading,Una cosa más aquí:
     * no local(asset/file/data/blob)Siempre bloqueado. Evite que las tarjetas generadas por modelos soliciten servicios externos en secreto(Seguimiento/volver).
     */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString()
        val allowed =
            url == null ||
                url.startsWith("file://") ||
                url.startsWith("data:") ||
                url.startsWith("blob:") ||
                url.startsWith("about:") ||
                url.startsWith("javascript:")
        return if (allowed) {
            null // null = Tomar procesamiento predeterminado
        } else {
            // Bloquear solicitudes externas:Devolver respuesta vacía
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
}

/**
 * Suscríbase a los datos vinculantes propios de esta tarjeta y genere credenciales push.
 *
 * Solo mira fijamente `cardId` Esta clave: Hoja completa cardId→data Si la asignación cambia pero la tarjeta permanece sin cambios, no se generarán nuevas credenciales.
 * Esto significa que no se enviarán notificaciones sin sentido a la tarjeta. Cuando falta el almacenamiento (no hay host de CardDataStore) o la tarjeta no tiene identidad
 * Devuelve nulo y el lado de la tarjeta vuelve al sondeo de 800 ms en consecuencia.
 */
@Composable
private fun rememberCardPushToken(
    store: io.zer0.muse.data.card.CardDataStore?,
    cardId: String?,
): CardPushToken? {
    if (store == null || cardId.isNullOrBlank()) return null
    val value by remember(store, cardId) {
        store.data.map { it[cardId] }.distinctUntilChanged()
    }.collectAsState(initial = store.get(cardId))
    return remember(value) { CardPushToken(value) }
}

@Suppress("FunctionNaming")
@Composable
private fun SvgCard(
    svg: String,
    onCardAction: ((CardAction) -> Unit)? = null,
    inlineCardData: String? = null,
    cardId: String? = null,
    heightSpec: String? = null,
) {
    val cardDataStore: io.zer0.muse.data.card.CardDataStore? = koinInject()
    val store = cardDataStore
    // La devolución de llamada de lectura de datos solo se basa en el almacenamiento y los datos en línea. Según estos dos cachés: cada cuadro creará un nuevo puente de tarjeta.
    // (recuerde usarlo como clave) se reconstruye y luego se vuelve a ensamblar todo el HTML de la tarjeta.
    val readData = remember(store, inlineCardData) { { id: String -> store?.get(id) ?: inlineCardData } }
    CardRuntime(
        spec = CardSpec(
            language = CardLanguage.SVG,
            code = svg,
            size = parseCardSize(heightSpec),
            data = inlineCardData,
        ),
        host = CardHost(
            onAction = onCardAction,
            readData = readData,
            cardId = cardId,
            pushToken = rememberCardPushToken(store, cardId),
        ),
    )
}

@Suppress("FunctionNaming")
@Composable
private fun HtmlCard(
    html: String,
    onCardAction: ((CardAction) -> Unit)? = null,
    inlineCardData: String? = null,
    cardId: String? = null,
    heightSpec: String? = null,
) {
    val cardDataStore: io.zer0.muse.data.card.CardDataStore? = koinInject()
    val store = cardDataStore
    // Igual que SvgCard: la devolución de llamada de datos leídos debe ser estable durante la reorganización; de lo contrario, el puente de la tarjeta se reconstruirá en consecuencia.
    val readData = remember(store, inlineCardData) { { id: String -> store?.get(id) ?: inlineCardData } }
    CardRuntime(
        spec = CardSpec(
            language = CardLanguage.HTML,
            code = html,
            size = parseCardSize(heightSpec),
            data = inlineCardData,
        ),
        host = CardHost(
            onAction = onCardAction,
            readData = readData,
            cardId = cardId,
            pushToken = rememberCardPushToken(store, cardId),
        ),
    )
}

@Composable
private fun ChartCard(json: String) {
    val chartCd = stringResource(R.string.markdown_chart_cd, json)
    val errorPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)
    val html = remember(json) { buildChartHtml(json, errorPrefix) }
    // v2.6.13: La altura del gráfico también va“Congelar el contenido una vez”.
    //
    // La implementación anterior tenía solo una `heightIn(min = 260.dp)`: No hay límite superior ni adaptación.
    // No importa qué tan alto deba ser el gráfico, solo comienza en 260 dp y el contenido adicional se recorta en WebView.
    // Ahora es consistente con otras tarjetas: el script de fotograma congelado en el costado de la tarjeta informa la altura real, que simplemente se acepta aquí.
    var chartHeightPx by remember(html) { mutableStateOf(0) }
    val heightSink: CardHeightSink = remember { CardHeightSink() }
    androidx.compose.runtime.LaunchedEffect(heightSink) {
        heightSink.onHeightChanged = { px -> if (px > 0) chartHeightPx = px }
    }
    androidx.compose.runtime.DisposableEffect(heightSink) {
        onDispose { heightSink.onHeightChanged = null }
    }
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = "file:///android_asset/",
        javaScriptEnabled = true,
        webViewClient = RichContentWebViewClient(),
        onWebViewCreated = { webView ->
            heightSink.attachTo(webView)
        },
        // Siga estrictamente el contenido y los gráficos se podrán ver de un vistazo sin tener que desplazarse por la tarjeta.
        scrollable = false,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (chartHeightPx > 0) {
                    Modifier.height(chartHeightPx.dp)
                } else {
                    Modifier.height(260.dp)
                },
            )
            .semantics { contentDescription = chartCd },
    )
}

/**
 * Crear página de renderizado Chart.js(Las tarjetas se comparten con vistas previas en pantalla completa.).
 *
 * v1.97: Chart.js Carga desde activos/proveedor local,Resuelva el problema de que CDN no esté disponible en China.
 * LLM El JSON de salida se pasa directamente como configuración de Chart.js.(new Chart(ctx, config)).
 */
private fun buildChartHtml(json: String, errorPrefix: String): String {
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), json)
    // P3-2: JS Prefijo de error inyectado desde el recurso(JSON Codificado como literal JS),Eliminar el chino codificado
    val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), errorPrefix)
    return """
        <html><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body { margin: 0; padding: 0; background: transparent; }
            body { color: #1a1a1c; font-family: -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif; font-size: 14px; }
            #wrap { position: relative; width: 100%; height: 240px; }
            #err { color: #d32f2f; font-family: monospace; font-size: 12px; white-space: pre-wrap; padding: 8px; }
        </style>
        <script src="vendor/chart.umd.min.js"></script>
        </head><body>
        <div style="padding:8px">
          <div id="wrap"><canvas id="cv"></canvas></div>
          <div id="err"></div>
        </div>
        <script>
            try {
                var raw = $encoded;
                var config = JSON.parse(raw);
                if (!config.type) { config.type = 'bar'; }
                var ctx = document.getElementById('cv');
                new Chart(ctx, config);
            } catch(e) {
                document.getElementById('wrap').style.display = 'none';
                document.getElementById('err').textContent = $encodedPrefix + e.message + '\n\ndatos sin procesar:\n' + raw;
            }
        </script>
        <script>$HEIGHT_OBSERVER_SCRIPT</script>
        </body></html>
    """.trimIndent()
}

/**
 * Construyendo una página renderizada de sirena(Para obtener una vista previa en pantalla completa).
 * P3-2: La representación de tarjetas ha convergido a [io.zer0.muse.ui.markdown.ChartRenderer.MermaidBlock]
 * (Viene con sondeo para obtener un prefijo de error alto y basado en recursos),Aquí solo se guardan páginas WebView estáticas con vistas previas en pantalla completa.
 *
 * v1.97: mermaid.js Carga desde activos/proveedor local;mermaid El código fuente se inyecta mediante codificación JSON. JS,Evite XSS.
 */
private fun buildMermaidHtml(mermaid: String, errorPrefix: String): String {
    // JSON Codificación del código fuente de sirena,Inyecte cadenas JS de forma segura
    val encoded = io.zer0.common.AppJson.encodeToString(String.serializer(), mermaid)
    // P3-2: Prefijo de error inyectado desde el recurso,Eliminar el chino codificado('Error de renderizado: ')
    val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), errorPrefix)
    return """
        <html><head><meta charset="UTF-8">
        <style>
            body { margin: 0; padding: 8px; background: transparent; display: flex; justify-content: center; }
            #err { color: #d32f2f; font-family: monospace; font-size: 12px; white-space: pre-wrap; }
        </style>
        <script src="vendor/mermaid.min.js"></script>
        </head><body>
        <div id="out"></div>
        <div id="err"></div>
        <script>
            try {
                // M1 Reparación: securityLevel Cambiar a strict,Deshabilitar las devoluciones de llamada de clic y la incrustación de HTML,Reducir el riesgo del contenido de LLM
                mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' });
                var src = $encoded;
                mermaid.render('mmd', src).then(function(res) {
                    document.getElementById('out').innerHTML = res.svg;
                }).catch(function(e) {
                    document.getElementById('err').textContent = $encodedPrefix + e.message;
                });
            } catch(e) {
                document.getElementById('err').textContent = $encodedPrefix + e.message;
            }
        </script>
        </body></html>
    """.trimIndent()
}
