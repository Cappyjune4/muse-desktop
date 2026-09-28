package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

@Serializable
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val art: String = "",
    val description: String = "",
    val lastRefresh: Long = 0,
)

@Serializable
data class Episode(
    val feedUrl: String,
    val guid: String,
    val title: String,
    val audioUrl: String,
    val published: Long = 0,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val art: String = "",
    val notes: String = "",
) {
    val key get() = "$feedUrl|$guid"
}

@Serializable
data class Progress(val positionMs: Long = 0, val durationMs: Long = 0, val played: Boolean = false, val updated: Long = 0)

@Serializable
data class Download(val id: Long, val file: String, val done: Boolean = false, val percent: Int = 0, val failed: Boolean = false)

/**
 * Subscriptions, episodes, listening progress and downloads. Plain JSON files with
 * tmp+rename writes, the same approach as Library. Shared by the UI and the playback
 * service, which saves positions even when the UI is gone.
 */
object Podcasts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The episode loaded in the player, playing or paused; set by PlaybackService. */
    @Volatile var loadedEpisode: String? = null
    private val json get() = Http.json

    private val _subs = MutableStateFlow<List<Podcast>>(emptyList())
    val subscriptions: StateFlow<List<Podcast>> = _subs.asStateFlow()

    private val _episodes = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())
    val episodes: StateFlow<Map<String, List<Episode>>> = _episodes.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val dir = File(Paths.data, "podcasts").apply { mkdirs() }
    private val downloadDir = File(Paths.data, "podcasts/episodes").apply { mkdirs() }

    fun init() {
        _subs.value = read("subscriptions.json", ListSerializer(Podcast.serializer())) ?: emptyList()
        // Episode lists run to megabytes (JRE alone has 2,500+ episodes), so never parse them on the
        // main thread: that blocked app start for ~7s. Small files above load immediately.
        scope.launch {
            val loaded = _subs.value.associate { it.feedUrl to (read(epFile(it.feedUrl), ListSerializer(Episode.serializer())) ?: emptyList()) }
            _episodes.update { current -> loaded + current } // anything refreshed meanwhile wins
        }
        _progress.value = read("progress.json", MapSerializer(String.serializer(), Progress.serializer())) ?: emptyMap()
        _downloads.value = read("downloads.json", MapSerializer(String.serializer(), Download.serializer())) ?: emptyMap()
        // A download cut off by closing muSE goes back in the queue.
        if (_downloads.value.values.any { !it.done && !it.failed }) schedule()
    }

    fun episode(key: String): Episode? = _episodes.value[key.substringBefore('|')]?.firstOrNull { it.key == key } ?: visiting[key]

    /** Episodes of shows you're not subscribed to, opened from a shared link: playable without subscribing. */
    private val visiting = java.util.concurrent.ConcurrentHashMap<String, Episode>()
    fun visit(ep: Episode) { visiting[ep.key] = ep }
    fun podcast(feedUrl: String): Podcast? = _subs.value.firstOrNull { it.feedUrl == feedUrl }

    // --- subscribing & refreshing -------------------------------------------------

    suspend fun subscribe(feedUrl: String): Podcast {
        val (pod, eps) = fetch(feedUrl.trim())
        _subs.update { list -> (list.filterNot { it.feedUrl == pod.feedUrl } + pod).sortedBy { it.title.lowercase() } }
        _episodes.update { it + (pod.feedUrl to eps) }
        write("subscriptions.json", ListSerializer(Podcast.serializer()), _subs.value)
        write(epFile(pod.feedUrl), ListSerializer(Episode.serializer()), eps)
        return pod
    }

    /** A show someone shared: the saved copy if subscribed, otherwise a fresh read of the feed. */
    suspend fun preview(feedUrl: String): Pair<Podcast, List<Episode>> {
        podcast(feedUrl)?.let { p -> _episodes.value[feedUrl]?.let { return p to it } }
        return fetch(feedUrl.trim())
    }

    fun unsubscribe(p: Podcast) {
        _subs.update { list -> list.filterNot { it.feedUrl == p.feedUrl } }
        _episodes.update { it - p.feedUrl }
        write("subscriptions.json", ListSerializer(Podcast.serializer()), _subs.value)
        File(dir, epFile(p.feedUrl)).delete()
        _downloads.value.keys.filter { it.startsWith(p.feedUrl + "|") }.forEach(::deleteDownload)
    }

    /** Refresh every feed, a few at a time. Returns how many feeds failed. */
    suspend fun refreshAll(): Int {
        if (_refreshing.value) return 0
        _refreshing.value = true
        val gate = Semaphore(4)
        val failed = try {
            _subs.value.map { p -> scope.async { gate.withPermit { runCatching { subscribe(p.feedUrl) }.isFailure } } }
                .awaitAll().count { it }
        } finally { _refreshing.value = false }
        return failed
    }

    fun refreshIfStale(maxAgeMs: Long = 60 * 60 * 1000) {
        val oldest = _subs.value.minOfOrNull { it.lastRefresh } ?: return
        if (System.currentTimeMillis() - oldest > maxAgeMs) scope.launch { refreshAll() }
    }

    /** OPML from AntennaPod (or anything else): every outline with an xmlUrl. */
    suspend fun importOpml(input: InputStream): Pair<Int, Int> {
        val urls = mutableListOf<String>()
        input.use {
            val r = xml(it)
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName.equals("outline", true)) {
                    r.getAttributeValue(null, "xmlUrl")?.takeIf { u -> u.startsWith("http") }?.let(urls::add)
                }
            }
        }
        val gate = Semaphore(4)
        val results = urls.distinct().map { u -> scope.async { gate.withPermit { runCatching { subscribe(u) }.isSuccess } } }.awaitAll()
        return results.count { it } to results.count { !it }
    }

    // --- progress -------------------------------------------------------------------

    private var lastWrite = 0L

    fun savePosition(key: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        // Within the last minute (or 97%) counts as finished, as most podcast apps do.
        val done = durationMs > 0 && (positionMs >= durationMs - 60_000 || positionMs >= durationMs * 0.97)
        val wasDone = _progress.value[key]?.played == true
        _progress.update { it + (key to Progress(positionMs, durationMs, done, System.currentTimeMillis())) }
        if (done && !wasDone && Settings.now.autoDeletePlayed && _downloads.value.containsKey(key)) {
            // Only once playback has moved on, so the file isn't pulled from under the player:
            // pausing in the last minute and coming back later must still find it.
            scope.launch {
                delay(90_000)
                while (loadedEpisode == key) delay(30_000)
                if (_progress.value[key]?.played == true) deleteDownload(key)
            }
        }
        val now = System.currentTimeMillis()
        if (force || now - lastWrite > 15_000) { lastWrite = now; persistProgress() }
    }

    fun markPlayed(key: String, played: Boolean) {
        _progress.update { m ->
            val cur = m[key] ?: Progress()
            m + (key to cur.copy(played = played, positionMs = if (played) cur.positionMs else 0, updated = System.currentTimeMillis()))
        }
        persistProgress()
    }

    private fun persistProgress() = write("progress.json", MapSerializer(String.serializer(), Progress.serializer()), _progress.value)

    // --- downloads ------------------------------------------------------------------

    /** Queue an episode; muSE downloads one at a time while it's open (OkHttp follows the long tracking-redirect chains). */
    fun download(ep: Episode) {
        if (_downloads.value[ep.key]?.let { !it.failed } == true) return
        val ext = ep.audioUrl.substringBefore('?').substringAfterLast('.', "mp3").take(4).lowercase()
            .takeIf { it.all(Char::isLetterOrDigit) && it.isNotBlank() } ?: "mp3"
        val file = File(downloadDir, sha1(ep.key) + "." + ext).absolutePath
        _downloads.update { it + (ep.key to Download(0, file)) }
        persistDownloads()
        schedule()
    }

    fun deleteDownload(key: String) {
        val d = _downloads.value[key] ?: return
        File(d.file).delete(); File(d.file + ".part").delete()
        _downloads.update { it - key }
        persistDownloads()
    }

    /** Local file for an episode, if fully downloaded. */
    fun localFile(key: String): File? = _downloads.value[key]?.takeIf { it.done }?.let { File(it.file) }?.takeIf { it.exists() }

    internal fun nextPending(): Pair<String, Download>? = _downloads.value.entries.firstOrNull { !it.value.done && !it.value.failed }?.toPair()
    internal fun updateDownload(key: String, f: (Download) -> Download) {
        _downloads.update { m -> m[key]?.let { m + (key to f(it)) } ?: m }
    }
    internal fun saveDownloads() = persistDownloads()

    private var worker: Job? = null

    @Synchronized private fun schedule() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val (key, d) = nextPending() ?: break
                runCatching { transfer(key, d) }.onFailure {
                    if (_downloads.value.containsKey(key)) updateDownload(key) { it.copy(failed = true) }
                    File(d.file + ".part").delete()
                }
                persistDownloads()
            }
        }
    }

    private fun transfer(key: String, d: Download) {
        val ep = episode(key) ?: error("episode gone")
        val part = File(d.file + ".part")
        Http.client.newCall(okhttp3.Request.Builder().url(ep.audioUrl).build()).execute().use { r ->
            if (!r.isSuccessful) error("Server said ${r.code}")
            val len = r.body.contentLength()
            var got = 0L; var last = -1
            r.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (!_downloads.value.containsKey(key)) error("cancelled")
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        val pct = if (len > 0) (got * 100 / len).toInt() else 0
                        if (pct != last) { last = pct; updateDownload(key) { it.copy(percent = pct) } }
                    }
                }
            }
        }
        if (!_downloads.value.containsKey(key)) { part.delete(); return }
        if (!part.renameTo(File(d.file))) error("Couldn't save the file")
        updateDownload(key) { it.copy(done = true, percent = 100) }
    }

    fun retryDownload(key: String) { updateDownload(key) { it.copy(failed = false, percent = 0) }; persistDownloads(); schedule() }

    /** Bytes used by finished downloads. */
    fun downloadedBytes(): Long = _downloads.value.values.filter { it.done }.sumOf { File(it.file).length() }

    /** Remove downloads of episodes already played. Returns how many. */
    fun deletePlayedDownloads(): Int {
        val keys = _downloads.value.keys.filter { _progress.value[it]?.played == true }
        keys.forEach(::deleteDownload)
        return keys.size
    }

    private fun persistDownloads() = write("downloads.json", MapSerializer(String.serializer(), Download.serializer()), _downloads.value)

    // --- feed parsing -----------------------------------------------------------------

    private suspend fun fetch(feedUrl: String): Pair<Podcast, List<Episode>> = withContext(Dispatchers.IO) {
        Http.client.newCall(okhttp3.Request.Builder().url(feedUrl).build()).execute().use { r ->
            if (!r.isSuccessful) error("Feed returned ${r.code}")
            parse(feedUrl, r.body.byteStream())
        }
    }

    private fun xml(input: InputStream): XMLStreamReader = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        // Feeds never need outside entities or DTDs; not fetching them keeps parsing safe.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }.createXMLStreamReader(input)

    /** "itunes:image", "content:encoded"...: the feed's own prefixed name, as the phone's parser saw it. */
    private fun XMLStreamReader.tag(): String = if (prefix.isNullOrBlank()) localName else "$prefix:$localName"

    private fun parse(feedUrl: String, input: InputStream): Pair<Podcast, List<Episode>> {
        val p = xml(input)
        var title = ""; var author = ""; var art = ""; var desc = ""
        val eps = mutableListOf<Episode>()
        var inItem = false
        var it = mutableMapOf<String, String>()
        var inImage = false

        while (p.hasNext()) {
            when (p.next()) {
                XMLStreamConstants.START_ELEMENT -> when (val n = p.tag()) {
                    "item" -> { inItem = true; it = mutableMapOf() }
                    "image" -> inImage = true
                    "itunes:image" -> p.getAttributeValue(null, "href")?.let { h -> if (inItem) it["art"] = h else if (art.isBlank()) art = h }
                    "enclosure" -> if (inItem && it["url"] == null) {
                        it["url"] = p.getAttributeValue(null, "url").orEmpty()
                        it["length"] = p.getAttributeValue(null, "length").orEmpty()
                    }
                    in TEXT_TAGS -> {
                        val text = runCatching { p.elementText }.getOrDefault("").trim()
                        if (inItem) when (n) {
                            "title" -> it["title"] = text
                            "guid" -> it["guid"] = text
                            "pubDate" -> it["pub"] = text
                            "itunes:duration" -> it["dur"] = text
                            "description", "itunes:summary", "content:encoded" -> if ((it["notes"]?.length ?: 0) < text.length) it["notes"] = text
                        } else when (n) {
                            "title" -> if (!inImage && title.isBlank()) title = text
                            "url" -> if (inImage && art.isBlank()) art = text
                            "itunes:author" -> if (author.isBlank()) author = text
                            "description", "itunes:summary" -> if (desc.isBlank()) desc = text
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> when (p.tag()) {
                    "image" -> inImage = false
                    "item" -> {
                        inItem = false
                        val url = it["url"].orEmpty()
                        if (url.startsWith("http")) eps += Episode(
                            feedUrl = feedUrl, guid = it["guid"]?.takeIf { g -> g.isNotBlank() } ?: url,
                            title = it["title"].orEmpty().ifBlank { "Untitled episode" }, audioUrl = url,
                            published = date(it["pub"]), durationMs = duration(it["dur"]),
                            sizeBytes = it["length"]?.toLongOrNull() ?: 0, art = it["art"].orEmpty(),
                            notes = plain(it["notes"].orEmpty()).take(1500),
                        )
                    }
                }
            }
        }
        val pod = Podcast(feedUrl, title.ifBlank { feedUrl }, author, art, plain(desc).take(1000), System.currentTimeMillis())
        return pod to eps.sortedByDescending { e -> e.published }
    }

    /** Only these hold text; reading text from a container tag would skip its first child. */
    private val TEXT_TAGS = setOf("title", "guid", "pubDate", "itunes:duration", "description", "itunes:summary",
        "content:encoded", "url", "itunes:author")

    private val dateFormats = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
    )

    private fun date(s: String?): Long {
        val t = s?.trim()?.replace(Regex("\\s+"), " ") ?: return 0
        for (f in dateFormats) runCatching { return ZonedDateTime.parse(t, f).toInstant().toEpochMilli() }
        return 0
    }

    /** itunes:duration is seconds, mm:ss or hh:mm:ss. */
    private fun duration(s: String?): Long {
        val parts = s?.trim()?.split(':')?.mapNotNull { it.toDoubleOrNull() } ?: return 0
        return (parts.fold(0.0) { acc, v -> acc * 60 + v } * 1000).toLong()
    }

    /** Show notes are HTML: paragraphs and line breaks kept, tags dropped, entities decoded. */
    private fun plain(html: String): String {
        val text = html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ").replace(Regex("<[^>]+>"), "")
        return unescape(text).replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©")

    private fun unescape(s: String) = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> entities[e]
        } ?: m.value
    }

    // --- storage ----------------------------------------------------------------------

    private fun epFile(feedUrl: String) = "episodes-${sha1(feedUrl)}.json"
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun <T> read(name: String, ser: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { json.decodeFromString(ser, File(dir, name).readText()) }.getOrNull()

    @Synchronized
    private fun <T> write(name: String, ser: kotlinx.serialization.KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.part")
        tmp.writeText(json.encodeToString(ser, value))
        tmp.renameTo(File(dir, name))
    }
}

/** Directory search: Apple's podcast index and fyyd, neither needs a key. */
object PodcastSearch {
    data class Result(val title: String, val author: String, val art: String, val feedUrl: String, val source: String)

    @Serializable private data class ItunesRes(val results: List<ItunesItem> = emptyList())
    @Serializable private data class ItunesItem(val collectionName: String? = null, val artistName: String? = null, val artworkUrl600: String? = null, val feedUrl: String? = null)
    @Serializable private data class FyydRes(val data: List<FyydItem> = emptyList())
    @Serializable private data class FyydItem(val title: String? = null, val author: String? = null, val imgURL: String? = null, val xmlURL: String? = null)

    suspend fun search(q: String): List<Result> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val apple = async {
            runCatching {
                Http.json.decodeFromString<ItunesRes>(Http.get("https://itunes.apple.com/search?media=podcast&limit=30&term=$enc")).results
                    .mapNotNull { r -> r.feedUrl?.let { Result(r.collectionName.orEmpty(), r.artistName.orEmpty(), r.artworkUrl600.orEmpty(), it, "Apple Podcasts") } }
            }.getOrDefault(emptyList())
        }
        val fyyd = async {
            runCatching {
                Http.json.decodeFromString<FyydRes>(Http.get("https://api.fyyd.de/0.2/search/podcast?count=20&title=$enc")).data
                    .mapNotNull { r -> r.xmlURL?.let { Result(r.title.orEmpty(), r.author.orEmpty(), r.imgURL.orEmpty(), it, "fyyd") } }
            }.getOrDefault(emptyList())
        }
        val seen = HashSet<String>()
        (apple.await() + fyyd.await()).filter { seen.add(it.feedUrl.trimEnd('/').lowercase()) }
    }
}

