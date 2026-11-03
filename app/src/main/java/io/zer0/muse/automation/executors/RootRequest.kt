package io.zer0.muse.automation.executors

/**
 * Root Pura lógica de solicitud de autorización. —— Construcción de comandos, juicio de salida y mapeo de motivos de falla.
 *
 * [RootExecutor.requestRootAccess] Únicamente responsable de los procesos secundarios y la orquestación del tiempo de espera.;Todo aquí no toca Android/proceso
 * función pura,La prueba única cubre cada ruta de falla en consecuencia.,No es necesario ejecutar realmente `su`(root La ventana emergente no se puede reproducir en el entorno de prueba.).
 */

/** Root Motivo fallido de la solicitud de autorización,UI Esto da una pista legible. */
enum class RootRequestFailure {
    /** El dispositivo no tiene su binario,No activará la ventana emergente del administrador raíz,Tampoco se puede obtener la raíz. */
    NO_SU_BINARY,

    /** Esperando que expire el tiempo de autorización del administrador raíz(La ventana emergente no ha sido procesada.). */
    TIMEOUT,

    /** El usuario rechaza la autorización,o su termina con un código de salida distinto de 0. */
    DENIED,

    /** Comienza el proceso hijo/Fallos sin categoría, como excepciones de lectura. */
    ERROR,
}

/** Root Resultado de la solicitud de autorización.[granted] cuando es cierto [failure] Debe ser nulo. */
data class RootRequestResult(
    val granted: Boolean,
    val failure: RootRequestFailure? = null,
) {
    companion object {
        /** Lo tengo uid=0. */
        val Granted: RootRequestResult = RootRequestResult(granted = true)

        /** Solicitud fallida,Adjunto el motivo del fracaso. */
        fun failed(reason: RootRequestFailure): RootRequestResult = RootRequestResult(granted = false, failure = reason)
    }
}

/** su comando de sonda:root El administrador abrirá un cuadro de diálogo de autorización debido a esta llamada.,También genera el uid actual. */
internal const val ROOT_PROBE_COMMAND = "id"

/**
 * Construya una lista de parámetros para la sonda su.
 *
 * con [RootExecutor.shellPrefix] La convención de convocatoria de:`su -c <cmd>`;Arreglado ir argv ProcessBuilder,
 * Sin concatenación de texto de shell,Evite las superficies de inyección de comandos.
 */
internal fun rootProbeArgs(prefix: List<String>): List<String> = prefix + listOf("-c", ROOT_PROBE_COMMAND)

/** ¿La salida de la sonda indica que se obtuvo root(uid=0). */
internal fun isRootGranted(output: String?): Boolean = output?.contains("uid=0") == true

/**
 * Determinación del estado final de la sonda:Código de salida 0 y la salida contiene uid=0 Sólo si la autorización es exitosa.
 *
 * Cuando falta su en algunas ROM, el "not found" Error de texto,unificado como [RootRequestFailure.NO_SU_BINARY],
 * Deje que la interfaz de usuario le indique"Sin equipo su"en lugar de un rechazo general de la autorización.
 */
internal fun evaluateRootProbe(exitCode: Int, output: String): RootRequestResult = when {
    exitCode == 0 && isRootGranted(output) -> RootRequestResult.Granted
    output.contains("not found", ignoreCase = true) ->
        RootRequestResult.failed(RootRequestFailure.NO_SU_BINARY)
    else -> RootRequestResult.failed(RootRequestFailure.DENIED)
}

/** La sonda no finalizó dentro del tiempo de espera(root Nadie maneja las ventanas emergentes)→ Falló el tiempo de espera. */
internal fun rootProbeTimeout(): RootRequestResult = RootRequestResult.failed(RootRequestFailure.TIMEOUT)

/** Comienza el proceso hijo/Leer excepción → Clasificación de motivos de fallo. */
internal fun classifyRootProbeException(error: Throwable): RootRequestFailure = when (error) {
    is SecurityException -> RootRequestFailure.DENIED
    else -> RootRequestFailure.ERROR
}
