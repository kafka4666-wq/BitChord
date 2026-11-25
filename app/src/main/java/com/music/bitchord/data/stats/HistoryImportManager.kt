package com.music.bitchord.data.stats

import android.content.Context
import android.util.AtomicFile
import com.music.bitchord.data.history.CsvListenRecord
import com.music.bitchord.data.history.HistoryIdentity
import com.music.bitchord.data.history.ImportedListen
import com.music.bitchord.data.scrobbling.LastFM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** Fetch/preview state; no local statistics change until [commit] is called. */
data class HistoryImportPreview(
    val batchId: String,
    val source: String,
    val username: String,
    val events: List<ImportedListen>,
    val fetched: Int,
    val matched: Int,
    val skipped: Int,
    val duplicate: Int,
    val pagesComplete: Int,
    val pagesTotal: Int,
    val resumable: Boolean,
    /** Repeats found in the source file or across Last.fm pages, not in local history. */
    val sourceDuplicates: Int = 0,
)

data class HistoryImportProgress(
    val fetched: Int,
    val pagesComplete: Int,
    val pagesTotal: Int,
)

data class HistoryImportCommit(
    val imported: Int,
    val duplicate: Int,
)

/**
 * Coordinates historical imports without creating playback events. Last.fm pages
 * and CSV previews are staged privately so a process death, rate limit, or
 * cancellation never replaces history and can be resumed after review.
 */
class HistoryImportManager(context: Context) {
    private val appContext = context.applicationContext
    private val stagingDirectory = File(appContext.filesDir, "history-import")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun previewLastFm(
        username: String,
        from: LocalDate? = null,
        to: LocalDate? = null,
        onProgress: (HistoryImportProgress) -> Unit = {},
    ): HistoryImportPreview = withContext(Dispatchers.IO) {
        val account = username.trim()
        require(account.isNotEmpty()) { "Enter your Last.fm username" }
        require(from == null || to == null || !from.isAfter(to)) { "The start date must not be after the end date" }
        val requestedZone = ZoneId.systemDefault()
        val fromSeconds = from?.atStartOfDay(requestedZone)?.toEpochSecond()?.minus(1)
        val fromDate = from?.toString()
        val toDate = to?.toString()
        val requestedTo = to?.plusDays(1)?.atStartOfDay(requestedZone)?.toEpochSecond()
        val previous = findLastFmCheckpoint(account, fromDate, toDate)
        val checkpoint: LastFmCheckpoint
        val checkpointFile: File
        if (previous != null) {
            checkpoint = previous.first
            checkpointFile = previous.second
        } else {
            checkpoint = LastFmCheckpoint(
                username = account,
                fromSeconds = fromSeconds,
                fromDate = fromDate,
                toDate = toDate,
                timeZoneId = requestedZone.id,
                toSeconds = requestedTo ?: Instant.now().epochSecond,
                nextPage = 1,
                totalPages = 1,
                batchId = UUID.randomUUID().toString(),
                events = emptyList(),
                fetched = 0,
                skipped = 0,
                duplicate = 0,
                complete = false,
            )
            checkpointFile = checkpointFile(account, checkpoint.batchId)
            saveCheckpoint(checkpointFile, checkpoint)
        }
        var current = checkpoint
        val zone = runCatching { ZoneId.of(current.timeZoneId) }.getOrDefault(requestedZone)
        if (!current.complete) {
            var pageNumber = current.nextPage
            while (pageNumber <= current.totalPages) {
                val page = fetchWithRetry(account, pageNumber, current.fromSeconds, current.toSeconds)
                val knownIds = current.events.asSequence().map { it.eventId }.toHashSet()
                var duplicate = current.duplicate
                val additions = page.tracks.mapNotNull { track ->
                    val eventMillis = runCatching { Math.multiplyExact(track.timestampSeconds, 1_000L) }.getOrNull()
                        ?: return@mapNotNull null
                    val eventDate = Instant.ofEpochMilli(eventMillis).atZone(zone).toLocalDate()
                    if ((from != null && eventDate.isBefore(from)) || (to != null && eventDate.isAfter(to))) {
                        return@mapNotNull null
                    }
                    val id = HistoryIdentity.eventId(track.artist, track.title, eventMillis)
                    if (!knownIds.add(id)) {
                        duplicate++
                        null
                    } else {
                        ImportedListen(
                            eventId = id,
                            trackId = HistoryIdentity.trackId(track.artist, track.title),
                            source = LASTFM_SOURCE,
                            sourceAccount = account,
                            batchId = current.batchId,
                            timestampMillis = eventMillis,
                            timeZoneId = zone.id,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                        )
                    }
                }
                val totalPages = maxOf(current.totalPages, page.totalPages)
                current = current.copy(
                    nextPage = pageNumber + 1,
                    totalPages = totalPages,
                    events = current.events + additions,
                    fetched = current.fetched + page.tracks.size + page.skipped,
                    skipped = current.skipped + page.skipped,
                    duplicate = duplicate,
                    complete = pageNumber >= totalPages,
                )
                saveCheckpoint(checkpointFile, current)
                onProgress(HistoryImportProgress(current.fetched, pageNumber, current.totalPages))
                pageNumber++
                if (pageNumber <= current.totalPages) delay(PAGE_DELAY_MILLIS)
            }
        }
        val existingDuplicates = ListeningStats.countImportedDuplicates(current.events)
        HistoryImportPreview(
            batchId = current.batchId,
            source = LASTFM_SOURCE,
            username = account,
            events = current.events,
            fetched = current.fetched,
            matched = current.events.size,
            skipped = current.skipped,
            duplicate = current.duplicate + existingDuplicates,
            pagesComplete = (current.nextPage - 1).coerceAtLeast(0),
            pagesTotal = current.totalPages,
            resumable = true,
            sourceDuplicates = current.duplicate,
        )
    }

