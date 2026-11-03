package io.zer0.muse.data.session

import android.content.Context

/**
 * P2-1 Derribar la fachada del almacén.
 *
 * Actualmente, solo está alojado MuseDb (mantenga una única biblioteca en funcionamiento, cero cambios de comportamiento); será arrancado más tarde
 * KnowledgeDb / ScheduleDb / AgentDb / StatsDb En ese momento, se podrán obtener a través de esta fachada.
 * Evite las dependencias directas del código comercial“Singleton global de biblioteca única”.
 */
object MuseDatabases {

    @Volatile
    private var museDb: MuseDb? = null

    /** Biblioteca de diálogo principal (actualmente la única instancia). */
    fun muse(context: Context): MuseDb {
        return museDb ?: synchronized(this) {
            museDb ?: MuseDb.get(context).also { museDb = it }
        }
    }

    /** Limpieza final del proceso (prueba/herramientas). */
    fun resetForTests() {
        synchronized(this) {
            museDb = null
        }
    }
}
