package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime

/**
 * v2.x Tiempo de ejecución extendido: herramientas de autoprueba de plataforma.
 *
 * Deja que el asistente se controle solo en el chat."La plataforma de aplicaciones en la que se encuentra"¿Qué capacidades están disponibles? ——
 * Anule el tiempo de ejecución del nodo integrado + autorización de cada canal de herramienta/estado listo,Una sola llamada proporciona un listado estructurado.
 *
 * Propósito:
 *  - Usuario preguntó"¿Se puede utilizar el tiempo de ejecución integrado?""¿Se pueden utilizar estas herramientas?"llamar directamente cuando;
 *  - solución de problemas"Una determinada herramienta no se puede ajustar"Autocomprobación primero,poner"Permiso no concedido"con"Fallo funcional"diferenciar.
 *
 * diseño:
 *  - cheque de solo lectura,Sin efectos secundarios(Excluir los datos del nodo y descomprimirlos todos a la vez,Esto se hará durante la inicialización de la aplicación.);
 *  - El estado del canal proviene del proveedor de autorización del entorno de ejecución([ToolRegistry.permissionStatusProvider]),
 *    Misma fuente que la verificación previa antes de la ejecución de la herramienta.,no aparecerá"La autoprueba dice que se puede utilizar pero en realidad no se puede ajustar."de bifurcación;
 *  - El punto único de falla no afecta a otros elementos(cada uno independientemente runCatching).
 */
object PlatformSelfCheckTool {

    const val NAME = "platform_selfcheck"

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "Plataforma operativa Muse de autoprueba:Estado de tiempo de ejecución del nodo incorporado + cada canal de herramienta(Accesibilidad/Shell/Root/Termux)" +
            "autorización y preparación,Devuelve una lista estructurada." +
            "Se llama cuando el usuario pregunta sobre \"autoprueba\", \"capacidades de la plataforma\", \"si se puede utilizar la herramienta\", \"estado de tiempo de ejecución\" o cuando la herramienta de solución de problemas no se puede ajustar.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Realice una autoprueba.
     *
     * @param context contexto de aplicación
     * @param permissionStatusProvider Proveedor de estado de autorización del entorno de ejecución(de ToolRegistry,Misma fuente que ejecutar la verificación previa)
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun execute(context: Context, permissionStatusProvider: (() -> ToolPermissionStatus?)?): String {
        val sb = StringBuilder("[Autocomprobación de la plataforma]")

        // ── 1. Tiempo de ejecución de nodo incorporado ──────────────────────────────────────────
        sb.append("\n■ Tiempo de ejecución de nodo incorporado")
        val node = MuseRuntime.nodeBinary(context)
        sb.append("\n  - Node binario: ").append(
            if (node.isFile) "existir (${node.length() / 1024 / 1024}MB)" else "desaparecido",
        )
        val nodeV = runCatching { MuseRuntime.execNode(context, listOf("-v"), timeoutMs = 20_000) }.getOrNull()
        sb.append("\n  - node -v: ").append(
            when {
                nodeV == null -> "excepción de ejecución"
                nodeV.exitCode == 0 -> "${nodeV.stdout} ✓"
                else -> "falló (exit=${nodeV.exitCode})"
            },
        )
        val data = MuseRuntime.ensureData(context)
        sb.append("\n  - datos de tiempo de ejecución: ").append(
            if (data.isSuccess) "Listo (${MuseRuntime.RUNTIME_DATA_VERSION})" else "La descompresión falló",
        )
        val npmCli = MuseRuntime.npmCli(context)
        if (npmCli.isFile) {
            val npmV = runCatching {
                MuseRuntime.execNode(context, listOf(npmCli.absolutePath, "-v"), timeoutMs = 60_000)
            }.getOrNull()
            sb.append("\n  - npm -v: ").append(
                when {
                    npmV == null -> "excepción de ejecución"
                    npmV.exitCode == 0 -> "${npmV.stdout} ✓"
                    else -> "falló (exit=${npmV.exitCode})"
                },
            )
        } else {
            sb.append("\n  - npm: No listo")
        }
        sb.append("\n  - Conclusión: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) "Listo en tiempo de ejecución ✓" else "Incompleto en tiempo de ejecución",
        )

        // ── 2. Autorización del canal de herramientas/listo ─────────────────────────────────────────
        sb.append("\n■ canal de herramientas")
        val status = runCatching { permissionStatusProvider?.invoke() }.getOrNull()
        if (status == null) {
            sb.append("\n  - No se puede obtener el estado del canal(Proveedor de autorización no listo)")
        } else {
            appendChannel(sb, "Accesibilidad(leer pantalla/Haga clic)", status.accessibility, "Después de activarlo, puede realizar lecturas de pantalla y herramientas de clic en la interfaz de usuario.")
            appendChannel(sb, "Shell / Root", status.shellTier, "Después de abrir, puede ejecutar clases de comandos y herramientas a nivel de sistema.")
            appendChannel(sb, "Termux", status.termux, "Después de abrir, se puede ejecutar el canal de comando termux")
        }

        // ── 3. Pautas de conclusión ─────────────────────────────────────────────────
        sb.append("\n■ Conclusión")
        val allOk = status?.let { it.accessibility && it.shellTier } == true
        sb.append(
            if (allOk) {
                "\n  Canal principal listo ✓. Si una herramienta aún no se puede ajustar,Probablemente sea un problema con la propia herramienta.,Se pueden proporcionar nombres de herramientas específicos para una mayor investigación."
            } else {
                "\n  Algunos canales no están listos —— Las herramientas correspondientes a canales no listos no estarán disponibles.,Entrará en vigor después de que el usuario haya configurado la autorización en el sistema." +
                    "Este es un permiso no concedido.,No es un fallo funcional."
            },
        )
        return sb.toString()
    }

    private fun appendChannel(sb: StringBuilder, label: String, ready: Boolean, hint: String) {
        sb.append("\n  - ").append(label).append(": ")
            .append(if (ready) "Disponible ✓" else "no concedido")
        if (!ready) sb.append(" —— ").append(hint)
    }
}

/**
 * v2.x Tiempo de ejecución extendido: registrador de herramientas de autoprueba de plataforma(init Los bloques se registran automáticamente en ToolRegistry).
 *
 * Estado del canal aprobado [ToolRegistry.permissionStatusProvider] leer —— Misma fuente que la verificación previa a la ejecución previa de la herramienta,
 * evitar"La autoprueba dice que está disponible, pero en realidad no se puede ajustar."de bifurcación.
 */
class PlatformSelfCheckToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PlatformSelfCheckTool.toolDef()) { _ ->
            PlatformSelfCheckTool.execute(context) {
                toolRegistry.permissionStatusProvider?.invoke()
            }
        }
    }
}
