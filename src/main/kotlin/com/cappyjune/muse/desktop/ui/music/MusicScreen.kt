package com.cappyjune.muse.desktop.ui.music

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.HorizontalScrollbar
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.cappyjune.muse.desktop.data.Entry
import com.cappyjune.muse.desktop.data.Jellyfin
import com.cappyjune.muse.desktop.data.JfItem
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.Artwork
import com.cappyjune.muse.desktop.ui.Back
import com.cappyjune.muse.desktop.ui.HeaderText
import com.cappyjune.muse.desktop.ui.HoverCard
import com.cappyjune.muse.desktop.ui.Messages
import com.cappyjune.muse.desktop.ui.PageHeader
import com.cappyjune.muse.desktop.ui.SearchField
import com.cappyjune.muse.desktop.ui.Section
import com.cappyjune.muse.desktop.ui.SectionTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A page opened inside the Jellyfin section; a stack, so Back walks out one level at a time. */
private sealed interface Page {
    data class Album(val item: JfItem) : Page
    data class Artist(val item: JfItem) : Page
    data class Playlist(val item: JfItem) : Page
}

private fun play(tracks: List<JfItem>, start: Int = 0, shuffle: Boolean = false) = Player.playQueue(tracks.map(Jellyfin::toEntry), start, shuffle)

@Composable
fun JellyfinScreen() {
    val account by Jellyfin.account.collectAsState()
    if (account == null) { Back.handler = null; SignInScreen(); return }
    val stack = remember { mutableStateListOf<Page>() }
    val holder = rememberSaveableStateHolder()
    val open: (Page) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    var query by rememberSaveable { mutableStateOf("") }
    // A shared link's album, playlist or artist.
    LaunchedEffect(com.cappyjune.muse.desktop.ui.Links.jellyfinPage) {
        com.cappyjune.muse.desktop.ui.Links.jellyfinPage?.let { (item, kind) ->
            stack.add(when (kind) { "album" -> Page.Album(item); "playlist" -> Page.Playlist(item); else -> Page.Artist(item) })
            com.cappyjune.muse.desktop.ui.Links.jellyfinPage = null
        }
    }
    Back.handler = { when { stack.isNotEmpty() -> { back(); true }; query.isNotEmpty() -> { query = ""; true }; else -> false } }
    when (val p = stack.lastOrNull()) {
        null -> holder.SaveableStateProvider("browse") { Browse(account!!.userName, query, { query = it }, open) }
        else -> key(stack.size, p) {
            when (p) {
                is Page.Album -> TrackPage(p.item, p.item.AlbumArtist, { Jellyfin.albumTracks(p.item.Id) }, back, open, numbered = true)
                is Page.Playlist -> TrackPage(p.item, "Playlist", { Jellyfin.playlistTracks(p.item.Id) }, back, open, numbered = false, playlistId = p.item.Id)
                is Page.Artist -> ArtistPage(p.item, back, open)
            }
        }
    }
}

@Composable
private fun Browse(userName: String, query: String, setQuery: (String) -> Unit, open: (Page) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(null, { SectionTitle(Section.JELLYFIN) }) {
            Text(userName, Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SearchField(query, setQuery, "Search your music", Modifier.width(360.dp))
        }
        if (query.trim().length >= 2) { SearchResults(query.trim(), open); return@Column }
        PrimaryTabRow(selectedTabIndex = tab, Modifier.padding(horizontal = 32.dp).widthIn(max = 640.dp)) {
            listOf("Home", "Albums", "Artists", "Songs", "Playlists").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> Home(open)
            1 -> Loader({ Jellyfin.albums() }) { AlbumGrid(it, open) }
            2 -> Loader({ Jellyfin.artists() }) { ArtistGrid(it, open) }
            3 -> Songs()
            else -> key(Jellyfin.playlistsChanged.collectAsState().value) {
                Loader({ Jellyfin.playlists() }) { list ->
                    if (list.isEmpty()) Empty("No playlists yet. Right-click songs to add them to a new one.")
                    else Grid(GridCells.Adaptive(180.dp)) {
                        items(list, key = { it.Id }) { pl ->
                            Card(Jellyfin.imageUrl(pl, 400), pl.Name.orEmpty(), pl.ChildCount?.let { "$it songs" }.orEmpty(), Modifier.fillMaxWidth(),
                                fallback = Icons.AutoMirrored.Rounded.QueueMusic) { open(Page.Playlist(pl)) }
                        }
                    }
                }
            }
        }
    }
}

