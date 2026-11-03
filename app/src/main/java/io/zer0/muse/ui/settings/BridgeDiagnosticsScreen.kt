package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.terminal.bridge.BridgeCoordinator
import io.zer0.muse.terminal.bridge.BridgeSnapshot
import io.zer0.muse.terminal.bridge.ShellIdentity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

@Composable
fun BridgeDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coordinator = remember { BridgeCoordinator.get(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<BridgeSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingGrant?>(null) }

    suspend fun reload() {
        loading = true
        snapshot = runCatching { coordinator.diagnose(refresh = true) }.getOrNull()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    SettingsSubPageScaffold(title = stringResource(R.string.bridge_settings_title), onBack = onBack) {
        item {
            MuseCapsuleButton(
                text = stringResource(R.string.bridge_refresh),
                onClick = { scope.launch { reload() } },
                loading = loading,
                modifier = Modifier.padding(bottom = MusePaddings.cardGap),
            )
        }
        item {
            StatusCard(
                title = stringResource(R.string.bridge_section_termux),
                rows = termuxRows(snapshot),
            )
        }
        item {
            StatusCard(
                title = stringResource(R.string.bridge_section_nightzuku),
                rows = nightzukuRows(snapshot),
            )
        }
        item {
            StatusCard(
                title = stringResource(R.string.bridge_section_android),
                rows = androidRows(snapshot),
            )
        }
        item {
            AuthorizationCard(
                snapshot = snapshot,
                onModify = { enabled ->
                    if (enabled) {
                        pending = PendingGrant.MODIFY
                    } else {
                        coordinator.setModify(false)
                        snapshot = snapshot.withGrant(modify = false, destructive = null)
                    }
                },
                onDestructive = { enabled ->
                    if (enabled) {
                        pending = PendingGrant.DESTRUCTIVE
                    } else {
                        coordinator.setDestructive(false)
                        snapshot = snapshot.withGrant(modify = null, destructive = false)
                    }
                },
            )
        }
    }

    val request = pending
    if (request != null) {
        GrantDialog(
            request = request,
            onConfirm = {
                when (request) {
                    PendingGrant.MODIFY -> coordinator.setModify(true)
                    PendingGrant.DESTRUCTIVE -> coordinator.setDestructive(true)
                }
                snapshot = snapshot.withGrant(
                    modify = if (request == PendingGrant.MODIFY) true else null,
                    destructive = if (request == PendingGrant.DESTRUCTIVE) true else null,
                )
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

private enum class PendingGrant { MODIFY, DESTRUCTIVE }

private fun BridgeSnapshot?.withGrant(modify: Boolean?, destructive: Boolean?): BridgeSnapshot? {
    val current = this ?: return null
    return current.copy(
        device = current.device.copy(
            modifyGranted = modify ?: current.device.modifyGranted,
            destructiveGranted = destructive ?: current.device.destructiveGranted,
        ),
    )
}

@Composable
private fun StatusCard(title: String, rows: List<Pair<String, String>>) {
    CardGroup(title = { Text(title) }) {
        rows.forEach { (headline, support) ->
            item(
                headlineContent = { Text(headline) },
                supportingContent = { Text(support) },
            )
        }
    }
}

@Composable
private fun AuthorizationCard(snapshot: BridgeSnapshot?, onModify: (Boolean) -> Unit, onDestructive: (Boolean) -> Unit) {
    CardGroup(title = { Text(stringResource(R.string.bridge_section_auth)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.bridge_auth_modify_title)) },
            supportingContent = { Text(stringResource(R.string.bridge_auth_modify_sub)) },
            trailingContent = {
                MuseSwitch(
                    checked = snapshot?.device?.modifyGranted == true,
                    onCheckedChange = onModify,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.bridge_auth_destructive_title)) },
            supportingContent = { Text(stringResource(R.string.bridge_auth_destructive_sub)) },
            trailingContent = {
                MuseSwitch(
                    checked = snapshot?.device?.destructiveGranted == true,
                    onCheckedChange = onDestructive,
                )
            },
        )
    }
}

@Composable
private fun GrantDialog(request: PendingGrant, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val destructive = request == PendingGrant.DESTRUCTIVE
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (destructive) R.string.bridge_auth_destructive_dialog_title else R.string.bridge_auth_modify_dialog_title,
        ),
        content = {
            Text(
                text = stringResource(
                    if (destructive) {
                        R.string.bridge_auth_destructive_dialog_body
                    } else {
                        R.string.bridge_auth_modify_dialog_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = destructive,
    )
}

@Composable
private fun termuxRows(snapshot: BridgeSnapshot?): List<Pair<String, String>> {
    val ready = stringResource(R.string.bridge_status_ready)
    val missing = stringResource(R.string.bridge_status_missing)
    val denied = stringResource(R.string.bridge_status_denied)
    val unknown = stringResource(R.string.bridge_status_unknown)
    val installed = when (snapshot?.termuxInstalled) {
        true -> ready
        false -> missing
        null -> unknown
    }
    val permission = when {
        snapshot == null -> unknown
        !snapshot.termuxInstalled -> missing
        snapshot.runCommandGranted -> ready
        else -> denied
    }
    return listOf(
        stringResource(R.string.bridge_termux_installed) to installed,
        stringResource(R.string.bridge_run_command) to permission,
        stringResource(R.string.bridge_home) to (snapshot?.home ?: unknown),
        stringResource(R.string.bridge_prefix) to (snapshot?.prefix ?: unknown),
    )
}

@Composable
private fun nightzukuRows(snapshot: BridgeSnapshot?): List<Pair<String, String>> {
    val missing = stringResource(R.string.bridge_status_missing)
    val unknown = stringResource(R.string.bridge_status_unknown)
    val identity = when (snapshot?.identity) {
        ShellIdentity.Kind.SHELL -> stringResource(R.string.bridge_identity_shell)
        ShellIdentity.Kind.ROOT -> stringResource(R.string.bridge_identity_root)
        ShellIdentity.Kind.OTHER -> stringResource(R.string.bridge_identity_other)
        ShellIdentity.Kind.UNKNOWN, null -> unknown
    }
    val packageStatus = when (snapshot?.device?.nightzukuPackage) {
        true -> stringResource(R.string.bridge_status_ready)
        false -> missing
        null -> unknown
    }
    return listOf(
        stringResource(R.string.bridge_rish) to (snapshot?.rishPath ?: missing),
        stringResource(R.string.bridge_identity) to identity,
        stringResource(R.string.bridge_nightzuku_pkg) to packageStatus,
    )
}

@Composable
private fun androidRows(snapshot: BridgeSnapshot?): List<Pair<String, String>> {
    val unknown = stringResource(R.string.bridge_status_unknown)
    val sdk = snapshot?.device?.sdkInt?.toString() ?: unknown
    val notifications = when (snapshot?.device?.notificationsGranted) {
        true -> stringResource(R.string.bridge_status_ready)
        false -> stringResource(R.string.bridge_status_denied)
        null -> unknown
    }
    val jobs = snapshot?.device?.activeJobs?.toString() ?: unknown
    return listOf(
        stringResource(R.string.bridge_sdk) to sdk,
        stringResource(R.string.bridge_notifications) to notifications,
        stringResource(R.string.bridge_fgs) to stringResource(R.string.bridge_fgs_value),
        stringResource(R.string.bridge_active_jobs) to jobs,
    )
}
