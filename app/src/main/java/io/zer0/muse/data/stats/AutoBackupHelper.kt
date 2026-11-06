package io.zer0.muse.data.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.zer0.common.AppDispatchers
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MessageDao
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.107 Asistente de respaldo automático.
 *
 * F-26(Revisión de integridad funcional): Haga una copia de seguridad de las tres bases de datos principales como ZIP en el almacenamiento interno `backups/` subdirectorio,
 * Anular sesión/noticias(muse.db), resumen de memoria/compilar producto(memory.db), metahechos(facts.db),
 * Y registre cada resultado de la copia de seguridad en [auto_backup_log][AutoBackupLogEntity] mesa,Se utiliza para ver y recuperar el historial de copias de seguridad.
 *
 * Estrategia de respaldo(Impulsado por tareas periódicas de WorkManager):
 *  - Frecuencia: 1 vez por día(Solo replicar cuando realmente haya un mensaje nuevo);
 *  - Reservar: Recientemente [DEFAULT_KEEP_COUNT] porción,Limpia automáticamente los más antiguos;
 *  - camino: `getDatabasePath("muse.db").parentFile/backups/`,nombre del paquete `muse_backup_yyyyMMdd_HHmmss.zip`.
 *
 * @param autoBackupLogDao Registro de respaldo DAO
 * @param context Se utiliza para obtener la ruta de la base de datos.
 * @param messageDao Se utiliza para registrar el número total de mensajes durante la copia de seguridad.
 * @param settings para exportar/Restaurar instantánea de configuración(Los elementos de configuración van con copia de seguridad automática)
 */
