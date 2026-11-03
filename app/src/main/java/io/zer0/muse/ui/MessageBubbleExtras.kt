@file:Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "CyclomaticComplexity",
    "UnusedPrivateMember",
    "UnusedPrivateProperty",
)

package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.zer0.ai.core.AttachmentRef
import io.zer0.ai.core.RagCitation
import io.zer0.common.FileSizeFormatter
import io.zer0.muse.R
import io.zer0.muse.ui.chat.ToolCallVisuals
import io.zer0.muse.ui.chat.ToolResultRenderer
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AttachmentChip
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.0.80: Tarjetas de llamadas de herramientas (reescritura moderna).
 *
 * Objetivos de diseño:
 *  - unificado"seguimiento de procesos"Idioma: línea del cabello + superficie inferior, sin relleno de bloque de color
 *  - Un icono semántico por herramienta ([ToolCallVisuals]), puedes saber de un vistazo qué está haciendo la IA en el estado plegado
 *  - El estado plegado muestra un resumen en chino ("Busqué en la web xxx"), ya no el nombre simple de la herramienta + las primeras 40 palabras de resultados
 *  - Estado expandido: etiqueta de herramienta + par clave-valor de parámetro + partición de resultados (la fuente monoespaciada solo está reservada para el código/JSON)
 *  - El estado de falla está delineado con error + cruz roja; ejecutando (result Vacío y sin éxito) muestra el progreso de la rotación.
 *
 * Si el texto del resultado contiene la ruta del archivo en la zona de pruebas,Se representará como un chip adjunto en el que se puede hacer clic.(ver [AttachmentChip]).
 */
