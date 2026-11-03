package io.zer0.muse.terminal.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.zer0.common.Logger
import io.zer0.muse.terminal.TermuxChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android entry for the Termux / Nightzuku bridge.
 *
 * Execution goes through the existing [TermuxChannel]. A dataSync foreground service is held while
 * a job is active so Android 16 can keep the process when Muse leaves the foreground, until the
 * platform time limit fires [onForegroundTimeout].
 */
internal class BridgeCoordinator private constructor(
    private val appContext: Context,
    private val channel: TermuxChannel,
    private val consentStore: NightzukuConsentStore,
    private val scope: CoroutineScope,
    private val runtime: BridgeRuntime,
) {
    @Volatile
    private var facts: BridgeProbe.ChannelFacts? = null

    @Volatile
    private var home: String = TermuxBootstrap.DEFAULT_HOME

    @Volatile
    private var prefix: String = TermuxBootstrap.DEFAULT_PREFIX

    fun consent(): NightzukuGate.Consent = consentStore.consent()

    fun setModify(enabled: Boolean) {
        consentStore.setModify(enabled)
    }

    fun setDestructive(enabled: Boolean) {
        consentStore.setDestructive(enabled)
    }

    suspend fun diagnose(refresh: Boolean): BridgeSnapshot {
        if (refresh || facts == null) {
            val probed = BridgeProbe(channel).collect()
            facts = probed
            home = probed.home
            prefix = probed.prefix
        }
        val current = facts ?: return emptySnapshot()
        val consent = consentStore.consent()
        return BridgeSnapshot(
            termuxInstalled = current.termuxInstalled,
            runCommandGranted = current.runCommandGranted,
            termuxReady = current.termuxReady,
            home = current.home,
            prefix = current.prefix,
            rishPath = current.rishPath,
            identity = current.identity,
            detail = current.detail,
            device = deviceFacts(consent),
        )
    }

    suspend fun runNightzuku(command: String, timeoutMs: Long, workdir: String?): String {
        val snapshot = diagnose(refresh = facts == null)
        val decision = NightzukuGate.nightzuku(command, consentStore.consent(), snapshot.identity)
        if (decision is NightzukuGate.Decision.Deny) return BridgeText.denied(decision)
        val rish = snapshot.rishPath
            ?: return BridgeText.denied(BridgeErrorKind.NOT_READY, "rish no apareció en HOME/bin ni en PREFIX/bin.")
        if (decision !is NightzukuGate.Decision.Allow) {
            return BridgeText.denied(BridgeErrorKind.FAILED, "decisión desconocida")
        }
        val id = UUID.randomUUID().toString()
        val pid = NightzukuScript.pidFile(prefix, id)
        val wrapped = TermuxBootstrap.wrap(NightzukuScript.run(rish, pid, command), home, prefix)
        return awaitSubmitted(spec(id, wrapped, timeoutMs, workdir, BridgeChannel.NIGHTZUKU, decision.risk, pid))
    }

    suspend fun runTermux(command: String, timeoutMs: Long, workdir: String?): String {
        val decision = NightzukuGate.termux(command)
        if (decision is NightzukuGate.Decision.Deny) return BridgeText.denied(decision)
        if (decision !is NightzukuGate.Decision.Allow) {
            return BridgeText.denied(BridgeErrorKind.FAILED, "decisión desconocida")
        }
        diagnose(refresh = facts == null)
        val id = UUID.randomUUID().toString()
        val pid = NightzukuScript.pidFile(prefix, id)
        val wrapped = TermuxBootstrap.wrap(NightzukuScript.termuxWrapped(pid, command), home, prefix)
        return awaitSubmitted(spec(id, wrapped, timeoutMs, workdir, BridgeChannel.TERMUX, decision.risk, pid))
    }

    suspend fun startJob(channel: BridgeChannel, command: String, timeoutMs: Long, workdir: String?): String {
        if (facts == null || (channel == BridgeChannel.NIGHTZUKU && facts?.rishPath == null)) {
            diagnose(refresh = true)
        }
        val prepared = prepare(channel, command) ?: return lastDeny
        Logger.i(TAG, "submit id=${prepared.id} channel=$channel risk=${prepared.risk}")
        runtime.submit(
            spec(prepared.id, prepared.wrapped, timeoutMs, workdir, channel, prepared.risk, prepared.pidFile),
        )
        val job = runtime.get(prepared.id)
            ?: return BridgeText.denied(BridgeErrorKind.FAILED, "no se pudo crear el trabajo")
        return BridgeText.job(job)
    }

    fun job(id: String): String {
        val found = runtime.get(id) ?: return BridgeText.denied(BridgeErrorKind.FAILED, "No existe el trabajo.")
        return BridgeText.job(found)
    }

    fun cancel(id: String): String {
        val found = runtime.cancel(id) ?: return BridgeText.denied(BridgeErrorKind.FAILED, "No existe el trabajo.")
        Logger.i(TAG, "cancel id=$id")
        return BridgeText.job(found)
    }

    fun onForegroundTimeout() {
        Logger.w(TAG, "foreground service timeout")
        runtime.failActive(
            BridgeErrorKind.SERVICE_LOST,
            "Android detuvo el servicio en primer plano (límite de dataSync).",
        )
    }

    private suspend fun awaitSubmitted(job: BridgeRuntime.JobSpec): String {
        val id = requireNotNull(job.id)
        Logger.i(TAG, "submit id=$id channel=${job.channel} risk=${job.risk}")
        runtime.submit(job)
        return BridgeText.job(runtime.await(id))
    }

    private fun prepare(channel: BridgeChannel, command: String): Prepared? {
        val decision = when (channel) {
            BridgeChannel.TERMUX -> NightzukuGate.termux(command)
            BridgeChannel.NIGHTZUKU -> NightzukuGate.nightzuku(
                command,
                consentStore.consent(),
                facts?.identity ?: ShellIdentity.Kind.UNKNOWN,
            )
        }
        if (decision is NightzukuGate.Decision.Deny) {
            lastDeny = BridgeText.denied(decision)
            return null
        }
        if (decision !is NightzukuGate.Decision.Allow) {
            lastDeny = BridgeText.denied(BridgeErrorKind.FAILED, "decisión desconocida")
            return null
        }
        if (channel == BridgeChannel.NIGHTZUKU && facts?.rishPath == null) {
            lastDeny = BridgeText.denied(BridgeErrorKind.NOT_READY, "rish todavía no está resuelto. Abre el diagnóstico.")
            return null
        }
        val rish = facts?.rishPath
        val id = UUID.randomUUID().toString()
        val pid = NightzukuScript.pidFile(prefix, id)
        val script = if (channel == BridgeChannel.NIGHTZUKU && rish != null) {
            NightzukuScript.run(rish, pid, command)
        } else {
            NightzukuScript.termuxWrapped(pid, command)
        }
        return Prepared(decision.risk, TermuxBootstrap.wrap(script, home, prefix), pid, id)
    }

    private fun onActive(count: Int) {
        if (count > 0) {
            val started = runCatching { BridgeExecutionService.start(appContext) }
            if (started.isFailure) {
                Logger.w(TAG, "foreground start rejected: ${started.exceptionOrNull()?.javaClass?.simpleName}")
                runtime.failActive(BridgeErrorKind.SERVICE_LOST, "No se pudo mantener el servicio en primer plano.")
            }
            return
        }
        scope.launch {
            delay(STOP_GRACE_MS)
            if (runtime.activeCount() == 0) {
                runCatching { BridgeExecutionService.stop(appContext) }
            }
        }
    }

    private fun nightzukuInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(NIGHTZUKU_PACKAGE, 0)
    }.isSuccess

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun deviceFacts(consent: NightzukuGate.Consent) = BridgeDeviceFacts(
        nightzukuPackage = nightzukuInstalled(),
        sdkInt = Build.VERSION.SDK_INT,
        notificationsGranted = notificationsGranted(),
        modifyGranted = consent.modify,
        destructiveGranted = consent.destructive,
        activeJobs = runtime.activeCount(),
    )

    private fun emptySnapshot(): BridgeSnapshot = BridgeSnapshot(
        termuxInstalled = false,
        runCommandGranted = false,
        termuxReady = false,
        home = home,
        prefix = prefix,
        rishPath = null,
        identity = ShellIdentity.Kind.UNKNOWN,
        detail = null,
        device = deviceFacts(NightzukuGate.Consent(modify = false, destructive = false)),
    )

    private var lastDeny: String = BridgeText.denied(BridgeErrorKind.FAILED, "rechazado")

    private data class Prepared(
        val risk: CommandRisk,
        val wrapped: String,
        val pidFile: String,
        val id: String,
    )

    companion object {
        private const val TAG = "Bridge"
        private const val STOP_GRACE_MS = 1_500L
        const val NIGHTZUKU_PACKAGE = "com.joselofarias.nightzuku"

        @Volatile
        private var instance: BridgeCoordinator? = null

        fun get(context: Context): BridgeCoordinator = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): BridgeCoordinator {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            lateinit var coordinator: BridgeCoordinator
            val runtime = BridgeRuntime(
                scope = scope,
                executor = { command, timeoutMs, workdir ->
                    val result = TermuxChannel.get(context).exec(command, timeoutMs, workdir)
                    CommandResult(result.exitCode, result.stdout, result.stderr, result.error)
                },
                killExecutor = { pidFile ->
                    val script = TermuxBootstrap.wrap(
                        NightzukuScript.kill(pidFile),
                        coordinator.home,
                        coordinator.prefix,
                    )
                    TermuxChannel.get(context).exec(script, KILL_TIMEOUT_MS, null)
                    Unit
                },
                onActiveCount = { count -> coordinator.onActive(count) },
            )
            coordinator = BridgeCoordinator(
                appContext = context,
                channel = TermuxChannel.get(context),
                consentStore = NightzukuConsentStore(context),
                scope = scope,
                runtime = runtime,
            )
            return coordinator
        }

        private fun spec(
            id: String,
            command: String,
            timeoutMs: Long,
            workdir: String?,
            channel: BridgeChannel,
            risk: CommandRisk,
            pidFile: String,
        ) = BridgeRuntime.JobSpec(
            command = command,
            timeoutMs = timeoutMs,
            workdir = workdir,
            channel = channel,
            risk = risk,
            pidFile = pidFile,
            id = id,
        )

        private const val KILL_TIMEOUT_MS = 8_000L
    }
}
