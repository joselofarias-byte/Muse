package io.zer0.ai.core

/**
 * v1.0.18: Configuración de modelo gratuita.
 *
 * Cuando el usuario no completa la clave API,Utilice créditos gratuitos de SiliconFlow integrados para acceder a los modelos designados.
 *
 * Estrategia:
 *  - host Contiene silicioflow y modelId está en la lista blanca y la clave de usuario está vacía → usar fallback key
 *  - Una vez que el usuario complete su clave, se cerrará la sesión del usuario. key,fallback Ya no está en vigor
 *
 * Descripción del proyecto:
 *  - Este objeto se coloca en el módulo ai en lugar de app/data/preset,porque [OpenAIProvider] en el módulo ai
 *    Necesito llamar directamente [resolveApiKey];ai El módulo no depende del módulo de la aplicación.,Las dependencias inversas rompen las capas.
 *  - [FALLBACK_API_KEY] usar "PLACEHOLDER" Marcador de posición,La clave real la inyecta CI/CD durante el lanzamiento real.
 *    app El módulo build.gradle.kts está habilitado buildConfig = true,Inyección posterior a través de BuildConfig;
 *    Actualmente solo está ocupado,fallback Analiza la causa "PLACEHOLDER" La cuerda es [resolveApiKey] negarse a regresar null,
 *    Regresar al comportamiento original de apiKey(Una cadena vacía o un proveedor que permita permitirMissingApiKey aún puede ajustar el control remoto /models).
 */
object FreeModelConfig {

    /** La reserva de crédito gratuita sin llave solo se permite para este proveedor interno. */
    const val FREE_PROVIDER_ID = "preset_siliconflow_free"

    /**
     * SiliconFlow Lista blanca de modelos gratuita.
     *
     * Estos modelos están disponibles públicamente en la plataforma SiliconFlow de forma gratuita y pueden ser utilizados directamente por usuarios que no hayan configurado una clave.
     */
    val FREE_MODEL_IDS: Set<String> = setOf(
        "THUDM/GLM-4-9B-0414",
        "Qwen/Qwen3-8B",
    )

    /**
     * Clave API alternativa incorporada.
     *
     * Ruta de inyección(ai/build.gradle.kts configurado buildConfigField):
     *  1. línea de comando -PFREE_MODEL_KEY=xxx
     *  2. local.properties El campo FREE_MODEL_KEY(Ignorado por .gitignore)
     *  3. variables de entorno FREE_MODEL_KEY
     *  4. Retroceso cuando ninguno de los dos está configurado "PLACEHOLDER"(fallback El mecanismo no surte efecto.)
     */
    val FALLBACK_API_KEY: String = getFallbackKey()

    /**
     * SiliconFlow base url(con [io.zer0.muse.data.preset.SiliconFlowFreeModels.BASE_URL] consistente).
     */
    const val SILICONFLOW_BASE_URL: String = "https://api.siliconflow.cn/v1"

    /**
     * UI Copia del mensaje de orientación:Se muestra cuando el usuario selecciona el proveedor de SiliconFlow y no completa la clave.
     */
    const val FREE_PROVIDER_HINT: String =
        "Modelos gratuitos disponibles sin iniciar sesión(GLM-4-9B / Qwen3-8B),Complete la clave API para desbloquear más modelos"

    /**
     * Comprueba si el proveedor + modelo especificado es elegible para modelos gratuitos.
     *
     * @param providerId solo [FREE_PROVIDER_ID] o los proveedores heredados ocultos migrados permiten créditos gratuitos integrados
     * @param baseUrl proveedor base url
     * @param modelId modelo id
     * @param userApiKey Usuario configurado API key(Cadena vacía significa no configurado)
     * @param hiddenFromSettings Los ajustes preestablecidos gratuitos copiados de versiones anteriores también pueden conservar capacidades a través de etiquetas de migración
     * @return Devolución si se cumplen las condiciones fallback key,De lo contrario regresar null
     */
    fun resolveApiKey(
        providerId: String,
        baseUrl: String,
        modelId: String,
        userApiKey: String,
        hiddenFromSettings: Boolean = false,
    ): String? {
        val providerAllowed = providerId == FREE_PROVIDER_ID || hiddenFromSettings
        val hostAllowed = extractHost(baseUrl)?.contains(HOST_MARKER) == true
        val modelAllowed = FREE_MODEL_IDS.any { it.equals(modelId, ignoreCase = true) }
        // Saltar completamente cuando el usuario ya haya configurado su propia clave fallback,Tome la clave de usuario.
        return FALLBACK_API_KEY.takeIf {
            providerAllowed &&
                userApiKey.isBlank() &&
                hostAllowed &&
                modelAllowed &&
                isFallbackKeyAvailable()
        }
    }

    /**
     * R-SEC-07: fallback key ¿Está disponible?(No está vacío ni es un marcador de posición.).
     */
    fun isFallbackKeyAvailable(): Boolean = FALLBACK_API_KEY.isNotBlank() && FALLBACK_API_KEY != "PLACEHOLDER"

    /**
     * Determinar si el proveedor especificado es un modelo gratuito provider(SiliconFlow y el usuario no ha rellenado key).
     *
     * suministro [OpenAIProvider.listModels] Juez en la entrada:Cuando es verdadero, devuelve directamente la lista de modelos libres preestablecidos,
     * Sin ajuste remoto /models(Evite el fallo 401 por falta de clave).
     */
    fun isFreeProvider(providerId: String, baseUrl: String, userApiKey: String, hiddenFromSettings: Boolean = false): Boolean =
        (providerId == FREE_PROVIDER_ID || hiddenFromSettings) &&
            userApiKey.isBlank() &&
            extractHost(baseUrl)?.contains(HOST_MARKER) == true

    private const val HOST_MARKER = "siliconflow"

    private fun extractHost(baseUrl: String): String? = try {
        java.net.URL(baseUrl).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /**
     * Leer desde BuildConfig fallback key,Retroceso cuando no se inyecta "PLACEHOLDER".
     *
     * ai El módulo build.gradle.kts tiene buildConfig activado y aprobado buildConfigField
     * inyectar FREE_MODEL_KEY(prioridad: -P parámetros > local.properties > variables de entorno > PLACEHOLDER).
     */
    private fun getFallbackKey(): String {
        return try {
            io.zer0.ai.BuildConfig.FREE_MODEL_KEY
        } catch (_: Throwable) {
            "PLACEHOLDER"
        }
    }
}
