package io.zer0.muse.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * v1.0.31: La página de traducción ha sido reescrita con un nuevo diseño.
 *
 * Principales cambios:
 *  - Centrar el título de la barra superior
 *  - El selector de idioma se cambia a dos grandes cápsulas a la izquierda y a la derecha.,Etiqueta pequeña + nombre de idioma grande,botón de intercambio central
 *  - Tarjeta de entrada de texto original:verde"idioma de origen"Etiqueta + recuento de palabras + área de entrada redondeada + barra de operación inferior
 *  - Tarjeta de resultados de traducción:verde"idioma de destino"etiqueta + texto de traducción + copiar/leer en voz alta/Intercambio/Enviar a conversación
 *  - Historial de traducción:Fila de título con icono de reloj y botón de borrar,Los elementos de registro se utilizan según lo diseñado."Originales/traducir"Línea doble + chip de flujo de idioma + hora + estrella favorita
 *  - Mantenga traducciones por lotes, estilos personalizados, glosarios,OCR Capacidades como fotografía y traducción.,almacenado en"Más"en el menú
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(onBack: () -> Unit, onSendToNewChat: (String) -> Unit = {}, viewModel: TranslateViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocrManager: io.zer0.muse.doc.OcrManager = koinInject()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showCustomStyleDialog by remember { mutableStateOf(false) }
    var showGlossaryDialog by remember { mutableStateOf(false) }
    var ocrRecognizing by remember { mutableStateOf(false) }
    // §11-6: Historial de traducción“Ver solo colecciones”Filtrar. Usar recordarGuardar, rotar/Corta el fondo y vuelve sin perderlo.
    var showFavoritesOnly by rememberSaveable { mutableStateOf(false) }

    // mensaje de error → Toast Consejos(Consumo único,Evite los bombardeos repetidos)
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            MuseToast.show(context.getString(R.string.translate_page_error_failed, msg))
            viewModel.consumeError()
        }
    }

    // OCR Selección de álbum
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrRecognizing = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ocrManager.recognize(uri, context) }
                    .getOrElse {
                        MuseToast.show(context.getString(R.string.translate_page_ocr_failed))
                        ""
                    }
            }
            ocrRecognizing = false
            when {
                text.isBlank() -> MuseToast.show(context.getString(R.string.translate_page_ocr_empty))
                else -> {
                    viewModel.applyOcrText(text)
                    MuseToast.show(context.getString(R.string.translate_page_ocr_applied))
                }
            }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = { TranslateTopBar(onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // ── fuente/Barra de selección de idioma de destino ──
            LanguageSelectorBar(
                sourceLanguage = state.sourceLanguage,
                targetLanguage = state.targetLanguage,
                onSourceChange = { viewModel.updateSourceLanguage(it) },
                onTargetChange = { viewModel.updateTargetLanguage(it) },
                onSwap = { viewModel.swapLanguages() },
                enabled = !state.translating,
            )

            // ── Área de entrada de texto original ──
            SourceInputCard(
                text = state.inputText,
                translating = state.translating,
                onTextChange = { viewModel.updateInput(it) },
                onPaste = {
                    val clipText = readClipboardText(context)
                    if (viewModel.paste(clipText)) {
                        MuseToast.show(context.getString(R.string.translate_page_pasted))
                    } else {
                        MuseToast.show(context.getString(R.string.translate_page_clipboard_empty))
                    }
                },
                onClear = { viewModel.clear() },
                onCopy = {
                    if (state.inputText.isNotBlank()) {
                        copyToClipboard(context, state.inputText)
                        MuseToast.show(context.getString(R.string.translate_page_copied))
                    }
                },
                onSpeak = {
                    if (!viewModel.speakSource()) {
                        MuseToast.show(context.getString(R.string.translate_page_tts_not_ready))
                    }
                },
                onTranslate = { viewModel.translate() },
                onOcr = { imagePicker.launch("image/*") },
                ocrRecognizing = ocrRecognizing,
                onBatch = { showBatchDialog = true },
                onGlossary = { showGlossaryDialog = true },
                onStyle = { showStyleDialog = true },
                // v2.6.7: deja de leer —— stopSpeaking Antes no había ninguna llamada y no podía dejar de leer un artículo extenso.
                onStopSpeaking = { viewModel.stopSpeaking() },
            )

            // ── Área de resultados de traducción ──
            TranslationResultCard(
                translatedText = state.translatedText,
                translating = state.translating,
                onCopy = {
                    if (state.translatedText.isNotBlank()) {
                        copyToClipboard(context, state.translatedText)
                        MuseToast.show(context.getString(R.string.translate_page_copied))
                    }
                },
                onSpeak = {
                    if (!viewModel.speakTranslated()) {
                        MuseToast.show(context.getString(R.string.translate_page_tts_not_ready))
                    }
                },
                onUseAsInput = { viewModel.swapResultToInput() },
                onSendToNewChat = {
                    if (state.translatedText.isNotBlank()) {
                        onSendToNewChat(state.translatedText)
                    }
                },
            )

            // ── Área de historial de traducción ──
            // §11-6: El filtrado de la colección se realiza localmente y state.history() no se modifica.“Todos”Aún tienes que poder regresar),
            // Mismo tipo que favoritosSolo en Momentos.
            TranslateHistorySection(
                history = TranslateViewModel.historyForDisplay(
                    history = state.history,
                    favorites = state.favorites,
                    favoritesOnly = showFavoritesOnly,
                ),
                // El interruptor determina si se mostrará en función del historial completo (aún puedes volver a cambiar a todo cuando la colección esté vacía)
                totalHistoryCount = state.history.size,
                favoritesOnly = showFavoritesOnly,
                onFavoritesOnlyChange = { showFavoritesOnly = it },
                onItemClick = { item ->
                    viewModel.loadHistoryItem(item)
                    MuseToast.show(context.getString(R.string.translate_page_history_loaded))
                },
                onClearClick = { showClearHistoryDialog = true },
                onToggleFavorite = { item ->
                    viewModel.toggleFavorite(item)
                    MuseToast.show(
                        context.getString(
                            if (item.favorite) {
                                R.string.translate_page_favorite_removed
                            } else {
                                R.string.translate_page_favorite_added
                            },
                        ),
                    )
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // Ventana emergente de confirmación secundaria de borrar historial
    if (showClearHistoryDialog) {
        MuseDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = stringResource(R.string.translate_page_history_clear_confirm),
            content = {
                Text(
                    text = stringResource(R.string.translate_page_history_clear_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.translate_page_history_clear),
            onConfirm = {
                viewModel.clearHistory()
                showClearHistoryDialog = false
                MuseToast.show(context.getString(R.string.translate_page_history_cleared))
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearHistoryDialog = false },
            destructive = true,
        )
    }

    // Ventana emergente de selección de estilo de traducción
    if (showStyleDialog) {
        StylePickerDialog(
            currentStyle = state.translationStyle,
            customStyles = state.customStyles,
            onDismiss = { showStyleDialog = false },
            onSelect = { style ->
                viewModel.updateTranslationStyle(style)
                showStyleDialog = false
            },
            onManageCustomStyles = {
                showStyleDialog = false
                showCustomStyleDialog = true
            },
        )
    }

    // Cuadro de diálogo de traducción por lotes
    if (showBatchDialog) {
        BatchTranslateDialog(
            translating = state.batchTranslating,
            results = state.batchResults,
            targetLanguage = state.targetLanguage,
            onDismiss = {
                viewModel.cancelBatchTranslation()
                viewModel.clearBatchResults()
                showBatchDialog = false
            },
            onTranslate = { texts ->
                viewModel.translateBatch(texts, state.targetLanguage)
            },
            onCopyResults = { results ->
                val merged = results.joinToString("\n\n") { it.translated }
                copyToClipboard(context, merged)
                MuseToast.show(context.getString(R.string.translate_page_copied))
            },
        )
    }

    // Cuadro de diálogo de gestión de estilos personalizados
    if (showCustomStyleDialog) {
        CustomStyleDialog(
            customStyles = state.customStyles,
            onDismiss = { showCustomStyleDialog = false },
            onAdd = { name, prompt ->
                if (name.isBlank()) {
                    MuseToast.show(context.getString(R.string.translate_page_custom_style_empty_name))
                } else {
                    viewModel.addCustomStyle(name, prompt)
                    MuseToast.show(context.getString(R.string.translate_page_custom_style_added))
                }
            },
            onRemove = { name ->
                if (viewModel.removeCustomStyle(name)) {
                    MuseToast.show(context.getString(R.string.translate_page_custom_style_removed))
                }
            },
        )
    }

    // Diálogo de gestión del glosario
    if (showGlossaryDialog) {
        GlossaryDialog(
            glossary = state.glossary,
            onDismiss = { showGlossaryDialog = false },
            onAdd = { original, translated ->
                viewModel.addGlossaryEntry(original, translated)
                MuseToast.show(context.getString(R.string.translate_page_glossary_added))
            },
            onRemove = { original ->
                if (viewModel.removeGlossaryEntry(original)) {
                    MuseToast.show(context.getString(R.string.translate_page_glossary_removed))
                }
            },
        )
    }
}

/**
 * Traducir barra superior — Título grande centrado + retorno.
 */
@Composable
private fun TranslateTopBar(onBack: () -> Unit) {
    MuseTopBar(
        title = stringResource(R.string.translate_page_title),
        onBack = onBack,
        largeTitle = true,
    )
}

/**
 * Barra de selección de idioma superior — Dos cápsulas grandes a izquierda y derecha.,Botón de intercambio en el medio.
 */
@Composable
private fun LanguageSelectorBar(
    sourceLanguage: String,
    targetLanguage: String,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSwap: () -> Unit,
    enabled: Boolean,
) {
    var showSourcePicker by rememberSaveable { mutableStateOf(false) }
    var showTargetPicker by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        LanguageSelectorButton(
            label = stringResource(R.string.translate_page_source_language),
            value = sourceLanguage,
            onClick = { if (enabled) showSourcePicker = true },
            modifier = Modifier.weight(1f),
        )

        Surface(
            onClick = onSwap,
            enabled = enabled && sourceLanguage != TranslateViewModel.SOURCE_AUTO,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = MuseIcons.swapHorizontal,
                    contentDescription = stringResource(R.string.translate_page_swap),
                    tint = if (enabled && sourceLanguage != TranslateViewModel.SOURCE_AUTO) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
        }

        LanguageSelectorButton(
            label = stringResource(R.string.translate_page_target_language),
            value = targetLanguage,
            onClick = { if (enabled) showTargetPicker = true },
            modifier = Modifier.weight(1f),
        )
    }

    if (showSourcePicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.translate_page_source_language),
            selected = sourceLanguage,
            options = TranslateViewModel.SOURCE_LANGUAGES,
            onSelected = {
                onSourceChange(it)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    if (showTargetPicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.translate_page_target_language),
            selected = targetLanguage,
            options = TranslateViewModel.TARGET_LANGUAGES,
            onSelected = {
                onTargetChange(it)
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false },
        )
    }
}

/**
 * Botón de cápsula de selección de idioma — Etiqueta pequeña + nombre de idioma grande + flecha desplegable.
 */
@Composable
private fun LanguageSelectorButton(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * Ventana emergente de selección de idioma — Reutilice MuseDialog para mantener el estilo general.
 */
@Composable
private fun LanguagePickerDialog(
    title: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            // LAYOUT-01: El nombre de la opción es el ancho restante + límite de línea.(El nombre del idioma puede ser más largo.),
                            // Evite que el ícono de verificación a la derecha lo apriete en una columna.
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = MuseIcons.check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

/**
 * Tarjeta de entrada de texto original — Muestre la etiqueta del idioma de origen, el recuento de palabras, el área de entrada y la barra de operación inferior de acuerdo con el dibujo de diseño.
 *
 * Traducción por lotes, glosario, estilo de traducción,OCR Espere a que las habilidades avanzadas se almacenen en"Más"en el menú.
 */
@Composable
private fun SourceInputCard(
    text: String,
    translating: Boolean,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onOcr: () -> Unit,
    ocrRecognizing: Boolean,
    onBatch: () -> Unit,
    onGlossary: () -> Unit,
    onStyle: () -> Unit,
    /** v2.6.7: Deje de leer (pasado desde la capa exterior, este componente no puede obtener el modelo de vista). */
    onStopSpeaking: () -> Unit = {},
) {
    var expandedMore by remember { mutableStateOf(false) }

    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // Línea de título: etiqueta del idioma de origen + recuento de palabras
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // LAYOUT-01: El título toma el ancho restante + límite de línea.,La anotación de recuento de palabras es un texto breve de ancho fijo.,Límite de 1 línea.
                Text(
                    text = stringResource(R.string.translate_page_source_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${text.length}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }

            // Cuadro de entrada
            MuseTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.translate_page_input_placeholder)) },
                minLines = 4,
                maxLines = 10,
                enabled = !translating,
            )

            // OCR Aviso de reconocimiento
            AnimatedVisibility(
                visible = ocrRecognizing,
                enter = MuseMotion.fadeEnter(),
                exit = MuseMotion.fadeExit(),
            ) {
                Column {
                    MuseIndeterminateProgressBar(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.translate_page_ocr_recognizing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Barra de herramientas inferior + botón de traducción principal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionIconButton(
                        icon = MuseIcons.clipboard,
                        contentDescription = stringResource(R.string.translate_page_paste),
                        onClick = onPaste,
                        enabled = !translating,
                    )
                    ActionIconButton(
                        icon = MuseIcons.x,
                        contentDescription = stringResource(R.string.translate_page_clear),
                        onClick = onClear,
                        enabled = !translating && text.isNotEmpty(),
                    )
                    ActionIconButton(
                        icon = MuseIcons.volume,
                        contentDescription = stringResource(R.string.translate_page_speak_source),
                        onClick = onSpeak,
                        enabled = !translating && text.isNotBlank(),
                    )
                    ActionIconButton(
                        icon = MuseIcons.camera,
                        contentDescription = stringResource(R.string.translate_page_ocr),
                        onClick = onOcr,
                        enabled = !translating && !ocrRecognizing,
                    )
                    Box {
                        ActionIconButton(
                            icon = MuseIcons.moreVertical,
                            contentDescription = stringResource(R.string.translate_page_more),
                            onClick = { expandedMore = true },
                            enabled = !translating,
                        )
                        TranslateMoreMenu(
                            expanded = expandedMore,
                            onDismiss = { expandedMore = false },
                            // v2.6.7: Deja de leer (stopSpeaking llamada nula antes)
                            onStopSpeaking = {
                                expandedMore = false
                                onStopSpeaking()
                            },
                            onBatch = {
                                expandedMore = false
                                onBatch()
                            },
                            onGlossary = {
                                expandedMore = false
                                onGlossary()
                            },
                            onStyle = {
                                expandedMore = false
                                onStyle()
                            },
                        )
                    }
                }

                TranslateButton(
                    onClick = onTranslate,
                    enabled = !translating && text.isNotBlank(),
                )
            }
        }
    }
}

