package io.zer0.muse.data.skill

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Phase 8.8: Skill entidad(Kotlin Realización directa,No hay necesidad QuickJS).
 *
 * Skill es una función de utilidad de Kotlin predefinida,Encapsular operaciones comunes(Leer y escribir archivos/solicitudes HTTP, etc.).
 * Cada habilidad está expuesta al exterior como una herramienta invocable de LLM.(Parámetro JSON Schema + función de ejecución).
 *
 * Diferencias con planes similares.:Soluciones similares utilizan QuickJS para ejecutar scripts definidos por el usuario(S2 Ya skip),
 * Este proyecto se cambia a una plantilla de habilidades de Kotlin predefinida + ejecución parametrizada,Mantener puntos de extensión(Se pueden agregar más más tarde skill).
 *
 * @param id clave primaria(Usualmente usado slug,Como por ejemplo "read_file")
 * @param name nombre para mostrar
 * @param description Descripción de la herramienta(LLM Decide si llamar basándose en esto)
 * @param parametersJson parámetros JSON Schema(OpenAI Formatos compatibles)
 * @param requiredJson Matriz de nombres de parámetros requeridos(JSON cuerda)
 * @param implementationKotlin darse cuenta key(para enrutamiento SkillExecutor,Como por ejemplo "read_file")
 * @param enabled Ya sea para habilitar
 * @param category Clasificación:file / http / system / custom
 * @param createdAt Crear marca de tiempo
 * @param updatedAt Actualizar marca de tiempo
 */
@Serializable
@Entity(
    tableName = "skills",
    // M-SD1: Índice habilitado/categoría,promover SkillRepository.listEnabled
    // y rendimiento de consultas filtrado por categoría. El agente maestro maneja de manera uniforme la indexación y la migración en MuseDb.
    indices = [Index(value = ["enabled"]), Index(value = ["category"])],
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(defaultValue = "{}") val parametersJson: String = "{}",
    @ColumnInfo(defaultValue = "[]") val requiredJson: String = "[]",
    // L-SET1: Usar valor predeterminado "unknown" Marcador de posición,Facilita la identificación de casos donde la implementación no está configurada al enrutar SkillExecutor
    @ColumnInfo(name = "implementationKotlin", defaultValue = "") val implementationKotlin: String = "unknown",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "custom") val category: String = "custom",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
