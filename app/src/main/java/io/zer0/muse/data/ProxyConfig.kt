package io.zer0.muse.data

import kotlinx.serialization.Serializable

/**
 * Configuración del proxy de red global.
 *
 * Después de configurarlo en la página de configuración, se sincroniza con io.zer0.ai.core.ProxyProvider y cada OkHttpClient pasa la dinámica
 * ProxySelector Léelo.
 * v2.6.9 Cambio de comportamiento: después de cambiar de agente**Las nuevas conexiones entran en vigor inmediatamente**, no es necesario reiniciar;
 * Sólo se utilizarán conexiones largas de mantenimiento de vida establecidas hasta que se reciclen.
 *
 * M-PRX1: password Los campos se refieren a credenciales confidenciales.,debería ir [SecureKeyStore] Cifrar y luego persistir,
 * El pedido no debe realizarse explícitamente. Esta clase de datos solo lleva definiciones de campos.,Pase de cifrado y descifrado [encrypted] / [decrypted]
 * Métodos de miembros en el límite de serialización(SettingsRepository.saveProxyConfig / proxyConfigFlow)
 * Completo:Antes de escribir en DataStore `encrypted()`,despues de leer `decrypted()`.
 *
 * SettingsRepository por etapa1Reparación unificada,Esta categoría solo proporciona entradas e instrucciones de cifrado y descifrado.
 */
@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    // HTTP o SOCKS5
    val type: String = "HTTP",
    val host: String = "",
    val port: Int = 7890,
    val username: String = "",
    val password: String = "",
    /**
     * v2.6.9: Lista de hosts sin proxy (separados por comas, admite sufijo de nombre de dominio e IP).
     *
     * Punto débil real: los usuarios nacionales a menudo habilitan agentes globales, pero los modelos locales (localhost:11434)
     * Si también utiliza un proxy para servicios de intranet, fallará directamente. Esta columna es la apertura para ellos.
     * Ejemplo:`localhost,127.0.0.1,192.168.,*.local`
     */
    val bypassHosts: String = "",
) {
    /**
     * M-PRX1: La contraseña de retorno está cifrada(ir [SecureKeyStore.encrypt])copia de,
     * Por llamar antes que la perseverancia. Deje la contraseña vacía como está(No cifrar valores nulos).
     */
    suspend fun encrypted(): ProxyConfig = copy(password = SecureKeyStore.encrypt(password))

    /**
     * M-PRX1: Contraseña de retorno descifrada(ir [SecureKeyStore.decrypt])copia de,
     * Se llama después de leer desde la capa de persistencia. La antigua contraseña en texto plano se transmite de forma transparente mediante descifrado(compatibles).
     */
    suspend fun decrypted(): ProxyConfig = copy(password = SecureKeyStore.decrypt(password))

    /**
     * v2.6.9: Convierta a la descripción del agente del módulo ai.
     *
     * Por qué es necesaria la conversión:`ai` Los módulos no pueden depender de `app`(de lo contrario dependencia circular),
     * entonces `io.zer0.ai.core.ProxyProvider` Aloje el proxy con su propia estructura de datos pura.
     * La conversión de límites se realiza aquí para evitar mantener una definición de campo en ambos lados.
     */
    fun toProxyDescriptor(): io.zer0.ai.core.ProxyProvider.Descriptor = io.zer0.ai.core.ProxyProvider.Descriptor(
        enabled = enabled,
        type = type,
        host = host,
        port = port,
        username = username,
        password = password,
    )
}
