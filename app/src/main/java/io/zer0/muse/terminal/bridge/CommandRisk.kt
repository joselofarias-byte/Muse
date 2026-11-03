package io.zer0.muse.terminal.bridge

/** Side-effect class of one shell command. The highest class in a pipeline wins. */
internal enum class CommandRisk {
    READ,
    MODIFY,
    DESTRUCTIVE,
}
