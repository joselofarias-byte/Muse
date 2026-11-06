package io.zer0.muse.terminal.bridge

internal data class BridgeDeviceFacts(
    val nightzukuPackage: Boolean,
    val sdkInt: Int,
    val notificationsGranted: Boolean,
    val modifyGranted: Boolean,
    val destructiveGranted: Boolean,
    val activeJobs: Int,
)

internal data class BridgeSnapshot(
    val termuxInstalled: Boolean,
    val runCommandGranted: Boolean,
    val termuxReady: Boolean,
    val home: String,
    val prefix: String,
    val rishPath: String?,
    val identity: ShellIdentity.Kind,
    val detail: String?,
    val device: BridgeDeviceFacts,
)
