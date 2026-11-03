package io.zer0.muse.terminal.bridge

/**
 * Scripts that run inside Termux. The user command is passed only as a single quoted argument to
 * `rish -c`. The pid file receives a process id and nothing else — never the command text.
 */
internal object NightzukuScript {
    private val SAFE_ID = Regex("[^A-Za-z0-9_-]")
    fun pidFile(prefix: String, jobId: String): String {
        val safeId = jobId.replace(SAFE_ID, "")
        return prefix.trimEnd('/') + "/tmp/muse-bridge-" + safeId + ".pid"
    }

    fun identity(rishPath: String): String = buildString {
        appendRishGuard(rishPath)
        append("id_out=\$(\"\$rish\" -c 'id' 2>&1) || { echo MUSE_IDENTITY_FAIL; printf '%s\\n' \"\$id_out\"; exit 126; }\n")
        append("printf '%s\\n' \"\$id_out\"\n")
    }

    fun run(rishPath: String, pidFile: String, userCommand: String): String = buildString {
        val pid = ShellQuote.single(pidFile)
        val command = ShellQuote.single(userCommand)
        append("set -m\n")
        append("pidfile=").append(pid).append('\n')
        append("echo \$\$ > \"\$pidfile\"\n")
        append("trap 'rm -f \"\$pidfile\"' EXIT\n")
        appendRishGuard(rishPath)
        append("id_out=\$(\"\$rish\" -c 'id' 2>&1) || { echo MUSE_IDENTITY_FAIL; printf '%s\\n' \"\$id_out\"; exit 126; }\n")
        append("case \"\$id_out\" in\n")
        append("  *'uid=0(root)'*) echo MUSE_IDENTITY_ROOT; exit 126 ;;\n")
        append("  *'uid=2000(shell)'*) ;;\n")
        append("  *) echo MUSE_IDENTITY_MISMATCH; printf '%s\\n' \"\$id_out\"; exit 126 ;;\n")
        append("esac\n")
        append("\"\$rish\" -c ").append(command).append('\n')
    }

    fun termuxWrapped(pidFile: String, userCommand: String): String = buildString {
        val pid = ShellQuote.single(pidFile)
        append("set -m\n")
        append("pidfile=").append(pid).append('\n')
        append("echo \$\$ > \"\$pidfile\"\n")
        append("trap 'rm -f \"\$pidfile\"' EXIT\n")
        append(userCommand).append('\n')
    }

    fun kill(pidFile: String): String = buildString {
        val pid = ShellQuote.single(pidFile)
        append("pidfile=").append(pid).append('\n')
        append("if [ ! -f \"\$pidfile\" ]; then exit 0; fi\n")
        append("pid=\$(cat \"\$pidfile\" 2>/dev/null) || exit 0\n")
        append("case \"\$pid\" in ''|*[!0-9]*) exit 0 ;; esac\n")
        append("kill -TERM -- \"-\$pid\" 2>/dev/null || kill -TERM \"\$pid\" 2>/dev/null || true\n")
    }

    private fun StringBuilder.appendRishGuard(rishPath: String) {
        append("rish=").append(ShellQuote.single(rishPath)).append('\n')
        append("if [ ! -x \"\$rish\" ]; then echo MUSE_RISH_MISSING; exit 127; fi\n")
    }
}
