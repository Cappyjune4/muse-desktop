package com.cappyjune.muse.desktop.playback

import com.cappyjune.muse.desktop.data.Abc
import com.cappyjune.muse.desktop.data.Entry
import com.cappyjune.muse.desktop.data.Jellyfin
import com.cappyjune.muse.desktop.data.Episode
import com.cappyjune.muse.desktop.data.Library
import com.cappyjune.muse.desktop.data.PodcastPrefs
import com.cappyjune.muse.desktop.data.Podcasts
import com.cappyjune.muse.desktop.data.Radio
import com.cappyjune.muse.desktop.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.util.Locale

enum class Repeat { OFF, ALL, ONE }

/** What's playing, as the screens (and GNOME's media controls) see it. The phone's NowPlaying. */
data class NowPlaying(
    val entry: Entry? = null,
    val title: String = "",
    val artist: String = "",
    val station: String = "",
    val art: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    /** Bumped whenever the queue itself changes (not just the position in it), for the Up next list. */
    val queueVersion: Int = 0,
) {
    val isLive get() = entry?.kind == Entry.Kind.RADIO
    val hasNext get() = queueIndex < queueSize - 1 || (repeat == Repeat.ALL && queueSize > 1)
    val hasPrevious get() = queueIndex > 0
}

/**
 * The one player: mpv, the queue, and what it's all doing as a StateFlow. The phone's
 * PlaybackService and PlayerConnection in one, since on the desktop the app is what plays.
 * Songs go through mpv's own playlist, two at a time (this one and the next), so an album
 * plays through with no gaps.
 */
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val _state = MutableStateFlow(NowPlaying(volume = Settings.now.volume))
    val state: StateFlow<NowPlaying> = _state.asStateFlow()
    private var mpv: MpvPlayer? = null
    private var abcJob: Job? = null
    private var retryJob: Job? = null
    private var retries = 0
    /** When the current stream was asked for: a stream that hasn't started a while after is an error. */
    private var loadedAt = 0L
    private var wantPlaying = false

    /** The queue, in play order (already shuffled when shuffle's on), and the pre-shuffle order. */
    private var queue: List<Entry> = emptyList()
    private var unshuffled: List<Entry> = emptyList()
    private var index = 0
    /** Which queue position mpv has lined up after the current song, if any. */
    private var appended: Int? = null
    /** The Jellyfin song Jellyfin was told is playing, for its "stopped" report. */
    private var reported: String? = null

    /** For the developer render: play silently. */
    var startMuted = System.getProperty("muse.mute") == "true"

    private fun player(): MpvPlayer = mpv ?: MpvPlayer().also { p ->
        mpv = p
        p.set("volume", Settings.now.volume.toString())
        if (startMuted) p.set("mute", "yes")
        scope.launch { while (isActive) { poll(p); delay(250) } }
    }

    fun queue(): List<Entry> = queue

    // --- radio -----------------------------------------------------------------------------------

    /** Plays a station, at its current stream (radio-browser knows if it moved), and remembers it. */
    fun playRadio(e: Entry) {
        scope.launch {
            val fresh = Radio.resolve(e)
            Library.recordPlay(fresh); Library.refresh(fresh)
            queue = listOf(fresh); unshuffled = queue; index = 0
            loadLive(fresh)
        }
    }

    private fun loadLive(e: Entry) {
        val p = player()
        reportStopped()
        retryJob?.cancel(); retries = 0; appended = null
        setCurrent(e)
        wantPlaying = true; loadedAt = System.currentTimeMillis()
        p.set("http-header-fields", ""); p.set("af", ""); p.set("loop-file", "no")
        p.set("pause", "no")
        p.command("loadfile", e.id, "replace")
        followAbc(e)
    }

    // --- songs -----------------------------------------------------------------------------------

    /** Plays [list] from [start]; with [shuffle], in a random order (the chosen song first, if one was). */
    fun playQueue(list: List<Entry>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        unshuffled = list
        if (shuffle) {
            queue = list.shuffled(); index = 0
        } else { queue = list; index = start.coerceIn(list.indices) }
        _state.update { it.copy(shuffle = shuffle) }
        Library.recordPlay(queue[index])
        loadSong()
    }

    // --- podcasts --------------------------------------------------------------------------------

    /** Plays an episode from where it was left (a few seconds back, to pick up the thread), downloaded copy first. */
    fun playEpisode(ep: Episode) {
        val pod = Podcasts.podcast(ep.feedUrl)
        val e = Entry(Entry.Kind.PODCAST, ep.key, ep.title, pod?.title.orEmpty(), ep.art.ifBlank { pod?.art.orEmpty() }, durationMs = ep.durationMs)
        Library.recordPlay(e)
        queue = listOf(e); unshuffled = queue; index = 0
        _state.update { it.copy(shuffle = false) }
        loadSong()
    }

    /** Skip back or forward within an episode or song. */
    fun skipBy(ms: Long) = seekBy(ms)

    /** A podcast's speed: for this show, or ([feed] null) the default for every show. */
    fun setSpeed(v: Float, feed: String?) {
        if (feed != null) PodcastPrefs.setShowSpeed(feed, v) else PodcastPrefs.setSpeed(v)
        if (_state.value.entry?.kind == Entry.Kind.PODCAST) mpv?.set("speed", String.format(Locale.ROOT, "%.2f", v))
    }

    private fun url(e: Entry) = when (e.kind) {
        Entry.Kind.MUSIC -> Jellyfin.streamUrl(e.id)
        Entry.Kind.PODCAST -> Podcasts.localFile(e.id)?.path ?: Podcasts.episode(e.id)?.audioUrl ?: ""
        else -> e.id
    }

    private var lastSave = 0L

    /** Where the episode's got to, for "Continue listening" (and, later, other devices). */
    private fun saveEpisode(force: Boolean = false, finished: Boolean = false) {
        val s = _state.value
        val e = s.entry?.takeIf { it.kind == Entry.Kind.PODCAST } ?: return
        if (s.durationMs <= 0) return
        Podcasts.savePosition(e.id, if (finished) s.durationMs else s.positionMs, s.durationMs, force)
    }

    /** Loads the current song into mpv (replacing whatever was there) and lines up the next. */
    private fun loadSong() {
        val p = player()
        abcJob?.cancel(); retryJob?.cancel(); reportStopped()
        saveEpisode(force = true)
        val e = queue.getOrNull(index) ?: return
        // Jellyfin streams are authorised by header, never by a token in the URL (and nothing
        // else, a podcast's host say, ever sees it).
        p.set("http-header-fields", if (queue.any { it.kind == Entry.Kind.MUSIC }) "Authorization: " + Jellyfin.authHeader().replace(",", "\\,") else "")
        p.set("loop-file", if (_state.value.repeat == Repeat.ONE && e.kind == Entry.Kind.MUSIC) "inf" else "no")
        val pod = e.kind == Entry.Kind.PODCAST
        p.set("speed", if (pod) String.format(Locale.ROOT, "%.2f", PodcastPrefs.speedFor(e.id.substringBefore('|'))) else "1")
        setCurrent(e)
        wantPlaying = true; loadedAt = System.currentTimeMillis()
        p.set("pause", "no")
        val resume = if (pod) Podcasts.progress.value[e.id]?.takeIf { !it.played && it.positionMs > 10_000 }?.let { (it.positionMs - 5_000) / 1000.0 } else null
        if (resume != null) p.command("loadfile", url(e), "replace", "-1", String.format(Locale.ROOT, "start=%.1f", resume))
        else p.command("loadfile", url(e), "replace")
        appended = null
        lineUpNext()
        startedSong(e)
    }

    private fun nextIndex(): Int? = when {
        _state.value.repeat == Repeat.ONE -> null  // mpv loops the file itself
        index + 1 < queue.size -> index + 1
        _state.value.repeat == Repeat.ALL && queue.size > 1 -> 0
        else -> null
    }

    /** Puts the next song after the current one in mpv's playlist (and nothing else after it). */
    private fun lineUpNext() {
        val p = mpv ?: return
        if (_state.value.isLive) return
        if (appended != null) { p.command("playlist-remove", "1"); appended = null }
        val n = nextIndex() ?: return
        p.command("loadfile", url(queue[n]), "append")
        appended = n
    }

    private fun setCurrent(e: Entry) {
        val artist = if (e.kind == Entry.Kind.RADIO) "" else e.subtitle.substringBefore(" · ")
        _state.update {
            it.copy(entry = e, station = e.title, title = e.title, artist = artist, art = e.art.ifBlank { null }, isBuffering = true, error = null,
                positionMs = 0, durationMs = e.durationMs, queueIndex = index, queueSize = queue.size, queueVersion = it.queueVersion + 1)
        }
        applyLevelling(e)
    }

    /**
     * Volume levelling from Jellyfin's NormalizationGain (ReplayGain track gain), as on the phone:
     * only turning loud masters down to meet quiet ones, never up (which would clip).
     */
    private fun applyLevelling(e: Entry) {
        val g = e.gainDb?.takeIf { Settings.now.levelling && e.kind == Entry.Kind.MUSIC }?.coerceAtMost(0.0)
        mpv?.set("af", if (g == null || g == 0.0) "" else String.format(Locale.ROOT, "volume=volume=%.2fdB", g))
    }

    /** Tell Jellyfin what's playing, so its play counts and "recently played" stay right. */
    private fun startedSong(e: Entry) {
        if (e.kind != Entry.Kind.MUSIC) return
        reported = e.id
        scope.launch { Jellyfin.reportStart(e.id) }
    }

    private fun reportStopped(finished: Boolean = false) {
        val id = reported ?: return
        reported = null
        val s = _state.value
        val at = if (finished) s.durationMs else s.positionMs
        scope.launch { Jellyfin.reportStop(id, at) }
    }

    fun next() {
        if (_state.value.isLive) return
        val n = when {
            index + 1 < queue.size -> index + 1
            _state.value.repeat != Repeat.OFF && queue.isNotEmpty() -> 0
            else -> return
        }
        index = n; loadSong()
    }

    /** Past the first few seconds, back to the start of this song; otherwise the one before. */
    fun previous() {
        if (_state.value.isLive) return
        if (_state.value.positionMs > 5_000 || index == 0) seekTo(0) else { index--; loadSong() }
    }

    fun jumpTo(i: Int) { if (i in queue.indices) { index = i; loadSong() } }

    fun seekTo(ms: Long) {
        mpv?.command("seek", String.format(Locale.ROOT, "%.2f", ms / 1000.0), "absolute")
        _state.update { it.copy(positionMs = ms) }
    }

    fun seekBy(ms: Long) = seekTo((_state.value.positionMs + ms).coerceIn(0, _state.value.durationMs.coerceAtLeast(0)))

    fun toggleShuffle() {
        if (_state.value.isLive || queue.isEmpty()) return
        val cur = queue[index]
        if (_state.value.shuffle) { queue = unshuffled; index = queue.indexOf(cur).coerceAtLeast(0) }
        else { queue = listOf(cur) + (queue - cur).shuffled(); index = 0 }
        _state.update { it.copy(shuffle = !it.shuffle, queueIndex = index, queueSize = queue.size, queueVersion = it.queueVersion + 1) }
        lineUpNext()
    }

    fun cycleRepeat() {
        val r = when (_state.value.repeat) { Repeat.OFF -> Repeat.ALL; Repeat.ALL -> Repeat.ONE; Repeat.ONE -> Repeat.OFF }
        _state.update { it.copy(repeat = r) }
        mpv?.set("loop-file", if (r == Repeat.ONE) "inf" else "no")
        lineUpNext()
    }

    /** Right after the current song. */
    fun playNext(entries: List<Entry>) = editQueue { q -> q.addAll((index + 1).coerceAtMost(q.size), entries); unshuffled = unshuffled + entries }

    /** At the end of the queue. */
    fun addToQueue(entries: List<Entry>) = editQueue { q -> q.addAll(entries); unshuffled = unshuffled + entries }

    fun removeAt(i: Int) {
        if (i == index || i !in queue.indices) return
        editQueue { q -> val e = q.removeAt(i); if (i < index) index--; unshuffled = unshuffled - e }
    }

    fun moveItem(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        editQueue { q ->
            val e = q.removeAt(from); q.add(to, e)
            index = when { from == index -> to; from < index && to >= index -> index - 1; from > index && to <= index -> index + 1; else -> index }
        }
    }

    private fun editQueue(edit: (MutableList<Entry>) -> Unit) {
        if (_state.value.entry == null || _state.value.isLive) {
            // Nothing (or radio) playing: whatever was added starts now.
            val q = mutableListOf<Entry>(); edit(q); if (q.isNotEmpty()) playQueue(q); return
        }
        val q = queue.toMutableList(); edit(q); queue = q
        _state.update { it.copy(queueIndex = index, queueSize = queue.size, queueVersion = it.queueVersion + 1) }
        lineUpNext()
    }

    // --- controls --------------------------------------------------------------------------------

    fun togglePlay() {
        val s = _state.value
        val e = s.entry ?: return
        val atEnd = mpv?.get("eof-reached") == "yes"
        when {
            s.isPlaying || (wantPlaying && s.isBuffering) -> { wantPlaying = false; mpv?.set("pause", "yes"); saveEpisode(force = true); _state.update { it.copy(isPlaying = false, isBuffering = false) } }
            // Live radio picks up at the live edge, not from minutes-old buffered audio.
            s.isLive -> loadLive(e)
            atEnd -> { index = 0; loadSong() }
            else -> { wantPlaying = true; mpv?.set("pause", "no") }
        }
    }

    /** Stops and closes the player bar. */
    fun stop() {
        abcJob?.cancel(); retryJob?.cancel(); wantPlaying = false
        reportStopped(); saveEpisode(force = true)
        mpv?.command("stop")
        queue = emptyList(); unshuffled = emptyList(); index = 0; appended = null
        _state.update { NowPlaying(volume = it.volume, muted = it.muted, repeat = it.repeat) }
    }

    fun changeVolume(v: Int) {
        val n = v.coerceIn(0, 100)
        mpv?.set("volume", n.toString()); if (n > 0) mpv?.set("mute", "no")
        _state.update { it.copy(volume = n, muted = if (n > 0) false else it.muted) }
        Settings.update { it.copy(volume = n) }
    }

    fun toggleMute() {
        val m = !_state.value.muted
        mpv?.set("mute", if (m) "yes" else "no"); _state.update { it.copy(muted = m) }
    }

    /** Settings changed levelling: the song playing now follows at once. */
    fun levellingChanged() { _state.value.entry?.let(::applyLevelling) }

    // --- following mpv ---------------------------------------------------------------------------

    private fun poll(p: MpvPlayer) {
        val s = _state.value
        if (s.entry == null) return
        // mpv moved on to the song lined up after this one: that's the current song now.
        if (!s.isLive && appended != null && p.get("playlist-pos") == "1") {
            reportStopped(finished = true)
            index = appended!!; appended = null
            p.command("playlist-remove", "0")
            setCurrent(queue[index]); startedSong(queue[index])
            lineUpNext()
            return
        }
        val idle = p.get("idle-active") == "yes"
        val eof = p.get("eof-reached") == "yes"
        val paused = p.get("pause") == "yes"
        val waiting = p.get("paused-for-cache") == "yes" || p.get("seeking") == "yes"
        val playing = !idle && !eof && !paused && !waiting && p.get("core-idle") == "no"
        _state.update { it.copy(isPlaying = playing, isBuffering = wantPlaying && !playing && !eof && (!idle || System.currentTimeMillis() - loadedAt < 1500),
            positionMs = ((p.get("time-pos")?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            durationMs = if (it.isLive) 0 else ((p.get("duration")?.toDoubleOrNull()?.times(1000))?.toLong() ?: it.durationMs)) }
        if (playing) { retries = 0; if (s.error != null) _state.update { it.copy(error = null) } }
        if (playing && System.currentTimeMillis() - lastSave > 5_000) { lastSave = System.currentTimeMillis(); saveEpisode() }

        // Radio: the station's own "Artist - Title" (ICY), unless it's ABC (which has no ICY; see followAbc).
        if (s.isLive && s.entry.abc == null) {
            val raw = (p.get("metadata/by-key/icy-title") ?: "").trim()
            if (raw.isNotEmpty() && !raw.contains("://")) {
                val (artist, title) = splitIcy(raw)
                if (title != s.title || artist != s.artist) _state.update { it.copy(title = title.ifBlank { it.station }, artist = artist) }
            }
        }

        val stalled = wantPlaying && (idle || eof) && System.currentTimeMillis() - loadedAt > 1500
        when {
            // The stream stopped by itself (it dropped, or never started).
            stalled && s.isLive -> retryLive()
            // The last song finished: stay on it, paused at the end.
            stalled && eof -> { wantPlaying = false; reportStopped(finished = true); saveEpisode(force = true, finished = true); _state.update { it.copy(isBuffering = false) } }
            stalled -> { wantPlaying = false; _state.update { it.copy(isBuffering = false, error = if (s.positionMs == 0L) "Couldn't play this" else null) } }
        }
    }

    /**
     * A radio stream that drops (a network blip) reconnects by itself, backing off to every 30s,
     * for about five minutes, as on the phone. Pausing or picking something else cancels it.
     */
    private fun retryLive() {
        if (retryJob?.isActive == true) return
        val e = _state.value.entry ?: return
        if (retries >= 14) { wantPlaying = false; _state.update { it.copy(error = "Couldn't play this station", isBuffering = false) }; return }
        if (retries == 1) _state.update { it.copy(error = "Reconnecting…") }
        val wait = minOf(2_000L shl minOf(retries, 4), 30_000L)
        retries++
        retryJob = scope.launch {
            delay(wait)
            if (wantPlaying && _state.value.entry?.key == e.key) { loadedAt = System.currentTimeMillis(); mpv?.command("loadfile", e.id, "replace") }
        }
    }

    /** ABC's HLS has no ICY titles, so poll their API while an ABC station is on. */
    private fun followAbc(e: Entry) {
        abcJob?.cancel()
        val slug = e.abc ?: return
        abcJob = scope.launch {
            var last: Abc.NowPlaying? = null
            while (isActive) {
                val np = runCatching { Abc.nowPlaying(slug) }.getOrNull()
                if (np != null && np != last && _state.value.entry?.key == e.key) {
                    last = np
                    _state.update { it.copy(title = np.title.ifBlank { it.station }, artist = np.artist, art = np.art ?: e.art) }
                }
                delay(20_000)
            }
        }
    }

    /** ICY titles are usually "Artist - Title". */
    fun splitIcy(raw: String): Pair<String, String> {
        val i = raw.indexOf(" - ")
        return if (i > 0) raw.substring(0, i).trim() to raw.substring(i + 3).trim() else "" to raw
    }

    fun shutdown() { reportStopped(); saveEpisode(force = true); mpv?.close(); mpv = null }
}
