package com.music.bitchord

import com.music.bitchord.data.matchesSearch
import com.music.bitchord.data.matchingSearch
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = album,
    )

    @Test
    fun matchesWithoutCaseSensitivity() {
        val track = song("one", "Yellow", "Coldplay", "Parachutes")

        assertTrue(track.matchesSearch("YELLOW"))
        assertTrue(track.matchesSearch("cOlDpLaY"))
        assertTrue(track.matchesSearch("PARACHUTES"))
    }

    @Test
    fun findsQueryTermsAcrossTitleArtistAndAlbum() {
        val track = song("one", "Midnight City", "M83", "Hurry Up, We're Dreaming")

        assertTrue(track.matchesSearch("midnight M83 dreaming"))
    }

    @Test
    fun missingAlbumDoesNotPreventTitleAndArtistMatches() {
        val track = song("one", "No Surprises", "Radiohead")

        assertTrue(track.matchesSearch("radiohead surprises"))
        assertFalse(track.matchesSearch("radiohead ok computer"))
    }

    @Test
    fun everyQueryTermMustMatch() {
        val track = song("one", "Yellow", "Coldplay", "Parachutes")

        assertFalse(track.matchesSearch("coldplay moonlight"))
    }

    @Test
    fun punctuationAndWhitespaceSeparateTerms() {
        val track = song("one", "Midnight City", "M83", "Hurry Up, We're Dreaming")

        assertTrue(track.matchesSearch("  M83\tMidnight,   Dreaming!  "))
    }

    @Test
    fun filteringKeepsTheOriginalRelativeOrder() {
        val tracks = listOf(
            song("first", "Yellow", "Coldplay"),
            song("skip", "Blue Monday", "New Order"),
            song("second", "Yellow (Live)", "Another Artist"),
        )

        assertEquals(
            listOf("first", "second"),
            tracks.matchingSearch("yellow").map { it.videoId },
        )
    }

    @Test
    fun listMatcherParsesOneMultiWordQueryAndTreatsPunctuationAsSeparators() {
        val tracks = listOf(
            song("first", "Midnight City", "M83", "Hurry Up, We're Dreaming"),
            song("second", "Midnight City", "M83", null),
        )

        assertEquals(listOf("first"), tracks.matchingSearch(" M83 / dreaming ").map { it.videoId })
        assertEquals(tracks, tracks.matchingSearch("  "))
    }
}
