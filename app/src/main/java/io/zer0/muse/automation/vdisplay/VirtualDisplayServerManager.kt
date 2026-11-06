package io.zer0.muse.automation.vdisplay

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.executors.ShellExecutor
import io.zer0.muse.vdproto.IVirtualDisplayService
import io.zer0.muse.vdproto.VdContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * v2.2.1 pantalla virtual:Gestión del ciclo de vida del servidor.(Implementar/iniciar/esperar carpeta/reutilizar).
 *
 * enlace de inicio(Consulte el conjunto mínimo de notas de desmontaje v1):
 *  1. assets Escritura base64 fragmentada en vd-server.jar /data/local/tmp(shell/root identidad,guardián);
 *  2. pkill instancia antigua → `CLASSPATH=<jar> app_process / io.zer0.muse.vdserver.Main <hostPkg> &`;
 *  3. Sondear el registro y otras carpetas de la mano(10s);El servidor se suicida en 15 segundos,Las llamadas posteriores se reinician automáticamente.
 *
 * puerta de permiso(v2.2.1 doble engranaje):Reutilizar canales de automatización [AutomationManager.execTiered] —— Shizuku prioridad,
 * Root Llega al fondo de esto. Demostración del equipo:shell uid con root(adb) Bajo dos identidades,Pantalla de construcción/captura de pantalla/Pasa toda la cadena de inicio en pantalla.
 */
