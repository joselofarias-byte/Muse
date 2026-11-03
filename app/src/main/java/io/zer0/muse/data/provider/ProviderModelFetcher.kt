package io.zer0.muse.data.provider

import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelListCache
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.x: Canalización de extracción de manifiesto del modelo de proveedor(Calibre común para página de guía/página de edición de proveedor).
 *
 * proceso: caché primero(5 minutos TTL) → URL Finalización de estrategias múltiples(El usuario no completó el llenado/Escenarios comunes de sobrellenado /v1) →
 * tirar → [ModelRegistry.enrich] Enriquecimiento de capacidades(abilities / modalities / contextWindow) →
 * Vuelva a escribir en el caché. Ya sean proveedores locales o puntos finales personalizados compatibles con OpenAI,Todos siguen la misma cadena.
 *
 * 401/403 Fallo inmediato sin respaldo de URL(Hay un problema de credenciales y cambiar la ruta es inútil.).
 */
object ProviderModelFetcher {

    sealed class Outcome {
        /** éxito: Lista de modelos enriquecida(No vacío) + configuración de golpe real(incluyendo corregido baseUrl). */
        data class Success(val models: List<Model>, val usedConfig: ProviderConfig) : Outcome()

        /** falló;[kind] Mapeo de copia localizada para personas que llaman,[message] es la información de diagnóstico original. */
        data class Failure(val kind: FailureKind, val message: String) : Outcome()
    }

    enum class FailureKind { AUTH, EMPTY, NETWORK, OTHER }

    /** Extraiga y enriquezca la lista de modelos. Devuelto cuando todas las estrategias fallan [Outcome.Failure]. */
    suspend fun fetch(config: ProviderConfig, forceFresh: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        // 1) caché primero(5 minutos TTL)— Compartir caché con la página de edición del proveedor,Evite golpear río arriba repetidamente
        if (!forceFresh) {
            val cached = ModelListCache.get(config, forceFresh = false)
            if (cached != null) {
                return@withContext if (cached.isEmpty()) {
                    Outcome.Failure(FailureKind.EMPTY, "cached model list is empty")
                } else {
                    Outcome.Success(cached.map { ModelRegistry.enrich(it) }, config)
                }
            }
        }

        // 2) URL Finalización de estrategias múltiples — Cubrir la información faltante del usuario/Escenario de completar más /v1
        val base = config.baseUrl.trimEnd('/')
        val urlsToTry = mutableListOf<String>()
        if (base.isNotBlank()) {
            urlsToTry.add(base)
            if (!base.endsWith("/v1") && !base.endsWith("/v1beta")) {
                urlsToTry.add("$base/v1")
            } else if (base.endsWith("/v1")) {
                urlsToTry.add(base.removeSuffix("/v1"))
            }
        } else {
            urlsToTry.add(config.baseUrl)
        }

        var lastError: String? = null
        var sawEmpty = false
        for (url in urlsToTry) {
            val cfg = config.copy(baseUrl = url)
            val result = resultOf { ProviderRegistry.create(cfg).listModels(cfg) }
            val models = result.getOrNull()
            if (models != null) {
                if (models.isEmpty()) {
                    sawEmpty = true
                    continue
                }
                // 3) Enriquecimiento uno por uno — derivación automática abilities / modalities / contextWindow,
                //    Asegurar la visión/El juicio de inferencia de tres estados ha entrado en vigor en la etapa de orientación.
                val enriched = models.map { ModelRegistry.enrich(it) }
                ModelListCache.put(cfg, models)
                return@withContext Outcome.Success(enriched, cfg)
            }
            var failMsg = "unknown"
            result.onError { msg, t -> failMsg = t?.message ?: msg }
            if (failMsg.contains("401") || failMsg.contains("403")) {
                return@withContext Outcome.Failure(FailureKind.AUTH, failMsg)
            }
            lastError = failMsg
        }

        // Todas las estrategias fallaron: Lista vacía vs red/Otros
        if (sawEmpty) {
            Outcome.Failure(FailureKind.EMPTY, "upstream returned no models")
        } else {
            Outcome.Failure(FailureKind.NETWORK, lastError ?: "unknown error")
        }
    }

