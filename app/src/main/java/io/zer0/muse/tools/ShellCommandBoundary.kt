package io.zer0.muse.tools

import java.io.File

/**
 * Herramientas de carcasa universales(El comando es una cadena de shell arbitraria.,Como por ejemplo terminal_exec)Se utiliza el límite de comando de dos paradas.
 *
 * ## ¿Por qué no aplicarlo directamente? [ToolPermissionResolver.isUnsafeCommand]
 * `execute_shell` si**consulta de solo lectura**Herramientas:17 Lista blanca de comandos, canalizaciones prohibidas, redirecciones prohibidas,entonces [isUnsafeCommand]
 * poner `` ` `` `$` `|` `;` `&` `<` `>` `*` `?` `{}` Toda la gramática establecida junto con rm/git/curl/python/chmod
 * Todos los nombres de archivos ejecutables se consideran inseguros —— Colocarlo en una zona de pruebas de solo lectura no provocará una muerte accidental.
 * `terminal_exec` si**terminales universales**:Corte de registros de canalización, redirección y almacenamiento de resultados, transferencia de parámetros variables, eliminación recursiva de subdirectorios
 * Es todo su trabajo normal.,Si transfieres ese conjunto de criterios tal como están, básicamente será inútil.
 * Así que aquí volvemos a categorizar por "poder destructivo real" en lugar de "forma de cuerda".
 *
 * ## dos engranajes
 *  - [forbidReason] —— El conjunto más pequeño que está absolutamente prohibido.terminal_exec Negarse directamente a ejecutar,
 *    TRUSTED El modo y "Permitir siempre esta herramienta" no se pueden omitir:es lista blanca/Aprobación del piso cuando surgen errores.
 *  - [cautionReason] —— Destructivo pero probablemente funcionando(Eliminar archivos en el espacio de trabajo, cambiar permisos, descargar,
 *    `git push --force`, proceso de finalización, comando de estado del dispositivo).terminal_exec **no te detengas**este nivel,
 *    Déjelo en manos de la cadena de aprobación existente.:terminal_exec en sí mismo un ALTO riesgo,ASK/STRICT Juega cartas una por una en el modo,
 *    El usuario puede ver el texto original del comando en la tarjeta. Esta capa solo registra un registro de advertencia adicional,Hace que sea más fácil mirar atrás más tarde.
 *
 * ## El primer nivel es el conjunto más pequeño.,No es un conjunto completo
 * Solo comprenda la forma de "un solo comando puede destruir un dato":Datos privados de la raíz del dispositivo y de la aplicación/Eliminación y escritura en la partición del sistema,
 * Formateo y escritura de dispositivos de bloqueo, cambios de permisos recursivos fuera de límites, descarga y ejecución(`curl … | sh`),
 * Escalada de privilegios y comandos a nivel de dispositivo, matar bombas init/fork. claro**no lo hagas**cosa:
 *  - No agotes los comandos destructivos.`mv` Mover el directorio,`tar` cubierta,`truncate` Claro,`dd` Llena el disco con estos
 *    Innumerables variantes no están aquí.;O caen en segunda marcha,O depende de la cadena de aprobación.
 *  - No puedo evitar "descargar primero y luego ejecutar el script local"(`curl … -o i.sh && sh i.sh`). esta relacionado con `curl … | sh`
 *    Mismo tipo de poder destructivo,Pero deshabilitarlo equivale a deshabilitar toda la ejecución de scripts.(`sh build.sh` es un uso legítimo de esta herramienta);
 *    Esta categoría sólo está marcada en la segunda marcha.,La responsabilidad corre a cargo del texto original del pedido en la tarjeta de aprobación.
 *  - La semántica del Shell no se analiza. Empalme de fragmentos de sustitución de comandos,base64 Decodificar y luego ejecutar,Este límite es invisible
 *    (Solo realiza segmentación y coincidencia a nivel de parámetros.). Lo que bloquea es**Comandos destructivos para la generación de modelos normales.**,
 *    No es una cadena cuidadosamente construida por el atacante. —— Esto último depende del texto original del comando leído por el usuario en la tarjeta de aprobación.
 *  - No reemplaza la caja de arena de herramientas.terminal_exec Ejecutar con los permisos propios de la aplicación.,En primer lugar, la mayoría de las rutas del sistema no se pueden escribir.;
 *    Lo que está bloqueado aquí es la intención de que "incluso si hay un error en la capa de permisos, no debería suceder".
 *
 * Los métodos de lectura de párrafos y colocación están en [ShellSegment] / [ShellLocator] / [ShellCommandLex] adentro.
 */
