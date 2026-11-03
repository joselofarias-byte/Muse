package io.zer0.muse.data.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.ShareIntentHelper
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * Importación de tarjeta de personaje/Exportar/Compartir.
 *
 * poner [AssistantEntity](y foto de avatar opcional)empaquetado en zip(.muse-assistant Archivo),
 * Conveniente en todos los dispositivos/Comparta configuraciones de roles entre instalaciones. Sólo uso java.util.zip,No se introducen nuevas dependencias.
 *
 * zip estructura:
 *  - assistant.json      serializado AssistantEntity(Borrar la identificación al exportar,Regenerar al importar)
 *  - avatar.<ext>        Opcional,Bytes de imagen de avatar(Inferir la extensión de la ruta del avatar)
 *
 * v2.x: Agregar uso compartido con un solo clic([share],El panel de uso compartido del sistema envía directamente el paquete de personajes)con vista previa de importación
 * ([parse] analizar → Confirmación de usuario → [commitParsed] escribir).
 */
object AssistantCardExporter {

    private const val TAG = "AssistantCardExporter"
    private const val ENTRY_JSON = "assistant.json"
    private const val ENTRY_AVATAR_PREFIX = "avatar."

    /** v2.x: paquete de personajes MIME — Alineado con el filtro de intención VIEW de AndroidManifest(Compartir/Recibir identificador único). */
    const val MIME_TYPE = "application/x-muse-assistant"

    /** v2.x: Extensión de archivo del paquete de caracteres. */
    const val FILE_EXTENSION = "muse-assistant"

    // M-EXP1: Lista blanca de extensiones de avatar(Sólo se permiten formatos de imagen comunes),Evite que tarjetas maliciosas escriban extensiones ejecutables como .exe
    private val ALLOWED_AVATAR_EXTS = setOf("jpg", "jpeg", "png", "webp")

    // v1.113: Limitar la entrada única al máximo 10MB,Bomba anti-ZIP
    private const val MAX_ENTRY_BYTES = 10L * 1024 * 1024

    /** M-EXP1: Verifique que la extensión esté en la lista blanca,Reserva si no está presente "jpg". */
    private fun sanitizeAvatarExt(raw: String): String {
        val lower = raw.lowercase().trim()
        return if (lower in ALLOWED_AVATAR_EXTS) lower else "jpg"
    }

    /**
     * v2.x: Contenido de la tarjeta de personaje analizado(Aún no escrito en el disco/base de datos).
     *
     * Para uso de confirmación de vista previa de importación:Después de que el usuario confirme el [commitParsed] Guarde la bandeja.
     */
    class ParsedCard(
        val entity: AssistantEntity,
        val avatarBytes: ByteArray?,
        val avatarExt: String?,
    )

    // ── paquete(Exporta y comparte con un clic) ─────────────────────────────────────────

