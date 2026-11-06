package io.zer0.muse.data.lorebook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Lorebook(libro mundial)entidad — Phase 8.5.
 *
 * Escritura e implementación independientes.: Inyección de contexto activada por palabras clave.
 * Cuando un mensaje de usuario contiene [keywords] Cuando cualquiera de las palabras clave,poner [content] como contexto del SISTEMA
 * Inyectar en el prefijo de la conversación,dejar AI "pensar en" esta configuración. Comúnmente utilizado en juegos de rol./Configuración de la visión del mundo.
 *
 * Phase 8.5 Reparación: Todos los campos NO NULOS más @ColumnInfo(defaultValue=...) Alineado con MIGRATION_4_5 SQL.
 */
@Serializable
@Entity(tableName = "lorebooks")
data class LorebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "[]") val keywordsJson: String = "[]",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val caseSensitive: Boolean = false,
    /**
     * v1.0.47: patrón de coincidencia de palabras completas — Las palabras clave deben ir precedidas y seguidas de caracteres que no sean palabras.(o límite de cadena)solo golpea,Reducir la activación falsa de subcadenas.
     * Por ejemplo palabras clave"cat"en wholeWord=true no golpeará cuando"category".
     * definición de límite de palabra:\b(Subrayado alfanumérico versus guión bajo no alfanumérico/límites de cadena).
     * compatible con versiones anteriores:Predeterminado false,Mantenga el comportamiento de contenido original.
     */
    @ColumnInfo(defaultValue = "0") val wholeWord: Boolean = false,
    @ColumnInfo(defaultValue = "after_system") val insertionPosition: String = "after_system",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
