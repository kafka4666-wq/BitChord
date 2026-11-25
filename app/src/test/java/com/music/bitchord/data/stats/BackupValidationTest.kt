package com.music.bitchord.data.stats

import com.music.bitchord.data.history.ImportedListen
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupValidationTest {

    @Test
    fun acceptsValidEmptyAndPopulatedBackups() {
        Backup.validateContents(
            Backup.BackupFile(
                settings = mapOf("enabled" to Backup.PrefValue("bool", "true")),
                listening = listOf(
                    StoredBucket(
                        month = "2025-02",
                        tracks = listOf(TrackEntry(id = "track-1", ms = 120_000, plays = 2)),
                        days = mapOf(28 to 120_000),
                    ),
                ),
            ),
        )
        Backup.validateContents(Backup.BackupFile())
    }

    @Test
    fun rejectsDuplicateMonths() {
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(listening = listOf(StoredBucket(month = "2025-01"), StoredBucket(month = "2025-01"))),
            )
        }
    }

    @Test
    fun rejectsMalformedMonthAndOutOfRangeDailyData() {
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(Backup.BackupFile(listening = listOf(StoredBucket(month = "2025-13"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(listening = listOf(StoredBucket(month = "2025-02", days = mapOf(29 to 1L)))),
            )
        }
    }

    @Test
    fun rejectsNegativeStatisticsAndMalformedKnownPreferenceTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(listening = listOf(StoredBucket(month = "2025-01", tracks = listOf(TrackEntry("t", ms = -1)))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(settings = mapOf("enabled" to Backup.PrefValue("bool", "perhaps"))),
            )
        }
    }

    @Test
    fun rejectsInvalidImportedProvenanceAndDurations() {
        val valid = ImportedListen(
            eventId = "event-1",
            trackId = "track-1",
            source = "lastfm",
            batchId = "batch-1",
            timestampMillis = 1_735_689_600_000,
            timeZoneId = "UTC",
            title = "Song",
            artist = "Artist",
        )
        Backup.validateContents(Backup.BackupFile(listening = listOf(StoredBucket("2025-01", importedEvents = listOf(valid)))))

        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(
                    listening = listOf(
                        StoredBucket("2025-01", importedEvents = listOf(valid, valid.copy(batchId = "batch-2"))),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(
                    listening = listOf(
                        StoredBucket("2025-01", importedEvents = listOf(valid.copy(timeZoneId = "Not/AZone"))),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.validateContents(
                Backup.BackupFile(
                    listening = listOf(
                        StoredBucket("2025-01", importedEvents = listOf(valid.copy(listeningMillis = -1))),
                    ),
                ),
            )
        }
    }
}
