package com.cappyjune.muse.desktop.ui.podcasts

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.cappyjune.muse.desktop.data.Download
import com.cappyjune.muse.desktop.data.Episode
import com.cappyjune.muse.desktop.data.PodcastSearch
import com.cappyjune.muse.desktop.data.Podcasts
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Page {
    data class Show(val feedUrl: String) : Page
    data object Add : Page
}

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun date(ms: Long) = if (ms > 0) dateFmt.format(Date(ms)) else ""
private fun mb(bytes: Long) = if (bytes > 0) "${bytes / 1_000_000} MB" else ""

/** Podcasts: Continue listening, your shows, the latest episodes; a show's page; adding shows. */
@Composable
fun PodcastsScreen() {
    var page by remember { mutableStateOf<Page?>(null) }
    val holder = rememberSaveableStateHolder()
    Back.handler = { if (page != null) { page = null; true } else false }
    LaunchedEffect(Unit) { Podcasts.refreshIfStale() }
    LaunchedEffect(com.cappyjune.muse.desktop.ui.Links.podcastPage) {
        com.cappyjune.muse.desktop.ui.Links.podcastPage?.let { page = Page.Show(it); com.cappyjune.muse.desktop.ui.Links.podcastPage = null }
    }
    when (val p = page) {
        null -> holder.SaveableStateProvider("home") { Home(open = { page = it }) }
        is Page.Show -> ShowPage(p.feedUrl, back = { page = null })
        Page.Add -> AddPage(back = { page = null }, opened = { page = Page.Show(it) })
    }
}