internal object ShellCommandBoundary {

    /**
     * primera marcha:Golpe significa que se rechaza la ejecución.
     *
     * @param command El texto original del comando de shell que se ejecutará.
     * @param workspaceDir Directorio de trabajo de la herramienta(terminal_exec espacio de trabajo de la aplicación). Los caminos relativos se resuelven en
     *   Punto de aterrizaje absoluto,Sólo entonces podremos distinguir entre "limpieza normal en el espacio de trabajo" y "eliminación transfronteriza". La ruta relativa no se puede determinar cuando se pasa nulo.
     *   punto de aterrizaje,No participa en la primera intercepción.
     * @return Cuando se cumple una regla, se devuelve un motivo que se puede informar directamente al usuario.;Regresar en caso de error null
     */
    fun forbidReason(command: String, workspaceDir: File? = null): String? {
        if (command.isBlank()) return null
        if (command.replace(FORK_BOMB_SPACES, "").contains(FORK_BOMB_SHAPE)) return FORK_BOMB_REASON
        val locator = ShellLocator(workspaceDir)
        for (segment in ShellSegment.split(command)) {
            val hit = deleteReason(segment, locator)
                ?: writeReason(segment, locator)
                ?: permissionReason(segment, locator)
                ?: formatReason(segment)
                ?: deviceReason(segment)
                ?: remoteCodeReason(segment)
                ?: killReason(segment)
            if (hit != null) return hit
        }
        return redirectReason(command, locator)
    }

    /**
     * Segunda marcha:Destructivo pero probablemente funcionando —— **no te detengas**,de la cadena de aprobación existente(HIGH Los riesgos se confirman uno por uno.)
     * responsable,La persona que llama puede registrar como máximo un registro.
     *
     * @return descripción destructiva;Regresa cuando el comando no tiene poder destructivo. null
     */
    fun cautionReason(command: String): String? =
        ShellSegment.split(command).firstNotNullOfOrNull(::cautionForSegment)

    private fun cautionForSegment(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        if (verb == "find" && segment.tokens.contains("-delete")) return "find -delete Eliminación por lotes"
        if (verb == "git" && segment.args().any { it in GIT_DESTRUCTIVE_ARGS }) return "git subcomando destructivo(reset/clean/Recomendación fuerte)"
        return CAUTION_NOTES[verb]
    }

    // ── Reglas específicas de primer nivel ────────────────────────────────────────────────────

    /** Eliminar:El punto de aterrizaje está fuera de los límites.(Raíz del dispositivo/partición del sistema/Datos privados de la aplicación)detener todo;La eliminación recursiva debe cubrir fuera del área de trabajo antes de detenerse. */
    private fun deleteReason(segment: ShellSegment, locator: ShellLocator): String? {
        val verb = segment.verb() ?: return null
        if (verb == "find") return findDeleteReason(segment, locator)
        if (verb !in DELETE_VERBS) return null
        val recursive = segment.args().any(ShellCommandLex::isRecursiveFlag)
        return segment.pathArgs().firstNotNullOfOrNull { raw ->
            deleteTargetReason(raw, locator.target(raw), recursive)
        }
    }

    private fun deleteTargetReason(raw: String, target: ShellTarget, recursive: Boolean): String? =
        unacceptable("Eliminar", target, raw) ?: when {
            !recursive -> null
            target.scope == ShellScope.WORKSPACE_ROOT && target.wholeDirectory -> "Eliminar recursivamente todo el espacio de trabajo de la aplicación:$raw"
            target.scope == ShellScope.STORAGE || target.scope == ShellScope.OUTSIDE -> "Eliminar recursivamente rutas que apunten fuera del espacio de trabajo:$raw"
            else -> null
        }

