package io.zer0.muse.terminal.bridge

/**
 * Classifies a command as read, modify, or destructive.
 *
 * Unknown verbs are MODIFY so they cannot run as a silent read. A pipeline takes the highest class.
 * This is not a shell parser: it catches the commands a model normally emits.
 */
internal object CommandRiskClassifier {
    private val SEPARATOR = Regex("&&|\\|\\||[;|]")
    private val WHITESPACE = Regex("\\s+")
    private val REDIRECT = Regex("(?<!&)\\d?>{1,2}\\s*\\S+")
    private val READ_VERBS = setOf(
        "id", "whoami", "pwd", "echo", "printf", "ls", "cat", "head", "tail", "wc", "stat",
        "uname", "date", "getprop", "dumpsys", "logcat", "ps", "df", "du", "which", "true",
        "false", "grep", "egrep", "fgrep", "test", "[", "toybox",
    )
    private val DESTRUCTIVE_VERBS = setOf(
        "rm", "rmdir", "shred", "unlink", "reboot", "poweroff", "shutdown", "halt", "mkfs",
        "wipefs", "dd",
    )

    fun classify(command: String): CommandRisk {
        val parts = command.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return CommandRisk.MODIFY
        return parts.maxBy { classifySegment(it).ordinal }.let { classifySegment(it) }
    }

    private fun classifySegment(segment: String): CommandRisk {
        if (hasFileRedirect(segment)) {
            val inner = classifyTokens(segment)
            return if (inner == CommandRisk.DESTRUCTIVE) inner else CommandRisk.MODIFY
        }
        return classifyTokens(segment)
    }

    private fun classifyTokens(segment: String): CommandRisk {
        val tokens = segment.split(WHITESPACE).filter { it.isNotEmpty() }
        val verb = tokens.firstOrNull { !it.startsWith("-") }?.substringAfterLast('/') ?: return CommandRisk.MODIFY
        val sub = tokens.dropWhile { it.startsWith("-") || it.substringAfterLast('/') != verb }
            .drop(1)
            .firstOrNull { !it.startsWith("-") }
        if (verb in DESTRUCTIVE_VERBS || verb.startsWith("mkfs.")) return CommandRisk.DESTRUCTIVE
        return when (verb) {
            "pm" -> pmRisk(sub)
            "am" -> amRisk(sub)
            "settings" -> settingsRisk(sub)
            "input", "screencap", "setprop" -> CommandRisk.MODIFY
            in READ_VERBS -> CommandRisk.READ
            else -> CommandRisk.MODIFY
        }
    }

    private fun pmRisk(sub: String?): CommandRisk = when (sub) {
        "list", "path", "dump" -> CommandRisk.READ
        "clear", "uninstall", "disable", "enable" -> CommandRisk.DESTRUCTIVE
        else -> CommandRisk.MODIFY
    }

    private fun amRisk(sub: String?): CommandRisk = when (sub) {
        "force-stop", "kill", "crash", "hang" -> CommandRisk.DESTRUCTIVE
        else -> CommandRisk.MODIFY
    }

    private fun settingsRisk(sub: String?): CommandRisk = when (sub) {
        "get", "list" -> CommandRisk.READ
        "delete" -> CommandRisk.DESTRUCTIVE
        else -> CommandRisk.MODIFY
    }

    private fun hasFileRedirect(segment: String): Boolean {
        return REDIRECT.containsMatchIn(segment) && !segment.contains(">&")
    }
}
