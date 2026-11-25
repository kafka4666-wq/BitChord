package com.music.bitchord.data.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HistoryCsvParserTest {
    @Test
    fun quotedFieldsAndHeaderMappingParseWithoutLosingMetadata() {
        val text = "Artist,Track,Album,When,Listening duration\n" +
            "\"Artist, With Comma\",\"Song \"\"Live\"\"\",\"Record, Vol. 1\",\"2024-01-02T03:04:05Z\",211\n"
        val (headers, suggestion) = HistoryCsvParser.inspect(text)
        assertEquals(listOf("Artist", "Track", "Album", "When", "Listening duration"), headers)
        val mapping = suggestion.copy(
            timestampColumn = 3,
            listeningDurationColumn = 4,
            durationUnitMillis = 1_000,
            timeZoneId = "UTC",
        )
        val preview = HistoryCsvParser.parse(text, mapping)
        assertEquals(1, preview.records.size)
        assertEquals("Artist, With Comma", preview.records.single().artist)
        assertEquals("Song \"Live\"", preview.records.single().title)
        assertEquals("Record, Vol. 1", preview.records.single().album)
        assertEquals(211_000L, preview.records.single().listeningMillis)
        assertTrue(preview.invalidRows.isEmpty())
    }

    @Test
    fun repeatedPlaysAtDifferentTimesRemainSeparateButExactDuplicatesAreCounted() {
        val text = "artist,title,timestamp\nArtist,Track,1700000000\nArtist,Track,1700003600\nArtist,Track,1700000000\n"
        val mapping = HistoryCsvParser.suggestMapping(listOf("artist", "title", "timestamp"))
        val preview = HistoryCsvParser.parse(text, mapping)
        assertEquals(2, preview.records.size)
        assertEquals(1, preview.duplicateRows)
        assertNotEquals(preview.records[0].eventId, preview.records[1].eventId)
        assertEquals(preview.records[0].trackId, preview.records[1].trackId)
    }

    @Test
    fun supportsEpochSecondsAndMillisecondsAndZoneLessDatesInSelectedZone() {
        val headers = listOf("artist", "track", "timestamp")
        val mapping = HistoryCsvParser.suggestMapping(headers).copy(timeZoneId = "Asia/Kolkata")
        val seconds = HistoryCsvParser.parse("artist,track,timestamp\nA,T,1700000000\n", mapping).records.single()
        val millis = HistoryCsvParser.parse("artist,track,timestamp\nA,T,1700000000000\n", mapping).records.single()
        assertEquals(1_700_000_000_000L, seconds.timestampMillis)
        assertEquals(seconds.timestampMillis, millis.timestampMillis)

        val custom = mapping.copy(timestampPattern = "dd/MM/yyyy HH:mm")
        val local = HistoryCsvParser.parse("artist,track,timestamp\nA,T,02/01/2024 03:04\n", custom).records.single()
        assertEquals("Asia/Kolkata", local.timeZoneId)
        assertEquals(1_704_144_840_000L, local.timestampMillis)
    }

    @Test
    fun malformedRowsAreReportedAndEmptyOptionalAlbumOrDurationIsAccepted() {
        val text = "artist,title,timestamp,album,listening duration\nA,T,not-a-date,,\nB,,1700000000,,12\nC,T,1700000001,,\n"
        val mapping = HistoryCsvParser.suggestMapping(listOf("artist", "title", "timestamp", "album", "listening duration"))
        val preview = HistoryCsvParser.parse(text, mapping)
        assertEquals(1, preview.records.size)
        assertEquals(2, preview.invalidRows.size)
        assertEquals(null, preview.records.single().album)
        assertEquals(null, preview.records.single().listeningMillis)
    }

    @Test
    fun supportsQuotedLineBreaksAndStripsUtf8BomFromHeader() {
        val text = "\uFEFFartist,title,timestamp\r\nA,\"Two\nLines\",1700000000\r\n"
        val headers = HistoryCsvParser.inspect(text).first
        val preview = HistoryCsvParser.parse(text, HistoryCsvParser.suggestMapping(headers))
        assertEquals("artist", headers.first())
        assertEquals("Two\nLines", preview.records.single().title)
        assertEquals(2, preview.records.single().sourceRow)
    }
}
