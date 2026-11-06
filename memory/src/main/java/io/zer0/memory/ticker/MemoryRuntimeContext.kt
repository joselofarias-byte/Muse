package io.zer0.memory.ticker

/** Recuerde la configuración del tiempo de ejecución de tareas en segundo plano con el lector de instantáneas espaciales. */
data class MemoryRuntimeContext(
    val getConfig: () -> MemoryConfig = { MemoryConfig() },
    val getCurrentSpaceId: suspend () -> String = { "default" },
    val getCurrentAssistantId: suspend () -> String = { "default" },
)
