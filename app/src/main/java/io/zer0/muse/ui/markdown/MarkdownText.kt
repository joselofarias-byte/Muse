package io.zer0.muse.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.common.markdown.FrontmatterParser
import io.zer0.muse.perf.MarkdownParseCache
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMarkdownRhythm
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// L-MD15 Reparación: Constante de cálculo del ancho del canal del número de línea(Originalmente, 10dp/16dp estaba codificado en CodeBlockView)
// ancho por dígito 10dp,ancho mínimo 16dp(1 El número de bits también preserva el espacio de alineación.)
private const val GUTTER_CHAR_WIDTH_DP = 10
private const val GUTTER_MIN_WIDTH_DP = 16

// L2 Reparación: La regularidad del formato en línea se promueve al nivel de archivo private val,Evite volver a compilar cada vez que se llame a parseInline
// regla principal: enlace [text](url) Prioridad,y luego `code` / **bold** / *italic*
private val INLINE_REGEX = Regex(
    """\[(.+?)]\((.+?)\)|`(.+?)`|\*\*(.+?)\*\*|\*(.+?)\*""",
)

// L2 Reparación: [N] Reglas de numeración de referencias — Sólo si N es un número entero positivo y no [text](url) forma
private val CITATION_REGEX = Regex("""\[(\d+)]""")

// v1.97: Reconocimiento automático de URL en texto plano(linkify enlace automático)
// Coincide con aquellos que comienzan con http/https URL,dejar en blanco/comillas/paréntesis/Termina la puntuación china
// Puntuación china al final.(. ,,;!?』』)Excluido por limpieza secundaria
// v1.97 Etapa 2: internal Para pruebas unitarias para cubrir casos extremos(URL_AUTOLINK_REGEX prueba de límites)
// v1.97 Corrección de la fase 2: Eliminación de clase de personaje `:` y `?` — Estos dos caracteres son legales en el cuerpo de la URL.(puerto :8080 / query ?q=1),
// Las exclusiones pueden provocar que las URL se trunquen.ASCII período `.` Del mismo modo, no se excluye(URL legal dentro,Como por ejemplo example.com),Puntuación al final de la oración
// Limpiado dos veces por el sitio de llamadas(Mantén las clases de personajes simples aquí.).
// Nota: `!` Mantener exclusiones(Signo de exclamación chino de ancho completo ! No debe incluirse cuando va seguido de texto en chino. URL).
internal val URL_AUTOLINK_REGEX = Regex("""https?://[^\s<>"'`)\]]』』. ,,;!]+""")

