package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.memory.fact.MemoryAuditSource
import io.zer0.muse.R
import io.zer0.muse.diagnostics.DiagnosticsDisplayFormat
import io.zer0.muse.diagnostics.DiagnosticsSnapshot
import io.zer0.muse.diagnostics.MainThreadJankTracker
import io.zer0.muse.schedule.WakeState
import io.zer0.muse.schedule.WakeWorkKind
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.23(S4): 运行诊断子页。
 *
 * 展示 [DiagnosticsSnapshot] 的各分区并支持「复制脱敏 JSON / 导出缓存文件」。
 * 页面本身只读:不注册任务、不清缓存、不改任何配置 —— 诊断工具在排查现场时
 * 不该成为现场的修改者。
 */
@Composable
fun SettingsDiagnosticsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // M4: 审计源走装配,页面自己不开库、不猜 id;未装配时导出给 databasesChecked=0,
    // 读导出的人能分辨「没装配」与「账本干净」。
    val memoryAudit = koinInject<MemoryAuditSource>()
    var snapshot by remember { mutableStateOf<DiagnosticsSnapshot?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        snapshot = runCatching { DiagnosticsSnapshot.collect(context, memoryAudit) }.getOrNull()
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_screen_diagnostics),
        onBack = onBack,
    ) {
        val data = snapshot
        if (data == null) {
            item {
                Text(
                    text = stringResource(R.string.settings_diagnostics_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                )
            }
        } else {
            item { AppInfoCard(data) }
            item { StorageCard(data) }
            item { MainThreadCard(data) }
            item { WebHostCard(data) }
            item { BackgroundCard(data) }
            item { MigrationCard(data) }
        }
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_diagnostics_actions)) }) {
                item(
                    headlineContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_diagnostics_export_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.settings_diagnostics_copy_json),
                                onClick = {
                                    val json = data?.sanitizedJson().orEmpty()
                                    if (json.isEmpty()) return@MuseCapsuleButton
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("MuseDiagnostics", json))
                                    MuseToast.show(resources.getString(R.string.settings_diagnostics_copied))
                                },
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.settings_diagnostics_export_file),
                                onClick = {
                                    val current = data ?: return@MuseCapsuleButton
                                    scope.launch(Dispatchers.IO) {
                                        val file = current.exportToCache(context)
                                        val ok = file.exists() && file.length() > 0L
                                        val msg = if (ok) {
                                            resources.getString(R.string.settings_diagnostics_exported)
                                        } else {
                                            resources.getString(R.string.settings_diagnostics_export_failed)
                                        }
                                        MuseToast.show(msg)
                                    }
                                },
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.settings_diagnostics_refresh),
                                onClick = { reload++ },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AppInfoCard(data: DiagnosticsSnapshot) {
    val title = stringResource(R.string.settings_diagnostics_app)
    val rows = listOf(
        stringResource(R.string.settings_diagnostics_version) to "${data.app.versionName} (${data.app.versionCode})",
        stringResource(R.string.settings_diagnostics_db_versions) to
            stringResource(
                R.string.settings_diagnostics_schema_summary,
                data.app.museDbVersion,
                data.app.factDbVersion,
                data.app.memoryDbVersion,
            ),
        stringResource(R.string.settings_diagnostics_collected_at) to DiagnosticsDisplayFormat.collectedAt(data.collectedAt),
    )
    CardGroup(title = { Text(title) }) {
        rows.forEach { (label, value) -> infoRow(label, value) }
    }
}

@Composable
private fun StorageCard(data: DiagnosticsSnapshot) {
    val title = stringResource(R.string.settings_diagnostics_storage)
    val unknownSuffix = stringResource(R.string.settings_diagnostics_unknown_db)
    val rows = buildList {
        add(stringResource(R.string.settings_diagnostics_total_bytes) to DiagnosticsDisplayFormat.bytes(data.storage.totalBytes))
        add(stringResource(R.string.settings_diagnostics_log_bytes) to DiagnosticsDisplayFormat.bytes(data.storage.logBytes))
        data.storage.databases.forEach { db ->
            val suffix = if (db.known) "" else " · $unknownSuffix"
            val sizes = stringResource(
                R.string.settings_diagnostics_database_sizes,
                DiagnosticsDisplayFormat.bytes(db.mainBytes),
                DiagnosticsDisplayFormat.bytes(db.walBytes),
            )
            add(db.name to "$sizes$suffix")
        }
    }
    CardGroup(title = { Text(title) }) {
        rows.forEach { (label, value) -> infoRow(label, value) }
    }
}

