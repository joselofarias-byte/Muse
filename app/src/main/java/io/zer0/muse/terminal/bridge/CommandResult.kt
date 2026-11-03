package io.zer0.muse.terminal.bridge

/** Result of one command attempt. [error] is a bridge failure, not the command's own stderr. */
internal data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val error: String?,
)
