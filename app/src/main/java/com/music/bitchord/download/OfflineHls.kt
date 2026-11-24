package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException

/**
 * An offline copy of an unencrypted HLS media playlist.  This deliberately
 * keeps fMP4 samples untouched: Media3's HLS reader is the same one that
 * plays the network stream, and therefore supports codecs MediaMuxer cannot
 * put in a fresh MP4 (notably FLAC and Dolby variants).
 */
internal object OfflineHls {
    suspend fun save(
        context: Context,
        id: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
        lyrics: LyricsTag.Embeddable?,
        artwork: MediaTagger.Artwork?,
        http1Only: Boolean = false,
    ): Uri = withContext(Dispatchers.IO) {
        val root = File(File(context.filesDir, "offline-hls"), id.hashCode().toUInt().toString(16))
        root.deleteRecursively()
        if (!root.mkdirs()) error("Could not create offline HLS package")
        try {
            fun request(target: String) = Request.Builder().url(target).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            fun execute(target: String, label: String) = try {
                Http.downloadClient(http1Only).newCall(request(target)).execute()
            } catch (e: IOException) {
                throw networkFailure(label, e)
            }
            fun check(response: okhttp3.Response, label: String) {
                if (response.isSuccessful) return
                val code = response.code
                val message = "$label failed (HTTP $code)"
                if (code == 408 || code == 429 || code >= 500) {
                    throw RetryableDownloadException(
                        message,
                        statusCode = code,
                        retryAfterMillis = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000),
                    )
                }
                error(message)
            }
            val playlist = execute(url, "HLS playlist").use { response ->
                check(response, "HLS playlist")
                try {
                    response.body?.string() ?: error("Empty HLS playlist")
                } catch (e: IOException) {
                    throw networkFailure("HLS playlist", e, response.protocol)
                }
            }
            if (!playlist.startsWith("#EXTM3U")) error("Invalid HLS playlist")
            if (playlist.contains("#EXT-X-KEY:") && !playlist.contains("METHOD=NONE")) {
                error("Encrypted HLS cannot be saved")
            }
            val base = url.toHttpUrlOrNull() ?: error("Invalid HLS URL")
            val references = playlist.lineSequence().map(String::trim).filter {
                it.isNotEmpty() && !it.startsWith('#')
            }.toMutableList()
            Regex("""#EXT-X-MAP:.*URI=\"([^\"]+)\"""").find(playlist)?.groupValues?.get(1)?.let {
                references.add(0, it)
            }
            if (references.isEmpty()) error("HLS playlist has no fragments")
            val localNames = linkedMapOf<String, String>()
            references.distinct().forEachIndexed { index, remote ->
                coroutineContext.ensureActive()
                val target = base.resolve(remote)?.toString() ?: error("Invalid HLS fragment")
                val local = "segment-${index.toString().padStart(5, '0')}.m4s"
                execute(target, "HLS fragment").use { response ->
                    check(response, "HLS fragment")
                    val input = response.body?.byteStream() ?: error("Empty HLS fragment")
                    input.use { source ->
                        File(root, local).outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = try {
                                    source.read(buffer)
                                } catch (e: IOException) {
                                    throw networkFailure("HLS fragment", e, response.protocol)
                                }
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                localNames[remote] = local
                onProgress((index + 1).toLong(), references.distinct().size.toLong())
            }
            val localPlaylist = playlist
                .replace(Regex("""(URI=\")([^\"]+)(\")""")) { match ->
                    match.groupValues[1] + (localNames[match.groupValues[2]] ?: match.groupValues[2]) + match.groupValues[3]
                }
                .lineSequence().joinToString("\n") { line -> localNames[line.trim()] ?: line }
            File(root, "playlist.m3u8").writeText(localPlaylist)
            lyrics?.let { File(root, "lyrics.lrc").writeText(it.enhanced ?: it.plain.orEmpty()) }
            artwork?.let { File(root, "cover.${if (it.mime.contains("png")) "png" else "jpg"}").writeBytes(it.bytes) }
            Uri.fromFile(File(root, "playlist.m3u8"))
        } catch (e: Throwable) {
            root.deleteRecursively()
            throw e
        }
    }

    private fun networkFailure(label: String, error: IOException, protocol: Protocol? = null): IOException {
        if (error is javax.net.ssl.SSLException) return error
        return RetryableDownloadException(
            "$label transfer failed: ${error.message ?: error.javaClass.simpleName}",
            useHttp1Only = (protocol == Protocol.HTTP_2 || protocol == null) &&
                error.message.orEmpty().contains("stream was reset", ignoreCase = true),
            cause = error,
        )
    }
}
