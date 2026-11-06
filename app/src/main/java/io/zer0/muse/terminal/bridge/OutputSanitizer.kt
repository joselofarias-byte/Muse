package io.zer0.muse.terminal.bridge

/** Bounds and redacts text before it is kept in memory or handed back to the model. */
internal object OutputSanitizer {
    const val MAX_CHARS = 32_000

    fun clean(text: String): String {
        val redacted = SecretGuard.redact(text)
        if (redacted.length <= MAX_CHARS) return redacted
        return redacted.take(MAX_CHARS) + "\n…[truncated]"
    }
}
