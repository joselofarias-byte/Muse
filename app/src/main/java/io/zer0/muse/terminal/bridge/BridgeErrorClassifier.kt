package io.zer0.muse.terminal.bridge

/** Maps a finished command result onto a bridge error. A null kind means the command itself finished. */
internal object BridgeErrorClassifier {
    fun classify(result: CommandResult): BridgeErrorKind? {
        val text = result.stdout + "\n" + result.stderr
        val fromPermission = result.exitCode != 0 && "permission denied" in text.lowercase()
        return when {
            classifyChannel(result.error) != null -> classifyChannel(result.error)
            !result.error.isNullOrBlank() -> BridgeErrorKind.FAILED
            classifyMarker(text) != null -> classifyMarker(text)
            fromPermission -> BridgeErrorKind.PERMISSION_DENIED
            else -> null
        }
    }

    fun classifyChannel(error: String?): BridgeErrorKind? {
        if (error.isNullOrBlank()) return null
        val text = error.lowercase()
        return when {
            "permission" in text || "权限" in text -> BridgeErrorKind.PERMISSION_DENIED
            "timeout" in text || "超时" in text -> BridgeErrorKind.TIMEOUT
            "not allowed to start service" in text || "启动 termux" in text || "后台" in text ->
                BridgeErrorKind.SERVICE_LOST
            else -> null
        }
    }

    fun classifyMarker(text: String): BridgeErrorKind? = when {
        "MUSE_RISH_MISSING" in text -> BridgeErrorKind.NOT_READY
        "MUSE_IDENTITY_ROOT" in text || "MUSE_IDENTITY_MISMATCH" in text || "MUSE_IDENTITY_FAIL" in text ->
            BridgeErrorKind.IDENTITY_MISMATCH
        else -> null
    }
}