class VirtualDisplayServerManager(
    private val context: Context,
    private val shell: AutomationManager,
) {
    private val startupMutex = Mutex()

    /** La última garantía fue exitosa. displayId(Reutilización de herramientas en llamadas). */
    @Volatile
    var lastDisplayId: Int = -1

    @Volatile
    private var handoffToken: String? = null

    /** Estado del canal de permiso:Regresar "ready(Shizuku)" / "ready(Root)" / Motivo de la indisponibilidad. */
    suspend fun channelState(): String {
        var state = shell.permissionState.value
        if (!state.shellEnabled && !state.rootEnabled) {
            state = shell.refreshPermissions()
        }
        return when {
            state.shellEnabled -> "ready(Shizuku)"
            state.rootEnabled -> "ready(Root)"
            else -> "Requiere acceso Shizuku o Root(ajustes→Autorización del asistente de permisos)"
        }
    }

    /** Asegúrese de que el servidor esté en línea,proxy de retorno;El fracaso conlleva una razón legible. */
    suspend fun ensureStarted(): Result<IVirtualDisplayService> = withContext(Dispatchers.IO) {
        existingLiveProxy()?.let { return@withContext Result.success(it) }

        startupMutex.withLock {
            existingLiveProxy()?.let { return@withLock Result.success(it) }

            var state = shell.permissionState.value
            if (!state.shellEnabled && !state.rootEnabled) {
                // La caché puede estar caducada(Recién autorizado/Cancelar),Compruébalo y decide de nuevo.
                state = shell.refreshPermissions()
            }
            if (!state.shellEnabled && !state.rootEnabled) {
                return@withLock Result.failure(
                    IllegalStateException("La pantalla virtual requiere acceso Shizuku o Root(ajustes→Autorización del asistente de permisos)"),
                )
            }
            if (!deployJar()) {
                return@withLock Result.failure(IllegalStateException("Error en la implementación del servidor de pantalla virtual(assets Falta o escritura rechazada)"))
            }

            val token = UUID.randomUUID().toString()
            handoffToken = token
            VirtualDisplayBinderRegistry.setExpectedHandoffToken(token)
            VirtualDisplayBinderRegistry.update(null)
            exec("pkill -f ${VdContract.SERVER_KILL_PATTERN}")
            val launch = exec(launchCommand(token))
            if (launch.exitCode != 0) {
                Logger.w(TAG, "Código de salida del comando de inicio del servidor ${launch.exitCode}: ${launch.output.take(200)}")
            }

            val proxy = awaitBinder()
                ?: run {
                    handoffToken = null
                    VirtualDisplayBinderRegistry.clearHandoffToken()
                    return@withLock Result.failure(
                        IllegalStateException(
                            "El servidor de pantalla virtual no está presente ${AWAIT_TIMEOUT_MS / 1000}s Listo dentro" +
                                "(Ver detalles /data/local/tmp/muse-vd-server.log)",
                        ),
                    )
                }
            Logger.i(TAG, "El servidor de pantalla virtual está listo")
            Result.success(proxy)
        }
    }

    /** Borrar carpetas caducadas y devolver los servidores proxy disponibles actualmente(Empezar sin gatillo). */
    fun existingLiveProxy(): IVirtualDisplayService? {
        val binder =
            VirtualDisplayBinderRegistry.current()
                ?: run {
                    clearCachedDisplayId()
                    return null
                }
        val proxy = IVirtualDisplayService.Stub.asInterface(binder)
        return try {
            if (proxy != null && proxy.isAlive) {
                proxy
            } else {
                VirtualDisplayBinderRegistry.update(null)
                clearCachedDisplayId()
                null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error en la detección del lado del servidor: ${e.message}")
            VirtualDisplayBinderRegistry.update(null)
            clearCachedDisplayId()
            null
        }
    }

    private fun clearCachedDisplayId() {
        if (lastDisplayId >= 0) {
            lastDisplayId = -1
        }
        handoffToken = null
        VirtualDisplayBinderRegistry.clearHandoffToken()
    }

    /** en equipo actualmente disponible(Shizuku prioridad,Root Ten todo en cuenta)ejecutar un comando(implementar/Empezar a compartir). */
    suspend fun exec(command: String): ShellExecutor.ExecDetail = shell.execTiered(command)?.second
        ?: ShellExecutor.ExecDetail(-1, "No hay ningún canal de acceso al shell disponible")

    // ── interno ────────────────────────────────────────────────────────────────

    private suspend fun deployJar(): Boolean {
        val bytes =
            runCatching {
                context.assets.open(ASSET_JAR_PATH).use { it.readBytes() }
            }.getOrElse {
                Logger.w(TAG, "Falta el vd-server.jar integrado: ${it.message}")
                return false
            }

        // Implementado y de tamaño consistente → Saltar escrituras duplicadas
        val stat = exec("stat -c %s ${VdContract.SERVER_JAR_PATH}")
        if (stat.exitCode == 0 && stat.output.trim() == bytes.size.toString()) {
            return true
        }

        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        var offset = 0
        var append = false
        while (offset < encoded.length) {
            val chunk = encoded.substring(offset, minOf(offset + DEPLOY_CHUNK_CHARS, encoded.length))
            val redirect = if (append) ">>" else ">"
            val result = exec("echo -n $chunk | base64 -d $redirect ${VdContract.SERVER_JAR_PATH}")
            if (result.exitCode != 0) {
                Logger.w(TAG, "jar Error de escritura bloqueada: ${result.output.take(200)}")
                return false
            }
            append = true
            offset += DEPLOY_CHUNK_CHARS
        }
        val verify = exec("stat -c %s ${VdContract.SERVER_JAR_PATH}")
        val ok = verify.exitCode == 0 && verify.output.trim() == bytes.size.toString()
        if (!ok) {
            Logger.w(TAG, "jar Error en la verificación de escritura: ${verify.output.trim()} != ${bytes.size}")
        }
        return ok
    }

    private fun launchCommand(token: String): String {
        val host = context.packageName
        return "CLASSPATH=${VdContract.SERVER_JAR_PATH} app_process / ${VdContract.SERVER_MAIN_CLASS} " +
            "$host $token > /data/local/tmp/muse-vd-server.log 2>&1 &"
    }

    private suspend fun awaitBinder(): IVirtualDisplayService? {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            existingLiveProxy()?.let { return it }
            delay(AWAIT_POLL_MS)
        }
        return null
    }

    companion object {
        private const val TAG = "VdServerManager"

        /** La ruta del jar del servidor integrado en los activos(Copiado por syncVdServerJar durante la construcción). */
        private const val ASSET_JAR_PATH = "vd/vd-server.jar"

        private const val AWAIT_TIMEOUT_MS = 10_000L
        private const val AWAIT_POLL_MS = 100L
        private const val DEPLOY_CHUNK_CHARS = 60_000
    }
}
