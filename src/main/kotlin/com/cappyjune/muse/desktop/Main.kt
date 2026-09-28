package com.cappyjune.muse.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.cappyjune.muse.desktop.data.Http
import com.cappyjune.muse.desktop.data.Paths
import com.cappyjune.muse.desktop.data.Settings
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.Back
import com.cappyjune.muse.desktop.ui.Links
import com.cappyjune.muse.desktop.data.Share
import androidx.compose.ui.input.key.isCtrlPressed
import com.cappyjune.muse.desktop.ui.MuseApp
import com.cappyjune.muse.desktop.ui.NowPlayingView
import com.cappyjune.muse.desktop.ui.theme.MuseTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.awt.Dimension
import kotlin.system.exitProcess

/** muSE for Ubuntu: one window that opens where it was last closed. */
@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    // Already open: that window comes to the front instead (and opens a link, if one was given).
    if (Instance.handOff(args.toList())) return
    // The dock matches the window to muse.desktop by this (Java would call it "com-cappyjune-...").
    runCatching {
        val tk = java.awt.Toolkit.getDefaultToolkit()
        tk.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(tk, "muse")
    }
    com.cappyjune.muse.desktop.data.Jellyfin.init()
    com.cappyjune.muse.desktop.data.Podcasts.init()
    setupImages()
    val last = Settings.now.window
    val quit = { Player.stop(); Player.shutdown(); Mpris.close(); exitProcess(0) }
    Mpris.onRaise = { Instance.raise++ }
    Mpris.onQuit = quit
    Mpris.start()
    Instance.listen(::openArgs)
    application {
        val state = rememberWindowState(
            size = DpSize((last?.width ?: 1280).dp, (last?.height ?: 820).dp),
            position = last?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            placement = if (last?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        val np by Player.state.collectAsState()
        Window(onCloseRequest = { quit() }, state = state, title = np.entry?.let { "${np.title.ifBlank { np.station }} · muSE" } ?: "muSE",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e ->
                e.type == KeyEventType.KeyDown && when {
                    // Escape: out of Now Playing, else back a page.
                    e.key == Key.Escape -> if (NowPlayingView.open) { NowPlayingView.open = false; true } else Back.back()
                    // Ctrl+V with a muSE link on the clipboard opens it (anything else pastes as usual).
                    e.key == Key.V && e.isCtrlPressed -> Share.fromClipboard()?.let { Links.open(it) } ?: false
                    else -> false
                }
            }) {
            window.minimumSize = Dimension(760, 560)
            LaunchedEffect(Unit) { openArgs(args.toList()) }
            LaunchedEffect(Instance.raise) {
                if (Instance.raise > 0) { if (state.isMinimized) state.isMinimized = false; window.toFront(); window.requestFocus() }
            }
            // The whole app recolours from what's playing.
            MuseTheme(np.art) { MuseApp() }
        }
        // Remember the window's place and size (a moment after it stops moving).
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.position, state.size, state.placement) }.debounce(500).collect { (pos, size, placement) ->
                val p = pos as? WindowPosition.Absolute ?: return@collect
                Settings.update { s ->
                    s.copy(window = Settings.WindowBounds(p.x.value.toInt(), p.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt(),
                        placement == WindowPlacement.Maximized))
                }
            }
        }
    }
}

/** What muSE was started with: audio files ("Open with muSE") play as a queue; muSE links open. */
fun openArgs(args: List<String>) {
    val files = args.map { java.io.File(it) }.filter { it.isFile && it.extension.lowercase() in com.cappyjune.muse.desktop.data.LocalMusic.EXTENSIONS }
    if (files.isNotEmpty()) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        val entries = files.map { f ->
            com.cappyjune.muse.desktop.data.Entry(com.cappyjune.muse.desktop.data.Entry.Kind.LOCAL, f.absolutePath, f.nameWithoutExtension, f.parentFile?.name.orEmpty())
        }
        javax.swing.SwingUtilities.invokeLater { Player.playQueue(entries) }
    }
    args.filterNot { a -> files.any { it.path == a } }.forEach { Links.open(it) }
}

/** Artwork through the app's own client, cached on disk. */
fun setupImages() = SingletonImageLoader.setSafe { ctx ->
    ImageLoader.Builder(ctx)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
        .diskCache { DiskCache.Builder().directory(Paths.cache.resolve("images").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        .build()
}
