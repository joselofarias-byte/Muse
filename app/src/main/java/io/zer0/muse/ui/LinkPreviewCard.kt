package io.zer0.muse.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val TAG = "LinkPreviewCard"

/**
 * El número máximo de bytes que se capturarán para una vista previa de un solo enlace(Prevenir páginas gigantes maliciosas OOM).
 * Tomar los primeros 50 KB de HTML suele ser suficiente para mantener og:* cabeza.
 */
private const val MAX_HTML_BYTES = 50_000

/**
 * Tiempo de espera total para la vista previa de un solo enlace(Contiene HEAD + GET + analizar).
 * Yohara 5s connect + 5s read alineación del límite superior superpuesta,Evite un solo estado de producción atascado.
 */
private const val FETCH_TIMEOUT_MS = 8_000L

data class LinkPreviewData(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    /** Phase 2: buscando(UI Mostrar marcador de posición de esqueleto). */
    val isLoading: Boolean = false,
    /** Phase 2: Error de recuperación(diferente de"Página sin título"). */
    val isFailed: Boolean = false,
)

private val URL_REGEX = Regex("https?://[^\\s)]+")

fun extractUrls(text: String): List<String> {
    return URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ')', '(', '[', ']', '!', '?', ';', ':') }.distinct().toList()
}

/**
 * Phase 2: Extraiga el nombre de dominio de origen para mostrar de la URL(Retire el www. prefijo;Si el análisis falla, regrese al original URL).
 */
internal fun linkDomain(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
    return host?.trim()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
}

/**
 * Universales <meta> Extracción de etiquetas regular — Haga coincidir todas las metaetiquetas a la vez,Luego filtre por el campo propiedad/nombre.
 *
 * antigua implementación [extractMeta] Internamente se crean 4 nuevos por cada convocatoria. Regex(property/name × secuencia),
 * 5 campos de destino × 4 = 20 Objetos de expresiones regulares,Y se reconstruye la vista previa de cada enlace.
 * Esto se cambia a un único índice general regular + Mapa.,de O(20×html) hasta O(html).
 *
 * grupo de captura:
 *  1. property/name valor(og:title / twitter:image espera)
 *  2. content valor
 *  Caso tolerante a fallos mediante IGNORE_CASE,Permitir cualquier orden de propiedad y contenido.
 */
private val META_REGEX = Regex(
    """<meta\s+[^>]*(?:property|name)\s*=\s*["']([^"']+)["'][^>]*content\s*=\s*["']([^"']*)["'][^>]*/?>|<meta\s+[^>]*content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']([^"']+)["'][^>]*/?>""",
    RegexOption.IGNORE_CASE,
)

private val TITLE_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)

/**
 * Extraer por nombre de campo de HTML meta content(Prioridad og:*,en segundo lugar twitter:*,finalmente <title>).
 */
private fun extractMeta(html: String, property: String): String? {
    // Iterar a través de todas las metaetiquetas a la vez,Crear índice por campo,Evite volver a escanear el html para cada propiedad.
    val metaMap = HashMap<String, String>(16)
    META_REGEX.findAll(html).forEach { m ->
        val key = m.groupValues[1].ifEmpty { m.groupValues[4] }
        val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
        if (key.isNotEmpty() && value.isNotEmpty() && key !in metaMap) {
            metaMap[key] = value.trim()
        }
    }
    return metaMap[property]
}

private fun extractTitle(html: String): String? {
    return TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim()
}

/**
 * Capture la información de vista previa de un solo enlace.
 *
 * La persona que llama debe llamar a IO Dispatcher(Esta función ya no corta hilos en el interior.,Evite el doble de sobrecarga de contexto).
 *
 * Puntos de reparación(v1.131 Optimización de la calidad del código):
 *  - HEAD/GET de [HttpURLConnection] con [BufferedReader] todos van [use] / try-finally,
 *    Las rutas de excepción ya no pierden FD/zócalos.
 *  - Ya no llamar a reconstruir cada vez Regex(META_REGEX/TITLE_REGEX Constante a nivel de archivo).
 *
 * Phase 2: tiempo de espera/anormal/SSRF Marca unificada de intercepción [LinkPreviewData.isFailed],
 * con"Obtenido correctamente pero la página no tiene título"diferenciar.
 */
private suspend fun fetchLinkPreview(url: String): LinkPreviewData = withContext(Dispatchers.IO) {
    // SSRF protección:Verificar el original al ingresar URL,Denegar intranet/Red no pública/no http(s) objetivo.
    if (SsrfGuard.isBlocked(url)) {
        Logger.w(TAG, "fetchLinkPreview Denegar intranet/Dirección de red no pública: $url")
        return@withContext LinkPreviewData(url = url, isFailed = true)
    }
    val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        resultOf<LinkPreviewData> {
            val finalUrl = followRedirect(url)
            // Verifique la dirección de destino de redireccionamiento nuevamente(Evitar el salto 302/307 a la intranet)
            if (SsrfGuard.isBlocked(finalUrl)) {
                Logger.w(TAG, "fetchLinkPreview Denegar redirección a intranet: $finalUrl")
                return@resultOf LinkPreviewData(url = finalUrl, isFailed = true)
            }
            val html = fetchHtml(finalUrl)
            val title = extractMeta(html, "og:title")
                ?: extractMeta(html, "twitter:title")
                ?: extractTitle(html)
                ?: ""
            val description = extractMeta(html, "og:description")
                ?: extractMeta(html, "twitter:description")
                ?: ""
            val imageUrl = extractMeta(html, "og:image")
                ?: extractMeta(html, "twitter:image")
                ?: ""
            LinkPreviewData(
                url = finalUrl,
                title = title,
                description = description,
                imageUrl = sanitizeImageUrl(imageUrl),
            )
        }
    }
    when {
        result == null -> {
            Logger.w(TAG, "fetchLinkPreview timeout: $url")
            LinkPreviewData(url = url, isFailed = true)
        }
        result.isError -> {
            Logger.w(TAG, "fetchLinkPreview failed: $url (${(result as? Result.Error)?.message})")
            LinkPreviewData(url = url, isFailed = true)
        }
        else -> result.getOrThrow()
    }
}

