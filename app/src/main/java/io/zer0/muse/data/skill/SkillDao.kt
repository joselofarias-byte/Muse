package io.zer0.muse.data.skill

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 8.8: Skill DAO.
 */
@Dao
interface SkillDao {
    // L-SD2: observeAll agregar LIMIT 200,Evite cargar toda la tabla de habilidades en la memoria a la vez cuando sea demasiado grande
    @Query("SELECT * FROM skills ORDER BY category ASC, name ASC LIMIT 200")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY category ASC, name ASC")
    suspend fun listEnabled(): List<SkillEntity>

    // M-SR1: Consulta habilitada por lista de id skill,Evite cargar la cantidad completa en la memoria filter
    @Query("SELECT * FROM skills WHERE enabled = 1 AND id IN (:ids) ORDER BY category ASC, name ASC")
    suspend fun listEnabledByIds(ids: List<String>): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: String): SkillEntity?

    // M-SE12/L-SD3: Utilice OnConflictStrategy.REPLACE.
    // evaluación de riesgos:La tabla de habilidades actual no tiene referencia de clave externa(Otras entidades no están declaradas FOREIGN KEY → skills(id)),
    // Por lo tanto, REPLACE no activa eliminaciones en cascada. Si se agrega una clave foránea más adelante que se refiera a habilidades,Necesito usar @Upsert en su lugar.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkillEntity)

    /**
     * P0-11: Activación de habilidades incorporada seed — Insertar solo si la clave principal no existe.
     *
     * con [upsert] la diferencia:Se conserva toda la fila de claves primarias existentes.(Contiene configuraciones del manual de usuario. enabled=false),
     * Solo instalaciones nuevas/Las habilidades integradas que faltan solo se escriben después de la actualización.Room La semántica de @Upsert es
     * "INSERT ... ON CONFLICT DO UPDATE",La selección de usuario aún se anulará con las semillas habilitadas,
     * Por eso se usa aquí. IGNORE(Saltar el conflicto)satisfacer"Inicializar solo si falta el campo".
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedIfAbsent(entity: SkillEntity)

    /**
     * v2.x: Actualizar los campos de definición de habilidades integradas,Conservar el estado del usuario, como habilitado.
     *
     * fondo: seedIfAbsent(IGNORE) Nunca actualizar filas existentes → Las habilidades integradas de Laoku schema
     * atascado en la primera versión de instalación(Si no puede ver los parámetros agregados más adelante para las habilidades integradas en el lado del modelo,).
     * Los usuarios no pueden editar ni definir las habilidades integradas,Por lo tanto, no se pierden datos de usuario al sobrescribir campos definidos.
     */
    @Query(
        "UPDATE skills SET name = :name, description = :description, parametersJson = :parametersJson, " +
            "requiredJson = :requiredJson, category = :category, implementationKotlin = :implementationKotlin " +
            "WHERE id = :id",
    )
    suspend fun refreshBuiltInDefinition(
        id: String,
        name: String,
        description: String,
        parametersJson: String,
        requiredJson: String,
        category: String,
        implementationKotlin: String,
    )

    @Update
    suspend fun update(entity: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE skills SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("SELECT * FROM skills")
    suspend fun getAll(): List<SkillEntity>

    @Query("DELETE FROM skills")
    suspend fun deleteAll()
}
