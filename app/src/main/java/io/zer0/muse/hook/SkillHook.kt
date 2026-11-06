package io.zer0.muse.hook

import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.TransformContext

/**
 * P1-1: Skill Hook Interfaz básica.
 *
 * Todos los Hooks implementan esta interfaz,Pase [HookRegistry] Regístrese para participar en el proceso de conversación sobre IA.
 * con existente [io.zer0.muse.transformer.Transformer] el límite de:
 *  - Transformer tubo"Deformación de la lista de mensajes"(transform/visualTransform/onGenerationFinish)
 *  - SkillHook tubo"Interceptación de procesos/anexar/eventos del ciclo de vida"(Una dimensión más amplia de los puntos de intervención)
 *
 * principios de diseño:
 *  - Priorización: priority Cuanto mayor sea el valor, antes se ejecutará.(Alinearse con la prioridad DESC del Lorebook)
 *  - Idempotente y reentrante: El mismo Hook puede ser llamado varias veces,Los implementadores deben garantizar la idempotencia
 *  - tolerancia a fallas: Un solo Hook lanza una excepción sin bloquear el proceso principal,por [HookRegistry] capturar y grabar
 *
 * @property id identificador único(para el registro/Cerrar sesión/Depuración)
 * @property priority prioridad(Cuanto mayor sea el valor, antes se ejecutará.,Predeterminado 50)
 * @property enabled Ya sea para habilitar( false El Hook no participa en la ejecución.)
 */
interface SkillHook {
    val id: String
    val priority: Int get() = 50
    val enabled: Boolean get() = true
}

// ════════════════════════════════════════════════════════════════
// 1. Ciclo de vida de la aplicación Hook
// ════════════════════════════════════════════════════════════════

/**
 * Ciclo de vida de la aplicación Hook — Creación de aplicaciones responsivas/Cambia entre delante y detrás.
 *
 * Escenario típico:
 *  - Inicializar tercero SDK
 *  - Informar evento de inicio
 *  - Restaurar la conexión en primer plano/liberar recursos en segundo plano
 */
interface AppLifecycleHook : SkillHook {
    suspend fun onAppCreate() {}
    suspend fun onAppForeground() {}
    suspend fun onAppBackground() {}
}

// ════════════════════════════════════════════════════════════════
// 2. Procesamiento de mensajes Hook
// ════════════════════════════════════════════════════════════════

/**
 * Procesamiento de mensajes Hook — Involucrado en la mensajería de usuario y la representación de mensajes de IA.
 *
 * Diferencias con Transformer.visualTransform:
 *  - visualTransform Proceso"Lista de mensajes generados"Realizar conversión de visualización de UI
 *  - MessageProcessingPlugin en"antes de enviar/antes de renderizar"hacer intercepción/Modificar
 */
interface MessageProcessingPlugin : SkillHook {
    /**
     * Se llama antes de enviar el mensaje del usuario.
     * @return Contenido del mensaje modificado;Devuelve nulo para interceptar el mensaje.(no enviar)
     */
    suspend fun onUserMessageSend(message: String): String? {
        return message
    }

    /**
     * AI Llamado antes de la representación del mensaje.(Puede realizar modificaciones finales al contenido.,como la desensibilización/Formato).
     * @return Contenido modificado
     */
    suspend fun onAssistantMessageRender(content: String): String {
        return content
    }
}

// ════════════════════════════════════════════════════════════════
// 3. Prompt línea de montaje Hook
// ════════════════════════════════════════════════════════════════

/**
 * Prompt Contexto de montaje — Datos de contexto pasados ​​a SystemPromptComposeHook.
 */
data class PromptContext(
    val assistantId: String?,
    val sessionId: String?,
    val locale: String,
    val forSubagent: Boolean,
)

/**
 * Montaje de indicaciones del sistema Hook — Se llama después de que SystemPromptAssembler haya ensamblado el indicador del sistema.
 *
 * Escenario típico:
 *  - Worldbook La entrada siempre activa se inyecta en
 *  - Instrucciones de adición dinámica(basado en el tiempo/Conciencia de la escena de ubicación)
 *
 * @return Contenido agregado al final del mensaje del sistema(Una cadena vacía significa que no se agrega)
 */
interface SystemPromptComposeHook : SkillHook {
    suspend fun afterComposeSystemPrompt(context: PromptContext): String {
        return ""
    }
}

/**
 * Conjunto de lista de herramientas Hook — Se llama después de que se haya reunido la lista de herramientas.,filtrable/ordenar/Añadir.
 *
 * Escenario típico:
 *  - Habilitado dinámicamente según el contexto/Deshabilitar ciertas herramientas
 *  - Inyectar descripción de herramienta personalizada
 */
