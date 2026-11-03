package io.zer0.muse.terminal.bridge

internal enum class BridgeChannel {
    TERMUX,
    NIGHTZUKU,
}

internal enum class BridgeJobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    ;

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT
}

internal data class BridgeOutput(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

internal data class BridgeJob(
    val id: String,
    val state: BridgeJobState,
    val channel: BridgeChannel,
    val risk: CommandRisk,
    val output: BridgeOutput,
    val errorKind: BridgeErrorKind?,
    val errorMessage: String?,
    val createdAtMs: Long,
    val finishedAtMs: Long?,
    val pidFile: String?,
)
