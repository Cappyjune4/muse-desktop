package com.cappyjune.muse.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cappyjune.muse.desktop.data.Jellyfin
import com.cappyjune.muse.desktop.data.JfItem
import com.cappyjune.muse.desktop.data.Podcast
import com.cappyjune.muse.desktop.data.Podcasts
import com.cappyjune.muse.desktop.data.Radio
import com.cappyjune.muse.desktop.data.Share
import com.cappyjune.muse.desktop.data.Shared
import com.cappyjune.muse.desktop.playback.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Opening a muSE share link (pasted in, or `muse <link>`): the same links the phones make and
 * open. Songs, stations and episodes play; albums, playlists and artists open in Jellyfin; a
 * show offers to subscribe.
 */
object Links {
    /** A Jellyfin page for the Jellyfin section to open: the item and what kind of page. */
    var jellyfinPage by mutableStateOf<Pair<JfItem, String>?>(null)
    /** A show someone shared that you're not subscribed to, offered in a dialog. */
    var showOffer by mutableStateOf<Pair<Podcast, String>?>(null)
    /** A show you are subscribed to, for the Podcasts section to open. */
    var podcastPage by mutableStateOf<String?>(null)
    /** Which section the link needs showing; MuseApp switches to it. */
    var section by mutableStateOf<Section?>(null)

    private val scope = CoroutineScope(Dispatchers.Swing)

    /** True if [text] held a muSE link (it's then being opened). */
    fun open(text: String): Boolean {
        val s = Share.parse(text.trim()) ?: return false
        scope.launch { runCatching { handle(s) }.onFailure { Messages.show("Couldn't open that link: ${it.message}") } }
        return true
    }

    private suspend fun jellyfin(id: String): JfItem {
        if (Jellyfin.account.value == null) { section = Section.JELLYFIN; error("sign in to Jellyfin first") }
        return Jellyfin.item(id) ?: error("it isn't on your Jellyfin server, or your account can't see it")
    }

    private suspend fun handle(s: Shared) {
        when (s) {
            is Shared.Song -> { val i = jellyfin(s.id); Player.playQueue(listOf(Jellyfin.toEntry(i))); Messages.show("Playing ${i.Name}") }
            is Shared.Album -> { jellyfinPage = jellyfin(s.id) to "album"; section = Section.JELLYFIN }
            is Shared.Playlist -> { jellyfinPage = jellyfin(s.id) to "playlist"; section = Section.JELLYFIN }
            is Shared.Artist -> { jellyfinPage = jellyfin(s.id) to "artist"; section = Section.JELLYFIN }
            is Shared.Station -> {
                val e = s.entry ?: s.rbId?.let { Radio.byUuid(it) } ?: error("that station isn't in the directory any more")
                Player.playRadio(e); Messages.show("Playing ${e.title}")
            }
            is Shared.Show -> {
                if (Podcasts.podcast(s.feedUrl) != null) { podcastPage = s.feedUrl; section = Section.PODCASTS }
                else showOffer = Podcasts.preview(s.feedUrl).first to s.feedUrl
            }
            is Shared.Episode -> {
                val (pod, eps) = Podcasts.preview(s.feedUrl)
                val ep = eps.firstOrNull { Share.tag(it.guid) == s.tag } ?: error("that episode isn't in ${pod.title}'s feed any more")
                Podcasts.visit(ep)
                Player.playEpisode(ep); Messages.show("Playing ${ep.title}")
            }
        }
    }
}

/** "Subscribe to …?" for a shared show. */
@Composable
fun ShowOfferDialog() {
    val (pod, feed) = Links.showOffer ?: return
    val scope = rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { Links.showOffer = null }, icon = { androidx.compose.material3.Icon(Icons.Rounded.Podcasts, null) },
        title = { Text("Subscribe to ${pod.title}?") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(pod.art, 96.dp, corner = 16.dp, fallback = Icons.Rounded.Podcasts)
                Column(Modifier.widthIn(max = 380.dp)) {
                    Text(pod.author, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(pod.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { Podcasts.subscribe(feed) }.onSuccess { Links.podcastPage = it.feedUrl; Links.section = Section.PODCASTS }
                    .onFailure { Messages.show("Couldn't load that feed: ${it.message}") }
                Links.showOffer = null
            }
        }) { Text(if (busy) "Subscribing…" else "Subscribe", fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { Links.showOffer = null }) { Text("Not now") } })
}

/** After "Copy share link": it's on the clipboard, or sharing needs its address first. */
fun copiedMessage() = if (Share.enabled) "Link copied: paste it into Signal or anywhere" else "Add a share link address in Settings to copy links"