interface ToolPromptComposeHook : SkillHook {
    /**
     * @param tools Lista de herramientas actual
     * @return Lista de herramientas revisada
     */
    suspend fun filterToolPromptItems(tools: List<ToolPromptItem>): List<ToolPromptItem> {
        return tools
    }
}

/**
 * Elementos de la lista de herramientas(Modelo simplificado,Para operación de gancho).
 */
data class ToolPromptItem(
    val name: String,
    val description: String,
    val parametersJson: String?,
    val enabled: Boolean = true,
)

/**
 * Prompt evento de finalización — Última oportunidad para revisar antes de enviar al modelo.
 */
data class PromptFinalizeEvent(
    val preparedHistory: List<UIMessage>,
    val assistantId: String?,
    val sessionId: String?,
    val transformContext: TransformContext,
)

/**
 * Prompt Finaliza el resultado.
 */
data class PromptFinalizeResult(
    val preparedHistory: List<UIMessage>,
)

/**
 * Prompt finalizar Hook — Se llama antes de que se envíe la lista de mensajes al modelo.
 *
 * Diferencias con Transformer.transform:
 *  - transform Ejecutar temprano en el proceso,Procesar el mensaje original
 *  - PromptFinalizeHook Ejecutar después de la canalización y antes de enviar.,Se puede truncar según el estado final./inyectar
 *
 * Escenario típico:
 *  - restricciones de contexto del piso(P1-4): Truncar mensajes históricos que superen los límites mínimos
 *  - Worldbook Inyección de desencadenador de palabras clave(P1-2): Escanea los últimos N niveles de mensajes de USUARIO,Inyectar entradas coincidentes
 */
interface PromptFinalizeHook : SkillHook {
    suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        return PromptFinalizeResult(event.preparedHistory)
    }
}

// ════════════════════════════════════════════════════════════════
// 4. Ciclo de vida de la herramienta Hook
// ════════════════════════════════════════════════════════════════

/**
 * Enumeración de la acción de la llamada de la herramienta.
 */
sealed class ToolCallAction {
    /** permitir la ejecución */
    object Allow : ToolCallAction()

    /** Ejecución de interceptación,Devolver resultados personalizados a LLM */
    data class Block(val reason: String, val fakeResult: String? = null) : ToolCallAction()
}

/**
 * Resultados de ejecución de herramientas(Modelo simplificado).
 */
data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val output: String,
    val durationMs: Long,
)

/**
 * Ciclo de vida de la herramienta Hook — Intervenir en varias etapas de la invocación de la herramienta.
 *
 * Escenario típico:
 *  - Registro de auditoría: Registre cada llamada de herramienta
 *  - Segunda verificación de permiso: Bloquee llamadas de alto riesgo según el contexto
 *  - Postprocesamiento de resultados: Desensibilizar la salida de la herramienta/Formato
 */
interface ToolLifecycleHook : SkillHook {
    /**
     * Se llama cuando la herramienta llama a una solicitud.,Puede ser interceptado.
     * @return [ToolCallAction.Allow] liberar; [ToolCallAction.Block] interceptar
     */
    suspend fun onToolCallRequested(toolName: String, params: Map<String, Any>): ToolCallAction {
        return ToolCallAction.Allow
    }

    /**
     * Se llama después de que se completa la verificación de permisos.
     */
    suspend fun onToolPermissionChecked(toolName: String, approved: Boolean) {}

    /**
     * Se llama después de que se completa la ejecución de la herramienta.
     */
    suspend fun onToolExecutionResult(result: ToolExecutionResult) {}
}

// ════════════════════════════════════════════════════════════════
// 5. Generación de resumen Hook
// ════════════════════════════════════════════════════════════════

/**
 * Generación de resumen Hook — compresión del diálogo de intervención/Etapas de generación de resúmenes.
 *
 * Escenario típico:
 *  - Palabras de resumen personalizadas
 *  - Postprocesamiento de resultados resumidos(Como extraer puntos de decisión clave.)
 *  - Activar la solidificación de la memoria según el resumen.
 */
interface SummaryGenerateHook : SkillHook {
    /**
     * Llamado antes de preparar palabras de resumen,Los mensajes históricos a comprimir se pueden modificar.
     * @return Mensaje histórico modificado
     */
    suspend fun beforePrepareSummaryPrompt(history: List<UIMessage>): List<UIMessage> {
        return history
    }

    /**
     * Se llama después de generar el resumen.,El contenido del resumen final se puede modificar.
     * @return Resumen revisado
     */
    suspend fun afterGenerateSummary(summary: String): String {
        return summary
    }
}
