package com.cappyjune.muse.desktop.ui.local

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cappyjune.muse.desktop.data.LocalMusic
import com.cappyjune.muse.desktop.data.Settings
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.Artwork
import com.cappyjune.muse.desktop.ui.Back
import com.cappyjune.muse.desktop.ui.HoverCard
import com.cappyjune.muse.desktop.ui.Messages
import com.cappyjune.muse.desktop.ui.PageHeader
import com.cappyjune.muse.desktop.ui.SearchField
import com.cappyjune.muse.desktop.ui.Section
import com.cappyjune.muse.desktop.ui.SectionTitle
import com.cappyjune.muse.desktop.ui.music.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Covers may need ffmpeg to pull them out of a file: never on the UI thread. */
private suspend fun entries(tracks: List<LocalMusic.Track>) = withContext(Dispatchers.IO) { tracks.map(LocalMusic::toEntry) }

private fun play(scope: kotlinx.coroutines.CoroutineScope, tracks: List<LocalMusic.Track>, start: Int = 0, shuffle: Boolean = false) =
    scope.launch { Player.playQueue(entries(tracks), start, shuffle) }

/** Music on this computer: albums and songs from the folders you choose (your Music folder to begin with). */
@Composable
fun LocalScreen() {
    val settings by Settings.state.collectAsState()
    val tracks by LocalMusic.tracks.collectAsState()
    val progress by LocalMusic.progress.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    // Looked at again each time the section opens (only new or changed files are read).
    LaunchedEffect(settings.musicFolders, reload) { scanning = true; LocalMusic.scan(settings.musicFolders); scanning = false }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var album by remember { mutableStateOf<LocalMusic.Album?>(null) }
    Back.handler = { when { album != null -> { album = null; true }; query.isNotEmpty() -> { query = ""; true }; else -> false } }
    album?.let { a -> AlbumPage(a) { album = null }; return }

    Column(Modifier.fillMaxSize()) {
        PageHeader(null, { SectionTitle(Section.LOCAL) }) {
            SearchField(query, { query = it }, "Search your music", Modifier.width(320.dp))
            IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Look again") }
            FoldersMenu(settings.musicFolders)
        }
        if (scanning && progress.second > 0 && progress.first < progress.second) {
            LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, Modifier.fillMaxWidth().padding(horizontal = 32.dp))
            Text("Reading ${progress.first} of ${progress.second} files…", Modifier.padding(horizontal = 32.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
        val all = tracks ?: return@Column
        if (all.isEmpty()) {
            Text("No music in ${settings.musicFolders.joinToString { it.replace(System.getProperty("user.home"), "~") }} yet. Add a folder with the folder button above.",
                Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val q = query.trim()
        val shown = if (q.length < 2) all else all.filter { t -> listOf(t.title, t.artist, t.album).any { it.contains(q, ignoreCase = true) } }
        PrimaryTabRow(selectedTabIndex = tab, Modifier.padding(horizontal = 32.dp).widthIn(max = 360.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Albums") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Songs") })
        }
        if (tab == 0) AlbumGrid(LocalMusic.albums(shown)) { album = it } else SongList(shown)
    }
}

@Composable
private fun Cover(t: LocalMusic.Track, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 12.dp) {
    val art by produceState<String?>(null, t.path) { value = withContext(Dispatchers.IO) { LocalMusic.cover(t) } }
    Artwork(art, size, modifier, fallback = Icons.Rounded.Album, corner = corner)
}

@Composable
private fun AlbumGrid(albums: List<LocalMusic.Album>, open: (LocalMusic.Album) -> Unit) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(180.dp), state = state, contentPadding = PaddingValues(32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(albums, key = { it.key + it.folder }) { a ->
                Menu({ a.tracks }) {
                    HoverCard(Modifier.fillMaxWidth(), onClick = { open(a) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f)) { Cover(a.tracks.first(), 400.dp, Modifier.fillMaxSize(), corner = 18.dp) }
                        Text(a.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(a.artist, a.year.takeIf { it > 0 }?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun SongList(tracks: List<LocalMusic.Track>) {
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${tracks.size} songs", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { play(scope, tracks, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, null); Text("  Shuffle all") }
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.path }) { i, t -> Row(t, null) { play(scope, tracks, i) } }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun AlbumPage(a: LocalMusic.Album, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        PageHeader(back, {})
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
                        Cover(a.tracks.first(), 220.dp, corner = 24.dp)
                        Column(Modifier.padding(start = 28.dp)) {
                            Text("ALBUM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(a.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(a.artist, a.year.takeIf { it > 0 }?.toString(), "${a.tracks.size} songs", fmt(a.tracks.sumOf { it.durationMs })).joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(a.folder.replace(System.getProperty("user.home"), "~"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { play(scope, a.tracks) }) { Icon(Icons.Rounded.PlayArrow, null); Text("  Play") }
                                FilledTonalButton(onClick = { play(scope, a.tracks, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, null); Text("  Shuffle") }
                                TextButton(onClick = { scope.launch { Player.addToQueue(entries(a.tracks)); Messages.show("Added to queue: ${a.title}") } }) {
                                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Text("  Add to queue")
                                }
                            }
                        }
                    }
                }
                itemsIndexed(a.tracks, key = { _, t -> t.path }) { i, t -> Row(t, t.track.takeIf { it > 0 } ?: (i + 1)) { play(scope, a.tracks, i) } }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** A song: number (or cover), title, artist, album, length. Click plays; right-click queues. */
@Composable
private fun Row(t: LocalMusic.Track, number: Int?, onClick: () -> Unit) {
    val np by Player.state.collectAsState()
    val playing = np.entry?.id == t.path
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val cs = MaterialTheme.colorScheme
    Menu({ listOf(t) }) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hovered) cs.surfaceContainerHigh else Color.Transparent)
            .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                when {
                    playing -> Icon(Icons.Rounded.GraphicEq, "Playing", tint = cs.primary)
                    hovered -> Icon(Icons.Rounded.PlayArrow, "Play")
                    number != null -> Text("$number", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                    else -> Cover(t, 44.dp, corner = 8.dp)
                }
            }
            Column(Modifier.weight(1.4f).padding(horizontal = 12.dp)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (playing) cs.primary else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(t.album, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt(t.durationMs), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Menu(tracks: () -> List<LocalMusic.Track>, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(c.surfaceContainerHigh, c.onSurface, c.primary.copy(alpha = 0.16f))) {
        ContextMenuArea(items = {
            listOf(
                ContextMenuItem("Play next") { scope.launch { Player.playNext(entries(tracks())); Messages.show("Playing next") } },
                ContextMenuItem("Add to queue") { scope.launch { Player.addToQueue(entries(tracks())); Messages.show("Added to queue") } },
                ContextMenuItem("Show in Files") { runCatching { ProcessBuilder("xdg-open", tracks().first().folder).start() } },
            )
        }) { content() }
    }
}

/** The folders "On this computer" looks in: remove one, or add another. */
@Composable
private fun FoldersMenu(folders: List<String>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.CreateNewFolder, "Folders to look in") }
        DropdownMenu(open, { open = false }) {
            Text("Looking in", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            folders.forEach { f ->
                DropdownMenuItem(text = { Text(f.replace(System.getProperty("user.home"), "~")) }, onClick = {}, leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                    trailingIcon = { if (folders.size > 1) IconButton(onClick = { Settings.update { s -> s.copy(musicFolders = s.musicFolders - f) } }) { Icon(Icons.Rounded.Close, "Stop looking here") } })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add a folder…") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = {
                open = false
                pickFolder()?.let { f -> Settings.update { s -> if (f in s.musicFolders) s else s.copy(musicFolders = s.musicFolders + f) } }
            })
        }
    }
}

/** GNOME's own folder picker (zenity); Java's if that's missing. */
private fun pickFolder(title: String = "Choose a folder of music"): String? {
    runCatching {
        val p = ProcessBuilder("zenity", "--file-selection", "--directory", "--title=$title").start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out.takeIf { p.exitValue() == 0 && java.io.File(it).isDirectory }
    }
    val c = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = title }
    return if (c.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) c.selectedFile.path else null
}
