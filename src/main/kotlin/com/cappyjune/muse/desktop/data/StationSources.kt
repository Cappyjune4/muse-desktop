package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder

/** Station search across every open source, for the Add Station page. */
object StationSources {
    data class Group(val source: String, val results: List<Entry>, val error: String? = null)

    suspend fun searchAll(q: String): List<Group> = coroutineScope {
        val rb = async { group("radio-browser") { Radio.search(q, 40).map { it.copy(source = "radio-browser") } } }
        val ti = async { group("TuneIn") { TuneIn.search(q) } }
        val gh = async { group("Community list (GitHub)") { Community.search(q) } }
        listOf(rb.await(), ti.await(), gh.await())
    }

    private suspend fun group(name: String, block: suspend () -> List<Entry>) =
        runCatching { Group(name, block()) }.getOrElse { Group(name, emptyList(), it.message ?: "failed") }

    /** Turn a search result into something playable (TuneIn links need one more hop). */
    suspend fun resolve(e: Entry): Entry? = when {
        e.tuneUrl != null -> TuneIn.tune(e)
        e.rbId != null -> Radio.resolve(e)
        else -> e
    }
}

/**
 * TuneIn's public OPML API. It blocks many big-name stations for unofficial apps by
 * serving a "notcompatible" clip, so those are dropped at resolve time.
 */
object TuneIn {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(q: String): List<Entry> = withContext(Dispatchers.IO) {
        val body = Http.json.parseToJsonElement(
            Http.get("https://opml.radiotime.com/Search.ashx?query=${enc(q)}&types=station&render=json")
        ).jsonObject["body"] as? JsonArray ?: return@withContext emptyList()
        body.mapNotNull { it as? JsonObject }
            .filter { it.s("item") == "station" && it.s("URL").isNotBlank() }
            .take(40)
            .map { o ->
                Entry(
                    kind = Entry.Kind.RADIO, id = o.s("URL"), title = o.s("text"),
                    subtitle = listOf(o.s("subtext"), o.s("formats").uppercase(), o.s("bitrate").takeIf { it.isNotBlank() }?.let { "$it kbps" })
                        .filter { !it.isNullOrBlank() }.joinToString(" · "),
                    art = o.s("image"), source = "TuneIn", tuneUrl = o.s("URL"),
                )
            }
    }

    suspend fun tune(e: Entry): Entry? = withContext(Dispatchers.IO) {
        val url = e.tuneUrl ?: return@withContext e
        val body = Http.json.parseToJsonElement(Http.get("$url&render=json")).jsonObject["body"] as? JsonArray
        val stream = body.orEmpty().mapNotNull { (it as? JsonObject)?.s("url") }
            .firstOrNull { it.startsWith("http") && "notcompatible" !in it && "nostream" !in it }
            ?: return@withContext null
        e.copy(id = stream, tuneUrl = null)
    }

    private fun JsonObject.s(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
    private fun JsonArray?.orEmpty() = this ?: emptyList()
}

/**
 * junguler/m3u-radio-music-playlists: a large community playlist scraped from Shoutcast,
 * TuneIn and others. Downloaded at most weekly, cached, and searched here.
 * Links aren't all checked upstream, so some will be dead.
 */
object Community {
    private const val URL = "https://raw.githubusercontent.com/junguler/m3u-radio-music-playlists/main/---everything-full.m3u"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private val lock = Mutex()
    private var parsed: List<Entry>? = null

    suspend fun search(q: String): List<Entry> {
        val all = load()
        val terms = q.lowercase().split(' ').filter { it.isNotBlank() }
        return all.asSequence().filter { e -> terms.all { it in e.title.lowercase() } }.take(40).toList()
    }

    private suspend fun load(): List<Entry> = lock.withLock {
        parsed?.let { return it }
        withContext(Dispatchers.IO) {
            val f = File(Paths.cache, "community-radio.m3u")
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
                runCatching {
                    val tmp = File(Paths.cache, "community-radio.m3u.part")
                    tmp.writeText(Http.get(URL))
                    tmp.renameTo(f)
                }.onFailure { if (!f.exists()) throw it } // stale cache beats nothing
            }
            parse(f.readLines())
        }.also { parsed = it }
    }

    private val logoRe = Regex("""tv[g]?-logo="([^"]*)"""")

    private fun parse(lines: List<String>): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        var name: String? = null
        var logo = ""
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(',').trim().takeIf { it.isNotBlank() }
                    logo = logoRe.find(line)?.groupValues?.get(1).orEmpty()
                }
                line.startsWith("http") -> {
                    val n = name
                    if (n != null && seen.add(line)) out += Entry(
                        kind = Entry.Kind.RADIO, id = line, title = n, subtitle = "Community list",
                        art = logo, source = "Community list (GitHub)",
                    )
                    name = null; logo = ""
                }
            }
        }
        return out
    }
}
