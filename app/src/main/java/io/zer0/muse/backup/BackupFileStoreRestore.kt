package io.zer0.muse.backup

internal fun readBackupFileStores(paths: Iterable<String>, read: (name: String) -> String?): Map<String, String> {
    val stores = linkedMapOf<String, String>()
    paths.forEach { name ->
        read(name)?.let { stores[name] = it }
    }
    return stores
}

/**
 * Límites a prueba de fallas para restaurar el almacenamiento de respaldo basado en archivos.
 *
 * El almacenamiento de archivos comparte el mismo libro de recuperación de almacenamiento cruzado con tres bibliotecas de Room; falla la escritura en cualquier archivo incluido en la lista blanca
 * Todo debe propagarse hacia arriba para activar [BackupService] El punto de recuperación se revierte.
 */
internal suspend fun restoreBackupFileStores(
    stores: Map<String, String>,
    allowedPaths: Set<String>,
    write: suspend (name: String, content: String) -> Unit,
) {
    stores.forEach { (name, content) ->
        if (name in allowedPaths) {
            write(name, content)
        }
    }
}
