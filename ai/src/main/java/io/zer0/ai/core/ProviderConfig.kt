package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * Clasificación de proveedores(Se utiliza para la visualización del grupo UI).
 * - [OFFICIAL]: Conexión directa oficial del fabricante.
 * - [RELAY]: estación de transferencia(OpenAI Protocolo compatible,Agregar múltiples fabricantes)
 * - [CUSTOM]: Definido por el usuario
 */
@Serializable
enum class ProviderCategory {
    OFFICIAL,
    RELAY,
    CUSTOM,
}

/**
 * Provider configuración de conexión.
 *
 * Phase 3 Soporta la coexistencia de múltiples Proveedores.:Cada banda de configuración [type] El campo identifica al fabricante.,
 * [ProviderRegistry] Según [type] Tome la sucursal correspondiente.
 *
 * Phase 8.1: agregar [specific] campo(ProviderSpecificConfig sealed class),
 * Configurar proveedor de forma única(Claude rápidoAlmacenamiento en caché / OpenAI useResponseApi /
 * Gemini El Vertex AI et al.)Encapsular en subclase,Deje que el compilador garantice la seguridad de tipos.
 * Cuando los datos JSON antiguos no tienen campos específicos,Para marco de serialización [fallbackSpecific] Llega al fondo de esto.
 *
 * [baseUrl] Presione cuando quede en blanco [type] Tomar valor predeterminado:
 *  - OPENAI → [DEFAULT_OPENAI_BASE_URL]
 *  - ANTHROPIC → [DEFAULT_ANTHROPIC_BASE_URL]
 *  - GEMINI → [DEFAULT_GEMINI_BASE_URL]
 *
 * P1-6: [oauthConfig] tiempo no vacío,ProviderEditPage "OAuth Botón de inicio de sesión,
 * Obtenido automáticamente a través del flujo del dispositivo o del flujo del código de autorización [apiKey](access_token).
 *
 * @param enabled Ya sea para habilitar(Predeterminado true),Los proveedores deshabilitados no aparecen en la lista de selección
 * @param builtIn ¿Está incorporado?(Predeterminado false),El proveedor integrado no se puede eliminar
 * @param balanceApiPath Ruta del punto final de consulta de saldo(Opcional,Como por ejemplo /dashboard/billing/usage)
 * @param balanceResultPath Ruta JSON del resultado del saldo(Como por ejemplo $.data.total_usage)
 * @param category Clasificación de proveedores(Predeterminado OFFICIAL,Compatible con versiones anteriores con datos más antiguos)
 * @param oauthConfig P1-6 OAuth Configuración de inicio de sesión(Opcional);Admite la adquisición automática de OAuth cuando no es nulo apiKey
 * @param allowMissingApiKey v1.0.6: Ya sea para permitir vacío apiKey(authType=none/optional).
 *   true El proveedor no requiere apiKey(Como el despliegue local de Ollama,system-speech espera).
 *   Predeterminado false(compatible con versiones anteriores,La mayoría de los proveedores requieren apiKey).
 * @param specId v1.0.7: Identificación de especificaciones de proveedor incorporada(Especificaciones del proveedor predeterminado providerId).
 *   Si no es nulo, indica que la configuración se deriva de una especificación incorporada del proveedor.(Como por ejemplo "openai"/"deepseek"/"anthropic"),
 *   tiempo de ejecución [io.zer0.ai.core.ProviderSpecMerger] Fusionará la lista de modelos predeterminados de especificaciones con la superposición del usuario.:
 *    - spec Modelo predeterminado + modelo definido por el usuario(Eliminar duplicados con el mismo ID,Los campos de usuario tienen prioridad)
 *    - Cuando el proveedor integrado actualiza la lista de modelos predeterminados,Los usuarios agregados ven automáticamente el nuevo modelo.
 *   Cuando nulo representa un proveedor puramente personalizado,No involucrado en la fusión(Compatible con versiones anteriores con datos más antiguos).
 *   Proporcionar datos existentes durante la migración de datos "preset_" La configuración del id del prefijo infiere automáticamente el specId.
 * @param requestLimitPerMinute P1-3: Número máximo de solicitudes por minuto(RPM),0 No indica límite.
 *   [io.zer0.ai.decorator.RateLimitDecorator] Utilice una ventana deslizante para limitar el flujo antes de enviar la solicitud,
 *   Cuando se exceda el límite, espere un espacio en lugar de rechazarlo directamente.,Evite fallas en las solicitudes de los usuarios.
 *   con KeyRoulette(429 interruptor de llave posterior)complementario:decorator Limitación precorriente,KeyRoulette Vuelva a intentarlo más tarde.
 * @param maxConcurrentRequests P1-3: Número máximo de solicitudes simultáneas en vuelo,0 No indica límite.
 *   Controlado por corrutina Semaphore,Limitar la transmisión simultánea desde el mismo proveedor/Número de solicitudes que no son de transmisión,
 *   Evite que una gran cantidad de solicitudes simultáneas abrumen el flujo ascendente en un corto período de tiempo(Especialmente en escenarios con múltiples grupos de claves, KeyRoulette solo se preocupa por el sondeo de claves y no por la concurrencia total.).
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val type: ProviderType = ProviderType.DEFAULT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<Model> = emptyList(),
    val specific: ProviderSpecificConfig? = null,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /** La interfaz aún puede utilizar proveedores de tiempo de ejecución internos,pero no aparece en la lista de configuración del proveedor. */
    val hiddenFromSettings: Boolean = false,
    val balanceApiPath: String = "",
    val balanceResultPath: String = "",
    val category: ProviderCategory = ProviderCategory.OFFICIAL,
    val oauthConfig: OAuthConfig? = null,
    val allowMissingApiKey: Boolean = false,
    val specId: String? = null,
    val requestLimitPerMinute: Int = 0,
    val maxConcurrentRequests: Int = 0,
) {
    /**
     * v1.80 (H-CORE1): Personalice toString para evitar que el texto sin formato de apiKey se filtre en los registros/Salida de depuración.
     * data class El toString generado automáticamente quedará expuesto. apiKey,Procesamiento de máscara aquí.
     *
     * v1.80 (M-CORE1): corto key(<=12 poco)La tasa de fuga es demasiado alta.(Originales take(4)+takeLast(4)
     * si 8~12 Las claves de bits apenas están enmascaradas),Cambia la máscara del uniforme a "****",Sólo los últimos 2 dígitos se revelan para identificación.
     */
    override fun toString(): String {
        val maskedKey = when {
            apiKey.length <= 12 && apiKey.isNotEmpty() -> "****" + apiKey.takeLast(2)
            apiKey.length > 12 -> apiKey.take(4) + "***" + apiKey.takeLast(4)
            else -> ""
        }
        return "ProviderConfig(id=$id, displayName=$displayName, type=$type, baseUrl=$baseUrl, " +
            "apiKey=$maskedKey, models=${models.size}un, specific=$specific, enabled=$enabled, " +
            "builtIn=$builtIn, category=$category, oauthConfig=${
                if (oauthConfig != null) "OAuthConfig(clientId=${oauthConfig.clientId})" else "null"
            })"
    }

    /**
     * analizar real por tipo baseUrl(Si está vacío, tome el valor predeterminado).
     *
     * v1.80 (L-CORE13): Advertencia de Log Logger.w para URL base que no sea https,
     * Advertir a los usuarios sobre el riesgo de transmisión HTTP de texto claro apiKey(Los valores predeterminados son todos https,Personalizar solo baseUrl puede violar las reglas).
     */
    fun resolvedBaseUrl(): String {
        val resolved = normalizeKnownBaseUrl(
            baseUrl.trimEnd('/').ifBlank {
                when (type) {
                    ProviderType.OPENAI -> DEFAULT_OPENAI_BASE_URL
                    ProviderType.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
                    ProviderType.GEMINI -> DEFAULT_GEMINI_BASE_URL
                    ProviderType.OPENAI_RESPONSES -> DEFAULT_OPENAI_RESPONSES_BASE_URL
                }
            },
        )
        if (resolved.isNotEmpty() && !resolved.startsWith("https://", ignoreCase = true)) {
            io.zer0.common.Logger.w(
                "ProviderConfig",
                "baseUrl no https(id=$id, type=$type),apiKey Posiblemente transmitido en texto claro.: $resolved",
            )
        }
        return resolved
    }

    /**
     * SiliconFlow documents its OpenAI-compatible API under `/v1`.
     *
     * Accepting the host-only form in the settings UI is useful, but every
     * request path must use the same normalized base URL. Without this,
     * `/models` may be discovered through the UI fallback while chat requests
     * are later sent to the host root and fail with HTTP 404.
     */
    private fun normalizeKnownBaseUrl(url: String): String = if (url.equals("https://api.siliconflow.cn", ignoreCase = true)) {
        "$url/v1"
    } else {
        // NewAPI's web UI is served from the host root; the OpenAI-compatible API is under /v1.
        // Users commonly paste only the host, which makes /models resolve to the HTML SPA and
        // fail JSON parsing. Normalize both the built-in template and this known host consistently
        // so model discovery and later chat requests use the same API base.
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val path = uri?.path.orEmpty().trimEnd('/')
        val isNewApi = id == "preset_newapi" || uri?.host?.equals("newapi.0z.hk", ignoreCase = true) == true
        if (isNewApi && path.isBlank()) "$url/v1" else url
    }

    /**
     * Obtener configuración específica,specific Presione cuando sea nulo [type] Ten todo en cuenta(Compatible con versiones anteriores con datos más antiguos).
     * De esta manera, la persona que llama siempre obtendrá un valor no nulo. [ProviderSpecificConfig],
     * puede estar seguro `as ProviderSpecificConfig.Anthropic` Convertir.
     */
    fun resolvedSpecific(): ProviderSpecificConfig = specific ?: ProviderSpecificConfig.defaultFor(type)

    /**
     * Derivar matriz de capacidades del proveedor(compat).
     *
     * llamar [ProviderCompatRules.resolve] prensa type + baseUrl + (Opcional) modelId Tres niveles de emparejamiento:
     *  1. type Determinar las reglas básicas a nivel de protocolo(OPENAI lleno true / ANTHROPIC / GEMINI)
     *  2. baseUrl host Refinando las diferencias entre las capas de compatibilidad OpenAI de los proveedores
     *  3. modelId Refinar la compatibilidad con modelos específicos
     *
     * @param modelId El modelo solicitado actualmente. id(Opcional). Diferentes modelos del mismo Proveedor pueden tener diferentes compat,
     *   Al pasar, refinar más por modelo(Si el razonador de búsqueda profunda está desactivado tool calling);
     *   Si no se aprueba, solo se derivará por tipo + baseUrl.
     */
    fun resolvedCompat(modelId: String? = null): ProviderCompat = ProviderCompatRules.resolve(type, baseUrl, modelId)

    companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
        const val DEFAULT_GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_OPENAI_RESPONSES_BASE_URL = "https://api.openai.com/v1"
    }
}
