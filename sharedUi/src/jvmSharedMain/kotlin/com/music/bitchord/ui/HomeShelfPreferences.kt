package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.withoutVideos
import java.util.Locale

private val TRENDING_HOME_TITLES = setOf("trending", "thịnh hành", "急上昇")

/** Applies listener-selected shelf visibility and ordering without changing the source feed. */
internal fun homeShelvesForDisplay(
    shelves: List<HomeShelf>,
    hideRecents: Boolean,
    hideVideos: Boolean,
    hideTrending: Boolean,
    playlistsAtTop: Boolean,
): List<HomeShelf> {
    var visible = shelves
    if (hideRecents) {
        visible = visible.filterNot { it.title == YtMusicRepository.RECENT_TITLE }
    }
    if (hideVideos) {
        visible = visible.withoutVideos()
    }
    if (hideTrending) {
        visible = visible.filterNot { it.title.trim().lowercase(Locale.ROOT) in TRENDING_HOME_TITLES }
    }
    if (playlistsAtTop) {
        val playlistsIndex = visible.indexOfFirst { it.title == YtMusicRepository.PLAYLISTS_SHELF }
        if (playlistsIndex > 0) {
            val playlists = visible[playlistsIndex]
            visible = buildList(visible.size) {
                add(playlists)
                visible.forEachIndexed { index, shelf ->
                    if (index != playlistsIndex) add(shelf)
                }
            }
        }
    }
    return visible
}
