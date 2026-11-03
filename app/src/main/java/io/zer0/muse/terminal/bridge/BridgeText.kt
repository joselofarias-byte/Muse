package io.zer0.muse.terminal.bridge

/** Machine-readable first line plus a Spanish explanation. The command text is never included. */
internal object BridgeText {
    fun denied(kind: BridgeErrorKind, detail: String): String = "BRIDGE error=$kind\n$detail"

    fun denied(decision: NightzukuGate.Decision.Deny): String = denied(decision.kind, decision.detail)

    fun job(job: BridgeJob): String = buildString {
        append("BRIDGE job=").append(job.id)
        append(" state=").append(job.state.name)
        append(" channel=").append(job.channel.name)
        append(" risk=").append(job.risk.name)
        job.output.exitCode?.let { append(" exit=").append(it) }
        job.errorKind?.let { append(" error=").append(it.name) }
        append('\n')
        append(explain(job))
        if (job.output.stderr.isNotBlank()) {
            append("\n--- stderr ---\n").append(job.output.stderr)
        }
        append("\n--- stdout ---\n")
        append(if (job.output.stdout.isBlank()) "(sin salida)" else job.output.stdout)
    }

    fun diagnose(snapshot: BridgeSnapshot): String = buildString {
        append("BRIDGE diagnose\n")
        append("termux_installed=").append(snapshot.termuxInstalled).append('\n')
        append("run_command=").append(snapshot.runCommandGranted).append('\n')
        append("termux_ready=").append(snapshot.termuxReady).append('\n')
        append("home=").append(snapshot.home).append('\n')
        append("prefix=").append(snapshot.prefix).append('\n')
        append("rish=").append(snapshot.rishPath ?: "missing").append('\n')
        append("identity=").append(ShellIdentity.summary(snapshot.identity)).append('\n')
        append("nightzuku_package=").append(snapshot.device.nightzukuPackage).append('\n')
        append("sdk=").append(snapshot.device.sdkInt).append('\n')
        append("notifications=").append(snapshot.device.notificationsGranted).append('\n')
        append("foreground_service=dataSync\n")
        append("auth_modify=").append(snapshot.device.modifyGranted).append('\n')
        append("auth_destructive=").append(snapshot.device.destructiveGranted).append('\n')
        append("active_jobs=").append(snapshot.device.activeJobs).append('\n')
        snapshot.detail?.let { append("detail=").append(OutputSanitizer.clean(it)).append('\n') }
    }

    private fun explain(job: BridgeJob): String = when (job.errorKind) {
        BridgeErrorKind.PERMISSION_DENIED ->
            "Permiso denegado. Revisa RUN_COMMAND en Termux."
        BridgeErrorKind.TIMEOUT -> "Tiempo de espera agotado."
        BridgeErrorKind.SERVICE_LOST ->
            "Se perdió el servicio. Termux o el servicio en primer plano no respondieron."
        BridgeErrorKind.IDENTITY_MISMATCH ->
            "Nightzuku no está habilitado: hace falta uid=2000(shell) y no se usa root."
        BridgeErrorKind.NOT_READY -> "Termux o rish todavía no están listos."
        BridgeErrorKind.CANCELLED -> "Trabajo cancelado."
        BridgeErrorKind.AUTHORIZATION_REQUIRED -> "Falta autorización explícita para esta clase de riesgo."
        BridgeErrorKind.SECRET_REJECTED -> "El comando fue rechazado porque contenía un secreto."
        BridgeErrorKind.BOUNDARY_REJECTED -> "El comando fue rechazado por el límite de seguridad."
        BridgeErrorKind.FAILED -> "El comando no llegó a completarse."
        null -> if (job.state == BridgeJobState.SUCCEEDED) "Comando terminado." else "Trabajo en curso."
    }
}
