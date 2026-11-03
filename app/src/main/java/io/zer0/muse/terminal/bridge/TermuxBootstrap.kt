package io.zer0.muse.terminal.bridge

/**
 * Environment prefix for a non-interactive Termux `bash -c`.
 *
 * Termux only adds `$HOME/bin` from the interactive rc files. Commands must not depend on that PATH:
 * `$PREFIX/bin` and `$HOME/bin` are exported explicitly, and `~/.bashrc` is never sourced.
 */
internal object TermuxBootstrap {
    const val DEFAULT_HOME = "/data/data/com.termux/files/home"
    const val DEFAULT_PREFIX = "/data/data/com.termux/files/usr"

    fun prefixOf(bashPath: String): String {
        val bin = bashPath.substringBeforeLast('/', missingDelimiterValue = "")
        val prefix = bin.substringBeforeLast('/', missingDelimiterValue = "")
        return prefix.ifBlank { DEFAULT_PREFIX }
    }

    fun wrap(command: String, home: String = DEFAULT_HOME, prefix: String = DEFAULT_PREFIX): String {
        val homeQuoted = ShellQuote.single(home)
        val prefixQuoted = ShellQuote.single(prefix)
        return "export HOME=$homeQuoted PREFIX=$prefixQuoted; " +
            "export TMPDIR=\"\$PREFIX/tmp\" " +
            "PATH=\"\$HOME/bin:\$PREFIX/bin:\$PREFIX/bin/applets\" " +
            "LD_LIBRARY_PATH=\"\$PREFIX/lib\"; " +
            command
    }
}