@Composable
private fun Home(open: (Page) -> Unit) {
    val scope = rememberCoroutineScope()
    val subs by Podcasts.subscriptions.collectAsState()
    val eps by Podcasts.episodes.collectAsState()
    val prog by Podcasts.progress.collectAsState()
    val dls by Podcasts.downloads.collectAsState()
    val refreshing by Podcasts.refreshing.collectAsState()
    var downloadsOnly by rememberSaveable { mutableStateOf(false) }
    // Started but not finished, most recently listened first: AntennaPod's "Continue listening".
    val cont = remember(eps, prog) {
        prog.filter { (_, p) -> !p.played && p.positionMs > 15_000 }.entries.sortedByDescending { it.value.updated }.mapNotNull { Podcasts.episode(it.key) }.take(12)
    }
    val latest = remember(eps, prog) { eps.values.flatten().filter { prog[it.key]?.played != true }.sortedByDescending { it.published }.take(40) }
    val downloaded = remember(eps, dls) { dls.keys.mapNotNull { Podcasts.episode(it) }.sortedByDescending { it.published } }

    Column(Modifier.fillMaxSize()) {
        PageHeader(null, { SectionTitle(Section.PODCASTS) }) {
            FilterChip(downloadsOnly, { downloadsOnly = !downloadsOnly }, label = { Text("Downloads") }, leadingIcon = { Icon(Icons.Rounded.DownloadDone, null, Modifier.size(18.dp)) })
            Spacer(Modifier.width(8.dp))
            if (refreshing) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { val f = Podcasts.refreshAll(); if (f > 0) Messages.show("$f feeds couldn't be refreshed") } }) { Icon(Icons.Rounded.Refresh, "Refresh all") }
            FilledTonalButton(onClick = { open(Page.Add) }) { Icon(Icons.Rounded.Add, null); Text("  Add podcasts") }
        }
        if (subs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Podcasts, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No podcasts yet", style = MaterialTheme.typography.titleLarge)
                Text("Search for shows, add a feed, or import your AntennaPod export.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { open(Page.Add) }) { Text("Add podcasts") }
            }
            return@Column
        }
        val state = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (downloadsOnly) {
                    item { Heading(if (downloaded.isEmpty()) "Nothing downloaded" else "Downloaded · ${mb(Podcasts.downloadedBytes())}") }
                    items(downloaded, key = { "d" + it.key }) { ep -> EpisodeRow(ep, showArt = true) }
                    return@LazyColumn
                }
                if (cont.isNotEmpty()) {
                    item { Heading("Continue listening") }
                    item {
                        Carousel {
                            items(cont, key = { it.key }) { ep ->
                                val p = prog[ep.key]
                                val art = ep.art.ifBlank { Podcasts.podcast(ep.feedUrl)?.art.orEmpty() }
                                HoverCard(Modifier.width(180.dp), onClick = { Player.playEpisode(ep) }) {
                                    Artwork(art, 180.dp, corner = 18.dp, fallback = Icons.Rounded.Podcasts)
                                    LinearProgressIndicator(progress = { ((p?.positionMs ?: 0).toFloat() / (p?.durationMs ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f) },
                                        Modifier.fillMaxWidth().padding(top = 8.dp), drawStopIndicator = {})
                                    Text(ep.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    p?.let { Text("${fmt((it.durationMs - it.positionMs).coerceAtLeast(0))} left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
                item { Heading("Your shows") }
                item {
                    Carousel {
                        items(subs, key = { it.feedUrl }) { p ->
                            HoverCard(Modifier.width(150.dp), onClick = { open(Page.Show(p.feedUrl)) }) {
                                Artwork(p.art, 150.dp, corner = 18.dp, fallback = Icons.Rounded.Podcasts)
                                Text(p.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                item { Heading("Latest episodes") }
                items(latest, key = { "l" + it.key }) { ep -> EpisodeRow(ep, showArt = true) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun Heading(t: String) = Text(t, Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

@Composable
private fun Carousel(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    Column {
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), content = content)
        HorizontalScrollbar(rememberScrollbarAdapter(state), Modifier.fillMaxWidth().padding(horizontal = 32.dp))
    }
}

/** An episode: click plays (from where you left it); the notes, download and "played" are beside it. Right-click for more. */
@Composable
private fun EpisodeRow(ep: Episode, showArt: Boolean) {
    val np by Player.state.collectAsState()
    val prog by Podcasts.progress.collectAsState()
    val dls by Podcasts.downloads.collectAsState()
    val p = prog[ep.key]
    val d = dls[ep.key]
    val playing = np.entry?.id == ep.key
    var notes by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val cs = MaterialTheme.colorScheme
    val art = ep.art.ifBlank { Podcasts.podcast(ep.feedUrl)?.art.orEmpty() }
    CompositionLocalProvider(LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(cs.surfaceContainerHigh, cs.onSurface, cs.primary.copy(alpha = 0.16f))) {
        ContextMenuArea(items = {
            listOfNotNull(
                ContextMenuItem("Play") { Player.playEpisode(ep) },
                ContextMenuItem("Show notes") { notes = true },
                if (p?.played == true) ContextMenuItem("Mark unplayed") { Podcasts.markPlayed(ep.key, false) } else ContextMenuItem("Mark played") { Podcasts.markPlayed(ep.key, true) },
                if (d == null) ContextMenuItem("Download") { Podcasts.download(ep) } else ContextMenuItem("Delete download") { Podcasts.deleteDownload(ep.key) },
                ContextMenuItem("Copy share link") {
                    com.cappyjune.muse.desktop.data.Share.copy(com.cappyjune.muse.desktop.data.Shared.Episode(ep.feedUrl, com.cappyjune.muse.desktop.data.Share.tag(ep.guid)), ep.title, Podcasts.podcast(ep.feedUrl)?.title.orEmpty())
                    Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
                },
            )
        }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp)).background(if (hovered) cs.surfaceContainerHigh else Color.Transparent)
                .hoverable(source).clickable { Player.playEpisode(ep) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    if (showArt) Artwork(art, 64.dp, corner = 12.dp, fallback = Icons.Rounded.Podcasts)
                    else Box(Modifier.size(64.dp).background(cs.surfaceContainerHigh, RoundedCornerShape(12.dp)))
                    if (playing || hovered) Icon(if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, null,
                        Modifier.align(Alignment.Center).size(32.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(4.dp), tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(listOfNotNull(if (showArt) Podcasts.podcast(ep.feedUrl)?.title else null, date(ep.published).ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1)
                    Text(ep.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = when { playing -> cs.primary; p?.played == true -> cs.onSurfaceVariant; else -> Color.Unspecified })
                    val left = p?.takeIf { !it.played && it.positionMs > 15_000 }?.let { "${fmt((it.durationMs - it.positionMs).coerceAtLeast(0))} left" }
                    Text(listOf(if (p?.played == true) "Played" else left ?: fmt(ep.durationMs).takeIf { ep.durationMs > 0 }.orEmpty(), mb(ep.sizeBytes))
                        .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (p != null && !p.played && p.positionMs > 15_000 && p.durationMs > 0) LinearProgressIndicator(progress = { (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) },
                        Modifier.width(180.dp).padding(top = 4.dp).height(3.dp), drawStopIndicator = {})
                }
                TextButton(onClick = { notes = true }) { Text("Notes") }
                DownloadButton(ep, d)
                IconButton(onClick = { Podcasts.markPlayed(ep.key, p?.played != true) }) {
                    Icon(if (p?.played == true) Icons.Rounded.CheckCircle else Icons.Rounded.DoneAll, if (p?.played == true) "Mark unplayed" else "Mark played",
                        tint = if (p?.played == true) cs.primary else cs.onSurfaceVariant)
                }
            }
        }
    }
    if (notes) ShowNotes(ep, art, d, onDismiss = { notes = false })
}

@Composable
private fun DownloadButton(ep: Episode, d: Download?) {
    when {
        d == null -> IconButton(onClick = { Podcasts.download(ep) }) { Icon(Icons.Rounded.Download, "Download") }
        d.failed -> IconButton(onClick = { Podcasts.retryDownload(ep.key) }) { Icon(Icons.Rounded.ErrorOutline, "Download failed, try again", tint = MaterialTheme.colorScheme.error) }
        !d.done -> IconButton(onClick = { Podcasts.deleteDownload(ep.key) }) {
            CircularProgressIndicator(progress = { d.percent / 100f }, Modifier.size(24.dp), strokeWidth = 3.dp)
        }
        else -> IconButton(onClick = { Podcasts.deleteDownload(ep.key) }) { Icon(Icons.Rounded.DownloadDone, "Downloaded (click to delete)", tint = MaterialTheme.colorScheme.primary) }
    }
}

/** Episode details and show notes, as AntennaPod and Pocket Casts both offer. */
@Composable
private fun ShowNotes(ep: Episode, art: String, d: Download?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }, text = {
        Column(Modifier.widthIn(min = 520.dp, max = 640.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(art, 80.dp, corner = 14.dp, fallback = Icons.Rounded.Podcasts)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(Podcasts.podcast(ep.feedUrl)?.title.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(listOf(date(ep.published), fmt(ep.durationMs).takeIf { ep.durationMs > 0 }.orEmpty(), mb(ep.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(ep.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDismiss(); Player.playEpisode(ep) }) { Icon(Icons.Rounded.PlayArrow, null); Text("  Play") }
                if (d == null) FilledTonalButton(onClick = { Podcasts.download(ep) }) { Icon(Icons.Rounded.Download, null); Text("  Download") }
            }
            SelectionContainer { Text(ep.notes.ifBlank { "No show notes for this episode." }, style = MaterialTheme.typography.bodyMedium) }
        }
    })
}

@Composable
private fun ShowPage(feedUrl: String, back: () -> Unit) {
    val subs by Podcasts.subscriptions.collectAsState()
    val eps by Podcasts.episodes.collectAsState()
    val pod = subs.firstOrNull { it.feedUrl == feedUrl } ?: run { LaunchedEffect(Unit) { back() }; return }
    val list = eps[feedUrl].orEmpty()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        PageHeader(back, {}) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy share link") }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, onClick = {
                        menu = false; com.cappyjune.muse.desktop.data.Share.copy(com.cappyjune.muse.desktop.data.Shared.Show(pod.feedUrl), pod.title, pod.author)
                        Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
                    })
                    DropdownMenuItem(text = { Text("Mark all played") }, leadingIcon = { Icon(Icons.Rounded.DoneAll, null) }, onClick = { list.forEach { Podcasts.markPlayed(it.key, true) }; menu = false })
                    DropdownMenuItem(text = { Text("Unsubscribe") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { confirm = true; menu = false })
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
                        Artwork(pod.art, 200.dp, corner = 24.dp, fallback = Icons.Rounded.Podcasts)
                        Column(Modifier.padding(start = 28.dp)) {
                            Text("PODCAST", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(pod.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(pod.author, "${list.size} episodes").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (pod.description.isNotBlank()) Text(pod.description, Modifier.padding(top = 10.dp).widthIn(max = 820.dp), maxLines = 5, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(list, key = { it.key }) { ep -> EpisodeRow(ep, showArt = false) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Unsubscribe from ${pod.title}?") },
        text = { Text("Its downloaded episodes will be deleted from this computer.") },
        confirmButton = { TextButton(onClick = { Podcasts.unsubscribe(pod); confirm = false; back() }) { Text("Unsubscribe") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

/** Search Apple's podcast index and fyyd, add a feed by its link, or import an AntennaPod (OPML) export. */
@Composable
private fun AddPage(back: () -> Unit, opened: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val subs by Podcasts.subscriptions.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PodcastSearch.Result>?>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var rss by remember { mutableStateOf(false) }
    fun subscribe(url: String) {
        busy = "Subscribing…"
        scope.launch {
            runCatching { Podcasts.subscribe(url) }.onSuccess { busy = null; opened(it.feedUrl) }
                .onFailure { busy = null; Messages.show("Couldn't load that feed: ${it.message}") }
        }
    }
    fun import() {
        val f = java.awt.FileDialog(null as java.awt.Frame?, "Import podcasts (OPML)", java.awt.FileDialog.LOAD).apply { isVisible = true }
            .let { d -> d.file?.let { java.io.File(d.directory, it) } } ?: return
        busy = "Importing…"
        scope.launch {
            val r = runCatching { Podcasts.importOpml(f.inputStream()) }
            busy = null
            r.onSuccess { (ok, bad) -> Messages.show("Imported $ok podcasts" + if (bad > 0) ", $bad failed" else "") }.onFailure { Messages.show("Couldn't read that file: ${it.message}") }
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(450); results = null
        results = PodcastSearch.search(q)
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(back, { Text("Add podcasts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }) {
            SearchField(query, { query = it }, "Search Apple Podcasts and fyyd", Modifier.width(400.dp))
            TextButton(onClick = { rss = true }) { Icon(Icons.Rounded.Link, null); Text("  Feed link") }
            TextButton(onClick = ::import) { Icon(Icons.Rounded.FileOpen, null); Text("  Import OPML") }
        }
        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp)); Text(it, Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) }
        val r = results
        val state = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            when {
                query.trim().length < 2 -> Text("Search for a show by name. Or add one by its RSS feed link, or import the .opml file AntennaPod (or most podcast apps) can export.",
                    Modifier.padding(32.dp).widthIn(max = 720.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                r == null -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
                r.isEmpty() -> Text("No podcasts match.", Modifier.padding(32.dp))
                else -> LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    items(r, key = { it.feedUrl }) { res ->
                        val sub = subs.firstOrNull { it.feedUrl.equals(res.feedUrl, true) }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { if (sub != null) opened(sub.feedUrl) else subscribe(res.feedUrl) }
                            .pointerHoverIcon(PointerIcon.Hand).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(res.art, 64.dp, corner = 12.dp, fallback = Icons.Rounded.Podcasts)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(res.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(res.author, res.source).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (sub != null) Icon(Icons.Rounded.CheckCircle, "Subscribed", tint = MaterialTheme.colorScheme.primary)
                            else FilledTonalButton(onClick = { subscribe(res.feedUrl) }) { Icon(Icons.Rounded.Add, null); Text("  Subscribe") }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    if (rss) {
        var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { rss = false }, title = { Text("Add by feed link") },
            text = { OutlinedTextField(url, { url = it }, label = { Text("RSS feed address") }, singleLine = true, modifier = Modifier.width(460.dp)) },
            confirmButton = { TextButton(enabled = url.startsWith("http"), onClick = { rss = false; subscribe(url.trim()) }) { Text("Subscribe") } },
            dismissButton = { TextButton(onClick = { rss = false }) { Text("Cancel") } })
    }
}

