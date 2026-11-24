package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.ui.homeShelvesForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeShelfPreferencesTest {
    @Test
    fun defaultsKeepTrendingAndFeedOrder() {
        val shelves = listOf(
            HomeShelf("Trending", emptyList()),
            HomeShelf("Charts", emptyList()),
            HomeShelf("Playlists", emptyList()),
        )

        assertEquals(
            shelves,
            homeShelvesForDisplay(
                shelves = shelves,
                hideRecents = false,
                hideVideos = false,
                hideTrending = false,
                playlistsAtTop = false,
            ),
        )
    }

    @Test
    fun canHideTrendingAndPromotePlaylistsWithoutReorderingOtherShelves() {
        val shelves = listOf(
            HomeShelf("Trending", emptyList()),
            HomeShelf("Charts", emptyList()),
            HomeShelf("Playlists", emptyList()),
            HomeShelf("New releases", emptyList()),
        )

        val visible = homeShelvesForDisplay(
            shelves = shelves,
            hideRecents = false,
            hideVideos = false,
            hideTrending = true,
            playlistsAtTop = true,
        )

        assertEquals(listOf("Playlists", "Charts", "New releases"), visible.map { it.title })
    }

    @Test
    fun promotionDoesNothingWhenThereIsNoPlaylistShelf() {
        val shelves = listOf(HomeShelf("Charts", emptyList()), HomeShelf("New releases", emptyList()))

        assertEquals(
            shelves,
            homeShelvesForDisplay(
                shelves = shelves,
                hideRecents = false,
                hideVideos = false,
                hideTrending = false,
                playlistsAtTop = true,
            ),
        )
    }
}
