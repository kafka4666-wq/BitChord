package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.ui.HOME_PLAYLISTS_SHELF_ID
import com.music.bitchord.ui.decodeHomeShelfOrder
import com.music.bitchord.ui.encodeHomeShelfOrder
import com.music.bitchord.ui.homeShelfStableId
import com.music.bitchord.ui.homeShelfUsesListLayout
import com.music.bitchord.ui.homeShelvesForDisplay
import com.music.bitchord.ui.moveHomeShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            ),
        )
    }

    @Test
    fun appliesSavedOrderAfterVisibilityFiltering() {
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
            shelfOrder = listOf("title:new releases", HOME_PLAYLISTS_SHELF_ID, "title:charts"),
        )

        assertEquals(listOf("New releases", "Playlists", "Charts"), visible.map { it.title })
    }

    @Test
    fun shelvesMissingFromSavedOrderAreAppendedInTheirOriginalRelativeOrder() {
        val shelves = listOf(
            HomeShelf("New server shelf", emptyList()),
            HomeShelf("Charts", emptyList()),
            HomeShelf("Recently added shelf", emptyList()),
        )

        assertEquals(
            listOf("Charts", "New server shelf", "Recently added shelf"),
            homeShelvesForDisplay(
                shelves = shelves,
                hideRecents = false,
                hideVideos = false,
                hideTrending = false,
                shelfOrder = listOf("title:charts", "title:stale shelf"),
            ).map { it.title },
        )
    }

    @Test
    fun newlyAvailableShelvesKeepFeedOrderAfterTheSavedShelves() {
        val shelves = listOf(
            HomeShelf("New one", emptyList()),
            HomeShelf("Playlists", emptyList()),
            HomeShelf("New two", emptyList()),
        )

        assertEquals(
            listOf("Playlists", "New one", "New two"),
            homeShelvesForDisplay(
                shelves = shelves,
                hideRecents = false,
                hideVideos = false,
                hideTrending = false,
                shelfOrder = listOf(HOME_PLAYLISTS_SHELF_ID),
            ).map { it.title },
        )
    }

    @Test
    fun recentsAndVideoVisibilityStillFilterTheFeedBeforeOrdering() {
        val shelves = listOf(
            HomeShelf("Recents", emptyList()),
            HomeShelf("Video charts", emptyList(), layout = ShelfLayout.VIDEO),
            HomeShelf("Charts", listOf(ShelfItem("Chart entry", "", null, "chart-item", null))),
        )

        assertEquals(
            listOf("Charts"),
            homeShelvesForDisplay(
                shelves = shelves,
                hideRecents = true,
                hideVideos = true,
                hideTrending = false,
                shelfOrder = listOf("builtin:recents", "title:video charts", "title:charts"),
            ).map { it.title },
        )
    }

    @Test
    fun reorderNeverMutatesTheSourceFeed() {
        val source = mutableListOf(
            HomeShelf("Playlists", emptyList()),
            HomeShelf("Charts", emptyList()),
        )
        val original = source.toList()

        val visible = homeShelvesForDisplay(
            shelves = source,
            hideRecents = false,
            hideVideos = false,
            hideTrending = false,
            shelfOrder = listOf("title:charts", HOME_PLAYLISTS_SHELF_ID),
        )

        assertEquals(original, source)
        assertEquals(listOf("Charts", "Playlists"), visible.map { it.title })
    }

    @Test
    fun shelfIdentifiersDoNotDependOnPositionAndPreferBrowseIdsForDynamicShelves() {
        val first = HomeShelf("Made for you", emptyList(), moreBrowseId = "FEhome")
        val later = HomeShelf("Made for you", emptyList(), moreBrowseId = "FEhome")

        assertEquals("browse:FEhome", homeShelfStableId(first))
        assertEquals(homeShelfStableId(first), homeShelfStableId(later))
        assertEquals(HOME_PLAYLISTS_SHELF_ID, homeShelfStableId(HomeShelf("Playlists", emptyList())))
    }

    @Test
    fun orderSerializationAndMoveHelpersAreDeterministicAndCopyTheirInput() {
        val ids = listOf("title:charts", "builtin:playlists", "title:charts")
        val encoded = encodeHomeShelfOrder(ids)

        assertEquals("title:charts\nbuiltin:playlists", encoded)
        assertEquals(listOf("title:charts", "builtin:playlists"), decodeHomeShelfOrder(encoded))
        assertEquals("", encodeHomeShelfOrder(emptyList()))
        assertEquals(emptyList<String>(), decodeHomeShelfOrder(null))
        assertEquals(listOf("b", "a", "c"), moveHomeShelf(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "b", "c"), moveHomeShelf(listOf("a", "b", "c"), -1, 1))
    }

    @Test
    fun homeRecentsLayoutFollowsTheSavedChoice() {
        val recents = HomeShelf("Recents", emptyList(), layout = ShelfLayout.CARDS)

        assertTrue(homeShelfUsesListLayout(recents, recentsAsList = true))
        assertFalse(homeShelfUsesListLayout(recents, recentsAsList = false))
    }

    @Test
    fun nonRecentsKeepTheLayoutProvidedByTheFeed() {
        val listShelf = HomeShelf("Quick picks", emptyList(), layout = ShelfLayout.LIST)
        val cardShelf = HomeShelf("Charts", emptyList(), layout = ShelfLayout.CARDS)

        assertTrue(homeShelfUsesListLayout(listShelf, recentsAsList = false))
        assertFalse(homeShelfUsesListLayout(cardShelf, recentsAsList = true))
    }
}
