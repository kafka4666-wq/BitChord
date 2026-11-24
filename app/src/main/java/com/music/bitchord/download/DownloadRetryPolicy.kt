package com.music.bitchord.download

import java.io.IOException

/** A transport failure that is safe to retry from byte zero in a new pending file. */
internal class RetryableDownloadException(
    message: String,
    val statusCode: Int? = null,
    val retryAfterMillis: Long? = null,
    /** True only for an HTTP/2 stream reset, which merits a request-scoped HTTP/1.1 retry. */
    val useHttp1Only: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The policy is deliberately small: bounded retries, and no silent quality downgrade. */
internal object DownloadRetryPolicy {
    const val MAX_ATTEMPTS = 3
    private const val BASE_BACKOFF_MILLIS = 500L
    private const val MAX_BACKOFF_MILLIS = 5_000L

    fun shouldRetry(error: Throwable): Boolean = retryableCause(error) != null

    fun useHttp1Only(error: Throwable): Boolean = retryableCause(error)?.useHttp1Only == true

    /** [retryIndex] is zero before the first retry, one before the second. */
    fun delayMillis(error: Throwable, retryIndex: Int): Long {
        val serverDelay = retryableCause(error)?.retryAfterMillis
            ?.takeIf { it > 0 }
        if (serverDelay != null) return serverDelay.coerceAtMost(MAX_BACKOFF_MILLIS)
        val exponent = retryIndex.coerceIn(0, 4)
        return (BASE_BACKOFF_MILLIS shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private fun retryableCause(error: Throwable): RetryableDownloadException? {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is RetryableDownloadException) return current
            current = current.cause
        }
        return null
    }
}
