@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.groupchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.chipOutline
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField

/**
 * Cree un nuevo cuadro de diálogo de chat grupal.
 *
 * contiene:
 *  - Cuadro de entrada del nombre del chat grupal
 *  - Selección múltiple de miembros. chips(Cargar desde la lista de ayuda)
 *  - Equipo asociado opcional(de SettingsRepository.multiAgentConfigCache.teams Cargar)
 *
 * No usado ModalBottomSheet(Conocido por estar estancado bug),Utilice MuseDialog en su lugar.
 *
 * @param assistants Lista de todos los asistentes(Candidato a miembro)
 * @param teams Todos los equipos colaborativos(Candidatos de equipo relacionados)
 * @param onDismiss Cerrar diálogo
 * @param onConfirm Confirmar devolución de llamada de creación(name, memberIds, teamId)
 */
@Composable
fun CreateGroupChatDialog(
    assistants: List<AssistantEntity>,
    teams: List<AgentTeam>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, memberIds: List<String>, teamId: String?, initialMode: String?, initialLength: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedMemberIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedTeamId by rememberSaveable { mutableStateOf<String?>(null) }
    // v1.77: Validación de entrada — El error se muestra después del primer envío.
    var showErrors by rememberSaveable { mutableStateOf(false) }
    // v2.x: plantilla rápida — Haga clic para completar automáticamente el nombre y los parámetros de discusión preestablecidos.(Aplicar después de la creación)
    var selectedTemplateKey by rememberSaveable { mutableStateOf<String?>(null) }
    var templateMode by rememberSaveable { mutableStateOf<String?>(null) }
    var templateLength by rememberSaveable { mutableStateOf<String?>(null) }
    // v2.3.1: Reutilice directamente recursos de redacción de plantillas con nombres preestablecidos(labelRes),No más codificación china ——
    // Este valor se utiliza como redacción de chip y como nombre de chat grupal precargado.,Ambas ubicaciones comparten la misma traducción en 7 idiomas.
    // Cuando solo hay un miembro candidato, no se puede establecer la plantilla multipersonal (debate/lluvia de ideas/revisión):
    // Estos modos presuponen que varias partes se turnan para hablar, y un solo miembro equivale a hablar solo.
    val templatePresets = if (assistants.size <= 1) {
        listOf(
            TemplatePreset("review", R.string.groupchat_template_review, "host", "standard"),
        )
    } else {
        listOf(
            TemplatePreset("review", R.string.groupchat_template_review, "host", "standard"),
            TemplatePreset("debate", R.string.groupchat_template_debate, "debate", "standard"),
            TemplatePreset("brainstorm", R.string.groupchat_template_brainstorm, "auto", "brief"),
            TemplatePreset("retro", R.string.groupchat_template_retro, "round_robin", "detailed"),
        )
    }

    val maxNameLength = 30
    val nameError = showErrors && name.isBlank()
    val memberError = showErrors && selectedMemberIds.isEmpty()

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.groupchat_create_title),
        content = {
            // Cuadro de entrada del nombre del chat grupal
            MuseTextField(
                value = name,
                onValueChange = { newName ->
                    showErrors = false
                    name = newName.take(maxNameLength)
                },
                label = { Text(stringResource(R.string.groupchat_name_label)) },
                singleLine = true,
                // Bloquear explícitamente 1 fila:singleLine Sólo afecta el comportamiento de entrada,
                // Sin limitar la altura mínima, el cuadro de entrada se ampliará para parecerse a un campo de texto de varias líneas.
                minLines = 1,
                maxLines = 1,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.groupchat_name_required), color = MaterialTheme.colorScheme.error) }
                } else {
                    { Text("${name.length}/$maxNameLength") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // v2.x: Línea de plantilla rápida(Haga clic para completar automáticamente el nombre y los parámetros de discusión.)
            Text(
                text = stringResource(R.string.groupchat_template_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(templatePresets, key = { it.key }) { preset ->
                    // v2.3.1: Obtenga la traducción primero,otra vez onClick(no Composable)reutilizar
                    val label = stringResource(preset.labelRes)
                    MuseChip(
                        selected = selectedTemplateKey == preset.key,
                        onClick = {
                            selectedTemplateKey = preset.key
                            name = label
                            templateMode = preset.mode
                            templateLength = preset.length
                        },
                        label = label,
                        // El color de fondo de la ventana emergente es el mismo que el de las cápsulas no seleccionadas, por lo que agregar un trazo la hace más visible.
                        modifier = Modifier.chipOutline(selected = selectedTemplateKey == preset.key),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Selección de miembros
            // A continuación sólo queda un claro recordatorio; el título anterior se vuelve rojo + el texto rojo a continuación aparece al mismo tiempo.
            // La misma frase se dijo dos veces.
            Text(
                text = stringResource(R.string.groupchat_select_members),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (assistants.isEmpty()) {
                Text(
                    text = stringResource(R.string.groupchat_no_assistants),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(assistants, key = { it.id }) { assistant ->
                        val selected = assistant.id in selectedMemberIds
                        MuseChip(
                            selected = selected,
                            onClick = {
                                showErrors = false
                                selectedMemberIds = if (selected) {
                                    selectedMemberIds - assistant.id
                                } else {
                                    selectedMemberIds + assistant.id
                                }
                            },
                            label = assistant.name,
                            modifier = Modifier.chipOutline(selected = selected),
                        )
                    }
                }
            }
            if (memberError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.groupchat_member_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Equipos relacionados(Opcional)
            if (teams.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.groupchat_team_optional),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    item(key = "no_team") {
                        MuseChip(
                            selected = selectedTeamId == null,
                            onClick = { selectedTeamId = null },
                            label = stringResource(R.string.groupchat_no_team),
                        )
                    }
                    items(teams, key = { it.id }) { team ->
                        val selected = selectedTeamId == team.id
                        MuseChip(
                            selected = selected,
                            onClick = { selectedTeamId = team.id },
                            label = team.name,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.groupchat_selected_members, selectedMemberIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = stringResource(R.string.groupchat_create_btn),
        onConfirm = {
            val trimmedName = name.trim()
            if (trimmedName.isNotBlank() && selectedMemberIds.isNotEmpty()) {
                onConfirm(trimmedName, selectedMemberIds.toList(), selectedTeamId, templateMode, templateLength)
            } else {
                showErrors = true
            }
        },
        dismissText = stringResource(R.string.groupchat_cancel),
        onDismiss = onDismiss,
    )
}

/** v2.x: Crear una nueva plantilla preestablecida rápida de chat grupal(Nombre + Modo de discusión + Duración del discurso). */
data class TemplatePreset(
    val key: String,
    /** v2.3.1: Recurso de nombre de plantilla — También se utiliza como redacción de chips y pre-llenado de nombres de chat grupal. */
    val labelRes: Int,
    val mode: String,
    val length: String,
)
