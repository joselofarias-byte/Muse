package io.zer0.muse.ui.chat
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.R
import io.zer0.muse.session.WarmupHistory
import io.zer0.muse.transformer.CompressionDiagnostics
import io.zer0.muse.transformer.ContextCompressTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.runManualCompressionStages
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v2.x Refactorización S2: orquestación comprimida contextual extraída de ChatViewModel.
 *
 * Ubicación original:ChatViewModel cadena de compresión automática(triggerAutoCompress /
 * finishCompressionWithCheckpoint / persistLocalCheckpointFallback /
 * persistContextCheckpoint)con cadena de compresión manual(manualCompress /
 * manualCompressContext / announceManualCompressDone / runManualCompress).
 *
 * Propiedades de migración:**extracción pura**——Mueve el código tal como está.,No hay cambios en ninguna lógica./orden/Semántica de excepción.
 * ChatViewModel Mantenga la llamada del delegado en su posición original.
 */
@Suppress(
    "LongParameterList", "LongMethod", "ComplexMethod", "TooGenericExceptionCaught",
    "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "TooManyFunctions",
    "LargeClass", "ComplexCondition", "UnusedPrivateMember", "unused",
)
internal class ChatCompressionCoordinator(
    private val host: ChatCompressionHostBridge,
) {
    fun triggerAutoCompress(sessionId: String) {
        val maxTokens = host.stateStore.state.value.contextMaxTokens
        val currentTokens = host.stateStore.state.value.contextTokenCount
        if (maxTokens <= 0 || currentTokens <= 0 || !host.shouldAutoCompress(currentTokens, maxTokens)) return
        val currentMessages = host.stateStore.messages.value
        if (currentMessages.size < 2) return
        // v2.x(Artículo de exportación de diagnóstico A): La compresión se activa en el rastro (observador puro)
        runCatching { io.zer0.muse.diagnostic.GenerationTrace.noteCompression() }

        host.coroutineScope.launch(AppDispatchers.io) {
            val keepRecent = minOf(COMPRESSION_SAFETY_TAIL_MESSAGES, currentMessages.size - 1).coerceAtLeast(1)
            // v2.x: Toma el límite del último punto de control. —— tanto para"Sólo se contarán los mensajes recién combinados.",También se utiliza para sobrescribir
            val existingCheckpoint =
                try {
                    host.checkpointReader.get(sessionId)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Logger.w("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.readCheckpointFailed(t.message))
                    null
                }
            val currentCheckpointBoundaryId = existingCheckpoint?.lastCoveredMessageId
            val compressContext =
                TransformContext(
                    sessionId = sessionId,
                    modelId = host.stateStore.state.value.currentAssistant?.modelId,
                    extras =
                    mapOf(
                        "compress_enabled" to true,
                        // X-5: Los umbrales de activación forzada manual se unifican a través de CompressionPolicy (semántica: no existe un umbral para la compresión manual).
                        "compress_threshold" to
                            io.zer0.muse.transformer.CompressionPolicy.FORCE_TRIGGER_THRESHOLD,
                        "compress_keep_recent" to keepRecent,
                    ),
                )
            // H-01 Reparación: transform es una función de suspensión,Utilice resultOf en su lugar para evitar engullir CancellationException
            val compressed =
                resultOf {
                    host.contextCompressTransformer.transform(currentMessages, compressContext)
                }.onError { msg, t ->
                    Logger.w("ChatVM", "Auto-compress transform failed: $msg")
                }.getOrNull() ?: currentMessages
            // La noticia realmente se incorporó al resumen esta vez.(Registro de nivel de agua en proceso);Vacío significa que no se presionó correctamente.,Sin escritura de puntos de control
            val compressedEntry = io.zer0.muse.transformer.CompressionSummaryStore.entry(sessionId)
            val coveredIds: Set<String> = compressedEntry?.coveredIds.orEmpty()
            val maxCoveredId: String? = coveredIds.maxOrNull()
            if (compressed.size < currentMessages.size) {
                // v2.3.2: guardia de sesión — La compresión es una llamada de LLM que requiere mucho tiempo,Es posible que el usuario haya abandonado la sesión durante este período.;
                // Los resultados caducados nunca deben escribirse en la sesión actual.(De lo contrario, el resumen de la sesión A se superpondrá en la lista de mensajes de la sesión B.,
                // Aparece la interfaz"contenido de otra conversación",Continuar enviando también enviará el contexto de A al modelo.).
                if (host.displayedSessionId() != sessionId) {
                    Logger.i("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.staleAutoCompressResult(sessionId))
                    return@launch
                }
                finishCompressionWithCheckpoint(
                    sessionId = sessionId,
                    currentMessages = currentMessages,
                    compressed = compressed,
                    coveredIds = coveredIds,
                    maxCoveredId = maxCoveredId,
                    previousBoundaryId = currentCheckpointBoundaryId,
                    previousTotalCovered = existingCheckpoint?.totalCoveredCount ?: 0,
                    keepRecent = keepRecent,
                    tokensBefore = currentTokens,
                    reason = io.zer0.memory.summary.ContextCheckpointEntity.REASON_AUTO,
                )
                Logger.i(
                    "ChatVM",
                    CompressionDiagnostics.checkpointSaved(
                        ratio = "%.2f".format(currentTokens.toFloat() / maxTokens),
                        covered = coveredIds.size,
                        before = currentMessages.size,
                        after = compressed.size,
                    ),
                )
            } else if (host.displayedSessionId() == sessionId) {
                // v2.x El último nivel de la escalera de degradación: sin resultados de resumen (red deficiente/error de modelo de resumen/tiempo de espera/sin beneficio neto).
                // En este momento**No vuelvas con las manos vacías** —— Realice una reconstrucción local con extractos de texto deterministas sin solicitud y aún así escriba un punto de control.
                persistLocalCheckpointFallback(
                    sessionId = sessionId,
                    messages = currentMessages,
                    keepRecent = keepRecent,
                    previousBoundaryId = currentCheckpointBoundaryId,
                    previousTotalCovered = existingCheckpoint?.totalCoveredCount ?: 0,
                    tokensBefore = currentTokens,
                )
            }
        }
    }

    /**
     * v2.x: una vez**éxito**El fin de la compresión —— Punto de control y actualización"Cubierto/Tasa de ocupación"calibre.
     *
     * La compresión automática y manual se comparten: las dos rutas son"¿Qué ve el modelo y cómo calcular la tasa de ocupación?"debe ser completamente consistente,
     * En caso contrario, el usuario toma la iniciativa."Compresión"Obtendrá resultados diferentes a los de la compresión automática (así fue como se bifurcó el comportamiento).
     *
     * Elimine el punto de control en lugar de reescribir la lista de mensajes de memoria: la interfaz continúa mostrando el historial completo (los mensajes antiguos están ocultos de forma predeterminada),
     * Sólo el historial enviado al modelo se filtra por límite; reiniciar la aplicación/Volver a la sesión seguirá surtiendo efecto.
     *
     * @param previousBoundaryId Los límites del último punto de control (`newlyCoveredCount` punto de partida)
     * @param previousTotalCovered El número acumulado de elementos desde el último punto de control (para visualización de interfaz, acumulación de desplazamiento)
     * @param maxCoveredId El valor inferior de la identificación del límite cubierto esta vez (normalmente debería ser [ContextCompressTransformer.lastCoveredBoundaryId])
     */
    private suspend fun finishCompressionWithCheckpoint(
        sessionId: String,
        currentMessages: List<UIMessage>,
        compressed: List<UIMessage>,
        coveredIds: Set<String>,
        maxCoveredId: String?,
        previousBoundaryId: String?,
        previousTotalCovered: Int,
        keepRecent: Int,
        tokensBefore: Int,
        reason: String,
    ) {
        if (maxCoveredId == null) {
            Logger.w("ChatVM", CompressionDiagnostics.NO_COVERAGE_RECORD)
            return
        }
        persistContextCheckpoint(
            sessionId = sessionId,
            previousBoundaryId = previousBoundaryId,
            previousTotalCovered = previousTotalCovered,
            generatedBoundaryId = maxCoveredId,
            tokensBefore = tokensBefore,
            tokensAfter = TokenEstimator.estimate(compressed),
            reason = reason,
            // Los límites y el número de barras se toman de"colección comprimida ordenada", inferencia sin clasificación de ID de mensaje:
            // La identificación del mensaje es un UUID aleatorio y la colección está desordenada. La clasificación seleccionará el límite incorrecto y descartará los mensajes descubiertos.
            boundaryMessageId = host.contextCompressTransformer.lastCoveredBoundaryId,
            newlyCoveredCount = newlyCoveredCount(
                messages = currentMessages,
                coveredIds = coveredIds,
                previousBoundaryId = previousBoundaryId,
            ),
        )
        // Primero se actualiza la colección de cobertura y luego se calcula la ocupación. —— Después de la actualización, host.checkpointCoveredIds es el resultado de esta compresión.
        // v2.6.0 (AUD-UI-F5): Ejecuciones de compresión en la corrutina IO,Es posible que el usuario haya cambiado a otra sesión cuando finalizó.
        // La implementación original estableció incondicionalmente la marca de agua global en la sesión A y la calculó en consecuencia. token,B La conversación luego dice
        // A nivel del agua → La ocupación del contexto se calcula mediante una sesión incorrecta(conversación en cadena).
        // solo aqui“El objetivo de compresión sigue siendo la sesión de visualización actual.”Sólo cuando caiga por debajo del nivel global del agua.;De lo contrario omitir,
        // Deje que sureCheckpointCoveredIds se cargue solo la próxima vez que se muestre la sesión de destino.
        if (host.displayedSessionId() == sessionId) {
            host.checkpointCoveredSessionId = sessionId
            host.refreshCheckpointCoveredIds(sessionId)
            host.updateContextTokenCount()
        }
        // keepRecent Actualmente no participa en el drop de la biblioteca, pero se mantiene en la firma para evitar que quien llama piense que"La política de retención está determinada únicamente por el compresor."
        Logger.d("ChatVM", "checkpoint finished (keepRecent=$keepRecent)")
    }

    /**
     * v2.x: contar"Resumen de esta nueva fusión"el número de mensajes.
     *
     * usar**Lista de mensajes ordenados**Para contar (en lugar de ordenar el conjunto de ID de anulación): la ID del mensaje es un UUID aleatorio,
     * La colección en sí está desordenada y cualquier inferencia basada en comparaciones de identificación se calculará mal con los datos reales.
     * Sólo se consideran los mensajes posteriores al último límite y que se encuentren dentro del conjunto de cobertura."Recién agregado esta vez".
     */
    internal fun newlyCoveredCount(messages: List<UIMessage>, coveredIds: Set<String>, previousBoundaryId: String?): Int =
        host.newlyCoveredCount(messages, coveredIds, previousBoundaryId)

    /**
     * v2.x: Cuando el resumen no está disponible**reconstrucción local**Puntos de control (cero solicitudes de modelo).
     *
     * La cobertura es consistente con la compresión normal: manténgala actualizada [keepRecent] Artículo y el resto son extractos de texto deterministas.
     * El extracto solo intercepta los fragmentos en lenguaje natural de cada artículo sin refinarlos. —— su valor no es"Que bueno el resumen",
     * Pero se encuentra en**Los límites entran en vigor inmediatamente**: El mensaje sobrescrito ya no ingresa al modelo y este estado es persistente.
     */
    private suspend fun persistLocalCheckpointFallback(
        sessionId: String,
        messages: List<UIMessage>,
        keepRecent: Int,
        previousBoundaryId: String?,
        previousTotalCovered: Int,
        tokensBefore: Int,
    ) {
        val compressibleEnd = messages.size - keepRecent
        val covered = if (compressibleEnd <= 0) {
            emptyList()
        } else {
            messages.subList(0, compressibleEnd).filterNot {
                it.role == MessageRole.SYSTEM && it.content.startsWith(io.zer0.muse.transformer.ContextCheckpointMerge.MARKER)
            }
        }
        if (covered.isEmpty()) return
        try {
            val boundary = covered.last().id.toString()
            val coveredSeq = runCatching { host.sessionRepository.getMessageById(boundary)?.seq ?: 0L }.getOrDefault(0L)
            val checkpoint = host.contextCompressTransformer.buildLocalCheckpoint(
                io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                    sessionId = sessionId,
                    covered = covered,
                    previousBoundaryId = previousBoundaryId,
                    coveredSeq = coveredSeq,
                    tokensBefore = tokensBefore,
                    // La devolución del extracto local es"No más texto original", se puede estimar en función de la longitud del extracto (no es preciso, pero está en la dirección correcta)
                    tokensAfter = TokenEstimator.estimate(covered.last().content) + covered.size * 20,
                    reason = io.zer0.memory.summary.ContextCheckpointEntity.REASON_AUTO,
                ),
            ) ?: return
            host.checkpointReader.put(
                checkpoint.copy(totalCoveredCount = previousTotalCovered + covered.size),
            )
            host.checkpointCoveredSessionId = sessionId
            host.refreshCheckpointCoveredIds(sessionId)
            host.updateContextTokenCount()
            Logger.i(
                "ChatVM",
                CompressionDiagnostics.localRebuildSaved(covered = covered.size, before = messages.size),
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Logger.w("ChatVM", CompressionDiagnostics.writeCheckpointFailed(t.message))
        }
    }

    /**
     * v2.x: Escribe el resultado de una compresión como punto de control de la sesión.
     *
     * La diferencia entre el límite y el tiempo anterior es"Resumen de esta fusión"colección de mensajes —— Los puntos de control están traspasados,
     * El nuevo punto de control ya contiene el contenido del antiguo punto de control. Si también se incluye el antiguo límite, hará `coveredCount`/`tokensBefore`
     * Aumento virtual sucesivo (que se muestra como"Ganancias comprimidas"Cuanto más pagas, más grande obtienes, pero en realidad no ahorras tanto).
     *
     * Solo se registrará el registro si falla: si el punto de control no se puede escribir, como máximo habrá que resumirlo nuevamente la próxima vez, lo que no debe afectar la ronda de diálogo actual.
     */
    private suspend fun persistContextCheckpoint(
        sessionId: String,
        previousBoundaryId: String?,
        previousTotalCovered: Int,
        generatedBoundaryId: String,
        tokensBefore: Int,
        tokensAfter: Int,
        reason: String,
        /** esta vez"Fusionado en resumen"La identificación del mensaje de límite (el último en orden cronológico, proporcionado por transformación). */
        boundaryMessageId: String? = null,
        /** El número de resúmenes recién combinados esta vez (calculado como una lista ordenada, inferida sin ordenar por ID de mensaje). */
        newlyCoveredCount: Int = 0,
    ) {
        try {
            // La secuencia real del mensaje de límite en la tabla de mensajes (pase 0 si no se encuentra: el lado del ensamblaje se basará en la identificación del mensaje.seq Sólo límite superior)
            val boundaryForSeq = boundaryMessageId ?: generatedBoundaryId
            val coveredSeq =
                runCatching { host.sessionRepository.getMessageById(boundaryForSeq)?.seq ?: 0L }
                    .getOrDefault(0L)
            val checkpoint = host.contextCompressTransformer.buildCheckpoint(
                io.zer0.muse.transformer.ContextCompressTransformer.CheckpointRequest(
                    sessionId = sessionId,
                    previousBoundaryId = previousBoundaryId,
                    generatedBoundaryId = generatedBoundaryId,
                    coveredSeq = coveredSeq,
                    tokensBefore = tokensBefore,
                    tokensAfter = tokensAfter,
                    reason = reason,
                    boundaryMessageId = boundaryMessageId,
                    previousTotalCovered = previousTotalCovered,
                    newlyCoveredCount = newlyCoveredCount,
                ),
            ) ?: return
            host.checkpointReader.put(checkpoint)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Logger.w("ChatVM", io.zer0.muse.transformer.CompressionDiagnostics.writeCheckpointFailed(t.message))
        }
    }

    /**
     * v0.45: Activar manualmente la compresión de contexto(Normalización de la compresión de la memoria.).
     *
     * @param updateMemoryFirst true = Ajustar primero [MemoryTicker.forceCompileNow] Actualizar memoria(fact/Resumen),
     *                          Recomprimir el historial;false = Sólo comprimir el historial(compresión pura)
     *
     * UI La entrada es la compresión en segundo plano con un solo clic: el sistema selecciona automáticamente la cola segura y no expone al usuario la cantidad de elementos retenidos ni las instrucciones de compresión.
     * El modo de compresión pura todavía se utiliza internamente mediante el comando de barra diagonal.
     *
     * El resultado de la compresión solo reemplaza el contenido en memoria. messages(host.stateStore.messages.value),no persistió en DB
     * (DB Mantener el historial completo de búsquedas/Exportar,Versión en memoria para contexto LLM).
     * Recargar desde la base de datos después de cambiar de sesión,El compresor automático lo procesará nuevamente la próxima vez que lo envíe.
     */
    fun manualCompress(updateMemoryFirst: Boolean = true) {
        val sessionId = host.displayedSessionId()
        val currentMessages = host.stateStore.messages.value
        // La verificación previa converge uniformemente en una única salida:Sin sesión/Muy pocas noticias/Transmisión/Regresa directamente durante la compresión.
        // Mantener la semántica original:solo"Muy pocas noticias"usuario rápido,El resto de las condiciones previas se omiten silenciosamente.
        if (sessionId == null || currentMessages.size < 2 ||
            host.stateStore.state.value.isStreaming || host.stateStore.state.value.isCompressing
        ) {
            if (sessionId != null && currentMessages.size < 2) {
                host.reportError(host.appContext.getString(R.string.err_chat_compress_too_few))
            }
            return
        }

        host.stateStore.state.update {
            it.copy(
                toolsState = it.toolsState.copy(
                    isCompressing = true,
                    compressionPhase = if (updateMemoryFirst) {
                        io.zer0.muse.ui.ManualCompressionPhase.UPDATING_MEMORY
                    } else {
                        io.zer0.muse.ui.ManualCompressionPhase.COMPRESSING_CONTEXT
                    },
                ),
            )
        }
        host.coroutineScope.launch(AppDispatchers.io) {
            runManualCompress(
                sessionId = sessionId,
                currentMessages = currentMessages,
                updateMemoryFirst = updateMemoryFirst,
            )
        }
    }

    /**
     * Transforme el contexto para la compresión manual.
     *
     * La única diferencia con la compresión automática es que"Gatillo forzado + encubrimiento forzado": Si el usuario hace clic activamente en la compresión, no debería ser porque
     * El beneficio no es obvio y no se hace nada; el presupuesto de caracteres todavía se deriva en función de la ventana actual para evitar cortar grandes trozos más allá de la ventana.
     */
    internal fun manualCompressContext(sessionId: String, keepRecent: Int): TransformContext = TransformContext(
        sessionId = sessionId,
        modelId = host.stateStore.state.value.currentAssistant?.modelId,
        extras =
        mapOf(
            "compress_enabled" to true,
            // Activador forzado: el usuario hace clic activamente en la compresión y no debe hacer nada porque los beneficios no son obvios.
            "compress_threshold" to io.zer0.muse.transformer.CompressionPolicy.FORCE_TRIGGER_THRESHOLD,
            "compress_keep_recent" to keepRecent,
            "compress_force_fallback" to true,
            "compress_char_budget" to
                // La compresión manual no puede obtener el límite superior de salida del modelo (aquí solo está la identificación del asistente, no el modelo analizado).
                // Dejemos que la fórmula presupuestaria se encargue de la reserva de producción mínima, que está aún más cerca de la realidad que el antiguo método proporcional.
                WarmupHistory.compressTokenBudgetFor(
                    contextMaxTokens = host.stateStore.state.value.contextMaxTokens,
                ),
        ),
    )

    /**
     * Comentarios externos después de una compresión manual exitosa.
     *
     * v2.x: No más informes"comprimido: N → M Artículo" —— El cambio en la cantidad de elementos no tiene sentido para el usuario y también hará que la gente piense que la sesión se ha cortado.
     * La expresión de compresión en la interfaz es la línea divisoria residente en el flujo de diálogo (ver actualizarContextInfo), y aquí solo se proporciona una oración de estado.
     */
    internal fun announceManualCompressDone(beforeCount: Int, afterCount: Int, keepRecent: Int) {
        // Los registros son diagnósticos internos.,No utilices literales chinos.(Evite errores de juicio sobre el control de acceso CJK)
        Logger.i(
            "ChatVM",
            "manualCompress: $beforeCount -> $afterCount msgs (keepRecent=$keepRecent)",
        )
        MuseToast.show(host.appContext.getString(R.string.err_chat_compress_done))
    }

    /** Ejecutable real comprimido manualmente (de [manualCompress] extraído para una fácil lectura y prueba). */
    internal suspend fun runManualCompress(sessionId: String, currentMessages: List<UIMessage>, updateMemoryFirst: Boolean) {
        try {
            val effectiveKeepRecent =
                minOf(COMPRESSION_SAFETY_TAIL_MESSAGES, currentMessages.size - 1).coerceAtLeast(1)
            // v2.x: La compresión manual también requiere puntos de control, así que primero tome un punto de control existente (límite + número acumulado de elementos)
            val manualExisting =
                runCatching { host.checkpointReader.get(sessionId) }.getOrNull()
            val manualPreviousBoundaryId = manualExisting?.lastCoveredMessageId
            val manualPreviousTotalCovered = manualExisting?.totalCoveredCount ?: 0
            val manualTokensBefore = host.stateStore.state.value.contextTokenCount
            val context = manualCompressContext(sessionId, effectiveKeepRecent)

            val compressed = runManualCompressionStages(
                updateMemoryFirst = updateMemoryFirst,
                onPhase = { phase ->
                    host.stateStore.state.update { state ->
                        state.copy(
                            toolsState = state.toolsState.copy(compressionPhase = phase),
                        )
                    }
                },
                updateMemory = {
                    // Refinación forzada fact + deep memory + Actualizar today;El fallo no bloquea la compresión posterior.
                    val model = resultOf { host.settings.getSelectedModel() }.getOrNull()
                    resultOf {
                        host.memoryTicker.forceCompileNow(model = model)
                    }.onError { msg, _ ->
                        Logger.w("ChatVM", "forceCompileNow failed: $msg")
                        MuseToast.show(host.appContext.getString(R.string.err_chat_compress_memory_failed))
                    }
                },
                compress = {
                    // H-01 Reparación: transform es una función de suspensión,Utilice resultOf en su lugar para evitar engullir CancellationException
                    resultOf {
                        host.contextCompressTransformer.transform(currentMessages, context)
                    }.onError { msg, _ ->
                        Logger.w("ChatVM", "manualCompress transform failed: $msg")
                    }.getOrNull() ?: currentMessages
                },
            )
            // 3. Reemplazar en la memoria messages(No persistente,DB Mantener el historial completo)
            if (compressed.size < currentMessages.size) {
                // v2.3.2: guardia de sesión — Lo mismo triggerAutoCompress,Si la sesión se cambia durante la compresión, los resultados caducados se descartarán.
                // (isCompressing finalmente reiniciado por la capa exterior,Puedes regresar directamente aquí)
                if (host.displayedSessionId() != sessionId) {
                    Logger.i("ChatVM", "manualCompress Los resultados han caducado(Sesión cambiada),descartar: $sessionId")
                    return
                }
                // v1.117: Corregir condición de carrera de pérdida de mensajes — La compresión es una llamada suspendida de LLM,toma varios segundos,
                // Los usuarios podrán seguir enviando nuevos mensajes durante este periodo.(ha sido añadido a host.stateStore.messages.value).
                // Sobrescribir directamente la versión comprimida de la instantánea anterior descartará estos mensajes nuevos.
                // v2.x: Unificar con compresión automática —— Resultado de compresión completado**punto de control de sesión**, ya no reescribe la lista de mensajes de memoria.
                // Dos significados:① La interfaz conserva el historial completo (los mensajes antiguos están ocultos de forma predeterminada, consulte la línea separadora en ChatScreen),
                // Sólo el historial enviado al modelo se filtra por límite;② Las rutas manuales y automáticas se comportan exactamente igual y no se verán afectadas por
                // Iniciativa del usuario"Compresión"Y volvamos al viejo comportamiento. La lógica de cierre comparte el mismo asistente que la ruta automática.
                val manualCoveredIds =
                    io.zer0.muse.transformer.CompressionSummaryStore.entry(sessionId)?.coveredIds.orEmpty()
                finishCompressionWithCheckpoint(
                    sessionId = sessionId,
                    currentMessages = currentMessages,
                    compressed = compressed,
                    coveredIds = manualCoveredIds,
                    maxCoveredId = manualCoveredIds.maxOrNull(),
                    previousBoundaryId = manualPreviousBoundaryId,
                    previousTotalCovered = manualPreviousTotalCovered,
                    keepRecent = effectiveKeepRecent,
                    tokensBefore = manualTokensBefore,
                    reason = io.zer0.memory.summary.ContextCheckpointEntity.REASON_MANUAL,
                )
                announceManualCompressDone(currentMessages.size, compressed.size, effectiveKeepRecent)
            } else if (updateMemoryFirst) {
                // La compresión no tiene efecto(Es posible que haya muy pocos mensajes o que LLM devuelva un resumen vacío),Pero la memoria ha sido actualizada.
                MuseToast.show(host.appContext.getString(R.string.err_chat_compress_no_need))
            }
            // 4. Actualizar el recuento de tokens(Mensaje de reconstrucción del sistema ya que es posible que se haya actualizado la memoria)
            host.refreshContextInfo()
        } catch (e: Exception) {
            Logger.w("ChatVM", "manualCompress failed: ${e.message}")
            host.reportError(host.appContext.getString(R.string.err_chat_compress_failed, e.message ?: ""))
        } finally {
            host.stateStore.state.update {
                it.copy(
                    toolsState = it.toolsState.copy(
                        isCompressing = false,
                        compressionPhase = io.zer0.muse.ui.ManualCompressionPhase.IDLE,
                    ),
                )
            }
        }
    }

    /** Constantes migradas desde ChatViewModel.companion(Referencia directa al enlace de compresión original.). */
    private companion object {
        const val COMPRESSION_SAFETY_TAIL_MESSAGES = 10
    }
}
