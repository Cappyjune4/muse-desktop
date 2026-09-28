package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ABC stations are curated because their Icecast endpoints 403 and only HLS works,
 * and HLS carries no ICY titles. Track names come from ABC's own now-playing API,
 * the one the Double J website uses. URLs carried over from muSE, all verified.
 */
object Abc {
    /**
     * Square tiles made from each station's official logo, in builds that include them (they're
     * ABC's, so the public build doesn't: those show the plain radio tile until a song's artwork
     * arrives). Unpacked to the cache, as the image loader wants a file.
     */
    fun logo(slug: String): String {
        val f = java.io.File(Paths.cache, "logos/$slug.png")
        if (!f.exists()) runCatching {
            val res = Abc::class.java.getResourceAsStream("/logos/$slug.png") ?: return ""
            f.parentFile.mkdirs()
            res.use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return if (f.exists()) f.toURI().toString() else ""
    }

    private fun station(name: String, slug: String, url: String) = Entry(
        kind = Entry.Kind.RADIO, id = url, title = name, subtitle = "ABC Australia · AAC", abc = slug, art = logo(slug),
    )

    val stations = listOf(
        station("Double J", "doublej", "https://mediaserviceslive.akamaized.net/hls/live/2038342/doublejqld/index.m3u8"),
        station("triple j", "triplej", "https://mediaserviceslive.akamaized.net/hls/live/2038347/triplejqld/masterhq.m3u8"),
        station("ABC Jazz", "jazz", "https://mediaserviceslive.akamaized.net/hls/live/2038319/abcjazz/masterhq.m3u8"),
        station("ABC Classic", "classic", "https://mediaserviceslive.akamaized.net/hls/live/2038316/classicfmnsw/index.m3u8"),
        station("ABC Country", "country", "https://mediaserviceslive.akamaized.net/hls/live/2038322/abccountry/index.m3u8"),
    )

    data class NowPlaying(val title: String, val artist: String, val art: String?)

    suspend fun nowPlaying(slug: String): NowPlaying? = withContext(Dispatchers.IO) {
        val root = Http.json.parseToJsonElement(
            Http.get("https://music.abcradio.net.au/api/v1/plays/$slug/now.json")
        ).jsonObject
        val now = (root["now"] as? JsonObject) ?: root
        val rec = now["recording"] as? JsonObject ?: return@withContext null
        val title = rec.str("title")
        val artist = (rec["artists"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) }
            .joinToString(", ")
        if (title.isBlank() && artist.isBlank()) return@withContext null

        // Artwork hangs off the release, or occasionally the recording itself.
        val holders = listOfNotNull((rec["releases"] as? JsonArray)?.firstOrNull() as? JsonObject, rec)
        val art = holders.firstNotNullOfOrNull { h ->
            (h["artwork"] as? JsonArray)?.firstNotNullOfOrNull { a ->
                val o = a as? JsonObject ?: return@firstNotNullOfOrNull null
                val sizes = (o["sizes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                // A phone wants a bigger rendition than the handheld did.
                sizes.firstOrNull { (it["width"]?.jsonPrimitive?.intOrNull ?: 0) in 500..1200 }?.str("url")
                    ?: o.str("url").takeIf { it.isNotBlank() }
            }
        }
        NowPlaying(title, artist, art)
    }

    private fun JsonObject.str(k: String): String = (this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }).orEmpty()
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