    /** `find <raíz> … -delete`:La raíz de escaneo se bloqueará si va más allá del área de trabajo.;La eliminación por predicado en el espacio de trabajo pertenece al segundo nivel. */
    private fun findDeleteReason(segment: ShellSegment, locator: ShellLocator): String? {
        if (!segment.tokens.contains("-delete")) return null
        val root = segment.pathArgs().firstOrNull() ?: return null
        val target = locator.target(root)
        unacceptable("find -delete escanear raíz", target, root)?.let { return it }
        return when (target.scope) {
            ShellScope.STORAGE, ShellScope.OUTSIDE -> "find -delete La raíz del escaneo está fuera del espacio de trabajo.:$root"
            else -> null
        }
    }

    /** Destino de escritura explícito:`dd of=`,`tee`,`cp/mv/install/ln` el punto de aterrizaje,`truncate`. */
    private fun writeReason(segment: ShellSegment, locator: ShellLocator): String? {
        val destinations = when (segment.verb()) {
            "dd" -> segment.args().filter { it.startsWith("of=") }.map { it.removePrefix("of=") }
            "tee" -> segment.args().filterNot { it.startsWith("-") }
            "cp", "mv", "install", "ln", "truncate" -> listOfNotNull(segment.pathArgs().lastOrNull())
            else -> emptyList()
        }
        return destinations.firstNotNullOfOrNull { raw -> unacceptable("escribir", locator.target(raw), raw) }
    }

    /**
     * Cambiar permisos/Dueño. En la raíz del dispositivo y la partición del sistema `chmod`/`chown` detener todo(`chmod 777 /` este tipo);
     * Cambiar permisos en el espacio de trabajo es un proceso normal para preparar scripts,no te detengas —— Libere de forma recursiva los permisos de escritura fuera del espacio de trabajo antes de bloquear.
     */
    private fun permissionReason(segment: ShellSegment, locator: ShellLocator): String? {
        val verb = segment.verb() ?: return null
        if (verb !in PERMISSION_VERBS) return null
        val args = segment.args()
        val recursive = args.any(ShellCommandLex::isRecursiveFlag)
        val worldWritable = args.any(ShellCommandLex::isWorldWritableMode)
        return segment.pathArgs().firstNotNullOfOrNull { raw ->
            permissionTargetReason(verb, locator.target(raw).scope, recursive, worldWritable, raw)
        }
    }

    private fun permissionTargetReason(
        verb: String,
        scope: ShellScope,
        recursive: Boolean,
        worldWritable: Boolean,
        raw: String,
    ): String? = when {
        scope == ShellScope.DEVICE_ROOT -> "$verb Actúa en la raíz del dispositivo:$raw"
        scope == ShellScope.SYSTEM -> "$verb Actúa sobre la partición del sistema.:$raw"
        scope == ShellScope.APP_DATA -> "$verb Actúa sobre los datos privados de la aplicación fuera del espacio de trabajo en /data:$raw"
        recursive && worldWritable && scope in OUT_OF_SCOPE_STORAGE -> {
            "Liberar recursivamente permisos de escritura en rutas fuera del espacio de trabajo:$verb $raw"
        }
        else -> null
    }

