package io.zer0.muse.data

/**
 * R-TEST-04: Política de clave de seguridad para la instantánea de la configuración de copia de seguridad.
 *
 * Copia de seguridad y restauración en todos los dispositivos,Los valores confidenciales que dependen de SecureKeyStore no se pueden descifrar,Por lo tanto, solo se permite al exportar.
 * Claves explícitas de la lista blanca;Se excluyen todas las claves que contengan nombres de campos confidenciales, como apiKey/token/password/secret.
 */
object SettingsSnapshotPolicy {

    val safeStringKeys = listOf(
        "active_provider_id", "selected_model_id", "tool_model_id",
        "compress_model_id",
        "theme_mode", "language", "theme_id", "dark_theme_id",
        "theme_schedule_json", "custom_themes_json", "font_size_scale",
        "prompt_templates_json", "user_profile_json", "chat_preferences_json",
        "memory_config_json", "notification_policy", "experiments_json",
        "share_template_json",
        "proactive_message_json", "image_gen_config_json", "video_gen_config_json",
        "multi_agent_config_json", "rag_config_json", "chat_drafts_json",
        "task_routing_config_json", "model_profiles_json",
        "account_user_name", "account_login_method",
        "multi_agent_review_model",
        // v2.6.7 fix (AUDIT F9): Preferencias de usuario (“Nunca más…”Las duras limitaciones de las precipitaciones) son activos de los usuarios,
        // Si no realiza una copia de seguridad, perderá todo después de cambiar de máquina. Esta clave no contiene nombres de campos confidenciales.
        "user_preferences",
        // P0-2: media_config_json Eliminado de la instantánea —— MediaConfig Contiene credenciales confidenciales ttsApiKey;
        // Incluso si ttsApiKey ha sido cifrado por SecureKeyStore,Las claves de vinculación de dispositivos tampoco se pueden descifrar entre dispositivos,
        // La exportación puede contener texto cifrado inútil y dar lugar a un uso indebido,Por lo tanto, toda la(Misma política que otras configuraciones clave).
    )

    val safeBooleanKeys = listOf(
        "memory_enabled", "dynamic_color", "onboarding_shown",
        "asr_tip_shown", "sticker_enabled", "experience_enabled",
        "keep_awake", "auto_launch",
        "account_logged_in", "account_guest_mode",
        "pii_guard_enabled",
        "multi_agent_llm_review_enabled",
    )

    val safeIntKeys = listOf(
        "default_home_page",
        "sticker_send_probability",
    )

    val safeLongKeys = listOf(
        "account_login_at",
    )

    /**
     * v2.6.9 fix (AUDIT §11.3-7): stringSet Lista blanca de respaldo de configuraciones de tipo.
     *
     * La instantánea anterior solo atravesó las cuatro categorías de cadena/booleano/int/long,stringSet Ninguno exportado,
     * Entonces estos dos usuarios"Colección"Nunca puedo acceder a la copia de seguridad —— Pierde tan pronto como cambies la máquina.,Y no hay ningún aviso.
     *
     * Los criterios de inclusión son consistentes con otros tipos.:El contenido es un activo del usuario.,Sin credenciales,Legible en todos los dispositivos.
     *  - moment_favorite_ids: Dinámica de cobros en Moments id(UUID);
     *  - mini_album_favorite_image_ids: Imágenes de la colección de álbumes de fotos. id(uri hashCode cadena decimal).
     *
     * Para los conjuntos de cadenas que se excluyen deliberadamente, consulte las notas de campo respectivas.(daily_summary_* Es el estado de deduplicación de programación.,
     * miniphone_hidden_apps / mini_album_hidden_image_ids si"ocultar"Vista lateral del estado de este tipo de equipos.).
     */
    val safeStringSetKeys = listOf(
        "moment_favorite_ids",
        "mini_album_favorite_image_ids",
    )

    /**
     * stringSet El prefijo clave en el mapa de instantáneas.
     *
     * El valor debe codificarse como String(La instantánea es Map<String, String>),Retener con matriz JSON
     * "colección vacía"con"No existe tal clave"la diferencia —— La concatenación de comas comprimirá el conjunto vacío en una cadena vacía,
     * Al restaurar, no está claro si escribir el conjunto vacío o conservar el valor original.
     */
    const val STRING_SET_PREFIX = "stringSet:"