@Suppress("LongParameterList")
@Composable
internal fun ToolCallCard(
    toolName: String,
    arguments: String,
    result: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
    /** Tiempo de ejecución local (milisegundos);null Indica desconocido o aún en ejecución. */
    durationMs: Long? = null,
    /** v2.6.0: devolución de tarjeta(show_card Reenviar \"guardar\" y otras acciones después de renderizar);null El puente de la tarjeta no se representa. */
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
    /** v2.6.7: tarjeta de identidad(Enviar mensajes en la escena del chat. id),Inyectado como window.muse.cardId. */
    cardId: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // v2.6.0: show_card Representación dedicada —— Parámetros de la herramienta(title/code/data)Renderizar directamente como una tarjeta en línea,
    // Los pares clave-valor de parámetro originales ya no se muestran. El análisis falló(Parámetros incompletos)Cuando se muestre la tarjeta de herramientas normal, retrocederá.
    val showCardSpec = remember(toolName, arguments) {
        if (toolName == SHOW_CARD_TOOL_NAME) parseShowCardArgs(arguments) else null
    }

    // v2.0: Tarjeta de herramientas enchufable — Los complementos brindan acceso de visualización al declarar tarjetas HTML personalizadas para la herramienta
    var showPluginCard by rememberSaveable { mutableStateOf(false) }
    val pluginManager: io.zer0.muse.data.plugin.PluginManager = org.koin.compose.koinInject()
    val pluginCardHtml = remember(toolName) {
        runCatching { pluginManager.loadToolCardHtml(toolName) }.getOrNull()
    }

    // isSuccess=false y el resultado está vacío → Se considera en ejecución (la herramienta acaba de ser lanzada y aún no hay resultados)
    val isRunning = !isSuccess && result.isBlank()
    val hasFailed = !isSuccess && result.isNotBlank()

    // Las etiquetas que se utilizan en la exportación directa de productos y las tarjetas de herramientas generales se pueden resolver con antelación.
    val labelResources = LocalContext.current.resources
    val label = remember(toolName) { ToolCallVisuals.labelFor(toolName, labelResources) }

    // ── Producto directo ──────────────────────────────────────────────────────────
    //
    // Comentarios de los usuarios: tarjeta generada/La imagen en sí no debe contraerse en una "llamada a herramienta".
    //
    // La mente de la tarjeta de herramientas es "proceso" (lo que se llama parámetros, consumo de tiempo),
    // Y fotos/tarjeta/El archivo descargado es el "resultado"——El resultado es lo que ve el usuario, no el registro de ejecución.
    // Después de insertar el producto en la tarjeta plegable, el usuario primero debe expandir la "tarjeta de visualización" para ver el producto, lo cual es otra operación sin sentido.
    //
    // Regla: cuando la ejecución es exitosa y hay un producto visualizable, el producto se representará directamente (con una línea muy clara de identificación de origen),
    // Ya no se coloca la carcasa de la tarjeta de herramientas; falló/ejecutando/Cuando no haya ningún producto para mostrar, conserve la tarjeta de herramientas original (para facilitar la resolución de problemas).
    if (!isRunning && !hasFailed) {
        val directOutput = remember(toolName, arguments, result) {
            resolveDirectOutput(toolName, arguments, result)
        }
        if (directOutput != null) {
            DirectToolOutput(
                output = directOutput,
                label = label,
                onCardAction = onCardAction,
                cardId = cardId,
                modifier = modifier,
            )
            return
        }
    }

    // v1.79: extractFilePaths Contiene file.exists() disco IO,Movido al hilo LaunchedEffect + IO
    var attachments by remember(result) { mutableStateOf(emptyList<Pair<String, Long?>>()) }
    LaunchedEffect(result) {
        attachments = withContext(Dispatchers.IO) { extractFilePaths(result) }
    }
    // Expandir de forma predeterminada cuando hay archivos adjuntos,Permitir a los usuarios ver directamente chips de archivos descargables
    LaunchedEffect(attachments.isNotEmpty()) {
        if (attachments.isNotEmpty()) expanded = true
    }

    // I18N-01: Resumen de herramientas/Etiquetas analizadas por recursos(zh+en Ya entré strings_tools)
    val resources = LocalContext.current.resources
    val icon = remember(toolName) { ToolCallVisuals.iconFor(toolName) }
    // label Ya resuelto al comienzo de la función (la rama directa del producto debe usarse con anticipación)
    // F-19: Generar imágenes/vídeo/Una herramienta de código QR que muestra miniaturas de AsyncImage si el resultado contiene fuentes de imágenes.
    val imageSources = remember(toolName, result) {
        if (toolName in IMAGE_PREVIEW_TOOL_NAMES) extractImageSources(result) else emptyList()
    }
    val summary = remember(toolName, arguments, result, isSuccess) {
        when {
            isRunning -> "ejecutando…"
            else -> ToolCallVisuals.terminalSummaryFor(result, resources)
                ?: ToolCallVisuals.summaryFor(toolName, arguments, result, isSuccess, resources)
        }
    }

    val accentColor = when {
        hasFailed -> MaterialTheme.colorScheme.error
        isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    // v1.0.80: Cambie el color de fondo de la tarjeta a surfaceVariant(gris claro)/errorContainer(rojo claro),Elimina el trazo de 1dp.
    //   fondo blanco+Cuando el trazo se redondea y se suaviza, el color de las cuatro esquinas se vuelve más claro y parece una línea discontinua.,con tarjetas de planificación(Fondo gris claro sin trazos.)El estilo tampoco es uniforme.
    val cardBg = when {
        hasFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val chipBg = when {
        hasFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    }

    Surface(
        color = cardBg,
        shape = MuseShapes.medium,
        tonalElevation = MuseElevation.none,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
            // ── Encabezado: insignia de icono + etiqueta + resumen + flecha de expansión ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Insignia de icono: base de luz redonda + icono semántico
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(chipBg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRunning) {
                        MuseSpinner(
                            size = 16.dp,
                            color = accentColor,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
                Spacer(Modifier.width(MusePaddings.contentGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Tiempo de ejecución: permitir a los usuarios distinguir“Lento en el modelo”con“Lento en las herramientas”.
                // No se muestra durante la ejecución (durationMs es nulo), se muestra una vez finalizado.
                if (durationMs != null && durationMs > 0) {
                    Text(
                        text = formatToolDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // Flecha de expansión (con animación de rotación)
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                    label = "toolcard-chevron",
                )
                Icon(
                    imageVector = MuseIcons.chevronDown,
                    contentDescription = if (expanded) {
                        stringResource(R.string.action_collapse)
                    } else {
                        stringResource(R.string.action_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(MuseIconSizes.iconSmall)
                        .rotate(rotation),
                )
            }

            // ── Ampliar contenido ──
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(
                    modifier = Modifier.padding(top = MusePaddings.contentGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    // v2.6.0: show_card Cuerpo de la tarjeta —— Renderizar tarjetas HTML/SVG directamente,
                    // Los pares clave-valor del parámetro original y los resultados de texto ya no se muestran.
                    if (showCardSpec != null) {
                        io.zer0.muse.ui.markdown.RichContentCard(
                            language = if (showCardSpec.isSvg) "svg" else "html",
                            content = showCardSpec.code,
                            onCardAction = onCardAction,
                            inlineCardData = showCardSpec.data,
                            cardId = cardId,
                            heightSpec = showCardSpec.height,
                            showPreviewButton = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Parámetros: visualización del par clave-valor (ya no está desnudo) {"k":"v"})
                        val paramRows = remember(arguments) { parseParamRows(arguments) }
                        if (paramRows.isNotEmpty()) {
                            ParamSection(rows = paramRows)
                        }
                        // Resultado: cuadro de salida con título,JSON/diff/Representación dedicada para tablas
                        if (result.isNotBlank()) {
                            ResultSection(result = result, hasFailed = hasFailed)
                        }
                    }
                    // Chip accesorio
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(attachments, key = { it.first }) { (path, size) ->
                                AttachmentChip(filePath = path, fileSize = size)
                            }
                        }
                    }
                    // F-19: Generar imágenes/vídeo/Vista previa integrada de la tarjeta de producto con código QR (AsyncImage Miniatura, haga clic para ampliar (desde la simplicidad (no hecho))
                    if (imageSources.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(imageSources.take(6)) { src ->
                                AsyncImage(
                                    model = src,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(MuseShapes.small),
                                )
                            }
                        }
                    }
                    // v2.0: Entrada de tarjeta de herramientas personalizada enchufable(Herramienta complementaria que declara toolCards)
                    if (pluginCardHtml != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPluginCard = true }
                                .padding(vertical = MusePaddings.tightGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_tool_card_open),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    // v2.0: Ventana emergente de tarjeta de herramientas complementarias — Complemento de renderizado de solo lectura HTML,Los datos se inyectan a través de la ventana.__TOOL_CARD__
    if (showPluginCard && pluginCardHtml != null) {
        MuseDialog(
            onDismissRequest = { showPluginCard = false },
            title = label,
            content = {
                RichContentCard(
                    language = "html",
                    content = buildToolCardHtml(pluginCardHtml, toolName, arguments, result, isSuccess),
                    showPreviewButton = false,
                )
            },
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showPluginCard = false },
        )
    }
}

/** v2.0: Inyectar datos de llamadas de herramientas en tarjetas enchufables HTML(Pre-guion,solo lectura). */
private fun buildToolCardHtml(html: String, toolName: String, arguments: String, result: String, isSuccess: Boolean): String {
    val argsElement = runCatching {
        io.zer0.common.AppJson.parseToJsonElement(arguments)
    }.getOrNull() ?: kotlinx.serialization.json.JsonPrimitive(arguments)
    val json = kotlinx.serialization.json.buildJsonObject {
        put("toolName", kotlinx.serialization.json.JsonPrimitive(toolName))
        put("arguments", argsElement)
        put("result", kotlinx.serialization.json.JsonPrimitive(result.take(8000)))
        put("success", kotlinx.serialization.json.JsonPrimitive(isSuccess))
    }.toString()
    return "<script>window.__TOOL_CARD__ = $json;</script>\n$html"
}

/**
 * Partición de parámetros de estado expandido: título "parámetros" + Fila de pares clave-valor.
 *
 * Truncar a una línea si el valor es demasiado largo; Booleano/Los números se resaltan con el color primario y las cadenas se resaltan con onSurfaceVariant.
 */
@Composable
private fun ParamSection(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
        Text(
            text = stringResource(R.string.chat_tool_params),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        rows.forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = k,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    // LAYOUT-01: El nombre del parámetro se fija en un ancho de columna de 84.dp y se agregan líneas para evitar que los nombres de clave largos se ajusten palabra por palabra en columnas estrechas.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(84.dp),
                )
                Text(
                    text = v,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Partición de resultados ampliada: título "resultado" + Caja de salida de color claro.
 *
 * truncar (>500 caracteres) para evitar representar la mitad de un JSON/diff; el resultado completo es [ToolResultRenderer].
 */
@Composable
private fun ResultSection(result: String, hasFailed: Boolean) {
    val context = LocalContext.current
    val resultLabel = stringResource(R.string.chat_tool_result)
    // Long results keep a bounded preview soRepresentación inteligente stays cheap, but the user can
    // still expand the full text and copy it without leaving the message.
    var showFullResult by rememberSaveable(result) { mutableStateOf(false) }
    val isTruncated = result.length > RESULT_PREVIEW_CHARS
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = resultLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                // LAYOUT-01: La etiqueta de resultado que se ha ampliado se agregará al límite de línea. Si el ancho restante es extremadamente pequeño, ya no se ajustará palabra por palabra.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // LAYOUT-01: La etiqueta de acción al final de la línea solo se limita a la línea y no toma el ancho (izquierda"resultado"Ya weight(1f), tomar el ancho lo apretará hacia el medio)
            Text(
                text = stringResource(R.string.chat_tool_copy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(MuseShapes.small)
                    .clickable {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Muse Tool Result", result),
                        )
                        MuseToast.show(context.getString(R.string.chat_copied_toast))
                    }
                    .padding(MusePaddings.tinyGap),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MuseShapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
                when {
                    !isTruncated -> ToolResultRenderer(result = result)
                    showFullResult -> Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (hasFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    else -> Text(
                        text = result.take(RESULT_PREVIEW_CHARS) + "…",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (hasFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            if (showFullResult) {
                                R.string.chat_tool_result_collapse
                            } else {
                                R.string.chat_tool_result_expand
                            },
                            result.length,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = MusePaddings.tinyGap)
                            .clip(MuseShapes.small)
                            .clickable { showFullResult = !showFullResult }
                            .padding(MusePaddings.tinyGap),
                    )
                }
            }
        }
    }
}

/** Bounded preview shown before the user expands a long tool result. */
private const val RESULT_PREVIEW_CHARS = 500

// ── Análisis de parámetros ────────────────────────────────────────────────────────────

private data class ParamRow(val key: String, val value: String)

/** Analiza los argumentos JSON en filas de pares clave-valor; devuelve una lista vacía si no es JSON o está vacía (UI El área de parámetros no se muestra). */
private fun parseParamRows(arguments: String): List<Pair<String, String>> {
    val t = arguments.trim()
    if (t.isEmpty() || !t.startsWith("{")) return emptyList()
    val obj = runCatching { JSONObject(t) }.getOrNull() ?: return emptyList()
    val rows = mutableListOf<Pair<String, String>>()
    val keys = obj.keys().asSequence().toList()
    for (k in keys) {
        val v = obj.opt(k)
        if (v == null || v == JSONObject.NULL) continue
        val display: String = when (v) {
            is String -> v
            is Boolean, is Number -> v.toString()
            is JSONArray -> "[${v.length()} artículo]"
            is JSONObject -> "{…}"
            else -> v.toString()
        }
        if (display.isNotBlank()) rows += k to display
    }
    return rows.take(8) // Truncar la visualización cuando hay demasiados parámetros
}

// ── v2.6.0: show_card Análisis de parámetros y representación de tarjetas en línea ───────────────────────────

/** show_card Nombre de la herramienta. */
internal const val SHOW_CARD_TOOL_NAME = "show_card"

/**
 * Contenido de la tarjeta en línea extraído del argumento de la herramienta para show_card.
 *
 * @param code HTML/SVG fragmento(renderizar el cuerpo)
 * @param data Datos de enlace opcionales(Script en línea para tarjeta;null significa ninguno)
 * @param isSvg Si el fragmento es SVG(Determinar la ruta de renderizado)
 */
internal data class ShowCardSpec(
    val code: String,
    val data: String?,
    val isSvg: Boolean,
    /** El tamaño de la tarjeta declarado por el modelo (dejado como está, interpretado por la capa de renderizado);null No indica ninguna declaración. */
    val height: String? = null,
)

/**
 * Parámetro de herramienta de análisis JSON para show_card.
 *
 * Devolver nulo indica que el parámetro está incompleto(code Desaparecido o ilegal JSON),En este momento, la tarjeta de herramientas vuelve a su visualización normal.
 * Los valores de los parámetros pueden ser objetos anidados.(El modelo pasa datos/código como cadena u objeto.),Unidad aquí
 * Utilice la tolerancia a errores para obtener caracteres.:Las primitivas de cadena eliminan el texto escapado,El resto toma texto JSON.
 *
 * v2.6.7: apoyo**Transmisión de productos semiacabados**——Los parámetros de la herramienta se detallan pieza por pieza. Se requiere el JSON completo.
 * Se establece al final. La implementación anterior siempre devolvía nulo durante la transmisión, por lo que primero se mostraba como una tarjeta de herramientas (parámetro
 * Pares clave-valor), solo después de salir y volver a ingresar se pueden obtener los parámetros completos de la base de datos y convertirlos en tarjetas. La apariencia y la sensación son
 * “No se mostrará cuando se cree y aparecerá solo después de volver a ingresar.”.
 * Ahora, cuando falla el análisis estricto, se agregará una "extracción progresiva": intente extraer el contenido existente del campo de código.
 * Deja que la tarjeta crezca a medida que fluye.
 */
internal fun parseShowCardArgs(arguments: String): ShowCardSpec? {
    val t = arguments.trim()
    if (t.isEmpty()) return null
    // Se da prioridad al JSON completo; si no se puede obtener el código, se recurrirá a la extracción progresiva.
    return parseComplete(t) ?: parsePartial(t)
}

/** Análisis completo de parámetros JSON; devuelve nulo si no hay ningún código válido. */
private fun parseComplete(t: String): ShowCardSpec? {
    val obj = t.takeIf { it.startsWith("{") }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
    val code = obj?.let { jsonArgText(it.opt("code")) }?.takeIf { it.isNotBlank() }
    val data = obj?.let { jsonArgText(it.opt("data")) }?.takeIf { d -> d.isNotBlank() }
    val height = obj?.let { jsonArgText(it.opt("height")) }?.takeIf { h -> h.isNotBlank() }
    return code?.let { buildSpec(it, data, height) }
}

/** Transmisión de producto semiacabado: haga todo lo posible para extraer el valor actual del código de un JSON incompleto. */
private fun parsePartial(t: String): ShowCardSpec? {
    val code = partialJsonStringField(t, "code")?.takeIf { it.isNotBlank() } ?: return null
    return buildSpec(
        code,
        partialJsonStringField(t, "data")?.takeIf { it.isNotBlank() },
        partialJsonStringField(t, "height")?.takeIf { it.isNotBlank() },
    )
}

private fun buildSpec(code: String, data: String?, height: String? = null): ShowCardSpec {
    val isSvg = code.trimStart().let { s -> s.startsWith("<svg", ignoreCase = true) || s.startsWith("<?xml") }
    return ShowCardSpec(code = code, data = data, isSvg = isSvg, height = height)
}

/**
 * v2.6.7: Extraiga el valor actual de un campo de cadena de texto JSON incompleto.
 *
 * No buscar la integridad y la corrección: encontrar `"key"` mas tarde `"` punto de partida, luego deslice el dedo hacia atrás hasta la cita de cierre
 * O el final del texto (el final cuando la transmisión no finaliza) y luego restaurar los caracteres escapados a caracteres reales.
 * Solo admite valores de cadena —— show_card El código/datos es inherentemente una cadena.
 */
internal fun partialJsonStringField(raw: String, key: String): String? {
    val start = findStringValueStart(raw, "\"$key\"") ?: return null
    return readJsonStringFrom(raw, start)
}

/** encontró `"key"` La posición inicial de las comillas del valor de cadena que sigue (se omite la comilla). */
private fun findStringValueStart(raw: String, keyToken: String): Int? {
    var result: Int? = null
    var searchFrom = 0
    var searching = true
    while (searching) {
        val keyAt = raw.indexOf(keyToken, searchFrom)
        if (keyAt < 0) {
            searching = false
        } else {
            searchFrom = keyAt + keyToken.length
            val valueStart = stringValueStartAfter(raw, searchFrom)
            if (valueStart != VALUE_NOT_FOUND) {
                result = valueStart.takeIf { it >= 0 }
                searching = false
            }
        }
    }
    return result
}

/** El valor de retorno indica“Esta clave no es un campo de cadena, sigue buscando.”. */
private const val VALUE_NOT_FOUND = Int.MIN_VALUE

/** de [from] Omita los espacios en blanco y los dos puntos y devuelva el punto inicial del cuerpo de la cadena; devolver si no es un campo de cadena. [VALUE_NOT_FOUND]. */
private fun stringValueStartAfter(raw: String, from: Int): Int {
    var i = from
    while (i < raw.length && raw[i].isWhitespace()) i++
    if (i >= raw.length || raw[i] != ':') return VALUE_NOT_FOUND
    i++
    while (i < raw.length && raw[i].isWhitespace()) i++
    return if (i < raw.length && raw[i] == '"') i + 1 else -1
}

/** de [from] Comience a leer el cuerpo de una cadena JSON (tolera sin cerrar, lea hasta el final durante la transmisión). */
private fun readJsonStringFrom(raw: String, from: Int): String {
    val sb = StringBuilder()
    var i = from
    while (i < raw.length) {
        val c = raw[i]
        if (c == '"') return sb.toString()
        if (c != '\\') {
            sb.append(c)
            i++
            continue
        }
        val consumed = appendEscape(raw, i, sb)
        i += consumed
    }
    return sb.toString()
}

/** Procesa una secuencia de escape y devuelve el número de caracteres consumidos. */
private fun appendEscape(raw: String, at: Int, sb: StringBuilder): Int {
    val esc = if (at + 1 < raw.length) raw[at + 1] else null
    val simple = when (esc) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        else -> null
    }
    val unicode = esc
        ?.takeIf { it == 'u' && at + 6 <= raw.length }
        ?.let { raw.substring(at + 2, at + 6).toIntOrNull(16) }

    val consumed = when {
        simple != null -> 2
        unicode != null -> 6
        esc == null -> 1
        else -> 2
    }
    when {
        simple != null -> sb.append(simple)
        unicode != null -> sb.append(unicode.toChar())
        esc != null -> sb.append(esc)
    }
    return consumed
}

/** Obtener el texto de un valor JSON:Usar cadena directamente,GSON de objetos anidados/Las matrices utilizan texto JSON. */
private fun jsonArgText(value: Any?): String? = when (value) {
    null, JSONObject.NULL -> null
    is String -> value
    else -> value.toString()
}

/**
 * Extraiga rutas de archivos dentro de la zona de pruebas a partir del texto del resultado de la herramienta.
 *
 * Encuentra archivos con extensiones en rutas absolutas como /data/.../files/ o /data/.../cache/,
 * Sólo devuelve archivos que realmente existen en el disco,Deduplicación por ruta.
 */
private val PATH_PATTERN = Regex("""(/data/[^\s,)]+\.[a-zA-Z0-9]+)""")

private fun extractFilePaths(text: String): List<Pair<String, Long?>> {
    val results = mutableListOf<Pair<String, Long?>>()
    PATH_PATTERN.findAll(text).forEach { match ->
        val path = match.groupValues[1]
        val file = java.io.File(path)
        if (file.exists()) {
            results.add(path to file.length())
        }
    }
    return results.distinctBy { it.first }
}

// ── F-19: Generar imágenes/vídeo/Vista previa del código QR ──────────────────────────────────────

/** F-19: El nombre de la herramienta que necesita representar el producto de vista previa de la imagen. */
private val IMAGE_PREVIEW_TOOL_NAMES: Set<String> = setOf("generate_image", "generate_video", "generate_qrcode")

/** F-19: de resultados de herramientas coincidentes http(s) URL de la imagen. */
private val IMAGE_URL_PATTERN = Regex("""https?://[^\s)\]},，。；;]+""")

/** F-19: Archivos de imagen locales que coinciden con rutas absolutas dentro del entorno sandbox (generate_qrcode etc. para devolver la ruta del archivo cacheDir). */
private val IMAGE_LOCAL_PATH_PATTERN = Regex("""(/data/[^\s,)]+\.(?:png|jpe?g|gif|webp|bmp))""", RegexOption.IGNORE_CASE)

/**
 * F-19: Extrae fuentes de imágenes incrustadas (URL de red o rutas de archivos de imágenes locales) del texto del resultado de la herramienta.
 *
 * Regresar List<Any>:El elemento es String(URL) o java.io.File(mapa local), para que Coil AsyncImage se cargue directamente.
 */
private fun extractImageSources(text: String): List<Any> {
    val sources = mutableListOf<Any>()
    IMAGE_URL_PATTERN.findAll(text).forEach { match ->
        sources.add(match.value.trimEnd('.', ',', '.', ';', ';', ',', ')', ']', '}'))
    }
    IMAGE_LOCAL_PATH_PATTERN.findAll(text).forEach { match ->
        sources.add(java.io.File(match.value))
    }
    return sources.distinct()
}

/**
 * v1.95: Regularidad de la ruta del paquete de expresiones — Coincide con rutas absolutas que contienen /stickers/.
 */
internal val STICKER_PATH_PATTERN =
    Regex("""(/[^\s\]]*?/stickers/[^\s\]]+\.(?:png|jpg|jpeg|gif|webp|bmp))""", RegexOption.IGNORE_CASE)

internal fun extractStickerPaths(text: String): List<String> {
    return STICKER_PATH_PATTERN.findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

/**
 * Markdown Gramática de imágenes `![alt](url)` La dirección es regular.
 */
internal val MARKDOWN_IMAGE_PATTERN = Regex("""!\[[^\]]*]\((\S+?)\)""")

/**
 * Tome la dirección de la imagen de Markdown del texto.
 *
 * Markdown El analizador `![alt](url)` Bórralo del texto (para evitar duplicaciones con el área de la imagen),
 * Pero no había lugar para sacarlos y renderizarlos antes, y toda la línea de la imagen del modelo escrita en Markdown desapareció.
 * Sólo se aceptan direcciones absolutas y URI de datos que se puedan cargar directamente; las rutas relativas no se procesan y se dejan en el texto.
 */
internal fun extractMarkdownImageUrls(text: String): List<String> =
    MARKDOWN_IMAGE_PATTERN.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("data:image/") }
        .distinct()
        .toList()

/**
 * v2.x: Juicio de mensaje largo — Contenido estructurado o texto muy largo.,AI noticias"Sin tarjeta de crédito"
 * (El contenido extenso cae directamente en el borde de respiración, diseño de ancho completo,No encerrado en contenedores de burbujas.).
 *
 * Señal de decisión(cualquier golpe):
 *  - valla de bloque de código ```
 *  - Markdown línea de título(comienzo de linea #~###### + espacio)
 *  - Markdown fila separadora de tabla |---
 *  - El texto es demasiado largo.(> 500 personaje)
 */
internal fun isLongFormContent(content: String): Boolean {
    if (content.length > 500) return true
    if (content.contains("```")) return true
    if (content.contains("|---")) return true
    return LONG_FORM_HEADING_REGEX.containsMatchIn(content)
}

/** v2.x: Markdown línea de título(1-6 # + espacios al principio de la línea). */
private val LONG_FORM_HEADING_REGEX = Regex("(?m)^#{1,6}\\s")

/**
 * v1.133: RAG Lista de chips de referencia — Representación de referencias de búsqueda de la base de conocimientos,Haga clic para expandir la vista previa del fragmento.
 *
 * Phase 2 completo:
 *  - index Como tecla LazyRow, presione primero el índice para eliminar duplicados.,Los datos duplicados anormales ya no provocan fallos de conflictos clave;
 *  - chip aumentar contentDescription(Número de referencia + nombre del documento + ampliar/Ocultar recordatorio);
 *  - Agregue "Abrir documento" al área de expansión(Pasado de forma transparente por la persona que llama [onOpenDocument],No mostrar si no se proporciona)con la acción "Copiar resumen".
 */
@Composable
internal fun RagCitationChips(
    citations: List<RagCitation>,
    modifier: Modifier = Modifier,
    onOpenDocument: ((RagCitation) -> Unit)? = null,
) {
    // Si hay duplicados en el mismo mensaje index(anomalía de datos/Repetir inyección),distinctBy Evite conflictos de claves de LazyRow
    val uniqueCitations = remember(citations) { citations.distinctBy { it.index } }
    var expandedIndex by rememberSaveable { mutableStateOf(-1) }
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(uniqueCitations, key = { it.index }) { citation ->
                RagCitationChip(
                    citation = citation,
                    isExpanded = expandedIndex == citation.index,
                    onClick = {
                        expandedIndex = if (expandedIndex == citation.index) -1 else citation.index
                    },
                )
            }
        }
        val expanded = uniqueCitations.firstOrNull { it.index == expandedIndex }
        if (expanded != null) {
            RagCitationDetail(
                citation = expanded,
                onOpenDocument = onOpenDocument,
            )
        }
    }
}

/** Detalles de cita ampliados:Título del fragmento + resumen + puntuación + línea de acción(Abrir documento/copiar resumen). */
@Composable
private fun RagCitationDetail(citation: RagCitation, onOpenDocument: ((RagCitation) -> Unit)?) {
    val context = LocalContext.current
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.tightGap),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.contentGap)) {
            Text(
                text = stringResource(
                    R.string.chat_knowledge_chunk_title,
                    citation.docTitle,
                    citation.chunkIndex,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            Text(
                text = citation.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.chat_knowledge_chunk_score,
                    "%.2f".format(citation.score),
                    citation.matchType,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = MusePaddings.tightGap),
            )
            // Phase 2: Acción de referencia — Abrir documento(devolución de llamada opcional)/ Copiar resumen(portapapeles)
            Row(
                modifier = Modifier.padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onOpenDocument != null) {
                    RagCitationAction(
                        text = stringResource(R.string.chat_citation_open_doc),
                        onClick = { onOpenDocument(citation) },
                    )
                }
                RagCitationAction(
                    text = stringResource(R.string.chat_citation_copy_snippet),
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Muse RAG Citation", citation.snippet),
                        )
                        MuseToast.show(context.getString(R.string.chat_copied_toast))
                    },
                )
            }
        }
    }
}

