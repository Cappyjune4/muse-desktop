package com.cappyjune.muse.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Favourites, history and hand-added stations, kept as small JSON files.
 * Writes go through a temp file + rename, like muSE's .part files, so a crash
 * mid-write can't leave a half-written list.
 */
object Library {
    private val dir = Paths.data
    // Must be declared before the flows below: their initialisers call load(), and Kotlin runs
    // property initialisers in order. Declared after them it was still null, load() failed
    // silently, and every launch started with empty lists that the next save then overwrote.
    private val serializer = ListSerializer(Entry.serializer())

    private val _favourites = MutableStateFlow(load("favourites.json"))
    val favourites: StateFlow<List<Entry>> = _favourites.asStateFlow()

    private val _history = MutableStateFlow(load("history.json"))
    val history: StateFlow<List<Entry>> = _history.asStateFlow()

    private val _custom = MutableStateFlow(load("custom_stations.json"))
    val custom: StateFlow<List<Entry>> = _custom.asStateFlow()

    fun isFavourite(key: String) = _favourites.value.any { it.key == key }

    fun toggleFavourite(e: Entry) = _favourites.update { list ->
        (if (list.any { it.key == e.key }) list.filterNot { it.key == e.key } else list + e.copy(playedAt = 0))
            .also { save("favourites.json", it) }
    }

    /** Keep a saved favourite pointing at a station's current stream. */
    fun refresh(e: Entry) = _favourites.update { list ->
        if (list.none { it.key == e.key && it.id != e.id }) list
        else list.map { if (it.key == e.key) e.copy(playedAt = 0) else it }.also { save("favourites.json", it) }
    }

    /** Most recent first; a repeat moves to the top. Capped like muSE's history. */
    fun recordPlay(e: Entry) = _history.update { list ->
        (listOf(e.copy(playedAt = System.currentTimeMillis())) + list.filterNot { it.key == e.key })
            .take(HISTORY_CAP).also { save("history.json", it) }
    }

    fun clearHistory() = _history.update { emptyList<Entry>().also { save("history.json", it) } }

    fun addCustom(name: String, url: String, logo: String) = _custom.update { list ->
        val e = Entry(Entry.Kind.RADIO, url.trim(), name.trim(), "My station", logo.trim())
        (list.filterNot { it.key == e.key } + e).also { save("custom_stations.json", it) }
    }

    /** Save a search result, keeping its radio-browser ID so it can heal if the stream moves. */
    fun addCustom(e: Entry) = _custom.update { list ->
        val saved = e.copy(subtitle = listOf("My station", e.source).filter { it.isNotBlank() }.joinToString(" · "),
            source = "", tuneUrl = null, playedAt = 0)
        (list.filterNot { it.key == saved.key } + saved).also { save("custom_stations.json", it) }
    }

    fun isCustom(key: String) = _custom.value.any { it.key == key }

    fun removeCustom(e: Entry) = _custom.update { list ->
        list.filterNot { it.key == e.key }.also { save("custom_stations.json", it) }
    }

    private fun load(name: String): List<Entry> = runCatching {
        Http.json.decodeFromString(serializer, File(dir, name).readText())
            // Saved ABC entries pick up the current station tile instead of whatever art they were saved with.
            .map { e -> e.abc?.let { e.copy(art = Abc.logo(it)) } ?: e }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(name: String, list: List<Entry>) {
        val tmp = File(dir, "$name.part")
        tmp.writeText(Http.json.encodeToString(serializer, list))
        tmp.renameTo(File(dir, name))
    }

    const val HISTORY_CAP = 60
}