    /** Formatear y bloquear dispositivos:Los objetos de estos comandos no pueden ser archivos en el espacio de trabajo. */
    private fun formatReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        if (verb == "mkfs" || verb.startsWith("mkfs.")) return "Comando de formato del sistema de archivos:$verb"
        if (verb in BLOCK_DEVICE_VERBS) return "Manipular particiones directamente/bloquear comandos del dispositivo:$verb"
        return ddDeviceReason(verb, segment)
    }

    private fun ddDeviceReason(verb: String, segment: ShellSegment): String? {
        if (verb != "dd") return null
        val output = segment.args().firstOrNull { it.startsWith("of=") }?.removePrefix("of=").orEmpty()
        return if (output.startsWith("/dev/") && !ShellCommandLex.isHarmlessSink(output)) "dd Escribir nodo de dispositivo:$output" else null
    }

    /**
     * Comandos a nivel de dispositivo:Escalada de privilegios, suministro de energía, montaje./espacio de nombres,Y borrará o desinstalará los datos de la aplicación. `pm`/`am`/`appops`
     * subcomando. Estos tienen sus propias herramientas especiales.(device_shell / app_clear_data / am_start espera),
     * Depende de cada individuo pasar por el proceso de aprobación.;La aparición en el archivo del terminal no tiene finalidad legítima.
     */
    private fun deviceReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        DEVICE_ONLY_VERBS[verb]?.let { return "$it:$verb" }
        return destructiveSubcommandReason(verb, segment.args().firstOrNull { !it.startsWith("-") })
    }

    private fun destructiveSubcommandReason(verb: String, subcommand: String?): String? = when {
        verb == "pm" && subcommand in PM_DESTRUCTIVE -> "Subcomandos destructivos de gestión de paquetes:pm $subcommand"
        verb == "am" && subcommand == "force-stop" -> "Comando para forzar la detención de otras aplicaciones:am force-stop"
        verb == "appops" && subcommand == "set" -> "Comandos para cambiar otros permisos de aplicaciones:appops set"
        else -> null
    }

    /**
     * Categoría "Ejecutar una parte de contenido dinámico como código de comando":Canalizado al shell/intérprete stdin,
     * o `eval "$(curl …)"`.`curl … | sh`,`wget -qO- … | bash` es su forma típica.
     *
     * El oleoducto en sí no está bloqueado.(`cat x | grep y` Usado todos los días),Bloquear solo cuando el contenido realmente se ejecutará:
     *  - shell sin `-c` tiempo,stdin es la fuente de su mando → detenerse
     *  - El intérprete no tiene argumentos de guión.,o el parámetro es `-`/`-s`/`--stdin` → stdin es la fuente del comando → detenerse
     *  - `… | python3 -m json.tool`,`… | python3 fmt.py`,`… | sh -c 'echo hi'`:
     *    En la tubería está**datos**No es un código de comando → no te detengas,Este es un uso legítimo del terminal.
     */
    private fun remoteCodeReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        val args = segment.args()
        val pipedCode = segment.isPipeInput && verb in INTERPRETERS &&
            ShellCommandLex.stdinIsCommandSource(args, verb in SHELL_INTERPRETERS)
        if (pipedCode) return "Ejecutar el contenido de la tubería como código de comando:| $verb ${args.joinToString(" ")}".trim()
        if (verb in SCRIPT_SOURCES && args.any(ShellCommandLex::loadsRemoteContent)) {
            return "$verb Ejecutar contenido del script desde la red."
        }
        return null
    }

    private fun killReason(segment: ShellSegment): String? = when {
        segment.verb() != "kill" -> null
        segment.args().any { it == "1" } -> "kill señalar a PID 1(init)"
        segment.args().any { it == "-1" } -> "kill Señalar todos los procesos."
        else -> null
    }

    /** Destino de ubicación de redireccionamiento:`> /system/...`,`>> /data/data/...`.`2>&1` Este tipo no es una colocación. */
    private fun redirectReason(command: String, locator: ShellLocator): String? =
        REDIRECT.findAll(command)
            .map { ShellCommandLex.stripQuotes(it.groupValues[1]) }
            .filter { it.isNotEmpty() && !it.startsWith("&") }
            .firstNotNullOfOrNull { raw -> unacceptable("redirigir escribir", locator.target(raw), raw) }

    /** Razones por las que los objetivos caen en áreas \"absolutamente inaceptables\";Las áreas restantes las juzga la persona que llama según el equipo. */
    private fun unacceptable(label: String, target: ShellTarget, raw: String): String? = when (target.scope) {
        ShellScope.DEVICE_ROOT -> "${label}Pulsa la raíz del dispositivo($raw)"
        ShellScope.SYSTEM -> "${label}Golpear la partición del sistema($raw)"
        ShellScope.APP_DATA -> "${label}Acceda a los datos privados de la aplicación fuera del espacio de trabajo en /data($raw)"
        else -> null
    }

    // ── tabla de criterios ────────────────────────────────────────────────────────────

    private val FORK_BOMB_SPACES = Regex("\\s+")
    private const val FORK_BOMB_SHAPE = ":(){:"
    private const val FORK_BOMB_REASON = "fork bomba autorreplicante"

    private val REDIRECT = Regex("""\d?>{1,2}\s*("?[^"\s;|&]+)""")

    private val DELETE_VERBS = setOf("rm", "rmdir", "shred", "unlink")
    private val PERMISSION_VERBS = setOf("chmod", "chown", "chgrp")
    private val BLOCK_DEVICE_VERBS = setOf("fdisk", "sfdisk", "parted", "sgdisk", "wipefs", "losetup", "mkswap")

    /** Comandos a nivel de dispositivo:No hay uso legítimo en el terminal App Sandbox,Cada uno tiene sus propias herramientas y aprobaciones específicas. */
    private val DEVICE_ONLY_VERBS = mapOf(
        "su" to "Comando de escalada de privilegios",
        "sudo" to "Comando de escalada de privilegios",
        "doas" to "Comando de escalada de privilegios",
        "reboot" to "Comandos de encendido del dispositivo",
        "poweroff" to "Comandos de encendido del dispositivo",
        "shutdown" to "Comandos de encendido del dispositivo",
        "halt" to "Comandos de encendido del dispositivo",
        "mount" to "Comandos de montaje y ejecución del sistema",
        "umount" to "Comandos de montaje y ejecución del sistema",
        "setprop" to "Comandos de montaje y ejecución del sistema",
        "restorecon" to "Comandos de montaje y ejecución del sistema",
        "chroot" to "Comandos de montaje y ejecución del sistema",
        "switch_root" to "Comandos de montaje y ejecución del sistema",
    )

    private val PM_DESTRUCTIVE = setOf("clear", "uninstall", "disable", "delete")

    /** Liberar recursivamente los permisos de escritura en estas áreas se considera el primer nivel.;Cambiar permisos en el espacio de trabajo es parte del proceso normal de preparación de scripts. */
    private val OUT_OF_SCOPE_STORAGE = setOf(ShellScope.STORAGE, ShellScope.OUTSIDE)

    private val SHELL_INTERPRETERS = setOf("sh", "bash", "dash", "ash", "ksh", "zsh", "csh", "tcsh")
    private val INTERPRETERS = SHELL_INTERPRETERS + setOf(
        "python", "python2", "python3", "perl", "ruby", "node", "php", "lua",
    )

    /** Declarar explícitamente \"stdin Este es el parámetro de mi fuente de comando\". */
    private val SCRIPT_SOURCES = setOf("eval", "source")

    private val GIT_DESTRUCTIVE_ARGS = setOf("--force", "reset", "clean", "push", "checkout")

    /** Segunda marcha:Trabajo destructivo pero normal.,Sólo mantén un registro,No pares. */
    private val CAUTION_NOTES = mapOf(
        "rm" to "Eliminar archivos",
        "rmdir" to "eliminar directorio",
        "unlink" to "Eliminar archivos",
        "shred" to "Destruir el contenido del archivo",
        "mv" to "moverse/sobrescribir archivo",
        "truncate" to "Truncar archivo",
        "dd" to "Leer y escribir en bloques.",
        "chmod" to "Cambiar permisos de archivos",
        "chown" to "Cambiar propietario del archivo",
        "chgrp" to "Cambiar el grupo del archivo",
        "kill" to "Terminar el proceso",
        "pkill" to "Terminar procesos en lotes por nombre",
        "killall" to "Terminar procesos en lotes por nombre",
        "curl" to "descarga de internet",
        "wget" to "descarga de internet",
        "pm" to "Comandos de gestión de paquetes",
        "am" to "Comandos del administrador de actividades",
        "settings" to "Configuración del sistema de lectura y escritura.",
        "input" to "Inyectar eventos de entrada",
        "sh" to "Ejecutar archivo de script",
        "bash" to "Ejecutar archivo de script",
        "python" to "Ejecutar archivo de script",
        "python3" to "Ejecutar archivo de script",
        "node" to "Ejecutar archivo de script",
        "perl" to "Ejecutar archivo de script",
        "ruby" to "Ejecutar archivo de script",
    )
}