/** Botón de acción de texto dentro de los detalles de la cotización. */
@Composable
private fun RagCitationAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(MuseShapes.small)
            .clickable(onClick = onClick)
            .padding(MusePaddings.tinyGap),
    )
}

@Composable
private fun RagCitationChip(citation: RagCitation, isExpanded: Boolean, onClick: () -> Unit) {
    val chipDescription = stringResource(
        R.string.chat_citation_chip_a11y,
        citation.index,
        citation.docTitle,
        stringResource(if (isExpanded) R.string.action_collapse else R.string.action_expand),
    )
    Surface(
        shape = MuseShapes.pill,
        color = if (isExpanded) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isExpanded) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = chipDescription },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            // LAYOUT-01: La marca del número de serie es la cantidad del encabezado de línea, que solo se limita a la línea y no toma el ancho (el texto docTitle ya es la opción de ancho)
            Text(
                text = "[${citation.index}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = citation.docTitle.take(20),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (isExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

// Los efectos especiales emocionales utilizan tamaños de fuente fijos, son estilos decorativos en línea y no ingresan al nivel de diseño del texto.
private fun moodSkinEffectStyle(effect: String): androidx.compose.ui.text.SpanStyle = when (effect) {
    "glow" -> androidx.compose.ui.text.SpanStyle(
        color = io.zer0.muse.ui.theme.MoodSkinColors.glow,
        fontWeight = FontWeight.SemiBold,
    )
    "big" -> androidx.compose.ui.text.SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold) // Tamaño de fuente decorativa de efectos especiales emocionales.
    "huge" -> androidx.compose.ui.text.SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold) // Tamaño de fuente decorativa de efectos especiales emocionales.
    "whisper" -> androidx.compose.ui.text.SpanStyle(fontSize = 13.sp, color = io.zer0.muse.ui.theme.MoodSkinColors.whisper) // Tamaño de fuente decorativa de efectos especiales emocionales.
    "red" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.red, fontWeight = FontWeight.Bold)
    "shake" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.shake, letterSpacing = 1.sp)
    "blur" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.blur)
    "glitch" -> androidx.compose.ui.text.SpanStyle(color = io.zer0.muse.ui.theme.MoodSkinColors.glitch, letterSpacing = 2.sp)
    else -> androidx.compose.ui.text.SpanStyle()
}

