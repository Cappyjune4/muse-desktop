package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something one muSE can hand another: a Jellyfin item, a station, a show or an episode. */
sealed interface Shared {
    data class Song(val id: String) : Shared
    data class Album(val id: String) : Shared
    data class Playlist(val id: String) : Shared
    data class Artist(val id: String) : Shared
    /** A full entry (ABC, or a station typed in by hand), or a radio-browser id to look up. */
    data class Station(val entry: Entry?, val rbId: String? = null) : Shared
    data class Show(val feedUrl: String) : Shared
    /** [tag] is [Share.tag] of the guid: guids can be whole URLs, too long for a link. */
    data class Episode(val feedUrl: String, val tag: String) : Shared
}

/**
 * Share links are plain https on a web address you own (set in Settings; the phone apps use the
 * same one), so Signal and friends make them tappable, and short: https://example.com/s/album/rlThiFRLRuNR084VY7-XCA. Jellyfin links carry
 * only the item id (32 hex digits packed into 22 characters), never the server or a token;
 * both phones are signed in to the same server already. Podcasts carry their feed address.
 * The page at /s/ is the fallback when the link opens in the browser; on the desktop, paste the
 * link into muSE (or run `muse <link>`).
 */
object Share {
    /** The web address links are made on; blank means sharing is off (opening links still works). */
    val host: String get() = Settings.now.shareHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    val enabled get() = host.isNotEmpty()
    private val BASE get() = "https://$host/s/"
    private val ANY = Regex("^https://([A-Za-z0-9.-]+)/s/(song|album|playlist|artist|station|show|episode)/", RegexOption.IGNORE_CASE)

    /**
     * What's showing from a link, newest last: a link that arrives starts afresh, and an album
     * opened from a shared artist goes on top, so Back returns to the artist.
     */
    private val stack = MutableStateFlow<List<Shared>>(emptyList())
    val incoming: StateFlow<List<Shared>> = stack.asStateFlow()
    /** A link pasted in, or handed over by "muse <link>" (a second launch passes it to the open window). */
    fun receive(url: String): Boolean = parse(url.trim())?.let { stack.value = listOf(it); true } ?: false
    fun open(s: Shared) { stack.value = stack.value + s }
    fun back() { stack.value = stack.value.dropLast(1) }
    fun close() { stack.value = emptyList() }

    fun link(s: Shared): String = BASE + when (s) {
        is Shared.Song -> "song/" + pack(s.id)
        is Shared.Album -> "album/" + pack(s.id)
        is Shared.Playlist -> "playlist/" + pack(s.id)
        is Shared.Artist -> "artist/" + pack(s.id)
        is Shared.Station -> {
            val e = s.entry
            when {
                e?.abc != null -> "station/abc/${e.abc}"
                (e?.rbId ?: s.rbId) != null -> "station/" + pack(e?.rbId ?: s.rbId!!)
                // Typed in by hand: nothing to look up, so the station itself goes along, squeezed.
                else -> "station/x/" + b64(deflate(Http.json.encodeToString(Entry.serializer(),
                    Entry(Entry.Kind.RADIO, e!!.id, e.title, art = e.art.takeIf { it.startsWith("http") }.orEmpty(), tuneUrl = e.tuneUrl))))
            }
        }
        is Shared.Show -> "show/" + bare(s.feedUrl)
        is Shared.Episode -> "episode/${s.tag}/" + bare(s.feedUrl)
    }