class AutoBackupHelper(
    private val autoBackupLogDao: AutoBackupLogDao,
    private val context: Context,
    private val messageDao: MessageDao,
    private val settings: SettingsRepository,
) {

    companion object {
        private const val TAG = "AutoBackupHelper"

        /** Prefijo del archivo de copia de seguridad. */
        private const val BACKUP_FILE_PREFIX = "muse_backup_"

        /** Sufijo del archivo de copia de seguridad. */
        private const val BACKUP_FILE_SUFFIX = ".zip"

        /** Nombre del subdirectorio de copia de seguridad. */
        private const val BACKUP_DIR_NAME = "backups"

        /** El nombre de cada entrada de la base de datos en el paquete de respaldo. */
        const val ENTITY_MUSE_DB = "muse.db"
        const val ENTITY_MEMORY_DB = "memory.db"
        const val ENTITY_FACTS_DB = "facts.db"

        /**
         * v2.6.x: Establezca el nombre de la entrada de la instantánea.
         *
         * Las exportaciones manuales y las copias de seguridad en la nube siempre incluyen instantáneas de configuración,La copia de seguridad automática diaria anteriormente solo incluía tres bases de datos,
         * La configuración se perderá después de que el usuario restaure desde el "Registro de copia de seguridad reciente" con un solo clic. Aquí la configuración también se incorpora al paquete de copia de seguridad automática.,
         * El calibre sigue siendo el mismo que el de la exportación manual.:Contiene solo claves de lista blanca no confidenciales(No incluye credenciales como claves de proveedor.).
         * Cuando el paquete de respaldo antiguo no tiene esta entrada,Restaurar prensa lateral“Sin instantánea de configuración”Procesamiento compatible.
         */
        const val ENTITY_SETTINGS = "settings.json"

        /** El número predeterminado de copias de seguridad que se conservarán. */
        private const val DEFAULT_KEEP_COUNT = 7
    }

    /**
     * Realizar una copia de seguridad.
     *
     * pasos:
     *  1. para tres bases de datos(muse.db/memory.db/facts.db)Ejecutar por separado `VACUUM INTO`
     *     Genere instantáneas consistentes(Contiene la parte no controlada de WAL);
     *  2. Empaquetar la instantánea como `muse_backup_yyyyMMdd_HHmmss.zip` Depósito backups/;
     *  3. Registrar los resultados de la copia de seguridad(Ruta, tamaño, número total de mensajes.)a auto_backup_log.
     *
     * en [AppDispatchers.io] ejecutar en. Cuando algún paso es anormal, el registro de fallas se registra y se devuelve. false,No lanzado hacia arriba.
     *
     * @return true Indica que la copia de seguridad se realizó correctamente
     */
    suspend fun backupNow(): Boolean = withContext(AppDispatchers.io) {
        // B-7: Copia de seguridad y restauración(Reemplazo de nivel de archivo DB)La copia de seguridad automática ya no se activa dentro de la ventana,Evite hacer VACUUM en la biblioteca que se está reemplazando.
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.w(TAG, "backupNow: Restauración de copia de seguridad en curso,Saltar esta copia de seguridad automática")
            return@withContext false
        }
        Logger.i(TAG, "backupNow: Iniciar copia de seguridad")
        val now = System.currentTimeMillis()

        val backupDir = ensureBackupDir()
        val dbNames = listOf(ENTITY_MUSE_DB, ENTITY_MEMORY_DB, ENTITY_FACTS_DB)
        val missingDbs = dbNames.filter { !context.getDatabasePath(it).exists() }
        if (backupDir == null || missingDbs.size == dbNames.size) {
            val reason = if (backupDir == null) "backup dir missing" else "all dbs missing"
            Logger.w(TAG, "backupNow: $reason")
            logResult(success = false, path = "", size = 0L, now = now, error = reason)
            return@withContext false
        }

        // 1. Cada biblioteca genera instantáneas consistentes en directorios temporales.
        val staging = File(context.cacheDir, "auto_backup_staging_$now")
        staging.mkdirs()
        val snapshotFiles = mutableListOf<Pair<String, File>>()
        for (name in dbNames) {
            if (io.zer0.common.ProcessWriteGate.restoring ||
                backupBlocksRestore("Instantánea de la base de datos")
            ) {
                return@withContext false
            }
            val dbFile = context.getDatabasePath(name)
            if (!dbFile.exists()) continue
            val snap = File(staging, name)
            // Copia de seguridad solo de la versión anterior muse.db;La nueva lógica omite las bibliotecas faltantes,No se considera un fracaso
            if (vacuumInto(dbFile, snap)) {
                snapshotFiles.add(name to snap)
            } else {
                Logger.w(TAG, "backupNow: $name La instantánea falló,Omitir esta biblioteca cuenta como éxito parcial")
            }
        }

        // 1b. v2.6.x: Exportar instantánea de configuración(con exportación manual/Lista blanca no confidencial del mismo calibre que la copia de seguridad en la nube).
        // El fallo no bloquea la copia de seguridad(La misma semántica que el error en la instantánea de una sola biblioteca),Simplemente inicie sesión.
        writeSettingsSnapshot(staging)?.let { snapshotFiles.add(ENTITY_SETTINGS to it) }

        // 2. paquete
        if (io.zer0.common.ProcessWriteGate.restoring ||
            backupBlocksRestore("Paquete comprimido de respaldo")
        ) {
            return@withContext false
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val target = File(backupDir, "$BACKUP_FILE_PREFIX$timestamp$BACKUP_FILE_SUFFIX")
        val zipped = zipSnapshots(snapshotFiles, target)

        // 3. Limpiar instantáneas temporales
        staging.deleteRecursively()

        if (zipped) {
            if (io.zer0.common.ProcessWriteGate.restoring ||
                backupBlocksRestore("Resultados de la copia de seguridad")
            ) {
                return@withContext false
            }
            val messageCount = try {
                messageDao.countMessages().toLong()
            } catch (e: Exception) {
                Logger.w(TAG, "backupNow: No se pudo leer el número total de mensajes: ${e.message}", e)
                0L
            }
            logResult(
                success = true,
                path = target.absolutePath,
                size = target.length(),
                now = now,
                error = "",
                messageCount = messageCount,
            )
            Logger.i(
                TAG,
                "backupNow: Copia de seguridad exitosa -> ${target.absolutePath} (${target.length()} bytes, dbs=${snapshotFiles.size})",
            )
        } else {
            logResult(
                success = false,
                path = target.absolutePath,
                size = 0L,
                now = now,
                error = "zip failed",
            )
        }
        zipped
    }

    /**
     * Escribe una instantánea de la configuración actual como una entrada JSON dentro del paquete de respaldo.
     *
     * Contiene sólo [SettingsRepository.exportSettingsSnapshot] claves de lista blanca no confidenciales (excluidas claves de proveedores,
     * Credenciales de respaldo en la nube y más——Estos dependen de SecureKeyStore a nivel de dispositivo y no se pueden descifrar entre dispositivos).
     * Si la exportación falla, se devuelve null y la persona que llama omite la entrada.
     */
    private suspend fun writeSettingsSnapshot(staging: File): File? {
        if (io.zer0.common.ProcessWriteGate.restoring) return null
        return runCatching {
            val snapshot = settings.exportSettingsSnapshot(includeSecrets = false)
            val target = File(staging, ENTITY_SETTINGS)
            target.writeText(
                AppJson.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    snapshot,
                ),
            )
            target
        }.getOrElse {
            Logger.w(TAG, "backupNow: Error al exportar la instantánea de configuración,saltar: ${it.message}")
            null
        }
    }

    /**
     * Empaquete varias instantáneas de bases de datos en un zip.
     */
    private fun zipSnapshots(snapshotFiles: List<Pair<String, File>>, target: File): Boolean {
        if (snapshotFiles.isEmpty()) return false
        return runCatching {
            FileOutputStream(target).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for ((name, file) in snapshotFiles) {
                        zos.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        }.getOrElse {
            Logger.e(TAG, "zipSnapshots: Error de embalaje: ${it.message}", it)
            target.delete()
            false
        }
    }

    private fun backupBlocksRestore(operation: String): Boolean {
        if (!io.zer0.common.ProcessWriteGate.restoring) return false
        Logger.w(TAG, "Copia de seguridad y restauración en curso, copia de seguridad automática omitida${operation}escribir")
        return true
    }

    /**
     * Limpiar registros y archivos de copia de seguridad antiguos,Mantenga solo el más reciente [keepCount] parte.
     *
     * Los archivos se mantienen en orden descendente según la marca de tiempo del nombre del archivo.(Compatible con versiones anteriores de copia de seguridad de un solo archivo .db);Llamada de sincronización de tabla de registro [AutoBackupLogDao.trim].
     * en [AppDispatchers.io] ejecutar en.
     *
     * @param keepCount Guarde copias,Predeterminado [DEFAULT_KEEP_COUNT]
     */
    suspend fun trimOldBackups(keepCount: Int = DEFAULT_KEEP_COUNT) = withContext(AppDispatchers.io) {
        val backupDir = ensureBackupDir()
        if (backupDir == null) {
            Logger.w(TAG, "trimOldBackups: El directorio de respaldo no está disponible")
            return@withContext
        }

        // L-14: Priorizar la clasificación por marca de tiempo de resolución de nombre de archivo(El nombre del archivo se fija en muse_backup_yyyyMMdd_HHmmss.{zip,db}),
        // lastModified Sólo como una cubierta,Evite errores de secuencia de eliminación cuando el reloj del sistema sea anormal
        val files = backupDir.listFiles { f ->
            f.isFile && f.name.startsWith(BACKUP_FILE_PREFIX)
        }?.sortedByDescending { f ->
            // Intente analizar la marca de tiempo del nombre del archivo como clave de clasificación
            val ts = f.name.substringAfterLast('_').substringBefore('.').toLongOrNull()
            ts ?: f.lastModified()
        } ?: emptyList()

        val toDelete = if (files.size > keepCount) files.drop(keepCount) else emptyList()
        var deleted = 0
        for (f in toDelete) {
            try {
                if (f.delete()) {
                    deleted++
                } else {
                    Logger.w(TAG, "trimOldBackups: Error al eliminar ${f.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "trimOldBackups: Eliminar excepción ${f.absolutePath}", e)
            }
        }

        try {
            autoBackupLogDao.trim(keepCount)
        } catch (e: Exception) {
            Logger.w(TAG, "trimOldBackups: No se pudo borrar la tabla de registro: ${e.message}", e)
        }

        Logger.i(TAG, "trimOldBackups: Reservar $keepCount porción,Eliminar $deleted archivos de copia de seguridad antiguos")
    }

    /**
     * Obtener(Crear si es necesario)Directorio de respaldo;Devuelve nulo si no se puede obtener ni crear.
     */
    private fun ensureBackupDir(): File? {
        val dbFile = context.getDatabasePath("muse.db")
        val parent = dbFile.parentFile ?: return null
        val backupDir = File(parent, BACKUP_DIR_NAME)
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            Logger.w(TAG, "ensureBackupDir: No se pudo crear el directorio de respaldo ${backupDir.absolutePath}")
            return null
        }
        return backupDir
    }

    /**
     * Reparación de auditoría (0.4): Utilice VACUUM INTO para generar instantáneas de respaldo consistentes.
     *
     * VACUUM INTO Se generará un archivo que contiene todos los datos enviados de la biblioteca actual.(Contiene la parte no controlada de WAL)de
     * Nuevo archivo de base de datos,y no bloquea las conexiones de escritura;sustituto"PASSIVE checkpoint + Copiar la biblioteca principal"viejo esquema
     * (Este último descarta fotogramas durante transacciones de escritura activas y nunca se replica. -wal/-shm,La copia de seguridad es un estado antiguo).
     *
     * @return true Indica que la copia de seguridad se realizó correctamente
     */
    private fun vacuumInto(dbFile: File, target: File): Boolean {
        var conn: SQLiteDatabase? = null
        try {
            conn = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            // VACUUM INTO Es necesario evitar las comillas simples en la ruta.
            val escaped = target.absolutePath.replace("'", "''")
            conn.execSQL("VACUUM INTO '$escaped'")
            Logger.d(TAG, "vacuumInto: Se ha generado una instantánea de coherencia. ${target.absolutePath} (${target.length()} bytes)")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "vacuumInto: No se pudo generar la instantánea: ${e.message}", e)
            // VACUUM INTO falló(equipo viejo/Permisos)revertir:Forzar el punto de control primero y luego copiar la biblioteca principal
            return runCatching {
                conn?.rawQuery("PRAGMA wal_checkpoint(FULL)", null)?.use { it.moveToFirst() }
                conn?.close()
                conn = null
                dbFile.copyTo(target, overwrite = true)
                true
            }.getOrElse {
                Logger.e(TAG, "vacuumInto: La replicación de reversión también falló: ${it.message}", it)
                false
            }
        } finally {
            try {
                conn?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "vacuumInto: No se pudo cerrar la conexión: ${e.message}", e)
            }
        }
    }

    /**
     * Escriba un registro de resultados de copia de seguridad. Solo se registran errores cuando falla la escritura del registro.,No afecta el proceso principal.
     *
     * @param success ¿Tiene éxito?
     * @param path Ruta absoluta del archivo de copia de seguridad
     * @param size Tamaño del archivo de respaldo(bytes)
     * @param now Marca de tiempo
     * @param error Mensaje de error en caso de falla(Pase una cadena vacía en caso de éxito)
     * @param messageCount Número total de mensajes durante la copia de seguridad
     */
    private suspend fun logResult(success: Boolean, path: String, size: Long, now: Long, error: String, messageCount: Long = 0L) {
        try {
            autoBackupLogDao.insert(
                AutoBackupLogEntity(
                    backupPath = path,
                    fileSizeBytes = size,
                    status = if (success) "success" else "failed",
                    errorMessage = error,
                    messageCount = messageCount,
                    createdAt = now,
                ),
            )
        } catch (e: Exception) {
            Logger.e(TAG, "logResult: No se pudo escribir el registro de respaldo", e)
        }
    }
}
