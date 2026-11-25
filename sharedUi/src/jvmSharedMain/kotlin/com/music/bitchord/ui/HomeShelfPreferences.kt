package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.withoutVideos
import java.text.Normalizer
import java.util.Locale

private val TRENDING_HOME_TITLES = setOf("trending", "thịnh hành", "急上昇")

/** Stable identifiers for Home shelves whose labels are owned by the app. */
const val HOME_PLAYLISTS_SHELF_ID = "builtin:playlists"
private const val HOME_RECENTS_SHELF_ID = "builtin:recents"
private const val HOME_TRENDING_SHELF_ID = "builtin:trending"

/**
 * A stable persisted key for a shelf. Known app shelves keep a semantic ID;
 * server-provided shelves use their browse ID when available and otherwise a
 * normalized title, never their position in the feed.
 */
fun homeShelfStableId(shelf: HomeShelf): String {
    val title = shelf.title.trim()
    val browseId = shelf.moreBrowseId
    return when {
        title.equals(YtMusicRepository.PLAYLISTS_SHELF, ignoreCase = true) -> HOME_PLAYLISTS_SHELF_ID
        title == YtMusicRepository.RECENT_TITLE || title.equals("Recents", ignoreCase = true) ->
            HOME_RECENTS_SHELF_ID
        title.lowercase(Locale.ROOT) in TRENDING_HOME_TITLES -> HOME_TRENDING_SHELF_ID
        !browseId.isNullOrBlank() -> "browse:${browseId.trim()}"
        else -> "title:${Normalizer.normalize(title.lowercase(Locale.ROOT).replace(Regex("\\s+"), " "), Normalizer.Form.NFC)}"
    }
}

/** Store ordered stable IDs as a compact, deterministic SharedPreferences string. */
fun encodeHomeShelfOrder(ids: List<String>): String = ids
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .joinToString("\n")

/** Decode a stored shelf order; malformed or duplicate entries are ignored safely. */
fun decodeHomeShelfOrder(value: String?): List<String> = value
    .orEmpty()
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .toList()

/**
 * Sorts only the shelves named by [shelfOrder]. New or unknown shelves keep
 * their source order after the saved shelves, so an app/feed update cannot
 * scramble a listener's established ordering.
 */
fun List<HomeShelf>.orderedByHomeShelfIds(shelfOrder: List<String>): List<HomeShelf> {
    if (shelfOrder.isEmpty() || size < 2) return toList()
    val ranks = shelfOrder.withIndex().associate { it.value to it.index }
    return withIndex()
        .sortedWith(compareBy<IndexedValue<HomeShelf>>({ ranks[homeShelfStableId(it.value)] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/** Returns a moved copy; invalid moves leave the original order untouched. */
fun moveHomeShelf(order: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) return order.toList()
    return order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

/** Applies listener-selected shelf visibility and ordering without changing the source feed. */
fun homeShelvesForDisplay(
    shelves: List<HomeShelf>,
    hideRecents: Boolean,
    hideVideos: Boolean,
    hideTrending: Boolean,
    shelfOrder: List<String> = emptyList(),
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
    return visible.orderedByHomeShelfIds(shelfOrder)
}

/** The user's Home Recents choice overrides its feed layout; all other shelves keep theirs. */
internal fun homeShelfUsesListLayout(shelf: HomeShelf, recentsAsList: Boolean): Boolean {
    val isRecents = shelf.title == YtMusicRepository.RECENT_TITLE ||
        shelf.title.equals("Recents", ignoreCase = true)
    return if (isRecents) recentsAsList else shelf.layout == ShelfLayout.LIST
}
