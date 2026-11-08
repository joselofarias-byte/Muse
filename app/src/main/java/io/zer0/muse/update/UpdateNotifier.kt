package io.zer0.muse.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface UpdateCheckResult {
    data class NewVersion(val release: UpdateChecker.ReleaseInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
    data object Skipped : UpdateCheckResult
}

/** 将网络查询结果转换为 UI 可消费的明确状态；纯逻辑便于测试。 */
internal fun classifyUpdateResult(currentVersion: String, result: io.zer0.common.Result<UpdateChecker.ReleaseInfo>): UpdateCheckResult {
    val release = result.getOrNull() ?: return UpdateCheckResult.Failed(
        (result as? io.zer0.common.Result.Error)?.message ?: "unknown",
    )
    return if (UpdateChecker.compareVersions(currentVersion, release.tagName) >= 0) {
        UpdateCheckResult.UpToDate
    } else {
        UpdateCheckResult.NewVersion(release)
    }
}

class UpdateNotifier(
    private val settings: SettingsRepository,
    private val checker: UpdateChecker,
) {

    /** Legacy automatic callers always skip, regardless of saved preferences. */
    suspend fun checkAndNotify(context: Context, forceCheck: Boolean = false): UpdateCheckResult =
        if (forceCheck) performCheck(context) else UpdateCheckResult.Skipped

    /** Called only by the explicit update action in Settings. */
    suspend fun checkManually(context: Context): UpdateCheckResult = try {
        checkAndNotify(context, forceCheck = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        Logger.w(TAG, "manual update check failed: ${error.message}", error)
        UpdateCheckResult.Failed(error.message ?: "network error")
    }

    private suspend fun performCheck(context: Context): UpdateCheckResult {
        // Keep a timestamp for the explicit manual check only.
        settings.saveLastUpdateCheckTime(System.currentTimeMillis())
        val currentVersion = getCurrentVersionName(context)
        return classifyUpdateResult(currentVersion, checker.checkLatestRelease())
    }

    companion object {
        private const val TAG = "UpdateNotifier"

        /**
         * 便捷方法:语义版本比较 — 委托给 [UpdateChecker.compareVersions]。
         */
        fun compareVersions(current: String, latest: String): Int = UpdateChecker.compareVersions(current, latest)

        /**
         * v1.0.72: 读取当前应用 versionName(从 private 提升为 companion,供 UI 静态调用)。
         * 读取失败返回 "0",保证后续比较可执行。
         */
        fun getCurrentVersionName(context: Context): String {
            return resultOf {
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                packageInfo.versionName ?: "0"
            }.getOrNull() ?: "0"
        }

        /**
         * 官网下载页 — 应用内所有下载/更新出口统一指向这里。
         *
         * 「立即下载」为什么先落到官网而不是直接给 APK 直链：官网会用当前 Release 的资产列表
         * 按机型给出对应架构的按钮，用户点一下即从 GitHub 资产直链下载；应用内自己判断架构
         * 反而容易选错包（尤其 32/64 位混杂的机器）。官网下载页不自己存安装包。
         */
        const val OFFICIAL_DOWNLOAD_PAGE = "https://museai.ltd/download/"

        /**
         * 构造用于打开官网下载页的 [Intent](ACTION_VIEW)。
         * 调用方负责 startActivity / chooser。
         */
        fun buildOfficialDownloadIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_DOWNLOAD_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
