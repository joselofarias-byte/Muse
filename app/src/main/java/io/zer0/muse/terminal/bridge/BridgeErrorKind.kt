package io.zer0.muse.terminal.bridge

/**
 * Stable failure classes. Permission, timeout and service loss stay distinct so a caller can tell
 * them apart without parsing prose.
 */
internal enum class BridgeErrorKind {
    PERMISSION_DENIED,
    TIMEOUT,
    SERVICE_LOST,
    IDENTITY_MISMATCH,
    AUTHORIZATION_REQUIRED,
    SECRET_REJECTED,
    NOT_READY,
    BOUNDARY_REJECTED,
    CANCELLED,
    FAILED,
}
