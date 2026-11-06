package io.zer0.muse.terminal.bridge

/**
 * Stops credentials from being copied into a command line, a log line, or a temp file.
 *
 * A match rejects the command before any process starts. Output that happens to contain a token
 * is redacted before it is stored or returned. The matched text itself is never logged.
 */
internal object SecretGuard {
    private val PEM = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")
    private val BEARER = Regex("(?i)\\b(authorization\\s*:\\s*bearer|bearer)\\s+[A-Za-z0-9\\-._~+/]{8,}=*")
    private val ASSIGNMENT = Regex("(?i)\\b(api[_-]?key|access[_-]?key|secret|token|password|passwd)\\b\\s*[:=]\\s*\\S+")
    private val OPENAI = Regex("\\bsk-[A-Za-z0-9]{16,}\\b")
    private val GITHUB = Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b")
    private val AWS = Regex("\\bAKIA[0-9A-Z]{16}\\b")
    private val GOOGLE = Regex("\\bAIza[0-9A-Za-z\\-_]{20,}\\b")
    private val PATTERNS = listOf(PEM, BEARER, ASSIGNMENT, OPENAI, GITHUB, AWS, GOOGLE)

    fun containsSecret(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }

    fun redact(text: String): String {
        var current = text
        for (pattern in PATTERNS) {
            current = pattern.replace(current, "[REDACTED]")
        }
        return current
    }
}
