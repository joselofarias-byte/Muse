package io.zer0.common

/**
 * Envoltorio de resultados genérico,Se utiliza para reemplazar la propagación de errores del estilo de lanzamiento de excepciones.
 *
 * Success llevar datos,Error Llevar mensajes legibles y excepciones opcionales.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(
        val message: String,
        val throwable: Throwable? = null,
    ) : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw throwable ?: IllegalStateException(message)
    }

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(data)
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (String, Throwable?) -> Unit): Result<T> {
        if (this is Error) action(message, throwable)
        return this
    }
}

/** Envolver bloques que pueden generar excepciones [Result]. */
inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.Success(block())
} catch (t: Throwable) {
    if (t is kotlin.coroutines.cancellation.CancellationException) throw t
    Result.Error(t.message ?: "Unknown error", t)
}
