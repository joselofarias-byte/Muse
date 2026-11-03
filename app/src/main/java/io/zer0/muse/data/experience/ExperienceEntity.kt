package io.zer0.muse.data.experience

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Clasificación de experiencia valor de almacenamiento estable(No cambia con el idioma de la interfaz.). */
const val DEFAULT_EXPERIENCE_CATEGORY = "Universales"

/**
 * v1.98: Entrada a la base de datos de experiencias — almacenamiento"¿Qué debe hacer si encuentra tareas similares en el futuro?"conocimiento experiencial.
 *
 * y la memoria ordinaria(fact)la diferencia:
 * - fact grabar"¿Qué tipo de persona es el usuario?"(propiedades estáticas:nombre/carrera/Preferencia)
 * - experience grabar"cómo hacer un determinado tipo de tarea"(experiencia dinámica:mejores practicas/Aprende de las trampas/Flujo de trabajo)
 *
 * Fuente:
 * 1. LLM Extracción automática(Recopilación de memoria para identificar contenido experiencial de conversaciones.)
 * 2. Usuario agregado manualmente(pagina de memoria CRUD)
 *
 * Inyectar mensaje del sistema: cuando experienceEnabled=true tiempo,Inyectar elementos de experiencia en la memoria a largo plazo,
 * Deje que la IA consulte experiencias pasadas cuando se encuentre con tareas similares.
 */
@Serializable
@Entity(tableName = "experiences")
data class ExperienceEntity(
    @PrimaryKey
    val id: String,
    /** Título(breve descripción,Como por ejemplo"Kotlin Mejores prácticas para el manejo del tiempo de espera de rutina") */
    val title: String,
    /** Detalles(Texto de experiencia,Puede contener pasos/Cosas a tener en cuenta/fragmento de código) */
    val content: String,
    /** Clasificación(Como por ejemplo"Programación"/"Uso de herramientas"/"Flujo de trabajo"/"Universales"),Fácil de filtrar */
    @ColumnInfo(defaultValue = DEFAULT_EXPERIENCE_CATEGORY)
    val category: String = DEFAULT_EXPERIENCE_CATEGORY,
    /** etiqueta(JSON cadena de matriz,Como por ejemplo ["kotlin","coroutine"]),para recuperación */
    @ColumnInfo(defaultValue = "[]")
    val tagsJson: String = "[]",
    /** Fuente: "manual"(Agregar manualmente) / "llm"(LLM Extracto) */
    @ColumnInfo(defaultValue = "manual")
    val source: String = "manual",
    /** Extraer sesión fuente ID(LLM Grabar al extraer,Fácil de rastrear) */
    val sessionId: String? = null,
    /** Crear marca de tiempo */
    val createdAt: Long = System.currentTimeMillis(),
    /** Actualizar marca de tiempo */
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ExperienceDao {
    @Query("SELECT * FROM experiences ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ExperienceEntity>>

    @Query("SELECT * FROM experiences ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ExperienceEntity>

    @Query("SELECT * FROM experiences WHERE id = :id")
    suspend fun getById(id: String): ExperienceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExperienceEntity)

    @Query("DELETE FROM experiences WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM experiences")
    suspend fun count(): Int

    @Query("DELETE FROM experiences")
    suspend fun deleteAll()
}
