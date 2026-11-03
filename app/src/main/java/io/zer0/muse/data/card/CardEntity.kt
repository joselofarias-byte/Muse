package io.zer0.muse.data.card

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * entidad de tarjeta (v2.6.8 / MuseDb 118).
 *
 * ¿Por qué aparece esta tabla? La tarjeta anteriormente era "un fragmento de JSON al final del texto del mensaje + card_data.json en proceso".
 * Una vez que la tarjeta abandona el flujo de mensajes (se arrastra hacia un lado y se acopla, o se presenta en la ventana flotante del sistema), no hay lugar para almacenar su contenido y estado.
 * Tampoco hay una identidad estable entre reinicios (`c_xxx` Es la marca de tiempo + se incrementa automáticamente en el proceso y se puede eliminar después de reiniciar). Esta tabla actualiza la tarjeta a
 * Entidades de primera clase:UUID Clave principal, contenido (code/dataJson) y estado atracado (dockSlot/dockedAt) todos caen aquí,
 * El lado del mensaje solo conserva `cardId` Referencia, utilizada para la barra de marcador de posición y el posicionamiento de arrastre hacia atrás.
 *
 * Relación con el mensaje (no agregue claves externas aquí):
 * - Las tarjetas pueden existir independientemente del flujo de mensajes y, si se elimina el mensaje, la tarjeta no debe eliminarse también;
 * - [originMessageId] / [originSessionId] Es sólo información de trazabilidad y puede dejarse vacía.
 *
 * Semántica del muelle:
 * - [dockSlot] para NULL = Solo se presenta en línea en el flujo de mensajes y no participa en el área de acoplamiento;
 * - [dockSlot] >= 0 = orden de atraque (0 ), el índice único asegura que no habrá dos tarjetas en la misma ranura.
 *   SQLite El índice único trata a NULL como desigual, por lo que "varias tarjetas no acopladas" no entrarán en conflicto entre sí.
 * - arrastrar hacia atrás solo poner [dockSlot] Vuelva a establecer NULL y no elimine filas (consulte las especificaciones de diseño §3.2).
 */
