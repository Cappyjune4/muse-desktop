package com.cappyjune.muse.desktop.spike

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.cappyjune.muse.desktop.data.Radio
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.setupImages
import com.cappyjune.muse.desktop.ui.MuseApp
import com.cappyjune.muse.desktop.ui.NowPlayingView
import com.cappyjune.muse.desktop.ui.theme.MuseTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities

/**
 * For checking the UI without a screen (Wayland doesn't let tools capture windows): draws the
 * app off-screen and saves a PNG. Usage: Render <out.png> [width] [height]
 * -Dmuse.section=RADIO|LIBRARY…, -Dmuse.play=<station search> plays the first match first,
 * -Dmuse.np=true opens Now Playing, -Dmuse.settle=<seconds> waits for the network.
 */
fun main(args: Array<String>) {
    setupImages()
    System.setProperty("muse.mute", "true")
    com.cappyjune.muse.desktop.Mpris.start()
    val w = args.getOrNull(1)?.toInt() ?: 1400; val h = args.getOrNull(2)?.toInt() ?: 900
    System.getProperty("muse.play")?.let { q ->
        val e = kotlinx.coroutines.runBlocking { if (q.startsWith("abc:")) com.cappyjune.muse.desktop.data.Abc.stations.first { it.abc == q.removePrefix("abc:") } else Radio.search(q).first() }
        SwingUtilities.invokeAndWait { Player.playRadio(e); NowPlayingView.open = System.getProperty("muse.np") == "true" }
    }
    // -Dmuse.podcast=<search>: subscribe to the first match (use a throwaway XDG_DATA_HOME), -Dmuse.episode=true plays its latest.
    com.cappyjune.muse.desktop.data.Podcasts.init()
    System.getProperty("muse.podcast")?.let { q ->
        kotlinx.coroutines.runBlocking {
            val r = com.cappyjune.muse.desktop.data.PodcastSearch.search(q).first()
            val pod = com.cappyjune.muse.desktop.data.Podcasts.subscribe(r.feedUrl)
            val eps = com.cappyjune.muse.desktop.data.Podcasts.episodes.value[pod.feedUrl].orEmpty()
            println("podcast: ${pod.title} · ${eps.size} episodes · first: ${eps.firstOrNull()?.title} · notes ${eps.firstOrNull()?.notes?.take(80)}")
            if (System.getProperty("muse.episode") == "true") eps.firstOrNull()?.let { ep ->
                com.cappyjune.muse.desktop.data.Podcasts.savePosition(ep.key, 125_000, ep.durationMs.coerceAtLeast(600_000), force = true)
                SwingUtilities.invokeAndWait { Player.playEpisode(ep); NowPlayingView.open = System.getProperty("muse.np") == "true" }
            }
        }
    }
    // -Dmuse.localplay=true: play the "On this computer" albums in order, to watch the queue move on.
    if (System.getProperty("muse.localplay") == "true") kotlinx.coroutines.runBlocking {
        com.cappyjune.muse.desktop.data.LocalMusic.scan(com.cappyjune.muse.desktop.data.Settings.now.musicFolders)
        val t = com.cappyjune.muse.desktop.data.LocalMusic.albums(com.cappyjune.muse.desktop.data.LocalMusic.tracks.value.orEmpty()).flatMap { it.tracks }
        val e = t.map(com.cappyjune.muse.desktop.data.LocalMusic::toEntry)
        SwingUtilities.invokeAndWait { Player.playQueue(e) }
        repeat(24) { Thread.sleep(500); Player.state.value.let { s -> println("t=${it / 2.0}s  ${s.queueIndex + 1}/${s.queueSize} ${s.title} pos=${s.positionMs}") } }
    }
    // -Dmuse.link=<share link>: open it, as a paste would.
    System.getProperty("muse.link")?.let { l -> SwingUtilities.invokeAndWait { println("link recognised: " + com.cappyjune.muse.desktop.ui.Links.open(l)) } }
    val settle = System.getProperty("muse.settle")?.toInt() ?: 2
    val scene = ImageComposeScene(w, h, Density(1f)) {
        val np by Player.state.collectAsState()
        MuseTheme(np.art) { MuseApp() }
    }
    repeat(settle * 4) { scene.render(it * 250_000_000L); Thread.sleep(250) }
    val img = scene.render(settle * 1_000_000_000L + 500_000_000L)
    File(args[0]).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("state: " + Player.state.value.let { "${it.station} | ${it.title} | ${it.artist} | playing=${it.isPlaying} buffering=${it.isBuffering} err=${it.error} pos=${it.positionMs} dur=${it.durationMs}" })
    Player.stop(); Player.shutdown()
    kotlin.system.exitProcess(0)
}