    suspend fun previewCsv(
        rows: List<CsvListenRecord>,
        sourceName: String,
        timeZoneId: String,
        duplicateRows: Int = 0,
        invalidRows: Int = 0,
    ): HistoryImportPreview = withContext(Dispatchers.Default) {
        val batchId = UUID.randomUUID().toString()
        val records = rows.map { row ->
            ImportedListen(
                eventId = row.eventId,
                trackId = row.trackId,
                source = CSV_SOURCE,
                sourceAccount = sourceName.take(160),
                batchId = batchId,
                timestampMillis = row.timestampMillis,
                timeZoneId = timeZoneId,
                title = row.title,
                artist = row.artist,
                album = row.album,
                listeningMillis = row.listeningMillis,
            )
        }
        val existingDuplicates = ListeningStats.countImportedDuplicates(records)
        val staged = CsvCheckpoint(batchId, sourceName, duplicateRows, invalidRows, records)
        withContext(Dispatchers.IO) { saveCsvCheckpoint(staged) }
        HistoryImportPreview(
            batchId = batchId,
            source = CSV_SOURCE,
            username = sourceName,
            events = records,
            fetched = rows.size + duplicateRows + invalidRows,
            matched = records.size,
            skipped = invalidRows,
            duplicate = duplicateRows + existingDuplicates,
            pagesComplete = 1,
            pagesTotal = 1,
            resumable = true,
            sourceDuplicates = duplicateRows,
        )
    }

    suspend fun loadStagedCsv(): HistoryImportPreview? = withContext(Dispatchers.IO) {
        stagingDirectory.listFiles()?.asSequence()
            ?.filter { it.name.startsWith("csv-") && it.name.endsWith(".json") }
            ?.mapNotNull { file -> runCatching { json.decodeFromString(CsvCheckpoint.serializer(), file.readText()) }.getOrNull() }
            ?.maxByOrNull { File(stagingDirectory, "csv-${it.batchId}.json").lastModified() }
            ?.let { staged ->
                val existingDuplicates = ListeningStats.countImportedDuplicates(staged.events)
                HistoryImportPreview(
                    batchId = staged.batchId,
                    source = CSV_SOURCE,
                    username = staged.sourceName,
                    events = staged.events,
                    fetched = staged.events.size + staged.sourceDuplicates + staged.invalidRows,
                    matched = staged.events.size,
                    skipped = staged.invalidRows,
                    duplicate = staged.sourceDuplicates + existingDuplicates,
                    pagesComplete = 1,
                    pagesTotal = 1,
                    resumable = true,
                    sourceDuplicates = staged.sourceDuplicates,
                )
            }
    }

    suspend fun commit(
        preview: HistoryImportPreview,
        onProgress: (HistoryImportProgress) -> Unit = {},
    ): HistoryImportCommit {
        val result = ListeningStats.importEvents(preview.events, onProgress)
        withContext(Dispatchers.IO) {
            when (preview.source) {
                LASTFM_SOURCE -> checkpointFile(preview.username, preview.batchId).delete()
                CSV_SOURCE -> csvCheckpointFile(preview.batchId).delete()
            }
        }
        return HistoryImportCommit(result.imported, result.duplicates + preview.sourceDuplicates)
    }

    suspend fun undoLatestImport(): Int = ListeningStats.removeLatestImportedBatch()

