package io.zer0.muse.tools

/**
 * Contexto inolvidable inyectado por el enlace del host cuando se ejecuta la herramienta.
 *
 * Los parámetros del modelo solo describen“Que comprobar”, no obtendrá la capacidad de modificar el alcance de la memoria.
 */
data class ToolExecutionContext(
    val scope: String,
    val spaceId: String,
    val assistantId: String? = null,
    /** v2.x: sesión de anfitrión id(find_tools Carga dinámica y otros usos lógicos que surten efecto por sesión);El antiguo sitio de llamadas tiene como valor predeterminado nulo. */
    val sessionId: String? = null,
    /**
     * El ID del mensaje del asistente que se genera en esta ronda.
     *
     * La identidad de una tarjeta es la identificación del mensaje que la lleva (ver `ShowCardTool` descripción),
     * Pero el modelo no puede saber esto durante el proceso de generación. id —— Lo genera el host al crear un mensaje de marcador de posición.
     * Si no se inyecta,`update_card_data` Nunca podrás conseguir una clave real.
     * La única opción es pedirle al modelo que adivine por sí mismo (lo cual es imposible hacerlo bien) o escribir silenciosamente en una clave que nadie pueda leer.
     */
    val messageId: String? = null,
)
