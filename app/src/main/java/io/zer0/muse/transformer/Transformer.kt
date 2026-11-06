package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage

/**
 * Interfaz del transformador de mensajes(Phase 8.1 H1).
 *
 * Escrito de forma independiente.
 * Todo el preprocesamiento de listas de mensajes./El posprocesamiento se abstrae como Transformer,
 * prensa [TransformerPipeline] La secuencia se ejecuta en una tubería.
 *
 * principios de diseño:
 *  - función pura: Misma entrada y misma salida,Sin efectos secundarios(Excepto aquellos marcados con suspender IO)
 *  - Se puede combinar: Múltiples transformadores en serie,Borrar orden
 *  - comprobable: Pruebas unitarias independientes para cada Transformador
 *  - Configurable: Depende de la persona que llama decidir qué Transformer(Como la configuración del asistente)
 *
 * Diseño de triple gancho:
 *  - [transform]: Preprocesamiento antes de enviar a LLM(Cambiar mensaje,Afecta las solicitudes reales)
 *  - [visualTransform]: Transiciones visuales durante la transmisión(No cambies el mensaje real.,Solo para visualización de UI)
 *  - [onGenerationFinish]: Procesamiento final tras generación.(El mensaje final se puede cambiar.)
 * Los tres tienen implementaciones predeterminadas.(Volver como está),Manténgase compatible con versiones anteriores,No se necesitan cambios en el antiguo Transformer.
 *
 * Uso:
 * ```
 * class MyTransformer : Transformer {
 *     override val name = "MyTransformer"
 *     override suspend fun transform(messages: List<UIMessage>): List<UIMessage> = ...
 * }
 *
 * val pipeline = TransformerPipeline(listOf(MyTransformer(), AnotherTransformer()))
 * val processed = pipeline.execute(rawMessages)
 * ```
 *
 * Nota: Transformer procesado es"Lista de mensajes a enviar",No es un delta de transmisión.
 * Las respuestas en streaming son enviadas directamente por el proveedor. ChatStreamEvent,Transformador no andante.
 */
interface Transformer {
    /** Transformer nombre único(para depurar/Iniciar sesión). */
    val name: String

    /**
     * Lista de mensajes de proceso,Volver nueva lista(inmutable,La lista original permanece sin cambios.).
     * suspend permitido hacer IO(Como leer Memory.db/llamar a la compresión LLM).
     *
     * @param context El contexto de la solicitud actual.(modelo/temperatura/identificación de sesión, etc.)
     */
    suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage>

    /**
     * Transformación visual durante la transmisión(No cambies el mensaje real.,Solo para visualización de UI).
     *
     * con [transform] la diferencia:
     *  - [transform] Cambiar mensaje enviado a LLM,Afecta las solicitudes reales
     *  - [visualTransform] Solo cambia la UI"ver"noticias,No afecta el almacenamiento real/Solicitar
     *
     * Escenario típico:
     *  - Se detectó abierto en citometría de flujo. `<think>` etiqueta,cerrado/La parte no cerrada se muestra en tiempo real como reasoning
     *  - Desnudo en vivo durante la transmisión `<mood>` Etiqueta al campo de estado de ánimo,UI Contraer visualización
     *
     * Implementación predeterminada: Volver como está(Sin conversión),Mantener la compatibilidad con versiones anteriores.
     *
     * @param context El contexto de la solicitud actual.
     */
    suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        return messages
    }

    /**
     * Procesamiento final una vez completada la construcción.
     *
     * con [transform] la diferencia:
     *  - [transform] Procesar mensajes históricos antes de enviar una solicitud.
     *  - [onGenerationFinish] Procese el mensaje final del asistente una vez completada la compilación(Puede cambiar el contenido/razonamiento/estado de ánimo, etc.)
     *
     * Escenario típico:
     *  - Finalmente limpie las etiquetas de pensamiento no cerradas
     *  - Extraiga metadatos como artefacto/cita
     *  - Extracción final del bloque de autorreflexión.
     *
     * Implementación predeterminada: Volver como está(No procesado),Mantener la compatibilidad con versiones anteriores.
     *
     * @param context El contexto de la solicitud actual.
     */
    suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        return messages
    }
}

/**
 * Transformer Contexto de ejecución.
 *
 * @param sessionId sesión actual id(Utilizado para lectura y escritura de memoria.)
 * @param modelId modelo actual id(Se utiliza para decidir si se permite el razonamiento, etc.)
 * @param temperature Temperatura de muestreo
 * @param maxTokens Límite superior de salida
 * @param extras parámetros adicionales(Cada transformador está personalizado. key). escribe inseguro,La persona que llama debe verificar el valor al tomarlo./Convertir.
 *  conocido key(Como referencia para cada transformador.,no exhaustivo):
 *   - "memory_enabled": Boolean — Ya sea para inyectar resúmenes de memoria a largo plazo
 *   - "compress_threshold": Int — El umbral para la cantidad de mensajes que desencadena la compresión histórica
 *   - "lorebook_entries": List<*> — Lorebook entrada(libro mundial/L2 Inyección de desencadenador de palabras clave)
 *   - "prompt_injections": List<String> — Adjunte un fragmento de mensaje inyectado en tiempo de ejecución
 *   - "time_reminder_enabled": Boolean — Si se debe habilitar el recordatorio de tiempo section
 *   - "pinned_memories_enabled": Boolean — Ya sea para inyectar Pinned Memories
 *   - "tool_manifest_enabled": Boolean — Ya sea para inyectar la lista de herramientas
 *   - "group_chat": Boolean — ¿Estás en un entorno de chat grupal?
 *  Cuando cada Transformer personalice una nueva clave, declararla en el KDoc del Transformer correspondiente.,Evite los conflictos de nombres.
 */
data class TransformContext(
    val sessionId: String? = null,
    val modelId: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val extras: Map<String, Any?> = emptyMap(),
) {
    /** Adquisición de valor conveniente. */
    fun extra(key: String): Any? = extras[key]
}
