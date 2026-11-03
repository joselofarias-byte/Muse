package io.zer0.muse.ui.taskcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.subagent.SubagentSessionStore
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny
import org.koin.compose.koinInject

/**
 * v1.202: Tarjeta de lista de tareas de subagente en segundo plano.
 *
 * Muestra los subprocesos activos del subagente en segundo plano + el procesamiento pendiente en la sesión actual(PENDING)Tarea,
 * Informar a los usuarios sobre el trabajo en progreso de la delegación sin bloqueo,Y proporcione una entrada de cancelación.
 *
 * fuente de datos:
 *  - [activeThreads]: SubagentThreadStore Hilos en estado ACTIVO(Cada hilo corresponde a una sesión de agente secundario reanudable)
 *  - [pendingTasks]: DeferredResultStore Tareas en estado PENDIENTE(todavía no resolve/fail/abort)
 *
 * Generalmente existe una correspondencia uno a uno entre los dos.(Aplazar una tarea al mismo tiempo al registrar un hilo),Pero se muestra por separado para abordar escenarios de desacoplamiento.
 * (Por ejemplo, el hilo finalizó pero los resultados de la tarea no se han consumido.,O la tarea ha sido abortada pero el hilo no se ha cerrado).
 *
 * UI comportamiento:
 *  - Plegado predeterminado(Evite ocupar demasiado espacio visual),Haga clic en la fila del título para expandir/doblar
 *  - Una línea por tarea:icono de estado(girar en circulos=corriendo / ✓=Completo / ✗=falló)+ label/taskSummary + threadId(Truncar)+ Botón cancelar
 *  - lista vacía(Sin hilos ni tareas pendientes)El contenedor de la tarjeta no se representa cuando(Volver vacío)
 *
 * @param activeThreads Lista de hilos de agentes secundarios activos
 * @param pendingTasks Pendiente(PENDING)lista de tareas retrasadas
 * @param onCancel Cancelar devolución de llamada de tarea,Los parámetros son taskId
 * @param modifier Modificación externa
 */
@Composable
fun SubagentTaskListCard(
    activeThreads: List<SubagentThreadStore.ThreadEntry>,
    pendingTasks: List<DeferredResultStore.DeferredTask>,
    onCancel: (taskId: String) -> Unit,
    onCloseThread: (threadId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // No renderizar cuando la lista esté vacía(Evite el ruido sin sentido de la tarjeta vacía)
    if (activeThreads.isEmpty() && pendingTasks.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var selectedThread by remember { mutableStateOf<Pair<String, String>?>(null) }
    // número total de tareas:El mayor entre el número de hilos y el número de tareas pendientes(Ambos suelen estar de acuerdo,Elija un valor mayor para evitar escenarios de desacoplamiento)
    val totalCount = maxOf(activeThreads.size, pendingTasks.size)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            SubagentCardHeader(
                activeThreads = activeThreads,
                pendingTasks = pendingTasks,
                totalCount = totalCount,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )

            // ── estado expandido:lista de tareas ──
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Renderizar tareas pendientes(PENDING) —— Estas son tareas diferidas que aún no se han completado.,Mostrar progreso + botón cancelar
                    pendingTasks.forEach { task ->
                        SubagentTaskRow(
                            label = task.label,
                            summary = task.taskSummary,
                            threadId = task.threadId,
                            status = task.status,
                            taskId = task.taskId,
                            assistantId = activeThreads.firstOrNull { it.threadId == task.threadId }?.assistantId,
                            onCancel = onCancel,
                            onClick = { task.threadId?.let { tid -> selectedThread = tid to (activeThreads.firstOrNull { it.threadId == tid }?.assistantId ?: "") } },
                        )
                    }

                    // No hay ninguna tarea PENDIENTE correspondiente en el hilo de renderizado activo.(Teóricamente raro,mostrar todo)
                    // Filtrar hilos expuestos por coincidencia de taskId
                    OrphanThreadRows(
                        activeThreads = activeThreads,
                        pendingTasks = pendingTasks,
                        onSelect = { thread -> selectedThread = thread.threadId to thread.assistantId },
                        onCloseThread = onCloseThread,
                    )
                    // Nota:anterior "pendingTasks y activeThreads están vacíos" Se han eliminado las ramas vacías. —
                    // Cuando ambas listas al comienzo de la función están vacías, directamente return,Esta sucursal nunca es accesible.(código muerto).
                }
            }
        }
    }

    selectedThread?.let { (threadId, assistantId) ->
        SubagentTaskDetailSheet(
            threadId = threadId,
            assistantId = assistantId,
            onDismiss = { selectedThread = null },
        )
    }
}

