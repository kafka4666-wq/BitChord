package com.music.bitchord.data.history

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A column mapping for a user-selected CSV export. Indexes refer to its header row. */
data class HistoryCsvMapping(
    val artistColumn: Int,
    val trackColumn: Int,
    val timestampColumn: Int,
    val albumColumn: Int? = null,
    /** Map only a column that represents time listened, not the track's runtime. */
    val listeningDurationColumn: Int? = null,
    val durationUnitMillis: Long = 1_000L,
    val timestampPattern: String = "",
    val timeZoneId: String = "UTC",
)

data class CsvListenRecord(
    val sourceRow: Int,
    val eventId: String,
    val trackId: String,
    val timestampMillis: Long,
    val timeZoneId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val listeningMillis: Long?,
)

data class CsvRowError(val row: Int, val message: String)

data class HistoryCsvPreview(
    val headers: List<String>,
    val suggestedMapping: HistoryCsvMapping,
    val records: List<CsvListenRecord>,
    val invalidRows: List<CsvRowError>,
    val duplicateRows: Int,
)

/** RFC-4180-compatible, bounded-to-input parser used before any persistent data is changed. */
object HistoryCsvParser {
    private data class CsvRow(val line: Int, val fields: List<String>)

    fun inspect(text: String): Pair<List<String>, HistoryCsvMapping> {
        val rows = parseRows(text)
        require(rows.isNotEmpty()) { "The CSV file is empty" }
        val headers = rows.first().fields.mapIndexed { index, value ->
            value.trim().removePrefix("\uFEFF").ifBlank { "Column ${index + 1}" }
        }
        return headers to suggestMapping(headers)
    }

    fun parse(
        text: String,
        mapping: HistoryCsvMapping,
    ): HistoryCsvPreview {
        val rows = parseRows(text)
        require(rows.isNotEmpty()) { "The CSV file is empty" }
        val headers = rows.first().fields.mapIndexed { index, value ->
            value.trim().removePrefix("\uFEFF").ifBlank { "Column ${index + 1}" }
        }
        val zone = runCatching { ZoneId.of(mapping.timeZoneId) }
            .getOrElse { throw IllegalArgumentException("Unknown time zone '${mapping.timeZoneId}'") }
        requireColumn(mapping.artistColumn, headers.size, "artist")
        requireColumn(mapping.trackColumn, headers.size, "track title")
        requireColumn(mapping.timestampColumn, headers.size, "timestamp")
        mapping.albumColumn?.let { requireColumn(it, headers.size, "album") }
        mapping.listeningDurationColumn?.let { requireColumn(it, headers.size, "listening duration") }
        require(mapping.durationUnitMillis > 0) { "Duration unit must be positive" }
        val formatter = mapping.timestampPattern.trim().takeIf { it.isNotEmpty() }?.let { pattern ->
            runCatching { DateTimeFormatter.ofPattern(pattern, Locale.ROOT) }
                .getOrElse { throw IllegalArgumentException("Invalid date format pattern") }
        }

        val valid = ArrayList<CsvListenRecord>()
        val errors = ArrayList<CsvRowError>()
        var duplicates = 0
        val seen = HashSet<String>()
        rows.drop(1).forEach { row ->
            if (row.fields.all(String::isBlank)) return@forEach
            try {
                val artist = row.field(mapping.artistColumn).trim()
                val title = row.field(mapping.trackColumn).trim()
                require(artist.isNotEmpty()) { "Artist is blank" }
                require(title.isNotEmpty()) { "Track title is blank" }
                val timestamp = parseTimestamp(row.field(mapping.timestampColumn).trim(), zone, formatter)
                val album = mapping.albumColumn?.let { row.field(it).trim().ifBlank { null } }
                val listeningMs = mapping.listeningDurationColumn?.let { column ->
                    parseListeningDuration(row.field(column).trim(), mapping.durationUnitMillis)
                }
                val eventId = HistoryIdentity.eventId(artist, title, timestamp)
                if (!seen.add(eventId)) {
                    duplicates++
                } else {
                    valid += CsvListenRecord(
                        sourceRow = row.line,
                        eventId = eventId,
                        trackId = HistoryIdentity.trackId(artist, title),
                        timestampMillis = timestamp,
                        timeZoneId = zone.id,
                        title = title,
                        artist = artist,
                        album = album,
                        listeningMillis = listeningMs,
                    )
                }
            } catch (error: Exception) {
                errors += CsvRowError(row.line, error.message ?: "Invalid row")
            }
        }
        return HistoryCsvPreview(headers, suggestMapping(headers), valid, errors, duplicates)
    }

