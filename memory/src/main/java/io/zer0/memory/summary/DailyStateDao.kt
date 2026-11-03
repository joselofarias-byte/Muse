package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyStateEntity)

    /**
     * v2.6.2(auditoría de memoria): Presione la tecla de destino para leer el progreso.
     *
     * Lectura fija de implementación anterior `key='default'` sola linea —— Asistente principal/subasistente/recopilación diaria de diferentes espacios
     * Al compartir la misma fila, después de cambiar de espacio, los pasos completados se omitirán ese día o se reutilizarán los pasos completados de otro objetivo.
     * Hace que los datos diarios/semanales/a largo plazo/del nuevo espacio nunca se compilen (pérdida de memoria aleatoria).
     */
    @Query("SELECT * FROM daily_state WHERE `key` = :key")
    suspend fun get(key: String): DailyStateEntity?

    /** v2.6.2: Lea el progreso de todos los objetivos (para copia de seguridad y exportación). */
    @Query("SELECT * FROM daily_state")
    suspend fun getAll(): List<DailyStateEntity>

    @Query("DELETE FROM daily_state")
    suspend fun deleteAll()
}
