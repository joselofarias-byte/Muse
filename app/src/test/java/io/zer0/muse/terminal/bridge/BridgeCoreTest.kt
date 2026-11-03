package io.zer0.muse.terminal.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCoreTest {
    private val home = "/data/data/com.termux/files/home"
    private val prefix = "/data/data/com.termux/files/usr"
    private val consent = NightzukuGate.Consent(modify = false, destructive = false)

    @Test
    fun bootstrapExportsPathWithoutInteractiveStartupFiles() {
        val wrapped = TermuxBootstrap.wrap("pkg list-installed", home, prefix)
        val marker = "\$HOME/bin:\$PREFIX/bin"
        assertTrue(wrapped, wrapped.contains(marker))
        assertFalse(wrapped.contains(".bashrc"))
        assertFalse(wrapped.contains(".profile"))
        assertTrue(wrapped.trimEnd().endsWith("pkg list-installed"))
    }

    @Test
    fun rishCandidatesPreferHomeBin() {
        val candidates = RishLocator.candidates(home, prefix)
        assertEquals("$home/bin/rish", candidates.first())
        val script = RishLocator.detectionScript(home, prefix)
        assertTrue(script.indexOf("$home/bin/rish") < script.indexOf("$prefix/bin/rish"))
        assertEquals("$home/bin/rish", RishLocator.parse("noise\n$home/bin/rish\n"))
        assertNull(RishLocator.parse("rish not found"))
    }

    @Test
    fun shellIdentityAcceptsOnlyUid2000() {
        assertEquals(ShellIdentity.Kind.SHELL, ShellIdentity.kindOf("uid=2000(shell) gid=2000(shell)"))
        assertEquals(ShellIdentity.Kind.ROOT, ShellIdentity.kindOf("uid=0(root) gid=0(root)"))
        assertEquals(ShellIdentity.Kind.OTHER, ShellIdentity.kindOf("uid=10295(u0_a295)"))
        assertEquals(ShellIdentity.Kind.UNKNOWN, ShellIdentity.kindOf(""))
    }

    @Test
    fun riskClassesCoverReadModifyAndDestructive() {
        assertEquals(CommandRisk.READ, CommandRiskClassifier.classify("dumpsys window"))
        assertEquals(CommandRisk.READ, CommandRiskClassifier.classify("settings get system screen_off_timeout"))
        assertEquals(CommandRisk.READ, CommandRiskClassifier.classify("logcat -d -t 20"))
        assertEquals(CommandRisk.MODIFY, CommandRiskClassifier.classify("am start -n com.example/.Main"))
        assertEquals(CommandRisk.MODIFY, CommandRiskClassifier.classify("input tap 10 10"))
        assertEquals(CommandRisk.MODIFY, CommandRiskClassifier.classify("screencap -p /sdcard/screen.png"))
        assertEquals(CommandRisk.DESTRUCTIVE, CommandRiskClassifier.classify("settings delete global foo"))
        assertEquals(CommandRisk.DESTRUCTIVE, CommandRiskClassifier.classify("rm notes.txt"))
        assertEquals(CommandRisk.DESTRUCTIVE, CommandRiskClassifier.classify("echo hi && settings delete global foo"))
        assertEquals(CommandRisk.MODIFY, CommandRiskClassifier.classify("some-unknown-tool"))
    }

    @Test
    fun secretsAreRejectedAndRedacted() {
        assertTrue(SecretGuard.containsSecret("curl -H 'Authorization: Bearer abcdefghijklmnop' https://example"))
        assertTrue(SecretGuard.containsSecret("api_key=supersecretvalue"))
        assertFalse(SecretGuard.containsSecret("settings get secure android_id"))
        val redacted = SecretGuard.redact("token=abc123")
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun nightzukuRequiresShellIdentityAndExplicitGrant() {
        val read = NightzukuGate.nightzuku("dumpsys window", consent, ShellIdentity.Kind.SHELL)
        assertTrue(read is NightzukuGate.Decision.Allow)

        val modify = NightzukuGate.nightzuku("am start -n com.example/.Main", consent, ShellIdentity.Kind.SHELL)
        assertEquals(BridgeErrorKind.AUTHORIZATION_REQUIRED, (modify as NightzukuGate.Decision.Deny).kind)

        val granted = NightzukuGate.nightzuku(
            "input tap 1 1",
            NightzukuGate.Consent(modify = true, destructive = false),
            ShellIdentity.Kind.SHELL,
        )
        assertTrue(granted is NightzukuGate.Decision.Allow)

        val destructive = NightzukuGate.nightzuku(
            "rm notes.txt",
            NightzukuGate.Consent(modify = true, destructive = false),
            ShellIdentity.Kind.SHELL,
        )
        assertEquals(BridgeErrorKind.AUTHORIZATION_REQUIRED, (destructive as NightzukuGate.Decision.Deny).kind)

        val root = NightzukuGate.nightzuku("id", consent, ShellIdentity.Kind.ROOT)
        assertEquals(BridgeErrorKind.IDENTITY_MISMATCH, (root as NightzukuGate.Decision.Deny).kind)

        val su = NightzukuGate.nightzuku("su", NightzukuGate.Consent(true, true), ShellIdentity.Kind.SHELL)
        assertEquals(BridgeErrorKind.BOUNDARY_REJECTED, (su as NightzukuGate.Decision.Deny).kind)
    }

    @Test
    fun nightzukuScriptChecksIdentityAndDoesNotStoreTheCommand() {
        val script = NightzukuScript.run("$home/bin/rish", "$prefix/tmp/muse-bridge-1.pid", "dumpsys window")
        assertTrue(script.contains("uid=2000(shell)"))
        assertTrue(script.contains("uid=0(root)"))
        assertTrue(script.contains("$home/bin/rish"))
        assertTrue(script.contains("echo \$\$ >"))
        assertFalse(script.contains("dumpsys window >"))
        assertTrue(script.contains("rish\" -c 'dumpsys window'") || script.contains("-c 'dumpsys window'"))
    }

    @Test
    fun channelErrorsStayDistinct() {
        assertEquals(
            BridgeErrorKind.PERMISSION_DENIED,
            BridgeErrorClassifier.classifyChannel("缺少 Termux 调用权限"),
        )
        assertEquals(
            BridgeErrorKind.TIMEOUT,
            BridgeErrorClassifier.classifyChannel("等待结果超时(60s)"),
        )
        assertEquals(
            BridgeErrorKind.SERVICE_LOST,
            BridgeErrorClassifier.classifyChannel("启动 Termux 服务失败: Not allowed to start service"),
        )
        assertEquals(
            BridgeErrorKind.PERMISSION_DENIED,
            BridgeErrorClassifier.classify(CommandResult(1, "", "ls: Permission denied", null)),
        )
        assertEquals(
            BridgeErrorKind.IDENTITY_MISMATCH,
            BridgeErrorClassifier.classify(CommandResult(126, "MUSE_IDENTITY_ROOT\n", "", null)),
        )
        assertNull(BridgeErrorClassifier.classify(CommandResult(1, "", "no such file", null)))
    }
}
