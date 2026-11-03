package io.zer0.muse.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantCardExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 8.10: Deep Link + El sistema comparte el procesador de recepción.
 *
 * Soporta dos categorías Intent:
 *
 * **enlace profundo**(`muse://` Acuerdo):
 *  - `muse://session/{sessionId}` → Abrir sesión especificada
 *  - `muse://new` → Nueva sesión
 *  - `muse://assistants` → Abra la página de gestión del Asistente
 *  - `muse://settings` → Abrir página de configuración
 *
 * **Compartir sistema**(ACTION_SEND / ACTION_PROCESS_TEXT):
 *  - Otras aplicaciones"Compartir con…"→ muse recibir texto,Precompletar en el cuadro de entrada
 *  - Mantenga presionado para seleccionar texto → "enviado en musa" → Igual que arriba
 *  - apoyo EXTRA_TEXT(texto plano) + EXTRA_STREAM(clase de texto URI,Procesamiento simplificado)
 *
 * diseño:
 *  - Capa analítica pura,no depende de ViewModel;Regresar [ShareResult] por la persona que llama(ChatScreen/ChatViewModel)consumo
 *  - Deep Link Para segmentación de rutas `Uri.host` + `Uri.pathSegments`,No depende de la regularidad
 *  - La extensión máxima del texto compartido es de 10.000 palabras.(Evite los de gran tamaño Intent OOM)
 *
 * @param context para analizar stream URI(leer InputStream)
 */
class ShareIntentHandler(private val context: Context) {

    /** Analiza los resultados. */
    sealed class ShareResult {
        /** Abra la sesión especificada. */
        data class OpenSession(val sessionId: String) : ShareResult()

        /**
         * Crea una nueva sesión.
         *
         * [assistantId] No nulo indica la persona que llama(widget de escritorio)Asistente asignado,Debe usarse al crear un nuevo,
         * De lo contrario, el asistente seleccionado por el usuario en el widget volverá al asistente predeterminado global después de hacer clic en él.
         *
         * [startVoice] Cuando sea verdadero, ingrese la entrada de voz inmediatamente después de crear una nueva creación. —— "Entrada rápida de voz" para widgets de escritorio
         * Anteriormente, sólo creaba nuevas sesiones y no abría el micrófono, lo que no era digno de su nombre. La lógica de Openmic reutiliza el acceso directo del Lanzador.
         */
        data class NewSession(val assistantId: String? = null, val startVoice: Boolean = false) : ShareResult()

        /** Abra la página de gestión del Asistente. */
        object OpenAssistants : ShareResult()

        /** Abra la página de configuración. */
        object OpenSettings : ShareResult()

        /** Abrir lista de conversaciones(desde widgets de escritorio"conversaciones recientes"). */
        object OpenChats : ShareResult()

        /** v1.64: Abrir la página de tareas programadas(Haga clic en la notificación de tarea programada). */
        object OpenScheduledTasks : ShareResult()

        /** Abra la tarea programada especificada y expanda el último registro de ejecución. */
        data class OpenScheduledTask(val taskId: String) : ShareResult()

        /** Abra notas rápidas y expanda la nota especificada. */
        data class OpenQuickNote(val noteId: String) : ShareResult()

        /** Abra la página de detalles del chat(Notificaciones sin contexto de sesión específico). */
        object OpenChat : ShareResult()

        /** Abra la página de inicio de la tarea. */
        object OpenHome : ShareResult()

        /** Abrir configuración-Datos y respaldo. */
        object OpenSettingsData : ShareResult()

        /** Abrir configuración-Copia de seguridad en la nube. */
        object OpenCloudBackup : ShareResult()

        /** Abrir configuración-Apariencia/Acerca de esperar el objetivo de notificación. */
        object OpenSettingsAbout : ShareResult()

        /** Abrir configuración-Asistente y Agente. */
        object OpenSettingsAgent : ShareResult()

        /** Abrir configuración-Modelos y Servicios. */
        object OpenSettingsModel : ShareResult()

        /** Abre el centro de memoria. */
        object OpenMemory : ShareResult()

        /** Gestión de bases de conocimiento abiertas. */
        object OpenKnowledge : ShareResult()

        /** Gestión de colecciones de bases de conocimientos abiertas. */
        object OpenKnowledgeBases : ShareResult()

        /** v2.x: Importar paquete de personajes(.muse-assistant Se hace clic en el archivo). */
        data class ImportAssistantCard(val uri: Uri) : ShareResult()

        /** Launcher atajo:Abrir página de traducción(de ACTION_TRANSLATE). */
        object OpenTranslate : ShareResult()

        /** Launcher atajo:Vaya a la página de inicio y active la entrada de voz(de ACTION_VOICE_INPUT). */
        object StartVoiceInput : ShareResult()

        /** v1.0.18: Launcher atajo:Abra la página de registro rápido(de ACTION_QUICK_NOTES). */
        object OpenQuickNotes : ShareResult()

