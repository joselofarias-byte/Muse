package io.zer0.muse.data.session

import android.content.Context
import androidx.room.withTransaction
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Phase 9.1 (M13): Almacén de carpetas.
 *
 * API de reino que proporciona carpeta CRUD + cambio de estado de expansión + movimiento de sesión a carpeta.
 * Pase del cajón de la barra lateral observeAll() Obtener lista de carpetas,cooperar SessionRepository.observeSessions()
 * Representación grupal por ID de carpeta.
 *
 * M-SESS8: Operaciones entre mesas(sessions + folders)usar [database.withTransaction] paquete;
 * [database] Inyectado por Koin(ver AppKoinModule).
 */
class FolderRepository(
    private val folderDao: FolderDao,
    private val sessionDao: SessionDao,
    private val database: MuseDb,
    private val context: Context,
) {

    private val TAG = "FolderRepo"

    /** Ver todas las carpetas(Ordenar por índice ascendente). */
    fun observeAll(): Flow<List<FolderEntity>> = folderDao.observeAll()

    /** Obtener carpeta por id(Desechable). */
    suspend fun getById(id: String): FolderEntity? = folderDao.getById(id)

    /** Crear nueva carpeta,Identificación de devolución. */
    suspend fun createFolder(name: String, sortIndex: Int = 0): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        folderDao.insert(
            FolderEntity(
                id = id,
                name = name.ifBlank { context.getString(R.string.folder_repo_default_name) },
                sortIndex = sortIndex,
                createdAt = now,
                updatedAt = now,
                expanded = true,
            ),
        )
        return id
    }

    /** Cambie el nombre de la carpeta. */
    suspend fun renameFolder(id: String, name: String) {
        // M-SESS8: átomo UPDATE,evitar leer-cambiar-Escribir actualizaciones perdidas de simultaneidad;Mantenimiento sincrónico updatedAt
        folderDao.updateName(id, name, System.currentTimeMillis())
    }

    /** Alternar estado de expansión de carpeta(colapso de la barra lateral/Expandir). */
    suspend fun setExpanded(id: String, expanded: Boolean) {
        folderDao.setExpanded(id, expanded)
    }

    /** eliminar carpeta(Borre el ID de carpeta de la sesión asociada,mudarse a"No agrupado"). */
    suspend fun deleteFolder(id: String) {
        // M-SESS8: Primero borre todas las sesiones en esta carpeta folderId,Eliminar la carpeta nuevamente,Evite referencias colgantes;
        // dos pasos a través de la mesa(sessions + folders),Utilice envoltura de transacciones para garantizar la atomicidad(Chocar sin quedar colgando folderId)
        withContext(Dispatchers.IO) {
            database.withTransaction {
                sessionDao.clearFolderId(id)
                folderDao.deleteById(id)
            }
        }
    }

    /** Mover sesión a la carpeta(folderId=null = Mover a desagrupado). */
    suspend fun moveSessionToFolder(sessionId: String, folderId: String?) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                // v1.107 Redundancia: Primero revisa los viejos folderId,para ajuste folders.sessionCount
                val session = sessionDao.getById(sessionId)
                val oldFolderId = session?.folderId
                sessionDao.setFolderId(sessionId, folderId)
                // v1.107 Redundancia: carpeta vieja sessionCount - 1
                if (oldFolderId != null) {
                    resultOf { folderDao.incrementSessionCount(oldFolderId, -1) }
                        .onError { msg, _ -> Logger.w(TAG, "decrement old folder sessionCount failed: $msg") }
                }
                // v1.107 Redundancia: nueva carpeta sessionCount + 1
                if (folderId != null) {
                    resultOf { folderDao.incrementSessionCount(folderId, 1) }
                        .onError { msg, _ -> Logger.w(TAG, "increment new folder sessionCount failed: $msg") }
                }
            }
        }
    }
}