    /** v2.6.0: Contiene lista blanca de claves —— Solo se fusionó en la exportación si el usuario marcó explícitamente \"Incluir clave de proveedor\".
     *  providers_json Contiene varios proveedores. apiKey / oauth token / customHeaders(puede incluir ID de cuenta), etc.
     *  Nota: Este es un certificado de texto claro que se puede utilizar directamente. El archivo de copia de seguridad debe conservarse correctamente. */
    val secretStringKeys = listOf(
        "providers_json",
    )

    /** Fragmento de nombre de clave confidencial:Incluso si lo agrega a la lista blanca por error, se le negará la entrada a la copia de seguridad. */
    private val sensitiveFragments = listOf(
        "api_key", "apikey", "token", "password", "secret", "jwt",
        "web_server", "mcp", "cloud_backup", "webdav", "s3", "asr_config", "pin",
    )

    /** El prefijo de tipo en el mapa de instantánea.string Tipo sin prefijo(formato histórico,Compatible con copia de seguridad de stock). */
    private val typePrefixes = listOf("bool:", "int:", "long:", STRING_SET_PREFIX)

    /**
     * Solo es seguro si incluye explícitamente las claves en la lista blanca y no accede a fragmentos confidenciales.
     *
     * el juicio es**Cubo por tipo**de:`bool:` Las claves prefijadas sólo estarán en [safeBooleanKeys] Encuentra aquí,
     * `stringSet:` solo en [safeStringSetKeys] Mira adentro. Si solo miras el nombre de la clave y no el tipo,
     * Una copia de seguridad manipulada funcionará `stringSet:theme_mode` Esta combinación va al DataStore.
     * cosas uno“otro tipo con el mismo nombre”,Aunque no afecta la lectura(DataStore La clave contiene tipo),
     * Pero provocará entradas en el producto de copia de seguridad que nunca deberían existir.
     */
    fun isSafeKey(rawKey: String): Boolean = isSafe(rawKey, allowSecretStringKeys = false)

    private fun isSafe(rawKey: String, allowSecretStringKeys: Boolean): Boolean {
        val prefix = typePrefixes.firstOrNull { rawKey.startsWith(it) } ?: ""
        val name = rawKey.removePrefix(prefix)
        // Cuando el usuario marca explícitamente "Incluir clave", se permite directamente proveedores_json. —— es conocido por el usuario
        // elegir,Ya no debería estar bloqueado por reglas de fragmentos confidenciales.
        val explicitlyAllowedSecret =
            allowSecretStringKeys && prefix.isEmpty() && name in secretStringKeys
        val whitelisted = when (prefix) {
            "bool:" -> name in safeBooleanKeys
            "int:" -> name in safeIntKeys
            "long:" -> name in safeLongKeys
            STRING_SET_PREFIX -> name in safeStringSetKeys
            else -> name in safeStringKeys
        }
        return name.isNotEmpty() &&
            (explicitlyAllowedSecret || (whitelisted && !isSensitiveName(name)))
    }

    private fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return sensitiveFragments.any { lower.contains(it) }
    }

    /**
     * v2.6.0: Contiene filtrado lateral de exportación clave —— Lista blanca básica + secretStringKeys(providers_json).
     * con [isSafeKey] Diferente:providers_json IsSafeKey rechazará los fragmentos sensibles (incluido apikey).
     * Sin embargo, debe permitirse después de que el usuario lo verifique explícitamente, por lo que aquí se juzga por separado.
     */
    fun isSafeKeyWithSecrets(rawKey: String): Boolean = isSafe(rawKey, allowSecretStringKeys = true)

    /** Conserve sólo las llaves de seguridad;Para probar y exportar lógica para compartir la misma semántica de filtrado. */
    fun sanitize(raw: Map<String, String>): Map<String, String> = raw.filterKeys(::isSafeKey)

    /** v2.6.0: Contiene filtrado lateral de recuperación de claves —— Permitir reescritura de proveedores_json. */
    fun sanitizeWithSecrets(raw: Map<String, String>): Map<String, String> = raw.filterKeys(::isSafeKeyWithSecrets)
}
