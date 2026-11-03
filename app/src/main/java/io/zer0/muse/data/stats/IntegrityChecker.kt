package io.zer0.muse.data.stats

import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.107 Comprobador de integridad de la base de datos.
 *
 * Al iniciar(o disparar manualmente)Ejecutando SQLite `PRAGMA integrity_check`,Comparar resultados con el tamaño del archivo de la base de datos.
 * grabado en [db_integrity_log][DbIntegrityLogEntity] mesa,Se utiliza para realizar un seguimiento del estado de la base de datos y de las tendencias de crecimiento del volumen.
 * Si la verificación falla,UI La capa puede solicitar al usuario que restaure desde una copia de seguridad automática.
 *
 * @param integrityLogDao registro de integridad DAO
 * @param db Room expuesto [SupportSQLiteDatabase],por `MuseDb.openHelper.writableDatabase` Obtener,
 *           Se utiliza para ejecutar consultas PRAGMA y leer rutas de archivos de bases de datos.
 */
class IntegrityChecker(
    private val integrityLogDao: DbIntegrityLogDao,
    private val db: SupportSQLiteDatabase,
) {

    companion object {
        private const val TAG = "IntegrityChecker"

        /** El número de entradas del registro histórico retenidas. */
        private const val KEEP_LOG_COUNT = 30
    }

    /**
     * Ejecutar una vez `PRAGMA integrity_check` Y grábalo en la biblioteca.
     *
     * pasos:
     *  1. Leer el tamaño del archivo maestro de la base de datos(Se utiliza para rastrear las tendencias de crecimiento.);
     *  2. Pase [SupportSQLiteDatabase.query] ejecutar `PRAGMA integrity_check`,Leer todas las líneas de resultados;
     *  3. estado de voluntad("ok"/"error"), detalles, tamaño del archivo, escritura de marca de tiempo db_integrity_log;
     *  4. Por cierto, limpia los registros viejos.,Mantenga solo el más reciente [KEEP_LOG_COUNT] artículo.
     *
     * en [AppDispatchers.io] ejecutar en. Las excepciones en cualquier paso se detectan y registran.,No lanzado hacia arriba.
     *
     * @return true Indica que la verificación pasó(El resultado es "ok");false Indica una excepción o error de consulta
     */
    suspend fun checkAndLog(): Boolean = withContext(AppDispatchers.io) {
        val dbSize = readDbSizeBytes()

        val details = try {
            db.query("PRAGMA integrity_check").use { cursor ->
                val sb = StringBuilder()
                while (cursor.moveToNext()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(cursor.getString(0) ?: "")
                }
                if (sb.isEmpty()) "empty" else sb.toString()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "checkAndLog: No se pudo ejecutar integridad_check", e)
            "query_failed: ${e.message}"
        }

        val isOk = details.equals("ok", ignoreCase = true)
        val status = if (isOk) "ok" else "error"

        try {
            integrityLogDao.insert(
                DbIntegrityLogEntity(
                    status = status,
                    details = details,
                    dbSizeBytes = dbSize,
                    checkedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            Logger.e(TAG, "checkAndLog: No se pudo escribir el registro de integridad", e)
        }

        try {
            integrityLogDao.trim(KEEP_LOG_COUNT)
        } catch (e: Exception) {
            Logger.w(TAG, "checkAndLog: Error al limpiar el antiguo registro de integridad: ${e.message}", e)
        }

        if (isOk) {
            Logger.i(TAG, "checkAndLog: integrity_check ok, dbSize=$dbSize")
        } else {
            Logger.w(TAG, "checkAndLog: integrity_check anormal: $details")
        }
        isOk
    }

    /**
     * Obtenga el último registro de verificación de integridad.
     *
     * en [AppDispatchers.io] ejecutar en.
     *
     * @return último registro;Regresa cuando la tabla está vacía null
     */
    suspend fun getLatestStatus(): DbIntegrityLogEntity? = withContext(AppDispatchers.io) {
        integrityLogDao.getLatest()
    }

    /**
     * Leer el tamaño del archivo maestro de la base de datos,Devuelve 0 en caso de error o si la ruta está vacía.
     */
    private fun readDbSizeBytes(): Long {
        val path: String? = try {
            db.path
        } catch (e: Exception) {
            Logger.w(TAG, "readDbSizeBytes: No se pudo obtener la ruta de la base de datos: ${e.message}", e)
            null
        }
        if (path.isNullOrEmpty()) return 0L
        return try {
            File(path).length()
        } catch (e: Exception) {
            Logger.w(TAG, "readDbSizeBytes: No se pudo leer el tamaño del archivo: ${e.message}", e)
            0L
        }
    }
}
