package io.zer0.ai.core

/**
 * v2.6.2: Relay aplica reglas de encabezado de solicitud (declarativas).
 *
 * Antecedentes: Transferencia parcial/El sitio de agregación impone una sesión para cada solicitud./Encabezado de identificación del cliente, si falta, será rechazado:
 *  - OpenCode Go(opencode.ai):`x-opencode-session`, devoluciones faltantes HTTP 400
 *    `MissingSessionID`. Este valor se utiliza para la afinidad de ruta y el almacenamiento en caché de avisos. —— El servidor no analiza su contenido.
 *    (Cualquier cadena es aceptable), pero el requisito es**La misma sesión es estable.**; de lo contrario, la tasa de aciertos de la caché vuelve a cero.
 *
 * Diseño: Declare reglas por host y la capa de solicitud se inyecta automáticamente en función del valor derivado de la identificación de la sesión actual. Para agregar una nueva estación de transferencia, simplemente agregue
 * [RULES] Agregue una línea, no es necesario cambiar el Proveedor/UI/persona que llama.
 *
 * Relación con el llenado del manual del usuario: se dará prioridad cuando el usuario complete explícitamente el encabezado con el mismo nombre en el "Encabezado de solicitud personalizada" en la página del proveedor.
 * (Ver [mergeWithUserHeaders]), la inyección automática solo cubre el resultado final y no cubre las intenciones del usuario.
 */
object RelayRequiredHeaders {

    /** Una regla única: qué hosts se ven afectados y qué encabezados se requieren. */
    private data class Rule(
        val match: (host: String) -> Boolean,
        val headerNames: List<String>,
    )

    /**
     * Se sabe que las estaciones de retransmisión requieren encabezados de sesión automáticos.
     *
     * Determine el host según baseUrl (minúscula, elimine el puerto). Compatible con subdominios personalizados mediante coincidencia de sufijos.
     */
    private val RULES: List<Rule> = listOf(
        // OpenCode Go — x-opencode-session(Afinidad de ruta + caché de avisos)
        Rule(
            match = { host -> host == "opencode.ai" || host.endsWith(".opencode.ai") },
            headerNames = listOf("x-opencode-session"),
        ),
    )

    /**
     * baseUrl debe ser una lista de nombres de encabezados inyectados automáticamente; Estará vacío si no hay reglas.
     */
    fun requiredHeaderNames(baseUrl: String): List<String> {
        val host = extractHost(baseUrl)
        if (host.isEmpty()) return emptyList()
        return RULES.firstOrNull { it.match(host) }?.headerNames.orEmpty()
    }

    /**
     * Generar encabezado automático (nombre → valor).
     *
     * El valor consiste en [seed] Derivado: priorizar la identificación de la sesión (estable por sesión) → La afinidad de la caché es correcta);
     * Degenera a un valor estable por proveedorId cuando no hay contexto de sesión (tarea en segundo plano) —— Asegúrese de que el encabezado exista,
     * 400 no se activa, la afinidad de la caché se reduce a la granularidad del proveedor.
     *
     * @param baseUrl URL base del proveedor (para coincidencia de host)
     * @param sessionId ID de sesión actual, opcional
     * @param providerId ID del proveedor (semilla degenerada cuando no hay ID de sesión)
     */
    fun autoHeaders(baseUrl: String, sessionId: String?, providerId: String): Map<String, String> {
        val names = requiredHeaderNames(baseUrl)
        if (names.isEmpty()) return emptyMap()
        val seed = sessionId?.takeIf { it.isNotBlank() } ?: "provider:$providerId"
        val value = stableId(seed)
        return names.associateWith { value }
    }

    /**
     * Los encabezados automáticos se combinan con encabezados explícitos del usuario —— Los valores explícitos del usuario tienen prioridad (el mismo nombre se determina sin distinguir entre mayúsculas y minúsculas).
     */
    fun mergeWithUserHeaders(auto: Map<String, String>, user: Map<String, String>): Map<String, String> {
        if (auto.isEmpty()) return user
        val userLower = user.keys.map { it.lowercase() }.toSet()
        val merged = LinkedHashMap<String, String>()
        auto.forEach { (k, v) -> if (k.lowercase() !in userLower) merged[k] = v }
        merged.putAll(user)
        return merged
    }

    /**
     * Identificación opaca estable derivada de semilla (32 bits hexadecimales). El servidor no analiza el contenido y solo requiere estabilidad.
     */
    private fun stableId(seed: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

    /** Extraiga el host (minúscula, elimine el puerto) de baseUrl. con [ProviderCompat] El extractHost tiene el mismo calibre. */
    private fun extractHost(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { return "" }
        return runCatching {
            trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
        }.getOrDefault("")
    }
}