/**
 * "Más"menú desplegable — Traducción por lotes, glosario, estilo de traducción.
 */
@Composable
private fun TranslateMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onBatch: () -> Unit,
    onGlossary: () -> Unit,
    onStyle: () -> Unit,
    /** v2.6.7: Detener la lectura en curso. */
    onStopSpeaking: (() -> Unit)? = null,
) {
    MuseAnchoredMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        MuseListItem(
            onClick = onStyle,
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.languages,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            headlineContent = { Text(stringResource(R.string.translate_page_style_label)) },
        )
        MuseListItem(
            onClick = onBatch,
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.calculator,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            headlineContent = { Text(stringResource(R.string.translate_page_batch_translate)) },
        )
        MuseListItem(
            onClick = onGlossary,
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.bookOpen,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            headlineContent = { Text(stringResource(R.string.translate_page_glossary)) },
        )
        // v2.6.7: deja de leer —— stopSpeaking No recibí ninguna llamada antes y no podía dejar de leer un artículo extenso.
        if (onStopSpeaking != null) {
            MuseListItem(
                onClick = onStopSpeaking,
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.stop,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                },
                headlineContent = { Text(stringResource(R.string.translate_page_stop_speaking)) },
            )
        }
    }
}

/**
 * Botón principal de traducción — Botón de color principal de la cápsula.
 */
