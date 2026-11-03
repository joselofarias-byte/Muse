package io.zer0.muse.ui.devpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.MessageBubble
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Página de vista previa del período de desarrollo: use real [MessageBubble] Representa un mensaje con llamadas de MOOD/PENSAMIENTOS/HERRAMIENTA.
 *
 * El único propósito de la existencia: cuando no hay un proveedor de configuración ni una sesión real,
 * También puede ver el diseño real de las burbujas de mensajes en el simulador, que se utiliza para verificar y aceptar las secciones de MOOD.
 * Piense en líneas de resumen de tarjetas y otros cambios. No participa en la navegación formal y solo adb puede abrirlo directamente.
 *
 * Debe eliminarse junto con la ruta antes de conectarse.
 */
@Composable
fun DevPreviewScreen() {
    var moodExpanded by remember { mutableStateOf(true) }
    var reasoningExpanded by remember { mutableStateOf(true) }

    val prefs = remember {
        ChatPreferences(
            showMoodBlock = true,
            showReasoning = true,
            moodExpandedByDefault = true,
            reasoningExpandedByDefault = true,
            showTokenEstimate = true,
            showModelName = true,
        )
    }

    val sample = remember { sampleMessage() }

    val toolSample = remember { sampleToolMessage() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = MusePaddings.sectionGap),
    ) {
        Text(
            text = "MOOD / Vista previa de la tarjeta de pensamiento (para desarrollo)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp),
        )
        MessageBubble(
            msg = sample,
            isStreaming = false,
            isLastAssistant = false,
            isTranslating = false,
            onEdit = {},
            onQuote = {},
            onRegenerate = {},
            onTranslate = {},
            modelName = "muse-preview",
            chatPrefs = prefs,
            isMoodExpanded = moodExpanded,
            isReasoningExpanded = reasoningExpanded,
            onToggleMoodExpanded = { moodExpanded = !moodExpanded },
            onToggleReasoningExpanded = { reasoningExpanded = !reasoningExpanded },
        )
        MessageBubble(
            msg = toolSample,
            isStreaming = false,
            isLastAssistant = false,
            isTranslating = false,
            onEdit = {},
            onQuote = {},
            onRegenerate = {},
            onTranslate = {},
            modelName = "muse-preview",
            chatPrefs = prefs,
            isReasoningExpanded = false,
            onToggleReasoningExpanded = {},
        )
        // Animación del proceso: el mismo componente que el final del flujo de mensajes en la página de chat.
        io.zer0.muse.ui.ThinkingBubble(progressText = null)
    }
}

/** Muestra para vista previa: cuatro párrafos con MOOD + texto de reflexión. */
private fun sampleMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "Reorganicé la página de configuración en 11 grupos según la intención del usuario y aumenté el número de entradas de primer nivel de 34 a 48.\n\n" +
        "Arreglé fácilmente dos páginas de configuración a las que no podía acceder antes.",
    reasoning = "Primero confirme si el nivel existente y el número real de campos son consistentes." +
        "Se encontró que solo hay 7 grupos y 34 entradas visibles en el primer nivel, pero hay 275 campos sin cita previa." +
        "Explique que la jerarquía de la interfaz es una proyección de la estructura del módulo de código, no una proyección de la intención del usuario.",
    mood = SAMPLE_MOOD,
    modelId = "preview",
)

/** Muestra para vista previa: solo pensamientos + una llamada de herramienta para mostrar la línea de resumen del proceso. */
private fun sampleToolMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    content = "",
    reasoning = "Lea las agrupaciones de páginas de configuración existentes antes de decidir cómo fusionarlas.",
    modelId = "preview",
    toolCallInfo = io.zer0.ai.core.ToolCallInfo(
        toolName = "update_settings",
        arguments = "{\"group\":\"look\"}",
        result = "ok",
        isSuccess = true,
        durationMs = 1_200L,
    ),
)

private const val SAMPLE_MOOD = "Vibe: Después de esta revisión, la página de chat finalmente ya no parece un montón de tarjetas.\n" +
    "Sparks:\n" +
    "  - Después de desarmar las cuatro secciones de estado de ánimo,Sparks Sólo esos tres tienen lugares donde pararse.\n" +
    "  - Las tarjetas de pensamiento y las tarjetas de herramientas deben ser como una corriente continua, no dos bloques de construcción.\n" +
    "  - Usar \"Lo pensé por un tiempo\" en la línea de resumen es más humano que cortar las primeras 40 palabras.\n" +
    "Reflections:\n" +
    "  - El título plegable tiene operandos, por lo que los usuarios pueden ver de un vistazo cuánto trabajo se ha realizado en este paso.\n" +
    "  - Es correcto mantener la tarjeta del plan independiente. Es lo más importante a tener en cuenta a la hora de trabajar.\n" +
    "Will:\n" +
    "  - Quiero estabilizar este diseño.\n" +
    "  - No quiero agregar nuevas tarjetas"
