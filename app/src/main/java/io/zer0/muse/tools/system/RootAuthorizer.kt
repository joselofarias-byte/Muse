package io.zer0.muse.tools.system

import io.zer0.common.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P3-3: Root Autorizador — Compruebe si el dispositivo tiene root,Y brinda la capacidad de ejecutar comandos con privilegios de root.
 *
 * El canal con mayor autoridad entre el enrutamiento de tres canales.(No requiere instalación Shizuku,Usar directamente su).
 * Método de detección:
 *  1. Compruebe si su binario existe en la ruta común
 *  2. ejecutar `su -v` Verificar que su esté disponible(Ventana emergente de autorización de root)
 *
 * seguro:
 *  - Consultar disponibilidad únicamente,No tomar la iniciativa de elevar derechos.
 *  - Esta categoría sólo proporciona [execute] primitivo;La selección del canal la realiza la persona que llama.(automation/executors/ShellExecutor)decidir
 *  - Root El riesgo del canal es mayor,Solo se usa como degradación cuando Shizuku no está disponible
 */
class RootAuthorizer {

    companion object {
        private const val TAG = "RootAuthorizer"

        /** su Tiempo de espera de la sonda:root La ventana emergente del administrador no bloquea la interfaz de usuario cuando nadie hace clic en ella. */
        private const val SU_PROBE_TIMEOUT_MS = 8_000L

        /** su Rutas de instalación comunes para binarios. */
        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/sbin/su",
            "/system/bin/.ext/.su",
        )
    }

    /**
     * Detecta rápidamente si el equipo"puede tener root"(No abrir la ventana emergente de autorización).
     *
     * v2.0: KernelSU departamento(Incluyendo Next/SukiSU y otras sucursales)No expuesto en el sistema de archivos en modo GKI/LKM su
     * binario —— El sucompat de la capa del kernel intercepta directamente los pares de aplicaciones "su" solicitud de ejecución,entonces File.exists() Extraviado.
     * Añadir aquí /data/adb(root Directorio de datos del proyecto,El equipo ordinario no existe.)como preselección;
     * Nota:La existencia de su / la existencia de /data/adb no significa que haya sido autorizado.,necesidad [checkPermission] detección real.
     */
    fun isAvailable(): Boolean = SU_PATHS.any { File(it).exists() } || File("/data/adb").exists()

    /**
     * Verificar la autorización de root:ejecutar `su -c id`,La salida contiene uid=0 Se considera autorizado.
     *
     * v2.0: Detectar desde `su -v` Cambiar a `su -c id` —— KernelSU Es posible que la solución raíz del kernel no sea compatible `-v`,
     * Las viejas detecciones juzgarán mal"Ninguno root";`-c id` Es un calibre común para Magisk/KernelSU/APatch.
     *
     * @return true Indica que root está disponible y autorizado
     */
    fun checkPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(SU_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                Logger.w(TAG, "su Tiempo de espera de la sonda(${SU_PROBE_TIMEOUT_MS}ms),Tratado como no autorizado")
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val granted = process.exitValue() == 0 && output.contains("uid=0")
            if (!granted) {
                Logger.w(TAG, "su Detección no obtenida uid=0: exit=${process.exitValue()}, out=${output.take(160)}")
            }
            granted
        } catch (e: Exception) {
            // Tolerancia a fallos necesaria:su La ejecución puede generar una excepción.(Permiso denegado/Falta el archivo/tiempo de espera),registrando
            Logger.w(TAG, "root Error en la verificación del permiso: ${e.message}")
            false
        }
    }

    /**
     * Ejecute el comando con privilegios de root.
     * @param command cadena de comando para ejecutar
     * @return [RootExecResult] Contiene código de salida y resultados.
     */
    fun execute(command: String): RootExecResult {
        if (!checkPermission()) return RootExecResult(-1, "", "root No autorizado")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val err = process.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            val exitCode = process.waitFor()
            RootExecResult(exitCode, out, err)
        } catch (e: Exception) {
            Logger.e(TAG, "root La ejecución falló: ${e.message}", e)
            RootExecResult(-1, "", e.message ?: "excepción de ejecución")
        }
    }

    /** Root Resultado de la ejecución del comando. */
    data class RootExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
