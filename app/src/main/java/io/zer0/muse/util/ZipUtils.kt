package io.zer0.muse.util

import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * v1.131: Herramienta de lectura limitada unificada ZipInputStream.
 *
 * Originalmente [readEntryWithLimit] / [readLimitedBytes] Patrones repartidos en 4 archivos.:
 *  - data/import/ThirdPartyImporter.kt
 *  - data/assistant/AssistantCardExporter.kt
 *  - data/sticker/StickerLibraryRepository.kt
 *  - doc/DocumentParser.kt (especial:Buscar por nombre de entrada,Mantener una implementación independiente)
 *
 * Bomba anti-ZIP:Número acumulado de bytes durante la lectura.,exceder [maxBytes] tirar [IOException] Abortar la lectura.
 *
 * Nota:[ZipInputStream] de [ZipInputStream.read] Regreso cuando se llegue al final de la entrada -1,
 * en lugar de lanzar una excepción,Por lo tanto, la condición de salida del bucle es `read <= 0`(contiene -1).
 *
 * @param zis ha sido llamado [ZipInputStream.getNextEntry] y localizar la entrada de destino ZipInputStream
 * @param maxBytes Número máximo de bytes descomprimidos para una sola entrada,más allá de tirar [IOException]
 * @param entryName Solo para visualización de mensajes de error,puede ser null
 * @return La matriz de bytes completa de la entrada actual.
 * @throws IOException El tamaño descomprimido excede [maxBytes]
 */
fun readZipEntryWithLimit(zis: ZipInputStream, maxBytes: Long, entryName: String? = null): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = zis.read(buf)
        if (read <= 0) break
        total += read
        if (total > maxBytes) {
            val label = entryName?.let { "entrada '$it' " } ?: "entrada "
            throw IOException(
                "${label}El tamaño de descompresión supera el límite(${maxBytes / 1024 / 1024}MB),Posiblemente un archivo malicioso o una bomba ZIP",
            )
        }
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}

/**
 * Transmite la entrada ZIP actual al flujo de salida de destino, limitando el número máximo de bytes después de la descompresión.
 *
 * con [readZipEntryWithLimit] La diferencia es que el ByteArray completo no se construirá en la memoria, lo que es adecuado para importar archivos grandes, como la exportación de conversaciones.
 * La persona que llama es responsable de eliminar los archivos de destino incompletos cuando se produce una excepción.
 *
 * @return Número real de bytes copiados
 */
fun copyZipEntryWithLimit(zis: ZipInputStream, output: OutputStream, maxBytes: Long, entryName: String? = null): Long {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = zis.read(buf)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            val label = entryName?.let { "entrada '$it' " } ?: "entrada "
            throw IOException(
                "${label}El tamaño de descompresión supera el límite(${maxBytes / 1024 / 1024}MB),Posiblemente un archivo malicioso o una bomba ZIP",
            )
        }
        output.write(buf, 0, read)
    }
    return total
}
