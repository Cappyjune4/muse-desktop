package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class JellyfinAccount(val server: String, val userId: String, val userName: String, val token: String)

@Serializable
data class JfItem(
    val Id: String,
    val Name: String? = null,
    val Type: String? = null,
    val MediaType: String? = null,
    val AlbumArtist: String? = null,
    val Artists: List<String>? = null,
    val Album: String? = null,
    val AlbumId: String? = null,
    val RunTimeTicks: Long? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val ProductionYear: Int? = null,
    val ChildCount: Int? = null,
    val ImageTags: Map<String, String>? = null,
    val AlbumPrimaryImageTag: String? = null,
    /** ReplayGain track gain in dB (Jellyfin 10.9+), used for volume levelling. */
    val NormalizationGain: Double? = null,
    val UserData: JfUserData? = null,
    /** Set on playlist entries; needed to remove one entry from a playlist. */
    val PlaylistItemId: String? = null,
)

@Serializable
data class JfUserData(val IsFavorite: Boolean = false, val PlayCount: Int = 0)

@Serializable
private data class JfItems(val Items: List<JfItem> = emptyList(), val TotalRecordCount: Int = 0)

/**
 * Jellyfin client. The account (with its token) lives only in the keyring and in the
 * Authorization header, which Http adds for requests to the signed-in server. It never goes in
 * a URL, so it can't leak into logs or mpv's stream metadata.
 */
object Jellyfin {
    private const val CLIENT = "muSE Desktop"
    private const val VERSION = "0.1.0"
    private val JSON = "application/json".toMediaType()

    private val _account = MutableStateFlow<JellyfinAccount?>(null)
    val account: StateFlow<JellyfinAccount?> = _account.asStateFlow()
    val deviceId: String get() = Settings.now.deviceId
    /** What Jellyfin's dashboard calls this device: the computer's name. */
    private val deviceName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.ifBlank { null } ?: "Ubuntu"

    fun init() {
        _account.value = Keyring.get()?.let {
            runCatching { Http.json.decodeFromString(JellyfinAccount.serializer(), it) }.getOrNull()
        }
    }

    /** Header value; with no token it still identifies the client, as sign-in requires. */
    fun authHeader(token: String? = _account.value?.token): String =
        "MediaBrowser Client=\"$CLIENT\", Device=\"$deviceName\", DeviceId=\"$deviceId\", Version=\"$VERSION\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    /** True when a request is going to the signed-in server, so it should carry the token. */
    fun owns(url: okhttp3.HttpUrl): Boolean {
        val s = _account.value?.server?.toHttpUrlOrNull() ?: return false
        return url.host == s.host && url.port == s.port
    }

