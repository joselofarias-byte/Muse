package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger

/**
 * Transformer actuador de tubería(Phase 8.1 H1).
 *
 * Ejecutar todo en orden Transformer,La salida del anterior sirve como entrada del siguiente.
 * Un solo transformador genera una excepción sin interrumpir toda la tubería,Saltar después de iniciar sesión(tolerancia a fallas).
 *
 * Uso:
 * ```
 * val pipeline = TransformerPipeline(listOf(
 *     MemoryInjectionTransformer(memoryTicker, settings),
 *     ThinkTagTransformer(),
 *     // ...
 * ))
 * val processed = pipeline.execute(messages, context)
 * ```
 *
 * compensaciones de diseño:
 *  - La tolerancia a fallos es mejor que el rigor: La falla de un solo transformador no bloquea el proceso principal,El chat aún puede continuar
 *  - orden sensible: Transformer Ejecutar en orden de suma,El orden lo garantiza el que llama.
 *  - Paralelismo: Ejecución en serie actual,En el futuro se podrá considerar el paralelismo sin dependencia.(Pero la lista de mensajes tiene estado.,Complejidad paralela)
 */
class TransformerPipeline(
    private val transformers: List<Transformer>,
) {
    /**
     * Canal de ejecución.
     *
     * No es obligatorio especificar la convención de interfaz despachador:Transformador. El transformador tipo IO debe `withContext(IO)`,
     * Mantenga la suspensión aquí para permitir que Transformer decida por sí solo.,Evite imponer una sobrecarga de conmutación de subprocesos en transformadores puramente computacionales.
     *
     * @param messages Lista de mensajes originales
     * @param context contexto de ejecución
     * @return Lista de mensajes procesados;Si la tubería está vacía,Volver como está
     */
    suspend fun execute(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        // L-PIPE3: La tubería vacía regresa temprano,Evite cambios innecesarios del programador/bucle
        if (transformers.isEmpty()) return messages
        var current = messages
        for (transformer in transformers) {
            current = try {
                transformer.transform(current, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // H-PIPE1: La cancelación de la rutina debe volver a lanzarse,no tragar
                throw e
            } catch (e: Error) {
                // M-PIPE2: OOM/StackOverflow etc. El error no es una excepción recuperable.,Vuelve a lanzar y deja que el nivel superior se encargue de ello.
                throw e
            } catch (err: Exception) {
                // L-PIPE4: Solo recuerda el nombre de la clase + truncado message,Evite que los registros filtren el contenido de los mensajes
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e("TransformerPipeline", "Transformer ${transformer.name} failed, skipping: $summary")
                current // Saltar si falla,Mantener el resultado del paso anterior
            }
        }
        return current
    }

    /**
     * Aplicar todos los transformadores. [Transformer.visualTransform] gancho(Transformación visual en streaming).
     *
     * utilizado en el proceso de transmisión:Ejecute el enlace visual en la lista de mensajes que muestra la interfaz de usuario actual,
     * La lista devuelta solo se usa para la representación de la interfaz de usuario.,El mensaje almacenado real no se reemplaza.
     *
     * con [execute] La misma estrategia de tolerancia a fallos:La falla de un solo transformador no bloquea la tubería.
     *
     * @param messages Lista de mensajes actualmente en la interfaz de usuario
     * @param context contexto de ejecución
     * @return lista convertida(Solo para visualización de UI,No reemplaza el mensaje real.)
     */
    suspend fun applyVisualTransform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        if (transformers.isEmpty()) return messages
        var current = messages
        for (transformer in transformers) {
            current = try {
                transformer.visualTransform(current, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e("TransformerPipeline", "Transformer ${transformer.name} visualTransform failed, skipping: $summary")
                current
            }
        }
        return current
    }

    /**
     * Aplicar todos los transformadores. [Transformer.onGenerationFinish] gancho(Procesamiento de posgeneración).
     *
     * Se utiliza una vez completada la generación.:Ejecute el gancho de finalización en la lista de mensajes final,
     * La lista devuelta se puede utilizar para reemplazar los mensajes almacenados reales.(Depende de la persona que llama decidir si realizar el pedido.).
     *
     * con [execute] La misma estrategia de tolerancia a fallos:La falla de un solo transformador no bloquea la tubería.
     *
     * @param messages Generar la lista de mensajes una vez completada
     * @param context contexto de ejecución
     * @return lista procesada(Puede reemplazar el mensaje real)
     */
    suspend fun applyOnGenerationFinish(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        if (transformers.isEmpty()) return messages
        var current = messages
        for (transformer in transformers) {
            current = try {
                transformer.onGenerationFinish(current, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e("TransformerPipeline", "Transformer ${transformer.name} onGenerationFinish failed, skipping: $summary")
                current
            }
        }
        return current
    }

    /** Estructura conveniente: Patrón de constructor. */
    class Builder {
        private val list = mutableListOf<Transformer>()
        fun add(transformer: Transformer): Builder = apply { list.add(transformer) }
        fun build(): TransformerPipeline = TransformerPipeline(list.toList())
    }
}
