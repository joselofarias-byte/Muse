package io.zer0.muse.miniphone.data.db

/**
 * La fuente de una acción.:Usuario local, rol en teléfono celular.,O el propio teléfono.
 *
 * Los remitentes de mensajes privados, los me gusta dinámicos y los comentaristas dinámicos comparten este conjunto de valores.,Las cadenas en minúsculas se almacenan de manera uniforme al iniciar sesión en la biblioteca.
 * [SYSTEM] Solo se usa para mensajes del sistema en mensajes privados.(Por ejemplo, la otra parte no responde.),No pertenece a ningún partido.
 */
enum class MpActorType(
    val value: String,
) {
    USER("user"),
    CHARACTER("character"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromValue(value: String): MpActorType = entries.firstOrNull { it.value == value } ?: USER
    }
}

/** Tipo de mensaje. */
enum class MpMessageType(
    val value: String,
) {
    TEXT("text"),
    IMAGE("image"),
    VOICE("voice"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromValue(value: String): MpMessageType = entries.firstOrNull { it.value == value } ?: TEXT
    }
}

/**
 * El estado de envío de un mensaje privado.
 *
 * Almacenar números enteros en la biblioteca.(en lugar del nombre de la enumeración),La fila existente no tiene esta columna.,Al migrar, haga clic en "Problema" para completar el proceso.:
 * Los datos antiguos son todos los mensajes que se han enviado.,Marcarlos como envío hará que aparezca de la nada una fila adicional de estado de progreso en la interfaz.
 */
enum class MpMessageStatus(
    val value: Int,
) {
    SENT(0),
    SENDING(1),
    FAILED(2),
    ;

    companion object {
        fun fromValue(value: Int): MpMessageStatus = entries.firstOrNull { it.value == value } ?: SENT
    }
}

/** fuente dinámica:personaje generado,Fue enviado por el propio usuario local. */
enum class MpMomentSource(
    val value: String,
) {
    AI("ai"),
    USER("user"),
    ;

    companion object {
        fun fromValue(value: String): MpMomentSource = entries.firstOrNull { it.value == value } ?: USER
    }
}

/** Fuente de las entradas del álbum:Diagrama de generación de personajes.,O el usuario importa desde fuera. */
enum class MpAlbumSource(
    val value: String,
) {
    AI("ai"),
    IMPORTED("imported"),
    ;

    companion object {
        fun fromValue(value: String): MpAlbumSource = entries.firstOrNull { it.value == value } ?: IMPORTED
    }
}
