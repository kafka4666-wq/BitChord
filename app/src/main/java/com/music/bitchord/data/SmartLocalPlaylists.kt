package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import java.util.Locale

/** A live, read-only view over Local Music; it is never persisted as a playlist. */
data class SmartLocalCollection(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val subtitle: String get() = "${songs.size} tracks"
}

/** Pure semantics for Local Music's dynamically derived collections. */
object SmartLocalPlaylists {
    const val RECENTLY_ADDED = "recently-added"
    const val MOST_PLAYED = "most-played"
    const val NEVER_PLAYED = "never-played"
    const val BROWSE_PREFIX = "local:smart:"

    /**
     * Derive the three collections using only local-file metadata and actual
     * play totals. Runtime is O(library log library + played tracks): history
     * has already been reduced to a map by [playCounts], never nested-scanned
     * once per library row.
     */
    fun derive(
        localSongs: List<Song>,
        playCounts: Map<String, Int>,
    ): List<SmartLocalCollection> {
        val local = localSongs.filter { !it.localUri.isNullOrBlank() }
        val byTitleArtistId = compareBy<Song> { it.title.lowercase(Locale.ROOT) }
            .thenBy { it.artist.lowercase(Locale.ROOT) }
            .thenBy { it.videoId }
        val recentlyAdded = local.asSequence()
            .filter { (it.localDateAddedSeconds ?: 0L) > 0L }
            .sortedWith(
                compareByDescending<Song> { it.localDateAddedSeconds ?: 0L }
                    .then(byTitleArtistId),
            )
            .toList()
        val mostPlayed = local.asSequence()
            .filter { (playCounts[it.videoId] ?: 0) > 0 }
            .sortedWith(
                compareByDescending<Song> { playCounts[it.videoId] ?: 0 }
                    .then(byTitleArtistId),
            )
            .toList()
        val neverPlayed = local.asSequence()
            .filter { (playCounts[it.videoId] ?: 0) <= 0 }
            .sortedWith(byTitleArtistId)
            .toList()

        return listOf(
            SmartLocalCollection(RECENTLY_ADDED, "Recently Added", recentlyAdded),
            SmartLocalCollection(MOST_PLAYED, "Most Played", mostPlayed),
            SmartLocalCollection(NEVER_PLAYED, "Never Played", neverPlayed),
        )
    }
}