/**
 * Componente de renderizado Markdown ligero(autoimplementable,No se introducen bibliotecas de terceros.).
 *
 * apoyo:
 *  - bloque de código de cerca ```(Fuente monoespaciada + fondo oscuro de papel cálido + desplazamiento horizontal + números de línea + botón copiar + plegado de código largo)
 *  - Título #/##/###(Tamaño de fuente jerárquico)
 *  - código en línea `...`(Monoancho + fondo claro)
 *  - Negrita **...** / cursiva *...*
 *  - enlace [...](...)(secondary color + subrayado)
 *  - ordenado/lista desordenada(sangría + signo)
 *  - Cotización >(barra de color izquierda)
 *  - linea horizontal ---
 *
 * No compatible: Nota al pie/fotos(La mesa es compatible — ver TableView).
 * v1.0.74 fix (Auditoría inicial 7): La anotación original decía"Las tablas no son compatibles"Pero ya hay renderizado de TableView en el archivo.,Comentarios arreglados.
 *
 * Phase 8.3 mejorar:
 *  - número de línea del bloque de código(lado izquierdo gutter,Alinear para igual ancho)
 *  - Botón de copia de bloque de código(esquina superior derecha,Toast retroalimentación)
 *  - Doblar bloques de código automáticamente(>30 Las filas están contraídas de forma predeterminada.,Mostrar las primeras 10 filas + "Expandir N líneas")
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    citationUrls: List<String> = emptyList(),
    /**
     * v1.42: Etiqueta de mensaje de transmisión.
     * Omitir la detección de clic en el enlace cuando sea verdadero(pointerInput)Optimice con diseños complejos,Reducir los gastos generales de reorganización.
     *
     * v1.97 (P2): Habilite dos optimizaciones adicionales al transmitir:
     *  1. El número de bloques excede [STREAMING_BLOCK_CAP] solo renderiza el ultimo [STREAMING_BLOCK_CAP] bloques,
     *     para arriba "…" Marcador de posición(Evite la combinación completa de mensajes extremadamente largos,Compose El costo de reorganización aumenta linealmente con el número de bloques.).
     *  2. CodeBlock / Formula / Table / RichContentCard Bajar a vista previa de solo texto,
     *     Omitir resaltado de sintaxis/renderizado LaTeX/diseño de tabla/renderizado SVG-HTML y otros diseños de peso;
     *     Después de la transmisión(isStreaming=false)Reanudar el renderizado completo.
     */
    isStreaming: Boolean = false,
    /**
     * v1.0.63: Deshabilitar la detección de clics en enlaces cuando esté en el modo de selección múltiple de mensajes,Permite pulsar sobre el mensaje completo para acceder a la selección.
     */
    disableLinks: Boolean = false,
    /**
     * v1.0.72: Devolución de llamada al presionar prolongadamente el área sin enlace(Menú de burbuja de pulsación larga).
     *
     * MarkdownText El pointerInput consumirá todos los eventos de pulsación larga.(Incluso áreas no vinculadas),
     * Como resultado, el Clickable onLongClick combinado de la burbuja exterior no puede recibir el gesto. → Al presionar prolongadamente el mensaje no aparece el menú.
     * Después de pasar esta devolución de llamada,Al presionar prolongadamente un área no vinculada, se invocará en lugar de ser tragada silenciosamente.
     */
    onLongPressOutside: (() -> Unit)? = null,
    /**
     * HTML/SVG Bloqueo de código de devolución de llamada de vista previa de pantalla completa.
     * Cuando el usuario hace clic en la esquina superior derecha del bloque de código HTML/SVG"Vista previa"Se activa cuando el ícono,
     * El parámetro es el código fuente HTML completo.(SVG Empaquetado para estar completo HTML).
     * por [MessageBubble] / [ChatScreen] Inyectar lógica de navegación,saltar a [HtmlPreviewScreen].
     */
    onHtmlPreview: (String) -> Unit = {},
    /**
     * v1.0.92: Devolución de llamada de acción de devolución de tarjeta(Inyección de escena de chat;null Cuando la tarjeta se renderiza exclusivamente sin scripts).
     */
    onCardAction: ((CardAction) -> Unit)? = null,
    /**
     * v2.6.13: Identidad de la tarjeta (id del mensaje en el escenario de chat).
     *
     * Consecuencias directas de la transmisión perdida: en el texto ```html / ```svg La tarjeta no se puede leer `window.muse.cardId`,
     * entonces `getData()` No puedo obtener los datos que enlacé.`send()` No hay fuente al regresar. ——
     * El rendimiento es "la tarjeta se puede procesar pero la interacción está muerta" y no se informa ningún error.
     */
    cardId: String? = null,
    /**
     * v1.0.53: Si se debe renderizar la portada(Predeterminado false;Página de detalles del documento true).
     * La escena del mensaje de chat no muestra la portada.,Mantener el status quo.
     */
    renderCover: Boolean = false,
    /**
     * v1.0.53: Analizador de imágenes de portada(De forma predeterminada, la ruta relativa a filesDir se utiliza para analizar;Se pueden inyectar pruebas).
     * Devuelve nulo para indicar un error de análisis,Baje a renderizado de solo texto.
     */
    coverResolver: ((String) -> File?)? = null,
) {
    // v1.0.53: frontmatter cubrir — renderCover=true Y cuando hay un campo de cobertura,
    // Análisis de texto después de eliminar el texto.,El área de la portada se representa encima del texto principal.
    val coverFrontmatter = if (renderCover) FrontmatterParser.parse(text) else null
    val bodyText = if (coverFrontmatter != null) FrontmatterParser.strip(text) else text
    val coverFile: File? = if (coverFrontmatter?.cover != null) {
        resolveCoverFile(coverFrontmatter.cover, coverResolver)
    } else {
        null
    }

    // v1.101 (P9): Mueva parseMarkdown al hilo de fondo durante la transmisión,Evite el análisis regular de alta frecuencia en el hilo principal.
    // La transmisión de texto actualiza cada 80 ms,Originales remember(text) Todo el texto será procesado en el hilo principal.(crecer con mensajes
    // Hasta miles de caracteres)Escaneo regular repetido,Es el principal cuello de botella restante del retraso en la transmisión.
    // Utilice produceState para resolver en Dispatchers.Default:text Cuando se produzcan cambios, cancele la rutina anterior y reinicie la nueva rutina.,
    // value Mantener el último resultado del análisis(Visualmente durante la transmisión ~80ms retraso,Un retraso de cuadro es aceptable).
    // sin transmisión(noticias historicas)mantener remember(text) Análisis sincrónico,Evite parpadear al desplazarse por la pantalla.
    //
    // A3 (v1.x): Ruta de transmisión utilizada en su lugar [IncrementalMarkdownParser]——Vuelva a escanear solo la cola adjunta,
    // El bloque de cabecera estabilizado sigue siendo la misma instancia.,Compose Omita el reensamblaje de estos bloques durante el reensamblaje.;
    // La instancia del analizador está mantenida por recordar(Ninguno key),mensaje completo/Reutilizar el mismo caché después de editar,
    // Cuando el contenido no es puramente adjunto, el analizador revierte automáticamente la cantidad total internamente.
    val allBlocks: List<MarkdownBlock> = if (isStreaming) {
        val parser = remember { IncrementalMarkdownParser() }
        val streamed by produceState<List<MarkdownBlock>>(emptyList(), bodyText) {
            // v1.0.92: 50ms → 16ms(un cuadro) — Se producen retrasos en la transmisión de contenido"catón+destello"Una de las fuentes del sentimiento.;
            // chunk intervalo(~50ms)Ya proporciona reducción de frecuencia natural.,Este retraso sólo se utiliza para permitir que una rápida sucesión de cambios se fusione en un fotograma.
            delay(16)
            val parsed = withContext(Dispatchers.Default) { parser.parse(bodyText) }
            value = parsed
        }
        streamed
    } else {
        // sin transmisión(noticias historicas):Análisis sincrónico + aciertos de LRU a nivel de proceso.
        // La causa principal del retraso en el desplazamiento es que LazyColumn se destruye cuando se implementa. composition,remember luego invalidado,
        // El importe total se volverá a calcular al retroceder.;El resultado del análisis cae en [MarkdownParseCache] después,Desplácese hacia afuera y hacia atrás para un golpe directo.
        // El primer fotograma todavía se analiza sincrónicamente.,No cambie a Dispatchers. Predeterminado: cuando no hay caché, primero se generará un marco vacío.,Cortar a
        // contenido real,Es un salto de altura en sí mismo.(Desplazarse es más deslumbrante que guardar un análisis).
        // key La parte de identificación de [cardId]:La escena del chat es el mensaje. id(msg.id.toString()),
        // Escenario donde no se pasa cardId(Vista previa del documento, etc.)Degenera en un hash de contenido puro.
        val cacheKey = remember(cardId, bodyText) { MarkdownParseCache.keyOf(cardId, bodyText) }
        remember(cacheKey) { MarkdownParseCache.getOrParse(cardId, bodyText) }
    }
    // v1.97 (P2): Streaming + solo representa la cola cuando el número de bloques excede el umbral,Reducir los costos de reorganización de Compose
    val truncated = isStreaming && allBlocks.size > STREAMING_BLOCK_CAP
    val blocks = if (truncated) allBlocks.takeLast(STREAMING_BLOCK_CAP) else allBlocks
    // El color del tema requerido para el formato en línea(@Composable lectura de contexto,Pasado a no componible parseInline)
    // v1.97: URL El color de resaltado cambió de secundario a primary(color del tema),Hacer que los enlaces sean más visibles
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBgColor = MaterialTheme.colorScheme.surfaceVariant
    val quoteTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Phase 8.4: Número de referencia [N] color(Usar secundario para resaltar,Consistente con hipervínculos)
    val citationColor = MaterialTheme.colorScheme.secondary

    Column(modifier = modifier.fillMaxWidth()) {
        // v1.0.53: área de cobertura — 16:5 Banner + título de pila de máscara de degradado inferior(Solo escenario de documentación)
        if (coverFile != null && coverFile.exists()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 5f)
                    .clip(MuseShapes.large),
            ) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = coverFrontmatter?.title ?: "cubrir",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Máscara de degradado inferior + título
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        ),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    if (!coverFrontmatter?.title.isNullOrBlank()) {
                        Text(
                            // v1.0.74 fix (Auditoría inicial 7): El nivel superior se ha considerado vacío.,Eliminar la doble afirmación no nula
                            text = coverFrontmatter?.title.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // v1.97 (P2): Al truncar, el marcador de posición se omite en la parte superior de la pantalla.,Indica al usuario que el contenido anterior se ha contraído(Reanudar después de que finalice la transmisión)
        if (truncated) {
            Text(
                text = "…",
                style = style,
                color = quoteTextColor,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
        // L-MD16 Descripción: Aquí todos los bloques se representan usando forEachIndexed en lugar de LazyColumn.
        // Razón: MarkdownText Usado dentro de una sola burbuja de mensaje,El número de bloques en un solo mensaje suele ser muy pequeño(Algunos párrafos de texto + algunos bloques de código),
        // La sobrecarga de utilizar una columna simple es aceptable. si usas LazyColumn,Destruirá el diseño en línea de la burbuja(Altamente adaptativo),
        // Y LazyColumn no se puede anidar en un contenedor principal de altura no fija(El flujo de mensajes en sí es LazyColumn).
        // v2.x Calibración de formato de texto largo: Los bloques de líneas en blanco ya no se muestran individualmente Spacer,Más bien, converge en"¿Hay una línea de párrafo en blanco antes?"
        // un booleano,por [markdownBlockGap] Determina uniformemente el ritmo vertical entre bloques adyacentes.(párrafo/bloquear/Archivos de título).
        val renderedBlocks = remember(blocks) {
            val visible = ArrayList<Pair<MarkdownBlock, Boolean>>(blocks.size)
            var blankSeen = false
            blocks.forEach { block ->
                if (block is MarkdownBlock.Blank) {
                    blankSeen = true
                } else {
                    visible.add(block to blankSeen)
                    blankSeen = false
                }
            }
            visible
        }
        renderedBlocks.forEachIndexed { idx, (block, blankBetween) ->
            val gap = markdownBlockGap(renderedBlocks.getOrNull(idx - 1)?.first, block, blankBetween)
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    // Phase 8.5 Reparación: parseInline Almacenamiento en caché con recordar,Evite volver a analizar el formato en línea cada vez que reorganice.
                    // La clave contiene texto + todos los colores. + citationUrls,Cualquier cambio se volverá a analizar.
                    val annotated = remember(block.text, color, linkColor, codeBgColor, citationUrls, citationColor) {
                        parseInline(block.text, color, linkColor, codeBgColor, citationUrls, citationColor)
                    }
                    if (isStreaming || disableLinks) {
                        // v1.42: Utilice directamente la representación de texto en streaming,Evite que el pointerInput de LinkableText se reconstruya repetidamente a medida que cambia el contenido.
                        // v1.0.63: También se utiliza en modo de selección múltiple Text,Asegúrese de hacer clic en el cuerpo del mensaje para seleccionarlo.
                        Text(
                            text = annotated,
                            style = style,
                            color = color,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinkableText(
                            annotatedText = annotated,
                            style = style,
                            modifier = Modifier.fillMaxWidth(),
                            onLongPressOutside = onLongPressOutside,
                        )
                    }
                }

                is MarkdownBlock.Heading -> {
                    // v2.x: Capa extraíble transparente 24 / 20 / 17 escalera(Anteriormente h1 y h2 eran ambos 20sp,Colapso de jerarquía);
                    // Deje la parte superior e inferior en blanco para [markdownBlockGap](Grande arriba y pequeño abajo,El título está cerca del texto principal.).
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineSmall
                        3 -> MaterialTheme.typography.titleMedium
                        4 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    }
                    val annotated = remember(block.text, color, linkColor, codeBgColor, citationUrls, citationColor) {
                        parseInline(block.text, color, linkColor, codeBgColor, citationUrls, citationColor)
                    }
                    // v2.x: Obtener explícitamente el color del texto del tema(No publicado antes color,Confíe en LocalContentColor para llegar al fondo de las cosas)
                    val headingColor = MaterialTheme.colorScheme.onSurface
                    if (disableLinks) {
                        Text(
                            text = annotated,
                            style = headingStyle,
                            color = headingColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinkableText(
                            annotatedText = annotated,
                            style = headingStyle,
                            color = headingColor,
                            modifier = Modifier.fillMaxWidth(),
                            onLongPressOutside = onLongPressOutside,
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    val richLang = block.language?.lowercase()?.trim().orEmpty()
                    // v2.6.6: Representación progresiva de tarjeta generativa. —— También haga que la tarjeta svg/html/chart aparezca durante la transmisión,
                    // En lugar de cambiar a texto plano (IU inteligentes alineadas“Renderizar mientras se genera”).
                    // Cuando el contenido es demasiado corto, no se representará (para evitar que el HTML de media frase parpadee); el interior de la tarjeta se volverá a dibujar según la longitud.
                    val canProgressiveCard =
                        richLang in setOf("svg", "html", "chart") &&
                            block.code.length >= ProgressiveCard.MIN_CHARS
                    if (canProgressiveCard) {
                        RichContentCard(
                            language = richLang,
                            content = block.code,
                            onHtmlPreview = onHtmlPreview,
                            onCardAction = onCardAction,
                            cardId = cardId,
                            progressive = isStreaming,
                        )
                    } else if (isStreaming) {
                        // v1.97 (P2): Bloques de código que no son de tarjetas degradados a vista previa de solo texto durante la transmisión
                        StreamingCodePreview(block, style, codeBgColor)
                    } else {
                        // v0.48: Bloque de código de medios enriquecidos(svg/html/chart)Renderizar con RichContentCard,el resto se va CodeBlockView
                        when {
                            // ChartRenderer: mermaid / plantuml Usar renderizador independiente(WebView + Coil,Contiene alta adaptabilidad y respaldo ante fallos.)
                            isMermaidCodeBlock(block.language, block.code) -> MermaidBlock(code = block.code)
                            isPlantUmlCodeBlock(block.language, block.code) -> PlantUmlBlock(code = block.code)
                            richLang in setOf("svg", "html", "chart") ->
                                RichContentCard(
                                    language = richLang,
                                    content = block.code,
                                    onHtmlPreview = onHtmlPreview,
                                    onCardAction = onCardAction,
                                    cardId = cardId,
                                )
                            else -> CodeBlockView(block)
                        }
                    }
                }

                is MarkdownBlock.ListItem -> ListItemView(block, style, color, linkColor, codeBgColor, citationUrls, citationColor)

                is MarkdownBlock.Quote -> QuoteView(
                    block,
                    style,
                    color,
                    linkColor,
                    codeBgColor,
                    quoteTextColor,
                    citationUrls,
                    citationColor,
                )

                // Phase 8.6: LaTeX bloque de fórmulas(JLatexMath renderizado)
                // v1.97 (P2): Cambiar a texto sin formato durante la transmisión,Saltar renderizado JLatexMath(Retransmisión)
                is MarkdownBlock.Formula -> {
                    if (isStreaming) {
                        Text(
                            text = block.latex,
                            style = style.copy(fontFamily = MuseMonoFontFamily),
                            color = quoteTextColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        FormulaView(block.latex)
                    }
                }

                // v1.97: Markdown mesa
                // v1.97 (P2): Bajar a vista previa de solo texto durante la transmisión,Saltar diseño TableView
                is MarkdownBlock.Table -> {
                    if (isStreaming) {
                        val plain = buildString {
                            appendLine(block.headers.joinToString(" | "))
                            block.rows.forEach { appendLine(it.joinToString(" | ")) }
                        }
                        Text(
                            text = plain,
                            style = style.copy(fontFamily = MuseMonoFontFamily),
                            color = quoteTextColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        TableView(block)
                    }
                }

                MarkdownBlock.Divider -> {
                    // v2.x: Alinear el color y el ancho de la línea MuseDivider(Página de configuración del mismo estilo de línea de cabello),El ritmo de subida y bajada viene dado por la función de espaciado de bloques.
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
                        thickness = MusePaddings.dividerThickness,
                    )
                }

                // Los bloques de líneas vacías se han fusionado en la etapa renderedBlocks.(sólo contribuir"¿Hay una línea en blanco entre el bloque anterior y el bloque actual?"),
                // Las sucursales se reservan aquí sólo para satisfacer la exhaustividad del sellado cuando,En realidad, no se renderiza.
                MarkdownBlock.Blank -> Unit
            }
        }
    }
}

/**
 * v2.x Formato de texto largo: ¿Es"bloque estructural" —— Un bloque separado que contrasta con el texto principal.
 * (Código/Tabla/Referencia/Fórmula/Separador).
 */
private fun isStructuralBlock(block: MarkdownBlock): Boolean = when (block) {
    is MarkdownBlock.CodeBlock,
    is MarkdownBlock.Table,
    is MarkdownBlock.Quote,
    is MarkdownBlock.Formula,
    MarkdownBlock.Divider,
    -> true
    else -> false
}

/**
 * v2.x Formato de texto largo: Ritmo vertical entre bloques visibles adyacentes.(Dp).
 *
 * función pura,Facilita la cobertura de una sola prueba. Prioridad de regla(en→abajo):
 *  1. Deja el primer bloque en blanco;
 *  2. El bloque actual es el título. → [MuseMarkdownRhythm.headingSpaceAbove];
 *  3. El bloque anterior es el título. → [MuseMarkdownRhythm.headingSpaceBelow](Grande arriba y pequeño abajo,Título cerca del texto);
 *  4. elementos de lista adyacentes → [MuseMarkdownRhythm.listItemGap](Incluso si hay líneas en blanco entre el texto original, permanecerá compacto.);
 *  5. Cada lado es un bloque estructural. → [MuseMarkdownRhythm.blockGap];
 *  6. El texto original tiene líneas en blanco. → [MuseMarkdownRhythm.paragraphGap];
 *  7. Saltos de línea suaves en el mismo párrafo → 0.
 */
internal fun markdownBlockGap(prev: MarkdownBlock?, current: MarkdownBlock, blankBetween: Boolean): Dp = when {
    prev == null -> 0.dp
    current is MarkdownBlock.Heading -> MuseMarkdownRhythm.headingSpaceAbove
    prev is MarkdownBlock.Heading -> MuseMarkdownRhythm.headingSpaceBelow
    prev is MarkdownBlock.ListItem && current is MarkdownBlock.ListItem -> MuseMarkdownRhythm.listItemGap
    isStructuralBlock(prev) || isStructuralBlock(current) -> MuseMarkdownRhythm.blockGap
    blankBetween -> MuseMarkdownRhythm.paragraphGap
    else -> 0.dp
}

/**
 * v1.97 (P2): Vista previa ligera de bloques de código durante la transmisión — Texto puro monoespaciado + fondo oscuro de papel cálido,Sin número de línea/Copiar/doblar/Destacar.
 * Una vez finalizado el flujo, el [CodeBlockView] Hazte cargo del renderizado completo.
 */
@Composable
private fun StreamingCodePreview(block: MarkdownBlock.CodeBlock, style: TextStyle, codeBgColor: Color) {
    Surface(
        color = codeBgColor,
        // v2.x: Borrador de diseño de alineación de esquinas redondeadas de bloque de código"bloque de código=12dp"archivo(El 8dp original está al mismo nivel que el código en línea.,No hay suficiente bloqueo)
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = block.code,
            style = style.copy(fontFamily = MuseMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(MuseMarkdownRhythm.blockInner)
                .fillMaxWidth(),
        )
    }
}

/**
 * v1.97 (P2): La cantidad máxima de fragmentos de MarkdownText que se procesarán durante la transmisión.
 * Cuando se excede este valor, sólo el último [STREAMING_BLOCK_CAP] bloques,para arriba "…" Marcador de posición.
 * Opción 30: respuesta larga normal(Miles de palabras)Generalmente entre 10 y 25 yuanes,30 suficiente para cubrir;
 *        respuesta muy larga(Nivel de 10.000 palabras.)Aún puedes ver el contenido más reciente después del truncamiento.,El volumen total se restablece una vez finalizado el flujo.
 */
private const val STREAMING_BLOCK_CAP = 30

// La longitud mínima del código y el calibre del equipo de las tarjetas progresivas de transmisión están unificados por [ProgressiveCard] Proporcionado por:
// No representa la tarjeta cuando está por debajo de ella (para evitar que el HTML de media oración parpadee y se vuelva a dibujar), y cuando llega a ella, se representa gradualmente de acuerdo con el engranaje geométrico.

/**
 * Phase 8.3: Admite el formato en línea de texto al hacer clic en enlaces.
 *
 * - renderizado [parseInline] Salida AnnotatedString(Contiene anotaciones de etiquetas URL)
 * - Haga clic en el área del enlace → Aparece un cuadro de diálogo de confirmación secundario → Después de que el usuario confirma, Intent.ACTION_VIEW abre el enlace externo.
 * - No hay respuesta al hacer clic en el área normal(No interfieras con la lista./Tarjeta seleccionada)
 *
 * v1.97: URL Haga clic para agregar confirmación secundaria(MuseDialog),Evite abrir accidentalmente enlaces externos.
 *        El cuadro de diálogo proporciona"abierto"y"Copiar enlace"Dos operaciones.
 */
@Composable
private fun LinkableText(
    annotatedText: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    /** v1.0.72: Devolución de llamada del área sin vínculo con pulsación larga(Se utiliza el menú de pulsación larga de burbuja). */
    onLongPressOutside: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // v1.97: Por confirmar URL(null Indica que no hay ningún enlace por confirmar.)
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    // v1.97: ¿Está seleccionada esta sesión?"No más confirmación"(Mantenga presionado o verifique el botón Atrás true,Haga clic para abrir directamente en esta sesión)
    var skipConfirm by remember { mutableStateOf(false) }

    // v1.97: Abrir la lógica pública de la URL(Llamado después de la confirmación o cuando se omite la confirmación.)
    val openUrl: (String) -> Unit = { url ->
        resultOf {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onError { msg, _ ->
            MuseToast.show(context.getString(R.string.markdown_open_link_failed, msg))
        }
    }

    Text(
        text = annotatedText,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotatedText) {
            detectTapGestures(
                // v1.97: Mantenga pulsado para abrir directamente(Saltar confirmación,Se enciende automáticamente al mismo tiempo."Esta sesión ya no está confirmada.")
                onLongPress = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val pos = layout.getOffsetForPosition(offset)
                    val url = annotatedText.getStringAnnotations("URL", pos, pos)
                        .firstOrNull()?.item
                    if (url == null) {
                        // v1.0.72: Pulsación larga en el área sin enlace → Menú de pulsación larga transparente para hacer burbujas
                        onLongPressOutside?.invoke()
                        return@detectTapGestures
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        MuseToast.show(context.getString(R.string.markdown_only_http))
                        return@detectTapGestures
                    }
                    skipConfirm = true
                    openUrl(url)
                },
                // v1.97: haga clic — Si es seleccionado"No más confirmación"luego abre directamente,De lo contrario, aparece un cuadro de diálogo de confirmación.
                onTap = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val pos = layout.getOffsetForPosition(offset)
                    val url = annotatedText.getStringAnnotations("URL", pos, pos)
                        .firstOrNull()?.item ?: return@detectTapGestures
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        MuseToast.show(context.getString(R.string.markdown_only_http))
                        return@detectTapGestures
                    }
                    if (skipConfirm) {
                        openUrl(url)
                    } else {
                        pendingUrl = url
                    }
                },
            )
        },
    )

    // v1.97: URL Haga clic en el cuadro de diálogo de confirmación secundario.(Contiene"Esta sesión ya no está confirmada."Opciones)
    pendingUrl?.let { url ->
        io.zer0.muse.ui.common.feedback.MuseDialog(
            onDismissRequest = { pendingUrl = null },
            title = context.getString(R.string.markdown_open_link_confirm_title),
            content = {
                Column {
                    Text(context.getString(R.string.markdown_open_link_confirm_message))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = skipConfirm,
                            onCheckedChange = { skipConfirm = it },
                        )
                        Text(
                            text = context.getString(R.string.markdown_open_link_dont_ask_again),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmText = context.getString(R.string.markdown_open_link_confirm_open),
            onConfirm = {
                pendingUrl = null
                openUrl(url)
            },
            dismissText = context.getString(R.string.markdown_open_link_confirm_copy),
            onDismiss = {
                // v1.97: "Copiar enlace"botón — Copiar URL al portapapeles
                pendingUrl = null
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                MuseToast.show(context.getString(R.string.markdown_link_copied))
            },
        )
    }
}

/**
 * vista de bloque de código: Fuente monoespaciada + fondo oscuro de papel cálido + desplazamiento horizontal + números de línea + copiar + doblar.
 *
 * Phase 8.3 mejorar:
 *  - Número de línea: Alineación de igual ancho del canal izquierdo(Alinear números a la derecha)
 *  - botón copiar: Icono de ContentCopy en la esquina superior derecha,Toast "N líneas de código copiadas"
 *  - plegado automático: Los bloques de código de más de 30 líneas están contraídos de forma predeterminada(Mostrar las primeras 10 filas),abajo"Expandir N líneas"botón
 */
@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    val context = LocalContext.current
    val codeLines = remember(block.code) { block.code.split("\n") }
    val lineCount = codeLines.size
    val autoCollapseThreshold = 30
    val previewLineCount = 10
    // Estado plegado predeterminado: Si supera el umbral, se contraerá de forma predeterminada.;rememberSaveable Desplazamiento garantizado/Estado conservado después de la reorganización
    var expanded by rememberSaveable(block.code) {
        mutableStateOf(lineCount <= autoCollapseThreshold)
    }
    val displayedLines = if (expanded) codeLines else codeLines.take(previewLineCount)
    val hiddenCount = lineCount - previewLineCount

    // v2.x: Deje la parte superior e inferior del bloque de código en blanco. markdownBlockGap,Ya no está incluido en el bloque. Spacer;
    // esquinas redondeadas elevadas a MuseShapes.medium(12dp,Borrador de diseño"bloque de código"archivo),Los márgenes interiores caminan leyendo fichas de ritmo.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.medium,
        tonalElevation = MuseElevation.low,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MuseMarkdownRhythm.blockInner)) {
            // fila superior: language Etiqueta + botón copiar + número de filas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    block.language?.takeIf { it.isNotBlank() }?.let { lang ->
                        Text(
                            text = lang,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        text = stringResource(R.string.markdown_line_count, lineCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = {
                        // L6 Limitaciones conocidas: El código copiado no está marcado como confidencial.(como un bloque de código que contiene una clave),Escriba texto sin formato en el portapapeles de manera uniforme;
                        // La detección de contenido posterior se puede combinar para determinar si es sensible.,Aún no implementado(Gran carga de trabajo).
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", block.code))
                        MuseToast.show(context.getString(R.string.markdown_copied_lines, lineCount))
                    },
                    contentDescription = stringResource(R.string.markdown_copy_code_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    iconSize = 16.dp,
                )
            }
            Spacer(Modifier.height(MusePaddings.labelVerticalGap))
            // Contenido del código: Número de línea + código
            // Phase 10.5: Números de línea alineados a la derecha + ancho fijo(Calculado por dígitos totales de fila),1/10/100 Bellamente alineado
            // L-MD15 Reparación: gutter El ancho se calcula utilizando constantes a nivel de archivo.(Originalmente codificado en 10/16)
            val gutterWidth = (lineCount.toString().length * GUTTER_CHAR_WIDTH_DP).coerceAtLeast(GUTTER_MIN_WIDTH_DP).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                // Número de línea gutter(Alinear a la derecha,Las fuentes monoespaciadas garantizan la alineación de los números)
                Column(modifier = Modifier.width(gutterWidth).padding(end = 8.dp)) {
                    displayedLines.forEachIndexed { idx, _ ->
                        Text(
                            text = "${idx + 1}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MuseMonoFontFamily,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            ),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // Contenido del código
                // v1.48: Superponer máscara de degradado cuando está plegada,conformarse a"Doblar con máscara degradada"restricción
                Box {
                    Column {
                        // H-MD1 Reparación: usar key() identificar cada línea,evitar doblar/Al cambiar para expandir
                        // forEachIndexed La ubicación de memoria incorrecta provoca una falla en la caché de CodeHighlighter.
                        // Tome el contenido de la línea original como key(Excluidos los añadidos al plegar // … sufijo),
                        // Compose Resalte el caché que puede coincidir con la misma línea en todas las reorganizaciones.
                        displayedLines.forEachIndexed { idx, line ->
                            key(line, idx) {
                                val displayLine = if (!expanded && idx == previewLineCount - 1 && hiddenCount > 0) {
                                    // Al plegar, la última línea muestra un mensaje de puntos suspensivos
                                    "$line  // …"
                                } else {
                                    line
                                }
                                Text(
                                    text = CodeHighlighter.highlight(displayLine, block.language),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = MuseMonoFontFamily,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Superponga un degradado de transparente a color de fondo en la parte inferior cuando esté plegado,crear"Continuará"señales visuales
                    if (!expanded && hiddenCount > 0) {
                        val bgColor = MaterialTheme.colorScheme.surfaceVariant
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.5f to Color.Transparent,
                                        1.0f to bgColor,
                                    ),
                                ),
                        )
                    }
                }
            }
            // doblar/Botón expandir(Se muestra solo cuando se excede el umbral)
            if (lineCount > autoCollapseThreshold) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = Color.Transparent,
                    shape = MuseShapes.extraSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        // H-MD6 Reparación: clickable agregar role=Role.Button semántica de accesibilidad;
                        // heightIn(min=48.dp) Objetivo táctil garantizado≥48dp(Originalmente vertical=6.dp solo sobre 28dp)
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClick = { expanded = !expanded },
                        )
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                            contentDescription = if (expanded) {
                                stringResource(
                                    R.string.common_collapse,
                                )
                            } else {
                                stringResource(R.string.common_expand)
                            },
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = if (expanded) {
                                stringResource(
                                    R.string.common_collapse,
                                )
                            } else {
                                stringResource(R.string.markdown_collapse_remaining, hiddenCount)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** vista de elementos de lista: sangría + signo(• o 1.). */
@Composable
private fun ListItemView(
    block: MarkdownBlock.ListItem,
    baseStyle: TextStyle,
    baseColor: Color,
    linkColor: Color,
    codeBgColor: Color,
    citationUrls: List<String> = emptyList(),
    citationColor: Color = linkColor,
) {
    val marker = if (block.ordered) "${block.index}." else "•"
    // Phase 8.5 Reparación: parseInline Almacenamiento en caché con recordar,Mantenga la coherencia con la ruta del párrafo/título
    val annotated = remember(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor) {
        parseInline(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = marker,
            style = baseStyle,
            color = baseColor,
            modifier = Modifier.width(24.dp),
        )
        LinkableText(
            annotatedText = annotated,
            style = baseStyle,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Vista de bloque de cotización: Barra de color izquierda + fondo claro. */
@Composable
private fun QuoteView(
    block: MarkdownBlock.Quote,
    baseStyle: TextStyle,
    baseColor: Color,
    linkColor: Color,
    codeBgColor: Color,
    quoteTextColor: Color,
    citationUrls: List<String> = emptyList(),
    citationColor: Color = linkColor,
) {
    // Phase 8.5 Reparación: parseInline Almacenamiento en caché con recordar,Mantenga la coherencia con la ruta del párrafo/título
    val annotated = remember(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor) {
        parseInline(block.text, baseColor, linkColor, codeBgColor, citationUrls, citationColor)
    }
    // v2.x Formato de texto largo:
    //  - El color semántico cambió a neutro. outline —— primary si"ganado"color interactivo,Las citas son sólo evidencia circunstancial.,No deberías agarrar la marca verde;
    //  - La altura de la barra de colores cambia para seguir todo el texto.(fijo original 20dp,Sólo la primera línea de una cita de varias líneas tiene una barra vertical.);
    //  - Deje la parte superior e inferior en blanco para markdownBlockGap,En el interior sólo se conserva el estrecho espacio entre la barra de color y el texto.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline, MuseShapes.pill),
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        LinkableText(
            annotatedText = annotated,
            style = baseStyle.copy(fontStyle = FontStyle.Italic),
            color = quoteTextColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Análisis de formato en línea: Negrita **...** / cursiva *...* / código en línea `...` / enlace [...](...) /
 * Número de referencia [N](Phase 8.4 UrlCitation).
 *
 * El color lo pasa la persona que llama.(buildAnnotatedString interno no @Composable,No puedo leer MaterialTheme).
 * orden de análisis: enlace [text](url) > Número de referencia [N] > código en línea > Negrita > cursiva(Alternancia regular de una exploración).
 * Sintaxis anidada(Como por ejemplo **`code`**)No totalmente compatible,Se puede cubrir el uso común.
 *
 * Phase 8.4 UrlCitation: cuando [citationUrls] tiempo no vacío,en texto [N](N es un entero positivo)será
 * Representado como anotación en la que se puede hacer clic en color secundario,Haga clic para saltar a citationUrls[N-1]. Nota [text](url)
 * Los enlaces de rebajas estándar del formulario coinciden primero,Evite confundir los números en el texto del enlace con comillas.
 */
fun parseInline(
    text: String,
    baseColor: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    codeBgColor: Color = Color.Unspecified,
    citationUrls: List<String> = emptyList(),
    citationColor: Color = linkColor,
): AnnotatedString = buildAnnotatedString {
    // L2 Reparación: La expresión regular ha sido promovida al nivel de archivo. private val(INLINE_REGEX / CITATION_REGEX),Cotización directa aquí
    // Phase 8.4: caminar solo [N] escaneo de citas(después del regular principal),evitar con [text](url) conflicto
    var lastEnd = 0
    INLINE_REGEX.findAll(text).forEach { match ->
        // Muestra el texto sin formato antes del partido.(puede contener [N] cita o texto plano URL)
        if (match.range.first > lastEnd) {
            appendSegmentWithCitations(
                text.substring(lastEnd, match.range.first),
                citationUrls,
                citationColor,
                CITATION_REGEX,
                baseColor,
                linkColor,
            )
        }
        when {
            // enlace [text](url) — group 1=text, 2=url
            match.groupValues[1].isNotEmpty() -> {
                val linkText = match.groupValues[1]
                val url = match.groupValues[2]
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(linkText)
                }
                pop()
            }
            // código en línea `...` — group 3
            match.groupValues[3].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        fontFamily = MuseMonoFontFamily,
                        background = codeBgColor,
                    ),
                ) {
                    append(match.groupValues[3])
                }
            }
            // Negrita **...** — group 4
            match.groupValues[4].isNotEmpty() -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[4])
                }
            }
            // cursiva *...* — group 5
            match.groupValues[5].isNotEmpty() -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[5])
                }
            }
        }
        lastEnd = match.range.last + 1
    }
    // Imprima el texto restante al final.(puede contener [N] cita o texto plano URL)
    if (lastEnd < text.length) {
        appendSegmentWithCitations(
            text.substring(lastEnd),
            citationUrls,
            citationColor,
            CITATION_REGEX,
            baseColor,
            linkColor,
        )
    }
}

/**
 * Phase 8.4: en un fragmento de texto ordinario [N] Los números de referencia se representan como anotaciones de URL en las que se puede hacer clic.
 * Anotado solo si N está dentro del rango de citationUrls,Otros [N] Cuando se muestra como texto normal.
 *
 * v1.97: Agregar identificación automática de URL de texto sin formato(linkify enlace automático).
 * Las URL http/https en texto normal se representarán automáticamente como enlaces en los que se puede hacer clic con el color primario.
 * URL tener prioridad sobre [N] escaneo de citas,Evitar dentro de la URL [numeros] No coinciden.
 *
 * Phase 8.5 limpiar: Eliminar los parámetros mainRanges/segmentOffset no utilizados(Originalmente diseñado para prevenir
 * [N] Error de escaneo de referencia [text](url) interno,Pero citationRegex solo coincide con números puros [N],
 * con [text](url) de [texto no vacío](...) Las estructuras naturalmente no se superponen,No es necesaria esta protección).
 */
private fun AnnotatedString.Builder.appendSegmentWithCitations(
    segment: String,
    citationUrls: List<String>,
    citationColor: Color,
    citationRegex: Regex,
    baseColor: Color,
    linkColor: Color = citationColor,
) {
    if (segment.isEmpty()) {
        append(segment)
        return
    }
    // v1.97: Escanee primero el texto sin formato URL,Dividir el texto en segmentos de URL y segmentos normales
    var urlLast = 0
    URL_AUTOLINK_REGEX.findAll(segment).forEach { urlMatch ->
        // URL Texto normal antes de ir [N] escaneo de citas
        if (urlMatch.range.first > urlLast) {
            appendCitationOnly(
                segment.substring(urlLast, urlMatch.range.first),
                citationUrls,
                citationColor,
                citationRegex,
            )
        }
        // URL Se representa como un enlace de color primario en el que se puede hacer clic.
        val url = urlMatch.value
        pushStringAnnotation(tag = "URL", annotation = url)
        withStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            ),
        ) {
            append(url)
        }
        pop()
        urlLast = urlMatch.range.last + 1
    }
    // URL Después de que el texto restante vaya [N] escaneo de citas
    if (urlLast < segment.length) {
        appendCitationOnly(
            segment.substring(urlLast),
            citationUrls,
            citationColor,
            citationRegex,
        )
    }
}

