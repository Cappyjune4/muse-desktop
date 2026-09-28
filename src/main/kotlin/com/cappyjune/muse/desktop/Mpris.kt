package com.cappyjune.muse.desktop

import com.cappyjune.muse.desktop.playback.NowPlaying
import com.cappyjune.muse.desktop.playback.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import javax.swing.SwingUtilities

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * muSE as an MPRIS player, the desktop's version of the phone's media notification: GNOME's
 * media controls (top bar and lock screen) show what's playing with its artwork, and the
 * keyboard's play/pause, next and previous keys work, whether or not muSE has focus.
 */
object Mpris : MediaPlayer2, MediaPlayer2Player, Properties {
    private const val PATH = "/org/mpris/MediaPlayer2"
    private const val ROOT = "org.mpris.MediaPlayer2"
    private const val PLAYER = "org.mpris.MediaPlayer2.Player"
    private var conn: DBusConnection? = null
    /** Main hooks these up: bring the window forward, and quit. */
    var onRaise: () -> Unit = {}
    var onQuit: () -> Unit = {}

    fun start() {
        runCatching {
            val c = DBusConnectionBuilder.forSessionBus().build()
            c.requestBusName("org.mpris.MediaPlayer2.muse")
            c.exportObject(PATH, this)
            conn = c
            // Tell GNOME whenever what it shows has changed.
            CoroutineScope(Dispatchers.Default).launch {
                Player.state.map { listOf(status(it), metadata(it).toString(), it.volume, it.hasNext, it.hasPrevious, it.shuffle, it.repeat) }.distinctUntilChanged().collect {
                    val s = Player.state.value
                    signal(PLAYER, mapOf("PlaybackStatus" to Variant(status(s)), "Metadata" to Variant(metadata(s), "a{sv}"),
                        "CanGoNext" to Variant(s.hasNext), "CanGoPrevious" to Variant(!s.isLive && s.entry != null), "CanPlay" to Variant(s.entry != null),
                        "CanPause" to Variant(s.entry != null), "CanSeek" to Variant(!s.isLive && s.durationMs > 0), "Volume" to Variant(s.volume / 100.0),
                        "Shuffle" to Variant(s.shuffle), "LoopStatus" to Variant(loop(s))))
                }
            }
        }.onFailure { System.err.println("muSE: media controls unavailable: ${it.message}") }
    }

    fun close() = runCatching { conn?.close() }

    private fun signal(iface: String, changed: Map<String, Variant<*>>) {
        val c = conn ?: return
        runCatching { c.sendMessage(Properties.PropertiesChanged(PATH, iface, changed, emptyList())) }
    }

    private fun loop(s: NowPlaying) = when (s.repeat) { com.cappyjune.muse.desktop.playback.Repeat.OFF -> "None"; com.cappyjune.muse.desktop.playback.Repeat.ALL -> "Playlist"; else -> "Track" }

    private fun status(s: NowPlaying) = when { s.entry == null -> "Stopped"; s.isPlaying || s.isBuffering -> "Playing"; else -> "Paused" }

    /** What GNOME shows: the track (or station), who by, and the artwork. */
    private fun metadata(s: NowPlaying): Map<String, Variant<*>> {
        val e = s.entry ?: return mapOf("mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")))
        val id = "/com/cappyjune/muse/track/" + Integer.toHexString(e.key.hashCode())
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(id)))
            put("xesam:title", Variant(s.title.ifBlank { e.title }))
            // Radio: the artist if the station says, the station's name under it either way.
            val artist = s.artist.ifBlank { if (s.isLive) s.station else e.subtitle }
            if (artist.isNotBlank()) put("xesam:artist", Variant(listOf(artist), "as"))
            put("xesam:album", Variant(if (s.isLive) s.station else e.subtitle))
            s.art?.takeIf { it.isNotBlank() }?.let { put("mpris:artUrl", Variant(it)) }
            if (s.durationMs > 0) put("mpris:length", Variant(s.durationMs * 1000))
        }
    }

    private fun onUi(block: () -> Unit) = SwingUtilities.invokeLater(block)

    // org.mpris.MediaPlayer2
    override fun Raise() = onUi { onRaise() }
    override fun Quit() = onUi { onQuit() }
    // org.mpris.MediaPlayer2.Player
    override fun Next() = onUi { Player.next() }
    override fun Previous() = onUi { Player.previous() }
    override fun Pause() = onUi { if (Player.state.value.isPlaying) Player.togglePlay() }
    override fun PlayPause() = onUi { Player.togglePlay() }
    override fun Stop() = onUi { Player.stop() }
    override fun Play() = onUi { if (!Player.state.value.isPlaying) Player.togglePlay() }
    override fun Seek(offset: Long) = onUi { Player.seekBy(offset / 1000) }
    override fun SetPosition(trackId: DBusPath, position: Long) = onUi { Player.seekTo(position / 1000) }
    override fun OpenUri(uri: String) {}

    override fun getObjectPath() = PATH

    private fun props(iface: String): Map<String, Variant<*>> {
        val s = Player.state.value
        return when (iface) {
            ROOT -> mapOf(
                "CanQuit" to Variant(true), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
                "Identity" to Variant("muSE"), "DesktopEntry" to Variant("muse"),
                "SupportedUriSchemes" to Variant(listOf("http", "https"), "as"), "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
            )
            PLAYER -> mapOf(
                "PlaybackStatus" to Variant(status(s)), "LoopStatus" to Variant(loop(s)), "Rate" to Variant(1.0), "Shuffle" to Variant(s.shuffle),
                "Metadata" to Variant(metadata(s), "a{sv}"), "Volume" to Variant(s.volume / 100.0), "Position" to Variant(s.positionMs * 1000),
                "MinimumRate" to Variant(1.0), "MaximumRate" to Variant(1.0),
                "CanGoNext" to Variant(s.hasNext), "CanGoPrevious" to Variant(!s.isLive && s.entry != null), "CanPlay" to Variant(s.entry != null),
                "CanPause" to Variant(s.entry != null), "CanSeek" to Variant(!s.isLive && s.durationMs > 0), "CanControl" to Variant(true),
            )
            else -> emptyMap()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(iface: String, name: String): A = props(iface)[name] as A
    override fun GetAll(iface: String): Map<String, Variant<*>> = props(iface)
    override fun <A : Any?> Set(iface: String, name: String, value: A) {
        if (iface == PLAYER && name == "Volume") {
            val v = ((value as? Variant<*>)?.value ?: value) as? Double ?: return
            onUi { Player.changeVolume((v * 100).toInt()) }
        }
        if (iface == PLAYER && name == "Shuffle") onUi { val on = ((value as? Variant<*>)?.value ?: value) == true; if (on != Player.state.value.shuffle) Player.toggleShuffle() }
    }
}
