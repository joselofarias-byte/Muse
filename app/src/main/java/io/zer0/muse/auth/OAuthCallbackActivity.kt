package io.zer0.muse.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.zer0.common.Logger

/**
 * P1-6: OAuth devolución de llamada vacía Activity — solo para recibir `io.zer0.muse://oauth/callback` Deep Link.
 *
 * Flujo de trabajo:
 *  1. Después de que el navegador complete la autorización del flujo del código de autorización,saltar a redirectUri
 *     (`io.zer0.muse://oauth/callback?code=xxx&state=yyy`)
 *  2. El sistema coincide con esta actividad. intent-filter,Empieza esto Activity
 *  3. [onCreate] / [onNewIntent] Analizar en consulta code + state,
 *     llamar [OAuthManager.completeAuthorizationCodeFlow] Completo Flow
 *  4. inmediatamente [finish],Lo que ve el usuario es el regreso a la página después de cerrar el navegador. ProviderEditPage
 *
 * Nota:
 *  - Esta Actividad Ninguna UI(setTheme Transparente),Sólo reenvío
 *  - exported="true" Requerido para enlace profundo(Navegador → Salto de aplicación)
 *  - state Verificación por [OAuthManager.completeAuthorizationCodeFlow] acabado interno(CSRF protección)
 */
class OAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    /** Analizar desde la intención code + state,adelante a [OAuthManager]. */
    private fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: run {
            Logger.w(TAG, "OAuthCallbackActivity recibido vacío data,ignorar")
            return
        }
        val code = data.getQueryParameter("code").orEmpty()
        val state = data.getQueryParameter("state").orEmpty()
        // Priorizar los parámetros de error(Autorización denegada al usuario/error del servidor)
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val desc = data.getQueryParameter("error_description").orEmpty()
            Logger.w(TAG, "OAuth error de devolución de llamada: error=$error, desc=$desc")
            // Establezca directamente OAuthManager en estado ERROR,Hacer pendiente el error de devolución de launchAuthorizationCodeFlow
            OAuthManager.cancel()
            return
        }
        if (code.isBlank() || state.isBlank()) {
            Logger.w(TAG, "OAuth Devolución de llamada corta code/state,ignorar: $data")
            return
        }
        OAuthManager.completeAuthorizationCodeFlow(code, state)
    }

    private companion object {
        private const val TAG = "OAuthCallbackActivity"
    }
}
