package io.zer0.muse.ui.markdown

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.resume

/**
 * Mermaid palabra clave de bloque de código — usado para [isMermaidCodeBlock] Identificación heurística.
 *
 * Cuando el bloque de código no es explícitamente ```mermaid marca de idioma,Si el código comienza con estas palabras clave,También juzgada como Sirena.
 */
private val MERMAID_KEYWORDS = arrayOf(
    "graph", "flowchart", "sequenceDiagram", "classDiagram",
    "stateDiagram", "erDiagram", "gantt", "pie", "journey",
)

/**
 * PlantUML Etiqueta de inicio del código fuente — usado para [isPlantUmlCodeBlock] Identificación heurística.
 */
private const val PLANTUML_START = "@startuml"

/**
 * Mermaid/PlantUML El número máximo de reintentos cuando falla la renderización.
 * Volver a la visualización de texto sin formato del bloque de código después de exceder,Evite infinitos reintentos.
 */
private const val MAX_RENDER_RETRY = 2

/**
 * Mermaid WebView intervalo de sondeo de altura(milisegundos).
 * mermaid.js renderizar asíncrono,Es necesario sondear el DOM para obtener la altura y el estado reales.
 */
private const val MERMAID_POLL_INTERVAL_MS = 150L

/**
 * Mermaid WebView Número máximo de encuestas de altura.(Si supera el límite, se considerará un fracaso.).
 * El tiempo total de espera es de aprox. = [MERMAID_POLL_INTERVAL_MS] * [MERMAID_POLL_MAX_ATTEMPTS].
 */
private const val MERMAID_POLL_MAX_ATTEMPTS = 40

/**
 * Determina si el bloque de código es un gráfico de sirena.
 *
 * Reglas de decisión(Simplemente satisface a cualquiera):
 *  - La lengua se identifica como "mermaid"(No distingue entre mayúsculas y minúsculas,Ir al espacio en blanco);
 *  - El contenido no vacío en la primera línea de código comienza con [MERMAID_KEYWORDS] Comience con cualquiera de las palabras clave.
 *
 * @param lang El idioma de la declaración del bloque de código.(Puede ser nulo/en blanco)
 * @param code contenido del bloque de código
 * @return true indica que debe ser entregado a [MermaidBlock] renderizado
 */
fun isMermaidCodeBlock(lang: String?, code: String): Boolean {
    if (lang != null && lang.trim().equals("mermaid", ignoreCase = true)) return true
    val firstLine = code.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    val trimmed = firstLine.trim()
    return MERMAID_KEYWORDS.any { kw -> trimmed.startsWith(kw, ignoreCase = true) }
}

/**
 * Determine si el bloque de código es un diagrama PlantUML.
 *
 * Reglas de decisión(Simplemente satisface a cualquiera):
 *  - La lengua se identifica como "plantuml"(No distingue entre mayúsculas y minúsculas,Ir al espacio en blanco);
 *  - el código contiene [@startuml] marca(PlantUML Código fuente esencial).
 *
 * @param lang El idioma de la declaración del bloque de código.(Puede ser nulo/en blanco)
 * @param code contenido del bloque de código
 * @return true indica que debe ser entregado a [PlantUmlBlock] renderizado
 */
fun isPlantUmlCodeBlock(lang: String?, code: String): Boolean {
    if (lang != null && lang.trim().equals("plantuml", ignoreCase = true)) return true
    return code.contains(PLANTUML_START)
}

