package com.cappyjune.muse.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.library.LibraryScreen
import com.cappyjune.muse.desktop.ui.player.NowPlayingScreen
import com.cappyjune.muse.desktop.ui.player.PlayerBar
import com.cappyjune.muse.desktop.ui.radio.RadioScreen

/** muSE's sections, the phone's bottom bar as a side menu; the colour is its wordmark letter's. */
enum class Section(val label: String, val icon: ImageVector?, val colour: Int, val stage: Int = 0) {
    RADIO("Radio", Icons.Rounded.Radio, 0),
    PODCASTS("Podcasts", Icons.Rounded.Podcasts, 1),
    JELLYFIN("Jellyfin", null, -1),
    LOCAL("On this computer", Icons.Rounded.Computer, 2),
    LIBRARY("Library", Icons.Rounded.LibraryMusic, 3),
}

@Composable
fun SectionIcon(s: Section, size: androidx.compose.ui.unit.Dp = 24.dp) {
    if (s.icon == null) JellyfinLogo(size) else Icon(s.icon, null, Modifier.size(size), tint = museColours()[s.colour])
}

@Composable
fun SectionTitle(s: Section) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionIcon(s, 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(s.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

/** Whether the full Now Playing view is up; Main closes it on Escape. */
object NowPlayingView { var open by mutableStateOf(false) }

/**
 * A permanent side menu (an icon rail when the window's narrow), the section beside it, and
 * the player bar along the bottom, which opens into Now Playing.
 */
@Composable
fun MuseApp() {
    val np by Player.state.collectAsState()
    var section by remember { mutableStateOf(System.getProperty("muse.section")?.let { Section.valueOf(it) } ?: Section.RADIO) }
    var settings by remember { mutableStateOf(false) }
    val pick: (Section) -> Unit = { section = it; settings = false }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Messages.current) { Messages.current?.let { snackbar.showSnackbar(it, withDismissAction = true); Messages.current = null } }
    LaunchedEffect(np.entry == null) { if (np.entry == null) NowPlayingView.open = false }
    // A link needs a section showing: go there.
    LaunchedEffect(Links.section) { Links.section?.let { section = it; settings = false; NowPlayingView.open = false; Links.section = null } }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 1000.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide) PermanentDrawerSheet(Modifier.width(240.dp)) {
                            Box(Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) { MuseLogo(big = true) }
                            Section.entries.forEach { s ->
                                if (s == Section.LIBRARY) HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 10.dp))
                                NavigationDrawerItem(label = { Text(s.label) }, selected = !settings && section == s, onClick = { pick(s) },
                                    icon = { SectionIcon(s) }, modifier = Modifier.padding(horizontal = 12.dp),
                                    // A neutral highlight, so the coloured icons keep their contrast.
                                    colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest))
                            }
                            Spacer(Modifier.weight(1f))
                            NavigationDrawerItem(label = { Text("Settings") }, selected = settings, onClick = { settings = true },
                                icon = { Icon(Icons.Rounded.Settings, null) }, modifier = Modifier.padding(12.dp),
                                colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest))
                        } else NavigationRail(Modifier.fillMaxHeight(), header = { Box(Modifier.padding(vertical = 12.dp)) { MuseLogo() } }) {
                            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Section.entries.forEach { s ->
                                    NavigationRailItem(selected = !settings && section == s, onClick = { pick(s) }, icon = { SectionIcon(s) },
                                        label = { Text(if (s == Section.LOCAL) "Local" else s.label) },
                                        colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest))
                                }
                                Spacer(Modifier.weight(1f))
                                NavigationRailItem(selected = settings, onClick = { settings = true }, icon = { Icon(Icons.Rounded.Settings, "Settings") },
                                    label = { Text("Settings") })
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        AnimatedContent(if (settings) null else section, Modifier.weight(1f).fillMaxHeight(),
                            transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
                            when (s) {
                                null -> SettingsScreen()
                                Section.RADIO -> RadioScreen()
                                Section.LIBRARY -> LibraryScreen()
                                Section.JELLYFIN -> com.cappyjune.muse.desktop.ui.music.JellyfinScreen()
                                Section.PODCASTS -> com.cappyjune.muse.desktop.ui.podcasts.PodcastsScreen()
                                Section.LOCAL -> com.cappyjune.muse.desktop.ui.local.LocalScreen()
                                else -> ComingSoon(s)
                            }
                        }
                    }
                }
                AnimatedVisibility(np.entry != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    PlayerBar(np, onOpen = { NowPlayingView.open = true })
                }
            }
            AnimatedVisibility(NowPlayingView.open && np.entry != null, Modifier.fillMaxSize().zIndex(1f),
                enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
                NowPlayingScreen(np, onClose = { NowPlayingView.open = false })
            }
            ShowOfferDialog()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp).zIndex(2f))
        }
    }
}

/** A section that isn't built yet: its heading, and which stage brings it. */
@Composable
private fun ComingSoon(s: Section) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(s)
        Text("Coming in stage ${s.stage}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