/** og:image Será capturado dos veces por Coil.,Rechazar también intranet/Dirección de imagen de red no pública. */
private fun sanitizeImageUrl(imageUrl: String): String = if (imageUrl.isNotBlank() && SsrfGuard.isBlocked(imageUrl)) {
    Logger.w(TAG, "fetchLinkPreview Rechazar direcciones de imágenes de intranet: $imageUrl")
    ""
} else {
    imageUrl
}

/** Inicie HEAD solo después de redirecciones,Regreso al aterrizaje final URL(Si falla, recurra al original URL). */
private fun followRedirect(url: String): String {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "HEAD"
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.connect()
        conn.url.toString()
    } catch (e: Exception) {
        Logger.w(TAG, "HEAD failed for $url: ${e.message}")
        url
    } finally {
        conn?.disconnect()
    }
}

/** GET Obtener el cuerpo HTML(Truncado a [MAX_HTML_BYTES] Evitar páginas enormes OOM). */
private fun fetchHtml(url: String): String {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "Mozilla/5.0 Muse/1.0")
        }
        conn.connect()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            reader.readText().take(MAX_HTML_BYTES)
        }
    } finally {
        conn?.disconnect()
    }
}

/**
 * Obtenga vistas previas de enlaces en el texto del mensaje.
 *
 * Phase 2: El valor inicial se devuelve inmediatamente."buscando"Marcador de posición(esqueleto),Una vez completada la búsqueda, reemplácela con el resultado./estado fallido,
 * Los usuarios ya no enfrentan"La tarjeta nunca aparece"ventana vacía.
 */
@Composable
fun rememberLinkPreviews(text: String): List<LinkPreviewData> {
    val urls = remember(text) { extractUrls(text).take(2) }
    val previews by produceState(
        initialValue = urls.map { LinkPreviewData(url = it, isLoading = true) },
        urls,
    ) {
        // v1.131: fetchLinkPreview cortar por dentro Dispatchers.IO + withTimeout,
        // El hilo principal de Redactar ya no está bloqueado aquí(La antigua implementación de produceState llama directamente al bloqueo de IO y se atasca. UI).
        value = if (urls.isEmpty()) emptyList() else urls.map { fetchLinkPreview(it) }
    }
    return previews
}

@Composable
fun LinkPreviewCard(preview: LinkPreviewData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Phase 2: Unifique las primitivas del contenedor del proyecto. [MuseSurface],Conserve las pequeñas esquinas redondeadas de la Tarjeta original.
    // surfaceVariant Color de fondo translúcido y comportamiento de clic en toda la tarjeta para abrir el enlace.
    MuseSurface(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        if (preview.isLoading) {
            LinkPreviewLoading(url = preview.url)
        } else {
            LinkPreviewContent(preview = preview)
        }
    }
}

/** Marcador de posición del esqueleto durante el agarre(Bloque de imagen + tres líneas de tira de texto). */
@Composable
private fun LinkPreviewLoading(url: String) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val loadingDescription = stringResource(R.string.link_preview_loading, linkDomain(url))
    Row(
        modifier = Modifier
            .padding(MusePaddings.contentGap)
            .semantics { contentDescription = loadingDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(MuseShapes.small)
                .background(barColor),
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBar(fraction = 0.7f, color = barColor)
            SkeletonBar(fraction = 0.95f, color = barColor)
            SkeletonBar(fraction = 0.4f, color = barColor)
        }
    }
}

/** Barra esqueleto de una sola línea. */
@Composable
private fun SkeletonBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(10.dp)
            .clip(MuseShapes.small)
            .background(color),
    )
}

/** Obtenga el contenido de la tarjeta una vez completada(Estado fallido / Estado sin título / Estado normal). */
@Composable
private fun LinkPreviewContent(preview: LinkPreviewData) {
    val domain = remember(preview.url) { linkDomain(preview.url) }
    Row(modifier = Modifier.padding(MusePaddings.contentGap)) {
        if (preview.imageUrl.isNotBlank()) {
            AsyncImage(
                model = preview.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 80.dp, height = 80.dp)
                    .clip(MuseShapes.small),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    preview.isFailed -> stringResource(R.string.link_preview_failed)
                    preview.title.isNotBlank() -> preview.title
                    // La búsqueda fue exitosa pero no og:title/<title>:con"Error de recuperación"Pantalla diferenciada
                    else -> stringResource(R.string.link_preview_no_title)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (preview.isFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!preview.isFailed && preview.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