/**
 * Representar un gráfico de sirena — Cargar con WebView mermaid.js(locales assets,Sin dependencia de la red).
 *
 * Puntos de implementación:
 *  - habilitar JavaScript,Deshabilitar archivos/Acceso al contenido(Profundidad de seguridad);
 *  - WebView fondo transparente,Ajuste profundo/burbujas de luz;
 *  - mermaid Contenido inyectado usando codificación JSON,evitar XSS;
 *  - Una vez finalizada la renderización, utilice evaluaJavascript para sondear y obtener la altura real.,Altura de redacción adaptable;
 *  - Mostrando cargando [CircularProgressIndicator];Mostrar mensaje de error en caso de falla + "Mostrar código fuente"botón para regresar.
 *
 * @param code Mermaid código fuente(Como por ejemplo `graph TD; A-->B`)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidBlock(code: String) {
    // Estado de renderizado:0=Cargando 1=éxito 2=falló
    var renderState by remember(code) { mutableIntStateOf(0) }
    // WebView altura real(px)
    var contentHeightPx by remember(code) { mutableStateOf(0) }
    // Si se debe recurrir a la visualización del código fuente
    var showSource by rememberSaveable(code) { mutableStateOf(false) }
    // Recuento de reintentos(Evite infinitos reintentos después del fallo)
    var retryCount by remember(code) { mutableIntStateOf(0) }
    // Activar renderizado key(retryCount Deje que AndroidView se vuelva a ejecutar cuando cambie update)
    var reloadKey by remember(code) { mutableIntStateOf(0) }

    val mermaidCd = stringResource(R.string.markdown_mermaid_cd, code.take(80))
    // CONS-02: JS El prefijo de copia de error se lee desde el recurso de cadena,JSON Codificar en cadena JS literal e incrustar en plantilla
    val renderFailedPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)

    // mermaid Codificación JSON del código fuente,Inyecte cadenas JS de forma segura
    val html = remember(code, renderFailedPrefix) {
        val encodedSrc = io.zer0.common.AppJson.encodeToString(String.serializer(), code)
        val encodedPrefix = io.zer0.common.AppJson.encodeToString(String.serializer(), renderFailedPrefix)
        """
        <html><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body { margin: 0; padding: 8px; background: transparent; }
            body { display: flex; justify-content: center; }
            #out { display: inline-block; }
            #err { color: #d32f2f; font-family: monospace; font-size: 12px;
                   white-space: pre-wrap; padding: 8px; max-width: 100%; }
        </style>
        <script src="vendor/mermaid.min.js"></script>
        </head><body>
        <div id="out"></div>
        <div id="err"></div>
        <script>
            try {
                mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict' });
                var src = $encodedSrc;
                mermaid.render('mmd', src).then(function(res) {
                    document.getElementById('out').innerHTML = res.svg;
                }).catch(function(e) {
                    document.getElementById('err').textContent =
                        $encodedPrefix + (e && e.message ? e.message : e);
                });
            } catch(e) {
                document.getElementById('err').textContent =
                    $encodedPrefix + (e && e.message ? e.message : e);
            }
        </script>
        </body></html>
        """.trimIndent()
    }

    // Contiene una referencia de WebView,Se utiliza para sondear el estado de DOM.
    var webViewRef by remember(code) { mutableStateOf<WebView?>(null) }

    // Sondeo de resultados de renderizado de WebView:altura del éxito,Error de marca de falla
    LaunchedEffect(reloadKey) {
        if (renderState != 0) return@LaunchedEffect
        var attempts = 0
        while (attempts < MERMAID_POLL_MAX_ATTEMPTS) {
            delay(MERMAID_POLL_INTERVAL_MS)
            val wv = webViewRef ?: run {
                attempts++
                continue
            }
            // Envolver con suspendCancellableCoroutine evaluateJavascript,Evite bloquear el hilo principal.
            val pollResult = suspendCancellableCoroutine { cont ->
                wv.post {
                    if (cont.isCompleted) return@post
                    wv.evaluateJavascript(
                        """
                        (function() {
                            var errEl = document.getElementById('err');
                            var outEl = document.getElementById('out');
                            if (errEl && errEl.textContent) return 'err';
                            if (outEl && outEl.innerHTML.trim()) {
                                return 'ok:' + document.body.scrollHeight;
                            }
                            return 'pending';
                        })();
                        """.trimIndent(),
                    ) { result ->
                        if (cont.isActive) cont.resume(result ?: "pending")
                    }
                }
            }
            when {
                pollResult == "'err'" || pollResult == "\"err\"" -> {
                    renderState = 2
                    return@LaunchedEffect
                }
                pollResult.startsWith("'ok:") -> {
                    // Altura de resolución:'ok:240' → 240
                    val heightStr = pollResult.removePrefix("'ok:").removeSuffix("'")
                    heightStr.toIntOrNull()?.let { contentHeightPx = it }
                    renderState = 1
                    return@LaunchedEffect
                }
                pollResult.startsWith("\"ok:") -> {
                    val heightStr = pollResult.removePrefix("\"ok:").removeSuffix("\"")
                    heightStr.toIntOrNull()?.let { contentHeightPx = it }
                    renderState = 1
                    return@LaunchedEffect
                }
            }
            attempts++
        }
        // Se agotó el tiempo y no se obtuvieron resultados.,Decisión fallida
        renderState = 2
    }

    // tarjeta exterior:Consistente con el estilo RichContentCard(surfaceVariant fondo translúcido + esquinas redondeadas medianas)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
            // etiqueta de encabezado
            ChartHeader(label = stringResource(R.string.markdown_mermaid_label))
            when {
                // Alternativa para mostrar el código fuente:Mostrar código original + botón de reintento
                showSource -> {
                    ChartSourceView(code = code, onRetry = {
                        showSource = false
                        renderState = 0
                        retryCount = 0
                        reloadKey++
                    })
                }
                renderState == 2 -> {
                    // Error de renderizado:Mensaje de error + botón mostrar fuente(Agregue un botón de reintento cuando la cantidad de fallas no alcance el límite superior)
                    ChartErrorView(
                        message = stringResource(R.string.markdown_chart_render_failed),
                        onShowSource = { showSource = true },
                        onRetry = if (retryCount < MAX_RENDER_RETRY) {
                            {
                                renderState = 0
                                retryCount++
                                reloadKey++
                            }
                        } else {
                            null
                        },
                    )
                }
                else -> {
                    // Cargando / Exitoso:WebView renderizado
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                            .then(
                                if (contentHeightPx > 0) {
                                    // El valor informado es el CSS px de WebView, que tiene la misma magnitud que dp y no se puede dividir por densidad.
                                    Modifier.height(contentHeightPx.dp)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // v1.88 Reparación: Usar en su lugar LifecycleAwareWebViewContainer,Procesamiento automático ON_PAUSE/ON_RESUME/ON_DESTROY,
                        // Resuelva el problema de que el temporizador JS relacionado con el renderizado de mermaid.js continúa ejecutándose cuando la actividad está en segundo plano y consume energía.
                        // Mantenga webViewRef para realizar encuestas a través de onWebViewCreated/onWebViewReleased.
                        LifecycleAwareWebViewContainer(
                            htmlContent = html,
                            baseUrl = "file:///android_asset/",
                            javaScriptEnabled = true,
                            onWebViewCreated = { webViewRef = it },
                            onWebViewReleased = { webViewRef = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = mermaidCd },
                        )
                        // Cargando capa flotante:WebView Mostrar el progreso antes de que se complete el renderizado
                        if (renderState == 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = MuseShapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(MusePaddings.contentGap),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    MuseSpinner(
                                        size = 16.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.markdown_chart_loading),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Representación de diagramas PlantUML — Utilice Coil para cargar de forma asincrónica el SVG devuelto por el servidor plantuml.com.
 *
 * Puntos de implementación:
 *  - URL usar `~1` modo simple prefijo:`https://www.plantuml.com/plantuml/svg/~1{URL_ENCODED_TEXT}`,
 *    PlantUML El servidor representará directamente el texto codificado en URL.,No se requiere codificación desinflada del lado del cliente;
 *  - Phase 2 Solución de privacidad: PlantUML Los costos de renderizado local son demasiado altos(Necesita ser empacado plantuml.jar/Graphviz),
 *    La representación del lado del servidor enviará el código fuente del gráfico a un tercero. plantuml.com,Por lo tanto, no se inicia ninguna solicitud por defecto. —
 *    Mostrar redes claras primero/Consejos de terceros,Clics del usuario"Todavía es necesario renderizarlo en línea."Cargar solo más tarde,No más salir en silencio;
 *  - usar Coil [AsyncImage] Carga asincrónica,Mostrando cargando [CircularProgressIndicator],
 *    Mostrar mensaje de error en caso de falla + "Mostrar código fuente"botón para regresar.
 *
 * Limitaciones conocidas: `~1` Par de patrones para código fuente súper largo(>4KB URL límite de longitud)No compatible,Necesidad de utilizar codificación desinflada;
 *           99% El gráfico de resultados de LLM está dentro de los límites.,Procesamiento simplificado aquí.
 *
 * @param code PlantUML código fuente(Se puede utilizar con o sin etiquetas @startuml/@enduml)
 */
