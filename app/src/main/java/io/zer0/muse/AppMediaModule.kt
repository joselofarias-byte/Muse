package io.zer0.muse

import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 Deslocalización:OCR / TTS / Los servicios de medios como la clonación de voz registran módulos independientes.
 */
val appMediaModule = module {

    // Phase 8.6: Administrador de OCR local(ML Kit Reconocimiento fuera de línea de chino e inglés)
    single { io.zer0.muse.doc.OcrManager() }

    // v1.0.30 gap4.6: Almacenamiento del glosario de traducción(JSON Texto original de persistencia de archivos→mapeo de traducción)
    single { io.zer0.muse.ui.translate.GlossaryStore(androidContext()) }

    // Phase 8.7: TTS Gerente(Android sistema TextToSpeech,0 APK Volumen)
    // v1.97: Inyecte CloudTtsService para admitir la nube TTS(OpenAI/MiniMax/Edge)
    // v1.97 Reparación: CloudTtsService Necesidades de construcción OkHttpClient,Debe usar named("chat") qualifier.
    //   Koin Sólo registrado con calificador OkHttpClient(chat/webSearch),desnudo get() Definición no encontrada,
    //   release Provocado por la confusión NoDefinitionFoundException,El encadenamiento hace que falle la creación de ChatViewModel → La aplicación falla.
    //   chat client Tiempo de espera configurado de 30 s/120 s/30 s + proxy,Adecuado para solicitudes de red TTS,No es necesario construir uno separado.
    single { io.zer0.muse.ui.speech.CloudTtsService(get(named("chat"))) }
    single { io.zer0.muse.ui.speech.TtsManager(androidContext(), get()) }

    // v2.6.16: Archivo de audio de mensajes de voz (filesDir/voice_messages/, separados de los archivos temporales cacheDir para evitar que el sistema los limpie)
    single {
        io.zer0.muse.ui.speech.VoiceMessageStore(
            storageDir = java.io.File(androidContext().filesDir, "voice_messages"),
        )
    }

    // v2.6.16: Controlador de reproducción para compartir mensajes de voz —— Las barras de voz no contienen cada una de ellas un jugador,
    //   Esta única instancia garantiza que "sólo se reproduzca una voz al mismo tiempo".
    single { io.zer0.muse.ui.speech.VoicePlaybackController() }

    // P2-9: Clonación de voz — ElevenLabs Voice Cloning Provider Reutilizar chat OkHttpClient
    //   (Para uso interno newBuilder() Anula tres tiempos de espera de 30 segundos,satisfacer"API La llamada debe tener un tiempo de espera.(30 segundos)"restricción)
    single { io.zer0.muse.ui.speech.ElevenLabsVoiceCloningProvider(get(named("chat"))) }
    single { io.zer0.muse.ui.speech.FishAudioVoiceCloningProvider(get(named("chat"))) }
    // P2-9: VoiceCloningService Distribución multiproveedor(OpenVoice / Fish Audio, etc. se pueden seguir agregando en el futuro. map)
    single {
        io.zer0.muse.ui.speech.VoiceCloningService(
            mapOf(
                "elevenlabs" to get<io.zer0.muse.ui.speech.ElevenLabsVoiceCloningProvider>(),
                "fish" to get<io.zer0.muse.ui.speech.FishAudioVoiceCloningProvider>(),
            ),
        )
    }
}
