package io.zer0.ai

import io.zer0.ai.image.AgnesImageProvider
import io.zer0.ai.image.GeminiImageProvider
import io.zer0.ai.image.ImageProviderRegistry
import io.zer0.ai.image.ImageService
import io.zer0.ai.image.OpenAIImageProvider
import io.zer0.ai.video.AgnesVideoProvider
import io.zer0.ai.video.GenericOpenAiVideoProvider
import io.zer0.ai.video.KlingVideoProvider
import io.zer0.ai.video.VideoGenerationService
import io.zer0.ai.video.VideoProviderRegistry
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * ai Montaje de módulos Koin.
 *
 * [ProviderConfigStore] Implementado por el módulo de la aplicación.(Basado en DataStore),
 * [OkHttpClient] También registrado por el módulo de la aplicación.(usar named("chat") qualifier,Diferenciarse del cliente de búsqueda web),
 * Por lo tanto, aquí sólo declaramos [ChatService] / [ImageService] / [VideoGenerationService].
 *
 * v1.0.18: La generación de imágenes cambió a la abstracción del proveedor. — Registrarse [ImageProviderRegistry] con cada uno [io.zer0.ai.image.ImageProvider]
 * (Agnes / OpenAI),[ImageService] a través de prensa de registro [io.zer0.ai.core.ProviderConfig] Selecciona automáticamente el adaptador.
 */
val aiModule: Module = module {
    single { ChatService(get()) }

    // v1.0.18: Generación de imágenes Capa de abstracción del proveedor
    // G4: Imagen de referencia del validador SSRF inyectado por Koin(app Módulo SsrfBridgeRegistro del módulo
    // RefImageUrlValidator → SsrfGuard.refImageUrlValidator);Reserva cuando no está registrado null(viejo comportamiento).
    single {
        AgnesImageProvider(
            client = get(named("chat")),
            referenceImageUrlValidator = getOrNull<RefImageUrlValidator>(),
        )
    }
    single {
        OpenAIImageProvider(
            client = get(named("chat")),
            referenceImageUrlValidator = getOrNull<RefImageUrlValidator>(),
        )
    }
    // B14-2: Gemini fotos independientes Provider(Salida de imagen nativa de generateContent)
    single {
        GeminiImageProvider(
            client = get(named("chat")),
            referenceImageUrlValidator = getOrNull<RefImageUrlValidator>(),
        )
    }
    single {
        ImageProviderRegistry().apply {
            register(get<AgnesImageProvider>())
            register(get<OpenAIImageProvider>())
            register(get<GeminiImageProvider>())
        }
    }
    single { ImageService(get(), get()) } // configStore + registry

    // P2-8: Servicios de generación de vídeo. — inyectar named("chat") OkHttpClient(Ya asignado 30s connect / 300s read tiempo de espera,
    // Cumplir con el requisito de tiempo de espera de 30 segundos para el envío + 5 minutos para el sondeo)
    // v1.137: Generación y reconstrucción de vídeo. — Registrarse [VideoProviderRegistry],Ruta a específico por specId/host Provider,
    // No más coincidencias difíciles por ID de proveedor(Reparación preset_kling ≠ kling enrutamiento bug).
    // Cada proveedor implementa la interfaz VideoProvider,Unifique el protocolo de envío/encuesta.
    single { KlingVideoProvider(get(named("chat"))) }
    single { AgnesVideoProvider(get(named("chat"))) }
    single { GenericOpenAiVideoProvider(get(named("chat"))) }
    single {
        VideoProviderRegistry(genericProvider = get<GenericOpenAiVideoProvider>()).apply {
            register(get<KlingVideoProvider>())
            register(get<AgnesVideoProvider>())
        }
    }
    single { VideoGenerationService(get()) }
}

/**
 * G4: Comprobador SSRF antes de descargar la imagen de referencia — http/https URL Determinado por la capa de aplicación SsrfGuard
 * (Ir a la intranet/bucle invertido/La dirección reservada devuelve falso y debe rechazarse),el resto scheme(data:/file:) Siempre fiel al lanzamiento.
 *
 * app El módulo SsrfBridgeModule [SsrfGuard.refImageUrlValidator] Regístrate para este tipo;
 * Cuando no está registrado, AgnesImageProvider obtiene null,Revertir el antiguo comportamiento(Descarga directa).
 */
fun interface RefImageUrlValidator {
    /** Devolver verdadero indica que la URL puede iniciar la descarga;false debe ser rechazado. */
    fun isAllowed(url: String): Boolean
}
