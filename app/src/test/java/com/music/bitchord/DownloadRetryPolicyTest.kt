package com.music.bitchord

import com.music.bitchord.download.DownloadRetryPolicy
import com.music.bitchord.download.RetryableDownloadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DownloadRetryPolicyTest {

    @Test
    fun `retry is limited to explicitly classified transport failures`() {
        assertTrue(DownloadRetryPolicy.shouldRetry(RetryableDownloadException("HTTP 503", statusCode = 503)))
        assertTrue(DownloadRetryPolicy.shouldRetry(RetryableDownloadException("timeout")))
        assertFalse(DownloadRetryPolicy.shouldRetry(IOException("HTTP 404")))
        assertFalse(DownloadRetryPolicy.shouldRetry(IllegalArgumentException("unsupported format")))
    }

    @Test
    fun `retry count is bounded and exponential delay is predictable`() {
        assertEquals(3, DownloadRetryPolicy.MAX_ATTEMPTS)
        val timeout = RetryableDownloadException("read timed out")
        assertEquals(500L, DownloadRetryPolicy.delayMillis(timeout, retryIndex = 0))
        assertEquals(1_000L, DownloadRetryPolicy.delayMillis(timeout, retryIndex = 1))
        assertEquals(5_000L, DownloadRetryPolicy.delayMillis(timeout, retryIndex = 10))
    }

    @Test
    fun `Retry-After is honored but capped`() {
        assertEquals(
            2_000L,
            DownloadRetryPolicy.delayMillis(
                RetryableDownloadException("HTTP 429", statusCode = 429, retryAfterMillis = 2_000),
                retryIndex = 0,
            ),
        )
        assertEquals(
            5_000L,
            DownloadRetryPolicy.delayMillis(
                RetryableDownloadException("HTTP 503", statusCode = 503, retryAfterMillis = 60_000),
                retryIndex = 0,
            ),
        )
    }

    @Test
    fun `only an HTTP2 reset requests the HTTP1 fallback`() {
        assertTrue(
            DownloadRetryPolicy.useHttp1Only(
                RetryableDownloadException("stream was reset: INTERNAL_ERROR", useHttp1Only = true),
            ),
        )
        assertFalse(
            DownloadRetryPolicy.useHttp1Only(
                RetryableDownloadException("HTTP 503", statusCode = 503),
            ),
        )
    }

    @Test
    fun `wrapped transport failures remain retryable`() {
        val reset = RetryableDownloadException(
            "stream was reset: INTERNAL_ERROR",
            useHttp1Only = true,
        )
        val wrapped = IOException("transfer failed", reset)

        assertTrue(DownloadRetryPolicy.shouldRetry(wrapped))
        assertTrue(DownloadRetryPolicy.useHttp1Only(wrapped))
    }
}