    fun suggestMapping(headers: List<String>): HistoryCsvMapping {
        fun find(vararg candidates: String): Int? {
            val normalized = headers.map(::normalizeHeader)
            return candidates.firstNotNullOfOrNull { candidate ->
                normalized.indexOf(normalizeHeader(candidate)).takeIf { it >= 0 }
            }
        }
        return HistoryCsvMapping(
            artistColumn = find("artist", "artist name", "performer") ?: -1,
            trackColumn = find("track", "track name", "title", "song", "song name") ?: -1,
            timestampColumn = find("timestamp", "played at", "playedat", "datetime", "date time", "date", "uts") ?: -1,
            albumColumn = find("album", "album name", "release"),
            listeningDurationColumn = find("listening duration", "played duration", "listened ms", "listening ms"),
        )
    }

    private fun normalizeHeader(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun requireColumn(index: Int, size: Int, description: String) {
        require(index in 0 until size) { "Choose a CSV column for $description" }
    }

    private fun CsvRow.field(index: Int): String = fields.getOrElse(index) { "" }

    private fun parseTimestamp(value: String, zone: ZoneId, formatter: DateTimeFormatter?): Long {
        require(value.isNotEmpty()) { "Timestamp is blank" }
        value.toLongOrNull()?.let { raw ->
            val millis = if (kotlin.math.abs(raw) >= 100_000_000_000L) raw else Math.multiplyExact(raw, 1_000L)
            return millis
        }
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val parsed = if (formatter != null) {
            runCatching { LocalDateTime.parse(value, formatter) }.getOrNull()
                ?: runCatching { LocalDate.parse(value, formatter).atStartOfDay() }.getOrNull()
        } else {
            runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }.getOrNull()
                ?: runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay() }.getOrNull()
        }
        return parsed?.atZone(zone)?.toInstant()?.toEpochMilli()
            ?: throw DateTimeParseException("Unsupported timestamp; choose a date format or use ISO-8601/epoch time", value, 0)
    }

    private fun parseListeningDuration(value: String, unitMillis: Long): Long? {
        if (value.isBlank()) return null
        val millis = if (value.contains(':')) {
            val pieces = value.split(':')
            require(pieces.size in 2..3) { "Duration must be seconds, milliseconds, or mm:ss/hh:mm:ss" }
            val numbers = pieces.map { it.toLongOrNull() ?: error("Invalid listening duration") }
            require(numbers.all { it >= 0 }) { "Listening duration cannot be negative" }
            val seconds = if (numbers.size == 2) {
                Math.addExact(Math.multiplyExact(numbers[0], 60), numbers[1])
            } else {
                Math.addExact(Math.addExact(Math.multiplyExact(numbers[0], 3_600), Math.multiplyExact(numbers[1], 60)), numbers[2])
            }
            Math.multiplyExact(seconds, 1_000L)
        } else {
            val numeric = value.toDoubleOrNull() ?: error("Invalid listening duration")
            require(numeric >= 0.0 && numeric.isFinite()) { "Listening duration cannot be negative" }
            (numeric * unitMillis).toLong()
        }
        return millis
    }

    private fun parseRows(text: String): List<CsvRow> {
        val rows = ArrayList<CsvRow>()
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var line = 1
        var rowLine = 1
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && inQuotes && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' && inQuotes -> inQuotes = false
                char == '"' && field.isEmpty() -> inQuotes = true
                char == ',' && !inQuotes -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                (char == '\n' || char == '\r') && !inQuotes -> {
                    fields += field.toString()
                    field.setLength(0)
                    if (fields.any(String::isNotBlank)) rows += CsvRow(rowLine, fields.toList())
                    fields.clear()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    line++
                    rowLine = line
                }
                else -> {
                    field.append(char)
                    if (char == '\n') line++
                }
            }
            index++
        }
        require(!inQuotes) { "CSV ends inside a quoted field" }
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields += field.toString()
            if (fields.any(String::isNotBlank)) rows += CsvRow(rowLine, fields.toList())
        }
        return rows
    }
}

/** Stable source-independent event identity; different timestamps remain distinct plays. */
object HistoryIdentity {
    fun eventId(artist: String, title: String, timestampMillis: Long): String = sha256(
        "event\u001f${normalize(artist)}\u001f${normalize(title)}\u001f${Math.floorDiv(timestampMillis, 1_000L)}",
    )

    fun trackId(artist: String, title: String): String = "history:${sha256("track\u001f${normalize(artist)}\u001f${normalize(title)}")}"

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
