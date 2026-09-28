package com.cappyjune.muse.desktop.data

import kotlinx.serialization.Serializable

/** Anything playable, from any source. `id` is the stream URL for radio. Same shape as the phone's. */
@Serializable
data class Entry(
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val art: String = "",
    /** ABC now-playing slug; set only for ABC stations, whose HLS carries no ICY titles. */
    val abc: String? = null,
    val codec: String = "",
    val bitrate: Int = 0,
    val playedAt: Long = 0,
    /** radio-browser's stationuuid: stable even when a station moves its stream URL. */
    val rbId: String? = null,
    /** Where a search result came from, shown as a label when adding stations. */
    val source: String = "",
    /** TuneIn's Tune.ashx link: resolved to a real stream only when needed. */
    val tuneUrl: String? = null,
    val durationMs: Long = 0,
    /** ReplayGain/NormalizationGain in dB, for volume levelling. */
    val gainDb: Double? = null,
) {
    @Serializable
    enum class Kind { RADIO, MUSIC, PODCAST, LOCAL }

    val key get() = "$kind|${rbId ?: id}"
}
