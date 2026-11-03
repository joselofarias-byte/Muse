package io.zer0.muse.terminal.bridge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.terminal.TermuxChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeProbeTest {
    private val channel: TermuxChannel = mockk()

    @Test
    fun missingPermissionDoesNotRunACommand() = runBlocking {
        every { channel.isInstalled() } returns true
        every { channel.hasPermission() } returns false
        val facts = BridgeProbe(channel).collect()
        assertFalse(facts.runCommandGranted)
        assertEquals("RUN_COMMAND", facts.detail)
        coVerify(exactly = 0) { channel.exec(any(), any(), any()) }
    }

    @Test
    fun probeResolvesHomeRishAndShellUid() = runBlocking {
        every { channel.isInstalled() } returns true
        every { channel.hasPermission() } returns true
        coEvery { channel.exec(any(), any(), any()) } answers {
            val command = firstArg<String>()
            when {
                " -c 'id'" in command -> TermuxChannel.ExecResult(0, "uid=2000(shell) gid=2000(shell)", "", null)
                command.contains("/bin/rish") -> TermuxChannel.ExecResult(
                    0,
                    "/data/data/com.termux/files/home/bin/rish\n",
                    "",
                    null,
                )
                else -> TermuxChannel.ExecResult(
                    0,
                    "/data/data/com.termux/files/home\n/data/data/com.termux/files/usr\n",
                    "",
                    null,
                )
            }
        }
        val facts = BridgeProbe(channel).collect()
        assertEquals("/data/data/com.termux/files/home", facts.home)
        assertEquals("/data/data/com.termux/files/usr", facts.prefix)
        assertEquals("/data/data/com.termux/files/home/bin/rish", facts.rishPath)
        assertEquals(ShellIdentity.Kind.SHELL, facts.identity)
        assertNull(facts.detail)
    }
}
