package io.zer0.muse.terminal.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRuntimeTest {
    @Test
    fun jobCanBeQueriedCancelledAndKeepsExitCode() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<CommandResult>()
        val killed = CompletableDeferred<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = BridgeRuntime(
            scope = scope,
            executor = { _, _, _ ->
                started.complete(Unit)
                release.await()
            },
            killExecutor = { killed.complete(it) },
        )
        val id = runtime.submit(
            BridgeRuntime.JobSpec(
                command = "echo hi",
                timeoutMs = 5_000,
                workdir = null,
                channel = BridgeChannel.TERMUX,
                risk = CommandRisk.READ,
                pidFile = "/data/data/com.termux/files/usr/tmp/muse-bridge-test.pid",
            ),
        )
        started.await()
        assertEquals(BridgeJobState.RUNNING, runtime.get(id)?.state)
        assertTrue(id.isNotBlank())

        val cancelled = runtime.cancel(id)
        assertEquals(BridgeJobState.CANCELLED, cancelled?.state)
        assertEquals(BridgeErrorKind.CANCELLED, cancelled?.errorKind)
        assertEquals("/data/data/com.termux/files/usr/tmp/muse-bridge-test.pid", killed.await())
        assertEquals(BridgeJobState.CANCELLED, runtime.await(id).state)

        release.complete(CommandResult(0, "late", "", null))
        scope.cancel()
    }

    @Test
    fun timeoutPermissionAndServiceLossAreDistinct() = runBlocking {
        assertEquals(BridgeErrorKind.TIMEOUT, runError("等待结果超时(15s)").errorKind)
        assertEquals(BridgeJobState.TIMED_OUT, runError("等待结果超时(15s)").state)
        assertEquals(BridgeErrorKind.PERMISSION_DENIED, runError("缺少 Termux 调用权限").errorKind)
        assertEquals(
            BridgeErrorKind.SERVICE_LOST,
            runError("启动 Termux 服务失败: Not allowed to start service Intent").errorKind,
        )
    }

    @Test
    fun completedCommandKeepsExitCodeAndRedactsSecrets() = runBlocking {
        val job = runResult(CommandResult(7, "Bearer abcdefghijklmnop", "done", null))
        assertEquals(BridgeJobState.SUCCEEDED, job.state)
        assertEquals(7, job.output.exitCode)
        assertFalse(job.output.stdout.contains("abcdefghijklmnop"))
        assertTrue(job.output.stdout.contains("[REDACTED]"))
    }

    @Test
    fun foregroundTimeoutFailsActiveJobsAsServiceLoss() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<CommandResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = BridgeRuntime(
            scope = scope,
            executor = { _, _, _ ->
                started.complete(Unit)
                release.await()
            },
        )
        val id = runtime.submit(
            BridgeRuntime.JobSpec("id", 1_000, null, BridgeChannel.NIGHTZUKU, CommandRisk.READ, null),
        )
        started.await()
        runtime.failActive(BridgeErrorKind.SERVICE_LOST, "quota")
        val job = runtime.await(id)
        assertEquals(BridgeJobState.FAILED, job.state)
        assertEquals(BridgeErrorKind.SERVICE_LOST, job.errorKind)
        release.complete(CommandResult(0, "", "", null))
        scope.cancel()
    }

    private suspend fun runError(error: String): BridgeJob = runResult(CommandResult(-1, "", "", error))

    private suspend fun runResult(result: CommandResult): BridgeJob {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = BridgeRuntime(scope = scope, executor = { _, _, _ -> result })
        val id = runtime.submit(
            BridgeRuntime.JobSpec("echo", 1_000, null, BridgeChannel.TERMUX, CommandRisk.READ, null),
        )
        val job = runtime.await(id)
        scope.cancel()
        return job
    }
}
