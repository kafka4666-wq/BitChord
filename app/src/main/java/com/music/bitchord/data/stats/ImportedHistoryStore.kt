package com.music.bitchord.data.stats

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.music.bitchord.data.history.ImportedListen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Result of appending one staged import; duplicates are never written. */
data class ImportedAppendResult(val imported: Int, val duplicates: Int)

/**
 * Append-only storage for imported listens. It is deliberately separate from
 * ListeningStats' playback counters: imports can be deduplicated and undone by
 * provenance without ever replacing, simulating or deleting native playback.
 */
internal object ImportedHistoryStore {
    private const val TAG = "BitChordHistoryImport"
    private const val DIRECTORY = "listening-imports"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val lock = Any()
    private lateinit var directory: File

    fun init(context: Context) {
        directory = File(context.applicationContext.filesDir, DIRECTORY)
        directory.mkdirs()
    }

    private val ready: Boolean get() = this::directory.isInitialized

    fun months(): List<YearMonth> {
        if (!ready) return emptyList()
        return (directory.listFiles() ?: emptyArray()).mapNotNull { file ->
            file.name.removeSuffix(".json").takeIf { it != file.name }
                ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        }.distinct().sorted()
    }

    fun readMonth(month: String): List<ImportedListen> {
        if (!ready) return emptyList()
        val file = File(directory, "$month.json")
        return runCatching {
            if (file.isFile) json.decodeFromString(ImportedMonth.serializer(), file.readText()).events
            else emptyList()
        }.onFailure { Log.w(TAG, "Could not read imported history for $month", it) }.getOrDefault(emptyList())
    }

    fun readAll(): List<ImportedListen> = months().flatMap { readMonth(it.toString()) }

    suspend fun duplicateCount(events: List<ImportedListen>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val known = months().flatMap { readMonth(it.toString()) }.asSequence().map { it.eventId }.toHashSet()
            var duplicates = 0
            events.forEach { if (!known.add(it.eventId)) duplicates++ }
            duplicates
        }
    }

    suspend fun append(
        events: List<ImportedListen>,
        onProgress: (completed: Int, total: Int, imported: Int) -> Unit = { _, _, _ -> },
    ): ImportedAppendResult = withContext(Dispatchers.IO) {
        require(ready) { "Listening history has not been initialized" }
        if (events.isEmpty()) return@withContext ImportedAppendResult(0, 0)
        mutex.withLock {
            val existingRecords = months().flatMap { readMonth(it.toString()) }
            val known = existingRecords.asSequence().map { it.eventId }.toHashSet()
            val accepted = ArrayList<ImportedListen>(events.size)
            var duplicates = 0
            val importedAt = maxOf(System.currentTimeMillis(), (existingRecords.maxOfOrNull { it.importedAtMillis } ?: 0L) + 1L)
            events.forEach { event ->
                if (known.add(event.eventId)) accepted += event.copy(importedAtMillis = importedAt)
                else duplicates++
            }
            val grouped = accepted.groupBy(::monthForEvent)
            val ordered = grouped.toSortedMap().entries.toList()
            var written = 0
            ordered.forEachIndexed { index, (month, additions) ->
                currentCoroutineContext().ensureActive()
                val existing = readMonth(month)
                writeMonth(month, ImportedMonth(month, existing + additions))
                written += additions.size
                onProgress(index + 1, ordered.size, written)
            }
            ImportedAppendResult(accepted.size, duplicates)
        }
    }

    suspend fun removeLatestBatch(): Int = withContext(Dispatchers.IO) {
        require(ready) { "Listening history has not been initialized" }
        mutex.withLock {
            val all = readAll()
            val latestBatch = all.maxByOrNull { it.importedAtMillis }?.batchId ?: return@withContext 0
            var removed = 0
            for (month in months()) {
                currentCoroutineContext().ensureActive()
                val key = month.toString()
                val records = readMonth(key)
                val kept = records.filterNot { it.batchId == latestBatch }
                removed += records.size - kept.size
                if (kept.size != records.size) {
                    if (kept.isEmpty()) File(directory, "$key.json").delete()
                    else writeMonth(key, ImportedMonth(key, kept))
                }
            }
            removed
        }
    }

    /** Backup restore is intentionally replacement semantics; normal import never calls this. */
    fun replaceAll(events: List<ImportedListen>) {
        if (!ready) return
        synchronized(lock) {
            val stage = File(directory.parentFile, "$DIRECTORY.restore-stage")
            stage.deleteRecursively()
            check(stage.mkdirs() || stage.isDirectory) { "Could not stage imported listening history" }
            try {
                events.distinctBy { it.eventId }.groupBy(::monthForEvent).forEach { (month, records) ->
                    writeMonth(stage, month, ImportedMonth(month, records))
                }
                replaceDirectory(stage)
            } catch (failure: Exception) {
                stage.deleteRecursively()
                throw failure
            }
        }
    }

    private fun writeMonth(month: String, value: ImportedMonth) {
        writeMonth(directory, month, value)
    }

    private fun writeMonth(target: File, month: String, value: ImportedMonth) {
        target.mkdirs()
        val atomic = AtomicFile(File(target, "$month.json"))
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(json.encodeToString(ImportedMonth.serializer(), value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            output?.let(atomic::failWrite)
            throw failure
        }
    }

    private fun replaceDirectory(stage: File) {
        val backup = File(directory.parentFile, "$DIRECTORY.restore-backup")
        backup.deleteRecursively()
        val hadOriginal = directory.exists()
        try {
            if (hadOriginal) check(directory.renameTo(backup)) { "Could not preserve existing imported history" }
            if (!stage.renameTo(directory)) {
                if (hadOriginal && !backup.renameTo(directory)) {
                    error("Could not restore the previous imported history after staging failed")
                }
                error("Could not install imported listening history")
            }
            backup.deleteRecursively()
        } catch (failure: Exception) {
            if (!directory.exists() && backup.exists() && !backup.renameTo(directory)) {
                failure.addSuppressed(IllegalStateException("The previous imported history remains at ${backup.name}"))
            }
            throw failure
        }
    }

    private fun monthForEvent(event: ImportedListen): String {
        val zone = runCatching { ZoneId.of(event.timeZoneId) }.getOrDefault(ZoneId.of("UTC"))
        return YearMonth.from(Instant.ofEpochMilli(event.timestampMillis).atZone(zone)).toString()
    }

    @Serializable
    private data class ImportedMonth(val month: String, val events: List<ImportedListen>)
}
