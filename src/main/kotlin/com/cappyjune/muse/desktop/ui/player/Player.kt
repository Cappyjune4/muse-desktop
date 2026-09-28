package com.cappyjune.muse.desktop.ui.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.cappyjune.muse.desktop.data.PodcastPrefs
import com.cappyjune.muse.desktop.data.Podcasts
import com.cappyjune.muse.desktop.data.Settings
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.cappyjune.muse.desktop.data.Entry
import com.cappyjune.muse.desktop.data.Jellyfin
import com.cappyjune.muse.desktop.playback.Repeat
import com.cappyjune.muse.desktop.ui.music.fmt
import com.cappyjune.muse.desktop.ui.music.instantMix
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cappyjune.muse.desktop.data.Library
import com.cappyjune.muse.desktop.playback.NowPlaying
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.Artwork

/** The play button: a circle while stopped, a rounded square while playing (Expressive's shape morph). */
@Composable
private fun PlayButton(np: NowPlaying, size: Int) {
    val cs = MaterialTheme.colorScheme
    FilledIconButton(onClick = Player::togglePlay, Modifier.size(size.dp), shape = if (np.isPlaying) RoundedCornerShape((size / 3).dp) else CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.primary)) {
        if (np.isBuffering) CircularProgressIndicator(Modifier.size((size * 0.42).dp), color = cs.onPrimary, strokeWidth = 3.dp)
        else Icon(if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (np.isPlaying) "Pause" else "Play", Modifier.size((size / 2).dp))
    }
}

/** A red LIVE badge, pulsing while it plays, as muSE draws it. */
@Composable
fun LiveBadge(playing: Boolean) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Row(Modifier.background(Color(0xFFE53935), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(if (playing) pulse else 1f).background(Color.White, CircleShape))
        Text("  LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Volume(np: NowPlaying, modifier: Modifier = Modifier) {
    Row(modifier.onPointerEvent(PointerEventType.Scroll) { e ->
        val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        Player.changeVolume(np.volume - (dy * 5).toInt())
    }, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = Player::toggleMute) {
            Icon(if (np.muted || np.volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Mute")
        }
        Slider(if (np.muted) 0f else np.volume.toFloat(), { Player.changeVolume(it.toInt()) }, Modifier.width(130.dp), valueRange = 0f..100f)
    }
}

@Composable
private fun FavouriteButton(np: NowPlaying) {
    val favs by Library.favourites.collectAsState()
    val e = np.entry ?: return
    val fav = favs.any { it.key == e.key }
    val scope = rememberCoroutineScope()
    IconButton(onClick = {
        Library.toggleFavourite(e)
        // Jellyfin songs are favourited on the server too, so they show in every Jellyfin app.
        if (e.kind == Entry.Kind.MUSIC) scope.launch { runCatching { Jellyfin.setFavourite(e.id, !fav) } }
    }) {
        Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) "Remove favourite" else "Add favourite",
            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The line under the title: the error if there is one, else the artist (radio: the station if it's not saying). */
private fun subline(np: NowPlaying) = np.error ?: np.artist.ifBlank { if (np.isLive && np.title != np.station) np.station else np.entry?.subtitle.orEmpty() }

/** Rockbox's music row: shuffle, previous, play, next, repeat. */
@Composable
private fun MusicTransport(np: NowPlaying, play: Int) {
    val cs = MaterialTheme.colorScheme
    val on = { active: Boolean -> if (active) cs.primary else cs.onSurfaceVariant }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (play > 60) 20.dp else 8.dp)) {
        IconButton(onClick = Player::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = on(np.shuffle)) }
        IconButton(onClick = Player::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(if (play > 60) 36.dp else 28.dp)) }
        PlayButton(np, play)
        IconButton(onClick = Player::next, enabled = np.hasNext) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(if (play > 60) 36.dp else 28.dp)) }
        IconButton(onClick = Player::cycleRepeat) {
            Icon(if (np.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = on(np.repeat != Repeat.OFF))
        }
    }
}

