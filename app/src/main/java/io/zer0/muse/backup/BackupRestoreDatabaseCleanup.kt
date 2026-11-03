package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Estado de sesión que será reemplazado por completo en una restauración limpia.
 *
 * La persona que llama debe ejecutar dentro de una transacción de MuseDb;checkpoint La limpieza explícita no depende de la configuración en cascada de claves externas.
 */
internal suspend fun clearConversationStateForRestore(db: MuseDb) {
    db.generationCheckpointDao().deleteAll()
    db.messageDao().deleteAll()
    db.sessionDao().deleteAll()
}

internal suspend fun <T> restoreAndPruneMessageImages(
    imageStore: MessageImageStore,
    restore: suspend () -> T,
    readReferencedPaths: suspend () -> Set<String>?,
): T {
    val snapshot = imageStore.snapshotStoredFilePaths()
    val result = restore()
    pruneMessageImageSnapshot(imageStore, snapshot, readReferencedPaths)
    return result
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun pruneMessageImageSnapshot(
    imageStore: MessageImageStore,
    snapshot: Set<String>,
    readReferencedPaths: suspend () -> Set<String>?,
) {
    if (snapshot.isEmpty()) return
    try {
        withContext(NonCancellable) {
            val referenced = readReferencedPaths() ?: return@withContext
            imageStore.deleteSnapshotFilesNotReferenced(snapshot, referenced)
        }
    } catch (error: Exception) {
        Logger.w("BackupRestore", "La limpieza de la imagen no se completa después de la recuperación: ${error.message}", error)
    }
}