@Composable
fun PlantUmlBlock(code: String) {
    val context = LocalContext.current
    // Si se debe recurrir a la visualización del código fuente
    var showSource by rememberSaveable(code) { mutableStateOf(false) }
    // Estado de carga:0=Cargando 1=éxito 2=falló
    var loadState by remember(code) { mutableIntStateOf(0) }
    // Phase 2: Si el usuario ha obtenido el"Enviar código fuente a un servicio de terceros"consentimiento expreso(No envíes en silencio)
    var allowRemoteRender by rememberSaveable(code) { mutableStateOf(false) }

    // Construyendo el servidor PlantUML URL(~1 modo:Pasar texto codificado en URL directamente)
    val imageUrl = remember(code) { buildPlantUmlUrl(code) }
    val plantumlCd = stringResource(R.string.markdown_plantuml_cd, code.take(80))

    // tarjeta exterior:Consistente con el estilo RichContentCard(surfaceVariant fondo translúcido + esquinas redondeadas medianas)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
            // etiqueta de encabezado
            ChartHeader(label = stringResource(R.string.markdown_plantuml_label))
            when {
                showSource -> {
                    ChartSourceView(code = code, onRetry = {
                        showSource = false
                        loadState = 0
                    })
                }
                loadState == 2 -> {
                    ChartErrorView(
                        message = stringResource(R.string.markdown_chart_render_failed),
                        onShowSource = { showSource = true },
                        onRetry = { loadState = 0 },
                    )
                }
                // Phase 2: No envíe código fuente a terceros sin el consentimiento del usuario,Mostrar mensaje explícito primero
                !allowRemoteRender -> {
                    PlantUmlRemoteNotice(
                        onRender = {
                            allowRemoteRender = true
                            loadState = 0
                        },
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val request = remember(imageUrl) {
                            ImageRequest.Builder(context)
                                .data(imageUrl)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = plantumlCd,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = plantumlCd },
                            contentScale = ContentScale.FillWidth,
                            onSuccess = { loadState = 1 },
                            onError = { loadState = 2 },
                        )
                        if (loadState == 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = MuseShapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(MusePaddings.contentGap),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) {
                                    MuseSpinner(
                                        size = 16.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.markdown_chart_loading),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 2: PlantUML Aviso explícito antes del renderizado remoto — Tenga en cuenta que se requiere una conexión a Internet y el código fuente se enviará a un servicio de terceros.,
 * La solicitud se inicia después de que el usuario hace clic.,Evite los mensajes salientes silenciosos.
 */
@Composable
private fun PlantUmlRemoteNotice(onRender: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = stringResource(R.string.markdown_plantuml_remote_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            MuseCapsuleButton(
                text = stringResource(R.string.markdown_plantuml_render_online),
                onClick = onRender,
                variant = IosCapsuleButtonVariant.Secondary,
                // v2.x: El icono de renderizado en línea se corrige a globe(semántica de redes)
                leadingIcon = MuseIcons.globe,
                fillWidth = false,
            )
        }
    }
}

/**
 * Construyendo renderizado del lado del servidor PlantUML URL(~1 modo sencillo).
 *
 * PlantUML Soporte de servidor `~1` El prefijo se pasa directamente al texto codificado en URL.:
 *  - GET https://www.plantuml.com/plantuml/svg/~1{URL_ENCODED_TEXT}
 *  - El servidor reconocerá automáticamente @startuml/@enduml,Completo por defecto
 *
 * Para código fuente extremadamente largo(Supera la longitud segura de la URL aprox. 4KB),Devuelve una cadena vacía para recurrir al código fuente si falla la carga.
 */
private fun buildPlantUmlUrl(code: String): String {
    // Si el código fuente no incluye explícitamente @startuml,maquillar(PlantUML Requisitos del servidor)
    val normalized = if (code.contains(PLANTUML_START)) code else "@startuml\n$code\n@enduml"
    val encoded = Uri.encode(normalized)
    // URL Límite de seguridad de longitud:Abandonar la representación del lado del servidor por encima de 4000 caracteres,Permitir que la bobina active el respaldo de falla
    if (encoded.length > 4000) return ""
    return "https://www.plantuml.com/plantuml/svg/~1$encoded"
}

/**
 * Etiqueta de encabezado de tarjeta de carta — Texto en color de contorno pequeño,con [RichContentCard] Estilo consistente.
 */
@Composable
private fun ChartHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(MusePaddings.tightGap))
}

/**
 * Vista fallida en la representación del gráfico — Mostrar mensaje de error + "Mostrar código fuente"botón(+ Opcional"Inténtalo de nuevo"botón).
 */
@Composable
private fun ChartErrorView(message: String, onShowSource: () -> Unit, onRetry: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = MuseShapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.markdown_chart_show_source),
                    onClick = onShowSource,
                    variant = IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.code,
                    fillWidth = false,
                )
                if (onRetry != null) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_retry),
                        onClick = onRetry,
                        variant = IosCapsuleButtonVariant.Secondary,
                        leadingIcon = MuseIcons.refresh,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

/**
 * Vista alternativa del código fuente del gráfico — La fuente monoespaciada muestra el código original + "Inténtalo de nuevo"botón.
 */
@Composable
private fun ChartSourceView(code: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MuseMonoFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            MuseCapsuleButton(
                text = stringResource(R.string.common_retry),
                onClick = onRetry,
                variant = IosCapsuleButtonVariant.Secondary,
                leadingIcon = MuseIcons.refresh,
                fillWidth = false,
            )
        }
    }
}
