package com.rskusum.whocaller.core.common.result

/** Failure kinds the UI knows how to explain. Never carries user data. */
enum class AppError {
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    NOT_FOUND,
    INVALID_NUMBER,
    UNAUTHORIZED,
    RATE_LIMITED,
    DUPLICATE,
    SERVER,
    BACKEND_NOT_CONFIGURED,
    PERMISSION_DENIED,
    STORAGE,
    UNKNOWN,
}

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError, val cause: Throwable? = null) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

/** Runs [block], turning any exception except cancellation into [AppResult.Failure]. */
suspend inline fun <T> resultOf(
    crossinline errorMapper: (Throwable) -> AppError = { AppError.UNKNOWN },
    crossinline block: suspend () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(errorMapper(e), e)
}
