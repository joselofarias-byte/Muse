package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.promptinjection.PromptInjectionEntity

/**
 * PromptInjection(inyección de patrón)Transformer — Phase 8.5.
 *
 * Implementación escrita de forma independiente.
 *
 * comportamiento:
 *  - de context.extra("prompt_injections") Obtenga los elementos inyectados previamente consultados
 *    (ChatViewModel Filtrado según el modo actual)
 *    extra contrato: key="prompt_injections",Tipo List<PromptInjectionEntity>,puede ser null
 *    (bajo borrado genérico unchecked cast,Consulte la anotación @Suppress a continuación)
 *  - filtrado defensivo enabled=false entrada(No depende del filtrado de la persona que llama)
 *  - Ordenar por prioridad descendente
 *  - Determine la posición de inyección según la posición de inserción:
 *    - [POSITION_BEFORE_SYSTEM]: Insertar antes de todos los mensajes del SISTEMA
 *    - [POSITION_AFTER_SYSTEM]: Insertar después de todos los mensajes del SISTEMA(Predeterminado)
 *    - valor desconocido: Después de grabar Logger.w, presione after_system para procesar
 *  - Una sola inyección es un mensaje del SISTEMA,Prefijo de contenido "[ModeInjection: name]"
 *  - Un solo contenido supera [MAX_SINGLE_CONTENT] truncamiento de caracteres;El total supera [MAX_TOTAL_CONTENT] personaje
 *    Saltar entradas posteriores,Evite que la inyección reviente el presupuesto inmediato
 *  - name saltos de línea / "]" será reemplazado por un guión bajo,evitar la destrucción "[ModeInjection: ...]" formato de prefijo
 *
 * Diferencias con el libro de historia: La inyección de patrón está conmutada(El modo de cambio de usuario entra en vigor inmediatamente),
 * Lorebook Es activado por palabra clave(Escanear el contenido del mensaje del usuario).
 */
class PromptInjectionTransformer : Transformer {

    override val name: String = "PromptInjection"

    override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        // extra contrato: key="prompt_injections",El valor es List<PromptInjectionEntity>.
        // bajo borrado genérico as? List<*> No se puede verificar el tipo de elemento en tiempo de ejecución,retrasado ClassCastException
        // El riesgo corre a cargo del contrato de tipo garantía del llamante.
        val injections = (context.extra("prompt_injections") as? List<*>)?.filterIsInstance<PromptInjectionEntity>()
            ?: return messages
        // filtrado defensivo:No depende del filtrado de la persona que llama enabled
        val enabledInjections = injections.filter { it.enabled }
        if (enabledInjections.isEmpty()) return messages

        val beforeSystem = mutableListOf<UIMessage>()
        val afterSystem = mutableListOf<UIMessage>()
        var totalLen = 0
        enabledInjections.sortedByDescending { it.priority }.forEach { inj ->
            // Truncamiento de contenido único,Presupuesto rápido antiexplosión
            val rawContent = inj.content
            val truncatedContent = if (rawContent.length > MAX_SINGLE_CONTENT) {
                Logger.w(TAG, "Inyectar entrada '${inj.name}' content longitud ${rawContent.length} exceder $MAX_SINGLE_CONTENT,truncado")
                rawContent.take(MAX_SINGLE_CONTENT)
            } else {
                rawContent
            }
            // Límite superior total:Omitir entradas posteriores si se excede el límite
            if (totalLen + truncatedContent.length > MAX_TOTAL_CONTENT) {
                Logger.w(TAG, "La cantidad total de inyección ha excedido $MAX_TOTAL_CONTENT límite de caracteres,Saltar entrada '${inj.name}'")
                return@forEach
            }
            totalLen += truncatedContent.length

            // name escapar:nueva línea / "]" Reemplazar con guión bajo,evitar la destrucción "[ModeInjection: name]" formato de prefijo
            val safeName = inj.name.replace(NAME_ESCAPE_REGEX, "_").trim()
            val sysMsg = UIMessage(
                role = MessageRole.SYSTEM,
                content = "[ModeInjection: $safeName]\n$truncatedContent",
            )
            when (inj.insertionPosition) {
                POSITION_BEFORE_SYSTEM -> beforeSystem.add(sysMsg)
                POSITION_AFTER_SYSTEM -> afterSystem.add(sysMsg)
                else -> {
                    Logger.w(TAG, "desconocido insertionPosition='${inj.insertionPosition}',entrada '${inj.name}' prensa $POSITION_AFTER_SYSTEM Proceso")
                    afterSystem.add(sysMsg)
                }
            }
        }

        val lastSystemIdx = messages.indexOfLast { it.role == MessageRole.SYSTEM }
        val result = mutableListOf<UIMessage>()
        result.addAll(beforeSystem)
        // Phase 8.5 Reparación: Cuando no hay ningún mensaje de SISTEMA,after_system Los elementos se insertan primero.(inmediatamente después antes del sistema),
        // evitar la lógica original lastSystemIdx=-1 cuando afterSystem se descarta silenciosamente
        if (afterSystem.isNotEmpty() && lastSystemIdx == -1) {
            result.addAll(afterSystem)
        }
        messages.forEachIndexed { idx, msg ->
            if (afterSystem.isNotEmpty() && idx == lastSystemIdx) {
                result.add(msg)
                result.addAll(afterSystem)
            } else {
                result.add(msg)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "PromptInjectionTransformer"

        /** constante de posición de inserción:Insertar antes de todos los mensajes del SISTEMA. */
        const val POSITION_BEFORE_SYSTEM = "before_system"

        /** constante de posición de inserción:Insertar después de todos los mensajes del SISTEMA(Predeterminado). */
        const val POSITION_AFTER_SYSTEM = "after_system"

        /** El límite máximo de caracteres para un único contenido inyectado.,Truncamiento por encima del límite. */
        private const val MAX_SINGLE_CONTENT = 4000

        /** El límite máximo de caracteres para el contenido total de todas las entradas inyectadas.,Si se excede el límite, se omitirán las entradas posteriores. */
        private const val MAX_TOTAL_CONTENT = 10000

        // name Personajes de los que hay que escapar.:carácter de nueva línea / "]" (destruirá "[ModeInjection: name]" formato de prefijo)
        private val NAME_ESCAPE_REGEX = Regex("[\\r\\n\\]]")
    }
}
