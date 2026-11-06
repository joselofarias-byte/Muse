package io.zer0.common

/**
 * Enumeración de códigos de error entre módulos(Sin dependencia del contexto de Android)
 *
 * ============================================================================
 * i18n Guía de migración(Debe leer)
 * ============================================================================
 *
 * fondo:
 *   v1.90 en ai / memory / app En total quedaron libres unas 800 plazas en las tres plantas. `// TODO i18n` marca,
 *   La causa principal es que ai/memoria es un módulo Gradle independiente,ViewModel Ninguno context,no se puede citar directamente
 *   app de R.string,Lo que da como resultado que los mensajes chinos codificados estén dispersos por todas partes.
 *
 * La función de este documento.:
 *   proporcionar"código de error → UI cuerda"Esqueleto desacoplado. Todos los errores entre módulos se expresan uniformemente como
 *   [ErrorMessage] Subclase,Sólo llevar estable [code] + ingles para decir la verdad [defaultMessage] +
 *   Opcional [metadata],No hace referencia a ningún recurso de Android/R.
 *
 * Arquitectura de tres niveles:
 *   1. data / ai / memory capa:tirar o regresar [ErrorMessage],No se construye ninguna copia de usuario.
 *   2. app/ui capa:llamar [io.zer0.muse.ui.ErrorMessageMapper.toLocalizedString]
 *      voluntad [ErrorMessage] Una cadena traducida a la configuración regional actual.(por R.string)
 *   3. ViewModel:atrapar [ErrorMessage] despues de pasar `appContext.getString(R.string.xxx)`
 *      rendir a UI,No es necesario crear cadenas codificadas
 *
 * Pasos de migración(para cada marca `// TODO i18n` ubicación):
 *   a. Identifique qué tipo de mensaje de error chino codificado existente es(Red/Autenticación/API/Almacenamiento/Verificación/General)
 *   b. Reemplazar con correspondiente [ErrorMessage] Subclase(Como por ejemplo `ErrorMessage.NetworkError.TIMEOUT`)
 *   c. en app/src/main/res/values/strings_errors.xml(y values-en / values-ja
 *      Espéralo ya locale)El complemento del medio corresponde a la cadena error_xxx
 *   d. en [ErrorMessageMapper] entrada de mapeo del complemento medio
 *   e. Eliminar el código fuente `// TODO i18n` marca
 *
 * Cosas a tener en cuenta:
 *   - [code] Una vez publicado no se puede cambiar.(UI / Se hará referencia a registros/puntos enterrados/depuración conjunta del lado del servidor)
 *   - [defaultMessage] debe estar en ingles(Utilizado como garantía de calidad.,Evite mostrar espacios en blanco cuando falte la configuración regional)
 *   - Error con argumentos variados.(Como RATE_LIMIT retryAfter)Utilice la clase de datos en lugar de object
 *   - con [io.zer0.common.ErrorCode] Las enumeraciones antiguas coexisten:Se da prioridad a los nuevos códigos [ErrorMessage],
 *     La enumeración anterior se descartará después de que se borren todos los TODO.(Ver detalles [ErrorCode] Comentarios principales)
 * ============================================================================
 */
sealed class ErrorMessage {

    /** código de error estable(inmutable,Se utiliza como clave de registro/enterrada/mapeo de UI). */
    abstract val code: String

    /** primicia inglesa(locale Se utiliza cuando faltan recursos.). */
    abstract val defaultMessage: String

    /** metadatos opcionales(Número de reintentosDespués de segundos como RATE_LIMIT,No participa en la unicidad del código.). */
    open val metadata: Map<String, String> get() = emptyMap()

    /**
     * Representación de cadenas compatible con registros([code] + [defaultMessage] + [metadata]),
     * dar `Logger.w(TAG, errorMessage.toLogString(), t)` Usar en otros escenarios,
     * Todo en ingles,Sin dependencia local,Se puede llamar desde cualquier hilo/módulo.
     */
    fun toLogString(): String = buildString {
        append("[ERR:")
        append(code)
        append("] ")
        append(defaultMessage)
        if (metadata.isNotEmpty()) {
            append(" | ")
            metadata.forEach { (k, v) ->
                append(k)
                append('=')
                append(v)
                append(';')
            }
        }
    }

    // ── error de red ──────────────────────────────────────────────────────────

    /** error de capa de red:Tiempo de espera de conexión, desconexión,DNS Fallos y otros problemas de la capa de transporte. */
    sealed class NetworkError : ErrorMessage() {
        /** Solicitar tiempo de espera(Fase de lectura/escritura/conexión). */
        data object TIMEOUT : NetworkError() {
            override val code = "network_timeout"
            override val defaultMessage = "Network request timed out, please retry"
        }

        /** No hay conexión de red disponible(Modo avión/WiFi desconectado). */
        data object NO_CONNECTION : NetworkError() {
            override val code = "network_no_connection"
            override val defaultMessage = "No network connection available"
        }

        /** DNS El análisis falló(El nombre de dominio no se puede resolver para el destino IP). */
        data object DNS_FAILED : NetworkError() {
            override val code = "network_dns_failed"
            override val defaultMessage = "DNS resolution failed"
        }
    }

    // ── Error de autenticación ──────────────────────────────────────────────────────────

    /** Error de capa de autenticación:API Key / Token Permisos no válidos o insuficientes. */
    sealed class AuthError : ErrorMessage() {
        /** API Key No válido o mal formado. */
        data object INVALID_KEY : AuthError() {
            override val code = "auth_invalid_key"
            override val defaultMessage = "Invalid API key, please check model service settings"
        }

