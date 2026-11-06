package io.zer0.muse.ui

import android.net.Uri
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.ui.chat.PendingDocument
import io.zer0.muse.ui.chat.VideoAttachment

/**
 * B7-07: InputBar Agregación de estado de entrada.
 *
 * Coloque los "datos" en los más de 50 parámetros de mosaico originales/switch" converge en un objeto de estado inmutable,
 * Las devoluciones de llamada se realizan de manera uniforme [InputBarCallbacks],El cuadro de entrada requiere solo dos parámetros.
 */
data class MuseInputState(
    val text: String = "",
    val assistantName: String = "Muse",
    val isStreaming: Boolean = false,
    val isWaitingFirstToken: Boolean = false,
    val isDrawMode: Boolean = false,
    val isWebSearchEnabled: Boolean = false,
    val isDeepThinkingEnabled: Boolean = false,
    val deepThinkingLevel: ReasoningLevel = ReasoningLevel.HIGH,
    val showExpandButton: Boolean = false,
    val imageGenParams: ImageGenParams = ImageGenParams(),
    val showRestartContext: Boolean = false,
    val assistants: List<AssistantEntity> = emptyList(),
    val enterToSend: Boolean = false,
    val quickMessages: List<QuickMessageEntity> = emptyList(),
    val pendingImages: List<String> = emptyList(),
    val pendingDocuments: List<PendingDocument> = emptyList(),
    val pendingVideo: VideoAttachment? = null,
    val replyingTo: UIMessage? = null,
    val replyQuoteOverride: String? = null,
    val isRecording: Boolean = false,
    val asrStatus: ASRStatus = ASRStatus.Idle,
    val asrErrorMessage: String? = null,
    val recordingAmplitudes: List<Float> = emptyList(),
    val showMic: Boolean = true,
    val toolCallCompleted: Int = 0,
    val toolCallTotal: Int = 0,
    val autoFocus: Boolean = true,
    val tokenEstimateEnabled: Boolean = false,
    val historyTokens: Int = 0,
    val contextWindow: Int = 0,
    val pasteAsFileEnabled: Boolean = true,
    val pasteAsFileThreshold: Int = 2000,
    /** v2.6: efecto de vidrio líquido — true Cuando la isla de entrada está habilitada con vidrio(Vidrio real o vidrio falso degradado). */
    val glassActive: Boolean = false,
    /** v2.5.0: Estilo de vidrio y configuración de cubículo. */
    val glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
)

/** B7-07: InputBar Todas las devoluciones de llamada convergen. */
data class InputBarCallbacks(
    val onCycleDeepThinkingLevel: () -> Unit = {},
    val onImageGenParamsChange: (ImageGenParams) -> Unit = {},
    val onTextChanged: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** A8: Cuando la entrada en streaming no está vacía,El botón de enviar cambia a"interponer"(Interrumpir la compilación actual y redirigir). */
    val onInterject: () -> Unit = {},
    /** B2: Agregue la entrada actual a la cola para enviarla durante la transmisión(No interrumpir la generación). */
    val onEnqueuePending: () -> Unit = {},
    val onNavigateInputHistory: (Int) -> Unit = {},
    val onPickDocument: () -> Unit = {},
    val onToggleDrawMode: () -> Unit = {},
    val onToggleWebSearch: () -> Unit = {},
    val onToggleDeepThinking: () -> Unit = {},
    val onRestartContext: () -> Unit = {},
    val onDelegateToAssistant: () -> Unit = {},
    val onPickKnowledge: () -> Unit = {},
    val onOpenPromptTemplates: () -> Unit = {},
    val onOpenSkills: () -> Unit = {},
    val onInsertQuickMessage: (QuickMessageEntity) -> Unit = {},
    val onPickImage: (Boolean) -> Unit = {},
    val onPickGalleryImage: (Uri) -> Unit = {},
    val onRemovePendingImage: (Int) -> Unit = {},
    val onRemovePendingDocument: (Int) -> Unit = {},
    val onPickVideo: () -> Unit = {},
    val onRemovePendingVideo: () -> Unit = {},
    val onClearReply: () -> Unit = {},
    val onEditReply: (String) -> Unit = {},
    val onStartRecording: () -> Boolean = { false },
    val onStopRecording: () -> Unit = {},
    val onCancelRecording: () -> Unit = {},
    val onShowToolCalls: () -> Unit = {},
    val onAddPastedTextAsDocument: (String) -> Unit = {},
)