// --- building blocks --------------------------------------------------------------------------

/** Loads once and shows a spinner, an error, or the content. */
@Composable
private fun <T> Loader(load: suspend () -> T, content: @Composable (T) -> Unit) {
    var data by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { load() }.onSuccess { data = it }.onFailure { error = it.message } }
    val d = data
    when {
        error != null -> Empty("Couldn't reach Jellyfin: $error")
        d == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) }
        else -> content(d)
    }
}

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Grid(cells: GridCells, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(cells, state = state, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** An album, playlist or song as a square card with its name under it. */
@Composable
private fun Card(art: String?, title: String, sub: String, modifier: Modifier, round: Boolean = false, playing: Boolean = false,
                 fallback: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Album, onClick: () -> Unit) {
    HoverCard(modifier, onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(art, 400.dp, Modifier.fillMaxSize(), fallback = fallback, corner = if (round) 400.dp else 18.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified)
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlbumGrid(albums: List<JfItem>, open: (Page) -> Unit, header: (@Composable () -> Unit)? = null) = Grid(GridCells.Adaptive(180.dp)) {
    if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
    items(albums, key = { it.Id }) { a ->
        AlbumMenu(a) { Card(Jellyfin.imageUrl(a, 400), a.Name.orEmpty(), listOfNotNull(a.AlbumArtist, a.ProductionYear?.toString()).joinToString(" · "), Modifier.fillMaxWidth()) { open(Page.Album(a)) } }
    }
}

@Composable
private fun ArtistGrid(artists: List<JfItem>, open: (Page) -> Unit) = Grid(GridCells.Adaptive(160.dp)) {
    items(artists, key = { it.Id }) { a -> Card(Jellyfin.imageUrl(a, 300), a.Name.orEmpty(), "", Modifier.fillMaxWidth(), round = true, fallback = Icons.Rounded.Person) { open(Page.Artist(a)) } }
}

/** A titled row of cards that scrolls sideways, as on Plexamp's and Finamp's home. */
private fun LazyListScope.row(title: String, action: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    item(key = "h-$title") {
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            action?.invoke()
        }
    }
    item(key = "r-$title") {
        val state = rememberLazyListState()
        Column {
            LazyRow(state = state, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), content = content)
            HorizontalScrollbar(rememberScrollbarAdapter(state), Modifier.fillMaxWidth().padding(horizontal = 32.dp))
        }
    }
}

private class HomeData(val recent: List<JfItem>, val most: List<JfItem>, val added: List<JfItem>, val favSongs: List<JfItem>, val favAlbums: List<JfItem>, val rediscover: List<JfItem>)

/** Most personal first: what you played, your favourites, what's new, and something to rediscover. */
@Composable
private fun Home(open: (Page) -> Unit) {
    val np by Player.state.collectAsState()
    Loader({
        coroutineScope {
            val r = async { runCatching { Jellyfin.recentlyPlayed() }.getOrDefault(emptyList()) }
            val m = async { runCatching { Jellyfin.mostPlayed() }.getOrDefault(emptyList()) }
            val a = async { runCatching { Jellyfin.recentAlbums() }.getOrDefault(emptyList()) }
            val fs = async { runCatching { Jellyfin.favouriteSongs() }.getOrDefault(emptyList()) }
            val fa = async { runCatching { Jellyfin.favouriteAlbums() }.getOrDefault(emptyList()) }
            val rd = async { runCatching { Jellyfin.rediscover() }.getOrDefault(emptyList()) }
            HomeData(r.await(), m.await(), a.await(), fs.await(), fa.await(), rd.await())
        }
    }) { h ->
        val state = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                fun songs(title: String, list: List<JfItem>) {
                    if (list.isEmpty()) return
                    row(title, action = { TextButton(onClick = { play(list, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, null); Text("  Shuffle") } }) {
                        list.forEachIndexed { i, t ->
                            item(key = title + t.Id) {
                                TrackMenu(listOf(t)) { Card(Jellyfin.imageUrl(t, 300), t.Name.orEmpty(), t.Artists?.firstOrNull() ?: t.AlbumArtist.orEmpty(), Modifier.width(160.dp),
                                    playing = np.entry?.id == t.Id) { play(list, i) } }
                            }
                        }
                    }
                }
                fun albums(title: String, list: List<JfItem>) {
                    if (list.isEmpty()) return
                    row(title) {
                        list.forEach { a -> item(key = title + a.Id) { AlbumMenu(a) { Card(Jellyfin.imageUrl(a, 300), a.Name.orEmpty(), a.AlbumArtist.orEmpty(), Modifier.width(160.dp)) { open(Page.Album(a)) } } } }
                    }
                }
                songs("Recently played", h.recent)
                songs("Your favourites", h.favSongs)
                songs("Most played", h.most)
                albums("Recently added", h.added)
                albums("Favourite albums", h.favAlbums)
                albums("Rediscover", h.rediscover)
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Every song A-Z, fetched a page at a time as the list nears its end. */
@Composable
private fun Songs() {
    val scope = rememberCoroutineScope()
    val songs = remember { mutableStateListOf<JfItem>() }
    var total by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = rememberLazyListState()
    val np by Player.state.collectAsState()
    fun more() {
        if (loading || (total >= 0 && songs.size >= total)) return
        loading = true
        scope.launch {
            runCatching { Jellyfin.songsPage(songs.size) }.onSuccess { (page, t) -> songs.addAll(page); total = t }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { more() }
    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > songs.size - 40 } }
    LaunchedEffect(nearEnd) { if (nearEnd) more() }
    if (error != null && songs.isEmpty()) { Empty("Couldn't reach Jellyfin: $error"); return }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (total >= 0) "$total songs" else "Loading…", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { scope.launch { runCatching { Jellyfin.randomSongs() }.onSuccess { play(it) } } }) {
                        Icon(Icons.Rounded.Shuffle, null); Text("  Shuffle all")
                    }
                }
            }
            // Play from here onward within what's loaded, like clicking a song in any library view.
            itemsIndexed(songs, key = { _, t -> t.Id }) { i, t -> TrackRow(t, null, np.entry?.id == t.Id) { play(songs.drop(i).take(500)) } }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) } }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** A song: its number (or artwork), name, artists, album and length. Click plays; right-click has the rest. */