    /** poner [assistant] Empaquetado como paquete de caracteres de escritura zip [os](Exportar una copia:id Borrar;Paquete de bytes de avatar). */
    private fun writeZip(os: OutputStream, assistant: AssistantEntity) {
        // Exportar una copia:Borrar id
        val exportEntity = assistant.copy(id = "")
        val json = AppJson.encodeToString(AssistantEntity.serializer(), exportEntity)

        // byte de avatar(Opcional):Empaquetar sólo si avatarImageUrl apunta a un archivo local existente
        var avatarExt: String? = null
        var avatarBytes: ByteArray? = null
        if (assistant.avatarImageUrl.isNotBlank()) {
            val avatarFile = File(assistant.avatarImageUrl)
            if (avatarFile.exists()) {
                // M-EXP1: Extensión incluida en la lista blanca,Evite que los datos locales sean manipulados y escriban extensiones sospechosas
                avatarExt = sanitizeAvatarExt(avatarFile.extension.ifBlank { "jpg" })
                avatarBytes = avatarFile.readBytes()
            }
        }

        ZipOutputStream(os).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_JSON))
            zos.write(json.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            val bytes = avatarBytes
            val ext = avatarExt
            if (bytes != null && ext != null) {
                zos.putNextEntry(ZipEntry("$ENTRY_AVATAR_PREFIX$ext"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    /** v2.x: Seguridad del nombre del archivo del paquete de caracteres(Eliminar separación de caminos/personajes reservados/en blanco). */
    private fun safePackName(name: String): String = name.ifBlank { "assistant" }
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .take(60)

    /**
     * Exportar tarjeta de personaje a [outputUri](SAF guardar).
     *
     * - id Claro para ""(Regenerar al importar),Deje los campos restantes como están(tarjeta local,Los campos sensibles no se borran)
     * - si [AssistantEntity.avatarImageUrl] Apuntar a un archivo local existente,Empaque los bytes del avatar juntos
     */
    suspend fun export(context: Context, assistant: AssistantEntity, outputUri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(outputUri)?.use { os ->
            writeZip(os, assistant)
        } ?: Logger.w(TAG, "openOutputStream failed for $outputUri")
    }

    /**
     * v2.x: Comparte con un clic — Empaque el paquete de caracteres en cacheDir y envíelo a través del panel para compartir del sistema(ACTION_SEND).
     *
     * Receptor(Dispositivos con Muse instalado)Haga clic en el archivo para ingresar directamente a la importación por MIME;Otros canales se entregan como archivos normales.
     *
     * @return Si el intercambio se inició exitosamente(paquete/Regresar si no se detuvo false)
     */
    suspend fun share(context: Context, assistant: AssistantEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "assistant_share").apply { mkdirs() }
            val file = File(dir, "${safePackName(assistant.name)}.$FILE_EXTENSION")
            file.outputStream().use { os -> writeZip(os, assistant) }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                ShareIntentHelper.startChooserSafely(context, intent, "Share Character Pack")
            }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "share failed: ${e.message}")
            false
        }
    }

    // ── analizar/enviar(Confirmación de vista previa de importación) ───────────────────────────────────────

    /**
     * v2.x: Analiza la tarjeta de personaje pero no escribas. — Para confirmación de vista previa de importación.
     *
     * Lea los bytes de asistente.json y avatar en zip;Sin escritura en disco, sin almacenamiento.
     *
     * @return Analizar resultados;El formato no es válido o el análisis falla. null
     */
    suspend fun parse(context: Context, inputUri: Uri): ParsedCard? = withContext(Dispatchers.IO) {
        var jsonStr: String? = null
        var avatarExt: String? = null
        var avatarBytes: ByteArray? = null

        context.contentResolver.openInputStream(inputUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_JSON -> {
                            jsonStr = readZipEntryWithLimit(zis, MAX_ENTRY_BYTES, name).toString(Charsets.UTF_8)
                        }
                        name.startsWith(ENTRY_AVATAR_PREFIX) -> {
                            // M-EXP1: Las tarjetas maliciosas pueden contener entradas como avatar.exe,Las extensiones se incluyen en la lista blanca antes de escribirse en el disco.
                            avatarExt = sanitizeAvatarExt(name.removePrefix(ENTRY_AVATAR_PREFIX))
                            avatarBytes = readZipEntryWithLimit(zis, MAX_ENTRY_BYTES, name)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return@withContext null

        val json = jsonStr ?: run {
            Logger.w(TAG, "no assistant.json in $inputUri")
            return@withContext null
        }

        val parsed = runCatching {
            AppJson.decodeFromString(AssistantEntity.serializer(), json)
        }.getOrElse {
            Logger.w(TAG, "parse assistant.json failed: ${it.message}")
            return@withContext null
        }

        ParsedCard(entity = parsed, avatarBytes = avatarBytes, avatarExt = avatarExt)
    }

    /**
     * v2.x: Escriba tarjetas de personajes analizadas en el disco y la base de datos.(Llamado después de la confirmación de la vista previa).
     *
     * - regenerar id([Uuid.random]),Deje que createAt/updatedAt sea la hora actual
     * - Bytes de avatar escritos nuevamente en filesDir/avatars/ y actualizados avatarImageUrl
     * - usar [repo].upsert Guardar en base de datos
     */
    suspend fun commitParsed(context: Context, repo: AssistantRepository, card: ParsedCard): AssistantEntity = withContext(Dispatchers.IO) {
        val newId = Uuid.random().toString()
        val now = System.currentTimeMillis()

        // El avatar se vuelve a escribir en el almacenamiento interno.(Si es así)
        val newAvatarUrl = run {
            val bytes = card.avatarBytes ?: return@run null
            val ext = card.avatarExt?.takeIf { it.isNotBlank() } ?: "jpg"
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val target = File(dir, "assistant_${newId}_$now.$ext")
            target.writeBytes(bytes)
            target.absolutePath
        }

        val entity = card.entity.copy(
            id = newId,
            createdAt = now,
            updatedAt = now,
            avatarImageUrl = newAvatarUrl ?: card.entity.avatarImageUrl,
        )
        repo.upsert(entity)
        entity
    }

    /**
     * de [inputUri] Importar cartas de personajes(analizar + confirmar,un paso).
     *
     * Reservado para personas que llaman sin confirmación previa;Utilice la importación con vista previa [parse] + [commitParsed].
     *
     * @return Después de importar [AssistantEntity];Si el formato del archivo no es válido o el análisis falla, devuelva null
     */
    suspend fun import(context: Context, repo: AssistantRepository, inputUri: Uri): AssistantEntity? {
        val card = parse(context, inputUri) ?: return null
        return commitParsed(context, repo, card)
    }
}
