@file:Suppress("TooManyFunctions")

package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * v2.4.6: Entidad de carpeta de base de conocimientos — Cree "carpetas" ciudadanas de primera clase que puedan existir de forma independiente (incluidas las carpetas vacías).
 *
 * fondo:Anteriormente la carpeta era**camino virtual**(existe [KnowledgeDocEntity.metadataJson] de ruta de carpeta),
 * Una carpeta sin ningún documento no tiene ningún contenido, por lo que no se puede implementar "Nueva carpeta".
 * Esta tabla almacena un registro para cada carpeta, de modo que las carpetas vacías también se puedan mostrar en el árbol de archivos.
 *
 * visión del mundo (v2.4.6 (de):
 *  - base de conocimientos(KB)= raíz;carpeta = Subdirectorios bajo la raíz,El camino parece `contrato` / `contrato/2026`.
 *  - La carpeta también es**Recuperar unidades de cuarentena**:vinculante/Al acceder a una ruta de carpeta,El alcance de la búsqueda se limita a esta ruta.
 *    y todos los documentos bajo sus subrutas,Está separada de otras carpetas.
 *  - [path] es una ruta relativa dentro de KB,raíz(= KB mismo)Representado por una cadena vacía,No dejes de dar en el blanco.
 *
 * Relación con el documento:Los documentos aún conservan la ruta de la carpeta en su propio Json de metadatos (única fuente de verdad),Este reloj sólo es responsable de
 * "Incluso sin documentación,Esta carpeta también existe." Fusione dos fuentes al leer un árbol de carpetas.
 *
 * @param id clave primaria,Formato "kbfolder-{kbId}-{path}"(La ruta es única bajo la misma KB.)
 * @param kbId Base de conocimientos de pertenencia id
 * @param path KB ruta relativa interna(Después de la normalización,Sin barras diagonales iniciales y finales),No vacío
 * @param name Nombre para mostrar de la carpeta(final del camino)
 * @param createdAt tiempo de creación
 * @param updatedAt Hora de actualización
 */
@Serializable
@Entity(
    tableName = "knowledge_folders",
    indices = [
        Index(value = ["kb_id"]),
        Index(value = ["kb_id", "path"], unique = true),
    ],
)
data class KnowledgeFolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "kb_id") val kbId: String,
    val path: String,
    val name: String,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface KnowledgeFolderDao {
    /** Listar todas las carpetas explícitas(También se devuelven las carpetas vacías.). */
    @Query("SELECT * FROM knowledge_folders ORDER BY kb_id, path")
    fun observeAll(): Flow<List<KnowledgeFolderEntity>>

    @Query("SELECT * FROM knowledge_folders WHERE kb_id = :kbId ORDER BY path")
    fun observeByKb(kbId: String): Flow<List<KnowledgeFolderEntity>>

    @Query("SELECT * FROM knowledge_folders WHERE kb_id = :kbId ORDER BY path")
    suspend fun getByKb(kbId: String): List<KnowledgeFolderEntity>

    /** Exportación completa(Para respaldo). */
    @Query("SELECT * FROM knowledge_folders ORDER BY kb_id, path")
    suspend fun getAll(): List<KnowledgeFolderEntity>

    @Query("SELECT * FROM knowledge_folders WHERE kb_id = :kbId AND path = :path")
    suspend fun getByPath(kbId: String, path: String): KnowledgeFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: KnowledgeFolderEntity)

    /** Eliminar registros de carpeta de una ruta y todas sus subrutas(Se utiliza para \"Eliminar carpeta con subdirectorios\"). */
    @Query("DELETE FROM knowledge_folders WHERE kb_id = :kbId AND (path = :path OR path LIKE :path || '/%')")
    suspend fun deleteByPathRecursive(kbId: String, path: String)

    /** Obtener todas las carpetas en una ruta y sus subrutas(Cambiar nombre/Vuelva a calcular la ruta y en la memoria al mover el subárbol name). */
    @Query("SELECT * FROM knowledge_folders WHERE kb_id = :kbId AND (path = :path OR path LIKE :path || '/%')")
    suspend fun getByPathRecursive(kbId: String, path: String): List<KnowledgeFolderEntity>

    @Query("DELETE FROM knowledge_folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_folders WHERE kb_id = :kbId")
    suspend fun deleteByKb(kbId: String)

    @Query("DELETE FROM knowledge_folders")
    suspend fun deleteAll()
}
