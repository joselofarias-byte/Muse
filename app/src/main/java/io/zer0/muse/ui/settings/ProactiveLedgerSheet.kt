package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Panel de libro mayor.
 *
 * Esta interfaz de usuario es la forma de realizar el nuevo diseño: los usuarios pueden ver "¿Por qué me busca?"
 * También puedes ver "cuando se calla".
 *
 * Puntajes de condición física, umbrales,intimacy Estos parámetros internos ——
 * Esos son para registros, no para usuarios. Lo que los usuarios quieren ver es "en qué está pensando".
 * y "¿Todavía me pueden molestar hoy?"
 */
@Composable
fun ProactiveLedgerSheet(
    state: LedgerUiState,
    onIntensityChange: (ProactiveIntensity) -> Unit,
    onResetIntensity: () -> Unit,
    onCooldown: () -> Unit,
    onClearCooldown: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_proactive_v2_ledger),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── Nivel de iniciativa ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_proactive_v2_intensity),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Probé "sticky" y quise volver al valor predeterminado. No debería haber regresado uno por uno, y hoy ya gasté el dinero.
                // La cuota también deberá reponerse en consecuencia. —— Este es el "reset" que quieren los usuarios.
                Text(
                    text = stringResource(R.string.settings_proactive_v2_reset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onResetIntensity).padding(4.dp),
                )
            }
            Spacer(Modifier.size(6.dp))
            IntensityRow(current = state.intensity, onSelect = onIntensityChange)
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── la cuota de hoy ──
            val unlimited = state.intensity.unlimited
            val remainingTimes = if (state.remainingBudget >= 1f) state.remainingBudget.toInt() else 0
            Text(
                text = when {
                    state.cooldownUntil > state.now -> stringResource(R.string.settings_proactive_v2_cooldown_sub)
                    // El límite del archivo pegajoso es Int.MAX_VALUE. La representación directa se convertirá en "Puedo decir 2147483647 veces hoy"
                    unlimited -> stringResource(R.string.settings_proactive_v2_budget_unlimited)
                    else -> stringResource(R.string.settings_proactive_v2_budget, remainingTimes)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── lista de libro mayor ──
            if (state.thoughts.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_proactive_v2_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.thoughts, key = { it.id }) { thought ->
                        ThoughtRow(thought = thought, now = state.now)
                    }
                }
            }

            Spacer(Modifier.size(MusePaddings.contentGap))

            // ── interruptor de enfriamiento ──
            // Al enfriarse, esta área debe convertirse en "Recuperación": si solo hay una entrada unidireccional, si hace clic en el botón incorrecto, tendrá que esperar siete días.
            val coolingDown = state.cooldownUntil > state.now
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp),
                    ).clickable(onClick = if (coolingDown) onClearCooldown else onCooldown)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (coolingDown) {
                            R.string.settings_proactive_v2_resume
                        } else {
                            R.string.settings_proactive_v2_cooldown
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (coolingDown) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Todo el estado de visualización del panel. Empaquetar en un tipo es permitir que el sitio de llamada se alinee con un solo parámetro. */
data class LedgerUiState(
    val thoughts: List<PendingThoughtEntity>,
    val intensity: ProactiveIntensity,
    val remainingBudget: Float,
    val cooldownUntil: Long,
    val now: Long,
)

/** Las pendientes de cinco velocidades están dispuestas horizontalmente. Entra en vigor una vez que hace clic en él, no se requiere confirmación. */
@Composable
private fun IntensityRow(current: ProactiveIntensity, onSelect: (ProactiveIntensity) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProactiveIntensity.entries.forEach { level ->
            val selected = level == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(10.dp),
                    ).clickable { if (!selected) onSelect(level) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(level.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Una sola preocupación. La etiqueta de naturaleza a la izquierda, la razón y el sentido del tiempo a la derecha. */
@Composable
private fun ThoughtRow(thought: PendingThoughtEntity, now: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(thought.kindEnum().labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thought.seed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = describeAge(thought, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** sentido del tiempo. Los usuarios se sienten mejor cuando miran \"Hace tres días\" que cuando miran la marca de tiempo. */
@Composable
private fun describeAge(thought: PendingThoughtEntity, now: Long): String {
    val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
    return when {
        hours < 1 -> "justo ahora"
        hours < 24 -> "${hours} hace horas"
        else -> "${hours / 24} hace dias"
    }
}

private fun ProactiveIntensity.labelRes(): Int = when (this) {
    ProactiveIntensity.QUIET -> R.string.proactive_intensity_quiet
    ProactiveIntensity.RESERVED -> R.string.proactive_intensity_reserved
    ProactiveIntensity.STANDARD -> R.string.proactive_intensity_standard
    ProactiveIntensity.ACTIVE -> R.string.proactive_intensity_active
    ProactiveIntensity.CLINGY -> R.string.proactive_intensity_clingy
}

private fun ThoughtKind.labelRes(): Int = when (this) {
    ThoughtKind.REMINDER -> R.string.proactive_kind_reminder
    ThoughtKind.FOLLOW_UP -> R.string.proactive_kind_follow_up
    ThoughtKind.CARE -> R.string.proactive_kind_care
    ThoughtKind.SHARE -> R.string.proactive_kind_share
    ThoughtKind.MILESTONE -> R.string.proactive_kind_milestone
}
