package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Music on this computer: everything under the folders in Settings, with its tags read by
 * ffprobe (remembered until a file changes) and each album's cover from its folder, or from
 * inside its files.
 */
object LocalMusic {
    val EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "ape", "wv", "mka", "alac", "dsf")

    @Serializable
    data class Track(
        val path: String, val modified: Long, val title: String, val artist: String, val album: String, val albumArtist: String,
        val track: Int = 0, val disc: Int = 0, val year: Int = 0, val durationMs: Long = 0,
    ) {
        val folder get() = File(path).parent ?: ""
        /** Albums are told apart by name and album artist, so two "Greatest Hits" don't merge. */
        val albumKey get() = "${albumArtist.lowercase()}|${album.lowercase()}"
    }

    data class Album(val key: String, val title: String, val artist: String, val year: Int, val tracks: List<Track>) {
        val folder get() = tracks.first().folder
    }

    private val _tracks = MutableStateFlow<List<Track>?>(null)
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()
    /** How far the first scan has got: (done, total). */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val cacheFile = File(Paths.cache, "local-music.json")
    private val ser = MapSerializer(String.serializer(), Track.serializer())
    private val cache = ConcurrentHashMap<String, Track>(runCatching { Http.json.decodeFromString(ser, cacheFile.readText()) }.getOrDefault(emptyMap()))

    /** Looks through the folders again; files already read and unchanged aren't read twice. */
    suspend fun scan(roots: List<String>) = withContext(Dispatchers.IO) {
        val files = roots.map(::File).filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }.maxDepth(10)
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS && !it.name.startsWith(".") }.toList()
        }.distinctBy { it.canonicalPath }
        _progress.value = 0 to files.size
        val gate = Semaphore(6)
        var done = 0
        val list = coroutineScope {
            files.map { f ->
                async {
                    gate.withPermit {
                        val t = cache[f.path]?.takeIf { it.modified == f.lastModified() } ?: probe(f)?.also { cache[f.path] = it }
                        synchronized(this@LocalMusic) { done++; if (done % 25 == 0 || done == files.size) _progress.value = done to files.size }
                        t
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // Forget files that are gone.
        cache.keys.retainAll(list.map { it.path }.toSet())
        runCatching { File(cacheFile.path + ".tmp").apply { writeText(Http.json.encodeToString(ser, HashMap(cache))); renameTo(cacheFile) } }
        _tracks.value = list.sortedBy { it.title.lowercase() }
    }

    /** ffprobe's reading of a file's tags; the file name stands in for a missing title. */
    private fun probe(f: File): Track? = runCatching {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags:stream_tags", "-of", "json", f.path)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(20, TimeUnit.SECONDS)
        val root = Http.json.parseToJsonElement(out).jsonObject
        val format = root["format"]?.jsonObject
        // Tags live on the container (MP3, FLAC) or on the audio stream (Ogg, Opus); names vary in case.
        val tags = HashMap<String, String>()
        (root["streams"] as? kotlinx.serialization.json.JsonArray)?.forEach { s ->
            s.jsonObject["tags"]?.jsonObject?.forEach { (k, v) -> tags[k.lowercase()] = v.jsonPrimitive.content }
        }
        format?.get("tags")?.jsonObject?.forEach { (k, v) -> tags[k.lowercase()] = v.jsonPrimitive.content }
        fun num(k: String) = tags[k]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        val artist = tags["artist"]?.ifBlank { null } ?: tags["album_artist"] ?: "Unknown artist"
        Track(
            path = f.path, modified = f.lastModified(),
            title = tags["title"]?.ifBlank { null } ?: f.nameWithoutExtension,
            artist = artist, album = tags["album"]?.ifBlank { null } ?: f.parentFile?.name ?: "",
            albumArtist = tags["album_artist"]?.ifBlank { null } ?: tags["albumartist"]?.ifBlank { null } ?: artist,
            track = num("track"), disc = num("disc"), year = (tags["date"] ?: tags["year"])?.take(4)?.toIntOrNull() ?: 0,
            durationMs = ((format?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
        )
    }.getOrNull()

    fun albums(tracks: List<Track>): List<Album> = tracks.groupBy { it.albumKey }.map { (k, t) ->
        val sorted = t.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
        Album(k, t.first().album, t.first().albumArtist, t.maxOf { it.year }, sorted)
    }.sortedBy { it.title.lowercase() }

    // --- covers ----------------------------------------------------------------------------------

    private val artDir = File(Paths.cache, "local-art").apply { mkdirs() }
    private val covers = ConcurrentHashMap<String, String>()
    private val coverNames = listOf("cover", "folder", "front", "album", "albumart")

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** An album's cover as a file address: a picture in its folder, or the one inside its first file. "" if neither. */
    fun cover(t: Track): String = covers.getOrPut(t.folder + "|" + t.albumKey) {
        val dir = File(t.folder)
        val pics = dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.orEmpty()
        val folderPic = coverNames.firstNotNullOfOrNull { n -> pics.firstOrNull { it.nameWithoutExtension.equals(n, true) } } ?: pics.singleOrNull()
        if (folderPic != null) return@getOrPut folderPic.toURI().toString()
        val out = File(artDir, md5(t.path) + ".jpg")
        if (!out.exists()) runCatching {
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", t.path, "-an", "-frames:v", "1", "-vf", "scale='min(600,iw)':-2", out.path)
                .redirectErrorStream(true).start()
            p.inputStream.readAllBytes(); p.waitFor(20, TimeUnit.SECONDS)
        }
        if (out.length() > 0) out.toURI().toString() else ""
    }

    fun toEntry(t: Track) = Entry(
        kind = Entry.Kind.LOCAL, id = t.path, title = t.title,
        subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
        art = cover(t), durationMs = t.durationMs,
    )
}
