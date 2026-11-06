package io.zer0.muse.automation.appcontrol

/**
 * Control de aplicaciones del dispositivo —— Núcleo de lógica pura (excluyendo el tipo Android, conveniente para pruebas individuales).
 *
 * ## ¿Por qué tomar una capa separada?
 *
 * "Qué puede hacer el modelo por la aplicación"Depende del canal de autorización actual:
 *  - **Listar aplicaciones/iniciar/abrir página de configuración del sistema**: Siempre que exista un Contexto (la aplicación tiene sus propios permisos, no se requiere autorización automática);
 *  - **Forzar detención/borrar datos/desinstalar/cambiar el estado del componente**: debe ir Shell(Shizuku·adb) O raíz.
 *
 * Coloque este conjunto de decisiones en Kotlin puro y podrá bloquearlo con una sola prueba."Cuando faltan permisos, se debe indicar con sinceridad qué nivel se requiere.",
 * En lugar de dejar que la capa de herramientas emita juicios separados y proporcione fallas vagas.
 */

/** Información del esqueleto de una aplicación instalada (iniciable). */
data class InstalledAppInfo(
    /** Nombre del paquete, identificador único. */
    val packageName: String,
    /** Nombre visible para el usuario (de PackageManager, recortado). */
    val label: String,
    /** Nombre de la versión, desconocido es una cadena vacía. */
    val versionName: String,
    /** ¿Es de la partición del sistema (desinstalar?/La eliminación de datos suele ser destructiva para las aplicaciones del sistema). */
    val system: Boolean,
    /** Ya sea la propia Muse. */
    val self: Boolean,
)

/** Acciones de control de aplicaciones. */
enum class AppControlAction {
    /** Inicie la aplicación (primer plano). */
    LAUNCH,

    /** Forzar la detención de la aplicación. Requiere Shell/Raíz. */
    FORCE_STOP,

    /** Borrar datos de la aplicación. Requiere Shell/Root y es el más destructivo. */
    CLEAR_DATA,

    /** Desinstale la aplicación. Requiere Shell/Root (la desinstalación normal hará que el sistema abra una ventana emergente para su confirmación y el Shell permanecerá en silencio). */
    UNINSTALL,

    /** Abra la página de detalles de la aplicación en la configuración del sistema (no se requiere autorización automática). */
    OPEN_APP_SETTINGS,
}

/** El resultado de una solicitud de control de la aplicación (para traducción al lenguaje humano por parte de la capa de herramientas). */
sealed interface AppActionResult {
    /** Ejecutado exitosamente ([via] Registre el paso real realizado para la verificación de registros y calibres). */
    data class Success(val via: ExecutionChannel) : AppActionResult

    /**
     * La acción está actualmente**Imposible**Completado (canal faltante/objetivo no permitido/nombre de paquete no válido).
     * [action] Indique qué capacidad se necesita y el modelo podrá explicársela al usuario o utilizar otra solución en consecuencia.
     */
    data class Unavailable(val reason: UnavailableReason) : AppActionResult

    /** Se intentó la ejecución pero falló (el canal está dentro, el comando falló). */
    data class Failed(val message: String) : AppActionResult
}

/** Canal de ejecución. */
enum class ExecutionChannel {
    /** Aplicar sus propios permisos (PackageManager / Intent), no requiere ninguna autorización automatizada. */
    APP,

    /** Shizuku / adb shell. */
    SHELL,

    /** Root(su). */
    ROOT,
}

/** Las razones específicas del “no puedo hacerlo”. */
enum class UnavailableReason {
    /** El nombre del paquete es ilegal (antiinyección: solo permitido `[A-Za-z0-9_.]`). */
    INVALID_PACKAGE_NAME,

    /** El paquete no se puede encontrar en el dispositivo (o no es visible debido a restricciones de visibilidad del paquete de Android 11+). */
    PACKAGE_NOT_FOUND,

    /** Requiere Shell o Root, pero ninguno de los dos. */
    NEED_SHELL_OR_ROOT,

    /** Esta acción no puede afectar las aplicaciones del sistema. */
    BLOCKED_SYSTEM_APP,

    /** Esta acción no puede afectar a la propia Muse. */
    BLOCKED_SELF,
}

/**
 * Canales actualmente disponibles (pasados después de la detección mediante el método de ensamblaje, manteniendo esta capa sin dependencias de Android).
 *
 * @property shell Shizuku/adb Listo o no
 * @property root su ¿Está disponible?
 */
data class ChannelAvailability(val shell: Boolean, val root: Boolean) {
    val anyElevated: Boolean get() = shell || root

    /** Verifique el canal utilizado para los comandos del dispositivo:Shell prioridad,Root Guárdalo para ti (con `AutomationManager.execTiered` mismo calibre). */
    fun preferredElevated(): ExecutionChannel = if (shell) ExecutionChannel.SHELL else ExecutionChannel.ROOT
}

/**
 * Decisiones en el centro del control de aplicaciones.
 *
 * Solo haz tres cosas:**Verificar el nombre del paquete → Determinar qué canal se requiere para la acción. → dar resultados decidibles**.
 * ejecución real por [AndroidAppController](acción de capa de aplicación) y `AutomationManager`(comando del dispositivo) asumido.
 */
object AppControlCore {

