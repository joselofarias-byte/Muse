package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * P1-6: OAuth Configuración de autenticación.
 *
 * para apoyo OAuth Device Flow(RFC 8628)o Authorization Code Flow(PKCE)de
 * proveedor(Como xAI,OpenAI Codex espera),Inicie sesión a través de OAuth en lugar de completar la clave apiKey manualmente.
 *
 * - [deviceCodeUrl] Cuando no esté vacío, vaya primero a Device Flow:POST. device_code + user_code,
 *   Visualización de ventana emergente user_code,sondeo [tokenUrl] Hasta que obtengas el access_token.
 * - [deviceCodeUrl] Si está vacío, vaya a Flujo del código de autorización: abrir con el navegador del sistema
 *   [authorizeUrl],Pase Deep Link(`io.zer0.muse://oauth/callback`)recibir code,
 *   Reemplace el código con access_token.
 *
 * @param authorizeUrl Authorization Code Flow página de autorización URL
 * @param tokenUrl Punto final del token común a ambos flujos(Cambiar access_token)
 * @param clientId OAuth cliente de aplicación ID(Requerido,Aplicar desde la consola del proveedor)
 * @param clientSecret OAuth Aplicar secreto de cliente(Confidential Client solo necesito,
 *   El cliente público celular debe dejarse en blanco,Usar en su lugar PKCE)
 * @param redirectUri Authorization Code Flow devolución de llamada URI,
 *   Predeterminado `io.zer0.muse://oauth/callback`(Registrado en AndroidManifest)
 * @param scope Alcance de la autoridad solicitada(Como por ejemplo `chat.read chat.write`),Vacío significa usar el proveedor predeterminado
 * @param deviceCodeUrl Device Flow El punto final de código_dispositivo;nulo significa no soportado Device Flow
 */