        /** Token / Key Venció. */
        data object EXPIRED : AuthError() {
            override val code = "auth_expired"
            override val defaultMessage = "Authentication token expired"
        }

        /** Autenticado pero no autorizado para acceder al recurso de destino(403). */
        data object UNAUTHORIZED : AuthError() {
            override val code = "auth_unauthorized"
            override val defaultMessage = "Not authorized to access this resource"
        }
    }

    // ── API error de servicio ─────────────────────────────────────────────────────

    /** Error de capa empresarial devuelto por el servicio API ascendente(HTTP 4xx/5xx Transmisión transparente o mapeo semántico). */
    sealed class ApiError : ErrorMessage() {
        /** límite de tasa de activación(429). llevar [retryAfter] Se recomienda esperar unos segundos antes de volver a intentarlo. */
        data class RATE_LIMIT(
            /** Número de segundos recomendado para esperar antes de volver a intentarlo(Si el río arriba no regresa, será null). */
            val retryAfter: Long? = null,
        ) : ApiError() {
            override val code = "api_rate_limit"
            override val defaultMessage = "Too many requests, please slow down"
            override val metadata: Map<String, String>
                get() = if (retryAfter != null) mapOf("retryAfter" to retryAfter.toString()) else emptyMap()
        }

        /** Error interno del servidor ascendente(5xx). */
        data object SERVER_ERROR : ApiError() {
            override val code = "api_server_error"
            override val defaultMessage = "Upstream service returned an error"
        }

        /** El modelo especificado no existe o no es compatible con el proveedor. */
        data object MODEL_NOT_FOUND : ApiError() {
            override val code = "api_model_not_found"
            override val defaultMessage = "Requested model is not available"
        }

        /** La entrada excede la longitud de contexto máxima del modelo. */
        data object CONTEXT_LENGTH : ApiError() {
            override val code = "api_context_length"
            override val defaultMessage = "Input exceeds the model context length limit"
        }
    }

    // ── Error de almacenamiento ──────────────────────────────────────────────────────────

    /** Error de capa de almacenamiento local:Error en la base de datos/archivo IO/migración. */
    sealed class StorageError : ErrorMessage() {
        /** La base de datos está llena(Espacio en disco insuficiente o cuota de base de datos agotada). */
        data object DB_FULL : StorageError() {
            override val code = "storage_db_full"
            override val defaultMessage = "Database is full"
        }

        /** Errores de E/S de archivos genéricos/DB(Fallos de lectura y escritura, ocupación de bloqueo de archivos, etc.). */
        data object IO_ERROR : StorageError() {
            override val code = "storage_io_error"
            override val defaultMessage = "Local storage read/write failed"
        }

        /** Error en la migración del esquema de la base de datos(Error SQL de actualización de versión). */
        data object MIGRATION_FAILED : StorageError() {
            override val code = "storage_migration_failed"
            override val defaultMessage = "Database migration failed"
        }
    }

    // ── error de verificación ──────────────────────────────────────────────────────────

    /** Error de validación de entrada:La entrada del usuario no cumple la condición previa. */
    sealed class ValidationError : ErrorMessage() {
        /** La entrada está vacía o contiene solo espacios en blanco. */
        data object EMPTY_INPUT : ValidationError() {
            override val code = "validation_empty_input"
            override val defaultMessage = "Input cannot be empty"
        }

        /** El formato de entrada es ilegal(El tipo de parámetro/longitud/patrón no coincide). */
        data object INVALID_FORMAT : ValidationError() {
            override val code = "validation_invalid_format"
            override val defaultMessage = "Input format is invalid"
        }
    }

    // ── Bolsillo universal ──────────────────────────────────────────────────────────

    /** Errores:Errores desconocidos que no se pueden clasificar en ninguna de las categorías anteriores. */
    sealed class GenericError : ErrorMessage() {
        data object UNKNOWN : GenericError() {
            override val code = "generic_unknown"
            override val defaultMessage = "An unknown error occurred"
        }
    }
}

/**
 * poner [Throwable] El mapeo heurístico es [ErrorMessage].
 *
 * Se utiliza para convertir temporalmente excepciones en [ErrorMessage],
 * Posteriormente, será reemplazado gradualmente por explícitos. [ErrorMessage] Construir. Clasificación general basada únicamente en tipos de excepción,
 * La precisión semántica no está garantizada——Para una definición precisa, regrese directamente a la sección específica. [ErrorMessage] subclase.
 */
fun Throwable.toErrorMessage(): ErrorMessage = when (this) {
    is java.net.UnknownHostException -> ErrorMessage.NetworkError.DNS_FAILED
    is java.net.SocketTimeoutException -> ErrorMessage.NetworkError.TIMEOUT
    is java.net.ConnectException -> ErrorMessage.NetworkError.NO_CONNECTION
    is android.database.sqlite.SQLiteFullException -> ErrorMessage.StorageError.DB_FULL
    is android.database.sqlite.SQLiteException -> ErrorMessage.StorageError.IO_ERROR
    is IllegalArgumentException -> ErrorMessage.ValidationError.INVALID_FORMAT
    else -> {
        val msg = message.orEmpty()
        when {
            msg.contains("timeout", ignoreCase = true) -> ErrorMessage.NetworkError.TIMEOUT
            msg.contains("unauthorized", ignoreCase = true) ||
                msg.contains("401", ignoreCase = true) -> ErrorMessage.AuthError.UNAUTHORIZED
            msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("429", ignoreCase = true) -> ErrorMessage.ApiError.RATE_LIMIT()
            msg.contains("context length", ignoreCase = true) -> ErrorMessage.ApiError.CONTEXT_LENGTH
            else -> ErrorMessage.GenericError.UNKNOWN
        }
    }
}
