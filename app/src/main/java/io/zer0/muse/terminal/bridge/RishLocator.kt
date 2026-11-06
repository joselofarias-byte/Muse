package io.zer0.muse.terminal.bridge

/**
 * Finds the Nightzuku `rish` binary by probing executable candidates.
 *
 * `$HOME/bin/rish` is first. Nothing here is a single hardcoded absolute path: every candidate is
 * derived from the HOME and PREFIX that Termux itself reported.
 */
internal object RishLocator {
    fun candidates(home: String, prefix: String): List<String> {
        val homeDir = home.trimEnd('/')
        val prefixDir = prefix.trimEnd('/')
        return listOf(
            "$homeDir/bin/rish",
            "$homeDir/.local/bin/rish",
            "$prefixDir/bin/rish",
        ).distinct()
    }

    fun detectionScript(home: String, prefix: String): String = buildString {
        for (path in candidates(home, prefix)) {
            val quoted = ShellQuote.single(path)
            append("if [ -x ").append(quoted).append(" ]; then printf '%s\\n' ")
            append(quoted).append("; exit 0; fi\n")
        }
        append("exit 1\n")
    }

    fun parse(stdout: String): String? {
        val line = stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") && it.endsWith("/rish") }
            ?: return null
        val unsafe = line.any { it.isWhitespace() || it == '\'' || it == '"' || it == ';' }
        return if (unsafe) null else line
    }
}
