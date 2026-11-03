package io.zer0.memory.fact

import androidx.room.Entity
import androidx.room.Fts4

/**
 * Facts FTS4 Tabla virtual de índice de texto completo.
 *
 * Puntos de diseño:
 * - Usando FTS4: soporte estable para anotaciones @Fts4 en la Sala 2.8,SQLite compatible con Android 7+.
 * - No depende del tokenizador incorporado: segmentación de palabras chinas por [FactFtsManager.toNgram] preprocesamiento,
 *   El índice almacena resultados de ventana deslizante de 2 gramos.,FTS4 Dividir por espacio en blanco para que coincida.
 * - Mantener el índice de forma independiente: escribir/Eliminar/actualizado por [FactStore] gatillo,Sincronizar con la tabla de hechos.
 * - rowid Gestionado automáticamente por FTS4,entity Ningún campo de clave principal explícito.
 */
@Entity(tableName = "facts_fts")
@Fts4
class FactFtsEntity(
    /** hechos asociados.id. Eliminar/Apunte a este campo al actualizar. */
    val fact_id: Long,
    /** contenido fáctico ngramificado(2-gram Ventana deslizante + palabras en minúsculas ASCII conservadas). */
    val content_ngram: String,
)
