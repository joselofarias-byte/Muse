package io.zer0.muse.miniphone.data.db

import androidx.room.TypeConverter

/**
 * Room enumeración ↔ Conversión de columnas de texto.
 *
 * Almacenar cadenas en minúsculas en la biblioteca(en lugar del número de serie),Es conveniente leer SQL directamente y agregar y eliminar elementos de enumeración entre versiones.;
 * Cuando la deserialización encuentra un valor desconocido, se devolverá el primer elemento de la enumeración.,Asegúrese de que la biblioteca antigua no falle cuando se lean valores sucios.
 */
class MpConverters {
    @TypeConverter
    fun actorToString(value: MpActorType): String = value.value

    @TypeConverter
    fun stringToActor(value: String): MpActorType = MpActorType.fromValue(value)

    @TypeConverter
    fun messageTypeToString(value: MpMessageType): String = value.value

    @TypeConverter
    fun stringToMessageType(value: String): MpMessageType = MpMessageType.fromValue(value)

    @TypeConverter
    fun messageStatusToInt(value: MpMessageStatus): Int = value.value

    @TypeConverter
    fun intToMessageStatus(value: Int): MpMessageStatus = MpMessageStatus.fromValue(value)

    @TypeConverter
    fun momentSourceToString(value: MpMomentSource): String = value.value

    @TypeConverter
    fun stringToMomentSource(value: String): MpMomentSource = MpMomentSource.fromValue(value)

    @TypeConverter
    fun albumSourceToString(value: MpAlbumSource): String = value.value

    @TypeConverter
    fun stringToAlbumSource(value: String): MpAlbumSource = MpAlbumSource.fromValue(value)
}