@Composable
private fun MainThreadCard(data: DiagnosticsSnapshot) {
    val title = stringResource(R.string.settings_diagnostics_main_thread)
    val mt = data.mainThread
    // 阈值常量从 tracker 取,页面不许抄死数字
    val rows = listOf(
        stringResource(R.string.settings_diagnostics_window) to "${mt.windowSeconds} s",
        stringResource(R.string.settings_diagnostics_jank_counts) to "${mt.jankCount} / ${mt.slowCount} / ${mt.blockCount}",
        stringResource(R.string.settings_diagnostics_worst) to "${mt.worstMs} ms",
        stringResource(R.string.settings_diagnostics_thresholds) to
            "${MainThreadJankTracker.JANK_MS} / ${MainThreadJankTracker.SLOW_MS} / ${MainThreadJankTracker.BLOCK_MS} ms",
    )
    CardGroup(title = { Text(title) }) {
        rows.forEach { (label, value) -> infoRow(label, value) }
    }
}

/**
 * S3:上一次数据迁移的结果。
 *
 * 没有记录时明说「这台设备还没有迁移记录」而不是不画这张卡:
 * 全新安装与「迁移器没读到」在诊断里必须长得不一样。
 */
@Composable
private fun MigrationCard(data: DiagnosticsSnapshot) {
    val title = stringResource(R.string.settings_diagnostics_migration)
    val run = data.migration
    val rows = if (run == null) {
        listOf(stringResource(R.string.settings_diagnostics_migration_none) to "")
    } else {
        buildList {
            add(
                stringResource(R.string.settings_diagnostics_migration_summary, run.steps, run.totalMillis, run.slowestStepMillis) to
                    "v${run.fromVersion} → v${run.toVersion}",
            )
            run.failedAtVersion?.let {
                add(stringResource(R.string.settings_diagnostics_migration_failed, it) to "")
            }
        }
    }
    CardGroup(title = { Text(title) }) {
        rows.forEach { (label, value) -> infoRow(label, value) }
    }
}

@Composable
private fun WebHostCard(data: DiagnosticsSnapshot) {
    val title = stringResource(R.string.settings_diagnostics_webhost)
    val p95 = data.webHost.p95Ms
    val p95Value = when {
        p95 == null -> stringResource(R.string.settings_diagnostics_no_requests)
        !data.webHost.p95Reliable -> "$p95 ms · " + stringResource(R.string.settings_diagnostics_few_samples)
        else -> "$p95 ms"
    }
    val rows = listOf(
        stringResource(R.string.settings_diagnostics_samples) to data.webHost.latencySamples.toString(),
        stringResource(R.string.settings_diagnostics_p95) to p95Value,
    )
    CardGroup(title = { Text(title) }) {
        rows.forEach { (label, value) -> infoRow(label, value) }
    }
}

@Composable
private fun BackgroundCard(data: DiagnosticsSnapshot) {
    val title = stringResource(R.string.settings_diagnostics_background)
    val rows = buildList {
        data.background.error?.let { add(stringResource(R.string.settings_diagnostics_bg_error) to it) }
        if (data.background.entries.isEmpty()) {
            add(stringResource(R.string.settings_diagnostics_bg_none) to "")
        }
        data.background.entries.forEach { entry ->
            val next = entry.nextRunAtMillis?.let { DiagnosticsDisplayFormat.epochMillis(it) } ?: "—"
            add(diagnosticsWorkKindLabel(entry.kind) to "${diagnosticsWorkStateLabel(entry.state)} · $next")
        }
    }
    CardGroup(title = { Text(title) }) {
        rows.forEach { (label, value) -> infoRow(label, value) }
    }
}

/**
 * 诊断行:挂在 [CardGroupScope] 上(item 是 DSL 方法,普通 @Composable 里不可用)。
 * label 由调用方在 Composable 上下文预解析 —— CardGroup 的 content 不是 @Composable lambda,
 * stringResource 不许出现在里面。
 */
private fun CardGroupScope.infoRow(label: String, value: String) {
    item(
        headlineContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (value.isNotEmpty()) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun diagnosticsWorkKindLabel(kind: WakeWorkKind): String = stringResource(
    when (kind) {
        WakeWorkKind.SCHEDULED_TASK -> R.string.wake_kind_scheduled_task
        WakeWorkKind.PROACTIVE -> R.string.wake_kind_proactive
        WakeWorkKind.DAILY_SUMMARY -> R.string.wake_kind_daily_summary
        WakeWorkKind.CLOUD_BACKUP -> R.string.wake_kind_cloud_backup
        WakeWorkKind.AUTO_BACKUP -> R.string.wake_kind_auto_backup
        WakeWorkKind.STATS_CACHE -> R.string.wake_kind_stats_cache
    },
)

@Composable
private fun diagnosticsWorkStateLabel(state: WakeState): String = stringResource(
    when (state) {
        WakeState.ENQUEUED -> R.string.wake_state_enqueued
        WakeState.RUNNING -> R.string.wake_state_running
        WakeState.BLOCKED -> R.string.wake_state_blocked
        WakeState.SUCCEEDED -> R.string.wake_state_succeeded
        WakeState.FAILED -> R.string.wake_state_failed
        WakeState.CANCELLED -> R.string.wake_state_cancelled
        WakeState.NOT_REGISTERED -> R.string.wake_state_not_registered
        WakeState.UNKNOWN -> R.string.wake_state_unknown
    },
)
