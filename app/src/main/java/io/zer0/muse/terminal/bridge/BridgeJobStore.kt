package io.zer0.muse.terminal.bridge

import java.util.concurrent.ConcurrentHashMap

/** In-process job table. Commands are not stored: only the id, the risk and the sanitized output. */
internal class BridgeJobStore {
    private val jobs = ConcurrentHashMap<String, BridgeJob>()
    private val order = ArrayDeque<String>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()

    fun create(id: String, channel: BridgeChannel, risk: CommandRisk, pidFile: String?, nowMs: Long): BridgeJob {
        val job = BridgeJob(
            id = id,
            state = BridgeJobState.QUEUED,
            channel = channel,
            risk = risk,
            output = BridgeOutput(exitCode = null, stdout = "", stderr = ""),
            errorKind = null,
            errorMessage = null,
            createdAtMs = nowMs,
            finishedAtMs = null,
            pidFile = pidFile,
        )
        synchronized(order) {
            jobs[id] = job
            order.addLast(id)
            trimLocked()
        }
        return job
    }

    fun get(id: String): BridgeJob? = jobs[id]

    fun activeIds(): List<String> = jobs.values.filter { !it.state.terminal }.map { it.id }

    fun activeCount(): Int = jobs.values.count { !it.state.terminal }

    fun isCancelRequested(id: String): Boolean = id in cancelRequested

    fun requestCancel(id: String) {
        cancelRequested.add(id)
    }

    /** Moves QUEUED to RUNNING. A terminal job is left unchanged. */
    fun markRunning(id: String): BridgeJob? = synchronized(order) {
        val current = jobs[id] ?: return null
        if (current.state != BridgeJobState.QUEUED) return current
        val running = current.copy(state = BridgeJobState.RUNNING)
        jobs[id] = running
        running
    }

    fun finish(
        id: String,
        state: BridgeJobState,
        output: BridgeOutput,
        errorKind: BridgeErrorKind?,
        errorMessage: String?,
        nowMs: Long,
    ): BridgeJob? = synchronized(order) {
        val current = jobs[id] ?: return null
        if (current.state.terminal) return current
        val finished = current.copy(
            state = state,
            output = output.copy(
                stdout = OutputSanitizer.clean(output.stdout),
                stderr = OutputSanitizer.clean(output.stderr),
            ),
            errorKind = errorKind,
            errorMessage = errorMessage?.let(OutputSanitizer::clean),
            finishedAtMs = nowMs,
        )
        jobs[id] = finished
        finished
    }

    private fun trimLocked() {
        while (order.size > MAX_JOBS) {
            val oldest = order.firstOrNull() ?: return
            val job = jobs[oldest]
            if (job != null && !job.state.terminal) return
            order.removeFirst()
            if (job != null) {
                jobs.remove(oldest)
                cancelRequested.remove(oldest)
            }
        }
    }

    private companion object {
        const val MAX_JOBS = 32
    }
}
