package com.music.bitchord.data.stats

import com.music.bitchord.data.history.ImportedListen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketMathImportedHistoryTest {
    private fun event(eventId: String, batchId: String = "batch") = ImportedListen(
        eventId = eventId,
        trackId = "history:track",
        source = "csv",
        sourceAccount = "history.csv",
        batchId = batchId,
        timestampMillis = 1_700_000_000_000L,
        timeZoneId = "UTC",
        title = "Song",
        artist = "Artist",
    )

    @Test
    fun plusUnionsImportedEventsByStableEventIdentity() {
        val shared = event("event-1", "older-batch")
        val fromA = StoredBucket(month = "2023-11", importedEvents = listOf(shared))
        val fromB = StoredBucket(
            month = "2023-11",
            importedEvents = listOf(shared.copy(batchId = "duplicate-copy"), event("event-2", "new-batch")),
        )

        val combined = BucketMath.plus(fromA, fromB)

        assertEquals(listOf("event-1", "event-2"), combined.importedEvents.map { it.eventId })
        assertEquals("older-batch", combined.importedEvents.first().batchId)
    }

    @Test
    fun minusReturnsOnlyImportedEventsMissingFromTheBaseBucket() {
        val old = event("event-1")
        val added = event("event-2", "new-batch")
        val current = StoredBucket(month = "2023-11", importedEvents = listOf(old, added))
        val base = StoredBucket(month = "2023-11", importedEvents = listOf(old))

        assertEquals(listOf(added), BucketMath.minus(current, base).importedEvents)
    }

    @Test
    fun importedOnlyBucketIsNotTreatedAsEmpty() {
        assertFalse(BucketMath.isEmpty(StoredBucket(month = "2023-11", importedEvents = listOf(event("event-1")))))
        assertTrue(BucketMath.isEmpty(StoredBucket(month = "2023-11")))
    }
}