@Entity(
    tableName = "card_entities",
    indices = [
        Index(value = ["dockSlot"], unique = true, name = "index_card_entities_dockSlot"),
        Index(value = ["originMessageId"], name = "index_card_entities_originMessageId"),
    ],
)
data class CardEntity(
    /** UUID, identidad de tarjeta estable durante los reinicios. */
    @PrimaryKey val id: String,
    /**
     * viejo `c_xxx` ID de tarjeta, solo para completar la coincidencia de datos existentes.
     *
     * El lado de lectura conserva la capacidad de "encontrar la tarjeta por la identificación anterior" y el lado de escritura ya no genera dichas identificaciones.
     */
    @ColumnInfo(defaultValue = "NULL") val legacyCardId: String? = null,
    /** ID de sesión de origen. Una vez que una tarjeta abandona la transmisión, aún puedes rastrear la conversación a la que pertenece. */
    @ColumnInfo(defaultValue = "NULL") val originSessionId: String? = null,
    /** ID del mensaje fuente. Se utiliza para la barra de marcador de posición del flujo de mensajes y el posicionamiento de arrastre hacia atrás. */
    @ColumnInfo(defaultValue = "NULL") val originMessageId: String? = null,
    /** Título de la tarjeta. */
    @ColumnInfo(defaultValue = "") val title: String = "",
    /** HTML / SVG fuente. */
    @ColumnInfo(defaultValue = "") val code: String = "",
    /** Rama de renderizado:true = SVG,false = HTML. */
    @ColumnInfo(defaultValue = "0") val isSvg: Boolean = false,
    /** Vincular datos (texto JSON en card_data.json original). */
    @ColumnInfo(defaultValue = "NULL") val dataJson: String? = null,
    /** NULL = Sólo en el flujo de mensajes;>= 0 = secuencia de atraque. */
    @ColumnInfo(defaultValue = "NULL") val dockSlot: Int? = null,
    /** Tiempo de muelle, utilizado para clasificar (dockSlot Cuando falten slots al mismo o al dato histórico). */
    @ColumnInfo(defaultValue = "NULL") val dockedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * Entidad de tarjeta DAO.
 *
 * Solo acceso, sin juicio empresarial: cómo asignar espacios y arrastrarlos/¿Cuándo ocurre el retroceso y si el relleno es exitoso?
 * Todos están determinados por el almacenamiento de nivel superior. Los métodos aquí siempre devuelven la fila original o el número de filas afectadas.
 */
@Dao
@Suppress("TooManyFunctions") // Las entradas de lectura y escritura del dominio de la tarjeta están naturalmente separadas (por identificación/información de origen/identificación anterior/área de acoplamiento/lleno/borrado/adición y eliminación de ranuras), y la fusión provocará fragmentación.
interface CardEntityDao {

    @Query("SELECT * FROM card_entities WHERE id = :id")
    suspend fun getById(id: String): CardEntity?

    /**
     * Obtenga la tarjeta según el mensaje fuente.
     *
     * Devuelve una lista en lugar de un solo mensaje: un mensaje puede contener varias cartas (barajas), una carta por línea.
     * Cuando se necesita el "marcador de posición de la tarjeta para el mensaje", la persona que llama puede seleccionarlo presionando dockSlot/creadoAt.
     */
    @Query("SELECT * FROM card_entities WHERE originMessageId = :originMessageId ORDER BY createdAt ASC")
    suspend fun getByOriginMessageId(originMessageId: String): List<CardEntity>

    /** prensa vieja `c_xxx` id Leer (compatible con datos existentes). */
    @Query("SELECT * FROM card_entities WHERE legacyCardId = :legacyCardId LIMIT 1")
    suspend fun getByLegacyCardId(legacyCardId: String): CardEntity?

    /** Todas las tarjetas de muelle, en orden ascendente por ranura (la fuente de orden del buscapersonas del área de muelle). */
    @Query("SELECT * FROM card_entities WHERE dockSlot IS NOT NULL ORDER BY dockSlot ASC")
    suspend fun getDockedCards(): List<CardEntity>

    /** Lo mismo ocurre con las suscripciones al área de atraque. */
    @Query("SELECT * FROM card_entities WHERE dockSlot IS NOT NULL ORDER BY dockSlot ASC")
    fun observeDockedCards(): Flow<List<CardEntity>>

    /**
     * Importe total (para copia de seguridad y exportación).
     *
     * Sin ORDER BY: el orden de exportación lo determina el lado de respaldo [io.zer0.muse.backup.inDockOrder] determinado,
     * Las reglas de clasificación pertenecen al contrato de formato electrónico y no deben ocultarse en la DAO; aquí sólo entregamos las líneas tal como están.
     */
    @Query("SELECT * FROM card_entities")
    suspend fun getAll(): List<CardEntity>

    @Query("SELECT COUNT(*) FROM card_entities")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(card: CardEntity)

    /**
     * Escritura por lotes (para copia de seguridad y recuperación), se reemplazarán los conflictos.
     *
     * usar `@Insert(REPLACE)` en lugar de elemento por elemento [upsert]: Toda la tabla se borró antes de la recuperación y hay duplicados en el mismo lote.
     * La clave principal son datos incorrectos y la semántica de reemplazo permite sobrescribirlos en lugar de interrumpir toda la recuperación (la verdadera integridad la proporciona
     * Responsable de la verificación previa de los slots de atraque y conciliación posterior a la recuperación).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<CardEntity>)

    /** Borrar antes de restaurar la copia de seguridad (mismo calibre que otras tablas). */
    @Query("DELETE FROM card_entities")
    suspend fun deleteAll()

    /** Ranura de escritura (arrastrar). La persona que llama es responsable de proporcionar el número de ranura y la marca de tiempo. */
    @Query("UPDATE card_entities SET dockSlot = :dockSlot, dockedAt = :dockedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDockSlot(id: String, dockSlot: Int, dockedAt: Long, updatedAt: Long): Int

    /** Borrar ranura (arrastrar hacia atrás). La fila de tarjetas se conserva, sólo se cancela el acoplamiento. */
    @Query("UPDATE card_entities SET dockSlot = NULL, dockedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun clearDockSlot(id: String, updatedAt: Long): Int
}