/**
 * v1.97: Ponga un fragmento de texto plano en [N] Números de referencia representados como anotaciones de URL en las que se puede hacer clic(No incluye identificación automática de URL).
 * Extraído de appendSegmentWithCitations,Para reutilizar texto normal después de la fragmentación de URL.
 */
private fun AnnotatedString.Builder.appendCitationOnly(
    text: String,
    citationUrls: List<String>,
    citationColor: Color,
    citationRegex: Regex,
) {
    if (citationUrls.isEmpty() || text.isEmpty()) {
        append(text)
        return
    }
    var segLast = 0
    citationRegex.findAll(text).forEach { cm ->
        if (cm.range.first > segLast) {
            append(text.substring(segLast, cm.range.first))
        }
        val n = cm.groupValues[1].toIntOrNull() ?: 0
        val url = citationUrls.getOrNull(n - 1)
        if (url != null) {
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                SpanStyle(
                    color = citationColor,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(cm.value)
            }
            pop()
        } else {
            append(cm.value)
        }
        segLast = cm.range.last + 1
    }
    if (segLast < text.length) {
        append(text.substring(segLast))
    }
}

/**
 * v1.97: Markdown Representación de mesa.
 * Utilice un fondo semitransparente principal para la fila del encabezado,Las filas de datos se colorean usando onSurfaceVariant,Diseño de columnas de igual ancho.
 */