/** muSE's podcast row: speed (for this show), back, play, forward. */
@Composable
private fun PodcastTransport(np: NowPlaying, play: Int) {
    val showSpeeds by PodcastPrefs.showSpeeds.collectAsState()
    val default by PodcastPrefs.speed.collectAsState()
    val settings by Settings.state.collectAsState()
    val feed = np.entry?.id?.substringBefore('|')
    val speed = feed?.let { showSpeeds[it] } ?: default
    var menu by remember { mutableStateOf(false) }
    val big = play > 60
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 20.dp else 8.dp)) {
        Box {
            TextButton(onClick = { menu = true }) { Text(if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×", fontWeight = FontWeight.Bold) }
            DropdownMenu(menu, { menu = false }) {
                Text("For this show", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                PodcastPrefs.speeds.forEach { v ->
                    DropdownMenuItem(text = { Text("${v}×", fontWeight = if (v == speed) FontWeight.Bold else null) }, onClick = { Player.setSpeed(v, feed); menu = false })
                }
                if (feed != null && showSpeeds.containsKey(feed)) DropdownMenuItem(text = { Text("Use default (${default}×)") },
                    onClick = { PodcastPrefs.setShowSpeed(feed, null); Player.setSpeed(default, null); menu = false })
            }
        }
        val back = settings.skipBack; val fwd = settings.skipForward
        IconButton(onClick = { Player.skipBy(-back * 1000L) }) {
            Icon(when (back) { 5 -> Icons.Rounded.Replay5; 30 -> Icons.Rounded.Replay30; else -> Icons.Rounded.Replay10 }, "Back $back seconds", Modifier.size(if (big) 36.dp else 28.dp))
        }
        PlayButton(np, play)
        IconButton(onClick = { Player.skipBy(fwd * 1000L) }) {
            Icon(when (fwd) { 5 -> Icons.Rounded.Forward5; 10 -> Icons.Rounded.Forward10; else -> Icons.Rounded.Forward30 }, "Forward $fwd seconds", Modifier.size(if (big) 36.dp else 28.dp))
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** Where you are in the song: drag to scrub (the time follows), let go to jump there. */
@Composable
private fun SeekBar(np: NowPlaying, compact: Boolean) {
    var dragging by remember { mutableStateOf(false) }
    var target by remember { mutableFloatStateOf(0f) }
    val dur = np.durationMs.coerceAtLeast(1).toFloat()
    val style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(if (dragging) target.toLong() else np.positionMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(if (dragging) target else np.positionMs.toFloat().coerceAtMost(dur), { target = it; dragging = true },
            Modifier.weight(1f).padding(horizontal = 10.dp).height(if (compact) 20.dp else 36.dp),
            onValueChangeFinished = { Player.seekTo(target.toLong()); dragging = false }, valueRange = 0f..dur)
        Text(fmt(np.durationMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Along the bottom of the window: what's playing, the transport, favourite and volume. Click it to open Now Playing. */
@Composable
fun PlayerBar(np: NowPlaying, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(np.art, 60.dp, corner = 14.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(np.title.ifBlank { np.station }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subline(np), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (np.error != null) cs.error else cs.onSurfaceVariant)
                }
            }
            if (np.isLive) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = Player::stop) { Icon(Icons.Rounded.Stop, "Stop") }
                PlayButton(np, 56)
                LiveBadge(np.isPlaying)
            } else Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (np.entry?.kind == Entry.Kind.PODCAST) PodcastTransport(np, 44) else MusicTransport(np, 44)
                SeekBar(np, compact = true)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                FavouriteButton(np)
                Volume(np)
                IconButton(onClick = onOpen) { Icon(Icons.Rounded.KeyboardArrowUp, "Now playing") }
            }
        }
    }
}

/**
 * The full player, over everything: the artwork big, blurred behind it all, and Rockbox's order
 * beside it (station, title, artist, the LIVE badge, then the transport), as on the phone.
 */
@Composable
fun NowPlayingScreen(np: NowPlaying, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isMusic = np.entry?.kind == Entry.Kind.MUSIC
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    LaunchedEffect(isMusic) { if (!isMusic) showLyrics = false }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(Modifier.fillMaxSize()) {
            if (!np.art.isNullOrBlank()) AsyncImage(np.art, null, Modifier.fillMaxSize().blur(90.dp).alpha(0.55f), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.surface.copy(alpha = 0.15f), cs.surface.copy(alpha = 0.55f), cs.surface))))
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                Text("NOW PLAYING", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                // The station or episode, not the live title, is what a link can point at.
                if (np.entry != null && np.entry.kind != Entry.Kind.LOCAL) IconButton(onClick = {
                    com.cappyjune.muse.desktop.data.Share.copy(np.entry); com.cappyjune.muse.desktop.ui.Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage())
                }) { Icon(Icons.Rounded.Share, "Copy share link") }
                FavouriteButton(np)
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = 64.dp, end = if (showQueue) 460.dp else 64.dp, top = 80.dp, bottom = 48.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxHeight, maxWidth * 0.45f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    Box(Modifier.size(side).shadow(24.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))) {
                        if (showLyrics && np.entry != null) LyricsView(np.entry.id, np)
                        else Artwork(np.art, side, Modifier.fillMaxSize(), corner = 28.dp)
                    }
                    Column(Modifier.widthIn(max = 560.dp)) {
                        Text((if (np.isLive) np.station else np.entry?.subtitle.orEmpty()).uppercase(), style = MaterialTheme.typography.labelLarge,
                            color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(np.title.ifBlank { np.station }, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val sub = subline(np)
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (np.error != null) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(32.dp))
                        if (np.isLive) {
                            LiveBadge(np.isPlaying)
                            Spacer(Modifier.height(28.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                FilledTonalIconButton(onClick = { Player.stop(); onClose() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.Stop, "Stop", Modifier.size(30.dp)) }
                                PlayButton(np, 88)
                            }
                        } else {
                            SeekBar(np, compact = false)
                            Spacer(Modifier.height(12.dp))
                            if (np.entry?.kind == Entry.Kind.PODCAST) PodcastTransport(np, 80) else MusicTransport(np, 80)
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Volume(np)
                            if (isMusic) FilterChip(showLyrics, { showLyrics = !showLyrics }, label = { Text("Lyrics") }, leadingIcon = { Icon(Icons.Rounded.Lyrics, null, Modifier.size(18.dp)) })
                            if (np.queueSize > 1) FilterChip(showQueue, { showQueue = !showQueue }, label = { Text("Up next") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(18.dp)) })
                            val episode = np.entry?.takeIf { it.kind == Entry.Kind.PODCAST }?.let { Podcasts.episode(it.id) }
                            if (episode != null) AssistChip({ showNotes = true }, label = { Text("Show notes") }, leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) })
                            if (showNotes && episode != null) AlertDialog(onDismissRequest = { showNotes = false }, confirmButton = {},
                                dismissButton = { TextButton(onClick = { showNotes = false }) { Text("Close") } },
                                title = { Text(episode.title) },
                                text = { Box(Modifier.widthIn(min = 480.dp, max = 640.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                                    androidx.compose.foundation.text.selection.SelectionContainer { Text(episode.notes.ifBlank { "No show notes for this episode." }) } } })
                            if (isMusic) AssistChip({ scope.launch { instantMix(np.entry!!.id) } }, label = { Text("Instant mix") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) })
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(showQueue, Modifier.align(Alignment.CenterEnd),
                enter = androidx.compose.animation.slideInHorizontally { it }, exit = androidx.compose.animation.slideOutHorizontally { it }) {
                QueuePanel(np) { showQueue = false }
            }
        }
    }
}