@Composable
internal fun SubagentTaskDetailSheet(threadId: String, assistantId: String, onDismiss: () -> Unit) {
    val sessionStore: SubagentSessionStore = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistant = assistants.firstOrNull { it.id == assistantId }
    var messages by remember(threadId) { mutableStateOf<List<io.zer0.ai.core.UIMessage>>(emptyList()) }
    var loading by remember(threadId) { mutableStateOf(true) }

    LaunchedEffect(threadId) {
        loading = true
        messages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { sessionStore.load(threadId, maxContextTokens = 200_000) }.getOrDefault(emptyList())
        }
        loading = false
    }

    io.zer0.muse.ui.common.form.MuseBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding)) {
                if (assistant != null) {
                    AssistantAvatar(assistant = assistant, avatarSize = 40.dp)
                } else {
                    Icon(
                        imageVector = MuseIcons.robot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Column {
                    Text(
                        text = assistant?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.subagent_task_list_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "thread: ${threadId.take(16)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.subagent_task_detail_count, messages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
                    ) {
                        MuseSpinner(
                            size = 18.dp,
                        )
                        Text(stringResource(R.string.subagent_task_detail_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                messages.isEmpty() -> {
                    Text(
                        stringResource(R.string.subagent_task_detail_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    messages.forEach { msg ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                            color = if (msg.role == io.zer0.ai.core.MessageRole.USER) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = 1.dp,
                        ) {
                            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                                Text(
                                    text = if (msg.role == io.zer0.ai.core.MessageRole.USER) {
                                        stringResource(
                                            R.string.subagent_task_detail_user,
                                        )
                                    } else {
                                        stringResource(R.string.subagent_task_detail_assistant)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = msg.content.ifBlank { "(…)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * única línea de tarea diferida:icono de estado + label/summary + threadId(Truncar)+ Botón Cancelar.
 *
 * mapeo estatal(con [StatusIcon] ser consistente):
 *  - PENDING → punto de pulso(corriendo),Mostrar botón cancelar
 *  - RESOLVED → ✓ Icono de estado hecho(Las tareas completadas ya no muestran animaciones en ejecución)
 *  - FAILED → ✗ rojo
 *  - ABORTED → ✗ gris(considerado cancelado,No mostrar el botón cancelar)
 */
@Composable
private fun SubagentTaskRow(
    label: String?,
    summary: String,
    threadId: String?,
    status: DeferredResultStore.TaskStatus,
    taskId: String,
    assistantId: String? = null,
    onCancel: (taskId: String) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        // Avatar asistente (cuando esté disponible); de lo contrario, muestra el icono de estado
        if (assistantId != null) {
            val assistantRepository: AssistantRepository = koinInject()
            val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
            val assistant = assistants.firstOrNull { it.id == assistantId }
            if (assistant != null) {
                AssistantAvatar(assistant = assistant, avatarSize = 24.dp)
            } else {
                StatusIcon(status = status)
            }
        } else {
            StatusIcon(status = status)
        }

        // label + summary
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val displayLabel = label?.takeIf { it.isNotBlank() }
                ?: threadId?.take(8)
                ?: taskId.take(8)
            Text(
                text = displayLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!threadId.isNullOrBlank()) {
                Text(
                    text = "thread: ${threadId.take(12)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Botón cancelar:Sólo se puede cancelar el estado PENDIENTE(Aquellos que se han resuelto/fallado/anulado ya no se muestran.)
        if (status == DeferredResultStore.TaskStatus.PENDING) {
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = { onCancel(taskId) },
                contentDescription = stringResource(R.string.subagent_task_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 48.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/**
 * Línea de título de la tarjeta: Avatar del asistente (o ícono de bot)+ Título + recuento de tareas + ícono de expandir.
 *
 * El propósito de convertirlo en una función separada es mantener el elemento componible principal en una longitud que se pueda leer de un vistazo. ——
 * Hay tres piezas de lógica de recuperación de números no relacionadas en esta línea. Mezclarlos cubrirá la línea principal real.
 */
@Composable
private fun SubagentCardHeader(
    activeThreads: List<SubagentThreadStore.ThreadEntry>,
    pendingTasks: List<DeferredResultStore.DeferredTask>,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        val assistantRepository: AssistantRepository = koinInject()
        val allAssistants by
            assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
        val assistantById = remember(allAssistants) { allAssistants.associateBy { it.id } }
        val firstAssistantId = activeThreads.firstOrNull()?.assistantId
            ?: pendingTasks.firstNotNullOfOrNull { task ->
                activeThreads.firstOrNull { it.threadId == task.threadId }?.assistantId
            }
        val headerAssistant = firstAssistantId?.let { assistantById[it] }
        if (headerAssistant != null) {
            AssistantAvatar(assistant = headerAssistant, avatarSize = 24.dp)
        } else {
            Icon(
                imageVector = MuseIcons.robot,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(R.string.subagent_task_list_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = MuseShapes.tiny,
        ) {
            Text(
                text = stringResource(R.string.subagent_task_list_count, totalCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Icon(
            imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Representa filas que tienen subprocesos activos pero no tienen tareas pendientes.
 *
 * Este tipo de hilo normalmente ha terminado de ejecutarse pero no ha finalizado. En implementaciones anteriores solo tenían devoluciones de llamada de clic,
 * No hay entrada cerrada, por lo que siempre estará colgada en la tarjeta de tareas. —— Extraerlo en una función separada,
 * Por cierto, deja que el elemento componible principal vuelva a la longitud legible.
 */
@Composable
private fun OrphanThreadRows(
    activeThreads: List<SubagentThreadStore.ThreadEntry>,
    pendingTasks: List<DeferredResultStore.DeferredTask>,
    onSelect: (SubagentThreadStore.ThreadEntry) -> Unit,
    onCloseThread: (threadId: String) -> Unit,
) {
    val coveredThreadIds = pendingTasks.mapNotNull { it.threadId }.toSet()
    activeThreads
        .filter { it.threadId !in coveredThreadIds }
        .forEach { thread ->
            SubagentThreadRow(
                threadId = thread.threadId,
                assistantId = thread.assistantId,
                onClick = { onSelect(thread) },
                onClose = { onCloseThread(thread.threadId) },
            )
        }
}

/**
 * Ten todo en cuenta:Cuando hay un hilo activo pero no hay una tarea PENDIENTE correspondiente, solo se muestra la información básica del hilo.
 * Esta situación es teóricamente rara.(Las tareas y los hilos generalmente 1:1), sólo para visualización.
 */
@Composable
private fun SubagentThreadRow(
    threadId: String,
    assistantId: String,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistant = assistants.firstOrNull { it.id == assistantId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        if (assistant != null) {
            AssistantAvatar(assistant = assistant, avatarSize = 24.dp)
        } else {
            MuseSpinner(
                size = 14.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = assistant?.name?.takeIf { it.isNotBlank() } ?: "assistant: ${assistantId.take(12)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "thread: ${threadId.take(12)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // No existe una tarea correspondiente para este tipo de fila y no se aplica la semántica de cancelación. —— El mensaje es "terminar este hilo".
        // Sin él, los hilos que hayan terminado de ejecutarse pero que no finalicen automáticamente permanecerán colgados en la tarjeta.
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.x,
                    contentDescription = "Cerrar subtarea",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * icono de estado:
 *  - PENDING → girar en circulos(corriendo)
 *  - RESOLVED → ✓ verde(Completado)
 *  - FAILED → ✗ rojo
 *  - ABORTED → ✗ gris(Cancelado)
 */
@Composable
private fun StatusIcon(status: DeferredResultStore.TaskStatus) {
    when (status) {
        DeferredResultStore.TaskStatus.PENDING -> {
            // corriendo:Puntos de pulso(Visualmente consistente con el estado RUNNING de DelegationChainCard)
            val alpha = if (MuseMotion.isReducedMotion()) {
                0.65f
            } else {
                val transition = rememberInfiniteTransition(label = "subagentPulse")
                val animatedAlpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "subagentPulseAlpha",
                )
                animatedAlpha
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
        DeferredResultStore.TaskStatus.RESOLVED -> {
            Icon(
                imageVector = MuseIcons.circleCheck,
                contentDescription = stringResource(R.string.subagent_task_completed),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
        DeferredResultStore.TaskStatus.FAILED -> {
            Icon(
                imageVector = MuseIcons.alertCircle,
                contentDescription = stringResource(R.string.subagent_task_failed),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        DeferredResultStore.TaskStatus.ABORTED -> {
            Icon(
                imageVector = MuseIcons.x,
                contentDescription = stringResource(R.string.subagent_task_cancel),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