    /** Plain string handling (no android.net.Uri), so it's unit-tested on the desktop JVM. */
    fun parse(url: String): Shared? {
        // Your own address, or (before one's set) any address with muSE's link shape.
        val prefix = when {
            enabled && url.startsWith(BASE, ignoreCase = true) -> BASE
            !enabled -> ANY.find(url)?.let { "https://${it.groupValues[1]}/s/" } ?: return null
            else -> return null
        }
        val rest = url.substring(prefix.length).substringBefore('#').takeIf { it.isNotEmpty() } ?: return null
        val kind = rest.substringBefore('/')
        val arg = rest.substringAfter('/', "")
        // Ids never contain / or ?, so a trailing slash or an appended ?utm=... is ignored.
        fun jf() = unpack(arg.substringBefore('/').substringBefore('?'))?.takeIf { it.length == 32 }
        return when (kind) {
            "song" -> jf()?.let(Shared::Song)
            "album" -> jf()?.let(Shared::Album)
            "playlist" -> jf()?.let(Shared::Playlist)
            "artist" -> jf()?.let(Shared::Artist)
            "station" -> when {
                arg.startsWith("abc/") -> arg.removePrefix("abc/").substringBefore('/').substringBefore('?')
                    .let { slug -> Abc.stations.firstOrNull { it.abc == slug } }?.let { Shared.Station(it) }
                arg.startsWith("x/") -> runCatching { Http.json.decodeFromString(Entry.serializer(), inflate(unb64(arg.removePrefix("x/").substringBefore('/').substringBefore('?')))) }
                    .getOrNull()?.takeIf { it.kind == Entry.Kind.RADIO && it.id.startsWith("http") }?.let { Shared.Station(it) }
                else -> unpack(arg.substringBefore('/').substringBefore('?'))?.takeIf { it.length == 32 }?.let { h ->
                    Shared.Station(null, "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}")
                }
            }
            "show" -> feed(arg)?.let(Shared::Show)
            "episode" -> arg.substringBefore('/').takeIf { it.length == 8 }?.let { t -> feed(arg.substringAfter('/', ""))?.let { Shared.Episode(it, t) } }
            else -> null
        }
    }

    /** A short, stable tag for an episode guid. */
    fun tag(guid: String): String = b64(java.security.MessageDigest.getInstance("SHA-256").digest(guid.toByteArray())).take(8)

    /** The link and what it is, ready to paste into Signal or anywhere else. */
    fun text(s: Shared, name: String, detail: String = ""): String =
        listOf(listOf(name, detail).filter { it.isNotBlank() }.joinToString(" · "), link(s)).filter { it.isNotBlank() }.joinToString("\n")

    /** Copies the link to the clipboard (the desktop's share sheet). */
    fun copy(s: Shared, name: String, detail: String = "") {
        if (!enabled) return
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text(s, name, detail)), null)
    }

    /** What an entry from a list or the player shares as, if it can be shared at all. */
    fun of(e: Entry): Shared? = when (e.kind) {
        Entry.Kind.MUSIC -> Shared.Song(e.id)
        Entry.Kind.RADIO -> Shared.Station(e)
        Entry.Kind.PODCAST -> e.id.split('|', limit = 2).takeIf { it.size == 2 }?.let { Shared.Episode(it[0], tag(it[1])) }
        Entry.Kind.LOCAL -> null // a file on this phone only
    }

    fun copy(e: Entry): Boolean = if (!enabled) false else of(e)?.let { copy(it, e.title, e.subtitle); true } ?: false

    /** A muSE link on the clipboard, if there is one. */
    fun fromClipboard(): String? = runCatching {
        (java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)
            ?.let { t -> Regex("https://\\S+/s/\\S+").findAll(t).map { it.value }.firstOrNull { parse(it) != null } }
    }.getOrNull()

    private fun b64(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)

    /** 32 hex digits (a Jellyfin id, or a uuid without its dashes) as 22 characters, and back. */
    private fun pack(id: String): String {
        val hex = id.replace("-", "")
        return b64(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
    private fun unpack(s: String): String? = runCatching { unb64(s).joinToString("") { "%02x".format(it) } }.getOrNull()

    /** Feeds are almost always https, so the link leaves that off; an http feed keeps its scheme. */
    private fun bare(feed: String) = feed.removePrefix("https://")
    private fun feed(s: String) = s.takeIf { it.contains('.') }?.let { if (it.startsWith("http://")) it else "https://$it" }

    private fun deflate(s: String): ByteArray {
        val d = java.util.zip.Deflater(9, true).apply { setInput(s.toByteArray()); finish() }
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }
    private fun inflate(b: ByteArray): String {
        val i = java.util.zip.Inflater(true).apply { setInput(b) }
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        // A damaged link must not loop forever or unpack into something huge.
        while (!i.finished() && out.size() < 16_384) { val n = i.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
        i.end(); return String(out.toByteArray())
    }
}