    suspend fun resumableLastFmRange(username: String): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        stagingDirectory.listFiles()?.asSequence()
            ?.filter { it.name.startsWith("lastfm-") && it.name.endsWith(".json") }
            ?.mapNotNull(::readCheckpoint)
            ?.filter { it.username.equals(username.trim(), ignoreCase = true) }
            ?.maxByOrNull { checkpoint ->
                runCatching { checkpointFile(username, checkpoint.batchId).lastModified() }.getOrDefault(0L)
            }
            ?.let { it.fromDate to it.toDate }
    }

    suspend fun hasResumableLastFm(username: String): Boolean = resumableLastFmRange(username) != null

    private suspend fun fetchWithRetry(
        username: String,
        page: Int,
        fromSeconds: Long?,
        toSeconds: Long,
    ): LastFM.RecentTracksPage {
        var attempt = 0
        while (true) {
            try {
                return LastFM.getRecentTracks(
                    username = username,
                    page = page,
                    limit = PAGE_SIZE,
                    fromSeconds = fromSeconds,
                    toSeconds = toSeconds,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!shouldRetry(error) || attempt >= MAX_RETRIES) throw error
                delay((INITIAL_BACKOFF_MILLIS shl attempt).coerceAtMost(MAX_BACKOFF_MILLIS))
                attempt++
            }
        }
    }

    private fun shouldRetry(error: Exception): Boolean = when (error) {
        is LastFM.LastFmException -> error.code in RETRYABLE_LASTFM_CODES || error.code >= 500
        is IllegalArgumentException -> false
        else -> error is IOException || error.javaClass.name.contains("timeout", ignoreCase = true) ||
            error.javaClass.name.contains("connect", ignoreCase = true)
    }

    private fun findLastFmCheckpoint(
        username: String,
        fromDate: String?,
        toDate: String?,
    ): Pair<LastFmCheckpoint, File>? = stagingDirectory.listFiles()?.asSequence()
        ?.filter { it.name.startsWith("lastfm-") && it.name.endsWith(".json") }
        ?.mapNotNull { file ->
            val checkpoint = readCheckpoint(file) ?: return@mapNotNull null
            if (checkpoint.username.equals(username, ignoreCase = true) &&
                checkpoint.fromDate == fromDate && checkpoint.toDate == toDate
            ) checkpoint to file else null
        }
        ?.maxByOrNull { it.second.lastModified() }

    private fun checkpointFile(username: String, batchId: String): File {
        val key = HistoryIdentity.trackId("lastfm", username.lowercase(Locale.ROOT)).removePrefix("history:")
        return File(stagingDirectory, "lastfm-$key-$batchId.json")
    }

    private fun readCheckpoint(file: File): LastFmCheckpoint? = runCatching {
        if (!file.isFile) null else json.decodeFromString<LastFmCheckpoint>(file.readText())
    }.getOrNull()

    private fun saveCheckpoint(file: File, checkpoint: LastFmCheckpoint) {
        stagingDirectory.mkdirs()
        writeAtomic(file, json.encodeToString(LastFmCheckpoint.serializer(), checkpoint))
    }

    private fun csvCheckpointFile(batchId: String) = File(stagingDirectory, "csv-$batchId.json")

    private fun saveCsvCheckpoint(checkpoint: CsvCheckpoint) {
        stagingDirectory.mkdirs()
        val file = csvCheckpointFile(checkpoint.batchId)
        writeAtomic(file, json.encodeToString(CsvCheckpoint.serializer(), checkpoint))
    }

    private fun writeAtomic(file: File, content: String) {
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            output?.let(atomic::failWrite)
            throw failure
        }
    }

    @Serializable
    private data class LastFmCheckpoint(
        val username: String,
        val fromSeconds: Long?,
        val fromDate: String? = null,
        val toDate: String? = null,
        val timeZoneId: String = "UTC",
        val toSeconds: Long,
        val nextPage: Int,
        val totalPages: Int,
        val batchId: String,
        val events: List<ImportedListen>,
        val fetched: Int,
        val skipped: Int,
        val duplicate: Int,
        val complete: Boolean,
    )

    @Serializable
    private data class CsvCheckpoint(
        val batchId: String,
        val sourceName: String,
        val sourceDuplicates: Int,
        val invalidRows: Int,
        val events: List<ImportedListen>,
    )

    companion object {
        const val LASTFM_SOURCE = "lastfm"
        const val CSV_SOURCE = "csv"
        const val PAGE_SIZE = 200
        private const val PAGE_DELAY_MILLIS = 300L
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L
        private val RETRYABLE_LASTFM_CODES = setOf(8, 11, 16, 29, 429)
    }
}
