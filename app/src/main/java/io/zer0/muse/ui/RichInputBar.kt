package io.zer0.muse.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.chat.VideoAttachment

/**
 * v1.0.75 fix (Comentarios de los usuarios): Se ha eliminado toda la barra de herramientas de formato de Markdown sobre el campo de entrada.
 *
 * P2-12 Barra de herramientas de formato de texto enriquecido de Era(Negrita/cursiva/código/Cotización/lista/enlace)Después de múltiples rondas de evaluación
 * Se ha confirmado que no tiene valor de uso real.:Los usuarios nunca confían en él para formatear la entrada.,Y ocupa el espacio sobre el campo de entrada.
 * Usuario claro"esa fila no sirve para nada,borrarlo".
 *
 * RichInputBar degradar al original [InputBar] capa de transmisión transparente pura(Mantenga los sitios de llamadas compatibles,Se puede reemplazar en su totalidad más tarde. InputBar).
 * Relacionado eliminado:FormatToolbarSurface / FormatButton / LinkInsertDialog / applyMarkdownFormat /
 * MarkdownFormat / formatEnabled Interruptor de parámetro/configuración(richInputEnabled).
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun RichInputBar(
    text: String,
    assistantName: String = "Muse",
    isStreaming: Boolean,
    isWaitingFirstToken: Boolean = false,
    isDrawMode: Boolean,
    isWebSearchEnabled: Boolean,
    isDeepThinkingEnabled: Boolean = false,
    deepThinkingLevel: io.zer0.ai.core.ReasoningLevel = io.zer0.ai.core.ReasoningLevel.HIGH,
    onCycleDeepThinkingLevel: () -> Unit = {},
    imageGenParams: ImageGenParams = ImageGenParams(),
    onImageGenParamsChange: (ImageGenParams) -> Unit = {},
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInterject: () -> Unit = {},
    onEnqueuePending: () -> Unit = {},
    onNavigateInputHistory: (Int) -> Unit = {},
    onPickDocument: () -> Unit,
    onToggleDrawMode: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleDeepThinking: () -> Unit = {},
    showRestartContext: Boolean = false,
    onRestartContext: () -> Unit = {},
    assistants: List<AssistantEntity> = emptyList(),
    onDelegateToAssistant: () -> Unit = {},
    onPickKnowledge: () -> Unit = {},
    onOpenPromptTemplates: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    enterToSend: Boolean = false,
    quickMessages: List<QuickMessageEntity> = emptyList(),
    onInsertQuickMessage: (QuickMessageEntity) -> Unit = {},
    pendingImages: List<String> = emptyList(),
    onPickImage: (asOcr: Boolean) -> Unit = {},
    onPickGalleryImage: (Uri) -> Unit = {},
    onRemovePendingImage: (Int) -> Unit = {},
    pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = emptyList(),
    onRemovePendingDocument: (Int) -> Unit = {},
    pendingVideo: VideoAttachment? = null,
    onPickVideo: () -> Unit = {},
    onRemovePendingVideo: () -> Unit = {},
    replyingTo: UIMessage? = null,
    onClearReply: () -> Unit = {},
    replyQuoteOverride: String? = null,
    onEditReply: (String) -> Unit = {},
    isRecording: Boolean = false,
    asrStatus: ASRStatus = ASRStatus.Idle,
    asrErrorMessage: String? = null,
    recordingAmplitudes: List<Float> = emptyList(),
    onStartRecording: () -> Boolean = { false },
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    showMic: Boolean = true,
    toolCallCompleted: Int = 0,
    toolCallTotal: Int = 0,
    onShowToolCalls: () -> Unit = {},
    showExpandButton: Boolean = false,
    autoFocus: Boolean = true,
    tokenEstimateEnabled: Boolean = false,
    historyTokens: Int = 0,
    contextWindow: Int = 0,
    pasteAsFileEnabled: Boolean = true,
    pasteAsFileThreshold: Int = 2000,
    onAddPastedTextAsDocument: (String) -> Unit = {},
    /** v2.6: vidrio liquido — true Al entrar a la isla se habilita el cristal. */
    glassActive: Boolean = false,
    /** v2.5.0: Estilo de vidrio y configuración de cubículo. */
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
) {
    // v1.0.75 fix: Se ha eliminado la barra de herramientas de formato.,Transmite la barra de entrada original directamente.
    // Nota:InputBar sí mismo incluido imePadding/navigationBarsPadding,No se repetirá aquí.
    InputBar(
        state = MuseInputState(
            text = text,
            assistantName = assistantName,
            isStreaming = isStreaming,
            isWaitingFirstToken = isWaitingFirstToken,
            isDrawMode = isDrawMode,
            isWebSearchEnabled = isWebSearchEnabled,
            isDeepThinkingEnabled = isDeepThinkingEnabled,
            deepThinkingLevel = deepThinkingLevel,
            showExpandButton = showExpandButton,
            imageGenParams = imageGenParams,
            showRestartContext = showRestartContext,
            assistants = assistants,
            enterToSend = enterToSend,
            quickMessages = quickMessages,
            pendingImages = pendingImages,
            pendingDocuments = pendingDocuments,
            pendingVideo = pendingVideo,
            replyingTo = replyingTo,
            replyQuoteOverride = replyQuoteOverride,
            isRecording = isRecording,
            asrStatus = asrStatus,
            asrErrorMessage = asrErrorMessage,
            recordingAmplitudes = recordingAmplitudes,
            showMic = showMic,
            toolCallCompleted = toolCallCompleted,
            toolCallTotal = toolCallTotal,
            autoFocus = autoFocus,
            tokenEstimateEnabled = tokenEstimateEnabled,
            historyTokens = historyTokens,
            contextWindow = contextWindow,
            pasteAsFileEnabled = pasteAsFileEnabled,
            pasteAsFileThreshold = pasteAsFileThreshold,
            glassActive = glassActive,
            glassConfig = glassConfig,
        ),
        callbacks = InputBarCallbacks(
            onCycleDeepThinkingLevel = onCycleDeepThinkingLevel,
            onImageGenParamsChange = onImageGenParamsChange,
            onTextChanged = onTextChanged,
            onSend = onSend,
            onStop = onStop,
            onInterject = onInterject,
            onEnqueuePending = onEnqueuePending,
            onNavigateInputHistory = onNavigateInputHistory,
            onPickDocument = onPickDocument,
            onToggleDrawMode = onToggleDrawMode,
            onToggleWebSearch = onToggleWebSearch,
            onToggleDeepThinking = onToggleDeepThinking,
            onRestartContext = onRestartContext,
            onDelegateToAssistant = onDelegateToAssistant,
            onPickKnowledge = onPickKnowledge,
            onOpenPromptTemplates = onOpenPromptTemplates,
            onOpenSkills = onOpenSkills,
            onInsertQuickMessage = onInsertQuickMessage,
            onPickImage = onPickImage,
            onPickGalleryImage = onPickGalleryImage,
            onRemovePendingImage = onRemovePendingImage,
            onRemovePendingDocument = onRemovePendingDocument,
            onPickVideo = onPickVideo,
            onRemovePendingVideo = onRemovePendingVideo,
            onClearReply = onClearReply,
            onEditReply = onEditReply,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onShowToolCalls = onShowToolCalls,
            onAddPastedTextAsDocument = onAddPastedTextAsDocument,
        ),
    )
}