@Composable
private fun TrackRow(t: JfItem, number: Int?, playing: Boolean, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val cs = MaterialTheme.colorScheme
    TrackMenu(listOf(t), onRemove) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hovered) cs.surfaceContainerHigh else Color.Transparent)
            .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                when {
                    playing -> Icon(Icons.Rounded.GraphicEq, "Playing", tint = cs.primary)
                    hovered -> Icon(Icons.Rounded.PlayArrow, "Play")
                    number != null -> Text("$number", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                    else -> Artwork(Jellyfin.imageUrl(t, 120), 44.dp, fallback = Icons.Rounded.Album, corner = 8.dp)
                }
            }
            Column(Modifier.weight(1.4f).padding(horizontal = 12.dp)) {
                Text(t.Name.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (playing) cs.primary else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.Artists?.joinToString(", ")?.ifBlank { null } ?: t.AlbumArtist.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(t.Album.orEmpty(), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt((t.RunTimeTicks ?: 0) / 10_000), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// --- right-click menus ------------------------------------------------------------------------

@Composable
private fun Themed(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(c.surfaceContainerHigh, c.onSurface, c.primary.copy(alpha = 0.16f)), content = content)
}

/** Play next / Add to queue / Add to playlist / Instant mix, as in most music apps. */
@Composable
fun TrackMenu(tracks: List<JfItem>, onRemove: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var addTo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val label = if (tracks.size == 1) tracks.first().Name.orEmpty() else "${tracks.size} songs"
    Themed {
        ContextMenuArea(items = {
            buildList {
                add(ContextMenuItem("Play next") { Player.playNext(tracks.map(Jellyfin::toEntry)); Messages.show("Playing next: $label") })
                add(ContextMenuItem("Add to queue") { Player.addToQueue(tracks.map(Jellyfin::toEntry)); Messages.show("Added to queue: $label") })
                add(ContextMenuItem("Add to playlist…") { addTo = true })
                if (tracks.size == 1) add(ContextMenuItem("Instant mix") { scope.launch { instantMix(tracks.first().Id) } })
                if (tracks.size == 1) add(ContextMenuItem("Copy share link") {
                    val t = tracks.first(); com.cappyjune.muse.desktop.data.Share.copy(com.cappyjune.muse.desktop.data.Shared.Song(t.Id), t.Name.orEmpty(), t.Artists?.joinToString(", ").orEmpty())
                    Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
                })
                if (onRemove != null) add(ContextMenuItem("Remove from playlist", onRemove))
            }
        }) { content() }
    }
    if (addTo) AddToPlaylistDialog(tracks.map { it.Id }, onDismiss = { addTo = false })
}

@Composable
private fun AlbumMenu(album: JfItem, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var addTo by remember { mutableStateOf<List<String>?>(null) }
    fun withTracks(block: (List<JfItem>) -> Unit) = scope.launch { runCatching { Jellyfin.albumTracks(album.Id) }.onSuccess(block).onFailure { Messages.show("Couldn't reach Jellyfin: ${it.message}") } }
    Themed {
        ContextMenuArea(items = {
            listOf(
                ContextMenuItem("Play") { withTracks { play(it) } },
                ContextMenuItem("Shuffle") { withTracks { play(it, shuffle = true) } },
                ContextMenuItem("Play next") { withTracks { Player.playNext(it.map(Jellyfin::toEntry)); Messages.show("Playing next: ${album.Name}") } },
                ContextMenuItem("Add to queue") { withTracks { Player.addToQueue(it.map(Jellyfin::toEntry)); Messages.show("Added to queue: ${album.Name}") } },
                ContextMenuItem("Add to playlist…") { withTracks { t -> addTo = t.map { it.Id } } },
                ContextMenuItem("Instant mix") { scope.launch { instantMix(album.Id) } },
                ContextMenuItem("Copy share link") {
                    com.cappyjune.muse.desktop.data.Share.copy(com.cappyjune.muse.desktop.data.Shared.Album(album.Id), album.Name.orEmpty(), album.AlbumArtist.orEmpty())
                    Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
                },
            )
        }) { content() }
    }
    addTo?.let { ids -> AddToPlaylistDialog(ids, onDismiss = { addTo = null }) }
}

/** Jellyfin's Instant Mix: songs like this one (or this album, or artist), queued up. */
suspend fun instantMix(seed: String) {
    val mix = runCatching { Jellyfin.instantMix(seed) }.getOrDefault(emptyList())
    if (mix.isEmpty()) Messages.show("Couldn't build a mix")
    else { play(mix); Messages.show("Instant Mix: ${mix.size} songs like this") }
}

// --- pages ------------------------------------------------------------------------------------

@Composable
private fun TrackPage(item: JfItem, subtitle: String?, load: suspend () -> List<JfItem>, back: () -> Unit, open: (Page) -> Unit,
                      numbered: Boolean, playlistId: String? = null) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val np by Player.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageHeader(back, {}) {
            TextButton(onClick = {
                val sh = if (playlistId != null) com.cappyjune.muse.desktop.data.Shared.Playlist(item.Id) else com.cappyjune.muse.desktop.data.Shared.Album(item.Id)
                com.cappyjune.muse.desktop.data.Share.copy(sh, item.Name.orEmpty(), if (playlistId != null) "Playlist" else subtitle.orEmpty())
                Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
            }) { Text("Copy share link") }
            if (playlistId != null) Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        key(reload) {
            Loader(load) { tracks ->
                val total = tracks.sumOf { it.RunTimeTicks ?: 0 } / 10_000
                val state = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
                                Artwork(Jellyfin.imageUrl(item, 800), 220.dp, fallback = if (playlistId != null) Icons.AutoMirrored.Rounded.QueueMusic else Icons.Rounded.Album, corner = 24.dp)
                                Column(Modifier.padding(start = 28.dp)) {
                                    Text(if (playlistId != null) "PLAYLIST" else "ALBUM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(item.Name.orEmpty(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(subtitle.takeIf { playlistId == null }, item.ProductionYear?.toString(), "${tracks.size} songs", fmt(total)).joinToString(" · "),
                                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { play(tracks) }) { Icon(Icons.Rounded.PlayArrow, null); Text("  Play") }
                                        FilledTonalButton(onClick = { play(tracks, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, null); Text("  Shuffle") }
                                        TextButton(onClick = { Player.addToQueue(tracks.map(Jellyfin::toEntry)); Messages.show("Added to queue: ${item.Name}") }) {
                                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Text("  Add to queue")
                                        }
                                        if (playlistId == null && item.AlbumArtist != null) TextButton(onClick = {
                                            scope.launch { runCatching { Jellyfin.search(item.AlbumArtist) }.getOrNull()?.firstOrNull { it.Type == "MusicArtist" }?.let { open(Page.Artist(it)) } }
                                        }) { Icon(Icons.Rounded.Person, null); Text("  ${item.AlbumArtist}") }
                                    }
                                }
                            }
                        }
                        itemsIndexed(tracks, key = { i, t -> t.PlaylistItemId ?: (t.Id + i) }) { i, t ->
                            val remove: (() -> Unit)? = if (playlistId != null && t.PlaylistItemId != null) { {
                                scope.launch {
                                    runCatching { Jellyfin.removeFromPlaylist(playlistId, listOf(t.PlaylistItemId)) }
                                        .onSuccess { reload++ }.onFailure { Messages.show("Couldn't remove: ${it.message}") }
                                }
                            } } else null
                            TrackRow(t, if (numbered) t.IndexNumber ?: (i + 1) else null, np.entry?.id == t.Id, remove) { play(tracks, i) }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
    if (confirmDelete && playlistId != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${item.Name}”?") },
        text = { Text("The playlist is removed from your Jellyfin server. The songs themselves aren't touched.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch { runCatching { Jellyfin.deletePlaylist(playlistId) }.onSuccess { back() }.onFailure { Messages.show("Couldn't delete: ${it.message}") } }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun ArtistPage(artist: JfItem, back: () -> Unit, open: (Page) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        PageHeader(back, {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(Jellyfin.imageUrl(artist, 200), 56.dp, fallback = Icons.Rounded.Person, corner = 28.dp)
                Spacer(Modifier.width(16.dp)); HeaderText(artist.Name.orEmpty())
            }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { scope.launch { runCatching { Jellyfin.artistTracks(artist.Id) }.onSuccess { play(it, shuffle = true) } } }) {
                    Icon(Icons.Rounded.Shuffle, null); Text("  Shuffle")
                }
                TextButton(onClick = { scope.launch { instantMix(artist.Id) } }) { Text("Instant mix") }
                TextButton(onClick = {
                    com.cappyjune.muse.desktop.data.Share.copy(com.cappyjune.muse.desktop.data.Shared.Artist(artist.Id), artist.Name.orEmpty(), "Artist")
                    Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
                }) { Text("Copy share link") }
            }
        }
        Loader({ Jellyfin.artistAlbums(artist.Id) }) { albums -> AlbumGrid(albums, open) }
    }
}

@Composable
private fun SearchResults(q: String, open: (Page) -> Unit) {
    var res by remember { mutableStateOf<List<JfItem>?>(null) }
    val np by Player.state.collectAsState()
    LaunchedEffect(q) { delay(350); res = null; res = runCatching { Jellyfin.searchAll(q) }.getOrDefault(emptyList()) }
    val r = res ?: run { Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) }; return }
    if (r.isEmpty()) { Empty("Nothing matches \"$q\"."); return }
    val tracks = r.filter { it.Type == "Audio" }
    val artists = r.filter { it.Type == "MusicArtist" }
    val albums = r.filter { it.Type == "MusicAlbum" }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (artists.isNotEmpty()) row("Artists") {
                artists.forEach { a -> item(key = "ar" + a.Id) { Card(Jellyfin.imageUrl(a, 300), a.Name.orEmpty(), "", Modifier.width(150.dp), round = true, fallback = Icons.Rounded.Person) { open(Page.Artist(a)) } } }
            }
            if (albums.isNotEmpty()) row("Albums") {
                albums.forEach { a -> item(key = "al" + a.Id) { AlbumMenu(a) { Card(Jellyfin.imageUrl(a, 300), a.Name.orEmpty(), a.AlbumArtist.orEmpty(), Modifier.width(160.dp)) { open(Page.Album(a)) } } } }
            }
            if (tracks.isNotEmpty()) item {
                Text("Songs", Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(tracks, key = { _, t -> "t" + t.Id }) { i, t ->
                Box(Modifier.padding(horizontal = 20.dp)) { TrackRow(t, null, np.entry?.id == t.Id) { play(tracks, i) } }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