@Composable
private fun TableView(table: MarkdownBlock.Table) {
    val headerColor = MaterialTheme.colorScheme.onSurface
    val cellColor = MaterialTheme.colorScheme.onSurfaceVariant
    // v2.x: Alineación de líneas de fila Color de línea MuseDivider(onSurface@13%),Mismo idioma que la página de configuración.
    val rowLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val headerBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    // v2.x Formato de texto largo: Tabla como bloque de contenido independiente —— Contenedor redondeado + superficie poco profunda con el mismo origen que el bloque de código,
    // Que sea en una base biplano."autónomo",Contraste con el texto;Deje la parte superior e inferior de la tabla en blanco y use markdownBlockGap.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // fila de encabezado
        Row(modifier = Modifier.fillMaxWidth()) {
            table.headers.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = headerColor,
                    modifier = Modifier
                        .weight(1f)
                        .background(headerBg)
                        .padding(MuseMarkdownRhythm.tableCellHeader),
                    textAlign = TextAlign.Start,
                )
            }
        }
        HorizontalDivider(color = rowLineColor, thickness = MusePaddings.dividerThickness)
        // fila de datos
        table.rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall,
                        color = cellColor,
                        modifier = Modifier
                            .weight(1f)
                            .padding(MuseMarkdownRhythm.tableCellBody),
                        textAlign = TextAlign.Start,
                    )
                }
            }
            // Ya no hay una línea divisoria colgante debajo de la última línea.
            if (rowIndex < table.rows.lastIndex) {
                HorizontalDivider(color = rowLineColor.copy(alpha = 0.5f), thickness = MusePaddings.dividerThickness)
            }
        }
    }
}

/**
 * v1.0.53: Analice el campo de portada en un archivo local.
 *
 * reglas:
 *  - "covers/xxx.jpg" → filesDir/covers/xxx.jpg
 *  - "file:///path/xxx.jpg" → Analizar directamente la ruta
 *  - Otros → filesDir/xxx
 *  - inyectar [resolver] prioridad al usar(prueba/Almacenamiento personalizado)
 */
@Composable
private fun resolveCoverFile(cover: String, resolver: ((String) -> File?)?): File? {
    if (resolver != null) return resolver(cover)
    val context = LocalContext.current
    return when {
        cover.startsWith("covers/") -> File(File(context.filesDir, "covers"), cover.removePrefix("covers/"))
        cover.startsWith("file://") -> File(cover.removePrefix("file://"))
        else -> File(context.filesDir, cover)
    }
}
