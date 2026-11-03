package io.zer0.muse.ui.taskcard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.formatToolDuration
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * Filas de pasos individuales para tarjetas de tareas.
 *
 * v2.6.8 de [TaskCard.kt] sacar —— Una razón es que la cantidad de funciones @Composable en el archivo excede
 * detekt TooManyFunctions; La razón más práctica es que esta sección y la "carcasa de la tarjeta de misión" son dos cosas diferentes:
 * etapa responsable del caparazón/Progreso/Ampliar, la línea de pasos es responsable de "lo que se hizo en este paso y lo que se produjo".
 *
 * Los resultados del paso se dividen en dos caminos: "proceso" y "entregable" (ver [TaskStepResult]).
 */

/** un solo paso(Contiene ícono de estado + título + detalles + resultados + tiempo empleado + botón de reintento). */
@Composable
internal fun TaskStepRow(
    step: TaskStep,
    onRetry: () -> Unit,
    onTogglePause: () -> Unit = {},
    /** v2.6.8: La tarjeta de identidad de la tarjeta de producto. —— difundir la noticia id(Igual que la tarjeta única en línea). */
    cardId: String? = null,
    /** v2.6.8: El puente de devolución de la tarjeta del producto;null La tarjeta sólo se representa sin interacción. */
    onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        StepStatusIcon(status = step.status)
        // Contenido del paso
        Column(modifier = Modifier.weight(1f)) {
            StepTitleRow(step = step)
            StepDetailAndProgress(step = step)
            // v1.200: resultado(Ampliable/cerrar) + Profundizar en subpasos
            if (step.result.isNotBlank() && step.status != TaskStepStatus.PENDING) {
                Spacer(Modifier.height(2.dp))
                TaskStepResult(
                    step = step,
                    cardId = cardId,
                    onCardAction = onCardAction,
                )
            }
            // v1.200: carril secundario
            if (step.subSteps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SubStepLane(subSteps = step.subSteps)
            }
            StepFooter(step = step, onRetry = onRetry)
        }
        // Permite pausar sólo herramientas que aún no se han iniciado;Los efectos secundarios durante la operación no son mortales.
        if (step.status == TaskStepStatus.PENDING) {
            MuseTactileButton(
                icon = if (step.pauseRequested) MuseIcons.play else MuseIcons.pause,
                onClick = onTogglePause,
                contentDescription = stringResource(
                    if (step.pauseRequested) R.string.task_card_resume_step else R.string.task_card_pause_step,
                ),
                tint = if (step.pauseRequested) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                size = 32.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/** La longitud de la vista previa ampliada del resultado del paso (estado contraído). */
private const val RESULT_PREVIEW_LENGTH = 120

/** v2.6.8: Título del paso + insignia de fuente (de [TaskStepRow] extraído). */
@Composable
private fun StepTitleRow(step: TaskStep) {
    // v1.200: Título + Insignia de fuente
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = step.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        if (step.source.isNotBlank()) {
            Text(
                text = step.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** v2.6.8: Detalles del paso + texto del progreso de la ejecución (de [TaskStepRow] extraído). */
@Composable
private fun StepDetailAndProgress(step: TaskStep) {
    if (step.detail.isNotBlank()) {
        Text(
            text = step.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
    // v0.49: Texto de progreso de ejecución de herramienta(RUNNING Estado actualizado por devolución de llamada SkillExecutor.onProgress)
    if (step.status == TaskStepStatus.RUNNING && !step.progressText.isNullOrBlank()) {
        Text(
            text = step.progressText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
    }
}

/** v2.6.8: Tiempo de paso realizado + botón de reintento (desde [TaskStepRow] extraído). */
@Composable
private fun StepFooter(step: TaskStep, onRetry: () -> Unit) {
    // Consume mucho tiempo(Mostrar después de finalizar)
    step.durationMs?.let { dur ->
        if (dur > 0) {
            Text(
                text = stringResource(R.string.task_card_step_duration, formatToolDuration(dur)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // botón de reintento(FAILED / TIMED_OUT pasos)
    if (step.status.isRetryable) {
        MuseTactileButton(
            icon = MuseIcons.refresh,
            onClick = onRetry,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** v2.6.8: Columna de icono de estado de un solo paso (desde [TaskStepRow] Retire, baje las ramas anidadas de la fila principal). */
@Composable
private fun StepStatusIcon(status: TaskStepStatus) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .padding(top = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            TaskStepStatus.PENDING -> Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            )
            TaskStepStatus.RUNNING ->
                // v2.6.12: Utilice una forma de tinta unificada (hoja) en todas las aplicaciones durante la operación.→estrella→anillo),
                // No más giros: la misma señal "Calculando" tiene la misma forma en la burbuja de espera y en la barra de herramientas.
                io.zer0.muse.ui.common.animation.MuseInkGlyph(
                    modifier = Modifier.size(16.dp),
                )
            TaskStepStatus.SUCCESS -> Icon(
                MuseIcons.check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            TaskStepStatus.FAILED -> Icon(
                MuseIcons.alertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            TaskStepStatus.CANCELLED -> Icon(
                MuseIcons.x,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            TaskStepStatus.TIMED_OUT -> Icon(
                MuseIcons.timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** v2.6.8: carril secundario (desde [TaskStepRow] Extraer, reducir la profundidad de anidación de la fila principal). */
@Composable
private fun SubStepLane(subSteps: List<TaskStep>) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            subSteps.forEach { subStep ->
                key(subStep.id) {
                    TaskStepRow(step = subStep, onRetry = {})
                }
            }
        }
    }
}

/**
 * Área de resultados del paso —— Aquí es donde radica la línea divisoria entre “proceso” y “entregables”.
 *
 * Se representan tres tipos de resultados por separado:
 *  - Entregables (show_card): No lo dibujes aquí, solo deja una línea de referencia. —— El cuerpo de la tarjeta se utiliza en el flujo de mensajes como
 *    Representación de tarjetas en línea de primera clase, los usuarios pueden ver y operar sin expandir el plan de ejecución;
 *  - Otros productos (fotos/Archivo/render_data): renderiza el producto directamente sin cortarlo en texto;
 *  - Resultados de texto normal: truncar vista previa + expandir/Guardar.
 *
 * v2.6.8 de [TaskStepRow] Extracción: Primero, rectificación LongMethod/CyclomaticComplexMethod,
 * En segundo lugar, este párrafo tiene originalmente un significado semántico independiente (“¿Qué produce este paso?”), no tiene nada que ver con los iconos de estado y los botones de reintento.
 */
@Composable
private fun TaskStepResult(step: TaskStep, cardId: String?, onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)?) {
    // v2.6.7: El resultado del paso es el producto.(tarjeta/fotos/Archivo)Al renderizar el producto directamente,
    // No más ser cortado en 120 palabras de texto plano y enterrado dentro —— Si el usuario no puede verlo significa que no se ha generado.
    val stepOutput = remember(step.toolName, step.result, step.rawArgs) {
        io.zer0.muse.ui.resolveDirectOutput(step.toolName, step.rawArgs, step.result)
    }
    when {
        stepOutput == null -> StepResultText(step = step)
        // v2.6.8: Entregables(show_card)No pintado aquí.
        //
        // La tarjeta es lo que el usuario quiere en sí, no el proceso de "ajustar una herramienta"; presiónelo en el plegable
        // En este paso, el usuario primero debe expandir el plan de ejecución para ver la tarjeta que acaba de generar. —— Y esta en el mismo
        // en el flujo de noticias. Por lo tanto, la tarjeta en sí se representa como una tarjeta en línea de primera clase en el flujo de mensajes (consulte ChatScreen /
        // MessageBubble ruta), dejando sólo una línea de referencia en la tarjeta de tarea.
        io.zer0.muse.ui.isInlineDeliverableTool(step.toolName) -> GeneratedCardReference()
        else -> StepOutputCard(
            output = stepOutput,
            cardId = cardId,
            onCardAction = onCardAction,
        )
    }
}

/** v2.6.8: La línea de referencia \"Tarjeta generada\" en la tarjeta de tarea —— La tarjeta en sí está en el flujo de mensajes. */
@Composable
private fun GeneratedCardReference() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        Icon(
            imageVector = MuseIcons.sparkle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.task_card_card_generated),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** v2.6.8: Tarjeta de producto paso (imagen/Archivo/tarjeta no entregable). */
@Composable
private fun StepOutputCard(
    output: io.zer0.muse.ui.DirectOutput,
    cardId: String?,
    onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
) {
    // El producto es el cuerpo principal que los usuarios pueden ver.,No debe tener sangría en la columna del icono de estado de la fila de pasos.
    // Utilice el diseño para expandir el ancho de la columna del icono hacia la izquierda,Alinee la tarjeta horizontalmente con el borde izquierdo de la tarjeta de tarea exterior.;
    // No puedo usar negativo padding —— Compose Se lanzará una excepción directamente.
    io.zer0.muse.ui.DirectToolOutputInline(
        output = output,
        // v2.6.8: Mensaje de identidad de la tarjeta id(La identificación de la tarjeta de tarea es el mensaje del asistente para montarla. id),
        // ya no se usa toolCallId —— Las identidades de las dos rutas de tarjetas deben estar en el mismo espacio, de lo contrario
        // La tarjeta no puede leer sus propios datos y aparecen mensajes de error al guardar artefactos.
        cardId = cardId,
        onCardAction = onCardAction?.let { handler ->
            { action -> handler(cardId.orEmpty(), action) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val extra = STEP_ICON_COLUMN_WIDTH.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + extra,
                        maxWidth = constraints.maxWidth + extra,
                    ),
                )
                layout(placeable.width, placeable.height) {
                    placeable.place(-extra, 0)
                }
            }
            .padding(top = 4.dp, bottom = 4.dp),
    )
}

/** v2.6.8: Resultados de texto normal (vista previa truncada + expandido/retraer). */
@Composable
private fun StepResultText(step: TaskStep) {
    var isResultExpanded by remember { mutableStateOf(false) }
    val showExpand = step.result.length > RESULT_PREVIEW_LENGTH
    val displayResult = if (isResultExpanded) step.result else step.result.take(RESULT_PREVIEW_LENGTH)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "→ $displayResult",
            style = MaterialTheme.typography.bodySmall,
            color = if (step.status == TaskStepStatus.FAILED ||
                step.status == TaskStepStatus.TIMED_OUT
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
            // LAYOUT-01: Row El texto interior se amplía + líneas limitadas para evitar el ajuste de línea palabra por palabra cuando se amplía el tamaño de fuente;
            // La versión plegada es una vista previa.(Límite de 2 líneas), el estado expandido conserva el resultado completo sin truncamiento
            maxLines = if (isResultExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (showExpand) {
            Text(
                text = stringResource(
                    if (isResultExpanded) R.string.action_collapse else R.string.action_expand,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable { isResultExpanded = !isResultExpanded },
            )
        }
    }
}
