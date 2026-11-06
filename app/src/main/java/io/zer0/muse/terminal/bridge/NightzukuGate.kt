package io.zer0.muse.terminal.bridge

import io.zer0.muse.tools.ShellCommandBoundary

/**
 * Decides whether a command may start.
 *
 * Sensitive Nightzuku actions need an explicit grant stored by the settings screen. The model
 * cannot grant itself. Root, disk format, and wiping the device root stay blocked even with a grant.
 */
internal object NightzukuGate {
    private val SPACES = Regex("\\s+")
    private val DD_DEVICE = Regex("\\bof=/dev/")
    private val RM_ROOT = Regex("""\brm\b[^\n]*\s+(/|/\*|/(system|data|sdcard|storage))(\s|$)""")
    data class Consent(val modify: Boolean, val destructive: Boolean)

    sealed class Decision {
        data class Allow(val risk: CommandRisk) : Decision()
        data class Deny(val kind: BridgeErrorKind, val detail: String) : Decision()
    }

    fun termux(command: String): Decision {
        secretOrBoundary(command)?.let { return it }
        return Decision.Allow(CommandRiskClassifier.classify(command))
    }

    fun nightzuku(command: String, consent: Consent, identity: ShellIdentity.Kind): Decision {
        secretOrBoundary(command)?.let { return it }
        if (identity == ShellIdentity.Kind.ROOT || identity == ShellIdentity.Kind.OTHER) {
            return Decision.Deny(
                BridgeErrorKind.IDENTITY_MISMATCH,
                "Nightzuku solo se habilita con uid=2000(shell). Identidad actual: ${ShellIdentity.summary(identity)}.",
            )
        }
        val risk = CommandRiskClassifier.classify(command)
        val allowed = when (risk) {
            CommandRisk.READ -> true
            CommandRisk.MODIFY -> consent.modify
            CommandRisk.DESTRUCTIVE -> consent.destructive
        }
        if (allowed) return Decision.Allow(risk)
        val kindName = if (risk == CommandRisk.DESTRUCTIVE) "destructiva" else "de modificación"
        return Decision.Deny(
            BridgeErrorKind.AUTHORIZATION_REQUIRED,
            "La acción $kindName necesita autorización explícita en Ajustes → Herramientas → Termux y Nightzuku.",
        )
    }

    private fun secretOrBoundary(command: String): Decision.Deny? {
        if (SecretGuard.containsSecret(command)) {
            return Decision.Deny(
                BridgeErrorKind.SECRET_REJECTED,
                "El comando contiene un secreto. No se ejecuta y no se copia a logs ni a archivos temporales.",
            )
        }
        hardFloor(command)?.let { return Decision.Deny(BridgeErrorKind.BOUNDARY_REJECTED, it) }
        val boundary = ShellCommandBoundary.forbidReason(command, workspaceDir = null)
        if (boundary != null) return Decision.Deny(BridgeErrorKind.BOUNDARY_REJECTED, boundary)
        return null
    }

    private fun hardFloor(command: String): String? {
        val compact = command.replace(SPACES, "")
        val verb = command.trim().substringBefore(' ').substringAfterLast('/')
        return when {
            compact.contains(":(){:") -> "fork bomb bloqueado"
            verb in setOf("su", "sudo", "doas") -> "sin root: $verb está bloqueado"
            verb == "mkfs" || verb.startsWith("mkfs.") -> "formatear discos está bloqueado"
            verb == "dd" && DD_DEVICE.containsMatchIn(command) -> "dd sobre un dispositivo está bloqueado"
            RM_ROOT.containsMatchIn(command) ->
                "borrar la raíz o una partición del sistema está bloqueado"
            else -> null
        }
    }
}