        /** Precompletar texto en el cuadro de entrada(De compartir/PROCESS_TEXT). */
        data class PrefillText(val text: String) : ShareResult()

        /** Sin intención coincidente. */
        object None : ShareResult()
    }

    /**
     * analizar Activity Intent,Regresar [ShareResult].
     * en [io.zer0.muse.MainActivity.onNewIntent] y llamó a onCreate.
     *
     * H5-1: Cambiar a suspend,Para archivo interno IO withContext(Dispatchers.IO) paquete,Evite bloquear el hilo principal.
     * La persona que llama debe llamarlo en la rutina.(Como LaunchedEffect o appScope.launch).
     */
    suspend fun handle(intent: Intent?): ShareResult {
        if (intent == null) return ShareResult.None
        // Priorizar el procesamiento Deep Link
        val data = intent.data
        if (data != null && data.scheme == SCHEME) {
            return parseDeepLink(data)
        }
        // v2.x: Se abre el archivo del paquete de personajes(VIEW + application/x-muse-assistant)→ Proceso de vista previa de importación
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            val type = intent.type ?: withContext(Dispatchers.IO) {
                resultOf { context.contentResolver.getType(data) }.getOrNull()
            }
            if (type == AssistantCardExporter.MIME_TYPE) {
                return ShareResult.ImportAssistantCard(data)
            }
        }
        // Compartir sistema de reprocesamiento
        when (intent.action) {
            Intent.ACTION_SEND -> return parseShareSend(intent)
            Intent.ACTION_PROCESS_TEXT -> return parseProcessText(intent)
        }
        return ShareResult.None
    }

    /** analizar muse:// deep link(El conjunto de objetivos es limitado y se enumera explícitamente,Facilita la auditoría de cada entrada de notificación.). */
    @Suppress("CyclomaticComplexMethod")
    private fun parseDeepLink(uri: Uri): ShareResult {
        val host = uri.host ?: return ShareResult.None
        val segments = uri.pathSegments
        return when (host) {
            "session" -> {
                val id = segments.firstOrNull()
                // M4: sessionId Verificación de formato — Antiinyección,Sólo se permiten letras/numeros/subrayado/guión,longitud 1-64
                if (id != null && SESSION_ID_REGEX.matches(id)) {
                    ShareResult.OpenSession(id)
                } else {
                    if (id != null) Logger.w(TAG, "Deep link sessionId Formato ilegal,ignorado: $id")
                    ShareResult.None
                }
            }
            "new" -> ShareResult.NewSession()
            "assistants" -> ShareResult.OpenAssistants
            "settings" -> ShareResult.OpenSettings
            "scheduled-tasks" -> ShareResult.OpenScheduledTasks
            "scheduled-task" -> {
                val id = segments.firstOrNull()
                if (id != null && SESSION_ID_REGEX.matches(id)) {
                    ShareResult.OpenScheduledTask(id)
                } else {
                    ShareResult.None
                }
            }
            "quick-notes" -> ShareResult.OpenQuickNotes
            "quick-note" -> {
                val id = segments.firstOrNull()
                if (id != null && SESSION_ID_REGEX.matches(id)) {
                    ShareResult.OpenQuickNote(id)
                } else {
                    ShareResult.None
                }
            }
            "chat" -> ShareResult.OpenChat
            "home" -> ShareResult.OpenHome
            "settings-data" -> ShareResult.OpenSettingsData
            "cloud-backup" -> ShareResult.OpenCloudBackup
            "settings-about" -> ShareResult.OpenSettingsAbout
            "settings-agent" -> ShareResult.OpenSettingsAgent
            "settings-model" -> ShareResult.OpenSettingsModel
            "memory" -> ShareResult.OpenMemory
            "knowledge" -> ShareResult.OpenKnowledge
            "knowledge-bases" -> ShareResult.OpenKnowledgeBases
            else -> {
                Logger.w(TAG, "Unknown deep link host: $host")
                ShareResult.None
            }
        }
    }

    /** analizar ACTION_SEND(compartir texto). */
    private suspend fun parseShareSend(intent: Intent): ShareResult {
        // Prioridad EXTRA_TEXT(Compartir texto sin formato)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            return ShareResult.PrefillText(text.take(MAX_SHARE_TEXT_LEN))
        }
        // intentarlo de nuevo EXTRA_STREAM(clase de texto URI,Como un archivo .txt)
        // M5-1: API 33+ Usar escrito getParcelableExtra,La antigua API toma la ruta obsoleta
        val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (stream != null) {
            // H5-1: Archivo IO en Dispatchers.IO,Evite bloquear el hilo principal.
            // H5-2: Limitar el volumen de lectura,Deténgase cuando el número total no exceda MAX_SHARE_TEXT_LEN + 1 caracteres,Evite archivos grandes OOM
            // M5-2: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
            val streamText = withContext(Dispatchers.IO) {
                resultOf {
                    context.contentResolver.openInputStream(stream)?.use { input ->
                        val reader = input.bufferedReader(Charsets.UTF_8)
                        val buf = CharArray(8192)
                        val sb = StringBuilder()
                        while (sb.length <= MAX_SHARE_TEXT_LEN) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            sb.append(buf, 0, n)
                        }
                        if (sb.length > MAX_SHARE_TEXT_LEN) {
                            sb.substring(0, MAX_SHARE_TEXT_LEN)
                        } else {
                            sb.toString()
                        }
                    }
                }.onError { msg, _ -> Logger.w(TAG, "No se pudo leer la transmisión compartida: $msg") }.getOrNull()
            }
            if (!streamText.isNullOrBlank()) {
                return ShareResult.PrefillText(streamText)
            }
        }
        return ShareResult.None
    }

    /** analizar ACTION_PROCESS_TEXT(Mantenga presionado para seleccionar texto → enviado en musa). */
    private fun parseProcessText(intent: Intent): ShareResult {
        val text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        return if (text.isNullOrBlank()) {
            ShareResult.None
        } else {
            ShareResult.PrefillText(text.take(MAX_SHARE_TEXT_LEN))
        }
    }

    companion object {
        private const val TAG = "ShareIntentHandler"

        /** muse:// scheme(AndroidManifest intent-filter necesidad de coincidir). */
        const val SCHEME = "muse"

        /**
         * Analice la intención generada por el clic en el widget del escritorio.
         *
         * Generado desde el método privado MainActivity,Esto es para permitir una prueba única de "si se ha leído la ID del asistente presentada por el widget";
         * Anteriormente, este vínculo sólo podía verificarse haciendo clic en un dispositivo real.,Estuvo desconectado mucho tiempo y nadie se dio cuenta.
         */
        fun parseWidgetAction(intent: Intent?): ShareResult? {
            val action = intent?.getStringExtra(EXTRA_WIDGET_ACTION) ?: return null
            return when (action) {
                WIDGET_ACTION_NEW_SESSION, WIDGET_ACTION_VOICE_INPUT -> {
                    val assistantId = intent.getStringExtra(EXTRA_WIDGET_ASSISTANT_ID)
                        ?.takeIf { it.isNotBlank() }
                    ShareResult.NewSession(
                        assistantId = assistantId,
                        startVoice = action == WIDGET_ACTION_VOICE_INPUT,
                    )
                }
                WIDGET_ACTION_OPEN_CHATS -> ShareResult.OpenChats
                WIDGET_ACTION_OPEN_SESSION -> {
                    val sessionId = intent.getStringExtra(EXTRA_WIDGET_SESSION_ID)
                    if (sessionId != null) ShareResult.OpenSession(sessionId) else null
                }
                else -> null
            }
        }

        /** Longitud máxima del texto compartido(Prevenir el sobredimensionamiento Intent OOM). */
        private const val MAX_SHARE_TEXT_LEN = 10_000

        /** M4: sessionId Reglas de verificación de formato — Antiinyección,Sólo se permiten letras/numeros/subrayado/guión,Longitud 1-64. */
        private val SESSION_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        /** Phase 12: widgets de escritorio → MainActivity empezar extra key(Alinear con MuseQuickWidget). */
        const val EXTRA_WIDGET_ACTION = "widget_action"
        const val WIDGET_ACTION_NEW_SESSION = "new_session"
        const val WIDGET_ACTION_OPEN_CHATS = "open_chats"

        /** v2.6.7: Widget de escritorio \"Entrada rápida de voz\"——Ingrese la entrada de voz inmediatamente después de crear una nueva sesión. */
        const val WIDGET_ACTION_VOICE_INPUT = "voice_input"

        /** P3-16: Abrir sesión especificada(Usar con EXTRA_WIDGET_SESSION_ID). */
        const val WIDGET_ACTION_OPEN_SESSION = "open_session"
        const val EXTRA_WIDGET_SESSION_ID = "widget_session_id"

        /** widget de escritorio → Especificar un asistente al crear una nueva sesión(Usar con WIDGET_ACTION_NEW_SESSION). */
        const val EXTRA_WIDGET_ASSISTANT_ID = "widget_assistant_id"

        /** Launcher Atajo de pulsación larga action(Consistente con la definición en atajos.xml). */
        const val ACTION_NEW_CHAT = "io.zer0.muse.ACTION_NEW_CHAT"
        const val ACTION_TRANSLATE = "io.zer0.muse.ACTION_TRANSLATE"
        const val ACTION_VOICE_INPUT = "io.zer0.muse.ACTION_VOICE_INPUT"
        const val ACTION_SETTINGS = "io.zer0.muse.ACTION_SETTINGS"

        /** v1.0.18: Graba rápidamente acciones de atajos. */
        const val ACTION_QUICK_NOTES = "io.zer0.muse.ACTION_QUICK_NOTES"
    }
}
