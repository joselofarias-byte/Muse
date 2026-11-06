package io.zer0.muse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.animation.MuseInkGlyph
import io.zer0.muse.ui.common.animation.MuseThinkingGlyphSize
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay

/**
 * Tarjeta de Proceso: Una visión general de lo que se está haciendo.
 *
 * Está dividido en dos niveles, acorde con el ritmo de lectura de "haga clic para revelar detalles":
 *
 *  **Una capa (resumen, siempre mostrada)**
 *   - Una forma de tinta que se deforma (en progreso)/ Icono de estado hecho
 *   - Una frase para describir lo que estás haciendo actualmente ("Buscando…" / "3 pasos completados")
 *   - Recuento de pasos y tiempo transcurrido
 *   - Expandir flecha a la derecha
 *
 *  **Segundo piso (ampliado)**
 *   - Piezas de pensamiento para cada ronda (si las hay)
 *   - Los pasos de la herramienta se enumeran uno por uno: nombre de la herramienta, estado, tiempo de paso único y resumen de resultados.
 *   - Subpasos anidados (desglose en equipo/flujo de trabajo) con sangría de un nivel más
 *
 * La combinación de colores sigue el tema:
 *   - La base de la tarjeta utiliza un contenedor primario con baja transparencia, que es de la misma familia pero diferente del bloque MOOD.
 *   - Use primario para cronometrar y contar pasos, enfatizando "aún en movimiento".
 *   - El color del estado del paso solo distingue el éxito/falló/Tercera marcha en progreso, no se han introducido paletas de colores adicionales
 *
 * @param taskCard datos de pasos de herramientas;null Sólo se muestra la línea de resumen (esperando la primera fase del token, etc.)
 * @param progressText Redacción publicitaria consecutiva (análisis visual del progreso,OCR progreso, etc)
 * @param isActive Si todavía se está ejecutando determina si la animación manuscrita y el tiempo continuarán.
 * @param startedAt Marca de tiempo inicial, utilizada para cronometrar;null Calculado en base al tiempo de la primera combinación.
 */
@Composable
internal fun ProcessCard(
    taskCard: TaskCardData?,
    progressText: String? = null,
    isActive: Boolean = true,
    startedAt: Long? = null,
    modifier: Modifier = Modifier,
) {
    val steps = taskCard?.steps.orEmpty()
    val totalSteps = steps.size
    val doneSteps = steps.count { it.status.isTerminal }
    val runningStep = steps.firstOrNull { it.status == TaskStepStatus.RUNNING }

    // Estado expandido: colapsado de forma predeterminada durante la ejecución (no bloquea el texto) y no aparecerá automáticamente una vez finalizado.
    var expanded by remember(taskCard?.id) { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    // Diferenciar del MOOD:MOOD Utilice el contenedor primario y la tarjeta de proceso utilice el contenedor secundario.
    // Ambos están en el sistema de colores temáticos, pero a simple vista se puede ver que son bloques de diferente naturaleza.
    val containerColor = scheme.secondaryContainer.copy(alpha = 0.5f)
    val borderColor = scheme.secondary.copy(alpha = 0.2f)

    // Tiempo: aumenta cada segundo durante la ejecución y se congela cuando termina
    val elapsedMs = rememberElapsed(startedAt ?: taskCard?.createdAt, isActive)

    val summaryText = resolveSummaryText(progressText, runningStep, doneSteps, totalSteps, isActive)

    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor),
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Un nivel: resumen ──
            ProcessCardSummary(
                summary =
                ProcessSummary(
                    text = summaryText,
                    doneSteps = doneSteps,
                    totalSteps = totalSteps,
                    elapsedMs = elapsedMs,
                    isActive = isActive,
                    hasFailure = taskCard?.hasFailedSteps == true,
                    expanded = expanded,
                ),
                onToggle = { expanded = !expanded },
            )

            // Barra de progreso: solo se muestra cuando la ejecución está en progreso y hay pasos, y se cierra cuando se completa
            if (isActive && totalSteps > 0) {
                ProcessCardProgress(doneSteps = doneSteps, totalSteps = totalSteps)
            }

            // ── Segundo piso: subdivisión ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120)),
            ) {
                ProcessStepList(steps = steps)
            }
        }
    }
}

/** Copia de resumen: utilice primero el progreso externo, seguido del título del paso actual y, finalmente, el resumen del paso. */
private fun resolveSummaryText(progressText: String?, runningStep: TaskStep?, doneSteps: Int, totalSteps: Int, isActive: Boolean): String =
    when {
        progressText != null -> progressText
        runningStep != null ->
            runningStep.title.ifBlank { runningStep.progressText.orEmpty() }.ifBlank { "ejecutando" }
        totalSteps > 0 && doneSteps == totalSteps -> "Completado $totalSteps paso"
        totalSteps > 0 -> "ya realizado $doneSteps / $totalSteps paso"
        isActive -> "Pensando"
        else -> "Completado"
    }

