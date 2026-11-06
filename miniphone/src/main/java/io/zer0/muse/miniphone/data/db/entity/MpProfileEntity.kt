package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * La información del usuario local en el pequeño teléfono celular. Solo se almacena una línea en toda la biblioteca.,La clave principal se fija en [SINGLE_ROW_ID].
 */
@Entity(tableName = "mp_profile")
data class MpProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val nickname: String,
    val avatarUri: String? = null,
    val signature: String? = null,
    val wallpaperUri: String? = null,
    val coverImageUri: String? = null,
) {
    companion object {
        const val SINGLE_ROW_ID: Int = 1
    }
}
