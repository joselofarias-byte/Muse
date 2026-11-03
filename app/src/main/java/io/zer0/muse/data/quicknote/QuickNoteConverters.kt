package io.zer0.muse.data.quicknote

import androidx.room.TypeConverter

/**
 * v1.0.17: Etiqueta de registro rápido TypeConverter.
 *
 * poner List<String> Serializar en almacenamiento de cadenas separadas por comas(tags columna),Facilita la recuperación de etiquetas SQL LIKE.
 * Dividir por coma y fusionar al deserializar trim,La cadena vacía devuelve una lista vacía.
 *
 * Nota:El valor de la etiqueta en sí no debe contener comas.(QuickNotesScreen UI Utilice únicamente la sintaxis #tag,sin coma),
 * Así que no es necesario escapar. Si necesita admitir etiquetas que contengan comas en el futuro,Utilice la serialización JSON en su lugar(prensa assistants.tagsJson).
 */
class QuickNoteConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
