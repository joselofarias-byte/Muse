package io.zer0.muse.terminal.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs bridge commands off the UI thread and keeps their results addressable by id.
 *
 * Cancelling marks the job immediately and asks the caller to signal the remote pid. The original
 * command text is not retained. A foreground-service timeout should call [failActive].
 */
internal class BridgeRuntime(
    private val scope: CoroutineScope,
    private val executor: suspend (command: String, timeoutMs: Long, workdir: String?) -> CommandResult,
    private val killExecutor: suspend (pidFile: String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onActiveCount: (Int) -> Unit = {},
    private val store: BridgeJobStore = BridgeJobStore(),
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<BridgeJob>>()

    fun activeCount(): Int = store.activeCount()

    fun get(id: String): BridgeJob? = store.get(id)

    fun submit(spec: JobSpec): String {
        val id = spec.id ?: newId()
        store.create(id, spec.channel, spec.risk, spec.pidFile, clock())
        val waiter = CompletableDeferred<BridgeJob>()
        waiters[id] = waiter
        notifyActive()
        scope.launch {
            val finished = runCatching { execute(id, spec) }.getOrElse { error ->
                store.finish(
                    id = id,
                    state = BridgeJobState.FAILED,
                    output = BridgeOutput(exitCode = null, stdout = "", stderr = ""),
                    errorKind = BridgeErrorKind.FAILED,
                    errorMessage = error.javaClass.simpleName,
                    nowMs = clock(),
                )
            }
            waiter.complete(finished ?: store.get(id) ?: missing(id))
            waiters.remove(id)
            notifyActive()
        }
        return id
    }

    suspend fun await(id: String): BridgeJob {
        waiters[id]?.await()
        return store.get(id) ?: missing(id)
    }

    fun cancel(id: String): BridgeJob? {
        val current = store.get(id) ?: return null
        if (current.state.terminal) return current
        store.requestCancel(id)
        val finished = store.finish(
            id = id,
            state = BridgeJobState.CANCELLED,
            output = current.output,
            errorKind = BridgeErrorKind.CANCELLED,
            errorMessage = "cancelled",
            nowMs = clock(),
        )
        current.pidFile?.let { pid ->
            scope.launch { runCatching { killExecutor(pid) } }
        }
        if (finished != null) waiters[id]?.complete(finished)
        notifyActive()
        return finished
    }

    fun failActive(kind: BridgeErrorKind, message: String) {
        for (id in store.activeIds()) {
            val finished = store.finish(
                id = id,
                state = BridgeJobState.FAILED,
                output = BridgeOutput(exitCode = null, stdout = "", stderr = ""),
                errorKind = kind,
                errorMessage = message,
                nowMs = clock(),
            )
            if (finished != null) waiters[id]?.complete(finished)
        }
        notifyActive()
    }

    private suspend fun execute(id: String, spec: JobSpec): BridgeJob? {
        val running = store.markRunning(id) ?: return null
        if (running.state != BridgeJobState.RUNNING) return running
        val result = executor(spec.command, spec.timeoutMs, spec.workdir)
        if (store.isCancelRequested(id)) return store.get(id)
        val kind = BridgeErrorClassifier.classify(result)
        val state = when (kind) {
            null -> BridgeJobState.SUCCEEDED
            BridgeErrorKind.TIMEOUT -> BridgeJobState.TIMED_OUT
            BridgeErrorKind.CANCELLED -> BridgeJobState.CANCELLED
            else -> BridgeJobState.FAILED
        }
        return store.finish(
            id = id,
            state = state,
            output = BridgeOutput(result.exitCode, result.stdout, result.stderr),
            errorKind = kind,
            errorMessage = result.error,
            nowMs = clock(),
        )
    }

    private fun notifyActive() {
        onActiveCount(store.activeCount())
    }

    private fun missing(id: String) = BridgeJob(
        id = id,
        state = BridgeJobState.FAILED,
        channel = BridgeChannel.TERMUX,
        risk = CommandRisk.READ,
        output = BridgeOutput(exitCode = null, stdout = "", stderr = ""),
        errorKind = BridgeErrorKind.FAILED,
        errorMessage = "missing",
        createdAtMs = clock(),
        finishedAtMs = clock(),
        pidFile = null,
    )

    data class JobSpec(
        val command: String,
        val timeoutMs: Long,
        val workdir: String?,
        val channel: BridgeChannel,
        val risk: CommandRisk,
        val pidFile: String?,
        val id: String? = null,
    )
}
