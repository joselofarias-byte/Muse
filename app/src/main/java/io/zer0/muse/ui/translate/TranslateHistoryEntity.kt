package io.zer0.muse.ui.translate

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.0.17: Persistencia del historial de traducción.
 */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "translate_history",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["source_language", "target_language"]),
        Index(value = ["favorite"]),
    ],
)
data class TranslateHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String,
    @ColumnInfo(name = "source_language") val sourceLanguage: String,
    @ColumnInfo(name = "target_language") val targetLanguage: String,
    @ColumnInfo(name = "style") val style: String = "Universales",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // v1.0.30 gap4.3: Favoritos de traducción,1=Recogido,0=No favorito
    @ColumnInfo(name = "favorite") val favorite: Boolean = false,
)
