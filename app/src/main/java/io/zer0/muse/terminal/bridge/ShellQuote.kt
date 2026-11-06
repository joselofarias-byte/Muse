package io.zer0.muse.terminal.bridge

/** POSIX single quotes. The quoted value is data, never expanded by the outer shell. */
internal object ShellQuote {
    fun single(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
