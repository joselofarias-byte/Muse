package io.zer0.muse.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * v1.0.53: Registro de políticas de permisos parametrizados.
 *
 * Los niveles de riesgo estáticos no se pueden expresar"Diferentes parámetros de la misma herramienta tienen diferentes riesgos"escena,Como por ejemplo:
 *  - open_url: http/https liberación automática,file:// rechazar,Acuerdo desconocido transferido para su aprobación
 *  - execute_javascript: Contiene modo peligroso(runtime exec / rm -rf)Rechazar directamente
 *
 * Determinar prioridad(en [ToolPermissionResolver.resolve] dentro):
 *  1. Estrategia de herramienta única para el usuario ALWAYS_DENY(máxima prioridad,sin cambios)
 *  2. estrategia paramétrica(evaluate Se utiliza cuando se devuelve un valor no nulo)
 *  3. Lógica de nivel de riesgo estático original
 *
 * La estrategia devuelve nulo para indicar"No participa en la determinación del nivel de parámetro.",Recurrir a la lógica estática.
 */
object ParamPolicies {

    /** estrategia paramétrica:Devuelve nulo para indicar que no hay intercepción,Utilice lógica estática. */
    fun interface ParamPolicy {
        fun evaluate(args: Map<String, Any?>): ToolApprovalState?
    }

    private val registry = ConcurrentHashMap<String, ParamPolicy>()

    /** Registrar estrategia de parámetros de herramienta(Cobertura de registro duplicado). */
    fun register(toolName: String, policy: ParamPolicy) {
        registry[toolName] = policy
    }

    /** estrategia de consulta(Regreso sin registro null). */
    fun evaluate(toolName: String, args: Map<String, Any?>): ToolApprovalState? = registry[toolName]?.evaluate(args)

    /** Registrar políticas integradas(Llamado una vez durante la construcción.). */
    fun registerBuiltIn() {
        // open_url:http/https liberar,file:// rechazar,Acuerdo desconocido transferido para su aprobación
        register("open_url") { args ->
            val url = (args["url"] as? String)?.trim() ?: return@register null
            when {
                url.startsWith("http://") || url.startsWith("https://") -> ToolApprovalState.Auto
                url.startsWith("file://") ->
                    ToolApprovalState.Denied("No permitir que se abran enlaces de archivos locales(file://),Bloqueado")
                else -> ToolApprovalState.Pending
            }
        }

        // execute_javascript:El modo de peligro rechaza directamente,El resto sigue el nivel estático.
        register("execute_javascript") { args ->
            val code = (args["code"] as? String) ?: return@register null
            val dangerous = listOf(
                "rm -rf",
                "ProcessBuilder",
                "Runtime.getRuntime().exec",
                "format(",
                "document.cookie",
            ).any { code.contains(it) }
            if (dangerous) {
                ToolApprovalState.Denied("El código contiene operaciones peligrosas.(rm -rf/ejecución del proceso/Se filtró información sensible),Bloqueado")
            } else {
                null
            }
        }
    }
}
