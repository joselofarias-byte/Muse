package io.zer0.muse.ui

import android.content.Context
import io.zer0.common.ErrorMessage
import io.zer0.muse.R

/**
 * ErrorMessage → Capa de adaptación para cadenas localizadas visibles para el usuario.
 *
 * Responsabilidades:
 *  - en el módulo de la aplicación(common El módulo no tiene dependencias de Android)puente [ErrorMessage] con [R.string]
 *  - Soporta errores con parámetros.(Como por ejemplo [ErrorMessage.ApiError.RATE_LIMIT] de retryAfter)
 *  - Volver a la configuración regional predeterminada si falta [ErrorMessage.defaultMessage](ingles para decir la verdad)
 *
 * llamador:
 *  - ViewModel / Activity / Fragment mantener [Context] llamado cuando [toLocalizedString]
 *  - No retenido [Context] Clase pura de Kotlin(Como módulo ai/memoria)solo salida [ErrorMessage],
 *    Esta extensión es llamada por el ViewModel superior en la rama catch/Result.Error
 *
 * principios de diseño:
 *  - función de mapeo único,uso exhaustive when(Kotlin El compilador fuerza la cobertura de todos [ErrorMessage] Subclase)
 *  - Nuevo [ErrorMessage] Después de la subclasificación, debe agregar aquí la rama when correspondiente.,De lo contrario, la compilación falla(Sincronización de fuerza)
 *  - Convención de nomenclatura de recursos de cadenas:`error_<code>`(con [ErrorMessage.code] correspondencia uno a uno)
 */
fun ErrorMessage.toLocalizedString(context: Context): String = when (this) {
    // ── error de red ──
    ErrorMessage.NetworkError.TIMEOUT ->
        context.getString(R.string.error_network_timeout)
    ErrorMessage.NetworkError.NO_CONNECTION ->
        context.getString(R.string.error_network_no_connection)
    ErrorMessage.NetworkError.DNS_FAILED ->
        context.getString(R.string.error_network_dns_failed)

    // ── Error de autenticación ──
    ErrorMessage.AuthError.INVALID_KEY ->
        context.getString(R.string.error_auth_invalid_key)
    ErrorMessage.AuthError.EXPIRED ->
        context.getString(R.string.error_auth_expired)
    ErrorMessage.AuthError.UNAUTHORIZED ->
        context.getString(R.string.error_auth_unauthorized)

    // ── API error de servicio ──
    is ErrorMessage.ApiError.RATE_LIMIT -> {
        // Con retryAfter, formatéelo como"Inténtalo de nuevo en N segundos.",De lo contrario, utilice la variante sin argumentos.
        val seconds = this.retryAfter
        if (seconds != null && seconds > 0) {
            context.getString(R.string.error_api_rate_limit, seconds)
        } else {
            context.getString(R.string.error_api_rate_limit_nodelay)
        }
    }
    ErrorMessage.ApiError.SERVER_ERROR ->
        context.getString(R.string.error_api_server_error)
    ErrorMessage.ApiError.MODEL_NOT_FOUND ->
        context.getString(R.string.error_api_model_not_found)
    ErrorMessage.ApiError.CONTEXT_LENGTH ->
        context.getString(R.string.error_api_context_length)

    // ── Error de almacenamiento ──
    ErrorMessage.StorageError.DB_FULL ->
        context.getString(R.string.error_storage_db_full)
    ErrorMessage.StorageError.IO_ERROR ->
        context.getString(R.string.error_storage_io_error)
    ErrorMessage.StorageError.MIGRATION_FAILED ->
        context.getString(R.string.error_storage_migration_failed)

    // ── error de verificación ──
    ErrorMessage.ValidationError.EMPTY_INPUT ->
        context.getString(R.string.error_validation_empty_input)
    ErrorMessage.ValidationError.INVALID_FORMAT ->
        context.getString(R.string.error_validation_invalid_format)

    // ── Bolsillo universal ──
    ErrorMessage.GenericError.UNKNOWN ->
        context.getString(R.string.error_generic_unknown)
}