/** Up next: the whole queue. Click to jump, arrows to move a song, ✕ to take it out. */
@Composable
private fun QueuePanel(np: NowPlaying, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val queue = remember(np.queueVersion) { Player.queue() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = np.queueIndex.coerceAtLeast(0))
    var save by remember { mutableStateOf(false) }
    Surface(Modifier.width(420.dp).fillMaxHeight().padding(top = 72.dp, bottom = 24.dp, end = 24.dp), shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainer.copy(alpha = 0.94f), tonalElevation = 4.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Up next", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                // Keep a good Instant Mix (or any queue) as a Jellyfin playlist.
                if (queue.any { it.kind == Entry.Kind.MUSIC }) TextButton(onClick = { save = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null); Text("  Save") }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    itemsIndexed(queue, key = { i, e -> "$i|${e.key}" }) { i, e ->
                        val current = i == np.queueIndex
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (current) cs.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                            .clickable { Player.jumpTo(i) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(e.art, 44.dp, corner = 8.dp, fallback = Icons.Rounded.Album)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(e.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else null,
                                    color = if (current) cs.primary else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (current) Icon(Icons.Rounded.GraphicEq, "Playing", tint = cs.primary)
                            else {
                                IconButton(onClick = { Player.moveItem(i, i - 1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { Player.moveItem(i, i + 1) }, enabled = i < queue.size - 1, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                                IconButton(onClick = { Player.removeAt(i) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Remove from queue") }
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
    if (save) com.cappyjune.muse.desktop.ui.music.AddToPlaylistDialog(queue.filter { it.kind == Entry.Kind.MUSIC }.map { it.id }, onDismiss = { save = false },
        createOnly = true, suggestedName = "Mix · " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date()))
}

/** Jellyfin lyrics over the artwork; synced lines follow the song and stay in view; click one to jump there. */
@Composable
private fun LyricsView(itemId: String, np: NowPlaying) {
    val cs = MaterialTheme.colorScheme
    var lines by remember(itemId) { mutableStateOf<List<Jellyfin.LyricLine>?>(null) }
    LaunchedEffect(itemId) { lines = Jellyfin.lyrics(itemId) }
    val l = lines
    Box(Modifier.fillMaxSize().background(cs.surfaceContainerHigh.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        when {
            l == null -> CircularProgressIndicator()
            l.isEmpty() -> Text("No lyrics for this song.\nAdd the LrcLib plugin in Jellyfin to fetch them.", Modifier.padding(24.dp),
                textAlign = TextAlign.Center, color = cs.onSurfaceVariant)
            else -> {
                val synced = l.any { it.Start != null }
                val current = if (synced) l.indexOfLast { (it.Start ?: Long.MAX_VALUE) / 10_000 <= np.positionMs } else -1
                val state = rememberLazyListState()
                LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem((current - 3).coerceAtLeast(0)) }
                LazyColumn(state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(28.dp)) {
                    itemsIndexed(l) { i, line ->
                        Text(line.Text.ifBlank { "♪" },
                            Modifier.padding(vertical = 6.dp).clickable(enabled = synced && line.Start != null) { Player.seekTo(line.Start!! / 10_000) },
                            style = MaterialTheme.typography.titleLarge, fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium,
                            color = if (!synced || i == current) cs.onSurface else cs.onSurface.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}
