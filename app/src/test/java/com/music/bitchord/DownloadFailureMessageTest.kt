package com.music.bitchord

import com.music.bitchord.download.downloadFailureMessage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureMessageTest {

    @Test
    fun `io failures retain their transfer cause instead of blaming the connection`() {
        assertEquals(
            "Download transfer failed: No space left on device",
            downloadFailureMessage(IOException("No space left on device")),
        )
    }

    @Test
    fun `io failures without a detail still identify an io transfer failure`() {
        assertEquals(
            "Download transfer failed (I/O error)",
            downloadFailureMessage(IOException()),
        )
    }

    @Test
    fun `explicit preparation errors keep their actionable message`() {
        assertEquals(
            "Unsupported MIME type audio/webm",
            downloadFailureMessage(IllegalArgumentException("Unsupported MIME type audio/webm")),
        )
    }
}
