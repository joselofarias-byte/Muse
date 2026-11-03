package io.zer0.muse.data.assistant

import kotlinx.serialization.Serializable

/**
 * v1.97: Reglas de reemplazo regular de nivel asistente.
 *
 * Reglas de reemplazo regular de nivel asistente,Permitir a los usuarios configurar reglas de reemplazo regulares para cada asistente,
 * Enviar mensaje/Reemplace automáticamente el contenido del texto cuando lo reciba.
 *
 * Escenarios de aplicación:
 *  - Reemplazo de nombre de personaje:poner"AI"Reemplazar con el nombre del personaje
 *  - Filtrado de palabras sensibles:Reemplazar palabras inapropiadas
 *  - Estandarización de formatos:Unificar puntuación y espacios
 *  - Modificación de salida:Etiquetar contenido específico
 *
 * @param id reglas id(identificador único)
 * @param name Nombre de la regla(Para exhibición)
 * @param enabled Ya sea para habilitar
 * @param findRegex encontrar expresión regular
 * @param replaceString reemplazar cadena(apoyo $1, $2 Esperar referencia retrospectiva)
 * @param affectingScope alcance de influencia:"user"(Mensajes de usuario) / "assistant"(Mensaje del asistente) / "both"(bidireccional)
 * @param visualOnly Solo reemplazo visual(No cambia el contenido real enviado.,Cambiar solo la visualización de la interfaz de usuario)
 */
@Serializable
data class AssistantRegex(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: String = "both",
    val visualOnly: Boolean = false,
)
