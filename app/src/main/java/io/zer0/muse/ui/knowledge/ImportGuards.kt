package io.zer0.muse.ui.knowledge

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// v2.3.1: Protección previa para la importación de un solo archivo —— Página principal de la base de conocimientos(KnowledgeScreen)con página de gestión de base de conocimientos
// (KnowledgeBaseManagePage)compartir el mismo juicio,Evite la deriva de dos reglas de ruta de importación.
//
// fondo(2026-09 retroalimentación):Anteriormente, los guardias solo se agregaban a la página de administración.,La página principal todavía lee binario como texto. →
// Escriba caracteres confusos en la biblioteca y cree índices vectoriales,Lo que ve el usuario es "Importación exitosa". Regla de nombre puro(Extensión del paquete comprimido)
// ver KnowledgeArchivePolicy.isArchiveFile;Este archivo solo contiene sniffs que requieren ContentResolver.

/**
 * Olfatear encabezados de archivos para bytes NUL(características binarias),Prevenir paquetes comprimidos/Los archivos ejecutables se tratan como índices de texto.
 * Cuando la lectura falla, se tratará como "no binaria".,No bloquea la importación normal.
 */
internal suspend fun looksBinary(uri: Uri, context: Context): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(512)
            val n = input.read(head)
            n > 0 && (0 until n).any { head[it] == 0.toByte() }
        } ?: false
    }.getOrDefault(false)
}
