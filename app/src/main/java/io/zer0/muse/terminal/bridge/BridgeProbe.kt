package io.zer0.muse.terminal.bridge

import io.zer0.muse.terminal.TermuxChannel

/**
 * Reads Termux, resolves `rish`, and checks `id` through the existing [TermuxChannel].
 * Interactive shell startup files are not used.
 */
internal class BridgeProbe(private val channel: TermuxChannel) {
    suspend fun collect(): ChannelFacts {
        val installed = channel.isInstalled()
        val permission = installed && channel.hasPermission()
        if (!installed || !permission) {
            return ChannelFacts(
                termuxInstalled = installed,
                runCommandGranted = permission,
                home = TermuxBootstrap.DEFAULT_HOME,
                prefix = TermuxBootstrap.DEFAULT_PREFIX,
                rishPath = null,
                identity = ShellIdentity.Kind.UNKNOWN,
                termuxReady = false,
                detail = if (installed) "RUN_COMMAND" else "not-installed",
            )
        }
        return collectReady()
    }

    private suspend fun collectReady(): ChannelFacts {
        val homeResult = channel.exec(TermuxBootstrap.wrap(HOME_PROBE), PROBE_TIMEOUT_MS, null).toCommand()
        val homeKind = BridgeErrorClassifier.classify(homeResult)
        val (home, prefix) = parseHomePrefix(homeResult.stdout)
        if (homeKind != null) {
            return base(home, prefix, rishPath = null, identity = ShellIdentity.Kind.UNKNOWN, detail = homeKind.name)
        }
        val detection = TermuxBootstrap.wrap(RishLocator.detectionScript(home, prefix), home, prefix)
        val detected = channel.exec(detection, PROBE_TIMEOUT_MS, null).toCommand()
        val rish = RishLocator.parse(detected.stdout)
        if (rish == null) {
            return base(home, prefix, rishPath = null, identity = ShellIdentity.Kind.UNKNOWN, detail = "rish-missing")
        }
        val identityScript = TermuxBootstrap.wrap(NightzukuScript.identity(rish), home, prefix)
        val identityResult = channel.exec(identityScript, PROBE_TIMEOUT_MS, null).toCommand()
        val identity = ShellIdentity.kindOf(identityResult.stdout + "\n" + identityResult.stderr)
        val detail = BridgeErrorClassifier.classify(identityResult)?.name
        return base(home, prefix, rish, identity, detail)
    }

    private fun base(home: String, prefix: String, rishPath: String?, identity: ShellIdentity.Kind, detail: String?): ChannelFacts {
        return ChannelFacts(
            termuxInstalled = true,
            runCommandGranted = true,
            home = home,
            prefix = prefix,
            rishPath = rishPath,
            identity = identity,
            termuxReady = true,
            detail = detail,
        )
    }

    private fun parseHomePrefix(stdout: String): Pair<String, String> {
        val lines = stdout.lines().map { it.trim() }.filter { it.startsWith("/") }
        val home = lines.getOrNull(0) ?: TermuxBootstrap.DEFAULT_HOME
        val prefix = lines.getOrNull(1) ?: TermuxBootstrap.DEFAULT_PREFIX
        return home to prefix
    }

    private fun TermuxChannel.ExecResult.toCommand() = CommandResult(exitCode, stdout, stderr, error)

    data class ChannelFacts(
        val termuxInstalled: Boolean,
        val runCommandGranted: Boolean,
        val home: String,
        val prefix: String,
        val rishPath: String?,
        val identity: ShellIdentity.Kind,
        val termuxReady: Boolean,
        val detail: String?,
    )

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
        const val HOME_PROBE = "printf '%s\\n' \"\$HOME\" \"\$PREFIX\""
    }
}
