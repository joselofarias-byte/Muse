package io.zer0.muse.terminal.bridge

/**
 * Nightzuku is enabled only for `uid=2000(shell)`. Root is rejected: this bridge does not use su.
 */
internal object ShellIdentity {
    enum class Kind {
        SHELL,
        ROOT,
        OTHER,
        UNKNOWN,
    }

    fun kindOf(idOutput: String): Kind = when {
        idOutput.contains("uid=0(root)") -> Kind.ROOT
        idOutput.contains("uid=2000(shell)") -> Kind.SHELL
        idOutput.contains("uid=") -> Kind.OTHER
        else -> Kind.UNKNOWN
    }

    fun summary(kind: Kind): String = when (kind) {
        Kind.SHELL -> "uid=2000(shell)"
        Kind.ROOT -> "uid=0(root)"
        Kind.OTHER -> "uid!=2000"
        Kind.UNKNOWN -> "unknown"
    }
}
