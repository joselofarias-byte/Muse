package io.zer0.muse.ui.taskcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.55: Agent Tarjeta de planificación del flujo de trabajo.
 *
 * Cuando LLM llama a la herramienta task_plan para crear un plan estructurado,Representado como una lista de verificación rastreable en el flujo de mensajes.
 * Diferencias con TaskCard:
 *  - TaskCard Mostrar pasos de llamada a la herramienta(Cada paso es una llamada a una herramienta.,Seguimiento automático del estado de ejecución)
 *  - PlanCard Mostrar pasos para la planificación de LLM(Cada paso es una tarea descriptiva.,Estado actualizado proactivamente por LLM)
 */
@Composable
fun PlanCard(plan: AgentPlan, modifier: Modifier = Modifier) {
    // Los contenedores de planes vacíos no se representan cuando los datos históricos están dañados o falla el análisis de parámetros.
    if (plan.steps.isEmpty()) return

    // M-TC3 Reparación: remember Cambiar a rememberSaveable,Expandir/Estado colapsado tras el cambio de configuración/Retenido después de la recuperación del proceso
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        // CHAT-09: Estándares unificados para tarjetas dentro de mensajes. — Color base + trazo 1dp + esquinas redondeadas uniformes
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            // barra de título:Color degradado + título del plan + progreso
            // M-TC1 Reparación: texto/El color del icono cambió de Color codificado. Blanco a MaterialTheme.colorScheme.onPrimary,
            // Contraste garantizado bajo temas personalizados
            val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    )
                    .clip(RoundedCornerShape(topStart = MuseCornerRadius.SEMI_LARGE.dp, topEnd = MuseCornerRadius.SEMI_LARGE.dp))
                    // CHAT-06: Se puede hacer clic en toda la línea — Después de que la flecha se mueva a la fila del encabezado,La barra de título se puede plegar en cualquier posición./Expandir
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = onPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    // v2.0.1: Planifique con anticipación — El estado plegado predice el siguiente paso (alineación"planea hacer"visibilidad)
                    val nextStep = remember(plan.steps) {
                        plan.steps.firstOrNull { it.status == AgentPlanStepStatus.IN_PROGRESS }
                            ?: plan.steps.firstOrNull { it.status == AgentPlanStepStatus.PENDING }
                    }
                    val nextStepLabel =
                        if (nextStep != null) {
                            stringResource(R.string.plan_next_step_label, nextStep.title)
                        } else {
                            null
                        }
                    Text(
                        text = buildString {
                            append("${plan.completedSteps}/${plan.totalSteps} paso completado")
                            if (plan.failedSteps > 0) append(" · ${plan.failedSteps} falló")
                            if (nextStepLabel != null) append(" · " + nextStepLabel)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = onPrimaryColor.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!plan.isAllDone) {
                    MuseSpinner(
                        size = 20.dp,
                        color = onPrimaryColor,
                    )
                } else {
                    Icon(
                        imageVector = MuseIcons.check,
                        contentDescription = null,
                        tint = onPrimaryColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // CHAT-06: La flecha de colapso se movió a la fila del encabezado,48dp zona caliente;Ahora se puede hacer clic en toda la fila.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.action_collapse else R.string.action_expand,
                        ),
                        tint = onPrimaryColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // barra de progreso
            if (!plan.isAllDone && plan.progress > 0f) {
                MuseProgressBar(
                    progress = plan.progress,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            // Lista de pasos
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // L-TC6 Reparación: forEach agregar key,Cuando cambia el estado del paso, Compose puede reorganizar con precisión las filas correspondientes.
                    plan.steps.forEach { step ->
                        key(step.id) {
                            PlanStepRow(step = step)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanStepRow(step: AgentPlanStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // icono de estado
        val (icon, color) = when (step.status) {
            AgentPlanStepStatus.PENDING -> MuseIcons.circle to MaterialTheme.colorScheme.outline
            AgentPlanStepStatus.IN_PROGRESS -> null to MaterialTheme.colorScheme.primary
            AgentPlanStepStatus.DONE -> MuseIcons.check to MaterialTheme.colorScheme.primary
            AgentPlanStepStatus.FAILED -> MuseIcons.x to MaterialTheme.colorScheme.error
            AgentPlanStepStatus.SKIPPED -> MuseIcons.play to MaterialTheme.colorScheme.outline
            // Phase 3: Agregar estado final — Usar color de advertencia para el tiempo de espera,Cancelar el color debilitado.,Consistente con la semántica de TaskCard
            AgentPlanStepStatus.TIMED_OUT -> MuseIcons.alertTriangle to MaterialTheme.colorScheme.error
            AgentPlanStepStatus.CANCELLED -> MuseIcons.x to MaterialTheme.colorScheme.outline
        }

        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                MuseSpinner(
                    size = 12.dp,
                    strokeWidth = 1.5.dp,
                    color = color,
                )
            }
        }

        // M-TC4 Reparación: Originalmente Spacer(height(0.dp).padding(horizontal=6.dp)) esta mal,
        // height(0.dp) Deja que Spacer no tenga altura.,padding Tampoco funciona. Cambiar a width(6.dp) Hacer espaciado horizontal
        Spacer(Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (step.status == AgentPlanStepStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal,
                color = if (step.status == AgentPlanStepStatus.DONE) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.description.isNotBlank()) {
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.result.isNotBlank() && step.status == AgentPlanStepStatus.DONE) {
                Text(
                    text = step.result.take(200),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
