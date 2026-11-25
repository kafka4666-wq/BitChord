package com.music.bitchord.data.scrobbling

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object LastFM {
    const val DEFAULT_API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    const val LIBREFM_API_ENDPOINT = "https://libre.fm/2.0/"

    @Serializable
    data class Session(val name: String, val key: String, val subscriber: Int)

    @Serializable
    data class Authentication(val session: Session)

    @Serializable
    data class TokenResponse(val token: String)

    @Serializable
    data class LastFmError(val error: Int, val message: String)

    data class RuntimeConfig(
        val endpoint: String,
        val apiKey: String,
        val secret: String,
        val sessionKey: String?,
    )

    data class RecentTrack(
        val artist: String,
        val title: String,
        val album: String?,
        val timestampSeconds: Long,
    )

    data class RecentTracksPage(
        val tracks: List<RecentTrack>,
        val page: Int,
        val totalPages: Int,
        val total: Long?,
        val skipped: Int = 0,
    )

    @Volatile
    private var runtimeConfig =
        RuntimeConfig(
            endpoint = DEFAULT_API_ENDPOINT,
            apiKey = "",
            secret = "",
            sessionKey = null,
        )

    var sessionKey: String?
        get() = runtimeConfig.sessionKey
        set(value) {
            runtimeConfig = runtimeConfig.copy(sessionKey = value)
        }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    private fun Map<String, String>.apiSig(secret: String): String {
        val sorted = toSortedMap()
        val toHash = sorted.entries.joinToString("") { it.key + it.value } + secret
        val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray())
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun HttpRequestBuilder.lastfmParams(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json",
    ) {
        headers.append(HttpHeaders.UserAgent, "BitChord (https://github.com/kushagrasinghx/BitChord)")
        val paramsForSig =
            mutableMapOf(
                "method" to method,
                "api_key" to apiKey,
            ).apply {
                sessionKey?.let { put("sk", it) }
                putAll(extra)
            }
        val apiSig = paramsForSig.apiSig(secret)

        setBody(
            FormDataContent(
                Parameters.build {
                    paramsForSig.forEach { (key, value) -> append(key, value) }
                    append("api_sig", apiSig)
                    append("format", format)
                },
            ),
        )
    }

    suspend fun getToken() =
        runCatching {
            postAndDecode<TokenResponse>(
                method = "auth.getToken",
            )
        }

    suspend fun getSession(token: String) =
        runCatching {
            postAndDecode<Authentication>(
                method = "auth.getSession",
                extra = mapOf("token" to token),
            )
        }

    fun getAuthUrl(token: String): String {
        val config = runtimeConfig
        return if (config.endpoint == LIBREFM_API_ENDPOINT) {
            "https://libre.fm/api/auth?api_key=${config.apiKey}&token=$token"
        } else {
            "https://www.last.fm/api/auth/?api_key=${config.apiKey}&token=$token"
        }
    }

    suspend fun getMobileSession(
        username: String,
        password: String,
    ) = runCatching {
        postAndDecode<Authentication>(
            method = "auth.getMobileSession",
            extra = mapOf("username" to username, "password" to password),
        )
    }

    /** Reads a public user's scrobble history; Last.fm documents this method as not requiring a session. */
    suspend fun getRecentTracks(
        username: String,
        page: Int,
        limit: Int = 200,
        fromSeconds: Long? = null,
        toSeconds: Long? = null,
    ): RecentTracksPage {
        require(username.isNotBlank()) { "A Last.fm username is required" }
        require(page >= 1) { "Last.fm pages start at one" }
        require(limit in 1..200) { "Last.fm allows at most 200 recent tracks per page" }
        val config = runtimeConfig
        require(config.apiKey.isNotBlank()) { "Last.fm API key is not configured for this build" }
        require(config.endpoint == DEFAULT_API_ENDPOINT) { "History import is supported for Last.fm, not this custom API endpoint" }

        val response = client.get(config.endpoint) {
            headers.append(HttpHeaders.UserAgent, "BitChord (https://github.com/kushagrasinghx/BitChord)")
            url {
                parameters.append("method", "user.getRecentTracks")
                parameters.append("user", username)
                parameters.append("api_key", config.apiKey)
                parameters.append("format", "json")
                parameters.append("page", page.toString())
                parameters.append("limit", limit.toString())
                fromSeconds?.let { parameters.append("from", it.toString()) }
                toSeconds?.let { parameters.append("to", it.toString()) }
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw LastFmException(response.status.value, response.status.description)
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw LastFmException(8, "Last.fm returned an unreadable history response") }
        root["error"]?.let { errorElement ->
            val code = (errorElement as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 8
            val message = root["message"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: "Last.fm could not return this listening history"
            throw LastFmException(code, message)
        }
        val recent = root["recenttracks"] as? JsonObject
            ?: throw LastFmException(8, "Last.fm returned no recent-track history")
        val attrs = recent["@attr"] as? JsonObject
        val actualPage = attrs?.string("page")?.toIntOrNull() ?: page
        val totalPages = attrs?.string("totalPages")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val total = attrs?.string("total")?.toLongOrNull()
        val tracksElement = recent["track"]
        val trackObjects = when (tracksElement) {
            is JsonArray -> tracksElement.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(tracksElement)
            else -> emptyList()
        }
        val tracks = trackObjects.mapNotNull { item ->
            val nowPlaying = (item["@attr"] as? JsonObject)?.string("nowplaying") == "true"
            if (nowPlaying) return@mapNotNull null
            val artist = item["artist"].textOrNestedText()?.trim().orEmpty()
            val title = item["name"].textOrNestedText()?.trim().orEmpty()
            val timestamp = (item["date"] as? JsonObject)?.string("uts")?.toLongOrNull()
            if (artist.isBlank() || title.isBlank() || timestamp == null || timestamp < 0L) return@mapNotNull null
            RecentTrack(
                artist = artist,
                title = title,
                album = item["album"].textOrNestedText()?.trim()?.ifBlank { null },
                timestampSeconds = timestamp,
            )
        }
        return RecentTracksPage(tracks, actualPage, totalPages, total, (trackObjects.size - tracks.size).coerceAtLeast(0))
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun kotlinx.serialization.json.JsonElement?.textOrNestedText(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> this["#text"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        else -> null
    }

    class LastFmException(
        val code: Int,
        override val message: String,
    ) : Exception(message)

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = runCatching {
        postAndRead(
            method = "track.updateNowPlaying",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.let { put("album", it) }
                    trackNumber?.let { put("trackNumber", it.toString()) }
                    duration?.let { put("duration", it.toString()) }
                },
        )
    }

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = runCatching {
        postAndRead(
            method = "track.scrobble",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.let { put("album[0]", it) }
                    trackNumber?.let { put("trackNumber[0]", it.toString()) }
                    duration?.let { put("duration[0]", it.toString()) }
                },
        )
    }

    fun initialize(
        apiKey: String,
        secret: String,
    ) {
        require(apiKey.isNotBlank() && secret.isNotBlank()) {
            "Last.fm API credentials are not configured for this build"
        }
        configure(
            endpoint = runtimeConfig.endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = runtimeConfig.sessionKey,
        )
    }

    fun configure(
        endpoint: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = runtimeConfig.sessionKey,
    ) {
        runtimeConfig =
            RuntimeConfig(
                endpoint = normalizeEndpoint(endpoint),
                apiKey = apiKey,
                secret = secret,
                sessionKey = sessionKey,
            )
    }

    fun currentConfig(): RuntimeConfig = runtimeConfig

    fun isInitialized(): Boolean = runtimeConfig.apiKey.isNotEmpty() && runtimeConfig.secret.isNotEmpty()

    fun normalizeEndpoint(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https")
        require(!uri.host.isNullOrBlank())
        require(uri.query == null && uri.fragment == null)

        val path =
            uri.path
                ?.takeIf { it.isNotBlank() && it != "/" }
                ?.trimEnd('/')
                ?: "/2.0"

        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "$path/",
            null,
            null,
        ).toString()
    }

    private fun requireSessionKey(): String = runtimeConfig.sessionKey ?: throw LastFmException(9, "Session key missing")

    private suspend inline fun <reified T> postAndDecode(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): T =
        json.decodeFromString(
            postAndRead(
                method = method,
                sessionKey = sessionKey,
                extra = extra,
            ),
        )

    private suspend fun postAndRead(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val config = runtimeConfig
        val response =
            client.post(config.endpoint) {
                lastfmParams(
                    method = method,
                    apiKey = config.apiKey,
                    secret = config.secret,
                    sessionKey = sessionKey,
                    extra = extra,
                )
            }

        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw LastFmException(response.status.value, response.status.description)
        }
        if (responseText.contains("\"error\"")) {
            val error = json.decodeFromString<LastFmError>(responseText)
            throw LastFmException(error.error, error.message)
        }
        return responseText
    }

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
}