@Composable
private fun buildHighlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    if (query.isEmpty() || text.isEmpty()) return androidx.compose.ui.text.buildAnnotatedString { append(text) }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    return androidx.compose.ui.text.buildAnnotatedString {
        var start = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        while (true) {
            val index = lowerText.indexOf(lowerQuery, start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(androidx.compose.ui.text.SpanStyle(background = highlightColor, color = onHighlight)) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}

/**
 * v1.98 (T5): Cápsula de tareas pendientes — tarea larga(≥2 llamada de herramienta de paso)tiempo,Se muestra a la izquierda de la tarjeta de llamada de herramienta.
 */
@Composable
private fun TaskProgressBadge(
    phase: io.zer0.muse.ui.taskcard.TaskCardPhase,
    successCount: Int,
    total: Int,
    isExecuting: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasFailed = phase == io.zer0.muse.ui.taskcard.TaskCardPhase.DONE && successCount < total
    val badgeColor = if (hasFailed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier.width(36.dp),
        shape = MuseShapes.pill,
        color = badgeColor.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = MusePaddings.contentGap, horizontal = MusePaddings.tinyGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        ) {
            if (isExecuting) {
                MuseSpinner(
                    size = MusePaddings.screen,
                    color = badgeColor,
                )
            } else {
                Icon(
                    imageVector = MuseIcons.wrench,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(MusePaddings.screen),
                )
            }
            Text(
                text = "$successCount/$total",
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Producto directo
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Contenido exportable producido por la herramienta.
 *
 * Distinga conceptualmente entre "proceso" y "resultado": la tarjeta de herramientas registra el proceso de ejecución,
 * Lo que se lleva aquí es lo que el usuario realmente quiere ver (tarjetas, imágenes, archivos) y no debe doblarse ni ocultarse.
 */
internal sealed interface DirectOutput {
    /** Tarjetas interactivas (HTML/SVG). */
    data class Card(
        val code: String,
        val isSvg: Boolean,
        val data: String?,
        /** Modelo dimensiones declaradas (show_card altura),null Indica adaptativo. */
        val height: String? = null,
    ) : DirectOutput

    /** Medios generados (fuente de vista previa de imagen/vídeo/código QR). */
    data class Media(val sources: List<Any>) : DirectOutput

    /** Archivos que se han colocado en el disco (descargar/exportación). */
    data class File(val paths: List<String>) : DirectOutput
}

/** Herramienta directa → Tipo de producto. Cuando la ejecución es exitosa, se omite la cubierta de la tarjeta de herramientas y el producto se presenta directamente al usuario. */
private val CARD_OUTPUT_TOOLS: Set<String> = setOf(SHOW_CARD_TOOL_NAME, "render_data")

/**
 * v2.6.8: El producto de esta herramienta es un "entregable", no un "proceso".
 *
 * show_card La tarjeta generada es lo que el usuario quiere (se puede hacer clic, enviar y guardar).
 * No "AI Registro "Ajustado una herramienta". El proceso se puede dividir en los pasos de la tarjeta de tareas, pero los entregables no ——
 * Los usuarios no deben expandir un plan de ejecución solo para ver la tarjeta que acaban de generar.
 *
 * Este juicio es utilizado por tres lugares al mismo tiempo, asegurando que los tres lugares tengan el mismo calibre:
 *  - Paso de la tarjeta de tareas: deje solo una línea de referencia, ya no presione la tarjeta en el paso;
 *  - Deduplicación de flujo de mensajes: no ocultar los mensajes de herramientas de show_card como "representados por tarjeta de tarea";
 *  - Agrupación de procesos: no combine show_card con la tarjeta de grupo "llamada continua a herramientas" (de lo contrario, deberá expandirla para verla).
 *
 * Función pura, conveniente para pruebas individuales.
 */
internal fun isInlineDeliverableTool(toolName: String?): Boolean = toolName == SHOW_CARD_TOOL_NAME

/**
 * v2.6.8: La identificación del mensaje de la herramienta que pertenece al "entregable" en la lista de mensajes (actualmente show_card).
 *
 * La persona que llama lo utiliza para extraer estos mensajes del conjunto de deduplicación de "La tarjeta de tareas ha generado el producto".
 * Deje que la tarjeta permanezca en el flujo del mensaje como una tarjeta en línea de primera clase.
 */
internal fun inlineDeliverableMessageIds(messages: List<io.zer0.ai.core.UIMessage>): Set<String> = messages.asSequence()
    .filter { isInlineDeliverableTool(it.toolCallInfo?.toolName) }
    .map { it.id.toString() }
    .toHashSet()

private val MEDIA_OUTPUT_TOOLS: Set<String> = setOf("generate_image", "generate_video", "generate_qr_code")

private val FILE_OUTPUT_TOOLS: Set<String> = setOf("create_download")

/**
 * Determine si una llamada de herramienta exitosa tiene productos de salida directos.
 *
 * Devolver nulo significa "esto es un proceso, no un resultado"——La persona que llama debería recurrir a la tarjeta de herramientas normal.
 * Función pura, conveniente para pruebas individuales; no se requiere IO de disco (la extracción de ruta la maneja el lado de renderizado).
 */
internal fun resolveDirectOutput(toolName: String, arguments: String, result: String): DirectOutput? {
    if (result.isBlank()) return null
    return when {
        toolName in CARD_OUTPUT_TOOLS -> {
            val spec = runCatching { parseShowCardArgs(arguments) }.getOrNull()
            if (spec != null && spec.code.isNotBlank()) {
                DirectOutput.Card(code = spec.code, isSvg = spec.isSvg, data = spec.data, height = spec.height)
            } else {
                null
            }
        }
        toolName in MEDIA_OUTPUT_TOOLS -> {
            val sources = extractImageSources(result)
            if (sources.isEmpty()) null else DirectOutput.Media(sources)
        }
        toolName in FILE_OUTPUT_TOOLS -> {
            val paths = extractFilePathsSync(result)
            if (paths.isEmpty()) null else DirectOutput.File(paths)
        }
        else -> null
    }
}

/** Versión síncrona de extracción de ruta (solo coincidencia regular, sin verificación del sistema de archivos), utilizada para la determinación y representación de funciones puras. */
private fun extractFilePathsSync(result: String): List<String> =
    runCatching { FILE_PATH_REGEX.findAll(result).map { it.value }.distinct().toList() }
        .getOrDefault(emptyList())

private val FILE_PATH_REGEX = Regex("""/[\w\-./]+\.(md|txt|json|csv|pdf|zip|png|jpg|jpeg|webp|mp4)\b""")

/**
 * Renderice directamente desde el producto: logotipo de fuente muy luminosa + cuerpo del producto.
 *
 * El propósito de conservar el logotipo es que los usuarios sepan que "esto es algo que acaba de generar la IA".
 * Pero sin usar el estuche plegable——El producto en sí es visible en la primera pantalla.
 */
/**
 * v2.6.7: Entrada de renderizado de productos —— tarjeta de plan/Aquí también van los pasos y resultados de la tarjeta de tareas.
 *
 * Motivo de la separación: Resulta que el producto solo se puede renderizar desde la tarjeta de herramientas aislada. Una vez incluida la llamada de herramienta en la tarjeta de diseño
 * “pasos”Aquí, el producto degenera en un texto truncado que los usuarios no pueden ver.
 *
 * v2.6.8: Conforma el puente de devolución de la tarjeta. La implementación anterior está codificada aquí. `onCardAction = null`, entonces
 * La misma tarjeta show_card: se puede hacer clic cuando aparece sola. Una vez que se incluya en el paso de la tarjeta de tareas, el puente se romperá. ——
 * El HTML renderizado se puede ver, pero todos los controles no responden cuando se hace clic (la fuente directa de retroalimentación real de la máquina).
 * El puente debe pasarse con el artefacto y la posición de renderizado no debe cambiar las capacidades de la tarjeta.
 *
 * @param label Identificación de fuente (pase el nombre de la herramienta); si la cadena está vacía, la línea de identificación no se mostrará.
 * @param onCardAction Puente de devolución de tarjeta;null La tarjeta solo se muestra sin interacción (escenario sin chat)
 */
@Composable
internal fun DirectToolOutputInline(
    output: DirectOutput,
    cardId: String? = null,
    label: String = "",
    modifier: Modifier = Modifier,
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
) {
    DirectToolOutput(
        output = output,
        label = label,
        onCardAction = onCardAction,
        cardId = cardId,
        modifier = modifier,
        showLabel = label.isNotBlank(),
    )
}

@Composable
private fun DirectToolOutput(
    output: DirectOutput,
    label: String,
    onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
    cardId: String? = null,
    modifier: Modifier = Modifier,
    /** v2.6.7: Los productos de la tarjeta del plan no son obligatorios.“Identificación de fuente”Bien, el espacio es precioso. */
    showLabel: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Identificación de fuente de luz muy
        if (showLabel) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = MuseIcons.sparkle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (output) {
            is DirectOutput.Card -> {
                io.zer0.muse.ui.markdown.RichContentCard(
                    language = if (output.isSvg) "svg" else "html",
                    content = output.code,
                    onCardAction = onCardAction,
                    inlineCardData = output.data,
                    cardId = cardId,
                    heightSpec = output.height,
                    showPreviewButton = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is DirectOutput.Media -> {
                // Los medios generados se muestran directamente (no se incluyen tarjetas de herramientas) y los usuarios pueden ver el producto terminado en la primera pantalla.
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    items(output.sources.take(6)) { src ->
                        AsyncImage(
                            model = src,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(MuseShapes.medium),
                        )
                    }
                }
            }
            is DirectOutput.File -> {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    output.paths.forEach { path ->
                        AttachmentChip(filePath = path, fileSize = null)
                    }
                }
            }
        }
    }
}

/**
 * v2.6.13: Chip adjunto en mensaje.
 *
 * Al enviar, el texto del archivo adjunto se comprime en una línea de texto "guardado en el espacio de trabajo" y se inserta en el cuerpo del mensaje. Esa es una referencia para el modelo.
 * No es adecuado para usarlo como interfaz: no puede ver cuántos archivos hay, qué tamaño tienen y no puede hacer clic en ellos. Aquí los chips se representan según metadatos estructurados.
 */
@Composable
internal fun MessageAttachmentChips(
    attachments: List<AttachmentRef>,
    onOpen: (AttachmentRef) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MuseShapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable { onOpen(attachment) }
                    .heightIn(min = MuseIconSizes.touchTarget)
                    .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.labelVerticalGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = attachmentIcon(attachment),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                )
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (attachment.size > 0) {
                    Text(
                        text = FileSizeFormatter.format(attachment.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v2.6.13: Vista previa del archivo adjunto.
 *
 * PDF Utilice el visor pdf.js existente; las clases de texto se muestran en línea; el resto (docx/xlsx/Paquete comprimido) solo muestra dónde está el original.
 * No "deje que una aplicación externa lo abra": SAF puede montar el espacio de trabajo y no se puede obtener la ruta del archivo que se puede compartir.
 */
@Composable
internal fun AttachmentPreviewDialog(
    attachment: AttachmentRef,
    loadBytes: suspend () -> ByteArray?,
    onDismiss: () -> Unit,
) {
    if (isPdfAttachment(attachment)) {
        io.zer0.muse.ui.artifact.DocPreviewDialog(
            fileName = attachment.name,
            loadBytes = loadBytes,
            onDismiss = onDismiss,
        )
        return
    }

    var text by remember(attachment.id) { mutableStateOf<String?>(null) }
    var loaded by remember(attachment.id) { mutableStateOf(false) }
    LaunchedEffect(attachment.id) {
        if (isTextAttachment(attachment)) {
            val bytes = runCatching { loadBytes() }.getOrNull()
            if (bytes != null && bytes.size <= MAX_INLINE_PREVIEW_BYTES) {
                text = String(bytes, Charsets.UTF_8)
            }
        }
        loaded = true
    }

    val unavailableText = stringResource(
        R.string.chat_attachment_preview_unavailable,
        attachment.workspacePath.orEmpty(),
    )
    MuseDialog(
        onDismissRequest = onDismiss,
        title = attachment.name,
        content = {
            val body = text
            when {
                body != null -> Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                !loaded -> MuseSpinner(size = MuseIconSizes.iconMedium)
                else -> Text(
                    text = unavailableText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissText = stringResource(R.string.common_close),
        onDismiss = onDismiss,
    )
}

/** Seleccione un icono semántico por MIME y extensión. */
private fun attachmentIcon(attachment: AttachmentRef): androidx.compose.ui.graphics.vector.ImageVector {
    val mime = attachment.mimeType.lowercase()
    val ext = attachment.name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> MuseIcons.image
        mime.startsWith("audio/") || mime.startsWith("video/") -> MuseIcons.paperclip
        mime == "application/pdf" || ext == "pdf" -> MuseIcons.fileText
        ext in TEXT_ATTACHMENT_EXTENSIONS -> MuseIcons.fileText
        else -> MuseIcons.paperclip
    }
}

private fun isPdfAttachment(attachment: AttachmentRef): Boolean =
    attachment.mimeType.equals("application/pdf", ignoreCase = true) ||
        attachment.name.endsWith(".pdf", ignoreCase = true)

private fun isTextAttachment(attachment: AttachmentRef): Boolean {
    val mime = attachment.mimeType.lowercase()
    val ext = attachment.name.substringAfterLast('.', "").lowercase()
    return mime.startsWith("text/") ||
        mime in setOf("application/json", "application/xml") ||
        ext in TEXT_ATTACHMENT_EXTENSIONS
}

/** Extensiones que se pueden previsualizar en línea. */
private val TEXT_ATTACHMENT_EXTENSIONS =
    setOf("txt", "md", "json", "xml", "csv", "log", "yaml", "yml", "html", "htm")

/** El límite superior de bytes de vista previa en línea: si excede el límite, no se insertará en Compose para evitar que un fragmento de registro bloquee la interfaz. */
private const val MAX_INLINE_PREVIEW_BYTES = 512 * 1024
