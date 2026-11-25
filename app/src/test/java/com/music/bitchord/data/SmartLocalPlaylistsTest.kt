package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartLocalPlaylistsTest {
    private fun song(
        id: String,
        title: String = id,
        uri: String? = "content://media/$id",
        added: Long? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
        localUri = uri,
        localDateAddedSeconds = added,
    )

    @Test
    fun recentlyAddedUsesOnlyPositiveLocalAddedDatesAndBreaksTiesByTitle() {
        val tracks = listOf(
            song("old", title = "Old", added = 100),
            song("z", title = "Zulu", added = 500),
            song("a", title = "alpha", added = 500),
            song("missing", title = "Missing date"),
            song("zero", title = "Zero date", added = 0),
            song("remote", title = "Remote", uri = null, added = 900),
        )

        val recent = SmartLocalPlaylists.derive(tracks, emptyMap())
            .single { it.id == SmartLocalPlaylists.RECENTLY_ADDED }

        assertEquals(listOf("a", "z", "old"), recent.songs.map { it.videoId })
    }

    @Test
    fun mostPlayedRanksActualPositiveCountsAndUsesStableAlphabeticTies() {
        val tracks = listOf(
            song("b", title = "Beta"),
            song("a", title = "Alpha"),
            song("top", title = "Top"),
            song("zero", title = "Zero"),
            song("remote", title = "Remote", uri = null),
        )

        val mostPlayed = SmartLocalPlaylists.derive(
            tracks,
            mapOf("a" to 4, "b" to 4, "top" to 8, "zero" to 0, "remote" to 50),
        ).single { it.id == SmartLocalPlaylists.MOST_PLAYED }

        assertEquals(listOf("top", "a", "b"), mostPlayed.songs.map { it.videoId })
    }

    @Test
    fun neverPlayedTreatsMissingAndZeroCountsAsNeverPlayedAndSortsDeterministically() {
        val tracks = listOf(
            song("z", title = "Zulu"),
            song("played", title = "Played"),
            song("a", title = "Alpha"),
            song("zero", title = "Zero"),
            song("negative", title = "Negative"),
        )

        val neverPlayed = SmartLocalPlaylists.derive(
            tracks,
            mapOf("played" to 1, "zero" to 0, "negative" to -1),
        ).single { it.id == SmartLocalPlaylists.NEVER_PLAYED }

        assertEquals(listOf("a", "negative", "zero", "z"), neverPlayed.songs.map { it.videoId })
    }

    @Test
    fun alwaysReturnsTheThreeLiveCollectionDefinitions() {
        assertEquals(
            listOf(
                SmartLocalPlaylists.RECENTLY_ADDED,
                SmartLocalPlaylists.MOST_PLAYED,
                SmartLocalPlaylists.NEVER_PLAYED,
            ),
            SmartLocalPlaylists.derive(emptyList(), emptyMap()).map { it.id },
        )
    }
}
