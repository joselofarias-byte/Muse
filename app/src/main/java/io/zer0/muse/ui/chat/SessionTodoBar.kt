package io.zer0.muse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.tools.TodoTool
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.0.92: Lista de tareas pendientes de la conversación — Muestra el progreso de descomposición de tareas mantenido por la IA a través de todo_write.
 *
 * Anteriormente la lista solo existía en la memoria.(TodoTool.sessionTodos),Los usuarios no pueden ver el progreso de desmontaje y ejecución de la IA;
 * Mostrar encima del campo de entrada cuando hay contenido"Tareas por hacer · Completo/total",Haga clic para ampliar la lista completa.
 * No ocupa espacio cuando no hay una lista de tareas pendientes.
 */
@Composable
fun SessionTodoBar(sessionId: String, modifier: Modifier = Modifier) {
    if (sessionId.isBlank()) return
    val todoList by remember(sessionId) { TodoTool.observeTodos(sessionId) }
        .collectAsStateWithLifecycle(initialValue = TodoTool.TodoList())
    val todos = todoList.todos
    if (todos.isEmpty()) return

    var showSheet by remember { mutableStateOf(false) }
    val doneCount = todos.count { it.status == "completed" }

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSheet = true }
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.chat_task_todo_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$doneCount/${todos.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = MuseIcons.chevronUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (showSheet) {
        MuseBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                text = stringResource(R.string.chat_task_todo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MusePaddings.itemGap))
            todos.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    val completed = item.status == "completed"
                    val inProgress = item.status == "in_progress"
                    Icon(
                        imageVector = if (completed) MuseIcons.check else MuseIcons.circle,
                        contentDescription = null,
                        tint = if (completed || inProgress) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (inProgress && item.activeForm.isNotBlank()) item.activeForm else item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (completed) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