    /**
     * Verificar que la configuración realmente funciona —— Basado en una conversación real.
     *
     * con [fetch] División del trabajo:
     *  - [fetch] solo responde“¿Qué modelos se pueden conseguir?”, basándose en modelos/implementación ascendentes.
     *    Y /models resulta ser el punto final menos confiable en la interfaz compatible. —— No implementado, devuelve una estructura no estándar,
     *    Es muy común imponer restricciones de autenticación solo, por lo que un proveedor que pueda chatear normalmente será condenado a muerte aquí.
     *  - Este método responde“¿Realmente puedes enviar un mensaje?”. Primero intenta /models obtener un nombre de modelo,
     *    Úsalo si no puedes conseguirlo. [fallbackModelId](Completado por la persona que llama) y luego envíe un mensaje mínimo.
     *
     * Semántica de resultados:
     *  - El chat es exitoso. → [VerifyOutcome.Ready], con el nombre del modelo y (posible) número de modelo;
     *  - no puedo chatear → [VerifyOutcome.NotReady], con controles de salud traducidos en motivos procesables.
     *
     * @param config Configuración a verificar (puede contener valores editados no guardados)
     * @param fallbackModelId El ID del modelo a probar cuando el upstream no puede obtener la lista (generalmente el primero configurado por el usuario)
     */
    suspend fun verify(config: ProviderConfig, fallbackModelId: String? = null): VerifyOutcome = withContext(Dispatchers.IO) {
        // Obtenga primero la lista de modelos: si puede conseguirla, traiga la cantidad. Si no puede conseguirlo, la detección del chat no se bloqueará.
        val listed = fetch(config)
        val listedModels = (listed as? Outcome.Success)?.models.orEmpty()
        val usedConfig = (listed as? Outcome.Success)?.usedConfig ?: config
        val probeId =
            listedModels.firstOrNull { it.id.isNotBlank() }?.id
                ?: fallbackModelId?.takeIf { it.isNotBlank() }
                ?: return@withContext VerifyOutcome.NotReady(
                    // Redacción publicitaria orientada al usuario: anteriormente, solo usaba inglés sin modelo para probar.
                    // Aparece una jerga de desarrollador en la interfaz china y los usuarios no pueden actuar en consecuencia.
                    reason = "No se encontró ningún modelo comprobable. Haga clic en \"Obtener\" para obtener la lista de modelos primero.",
                    kind = (listed as? Outcome.Failure)?.kind ?: FailureKind.OTHER,
                )

        val provider = ProviderRegistry.create(usedConfig)
        val health = resultOf {
            provider.healthCheck(Model(id = probeId, providerId = usedConfig.id))
        }
        val result = health.getOrNull()
        if (result?.success == true) {
            VerifyOutcome.Ready(
                modelId = probeId,
                modelCount = listedModels.size,
                usedConfig = usedConfig,
                models = listedModels,
            )
        } else {
            // healthCheck La excepción se ha traducido en palabras prácticas; Si no puede obtenerlo, deje el error original.
            val reason = result?.message
                ?: (health as? Result.Error)?.throwable?.message
                ?: "connection failed"
            VerifyOutcome.NotReady(reason = reason, kind = FailureKind.OTHER)
        }
    }

    /** [verify] resultado. */
    sealed class VerifyOutcome {
        /** Enlace de chat disponible.[modelCount] Un valor de 0 significa que el canal ascendente no ha proporcionado una lista, lo que no significa que no esté disponible. */
        data class Ready(
            val modelId: String,
            val modelCount: Int,
            val usedConfig: ProviderConfig,
            val models: List<Model>,
        ) : VerifyOutcome()

        /** El enlace del chat no está disponible.[reason] Ya es una frase visible. */
        data class NotReady(val reason: String, val kind: FailureKind) : VerifyOutcome()
    }
}
