package io.zer0.muse.diagnostic

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.R
import io.zer0.muse.debug.DebugLogStore
import io.zer0.muse.schedule.BackgroundWakeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v2.x(Proyecto de exportación de diagnóstico A): empaquete la información de diagnóstico en un zip y active el uso compartido del sistema.
 *
 * Qué incluye (todo insensibilizado):
 *  - generation-trace.jsonl: Vincula trayectorias generadas en las últimas 20 rondas (estadísticas, sin texto)
 *  - device-info.txt:Dispositivo/Android versión/ABI/app versión
 *  - debug-log.txt:DebugLogStore búfer de registro existente (si lo hubiera)
 *  - background-work.txt:WorkManager El estado de las tareas en segundo plano registradas en la cola (la única respuesta es cuando la tarea programada "no se está ejecutando")
 *    Para obtener evidencia de "si el sistema se ha despertado", consulte [BackgroundWakeDiagnostics])
 *
 * **linea roja**: No contiene texto de mensaje,prompt,API key Texto original.
 */
object DiagnosticExporter {

    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Empaqueta el zip de diagnóstico en cacheDir y devuelve un URI de FileProvider (para uso compartido).
     * Devuelve nulo en caso de error.
     *
     * Todo el proceso está en el hilo IO:zip La escritura, la lectura de archivos de registro y las consultas de WorkManager no deberían sobrecargar el hilo de la persona que llama.
     */
    suspend fun exportZip(context: Context): android.net.Uri? = withContext(Dispatchers.IO) {
        val zipFile = buildDiagnosticZip(context) ?: return@withContext null
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        }.onFailure { Logger.w("DiagnosticExporter", "Exportación fallida: ${it.message}") }.getOrNull()
    }

    /**
     * Solo es responsable de escribir el contenido en el zip y no toca el FileProvider.
     *
     * El propósito de dividirlo en funciones independientes es hacer afirmaciones directamente sobre el contenido zip (sin depender de la declaración del proveedor ni de la intención de compartir).
     * Cuando cualquier enlace genera una excepción, todo el paquete falla y se devuelve un valor nulo.
     */
    internal suspend fun buildDiagnosticZip(context: Context): File? = withContext(Dispatchers.IO) {
        // No recopilar el estado de activación no afecta la exportación del paquete completo:Otra información de diagnóstico sigue siendo valiosa.
        val backgroundWork = resultOf {
            BackgroundWakeDiagnostics.formatSnapshot(BackgroundWakeDiagnostics.collectWithSettings(context))
        }.onError { message, throwable ->
            Logger.w("DiagnosticExporter", "Falló la recopilación del estado de activación en segundo plano: $message", throwable)
        }.getOrNull()
        runCatching {
            val zipFile = File(context.cacheDir, "muse_diagnostic_${exportDateFormat.format(Date())}.zip")
            ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
                // 1. Generar seguimientos de enlaces
                GenerationTrace.traceFileOrNull()?.let { traceFile ->
                    zos.putNextEntry(ZipEntry("generation-trace.jsonl"))
                    traceFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } ?: run {
                    // Almacenamiento en memoria intermedia cuando no hay ningún archivo (la ronda en la que el proceso acaba de iniciarse y aún no se ha descargado)
                    val records = GenerationTrace.snapshot()
                    if (records.isNotEmpty()) {
                        zos.putNextEntry(ZipEntry("generation-trace.jsonl"))
                        records.forEach { rec ->
                            val line = AppJson.encodeToString(GenerationTrace.TraceRecord.serializer(), rec)
                            zos.write((line + "\n").toByteArray())
                        }
                        zos.closeEntry()
                    }
                }

                // 2. Información del dispositivo
                zos.putNextEntry(ZipEntry("device-info.txt"))
                zos.write(buildDeviceInfo().toByteArray())
                zos.closeEntry()

                // 3. Registros de depuración existentes (búfer circular de memoria, solo las últimas 1000 entradas)
                DebugLogStore.exportToFile()?.let { debugFile ->
                    zos.putNextEntry(ZipEntry("debug-log.txt"))
                    debugFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 4. registro de archivos (cacheDir/logs/muse.log,1MB desplazarse)
                // Los problemas de enlace de canal a menudo ocurren antes de que se registren miles de registros y el búfer de memoria se haya vaciado durante mucho tiempo.
                Logger.getLogFile()?.let { logFile ->
                    zos.putNextEntry(ZipEntry("muse.log"))
                    logFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 5. Estado de activación en segundo plano (WorkManager Tareas registradas y estado de la cola)
                if (backgroundWork != null) {
                    zos.putNextEntry(ZipEntry("background-work.txt"))
                    zos.write(backgroundWork.toByteArray())
                    zos.closeEntry()
                }
            }
            zipFile
        }.onFailure { Logger.w("DiagnosticExporter", "Exportación fallida: ${it.message}") }.getOrNull()
    }

    /** Habilite el uso compartido del sistema. */
    suspend fun share(context: Context) {
        val uri = exportZip(context) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.diagnostic_export_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun buildDeviceInfo(): String = buildString {
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("locale: ${Locale.getDefault()}")
        appendLine("exported_at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
    }
}
