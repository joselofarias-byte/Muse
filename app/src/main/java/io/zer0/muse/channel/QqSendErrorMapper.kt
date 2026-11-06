package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * QQ La plataforma abierta no pudo enviar**legibilidad**.
 *
 * Por qué extraerlo por separado: solo había una oración antes de que fallara el envío `HTTP 400: {...}`,
 * Todo lo que el usuario ve es "No me respondió" y no hay forma de saber si se agotó el tiempo de espera, no se unió al grupo o no tenía permiso.
 * Las direcciones de investigación de estas tres razones son completamente diferentes y deben discutirse por separado.
 *
 * Solo se asignan códigos de error basados ​​en documentos públicos y el resto de los fragmentos de respuesta se transmiten de forma transparente tal como están. ——
 * Inventar el significado del código es peor que no explicarlo.
 */
object QqSendErrorMapper {

    /** El problema es \"demasiado tarde\", no una mala configuración —— No tiene sentido volver a intentarlo. */
    const val CODE_PASSIVE_EXCEEDED = 40034128

    /** Los robots no pertenecen en absoluto a este grupo. */
    const val CODE_BOT_NOT_IN_GROUP = 40034101

    /** No hay permiso de mensaje activo (básicamente no está disponible en escenarios de chat grupal). */
    const val CODE_NO_ACTIVE_PERMISSION = 40034102

    /** Excepción de envío de mensajes no solicitados (control de frecuencia/cuota). */
    const val CODE_ACTIVE_FAILED = 40034105

    /**
     * Extraiga códigos de error QQ del texto de excepción.
     *
     * [raw] en forma de `HTTP 400: {"code":40034102,"message":"Error en el mensaje no solicitado, Sin permiso"}`;
     * También es compatible solo con el cuerpo de respuesta. `err_code` forma de escribir.
     */
    fun extractCode(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        // solo trae “HTTP 400: ” Pele sólo una capa para dichos prefijos; de lo contrario, el JSON simple se destruirá en los primeros dos puntos.
        val body = if (text.startsWith("HTTP", ignoreCase = true)) text.substringAfter(':', text) else text
        val jsonStart = body.indexOf('{')
        return if (text.isEmpty() || jsonStart < 0) {
            null
        } else {
            runCatching {
                val obj = AppJson.parseToJsonElement(body.substring(jsonStart)).jsonObject
                (obj["code"] ?: obj["err_code"])?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            }.getOrNull()
        }
    }

    /**
     * Traduzca el motivo del error en una frase que los usuarios puedan utilizar para solucionar el problema.
     *
     * Distinga entre "demasiado tarde esta vez" y "configuración incorrecta": la primera puede tener éxito pronto la próxima vez,
     * Este último siempre fallará si no se cambia la configuración. —— Los dos tipos de consejos a los usuarios deben ser diferentes.
     */
    fun describe(raw: String?, isGroup: Boolean): String {
        val code = extractCode(raw)
        return when (code) {
            CODE_PASSIVE_EXCEEDED ->
                "Demasiado tarde para responder:QQ Solo permita respuestas dentro de los 5 minutos posteriores a la recepción del mensaje." +
                    if (isGroup) ", chat grupal y no admite mensajes activos" else ""
            CODE_BOT_NOT_IN_GROUP -> "El robot no está en este grupo. Primero agregue el robot al grupo."
            CODE_NO_ACTIVE_PERMISSION -> "Este escenario no tiene permiso de mensajes activos (el chat grupal no admite envíos activos)"
            CODE_ACTIVE_FAILED -> "Error en el envío de mensajes no solicitados (se ha agotado el control de frecuencia o la cuota)"
            else -> raw?.take(200)?.takeIf { it.isNotBlank() } ?: "error desconocido"
        }
    }

    /**
     * **Este**El mensaje no se puede enviar aunque lo vuelva a intentar. —— No es lo mismo que "la configuración está permanentemente rota".
     *
     * Ambos se consideran permanentes, pero tienen significados diferentes:
     *  - clase de configuración ([CODE_BOT_NOT_IN_GROUP] / [CODE_NO_ACTIVE_PERMISSION]): Siempre fallará si no cambia la configuración;
     *  - La ventana pasiva expira ([CODE_PASSIVE_EXCEEDED]): Sólo llegará más tarde. Esta noticia es realmente desesperada.
     *    Sin embargo, el usuario aún puede responder normalmente al siguiente mensaje nuevo.
     *
     * Entonces, cuando la persona que llama sea cierta, debería poner**Entrante actual**Estado final de cosecha (no más reintentos),
     * En lugar de marcar el canal como no disponible.
     */
    fun isPermanent(raw: String?): Boolean = when (extractCode(raw)) {
        CODE_BOT_NOT_IN_GROUP, CODE_NO_ACTIVE_PERMISSION, CODE_PASSIVE_EXCEEDED -> true
        else -> false
    }
}