/** Toda la información necesaria para la línea de resumen está agrupada en un solo paquete para evitar inflar la lista de parámetros. */
private data class ProcessSummary(
    val text: String,
    val doneSteps: Int,
    val totalSteps: Int,
    val elapsedMs: Long,
    val isActive: Boolean,
    val hasFailure: Boolean,
    val expanded: Boolean,
)

/** Un nivel: fila de resumen. */
@Composable
private fun ProcessCardSummary(summary: ProcessSummary, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(enabled = summary.totalSteps > 0, onClick = onToggle)
            // Estado plegado compacto: esta línea solo responde "¿A dónde fue?", Y la altura de una línea es suficiente
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (summary.isActive) {
            MuseInkGlyph(
                modifier = Modifier.size(MuseThinkingGlyphSize * 0.8f),
                animate = true,
            )
        } else {
            Icon(
                imageVector = if (summary.hasFailure) MuseIcons.x else MuseIcons.check,
                contentDescription = null,
                tint = if (summary.hasFailure) scheme.error else scheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.totalSteps > 0 || summary.elapsedMs > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (summary.totalSteps > 0) {
                        Text(
                            text = "${summary.doneSteps} / ${summary.totalSteps}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                    if (summary.elapsedMs > 0) {
                        Text(
                            text = formatElapsed(summary.elapsedMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline,
                        )
                    }
                }
            }
        }

        if (summary.totalSteps > 0) {
            Icon(
                imageVector = if (summary.expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

/** Barra de progreso delgada: solo se muestra durante la ejecución. */
@Composable
private fun ProcessCardProgress(doneSteps: Int, totalSteps: Int) {
    val scheme = MaterialTheme.colorScheme
    val progress by
        animateFloatAsState(
            targetValue = if (totalSteps == 0) 0f else doneSteps.toFloat() / totalSteps,
            animationSpec = tween(300),
            label = "process_progress",
        )
    LinearProgressIndicator(
        progress = { progress },
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp)),
        color = scheme.primary,
        trackColor = scheme.primary.copy(alpha = 0.12f),
    )
}

/** Segunda capa: contenedor de lista de pasos. */
@Composable
private fun ProcessStepList(steps: List<TaskStep>) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEach { step -> ProcessStepRow(step = step, depth = 0) }
    }
}

/**
 * Paso único: punto de estado + título + tiempo transcurrido + resumen de resultados; si hay subpasos, sangría un nivel.
 */
@Composable
private fun ProcessStepRow(step: TaskStep, depth: Int) {
    val scheme = MaterialTheme.colorScheme
    val statusColor =
        when (step.status) {
            TaskStepStatus.SUCCESS -> scheme.primary
            TaskStepStatus.FAILED, TaskStepStatus.TIMED_OUT -> scheme.error
            TaskStepStatus.CANCELLED -> scheme.outline
            TaskStepStatus.RUNNING -> scheme.primary
            TaskStepStatus.PENDING -> scheme.outline.copy(alpha = 0.5f)
        }

    Column(
        modifier = Modifier.padding(start = (depth * 12).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor),
            )
            Text(
                text = step.title.ifBlank { step.toolName.ifBlank { "pasos" } },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            step.durationMs?.let { ms ->
                Text(
                    text = formatElapsed(ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                )
            }
            if (step.status == TaskStepStatus.RUNNING) {
                Text(
                    text = "En progreso",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
        // Copia de progreso (RUNNING actualizado por devolución de llamada de herramienta)
        step.progressText?.takeIf { it.isNotBlank() }?.let { progress ->
            Text(
                text = progress,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        // Resumen de resultados (muestra las dos primeras líneas en estado final)
        if (step.status.isTerminal && step.result.isNotBlank()) {
            Text(
                text = step.result.lineSequence().take(2).joinToString(" ").take(120),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        // Profundice un nivel más en subpasos
        step.subSteps.forEach { sub -> ProcessStepRow(step = sub, depth = depth + 1) }
    }
}

/** Temporización: actualice cada 500 ms durante la ejecución y congele en el valor final una vez finalizada. */
@Composable
private fun rememberElapsed(startAt: Long?, isActive: Boolean): Long {
    if (startAt == null || startAt <= 0L) return 0L
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isActive, startAt) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    return (now - startAt).coerceAtLeast(0L)
}

/** milisegundos → Legible por humanos:<1s Mostrar 0.x s,<60s Muestra x s, en caso contrario m minutos s segundos. */
private fun formatElapsed(ms: Long): String = when {
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        "${minutes}m ${seconds}s"
    }
}
