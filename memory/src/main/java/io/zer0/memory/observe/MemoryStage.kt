package io.zer0.memory.observe

/**
 * D3-P1: Etiqueta de cinco etapas de la canalización de memoria.
 *
 * utilizado para agregar existente [io.zer0.memory.ticker.MemoryTicker] Los pasos están etiquetados con sus correspondientes etapas conceptuales.,
 * Deja que los datos de observación.(ver [PipelineLog])puede presionar Ingest→Extract→Index→Retrieve→Inject agregación.
 *
 * Nota:P1 solo escenario"marca",No cambia la implementación real ni el orden de llamada de los pasos existentes.;real
 * Cinco etapas de desacoplamiento(Cada paso puede fallar individualmente/Inténtalo de nuevo/observación)Déjelo para etapas posteriores.
 */
enum class MemoryStage {
    /** Entrada a la generación de memoria.:Conversación guardada en tiempo real, excavación diaria, manual/Importación de reabastecimiento. */
    INGEST,

    /** LLM Extracto:sesión → abstracto, abstracto → Hechos atómicos. */
    EXTRACT,

    /** escribir/Orquestar almacenamiento:El producto compilado se divide en Sala/archivo, desplazamiento de ventana y conciliación. */
    INDEX,

    /** Recuperación del lado de inyección:Recordar recuerdos relevantes basados ​​en el problema actual. */
    RETRIEVE,

    /** system prompt Inyección de montaje. */
    INJECT,
    ;

    companion object {
        /**
         * sección de tubería → Mapeo predeterminado para el escenario principal.
         *
         * Solo se da un paso para cada párrafo."escenario principal"(Los pasos suelen incluir extracción y colocación al mismo tiempo.,Haga clic aquí
         * Clasificación de atribución de productos):Resumen/extracción de hechos EXTRACT,El diseño del producto compilado pertenece a INDEX.
         */
        fun ofStep(stepKey: String): MemoryStage = when (stepKey) {
            "rollingSummary" -> EXTRACT
            "deepMemory", "deepMemory.sessionEnd" -> EXTRACT
            "compileDaily", "compileToday", "rollDailyWindow", "compileFacts",
            "assembleWeekFromDaily", "reconcileFacts",
            -> INDEX
            "autoSave" -> EXTRACT
            else -> INDEX
        }

        /** Pasos de la etapa de búsqueda(lado de inyección,Actualmente activado por SystemPromptAssembler). */
        fun ofRetrieveStep(stepKey: String): MemoryStage = when (stepKey) {
            "relevantMemory" -> RETRIEVE
            "longTermMemory" -> INJECT
            else -> RETRIEVE
        }
    }
}
