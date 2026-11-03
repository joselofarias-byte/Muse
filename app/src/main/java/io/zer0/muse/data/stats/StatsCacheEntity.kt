package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.107 Tabla de caché de agregación de estadísticas.
 *
 * Almacene en caché los resultados de 6 agregaciones GROUP BY de tabla completa en MessageDao,Evite el escaneo completo de la tabla cada vez que ingrese a la página de estadísticas.
 *  - key: Identificador de dimensión de agregación(Como por ejemplo "countByRole" / "countByModel" / "countByAssistant" /
 *    "countByHour" / "topSessions" / "totalMessages" / "totalSessions" espera)
 *  - value: JSON Resultados agregados serializados
 *  - updatedAt: Última hora de actualización,Se utiliza para determinar si el caché ha caducado
 *
 * estrategia de actualización:
 *  1. envío de mensajes/Actualizar de forma asincrónica después de la eliminación(Retrasa 5 segundos para fusionar múltiples escrituras)
 *  2. WorkManager Actualización completa todos los días
 *  3. Cuando se abre la página de estadísticas, si el caché excede 1 hora, se actualizará en segundo plano.,Mostrar caché antiguo primero
 */
@Entity(tableName = "stats_cache")
data class StatsCacheEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(defaultValue = "{}") val value: String = "{}",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)
