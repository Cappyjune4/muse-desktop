package com.cappyjune.muse.desktop.ui.library

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cappyjune.muse.desktop.data.Entry
import com.cappyjune.muse.desktop.data.Library
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.Back
import com.cappyjune.muse.desktop.ui.EntryRow
import com.cappyjune.muse.desktop.ui.PageHeader
import com.cappyjune.muse.desktop.ui.Section
import com.cappyjune.muse.desktop.ui.SectionTitle

/** Favourites and History across every source, as muSE's two rail entries were. */
@Composable
fun LibraryScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val favs by Library.favourites.collectAsState()
    val history by Library.history.collectAsState()
    val np by Player.state.collectAsState()
    Back.handler = null
    Column(Modifier.fillMaxSize()) {
        PageHeader(null, { SectionTitle(Section.LIBRARY) }) {
            if (tab == 1 && history.isNotEmpty()) TextButton(onClick = Library::clearHistory) { Icon(Icons.Rounded.DeleteSweep, null); Text("  Clear history") }
        }
        PrimaryTabRow(selectedTabIndex = tab, Modifier.padding(horizontal = 32.dp).widthIn(max = 420.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Favourites") })
            Tab(tab == 1, { tab = 1 }, text = { Text("History") })
        }
        val list = if (tab == 0) favs else history
        val state = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            if (list.isEmpty()) Text(if (tab == 0) "Click the heart on any station or song to keep it here." else "Nothing played yet.",
                Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyVerticalGrid(GridCells.Adaptive(420.dp), state = state, contentPadding = PaddingValues(20.dp)) {
                items(list, key = { it.key }) { e ->
                    EntryRow(e, playing = np.entry?.key == e.key, onClick = { play(e) }) {
                        if (tab == 0) IconButton(onClick = { Library.toggleFavourite(e) }) {
                            Icon(Icons.Rounded.Favorite, "Remove favourite", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Stations, songs, episodes and files. */
private fun play(e: Entry) = when (e.kind) {
    Entry.Kind.RADIO -> Player.playRadio(e)
    Entry.Kind.MUSIC -> Player.playQueue(listOf(e))
    Entry.Kind.PODCAST -> com.cappyjune.muse.desktop.data.Podcasts.episode(e.id)?.let(Player::playEpisode) ?: Unit
    Entry.Kind.LOCAL -> if (java.io.File(e.id).exists()) Player.playQueue(listOf(e)) else com.cappyjune.muse.desktop.ui.Messages.show("That file isn't there any more")
}
