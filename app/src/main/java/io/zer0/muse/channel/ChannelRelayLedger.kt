package io.zer0.muse.channel

import java.security.MessageDigest

object ChannelRelayLedger {

    const val ECHO_WINDOW_MS = 90_000L

    const val MAX_RELAY_PER_HOUR = 10

    const val MAX_RELAY_PER_TURN = 2

    private const val HOUR_MS = 3_600_000L

    private data class EchoKey(val channelId: String, val textDigest: String)

    private val lock = Any()
    private val echoes = HashMap<EchoKey, Long>()
    private val sends = ArrayDeque<Long>()
    private var turnRelays = 0
    private var pendingRelays = 0

    fun beginTurn() {
        synchronized(lock) { turnRelays = 0 }
    }

    fun tryReserveSend(now: Long): Boolean = synchronized(lock) {
        prune(now)
        if (turnRelays + pendingRelays >= MAX_RELAY_PER_TURN) return false
        if (sends.size + pendingRelays >= MAX_RELAY_PER_HOUR) return false
        pendingRelays++
        true
    }

    // Las reservas cuentan mientras la red está en curso; los fallos no consumen cuota.
    fun releaseSend() {
        synchronized(lock) {
            if (pendingRelays > 0) pendingRelays--
        }
    }

    fun recordSent(channelId: String, text: String, now: Long) {
        synchronized(lock) {
            if (pendingRelays > 0) pendingRelays--
            turnRelays++
            sends.addLast(now)
            echoes[EchoKey(channelId, digest(text))] = now
            prune(now)
        }
    }

    fun isEcho(channelId: String, text: String, now: Long): Boolean {
        synchronized(lock) {
            prune(now)
            val sentAt = echoes[EchoKey(channelId, digest(text))] ?: return false
            return now - sentAt <= ECHO_WINDOW_MS
        }
    }

    fun reset() {
        synchronized(lock) {
            echoes.clear()
            sends.clear()
            turnRelays = 0
            pendingRelays = 0
        }
    }

    private fun prune(now: Long) {
        sends.removeAll { now - it > HOUR_MS }
        echoes.entries.removeAll { now - it.value > ECHO_WINDOW_MS }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.trim().toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