@Serializable
data class OAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = "io.zer0.muse://oauth/callback",
    /**
     * v2.6.0: Codex Interruptor de proceso de código de equipo especial.
     *
     * true Realice el proceso de autorización de dispositivo exclusivo de OpenAI Codex(oficial codex CLI `--device-auth` Mismo estilo):
     *  - POST {base}/api/accounts/deviceauth/usercode (JSON, body {client_id})
     *  - sondeo POST {base}/api/accounts/deviceauth/token (JSON, body {device_auth_id, user_code})
     *  - POST {base}/oauth/token Reemplazar con el código_autorización + verificador_código devuelto token
     *    (redirect_uri fijo a https://auth.openai.com/deviceauth/callback)
     * No es necesario realizar una devolución de llamada de localhost durante todo el proceso. —— Evite que el agente de usuario trague la dirección de bucle invertido en el túnel y provoque que se agote el tiempo de espera de la conexión.
     */
    val codexDeviceAuth: Boolean = false,
    val scope: String = "",
    val deviceCodeUrl: String? = null,
) {
    companion object {
        /**
         * xAI OAuth Predeterminado.
         *
         * v2.6.0(Comentarios de los usuarios: el inicio de sesión con un clic no está disponible): funcionario de prensa/Solución implementada por la comunidad.
         *
         * El punto final de implementación original y el clientId son marcadores de posición.(x.ai/oauth,clientId vacío) —— Un ID de cliente vacío hará
         * Device Flow juicio directo“No disponible”. Inicio de sesión de suscripción xAI real:
         *  - Servidor de autorización: `https://auth.x.ai`(no x.ai), admite descubrimiento OIDC
         *  - device code: `https://auth.x.ai/oauth2/device/code`(RFC 8628)
         *  - token:      `https://auth.x.ai/oauth2/token`
         *  - clientId:    Grok CLI Cliente público de flujo de dispositivos (mismo origen que oh-my-pi/hermes-agent, no confidencial)
         *  - scope:       `openid profile email offline_access grok-cli:access`
         */
        val XAI_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.x.ai/oauth2/authorize",
            tokenUrl = "https://auth.x.ai/oauth2/token",
            clientId = "b1a00492-073a-47ea-816f-4c329264a828",
            scope = "openid profile email offline_access grok-cli:access",
            deviceCodeUrl = "https://auth.x.ai/oauth2/device/code",
        )

        /**
         * v1.0.6: xAI OAuth Responses Predeterminado.
         *
         * con [XAI_PRESET] diferencia:Este valor predeterminado utiliza el protocolo OpenAI Responses.(openai-responses),
         * baseUrl Apunte al punto final del proxy xAI OAuth https://cli-chat-proxy.grok.com,
         * Una vez autorizado el usuario, obtiene el token de acceso y llama al punto final /v1/responses para acceder al modelo Grok 4.5+.
         *
         * v2.6.0: Corrección de sincronización con el punto final auth.x.ai + client_id público (igual que [XAI_PRESET]).
         */
        val XAI_OAUTH_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.x.ai/oauth2/authorize",
            tokenUrl = "https://auth.x.ai/oauth2/token",
            clientId = "b1a00492-073a-47ea-816f-4c329264a828",
            scope = "openid profile email offline_access grok-cli:access",
            deviceCodeUrl = "https://auth.x.ai/oauth2/device/code",
        )

        /**
         * v2.x(comentarios de los usuarios): OpenAI Codex OAuth Predeterminado —— Modificado según el mismo acuerdo que Codex CLI.
         *
         * ChatGPT Inicie sesión con una cuenta de suscripción (no utilice facturación API, utilice cuota de suscripción):
         *  - proceso: Authorization Code Flow + PKCE(S256), el navegador volverá al bucle invertido después de abrir la página de autorización;
         *  - authorizeUrl: `https://auth.openai.com/oauth/authorize`;
         *  - tokenUrl:     `https://auth.openai.com/oauth/token`(code Compartido con reemplazo y actualización de token);
         *  - clientId:     Codex CLI client_id público (valor común de implementación comunitaria);
         *  - scope:        `openid profile email offline_access`(offline_access enviará refresco_token);
         *  - punto final de inferencia:     `https://chatgpt.com/backend-api/codex/responses`(ver PresetProviders.openaiCodex);
         *  - Encabezado de solicitud:       Además del portador, también necesitas `ChatGPT-Account-ID`(analizado de id_token JWT).
         */
        val OPENAI_CODEX_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.openai.com/oauth/authorize",
            tokenUrl = "https://auth.openai.com/oauth/token",
            clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
            scope = "openid profile email offline_access",
            redirectUri = "http://localhost:1455/auth/callback",
            // v2.6.0: Proceso de código de dispositivo específico de Mark Codex —— Codex Sin estándares RFC 8628:
            // Punto final del código del dispositivo /api/accounts/deviceauth/*(JSON + dos etapas),
            // token intercambio de redirección_uri arreglado https://auth.openai.com/deviceauth/callback.
            // No se requiere devolución de llamada de localhost para seguir esta ruta,evitar completamente“Bucle invertido del agente→tiempo de espera”pregunta.
            codexDeviceAuth = true,
        )

        /**
         * v1.134 P2-1: GitHub Copilot OAuth Predeterminado(Device Flow).
         *
         * GitHub oficial OAuth Device Flow(RFC 8628),Después de que el usuario ingresa el código de usuario en el navegador para obtener autorización,
         * Llamado con access_token Copilot API(https://api.githubcopilot.com):
         *  - authorizeUrl: `https://github.com/login/oauth/authorize`(Authorization Code Flow Repuesto)
         *  - tokenUrl:     `https://github.com/login/oauth/access_token`(Cambiar token)
         *  - deviceCodeUrl:`https://github.com/login/device/code`(Device Flow camino principal)
         *  - clientId:     GitHub Copilot cliente público de OAuth (VS Code / Copilot CLI y
         *                  Una gran cantidad de herramientas de terceros son comunes y no confidenciales;v2.6.0 Corrección: el valor original estaba vacío, lo que hacía que no estuviera disponible directamente)
         *  - scope:        `read:user`
         *
         * Al llamar a Copilot, trate access_token como apiKey,BASE_URL señalar a
         * `https://api.githubcopilot.com`,Copilot La puerta de enlace funcionará como proxy para modelos como OpenAI/GPT-4o.
         */
        val GITHUB_COPILOT_PRESET = OAuthConfig(
            authorizeUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            clientId = "Iv1.b507a08c87ecfe98",
            scope = "read:user",
            deviceCodeUrl = "https://github.com/login/device/code",
        )
    }
}
