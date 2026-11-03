package io.zer0.muse.terminal.bridge

/**
 * Stops credentials from being copied into a command line, a log line, or a temp file.
 *
 * A match rejects the command before any process starts. Output that happens to contain a token
 * is redacted before it is stored or returned. The matched text itself is never logged.
 */
internal object SecretGuard {
    private val PATTERNS: List<Regex> = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("(?i)\\b(authorization\\s*:\\s*bearer|bearer)\\s+[A-Za-z0-9\\-._~+/]{8,}=*"),
        Regex("(?i)\\b(api[_-]?key|access[_-]?key|secret|token|password|passwd)\\b\\s*[:=]\\s*\\S+"),
        Regex("\\bsk-[A-Za-z0-9]{16,}\\b"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bAIza[0-9A-Za-z\\-_]{20,}\\b"),
    )

    fun containsSecret(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }

    fun redact(text: String): String {
        var current = text
        for (pattern in PATTERNS) {
            current = pattern.replace(current, "[REDACTED]")
        }
        return current
    }
}