    /**
     * Lista blanca de nombres de paquetes:Android Se permiten letras, números, guiones bajos, puntos y al menos dos párrafos.
     *
     * apretar para"solo estos personajes"es para**Prevenir la inyección de comandos**——El nombre del paquete eventualmente se escribirá en el comando de shell
     * (`am force-stop <pkg>`), cualquier espacio/punto y coma/Las cotizaciones deben bloquearse aquí.
     */
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /** ¿Es legal el nombre del paquete? (se puede usar directamente para deletrear comandos). */
    fun isValidPackageName(packageName: String): Boolean = PACKAGE_NAME.matches(packageName)

    /**
     * Determine si se puede ejecutar una acción en este momento y qué canal se requiere.
     *
     * @param target Aplicación de destino (pasada si se conoce;null significa solo hacer"¿Es legal el nombre del paquete?"juicio estático)
     */
    fun evaluate(
        action: AppControlAction,
        packageName: String,
        channels: ChannelAvailability,
        target: InstalledAppInfo? = null,
    ): AppActionResult {
        val result = when {
            // El nombre del paquete eventualmente se escribirá en el comando de shell. Si es ilegal será rechazado (antiinyección)
            !isValidPackageName(packageName) ->
                AppActionResult.Unavailable(UnavailableReason.INVALID_PACKAGE_NAME)

            // La demarcación entre las acciones de la capa de aplicación y los comandos del dispositivo: solo acciones que necesitan cruzar el entorno limitado de la aplicación Shell/Root
            action == AppControlAction.LAUNCH || action == AppControlAction.OPEN_APP_SETTINGS ->
                AppActionResult.Success(ExecutionChannel.APP)

            // Deja que el modelo se descargue./Limpiar Muse en sí es una operación destructiva sin sentido y debe bloquearse directamente.
            target?.self == true ->
                AppActionResult.Unavailable(UnavailableReason.BLOCKED_SELF)

            // Borrar datos de la aplicación del sistema/Desinstalarlo equivale básicamente a dañar el teléfono; La parada forzada sigue siendo una operación segura y reversible.
            target?.system == true && action != AppControlAction.FORCE_STOP ->
                AppActionResult.Unavailable(UnavailableReason.BLOCKED_SYSTEM_APP)

            !channels.anyElevated ->
                AppActionResult.Unavailable(UnavailableReason.NEED_SHELL_OR_ROOT)

            else -> AppActionResult.Success(channels.preferredElevated())
        }
        return result
    }

    /** Una colección de acciones que requieren Shell/Root (en función de lo cual la capa de herramienta decide si detecta el canal primero). */
    fun actionsNeedingElevation(): Set<AppControlAction> = setOf(
        AppControlAction.FORCE_STOP,
        AppControlAction.CLEAR_DATA,
        AppControlAction.UNINSTALL,
    )

    /**
     * Construir comandos de dispositivos.
     *
     * Simplemente deletree el nombre del paquete aquí——Aprobado [isValidPackageName] Lista blanca, el valor de retorno se puede dar directamente a `execTiered`.
     * Las nuevas acciones deben registrarse aquí al mismo tiempo para evitar que se dispersen en la capa de herramientas.
     */
    fun buildCommand(action: AppControlAction, packageName: String): String? = when (action) {
        AppControlAction.FORCE_STOP -> "am force-stop $packageName"
        AppControlAction.CLEAR_DATA -> "pm clear $packageName"
        AppControlAction.UNINSTALL -> "pm uninstall $packageName"
        AppControlAction.LAUNCH, AppControlAction.OPEN_APP_SETTINGS -> null
    }

    /**
     * Presione en la lista de candidatos"El nombre del paquete es exacto. → Los nombres de los paquetes ignoran mayúsculas y minúsculas → el nombre contiene"Emparejando paso a paso.
     *
     * Lo que el modelo ofrece a los usuarios es a menudo"WeChat"en lugar de `com.tencent.mm`, por lo que la coincidencia de nombres es una capacidad necesaria;
     * Sin embargo, el orden debe ser determinista al degradar para evitar que las aplicaciones homófonas se seleccionen aleatoriamente.
     */
    fun matchApp(apps: List<InstalledAppInfo>, query: String): InstalledAppInfo? {
        val q = query.trim()
        return if (q.isEmpty()) {
            null
        } else {
            apps.firstOrNull { it.packageName == q }
                ?: apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }
                ?: apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
                ?: apps.firstOrNull { it.label.contains(q, ignoreCase = true) }
        }
    }

    /** Renderice la lista de aplicaciones (muéstrela al modelo; ordene por nombre, etiquete las aplicaciones del sistema y él mismo). */
    fun renderAppList(apps: List<InstalledAppInfo>, limit: Int = 200): String {
        if (apps.isEmpty()) return "No se encontró ninguna aplicación ejecutable (o no se puede incluir en la lista debido a restricciones de visibilidad del paquete del sistema)."
        val sorted = apps.sortedBy { it.label.lowercase() }
        val shown = sorted.take(limit)
        return buildString {
            appendLine("total ${sorted.size} aplicaciones ejecutables${if (sorted.size > shown.size) "(solo listado antes ${shown.size} )" else ""}:")
            shown.forEach { app ->
                append("- ${app.label} | ${app.packageName}")
                if (app.system) append(" | Aplicación del sistema")
                if (app.self) append(" | Esta aplicación")
                appendLine()
            }
        }.trimEnd()
    }
}
