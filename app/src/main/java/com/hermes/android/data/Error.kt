package com.hermes.android.data

sealed class AppError(override val message: String, override val cause: Throwable? = null) : Exception(message, cause) {
    data class Network(override val cause: Throwable) : AppError("Network error", cause)
    data class Matrix(val errorCode: String, override val message: String) : AppError(message)
    data class Crypto(override val message: String) : AppError(message)
    data class Unknown(override val cause: Throwable) : AppError("Unknown error", cause)
}

suspend fun <T> safeCall(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(AppError.Unknown(e))
    }
}
