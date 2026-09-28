package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLEncoder

enum class CategoryIcon { STAR, GLOBE, NEWS, SPORT, MUSIC, CLOCK }

data class Category(val name: String, val icon: CategoryIcon, val kind: String, val arg: String = "")

object Radio {
    private const val BASE = "https://de1.api.radio-browser.info/json"
    private const val COMMON = "hidebroken=true&order=clickcount&reverse=true"

    /**
     * Same curated list as muSE. Genres are hand-picked because radio-browser's
     * top tags are full of junk and duplicate language variants.
     */
    val categories = listOf(
        Category("My Stations", CategoryIcon.STAR, "custom"),
        Category("ABC Radio", CategoryIcon.STAR, "abc"),
        Category("Popular", CategoryIcon.STAR, "top"),
        Category("Australia", CategoryIcon.GLOBE, "country", "AU"),
        Category("New Zealand", CategoryIcon.GLOBE, "country", "NZ"),
        Category("UK", CategoryIcon.GLOBE, "country", "GB"),
        Category("USA", CategoryIcon.GLOBE, "country", "US"),
        Category("Ireland", CategoryIcon.GLOBE, "country", "IE"),
        Category("Canada", CategoryIcon.GLOBE, "country", "CA"),
        Category("Germany", CategoryIcon.GLOBE, "country", "DE"),
        Category("France", CategoryIcon.GLOBE, "country", "FR"),
        Category("Italy", CategoryIcon.GLOBE, "country", "IT"),
        Category("Japan", CategoryIcon.GLOBE, "country", "JP"),
        Category("News", CategoryIcon.NEWS, "tag", "news"),
        Category("Talk", CategoryIcon.NEWS, "tag", "talk"),
        Category("Sport", CategoryIcon.SPORT, "tag", "sports"),
        Category("Pop", CategoryIcon.MUSIC, "tag", "pop"),
        Category("Rock", CategoryIcon.MUSIC, "tag", "rock"),
        Category("Indie", CategoryIcon.MUSIC, "tag", "indie"),
        Category("Metal", CategoryIcon.MUSIC, "tag", "metal"),
        Category("Jazz", CategoryIcon.MUSIC, "tag", "jazz"),
        Category("Blues", CategoryIcon.MUSIC, "tag", "blues"),
        Category("Classical", CategoryIcon.MUSIC, "tag", "classical"),
        Category("Dance", CategoryIcon.MUSIC, "tag", "dance"),
        Category("Electronic", CategoryIcon.MUSIC, "tag", "electronic"),
        Category("Hip Hop", CategoryIcon.MUSIC, "tag", "hip hop"),
        Category("Reggae", CategoryIcon.MUSIC, "tag", "reggae"),
        Category("Country", CategoryIcon.MUSIC, "tag", "country"),
        Category("Soul", CategoryIcon.MUSIC, "tag", "soul"),
        Category("Oldies", CategoryIcon.CLOCK, "tag", "oldies"),
        Category("Chillout", CategoryIcon.MUSIC, "tag", "chillout"),
        Category("Folk", CategoryIcon.MUSIC, "tag", "folk"),
    )

    @Serializable
    private data class RbStation(
        val stationuuid: String? = null,
        val name: String? = null,
        val url: String? = null,
        val url_resolved: String? = null,
        val favicon: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        val country: String? = null,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun stations(category: Category, limit: Int = 150): List<Entry> = when (category.kind) {
        "abc" -> Abc.stations
        "top" -> fetch("$BASE/stations/topclick/$limit")
        "country" -> fetch("$BASE/stations/bycountrycodeexact/${enc(category.arg)}?limit=$limit&$COMMON")
        "tag" -> fetch("$BASE/stations/bytagexact/${enc(category.arg)}?limit=$limit&$COMMON")
        else -> emptyList()
    }

    suspend fun search(text: String, limit: Int = 100): List<Entry> =
        fetch("$BASE/stations/search?name=${enc(text)}&limit=$limit&$COMMON")

    @Serializable
    private data class RbClick(val ok: Boolean = false, val url: String? = null)

    /**
     * Ask radio-browser for the station's current stream. /url/{uuid} is also how the
     * directory counts plays, so this is the call it asks clients to make on play.
     * Falls back to the saved URL if the directory is unreachable.
     */
    suspend fun resolve(e: Entry): Entry {
        val id = e.rbId ?: return e
        return runCatching {
            withContext(Dispatchers.IO) {
                val r = Http.json.decodeFromString<RbClick>(Http.get("$BASE/url/${enc(id)}"))
                r.url?.trim()?.takeIf { r.ok && it.startsWith("http") }?.let { e.copy(id = it) } ?: e
            }
        }.getOrDefault(e)
    }

    /** One station by its radio-browser uuid, for a shared link. */
    suspend fun byUuid(uuid: String): Entry? = fetch("$BASE/stations/byuuid/${enc(uuid)}").firstOrNull()

    private suspend fun fetch(url: String): List<Entry> = withContext(Dispatchers.IO) {
        val list = Http.json.decodeFromString<List<RbStation>>(Http.get(url))
        val seen = HashSet<String>()
        list.mapNotNull { s ->
            val u = (s.url_resolved ?: s.url)?.trim().orEmpty()
            val name = s.name?.split(Regex("\\s+"))?.joinToString(" ")?.trim().orEmpty()
            // The directory has many near-duplicates of the same stream.
            if (u.isEmpty() || name.isEmpty() || !seen.add(u)) return@mapNotNull null
            Entry(
                kind = Entry.Kind.RADIO, id = u, title = name,
                subtitle = listOfNotNull(
                    s.country?.takeIf { it.isNotBlank() },
                    s.codec?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
                    s.bitrate?.takeIf { it > 0 }?.let { "$it kbps" },
                ).joinToString(" · "),
                art = s.favicon?.trim().orEmpty(),
                codec = s.codec.orEmpty(), bitrate = s.bitrate ?: 0,
                rbId = s.stationuuid?.takeIf { it.isNotBlank() },
            )
        }
    }
}