@Composable
private fun TranslateButton(onClick: () -> Unit, enabled: Boolean) {
    MuseCapsuleButton(
        text = stringResource(R.string.translate_page_translate),
        onClick = onClick,
        enabled = enabled,
        leadingIcon = MuseIcons.languages,
        fillWidth = false,
    )
}

/**
 * Tarjeta de resultados de traducción — Muestre etiquetas, traducciones y barras de acción del idioma de destino según lo diseñado.
 */
@Composable
private fun TranslationResultCard(
    translatedText: String,
    translating: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onUseAsInput: () -> Unit,
    onSendToNewChat: () -> Unit,
) {
    AnimatedVisibility(
        visible = translatedText.isNotBlank() || translating,
        enter = MuseMotion.fadeEnter(),
        exit = MuseMotion.fadeExit(),
    ) {
        Surface(
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.translate_page_target_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                if (translating) {
                    MuseIndeterminateProgressBar(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                SelectionContainer {
                    Text(
                        text = translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                        ActionIconButton(
                            icon = MuseIcons.copy,
                            contentDescription = stringResource(R.string.translate_page_copy),
                            onClick = onCopy,
                            enabled = !translating && translatedText.isNotBlank(),
                        )
                        ActionIconButton(
                            icon = MuseIcons.volume,
                            contentDescription = stringResource(R.string.translate_page_speak_result),
                            onClick = onSpeak,
                            enabled = !translating && translatedText.isNotBlank(),
                        )
                        ActionIconButton(
                            icon = MuseIcons.swapHorizontal,
                            contentDescription = stringResource(R.string.translate_page_use_as_input),
                            onClick = onUseAsInput,
                            enabled = !translating && translatedText.isNotBlank(),
                        )
                    }

                    SendToChatButton(
                        onClick = onSendToNewChat,
                        enabled = !translating && translatedText.isNotBlank(),
                    )
                }
            }
        }
    }
}

/**
 * "Enviar a conversación" botón — forma de cápsula,El color principal está resaltado.
 */
@Composable
private fun SendToChatButton(onClick: () -> Unit, enabled: Boolean) {
    Surface(
        shape = MuseShapes.pill,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.outline
        },
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = MuseIcons.send,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.translate_page_send_to_chat_session),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Botón de icono de operación unificada — El tamaño pequeño, sin borde y el estado deshabilitado están atenuados.
 */
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    MuseTactileButton(
        icon = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            // v1.0.28 Reparación: outline.copy(alpha=0.5f) Demasiado cerca del fondo,
            // Parecen cajas vacías con un tema claro. Utilice onSurfaceVariant en su lugar y mantenga 0.5f alpha,
            // Ambos expresan el estado de discapacidad.,No hará que los usuarios piensen erróneamente que es un marcador de posición inútil.
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        enabled = enabled,
        size = MuseIconSizes.touchTarget,
        iconSize = MuseIconSizes.iconMedium,
    )
}

// ── Función auxiliar del portapapeles ──

/** Leer texto sin formato desde el portapapeles del sistema(puede estar vacío). */
private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** Escribe texto en el portapapeles del sistema.v2.4.6: Se cambió a interno para su reutilización por elementos del historial de traducción. */
internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
}