/** Podcast-only playback settings, as on the phone; music and radio always play at normal speed. */
object PodcastPrefs {
    @Serializable private data class Saved(val speed: Float = 1f, val showSpeeds: Map<String, Float> = emptyMap())
    private val file = File(Paths.data, "podcasts/prefs.json")
    private val saved = runCatching { Http.json.decodeFromString(Saved.serializer(), file.readText()) }.getOrDefault(Saved())

    private val _speed = MutableStateFlow(saved.speed)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    /** Per-show speed overrides, Pocket Casts style: feedUrl -> speed. */
    private val _showSpeeds = MutableStateFlow(saved.showSpeeds)
    val showSpeeds: StateFlow<Map<String, Float>> = _showSpeeds.asStateFlow()

    val speeds = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f)

    fun speedFor(feedUrl: String?): Float = feedUrl?.let { _showSpeeds.value[it] } ?: _speed.value
    fun setSpeed(v: Float) { _speed.value = v; save() }
    fun setShowSpeed(feedUrl: String, v: Float?) { _showSpeeds.value = if (v == null) _showSpeeds.value - feedUrl else _showSpeeds.value + (feedUrl to v); save() }
    private fun save() = runCatching { file.parentFile.mkdirs(); file.writeText(Http.json.encodeToString(Saved.serializer(), Saved(_speed.value, _showSpeeds.value))) }
}