    fun normalise(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    // --- sign in ---------------------------------------------------------------------

    @Serializable private data class AuthResult(val AccessToken: String, val User: AuthUser)
    @Serializable private data class AuthUser(val Id: String, val Name: String)

    suspend fun signIn(server: String, user: String, password: String) {
        val base = normalise(server)
        val body = buildJsonObject { put("Username", user); put("Pw", password) }.toString()
        val res = post(base, "/Users/AuthenticateByName", body, token = null)
        save(base, Http.json.decodeFromString(AuthResult.serializer(), res))
    }

    @Serializable data class QuickConnect(val Code: String, val Secret: String, val Authenticated: Boolean = false)

    suspend fun quickConnectStart(server: String): QuickConnect =
        Http.json.decodeFromString(QuickConnect.serializer(), post(normalise(server), "/QuickConnect/Initiate", "", token = null))

    suspend fun quickConnectApproved(server: String, secret: String): Boolean =
        Http.json.decodeFromString(QuickConnect.serializer(), get(normalise(server), "/QuickConnect/Connect?secret=$secret", token = null)).Authenticated

    suspend fun quickConnectFinish(server: String, secret: String) {
        val base = normalise(server)
        val res = post(base, "/Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", secret) }.toString(), token = null)
        save(base, Http.json.decodeFromString(AuthResult.serializer(), res))
    }

    private fun save(server: String, r: AuthResult) {
        val acct = JellyfinAccount(server, r.User.Id, r.User.Name, r.AccessToken)
        Keyring.put(Http.json.encodeToString(JellyfinAccount.serializer(), acct))
        _account.value = acct
    }

    suspend fun signOut() {
        val a = _account.value ?: return
        runCatching { post(a.server, "/Sessions/Logout", "", a.token) } // revokes the token server-side
        Keyring.put(null)
        _account.value = null
    }

    // --- library ---------------------------------------------------------------------

    internal fun acct() = _account.value ?: error("Not signed in")
    private const val FIELDS = "Fields=PrimaryImageAspectRatio,ChildCount,ProductionYear,NormalizationGain&EnableImageTypes=Primary&EnableUserData=true"

    private suspend fun items(query: String): List<JfItem> {
        val a = acct()
        return Http.json.decodeFromString(JfItems.serializer(), get(a.server, "/Items?userId=${a.userId}&$FIELDS&$query", a.token)).Items
    }

    suspend fun albums() = items("IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=SortName&SortOrder=Ascending&Limit=5000")
    suspend fun recentAlbums() = items("IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=60")
    /** One page of every song A-Z, plus the total so the list knows when to stop. */
    suspend fun songsPage(start: Int, limit: Int = 200): Pair<List<JfItem>, Int> {
        val a = acct()
        val r = Http.json.decodeFromString(JfItems.serializer(), get(a.server,
            "/Items?userId=${a.userId}&$FIELDS&IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&SortOrder=Ascending&StartIndex=$start&Limit=$limit", a.token))
        return r.Items to r.TotalRecordCount
    }
    // --- Home: what Plexamp and Finamp lead with ---
    suspend fun recentlyPlayed() = items("IncludeItemTypes=Audio&Recursive=true&Filters=IsPlayed&SortBy=DatePlayed&SortOrder=Descending&Limit=30")
    suspend fun mostPlayed() = items("IncludeItemTypes=Audio&Recursive=true&Filters=IsPlayed&SortBy=PlayCount,SortName&SortOrder=Descending&Limit=30")
    suspend fun favouriteSongs() = items("IncludeItemTypes=Audio&Recursive=true&Filters=IsFavorite&SortBy=SortName&Limit=200")
    suspend fun favouriteAlbums() = items("IncludeItemTypes=MusicAlbum&Recursive=true&Filters=IsFavorite&SortBy=SortName&Limit=100")
    suspend fun rediscover() = items("IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=Random&Limit=20")

    /** Favourites live on the server, so they show in every Jellyfin app. */
    suspend fun setFavourite(itemId: String, favourite: Boolean) {
        val a = acct()
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url("${a.server}/UserFavoriteItems/$itemId?userId=${a.userId}").header("Authorization", authHeader())
            Http.client.newCall(if (favourite) b.post(ByteArray(0).toRequestBody(null)).build() else b.delete().build()).execute().close()
        }
    }

    suspend fun isFavourite(itemId: String): Boolean {
        val a = acct()
        return runCatching {
            Http.json.decodeFromString(JfItem.serializer(), get(a.server, "/Items/$itemId?userId=${a.userId}&EnableUserData=true", a.token)).UserData?.IsFavorite == true
        }.getOrDefault(false)
    }

    // --- Playlists: real Jellyfin playlists, so they show in every Jellyfin app ---

    @Serializable private data class Created(val Id: String)

    /** Create a playlist, optionally with songs already in it. Returns the new playlist's id. */
    /** Bumped on every playlist edit so open playlist lists reload. */
    val playlistsChanged = kotlinx.coroutines.flow.MutableStateFlow(0)

    suspend fun createPlaylist(name: String, itemIds: List<String>): String {
        val a = acct()
        val body = kotlinx.serialization.json.buildJsonObject {
            put("Name", name); put("UserId", a.userId); put("MediaType", "Audio")
            put("Ids", kotlinx.serialization.json.JsonArray(itemIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        return Http.json.decodeFromString(Created.serializer(), post(a.server, "/Playlists", body, a.token)).Id
            .also { playlistsChanged.value++ }
    }

    suspend fun addToPlaylist(playlistId: String, itemIds: List<String>) {
        val a = acct()
        // Large queues go in batches to keep the URL a sane length.
        itemIds.chunked(100).forEach { chunk ->
            post(a.server, "/Playlists/$playlistId/Items?userId=${a.userId}&ids=${chunk.joinToString(",")}", "", a.token)
        }
        playlistsChanged.value++
    }

    suspend fun removeFromPlaylist(playlistId: String, entryIds: List<String>) {
        val a = acct()
        withContext(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url("${a.server}/Playlists/$playlistId/Items?entryIds=${entryIds.joinToString(",")}")
                .header("Authorization", authHeader()).delete().build()).execute().use { if (!it.isSuccessful) error("Server said ${it.code}") }
        }
        playlistsChanged.value++
    }

    suspend fun deletePlaylist(playlistId: String) {
        val a = acct()
        withContext(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url("${a.server}/Items/$playlistId").header("Authorization", authHeader()).delete().build())
                .execute().use { if (!it.isSuccessful) error("Server said ${it.code}") }
        }
        playlistsChanged.value++
    }

    suspend fun randomSongs() = items("IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=300")
    // No MediaTypes=Audio filter: freshly made playlists have no media type until a library
    // scan, so the server would hide them. Drop video playlists here instead.
    /** One item by id, as this user sees it; null if it's gone or their account can't see it. */
    suspend fun item(id: String): JfItem? = items("Ids=$id").firstOrNull()

    suspend fun playlists() = items("IncludeItemTypes=Playlist&Recursive=true&SortBy=SortName")
        .filter { it.MediaType != "Video" }
    suspend fun albumTracks(albumId: String) = items("ParentId=$albumId&IncludeItemTypes=Audio&Recursive=true&SortBy=ParentIndexNumber,IndexNumber,SortName")
    suspend fun artistAlbums(artistId: String) = items("ArtistIds=$artistId&IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=ProductionYear,SortName&SortOrder=Descending")
    suspend fun artistTracks(artistId: String) = items("ArtistIds=$artistId&IncludeItemTypes=Audio&Recursive=true&SortBy=Random&Limit=200")
    /**
     * Search the way music apps do: name matches, plus the albums and songs of any artist whose
     * name matches ("hilltop" should find Hilltop Hoods' albums, not just the artist).
     */
    suspend fun searchAll(q: String): List<JfItem> = kotlinx.coroutines.coroutineScope {
        val base = search(q)
        val artists = base.filter { it.Type == "MusicArtist" }.take(2)
        val extra = artists.map { a ->
            async {
                runCatching {
                    artistAlbums(a.Id) + items("ArtistIds=${a.Id}&IncludeItemTypes=Audio&Recursive=true&SortBy=PlayCount,SortName&SortOrder=Descending&Limit=25")
                }.getOrDefault(emptyList())
            }
        }.flatMap { it.await() }
        val seen = HashSet<String>()
        (base + extra).filter { seen.add(it.Id) }
    }

    suspend fun search(q: String) = items("searchTerm=${java.net.URLEncoder.encode(q, "UTF-8")}&IncludeItemTypes=MusicAlbum,Audio,MusicArtist&Recursive=true&Limit=60")

    suspend fun artists(): List<JfItem> {
        val a = acct()
        return Http.json.decodeFromString(JfItems.serializer(),
            get(a.server, "/Artists/AlbumArtists?userId=${a.userId}&$FIELDS&SortBy=SortName&Limit=5000", a.token)).Items
    }

    suspend fun playlistTracks(id: String): List<JfItem> {
        val a = acct()
        return Http.json.decodeFromString(JfItems.serializer(),
            get(a.server, "/Playlists/$id/Items?userId=${a.userId}&$FIELDS", a.token)).Items
    }

    /** Jellyfin's Instant Mix: similar songs seeded from a track, album or artist. */
    suspend fun instantMix(itemId: String): List<JfItem> {
        val a = acct()
        return Http.json.decodeFromString(JfItems.serializer(),
            get(a.server, "/Items/$itemId/InstantMix?userId=${a.userId}&limit=100&$FIELDS", a.token)).Items
    }

    @Serializable data class LyricLine(val Text: String = "", val Start: Long? = null)
    @Serializable private data class LyricsDto(val Lyrics: List<LyricLine> = emptyList())

    /** Lyrics if the server has them (e.g. via the LrcLib plugin); timed lines when synced. */
    suspend fun lyrics(itemId: String): List<LyricLine> {
        val a = _account.value ?: return emptyList()
        return runCatching {
            Http.json.decodeFromString(LyricsDto.serializer(), get(a.server, "/Audio/$itemId/Lyrics", a.token)).Lyrics
        }.getOrDefault(emptyList())
    }

    fun imageUrl(item: JfItem, size: Int = 600): String? {
        val a = _account.value ?: return null
        val id = when {
            item.ImageTags?.containsKey("Primary") == true -> item.Id
            item.AlbumPrimaryImageTag != null && item.AlbumId != null -> item.AlbumId
            else -> return null
        }
        return "${a.server}/Items/$id/Images/Primary?maxWidth=$size&quality=90"
    }

    /** Direct file stream; the Authorization header, not the URL, authorises it. */
    fun streamUrl(itemId: String): String = "${acct().server}/Audio/$itemId/stream?static=true"

    /** Quick reachability check for offline mode: /System/Ping with a short timeout. */
    suspend fun reachable(): Boolean {
        val a = _account.value ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url("${a.server}/System/Ping").build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    suspend fun allArtistTracks(artistId: String) = items("ArtistIds=$artistId&IncludeItemTypes=Audio&Recursive=true&SortBy=Album,ParentIndexNumber,IndexNumber&Limit=2000")

    fun toEntry(t: JfItem) = Entry(
        kind = Entry.Kind.MUSIC, id = t.Id, title = t.Name.orEmpty(),
        subtitle = listOfNotNull(t.Artists?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: t.AlbumArtist, t.Album)
            .filter { it.isNotBlank() }.joinToString(" · "),
        art = imageUrl(t).orEmpty(),
        durationMs = (t.RunTimeTicks ?: 0) / 10_000,
        gainDb = t.NormalizationGain,
    )

    // --- playback reporting, so Jellyfin's play counts and "recently played" stay right ---

    suspend fun reportStart(itemId: String) = report("/Sessions/Playing", itemId, 0)
    suspend fun reportStop(itemId: String, positionMs: Long) = report("/Sessions/Playing/Stopped", itemId, positionMs)

    private suspend fun report(path: String, itemId: String, positionMs: Long) {
        val a = _account.value ?: return
        runCatching {
            post(a.server, path, buildJsonObject {
                put("ItemId", itemId); put("PositionTicks", positionMs * 10_000); put("CanSeek", true); put("PlayMethod", "DirectStream")
            }.toString(), a.token)
        }
    }

    // --- http ------------------------------------------------------------------------

    internal suspend fun get(base: String, path: String, token: String?): String = call(
        Request.Builder().url(base + path).header("Authorization", authHeader(token)).build()
    )

    internal suspend fun post(base: String, path: String, json: String, token: String?): String = call(
        Request.Builder().url(base + path).header("Authorization", authHeader(token)).post(json.toRequestBody(JSON)).build()
    )

    /** DELETE (or any method) with an optional JSON body, for favourites, watched and admin actions. */
    internal suspend fun send(method: String, path: String, json: String? = null): String {
        val a = acct()
        val body = json?.toRequestBody(JSON) ?: if (method == "POST") "".toRequestBody(JSON) else null
        return call(Request.Builder().url(a.server + path).header("Authorization", authHeader(a.token)).method(method, body).build())
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        Http.client.newCall(req).execute().use { r ->
            when {
                r.code == 401 -> error("Wrong username or password, or the session has expired")
                !r.isSuccessful -> error("Server said ${r.code}")
                else -> r.body.string()
            }
        }
    }
}
