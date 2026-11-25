package com.music.bitchord.data.history

import kotlinx.serialization.Serializable

/** One source listen retained with its original timestamp and import provenance. */
@Serializable
data class ImportedListen(
    val eventId: String,
    val trackId: String,
    val source: String,
    val sourceAccount: String = "",
    val batchId: String,
    val timestampMillis: Long,
    val timeZoneId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** Actual listening time, only when supplied as such by the source file. */
    val listeningMillis: Long? = null,
    val importedAtMillis: Long = 0L,
)
